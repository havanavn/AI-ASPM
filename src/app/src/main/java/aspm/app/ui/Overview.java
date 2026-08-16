package aspm.app.ui;

import aspm.module.insight.domain.PresentationState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The overview dashboard. DOC-12, DOC-08 §10.
 *
 * <p>An executive dashboard is where this platform is most able to mislead, and DOC-08 §10 exists because
 * of it. The first product principle — <b>absence of evidence is not evidence of absence</b> — has a
 * specific interface consequence here: a coverage figure of "0 critical findings" over an estate that was
 * never scanned is the most reassuring thing the product can display and the most wrong.
 *
 * <p>So every figure on this page is built through {@link Kpi}, which cannot render a number without the
 * population it was computed over. A card whose measured population is zero renders the unmeasured state
 * and the action that would measure it, with no numeral anywhere in the markup.
 *
 * <h2>Drill-down is a link, not a modal</h2>
 *
 * <p>Each card and chart points at the filtered list that produced it. A dashboard figure a user cannot
 * get behind is a figure they have to trust, and DOC-12 requires drill-down for that reason. Links also
 * mean the drill-down works with the keyboard, in a new tab, and in the printed report where the
 * stylesheet appends the target URL.
 */
public final class Overview {

    private Overview() {
    }

    /**
     * One headline figure.
     *
     * <p>{@code measuredPopulation} and {@code inScopePopulation} are required, not optional. That is the
     * whole mechanism: a caller cannot construct a card from a value alone, so there is no path by which
     * an unmeasured figure becomes a zero.
     */
    public record Kpi(String labelKey, long value, int measuredPopulation, int inScopePopulation,
            String href, Optional<Delta> delta, List<Chart.Point> spark) {

        public record Delta(int percent, boolean worse) {
        }
    }

    /** A coverage bar: how much of the estate a given measurement actually covers. */
    public record Coverage(String labelKey, int measured, int inScope, String href) {
    }

    public static String render(Messages messages, List<Kpi> kpis, List<Coverage> coverages,
            List<Chart.Point> trend, List<Chart.Bar> severity, String recentTableHtml) {
        Objects.requireNonNull(messages, "messages are required");

        StringBuilder out = new StringBuilder(4096);

        out.append("<div class=\"grid grid-kpi mb-6\">");
        for (Kpi kpi : kpis) {
            out.append(kpiCard(messages, kpi));
        }
        out.append("</div>");

        out.append("<div class=\"grid grid-2 mb-6\">");
        out.append(card(messages, "overview.trendTitle",
                Chart.trend(messages, "overview.trendTitle", trend), "/vulnerabilities"));
        out.append(card(messages, "overview.severityTitle",
                Chart.bars(messages, "overview.severityTitle", severity), "/vulnerabilities"));
        out.append("</div>");

        out.append("<div class=\"grid grid-2\">");
        out.append(card(messages, "overview.coverageTitle", coverage(messages, coverages), ""));
        out.append(card(messages, "overview.recentTitle", recentTableHtml, "/vulnerabilities"));
        out.append("</div>");

        return out.toString();
    }

    private static String kpiCard(Messages messages, Kpi kpi) {
        Optional<PresentationState> state = PresentationState.forMeasure(
                kpi.measuredPopulation(), kpi.inScopePopulation(), false);

        StringBuilder out = new StringBuilder();
        out.append("<a class=\"card\" href=").append(Html.attribute(kpi.href()))
                .append("><div class=\"kpi\">");
        out.append("<span class=\"kpi-label\">")
                .append(Html.text(messages.get(kpi.labelKey()))).append("</span>");

        if (state.isPresent()) {
            // No numeral. Not a zero, not a dash, not an em dash that a reader resolves to zero.
            out.append("<span class=\"kpi-value state-label fs-20\">")
                    .append(Html.text(messages.get("state.unmeasured"))).append("</span>")
                    .append("<span class=\"kpi-qualifier\">")
                    .append(Html.text(messages.get("overview.noMeasurement"))).append("</span>");
        } else {
            out.append("<span class=\"kpi-value tabular\">").append(kpi.value()).append("</span>");
            out.append("<span class=\"kpi-qualifier\">")
                    .append(Html.text(messages.get("honesty.coverage",
                            Integer.valueOf(kpi.measuredPopulation()),
                            Integer.valueOf(kpi.inScopePopulation()))))
                    .append("</span>");
            kpi.delta().ifPresent(delta -> out.append("<span class=\"kpi-delta ")
                    .append(delta.worse() ? "up" : "down").append("\">")
                    .append(delta.worse() ? "▲ " : "▼ ")
                    .append(Math.abs(delta.percent())).append("%</span>"));
            if (!kpi.spark().isEmpty()) {
                out.append("<div class=\"kpi-spark\">").append(Chart.sparkline(kpi.spark()))
                        .append("</div>");
            }
        }
        out.append("</div></a>");
        return out.toString();
    }

    private static String coverage(Messages messages, List<Coverage> coverages) {
        StringBuilder out = new StringBuilder("<div class=\"col gap-4\">");
        for (Coverage item : coverages) {
            boolean measured = item.inScope() > 0 && item.measured() > 0;
            int percent = measured
                    ? (int) Math.round(100.0 * item.measured() / item.inScope())
                    : 0;
            String fill = percent >= 90 ? "ok" : percent >= 60 ? "warn" : "danger";

            out.append("<div class=\"meter\">")
                    .append("<div class=\"row between\"><a class=\"link fs-12\" href=")
                    .append(Html.attribute(item.href())).append(">")
                    .append(Html.text(messages.get(item.labelKey()))).append("</a>");
            if (measured) {
                out.append("<span class=\"fs-12 tabular subtle\">").append(percent).append("%</span>");
            } else {
                // A hatched track and the word, never a 0% bar — which reads as "measured, and none".
                out.append("<span class=\"fs-12 state-label\">")
                        .append(Html.text(messages.get("state.unmeasured"))).append("</span>");
            }
            out.append("</div><div class=\"meter-track")
                    .append(measured ? "\"" : " unmeasured\"").append(">");
            if (measured) {
                out.append("<div class=\"meter-fill ").append(fill).append(" ")
                        .append(DesignSystem.widthClass(percent)).append("\"></div>");
            }
            out.append("</div>");
            if (measured) {
                out.append("<span class=\"fs-12 subtle\">")
                        .append(Html.text(messages.get("honesty.coverage",
                                Integer.valueOf(item.measured()), Integer.valueOf(item.inScope()))))
                        .append("</span>");
            }
            out.append("</div>");
        }
        out.append("</div>");
        return out.toString();
    }

    private static String card(Messages messages, String titleKey, String body, String drillDown) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2>")
                .append(Html.text(messages.get(titleKey))).append("</h2>");
        if (!drillDown.isEmpty()) {
            out.append("<div class=\"card-actions\"><a class=\"btn btn-ghost btn-sm\" href=")
                    .append(Html.attribute(drillDown)).append(">")
                    .append(Html.text(messages.get("overview.drillDown"))).append("</a></div>");
        }
        out.append("</div><div class=\"card-body\">").append(body).append("</div></section>");
        return out.toString();
    }
}
