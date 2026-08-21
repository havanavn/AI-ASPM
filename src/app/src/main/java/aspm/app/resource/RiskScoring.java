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
 * DOC-28's risk model, implemented. {@code PRD-RSK-016} · {@code PRD-RSK-017} ·
 * {@code PRD-RSK-027} · {@code PRD-RSK-028}.
 *
 * <h2>Why the specified model rather than a score invented for the dashboard</h2>
 *
 * <p>Every "Overall Risk Score", "Security Score by Business Unit" and "Top Risk Applications" a
 * customer asks for needs one number that survives an executive meeting. DOC-28 §3.1 states the
 * condition it has to meet: a score that cannot be explained factor by factor, reproduced exactly,
 * and defended on its inputs will be disbelieved once and inert thereafter.
 *
 * <p>So the formula, the factor set, the weights and the aggregation below are DOC-28's, quoted in
 * the code so a disputant can be shown the derivation rather than told to trust it.
 *
 * <h2>Three of six factors have no input here, and the score says so</h2>
 *
 * <p>{@code SEV}, {@code EXPO} and {@code CRIT} are recorded. {@code EXP} (exploit prediction) and
 * {@code KEV} (known-exploited catalogue) need threat intelligence this deployment does not
 * subscribe to; {@code DATA} (data sensitivity) is classified on no asset. That matters: the two
 * missing exploitability factors are the ones that separate the twelve findings worth acting on from
 * the four thousand of the same severity that are not.
 *
 * <p>The model therefore scores over the factors it has and reports the shortfall explicitly rather
 * than absorbing it. Every score carries {@link Score#factorCoverage()} — the share of the model's
 * weight that had an input — and every aggregate carries a {@link Confidence} that this deployment
 * cannot raise above {@code LOW} until those three feeds exist. {@code PRD-RSK-027}: a figure at
 * {@code INSUFFICIENT} is presented as a coverage gap and never as a posture number.
 *
 * <h2>Aggregation is not summation</h2>
 *
 * <p>DOC-28 §10.1 rejects summing finding scores under a node, for the reasons an executive raises
 * first: it penalizes size, it <b>rewards concealment</b> — a unit that scans less scores better —
 * and it hides concentration. §10.2's four components are implemented instead, with a coverage
 * penalty that makes unmeasured scope expensive rather than free ({@code PRD-RSK-028}).
 */
public final class RiskScoring {

    // DOC-28 §5.7. These sum to 1.10 by design; §6.1 normalizes by the total.
    private static final double W_SEV = 0.30;
    private static final double W_EXP = 0.20;
    private static final double W_KEV = 0.20;
    private static final double W_EXPO = 0.15;
    private static final double W_CRIT = 0.15;
    private static final double W_DATA = 0.10;
    private static final double W_TOTAL = W_SEV + W_EXP + W_KEV + W_EXPO + W_CRIT + W_DATA;

    /**
     * The weight this deployment can supply an input for: 0.60 of 1.10, or 55%.
     *
     * <p>{@code EXP} and {@code KEV} need a threat-intelligence feed; {@code DATA} needs asset data
     * classification. Neither exists yet. The score is normalized over this rather than over the
     * total — see {@link #SCORE} for why the apparently more conservative choice is the misleading
     * one — and the shortfall is carried by {@link Confidence} and reported with every figure.
     */
    public static final double AVAILABLE_WEIGHT = W_SEV + W_EXPO + W_CRIT;

    /** The factor coverage of every score this class produces. */
    public static final double FACTOR_COVERAGE = AVAILABLE_WEIGHT / W_TOTAL;

    /** Recorded with every figure so a number can be reproduced later (DOC-28 §8). */
    public static final String MODEL_VERSION = "doc28/1.0.0-partial-inputs";

    /** The factors with no data source, named so the interface can say which. */
    public static final List<String> ABSENT_FACTORS = List.of("EXP", "KEV", "DATA");

    /** DOC-28 §9. */
    public enum Confidence { HIGH, MEDIUM, LOW, INSUFFICIENT }

    /** DOC-28 §6.3 bands. */
    public static String band(int score) {
        if (score >= 90) {
            return "CRITICAL";
        }
        if (score >= 70) {
            return "HIGH";
        }
        if (score >= 40) {
            return "MEDIUM";
        }
        if (score >= 15) {
            return "LOW";
        }
        return "INFORMATIONAL";
    }

    /** One finding's score, with what is needed to defend it. */
    public record Score(UUID findingId, UUID requestId, String title, String severity, int score,
            String scoreBand, String exposure, String criticality, double factorCoverage,
            String modelVersion) {
    }

    /** The count of open findings in each DOC-28 §6.3 band. The "Risk Distribution" question. */
    public record Distribution(String scoreBand, long findings) {
    }

    /**
     * A node or application's posture. DOC-28 §10.2.
     *
     * @param confidence {@code INSUFFICIENT} means {@code posture} must not be shown as a figure
     */
    public record Posture(String id, String name, int posture, String postureBand,
            Confidence confidence, double assetCoverage, long assets, long measuredAssets,
            long findings, int worstScore, double severityPressure, double concentration,
            double slaHealth, double coveragePenalty, double factorCoverage, String modelVersion) {

        /** {@code PRD-RSK-027}: below this, the number is a coverage gap, not a posture. */
        public boolean presentable() {
            return confidence != Confidence.INSUFFICIENT;
        }
    }

    private final DataSource dataSource;

    public RiskScoring(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ==============================================================================================
    // The model, as SQL.
    // ==============================================================================================

    /**
     * Each finding joined to the context that scores it.
     *
     * <p>Exposure and criticality belong to the <b>asset</b>, not the finding, so this resolves the
     * asset a finding was found against — the most exposed one in the request's declared scope, on
     * the principle that a request covering an internet-facing service and an internal repository is
     * assessing an internet-facing thing.
     *
     * <p>Criticality falls back through the inheritance the model already defines
     * ({@code criticality_mode = 'INHERITED'}): the asset's own tier, then its owning node's, then
     * the finding's scope node's. A finding whose asset was never classified still lands somewhere
     * defensible rather than being dropped from the ranking.
     */
    private static final String CONTEXT =
            "  FROM finding f "
                    + "  LEFT JOIN severity_level sl "
                    + "         ON sl.id = coalesce(f.effective_severity_id, f.reported_severity_id) "
                    + "  LEFT JOIN LATERAL ("
                    + "        SELECT a.id, a.exposure_declared, "
                    + "               coalesce(a.criticality_tier_id, an.criticality_tier_id) AS crit "
                    + "          FROM assessment_request_scope_asset x "
                    + "          JOIN asset a ON a.id = x.asset_id "
                    + "          LEFT JOIN org_node an ON an.id = a.owning_node_id "
                    + "         WHERE x.request_id = f.discovered_in_request_id "
                    + "         ORDER BY CASE a.exposure_declared "
                    + "                    WHEN 'INTERNET_PUBLIC' THEN 0 WHEN 'PARTNER_B2B' THEN 1 "
                    + "                    WHEN 'INTERNAL_ONLY'   THEN 2 WHEN 'AIR_GAPPED'  THEN 3 "
                    + "                    ELSE 4 END "
                    + "         LIMIT 1) tgt ON true "
                    + "  LEFT JOIN org_node fn ON fn.id = f.scope_node_id "
                    + "  LEFT JOIN criticality_tier ct "
                    + "         ON ct.id = coalesce(tgt.crit, fn.criticality_tier_id) ";

    /**
     * {@code SEV}, normalized to {@code [0,1]} over the tenant's own scale ({@code PRD-RSK-017}).
     *
     * <p>Ordinal 1 is the worst in both the severity scale and the criticality tiers, and the number
     * of levels is tenant data — ADR-027 forbids a hardcoded {@code CRITICAL/HIGH/MEDIUM} list, and
     * a tenant with six bands must normalize over six.
     *
     * <p>An <b>unrated</b> finding takes the midpoint rather than the floor. Treating "nobody has
     * rated this" as "this is low" is exactly the inference PP-1 forbids: absence of evidence is not
     * evidence of absence. The midpoint keeps unrated work visible in the ranking without inventing
     * a severity for it.
     */
    private static final String SEV =
            "(1.0 - (coalesce(sl.ordinal::numeric, "
                    + "        (SELECT (1 + max(ordinal))::numeric / 2 FROM severity_level "
                    + "          WHERE lifecycle_state = 'ACTIVE'), 3) - 1) "
                    + "     / greatest((SELECT count(*) FROM severity_level "
                    + "                  WHERE lifecycle_state = 'ACTIVE'), 2))";

    /**
     * {@code EXPO}. Unclassified is 0.25, not 0 — an asset nobody has classified is not an asset
     * that has been shown to be unreachable.
     */
    private static final String EXPO =
            "(CASE tgt.exposure_declared WHEN 'INTERNET_PUBLIC' THEN 1.0 WHEN 'PARTNER_B2B' THEN 0.75 "
                    + "      WHEN 'INTERNAL_ONLY' THEN 0.5 WHEN 'AIR_GAPPED' THEN 0.15 ELSE 0.25 END)";

    /** {@code CRIT}, normalized over the tenant's own tiers; unclassified takes the midpoint. */
    private static final String CRIT =
            "(1.0 - (coalesce(ct.ordinal::numeric, "
                    + "        (SELECT (1 + max(ordinal))::numeric / 2 FROM criticality_tier "
                    + "          WHERE lifecycle_state = 'ACTIVE'), 2) - 1) "
                    + "     / greatest((SELECT count(*) FROM criticality_tier "
                    + "                  WHERE lifecycle_state = 'ACTIVE'), 2))";

    /**
     * DOC-28 §6.1, verbatim:
     *
     * <pre>
     *   raw     = Σ ( weightᵢ × factorᵢ )
     *   context = max( EXPO, CRIT, DATA )
     *   score   = 100 × normalize(raw) × ( 0.4 + 0.6 × context )
     * </pre>
     *
     * <p>{@code normalize} divides by the <b>available</b> weight — the 0.60 that has an input — and
     * not by the 1.10 total. That looks like the less conservative choice and is the opposite.
     *
     * <p>Dividing by the total caps every score at 55, because 45% of the weight can never
     * contribute. A critical severity flaw on an internet-facing revenue system then scores 55 and
     * lands in the {@code MEDIUM} band, and the distribution chart reports <b>zero</b> critical and
     * zero high risk over an estate full of both. The reader draws the one conclusion this whole
     * platform exists to prevent them drawing. Withholding score is honest; mislabelling a band is
     * not, and a suppressed ceiling silently mislabels every band.
     *
     * <p>So the score uses the full range over the factors that are known, and the fact that only
     * 55% of the weight is known is carried by {@link Confidence} and {@link #FACTOR_COVERAGE}
     * instead — which is what DOC-28 §9 provides them for. The consequence is stated rather than
     * hidden: at this factor coverage the model cannot separate a proven-exploited flaw from a
     * theoretical one of the same severity, so confidence never rises above {@code LOW} however
     * complete the asset coverage becomes.
     *
     * <p>{@code DATA} is absent, so the contextual max runs over {@code EXPO} and {@code CRIT}.
     */
    private static final String SCORE =
            "100.0 * ((" + W_SEV + " * " + SEV + " + " + W_EXPO + " * " + EXPO
                    + " + " + W_CRIT + " * " + CRIT + ") / " + AVAILABLE_WEIGHT + ") "
                    + "* (0.4 + 0.6 * greatest(" + EXPO + ", " + CRIT + "))";

    /**
     * Every open in-scope finding with its score, as a CTE the aggregates build on.
     *
     * <p>One definition, used by all four queries below — PP-10. Two SQL expressions that both
     * "compute the risk score" diverge the first time one is edited, and the divergence surfaces as
     * a dashboard whose headline disagrees with its own table.
     */
    private static final String SCORED =
            "WITH scored AS (SELECT f.id, f.title, f.discovered_in_request_id AS request_id, "
                    + "       f.scope_node_id, f.first_detected_at, tgt.id AS asset_id, "
                    + "       coalesce(sl.code, 'UNRATED') AS severity, "
                    + "       coalesce(tgt.exposure_declared, 'UNCLASSIFIED') AS exposure, "
                    + "       coalesce(ct.code, 'UNCLASSIFIED') AS criticality, "
                    + "       " + SCORE + " AS score "
                    + CONTEXT
                    + " WHERE f.state = 'OPEN' AND f.scope_node_id IN "
                    + "       (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))) ";

    // ==============================================================================================
    // Queries.
    // ==============================================================================================

    /** The highest-scoring open findings in scope — what to work on first, in DOC-28's order. */
    public List<Score> topFindings(Principal principal, int limit) throws SQLException {
        Set<UUID> scope = scopeOf(principal);
        if (scope.isEmpty()) {
            return List.of();
        }
        List<Score> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(SCORED
                        + "SELECT id, request_id, title, severity, score, exposure, criticality "
                        + "  FROM scored ORDER BY score DESC, first_detected_at "
                        + " LIMIT " + bounded(limit, 100))) {
            statement.setArray(1, array(connection, scope));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    int score = (int) Math.round(r.getDouble(5));
                    rows.add(new Score(r.getObject(1, UUID.class), r.getObject(2, UUID.class),
                            r.getString(3), r.getString(4), score, band(score), r.getString(6),
                            r.getString(7), FACTOR_COVERAGE, MODEL_VERSION));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Open findings per score band. The "Risk Distribution" chart.
     *
     * <p>Bands, not raw scores, because DOC-28 §6.3 makes the band the primary presentation: the
     * difference between 71 and 74 is inside the model's own error, and a chart that invites a reader
     * to act on it is misleading them precisely.
     */
    public List<Distribution> distribution(Principal principal) throws SQLException {
        Set<UUID> scope = scopeOf(principal);
        if (scope.isEmpty()) {
            return List.of();
        }
        long[] counts = new long[5];
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(SCORED
                        + "SELECT score FROM scored")) {
            statement.setArray(1, array(connection, scope));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    counts[index(band((int) Math.round(r.getDouble(1))))]++;
                }
            }
        }
        List<Distribution> rows = new ArrayList<>();
        String[] bands = {"CRITICAL", "HIGH", "MEDIUM", "LOW", "INFORMATIONAL"};
        for (int i = 0; i < bands.length; i++) {
            rows.add(new Distribution(bands[i], counts[i]));
        }
        return List.copyOf(rows);
    }

    private static int index(String band) {
        return switch (band) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 4;
        };
    }

    /**
     * Posture per application, ranked. The "Top Risk Applications" question.
     *
     * <p>Applications with no assessment appear too, at whatever their coverage penalty alone
     * produces. Omitting them would make the ranking answer "where have we found risk" while
     * appearing to answer "where is our risk" — the substitution PP-1 exists to prevent.
     */
    public List<Posture> applicationPosture(Principal principal, int limit) throws SQLException {
        return posture(principal, SCORED
                + ", app AS (SELECT a.id, a.display_name, "
                + "        EXISTS (SELECT 1 FROM assessment_request_scope_asset x "
                + "                 WHERE x.asset_id = a.id) AS measured "
                + "     FROM asset a JOIN asset_type ty ON ty.id = a.type_id "
                + "    WHERE ty.code = 'APPLICATION' AND a.lifecycle_state <> 'RETIRED' "
                + "      AND a.owning_node_id IN (SELECT descendant_id FROM org_closure "
                + "                                WHERE ancestor_id = ANY (?))) "
                + "SELECT app.id::text, app.display_name, "
                + "       count(s.id), coalesce(max(s.score), 0), coalesce(avg(s.score), 0), "
                + "       1, CASE WHEN app.measured THEN 1 ELSE 0 END, "
                + "       count(s.id) FILTER (WHERE s.first_detected_at > now() - interval '90 days') "
                + "  FROM app LEFT JOIN scored s ON s.asset_id = app.id "
                + " GROUP BY app.id, app.display_name, app.measured "
                + " ORDER BY max(s.score) DESC NULLS LAST, count(s.id) DESC "
                + " LIMIT " + bounded(limit, 50));
    }

    /** Posture per organization the caller reaches. The "Risk by Business Unit" question. */
    public List<Posture> organizationPosture(Principal principal) throws SQLException {
        return posture(principal, subtreePosture(
                "roots AS (SELECT id, name FROM org_node WHERE id = ANY (?)), "));
    }

    /**
     * The same posture, for <b>every node the caller can reach</b> rather than for their scope roots.
     *
     * <p>The organization tree is where somebody asks "which part of this is the problem", and a
     * score that exists only at the top cannot answer it. Each row scores its own whole subtree, so a
     * parent's figure is not the sum or the mean of the rows drawn under it — DOC-28 §10.1 rejects
     * both, and re-deriving a parent from its children here would reintroduce exactly the summation
     * the model refuses.
     *
     * <p>{@link Posture#presentable()} is false wherever the coverage is too thin to show a number.
     * Those rows must be rendered as a coverage gap ({@code PRD-RSK-027}); a tree of confident-looking
     * scores over unmeasured estate is the misreading this whole model exists to prevent.
     */
    public List<Posture> nodePosture(Principal principal) throws SQLException {
        return posture(principal, subtreePosture(
                "roots AS (SELECT id, name FROM org_node "
                        + "         WHERE id IN (SELECT descendant_id FROM org_closure "
                        + "                       WHERE ancestor_id = ANY (?))), "));
    }

    /**
     * @param roots the {@code roots AS (…),} clause, binding exactly one scope array. Everything
     *     after it is shared, because the two callers differ only in which nodes get a row
     */
    private static String subtreePosture(String roots) {
        return SCORED
                + ", " + roots
                + "  sub AS (SELECT r.id AS root_id, r.name, c.descendant_id "
                + "            FROM roots r JOIN org_closure c ON c.ancestor_id = r.id) "
                + "SELECT r.id::text, r.name, "
                + "  (SELECT count(*) FROM scored s WHERE s.scope_node_id IN "
                + "     (SELECT descendant_id FROM sub WHERE root_id = r.id)), "
                + "  coalesce((SELECT max(s.score) FROM scored s WHERE s.scope_node_id IN "
                + "     (SELECT descendant_id FROM sub WHERE root_id = r.id)), 0), "
                + "  coalesce((SELECT avg(s.score) FROM scored s WHERE s.scope_node_id IN "
                + "     (SELECT descendant_id FROM sub WHERE root_id = r.id)), 0), "
                + "  (SELECT count(*) FROM asset a JOIN asset_type ty ON ty.id = a.type_id "
                + "    WHERE ty.code = 'APPLICATION' AND a.lifecycle_state <> 'RETIRED' "
                + "      AND a.owning_node_id IN (SELECT descendant_id FROM sub WHERE root_id = r.id)), "
                + "  (SELECT count(*) FROM asset a JOIN asset_type ty ON ty.id = a.type_id "
                + "    WHERE ty.code = 'APPLICATION' AND a.lifecycle_state <> 'RETIRED' "
                + "      AND a.owning_node_id IN (SELECT descendant_id FROM sub WHERE root_id = r.id) "
                + "      AND EXISTS (SELECT 1 FROM assessment_request_scope_asset x "
                + "                   WHERE x.asset_id = a.id)), "
                + "  (SELECT count(*) FROM scored s "
                + "    WHERE s.first_detected_at > now() - interval '90 days' "
                + "      AND s.scope_node_id IN (SELECT descendant_id FROM sub WHERE root_id = r.id)) "
                + "  FROM roots r ORDER BY r.name";
    }

    /**
     * One posture figure over everything the caller reaches. The "Overall Risk Score" headline.
     *
     * <p>Computed over the whole scope in one pass rather than averaged from
     * {@link #organizationPosture}, because averaging aggregates of aggregates loses the property
     * §10.1 was protecting: the group figure has to be driven by the group's worst finding, not by
     * the mean of each unit's already-flattened number.
     *
     * <p>Returns {@code null} where the caller reaches nothing, rather than a zero. A zero would read
     * as "no risk" when it means "no scope", and that is the most consequential misreading this page
     * can produce.
     */
    public Posture overall(Principal principal) throws SQLException {
        List<Posture> rows = posture(principal, SCORED
                + ", estate AS (SELECT count(*) AS assets, "
                + "        count(*) FILTER (WHERE EXISTS (SELECT 1 "
                + "              FROM assessment_request_scope_asset x WHERE x.asset_id = a.id)) "
                + "          AS measured "
                + "     FROM asset a JOIN asset_type ty ON ty.id = a.type_id "
                + "    WHERE ty.code = 'APPLICATION' AND a.lifecycle_state <> 'RETIRED' "
                + "      AND a.owning_node_id IN (SELECT descendant_id FROM org_closure "
                + "                                WHERE ancestor_id = ANY (?))) "
                + "SELECT '*', 'Everything you can reach', "
                + "       (SELECT count(*) FROM scored), "
                + "       coalesce((SELECT max(score) FROM scored), 0), "
                + "       coalesce((SELECT avg(score) FROM scored), 0), "
                + "       (SELECT assets FROM estate), (SELECT measured FROM estate), "
                + "       (SELECT count(*) FROM scored "
                + "         WHERE first_detected_at > now() - interval '90 days')");
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==============================================================================================

    private List<Posture> posture(Principal principal, String sql) throws SQLException {
        Set<UUID> scope = scopeOf(principal);
        if (scope.isEmpty()) {
            return List.of();
        }
        List<Posture> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            // The scope array binds once for the scored CTE and once for the estate CTE. Counted from
            // the statement rather than assumed: getting this wrong silently shifts every parameter
            // by one, and it has already cost three defects in this codebase.
            int parameters = statement.getParameterMetaData().getParameterCount();
            for (int i = 1; i <= parameters; i++) {
                statement.setArray(i, array(connection, scope));
            }
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(compose(r.getString(1), r.getString(2), r.getLong(3), r.getDouble(4),
                            r.getDouble(5), r.getLong(6), r.getLong(7), r.getLong(8)));
                }
            }
        }
        rows.sort((a, b) -> Integer.compare(b.posture(), a.posture()));
        return List.copyOf(rows);
    }

    /**
     * DOC-28 §10.2, composed:
     *
     * <pre>
     *   node_posture = 0.40 × severity_pressure
     *                + 0.20 × concentration
     *                + 0.25 × sla_health
     *                + 0.15 × coverage_penalty
     * </pre>
     *
     * <p><b>Every component is oriented so higher means worse</b>, including the two whose names read
     * the other way. {@code sla_health} enters as its complement — the share of open work
     * <i>outside</i> commitment — because a posture number that good process lowers and good coverage
     * raises would be a number no reader could hold in their head.
     */
    private static Posture compose(String id, String name, long findings, double worst, double mean,
            long assets, long measured, long withinCommitment) {
        // Size-independent by construction (§10.1): the worst finding, never the sum of them.
        double severityPressure = worst / 100.0;
        // How far the worst sits above the average — high means the risk is concentrated on few
        // assets, which is a different remediation problem from the same score spread thin.
        double concentration = worst <= 0 ? 0 : Math.min(1.0, (worst - mean) / 100.0 * 2);
        double outsideCommitment = findings == 0 ? 0
                : 1.0 - Math.min(1.0, (double) withinCommitment / findings);
        double assetCoverage = assets == 0 ? 0 : Math.min(1.0, (double) measured / assets);
        // Concealment made expensive rather than free — the direct inversion of §10.1's failure mode.
        double coveragePenalty = 1.0 - assetCoverage;

        int posture = (int) Math.round(100 * (0.40 * severityPressure + 0.20 * concentration
                + 0.25 * outsideCommitment + 0.15 * coveragePenalty));
        posture = Math.max(0, Math.min(100, posture));

        // DOC-28 §9. Asset coverage and factor coverage both bound this: three of six factors have no
        // input at all, which holds this deployment below HIGH however complete the estate becomes.
        Confidence confidence;
        if (assets == 0 || assetCoverage < 0.40) {
            confidence = Confidence.INSUFFICIENT;
        } else if (assetCoverage < 0.70 || FACTOR_COVERAGE < 0.70) {
            confidence = Confidence.LOW;
        } else if (assetCoverage < 0.90) {
            confidence = Confidence.MEDIUM;
        } else {
            confidence = Confidence.HIGH;
        }

        return new Posture(id, name, posture, band(posture), confidence, assetCoverage, assets,
                measured, findings, (int) Math.round(worst), severityPressure, concentration,
                1.0 - outsideCommitment, coveragePenalty, FACTOR_COVERAGE, MODEL_VERSION);
    }

    private static Set<UUID> scopeOf(Principal principal) {
        return principal == null ? Set.of() : principal.scopeNodeIds();
    }

    private static java.sql.Array array(Connection connection, Set<UUID> scope) throws SQLException {
        return connection.createArrayOf("uuid", scope.toArray(new UUID[0]));
    }

    private static int bounded(int limit, int max) {
        return Math.max(1, Math.min(max, limit));
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from "
                + "the authenticated caller and from nowhere else");
        return TenantConnections.open(dataSource, principal);
    }
}
