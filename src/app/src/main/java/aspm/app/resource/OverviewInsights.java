package aspm.app.resource;

import aspm.app.persistence.TenantConnections;
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
 * What the estate is telling you, as ranked observations rather than as figures to interpret.
 *
 * <h2>Why observations and not more charts</h2>
 *
 * <p>A dashboard of counts asks its reader to do the analysis. "248 open findings" is a fact nobody
 * can act on without knowing how many are serious, how many are reachable from the internet, how
 * many have been open a year, and whether the number is rising. Every one of those is available, so
 * the platform should compose them rather than making a person do it from four separate panels.
 *
 * <h2>The rules are deterministic, and that is the point — including for what comes next</h2>
 *
 * <p>This class is the seam the planned analysis agent plugs into. Product principle 2 divides the
 * world: <b>scores, SLAs, dedup, authorization and state transitions are deterministic and
 * reproducible; AI interprets and drafts.</b> So the shape below is designed for both:
 *
 * <ul>
 *   <li>Every observation carries the <b>numbers it was derived from</b> and the <b>rule that
 *       produced it</b>. A reader can always ask "why does it say that" and get an answer that is
 *       not "the model said so".
 *   <li>{@code basis} names the rule. When an agent generates an observation instead, that field
 *       names the model and the prompt version, and the contract is otherwise unchanged.
 *   <li><b>No observation invents a number.</b> ADR-038 binds narrative to record fields and forbids
 *       AI generating a numeric value; the same discipline applies to a rule, because a figure whose
 *       provenance nobody can trace is not improved by a human having written the rule.
 *   <li>Nothing here writes. ADR-005 keeps AI output in a suggestion ledger promoted by an audited
 *       human action, and these observations are read-only for the same reason: a dashboard that
 *       silently reassigns work is a dashboard nobody can audit.
 * </ul>
 *
 * <h2>No risk score is invented</h2>
 *
 * <p>DOC-28 owns the risk model and it is not implemented. The temptation is to weight severity by
 * criticality and exposure and call the result a score — a second scoring model, unversioned,
 * disagreeing with DOC-28's the moment it ships. Instead these rules <b>compose facts</b>: "at the
 * top two severities, on an internet-facing tier-one asset, open more than ninety days" is a filter
 * whose every term is a recorded value. It ranks the estate without pretending to measure it.
 */
public final class OverviewInsights {

    /** How loudly an observation asks to be dealt with. */
    public enum Level {
        /** A specific, present exposure with a named population. */
        ACT_NOW(0),
        /** A trend or a gap that will become the first kind if nothing changes. */
        WATCH(1),
        /** True, good, and worth saying — a dashboard of only bad news stops being read. */
        HEALTHY(3),
        /** The platform cannot answer this, and says which measurement is missing (PP-1). */
        UNMEASURED(2);

        /**
         * Display rank, declared rather than taken from the declaration order.
         *
         * <p>Ordering by {@code ordinal()} would silently re-rank the whole page the first time
         * somebody inserted a level in the middle of the enum — and note that the ranks below are
         * NOT the declaration order: unmeasured outranks healthy, because "we cannot tell" needs
         * more attention than "this is fine".
         */
        private final int rank;

