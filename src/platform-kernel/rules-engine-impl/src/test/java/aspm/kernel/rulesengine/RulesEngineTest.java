package aspm.kernel.rulesengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.kernel.rulesengine.contract.Condition;
import aspm.kernel.rulesengine.contract.FactSet;
import aspm.kernel.rulesengine.contract.FactValue;
import aspm.kernel.rulesengine.contract.Operator;
import aspm.kernel.rulesengine.contract.RuleOutcome;
import aspm.kernel.rulesengine.domain.ConditionEvaluator;
import aspm.kernel.rulesengine.domain.ConditionValidator;
import aspm.kernel.rulesengine.domain.RuleMatcher;
import aspm.sharedkernel.OrgNodeId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** CON-PLT-012, PRD-WRK-033, and the most-specific-wins matching of DOC-28 section 11.1. */
class RulesEngineTest {

    private static Condition eq(String key, String value) {
        return new Condition.Comparison(key, Operator.EQUALS, new FactValue.Text(value));
    }

    private static Condition ordinalAtLeast(String key, int ordinal) {
        return new Condition.Comparison(key, Operator.GREATER_THAN_OR_EQUAL,
                new FactValue.Ordinal("band", ordinal));
    }

    @Nested
    @DisplayName("Three-valued logic — the reason this engine is not boolean")
    class ThreeValued {

        /** The single most important test in this class. */
        @Test
        @DisplayName("NOT over a missing fact is UNDEFINED, not TRUE")
        void negationOverMissingFactDoesNotPass() {
            var guard = new Condition.Not(eq("severity", "INFORMATIONAL"));
            var outcome = ConditionEvaluator.evaluate(guard, FactSet.empty());

            assertEquals(RuleOutcome.UNDEFINED, outcome,
                    "Under two-valued logic this returns TRUE, so a guard reading 'may close when NOT "
                            + "informational' would permit closing a finding whose severity was never "
                            + "recorded. That is a fail-open produced by a reasonable-looking guard over "
                            + "incomplete data — exactly what product principle 1 exists to prevent.");
            assertFalse(outcome.isTrue(), "a guard must not be satisfied by an undefined outcome");
        }

        @Test
        @DisplayName("a definite FALSE still dominates an UNDEFINED sibling in a conjunction")
        void definiteFalseDominatesInConjunction() {
            var facts = new FactSet(Map.of("state", new FactValue.Text("OPEN")));
            var condition = new Condition.All(List.of(eq("state", "CLOSED"), eq("absent_key", "x")));
            // Kleene AND: one clause is definitely false, so the conjunction is definitely false even
            // though the other is unknown. This lets a cheap disqualifying clause short-circuit.
            assertEquals(RuleOutcome.FALSE, ConditionEvaluator.evaluate(condition, facts));
        }

        @Test
        @DisplayName("an UNDEFINED clause prevents an otherwise-true conjunction")
        void undefinedPreventsTrueConjunction() {
            var facts = new FactSet(Map.of("state", new FactValue.Text("OPEN")));
            var condition = new Condition.All(List.of(eq("state", "OPEN"), eq("absent_key", "x")));
            assertEquals(RuleOutcome.UNDEFINED, ConditionEvaluator.evaluate(condition, facts));
        }

        @Test
        @DisplayName("a definite TRUE dominates an UNDEFINED sibling in a disjunction")
        void definiteTrueDominatesInDisjunction() {
            var facts = new FactSet(Map.of("state", new FactValue.Text("OPEN")));
            var condition = new Condition.Any(List.of(eq("state", "OPEN"), eq("absent_key", "x")));
            assertEquals(RuleOutcome.TRUE, ConditionEvaluator.evaluate(condition, facts));
        }

        @Test
        @DisplayName("cross-type comparison is UNDEFINED, not FALSE, so a broken rule is diagnosable")
        void crossTypeComparisonIsUndefined() {
            var facts = new FactSet(Map.of("severity", new FactValue.Ordinal("HIGH", 3)));
            assertEquals(RuleOutcome.UNDEFINED,
                    ConditionEvaluator.evaluate(eq("severity", "HIGH"), facts),
                    "reporting a type mismatch as false makes the rule quietly inert, which is the "
                            + "failure a tenant cannot diagnose because the rule looks correct");
        }

