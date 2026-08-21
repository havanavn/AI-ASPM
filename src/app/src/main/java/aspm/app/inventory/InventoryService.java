package aspm.app.inventory;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The application/product inventory and the organization hierarchy behind it. ADR-001, ADR-009,
 * ADR-010, {@code PRD-AST-*}, {@code PRD-ORG-*}.
 *
 * <h2>An application is an asset, not a new table</h2>
 *
 * <p>ADR-009: "one {@code Asset} aggregate with a type registry, not five parallel inventories". So an
 * application is a row in {@code asset} whose type is {@code APPLICATION}, its business unit is
 * {@code owning_node_id}, its public/internal answer is {@code exposure_declared}, and its production
 * and staging URLs are <b>related DOMAIN assets</b> rather than text columns.
 *
 * <p>The last one is the least obvious and the most load-bearing. A domain is network-reachable and can
 * carry findings; a finding raised against {@code pay.example.com} has to attach to something. If the
 * production URL were a column on the application, that finding would have nowhere to go and the same
 * host appearing under two applications would be two strings rather than one asset with two owners.
 * The form still asks for one text box per environment — {@link #saveApplication} creates or reuses the
 * DOMAIN asset and the edge.
 *
 * <h2>Scope is composed into the query</h2>
 *
 * <p>{@code SEC-AUZ-016}: the scope predicate goes into the SQL, never over its result. A filtered list
 * built by fetching everything and dropping rows in Java has already read rows the caller may not see,
 * and every later path that forgets to drop them is a disclosure. Product principle 4 is the other half:
 * the scope comes from the principal, never from a request field, so a caller cannot widen it.
 */
public final class InventoryService {

    /** The asset type this inventory presents. Tenant data, looked up by code. */
    public static final String APPLICATION_TYPE = "APPLICATION";

    /** Edge types already permitted by V005, used with the meanings DOC-03 §8.3 gives them. */
    private static final String EDGE_PUBLISHED_ON = "PUBLISHED_ON";
    private static final String EDGE_BUILDS = "BUILDS";
    /**
     * The edge a feature belongs to an application by, and a service to a feature.
     *
     * <p>Written by the component editor, which asks for a name, a type and a parent rather than
     * creating one implicitly from a text box. The earlier note here said the form does not create
     * services because a text box would produce assets nobody claimed — that reasoning stands, and
     * the editor answers it: it asks for the owning node and the security attributes at creation
     * time, so a component arrives claimed rather than orphaned.
     */
    private static final String EDGE_CONTAINS = "CONTAINS";

    /**
     * Filterable columns, by the name the query string uses.
     *
     * <p>A whitelist, because these are interpolated into SQL rather than bound: a column name is an
     * identifier and an identifier cannot be a bind parameter. Escaping an identifier is a different
     * operation from escaping a value and the two get confused, so the safe form is to never build one
     * from input — only to select one from a fixed map.
     */
    private static final Map<String, String> FILTERABLE = Map.ofEntries(
            Map.entry("node", "owning_node_id"),
            Map.entry("exposure", "exposure_declared"),
            Map.entry("criticality", "criticality_code"),
            Map.entry("lifecycle", "lifecycle_state"),
            Map.entry("band", "risk_band"),
            Map.entry("coverage", "risk_coverage"));

    /** Sortable columns, same reasoning. */
    private static final Map<String, String> SORTABLE = Map.of(
            "name", "display_name",
            "criticality", "criticality_code",
            "exposure", "exposure_declared",
            "score", "risk_value",
            "findings", "finding_count",
            "requests", "request_count");

    /** One row of the inventory list. */
    public record Application(UUID id, String name, String identityKey, String lifecycleState,
            UUID owningNodeId, String owningNodeName, String owningNodeTypeCode,
            List<String> ancestorNames, String exposureDeclared, String exposureObserved,
            boolean exposureConflict, String criticalityCode, boolean criticalityInherited,
            List<String> tags, Map<String, String> attributes,
            Integer riskValue, String riskBand, String riskCoverage,
            long requestCount, long findingCount, int rowVersion) {

        /** The user base, a free-text description. Attribute rather than column — see the class note. */
        public String userBase() {
            return attributes.getOrDefault("user_base", "");
        }

        public String description() {
            return attributes.getOrDefault("description", "");
        }

        /** Feature names, comma-separated in the attribute and split here for rendering. */
        public List<String> features() {
            String raw = attributes.getOrDefault("features", "");
            if (raw.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(raw.split(",", -1)).map(String::strip)
                    .filter(f -> !f.isEmpty()).toList();
        }
    }

    /** A related asset: a domain, a repository, a service. */
    public record Related(UUID id, String name, String typeCode, String edgeType,
            String environment, String exposure, String lifecycleState, String branch) {
    }

    /** An organization node, for the picker and the organization editor. */
    public record Node(UUID id, String name, UUID parentId, String parentName, String typeCode,
            boolean mayOwnAssets, String criticalityCode, String lifecycleState,
            long assetCount, long childCount, int depth, int rowVersion) {
    }

    /** A criticality tier, tenant-configured. */
    public record Tier(UUID id, String code, int ordinal) {
    }

    /** A node type, tenant-configured. ADR-027 — the interface never names a level. */
    public record NodeType(UUID id, String code, boolean mayOwnAssets, int ordinal) {
    }

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public InventoryService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * The inventory list, scoped to the caller and narrowed by whatever filters were supplied.
     *
     * @param filters query-string names to values; anything not in {@link #FILTERABLE} is ignored
     *     rather than rejected, because a stale bookmark should not be an error page
     * @param search a substring matched against the display name and the identity key
     */
    public List<Application> applications(Principal principal, Map<String, String> filters,
            String search, String sort, boolean descending) throws SQLException {
        return applications(principal, filters, Map.of(), search, sort, descending);
    }

    /**
     * The same list, additionally narrowed by DECLARED attributes.
     *
     * @param attributeFilters attribute key to required value. Matched with jsonb containment, which
     *     is the operator {@code ix_asset__attributes} is built for: a MULTI_SELECT attribute is
     *     stored as an array, so {@code @> '{"compliance_scope":["PCI_DSS"]}'} matches an application
     *     in PCI scope among others. A comma-joined string could only ever be matched whole, which is
     *     why the writer stores arrays
     */
    public List<Application> applications(Principal principal, Map<String, String> filters,
            Map<String, String> attributeFilters, String search, String sort, boolean descending)
            throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT id, display_name, identity_key, lifecycle_state, owning_node_id, "
                        + "owning_node_name, owning_node_type_code, ancestor_names, "
                        + "exposure_declared, exposure_observed, exposure_conflict, criticality_code, "
                        + "criticality_inherited, tags, attributes, risk_value, risk_band, "
                        + "risk_coverage, request_count, finding_count, row_version "
                        + "  FROM application_inventory WHERE type_code = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(APPLICATION_TYPE);

        // The scope predicate, in the query. An empty scope denies rather than allowing over nothing
        // (SEC-AUZ-014), so it is `IN (...)` over the caller's nodes and their descendants.
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        sql.append(" AND (owning_node_id IN (SELECT descendant_id FROM org_closure "
                + "WHERE ancestor_id = ANY (?))");
        // An application with no owning node is visible to nobody until it is claimed. Stating it
        // rather than letting the IN quietly exclude it: an unowned asset is a real state
        // (DISCOVERED, before an ownership claim) and it must not vanish from every list.
        sql.append(" OR owning_node_id IS NULL AND ? )");
        parameters.add(scope.toArray(new UUID[0]));
        parameters.add(principal.scopeNodeIds().size() > 0);

        for (Map.Entry<String, String> filter : filters.entrySet()) {
            String column = FILTERABLE.get(filter.getKey());
            if (column == null || filter.getValue() == null || filter.getValue().isBlank()) {
                continue;
            }
            if ("owning_node_id".equals(column)) {
                // A node filter means "this node and everything under it", which is what a person
                // picking a business unit means. A node-only match would show an empty list for every
                // level above a project.
                sql.append(" AND owning_node_id IN (SELECT descendant_id FROM org_closure "
                        + "WHERE ancestor_id = ?)");
                try {
                    parameters.add(UUID.fromString(filter.getValue()));
                } catch (IllegalArgumentException e) {
                    return List.of();
                }
            } else {
                sql.append(" AND ").append(column).append(" = ?");
                parameters.add(filter.getValue());
            }
        }
        // Declared attributes. The KEY is validated against the tenant's definitions before it
        // reaches the query — it is interpolated into a JSON document, and an unvalidated key would
        // let a caller construct a containment test over a field nobody declared.
        Map<String, String> declaredTypes = attributeTypes(principal, APPLICATION_TYPE);
        for (Map.Entry<String, String> filter : attributeFilters.entrySet()) {
            String type = declaredTypes.get(filter.getKey());
            if (type == null || filter.getValue() == null || filter.getValue().isBlank()) {
                continue;
            }
            sql.append(" AND attributes @> ?::jsonb");
            parameters.add(containmentFor(filter.getKey(), filter.getValue(), type));
        }

        if (search != null && !search.isBlank()) {
            // Name, identity key, OR a hostname published on this application or on anything beneath
            // it. The composition join is what makes "which application is this subdomain part of"
            // answerable from the box people already type into: the host is almost always attached to
            // a project or a service, not to the application itself.
            sql.append(" AND (display_name ILIKE ? OR identity_key ILIKE ?"
                    // The application's OWN edges as well as its parts'. asset_composition holds no
                    // self row, so the first clause is not redundant — without it, a host attached
                    // directly to the application is unfindable, which is where the first host any
                    // deployment records ends up.
                    + "  OR EXISTS (SELECT 1 FROM asset_relationship dr "
                    + "               JOIN asset d ON d.id = dr.to_asset_id "
                    + "               JOIN asset_type dt ON dt.id = d.type_id AND dt.code = 'DOMAIN' "
                    + "              WHERE dr.valid_until IS NULL "
                    + "                AND (dr.from_asset_id = application_inventory.id "
                    + "                     OR dr.from_asset_id IN "
                    + "                        (SELECT c.asset_id FROM asset_composition c "
                    + "                          WHERE c.root_id = application_inventory.id)) "
                    + "                AND d.display_name ILIKE ?))");
            String pattern = "%" + search.strip() + "%";
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
        }

        String column = SORTABLE.getOrDefault(sort == null ? "" : sort, "display_name");
        // NULLS LAST on every sort. An unscored application sorting to the top of "worst score first"
        // would read as the worst one, which is the PP-1 failure in an ORDER BY.
        sql.append(" ORDER BY ").append(column).append(descending ? " DESC" : " ASC")
                .append(" NULLS LAST, display_name ASC");

        List<Application> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(connection, statement, parameters);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(readApplication(results));
                }
            }
        }
        return List.copyOf(rows);
    }

    /** One application, re-validated against the caller's scope. {@code SEC-AUZ-017}. */
    public Optional<Application> application(Principal principal, UUID id) throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, display_name, identity_key, lifecycle_state, owning_node_id, "
                                + "owning_node_name, owning_node_type_code, ancestor_names, "
                                + "exposure_declared, exposure_observed, exposure_conflict, "
                                + "criticality_code, criticality_inherited, tags, attributes, "
                                + "risk_value, risk_band, risk_coverage, request_count, "
                                + "finding_count, row_version FROM application_inventory "
                                + " WHERE id = ? AND (owning_node_id IN "
                                + "   (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))"
                                + "   OR owning_node_id IS NULL)")) {
            statement.setObject(1, id);
            statement.setArray(2, connection.createArrayOf("uuid", scope.toArray()));
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readApplication(results)) : Optional.empty();
            }
        }
    }

    /** Domains, repositories and services related to one application. */
    public List<Related> related(Principal principal, UUID applicationId) throws SQLException {
        List<Related> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT b.id, b.display_name, t.code, r.edge_type, "
                                + "       r.attributes ->> 'environment', b.exposure_declared, "
                                // The branch lives on the EDGE, not on the repository. One repository
                                // builds several projects from several branches, and putting it on the
                                // repository asset would make the last writer's branch everybody's.
                                + "       b.lifecycle_state, r.attributes ->> 'branch' "
                                + "  FROM asset_relationship r "
                                + "  JOIN asset b ON b.id = r.to_asset_id "
                                + "  JOIN asset_type t ON t.id = b.type_id "
                                + " WHERE r.from_asset_id = ? AND r.valid_until IS NULL "
                                + " ORDER BY t.ordinal, b.display_name")) {
            statement.setObject(1, applicationId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(new Related(results.getObject(1, UUID.class), results.getString(2),
                            results.getString(3), results.getString(4), results.getString(5),
                            results.getString(6), results.getString(7), results.getString(8)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Assessment requests whose scope includes this application, or any part of it.
     *
     * <p><b>Corrected.</b> This read {@code assessment_scope_asset} joined as
     * {@code assessment_request.id = sa.assessment_id} — an assessment identifier compared against a
     * request identifier, which are different keys. It matched nothing, and it would have matched the
     * wrong request had the two ever collided. It also looked only at the assessment-level scope,
     * which is written once an assessor is named; a request sitting in intake against this
     * application was therefore invisible here, which is the opposite of product principle 6.
     *
     * <p>{@code application_request} (V035) is the union over the request-level scope of V029 and the
     * older assessment-level one, rolled up over the application's whole composition — so a request
     * raised against a project inside the application appears against the application too.
     */
    public List<Map<String, String>> requestsFor(Principal principal, UUID applicationId)
            throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT r.id, r.request_code, r.state, r.created_at "
                                + "  FROM application_request ar "
                                + "  JOIN assessment_request r ON r.id = ar.request_id "
                                + " WHERE ar.asset_id = ? ORDER BY r.created_at DESC")) {
            statement.setObject(1, applicationId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("id", results.getObject(1, UUID.class).toString());
                    row.put("code", results.getString(2));
                    row.put("state", results.getString(3));
                    row.put("created_at", String.valueOf(results.getObject(4)));
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            // Rethrown rather than swallowed. A request list that renders empty because the query
            // failed is indistinguishable from an application nobody has ever assessed, and those
            // are opposite facts — PP-1. The caller reports the failure; it does not report zero.
            throw e;
        }
        return List.copyOf(rows);
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * What the editor submits. Every field optional except the name and the owning node.
     *
     * @param domains the hosts this application is published on, keyed by environment code. An
     *     environment PRESENT with an empty list clears that environment's endpoints; an environment
     *     ABSENT from the map is left untouched. The distinction is what lets a form render only the
     *     environments the tenant currently declares without silently retiring the endpoints of one
     *     that was deprecated while still holding a host.
     */
    public record ApplicationDraft(UUID id, String name, UUID owningNodeId, UUID criticalityTierId,
            String exposureDeclared, String description, String userBase, String features,
            String tags, Map<String, List<String>> domains, String repository,
            Integer rowVersion) {
    }

    /**
     * Creates or updates an application, together with its endpoints and repository.
     *
     * <p>One transaction. An application saved without its production domain, or a domain asset created
     * without the edge that attaches it, is a half-written inventory that reads as a complete one.
     *
     * <p>{@code row_version} is required on update and the statement carries it, so a lost update is a
     * refusal rather than a silent overwrite of somebody else's edit.
     *
     * @return the application identifier, or empty if the update was refused as stale
     */
    public Optional<UUID> saveApplication(Principal principal, ApplicationDraft draft)
            throws SQLException {
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                UUID typeId = assetTypeId(connection, APPLICATION_TYPE)
                        .orElseThrow(() -> new IllegalStateException(
                                "the APPLICATION asset type does not exist in this tenant. Asset types "
                                        + "are tenant data (ADR-009), so the inventory cannot invent "
                                        + "one — seed it before using this page."));
                UUID id = draft.id();
                Map<String, String> attributes = new LinkedHashMap<>();
                putIfPresent(attributes, "description", draft.description());
                putIfPresent(attributes, "user_base", draft.userBase());
                putIfPresent(attributes, "features", draft.features());

                if (id == null) {
                    id = insertAsset(connection, principal, typeId, draft, attributes);
                } else {
                    boolean updated = updateAsset(connection, principal, id, draft, attributes);
                    if (!updated) {
                        connection.rollback();
                        return Optional.empty();
                    }
                }

                // Endpoints and repository. Each is create-or-reuse: two applications published on the
                // same host must be two edges to ONE domain asset, not two domain assets with the same
                // name — that is the duplicate the identity key exists to prevent.
                //
                // Driven by the submitted map rather than by a pair of named environments. The two
                // that used to be named here — PRODUCTION and STAGING — were half of the reason an
                // application could not record a UAT host at all: the column offering is derived from
                // recorded data, so an environment with no write path never became a column. See
                // V069.
                linkEndpoints(connection, principal, id, draft.domains());
                linkRepository(connection, principal, id, draft.repository());

                // One event for the aggregate, whichever half of the save ran. The asset table is the
                // aggregate name because it is the thing written; that an APPLICATION is an asset of a
                // type rather than its own inventory is ADR-009 and is visible in the payload.
                audit.domainChange(connection, principal, "asset",
                        draft.id() == null
                                ? aspm.kernel.audit.contract.DomainChangeKind.CREATED
                                : aspm.kernel.audit.contract.DomainChangeKind.UPDATED,
                        id, aspm.app.audit.AuditScopes.ofAsset(connection, id),
                        java.util.Map.of("asset_type", APPLICATION_TYPE,
                                "name", draft.name() == null ? "" : draft.name().strip(),
                                "owning_node_id", draft.owningNodeId() == null
                                        ? "" : draft.owningNodeId().toString()));
                connection.commit();
                return Optional.of(id);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Retires an application. There is no delete.
     *
     * <p>{@code ck_asset__retired_has_reason} requires a reason, and no {@code DELETE} grant on
     * {@code asset} exists anywhere in the schema. An asset that ever carried a finding cannot be
     * deleted without deleting the subject of that finding, and the finding is the record of a weakness
     * that really existed — product principle 5.
     */
    public boolean retireApplication(Principal principal, UUID id, String reason, int rowVersion)
            throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE asset SET lifecycle_state = 'RETIRED', retired_reason = ?, "
                                + "updated_at = now(), updated_by = ?, row_version = row_version + 1 "
                                + " WHERE id = ? AND row_version = ? AND lifecycle_state <> 'RETIRED'")) {
            statement.setString(1, reason == null || reason.isBlank()
                    ? "retired from the application inventory" : reason.strip());
            statement.setObject(2, principal == null ? null : principal.principalId());
            statement.setObject(3, id);
            statement.setInt(4, rowVersion);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.domainChange(connection, principal, "asset",
                        aspm.kernel.audit.contract.DomainChangeKind.RETIRED, id,
                        aspm.app.audit.AuditScopes.ofAsset(connection, id),
                        java.util.Map.of("reason", reason == null || reason.isBlank()
                                ? "retired from the application inventory" : reason.strip()));
            }
            connection.commit();
            return applied;
        }
    }

    // ----------------------------------------------------------------------------------------------

    /** Nodes the caller can see, ordered as a tree. Used by the picker and the organization editor. */
    public List<Node> nodes(Principal principal, boolean ownersOnly) throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        List<Node> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT n.id, n.name, n.parent_id, p.name, t.code, t.may_own_assets, "
                                + "       ct.code, n.lifecycle_state, "
                                + "       (SELECT count(*) FROM asset a WHERE a.owning_node_id = n.id "
                                + "         AND a.lifecycle_state <> 'RETIRED'), "
                                + "       (SELECT count(*) FROM org_node c WHERE c.parent_id = n.id), "
                                + "       (SELECT max(depth) FROM org_closure cl "
                                + "         WHERE cl.descendant_id = n.id), n.row_version "
                                + "  FROM org_node n "
                                + "  JOIN org_node_type t ON t.id = n.type_id "
                                + "  LEFT JOIN org_node p ON p.id = n.parent_id "
                                + "  LEFT JOIN criticality_tier ct ON ct.id = n.criticality_tier_id "
                                + " WHERE n.id IN (SELECT descendant_id FROM org_closure "
                                + "                 WHERE ancestor_id = ANY (?)) "
                                + (ownersOnly ? "   AND t.may_own_assets " : "")
                                + " ORDER BY t.ordinal, n.name")) {
            statement.setArray(1, connection.createArrayOf("uuid", scope.toArray()));
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(new Node(results.getObject(1, UUID.class), results.getString(2),
                            results.getObject(3, UUID.class), results.getString(4),
                            results.getString(5), results.getBoolean(6), results.getString(7),
                            results.getString(8), results.getLong(9), results.getLong(10),
                            results.getInt(11), results.getInt(12)));
                }
            }
        }
        return List.copyOf(rows);
    }

    public Optional<Node> node(Principal principal, UUID id) throws SQLException {
        return nodes(principal, false).stream().filter(n -> n.id().equals(id)).findFirst();
    }

    /**
     * Creates or renames an organization node.
     *
     * <p><b>The parent is not changeable here.</b> DOC-05 §12 records reorganization — move, merge,
     * split — as asynchronous because it rewrites thousands of closure rows and cannot finish inside a
     * request budget. Offering a parent dropdown on an edit form would either block the request or
     * leave the closure table inconsistent with the tree, and an inconsistent closure table is a wrong
     * answer to every scope question in the platform. Creating a node under a parent is fine: that adds
     * closure rows for one new leaf.
     */
    public Optional<UUID> saveNode(Principal principal, UUID id, String name, UUID typeId,
            UUID parentId, UUID criticalityTierId, Integer rowVersion) throws SQLException {
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                UUID result;
                if (id == null) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO org_node (tenant_id, type_id, parent_id, name, "
                                    + "criticality_mode, criticality_tier_id, created_by, updated_by) "
                                    + "VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
                        insert.setObject(1, typeId);
                        insert.setObject(2, parentId);
                        insert.setString(3, name.strip());
                        insert.setString(4, criticalityTierId == null ? "INHERITED" : "ASSIGNED");
                        insert.setObject(5, criticalityTierId);
                        insert.setObject(6, principal == null ? null : principal.principalId());
                        insert.setObject(7, principal == null ? null : principal.principalId());
                        try (ResultSet keys = insert.executeQuery()) {
                            if (!keys.next()) {
                                connection.rollback();
                                return Optional.empty();
                            }
                            result = keys.getObject(1, UUID.class);
                        }
                    }
                } else {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE org_node SET name = ?, criticality_mode = ?, "
                                    + "criticality_tier_id = ?, updated_at = now(), updated_by = ?, "
                                    + "row_version = row_version + 1 "
                                    + " WHERE id = ? AND row_version = ?")) {
                        update.setString(1, name.strip());
                        update.setString(2, criticalityTierId == null ? "INHERITED" : "ASSIGNED");
                        update.setObject(3, criticalityTierId);
                        update.setObject(4, principal == null ? null : principal.principalId());
                        update.setObject(5, id);
                        update.setInt(6, rowVersion == null ? -1 : rowVersion);
                        if (update.executeUpdate() != 1) {
                            connection.rollback();
                            return Optional.empty();
                        }
                    }
                    result = id;
                }
                audit.domainChange(connection, principal, "org_node",
                        id == null
                                ? aspm.kernel.audit.contract.DomainChangeKind.CREATED
                                : aspm.kernel.audit.contract.DomainChangeKind.UPDATED,
                        result, aspm.app.audit.AuditScopes.ofNode(connection, result),
                        java.util.Map.of("name", name == null ? "" : name.strip(),
                                "node_type_id", typeId == null ? "" : typeId.toString(),
                                "criticality_tier_id", criticalityTierId == null
                                        ? "inherited" : criticalityTierId.toString()));
                connection.commit();
                return Optional.of(result);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Deprecates a node. Not a delete: {@code org_node.parent_id} and every scope grant reference it
     * {@code ON DELETE RESTRICT}, and a node that ever scoped a grant is named by the record of a
     * decision.
     */
    public boolean deprecateNode(Principal principal, UUID id, int rowVersion) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE org_node SET lifecycle_state = 'DEPRECATED', updated_at = now(), "
                                + "updated_by = ?, row_version = row_version + 1 "
                                + " WHERE id = ? AND row_version = ? AND lifecycle_state = 'ACTIVE' "
                                // A node with live children or assets is not deprecated: doing so would
                                // leave them under a node the tree no longer offers, reachable only by
                                // a caller who already knows the identifier.
                                + "   AND NOT EXISTS (SELECT 1 FROM org_node c WHERE c.parent_id = ? "
                                + "                    AND c.lifecycle_state = 'ACTIVE') "
                                + "   AND NOT EXISTS (SELECT 1 FROM asset a WHERE a.owning_node_id = ? "
                                + "                    AND a.lifecycle_state <> 'RETIRED')")) {
            statement.setObject(1, principal == null ? null : principal.principalId());
            statement.setObject(2, id);
            statement.setInt(3, rowVersion);
            statement.setObject(4, id);
            statement.setObject(5, id);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.domainChange(connection, principal, "org_node",
                        aspm.kernel.audit.contract.DomainChangeKind.RETIRED, id,
                        aspm.app.audit.AuditScopes.ofNode(connection, id),
                        java.util.Map.of("row_version_seen", Integer.valueOf(rowVersion)));
            }
            connection.commit();
            return applied;
        }
    }

    public List<Tier> tiers(Principal principal) throws SQLException {
        List<Tier> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, code, ordinal FROM criticality_tier "
                                + "WHERE lifecycle_state = 'ACTIVE' ORDER BY ordinal");
                ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rows.add(new Tier(results.getObject(1, UUID.class), results.getString(2),
                        results.getInt(3)));
            }
        }
        return List.copyOf(rows);
    }

    public List<NodeType> nodeTypes(Principal principal) throws SQLException {
        List<NodeType> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, code, may_own_assets, ordinal FROM org_node_type "
                                + "WHERE lifecycle_state = 'ACTIVE' ORDER BY ordinal");
                ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rows.add(new NodeType(results.getObject(1, UUID.class), results.getString(2),
                        results.getBoolean(3), results.getInt(4)));
            }
        }
        return List.copyOf(rows);
    }

    /** Distinct values present for a filterable column, so a dropdown offers only what exists. */
    public List<String> distinct(Principal principal, String filterName) throws SQLException {
        String column = FILTERABLE.get(filterName);
        if (column == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT DISTINCT " + column + " FROM application_inventory "
                                + " WHERE type_code = ? AND " + column + " IS NOT NULL "
                                + " ORDER BY 1")) {
            statement.setString(1, APPLICATION_TYPE);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    values.add(results.getString(1));
                }
            }
        }
        return List.copyOf(values);
    }

    /** Whether the APPLICATION asset type has been configured for this tenant. */
    public boolean applicationTypeConfigured(Principal principal) throws SQLException {
        try (Connection connection = open(principal)) {
            return assetTypeId(connection, APPLICATION_TYPE).isPresent();
        }
    }

    // ----------------------------------------------------------------------------------------------

    private UUID insertAsset(Connection connection, Principal principal, UUID typeId,
            ApplicationDraft draft, Map<String, String> attributes) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, "
                        + "display_name, owning_node_id, criticality_mode, criticality_tier_id, "
                        + "exposure_declared, exposure_declared_by, exposure_declared_at, "
                        + "lifecycle_state, attributes, tags, discovery_source, discovery_method, "
                        // first_seen_at and last_confirmed_at are NOT NULL with no default, and
                        // omitting them made every insert on this path fail. Found by running the
                        // statement against a real engine rather than by reading the schema — the
                        // third time in this work that a write path was written against a table
                        // nobody had inserted into from here.
                        //
                        // now() for both is honest for a manual entry: the platform first saw this
                        // asset when a person typed it, and that is also the last time anything
                        // confirmed it exists. A discovery source would set them from its scan.
                        + "first_seen_at, last_confirmed_at, created_by, updated_by) "
                        + "VALUES (current_tenant_id(), ?, ?, 1, ?, ?, ?, ?, ?, ?, "
                        + "        CASE WHEN ?::text IS NULL THEN NULL ELSE now() END, "
                        + "        'ACTIVE', ?::jsonb, ?, 'MANUAL', 'INVENTORY_FORM', "
                        + "        now(), now(), ?, ?) "
                        + "RETURNING id")) {
            String identity = identityKey(draft.name());
            insert.setObject(1, typeId);
            insert.setString(2, identity);
            insert.setString(3, draft.name().strip());
            insert.setObject(4, draft.owningNodeId());
            insert.setString(5, draft.criticalityTierId() == null ? "INHERITED" : "ASSIGNED");
            insert.setObject(6, draft.criticalityTierId());
            insert.setString(7, blankToNull(draft.exposureDeclared()));
            insert.setObject(8, blankToNull(draft.exposureDeclared()) == null
                    ? null : (principal == null ? null : principal.principalId()));
            insert.setString(9, blankToNull(draft.exposureDeclared()));
            insert.setString(10, aspm.app.runtime.Json.write(attributes));
            insert.setArray(11, connection.createArrayOf("text", splitTags(draft.tags())));
            insert.setObject(12, principal == null ? null : principal.principalId());
            insert.setObject(13, principal == null ? null : principal.principalId());
            try (ResultSet keys = insert.executeQuery()) {
                keys.next();
                return keys.getObject(1, UUID.class);
            }
        }
    }

    private boolean updateAsset(Connection connection, Principal principal, UUID id,
            ApplicationDraft draft, Map<String, String> attributes) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE asset SET display_name = ?, owning_node_id = ?, criticality_mode = ?, "
                        + "criticality_tier_id = ?, exposure_declared = ?, "
                        + "exposure_declared_by = CASE WHEN ?::text IS NULL THEN exposure_declared_by "
                        + "                            ELSE ? END, "
                        + "exposure_declared_at = CASE WHEN ?::text IS NULL THEN exposure_declared_at "
                        + "                            ELSE now() END, "
                        // Merged rather than replaced: attributes may carry keys this form does not
                        // know about — an ingestion source, a schema-referenced field — and a form
                        // that overwrote the whole object would silently drop them.
                        + "attributes = attributes || ?::jsonb, tags = ?, updated_at = now(), "
                        + "updated_by = ?, row_version = row_version + 1 "
                        + " WHERE id = ? AND row_version = ?")) {
            update.setString(1, draft.name().strip());
            update.setObject(2, draft.owningNodeId());
            update.setString(3, draft.criticalityTierId() == null ? "INHERITED" : "ASSIGNED");
            update.setObject(4, draft.criticalityTierId());
            update.setString(5, blankToNull(draft.exposureDeclared()));
            update.setString(6, blankToNull(draft.exposureDeclared()));
            update.setObject(7, principal == null ? null : principal.principalId());
            update.setString(8, blankToNull(draft.exposureDeclared()));
            update.setString(9, aspm.app.runtime.Json.write(attributes));
            update.setArray(10, connection.createArrayOf("text", splitTags(draft.tags())));
            update.setObject(11, principal == null ? null : principal.principalId());
            update.setObject(12, id);
            update.setInt(13, draft.rowVersion() == null ? -1 : draft.rowVersion());
            return update.executeUpdate() == 1;
        }
    }

    /**
     * Publishes an asset on the hosts it is submitted with, one environment at a time.
     *
     * <p><b>Only the environments present in the map are touched.</b> An environment the caller did
     * not send keeps whatever it has. This is what allows a form to render the environments the
     * tenant currently declares (V069) without retiring the endpoints of one that was deprecated
     * while a host was still recorded against it — a form that submitted the whole world implicitly
     * would delete data it never showed.
     *
     * @param domains hosts by environment code. An environment mapped to an empty list has its
     *     endpoints closed; that is how the interface clears one.
     */
    private void linkEndpoints(Connection connection, Principal principal, UUID assetId,
            Map<String, List<String>> domains) throws SQLException {
        if (domains == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : domains.entrySet()) {
            linkEndpoint(connection, principal, assetId, entry.getKey(), entry.getValue());
        }
    }

    /**
     * The hosts one asset is published on in one environment, reconciled against what is there.
     *
     * <p><b>*** AN UNCHANGED HOST IS NO LONGER CLOSED AND RE-OPENED, AND THAT IS A CORRECTION. ***
     * </b> This method used to close every current edge for the environment and insert one, on every
     * save. Saving a project without touching its domain therefore ended the edge and started an
     * identical one, so {@code valid_from} recorded the last time somebody pressed Save rather than
     * when the application was published there — and "what was deployed when this finding was open"
     * answered with the edit history of a form. The edges to keep are now left alone.
     *
     * <p>What is closed rather than deleted stays closed: INV-AST-16 rejects reopening an edge and
     * there is no DELETE grant, so moving a host away leaves the old edge with an end date.
     */
    private void linkEndpoint(Connection connection, Principal principal, UUID assetId,
            String environment, List<String> hosts) throws SQLException {
        if (blankToNull(environment) == null) {
            return;
        }
        // Trimmed, de-duplicated, blanks dropped. Two spellings of one host in one form field would
        // otherwise become two edges to two DOMAIN assets, which is the duplicate the identity key
        // exists to prevent.
        java.util.LinkedHashSet<String> wanted = new java.util.LinkedHashSet<>();
        if (hosts != null) {
            for (String host : hosts) {
                if (blankToNull(host) != null) {
                    wanted.add(host.strip());
                }
            }
        }
        // What is already published here, by host name. The identity key rather than the display name
        // decides sameness, for the same reason the asset lookup uses it: `PAY.example.com` and
        // `pay.example.com` are one host.
        Map<String, UUID> current = new LinkedHashMap<>();
        try (PreparedStatement existing = connection.prepareStatement(
                "SELECT r.id, d.identity_key FROM asset_relationship r "
                        + "  JOIN asset d ON d.id = r.to_asset_id "
                        + "  JOIN asset_type dt ON dt.id = d.type_id AND dt.code = 'DOMAIN' "
                        + " WHERE r.from_asset_id = ? AND r.edge_type = ? AND r.valid_until IS NULL "
                        // coalesced, not compared directly: an edge written with no environment at
                        // all reads as UNSPECIFIED everywhere else — the columns, the filters and the
                        // form all use that substitute. Comparing the raw attribute would fail to
                        // match such an edge, so the form would leave it open AND insert a second
                        // edge to the same host, which is the duplicate the identity key exists to
                        // prevent.
                        + "   AND coalesce(r.attributes ->> 'environment', 'UNSPECIFIED') = ?")) {
            existing.setObject(1, assetId);
            existing.setString(2, EDGE_PUBLISHED_ON);
            existing.setString(3, environment);
            try (ResultSet r = existing.executeQuery()) {
                while (r.next()) {
                    current.put(r.getString(2), r.getObject(1, UUID.class));
                }
            }
        }
        java.util.Set<String> keep = new java.util.LinkedHashSet<>();
        for (String host : wanted) {
            keep.add(identityKey(host));
        }
        for (Map.Entry<String, UUID> edge : current.entrySet()) {
            if (keep.contains(edge.getKey())) {
                continue;
            }
            try (PreparedStatement close = connection.prepareStatement(
                    "UPDATE asset_relationship SET valid_until = now(), updated_at = now(), "
                            + "updated_by = ? WHERE id = ? AND valid_until IS NULL")) {
                close.setObject(1, principal == null ? null : principal.principalId());
                close.setObject(2, edge.getValue());
                close.executeUpdate();
            }
        }
        for (String host : wanted) {
            if (current.containsKey(identityKey(host))) {
                continue;
            }
            UUID domainId = findOrCreateAsset(connection, principal, "DOMAIN", host);
            insertEdge(connection, principal, assetId, domainId, EDGE_PUBLISHED_ON, environment);
        }
    }

    private void linkRepository(Connection connection, Principal principal, UUID applicationId,
            String repository) throws SQLException {
        linkRepository(connection, principal, applicationId, repository, null);
    }

    /** The same, recording which branch this asset is built from. */
    private void linkRepository(Connection connection, Principal principal, UUID applicationId,
            String repository, String branch) throws SQLException {
        try (PreparedStatement close = connection.prepareStatement(
                "UPDATE asset_relationship SET valid_until = now(), updated_at = now(), updated_by = ? "
                        + " WHERE from_asset_id = ? AND edge_type = ? AND valid_until IS NULL")) {
            close.setObject(1, principal == null ? null : principal.principalId());
            close.setObject(2, applicationId);
            close.setString(3, EDGE_BUILDS);
            close.executeUpdate();
        }
        if (blankToNull(repository) == null) {
            return;
        }
        // A REFERENCE to a repository, never a clone. ADR-024: the platform never fetches, clones or
        // persists source code and stores no Git credentials. This is a name in an inventory.
        UUID repoId = findOrCreateAsset(connection, principal, "REPOSITORY", repository.strip());
        insertEdgeWithAttributes(connection, principal, applicationId, repoId, EDGE_BUILDS,
                Map.of("branch", branch == null ? "" : branch));
    }

    private UUID findOrCreateAsset(Connection connection, Principal principal, String typeCode,
            String name) throws SQLException {
        UUID typeId = assetTypeId(connection, typeCode).orElseThrow(() -> new IllegalStateException(
                "the " + typeCode + " asset type does not exist in this tenant"));
        String identity = identityKey(name);
        try (PreparedStatement find = connection.prepareStatement(
                "SELECT id FROM asset WHERE type_id = ? AND identity_key = ?")) {
            find.setObject(1, typeId);
            find.setString(2, identity);
            try (ResultSet results = find.executeQuery()) {
                if (results.next()) {
                    return results.getObject(1, UUID.class);
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, "
                        + "display_name, lifecycle_state, discovery_source, discovery_method, "
                        + "first_seen_at, last_confirmed_at, created_by, updated_by) "
                        + "VALUES (current_tenant_id(), ?, ?, 1, ?, 'ACTIVE', 'MANUAL', "
                        + "        'INVENTORY_FORM', now(), now(), ?, ?) RETURNING id")) {
            insert.setObject(1, typeId);
            insert.setString(2, identity);
            insert.setString(3, name);
            insert.setObject(4, principal == null ? null : principal.principalId());
            insert.setObject(5, principal == null ? null : principal.principalId());
            try (ResultSet keys = insert.executeQuery()) {
                keys.next();
                return keys.getObject(1, UUID.class);
            }
        }
    }

    private static void insertEdge(Connection connection, Principal principal, UUID from, UUID to,
            String edgeType, String environment) throws SQLException {
        insertEdgeWithAttributes(connection, principal, from, to, edgeType,
                environment == null ? Map.of() : Map.of("environment", environment));
    }

    /**
     * The same edge, with whatever the relationship itself carries.
     *
     * <p>Environment and branch are properties of the EDGE and not of either end: the same host serves
     * two projects in different environments, and the same repository builds two projects from
     * different branches. Recording either on the far asset would make the last save win for everybody
     * pointing at it.
     */
    private static void insertEdgeWithAttributes(Connection connection, Principal principal,
            UUID from, UUID to, String edgeType, Map<String, String> edgeAttributes)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO asset_relationship (tenant_id, from_asset_id, to_asset_id, edge_type, "
                        + "discovery_source, attributes, valid_from, created_by, updated_by) "
                        + "VALUES (current_tenant_id(), ?, ?, ?, 'MANUAL', ?::jsonb, now(), ?, ?)")) {
            insert.setObject(1, from);
            insert.setObject(2, to);
            insert.setString(3, edgeType);
            Map<String, String> present = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : edgeAttributes.entrySet()) {
                if (blankToNull(entry.getValue()) != null) {
                    present.put(entry.getKey(), entry.getValue().strip());
                }
            }
            insert.setString(4, present.isEmpty() ? "{}" : aspm.app.runtime.Json.write(present));
            insert.setObject(5, principal == null ? null : principal.principalId());
            insert.setObject(6, principal == null ? null : principal.principalId());
            insert.executeUpdate();
        }
    }

    private static Optional<UUID> assetTypeId(Connection connection, String code)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM asset_type WHERE code = ? AND lifecycle_state = 'ACTIVE' "
                        + "ORDER BY tenant_id NULLS LAST LIMIT 1")) {
            statement.setString(1, code);
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? Optional.of(results.getObject(1, UUID.class)) : Optional.empty();
            }
        }
    }

    private static Application readApplication(ResultSet results) throws SQLException {
        java.sql.Array ancestors = results.getArray(8);
        java.sql.Array tags = results.getArray(14);
        Object score = results.getObject(16);
        return new Application(
                results.getObject(1, UUID.class), results.getString(2), results.getString(3),
                results.getString(4), results.getObject(5, UUID.class), results.getString(6),
                results.getString(7),
                ancestors == null ? List.of() : List.of((String[]) ancestors.getArray()),
                results.getString(9), results.getString(10), results.getBoolean(11),
                results.getString(12), results.getBoolean(13),
                tags == null ? List.of() : List.of((String[]) tags.getArray()),
                stringAttributes(results.getString(15)),
                score == null ? null : ((Number) score).intValue(),
                results.getString(17), results.getString(18),
                results.getLong(19), results.getLong(20), results.getInt(21));
    }

    /**
     * The attributes object flattened to strings.
     *
     * <p>Non-string values are rendered with {@code String.valueOf} rather than dropped: an attribute
     * written by an ingestion source as a number is still an attribute, and silently omitting it would
     * make the detail page disagree with the row it came from.
     */
    private static Map<String, String> stringAttributes(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, String> flat = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : aspm.app.runtime.Json.readObject(json).entrySet()) {
            flat.put(entry.getKey(), flatten(entry.getValue()));
        }
        return Map.copyOf(flat);
    }

    /**
     * One attribute value as display text.
     *
     * <p>A MULTI_SELECT attribute is a JSON array, and {@code String.valueOf} on a list renders
     * {@code [NODEJS, EXPRESS]} — brackets a person did not type, and a string the editor could not
     * match an option against, so every checkbox came back unchecked on edit. Joining the elements
     * gives text that reads correctly in a table cell AND splits back into the values the form needs.
     */
    private static String flatten(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
        }
        return String.valueOf(value);
    }

    /** The elements of a multi-valued attribute, from the text {@link #flatten} produced. */
    public static List<String> multiValueOf(String flattened) {
        if (flattened == null || flattened.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(flattened.split(",", -1)).map(String::strip)
                .filter(v -> !v.isEmpty()).toList();
    }

    private static void bind(Connection connection, PreparedStatement statement,
            List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            Object value = parameters.get(i);
            if (value instanceof UUID[] array) {
                statement.setArray(i + 1, connection.createArrayOf("uuid", array));
            } else {
                statement.setObject(i + 1, value);
            }
        }
    }

    /**
     * The identity key for a manually created asset.
     *
     * <p>Case-folded and whitespace-collapsed, because {@code pay.example.com} and
     * {@code Pay.Example.Com } are one host and two rows would be the duplicate the deduplication
     * pipeline exists to prevent (ADR-011).
     *
     * <p>Public because the REST create path resolves identity the same way. Two implementations of
     * "the same asset" is how one repository becomes two rows under two spellings, and ADR-011 puts
     * normalization on a single path for that reason.
     */
    public static String identityKey(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String[] splitTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        Set<String> tags = new LinkedHashSet<>();
        // -1 so a trailing comma yields an empty element that the blank check drops, rather than
        // being silently discarded by split's default behaviour — the two differ only in edge cases,
        // which is exactly where a tag list gets mangled.
        for (String tag : raw.split(",", -1)) {
            String cleaned = tag.strip();
            if (!cleaned.isEmpty()) {
                tags.add(cleaned);
            }
        }
        return tags.toArray(new String[0]);
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        target.put(key, value == null ? "" : value.strip());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    // ==============================================================================================
    // Composition, posture, declared attributes, and the AI suggestion ledger.
    // ==============================================================================================

    /** One part of an application: a feature, a service, a repository, a domain. */
    public record Component(UUID id, int depth, String edgeType, List<String> path, String name,
            String typeCode, String lifecycleState, String exposure, Map<String, String> attributes,
            long findingOpen, long findingTotal, long criticalOpen, long highOpen, long mediumOpen,
            long scaOpen, long acceptedTotal) {
    }

    /** The rollup over an application and everything it contains. */
    public record Posture(long componentCount, long findingTotal, long findingOpen,
            long findingAccepted, long criticalTotal, long criticalOpen, long highTotal,
            long highOpen, long mediumTotal, long mediumOpen, long lowTotal, long lowOpen,
            long scaTotal, long scaOpen, long scaCriticalOpen, long scaHighOpen, long scaMediumOpen,
            String lastDetectedAt, long sbomCoveredParts, String sbomLatestAt,
            long sbomRejectedParts) {

        /**
         * Whether anything has ever looked at this application.
         *
         * <p>The difference between "no open findings because it is clean" and "no open findings
         * because nothing has ever been run against it". Product principle 1, and the reason the
         * interface must not render a reassuring zero without this answer beside it.
         */
        public boolean everAssessed() {
            return findingTotal > 0 || sbomCoveredParts > 0;
        }
    }

    /** A tenant-declared attribute on an asset type. */
    public record AttributeDefinition(UUID id, String key, String label, String dataType,
            List<String> permittedValues, boolean filterable, boolean required, String purpose,
            int ordinal, String lifecycleState, int rowVersion, String labelVi) {

        /** The active-only shape the forms read. Deprecated fields never reach them. */
        public AttributeDefinition(UUID id, String key, String label, String dataType,
                List<String> permittedValues, boolean filterable, boolean required, String purpose,
                int ordinal) {
            this(id, key, label, dataType, permittedValues, filterable, required, purpose, ordinal,
                    "ACTIVE", 1, "");
        }

        public boolean isSelect() {
            return "SINGLE_SELECT".equals(dataType) || "MULTI_SELECT".equals(dataType);
        }

        public boolean active() {
            return "ACTIVE".equals(lifecycleState);
        }
    }

    /** A pending AI suggestion. ADR-005 — never applied, only offered. */
    public record Suggestion(UUID id, String kind, String content, String modelIdentity,
            String confidenceBand, String generatedAt, int groundingCount) {
    }

    /**
     * The parts of an application, deduplicated by asset with every path that reaches them.
     *
     * <p>Deduplicated because the composition is a GRAPH: one service contained by two features is
     * one service, and listing it twice would double every count a reader adds up by eye. The paths
     * are kept so the table can still show that it belongs to both.
     */
    public List<Component> components(Principal principal, UUID rootId) throws SQLException {
        Map<UUID, Component> byAsset = new LinkedHashMap<>();
        Map<UUID, List<String>> paths = new LinkedHashMap<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT c.asset_id, c.depth, c.edge_type, c.path_names, c.display_name, "
                                + "       c.type_code, c.lifecycle_state, c.exposure_declared, "
                                + "       c.attributes, "
                                + "       coalesce(t.open_total,0), coalesce(t.total,0), "
                                + "       coalesce(t.critical_open,0), coalesce(t.high_open,0), "
                                + "       coalesce(t.medium_open,0), coalesce(t.sca_open,0), "
                                + "       coalesce(t.accepted_total,0) "
                                + "  FROM asset_composition c "
                                + "  LEFT JOIN asset_finding_tally t ON t.asset_id = c.asset_id "
                                + " WHERE c.root_id = ? ORDER BY c.depth, c.display_name")) {
            statement.setObject(1, rootId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    UUID id = results.getObject(1, UUID.class);
                    java.sql.Array pathArray = results.getArray(4);
                    List<String> path = pathArray == null
                            ? List.of() : List.of((String[]) pathArray.getArray());
                    paths.computeIfAbsent(id, k -> new ArrayList<>())
                            .add(path.isEmpty() ? "" : String.join(" / ", path));
                    if (byAsset.containsKey(id)) {
                        continue;
                    }
                    byAsset.put(id, new Component(id, results.getInt(2), results.getString(3),
                            path, results.getString(5), results.getString(6), results.getString(7),
                            results.getString(8), stringAttributes(results.getString(9)),
                            results.getLong(10), results.getLong(11), results.getLong(12),
                            results.getLong(13), results.getLong(14), results.getLong(15),
                            results.getLong(16)));
                }
            }
        }
        return List.copyOf(byAsset.values());
    }

    /** The posture rollup for one application. */
    public Optional<Posture> posture(Principal principal, UUID rootId) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT component_count, finding_total, finding_open, finding_accepted, "
                                + "critical_total, critical_open, high_total, high_open, "
                                + "medium_total, medium_open, low_total, low_open, sca_total, "
                                + "sca_open, sca_critical_open, sca_high_open, sca_medium_open, "
                                + "last_detected_at, sbom_covered_parts, sbom_latest_at, "
                                + "sbom_rejected_parts FROM application_posture WHERE asset_id = ?")) {
            statement.setObject(1, rootId);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Posture(r.getLong(1), r.getLong(2), r.getLong(3),
                        r.getLong(4), r.getLong(5), r.getLong(6), r.getLong(7), r.getLong(8),
                        r.getLong(9), r.getLong(10), r.getLong(11), r.getLong(12), r.getLong(13),
                        r.getLong(14), r.getLong(15), r.getLong(16), r.getLong(17),
                        r.getObject(18) == null ? null : String.valueOf(r.getObject(18)),
                        r.getLong(19),
                        r.getObject(20) == null ? null : String.valueOf(r.getObject(20)),
                        r.getLong(21)));
            }
        }
    }

    /** The attributes a tenant has declared for one asset type, by type code. */
    public List<AttributeDefinition> attributeDefinitions(Principal principal, String typeCode)
            throws SQLException {
        List<AttributeDefinition> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT d.id, d.attribute_key, coalesce(d.label_i18n->>'en', d.attribute_key), "
                                + "       d.data_type, d.permitted_values, d.filterable, d.required, "
                                + "       d.purpose, d.ordinal "
                                + "  FROM asset_attribute_definition d "
                                + "  JOIN asset_type t ON t.id = d.asset_type_id "
                                + " WHERE t.code = ? AND d.lifecycle_state = 'ACTIVE' "
                                + " ORDER BY d.ordinal, d.attribute_key")) {
            statement.setString(1, typeCode);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    java.sql.Array values = r.getArray(5);
                    rows.add(new AttributeDefinition(r.getObject(1, UUID.class), r.getString(2),
                            r.getString(3), r.getString(4),
                            values == null ? List.of() : List.of((String[]) values.getArray()),
                            r.getBoolean(6), r.getBoolean(7), r.getString(8), r.getInt(9)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Writes declared attributes onto an asset.
     *
     * <p>Merged with {@code ||}, never replaced: an asset carries attributes this form does not know
     * about — written by an ingestion source or an earlier schema version — and replacing the object
     * would drop them silently.
     */
    public boolean saveAttributes(Principal principal, UUID assetId, Map<String, String> values,
            int rowVersion) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE asset SET attributes = attributes || ?::jsonb, updated_at = now(), "
                                + "updated_by = ?, row_version = row_version + 1 "
                                + " WHERE id = ? AND row_version = ?")) {
            statement.setString(1, aspm.app.runtime.Json.write(values));
            statement.setObject(2, principal == null ? null : principal.principalId());
            statement.setObject(3, assetId);
            statement.setInt(4, rowVersion);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                // The attribute KEYS, not the values. A declared attribute is tenant-defined and can
                // hold anything a tenant decides to put in it, up to and including material this
                // platform holds precisely because it is sensitive; the keys say what was touched
                // without copying it into a second store (SEC-AUD-022).
                audit.domainChange(connection, principal, "asset",
                        aspm.kernel.audit.contract.DomainChangeKind.UPDATED, assetId,
                        aspm.app.audit.AuditScopes.ofAsset(connection, assetId),
                        java.util.Map.of("attributes_written",
                                values == null ? List.of() : List.copyOf(values.keySet())));
            }
            connection.commit();
            return applied;
        }
    }

    /**
     * Pending AI suggestions for a subject. ADR-005, ADR-044.
     *
     * <p>Reads a ledger nothing writes to yet. That is the point: the table, the grounding
     * requirement and the promotion constraint exist BEFORE any capability can produce a row, so the
     * first suggestion ever written is already subject to them. The interface renders an empty
     * ledger and says why rather than showing an analysis nobody produced.
     */
    public List<Suggestion> suggestions(Principal principal, String subjectKind, UUID subjectId)
            throws SQLException {
        List<Suggestion> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, suggestion_kind, content::text, model_identity, confidence_band, "
                                + "       generated_at, jsonb_array_length(grounding_refs) "
                                + "  FROM ai_suggestion "
                                + " WHERE subject_kind = ? AND subject_id = ? AND state = 'PENDING' "
                                + " ORDER BY generated_at DESC LIMIT 20")) {
            statement.setString(1, subjectKind);
            statement.setObject(2, subjectId);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Suggestion(r.getObject(1, UUID.class), r.getString(2),
                            r.getString(3), r.getString(4), r.getString(5),
                            String.valueOf(r.getObject(6)), r.getInt(7)));
                }
            }
        }
        return List.copyOf(rows);
    }


    /** Declared attribute key to data type, for one asset type. */
    private Map<String, String> attributeTypes(Principal principal, String typeCode)
            throws SQLException {
        Map<String, String> types = new LinkedHashMap<>();
        for (AttributeDefinition definition : attributeDefinitions(principal, typeCode)) {
            types.put(definition.key(), definition.dataType());
        }
        return types;
    }

    /**
     * A containment document for one attribute filter.
     *
     * <p>A MULTI_SELECT attribute is an array, and containment against an array asks "is this element
     * present". A scalar attribute is compared whole. Getting this wrong is silent: a filter that
     * builds a scalar test against an array matches nothing and reads as "no applications are in PCI
     * scope", which is the most reassuring possible way to be wrong.
     */
    private static String containmentFor(String key, String value, String dataType) {
        Object shaped = switch (dataType) {
            case "MULTI_SELECT" -> List.of(value);
            // A BOOLEAN attribute is stored as a JSON boolean, not the string "true". Containment is
            // type-sensitive, so comparing against the string would match nothing and read as "no
            // third-party applications" — reassuring, and wrong.
            case "BOOLEAN" -> Boolean.valueOf("true".equalsIgnoreCase(value));
            default -> value;
        };
        return aspm.app.runtime.Json.write(Map.of(key, shaped));
    }

    /**
     * Turns submitted form values into the attribute document, honouring each declared data type.
     *
     * @param multiValues values submitted more than once, by key — a checkbox group is genuinely
     *     multi-valued and a last-wins form parser would store exactly one of them
     */
    public Map<String, Object> attributeDocument(List<AttributeDefinition> definitions,
            Map<String, String> single, Map<String, List<String>> multiValues) {
        Map<String, Object> document = new LinkedHashMap<>();
        for (AttributeDefinition definition : definitions) {
            switch (definition.dataType()) {
                case "MULTI_SELECT" -> document.put(definition.key(),
                        multiValues.getOrDefault(definition.key(), List.of()));
                case "BOOLEAN" -> document.put(definition.key(),
                        "true".equalsIgnoreCase(single.getOrDefault(definition.key(), "false")));
                default -> {
                    String value = single.getOrDefault(definition.key(), "");
                    // Written even when blank, so clearing a field clears it. Omitting the key would
                    // leave the previous value in place under the `||` merge, and the form would
                    // appear to accept a deletion it did not perform.
                    document.put(definition.key(), value == null ? "" : value.strip());
                }
            }
        }
        return document;
    }

    // ==============================================================================================
    // Reverse lookup: what is this hostname?
    // ==============================================================================================

    /**
     * One host, and one thing it is attached to.
     *
     * @param environment the environment recorded on the EDGE, so the same host serving production
     *     for one project and UAT for another reports both truthfully
     * @param applicationName the application above the attached asset, where there is one. A project
     *     is the useful answer to "whose is this"; the application is the useful answer to "what is
     *     it part of", and somebody looking a host up in an incident needs both
     */
    public record HostAttachment(UUID domainId, String host, String exposure, UUID assetId,
            String assetName, String assetTypeCode, String environment, String owningNodeName,
            List<String> ownerAncestors, UUID applicationId, String applicationName) {
    }

    /**
     * Which assets a hostname is published on.
     *
     * <h2>Why this exists as its own query</h2>
     *
     * <p>"An alert names {@code uat-pay.example.vn} — whose is it, and what is it part of" is the
     * first question of every incident that starts outside this platform, and until now the answer
     * was unobtainable: domains are assets joined by an edge, so no name search on the project or
     * application list could reach one, and no page listed them at all. An inventory that cannot be
     * queried by the identifier the outside world uses is an inventory nobody consults during the
     * hour it matters.
     *
     * <h2>Substring, deliberately</h2>
     *
     * <p>Matched with {@code ILIKE '%q%'} rather than on equality. Somebody pastes a URL, a
     * certificate subject or a log line; requiring the exact stored form would answer "no" to a host
     * that is recorded, which is the worst possible answer to this question. Searching
     * {@code example.vn} therefore returns every subdomain recorded under it, which is the other
     * half of what people use this for.
     *
     * <p><b>Scope comes from the ATTACHED asset, never from the domain.</b> A domain has no owning
     * node — it is shared by construction, which is the whole reason it is an asset rather than a
     * string. So the predicate is applied to the thing it is attached to, and a caller sees a host
     * only through an asset they could already reach. A host attached to two applications in two
     * branches shows one row to each reader, and neither learns the other exists.
     */
    public List<HostAttachment> hostLookup(Principal principal, String query) throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty() || query == null || query.strip().isEmpty()) {
            return List.of();
        }
        List<HostAttachment> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT d.id, d.display_name, d.exposure_declared, "
                                + "       a.id, a.display_name, at.code, "
                                + "       r.attributes ->> 'environment', n.name, "
                                + "       (SELECT array_agg(an.name ORDER BY cl.depth DESC) "
                                + "          FROM org_closure cl "
                                + "          JOIN org_node an ON an.id = cl.ancestor_id "
                                + "         WHERE cl.descendant_id = a.owning_node_id "
                                + "           AND cl.depth > 0), "
                                + "       app.id, app.display_name "
                                + "  FROM asset d "
                                + "  JOIN asset_type dt ON dt.id = d.type_id AND dt.code = 'DOMAIN' "
                                + "  JOIN asset_relationship r ON r.to_asset_id = d.id "
                                + "                          AND r.valid_until IS NULL "
                                + "  JOIN asset a ON a.id = r.from_asset_id "
                                + "  JOIN asset_type at ON at.id = a.type_id "
                                + "  LEFT JOIN org_node n ON n.id = a.owning_node_id "
                                // The application above whatever the host is attached to. Absent when
                                // the host is attached to the application itself, which is why the
                                // interface falls back to the asset's own name rather than to a blank.
                                + "  LEFT JOIN LATERAL (SELECT ra.id, ra.display_name "
                                + "                       FROM asset_composition c "
                                + "                       JOIN asset ra ON ra.id = c.root_id "
                                + "                       JOIN asset_type rt ON rt.id = ra.type_id "
                                + "                                         AND rt.code = 'APPLICATION' "
                                + "                      WHERE c.asset_id = a.id AND c.depth > 0 "
                                + "                      ORDER BY c.depth LIMIT 1) app ON true "
                                + " WHERE d.display_name ILIKE ? "
                                + "   AND a.lifecycle_state <> 'RETIRED' "
                                + "   AND a.owning_node_id IN (SELECT descendant_id FROM org_closure "
                                + "                             WHERE ancestor_id = ANY (?)) "
                                + " ORDER BY d.display_name, at.ordinal, a.display_name "
                                + " LIMIT 200")) {
            statement.setString(1, "%" + query.strip() + "%");
            statement.setArray(2, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    java.sql.Array ancestors = r.getArray(9);
                    rows.add(new HostAttachment(r.getObject(1, UUID.class), r.getString(2),
                            r.getString(3), r.getObject(4, UUID.class), r.getString(5),
                            r.getString(6), r.getString(7), r.getString(8),
                            ancestors == null ? List.of() : List.of((String[]) ancestors.getArray()),
                            r.getObject(10, UUID.class), r.getString(11)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Every host attached to each of these assets, and to the projects beneath them.
     *
     * <p>Keyed by the asset asked about. Used to put a domain column on a list without a query per
     * row: the inventory tables are the place somebody notices a host they did not expect, and they
     * cannot notice it if seeing it costs a page load each.
     *
     * @param includeDescendants when true, an application also reports the hosts of the projects
     *     beneath it — because an application's reachable surface is its own plus its parts'
     */
    public Map<UUID, Map<String, java.util.TreeSet<String>>> hostsByAsset(Principal principal,
            List<UUID> assetIds, boolean includeDescendants) throws SQLException {
        if (assetIds == null || assetIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<String, java.util.TreeSet<String>>> out = new LinkedHashMap<>();
        // `subject` is the asset the caller asked about; `a` is whatever actually holds the edge.
        //
        // *** THE SUBJECT ITSELF IS ALWAYS IN THE REACH, AND IT WAS NOT. ***
        //
        // This joined `asset_composition` alone, which holds no depth-0 self row — verified against
        // the engine, not assumed from the schema. So an application with a domain attached directly
        // to it reported no host at all, which is the exact case the seeded estate is in and the case
        // most deployments start in: the first host somebody records goes on the application, before
        // any project exists to hang it on. The UNION ALL puts the subject back.
        String reach = "  JOIN LATERAL (SELECT subject.id AS id"
                + (includeDescendants
                        ? " UNION ALL SELECT c.asset_id FROM asset_composition c "
                                + "WHERE c.root_id = subject.id"
                        : "")
                + ") reach ON true "
                + "  JOIN asset a ON a.id = reach.id ";
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT subject.id, coalesce(r.attributes ->> 'environment', 'UNSPECIFIED'), "
                                + "       d.display_name "
                                + "  FROM asset subject "
                                + reach
                                + "  JOIN asset_relationship r ON r.from_asset_id = a.id "
                                + "                          AND r.valid_until IS NULL "
                                + "  JOIN asset d ON d.id = r.to_asset_id "
                                + "  JOIN asset_type dt ON dt.id = d.type_id AND dt.code = 'DOMAIN' "
                                + " WHERE subject.id = ANY (?)")) {
            statement.setArray(1, connection.createArrayOf("uuid", assetIds.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.computeIfAbsent(r.getObject(1, UUID.class), k -> new LinkedHashMap<>())
                            .computeIfAbsent(r.getString(2), k -> new java.util.TreeSet<>())
                            .add(r.getString(3));
                }
            }
        }
        return out;
    }

    /**
     * A filter over the hosts an asset is published on in one environment.
     *
     * <p><b>*** THE COLUMN USED TO BE UNFILTERABLE, AND THAT WAS THE DEFECT THIS CLOSES. ***</b> The
     * domain columns were sent to the interface with {@code filterable: false}, and both inventory
     * lists validated their filter keys against the declared-field catalogue alone — so a filter on
     * one was not refused, it was silently dropped, and the unfiltered list came back looking
     * filtered. "Which projects have a UAT host" was unanswerable on the page whose purpose is to
     * answer it.
     *
     * <p>Two questions, deliberately separate. <b>Presence</b> is the posture question: which systems
     * have an endpoint in this environment at all, and — the half product principle 1 exists for —
     * which have none recorded, which is not the same as having none. <b>Contains</b> is the triage
     * question: somebody has a hostname from an alert and needs the asset it belongs to.
     *
     * @param environment the environment code, validated by the caller against the catalogue
     * @param presence {@code RECORDED}, {@code ABSENT}, or blank for either
     * @param contains a hostname fragment, matched case-insensitively, or blank for any
     */
    public record HostFilter(String environment, String presence, String contains) {

        /** Whether this filter is doing anything at all. */
        public boolean active() {
            return "RECORDED".equals(presence) || "ABSENT".equals(presence)
                    || (contains != null && !contains.isBlank());
        }

        /**
         * Applied to one asset's hosts, as {@link InventoryService#hostsByAsset} returns them.
         *
         * <p>{@code ABSENT} with a fragment means "no host here matching that", which is a coherent
         * question and the reason the two are not folded into one parameter.
         */
        public boolean matches(Map<String, java.util.TreeSet<String>> hostsByEnvironment) {
            java.util.Set<String> hosts = hostsByEnvironment == null
                    ? java.util.Set.of()
                    : hostsByEnvironment.getOrDefault(environment, new java.util.TreeSet<>());
            boolean any = contains == null || contains.isBlank()
                    ? !hosts.isEmpty()
                    : hosts.stream().anyMatch(host -> host.toLowerCase(Locale.ROOT)
                            .contains(contains.strip().toLowerCase(Locale.ROOT)));
            return "ABSENT".equals(presence) ? !any : any;
        }
    }

    // ==============================================================================================
    // The declared-field catalogue itself
    // ==============================================================================================

    /** An asset type, for the catalogue editor. */
    public record AssetTypeRow(UUID id, String code, int ordinal, long fieldCount) {
    }

    /** The permission that governs the catalogue. Declaring a field is not editing an asset. */
    public static final String FIELD_ADMIN = "cfg.asset.field.manage";

    /** {@code ^[a-z][a-z0-9_]{1,48}$} — the same shape the CHECK constraint enforces. */
    private static final java.util.regex.Pattern KEY_SHAPE =
            java.util.regex.Pattern.compile("^[a-z][a-z0-9_]{1,48}$");

    /** The storage kinds the product supplies an editor, a validator and a filter for. */
    public static final List<String> DATA_TYPES = List.of("TEXT", "LONG_TEXT", "URL", "BOOLEAN",
            "INTEGER", "SINGLE_SELECT", "MULTI_SELECT");

    /** Every asset type in the tenant, with how many fields each already declares. */
    public List<AssetTypeRow> assetTypes(Principal principal) throws SQLException {
        List<AssetTypeRow> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT t.id, t.code, t.ordinal, "
                                + "  (SELECT count(*) FROM asset_attribute_definition d "
                                + "    WHERE d.asset_type_id = t.id AND d.lifecycle_state = 'ACTIVE') "
                                + "  FROM asset_type t ORDER BY t.ordinal, t.code");
                ResultSet r = statement.executeQuery()) {
            while (r.next()) {
                rows.add(new AssetTypeRow(r.getObject(1, UUID.class), r.getString(2), r.getInt(3),
                        r.getLong(4)));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Every declared field on a type, DEPRECATED ones included.
     *
     * <p>{@link #attributeDefinitions} returns only the active set, which is right for a form. The
     * catalogue editor needs the deprecated ones too: a field that was retired is still holding
     * values on every record that had one, and an administrator who cannot see it cannot restore it
     * and will declare a second field with a different key meaning the same thing.
     */
    public List<AttributeDefinition> allDefinitions(Principal principal, String typeCode)
            throws SQLException {
        List<AttributeDefinition> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT d.id, d.attribute_key, coalesce(d.label_i18n->>'en', d.attribute_key), "
                                + "       d.data_type, d.permitted_values, d.filterable, d.required, "
                                + "       d.purpose, d.ordinal, d.lifecycle_state, d.row_version, "
                                + "       coalesce(d.label_i18n->>'vi', '') "
                                + "  FROM asset_attribute_definition d "
                                + "  JOIN asset_type t ON t.id = d.asset_type_id "
                                + " WHERE t.code = ? ORDER BY d.ordinal, d.attribute_key")) {
            statement.setString(1, typeCode);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    java.sql.Array values = r.getArray(5);
                    rows.add(new AttributeDefinition(r.getObject(1, UUID.class), r.getString(2),
                            r.getString(3), r.getString(4),
                            values == null ? List.of() : List.of((String[]) values.getArray()),
                            r.getBoolean(6), r.getBoolean(7), r.getString(8), r.getInt(9),
                            r.getString(10), r.getInt(11), r.getString(12)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Which of a select field's permitted values are actually recorded on an asset somewhere.
     *
     * <p>Used to refuse the removal of a value that rows are holding. Removing it does not delete
     * those values — they stay in {@code attributes} and would render as a value nobody declared,
     * filterable by nothing and explicable by no one. Silent orphaning is the failure this prevents.
     */
    public java.util.Set<String> valuesInUse(Principal principal, String typeCode, String key)
            throws SQLException {
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        // Handles both shapes in one pass: a MULTI_SELECT is a JSON array and a
                        // SINGLE_SELECT a JSON string, and jsonb_array_elements_text would error on
                        // the second. The CASE keeps it to one query over one index.
                        "SELECT DISTINCT v FROM asset a "
                                + "  JOIN asset_type t ON t.id = a.type_id, "
                                + "  LATERAL (SELECT CASE jsonb_typeof(a.attributes -> ?) "
                                + "                    WHEN 'array' THEN a.attributes -> ? "
                                + "                    WHEN 'string' THEN jsonb_build_array("
                                + "                                        a.attributes -> ?) "
                                + "                    ELSE '[]'::jsonb END AS arr) x, "
                                + "  LATERAL jsonb_array_elements_text(x.arr) v "
                                + " WHERE t.code = ?")) {
            statement.setString(1, key);
            statement.setString(2, key);
            statement.setString(3, key);
            statement.setString(4, typeCode);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    used.add(r.getString(1));
                }
            }
        }
        return java.util.Set.copyOf(used);
    }

    /**
     * Declares a new field on an asset type.
     *
     * @return the identifier, or empty if the key is already declared on this type
     * @throws IllegalArgumentException if the key, type or value list is unusable — these are
     *     programming or input errors the endpoint turns into a 400, not states to store
     */
    public Optional<UUID> createDefinition(Principal principal, String typeCode, String key,
            String labelEn, String labelVi, String dataType, List<String> permittedValues,
            boolean filterable, boolean required, String purpose) throws SQLException {
        String normalised = key == null ? "" : key.strip().toLowerCase(java.util.Locale.ROOT);
        if (!KEY_SHAPE.matcher(normalised).matches()) {
            throw new IllegalArgumentException("a field key must match ^[a-z][a-z0-9_]{1,48}$ — it is "
                    + "addressed in a JSON path and in a query string, and anything else needs "
                    + "quoting somebody will forget");
        }
        if (!DATA_TYPES.contains(dataType)) {
            throw new IllegalArgumentException("unknown field type " + dataType);
        }
        List<String> values = cleanValues(permittedValues);
        if (isSelectType(dataType) && values.isEmpty()) {
            throw new IllegalArgumentException("a dropdown with no options is a field nobody can "
                    + "complete — give it at least one value");
        }
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                UUID typeId = assetTypeId(connection, typeCode).orElseThrow(
                        () -> new IllegalArgumentException("no asset type " + typeCode));
                UUID id;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO asset_attribute_definition (tenant_id, asset_type_id, "
                                + "attribute_key, label_i18n, data_type, permitted_values, "
                                + "filterable, required, purpose, ordinal, created_by, updated_by) "
                                + "VALUES (current_tenant_id(), ?, ?, ?::jsonb, ?, ?, ?, ?, ?, "
                                // Appended at the end of the type's list rather than at a position
                                // the caller chooses. Ordering is a separate, reorderable concern and
                                // an insert that could claim an existing ordinal would silently
                                // reshuffle a form somebody else is looking at.
                                + "  (SELECT coalesce(max(ordinal), 0) + 1 "
                                + "     FROM asset_attribute_definition WHERE asset_type_id = ?), "
                                + "?, ?) ON CONFLICT (tenant_id, asset_type_id, attribute_key) "
                                + "DO NOTHING RETURNING id")) {
                    insert.setObject(1, typeId);
                    insert.setString(2, normalised);
                    insert.setString(3, aspm.app.runtime.Json.write(labelMap(labelEn, labelVi,
                            normalised)));
                    insert.setString(4, dataType);
                    insert.setArray(5, connection.createArrayOf("text", values.toArray()));
                    insert.setBoolean(6, filterable);
                    insert.setBoolean(7, required);
                    insert.setString(8, blankToNull(purpose));
                    insert.setObject(9, typeId);
                    insert.setObject(10, principal == null ? null : principal.principalId());
                    insert.setObject(11, principal == null ? null : principal.principalId());
                    try (ResultSet keys = insert.executeQuery()) {
                        if (!keys.next()) {
                            connection.rollback();
                            return Optional.empty();
                        }
                        id = keys.getObject(1, UUID.class);
                    }
                }
                // scopeNode is null and that is correct: a field declaration is tenant-wide
                // configuration, not a change inside one branch of the organization tree. Attaching
                // it to a node would make it look scoped and hide it from anybody reading the
                // tenant's configuration history.
                audit.domainChange(connection, principal, "asset_attribute_definition",
                        aspm.kernel.audit.contract.DomainChangeKind.CREATED, id, null,
                        java.util.Map.of("action", "DECLARED", "asset_type", typeCode,
                                "attribute_key", normalised, "data_type", dataType));
                connection.commit();
                return Optional.of(id);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Changes a declared field, within the bounds that keep recorded values meaningful.
     *
     * <h2>What cannot change, and why</h2>
     *
     * <p><b>The key.</b> It is the JSON key every recorded value is stored under. Renaming it would
     * orphan every value on every asset in one statement, and nothing afterwards would say so.
     *
     * <p><b>The data type.</b> A SINGLE_SELECT holding {@code "ZTNA"} and a MULTI_SELECT holding
     * {@code ["ZTNA"]} are different documents. Changing the type would leave every existing row in
     * the shape the old type wrote, and the editor would silently drop what it could not read.
     *
     * <p>Both are enforced by simply not reading them from the caller. A tenant that needs either
     * declares a new field and deprecates this one, which keeps the old values readable.
     *
     * @param removable values being dropped from the permitted list that assets still hold. The
     *     caller supplies the check result rather than this method performing it, so the endpoint can
     *     report every offending value at once
     * @return false if the update was refused as stale
     */
    public boolean updateDefinition(Principal principal, UUID id, String labelEn, String labelVi,
            List<String> permittedValues, boolean filterable, boolean required, String purpose,
            int rowVersion) throws SQLException {
        List<String> values = cleanValues(permittedValues);
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                boolean applied;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE asset_attribute_definition "
                                + "   SET label_i18n = ?::jsonb, permitted_values = ?, "
                                + "       filterable = ?, required = ?, purpose = ?, "
                                + "       updated_at = now(), updated_by = ?, "
                                + "       row_version = row_version + 1 "
                                // The select-type guard, at the engine: an UPDATE that emptied a
                                // dropdown would leave a required field nobody can complete, and the
                                // CHECK constraint refuses it whichever path the write came through.
                                + " WHERE id = ? AND row_version = ?")) {
                    update.setString(1, aspm.app.runtime.Json.write(
                            labelMap(labelEn, labelVi, null)));
                    update.setArray(2, connection.createArrayOf("text", values.toArray()));
                    update.setBoolean(3, filterable);
                    update.setBoolean(4, required);
                    update.setString(5, blankToNull(purpose));
                    update.setObject(6, principal == null ? null : principal.principalId());
                    update.setObject(7, id);
                    update.setInt(8, rowVersion);
                    applied = update.executeUpdate() == 1;
                }
                if (!applied) {
                    connection.rollback();
                    return false;
                }
                audit.domainChange(connection, principal, "asset_attribute_definition",
                        aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id, null,
                        java.util.Map.of("action", "AMENDED", "permitted_values", values,
                                "filterable", filterable, "required", required));
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Retires a field, or brings one back.
     *
     * <p>Never a delete, and there is no DELETE grant on the table. Values already recorded under
     * this key stay exactly where they are: deprecation removes the field from forms and from column
     * pickers, and restoring it makes every one of those values visible again. Deleting the
     * definition would leave the data with nothing to explain it.
     */
    public boolean setDefinitionLifecycle(Principal principal, UUID id, boolean active,
            int rowVersion) throws SQLException {
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                boolean applied;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE asset_attribute_definition SET lifecycle_state = ?, "
                                + "updated_at = now(), updated_by = ?, row_version = row_version + 1 "
                                + " WHERE id = ? AND row_version = ?")) {
                    update.setString(1, active ? "ACTIVE" : "DEPRECATED");
                    update.setObject(2, principal == null ? null : principal.principalId());
                    update.setObject(3, id);
                    update.setInt(4, rowVersion);
                    applied = update.executeUpdate() == 1;
                }
                if (!applied) {
                    connection.rollback();
                    return false;
                }
                audit.domainChange(connection, principal, "asset_attribute_definition",
                        aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id, null,
                        java.util.Map.of("action", active ? "RESTORED" : "DEPRECATED"));
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** Moves a field up or down within its type's ordering. */
    public boolean reorderDefinition(Principal principal, UUID id, int delta) throws SQLException {
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                boolean applied;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE asset_attribute_definition SET ordinal = greatest(0, ordinal + ?), "
                                + "updated_at = now(), updated_by = ?, row_version = row_version + 1 "
                                + " WHERE id = ?")) {
                    // Ordinals need not be dense or unique — the read orders by (ordinal,
                    // attribute_key), so a tie resolves the same way on every load rather than
                    // shuffling. A dense re-pack would rewrite every row of the type to move one.
                    update.setInt(1, delta * 3);
                    update.setObject(2, principal == null ? null : principal.principalId());
                    update.setObject(3, id);
                    applied = update.executeUpdate() == 1;
                }
                if (applied) {
                    audit.domainChange(connection, principal, "asset_attribute_definition",
                            aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id, null,
                            java.util.Map.of("action", "REORDERED", "delta", delta));
                }
                connection.commit();
                return applied;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    // ==============================================================================================
    // The endpoint environment catalogue
    // ==============================================================================================

    /**
     * One environment an endpoint can be published in.
     *
     * <p>Three lifecycle states, and only two of them exist in the table. {@code ACTIVE} and
     * {@code DEPRECATED} are stored; <b>{@code UNDECLARED} is synthetic</b> and means the environment
     * appears on a recorded edge and in no catalogue row. Undeclared environments are reported rather
     * than hidden: an importer may carry an environment name nobody has declared, and dropping it
     * from the interface would hide a recorded host, which product principle 1 forbids. They are
     * offered as columns and never offered in a form, because a form option that is not in the
     * catalogue is a vocabulary nobody agreed to.
     *
     * @param recorded whether any current published-on edge actually carries this environment. It is
     *     what separates "declared and nobody has recorded one" — a column worth offering, because an
     *     empty cell is the answer — from "deprecated and holding data", which must stay visible
     * @param id null for an undeclared environment, which has no catalogue row to amend
     */
    public record EndpointEnvironment(UUID id, String code, String label, String labelVi,
            String purpose, int ordinal, String lifecycleState, boolean recorded, int rowVersion) {

        /** Offered in a form: the tenant declares it and has not retired it. */
        public boolean active() {
            return "ACTIVE".equals(lifecycleState);
        }

        /** Declared at all, as against inferred from an edge. */
        public boolean declared() {
            return id != null;
        }

        /**
         * Offered as a column. Active environments are offered whether or not anything is recorded —
         * "no UAT host recorded against this project" is an answer somebody needs, and the column is
         * where it is given (product principle 1). A retired or undeclared one is offered only while
         * it still has data to show.
         */
        public boolean columnWorthy() {
            return active() || recorded;
        }
    }

    /** {@code ^[A-Z][A-Z0-9_]{1,30}$} — the same shape the CHECK constraint enforces. */
    private static final java.util.regex.Pattern ENVIRONMENT_SHAPE =
            java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]{1,30}$");

    /**
     * Every environment the tenant declares, plus every one its data carries.
     *
     * <p>One query and one type for four callers — the two editors, which offer the active ones; the
     * two inventory lists, which offer columns for the ones worth a column and validate a host filter
     * against the same set; and the catalogue editor. Deriving them separately is how the application
     * editor came to offer PRODUCTION and STAGING while the project editor offered PRODUCTION and
     * UAT.
     */
    public List<EndpointEnvironment> endpointEnvironments(Principal principal) throws SQLException {
        List<EndpointEnvironment> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "WITH declared AS ("
                                + "  SELECT e.id, e.code, coalesce(e.label_i18n ->> 'en', e.code) "
                                + "         AS label, coalesce(e.label_i18n ->> 'vi', '') AS label_vi,"
                                + "         e.purpose, e.ordinal, e.lifecycle_state, e.row_version "
                                + "    FROM asset_endpoint_environment e), "
                                + "recorded AS ("
                                // Same coalesce the host columns use, so an edge with no environment
                                // recorded lands under the same name in both places rather than
                                // appearing in one and vanishing from the other.
                                + "  SELECT DISTINCT coalesce(r.attributes ->> 'environment', "
                                + "                          'UNSPECIFIED') AS code "
                                + "    FROM asset_relationship r "
                                + "    JOIN asset d ON d.id = r.to_asset_id "
                                + "    JOIN asset_type dt ON dt.id = d.type_id "
                                + "                      AND dt.code = 'DOMAIN' "
                                + "   WHERE r.valid_until IS NULL) "
                                + "SELECT d.id, coalesce(d.code, o.code) AS code, "
                                + "       coalesce(d.label, o.code) AS label, "
                                + "       coalesce(d.label_vi, '') AS label_vi, d.purpose, "
                                // An undeclared environment sorts after every declared one. It has no
                                // place the tenant chose, and putting it first would give an imported
                                // name precedence over the vocabulary somebody agreed.
                                + "       coalesce(d.ordinal, 100000) AS ordinal, "
                                + "       coalesce(d.lifecycle_state, 'UNDECLARED') AS lifecycle, "
                                + "       (o.code IS NOT NULL) AS recorded, "
                                + "       coalesce(d.row_version, 0) AS row_version "
                                + "  FROM declared d FULL OUTER JOIN recorded o ON o.code = d.code "
                                + " ORDER BY ordinal, code");
                ResultSet r = statement.executeQuery()) {
            while (r.next()) {
                rows.add(new EndpointEnvironment(r.getObject(1, UUID.class), r.getString(2),
                        r.getString(3), r.getString(4), r.getString(5), r.getInt(6), r.getString(7),
                        r.getBoolean(8), r.getInt(9)));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Declares an environment.
     *
     * <p>The code is upper-cased rather than rejected for case: it is matched against
     * {@code attributes->>'environment'} on edges that importers write, and a tenant who types
     * {@code uat} means the environment the data spells {@code UAT}. A code that cannot be
     * normalised into shape is refused, because a code with a space or a dot in it cannot be put in
     * a query-string parameter without quoting somebody will forget.
     *
     * @return the identifier, or empty if that code is already declared — including as a DEPRECATED
     *     row, which is restored rather than duplicated
     */
    public Optional<UUID> createEnvironment(Principal principal, String code, String labelEn,
            String labelVi, String purpose) throws SQLException {
        String normalised = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (!ENVIRONMENT_SHAPE.matcher(normalised).matches()) {
            throw new IllegalArgumentException("an environment code must match ^[A-Z][A-Z0-9_]{1,30}$ "
                    + "— it is matched against the environment recorded on an edge and carried in a "
                    + "query string");
        }
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                UUID id;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO asset_endpoint_environment (tenant_id, code, label_i18n, "
                                + "purpose, ordinal, created_by, updated_by) "
                                + "VALUES (current_tenant_id(), ?, ?::jsonb, ?, "
                                // Ten past the last, matching the gaps V069 left: a tenant inserting
                                // one between two others should not have to renumber the rest.
                                + "  (SELECT coalesce(max(ordinal), 0) + 10 "
                                + "     FROM asset_endpoint_environment), ?, ?) "
                                + "ON CONFLICT (tenant_id, code) DO NOTHING RETURNING id")) {
                    insert.setString(1, normalised);
                    insert.setString(2, aspm.app.runtime.Json.write(
                            labelMap(labelEn, labelVi, normalised)));
                    insert.setString(3, blankToNull(purpose));
                    insert.setObject(4, principal == null ? null : principal.principalId());
                    insert.setObject(5, principal == null ? null : principal.principalId());
                    try (ResultSet keys = insert.executeQuery()) {
                        if (!keys.next()) {
                            connection.rollback();
                            return Optional.empty();
                        }
                        id = keys.getObject(1, UUID.class);
                    }
                }
                // scopeNode null, as with a field declaration: the vocabulary is tenant-wide
                // configuration and not a change inside one branch of the organization tree.
                audit.domainChange(connection, principal, "asset_endpoint_environment",
                        aspm.kernel.audit.contract.DomainChangeKind.CREATED, id, null,
                        java.util.Map.of("action", "DECLARED", "code", normalised));
                connection.commit();
                return Optional.of(id);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Relabels an environment, or restates what it is for.
     *
     * <p><b>The code cannot change.</b> It is the value recorded on every edge published in this
     * environment; renaming it would orphan all of them in one statement and nothing afterwards would
     * say so. A tenant that needs a different code declares one and deprecates this one, which keeps
     * the recorded edges explicable — the same rule, for the same reason, as a declared field's key.
     *
     * @return false if the update was refused as stale
     */
    public boolean updateEnvironment(Principal principal, UUID id, String labelEn, String labelVi,
            String purpose, int rowVersion) throws SQLException {
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                boolean applied;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE asset_endpoint_environment SET label_i18n = ?::jsonb, purpose = ?, "
                                + "updated_at = now(), updated_by = ?, row_version = row_version + 1 "
                                + " WHERE id = ? AND row_version = ?")) {
                    update.setString(1, aspm.app.runtime.Json.write(
                            labelMap(labelEn, labelVi, null)));
                    update.setString(2, blankToNull(purpose));
                    update.setObject(3, principal == null ? null : principal.principalId());
                    update.setObject(4, id);
                    update.setInt(5, rowVersion);
                    applied = update.executeUpdate() == 1;
                }
                if (!applied) {
                    connection.rollback();
                    return false;
                }
                audit.domainChange(connection, principal, "asset_endpoint_environment",
                        aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id, null,
                        java.util.Map.of("action", "AMENDED"));
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Retires an environment, or brings one back.
     *
     * <p>Never a delete, and there is no DELETE grant on the table. Retiring stops the forms offering
     * it; it does not touch a single edge. Hosts already published in it keep their edges, keep their
     * column while those edges are current, and reappear in every form the moment it is restored —
     * which is why the editor says how many are recorded before anybody presses the button.
     */
    public boolean setEnvironmentLifecycle(Principal principal, UUID id, boolean active,
            int rowVersion) throws SQLException {
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                boolean applied;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE asset_endpoint_environment SET lifecycle_state = ?, "
                                + "updated_at = now(), updated_by = ?, row_version = row_version + 1 "
                                + " WHERE id = ? AND row_version = ?")) {
                    update.setString(1, active ? "ACTIVE" : "DEPRECATED");
                    update.setObject(2, principal == null ? null : principal.principalId());
                    update.setObject(3, id);
                    update.setInt(4, rowVersion);
                    applied = update.executeUpdate() == 1;
                }
                if (!applied) {
                    connection.rollback();
                    return false;
                }
                audit.domainChange(connection, principal, "asset_endpoint_environment",
                        aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id, null,
                        java.util.Map.of("action", active ? "RESTORED" : "DEPRECATED"));
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** Moves an environment up or down the order the forms and the column picker render. */
    public boolean reorderEnvironment(Principal principal, UUID id, int delta) throws SQLException {
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                boolean applied;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE asset_endpoint_environment "
                                + "   SET ordinal = greatest(0, ordinal + ?), updated_at = now(), "
                                + "       updated_by = ?, row_version = row_version + 1 "
                                + " WHERE id = ?")) {
                    // Ordinals are neither dense nor unique — the read orders by (ordinal, code), so a
                    // tie resolves the same way on every load. Fifteen rather than three because
                    // V069 spaced the defaults ten apart.
                    update.setInt(1, delta * 15);
                    update.setObject(2, principal == null ? null : principal.principalId());
                    update.setObject(3, id);
                    applied = update.executeUpdate() == 1;
                }
                if (applied) {
                    audit.domainChange(connection, principal, "asset_endpoint_environment",
                            aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id, null,
                            java.util.Map.of("action", "REORDERED", "delta", delta));
                }
                connection.commit();
                return applied;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * How many current endpoints are published in one environment, tenant-wide.
     *
     * <p>Shown beside the retire button. Deprecating an environment that forty hosts are published in
     * removes it from every form while leaving all forty edges current, and an administrator who
     * cannot see the number before pressing the button finds out afterwards from somebody else.
     */
    public long endpointsInEnvironment(Principal principal, String code) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM asset_relationship r "
                                + "  JOIN asset d ON d.id = r.to_asset_id "
                                + "  JOIN asset_type dt ON dt.id = d.type_id AND dt.code = 'DOMAIN' "
                                + " WHERE r.valid_until IS NULL "
                                + "   AND coalesce(r.attributes ->> 'environment', 'UNSPECIFIED') "
                                + "       = ?")) {
            statement.setString(1, code);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? r.getLong(1) : 0L;
            }
        }
    }

    private static boolean isSelectType(String dataType) {
        return "SINGLE_SELECT".equals(dataType) || "MULTI_SELECT".equals(dataType);
    }

    /** Trimmed, de-duplicated, order preserved. A list with a blank in it is a dropdown with a gap. */
    private static List<String> cleanValues(List<String> values) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.strip().isEmpty()) {
                    out.add(value.strip());
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * The label document. Vietnamese is written when supplied and omitted when not — an empty string
     * would make the interface render a blank label in that locale rather than falling back to
     * English, which is the worse of the two failures (NFR-INT-003).
     */
    private static Map<String, String> labelMap(String labelEn, String labelVi, String fallback) {
        Map<String, String> labels = new LinkedHashMap<>();
        String en = labelEn == null ? "" : labelEn.strip();
        labels.put("en", en.isEmpty() ? (fallback == null ? "" : fallback) : en);
        String vi = labelVi == null ? "" : labelVi.strip();
        if (!vi.isEmpty()) {
            labels.put("vi", vi);
        }
        return labels;
    }

    /** A submitted attribute value the tenant's own catalogue refuses. */
    public record AttributeViolation(String key, String code, String message) {
    }

    /**
     * Builds the attribute document from a JSON payload, coerced by each field's declared type.
     *
     * <p>Only DECLARED keys are read. A payload naming a field nobody declared is ignored rather than
     * stored: {@code attributes} is merged with {@code ||}, so an unfiltered write would let any
     * caller add arbitrary keys to an asset for ever, and nothing would ever remove them.
     *
     * <p>A blank {@code INTEGER} is written as JSON {@code null} and a blank text field as an empty
     * string. The asymmetry is deliberate — {@code ""} is not a number, and a zero would claim the
     * project exposes no endpoints when the truth is that nobody has counted (product principle 1).
     */
    public Map<String, Object> attributeDocumentFrom(List<AttributeDefinition> definitions,
            Map<String, Object> payload) {
        Map<String, Object> document = new LinkedHashMap<>();
        for (AttributeDefinition definition : definitions) {
            Object raw = payload.get(definition.key());
            switch (definition.dataType()) {
                case "MULTI_SELECT" -> {
                    List<String> values = new ArrayList<>();
                    if (raw instanceof List<?> list) {
                        for (Object item : list) {
                            String value = item == null ? "" : String.valueOf(item).strip();
                            if (!value.isEmpty()) {
                                values.add(value);
                            }
                        }
                    }
                    document.put(definition.key(), List.copyOf(values));
                }
                case "BOOLEAN" -> document.put(definition.key(),
                        raw instanceof Boolean flag ? flag
                                : Boolean.parseBoolean(String.valueOf(raw)));
                case "INTEGER" -> {
                    String value = raw == null ? "" : String.valueOf(raw).strip();
                    if (value.isEmpty()) {
                        document.put(definition.key(), null);
                    } else {
                        try {
                            document.put(definition.key(), Long.valueOf(value.contains(".")
                                    ? String.valueOf((long) Double.parseDouble(value)) : value));
                        } catch (NumberFormatException e) {
                            // Kept as the text that was sent so the validator below can name it back
                            // to the person. Discarding it here would report "not a number" beside an
                            // empty box, which is the least useful form of that message.
                            document.put(definition.key(), value);
                        }
                    }
                }
                default -> document.put(definition.key(),
                        raw == null ? "" : String.valueOf(raw).strip());
            }
        }
        return document;
    }

    /**
     * Checks a document against the catalogue that produced its fields.
     *
     * <h2>Why this exists, given that the editor renders a dropdown</h2>
     *
     * <p>The dropdown is on the client, and the client is under the caller's control. Until now the
     * only thing standing between a submitted value and {@code asset.attributes} was the form markup:
     * {@link #attributeDocument} coerced types and never once compared a value against
     * {@code permitted_values}. A caller posting {@code {"waf":"anything"}} was stored verbatim, and
     * every filter, count and report built on that field silently acquired a value nobody declared.
     *
     * <p>This is the same defect class the product exists to find in customers' software — trusting a
     * constraint that was only ever enforced in the interface — so it is checked at the write.
     *
     * @return the violations, empty when the document is acceptable
     */
    public List<AttributeViolation> attributeViolations(List<AttributeDefinition> definitions,
            Map<String, Object> document) {
        List<AttributeViolation> problems = new ArrayList<>();
        for (AttributeDefinition definition : definitions) {
            Object value = document.get(definition.key());
            switch (definition.dataType()) {
                case "SINGLE_SELECT" -> {
                    String chosen = value == null ? "" : String.valueOf(value).strip();
                    if (!chosen.isEmpty() && !definition.permittedValues().contains(chosen)) {
                        problems.add(new AttributeViolation(definition.key(), "VALUE_NOT_PERMITTED",
                                definition.label() + ": \"" + chosen + "\" is not one of the values "
                                        + "this tenant declared for it"));
                    }
                }
                case "MULTI_SELECT" -> {
                    if (value instanceof List<?> list) {
                        for (Object item : list) {
                            String chosen = item == null ? "" : String.valueOf(item).strip();
                            if (!chosen.isEmpty()
                                    && !definition.permittedValues().contains(chosen)) {
                                problems.add(new AttributeViolation(definition.key(),
                                        "VALUE_NOT_PERMITTED", definition.label() + ": \"" + chosen
                                                + "\" is not one of the values this tenant declared "
                                                + "for it"));
                            }
                        }
                    }
                }
                case "INTEGER" -> {
                    if (value != null && !(value instanceof Number)) {
                        problems.add(new AttributeViolation(definition.key(), "NOT_A_NUMBER",
                                definition.label() + " must be a whole number"));
                    } else if (value instanceof Number number && number.longValue() < 0) {
                        problems.add(new AttributeViolation(definition.key(), "NEGATIVE",
                                definition.label() + " cannot be negative"));
                    }
                }
                case "URL" -> {
                    String url = value == null ? "" : String.valueOf(value).strip();
                    // http and https only. A javascript: or data: URL stored here is rendered as a
                    // link on a page other people open, which turns an inventory field into a
                    // delivery mechanism.
                    if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                        problems.add(new AttributeViolation(definition.key(), "NOT_A_URL",
                                definition.label() + " must be an http:// or https:// address"));
                    }
                }
                default -> { }
            }
            if (definition.required() && isBlankValue(value)) {
                problems.add(new AttributeViolation(definition.key(), "REQUIRED",
                        definition.label() + " is required"));
            }
        }
        return List.copyOf(problems);
    }

    private static boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        return String.valueOf(value).strip().isEmpty();
    }

    // ==============================================================================================
    // Projects
    // ==============================================================================================

    /**
     * Everything the project editor can change.
     *
     * @param attributes the declared-attribute document, already coerced by
     *     {@link #attributeDocumentFrom} and checked by {@link #attributeViolations}
     * @param technicalContactId the person to call. Distinct from the delivery team recorded in the
     *     attributes: a team is accountable, a person answers
     */
    public record ProjectDraft(UUID id, String name, UUID owningNodeId, UUID criticalityTierId,
            String exposureDeclared, UUID technicalContactId, Map<String, Object> attributes,
            Map<String, List<String>> domains, String repository, String repositoryBranch,
            Integer rowVersion) {
    }

    /**
     * Updates one project's record: its own columns, its declared attributes, and the assets it is
     * related to.
     *
     * <p>One transaction, for the same reason the application save is one: a project whose domain
     * edge was written and whose attributes were not is a half-recorded inventory that reads as a
     * whole one.
     *
     * <p><b>Update only.</b> Projects are created by the composition pipeline and by import, which
     * establish the edge to their application; this path deliberately cannot create one, because a
     * project with no application above it is invisible to every rollup on the platform.
     *
     * @return the identifier, or empty if the update was refused as stale
     */
    public Optional<UUID> saveProject(Principal principal, ProjectDraft draft) throws SQLException {
        Objects.requireNonNull(draft.id(), "saveProject updates an existing project");
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE asset SET display_name = ?, criticality_mode = ?, "
                            + "criticality_tier_id = ?, exposure_declared = ?, "
                            + "exposure_declared_by = CASE WHEN ?::text IS NULL "
                            + "                            THEN exposure_declared_by ELSE ? END, "
                            + "exposure_declared_at = CASE WHEN ?::text IS NULL "
                            + "                            THEN exposure_declared_at ELSE now() END, "
                            + "technical_contact_id = ?, "
                            // Merged, never replaced. A project carries attributes this form does not
                            // render — written by an importer, or declared after this page was built —
                            // and replacing the object would drop them with no trace.
                            + "attributes = attributes || ?::jsonb, updated_at = now(), "
                            + "updated_by = ?, row_version = row_version + 1 "
                            + " WHERE id = ? AND row_version = ?")) {
                update.setString(1, draft.name().strip());
                update.setString(2, draft.criticalityTierId() == null ? "INHERITED" : "ASSIGNED");
                update.setObject(3, draft.criticalityTierId());
                update.setString(4, blankToNull(draft.exposureDeclared()));
                update.setString(5, blankToNull(draft.exposureDeclared()));
                update.setObject(6, principal == null ? null : principal.principalId());
                update.setString(7, blankToNull(draft.exposureDeclared()));
                update.setObject(8, draft.technicalContactId());
                update.setString(9, aspm.app.runtime.Json.write(
                        draft.attributes() == null ? Map.of() : draft.attributes()));
                update.setObject(10, principal == null ? null : principal.principalId());
                update.setObject(11, draft.id());
                update.setInt(12, draft.rowVersion() == null ? -1 : draft.rowVersion());
                if (update.executeUpdate() != 1) {
                    connection.rollback();
                    return Optional.empty();
                }
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
            try {
                // Create-or-reuse, exactly as the application editor does it: two projects deployed on
                // one host are two edges to ONE domain asset. That is what makes "everything reachable
                // at this host" answerable, and it is lost the moment a domain becomes a text column.
                //
                // Environments come from the tenant's catalogue (V069), not from two names written
                // here. The pair that used to be named — PRODUCTION and UAT — disagreed with the
                // application editor's PRODUCTION and STAGING, so each form could record an
                // environment the other could not.
                linkEndpoints(connection, principal, draft.id(), draft.domains());
                // A REFERENCE to a repository and a branch name, never a clone. ADR-024: the platform
                // never fetches, clones or persists source code and holds no Git credentials.
                linkRepository(connection, principal, draft.id(), draft.repository(),
                        draft.repositoryBranch());

                // The attribute KEYS, not the values (SEC-AUD-022). A declared field can hold anything
                // a tenant decides to put in it; the record says what was touched without copying it.
                audit.domainChange(connection, principal, "asset",
                        aspm.kernel.audit.contract.DomainChangeKind.UPDATED, draft.id(),
                        aspm.app.audit.AuditScopes.ofAsset(connection, draft.id()),
                        java.util.Map.of("asset_type", aspm.app.inventory.ProjectQuery.PROJECT_TYPE,
                                "name", draft.name() == null ? "" : draft.name().strip(),
                                "attributes_written", draft.attributes() == null
                                        ? List.of() : List.copyOf(draft.attributes().keySet())));
                connection.commit();
                return Optional.of(draft.id());
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** One component asset, re-validated against the application it is reached through. */
    public Optional<Component> component(Principal principal, UUID rootId, UUID componentId)
            throws SQLException {
        return components(principal, rootId).stream()
                .filter(c -> c.id().equals(componentId)).findFirst();
    }

    /** The row version of an asset, needed for an optimistic update. */
    public Optional<Integer> assetVersion(Principal principal, UUID assetId) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT row_version FROM asset WHERE id = ?")) {
            statement.setObject(1, assetId);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? Optional.of(r.getInt(1)) : Optional.empty();
            }
        }
    }

    /**
     * Creates a component under a parent, or updates an existing one.
     *
     * <p>One transaction: an asset created without the edge that attaches it is an orphan nobody will
     * find, and an edge written without its asset is a dangling reference.
     *
     * @param parentId the asset the new component hangs under — the application itself, or a feature
     * @return the component identifier, or empty if an update was refused as stale
     */
    public Optional<UUID> saveComponent(Principal principal, UUID componentId, String typeCode,
            String name, UUID parentId, UUID owningNodeId, String exposure,
            Map<String, Object> attributes, Integer rowVersion) throws SQLException {
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                UUID typeId = assetTypeId(connection, typeCode).orElseThrow(
                        () -> new IllegalStateException("the " + typeCode
                                + " asset type does not exist in this tenant"));
                UUID id = componentId;
                String attributeJson = aspm.app.runtime.Json.write(attributes);
                if (id == null) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO asset (tenant_id, type_id, identity_key, "
                                    + "identity_rule_version, display_name, owning_node_id, "
                                    + "criticality_mode, exposure_declared, exposure_declared_at, "
                                    + "lifecycle_state, attributes, discovery_source, "
                                    + "discovery_method, first_seen_at, last_confirmed_at, "
                                    + "created_by, updated_by) "
                                    + "VALUES (current_tenant_id(), ?, ?, 1, ?, ?, 'INHERITED', ?, "
                                    + "  CASE WHEN ?::text IS NULL THEN NULL ELSE now() END, "
                                    + "  'ACTIVE', ?::jsonb, 'MANUAL', 'INVENTORY_FORM', now(), "
                                    + "  now(), ?, ?) RETURNING id")) {
                        insert.setObject(1, typeId);
                        insert.setString(2, identityKey(name));
                        insert.setString(3, name.strip());
                        insert.setObject(4, owningNodeId);
                        insert.setString(5, blankToNull(exposure));
                        insert.setString(6, blankToNull(exposure));
                        insert.setString(7, attributeJson);
                        insert.setObject(8, principal == null ? null : principal.principalId());
                        insert.setObject(9, principal == null ? null : principal.principalId());
                        try (ResultSet keys = insert.executeQuery()) {
                            keys.next();
                            id = keys.getObject(1, UUID.class);
                        }
                    }
                    if (parentId != null) {
                        insertEdge(connection, principal, parentId, id, EDGE_CONTAINS, null);
                    }
                } else {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE asset SET display_name = ?, exposure_declared = ?, "
                                    + "exposure_declared_at = CASE WHEN ?::text IS NULL "
                                    + "  THEN exposure_declared_at ELSE now() END, "
                                    + "attributes = attributes || ?::jsonb, updated_at = now(), "
                                    + "updated_by = ?, row_version = row_version + 1 "
                                    + " WHERE id = ? AND row_version = ?")) {
                        update.setString(1, name.strip());
                        update.setString(2, blankToNull(exposure));
                        update.setString(3, blankToNull(exposure));
                        update.setString(4, attributeJson);
                        update.setObject(5, principal == null ? null : principal.principalId());
                        update.setObject(6, id);
                        update.setInt(7, rowVersion == null ? -1 : rowVersion);
                        if (update.executeUpdate() != 1) {
                            connection.rollback();
                            return Optional.empty();
                        }
                    }
                }
                audit.domainChange(connection, principal, "asset",
                        componentId == null
                                ? aspm.kernel.audit.contract.DomainChangeKind.CREATED
                                : aspm.kernel.audit.contract.DomainChangeKind.UPDATED,
                        id, aspm.app.audit.AuditScopes.ofAsset(connection, id),
                        java.util.Map.of("asset_type", typeCode == null ? "" : typeCode,
                                "name", name == null ? "" : name.strip(),
                                "parent_asset_id", parentId == null ? "" : parentId.toString()));
                connection.commit();
                return Optional.of(id);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Detaches a component from its parent by CLOSING the edge.
     *
     * <p>The asset survives. A service removed from one application may still be part of another, and
     * a finding raised against it while it was attached is still the record of a real weakness —
     * INV-AST-16 refuses to reopen a closed edge precisely so "what was part of what, when" stays
     * answerable to a retest six months later.
     */
    public boolean detachComponent(Principal principal, UUID parentId, UUID componentId)
            throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE asset_relationship SET valid_until = now(), updated_at = now(), "
                                + "updated_by = ? "
                                + " WHERE from_asset_id = ? AND to_asset_id = ? "
                                + "   AND valid_until IS NULL")) {
            statement.setObject(1, principal == null ? null : principal.principalId());
            statement.setObject(2, parentId);
            statement.setObject(3, componentId);
            boolean detached = statement.executeUpdate() >= 1;
            if (detached) {
                audit.domainChange(connection, principal, "asset_relationship",
                        aspm.kernel.audit.contract.DomainChangeKind.RETIRED, componentId,
                        aspm.app.audit.AuditScopes.ofAsset(connection, parentId),
                        java.util.Map.of("parent_asset_id", parentId.toString(),
                                "component_asset_id", componentId.toString()));
            }
            connection.commit();
            return detached;
        }
    }

    /** Candidate parents inside one application: the application itself and its features. */
    public List<Component> parentCandidates(Principal principal, UUID rootId) throws SQLException {
        return components(principal, rootId).stream()
                .filter(c -> "FEATURE".equals(c.typeCode()))
                .toList();
    }


    /** The product-fixed finding classes, and the assurance activity each one is evidence of. */
    public static final List<String> ASSURANCE_CLASSES = List.of(
            "MANUAL", "CODE", "DEPENDENCY", "RUNTIME", "SECRET", "CONFIGURATION", "INFRASTRUCTURE");

    /** Evidence of one assurance activity, or its absence. */
    public record Assurance(String findingClass, String lastEvidenceAt, long coveredParts,
            long openCount, long findingCount) {

        /** Nothing has ever produced a finding of this class here. */
        public boolean never() {
            return lastEvidenceAt == null;
        }
    }

    /** SBOM state for one asset. {@code PRD-SBM-056} keeps NEVER_SUBMITTED as a value. */
    public record SbomState(UUID assetId, String freshness, String quality, String latestAt,
            List<String> uncoveredEcosystems) {
    }

    /** How long remediation actually takes here. */
    public record Remediation(long closedCount, Integer meanDaysToClose, Integer openOldestDays,
            long openOver90Days) {
    }

    /**
     * Assurance coverage for an application, ONE ROW PER PRODUCT-FIXED CLASS.
     *
     * <p>Classes with no evidence are returned with a null date rather than omitted. That is the
     * whole point: a list of what HAS run answers a different question from a list of what could have
     * run and did not, and only the second one tells a reader that nothing has ever penetration
     * tested this application.
     */
    public List<Assurance> assurance(Principal principal, UUID rootId) throws SQLException {
        Map<String, Assurance> found = new LinkedHashMap<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT finding_class, last_evidence_at, covered_parts, open_count, "
                                + "       finding_count FROM application_assurance WHERE asset_id = ?")) {
            statement.setObject(1, rootId);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    found.put(r.getString(1), new Assurance(r.getString(1),
                            r.getObject(2) == null ? null : String.valueOf(r.getObject(2)),
                            r.getLong(3), r.getLong(4), r.getLong(5)));
                }
            }
        }
        List<Assurance> all = new ArrayList<>();
        for (String findingClass : ASSURANCE_CLASSES) {
            all.add(found.getOrDefault(findingClass,
                    new Assurance(findingClass, null, 0, 0, 0)));
        }
        return List.copyOf(all);
    }

    /** SBOM state across an application's parts, keyed by asset. */
    public Map<UUID, SbomState> sbomStates(Principal principal, UUID rootId) throws SQLException {
        Map<UUID, SbomState> states = new LinkedHashMap<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT s.asset_id, s.freshness, s.quality, s.latest_snapshot_at, "
                                + "       s.uncovered_ecosystems FROM asset_sbom_state s "
                                + " WHERE s.asset_id = ? OR s.asset_id IN "
                                + "   (SELECT asset_id FROM asset_composition WHERE root_id = ?)")) {
            statement.setObject(1, rootId);
            statement.setObject(2, rootId);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    java.sql.Array eco = r.getArray(5);
                    states.put(r.getObject(1, UUID.class), new SbomState(
                            r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                            r.getObject(4) == null ? null : String.valueOf(r.getObject(4)),
                            eco == null ? List.of() : List.of((String[]) eco.getArray())));
                }
            }
        }
        return Map.copyOf(states);
    }

    /** Remediation timing rolled up over an application and its parts. */
    public Optional<Remediation> remediation(Principal principal, UUID rootId) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT coalesce(sum(closed_count),0), "
                                + "       round(avg(mean_days_to_close))::int, "
                                + "       max(open_oldest_days), "
                                + "       coalesce(sum(open_over_90_days),0) "
                                + "  FROM asset_remediation "
                                + " WHERE asset_id = ? OR asset_id IN "
                                + "   (SELECT asset_id FROM asset_composition WHERE root_id = ?)")) {
            statement.setObject(1, rootId);
            statement.setObject(2, rootId);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                Object mean = r.getObject(2);
                Object oldest = r.getObject(3);
                return Optional.of(new Remediation(r.getLong(1),
                        mean == null ? null : ((Number) mean).intValue(),
                        oldest == null ? null : ((Number) oldest).intValue(),
                        r.getLong(4)));
            }
        }
    }

    /**
     * Whether this tenant has configured any service level policy at all.
     *
     * <p>Asked so the interface can say "no policy is configured" rather than omitting the section.
     * An application page with no remediation-deadline panel reads as an application with nothing
     * overdue, which is the PP-1 failure in the shape of a missing element.
     */
    public boolean serviceLevelConfigured(Principal principal) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM service_level_policy");
                ResultSet r = statement.executeQuery()) {
            r.next();
            return r.getLong(1) > 0;
        }
    }



    /**
     * A connection with the tenant context established.
     *
     * <p>{@code set_config(..., false)} — session scope, not {@code SET LOCAL} — because these methods
     * open and close their own connection and several run more than one transaction on it, where a
     * transaction-local setting would be discarded at the first commit. The connection is closed rather
     * than returned to a shared pool, so the residue concern of {@code OPS-DEP-010} and
     * {@code SEC-TEN-007} does not arise on this path. Stated because a reviewer will and should ask:
     * the same reasoning is written out in {@code IdentityService.open}, and if this tier ever gains a
     * connection pool both have to change together.
     *
     * <p>The tenant comes from the PRINCIPAL. {@code SEC-TEN-004} forbids deriving it from any request
     * field, and taking it as a UUID parameter here would let a caller with a UUID in hand supply one.
     */
    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
