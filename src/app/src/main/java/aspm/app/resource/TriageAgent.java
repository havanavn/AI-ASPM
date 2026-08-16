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
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The agents, as deterministic rules. The seam a model plugs into. ADR-005, ADR-038, ADR-044.
 *
 * <h2>Why rules ship before a model, rather than instead of one</h2>
 *
 * <p>Everything that makes an AI capability safe here is outside the model: the ledger it writes to,
 * the grounding it must carry, the human who promotes it, the permission that human needs, the data
 * category that decides what it may read. All of that can be built, run and reviewed with rules
 * producing the suggestions — and until it is built, connecting a model would mean inventing those
 * controls under time pressure with output already arriving.
 *
 * <p>So each agent below produces real suggestions from real records today. When a provider is
 * configured and a capability enabled, the generation swaps and nothing else does: same ledger, same
 * grounding, same review, same permission. {@code model_identity} on every row says which produced
 * it, so a promoted suggestion is attributable forever.
 *
 * <h2>What none of them do</h2>
 *
 * <p>None writes to a finding. None computes a score, an SLA or a severity value — {@code
 * SEVERITY_REVIEW} names a band and its reason, it does not set one (ADR-038). None runs on view:
 * every one of these is invoked explicitly or on a schedule (PRD-AIC-056). And none reads the text of
 * a finding: the capability catalogue declares {@code AGGREGATE} for all of them, because routing,
 * duplicate-spotting and grade-questioning can be done from structure, and structure cannot carry a
 * prompt injection (risk surface 5).
 *
 * <p>{@code remediation.draft} is the exception that proves it — it cannot work without the finding's
 * text, it declares {@code RECORD}, and it is deliberately NOT implemented here. A rules engine cannot
 * write remediation guidance, and pretending otherwise with a template would produce advice that looks
 * authored and is not.
 */
public final class TriageAgent {

    /** What produced these suggestions. Never absent — the ledger's schema refuses a row without it. */
    public static final String IDENTITY = "deterministic-rules/v1";

    /** The result of one run, for the caller to report. */
    public record Run(String capability, int considered, int proposed, int skipped, String detail) {
    }

    private final DataSource dataSource;
    private final SuggestionLedger ledger;

    /**
     * Injection signals noticed during the run in progress. {@code PRD-AIC-038}.
     *
     * <p>Held here rather than in {@link Run}, which the interface renders: the count belongs in the
     * audit trail, where a run of them across days is the finding, and not on a button that would
     * teach an operator to treat "3 signals" as a normal number to click past.
     */
    private final ThreadLocal<Integer> injectionSignals = ThreadLocal.withInitial(() -> Integer.valueOf(0));
    /**
     * The bridge to a configured model, used where a sentence is wanted and refused everywhere else.
     *
     * <p>Every capability in this class keeps its rules. The model is asked to WRITE, never to decide:
     * with a provider configured the narrative reads better, and with none configured — or with a reply
     * that invented a figure — the deterministic sentence is used and the suggestion says so. That is
     * ADR-044's "every capability has a non-AI fallback" as a property of the code rather than a promise.
     */
    private final ModelNarrator narrator;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public TriageAgent(DataSource dataSource) {
        this.narrator = new ModelNarrator(dataSource);
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
        this.ledger = new SuggestionLedger(dataSource);
    }

