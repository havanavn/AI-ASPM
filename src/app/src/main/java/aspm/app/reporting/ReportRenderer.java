package aspm.app.reporting;

import aspm.app.persistence.TenantConnections;
import aspm.app.resource.VulnerabilityQuery;
import aspm.app.resource.Workbook;
import aspm.app.runtime.Principal;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The report compositions of DOC-12 §10 that this release renders, each as a workbook, each AS ONE
 * RECIPIENT. {@code PRD-DSH-039}, {@code PRD-DSH-040}, {@code PRD-DSH-042}, {@code PRD-DSH-043},
 * {@code PRD-DSH-046}, {@code PRD-DSH-047}, {@code PRD-DSH-048}.
 *
 * <p>Every query here carries the recipient's scope closure the way {@link VulnerabilityQuery} does,
 * because the recipient is the connection's principal and the file is whatever that principal may see
 * at this moment — a report is an export with a schedule, and an export that widened its reader's
 * authorization would be a disclosure with a timestamp.
 *
 * <p>The first sheet of every workbook is "About": kind, generated when and for whom, scope, period,
 * filters, the aggregation basis and the record count of every other sheet. It is not optional and
 * there is no template that can remove it ({@code PRD-DSH-042}). Audit evidence adds the exclusions
 * of {@code PRD-DSH-048} by construction: no description, no proof of concept, no credential, no
 * evidence body — evidence is listed by identifier, media type, size and hash so an auditor asks for a
 * specific item through the audited reveal path.
 */
public final class ReportRenderer {

    /** DOC-12 §10 compositions rendered here. Product-fixed; a tenant chooses among them. */
    public enum Kind {
        FINDING_REGISTER("Finding register", "Findings the recipient may see, with lifecycle, closure reasons and where they live; detected or open in the period.",
                List.of("Findings"), "vul.finding.read"),
        EXCEPTION_REGISTER("Exception register", "Risk exceptions active in the period with approver, expiry, compensating controls and renewal chain (PRD-DSH-040).",
                List.of("Exceptions"), "vul.finding.read"),
        SERVICE_LEVEL("Service level report", "Clocks that ran in the period: met, breached, paused time, extensions, and who was blocking.",
                List.of("Summary", "Clocks"), "vul.finding.read"),
        COVERAGE("Coverage report", "Per organization node: assets, measured in the period, stale, never measured, assessments completed, open findings.",
                List.of("Coverage"), "vul.finding.read"),
        AUDIT_EVIDENCE("Audit evidence", "For a scope and period: assessments with coverage, findings with lifecycle, exceptions with approvals, service levels with attribution, access review, configuration changes, evidence references (PRD-DSH-046).",
                List.of("Assessments", "Findings", "Exceptions", "Service levels", "Access review", "Configuration changes", "Evidence references"),
                "rpt.evidence.export");

        public final String label;
        public final String description;
        public final List<String> sheets;
        /** The permission a recipient must hold to receive this kind at all. */
        public final String requiredPermission;

        Kind(String label, String description, List<String> sheets, String requiredPermission) {
            this.label = label;
            this.description = description;
            this.sheets = sheets;
            this.requiredPermission = requiredPermission;
        }
    }

