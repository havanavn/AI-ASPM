package aspm.module.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.insight.domain.DesignToken;
import aspm.module.insight.domain.PresentationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Prompt 18 — interface. DOC-08 sections 9 and 10, and the token rule. */
class InterfaceStateTest {

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-08 section 9 — the states, each distinct")
    class States {

        @Test
        @DisplayName("PRD-UIX-022: unmeasured never renders as a numeral")
        void unmeasuredIsNeverZero() {
            String rendered = PresentationState.UNMEASURED.render("Submit an SBOM for this asset to measure it.");
            assertTrue(rendered.startsWith("Not measured."),
                    "rendering unmeasured as zero is THE interface-layer expression of the PP-1 failure the "
                            + "whole corpus guards against: a favourable figure produced by absent data");
            assertFalse(rendered.matches(".*\\d.*"), "no numeral anywhere in it");
            assertFalse(PresentationState.UNMEASURED.carriesAFigure());
        }

        @Test
        @DisplayName("no state carries a figure — a component reaches a numeral only when none applies")
        void noStateCarriesAFigure() {
            for (PresentationState state : PresentationState.values()) {
                assertFalse(state.carriesAFigure(), state + " permits a numeral");
            }
            assertTrue(PresentationState.forMeasure(40, 40, false).isEmpty(),
                    "empty means 'render the number', which is the only case where a component may produce a "
                            + "numeral");
        }

        @Test
        @DisplayName("the decision needs the measured population, so a null check cannot substitute")
        void theDecisionRequiresThePopulation() {
            assertEquals(Optional.of(PresentationState.UNMEASURED),
                    PresentationState.forMeasure(0, 40, false),
                    "forty assets in scope and none measured is UNMEASURED, not empty — a component asking "
                            + "'is the value null' gets a zero");
            assertEquals(Optional.of(PresentationState.EMPTY_NO_DATA),
                    PresentationState.forMeasure(0, 0, false));
            assertEquals(Optional.of(PresentationState.EMPTY_FILTERED),
                    PresentationState.forMeasure(0, 0, true),
                    "conflating the two empties tells a user their estate is clean when their filter is "
                            + "wrong, and both look identical in a table with no rows");
        }

        @Test
        @DisplayName("withheld confirms nothing about whether a value exists")
        void withheldConfirmsNothing() {
            String rendered = PresentationState.WITHHELD.render("n/a");
            assertEquals("Not shown to you.", rendered);
            assertFalse(rendered.contains("*") || rendered.matches(".*\\d.*"),
                    "a masked placeholder confirms the field has a value, which for a secret finding confirms "
                            + "a credential exists at that location (PRD-UIX-023, SEC-AUZ-022)");
        }

        @Test
        @DisplayName("a degraded region states the capability, the reason and what remains")
        void degradedIsNotAnEmptyRegion() {
            String rendered = PresentationState.DEGRADED.render(
                    "AI narrative is unavailable; the factor breakdown below is unaffected.");
            assertTrue(rendered.contains("unaffected"),
                    "an empty section in an executive report reads as 'nothing to report' (PRD-UIX-024)");
            assertThrows(IllegalArgumentException.class, () -> PresentationState.DEGRADED.render("  "),
                    "a bare state tells a user nothing they did not already see from the empty region");
        }

        @Test
        @DisplayName("PRD-UIX-025: reconnaissance material cannot reach an error surface")
        void errorTextIsChecked() {
            for (String unsafe : List.of(
                    "java.lang.NullPointerException at aspm.Foo",
                    "\tat aspm.module.Bar.baz(Bar.java:42)",
                    "Caused by: connection refused",
                    "SELECT * FROM finding WHERE id = ?",
                    "could not reach db-primary-01.internal",
                    "jdbc:postgresql://db/aspm")) {
                assertThrows(IllegalArgumentException.class,
                        () -> PresentationState.assertErrorTextIsSafe(unsafe),
                        "'" + unsafe + "' reached an error surface. Each is reconnaissance, and error surfaces "
                                + "are among the platform's LEAST-REVIEWED output paths (PRD-UIX-025).");
            }
            PresentationState.assertErrorTextIsSafe(
                    "That submission could not be accepted. Check the format and try again, or contact your "
                            + "platform administrator if it persists.");
        }

