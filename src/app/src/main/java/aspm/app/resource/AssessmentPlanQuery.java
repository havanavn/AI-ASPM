package aspm.app.resource;

import aspm.app.persistence.TenantConnections;
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
 * Planning the periodic assessment of applications. DOC-12, {@code PRD-ASM-*}, ADR-001.
 *
 * <h2>What a plan is here, and what it is not</h2>
 *
 * <p>The platform has no separate "planned assessment" record, and this deliberately does not invent
 * one. A plan drawn from a table nobody updates is a plan that diverges from the work within a month
 * and is then believed anyway. So every bar on the chart is a fact the system already holds:
 *
 * <ul>
 *   <li><b>Work that happened or is happening</b> — an assessment request, from when it started to
 *       when it closed, or to its due date while it is open.
 *   <li><b>Work that is owed</b> — the next full review due date, computed by
 *       {@code application_review_cadence} from the tenant's own interval policy.
 * </ul>
 *
 * <p>The second is the planning half and it is a projection, not a commitment: nobody has scheduled
 * it, and the chart says so by drawing it differently. A projection drawn like a booking is how a
 * plan comes to be read as capacity that was never allocated.
 *
 * <h2>Never assessed is a bar, not a gap</h2>
 *
 * <p>An application with no review at all has nothing to draw between two dates, and the honest
 * temptation is to leave its row empty. That is the worst possible rendering: an empty row reads as a
 * quiet period. It carries an explicit marker instead — product principle 1, applied to a timeline.
 */
public final class AssessmentPlanQuery {

    /** One application's planning row. */
    /**
     * One application on the plan.
     *
     * <p>The last four fields are what a planner sizes an engagement from, and they are here rather
     * than a click away because the decision they inform — how much of the next quarter this target
     * needs — is made while scanning the list. {@code exposureDeclared} is the asset's own column;
     * {@code apiCount} is summed over the application's projects, where the Excel inventory records
     * it; {@code projectCount} says how many parts the sum came from, because 300 endpoints across
     * one project and across nine are different engagements.
     *
     * <p>{@code apiCount} is null and not zero when nothing declared one. Product principle 1: an
     * application whose API count nobody recorded is not an application with no APIs, and a zero in a
     * column somebody sorts by to find the small jobs would put it first.
     */
    public record PlanRow(String assetId, String name, String orgPath, String criticality,
            long completed, long inFlight, long abandoned, String lastReviewAt, Integer intervalMonths,
            String nextDueAt, String status, long openRequests, long severeOpen,
            String exposureDeclared, Integer apiCount, long projectCount, long plannedWindows,
            /**
             * How the platform knows about the last review: {@code OBSERVED}, {@code ATTESTED}, or
             * null where there has been none. Never inferred from whether a count is zero — an
             * application can carry observed reviews AND a later attested one.
             */
            long attestedCount, String lastReviewSource) {
    }

    /** One bar: a request that happened, or the projection of one that is owed. */
    public record Bar(String assetId, String requestId, String code, String label, String kind,
            String startAt, String endAt, String state, boolean open, boolean overdue,
            boolean fullReview) {
    }

    /**
     * A project under an application: a real scope a request can name, and the granularity the sizing
     * facts are actually declared at.
     *
     * <p>{@code apiCount} and {@code accessPath} are tenant-declared fields on the PROJECT asset type
     * ({@code api_count}, {@code access_path}) — which is why breaking the plan down by project is not
     * cosmetic. At application level the API count is a sum and the access path is a set; here both
     * are single facts about one deployable thing, which is what an engagement is scoped to.
     *
     * <p>{@code accessPath} is passed through as the tenant's own value and never ranked. Whether
     * "PUBLIC" is more exposed than "ZTNA" is a judgement about a tenant-configured vocabulary, and
     * ADR-027 forbids the code holding an opinion about values it did not define.
     */
    public record ProjectRef(String assetId, String projectId, String name, Integer apiCount,
            String accessPath, String lifecycleState, long plannedWindows) {
    }

    /** One month of planned load, for the capacity chart. */
    public record LoadPoint(String label, long due, long started, long closed) {
    }

