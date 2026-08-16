package aspm.module.riskprioritization.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A computed risk score. Immutable ({@code INV-RSK-03}, {@code PRD-RSK-024}) and self-contained
 * ({@code PRD-RSK-023}).
 *
 * <h2>The formula — DOC-28 section 6.1</h2>
 *
 * <pre>
 * raw     = Σ ( weightᵢ × factorᵢ )              weighted sum
 * context = max( EXPO, CRIT, DATA )              contextual ceiling
 * score   = 100 × normalize( raw ) × ( 0.4 + 0.6 × context )
 * </pre>
 *
 * <p>Every element of that shape answers a specific failure, and none is decorative ({@code PRD-RSK-016}):
 *
 * <ul>
 *   <li><b>A sum, not a product.</b> With six factors, several legitimately zero — not in a known-exploited
 *       catalogue, an air-gapped asset — a product produces zero for most findings and no ordering at all.
 *   <li><b>A contextual multiplier on top.</b> A pure sum lets technical severity carry a finding to a high score
 *       regardless of context; a critical on a retired air-gapped internal tool would score highly, which "is
 *       exactly the behaviour that makes teams distrust a score".
 *   <li><b>{@code max}, not a sum, of the contextual factors.</b> Exposure, criticality and data sensitivity are
 *       substantially correlated — an internet-facing payment service is high on all three — so summing them
 *       triple-counts one underlying property.
 *   <li><b>A multiplier floor of 0.4, not 0.</b> Zero would hide genuine technical problems on assets nobody
 *       classified. At 0.4 the lowest-context asset scores at 40% of its technical value: still deprioritized,
 *       still visible.
 * </ul>
 *
 * <h2>Self-containment</h2>
 *
 * <p>The score retains every factor input <b>value</b> with its source and freshness rather than a reference to
 * the row it came from, because {@code PRD-RSK-023} requires recomputation "without access to any other data,
 * including data that has since changed" — and criticality, exposure and intelligence change constantly. DOC-04
 * section 18.2 quantifies the cost at 400–800 bytes per score and 30–40 GB at fifty million scores, and names it
 * the largest single storage consequence of the reproducibility requirement.
 */
public final class RiskScore {

    /** DOC-04 section 18.2 {@code subject_kind}. */
    public enum SubjectKind {
        /**
         * One finding on one asset.
         *
         * <p>DOC-28 section 4.1: "the same vulnerability in an internet-facing payment service and in a retired
         * internal tool are different priorities and must not share a score". It is also the anti-gaming control
         * for finding splitting (section 13.2): the score is per finding-asset pair, so splitting a finding
         * increases the count without lowering the maximum.
         */
        FINDING_IMPACT,
        ASSET,
        ORG_NODE
    }

    /** The contextual multiplier floor of DOC-28 section 6.1. */
    private static final BigDecimal CONTEXT_FLOOR = new BigDecimal("0.4");
    private static final BigDecimal CONTEXT_RANGE = new BigDecimal("0.6");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Retained scale for intermediates. Wide enough that rounding is invisible at integer output. */
    private static final int SCALE = 8;

    /**
     * Why a factor carries a fallback rather than a measured value.
     *
     * <p>{@code PRD-RSK-018}: a fallback MUST be recorded and MUST NOT be a silent zero, "because silent zero
     * substitution means missing data lowers the score, so absent intelligence looks like absent risk — PP-1
     * violated inside the formula".
     */
    public enum Fallback {
        /** The input was measured. */
        NONE,
        /** DOC-28 section 5.2: absent exploit prediction falls back to a neutral 0.5, never to 0. */
        NEUTRAL_MIDPOINT,
        /** DOC-28 section 5.3: absence from a known-exploited catalogue is weak evidence, recorded as such. */
        ABSENT_FROM_CATALOGUE,
        /** DOC-28 sections 5.1, 5.6: an unclassified input takes the documented floor, not zero. */
        DOCUMENTED_FLOOR,
        /** The factor is reserved and contributes nothing by design ({@code REACH}). */
        RESERVED_FACTOR
    }

