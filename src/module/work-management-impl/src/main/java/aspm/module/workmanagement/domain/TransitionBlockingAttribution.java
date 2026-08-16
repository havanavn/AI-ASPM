package aspm.module.workmanagement.domain;

/**
 * Why work stopped, recorded on the transition. DOC-04 section 16.3.
 *
 * <p><b>This is a different enumeration from the service level clock's, deliberately, and the difference is worth
 * stating.</b> {@code PRD-RSK-034}'s set — requester, third party, security function — answers <i>who is
 * accountable for the delay</i>, because that determines whose escalation chain fires
 * ({@code PRD-RSK-037}). This set, from DOC-04 section 16.3, answers <i>what stopped the work</i>, which is what
 * {@code PRD-CAP-009}'s breach attribution reporting aggregates. One is for deciding whom to escalate to; the
 * other is for deciding what to fix.
 *
 * <p>PP-10 says one name, one meaning, one place. Two enumerations with overlapping members are exactly the kind
 * of thing PP-10 warns about, so {@link #escalationAttribution()} maps this set onto the clock's rather than
 * leaving two independent judgements about the same pause. The mapping lives here, once, and the clock never
 * infers it.
 */
public enum TransitionBlockingAttribution {

    /** Waiting on the requester to provide something — an environment, a decision, a test account. */
    REQUESTER_READINESS,

    /** Waiting on a vendor, an upstream project, an external partner. */
    THIRD_PARTY,

    /** The environment is unavailable or broken. Distinct from the requester: nobody chose it. */
    ENVIRONMENT,

    /** The scope of the work changed underneath it and it must be re-planned. */
    SCOPE_CHANGE,

    /** Nobody is available to do it. The one attribution that points at the accountable team. */
    CAPACITY,

    /** Blocked by another work item or another team's dependency. */
    EXTERNAL_DEPENDENCY;

    /**
     * The clock-level attribution this maps onto, as the code the service level module uses.
     *
     * <p>Returned as a string rather than the risk module's enum because a cross-module type dependency in this
     * direction is not permitted (ADR-003, {@code CON-PLT-014}), and inventing a shared-kernel type for six
     * values used by two modules would put vocabulary in the kernel that neither owns.
     */
    public String escalationAttribution() {
        return switch (this) {
            case REQUESTER_READINESS, SCOPE_CHANGE -> "REQUESTER";
            case THIRD_PARTY, ENVIRONMENT, EXTERNAL_DEPENDENCY -> "THIRD_PARTY";
            // Capacity is the accountable team's own constraint. Mapping it to REQUESTER or THIRD_PARTY would
            // suppress the escalation that ought to fire (PRD-RSK-037), which is how a backlog becomes
            // invisible: every item paused, nobody escalated.
            case CAPACITY -> "SECURITY_FUNCTION";
        };
    }

    /** Whether pausing for this reason suppresses the remediation escalation chain ({@code PRD-RSK-037}). */
    public boolean suppressesRemediationEscalation() {
        return !escalationAttribution().equals("SECURITY_FUNCTION");
    }
}
