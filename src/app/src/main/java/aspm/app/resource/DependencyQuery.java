package aspm.app.resource;

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
 * The figures behind software composition management. V036, DOC-22, ADR-013.
 *
 * <h2>The shape of the module</h2>
 *
 * <p>An SBOM is submitted against an <b>artifact</b> — in practice a repository — and a repository
 * sits under a project, which sits under an application. So every question here is asked at one level
 * of that tree and answered over everything beneath it. {@code application_dependency_posture} (V036)
 * is that rollup, and it reports the same columns at every level so two rows from different levels
 * are comparable.
 *
 * <h2>Scope is composed into every query</h2>
 *
 * <p>{@code SEC-AUZ-016}. Assets are reached through {@code owning_node_id} against the caller's
 * closure expansion, which is the same predicate the application inventory uses — so the dependency
 * tree and the inventory show the same estate. An empty scope returns nothing rather than everything:
 * a principal whose assignments resolve to no node reaches no node, and the opposite default silently
 * grants the estate to a misconfigured grant.
 *
 * <h2>Two timelines, not one</h2>
 *
 * <p>"CVEs closed" is reported from {@code component_advisory.resolved_at} — the component genuinely
 * stopped being affected — and NOT from a work item being closed. A finding closed as a duplicate, or
 * accepted as a risk, is closed work over a component that is still vulnerable. Reporting the two as
 * one number is how a dashboard comes to show a backlog falling while the estate does not change.
 */
public final class DependencyQuery {

    /** Estate-wide headline. Every count carries the population it was measured over. */
    public record Overview(long assets, long assetsWithSbom, long assetsCurrent, long snapshots,
            long components, long directComponents, long vulnerableComponents,
            long advisoriesOpen, long criticalOpen, long highOpen, long mediumOpen, long lowOpen,
            long unratedOpen, long fixableOpen, long resolvedLast90Days, String latestSnapshotAt) {
    }

    /** One month of the module's flow. */
    public record MonthPoint(String label, long snapshots, long advisoriesAppeared,
            long advisoriesResolved, long componentsAdded) {
    }

    /**
     * One node of the application → project → repository tree, with its rollup.
     *
     * @param owningNodeName the node that OWNS this asset — usually the delivery team
     * @param orgName the organization it sits in: the topmost node above that owner, which is the same
     *     definition the projects inventory and the CI/CD table use. Two different facts, both wanted —
     *     the team is who to talk to, the organization is whose estate this is
     */
    public record TreeNode(String id, String name, String typeCode, int depth, String parentId,
            String owningNodeName, long parts, long children, long sbomParts, long componentCount,
            long directCount, long advisoryOpen, long criticalOpen, long highOpen, long mediumOpen,
            long lowOpen, long vulnerableComponents, long fixableOpen, String latestSnapshotAt,
            String sbomQuality, boolean submitsSbom, String orgId, String orgName) {
    }

    /** An advisory, with how far it has spread. */
    public record AdvisoryRow(String id, String advisoryKey, String severityCode,
            Double cvssScore, String summary, String publishedAt, String firstRecordedAt,
            long componentCount, long assetCount, long applicationCount, long unresolved,
            String source) {
    }

    /** A component, with how far it has spread. */
    public record ComponentRow(String id, String purl, String ecosystem, String name, String version,
            long assetCount, long applicationCount, long advisoryOpen, long criticalOpen,
            long highOpen, boolean anyDirect, List<String> licenses) {
    }

    /** Where one advisory or component actually is, for the drill-down. */
    public record Location(String assetId, String assetName, String assetTypeCode,
            List<String> path, String applicationId, String applicationName,
            String componentName, String componentVersion, boolean direct, String fixedVersion) {
    }

    /** One edge of a snapshot's dependency graph, for the tree view. */
    public record Edge(String parentId, String parentName, String parentVersion,
            String childId, String childName, String childVersion, boolean childDirect,
            long childAdvisoryOpen, String childWorstSeverity) {
    }

    private static String inScope(String column) {
        return column + " IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))";
    }

    /**
     * The organization filter, as a SQL fragment.
     *
     * <p>Separate from {@link #inScope}, and both are always applied. Scope is what the caller MAY
     * see and the filter is what they are ASKING to see; collapsing them into one predicate is how a
     * filter comes to widen a permission — set the filter to a node above your scope and the query
     * would return it. Narrowing twice is the only safe order.
     */
    /**
     * The severity filter, as a SQL fragment over the rollup's own columns.
     *
     * <p>Built from a FIXED MAP rather than from the request. The values arrive as text and end up in
     * SQL as column names, and a column name cannot be a bind parameter — so the safe form is to
     * never build one from input, only to select one from a map this class owns. The same reasoning
     * as {@code InventoryService.FILTERABLE}.
     *
     * <p>An unrecognised band is ignored rather than rejected: a stale bookmark should narrow to
     * nothing surprising, not produce an error page.
     */
    private static String withSeverity(String bands, String prefix, String suffix) {
        if (bands == null || bands.isBlank()) {
            return "";
        }
        Map<String, String> columns = Map.of(
                "CRITICAL", "critical_open", "HIGH", "high_open",
                "MEDIUM", "medium_open", "LOW", "low_open");
        List<String> chosen = new ArrayList<>();
        for (String band : bands.split(",")) {
            String column = columns.get(band.strip().toUpperCase(java.util.Locale.ROOT));
            if (column != null && !chosen.contains(column)) {
                chosen.add(prefix + column + suffix + " > 0");
            }
        }
        return chosen.isEmpty() ? "" : " AND (" + String.join(" OR ", chosen) + ")";
    }

    /**
     * The same filter expressed over an advisory's own severity code.
     *
     * <p>Built by matching against a fixed set, so nothing from the request reaches the SQL. UNRATED
     * maps to IS NULL rather than to a code, because that is what an unrated advisory is in the
     * database and inventing a sentinel string would put a value in a column nobody wrote.
     */
    private static String severityBands(String bands) {
        if (bands == null || bands.isBlank()) {
            return "";
        }
        Set<String> known = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNRATED");
        List<String> chosen = new ArrayList<>();
        boolean unrated = false;
        for (String band : bands.split(",")) {
            String value = band.strip().toUpperCase(java.util.Locale.ROOT);
            if (!known.contains(value)) {
                continue;
            }
            if ("UNRATED".equals(value)) {
                unrated = true;
            } else if (!chosen.contains("'" + value + "'")) {
                chosen.add("'" + value + "'");
            }
        }
        if (chosen.isEmpty() && !unrated) {
            return "";
        }
        String coded = chosen.isEmpty() ? "" : "v.severity_code IN (" + String.join(",", chosen) + ")";
        if (unrated) {
            return coded.isEmpty() ? "(v.severity_code IS NULL)"
                    : "(" + coded + " OR v.severity_code IS NULL)";
        }
        return "(" + coded + ")";
    }