    /**
     * One factor's input as it was at computation time.
     *
     * @param normalizedValue in {@code [0, 1]} ({@code PRD-RSK-017}) — mixing raw scales makes weights
     *     uninterpretable and the explanation unusable
     * @param sourceReference what was read, in a form a reader can follow six months later
     * @param observedAt the input's own freshness, not the computation time. {@code INV-VUL-18} requires
     *     staleness visible wherever a value is used, and "wherever" includes a score explanation two years on
     * @param fallback {@link Fallback#NONE} where measured
     */
    public record FactorInput(Factor factor, BigDecimal normalizedValue, String sourceReference,
            Instant observedAt, Fallback fallback) {

        public FactorInput {
            Objects.requireNonNull(factor, "factor is required");
            Objects.requireNonNull(normalizedValue, "a normalized value is required");
            Objects.requireNonNull(sourceReference,
                    "a source reference is required (PRD-RSK-017). The score must carry what it read rather "
                            + "than a pointer to something that can move.");
            Objects.requireNonNull(observedAt,
                    "the observation time is required (INV-VUL-18). A value whose age is unknown cannot be "
                            + "distinguished from a current one in an explanation.");
            Objects.requireNonNull(fallback,
                    "a fallback classification is required, NONE where measured (PRD-RSK-018). Absent data must "
                            + "not be indistinguishable from measured-and-zero.");
            if (normalizedValue.compareTo(BigDecimal.ZERO) < 0
                    || normalizedValue.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("every factor normalizes to [0,1] before weighting "
                        + "(PRD-RSK-017); " + factor + " arrived as " + normalizedValue);
            }
        }

        /** Convenience for a measured input. */
        public static FactorInput measured(Factor factor, String value, String source, Instant observedAt) {
            return new FactorInput(factor, new BigDecimal(value), source, observedAt, Fallback.NONE);
        }
    }

    /** One factor's contribution to {@code raw}, retained so the explanation is a lookup. */
    public record Contribution(Factor factor, BigDecimal weight, BigDecimal normalizedValue,
            BigDecimal weightedValue) {
    }

    private final UUID id;
    private final SubjectKind subjectKind;
    private final UUID subjectId;
    private final int modelVersion;
    private final Instant computedAt;
    private final List<FactorInput> inputs;
    private final WeightSet weights;
    private final BandThresholds thresholds;
    private final CoverageQualifier coverage;
    private final long populationVersion;
    private final List<Contribution> contributions;
    private final BigDecimal raw;
    private final BigDecimal contextMultiplier;
    private final int value;
    private final ScoreBand band;

    private RiskScore(UUID id, SubjectKind subjectKind, UUID subjectId, int modelVersion, Instant computedAt,
            List<FactorInput> inputs, WeightSet weights, BandThresholds thresholds, CoverageQualifier coverage,
            long populationVersion) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.subjectKind = Objects.requireNonNull(subjectKind, "subjectKind is required");
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId is required");
        this.computedAt = Objects.requireNonNull(computedAt, "computedAt is required");
        this.weights = Objects.requireNonNull(weights, "a weight set is required");
        this.thresholds = Objects.requireNonNull(thresholds, "band thresholds are required");
        this.coverage = Objects.requireNonNull(coverage,
                "a coverage qualifier is required (PRD-RSK-027, INV-RSK-06). A score without one cannot say "
                        + "whether it is a posture figure or a coverage gap.");
        Objects.requireNonNull(inputs, "factor inputs are required");
        if (modelVersion < 1) {
            throw new IllegalArgumentException("a model version is required (PRD-RSK-023)");
        }
        this.modelVersion = modelVersion;
        this.populationVersion = populationVersion;

        Map<Factor, FactorInput> byFactor = new EnumMap<>(Factor.class);
        for (FactorInput input : inputs) {
            if (byFactor.put(input.factor(), input) != null) {
                throw new IllegalArgumentException(
                        "two inputs supplied for " + input.factor() + "; which one contributed would then "
                                + "depend on iteration order, and the score would not be reproducible");
            }
        }
        for (Factor factor : Factor.values()) {
            if (!byFactor.containsKey(factor)) {
                throw new IllegalArgumentException(
                        "no input for " + factor + ". The factor set is product-fixed (PRD-RSK-004) and every "
                                + "factor needs a value or a recorded fallback (PRD-RSK-018) — an omitted "
                                + "factor is a silent zero, which is what that requirement forbids.");
            }
        }

        // Evaluation in Factor declaration order, NEVER the caller's list order and never a HashMap's iteration
        // order. BigDecimal addition is associative only if the order never varies; PRD-RSK-023 requires an
        // identical value on recomputation, and "identical" includes the last decimal place.
        BigDecimal accumulated = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal context = BigDecimal.ZERO;
        List<Contribution> built = new java.util.ArrayList<>(Factor.values().length);
        List<FactorInput> retained = new java.util.ArrayList<>(Factor.values().length);

        for (Factor factor : Factor.values()) {
            FactorInput input = byFactor.get(factor);
            BigDecimal weight = weights.weightOf(factor);
            BigDecimal weighted = weight.multiply(input.normalizedValue())
                    .setScale(SCALE, RoundingMode.HALF_UP);
            accumulated = accumulated.add(weighted);
            built.add(new Contribution(factor, weight, input.normalizedValue(), weighted));
            retained.add(input);

            if (factor.contextual() && input.normalizedValue().compareTo(context) > 0) {
                // max, not sum. Summing correlated contextual factors triple-counts one underlying property.
                context = input.normalizedValue();
            }
        }

