package aspm.app.ui;

import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import aspm.app.resource.ResourceCatalogue;
import aspm.app.resource.ResourceEndpoint;
import aspm.app.resource.ResourceGroup;
import aspm.module.insight.domain.PresentationState;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The interface pages. DOC-08, ADR-058.
 *
 * <p>Every page goes through the same {@link ResourceEndpoint} the API uses, so authorization, the scope
 * predicate, and the projection are the same code. An interface with its own query path is an interface
 * with its own authorization defects, and DOC-07 makes that explicit: "every context enforces
 * authorization through a single published evaluation contract. Contexts do not implement their own
 * checks — a per-context check is how enforcement points get omitted."
 *
 * <h2>Keyboard parity is structural</h2>
 *
 * <p>{@code PRD-UIX-013}: "Every action available by pointer MUST be available by keyboard. A pointer-only
 * capability MUST NOT exist." Every action rendered here is an {@code <a href>} or a {@code <button>}
 * inside a {@code <form>}. There is no code path that attaches behaviour to a non-interactive element,
 * which is what makes the parity a property rather than a review item.
 */
public final class InterfaceResource {

    /**
     * These four uses are reads, and a read on a class A path records nothing — but the endpoint takes
     * a trail rather than an optional one, so that adding a write here later cannot be done without
     * noticing the audit obligation.
     */
    private static final aspm.app.audit.AuditTrail AUDIT =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    private final DataSource dataSource;

    public InterfaceResource(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /** {@code GET /style.css}. Class G — a stylesheet discloses nothing. */
    public static Dispatcher.Response stylesheet(Dispatcher.Request request) {
        return new Dispatcher.Response(200, new Raw(DesignSystem.css()),
                Map.of("Content-Type", "text/css; charset=utf-8"));
    }

    /** {@code GET /app.js}. Progressive enhancement only: everything works without it. */
    public static Dispatcher.Response script(Dispatcher.Request request) {
        return new Dispatcher.Response(200, new Raw(Script.js()),
                Map.of("Content-Type", "text/javascript; charset=utf-8"));
    }

    /** A page for one resource group. */
    public Dispatcher.Response list(ResourceGroup group, Dispatcher.Request request) throws Exception {
        Messages messages = messagesFor(request);
        Principal principal = request.principal();

        String body;
        try {
            Dispatcher.Response data = new ResourceEndpoint(dataSource, group, AUDIT).list(request);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) data.body();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
            body = DataTable.render(messages, group, items, !request.query().isEmpty(), request.query());
        } catch (Dispatcher.UnauthorizedException e) {
            // Absence, not denial — the same rule the API follows (PRD-API-036). A page saying
            // "forbidden" would confirm the collection exists to someone who may not see it.
            body = StateRenderer.state(messages, PresentationState.EMPTY_NO_DATA,
                    Optional.of(messages.get("table.empty.generic")));
        }

        Page.Context context = Page.Context.of(sectionKey(group), "/" + group.name(), Optional.ofNullable(principal))
                .withScope(scopeLabel(messages, principal))
                .withBreadcrumbs(breadcrumbs(messages, principal, group.name()))
                .withActions("<a class=\"btn btn-sm\" href=\"/api/v1/" + group.name()
                        + "\">" + Html.text(messages.get("action.viewJson")) + "</a>");
        return html(Page.render(messages, context, body));
    }

