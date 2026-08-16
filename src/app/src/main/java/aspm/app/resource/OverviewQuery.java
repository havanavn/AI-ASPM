package aspm.app.resource;

import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The figures behind the overview dashboard. DOC-12, {@code PRD-UIX-011}, {@code PRD-UIX-022}.
 *
 * <h2>Every figure carries the population it was computed over</h2>
 *
 * <p>Product principle 1 — absence of evidence is not evidence of absence — has a specific
 * consequence for a dashboard: "0 critical findings" over an estate nobody measured is the most
 * reassuring thing this product can display and the most wrong. So nothing here returns a bare
 * number. Each record carries the measured and in-scope populations beside the value, and
 * {@code aspm.module.insight.domain.PresentationState#forMeasure} decides at the boundary whether a
 * numeral may be rendered at all.
 *
 * <h2>Scope is composed into every query, and matches the list the figure links to</h2>
 *
 * <p>{@code SEC-AUZ-016} requires the caller's scope in the retrieval rather than applied to its
 * result. Each query below uses the closure expansion the page it drills into uses — requests
 * through {@code requested_org_node_id} as the board does, findings through {@code scope_node_id} as
 * the finding list does, assets through {@code owning_node_id} as the application inventory does.
 *
 * <p>That consistency is deliberate and is worth more than a larger total would be. <b>A headline
 * figure that disagrees with the list it links to is the defect a user notices first</b>, and they
 * cannot tell whether the summary is over-counting or the list is hiding rows — so they stop
 * trusting both.
 *
 * <h2>What is deliberately not computed here</h2>
 *
 * <ul>
 *   <li><b>Risk score.</b> DOC-28's model is not implemented, so no application has one. The
 *       inventory reports that as the unmeasured state and this page does not restate it as a
 *       number.
 *   <li><b>Service level compliance.</b> No policy is configured, so nothing has a deadline. See
 *       {@link WorkloadQuery} for why a percentage over zero clocks is the worst available answer.
 * </ul>
 */
public final class OverviewQuery {

    /**
     * Findings at one severity, with the figures a practitioner acts on.
     *
     * <p>{@code total} counts every finding at this severity whatever its state, and it is what makes
     * the open count measurable: zero open findings over zero findings is unmeasured, and zero open
     * over forty closed is a genuine result. Without the denominator the two are the same number.
     */
    public record SeverityLoad(String code, int ordinal, long total, long open, long unassigned,
            long agedOverThirtyDays) {
    }

    /**
     * One week of finding flow.
     *
     * <p>Opened is counted from {@code first_detected_at} and closed from {@code closed_at}, so a
     * finding detected in one week and closed in another contributes to both — which is the point.
     * A single "net change" figure hides a team closing forty findings while forty-one arrive.
     */
    public record TrendPoint(String label, long opened, long closed) {
    }

    /** The request queue, as counts. */
    public record RequestLoad(long total, long open, long overdue, long unassigned,
            long closedThirtyDays) {
    }

    /**
     * The estate, and how much of it has been measured.
     *
     * <p>{@code applicationsReviewed} counts applications with at least one completed full review —
     * not one in flight, and not one that was abandoned. An abandoned review discharging a review
     * obligation is a defect this platform has already had once.
     */
    public record Estate(int applications, int applicationsReviewed, int assets, int assetsWithSbom,
            int assetsWithCurrentSbom, int nodes) {
    }

    /** A recently detected finding, for the drill-down list. */
    public record RecentFinding(String id, String requestId, String title, String severity,
            String state, String firstDetectedAt, String sourceTool) {
    }

    /**
     * The closure expansion, as a SQL fragment.
     *
     * <p>Interpolated rather than parameterized because it is structure, not a value; the node
     * identifiers themselves are always bound. The column name is supplied by this class and never
     * by a caller.
     */
    private static String inScope(String column) {
        return column + " IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))";
    }

    private final DataSource dataSource;

    public OverviewQuery(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Open findings per severity.
     *
     * <p>A LEFT JOIN to {@code severity_level}, and an unrated finding is reported under its own code
     * rather than dropped. An inner join here would remove findings nobody has rated from a count of
     * findings, which is the same failure as reporting them as zero: the estate looks smaller than it
     * is, and the rows that vanished are precisely the ones nobody has looked at.
     */
    public List<SeverityLoad> severities(Principal principal) throws SQLException {
        return read(principal,
                "SELECT coalesce(s.code, 'UNRATED') AS code, coalesce(s.ordinal, 9999) AS ordinal, "
                        + "       count(*) AS total, "
                        + "       count(*) FILTER (WHERE f.state = 'OPEN') AS open, "
                        + "       count(*) FILTER (WHERE f.state = 'OPEN' "
                        + "                        AND f.assignee_id IS NULL) AS unassigned, "
                        + "       count(*) FILTER (WHERE f.state = 'OPEN' AND f.first_detected_at "
                        + "                        < now() - interval '30 days') AS aged "
                        + "  FROM finding f "
                        + "  LEFT JOIN severity_level s "
                        + "         ON s.id = coalesce(f.effective_severity_id, f.reported_severity_id) "
                        + " WHERE " + inScope("f.scope_node_id")
                        + " GROUP BY 1, 2 ORDER BY 2",
                1, results -> new SeverityLoad(results.getString(1), results.getInt(2),
                        results.getLong(3), results.getLong(4), results.getLong(5),
                        results.getLong(6)));
    }

    /**
     * Finding flow, one row per ISO week, oldest first.
     *
     * <p>The week series is generated rather than derived from the rows, so a week in which nothing
     * happened is a week with zeros rather than a missing point. A chart that omits quiet weeks
     * compresses the time axis and makes a burst look like a trend.
     */
    public List<TrendPoint> trend(Principal principal, int weeks) throws SQLException {
        int span = Math.max(1, Math.min(52, weeks));
        return read(principal,
                "WITH series AS ( "
                        + "  SELECT generate_series( "
                        + "           date_trunc('week', now()) - make_interval(weeks => " + (span - 1)
                        + "), date_trunc('week', now()), interval '1 week') AS week_start) "
                        + "SELECT to_char(w.week_start, 'IYYY-\"W\"IW') AS label, "
                        + "  (SELECT count(*) FROM finding f "
                        + "    WHERE " + inScope("f.scope_node_id")
                        + "      AND f.first_detected_at >= w.week_start "
                        + "      AND f.first_detected_at < w.week_start + interval '1 week') AS opened, "
                        + "  (SELECT count(*) FROM finding f "
                        + "    WHERE " + inScope("f.scope_node_id")
                        + "      AND f.closed_at >= w.week_start "
                        + "      AND f.closed_at < w.week_start + interval '1 week') AS closed "
                        + "  FROM series w ORDER BY w.week_start",
                2, results -> new TrendPoint(results.getString(1), results.getLong(2),
                        results.getLong(3)));
    }

    /**
     * The request queue.
     *
     * <p>Terminality is read from {@code state_category} rather than from the state code. DOC-09 lets
     * a tenant name its states, so a code-matching test here would go stale on the first workflow
     * configuration change and would silently count closed work as open.
     */
    public RequestLoad requests(Principal principal) throws SQLException {
        List<RequestLoad> rows = read(principal,
                "SELECT count(*) AS total, "
                        + "       count(*) FILTER (WHERE coalesce(state_category, '') <> 'TERMINAL') "
                        + "         AS open, "
                        + "       count(*) FILTER (WHERE due_at < now() "
                        + "         AND coalesce(state_category, '') <> 'TERMINAL') AS overdue, "
                        + "       count(*) FILTER (WHERE lead_principal_id IS NULL "
                        + "         AND coalesce(state_category, '') <> 'TERMINAL') AS unassigned, "
                        + "       count(*) FILTER (WHERE closed_at > now() - interval '30 days') "
                        + "         AS closed_recently "
                        + "  FROM request_board WHERE " + inScope("requested_org_node_id"),
                1, results -> new RequestLoad(results.getLong(1), results.getLong(2),
                        results.getLong(3), results.getLong(4), results.getLong(5)));
        return rows.isEmpty() ? new RequestLoad(0, 0, 0, 0, 0) : rows.get(0);
    }

    /**
     * The estate and its measured fractions.
     *
     * <p>{@code assetsWithSbom} is {@code latest_snapshot_at IS NOT NULL}, never {@code quality IS
     * NOT NULL}. {@code sbom_coverage_state.quality} is NOT NULL with a default, so every row has one
     * whether or not anything was ever submitted — reading absence off that column reported assets
     * nobody had scanned as assets that were scanned and failed. The same trap is documented at
     * {@code CompositionPage}.
     */
    public Estate estate(Principal principal) throws SQLException {
        List<Estate> rows = read(principal,
                "SELECT "
                        + " (SELECT count(*) FROM asset a JOIN asset_type t ON t.id = a.type_id "
                        + "   WHERE t.code = 'APPLICATION' AND a.lifecycle_state <> 'RETIRED' "
                        + "     AND " + inScope("a.owning_node_id") + ") AS applications, "
                        + " (SELECT count(*) FROM asset a JOIN asset_type t ON t.id = a.type_id "
                        + "   JOIN application_review_cadence c ON c.asset_id = a.id "
                        + "   WHERE t.code = 'APPLICATION' AND a.lifecycle_state <> 'RETIRED' "
                        + "     AND c.full_review_count > 0 "
                        + "     AND " + inScope("a.owning_node_id") + ") AS reviewed, "
                        + " (SELECT count(*) FROM asset a WHERE a.lifecycle_state <> 'RETIRED' "
                        + "     AND " + inScope("a.owning_node_id") + ") AS assets, "
                        + " (SELECT count(*) FROM asset a "
                        + "   JOIN sbom_coverage_state c ON c.asset_id = a.id "
                        + "   WHERE a.lifecycle_state <> 'RETIRED' "
                        + "     AND c.latest_snapshot_at IS NOT NULL "
                        + "     AND " + inScope("a.owning_node_id") + ") AS with_sbom, "
                        + " (SELECT count(*) FROM asset a "
                        + "   JOIN sbom_coverage_state c ON c.asset_id = a.id "
                        + "   WHERE a.lifecycle_state <> 'RETIRED' "
                        + "     AND c.latest_snapshot_at IS NOT NULL AND c.quality = 'ABOVE_WARNING' "
                        + "     AND " + inScope("a.owning_node_id") + ") AS current_sbom, "
                        + " (SELECT count(*) FROM org_node n WHERE " + inScope("n.id") + ") AS nodes",
                6, results -> new Estate(results.getInt(1), results.getInt(2), results.getInt(3),
                        results.getInt(4), results.getInt(5), results.getInt(6)));
        return rows.isEmpty() ? new Estate(0, 0, 0, 0, 0, 0) : rows.get(0);
    }

    /**
     * The most recently detected open findings.
     *
     * <p>{@code discovered_in_request_id} travels with each row so the interface can link to the
     * finding where it lives — inside its request. A dashboard row that links nowhere is a figure the
     * reader has to trust, and DOC-12 requires drill-down for that reason.
     */
    public List<RecentFinding> recent(Principal principal, int limit) throws SQLException {
        int rows = Math.max(1, Math.min(50, limit));
        return read(principal,
                "SELECT f.id, f.discovered_in_request_id, f.title, coalesce(s.code, 'UNRATED'), "
                        + "       f.state, to_char(f.first_detected_at, 'YYYY-MM-DD'), f.source_tool "
                        + "  FROM finding f "
                        + "  LEFT JOIN severity_level s "
                        + "         ON s.id = coalesce(f.effective_severity_id, f.reported_severity_id) "
                        + " WHERE f.state = 'OPEN' AND " + inScope("f.scope_node_id")
                        + " ORDER BY f.first_detected_at DESC LIMIT " + rows,
                1, results -> new RecentFinding(
                        String.valueOf(results.getObject(1)),
                        results.getObject(2) == null ? null : String.valueOf(results.getObject(2)),
                        results.getString(3), results.getString(4), results.getString(5),
                        results.getString(6), results.getString(7)));
    }

    // ----------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet results) throws SQLException;
    }

    /**
     * Runs a query with the tenant set and the caller's scope bound {@code scopeBindings} times.
     *
     * <p>An empty scope returns nothing rather than everything. A principal whose role assignments
     * resolve to no node reaches no node, and the failure mode of the opposite default — treating an
     * empty set as "unrestricted" — is that a misconfigured assignment silently grants the estate.
     */
    private <T> List<T> read(Principal principal, String sql, int scopeBindings, RowMapper<T> mapper)
            throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        Set<UUID> scope = principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        try (Connection connection =
                aspm.app.persistence.TenantConnections.open(dataSource, principal)) {
            try {
                List<T> rows = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int binding = 1; binding <= scopeBindings; binding++) {
                        statement.setArray(binding,
                                connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
                    }
                    try (ResultSet results = statement.executeQuery()) {
                        while (results.next()) {
                            rows.add(mapper.map(results));
                        }
                    }
                }
                connection.commit();
                return List.copyOf(rows);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }
}
