package aspm.app.resource;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The management view of an AppSec team: who is carrying what, how fast, and what is escaping.
 * DOC-12, {@code PRD-CAP-001} onward.
 *
 * <h2>Every series carries its denominator, and two of them are honestly unmeasurable</h2>
 *
 * <p>A management dashboard is where this platform is most able to mislead, so each method returns
 * the population a figure was computed over and never a bare number. Two things asked for here
 * cannot be computed from what the platform holds, and the honest answer is to say so at the point
 * of use rather than to draw a plausible line:
 *
 * <ul>
 *   <li><b>Service level attainment.</b> No {@code service_level_policy} is configured and no
 *       {@code service_level_clock} is running. Attainment over zero clocks is 100%, and a chart
 *       showing a team hitting every target it does not have is the PP-1 failure at its most
 *       flattering. {@link #serviceLevel} returns the two counts so the interface can name what is
 *       missing instead.
 *   <li><b>Teams.</b> Nothing records which team an assessor belongs to. Rather than invent one,
 *       {@link #byCoverageArea} groups by the organization node each assessor's assignment is scoped
 *       to — a real grouping, and labelled as what it is rather than as "team".
 * </ul>
 *
 * <h2>Individual measures are capacity planning, not performance</h2>
 *
 * <p>{@code PRD-CAP-013} classifies per-person figures RESTRICTED and {@code PRD-CAP-014} requires
 * the purpose to be stated where they are presented. Everything keyed by person here is behind
 * {@code cap.member.read.all} at the endpoint, and every such series is ordered by <b>name</b>
 * rather than by volume — a chart sorted by count is a ranking whatever its caption says.
 */
public final class WorkloadAnalytics {

    /** A count against a label, with the population it came from. */
    public record Slice(String key, String label, long value, long population) {
    }

    /** One period of a time series. */
    public record Period(String label, long opened, long closed) {
    }

    /** Findings found in a period, split by severity. */
    public record SeverityPeriod(String label, long critical, long high, long medium, long other) {
    }

    /** How long work took, per person. Median as well as mean — see {@link #cycleTimeByAssessor}. */
    public record CycleTime(String key, String label, long closed, double meanDays,
            double medianDays) {
    }

    /** Findings that were found after release, against all findings at that severity. */
    public record Escape(String label, long escaped, long total) {
    }

    /** One organization node's review coverage. */
    public record Coverage(String nodeId, String nodeName, String path, long applications,
            long assessedThisYear, long reviewsThisYear, long neverAssessed) {
    }

    /** What is missing before service level attainment can be computed at all. */
    public record ServiceLevel(long policies, long clocks) {

        public boolean measurable() {
            return policies > 0 && clocks > 0;
        }
    }

    private final DataSource dataSource;

    public WorkloadAnalytics(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Requests per assessor in a window.
     *
     * <p>Requests with nobody assigned are returned under their own label rather than dropped. A
     * chart of "work per person" that silently omits unassigned work understates the queue by
     * exactly the part nobody has picked up, which is the part a lead most needs to see.
     */
    public List<Slice> requestsByAssessor(Principal principal, LocalDate from, LocalDate to)
            throws SQLException {
        return slices(principal,
                "SELECT coalesce(b.lead_principal_id::text, 'unassigned'), "
                        + "       coalesce(p.display_name, 'Nobody assigned'), count(*), "
                        + "       sum(count(*)) OVER () "
                        + "  FROM request_board b "
                        + "  LEFT JOIN principal p ON p.id = b.lead_principal_id "
                        + " WHERE " + IN_SCOPE + " AND b.created_at >= ? AND b.created_at < ? "
                        + " GROUP BY 1, 2 ORDER BY 2",
                from, to);
    }

    /**
     * Requests per assessor team, per period.
     *
     * <p>Teams are a real table now (V034), so this groups by membership rather than by the
     * organization node an assignment happens to be scoped to. Membership is exclusive by
     * constraint, which is what lets these bars be summed: a person in two teams would be counted in
     * both and the total would exceed the work that exists.
     *
     * <p>Requests whose assessor is on no team appear under their own label rather than vanishing —
     * a per-team chart that quietly drops unteamed work understates the total by exactly the part
     * nobody has organised.
     */
    public List<Slice> requestsByTeam(Principal principal, LocalDate from, LocalDate to)
            throws SQLException {
        return slices(principal,
                "SELECT coalesce(t.id::text, 'none'), coalesce(t.name, 'No team'), count(*), "
                        + "       sum(count(*)) OVER () "
                        + "  FROM request_board b "
                        + "  LEFT JOIN assessor_team_member m ON m.principal_id = b.lead_principal_id "
                        + "       AND m.removed_at IS NULL "
                        + "  LEFT JOIN assessor_team t ON t.id = m.team_id "
                        + " WHERE " + IN_SCOPE + " AND b.created_at >= ? AND b.created_at < ? "
                        + " GROUP BY 1, 2 ORDER BY 2",
                from, to);
    }

    /**
     * Findings each team recorded, so a team chart is not only about volume of requests.
     */
    public List<Slice> findingsByTeam(Principal principal, LocalDate from, LocalDate to)
            throws SQLException {
        return slices(principal,
                "SELECT coalesce(t.id::text, 'none'), coalesce(t.name, 'No team'), count(*), "
                        + "       sum(count(*)) OVER () "
                        + "  FROM finding f "
                        + "  LEFT JOIN assessor_team_member m ON m.principal_id = f.created_by "
                        + "       AND m.removed_at IS NULL "
                        + "  LEFT JOIN assessor_team t ON t.id = m.team_id "
                        + " WHERE f.scope_node_id IN (SELECT descendant_id FROM org_closure "
                        + "                            WHERE ancestor_id = ANY (?)) "
                        + "   AND f.first_detected_at >= ? AND f.first_detected_at < ? "
                        + " GROUP BY 1, 2 ORDER BY 2",
                from, to);
    }

    /**
     * Requests per coverage area.
     *
     * <p><b>Not "per team" — nothing records team membership.</b> This groups by the organization
     * node each assessor's live assignment is scoped to, which is the area they cover. It is a real
     * grouping and a useful one, and calling it a team would be a claim the data does not support.
     */
    public List<Slice> byCoverageArea(Principal principal, LocalDate from, LocalDate to)
            throws SQLException {
        return slices(principal,
                "SELECT coalesce(n.id::text, 'none'), coalesce(n.name, 'No assigned area'), "
                        + "       count(*), sum(count(*)) OVER () "
                        + "  FROM request_board b "
                        + "  LEFT JOIN LATERAL ( "
                        + "        SELECT ra.scope_node_id FROM role_assignment ra "
                        + "         WHERE ra.principal_id = b.lead_principal_id "
                        + "           AND ra.revoked_at IS NULL AND ra.scope_node_id IS NOT NULL "
                        + "         ORDER BY ra.granted_at LIMIT 1 "
                        + "  ) area ON true "
                        + "  LEFT JOIN org_node n ON n.id = area.scope_node_id "
                        + " WHERE " + IN_SCOPE + " AND b.created_at >= ? AND b.created_at < ? "
                        + " GROUP BY 1, 2 ORDER BY 2",
                from, to);
    }

    /**
     * How long a closed request took, per assessor.
     *
     * <p><b>Median beside the mean, always.</b> One engagement that sat blocked for three months
     * moves a mean of six enough to make a team look slow, and the median says whether that
     * happened. Presenting only the mean is how a single outlier becomes a performance conversation.
     *
     * <p>Only closed requests: an open one has no duration, and counting elapsed-so-far as a
     * duration makes a team look slower the longer they are given.
     */
    public List<CycleTime> cycleTimeByAssessor(Principal principal, LocalDate from, LocalDate to)
            throws SQLException {
        List<CycleTime> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = grouped(connection,
                        "SELECT coalesce(b.lead_principal_id::text, 'unassigned'), "
                                + "       coalesce(p.display_name, 'Nobody assigned'), count(*), "
                                + "       avg(extract(epoch FROM b.closed_at - b.created_at)) "
                                + "         / 86400.0, "
                                + "       percentile_cont(0.5) WITHIN GROUP ( "
                                + "         ORDER BY extract(epoch FROM b.closed_at - b.created_at)) "
                                + "         / 86400.0 "
                                + "  FROM request_board b "
                                + "  LEFT JOIN principal p ON p.id = b.lead_principal_id "
                                + " WHERE " + IN_SCOPE + " AND b.closed_at IS NOT NULL "
                                + "   AND b.closed_at >= ? AND b.closed_at < ? "
                                + " GROUP BY 1, 2 ORDER BY 2",
                        principal, from, to)) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new CycleTime(r.getString(1), r.getString(2), r.getLong(3),
                            r.getDouble(4), r.getDouble(5)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Requests raised and closed, per period.
     *
     * <p>Both series, never a net figure. A net of zero is a team closing forty requests while forty
     * arrive, and it is indistinguishable from a team doing nothing.
     *
     * <p>The period series is generated, so a quiet week is a week with zeros rather than a missing
     * point — a chart that omits quiet periods compresses its own time axis.
     */
    public List<Period> requestTrend(Principal principal, LocalDate from, LocalDate to,
            String granularity) throws SQLException {
        String unit = unit(granularity);
        List<Period> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = series(connection,
                        "WITH series AS (SELECT generate_series(date_trunc('" + unit + "', ?::date), "
                                + "                              date_trunc('" + unit + "', ?::date), "
                                + "                              interval '1 " + unit + "') AS s) "
                                + "SELECT to_char(series.s, '" + format(unit) + "'), "
                                + "  (SELECT count(*) FROM request_board b WHERE " + IN_SCOPE
                                + "     AND b.created_at >= series.s "
                                + "     AND b.created_at < series.s + interval '1 " + unit + "'), "
                                + "  (SELECT count(*) FROM request_board b WHERE " + IN_SCOPE
                                + "     AND b.closed_at >= series.s "
                                + "     AND b.closed_at < series.s + interval '1 " + unit + "') "
                                + "  FROM series ORDER BY series.s",
                        principal, from, to, 2)) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Period(r.getString(1), r.getLong(2), r.getLong(3)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Findings first detected in each period, by severity.
     *
     * <p>Severity is read from the tenant's {@code severity_level} ordinal, not from a hardcoded set
     * of names — ADR-027 makes the scale tenant data, so the top three ordinals are "the three most
     * serious bands this tenant defined" and everything below is grouped.
     */
    public List<SeverityPeriod> findingTrend(Principal principal, LocalDate from, LocalDate to,
            String granularity) throws SQLException {
        String unit = unit(granularity);
        List<SeverityPeriod> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = series(connection,
                        "WITH series AS (SELECT generate_series(date_trunc('" + unit + "', ?::date), "
                                + "                              date_trunc('" + unit + "', ?::date), "
                                + "                              interval '1 " + unit + "') AS s), "
                                + "     f AS (SELECT date_trunc('" + unit + "', x.first_detected_at) AS b, "
                                + "                  coalesce(s.ordinal, 99) AS ord "
                                + "             FROM finding x "
                                + "             LEFT JOIN severity_level s ON s.id = "
                                + "                  coalesce(x.effective_severity_id, x.reported_severity_id) "
                                + "            WHERE x.scope_node_id IN (SELECT descendant_id "
                                + "                    FROM org_closure WHERE ancestor_id = ANY (?))) "
                                + "SELECT to_char(series.s, '" + format(unit) + "'), "
                                + "  count(*) FILTER (WHERE f.ord = 1), "
                                + "  count(*) FILTER (WHERE f.ord = 2), "
                                + "  count(*) FILTER (WHERE f.ord = 3), "
                                + "  count(*) FILTER (WHERE f.ord > 3) "
                                + "  FROM series LEFT JOIN f ON f.b = series.s "
                                + " GROUP BY series.s ORDER BY series.s",
                        principal, from, to, 1)) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new SeverityPeriod(r.getString(1), r.getLong(2), r.getLong(3),
                            r.getLong(4), r.getLong(5)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Serious findings each person recorded in the window.
     *
     * <p>Ordered by name. {@code PRD-CAP-014}: individual measures are for capacity planning, and a
     * table sorted by count is a league table whatever the caption says. It is also the wrong
     * incentive to publish — a tester measured on findings found is a tester who reports noise.
     */
    public List<Slice> seriousFindingsByPerson(Principal principal, LocalDate from, LocalDate to)
            throws SQLException {
        return slices(principal,
                "SELECT coalesce(f.created_by::text, 'system'), "
                        + "       coalesce(p.display_name, 'Automated ingestion'), count(*), "
                        + "       sum(count(*)) OVER () "
                        + "  FROM finding f "
                        + "  LEFT JOIN principal p ON p.id = f.created_by "
                        + "  LEFT JOIN severity_level s ON s.id = "
                        + "       coalesce(f.effective_severity_id, f.reported_severity_id) "
                        + " WHERE f.scope_node_id IN (SELECT descendant_id FROM org_closure "
                        + "                            WHERE ancestor_id = ANY (?)) "
                        + "   AND coalesce(s.ordinal, 99) <= 3 "
                        + "   AND f.first_detected_at >= ? AND f.first_detected_at < ? "
                        + " GROUP BY 1, 2 ORDER BY 2",
                from, to);
    }

    /**
     * Serious findings that reached production before anybody found them.
     *
     * <p><b>The definition matters and is stated rather than buried.</b> "Escaped" means the finding
     * was discovered through a channel that only exists after release — a bug bounty submission or
     * an incident. Those are unambiguous: nobody files a bug bounty against a staging environment.
     * An external penetration test is deliberately NOT counted, because it is routinely run against
     * pre-production and counting it would inflate the number with work that caught things in time.
     *
     * <p>The denominator is every serious finding in the period, so the ratio answers "what fraction
     * of our serious findings did we fail to catch before release" — which is the question worth
     * asking about a testing programme, and the only one that gets worse when testing gets weaker.
     */
    public List<Escape> escapedToProduction(Principal principal, LocalDate from, LocalDate to)
            throws SQLException {
        List<Escape> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = series(connection,
                        "WITH series AS (SELECT generate_series(date_trunc('month', ?::date), "
                                + "                              date_trunc('month', ?::date), "
                                + "                              interval '1 month') AS s) "
                                + "SELECT to_char(series.s, 'YYYY-MM'), "
                                + "  count(f.id) FILTER (WHERE f.assessment_context IN "
                                + "        ('BUG_BOUNTY', 'INCIDENT')), "
                                + "  count(f.id) "
                                + "  FROM series "
                                + "  LEFT JOIN finding f "
                                + "    ON date_trunc('month', f.first_detected_at) = series.s "
                                + "   AND f.scope_node_id IN (SELECT descendant_id FROM org_closure "
                                + "                            WHERE ancestor_id = ANY (?)) "
                                + "   AND coalesce((SELECT s2.ordinal FROM severity_level s2 "
                                + "        WHERE s2.id = coalesce(f.effective_severity_id, "
                                + "                               f.reported_severity_id)), 99) <= 2 "
                                + " GROUP BY series.s ORDER BY series.s",
                        principal, from, to, 1)) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Escape(r.getString(1), r.getLong(2), r.getLong(3)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /** How many findings stood open at the end of each period. */
    public record Backlog(String label, long open, long serious) {
    }

    /**
     * The backlog at the end of each period — <b>the only chart that answers "are we getting better
     * or worse", and the one this dashboard was missing</b>.
     *
     * <p>Everything else here is activity: findings found, requests closed, hours spent. Activity
     * rises when a team works harder and also when a team tests more, so none of it distinguishes a
     * programme that is reducing risk from one that is merely busy. A backlog that falls while
     * activity stays flat is the shape of a team getting ahead; one that rises while activity rises
     * is a team being outrun.
     *
     * <p>Computed from the two dates each finding already carries rather than from a snapshot table,
     * so it is correct retrospectively — a finding closed last week lowers last week's bar, not
     * today's. A stored counter would have to have been running since the estate began.
     */
    public List<Backlog> backlog(Principal principal, LocalDate from, LocalDate to,
            String granularity) throws SQLException {
        String unit = unit(granularity);
        List<Backlog> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = series(connection,
                        "WITH series AS (SELECT generate_series(date_trunc('" + unit + "', ?::date), "
                                + "                              date_trunc('" + unit + "', ?::date), "
                                + "                              interval '1 " + unit + "') AS s) "
                                + "SELECT to_char(series.s, '" + format(unit) + "'), "
                                + "  count(f.id), "
                                + "  count(f.id) FILTER (WHERE coalesce(sv.ordinal, 99) <= 2) "
                                + "  FROM series "
                                + "  LEFT JOIN finding f "
                                + "    ON f.first_detected_at < series.s + interval '1 " + unit + "' "
                                + "   AND (f.closed_at IS NULL "
                                + "        OR f.closed_at >= series.s + interval '1 " + unit + "') "
                                + "   AND f.scope_node_id IN (SELECT descendant_id FROM org_closure "
                                + "                            WHERE ancestor_id = ANY (?)) "
                                + "  LEFT JOIN severity_level sv ON sv.id = "
                                + "       coalesce(f.effective_severity_id, f.reported_severity_id) "
                                + " GROUP BY series.s ORDER BY series.s",
                        principal, from, to, 1)) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Backlog(r.getString(1), r.getLong(2), r.getLong(3)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /** Four headline counts for one window, so two windows can be compared. */
    public record Headline(long requestsRaised, long requestsClosed, long findingsFound,
            long findingsClosed) {
    }

    /**
     * The window's totals, so the interface can show movement rather than a bare number.
     *
     * <p>"Five requests" is not a fact anybody can act on; "five, down from twelve" is. Every
     * headline on this dashboard was an absolute figure with no baseline, which makes a reader
     * supply their own from memory — and the one they supply is usually wrong and always
     * unfalsifiable.
     */
    public Headline headline(Principal principal, LocalDate from, LocalDate to) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = grouped(connection,
                        "SELECT (SELECT count(*) FROM request_board b WHERE " + IN_SCOPE
                                + "    AND b.created_at >= ? AND b.created_at < ?), "
                                + "  (SELECT count(*) FROM request_board b WHERE " + IN_SCOPE
                                + "    AND b.closed_at >= ? AND b.closed_at < ?), "
                                + "  (SELECT count(*) FROM finding f WHERE f.scope_node_id IN "
                                + "       (SELECT descendant_id FROM org_closure "
                                + "         WHERE ancestor_id = ANY (?)) "
                                + "    AND f.first_detected_at >= ? AND f.first_detected_at < ?), "
                                + "  (SELECT count(*) FROM finding f WHERE f.scope_node_id IN "
                                + "       (SELECT descendant_id FROM org_closure "
                                + "         WHERE ancestor_id = ANY (?)) "
                                + "    AND f.closed_at >= ? AND f.closed_at < ?)",
                        principal, from, to, 4)) {
            try (ResultSet r = statement.executeQuery()) {
                r.next();
                return new Headline(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4));
            }
        }
    }

    /** Requests closed in a period, split by whether they met their due date. */
    public record Attainment(String label, long met, long missed, long noDate, long stillOpenLate) {

        /** Only requests that had a date can be judged. */
        public long judged() {
            return met + missed;
        }
    }

    /**
     * Whether closed requests met the date they were given.
     *
     * <p><b>This is the tenant's definition of a service level, and it is a different thing from
     * {@code service_level_policy}.</b> That machinery models targets per severity against a
     * business calendar with pause semantics, and nothing configures it. This measures what the team
     * actually agreed on each request: closed on or before {@code due_at} is met, after it is
     * missed. It is coarser and it is real today, which beats precise and absent.
     *
     * <p>Three honesty properties, each of which a simpler query would get wrong:
     *
     * <ul>
     *   <li><b>Requests with no due date are counted separately, never as met.</b> Older requests
     *       predate the date being mandatory, and folding them into the numerator would report
     *       attainment the platform cannot evidence.
     *   <li><b>Still open and past its date is its own column.</b> Counting it as missed judges work
     *       that is not finished; ignoring it hides the overrun that is happening right now.
     *   <li><b>Dates are compared as dates.</b> Closing at 23:00 on the due date met it; comparing
     *       a timestamp against midnight would score that as a day late.
     * </ul>
     */
    public List<Attainment> dueDateAttainment(Principal principal, LocalDate from, LocalDate to,
            String granularity) throws SQLException {
        String unit = unit(granularity);
        List<Attainment> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = series(connection,
                        "WITH series AS (SELECT generate_series(date_trunc('" + unit + "', ?::date), "
                                + "                              date_trunc('" + unit + "', ?::date), "
                                + "                              interval '1 " + unit + "') AS s) "
                                + "SELECT to_char(series.s, '" + format(unit) + "'), "
                                + "  count(b.id) FILTER (WHERE b.due_at IS NOT NULL "
                                + "        AND b.closed_at::date <= b.due_at::date), "
                                + "  count(b.id) FILTER (WHERE b.due_at IS NOT NULL "
                                + "        AND b.closed_at::date > b.due_at::date), "
                                + "  count(b.id) FILTER (WHERE b.due_at IS NULL), "
                                + "  (SELECT count(*) FROM request_board o "
                                + "    WHERE " + IN_SCOPE.replace("b.", "o.") + " AND o.closed_at IS NULL "
                                + "      AND o.due_at IS NOT NULL AND o.due_at < now() "
                                + "      AND date_trunc('" + unit + "', o.due_at) = series.s) "
                                + "  FROM series "
                                + "  LEFT JOIN request_board b "
                                + "    ON date_trunc('" + unit + "', b.closed_at) = series.s "
                                + "   AND " + IN_SCOPE
                                + " GROUP BY series.s ORDER BY series.s",
                        principal, from, to, 2)) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Attainment(r.getString(1), r.getLong(2), r.getLong(3),
                            r.getLong(4), r.getLong(5)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Review coverage per organization node.
     *
     * <p>{@code neverAssessed} is the column that earns the table. "Eleven of fourteen applications
     * reviewed" reads as good progress and says nothing about whether the missing three are the
     * internet-facing ones; a count of applications nobody has ever looked at is a number somebody
     * has to act on (PP-1).
     */
    public List<Coverage> organizationCoverage(Principal principal) throws SQLException {
        List<Coverage> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = grouped(connection,
                        "SELECT n.id::text, n.name, "
                                + "       coalesce((SELECT string_agg(an.name, ' › ' ORDER BY cl.depth DESC) "
                                + "          FROM org_closure cl JOIN org_node an ON an.id = cl.ancestor_id "
                                + "         WHERE cl.descendant_id = n.id AND cl.depth > 0), ''), "
                                + "       count(a.id), "
                                + "       count(DISTINCT r.asset_id), "
                                + "       coalesce(sum(r.reviews), 0), "
                                + "       count(a.id) FILTER (WHERE r.asset_id IS NULL) "
                                + "  FROM org_node n "
                                + "  LEFT JOIN asset a ON a.owning_node_id = n.id "
                                + "       AND a.lifecycle_state <> 'RETIRED' "
                                + "       AND a.type_id = (SELECT id FROM asset_type "
                                + "                         WHERE code = 'APPLICATION' LIMIT 1) "
                                + "  LEFT JOIN LATERAL ( "
                                + "        SELECT sa.asset_id, count(*) AS reviews "
                                + "          FROM assessment_request_scope_asset sa "
                                + "          JOIN assessment_request req ON req.id = sa.request_id "
                                + "          JOIN assessment_trigger t ON t.id = req.trigger_id "
                                + "         WHERE sa.asset_id = a.id AND t.counts_as_full_review "
                                + "           AND req.created_at >= date_trunc('year', now()) "
                                + "         GROUP BY sa.asset_id "
                                + "  ) r ON true "
                                + " WHERE n.id IN (SELECT descendant_id FROM org_closure "
                                + "                 WHERE ancestor_id = ANY (?)) "
                                + " GROUP BY n.id, n.name HAVING count(a.id) > 0 "
                                + " ORDER BY n.name",
                        principal, null, null)) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Coverage(r.getString(1), r.getString(2), r.getString(3),
                            r.getLong(4), r.getLong(5), r.getLong(6), r.getLong(7)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * What is configured, so the interface can say why attainment is unmeasurable.
     *
     * <p>Two counts rather than a percentage. Attainment over zero clocks computes to 100%, and
     * publishing that would be the most flattering possible form of the PP-1 failure — a team shown
     * hitting every target it has not got.
     */
    public ServiceLevel serviceLevel(Principal principal) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT (SELECT count(*) FROM service_level_policy WHERE state = 'ACTIVE'), "
                                + "       (SELECT count(*) FROM service_level_clock)")) {
            try (ResultSet r = statement.executeQuery()) {
                r.next();
                return new ServiceLevel(r.getLong(1), r.getLong(2));
            }
        }
    }

    /**
     * Open findings by how long they have been open.
     *
     * <p>An addition, and the one a lead reaches for first: a count of open findings says how much
     * there is, and an age profile says whether the oldest of it is being worked. Buckets rather
     * than an average because the shape is the information — forty findings averaging thirty days
     * is a different estate from thirty-nine fresh ones and one from last year.
     */
    public List<Slice> agingProfile(Principal principal) throws SQLException {
        return slices(principal,
                "SELECT bucket, bucket, count(*), sum(count(*)) OVER () FROM ( "
                        + "  SELECT CASE "
                        + "    WHEN f.first_detected_at > now() - interval '7 days'  THEN '1. under a week' "
                        + "    WHEN f.first_detected_at > now() - interval '30 days' THEN '2. under a month' "
                        + "    WHEN f.first_detected_at > now() - interval '90 days' THEN '3. one to three months' "
                        + "    WHEN f.first_detected_at > now() - interval '365 days' THEN '4. three months to a year' "
                        + "    ELSE '5. over a year' END AS bucket "
                        + "    FROM finding f "
                        + "   WHERE f.state = 'OPEN' AND f.scope_node_id IN "
                        + "         (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)) "
                        + ") aged GROUP BY bucket ORDER BY bucket",
                null, null);
    }

    /**
     * Why work is arriving.
     *
     * <p>An addition. A programme where every request is ad hoc has no cadence, and one where every
     * request is a periodic review is not responding to change. Neither is visible from a count of
     * requests, and both change what a lead does next.
     */
    public List<Slice> requestsByTrigger(Principal principal, LocalDate from, LocalDate to)
            throws SQLException {
        return slices(principal,
                "SELECT coalesce(t.code, 'none'), "
                        + "       coalesce(t.label_i18n->>'en', t.code, 'Not stated'), count(*), "
                        + "       sum(count(*)) OVER () "
                        + "  FROM request_board b "
                        + "  LEFT JOIN assessment_request req ON req.id = b.id "
                        + "  LEFT JOIN assessment_trigger t ON t.id = req.trigger_id "
                        + " WHERE " + IN_SCOPE + " AND b.created_at >= ? AND b.created_at < ? "
                        + " GROUP BY 1, 2 ORDER BY 2",
                from, to);
    }

    /**
     * How long a claimed fix waits for a retest.
     *
     * <p>An addition, and newly measurable: V032 separated "a fix is claimed" from "a fix is
     * verified", and the gap between them is a queue that was invisible before. PP-6 — waiting is
     * visible and attributed — and this is the wait the delivery team experiences from us.
     */
    public List<Slice> retestQueue(Principal principal) throws SQLException {
        return slices(principal,
                "SELECT bucket, bucket, count(*), sum(count(*)) OVER () FROM ( "
                        + "  SELECT CASE "
                        + "    WHEN f.remediation_claimed_at > now() - interval '3 days'  THEN '1. under three days' "
                        + "    WHEN f.remediation_claimed_at > now() - interval '7 days'  THEN '2. under a week' "
                        + "    WHEN f.remediation_claimed_at > now() - interval '30 days' THEN '3. under a month' "
                        + "    ELSE '4. over a month' END AS bucket "
                        + "    FROM finding f "
                        + "   WHERE f.state = 'OPEN' AND f.remediation_claimed_at IS NOT NULL "
                        + "     AND f.scope_node_id IN (SELECT descendant_id FROM org_closure "
                        + "                              WHERE ancestor_id = ANY (?)) "
                        + ") q GROUP BY bucket ORDER BY bucket",
                null, null);
    }

    /**
     * Findings that came back after being closed.
     *
     * <p>An addition. {@code recurrence_count} is already maintained by the deduplication pipeline,
     * and a rising reopen rate says a remediation process is closing things that were not fixed —
     * which no other figure on this page would reveal.
     */
    public List<Slice> recurrence(Principal principal) throws SQLException {
        return slices(principal,
                "SELECT bucket, bucket, count(*), sum(count(*)) OVER () FROM ( "
                        + "  SELECT CASE WHEN f.recurrence_count = 0 THEN '1. found once' "
                        + "              WHEN f.recurrence_count = 1 THEN '2. came back once' "
                        + "              ELSE '3. came back repeatedly' END AS bucket "
                        + "    FROM finding f "
                        + "   WHERE f.scope_node_id IN (SELECT descendant_id FROM org_closure "
                        + "                              WHERE ancestor_id = ANY (?)) "
                        + ") rec GROUP BY bucket ORDER BY bucket",
                null, null);
    }

    // ----------------------------------------------------------------------------------------------

    private static final String IN_SCOPE =
            "b.requested_org_node_id IN (SELECT descendant_id FROM org_closure "
                    + "                             WHERE ancestor_id = ANY (?))";

    /** Week or month. Anything else is a week — a caller cannot inject a unit. */
    private static String unit(String granularity) {
        return "month".equalsIgnoreCase(granularity) ? "month" : "week";
    }

    private static String format(String unit) {
        return "month".equals(unit) ? "YYYY-MM" : "IYYY-\"W\"IW";
    }

    private List<Slice> slices(Principal principal, String sql, LocalDate from, LocalDate to)
            throws SQLException {
        List<Slice> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = grouped(connection, sql, principal, from, to)) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Slice(r.getString(1), r.getString(2), r.getLong(3), r.getLong(4)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * A grouped query: one scope array, then the window if there is one.
     *
     * <p>The window is bound as a half-open interval — {@code >= from} and {@code < to + 1 day} — so
     * a range ending today includes today. A closed interval on a timestamp column silently drops
     * everything after midnight on the last day, which is the most recent and most interesting one.
     */
    private PreparedStatement grouped(Connection connection, String sql, Principal principal,
            LocalDate from, LocalDate to) throws SQLException {
        return grouped(connection, sql, principal, from, to, 1);
    }

    /**
     * {@code repeats} groups of (scope array, from, to), in that order.
     *
     * <p>One group for an ordinary grouped query; four for the headline, whose subqueries each carry
     * their own copy. Stated by the caller rather than inferred, for the reason recorded on
     * {@link #series}.
     */
    private PreparedStatement grouped(Connection connection, String sql, Principal principal,
            LocalDate from, LocalDate to, int repeats) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        int index = 1;
        for (int i = 0; i < repeats; i++) {
            statement.setArray(index++, scopeArray(connection, principal));
            if (from != null && to != null) {
                statement.setObject(index++, from.atStartOfDay(java.time.ZoneOffset.UTC)
                        .toOffsetDateTime());
                statement.setObject(index++, to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC)
                        .toOffsetDateTime());
            }
        }
        return statement;
    }

    /**
     * A generated-series query: two dates bounding the series, then {@code scopeArrays} scope arrays.
     *
     * <p>The count is stated by the caller because it varies with how many correlated subqueries the
     * series drives, and getting it wrong is silent until the statement is executed. The first
     * version of this class tried to infer it from one flag shared by three differently shaped
     * queries and bound five parameters into a statement that took four.
     */
    private PreparedStatement series(Connection connection, String sql, Principal principal,
            LocalDate from, LocalDate to, int scopeArrays) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setObject(1, from);
        statement.setObject(2, to);
        for (int i = 0; i < scopeArrays; i++) {
            statement.setArray(3 + i, scopeArray(connection, principal));
        }
        return statement;
    }

    private static java.sql.Array scopeArray(Connection connection, Principal principal)
            throws SQLException {
        Set<UUID> scope = principal.scopeNodeIds();
        return connection.createArrayOf("uuid", scope.toArray(new UUID[0]));
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from "
                + "the authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
