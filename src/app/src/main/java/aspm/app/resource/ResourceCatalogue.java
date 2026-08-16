package aspm.app.resource;

import static aspm.app.resource.ResourceGroup.ColumnKind.BOOLEAN;
import static aspm.app.resource.ResourceGroup.ColumnKind.INTEGER;
import static aspm.app.resource.ResourceGroup.ColumnKind.JSON;
import static aspm.app.resource.ResourceGroup.ColumnKind.TEXT;
import static aspm.app.resource.ResourceGroup.ColumnKind.TEXT_ARRAY;
import static aspm.app.resource.ResourceGroup.ColumnKind.TIMESTAMP;
import static aspm.app.resource.ResourceGroup.ColumnKind.UUID;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The resource groups the platform serves. DOC-05 §12 onward.
 *
 * <p><b>Six of roughly twenty.</b> DOC-05 specifies over a hundred operations; this is the subset whose
 * modules and schema exist and whose semantics are the generic collection, retrieval, creation and update
 * of {@link ResourceEndpoint}. The operations it does <i>not</i> cover are named in
 * {@code deploy/README.md} rather than left to be inferred from a short list — reorganization sagas,
 * graph traversal with per-node filtering, bulk operations, merges, state transitions, and everything
 * asynchronous. Each needs behaviour a descriptor cannot express, which is the honest reason they are
 * absent rather than a matter of time.
 *
 * <h2>Every column is a decision</h2>
 *
 * <p>The projection is what the query selects. A column absent from it is absent from the SQL, so the
 * failure mode of forgetting a sensitive column is that it is never exposed — the opposite of
 * {@code SELECT *} with a filter, where the failure mode is exposure.
 *
 * <p>Two examples worth stating, because they look like omissions:
 *
 * <ul>
 *   <li>{@code asset.attributes} is not exposed. It is tenant-defined custom fields and can hold anything
 *       a tenant puts there, including material an attribute-level permission should gate
 *       ({@code SEC-AUZ-022}). Exposing it wholesale would return fields nobody authorized individually.
 *   <li>{@code finding.description} and {@code raw_source_record_ref} are not exposed. Finding content is
 *       attacker-authored by design — it is the fifth highest-risk surface — and returning it needs the
 *       evidence-handling path of DOC-15 §6, not a JSON string field.
 * </ul>
 */
public final class ResourceCatalogue {

    private ResourceCatalogue() {
    }

    /** A group whose insert is exactly the body. Named rather than repeated as an empty lambda. */
    private static final java.util.function.Function<Map<String, Object>, Map<String, Object>>
            NOTHING_DERIVED = body -> Map.of();

    /**
     * The identity rule a new asset type gets.
     *
     * <p>{@code asset_type.identity_rule} is NOT NULL and describes which attributes resolve to the
     * natural key. Every type this tenant ships carries the same one, and it is the rule
     * {@link aspm.app.inventory.InventoryService#identityKey} already implements — so a type created
     * over the API resolves identity the way a type created in the interface does. A second rule would
     * mean two assets with the same name under different types deduplicating differently, which
     * {@code INV-AST-06} makes a correctness question rather than a preference.
     *
     * <p>It is not writable by the caller because there is no second value to choose: the resolver
     * understands this rule and no other, so accepting an arbitrary one would store a description the
     * code does not honour.
     */
    private static final ResourceGroup.JsonValue DEFAULT_IDENTITY_RULE = new ResourceGroup.JsonValue(
            "{\"version\": 1, \"natural_key_attributes\": [\"display_name\"]}");

