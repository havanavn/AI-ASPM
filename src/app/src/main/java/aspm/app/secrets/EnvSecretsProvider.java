package aspm.app.secrets;

import aspm.sharedkernel.secrets.SecretReference;
import aspm.sharedkernel.secrets.SecretsProvider;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code env:<VARIABLE>} — the process environment. Read-only, deployment-level, and refused in
 * production unless the operator says so in writing. {@code OPS-DEP-020}.
 *
 * <p>This adapter exists because a local compose stack has nothing else, and because {@code OPS-DEP-020}
 * is the requirement it violates: "long-lived process environment variables MUST NOT hold secrets". So
 * it constructs in a development environment, and in any other environment only when
 * {@code ASPM_SECRETS_ALLOW_ENV=true} is set — a second variable whose only purpose is to make the
 * exception visible in the deployment's configuration rather than implied by the absence of a vault.
 *
 * <p>It is not tenant-scoped: a variable belongs to the process, not to a tenant, so this adapter is
 * for deployment secrets (a relay password, a bootstrap token) and a tenant-entered secret is never
 * stored here — {@link #writable()} is false and {@link #store} refuses.
 */
public final class EnvSecretsProvider implements SecretsProvider {

    public static final String KIND = "env";
    public static final String ALLOW_VARIABLE = "ASPM_SECRETS_ALLOW_ENV";

    private final Map<String, String> environment;

    private EnvSecretsProvider(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    /**
     * @throws IllegalStateException outside a development environment without the explicit allow flag,
     *     so a production deployment that copied a development file fails to start rather than reading
     *     secrets from where the requirement forbids them
     */
    public static EnvSecretsProvider from(Map<String, String> environment) {
        Objects.requireNonNull(environment);
        boolean development = "development".equalsIgnoreCase(environment.getOrDefault("ASPM_ENVIRONMENT", ""));
        boolean allowed = "true".equalsIgnoreCase(environment.getOrDefault(ALLOW_VARIABLE, ""));
        if (!development && !allowed) {
            throw new IllegalStateException("the env secrets provider is enabled outside a development "
                    + "environment. OPS-DEP-020 forbids secrets in long-lived process environment variables; "
                    + "either remove 'env' from ASPM_SECRETS_PROVIDERS or set " + ALLOW_VARIABLE
                    + "=true to record that this deployment accepts the exception.");
        }
        return new EnvSecretsProvider(environment);
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Optional<char[]> resolve(UUID tenantId, SecretReference reference) {
        if (!KIND.equals(reference.provider()) || !reference.path().matches("[A-Z][A-Z0-9_]{0,127}")) {
            return Optional.empty();
        }
        String value = environment.get(reference.path());
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value.toCharArray());
    }

    @Override
    public boolean writable() {
        return false;
    }

    @Override
    public boolean tenantScoped() {
        return false;
    }

    @Override
    public SecretReference store(UUID tenantId, String namespace, String name, char[] value) {
        throw new SecretsException("READ_ONLY_PROVIDER",
                "the process environment cannot hold a tenant-entered secret; configure a writable provider");
    }

    @Override
    public void destroy(UUID tenantId, SecretReference reference) {
        // Nothing to destroy: the value is the operator's to remove from the environment.
    }

    @Override
    public String description() {
        return "env (process environment; deployment secrets only; OPS-DEP-020 exception recorded)";
    }
}
