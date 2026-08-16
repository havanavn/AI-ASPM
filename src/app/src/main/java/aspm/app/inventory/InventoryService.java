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
            String environment, String exposure, String lifecycleState) {
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
            sql.append(" AND (display_name ILIKE ? OR identity_key ILIKE ?)");
            String pattern = "%" + search.strip() + "%";
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
                                + "       b.lifecycle_state "
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
                            results.getString(6), results.getString(7)));
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

    /** What the editor submits. Every field optional except the name and the owning node. */
    public record ApplicationDraft(UUID id, String name, UUID owningNodeId, UUID criticalityTierId,
            String exposureDeclared, String description, String userBase, String features,
            String tags, String productionDomain, String stagingDomain, String repository,
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
                linkEndpoint(connection, principal, id, draft.productionDomain(), "PRODUCTION");
                linkEndpoint(connection, principal, id, draft.stagingDomain(), "STAGING");
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

    /** Attaches a domain for one environment, replacing whatever was attached for it before. */
    private void linkEndpoint(Connection connection, Principal principal, UUID applicationId,
            String host, String environment) throws SQLException {
        // The previous edge for this environment is CLOSED, not deleted. INV-AST-16 rejects reopening
        // an edge and there is no DELETE grant: "what was deployed when this finding was open" has to
        // stay answerable, so moving an application to a new host leaves the old edge with an end date.
        try (PreparedStatement close = connection.prepareStatement(
                "UPDATE asset_relationship SET valid_until = now(), updated_at = now(), updated_by = ? "
                        + " WHERE from_asset_id = ? AND edge_type = ? AND valid_until IS NULL "
                        + "   AND attributes ->> 'environment' = ?")) {
            close.setObject(1, principal == null ? null : principal.principalId());
            close.setObject(2, applicationId);
            close.setString(3, EDGE_PUBLISHED_ON);
            close.setString(4, environment);
            close.executeUpdate();
        }
        if (blankToNull(host) == null) {
            return;
        }
        UUID domainId = findOrCreateAsset(connection, principal, "DOMAIN", host.strip());
        insertEdge(connection, principal, applicationId, domainId, EDGE_PUBLISHED_ON, environment);
    }

    private void linkRepository(Connection connection, Principal principal, UUID applicationId,
            String repository) throws SQLException {
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
        insertEdge(connection, principal, applicationId, repoId, EDGE_BUILDS, null);
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
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO asset_relationship (tenant_id, from_asset_id, to_asset_id, edge_type, "
                        + "discovery_source, attributes, valid_from, created_by, updated_by) "
                        + "VALUES (current_tenant_id(), ?, ?, ?, 'MANUAL', ?::jsonb, now(), ?, ?)")) {
            insert.setObject(1, from);
            insert.setObject(2, to);
            insert.setString(3, edgeType);
            insert.setString(4, environment == null
                    ? "{}" : aspm.app.runtime.Json.write(Map.of("environment", environment)));
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
            int ordinal) {

        public boolean isSelect() {
            return "SINGLE_SELECT".equals(dataType) || "MULTI_SELECT".equals(dataType);
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
