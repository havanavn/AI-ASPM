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

/**
 * {@code vault:tenants/<tenant>/<namespace>/<name>} — HashiCorp Vault or OpenBao, KV version 2.
 * ADR-052's platform default vault is OpenBao; the API is the same. {@code SEC-SEC-023},
 * {@code SEC-SEC-026}, {@code PRD-CON-021}, {@code PRD-CON-022}.
 *
 * <h2>Authentication options</h2>
 *
 * <ul>
 *   <li><b>Kubernetes auth</b> ({@code ASPM_VAULT_K8S_ROLE}): the pod's projected service-account token
 *       is exchanged for a Vault token at {@code auth/<path>/login}. This is the mesh-native default —
 *       no long-lived Vault token exists anywhere, the identity is the workload's, and rotation is the
 *       platform's. The token file is re-read on every login so a projected-token refresh is honoured.
 *   <li><b>Static token</b> ({@code ASPM_VAULT_TOKEN} or, better, {@code ASPM_VAULT_TOKEN_REF} resolved
 *       through a mounted file): for compose and for enterprises that issue an AppRole-derived token by
 *       their own means. Present because the alternative is a deployment that cannot start.
 * </ul>
 *
 * <h2>Per-tenant namespace</h2>
 *
 * <p>Every path a tenant's secret is written under starts with {@code tenants/<tenant id>/}, and
 * {@link #resolve} refuses a reference whose path does not start with the resolving tenant's prefix
 * before it makes a request. The vault-side policy is expected to mirror it (one policy per mount
 * prefix), so the check here is the first of two rather than the only one — DOC-24 §6.2 entry 12.
 * Vault Enterprise / OpenBao namespaces are supported through {@code ASPM_VAULT_NAMESPACE} as a further
 * partition above the path.
 */
public final class VaultSecretsProvider implements SecretsProvider {

    public static final String KIND = "vault";
    public static final String ADDRESS = "ASPM_VAULT_ADDR";
    public static final String MOUNT = "ASPM_VAULT_MOUNT";
    public static final String NAMESPACE = "ASPM_VAULT_NAMESPACE";
    public static final String TOKEN = "ASPM_VAULT_TOKEN";
    public static final String K8S_ROLE = "ASPM_VAULT_K8S_ROLE";
    public static final String K8S_AUTH_PATH = "ASPM_VAULT_K8S_AUTH_PATH";
    public static final String K8S_JWT_FILE = "ASPM_VAULT_K8S_JWT_FILE";
    public static final String DEFAULT_K8S_JWT_FILE = "/var/run/secrets/kubernetes.io/serviceaccount/token";

    /** The KV key every value is stored under. One key per secret keeps a reference one-dimensional. */
    static final String VALUE_KEY = "value";

    private final URI address;
    private final String mount;
    private final Optional<String> vaultNamespace;
    private final Optional<char[]> staticToken;
    private final Optional<String> kubernetesRole;
    private final String kubernetesAuthPath;
    private final Path kubernetesJwtFile;

    private volatile Lease lease;

    private record Lease(String token, Instant expiresAt) {
        boolean fresh(Instant now) {
            return expiresAt == null || now.isBefore(expiresAt.minusSeconds(30));
        }
    }

    public VaultSecretsProvider(URI address, String mount, Optional<String> vaultNamespace,
            Optional<char[]> staticToken, Optional<String> kubernetesRole, String kubernetesAuthPath,
            Path kubernetesJwtFile) {
        this.address = Objects.requireNonNull(address);
        this.mount = SecretsProvider.segment(mount, "the KV mount");
        this.vaultNamespace = vaultNamespace;
        this.staticToken = staticToken;
        this.kubernetesRole = kubernetesRole;
        this.kubernetesAuthPath = kubernetesAuthPath;
        this.kubernetesJwtFile = kubernetesJwtFile;
        if (staticToken.isEmpty() && kubernetesRole.isEmpty()) {
            throw new IllegalStateException("the vault provider needs either " + TOKEN + " (or " + TOKEN
                    + "_REF) or " + K8S_ROLE + "; with neither it can authenticate to nothing");
        }
    }

    /** From environment, with the static token possibly resolved through an earlier provider. */
    public static VaultSecretsProvider from(Map<String, String> environment, Optional<char[]> resolvedToken) {
        Optional<char[]> token = resolvedToken.or(() -> Optional.ofNullable(environment.get(TOKEN))
                .filter(t -> !t.isBlank()).map(String::toCharArray));
        return new VaultSecretsProvider(
                SecretsHttp.httpsBase(environment.get(ADDRESS), ADDRESS),
                environment.getOrDefault(MOUNT, "aspm"),
                Optional.ofNullable(environment.get(NAMESPACE)).filter(n -> !n.isBlank()),
                token,
                Optional.ofNullable(environment.get(K8S_ROLE)).filter(r -> !r.isBlank()),
                environment.getOrDefault(K8S_AUTH_PATH, "kubernetes"),
                Path.of(environment.getOrDefault(K8S_JWT_FILE, DEFAULT_K8S_JWT_FILE)));
    }

