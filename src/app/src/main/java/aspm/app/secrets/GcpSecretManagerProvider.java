package aspm.app.secrets;

import aspm.sharedkernel.secrets.SecretReference;
import aspm.sharedkernel.secrets.SecretsProvider;
import aspm.app.runtime.Json;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code gcpsm:<secret id>/<version>} — Google Cloud Secret Manager, REST v1. {@code SEC-SEC-023},
 * {@code SEC-SEC-026}, {@code PRD-CON-021}.
 *
 * <h2>Credential options</h2>
 *
 * <ul>
 *   <li><b>Workload identity</b> (no configuration): the GKE metadata server issues an access token for
 *       the pod's Kubernetes service account. Nothing long-lived exists. The metadata host is the one
 *       destination this tier reaches over plain http, because that is the only way it is reachable
 *       and it is link-local to the node, not the network.
 *   <li><b>Service-account key</b> ({@code GOOGLE_APPLICATION_CREDENTIALS} pointing at the JSON key
 *       file, normally a mounted secret): a self-signed RS256 JWT is exchanged for an access token at
 *       the key's token URI. For everything that is not GKE.
 * </ul>
 *
 * <p>Secret ids allow letters, digits, dashes and underscores, so the tenant prefix is
 * {@code aspm-<tenant id without dashes>-}, and {@link #resolve} refuses an id outside it before any
 * request. One project per deployment.
 */
public final class GcpSecretManagerProvider implements SecretsProvider {

    public static final String KIND = "gcpsm";
    public static final String PROJECT = "ASPM_GCPSM_PROJECT";
    public static final String CREDENTIALS_FILE = "GOOGLE_APPLICATION_CREDENTIALS";
    public static final String METADATA_TOKEN_URL = "ASPM_GCP_METADATA_TOKEN_URL";
    static final String DEFAULT_METADATA_TOKEN_URL =
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token";
    static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private final String project;
    private final URI api;
    private final URI metadataTokenUrl;
    private final Optional<Path> serviceAccountKeyFile;

    private volatile Token token;

    private record Token(String value, Instant expiresAt) {
        boolean fresh(Instant now) {
            return now.isBefore(expiresAt.minusSeconds(60));
        }
    }

    public GcpSecretManagerProvider(String project, URI api, URI metadataTokenUrl, Optional<Path> serviceAccountKeyFile) {
        this.project = Objects.requireNonNull(project);
        this.api = Objects.requireNonNull(api);
        this.metadataTokenUrl = Objects.requireNonNull(metadataTokenUrl);
        this.serviceAccountKeyFile = serviceAccountKeyFile;
        if (!project.matches("[a-z][a-z0-9-]{4,28}[a-z0-9]")) {
            throw new IllegalStateException(PROJECT + " must be a project id");
        }
    }

    public static GcpSecretManagerProvider from(Map<String, String> environment) {
        String project = Optional.ofNullable(environment.get(PROJECT)).filter(p -> !p.isBlank())
                .orElseThrow(() -> new IllegalStateException(PROJECT + " is not configured"));
        return new GcpSecretManagerProvider(project, URI.create("https://secretmanager.googleapis.com"),
                URI.create(environment.getOrDefault(METADATA_TOKEN_URL, DEFAULT_METADATA_TOKEN_URL)),
                Optional.ofNullable(environment.get(CREDENTIALS_FILE)).filter(f -> !f.isBlank()).map(Path::of));
    }

    @Override
    public String kind() {
        return KIND;
    }

    static String tenantPrefix(UUID tenantId) {
        return "aspm-" + tenantId.toString().replace("-", "") + "-";
    }

    @Override
    public Optional<char[]> resolve(UUID tenantId, SecretReference reference) {
        if (!KIND.equals(reference.provider()) || !reference.path().startsWith(tenantPrefix(tenantId))
                || !reference.path().matches("[A-Za-z0-9_-]+/(latest|\\d+)")) {
            return Optional.empty();
        }
        int slash = reference.path().indexOf('/');
        String secretId = reference.path().substring(0, slash);
        String version = reference.path().substring(slash + 1);
        try {
            SecretsHttp.Reply reply = SecretsHttp.getJson(api.resolve("/v1/projects/" + project + "/secrets/"
                    + secretId + "/versions/" + version + ":access"), bearer());
            if (!reply.ok()) {
                return Optional.empty();
            }
            Object payload = reply.json().get("payload");
            if (payload instanceof Map<?, ?> p && p.get("data") instanceof String data) {
                String value = new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8);
                return value.isEmpty() ? Optional.empty() : Optional.of(value.toCharArray());
            }
            return Optional.empty();
        } catch (SecretsException | IllegalArgumentException e) {
            System.getLogger("aspm.secrets").log(System.Logger.Level.ERROR,
                    "secret manager resolution failed: " + e.getClass().getSimpleName());
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
        String secretId = tenantPrefix(tenantId) + namespace + "-" + name;
        SecretsHttp.Reply created = SecretsHttp.postJson(
                api.resolve("/v1/projects/" + project + "/secrets?secretId=" + secretId), bearer(),
                Map.of("replication", Map.of("automatic", Map.of())));
        if (!created.ok() && created.status() != 409) {
            throw new SecretsException("STORE_FAILED", "secret manager answered HTTP " + created.status());
        }
        String data = Base64.getEncoder().encodeToString(new String(value).getBytes(StandardCharsets.UTF_8));
        SecretsHttp.Reply added = SecretsHttp.postJson(
                api.resolve("/v1/projects/" + project + "/secrets/" + secretId + ":addVersion"), bearer(),
                Map.of("payload", Map.of("data", data)));
        if (!added.ok()) {
            throw new SecretsException("STORE_FAILED", "secret manager answered HTTP " + added.status());
        }
        String version = "latest";
        if (added.json().get("name") instanceof String versionName) {
            int slash = versionName.lastIndexOf('/');
            String tail = slash > 0 ? versionName.substring(slash + 1) : "";
            if (tail.matches("\\d+")) {
                version = tail;
            }
        }
        return new SecretReference(KIND, secretId + "/" + version);
    }

    @Override
    public void destroy(UUID tenantId, SecretReference reference) {
        if (!KIND.equals(reference.provider()) || !reference.path().startsWith(tenantPrefix(tenantId))) {
            return;
        }
        String secretId = reference.path().contains("/")
                ? reference.path().substring(0, reference.path().indexOf('/')) : reference.path();
        SecretsHttp.Reply reply = SecretsHttp.delete(
                api.resolve("/v1/projects/" + project + "/secrets/" + secretId), bearer());
        if (!reply.ok() && reply.status() != 404) {
            throw new SecretsException("DESTROY_FAILED", "secret manager answered HTTP " + reply.status());
        }
    }

    @Override
    public String description() {
        return "gcpsm (project " + project + ", auth " + (serviceAccountKeyFile.isPresent()
                ? "service-account key" : "workload identity via metadata server") + "; per-tenant id prefix aspm-<tenant>-)";
    }

    private Map<String, String> bearer() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + accessToken());
        return h;
    }

    private String accessToken() {
        Token current = token;
        Instant now = Instant.now();
        if (current != null && current.fresh(now)) {
            return current.value();
        }
        synchronized (this) {
            current = token;
            if (current != null && current.fresh(now)) {
                return current.value();
            }
            Map<String, Object> body;
            if (serviceAccountKeyFile.isPresent()) {
                body = exchangeServiceAccountJwt(now);
            } else {
                SecretsHttp.Reply reply = SecretsHttp.getJson(metadataTokenUrl, Map.of("Metadata-Flavor", "Google"));
                if (!reply.ok()) {
                    throw new SecretsException("LOGIN_FAILED", "the metadata server answered HTTP " + reply.status());
                }
                body = reply.json();
            }
            if (!(body.get("access_token") instanceof String access)) {
                throw new SecretsException("LOGIN_FAILED", "no access token was returned");
            }
            long ttl = body.get("expires_in") instanceof Number n ? n.longValue() : 300L;
            token = new Token(access, now.plusSeconds(ttl));
            return access;
        }
    }

    private Map<String, Object> exchangeServiceAccountJwt(Instant now) {
        Map<String, Object> key;
        try {
            key = Json.readObject(Files.readString(serviceAccountKeyFile.orElseThrow(), StandardCharsets.UTF_8));
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new SecretsException("NO_CREDENTIALS", "the service-account key file is unreadable");
        }
        String email = String.valueOf(key.get("client_email"));
        String tokenUri = String.valueOf(key.getOrDefault("token_uri", "https://oauth2.googleapis.com/token"));
        String pem = String.valueOf(key.get("private_key"));
        String header = SecretsHttp.base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", email);
        claims.put("scope", SCOPE);
        claims.put("aud", tokenUri);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.getEpochSecond() + 3600);
        String payload = SecretsHttp.base64Url(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        String jwt = signingInput + "." + SecretsHttp.base64Url(rs256(pem, signingInput));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.put("assertion", jwt);
        SecretsHttp.Reply reply = SecretsHttp.postForm(SecretsHttp.httpsBase(tokenUri, "token_uri"), Map.of(), form);
        if (!reply.ok()) {
            throw new SecretsException("LOGIN_FAILED", "the token endpoint answered HTTP " + reply.status());
        }
        return reply.json();
    }

    static byte[] rs256(String pem, String input) {
        try {
            String body = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(input.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new SecretsException("NO_CREDENTIALS", "the service-account private key is not usable");
        }
    }
}
