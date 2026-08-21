package aspm.app.ui;

import aspm.app.identity.AccountService;
import aspm.app.inventory.InventoryService;
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
 * The write side of the React interface: applications, organization nodes, and roles.
 *
 * <p>Until now every edit in {@code /app} handed the caller off to the server-rendered page at
 * {@code /ui}. That worked, and it was still wrong: a person mid-task lost their filters, their
 * scroll position and their place in a list, and came back to a page that looked like a different
 * product. The hand-off also made the two tiers drift — a field added to one editor was invisible in
 * the other.
 *
 * <h2>These endpoints do not re-implement the domain rules, and that is deliberate</h2>
 *
 * <p>Every handler here calls the same {@link InventoryService} and {@link AccountService} method the
 * server-rendered page calls. Optimistic concurrency, scope re-validation, the transaction that keeps
 * an application and its endpoints together, the refusal to deprecate a node that still owns
 * something — all of it lives below this layer and is reached identically from both tiers. A second
 * copy of any of those rules would be a copy that diverges, and the divergence would surface as one
 * interface permitting what the other refuses.
 *
 * <p>What this layer does own is the part that cannot be shared: reading JSON instead of a form body,
 * returning a status instead of a redirect, and turning a refusal into something a client can act on
 * rather than a query parameter on a redirect target.
 *
 * <h2>A stale write is reported, never retried</h2>
 *
 * <p>{@code row_version} travels to the client and back. Where the update is refused the answer is
 * {@code 409 STALE} and the interface re-reads and tells the person what happened. It does not
 * silently re-submit with a fresh version, which would turn optimistic concurrency into a mechanism
 * for losing somebody else's edit slightly later.
 *
 * <h2>Step-up is the dispatcher's, not this file's</h2>
 *
 * <p>Role writes are class E and are refused without a fresh second factor before a handler here
 * runs. Nothing below tests for it. A second gate in the client is a gate that can disagree with the
 * one that actually holds.
 */
public final class EditorApi {

    private final InventoryService inventory;
    private final AccountService accounts;
    private final aspm.app.assessment.AssessmentService assessments;
    // Built once. The finding form reads three taxonomies and the submit path writes one, so a
    // per-request instance would be two constructions on the hottest write in the product.
    private final aspm.app.resource.FindingClassifier classifier;
    private final aspm.app.inventory.ProjectQuery projects;
    private final UUID tenantId;

    public EditorApi(DataSource dataSource, UUID tenantId) {
        Objects.requireNonNull(dataSource, "a data source is required");
        this.inventory = new InventoryService(dataSource);
        this.accounts = new AccountService(dataSource);
        this.assessments = new aspm.app.assessment.AssessmentService(dataSource);
        this.classifier = new aspm.app.resource.FindingClassifier(dataSource);
        this.projects = new aspm.app.inventory.ProjectQuery(dataSource);
        this.tenantId = Objects.requireNonNull(tenantId, "a tenant is required");
    }

    // ==============================================================================================
    // Applications
    // ==============================================================================================

    /**
     * {@code GET /api/ui/applications/{id}/editor} and {@code GET /api/ui/applications/editor}.
     *
     * <p>The record plus every option the form needs, in one response. Two round trips would let the
     * form render with an owner list that no longer contains the application's own owner, and the
     * first thing the person would do is save that.
     */
    public Dispatcher.Response applicationEditor(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        String raw = request.pathVariables().get("id");
        UUID id = raw == null ? null : uuid(raw);
        if (raw != null && id == null) {
            return Dispatcher.Response.notFound();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        // Declared out here so the environment list below can be widened by what this record already
        // holds. An environment retired while a host was still recorded against it must still appear
        // in the form, or the value is invisible and nobody can clear it.
        Map<String, List<String>> domains = new java.util.LinkedHashMap<>();
        if (id != null) {
            Optional<InventoryService.Application> found = inventory.application(principal, id);
            if (found.isEmpty()) {
                return Dispatcher.Response.notFound();
            }
            InventoryService.Application app = found.orElseThrow();
            List<InventoryService.Related> related = inventory.related(principal, id);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", app.id().toString());
            entry.put("name", app.name());
            entry.put("owningNodeId", app.owningNodeId() == null ? null
                    : app.owningNodeId().toString());
            entry.put("exposureDeclared", app.exposureDeclared());
            entry.put("criticalityCode", app.criticalityCode());
            // Whether the tier is the application's own or its node's. The form needs the distinction
            // to render "inherited" as a state rather than as a value the person appears to have set,
            // and re-saving an inherited tier as an assigned one is a change nobody intended to make.
            entry.put("criticalityInherited", app.criticalityInherited());
            entry.put("description", app.description());
            entry.put("userBase", app.userBase());
            entry.put("features", String.join(", ", app.features()));
            entry.put("tags", String.join(", ", app.tags()));
            domains.putAll(domainsOf(related));
            entry.put("domains", domains);
            entry.put("repository", repositoryOf(related));
            entry.put("rowVersion", app.rowVersion());
            entry.put("lifecycleState", app.lifecycleState());
            body.put("application", entry);
        } else {
            body.put("application", null);
        }

        // Owners only: an application must be owned by a node whose TYPE may own assets. An empty list
        // is a real state and the interface says which condition failed, because "no options" in a
        // required dropdown is a dead end nobody can debug from the screen.
        body.put("owners", inventory.nodes(principal, true).stream().map(n -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", n.id().toString());
            entry.put("name", n.name());
            entry.put("typeCode", n.typeCode());
            entry.put("depth", n.depth());
            return entry;
        }).toList());
        body.put("tiers", inventory.tiers(principal).stream()
                .map(t -> Map.of("id", t.id().toString(), "code", t.code(),
                        "ordinal", t.ordinal())).toList());
        // Tenant data, not a fixed enumeration in the client. The exposure vocabulary is a check
        // constraint today, so it is listed here rather than typed into the React bundle — one place
        // to change it when it becomes configurable.
        body.put("exposures", List.of("INTERNET_PUBLIC", "PARTNER_B2B", "INTERNAL_ONLY",
                "AIR_GAPPED"));
        // The environments this form offers a domain input for. Tenant data (V069): this form used to
        // have Production and Staging compiled into it while the project form had Production and UAT,
        // so neither could record what the other could and an application's UAT host was
        // unrecordable — and therefore absent from every column, filter and count.
        body.put("environments", environmentsFor(inventory.endpointEnvironments(principal),
                domains.keySet()));
        body.put("mayRetire", principal != null && principal.holds(ApplicationPages.UPDATE));
        return json(body);
    }

