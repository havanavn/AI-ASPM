package aspm.module.riskprioritization.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Node-level posture. DOC-28 section 10.2, {@code PRD-RSK-029}.
 *
 * <pre>
 * node_posture = w₁ × severity_pressure      normalized worst-case exposure
 *              + w₂ × concentration          share of score in the top decile of assets
 *              + w₃ × sla_health             proportion within remediation commitments
 *              + w₄ × coverage_penalty       explicit penalty for unmeasured scope
 * </pre>
 *
 * <p>Defaults {@code w₁} 0.40 · {@code w₂} 0.20 · {@code w₃} 0.25 · {@code w₄} 0.15.
 *
 * <h2>Why not the sum of the finding scores beneath the node</h2>
 *
 * <p>DOC-28 section 10.1 gives three failures, "each of which would be raised in the first executive review":
 *
 * <ul>
 *   <li><b>It penalizes size.</b> A unit with 400 applications will exceed one with 40 regardless of relative
 *       security; the comparison is then dismissed as unfair, correctly, "and dismissal of one metric spreads to
 *       the rest".
 *   <li><b>It rewards concealment.</b> A unit that scans less has fewer findings and a lower sum — summation
 *       makes <i>not looking</i> the cheapest improvement available.
 *   <li><b>It obscures concentration.</b> One hundred mediums across a portfolio and twenty criticals on one
 *       internet-facing payment service can sum equally while demanding entirely different responses.
 * </ul>
 *
 * <p><b>On {@code sla_health} carrying a quarter of the weight.</b> It is the only component measuring what the
 * unit <i>does</i> rather than what it <i>has</i>. A unit that inherits a poor portfolio cannot change that this
 * quarter but can respond within commitment — and "a metric that feels punitive gets litigated instead of acted
 * upon".
 *
 * <p><b>The scale is inverted relative to a finding score.</b> Higher posture is worse, matching the finding
 * score's direction, so that a single reading of "higher is worse" holds across the product (PP-10).
 */
public final class NodePosture {

    private static final BigDecimal W_SEVERITY_PRESSURE = new BigDecimal("0.40");
    private static final BigDecimal W_CONCENTRATION = new BigDecimal("0.20");
    private static final BigDecimal W_SLA_HEALTH = new BigDecimal("0.25");
    private static final BigDecimal W_COVERAGE_PENALTY = new BigDecimal("0.15");
    private static final int SCALE = 6;

    /**
     * The minimum number of peers in a comparison set, per {@code PRD-RSK-031}.
     *
     * <p>"A comparison against two peers discloses those peers' posture by inference ({@code SEC-AUZ-026})." This
     * is an authorization control expressed as a presentation rule, which is why it lives with the calculation
     * rather than in a chart component: a caller reaching the number by another route must hit the same guard.
     */
    public static final int MINIMUM_COMPARISON_SET = 4;

    private final UUID nodeId;
    private final Instant computedAt;
    private final BigDecimal severityPressure;
    private final BigDecimal concentration;
    private final BigDecimal slaHealth;
    private final BigDecimal coveragePenalty;
    private final CoverageQualifier coverage;
    private final int assetsInScope;
    private final BigDecimal value;

    private NodePosture(UUID nodeId, Instant computedAt, BigDecimal severityPressure, BigDecimal concentration,
            BigDecimal slaHealth, BigDecimal coveragePenalty, CoverageQualifier coverage, int assetsInScope) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId is required");
        this.computedAt = Objects.requireNonNull(computedAt, "computedAt is required");
        this.severityPressure = requireUnitInterval(severityPressure, "severityPressure");
        this.concentration = requireUnitInterval(concentration, "concentration");
        this.slaHealth = requireUnitInterval(slaHealth, "slaHealth");
        this.coveragePenalty = requireUnitInterval(coveragePenalty, "coveragePenalty");
        this.coverage = Objects.requireNonNull(coverage,
                "a coverage qualifier is required (PRD-RSK-027). An aggregate without one cannot distinguish a "
                        + "well-measured clean node from an unmeasured one, which is PP-1 at aggregate level.");
        if (assetsInScope < 0) {
            throw new IllegalArgumentException("assetsInScope cannot be negative");
        }
        this.assetsInScope = assetsInScope;

