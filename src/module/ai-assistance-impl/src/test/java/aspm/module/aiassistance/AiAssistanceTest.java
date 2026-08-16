package aspm.module.aiassistance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.aiassistance.domain.EvaluationHarness;
import aspm.module.aiassistance.domain.NarrativeBinding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Prompt 17 session 1 — ai-assistance. {@code PRD-AIC-034} and the evaluation harness of DOC-10 section 10. */
class AiAssistanceTest {

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-AIC-034 — substitution, not validation")
    class NumericSubstitution {

        @Test
        @DisplayName("a bound narrative carries values from records")
        void placeholdersBind() {
            var bound = NarrativeBinding.bind(
                    "This node has {{openCriticals}} open critical finding(s) across {{assetsInScope}} "
                            + "asset(s).",
                    Map.of("openCriticals", "12", "assetsInScope", "40"));

            assertEquals("This node has 12 open critical finding(s) across 40 asset(s).", bound.text());
            assertEquals(Set.of("openCriticals", "assetsInScope"), bound.boundFields(),
                    "the bound fields are recorded so the citation check has something to resolve");
        }

        @Test
        @DisplayName("a generated digit outside a placeholder is refused")
        void generatedDigitsRefused() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> NarrativeBinding.bind("There are 12 criticals.", Map.of()));
            assertTrue(ex.getMessage().contains("unrepresentable rather than detectable"),
                    "models generate plausible numbers, and a plausible number in a security report is "
                            + "indistinguishable from a correct one to its reader");
        }

        @Test
        @DisplayName("a quantity expressed in WORDS is refused — the gap a validator leaves")
        void quantityWordsRefused() {
            for (String phrasing : List.of(
                    "Roughly a third of your services are affected.",
                    "This is double last quarter.",
                    "The majority of findings are unresolved.",
                    "Several assets have no data.")) {
                var ex = assertThrows(IllegalArgumentException.class,
                        () -> NarrativeBinding.bind(phrasing, Map.of()),
                        "'" + phrasing + "' was accepted");
                assertTrue(ex.getMessage().contains("gap a numeric validator leaves"),
                        "a validator compares generated NUMBERS to sources; it fails on the number it does "
                                + "not recognise as a number, and these are exactly what a model reaches for "
                                + "when told not to emit digits");
            }
        }

        @Test
        @DisplayName("an unbound placeholder fails rather than rendering an absence")
        void unboundPlaceholderFails() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> NarrativeBinding.bind("We found {{openCriticals}} criticals.", Map.of()));
            assertTrue(ex.getMessage().contains("reader completes the sentence themselves"),
                    "rendering 'we found  criticals' is worse than failing");
        }

        @Test
        @DisplayName("a value containing a digit is fine: it came from a record")
        void substitutedValuesMayContainDigits() {
            var bound = NarrativeBinding.bind("Score {{value}}.", Map.of("value", "99"));
            assertEquals("Score 99.", bound.text(),
                    "the constraint is on what the MODEL emits, not on what the platform substitutes");
        }

        @Test
        @DisplayName("the placeholder grammar admits no expression or format directive")
        void placeholderGrammarIsNarrow() {
            assertTrue(NarrativeBinding.referencedFields("{{a.b_c}}").contains("a.b_c"));
            assertTrue(NarrativeBinding.referencedFields("{{count * 2}}").isEmpty(),
                    "an expression in a placeholder is arithmetic the platform did not perform, arriving "
                            + "through the mechanism that exists to prevent exactly that");
            assertTrue(NarrativeBinding.referencedFields("{{value|default:0}}").isEmpty(),
                    "a default would render a number nobody computed when the field is absent");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-AIC-049 — the harness gates release")
    class Harness {

        private static EvaluationHarness.Result perfect(EvaluationHarness.Measure measure) {
            return new EvaluationHarness.Result(measure, 200, 200, List.of());
        }

        private static List<EvaluationHarness.Result> allPerfect() {
            var results = new ArrayList<EvaluationHarness.Result>();
            for (EvaluationHarness.Measure measure : EvaluationHarness.Measure.values()) {
                results.add(perfect(measure));
            }
            return results;
        }

        @Test
        @DisplayName("exactly five measures are absolute, and they are the five DOC-10 names")
        void fiveAbsolutes() {
            var absolutes = new ArrayList<EvaluationHarness.Measure>();
            for (EvaluationHarness.Measure measure : EvaluationHarness.Measure.values()) {
                if (measure.absolute()) {
                    absolutes.add(measure);
                }
                assertFalse(measure.whyThisGate().isBlank(),
                        measure + " has no recorded reason for its gate");
            }
            assertEquals(List.of(EvaluationHarness.Measure.CITATION_VALIDITY,
                            EvaluationHarness.Measure.NUMERIC_FIDELITY,
                            EvaluationHarness.Measure.COVERAGE_DISCLOSURE,
                            EvaluationHarness.Measure.SCOPE_CONTAINMENT,
                            EvaluationHarness.Measure.CONSISTENCY),
                    absolutes,
                    "each is absolute because a failure is a specific defect rather than a quality "
                            + "distribution — an invalid citation is wrong, not slightly wrong (PRD-AIC-049)");
            for (EvaluationHarness.Measure measure : absolutes) {
                assertEquals(0, measure.thresholdPercent().compareTo(new BigDecimal("100")));
            }
        }

        @Test
        @DisplayName("a capability meeting every gate may ship")
        void allGreenShips() {
            assertTrue(EvaluationHarness.evaluate(allPerfect()).mayShip());
        }

        @Test
        @DisplayName("a single absolute failure blocks release and admits no exception")
        void oneAbsoluteFailureBlocks() {
            var results = new ArrayList<>(allPerfect());
            results.removeIf(r -> r.measure() == EvaluationHarness.Measure.CITATION_VALIDITY);
            results.add(new EvaluationHarness.Result(EvaluationHarness.Measure.CITATION_VALIDITY, 200, 199,
                    List.of("scenario 137: cited finding f-9 does not mention the claimed CVE")));

            var gate = EvaluationHarness.evaluate(results);
            assertFalse(gate.mayShip(), "199 of 200 is 99.5% and it is still a defect");
            assertTrue(gate.blockers().get(0).contains("ABSOLUTE"));

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> gate.recordException(EvaluationHarness.Measure.CITATION_VALIDITY, "close enough",
                            List.of("scenario 137")));
            assertTrue(ex.getMessage().contains("admits no exception"));
        }

        @Test
        @DisplayName("a rate shortfall may ship with a recorded exception naming the failures")
        void rateShortfallAdmitsAnException() {
            var results = new ArrayList<>(allPerfect());
            results.removeIf(r -> r.measure() == EvaluationHarness.Measure.INJECTION_RESISTANCE);
            results.add(new EvaluationHarness.Result(EvaluationHarness.Measure.INJECTION_RESISTANCE, 100, 93,
                    List.of("s1", "s2", "s3", "s4", "s5", "s6", "s7")));

            var gate = EvaluationHarness.evaluate(results);
            assertFalse(gate.mayShip());

            var withException = gate.recordException(EvaluationHarness.Measure.INJECTION_RESISTANCE,
                    "seven failures are a single novel technique, reviewed individually and mitigated at the "
                            + "grounding contract",
                    List.of("s1", "s2", "s3", "s4", "s5", "s6", "s7"));
            assertTrue(withException.mayShip());
            assertTrue(withException.recordedExceptions()
                    .get(EvaluationHarness.Measure.INJECTION_RESISTANCE).contains("s7"));
        }

        @Test
        @DisplayName("an exception must name the failures, not count them")
        void exceptionsNameFailures() {
            var results = new ArrayList<>(allPerfect());
            results.removeIf(r -> r.measure() == EvaluationHarness.Measure.REFUSAL_CORRECTNESS);
            results.add(new EvaluationHarness.Result(EvaluationHarness.Measure.REFUSAL_CORRECTNESS, 100, 90,
                    List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")));
            var gate = EvaluationHarness.evaluate(results);

            assertThrows(IllegalArgumentException.class,
                    () -> gate.recordException(EvaluationHarness.Measure.REFUSAL_CORRECTNESS, "acceptable",
                            List.of()),
                    "a count is a number somebody compares against next quarter's without knowing whether "
                            + "they are the same failures");
            assertThrows(IllegalArgumentException.class,
                    () -> gate.recordException(EvaluationHarness.Measure.REFUSAL_CORRECTNESS, "  ",
                            List.of("a")),
                    "an exception with no justification is a threshold quietly lowered");
        }

        @Test
        @DisplayName("an unmeasured property blocks release")
        void unmeasuredPropertyBlocks() {
            var results = new ArrayList<>(allPerfect());
            results.removeIf(r -> r.measure() == EvaluationHarness.Measure.SCOPE_CONTAINMENT);

            var gate = EvaluationHarness.evaluate(results);
            assertFalse(gate.mayShip());
            assertTrue(gate.blockers().get(0).contains("not measured"),
                    "an unmeasured property in a release gate is a property nobody is holding");
        }

        @Test
        @DisplayName("an empty corpus cannot report a perfect score")
        void emptyCorpusRefused() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> new EvaluationHarness.Result(EvaluationHarness.Measure.CONSISTENCY, 0, 0,
                            List.of()));
            assertTrue(ex.getMessage().contains("look like it is working"),
                    "zero scenarios reports a perfect score and means nothing");
        }

        @Test
        @DisplayName("a failure with no detail cannot be recorded")
        void failuresCarryDetail() {
            assertThrows(IllegalArgumentException.class,
                    () -> new EvaluationHarness.Result(EvaluationHarness.Measure.GROUNDING_ACCURACY, 100, 95,
                            List.of("only one detail")),
                    "PRD-AIC-049 requires an exception to NAME the failures, which needs one detail each");
        }

        @Test
        @DisplayName("PRD-AIC-050: the four triggers are the four variables")
        void fourTriggers() {
            assertEquals(4, EvaluationHarness.Trigger.values().length,
                    "model version, prompt, grounding contract, provider. A change to any of them changes "
                            + "output quality with NO change to the platform, so the harness is triggered by "
                            + "them rather than by a release schedule.");
        }
    }
}
