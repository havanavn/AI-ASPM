package aspm.app.ui;

import aspm.app.inventory.InventoryService;
import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The application and product inventory. {@code PRD-AST-*}, ADR-009, ADR-001.
 *
 * <ul>
 *   <li>{@code GET /applications} — the list, filterable on every column that has a bounded set of
 *       values and searchable on the ones that do not.
 *   <li>{@code GET /applications/{id}} — one application: its endpoints, repository, services,
 *       features, and the assessment requests whose scope names it.
 *   <li>{@code GET /applications/new}, {@code POST /applications}, {@code POST .../{id}} — the
 *       editor. Class B: a scoped write with a replay key.
 *   <li>{@code POST /applications/{id}/retire} — there is no delete. See the note below.
 * </ul>
 *
 * <h2>Why a dropdown for some filters and a text box for others</h2>
 *
 * <p>A dropdown is offered where the value set is bounded and short — the organization nodes the caller
 * can reach, the four exposure levels, the tenant's criticality tiers, the lifecycle states, the score
 * bands. Each is populated from what actually exists rather than from a literal list, so a tenant that
 * defines a fourth tier gets a fourth option without a code change (ADR-027).
 *
 * <p>Names and identity keys get a search box, because that set is unbounded. Offering a dropdown of
 * every application would be a dropdown that stops being usable at the second business unit.
 *
 * <h2>Retire, not delete</h2>
 *
 * <p>There is no delete button and no {@code DELETE} grant on {@code asset} anywhere in the schema. An
 * application that ever carried a finding cannot be deleted without deleting the subject of that
 * finding, and the finding is the record of a weakness that really existed — product principle 5. What
 * the button does is set {@code lifecycle_state = 'RETIRED'} with a reason the schema requires.
 */
public final class ApplicationPages {

    /** Read the inventory. */
    public static final String READ = "ast.asset.read";
    /** Add an application. */
    public static final String CREATE = "ast.asset.create";
    /** Amend or retire one. */
    public static final String UPDATE = "ast.asset.update";

    private final InventoryService inventory;

    private final aspm.app.assessment.AssessmentService assessments;

    public ApplicationPages(DataSource dataSource) {
        this.inventory = new InventoryService(Objects.requireNonNull(dataSource));
        // The review cadence is assessment data, not inventory data: it is derived from requests and
        // their triggers. Reading it through the assessment service keeps the derivation in one place
        // rather than giving the inventory a second, parallel idea of what a review is (PP-10).
        this.assessments = new aspm.app.assessment.AssessmentService(dataSource);
    }

    // ----------------------------------------------------------------------------------------------

    /** {@code GET /applications}. */
    public Dispatcher.Response list(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();

        if (!inventory.applicationTypeConfigured(principal)) {
            // An actionable empty state rather than an exception. Asset types are TENANT data (ADR-009),
            // so the inventory cannot invent one — and a page that threw here would report a
            // configuration gap as a platform fault.
            return html(Page.render(messages, listContext(messages, principal),
                    StateRenderer.state(messages,
                            aspm.module.insight.domain.PresentationState.EMPTY_NO_DATA,
                            Optional.of(messages.get("app.inventory.typeMissing")))));
        }

        Map<String, String> filters = new LinkedHashMap<>();
        for (String name : List.of("node", "exposure", "criticality", "lifecycle", "band")) {
            String value = request.query().get(name);
            if (value != null && !value.isBlank()) {
                filters.put(name, value);
            }
        }
        // Declared attributes arrive as attr_<key>. Prefixed so a tenant attribute called "sort" or
        // "q" cannot collide with a control parameter — a collision would silently change the sort.
        Map<String, String> attributeFilters = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : request.query().entrySet()) {
            if (entry.getKey().startsWith("attr_") && !entry.getValue().isBlank()) {
                attributeFilters.put(entry.getKey().substring(5), entry.getValue());
            }
        }
        String search = request.query().getOrDefault("q", "");
        String sort = request.query().getOrDefault("sort", "name");
        boolean descending = request.query().containsKey("desc");

        List<InventoryService.Application> rows = inventory.applications(principal, filters,
                attributeFilters, search, sort, descending);
        List<InventoryService.Node> nodes = inventory.nodes(principal, false);
        List<InventoryService.AttributeDefinition> declared =
                inventory.attributeDefinitions(principal, "APPLICATION");

        StringBuilder body = new StringBuilder(8192);
        if (request.query().containsKey("saved")) {
            body.append(notice(messages.get("app.inventory.saved")));
        }
        if (request.query().containsKey("retired")) {
            body.append(notice(messages.get("app.inventory.retired")));
        }
        if (request.query().containsKey("stale")) {
            body.append(danger(messages.get("app.inventory.stale")));
        }

        // The rollup per application, so the totals count the whole composition rather than the
        // application row alone — the same reason the detail page counts the subtree.
        Map<UUID, InventoryService.Posture> postures = new LinkedHashMap<>();
        for (InventoryService.Application row : rows) {
            inventory.posture(principal, row.id()).ifPresent(p -> postures.put(row.id(), p));
        }
        body.append(summary(messages, rows, postures));
        body.append(filterBar(messages, nodes, filters, attributeFilters, declared, search, sort,
                descending));
        body.append(table(messages, rows, assessments.cadences(principal,
                rows.stream().map(InventoryService.Application::id).toList())));

