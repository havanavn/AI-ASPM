package aspm.app.ui;

import aspm.app.inventory.InventoryService;
import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The organization hierarchy — business units, products, projects, and whatever else a tenant defines.
 * ADR-001, ADR-010, ADR-027, {@code PRD-ORG-*}.
 *
 * <h2>This page names no level</h2>
 *
 * <p>The user calls the thing they want to manage a business unit. This page manages <b>organization
 * nodes</b>, and "Business unit" is the {@code code} of one of the tenant's node types — a row in
 * {@code org_node_type}, alongside Division, Product and Project. That is not pedantry: ADR-027 requires
 * the platform to be deployable by any conglomerate without code modification, and
 * {@code PRD-UIX-009}'s reasoning is commercial as much as technical. A product with "Business Unit"
 * compiled into its administration page cannot be sold to the second customer, whose levels are P&amp;Ls
 * or divisions or something else again.
 *
 * <p>So the type dropdown is populated from {@code org_node_type} and the tree renders whatever depth
 * the tenant configured.
 *
 * <h2>The parent cannot be changed after creation</h2>
 *
 * <p>Deliberately. DOC-05 §12 records reorganization — move, merge, split — as <b>asynchronous</b>
 * because it rewrites thousands of closure rows and cannot finish inside a request budget. A parent
 * dropdown on the edit form would either block the request or leave {@code org_closure} disagreeing
 * with the tree, and a closure table that disagrees with the tree is a wrong answer to every scope
 * question in the platform — silently, and for every caller.
 *
 * <p>Creating a node under a parent is fine: that adds closure rows for one new leaf.
 */
public final class OrganizationPages {

    /** Read the hierarchy. */
    public static final String READ = "org.node.read";
    /** Add a node. */
    public static final String CREATE = "org.node.create";
    /** Rename one, set its criticality, deprecate it. */
    public static final String UPDATE = "org.node.update";

    private final InventoryService inventory;

    public OrganizationPages(DataSource dataSource) {
        this.inventory = new InventoryService(Objects.requireNonNull(dataSource));
    }

    // ----------------------------------------------------------------------------------------------

