package aspm.kernel.audit.contract;

/** Event outcome, per DOC-14 section 2 and the {@code audit_event.outcome} check constraint. */
public enum AuditOutcome {
    SUCCESS,
    DENIED,
    FAILED
}
