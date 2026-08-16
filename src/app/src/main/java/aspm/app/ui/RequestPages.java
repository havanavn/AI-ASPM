package aspm.app.ui;

import aspm.app.assessment.AssessmentService;
import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The assessment request board, one request, and the findings recorded against it. DOC-09, DOC-06.
 *
 * <ul>
 *   <li>{@code GET /board} — every request the caller can see, deadline first.
 *   <li>{@code GET /board/{id}} — one request: scope, people, findings, comments.
 *   <li>{@code GET /board/{id}/findings/{findingId}} — the write-up: description, proof of
 *       concept, comments, and the risk-acceptance control.
 *   <li>{@code POST} siblings for recording a finding, amending it, accepting risk, reopening, and
 *       commenting.
 * </ul>
 *
 * <h2>Write-ups render through {@link Markdown}, never as HTML</h2>
 *
 * <p>Every body on these pages is attacker-influenced by design: a pentester writing up a cross-site
 * scripting finding pastes the payload that worked, and a comment on an ingested finding quotes text
 * an attacker authored. The renderer accepts a small Markdown subset and escapes everything first, so
 * the failure mode of a mistake here is ugly text rather than script execution — in the product whose
 * purpose is finding that defect in other people's software.
 */
public final class RequestPages {

    /** Read the board. */
    public static final String READ = "asm.request.read";
    /** Record and amend findings. */
    public static final String TRIAGE = "vul.finding.triage";
    /** Accept residual risk — restricted and step-up in the catalogue. */
    public static final String ACCEPT_RISK = "asm.request.acceptrisk";

    private final AssessmentService assessments;
    private final aspm.app.assessment.AttachmentService attachments;
    private final aspm.app.resource.RequestTransition transitionService;

    public RequestPages(DataSource dataSource) {
        this.assessments = new AssessmentService(Objects.requireNonNull(dataSource));
        this.attachments = new aspm.app.assessment.AttachmentService(dataSource);
        // Reused, not reimplemented. DOC-09 §4's machine is tenant-configurable data and this class
        // already reads it, applies the guards and writes the transition log; a second implementation
        // on this page would be a second answer to "may this move happen".
        this.transitionService = new aspm.app.resource.RequestTransition(dataSource);
    }

    // ----------------------------------------------------------------------------------------------

