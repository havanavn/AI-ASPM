package aspm.module.riskprioritization.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Why a score changed. DOC-28 section 8.3, {@code PRD-RSK-025}.
 *
 * <p>The requirement's rationale is the design brief: <i>"Why did our score change?" is asked constantly, and
 * speculation is the default answer.</i> The one cause that must never be conflated with the others is
 * {@link Cause#POPULATION_SHIFT}, "because it is the one cause where nothing about the finding changed, and
 * conflating it with a real change destroys trust in attribution generally".
 *
 * <h2>The attribution method, and its residual</h2>
 *
 * <p>Per-factor delta is computed by <b>counterfactual</b>: recompute the current score with that one factor held
 * at its previous value, and report the difference. This is deterministic and needs no data beyond the two
 * scores, both of which are self-contained ({@code PRD-RSK-023}).
 *
 * <p>Counterfactual deltas <b>do not sum to the total delta</b>, because the formula is not additive — the
 * contextual multiplier of DOC-28 section 6.1 multiplies the whole weighted sum, so two contextual factors moving
 * together interact. {@link #interactionResidual()} carries the difference explicitly rather than distributing it
 * silently across factors. An attribution that quietly balanced its own books would be reporting an arithmetic
 * convenience as a finding about the world.
 */
public final class ScoreChangeAttribution {

    /** The cause classes of DOC-28 section 8.3. */
    public enum Cause {
        /** Newly known-exploited, or an exploit prediction update. {@code KEV}, {@code EXP}. */
        INTELLIGENCE_UPDATE,
        /** Criticality reassigned, exposure or classification changed. {@code EXPO}, {@code CRIT}, {@code DATA}. */
        CONTEXT_CHANGE,
        /** A practitioner adjusted severity. Carries the actor and reason from the adjustment audit record. */
        SEVERITY_ADJUSTMENT,
        /**
         * The rank transform moved because the tenant's finding population changed (DOC-28 section 5.2).
         *
         * <p><b>Nothing about this finding changed.</b> Reported as its own class because a reader told their
         * score moved for an unstated reason will stop believing attribution altogether.
         */
        POPULATION_SHIFT,
        /** A weight or threshold reconfiguration. Affects every score at once. */
        MODEL_CHANGE,
        /** Coverage confidence moved; the score value itself may be unchanged. */
        COVERAGE_CHANGE
    }

    /**
     * One attributed component.
     *
     * @param delta the counterfactual effect on the 0–100 value
     * @param detail human-readable, e.g. {@code "KEV changed 0 -> 1"}
     */
    public record Component(Cause cause, Factor factor, int delta, String detail) {
    }

    private final UUID previousScoreId;
    private final UUID currentScoreId;
    private final int previousValue;
    private final int currentValue;
    private final ScoreBand previousBand;
    private final ScoreBand currentBand;
    private final List<Component> components;
    private final int interactionResidual;

    private ScoreChangeAttribution(RiskScore previous, RiskScore current, List<Component> components) {
        this.previousScoreId = previous.id();
        this.currentScoreId = current.id();
        this.previousValue = previous.valueForPrioritisationOnly();
        this.currentValue = current.valueForPrioritisationOnly();
        this.previousBand = previous.band();
        this.currentBand = current.band();
        this.components = List.copyOf(components);
        int attributed = components.stream().mapToInt(Component::delta).sum();
        this.interactionResidual = (currentValue - previousValue) - attributed;
    }

    /**
     * Derives the attribution between two scores for the same subject.
     *
     * @throws IllegalArgumentException where the subjects differ. Attributing a change between two different
     *     subjects' scores would produce a confident, meaningless answer
     */
    public static ScoreChangeAttribution between(RiskScore previous, RiskScore current) {
        Objects.requireNonNull(previous, "a previous score is required");
        Objects.requireNonNull(current, "a current score is required");
        if (!previous.subjectId().equals(current.subjectId())
                || previous.subjectKind() != current.subjectKind()) {
            throw new IllegalArgumentException(
                    "attribution compares two scores for the SAME subject; got " + previous.subjectKind() + " "
                            + previous.subjectId() + " and " + current.subjectKind() + " "
                            + current.subjectId());
        }
        if (current.computedAt().isBefore(previous.computedAt())) {
            throw new IllegalArgumentException(
                    "the current score was computed before the previous one; the arguments are reversed, and a "
                            + "reversed attribution reports every increase as a decrease");
        }

        List<Component> components = new ArrayList<>();
        Map<Factor, RiskScore.FactorInput> before = byFactor(previous);
        Map<Factor, RiskScore.FactorInput> after = byFactor(current);

        // The model change is attributed first and separately: it is the one cause that moves every score in the
        // tenancy at once, and DOC-28 section 13.2 names configuration change as the most efficient gaming path
        // precisely because it "appears nowhere in a finding-level audit review".
        boolean modelChanged = previous.modelVersion() != current.modelVersion()
                || !previous.weights().equals(current.weights())
                || !previous.thresholds().equals(current.thresholds());
        if (modelChanged) {
            RiskScore underPreviousModel = RiskScore.compute(current.id(), current.subjectKind(),
                    current.subjectId(), previous.modelVersion(), current.computedAt(), current.inputs(),
                    previous.weights(), previous.thresholds(), current.coverage(), current.populationVersion());
            components.add(new Component(Cause.MODEL_CHANGE, null,
                    current.valueForPrioritisationOnly() - underPreviousModel.valueForPrioritisationOnly(),
                    "model version " + previous.modelVersion() + " -> " + current.modelVersion()
                            + ", weights " + previous.weights().total() + " -> " + current.weights().total()));
        }

        boolean populationMoved = previous.populationVersion() != current.populationVersion();

        for (Factor factor : Factor.values()) {
            RiskScore.FactorInput was = before.get(factor);
            RiskScore.FactorInput is = after.get(factor);
            if (was.normalizedValue().compareTo(is.normalizedValue()) == 0
                    && was.fallback() == is.fallback()) {
                continue;
            }

            List<RiskScore.FactorInput> counterfactual = new ArrayList<>(current.inputs().size());
            for (RiskScore.FactorInput input : current.inputs()) {
                counterfactual.add(input.factor() == factor ? was : input);
            }
            RiskScore held = RiskScore.compute(current.id(), current.subjectKind(), current.subjectId(),
                    current.modelVersion(), current.computedAt(), counterfactual, current.weights(),
                    current.thresholds(), current.coverage(), current.populationVersion());
            int delta = current.valueForPrioritisationOnly() - held.valueForPrioritisationOnly();

            String detail = factor + " changed " + was.normalizedValue() + " -> " + is.normalizedValue()
                    + fallbackNote(was, is) + " (source " + is.sourceReference() + ")";
            components.add(new Component(causeOf(factor, populationMoved), factor, delta, detail));
        }

        if (previous.coverage().confidence() != current.coverage().confidence()) {
            // Zero delta on the value by construction: coverage qualifies a score, it does not enter the formula.
            // Recorded anyway, because DOC-28 section 8.3's last row is exactly this — "coverage qualifier
            // changed; score value unchanged" — and a score that became unpresentable while its number held
            // steady is the change a reader most needs told.
            components.add(new Component(Cause.COVERAGE_CHANGE, null, 0,
                    "coverage confidence " + previous.coverage().confidence() + " -> "
                            + current.coverage().confidence()
                            + (current.coverage().presentableAsPostureFigure()
                                    ? "" : "; no longer presentable as a posture figure (PRD-RSK-027)")));
        }

        return new ScoreChangeAttribution(previous, current, components);
    }

    private static Cause causeOf(Factor factor, boolean populationMoved) {
        return switch (factor) {
            case SEV -> Cause.SEVERITY_ADJUSTMENT;
            // A rank-transformed factor that moved while the population version also moved is attributed to the
            // population, not to intelligence. Getting this the wrong way round is the specific conflation
            // PRD-RSK-025 forbids.
            case EXP -> populationMoved ? Cause.POPULATION_SHIFT : Cause.INTELLIGENCE_UPDATE;
            case KEV -> Cause.INTELLIGENCE_UPDATE;
            case EXPO, CRIT, DATA -> Cause.CONTEXT_CHANGE;
            case REACH -> Cause.MODEL_CHANGE;
        };
    }

    private static String fallbackNote(RiskScore.FactorInput was, RiskScore.FactorInput is) {
        if (was.fallback() == is.fallback()) {
            return "";
        }
        return " [fallback " + was.fallback() + " -> " + is.fallback() + "]";
    }

    private static Map<Factor, RiskScore.FactorInput> byFactor(RiskScore score) {
        Map<Factor, RiskScore.FactorInput> map = new EnumMap<>(Factor.class);
        for (RiskScore.FactorInput input : score.inputs()) {
            map.put(input.factor(), input);
        }
        return map;
    }

    public UUID previousScoreId() {
        return previousScoreId;
    }

    public UUID currentScoreId() {
        return currentScoreId;
    }

    public int totalDelta() {
        return currentValue - previousValue;
    }

    public ScoreBand previousBand() {
        return previousBand;
    }

    public ScoreBand currentBand() {
        return currentBand;
    }

    public boolean bandChanged() {
        return previousBand != currentBand;
    }

    public List<Component> components() {
        return components;
    }

    /**
     * The part of the total delta not attributable to any single factor in isolation.
     *
     * <p>Non-zero where several factors moved together, because the contextual multiplier makes the formula
     * non-additive. Reported rather than absorbed: a reader comparing the components against the total will find
     * the difference, and finding it unexplained costs more trust than the residual itself.
     */
    public int interactionResidual() {
        return interactionResidual;
    }

    /**
     * Whether the change is attributable to something about the finding or its asset, as opposed to the
     * population moving underneath it or the model being reconfigured.
     *
     * <p>This is the distinction {@code PRD-RSK-025} requires be available, not merely derivable.
     */
    public boolean attributableToTheSubject() {
        return components.stream().anyMatch(c -> c.cause() != Cause.POPULATION_SHIFT
                && c.cause() != Cause.MODEL_CHANGE
                && c.cause() != Cause.COVERAGE_CHANGE);
    }
}