    /**
     * The columns an asset needs that its request body cannot carry.
     *
     * <p><b>Why this exists.</b> {@code POST /api/v1/assets} could not succeed. Six columns on
     * {@code asset} are NOT NULL with no default and none of them was in the writable set, so the
     * insert the endpoint built was refused by the engine on the first one it reached — the endpoint
     * was registered, documented, annotated, permission-gated, and had never inserted a row.
     *
     * <p><b>Why the caller does not supply them.</b> Each is provenance or identity, and both are
     * claims the platform makes rather than claims it accepts:
     *
     * <ul>
     *   <li>{@code identity_key} is the resolved natural key that {@code INV-AST-06} makes unique per
     *       type. A caller-supplied key lets two names collapse to one asset, or one asset split into
     *       two, and deduplication is the thing ADR-011 keeps on a single path.
     *   <li>{@code identity_rule_version} records which rule produced that key, so it can be
     *       re-resolved when the rule changes ({@code PRD-AST-006}).
     *   <li>{@code discovery_source} and {@code discovery_method} are how this asset came to be known.
     *       PP-1 turns on the difference between measured and assumed, and a caller that could write
     *       "CONNECTOR" over a manual creation would erase exactly that difference.
     *   <li>{@code first_seen_at} and {@code last_confirmed_at} are coverage signals; {@code INV-AST-12}
     *       already forbids a manual edit from advancing the second one.
     * </ul>
     *
     * <p>The scope descriptor columns are deliberately not set here. They are resolved at ownership
     * assignment (DOC-04 §11.3.2) and immutable afterwards ({@code CON-DAT-009}), and nothing on this
     * path resolves an ancestor path; leaving them null is honest, whereas writing a partial descriptor
     * would be a historical record that cannot be corrected later. Live scope comes from
     * {@code owning_node_id}, which the body does supply.
     */
    private static Map<String, Object> completeAsset(Map<String, Object> body) {
        Object name = body.get("display_name");
        if (name == null || String.valueOf(name).isBlank()) {
            throw new IllegalArgumentException(
                    "display_name is required: it is what the asset's identity is resolved from");
        }
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("identity_key",
                aspm.app.inventory.InventoryService.identityKey(String.valueOf(name)));
        derived.put("identity_rule_version", Integer.valueOf(1));
        derived.put("discovery_source", "MANUAL");
        derived.put("discovery_method", "API_CREATE");
        derived.put("first_seen_at", ResourceGroup.SqlDefault.NOW);
        derived.put("last_confirmed_at", ResourceGroup.SqlDefault.NOW);
        return derived;
    }

