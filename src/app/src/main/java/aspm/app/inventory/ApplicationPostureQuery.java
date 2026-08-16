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
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The security posture of one application, as the figures its dashboard draws. DOC-12,
 * {@code PRD-UIX-011}, {@code PRD-UIX-022}.
 *
 * <h2>What a finding has to do with an application</h2>
 *
 * <p>Two edges, and reading only one of them is the defect V035 corrects. {@code finding_asset_impact}
 * is written by the ingestion pipeline and by the manual form when an assessor names a specific
 * asset; the request a finding was discovered in is written by intake on every request. Most work
 * takes the second path, so a page reading only the first reported zero for an application with a
 * hundred and fifty-eight findings against it. {@code asset_finding_link} and
 * {@code application_finding} state the union once, and every query here reads them rather than
 * restating the join — one name, one meaning, one place.
 *
 * <h2>The figures are the application's, not the reader's</h2>
 *
 * <p>Every query below is keyed on one application identifier and composes <b>no org-scope
 * predicate</b>. That is deliberate and it is not a gap:
 *
 * <ul>
 *   <li>The caller reached this page through {@link InventoryService#application}, which authorizes
 *       the application against the caller's scope before anything here runs. The object-level
 *       decision has already been made, on the object being read.
 *   <li>The posture of an application is a property of the application. A reader scoped to one team
 *       inside it would otherwise see a smaller, cleaner application than it is — and product
 *       principle 1 says the most dangerous output this platform can produce is a clean-looking
 *       estate that was merely not measured. A partial view labelled as the whole is that output.
 *   <li>{@code application_inventory} counts the same way, so the list and this page cannot
 *       disagree. A headline figure that contradicts the row that linked to it is the defect a user
 *       notices first, and they cannot tell which of the two is lying.
 * </ul>
 *
 * <p>The consequence is stated rather than hidden: these counts can exceed what the same reader sees
 * in a scope-filtered finding list, and the interface says so beside the drill-down.
 *
 * <h2>Nothing here renders a numeral it cannot justify</h2>
 *
 * <p>{@code PRD-UIX-022}. A mean time to remediate over zero closed findings is NULL, never zero —
 * zero days reads as "fixed the same day", which is the most flattering possible reading of no
 * evidence at all. A month with no findings is an unmeasured month, not a good one. Where a record
 * below carries a {@code null} it is carrying that distinction, and the interface renders the word.
 */
public final class ApplicationPostureQuery {

    /**
     * The headline figures, from {@code application_posture}.
     *
     * @param meanDaysToClose null where nothing has ever closed. See the class note.
     * @param openOldestDays null where nothing is open — which is a genuinely clean result and is
     *     not the same as an unmeasured one, so the open counts beside it carry the difference.
     * @param remediationClaimedOpen open findings a developer has marked fixed and no assessor has
     *     verified. Product principle 6: waiting is visible and attributed, and this is the queue
     *     that is waiting on the security team rather than on the delivery team.
     */
    public record Posture(long componentCount, long findingTotal, long findingOpen,
            long findingAccepted, long criticalTotal, long criticalOpen, long highTotal,
            long highOpen, long mediumTotal, long mediumOpen, long lowTotal, long lowOpen,
            long scaTotal, long scaOpen, String lastDetectedAt, long sbomCoveredParts,
            String sbomLatestAt, long sbomRejectedParts, long openOver30Days, long openOver90Days,
            long openOver180Days, long closedLast90Days, Integer meanDaysToClose,
            Integer openOldestDays, long remediationClaimedOpen, long requestTotal,
            long requestOpen, String lastRequestClosedAt) {
    }

    /**
     * One severity band.
     *
     * <p>{@code total} is the denominator that makes {@code open} measurable: zero open over zero
     * findings is unmeasured, and zero open over forty closed is a result somebody earned. Without
     * the denominator the two render identically.
     */
    public record Severity(String code, int ordinal, long total, long open, long openOver90Days) {
    }

    /**
     * One month of finding flow.
     *
     * <p>Two series, never a net figure. A net of zero is a team closing forty findings a month while
     * forty-one arrive, and it is indistinguishable from a team doing nothing.
     */
    public record MonthPoint(String label, long opened, long closed) {
    }

    /**
     * Open findings by how long they have been open, split by severity.
     *
     * <p>The chart the count of open findings cannot replace. Twenty open findings that all arrived
     * last week and twenty that have been open for two years are the same number and opposite
     * situations, and only one of them is a remediation problem.
     */
    public record AgeBand(String label, long critical, long high, long medium, long low,
            long unrated) {
    }

    /** One part of the application, and what is open against it. */
    public record Part(String assetId, String name, String typeCode, int depth, long open,
            long criticalOpen, long highOpen, long total, String lastDetectedAt) {
    }

    /** A named slice — a finding class, an assessment context, a tool, a closure reason. */
    public record Slice(String key, long total, long open) {
    }

    /**
     * How long remediation takes, per severity, over findings that were actually closed.
     *
     * <p>An average that counts still-open findings as though they closed today is the flattering
     * version and improves every time a new finding arrives. {@code oldestOpenDays} is the honest
     * companion — a good mean with a two-year-old critical underneath it is a problem the mean hides.
     *
     * @param medianDaysToClose reported beside the mean because one finding that took three years
     *     moves a mean and does not move a median, and the difference between them is itself the
     *     signal that a long tail exists.
     */
    public record Remediation(String code, int ordinal, long closedCount, Integer meanDaysToClose,
            Integer medianDaysToClose, Integer oldestOpenDays) {
    }

    /**
     * Whether any assurance activity of a given class has ever looked at this application.
     *
     * <p>Product principle 1, applied to the estate rather than to a figure. A class ABSENT from this
     * result is the answer: nothing of that kind has ever run here. {@code coveredParts} against
     * {@code componentCount} is what distinguishes "one of five services was scanned" from "the
     * application was scanned", which a boolean cannot.
     */
    public record Assurance(String findingClass, long coveredParts, long findingCount, long openCount,
            String lastEvidenceAt, List<String> tools) {
    }

    /**
     * Assessment demand and whether it was delivered by its due date.
     *
     * <p>The due date, not a service level policy. No {@code service_level_policy} row exists in this
     * deployment and no clock has ever run, so a compliance percentage would be a percentage over
     * zero measured obligations — the worst available answer, because it looks like a measurement.
     * The due date is coarser and is real today.
     *
     * <p>{@code noDueDate} and {@code openPastDue} are their own columns and are never folded into
     * met or missed. A request nobody gave a deadline is not a request that met its deadline.
     */
    public record RequestSla(long met, long missed, long openPastDue, long openWithinDue,
            long closedNoDueDate, long openNoDueDate) {
    }

    /** Distinct findings and requests across a set of applications. */
    public record EstateTotals(long findings, long requests) {
    }

    /**
     * How many parts of one type each of several applications contains.
     *
     * <p>One query for the whole list rather than one per row. {@code asset_composition} is a
     * recursive walk over the graph; a correlated subselect per application would re-run it per
     * application, which is invisible over eleven and is not over eleven thousand.
     *
     * <p><b>The type code is a bound parameter, never written into the SQL.</b> ADR-027 makes asset
     * types tenant data, so "which type is a project" is a decision that belongs in one Java constant
     * ({@link ProjectQuery#PROJECT_TYPE}) that a deployment without projects simply never matches —
     * not in a view definition, where it would become schema.
     */
    public java.util.Map<UUID, Long> partCounts(Principal principal, List<UUID> applicationIds,
            String typeCode) throws SQLException {
        java.util.Map<UUID, Long> out = new java.util.LinkedHashMap<>();
        if (applicationIds.isEmpty()) {
            return out;
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT c.root_id, count(*)
                          FROM asset_composition c
                         WHERE c.root_id = ANY (?) AND c.type_code = ?
                           AND c.lifecycle_state <> 'RETIRED'
                         GROUP BY c.root_id
                        """)) {
            statement.setArray(1, connection.createArrayOf("uuid", applicationIds.toArray()));
            statement.setString(2, typeCode);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.put(r.getObject(1, UUID.class), r.getLong(2));
                }
            }
        }
        return out;
    }

    private final DataSource dataSource;

    public ApplicationPostureQuery(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /**
     * The totals for a list of applications, counting each finding and each request once.
     *
     * <p><b>Not the sum of the per-application counts.</b> Two applications legitimately share a
     * service — that is what happens when one team's work depends on another's — so a finding
     * against that service rolls into both. Correct per application, and adding them reports more
     * open findings than the estate contains. A headline figure larger than the truth destroys trust
     * in a page exactly as fast as one that is too small, and the reader cannot tell which of the two
     * they are looking at. The same trap is recorded at {@link ProjectQuery}, where the answer was to
     * count projects instead.
     *
     * <p>An empty list returns zeros without a query. {@code = ANY} over an empty array is valid SQL
     * and returns zero, but the round trip is pointless and the empty case is worth being explicit
     * about.
     */
    public EstateTotals estateTotals(Principal principal, List<UUID> applicationIds)
            throws SQLException {
        if (applicationIds.isEmpty()) {
            return new EstateTotals(0, 0);
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT (SELECT count(DISTINCT af.finding_id) FROM application_finding af
                                 WHERE af.asset_id = ANY (?)),
                               (SELECT count(DISTINCT ar.request_id) FROM application_request ar
                                 WHERE ar.asset_id = ANY (?))
                        """)) {
            java.sql.Array ids = connection.createArrayOf("uuid", applicationIds.toArray());
            statement.setArray(1, ids);
            statement.setArray(2, ids);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? new EstateTotals(r.getLong(1), r.getLong(2))
                        : new EstateTotals(0, 0);
            }
        }
    }

    // ----------------------------------------------------------------------------------------------

    /** The headline rollup. Empty only where the asset does not exist. */
    public Optional<Posture> posture(Principal principal, UUID applicationId) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT component_count, finding_total, finding_open, finding_accepted,
                               critical_total, critical_open, high_total, high_open,
                               medium_total, medium_open, low_total, low_open,
                               sca_total, sca_open,
                               to_char(last_detected_at, 'YYYY-MM-DD'),
                               sbom_covered_parts, to_char(sbom_latest_at, 'YYYY-MM-DD'),
                               sbom_rejected_parts,
                               open_over_30_days, open_over_90_days, open_over_180_days,
                               closed_last_90_days, mean_days_to_close, open_oldest_days,
                               remediation_claimed_open, request_total, request_open,
                               to_char(last_request_closed_at, 'YYYY-MM-DD')
                          FROM application_posture WHERE asset_id = ?
                        """)) {
            statement.setObject(1, applicationId);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Posture(r.getLong(1), r.getLong(2), r.getLong(3),
                        r.getLong(4), r.getLong(5), r.getLong(6), r.getLong(7), r.getLong(8),
                        r.getLong(9), r.getLong(10), r.getLong(11), r.getLong(12), r.getLong(13),
                        r.getLong(14), r.getString(15), r.getLong(16), r.getString(17),
                        r.getLong(18), r.getLong(19), r.getLong(20), r.getLong(21), r.getLong(22),
                        nullableInt(r, 23), nullableInt(r, 24), r.getLong(25), r.getLong(26),
                        r.getLong(27), r.getString(28)));
            }
        }
    }

    /**
     * The severity mix.
     *
     * <p>A RIGHT JOIN onto {@code severity_level} so a band with no findings is a row reading zero
     * rather than a band missing from the chart. A missing band and a band at zero look the same on
     * a bar chart and mean different things — one of them is "this tenant does not use that
     * severity".
     *
     * <p>Severity is the effective one where a human set it, falling back to the reported one. Reading
     * {@code effective_severity_id} alone reports every untriaged finding as unrated, which empties
     * the chart for exactly the estate nobody has looked at.
     */
    public List<Severity> severities(Principal principal, UUID applicationId) throws SQLException {
        return read(principal, applicationId, """
                SELECT coalesce(s.code, 'UNRATED') AS code,
                       coalesce(s.ordinal, 9999)   AS ordinal,
                       count(f.id)                                             AS total,
                       count(f.id) FILTER (WHERE f.state = 'OPEN')             AS open,
                       count(f.id) FILTER (WHERE f.state = 'OPEN'
                           AND f.first_detected_at < now() - interval '90 days') AS aged
                  FROM severity_level s
                  FULL OUTER JOIN (
                        SELECT f.id, f.state, f.first_detected_at,
                               coalesce(f.effective_severity_id, f.reported_severity_id) AS sev
                          FROM application_finding af
                          JOIN finding f ON f.id = af.finding_id
                         WHERE af.asset_id = ?
                  ) f ON f.sev = s.id
                 GROUP BY 1, 2 ORDER BY 2, 1
                """, 1, r -> new Severity(r.getString(1), r.getInt(2), r.getLong(3), r.getLong(4),
                r.getLong(5)));
    }

    /**
     * Finding flow, one row per month, oldest first.
     *
     * <p>The month series is generated rather than derived from the rows, so a month in which nothing
     * happened is a month of zeros and not a missing point. A chart that omits quiet months
     * compresses its own time axis and makes a burst look like a trend.
     */
    public List<MonthPoint> trend(Principal principal, UUID applicationId, int months)
            throws SQLException {
        int span = Math.max(1, Math.min(36, months));
        return read(principal, applicationId, """
                WITH series AS (
                    SELECT generate_series(date_trunc('month', now())
                             - make_interval(months => %d),
                           date_trunc('month', now()), interval '1 month') AS month_start),
                     linked AS (
                    SELECT f.first_detected_at, f.closed_at
                      FROM application_finding af
                      JOIN finding f ON f.id = af.finding_id
                     WHERE af.asset_id = ?)
                SELECT to_char(m.month_start, 'YYYY-MM') AS label,
                       (SELECT count(*) FROM linked l
                         WHERE l.first_detected_at >= m.month_start
                           AND l.first_detected_at < m.month_start + interval '1 month') AS opened,
                       (SELECT count(*) FROM linked l
                         WHERE l.closed_at >= m.month_start
                           AND l.closed_at < m.month_start + interval '1 month') AS closed
                  FROM series m ORDER BY m.month_start
                """.formatted(span - 1), 1,
                r -> new MonthPoint(r.getString(1), r.getLong(2), r.getLong(3)));
    }

    /**
     * Open findings by age band and severity.
     *
     * <p>The bands are fixed and product-wide rather than tenant-configurable, and that is a decision
     * rather than an omission: they are a way of reading a distribution, not a service level. A
     * tenant's actual deadlines live in {@code service_level_policy}, and when a policy exists the
     * overdue figure comes from the clock rather than from a band that happens to be near it.
     */
    public List<AgeBand> ageBands(Principal principal, UUID applicationId) throws SQLException {
        return read(principal, applicationId, """
                WITH bands AS (
                    SELECT * FROM (VALUES (1, '0-30', 0, 30), (2, '31-90', 30, 90),
                                          (3, '91-180', 90, 180), (4, '181-365', 180, 365),
                                          (5, 'over 365', 365, 100000))
                      AS b(ordinal, label, from_days, to_days)),
                     open_findings AS (
                    SELECT EXTRACT(DAY FROM (now() - f.first_detected_at))::int AS age_days,
                           coalesce(s.code, 'UNRATED') AS code
                      FROM application_finding af
                      JOIN finding f ON f.id = af.finding_id
                      LEFT JOIN severity_level s
                             ON s.id = coalesce(f.effective_severity_id, f.reported_severity_id)
                     WHERE af.asset_id = ? AND f.state = 'OPEN')
                SELECT b.label,
                       count(o.code) FILTER (WHERE o.code = 'CRITICAL'),
                       count(o.code) FILTER (WHERE o.code = 'HIGH'),
                       count(o.code) FILTER (WHERE o.code = 'MEDIUM'),
                       count(o.code) FILTER (WHERE o.code = 'LOW'),
                       count(o.code) FILTER (WHERE o.code NOT IN
                            ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW'))
                  FROM bands b
                  LEFT JOIN open_findings o
                         ON o.age_days >= b.from_days AND o.age_days < b.to_days
                 GROUP BY b.ordinal, b.label ORDER BY b.ordinal
                """, 1, r -> new AgeBand(r.getString(1), r.getLong(2), r.getLong(3), r.getLong(4),
                r.getLong(5), r.getLong(6)));
    }

    /**
     * Where inside the application the open findings actually are.
     *
     * <p>Per part, over {@code asset_finding_link} rather than by summing the parts' own rollups: a
     * service shared by two features would otherwise appear under both and the column would add up
     * to more than the application holds.
     *
     * <p>Parts with nothing open are returned too, with zeros. A composition list that shows only the
     * parts with findings is a list of the parts somebody has looked at, and it reads as the whole
     * application.
     */
    public List<Part> parts(Principal principal, UUID applicationId) throws SQLException {
        return read(principal, applicationId, """
                SELECT c.asset_id::text, c.display_name, c.type_code, c.depth,
                       count(f.id) FILTER (WHERE f.state = 'OPEN'),
                       count(f.id) FILTER (WHERE f.state = 'OPEN' AND s.code = 'CRITICAL'),
                       count(f.id) FILTER (WHERE f.state = 'OPEN' AND s.code = 'HIGH'),
                       count(f.id),
                       to_char(max(f.last_detected_at), 'YYYY-MM-DD')
                  FROM asset_composition c
                  LEFT JOIN asset_finding_link l ON l.asset_id = c.asset_id
                  LEFT JOIN finding f ON f.id = l.finding_id
                  LEFT JOIN severity_level s
                         ON s.id = coalesce(f.effective_severity_id, f.reported_severity_id)
                 WHERE c.root_id = ?
                 GROUP BY c.asset_id, c.display_name, c.type_code, c.depth
                 ORDER BY 5 DESC, 8 DESC, c.display_name
                """, 1, r -> new Part(r.getString(1), r.getString(2), r.getString(3), r.getInt(4),
                r.getLong(5), r.getLong(6), r.getLong(7), r.getLong(8), r.getString(9)));
    }

    /**
     * What kind of weakness this application has, by finding class.
     *
     * <p>The classes are a product-fixed list (DOC-04): a tenant renames what it displays, it does not
     * invent a sixth kind of assurance activity, and a chart grouped on free text would split
     * "Dependency" and "DEPENDENCY" into two bars.
     */
    public List<Slice> classes(Principal principal, UUID applicationId) throws SQLException {
        return slice(principal, applicationId, "f.finding_class");
    }

    /**
     * How the weaknesses are being found.
     *
     * <p>Worth its own chart because the mix is the answer to a question the count cannot reach: an
     * application whose findings all come from automated scanning has not been penetration tested,
     * however many findings it has. And a finding whose context is an incident or a bug bounty was
     * found in production by somebody outside the process — the assurance did not catch it.
     */
    public List<Slice> contexts(Principal principal, UUID applicationId) throws SQLException {
        return slice(principal, applicationId, "f.assessment_context");
    }

    /**
     * How closed findings were closed.
     *
     * <p>A finding closed as verified-fixed and one closed as risk-accepted are both closed and are
     * not the same outcome. Accepted risk that is invisible on a posture page is accepted risk nobody
     * revisits.
     */
    public List<Slice> closures(Principal principal, UUID applicationId) throws SQLException {
        // Restricted to findings that are actually closed. Grouping the whole population on
        // closure_reason would put every open finding into an "UNSPECIFIED" bar — the largest one on
        // the chart, describing nothing, and reading as a data quality problem that does not exist.
        return read(principal, applicationId, """
                SELECT coalesce(f.closure_reason, 'UNSPECIFIED') AS key,
                       count(*)                                  AS total,
                       0                                         AS open
                  FROM application_finding af
                  JOIN finding f ON f.id = af.finding_id
                 WHERE af.asset_id = ? AND f.state <> 'OPEN'
                 GROUP BY 1 ORDER BY 2 DESC, 1
                """, 1, r -> new Slice(r.getString(1), r.getLong(2), r.getLong(3)));
    }

    /** Which tools produced the evidence. A class covered by one tool is not a class covered. */
    public List<Slice> tools(Principal principal, UUID applicationId) throws SQLException {
        return slice(principal, applicationId, "f.source_tool");
    }

    /** Time to remediate, per severity, over closed findings only. */
    public List<Remediation> remediation(Principal principal, UUID applicationId)
            throws SQLException {
        return read(principal, applicationId, """
                SELECT coalesce(s.code, 'UNRATED'), coalesce(s.ordinal, 9999),
                       count(*) FILTER (WHERE f.closed_at IS NOT NULL),
                       round(avg(EXTRACT(EPOCH FROM (f.closed_at - f.first_detected_at)) / 86400.0)
                             FILTER (WHERE f.closed_at IS NOT NULL))::int,
                       percentile_cont(0.5) WITHIN GROUP (
                           ORDER BY EXTRACT(EPOCH FROM (f.closed_at - f.first_detected_at))
                                    / 86400.0)::int,
                       max(EXTRACT(DAY FROM (now() - f.first_detected_at)))
                           FILTER (WHERE f.state = 'OPEN')::int
                  FROM application_finding af
                  JOIN finding f ON f.id = af.finding_id
                  LEFT JOIN severity_level s
                         ON s.id = coalesce(f.effective_severity_id, f.reported_severity_id)
                 WHERE af.asset_id = ?
                 GROUP BY 1, 2 ORDER BY 2
                """, 1, r -> new Remediation(r.getString(1), r.getInt(2), r.getLong(3),
                nullableInt(r, 4), nullableInt(r, 5), nullableInt(r, 6)));
    }

    /**
     * Assurance coverage: which classes of activity have ever produced evidence here, and when.
     *
     * <p>Reads {@code application_assurance}, which rolls the classes up over the whole composition.
     * A SAST run against one service is evidence for that service; whether it counts for the
     * application is a judgement, and the platform takes the position that it does — with
     * {@code coveredParts} beside it so a reader can see that one of five parts was covered. The
     * alternative, requiring every part before the class counts, reports NEVER for an application
     * somebody did scan.
     */
    public List<Assurance> assurance(Principal principal, UUID applicationId) throws SQLException {
        List<Assurance> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT a.finding_class, a.covered_parts, a.finding_count, a.open_count,
                               to_char(a.last_evidence_at, 'YYYY-MM-DD'),
                               (SELECT array_agg(DISTINCT f.source_tool)
                                  FROM application_finding af
                                  JOIN finding f ON f.id = af.finding_id
                                 WHERE af.asset_id = a.asset_id
                                   AND f.finding_class = a.finding_class)
                          FROM application_assurance a
                         WHERE a.asset_id = ?
                         ORDER BY a.finding_class
                        """)) {
            statement.setObject(1, applicationId);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    java.sql.Array tools = r.getArray(6);
                    rows.add(new Assurance(r.getString(1), r.getLong(2), r.getLong(3), r.getLong(4),
                            r.getString(5),
                            tools == null ? List.of() : List.of((String[]) tools.getArray())));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Assessment requests against this application, and whether they landed by their due date.
     *
     * <p>Terminality is read from {@code state_category} and never from the state code. DOC-09 lets a
     * tenant name its own states, so matching on a code here would go stale on the first workflow
     * change and would silently count closed work as open.
     */
    public RequestSla requestSla(Principal principal, UUID applicationId) throws SQLException {
        List<RequestSla> rows = read(principal, applicationId, """
                SELECT count(*) FILTER (WHERE b.state_category = 'TERMINAL'
                            AND b.due_at IS NOT NULL AND b.closed_at IS NOT NULL
                            AND b.closed_at <= b.due_at),
                       count(*) FILTER (WHERE b.state_category = 'TERMINAL'
                            AND b.due_at IS NOT NULL AND b.closed_at IS NOT NULL
                            AND b.closed_at > b.due_at),
                       count(*) FILTER (WHERE coalesce(b.state_category, '') <> 'TERMINAL'
                            AND b.due_at IS NOT NULL AND b.due_at < now()),
                       count(*) FILTER (WHERE coalesce(b.state_category, '') <> 'TERMINAL'
                            AND b.due_at IS NOT NULL AND b.due_at >= now()),
                       count(*) FILTER (WHERE b.state_category = 'TERMINAL' AND b.due_at IS NULL),
                       count(*) FILTER (WHERE coalesce(b.state_category, '') <> 'TERMINAL'
                            AND b.due_at IS NULL)
                  FROM application_request ar
                  JOIN request_board b ON b.id = ar.request_id
                 WHERE ar.asset_id = ?
                """, 1, r -> new RequestSla(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4),
                r.getLong(5), r.getLong(6)));
        return rows.isEmpty() ? new RequestSla(0, 0, 0, 0, 0, 0) : rows.get(0);
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * A one-column grouping over the application's findings.
     *
     * <p>{@code column} is interpolated and is supplied only by the methods above, never by a caller
     * and never from a request. It names a column, which is structure; the application identifier is
     * the value and is always bound.
     */
    private List<Slice> slice(Principal principal, UUID applicationId, String column)
            throws SQLException {
        return read(principal, applicationId, """
                SELECT coalesce(%s, 'UNSPECIFIED') AS key,
                       count(*)                                    AS total,
                       count(*) FILTER (WHERE f.state = 'OPEN')    AS open
                  FROM application_finding af
                  JOIN finding f ON f.id = af.finding_id
                 WHERE af.asset_id = ?
                 GROUP BY 1 ORDER BY 3 DESC, 2 DESC, 1
                """.formatted(column), 1,
                r -> new Slice(r.getString(1), r.getLong(2), r.getLong(3)));
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet results) throws SQLException;
    }

    private <T> List<T> read(Principal principal, UUID applicationId, String sql, int bindings,
            RowMapper<T> mapper) throws SQLException {
        List<T> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int binding = 1; binding <= bindings; binding++) {
                statement.setObject(binding, applicationId);
            }
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(mapper.map(results));
                }
            }
        }
        return List.copyOf(rows);
    }

    /** {@code getInt} returns 0 for SQL NULL, which is the value this page must never invent. */
    private static Integer nullableInt(ResultSet results, int column) throws SQLException {
        int value = results.getInt(column);
        return results.wasNull() ? null : value;
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