    private static String underOrg(String column, UUID org) {
        return org == null ? ""
                : " AND " + column + " IN (SELECT descendant_id FROM org_closure "
                        + "WHERE ancestor_id = ?)";
    }

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public DependencyQuery(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * The headline.
     *
     * <p>{@code assetsWithSbom} against {@code assets} is the figure the module lives or dies by:
     * PRD-SBM-056 requires an asset that never submitted to appear in coverage rather than be absent
     * from it, because absence reads as absence of problems.
     */
    public Overview overview(Principal principal, UUID org) throws SQLException {
        List<Overview> rows = readFiltered(principal, org, """
                WITH visible AS (
                    SELECT a.id FROM asset a
                     WHERE a.lifecycle_state <> 'RETIRED' AND %s%s)
                SELECT (SELECT count(*) FROM visible),
                       (SELECT count(*) FROM sbom_coverage_state cs JOIN visible v ON v.id = cs.asset_id
                         WHERE cs.latest_snapshot_id IS NOT NULL),
                       (SELECT count(*) FROM sbom_coverage_state cs JOIN visible v ON v.id = cs.asset_id
                         WHERE cs.latest_snapshot_id IS NOT NULL AND cs.quality = 'ABOVE_WARNING'),
                       (SELECT count(*) FROM sbom_snapshot s JOIN visible v ON v.id = s.artifact_asset_id),
                       (SELECT count(DISTINCT ac.component_id) FROM asset_component ac
                          JOIN visible v ON v.id = ac.asset_id),
                       (SELECT count(DISTINCT ac.component_id) FROM asset_component ac
                          JOIN visible v ON v.id = ac.asset_id WHERE ac.is_direct),
                       (SELECT count(DISTINCT x.component_id) FROM asset_component_advisory x
                          JOIN visible v ON v.id = x.asset_id WHERE x.resolved_at IS NULL),
                       (SELECT count(DISTINCT x.advisory_id) FROM asset_component_advisory x
                          JOIN visible v ON v.id = x.asset_id WHERE x.resolved_at IS NULL),
                       (SELECT count(DISTINCT x.advisory_id) FROM asset_component_advisory x
                          JOIN visible v ON v.id = x.asset_id WHERE x.resolved_at IS NULL
                            AND x.severity_code = 'CRITICAL'),
                       (SELECT count(DISTINCT x.advisory_id) FROM asset_component_advisory x
                          JOIN visible v ON v.id = x.asset_id WHERE x.resolved_at IS NULL
                            AND x.severity_code = 'HIGH'),
                       (SELECT count(DISTINCT x.advisory_id) FROM asset_component_advisory x
                          JOIN visible v ON v.id = x.asset_id WHERE x.resolved_at IS NULL
                            AND x.severity_code = 'MEDIUM'),
                       (SELECT count(DISTINCT x.advisory_id) FROM asset_component_advisory x
                          JOIN visible v ON v.id = x.asset_id WHERE x.resolved_at IS NULL
                            AND x.severity_code = 'LOW'),
                       -- Unrated is its own figure and is never folded into LOW. An advisory nobody
                       -- rated is not a low-severity advisory; it is one nobody has looked at.
                       (SELECT count(DISTINCT x.advisory_id) FROM asset_component_advisory x
                          JOIN visible v ON v.id = x.asset_id WHERE x.resolved_at IS NULL
                            AND x.severity_code IS NULL),
                       (SELECT count(DISTINCT x.advisory_id) FROM asset_component_advisory x
                          JOIN visible v ON v.id = x.asset_id WHERE x.resolved_at IS NULL
                            AND x.fixed_version IS NOT NULL),
                       (SELECT count(*) FROM asset_component_advisory x
                          JOIN visible v ON v.id = x.asset_id
                         WHERE x.resolved_at > now() - interval '90 days'),
                       (SELECT to_char(max(cs.latest_snapshot_at), 'YYYY-MM-DD')
                          FROM sbom_coverage_state cs JOIN visible v ON v.id = cs.asset_id)
                """.formatted(inScope("a.owning_node_id"), underOrg("a.owning_node_id", org)),
                r -> new Overview(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4),
                        r.getLong(5), r.getLong(6), r.getLong(7), r.getLong(8), r.getLong(9),
                        r.getLong(10), r.getLong(11), r.getLong(12), r.getLong(13), r.getLong(14),
                        r.getLong(15), r.getString(16)));
        return rows.isEmpty()
                ? new Overview(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null)
                : rows.get(0);
    }

    /**
     * SBOMs submitted, advisories that appeared, advisories that were resolved — per month.
     *
     * <p>The month series is generated rather than derived from the rows, so a quiet month is a month
     * of zeros and not a missing point. A chart that omits quiet months compresses its own time axis
     * and turns a burst into a trend.
     */
    public List<MonthPoint> timeline(Principal principal, UUID org, int months) throws SQLException {
        int span = Math.max(1, Math.min(36, months));
        return readFiltered(principal, org, """
                WITH visible AS (
                    SELECT a.id FROM asset a WHERE %s%s),
                     series AS (
                    SELECT generate_series(date_trunc('month', now()) - make_interval(months => %d),
                           date_trunc('month', now()), interval '1 month') AS month_start)
                SELECT to_char(m.month_start, 'YYYY-MM'),
                       (SELECT count(*) FROM sbom_snapshot s JOIN visible v ON v.id = s.artifact_asset_id
                         WHERE s.created_at >= m.month_start
                           AND s.created_at < m.month_start + interval '1 month'),
                       (SELECT count(DISTINCT x.advisory_id) FROM asset_component_advisory x
                          JOIN visible v ON v.id = x.asset_id
                         WHERE x.detected_at >= m.month_start
                           AND x.detected_at < m.month_start + interval '1 month'),
                       (SELECT count(DISTINCT x.advisory_id) FROM asset_component_advisory x
                          JOIN visible v ON v.id = x.asset_id
                         WHERE x.resolved_at >= m.month_start
                           AND x.resolved_at < m.month_start + interval '1 month'),
                       (SELECT count(DISTINCT e.component_id) FROM component_entry e
                          JOIN sbom_snapshot s ON s.id = e.snapshot_id
                          JOIN visible v ON v.id = s.artifact_asset_id
                         WHERE s.created_at >= m.month_start
                           AND s.created_at < m.month_start + interval '1 month')
                  FROM series m ORDER BY m.month_start
                """.formatted(inScope("a.owning_node_id"), underOrg("a.owning_node_id", org),
                        span - 1),
                r -> new MonthPoint(r.getString(1), r.getLong(2), r.getLong(3), r.getLong(4),
                        r.getLong(5)));
    }

