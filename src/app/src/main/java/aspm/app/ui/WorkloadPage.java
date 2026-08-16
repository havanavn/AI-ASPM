package aspm.app.ui;

import aspm.app.resource.WorkloadQuery;
import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import aspm.module.insight.domain.PresentationState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The AppSec workload and service-level dashboard. DOC-12, {@code PRD-CAP-001} onward.
 *
 * <p>This is the page a security lead opens to answer "what is the team carrying, and what is it waiting
 * on". Three requirements make it different from the workload report a spreadsheet produces.
 *
 * <h2>1. Waiting time is separated, not absorbed</h2>
 *
 * <p>{@code PRD-CAP-008} requires cycle time "decomposed by workflow stage, including time in states
 * awaiting external parties", and product principle 6 makes waiting visible and attributed. So each stage
 * carries whether the service level clock was running in it. <b>Aggregating waiting into one cycle time is
 * how a team's throughput gets blamed for a requester who took three weeks to seed test data.</b>
 *
 * <h2>2. Individual measures are gated, and their purpose is stated on the page</h2>
 *
 * <p>{@code PRD-CAP-013} classifies per-person workload RESTRICTED and requires access "only through
 * explicit permission rather than by role seniority or organizational position". So the section needs
 * {@code cap.member.read.all}, and where the caller lacks it the section is <b>absent</b> rather than
 * blanked — ADR-047: restricted fields are absent from representations, not masked. A greyed-out panel
 * would confirm the data exists and say how many rows it has.
 *
 * <p>{@code PRD-CAP-014} requires the interface to document, <i>where the measures are presented</i>, that
 * they are for capacity planning and not for performance evaluation or ranking. So the statement is beside
 * the table, not in a policy document, and the table is ordered by identifier rather than by volume —
 * <b>a table sorted by count is a ranking whatever its caption says.</b>
 *
 * <h2>3. Utilization and compliance are unmeasured, and say so</h2>
 *
 * <p>{@code PRD-CAP-005} defines utilization as allocated effort over available capacity, presented
 * against a configurable target band "rather than against a maximum". No capacity ratio or availability is
 * recorded ({@code PRD-CAP-002}, {@code PRD-CAP-003}), so the denominator does not exist. No service level
 * policy is configured, so nothing has a deadline.
 *
 * <p>Both render the unmeasured state. A utilization bar computed against a guessed denominator would be
 * quoted in a staffing conversation, and "100% service level compliance" over zero clocks is the most
 * flattering form of the PP-1 failure.
 */
public final class WorkloadPage {

    /** {@code PRD-CAP-013}'s permission. Never implied by seniority — DOC-07 §5.2 says so explicitly. */
    public static final String INDIVIDUAL_PERMISSION = "cap.member.read.all";

    private final DataSource dataSource;

