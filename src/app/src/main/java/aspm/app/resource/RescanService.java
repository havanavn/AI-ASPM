package aspm.app.resource;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Handing archived bills of materials to a scanner, and taking its verdict back. V042.
 *
 * <h2>The scanner is a client of the platform, not a library inside it</h2>
 *
 * <p>It asks what is due, receives the document, runs Trivy against it, and posts the result through
 * the ordinary signed-credential path (ADR-004, V037). That shape was chosen over embedding a
 * scanner for three reasons, and each is load-bearing:
 *
 * <ul>
 *   <li><b>No new authentication.</b> The worker uses the same signed requests a CI pipeline uses,
 *       so there is one ingestion identity model rather than two.
 *   <li><b>No new ingestion route.</b> Results are written through the same {@link SbomGraph} the
 *       pipeline path uses, so a scheduled-scan advisory and a CI-submitted one cannot come to mean
 *       different things.
 *   <li><b>The document goes to the scanner; the store credentials do not.</b> The worker never
 *       reaches the object store, so a compromised scanner cannot read the archive of every bill of
 *       materials the group has ever submitted.
 * </ul>
 *
 * <p>ADR-013 and ADR-024 hold: Trivy runs against a stored document, never against source. The
 * platform still fetches no code and holds no Git credential.
 */
public final class RescanService {

    /** One unit of work: a snapshot due for re-scan, with the document to scan. */
    public record Pending(String snapshotId, String artifactName, String format, String document) {
    }

