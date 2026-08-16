package aspm.module.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.insight.domain.HonestRenderer;
import aspm.module.insight.domain.Measure;
import aspm.module.insight.domain.PresentationOptions;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The eighteen honesty assertions of DOC-16 section 11.
 *
 * <p>DOC-16 introduces the suite as "verifying that the platform does not produce confident wrong output. It
 * exists because <b>every mechanism in it is individually easy to remove for a cleaner interface</b>."
 *
 * <p>{@code TST-DSH-002} binds where the assertion lives: "H1 through H18 MUST be verified against rendered
 * output, not against the data layer. A measure carrying its coverage in the API response and losing it in the
 * interface has lost it." So every assertion below reads the rendered string.
 *
 * <p>Same inventory shape as the isolation paths and the authorization assertions, for the same reason: a
 * missing assertion is invisible, and the inventory is what makes it visible.
 */
class HonestyAssertionTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Honesty {
        String value();
    }

    private static final List<String> ASSERTIONS = List.of(
            "H1", "H2", "H3", "H4", "H5", "H6", "H7", "H8", "H9",
            "H10", "H11", "H12", "H13", "H14", "H15", "H16", "H17", "H18");

    private static final PresentationOptions DEFAULTS = PresentationOptions.defaults();

    private static Measure.CoverageQualifier coverage(int measured, int unmeasured, boolean staleIntel) {
        return new Measure.CoverageQualifier(measured, unmeasured, Duration.ofDays(3),
                staleIntel ? Duration.ofDays(200) : Duration.ofHours(6), staleIntel,
                "size-normalized against " + (measured + unmeasured) + " in-scope asset(s)");
    }

    private static Measure measure(Measure.Confidence confidence, Measure.CoverageQualifier coverage,
            Measure.Cause cause) {
        return Measure.of("Node posture", new BigDecimal("42"), confidence, coverage, cause,
                "to identify which parts of the organization need attention, not to rank teams");
    }

    @Test
    @DisplayName("TST-DSH-002: every one of the eighteen assertions is claimed by a method")
    void theInventoryIsComplete() {
        Set<String> claimed = Arrays.stream(HonestyAssertionTest.class.getDeclaredClasses())
                .flatMap(inner -> Arrays.stream(inner.getDeclaredMethods()))
                .map(m -> m.getAnnotation(Honesty.class))
                .filter(java.util.Objects::nonNull)
                .map(Honesty::value)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> missing = new TreeSet<>(ASSERTIONS);
        missing.removeAll(claimed);
        assertTrue(missing.isEmpty(),
                "no method claims " + missing + ". Every mechanism in this suite is individually easy to "
                        + "remove for a cleaner interface, so an unclaimed assertion is one nobody is holding.");
        assertEquals(18, ASSERTIONS.size());
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("H1 to H8 — the measure itself")
    class MeasureSurfaces {

        @Test
        @Honesty("H1")
        @DisplayName("H1: every measure presents coverage and freshness in its rendered output")
        void coverageAndFreshnessRendered() {
            String rendered = HonestRenderer.render(
                    measure(Measure.Confidence.HIGH, coverage(95, 5, false), null), DEFAULTS);
            assertTrue(rendered.contains("Coverage: 95 of 100 measured"),
                    "materialized with the measure, not computed at presentation (CON-PLT-028); got:\n"
                            + rendered);
            assertTrue(rendered.contains("day(s) old"), "and freshness");
        }

        @Test
        @Honesty("H2")
        @DisplayName("H2: at insufficient confidence the gap is primary and the figure is not rendered")
        void insufficientConfidenceRendersTheGapNotTheFigure() {
            String rendered = HonestRenderer.render(
                    measure(Measure.Confidence.INSUFFICIENT, coverage(20, 80, false), null), DEFAULTS);

            assertTrue(rendered.startsWith("COVERAGE GAP:"),
                    "the gap is PRIMARY — first line, not a footnote under a number (PRD-DSH-025); got:\n"
                            + rendered);
            assertFalse(rendered.contains("42"),
                    "the measure must not be rendered as a figure at all. A favourable number over 20% "
                            + "coverage is the specific mechanism by which the platform produces a confident, "
                            + "wrong executive report.");
            assertTrue(rendered.contains("not a measurement of low risk"));
        }

        @Test
        @Honesty("H3")
        @DisplayName("H3: an improvement carries its cause, and lost coverage is not an improvement")
        void improvementCarriesItsCause() {
            String lostCoverage = HonestRenderer.render(
                    measure(Measure.Confidence.HIGH, coverage(50, 50, false), Measure.Cause.LOST_COVERAGE),
                    DEFAULTS);
            assertTrue(lostCoverage.contains("NOT AN IMPROVEMENT"),
                    "a finding count falling because a scanner stopped running looks identical to one "
                            + "falling because vulnerabilities were fixed; got:\n" + lostCoverage);

            String remediation = HonestRenderer.render(
                    measure(Measure.Confidence.HIGH, coverage(95, 5, false), Measure.Cause.REMEDIATION),
                    DEFAULTS);
            assertTrue(remediation.contains("attributed to remediation"));
            assertFalse(remediation.contains("NOT AN IMPROVEMENT"),
                    "and a genuine improvement must not be hedged, or the warning becomes noise");
        }

        @Test
        @Honesty("H4")
        @DisplayName("H4: unmeasured is distinct from zero and from empty")
        void unmeasuredIsDistinctFromZero() {
            var nothingMeasured = Measure.of("Open criticals", BigDecimal.ZERO, Measure.Confidence.LOW,
                    coverage(0, 40, false), null, "to show remaining exposure");
            var measuredZero = Measure.of("Open criticals", BigDecimal.ZERO, Measure.Confidence.HIGH,
                    coverage(40, 0, false), null, "to show remaining exposure");

            String unmeasured = HonestRenderer.render(nothingMeasured, DEFAULTS);
            String zero = HonestRenderer.render(measuredZero, DEFAULTS);

            assertTrue(unmeasured.contains("Open criticals: not measured"),
                    "a rendered 0 for an unmeasured population is the single most consequential confusion "
                            + "this platform can produce; got:\n" + unmeasured);
            assertTrue(zero.contains("Open criticals: 0"));
            assertFalse(zero.startsWith("Open criticals: not measured"));
        }

        @Test
        @Honesty("H5")
        @DisplayName("H5: a comparison states its normalization and each entity's coverage")
        void comparisonStatesItsNormalization() {
            String rendered = HonestRenderer.render(
                    measure(Measure.Confidence.HIGH, coverage(95, 5, false), null), DEFAULTS);
            assertTrue(rendered.contains("Normalization: size-normalized"),
                    "an unstated normalization is indistinguishable from an unfair comparison, and comparing "
                            + "a well-measured unit against a poorly-measured one without saying so favours "
                            + "the latter; got:\n" + rendered);
            assertTrue(rendered.contains("Coverage:"), "and each entity's coverage travels with it");
        }

        @Test
        @Honesty("H6")
        @DisplayName("H6: the aggregation basis is labelled as-was or as-is")
        void aggregationBasisLabelled() {
            String asWas = HonestRenderer.renderAggregationBasis("Findings last quarter",
                    new BigDecimal("312"), true, DEFAULTS);
            String asIs = HonestRenderer.renderAggregationBasis("Findings last quarter",
                    new BigDecimal("298"), false, DEFAULTS);

            assertTrue(asWas.contains("AS-WAS"));
            assertTrue(asIs.contains("AS-IS"));
            assertTrue(asIs.contains("present owner"),
                    "both are legitimate and they answer different questions; the reader must be told which, "
                            + "or a reorganization silently changes last quarter's numbers");
        }

        @Test
        @Honesty("H7")
        @DisplayName("H7: utilization is presented against a target band with its reason")
        void utilizationHasABandAndAReason() {
            String rendered = HonestRenderer.renderUtilization(new BigDecimal("78"), 70, 85,
                    "a function at full utilization absorbs no incident without dropping planned work",
                    DEFAULTS);
            assertTrue(rendered.contains("target band of 70–85%"));
            assertTrue(rendered.contains("Why this band:"),
                    "a bare percentage reads as a score to maximise, and a team optimising it to a hundred "
                            + "has removed all the slack that absorbs incidents");

            assertThrows(IllegalArgumentException.class,
                    () -> HonestRenderer.renderUtilization(new BigDecimal("78"), 70, 85, "  ", DEFAULTS));
        }

        @Test
        @Honesty("H8")
        @DisplayName("H8: an individual metric carries its purpose statement")
        void individualMetricsCarryTheirPurpose() {
            String rendered = HonestRenderer.render(
                    measure(Measure.Confidence.HIGH, coverage(95, 5, false), null), DEFAULTS);
            assertTrue(rendered.contains("Purpose: to identify which parts"),
                    "a metric about an individual with no stated purpose is one whose purpose the reader "
                            + "supplies, and the purpose a reader supplies for a per-person number is "
                            + "performance management; got:\n" + rendered);

            assertThrows(IllegalArgumentException.class,
                    () -> Measure.of("x", BigDecimal.ONE, Measure.Confidence.HIGH,
                            coverage(1, 0, false), null, "  "));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("H9 to H13 — provenance and staleness markers")
    class Markers {

        @Test
        @Honesty("H9")
        @DisplayName("H9: generated content is labelled, and the label survives export")
        void generatedContentLabelled() {
            String rendered = HonestRenderer.renderContent("This finding likely stems from...", true, false,
                    false, DEFAULTS);
            assertTrue(rendered.startsWith(HonestRenderer.AI_GENERATED_MARKER),
                    "the marker is prefixed into the TEXT rather than attached as metadata, which is what "
                            + "makes it survive export — and the reader of an exported artifact is exactly the "
                            + "reader who cannot check");
        }

        @Test
        @Honesty("H10")
        @DisplayName("H10: migrated records are marked in every presentation")
        void migratedRecordsMarked() {
            String rendered = HonestRenderer.renderContent("agreed, accepting the risk", false, true, false,
                    DEFAULTS);
            assertTrue(rendered.contains(HonestRenderer.MIGRATED_MARKER),
                    "the capability that preserves history could fabricate a record of a decision never "
                            + "made (DOC-26 section 8); the marker is the control");
        }

        @Test
        @Honesty("H11")
        @DisplayName("H11: inbound-attributed comments are marked")
        void inboundCommentsMarked() {
            String rendered = HonestRenderer.renderContent("Sure, go ahead", false, false, true, DEFAULTS);
            assertTrue(rendered.contains(HonestRenderer.INBOUND_MARKER),
                    "an email reply recorded as a comment was written without the platform's context, and a "
                            + "reader treating it as a considered in-platform response is reading it wrong");
        }

        @Test
        @Honesty("H12")
        @DisplayName("H12: estimation confidence is shown where calibration data is thin")
        void thinCalibrationIsLabelled() {
            String thin = HonestRenderer.renderEstimate(new BigDecimal("8"), 4, 50, DEFAULTS);
            assertTrue(thin.contains("LOW CONFIDENCE"),
                    "an early estimate presented confidently and then missed damages trust in the whole "
                            + "capacity model (PRD-RSK-040)");

            String calibrated = HonestRenderer.renderEstimate(new BigDecimal("8"), 120, 50, DEFAULTS);
            assertFalse(calibrated.contains("LOW CONFIDENCE"));
        }

        @Test
        @Honesty("H13")
        @DisplayName("H13: intelligence staleness is shown on affected output")
        void intelligenceStalenessShown() {
            String stale = HonestRenderer.render(
                    measure(Measure.Confidence.HIGH, coverage(95, 5, true), null), DEFAULTS);
            assertTrue(stale.contains("intelligence is 200 day(s) old"),
                    "in an air-gapped deployment stale intelligence is the NORMAL condition; presenting "
                            + "six-month-old exploit prediction as current is PP-1 violated in its most "
                            + "consequential form, because prioritization rests on it");

            String fresh = HonestRenderer.render(
                    measure(Measure.Confidence.HIGH, coverage(95, 5, false), null), DEFAULTS);
            assertFalse(fresh.contains("beyond the freshness threshold"),
                    "and a fresh dataset must not carry the warning, or it stops meaning anything");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("H14 — no honesty surface is suppressible, across every combination")
    class Suppression {

        @Test
        @Honesty("H14")
        @DisplayName("H14 / TST-DSH-001: every option combination renders every surface")
        void noSurfaceIsSuppressibleByAnyCombination() {
            var withEverything = measure(Measure.Confidence.HIGH, coverage(50, 50, true),
                    Measure.Cause.LOST_COVERAGE);
            var options = PresentationOptions.all();
            assertEquals(192, options.size(),
                    "4 themes x 3 densities x 4 templates x 2 x 2 preferences = 192. Exhaustive, because "
                            + "TST-DSH-001 says testing one path leaves the others open — and the count is "
                            + "asserted so adding an option without extending this suite fails here rather "
                            + "than silently leaving the new path untested.");

            for (PresentationOptions option : options) {
                String rendered = HonestRenderer.render(withEverything, option);
                String normalized = rendered.toLowerCase(Locale.ROOT);
                for (String surface : List.of("cov", "norm", "intelligence is 200 day(s) old",
                        "not an improvement", "purpose")) {
                    assertTrue(normalized.contains(surface),
                            "surface '" + surface + "' is missing under " + option + ". The suppression paths "
                                    + "differ — a template removes a section, a density hides a secondary "
                                    + "line, a preference turns off extra detail — and each would defeat the "
                                    + "same mechanism (TST-DSH-001).\nRendered:\n" + rendered);
                }
            }
        }

        @Test
        @Honesty("H14")
        @DisplayName("PresentationOptions has no field that could remove a surface")
        void optionsCannotExpressSuppression() {
            for (var component : PresentationOptions.class.getRecordComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("hide") || name.startsWith("show") || name.startsWith("suppress")
                                || name.startsWith("omit") || name.contains("sections"),
                        "found " + component.getName() + ". A boolean here would be set to false by somebody "
                                + "making a dashboard look tidier, in a commit nobody reads as a security "
                                + "change (PRD-DSH-042).");
            }
            for (var component : PresentationOptions.class.getRecordComponents()) {
                assertFalse(Map.class.isAssignableFrom(component.getType()),
                        "a free-form preference map is every future suppression flag at once");
            }
        }

        @Test
        @Honesty("H14")
        @DisplayName("the terse and condensed settings shorten wording, never the line count")
        void presentationVariesWordingNotPresence() {
            var subject = measure(Measure.Confidence.HIGH, coverage(50, 50, true),
                    Measure.Cause.LOST_COVERAGE);
            int baseline = HonestRenderer.render(subject, DEFAULTS).split("\n", -1).length;

            for (PresentationOptions option : PresentationOptions.all()) {
                assertEquals(baseline, HonestRenderer.render(subject, option).split("\n", -1).length,
                        "line count changed under " + option + "; presentation may reorder or reword, never "
                                + "remove");
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("H15 to H18 — figures that must not stand alone")
    class CompositeFigures {

        @Test
        @Honesty("H15")
        @DisplayName("H15: a closure figure distinguishes verified from other reasons")
        void closureDistinguishesVerified() {
            Map<String, Integer> other = new LinkedHashMap<>();
            other.put("NOT_APPLICABLE", 300);
            other.put("RISK_ACCEPTED", 50);

            String rendered = HonestRenderer.renderClosure(40, other, DEFAULTS);
            assertTrue(rendered.contains("390, of which 40 verified remediated"),
                    "an undifferentiated closure rate is the metric most easily optimized by closing rather "
                            + "than fixing, and it is the figure most likely to appear in an executive "
                            + "summary; got:\n" + rendered);
            assertTrue(rendered.contains("NOT_APPLICABLE=300"));
        }

        @Test
        @Honesty("H16")
        @DisplayName("H16: breach counts are presented by attribution, never as a single figure")
        void breachesByAttribution() {
            Map<String, Integer> byAttribution = new LinkedHashMap<>();
            byAttribution.put("REQUESTER_READINESS", 7);
            byAttribution.put("THIRD_PARTY", 4);
            byAttribution.put("CAPACITY", 2);

            String rendered = HonestRenderer.renderBreaches(byAttribution, DEFAULTS);
            assertTrue(rendered.contains("REQUESTER_READINESS: 7") && rendered.contains("CAPACITY: 2"),
                    "a single breach count invites the reading that the accountable team missed every one of "
                            + "them; by attribution the same number becomes a question about where the delay "
                            + "actually was (PP-6)");
            assertFalse(rendered.contains("Breaches: 13"), "and the single figure is not offered anywhere");
        }

        @Test
        @Honesty("H17")
        @DisplayName("H17: an assessment report cannot present findings without coverage")
        void assessmentFindingsCarryCoverage() {
            String withFindings = HonestRenderer.renderAssessmentFindings(
                    List.of("Broken object-level authorization on /orders"), 340, 351, DEFAULTS);
            assertTrue(withFindings.startsWith("Coverage: 340 of 351"),
                    "coverage is a required argument, so findings cannot be rendered without it");

            String none = HonestRenderer.renderAssessmentFindings(List.of(), 12, 351, DEFAULTS);
            assertTrue(none.contains("Coverage: 12 of 351"));
            assertTrue(none.contains("none among the items assessed"),
                    "'no findings' over 12 of 351 items is a different statement from 'no findings' over "
                            + "351, and only the qualified one is honest");
        }

        @Test
        @Honesty("H18")
        @DisplayName("H18: a scheduled report is generated per recipient, with no shared artifact")
        void scheduledReportsArePerRecipient() {
            // PRD-DSH-043. The scope root is derived from the recipient's authorization context, so two
            // recipients cannot share a rendering — and the resolver takes a principal with no overload
            // taking a node (PRD-DSH-021).
            var broadReader = UUID.randomUUID();
            var narrowReader = UUID.randomUUID();
            Map<UUID, UUID> rootByPrincipal = Map.of(
                    broadReader, new UUID(140, 1),
                    narrowReader, new UUID(140, 2));

            var broadRoot = HonestRenderer.ScopeRootResolution.forPrincipal(broadReader,
                    p -> Optional.ofNullable(rootByPrincipal.get(p)));
            var narrowRoot = HonestRenderer.ScopeRootResolution.forPrincipal(narrowReader,
                    p -> Optional.ofNullable(rootByPrincipal.get(p)));

            assertFalse(broadRoot.rootNodeId().equals(narrowRoot.rootNodeId()),
                    "a shared artifact across differing authorization is one reader seeing the other's "
                            + "scope (PRD-DSH-043)");
            assertTrue(broadRoot.derivedFrom().contains("authorization context"));

            assertThrows(IllegalStateException.class,
                    () -> HonestRenderer.ScopeRootResolution.forPrincipal(UUID.randomUUID(),
                            p -> Optional.empty()),
                    "a composition with no derivable root renders nothing rather than defaulting to the "
                            + "tenant root (PRD-DSH-021)");

            for (var m : HonestRenderer.ScopeRootResolution.class.getMethods()) {
                if (!m.getName().equals("forPrincipal")) {
                    continue;
                }
                assertEquals(2, m.getParameterCount(),
                        "there is no overload taking a scope root: a root the client supplies is a scope the "
                                + "client chose, and the composition permission would then convey visibility "
                                + "of any subtree the caller named");
            }
        }
    }
}
