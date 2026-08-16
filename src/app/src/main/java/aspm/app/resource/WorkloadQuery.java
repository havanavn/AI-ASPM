package aspm.app.resource;

import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The AppSec workload and service-level queries. DOC-12, DOC-28, {@code PRD-CAP-001} onward.
 *
 * <p>Every figure here is computed from the transition log, which is why V014's
 * {@code assessment_request_transition} had to exist first. {@code PRD-CAP-001} requires workload
 * snapshots "by rollup from the state transition history", and {@code INV-WRK-03} makes that history
 * append-only — so a cycle time computed from it is reproducible, which is the property
 * {@code PRD-CAP-011}'s estimation-bias reporting depends on.
 *
 * <h2>What is not computed, and why that is the honest answer</h2>
 *
 * <ul>
 *   <li><b>Utilization.</b> {@code PRD-CAP-005} defines it as allocated effort over available capacity,
 *       and {@code PRD-CAP-002} makes available capacity "net of non-working days, leave, and a configured
 *       non-project overhead allowance". No member capacity ratio or availability is recorded, so the
 *       denominator does not exist. A utilization figure computed against a guessed denominator is worse
 *       than none: it would be quoted in a staffing conversation.
 *   <li><b>Service level compliance.</b> No {@code service_level_policy} is configured and no
 *       {@code service_level_clock} is running, so nothing has a deadline. A compliance figure of 100%
 *       over zero clocks is the PP-1 failure in its most flattering form.
 * </ul>
 *
 * <p>Both return an unmeasured population rather than a zero, so the presentation layer renders the state
 * instead of a numeral.
 */
public final class WorkloadQuery {

    /** Requests grouped by the workflow category their state belongs to. */
    public record FlowBucket(String category, String state, long count, boolean clockRunning) {
    }

    /** Time spent in a state, from the append-only transition log. */
    public record StageTime(String state, long transitions, double averageHours, boolean clockRunning) {
    }

    /** A request waiting on someone. PP-6: waiting is visible and attributed. */
    public record Waiting(String requestCode, String state, String since, String reason,
            long hoursWaiting) {
    }

    /** A findings summary. */
    public record FindingLoad(String severity, long open, long unassigned, long overThirtyDays) {
    }

    /** Per-member allocation. RESTRICTED — see {@code PRD-CAP-013}. */
    public record MemberLoad(String principalId, long assignedFindings, long assignedRequests) {
    }

    private final DataSource dataSource;

