package aspm.app.secrets;

import aspm.sharedkernel.secrets.SecretReference;
import aspm.sharedkernel.secrets.SecretsProvider;
import aspm.app.assessment.CredentialCustody;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The secrets store as the platform sees it: an ordered set of providers selected by configuration,
 * one of them designated to receive writes. ADR-052. {@code SEC-SEC-023}, {@code SEC-SEC-024},
 * {@code OPS-DEP-019}, {@code OPS-DEP-020}, {@code PRD-CON-021}.
 *
 * <h2>Configuration</h2>
 *
 * <pre>
 * ASPM_SECRETS_PROVIDERS   comma list, e.g. "file,vault" or "file,sealed" (default) — the adapters
 *                          enabled, in resolution order. "env" is appended automatically in a
 *                          development environment and refused elsewhere without ASPM_SECRETS_ALLOW_ENV.
 * ASPM_SECRETS_WRITER      which adapter receives tenant-entered secrets; default: the first writable
 *                          one in the list. Read-only adapters (file, env) cannot be the writer.
 * </pre>
 *
 * <p>Deployment-level bootstrap values may themselves be references into a read-only adapter:
 * {@code ASPM_CREDENTIAL_KEY_REF=file:credential-key}, {@code ASPM_VAULT_TOKEN_REF=file:vault-token},
 * {@code ASPM_AZURE_CLIENT_SECRET_REF}, {@code ASPM_AWS_SECRET_ACCESS_KEY_REF}. They are resolved once, at
 * start, through the read-only adapters, which is how a Kubernetes-mounted file reaches an adapter that
 * needs a token to exist — without that token living in the environment.
 *
 * <h2>Two resolution paths, deliberately</h2>
 *
 * <p>{@link #resolveTenant} serves references stored in a TENANT's configuration and refuses any adapter
 * that is not tenant-scoped. {@link #resolveDeployment} serves references the OPERATOR placed in the
 * environment and may use any adapter. The split exists because a tenant administrator can type any
 * string into a configuration form, and if that string could name a deployment secret, "send a test
 * notification to my SMTP server" would be an exfiltration primitive. Services that hold tenant
 * configuration call the first; only {@code AspmApplication} and the delivery worker call the second,
 * with references from the environment.
 */
public final class Secrets {

    public static final String PROVIDERS = "ASPM_SECRETS_PROVIDERS";
    public static final String WRITER = "ASPM_SECRETS_WRITER";
    public static final String CREDENTIAL_KEY_REF = "ASPM_CREDENTIAL_KEY_REF";

    private final Map<String, SecretsProvider> providers;
    private final Optional<SecretsProvider> writer;
    private final CredentialCustody custody;

    Secrets(List<SecretsProvider> ordered, Optional<SecretsProvider> writer, CredentialCustody custody) {
        Map<String, SecretsProvider> byKind = new LinkedHashMap<>();
        for (SecretsProvider p : ordered) {
            if (byKind.putIfAbsent(p.kind(), p) != null) {
                throw new IllegalStateException("two secrets providers answer to '" + p.kind() + "'");
            }
        }
        this.providers = Map.copyOf(byKind);
        this.writer = writer;
        this.custody = Objects.requireNonNull(custody);
        writer.ifPresent(w -> {
            if (!w.writable()) {
                throw new IllegalStateException("secrets writer '" + w.kind() + "' is read-only");
            }
        });
    }

    /**
     * Builds the store from the environment. Read-only adapters come first so that every {@code _REF}
     * bootstrap value can be resolved before the adapter that needs it is constructed.
     */
    public static Secrets fromEnvironment(Map<String, String> environment, DataSource dataSource) {
        Objects.requireNonNull(environment);
        boolean development = "development".equalsIgnoreCase(environment.getOrDefault("ASPM_ENVIRONMENT", ""));
        List<String> kinds = new ArrayList<>();
        for (String part : environment.getOrDefault(PROVIDERS, "file,sealed").split(",")) {
            String k = part.strip().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && !kinds.contains(k)) {
                kinds.add(k);
            }
        }
        if (development && !kinds.contains(EnvSecretsProvider.KIND)) {
            kinds.add(EnvSecretsProvider.KIND);
        }

        // Stage 1: the read-only adapters, which need nothing.
        List<SecretsProvider> bootstrap = new ArrayList<>();
        for (String k : kinds) {
            switch (k) {
                case FileSecretsProvider.KIND -> bootstrap.add(FileSecretsProvider.from(environment));
                case EnvSecretsProvider.KIND -> bootstrap.add(EnvSecretsProvider.from(environment));
                default -> { }
            }
        }
        Secrets stage1 = new Secrets(bootstrap, Optional.empty(), CredentialCustody.from(Map.of()));

        // The sealing key: inline (compose) or by reference (mounted file).
        Map<String, String> custodyEnvironment = new LinkedHashMap<>();
        Optional<char[]> keyByRef = stage1.deploymentRef(environment, CREDENTIAL_KEY_REF);
        if (keyByRef.isPresent()) {
            custodyEnvironment.put(CredentialCustody.KEY_VARIABLE, new String(keyByRef.get()));
        } else if (environment.get(CredentialCustody.KEY_VARIABLE) != null) {
            custodyEnvironment.put(CredentialCustody.KEY_VARIABLE, environment.get(CredentialCustody.KEY_VARIABLE));
        }
        CredentialCustody custody = CredentialCustody.from(custodyEnvironment);
        // Every other holder of sealed material (provider keys, test-account custody) reads the same key.
        CredentialCustody.bindDeployment(custody);

        // Stage 2: everything, in the configured order.
        List<SecretsProvider> all = new ArrayList<>();
        for (String k : kinds) {
            all.add(switch (k) {
                case FileSecretsProvider.KIND -> FileSecretsProvider.from(environment);
                case EnvSecretsProvider.KIND -> EnvSecretsProvider.from(environment);
                case SealedSecretsProvider.KIND -> new SealedSecretsProvider(dataSource, custody);
                case VaultSecretsProvider.KIND -> VaultSecretsProvider.from(environment,
                        stage1.deploymentRef(environment, VaultSecretsProvider.TOKEN + "_REF"));
                case AzureKeyVaultSecretsProvider.KIND -> AzureKeyVaultSecretsProvider.from(environment,
                        stage1.deploymentRef(environment, AzureKeyVaultSecretsProvider.CLIENT_SECRET + "_REF"));
                case AwsSecretsManagerProvider.KIND -> AwsSecretsManagerProvider.from(environment,
                        stage1.deploymentRef(environment, "ASPM_AWS_SECRET_ACCESS_KEY_REF"));
                case GcpSecretManagerProvider.KIND -> GcpSecretManagerProvider.from(environment);
                default -> throw new IllegalStateException("unknown secrets provider '" + k + "' in " + PROVIDERS
                        + "; the options are file, sealed, vault, azkv, awssm, gcpsm and (development only) env");
            });
        }

        Optional<SecretsProvider> writer;
        String configuredWriter = environment.getOrDefault(WRITER, "").strip().toLowerCase(Locale.ROOT);
        if (!configuredWriter.isEmpty()) {
            writer = all.stream().filter(p -> p.kind().equals(configuredWriter)).findFirst();
            if (writer.isEmpty()) {
                throw new IllegalStateException(WRITER + "=" + configuredWriter + " names a provider that is not in "
                        + PROVIDERS);
            }
        } else {
            writer = all.stream().filter(SecretsProvider::writable).findFirst();
        }
        return new Secrets(all, writer, custody);
    }

    /**
     * Resolves every {@code <NAME>_REF} deployment variable into {@code <NAME>} in a COPY of the
     * environment, through the read-only adapters — so the database password, the object-store
     * credential and the bootstrap password can be mounted files ({@code OPS-DEP-019},
     * {@code OPS-DEP-020}) while every consumer keeps reading the variable it always read. The copy is
     * in-memory only; the process environment never carries the values. A reference that does not
     * resolve stops the start, as {@link #fromEnvironment} does for its own bootstrap references.
     */
    public static Map<String, String> expandDeploymentReferences(Map<String, String> environment) {
        Map<String, String> expanded = new LinkedHashMap<>(environment);
        List<SecretsProvider> readOnly = new ArrayList<>();
        readOnly.add(FileSecretsProvider.from(environment));
        if ("development".equalsIgnoreCase(environment.getOrDefault("ASPM_ENVIRONMENT", ""))
                || "true".equalsIgnoreCase(environment.getOrDefault(EnvSecretsProvider.ALLOW_VARIABLE, ""))) {
            readOnly.add(EnvSecretsProvider.from(environment));
        }
        Secrets bootstrap = new Secrets(readOnly, Optional.empty(), CredentialCustody.from(Map.of()));
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey();
            if (!name.endsWith("_REF") || name.equals(CREDENTIAL_KEY_REF) || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            String target = name.substring(0, name.length() - "_REF".length());
            if (environment.containsKey(target) && !environment.get(target).isBlank()) {
                throw new IllegalStateException(target + " and " + name + " are both set; a value and a reference to a value "
                        + "cannot both be the truth");
            }
            if (!SecretReference.looksLikeReference(entry.getValue())) {
                continue;   // ASPM_VAULT_TOKEN_REF and friends are handled by fromEnvironment; unknown suffixes are left alone
            }
            SecretReference reference = SecretReference.parse(entry.getValue());
            Optional<char[]> value = bootstrap.resolveDeployment(reference);
            if (value.isEmpty()) {
                throw new IllegalStateException(name + "=" + reference + " does not resolve. Refusing to start rather than "
                        + "running without the secret it names.");
            }
            expanded.put(target, new String(value.get()));
        }
        return Map.copyOf(expanded);
    }

    /** The deployment sealing key material, for the code paths that seal directly today. */
    public CredentialCustody custody() {
        return custody;
    }

    /** Whether tenant-entered secrets can be accepted at all. */
    public boolean writable() {
        return writer.isPresent() && writer.get().writable();
    }

    /** A reference held in a tenant's configuration. Refuses deployment-level adapters. */
    public Optional<char[]> resolveTenant(UUID tenantId, SecretReference reference) {
        Objects.requireNonNull(tenantId);
        SecretsProvider provider = providers.get(reference.provider());
        if (provider == null || !provider.tenantScoped()) {
            return Optional.empty();
        }
        return provider.resolve(tenantId, reference);
    }

    /** A reference the operator placed in the environment. Any enabled adapter. */
    public Optional<char[]> resolveDeployment(SecretReference reference) {
        SecretsProvider provider = providers.get(reference.provider());
        if (provider == null) {
            return Optional.empty();
        }
        // Deployment secrets in a tenant-scoped provider live under the nil tenant's prefix, so an
        // operator can keep them in the same vault under a path no tenant reference can reach.
        return provider.resolve(new UUID(0L, 0L), reference);
    }

    /** Stores a tenant-entered secret with the configured writer. */
    public SecretReference store(UUID tenantId, String namespace, String name, char[] value) {
        SecretsProvider w = writer.orElseThrow(() -> new SecretsProvider.SecretsException("NO_WRITABLE_PROVIDER",
                "this deployment has no writable secrets provider, so it cannot hold a secret; configure "
                        + PROVIDERS + " with sealed (plus " + CredentialCustody.KEY_VARIABLE + ") or a vault"));
        return w.store(tenantId, namespace, name, value);
    }

    /** Destroys a tenant secret wherever it lives. */
    public void destroy(UUID tenantId, SecretReference reference) {
        SecretsProvider provider = providers.get(reference.provider());
        if (provider != null && provider.tenantScoped()) {
            provider.destroy(tenantId, reference);
        }
    }

    /** For the startup banner: one line per adapter, never a value. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (SecretsProvider p : providers.values()) {
            lines.add((writer.isPresent() && writer.get() == p ? "* " : "  ") + p.description());
        }
        if (writer.isEmpty()) {
            lines.add("  (no writable provider: tenant-entered secrets will be refused)");
        }
        return lines;
    }

    /** Resolves {@code VARIABLE_REF} from the environment through the read-only adapters, if set. */
    private Optional<char[]> deploymentRef(Map<String, String> environment, String variable) {
        String ref = environment.get(variable);
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        SecretReference reference = SecretReference.parse(ref);
        Optional<char[]> value = resolveDeployment(reference);
        if (value.isEmpty()) {
            throw new IllegalStateException(variable + "=" + reference + " does not resolve. Refusing to start "
                    + "rather than running without the secret it names.");
        }
        return value;
    }
}
