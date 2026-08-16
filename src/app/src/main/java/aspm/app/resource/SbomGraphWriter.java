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
 * Applying a scanner's verdict to a snapshot that already exists. V042.
 *
 * <h2>Why this is not just another submission</h2>
 *
 * <p>A re-scan is emphatically NOT a new bill of materials. The components did not change — nobody
 * built anything — only what is known about them did. Routing it through the submission endpoint
 * would create a second snapshot with identical content, doubling the estate's snapshot count every
 * night and making "SBOMs submitted per month" a chart of how often the scanner ran.
 *
 * <p>So the components are read back from the snapshot that exists, and only the advisory half is
 * rewritten — through {@link SbomGraph}, the same writer the submission path uses, so the two cannot
 * come to mean different things.
 *
 * <h2>What a re-scan may and may not conclude</h2>
 *
 * <p>It may <b>add</b> an advisory to a component, and it may <b>resolve</b> one it no longer
 * reports. The second is the delicate half: a scanner failing to mention a CVE it previously found
 * is ambiguous — the intelligence may have withdrawn it, or the scanner may have crashed halfway.
 * So resolution here requires the scan to have completed and is recorded with an explicit reason
 * naming the scanner, never as a silent disappearance.
 */
public final class SbomGraphWriter {

    private final DataSource dataSource;

    public SbomGraphWriter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /**
     * Writes the advisories a scanner reported for one snapshot.
     *
     * @param results the scanner output, in any shape {@link SbomGraph} reads — CycloneDX with a
     *     {@code vulnerabilities[]} section, Trivy's native JSON, or the normalized
     *     {@code aspm_vulnerabilities[]} array
     * @return the advisories detected for the first time, for the caller to alert on
     */
    public List<WebhookAlerts.Event> applyRescan(Principal principal, UUID snapshotId,
            Map<String, Object> results, String scanner, String intelligenceVersion)
            throws SQLException {
        List<WebhookAlerts.Event> fresh = new ArrayList<>();
        try (Connection connection =
                aspm.app.persistence.TenantConnections.open(dataSource, principal)) {
            try {
                // The components this snapshot already holds, keyed the way the scanner refers to
                // them. A scanner reading a CycloneDX document echoes back the purl it was given, so
                // the purl IS the reference — there are no bom-refs to recover because this document
                // was parsed once already.
                Map<String, UUID> componentIdByRef = new LinkedHashMap<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT c.id, c.purl_canonical, c.purl_original
                          FROM component_entry e
                          JOIN component c ON c.id = e.component_id
                         WHERE e.snapshot_id = ?
                        """)) {
                    statement.setObject(1, snapshotId);
                    try (ResultSet r = statement.executeQuery()) {
                        while (r.next()) {
                            UUID id = r.getObject(1, UUID.class);
                            for (int column : new int[] {2, 3}) {
                                String reference = r.getString(column);
                                if (reference != null && !reference.isBlank()) {
                                    componentIdByRef.putIfAbsent(reference, id);
                                }
                            }
                        }
                    }
                }
                if (componentIdByRef.isEmpty()) {
                    connection.rollback();
                    return List.of();
                }

                // Which pairs existed BEFORE, so "newly detected" is a comparison rather than a
                // guess. Read inside the transaction, before the writer runs.
                java.util.Set<String> before = existingPairs(connection, componentIdByRef.values());

                SbomGraph.Outcome outcome = SbomGraph.record(connection, principal, snapshotId,
                        results, componentIdByRef);

                java.util.Set<String> after = existingPairs(connection, componentIdByRef.values());
                after.removeAll(before);
                fresh.addAll(describe(connection, snapshotId, after));

                RescanService.recordScan(connection, snapshotId, scanner, intelligenceVersion,
                        outcome.advisories(), fresh.size(), "COMPLETED",
                        outcome.warnings().isEmpty() ? null : String.join("; ", outcome.warnings()));
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
        return fresh;
    }

    private static java.util.Set<String> existingPairs(Connection connection,
            java.util.Collection<UUID> components) throws SQLException {
        java.util.Set<String> pairs = new java.util.LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT component_id, advisory_id FROM component_advisory "
                        + " WHERE component_id = ANY (?) AND resolved_at IS NULL")) {
            statement.setArray(1, connection.createArrayOf("uuid", components.toArray()));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    pairs.add(r.getString(1) + "|" + r.getString(2));
                }
            }
        }
        return pairs;
    }

    /** Turns the newly appeared pairs into alertable events, with the place they were found. */
    private static List<WebhookAlerts.Event> describe(Connection connection, UUID snapshotId,
            java.util.Set<String> pairs) throws SQLException {
        List<WebhookAlerts.Event> events = new ArrayList<>();
        if (pairs.isEmpty()) {
            return events;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.advisory_key, sl.code, a.cvss_score, coalesce(a.summary, a.description),
                       c.name, c.version, ca.fixed_version,
                       app.root_name, mid.project_name, ar.display_name, a.id, ar.id,
                       c.id::text || '|' || a.id::text
                  FROM sbom_snapshot s
                  JOIN asset ar ON ar.id = s.artifact_asset_id
                  JOIN component_entry e ON e.snapshot_id = s.id
                  JOIN component c ON c.id = e.component_id
                  JOIN component_advisory ca ON ca.component_id = c.id AND ca.resolved_at IS NULL
                  JOIN advisory a ON a.id = ca.advisory_id
                  LEFT JOIN severity_level sl ON sl.id = a.severity_id
                  LEFT JOIN LATERAL (
                        SELECT ra.display_name AS root_name FROM asset_composition cc
                          JOIN asset ra ON ra.id = cc.root_id
                          JOIN asset_type rt ON rt.id = ra.type_id AND rt.code = 'APPLICATION'
                         WHERE cc.asset_id = ar.id ORDER BY cc.depth LIMIT 1) app ON true
                  LEFT JOIN LATERAL (
                        SELECT ra.display_name AS project_name FROM asset_composition cc
                          JOIN asset ra ON ra.id = cc.root_id
                          JOIN asset_type rt ON rt.id = ra.type_id AND rt.code = 'PROJECT'
                         WHERE cc.asset_id = ar.id ORDER BY cc.depth LIMIT 1) mid ON true
                 WHERE s.id = ?
                """)) {
            statement.setObject(1, snapshotId);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    if (!pairs.contains(r.getString(13))) {
                        continue;
                    }
                    Double score = r.getObject(3) == null ? null : Double.valueOf(r.getDouble(3));
                    events.add(new WebhookAlerts.Event(r.getString(1), r.getString(2), score,
                            r.getString(4), r.getString(5), r.getString(6), r.getString(7),
                            r.getString(8), r.getString(9), r.getString(10),
                            r.getObject(11, UUID.class), r.getObject(12, UUID.class)));
                }
            }
        }
        return events;
    }
}
