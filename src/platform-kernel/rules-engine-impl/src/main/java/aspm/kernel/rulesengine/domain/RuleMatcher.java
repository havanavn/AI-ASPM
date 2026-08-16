package aspm.kernel.rulesengine.domain;

import aspm.kernel.rulesengine.contract.Condition;
import aspm.kernel.rulesengine.contract.FactSet;
import aspm.kernel.rulesengine.contract.RuleOutcome;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Most-specific-wins matching, per DOC-28 section 11.1 and the checklist selection of DOC-03.
 *
 * <p>"A policy matches on finding characteristics, and the most specific match applies. Where two
 * policies match with equal specificity, the shorter duration applies — a deliberate default: ambiguous
 * configuration should err toward more urgency, not less."
 *
 * <p><b>The engine ranks; it does not break the final tie.</b> "Shorter duration wins" is a
 * service-level policy, not a rule-evaluation policy — checklist selection has no duration and would
 * need a different rule. So this class returns the full set of equally-most-specific matches and the
 * caller applies its own tie-break. Making the engine pick would either hardcode the service-level rule
 * into the kernel, which ADR-027 forbids for a domain policy, or pick arbitrarily, which would be
 * non-deterministic across runs.
 *
 * <p>Ordering within a specificity band is nonetheless <b>stable</b>: candidates are ranked by
 * specificity descending, then by rule identifier ascending. A caller that ignores the tie set still
 * gets the same answer on every run, which matters because {@code PRD-RSK-032} pins a policy version at
 * clock start and a flapping selection would pin different policies for identical findings.
 */
public final class RuleMatcher {

    /** A candidate rule: its identity, its condition, and the payload the caller wants back. */
    public record Candidate<T>(String ruleId, Condition condition, T payload) {

        public Candidate {
            Objects.requireNonNull(ruleId, "ruleId is required; it is the stable tie-break key");
            Objects.requireNonNull(condition, "condition is required");
        }
    }

    /** A rule that matched, with the specificity it matched at. */
    public record Match<T>(String ruleId, int specificity, T payload) {}

    /**
     * The result of matching.
     *
     * @param winners every rule tied at the highest specificity, in stable order. Empty where nothing
     *     matched
     * @param undefined rules whose condition evaluated UNDEFINED. Reported separately because a rule
     *     that never fires due to a permanently absent fact is a configuration defect, and it is
     *     invisible if UNDEFINED is folded into "did not match"
     */
    public record MatchResult<T>(List<Match<T>> winners, List<Match<T>> undefined) {

        public MatchResult {
            winners = List.copyOf(Objects.requireNonNull(winners, "winners are required"));
            undefined = List.copyOf(Objects.requireNonNull(undefined, "undefined list is required"));
        }

        public boolean matched() {
            return !winners.isEmpty();
        }

        /** True where more than one rule tied and the caller must apply its own tie-break. */
        public boolean isAmbiguous() {
            return winners.size() > 1;
        }

        /** The single winner, or empty where nothing matched or the outcome is ambiguous. */
        public java.util.Optional<Match<T>> unambiguousWinner() {
            return winners.size() == 1 ? java.util.Optional.of(winners.get(0)) : java.util.Optional.empty();
        }
    }

    private RuleMatcher() {
        throw new AssertionError("not instantiable");
    }

    /** Evaluates every candidate and returns those tied at the highest specificity. */
    public static <T> MatchResult<T> match(List<Candidate<T>> candidates, FactSet facts) {
        Objects.requireNonNull(candidates, "candidates are required");
        Objects.requireNonNull(facts, "facts are required");

        List<Match<T>> matched = new ArrayList<>();
        List<Match<T>> undefined = new ArrayList<>();

        for (Candidate<T> candidate : candidates) {
            RuleOutcome outcome = ConditionEvaluator.evaluate(candidate.condition(), facts);
            var entry = new Match<>(candidate.ruleId(), candidate.condition().specificity(),
                    candidate.payload());
            switch (outcome) {
                case TRUE -> matched.add(entry);
                case UNDEFINED -> undefined.add(entry);
                case FALSE -> {
                    // Definitely not applicable. Nothing to report: a rule that correctly does not apply
                    // is not a defect, which is why FALSE and UNDEFINED are kept apart.
                }
            }
        }

        // Stable: specificity descending, then rule id ascending. The second key is what makes the
        // result identical across runs regardless of candidate order or map iteration.
        Comparator<Match<T>> ranking = Comparator
                .comparingInt(Match<T>::specificity).reversed()
                .thenComparing(Match::ruleId);
        matched.sort(ranking);
        undefined.sort(ranking);

        List<Match<T>> winners = matched.isEmpty()
                ? List.of()
                : matched.stream().filter(m -> m.specificity() == matched.get(0).specificity()).toList();

        return new MatchResult<>(winners, undefined);
    }
}