    /**
     * The tree, one level at a time.
     *
     * <p>{@code parent} null returns the applications; otherwise the direct children of that asset.
     * Level by level rather than the whole tree in one payload, because the estate is a graph of
     * unknown depth and a reader opens one branch at a time. The rollup on every row covers the whole
     * subtree beneath it, so a collapsed row is not a smaller number than the rows it hides.
     */
    public List<TreeNode> tree(Principal principal, UUID parent, String search, UUID org,
            String severity) throws SQLException {
        boolean roots = parent == null;
        String filter = roots
                ? "t.code = 'APPLICATION' AND " + inScope("a.owning_node_id")
                : "r.from_asset_id = ? AND r.valid_until IS NULL AND " + inScope("a.owning_node_id");
        String join = roots ? ""
                : "JOIN asset_relationship r ON r.to_asset_id = a.id AND r.edge_type = 'CONTAINS' ";
        String searchClause = search == null || search.isBlank() ? ""
                : " AND a.display_name ILIKE ?";
        // Applied only at the ROOT level. Filtering the children of an already-filtered application
        // by the same organization would hide a repository owned by a different team inside an
        // application the filter matched — which is a real arrangement and would look like data loss.
        String orgClause = roots ? underOrg("a.owning_node_id", org) : "";
        // Applied at EVERY level, unlike the organization filter. A reader who filtered to CRITICAL
        // and opened an application wants its critical parts, not all of them — and a row whose
        // subtree holds nothing critical is a row they asked not to see.
        String severityClause = withSeverity(severity, "coalesce(p.", ", 0)");

        String sql = """
                SELECT a.id::text, a.display_name, t.code,
                       %s,
                       n.name,
                       coalesce(p.parts, 0), coalesce(p.sbom_parts, 0),
                       coalesce(p.component_count, 0), coalesce(p.direct_count, 0),
                       coalesce(p.advisory_open, 0), coalesce(p.critical_open, 0),
                       coalesce(p.high_open, 0), coalesce(p.medium_open, 0), coalesce(p.low_open, 0),
                       coalesce(p.vulnerable_components, 0), coalesce(p.fixable_open, 0),
                       to_char(p.latest_snapshot_at, 'YYYY-MM-DD'),
                       cs.quality,
                       (cs.latest_snapshot_id IS NOT NULL),
                       (SELECT count(*) FROM asset_relationship cr
                         WHERE cr.from_asset_id = a.id AND cr.edge_type = 'CONTAINS'
                           AND cr.valid_until IS NULL),
                       org.id::text, org.name
                  FROM asset a
                  JOIN asset_type t ON t.id = a.type_id
                  %s
                  LEFT JOIN org_node n ON n.id = a.owning_node_id
                  -- The topmost ancestor of the owner, which is the organization. `depth DESC` walks to
                  -- the far end of the closure; an owner that IS a root resolves to itself, which is the
                  -- same fallback the projects inventory makes rather than reporting no organization for
                  -- an asset owned directly by a division.
                  LEFT JOIN LATERAL (SELECT an.id, an.name FROM org_closure cl
                                       JOIN org_node an ON an.id = cl.ancestor_id
                                      WHERE cl.descendant_id = a.owning_node_id
                                      ORDER BY cl.depth DESC LIMIT 1) org ON true
                  LEFT JOIN application_dependency_posture p ON p.asset_id = a.id
                  LEFT JOIN sbom_coverage_state cs ON cs.asset_id = a.id
                 WHERE a.lifecycle_state <> 'RETIRED' AND %s%s%s%s
                 ORDER BY coalesce(p.critical_open, 0) DESC, coalesce(p.high_open, 0) DESC,
                          a.display_name
                """.formatted(roots ? "NULL::text" : "r.from_asset_id::text", join, filter,
                        searchClause, orgClause, severityClause);

        List<TreeNode> rows = new ArrayList<>();
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (!roots) {
                statement.setObject(index++, parent);
            }
            statement.setArray(index++, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            if (!searchClause.isEmpty()) {
                statement.setString(index++, "%" + search.strip() + "%");
            }
            if (!orgClause.isEmpty()) {
                statement.setObject(index, org);
            }
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new TreeNode(r.getString(1), r.getString(2), r.getString(3),
                            roots ? 0 : 1, r.getString(4), r.getString(5),
                            r.getLong(6), r.getLong(20), r.getLong(7), r.getLong(8), r.getLong(9),
                            r.getLong(10), r.getLong(11), r.getLong(12), r.getLong(13),
                            r.getLong(14), r.getLong(15), r.getLong(16), r.getString(17),
                            r.getString(18), r.getBoolean(19),
                            r.getString(21), r.getString(22)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Advisories, searchable by identifier.
     *
     * <p>{@code applicationCount} is the figure somebody triaging an advisory actually needs: two
     * hundred affected components across one application is a different morning from two hundred
     * across forty. It is computed by walking each affected asset up to the application that contains
     * it, which is the same walk {@code application_dependency_posture} uses — one definition of
     * "which application is this under".
     */
    public List<AdvisoryRow> advisories(Principal principal, String search, boolean openOnly,
            int limit) throws SQLException {
        String term = search == null ? "" : search.strip();
        String where = term.isBlank() ? "" : " AND upper(a.advisory_key) LIKE upper(?)";
        String openClause = openOnly ? " AND x.resolved_at IS NULL" : "";
        String sql = """
                WITH visible AS (SELECT s.id FROM asset s WHERE %s),
                     hit AS (
                    SELECT x.advisory_id,
                           count(DISTINCT x.component_id)                        AS components,
                           count(DISTINCT x.asset_id)                            AS assets,
                           count(DISTINCT coalesce(app.id, x.asset_id))          AS applications,
                           count(*) FILTER (WHERE x.resolved_at IS NULL)         AS unresolved
                      FROM asset_component_advisory x
                      JOIN visible v ON v.id = x.asset_id
                      -- The NEAREST application above the affected asset, not every root above it.
                      -- A repository sits under a project AND under the application containing that
                      -- project, so counting distinct roots of any type reported one application as
                      -- two. Same walk as ProjectQuery#applicationOf, so the two cannot disagree.
                      LEFT JOIN LATERAL (
                            SELECT ra.id FROM asset_composition c
                              JOIN asset ra ON ra.id = c.root_id
                              JOIN asset_type rt ON rt.id = ra.type_id AND rt.code = 'APPLICATION'
                             WHERE c.asset_id = x.asset_id ORDER BY c.depth LIMIT 1
                      ) app ON true
                     WHERE true %s
                     GROUP BY x.advisory_id)
                SELECT a.id::text, a.advisory_key, sl.code, a.cvss_score, a.summary,
                       to_char(a.published_at, 'YYYY-MM-DD'),
                       to_char(a.first_recorded_at, 'YYYY-MM-DD'),
                       h.components, h.assets, h.applications, h.unresolved, a.source
                  FROM hit h
                  JOIN advisory a ON a.id = h.advisory_id
                  LEFT JOIN severity_level sl ON sl.id = a.severity_id
                 WHERE true %s
                 ORDER BY coalesce(sl.ordinal, 99), h.applications DESC, a.advisory_key
                 LIMIT %d
                """.formatted(inScope("s.owning_node_id"), openClause, where,
                        Math.max(1, Math.min(500, limit)));
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        List<AdvisoryRow> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            if (!where.isEmpty()) {
                statement.setString(2, "%" + term + "%");
            }
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    Double score = r.getObject(4) == null ? null
                            : Double.valueOf(r.getDouble(4));
                    rows.add(new AdvisoryRow(r.getString(1), r.getString(2), r.getString(3), score,
                            r.getString(5), r.getString(6), r.getString(7), r.getLong(8),
                            r.getLong(9), r.getLong(10), r.getLong(11), r.getString(12)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /** Components, searchable by name or package URL. */
    public List<ComponentRow> components(Principal principal, String search, boolean vulnerableOnly,
            int limit) throws SQLException {
        String term = search == null ? "" : search.strip();
        String where = term.isBlank() ? ""
                : " AND (ac.name ILIKE ? OR ac.purl_canonical ILIKE ?)";
        String having = vulnerableOnly ? " HAVING count(DISTINCT x.advisory_id) > 0" : "";
        String sql = """
                WITH visible AS (SELECT s.id FROM asset s WHERE %s)
                SELECT ac.component_id::text, ac.purl_canonical, ac.ecosystem, ac.name, ac.version,
                       count(DISTINCT ac.asset_id),
                       count(DISTINCT coalesce(app.id, ac.asset_id)),
                       count(DISTINCT x.advisory_id) FILTER (WHERE x.resolved_at IS NULL),
                       count(DISTINCT x.advisory_id) FILTER (WHERE x.resolved_at IS NULL
                                                               AND x.severity_code = 'CRITICAL'),
                       count(DISTINCT x.advisory_id) FILTER (WHERE x.resolved_at IS NULL
                                                               AND x.severity_code = 'HIGH'),
                       bool_or(ac.is_direct),
                       (array_agg(DISTINCT lic) FILTER (WHERE lic IS NOT NULL))
                  FROM asset_component ac
                  JOIN visible v ON v.id = ac.asset_id
                  LEFT JOIN LATERAL unnest(ac.license_refs) AS lic ON true
                  LEFT JOIN LATERAL (
                        SELECT ra.id FROM asset_composition c
                          JOIN asset ra ON ra.id = c.root_id
                          JOIN asset_type rt ON rt.id = ra.type_id AND rt.code = 'APPLICATION'
                         WHERE c.asset_id = ac.asset_id ORDER BY c.depth LIMIT 1
                  ) app ON true
                  LEFT JOIN asset_component_advisory x ON x.component_id = ac.component_id
                                                      AND x.asset_id = ac.asset_id
                 WHERE true %s
                 GROUP BY ac.component_id, ac.purl_canonical, ac.ecosystem, ac.name, ac.version
                 %s
                 ORDER BY count(DISTINCT x.advisory_id) FILTER (WHERE x.resolved_at IS NULL) DESC,
                          count(DISTINCT ac.asset_id) DESC, ac.name
                 LIMIT %d
                """.formatted(inScope("s.owning_node_id"), where, having,
                        Math.max(1, Math.min(500, limit)));
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        List<ComponentRow> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            if (!where.isEmpty()) {
                statement.setString(2, "%" + term + "%");
                statement.setString(3, "%" + term + "%");
            }
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    java.sql.Array licenses = r.getArray(12);
                    rows.add(new ComponentRow(r.getString(1), r.getString(2), r.getString(3),
                            r.getString(4), r.getString(5), r.getLong(6), r.getLong(7),
                            r.getLong(8), r.getLong(9), r.getLong(10), r.getBoolean(11),
                            licenses == null ? List.of() : List.of((String[]) licenses.getArray())));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Where an advisory or a component actually sits, with the path down to it.
     *
     * <p>Exactly one of the two identifiers is used; the caller decides which question it is asking.
     * The path is the composition walk, so a reader sees "Card Issuing › Authorization › card-api"
     * rather than a repository name with no context — a repository name alone is the answer to a
     * question nobody asked.
     */
    public List<Location> locations(Principal principal, UUID advisoryId, UUID componentId)
            throws SQLException {
        String predicate = advisoryId != null ? "x.advisory_id = ?" : "x.component_id = ?";
        String sql = """
                SELECT x.asset_id::text, a.display_name, t.code,
                       coalesce(app.path_names, ARRAY[]::text[]),
                       app.root_id::text, app.root_name,
                       x.name, x.version, x.is_direct, x.fixed_version
                  FROM asset_component_advisory x
                  JOIN asset a ON a.id = x.asset_id
                  JOIN asset_type t ON t.id = a.type_id
                  LEFT JOIN LATERAL (
                        SELECT c.root_id, c.path_names, ra.display_name AS root_name
                          FROM asset_composition c
                          JOIN asset ra ON ra.id = c.root_id
                          JOIN asset_type rt ON rt.id = ra.type_id AND rt.code = 'APPLICATION'
                         WHERE c.asset_id = x.asset_id ORDER BY c.depth LIMIT 1
                  ) app ON true
                 WHERE %s AND x.resolved_at IS NULL AND %s
                 ORDER BY app.root_name NULLS LAST, a.display_name, x.name
                """.formatted(predicate, inScope("a.owning_node_id"));
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        List<Location> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, advisoryId != null ? advisoryId : componentId);
            statement.setArray(2, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    java.sql.Array path = r.getArray(4);
                    rows.add(new Location(r.getString(1), r.getString(2), r.getString(3),
                            path == null ? List.of() : List.of((String[]) path.getArray()),
                            r.getString(5), r.getString(6), r.getString(7), r.getString(8),
                            r.getBoolean(9), r.getString(10)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * One asset's dependency graph, from its latest snapshot.
     *
     * <p>Edges rather than a nested structure. The graph is a DAG and a package is routinely reached
     * by two paths; serialising it as a tree would duplicate those subtrees and make a reader count
     * one library twice. The interface assembles the shape it wants from the edges, and can say
     * honestly that a node appears under two parents.
     */
    public List<Edge> dependencyGraph(Principal principal, UUID assetId) throws SQLException {
        return read(principal, """
                SELECT d.parent_component_id::text, pc.name, pc.version,
                       d.child_component_id::text, cc.name, cc.version,
                       (ce.relationship = 1),
                       (SELECT count(DISTINCT ca.advisory_id) FROM component_advisory ca
                         WHERE ca.component_id = d.child_component_id AND ca.resolved_at IS NULL),
                       (SELECT sl.code FROM component_advisory ca
                          JOIN advisory adv ON adv.id = ca.advisory_id
                          LEFT JOIN severity_level sl ON sl.id = adv.severity_id
                         WHERE ca.component_id = d.child_component_id AND ca.resolved_at IS NULL
                         ORDER BY coalesce(sl.ordinal, 99) LIMIT 1)
                  FROM sbom_coverage_state cs
                  JOIN component_dependency d ON d.snapshot_id = cs.latest_snapshot_id
                  JOIN component pc ON pc.id = d.parent_component_id
                  JOIN component cc ON cc.id = d.child_component_id
                  LEFT JOIN component_entry ce ON ce.snapshot_id = cs.latest_snapshot_id
                                              AND ce.component_id = d.child_component_id
                  JOIN asset a ON a.id = cs.asset_id
                 WHERE cs.asset_id = ? AND %s
                 ORDER BY pc.name, cc.name
                """.formatted(inScope("a.owning_node_id")), 0,
                r -> new Edge(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6), r.getBoolean(7), r.getLong(8),
                        r.getString(9)),
                assetId);
    }

    /**
     * One advisory as it applies at one place, with everything needed to act on it.
     *
     * <p>The unit is (advisory, component, asset) rather than advisory alone, and that is the point:
     * the same CVE at two versions of the same library in two repositories is two different pieces of
     * work with two different upgrades, and a list that collapsed them to one row would name one
     * version and hide the other.
     *
     * @param recommendation derived, not stored — "upgrade to 2.17.1" where a fix is published, and
     *     an explicit statement that none is where there is not. A blank cell there reads as missing
     *     data when it means "this one needs a decision rather than an upgrade".
     */
    public record NodeAdvisory(String advisoryId, String advisoryKey, String severity, int ordinal,
            Double cvss, String summary, String description, List<String> cweIds,
            List<String> references, String dataSource, String status,
            String publishedAt, String detectedAt, String source,
            String componentId, String componentName, String componentVersion, String purl,
            String ecosystem, boolean direct, String fixedVersion, String recommendation,
            String assetId, String assetName, String assetTypeCode, String applicationName,
            String projectName, String snapshotAt) {
    }

    /**
     * Every unresolved advisory under one asset, whatever level it is.
     *
     * <p>One query for an application, a project and a repository, because the subtree walk is the
     * same at every level — the caller passes the identifier of whichever row was clicked. Three
     * queries would be three chances to disagree about what "under" means.
     *
     * <p>Resolved occurrences are excluded by default: this is a work list, and something already
     * upgraded away is not work. {@code includeResolved} exists for the reader checking what changed.
     */
    public List<NodeAdvisory> nodeAdvisories(Principal principal, UUID assetId,
            boolean includeResolved, String severity) throws SQLException {
        String resolved = includeResolved ? "" : " AND v.resolved_at IS NULL";
        // Here the filter is on the advisory's OWN band, not on a rollup column, because this list
        // is one row per occurrence. UNRATED is selectable and is its own value: an advisory nobody
        // rated is not a low-severity one, and folding it into LOW would hide the ones nobody has
        // looked at behind the ones somebody judged unimportant.
        String bands = severityBands(severity);
        String bandClause = bands.isEmpty() ? "" : " AND " + bands;
        return read(principal, """
                SELECT v.advisory_id::text, v.advisory_key, v.severity_code,
                       coalesce(v.severity_ordinal, 99), v.cvss_score, v.summary,
                       v.description, v.cwe_ids, v.references_urls, v.data_source, v.status,
                       to_char(v.published_at, 'YYYY-MM-DD'),
                       to_char(v.detected_at, 'YYYY-MM-DD'), v.source_tool,
                       v.component_id::text, v.name, v.version, v.purl_canonical, v.ecosystem,
                       v.is_direct, v.fixed_version,
                       a.display_name, t.code,
                       app.root_name, mid.project_name,
                       to_char(cs.latest_snapshot_at, 'YYYY-MM-DD'),
                       v.asset_id::text
                  FROM asset_component_advisory v
                  JOIN asset a ON a.id = v.asset_id
                  JOIN asset_type t ON t.id = a.type_id
                  LEFT JOIN sbom_coverage_state cs ON cs.asset_id = v.asset_id
                  LEFT JOIN LATERAL (
                        SELECT ra.display_name AS root_name FROM asset_composition c
                          JOIN asset ra ON ra.id = c.root_id
                          JOIN asset_type rt ON rt.id = ra.type_id AND rt.code = 'APPLICATION'
                         WHERE c.asset_id = v.asset_id ORDER BY c.depth LIMIT 1) app ON true
                  LEFT JOIN LATERAL (
                        SELECT ra.display_name AS project_name FROM asset_composition c
                          JOIN asset ra ON ra.id = c.root_id
                          JOIN asset_type rt ON rt.id = ra.type_id AND rt.code = 'PROJECT'
                         WHERE c.asset_id = v.asset_id ORDER BY c.depth LIMIT 1) mid ON true
                 WHERE (v.asset_id = ? OR v.asset_id IN (SELECT c2.asset_id FROM asset_composition c2
                                                          WHERE c2.root_id = ?))%s%s
                   AND %s
                 ORDER BY coalesce(v.severity_ordinal, 99), v.advisory_key, v.name, v.version
                """.formatted(resolved, bandClause, inScope("a.owning_node_id")), 0,
                r -> {
                    String fixed = r.getString(21);
                    String status = r.getString(11);
                    Double score = r.getObject(5) == null ? null : Double.valueOf(r.getDouble(5));
                    java.sql.Array cwes = r.getArray(8);
                    java.sql.Array references = r.getArray(9);
                    return new NodeAdvisory(r.getString(1), r.getString(2), r.getString(3),
                            r.getInt(4), score, r.getString(6), r.getString(7),
                            cwes == null ? List.of() : List.of((String[]) cwes.getArray()),
                            references == null ? List.of()
                                    : List.of((String[]) references.getArray()),
                            r.getString(10), status,
                            r.getString(12), r.getString(13), r.getString(14),
                            r.getString(15), r.getString(16), r.getString(17), r.getString(18),
                            r.getString(19), r.getBoolean(20), fixed,
                            recommendation(fixed, r.getString(16), r.getString(17),
                                    r.getBoolean(20), status),
                            r.getString(27), r.getString(22), r.getString(23), r.getString(24),
                            r.getString(25), r.getString(26));
                },
                assetId, assetId);
    }

    /**
     * What to do about it, in a sentence.
     *
     * <p>Derived here rather than stored, because it is a restatement of two facts the row already
     * carries and a stored copy would go stale the moment a fix is published. It is deliberately not
     * generated by a model: ADR-038 keeps AI narrative bound to record fields and forbids it
     * producing a value, and "upgrade to 2.17.1" is a value.
     */
    private static String recommendation(String fixedVersion, String name, String version,
            boolean direct, String status) {
        // Upstream declining to fix changes the answer entirely, so it is checked FIRST. A row that
        // says "upgrade to …" when the maintainer has said there will be no version to upgrade to
        // sends somebody looking for something that does not exist.
        if (status != null && ("will_not_fix".equalsIgnoreCase(status)
                || "end_of_life".equalsIgnoreCase(status))) {
            return "Upstream will not fix this" + ("end_of_life".equalsIgnoreCase(status)
                    ? " — the package is end of life." : ".")
                    + " There is no upgrade to wait for: either replace " + name
                    + ", put a compensating control in front of it, or accept the risk with an "
                    + "expiry and a named owner.";
        }
        if (fixedVersion != null && !fixedVersion.isBlank()) {
            return direct
                    ? "Upgrade " + name + " from " + version + " to " + fixedVersion + "."
                    : "Upgrade whatever declares " + name + " so it resolves to " + fixedVersion
                            + " or later. It is transitive, so changing " + name + " directly will "
                            + "not hold.";
        }
        return direct
                ? "No fix is published. This one needs a decision — a compensating control, or "
                        + "accepting the risk with an expiry — rather than an upgrade."
                : "No fix is published, and it is transitive. Check whether the declaring dependency "
                        + "can drop it, and record a decision if it cannot.";
    }

    /**
     * One exported row: a component in a place, with the advisory it carries where there is one.
     *
     * <p>Denormalized on purpose. This is what leaves the platform as a spreadsheet, and a reader
     * opening it in Excel has no joins available — every row has to carry its own context or the file
     * is only usable by somebody who already knows the estate.
     */
    public record ExportRow(String applicationName, String projectName, String repositoryName,
            String orgNodeName, String ecosystem, String componentName, String componentVersion,
            String purl, String relationship, String licenses, String advisoryKey, String severity,
            String cvss, String fixedVersion, String detectedAt, String resolvedAt,
            String snapshotAt) {
    }

    /**
     * Everything under one org node or one asset, one row per (component, advisory) — and one row per
     * component where there is no advisory.
     *
     * <p><b>Components with no advisory are included.</b> A CVE list that only lists CVEs cannot tell
     * a reader whether a repository has none because it is clean or because nothing scanned it, and
     * PP-1 makes that the distinction the whole module exists to preserve. The advisory columns are
     * blank on those rows, which is a different thing from the row being absent.
     *
     * @param orgNodeId when set, everything owned at or under this organization node
     * @param assetId when set, this asset and its whole composition subtree — an application, a
     *     project or a repository, because the rollup is the same walk at every level
     */
    public List<ExportRow> exportRows(Principal principal, UUID orgNodeId, UUID assetId)
            throws SQLException {
        String subject = orgNodeId != null
                ? "ac.asset_id IN (SELECT a2.id FROM asset a2 WHERE a2.owning_node_id IN "
                        + "(SELECT descendant_id FROM org_closure WHERE ancestor_id = ?))"
                : "(ac.asset_id = ? OR ac.asset_id IN (SELECT c2.asset_id FROM asset_composition c2 "
                        + "WHERE c2.root_id = ?))";
        String sql = """
                SELECT app.root_name, mid.project_name, a.display_name, n.name,
                       ac.ecosystem, ac.name, ac.version, ac.purl_canonical,
                       CASE WHEN ac.is_direct THEN 'direct' ELSE 'transitive' END,
                       array_to_string(ac.license_refs, '; '),
                       adv.advisory_key, sl.code, adv.cvss_score::text, ca.fixed_version,
                       to_char(ca.detected_at, 'YYYY-MM-DD'),
                       to_char(ca.resolved_at, 'YYYY-MM-DD'),
                       to_char(cs.latest_snapshot_at, 'YYYY-MM-DD')
                  FROM asset_component ac
                  JOIN asset a ON a.id = ac.asset_id
                  LEFT JOIN org_node n ON n.id = a.owning_node_id
                  LEFT JOIN sbom_coverage_state cs ON cs.asset_id = ac.asset_id
                  LEFT JOIN component_advisory ca ON ca.component_id = ac.component_id
                  LEFT JOIN advisory adv ON adv.id = ca.advisory_id
                  LEFT JOIN severity_level sl ON sl.id = adv.severity_id
                  LEFT JOIN LATERAL (
                        SELECT ra.display_name AS root_name FROM asset_composition c
                          JOIN asset ra ON ra.id = c.root_id
                          JOIN asset_type rt ON rt.id = ra.type_id AND rt.code = 'APPLICATION'
                         WHERE c.asset_id = ac.asset_id ORDER BY c.depth LIMIT 1) app ON true
                  LEFT JOIN LATERAL (
                        SELECT ra.display_name AS project_name FROM asset_composition c
                          JOIN asset ra ON ra.id = c.root_id
                          JOIN asset_type rt ON rt.id = ra.type_id AND rt.code = 'PROJECT'
                         WHERE c.asset_id = ac.asset_id ORDER BY c.depth LIMIT 1) mid ON true
                 WHERE %s AND %s
                 ORDER BY app.root_name NULLS LAST, mid.project_name NULLS LAST, a.display_name,
                          coalesce(sl.ordinal, 99), ac.name
                """.formatted(subject, inScope("a.owning_node_id"));
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty() || (orgNodeId == null && assetId == null)) {
            return List.of();
        }
        List<ExportRow> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setObject(index++, orgNodeId != null ? orgNodeId : assetId);
            if (orgNodeId == null) {
                statement.setObject(index++, assetId);
            }
            statement.setArray(index, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new ExportRow(r.getString(1), r.getString(2), r.getString(3),
                            r.getString(4), r.getString(5), r.getString(6), r.getString(7),
                            r.getString(8), r.getString(9), r.getString(10), r.getString(11),
                            r.getString(12), r.getString(13), r.getString(14), r.getString(15),
                            r.getString(16), r.getString(17)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /** The name of an org node or an asset, for the export's filename and its title row. */
    public Optional<String> subjectName(Principal principal, UUID orgNodeId, UUID assetId)
            throws SQLException {
        String sql = orgNodeId != null
                ? "SELECT n.name FROM org_node n WHERE n.id = ? AND " + inScope("n.id")
                : "SELECT a.display_name FROM asset a WHERE a.id = ? AND "
                        + inScope("a.owning_node_id");
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, orgNodeId != null ? orgNodeId : assetId);
            statement.setArray(2, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? Optional.ofNullable(r.getString(1)) : Optional.empty();
            }
        }
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Runs a query whose SQL binds the caller's scope once and then, if present, the org filter.
     *
     * <p>The order is fixed by {@link #underOrg} emitting its placeholder immediately after the scope
     * predicate it narrows. Keeping the binding here rather than at each call site is what stops the
     * two being swapped — and swapping them would silently filter by a scope array and scope by a
     * single node, which returns plausible rows and the wrong ones.
     */
    private <T> List<T> readFiltered(Principal principal, UUID org, String sql, RowMapper<T> mapper)
            throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        List<T> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setArray(index++, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            if (org != null) {
                statement.setObject(index, org);
            }
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(mapper.map(results));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * One node's own flow: advisories appearing and closing under it, month by month.
     *
     * <p>The estate-wide chart answers "is the group getting better". This answers "did WE fix
     * anything", which is the question the team who owns the repository actually has — and the one
     * they cannot get from a figure averaged over everybody else's work.
     *
     * <p>Closing is {@code resolved_at}, which the ingestion sets when a component stops appearing in
     * any bill of materials. So the month a team upgrades away from a library is the month their line
     * moves, and it moves because of what they shipped rather than because somebody closed a ticket.
     */
    public List<MonthPoint> nodeTimeline(Principal principal, UUID assetId, int months)
            throws SQLException {
        int span = Math.max(1, Math.min(36, months));
        return read(principal, """
                WITH members AS (
                    SELECT ? AS id
                    UNION
                    SELECT c.asset_id FROM asset_composition c WHERE c.root_id = ?),
                     series AS (
                    SELECT generate_series(date_trunc('month', now()) - make_interval(months => %d),
                           date_trunc('month', now()), interval '1 month') AS month_start)
                SELECT to_char(m.month_start, 'YYYY-MM'),
                       (SELECT count(*) FROM sbom_snapshot s
                          JOIN members v ON v.id = s.artifact_asset_id
                          JOIN asset a ON a.id = s.artifact_asset_id
                         WHERE s.created_at >= m.month_start
                           AND s.created_at < m.month_start + interval '1 month'
                           AND %s),
                       (SELECT count(DISTINCT x.advisory_id) FROM asset_component_advisory x
                          JOIN members v ON v.id = x.asset_id
                         WHERE x.detected_at >= m.month_start
                           AND x.detected_at < m.month_start + interval '1 month'),
                       (SELECT count(DISTINCT x.advisory_id) FROM asset_component_advisory x
                          JOIN members v ON v.id = x.asset_id
                         WHERE x.resolved_at >= m.month_start
                           AND x.resolved_at < m.month_start + interval '1 month'),
                       0
                  FROM series m ORDER BY m.month_start
                """.formatted(span - 1, inScope("a.owning_node_id")), 0,
                r -> new MonthPoint(r.getString(1), r.getLong(2), r.getLong(3), r.getLong(4),
                        r.getLong(5)),
                assetId, assetId);
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet results) throws SQLException;
    }

    private <T> List<T> read(Principal principal, String sql, int scopeBindings, RowMapper<T> mapper,
            Object... leading) throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        List<T> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (Object value : leading) {
                statement.setObject(index++, value);
            }
            int bindings = Math.max(scopeBindings, 1);
            for (int i = 0; i < bindings; i++) {
                statement.setArray(index++,
                        connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            }
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(mapper.map(results));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * The application / project / repository names for one artifact asset the caller may reach.
     *
     * <p>The ingestion path resolves its target by that triple. The interactive upload knows the
     * asset id instead, so this is the bridge — and it is a bridge in the safe direction: resolving
     * an id to names cannot create an artifact, whereas accepting names from the page could create
     * one from a typo.
     *
     * @return empty when the asset is absent, retired, out of scope, or is an application — an
     *     application is a rollup of the repositories beneath it and has no bill of materials of its
     *     own to replace
     */
    public Optional<SbomIngestion.Target> artifactTarget(Principal principal, UUID assetId)
            throws SQLException {
        if (assetId == null) {
            return Optional.empty();
        }
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT app.display_name, pr.display_name, a.display_name
                          FROM asset a
                          JOIN asset_type t ON t.id = a.type_id AND t.code <> 'APPLICATION'
                          LEFT JOIN LATERAL (
                                SELECT ra.display_name FROM asset_composition cc
                                  JOIN asset ra ON ra.id = cc.root_id
                                  JOIN asset_type rt ON rt.id = ra.type_id
                                       AND rt.code = 'APPLICATION'
                                 WHERE cc.asset_id = a.id ORDER BY cc.depth LIMIT 1) app ON true
                          LEFT JOIN LATERAL (
                                SELECT ra.display_name FROM asset_composition cc
                                  JOIN asset ra ON ra.id = cc.root_id
                                  JOIN asset_type rt ON rt.id = ra.type_id AND rt.code = 'PROJECT'
                                 WHERE cc.asset_id = a.id ORDER BY cc.depth LIMIT 1) pr ON true
                         WHERE a.id = ? AND a.lifecycle_state <> 'RETIRED'
                           AND (a.owning_node_id IS NULL
                                OR a.owning_node_id IN (SELECT descendant_id FROM org_closure
                                                         WHERE ancestor_id = ANY (?)))
                        """)) {
            statement.setObject(1, assetId);
            statement.setArray(2, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SbomIngestion.Target(r.getString(1), r.getString(2),
                        bareRepositoryName(r.getString(3))));
            }
        }
    }

    /**
     * The repository name a pipeline would have sent, recovered from the stored display name.
     *
     * <h2>The defect this closes</h2>
     *
     * <p>An artifact created by a submission that matched no existing asset is stored UNCLAIMED, and
     * its display name is the composed key itself — {@code repo:<application>/<project>/<name>} —
     * rather than the bare repository name a claimed asset carries. Feeding that display name back
     * into the target triple composes a second time, producing
     * {@code repo:App/Project/repo:App/Project/name}: a different key, so the submission CREATED A
     * DUPLICATE ARTIFACT instead of replacing the one the person was looking at.
     *
     * <p>Found by uploading twice against the same row and seeing {@code replacedSnapshotId} come
     * back null the second time, with a fresh asset beside the original in the estate. Stripping the
     * prefix makes the round trip idempotent for both shapes: a claimed asset's name passes through
     * untouched, and an unclaimed one is reduced to what it would have been if it had been claimed.
     */
    private static String bareRepositoryName(String displayName) {
        if (displayName == null || !displayName.startsWith("repo:")) {
            return displayName;
        }
        int lastSlash = displayName.lastIndexOf('/');
        return lastSlash >= 0 && lastSlash + 1 < displayName.length()
                ? displayName.substring(lastSlash + 1)
                : displayName.substring("repo:".length());
    }

    /**
     * Stops tracking one artifact — a repository or a service — by retiring the asset.
     *
     * <h2>Why there is no "delete this SBOM"</h2>
     *
     * <p>{@code INV-SBM-01} makes a snapshot immutable and nothing in the schema grants DELETE on it.
     * That is deliberate: a bill of materials is the record of what a build actually contained on a
     * date, and a vulnerability found against it was really there. Deleting it would erase the
     * subject of findings that remain true — product principle 5.
     *
     * <p>So the operation a person actually wants — "this repository is gone, stop counting it
     * against us" — is retiring the ARTIFACT. Its snapshots stay, its history stays, and it drops out
     * of coverage because a retired asset is not part of the estate. What it must never do is make
     * the estate look cleaner by removing evidence, and retiring cannot: the findings survive.
     *
     * <h2>An application is refused here</h2>
     *
     * <p>Retiring an application is a bigger act with its own flow, its own confirmation and its own
     * optimistic lock. Allowing it through a composition-tree button would be a second, weaker path
     * to the same effect — the pattern {@code CON-PLT-009} exists to stop.
     *
     * @return false when the asset is absent, already retired, out of the caller's scope, or is an
     *     application
     */
    public boolean retireArtifact(Principal principal, UUID assetId, String reason)
            throws SQLException {
        if (assetId == null) {
            return false;
        }
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return false;
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE asset SET lifecycle_state = 'RETIRED', retired_reason = ?,
                               updated_at = now(), updated_by = ?, row_version = row_version + 1
                         WHERE id = ? AND lifecycle_state <> 'RETIRED'
                           AND type_id <> (SELECT id FROM asset_type WHERE code = 'APPLICATION')
                           -- An UNCLAIMED artifact has no owning node, and `IN (...)` is never true
                           -- for NULL — so the first version of this silently refused to retire the
                           -- very rows most in need of it: the ones a submission created because no
                           -- existing asset matched. Exactly the defect that once made tenant-wide
                           -- service credentials un-revokable, reproduced here.
                           --
                           -- Allowing it is not a scope hole. A row with no owning node sits in no
                           -- part of the organization, so there is no scope to be inside of, and
                           -- row-level security still confines this statement to one tenant.
                           AND (owning_node_id IS NULL
                                OR owning_node_id IN (SELECT descendant_id FROM org_closure
                                                       WHERE ancestor_id = ANY (?)))
                        """)) {
            statement.setString(1, reason == null || reason.isBlank()
                    ? "retired from the software composition inventory" : reason.strip());
            statement.setObject(2, principal == null ? null : principal.principalId());
            statement.setObject(3, assetId);
            statement.setArray(4, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                // Retiring an artifact takes its components, and their advisories, out of every
                // dependency figure. PP-1: a count that fell because something stopped being measured
                // must be distinguishable from one that fell because something was fixed.
                audit.domainChange(connection, principal, "asset",
                        aspm.kernel.audit.contract.DomainChangeKind.RETIRED, assetId,
                        aspm.app.audit.AuditScopes.ofAsset(connection, assetId),
                        java.util.Map.of("reason", reason == null || reason.isBlank()
                                ? "retired from the software composition inventory" : reason.strip(),
                                "surface", "software composition"));
            }
            connection.commit();
            return applied;
        }
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