    /** Who, what subtree, which period, when. */
    public record Context(Principal recipient, String recipientName, Optional<UUID> scopeNode, LocalDate from, LocalDate to,
            Instant generatedAt, String requestedBy) {
        public Context {
            Objects.requireNonNull(recipient);
            Objects.requireNonNull(recipientName);
            Objects.requireNonNull(scopeNode);
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            Objects.requireNonNull(generatedAt);
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("the period ends before it starts");
            }
        }
    }

    /**
     * @param reaches false when the recipient's scope does not contain the requested subtree — nothing was
     *     rendered, and the caller treats it as a recipient who lost access ({@code PRD-DSH-045})
     */
    public record Rendered(byte[] bytes, Map<String, Object> basis, boolean reaches) {
    }

    private static final String AGGREGATION_BASIS = "Rows are the records the recipient was authorized to see at generation time, "
            + "within the stated scope and period; counts are computed over exactly that set. Absence of a row is not evidence of "
            + "absence: consult the coverage figures before reading a clean sheet as clean. Nothing here was generated by a model.";

    private final DataSource dataSource;
    private final VulnerabilityQuery vulnerabilities;

    public ReportRenderer(DataSource dataSource, VulnerabilityQuery vulnerabilities) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.vulnerabilities = Objects.requireNonNull(vulnerabilities);
    }

    public Rendered render(Kind kind, Context context) throws SQLException, IOException {
        try (Connection connection = TenantConnections.open(dataSource, context.recipient())) {
            Optional<java.sql.Array> scope = scopeOf(connection, context);
            if (scope.isEmpty()) {
                return new Rendered(new byte[0], Map.of("reaches", false), false);
            }
            String scopePath = context.scopeNode().map(node -> path(connection, node)).orElse("the recipient's whole reach");
            List<Workbook.Sheet> sheets = new ArrayList<>();
            Map<String, Object> counts = new LinkedHashMap<>();
            switch (kind) {
                case FINDING_REGISTER -> sheets.add(count(counts, findingRegister(context)));
                case EXCEPTION_REGISTER -> sheets.add(count(counts, exceptions(connection, scope.get(), context, false)));
                case SERVICE_LEVEL -> {
                    Workbook.Sheet clocks = serviceLevels(connection, scope.get(), context, "Clocks");
                    sheets.add(serviceLevelSummary(clocks));
                    sheets.add(count(counts, clocks));
                }
                case COVERAGE -> sheets.add(count(counts, coverage(connection, scope.get(), context)));
                case AUDIT_EVIDENCE -> {
                    sheets.add(count(counts, assessments(connection, scope.get(), context)));
                    sheets.add(count(counts, findingsForEvidence(connection, scope.get(), context)));
                    sheets.add(count(counts, exceptions(connection, scope.get(), context, true)));
                    sheets.add(count(counts, serviceLevels(connection, scope.get(), context, "Service levels")));
                    sheets.add(count(counts, accessReview(connection, scope.get(), context)));
                    sheets.add(count(counts, configurationChanges(connection, scope.get(), context)));
                    sheets.add(count(counts, evidenceReferences(connection, scope.get(), context)));
                }
            }
            Map<String, Object> basis = new LinkedHashMap<>();
            basis.put("kind", kind.name());
            basis.put("scope", context.scopeNode().map(UUID::toString).orElse("recipient-reach"));
            basis.put("scope_path", scopePath);
            basis.put("period_from", context.from().toString());
            basis.put("period_to", context.to().toString());
            basis.put("generated_at", context.generatedAt().toString());
            basis.put("generated_for", context.recipient().principalId().toString());
            basis.put("aggregation_basis", AGGREGATION_BASIS);
            basis.put("exclusions", kind == Kind.AUDIT_EVIDENCE
                    ? "PRD-DSH-048: no credentials, secret values, evidence content or per-person workload data; evidence referenced, not embedded"
                    : "no credentials, secret values, evidence content or per-person workload data");
            basis.put("counts", counts);
            sheets.add(0, about(kind, context, scopePath, counts));
            return new Rendered(Workbook.write(sheets), basis, true);
        }
    }

    // ==============================================================================================
    // About — the four honesty mechanisms, unremovable
    // ==============================================================================================

    private static Workbook.Sheet about(Kind kind, Context context, String scopePath, Map<String, Object> counts) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Report", kind.label));
        rows.add(List.of("What it contains", kind.description));
        rows.add(List.of("Generated at (UTC)", context.generatedAt().toString()));
        rows.add(List.of("Generated for", context.recipientName()));
        rows.add(List.of("Requested by", context.requestedBy() == null ? context.recipientName() : context.requestedBy()));
        rows.add(List.of("Scope", scopePath));
        rows.add(List.of("Period", context.from() + " to " + context.to() + " (inclusive)"));
        rows.add(List.of("Aggregation basis", AGGREGATION_BASIS));
        rows.add(List.of("Coverage", "Every count in this file is bounded by what was measured. Measured-and-clean and not-measured are distinguished in the Coverage columns where they appear; where they do not, treat a zero as 'no record', not as 'none exist'."));
        rows.add(List.of("Generated content", "None. No narrative, number or classification in this file was produced by a model."));
        rows.add(List.of("Normalization", "Figures are not normalized across organization nodes of different size; compare rates, not counts, between nodes."));
        rows.add(List.of("Excluded by policy", kind == Kind.AUDIT_EVIDENCE
                ? "Finding descriptions and proofs of concept, credentials, secret values, evidence bodies, per-person workload (PRD-DSH-048). Evidence is referenced by identifier and hash."
                : "Credentials, secret values, evidence bodies, per-person workload."));
        counts.forEach((sheet, n) -> rows.add(List.of("Rows in '" + sheet + "'", String.valueOf(n))));
        return new Workbook.Sheet("About", List.of("Field", "Value"), rows);
    }

    private static Workbook.Sheet count(Map<String, Object> counts, Workbook.Sheet sheet) {
        counts.put(sheet.name(), sheet.rows().size());
        return sheet;
    }

    // ==============================================================================================
    // Scope
    // ==============================================================================================

    /** The closure roots to query with: the requested subtree if the recipient reaches it, else the recipient's own roots. */
    private static Optional<java.sql.Array> scopeOf(Connection connection, Context context) throws SQLException {
        java.util.Set<UUID> reach = context.recipient().scopeNodeIds();
        if (reach.isEmpty()) {
            return Optional.empty();
        }
        if (context.scopeNode().isEmpty()) {
            return Optional.of(connection.createArrayOf("uuid", reach.toArray()));
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM org_closure WHERE descendant_id = ? AND ancestor_id = ANY (?)")) {
            statement.setObject(1, context.scopeNode().get());
            statement.setArray(2, connection.createArrayOf("uuid", reach.toArray()));
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(connection.createArrayOf("uuid", new Object[] {context.scopeNode().get()}));
    }

    private static String path(Connection connection, UUID node) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT string_agg(an.name, ' › ' ORDER BY cl.depth DESC) FROM org_closure cl JOIN org_node an ON an.id = cl.ancestor_id WHERE cl.descendant_id = ?")) {
            statement.setObject(1, node);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() && r.getString(1) != null ? r.getString(1) : node.toString();
            }
        } catch (SQLException e) {
            return node.toString();
        }
    }

    private static final String ORG_PATH = "(SELECT string_agg(an.name, ' › ' ORDER BY cl.depth DESC) FROM org_closure cl "
            + "JOIN org_node an ON an.id = cl.ancestor_id WHERE cl.descendant_id = %s)";
    private static final String NAME_OF = "(SELECT coalesce(p.display_name, p.username) FROM principal p WHERE p.id = %s)";

    private static void bindPeriod(PreparedStatement statement, int fromIndex, Context context) throws SQLException {
        statement.setObject(fromIndex, context.from());
        statement.setObject(fromIndex + 1, context.to().plusDays(1));
    }

    private static String s(ResultSet r, int i) throws SQLException {
        String v = r.getString(i);
        return v == null ? "" : v;
    }

    private static String stamp(ResultSet r, int i) throws SQLException {
        java.sql.Timestamp t = r.getTimestamp(i);
        return t == null ? "" : t.toInstant().atOffset(ZoneOffset.UTC).toString().replace("T", " ").substring(0, 16);
    }

    // ==============================================================================================
    // Sheets
    // ==============================================================================================

    private Workbook.Sheet findingRegister(Context context) throws SQLException {
        VulnerabilityQuery.Filter filter = new VulnerabilityQuery.Filter(
                context.scopeNode().map(List::of).orElse(null), null, null, null, null, false, null, java.util.Set.of(), null,
                context.from().toString(), context.to().toString(), null, null, null, false, null, null, null, false);
        List<List<String>> cells = new ArrayList<>();
        for (VulnerabilityQuery.Row row : vulnerabilities.exportRows(context.recipient(), filter)) {
            cells.add(List.of(row.id(), row.title(), n(row.severity()), n(row.reportedSeverity()), row.state(), n(row.findingClass()), n(row.sourceTool()),
                    n(row.orgPath()), n(row.applicationName()), n(row.projectName()), n(row.assetName()), String.valueOf(row.assetCount()),
                    row.internetFacing() ? "yes" : "no", row.claimed() ? "yes" : "no", row.accepted() ? "yes" : "no", String.valueOf(row.recurrence()),
                    n(row.firstDetectedAt()), n(row.lastDetectedAt()), n(row.closedAt()), n(row.closureReason()),
                    "OPEN".equals(row.state()) ? "" : (row.closureVerified() ? "yes" : "no"), row.ageDays() == null ? "" : String.valueOf(row.ageDays()),
                    n(row.requestCode())));
        }
        return new Workbook.Sheet("Findings", List.of("Finding", "Title", "Severity", "Severity reported by tool", "State", "Kind", "Found by",
                "Organization", "Application", "Project", "Asset", "Assets affected", "Internet-facing", "Fix claimed", "Risk accepted", "Recurrences",
                "First detected", "Last detected", "Closed", "Closure reason", "Closure verified", "Age (days)", "Assessment"), cells);
    }

    /** PRD-DSH-048: identity, lifecycle, closure — never the description or the proof of concept. */
    private static Workbook.Sheet findingsForEvidence(Connection connection, java.sql.Array scope, Context context) throws SQLException {
        List<List<String>> cells = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT f.id, f.title, coalesce(es.code, rs.code, 'UNRATED'), f.finding_class, f.state, f.lifecycle_state, f.source_tool, "
                        + ORG_PATH.formatted("f.scope_node_id") + ", f.first_detected_at, f.last_detected_at, f.closed_at, f.closure_reason, "
                        + "f.closure_verification_method, " + NAME_OF.formatted("f.closure_verified_by") + ", f.recurrence_count, "
                        + "(SELECT r.request_code FROM assessment_request r WHERE r.id = f.discovered_in_request_id) "
                        + "FROM finding f LEFT JOIN severity_level es ON es.id = f.effective_severity_id LEFT JOIN severity_level rs ON rs.id = f.reported_severity_id "
                        + "WHERE f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)) "
                        + "AND f.first_detected_at < ? AND (f.closed_at IS NULL OR f.closed_at >= ?) ORDER BY f.first_detected_at LIMIT 20000")) {
            statement.setArray(1, scope);
            statement.setObject(2, context.to().plusDays(1));
            statement.setObject(3, context.from());
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    cells.add(List.of(s(r, 1), s(r, 2), s(r, 3), s(r, 4), s(r, 5), s(r, 6), s(r, 7), s(r, 8), stamp(r, 9), stamp(r, 10), stamp(r, 11),
                            s(r, 12), s(r, 13), s(r, 14), s(r, 15), s(r, 16)));
                }
            }
        }
        return new Workbook.Sheet("Findings", List.of("Finding", "Title", "Severity", "Kind", "State", "Lifecycle", "Found by", "Organization",
                "First detected", "Last detected", "Closed", "Closure reason", "Closure verification", "Verified by", "Recurrences", "Assessment"), cells);
    }

    /** PRD-DSH-040: expiry, approver, compensating controls, renewal chain. */
    private static Workbook.Sheet exceptions(Connection connection, java.sql.Array scope, Context context, boolean evidence) throws SQLException {
        List<List<String>> cells = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT e.id, e.subject_kind, f.title, coalesce(sl.code, 'UNRATED'), " + ORG_PATH.formatted("f.scope_node_id") + ", e.state, "
                        + NAME_OF.formatted("e.requested_by") + ", e.requested_at, e.expires_at, e.max_duration_days, " + NAME_OF.formatted("e.approved_by")
                        + ", e.approved_at, e.step_up_authenticated, e.resolved_at, e.resolution_reason, "
                        + "(SELECT string_agg(c.kind || CASE WHEN c.description IS NULL THEN '' ELSE ': ' || left(c.description, 120) END, ' | ') "
                        + " FROM risk_exception_compensating_control c WHERE c.risk_exception_id = e.id), "
                        + "(SELECT p.id::text FROM risk_exception p WHERE p.renewed_by_exception_id = e.id LIMIT 1), e.renewed_by_exception_id::text, "
                        + "(WITH RECURSIVE chain AS (SELECT e.id AS cur, 0 AS depth UNION ALL SELECT p.id, chain.depth + 1 FROM risk_exception p JOIN chain ON p.renewed_by_exception_id = chain.cur) "
                        + " SELECT max(depth) FROM chain) "
                        + "FROM risk_exception e LEFT JOIN finding f ON f.id = e.subject_id LEFT JOIN severity_level sl ON sl.id = coalesce(f.effective_severity_id, f.reported_severity_id) "
                        + "WHERE f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)) "
                        + "AND e.requested_at < ? AND (e.resolved_at IS NULL OR e.resolved_at >= ?) ORDER BY e.requested_at LIMIT 20000")) {
            statement.setArray(1, scope);
            statement.setObject(2, context.to().plusDays(1));
            statement.setObject(3, context.from());
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    cells.add(List.of(s(r, 1), s(r, 2), s(r, 3), s(r, 4), s(r, 5), s(r, 6), s(r, 7), stamp(r, 8), stamp(r, 9), s(r, 10), s(r, 11), stamp(r, 12),
                            r.getBoolean(13) ? "yes" : "no", stamp(r, 14), s(r, 15), s(r, 16), s(r, 17), s(r, 18), s(r, 19)));
                }
            }
        }
        return new Workbook.Sheet("Exceptions", List.of("Exception", "Subject kind", "Finding", "Severity", "Organization", "State", "Requested by",
                "Requested at", "Expires at", "Max duration (days)", "Approved by", "Approved at", "Step-up at approval", "Resolved at", "Resolution reason",
                "Compensating controls", "Renews exception", "Renewed by exception", "Renewals before this one"), cells);
    }

    private static Workbook.Sheet serviceLevels(Connection connection, java.sql.Array scope, Context context, String name) throws SQLException {
        List<List<String>> cells = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT c.id, c.subject_kind, f.title, coalesce(sl.code, 'UNRATED'), " + ORG_PATH.formatted("f.scope_node_id") + ", p.code, c.policy_version, "
                        + "p.target_business_days, c.started_at, c.due_at, c.original_due_at, c.state, c.breached_at, c.resolved_at, "
                        + "CASE WHEN c.resolved_at IS NULL THEN CASE WHEN c.breached_at IS NULL AND c.due_at > now() THEN 'RUNNING' ELSE 'BREACHED' END "
                        + "     WHEN c.resolved_at <= c.due_at THEN 'MET' ELSE 'MISSED' END, "
                        + "round(c.elapsed_running_seconds / 3600.0, 1), round(c.elapsed_paused_seconds / 3600.0, 1), "
                        + NAME_OF.formatted("c.extension_approved_by") + ", c.extension_approved_at, c.extension_reason, "
                        + "(SELECT string_agg(DISTINCT i.blocking_attribution, ', ') FROM service_level_clock_interval i WHERE i.clock_id = c.id AND i.blocking_attribution IS NOT NULL) "
                        + "FROM service_level_clock c JOIN service_level_policy p ON p.id = c.policy_id LEFT JOIN finding f ON f.id = c.subject_id "
                        + "LEFT JOIN severity_level sl ON sl.id = coalesce(f.effective_severity_id, f.reported_severity_id) "
                        + "WHERE f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)) "
                        + "AND c.started_at < ? AND (c.resolved_at IS NULL OR c.resolved_at >= ?) ORDER BY c.due_at LIMIT 20000")) {
            statement.setArray(1, scope);
            statement.setObject(2, context.to().plusDays(1));
            statement.setObject(3, context.from());
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    cells.add(List.of(s(r, 1), s(r, 2), s(r, 3), s(r, 4), s(r, 5), s(r, 6), s(r, 7), s(r, 8), stamp(r, 9), stamp(r, 10), stamp(r, 11), s(r, 12),
                            stamp(r, 13), stamp(r, 14), s(r, 15), s(r, 16), s(r, 17), s(r, 18), stamp(r, 19), s(r, 20), s(r, 21)));
                }
            }
        }
        return new Workbook.Sheet(name, List.of("Clock", "Subject kind", "Finding", "Severity", "Organization", "Policy", "Policy version",
                "Target (business days)", "Started", "Due", "Original due", "State", "Breached at", "Resolved at", "Outcome", "Running (hours)", "Paused (hours)",
                "Extension approved by", "Extension approved at", "Extension reason", "Blocking attribution"), cells);
    }

    /** Compliance as a rate over the sheet it summarizes, so the two cannot disagree. */
    private static Workbook.Sheet serviceLevelSummary(Workbook.Sheet clocks) {
        Map<String, int[]> byAttribution = new LinkedHashMap<>();
        int met = 0;
        int missed = 0;
        int breachedOpen = 0;
        int running = 0;
        for (List<String> row : clocks.rows()) {
            String outcome = row.get(14);
            switch (outcome) {
                case "MET" -> met++;
                case "MISSED" -> missed++;
                case "BREACHED" -> breachedOpen++;
                default -> running++;
            }
            if (!"MET".equals(outcome) && !"RUNNING".equals(outcome)) {
                String attribution = row.get(20).isEmpty() ? "(no blocking attribution recorded)" : row.get(20);
                byAttribution.computeIfAbsent(attribution, k -> new int[1])[0]++;
            }
        }
        int decided = met + missed;
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Clocks in period", String.valueOf(clocks.rows().size())));
        rows.add(List.of("Resolved within target", String.valueOf(met)));
        rows.add(List.of("Resolved after target", String.valueOf(missed)));
        rows.add(List.of("Breached and still open", String.valueOf(breachedOpen)));
        rows.add(List.of("Running, not yet due", String.valueOf(running)));
        rows.add(List.of("Compliance (resolved within target / resolved)", decided == 0 ? "no clock resolved in the period" : (met * 100 / decided) + "%"));
        byAttribution.forEach((who, n) -> rows.add(List.of("Breaches attributed to: " + who, String.valueOf(n[0]))));
        return new Workbook.Sheet("Summary", List.of("Measure", "Value"), rows);
    }

    /** PP-1 as a sheet: measured, stale, never measured — per node, so a clean node and an unmeasured node read differently. */
    private static Workbook.Sheet coverage(Connection connection, java.sql.Array scope, Context context) throws SQLException {
        List<List<String>> cells = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT n.id, " + ORG_PATH.formatted("n.id") + ", "
                        + "(SELECT count(*) FROM asset a WHERE a.owning_node_id = n.id AND a.lifecycle_state IN ('DISCOVERED', 'ACTIVE')), "
                        + "(SELECT count(DISTINCT l.asset_id) FROM asset_finding_link l JOIN finding f ON f.id = l.finding_id JOIN asset a ON a.id = l.asset_id "
                        + "  WHERE a.owning_node_id = n.id AND f.last_detected_at >= ? AND f.last_detected_at < ?), "
                        + "(SELECT count(DISTINCT l.asset_id) FROM asset_finding_link l JOIN finding f ON f.id = l.finding_id JOIN asset a ON a.id = l.asset_id "
                        + "  WHERE a.owning_node_id = n.id AND f.last_detected_at < ?), "
                        + "(SELECT count(*) FROM asset a WHERE a.owning_node_id = n.id AND a.lifecycle_state IN ('DISCOVERED', 'ACTIVE') "
                        + "  AND NOT EXISTS (SELECT 1 FROM asset_finding_link l WHERE l.asset_id = a.id)), "
                        + "(SELECT count(*) FROM assessment s WHERE s.scope_node_id = n.id AND s.completed_at >= ? AND s.completed_at < ?), "
                        + "(SELECT count(*) FROM finding f WHERE f.scope_node_id = n.id AND f.state = 'OPEN'), "
                        + "(SELECT max(f.last_detected_at) FROM finding f WHERE f.scope_node_id = n.id) "
                        + "FROM org_node n WHERE n.id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)) ORDER BY 2")) {
            statement.setObject(1, context.from());
            statement.setObject(2, context.to().plusDays(1));
            statement.setObject(3, context.from());
            statement.setObject(4, context.from());
            statement.setObject(5, context.to().plusDays(1));
            statement.setArray(6, scope);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    long assets = r.getLong(3);
                    long measured = r.getLong(4);
                    cells.add(List.of(s(r, 1), s(r, 2), String.valueOf(assets), String.valueOf(measured), String.valueOf(r.getLong(5)), String.valueOf(r.getLong(6)),
                            assets == 0 ? "no assets" : (measured * 100 / assets) + "%", String.valueOf(r.getLong(7)), String.valueOf(r.getLong(8)), stamp(r, 9)));
                }
            }
        }
        return new Workbook.Sheet("Coverage", List.of("Node", "Organization", "Assets (live)", "Assets with a detection in period", "Assets stale (last detection before period)",
                "Assets never measured", "Measured in period (% of live assets)", "Assessments completed in period", "Open findings", "Latest detection"), cells);
    }

    /** PRD-DSH-039 / PRD-DSH-046: assessments with coverage — assessed, not applicable, not assessed. */
    private static Workbook.Sheet assessments(Connection connection, java.sql.Array scope, Context context) throws SQLException {
        List<List<String>> cells = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT a.id, (SELECT r.request_code FROM assessment_request r WHERE r.id = a.request_id), t.code, " + ORG_PATH.formatted("a.scope_node_id")
                        + ", a.state, a.outcome, a.coverage_items_total, a.coverage_items_assessed, a.coverage_items_not_applicable, a.coverage_items_not_assessed, "
                        + "a.coverage_ratio, a.incompleteness_acknowledged, a.incompleteness_reason, " + NAME_OF.formatted("a.lead_principal_id") + ", a.started_at, a.completed_at, "
                        + "(SELECT count(*) FROM finding f WHERE f.discovered_in_request_id = a.request_id), "
                        + "(SELECT string_agg(DISTINCT cr.outcome || ':' || coalesce(cr.reason, ''), ' | ') FROM checklist_instance ci JOIN checklist_item_result cr ON cr.instance_id = ci.id "
                        + " WHERE ci.assessment_id = a.id AND cr.outcome = 'NOT_APPLICABLE') "
                        + "FROM assessment a JOIN assessment_type t ON t.id = a.type_id "
                        + "WHERE a.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)) "
                        + "AND coalesce(a.completed_at, a.started_at, a.created_at) >= ? AND coalesce(a.completed_at, a.started_at, a.created_at) < ? ORDER BY a.created_at LIMIT 20000")) {
            statement.setArray(1, scope);
            bindPeriod(statement, 2, context);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    cells.add(List.of(s(r, 1), s(r, 2), s(r, 3), s(r, 4), s(r, 5), s(r, 6), s(r, 7), s(r, 8), s(r, 9), s(r, 10), s(r, 11),
                            r.getBoolean(12) ? "yes" : "no", s(r, 13), s(r, 14), stamp(r, 15), stamp(r, 16), s(r, 17), s(r, 18)));
                }
            }
        }
        return new Workbook.Sheet("Assessments", List.of("Assessment", "Request", "Type", "Organization", "State", "Outcome", "Items total", "Items assessed",
                "Items not applicable", "Items not assessed", "Coverage ratio", "Incompleteness acknowledged", "Incompleteness reason", "Lead", "Started", "Completed",
                "Findings from this request", "Not-applicable reasons"), cells);
    }

    /** Access review output: who held what, over which subtree, granted and revoked when and by whom. */
    private static Workbook.Sheet accessReview(Connection connection, java.sql.Array scope, Context context) throws SQLException {
        List<List<String>> cells = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT a.id, p.username, coalesce(p.display_name, ''), p.kind, p.lifecycle_state, r.code, a.scope_mode, "
                        + "CASE WHEN a.scope_mode = 'TENANT' THEN 'whole tenant' ELSE coalesce(" + ORG_PATH.formatted("a.scope_node_id") + ", '') END, "
                        + "a.granted_at, " + NAME_OF.formatted("a.granted_by") + ", a.expires_at, a.revoked_at, a.revoked_reason, "
                        + "CASE WHEN a.revoked_at IS NULL AND (a.expires_at IS NULL OR a.expires_at > now()) THEN 'yes' ELSE 'no' END "
                        + "FROM role_assignment a JOIN principal p ON p.id = a.principal_id JOIN role r ON r.id = a.role_id "
                        + "WHERE (a.scope_mode = 'TENANT' OR a.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)) "
                        + "   OR a.scope_node_id IN (SELECT ancestor_id FROM org_closure WHERE descendant_id = ANY (?))) "
                        + "AND a.granted_at < ? AND (a.revoked_at IS NULL OR a.revoked_at >= ?) ORDER BY p.username, a.granted_at LIMIT 20000")) {
            statement.setArray(1, scope);
            statement.setArray(2, scope);
            statement.setObject(3, context.to().plusDays(1));
            statement.setObject(4, context.from());
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    cells.add(List.of(s(r, 1), s(r, 2), s(r, 3), s(r, 4), s(r, 5), s(r, 6), s(r, 7), s(r, 8), stamp(r, 9), s(r, 10), stamp(r, 11), stamp(r, 12), s(r, 13), s(r, 14)));
                }
            }
        }
        return new Workbook.Sheet("Access review", List.of("Assignment", "Username", "Display name", "Principal kind", "Principal state", "Role", "Scope mode",
                "Scope", "Granted at", "Granted by", "Expires at", "Revoked at", "Revoked reason", "Active at generation"), cells);
    }

    /** Configuration change history from the audit chain: the event, never the payload body. */
    private static Workbook.Sheet configurationChanges(Connection connection, java.sql.Array scope, Context context) throws SQLException {
        List<List<String>> cells = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT e.sequence, e.occurred_at, e.event_type, " + NAME_OF.formatted("e.actor_id") + ", e.actor_type, e.object_kind, e.object_id::text, e.outcome, "
                        + "CASE WHEN e.scope_node_id IS NULL THEN '' ELSE coalesce(" + ORG_PATH.formatted("e.scope_node_id") + ", '') END, e.break_glass_ref IS NOT NULL "
                        + "FROM audit_event e WHERE e.occurred_at >= ? AND e.occurred_at < ? "
                        + "AND (e.event_type ~ '^(role|assignment|sod_constraint|org_node_type|workflow|automation_rule|scoring_model|sla_policy|taxonomy|attribute_schema|ai_configuration|connector|identity_provider|notification_channel|report_schedule|entitlement|retention|service_credential|asset_attribute_definition|endpoint_environment|org_node)\\.') "
                        + "AND (e.scope_node_id IS NULL OR e.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))) "
                        + "ORDER BY e.sequence LIMIT 20000")) {
            bindPeriod(statement, 1, context);
            statement.setArray(3, scope);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    cells.add(List.of(s(r, 1), stamp(r, 2), s(r, 3), s(r, 4), s(r, 5), s(r, 6), s(r, 7), s(r, 8), s(r, 9), r.getBoolean(10) ? "yes" : "no"));
                }
            }
        }
        return new Workbook.Sheet("Configuration changes", List.of("Sequence", "Occurred at", "Event", "Actor", "Actor type", "Object kind", "Object", "Outcome",
                "Scope", "Under break-glass"), cells);
    }

    /** PRD-DSH-048: referenced, not embedded — identifier, media type, size, hash, availability. */
    private static Workbook.Sheet evidenceReferences(Connection connection, java.sql.Array scope, Context context) throws SQLException {
        List<List<String>> cells = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT v.id, v.finding_id::text, v.assessment_id::text, v.declared_media_type, coalesce(v.verified_media_type, ''), v.byte_size, v.content_hash, "
                        + "v.malware_verdict, v.availability, v.uploaded_at, v.retention_until, v.destroyed_at "
                        + "FROM evidence v LEFT JOIN finding f ON f.id = v.finding_id LEFT JOIN assessment a ON a.id = v.assessment_id "
                        + "WHERE coalesce(f.scope_node_id, a.scope_node_id) IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)) "
                        + "AND v.uploaded_at >= ? AND v.uploaded_at < ? ORDER BY v.uploaded_at LIMIT 20000")) {
            statement.setArray(1, scope);
            bindPeriod(statement, 2, context);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    byte[] hash = r.getBytes(7);
                    cells.add(List.of(s(r, 1), s(r, 2), s(r, 3), s(r, 4), s(r, 5), s(r, 6), hash == null ? "" : "sha256:" + HexFormat.of().formatHex(hash),
                            s(r, 8), s(r, 9), stamp(r, 10), stamp(r, 11), stamp(r, 12)));
                }
            }
        }
        return new Workbook.Sheet("Evidence references", List.of("Evidence", "Finding", "Assessment", "Declared media type", "Verified media type", "Bytes",
                "Content hash", "Malware verdict", "Availability", "Uploaded at", "Retention until", "Destroyed at"), cells);
    }

    private static String n(String value) {
        return value == null ? "" : value;
    }
}
