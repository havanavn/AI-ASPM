package aspm.module.workmanagement.domain;

/**
 * How a principal participates in a work item. DOC-04 section 16.5.
 *
 * <p><b>Distinct from assignment.</b> {@code INV-WRK-05} makes assignment "to exactly one individual; supporters
 * are participants". The reason is accountability: a work item assigned to three people is assigned to nobody,
 * and every study of shared ownership says so. Participation is how the other three stay involved without the
 * accountability being diluted.
 *
 * <p>These four are product-fixed rather than tenant vocabulary. They are not role names in the ADR-027 sense —
 * a tenant's roles are its authorization structure, while these describe a relationship to one item, and
 * notification fan-out and the participant licence tier ({@code LIC-PLT-002}) both key off them.
 */
public enum ParticipantRole {

    /** Coordinating without being the assignee — typically across several related items. */
    LEAD,

    /** Doing part of the work. The role {@code INV-WRK-05} intends supporters to hold. */
    SUPPORT,

    /** Reviewing the outcome. Separate from support because a reviewer who also did the work is not a review. */
    REVIEWER,

    /**
     * Observing to learn. Carries no expectation of action.
     *
     * <p>Distinguished from a watcher: a shadow is recorded as a participant deliberately placed there, a watcher
     * subscribed themselves. Merging them would make "who is expected to be involved" unanswerable.
     */
    SHADOW
}
