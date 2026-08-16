package aspm.app.ui;

import aspm.module.insight.domain.PresentationState;
import java.util.Objects;
import java.util.Optional;

/**
 * Renders the seven presentation states of DOC-08 §9, and the measure that has none.
 *
 * <p>{@code PRD-UIX-022} is the one that matters and it is the reason this class exists rather than each
 * page formatting its own numbers: "Rendering unmeasured as zero is <b>the interface-layer expression of
 * the PP-1 failure the whole corpus guards against</b>: a favourable figure produced by absent data."
 *
 * <p>{@link #measure} takes the measured and in-scope populations and decides. A caller cannot hand it a
 * value and a state — there is no such method — so the only way to render a figure is to have supplied
 * the population that justifies it. A component asking "is the value null" gets a zero; asking this gets
 * {@code UNMEASURED}, and {@code UNMEASURED} has no numeral form.
 *
 * <p>Every state carries a text label as well as its styling. DOC-08 requires the seven to be visually
 * distinct, and the stylesheet distinguishes them by border treatment — dashed, solid, double, dotted —
 * rather than by colour, because high contrast and monochrome print are where a colour-only distinction
 * disappears and an executive report is printed.
 */
public final class StateRenderer {

    private StateRenderer() {
    }

    /**
     * A measure, with its coverage qualifier attached.
     *
     * <p>The qualifier is not optional and not separable. DOC-08 §10's first honesty surface is coverage
     * and freshness with every measure, and a figure whose qualifier is a sibling element is a figure
     * somebody will render without it.
     *
     * @param measuredPopulation how many in-scope objects contributed. Zero means unmeasured, whatever
     *     the value says
     */
    public static String measure(Messages messages, String labelKey, long value,
            int measuredPopulation, int inScopePopulation, boolean filterActive) {
        Objects.requireNonNull(messages, "messages are required");

        Optional<PresentationState> state =
                PresentationState.forMeasure(measuredPopulation, inScopePopulation, filterActive);
        if (state.isPresent()) {
            return state(messages, state.orElseThrow(), Optional.empty());
        }

        // A figure is presentable, and it is presented with what it was computed over.
        String coverage = measuredPopulation == 0
                ? messages.get("honesty.coverageNone")
                : messages.get("honesty.coverage", Integer.valueOf(measuredPopulation),
                        Integer.valueOf(inScopePopulation));
        return "<div class=\"measure\">"
                + "<span class=\"measure-label\">" + Html.text(messages.get(labelKey)) + "</span>"
                + "<span class=\"measure-value\">" + Html.text(String.valueOf(value)) + "</span>"
                + "<span class=\"measure-coverage\">" + Html.text(coverage) + "</span>"
                + "</div>";
    }

    /**
     * Renders a state.
     *
     * <p>{@code UNMEASURED} produces the word and its action, never a numeral — asserted by the test with
     * a regex over the rendered markup, because the rule is about what reaches the browser rather than
     * about what the model holds.
     */
    public static String state(Messages messages, PresentationState state, Optional<String> detail) {
        Objects.requireNonNull(messages, "messages are required");
        Objects.requireNonNull(state, "a state is required");

        String cssClass = switch (state) {
            case LOADING -> "state-loading";
            case EMPTY_NO_DATA, EMPTY_FILTERED -> "state-empty";
            case UNMEASURED -> "state-unmeasured";
            case WITHHELD -> "state-withheld";
            case DEGRADED -> "state-degraded";
            case ERROR -> "state-error";
        };
        String labelKey = switch (state) {
            case LOADING -> "state.loading";
            case EMPTY_NO_DATA -> "state.emptyNoData";
            case EMPTY_FILTERED -> "state.emptyFiltered";
            case UNMEASURED -> "state.unmeasured";
            case WITHHELD -> "state.withheld";
            case DEGRADED -> "state.degraded";
            case ERROR -> "state.error";
        };

        String label = switch (state) {
            // These four take the detail as an ICU argument, so the sentence is one translatable unit
            // rather than a label with text appended (INT-UIX-008).
            case EMPTY_NO_DATA, EMPTY_FILTERED, DEGRADED, ERROR ->
                    messages.get(labelKey, detail.orElse(""));
            default -> messages.get(labelKey);
        };

        StringBuilder out = new StringBuilder();
        // role=status announces the state to a screen reader when it replaces content, which is the
        // case INT-UIX-003's "no keyboard trap" does not cover: a silent region change.
        out.append("<div class=\"state ").append(cssClass).append("\" role=\"status\">");
        out.append("<span class=\"state-label\">").append(Html.text(label)).append("</span>");
        if (state == PresentationState.UNMEASURED && detail.isPresent()) {
            out.append("<span class=\"state-detail\">")
                    .append(Html.text(messages.get("state.unmeasured.detail", detail.orElseThrow())))
                    .append("</span>");
        }
        out.append("</div>");
        return out.toString();
    }
}