    /**
     * What the caller has narrowed the plan to. All three are multi-select.
     *
     * <p>{@code null} means "no filter on this dimension"; an EMPTY list means "nothing selected", so
     * nothing matches. The distinction is load-bearing: a picker whose last selection was removed must
     * show an empty plan, not silently widen back to everything — that is how a person reads a
     * filtered estate as the whole one.
     *
     * <p><b>Team is derived, not stored.</b> No request carries a team. A team here means "the lead
     * assessor belongs to this team", which is the only relationship the data supports, and it is a
     * many-to-many one — a lead in two teams makes their requests visible under both. Stated because a
     * reader who assumes a request has one owning team will misread the counts.
     */
    public record Filter(List<UUID> orgs, List<UUID> teams, List<UUID> assessors,
            boolean unassignedAssessor) {

        public static Filter none() {
            return new Filter(null, null, null, false);
        }

        boolean anyRequestFilter() {
            return teams != null || assessors != null || unassignedAssessor;
        }
    }

    private static String inScope(String column) {
        return column + " IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))";
    }

    /**
     * Accumulates the values a built-up WHERE clause needs, in the order it needs them.
     *
     * <p>The clauses here are assembled conditionally, so the parameter positions are not knowable
     * when the SQL text is written. Threading an index by hand through four optional predicates is how
     * a filter comes to apply the wrong list to the wrong column — silently, because both are UUID
     * arrays. The clause builder appends its value at the moment it appends its text, so the two
     * cannot drift apart.
     */
    private static final class Binder {
        private final List<Object> values = new ArrayList<>();

        void add(Object value) {
            values.add(value);
        }

        void bind(Connection connection, PreparedStatement statement, int from) throws SQLException {
            int index = from;
            for (Object value : values) {
                if (value instanceof List<?> list) {
                    statement.setArray(index++, connection.createArrayOf("uuid", list.toArray()));
                } else {
                    statement.setObject(index++, value);
                }
            }
        }
    }

    /** The organization predicate, or empty text where no organization filter is set. */
    private static String orgClause(String column, Filter filter, Binder binder) {
        List<UUID> orgs = filter.orgs();
        if (orgs == null) {
            return "";
        }
        if (orgs.isEmpty()) {
            // Nothing selected means nothing matches. NOT "everything": a picker whose last selection
            // was cleared must show an empty plan rather than quietly widening back to the whole
            // estate, which is how a reader comes to believe they are looking at everything.
            return " AND false";
        }
        binder.add(orgs);
        return " AND " + column + " IN (SELECT descendant_id FROM org_closure "
                + "WHERE ancestor_id = ANY (?))";
    }

    /**
     * The team and assessor predicate, as an EXISTS over the application's requests.
     *
     * <p>Applied to the APPLICATION rather than to a request, because these two filters answer "whose
     * work is this" and the honest answer at row level is "an application this person has work on".
     * An application with no matching request drops out — which is right for that question, and is why
     * this predicate is deliberately NOT applied to the cadence filter, where a row with no work is
     * the most important thing on the page.
     */
    private static String requestClause(String assetColumn, Filter filter, Binder binder) {
        if (!filter.anyRequestFilter()) {
            return "";
        }
        return " AND EXISTS (SELECT 1 FROM application_request fr"
                + " JOIN request_board fb ON fb.id = fr.request_id"
                + " WHERE fr.asset_id = " + assetColumn
                + " AND " + participantPredicate("fb", filter, binder) + ")";
    }

    /**
     * Whether one request matches the team and assessor selection.
     *
     * <p>Team is not a column on a request. It is derived through the lead assessor's team membership,
     * which is the only relationship the data supports — and it is many-to-many, so a lead who belongs
     * to two teams makes their requests visible under both. An assessor means the LEAD or a recorded
     * participant, because a review somebody ran but did not lead is still their work.
     */
    private static String participantPredicate(String board, Filter filter, Binder binder) {
        List<String> any = new ArrayList<>();
        if (filter.teams() != null) {
            if (filter.teams().isEmpty()) {
                any.add("false");
            } else {
                binder.add(filter.teams());
                any.add("EXISTS (SELECT 1 FROM assessor_team_member m"
                        + " WHERE m.team_id = ANY (?) AND m.removed_at IS NULL"
                        + " AND m.principal_id = " + board + ".lead_principal_id)");
            }
        }
        if (filter.assessors() != null) {
            if (filter.assessors().isEmpty()) {
                any.add("false");
            } else {
                binder.add(filter.assessors());
                binder.add(filter.assessors());
                any.add("(" + board + ".lead_principal_id = ANY (?)"
                        + " OR EXISTS (SELECT 1 FROM assessment_request_participant p"
                        + " WHERE p.request_id = " + board + ".id AND p.removed_at IS NULL"
                        + " AND p.principal_id = ANY (?)))");
            }
        }
        if (filter.unassignedAssessor()) {
            // Its own option rather than a gap in the assessor list. "Nobody is leading this" is a
            // planning fact somebody needs to find, and it is unreachable through a list of people.
            any.add(board + ".lead_principal_id IS NULL");
        }
        // OR, not AND. Selecting two teams means either team; adding "unassigned" beside a person
        // means that person's work OR the work nobody owns. An AND across dimensions would return
        // nothing and read as a bug.
        return any.isEmpty() ? "true" : "(" + String.join(" OR ", any) + ")";
    }

