package aspm.app.secrets;

import aspm.sharedkernel.secrets.SecretReference;
import aspm.sharedkernel.secrets.SecretsProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code azkv:<secret-name>[/<version>]} — Azure Key Vault secrets, REST API 7.4. {@code SEC-SEC-023},
 * {@code SEC-SEC-026}, {@code PRD-CON-021}.
 *
 * <h2>Authentication options</h2>
 *
 * <ul>
 *   <li><b>Workload identity</b> ({@code AZURE_FEDERATED_TOKEN_FILE}, {@code AZURE_CLIENT_ID},
 *       {@code AZURE_TENANT_ID} — the variables the AKS workload-identity webhook injects): the pod's
 *       projected token is presented as a client assertion. Nothing long-lived is stored anywhere. This
 *       is the mesh-native default on AKS.
 *   <li><b>Client credentials</b> ({@code ASPM_AZURE_CLIENT_SECRET}, or {@code _REF} through a mounted
 *       file): an application registration with a secret, for everything that is not AKS.
 * </ul>
 *
 * <h2>Per-tenant namespace</h2>
 *
 * <p>Key Vault secret names allow only letters, digits and dashes, so the tenant prefix is
 * {@code aspm-<tenant id without dashes>-}. {@link #resolve} refuses a name outside the resolving
 * tenant's prefix before any request is made. One vault per deployment is assumed; a group that wants
 * one vault per tenant runs one deployment per tenant, which is DOC-15 §3.2 and changes nothing here.
 */
public final class AzureKeyVaultSecretsProvider implements SecretsProvider {

    public static final String KIND = "azkv";
    public static final String VAULT_NAME = "ASPM_AZKV_VAULT";
    public static final String AUTHORITY = "ASPM_AZURE_AUTHORITY";
    public static final String TENANT = "AZURE_TENANT_ID";
    public static final String CLIENT_ID = "AZURE_CLIENT_ID";
    public static final String CLIENT_SECRET = "ASPM_AZURE_CLIENT_SECRET";
    public static final String FEDERATED_TOKEN_FILE = "AZURE_FEDERATED_TOKEN_FILE";
    static final String API_VERSION = "7.4";
    static final String SCOPE = "https://vault.azure.net/.default";

    private final URI vault;
    private final URI authority;
    private final String azureTenant;
    private final String clientId;
    private final Optional<char[]> clientSecret;
    private final Optional<Path> federatedTokenFile;

    private volatile Token token;

    private record Token(String value, Instant expiresAt) {
        boolean fresh(Instant now) {
            return now.isBefore(expiresAt.minusSeconds(60));
        }
    }

    public AzureKeyVaultSecretsProvider(URI vault, URI authority, String azureTenant, String clientId,
            Optional<char[]> clientSecret, Optional<Path> federatedTokenFile) {
        this.vault = Objects.requireNonNull(vault);
        this.authority = Objects.requireNonNull(authority);
        this.azureTenant = requireText(azureTenant, TENANT);
        this.clientId = requireText(clientId, CLIENT_ID);
        this.clientSecret = clientSecret;
        this.federatedTokenFile = federatedTokenFile;
        if (clientSecret.isEmpty() && federatedTokenFile.isEmpty()) {
            throw new IllegalStateException("the Azure Key Vault provider needs " + CLIENT_SECRET + " (or _REF) or "
                    + FEDERATED_TOKEN_FILE + "; with neither it cannot obtain a token");
        }
    }

    public static AzureKeyVaultSecretsProvider from(Map<String, String> environment, Optional<char[]> resolvedSecret) {
        String name = requireText(environment.get(VAULT_NAME), VAULT_NAME);
        if (!name.matches("[A-Za-z0-9-]{3,24}")) {
            throw new IllegalStateException(VAULT_NAME + " must be the vault NAME, not a URL");
        }
        Optional<char[]> secret = resolvedSecret.or(() -> Optional.ofNullable(environment.get(CLIENT_SECRET))
                .filter(s -> !s.isBlank()).map(String::toCharArray));
        return new AzureKeyVaultSecretsProvider(
                URI.create("https://" + name.toLowerCase(Locale.ROOT) + ".vault.azure.net"),
                SecretsHttp.httpsBase(environment.getOrDefault(AUTHORITY, "https://login.microsoftonline.com"), AUTHORITY),
                environment.get(TENANT), environment.get(CLIENT_ID), secret,
                Optional.ofNullable(environment.get(FEDERATED_TOKEN_FILE)).filter(f -> !f.isBlank()).map(Path::of));
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(what + " is not configured");
        }
        return value.strip();
    }

    @Override
    public String kind() {
        return KIND;
    }

    static String tenantPrefix(UUID tenantId) {
        return "aspm-" + tenantId.toString().replace("-", "") + "-";
    }

    static String secretName(UUID tenantId, String namespace, String name) {
        return tenantPrefix(tenantId) + namespace.replace('_', '-') + "-" + name.replace('_', '-');
    }

    @Override
    public Optional<char[]> resolve(UUID tenantId, SecretReference reference) {
        if (!KIND.equals(reference.provider()) || !reference.path().startsWith(tenantPrefix(tenantId))
                || !reference.path().matches("[A-Za-z0-9-]+(/[A-Za-z0-9]+)?")) {
            return Optional.empty();
        }
        try {
            SecretsHttp.Reply reply = SecretsHttp.getJson(
                    vault.resolve("/secrets/" + reference.path() + "?api-version=" + API_VERSION), bearer());
            if (!reply.ok()) {
                return Optional.empty();
            }
            Object value = reply.json().get("value");
            return value instanceof String s && !s.isEmpty() ? Optional.of(s.toCharArray()) : Optional.empty();
        } catch (SecretsException e) {
            System.getLogger("aspm.secrets").log(System.Logger.Level.ERROR,
                    "key vault resolution failed: " + e.code());
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
        String secretName = secretName(tenantId, namespace, name);
        SecretsHttp.Reply reply = SecretsHttp.putJson(
                vault.resolve("/secrets/" + secretName + "?api-version=" + API_VERSION), bearer(),
                Map.of("value", new String(value)));
        if (!reply.ok()) {
            throw new SecretsException("STORE_FAILED", "key vault answered HTTP " + reply.status());
        }
        // The id is https://<vault>/secrets/<name>/<version>; the version pins the reference so a
        // later rotation writes a new version and the old reference keeps resolving until destroyed
        // (PRD-CON-022's overlap).
        Object id = reply.json().get("id");
        String version = "";
        if (id instanceof String s) {
            int slash = s.lastIndexOf('/');
            String tail = slash > 0 ? s.substring(slash + 1) : "";
            if (tail.matches("[A-Za-z0-9]+") && !tail.equalsIgnoreCase(secretName)) {
                version = "/" + tail;
            }
        }
        return new SecretReference(KIND, secretName + version);
    }

    @Override
    public void destroy(UUID tenantId, SecretReference reference) {
        if (!KIND.equals(reference.provider()) || !reference.path().startsWith(tenantPrefix(tenantId))) {
            return;
        }
        String name = reference.path().contains("/")
                ? reference.path().substring(0, reference.path().indexOf('/')) : reference.path();
        SecretsHttp.Reply reply = SecretsHttp.delete(
                vault.resolve("/secrets/" + name + "?api-version=" + API_VERSION), bearer());
        if (!reply.ok() && reply.status() != 404) {
            throw new SecretsException("DESTROY_FAILED", "key vault answered HTTP " + reply.status());
        }
        // Soft-delete: Key Vault retains the secret for its configured retention and purge is a separate,
        // separately-permissioned call. That is OPS-DEP-022's dual control arriving from the provider
        // side, and the platform does not purge on its own.
    }

    @Override
    public String description() {
        return "azkv (" + vault.getHost() + ", auth " + (federatedTokenFile.isPresent()
                ? "workload identity" : "client credentials") + "; per-tenant name prefix aspm-<tenant>-)";
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
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", clientId);
            form.put("scope", SCOPE);
            form.put("grant_type", "client_credentials");
            if (federatedTokenFile.isPresent()) {
                try {
                    form.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
                    form.put("client_assertion",
                            Files.readString(federatedTokenFile.get(), StandardCharsets.UTF_8).strip());
                } catch (java.io.IOException e) {
                    throw new SecretsException("NO_WORKLOAD_IDENTITY", "the federated token file is unreadable");
                }
            } else {
                form.put("client_secret", new String(clientSecret.orElseThrow()));
            }
            SecretsHttp.Reply reply = SecretsHttp.postForm(
                    authority.resolve("/" + azureTenant + "/oauth2/v2.0/token"), Map.of(), form);
            if (!reply.ok()) {
                throw new SecretsException("LOGIN_FAILED", "the token endpoint answered HTTP " + reply.status());
            }
            Map<String, Object> body = reply.json();
            if (!(body.get("access_token") instanceof String access)) {
                throw new SecretsException("LOGIN_FAILED", "the token endpoint returned no access token");
            }
            long ttl = body.get("expires_in") instanceof Number n ? n.longValue() : 300L;
            token = new Token(access, now.plusSeconds(ttl));
            return access;
        }
    }
}
