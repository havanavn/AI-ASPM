package aspm.app.secrets;

import aspm.sharedkernel.secrets.SecretReference;
import aspm.sharedkernel.secrets.SecretsProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code awssm:aspm/<tenant>/<namespace>/<name>} — AWS Secrets Manager. {@code SEC-SEC-023},
 * {@code SEC-SEC-026}, {@code PRD-CON-021}.
 *
 * <h2>Credential options</h2>
 *
 * <ul>
 *   <li><b>Web identity</b> ({@code AWS_WEB_IDENTITY_TOKEN_FILE} + {@code AWS_ROLE_ARN} — what IRSA and
 *       EKS Pod Identity inject): the pod's projected token is exchanged at STS for temporary keys,
 *       refreshed before expiry. Nothing long-lived. This is the mesh-native default on EKS.
 *   <li><b>Static keys</b> ({@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY}, optional
 *       {@code AWS_SESSION_TOKEN}): for everything else. The secret key may arrive through a mounted
 *       file as {@code ASPM_AWS_SECRET_ACCESS_KEY_REF}.
 * </ul>
 *
 * <p>The endpoint is derived from the region and is never taken from data. Secret names carry the
 * tenant as a path prefix; {@link #resolve} refuses a name outside the resolving tenant's prefix before
 * any request, and an IAM policy on the prefix is expected to mirror it.
 */
public final class AwsSecretsManagerProvider implements SecretsProvider {

    public static final String KIND = "awssm";
    public static final String REGION = "ASPM_AWSSM_REGION";
    public static final String ACCESS_KEY_ID = "AWS_ACCESS_KEY_ID";
    public static final String SECRET_ACCESS_KEY = "AWS_SECRET_ACCESS_KEY";
    public static final String SESSION_TOKEN = "AWS_SESSION_TOKEN";
    public static final String WEB_IDENTITY_TOKEN_FILE = "AWS_WEB_IDENTITY_TOKEN_FILE";
    public static final String ROLE_ARN = "AWS_ROLE_ARN";

    private static final Pattern XML_FIELD = Pattern.compile("<(AccessKeyId|SecretAccessKey|SessionToken|Expiration)>([^<]+)</\\1>");

    private final String region;
    private final URI endpoint;
    private final URI stsEndpoint;
    private final Optional<SigV4.Credentials> staticCredentials;
    private final Optional<Path> webIdentityTokenFile;
    private final Optional<String> roleArn;

    private volatile SigV4.Credentials assumed;

    public AwsSecretsManagerProvider(String region, URI endpoint, URI stsEndpoint,
            Optional<SigV4.Credentials> staticCredentials, Optional<Path> webIdentityTokenFile,
            Optional<String> roleArn) {
        this.region = Objects.requireNonNull(region);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.stsEndpoint = Objects.requireNonNull(stsEndpoint);
        this.staticCredentials = staticCredentials;
        this.webIdentityTokenFile = webIdentityTokenFile;
        this.roleArn = roleArn;
        if (staticCredentials.isEmpty() && (webIdentityTokenFile.isEmpty() || roleArn.isEmpty())) {
            throw new IllegalStateException("the AWS Secrets Manager provider needs static keys or "
                    + WEB_IDENTITY_TOKEN_FILE + " with " + ROLE_ARN + "; with neither it cannot sign a request");
        }
    }

    public static AwsSecretsManagerProvider from(Map<String, String> environment, Optional<char[]> resolvedSecretKey) {
        String region = Optional.ofNullable(environment.get(REGION)).filter(r -> !r.isBlank())
                .orElseThrow(() -> new IllegalStateException(REGION + " is not configured"));
        if (!region.matches("[a-z]{2}(-[a-z]+)+-\\d")) {
            throw new IllegalStateException(REGION + " must be a region code such as ap-southeast-1");
        }
        Optional<char[]> secretKey = resolvedSecretKey.or(() -> Optional.ofNullable(environment.get(SECRET_ACCESS_KEY))
                .filter(s -> !s.isBlank()).map(String::toCharArray));
        Optional<SigV4.Credentials> statics = Optional.ofNullable(environment.get(ACCESS_KEY_ID))
                .filter(k -> !k.isBlank() && secretKey.isPresent())
                .map(k -> new SigV4.Credentials(k, secretKey.get(), environment.get(SESSION_TOKEN), null));
        return new AwsSecretsManagerProvider(region,
                URI.create("https://secretsmanager." + region + ".amazonaws.com/"),
                URI.create("https://sts." + region + ".amazonaws.com/"),
                statics,
                Optional.ofNullable(environment.get(WEB_IDENTITY_TOKEN_FILE)).filter(f -> !f.isBlank()).map(Path::of),
                Optional.ofNullable(environment.get(ROLE_ARN)).filter(r -> !r.isBlank()));
    }

    @Override
    public String kind() {
        return KIND;
    }

    static String tenantPrefix(UUID tenantId) {
        return "aspm/" + tenantId + "/";
    }

    @Override
    public Optional<char[]> resolve(UUID tenantId, SecretReference reference) {
        if (!KIND.equals(reference.provider()) || !reference.path().startsWith(tenantPrefix(tenantId))
                || !reference.path().matches("[A-Za-z0-9/_+=.@-]+")) {
            return Optional.empty();
        }
        try {
            SecretsHttp.Reply reply = call("GetSecretValue", Map.of("SecretId", reference.path()));
            if (!reply.ok()) {
                return Optional.empty();
            }
            Object value = reply.json().get("SecretString");
            return value instanceof String s && !s.isEmpty() ? Optional.of(s.toCharArray()) : Optional.empty();
        } catch (SecretsException e) {
            System.getLogger("aspm.secrets").log(System.Logger.Level.ERROR,
                    "secrets manager resolution failed: " + e.code());
            return Optional.empty();
        }
    }

    @Override
    public boolean writable() {
        return true;
    }

    @Override
    public SecretReference store(UUID tenantId, String namespace, String name, char[] value) {
        SecretsProvider.segment(namespace, "namespace");
        SecretsProvider.segment(name, "name");
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("nothing to store");
        }
        String secretName = tenantPrefix(tenantId) + namespace + "/" + name;
        SecretsHttp.Reply reply = call("CreateSecret", Map.of("Name", secretName, "SecretString", new String(value)));
        if (reply.status() == 400 && String.valueOf(reply.json().get("__type")).contains("ResourceExistsException")) {
            // Rotation: a new version on the existing secret. The previous version stays readable under
            // its staging label until the provider ages it out, which is PRD-CON-022's overlap.
            reply = call("PutSecretValue", Map.of("SecretId", secretName, "SecretString", new String(value)));
        }
        if (!reply.ok()) {
            throw new SecretsException("STORE_FAILED", "secrets manager answered HTTP " + reply.status());
        }
        return new SecretReference(KIND, secretName);
    }

    @Override
    public void destroy(UUID tenantId, SecretReference reference) {
        if (!KIND.equals(reference.provider()) || !reference.path().startsWith(tenantPrefix(tenantId))) {
            return;
        }
        // Scheduled deletion with the shortest recovery window the service allows. Immediate forced
        // deletion exists and is not used: OPS-DEP-022 wants destruction to be dual-controlled, and a
        // recovery window is the provider-side half of that control.
        SecretsHttp.Reply reply = call("DeleteSecret", Map.of("SecretId", reference.path(), "RecoveryWindowInDays", 7));
        if (!reply.ok() && reply.status() != 400) {
            throw new SecretsException("DESTROY_FAILED", "secrets manager answered HTTP " + reply.status());
        }
    }

    @Override
    public String description() {
        return "awssm (" + endpoint.getHost() + ", auth " + (staticCredentials.isPresent() ? "static keys" : "web identity")
                + "; per-tenant name prefix aspm/<tenant>/)";
    }

    private SecretsHttp.Reply call(String action, Map<String, ?> body) {
        String payload = aspm.app.runtime.Json.write(body);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-amz-json-1.1");
        headers.put("X-Amz-Target", "secretsmanager." + action);
        Map<String, String> signed = SigV4.sign("POST", endpoint, headers, payload, region, "secretsmanager",
                credentials(), Instant.now());
        return SecretsHttp.send("POST", endpoint, signed, payload);
    }

    private SigV4.Credentials credentials() {
        if (staticCredentials.isPresent()) {
            return staticCredentials.get();
        }
        SigV4.Credentials current = assumed;
        Instant now = Instant.now();
        if (current != null && current.fresh(now)) {
            return current;
        }
        synchronized (this) {
            current = assumed;
            if (current != null && current.fresh(now)) {
                return current;
            }
            String jwt;
            try {
                jwt = Files.readString(webIdentityTokenFile.orElseThrow(), StandardCharsets.UTF_8).strip();
            } catch (java.io.IOException e) {
                throw new SecretsException("NO_WORKLOAD_IDENTITY", "the web identity token file is unreadable");
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("Action", "AssumeRoleWithWebIdentity");
            form.put("Version", "2011-06-15");
            form.put("RoleArn", roleArn.orElseThrow());
            form.put("RoleSessionName", "aspm-secrets");
            form.put("WebIdentityToken", jwt);
            form.put("DurationSeconds", "3600");
            SecretsHttp.Reply reply = SecretsHttp.postForm(stsEndpoint, Map.of("Accept", "text/xml"), form);
            if (!reply.ok()) {
                throw new SecretsException("LOGIN_FAILED", "STS answered HTTP " + reply.status());
            }
            Map<String, String> fields = new LinkedHashMap<>();
            Matcher m = XML_FIELD.matcher(reply.body());
            while (m.find()) {
                fields.put(m.group(1), m.group(2));
            }
            if (!fields.containsKey("AccessKeyId") || !fields.containsKey("SecretAccessKey")) {
                throw new SecretsException("LOGIN_FAILED", "STS returned no credentials");
            }
            Instant expires = fields.containsKey("Expiration") ? Instant.parse(fields.get("Expiration")) : now.plusSeconds(900);
            assumed = new SigV4.Credentials(fields.get("AccessKeyId"), fields.get("SecretAccessKey").toCharArray(),
                    fields.get("SessionToken"), expires);
            return assumed;
        }
    }
}