    private final DataSource dataSource;

    public AssessmentPlanQuery(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /**
     * One row per application, ordered by how urgently it is owed a review.
     *
     * <p>Overdue first, then never assessed, then by next due date. Sorting by name would be tidier
     * and would bury the row somebody opened the page to find.
     */
    public List<PlanRow> rows(Principal principal, Filter filter) throws SQLException {
        Binder binder = new Binder();
        String orgClause = orgClause("a.owning_node_id", filter, binder);
        String requestClause = requestClause("a.id", filter, binder);
        String sql = """
                SELECT a.id::text, a.display_name,
                       (SELECT string_agg(an.name, ' › ' ORDER BY cl.depth DESC)
                          FROM org_closure cl JOIN org_node an ON an.id = cl.ancestor_id
                         WHERE cl.descendant_id = a.owning_node_id AND cl.depth > 0),
                       ct.code,
                       coalesce(c.full_review_count, 0), coalesce(c.full_review_in_flight, 0),
                       coalesce(c.full_review_abandoned, 0),
                       to_char(c.last_full_review_at, 'YYYY-MM-DD'),
                       c.interval_months,
                       to_char(c.next_full_review_due, 'YYYY-MM-DD'),
                       coalesce(c.full_review_status, 'NEVER'),
                       (SELECT count(*) FROM application_request ar
                          JOIN request_board b ON b.id = ar.request_id
                         WHERE ar.asset_id = a.id
                           AND coalesce(b.state_category, '') <> 'TERMINAL'),
                       coalesce(p.critical_open, 0) + coalesce(p.high_open, 0),
                       a.exposure_declared,
                       -- Summed over the application's projects, where the inventory declares it.
                       -- The regex guard is not paranoia: `attributes` is jsonb and a declared field
                       -- can be retyped from TEXT to INTEGER after values exist, which leaves a cast
                       -- that fails the whole page rather than one cell.
                       sz.api_count, coalesce(sz.project_count, 0),
                       coalesce(pw.planned, 0),
                       coalesce(c.attested_review_count, 0), c.last_full_review_source
                  FROM asset a
                  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION'
                  LEFT JOIN application_review_cadence c ON c.asset_id = a.id
                  LEFT JOIN criticality_tier ct ON ct.id = a.criticality_tier_id
                  LEFT JOIN application_posture p ON p.asset_id = a.id
                  LEFT JOIN LATERAL (
                      SELECT sum(CASE WHEN pr.attributes ->> 'api_count' ~ '^[0-9]+$'
                                      THEN (pr.attributes ->> 'api_count')::int END) AS api_count,
                             count(*) AS project_count
                        FROM asset_composition cc
                        JOIN asset pr ON pr.id = cc.asset_id
                        JOIN asset_type pt ON pt.id = pr.type_id AND pt.code = 'PROJECT'
                       WHERE cc.root_id = a.id AND pr.lifecycle_state <> 'RETIRED'
                  ) sz ON true
                  -- The plan for this application AND for its projects: a window on a project is a
                  -- window on the application as far as "is this target planned" is concerned, and a
                  -- planner who scheduled the parts must not be told the whole is unplanned.
                  LEFT JOIN LATERAL (
                      SELECT count(*) AS planned
                        FROM assessment_plan_window w
                       WHERE w.state = 'PLANNED' AND""" + " " + PlanWindows.IN_PLAN_HORIZON + """
                         AND (w.target_asset_id = a.id
                              OR w.target_asset_id IN (SELECT cc.asset_id FROM asset_composition cc
                                                        WHERE cc.root_id = a.id))
                  ) pw ON true
                 WHERE a.lifecycle_state <> 'RETIRED' AND %s%s%s
                 -- Overdue first, then never assessed, then soonest due. Alphabetical would be
                 -- tidier and would bury the row somebody opened this page to find.
                 ORDER BY CASE coalesce(c.full_review_status, 'NEVER')
                            WHEN 'OVERDUE' THEN 0 WHEN 'NEVER' THEN 1
                            WHEN 'DUE_SOON' THEN 2 ELSE 3 END,
                          c.next_full_review_due NULLS FIRST, a.display_name
                """.formatted(inScope("a.owning_node_id"), orgClause, requestClause);
        return read(principal, sql, binder, r -> new PlanRow(r.getString(1), r.getString(2),
                r.getString(3), r.getString(4), r.getLong(5), r.getLong(6), r.getLong(7),
                r.getString(8), r.getObject(9) == null ? null : Integer.valueOf(r.getInt(9)),
                r.getString(10), r.getString(11), r.getLong(12), r.getLong(13),
                r.getString(14),
                r.getObject(15) == null ? null : Integer.valueOf(r.getInt(15)),
                r.getLong(16), r.getLong(17), r.getLong(18), r.getString(19)));
    }

    /**
     * The bars: assessment requests, and the projected next review.
     *
     * <p>An open request runs to its DUE date rather than to today, because the bar is what was
     * committed to and the point of the chart is to see a commitment about to be missed. Its start is
     * the recorded start where there is one, and the intake date where there is not — stated as a
     * distinct kind so nobody reads an intake date as the day work began.
     */
    public List<Bar> bars(Principal principal, Filter filter, int monthsBack, int monthsAhead)
            throws SQLException {
        Binder binder = new Binder();
        String orgClause = orgClause("a.owning_node_id", filter, binder);
        // On the bars this applies to the REQUEST itself, not through an EXISTS: the question here is
        // "draw the work matching this filter", so a non-matching request must not be drawn even on a
        // row that survived because of a different one.
        String who = filter.anyRequestFilter()
                ? " AND " + participantPredicate("b", filter, binder) : "";
        String sql = """
                SELECT ar.asset_id::text, b.id::text, b.request_code, b.title,
                       CASE WHEN b.trigger_is_full_review THEN 'FULL_REVIEW' ELSE 'REQUEST' END,
                       to_char(coalesce(b.submitted_at, b.created_at), 'YYYY-MM-DD'),
                       to_char(coalesce(b.closed_at, b.due_at,
                                        coalesce(b.submitted_at, b.created_at)
                                        + interval '14 days'), 'YYYY-MM-DD'),
                       b.state,
                       (coalesce(b.state_category, '') <> 'TERMINAL'),
                       (b.due_at < now() AND coalesce(b.state_category, '') <> 'TERMINAL'),
                       coalesce(b.trigger_is_full_review, false)
                  FROM application_request ar
                  JOIN request_board b ON b.id = ar.request_id
                  JOIN asset a ON a.id = ar.asset_id
                  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION'
                 WHERE coalesce(b.submitted_at, b.created_at)
                         > now() - make_interval(months => %d)
                   AND coalesce(b.submitted_at, b.created_at)
                         < now() + make_interval(months => %d)
                   AND %s%s%s
                 ORDER BY 6
                """.formatted(Math.max(1, monthsBack), Math.max(1, monthsAhead),
                        inScope("a.owning_node_id"), orgClause, who);
        List<Bar> bars = new ArrayList<>(read(principal, sql, binder,
                r -> new Bar(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6), r.getString(7), r.getString(8),
                        r.getBoolean(9), r.getBoolean(10), r.getBoolean(11))));

        // The projection. A separate kind, drawn differently, because nobody has scheduled it: it is
        // what the tenant's own interval policy implies, not a booking anybody has made. Rendering it
        // like a request would turn a policy into an allocation nobody agreed to.
        Binder projectedBinder = new Binder();
        String projectedOrg = orgClause("a.owning_node_id", filter, projectedBinder);
        // The projection belongs to nobody: no request exists, so no lead and no team. Under an
        // assessor or team filter it is therefore withheld rather than attributed to whoever was
        // selected — showing it would claim somebody owns work that has not been created.
        String projected = filter.anyRequestFilter() ? null : """
                SELECT a.id::text, NULL, NULL, 'Next full review due', 'PROJECTED',
                       to_char(c.next_full_review_due, 'YYYY-MM-DD'),
                       to_char(c.next_full_review_due
                               + make_interval(days => coalesce(c.warn_days_before, 30)),
                               'YYYY-MM-DD'),
                       coalesce(c.full_review_status, 'NEVER'), true,
                       (c.next_full_review_due < now()), true
                  FROM asset a
                  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION'
                  JOIN application_review_cadence c ON c.asset_id = a.id
                 WHERE a.lifecycle_state <> 'RETIRED'
                   AND c.next_full_review_due IS NOT NULL
                   AND %s%s
                """.formatted(inScope("a.owning_node_id"), projectedOrg);
        if (projected != null) {
            bars.addAll(read(principal, projected, projectedBinder,
                    r -> new Bar(r.getString(1), null, null, r.getString(4), r.getString(5),
                            r.getString(6), r.getString(7), r.getString(8), true, r.getBoolean(10),
                            true)));
        }
        return List.copyOf(bars);
    }