        @Test
        @DisplayName("every state renders distinguishably")
        void statesAreDistinct() {
            Set<String> rendered = new TreeSet<>();
            for (PresentationState state : PresentationState.values()) {
                rendered.add(state.render("detail"));
            }
            assertEquals(PresentationState.values().length, rendered.size(),
                    "two states rendering the same text are not visually distinct however they are styled");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Tokens — theming is substitution, and colour is never alone")
    class Tokens {

        @Test
        @DisplayName("a token missing a mode cannot be constructed")
        void everyTokenResolvesInEveryMode() {
            Map<DesignToken.Mode, String> partial = new LinkedHashMap<>();
            partial.put(DesignToken.Mode.LIGHT, "#fff");
            partial.put(DesignToken.Mode.DARK, "#000");

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> DesignToken.decoration("surface.background", partial));
            assertTrue(ex.getMessage().contains("nobody opens"),
                    "a token missing a mode falls back to whatever the component would have done — the literal "
                            + "the token exists to remove — and the missing mode is always the one nobody "
                            + "opens");
        }

        @Test
        @DisplayName("a semantic token declares a non-colour channel")
        void semanticTokensCarryASecondChannel() {
            assertThrows(IllegalArgumentException.class,
                    () -> DesignToken.semantic("semantic.state.error", DesignToken.NonColourChannel.NONE,
                            Map.of()),
                    "colour as the sole carrier of meaning fails accessibility and monochrome review, and "
                            + "high-contrast and print are the modes where it disappears entirely");
        }

        @Test
        @DisplayName("every state has a token, and each declares a channel")
        void everyStateIsTokenised() {
            var tokens = DesignToken.stateTokens();
            assertEquals(PresentationState.values().length, tokens.size());
            for (var entry : tokens.entrySet()) {
                assertNotEquals(DesignToken.NonColourChannel.NONE, entry.getValue().nonColourChannel(),
                        entry.getKey() + " carries meaning in colour alone");
                for (DesignToken.Mode mode : DesignToken.Mode.values()) {
                    assertNotEquals(null, entry.getValue().resolve(mode),
                            entry.getKey() + " does not resolve in " + mode);
                }
            }
        }

        @Test
        @DisplayName("the unmeasured token's channel is a text label, not an icon")
        void unmeasuredUsesAWord() {
            assertEquals(DesignToken.NonColourChannel.TEXT_LABEL,
                    DesignToken.stateTokens().get(PresentationState.UNMEASURED).nonColourChannel(),
                    "an icon is a convention a reader learns, and the one reader who has not learnt it reads "
                            + "an unmeasured tile as an empty one — which is the failure PRD-UIX-022 exists "
                            + "to prevent, arriving through the mitigation");
        }

        @Test
        @DisplayName("high contrast and monochrome print are modes, not afterthoughts")
        void accessibilityModesAreFirstClass() {
            var modes = Set.of(DesignToken.Mode.values());
            assertTrue(modes.contains(DesignToken.Mode.HIGH_CONTRAST));
            assertTrue(modes.contains(DesignToken.Mode.PRINT_MONOCHROME),
                    "an executive report is printed, and a colour-only signal in it is a signal nobody sees");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-08 section 10 — the fourteen honesty surfaces")
    class HonestySurfaces {

        /** DOC-08 section 10's table, verbatim in count and subject. */
        private static final List<String> SURFACES = List.of(
                "coverage and freshness with every measure",
                "coverage gap primary at insufficient confidence",
                "improvement distinguished from lost coverage",
                "unmeasured assets visible",
                "normalization statement on comparison",
                "aggregation basis label",
                "utilization against a target band with the reason",
                "individual metrics purpose statement",
                "generated content labelled",
                "AI unavailability stated",
                "estimation confidence where calibration is thin",
                "intelligence staleness",
                "inbound-attributed comments marked",
                "migrated records marked");

        @Test
        @DisplayName("there are fourteen, and the count is asserted")
        void fourteenSurfaces() {
            assertEquals(14, SURFACES.size(),
                    "DOC-08 section 10 gathers them 'so they are implemented as a coherent set rather than "
                            + "discovered one at a time' — and a count is what makes a missing one visible");
        }

        @Test
        @DisplayName("PRD-UIX-026: none is suppressible by any option combination")
        void noneIsSuppressible() {
            // The rendering-level assertion is HonestyAssertionTest.Suppression, over all 192 combinations.
            // This one is the interface-layer half: the option type has no field that could express
            // suppression, and the state that carries the unmeasured surface has no numeral form.
            for (var component : PresentationOptionsProbe.componentNames()) {
                String name = component.toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("hide") || name.startsWith("suppress") || name.startsWith("omit"),
                        "found " + component + ". Any of them being optional means it is removed by whoever "
                                + "wants a cleaner view (PRD-UIX-026).");
            }
            assertFalse(PresentationState.UNMEASURED.carriesAFigure(),
                    "the unmeasured surface cannot be turned into a figure by any option, because there is no "
                            + "figure form of it");
        }

        @Test
        @DisplayName("two surfaces are DOC-08 section 9 states rather than separate mechanisms")
        void twoSurfacesAreStates() {
            // "Unmeasured assets visible" and "AI unavailability stated" both point at section 9 in the
            // table. Recording that here so a reader does not go looking for a fourteenth mechanism.
            assertTrue(SURFACES.contains("unmeasured assets visible"));
            assertTrue(SURFACES.contains("AI unavailability stated"));
            assertEquals(PresentationState.UNMEASURED,
                    PresentationState.forMeasure(0, 40, false).orElseThrow());
            assertTrue(PresentationState.DEGRADED.render("AI narrative unavailable; the breakdown remains")
                    .contains("Unavailable"));
        }
    }

    /** Reads {@code PresentationOptions}' record components without importing it into the assertion. */
    private static final class PresentationOptionsProbe {

        static List<String> componentNames() {
            return java.util.Arrays.stream(
                            aspm.module.insight.domain.PresentationOptions.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();
        }
    }
}
