package aspm.module.insight.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A measure derived from variable-coverage data, with its qualifier <b>materialized alongside it</b>.
 *
 * <p>{@code CON-PLT-028} and {@code PRD-DSH-024}: coverage and freshness are materialized with the measure
 * rather than computed at presentation. The prompt states the reason directly — "This is what makes omitting
 * them require deliberate effort."
 *
 * <p>Computed at presentation, a qualifier is a join the renderer can skip, a field a template can drop, and a
 * lookup that fails silently under load. Materialized, it arrives with the number and there is no code path that
 * has the number without it: {@link #of} takes both, and there is no constructor taking a value alone.
 */
public record Measure(String label, java.math.BigDecimal value, Confidence confidence,
        CoverageQualifier coverage, Optional<Cause> improvementCause, String purposeStatement) {

    /** DOC-28 section 9's four bands, restated here because the projection cannot depend on that module. */
    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW,
        /** {@code H2}: at this level the coverage gap is primary and the measure is not rendered as a figure. */
        INSUFFICIENT;

        public boolean presentableAsFigure() {
            return this != INSUFFICIENT;
        }
    }

    /**
     * Why a measure moved. {@code H3}, {@code PRD-DSH-026}.
     *
     * <p>An improvement must carry its cause, and remediation must be distinguished from lost coverage. The two
     * look identical in the number and mean opposite things.
     */
    public enum Cause {
        REMEDIATION,
        /** The number improved because less was measured. Not an improvement. */
        LOST_COVERAGE,
        REMEDIATION_WITH_COVERAGE_GAIN,
        NO_CHANGE,
        /** Neither period had presentable coverage, so no claim is available. */
        INDETERMINATE;

        public boolean presentableAsImprovement() {
            return this == REMEDIATION || this == REMEDIATION_WITH_COVERAGE_GAIN;
        }
    }

    /**
     * The coverage and freshness travelling with the measure.
     *
     * @param unmeasuredPopulation how many in-scope objects contributed nothing. {@code H4} makes this visually
     *     distinct from zero and from empty, which requires knowing it rather than inferring it from an absence
     * @param dataAge freshness. {@code H13}'s intelligence staleness is a separate field because a measure can
     *     be built from fresh findings and stale intelligence
     */
    public record CoverageQualifier(int measuredPopulation, int unmeasuredPopulation, Duration dataAge,
            Duration intelligenceAge, boolean intelligenceStale, String normalizationBasis) {

        public CoverageQualifier {
            Objects.requireNonNull(dataAge, "the data age is required (PRD-DSH-024)");
            Objects.requireNonNull(intelligenceAge, "the intelligence age is required (PRD-VUL-008)");
            Objects.requireNonNull(normalizationBasis,
                    "the normalization basis is required (H5, PRD-DSH-035). An unstated normalization is "
                            + "indistinguishable from an unfair comparison.");
            if (measuredPopulation < 0 || unmeasuredPopulation < 0) {
                throw new IllegalArgumentException("populations cannot be negative");
            }
        }

        public int totalPopulation() {
            return measuredPopulation + unmeasuredPopulation;
        }

        /** True where nothing was measured — {@code H4}'s "unmeasured", distinct from a measured zero. */
        public boolean nothingMeasured() {
            return measuredPopulation == 0;
        }
    }

    public Measure {
        Objects.requireNonNull(label, "a label is required");
        Objects.requireNonNull(value, "a value is required");
        Objects.requireNonNull(confidence, "a confidence is required");
        Objects.requireNonNull(coverage,
                "a coverage qualifier is required and is MATERIALIZED with the measure (CON-PLT-028). "
                        + "Computed at presentation it is a join the renderer can skip, a field a template can "
                        + "drop, and a lookup that fails silently under load.");
        Objects.requireNonNull(improvementCause, "improvementCause is required, empty where not a comparison");
        Objects.requireNonNull(purposeStatement,
                "a purpose statement is required (H8, PRD-DSH-034). A metric about an individual with no "
                        + "stated purpose is one whose purpose the reader supplies.");
        if (purposeStatement.isBlank()) {
            throw new IllegalArgumentException("a blank purpose statement states no purpose");
        }
    }

    public static Measure of(String label, java.math.BigDecimal value, Confidence confidence,
            CoverageQualifier coverage, Cause improvementCause, String purposeStatement) {
        return new Measure(label, value, confidence, coverage, Optional.ofNullable(improvementCause),
                purposeStatement);
    }
}