    /**
     * Assessment load per month: what falls due, what started, what closed.
     *
     * <p>Due against started is the planning question — a month with nine reviews falling due and two
     * ever started is a month the plan was not met, and neither figure alone says that.
     */
    public List<LoadPoint> load(Principal principal, Filter filter, int months) throws SQLException {
        Binder binder = new Binder();
        String orgClause = orgClause("a2.owning_node_id", filter, binder);
        // Repeated into each of the three counts below rather than applied to `visible`, because these
        // count REQUESTS and the filter selects requests. Narrowing the application set instead would
        // count every request of an application the assessor merely touched once.
        String who = filter.anyRequestFilter()
                ? " AND " + participantPredicate("b", filter, binder) : "";
        String sql = """
                WITH visible AS (
                    SELECT a2.id FROM asset a2
                      JOIN asset_type t2 ON t2.id = a2.type_id AND t2.code = 'APPLICATION'
                     WHERE a2.lifecycle_state <> 'RETIRED' AND %s%s),
                     series AS (
                    SELECT generate_series(date_trunc('month', now()) - make_interval(months => %d),
                           date_trunc('month', now()) + make_interval(months => 5),
                           interval '1 month') AS m)
                SELECT to_char(s.m, 'YYYY-MM'),
                       (SELECT count(*) FROM application_request ar
                          JOIN request_board b ON b.id = ar.request_id
                          JOIN visible v ON v.id = ar.asset_id
                         WHERE b.due_at >= s.m AND b.due_at < s.m + interval '1 month'%s),
                       (SELECT count(*) FROM application_request ar
                          JOIN request_board b ON b.id = ar.request_id
                          JOIN visible v ON v.id = ar.asset_id
                         WHERE coalesce(b.submitted_at, b.created_at) >= s.m
                           AND coalesce(b.submitted_at, b.created_at) < s.m + interval '1 month'%s),
                       (SELECT count(*) FROM application_request ar
                          JOIN request_board b ON b.id = ar.request_id
                          JOIN visible v ON v.id = ar.asset_id
                         WHERE b.closed_at >= s.m AND b.closed_at < s.m + interval '1 month'%s)
                  FROM series s ORDER BY s.m
                """.formatted(inScope("a2.owning_node_id"), orgClause, Math.max(1, months),
                        who, who, who);
        // The three `who` clauses reuse the same bound values, so each has to be appended again.
        if (filter.anyRequestFilter()) {
            Binder repeat = new Binder();
            orgClause("a2.owning_node_id", filter, repeat);
            participantPredicate("b", filter, repeat);
            participantPredicate("b", filter, repeat);
            participantPredicate("b", filter, repeat);
            return read(principal, sql, repeat, r -> new LoadPoint(r.getString(1), r.getLong(2),
                    r.getLong(3), r.getLong(4)));
        }
        return read(principal, sql, binder, r -> new LoadPoint(r.getString(1), r.getLong(2),
                r.getLong(3), r.getLong(4)));
    }