        @Test
        @DisplayName("a scope rule with no scope fact is UNDEFINED, not outside-the-subtree")
        void missingScopeFactIsUndefined() {
            var condition = new Condition.WithinSubtree("scope", new OrgNodeId(UUID.randomUUID()));
            assertEquals(RuleOutcome.UNDEFINED, ConditionEvaluator.evaluate(condition, FactSet.empty()),
                    "treating a missing scope as 'outside' would silently apply a more general policy");
        }
    }

    @Nested
    @DisplayName("PRD-WRK-033 — determinism and bounded cost, structurally")
    class DeterminismAndBounds {

        @Test
        @DisplayName("BigDecimal scale does not change a comparison")
        void scaleDoesNotAffectEquality() {
            var facts = new FactSet(Map.of("score", new FactValue.Decimal(new BigDecimal("7.50"))));
            var condition = new Condition.Comparison("score", Operator.EQUALS,
                    new FactValue.Decimal(new BigDecimal("7.5")));
            assertEquals(RuleOutcome.TRUE, ConditionEvaluator.evaluate(condition, facts),
                    "BigDecimal.equals distinguishes 7.5 from 7.50, which is a scale difference and not "
                            + "a value difference; a rule must not depend on how a value was typed");
        }

        @Test
        @DisplayName("a relational operator on an unordered type is rejected at construction")
        void relationalOnUnorderedTypeRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Condition.Comparison("owner", Operator.GREATER_THAN,
                            new FactValue.Id(UUID.randomUUID())),
                    "a relational comparison on an unordered type would be implementation-dependent, and "
                            + "comparison is authorization-relevant wherever a fact participates in a guard");
        }

        @Test
        @DisplayName("comparing against Absent is rejected; absence is tested with Not(Present)")
        void cannotCompareAgainstAbsent() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Condition.Comparison("x", Operator.EQUALS, new FactValue.Absent()));
            // The explicit form works and reads as intended.
            assertEquals(RuleOutcome.TRUE,
                    ConditionEvaluator.evaluate(new Condition.Not(new Condition.Present("x")),
                            FactSet.empty()));
        }

        @Test
        @DisplayName("evaluation is repeatable for the same condition and facts")
        void evaluationIsRepeatable() {
            var facts = new FactSet(Map.of(
                    "severity", new FactValue.Ordinal("HIGH", 3),
                    "labels", new FactValue.TextSet(Set.of("internet-facing", "pci"))));
            var condition = new Condition.All(List.of(
                    ordinalAtLeast("severity", 3),
                    new Condition.Comparison("labels", Operator.CONTAINS,
                            new FactValue.Text("internet-facing"))));
            var first = ConditionEvaluator.evaluate(condition, facts);
            for (int i = 0; i < 50; i++) {
                assertEquals(first, ConditionEvaluator.evaluate(condition, facts));
            }
            assertEquals(RuleOutcome.TRUE, first);
        }

        @Test
        @DisplayName("a condition exceeding the depth bound is rejected before activation")
        void depthBoundRejectsAtDefinitionTime() {
            Condition deep = eq("a", "b");
            for (int i = 0; i < ConditionValidator.MAX_DEPTH + 2; i++) {
                deep = new Condition.Not(deep);
            }
            var findings = ConditionValidator.validate(deep, Set.of("a"));
            assertFalse(ConditionValidator.isActivatable(findings));
            assertTrue(findings.stream()
                    .anyMatch(f -> f.kind() == ConditionValidator.Finding.Kind.TOO_DEEP));
        }

        @Test
        @DisplayName("a rule referencing an unsupplied fact is rejected before activation")
        void unknownFactKeyRejected() {
            var findings = ConditionValidator.validate(eq("typo_in_key", "x"), Set.of("severity"));
            assertFalse(ConditionValidator.isActivatable(findings));
            assertTrue(findings.stream()
                            .anyMatch(f -> f.kind() == ConditionValidator.Finding.Kind.UNKNOWN_FACT_KEY),
                    "a rule reading a never-populated fact evaluates UNDEFINED forever: it never fires "
                            + "and never errors, which is invisible in testing");
        }

        @Test
        @DisplayName("a catch-all is reported but not rejected")
        void catchAllIsReportedNotRejected() {
            var findings = ConditionValidator.validate(Condition.alwaysTrue(), Set.of());
            assertTrue(ConditionValidator.isActivatable(findings),
                    "DOC-28 section 11.2's default policy set needs a catch-all");
            assertTrue(findings.stream()
                    .anyMatch(f -> f.kind() == ConditionValidator.Finding.Kind.MATCHES_EVERYTHING));
        }
    }

    @Nested
    @DisplayName("DOC-28 section 11.1 — most-specific-wins matching")
    class Matching {

        private static final OrgNodeId ROOT = new OrgNodeId(UUID.randomUUID());
        private static final OrgNodeId UNIT = new OrgNodeId(UUID.randomUUID());

        private static FactSet knownExploitedInternetFacing() {
            return new FactSet(Map.of(
                    "score_band", new FactValue.Ordinal("CRITICAL", 4),
                    "known_exploited", new FactValue.Bool(true),
                    "exposure", new FactValue.Text("INTERNET_FACING"),
                    "scope", new FactValue.ScopePath(List.of(ROOT), UNIT)));
        }

        /** The DOC-28 section 11.2 default policy set, in the shape the engine sees it. */
        private static List<RuleMatcher.Candidate<String>> defaultPolicies() {
            return List.of(
                    new RuleMatcher.Candidate<>("known-exploited-internet-facing",
                            new Condition.All(List.of(
                                    new Condition.Comparison("known_exploited", Operator.EQUALS,
                                            new FactValue.Bool(true)),
                                    new Condition.Comparison("exposure", Operator.EQUALS,
                                            new FactValue.Text("INTERNET_FACING")))),
                            "3 business days"),
                    new RuleMatcher.Candidate<>("score-band-critical",
                            new Condition.Comparison("score_band", Operator.EQUALS,
                                    new FactValue.Ordinal("CRITICAL", 4)),
                            "7 business days"),
                    new RuleMatcher.Candidate<>("catch-all", Condition.alwaysTrue(), "no commitment"));
        }

        @Test
        @DisplayName("the most specific policy wins over a general one that also matches")
        void mostSpecificWins() {
            var result = RuleMatcher.match(defaultPolicies(), knownExploitedInternetFacing());

            assertTrue(result.matched());
            assertEquals("known-exploited-internet-facing",
                    result.unambiguousWinner().orElseThrow().ruleId(),
                    "observed exploitation on a reachable asset is the one case warranting interruption "
                            + "of planned work (DOC-28 section 11.2), and it must beat the broader "
                            + "critical-band policy that also matches");
            assertEquals(2, result.unambiguousWinner().orElseThrow().specificity());
        }

        @Test
        @DisplayName("a tie is reported as ambiguous rather than resolved arbitrarily")
        void tiesAreReportedNotGuessed() {
            var tied = List.of(
                    new RuleMatcher.Candidate<>("policy-a", ordinalAtLeast("score_band", 3), "14 days"),
                    new RuleMatcher.Candidate<>("policy-b", ordinalAtLeast("score_band", 2), "30 days"));
            var facts = new FactSet(Map.of("score_band", new FactValue.Ordinal("CRITICAL", 4)));

            var result = RuleMatcher.match(tied, facts);
            assertTrue(result.isAmbiguous(),
                    "both constrain one dimension, so specificity is equal. 'Shorter duration wins' is a "
                            + "service-level policy, not a rule-evaluation policy — checklist selection "
                            + "has no duration — so the engine must surface the tie rather than "
                            + "hardcoding a domain rule into the kernel");
            assertTrue(result.unambiguousWinner().isEmpty());
            assertEquals(2, result.winners().size());
        }

        @Test
        @DisplayName("ranking is stable regardless of candidate order")
        void rankingIsStable() {
            var facts = knownExploitedInternetFacing();
            var forward = RuleMatcher.match(defaultPolicies(), facts);
            var reversed = new java.util.ArrayList<>(defaultPolicies());
            java.util.Collections.reverse(reversed);
            var backward = RuleMatcher.match(reversed, facts);

            assertEquals(forward.winners().get(0).ruleId(), backward.winners().get(0).ruleId(),
                    "PRD-RSK-032 pins a policy version at clock start; a flapping selection would pin "
                            + "different policies for identical findings");
        }

        @Test
        @DisplayName("a rule evaluating UNDEFINED is reported separately from one that did not match")
        void undefinedIsReportedSeparately() {
            var candidates = List.of(
                    new RuleMatcher.Candidate<>("reads-missing-fact", eq("never_populated", "x"), "p"),
                    new RuleMatcher.Candidate<>("correctly-inapplicable", eq("exposure", "INTERNAL"), "q"));
            var result = RuleMatcher.match(candidates,
                    new FactSet(Map.of("exposure", new FactValue.Text("INTERNET_FACING"))));

            assertFalse(result.matched());
            assertEquals(1, result.undefined().size(),
                    "a rule that never fires because a fact is always absent is a configuration defect, "
                            + "and it is invisible if UNDEFINED and FALSE are the same value");
            assertEquals("reads-missing-fact", result.undefined().get(0).ruleId());
        }

        @Test
        @DisplayName("a disjunction's specificity is its least specific branch, not its most")
        void disjunctionSpecificityIsMinimum() {
            var gameable = new Condition.Any(List.of(
                    eq("exposure", "INTERNET_FACING"),
                    new Condition.All(List.of(eq("a", "1"), eq("b", "2"), eq("c", "3")))));
            assertEquals(1, gameable.specificity(),
                    "taking the maximum would let an author inflate specificity by adding an unreachable "
                            + "narrow alternative, which is a configuration gaming path");
        }

        @Test
        @DisplayName("subtree matching uses the recorded ancestor path")
        void subtreeMatchingUsesRecordedPath() {
            var facts = knownExploitedInternetFacing();
            assertEquals(RuleOutcome.TRUE,
                    ConditionEvaluator.evaluate(new Condition.WithinSubtree("scope", ROOT), facts));
            assertEquals(RuleOutcome.TRUE,
                    ConditionEvaluator.evaluate(new Condition.WithinSubtree("scope", UNIT), facts));
            assertEquals(RuleOutcome.FALSE,
                    ConditionEvaluator.evaluate(
                            new Condition.WithinSubtree("scope", new OrgNodeId(UUID.randomUUID())), facts));
        }
    }

    @Nested
    @DisplayName("CON-PLT-012 — one evaluator, four callers")
    class OneEvaluator {

        @Test
        @DisplayName("a workflow guard and a match rule agree on a definite FALSE")
        void definiteFalseAgreesAcrossCallers() {
            // A required-fields guard built from Present. Note this is FALSE, not UNDEFINED: Present is
            // the one test that IS defined over an absent fact, which is why it exists as its own variant.
            // A first version of this test asserted UNDEFINED here and was wrong about the engine.
            var guard = new Condition.All(List.of(
                    new Condition.Present("verification_method"),
                    new Condition.Present("verifier_id")));
            var incomplete = new FactSet(Map.of("verification_method", new FactValue.Text("RETEST")));

            assertEquals(RuleOutcome.FALSE, ConditionEvaluator.evaluate(guard, incomplete),
                    "INV-VUL-11: FIXED_VERIFIED requires both a verification method and a verifier, and "
                            + "the absence of the verifier is definitely known rather than unknown");

            var result = RuleMatcher.match(
                    List.of(new RuleMatcher.Candidate<>("r", guard, "payload")), incomplete);
            assertFalse(result.matched(), "the guard denies, so the equivalent match rule must not match");
            assertTrue(result.undefined().isEmpty(),
                    "a rule that correctly does not apply is not a configuration defect, which is why "
                            + "FALSE and UNDEFINED are kept apart");
        }

        @Test
        @DisplayName("a guard and a match rule agree on UNDEFINED, and both decline to fire")
        void undefinedAgreesAcrossCallers() {
            // The same condition reaching both callers, this time with a value comparison over a fact
            // that was never recorded — the case where the two-valued shortcut would diverge.
            var condition = new Condition.All(List.of(
                    eq("state", "OPEN"),
                    new Condition.Not(eq("severity", "INFORMATIONAL"))));
            var facts = new FactSet(Map.of("state", new FactValue.Text("OPEN")));

            var guardOutcome = ConditionEvaluator.evaluate(condition, facts);
            assertEquals(RuleOutcome.UNDEFINED, guardOutcome);
            assertFalse(guardOutcome.isTrue(), "the workflow guard denies (DOC-09 section 2.1 step 8)");

            var result = RuleMatcher.match(
                    List.of(new RuleMatcher.Candidate<>("r", condition, "payload")), facts);
            assertFalse(result.matched(), "the service-level match does not match, so a broader policy applies");
            assertEquals(1, result.undefined().size(),
                    "and the tenant can see the rule is inert rather than merely unmatched. One evaluator, "
                            + "one outcome, four callers — which is what CON-PLT-012 exists to guarantee, "
                            + "because divergence in one of them governs whether an approval gate applies");
        }
    }
}
