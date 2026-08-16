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
 * What the estate is made of, how long it takes to fix, and which way both are moving.
 *
 * <p>These are the questions an executive asks after the risk score: <i>how fast do we close things,
 * is the backlog getting older, what kind of weakness keeps coming back, and is the surface we have
 * to defend growing.</i> They are separate from {@link RiskScoring} because none of them is a score
 * — every figure here is a count or an elapsed time computed directly from recorded events, and
 * mixing them into the scoring class would blur which figures depend on the model's missing factors
 * and which do not. Nothing on this surface does.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>Several breakdowns a customer expects on this page cannot be produced from what the platform
 * records, and this class returns nothing for them rather than a plausible substitute:
 *
 * <ul>
 *   <li><b>By cloud provider</b> — no asset carries a provider. The asset type registry (ADR-009)
 *       has no cloud dimension and inventing one from a hostname would be a guess presented as a
 *       fact.
 *   <li><b>By environment</b> — environments are declared per assessment request, not per asset, so
 *       "risk in production" is not answerable about an asset that was never assessed. Answering it
 *       over only assessed assets would report the measured slice as the whole.
 *   <li><b>By weakness taxonomy</b> — there is no CWE or OWASP category column.
 *       {@link Category#code() finding_class} is the closest recorded classification and it has
 *       seven values, so it answers "what kind of control failed" and not "which CWE".
 * </ul>
 */
public final class AttackSurface {

    /** Mean and median days from first detection to closure, with the population behind them. */
    public record Remediation(long closed, Double meanDays, Double medianDays, Double p90Days) {
    }

    /** One month of closures. */
    public record RemediationPoint(String label, long closed, Double medianDays) {
    }

    /** Open findings in an age bucket. */
    public record AgeBucket(String label, long findings, long serious) {
    }

    /** Findings grouped by the classification the platform actually records. */
    public record Category(String code, long open, long closed, long serious) {
    }

    /** The estate by asset type, with the internet-facing share of each. */
    public record AssetClass(String code, String label, long total, long internetFacing,
            long unclassified) {
    }

    /** One month of first-seen assets — the surface growing or not. */
    public record GrowthPoint(String label, long added, long cumulative) {
    }

    /** An internet-facing asset ranked by what is open on it. */
    public record ExposedAsset(String id, String name, String typeCode, String criticality,
            long open, long serious, String lastAssessedAt) {
    }

    private final DataSource dataSource;

    public AttackSurface(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Time to remediate, over findings actually closed in the window.
     *
     * <p>Mean <b>and</b> median <b>and</b> p90, because they disagree and the disagreement is the
     * information. A team that closes forty trivial findings in a day and leaves four hard ones open
     * for a year has a good median and a bad mean; reporting either alone lets one of those two
     * stories be told without the other. p90 is what a delivery team experiences as "how long this
     * usually takes when it is my finding".
     *
     * <p>Computed only over closed findings, and the count is returned with it. An MTTR over eleven
     * closures is not a service level, and a reader who cannot see the eleven will treat it as one.
     */
    public Remediation remediation(Principal principal, int days) throws SQLException {
        Set<UUID> scope = scopeOf(principal);
        if (scope.isEmpty()) {
            return new Remediation(0, null, null, null);
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*), "
                                + "  avg(extract(epoch FROM (closed_at - first_detected_at)) / 86400), "
                                + "  percentile_cont(0.5) WITHIN GROUP (ORDER BY "
                                + "    extract(epoch FROM (closed_at - first_detected_at)) / 86400), "
                                + "  percentile_cont(0.9) WITHIN GROUP (ORDER BY "
                                + "    extract(epoch FROM (closed_at - first_detected_at)) / 86400) "
                                + "  FROM finding "
                                + " WHERE closed_at IS NOT NULL "
                                + "   AND closed_at > now() - make_interval(days => ?) "
                                + "   AND closed_at >= first_detected_at "
                                + "   AND scope_node_id IN (SELECT descendant_id FROM org_closure "
                                + "                          WHERE ancestor_id = ANY (?))")) {
            statement.setInt(1, Math.max(1, days));
            statement.setArray(2, array(connection, scope));
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next() || r.getLong(1) == 0) {
                    return new Remediation(0, null, null, null);
                }
                return new Remediation(r.getLong(1), round(r.getDouble(2)), round(r.getDouble(3)),
                        round(r.getDouble(4)));
            }
        }
    }

    /** Median time to close per month, so a reader can see whether it is improving. */
    public List<RemediationPoint> remediationTrend(Principal principal, int months)
            throws SQLException {
        Set<UUID> scope = scopeOf(principal);
        if (scope.isEmpty()) {
            return List.of();
        }
        List<RemediationPoint> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "WITH months AS (SELECT generate_series(date_trunc('month', now()) "
                                + "        - make_interval(months => ? - 1), "
                                + "        date_trunc('month', now()), interval '1 month') AS m) "
                                + "SELECT to_char(months.m, 'YYYY-MM'), count(f.id), "
                                + "  percentile_cont(0.5) WITHIN GROUP (ORDER BY "
                                + "    extract(epoch FROM (f.closed_at - f.first_detected_at)) / 86400) "
                                + "  FROM months "
                                + "  LEFT JOIN finding f "
                                + "         ON date_trunc('month', f.closed_at) = months.m "
                                + "        AND f.closed_at >= f.first_detected_at "
                                + "        AND f.scope_node_id IN (SELECT descendant_id FROM org_closure "
                                + "                                 WHERE ancestor_id = ANY (?)) "
                                + " GROUP BY months.m ORDER BY months.m")) {
            statement.setInt(1, Math.max(1, Math.min(24, months)));
            statement.setArray(2, array(connection, scope));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    double median = r.getDouble(3);
                    rows.add(new RemediationPoint(r.getString(1), r.getLong(2),
                            r.wasNull() ? null : round(median)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Open findings by age.
     *
     * <p>Buckets rather than an average age, because the shape is what matters: a backlog with a long
     * tail and a backlog with a uniform spread have the same mean and need different responses. The
     * serious count travels with each bucket for the same reason — three hundred low findings older
     * than ninety days is untidy; three critical ones is a different conversation.
     */
    public List<AgeBucket> aging(Principal principal) throws SQLException {
        Set<UUID> scope = scopeOf(principal);
        if (scope.isEmpty()) {
            return List.of();
        }
        List<AgeBucket> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "WITH aged AS (SELECT f.id, "
                                + "        extract(epoch FROM (now() - f.first_detected_at)) / 86400 AS age, "
                                + "        coalesce(sl.ordinal, 99) AS ordinal "
                                + "     FROM finding f "
                                + "     LEFT JOIN severity_level sl ON sl.id = "
                                + "          coalesce(f.effective_severity_id, f.reported_severity_id) "
                                + "    WHERE f.state = 'OPEN' AND f.scope_node_id IN "
                                + "          (SELECT descendant_id FROM org_closure "
                                + "            WHERE ancestor_id = ANY (?))), "
                                // The top two of the tenant's own scale, never a hardcoded name list
                                // (ADR-027) — a tenant with six bands must still get its worst two.
                                + "  serious AS (SELECT coalesce(max(ordinal), 2) AS cut "
                                + "     FROM (SELECT ordinal FROM severity_level "
                                + "            WHERE lifecycle_state = 'ACTIVE' "
                                + "            ORDER BY ordinal LIMIT 2) top2), "
                                + "  buckets AS (SELECT * FROM (VALUES "
                                + "     ('0–30 days', 0, 30), ('31–60 days', 30, 60), "
                                + "     ('61–90 days', 60, 90), ('91–180 days', 90, 180), "
                                + "     ('over 180 days', 180, 100000)) AS b(label, lo, hi)) "
                                + "SELECT b.label, count(a.id), "
                                + "       count(a.id) FILTER (WHERE a.ordinal <= (SELECT cut FROM serious)) "
                                + "  FROM buckets b "
                                + "  LEFT JOIN aged a ON a.age >= b.lo AND a.age < b.hi "
                                + " GROUP BY b.label, b.lo ORDER BY b.lo")) {
            statement.setArray(1, array(connection, scope));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new AgeBucket(r.getString(1), r.getLong(2), r.getLong(3)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Findings by the classification the platform records.
     *
     * <p>This answers "what kind of control keeps failing" — the question behind the root-cause
     * request — and it does not answer "which CWE", because no CWE is recorded. Naming the axis
     * honestly is the difference between a chart that directs a training budget and one that looks
     * like it does.
     */
    public List<Category> categories(Principal principal) throws SQLException {
        Set<UUID> scope = scopeOf(principal);
        if (scope.isEmpty()) {
            return List.of();
        }
        List<Category> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "WITH serious AS (SELECT coalesce(max(ordinal), 2) AS cut "
                                + "     FROM (SELECT ordinal FROM severity_level "
                                + "            WHERE lifecycle_state = 'ACTIVE' "
                                + "            ORDER BY ordinal LIMIT 2) top2) "
                                + "SELECT f.finding_class, "
                                + "       count(*) FILTER (WHERE f.state = 'OPEN'), "
                                + "       count(*) FILTER (WHERE f.state <> 'OPEN'), "
                                + "       count(*) FILTER (WHERE f.state = 'OPEN' "
                                + "         AND coalesce(sl.ordinal, 99) <= (SELECT cut FROM serious)) "
                                + "  FROM finding f "
                                + "  LEFT JOIN severity_level sl ON sl.id = "
                                + "       coalesce(f.effective_severity_id, f.reported_severity_id) "
                                + " WHERE f.scope_node_id IN (SELECT descendant_id FROM org_closure "
                                + "                            WHERE ancestor_id = ANY (?)) "
                                + " GROUP BY f.finding_class "
                                + " ORDER BY 2 DESC, 4 DESC")) {
            statement.setArray(1, array(connection, scope));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Category(r.getString(1), r.getLong(2), r.getLong(3), r.getLong(4)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * The estate by asset type, with how much of each is internet-facing and how much unclassified.
     *
     * <p>The unclassified column is not a rounding remainder. An asset with no declared exposure is
     * the one nobody has looked at, and folding it into "internal" would turn the most uncertain part
     * of the estate into the most reassuring number on the page.
     */
    public List<AssetClass> assetClasses(Principal principal) throws SQLException {
        Set<UUID> scope = scopeOf(principal);
        if (scope.isEmpty()) {
            return List.of();
        }
        List<AssetClass> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT ty.code, coalesce(ty.label_i18n ->> 'en', ty.code), count(a.id), "
                                + "  count(a.id) FILTER (WHERE a.exposure_declared = 'INTERNET_PUBLIC'), "
                                + "  count(a.id) FILTER (WHERE a.exposure_declared IS NULL) "
                                + "  FROM asset_type ty "
                                + "  JOIN asset a ON a.type_id = ty.id "
                                + "   AND a.lifecycle_state <> 'RETIRED' "
                                + "   AND a.owning_node_id IN (SELECT descendant_id FROM org_closure "
                                + "                             WHERE ancestor_id = ANY (?)) "
                                + " GROUP BY ty.code, ty.label_i18n, ty.ordinal "
                                + " ORDER BY count(a.id) DESC, ty.ordinal")) {
            statement.setArray(1, array(connection, scope));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new AssetClass(r.getString(1), r.getString(2), r.getLong(3),
                            r.getLong(4), r.getLong(5)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Assets first seen per month, and the running total.
     *
     * <p>{@code first_seen_at} is when the platform learned of an asset, not when it was built. The
     * distinction matters on the first months of a deployment, where onboarding an inventory looks
     * identical to explosive growth — which is why the cumulative line is returned alongside, and why
     * the interface labels the axis as discovery rather than as creation.
     */
    public List<GrowthPoint> growth(Principal principal, int months) throws SQLException {
        Set<UUID> scope = scopeOf(principal);
        if (scope.isEmpty()) {
            return List.of();
        }
        List<GrowthPoint> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "WITH months AS (SELECT generate_series(date_trunc('month', now()) "
                                + "        - make_interval(months => ? - 1), "
                                + "        date_trunc('month', now()), interval '1 month') AS m), "
                                + "  scoped AS (SELECT a.first_seen_at FROM asset a "
                                + "    WHERE a.lifecycle_state <> 'RETIRED' "
                                + "      AND a.owning_node_id IN (SELECT descendant_id FROM org_closure "
                                + "                                WHERE ancestor_id = ANY (?))) "
                                + "SELECT to_char(months.m, 'YYYY-MM'), "
                                + "  (SELECT count(*) FROM scoped s "
                                + "    WHERE date_trunc('month', s.first_seen_at) = months.m), "
                                + "  (SELECT count(*) FROM scoped s "
                                + "    WHERE s.first_seen_at < months.m + interval '1 month') "
                                + "  FROM months ORDER BY months.m")) {
            statement.setInt(1, Math.max(1, Math.min(24, months)));
            statement.setArray(2, array(connection, scope));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new GrowthPoint(r.getString(1), r.getLong(2), r.getLong(3)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Internet-facing assets, ranked by what is open on them.
     *
     * <p>An internet-facing asset with <b>no</b> open findings and no assessment appears in this list
     * too, at the bottom with an empty last-assessed date. That is the row worth the reader's
     * attention: an unassessed asset reachable from the internet is not a low-risk asset, and a list
     * that ranks purely by finding count would put it where nobody looks.
     */
    public List<ExposedAsset> internetFacing(Principal principal, int limit) throws SQLException {
        Set<UUID> scope = scopeOf(principal);
        if (scope.isEmpty()) {
            return List.of();
        }
        List<ExposedAsset> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "WITH serious AS (SELECT coalesce(max(ordinal), 2) AS cut "
                                + "     FROM (SELECT ordinal FROM severity_level "
                                + "            WHERE lifecycle_state = 'ACTIVE' "
                                + "            ORDER BY ordinal LIMIT 2) top2) "
                                + "SELECT a.id::text, a.display_name, ty.code, "
                                + "  coalesce(ct.code, cn.code, 'UNCLASSIFIED'), "
                                + "  (SELECT count(*) FROM finding f "
                                + "     JOIN assessment_request_scope_asset x "
                                + "       ON x.request_id = f.discovered_in_request_id "
                                + "    WHERE x.asset_id = a.id AND f.state = 'OPEN'), "
                                + "  (SELECT count(*) FROM finding f "
                                + "     JOIN assessment_request_scope_asset x "
                                + "       ON x.request_id = f.discovered_in_request_id "
                                + "     LEFT JOIN severity_level sl ON sl.id = "
                                + "          coalesce(f.effective_severity_id, f.reported_severity_id) "
                                + "    WHERE x.asset_id = a.id AND f.state = 'OPEN' "
                                + "      AND coalesce(sl.ordinal, 99) <= (SELECT cut FROM serious)), "
                                // request_board is where closure time lives — assessment_request has
                                // no closed_at, because the transition log is the record of when a
                                // state was entered (INV-WRK-03) and the view derives it from there.
                                + "  to_char((SELECT max(r.closed_at) FROM request_board r "
                                + "     JOIN assessment_request_scope_asset x ON x.request_id = r.id "
                                + "    WHERE x.asset_id = a.id AND r.closed_at IS NOT NULL), "
                                + "    'YYYY-MM-DD') "
                                + "  FROM asset a "
                                + "  JOIN asset_type ty ON ty.id = a.type_id "
                                + "  LEFT JOIN criticality_tier ct ON ct.id = a.criticality_tier_id "
                                + "  LEFT JOIN org_node n ON n.id = a.owning_node_id "
                                + "  LEFT JOIN criticality_tier cn ON cn.id = n.criticality_tier_id "
                                + " WHERE a.lifecycle_state <> 'RETIRED' "
                                + "   AND (a.exposure_declared = 'INTERNET_PUBLIC' "
                                + "        OR a.exposure_observed = 'INTERNET_PUBLIC') "
                                + "   AND a.owning_node_id IN (SELECT descendant_id FROM org_closure "
                                + "                             WHERE ancestor_id = ANY (?)) "
                                + " ORDER BY 6 DESC, 5 DESC, a.display_name "
                                + " LIMIT " + Math.max(1, Math.min(50, limit)))) {
            statement.setArray(1, array(connection, scope));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new ExposedAsset(r.getString(1), r.getString(2), r.getString(3),
                            r.getString(4), r.getLong(5), r.getLong(6), r.getString(7)));
                }
            }
        }
        return List.copyOf(rows);
    }

    // ----------------------------------------------------------------------------------------------

    private static Double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static Set<UUID> scopeOf(Principal principal) {
        return principal == null ? Set.of() : principal.scopeNodeIds();
    }

    private static java.sql.Array array(Connection connection, Set<UUID> scope) throws SQLException {
        return connection.createArrayOf("uuid", scope.toArray(new UUID[0]));
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from "
                + "the authenticated caller and from nowhere else");
        return TenantConnections.open(dataSource, principal);
    }
}
