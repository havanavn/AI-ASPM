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
import javax.sql.DataSource;

/**
 * Whether each CI integration is actually working — {@code PRD-SBM-024}.
 *
 * <h2>Why this exists</h2>
 *
 * <p>ADR-023 makes the SBOM push API the only automated ingestion path in v1 and states the consequence
 * in its own text: the endpoint is a single point of failure for the entire SCA capability, "which is
 * why submission health must be visible per credential". An integration that has been failing for weeks
 * is the mechanism by which coverage gaps form, and it must fail loudly (PP-9) to the party who can fix
 * it rather than into a server log nobody reads.
 *
 * <h2>What it could answer before</h2>
 *
 * <p>One column, {@code last_used_at}, set whenever a signature verified. It could not distinguish a
 * pipeline that submitted a bill of materials from one whose document was rejected forty times, and it
 * said nothing whatever about a pipeline whose secret had gone stale — those never get far enough to
 * write it. All three of the commonest ways an integration dies looked the same as "nobody has pushed
 * lately", which product principle 1 forbids: not-measured must never resemble measured-and-clean.
 *
 * <h2>Silence is reported as silence, not as health</h2>
 *
 * <p>A credential with no recorded outcome reports exactly that. It does not report zero failures,
 * because zero failures is a measurement and this is the absence of one — and the two would lead an
 * administrator to opposite conclusions. The counters were deliberately not backfilled: every service
 * credential in this deployment acts as the same principal, so attributing the existing snapshots per
 * principal would have credited ten integrations with one pipeline's work.
 *
 * <h2>The estate consequence sits beside the credential</h2>
 *
 * <p>A broken integration matters because of what stops being measured. So each row carries how many
 * artifacts are now stale or have never had a bill of materials at all — the sentence "this pipeline has
 * been failing for eleven days and forty-two artifacts have no current inventory" is what makes somebody
 * act, and neither half of it does that alone.
 */
public final class SubmissionHealth {

    /** Reading submission health. Not restricted: knowing an integration is broken helps nobody attack. */
    public static final String READ = "sbm.coverage.read";

    /**
     * One integration's health.
     *
     * @param outcomeRecorded false when nothing has been recorded since the health columns existed —
     *     which is reported as its own state rather than as a clean bill
     */
    public record Row(String id, String label, String keyId, String actsAs, String scope,
            List<String> permissions, String expiresAt, boolean expired, String revokedAt,
            String lastUsedAt, String lastSuccessAt, String lastFailureAt, String lastFailureReason,
            int successCount, int failureCount, int consecutiveFailures, boolean outcomeRecorded,
            Integer daysSinceSuccess, String verdict, String advice) {
    }

    /** What the estate looks like as a result, for the panel heading. */
    public record Estate(int liveSubmitters, int broken, int silent, int neverRecorded,
            int artifactsWithoutSbom, int artifactsStale) {
    }

    private final DataSource dataSource;

