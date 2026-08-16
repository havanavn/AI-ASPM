package aspm.module.riskprioritization.domain;

import java.util.Objects;

/**
 * The coverage qualifier of DOC-28 section 9, carried by every aggregate score.
 *
 * <p><b>{@code PRD-RSK-027} names the failure this exists to prevent</b>: "A node with no data must not score
 * well. Presenting a favourable number over 30% coverage is the specific mechanism by which the platform would
 * produce a confident, wrong executive report."
 *
 * <p>Product principle 1 made structural: {@code INSUFFICIENT} is not a low score, it is <b>not a score</b>.
 * {@link #presentableAsPostureFigure()} returns false for it, and {@link AggregateScore} refuses to expose a
 * figure without consulting that.
 *
 * <p><b>{@code PRD-RSK-028} closes the obvious shortcut.</b> "Coverage MUST NOT be improvable by any action other
 * than acquiring data. In particular, retiring or excluding unmeasured assets MUST NOT raise the coverage
 * ratio… Otherwise the cheapest way to reach HIGH confidence is to exclude everything unmeasured, which inverts
 * the metric's meaning." The denominator is therefore assets *in scope*, and the constructor rejects a
 * denominator smaller than the measured count — the shape an exclusion-based inflation would take.
 */
public final class CoverageQualifier {

    public enum Confidence {
        /** ≥ 90% current, intelligence within threshold. */
        HIGH,
        /** ≥ 70% current. */
        MEDIUM,
        /** ≥ 40% current, <b>or</b> intelligence beyond threshold. */
        LOW,
        /** &lt; 40% current. A score at this level is a coverage gap, not a posture figure. */
        INSUFFICIENT
    }

    private final int assetsInScope;
    private final int assetsWithCurrentData;
    private final int assetsNeverMeasured;
    private final int oldestDataAgeDays;
    private final int intelligenceAgeDays;
    private final boolean intelligenceWithinThreshold;

    public CoverageQualifier(int assetsInScope, int assetsWithCurrentData, int assetsNeverMeasured,
            int oldestDataAgeDays, int intelligenceAgeDays, boolean intelligenceWithinThreshold) {
        if (assetsInScope < 0 || assetsWithCurrentData < 0 || assetsNeverMeasured < 0) {
            throw new IllegalArgumentException("asset counts are non-negative");
        }
        if (assetsWithCurrentData > assetsInScope) {
            // The shape an exclusion-based inflation takes: shrink the denominator, keep the numerator.
            throw new IllegalArgumentException(
                    "assetsWithCurrentData (" + assetsWithCurrentData + ") exceeds assetsInScope ("
                            + assetsInScope + "). PRD-RSK-028: coverage must not be improvable by excluding "
                            + "unmeasured assets, and a numerator larger than its denominator is what that "
                            + "exclusion looks like arithmetically.");
        }
        if (assetsNeverMeasured > assetsInScope) {
            throw new IllegalArgumentException("assetsNeverMeasured exceeds assetsInScope");
        }
        if (oldestDataAgeDays < 0 || intelligenceAgeDays < 0) {
            throw new IllegalArgumentException("ages are non-negative");
        }
        this.assetsInScope = assetsInScope;
        this.assetsWithCurrentData = assetsWithCurrentData;
        this.assetsNeverMeasured = assetsNeverMeasured;
        this.oldestDataAgeDays = oldestDataAgeDays;
        this.intelligenceAgeDays = intelligenceAgeDays;
        this.intelligenceWithinThreshold = intelligenceWithinThreshold;
    }

    /** The measured fraction. Zero in-scope assets yields zero, never one. */
    public double currentDataRatio() {
        if (assetsInScope == 0) {
            // Not 1.0. An empty scope has measured nothing, and "100% of nothing" is the arithmetic that
            // produces a perfect posture score for a node with no assets.
            return 0.0;
        }
        return (double) assetsWithCurrentData / assetsInScope;
    }