    /** {@code POST /api/ui/applications} and {@code POST /api/ui/applications/{id}}. */
    public Dispatcher.Response applicationSave(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        String raw = request.pathVariables().get("id");
        UUID id = raw == null ? null : uuid(raw);
        if (raw != null && id == null) {
            return Dispatcher.Response.notFound();
        }
        // Re-read before writing through it. Authorizing the path and then acting on the identifier is
        // the defect class this product exists to find in other people's software.
        if (id != null && inventory.application(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }

        Map<String, Object> payload = request.body().orElse(Map.of());
        String name = text(payload.get("name"));
        name = name == null ? "" : name.strip();
        UUID node = uuid(text(payload.get("owningNodeId")));
        if (name.isEmpty()) {
            return rejected("NAME_REQUIRED", "name", "an application needs a name");
        }
        if (node == null) {
            return rejected("OWNER_REQUIRED", "owningNodeId",
                    "choose the organization that owns this application");
        }
        // The owner must be a node the CALLER can reach, re-checked here rather than trusted from the
        // form. A picker is a usability feature and never an authorization control (product principle
        // 4): a caller who edits the submitted identifier must not be able to move an application into
        // a branch they cannot see, which would take it out of their own scope and out of everybody
        // else's view of who owns it.
        if (inventory.node(principal, node).isEmpty()) {
            return rejected("OWNER_UNREACHABLE", "owningNodeId",
                    "that organization is not one you can reach");
        }

        UUID criticality = uuid(text(payload.get("criticalityTierId")));
        Integer rowVersion = integer(payload.get("rowVersion"));
        if (id != null && rowVersion == null) {
            return rejected("ROW_VERSION_REQUIRED", "rowVersion",
                    "the editor must send the version it loaded, so a concurrent edit is refused "
                            + "rather than overwritten");
        }

        // Endpoints, by environment. Validated against the tenant's catalogue rather than trusted:
        // the map keys become the environment recorded on an edge, and a caller who edited the
        // payload could otherwise write a vocabulary nobody declared — which the interface would then
        // show as a column, indistinguishable from one somebody agreed to.
        Map<String, List<String>> domains;
        try {
            domains = submittedDomains(payload.get("domains"),
                    inventory.endpointEnvironments(principal));
        } catch (IllegalArgumentException e) {
            return rejected("ENVIRONMENT_UNKNOWN", "domains", e.getMessage());
        }

        var draft = new InventoryService.ApplicationDraft(id, name, node, criticality,
                text(payload.get("exposureDeclared")), text(payload.get("description")),
                text(payload.get("userBase")), text(payload.get("features")),
                text(payload.get("tags")), domains, text(payload.get("repository")), rowVersion);

        Optional<UUID> saved = inventory.saveApplication(principal, draft);
        if (saved.isEmpty()) {
            return stale("somebody else changed this application while you were editing it. "
                    + "Reload to see their version before saving yours.");
        }
        return json(Map.of("id", saved.orElseThrow().toString()));
    }

    // ==============================================================================================
    // The declared-field catalogue
    // ==============================================================================================

    /**
     * {@code GET /api/ui/settings/fields}.
     *
     * <p>Every asset type and, for the one asked about, every field declared on it — DEPRECATED ones
     * included. An administrator who cannot see a retired field cannot restore it, and will instead
     * declare a second field with a different key meaning the same thing; two keys for one fact is
     * exactly what a catalogue exists to prevent.
     */
    public Dispatcher.Response fieldCatalogue(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        List<InventoryService.AssetTypeRow> types = inventory.assetTypes(principal);
        String typeCode = request.query().get("type");
        if (typeCode == null || typeCode.isBlank()) {
            typeCode = types.isEmpty() ? "" : types.get(0).code();
        }
        final String selected = typeCode;
        // A type the tenant does not have is not an error page. A stale bookmark should show the
        // catalogue, not a dead end.
        boolean known = types.stream().anyMatch(t -> t.code().equals(selected));
        List<InventoryService.AttributeDefinition> fields =
                known ? inventory.allDefinitions(principal, selected) : List.of();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("types", types.stream().map(t -> Map.of(
                "code", t.code(), "ordinal", t.ordinal(), "fieldCount", t.fieldCount())).toList());
        body.put("selectedType", known ? selected : "");
        body.put("dataTypes", InventoryService.DATA_TYPES);
        body.put("fields", fields.stream().map(EditorApi::catalogueEntry).toList());
        // Which permitted values are actually recorded, per select field. The editor greys the
        // removal of a value rows are holding, and says how many hold it — a refusal at save time
        // with no warning beforehand is the same information delivered too late to act on.
        Map<String, Object> inUse = new LinkedHashMap<>();
        if (known) {
            for (InventoryService.AttributeDefinition field : fields) {
                if (field.isSelect()) {
                    inUse.put(field.key(),
                            List.copyOf(inventory.valuesInUse(principal, selected, field.key())));
                }
            }
        }
        body.put("valuesInUse", inUse);
        body.put("mayManage", principal != null && principal.holds(InventoryService.FIELD_ADMIN));
        return json(body);
    }

    private static Map<String, Object> catalogueEntry(InventoryService.AttributeDefinition d) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", d.id().toString());
        entry.put("key", d.key());
        entry.put("label", d.label());
        entry.put("labelVi", d.labelVi());
        entry.put("dataType", d.dataType());
        entry.put("permittedValues", d.permittedValues());
        entry.put("filterable", d.filterable());
        entry.put("required", d.required());
        entry.put("purpose", d.purpose());
        entry.put("ordinal", d.ordinal());
        entry.put("lifecycleState", d.lifecycleState());
        entry.put("rowVersion", d.rowVersion());
        return entry;
    }

    /** {@code POST /api/ui/settings/fields} — declare a new one. */
    public Dispatcher.Response fieldCreate(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        Map<String, Object> payload = request.body().orElse(Map.of());
        String typeCode = text(payload.get("assetType"));
        if (typeCode == null || typeCode.isBlank()) {
            return rejected("TYPE_REQUIRED", "assetType", "choose the asset type this field is for");
        }
        try {
            Optional<UUID> created = inventory.createDefinition(principal, typeCode,
                    text(payload.get("key")), text(payload.get("label")),
                    text(payload.get("labelVi")), text(payload.get("dataType")),
                    stringList(payload.get("permittedValues")),
                    Boolean.TRUE.equals(payload.get("filterable")),
                    Boolean.TRUE.equals(payload.get("required")),
                    text(payload.get("purpose")));
            if (created.isEmpty()) {
                return rejected("KEY_TAKEN", "key",
                        "that key is already declared on this type. Restore the existing field "
                                + "rather than declaring a second one for the same fact");
            }
            return json(Map.of("id", created.orElseThrow().toString()));
        } catch (IllegalArgumentException e) {
            return rejected("INVALID", "key", e.getMessage());
        }
    }