    private static Map<String, ResourceGroup.ColumnKind> columns(Object... pairs) {
        Map<String, ResourceGroup.ColumnKind> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (ResourceGroup.ColumnKind) pairs[i + 1]);
        }
        return map;
    }

    /**
     * Organization node types. Tenant-wide configuration, so <b>not</b> scoped: every principal holding
     * the permission sees every type, which is correct because a type is the vocabulary of the hierarchy
     * rather than a thing inside it ({@code CFG-ORG-001}, {@code PRD-ORG-004}).
     */
    public static final ResourceGroup ORG_NODE_TYPES = new ResourceGroup(
            "org-node-types", "org_node_type", Optional.empty(), Set.of(), "ordinal",
            columns("id", UUID, "code", TEXT, "label_i18n", JSON, "ordinal", INTEGER,
                    "may_own_assets", BOOLEAN, "may_scope_work", BOOLEAN,
                    "lifecycle_state", TEXT, "row_version", INTEGER),
            Set.of("code", "lifecycle_state"),
            Set.of("code", "label_i18n", "ordinal", "may_own_assets", "may_scope_work"),
            Set.of("label_i18n", "ordinal", "lifecycle_state"),
            NOTHING_DERIVED,
            "org.nodetype.read", "org.nodetype.manage", "org.nodetype.manage");

    /** Criticality tiers. Tenant-wide configuration, same reasoning. */
    public static final ResourceGroup CRITICALITY_TIERS = new ResourceGroup(
            "criticality-tiers", "criticality_tier", Optional.empty(), Set.of(), "ordinal",
            columns("id", UUID, "code", TEXT, "label_i18n", JSON, "ordinal", INTEGER,
                    "lifecycle_state", TEXT, "row_version", INTEGER),
            Set.of("code", "lifecycle_state"),
            Set.of("code", "label_i18n", "ordinal"),
            Set.of("label_i18n", "ordinal", "lifecycle_state"),
            NOTHING_DERIVED,
            "org.nodetype.read", "org.nodetype.manage", "org.nodetype.manage");

    /** Asset types. Tenant-wide configuration. */
    public static final ResourceGroup ASSET_TYPES = new ResourceGroup(
            "asset-types", "asset_type", Optional.empty(), Set.of(), "ordinal",
            columns("id", UUID, "code", TEXT, "label_i18n", JSON, "ordinal", INTEGER,
                    "is_network_reachable", BOOLEAN, "may_carry_findings", BOOLEAN,
                    "lifecycle_state", TEXT, "row_version", INTEGER),
            Set.of("code", "lifecycle_state"),
            Set.of("code", "label_i18n", "ordinal", "is_network_reachable", "may_carry_findings"),
            Set.of("label_i18n", "ordinal", "lifecycle_state"),
            body -> Map.of("identity_rule", DEFAULT_IDENTITY_RULE),
            "ast.assettype.read", "ast.assettype.manage", "ast.assettype.manage");

    /**
     * Organization nodes. <b>Scoped by their own identifier</b>: a principal granted a node reaches that
     * node and its subtree, and the closure expansion in {@code RequestScope} is what turns the grant into
     * the permitted set. A node outside it is absent, not forbidden.
     *
     * <p>The reorganization operations of DOC-05 §12 — move, merge, split — are absent. They are
     * asynchronous because they touch thousands of closure rows, and DOC-05 records that as a deliberate
     * deviation; a synchronous PATCH that changed {@code parent_id} would do the same work inside a
     * request budget and leave the closure inconsistent if it failed halfway. So {@code parent_id} is not
     * updatable here, and the absence is the control.
     */
    public static final ResourceGroup ORG_NODES = new ResourceGroup(
            "org-nodes", "org_node", Optional.of("id"), Set.of("parent_id"), "name",
            columns("id", UUID, "type_id", UUID, "parent_id", UUID, "name", TEXT,
                    "external_reference", TEXT, "criticality_mode", TEXT, "criticality_tier_id", UUID,
                    "lifecycle_state", TEXT, "tags", TEXT_ARRAY, "created_at", TIMESTAMP,
                    "updated_at", TIMESTAMP, "row_version", INTEGER),
            Set.of("parent_id", "type_id", "lifecycle_state"),
            Set.of("type_id", "parent_id", "name", "external_reference", "criticality_mode"),
            Set.of("name", "external_reference", "lifecycle_state"),
            NOTHING_DERIVED,
            "org.node.read", "org.node.create", "org.node.update");

    /**
     * Assets. Scoped by {@code owning_node_id}.
     *
     * <p><b>It was {@code scope_node_id}, and that was the wrong column.</b> The embedded scope
     * descriptor records scope <em>as it was</em> and {@code CON-DAT-009} makes it immutable after
     * insert, so it cannot follow an asset that changes owner — a live authorization decision taken on
     * it keeps showing the asset to the division that used to own it and never to the one that does.
     * DOC-04 §11.3.2 resolves the descriptor "at ownership assignment" and names
     * {@code ix_asset__owner_state} on {@code (tenant_id, owning_node_id, lifecycle_state)} as the index
     * serving scope-filtered reads, which is this query.
     *
     * <p>It was also a <em>second</em> enforcement point: the interface has always scoped assets on
     * {@code owning_node_id}, so two doors onto the same rows disagreed about which column carried the
     * answer, and the two are written by different code paths. Measured on real data before the change:
     * of 67 assets, 42 had an owner and no descriptor and 20 had a descriptor and no owner — each half
     * invisible through one of the two doors, silently, which is the PP-1 failure this platform exists
     * to detect in other people's systems. {@code CON-PLT-009} forbids the weaker second point; one
     * column for one question is {@code PP-10}.
     *
     * <p>The descriptor columns stay on the row and stay immutable. They answer a different question —
     * who owned this when the record was made — and nothing here reads them.
     *
     * <p>{@code exposure_observed} is exposed and not writable: DOC-05 §13 says the observed value is not
     * settable via the API, because {@code INV-AST-08} treats observed-conflicting-with-declared as a
     * conflict rather than silently correcting the declaration. {@code last_confirmed_at} is exposed and
     * not writable for the same reason ({@code INV-AST-12}).
     */
    public static final ResourceGroup ASSETS = new ResourceGroup(
            "assets", "asset", Optional.of("owning_node_id"), Set.of("owning_node_id"), "display_name",
            columns("id", UUID, "type_id", UUID, "display_name", TEXT, "owning_node_id", UUID,
                    "criticality_mode", TEXT, "criticality_tier_id", UUID,
                    "exposure_declared", TEXT, "exposure_observed", TEXT, "exposure_conflict", BOOLEAN,
                    "lifecycle_state", TEXT, "tags", TEXT_ARRAY, "first_seen_at", TIMESTAMP,
                    "last_confirmed_at", TIMESTAMP, "row_version", INTEGER),
            Set.of("type_id", "owning_node_id", "lifecycle_state", "exposure_declared",
                    "exposure_conflict", "criticality_tier_id"),
            Set.of("type_id", "display_name", "owning_node_id", "criticality_mode", "exposure_declared"),
            // type_id is absent: DOC-05 §13 makes it immutable, and an asset whose type changed would
            // have a different identity rule and therefore a different identity (INV-AST-*).
            Set.of("display_name", "owning_node_id", "lifecycle_state"),
            ResourceCatalogue::completeAsset,
            "ast.asset.read", "ast.asset.create", "ast.asset.update");

    /**
     * Findings. <b>Read-only over this path.</b> ADR-011 makes normalization and deduplication one
     * pipeline shared by file import and native matching, so a finding created through a REST POST would
     * bypass fingerprinting and become a duplicate nothing reconciles. Creation is ingestion's, and
     * {@code writableOnCreate} is empty rather than absent so the refusal is visible in the descriptor.
     *
     * <p>{@code description} and {@code raw_source_record_ref} are not exposed. Finding content is
     * attacker-authored by design — indirect prompt injection through ingested findings is the fifth
     * highest-risk surface — and serving it belongs to the evidence path of {@code OPS-DEP-016}, from a
     * distinct origin, not to a JSON field on the application origin.
     */
    public static final ResourceGroup FINDINGS = new ResourceGroup(
            "findings", "finding", Optional.of("scope_node_id"), Set.of(), "created_at",
            columns("id", UUID, "finding_class", TEXT, "title", TEXT,
                    "reported_severity_id", UUID, "effective_severity_id", UUID,
                    "state", TEXT, "closure_reason", TEXT, "assignee_id", UUID,
                    "recurrence_count", INTEGER, "source_tool", TEXT,
                    "first_detected_at", TIMESTAMP, "last_detected_at", TIMESTAMP,
                    "created_at", TIMESTAMP, "row_version", INTEGER),
            Set.of("finding_class", "state", "assignee_id", "source_tool"),
            Set.of(),
            Set.of("assignee_id"),
            NOTHING_DERIVED,
            "vul.finding.read", "vul.finding.triage", "vul.finding.triage");

    /**
     * Assessment requests — the intake surface. DOC-05 §14.1, DOC-04 §12.2.
     *
     * <p>Read-only over this path, and the reason is {@code INV-ASM-07}: a submitted request has a
     * resolved scope descriptor and a draft has none, so submission is the act that resolves it. A REST
     * create would have to either resolve scope for a draft — which the invariant forbids — or write a
     * request nobody can authorize a read of. {@code writableOnCreate} is empty rather than absent so the
     * refusal is visible in the descriptor.
     *
     * <p>The derived columns are exposed and not updatable. {@code INV-ASM-08}: "derived, never set by a
     * client. No API surface writes these columns; the estimation job does." Exposing them is what makes
     * a priority a reader can check rather than a number they must trust.
     *
     * <p>{@code classification} and {@code technical_profile} are JSONB and are <b>not</b> exposed. They
     * carry tenant-defined intake fields, and returning them wholesale would return fields nobody
     * authorized individually — the same reasoning that keeps {@code asset.attributes} out.
     */
    public static final ResourceGroup REQUESTS = new ResourceGroup(
            "requests", "assessment_request", Optional.of("scope_node_id"), Set.of(), "request_code",
            columns("id", UUID, "request_code", TEXT, "type_id", UUID, "group_id", UUID,
                    "state", TEXT, "requested_org_node_id", UUID,
                    "readiness_environment_available", BOOLEAN,
                    "readiness_accounts_provisioned", BOOLEAN,
                    "readiness_data_seeded", BOOLEAN,
                    "readiness_contact_available", BOOLEAN,
                    "readiness_attested_at", TIMESTAMP,
                    "derived_priority_score", INTEGER, "derived_effort_days", TEXT,
                    "derived_feasible_start", TEXT,
                    "is_retest", BOOLEAN, "revision_identifier", TEXT,
                    "requested_by", UUID, "submitted_at", TIMESTAMP,
                    "created_at", TIMESTAMP, "row_version", INTEGER),
            Set.of("state", "is_retest", "type_id"),
            Set.of(),
            // State changes are workflow transitions with guards (DOC-09), not field writes. A PATCH on
            // `state` would bypass every guard the transition carries, so the column is exposed and
            // not updatable.
            Set.of(),
            NOTHING_DERIVED,
            "asm.request.read", "asm.request.create", "asm.request.update");

    /** Every group, in the order DOC-05 introduces them. */
    public static List<ResourceGroup> all() {
        return List.of(ORG_NODE_TYPES, CRITICALITY_TIERS, ORG_NODES, ASSET_TYPES, ASSETS,
                FINDINGS, REQUESTS);
    }
}
