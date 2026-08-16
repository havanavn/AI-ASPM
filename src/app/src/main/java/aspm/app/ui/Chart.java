package aspm.app.ui;

import java.util.List;
import java.util.Objects;

/**
 * Server-rendered SVG charts. ADR-058, {@code INT-UIX-006}, {@code PRD-UIX-022}.
 *
 * <p>{@code INT-UIX-006}: "Charts MUST have a keyboard-accessible tabular alternative conveying the same
 * information." Every method here emits the table alongside the graphic rather than offering to generate
 * it — an alternative produced on request is an alternative that is missing when the request path breaks,
 * and this one is plain markup inside a {@code <details>}.
 *
 * <h2>An unmeasured series is not a flat line</h2>
 *
 * <p>{@code PRD-UIX-022} again, in the form it takes in a chart: a period with no measurement plotted at
 * zero is a chart that says the estate was clean. So a null point breaks the line and the tabular
 * alternative says "not measured" in words. A trend drawn through absent data is the most persuasive way
 * this platform could lie.
 */
public final class Chart {

    private Chart() {
    }

    /** One point. A null value means <b>not measured</b>, which is different from zero. */
    public record Point(String label, Long value) {

        public Point {
            Objects.requireNonNull(label, "a label is required");
        }

        public boolean measured() {
            return value != null;
        }
    }

    /** A bar, with the severity class that colours it and the text label that survives monochrome. */
    public record Bar(String label, long value, String severityClass) {
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * A sparkline for a KPI card. Decorative by design: the figure beside it carries the number, and the
     * tabular alternative belongs to the full chart rather than to every thumbnail.
     */
    public static String sparkline(List<Point> points) {
        if (points.size() < 2) {
            return "";
        }
        int w = 220;
        int h = 36;
        long max = points.stream().filter(Point::measured).mapToLong(Point::value).max().orElse(1);
        long min = points.stream().filter(Point::measured).mapToLong(Point::value).min().orElse(0);
        long span = Math.max(1, max - min);

        StringBuilder path = new StringBuilder();
        boolean penDown = false;
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (!point.measured()) {
                // The pen lifts. A line drawn across an unmeasured period asserts a value nobody measured.
                penDown = false;
                continue;
            }
            double x = (double) i / (points.size() - 1) * (w - 4) + 2;
            double y = h - 3 - ((double) (point.value() - min) / span) * (h - 8);
            path.append(penDown ? " L" : " M").append(fmt(x)).append(' ').append(fmt(y));
            penDown = true;
        }
        return "<svg class=\"chart spark\" viewBox=\"0 0 " + w + " " + h + "\" role=\"presentation\" "
                + "aria-hidden=\"true\" preserveAspectRatio=\"none\">"
                + "<path class=\"series-line\" d=\"" + path.toString().trim() + "\"/></svg>";
    }