    /** {@code POST /api/ui/settings/fields/{id}} — amend one. */
    public Dispatcher.Response fieldUpdate(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        Map<String, Object> payload = request.body().orElse(Map.of());
        String typeCode = text(payload.get("assetType"));
        Integer rowVersion = integer(payload.get("rowVersion"));
        if (id == null || typeCode == null || typeCode.isBlank()) {
            return Dispatcher.Response.notFound();
        }
        if (rowVersion == null) {
            return rejected("ROW_VERSION_REQUIRED", "rowVersion",
                    "the editor must send the version it loaded");
        }
        Optional<InventoryService.AttributeDefinition> existing =
                inventory.allDefinitions(principal, typeCode).stream()
                        .filter(d -> d.id().equals(id)).findFirst();
        if (existing.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        InventoryService.AttributeDefinition field = existing.orElseThrow();
        List<String> wanted = stringList(payload.get("permittedValues"));

        // Removing a value that assets are holding is refused, and every offending value is named.
        // Removal does not delete those values — they stay in `attributes` and would render as a
        // value nobody declared, filterable by nothing and explicable by no one.
        if (field.isSelect()) {
            java.util.Set<String> used = inventory.valuesInUse(principal, typeCode, field.key());
            List<String> orphaned = field.permittedValues().stream()
                    .filter(used::contains).filter(v -> !wanted.contains(v)).toList();
            if (!orphaned.isEmpty()) {
                return new Dispatcher.Response(400, Map.of(
                        "code", "VALUE_IN_USE", "field", "permittedValues",
                        "message", "still recorded on assets: " + String.join(", ", orphaned)
                                + ". Removing a value does not remove it from the records holding "
                                + "it — change those first, or leave the value declared.",
                        "values", orphaned), Map.of());
            }
        }
        try {
            boolean applied = inventory.updateDefinition(principal, id, text(payload.get("label")),
                    text(payload.get("labelVi")), wanted,
                    Boolean.TRUE.equals(payload.get("filterable")),
                    Boolean.TRUE.equals(payload.get("required")),
                    text(payload.get("purpose")), rowVersion.intValue());
            return applied ? json(Map.of("id", id.toString()))
                    : stale("somebody else changed this field while you were editing it.");
        } catch (java.sql.SQLException e) {
            // The CHECK that forbids a select type with no options. Reported as the sentence it is
            // rather than as a constraint name nobody outside this file can read.
            return rejected("NO_OPTIONS", "permittedValues",
                    "a dropdown with no options is a field nobody can complete");
        }
    }

    /** {@code POST /api/ui/settings/fields/{id}/lifecycle} — retire one, or bring it back. */
    public Dispatcher.Response fieldLifecycle(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        Map<String, Object> payload = request.body().orElse(Map.of());
        Integer rowVersion = integer(payload.get("rowVersion"));
        if (id == null || rowVersion == null) {
            return Dispatcher.Response.notFound();
        }
        boolean active = Boolean.TRUE.equals(payload.get("active"));
        boolean applied = inventory.setDefinitionLifecycle(principal, id, active,
                rowVersion.intValue());
        return applied ? json(Map.of("id", id.toString()))
                : stale("somebody else changed this field while you were editing it.");
    }

    /** {@code POST /api/ui/settings/fields/{id}/move} — reorder within the type. */
    public Dispatcher.Response fieldMove(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        Map<String, Object> payload = request.body().orElse(Map.of());
        Integer delta = integer(payload.get("delta"));
        if (id == null || delta == null || delta.intValue() == 0) {
            return Dispatcher.Response.notFound();
        }
        boolean applied = inventory.reorderDefinition(principal, id,
                delta.intValue() > 0 ? 1 : -1);
        return applied ? json(Map.of("id", id.toString())) : Dispatcher.Response.notFound();
    }

    // ==============================================================================================
    // The endpoint environment catalogue
    // ==============================================================================================

    /**
     * {@code GET /api/ui/settings/environments}.
     *
     * <p>Every environment, in all three states, with how many endpoints each currently holds. The
     * count is not decoration: retiring an environment removes it from both editors and leaves every
     * edge published in it exactly where it was, so an administrator needs to know that forty hosts
     * are about to become unmaintainable from any form before pressing the button rather than after.
     *
     * <p>Undeclared environments are listed too, marked as such. They are the ones an importer wrote,
     * and the useful action on them is to declare them — which cannot be offered by a screen that
     * does not admit they exist.
     */
    public Dispatcher.Response environmentCatalogue(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        // *** THE ENDPOINT COUNT IS BEHIND THE MANAGE PERMISSION, AND THAT IS A CORRECTION. ***
        //
        // It was returned to anybody holding ast.asset.read. The count is TENANT-WIDE and carries no
        // scope predicate — it cannot have one, because "how many endpoints does retiring this
        // environment affect" is a question about the whole tenant — so a reader whose scope is one
        // team could read how many endpoints exist in each environment across every team. A count is
        // not an identity, but it is an aggregate that ignores scope, and DOC-24 names aggregates as
        // one of the surfaces object-level authorization leaks through (SEC-AUZ-016, product
        // principle 4).
        //
        // Gated rather than scoped: the number exists to inform an administrative decision, and a
        // scoped version of it would answer a different question while looking like the same one.
        boolean mayManage = principal != null && principal.holds(InventoryService.FIELD_ADMIN);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InventoryService.EndpointEnvironment environment
                : inventory.endpointEnvironments(principal)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", environment.id() == null ? null : environment.id().toString());
            entry.put("code", environment.code());
            entry.put("label", environment.label());
            entry.put("labelVi", environment.labelVi());
            entry.put("purpose", environment.purpose());
            entry.put("ordinal", environment.ordinal());
            entry.put("lifecycleState", environment.lifecycleState());
            entry.put("declared", environment.declared());
            entry.put("rowVersion", environment.rowVersion());
            // Absent, not zero. A zero would read as "nothing is published here", which is a fact
            // about the estate; absence says the caller was not told (product principle 1).
            entry.put("endpointCount", mayManage
                    ? Long.valueOf(inventory.endpointsInEnvironment(principal, environment.code()))
                    : null);
            rows.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("environments", rows);
        // The same permission the declared-field catalogue uses, and deliberately not a second one:
        // deciding that the inventory asks "which host serves this in UAT" is the same decision, by
        // the same people, as deciding it asks "which CDN is in front of it" (V069).
        body.put("mayManage", mayManage);
        return json(body);
    }

    /** {@code POST /api/ui/settings/environments} — declare one. */
    public Dispatcher.Response environmentCreate(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        Map<String, Object> payload = request.body().orElse(Map.of());
        try {
            Optional<UUID> created = inventory.createEnvironment(principal,
                    text(payload.get("code")), text(payload.get("label")),
                    text(payload.get("labelVi")), text(payload.get("purpose")));
            if (created.isEmpty()) {
                return rejected("CODE_TAKEN", "code",
                        "that environment is already declared. Restore the existing one rather than "
                                + "declaring a second row for the same environment — the edges "
                                + "recorded under it would answer to whichever row somebody read.");
            }
            return json(Map.of("id", created.orElseThrow().toString()));
        } catch (IllegalArgumentException e) {
            return rejected("INVALID", "code", e.getMessage());
        }
    }

    /** {@code POST /api/ui/settings/environments/{id}} — relabel or restate one. */
    public Dispatcher.Response environmentUpdate(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        Map<String, Object> payload = request.body().orElse(Map.of());
        Integer rowVersion = integer(payload.get("rowVersion"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        if (rowVersion == null) {
            return rejected("ROW_VERSION_REQUIRED", "rowVersion",
                    "the editor must send the version it loaded");
        }
        boolean applied = inventory.updateEnvironment(principal, id, text(payload.get("label")),
                text(payload.get("labelVi")), text(payload.get("purpose")), rowVersion.intValue());
        return applied ? json(Map.of("id", id.toString()))
                : stale("somebody else changed this environment while you were editing it.");
    }

    /** {@code POST /api/ui/settings/environments/{id}/lifecycle} — retire one, or restore it. */
    public Dispatcher.Response environmentLifecycle(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        Map<String, Object> payload = request.body().orElse(Map.of());
        Integer rowVersion = integer(payload.get("rowVersion"));
        if (id == null || rowVersion == null) {
            return Dispatcher.Response.notFound();
        }
        boolean active = Boolean.TRUE.equals(payload.get("active"));
        boolean applied = inventory.setEnvironmentLifecycle(principal, id, active,
                rowVersion.intValue());
        return applied ? json(Map.of("id", id.toString()))
                : stale("somebody else changed this environment while you were editing it.");
    }

    /** {@code POST /api/ui/settings/environments/{id}/move} — reorder. */
    public Dispatcher.Response environmentMove(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        Map<String, Object> payload = request.body().orElse(Map.of());
        Integer delta = integer(payload.get("delta"));
        if (id == null || delta == null || delta.intValue() == 0) {
            return Dispatcher.Response.notFound();
        }
        boolean applied = inventory.reorderEnvironment(principal, id,
                delta.intValue() > 0 ? 1 : -1);
        return applied ? json(Map.of("id", id.toString())) : Dispatcher.Response.notFound();
    }

    /** A JSON array of strings, from a payload that may send anything. */
    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).strip().isEmpty()) {
                out.add(String.valueOf(item).strip());
            }
        }
        return List.copyOf(out);
    }

    // ==============================================================================================
    // Projects
    // ==============================================================================================

    /**
     * {@code GET /api/ui/projects/{id}/editor}.
     *
     * <p>The project, the tenant's declared field catalogue for {@code PROJECT}, and every option list
     * the form needs, in one response. The catalogue is sent rather than compiled into the client
     * because it is <b>tenant data</b> (ADR-027): another deployment declares different fields with
     * different permitted values, and a dropdown hard-coded in the React bundle would make that a
     * release rather than an INSERT.
     */
    public Dispatcher.Response projectEditor(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        Optional<aspm.app.inventory.ProjectQuery.Project> found = projects.project(principal, id);
        if (found.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        aspm.app.inventory.ProjectQuery.Project project = found.orElseThrow();
        List<InventoryService.Related> related = inventory.related(principal, id);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", project.id().toString());
        entry.put("name", project.name());
        entry.put("applicationName", project.applicationName());
        entry.put("owningNodeName", project.owningNodeName());
        entry.put("exposureDeclared", project.exposureDeclared());
        entry.put("criticalityCode", project.criticalityCode());
        // The distinction the form needs to render "inherited" as a state rather than as a value
        // somebody appears to have chosen. Re-saving an inherited tier as an assigned one is a change
        // nobody intended to make and nothing would ever flag.
        entry.put("criticalityInherited", project.criticalityInherited());
        entry.put("technicalContactId", project.technicalContactId() == null
                ? null : project.technicalContactId().toString());
        entry.put("technicalContactName", project.technicalContactName());
        entry.put("attributes", project.attributes());
        Map<String, List<String>> domains = domainsOf(related);
        entry.put("domains", domains);
        entry.put("repository", repositoryOf(related));
        entry.put("repositoryBranch", branchOf(related));
        entry.put("rowVersion", project.rowVersion());
        entry.put("lifecycleState", project.lifecycleState());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project", entry);
        body.put("fields", inventory.attributeDefinitions(principal,
                aspm.app.inventory.ProjectQuery.PROJECT_TYPE).stream()
                .map(EditorApi::fieldOf).toList());
        body.put("tiers", inventory.tiers(principal).stream()
                .map(t -> Map.of("id", t.id().toString(), "code", t.code(),
                        "ordinal", t.ordinal())).toList());
        // A check constraint today, so it is listed here rather than typed into the React bundle —
        // one place to change it when it becomes configurable. `access_path` is a DECLARED attribute
        // and arrives in `fields` instead; the two answer different questions and are kept apart on
        // purpose (see the PROJECT catalogue seed).
        body.put("exposures", List.of("INTERNET_PUBLIC", "PARTNER_B2B", "INTERNAL_ONLY",
                "AIR_GAPPED"));
        // The same environment catalogue the application editor renders, from the same read (V069).
        // Two forms with two hardcoded pairs is what this replaces.
        body.put("environments", environmentsFor(inventory.endpointEnvironments(principal),
                domains.keySet()));
        body.put("people", assessments.assignableprincipals(principal));
        return json(body);
    }

    /** One declared field, as the editor needs it. */
    private static Map<String, Object> fieldOf(InventoryService.AttributeDefinition definition) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("key", definition.key());
        field.put("label", definition.label());
        field.put("dataType", definition.dataType());
        field.put("permittedValues", definition.permittedValues());
        field.put("required", definition.required());
        field.put("filterable", definition.filterable());
        // Rendered beside the field. A field whose purpose nobody can state is a field people fill in
        // wrongly and then filter on, which is worse than leaving it empty.
        field.put("purpose", definition.purpose());
        return field;
    }

    /**
     * {@code POST /api/ui/projects/{id}/editor}.
     *
     * <p>Every value is re-checked against the catalogue here, not only in the dropdown that produced
     * it. The form is on the client and the client belongs to the caller: until
     * {@link InventoryService#attributeViolations} existed, a posted
     * {@code {"waf":"whatever"}} was stored verbatim and every filter built on that field acquired a
     * value nobody declared.
     */
    public Dispatcher.Response projectSave(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        // Re-read through the scoped query before writing through the identifier. Authorizing the path
        // and then acting on what the body names is the defect class this product exists to find in
        // other people's software (SEC-AUZ-017).
        Optional<aspm.app.inventory.ProjectQuery.Project> found = projects.project(principal, id);
        if (found.isEmpty()) {
            return Dispatcher.Response.notFound();
        }

        Map<String, Object> payload = request.body().orElse(Map.of());
        String name = text(payload.get("name"));
        name = name == null ? "" : name.strip();
        if (name.isEmpty()) {
            return rejected("NAME_REQUIRED", "name", "a project needs a name");
        }
        Integer rowVersion = integer(payload.get("rowVersion"));
        if (rowVersion == null) {
            return rejected("ROW_VERSION_REQUIRED", "rowVersion",
                    "the editor must send the version it loaded, so a concurrent edit is refused "
                            + "rather than overwritten");
        }

        // The named contact must be somebody this caller could assign. Re-checked rather than trusted
        // from the picker: product principle 4 — a filtered picker is a usability feature and never an
        // authorization control.
        UUID contact = uuid(text(payload.get("technicalContactId")));
        if (contact != null) {
            boolean assignable = assessments.assignableprincipals(principal).stream()
                    .anyMatch(person -> contact.toString().equals(person.get("id")));
            if (!assignable) {
                return rejected("CONTACT_UNKNOWN", "technicalContactId",
                        "that person is not one you can name as a contact");
            }
        }

        List<InventoryService.AttributeDefinition> definitions = inventory.attributeDefinitions(
                principal, aspm.app.inventory.ProjectQuery.PROJECT_TYPE);
        Object raw = payload.get("attributes");
        Map<String, Object> submitted = raw instanceof Map<?, ?> map
                ? map.entrySet().stream().collect(LinkedHashMap::new,
                        (into, e) -> into.put(String.valueOf(e.getKey()), e.getValue()),
                        LinkedHashMap::putAll)
                : Map.of();
        Map<String, Object> attributes = inventory.attributeDocumentFrom(definitions, submitted);
        List<InventoryService.AttributeViolation> problems =
                inventory.attributeViolations(definitions, attributes);
        if (!problems.isEmpty()) {
            InventoryService.AttributeViolation first = problems.get(0);
            // The first violation drives focus; all of them are returned, because a form that reports
            // one error per round trip teaches people to stop reading the message.
            return new Dispatcher.Response(400, Map.of(
                    "code", first.code(), "field", first.key(), "message", first.message(),
                    "violations", problems.stream().map(v -> Map.of(
                            "field", v.key(), "code", v.code(), "message", v.message())).toList()),
                    Map.of());
        }

        Map<String, List<String>> domains;
        try {
            domains = submittedDomains(payload.get("domains"),
                    inventory.endpointEnvironments(principal));
        } catch (IllegalArgumentException e) {
            return rejected("ENVIRONMENT_UNKNOWN", "domains", e.getMessage());
        }

        var draft = new InventoryService.ProjectDraft(id, name,
                found.orElseThrow().owningNodeId(), uuid(text(payload.get("criticalityTierId"))),
                text(payload.get("exposureDeclared")), contact, attributes, domains,
                text(payload.get("repository")), text(payload.get("repositoryBranch")),
                rowVersion);

        Optional<UUID> saved = inventory.saveProject(principal, draft);
        if (saved.isEmpty()) {
            return stale("somebody else changed this project while you were editing it. "
                    + "Reload to see their version before saving yours.");
        }
        return json(Map.of("id", saved.orElseThrow().toString()));
    }

    /** {@code POST /api/ui/applications/{id}/retire}. */
    public Dispatcher.Response applicationRetire(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || inventory.application(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> payload = request.body().orElse(Map.of());
        Integer rowVersion = integer(payload.get("rowVersion"));
        boolean done = inventory.retireApplication(principal, id, text(payload.get("reason")),
                rowVersion == null ? -1 : rowVersion);
        if (!done) {
            return stale("this application changed while the page was open. Reload and try again.");
        }
        return json(Map.of("retired", true));
    }

    // ==============================================================================================
    // Organization nodes
    // ==============================================================================================

    /** {@code POST /api/ui/organization} — create a node. */
    public Dispatcher.Response nodeCreate(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        Map<String, Object> payload = request.body().orElse(Map.of());
        String name = text(payload.get("name"));
        name = name == null ? "" : name.strip();
        UUID typeId = uuid(text(payload.get("typeId")));
        if (name.isEmpty()) {
            return rejected("NAME_REQUIRED", "name", "the node needs a name");
        }
        if (typeId == null) {
            return rejected("TYPE_REQUIRED", "typeId", "choose what kind of node this is");
        }
        UUID parentId = uuid(text(payload.get("parentId")));
        // Same re-validation as the owner above, for the same reason: grafting a node onto a branch
        // the caller cannot see would put it beyond their own scope the moment it is created.
        if (parentId != null && inventory.node(principal, parentId).isEmpty()) {
            return rejected("PARENT_UNREACHABLE", "parentId",
                    "that parent is not one you can reach");
        }
        Optional<UUID> created = inventory.saveNode(principal, null, name, typeId, parentId,
                uuid(text(payload.get("criticalityTierId"))), null);
        if (created.isEmpty()) {
            return stale("the tree changed while this form was open. Reload and try again.");
        }
        return json(Map.of("id", created.orElseThrow().toString()));
    }

    /** {@code POST /api/ui/organization/{id}} — rename, or set criticality. */
    public Dispatcher.Response nodeUpdate(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || inventory.node(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> payload = request.body().orElse(Map.of());
        String name = text(payload.get("name"));
        name = name == null ? "" : name.strip();
        if (name.isEmpty()) {
            return rejected("NAME_REQUIRED", "name", "the node needs a name");
        }
        Integer rowVersion = integer(payload.get("rowVersion"));
        if (rowVersion == null) {
            return rejected("ROW_VERSION_REQUIRED", "rowVersion",
                    "the editor must send the version it loaded");
        }
        // Type and parent are deliberately not editable here, matching the service: moving a node
        // rewrites every closure row and every asset's cached ancestor path beneath it, and that is a
        // migration rather than an edit.
        Optional<UUID> saved = inventory.saveNode(principal, id, name, null, null,
                uuid(text(payload.get("criticalityTierId"))), rowVersion);
        if (saved.isEmpty()) {
            return stale("somebody else changed this node while you were editing it.");
        }
        return json(Map.of("id", saved.orElseThrow().toString()));
    }

    /** {@code POST /api/ui/organization/{id}/deprecate}. */
    public Dispatcher.Response nodeDeprecate(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || inventory.node(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> payload = request.body().orElse(Map.of());
        Integer rowVersion = integer(payload.get("rowVersion"));
        boolean done = inventory.deprecateNode(principal, id, rowVersion == null ? -1 : rowVersion);
        if (!done) {
            // Refused means live children or assets still hang off it. Said plainly rather than as a
            // generic failure: deprecating a node with assets under it would leave them owned by
            // something the tree no longer offers, reachable only by somebody who knows the id.
            return new Dispatcher.Response(409, Map.of("code", "NODE_IN_USE",
                    "message", "this node still has live children or assets under it. Move or "
                            + "retire those first — deprecating it now would leave them owned by "
                            + "something the tree no longer shows."), Map.of());
        }
        return json(Map.of("deprecated", true));
    }

    // ==============================================================================================
    // Recording a finding
    // ==============================================================================================

    /**
     * {@code GET /api/ui/board/{id}/finding-form} — the option lists the record form needs.
     *
     * <p>Separate from the request payload rather than folded into it. The request page is read far
     * more often than a finding is recorded, and the severity scale and the request's scope assets
     * are only needed by the person about to write one — a read that is mostly a read should not
     * carry the write's vocabulary on every load.
     */
    public Dispatcher.Response findingForm(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || assessments.request(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("severities", assessments.severities(principal));
        // The assets this request actually declared. Not the whole inventory: a finding recorded
        // against an asset the engagement never covered would put a weakness on something nobody
        // tested, and the coverage figures downstream would then be quietly wrong.
        body.put("assets", assessments.scopeAssets(principal, id));
        body.put("contexts", aspm.app.assessment.AssessmentService.CONTEXTS);
        body.put("defaultContext", aspm.app.assessment.AssessmentService.DEFAULT_CONTEXT);
        // The three classifications the form now requires, each with the tenant's own help text. Sent
        // with the form rather than fetched separately: a picker whose options arrive after the field
        // is rendered is a picker somebody types past.
        body.put("riskCategories", options(classifier.categories(principal)));
        body.put("owaspCategories", options(classifier.owasp(principal)));
        body.put("cwes", options(classifier.cwes(principal)));
        return json(body);
    }

    private static List<Map<String, Object>> options(
            List<aspm.app.resource.FindingClassifier.Option> from) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var option : from) {
            out.add(Map.of("code", option.code(), "label", option.label(),
                    "hint", option.hint() == null ? "" : option.hint()));
        }
        return out;
    }

    /**
     * {@code POST /api/ui/board/{id}/findings} — record one.
     *
     * <p>{@code finding_class} is fixed at {@code MANUAL} and is not read from the payload, exactly
     * as the server-rendered form fixes it. The class records how a finding was <b>discovered</b>, and
     * a person typing into this form discovered it by assessment however they describe it — letting
     * the client choose would make the provenance field say whatever the client preferred.
     */
    public Dispatcher.Response recordFinding(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || assessments.request(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> payload = request.body().orElse(Map.of());
        String title = text(payload.get("title"));
        title = title == null ? "" : title.strip();
        if (title.isEmpty()) {
            return rejected("TITLE_REQUIRED", "title",
                    "a finding needs a title. It is what everybody downstream sees first.");
        }
        String context = text(payload.get("context"));
        if (context == null
                || !aspm.app.assessment.AssessmentService.CONTEXTS.contains(context)) {
            context = aspm.app.assessment.AssessmentService.DEFAULT_CONTEXT;
        }
        // THE GATE. A finding cannot be submitted without all three classifications.
        //
        // Refusing is the whole design. The earlier plan was to let a submission through and have an
        // agent fill the gaps afterwards, which meant an AI value entering the record with nobody
        // having looked at it. Blocking instead means the person either types it or presses analyse and
        // reads what appeared — and their submission is what writes it. ADR-005 holds because there is
        // no path here that an agent can take on its own.
        String category = text(payload.get("executiveRiskCategory"));
        String owasp = text(payload.get("owaspTop10_2025"));
        String cwe = text(payload.get("cweId"));
        if (category == null || category.isBlank()) {
            return rejected("RISK_CATEGORY_REQUIRED", "executiveRiskCategory",
                    "pick an executive risk category, or use Analyse to propose one. This is what the "
                            + "business-facing reports group by.");
        }
        if (owasp == null || owasp.isBlank()) {
            return rejected("OWASP_REQUIRED", "owaspTop10_2025",
                    "pick an OWASP Top 10:2025 entry, or “Not in the OWASP Top 10:2025” if none fits. "
                            + "Saying it does not apply is an answer; leaving it empty is not.");
        }
        if (cwe == null || cwe.isBlank()) {
            return rejected("CWE_REQUIRED", "cweId",
                    "pick a CWE, or CWE-UNKNOWN if the weakness class is not clear. A wrong CWE is "
                            + "worse than an unknown one.");
        }
        String source = "AI_ASSISTED".equals(text(payload.get("classificationSource")))
                ? "AI_ASSISTED" : "ASSESSOR";

        UUID finding = assessments.recordFinding(principal, id, uuid(text(payload.get("assetId"))),
                title, uuid(text(payload.get("severityId"))), "MANUAL", context,
                text(payload.get("description")), text(payload.get("proofOfConcept")));
        // Applied after the insert rather than inside it, because AssessmentService.recordFinding is
        // shared with the server-rendered form and widening its signature would change a second caller
        // that does not collect these yet. The finding exists either way; a failure here leaves it
        // unclassified and visible as such, which is the safe direction.
        classifier.apply(principal, finding,
                category, owasp, cwe, source, text(payload.get("classificationBasis")));
        return json(Map.of("id", finding.toString(), "classificationSource", source));
    }

    // ==============================================================================================
    // Roles
    // ==============================================================================================

    /**
     * {@code GET /api/ui/roles} — every role with its permission count, plus the catalogue.
     *
     * <p>Retired roles are included and flagged. A retired role that still has live assignments is the
     * thing an administrator most needs to see, and filtering it out of the list is how it stays
     * granted for another year.
     */
    public Dispatcher.Response roles(Dispatcher.Request request) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AccountService.RoleRow role : accounts.allRoles(tenantId)) {
            rows.add(roleEntry(role));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roles", rows);
        body.put("permissions", permissionCatalogue());
        return json(body);
    }

    /** {@code GET /api/ui/roles/{id}} — one role, its permissions, and whether it can be removed. */
    public Dispatcher.Response role(Dispatcher.Request request) throws Exception {
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        Optional<AccountService.RoleRow> found = accounts.role(tenantId, id);
        if (found.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        AccountService.RoleRemoval removal = accounts.roleRemoval(tenantId, id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role", roleEntry(found.orElseThrow()));
        body.put("permissions", permissionCatalogue());
        // Three separate figures, not one boolean. "Cannot delete" is unactionable; "twelve people
        // hold it, and it has been held at some point by thirty" tells an administrator whether to
        // revoke or to retire, and retiring is what preserves the audit trail (principle 5).
        body.put("removal", Map.of(
                "deletable", removal.deletable(),
                "liveAssignments", removal.liveAssignments(),
                "everAssigned", removal.everAssigned()));
        return json(body);
    }

    /** {@code POST /api/ui/roles} — create a role. */
    public Dispatcher.Response roleCreate(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        Map<String, Object> payload = request.body().orElse(Map.of());
        String code = text(payload.get("code"));
        code = code == null ? "" : code.strip().toUpperCase(java.util.Locale.ROOT);
        String label = text(payload.get("label"));
        label = label == null ? "" : label.strip();
        if (code.isEmpty()) {
            return rejected("CODE_REQUIRED", "code",
                    "a role needs a code. It is immutable once created, so choose it deliberately.");
        }
        if (!code.matches("[A-Z][A-Z0-9_]{1,63}")) {
            return rejected("CODE_INVALID", "code",
                    "letters, digits and underscores, starting with a letter. The code is used in "
                            + "audit records and cannot be changed afterwards.");
        }
        if (label.isEmpty()) {
            return rejected("LABEL_REQUIRED", "label", "a role needs a name people will recognise");
        }
        Optional<UUID> created = accounts.createRole(tenantId, actorOf(principal), code, label,
                text(payload.get("description")));
        if (created.isEmpty()) {
            return rejected("CODE_TAKEN", "code", "a role with that code already exists");
        }
        UUID id = created.orElseThrow();
        Set<String> codes = permissionCodes(payload.get("permissions"));
        if (!codes.isEmpty()) {
            accounts.setRolePermissions(tenantId, actorOf(principal), id, codes);
        }
        return json(Map.of("id", id.toString()));
    }

    /**
     * {@code POST /api/ui/roles/{id}} — update the label, description and permission set.
     *
     * <p>The code is not editable and is not read from the payload. It appears in audit records that
     * are already written, and letting it change would make those records refer to a role that no
     * longer means what they say it meant.
     */
    public Dispatcher.Response roleSave(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || accounts.role(tenantId, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> payload = request.body().orElse(Map.of());
        String label = text(payload.get("label"));
        label = label == null ? "" : label.strip();
        if (label.isEmpty()) {
            return rejected("LABEL_REQUIRED", "label", "a role needs a name people will recognise");
        }
        boolean updated = accounts.updateRole(tenantId, actorOf(principal), id, label,
                text(payload.get("description")));
        if (!updated) {
            return stale("this role changed while the page was open. Reload and try again.");
        }
        // The permission set is replaced wholesale, and only where the payload names it. A client that
        // sends no permissions field is editing the label, not stripping every permission the role
        // holds — a distinction an absent key and an empty list have to keep apart.
        int changed = -1;
        if (payload.containsKey("permissions")) {
            changed = accounts.setRolePermissions(tenantId, actorOf(principal), id,
                    permissionCodes(payload.get("permissions")));
        }
        return json(Map.of("id", id.toString(), "permissionsChanged", changed));
    }

    /** {@code POST /api/ui/roles/{id}/retire}. */
    public Dispatcher.Response roleRetire(Dispatcher.Request request) throws Exception {
        return roleLifecycle(request, "retire");
    }

    /** {@code POST /api/ui/roles/{id}/restore}. */
    public Dispatcher.Response roleRestore(Dispatcher.Request request) throws Exception {
        return roleLifecycle(request, "restore");
    }

    /**
     * {@code POST /api/ui/roles/{id}/delete}.
     *
     * <p>Permitted only for a role that has <b>never</b> been assigned. Anything else is retired
     * instead, because deleting a role that once granted somebody access removes the meaning of every
     * audit record that names it — and the record of what happened is inviolable (principle 5).
     */
    public Dispatcher.Response roleDelete(Dispatcher.Request request) throws Exception {
        return roleLifecycle(request, "delete");
    }

    private Dispatcher.Response roleLifecycle(Dispatcher.Request request, String action)
            throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || accounts.role(tenantId, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        boolean done = switch (action) {
            case "retire" -> accounts.retireRole(tenantId, actorOf(principal), id);
            case "restore" -> accounts.restoreRole(tenantId, actorOf(principal), id);
            default -> accounts.deleteRole(tenantId, id);
        };
        if (!done) {
            if ("delete".equals(action)) {
                AccountService.RoleRemoval removal = accounts.roleRemoval(tenantId, id);
                return new Dispatcher.Response(409, Map.of("code", "ROLE_HAS_HISTORY",
                        "message", "this role has been assigned " + removal.everAssigned()
                                + " time(s), so deleting it would strand the audit records that "
                                + "name it. Retire it instead — that stops new grants and leaves "
                                + "the history readable."), Map.of());
            }
            return stale("this role changed while the page was open. Reload and try again.");
        }
        return json(Map.of(action + "d", true));
    }

    // ----------------------------------------------------------------------------------------------

    private Map<String, Object> roleEntry(AccountService.RoleRow role) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", role.id().toString());
        entry.put("code", role.code());
        entry.put("label", role.label());
        entry.put("description", role.description());
        entry.put("active", role.active());
        entry.put("permissions", List.copyOf(role.permissionCodes()));
        entry.put("assignments", role.assignmentCount());
        // Whether the role came from the deployment template. Shown because editing one is legitimate
        // but means the next template refresh will not carry the change — a person renaming a template
        // role should know that before they do it, not after.
        entry.put("fromTemplate", role.fromTemplate());
        return entry;
    }

    /**
     * The permission catalogue, grouped by its own prefix.
     *
     * <p>Product-fixed, unlike the roles built from it (ADR-027). Grouped by the domain the catalogue
     * already records, because a flat list of a hundred and fifty checkboxes is a list nobody reads
     * before ticking — and because the grouping is then a fact about the catalogue rather than a
     * taxonomy invented for this screen and diverging from the next one.
     */
    /**
     * The catalogue, from the one definition of it.
     *
     * <p>It used to be built here and nowhere else, and then the service-credential form needed the
     * same list — two builders of one catalogue is two groupings that drift, and the drift shows up as
     * a permission an administrator can grant to a role but not to a credential.
     */
    private List<Map<String, Object>> permissionCatalogue() throws java.sql.SQLException {
        return AccessApi.permissionCatalogue(accounts, tenantId);
    }

    /** Only codes the catalogue actually contains, so a typo cannot store a permission nobody grants. */
    private Set<String> permissionCodes(Object raw) throws java.sql.SQLException {
        if (!(raw instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> known = new LinkedHashSet<>();
        for (AccountService.PermissionRow permission : accounts.permissions(tenantId)) {
            known.add(permission.code());
        }
        Set<String> codes = new LinkedHashSet<>();
        for (Object item : list) {
            String code = text(item);
            if (code != null && known.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /**
     * The hosts one record is published on, grouped by environment.
     *
     * <p><b>*** THIS REPLACES A `findFirst()`, AND THAT WAS A DEFECT. ***</b> The editors read one
     * host per environment. A project published on two hosts in one environment showed the first, and
     * saving the form closed the edge to the second — silently, because the form never displayed it.
     * The value was in the column on the list page the whole time, which is where somebody would have
     * had to notice the discrepancy.
     *
     * <p>An edge carrying no environment lands under {@code UNSPECIFIED}, the same substitute the
     * inventory columns use. It is deliberately not a catalogue row: it is the absence of an answer,
     * not a name anybody chose, so the form shows it and no form offers it as a new choice.
     */
    private static Map<String, List<String>> domainsOf(List<InventoryService.Related> related) {
        Map<String, List<String>> domains = new LinkedHashMap<>();
        for (InventoryService.Related row : related) {
            if (!"DOMAIN".equals(row.typeCode())) {
                continue;
            }
            String environment = row.environment() == null || row.environment().isBlank()
                    ? "UNSPECIFIED" : row.environment();
            domains.computeIfAbsent(environment, key -> new ArrayList<>()).add(row.name());
        }
        domains.values().forEach(java.util.Collections::sort);
        return domains;
    }

    /**
     * The environments a form offers a domain input for.
     *
     * <p>Active catalogue rows, plus any environment this record already holds a host in even where
     * that environment is retired or was never declared. The second half is what keeps a recorded
     * value visible: an environment dropped from the catalogue while a host was published in it would
     * otherwise vanish from the form, leaving a value on the list page that no form could correct.
     *
     * <p>Each entry says which of the three states it is in, so the form can label a retired one
     * rather than presenting it as a current choice.
     */
    private static List<Map<String, Object>> environmentsFor(
            List<InventoryService.EndpointEnvironment> catalogue, Set<String> recordedHere) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        for (InventoryService.EndpointEnvironment environment : catalogue) {
            if (!environment.active() && !recordedHere.contains(environment.code())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", environment.code());
            entry.put("label", environment.label());
            entry.put("purpose", environment.purpose());
            entry.put("lifecycleState", environment.lifecycleState());
            out.add(entry);
            emitted.add(environment.code());
        }
        // A value recorded under a name the catalogue has never heard of — UNSPECIFIED, or whatever an
        // importer wrote. Shown last, and shown, because the alternative is a host nobody can see.
        for (String code : recordedHere) {
            if (emitted.add(code)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("code", code);
                entry.put("label", code);
                entry.put("purpose", null);
                entry.put("lifecycleState", "UNDECLARED");
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Reads the submitted endpoint map, refusing an environment the tenant has not declared.
     *
     * <p>Refused rather than dropped, which is the opposite of how the read side treats an unknown
     * environment in a filter. A filter that names a retired environment is a stale bookmark and
     * narrowing to nothing is the honest answer; a WRITE that names one is either a form built
     * against a different catalogue or somebody editing the payload, and accepting it would put a
     * vocabulary nobody agreed to onto an edge — where it becomes a column indistinguishable from a
     * declared one.
     *
     * <p>An environment already recorded on some edge is accepted even if undeclared, because the
     * form legitimately renders it in order to let somebody clear it.
     *
     * <p>Each value may be a single host or a list of them. A string is split on commas: one input
     * box per environment is what the form shows, and a project on two hosts in one environment has
     * to be expressible in it.
     *
     * @throws IllegalArgumentException naming the offending environment
     */
    private static Map<String, List<String>> submittedDomains(Object raw,
            List<InventoryService.EndpointEnvironment> catalogue) {
        Map<String, List<String>> domains = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> submitted)) {
            // Absent, not empty. An editor that sends no `domains` key at all — an older client, or a
            // form that does not touch endpoints — leaves every environment exactly as it is; the
            // service only writes the environments the map names.
            return domains;
        }
        Set<String> acceptable = new LinkedHashSet<>();
        for (InventoryService.EndpointEnvironment environment : catalogue) {
            if (environment.active() || environment.recorded()) {
                acceptable.add(environment.code());
            }
        }
        for (Map.Entry<?, ?> entry : submitted.entrySet()) {
            String environment = String.valueOf(entry.getKey()).strip();
            if (!acceptable.contains(environment)) {
                throw new IllegalArgumentException("no environment " + environment
                        + " is declared in this tenant. Declare it in the endpoint environment "
                        + "catalogue before recording a host in it.");
            }
            List<String> hosts = new ArrayList<>();
            if (entry.getValue() instanceof List<?> list) {
                for (Object host : list) {
                    addHost(hosts, host);
                }
            } else {
                for (String host : String.valueOf(
                        entry.getValue() == null ? "" : entry.getValue()).split(",", -1)) {
                    addHost(hosts, host);
                }
            }
            domains.put(environment, List.copyOf(hosts));
        }
        return domains;
    }

    /** Trimmed, blanks dropped, duplicates dropped. Order is the order somebody typed. */
    private static void addHost(List<String> hosts, Object raw) {
        String host = raw == null ? "" : String.valueOf(raw).strip();
        if (!host.isEmpty() && !hosts.contains(host)) {
            hosts.add(host);
        }
    }

    private static String repositoryOf(List<InventoryService.Related> related) {
        return related.stream().filter(r -> "REPOSITORY".equals(r.typeCode()))
                .map(InventoryService.Related::name).findFirst().orElse("");
    }

    /** The branch this asset is built from, carried on the edge rather than on the repository. */
    private static String branchOf(List<InventoryService.Related> related) {
        return related.stream().filter(r -> "REPOSITORY".equals(r.typeCode()))
                .map(InventoryService.Related::branch).filter(Objects::nonNull)
                .findFirst().orElse("");
    }

    private static UUID actorOf(Principal principal) {
        return principal == null ? null : principal.principalId();
    }

    private static Dispatcher.Response json(Map<String, Object> body) {
        return new Dispatcher.Response(200, body, Map.of());
    }

    /** A refusal a form can act on: the code for logic, the field for focus, the message for a person. */
    private static Dispatcher.Response rejected(String code, String field, String message) {
        return new Dispatcher.Response(400,
                Map.of("code", code, "field", field, "message", message), Map.of());
    }

    private static Dispatcher.Response stale(String message) {
        return new Dispatcher.Response(409, Map.of("code", "STALE", "message", message), Map.of());
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String out = String.valueOf(value).strip();
        return out.isEmpty() ? null : out;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String raw = text(value);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID uuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
