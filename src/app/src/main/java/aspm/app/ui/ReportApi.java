package aspm.app.ui;

import aspm.app.persistence.TenantConnections;
import aspm.app.reporting.ReportService;
import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Scheduled reports, "my reports", and the audit evidence export over JSON and files. DOC-12 §11–§12.
 * Schedule administration is class A/E under {@code rpt.schedule.manage}; artifacts are the caller's
 * own (class A under {@code vul.finding.read}); audit evidence is class A under {@code rpt.evidence.export}.
 */
public final class ReportApi {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final DataSource dataSource;
    private final ReportService reports;

    public ReportApi(DataSource dataSource, ReportService reports) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.reports = Objects.requireNonNull(reports);
    }

    /** {@code GET /api/ui/settings/report-schedules}. */
    public Dispatcher.Response list(Dispatcher.Request request) throws SQLException {
        Principal principal = request.principal();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", reports.list(principal).stream().map(ReportApi::schedule).toList());
        body.put("kinds", ReportService.kinds());
        body.put("mayManage", principal.holds(ReportService.MANAGE));
        body.put("mayExportEvidence", principal.holds(ReportService.EVIDENCE));
        body.put("elevated", principal.stepUpAuthenticated());
        body.put("storageConfigured", reports.storageConfigured());
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> people = new ArrayList<>();
        try (Connection c = TenantConnections.open(dataSource, principal)) {
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT n.id, (SELECT string_agg(an.name, ' › ' ORDER BY cl.depth DESC) FROM org_closure cl JOIN org_node an ON an.id = cl.ancestor_id "
                            + "WHERE cl.descendant_id = n.id) FROM org_node n WHERE n.id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)) ORDER BY 2 LIMIT 500")) {
                statement.setArray(1, c.createArrayOf("uuid", principal.scopeNodeIds().toArray()));
                try (ResultSet r = statement.executeQuery()) {
                    while (r.next()) {
                        nodes.add(Map.of("id", r.getString(1), "path", r.getString(2) == null ? "" : r.getString(2)));
                    }
                }
            }
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT id, coalesce(display_name, username) FROM principal WHERE kind = 'HUMAN' AND lifecycle_state = 'ACTIVE' ORDER BY 2 LIMIT 500");
                    ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    people.add(Map.of("id", r.getString(1), "name", r.getString(2)));
                }
            }
        }
        body.put("nodes", nodes);
        body.put("people", people);
        return json(body);
    }

    /** {@code POST /api/ui/settings/report-schedules}. */
    public Dispatcher.Response create(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        return json(schedule(reports.create(request.principal(), text(body, "code").orElse(""), text(body, "displayName").orElse(""), text(body, "reportKind").orElse(""),
                uuid(body, "scopeNodeId"), integer(body, "periodDays").orElse(30), text(body, "cadence").orElse("WEEKLY"), integer(body, "runHourUtc").orElse(6),
                integer(body, "runWeekday").orElse(1), integer(body, "runDayOfMonth").orElse(1), uuids(body, "recipients"), uuid(body, "ownerPrincipalId"))));
    }

    /** {@code POST /api/ui/settings/report-schedules/{id}}. */
    public Dispatcher.Response update(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        int rowVersion = body.get("rowVersion") instanceof Number n ? n.intValue() : -1;
        if (rowVersion < 0) {
            return new Dispatcher.Response(400, Map.of("code", "ROW_VERSION_REQUIRED", "message", "the row version being edited is required"), Map.of());
        }
        return json(schedule(reports.update(request.principal(), id(request), text(body, "displayName").orElse(null), body.containsKey("scopeNodeId"), uuid(body, "scopeNodeId"),
                integer(body, "periodDays"), text(body, "cadence"), integer(body, "runHourUtc"), integer(body, "runWeekday"), integer(body, "runDayOfMonth"),
                uuid(body, "ownerPrincipalId"), rowVersion)));
    }

    /** {@code POST /api/ui/settings/report-schedules/{id}/recipients}. */
    public Dispatcher.Response setRecipients(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        return json(schedule(reports.setRecipients(request.principal(), id(request), uuids(body, "recipients"))));
    }

    /** {@code POST /api/ui/settings/report-schedules/{id}/transition}. */
    public Dispatcher.Response transition(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        return json(schedule(reports.transition(request.principal(), id(request), String.valueOf(body.getOrDefault("state", "")))));
    }

    /** {@code POST /api/ui/settings/report-schedules/{id}/run}. */
    public Dispatcher.Response run(Dispatcher.Request request) throws SQLException {
        return json(schedule(reports.runNow(request.principal(), id(request))));
    }

    /** {@code GET /api/ui/reports/artifacts}. */
    public Dispatcher.Response artifacts(Dispatcher.Request request) throws SQLException {
        return json(Map.of("rows", reports.artifacts(request.principal()).stream().map(ReportApi::artifact).toList()));
    }

    /** {@code GET /api/ui/reports/artifacts/{id}/download}. */
    public Dispatcher.Response download(Dispatcher.Request request) throws SQLException {
        Optional<ReportService.Generated> file = reports.download(request.principal(), id(request));
        if (file.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        return new Dispatcher.Response(200, new InterfaceResource.Binary(file.get().bytes()),
                Map.of("Content-Type", XLSX, "Content-Disposition", "attachment; filename=\"" + file.get().filename() + "\""));
    }

    /** {@code GET /api/ui/reports/audit-evidence?scope=&from=&to=}. Assembled now, as the caller; audited. */
    public Dispatcher.Response auditEvidence(Dispatcher.Request request) throws Exception {
        Map<String, String> query = request.query();
        Optional<UUID> scope = Optional.ofNullable(query.get("scope")).filter(s -> !s.isBlank()).map(s -> {
            try {
                return UUID.fromString(s.strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("scope is not a UUID");
            }
        });
        LocalDate to = date(query.get("to")).orElse(LocalDate.now(java.time.ZoneOffset.UTC));
        LocalDate from = date(query.get("from")).orElse(to.minusDays(89));
        ReportService.Generated generated = reports.generateEvidence(request.principal(), scope, from, to);
        return new Dispatcher.Response(200, new InterfaceResource.Binary(generated.bytes()),
                Map.of("Content-Type", XLSX, "Content-Disposition", "attachment; filename=\"" + generated.filename() + "\"",
                        "X-ASPM-Artifact", generated.artifactId().toString()));
    }

    // ==============================================================================================

    private static UUID id(Dispatcher.Request request) {
        try {
            return UUID.fromString(request.pathVariables().get("id"));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the identifier is not a UUID");
        }
    }

    private static Optional<String> text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || String.valueOf(value).isBlank() ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    private static Optional<UUID> uuid(Map<String, Object> body, String key) {
        return text(body, key).map(v -> {
            try {
                return UUID.fromString(v);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(key + " is not a UUID");
            }
        });
    }

    private static List<UUID> uuids(Map<String, Object> body, String key) {
        List<UUID> out = new ArrayList<>();
        if (body.get(key) instanceof List<?> list) {
            for (Object o : list) {
                try {
                    out.add(UUID.fromString(String.valueOf(o)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(key + " carries a value that is not a UUID");
                }
            }
        }
        return out;
    }

    private static Optional<Integer> integer(Map<String, Object> body, String key) {
        return body.get(key) instanceof Number n ? Optional.of(n.intValue()) : Optional.empty();
    }

    private static Optional<LocalDate> date(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value.strip()));
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("dates are YYYY-MM-DD");
        }
    }

    static Map<String, Object> schedule(ReportService.Schedule s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id().toString());
        m.put("code", s.code());
        m.put("displayName", s.displayName());
        m.put("reportKind", s.reportKind());
        m.put("scopeNodeId", s.scopeNodeId().map(UUID::toString).orElse(null));
        m.put("scopePath", s.scopePath().orElse(null));
        m.put("periodDays", s.periodDays());
        m.put("cadence", s.cadence());
        m.put("runHourUtc", s.runHourUtc());
        m.put("runWeekday", s.runWeekday());
        m.put("runDayOfMonth", s.runDayOfMonth());
        m.put("ownerPrincipalId", s.ownerPrincipalId().toString());
        m.put("ownerName", s.ownerName());
        m.put("lifecycleState", s.lifecycleState());
        m.put("nextRunAt", s.nextRunAt().map(OffsetDateTime::toString).orElse(null));
        m.put("lastRunAt", s.lastRunAt().map(OffsetDateTime::toString).orElse(null));
        m.put("lastOutcome", s.lastOutcome().orElse(null));
        m.put("recipients", s.recipients().stream().map(r -> {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("principalId", r.principalId().toString());
            rm.put("name", r.name());
            rm.put("droppedAt", r.droppedAt().map(OffsetDateTime::toString).orElse(null));
            rm.put("droppedReason", r.droppedReason().orElse(null));
            return rm;
        }).toList());
        m.put("rowVersion", s.rowVersion());
        return m;
    }

    static Map<String, Object> artifact(ReportService.Artifact a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id().toString());
        m.put("scheduleId", a.scheduleId().map(UUID::toString).orElse(null));
        m.put("scheduleName", a.scheduleName().orElse(null));
        m.put("reportKind", a.reportKind());
        m.put("recipientPrincipalId", a.recipientPrincipalId().toString());
        m.put("recipientName", a.recipientName());
        m.put("scopePath", a.scopePath().orElse(null));
        m.put("periodFrom", a.periodFrom().toString());
        m.put("periodTo", a.periodTo().toString());
        m.put("generatedAt", a.generatedAt().toString());
        m.put("status", a.status());
        m.put("failureDetail", a.failureDetail().orElse(null));
        m.put("byteSize", a.byteSize());
        m.put("sha256", a.sha256().orElse(null));
        m.put("stored", a.stored());
        m.put("basis", a.basis());
        m.put("downloadCount", a.downloadCount());
        return m;
    }

    private static Dispatcher.Response json(Object body) {
        return Dispatcher.Response.ok(body);
    }
}
