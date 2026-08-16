package aspm.app.inventory;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Projects: the branch of an application one team delivers. {@code PRD-AST-001}, ADR-001, ADR-009.
 *
 * <h2>A project is an asset, and its application is derived</h2>
 *
 * <p>No new aggregate and no new table. ADR-009 keeps <b>one</b> {@code Asset} aggregate with a type
 * registry precisely so a new kind of thing is a row in {@code asset_type} rather than a fifth
 * parallel inventory, and a project is a thing that exists, carries findings, and can be assessed.
 * The type is tenant data, seeded in {@code deploy/seed-projects.sql}; a deployment with no projects
 * simply has none, and this query returns nothing.
 *
 * <p>ADR-001 stays intact and is what makes the model work rather than merely fit. The <b>org tree</b>
 * says who is accountable — a project's {@code owning_node_id} is the team that delivers it, and two
 * projects under one application routinely belong to different teams. The <b>asset graph</b> says
 * what exists and what contains what. Joining them by ownership rather than merging them is why
 * revoking somebody's access to a team does not revoke their access to the application that outlives
 * it.
 *
 * <h2>The application is not a field</h2>
 *
 * <p>{@code applicationId} is resolved by walking {@code asset_composition} up from the project to the
 * nearest root of type {@code APPLICATION}. <b>It is deliberately not a column on the project.</b> A
 * stored parent is a second copy of an answer the graph already holds, and the copy is the one that
 * goes stale when a project moves — which is the case this whole level exists to accommodate.
 *
 * <p>That derivation is also what lets an assessment request name a project and acquire its
 * application: the mapping is a query, so it cannot disagree with the inventory.
 */
public final class ProjectQuery {

    /** The type code this query selects on. Tenant data; absent in a deployment with no projects. */
    public static final String PROJECT_TYPE = "PROJECT";

    /**
     * One project, with the application above it and the posture of everything under it.
     *
     * @param componentCount how many assets the project contains, at any depth
     * @param findingOpen open findings across the project and everything under it, so a project whose
     *     own row is clean but whose services are not does not read as clean
     */
    /**
     * @param ownerAncestors the owning node's ancestors, <b>root first</b>
     * @param ownerAncestorIds the same nodes in the same order, so a reader that renders a name can also
     *     link or filter by it. Positional pairing rather than a list of pairs because the two come from
     *     one {@code array_agg} over one ordering — building pairs in SQL would let them disagree
     */
    public record Project(UUID id, String name, String lifecycleState, String description,
            UUID owningNodeId, String owningNodeName, List<String> ownerAncestors,
            List<UUID> ownerAncestorIds,
            String deliveryTeam, String criticalityCode, boolean criticalityInherited,
            String exposureDeclared, String exposureObserved, boolean exposureConflict,
            UUID applicationId, String applicationName,
            long componentCount, long findingTotal, long findingOpen, long findingAccepted,
            long criticalOpen, long highOpen, long scaOpen, long requestCount,
            String lastDetectedAt) {
    }

    private final DataSource dataSource;

