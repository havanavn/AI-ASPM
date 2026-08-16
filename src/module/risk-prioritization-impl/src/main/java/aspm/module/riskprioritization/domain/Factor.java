package aspm.module.riskprioritization.domain;

import java.math.BigDecimal;

/**
 * The factor set of DOC-28 section 4.2. <b>Product-fixed</b> ({@code PRD-RSK-004}); weights are
 * tenant-configurable within the bounds of DOC-28 section 7.2.
 *
 * <p>This is one of the few enumerations in the platform that is deliberately fixed rather than tenant data.
 * DOC-28 section 7.1 lists it under Fixed: adding a factor is a model version change, "which is the correct
 * friction". ADR-027 governs roles, org levels, workflow states and vocabulary — not the methodology's own
 * structure, which every tenant's scores must share for a score to mean one thing.
 *
 * <p>Declaration order is the <b>evaluation order</b> used by {@link RiskScore}. It is load-bearing: BigDecimal
 * addition is associative only if the order never varies, and {@code PRD-RSK-023} requires an identical value on
 * recomputation. Reordering these constants changes historical reproducibility, so it is a model version change
 * too.
 */
public enum Factor {

    /** Base severity. DOC-28 section 5.1. Default 0.30. */
    SEV("0.15", "0.45", "0.30", Group.TECHNICAL),

    /**
     * Exploit likelihood. DOC-28 section 5.2. Default 0.20.
     *
     * <p><b>Population-relative.</b> The input is rank-transformed within the tenant's own finding population, so
     * a score can move when unrelated findings are added or resolved and nothing about this finding changed.
     * DOC-28 accepts that cost and mitigates it by requiring attribution to name population shift as the cause —
     * see {@link ScoreChangeAttribution.Cause#POPULATION_SHIFT}. That is why a score carries a population
     * version.
     */
    EXP("0.05", "0.35", "0.20", Group.TECHNICAL),

    /** Known exploited. DOC-28 section 5.3. Binary input. Default 0.20. */
    KEV("0.05", "0.35", "0.20", Group.TECHNICAL),

    /** Asset exposure, using the MORE exposed of declared and observed ({@code INV-AST-08}). Default 0.15. */
    EXPO("0.05", "0.30", "0.15", Group.CONTEXTUAL),

    /** Business criticality, resolved through inheritance ({@code INV-AST-06}). Default 0.15. */
    CRIT("0.05", "0.30", "0.15", Group.CONTEXTUAL),

    /** Data sensitivity. Default 0.10. Unclassified floors at 0.20 rather than 0 — DOC-28 section 5.6. */
    DATA("0.00", "0.25", "0.10", Group.CONTEXTUAL),

    /**
     * Reachability. <b>Reserved at weight zero</b> (DF-03).
     *
     * <p>Present so that enabling reachability later is a weight change plus an input, not a model
     * restructuring, and so existing scores are unaffected until a tenant deliberately raises the weight.
     */
    REACH("0.00", "0.20", "0.00", Group.TECHNICAL);

    /** Technical characteristics determine WHETHER something is dangerous; contextual, whether it matters here. */
    public enum Group {
        TECHNICAL,
        CONTEXTUAL
    }

    private final BigDecimal minimumWeight;
    private final BigDecimal maximumWeight;
    private final BigDecimal defaultWeight;
    private final Group group;

    Factor(String minimumWeight, String maximumWeight, String defaultWeight, Group group) {
        this.minimumWeight = new BigDecimal(minimumWeight);
        this.maximumWeight = new BigDecimal(maximumWeight);
        this.defaultWeight = new BigDecimal(defaultWeight);
        this.group = group;
    }

    public BigDecimal minimumWeight() {
        return minimumWeight;
    }

    public BigDecimal maximumWeight() {
        return maximumWeight;
    }

    public BigDecimal defaultWeight() {
        return defaultWeight;
    }

    public Group group() {
        return group;
    }

    /**
     * Whether this factor participates in the contextual multiplier of DOC-28 section 6.1.
     *
     * <p>Derived from {@link #group} rather than declared separately, because two lists of the same three
     * factors would be a place for them to diverge (PP-10). Note that {@code REACH} is technical, so reserving
     * it does not silently enter the multiplier.
     */
    public boolean contextual() {
        return group == Group.CONTEXTUAL;
    }
}
