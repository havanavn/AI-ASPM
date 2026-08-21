package aspm.app.inventory;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            String lastDetectedAt,
            /* The tenant-declared attribute document, exactly as stored. Values are typed — a
             * MULTI_SELECT is a list and an INTEGER is a number — because flattening them to strings
             * here would leave the editor unable to tell an empty list from an unset field. */
            Map<String, Object> attributes,
            /* The person to call. Distinct from the delivery team in the attributes: a team is
             * accountable and survives someone leaving; a person answers the phone tonight. */
            UUID technicalContactId, String technicalContactName,
            /* Carried so the editor can send it back and a concurrent edit is refused rather than
             * silently overwritten. */
            int rowVersion) {
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
        return projects(principal, search, applicationFilter, orgFilter, Map.of(), List.of());
    }

    /**
     * The same list, additionally narrowed by DECLARED attributes.
     *
     * <p>Matched with jsonb containment, which is the operator {@code ix_asset__attributes} is built
     * for. A MULTI_SELECT is stored as an array, so {@code @> '{"tech_stack":["JAVA"]}'} matches a
     * project using Java among other things — which is the only useful reading of that filter.
     *
     * <p>Applied in the query rather than over the fetched rows. This list is already narrowed by
     * scope, search and organization at the engine, and a filter applied afterwards would report a
     * different total from the one the other filters produce.
     *
     * @param attributeFilters key to required value; a key not in {@code definitions} is ignored
     *     rather than rejected, because a stale bookmark should not be an error page
     * @param definitions the tenant's catalogue for PROJECT. The key is validated against it before
     *     it reaches the query — it is interpolated into a JSON document, and an unvalidated key
     *     would let a caller construct a containment test over a field nobody declared
     */
    public List<Project> projects(Principal principal, String search, UUID applicationFilter,
            List<UUID> orgFilter, Map<String, String> attributeFilters,
            List<aspm.app.inventory.InventoryService.AttributeDefinition> definitions)
            throws SQLException {
        return projects(principal, search, applicationFilter, orgFilter, attributeFilters,
                definitions, List.of());
    }

    /**
     * The same list, additionally narrowed by the hosts a project is published on.
     *
     * <p>Applied at the engine and over <b>the same reach the host column displays</b> — the project
     * and everything beneath it. A filter that searched a narrower set than the column shows would
     * hide rows whose cell is visibly populated, which reads as a broken page rather than as a
     * filter.
     *
     * <p><b>Presence is a predicate, not a fallback.</b> {@code ABSENT} is a {@code NOT EXISTS} and
     * therefore returns projects with no endpoint recorded in that environment — the answer product
     * principle 1 exists to make available, and the one a UAT estate nobody has inventoried is hiding
     * in. It is not the same question as "has an endpoint that is not internet-facing".
     *
     * @param hostFilters environments already validated against the catalogue by the caller. The
     *     environment code is bound as a parameter and never interpolated, but a code the tenant does
     *     not have would still produce a filter that silently matches nothing, which is why the
     *     endpoint checks it rather than passing it through
     */
    public List<Project> projects(Principal principal, String search, UUID applicationFilter,
            List<UUID> orgFilter, Map<String, String> attributeFilters,
            List<aspm.app.inventory.InventoryService.AttributeDefinition> definitions,
            List<aspm.app.inventory.InventoryService.HostFilter> hostFilters)
            throws SQLException {
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
            // Name OR hostname. Somebody pastes a host from an alert into the search box on this page
            // long before they think to look for a separate lookup, and answering "no projects" to a
            // host that is recorded is worse than not offering the search at all.
            sql.append(" AND (p.display_name ILIKE ?"
                    + "  OR EXISTS (SELECT 1 FROM asset_relationship dr "
                    + "               JOIN asset d ON d.id = dr.to_asset_id "
                    + "               JOIN asset_type dt ON dt.id = d.type_id AND dt.code = 'DOMAIN' "
                    + "              WHERE dr.valid_until IS NULL "
                    // The project and anything beneath it, the same reach the host COLUMN uses. A
                    // search that found fewer things than the column displays would read as broken.
                    + "                AND (dr.from_asset_id = p.id "
                    + "                     OR dr.from_asset_id IN "
                    + "                        (SELECT c.asset_id FROM asset_composition c "
                    + "                          WHERE c.root_id = p.id)) "
                    + "                AND d.display_name ILIKE ?))");
        }
        if (applicationFilter != null) {
            sql.append(" AND app.id = ?");
        }
        List<String> containments = new ArrayList<>();
        Map<String, String> declared = new java.util.LinkedHashMap<>();
        for (var definition : definitions) {
            declared.put(definition.key(), definition.dataType());
        }
        for (Map.Entry<String, String> filter : attributeFilters.entrySet()) {
            String dataType = declared.get(filter.getKey());
            if (dataType == null || filter.getValue() == null || filter.getValue().isBlank()) {
                continue;
            }
            sql.append(" AND p.attributes @> ?::jsonb");
            containments.add(containmentFor(filter.getKey(), filter.getValue(), dataType));
        }
        // Hosts. One EXISTS per environment asked about, over the project and its parts — the same
        // reach `InventoryService#hostsByAsset` uses for the column, spelled here because this
        // predicate has to run at the engine to keep the row count and the total in agreement.
        List<aspm.app.inventory.InventoryService.HostFilter> hosts = new ArrayList<>();
        for (var filter : hostFilters == null ? List.<aspm.app.inventory.InventoryService
                .HostFilter>of() : hostFilters) {
            if (filter == null || !filter.active()) {
                continue;
            }
            sql.append("ABSENT".equals(filter.presence()) ? " AND NOT EXISTS (" : " AND EXISTS (")
                    .append("SELECT 1 FROM asset_relationship dr ")
                    .append("  JOIN asset d ON d.id = dr.to_asset_id ")
                    .append("  JOIN asset_type dt ON dt.id = d.type_id AND dt.code = 'DOMAIN' ")
                    .append(" WHERE dr.valid_until IS NULL ")
                    .append("   AND dr.edge_type = 'PUBLISHED_ON' ")
                    .append("   AND coalesce(dr.attributes ->> 'environment', 'UNSPECIFIED') = ? ")
                    .append("   AND (dr.from_asset_id = p.id ")
                    .append("        OR dr.from_asset_id IN (SELECT c.asset_id FROM asset_composition c ")
                    .append("                                 WHERE c.root_id = p.id))");
            if (filter.contains() != null && !filter.contains().isBlank()) {
                sql.append(" AND d.display_name ILIKE ?");
            }
            sql.append(")");
            hosts.add(filter);
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
                statement.setString(index++, "%" + search.strip() + "%");
            }
            if (applicationFilter != null) {
                statement.setObject(index++, applicationFilter);
            }
            for (String containment : containments) {
                statement.setString(index++, containment);
            }
            for (var filter : hosts) {
                statement.setString(index++, filter.environment());
                if (filter.contains() != null && !filter.contains().isBlank()) {
                    // A substring, matched the same way the search box matches a hostname on this
                    // page. Somebody pastes `uat.` from an alert; an equality test would answer "no
                    // projects" to a host that is recorded.
                    statement.setString(index++, "%" + filter.contains().strip() + "%");
                }
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
                   to_char(roll.last_detected_at, 'YYYY-MM-DD'),
                   p.attributes::text, p.technical_contact_id, tc.display_name, p.row_version
              FROM asset p
              JOIN asset_type ty ON ty.id = p.type_id
              LEFT JOIN org_node n ON n.id = p.owning_node_id
              LEFT JOIN criticality_tier ct ON ct.id = p.criticality_tier_id
              -- The named contact. LEFT, because a project with nobody recorded is a real and
              -- reportable state; an inner join would make those projects disappear from the list
              -- entirely, which is the opposite of what an unowned project should do.
              LEFT JOIN principal tc ON tc.id = p.technical_contact_id
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

    /**
     * Every project's declared attributes, folded up onto the application above it.
     *
     * <h2>Why an application can carry a project's fields at all</h2>
     *
     * <p>A project is the branch of an application one team delivers, so every fact recorded on a
     * project is a fact about part of that application. "Which of our applications still have a
     * component behind no WAF" is a question about applications that can only be answered from
     * project rows, and asking somebody to open each application and read its projects is asking
     * them not to ask.
     *
     * <h2>How each kind folds, and what the fold costs</h2>
     *
     * <ul>
     *   <li><b>SINGLE_SELECT, MULTI_SELECT, BOOLEAN</b> — the distinct set across the projects. An
     *       application whose four projects use Java, Go and Java again shows Java and Go. Crucially
     *       a set, not a majority: if one project out of twelve is behind no WAF, {@code NONE}
     *       appears, and that is the reading a security team needs. A "most common" fold would hide
     *       precisely the outlier worth finding.</li>
     *   <li><b>INTEGER</b> — the sum. Endpoint counts add up; nothing else here does.</li>
     *   <li><b>TEXT, LONG_TEXT, URL</b> — <b>not folded, and absent from the result.</b> Concatenating
     *       four descriptions or four architecture links produces a cell nobody reads and a filter
     *       that matches by accident. The field stays available on the project itself.</li>
     * </ul>
     *
     * <p>An application with no projects is absent from the map rather than present with empty
     * values. The two are different: nothing recorded because there is nothing to record, versus
     * nothing recorded because nobody has. The interface draws them differently.
     */
    public Map<UUID, Map<String, Object>> attributesByApplication(Principal principal,
            List<aspm.app.inventory.InventoryService.AttributeDefinition> definitions)
            throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty() || definitions.isEmpty()) {
            return Map.of();
        }
        // Ordered so the sets below come out in a stable order on every load. A cell whose badges
        // reshuffle between refreshes reads as data changing when nothing has.
        Map<UUID, Map<String, java.util.TreeSet<String>>> sets = new java.util.LinkedHashMap<>();
        Map<UUID, Map<String, Long>> sums = new java.util.LinkedHashMap<>();
        Map<UUID, Long> counted = new java.util.LinkedHashMap<>();

        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT app.id, p.attributes::text "
                                + "  FROM asset p "
                                + "  JOIN asset_type ty ON ty.id = p.type_id AND ty.code = ? "
                                + "  JOIN LATERAL (SELECT ra.id "
                                + "                  FROM asset_composition c "
                                + "                  JOIN asset ra ON ra.id = c.root_id "
                                + "                  JOIN asset_type rt ON rt.id = ra.type_id "
                                + "                                    AND rt.code = 'APPLICATION' "
                                + "                 WHERE c.asset_id = p.id AND c.depth > 0 "
                                + "                 ORDER BY c.depth LIMIT 1) app ON true "
                                + " WHERE p.lifecycle_state <> 'RETIRED' "
                                + "   AND p.owning_node_id IN (SELECT descendant_id FROM org_closure "
                                + "                             WHERE ancestor_id = ANY (?))")) {
            statement.setString(1, PROJECT_TYPE);
            statement.setArray(2, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    UUID applicationId = r.getObject(1, UUID.class);
                    Map<String, Object> attributes =
                            aspm.app.runtime.Json.readObject(r.getString(2));
                    counted.merge(applicationId, 1L, Long::sum);
                    for (var definition : definitions) {
                        Object value = attributes.get(definition.key());
                        if (value == null) {
                            continue;
                        }
                        switch (definition.dataType()) {
                            case "INTEGER" -> {
                                if (value instanceof Number number) {
                                    sums.computeIfAbsent(applicationId,
                                                    k -> new java.util.LinkedHashMap<>())
                                            .merge(definition.key(), number.longValue(), Long::sum);
                                }
                            }
                            case "SINGLE_SELECT", "MULTI_SELECT", "BOOLEAN" -> {
                                var bucket = sets
                                        .computeIfAbsent(applicationId,
                                                k -> new java.util.LinkedHashMap<>())
                                        .computeIfAbsent(definition.key(),
                                                k -> new java.util.TreeSet<>());
                                if (value instanceof List<?> list) {
                                    for (Object item : list) {
                                        if (item != null && !String.valueOf(item).isBlank()) {
                                            bucket.add(String.valueOf(item));
                                        }
                                    }
                                } else if (!String.valueOf(value).isBlank()) {
                                    bucket.add(String.valueOf(value));
                                }
                            }
                            default -> { }
                        }
                    }
                }
            }
        }

        Map<UUID, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        for (UUID applicationId : counted.keySet()) {
            Map<String, Object> folded = new java.util.LinkedHashMap<>();
            // Carried so the interface can say "across 4 projects". A set of three values means
            // something different over four projects than over forty, and the cell would otherwise
            // invite the reader to assume the smaller number.
            folded.put("__projects", counted.get(applicationId));
            for (var entry : sets.getOrDefault(applicationId, Map.of()).entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    folded.put(entry.getKey(), List.copyOf(entry.getValue()));
                }
            }
            folded.putAll(sums.getOrDefault(applicationId, Map.of()));
            out.put(applicationId, folded);
        }
        return Map.copyOf(out);
    }

    /**
     * One containment document for one filter.
     *
     * <p>A MULTI_SELECT wraps the value in an array, because the stored value is an array and
     * {@code @>} on a scalar would never match one. A BOOLEAN becomes a JSON boolean and an INTEGER a
     * JSON number, for the same reason: {@code {"third_party":"true"}} does not contain
     * {@code {"third_party":true}}.
     */
    private static String containmentFor(String key, String value, String dataType) {
        Object typed = switch (dataType) {
            case "MULTI_SELECT" -> List.of(value);
            case "BOOLEAN" -> Boolean.valueOf("true".equalsIgnoreCase(value));
            case "INTEGER" -> {
                try {
                    yield Long.valueOf(value.strip());
                } catch (NumberFormatException e) {
                    // A non-numeric value on a number field matches nothing rather than everything.
                    // The alternative — dropping the filter — widens the list silently, which is how
                    // somebody reads a whole estate as the subset they thought they had asked for.
                    yield value;
                }
            }
            default -> value;
        };
        return aspm.app.runtime.Json.write(Map.of(key, typed));
    }

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
                r.getString(25),
                // Read as text and parsed, rather than through the driver's PGobject: the values are
                // typed — a MULTI_SELECT is a JSON array and an INTEGER a JSON number — and a
                // getString on each key would flatten all three into indistinguishable strings.
                aspm.app.runtime.Json.readObject(r.getString(26)),
                r.getObject(27, UUID.class), r.getString(28), r.getInt(29));
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