        this.value = W_SEVERITY_PRESSURE.multiply(this.severityPressure)
                .add(W_CONCENTRATION.multiply(this.concentration))
                .add(W_SLA_HEALTH.multiply(this.slaHealth))
                .add(W_COVERAGE_PENALTY.multiply(this.coveragePenalty))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Builds a posture value.
     *
     * @param severityPressure the highest-scoring findings normalized against portfolio size. <b>Normalized by
     *     the caller</b>, which must divide by a size measure — this class asserts the result is in {@code [0,1]}
     *     but cannot see whether the division happened, so {@link #normalizationBasis()} records what the caller
     *     claims it did and {@code PRD-RSK-030} requires that claim be presented
     * @param slaHealth <b>1.0 means poor health.</b> The component is oriented so every component points the same
     *     way; a component that improved the total when things got worse would be a defect nobody could see in
     *     the arithmetic
     */
    public static NodePosture of(UUID nodeId, Instant computedAt, BigDecimal severityPressure,
            BigDecimal concentration, BigDecimal slaHealth, BigDecimal coveragePenalty,
            CoverageQualifier coverage, int assetsInScope) {
        return new NodePosture(nodeId, computedAt, severityPressure, concentration, slaHealth, coveragePenalty,
                coverage, assetsInScope);
    }

    /**
     * The coverage penalty implied by a qualifier: the proportion of in-scope assets without current data.
     *
     * <p>Derived from the qualifier rather than supplied, so that the penalty and the confidence band cannot
     * disagree about the same population. DOC-28 section 10.1: this component is what "makes concealment
     * expensive rather than free".
     */
    public static BigDecimal penaltyFrom(CoverageQualifier coverage) {
        Objects.requireNonNull(coverage, "a coverage qualifier is required");
        if (coverage.assetsInScope() == 0) {
            // An empty scope takes the maximum penalty, not the minimum. "100% of nothing" is the arithmetic
            // that produces a perfect posture for a node with no assets (PRD-RSK-027).
            return BigDecimal.ONE;
        }
        return BigDecimal.ONE.subtract(
                BigDecimal.valueOf(coverage.currentDataRatio()).setScale(SCALE, RoundingMode.HALF_UP));
    }

    /**
     * The posture value where it is presentable, and empty where coverage forbids it.
     *
     * <p>{@code PRD-RSK-027} applies with more force at aggregate level than at finding level: an aggregate is
     * what reaches an executive summary.
     */
    public Optional<BigDecimal> asPostureFigure() {
        return coverage.presentableAsPostureFigure() ? Optional.of(value) : Optional.empty();
    }

    /**
     * The normalization applied, for presentation alongside any comparison ({@code PRD-RSK-030}).
     *
     * <p>"An unstated normalization is indistinguishable from an unfair comparison."
     */
    public String normalizationBasis() {
        return "severity pressure normalized against " + assetsInScope + " in-scope asset(s); coverage "
                + coverage.confidence() + " at " + Math.round(coverage.currentDataRatio() * 100)
                + "% current data";
    }

    /**
     * Guards a comparative presentation. {@code PRD-RSK-030} and {@code PRD-RSK-031}.
     *
     * @throws IllegalArgumentException where the set is too small to present. Deliberately an exception rather
     *     than a silently-truncated chart: a comparison that quietly dropped to two peers would satisfy nobody's
     *     expectation and would still disclose their posture by inference
     */
    public static List<NodePosture> comparisonSet(List<NodePosture> peers) {
        Objects.requireNonNull(peers, "a peer list is required");
        if (peers.size() < MINIMUM_COMPARISON_SET) {
            throw new IllegalArgumentException(
                    "a comparison needs at least " + MINIMUM_COMPARISON_SET + " entities; got " + peers.size()
                            + ". A comparison against two peers discloses those peers' posture by inference "
                            + "(PRD-RSK-031, SEC-AUZ-026).");
        }
        return List.copyOf(peers);
    }

    public UUID nodeId() {
        return nodeId;
    }

    public Instant computedAt() {
        return computedAt;
    }

    public BigDecimal severityPressure() {
        return severityPressure;
    }

    public BigDecimal concentration() {
        return concentration;
    }

    public BigDecimal slaHealth() {
        return slaHealth;
    }

    public BigDecimal coveragePenalty() {
        return coveragePenalty;
    }

    public CoverageQualifier coverage() {
        return coverage;
    }

    public int assetsInScope() {
        return assetsInScope;
    }

    /** The value for ordering and drill-down, available regardless of coverage. See {@link #asPostureFigure}. */
    public BigDecimal valueForPrioritisationOnly() {
        return value;
    }

    private static BigDecimal requireUnitInterval(BigDecimal candidate, String name) {
        Objects.requireNonNull(candidate, name + " is required");
        if (candidate.compareTo(BigDecimal.ZERO) < 0 || candidate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(name + " must lie in [0,1]; got " + candidate
                    + ". A component outside the interval makes the weights uninterpretable and can push the "
                    + "posture outside its own scale, which would be visible only as an odd-looking chart.");
        }
        return candidate.setScale(SCALE, RoundingMode.HALF_UP);
    }

    @Override
    public String toString() {
        return "NodePosture[" + nodeId + " " + value + " coverage=" + coverage.confidence() + "]";
    }
}