    /** The estate-wide schedule. */
    public record Schedule(boolean enabled, int intervalHours, int batchSize, String lastTickAt,
            long due, long unscannable, long total) {
    }

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());
    private final ObjectStore objects = new ObjectStore(System.getenv());
    private final SbomGraphWriter writer;

    public RescanService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
        this.writer = new SbomGraphWriter(dataSource);
    }

    // ----------------------------------------------------------------------------------------------

    /** The schedule, with how much work it implies. */
    public Schedule schedule(Principal principal) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT coalesce(s.enabled, false), coalesce(s.interval_hours, 24),
                               coalesce(s.batch_size, 25),
                               to_char(s.last_tick_at, 'YYYY-MM-DD HH24:MI'),
                               (SELECT count(*) FROM rescan_queue q
                                 WHERE NOT q.unscannable
                                   AND (q.last_scanned_at IS NULL
                                        OR q.last_scanned_at < now()
                                           - make_interval(hours => coalesce(s.interval_hours, 24)))),
                               (SELECT count(*) FROM rescan_queue q WHERE q.unscannable),
                               (SELECT count(*) FROM rescan_queue)
                          FROM (SELECT 1) one
                          LEFT JOIN rescan_schedule s ON true
                        """)) {
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    return new Schedule(false, 24, 25, null, 0, 0, 0);
                }
                return new Schedule(r.getBoolean(1), r.getInt(2), r.getInt(3), r.getString(4),
                        r.getLong(5), r.getLong(6), r.getLong(7));
            }
        }
    }

    /** Saves the schedule. One row per tenant; upserted because there is nothing to create first. */
    public void setSchedule(Principal principal, boolean enabled, int intervalHours, int batchSize)
            throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO rescan_schedule (tenant_id, enabled, interval_hours, batch_size,
                                                     updated_by)
                        VALUES (current_tenant_id(), ?, ?, ?, ?)
                        ON CONFLICT (tenant_id) DO UPDATE
                           SET enabled = EXCLUDED.enabled,
                               interval_hours = EXCLUDED.interval_hours,
                               batch_size = EXCLUDED.batch_size,
                               updated_at = now(), updated_by = EXCLUDED.updated_by
                        """)) {
            statement.setBoolean(1, enabled);
            statement.setInt(2, Math.max(1, Math.min(8760, intervalHours)));
            statement.setInt(3, Math.max(1, Math.min(500, batchSize)));
            statement.setObject(4, principal.principalId());
            statement.executeUpdate();
            // Turning the rescan schedule off stops known-vulnerable dependencies being re-checked
            // while every page keeps showing the last result as if it were current. PP-1 makes that a
            // coverage change, and a coverage change nobody recorded is one nobody can date.
            audit.domainChange(connection, principal, "rescan_schedule",
                    aspm.kernel.audit.contract.DomainChangeKind.UPDATED, null, null,
                    java.util.Map.of("enabled", Boolean.valueOf(enabled),
                            "interval_hours", Integer.valueOf(Math.max(1, Math.min(8760, intervalHours))),
                            "batch_size", Integer.valueOf(Math.max(1, Math.min(500, batchSize)))));
            connection.commit();
        }
    }

    /**
     * The work due now, with each document inline.
     *
     * <p>Returns nothing when the schedule is off. The worker ticks on a fixed interval and asks;
     * whether anything is due is the platform's decision, which is what keeps the schedule
     * configurable in the dashboard rather than in a crontab.
     *
     * <p>A snapshot whose archived document cannot be read is SKIPPED and its scan row is marked,
     * rather than handed over empty: a scanner given no document reports no vulnerabilities, and
     * that would be recorded as a clean result. Product principle 1 — the most dangerous output this
     * platform can produce is a clean answer nobody measured.
     */
    public List<Pending> pending(Principal principal) throws SQLException {
        List<Pending> out = new ArrayList<>();
        try (Connection connection = open(principal)) {
            int batch;
            int hours;
            try (PreparedStatement config = connection.prepareStatement(
                    "SELECT enabled, interval_hours, batch_size FROM rescan_schedule")) {
                try (ResultSet r = config.executeQuery()) {
                    if (!r.next() || !r.getBoolean(1)) {
                        return List.of();
                    }
                    hours = r.getInt(2);
                    batch = r.getInt(3);
                }
            }
            try (PreparedStatement mark = connection.prepareStatement(
                    "UPDATE rescan_schedule SET last_tick_at = now()")) {
                mark.executeUpdate();
            }

            List<String[]> due = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT q.snapshot_id::text, q.artifact_name, q.format, q.storage_ref
                      FROM rescan_queue q
                     WHERE NOT q.unscannable
                       AND (q.last_scanned_at IS NULL
                            OR q.last_scanned_at < now() - make_interval(hours => ?))
                     ORDER BY q.last_scanned_at NULLS FIRST, q.submitted_at
                     LIMIT ?
                    """)) {
                statement.setInt(1, hours);
                statement.setInt(2, batch);
                try (ResultSet r = statement.executeQuery()) {
                    while (r.next()) {
                        due.add(new String[] {r.getString(1), r.getString(2), r.getString(3),
                                r.getString(4)});
                    }
                }
            }

            for (String[] row : due) {
                Optional<byte[]> document = objects.get(row[3]);
                if (document.isEmpty()) {
                    recordScan(connection, UUID.fromString(row[0]), "unavailable", null, 0, 0,
                            "UNREADABLE", "the archived document could not be read");
                    continue;
                }
                out.add(new Pending(row[0], row[1], row[2],
                        new String(document.orElseThrow(), StandardCharsets.UTF_8)));
            }
            // The tick and the unreadable-document records commit with the batch that produced them.
            connection.commit();
        }
        return List.copyOf(out);
    }

    /**
     * Records a scanner's verdict for one snapshot.
     *
     * <p>The advisory rows are written by the SAME code the submission path uses, so a scheduled scan
     * and a CI push cannot disagree about what an advisory means or where it applies.
     *
     * @return the advisories detected for the first time, for the caller to alert on
     */
    public List<WebhookAlerts.Event> submit(Principal principal, UUID snapshotId,
            Map<String, Object> results, String scanner, String intelligenceVersion)
            throws SQLException {
        return writer.applyRescan(principal, snapshotId, results, scanner, intelligenceVersion);
    }

    static void recordScan(Connection connection, UUID snapshotId, String scanner,
            String intelligenceVersion, int found, int fresh, String status, String detail)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO snapshot_scan (tenant_id, snapshot_id, scanner, intelligence_version,
                                           advisories_found, advisories_new, status, detail)
                VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, snapshot_id) DO UPDATE
                   SET scanned_at = now(), scanner = EXCLUDED.scanner,
                       intelligence_version = EXCLUDED.intelligence_version,
                       advisories_found = EXCLUDED.advisories_found,
                       advisories_new = EXCLUDED.advisories_new,
                       status = EXCLUDED.status, detail = EXCLUDED.detail
                """)) {
            statement.setObject(1, snapshotId);
            statement.setString(2, scanner);
            statement.setString(3, intelligenceVersion);
            statement.setInt(4, found);
            statement.setInt(5, fresh);
            statement.setString(6, status);
            statement.setString(7, detail);
            statement.executeUpdate();
        }
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