    /** {@code GET /organization}. */
    public Dispatcher.Response list(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Principal principal = request.principal();

        List<InventoryService.Node> nodes = inventory.nodes(principal, false);
        List<InventoryService.NodeType> types = inventory.nodeTypes(principal);
        List<InventoryService.Tier> tiers = inventory.tiers(principal);
        boolean mayWrite = principal != null && principal.holds(CREATE);

        StringBuilder body = new StringBuilder(8192);
        if (request.query().containsKey("saved")) {
            body.append(notice(messages.get("org.saved")));
        }
        if (request.query().containsKey("stale")) {
            body.append(danger(messages.get("org.stale")));
        }
        if (request.query().containsKey("blocked")) {
            body.append(danger(messages.get("org.blocked")));
        }

        // A data problem worth surfacing rather than hiding: two node types whose codes differ only by
        // punctuation are two types nobody can tell apart in a picker, and assets end up split across
        // them. Detected rather than assumed — the check is on the codes actually present.
        String duplicate = nearDuplicateTypes(types);
        if (duplicate != null) {
            body.append(danger(messages.get("org.duplicateTypes", duplicate)));
        }

        body.append("<div class=\"grid grid-kpi mb-6\">")
                .append(kpi(messages, "org.kpi.nodes", String.valueOf(nodes.size())))
                .append(kpi(messages, "org.kpi.owners", String.valueOf(
                        nodes.stream().filter(InventoryService.Node::mayOwnAssets).count())))
                .append(kpi(messages, "org.kpi.assets", String.valueOf(
                        nodes.stream().mapToLong(InventoryService.Node::assetCount).sum())))
                .append(kpi(messages, "org.kpi.types", String.valueOf(types.size())))
                .append("</div>");

        if (mayWrite) {
            body.append(createCard(messages, nodes, types, tiers));
        }
        body.append(tree(messages, nodes, tiers, principal));

        Page.Context context = Page.Context.of("org.title", "/organization",
                        Optional.ofNullable(principal))
                .withSubtitle("org.subtitle")
                .withScope(InterfaceResource.scopeLabelFor(messages, principal))
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("nav.organization"), Optional.empty())));
        return html(Page.render(messages, context, body.toString()));
    }

    /** {@code POST /organization} — create a node. */
    public Dispatcher.Response create(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        String name = form.getOrDefault("name", "").strip();
        UUID typeId = uuid(form.get("type"));
        if (name.isEmpty() || typeId == null) {
            return redirect("/organization?stale=1");
        }
        UUID parentId = uuid(form.get("parent"));
        // The parent must be a node the caller can already reach. Re-validated here rather than trusted
        // from the form: a picker is a usability feature and never an authorization control (product
        // principle 4), and a caller who edits the option value must not be able to graft a node onto a
        // branch they cannot see.
        if (parentId != null && inventory.node(principal, parentId).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Optional<UUID> created = inventory.saveNode(principal, null, name, typeId, parentId,
                uuid(form.get("criticality")), null);
        return redirect(created.isPresent() ? "/organization?saved=1" : "/organization?stale=1");
    }

    /** {@code POST /organization/{id}} — rename, or set criticality. */
    public Dispatcher.Response update(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        Optional<InventoryService.Node> existing = inventory.node(principal, id);
        if (existing.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        String name = form.getOrDefault("name", "").strip();
        if (name.isEmpty()) {
            return redirect("/organization?stale=1");
        }
        Optional<UUID> saved = inventory.saveNode(principal, id, name, null, null,
                uuid(form.get("criticality")), integer(form.get("row_version")));
        return redirect(saved.isPresent() ? "/organization?saved=1" : "/organization?stale=1");
    }

    /** {@code POST /organization/{id}/deprecate}. */
    public Dispatcher.Response deprecate(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || inventory.node(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> form = AccountPages.parseForm(request.rawForm().orElse(""));
        Integer version = integer(form.get("row_version"));
        boolean done = inventory.deprecateNode(principal, id, version == null ? -1 : version);
        // Refused means the node still has live children or assets. Reported as such rather than as a
        // generic failure: deprecating a node with assets under it would leave them owned by something
        // the tree no longer offers, reachable only by a caller who already knows the identifier.
        return redirect(done ? "/organization?saved=1" : "/organization?blocked=1");
    }

    // ----------------------------------------------------------------------------------------------

    private static String createCard(Messages messages, List<InventoryService.Node> nodes,
            List<InventoryService.NodeType> types, List<InventoryService.Tier> tiers) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card mb-6\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(messages.get("org.create.title")))
                .append("</h2><p class=\"fs-12 muted\">")
                .append(Html.text(messages.get("org.create.lede")))
                .append("</p></div><div class=\"card-body\">")
                .append("<form method=\"post\" action=\"/organization\">")
                .append(Forms.idempotencyField())
                .append("<div class=\"form-grid\">")
                .append(Forms.field(messages, "name", "org.field.name", "text", "", true, null))
                .append(Forms.select(messages, "type", "org.field.type",
                        types.stream().map(t -> Map.entry(t.id().toString(),
                                t.code() + (t.mayOwnAssets()
                                        ? "  ·  " + messages.get("org.field.ownsAssets") : "")))
                                .toList(),
                        types.isEmpty() ? "" : types.get(0).id().toString()))
                .append(Forms.select(messages, "parent", "org.field.parent",
                        withBlank(messages.get("org.value.root"),
                                nodes.stream().map(n -> Map.entry(n.id().toString(),
                                        indent(n.depth()) + n.name() + "  ·  " + n.typeCode()))
                                        .toList()),
                        ""))
                .append(Forms.select(messages, "criticality", "org.field.criticality",
                        withBlank(messages.get("org.value.inherit"),
                                tiers.stream().map(t -> Map.entry(t.id().toString(), t.code()))
                                        .toList()),
                        ""))
                .append("</div><div class=\"form-actions\">")
                .append("<button class=\"btn btn-primary\" type=\"submit\">")
                .append(Html.text(messages.get("org.create.action")))
                .append("</button></div></form></div></section>");
        return out.toString();
    }

    /**
     * The hierarchy, as rows indented by depth.
     *
     * <p>Indentation rather than nested lists, so every row is one table row a filter or a screen reader
     * traverses linearly, and the depth is carried by an explicit column as well as by the indent —
     * indentation alone is a visual-only carrier of structure.
     */
    private static String tree(Messages messages, List<InventoryService.Node> nodes,
            List<InventoryService.Tier> tiers, Principal principal) {
        if (nodes.isEmpty()) {
            return StateRenderer.state(messages,
                    aspm.module.insight.domain.PresentationState.EMPTY_NO_DATA,
                    Optional.of(messages.get("org.empty")));
        }
        boolean mayWrite = principal != null && principal.holds(UPDATE);
        StringBuilder out = new StringBuilder(8192);
        out.append("<div class=\"table-wrap\"><div class=\"table-scroll\"><table class=\"data\">")
                .append("<caption>").append(Html.text(messages.get("org.caption")))
                .append("</caption><thead><tr>")
                .append(th(messages, "org.col.name")).append(th(messages, "org.col.type"))
                .append(th(messages, "org.col.parent")).append(th(messages, "org.col.criticality"))
                .append(th(messages, "org.col.assets")).append(th(messages, "org.col.children"))
                .append(th(messages, "org.col.state"))
                .append(mayWrite ? th(messages, "org.col.actions") : "")
                .append("</tr></thead><tbody>");

        for (InventoryService.Node node : nodes) {
            out.append("<tr><td>")
                    .append("<span class=\"fs-11 muted\" aria-hidden=\"true\">")
                    .append(Html.text(indent(node.depth()))).append("</span>")
                    .append("<span class=\"fs-13\">").append(Html.text(node.name())).append("</span>")
                    .append("<span class=\"visually-hidden\"> ")
                    .append(Html.text(messages.get("org.depth", node.depth())))
                    .append("</span></td>")
                    .append("<td>").append(pill(node.mayOwnAssets() ? "ok" : "info", node.typeCode()))
                    .append("</td>")
                    .append("<td class=\"fs-12 muted\">")
                    .append(Html.text(node.parentName() == null
                            ? messages.get("org.value.root") : node.parentName()))
                    .append("</td>")
                    .append("<td>").append(node.criticalityCode() == null
                            ? "<span class=\"fs-12 muted\">"
                                    + Html.text(messages.get("org.value.inherited")) + "</span>"
                            : pill("high", node.criticalityCode()))
                    .append("</td>")
                    .append("<td class=\"tabular\">").append(node.assetCount()).append("</td>")
                    .append("<td class=\"tabular\">").append(node.childCount()).append("</td>")
                    .append("<td>").append(pill("ACTIVE".equals(node.lifecycleState())
                            ? "ok" : "warn", node.lifecycleState())).append("</td>");
            if (mayWrite) {
                out.append("<td>").append(rowActions(messages, node, tiers)).append("</td>");
            }
            out.append("</tr>");
        }
        return out.append("</tbody></table></div></div>").toString();
    }

    /** Rename and criticality inline, and deprecate beside them. No parent field — see the class note. */
    private static String rowActions(Messages messages, InventoryService.Node node,
            List<InventoryService.Tier> tiers) {
        StringBuilder out = new StringBuilder();
        out.append("<details><summary class=\"btn btn-sm\">")
                .append(Html.text(messages.get("org.row.edit"))).append("</summary>")
                .append("<form method=\"post\" action=\"/organization/")
                .append(Html.text(node.id().toString()))
                .append("\" class=\"col gap-2 pt-2\">")
                .append(Forms.idempotencyField())
                .append("<input type=\"hidden\" name=\"row_version\" value=")
                .append(Html.attribute(String.valueOf(node.rowVersion()))).append(">")
                .append(Forms.field(messages, "name", "org.field.name", "text", node.name(), true,
                        null))
                .append(Forms.select(messages, "criticality", "org.field.criticality",
                        withBlank(messages.get("org.value.inherit"),
                                tiers.stream().map(t -> Map.entry(t.id().toString(), t.code()))
                                        .toList()),
                        ""))
                .append("<div class=\"form-actions\">")
                .append("<button class=\"btn btn-sm btn-primary\" type=\"submit\">")
                .append(Html.text(messages.get("org.row.save"))).append("</button></div></form>");

        if (node.assetCount() == 0 && node.childCount() == 0
                && "ACTIVE".equals(node.lifecycleState())) {
            out.append("<form method=\"post\" action=\"/organization/")
                    .append(Html.text(node.id().toString())).append("/deprecate\">")
                    .append(Forms.idempotencyField())
                    .append("<input type=\"hidden\" name=\"row_version\" value=")
                    .append(Html.attribute(String.valueOf(node.rowVersion()))).append(">")
                    .append("<button class=\"btn btn-sm btn-danger\" type=\"submit\">")
                    .append(Html.text(messages.get("org.row.deprecate")))
                    .append("</button></form>");
        } else if ("ACTIVE".equals(node.lifecycleState())) {
            // The button is absent and the reason is written, rather than the button being present and
            // the click failing. A control that is offered and then refused teaches people to click twice.
            out.append("<p class=\"fs-11 muted\">")
                    .append(Html.text(messages.get("org.row.cannotDeprecate")))
                    .append("</p>");
        }
        return out.append("</details>").toString();
    }

    /**
     * Two type codes that differ only by punctuation or case.
     *
     * <p>{@code BUSINESS_UNIT} and {@code BUSINESSUNIT} both exist in the demo tenant, one of which may
     * own assets and one of which may not. Two types nobody can tell apart in a picker is how assets end
     * up split across them, and the split is invisible until a scope query returns half a business unit.
     * Reported, never repaired automatically: merging two node types moves every asset under them.
     */
    static String nearDuplicateTypes(List<InventoryService.NodeType> types) {
        List<String> seen = new ArrayList<>();
        for (InventoryService.NodeType type : types) {
            String normalised = type.code().toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]", "");
            for (InventoryService.NodeType other : types) {
                if (other.code().equals(type.code())) {
                    continue;
                }
                String otherNormalised = other.code().toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("[^a-z0-9]", "");
                // Codes are unique per tenant, so equal codes mean the same row rather than a pair.
                if (normalised.equals(otherNormalised) && !seen.contains(normalised)) {
                    seen.add(normalised);
                    return type.code() + " / " + other.code();
                }
            }
        }
        return null;
    }

    private static List<Map.Entry<String, String>> withBlank(String label,
            List<Map.Entry<String, String>> options) {
        List<Map.Entry<String, String>> all = new ArrayList<>();
        all.add(Map.entry("", label));
        all.addAll(options);
        return all;
    }

    private static String indent(int depth) {
        return "　".repeat(Math.max(0, Math.min(depth, 8)));
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

    private static String pill(String tone, String label) {
        return "<span class=\"pill pill-" + tone + "\">" + Html.text(label) + "</span>";
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
