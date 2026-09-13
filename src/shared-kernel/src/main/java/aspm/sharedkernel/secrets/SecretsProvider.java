package aspm.sharedkernel.secrets;

import java.util.Optional;
import java.util.UUID;

/**
 * The secrets contract of ADR-052: per-tenant namespace, write, reference-based read, destruction.
 * {@code SEC-SEC-023}, {@code SEC-SEC-024}, {@code SEC-SEC-026}, {@code OPS-DEP-019}, {@code OPS-DEP-020},
 * {@code PRD-CON-021}.
 *
 * <h2>Why a contract and not a client</h2>
 *
 * <p>OQ-026 asked whether the platform provides its own vault or integrates with the enterprise's, and
 * ADR-052 answered "both" and made the contract the decision: the providers are configuration. A
 * conglomerate that already runs HashiCorp Vault, OpenBao, Azure Key Vault, AWS Secrets Manager or
 * Google Secret Manager under controls stricter than this platform can claim keeps it; a deployment
 * with none of those gets the sealed store, which encrypts under a key held outside the database and
 * is itself an asset of the highest value (DOC-06 §18.2) — which is why it is a default and not the
 * only option.
 *
 * <h2>What every adapter must hold to</h2>
 *
 * <ul>
 *   <li><b>The tenant is an argument, never derived from the reference.</b> A reference from tenant A
 *       presented under tenant B resolves to {@link Optional#empty()}, indistinguishable from a
 *       reference that never existed. Isolation path I13.
 *   <li><b>Nothing here returns a value to a caller that did not present a reference.</b> There is no
 *       list operation and no search. {@code SEC-SEC-024}'s "non-retrievable after entry" is enforced
 *       above the provider as the absence of a read path (ADR-052 gaps accepted), and a listing would be
 *       that read path.
 *   <li><b>Failure detail names the class, never the message.</b> A provider's error text can carry the
 *       resolved address, the path and occasionally the value. {@link SecretsException} carries a stable
 *       code and a class name, and the caller renders those.
 *   <li><b>Values travel as {@code char[]}</b> so a caller can zero them; a {@code String} lives in the
 *       heap until the collector gets to it. {@code OPS-DEP-020}.
 * </ul>
 */
public interface SecretsProvider {

    /** The prefix this adapter answers to in a {@link SecretReference}. */
    String kind();

    /**
     * Reads a secret by reference, under a tenant.
     *
     * @return the value, or empty when the reference does not resolve under this tenant — for any
     *     reason, because the reasons are not the caller's business
     */
    Optional<char[]> resolve(UUID tenantId, SecretReference reference);

    /** Whether this adapter accepts writes. Mounted files and process environment do not. */
    boolean writable();

    /**
     * Whether a reference from this adapter belongs to a tenant. Mounted files and the process
     * environment hold DEPLOYMENT secrets — a relay password, a bootstrap token — and are not tenant
     * scoped. the application tier's {@code Secrets} router refuses to resolve such a reference on a tenant's behalf: otherwise a tenant
     * administrator could type {@code file:db/password} into an SMTP configuration form, press "send
     * test", and receive the deployment's database password at a mail server they control.
     */
    default boolean tenantScoped() {
        return true;
    }

    /**
     * Stores a value in the tenant's namespace and returns the reference that will read it back.
     *
     * @param namespace what kind of thing this secret belongs to, e.g. {@code identity_provider};
     *     lowercase, underscores, no slashes
     * @param name a caller-chosen name within the namespace, same alphabet
     * @throws SecretsException when the adapter is read-only or the provider refused
     */
    SecretReference store(UUID tenantId, String namespace, String name, char[] value);

    /**
     * Destroys a secret so the reference resolves to nothing. Idempotent: destroying twice is not an
     * error, and destroying a reference that never existed is not one either — a caller that could tell
     * the two apart could enumerate.
     */
    void destroy(UUID tenantId, SecretReference reference);

    /** One line for the startup banner. Names the provider and its endpoint; never a credential. */
    String description();

    /**
     * A failure the caller should surface. {@code code} is stable and safe to show; {@code detail} is a
     * class name or a short fixed phrase, never provider text.
     */
    final class SecretsException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public SecretsException(String code, String detail) {
            super(code + ": " + detail);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The alphabet a namespace or name must use, so it can be a path segment in every provider. */
    static String segment(String value, String what) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException(what + " must be 1–64 characters of [a-z0-9_-], starting "
                    + "with a letter or digit");
        }
        return value;
    }
}
