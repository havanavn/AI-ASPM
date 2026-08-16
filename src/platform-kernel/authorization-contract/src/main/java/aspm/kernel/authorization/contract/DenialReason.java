package aspm.kernel.authorization.contract;

/**
 * Enumerated denial reasons, per DOC-07 section 8.2.
 *
 * <p><b>Recorded in audit at full fidelity and never returned to a client.</b> DOC-07 section 8.2 and
 * {@code SEC-AUZ-020}: a differentiated denial reason discloses whether an object exists, and
 * {@code PRD-API-021} extends the prohibition to status code, error code, message and timing. The
 * {@code audit_event.denial_reason} column of DOC-04 section 20.1 is annotated "full fidelity; never
 * returned to a client" for the same reason.
 *
 * <p>{@link #OBJECT_NOT_FOUND} is in this enumeration precisely so that non-existence and
 * non-authorization travel the same path to the client and diverge only into the audit trail.
 */
public enum DenialReason {

    /** No grant matched. The {@code SEC-AUZ-014} default. */
    NO_MATCHING_GRANT,

    /** A grant matched the permission but not the object's scope. */
    OUT_OF_SCOPE,

    /** The object does not exist. Indistinguishable to the client from the two above. */
    OBJECT_NOT_FOUND,

    /** Evaluation raised. {@code SEC-AUZ-014} requires denial rather than a permissive fallback. */
    EVALUATION_ERROR,

    /** Scope could not be resolved. Denied rather than treated as unrestricted. */
    SCOPE_RESOLUTION_UNAVAILABLE,

    /** Separation of duties forbids this principal for this action ({@code SEC-AUZ-039}). */
    SEPARATION_OF_DUTIES,

    /** The delegation would exceed the delegator's own authority ({@code SEC-AUZ-043}). */
    DELEGATION_EXCEEDED,

    /** An automation rule would exceed its owner's authority ({@code SEC-AUZ-037}). */
    AUTOMATION_EXCEEDS_OWNER,

    /** The entitlement tier does not include the capability ({@code LIC-PLT-003}). */
    ENTITLEMENT_EXCLUDED,

    /**
     * Anything not covered above.
     *
     * <p>Exists so that {@code SEC-AUZ-014}'s "any unhandled condition" has a representable value.
     * A default branch that permitted rather than denied would be the fail-open this enumeration's
     * existence prevents.
     */
    UNHANDLED_CONDITION
}