        Page.Context context = listContext(messages, principal)
                .withActions(principal != null && principal.holds(CREATE)
                        ? "<a class=\"btn btn-primary\" href=\"/applications/new\">"
                                + Html.text(messages.get("app.inventory.add")) + "</a>"
                        : "");
        return html(Page.render(messages, context, body.toString()));
    }


    // GET /components — "the technical estate: everything that is not an application" — was here and
    // was REMOVED at the user's request: they could not tell what the page was for. The handler is gone
    // rather than left unrouted, because an unrouted handler is the dead code this tier already carries
    // too much of. InventoryService#components(Principal, Map, String, String, boolean, boolean) and
    // #typeCodes went with it: they had this one caller and nothing else reads them.
    //
    // What went with it is recorded beside the navigation entry in Page.java — the unowned-asset queue.


    /** {@code GET /applications/{id}}. */
    public Dispatcher.Response detail(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        // Re-validated against the OBJECT and the caller's scope, not only against the path.
        // SEC-AUZ-017 — authorizing the path and then loading the row is the defect class this product
        // exists to find.
        Optional<InventoryService.Application> found = inventory.application(principal, id);
        if (found.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        InventoryService.Application app = found.orElseThrow();
        List<InventoryService.Related> related = inventory.related(principal, id);
        List<Map<String, String>> requests = inventory.requestsFor(principal, id);
        List<InventoryService.Component> components = inventory.components(principal, id);
        InventoryService.Posture posture = inventory.posture(principal, id)
                .orElse(null);
        List<InventoryService.Suggestion> suggestions =
                inventory.suggestions(principal, "ASSET", id);
        List<InventoryService.AttributeDefinition> appAttributes =
                inventory.attributeDefinitions(principal, "APPLICATION");
        List<InventoryService.Assurance> assurance = inventory.assurance(principal, id);
        Map<UUID, InventoryService.SbomState> sbom = inventory.sbomStates(principal, id);
        InventoryService.Remediation remediation =
                inventory.remediation(principal, id).orElse(null);
        boolean serviceLevels = inventory.serviceLevelConfigured(principal);

        StringBuilder body = new StringBuilder(8192);
        if (request.query().containsKey("saved")) {
            body.append(notice(messages.get("app.inventory.saved")));
        }
        if ("RETIRED".equals(app.lifecycleState())) {
            body.append(danger(messages.get("app.inventory.isRetired")));
        }

        // The order is the order an assessor reads it in, and it is not the order the numbers are
        // most flattering in:
        //
        //   1. WHAT IS IT       identity, ownership, exposure — nothing below means anything without it
        //   2. WHAT HAS LOOKED  assurance coverage, SBOM freshness. Every count below is qualified
        //                       by this, so it comes BEFORE the counts rather than after them
        //   3. WHAT WAS FOUND   posture, severity split, remediation timing
        //   4. WHAT IT IS MADE OF   composition, with each part's own facts and counts
        //   5. WHAT IS PLANNED  assessment requests
        //   6. WHAT IS SUGGESTED    the AI ledger, last because nothing in it is authoritative
        //
        // The previous order led with counts, which presumes the counts mean something. An
        // application with two open findings and no penetration test ever is not a better-looking
        // application than one with twenty and a full assessment history, and leading with the
        // number says it is.
        body.append("<div class=\"grid grid-2 mb-6\">")
                .append(profileCard(messages, app, appAttributes))
                .append(endpointCard(messages, related))
                .append("</div>");

        // Immediately after coverage and before any finding count, for the reason stated above: a
        // whole-application review is the broadest thing that has ever LOOKED at this application, so
        // it qualifies every number below it.
        body.append(fullReviewCard(messages,
                assessments.cadence(principal, id).orElse(null),
                assessments.fullReviews(principal, id)));

        body.append(assuranceCard(messages, assurance, sbom, components.size() + 1));

        body.append(postureCards(messages, app, posture));
        body.append(severityTable(messages, posture));
        body.append(remediationCard(messages, remediation, serviceLevels));

        body.append(compositionTable(messages, id,
                principal != null && principal.holds(UPDATE), components, sbom));

        body.append("<div class=\"grid grid-2 mb-6\">")
                .append(requestCard(messages, requests))
                .append(suggestionCard(messages, suggestions))
                .append("</div>");

        Page.Context context = Page.Context.of("app.detail.title", "/applications",
                        Optional.ofNullable(principal))
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withActions(principal != null && principal.holds(UPDATE)
                        ? "<a class=\"btn\" href=\"/applications/" + Html.text(id.toString())
                                + "/edit\">" + Html.text(messages.get("app.inventory.edit")) + "</a>"
                        : "")
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("nav.applications"),
                                Optional.of("/applications")),
                        new Page.Crumb(app.name(), Optional.empty())));
        return html(Page.render(messages, context, body.toString()));
    }

    /** {@code GET /applications/new} and {@code GET /applications/{id}/edit}. */
    public Dispatcher.Response editor(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();
        String raw = request.pathVariables().get("id");
        UUID id = raw == null ? null : uuid(raw);

        InventoryService.Application existing = null;
        List<InventoryService.Related> related = List.of();
        if (id != null) {
            Optional<InventoryService.Application> found = inventory.application(principal, id);
            if (found.isEmpty()) {
                return Dispatcher.Response.notFound();
            }
            existing = found.orElseThrow();
            related = inventory.related(principal, id);
        }

        List<InventoryService.Node> owners = inventory.nodes(principal, true);
        List<InventoryService.Tier> tiers = inventory.tiers(principal);

        StringBuilder body = new StringBuilder(4096);
        if (request.query().containsKey("invalid")) {
            body.append(danger(messages.get("app.inventory.invalid")));
        }
        if (owners.isEmpty()) {
            // An application must be owned by a node whose TYPE may own assets. Saying which condition
            // failed, because "no options" in a required dropdown is a dead end a person cannot debug.
            body.append(danger(messages.get("app.inventory.noOwners")));
        }

        String action = existing == null
                ? "/applications" : "/applications/" + existing.id();
        body.append("<form method=\"post\" action=").append(Html.attribute(action)).append(">")
                .append(Forms.idempotencyField());
        if (existing != null) {
            body.append("<input type=\"hidden\" name=\"row_version\" value=")
                    .append(Html.attribute(String.valueOf(existing.rowVersion()))).append(">");
        }

        body.append("<section class=\"card mb-6\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(messages.get("app.editor.identity")))
                .append("</h2></div><div class=\"card-body\"><div class=\"form-grid\">")
                .append(Forms.field(messages, "name", "app.field.name", "text",
                        existing == null ? "" : existing.name(), true, null))
                .append(Forms.select(messages, "node", "app.field.owner",
                        owners.stream().map(n -> Map.entry(n.id().toString(),
                                n.name() + "  ·  " + n.typeCode())).toList(),
                        existing == null || existing.owningNodeId() == null
                                ? "" : existing.owningNodeId().toString()))
                .append(Forms.select(messages, "criticality", "app.field.criticality",
                        withBlank(messages.get("app.value.inherit"),
                                tiers.stream().map(t -> Map.entry(t.id().toString(), t.code()))
                                        .toList()),
                        ""))
                .append(Forms.select(messages, "exposure", "app.field.exposure",
                        withBlank(messages.get("app.value.unknown"), exposureOptions(messages)),
                        existing == null || existing.exposureDeclared() == null
                                ? "" : existing.exposureDeclared()))
                .append("</div><div class=\"form-grid mt-6\">")
                .append(Forms.field(messages, "user_base", "app.field.userBase", "text",
                        existing == null ? "" : existing.userBase(), false,
                        messages.get("app.field.userBaseHint")))
                .append(Forms.field(messages, "tags", "app.field.tags", "text",
                        existing == null ? "" : String.join(", ", existing.tags()), false,
                        messages.get("app.field.tagsHint")))
                .append("</div><div class=\"mt-6\">")
                .append(Forms.field(messages, "description", "app.field.description", "text",
                        existing == null ? "" : existing.description(), false, null))
                .append("</div></div></section>");

        body.append("<section class=\"card mb-6\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(messages.get("app.editor.technical")))
                .append("</h2><p class=\"fs-12 muted\">")
                .append(Html.text(messages.get("app.editor.technicalLede")))
                .append("</p></div><div class=\"card-body\"><div class=\"form-grid\">")
                // One input per environment the tenant declares, named `domain.<CODE>` (ADR-061).
                // Production and Staging used to be named here while the project form named
                // Production and UAT, so neither form could record what the other could.
                .append(endpointFields(messages, principal, related))
                .append(Forms.field(messages, "repository", "app.field.repository", "text",
                        repositoryOf(related), false, messages.get("app.field.repositoryHint")))
                .append("</div><div class=\"mt-6\">")
                .append(Forms.field(messages, "features", "app.field.features", "text",
                        existing == null ? "" : String.join(", ", existing.features()), false,
                        messages.get("app.field.featuresHint")))
                .append("</div></div></section>");

        body.append("<div class=\"form-actions\"><button class=\"btn btn-primary\" type=\"submit\">")
                .append(Html.text(messages.get("app.editor.save"))).append("</button>")
                .append("<a class=\"btn\" href=\"")
                .append(existing == null ? "/applications"
                        : "/applications/" + existing.id())
                .append("\">").append(Html.text(messages.get("app.editor.cancel")))
                .append("</a></div></form>");

        if (existing != null && principal != null && principal.holds(UPDATE)) {
            body.append(retireCard(messages, existing));
        }

        Page.Context context = Page.Context.of(
                        existing == null ? "app.editor.newTitle" : "app.editor.editTitle",
                        "/applications", Optional.ofNullable(principal))
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("nav.applications"),
                                Optional.of("/applications")),
                        new Page.Crumb(existing == null
                                ? messages.get("app.editor.newTitle") : existing.name(),
                                Optional.empty())));
        return html(Page.render(messages, context, body.toString()));
    }

    /** {@code POST /applications} and {@code POST /applications/{id}}. */
    public Dispatcher.Response save(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        String raw = request.pathVariables().get("id");
        UUID id = raw == null ? null : uuid(raw);
        if (raw != null && id == null) {
            return Dispatcher.Response.notFound();
        }
        if (id != null && inventory.application(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }

        String name = form.getOrDefault("name", "").strip();
        UUID node = uuid(form.get("node"));
        if (name.isEmpty() || node == null) {
            return redirect(id == null ? "/applications/new?invalid=1"
                    : "/applications/" + id + "/edit?invalid=1");
        }

        // Endpoints, by environment, from the `domain.<CODE>` fields the form rendered. Only the
        // environments the form actually submitted are touched, so an environment this page did not
        // show keeps whatever it holds.
        Map<String, List<String>> domains = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> field : form.entrySet()) {
            if (field.getKey().startsWith("domain.") && field.getKey().length() > "domain.".length()) {
                List<String> hosts = new java.util.ArrayList<>();
                for (String host : field.getValue() == null
                        ? new String[0] : field.getValue().split(",", -1)) {
                    if (!host.strip().isEmpty() && !hosts.contains(host.strip())) {
                        hosts.add(host.strip());
                    }
                }
                domains.put(field.getKey().substring("domain.".length()), List.copyOf(hosts));
            }
        }

        var draft = new InventoryService.ApplicationDraft(id, name, node,
                uuid(form.get("criticality")), form.get("exposure"), form.get("description"),
                form.get("user_base"), form.get("features"), form.get("tags"),
                domains, form.get("repository"),
                integer(form.get("row_version")));

        Optional<UUID> saved = inventory.saveApplication(principal, draft);
        if (saved.isEmpty()) {
            // A refused update is a stale row_version: somebody else edited this application between
            // the form being rendered and submitted. Reported rather than overwritten — a lost update
            // on an ownership or exposure field is a wrong answer nobody sees.
            return redirect("/applications?stale=1");
        }
        return redirect("/applications/" + saved.orElseThrow() + "?saved=1");
    }

    /** {@code POST /applications/{id}/retire}. */
    public Dispatcher.Response retire(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || inventory.application(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        boolean done = inventory.retireApplication(principal, id, form.get("reason"),
                integer(form.get("row_version")) == null ? -1 : integer(form.get("row_version")));
        return redirect(done ? "/applications?retired=1"
                : "/applications/" + id + "/edit?invalid=1");
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * The estate totals.
     *
     * <p>Two rows rather than one. The first is about the ESTATE — how many applications, how many
     * internet-facing, how many nothing has ever scored. The second is about the WORK — findings
     * found, still open, accepted, and assessment requests raised.
     *
     * <p>Kept apart because they answer to different people. A security lead reads the first to decide
     * where to look; a delivery manager reads the second to decide what to staff. Mixed into one strip
     * of eight numbers, neither is read at all.
     */
    private static String summary(Messages messages, List<InventoryService.Application> rows,
            Map<UUID, InventoryService.Posture> postures) {
        long internetFacing = rows.stream()
                .filter(a -> "INTERNET_PUBLIC".equals(a.exposureDeclared())).count();
        long unscored = rows.stream().filter(a -> a.riskValue() == null).count();
        long conflicted = rows.stream().filter(InventoryService.Application::exposureConflict).count();

        long total = 0;
        long open = 0;
        long accepted = 0;
        long requests = 0;
        for (InventoryService.Application row : rows) {
            InventoryService.Posture posture = postures.get(row.id());
            if (posture != null) {
                total += posture.findingTotal();
                open += posture.findingOpen();
                accepted += posture.findingAccepted();
            }
            requests += row.requestCount();
        }

        return "<div class=\"grid grid-kpi mb-6\">"
                + kpi(messages, "app.kpi.total", String.valueOf(rows.size()))
                + kpi(messages, "app.kpi.internetFacing", String.valueOf(internetFacing))
                // PP-1 as a headline figure. "How many of these have never been scored" is the number
                // that tells you whether the other numbers mean anything.
                + kpi(messages, "app.kpi.unscored", String.valueOf(unscored))
                + kpi(messages, "app.kpi.conflicted", String.valueOf(conflicted))
                + "</div><div class=\"grid grid-kpi mb-6\">"
                + kpi(messages, "app.kpi.findingsTotal", String.valueOf(total))
                + kpi(messages, "app.kpi.findingsOpen", String.valueOf(open))
                + kpi(messages, "app.kpi.findingsAccepted", String.valueOf(accepted))
                + kpi(messages, "app.kpi.requests", String.valueOf(requests))
                + "</div>";
    }

    private static String filterBar(Messages messages, List<InventoryService.Node> nodes,
            Map<String, String> filters, Map<String, String> attributeFilters,
            List<InventoryService.AttributeDefinition> declared, String search, String sort,
            boolean descending) {
        StringBuilder out = new StringBuilder();
        // A GET form, so a filtered view is a URL somebody can bookmark and send to a colleague — and
        // so the whole thing works with script disabled (PRD-UIX-013).
        out.append("<form class=\"card mb-6\" method=\"get\" action=\"/applications\">")
                .append("<div class=\"card-body\"><div class=\"form-grid\">")
                .append(Forms.field(messages, "q", "app.filter.search", "search", search, false,
                        messages.get("app.filter.searchHint")))
                .append(Forms.select(messages, "node", "app.filter.owner",
                        withBlank(messages.get("app.value.any"),
                                nodes.stream().map(n -> Map.entry(n.id().toString(),
                                        indent(n.depth()) + n.name() + "  ·  " + n.typeCode()))
                                        .toList()),
                        filters.getOrDefault("node", "")))
                .append(Forms.select(messages, "exposure", "app.filter.exposure",
                        withBlank(messages.get("app.value.any"), exposureOptions(messages)),
                        filters.getOrDefault("exposure", "")))
                .append(Forms.select(messages, "criticality", "app.filter.criticality",
                        withBlank(messages.get("app.value.any"), codeOptions(criticalityCodes(nodes))),
                        filters.getOrDefault("criticality", "")))
                .append(Forms.select(messages, "lifecycle", "app.filter.lifecycle",
                        withBlank(messages.get("app.value.any"), codeOptions(
                                List.of("DISCOVERED", "ACTIVE", "DEPRECATED", "RETIRED"))),
                        filters.getOrDefault("lifecycle", "")))
                // One filter per DECLARED attribute the tenant marked filterable. Generated from the
                // definition, so a tenant that starts recording a new fact gets a filter for it
                // without anybody editing this file.
                .append(attributeFilters(messages, declared, attributeFilters))
                .append(Forms.select(messages, "sort", "app.filter.sort", List.of(
                                Map.entry("name", messages.get("app.sort.name")),
                                Map.entry("criticality", messages.get("app.sort.criticality")),
                                Map.entry("exposure", messages.get("app.sort.exposure")),
                                Map.entry("score", messages.get("app.sort.score")),
                                Map.entry("findings", messages.get("app.sort.findings")),
                                Map.entry("requests", messages.get("app.sort.requests"))),
                        sort))
                .append("</div><div class=\"form-actions\">")
                .append(Forms.checkbox(messages, "desc", "app.filter.descending", descending))
                .append("<button class=\"btn btn-primary\" type=\"submit\">")
                .append(Html.text(messages.get("app.filter.apply"))).append("</button>")
                .append("<a class=\"btn\" href=\"/applications\">")
                .append(Html.text(messages.get("app.filter.clear"))).append("</a>")
                .append("</div></div></form>");
        return out.toString();
    }

    private static String table(Messages messages, List<InventoryService.Application> rows,
            Map<UUID, aspm.app.assessment.AssessmentService.Cadence> cadence) {
        if (rows.isEmpty()) {
            return StateRenderer.state(messages,
                    aspm.module.insight.domain.PresentationState.EMPTY_FILTERED,
                    Optional.of(messages.get("app.inventory.noMatch")));
        }
        StringBuilder out = new StringBuilder(8192);
        out.append("<div class=\"table-wrap\"><div class=\"table-scroll\"><table class=\"data\">")
                .append("<caption>").append(Html.text(messages.get("app.inventory.caption")))
                .append("</caption><thead><tr>")
                .append(th(messages, "app.col.name")).append(th(messages, "app.col.organization"))
                .append(th(messages, "app.col.owner"))
                .append(th(messages, "app.col.criticality")).append(th(messages, "app.col.exposure"))
                .append(th(messages, "app.col.score")).append(th(messages, "app.col.findings"))
                .append(th(messages, "app.col.requests"))
                .append(th(messages, "app.col.fullReviews"))
                .append(th(messages, "app.col.lifecycle"))
                .append("</tr></thead><tbody>");
        for (InventoryService.Application app : rows) {
            out.append("<tr><td><a class=\"link\" href=\"/applications/")
                    .append(Html.text(app.id().toString())).append("\">")
                    .append(Html.text(app.name())).append("</a>")
                    .append(app.userBase().isBlank() ? ""
                            : "<div class=\"fs-11 muted\">" + Html.text(app.userBase()) + "</div>")
                    .append("</td>");
            // Organization and owner as SEPARATE columns. The owner is the unit accountable for this
            // application; the organization is where that unit sits. One column showing a path made
            // the accountable unit the least readable part of it, and the accountable unit is the
            // one a person needs to contact.
            out.append("<td><div class=\"fs-12\">")
                    .append(app.ancestorNames().isEmpty()
                            ? muted(messages.get("app.value.none"))
                            : Html.text(String.join(" › ", app.ancestorNames())))
                    .append("</div></td>");
            out.append("<td><div class=\"fs-13\">")
                    .append(Html.text(app.owningNodeName() == null
                            ? messages.get("app.value.unowned") : app.owningNodeName()))
                    .append("</div>")
                    .append(app.owningNodeTypeCode() == null ? ""
                            : "<div class=\"fs-11 muted\">"
                                    + Html.text(app.owningNodeTypeCode()) + "</div>")
                    .append("</td>");
            out.append("<td>").append(app.criticalityCode() == null
                            ? muted(messages.get("app.value.none"))
                            : pill(criticalityTone(app.criticalityCode()), app.criticalityCode())
                                    + (app.criticalityInherited()
                                            ? " " + muted(messages.get("app.value.inherited")) : ""))
                    .append("</td>");
            out.append("<td>").append(exposureCell(messages, app)).append("</td>");
            out.append("<td>").append(scoreCell(messages, app)).append("</td>");
            out.append("<td class=\"tabular\">").append(app.findingCount()).append("</td>");
            out.append("<td class=\"tabular\">").append(app.requestCount()).append("</td>");
            out.append("<td>").append(cadenceCell(messages, cadence.get(app.id()))).append("</td>");
            out.append("<td>").append(pill(lifecycleTone(app.lifecycleState()), app.lifecycleState()))
                    .append("</td></tr>");
        }
        out.append("</tbody></table></div></div>");
        return out.toString();
    }

    /**
     * The whole-application reviews of this application, with the dates.
     *
     * <p>Two things, deliberately in one card: where the application stands against its obligation,
     * and the individual reviews that got it there. Separating them would mean a person checking
     * "is this current?" and a person checking "when did the last one actually finish?" reading two
     * cards that can disagree.
     */
    private static String fullReviewCard(Messages messages,
            aspm.app.assessment.AssessmentService.Cadence cadence,
            List<aspm.app.assessment.AssessmentService.FullReview> reviews) {
        StringBuilder out = new StringBuilder(2048);
        out.append("<section class=\"card mb-6\"><div class=\"card-header\">")
                .append("<h2 class=\"card-title\">")
                .append(Html.text(messages.get("app.review.title"))).append("</h2>")
                .append("<p class=\"fs-12 muted\">")
                .append(Html.text(messages.get("app.review.lede")))
                .append("</p></div><div class=\"card-body\">");

        if (cadence == null) {
            out.append("<p class=\"fs-13 muted\">")
                    .append(Html.text(messages.get("app.review.noPolicy"))).append("</p>");
        } else {
            out.append("<div class=\"grid grid-kpi mb-4\">")
                    .append(kpi(messages, "app.review.kpi.count",
                            String.valueOf(cadence.completed())))
                    .append(kpi(messages, "app.review.kpi.last",
                            cadence.lastAt() == null ? messages.get("app.value.never")
                                    : cadence.lastAt()))
                    .append(kpi(messages, "app.review.kpi.next",
                            cadence.nextDueAt() == null
                                    ? messages.get("app.review." + cadence.status(),
                                            cadence.status())
                                    : cadence.nextDueAt()))
                    .append(kpi(messages, "app.review.kpi.abandoned",
                            String.valueOf(cadence.abandoned())))
                    .append(kpi(messages, "app.review.kpi.interval",
                            cadence.intervalMonths() == null
                                    ? messages.get("app.review.NO_OBLIGATION")
                                    : messages.get("app.review.every",
                                            String.valueOf(cadence.intervalMonths()))))
                    .append("</div>");
        }

        if (reviews.isEmpty()) {
            // Not an empty table. "No rows" under column headings reads as a table that failed to
            // load; a sentence saying no whole-application review has been recorded is the finding.
            out.append("<p class=\"fs-13 muted\">")
                    .append(Html.text(messages.get("app.review.none"))).append("</p>");
        } else {
            out.append("<div class=\"table-scroll\"><table class=\"data\"><thead><tr>")
                    .append(th(messages, "app.review.col.request"))
                    .append(th(messages, "app.review.col.trigger"))
                    .append(th(messages, "app.review.col.started"))
                    .append(th(messages, "app.review.col.closed"))
                    .append(th(messages, "app.review.col.state"))
                    .append("</tr></thead><tbody>");
            for (var review : reviews) {
                out.append("<tr><td><a class=\"link\" href=\"/board/")
                        .append(Html.text(review.requestId().toString())).append("\">")
                        .append(Html.text(review.title() == null ? review.code() : review.title()))
                        .append("</a><div class=\"fs-11 muted mono\">")
                        .append(Html.text(review.code())).append("</div></td>")
                        .append("<td class=\"fs-12\">")
                        .append(Html.text(review.triggerLabel())).append("</td>")
                        .append("<td class=\"mono fs-11\">")
                        .append(review.startedAt() == null ? muted("—")
                                : Html.text(review.startedAt()))
                        // Marked when the date shown is the intake date rather than a recorded start.
                        // Presenting one as the other would put the clock several weeks early on
                        // every request that waited in a queue before anybody began.
                        .append(review.startedAtIsIntakeDate() && review.startedAt() != null
                                ? "<div class=\"fs-11 muted\">"
                                        + Html.text(messages.get("app.review.intakeDate")) + "</div>"
                                : "")
                        .append("</td>")
                        .append("<td class=\"mono fs-11\">")
                        .append(review.closedAt() != null ? Html.text(review.closedAt())
                                : review.abandoned()
                                        ? muted(messages.get("app.review.abandonedOn",
                                                review.abandonedAt() == null ? "—"
                                                        : review.abandonedAt()))
                                : "TERMINAL".equals(review.stateCategory())
                                        ? muted(messages.get("app.review.closedUnrecorded"))
                                        : muted(messages.get("app.review.stillOpen")))
                        .append("</td>")
                        .append("<td>")
                        .append(pill(review.abandoned() ? "warn" : review.open() ? "info" : "ok",
                                review.state()))
                        // The row stays in the history and says why it does not count. Deleting it
                        // would make the coverage number defensible and the record incomplete.
                        .append(review.abandoned()
                                ? "<div class=\"fs-11 muted\">"
                                        + Html.text(messages.get("app.review.doesNotCount"))
                                        + "</div>" : "")
                        .append("</td></tr>");
            }
            out.append("</tbody></table></div>");
        }
        return out.append("</div></section>").toString();
    }

    /**
     * The full-review cell: how many, and where the application stands against its obligation.
     *
     * <p>The count and the status are shown TOGETHER because neither is usable alone. A count of four
     * says nothing about whether one is owed now; a status of overdue says nothing about whether this
     * application has ever been reviewed at all. The two together answer the question a security lead
     * is actually asked.
     *
     * <p>{@code NEVER} is not rendered as an overdue variant. An application that has never had a full
     * review has no elapsed interval to report, and putting it in the same bucket as one that is three
     * weeks late would misstate both — see the view comment in V024 and PP-1.
     */
    private static String cadenceCell(Messages messages,
            aspm.app.assessment.AssessmentService.Cadence cadence) {
        if (cadence == null) {
            return "<span class=\"pill pill-unknown\">"
                    + Html.text(messages.get("app.value.unknown")) + "</span>";
        }
        String tone = switch (cadence.status()) {
            case "OVERDUE" -> "danger";
            case "DUE_SOON" -> "warn";
            case "NEVER" -> "high";
            case "CURRENT" -> "ok";
            default -> "unknown";
        };
        StringBuilder out = new StringBuilder();
        out.append("<span class=\"tabular fw-semibold\">")
                .append(cadence.completed()).append("</span> ")
                .append(pill(tone, messages.get("app.review." + cadence.status(), cadence.status())));
        if (cadence.inFlight() > 0) {
            out.append(" ").append(pill("info",
                    messages.get("app.review.inFlight", String.valueOf(cadence.inFlight()))));
        }
        if (cadence.abandoned() > 0) {
            // Shown, not hidden. Reviews that were raised and then cancelled do not count towards the
            // obligation, and an application where that keeps happening looks identical to one nobody
            // ever scheduled unless the count is on the page.
            out.append(" ").append(pill("warn",
                    messages.get("app.review.abandoned", String.valueOf(cadence.abandoned()))));
        }
        if (cadence.nextDueAt() != null) {
            out.append("<div class=\"fs-11 muted mono\">")
                    .append(Html.text(messages.get("app.review.next", cadence.nextDueAt())))
                    .append("</div>");
        } else if (cadence.intervalMonths() != null) {
            out.append("<div class=\"fs-11 muted\">")
                    .append(Html.text(messages.get("app.review.every",
                            String.valueOf(cadence.intervalMonths()))))
                    .append("</div>");
        }
        return out.toString();
    }

    /**
     * The score cell.
     *
     * <p>{@code PRD-UIX-022}: an unmeasured value has <b>no numeral form</b>. An application nothing has
     * scored shows the word, not a zero and not a dash that reads as zero — and a scored one shows its
     * coverage beside it, because a score computed over one scanner's output and a score computed over
     * full coverage are different claims wearing the same number.
     */
    private static String scoreCell(Messages messages, InventoryService.Application app) {
        if (app.riskValue() == null) {
            return "<span class=\"pill pill-unknown\">"
                    + Html.text(messages.get("app.value.unscored")) + "</span>";
        }
        return "<span class=\"tabular fw-semibold\">"
                + app.riskValue() + "</span> "
                + pill(bandTone(app.riskBand()), app.riskBand())
                + "<div class=\"fs-11 muted\">"
                + Html.text(messages.get("app.value.coverage", String.valueOf(app.riskCoverage())))
                + "</div>";
    }

    private static String scoreCard(Messages messages, InventoryService.Application app) {
        if (app.riskValue() == null) {
            return "<div class=\"card kpi\"><span class=\"kpi-label\">"
                    + Html.text(messages.get("app.detail.score")) + "</span>"
                    + "<span class=\"pill pill-unknown self-start\">"
                    + Html.text(messages.get("app.value.unscored")) + "</span>"
                    + "<span class=\"kpi-qualifier\">"
                    + Html.text(messages.get("app.detail.scoreAbsent")) + "</span></div>";
        }
        return "<div class=\"card kpi\"><span class=\"kpi-label\">"
                + Html.text(messages.get("app.detail.score")) + "</span>"
                + "<span class=\"kpi-value\">" + app.riskValue() + "</span>"
                + "<span class=\"kpi-qualifier\">"
                + Html.text(messages.get("app.value.coverage", String.valueOf(app.riskCoverage())))
                + "</span></div>";
    }

    /**
     * Exposure, with the conflict shown rather than resolved.
     *
     * <p>The schema records a declared level and an observed one and flags a conflict between them. A
     * cell that showed only the declared value would present "internal only" for a host something has
     * observed on the internet — which is the single most consequential wrong answer this inventory can
     * give.
     */
    private static String exposureCell(Messages messages, InventoryService.Application app) {
        if (app.exposureDeclared() == null && app.exposureObserved() == null) {
            return muted(messages.get("app.value.unknown"));
        }
        String declared = app.exposureDeclared() == null
                ? muted(messages.get("app.value.undeclared"))
                : pill(exposureTone(app.exposureDeclared()), app.exposureDeclared());
        if (!app.exposureConflict()) {
            return declared;
        }
        return declared + " " + pill("danger", messages.get("app.value.conflict"))
                + "<div class=\"fs-11 muted\">"
                + Html.text(messages.get("app.value.observed", String.valueOf(app.exposureObserved())))
                + "</div>";
    }

    private static String profileCard(Messages messages, InventoryService.Application app,
            List<InventoryService.AttributeDefinition> definitions) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(messages.get("app.detail.profile")))
                .append("</h2></div><div class=\"card-body col gap-3\">")
                .append(definition(messages.get("app.field.name"), app.name()))
                .append(definition(messages.get("app.field.owner"),
                        app.owningNodeName() == null
                                ? messages.get("app.value.unowned")
                                : String.join(" › ", concat(app.ancestorNames(), app.owningNodeName()))))
                .append(definition(messages.get("app.field.exposure"),
                        app.exposureDeclared() == null
                                ? messages.get("app.value.undeclared") : app.exposureDeclared()))
                .append(definition(messages.get("app.field.userBase"),
                        app.userBase().isBlank() ? messages.get("app.value.none") : app.userBase()))
                .append(definition(messages.get("app.field.identityKey"), app.identityKey()));
        // The tenant's DECLARED attributes, rendered from the definition rather than from a list in
        // this file. A tenant that adds "regulatory owner" gets a row here without a code change.
        for (InventoryService.AttributeDefinition definition : definitions) {
            if ("description".equals(definition.key()) || "user_base".equals(definition.key())) {
                continue;
            }
            String value = app.attributes().getOrDefault(definition.key(), "");
            out.append(definition(definition.label(),
                    value.isBlank() ? messages.get("app.value.none") : value));
        }
        if (!app.description().isBlank()) {
            out.append("<p class=\"fs-13\">").append(Html.text(app.description())).append("</p>");
        }
        if (!app.tags().isEmpty()) {
            out.append("<div class=\"row gap-1 wrap\">");
            for (String tag : app.tags()) {
                out.append(pill("info", tag));
            }
            out.append("</div>");
        }
        return out.append("</div></section>").toString();
    }

    private static String endpointCard(Messages messages, List<InventoryService.Related> related) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(messages.get("app.detail.technical")))
                .append("</h2></div><div class=\"card-body col gap-3\">");
        if (related.isEmpty()) {
            out.append("<p class=\"fs-13 muted\">")
                    .append(Html.text(messages.get("app.detail.noTechnical"))).append("</p>");
        }
        for (InventoryService.Related item : related) {
            out.append("<div class=\"row between gap-3\"><div class=\"col\">")
                    .append("<span class=\"fs-13 mono\">").append(Html.text(item.name()))
                    .append("</span><span class=\"fs-11 muted\">")
                    .append(Html.text(item.typeCode()))
                    .append(item.environment() == null ? ""
                            : " · " + Html.text(item.environment()))
                    .append("</span></div>")
                    .append(item.exposure() == null ? ""
                            : pill(exposureTone(item.exposure()), item.exposure()))
                    .append("</div>");
        }
        return out.append("</div></section>").toString();
    }



    /**
     * A select per declared, filterable attribute.
     *
     * <p>Select-typed attributes offer their permitted values; BOOLEAN attributes offer yes and no.
     * Free-text attributes get nothing: a dropdown of every value anybody ever typed stops being
     * usable at the second page of applications and teaches people the filters do not work.
     *
     * <p>"Third-party supplied" is a boolean and it is one of the most useful filters on this page —
     * it answers "which of these can we not patch ourselves", which is the first question after a
     * critical advisory. Leaving booleans out because they are not selects would have dropped it.
     */
    private static String attributeFilters(Messages messages,
            List<InventoryService.AttributeDefinition> declared, Map<String, String> selected) {
        StringBuilder out = new StringBuilder();
        for (InventoryService.AttributeDefinition definition : declared) {
            if (!definition.filterable()) {
                continue;
            }
            List<Map.Entry<String, String>> options;
            if (definition.isSelect()) {
                options = definition.permittedValues().stream().map(v -> Map.entry(v, v)).toList();
            } else if ("BOOLEAN".equals(definition.dataType())) {
                options = List.of(Map.entry("true", messages.get("app.value.yes")),
                        Map.entry("false", messages.get("app.value.no")));
            } else {
                continue;
            }
            out.append(Forms.select(messages, "attr_" + definition.key(), null,
                    withBlank(messages.get("app.value.any"), options),
                    selected.getOrDefault(definition.key(), ""), definition.label()));
        }
        return out.toString();
    }

    /**
     * {@code GET /applications/{id}/components/new} and
     * {@code GET /applications/{id}/components/{componentId}}.
     *
     * <p>The form is generated from the tenant's declared attributes for the chosen asset type. There
     * is no field list in this method, which is the point: a tenant that declares "runtime platform"
     * on services gets the field, the dropdown of permitted values, and the purpose note beneath it,
     * with no code change.
     */
    public Dispatcher.Response componentEditor(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();
        UUID appId = uuid(request.pathVariables().get("id"));
        if (appId == null) {
            return Dispatcher.Response.notFound();
        }
        Optional<InventoryService.Application> app = inventory.application(principal, appId);
        if (app.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        UUID componentId = uuid(request.pathVariables().get("componentId"));
        InventoryService.Component existing = null;
        if (componentId != null) {
            // Reached THROUGH the application, so a component identifier from another application
            // resolves to nothing here. SEC-AUZ-017: the object is re-validated, not just the path.
            Optional<InventoryService.Component> found =
                    inventory.component(principal, appId, componentId);
            if (found.isEmpty()) {
                return Dispatcher.Response.notFound();
            }
            existing = found.orElseThrow();
        }

        String typeCode = existing != null ? existing.typeCode()
                : request.query().getOrDefault("type", "FEATURE");
        if (!List.of("FEATURE", "SERVICE").contains(typeCode)) {
            // Only the two composition types are creatable here. Domains and repositories are created
            // by the application editor from their host name, because they are identified by it.
            return Dispatcher.Response.notFound();
        }
        List<InventoryService.AttributeDefinition> definitions =
                inventory.attributeDefinitions(principal, typeCode);
        List<InventoryService.Component> parents = inventory.parentCandidates(principal, appId);
        Integer version = existing == null ? null
                : inventory.assetVersion(principal, existing.id()).orElse(null);

        StringBuilder body = new StringBuilder(4096);
        if (request.query().containsKey("invalid")) {
            body.append(danger(messages.get("app.component.invalid")));
        }
        if (request.query().containsKey("stale")) {
            body.append(danger(messages.get("app.inventory.stale")));
        }

        String action = "/applications/" + appId + "/components"
                + (existing == null ? "" : "/" + existing.id());
        body.append("<form method=\"post\" action=").append(Html.attribute(action)).append(">")
                .append(Forms.idempotencyField())
                .append("<input type=\"hidden\" name=\"type\" value=")
                .append(Html.attribute(typeCode)).append(">");
        if (version != null) {
            body.append("<input type=\"hidden\" name=\"row_version\" value=")
                    .append(Html.attribute(String.valueOf(version))).append(">");
        }

        body.append("<section class=\"card mb-6\"><div class=\"card-header\">")
                .append("<h2 class=\"card-title\">")
                .append(Html.text(messages.get("app.component.identity")))
                .append("</h2><span class=\"pill pill-info\">").append(Html.text(typeCode))
                .append("</span></div><div class=\"card-body\"><div class=\"form-grid\">")
                .append(Forms.field(messages, "name", "app.component.name", "text",
                        existing == null ? "" : existing.name(), true, null));
        if (existing == null) {
            body.append(Forms.select(messages, "parent", "app.component.parent",
                    withBlank(messages.get("app.component.parentApplication"),
                            parents.stream().map(p -> Map.entry(p.id().toString(), p.name()))
                                    .toList()),
                    ""));
        }
        body.append(Forms.select(messages, "exposure", "app.field.exposure",
                        withBlank(messages.get("app.value.unknown"), exposureOptions(messages)),
                        existing == null || existing.exposure() == null ? "" : existing.exposure()))
                .append("</div></div></section>");

        body.append("<section class=\"card mb-6\"><div class=\"card-header\">")
                .append("<h2 class=\"card-title\">")
                .append(Html.text(messages.get("app.component.security")))
                .append("</h2><p class=\"fs-12 muted\">")
                .append(Html.text(messages.get("app.component.securityLede")))
                .append("</p></div><div class=\"card-body col gap-5\">")
                .append(attributeFields(messages, definitions,
                        existing == null ? Map.of() : existing.attributes()))
                .append("</div></section>");

        body.append("<div class=\"form-actions\"><button class=\"btn btn-primary\" type=\"submit\">")
                .append(Html.text(messages.get("app.editor.save"))).append("</button>")
                .append("<a class=\"btn\" href=\"/applications/").append(Html.text(appId.toString()))
                .append("\">").append(Html.text(messages.get("app.editor.cancel")))
                .append("</a></div></form>");

        if (existing != null) {
            body.append("<section class=\"card mt-6\"><div class=\"card-header\">")
                    .append("<h2 class=\"card-title\">")
                    .append(Html.text(messages.get("app.component.detachTitle")))
                    .append("</h2></div><div class=\"card-body\"><p class=\"fs-13\">")
                    .append(Html.text(messages.get("app.component.detachLede")))
                    .append("</p><form method=\"post\" action=\"/applications/")
                    .append(Html.text(appId.toString())).append("/components/")
                    .append(Html.text(existing.id().toString())).append("/detach\">")
                    .append(Forms.idempotencyField())
                    .append("<div class=\"form-actions\">")
                    .append("<button class=\"btn btn-sm btn-danger\" type=\"submit\">")
                    .append(Html.text(messages.get("app.component.detachAction")))
                    .append("</button></div></form></div></section>");
        }

        Page.Context context = Page.Context.of(
                        existing == null ? "app.component.newTitle" : "app.component.editTitle",
                        "/applications", Optional.ofNullable(principal))
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("nav.applications"),
                                Optional.of("/applications")),
                        new Page.Crumb(app.orElseThrow().name(),
                                Optional.of("/applications/" + appId)),
                        new Page.Crumb(existing == null
                                ? messages.get("app.component.newTitle") : existing.name(),
                                Optional.empty())));
        return html(Page.render(messages, context, body.toString()));
    }

    /** One input per declared attribute, with the purpose note the definition carries. */
    private static String attributeFields(Messages messages,
            List<InventoryService.AttributeDefinition> definitions, Map<String, String> current) {
        StringBuilder out = new StringBuilder();
        for (InventoryService.AttributeDefinition definition : definitions) {
            String value = current.getOrDefault(definition.key(), "");
            out.append("<div class=\"col gap-1\">");
            switch (definition.dataType()) {
                case "SINGLE_SELECT" -> out.append(Forms.select(messages, definition.key(), null,
                        withBlank(messages.get("app.value.unknown"),
                                definition.permittedValues().stream().map(v -> Map.entry(v, v))
                                        .toList()),
                        value, definition.label()));
                case "MULTI_SELECT" -> {
                    out.append("<span class=\"fs-12 muted\">")
                            .append(Html.text(definition.label())).append("</span>")
                            .append("<div class=\"row gap-3 wrap\">");
                    for (String option : definition.permittedValues()) {
                        // Membership, not substring. A substring test would tick "JAVA" for a service
                        // whose stack is "JAVASCRIPT", and tick nothing at all once the value stopped
                        // carrying quotes — which is exactly what happened.
                        boolean checked = InventoryService.multiValueOf(value).contains(option);
                        out.append("<label class=\"row gap-1 items-center\">")
                                .append("<input type=\"checkbox\" name=")
                                .append(Html.attribute(definition.key()))
                                .append(" value=").append(Html.attribute(option))
                                .append(checked ? " checked" : "").append(">")
                                .append("<span class=\"fs-12\">").append(Html.text(option))
                                .append("</span></label>");
                    }
                    out.append("</div>");
                }
                case "BOOLEAN" -> out.append(Forms.checkboxLabelled(messages, definition.key(),
                        definition.label(), "true".equalsIgnoreCase(value)));
                default -> out.append(Forms.field(messages, definition.key(), null, "text", value,
                        definition.required(), null, definition.label()));
            }
            if (definition.purpose() != null && !definition.purpose().isBlank()) {
                // The security question the field answers, beside the field. A field whose purpose
                // nobody can state is a field people fill in wrongly and then filter on.
                out.append("<span class=\"fs-11 muted\">")
                        .append(Html.text(definition.purpose())).append("</span>");
            }
            out.append("</div>");
        }
        return out.toString();
    }

    /** {@code POST /applications/{id}/components} and {@code POST .../{componentId}}. */
    public Dispatcher.Response componentSave(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID appId = uuid(request.pathVariables().get("id"));
        if (appId == null) {
            return Dispatcher.Response.notFound();
        }
        Optional<InventoryService.Application> app = inventory.application(principal, appId);
        if (app.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        UUID componentId = uuid(request.pathVariables().get("componentId"));
        if (componentId != null && inventory.component(principal, appId, componentId).isEmpty()) {
            return Dispatcher.Response.notFound();
        }

        String raw = request.rawForm().orElse("");
        Map<String, String> form = AccountPages.parseForm(raw);
        String typeCode = form.getOrDefault("type", "FEATURE");
        if (!List.of("FEATURE", "SERVICE").contains(typeCode)) {
            return Dispatcher.Response.notFound();
        }
        String name = form.getOrDefault("name", "").strip();
        if (name.isEmpty()) {
            return redirect(editorPath(appId, componentId, typeCode) + "&invalid=1");
        }

        List<InventoryService.AttributeDefinition> definitions =
                inventory.attributeDefinitions(principal, typeCode);
        Map<String, List<String>> multi = new LinkedHashMap<>();
        for (InventoryService.AttributeDefinition definition : definitions) {
            if ("MULTI_SELECT".equals(definition.dataType())) {
                multi.put(definition.key(), List.copyOf(multiValues(raw, definition.key())));
            }
        }
        Map<String, Object> attributes =
                inventory.attributeDocument(definitions, form, multi);

        UUID parent = componentId == null
                ? (uuid(form.get("parent")) == null ? appId : uuid(form.get("parent")))
                : null;
        Optional<UUID> saved = inventory.saveComponent(principal, componentId, typeCode, name,
                parent, app.orElseThrow().owningNodeId(), form.get("exposure"), attributes,
                integer(form.get("row_version")));
        if (saved.isEmpty()) {
            return redirect(editorPath(appId, componentId, typeCode) + "&stale=1");
        }
        return redirect("/applications/" + appId + "?saved=1");
    }

    /** {@code POST /applications/{id}/components/{componentId}/detach}. */
    public Dispatcher.Response componentDetach(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID appId = uuid(request.pathVariables().get("id"));
        UUID componentId = uuid(request.pathVariables().get("componentId"));
        if (appId == null || componentId == null
                || inventory.application(principal, appId).isEmpty()
                || inventory.component(principal, appId, componentId).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        // Detached from every current parent inside this application: a component reached through two
        // features is attached twice, and closing one edge would leave it on the page looking as
        // though the action failed.
        for (InventoryService.Component parent : inventory.parentCandidates(principal, appId)) {
            inventory.detachComponent(principal, parent.id(), componentId);
        }
        inventory.detachComponent(principal, appId, componentId);
        return redirect("/applications/" + appId + "?saved=1");
    }

    private static String editorPath(UUID appId, UUID componentId, String typeCode) {
        return componentId == null
                ? "/applications/" + appId + "/components/new?type=" + typeCode
                : "/applications/" + appId + "/components/" + componentId + "?x=1";
    }

    /** Every value submitted under one name. A checkbox group is genuinely multi-valued. */
    private static java.util.Set<String> multiValues(String body, String name) {
        java.util.Set<String> values = new java.util.LinkedHashSet<>();
        for (String pair : body.split("&", -1)) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(pair.substring(0, equals),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (name.equals(key)) {
                values.add(java.net.URLDecoder.decode(pair.substring(equals + 1).replace('+', ' '),
                        java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    /**
     * The headline posture cards.
     *
     * <p>Every count is over the application AND everything it contains. A vulnerability in a service
     * belongs to the application that service is part of, and a card reporting only the application
     * row would show zero for an application with eight open findings underneath it — the most
     * dangerous wrong answer this product can give.
     */
    private static String postureCards(Messages messages, InventoryService.Application app,
            InventoryService.Posture posture) {
        if (posture == null || !posture.everAssessed()) {
            // PP-1. Nothing has been run against this application, so there is no clean bill of
            // health to give and no numeral that would not be an invention.
            return "<div class=\"banner banner-danger\" role=\"status\"><div><strong>"
                    + Html.text(messages.get("app.posture.neverAssessedTitle")) + "</strong> "
                    + Html.text(messages.get("app.posture.neverAssessedBody")) + "</div></div>";
        }
        return "<div class=\"grid grid-kpi mb-6\">"
                + scoreCard(messages, app)
                + kpi(messages, "app.posture.openFindings",
                        posture.findingOpen() + " / " + posture.findingTotal())
                + kpi(messages, "app.posture.scaOpen",
                        posture.scaOpen() + " / " + posture.scaTotal())
                + kpi(messages, "app.posture.accepted", String.valueOf(posture.findingAccepted()))
                + kpi(messages, "app.posture.components", String.valueOf(posture.componentCount()))
                + "</div>";
    }

    /**
     * Findings by severity, split into all-time and still-open, and again for SCA.
     *
     * <p>Both columns, because "we found twelve criticals" and "three criticals are still open" are
     * different claims and an inventory that shows only one of them cannot answer whether anything is
     * improving. Accepted risk is counted as closed and shown separately — counting it as open too
     * would double the figure an executive reads first.
     */
    private static String severityTable(Messages messages, InventoryService.Posture posture) {
        if (posture == null || !posture.everAssessed()) {
            return "";
        }
        record Row(String code, String tone, long total, long open, long scaOpen) {
        }
        List<Row> rows = List.of(
                new Row("CRITICAL", "critical", posture.criticalTotal(), posture.criticalOpen(),
                        posture.scaCriticalOpen()),
                new Row("HIGH", "high", posture.highTotal(), posture.highOpen(),
                        posture.scaHighOpen()),
                new Row("MEDIUM", "medium", posture.mediumTotal(), posture.mediumOpen(),
                        posture.scaMediumOpen()),
                new Row("LOW", "low", posture.lowTotal(), posture.lowOpen(), 0));

        StringBuilder out = new StringBuilder();
        out.append("<div class=\"table-wrap mb-6\"><table class=\"data\"><caption>")
                .append(Html.text(messages.get("app.posture.caption")))
                .append("</caption><thead><tr>")
                .append(th(messages, "app.posture.col.severity"))
                .append(th(messages, "app.posture.col.found"))
                .append(th(messages, "app.posture.col.open"))
                .append(th(messages, "app.posture.col.scaOpen"))
                .append("</tr></thead><tbody>");
        for (Row row : rows) {
            out.append("<tr><td>").append(pill(row.tone(), row.code())).append("</td>")
                    .append("<td class=\"tabular\">").append(row.total()).append("</td>")
                    .append("<td class=\"tabular\">").append(row.open()).append("</td>")
                    .append("<td class=\"tabular\">")
                    .append("LOW".equals(row.code()) ? muted("—") : String.valueOf(row.scaOpen()))
                    .append("</td></tr>");
        }
        out.append("</tbody></table></div>");
        return out.toString();
    }


    /**
     * Assurance coverage — what has looked at this application, and what never has.
     *
     * <p>Every product-fixed finding class is listed, including the ones with no evidence. That is
     * the panel's reason to exist: a list of what HAS run tells a reader what was found, and only a
     * list that includes what has NOT run tells them nothing has ever penetration tested this.
     *
     * <p>Product principle 1 applied to the estate rather than to a single figure. The platform
     * already refuses to print a numeral for an unscored application; this refuses to let an
     * application with five clean scanners and no dynamic testing look assessed.
     */
    private static String assuranceCard(Messages messages, List<InventoryService.Assurance> assurance,
            Map<UUID, InventoryService.SbomState> sbom, int partCount) {
        long never = assurance.stream().filter(InventoryService.Assurance::never).count();
        long neverSbom = sbom.values().stream()
                .filter(s -> "NEVER_SUBMITTED".equals(s.freshness())).count();
        long staleSbom = sbom.values().stream().filter(s -> "STALE".equals(s.freshness())).count();

        StringBuilder out = new StringBuilder(4096);
        out.append("<section class=\"card mb-6\"><div class=\"card-header\">")
                .append("<h2 class=\"card-title\">")
                .append(Html.text(messages.get("app.assurance.title"))).append("</h2>")
                .append(never == 0 ? pill("ok", messages.get("app.assurance.allCovered"))
                        : pill("warn", messages.get("app.assurance.gaps", never)))
                .append("<p class=\"fs-12 muted\">")
                .append(Html.text(messages.get("app.assurance.lede")))
                .append("</p></div><div class=\"card-body\">")
                .append("<div class=\"table-scroll\"><table class=\"data\"><thead><tr>")
                .append(th(messages, "app.assurance.col.activity"))
                .append(th(messages, "app.assurance.col.last"))
                .append(th(messages, "app.assurance.col.parts"))
                .append(th(messages, "app.assurance.col.open"))
                .append("</tr></thead><tbody>");

        for (InventoryService.Assurance row : assurance) {
            out.append("<tr><td>")
                    .append(Html.text(messages.get("app.assurance.class." + row.findingClass(),
                            row.findingClass())))
                    .append("<div class=\"fs-11 muted\">")
                    .append(Html.text(row.findingClass())).append("</div></td>");
            if (row.never()) {
                // Not a dash and not a zero. "Never" is the finding.
                out.append("<td colspan=\"3\">")
                        .append(pill("danger", messages.get("app.assurance.never")))
                        .append(" <span class=\"fs-12 muted\">")
                        .append(Html.text(messages.get("app.assurance.neverDetail")))
                        .append("</span></td>");
            } else {
                out.append("<td class=\"mono fs-11\">")
                        .append(Html.text(row.lastEvidenceAt().substring(0,
                                Math.min(10, row.lastEvidenceAt().length()))))
                        .append("</td>")
                        // Covered parts out of total: one service scanned out of five is not the same
                        // as the application being scanned, and a tick would say it was.
                        .append("<td class=\"tabular\">").append(row.coveredParts())
                        .append(" / ").append(partCount).append("</td>")
                        .append("<td class=\"tabular\">").append(row.openCount()).append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</tbody></table></div>");

        // SBOM, in the same panel, because "has a dependency scanner run" and "is there a current
        // bill of materials" are the same question asked two ways, and answering only the first
        // reports coverage the second contradicts.
        out.append("<div class=\"row gap-4 wrap mt-4\">")
                .append("<span class=\"fs-13\">")
                .append(Html.text(messages.get("app.assurance.sbom"))).append("</span>")
                .append(neverSbom > 0
                        ? pill("danger", messages.get("app.assurance.sbomNever", neverSbom))
                        : pill("ok", messages.get("app.assurance.sbomAll")))
                .append(staleSbom > 0
                        ? pill("warn", messages.get("app.assurance.sbomStale", staleSbom)) : "")
                .append("</div></div></section>");
        return out.toString();
    }

    /**
     * Remediation timing, and the absence of a service level policy stated rather than omitted.
     *
     * <p>A page with no overdue panel reads as a page with nothing overdue. There is no service level
     * policy configured in this deployment, so nothing has a deadline and nothing can be overdue —
     * which is a true statement that has to be made out loud, because its silent version is a
     * reassuring one.
     */
    private static String remediationCard(Messages messages,
            InventoryService.Remediation remediation, boolean serviceLevels) {
        StringBuilder out = new StringBuilder();
        out.append("<div class=\"grid grid-kpi mb-6\">");
        if (remediation != null && remediation.meanDaysToClose() != null) {
            out.append(kpi(messages, "app.remediation.mean",
                    messages.get("app.remediation.days", remediation.meanDaysToClose())));
        } else {
            // Nothing has been closed, so there is no average. A zero here would read as "fixed
            // immediately", which is the opposite of the truth.
            out.append(unmeasured(messages, "app.remediation.mean", "app.remediation.noneClosed"));
        }
        if (remediation != null && remediation.openOldestDays() != null) {
            out.append(kpi(messages, "app.remediation.oldest",
                    messages.get("app.remediation.days", remediation.openOldestDays())));
        } else {
            out.append(unmeasured(messages, "app.remediation.oldest", "app.remediation.noneOpen"));
        }
        out.append(kpi(messages, "app.remediation.over90",
                String.valueOf(remediation == null ? 0 : remediation.openOver90Days())));
        if (serviceLevels) {
            out.append(kpi(messages, "app.remediation.overdue",
                    messages.get("app.remediation.seeQueue")));
        } else {
            out.append(unmeasured(messages, "app.remediation.overdue", "app.remediation.noPolicy"));
        }
        return out.append("</div>").toString();
    }

    /** A KPI with no numeral, because the thing it names has not been measured. PRD-UIX-022. */
    private static String unmeasured(Messages messages, String labelKey, String reasonKey) {
        return "<div class=\"card kpi\"><span class=\"kpi-label\">"
                + Html.text(messages.get(labelKey)) + "</span>"
                + "<span class=\"pill pill-unknown self-start\">"
                + Html.text(messages.get("app.value.unmeasured")) + "</span>"
                + "<span class=\"kpi-qualifier\">" + Html.text(messages.get(reasonKey))
                + "</span></div>";
    }

    /**
     * The composition table: every feature, service, repository and domain, with the security facts
     * each one carries and its own open-finding counts.
     *
     * <p>The attribute columns are DECLARED, not coded. They come from
     * {@code asset_attribute_definition}, so a tenant that starts recording "runtime platform" gets a
     * column without anybody editing this file — which is what stops an ASPM tool from fitting only
     * the organization it was written for.
     *
     * <p>One row per asset even where the graph reaches it twice: a service contained by two features
     * is one service, and listing it twice would double every count a reader adds up by eye. The
     * paths column shows that it belongs to both.
     */
    private static String compositionTable(Messages messages, UUID appId, boolean mayWrite,
            List<InventoryService.Component> components,
            Map<UUID, InventoryService.SbomState> sbom) {
        String actions = !mayWrite ? "" :
                "<div class=\"form-actions\">"
                + "<a class=\"btn btn-sm btn-primary\" href=\"/applications/" + appId
                + "/components/new?type=FEATURE\">"
                + Html.text(messages.get("app.composition.addFeature")) + "</a>"
                + "<a class=\"btn btn-sm\" href=\"/applications/" + appId
                + "/components/new?type=SERVICE\">"
                + Html.text(messages.get("app.composition.addService")) + "</a></div>";
        if (components.isEmpty()) {
            return "<section class=\"card mb-6\"><div class=\"card-header\">"
                    + "<h2 class=\"card-title\">"
                    + Html.text(messages.get("app.composition.title")) + "</h2></div>"
                    + "<div class=\"card-body\"><p class=\"fs-13 muted\">"
                    + Html.text(messages.get("app.composition.empty")) + "</p>"
                    + actions + "</div></section>";
        }
        // The union of attribute keys actually present, so the table has no column of dashes.
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (String preferred : List.of("tech_stack", "authentication", "data_classification",
                "runtime_platform", "internet_entrypoint")) {
            for (InventoryService.Component component : components) {
                String value = component.attributes().get(preferred);
                if (value != null && !value.isBlank()) {
                    keys.add(preferred);
                    break;
                }
            }
        }

        StringBuilder out = new StringBuilder(8192);
        out.append("<div class=\"table-wrap mb-6\"><div class=\"table-scroll\">")
                .append("<table class=\"data\"><caption>")
                .append(Html.text(messages.get("app.composition.title")))
                .append("</caption><thead><tr>")
                .append(th(messages, "app.composition.col.part"))
                .append(th(messages, "app.composition.col.type"));
        for (String key : keys) {
            out.append("<th scope=\"col\">")
                    .append(Html.text(messages.get("app.attr." + key, key))).append("</th>");
        }
        out.append(th(messages, "app.composition.col.exposure"))
                .append(th(messages, "app.composition.col.open"))
                .append(th(messages, "app.composition.col.sca"))
                .append(th(messages, "app.composition.col.sbom"))
                .append("</tr></thead><tbody>");

        for (InventoryService.Component component : components) {
            out.append("<tr><td>")
                    .append("<span class=\"fs-11 muted\" aria-hidden=\"true\">")
                    .append("　".repeat(Math.max(0, Math.min(component.depth() - 1, 5))))
                    .append("</span>")
                    // Only the composition types are editable here. A domain is identified by its host
                    // name and is edited from the application form; a repository likewise.
                    .append(mayWrite && List.of("FEATURE", "SERVICE").contains(component.typeCode())
                            ? "<a class=\"link fs-13\" href=\"/applications/" + appId
                                    + "/components/" + component.id() + "\">"
                                    + Html.text(component.name()) + "</a>"
                            : "<span class=\"fs-13\">" + Html.text(component.name()) + "</span>")
                    .append(component.path().size() > 1
                            ? "<div class=\"fs-11 muted\">"
                                    + Html.text(String.join(" / ", component.path())) + "</div>"
                            : "")
                    .append("</td>")
                    .append("<td>").append(pill(typeTone(component.typeCode()),
                            component.typeCode())).append("</td>");
            for (String key : keys) {
                String value = component.attributes().getOrDefault(key, "");
                out.append("<td class=\"fs-12\">")
                        .append(value.isBlank() ? muted("—") : Html.text(value))
                        .append("</td>");
            }
            out.append("<td>").append(component.exposure() == null
                            ? muted("—") : pill(exposureTone(component.exposure()),
                                    component.exposure()))
                    .append("</td>")
                    // Open over total, so a part with nine fixed and one open does not read the same
                    // as a part nobody has ever looked at.
                    .append("<td class=\"tabular\">")
                    .append(component.findingTotal() == 0
                            ? muted(messages.get("app.value.noneFound"))
                            : component.findingOpen() + " / " + component.findingTotal())
                    .append("</td>")
                    .append("<td class=\"tabular\">")
                    .append(component.scaOpen() == 0 ? muted("—")
                            : String.valueOf(component.scaOpen()))
                    .append("</td>")
                    // NEVER_SUBMITTED is a value, not an absent row (PRD-SBM-056). A blank cell here
                    // would read as "not applicable" for a service that simply never sent one.
                    .append("<td>").append(sbomCell(messages, sbom.get(component.id())))
                    .append("</td></tr>");
        }
        out.append("</tbody></table></div>").append(actions).append("</div>");
        return out.toString();
    }

    /**
     * The AI suggestion panel. ADR-005, ADR-044.
     *
     * <p>It reads a ledger nothing writes to yet, and says so. <b>It does not compute anything.</b>
     * "The weaknesses this team keeps repeating" is a real and valuable analysis and it is exactly
     * the kind of claim that must not be fabricated by a page: a list assembled here from a
     * {@code GROUP BY} would be presented with the authority of an analysis while being a frequency
     * count over whatever happens to be in the database, including duplicates the deduplication
     * pipeline has not merged and findings three tools reported once each.
     *
     * <p>ADR-005 requires AI output to land in a ledger and be promoted by an audited human action;
     * ADR-044 defers the capability. So the ledger exists with its grounding and promotion
     * constraints already enforced, and this panel renders whatever is in it — today, nothing.
     */
    private static String suggestionCard(Messages messages,
            List<InventoryService.Suggestion> suggestions) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(messages.get("app.ai.title")))
                .append("</h2><span class=\"pill pill-info\">")
                .append(Html.text(messages.get("app.ai.badge")))
                .append("</span></div><div class=\"card-body col gap-3\">");
        if (suggestions.isEmpty()) {
            out.append("<p class=\"fs-13\">")
                    .append(Html.text(messages.get("app.ai.emptyTitle"))).append("</p>")
                    .append("<p class=\"fs-12 muted\">")
                    .append(Html.text(messages.get("app.ai.emptyBody"))).append("</p>");
        }
        for (InventoryService.Suggestion suggestion : suggestions) {
            out.append("<div class=\"row between gap-3\"><div class=\"col\">")
                    .append("<span class=\"fs-13\">").append(Html.text(suggestion.kind()))
                    .append("</span><span class=\"fs-11 muted\">")
                    .append(Html.text(messages.get("app.ai.grounding",
                            suggestion.groundingCount(), suggestion.modelIdentity())))
                    .append("</span></div>")
                    .append(pill("warn", messages.get("app.ai.pending")))
                    .append("</div>");
        }
        return out.append("</div></section>").toString();
    }

    /** SBOM freshness for one part, with never and stale as first-class answers. */
    private static String sbomCell(Messages messages, InventoryService.SbomState state) {
        if (state == null) {
            // No coverage row at all. Distinct from NEVER_SUBMITTED: the platform has not even been
            // asked to track this asset's composition, which is a different gap.
            return muted(messages.get("app.sbom.untracked"));
        }
        return switch (state.freshness()) {
            case "CURRENT" -> pill("ok", messages.get("app.sbom.current"));
            case "STALE" -> pill("warn", messages.get("app.sbom.stale"));
            default -> pill("danger", messages.get("app.sbom.never"));
        };
    }

    private static String typeTone(String typeCode) {
        return switch (typeCode) {
            case "FEATURE" -> "info";
            case "SERVICE" -> "ok";
            case "DOMAIN" -> "warn";
            case "REPOSITORY" -> "medium";
            default -> "unknown";
        };
    }

    private static String requestCard(Messages messages, List<Map<String, String>> requests) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(messages.get("app.detail.requests")))
                .append("</h2></div><div class=\"card-body col gap-2\">");
        if (requests.isEmpty()) {
            // Not "no requests" — "no request NAMES this application in its scope". The distinction
            // matters while assessment_scope_asset is unpopulated: a page reporting zero would be read
            // as "never assessed" when the truth is "the link is not recorded".
            out.append("<p class=\"fs-13 muted\">")
                    .append(Html.text(messages.get("app.detail.noRequests"))).append("</p>");
        }
        for (Map<String, String> row : requests) {
            out.append("<div class=\"row between\"><a class=\"link\" href=\"/requests/")
                    .append(Html.text(row.get("id"))).append("\">")
                    .append(Html.text(row.get("code"))).append("</a>")
                    .append(pill("info", row.get("state"))).append("</div>");
        }
        return out.append("</div></section>").toString();
    }

    private static String retireCard(Messages messages, InventoryService.Application app) {
        return "<section class=\"card mt-6\"><div class=\"card-header\"><h2 class=\"card-title\">"
                + Html.text(messages.get("app.retire.title")) + "</h2></div><div class=\"card-body\">"
                + "<p class=\"fs-13\">" + Html.text(messages.get("app.retire.lede")) + "</p>"
                + "<form method=\"post\" action=\"/applications/" + Html.text(app.id().toString())
                + "/retire\">" + Forms.idempotencyField()
                + "<input type=\"hidden\" name=\"row_version\" value="
                + Html.attribute(String.valueOf(app.rowVersion())) + ">"
                + "<div class=\"form-grid\">"
                + Forms.field(messages, "reason", "app.retire.reason", "text", "", true,
                        messages.get("app.retire.reasonHint"))
                + "</div><div class=\"form-actions\">"
                + "<button class=\"btn btn-sm btn-danger\" type=\"submit\">"
                + Html.text(messages.get("app.retire.action")) + "</button></div></form></div></section>";
    }

    // ----------------------------------------------------------------------------------------------

    private Page.Context listContext(Messages messages, Principal principal) {
        return Page.Context.of("app.inventory.title", "/applications",
                        Optional.ofNullable(principal))
                .withSubtitle("app.inventory.subtitle")
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("nav.applications"), Optional.empty())));
    }

    private static List<Map.Entry<String, String>> exposureOptions(Messages messages) {
        // The four levels of ck_asset__exposure_levels. A product-fixed set, not tenant vocabulary:
        // the schema constrains them, so offering a fifth would produce a row the engine refuses.
        List<Map.Entry<String, String>> options = new ArrayList<>();
        for (String code : List.of("INTERNET_PUBLIC", "PARTNER_B2B", "INTERNAL_ONLY", "AIR_GAPPED")) {
            options.add(Map.entry(code, messages.get("app.exposure." + code, code)));
        }
        return options;
    }

    private static List<String> criticalityCodes(List<InventoryService.Node> nodes) {
        List<String> codes = new ArrayList<>();
        for (InventoryService.Node node : nodes) {
            if (node.criticalityCode() != null && !codes.contains(node.criticalityCode())) {
                codes.add(node.criticalityCode());
            }
        }
        java.util.Collections.sort(codes);
        return codes;
    }

    private static List<Map.Entry<String, String>> codeOptions(List<String> codes) {
        return codes.stream().map(c -> Map.entry(c, c)).toList();
    }

    private static List<Map.Entry<String, String>> withBlank(String label,
            List<Map.Entry<String, String>> options) {
        List<Map.Entry<String, String>> all = new ArrayList<>();
        all.add(Map.entry("", label));
        all.addAll(options);
        return all;
    }

    private static List<String> concat(List<String> ancestors, String leaf) {
        List<String> all = new ArrayList<>(ancestors);
        all.add(leaf);
        return all;
    }

    /**
     * One text input per declared environment, named {@code domain.<CODE>}.
     *
     * <p>Comma-separated, because an asset published on two hosts in one environment is a real state
     * and a single-valued input would show one of them and close the edge to the other on save.
     */
    private String endpointFields(Messages messages, Principal principal,
            List<InventoryService.Related> related) throws java.sql.SQLException {
        StringBuilder out = new StringBuilder(512);
        for (InventoryService.EndpointEnvironment environment
                : inventory.endpointEnvironments(principal)) {
            String recorded = String.join(", ", endpointsOf(related, environment.code()));
            if (!environment.active() && recorded.isEmpty()) {
                continue;
            }
            out.append(Forms.field(messages, "domain." + environment.code(), null, "text",
                    recorded, false, messages.get("app.field.domainHint"),
                    environment.label() + " domain"));
        }
        return out.toString();
    }

    private static List<String> endpointsOf(List<InventoryService.Related> related,
            String environment) {
        return related.stream()
                .filter(r -> "PUBLISHED_ON".equals(r.edgeType()) && environment.equals(r.environment()))
                .map(InventoryService.Related::name).sorted().toList();
    }

    private static String repositoryOf(List<InventoryService.Related> related) {
        return related.stream().filter(r -> "BUILDS".equals(r.edgeType()))
                .map(InventoryService.Related::name).findFirst().orElse("");
    }

    private static String indent(int depth) {
        return " ".repeat(Math.max(0, Math.min(depth, 6)) * 2);
    }

    private static String criticalityTone(String code) {
        return switch (code) {
            case "TIER1" -> "critical";
            case "TIER2" -> "high";
            case "TIER3" -> "medium";
            default -> "info";
        };
    }

    private static String exposureTone(String code) {
        return switch (code) {
            case "INTERNET_PUBLIC" -> "critical";
            case "PARTNER_B2B" -> "high";
            case "INTERNAL_ONLY" -> "low";
            default -> "info";
        };
    }

    private static String bandTone(String band) {
        if (band == null) {
            return "unknown";
        }
        return switch (band.toUpperCase(java.util.Locale.ROOT)) {
            case "CRITICAL" -> "critical";
            case "HIGH" -> "high";
            case "MEDIUM" -> "medium";
            case "LOW" -> "low";
            default -> "info";
        };
    }

    private static String lifecycleTone(String state) {
        return switch (state) {
            case "ACTIVE" -> "ok";
            case "DISCOVERED" -> "info";
            case "DEPRECATED" -> "warn";
            default -> "unknown";
        };
    }

    private static UUID uuid(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Integer integer(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : Integer.valueOf(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String kpi(Messages messages, String labelKey, String value) {
        return "<div class=\"card kpi\"><span class=\"kpi-label\">"
                + Html.text(messages.get(labelKey)) + "</span>"
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