    @Override
    public String kind() {
        return KIND;
    }

    static String tenantPrefix(UUID tenantId) {
        return "tenants/" + tenantId + "/";
    }

    @Override
    public Optional<char[]> resolve(UUID tenantId, SecretReference reference) {
        if (!KIND.equals(reference.provider()) || !reference.path().startsWith(tenantPrefix(tenantId))
                || !reference.path().matches("[A-Za-z0-9_./-]+") || reference.path().contains("..")) {
            return Optional.empty();
        }
        try {
            SecretsHttp.Reply reply = SecretsHttp.getJson(
                    address.resolve("/v1/" + mount + "/data/" + reference.path()), headers());
            if (!reply.ok()) {
                return Optional.empty();
            }
            Object data = reply.json().get("data");
            if (data instanceof Map<?, ?> outer && outer.get("data") instanceof Map<?, ?> inner
                    && inner.get(VALUE_KEY) instanceof String value && !value.isEmpty()) {
                return Optional.of(value.toCharArray());
            }
            return Optional.empty();
        } catch (SecretsException e) {
            System.getLogger("aspm.secrets").log(System.Logger.Level.ERROR,
                    "vault resolution failed: " + e.code());
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
        String path = tenantPrefix(tenantId) + namespace + "/" + name;
        Map<String, Object> body = Map.<String, Object>of("data", Map.of(VALUE_KEY, new String(value)));
        SecretsHttp.Reply reply = SecretsHttp.postJson(address.resolve("/v1/" + mount + "/data/" + path),
                headers(), body);
        if (!reply.ok()) {
            throw new SecretsException("STORE_FAILED", "vault answered HTTP " + reply.status());
        }
        return new SecretReference(KIND, path);
    }

    @Override
    public void destroy(UUID tenantId, SecretReference reference) {
        if (!KIND.equals(reference.provider()) || !reference.path().startsWith(tenantPrefix(tenantId))) {
            return;
        }
        // Metadata delete removes every version. A soft delete of the latest version would leave the
        // value recoverable with `undelete`, which is not what "destroy" promises.
        SecretsHttp.Reply reply = SecretsHttp.delete(
                address.resolve("/v1/" + mount + "/metadata/" + reference.path()), headers());
        if (!reply.ok() && reply.status() != 404) {
            throw new SecretsException("DESTROY_FAILED", "vault answered HTTP " + reply.status());
        }
    }

    @Override
    public String description() {
        return "vault (" + address.getHost() + ", mount " + mount
                + (vaultNamespace.map(n -> ", namespace " + n).orElse(""))
                + ", auth " + (kubernetesRole.isPresent() ? "kubernetes role " + kubernetesRole.get() : "static token")
                + "; per-tenant path prefix tenants/<tenant>/)";
    }

    private Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Vault-Token", token());
        h.put("X-Vault-Request", "true");
        vaultNamespace.ifPresent(n -> h.put("X-Vault-Namespace", n));
        return h;
    }

    private String token() {
        if (kubernetesRole.isEmpty()) {
            return new String(staticToken.orElseThrow());
        }
        Lease current = lease;
        Instant now = Instant.now();
        if (current != null && current.fresh(now)) {
            return current.token();
        }
        synchronized (this) {
            current = lease;
            if (current != null && current.fresh(now)) {
                return current.token();
            }
            String jwt;
            try {
                jwt = Files.readString(kubernetesJwtFile, StandardCharsets.UTF_8).strip();
            } catch (java.io.IOException e) {
                throw new SecretsException("NO_WORKLOAD_IDENTITY", "the service-account token file is unreadable");
            }
            Map<String, String> h = new LinkedHashMap<>();
            vaultNamespace.ifPresent(n -> h.put("X-Vault-Namespace", n));
            SecretsHttp.Reply reply = SecretsHttp.postJson(
                    address.resolve("/v1/auth/" + kubernetesAuthPath + "/login"), h,
                    Map.of("role", kubernetesRole.get(), "jwt", jwt));
            if (!reply.ok()) {
                throw new SecretsException("LOGIN_FAILED", "vault kubernetes login answered HTTP " + reply.status());
            }
            Object auth = reply.json().get("auth");
            if (!(auth instanceof Map<?, ?> a) || !(a.get("client_token") instanceof String clientToken)) {
                throw new SecretsException("LOGIN_FAILED", "vault login returned no client token");
            }
            long ttl = a.get("lease_duration") instanceof Number n ? n.longValue() : 0L;
            lease = new Lease(clientToken, ttl > 0 ? now.plusSeconds(ttl) : null);
            return clientToken;
        }
    }
}
