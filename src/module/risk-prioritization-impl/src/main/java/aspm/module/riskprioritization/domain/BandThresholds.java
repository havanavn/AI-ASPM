package aspm.module.riskprioritization.domain;

/**
 * Band thresholds, tenant-configurable within bounds (DOC-28 section 6.3, section 7.1).
 *
 * <p>Each value is the <b>inclusive lower bound</b> of the band. Defaults: 90+ critical, 70–89 high, 40–69
 * medium, 15–39 low, below 15 informational.
 *
 * <p>Stored on {@code scoring_model.band_thresholds} (DOC-04 section 18.1) as part of the model version, so a
 * threshold change is a model version change and historical scores keep the band they were assigned. A band
 * recomputed under today's thresholds against a two-year-old value would silently rewrite history.
 */
public record BandThresholds(int criticalFrom, int highFrom, int mediumFrom, int lowFrom) {

    public BandThresholds {
        // Strictly descending, or a value falls into two bands and the first match wins by accident of ordering.
        if (!(criticalFrom > highFrom && highFrom > mediumFrom && mediumFrom > lowFrom)) {
            throw new IllegalArgumentException(
                    "band thresholds must be strictly descending; got critical>=" + criticalFrom + ", high>="
                            + highFrom + ", medium>=" + mediumFrom + ", low>=" + lowFrom);
        }
        if (lowFrom < 1 || criticalFrom > 100) {
            throw new IllegalArgumentException(
                    "thresholds must lie within 1..100; got low>=" + lowFrom + " critical>=" + criticalFrom);
        }
    }

    public static BandThresholds defaults() {
        return new BandThresholds(90, 70, 40, 15);
    }

    public ScoreBand bandOf(int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("a score is an integer 0..100 (DOC-28 section 6.3); got " + value);
        }
        if (value >= criticalFrom) {
            return ScoreBand.CRITICAL;
        }
        if (value >= highFrom) {
            return ScoreBand.HIGH;
        }
        if (value >= mediumFrom) {
            return ScoreBand.MEDIUM;
        }
        if (value >= lowFrom) {
            return ScoreBand.LOW;
        }
        return ScoreBand.INFORMATIONAL;
    }
}