    /**
     * The overview dashboard. DOC-12.
     *
     * <p>⚠ The figures here are computed from the read models the projections would populate, and no
     * projection runs yet — so the populations are what the tables actually hold. That is deliberate: a
     * dashboard seeded with plausible demonstration numbers is the exact failure the honesty surfaces
     * exist to prevent, and it would be indistinguishable from a working one to anyone evaluating the
     * product. What renders instead is the unmeasured state, which is the truth.
     */
    public Dispatcher.Response overview(Dispatcher.Request request) throws Exception {
        Messages messages = messagesFor(request);
        Principal principal = request.principal();

        int findings = countVisible(ResourceCatalogue.FINDINGS, request);
        int assets = countVisible(ResourceCatalogue.ASSETS, request);
        int nodes = countVisible(ResourceCatalogue.ORG_NODES, request);

        List<Overview.Kpi> kpis = List.of(
                new Overview.Kpi("overview.openFindings", findings, findings, Math.max(assets, findings),
                        "/vulnerabilities", Optional.empty(), List.of()),
                new Overview.Kpi("overview.overdue", 0, 0, Math.max(findings, 0),
                        "/vulnerabilities?state=OPEN", Optional.empty(), List.of()),
                // /ui/assessments has never had a route. The assessment surface is /ui/board, and a
                // coverage figure whose drill-down 404s teaches people not to click the figures.
                new Overview.Kpi("overview.assessmentCoverage", 0, 0, assets,
                        "/board", Optional.empty(), List.of()),
                new Overview.Kpi("overview.compositionCoverage", 0, 0, assets,
                        "/composition", Optional.empty(), List.of()));

        List<Overview.Coverage> coverages = List.of(
                new Overview.Coverage("overview.assessmentCoverage", 0, assets, "/board"),
                new Overview.Coverage("overview.compositionCoverage", 0, assets, "/composition"),
                new Overview.Coverage("nav.organization", nodes, nodes, "/organization"));

        // A trend with no measured period. Every point is null, so the line does not draw and the
        // tabular alternative says "not measured" for each period rather than showing zeros.
        List<Chart.Point> trend = List.of(
                new Chart.Point("T-5", null), new Chart.Point("T-4", null),
                new Chart.Point("T-3", null), new Chart.Point("T-2", null),
                new Chart.Point("T-1", null), new Chart.Point("T", null));

        List<Chart.Bar> severity = List.of(
                new Chart.Bar(messages.get("severity.critical"), 0, "danger"),
                new Chart.Bar(messages.get("severity.high"), 0, "warn"),
                new Chart.Bar(messages.get("severity.medium"), 0, ""),
                new Chart.Bar(messages.get("severity.low"), 0, "ok"));

        String recent;
        try {
            Dispatcher.Response data = new ResourceEndpoint(dataSource, ResourceCatalogue.FINDINGS, AUDIT)
                    .list(request);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) data.body();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
            recent = DataTable.compact(messages, ResourceCatalogue.FINDINGS, items);
        } catch (Dispatcher.UnauthorizedException e) {
            recent = StateRenderer.state(messages, PresentationState.EMPTY_NO_DATA,
                    Optional.of(messages.get("table.empty.findings")));
        }

