package aspm.module.riskprioritization.domain;

/**
 * The score-reducing action classes of DOC-28 section 13.2, enumerated because {@code PRD-RSK-041} requires
 * anomalous <b>rates</b> of them surfaced per principal and per organization node.
 *
 * <p>DOC-28 section 13.1 states the premise without euphemism: "Once a score drives executive attention it will be
 * optimized, and optimizing the score is cheaper than reducing risk. This is not cynicism; it is the predictable
 * consequence of measurement, and a model that does not anticipate it will be gamed within two quarters."
 *
 * <p><b>Every action here is legitimate.</b> {@code PRD-RSK-041}: "Each individual action is legitimate; the
 * <i>rate</i> is the signal. Detection at the rate level catches gaming without impeding normal work." Nothing in
 * this module blocks any of these actions — a control that blocked them would obstruct the ordinary work of a
 * security team, which is a worse outcome than the gaming it prevented.
 *
 * <p>This enumeration is product-fixed rather than tenant data, unlike the closure reasons a tenant configures
 * ({@code PRD-VUL-011}). It classifies <i>what kind of effect on the score</i> an action has, which is a property
 * of the methodology; a tenant may add a closure reason, and it maps onto {@link #CLOSE_NOT_APPLICABLE} here.
 *
 * <p>{@code TST-AUZ-001}-style extension note: adding a new way to lower a score without adding a constant here
 * creates an undetected gaming path. The detector's own test asserts this enumeration covers every row of DOC-28
 * section 13.2.
 */
public enum ScoreReducingAction {

    /** Mass-close as not-applicable. Control: enumerated reasons, separately reported ({@code PRD-VUL-011}). */
    CLOSE_NOT_APPLICABLE,

    /** Bulk severity downgrade. Control: reported severity immutable, adjustment audited ({@code INV-VUL-07}). */
    SEVERITY_DOWNGRADE,

    /** Except rather than fix. Control: excepted findings stay in aggregate risk ({@code INV-VUL-27}). */
    RISK_EXCEPTION,

    /** Asset criticality downgrade. Control: override needs justification, audited ({@code INV-AST-06}). */
    CRITICALITY_DOWNGRADE,

    /** Declare internal-only. Control: observed exposure wins where more exposed ({@code INV-AST-08}). */
    EXPOSURE_DOWNGRADE,

    /** Remove data classification. Control: unclassified floors at 0.20, worse than confirmed-none. */
    DATA_CLASSIFICATION_REMOVAL,

    /** Stop scanning. Control: coverage penalty, and coverage is not improvable by exclusion. */
    SCOPE_EXCLUSION,

    /** Retire an asset that is still live. Control: retirement audited, recent activity evidence flagged. */
    ASSET_RETIREMENT,

    /** Suppress as false positive. Control: suppression expires and requires revalidation ({@code INV-VUL-21}). */
    FALSE_POSITIVE_SUPPRESSION,

    /** Extend a deadline. Control: extension is a distinct state, reported separately ({@code INV-RSK-11}). */
    DEADLINE_EXTENSION,

    /**
     * Split a finding so each part scores lower.
     *
     * <p>Control: the score is per finding-asset pair, so splitting raises the count without lowering the
     * maximum. Included in the rate detection anyway: the control removes the benefit, and an actor repeatedly
     * attempting something that does not work is itself worth surfacing.
     */
    FINDING_SPLIT,

    /**
     * Score-affecting configuration change — weights, thresholds, criticality tiers, service level policies.
     *
     * <p>{@code PRD-RSK-043}: "Configuration change is the most efficient gaming path: one weight change affects
     * every score at once and appears nowhere in a finding-level audit review." It is the only action here that
     * additionally requires elevated permission and a periodic configuration-change summary.
     */
    SCORE_CONFIGURATION_CHANGE;

    /**
     * Whether {@code PRD-RSK-043}'s elevated permission and periodic configuration summary apply.
     *
     * <p>Separated from rate detection because the two controls answer different questions: rate detection asks
     * whether this principal is doing an unusual amount of something ordinary, and this asks whether the action
     * should have been available to them at all.
     */
    public boolean requiresElevatedPermissionAndConfigurationSummary() {
        return this == SCORE_CONFIGURATION_CHANGE;
    }
}