    /**
     * Runs one capability.
     *
     * <p>Refuses a capability that is disabled rather than running it anyway — the enable switch is
     * how ADR-044's deferral is expressed, and an explicit run request is not consent to turn
     * something on.
     */
    public Run run(Principal principal, String code) throws SQLException {
        try (Connection connection = ledger.open(principal)) {
            Map<String, Object> capability = capability(connection, code);
            if (capability.isEmpty()) {
                return new Run(code, 0, 0, 0, "no such capability");
            }
            if (!Boolean.TRUE.equals(capability.get("enabled"))) {
                return new Run(code, 0, 0, 0,
                        "the capability is switched off; enable it in Configuration first");
            }
            int cap = (int) capability.get("max_per_run");
            injectionSignals.remove();
            // Read from the CATALOGUE, not passed in by the caller. It decides whether record content
            // may reach a model at all, and a value a caller could choose is not a control.
            String dataCategory = String.valueOf(capability.get("data_category"));
            connection.setAutoCommit(false);
            try {
                Run result = switch (code) {
                    case "ownership.routing" -> ownership(connection, cap);
                    case "duplicate.candidate" -> duplicates(connection, cap);
                    case "severity.review" -> severity(connection, cap);
                    case "coverage.caveat" -> coverage(connection, cap);
                    case "exception.brief" -> exceptions(connection, cap);
                    case "narrative.draft" -> narrative(principal, connection, cap, dataCategory);
                    case "remediation.draft" ->
                            remediation(principal, connection, cap, dataCategory);
                    default -> new Run(code, 0, 0, 0,
                            "this capability has no rules implementation and needs a model provider");
                };

                // *** WHAT RAN, OVER WHAT, AT WHOSE REQUEST. ***
                //
                // `ai.invoked` has been in the audit catalogue since the kernel was written and had
                // no writer, which made the AI surface the one part of the platform whose activity
                // left no trail. It matters here for two reasons that outlive the rules version:
                //
                //   the privacy one — DATA CATEGORY says whether record content was in scope for
                //   this run, and once a provider is configured that is the question "did anything
                //   of ours leave" reduces to. It is recorded per run rather than per capability,
                //   because the catalogue can be edited and this cannot.
                //
                //   the accountability one — PRD-AIC-056 forbids running a capability because
                //   somebody opened a page. An event per run is what makes that checkable rather
                //   than asserted: a run nobody asked for shows up as an event with an actor.
                //
                // The counts, never the content. What a suggestion says is in the ledger, which is
                // where a reviewer reads it; copying it here would put model output in the one store
                // that is meant to be tamper-evident and never rewritten.
                audit.event(connection, principal,
                        aspm.kernel.audit.contract.AuditEventType.AI_INVOKED, null, null,
                        java.util.Map.of("capability", code,
                                "model_identity", IDENTITY,
                                "data_category", dataCategory,
                                "considered", Integer.valueOf(result.considered()),
                                "proposed", Integer.valueOf(result.proposed()),
                                "outcome", result.detail(),
                                // PRD-AIC-038. Zero on every run that saw nothing, which is what
                                // makes a non-zero one worth looking at.
                                "injection_signals", injectionSignals.get()));
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Runs every ENABLED capability declared for one dashboard.
     *
     * <p>This is what the "Analyse with AI" button calls. Explicit by construction: a person presses
     * it about the screen in front of them, which is the invocation model {@code PRD-AIC-056} requires
     * and the one that keeps a privacy decision a decision rather than a side effect of browsing.
     *
     * <p>Disabled capabilities are skipped silently here rather than reported as refusals. On a single
     * capability the refusal is the answer somebody needs; across a dashboard it is noise, because the
     * normal state is that some are on and some are not.
     */
    public List<Run> runSurface(Principal principal, String surface) throws SQLException {
        List<Run> out = new ArrayList<>();
        for (var capability : ledger.capabilitiesFor(principal, surface)) {
            if (!capability.enabled()) {
                continue;
            }
            out.add(run(principal, capability.code()));
        }
        return List.copyOf(out);
    }

    // ==============================================================================================

    /**
     * Ownership routing — the estate's measured bottleneck.
     *
     * <p>240 of 248 open findings have no owner. The rule proposes the person who most recently closed
     * a finding on the same asset, because somebody who has fixed something here before is the least
     * bad guess a machine can make from structure alone. Where no such person exists it proposes
     * nothing rather than the nearest name: an unowned finding is a visible problem, and a wrongly
     * owned one is an invisible one.
     */
    private Run ownership(Connection connection, int cap) throws SQLException {
        int considered = 0;
        int proposed = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT f.id, f.title, a.display_name, prev.assignee, prev.closed, prev.example
                  FROM finding f
                  JOIN asset_finding_link l ON l.finding_id = f.id
                  JOIN asset a ON a.id = l.asset_id
                  JOIN LATERAL (
                        SELECT pf.assignee_id AS assignee, count(*) AS closed,
                               max(pf.title) AS example
                          FROM asset_finding_link pl
                          JOIN finding pf ON pf.id = pl.finding_id
                         WHERE pl.asset_id = l.asset_id AND pf.state <> 'OPEN'
                           AND pf.assignee_id IS NOT NULL
                         GROUP BY pf.assignee_id ORDER BY count(*) DESC LIMIT 1) prev ON true
                 WHERE f.state = 'OPEN' AND f.assignee_id IS NULL
                 ORDER BY f.first_detected_at
                 LIMIT ?
                """)) {
            statement.setInt(1, cap);
            List<Object[]> rows = new ArrayList<>();
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Object[] {r.getObject(1, UUID.class), r.getString(2),
                            r.getString(3), r.getObject(4, UUID.class), r.getLong(5),
                            r.getString(6)});
                }
            }
            for (Object[] row : rows) {
                considered += 1;
                var draft = new SuggestionLedger.Draft("OWNERSHIP_ROUTING", "FINDING",
                        (UUID) row[0],
                        "Route to whoever last fixed something on " + row[2],
                        "Nobody is assigned. On this asset, one person has closed "
                                + row[4] + " finding(s) already, including \\u201C" + row[5]
                                + "\\u201D.",
                        "Assign to principal " + row[3] + ", or pick somebody else — this is a "
                                + "suggestion from history, not from knowledge of who is free.",
                        // The grounding: the records the sentence rests on, so "why does it say that"
                        // has an answer that is not "the model said so".
                        List.of("finding:" + row[0], "asset:" + row[2],
                                "prior-closures:" + row[4], "candidate:" + row[3]),
                        // A band, never a percentage (ADR-038). "Somebody fixed things here before" is
                        // weak evidence and the band says so.
                        row[4] instanceof Long count && count >= 3 ? "MEDIUM" : "LOW");
                if (ledger.propose(connection, draft, IDENTITY, "ownership-routing/rules-1")) {
                    proposed += 1;
                }
            }
        }
        return new Run("ownership.routing", considered, proposed, considered - proposed,
                considered == 0
                        ? "no unassigned finding sits on an asset anybody has closed work on before"
                        : "proposed an owner from prior closures on the same asset");
    }

    /**
     * Duplicate candidates — proposed, never decided.
     *
     * <p>Deduplication is deterministic and stays that way (product principle 2): the fingerprint rules
     * own it. What a rules agent can usefully do is surface pairs the fingerprint did NOT join — same
     * asset, same title, different tool — which is exactly where two scanners describe one weakness in
     * two vocabularies. A person confirms; the confirmation is what feeds the deterministic side.
     */
    private Run duplicates(Connection connection, int cap) throws SQLException {
        int proposed = 0;
        int considered = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT f.id, f.title, f.source_tool, other.id, other.source_tool, a.display_name
                  FROM finding f
                  JOIN asset_finding_link l ON l.finding_id = f.id
                  JOIN asset a ON a.id = l.asset_id
                  JOIN asset_finding_link ol ON ol.asset_id = l.asset_id
                  JOIN finding other ON other.id = ol.finding_id
                 WHERE f.state = 'OPEN' AND other.state = 'OPEN'
                   AND other.id > f.id
                   AND other.source_tool <> f.source_tool
                   AND lower(f.title) = lower(other.title)
                   -- Different fingerprints: the deterministic pipeline already joined the ones it
                   -- could, and re-proposing those would be noise in front of a reviewer.
                   AND f.fingerprint_digest IS DISTINCT FROM other.fingerprint_digest
                 ORDER BY f.first_detected_at
                 LIMIT ?
                """)) {
            statement.setInt(1, cap);
            List<Object[]> rows = new ArrayList<>();
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Object[] {r.getObject(1, UUID.class), r.getString(2),
                            r.getString(3), r.getObject(4, UUID.class), r.getString(5),
                            r.getString(6)});
                }
            }
            for (Object[] row : rows) {
                considered += 1;
                var draft = new SuggestionLedger.Draft("DUPLICATE_CANDIDATE", "FINDING",
                        (UUID) row[0],
                        "Possibly the same weakness as a " + row[4] + " finding",
                        "Same title and same asset (" + row[5] + "), reported by " + row[2]
                                + " and by " + row[4] + ", with different fingerprints — so the "
                                + "deduplication rules did not join them.",
                        "Confirm whether these are one weakness. Confirmation feeds the fingerprint "
                                + "rules; this agent does not merge anything.",
                        List.of("finding:" + row[0], "finding:" + row[3], "asset:" + row[5],
                                "tools:" + row[2] + "," + row[4]),
                        "MEDIUM");
                if (ledger.propose(connection, draft, IDENTITY, "duplicate-candidate/rules-1")) {
                    proposed += 1;
                }
            }
        }
        return new Run("duplicate.candidate", considered, proposed, considered - proposed,
                considered == 0 ? "no cross-tool title match on a shared asset"
                        : "same title, same asset, different tools, different fingerprints");
    }

    /**
     * Severity review — questions a grade, never sets one.
     *
     * <p>Flags open findings the estate treats as low or medium that sit on an internet-facing asset.
     * That is not a claim the grade is wrong; it is a claim the grade was made without the exposure in
     * view, which is the commonest way a severity comes to be misleading. The suggestion names the
     * band it thinks is worth considering and why — it never writes a number (ADR-038).
     */
    private Run severity(Connection connection, int cap) throws SQLException {
        int proposed = 0;
        int considered = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT f.id, f.title, sl.code, a.display_name
                  FROM finding f
                  JOIN severity_level sl
                       ON sl.id = coalesce(f.effective_severity_id, f.reported_severity_id)
                  JOIN asset_finding_link l ON l.finding_id = f.id
                  JOIN asset a ON a.id = l.asset_id
                 WHERE f.state = 'OPEN'
                   AND sl.ordinal >= 3
                   AND (a.exposure_declared = 'INTERNET_PUBLIC'
                        OR a.exposure_observed = 'INTERNET_PUBLIC')
                 ORDER BY f.first_detected_at
                 LIMIT ?
                """)) {
            statement.setInt(1, cap);
            List<Object[]> rows = new ArrayList<>();
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Object[] {r.getObject(1, UUID.class), r.getString(2),
                            r.getString(3), r.getString(4)});
                }
            }
            for (Object[] row : rows) {
                considered += 1;
                var draft = new SuggestionLedger.Draft("SEVERITY_REVIEW", "FINDING",
                        (UUID) row[0],
                        "Graded " + row[2] + " on an internet-facing asset",
                        "The asset " + row[3] + " is reachable from the internet, and this finding "
                                + "is graded " + row[2] + ". The grade may have been set without "
                                + "the exposure in view.",
                        "Review the grade. This does not propose a value — only that somebody who "
                                + "can see both the weakness and the exposure looks again.",
                        List.of("finding:" + row[0], "asset:" + row[3], "current-band:" + row[2],
                                "exposure:INTERNET_PUBLIC"),
                        "LOW");
                if (ledger.propose(connection, draft, IDENTITY, "severity-review/rules-1")) {
                    proposed += 1;
                }
            }
        }
        return new Run("severity.review", considered, proposed, considered - proposed,
                considered == 0 ? "no low or medium finding sits on an internet-facing asset"
                        : "low or medium grade on a publicly reachable asset");
    }

    /**
     * Coverage — the observation an executive surface is most dangerous without.
     *
     * <p>States, per organization, how much of what the figures are drawn from was actually measured.
     * "97 critical and high" beside "9 of 17 applications have never been reviewed" is a different
     * sentence from "97 critical and high", and only one of them is honest. The suggestion is raised
     * against the ORG NODE rather than any finding, because the gap belongs to the organization and
     * not to any record inside it.
     *
     * <p>Deliberately produces nothing where coverage is complete. An observation that fires always
     * is an observation people learn to skip.
     */
    private Run coverage(Connection connection, int cap) throws SQLException {
        int considered = 0;
        int proposed = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT n.id, n.name,
                       count(*) FILTER (WHERE c.full_review_status = 'NEVER'),
                       count(*),
                       (SELECT count(*) FROM asset a2
                          JOIN asset_type t2 ON t2.id = a2.type_id AND t2.code <> 'APPLICATION'
                          LEFT JOIN sbom_coverage_state cs ON cs.asset_id = a2.id
                         WHERE a2.lifecycle_state <> 'RETIRED'
                           AND a2.owning_node_id IN (SELECT descendant_id FROM org_closure
                                                      WHERE ancestor_id = n.id)
                           AND cs.latest_snapshot_id IS NULL),
                       -- The freshness half. Everything above counts what was never measured; these
                       -- two count what WAS measured and is no longer current, which reads as
                       -- coverage on every dashboard and is not.
                       --
                       -- OVERDUE is the tenant's own definition, not a number invented here: the
                       -- view derives it from full_review_policy.interval_months per criticality
                       -- tier, so a tenant that lengthens its cadence changes this figure and the
                       -- sentence stays true.
                       count(*) FILTER (WHERE c.full_review_status = 'OVERDUE'),
                       (SELECT count(*) FROM asset a3
                          JOIN asset_type t3 ON t3.id = a3.type_id AND t3.code <> 'APPLICATION'
                          JOIN sbom_coverage_state cs3 ON cs3.asset_id = a3.id
                         WHERE a3.lifecycle_state <> 'RETIRED'
                           AND a3.owning_node_id IN (SELECT descendant_id FROM org_closure
                                                      WHERE ancestor_id = n.id)
                           AND cs3.latest_snapshot_at
                                 < now() - make_interval(days => cs3.freshness_threshold_days)),
                       -- The threshold itself, so the sentence can say what "stale" means here
                       -- rather than asserting staleness against a number nobody can see.
                       (SELECT max(cs4.freshness_threshold_days) FROM asset a4
                          JOIN sbom_coverage_state cs4 ON cs4.asset_id = a4.id
                         WHERE a4.owning_node_id IN (SELECT descendant_id FROM org_closure
                                                      WHERE ancestor_id = n.id))
                  FROM org_node n
                  JOIN asset a ON a.owning_node_id IN (SELECT descendant_id FROM org_closure
                                                        WHERE ancestor_id = n.id)
                  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION'
                  JOIN application_review_cadence c ON c.asset_id = a.id
                 WHERE a.lifecycle_state <> 'RETIRED' AND n.parent_id IS NULL
                 GROUP BY n.id, n.name
                -- Fires on never-measured OR gone-stale. It used to fire on the first alone, so an
                -- organization that had reviewed everything two years ago produced no caveat at all
                -- and its figures read as fully covered — the exact blind spot this capability
                -- exists to name (PP-1: every metric carries its coverage AND its freshness).
                HAVING count(*) FILTER (WHERE c.full_review_status = 'NEVER') > 0
                    OR count(*) FILTER (WHERE c.full_review_status = 'OVERDUE') > 0
                    OR (SELECT count(*) FROM asset a5
                          JOIN sbom_coverage_state cs5 ON cs5.asset_id = a5.id
                         WHERE a5.lifecycle_state <> 'RETIRED'
                           AND a5.owning_node_id IN (SELECT descendant_id FROM org_closure
                                                      WHERE ancestor_id = n.id)
                           AND cs5.latest_snapshot_at
                                 < now() - make_interval(days => cs5.freshness_threshold_days)) > 0
                 ORDER BY 3 DESC
                 LIMIT ?
                """)) {
            statement.setInt(1, cap);
            List<Object[]> rows = new ArrayList<>();
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Object[] {r.getObject(1, UUID.class), r.getString(2),
                            r.getLong(3), r.getLong(4), r.getLong(5),
                            r.getLong(6), r.getLong(7), r.getLong(8)});
                }
            }
            for (Object[] row : rows) {
                considered += 1;
                long never = (Long) row[2];
                long applications = (Long) row[3];
                long partsWithoutSbom = (Long) row[4];
                long overdue = (Long) row[5];
                long staleSbom = (Long) row[6];
                long thresholdDays = row[7] == null ? 0L : (Long) row[7];

                // Two sentences, and they are different problems with different answers: never
                // measured is work that has not started, out of date is work that has to happen
                // again. Folding them into one number would let an organization that measures
                // everything once look identical to one that keeps measuring.
                // Each clause appears only where its figure is not zero. "0 application(s) are past
                // the review interval" is noise, and a sentence carrying a zero is how a reader
                // learns to skim the whole thing — which costs the sentences that are not zero.
                List<String> unmeasured = new ArrayList<>();
                if (never > 0) {
                    unmeasured.add(never + " application(s) have never had a full review");
                }
                if (partsWithoutSbom > 0) {
                    unmeasured.add(partsWithoutSbom
                            + " part(s) have never submitted a bill of materials");
                }
                List<String> expired = new ArrayList<>();
                if (overdue > 0) {
                    expired.add(overdue + " application(s) are past the review interval this tenant "
                            + "set for their criticality tier");
                }
                if (staleSbom > 0) {
                    expired.add(staleSbom + " part(s) last submitted a bill of materials more than "
                            + thresholdDays + " days ago");
                }

                StringBuilder detail = new StringBuilder();
                if (!unmeasured.isEmpty()) {
                    detail.append(String.join(", and ", unmeasured))
                            .append(". Anything reported as clean for those was not measured — it "
                                    + "was not looked at.");
                }
                if (!expired.isEmpty()) {
                    if (detail.length() > 0) {
                        detail.append(' ');
                    }
                    detail.append(String.join(", and ", expired))
                            .append(". Those were measured, and what they were measured against has "
                                    + "moved since.");
                }

                var draft = new SuggestionLedger.Draft("COVERAGE_CAVEAT", "ORG_NODE",
                        (UUID) row[0],
                        "Figures for " + row[1] + " cover " + (applications - never)
                                + " of " + applications + " applications"
                                + (overdue + staleSbom > 0
                                        ? ", and " + (overdue + staleSbom) + " measurement(s) have "
                                                + "gone out of date"
                                        : ""),
                        detail.toString(),
                        "Read every figure for this organization against that denominator. A count "
                                + "of weaknesses is a count of what was found, not of what is there — "
                                + "and a measurement that has expired is not a measurement of today.",
                        List.of("org:" + row[1], "applications:" + applications,
                                "never-reviewed:" + never, "parts-without-sbom:" + partsWithoutSbom,
                                "reviews-overdue:" + overdue, "sbom-stale:" + staleSbom,
                                "sbom-threshold-days:" + thresholdDays),
                        // Not a judgement. This is arithmetic over recorded facts, and the band says
                        // so rather than implying a model weighed anything.
                        "HIGH");
                if (ledger.propose(connection, draft, IDENTITY, "coverage-caveat/rules-1")) {
                    proposed += 1;
                }
            }
        }
        return new Run("coverage.caveat", considered, proposed, considered - proposed,
                considered == 0
                        ? "every organization's estate is measured and every measurement is current"
                        : "organizations whose figures rest on an estate that is partly unmeasured "
                                + "or partly out of date");
    }

    /**
     * The brief for a decision that is waiting on somebody.
     *
     * <p>A risk exception in REQUESTED is a person waiting for an answer. Approving one is the most
     * consequential thing an executive does on this platform — it leaves a known weakness in place —
     * and it was visible on no surface at all. This assembles what the decision needs: what it covers,
     * how exposed that is, and when it runs out.
     *
     * <p>It proposes NO decision. Promoting it records that somebody read the brief; approving the
     * exception itself remains the exception's own write path, with its own step-up.
     */
    private Run exceptions(Connection connection, int cap) throws SQLException {
        int considered = 0;
        int proposed = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.id, coalesce(f.title, '(subject not a finding)'),
                       coalesce(sl.code, 'UNRATED'),
                       to_char(e.expires_at, 'YYYY-MM-DD'),
                       coalesce(p.display_name, p.username, 'somebody no longer recorded'),
                       EXISTS (SELECT 1 FROM asset_finding_link l
                                 JOIN asset a ON a.id = l.asset_id
                                WHERE l.finding_id = f.id
                                  AND (a.exposure_declared = 'INTERNET_PUBLIC'
                                       OR a.exposure_observed = 'INTERNET_PUBLIC')),
                       extract(day FROM e.expires_at - now())::int
                  FROM risk_exception e
                  LEFT JOIN finding f ON f.id = e.subject_id AND e.subject_kind = 'FINDING'
                  LEFT JOIN severity_level sl
                       ON sl.id = coalesce(f.effective_severity_id, f.reported_severity_id)
                  LEFT JOIN principal p ON p.id = e.requested_by
                 WHERE e.state = 'REQUESTED'
                 ORDER BY e.requested_at
                 LIMIT ?
                """)) {
            statement.setInt(1, cap);
            List<Object[]> rows = new ArrayList<>();
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Object[] {r.getObject(1, UUID.class), r.getString(2),
                            r.getString(3), r.getString(4), r.getString(5), r.getBoolean(6),
                            r.getObject(7)});
                }
            }
            for (Object[] row : rows) {
                considered += 1;
                boolean exposed = (Boolean) row[5];
                var draft = new SuggestionLedger.Draft("EXCEPTION_BRIEF", "RISK_EXCEPTION",
                        (UUID) row[0],
                        "A risk acceptance is waiting on a decision",
                        row[4] + " asked to accept \u201C" + row[1] + "\u201D, graded "
                                + row[2] + (exposed
                                        ? ", on an asset reachable from the internet"
                                        : ", on an asset not recorded as internet-facing")
                                + ". It would run until " + row[3]
                                + (row[6] == null ? "" : " (" + row[6] + " days).")
                                + " Accepting leaves the weakness in place for that period.",
                        "Decide on the exception itself — this brief records only that somebody read "
                                + "it. Approval is a separate, step-up action on the exception.",
                        List.of("exception:" + row[0], "finding:" + row[1], "severity:" + row[2],
                                "expires:" + row[3],
                                "internet-facing:" + (exposed ? "yes" : "no")),
                        // Exposure raises the stakes, not the confidence. The band describes how
                        // firmly the facts are known, and they are known exactly.
                        "HIGH");
                if (ledger.propose(connection, draft, IDENTITY, "exception-brief/rules-1")) {
                    proposed += 1;
                }
            }
        }
        return new Run("exception.brief", considered, proposed, considered - proposed,
                considered == 0 ? "no risk acceptance is waiting for a decision"
                        : "risk acceptances awaiting an answer");
    }

    /**
     * Remediation guidance — the one capability that cannot be written by rules, and the first that
     * sends record content anywhere.
     *
     * <h2>Why there is no deterministic version of this, and why that is the honest answer</h2>
     *
     * <p>Every other agent here composes recorded facts into a sentence. Remediation advice is not a
     * composition of facts: it is judgement about a specific weakness in a specific system, and a
     * template that produced "sanitise the input and apply the vendor patch" for everything would
     * read as authored advice while being a form letter. So this capability proposes nothing when no
     * model answers. ADR-044 requires a non-AI fallback for every capability; the fallback here is
     * <b>silence, stated</b>, which is a fallback in the sense that matters — the platform still
     * works and nobody is misled.
     *
     * <h2>What leaves, and the three things that never do</h2>
     *
     * <p>This is the first capability declared {@code RECORD}, so a finding's title and description
     * reach a provider the tenant configured. Three exclusions are structural rather than
     * configurable:
     *
     * <ul>
     *   <li><b>Findings of class SECRET are never sent.</b> Their content <em>is</em> a recovered
     *       credential — the third risk surface in CLAUDE.md is that this platform concentrates
     *       secrets — and "the description of a secret finding" is the secret. The predicate is in
     *       the query, so no configuration and no argument can include one.
     *   <li><b>The proof of concept is never sent.</b> It is working exploit material against a
     *       system that is still vulnerable (risk surface 4). A model can write remediation guidance
     *       from what the weakness IS without being handed the payload that triggers it.
     *   <li><b>Nothing is sent when the tenant has not said it may.</b> {@code ModelNarrator} checks
     *       the category and the provider's own flag; this method chooses what to offer, and that
     *       check decides what goes.
     * </ul>
     *
     * <p>What remains is a title and a description written by a scanner or an assessor — which is
     * attacker-authored text, which is why it goes through the fenced channel with the injection
     * corpus written against it, and why a reply that invents a figure or contradicts the record is
     * refused rather than shown.
     */
    private Run remediation(Principal principal, Connection connection, int cap, String dataCategory)
            throws SQLException {
        int considered = 0;
        int proposed = 0;
        int withheld = 0;
        String lastRefusal = null;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT f.id, f.title, coalesce(f.description, ''), f.finding_class,
                       -- The tenant's own word for the severity, from label_i18n. There is no
                       -- `name` column: severity scales are tenant vocabulary (ADR-027) and the
                       -- label is translatable, which is why it is JSON.
                       coalesce(sl.label_i18n->>'en', sl.code, 'ungraded'), sl.ordinal,
                       coalesce(string_agg(DISTINCT a.display_name, ', '), 'no asset recorded'),
                       bool_or(a.exposure_declared = 'INTERNET_PUBLIC'
                               OR a.exposure_observed = 'INTERNET_PUBLIC'),
                       coalesce(f.primary_cwe_id, 'none recorded'),
                       extract(day FROM now() - f.first_detected_at)::int
                  FROM finding f
                  LEFT JOIN severity_level sl
                       ON sl.id = coalesce(f.effective_severity_id, f.reported_severity_id)
                  LEFT JOIN asset_finding_link l ON l.finding_id = f.id
                  LEFT JOIN asset a ON a.id = l.asset_id
                 WHERE f.state = 'OPEN'
                   -- The exclusion that cannot be configured away. See the class note above.
                   AND f.finding_class <> 'SECRET'
                   -- Something to work from. A description of two words produces advice about
                   -- nothing, and sending it spends egress on a request whose answer is worthless.
                   AND length(coalesce(f.description, '')) >= 40
                 GROUP BY f.id, f.title, f.description, f.finding_class, sl.label_i18n, sl.code,
                          sl.ordinal, f.primary_cwe_id, f.first_detected_at
                 ORDER BY sl.ordinal NULLS LAST, f.first_detected_at
                 LIMIT ?
                """)) {
            statement.setInt(1, cap);
            List<Object[]> rows = new ArrayList<>();
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Object[] {r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                            r.getString(4), r.getString(5), r.getString(7), r.getBoolean(8),
                            r.getString(9), r.getObject(10)});
                }
            }
            for (Object[] row : rows) {
                considered += 1;
                boolean exposed = (Boolean) row[6];

                // FACTS: platform-composed, and the only place a figure may come from. Note what is
                // NOT here — no severity score, no percentage, no invented deadline.
                List<String> facts = ModelNarrator.facts(
                        "severity: " + row[4],
                        "finding class: " + row[3],
                        "affected: " + row[5],
                        "internet-facing: " + (exposed ? "yes" : "no"),
                        "primary CWE: " + row[7],
                        "days since first detected: " + row[8]);

                // The untrusted half. Title and description only — the proof of concept is withheld
                // deliberately and the class note says why.
                Map<String, String> untrusted = new LinkedHashMap<>();
                untrusted.put("finding_title", String.valueOf(row[1]));
                untrusted.put("finding_description", String.valueOf(row[2]));
                injectionSignals.set(Integer.valueOf(injectionSignals.get().intValue()
                        + ModelNarrator.injectionSignals(untrusted)));

                Object narrated = narrator.narrate(principal,
                        "Write remediation guidance for this weakness: what to change, and what to "
                        + "check afterwards. Address the engineer who owns the code.",
                        facts, untrusted, dataCategory);
                if (!(narrated instanceof ModelNarrator.Narration written)) {
                    // No template, no partial credit. The refusal is reported so an operator can see
                    // WHY nothing appeared — no provider, an invented figure, a contradiction — and
                    // each of those is a different thing to do about it.
                    withheld += 1;
                    if (narrated instanceof ModelNarrator.Refusal refused) {
                        lastRefusal = refused.code();
                    }
                    continue;
                }

                var draft = new SuggestionLedger.Draft("REMEDIATION_DRAFT", "FINDING",
                        (UUID) row[0],
                        "Suggested remediation for a " + row[4] + " finding",
                        written.text(),
                        "This is a draft for the owning engineer to judge, not an instruction. "
                                + "Nothing about the finding has changed; accepting records that you "
                                + "read it and found it sound.",
                        List.of("finding:" + row[0], "severity:" + row[4],
                                "class:" + row[3], "asset:" + row[5],
                                "cwe:" + row[7], "internet-facing:" + (exposed ? "yes" : "no"),
                                // The provenance is grounding too: a reader deciding how much weight
                                // to give the paragraph needs to know a model wrote it.
                                "written-by:" + written.modelIdentity()),
                        // Never HIGH. A drafted remediation is the least verifiable thing this
                        // platform produces, and a band that said otherwise would be the model
                        // rating its own work.
                        "LOW");
                if (ledger.propose(connection, draft, written.modelIdentity(),
                        written.promptVersion())) {
                    proposed += 1;
                }
            }
        }
        String outcome;
        if (considered == 0) {
            outcome = "no open finding has a description long enough to work from, and secret "
                    + "findings are never sent";
        } else if (proposed == 0) {
            outcome = "nothing was written for " + considered + " finding(s)"
                    + (lastRefusal == null ? "" : " (" + lastRefusal + ")")
                    + ". This capability has no rules version on purpose: a templated remediation "
                    + "reads as advice and is not";
        } else {
            outcome = "drafted for " + proposed + " of " + considered + " finding(s); "
                    + withheld + " withheld"
                    + (lastRefusal == null ? "" : " (" + lastRefusal + ")");
        }
        return new Run("remediation.draft", considered, proposed, considered - proposed, outcome);
    }

    /**
     * The narrative — and the one rule that makes it safe.
     *
     * <p>It REFUSES to write anything for an organization whose coverage caveat has not been raised.
     * A fluent paragraph over a partly unmeasured estate is the single most dangerous output this
     * platform can produce: it reads as an assessment and it is a description of what happened to be
     * looked at. So the caveat is a precondition, enforced here rather than left to a prompt.
     *
     * <p>Every figure it states comes from a query and is listed in the grounding (ADR-038). When a
     * provider replaces these rules, that constraint does not relax — the model may write the prose
     * around these numbers and may not produce one of its own.
     */
    private Run narrative(Principal principal, Connection connection, int cap, String dataCategory)
            throws SQLException {
        int wroteByModel = 0;
        int withheldForCoverage = 0;
        int alreadyHasOne = 0;
        String lastRefusal = null;
        int considered = 0;
        int proposed = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT n.id, n.name,
                       count(*) FILTER (WHERE f.state = 'OPEN'),
                       count(*) FILTER (WHERE f.state = 'OPEN' AND sl.ordinal <= 2),
                       count(*) FILTER (WHERE f.closed_at > now() - interval '30 days'),
                       EXISTS (SELECT 1 FROM ai_suggestion s
                                WHERE s.suggestion_kind = 'COVERAGE_CAVEAT'
                                  AND s.subject_id = n.id),
                       -- Already has one, so nothing new can be proposed for it. Excluded HERE rather
                       -- than discovered when the ledger deduplicates, because between those two points
                       -- the model gets called: the first version narrated six organizations and threw
                       -- five of the results away, which is five needless egresses of a tenant's figures
                       -- to a third party. A cheap query beats an expensive apology.
                       EXISTS (SELECT 1 FROM ai_suggestion s2
                                WHERE s2.suggestion_kind = 'NARRATIVE_DRAFT'
                                  AND s2.subject_id = n.id)
                  FROM org_node n
                  JOIN finding f ON f.scope_node_id IN (SELECT descendant_id FROM org_closure
                                                         WHERE ancestor_id = n.id)
                  LEFT JOIN severity_level sl
                       ON sl.id = coalesce(f.effective_severity_id, f.reported_severity_id)
                 WHERE n.parent_id IS NULL
                 GROUP BY n.id, n.name
                 ORDER BY 4 DESC
                 LIMIT ?
                """)) {
            statement.setInt(1, cap);
            List<Object[]> rows = new ArrayList<>();
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Object[] {r.getObject(1, UUID.class), r.getString(2),
                            r.getLong(3), r.getLong(4), r.getLong(5), r.getBoolean(6),
                            Boolean.valueOf(r.getBoolean(7))});
                }
            }
            for (Object[] row : rows) {
                considered += 1;
                // Counted for every organization CONSIDERED, before any skip.
                //
                // The content is what carries the attempt, and it is in the platform whether or not
                // this run goes on to ground a suggestion in it. Counting only the ones that reached
                // a model would mean a run that skipped everything reported zero signals over content
                // full of them — which is the shape the first version had, and it read as reassuring.
                injectionSignals.set(Integer.valueOf(injectionSignals.get().intValue()
                        + ModelNarrator.injectionSignals(
                                Map.of("organization_name", String.valueOf(row[1])))));
                if ((Boolean) row[6]) {
                    // Nothing to add, and nothing sent anywhere. Counted as skipped, not withheld: the
                    // organization is not missing a coverage note, it already has a narrative waiting.
                    alreadyHasOne += 1;
                    continue;
                }
                if (!(Boolean) row[5]) {
                    // Withheld, not softened. See the class note: a narrative whose coverage is
                    // unstated is the output this platform must not produce.
                    withheldForCoverage += 1;
                    continue;
                }
                // The deterministic sentence, always built. It is what gets used when there is no
                // provider, when the provider is unreachable, and when the reply invents a figure — so
                // it is not a fallback bolted on, it is the baseline the model has to improve on.
                String detail = row[2] + " findings are open in " + row[1] + ", " + row[3]
                        + " of them critical or high. " + row[4]
                        + " were closed in the last 30 days. Read all of these against the "
                        + "coverage note raised for this organization.";
                String identity = IDENTITY;
                String promptVersion = "narrative-draft/rules-1";

                // The facts are the ONLY material the model may draw a number from, and they are the
                // same numbers the sentence above states. Nothing about a finding's text is sent: this
                // capability is declared AGGREGATE, and ModelNarrator would drop record content anyway.
                List<String> facts = ModelNarrator.facts(
                        "open findings: " + row[2],
                        "critical or high among them: " + row[3],
                        "closed in the last 30 days: " + row[4],
                        "a coverage caveat exists for this organization and must be read with it");
                // The NAME goes through the untrusted channel, not into the facts.
                //
                // It reads like platform data and is not: an organization is named by whoever
                // administers the tenant, but the same channel carries asset names created by an SBOM
                // push from a pipeline. A name interpolated into a FACTS bullet is attacker-authored
                // text in instruction position, which is what PRD-AIC-037 forbids — and it was here.
                Map<String, String> untrusted =
                        Map.of("organization_name", String.valueOf(row[1]));
                Object narrated = narrator.narrate(principal,
                        "Summarise where this organization stands on open security weaknesses for the "
                        + "person who owns it.", facts, untrusted, dataCategory);
                if (narrated instanceof ModelNarrator.Narration written) {
                    detail = written.text();
                    identity = written.modelIdentity();
                    promptVersion = written.promptVersion();
                    wroteByModel += 1;
                } else if (narrated instanceof ModelNarrator.Refusal refused) {
                    lastRefusal = refused.code();
                }

                var draft = new SuggestionLedger.Draft("NARRATIVE_DRAFT", "ORG_NODE",
                        (UUID) row[0],
                        row[1] + ": " + row[3] + " serious weaknesses open",
                        detail,
                        "Every figure here comes from a query and is listed below; none was "
                                + "estimated.",
                        List.of("org:" + row[1], "open:" + row[2], "serious:" + row[3],
                                "closed-30d:" + row[4], "requires:COVERAGE_CAVEAT"),
                        "HIGH");
                // The identity is the MODEL's when the model wrote it. A suggestion that read
                // "deterministic-rules/v1" over a sentence a model produced would make the ledger's
                // provenance a decoration, and provenance is the only reason to keep a ledger.
                if (ledger.propose(connection, draft, identity, promptVersion)) {
                    proposed += 1;
                }
            }
        }
        // The detail says WHO wrote these and, when a model did not, why. Degrading quietly to rules
        // would leave an operator believing a configured provider was being used (PP-9).
        String provenance = wroteByModel > 0
                ? wroteByModel + " of " + proposed + " written by the configured model"
                : lastRefusal == null
                        ? "written from the rules"
                        : "written from the rules — the model was not used (" + lastRefusal + ")";
        // WHY nothing was proposed, distinguished. The message used to say "no coverage note" whenever
        // proposed was zero, and that is only one of two reasons — the other is that every candidate
        // already HAS a narrative and the ledger deduplicated. An operator told to raise a coverage note
        // that already exists goes looking for a problem that is not there, so the two are separated by
        // counting the withholding rather than inferring it.
        String outcome;
        if (proposed > 0) {
            outcome = "composed from recorded figures, bound to the coverage note; " + provenance;
        } else if (considered == 0) {
            outcome = "no organization has open findings to describe";
        } else if (withheldForCoverage > 0) {
            outcome = "withheld for " + withheldForCoverage + " of " + considered + ": no coverage note "
                    + "has been raised, and a narrative without one describes only what was looked at";
        } else if (alreadyHasOne > 0) {
            outcome = alreadyHasOne + " organization(s) already have a narrative waiting; decide those "
                    + "before asking for more. No model was called for them.";
        } else {
            outcome = "nothing new to describe";
        }
        return new Run("narrative.draft", considered, proposed, considered - proposed, outcome);
    }

    private static Map<String, Object> capability(Connection connection, String code)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT enabled, max_per_run, data_category FROM ai_capability WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    return Map.of();
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("enabled", Boolean.valueOf(r.getBoolean(1)));
                out.put("max_per_run", Integer.valueOf(r.getInt(2)));
                out.put("data_category", r.getString(3));
                return out;
            }
        }
    }
}