    public WorkloadQuery(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Request counts per state, with the category and clock behaviour the workflow definition declares.
     *
     * <p>Joined to {@code workflow_state} rather than grouped by the raw state string. The category is
     * what makes "in progress" mean the same thing across two tenants who named their states
     * differently, and {@code sla_clock_running} is what makes a waiting state distinguishable from a
     * working one — DOC-09 §2.2 lets a tenant add states, so a hardcoded list here would go stale on the
     * first configuration change.
     */
    public List<FlowBucket> flow(Principal principal) throws SQLException {
        return read(principal,
                "SELECT coalesce(w.category, 'UNDEFINED') AS category, r.state, count(*) AS n, "
                        + "       coalesce(w.sla_clock_running, true) AS clock "
                        + "  FROM assessment_request r "
                        + "  LEFT JOIN assessment_type ty ON ty.id = r.type_id "
                        + "  LEFT JOIN workflow_definition d ON d.id = ty.workflow_definition_id "
                        + "  LEFT JOIN workflow_state w ON w.definition_id = d.id AND w.code = r.state "
                        + " WHERE @scope(r) "
                        + " GROUP BY 1, 2, 4 ORDER BY 1, 2",
                results -> new FlowBucket(results.getString(1), results.getString(2),
                        results.getLong(3), results.getBoolean(4)));
    }

    /**
     * Average time in each state. {@code PRD-CAP-008}: cycle time decomposed by stage, <b>including time
     * in states awaiting external parties</b>.
     *
     * <p>The clock flag travels with the figure. Aggregating waiting time into a single cycle time is how
     * a team's own throughput gets blamed for a requester who took three weeks to seed test data — and
     * PP-6 makes waiting visible and attributed for exactly that reason.
     */
    public List<StageTime> stageTimes(Principal principal) throws SQLException {
        // *** THE CLOCK FLAG ON A TRANSITION ROW DESCRIBES THE STATE BEING ENTERED, NOT THE ONE BEING
        // LEFT. *** Grouping by from_state and reading t.sla_clock_running labelled INTAKE_REVIEW —
        // a working stage — as "awaiting others", because the transition out of it went to
        // RETURNED_FOR_INFO, which pauses the clock.
        //
        // That is the worst possible direction for this error: the panel exists to separate waiting
        // time from working time, and it was attributing working time to waiting. The stage's own
        // behaviour comes from the stage's own workflow_state row.
        return read(principal,
                "SELECT t.from_state, count(*) AS n, "
                        + "       avg(extract(epoch FROM t.prior_state_duration)) / 3600.0 AS hours, "
                        + "       bool_and(coalesce(w.sla_clock_running, true)) AS clock "
                        + "  FROM assessment_request_transition t "
                        + "  JOIN assessment_request r ON r.id = t.request_id "
                        + "  JOIN assessment_type ty ON ty.id = r.type_id "
                        + "  JOIN workflow_definition d ON d.id = ty.workflow_definition_id "
                        + "  LEFT JOIN workflow_state w ON w.definition_id = d.id "
                        + "       AND w.code = t.from_state "
                        + " WHERE t.from_state IS NOT NULL AND t.prior_state_duration IS NOT NULL "
                        // Scoped on the REQUEST the transition belongs to, not on the transition: a
                        // transition has no organization of its own, and the request is what somebody
                        // is or is not entitled to see.
                        + "   AND @scope(r) "
                        + " GROUP BY 1 ORDER BY 3 DESC NULLS LAST",
                results -> new StageTime(results.getString(1), results.getLong(2),
                        results.getDouble(3), results.getBoolean(4)));
    }

    /**
     * The waiting queue. {@code PRD-CAP-015}: blocked work with the blocking party, duration, and last
     * escalation, as an <b>actionable</b> queue.
     *
     * <p>The reason comes from the transition that put the request there — DOC-09 §3 requires a reason on
     * a transition into a state whose definition demands one, and {@code request_information} does. So the
     * queue says what is being waited for rather than only that something is.
     */
    public List<Waiting> waiting(Principal principal) throws SQLException {
        return read(principal,
                "SELECT r.request_code, r.state, "
                        + "       to_char(latest.occurred_at, 'YYYY-MM-DD HH24:MI') AS since, "
                        + "       coalesce(latest.reason, '') AS reason, "
                        + "       round(extract(epoch FROM now() - latest.occurred_at) / 3600.0)::bigint "
                        + "  FROM assessment_request r "
                        + "  JOIN assessment_type ty ON ty.id = r.type_id "
                        + "  JOIN workflow_definition d ON d.id = ty.workflow_definition_id "
                        + "  JOIN workflow_state w ON w.definition_id = d.id AND w.code = r.state "
                        + "  LEFT JOIN LATERAL ( "
                        + "        SELECT t.occurred_at, t.reason FROM assessment_request_transition t "
                        + "         WHERE t.request_id = r.id ORDER BY t.sequence_number DESC LIMIT 1 "
                        + "  ) latest ON true "
                        + " WHERE w.sla_clock_running = false AND w.category <> 'TERMINAL' "
                        + "   AND @scope(r) "
                        + " ORDER BY 5 DESC NULLS LAST",
                results -> new Waiting(results.getString(1), results.getString(2),
                        results.getString(3), results.getString(4), results.getLong(5)));
    }

    /** Findings per severity, with the two figures a practitioner acts on. */
    public List<FindingLoad> findingLoad(Principal principal) throws SQLException {
        return read(principal,
                "SELECT s.code, count(*) AS open, "
                        + "       count(*) FILTER (WHERE f.assignee_id IS NULL) AS unassigned, "
                        + "       count(*) FILTER (WHERE f.first_detected_at < now() - interval '30 days')"
                        + "  FROM finding f "
                        + "  JOIN severity_level s ON s.id = coalesce(f.effective_severity_id, "
                        + "                                           f.reported_severity_id) "
                        + " WHERE f.state = 'OPEN' AND @scope(f) "
                        + " GROUP BY s.code, s.ordinal ORDER BY s.ordinal",
                results -> new FindingLoad(results.getString(1), results.getLong(2),
                        results.getLong(3), results.getLong(4)));
    }

    /**
     * Per-member allocation.
     *
     * <p>{@code PRD-CAP-013} classifies this RESTRICTED and requires access "only through explicit
     * permission rather than by role seniority or organizational position". The caller's permission is
     * checked before this is called, and where it is absent the section is <b>omitted</b> rather than
     * blanked — ADR-047: restricted fields are absent from representations, not masked.
     *
     * <p>Deliberately ordered by identifier, not by count. {@code PRD-CAP-014} requires the interface to
     * state that individual measures are for capacity planning and not for performance evaluation or
     * ranking, and a table sorted by volume <i>is</i> a ranking whatever the caption says.
     */
    public List<MemberLoad> memberLoad(Principal principal) throws SQLException {
        return read(principal,
                "SELECT p.principal_id, "
                        + "  (SELECT count(*) FROM finding f "
                        + "    WHERE f.assignee_id = p.principal_id AND f.state = 'OPEN' "
                        + "      AND @scope(f)), "
                        + "  (SELECT count(*) FROM assessment_request r "
                        + "    WHERE r.requested_by = p.principal_id AND @scope(r)) "
                        // The MEMBER LIST is scoped too, and that is the load-bearing one: without it
                        // the page names every assessor in the group to a reader entitled to one
                        // division, with two zeroes beside each. Zeroes are not the disclosure — the
                        // roster is.
                        + "  FROM ( "
                        + "     SELECT DISTINCT fa.assignee_id AS principal_id FROM finding fa "
                        + "      WHERE fa.assignee_id IS NOT NULL AND @scope(fa) "
                        + "  ) p ORDER BY p.principal_id",
                results -> new MemberLoad(String.valueOf(results.getObject(1)),
                        results.getLong(2), results.getLong(3)));
    }

    /**
     * How many service level clocks exist.
     *
     * <p>Returned as a count rather than a compliance percentage, so the caller decides what to render.
     * Zero clocks means compliance is <b>unmeasured</b>: a percentage over an empty set is 100%, and
     * "100% service level compliance" on a platform where nothing has a deadline is the most flattering
     * form of the PP-1 failure.
     */
    public long serviceLevelClocks(Principal principal) throws SQLException {
        // Scoped through the SUBJECT, because a clock has no organization column — it has a
        // subject_kind and a subject_id, and the thing it is attached to is what carries the scope.
        // Unverifiable against data today: this deployment has zero clocks, so this predicate has
        // never returned a row and is written from the schema rather than confirmed by a count.
        return read(principal,
                "SELECT count(*) FROM service_level_clock c "
                        + " WHERE EXISTS (SELECT 1 FROM finding f "
                        + "                WHERE f.id = c.subject_id AND @scope(f)) "
                        + "    OR EXISTS (SELECT 1 FROM assessment_request r "
                        + "                WHERE r.id = c.subject_id AND @scope(r))",
                results -> Long.valueOf(results.getLong(1))).stream()
                .findFirst().orElse(Long.valueOf(0)).longValue();
    }

    /** How many members have a recorded capacity, for the utilization denominator. */
    public long membersWithCapacity(Principal principal) throws SQLException {
        // No table records a capacity ratio or availability (PRD-CAP-002, PRD-CAP-003), so the
        // denominator of PRD-CAP-005 does not exist. Reported as zero measured members rather than
        // computed against a guess.
        return 0L;
    }

    // ----------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet results) throws SQLException;
    }

