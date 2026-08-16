package aspm.module.riskprioritization.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Whether an apparent improvement in node posture came from remediation or from lost coverage.
 * {@code PRD-RSK-026}.
 *
 * <p>DOC-28 section 8.3 states plainly: <b>"{@code PRD-RSK-026} is the most important requirement in this
 * document. Every other requirement makes the score correct; this one makes it honest."</b>
 *
 * <p>The failure it prevents: "A finding count falling because a scanner stopped running looks identical to one
 * falling because vulnerabilities were fixed. This is PP-1 at its most consequential: an improvement narrative
 * built on lost coverage will be presented to executives as success."
 *
 * <h2>Why this is a type and not a report query</h2>
 *
 * <p>Making it a value with a mandatory verdict means no caller can present an improvement without having
 * computed the verdict — the compiler asks the question. A reporting-layer check would be satisfiable by not
 * calling it, and the one code path that skipped it would be the one that produced the executive slide.
 */
public final class PostureImprovementAttribution {

    /** The verdict. Ordered from most to least defensible as a claim of progress. */
    public enum Verdict {
        /** Posture improved and coverage did not fall. The improvement is attributable to work done. */
        REMEDIATION,
        /**
         * Posture improved <b>and</b> coverage fell. The improvement is not defensible as progress.
         *
         * <p>Not merely flagged: {@link #presentableAsImprovement()} returns false, because the narrative is what
         * causes the harm and a footnote does not stop a narrative.
         */
        COVERAGE_LOSS,
        /** Posture improved while coverage also improved. Progress, with the caveat that the population grew. */
        REMEDIATION_WITH_COVERAGE_GAIN,
        /** Posture did not improve. Included so callers need no separate branch for the ordinary case. */
        NO_IMPROVEMENT,
        /**
         * Either period is below presentable coverage, so no improvement claim is available at all.
         *
         * <p>Distinguished from {@link #COVERAGE_LOSS}: there, a claim exists and is wrong; here, there was never
         * a figure to compare.
         */
        INDETERMINATE
    }

    private final BigDecimal previousValue;
    private final BigDecimal currentValue;
    private final CoverageQualifier previousCoverage;
    private final CoverageQualifier currentCoverage;
    private final Verdict verdict;

    private PostureImprovementAttribution(NodePosture previous, NodePosture current) {
        Objects.requireNonNull(previous, "a previous posture is required");
        Objects.requireNonNull(current, "a current posture is required");
        if (!previous.nodeId().equals(current.nodeId())) {
            throw new IllegalArgumentException("improvement is compared within one node; got "
                    + previous.nodeId() + " and " + current.nodeId());
        }
        this.previousValue = previous.valueForPrioritisationOnly();
        this.currentValue = current.valueForPrioritisationOnly();
        this.previousCoverage = previous.coverage();
        this.currentCoverage = current.coverage();

        // Lower posture is better, so an improvement is a decrease.
        boolean improved = currentValue.compareTo(previousValue) < 0;
        double coverageDelta = currentCoverage.currentDataRatio() - previousCoverage.currentDataRatio();

        if (!previousCoverage.presentableAsPostureFigure() || !currentCoverage.presentableAsPostureFigure()) {
            this.verdict = Verdict.INDETERMINATE;
        } else if (!improved) {
            this.verdict = Verdict.NO_IMPROVEMENT;
        } else if (coverageDelta < 0) {
            this.verdict = Verdict.COVERAGE_LOSS;
        } else if (coverageDelta > 0) {
            this.verdict = Verdict.REMEDIATION_WITH_COVERAGE_GAIN;
        } else {
            this.verdict = Verdict.REMEDIATION;
        }
    }

    public static PostureImprovementAttribution between(NodePosture previous, NodePosture current) {
        return new PostureImprovementAttribution(previous, current);
    }

    public Verdict verdict() {
        return verdict;
    }

    /**
     * Whether the change may be presented as an improvement.
     *
     * <p>False for {@link Verdict#COVERAGE_LOSS} even though the number genuinely fell, because the number
     * falling is not the claim being made — "we improved" is, and that claim is unsupported when the population
     * measured shrank.
     */
    public boolean presentableAsImprovement() {
        return verdict == Verdict.REMEDIATION || verdict == Verdict.REMEDIATION_WITH_COVERAGE_GAIN;
    }

    /**
     * The sentence to present with the figure. Mandatory rather than optional, per {@code PRD-RSK-026}'s
     * requirement that the distinction be <b>presented</b> and not merely determined.
     */
    public String presentation() {
        return switch (verdict) {
            case REMEDIATION -> "Posture improved from " + previousValue + " to " + currentValue
                    + " with coverage held at " + percent(currentCoverage) + "% of in-scope assets.";
            case REMEDIATION_WITH_COVERAGE_GAIN -> "Posture improved from " + previousValue + " to "
                    + currentValue + " while coverage rose from " + percent(previousCoverage) + "% to "
                    + percent(currentCoverage) + "%.";
            case COVERAGE_LOSS -> "Posture fell from " + previousValue + " to " + currentValue
                    + ", but coverage also fell from " + percent(previousCoverage) + "% to "
                    + percent(currentCoverage) + "%. This is NOT an improvement: fewer assets were measured, so "
                    + "the change is not attributable to remediation (PRD-RSK-026).";
            case NO_IMPROVEMENT -> "Posture moved from " + previousValue + " to " + currentValue
                    + " at " + percent(currentCoverage) + "% coverage.";
            case INDETERMINATE -> "No improvement figure is available: coverage was "
                    + previousCoverage.confidence() + " then " + currentCoverage.confidence()
                    + ", and a posture figure requires better than INSUFFICIENT (PRD-RSK-027).";
        };
    }

    private static long percent(CoverageQualifier coverage) {
        return Math.round(coverage.currentDataRatio() * 100);
    }
}