    /**
     * The projects under each application in scope.
     *
     * <p>Carried so the plan can offer to raise a review against a REAL scope. A request is scoped to
     * a project, not to an application, and a "schedule this" button that guessed which project would
     * be asserting scope on the client's behalf — the thing product principle 4 exists to forbid. So
     * the plan sends the choices and the person makes it; where there is exactly one, the form is
     * pre-filled with it, which is a convenience rather than a decision.
     */
    public List<ProjectRef> projects(Principal principal, Filter filter) throws SQLException {
        Binder binder = new Binder();
        String orgClause = orgClause("app.owning_node_id", filter, binder);
        String sql = """
                SELECT app.id::text, pr.id::text, pr.display_name,
                       CASE WHEN pr.attributes ->> 'api_count' ~ '^[0-9]+$'
                            THEN (pr.attributes ->> 'api_count')::int END,
                       pr.attributes ->> 'access_path',
                       pr.lifecycle_state,
                       (SELECT count(*) FROM assessment_plan_window w
                         WHERE w.target_asset_id = pr.id AND w.state = 'PLANNED'
                           AND""" + " " + PlanWindows.IN_PLAN_HORIZON + """
                       )
                  FROM asset app
                  JOIN asset_type at ON at.id = app.type_id AND at.code = 'APPLICATION'
                  JOIN asset_composition cc ON cc.root_id = app.id
                  JOIN asset pr ON pr.id = cc.asset_id
                  JOIN asset_type pt ON pt.id = pr.type_id AND pt.code = 'PROJECT'
                 WHERE app.lifecycle_state <> 'RETIRED' AND pr.lifecycle_state <> 'RETIRED'
                   AND %s%s
                 ORDER BY app.display_name, pr.display_name
                """.formatted(inScope("app.owning_node_id"), orgClause);
        return read(principal, sql, binder,
                r -> new ProjectRef(r.getString(1), r.getString(2), r.getString(3),
                        r.getObject(4) == null ? null : Integer.valueOf(r.getInt(4)),
                        r.getString(5), r.getString(6), r.getLong(7)));
    }