    /**
     * The authorization predicate, written once and expanded into every query in this class.
     *
     * <h2>The defect this closes</h2>
     *
     * <p>Every query here set the tenant and composed <b>no organization predicate at all</b>, so this
     * page aggregated the whole tenant for anybody who could open it. Measured before the fix: a
     * PENTESTER scoped to one division received the same headline, flow, stage, waiting and finding
     * figures as a tenant-wide administrator — identical on every key. Product principle 4 says scope is
     * derived and never asserted; here it was simply absent, and the page calls itself the team's own
     * workload while answering for the group.
     *
     * <p>Only ADMIN and PENTESTER hold {@code cap.team.read}, and what leaked was aggregate counts
     * rather than records, which is why this was a defect and not an incident. It is still the first of
     * the five highest-risk surfaces — broken object-level authorization — in its aggregate form.
     *
     * <h2>Why a token rather than a concatenated string</h2>
     *
     * <p>The queries are literal SQL with no placeholders, so a predicate appended by hand would have to
     * be kept in step with a parameter index in a second place. The token is expanded and the bindings
     * are counted from the SAME expansion, so the two cannot disagree — and a scope-bearing query that
     * forgot the token fails loudly below rather than quietly returning the estate.
     */
    private static final String TOKEN = "@scope(";

    /**
     * Widens the predicate for a caller who can already see every root.
     *
     * <p>Rows with no organization recorded — one request, seven findings and forty-two assets in this
     * deployment — match no closure and would drop out of every figure the moment a predicate arrived.
     * For a reader entitled to the whole tree that is a silent undercount, and the assets figure would
     * have fallen from sixty-seven to twenty-five with nothing on screen to say why. The same widening
     * `VulnerabilityQuery#admitUnscoped` applies, for the same reason and only for the same callers: an
     * organization-scoped reader cannot be shown a row whose organization is unknown without inventing
     * one for it.
     */
    static String predicate(String alias, boolean seesEveryRoot) {
        String scoped = alias + ".scope_node_id IN (SELECT descendant_id FROM org_closure "
                + "WHERE ancestor_id = ANY (?))";
        return seesEveryRoot
                ? "(" + scoped + " OR " + alias + ".scope_node_id IS NULL)"
                : scoped;
    }

