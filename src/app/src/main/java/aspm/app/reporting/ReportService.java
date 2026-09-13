package aspm.app.reporting;

import aspm.app.identity.IdentityService;
import aspm.app.notification.Notifier;
import aspm.app.persistence.TenantConnections;
import aspm.app.resource.ObjectStore;
import aspm.app.runtime.Json;
import aspm.app.runtime.Principal;
import java.io.IOException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Scheduled reports and the audit evidence artifact. DOC-12 §11–§12. {@code PRD-DSH-043}, {@code PRD-DSH-045},
 * {@code PRD-DSH-046}, {@code PRD-DSH-047}, {@code PRD-DSH-048}, {@code SEC-AUD-009}.
 *
 * <p>Administration (schedules, recipients) is audited as the {@code report_schedule} aggregate. A
 * run renders one artifact PER RECIPIENT, as that recipient, at that moment; a recipient who no longer
 * holds the permission the report needs, or whose scope no longer reaches the schedule's subtree, is
 * dropped from the schedule with the reason and the owner told. Every generation is a
 * {@code report.generated} audit event carrying the basis ({@code PRD-DSH-047}); the artifact row
 * carries the same basis so the file, the row and the trail say one thing.
 */
public final class ReportService {

    public static final String MANAGE = "rpt.schedule.manage";
    public static final String EVIDENCE = "rpt.evidence.export";

    public record Recipient(UUID principalId, String name, Optional<OffsetDateTime> droppedAt, Optional<String> droppedReason) {
    }

    public record Schedule(UUID id, String code, String displayName, String reportKind, Optional<UUID> scopeNodeId, Optional<String> scopePath,
            int periodDays, String cadence, int runHourUtc, int runWeekday, int runDayOfMonth, UUID ownerPrincipalId, String ownerName,
            String lifecycleState, Optional<OffsetDateTime> nextRunAt, Optional<OffsetDateTime> lastRunAt, Optional<String> lastOutcome,
            List<Recipient> recipients, int rowVersion) {
    }

    public record Artifact(UUID id, Optional<UUID> scheduleId, Optional<String> scheduleName, String reportKind, UUID recipientPrincipalId,
            String recipientName, Optional<String> scopePath, LocalDate periodFrom, LocalDate periodTo, OffsetDateTime generatedAt, String status,
            Optional<String> failureDetail, long byteSize, Optional<String> sha256, boolean stored, Map<String, Object> basis, int downloadCount) {
    }

    public record Generated(byte[] bytes, String filename, UUID artifactId) {
    }