        Level(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    /** One number behind an observation, so the sentence can be checked. */
    public record Evidence(String label, long value) {
    }

    /**
     * One thing worth knowing.
     *
     * @param code stable identifier, so an interface can order or suppress without matching prose
     * @param basis what produced it. A rule name today; a model and prompt version when the agent
     *     does. Never absent — an observation nobody can trace back is one nobody should act on
     */
    public record Observation(String code, Level level, String headline, String detail,
            List<Evidence> evidence, String href, String basis) {
    }

    /**
     * Singular or plural, chosen from the count.
     *
     * <p>"1 serious findings" is the sort of thing that costs a demo its credibility in the first
     * five seconds: it reads as a template nobody checked, and a reader who notices it starts
     * wondering what else was not checked. NFR-INT-003 wants ICU message format for this once the
     * strings are externalised; until then the rule is here rather than absent.
     */
    private static String plural(long count, String singular, String pluralForm) {
        return count + " " + (count == 1 ? singular : pluralForm);
    }

    private static final String RULES = "deterministic-rules/v1";

    private final DataSource dataSource;

    public OverviewInsights(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /**
     * Every observation the rules produce, most urgent first.
     *
     * <p>Ordered by level then by the size of the population behind them, so the ranking is a
     * property of the data rather than of the order the rules happen to be written in.
     */
    public List<Observation> observations(Principal principal) throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        Map<String, Long> f = facts(principal);
        List<Observation> out = new ArrayList<>();

        // 1. The sentence that matters most: serious, reachable, and nobody has fixed it.
        long exposed = f.getOrDefault("exposed_serious_open", 0L);
        long exposedOld = f.getOrDefault("exposed_serious_old", 0L);
        if (exposed > 0) {
            out.add(new Observation("EXPOSED_SERIOUS", Level.ACT_NOW,
                    plural(exposed, "serious finding sits", "serious findings sit")
                            + " on internet-facing, business-critical systems",
                    exposedOld > 0
                            ? exposedOld + " of them have been open for more than ninety days. Every "
                                    + "term here is a recorded value — the severity your team "
                                    + "assigned, the exposure declared on the asset, its criticality "
                                    + "tier, and the date it was first detected."
                            : "None has been open more than ninety days, which is the one "
                                    + "encouraging thing about this number.",
                    List.of(new Evidence("Open at the top two severities", exposed),
                            new Evidence("Of those, open over 90 days", exposedOld),
                            new Evidence("All open findings", f.getOrDefault("open_total", 0L))),
                    "/board", RULES));
        }

        // 2. Direction. A count is a photograph; this is the film.
        long now = f.getOrDefault("open_total", 0L);
        long before = f.getOrDefault("open_90_days_ago", 0L);
        if (now > 0 || before > 0) {
            long delta = now - before;
            out.add(new Observation("BACKLOG_DIRECTION",
                    delta > 0 ? Level.WATCH : Level.HEALTHY,
                    delta > 0
                            ? "The open backlog grew by " + delta + " over the last ninety days"
                            : delta < 0
                                    ? "The open backlog fell by " + Math.abs(delta)
                                            + " over the last ninety days"
                                    : "The open backlog is unchanged over the last ninety days",
                    delta > 0
                            ? "Work is arriving faster than it is being closed. Activity counts "
                                    + "cannot show this: a team closing more than ever can still be "
                                    + "falling behind, and only the backlog distinguishes the two."
                            : "Findings are being closed at least as fast as they arrive.",
                    List.of(new Evidence("Open now", now),
                            new Evidence("Open ninety days ago", before)),
                    "/workload", RULES));
        }

        // 3. What has never been looked at. PP-1: unmeasured is not clean.
        long never = f.getOrDefault("apps_never_assessed", 0L);
        long apps = f.getOrDefault("apps_total", 0L);
        if (apps == 0) {
            out.add(new Observation("UNMEASURED_ESTATE", Level.UNMEASURED,
                    "No application is registered in your scope",
                    "Coverage cannot be computed over an empty inventory, and an empty inventory is "
                            + "not an estate with nothing in it.",
                    List.of(), "/applications", RULES));
        } else if (never > 0) {
            out.add(new Observation("UNMEASURED_ESTATE", Level.ACT_NOW,
                    never + " of " + apps + (never == 1 ? " applications has" : " applications have")
                            + " never been assessed",
                    "Nothing is known about these. They contribute no findings, so every count on "
                            + "this page is quieter than the estate actually is — absence of "
                            + "evidence is not evidence of absence.",
                    List.of(new Evidence("Never assessed", never),
                            new Evidence("Applications in scope", apps)),
                    "/applications", RULES));
        } else {
            out.add(new Observation("UNMEASURED_ESTATE", Level.HEALTHY,
                    "Every application in scope has been assessed at least once",
                    "Coverage is a floor rather than a guarantee — assessed once is not assessed "
                            + "recently.",
                    List.of(new Evidence("Applications in scope", apps)), "/applications", RULES));
        }

        // 4. The queue the delivery side experiences from us.
        long stale = f.getOrDefault("retest_overdue", 0L);
        if (stale > 0) {
            out.add(new Observation("VERIFICATION_DEBT", Level.ACT_NOW,
                    plural(stale, "claimed fix has", "claimed fixes have")
                            + " waited more than two weeks for a retest",
                    "Somebody did the work and is waiting on us to confirm it. Until it is retested "
                            + "the finding stays open and counts against them, which is the fastest "
                            + "way to lose a delivery team's goodwill.",
                    List.of(new Evidence("Claimed, awaiting retest over 14 days", stale),
                            new Evidence("Claimed, awaiting retest in total",
                                    f.getOrDefault("retest_total", 0L))),
                    "/workload", RULES));
        }

        // 5. Work with nobody's name on it.
        long unowned = f.getOrDefault("unowned_open", 0L);
        if (unowned > 0) {
            out.add(new Observation("UNOWNED_WORK", Level.WATCH,
                    plural(unowned, "open finding has", "open findings have")
                            + " nobody assigned",
                    "An unassigned finding is one nobody has agreed to fix, and it will not appear "
                            + "in anybody's queue until somebody notices it here.",
                    List.of(new Evidence("Open with no assignee", unowned)), "/board", RULES));
        }

        // 6. What got past the programme entirely.
        long escaped = f.getOrDefault("escaped_90", 0L);
        if (escaped > 0) {
            out.add(new Observation("ESCAPED_TO_PRODUCTION", Level.ACT_NOW,
                    plural(escaped, "serious finding reached", "serious findings reached")
                            + " production before anyone caught "
                            + (escaped == 1 ? "it" : "them"),
                    "Found in the last ninety days through a bug bounty submission or an incident — "
                            + "channels that only exist after release. Each one is a case the "
                            + "testing programme did not cover, which is more useful to know than "
                            + "any count of what it did.",
                    List.of(new Evidence("Escaped in the last 90 days", escaped)), "/workload",
                    RULES));
        }

        // 7. Dependency blindness. Different from having no vulnerable dependencies.
        long noSbom = f.getOrDefault("assets_no_sbom", 0L);
        long assets = f.getOrDefault("assets_total", 0L);
        if (assets > 0 && noSbom > 0) {
            out.add(new Observation("DEPENDENCY_BLIND", Level.WATCH,
                    noSbom + " of " + assets
                            + (noSbom == 1 ? " assets has" : " assets have")
                            + " never submitted a bill of materials",
                    "Their dependencies are unknown, so no component vulnerability can be matched "
                            + "against them. They will never appear in a dependency finding, which "
                            + "reads as clean.",
                    List.of(new Evidence("No SBOM ever submitted", noSbom),
                            new Evidence("Assets in scope", assets)),
                    "/composition", RULES));
        }

        out.sort((a, b) -> {
            int byLevel = Integer.compare(a.level().rank(), b.level().rank());
            if (byLevel != 0) {
                return byLevel;
            }
            long left = a.evidence().isEmpty() ? 0 : a.evidence().get(0).value();
            long right = b.evidence().isEmpty() ? 0 : b.evidence().get(0).value();
            return Long.compare(right, left);
        });
        return List.copyOf(out);
    }

    /**
     * One operating company's posture.
     *
     * @param exposedSerious open at the top two severities on an internet-facing, tier-one asset —
     *     the composed fact, not a score
     * @param openNow open findings; {@code openBefore} is the same count ninety days ago, so the
     *     row carries direction rather than a photograph
     * @param lastAssessedAt when anything in this organization was last assessed. Null is the
     *     answer that matters most and the one a count of assessments cannot give
     */
    public record Posture(String nodeId, String name, long applications, long neverAssessed,
            long openNow, long openBefore, long serious, long exposedSerious,
            String lastAssessedAt) {

        /** Whether this organization has anything the platform could measure at all. */
        public boolean measured() {
            return applications > 0;
        }
    }

    /**
     * Posture per organization, for the people who run one.
     *
     * <h2>Why the overview is organized this way</h2>
     *
     * <p>A group-level total answers a question nobody owns. The person opening this page runs GSM,
     * or Fintech, or the whole group — and in every case the first question is "how is <b>mine</b>
     * doing, and is it worse than the others". A single aggregate row cannot answer either half.
     *
     * <p>Rows are the caller's <b>scope roots</b>, so this is automatically the right page for both
     * readers: an executive with a tenant-wide grant sees every operating company side by side, and
     * a manager scoped to one sees one row. Neither needs a different page or a filter they have to
     * remember to set.
     *
     * <p>Each row aggregates the whole subtree beneath that root through {@code org_closure}, so a
     * finding three levels down still counts against the company that owns it — which is the only
     * reading an accountable manager would accept.
     */
    public List<Posture> posture(Principal principal) throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        List<Posture> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "WITH roots AS (SELECT id, name FROM org_node WHERE id = ANY (?)), "
                                + "     sub AS (SELECT r.id AS root_id, r.name, c.descendant_id "
                                + "               FROM roots r "
                                + "               JOIN org_closure c ON c.ancestor_id = r.id) "
                                + "SELECT s.root_id::text, s.name, "
                                + "  (SELECT count(*) FROM asset a JOIN asset_type ty ON ty.id = a.type_id "
                                + "    WHERE ty.code = 'APPLICATION' AND a.lifecycle_state <> 'RETIRED' "
                                + "      AND a.owning_node_id IN (SELECT descendant_id FROM sub "
                                + "                                WHERE root_id = s.root_id)), "
                                + "  (SELECT count(*) FROM asset a JOIN asset_type ty ON ty.id = a.type_id "
                                + "    WHERE ty.code = 'APPLICATION' AND a.lifecycle_state <> 'RETIRED' "
                                + "      AND a.owning_node_id IN (SELECT descendant_id FROM sub "
                                + "                                WHERE root_id = s.root_id) "
                                + "      AND NOT EXISTS (SELECT 1 FROM assessment_request_scope_asset x "
                                + "                       WHERE x.asset_id = a.id)), "
                                + "  (SELECT count(*) FROM finding f WHERE f.state = 'OPEN' "
                                + "      AND f.scope_node_id IN (SELECT descendant_id FROM sub "
                                + "                               WHERE root_id = s.root_id)), "
                                // Ninety days ago, from the two dates each finding carries.
                                + "  (SELECT count(*) FROM finding f "
                                + "    WHERE f.first_detected_at < now() - interval '90 days' "
                                + "      AND (f.closed_at IS NULL "
                                + "           OR f.closed_at >= now() - interval '90 days') "
                                + "      AND f.scope_node_id IN (SELECT descendant_id FROM sub "
                                + "                               WHERE root_id = s.root_id)), "
                                + "  (SELECT count(*) FROM finding f "
                                + "     LEFT JOIN severity_level sl ON sl.id = "
                                + "          coalesce(f.effective_severity_id, f.reported_severity_id) "
                                + "    WHERE f.state = 'OPEN' AND coalesce(sl.ordinal, 99) <= 2 "
                                + "      AND f.scope_node_id IN (SELECT descendant_id FROM sub "
                                + "                               WHERE root_id = s.root_id)), "
                                + "  (SELECT count(*) FROM finding f "
                                + "     LEFT JOIN severity_level sl ON sl.id = "
                                + "          coalesce(f.effective_severity_id, f.reported_severity_id) "
                                + "     LEFT JOIN LATERAL (SELECT a.exposure_declared, a.criticality_tier_id "
                                + "            FROM assessment_request_scope_asset x "
                                + "            JOIN asset a ON a.id = x.asset_id "
                                + "           WHERE x.request_id = f.discovered_in_request_id "
                                + "           ORDER BY a.exposure_declared LIMIT 1) tgt ON true "
                                + "    WHERE f.state = 'OPEN' AND coalesce(sl.ordinal, 99) <= 2 "
                                + "      AND tgt.exposure_declared = 'INTERNET_PUBLIC' "
                                + "      AND tgt.criticality_tier_id = (SELECT id FROM criticality_tier "
                                + "                                      ORDER BY ordinal LIMIT 1) "
                                + "      AND f.scope_node_id IN (SELECT descendant_id FROM sub "
                                + "                               WHERE root_id = s.root_id)), "
                                + "  (SELECT to_char(max(r.created_at), 'YYYY-MM-DD') "
                                + "     FROM assessment_request r "
                                + "    WHERE r.requested_org_node_id IN (SELECT descendant_id FROM sub "
                                + "                                       WHERE root_id = s.root_id)) "
                                + "  FROM sub s GROUP BY s.root_id, s.name ORDER BY s.name")) {
            statement.setArray(1, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Posture(r.getString(1), r.getString(2), r.getLong(3), r.getLong(4),
                            r.getLong(5), r.getLong(6), r.getLong(7), r.getLong(8),
                            r.getString(9)));
                }
            }
        }
        return List.copyOf(rows);
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Every number the rules need, in one round trip.
     *
     * <p>One statement rather than seven: the overview is the most-opened page in the platform, and
     * a rule set that costs a query each is a page that gets slower every time somebody adds an
     * insight.
     *
     * <p>A finding reaches its asset through the request that found it. {@code finding_asset_impact}
     * is the direct link and is populated only by the ingestion pipeline, so relying on it alone
     * would make every rule silent for manually recorded work — which is most of it.
     */
    private Map<String, Long> facts(Principal principal) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        String inScope = "IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))";
        String exposedJoin =
                "  FROM finding f "
                        + "  LEFT JOIN severity_level s ON s.id = "
                        + "       coalesce(f.effective_severity_id, f.reported_severity_id) "
                        + "  LEFT JOIN LATERAL ( "
                        + "        SELECT a.exposure_declared, a.criticality_tier_id "
                        + "          FROM assessment_request_scope_asset sa "
                        + "          JOIN asset a ON a.id = sa.asset_id "
                        + "         WHERE sa.request_id = f.discovered_in_request_id "
                        + "         ORDER BY a.exposure_declared LIMIT 1 "
                        + "  ) tgt ON true "
                        + " WHERE f.state = 'OPEN' AND f.scope_node_id " + inScope
                        + "   AND coalesce(s.ordinal, 99) <= 2 "
                        + "   AND tgt.exposure_declared = 'INTERNET_PUBLIC' "
                        + "   AND tgt.criticality_tier_id = (SELECT id FROM criticality_tier "
                        + "                                   ORDER BY ordinal LIMIT 1) ";

        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT "
                                + " (SELECT count(*) " + exposedJoin + ") AS exposed_serious_open, "
                                + " (SELECT count(*) " + exposedJoin
                                + "     AND f.first_detected_at < now() - interval '90 days') "
                                + "     AS exposed_serious_old, "
                                + " (SELECT count(*) FROM finding f WHERE f.state = 'OPEN' "
                                + "     AND f.scope_node_id " + inScope + ") AS open_total, "
                                // The backlog ninety days ago, from the two dates each finding
                                // already carries. A stored counter would have to have been running.
                                + " (SELECT count(*) FROM finding f "
                                + "   WHERE f.first_detected_at < now() - interval '90 days' "
                                + "     AND (f.closed_at IS NULL "
                                + "          OR f.closed_at >= now() - interval '90 days') "
                                + "     AND f.scope_node_id " + inScope + ") AS open_90_days_ago, "
                                + " (SELECT count(*) FROM asset a JOIN asset_type t ON t.id = a.type_id "
                                + "   WHERE t.code = 'APPLICATION' AND a.lifecycle_state <> 'RETIRED' "
                                + "     AND a.owning_node_id " + inScope + ") AS apps_total, "
                                + " (SELECT count(*) FROM asset a JOIN asset_type t ON t.id = a.type_id "
                                + "   WHERE t.code = 'APPLICATION' AND a.lifecycle_state <> 'RETIRED' "
                                + "     AND a.owning_node_id " + inScope
                                + "     AND NOT EXISTS (SELECT 1 FROM assessment_request_scope_asset sa "
                                + "                      WHERE sa.asset_id = a.id)) AS apps_never_assessed, "
                                + " (SELECT count(*) FROM finding f WHERE f.state = 'OPEN' "
                                + "     AND f.remediation_claimed_at IS NOT NULL "
                                + "     AND f.scope_node_id " + inScope + ") AS retest_total, "
                                + " (SELECT count(*) FROM finding f WHERE f.state = 'OPEN' "
                                + "     AND f.remediation_claimed_at < now() - interval '14 days' "
                                + "     AND f.scope_node_id " + inScope + ") AS retest_overdue, "
                                + " (SELECT count(*) FROM finding f WHERE f.state = 'OPEN' "
                                + "     AND f.assignee_id IS NULL "
                                + "     AND f.scope_node_id " + inScope + ") AS unowned_open, "
                                + " (SELECT count(*) FROM finding f "
                                + "   LEFT JOIN severity_level s2 ON s2.id = "
                                + "        coalesce(f.effective_severity_id, f.reported_severity_id) "
                                + "   WHERE f.assessment_context IN ('BUG_BOUNTY', 'INCIDENT') "
                                + "     AND coalesce(s2.ordinal, 99) <= 2 "
                                + "     AND f.first_detected_at >= now() - interval '90 days' "
                                + "     AND f.scope_node_id " + inScope + ") AS escaped_90, "
                                + " (SELECT count(*) FROM asset a "
                                + "   WHERE a.lifecycle_state <> 'RETIRED' "
                                + "     AND a.owning_node_id " + inScope + ") AS assets_total, "
                                + " (SELECT count(*) FROM asset a "
                                + "   WHERE a.lifecycle_state <> 'RETIRED' "
                                + "     AND a.owning_node_id " + inScope
                                + "     AND NOT EXISTS (SELECT 1 FROM sbom_coverage_state c "
                                + "          WHERE c.asset_id = a.id "
                                + "            AND c.latest_snapshot_at IS NOT NULL)) AS assets_no_sbom")) {
            // Every placeholder in this statement is the same scope array, and the COUNT comes from
            // the driver rather than from me. Hand-counting it was wrong three times across this
            // codebase — off by one here, off by one in two of the analytics queries — and each was
            // silent until execution. The statement knows how many it has; ask it.
            java.sql.Array scope = connection.createArrayOf("uuid",
                    principal.scopeNodeIds().toArray(new UUID[0]));
            int parameters = statement.getParameterMetaData().getParameterCount();
            for (int i = 1; i <= parameters; i++) {
                statement.setArray(i, scope);
            }
            try (ResultSet r = statement.executeQuery()) {
                r.next();
                java.sql.ResultSetMetaData meta = r.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    out.put(meta.getColumnLabel(i), r.getLong(i));
                }
            }
        }
        return out;
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from "
                + "the authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