    /**
     * One application's contribution to the coverage figures, for one year.
     *
     * <p>Deliberately per-application and not pre-aggregated. The grouping — by organization, by
     * criticality tier, by how many times it was reviewed — happens in Java, where it can be read and
     * tested, and where the same rows produce every table on the screen. Five aggregate queries over
     * the same population is five chances for one of them to apply a different scope predicate and
     * report a total that does not match the rows beneath it.
     *
     * <p>{@code tierCode} and {@code tierOrdinal} come from {@code criticality_tier}, which is tenant
     * data (ADR-027). There is no CRITICAL/HIGH/MEDIUM here and there must not be: the tiers are
     * whatever the tenant named them, and their order is the tenant's {@code ordinal}. A null tier is
     * its own group, because an application nobody classified is not a low-criticality application.
     *
     * <p>{@code reviewsObserved} and {@code reviewsAttested} are separate for the reason
     * {@code PRD-ASM-020} gives: a frequency table IS a coverage figure derived from review counts, so
     * it has to be able to say how much of it rests on assertion.
     */
    public record CoverageRow(String assetId, String orgId, String orgName,
            String tierCode, String tierLabel, Integer tierOrdinal,
            long reviewsObserved, long reviewsAttested,
            long planned, long cancelled, long converted, long done, long missed) {

        /** Reviews of either kind. What "assessed N times this year" means to a reader. */
        public long reviews() {
            return reviewsObserved + reviewsAttested;
        }
    }