        this.inputs = List.copyOf(retained);
        this.contributions = List.copyOf(built);
        this.raw = accumulated;
        this.contextMultiplier = CONTEXT_FLOOR.add(CONTEXT_RANGE.multiply(context))
                .setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal normalized = weights.normalize(raw, SCALE);
        BigDecimal scaled = HUNDRED.multiply(normalized).multiply(contextMultiplier);
        // Integer 0..100 (DOC-28 section 6.3). Clamped rather than allowed to exceed: a bounds-valid weight set
        // cannot exceed 100 after normalization, so a value above it means an invariant broke, and truncating
        // silently would hide that. The clamp is asserted against in the test suite.
        int rounded = scaled.setScale(0, RoundingMode.HALF_UP).intValueExact();
        if (rounded < 0 || rounded > 100) {
            throw new IllegalStateException(
                    "computed " + scaled + ", outside 0..100. normalize(raw) is bounded by construction for any "
                            + "bounds-valid weight set, so this indicates a factor value outside [0,1] or a "
                            + "weight set that bypassed WeightSet validation.");
        }
        this.value = rounded;
        this.band = thresholds.bandOf(rounded);
    }

    public static RiskScore compute(UUID id, SubjectKind subjectKind, UUID subjectId, int modelVersion,
            Instant computedAt, List<FactorInput> inputs, WeightSet weights, BandThresholds thresholds,
            CoverageQualifier coverage, long populationVersion) {
        return new RiskScore(id, subjectKind, subjectId, modelVersion, computedAt, inputs, weights, thresholds,
                coverage, populationVersion);
    }

    /**
     * Recomputes from the <b>retained</b> inputs — nothing is re-read.
     *
     * <p>This is the operation {@code PRD-RSK-023} exists for: the same score, computed again, six months later,
     * after criticality has been reassigned and the asset retired. It returns a new instance because
     * {@code PRD-RSK-024} makes scores immutable — "an in-place update destroys the prior value and with it the
     * ability to answer what changed".
     *
     * @param newWeights pass the original set to reproduce; pass a new set to preview a weight change
     *     ({@code PRD-RSK-022})
     */
    public RiskScore recomputeWith(UUID newId, int newModelVersion, Instant at, WeightSet newWeights,
            BandThresholds newThresholds) {
        return new RiskScore(newId, subjectKind, subjectId, newModelVersion, at, inputs, newWeights,
                newThresholds, coverage, populationVersion);
    }

    /**
     * The value where it is presentable as a posture figure, and empty where it is not.
     *
     * <p>{@code PRD-RSK-027}: at {@code INSUFFICIENT} coverage the score "MUST be presented as a coverage gap
     * rather than as a posture figure", because "presenting a favourable number over 30% coverage is the specific
     * mechanism by which the platform would produce a confident, wrong executive report".
     *
     * <p>An {@code Optional} rather than a number plus a boolean flag, because a flag is ignorable and every
     * caller that ignored it would produce exactly that report.
     */
    public Optional<Integer> asPostureFigure() {
        return coverage.presentableAsPostureFigure() ? Optional.of(value) : Optional.empty();
    }

    /**
     * The value for ordering a work queue, available at any coverage level.
     *
     * <p>Named for what it is licensed for. Poor coverage makes a score unusable as a <b>posture statement</b>
     * about a population; it does not make the relative ordering of the findings that <i>were</i> measured
     * useless, and withholding the ordering would leave practitioners with no queue at all.
     */
    public int valueForPrioritisationOnly() {
        return value;
    }

    /** The band. {@code PRD-RSK-019} presents this prominently and {@link #valueForPrioritisationOnly} second. */
    public ScoreBand band() {
        return band;
    }

    public UUID id() {
        return id;
    }

    public SubjectKind subjectKind() {
        return subjectKind;
    }

    public UUID subjectId() {
        return subjectId;
    }

    public int modelVersion() {
        return modelVersion;
    }

    public Instant computedAt() {
        return computedAt;
    }

    /** The population version behind the rank transform of {@link Factor#EXP}. See DOC-04 section 18.2. */
    public long populationVersion() {
        return populationVersion;
    }

    public List<FactorInput> inputs() {
        return inputs;
    }

    public List<Contribution> contributions() {
        return contributions;
    }

    public WeightSet weights() {
        return weights;
    }

    public BandThresholds thresholds() {
        return thresholds;
    }

    public CoverageQualifier coverage() {
        return coverage;
    }

    /** The weighted sum before normalization and the contextual multiplier. Retained for the explanation. */
    public BigDecimal raw() {
        return raw;
    }

    /** {@code 0.4 + 0.6 × max(EXPO, CRIT, DATA)}. Retained so a reader can see the gate that was applied. */
    public BigDecimal contextMultiplier() {
        return contextMultiplier;
    }

    /** Whether any factor used a fallback. Surfaced in the explanation per {@code PRD-RSK-018}. */
    public boolean anyFallbackApplied() {
        return inputs.stream().anyMatch(i -> i.fallback() != Fallback.NONE);
    }

    @Override
    public String toString() {
        return "RiskScore[" + subjectKind + " " + subjectId + " " + band + " " + value + " model=" + modelVersion
                + " coverage=" + coverage.confidence() + "]";
    }
}
