package aspm.app.ui;

import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import aspm.app.resource.SbomIngestion;
import aspm.module.insight.domain.PresentationState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The dependency coverage dashboard. DOC-22 §9, DOC-12.
 *
 * <p>{@code PRD-SBM-056} is described in DOC-22 as "the single most important requirement in the module",
 * and this page is where it is either honoured or lost: <b>an asset that never submitted an SBOM appears
 * here as {@code NEVER_SUBMITTED}, not as a row with zero components and not as an absent row.</b> An
 * absent row reads as absence of problems, and a zero reads as a clean application.
 *
 * <p>So the query is a left join from {@code asset}, and the coverage figure at the top is built through
 * {@link Overview.Kpi}, which cannot render a number without the population behind it.
 */
public final class CompositionPage {

    private final DataSource dataSource;

    public CompositionPage(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    public Dispatcher.Response render(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();

        List<Map<String, Object>> rows = new SbomIngestion(dataSource).coverage(principal);

        int inScope = rows.size();
        // *** NEVER SUBMITTED IS `latest_snapshot_at IS NULL`, NOT `quality IS NULL`. ***
        //
        // sbom_coverage_state.quality is NOT NULL with a default of 'REJECTED', so every asset with a
        // coverage row has a quality whether or not it ever submitted anything. Reading absence off that
        // column reported seven assets as submitted when two had — which is PP-1 exactly: an asset
        // nobody scanned presented as an asset that was scanned and failed. The schema pairs
        // latest_snapshot_id with latest_snapshot_at (ck_coverage__snapshot_complete), and that pair is
        // the only honest discriminator.
        int submitted = (int) rows.stream().filter(CompositionPage::hasSnapshot).count();
        int current = (int) rows.stream()
                .filter(r -> hasSnapshot(r) && "ABOVE_WARNING".equals(r.get("quality"))).count();

        StringBuilder body = new StringBuilder();

        body.append("<div class=\"grid grid-kpi mb-6\">")
                .append(kpi(messages, "composition.kpi.covered", current, submitted, inScope))
                .append(kpi(messages, "composition.kpi.submitted", submitted, submitted, inScope))
                .append(kpiCount(messages, "composition.kpi.neverSubmitted", inScope - submitted, inScope))
                .append("</div>");

        if (rows.isEmpty()) {
            body.append(StateRenderer.state(messages, PresentationState.EMPTY_NO_DATA,
                    Optional.of(messages.get("composition.noAssets"))));
        } else {
            body.append(table(messages, rows));
        }

        body.append("<section class=\"card mt-6\">")
                .append("<div class=\"card-header\"><h2>")
                .append(Html.text(messages.get("composition.howToSubmit"))).append("</h2></div>")
                .append("<div class=\"card-body\"><p class=\"fs-12 muted prose\">")
                .append(Html.text(messages.get("composition.pushOnly")))
                .append("</p><pre class=\"mono fs-12 code-sample\">")
                .append(Html.text("""
                        POST /api/v1/sbom-submissions
                        Content-Type: application/json
                        Idempotency-Key: <per-pipeline-run>

                        { "artifact_reference": "payments-api",
                          "document": { "bomFormat": "CycloneDX", "specVersion": "1.5",
                                        "components": [ ... ] } }"""))
                .append("</pre></div></section>");

        Page.Context context = Page.Context.of("composition.title", "/composition", Optional.ofNullable(principal))
                .withSubtitle("composition.subtitle")
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("nav.composition"), Optional.empty())))
                .withActions("<a class=\"btn btn-sm\" href=\"/api/v1/coverage-states\">"
                        + Html.text(messages.get("action.viewJson")) + "</a>");

        return new Dispatcher.Response(200,
                new InterfaceResource.Raw(Page.render(messages, context, body.toString())),
                Map.of("Content-Type", "text/html; charset=utf-8"));
    }

    /** A ratio KPI. Unmeasured where nothing was submitted, never a zero percentage. */
    private static String kpi(Messages messages, String labelKey, long value, int measured,
            int inScope) {
        return Overview.render(messages,
                List.of(new Overview.Kpi(labelKey, value, measured, inScope, "/composition",
                        Optional.empty(), List.of())),
                List.of(), List.of(), List.of(), "")
                // Only the card is wanted here, not the whole dashboard shell Overview.render adds.
                .replaceAll("(?s)</div>.*", "</div>");
    }

    /**
     * A count KPI. The never-submitted count is a real measurement of the asset population, so it
     * renders as a figure even when it is the whole estate — the thing that must not render as zero is
     * <i>coverage</i>, and a never-submitted count of zero would be good news that this figure can
     * legitimately report.
     */
    private static String kpiCount(Messages messages, String labelKey, int value, int inScope) {
        return "<a class=\"card\" href=\"/composition\"><div class=\"kpi\">"
                + "<span class=\"kpi-label\">" + Html.text(messages.get(labelKey)) + "</span>"
                + "<span class=\"kpi-value tabular\">" + value + "</span>"
                + "<span class=\"kpi-qualifier\">"
                + Html.text(messages.get("composition.ofAssets", Integer.valueOf(inScope)))
                + "</span></div></a>";
    }

    private static String table(Messages messages, List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder();
        out.append("<div class=\"table-wrap\"><div class=\"table-scroll\"><table class=\"data\">")
                .append("<caption>")
                .append(Html.text(messages.get("table.rowCount", Integer.valueOf(rows.size()))))
                .append("</caption><thead><tr>")
                .append(th(messages, "composition.column.asset"))
                .append(th(messages, "composition.column.status"))
                .append(th(messages, "composition.column.components"))
                .append(th(messages, "composition.column.quality"))
                .append(th(messages, "composition.column.ecosystems"))
                .append(th(messages, "composition.column.lastSubmitted"))
                .append("</tr></thead><tbody>");

        for (Map<String, Object> row : rows) {
            boolean submitted = hasSnapshot(row);
            out.append("<tr tabindex=\"-1\">");
            out.append("<td class=\"cell-primary truncate\">")
                    .append(Html.text(String.valueOf(row.get("display_name")))).append("</td>");

            // The status pill. NEVER_SUBMITTED is dashed and worded, so it cannot be read as a clean
            // result — which is what a green pill or an empty cell would be read as.
            out.append("<td>");
            if (!submitted) {
                out.append("<span class=\"pill pill-unknown\">")
                        .append(Html.text(messages.get("composition.neverSubmitted"))).append("</span>");
            } else if ("ABOVE_WARNING".equals(row.get("quality"))) {
                out.append("<span class=\"pill pill-ok\">")
                        .append(Html.text(messages.get("composition.current"))).append("</span>");
            } else {
                out.append("<span class=\"pill pill-warn\">")
                        .append(Html.text(messages.get("composition.partial"))).append("</span>");
            }
            out.append("</td>");

            out.append("<td class=\"num tabular\">")
                    .append(row.get("component_count") == null
                            ? "<span class=\"state-label\">"
                                    + Html.text(messages.get("state.unmeasured")) + "</span>"
                            : Html.text(String.valueOf(row.get("component_count"))))
                    .append("</td>");

            out.append("<td>");
            if (row.get("quality_score") == null) {
                out.append("<span class=\"state-label\">")
                        .append(Html.text(messages.get("state.unmeasured"))).append("</span>");
            } else {
                int score = ((Number) row.get("quality_score")).intValue();
                String fill = score > 60 ? "ok" : score > 30 ? "warn" : "danger";
                out.append("<div class=\"meter min-w-120\">")
                        .append("<div class=\"row between fs-12\"><span class=\"tabular\">")
                        .append(score).append("</span></div>")
                        .append("<div class=\"meter-track\"><div class=\"meter-fill ").append(fill)
                        .append(" ").append(DesignSystem.widthClass(score))
                        .append("\"></div></div></div>");
            }
            out.append("</td>");

            @SuppressWarnings("unchecked")
            List<Object> ecosystems = (List<Object>) row.get("covered_ecosystems");
            out.append("<td>");
            if (ecosystems == null || ecosystems.isEmpty()) {
                out.append("<span class=\"subtle\">—</span>");
            } else {
                for (Object ecosystem : ecosystems) {
                    out.append("<span class=\"id-chip me-1\">")
                            .append(Html.text(String.valueOf(ecosystem))).append("</span>");
                }
            }
            out.append("</td>");

            out.append("<td class=\"cell-secondary\">")
                    .append(row.get("latest_snapshot_at") == null
                            ? "<span class=\"state-label\">"
                                    + Html.text(messages.get("honesty.neverMeasured")) + "</span>"
                            : Html.text(String.valueOf(row.get("latest_snapshot_at")).substring(0, 16))
                                    + " UTC")
                    .append("</td>");
            out.append("</tr>");
        }
        out.append("</tbody></table></div></div>");
        return out.toString();
    }

    /** Whether this asset has ever submitted. See the note in {@link #render}. */
    private static boolean hasSnapshot(Map<String, Object> row) {
        return row.get("latest_snapshot_at") != null;
    }

    private static String th(Messages messages, String key) {
        return "<th scope=\"col\">" + Html.text(messages.get(key)) + "</th>";
    }
}
