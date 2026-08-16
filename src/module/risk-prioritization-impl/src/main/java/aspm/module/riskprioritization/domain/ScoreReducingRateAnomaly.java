package aspm.module.riskprioritization.domain;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Rate anomaly detection over score-reducing actions. {@code PRD-RSK-041}.
 *
 * <p>Surfaces an anomalous <b>rate</b> per principal and per organization node. It does not block, warn the actor,
 * or require approval: each individual action is legitimate, and "detection at the rate level catches gaming
 * without impeding normal work" (DOC-28 section 13.2, section 13.3).
 *
 * <h2>The comparison baseline, and what it costs</h2>
 *
 * <p>The rate is compared against <b>the same principal's own trailing rate</b> and against <b>the node's other
 * principals over the same window</b>. Both, not either:
 *
 * <ul>
 *   <li>Self-comparison alone misses a principal who has always operated at a gaming rate — there is nothing to
 *       deviate from.
 *   <li>Peer comparison alone misfires on a principal whose role legitimately concentrates one action; a triage
 *       specialist closes far more not-applicable findings than a developer, and flagging that every week trains
 *       the reviewer to dismiss the signal.
 * </ul>
 *
 * <p><b>The cost, stated:</b> requiring both means a coordinated shift across a whole node — everyone raising
 * their rate together — is not detected by this control. That case is what the periodic configuration-change
 * summary and the closure-reason breakdown of {@code PRD-RSK-042} are for. Naming the gap here rather than leaving
 * the reader to assume the detector is complete.
 *
 * <h2>Deliberately not AI</h2>
 *
 * <p>This is a threshold comparison over counts, not a learned anomaly model. PP-2 puts detection that leads to an
 * accusation on the deterministic side: a principal told their closure rate was flagged is entitled to the
 * arithmetic, and "the model found it anomalous" is not an answer that survives being challenged.
 */
public final class ScoreReducingRateAnomaly {

    /** Below this count in the window, no anomaly is raised regardless of ratio. */
    private static final int MINIMUM_COUNT_FOR_SIGNIFICANCE = 10;

    /** Multiple of the baseline that constitutes an anomaly. Tenant-configurable in deployment (⚙). */
    private static final double DEFAULT_ANOMALY_MULTIPLE = 3.0;

    /**
     * One window's observation for one principal, one action class, one node.
     *
     * @param nodeId the organization node the actions applied within — {@code PRD-RSK-041} requires per-node as
     *     well as per-principal, because a principal with broad scope can spread a gaming rate thinly enough that
     *     their overall rate looks ordinary
     */
    public record Observation(UUID principalId, UUID nodeId, ScoreReducingAction action, int countInWindow,
            Duration window) {

        public Observation {
            Objects.requireNonNull(principalId, "principalId is required");
            Objects.requireNonNull(nodeId, "nodeId is required");
            Objects.requireNonNull(action, "action is required");
            Objects.requireNonNull(window, "the observation window is required");
            if (countInWindow < 0) {
                throw new IllegalArgumentException("countInWindow cannot be negative");
            }
            if (window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("the window must be a positive duration");
            }
        }

        /** Actions per day, so windows of different lengths are comparable. */
        public double ratePerDay() {
            double days = window.toMillis() / 86_400_000.0;
            return countInWindow / days;
        }
    }

    /** A raised anomaly. Carries its own arithmetic, so the reviewer never has to reconstruct it. */
    public record Anomaly(Observation observation, double ownTrailingRatePerDay, double peerMedianRatePerDay,
            double multipleOfOwnBaseline, double multipleOfPeerMedian, String explanation) {
    }

    private ScoreReducingRateAnomaly() {
    }

    /**
     * Evaluates one observation.
     *
     * @param ownTrailingRatePerDay the same principal's rate for the same action over a longer trailing period
     * @param peerRatesPerDay every other principal's rate for the same action in the same node over the same
     *     window. May be empty, in which case there is no peer comparison and no anomaly is raised — a
     *     single-principal node offers nothing to compare against, and treating the absence of peers as
     *     confirmation would flag every small node's only practitioner
     * @return the anomaly, or empty
     */
    public static Optional<Anomaly> evaluate(Observation observation, double ownTrailingRatePerDay,
            List<Double> peerRatesPerDay) {
        Objects.requireNonNull(observation, "an observation is required");
        Objects.requireNonNull(peerRatesPerDay, "a peer rate list is required, possibly empty");

        if (observation.countInWindow() < MINIMUM_COUNT_FOR_SIGNIFICANCE) {
            // Three closures against a baseline of half a closure per day is a 6x ratio and means nothing.
            return Optional.empty();
        }
        if (peerRatesPerDay.isEmpty()) {
            return Optional.empty();
        }

        double observed = observation.ratePerDay();
        double peerMedian = median(peerRatesPerDay);

        // A zero baseline would divide to infinity. Treated as "no baseline to deviate from" rather than as an
        // infinite deviation, because a principal's first week of work would otherwise be flagged every time.
        double ownMultiple = ownTrailingRatePerDay > 0 ? observed / ownTrailingRatePerDay : 0.0;
        double peerMultiple = peerMedian > 0 ? observed / peerMedian : 0.0;

        boolean exceedsOwn = ownMultiple >= DEFAULT_ANOMALY_MULTIPLE;
        boolean exceedsPeers = peerMultiple >= DEFAULT_ANOMALY_MULTIPLE;
        if (!exceedsOwn || !exceedsPeers) {
            return Optional.empty();
        }

        String explanation = observation.action() + ": " + observation.countInWindow() + " action(s) in "
                + observation.window().toDays() + " day(s) is " + round(observed) + "/day, "
                + round(ownMultiple) + "x this principal's trailing rate of " + round(ownTrailingRatePerDay)
                + "/day and " + round(peerMultiple) + "x the node's peer median of " + round(peerMedian)
                + "/day. Each action is legitimate; the rate is the signal (PRD-RSK-041).";
        return Optional.of(new Anomaly(observation, ownTrailingRatePerDay, peerMedian, ownMultiple, peerMultiple,
                explanation));
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