    /**
     * The tenant's active criticality tiers, in the tenant's own order.
     *
     * <p>Read from the catalogue and NOT derived from the applications observed, which is how it was
     * first written and was wrong: a tier nobody has classified anything into disappeared from the
     * table entirely, so a reader could not tell "no application is Tier 3" from "there is no Tier 3".
     * The second is a configuration fact and the first is a finding about the estate. A column of
     * zeroes states the finding; a missing column hides it, and changes the shape of the table as the
     * data moves.
     */
    public List<PlanStatistics.Tier> tiers(Principal principal) throws SQLException {
        List<PlanStatistics.Tier> tiers = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT code, label_i18n ->> 'en', ordinal
                          FROM criticality_tier
                         WHERE lifecycle_state = 'ACTIVE'
                         ORDER BY ordinal, code
                        """);
                ResultSet r = statement.executeQuery()) {
            while (r.next()) {
                tiers.add(new PlanStatistics.Tier(r.getString(1),
                        r.getString(2) == null ? r.getString(1) : r.getString(2), r.getInt(3)));
            }
        }
        return tiers;
    }

    /**
     * Coverage and plan progress, one row per application, for a single calendar year.
     *
     * <p>The year is a calendar year and not a rolling twelve months. A planning cycle is annual and
     * budgeted annually, so "twice this year" is the question people actually ask; a rolling window
     * would make the same application move between buckets on a day nobody changed anything.
     *
     * <p><b>Parameter order is explicit here rather than going through {@code read}.</b> That helper
     * binds the scope array at position one, and this query's first placeholders are dates inside
     * lateral joins that appear earlier in the text. Reusing it would have bound a date where an array
     * was expected — which is the failure the {@code Binder} exists to prevent when clauses are
     * assembled conditionally, and this query assembles none.
     */
    public List<CoverageRow> coverage(Principal principal, Filter filter, int year)
            throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        java.time.LocalDate from = java.time.LocalDate.of(year, 1, 1);
        java.time.LocalDate until = from.plusYears(1);
        Binder binder = new Binder();
        String orgClause = orgClause("a.owning_node_id", filter, binder);
        String sql = """
                SELECT a.id::text, root.id::text, root.name,
                       ct.code, ct.label_i18n ->> 'en', ct.ordinal,
                       coalesce(obs.n, 0), coalesce(att.n, 0),
                       coalesce(pw.planned, 0), coalesce(pw.cancelled, 0),
                       coalesce(pw.converted, 0), coalesce(pw.done, 0), coalesce(pw.missed, 0)
                  FROM asset a
                  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION'
                  LEFT JOIN org_node n ON n.id = a.owning_node_id
                  -- The organization at the top of this application's branch.
                  --
                  -- A LATERAL and not two joins. Joining org_closure directly yields one row per
                  -- ANCESTOR — three for an application three levels down — and putting
                  -- `parent_id IS NULL` on the org_node join does not remove them, because a LEFT
                  -- JOIN keeps the unmatched closure rows with a null root beside them. Measured:
                  -- eleven applications came back as twenty-eight rows, every visible one with an
                  -- empty organization, and every per-organization total would have been inflated by
                  -- the depth of the tree.
                  LEFT JOIN LATERAL (
                      SELECT rn.id, rn.name
                        FROM org_closure cl
                        JOIN org_node rn ON rn.id = cl.ancestor_id AND rn.parent_id IS NULL
                       WHERE cl.descendant_id = a.owning_node_id
                       LIMIT 1
                  ) root ON true
                  -- The tier the application actually carries, inherited from its node where it has
                  -- none of its own — the same resolution application_review_cadence uses, so the
                  -- two cannot disagree about which tier an application is in.
                  LEFT JOIN criticality_tier ct
                         ON ct.id = coalesce(a.criticality_tier_id, n.criticality_tier_id)
                  LEFT JOIN LATERAL (
                      SELECT count(*) AS n FROM application_full_review fr
                       WHERE fr.asset_id = a.id AND fr.closed_at IS NOT NULL
                         AND fr.closed_at >= ? AND fr.closed_at < ?
                  ) obs ON true
                  LEFT JOIN LATERAL (
                      SELECT count(*) AS n FROM application_review_attestation at
                       WHERE at.asset_id = a.id AND at.withdrawn_at IS NULL
                         AND at.performed_to >= ? AND at.performed_to < ?
                  ) att ON true
                  -- The plan for this application AND for its projects: a window on a project is part
                  -- of the application's plan, which is how the Planned column on the table already
                  -- counts it. Two definitions of "planned" on one screen is the defect this avoids.
                  LEFT JOIN LATERAL (
                      SELECT count(*) FILTER (WHERE w.state <> 'CANCELLED')        AS planned,
                             count(*) FILTER (WHERE w.state = 'CANCELLED')         AS cancelled,
                             count(*) FILTER (WHERE w.state = 'CONVERTED')         AS converted,
                             count(*) FILTER (WHERE w.state = 'CONVERTED'
                                              AND rcs.disposition = 'COMPLETED')   AS done,
                             -- The planning finding: the fortnight passed and nothing was raised.
                             count(*) FILTER (WHERE w.state = 'PLANNED'
                                              AND w.ends_on < current_date)        AS missed
                        FROM assessment_plan_window w
                        LEFT JOIN assessment_request r ON r.id = w.request_id
                        LEFT JOIN review_completion_state rcs
                               ON rcs.state_code = r.state AND rcs.tenant_id = r.tenant_id
                       WHERE (w.target_asset_id = a.id
                              OR w.target_asset_id IN (SELECT cc.asset_id FROM asset_composition cc
                                                        WHERE cc.root_id = a.id))
                         AND w.starts_on >= ? AND w.starts_on < ?
                  ) pw ON true
                 WHERE a.lifecycle_state <> 'RETIRED' AND %s%s
                 ORDER BY root.name, a.display_name
                """.formatted(inScope("a.owning_node_id"), orgClause);

        List<CoverageRow> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, from);
            statement.setObject(2, until);
            statement.setObject(3, from);
            statement.setObject(4, until);
            statement.setObject(5, from);
            statement.setObject(6, until);
            statement.setArray(7, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            binder.bind(connection, statement, 8);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new CoverageRow(r.getString(1), r.getString(2), r.getString(3),
                            r.getString(4), r.getString(5),
                            r.getObject(6) == null ? null : Integer.valueOf(r.getInt(6)),
                            r.getLong(7), r.getLong(8), r.getLong(9), r.getLong(10),
                            r.getLong(11), r.getLong(12), r.getLong(13)));
                }
            }
        }
        return rows;
    }

    /** One selectable filter option, with how much work sits behind it. */
    public record Option(String id, String name, long requests) {
    }

    /**
     * The teams and assessors worth offering as filter options.
     *
     * <h2>Derived from the visible requests, and from the ORGANIZATION filter only</h2>
     *
     * <p>The options are the people and teams who actually appear in this scope, so a picker never
     * offers a name that would return nothing. But they deliberately ignore the team and assessor
     * selections themselves: deriving the options from the fully filtered set would make choosing one
     * assessor remove every other assessor from the list, which is a picker that cannot be changed
     * once used.
     *
     * @param teams true for teams, false for individual assessors
     */
    public List<Option> options(Principal principal, Filter filter, boolean teams)
            throws SQLException {
        // Only the organization narrows the options — see the note above.
        Filter orgOnly = new Filter(filter.orgs(), null, null, false);
        Binder binder = new Binder();
        String orgClause = orgClause("a.owning_node_id", orgOnly, binder);
        String sql = teams ? """
                SELECT tm.id::text, tm.name, count(DISTINCT b.id)
                  FROM application_request ar
                  JOIN request_board b ON b.id = ar.request_id
                  JOIN asset a ON a.id = ar.asset_id
                  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION'
                  JOIN assessor_team_member m ON m.principal_id = b.lead_principal_id
                       AND m.removed_at IS NULL
                  JOIN assessor_team tm ON tm.id = m.team_id
                       AND tm.lifecycle_state <> 'RETIRED'
                 WHERE a.lifecycle_state <> 'RETIRED' AND %s%s
                 GROUP BY tm.id, tm.name ORDER BY tm.name
                """.formatted(inScope("a.owning_node_id"), orgClause) : """
                SELECT p.id::text, coalesce(p.display_name, p.username), count(DISTINCT b.id)
                  FROM application_request ar
                  JOIN request_board b ON b.id = ar.request_id
                  JOIN asset a ON a.id = ar.asset_id
                  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION'
                  JOIN principal p ON p.id = b.lead_principal_id
                 WHERE a.lifecycle_state <> 'RETIRED' AND %s%s
                 GROUP BY p.id, coalesce(p.display_name, p.username)
                 ORDER BY coalesce(p.display_name, p.username)
                """.formatted(inScope("a.owning_node_id"), orgClause);
        return read(principal, sql, binder,
                r -> new Option(r.getString(1), r.getString(2), r.getLong(3)));
    }

    /** How many visible requests have no lead at all, so "Unassigned" can carry a count. */
    public long unassignedRequests(Principal principal, Filter filter) throws SQLException {
        Filter orgOnly = new Filter(filter.orgs(), null, null, false);
        Binder binder = new Binder();
        String orgClause = orgClause("a.owning_node_id", orgOnly, binder);
        String sql = """
                SELECT count(DISTINCT b.id)
                  FROM application_request ar
                  JOIN request_board b ON b.id = ar.request_id
                  JOIN asset a ON a.id = ar.asset_id
                  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION'
                 WHERE a.lifecycle_state <> 'RETIRED' AND b.lead_principal_id IS NULL AND %s%s
                """.formatted(inScope("a.owning_node_id"), orgClause);
        List<Long> one = read(principal, sql, binder, r -> Long.valueOf(r.getLong(1)));
        return one.isEmpty() ? 0 : one.get(0);
    }

    /**
     * The tenant's own trigger that counts as a full review, if it has exactly one.
     *
     * <p>Read from {@code counts_as_full_review} rather than from a code, because the trigger list is
     * tenant data (ADR-027) — a tenant that calls its periodic review something else must still get a
     * working button. Returns null where the tenant has none or several, in which case the form asks
     * rather than choosing on their behalf.
     */
    public String fullReviewTriggerId(Principal principal) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id::text FROM assessment_trigger WHERE counts_as_full_review")) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    ids.add(r.getString(1));
                }
            }
        }
        return ids.size() == 1 ? ids.get(0) : null;
    }

    // ----------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet results) throws SQLException;
    }

    private <T> List<T> read(Principal principal, String sql, Binder binder, RowMapper<T> mapper)
            throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        List<T> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            binder.bind(connection, statement, 2);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(mapper.map(results));
                }
            }
        }
        return List.copyOf(rows);
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