    public SubmissionHealth(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ==============================================================================================
    // The write side — called from the submission endpoint
    // ==============================================================================================

    /**
     * Records an accepted submission and clears the consecutive-failure run.
     *
     * <p>Silent when the caller is not a service credential: the manual upload path is a person doing it
     * by hand, and a person is not an integration whose health decays. Silent too when the recording
     * fails — an ingestion must never be lost because a counter could not be written.
     */
    public void recordSuccess(Principal principal) {
        update(principal, """
                UPDATE service_credential
                   SET success_count = success_count + 1,
                       last_success_at = now(),
                       consecutive_failures = 0
                 WHERE id = ?
                """, null);
    }

    /**
     * Records a refused submission.
     *
     * @param reason platform-authored text. Never a string taken from the request body: a CI caller
     *     supplies that content, and this field is rendered on an administrator's screen.
     */
    public void recordFailure(Principal principal, String reason) {
        update(principal, """
                UPDATE service_credential
                   SET failure_count = failure_count + 1,
                       consecutive_failures = consecutive_failures + 1,
                       last_failure_at = now(),
                       last_failure_reason = ?
                 WHERE id = ?
                """, reason == null ? "no reason recorded"
                        : reason.substring(0, Math.min(500, reason.length())));
    }

    private void update(Principal principal, String sql, String reason) {
        if (principal == null || principal.credential() == null) {
            return;
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (reason != null) {
                statement.setString(index++, reason);
            }
            statement.setObject(index, principal.credential());
            statement.executeUpdate();
        } catch (SQLException e) {
            // Deliberately swallowed, and deliberately logged. The bill of materials is the record; a
            // failed counter write must not take it down with it. Losing the counter silently would be
            // the same defect one level up, so it is logged where an operator can find it.
            System.getLogger("aspm.sbom").log(System.Logger.Level.WARNING,
                    "submission health could not be recorded for credential "
                    + principal.credential(), e);
        }
    }

    // ==============================================================================================
    // The read side — the dashboard
    // ==============================================================================================

    /** Every credential that may submit, worst first. */
    public List<Row> rows(Principal principal) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT c.id::text, c.label, c.key_id,
                               coalesce(p.display_name, p.username), n.name,
                               c.permissions,
                               c.expires_at::date::text,
                               c.expires_at IS NOT NULL AND c.expires_at <= now(),
                               c.revoked_at::date::text,
                               c.last_used_at::text,
                               c.last_success_at::text,
                               c.last_failure_at::text,
                               c.last_failure_reason,
                               c.success_count, c.failure_count, c.consecutive_failures,
                               -- Whole days, floored. "Failing for 11 days" is the unit somebody acts
                               -- on; hours would imply a precision the freshness thresholds do not have.
                               CASE WHEN c.last_success_at IS NOT NULL
                                    THEN floor(extract(epoch FROM now() - c.last_success_at) / 86400)
                               END
                          FROM service_credential c
                          LEFT JOIN principal p ON p.id = c.principal_id
                          LEFT JOIN org_node n ON n.id = c.scope_node_id
                         WHERE 'sbm.sbom.submit' = ANY (c.permissions)
                         -- Revoked keys last, then the most broken, then the longest silent. An
                         -- administrator opening this page is looking for what to fix, and the ordering
                         -- is the answer to that rather than an alphabetical list.
                         ORDER BY c.revoked_at IS NOT NULL,
                                  c.consecutive_failures DESC,
                                  c.last_success_at ASC NULLS FIRST,
                                  c.label
                        """)) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    List<String> permissions = new ArrayList<>();
                    java.sql.Array granted = r.getArray(6);
                    if (granted != null) {
                        for (Object o : (Object[]) granted.getArray()) {
                            permissions.add(String.valueOf(o));
                        }
                    }
                    int success = r.getInt(14);
                    int failure = r.getInt(15);
                    int run = r.getInt(16);
                    Integer days = (Integer) (r.getObject(17) == null
                            ? null : Integer.valueOf((int) r.getDouble(17)));
                    boolean recorded = success > 0 || failure > 0;
                    String revoked = r.getString(9);
                    boolean expired = r.getBoolean(8);
                    out.add(new Row(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                            r.getString(5), permissions, r.getString(7), expired, revoked,
                            r.getString(10), r.getString(11), r.getString(12), r.getString(13),
                            success, failure, run, recorded, days,
                            verdict(revoked, expired, recorded, run, days),
                            advice(revoked, expired, recorded, run, days)));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * One word for the state of an integration, decided here so the interface cannot invent a sixth.
     *
     * <p>The order matters. Revoked and expired come first because a key that cannot authenticate is not
     * "failing" — it is finished, and telling somebody to investigate rejected documents would send them
     * looking for a problem that is not there.
     */
    private static String verdict(String revoked, boolean expired, boolean recorded, int run,
            Integer daysSinceSuccess) {
        if (revoked != null) {
            return "REVOKED";
        }
        if (expired) {
            return "EXPIRED";
        }
        if (!recorded) {
            // Not "healthy". Nothing has been measured, and saying otherwise is the exact substitution
            // product principle 1 rules out.
            return "NEVER_USED";
        }
        if (run > 0) {
            return "FAILING";
        }
        if (daysSinceSuccess != null && daysSinceSuccess >= 14) {
            // A pipeline that succeeded a fortnight ago and has not been heard from since is not
            // failing — nothing is being rejected. It has gone quiet, which a failure count cannot say
            // and which is just as capable of producing a coverage gap.
            return "SILENT";
        }
        return "HEALTHY";
    }

    /** What to do about it, in one sentence, because a verdict without a next step is a colour. */
    private static String advice(String revoked, boolean expired, boolean recorded, int run,
            Integer daysSinceSuccess) {
        if (revoked != null) {
            return "This key was revoked. If the pipeline it belonged to still needs to submit, issue a "
                    + "new key and update the pipeline — nothing it sends now will be accepted.";
        }
        if (expired) {
            return "This key has expired. Issue a replacement and update the pipeline's secret.";
        }
        if (!recorded) {
            return "No submission has been recorded on this key. Either the pipeline has never run it, "
                    + "or it has never been wired up — check the build configuration before assuming "
                    + "coverage.";
        }
        if (run > 0) {
            return "The last " + run + " submission" + (run == 1 ? "" : "s") + " on this key "
                    + (run == 1 ? "was" : "were") + " refused. The reason is below; until it is fixed, "
                    + "everything this pipeline builds is going unmeasured.";
        }
        if (daysSinceSuccess != null && daysSinceSuccess >= 14) {
            return "Nothing has been submitted for " + daysSinceSuccess + " days and nothing is being "
                    + "refused, so the pipeline is probably not calling the endpoint at all — a removed "
                    + "build step looks exactly like this.";
        }
        return "Submitting normally.";
    }

    /** The estate summary, so the panel says what the failures cost rather than only counting them. */
    public Estate estate(Principal principal) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT
                          (SELECT count(*) FROM service_credential
                            WHERE 'sbm.sbom.submit' = ANY (permissions) AND revoked_at IS NULL
                              AND (expires_at IS NULL OR expires_at > now())),
                          (SELECT count(*) FROM service_credential
                            WHERE 'sbm.sbom.submit' = ANY (permissions) AND revoked_at IS NULL
                              AND (expires_at IS NULL OR expires_at > now())
                              AND consecutive_failures > 0),
                          (SELECT count(*) FROM service_credential
                            WHERE 'sbm.sbom.submit' = ANY (permissions) AND revoked_at IS NULL
                              AND (expires_at IS NULL OR expires_at > now())
                              AND consecutive_failures = 0 AND last_success_at IS NOT NULL
                              AND last_success_at < now() - interval '14 days'),
                          (SELECT count(*) FROM service_credential
                            WHERE 'sbm.sbom.submit' = ANY (permissions) AND revoked_at IS NULL
                              AND (expires_at IS NULL OR expires_at > now())
                              AND success_count = 0 AND failure_count = 0),
                          -- What it costs. Never-covered and stale are counted apart: one is an artifact
                          -- nobody has ever inventoried, the other is one whose inventory has aged out,
                          -- and only the first means the pipeline was never connected.
                          (SELECT count(*) FROM asset_sbom_state WHERE latest_snapshot_at IS NULL),
                          (SELECT count(*) FROM asset_sbom_state WHERE freshness = 'STALE')
                        """)) {
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    return new Estate(0, 0, 0, 0, 0, 0);
                }
                return new Estate(r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4), r.getInt(5),
                        r.getInt(6));
            }
        }
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