    /**
     * A trend line with axes, and the table that conveys the same information.
     *
     * @param titleKey a message key for the accessible name. Not text: {@code INT-UIX-008} forbids a
     *     concatenated sentence and an accessible name is a sentence a screen reader reads
     */
    public static String trend(Messages messages, String titleKey, List<Point> points) {
        int w = 640;
        int h = 200;
        int padL = 44;
        int padB = 26;
        int padT = 12;
        int padR = 8;

        long max = points.stream().filter(Point::measured).mapToLong(Point::value).max().orElse(1);
        long niceMax = niceCeiling(max);

        StringBuilder svg = new StringBuilder();
        svg.append("<svg class=\"chart\" viewBox=\"0 0 ").append(w).append(' ').append(h)
                .append("\" role=\"img\" aria-labelledby=\"chart-").append(titleKey.hashCode())
                .append("\">");
        svg.append("<title id=\"chart-").append(titleKey.hashCode()).append("\">")
                .append(Html.text(messages.get(titleKey))).append("</title>");

        // Grid and y ticks.
        for (int i = 0; i <= 4; i++) {
            double y = padT + (h - padT - padB) * (1 - i / 4.0);
            long value = niceMax * i / 4;
            svg.append("<line class=\"grid-line\" x1=\"").append(padL).append("\" y1=\"").append(fmt(y))
                    .append("\" x2=\"").append(w - padR).append("\" y2=\"").append(fmt(y)).append("\"/>");
            svg.append("<text class=\"tick\" x=\"").append(padL - 8).append("\" y=\"").append(fmt(y + 3))
                    .append("\" text-anchor=\"end\">").append(value).append("</text>");
        }

        StringBuilder line = new StringBuilder();
        StringBuilder dots = new StringBuilder();
        boolean penDown = false;
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            double x = padL + (double) i / Math.max(1, points.size() - 1) * (w - padL - padR);
            if (i % Math.max(1, points.size() / 6) == 0) {
                svg.append("<text class=\"tick\" x=\"").append(fmt(x)).append("\" y=\"")
                        .append(h - padB + 16).append("\" text-anchor=\"middle\">")
                        .append(Html.text(point.label())).append("</text>");
            }
            if (!point.measured()) {
                penDown = false;
                continue;
            }
            double y = padT + (h - padT - padB) * (1 - (double) point.value() / Math.max(1, niceMax));
            line.append(penDown ? " L" : " M").append(fmt(x)).append(' ').append(fmt(y));
            dots.append("<circle class=\"point\" cx=\"").append(fmt(x)).append("\" cy=\"")
                    .append(fmt(y)).append("\" r=\"2.5\"/>");
            penDown = true;
        }
        svg.append("<line class=\"axis\" x1=\"").append(padL).append("\" y1=\"").append(h - padB)
                .append("\" x2=\"").append(w - padR).append("\" y2=\"").append(h - padB).append("\"/>");
        svg.append("<path class=\"series-line\" d=\"").append(line.toString().trim()).append("\"/>");
        svg.append(dots);
        svg.append("</svg>");

        return svg + tabularAlternative(messages, points);
    }

    /** A horizontal bar chart. Used for distributions where the category order carries meaning. */
    public static String bars(Messages messages, String titleKey, List<Bar> bars) {
        long max = bars.stream().mapToLong(Bar::value).max().orElse(1);
        StringBuilder out = new StringBuilder();
        out.append("<div class=\"col\" role=\"img\" aria-label=")
                .append(Html.attribute(messages.get(titleKey))).append(">");
        for (Bar bar : bars) {
            int percent = (int) Math.round(100.0 * bar.value() / Math.max(1, max));
            out.append("<div class=\"meter\">")
                    .append("<div class=\"row between fs-12\"><span>")
                    .append(Html.text(bar.label()))
                    .append("</span><span class=\"tabular subtle\">").append(bar.value())
                    .append("</span></div>")
                    .append("<div class=\"meter-track\"><div class=\"meter-fill ")
                    .append(Html.text(bar.severityClass())).append(" ")
                    // A class, not a style attribute: the policy blocks the attribute, and a bar
                    // silently rendered at zero width is indistinguishable from a real zero.
                    .append(DesignSystem.widthClass(percent)).append("\"></div></div>")
                    .append("</div>");
        }
        out.append("</div>");
        return out.toString();
    }

    /**
     * The tabular alternative. {@code INT-UIX-006}.
     *
     * <p>An unmeasured period says so in words. A blank cell would be read as zero by anyone scanning
     * the column, which is the failure the chart's broken line already avoids.
     */
    private static String tabularAlternative(Messages messages, List<Point> points) {
        StringBuilder out = new StringBuilder();
        out.append("<details class=\"chart-data\"><summary>")
                .append(Html.text(messages.get("chart.showData"))).append("</summary><table>")
                .append("<thead><tr><th scope=\"col\">")
                .append(Html.text(messages.get("chart.period")))
                .append("</th><th scope=\"col\">")
                .append(Html.text(messages.get("chart.value")))
                .append("</th></tr></thead><tbody>");
        for (Point point : points) {
            out.append("<tr><td>").append(Html.text(point.label())).append("</td><td>");
            if (point.measured()) {
                out.append("<span class=\"tabular\">").append(point.value()).append("</span>");
            } else {
                out.append("<span class=\"state-label\">")
                        .append(Html.text(messages.get("state.unmeasured"))).append("</span>");
            }
            out.append("</td></tr>");
        }
        out.append("</tbody></table></details>");
        return out.toString();
    }

    private static long niceCeiling(long value) {
        if (value <= 5) {
            return 5;
        }
        long magnitude = (long) Math.pow(10, Math.floor(Math.log10((double) value)));
        long normalized = (long) Math.ceil((double) value / magnitude);
        long rounded = normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
        return rounded * magnitude;
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
