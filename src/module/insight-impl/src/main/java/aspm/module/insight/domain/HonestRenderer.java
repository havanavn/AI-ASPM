package aspm.module.insight.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Renders a measure. {@code H1} through {@code H18}, verified against <b>this output</b> per
 * {@code TST-DSH-002}: "A measure carrying its coverage in the API response and losing it in the interface has
 * lost it. The assertion belongs where the reader sees it."
 *
 * <h2>The honesty surfaces are emitted before the options are consulted</h2>
 *
 * <p>{@link #render} builds the mandatory lines first and applies {@link PresentationOptions} only to what
 * remains. There is no branch in which a theme, density, template or preference can reach the qualifier — not
 * because the code chooses not to, but because by the time the options are read the qualifier is already in the
 * output.
 *
 * <p>That ordering is the mechanism {@code H14} needs. A renderer that assembled everything and then filtered by
 * options would satisfy every test written against the default options and fail on the one combination nobody
 * tried, which is the failure {@code TST-DSH-001} exists to prevent.
 *
 * <h2>Why plain text</h2>
 *
 * <p>The assertions are about what a reader can see, and a text rendering is the smallest thing that makes
 * "can the reader see it" a testable question. A real interface renders differently; what carries over is that
 * the honesty content is produced by this layer and not assembled by the presentation one.
 */
public final class HonestRenderer {

    /** Marks generated content. {@code H9} requires it to survive export, so it is part of the text itself. */
    public static final String AI_GENERATED_MARKER = "[AI-generated]";

    /** {@code H10}: migrated records marked, surviving every presentation. */
    public static final String MIGRATED_MARKER = "[migrated from an external tracker]";

    /** {@code H11}: inbound-attributed comments marked. */
    public static final String INBOUND_MARKER = "[received by email]";

    /** {@code H4}: unmeasured, distinct from zero and from empty. */
    public static final String UNMEASURED = "not measured";

    private HonestRenderer() {
    }

    /**
     * Renders a measure with every honesty surface its content requires.
     *
     * @param options may vary wording and ordering; it cannot remove a line
     */
    public static String render(Measure measure, PresentationOptions options) {
        Objects.requireNonNull(measure, "a measure is required");
        Objects.requireNonNull(options, "presentation options are required");

        // ---- Mandatory lines, built BEFORE options are read. -----------------------------------------
        List<String> lines = new ArrayList<>();

        // H2: at insufficient confidence the coverage gap is primary and the measure is NOT a figure.
        if (!measure.confidence().presentableAsFigure()) {
            lines.add("COVERAGE GAP: " + measure.label() + " cannot be presented as a figure. "
                    + measure.coverage().measuredPopulation() + " of "
                    + measure.coverage().totalPopulation() + " in scope were measured.");
            lines.add("This is a gap in what is known, not a measurement of low risk (PRD-DSH-025).");
            lines.add(purposeLine(measure));
            return String.join("\n", applyPresentation(lines, options));
        }

        // H4: unmeasured is distinct from zero and from empty.
        String figure = measure.coverage().nothingMeasured()
                ? UNMEASURED
                : measure.value().toPlainString();
        lines.add(measure.label() + ": " + figure);

        // H1: coverage and freshness, always.
        lines.add("Coverage: " + measure.coverage().measuredPopulation() + " of "
                + measure.coverage().totalPopulation() + " measured, "
                + measure.coverage().unmeasuredPopulation() + " " + UNMEASURED
                + "; data " + measure.coverage().dataAge().toDays() + " day(s) old.");

        // H5: the normalization, stated.
        lines.add("Normalization: " + measure.coverage().normalizationBasis());

        // H13: intelligence staleness on affected output. Separate from data age because a measure can be
        // built from fresh findings and stale intelligence.
        if (measure.coverage().intelligenceStale()) {
            lines.add("Vulnerability intelligence is "
                    + measure.coverage().intelligenceAge().toDays() + " day(s) old and beyond the freshness "
                    + "threshold; exploit and known-exploited signals in this figure may be out of date "
                    + "(PRD-VUL-008).");
        }

        // H3: an improvement carries its cause, and lost coverage is not an improvement.
        measure.improvementCause().ifPresent(cause -> lines.add(causeLine(cause)));

        // H8: the purpose statement.
        lines.add(purposeLine(measure));

        return String.join("\n", applyPresentation(lines, options));
    }

    private static String purposeLine(Measure measure) {
        return "Purpose: " + measure.purposeStatement();
    }

    private static String causeLine(Measure.Cause cause) {
        return switch (cause) {
            case REMEDIATION -> "Change attributed to remediation, with coverage held.";
            case REMEDIATION_WITH_COVERAGE_GAIN ->
                    "Change attributed to remediation; coverage also rose.";
            case LOST_COVERAGE ->
                    "NOT AN IMPROVEMENT: the figure moved because coverage fell. Fewer objects were "
                            + "measured, so the change is not attributable to remediation (PRD-DSH-026).";
            case NO_CHANGE -> "No material change.";
            case INDETERMINATE ->
                    "No change claim is available: coverage was insufficient in at least one period.";
        };
    }

    /**
     * Applies the presentation options.
     *
     * <p>Takes an already-complete list and may reorder or reword it. It <b>cannot</b> shorten it — the return
     * is asserted to be the same size, which is the one place a future change could break {@code H14} and the
     * one place a test can catch it.
     */
    private static List<String> applyPresentation(List<String> lines, PresentationOptions options) {
        List<String> presented = new ArrayList<>(lines);

        if (options.preferNumericFirst() && presented.size() > 1) {
            // Reordering, not removal.
            presented.add(0, presented.remove(presented.size() - 1));
        }
        if (options.preferTerseLabels()) {
            presented.replaceAll(line -> line.replace("Coverage: ", "Cov: ")
                    .replace("Normalization: ", "Norm: "));
        }
        if (options.density() == PresentationOptions.Density.CONDENSED) {
            // The density setting a "hide secondary lines" implementation would key off. It compacts
            // whitespace and nothing else.
            presented.replaceAll(line -> line.replaceAll("\\s{2,}", " "));
        }

        if (presented.size() != lines.size()) {
            throw new IllegalStateException(
                    "presentation removed a line (H14, PRD-DSH-042). No honesty surface is suppressible by "
                            + "theme, density, template, or user preference — and the suppression paths differ, "
                            + "so an implementation that satisfied the default options would fail on the one "
                            + "combination nobody tried.");
        }
        return presented;
    }

    /**
     * Renders a closure figure. {@code H15}, {@code PRD-DSH-030}.
     *
     * <p>There is no parameter for a total alone: the breakdown and the total are produced together, because
     * "an undifferentiated closure rate is the metric most easily optimized by closing rather than fixing".
     */
    public static String renderClosure(int verified, java.util.Map<String, Integer> otherReasons,
            PresentationOptions options) {
        Objects.requireNonNull(otherReasons, "the other-reason breakdown is required");
        Objects.requireNonNull(options, "presentation options are required");
        int total = verified + otherReasons.values().stream().mapToInt(Integer::intValue).sum();

        List<String> lines = new ArrayList<>();
        lines.add("Closed: " + total + ", of which " + verified + " verified remediated.");
        lines.add("Other closure reasons: " + (otherReasons.isEmpty() ? "none" : otherReasons));
        return String.join("\n", applyPresentation(lines, options));
    }

    /**
     * Renders breach counts. {@code H16}, {@code PRD-DSH-029}: <b>never as a single figure.</b>
     *
     * <p>A single breach count invites the reading that the accountable team missed every one of them. Presented
     * by attribution, the same number becomes a question about where the delay actually was — which is the
     * PP-6 point applied to a dashboard.
     */
    public static String renderBreaches(java.util.Map<String, Integer> byAttribution,
            PresentationOptions options) {
        Objects.requireNonNull(byAttribution, "the attribution breakdown is required");
        Objects.requireNonNull(options, "presentation options are required");
        if (byAttribution.isEmpty()) {
            return String.join("\n", applyPresentation(List.of("Breaches: none in this period."), options));
        }
        List<String> lines = new ArrayList<>();
        lines.add("Breaches by attribution (never presented as a single figure, PRD-DSH-029):");
        byAttribution.forEach((attribution, count) -> lines.add("  " + attribution + ": " + count));
        return String.join("\n", applyPresentation(lines, options));
    }

    /**
     * Renders a utilization figure. {@code H7}, {@code PRD-DSH-033}.
     *
     * <p>Against a target band <b>with its reason</b>. A bare utilization percentage reads as a score to
     * maximise, and a team optimising it to a hundred percent has removed all slack from the function that
     * absorbs incidents.
     */
    public static String renderUtilization(java.math.BigDecimal utilization, int targetLowPercent,
            int targetHighPercent, String bandReason, PresentationOptions options) {
        Objects.requireNonNull(utilization, "a utilization figure is required");
        Objects.requireNonNull(options, "presentation options are required");
        if (bandReason == null || bandReason.isBlank()) {
            throw new IllegalArgumentException(
                    "the target band needs its reason (H7, PRD-DSH-033). Without it the band reads as a "
                            + "target to exceed rather than a range to stay within.");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Utilization: " + utilization.toPlainString() + "% against a target band of "
                + targetLowPercent + "–" + targetHighPercent + "%.");
        lines.add("Why this band: " + bandReason);
        return String.join("\n", applyPresentation(lines, options));
    }

    /**
     * Renders an estimate. {@code H12}, {@code PRD-RSK-040}.
     *
     * @param comparableCompletedItems calibration volume. Below the threshold the estimate is labelled
     *     low-confidence, because "an early estimate presented confidently and then missed damages trust in the
     *     whole capacity model"
     */
    public static String renderEstimate(java.math.BigDecimal days, int comparableCompletedItems,
            int calibrationThreshold, PresentationOptions options) {
        Objects.requireNonNull(days, "an estimate is required");
        Objects.requireNonNull(options, "presentation options are required");
        List<String> lines = new ArrayList<>();
        lines.add("Estimate: " + days.toPlainString() + " day(s).");
        lines.add(comparableCompletedItems < calibrationThreshold
                ? "LOW CONFIDENCE: only " + comparableCompletedItems + " comparable item(s) completed against "
                        + "a calibration threshold of " + calibrationThreshold + " (PRD-RSK-040)."
                : "Calibrated against " + comparableCompletedItems + " comparable completed item(s).");
        return String.join("\n", applyPresentation(lines, options));
    }

    /**
     * Renders content that may be generated, migrated or inbound. {@code H9}, {@code H10}, {@code H11}.
     *
     * <p>The markers are prefixed into the text rather than attached as metadata, which is what makes them
     * "survive export" — an export that dropped a metadata field would drop the label, and the reader of the
     * exported artifact is exactly the reader who cannot check.
     */
    public static String renderContent(String body, boolean aiGenerated, boolean migrated,
            boolean inboundEmail, PresentationOptions options) {
        Objects.requireNonNull(body, "a body is required");
        Objects.requireNonNull(options, "presentation options are required");
        List<String> markers = new ArrayList<>();
        if (aiGenerated) {
            markers.add(AI_GENERATED_MARKER);
        }
        if (migrated) {
            markers.add(MIGRATED_MARKER);
        }
        if (inboundEmail) {
            markers.add(INBOUND_MARKER);
        }
        List<String> lines = new ArrayList<>();
        lines.add(markers.isEmpty() ? body : String.join(" ", markers) + " " + body);
        return String.join("\n", applyPresentation(lines, options));
    }

    /**
     * Renders an assessment report's finding section. {@code H17}, {@code PRD-DSH-039}.
     *
     * <p>Coverage is a required argument, so findings cannot be rendered without it. "An assessment reporting no
     * findings is meaningless without knowing what was examined."
     */
    public static String renderAssessmentFindings(List<String> findingTitles, int itemsAssessed,
            int itemsTotal, PresentationOptions options) {
        Objects.requireNonNull(findingTitles, "the findings are required, possibly empty");
        Objects.requireNonNull(options, "presentation options are required");
        List<String> lines = new ArrayList<>();
        lines.add("Coverage: " + itemsAssessed + " of " + itemsTotal + " checklist item(s) assessed.");
        lines.add(findingTitles.isEmpty()
                ? "Findings: none among the items assessed."
                : "Findings: " + findingTitles.size());
        findingTitles.forEach(title -> lines.add("  " + title));
        return String.join("\n", applyPresentation(lines, options));
    }

    /**
     * Renders an aggregation with its basis. {@code H6}, {@code PRD-DSH-018}.
     *
     * @param asWas true where the aggregation used the scope as it was recorded, false where it used the
     *     current tree. Both are legitimate and they answer different questions; the reader must be told which
     */
    public static String renderAggregationBasis(String label, java.math.BigDecimal value, boolean asWas,
            PresentationOptions options) {
        Objects.requireNonNull(label, "a label is required");
        Objects.requireNonNull(value, "a value is required");
        Objects.requireNonNull(options, "presentation options are required");
        List<String> lines = new ArrayList<>();
        lines.add(label + ": " + value.toPlainString());
        lines.add(asWas
                ? "Basis: AS-WAS — aggregated over the organization structure as it was recorded at the time."
                : "Basis: AS-IS — aggregated over the current organization structure, so historical objects "
                        + "are counted under their present owner.");
        return String.join("\n", applyPresentation(lines, options));
    }

    /** The scope root, for {@code PRD-DSH-021}. See {@link ScopeRootResolution}. */
    public record ScopeRootResolution(java.util.UUID rootNodeId, String derivedFrom) {

        public ScopeRootResolution {
            Objects.requireNonNull(rootNodeId, "a root is required");
            Objects.requireNonNull(derivedFrom, "the derivation is required");
        }

        /**
         * Derives the root from the caller's authorization context. {@code PRD-DSH-021}: <b>never a
         * parameter.</b>
         *
         * <p>A scope root the client supplies is a scope the client chose, and a composition permission would
         * then convey visibility of any subtree the caller named. The signature takes the principal, and there
         * is no overload taking a node.
         */
        public static ScopeRootResolution forPrincipal(java.util.UUID principalId,
                java.util.function.Function<java.util.UUID, Optional<java.util.UUID>> authorizationContext) {
            Objects.requireNonNull(principalId, "a principal is required");
            Objects.requireNonNull(authorizationContext, "an authorization context is required");
            java.util.UUID root = authorizationContext.apply(principalId).orElseThrow(
                    () -> new IllegalStateException(
                            "no scope root for this principal. A composition with no derivable root renders "
                                    + "nothing rather than defaulting to the tenant root (PRD-DSH-021)."));
            return new ScopeRootResolution(root, "derived from the caller's authorization context");
        }
    }
}