    private final DataSource dataSource;
    private final ObjectStore objects;
    private final ReportRenderer renderer;
    private final aspm.app.audit.AuditTrail audit = new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public ReportService(DataSource dataSource, ObjectStore objects, ReportRenderer renderer) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.objects = Objects.requireNonNull(objects);
        this.renderer = Objects.requireNonNull(renderer);
    }

    /** The compositions on offer, for the form. */
    public static List<Map<String, Object>> kinds() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReportRenderer.Kind kind : ReportRenderer.Kind.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind.name());
            m.put("label", kind.label);
            m.put("description", kind.description);
            m.put("sheets", kind.sheets);
            m.put("requiredPermission", kind.requiredPermission);
            out.add(m);
        }
        return out;
    }

    public boolean storageConfigured() {
        return objects.configured();
    }

    // ==============================================================================================
    // Schedules
    // ==============================================================================================

    public List<Schedule> list(Principal principal) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, principal)) {
            return schedules(connection, "ORDER BY CASE s.lifecycle_state WHEN 'ACTIVE' THEN 0 WHEN 'PAUSED' THEN 1 ELSE 2 END, s.display_name", null);
        }
    }

    public Schedule create(Principal actor, String code, String displayName, String kind, Optional<UUID> scopeNodeId, int periodDays, String cadence,
            int hour, int weekday, int dayOfMonth, List<UUID> recipients, Optional<UUID> owner) throws SQLException {
        ReportRenderer.Kind reportKind = kindOf(kind);
        if (code == null || !code.matches("[a-z][a-z0-9-]{1,31}")) {
            throw new IllegalArgumentException("the schedule code is 2–32 lower-case letters, digits or dashes, starting with a letter");
        }
        if (displayName == null || displayName.isBlank() || displayName.strip().length() > 80) {
            throw new IllegalArgumentException("a display name of at most 80 characters is required");
        }
        validateTiming(periodDays, cadence, hour, weekday, dayOfMonth);
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("at least one recipient is required; a report nobody receives is a schedule for nothing");
        }
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            scopeNodeId.ifPresent(node -> requireNode(connection, node));
            Instant next = nextRun(cadence, hour, weekday, dayOfMonth, Instant.now());
            UUID id;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO report_schedule (tenant_id, code, display_name, report_kind, scope_node_id, period_days, cadence, run_hour_utc, run_weekday, "
                            + "run_day_of_month, owner_principal_id, next_run_at, created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
                statement.setObject(1, actor.tenantId());
                statement.setString(2, code);
                statement.setString(3, displayName.strip());
                statement.setString(4, reportKind.name());
                statement.setObject(5, scopeNodeId.orElse(null));
                statement.setInt(6, periodDays);
                statement.setString(7, cadence);
                statement.setInt(8, hour);
                statement.setInt(9, weekday);
                statement.setInt(10, dayOfMonth);
                statement.setObject(11, owner.orElse(actor.principalId()));
                statement.setObject(12, next.atOffset(ZoneOffset.UTC));
                statement.setObject(13, actor.principalId());
                statement.setObject(14, actor.principalId());
                try (ResultSet r = statement.executeQuery()) {
                    r.next();
                    id = r.getObject(1, UUID.class);
                }
            }
            replaceRecipients(connection, actor, id, recipients);
            audit.domainChangeBy(connection, actor.principalId(), "report_schedule", aspm.kernel.audit.contract.DomainChangeKind.CREATED, id,
                    scopeNodeId.orElse(null), Map.of("code", code, "kind", reportKind.name(), "cadence", cadence, "period_days", periodDays,
                            "recipients", recipients.stream().map(UUID::toString).toList(), "scope_node_id", scopeNodeId.map(UUID::toString).orElse("recipient-reach")));
            connection.commit();
            return one(connection, id);
        }
    }

    public Schedule update(Principal actor, UUID id, String displayName, boolean scopeGiven, Optional<UUID> scopeNodeId, Optional<Integer> periodDays,
            Optional<String> cadence, Optional<Integer> hour, Optional<Integer> weekday, Optional<Integer> dayOfMonth, Optional<UUID> owner, int rowVersion)
            throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Schedule current = one(connection, id);
            if (current.rowVersion() != rowVersion) {
                throw new IllegalStateException("the schedule changed since it was loaded; reload and apply the change again");
            }
            if ("RETIRED".equals(current.lifecycleState())) {
                throw new IllegalArgumentException("a retired schedule is not edited");
            }
            int period = periodDays.orElse(current.periodDays());
            String cad = cadence.orElse(current.cadence());
            int h = hour.orElse(current.runHourUtc());
            int wd = weekday.orElse(current.runWeekday());
            int dom = dayOfMonth.orElse(current.runDayOfMonth());
            validateTiming(period, cad, h, wd, dom);
            Optional<UUID> scope = scopeGiven ? scopeNodeId : current.scopeNodeId();
            scope.ifPresent(node -> requireNode(connection, node));
            String name = displayName == null || displayName.isBlank() ? current.displayName() : displayName.strip();
            Instant next = nextRun(cad, h, wd, dom, Instant.now());
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE report_schedule SET display_name = ?, scope_node_id = ?, period_days = ?, cadence = ?, run_hour_utc = ?, run_weekday = ?, run_day_of_month = ?, "
                            + "owner_principal_id = ?, next_run_at = ?, updated_at = now(), updated_by = ?, row_version = row_version + 1 WHERE id = ? AND row_version = ?")) {
                statement.setString(1, name);
                statement.setObject(2, scope.orElse(null));
                statement.setInt(3, period);
                statement.setString(4, cad);
                statement.setInt(5, h);
                statement.setInt(6, wd);
                statement.setInt(7, dom);
                statement.setObject(8, owner.orElse(current.ownerPrincipalId()));
                statement.setObject(9, next.atOffset(ZoneOffset.UTC));
                statement.setObject(10, actor.principalId());
                statement.setObject(11, id);
                statement.setInt(12, rowVersion);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("the schedule changed since it was loaded; reload and apply the change again");
                }
            }
            audit.domainChangeBy(connection, actor.principalId(), "report_schedule", aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id,
                    scope.orElse(null), Map.of("cadence", cad, "period_days", period, "scope_node_id", scope.map(UUID::toString).orElse("recipient-reach")));
            connection.commit();
            return one(connection, id);
        }
    }

    /** Replaces the recipient list. Dropped recipients keep their row with the reason (PRD-DSH-045 is about being told, and the record is how). */
    public Schedule setRecipients(Principal actor, UUID id, List<UUID> recipients) throws SQLException {
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("at least one recipient is required");
        }
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Schedule current = one(connection, id);
            replaceRecipients(connection, actor, id, recipients);
            audit.domainChangeBy(connection, actor.principalId(), "report_schedule", aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id,
                    current.scopeNodeId().orElse(null), Map.of("recipients", recipients.stream().map(UUID::toString).toList()));
            connection.commit();
            return one(connection, id);
        }
    }

    public Schedule transition(Principal actor, UUID id, String target) throws SQLException {
        if (!List.of("ACTIVE", "PAUSED", "RETIRED").contains(target)) {
            throw new IllegalArgumentException("the target state is ACTIVE, PAUSED or RETIRED");
        }
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Schedule current = one(connection, id);
            if ("RETIRED".equals(current.lifecycleState())) {
                throw new IllegalArgumentException("a retired schedule stays retired");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE report_schedule SET lifecycle_state = ?, next_run_at = CASE WHEN ? = 'ACTIVE' THEN ? ELSE NULL END, updated_at = now(), updated_by = ?, "
                            + "row_version = row_version + 1 WHERE id = ?")) {
                statement.setString(1, target);
                statement.setString(2, target);
                statement.setObject(3, nextRun(current.cadence(), current.runHourUtc(), current.runWeekday(), current.runDayOfMonth(), Instant.now()).atOffset(ZoneOffset.UTC));
                statement.setObject(4, actor.principalId());
                statement.setObject(5, id);
                statement.executeUpdate();
            }
            audit.domainChangeBy(connection, actor.principalId(), "report_schedule",
                    "RETIRED".equals(target) ? aspm.kernel.audit.contract.DomainChangeKind.RETIRED : aspm.kernel.audit.contract.DomainChangeKind.TRANSITIONED,
                    id, current.scopeNodeId().orElse(null), Map.of("from", current.lifecycleState(), "to", target));
            connection.commit();
            return one(connection, id);
        }
    }

    /** The next run is now. The worker does the rendering, as it would at the scheduled hour. */
    public Schedule runNow(Principal actor, UUID id) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Schedule current = one(connection, id);
            if (!"ACTIVE".equals(current.lifecycleState())) {
                throw new IllegalArgumentException("only an active schedule runs");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE report_schedule SET next_run_at = now(), updated_at = now(), updated_by = ? WHERE id = ?")) {
                statement.setObject(1, actor.principalId());
                statement.setObject(2, id);
                statement.executeUpdate();
            }
            audit.domainChangeBy(connection, actor.principalId(), "report_schedule", aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id,
                    current.scopeNodeId().orElse(null), Map.of("run_requested", true));
            connection.commit();
            return one(connection, id);
        }
    }

    // ==============================================================================================
    // A run — called by the worker with a tenant context bound and no actor
    // ==============================================================================================

    /** Renders one due schedule for each live recipient. Returns a one-line outcome. */
    public String runSchedule(UUID tenantId, UUID scheduleId) throws SQLException {
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId)) {
            Schedule schedule = one(connection, scheduleId);
            ReportRenderer.Kind kind = ReportRenderer.Kind.valueOf(schedule.reportKind());
            LocalDate to = LocalDate.now(ZoneOffset.UTC).minusDays(1);
            LocalDate from = to.minusDays(schedule.periodDays() - 1L);
            int generated = 0;
            int dropped = 0;
            int failed = 0;
            String ownerName = nameOf(connection, schedule.ownerPrincipalId());
            for (Recipient recipient : schedule.recipients()) {
                if (recipient.droppedAt().isPresent()) {
                    continue;
                }
                Principal as = IdentityService.effectivePrincipal(connection, tenantId, recipient.principalId(), false, false);
                String dropReason = null;
                if (!principalActive(connection, recipient.principalId())) {
                    dropReason = "the recipient's account is no longer active";
                } else if (!as.holds(kind.requiredPermission)) {
                    dropReason = "the recipient no longer holds " + kind.requiredPermission;
                }
                ReportRenderer.Rendered rendered = null;
                if (dropReason == null) {
                    try {
                        rendered = renderer.render(kind, new ReportRenderer.Context(as, recipient.name(), schedule.scopeNodeId(), from, to, Instant.now(), ownerName));
                    } catch (IOException | RuntimeException e) {
                        failed++;
                        recordFailure(connection, schedule, kind, recipient, from, to, "render failed: " + e.getClass().getSimpleName());
                        Notifier.emit(connection, tenantId, event("report.failed", schedule, Map.of("state", recipient.name() + ": " + e.getClass().getSimpleName())),
                                Set.of(schedule.ownerPrincipalId()));
                        continue;
                    }
                    if (!rendered.reaches()) {
                        dropReason = "the recipient's scope no longer reaches " + schedule.scopePath().orElse("the schedule's subtree");
                    }
                }
                if (dropReason != null) {
                    // PRD-DSH-045: dropped with the reason, and the owner told — never silently retained, never silently gone.
                    dropped++;
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE report_schedule_recipient SET dropped_at = now(), dropped_reason = ? WHERE schedule_id = ? AND principal_id = ? AND dropped_at IS NULL")) {
                        statement.setString(1, dropReason);
                        statement.setObject(2, scheduleId);
                        statement.setObject(3, recipient.principalId());
                        statement.executeUpdate();
                    }
                    Notifier.emit(connection, tenantId, event("report.recipient_dropped", schedule, Map.of("state", recipient.name() + " — " + dropReason)),
                            Set.of(schedule.ownerPrincipalId()));
                    continue;
                }
                if (!objects.configured()) {
                    failed++;
                    recordFailure(connection, schedule, kind, recipient, from, to, "no object store is configured (ASPM_OBJECTSTORE_*); the file cannot be retained");
                    Notifier.emit(connection, tenantId, event("report.failed", schedule, Map.of("state", "no object store configured")), Set.of(schedule.ownerPrincipalId()));
                    continue;
                }
                String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC).format(Instant.now());
                String key = "tenants/" + tenantId + "/reports/" + schedule.code() + "/" + recipient.principalId() + "/" + kind.name().toLowerCase(java.util.Locale.ROOT) + "-" + stamp + ".xlsx";
                Optional<String> ref = objects.put(ObjectStore.EXPORT_BUCKET, key, rendered.bytes(),
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                if (ref.isEmpty()) {
                    failed++;
                    recordFailure(connection, schedule, kind, recipient, from, to, "the object store refused the upload");
                    Notifier.emit(connection, tenantId, event("report.failed", schedule, Map.of("state", "upload refused")), Set.of(schedule.ownerPrincipalId()));
                    continue;
                }
                UUID artifactId = insertArtifact(connection, tenantId, Optional.of(scheduleId), kind, recipient.principalId(), schedule.scopeNodeId(), from, to,
                        schedule.ownerPrincipalId(), ref, rendered.bytes(), rendered.basis(), null);
                // SEC-AUD-009 / PRD-DSH-047: the generation is audited, for the recipient it was rendered as.
                audit.event(connection, as, aspm.kernel.audit.contract.AuditEventType.REPORT_GENERATED, artifactId, schedule.scopeNodeId().orElse(null),
                        basisPayload(rendered.basis(), "schedule", schedule.code()));
                Notifier.emit(connection, tenantId, new Notifier.Event("report.ready", "REPORT_ARTIFACT", artifactId, schedule.scopeNodeId(), schedule.displayName(),
                        Optional.empty(), ownerName, Map.of("state", kind.label), Optional.of("/settings?tab=reports")), Set.of(recipient.principalId()));
                generated++;
            }
            String outcome = generated + " generated, " + dropped + " dropped, " + failed + " failed";
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE report_schedule SET last_run_at = now(), last_outcome = ?, next_run_at = ?, updated_at = now() WHERE id = ?")) {
                statement.setString(1, outcome);
                statement.setObject(2, nextRun(schedule.cadence(), schedule.runHourUtc(), schedule.runWeekday(), schedule.runDayOfMonth(), Instant.now()).atOffset(ZoneOffset.UTC));
                statement.setObject(3, scheduleId);
                statement.executeUpdate();
            }
            connection.commit();
            return outcome;
        }
    }

    private static Notifier.Event event(String kind, Schedule schedule, Map<String, String> arguments) {
        return new Notifier.Event(kind, "REPORT_SCHEDULE", schedule.id(), schedule.scopeNodeId(), schedule.displayName(), Optional.empty(), "",
                arguments, Optional.of("/settings?tab=reports"));
    }

    // ==============================================================================================
    // On demand: audit evidence
    // ==============================================================================================

    /** PRD-DSH-046..048: assembled now, as the caller, for a scope and period; audited; retained when a store exists. */
    public Generated generateEvidence(Principal actor, Optional<UUID> scopeNodeId, LocalDate from, LocalDate to) throws SQLException, IOException {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("a period is required, and it must end after it starts");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 730) {
            throw new IllegalArgumentException("a period of at most two years");
        }
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            scopeNodeId.ifPresent(node -> requireNode(connection, node));
            String me = nameOf(connection, actor.principalId());
            ReportRenderer.Rendered rendered = renderer.render(ReportRenderer.Kind.AUDIT_EVIDENCE,
                    new ReportRenderer.Context(actor, me, scopeNodeId, from, to, Instant.now(), me));
            if (!rendered.reaches()) {
                throw new IllegalArgumentException("the requested scope is outside your reach");
            }
            String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC).format(Instant.now());
            String filename = "audit-evidence-" + from + "-to-" + to + "-" + stamp + ".xlsx";
            Optional<String> ref = objects.configured()
                    ? objects.put(ObjectStore.EXPORT_BUCKET, "tenants/" + actor.tenantId() + "/evidence/" + actor.principalId() + "/" + filename, rendered.bytes(),
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    : Optional.empty();
            UUID artifactId = insertArtifact(connection, actor.tenantId(), Optional.empty(), ReportRenderer.Kind.AUDIT_EVIDENCE, actor.principalId(), scopeNodeId,
                    from, to, actor.principalId(), ref, rendered.bytes(), rendered.basis(), null);
            audit.event(connection, actor, aspm.kernel.audit.contract.AuditEventType.REPORT_GENERATED, artifactId, scopeNodeId.orElse(null),
                    basisPayload(rendered.basis(), "on_demand", "audit_evidence"));
            connection.commit();
            return new Generated(rendered.bytes(), filename, artifactId);
        }
    }

    // ==============================================================================================
    // Artifacts
    // ==============================================================================================

    /** The caller's own artifacts, plus every artifact of a schedule the caller owns. */
    public List<Artifact> artifacts(Principal principal) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, principal)) {
            return artifacts(connection, "WHERE a.recipient_principal_id = ? OR s.owner_principal_id = ? ORDER BY a.generated_at DESC LIMIT 200",
                    principal.principalId(), principal.principalId());
        }
    }

    /** The bytes, for the recipient only: the file was rendered as them and nobody else may read it as them. */
    public Optional<Generated> download(Principal principal, UUID artifactId) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, principal)) {
            List<Artifact> found = artifacts(connection, "WHERE a.id = ? AND a.recipient_principal_id = ?", artifactId, principal.principalId());
            if (found.isEmpty() || !found.get(0).stored()) {
                return Optional.empty();
            }
            String ref;
            try (PreparedStatement statement = connection.prepareStatement("SELECT storage_ref FROM report_artifact WHERE id = ?")) {
                statement.setObject(1, artifactId);
                try (ResultSet r = statement.executeQuery()) {
                    ref = r.next() ? r.getString(1) : null;
                }
            }
            Optional<byte[]> bytes = ref == null ? Optional.empty() : objects.get(ref);
            if (bytes.isEmpty()) {
                return Optional.empty();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE report_artifact SET download_count = download_count + 1, last_downloaded_at = now() WHERE id = ?")) {
                statement.setObject(1, artifactId);
                statement.executeUpdate();
            }
            connection.commit();
            Artifact a = found.get(0);
            return Optional.of(new Generated(bytes.get(), a.reportKind().toLowerCase(java.util.Locale.ROOT) + "-" + a.periodFrom() + "-to-" + a.periodTo() + ".xlsx", artifactId));
        }
    }

    // ==============================================================================================

    private static ReportRenderer.Kind kindOf(String kind) {
        try {
            return ReportRenderer.Kind.valueOf(kind == null ? "" : kind);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown report kind; the options are " + List.of(ReportRenderer.Kind.values()));
        }
    }

    private static void validateTiming(int periodDays, String cadence, int hour, int weekday, int dayOfMonth) {
        if (periodDays < 1 || periodDays > 730) {
            throw new IllegalArgumentException("the period is between 1 and 730 days");
        }
        if (!List.of("DAILY", "WEEKLY", "MONTHLY").contains(cadence)) {
            throw new IllegalArgumentException("the cadence is DAILY, WEEKLY or MONTHLY");
        }
        if (hour < 0 || hour > 23 || weekday < 1 || weekday > 7 || dayOfMonth < 1 || dayOfMonth > 28) {
            throw new IllegalArgumentException("the run time is an hour 0–23 UTC, a weekday 1–7 (Monday first) and a day of month 1–28");
        }
    }

    /** The next occurrence strictly after {@code after}. Pure; tested directly. */
    public static Instant nextRun(String cadence, int hour, int weekday, int dayOfMonth, Instant after) {
        ZonedDateTime base = after.atZone(ZoneOffset.UTC);
        ZonedDateTime candidate = base.toLocalDate().atStartOfDay(ZoneOffset.UTC).plusHours(hour);
        switch (cadence) {
            case "DAILY" -> {
                if (!candidate.isAfter(base)) {
                    candidate = candidate.plusDays(1);
                }
            }
            case "WEEKLY" -> {
                DayOfWeek wanted = DayOfWeek.of(weekday);
                while (candidate.getDayOfWeek() != wanted || !candidate.isAfter(base)) {
                    candidate = candidate.plusDays(1);
                }
            }
            case "MONTHLY" -> {
                candidate = candidate.withDayOfMonth(dayOfMonth);
                if (!candidate.isAfter(base)) {
                    candidate = candidate.plusMonths(1).withDayOfMonth(dayOfMonth);
                }
            }
            default -> throw new IllegalArgumentException("the cadence is DAILY, WEEKLY or MONTHLY");
        }
        return candidate.toInstant();
    }

    private static void requireNode(Connection connection, UUID node) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM org_node WHERE id = ?")) {
            statement.setObject(1, node);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    throw new IllegalArgumentException("the scope node does not exist in this tenant");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean principalActive(Connection connection, UUID principalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM principal WHERE id = ? AND lifecycle_state = 'ACTIVE'")) {
            statement.setObject(1, principalId);
            try (ResultSet r = statement.executeQuery()) {
                return r.next();
            }
        }
    }

    private static String nameOf(Connection connection, UUID principalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT coalesce(display_name, username) FROM principal WHERE id = ?")) {
            statement.setObject(1, principalId);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? r.getString(1) : principalId.toString();
            }
        }
    }

    private static void replaceRecipients(Connection connection, Principal actor, UUID scheduleId, List<UUID> recipients) throws SQLException {
        Set<UUID> wanted = new LinkedHashSet<>(recipients);
        for (UUID principalId : wanted) {
            if (!principalActive(connection, principalId)) {
                throw new IllegalArgumentException("recipient " + principalId + " is not an active principal of this tenant");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE report_schedule_recipient SET dropped_at = now(), dropped_reason = 'removed by the schedule owner' "
                        + "WHERE schedule_id = ? AND dropped_at IS NULL AND principal_id <> ALL (?)")) {
            statement.setObject(1, scheduleId);
            statement.setArray(2, connection.createArrayOf("uuid", wanted.toArray()));
            statement.executeUpdate();
        }
        for (UUID principalId : wanted) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO report_schedule_recipient (tenant_id, schedule_id, principal_id, added_by) SELECT ?, ?, ?, ? "
                            + "WHERE NOT EXISTS (SELECT 1 FROM report_schedule_recipient WHERE schedule_id = ? AND principal_id = ? AND dropped_at IS NULL)")) {
                statement.setObject(1, actor.tenantId());
                statement.setObject(2, scheduleId);
                statement.setObject(3, principalId);
                statement.setObject(4, actor.principalId());
                statement.setObject(5, scheduleId);
                statement.setObject(6, principalId);
                statement.executeUpdate();
            }
        }
    }

    private static Map<String, Object> basisPayload(Map<String, Object> basis, String how, String what) {
        Map<String, Object> payload = new LinkedHashMap<>(basis);
        payload.put("generation", how);
        payload.put("subject", what);
        return payload;
    }

    private void recordFailure(Connection connection, Schedule schedule, ReportRenderer.Kind kind, Recipient recipient, LocalDate from, LocalDate to, String detail)
            throws SQLException {
        insertArtifact(connection, schedule.ownerPrincipalId() == null ? null : connectionTenant(connection), Optional.of(schedule.id()), kind, recipient.principalId(),
                schedule.scopeNodeId(), from, to, schedule.ownerPrincipalId(), Optional.empty(), new byte[0], Map.of("kind", kind.name()), detail);
    }

    private static UUID connectionTenant(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT current_tenant_id()"); ResultSet r = statement.executeQuery()) {
            r.next();
            return r.getObject(1, UUID.class);
        }
    }

    private static UUID insertArtifact(Connection connection, UUID tenantId, Optional<UUID> scheduleId, ReportRenderer.Kind kind, UUID recipient, Optional<UUID> scope,
            LocalDate from, LocalDate to, UUID generatedBy, Optional<String> ref, byte[] bytes, Map<String, Object> basis, String failure) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO report_artifact (tenant_id, schedule_id, report_kind, recipient_principal_id, scope_node_id, period_from, period_to, generated_by, "
                        + "storage_ref, byte_size, sha256, status, failure_detail, basis) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id")) {
            statement.setObject(1, tenantId);
            statement.setObject(2, scheduleId.orElse(null));
            statement.setString(3, kind.name());
            statement.setObject(4, recipient);
            statement.setObject(5, scope.orElse(null));
            statement.setObject(6, from);
            statement.setObject(7, to);
            statement.setObject(8, generatedBy);
            statement.setString(9, ref.orElse(null));
            statement.setLong(10, bytes.length);
            statement.setString(11, bytes.length == 0 ? null : sha256(bytes));
            statement.setString(12, failure == null ? "GENERATED" : "FAILED");
            statement.setString(13, failure);
            statement.setString(14, Json.write(basis));
            try (ResultSet r = statement.executeQuery()) {
                r.next();
                return r.getObject(1, UUID.class);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Schedule one(Connection connection, UUID id) throws SQLException {
        List<Schedule> found = schedules(connection, "WHERE s.id = ?", id);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("no such schedule");
        }
        return found.get(0);
    }

    private static List<Schedule> schedules(Connection connection, String clause, Object parameter) throws SQLException {
        List<Schedule> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT s.id, s.code, s.display_name, s.report_kind, s.scope_node_id, (SELECT string_agg(an.name, ' › ' ORDER BY cl.depth DESC) FROM org_closure cl "
                        + "JOIN org_node an ON an.id = cl.ancestor_id WHERE cl.descendant_id = s.scope_node_id), s.period_days, s.cadence, s.run_hour_utc, s.run_weekday, "
                        + "s.run_day_of_month, s.owner_principal_id, coalesce(p.display_name, p.username, '?'), s.lifecycle_state, s.next_run_at, s.last_run_at, s.last_outcome, s.row_version "
                        + "FROM report_schedule s LEFT JOIN principal p ON p.id = s.owner_principal_id " + clause)) {
            if (parameter != null) {
                statement.setObject(1, parameter);
            }
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    UUID id = r.getObject(1, UUID.class);
                    out.add(new Schedule(id, r.getString(2), r.getString(3), r.getString(4), Optional.ofNullable(r.getObject(5, UUID.class)),
                            Optional.ofNullable(r.getString(6)), r.getInt(7), r.getString(8), r.getInt(9), r.getInt(10), r.getInt(11), r.getObject(12, UUID.class),
                            r.getString(13), r.getString(14), Optional.ofNullable(r.getObject(15, OffsetDateTime.class)), Optional.ofNullable(r.getObject(16, OffsetDateTime.class)),
                            Optional.ofNullable(r.getString(17)), recipients(connection, id), r.getInt(18)));
                }
            }
        }
        return out;
    }

    private static List<Recipient> recipients(Connection connection, UUID scheduleId) throws SQLException {
        List<Recipient> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT rr.principal_id, coalesce(p.display_name, p.username, '?'), rr.dropped_at, rr.dropped_reason FROM report_schedule_recipient rr "
                        + "LEFT JOIN principal p ON p.id = rr.principal_id WHERE rr.schedule_id = ? ORDER BY rr.dropped_at NULLS FIRST, 2")) {
            statement.setObject(1, scheduleId);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.add(new Recipient(r.getObject(1, UUID.class), r.getString(2), Optional.ofNullable(r.getObject(3, OffsetDateTime.class)), Optional.ofNullable(r.getString(4))));
                }
            }
        }
        return out;
    }

    private static List<Artifact> artifacts(Connection connection, String clause, Object... parameters) throws SQLException {
        List<Artifact> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT a.id, a.schedule_id, s.display_name, a.report_kind, a.recipient_principal_id, coalesce(p.display_name, p.username, '?'), "
                        + "(SELECT string_agg(an.name, ' › ' ORDER BY cl.depth DESC) FROM org_closure cl JOIN org_node an ON an.id = cl.ancestor_id WHERE cl.descendant_id = a.scope_node_id), "
                        + "a.period_from, a.period_to, a.generated_at, a.status, a.failure_detail, a.byte_size, a.sha256, a.storage_ref IS NOT NULL, a.basis::text, a.download_count "
                        + "FROM report_artifact a LEFT JOIN report_schedule s ON s.id = a.schedule_id LEFT JOIN principal p ON p.id = a.recipient_principal_id " + clause)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.add(new Artifact(r.getObject(1, UUID.class), Optional.ofNullable(r.getObject(2, UUID.class)), Optional.ofNullable(r.getString(3)), r.getString(4),
                            r.getObject(5, UUID.class), r.getString(6), Optional.ofNullable(r.getString(7)), r.getObject(8, LocalDate.class), r.getObject(9, LocalDate.class),
                            r.getObject(10, OffsetDateTime.class), r.getString(11), Optional.ofNullable(r.getString(12)), r.getLong(13), Optional.ofNullable(r.getString(14)),
                            r.getBoolean(15), Json.readObject(r.getString(16)), r.getInt(17)));
                }
            }
        }
        return out;
    }
}
