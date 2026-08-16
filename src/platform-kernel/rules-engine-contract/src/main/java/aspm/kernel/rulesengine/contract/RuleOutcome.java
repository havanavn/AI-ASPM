package aspm.kernel.rulesengine.contract;

/**
 * Three-valued evaluation outcome.
 *
 * <p><b>Why three values and not a boolean.</b> This is the single most consequential decision in the
 * rules engine, and it exists because of one case: negation over a missing fact.
 *
 * <p>With two-valued logic, an absent fact makes a comparison false, so {@code NOT(severity = LOW)}
 * evaluates <b>true</b> for an object whose severity was never recorded. A workflow guard reading
 * "may close when NOT informational" would then permit closing a finding whose severity is unknown.
 * That is a fail-open, produced by a reasonable-looking guard over incomplete data, and it is exactly
 * what product principle 1 exists to prevent: "measured-and-clean must be distinguishable from
 * not-measured".
 *
 * <p>With three values, {@code UNDEFINED} propagates through negation, so the guard is not satisfied and
 * {@code SEC-AUZ-014}'s "deny on any unhandled condition" is honoured by the arithmetic rather than by
 * the caller remembering.
 *
 * <p><b>Every caller treats UNDEFINED as not-true, but for different reasons</b>, which is why the
 * distinction is preserved rather than collapsed at the boundary:
 *
 * <ul>
 *   <li>A workflow guard denies the transition ({@code PRD-WRK-033}, DOC-09 section 2.1 step 8).
 *   <li>A service-level match does not match, so a more general policy applies ({@code DOC-28 §11.1}).
 *   <li>A checklist selection rule does not select the item.
 *   <li>An automation rule does not fire, and {@code UNDEFINED} is reportable so a tenant can see that
 *       their rule is silently inert rather than merely unmatched — a rule that never fires because a
 *       fact is always absent is a configuration defect, and it is invisible if UNDEFINED and FALSE are
 *       the same value.
 * </ul>
 */
public enum RuleOutcome {

    TRUE,
    FALSE,

    /**
     * Neither true nor false: a fact the condition depends on is absent, or a comparison is not defined
     * for the values given.
     *
     * <p>Never coerced to FALSE by the engine. A caller that wants that coercion calls {@link #isTrue()}
     * and gets it, but the coercion is at the caller's boundary where its reason is visible.
     */
    UNDEFINED;

    /** True only for {@link #TRUE}. The safe accessor: {@code UNDEFINED} is never true. */
    public boolean isTrue() {
        return this == TRUE;
    }

    /** Kleene AND: UNDEFINED dominates TRUE, FALSE dominates UNDEFINED. */
    public RuleOutcome and(RuleOutcome other) {
        if (this == FALSE || other == FALSE) {
            // A definite false makes the conjunction definitely false even with an unknown sibling,
            // which is correct and lets a cheap disqualifying clause short-circuit an expensive one.
            return FALSE;
        }
        return (this == UNDEFINED || other == UNDEFINED) ? UNDEFINED : TRUE;
    }

    /** Kleene OR: a definite TRUE dominates UNDEFINED. */
    public RuleOutcome or(RuleOutcome other) {
        if (this == TRUE || other == TRUE) {
            return TRUE;
        }
        return (this == UNDEFINED || other == UNDEFINED) ? UNDEFINED : FALSE;
    }

    /**
     * Kleene NOT: {@code NOT UNDEFINED} is UNDEFINED.
     *
     * <p>This one line is the reason this enum exists. Under two-valued logic it would return TRUE, and
     * a guard negating a missing fact would pass.
     */
    public RuleOutcome negate() {
        return switch (this) {
            case TRUE -> FALSE;
            case FALSE -> TRUE;
            case UNDEFINED -> UNDEFINED;
        };
    }

    public static RuleOutcome of(boolean value) {
        return value ? TRUE : FALSE;
    }
}