    /** {@code GET /board}. */
    public Dispatcher.Response board(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();

        Map<String, String> filters = new LinkedHashMap<>();
        for (String name : List.of("state", "node", "only", "trigger", "category")) {
            String value = request.query().get(name);
            if (value != null && !value.isBlank()) {
                filters.put(name, value);
            }
        }
        String search = request.query().getOrDefault("q", "");
        String sort = request.query().getOrDefault("sort", "due");
        List<AssessmentService.Trigger> triggers = assessments.triggers(principal);
        List<AssessmentService.Request> rows = assessments.board(principal, filters, search, sort);

        Set<UUID> people = new LinkedHashSet<>();
        for (AssessmentService.Request row : rows) {
            if (row.contactId() != null) {
                people.add(row.contactId());
            }
            if (row.leadId() != null) {
                people.add(row.leadId());
            }
        }
        Map<UUID, String> names = assessments.principalNames(principal, people);

        long overdue = rows.stream().filter(AssessmentService.Request::overdue).count();
        long unassigned = rows.stream().filter(r -> r.leadId() == null
                && !r.state().startsWith("CLOSED") && !"DRAFT".equals(r.state())).count();

        StringBuilder body = new StringBuilder(8192);
        if (overdue > 0) {
            body.append("<div class=\"banner banner-danger\" role=\"status\"><div><strong>")
                    .append(Html.text(messages.get("board.overdueTitle", overdue)))
                    .append("</strong> ")
                    .append(Html.text(messages.get("board.overdueBody")))
                    .append(" <a class=\"link\" href=\"/board?only=overdue\">")
                    .append(Html.text(messages.get("board.overdueLink"))).append("</a></div></div>");
        }

        body.append("<div class=\"grid grid-kpi mb-6\">")
                .append(kpi(messages, "board.kpi.total", String.valueOf(rows.size())))
                .append(kpi(messages, "board.kpi.overdue", String.valueOf(overdue), "danger"))
                // An in-flight engagement with nobody leading it is the one row that stalls silently:
                // it has a deadline, it is not closed, and no person is answerable for it.
                .append(kpi(messages, "board.kpi.unassigned", String.valueOf(unassigned), "warn"))
                .append(kpi(messages, "board.kpi.openFindings", String.valueOf(
                        rows.stream().mapToLong(AssessmentService.Request::findingOpen).sum()),
                        "high"))
                .append("</div>");

        body.append("<form class=\"card mb-6\" method=\"get\" action=\"/board\">")
                .append("<div class=\"card-body\"><div class=\"form-grid\">")
                .append(Forms.field(messages, "q", "board.filter.search", "search", search, false,
                        null))
                .append(Forms.select(messages, "state", "board.filter.state",
                        withBlank(messages.get("app.value.any"),
                                states(rows).stream().map(s -> Map.entry(s, s)).toList()),
                        filters.getOrDefault("state", "")))
                // The reason, as a picker. The options are tenant rows read on each render, so a
                // tenant that adds a trigger sees it here without a release (ADR-027). "Not stated"
                // is offered explicitly: a blank column is not a way to ask which requests are missing
                // one, and those are exactly the requests somebody needs to go and fix.
                .append(Forms.select(messages, "trigger", "board.filter.trigger",
                        triggerOptions(messages, triggers),
                        filters.getOrDefault("trigger", "")))
                .append(Forms.select(messages, "sort", "board.filter.sort", List.of(
                                Map.entry("due", messages.get("board.sort.due")),
                                Map.entry("created", messages.get("board.sort.created")),
                                Map.entry("state", messages.get("board.sort.state")),
                                Map.entry("findings", messages.get("board.sort.findings")),
                                Map.entry("title", messages.get("board.sort.title"))),
                        sort))
                .append("</div><div class=\"form-actions\">")
                .append("<button class=\"btn btn-primary\" type=\"submit\">")
                .append(Html.text(messages.get("app.filter.apply"))).append("</button>")
                .append("<a class=\"btn\" href=\"/board\">")
                .append(Html.text(messages.get("app.filter.clear")))
                .append("</a></div></div></form>");

        if (rows.isEmpty()) {
            body.append(StateRenderer.state(messages,
                    aspm.module.insight.domain.PresentationState.EMPTY_FILTERED,
                    Optional.of(messages.get("board.noMatch"))));
        } else {
            body.append("<div class=\"table-wrap\"><div class=\"table-scroll\">")
                    .append("<table class=\"data\"><caption>")
                    .append(Html.text(messages.get("board.caption")))
                    .append("</caption><thead><tr>")
                    .append(th(messages, "board.col.request"))
                    .append(th(messages, "board.col.application"))
                    .append(th(messages, "board.col.organization"))
                    .append(th(messages, "board.col.trigger"))
                    .append(th(messages, "board.col.findings"))
                    .append(th(messages, "board.col.created"))
                    .append(th(messages, "board.col.due"))
                    .append(th(messages, "board.col.closed"))
                    .append(th(messages, "board.col.state"))
                    .append(th(messages, "board.col.dev"))
                    .append(th(messages, "board.col.assessor"))
                    .append("</tr></thead><tbody>");
            for (AssessmentService.Request row : rows) {
                body.append(boardRow(messages, row, names));
            }
            body.append("</tbody></table></div></div>");
        }

        Page.Context context = Page.Context.of("board.title", "/board",
                        Optional.ofNullable(principal))
                .withSubtitle("board.subtitle")
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("nav.board"), Optional.empty())));
        return html(Page.render(messages, context, body.toString()));
    }

    private static String boardRow(Messages messages, AssessmentService.Request row,
            Map<UUID, String> names) {
        StringBuilder out = new StringBuilder();
        out.append("<tr><td><a class=\"link\" href=\"/board/")
                .append(Html.text(row.id().toString())).append("\">")
                .append(Html.text(row.title() == null ? row.code() : row.title())).append("</a>")
                .append("<div class=\"fs-11 muted mono\">").append(Html.text(row.code()))
                .append(row.retest() ? " · " + Html.text(messages.get("board.retest")) : "")
                .append("</div></td>")
                .append("<td>").append(row.primaryApplication() == null
                        ? muted(messages.get("board.noApplication"))
                        : Html.text(row.primaryApplication()))
                .append(row.scopeAssets() > 1
                        ? "<div class=\"fs-11 muted\">"
                                + Html.text(messages.get("board.plusMore", row.scopeAssets() - 1))
                                + "</div>" : "")
                .append("</td>")
                .append("<td><div class=\"fs-13\">")
                .append(Html.text(row.orgNodeName() == null ? "—" : row.orgNodeName()))
                .append("</div>")
                .append(row.orgAncestors().isEmpty() ? ""
                        : "<div class=\"fs-11 muted\">"
                                + Html.text(String.join(" › ", row.orgAncestors())) + "</div>")
                .append("</td>")
                // Why it was raised. A full review is marked, because it is the one that discharges an
                // obligation and the one a coverage question is really about.
                .append("<td class=\"fs-12\">")
                .append(row.triggerCode() == null
                        ? pill("unknown", messages.get("board.trigger.none"))
                        : Html.text(row.triggerLabel()))
                .append(row.triggerIsFullReview()
                        ? " " + pill("info", messages.get("board.trigger.full")) : "")
                .append("</td>")
                // Open / accepted / total in one cell. Three separate columns of small integers is a
                // table nobody scans; the shape "3 / 1 / 7" is read at a glance once its header says so.
                .append("<td class=\"tabular\">")
                .append(row.findingTotal() == 0 ? muted(messages.get("board.noFindings"))
                        : Html.text(row.findingOpen() + " / " + row.findingAccepted() + " / "
                                + row.findingTotal()))
                .append(row.findingSevereOpen() > 0
                        ? " " + pill("critical", String.valueOf(row.findingSevereOpen())) : "")
                .append("</td>")
                .append("<td class=\"mono fs-11\">").append(Html.text(row.createdAt())).append("</td>")
                .append("<td class=\"mono fs-11\">")
                .append(row.dueAt() == null ? muted("—")
                        : (row.overdue() ? pill("danger", row.dueAt()) : Html.text(row.dueAt())))
                .append("</td>")
                // When it actually closed, from the transition log. A terminal request with no entry
                // shows "not recorded" rather than a fabricated date: the requests seeded straight
                // into a closed state genuinely have no closure event, and inventing one would put a
                // date on a review nobody performed (PP-1).
                .append("<td class=\"mono fs-11\">")
                .append(row.closedAt() != null ? Html.text(row.closedAt())
                        : row.terminal() ? muted(messages.get("board.closed.unrecorded"))
                        : muted("—"))
                .append("</td>")
                .append("<td>").append(pill(stateTone(row.state()), row.state())).append("</td>")
                .append("<td class=\"fs-12\">")
                .append(Html.text(name(names, row.contactId(), "—"))).append("</td>")
                .append("<td class=\"fs-12\">")
                .append(row.leadId() == null
                        ? pill("warn", messages.get("board.unassigned"))
                        : Html.text(name(names, row.leadId(), "—")))
                .append("</td></tr>");
        return out.toString();
    }

    // ----------------------------------------------------------------------------------------------

    /** {@code GET /board/{id}}. */
    public Dispatcher.Response detail(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        Optional<AssessmentService.Request> found = assessments.request(principal, id);
        if (found.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        AssessmentService.Request req = found.orElseThrow();
        List<AssessmentService.Finding> findings = assessments.findings(principal, id);
        List<AssessmentService.Comment> comments =
                assessments.comments(principal, "ASSESSMENT_REQUEST", id);
        List<Map<String, String>> severities = assessments.severities(principal);
        List<Map<String, String>> scope = assessments.scopeAssets(principal, id);
        Map<UUID, String> names = assessments.principalNames(principal,
                new LinkedHashSet<>(java.util.Arrays.asList(req.contactId(), req.leadId()))
                        .stream().filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        boolean mayTriage = principal != null && principal.holds(TRIAGE);
        List<aspm.app.resource.RequestTransition.Available> transitions =
                transitions(principal, id);
        List<Map<String, String>> people = assessments.assignableprincipals(principal);

        StringBuilder body = new StringBuilder(16384);
        if (request.query().containsKey("saved")) {
            body.append(notice(messages.get("board.saved")));
        }
        if (request.query().containsKey("blocked")) {
            body.append(danger(messages.get("board.transitionBlocked",
                    request.query().getOrDefault("blocked", ""))));
        }
        if (req.overdue()) {
            body.append("<div class=\"banner banner-danger\" role=\"status\"><div>")
                    .append(Html.text(messages.get("board.thisOverdue", req.dueAt())))
                    .append("</div></div>");
        }

        body.append("<div class=\"grid grid-kpi mb-6\">")
                .append(kpi(messages, "board.detail.open", String.valueOf(req.findingOpen()),
                        "high"))
                .append(kpi(messages, "board.detail.accepted",
                        String.valueOf(req.findingAccepted()), "warn"))
                .append(kpi(messages, "board.detail.total", String.valueOf(req.findingTotal())))
                .append(kpi(messages, "board.detail.due", req.dueAt() == null ? "—" : req.dueAt(),
                        req.overdue() ? "danger" : null))
                .append("</div>");

        body.append("<div class=\"grid grid-2 mb-6\">")
                .append(summaryCard(messages, req, scope))
                .append(peopleCard(messages, req, names))
                .append("</div>");

        body.append(transitionCard(messages, id, req.state(), transitions, mayTriage));
        if (mayTriage) {
            body.append(assignCard(messages, req, people, assessments.triggers(principal)));
        }
        body.append(findingTable(messages, id, findings));
        if (mayTriage) {
            body.append(recordCard(messages, id, severities, scope));
        }
        body.append(commentCard(messages, comments, BOARD + id + "/comments",
                principal != null));

        Page.Context context = Page.Context.of("board.detail.title", "/board",
                        Optional.ofNullable(principal))
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("nav.board"), Optional.of("/board")),
                        new Page.Crumb(req.code(), Optional.empty())));
        return html(Page.render(messages, context, body.toString()));
    }

    private static String summaryCard(Messages messages, AssessmentService.Request req,
            List<Map<String, String>> scope) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(req.title() == null ? req.code() : req.title()))
                .append("</h2><span class=\"pill pill-").append(stateTone(req.state())).append("\">")
                .append(Html.text(req.state())).append("</span></div>")
                .append("<div class=\"card-body col gap-3\">")
                .append(definition(messages.get("board.field.code"), req.code()))
                .append(definition(messages.get("board.field.organization"),
                        String.join(" › ", concat(req.orgAncestors(),
                                req.orgNodeName() == null ? "—" : req.orgNodeName()))))
                .append(definition(messages.get("board.field.created"), req.createdAt()))
                .append(definition(messages.get("board.field.due"),
                        req.dueAt() == null ? messages.get("board.noDue") : req.dueAt()))
                .append(definition(messages.get("board.field.retest"),
                        messages.get(req.retest() ? "admin.user.yes" : "admin.user.no")));
        out.append("<div class=\"col gap-1\"><span class=\"fs-12 muted\">")
                .append(Html.text(messages.get("board.field.scope"))).append("</span>");
        if (scope.isEmpty()) {
            // A request with no scope cannot be assessed and cannot be planned. Said, not blank.
            out.append("<span class=\"pill pill-warn\">")
                    .append(Html.text(messages.get("board.noScope"))).append("</span>");
        }
        for (Map<String, String> asset : scope) {
            out.append("<span class=\"fs-12\">").append(Html.text(asset.get("name")))
                    .append(" <span class=\"fs-11 muted\">").append(Html.text(asset.get("type")))
                    .append("</span></span>");
        }
        return out.append("</div></div></section>").toString();
    }


    /**
     * A person's name, or a stated absence.
     *
     * <p>Exists because {@code Map.getOrDefault(null, …)} throws on an immutable map rather than
     * returning the default — {@code Map.of()} and {@code Map.copyOf()} reject null keys outright.
     * Every request with no assessor yet has a null identifier, so the request detail page answered
     * 500 for every unassigned request: exactly the rows the board highlights as needing attention.
     *
     * <p>Found by walking every link on every page rather than by reading the code. The board itself
     * never failed, because the seeded requests all had a dev contact and the assessor branch was
     * already null-guarded there; only the detail page reached the unguarded lookup.
     */
    private static String name(Map<UUID, String> names, UUID id, String absent) {
        return id == null ? absent : names.getOrDefault(id, absent);
    }

    private static String peopleCard(Messages messages, AssessmentService.Request req,
            Map<UUID, String> names) {
        return "<section class=\"card\"><div class=\"card-header\"><h2 class=\"card-title\">"
                + Html.text(messages.get("board.people")) + "</h2></div>"
                + "<div class=\"card-body col gap-3\">"
                + definition(messages.get("board.field.dev"),
                        name(names, req.contactId(), messages.get("board.noContact")))
                + definition(messages.get("board.field.assessor"),
                        name(names, req.leadId(), messages.get("board.unassigned")))
                + "<p class=\"fs-11 muted\">" + Html.text(messages.get("board.peopleNote"))
                + "</p></div></section>";
    }

    private static String findingTable(Messages messages, UUID requestId,
            List<AssessmentService.Finding> findings) {
        StringBuilder out = new StringBuilder(8192);
        out.append("<section class=\"card mb-6\"><div class=\"card-header\">")
                .append("<h2 class=\"card-title\">")
                .append(Html.text(messages.get("board.findings.title"))).append("</h2>")
                .append("<span class=\"pill pill-unknown\">").append(findings.size())
                .append("</span></div><div class=\"card-body\">");
        if (findings.isEmpty()) {
            // Not "no vulnerabilities". A clean engagement and an engagement nobody has written up
            // are the same empty list, and only one of them is a result.
            out.append("<p class=\"fs-13 muted\">")
                    .append(Html.text(messages.get("board.findings.none"))).append("</p>");
        } else {
            out.append("<div class=\"table-scroll\"><table class=\"data\"><thead><tr>")
                    .append(th(messages, "board.findings.col.title"))
                    .append(th(messages, "board.findings.col.severity"))
                    .append(th(messages, "board.findings.col.context"))
                    .append(th(messages, "board.findings.col.detected"))
                    .append(th(messages, "board.findings.col.state"))
                    .append(th(messages, "board.findings.col.acceptedUntil"))
                    .append("</tr></thead><tbody>");
            for (AssessmentService.Finding f : findings) {
                out.append("<tr><td><a class=\"link\" href=\"/board/")
                        .append(Html.text(requestId.toString())).append("/findings/")
                        .append(Html.text(f.id().toString())).append("\">")
                        .append(Html.text(f.title())).append("</a>")
                        .append(f.assetName() == null ? ""
                                : "<div class=\"fs-11 muted\">" + Html.text(f.assetName()) + "</div>")
                        .append("</td>")
                        .append("<td>").append(f.severity() == null
                                ? muted("—") : pill(severityTone(f.severity()), f.severity()))
                        .append("</td>")
                        .append("<td class=\"fs-11\">")
                        .append(Html.text(f.assessmentContext() == null ? "—"
                                : messages.get("finding.context." + f.assessmentContext(),
                                        f.assessmentContext())))
                        .append("</td>")
                        // The detection date, which was filled automatically at the moment of entry
                        // rather than typed months later when the report is written.
                        .append("<td class=\"mono fs-11\">")
                        .append(Html.text(f.firstDetectedAt())).append("</td>")
                        .append("<td>").append(f.accepted()
                                ? pill("warn", messages.get("finding.state.accepted"))
                                : pill(f.open() ? "danger" : "ok", f.state()))
                        .append("</td>")
                        .append("<td class=\"mono fs-11\">")
                        .append(f.acceptedUntil() == null ? muted("—")
                                : Html.text(f.acceptedUntil()))
                        .append("</td></tr>");
            }
            out.append("</tbody></table></div>");
        }
        return out.append("</div></section>").toString();
    }

    private static String recordCard(Messages messages, UUID requestId,
            List<Map<String, String>> severities, List<Map<String, String>> scope) {
        return "<section class=\"card mb-6\"><div class=\"card-header\"><h2 class=\"card-title\">"
                + Html.text(messages.get("board.record.title")) + "</h2>"
                + "<p class=\"fs-12 muted\">" + Html.text(messages.get("board.record.lede"))
                + "</p></div><div class=\"card-body\">"
                + "<form method=\"post\" action=\"/board/" + requestId + "/findings\">"
                + Forms.idempotencyField()
                + "<div class=\"form-grid\">"
                + Forms.field(messages, "title", "board.findings.col.title", "text", "", true, null)
                + Forms.select(messages, "severity", "board.findings.col.severity",
                        severities.stream().map(s -> Map.entry(s.get("id"), s.get("code"))).toList(),
                        severities.isEmpty() ? "" : severities.get(1 % severities.size()).get("id"))
                + Forms.select(messages, "context", "board.findings.col.context",
                        AssessmentService.CONTEXTS.stream()
                                .map(c -> Map.entry(c, messages.get("finding.context." + c, c)))
                                .toList(),
                        AssessmentService.DEFAULT_CONTEXT)
                + Forms.select(messages, "asset", "board.record.asset",
                        withBlank(messages.get("board.record.noAsset"),
                                scope.stream().map(a -> Map.entry(a.get("id"),
                                        a.get("name") + "  ·  " + a.get("type"))).toList()),
                        "")
                + "</div><div class=\"mt-6 col gap-4\">"
                + textarea(messages, "description", "finding.description", "", 5,
                        messages.get("finding.markdownHint"), uploadTo(requestId, null))
                + textarea(messages, "proof_of_concept", "finding.poc", "", 8,
                        messages.get("finding.pocHint"), uploadTo(requestId, null))
                + "</div><div class=\"form-actions\">"
                + "<button class=\"btn btn-primary\" type=\"submit\">"
                + Html.text(messages.get("board.record.action")) + "</button>"
                + "<span class=\"fs-12 muted\">" + Html.text(messages.get("board.record.autoTime"))
                + "</span></div></form></div></section>";
    }


    /**
     * The state control: one dropdown and one button.
     *
     * <p>This replaced a card that rendered every available move as its own form — up to nine of
     * them, each with its own button, its own destination label and sometimes its own text input.
     * That layout was a faithful picture of the workflow and an unusable control, and it is what the
     * six-state version 2 workflow (V027) exists to make unnecessary.
     *
     * <p>Blocked moves are still RENDERED, as disabled options carrying their reason. A move that
     * simply is not in the list tells somebody nothing and produces a support conversation; one that
     * is present and disabled tells them what to go and do. That was the right instinct in the old
     * card and it survives the rewrite.
     *
     * <p>The reason field is shown whenever ANY available move requires one — it cannot be bound to
     * the selected option without script, and a required input that appears only after a selection is
     * a form that fails validation for reasons the person cannot see. It is marked as applying to the
     * moves that need it.
     */
    private static String transitionCard(Messages messages, UUID requestId, String currentState,
            List<aspm.app.resource.RequestTransition.Available> transitions, boolean mayAct) {
        StringBuilder out = new StringBuilder(2048);
        out.append("<section class=\"card mb-6\"><div class=\"card-header\">")
                .append("<h2 class=\"card-title\">")
                .append(Html.text(messages.get("board.transitions.title"))).append("</h2>")
                .append("<p class=\"fs-12 muted\">")
                .append(Html.text(messages.get("board.transitions.lede")))
                .append("</p></div><div class=\"card-body\">");

        if (transitions.isEmpty()) {
            return out.append("<p class=\"fs-13 muted\">")
                    .append(Html.text(messages.get("board.transitions.terminal")))
                    .append("</p></div></section>").toString();
        }

        boolean anyReasonRequired = transitions.stream()
                .anyMatch(t -> t.reasonRequired() && t.permitted() && mayAct);

        out.append("<form method=\"post\" action=\"/board/")
                .append(Html.text(requestId.toString())).append("/transitions\">")
                .append(Forms.idempotencyField())
                .append("<div class=\"form-grid\">")
                .append("<label class=\"col gap-1\">")
                .append("<span class=\"fs-12 muted\">")
                .append(Html.text(messages.get("board.transitions.moveTo"))).append("</span>")
                .append("<select class=\"input\" name=\"event\">");
        for (var t : transitions) {
            boolean actionable = t.permitted() && mayAct;
            out.append("<option value=").append(Html.attribute(t.event()))
                    .append(actionable ? "" : " disabled").append(">")
                    .append(Html.text(t.toStateLabel()))
                    .append(actionable ? "" : "  —  " + Html.text(t.blockedReason()
                            .map(r -> messages.getOr("board.transitions.blocked." + r, r))
                            .orElse(messages.get("board.transitions.blocked.permission"))))
                    .append("</option>");
        }
        out.append("</select></label>");
        if (anyReasonRequired) {
            out.append("<label class=\"col gap-1\">")
                    .append("<span class=\"fs-12 muted\">")
                    .append(Html.text(messages.get("board.transitions.reason"))).append("</span>")
                    .append("<input class=\"input\" name=\"reason\" autocomplete=\"off\" ")
                    .append("placeholder=")
                    .append(Html.attribute(messages.get("board.transitions.reasonHint")))
                    .append("></label>");
        }
        out.append("</div><div class=\"form-actions\">")
                .append("<span class=\"fs-12 muted\">")
                .append(Html.text(messages.get("board.transitions.currently")))
                .append(" ").append(pill(stateTone(currentState), currentState)).append("</span>")
                .append("<button class=\"btn btn-primary btn-sm\" type=\"submit\"")
                .append(mayAct ? "" : " disabled").append(">")
                .append(Html.text(messages.get("board.transitions.apply")))
                .append("</button></div></form></div></section>");
        return out.toString();
    }

    /**
     * Naming the people and the deadline.
     *
     * <p>The assessor is stored on the ASSESSMENT rather than the request, so naming one creates the
     * assessment if none exists — which is also the moment a request stops being something asked for
     * and starts being work somebody is doing.
     */
    private static String assignCard(Messages messages, AssessmentService.Request req,
            List<Map<String, String>> people, List<AssessmentService.Trigger> triggers) {
        List<Map.Entry<String, String>> options = people.stream()
                .map(p -> Map.entry(p.get("id"), p.get("name"))).toList();
        List<Map.Entry<String, String>> triggerOptions = new ArrayList<>();
        triggerOptions.add(Map.entry("", messages.get("board.trigger.none")));
        for (AssessmentService.Trigger t : triggers) {
            triggerOptions.add(Map.entry(t.id().toString(),
                    t.countsAsFullReview() ? t.label() + " ★" : t.label()));
        }
        String guidance = triggers.stream().filter(AssessmentService.Trigger::countsAsFullReview)
                .map(AssessmentService.Trigger::guidance).filter(Objects::nonNull)
                .findFirst().orElse(null);
        return "<section class=\"card mb-6\"><div class=\"card-header\"><h2 class=\"card-title\">"
                + Html.text(messages.get("board.assign.title")) + "</h2>"
                + "<p class=\"fs-12 muted\">" + Html.text(messages.get("board.assign.lede"))
                + "</p></div><div class=\"card-body\">"
                + "<form method=\"post\" action=\"/board/" + req.id() + "/assign\">"
                + Forms.idempotencyField()
                + "<div class=\"form-grid\">"
                + Forms.personPicker(messages, "contact", "board.field.dev", options,
                        req.contactId() == null ? "" : req.contactId().toString(),
                        messages.get("board.assign.typeHint"))
                + Forms.personPicker(messages, "assessor", "board.field.assessor", options,
                        req.leadId() == null ? "" : req.leadId().toString(),
                        messages.get("board.assign.typeHint"))
                + Forms.field(messages, "due", "board.field.due", "date",
                        req.dueAt() == null ? "" : req.dueAt(), false, null)
                + Forms.select(messages, "trigger", "board.field.trigger", triggerOptions,
                        req.triggerId() == null ? "" : req.triggerId().toString())
                + "</div>"
                + (guidance == null ? "" : "<p class=\"fs-11 muted\">★ "
                        + Html.text(guidance) + "</p>")
                + "<div class=\"form-actions\">"
                + "<button class=\"btn btn-primary btn-sm\" type=\"submit\">"
                + Html.text(messages.get("board.assign.action")) + "</button></div></form></div></section>";
    }

    private List<aspm.app.resource.RequestTransition.Available> transitions(Principal principal,
            UUID requestId) throws Exception {
        return transitionService.available(principal, requestId);
    }

    /** {@code POST /board/{id}/transitions}. */
    public Dispatcher.Response transition(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || assessments.request(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        var outcome = transitionService.apply(principal, id,
                form.getOrDefault("event", ""),
                Optional.ofNullable(form.get("reason")).filter(r -> !r.isBlank()));
        if (outcome instanceof aspm.app.resource.RequestTransition.Outcome.Applied) {
            return redirect("/board/" + id + "?saved=1");
        }
        // The guard's own words, carried back. A generic failure here would discard the one piece of
        // information the requester needs.
        String detail = outcome instanceof aspm.app.resource.RequestTransition.Outcome.Denied denied
                ? denied.detail() : messages("blocked");
        return redirect("/board/" + id + "?blocked="
                + java.net.URLEncoder.encode(detail, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String messages(String fallback) {
        return fallback;
    }

    /** {@code POST /board/{id}/assign}. */
    public Dispatcher.Response assign(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || assessments.request(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        java.time.LocalDate due = null;
        String raw = form.getOrDefault("due", "").strip();
        if (!raw.isEmpty()) {
            try {
                due = java.time.LocalDate.parse(raw);
            } catch (java.time.format.DateTimeParseException e) {
                return redirect("/board/" + id + "?blocked=date");
            }
        }
        List<Map<String, String>> people = assessments.assignableprincipals(principal);
        Resolved contact = resolvePerson(people, form.get("contact"));
        Resolved assessor = resolvePerson(people, form.get("assessor"));
        if (contact.unresolved() || assessor.unresolved()) {
            // Told, not ignored. A picker that quietly drops a name somebody typed produces a request
            // that looks assigned on the form and is unassigned in the record.
            return redirect("/board/" + id + "?blocked=person");
        }
        assessments.assignRequest(principal, id, contact.id(), assessor.id(), due);

        // The reason lives on the same form because it is set at the same moment by the same person:
        // triage is when somebody decides what this engagement is and who does it.
        UUID trigger = uuid(form.get("trigger"));
        if (trigger != null) {
            assessments.setTrigger(principal, id, trigger);
        }
        return redirect("/board/" + id + "?saved=1");
    }

    /** {@code POST /board/{id}/findings/{findingId}/close}. */
    public Dispatcher.Response closeFinding(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        UUID findingId = uuid(request.pathVariables().get("findingId"));
        if (id == null || findingId == null
                || assessments.finding(principal, id, findingId).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        boolean done = assessments.closeFinding(principal, findingId,
                form.getOrDefault("reason", ""), form.get("method"),
                integer(form.get("row_version")));
        return redirect("/board/" + id + "/findings/" + findingId
                + (done ? "?saved=1" : "?stale=1"));
    }

    // ----------------------------------------------------------------------------------------------

    /** {@code GET /board/{id}/findings/{findingId}}. */
    public Dispatcher.Response finding(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        UUID findingId = uuid(request.pathVariables().get("findingId"));
        if (id == null || findingId == null || assessments.request(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Optional<AssessmentService.Finding> found =
                assessments.finding(principal, id, findingId);
        if (found.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        AssessmentService.Finding f = found.orElseThrow();
        List<AssessmentService.Comment> comments =
                assessments.comments(principal, "FINDING", findingId);
        List<Map<String, String>> severities = assessments.severities(principal);
        boolean mayTriage = principal != null && principal.holds(TRIAGE);
        boolean mayAccept = principal != null && principal.holds(ACCEPT_RISK);

        StringBuilder body = new StringBuilder(16384);
        if (request.query().containsKey("saved")) {
            body.append(notice(messages.get("board.saved")));
        }
        if (request.query().containsKey("stale")) {
            body.append(danger(messages.get("app.inventory.stale")));
        }
        if (f.accepted()) {
            body.append("<div class=\"banner\" role=\"status\"><div><strong>")
                    .append(Html.text(messages.get("finding.acceptedTitle")))
                    .append("</strong> ")
                    .append(Html.text(messages.get("finding.acceptedBody",
                            f.acceptedUntil() == null ? "—" : f.acceptedUntil())))
                    .append("</div></div>");
        }

        body.append("<div class=\"grid grid-2 mb-6\">")
                .append(findingFacts(messages, f))
                .append(mayTriage ? findingForm(messages, id, f, severities) : "")
                .append("</div>");

        // Description and proof of concept, RENDERED THROUGH Markdown. See the class note.
        body.append(prose(messages, "finding.description", f.description()));
        body.append(prose(messages, "finding.poc", f.proofOfConcept()));

        if (mayTriage && f.open()) {
            body.append(closeCard(messages, id, f));
        }
        if (mayAccept) {
            body.append(acceptCard(messages, id, f));
        }
        body.append(commentCard(messages, comments,
                BOARD + id + "/findings/" + findingId + "/comments", principal != null));

        Page.Context context = Page.Context.of("finding.title", "/board",
                        Optional.ofNullable(principal))
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("nav.board"), Optional.of("/board")),
                        new Page.Crumb(messages.get("board.detail.title"),
                                Optional.of("/board/" + id)),
                        new Page.Crumb(f.title(), Optional.empty())));
        return html(Page.render(messages, context, body.toString()));
    }

    private static String findingFacts(Messages messages, AssessmentService.Finding f) {
        return "<section class=\"card\"><div class=\"card-header\"><h2 class=\"card-title\">"
                + Html.text(f.title()) + "</h2>"
                + (f.severity() == null ? "" : pill(severityTone(f.severity()), f.severity()))
                + "</div><div class=\"card-body col gap-3\">"
                + definition(messages.get("board.findings.col.state"),
                        f.accepted() ? messages.get("finding.state.accepted") : f.state())
                + definition(messages.get("board.findings.col.context"),
                        f.assessmentContext() == null ? "—"
                                : messages.get("finding.context." + f.assessmentContext(),
                                        f.assessmentContext()))
                + definition(messages.get("finding.class"), f.findingClass())
                + definition(messages.get("board.findings.col.detected"), f.firstDetectedAt())
                + definition(messages.get("finding.lastSeen"), f.lastDetectedAt())
                + definition(messages.get("finding.asset"),
                        f.assetName() == null ? "—" : f.assetName())
                + definition(messages.get("finding.tool"), f.sourceTool())
                + "</div></section>";
    }

    private static String findingForm(Messages messages, UUID requestId,
            AssessmentService.Finding f, List<Map<String, String>> severities) {
        return "<section class=\"card\"><div class=\"card-header\"><h2 class=\"card-title\">"
                + Html.text(messages.get("finding.amend")) + "</h2></div><div class=\"card-body\">"
                + "<form method=\"post\" action=\"/board/" + requestId + "/findings/" + f.id()
                + "\">" + Forms.idempotencyField()
                + "<input type=\"hidden\" name=\"row_version\" value=\""
                + f.rowVersion() + "\">"
                + "<div class=\"form-grid\">"
                + Forms.field(messages, "title", "board.findings.col.title", "text", f.title(),
                        true, null)
                + Forms.select(messages, "severity", "board.findings.col.severity",
                        severities.stream().map(s -> Map.entry(s.get("id"), s.get("code"))).toList(),
                        "")
                + Forms.select(messages, "context", "board.findings.col.context",
                        AssessmentService.CONTEXTS.stream()
                                .map(c -> Map.entry(c, messages.get("finding.context." + c, c)))
                                .toList(),
                        f.assessmentContext() == null
                                ? AssessmentService.DEFAULT_CONTEXT : f.assessmentContext())
                + "</div><div class=\"mt-6 col gap-4\">"
                + textarea(messages, "description", "finding.description",
                        f.description() == null ? "" : f.description(), 6,
                        messages.get("finding.markdownHint"), uploadTo(requestId, f.id()))
                + textarea(messages, "proof_of_concept", "finding.poc",
                        f.proofOfConcept() == null ? "" : f.proofOfConcept(), 8,
                        messages.get("finding.pocHint"), uploadTo(requestId, f.id()))
                + "</div><div class=\"form-actions\">"
                + "<button class=\"btn btn-primary\" type=\"submit\">"
                + Html.text(messages.get("app.editor.save")) + "</button></div></form></div></section>";
    }

    /**
     * Closing a finding with an outcome.
     *
     * <p>Risk acceptance is deliberately absent from these buttons and lives in its own card: it is
     * the only closure that expires, needs an approver, and brings the finding back. Putting it in a
     * dropdown beside "false positive" would make the four look interchangeable, and they are not —
     * three end the finding and one postpones it.
     *
     * <p>{@code FIXED_VERIFIED} asks HOW it was verified, because
     * {@code ck_finding__verified_closure} refuses the value without a method and a verifier. "Fixed"
     * asserted by whoever wrote the fix and checked by nobody is the closure an auditor opens first.
     */
    private static String closeCard(Messages messages, UUID requestId,
            AssessmentService.Finding f) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card mb-6\"><div class=\"card-header\">")
                .append("<h2 class=\"card-title\">")
                .append(Html.text(messages.get("finding.close.title"))).append("</h2>")
                .append("<p class=\"fs-12 muted\">")
                .append(Html.text(messages.get("finding.close.lede")))
                .append("</p></div><div class=\"card-body\">")
                .append("<form method=\"post\" action=\"/board/")
                .append(Html.text(requestId.toString())).append("/findings/")
                .append(Html.text(f.id().toString())).append("/close\">")
                .append(Forms.idempotencyField())
                .append("<input type=\"hidden\" name=\"row_version\" value=\"")
                .append(f.rowVersion()).append("\">")
                .append("<div class=\"form-grid\">")
                .append(Forms.select(messages, "reason", "finding.close.outcome",
                        AssessmentService.CLOSURES.stream()
                                .map(c -> Map.entry(c, messages.get("finding.closure." + c, c)))
                                .toList(),
                        "FIXED_VERIFIED"))
                .append(Forms.field(messages, "method", "finding.close.method", "text", "RETEST",
                        false, messages.get("finding.close.methodHint")))
                .append("</div><div class=\"form-actions\">")
                .append("<button class=\"btn btn-sm btn-primary\" type=\"submit\">")
                .append(Html.text(messages.get("finding.close.action")))
                .append("</button></div></form></div></section>");
        return out.toString();
    }

    private static String acceptCard(Messages messages, UUID requestId,
            AssessmentService.Finding f) {
        if (f.accepted()) {
            return "<section class=\"card mb-6\"><div class=\"card-header\"><h2 class=\"card-title\">"
                    + Html.text(messages.get("finding.reopenTitle")) + "</h2></div>"
                    + "<div class=\"card-body\"><p class=\"fs-13\">"
                    + Html.text(messages.get("finding.reopenLede")) + "</p>"
                    + "<form method=\"post\" action=\"/board/" + requestId + "/findings/" + f.id()
                    + "/reopen\">" + Forms.idempotencyField()
                    + "<input type=\"hidden\" name=\"row_version\" value=\"" + f.rowVersion() + "\">"
                    + "<div class=\"form-actions\"><button class=\"btn btn-sm\" type=\"submit\">"
                    + Html.text(messages.get("finding.reopenAction"))
                    + "</button></div></form></div></section>";
        }
        return "<section class=\"card mb-6\"><div class=\"card-header\"><h2 class=\"card-title\">"
                + Html.text(messages.get("finding.acceptTitle")) + "</h2>"
                + "<p class=\"fs-12 muted\">" + Html.text(messages.get("finding.acceptLede"))
                + "</p></div><div class=\"card-body\">"
                + "<form method=\"post\" action=\"/board/" + requestId + "/findings/" + f.id()
                + "/accept\">" + Forms.idempotencyField()
                + "<input type=\"hidden\" name=\"row_version\" value=\"" + f.rowVersion() + "\">"
                + "<div class=\"form-grid\">"
                + Forms.field(messages, "until", "finding.acceptUntil", "date", "", true,
                        messages.get("finding.acceptUntilHint"))
                + "</div><div class=\"form-actions\">"
                + "<button class=\"btn btn-sm btn-danger\" type=\"submit\">"
                + Html.text(messages.get("finding.acceptAction")) + "</button></div></form></div></section>";
    }

    /** A rendered prose block, or an explicit statement that it is empty. */
    private static String prose(Messages messages, String labelKey, String markdown) {
        String rendered = Markdown.render(markdown);
        return "<section class=\"card mb-6\"><div class=\"card-header\"><h2 class=\"card-title\">"
                + Html.text(messages.get(labelKey)) + "</h2></div>"
                + "<div class=\"card-body md\">"
                + (rendered.isEmpty()
                        ? "<p class=\"fs-13 muted\">" + Html.text(messages.get("finding.empty"))
                                + "</p>"
                        : rendered)
                + "</div></section>";
    }

    // ----------------------------------------------------------------------------------------------

    private static String commentCard(Messages messages, List<AssessmentService.Comment> comments,
            String action, boolean mayComment) {
        StringBuilder out = new StringBuilder(4096);
        out.append("<section class=\"card mb-6\"><div class=\"card-header\">")
                .append("<h2 class=\"card-title\">")
                .append(Html.text(messages.get("comment.title"))).append("</h2>")
                .append("<span class=\"pill pill-unknown\">").append(comments.size())
                .append("</span></div><div class=\"card-body col gap-4\">");
        if (comments.isEmpty()) {
            out.append("<p class=\"fs-13 muted\">")
                    .append(Html.text(messages.get("comment.none"))).append("</p>");
        }
        for (AssessmentService.Comment comment : comments) {
            out.append("<div class=\"comment\"><div class=\"row between fs-11 muted\">")
                    .append("<span>")
                    .append(Html.text(comment.authorName() == null ? "—" : comment.authorName()))
                    .append("</span><span>").append(Html.text(comment.createdAt()))
                    .append(comment.editCount() > 0
                            ? " · " + Html.text(messages.get("comment.edited", comment.editCount()))
                            : "")
                    .append("</span></div><div class=\"md\">")
                    // A redacted comment keeps its row and loses its body: the record that somebody
                    // said something at a time survives, which is what DOC-14 makes inviolable.
                    .append(comment.redacted()
                            ? "<p class=\"fs-13 muted\">"
                                    + Html.text(messages.get("comment.redacted")) + "</p>"
                            : Markdown.render(comment.body()))
                    .append("</div></div>");
        }
        if (mayComment) {
            out.append("<form method=\"post\" action=").append(Html.attribute(action)).append(">")
                    .append(Forms.idempotencyField())
                    .append(textarea(messages, "body", "comment.add", "", 4,
                            messages.get("finding.markdownHint"), uploadFromAction(action)))
                    .append("<div class=\"form-actions\">")
                    .append("<button class=\"btn btn-primary btn-sm\" type=\"submit\">")
                    .append(Html.text(messages.get("comment.post")))
                    .append("</button></div></form>");
        }
        return out.append("</div></section>").toString();
    }

    // ----------------------------------------------------------------------------------------------

    /** {@code POST /board/{id}/findings}. */
    public Dispatcher.Response recordFinding(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || assessments.request(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        String title = form.getOrDefault("title", "").strip();
        if (title.isEmpty()) {
            return redirect("/board/" + id + "?invalid=1");
        }
        assessments.recordFinding(principal, id, uuid(form.get("asset")), title,
                uuid(form.get("severity")), "MANUAL",
                form.getOrDefault("context", AssessmentService.DEFAULT_CONTEXT),
                form.get("description"), form.get("proof_of_concept"));
        return redirect("/board/" + id + "?saved=1");
    }

    /** {@code POST /board/{id}/findings/{findingId}}. */
    public Dispatcher.Response amendFinding(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        UUID findingId = uuid(request.pathVariables().get("findingId"));
        if (id == null || findingId == null
                || assessments.finding(principal, id, findingId).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        boolean saved = assessments.updateFinding(principal, findingId,
                form.getOrDefault("title", "").strip(), uuid(form.get("severity")),
                form.get("context"), form.get("description"), form.get("proof_of_concept"),
                integer(form.get("row_version")));
        return redirect("/board/" + id + "/findings/" + findingId
                + (saved ? "?saved=1" : "?stale=1"));
    }

    /** {@code POST /board/{id}/findings/{findingId}/accept}. */
    public Dispatcher.Response accept(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        UUID findingId = uuid(request.pathVariables().get("findingId"));
        Optional<AssessmentService.Finding> found = id == null || findingId == null
                ? Optional.empty() : assessments.finding(principal, id, findingId);
        if (found.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        java.time.LocalDate until;
        try {
            until = java.time.LocalDate.parse(form.getOrDefault("until", ""));
        } catch (java.time.format.DateTimeParseException e) {
            return redirect("/board/" + id + "/findings/" + findingId + "?stale=1");
        }
        boolean done = assessments.acceptRisk(principal, findingId,
                found.orElseThrow().findingClass(), until, integer(form.get("row_version")));
        return redirect("/board/" + id + "/findings/" + findingId
                + (done ? "?saved=1" : "?stale=1"));
    }

    /** {@code POST /board/{id}/findings/{findingId}/reopen}. */
    public Dispatcher.Response reopen(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        UUID findingId = uuid(request.pathVariables().get("findingId"));
        if (id == null || findingId == null
                || assessments.finding(principal, id, findingId).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        boolean done = assessments.reopen(principal, findingId, integer(form.get("row_version")));
        return redirect("/board/" + id + "/findings/" + findingId
                + (done ? "?saved=1" : "?stale=1"));
    }

    /** {@code POST /board/{id}/comments}. */
    public Dispatcher.Response commentOnRequest(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || assessments.request(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        String body = AccountPages.parseForm(request.rawForm().orElse(""))
                .getOrDefault("body", "").strip();
        if (!body.isEmpty()) {
            assessments.addComment(principal, "ASSESSMENT_REQUEST", id, body);
        }
        return redirect("/board/" + id + "?saved=1");
    }

    /** {@code POST /board/{id}/findings/{findingId}/comments}. */
    public Dispatcher.Response commentOnFinding(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        UUID findingId = uuid(request.pathVariables().get("findingId"));
        if (id == null || findingId == null
                || assessments.finding(principal, id, findingId).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        String body = AccountPages.parseForm(request.rawForm().orElse(""))
                .getOrDefault("body", "").strip();
        if (!body.isEmpty()) {
            assessments.addComment(principal, "FINDING", findingId, body);
        }
        return redirect("/board/" + id + "/findings/" + findingId + "?saved=1");
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * {@code POST /board/{id}/attachments} — an image pasted into a write-up or a comment.
     *
     * <p>The bytes arrive <b>base64 in an ordinary form field</b>, not as multipart. A deliberate
     * trade: multipart means a parser handling attacker-controlled framing on the request path of
     * every route, for one feature. Base64 reuses the body handling that already exists, costs a
     * third in size against a bound the transport already enforces, and moves the only new parsing to
     * {@code Base64.getDecoder}, which fails closed.
     *
     * <p>The editor downscales before encoding, so a screenshot fits the cap without the person
     * having to think about it.
     */
    public Dispatcher.Response upload(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || assessments.request(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        // The attachment belongs to the FINDING when one is named, otherwise to the request. Either
        // way the subject is re-validated: a finding identifier from another request resolves to
        // nothing here (SEC-AUZ-017).
        UUID finding = uuid(form.get("finding"));
        String kind = finding == null ? "ASSESSMENT_REQUEST" : "FINDING";
        UUID subject = finding == null ? id : finding;
        if (finding != null && assessments.finding(principal, id, finding).isEmpty()) {
            return Dispatcher.Response.notFound();
        }

        byte[] content;
        try {
            content = java.util.Base64.getDecoder().decode(
                    form.getOrDefault("data", "").replaceAll("^data:[^,]*,", ""));
        } catch (IllegalArgumentException e) {
            return new Dispatcher.Response(400, Map.of("error", "not base64"), Map.of());
        }
        Optional<UUID> stored = attachments.store(principal, kind, subject, content,
                form.get("filename"));
        if (stored.isEmpty()) {
            // One message for "too large" and "not an image". The caller controls both and does not
            // need them distinguished to fix either.
            return new Dispatcher.Response(400, Map.of("error", "rejected"), Map.of());
        }
        String url = "/attachments/" + stored.orElseThrow();
        // Both forms. `markdown` is what the server-rendered editor inserts at the cursor; `url` is
        // what CKEditor's upload contract expects back. One endpoint rather than two, because a
        // second one would be a second place the subject authorization has to be got right.
        return new Dispatcher.Response(200, Map.of(
                "markdown", "![" + sanitizeAlt(form.get("filename")) + "](" + url + ")",
                "url", url), Map.of());
    }

    /**
     * {@code GET /attachments/{id}} — the bytes.
     *
     * <p>The headers are the control. {@code nosniff} stops a browser deciding for itself that a file
     * is HTML; the content type is the one the server derived from the bytes and never the one the
     * uploader claimed; and a restrictive policy means that even if something here were treated as a
     * document, it would have no script, no frame and no origin to talk to.
     */
    public Dispatcher.Response attachment(Dispatcher.Request request) throws Exception {
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        Optional<aspm.app.assessment.AttachmentService.Attachment> found =
                attachments.load(request.principal(), id);
        if (found.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        var attachment = found.orElseThrow();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", attachment.mediaType());
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Content-Security-Policy",
                "default-src 'none'; img-src 'self'; sandbox; frame-ancestors 'self'");
        headers.put("Referrer-Policy", "no-referrer");
        // `no-cache` rather than a lifetime: the browser may keep the bytes, but it must ask before
        // showing them again, and that ask goes through `attachments.load`, which composes the
        // caller's scope. With `max-age` the bytes were served from disk for a day after the scope
        // that granted them was narrowed, the session revoked or the principal offboarded — evidence
        // outliving its authorization, on a shared workstation, for the one content class the
        // platform holds that is expected to be sensitive and sometimes malicious.
        headers.put("Cache-Control", "private, no-cache");
        // The hash IS the version: there is no UPDATE grant on the table, so the bytes cannot change
        // and a strong validator is honest here rather than optimistic.
        headers.put("ETag", "\"" + attachment.hashHex() + "\"");
        return new Dispatcher.Response(200,
                new InterfaceResource.Binary(attachment.content()), headers);
    }

    private static String sanitizeAlt(String filename) {
        if (filename == null || filename.isBlank()) {
            return "image";
        }
        // Brackets and parentheses would break out of the Markdown image syntax. Stripped rather than
        // escaped: an alt text is a label, not a payload.
        return filename.replaceAll("[\\[\\]()\\r\\n]", " ").strip();
    }

    /**
     * A Markdown editor: a toolbar, a textarea, and a live preview.
     *
     * <p>The toolbar inserts Markdown into the textarea and the preview renders it. Both are added by
     * script and both are <b>absent</b> without it, leaving a plain textarea that still works —
     * {@code PRD-UIX-013} forbids a capability that exists only for a pointer, and the same reasoning
     * covers one that exists only with script.
     *
     * <p>The preview is rendered in the browser and is a CONVENIENCE, not the renderer. What the page
     * ultimately displays comes from {@link Markdown} on the server, which is the only thing that
     * decides what markup exists. A client-side preview that disagreed would be a cosmetic surprise;
     * a client-side preview that was trusted would be a cross-site scripting hole, so the script
     * inserts its output as text and never as markup.
     *
     * @param upload the {@code data-md-upload} / {@code data-md-finding} attributes naming where an
     *     image goes; empty leaves the editor text-only, which is what a form with no subject to
     *     attach to must get — an upload control that has nowhere to put the bytes is a control that
     *     fails at the moment somebody trusts it
     */
    private static String textarea(Messages messages, String name, String labelKey, String value,
            int rows, String hint, String upload) {
        String id = "md-" + name + "-" + Integer.toHexString(name.hashCode() & 0xffff);
        return "<label class=\"col gap-1\" for=" + Html.attribute(id) + ">"
                + "<span class=\"fs-12 muted\">" + Html.text(messages.get(labelKey)) + "</span>"
                + "</label>"
                + "<div class=\"md-editor\" data-md-editor" + upload + ">"
                + "<textarea class=\"input md-input\" id=" + Html.attribute(id)
                + " name=" + Html.attribute(name)
                + " rows=\"" + rows + "\" data-md-input>" + Html.text(value) + "</textarea>"
                + "</div>"
                + (hint == null ? ""
                        : "<span class=\"fs-11 muted\">" + Html.text(hint) + "</span>")
                + (upload.isEmpty() ? "<span class=\"fs-11 muted\">"
                        + Html.text(messages.get("editor.imageUnavailable")) + "</span>" : "");
    }

    /**
     * The one statement of where the board lives.
     *
     * <p>Named because it is read twice — once to BUILD an upload endpoint and once to RECOGNISE a
     * comment action — and the two must agree. They did not: moving the interface off the {@code /ui}
     * prefix rewrote the builder and left the recogniser matching the old address, so
     * {@link #uploadFromAction} returned nothing for every comment form and image upload was absent
     * from all of them. Absent, not broken: the editor degrades to text-only by design
     * ({@code PRD-UIX-013}), so nothing failed loudly and no test disagreed.
     */
    private static final String BOARD = "/board/";

    /** Where an image dropped into this editor is stored. Empty when there is no subject yet. */
    private static String uploadTo(UUID requestId, UUID findingId) {
        if (requestId == null) {
            return "";
        }
        return " data-md-upload=" + Html.attribute(BOARD + requestId + "/attachments")
                + (findingId == null ? "" : " data-md-finding=" + Html.attribute(findingId.toString()));
    }

    /**
     * The same, derived from a comment form's action.
     *
     * <p>Derived rather than passed because the action already encodes exactly the subject the
     * comment attaches to. Threading the two identifiers separately would create a second statement
     * of the same fact, and the two could disagree — the failure being an image filed against a
     * different record from the comment that shows it.
     *
     * <p>The prefix comes from {@link #BOARD} rather than from a literal, so a future move of the
     * board cannot rewrite one side of this pair and leave the other behind, which is exactly what
     * happened once.
     */
    private static String uploadFromAction(String action) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^" + java.util.regex.Pattern.quote(BOARD)
                + "([0-9a-fA-F-]{36})(?:/findings/([0-9a-fA-F-]{36}))?/comments$")
                .matcher(action == null ? "" : action);
        if (!m.matches()) {
            return "";
        }
        return uploadTo(uuid(m.group(1)), m.group(2) == null ? null : uuid(m.group(2)));
    }

    /**
     * The trigger picker's options.
     *
     * <p>"Not stated" is a real option rather than the blank one. The blank entry means "any"; a
     * request with no recorded reason is a different thing from a filter that does not care, and
     * conflating them hides the requests somebody needs to go back and classify.
     */
    private static List<Map.Entry<String, String>> triggerOptions(Messages messages,
            List<AssessmentService.Trigger> triggers) {
        List<Map.Entry<String, String>> options = new ArrayList<>();
        options.add(Map.entry("", messages.get("app.value.any")));
        for (AssessmentService.Trigger t : triggers) {
            options.add(Map.entry(t.code(), t.countsAsFullReview()
                    ? t.label() + " ★" : t.label()));
        }
        options.add(Map.entry("none", messages.get("board.trigger.none")));
        return List.copyOf(options);
    }

    /**
     * What a typed person name resolved to.
     *
     * <p>Three outcomes, and the third is why this is not just a nullable UUID: blank means "nobody",
     * a match means that person, and text that matches nothing is a MISTAKE that must be reported.
     * Collapsing the third into the first is what makes a picker lose an assignment silently.
     */
    private record Resolved(UUID id, boolean unresolved) {
    }

    /**
     * Resolves what somebody typed into the person picker back to a principal.
     *
     * <p>Matched against the option label first, then against either half of it — the label is
     * {@code display name · username}, and somebody who types just the username has named a person
     * unambiguously and should not be told they have not.
     *
     * <p>The candidate list is the caller's own {@code assignableprincipals} query, so a name that
     * resolves here is by construction one this caller could already see. Resolution is not an
     * authorization decision and does not become one.
     */
    private static Resolved resolvePerson(List<Map<String, String>> people, String typed) {
        String text = typed == null ? "" : typed.strip();
        if (text.isEmpty()) {
            return new Resolved(null, false);
        }
        for (Map<String, String> person : people) {
            if (text.equalsIgnoreCase(person.get("name"))) {
                return new Resolved(uuid(person.get("id")), false);
            }
        }
        // The halves. Exact, not prefix: a prefix match over a list containing both "Nguyen Van A"
        // and "Nguyen Van An" would resolve the shorter name to whichever row came first.
        List<UUID> partial = new ArrayList<>();
        for (Map<String, String> person : people) {
            String label = person.getOrDefault("name", "");
            int separator = label.indexOf("  ·  ");
            String display = separator < 0 ? label : label.substring(0, separator);
            String username = separator < 0 ? "" : label.substring(separator + 5);
            if (text.equalsIgnoreCase(display) || text.equalsIgnoreCase(username)) {
                partial.add(uuid(person.get("id")));
            }
        }
        // Exactly one, or it is unresolved. Two people sharing a display name is not rare in a group,
        // and picking the first would assign work to the wrong person with no sign anything happened.
        return partial.size() == 1 ? new Resolved(partial.get(0), false) : new Resolved(null, true);
    }

    private static List<String> states(List<AssessmentService.Request> rows) {
        List<String> states = new ArrayList<>();
        for (AssessmentService.Request row : rows) {
            if (!states.contains(row.state())) {
                states.add(row.state());
            }
        }
        java.util.Collections.sort(states);
        return states;
    }

    private static List<String> concat(List<String> ancestors, String leaf) {
        List<String> all = new ArrayList<>(ancestors);
        all.add(leaf);
        return all;
    }

    private static List<Map.Entry<String, String>> withBlank(String label,
            List<Map.Entry<String, String>> options) {
        List<Map.Entry<String, String>> all = new ArrayList<>();
        all.add(Map.entry("", label));
        all.addAll(options);
        return all;
    }

    private static String stateTone(String state) {
        return switch (state) {
            case "DRAFT" -> "unknown";
            case "INTAKE_REVIEW", "RETURNED_FOR_INFO" -> "warn";
            case "SCHEDULED" -> "info";
            case "IN_PROGRESS", "FIXING" -> "high";
            default -> state.startsWith("CLOSED") ? "ok" : "info";
        };
    }

    private static String severityTone(String code) {
        return switch (code) {
            case "CRITICAL" -> "critical";
            case "HIGH" -> "high";
            case "MEDIUM" -> "medium";
            case "LOW" -> "low";
            default -> "info";
        };
    }

    private static UUID uuid(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int integer(String raw) {
        try {
            return raw == null || raw.isBlank() ? -1 : Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String kpi(Messages messages, String labelKey, String value) {
        return kpi(messages, labelKey, value, null);
    }

    /**
     * A KPI whose figure carries a tone.
     *
     * <p>The tone is applied only when the figure is non-zero. A colour that fires on zero teaches
     * people to ignore it — "overdue: 0" in red is the boy who cried wolf rendered in CSS — and the
     * whole point of colouring these is that the one number that matters should be the one that
     * catches the eye.
     *
     * <p>Never colour alone: DOC-00 forbids colour as the sole carrier of meaning, so the label
     * already names the condition and the tone only reinforces it.
     */
    private static String kpi(Messages messages, String labelKey, String value, String tone) {
        boolean lit = tone != null && !"0".equals(value) && !value.isBlank() && !"—".equals(value);
        return "<div class=\"card kpi" + (lit ? " kpi-" + tone : "") + "\">"
                + "<span class=\"kpi-label\">" + Html.text(messages.get(labelKey)) + "</span>"
                + "<span class=\"kpi-value\">" + Html.text(value) + "</span></div>";
    }

    private static String th(Messages messages, String key) {
        return "<th scope=\"col\">" + Html.text(messages.get(key)) + "</th>";
    }

    private static String definition(String label, String value) {
        return "<div class=\"row between gap-3\">"
                + "<span class=\"fs-12 muted\">" + Html.text(label) + "</span>"
                + "<span class=\"fs-13\">" + Html.text(value) + "</span></div>";
    }

    private static String pill(String tone, String label) {
        return "<span class=\"pill pill-" + tone + "\">" + Html.text(label) + "</span>";
    }

    private static String muted(String text) {
        return "<span class=\"fs-12 muted\">" + Html.text(text) + "</span>";
    }

    private static String notice(String message) {
        return "<div class=\"banner\" role=\"status\">" + Html.text(message) + "</div>";
    }

    private static String danger(String message) {
        return "<div class=\"banner banner-danger\" role=\"alert\">" + Html.text(message) + "</div>";
    }

    private static Dispatcher.Response html(String markup) {
        return new Dispatcher.Response(200, new InterfaceResource.Raw(markup),
                Map.of("Content-Type", "text/html; charset=utf-8"));
    }

    private static Dispatcher.Response redirect(String location) {
        return new Dispatcher.Response(303, new InterfaceResource.Raw(""), Map.of(
                "Location", location, "Content-Type", "text/html; charset=utf-8"));
    }
}