    public WorkloadPage(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    public Dispatcher.Response render(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();
        WorkloadQuery query = new WorkloadQuery(dataSource);

        Map<String, Long> headline = query.headline(principal);
        List<WorkloadQuery.FlowBucket> flow = query.flow(principal);
        List<WorkloadQuery.StageTime> stages = query.stageTimes(principal);
        List<WorkloadQuery.Waiting> waiting = query.waiting(principal);
        List<WorkloadQuery.FindingLoad> findings = query.findingLoad(principal);
        long clocks = query.serviceLevelClocks(principal);
        long capacityMembers = query.membersWithCapacity(principal);

        StringBuilder body = new StringBuilder(4096);

        body.append("<div class=\"grid grid-kpi mb-6\">")
                .append(count(messages, "workload.kpi.requestsWeek",
                        headline.get("requests_week"), "workload.kpi.ofTotal",
                        headline.get("requests_total")))
                .append(count(messages, "workload.kpi.openFindings",
                        headline.get("findings_open"), "workload.kpi.acrossAssets",
                        headline.get("assets")))
                .append(count(messages, "workload.kpi.unassigned",
                        headline.get("findings_unassigned"), "workload.kpi.ofOpen",
                        headline.get("findings_open")))
                // Compliance is unmeasured, and the card says which measurement is missing rather than
                // showing a percentage nobody could defend.
                .append(unmeasured(messages, "workload.kpi.slaCompliance",
                        clocks == 0 ? "workload.noClocks" : "workload.noPolicy"))
                .append("</div>");

        body.append("<div class=\"grid grid-2 mb-6\">")
                .append(card(messages, "workload.flow.title", flowPanel(messages, flow)))
                .append(card(messages, "workload.stages.title", stagePanel(messages, stages)))
                .append("</div>");

        body.append("<div class=\"grid grid-2 mb-6\">")
                .append(card(messages, "workload.waiting.title", waitingPanel(messages, waiting)))
                .append(card(messages, "workload.findings.title", findingPanel(messages, findings)))
                .append("</div>");

        body.append(utilizationPanel(messages, capacityMembers));

        // PRD-CAP-013: explicit permission, never seniority. Absent rather than masked (ADR-047).
        if (principal != null && principal.holds(INDIVIDUAL_PERMISSION)) {
            body.append(memberPanel(messages, query.memberLoad(principal)));
        }

        Page.Context context = Page.Context.of("workload.title", "/workload", Optional.ofNullable(principal))
                .withSubtitle("workload.subtitle")
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("nav.workload"), Optional.empty())));

        return new Dispatcher.Response(200,
                new InterfaceResource.Raw(Page.render(messages, context, body.toString())),
                Map.of("Content-Type", "text/html; charset=utf-8"));
    }

    // ----------------------------------------------------------------------------------------------

    private static String flowPanel(Messages messages, List<WorkloadQuery.FlowBucket> flow) {
        if (flow.isEmpty()) {
            return StateRenderer.state(messages, PresentationState.EMPTY_NO_DATA,
                    Optional.of(messages.get("workload.flow.none")));
        }
        long total = flow.stream().mapToLong(WorkloadQuery.FlowBucket::count).sum();
        StringBuilder out = new StringBuilder("<div class=\"col gap-3\">");
        for (WorkloadQuery.FlowBucket bucket : flow) {
            int percent = (int) Math.round(100.0 * bucket.count() / Math.max(1, total));
            out.append("<div class=\"meter\">")
                    .append("<div class=\"row between fs-12\">")
                    .append("<span class=\"row gap-2\">")
                    .append("<a class=\"link\" href=\"/board?state=")
                    .append(Html.text(bucket.state())).append("\">")
                    .append(Html.text(bucket.state())).append("</a>")
                    // The clock flag beside the state, because a count of requests "in progress" that
                    // includes ones nobody can work on is a count nobody can plan from.
                    .append(bucket.clockRunning()
                            ? "<span class=\"pill pill-info\">"
                                    + Html.text(messages.get("workload.clockRunning")) + "</span>"
                            : "<span class=\"pill pill-warn\">"
                                    + Html.text(messages.get("workload.clockPaused")) + "</span>")
                    .append("</span><span class=\"tabular\">").append(bucket.count())
                    .append("</span></div>")
                    .append("<div class=\"meter-track\"><div class=\"meter-fill")
                    .append(bucket.clockRunning() ? " " : " warn ")
                    .append(DesignSystem.widthClass(percent)).append("\"></div></div>")
                    .append("</div>");
        }
        out.append("</div>");
        return out.toString();
    }

    private static String stagePanel(Messages messages, List<WorkloadQuery.StageTime> stages) {
        if (stages.isEmpty()) {
            // Not "0 hours". No transition has been recorded, so no stage has a measured duration.
            return StateRenderer.state(messages, PresentationState.UNMEASURED,
                    Optional.of(messages.get("workload.stages.none")));
        }
        StringBuilder out = new StringBuilder();
        out.append("<div class=\"table-scroll\"><table class=\"data\"><thead><tr>")
                .append(th(messages, "workload.stages.state"))
                .append(th(messages, "workload.stages.average"))
                .append(th(messages, "workload.stages.transitions"))
                .append("</tr></thead><tbody>");
        for (WorkloadQuery.StageTime stage : stages) {
            out.append("<tr tabindex=\"-1\"><td class=\"cell-primary\">")
                    .append(Html.text(stage.state()))
                    .append(stage.clockRunning() ? ""
                            : " <span class=\"pill pill-warn\">"
                                    + Html.text(messages.get("workload.awaitingExternal"))
                                    + "</span>")
                    .append("</td><td class=\"num tabular\">")
                    .append(Html.text(String.format(java.util.Locale.ROOT, "%.1f", stage.averageHours())))
                    .append("</td><td class=\"num tabular\">").append(stage.transitions())
                    .append("</td></tr>");
        }
        out.append("</tbody></table></div>")
                .append("<p class=\"fs-12 muted mt-3\">")
                .append(Html.text(messages.get("workload.stages.note"))).append("</p>");
        return out.toString();
    }

    private static String waitingPanel(Messages messages, List<WorkloadQuery.Waiting> waiting) {
        if (waiting.isEmpty()) {
            // A genuine zero: nothing is in a clock-paused state. Distinct from unmeasured, and the
            // difference matters — "nothing is blocked" is good news the data supports.
            return "<p class=\"fs-12 muted\">" + Html.text(messages.get("workload.waiting.none"))
                    + "</p>";
        }
        StringBuilder out = new StringBuilder();
        out.append("<div class=\"col gap-3\">");
        for (WorkloadQuery.Waiting item : waiting) {
            out.append("<div class=\"col gap-tight\">")
                    .append("<div class=\"row wrap gap-2\">")
                    .append("<code class=\"fs-12\">").append(Html.text(item.requestCode()))
                    .append("</code>")
                    .append("<span class=\"pill pill-warn\">").append(Html.text(item.state()))
                    .append("</span>")
                    .append("<span class=\"fs-12 tabular subtle\">")
                    .append(Html.text(messages.get("workload.waiting.hours",
                            Long.valueOf(item.hoursWaiting()))))
                    .append("</span></div>");
            // PP-6 and PRD-CAP-015: the reason is the actionable part. A queue that says only that
            // something is blocked is a queue nobody works.
            if (!item.reason().isBlank()) {
                out.append("<p class=\"fs-12\">").append(Html.text(item.reason())).append("</p>");
            } else {
                out.append("<p class=\"fs-12 state-label\">")
                        .append(Html.text(messages.get("workload.waiting.noReason"))).append("</p>");
            }
            out.append("</div>");
        }
        out.append("</div>");
        return out.toString();
    }

    private static String findingPanel(Messages messages, List<WorkloadQuery.FindingLoad> findings) {
        if (findings.isEmpty()) {
            return "<p class=\"fs-12 muted\">" + Html.text(messages.get("workload.findings.none"))
                    + "</p>";
        }
        StringBuilder out = new StringBuilder();
        out.append("<div class=\"table-scroll\"><table class=\"data\"><thead><tr>")
                .append(th(messages, "workload.findings.severity"))
                .append(th(messages, "workload.findings.open"))
                .append(th(messages, "workload.findings.unassigned"))
                .append(th(messages, "workload.findings.aged"))
                .append("</tr></thead><tbody>");
        for (WorkloadQuery.FindingLoad load : findings) {
            String variant = switch (load.severity()) {
                case "CRITICAL" -> "critical";
                case "HIGH" -> "high";
                case "MEDIUM" -> "medium";
                default -> "low";
            };
            out.append("<tr tabindex=\"-1\"><td><span class=\"pill pill-").append(variant).append("\">")
                    .append(Html.text(load.severity())).append("</span></td>")
                    .append("<td class=\"num tabular\">").append(load.open()).append("</td>")
                    .append("<td class=\"num tabular\">")
                    .append(load.unassigned() > 0
                            ? "<strong>" + load.unassigned() + "</strong>" : "0")
                    .append("</td><td class=\"num tabular\">").append(load.overThirtyDays())
                    .append("</td></tr>");
        }
        out.append("</tbody></table></div>");
        return out.toString();
    }

    /**
     * Utilization. Unmeasured, with the missing measurement named.
     *
     * <p>{@code PRD-CAP-005} requires it "against a configurable target band rather than against a
     * maximum" — a bar against 100% invites the reading that 100% is the goal, and a team at 100%
     * allocation has no capacity for the incident that is the point of the team.
     */
    private static String utilizationPanel(Messages messages, long members) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card mb-6\"><div class=\"card-header\"><h2>")
                .append(Html.text(messages.get("workload.utilization.title"))).append("</h2></div>")
                .append("<div class=\"card-body\">");
        if (members == 0) {
            out.append(StateRenderer.state(messages, PresentationState.UNMEASURED,
                    Optional.of(messages.get("workload.utilization.noCapacity"))));
        }
        out.append("<p class=\"fs-12 muted prose mt-3\">")
                .append(Html.text(messages.get("workload.utilization.band")))
                .append("</p></div></section>");
        return out.toString();
    }

    /**
     * Per-member allocation. Reached only with {@code cap.member.read.all}.
     *
     * <p>The purpose statement is inside the panel, which is what {@code PRD-CAP-014} asks for: documented
     * "in the interface where individual measures are presented". A policy page nobody opens is not that.
     */
    private static String memberPanel(Messages messages, List<WorkloadQuery.MemberLoad> members) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2>")
                .append(Html.text(messages.get("workload.members.title"))).append("</h2>")
                .append("<div class=\"card-actions\"><span class=\"pill pill-critical\">")
                .append(Html.text(messages.get("workload.members.restricted")))
                .append("</span></div></div>")
                .append("<div class=\"card-body\">")
                .append("<div class=\"banner mb-4\"><span>")
                .append(Html.text(messages.get("workload.members.purpose")))
                .append("</span></div>");

        if (members.isEmpty()) {
            out.append("<p class=\"fs-12 muted\">")
                    .append(Html.text(messages.get("workload.members.none"))).append("</p>");
        } else {
            out.append("<div class=\"table-scroll\"><table class=\"data\"><thead><tr>")
                    .append(th(messages, "workload.members.member"))
                    .append(th(messages, "workload.members.findings"))
                    .append(th(messages, "workload.members.requests"))
                    .append(th(messages, "workload.members.utilization"))
                    .append("</tr></thead><tbody>");
            for (WorkloadQuery.MemberLoad member : members) {
                out.append("<tr tabindex=\"-1\">")
                        .append("<td><span class=\"id-chip\">")
                        .append(Html.text(member.principalId().length() > 8
                                ? member.principalId().substring(0, 8) : member.principalId()))
                        .append("</span></td>")
                        .append("<td class=\"num tabular\">").append(member.assignedFindings())
                        .append("</td><td class=\"num tabular\">").append(member.assignedRequests())
                        .append("</td>")
                        // Utilization needs available capacity, which is not recorded. A ratio of
                        // assigned items to nothing is not utilization.
                        .append("<td><span class=\"state-label\">")
                        .append(Html.text(messages.get("state.unmeasured")))
                        .append("</span></td></tr>");
            }
            out.append("</tbody></table></div>");
        }
        out.append("<p class=\"fs-12 subtle mt-3\">")
                .append(Html.text(messages.get("workload.members.ordering")))
                .append("</p></div></section>");
        return out.toString();
    }

    // ----------------------------------------------------------------------------------------------

    private static String count(Messages messages, String labelKey, Long value, String qualifierKey,
            Long qualifier) {
        return "<div class=\"card\"><div class=\"kpi\">"
                + "<span class=\"kpi-label\">" + Html.text(messages.get(labelKey)) + "</span>"
                + "<span class=\"kpi-value tabular\">" + (value == null ? 0 : value) + "</span>"
                + "<span class=\"kpi-qualifier\">"
                + Html.text(messages.get(qualifierKey, qualifier == null ? Long.valueOf(0) : qualifier))
                + "</span></div></div>";
    }

    private static String unmeasured(Messages messages, String labelKey, String reasonKey) {
        return "<div class=\"card\"><div class=\"kpi\">"
                + "<span class=\"kpi-label\">" + Html.text(messages.get(labelKey)) + "</span>"
                + "<span class=\"kpi-value state-label fs-20\">"
                + Html.text(messages.get("state.unmeasured")) + "</span>"
                + "<span class=\"kpi-qualifier\">" + Html.text(messages.get(reasonKey))
                + "</span></div></div>";
    }

    private static String card(Messages messages, String titleKey, String body) {
        return "<section class=\"card\"><div class=\"card-header\"><h2>"
                + Html.text(messages.get(titleKey)) + "</h2></div>"
                + "<div class=\"card-body\">" + body + "</div></section>";
    }

    private static String th(Messages messages, String key) {
        return "<th scope=\"col\">" + Html.text(messages.get(key)) + "</th>";
    }
}