    /** Expands every token, returning the SQL and how many scope arrays it now expects. */
    static Expanded expand(String sql, boolean seesEveryRoot) {
        StringBuilder out = new StringBuilder(sql.length() + 256);
        int at = 0;
        int count = 0;
        while (true) {
            int start = sql.indexOf(TOKEN, at);
            if (start < 0) {
                out.append(sql, at, sql.length());
                break;
            }
            int close = sql.indexOf(')', start);
            if (close < 0) {
                throw new IllegalStateException("an unterminated " + TOKEN + " token: " + sql);
            }
            out.append(sql, at, start)
                    .append(predicate(sql.substring(start + TOKEN.length(), close), seesEveryRoot));
            at = close + 1;
            count++;
        }
        if (count == 0) {
            // Every query in this class reads scope-bearing rows. One that carries no token would run
            // unscoped and look exactly like a working page, which is how this defect survived in the
            // first place.
            throw new IllegalStateException("a workload query with no " + TOKEN + " token would "
                    + "aggregate the whole tenant: " + sql);
        }
        return new Expanded(out.toString(), count);
    }

    record Expanded(String sql, int scopeArrays) {
    }

    /** Whether the caller's scope already covers every root of the organization tree. */
    private static boolean seesEveryRoot(Connection connection, Set<UUID> scope) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT NOT EXISTS (SELECT 1 FROM org_node WHERE parent_id IS NULL "
                        + "AND id <> ALL (?))")) {
            statement.setArray(1, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && results.getBoolean(1);
            }
        }
    }

    private <T> List<T> read(Principal principal, String sql, RowMapper<T> mapper) throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            // No scope is no rows, never every row. SEC-AUZ-014 denies on unavailable scope, and a
            // page that answered "everything" here would be the same defect wearing a guard clause.
            return List.of();
        }
        try (Connection connection =
                aspm.app.persistence.TenantConnections.open(dataSource, principal)) {
            try {
                Expanded expanded = expand(sql, seesEveryRoot(connection, scope));
                List<T> rows = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(expanded.sql())) {
                    java.sql.Array array =
                            connection.createArrayOf("uuid", scope.toArray(new UUID[0]));
                    for (int index = 1; index <= expanded.scopeArrays(); index++) {
                        statement.setArray(index, array);
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

    /** Counts a practitioner opens the page for, in one round trip. */
    public Map<String, Long> headline(Principal principal) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return Map.of();
        }
        try (Connection connection =
                aspm.app.persistence.TenantConnections.open(dataSource, principal)) {
            Expanded expanded = expand("SELECT "
                            + " (SELECT count(*) FROM assessment_request r "
                            + "   WHERE @scope(r)) AS requests_total, "
                            + " (SELECT count(*) FROM assessment_request r "
                            + "   WHERE r.submitted_at > now() - interval '7 days' "
                            + "     AND @scope(r)) AS requests_week, "
                            + " (SELECT count(*) FROM assessment_request r "
                            + "   WHERE r.submitted_at > now() - interval '30 days' "
                            + "     AND @scope(r)) AS requests_month, "
                            + " (SELECT count(*) FROM finding f "
                            + "   WHERE f.state = 'OPEN' AND @scope(f)) AS findings_open, "
                            + " (SELECT count(*) FROM finding f "
                            + "   WHERE f.state = 'OPEN' AND f.assignee_id IS NULL "
                            + "     AND @scope(f)) AS findings_unassigned, "
                            + " (SELECT count(*) FROM asset a WHERE @scope(a)) AS assets",
                    seesEveryRoot(connection, scope));
            try (PreparedStatement statement = connection.prepareStatement(expanded.sql())) {
                java.sql.Array array = connection.createArrayOf("uuid", scope.toArray(new UUID[0]));
                for (int index = 1; index <= expanded.scopeArrays(); index++) {
                    statement.setArray(index, array);
                }
                try (ResultSet results = statement.executeQuery()) {
                results.next();
                out.put("requests_total", Long.valueOf(results.getLong(1)));
                out.put("requests_week", Long.valueOf(results.getLong(2)));
                out.put("requests_month", Long.valueOf(results.getLong(3)));
                out.put("findings_open", Long.valueOf(results.getLong(4)));
                out.put("findings_unassigned", Long.valueOf(results.getLong(5)));
                out.put("assets", Long.valueOf(results.getLong(6)));
                }
            }
            connection.commit();
        }
        return Map.copyOf(out);
    }
}