        Page.Context context = Page.Context.of("overview.title", "/overview", Optional.ofNullable(principal))
                .withSubtitle("overview.subtitle")
                .withScope(scopeLabel(messages, principal))
                .withBreadcrumbs(breadcrumbs(messages, principal, null));
        return html(Page.render(messages, context,
                Overview.render(messages, kpis, coverages, trend, severity, recent)));
    }

    /**
     * {@code GET /requests/{id}}. The intake detail page.
     *
     * <p>Authorization is the parent read: {@link ResourceEndpoint#get} re-validates against the object
     * and returns absence for anything out of scope, so a request belonging to a node this principal does
     * not reach is a 404 identical to one that does not exist. The child tables are only read after that
     * has passed.
     */
    public Dispatcher.Response requestDetail(Dispatcher.Request request) throws Exception {
        Messages messages = messagesFor(request);
        Principal principal = request.principal();
        ResourceEndpoint endpoint = new ResourceEndpoint(dataSource, ResourceCatalogue.REQUESTS, AUDIT);

        Dispatcher.Response parent;
        try {
            parent = endpoint.get(request);
        } catch (Dispatcher.UnauthorizedException e) {
            return html(Page.render(messages,
                    Page.Context.of("nav.requestDetail", "/board", Optional.ofNullable(principal))
                            .withScope(scopeLabel(messages, principal)),
                    StateRenderer.state(messages, PresentationState.EMPTY_NO_DATA,
                            Optional.of(messages.get("error.notFound")))));
        }
        if (parent.status() != 200) {
            return html(Page.render(messages,
                    Page.Context.of("nav.requestDetail", "/board", Optional.ofNullable(principal))
                            .withScope(scopeLabel(messages, principal)),
                    StateRenderer.state(messages, PresentationState.EMPTY_NO_DATA,
                            Optional.of(messages.get("error.notFound")))));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) parent.body();
        java.util.UUID id = java.util.UUID.fromString(String.valueOf(row.get("id")));

        List<Map<String, Object>> accounts = endpoint.children(principal,
                "assessment_request_role_account", "request_id", id,
                List.of("role_name", "username", "credential_ref", "mfa_enrolled", "account_status"));
        List<Map<String, Object>> environments = endpoint.children(principal,
                "assessment_request_environment", "request_id", id,
                List.of("env_type", "base_url", "protective_control_present", "bypass_arranged",
                        "rate_limit_present", "data_destruction_allowed", "vpn_required"));

        String code = String.valueOf(row.get("request_code"));
        Page.Context context = Page.Context.of("nav.requestDetail", "/board", Optional.ofNullable(principal))
                .withSubtitle("request.subtitle")
                .withScope(scopeLabel(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        // The board, not /requests. The generic request list was a server-rendered table
                        // over every row and it has no route any more; the board is where a request is
                        // read. A breadcrumb that 404s teaches people that breadcrumbs do not work.
                        new Page.Crumb(messages.get("nav.requests"), Optional.of("/board")),
                        new Page.Crumb(code, Optional.empty())))
                .withActions("<a class=\"btn btn-sm\" href=\"/api/v1/requests/" + id
                        + "\">" + Html.text(messages.get("action.viewJson")) + "</a>");

        var transitionService = new aspm.app.resource.RequestTransition(dataSource);
        var available = transitionService.available(principal, id);
        var history = transitionService.history(principal, id);

        return html(Page.render(messages, context,
                RequestDetail.render(messages, row, accounts, environments, available, history)));
    }

    /**
     * {@code POST /requests/{id}/transitions}. The form post behind the transition buttons.
     *
     * <p>A redirect on success rather than a rendered page, so a refresh does not re-post the
     * transition. Idempotency in DOC-09 §3 already makes a repeat harmless, and this keeps the browser
     * from asking.
     */
    public Dispatcher.Response requestTransition(Dispatcher.Request request) throws Exception {
        Messages messages = messagesFor(request);
        java.util.UUID id;
        try {
            id = java.util.UUID.fromString(request.pathVariables().get("id"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Dispatcher.Response.notFound();
        }

        Map<String, String> form = parseForm(request.rawForm().orElse(""));
        String event = form.getOrDefault("event", "");
        Optional<String> reason = Optional.ofNullable(form.get("reason")).filter(r -> !r.isBlank());

        var outcome = new aspm.app.resource.RequestTransition(dataSource)
                .apply(request.principal(), id, event, reason);

        if (outcome instanceof aspm.app.resource.RequestTransition.Outcome.Applied) {
            return new Dispatcher.Response(303, new Raw(""),
                    Map.of("Location", "/requests/" + id,
                            "Content-Type", "text/html; charset=utf-8"));
        }

        // A denial renders the page again with the reason, rather than a bare error. The reason is the
        // product: a requester told "denied" learns nothing, and one told which readiness condition is
        // missing can act.
        String detail = switch (outcome) {
            case aspm.app.resource.RequestTransition.Outcome.Denied denied ->
                    messages.get("request.actions.blocked", denied.detail());
            case aspm.app.resource.RequestTransition.Outcome.Invalid invalid ->
                    messages.get("request.actions.invalid", invalid.event(), invalid.currentState());
            default -> messages.get("error.generic");
        };
        return html(Page.render(messages,
                Page.Context.of("nav.requestDetail", "/board",
                                Optional.ofNullable(request.principal()))
                        .withScope(scopeLabel(messages, request.principal())),
                StateRenderer.state(messages, PresentationState.ERROR, Optional.of(detail))
                        + "<p class=\"mt-4\">"
                        + "<a class=\"btn btn-sm\" href=\"/requests/" + id + "\">"
                        + Html.text(messages.get("request.actions.back")) + "</a></p>"));
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new java.util.LinkedHashMap<>();
        for (String pair : body.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            form.put(java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(value.replace('+', ' '),
                            java.nio.charset.StandardCharsets.UTF_8));
        }
        return form;
    }

    /** How many rows the caller can actually see. Never the tenant total. */
    private int countVisible(ResourceGroup group, Dispatcher.Request request) {
        try {
            Dispatcher.Response data = new ResourceEndpoint(dataSource, group, AUDIT).list(request);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) data.body();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
            return items.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The scope label.
     *
     * <p>⚠ It reports how many nodes the principal reaches rather than naming the node, because the
     * node's tenant-configured name would have to be read through the organization module and the
     * interface has no query for it yet. A count is honest; a placeholder name would not be.
     */
    static Optional<String> scopeLabelFor(Messages messages, Principal principal) {
        return scopeLabel(messages, principal);
    }

    private static Optional<String> scopeLabel(Messages messages, Principal principal) {
        if (principal == null || principal.scopeNodeIds().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(messages.get("scope.nodeCount",
                Integer.valueOf(principal.scopeNodeIds().size())));
    }

    /**
     * Breadcrumbs from the caller's scope root. {@code PRD-UIX-010}: never from a tenant root they cannot
     * see, because displaying an unreachable ancestor discloses the organization's shape above them.
     */
    private static List<Page.Crumb> breadcrumbs(Messages messages, Principal principal, String leaf) {
        if (principal == null) {
            return List.of();
        }
        List<Page.Crumb> crumbs = new java.util.ArrayList<>();
        crumbs.add(new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")));
        if (leaf != null) {
            crumbs.add(new Page.Crumb(leaf, Optional.empty()));
        }
        return List.copyOf(crumbs);
    }

    private static String sectionKey(ResourceGroup group) {
        return switch (group.name()) {
            case "findings" -> "nav.findings";
            case "assets" -> "nav.assets";
            case "requests" -> "nav.requests";
            case "org-nodes" -> "nav.organization";
            default -> "nav.nodeTypes";
        };
    }

    /**
     * The viewer's locale, from {@code Accept-Language}.
     *
     * <p>{@code INT-UIX-010} formats per the viewer's locale. The pseudo-locale is reachable by asking
     * for it explicitly, which is how the build gate exercises a rendered page rather than a bundle.
     */
    static Messages messagesFor(Dispatcher.Request request) {
        String header = request.headers().getOrDefault("accept-language", "");
        if (header.toLowerCase(Locale.ROOT).contains("qps")) {
            return Messages.forLocale(Messages.PSEUDO);
        }
        if (header.toLowerCase(Locale.ROOT).startsWith("vi")) {
            return Messages.forLocale(Messages.VIETNAMESE);
        }
        return Messages.forLocale(Messages.SOURCE);
    }

    private static Dispatcher.Response html(String markup) {
        return new Dispatcher.Response(200, new Raw(markup),
                Map.of("Content-Type", "text/html; charset=utf-8"));
    }

    /** A body already serialized. The JSON writer must not touch it. */
    public record Raw(String content) {
    }

    /**
     * A response body that is bytes, not text.
     *
     * <p>An inline image. Distinct from {@link Raw} because the transport must not put it through a
     * character encoder — {@code new String(png, UTF_8).getBytes(UTF_8)} is not the PNG, and the
     * corruption is silent.
     *
     * <p>A class rather than a record: a record's generated {@code equals} compares an array by
     * REFERENCE, so two identical images would never be equal and somebody would eventually rely on
     * it. Error Prone is right about this, as it was about the credential hash.
     */
    public static final class Binary {

        private final byte[] content;

        public Binary(byte[] content) {
            this.content = content.clone();
        }

        public byte[] content() {
            return content.clone();
        }
    }

    /** An empty body for a redirect, so the dispatcher does not import Raw to build one. */
    public static Raw emptyBody() {
        return new Raw("");
    }

    /** Handlers bound to a group, so the composition root can register one route per section. */
    public Dispatcher.Handler listing(ResourceGroup group) {
        return request -> list(group, request);
    }

    public static List<ResourceGroup> pages() {
        return List.of(ResourceCatalogue.FINDINGS, ResourceCatalogue.REQUESTS,
                ResourceCatalogue.ASSETS, ResourceCatalogue.ORG_NODES,
                ResourceCatalogue.ORG_NODE_TYPES);
    }
}
