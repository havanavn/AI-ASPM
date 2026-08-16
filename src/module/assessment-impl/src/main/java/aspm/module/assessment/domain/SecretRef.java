package aspm.module.assessment.domain;

import java.util.Objects;

/**
 * A reference to a secret held in the secrets store. {@code INV-ASM-03}, {@code SEC-PTR-004},
 * {@code PRD-API-033}.
 *
 * <p>⚠️ <b>Working assumption (OQ-026):</b> the platform integrates with an external secrets store and ships a
 * default. This type is deliberately opaque about which one — it carries a provider-qualified reference string
 * and nothing about the provider's API — so that answering OQ-026 changes an adapter and not this type or the
 * schema that stores it.
 *
 * <h2>Why a type rather than a String field</h2>
 *
 * <p>{@code SEC-PTR-004}'s subject is "live credentials to pre-production environments that frequently share
 * data or trust relationships with production. Storing them as request fields would place working credentials in
 * every export, backup, log line that dumps a record, and AI prompt."
 *
 * <p>A {@code String} field can hold either a reference or a credential, and nothing in the type says which. The
 * validation would live at one boundary, and the second write path — an import, a migration, a bulk edit — would
 * not have it. Here the only constructor rejects anything that looks like a value, so a plaintext credential
 * cannot be represented at all.
 *
 * <h2>The rejected value is never in the exception, the log, or the {@code toString}</h2>
 *
 * <p>{@code PRD-API-033}: "A request body containing a value in a credential field MUST be rejected with
 * {@code VALIDATION_FAILED} and the value MUST NOT be logged", because "rejecting at the boundary and not logging
 * the rejected value prevents the credential reaching a log as a side effect of the rejection".
 *
 * <p>That is easy to write and easy to undo. A helpful diagnosis quoting the offending input is the natural thing
 * to add, and it would put the credential in the log line that reports the rejection — the one place everybody
 * looks. {@link #rejectionMessage} therefore returns a message built only from what the input <i>is not</i>, and a
 * test asserts the input never appears in it.
 */
public final class SecretRef {

    /**
     * The reference shape: {@code provider:path/to/secret#version}.
     *
     * <p>Deliberately narrow. A reference that could be any string would let a credential through by being a
     * credential without spaces, and the whole control rests on the two forms being distinguishable.
     */
    private static final java.util.regex.Pattern REFERENCE =
            java.util.regex.Pattern.compile("[a-z][a-z0-9+.-]{1,31}:[A-Za-z0-9_./-]{1,256}(#[A-Za-z0-9_.-]{1,64})?");

    private final String value;

    private SecretRef(String value) {
        this.value = value;
    }

    /**
     * Parses a reference.
     *
     * @throws PlaintextCredentialRejected where the input is not a reference. The exception carries <b>no part
     *     of the input</b> — see {@link #rejectionMessage}
     */
    public static SecretRef of(String candidate) {
        Objects.requireNonNull(candidate, "a credential reference is required (INV-ASM-03)");
        if (!REFERENCE.matcher(candidate).matches()) {
            throw new PlaintextCredentialRejected(rejectionMessage());
        }
        return new SecretRef(candidate);
    }

    /** Whether a candidate is a reference, for a caller that wants to test without an exception. */
    public static boolean isReference(String candidate) {
        return candidate != null && REFERENCE.matcher(candidate).matches();
    }

    /**
     * The rejection message.
     *
     * <p><b>It takes no argument.</b> A first version passed the rejected candidate in and never read it; Error
     * Prone's {@code UnusedVariable} check flagged the parameter, which was the right call for a better reason
     * than it knew — a parameter that exists is one a later change can start using, and the natural later change
     * is "make this diagnosis more helpful". Removing it makes the leak impossible rather than absent.
     *
     * <p>So: no substring, no length, no character-class summary, no hash. A length alone narrows a brute force;
     * a character-class summary narrows it further; a hash of a short credential is the credential.
     */
    private static String rejectionMessage() {
        return "VALIDATION_FAILED: a credential field accepts a vault reference only, of the form "
                + "'provider:path' with an optional '#version' (PRD-API-033, INV-ASM-03). The submitted value "
                + "is not a reference and has been discarded without being recorded — it is not in this "
                + "message, in the log, or in the audit entry, because the log line reporting a rejected "
                + "credential is the one place everybody looks. If a credential was submitted, treat it as "
                + "exposed to whatever handled the request and rotate it.";
    }

    /** Raised on a plaintext credential. Carries no part of the rejected input. */
    public static final class PlaintextCredentialRejected extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        PlaintextCredentialRejected(String message) {
            super(message);
        }
    }

    /**
     * The reference itself.
     *
     * <p>Safe to log: it names a location, not a secret. Resolving it requires the reveal path of
     * {@code SEC-PTR-004} — explicitly permissioned, step-up authenticated, per-object audited — which is not in
     * this module and deliberately not reachable from this type.
     */
    public String reference() {
        return value;
    }

    /** The provider segment, for routing to the right adapter once OQ-026 is answered. */
    public String provider() {
        return value.substring(0, value.indexOf(':'));
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SecretRef r && value.equals(r.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