    /**
     * The confidence band of DOC-28 section 9's table.
     *
     * <p>Stale intelligence caps confidence at {@code LOW} regardless of asset coverage — the table's "or
     * intelligence beyond threshold". In an air-gapped deployment stale intelligence is the *normal* condition
     * ({@code PRD-SBM-013}), so this is a routine cap rather than an incident, and {@code INV-VUL-18} requires
     * the staleness to be visible wherever the score is used.
     */
    public Confidence confidence() {
        double ratio = currentDataRatio();
        if (ratio < 0.40) {
            return Confidence.INSUFFICIENT;
        }
        if (!intelligenceWithinThreshold) {
            return Confidence.LOW;
        }
        if (ratio >= 0.90) {
            return Confidence.HIGH;
        }
        if (ratio >= 0.70) {
            return Confidence.MEDIUM;
        }
        return Confidence.LOW;
    }

    /**
     * Whether a score qualified by this may be shown as a posture figure.
     *
     * <p>{@code PRD-RSK-027}: at {@code INSUFFICIENT} the score "MUST be presented as a coverage gap rather than
     * as a posture figure". Not a warning beside a number — the number itself must not be the answer.
     */
    public boolean presentableAsPostureFigure() {
        return confidence() != Confidence.INSUFFICIENT;
    }

    public int assetsInScope() {
        return assetsInScope;
    }

    public int assetsWithCurrentData() {
        return assetsWithCurrentData;
    }

    /** {@code PP-1}: measured-and-clean must be distinguishable from not-measured. This is the count. */
    public int assetsNeverMeasured() {
        return assetsNeverMeasured;
    }

    public int oldestDataAgeDays() {
        return oldestDataAgeDays;
    }

    public int intelligenceAgeDays() {
        return intelligenceAgeDays;
    }

    public boolean intelligenceWithinThreshold() {
        return intelligenceWithinThreshold;
    }

    /**
     * Recomputes coverage after assets are excluded from scope.
     *
     * <p>Exists to make {@code PRD-RSK-028} demonstrable rather than assumed: excluding assets removes them
     * from <b>both</b> the numerator and the denominator where they were measured, and from the denominator
     * only where they were not — which is the arithmetic that would raise the ratio. So unmeasured exclusions
     * are refused.
     */
    public CoverageQualifier withAssetsExcluded(int excludedCount, int excludedThatWereMeasured) {
        if (excludedThatWereMeasured > excludedCount) {
            throw new IllegalArgumentException("more measured exclusions than exclusions");
        }
        if (excludedCount > excludedThatWereMeasured) {
            throw new IllegalArgumentException(
                    "excluding " + (excludedCount - excludedThatWereMeasured) + " UNMEASURED asset(s) would "
                            + "raise the coverage ratio without acquiring any data, which PRD-RSK-028 "
                            + "prohibits: the cheapest route to HIGH confidence would be to exclude everything "
                            + "unmeasured, inverting the metric's meaning. Retire the asset if it is gone — "
                            + "retirement is audited and flagged where recent activity evidence exists.");
        }
        return new CoverageQualifier(assetsInScope - excludedCount,
                assetsWithCurrentData - excludedThatWereMeasured, assetsNeverMeasured,
                oldestDataAgeDays, intelligenceAgeDays, intelligenceWithinThreshold);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CoverageQualifier q
                && assetsInScope == q.assetsInScope
                && assetsWithCurrentData == q.assetsWithCurrentData
                && assetsNeverMeasured == q.assetsNeverMeasured
                && oldestDataAgeDays == q.oldestDataAgeDays
                && intelligenceAgeDays == q.intelligenceAgeDays
                && intelligenceWithinThreshold == q.intelligenceWithinThreshold;
    }

    @Override
    public int hashCode() {
        return Objects.hash(assetsInScope, assetsWithCurrentData, assetsNeverMeasured, oldestDataAgeDays,
                intelligenceAgeDays, intelligenceWithinThreshold);
    }
}