    public ProjectQuery(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /**
     * Every project in the caller's scope.
     *
     * <p>Scoped by {@code owning_node_id} through the closure, the same predicate the application
     * inventory uses. A project is reached through the team that owns it, which is the whole reason
     * the ownership edge exists.
     */
    public List<Project> projects(Principal principal, String search, UUID applicationFilter)
            throws SQLException {
        return projects(principal, search, applicationFilter, null);
    }

    /**
     * The same, narrowed to one or more branches of the organization.
     *
     * <p>Subtree-inclusive, and that is the only defensible reading: the tree exists so that naming a
     * division means the divison and everything under it. Filtering on {@code owning_node_id = ANY} would
     * make a filter on "VinFast" return nothing at all, because no project is owned by a division — they
     * are owned by the teams four levels down, and a filter that silently answers "none" for the level a
     * reader actually thinks in is worse than no filter.
     *
     * <p>Spelled with the same closure predicate as the authorization scope, and applied <b>in addition
     * to</b> it rather than instead of it. A filter is a convenience; the scope is the control, and a
     * request naming a node outside the caller's scope narrows to nothing rather than widening
     * ({@code SEC-AUZ-016} — scope is derived, never asserted by the client).
     *
     * @param orgFilter {@code null} for no filter; an <b>empty</b> list means the caller selected
     *     nothing, which matches nothing. The distinction is the same one the vulnerability dashboard
     *     draws: dropping an emptied multi-select would turn it back into the unfiltered list, and a
     *     filter that widens when you clear it is a filter nobody can trust
     */
    public List<Project> projects(Principal principal, String search, UUID applicationFilter,
            List<UUID> orgFilter) throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        if (orgFilter != null && orgFilter.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(SELECT_PROJECT
                + " WHERE ty.code = ? AND p.lifecycle_state <> 'RETIRED'"
                + "   AND p.owning_node_id IN "
                + "       (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))");
        boolean byOrg = orgFilter != null;
        if (byOrg) {
            sql.append(" AND p.owning_node_id IN "
                    + "     (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))");
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND p.display_name ILIKE ?");
        }
        if (applicationFilter != null) {
            sql.append(" AND app.id = ?");
        }
        sql.append(" ORDER BY app.display_name NULLS LAST, p.display_name");

        List<Project> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, PROJECT_TYPE);
            statement.setArray(index++, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            if (byOrg) {
                statement.setArray(index++,
                        connection.createArrayOf("uuid", orgFilter.toArray(new UUID[0])));
            }
            if (search != null && !search.isBlank()) {
                statement.setString(index++, "%" + search.strip() + "%");
            }
            if (applicationFilter != null) {
                statement.setObject(index, applicationFilter);
            }
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(map(results));
                }
            }
        }
        return List.copyOf(rows);
    }

    /** One project, re-validated against the caller's scope. {@code SEC-AUZ-017}. */
    public Optional<Project> project(Principal principal, UUID id) throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty() || id == null) {
            return Optional.empty();
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(SELECT_PROJECT
                        + " WHERE ty.code = ? AND p.id = ?"
                        + "   AND p.owning_node_id IN "
                        + "       (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))")) {
            statement.setString(1, PROJECT_TYPE);
            statement.setObject(2, id);
            statement.setArray(3, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(map(results)) : Optional.empty();
            }
        }
    }

    /**
     * The application a project belongs to, for an intake form that only asks for the project.
     *
     * <p>Scope-checked through {@link #project}, so a project identifier the caller cannot reach
     * resolves to nothing rather than to somebody else's application name.
     */
    public Optional<UUID> applicationOf(Principal principal, UUID projectId) throws SQLException {
        return project(principal, projectId).map(Project::applicationId)
                .filter(Objects::nonNull);
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * The projection.
     *
     * <p>The rollup comes from {@code application_posture}, which has a row for <b>every</b> asset —
     * a project included — covering the asset and everything beneath it. A rollup that counted only
     * the project's own row would report a project as clean whenever its findings sat on the services
     * beneath it, which is where they almost always sit.
     *
     * <p><b>Corrected twice.</b> This used to sum {@code asset_finding_tally} across the subtree,
     * which counted a finding once per part it touched — a service shared by two features was
     * counted twice inside a single project. And {@code requestCount} read
     * {@code assessment_scope_asset}, which is written only once an assessor has been named, so a
     * project with requests waiting in intake reported zero and read as never assessed.
     * {@code application_posture} and {@code application_request} (V035) count distinct findings and
     * distinct requests, and they are the same views the application inventory reads — so a project
     * and its application can no longer disagree about the same finding.
     */
    private static final String SELECT_PROJECT = """
            SELECT p.id, p.display_name, p.lifecycle_state, p.attributes ->> 'description',
                   p.owning_node_id, n.name,
                   (SELECT array_agg(an.name ORDER BY cl.depth DESC)
                      FROM org_closure cl JOIN org_node an ON an.id = cl.ancestor_id
                     WHERE cl.descendant_id = p.owning_node_id AND cl.depth > 0),
                   -- The same ancestors as identifiers, on the SAME ordering expression. Two aggregates
                   -- over one order, not one aggregate of composites: the column the interface renders
                   -- and the column it filters by have to be the same node, and ordering each list
                   -- separately by its own key is how they would come to disagree.
                   (SELECT array_agg(an.id ORDER BY cl.depth DESC)
                      FROM org_closure cl JOIN org_node an ON an.id = cl.ancestor_id
                     WHERE cl.descendant_id = p.owning_node_id AND cl.depth > 0),
                   p.attributes ->> 'delivery_team',
                   ct.code, p.criticality_mode = 'INHERITED',
                   p.exposure_declared, p.exposure_observed, p.exposure_conflict,
                   app.id, app.display_name,
                   coalesce(roll.component_count, 0), coalesce(roll.finding_total, 0),
                   coalesce(roll.finding_open, 0),
                   coalesce(roll.finding_accepted, 0), coalesce(roll.critical_open, 0),
                   coalesce(roll.high_open, 0), coalesce(roll.sca_open, 0),
                   coalesce(roll.request_total, 0),
                   to_char(roll.last_detected_at, 'YYYY-MM-DD')
              FROM asset p
              JOIN asset_type ty ON ty.id = p.type_id
              LEFT JOIN org_node n ON n.id = p.owning_node_id
              LEFT JOIN criticality_tier ct ON ct.id = p.criticality_tier_id
              LEFT JOIN LATERAL (
                    SELECT ra.id, ra.display_name
                      FROM asset_composition c
                      JOIN asset ra ON ra.id = c.root_id
                      JOIN asset_type rt ON rt.id = ra.type_id AND rt.code = 'APPLICATION'
                     WHERE c.asset_id = p.id AND c.depth > 0
                     ORDER BY c.depth LIMIT 1
              ) app ON true
              LEFT JOIN application_posture roll ON roll.asset_id = p.id
            """;

    private static Project map(ResultSet r) throws SQLException {
        java.sql.Array ancestors = r.getArray(7);
        java.sql.Array ancestorIds = r.getArray(8);
        return new Project(
                r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                r.getObject(5, UUID.class), r.getString(6),
                ancestors == null ? List.of() : List.of((String[]) ancestors.getArray()),
                ancestorIds == null ? List.of() : List.of((UUID[]) ancestorIds.getArray()),
                r.getString(9), r.getString(10), r.getBoolean(11),
                r.getString(12), r.getString(13), r.getBoolean(14),
                r.getObject(15, UUID.class), r.getString(16),
                r.getLong(17), r.getLong(18), r.getLong(19), r.getLong(20),
                r.getLong(21), r.getLong(22), r.getLong(23), r.getLong(24),
                r.getString(25));
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
