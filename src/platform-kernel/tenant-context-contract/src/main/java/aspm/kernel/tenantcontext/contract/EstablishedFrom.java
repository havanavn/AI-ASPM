package aspm.kernel.tenantcontext.contract;

/**
 * How a tenant context came to exist, per DOC-24 section 5.2.
 *
 * <p>Recorded rather than inferred because {@code SEC-TEN-004} forbids deriving the tenant from any
 * request parameter, header, path segment or body field. An enumerated provenance makes the
 * prohibited fifth case — "from the request" — unrepresentable rather than merely discouraged.
 */
public enum EstablishedFrom {

    /** An authenticated human principal's session or token. */
    AUTHENTICATED_PRINCIPAL,

    /** A scope-pinned service credential (ADR-004; no bearer API keys). */
    SERVICE_CREDENTIAL,

    /**
     * An explicit binding carried by queued or scheduled work. {@code SEC-TEN-006} forbids a work
     * item without one from executing, because asynchronous work has no ambient request to inherit.
     */
    SCHEDULED_JOB_BINDING,

    /** A break-glass grant, which is visible to the tenant per {@code SEC-TEN-030}. */
    BREAK_GLASS_GRANT
}
