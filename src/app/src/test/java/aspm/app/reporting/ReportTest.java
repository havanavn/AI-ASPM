package aspm.app.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.app.persistence.AllMigrations;
import aspm.app.persistence.TenantConnections;
import aspm.app.resource.ObjectStore;
import aspm.app.resource.VulnerabilityQuery;
import aspm.app.runtime.Principal;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Scheduled reports and the audit evidence artifact against the platform's schema and a fake object
 * store. {@code PRD-DSH-043}, {@code PRD-DSH-045}, {@code PRD-DSH-046}, {@code PRD-DSH-047}, {@code PRD-DSH-048}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportTest {

    private static final UUID TENANT = UUID.fromString("7d000000-0000-4000-8000-000000000001");
    private static final UUID ADMIN = UUID.fromString("7d000000-0000-4000-8000-0000000000ad");
    private static final UUID ALICE = UUID.fromString("7d000000-0000-4000-8000-00000000a11c");
    private static final UUID BOB = UUID.fromString("7d000000-0000-4000-8000-000000000b0b");

    private DataSource dataSource;
    private HttpServer s3;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private ReportService reports;
    private ReportWorker worker;
    private Principal admin;
    private UUID root;
    private UUID unitA;
    private UUID unitB;
    private UUID readerRole;
    private UUID bobAssignment;

    @BeforeAll
    void start() throws Exception {
        dataSource = AllMigrations.dataSource();
        s3 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s3.createContext("/", exchange -> {
            String key = exchange.getRequestURI().getPath();
            if ("PUT".equals(exchange.getRequestMethod())) {
                objects.put(key, exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(200, -1);
            } else if (objects.containsKey(key)) {
                byte[] body = objects.get(key);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        s3.start();
        ObjectStore store = new ObjectStore(Map.of("ASPM_OBJECTSTORE_ENDPOINT", "http://127.0.0.1:" + s3.getAddress().getPort(),
                "ASPM_OBJECTSTORE_USER", "test", "ASPM_OBJECTSTORE_PASSWORD", "test"));
        reports = new ReportService(dataSource, store, new ReportRenderer(dataSource, new VulnerabilityQuery(dataSource)));
        worker = new ReportWorker(dataSource, TENANT, reports);

        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "INSERT INTO tenant (id, display_name, lifecycle_state, residency_region, key_reference, entitlement_tier) "
                    + "VALUES (?, 'Report test', 'ACTIVE', 'VN', 'vault://test', 'STANDARD') ON CONFLICT (id) DO NOTHING", TENANT);
            execute(c, "INSERT INTO password_policy (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", TENANT);
            for (Object[] p : new Object[][] {{ADMIN, "rpt.admin", "Admin"}, {ALICE, "rpt.alice", "Alice"}, {BOB, "rpt.bob", "Bob"}}) {
                execute(c, "INSERT INTO principal (id, tenant_id, kind, username, email, display_name, lifecycle_state) VALUES (?, ?, 'HUMAN', ?, ?, ?, 'ACTIVE')",
                        p[0], TENANT, p[1], p[1] + "@example.com", p[2]);
            }
            UUID typeId = returning(c, "INSERT INTO org_node_type (tenant_id, code, label_i18n, ordinal, may_own_assets, may_scope_work) "
                    + "VALUES (?, 'UNIT', '{\"en\":\"Unit\"}', 1, true, true) RETURNING id", TENANT);
            root = returning(c, "INSERT INTO org_node (tenant_id, type_id, name, criticality_mode) VALUES (?, ?, 'Group', 'INHERITED') RETURNING id", TENANT, typeId);
            unitA = returning(c, "INSERT INTO org_node (tenant_id, type_id, parent_id, name, criticality_mode) VALUES (?, ?, ?, 'Unit A', 'INHERITED') RETURNING id", TENANT, typeId, root);
            unitB = returning(c, "INSERT INTO org_node (tenant_id, type_id, parent_id, name, criticality_mode) VALUES (?, ?, ?, 'Unit B', 'INHERITED') RETURNING id", TENANT, typeId, root);
            // The catalogue row the deployment seed (deploy/seed-identity.sql) provides; migrations do not.
            execute(c, "INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up) "
                    + "VALUES ('vul.finding.read', 'vul', '{\"en\":\"Read findings\"}'::jsonb, false, false) ON CONFLICT (code) DO NOTHING");
            // A reader role: what a recipient must hold. Alice over Unit A, Bob over Unit B.
            readerRole = returning(c, "INSERT INTO role (tenant_id, code, label_i18n) VALUES (?, 'RPT_READER', '{\"en\":\"Reader\"}') RETURNING id", TENANT);
            execute(c, "INSERT INTO role_permission (tenant_id, role_id, permission_code) VALUES (?, ?, 'vul.finding.read')", TENANT, readerRole);
            execute(c, "INSERT INTO role_assignment (tenant_id, principal_id, role_id, scope_mode, scope_node_id) VALUES (?, ?, ?, 'SUBTREE', ?)", TENANT, ALICE, readerRole, unitA);
            bobAssignment = returning(c, "INSERT INTO role_assignment (tenant_id, principal_id, role_id, scope_mode, scope_node_id) VALUES (?, ?, ?, 'SUBTREE', ?) RETURNING id",
                    TENANT, BOB, readerRole, unitB);
            finding(c, unitA, "SQL injection in login", "Payload ' OR 1=1 -- SECRET-POC-TEXT");
            finding(c, unitB, "Missing rate limit", "Brute force possible");
            c.commit();
        }
        admin = new Principal(TENANT, ADMIN, Set.of(ReportService.MANAGE, ReportService.EVIDENCE, "vul.finding.read"), Set.of(root), true, false, false);
    }

    @AfterAll
    void stop() {
        if (s3 != null) {
            s3.stop(0);
        }
    }

    @Test
    @DisplayName("PRD-DSH-043: a run renders one file PER RECIPIENT, each within that recipient's own scope, stored under a per-tenant prefix, and tells each recipient")
    void perRecipient() throws Exception {
        ReportService.Schedule schedule = as(() -> reports.create(admin, "weekly-findings", "Weekly findings", "FINDING_REGISTER", Optional.empty(), 30,
                "WEEKLY", 6, 1, 1, List.of(ALICE, BOB), Optional.empty()));
        assertEquals("ACTIVE", schedule.lifecycleState());
        assertEquals(2, schedule.recipients().size());
        as(() -> reports.runNow(admin, schedule.id()));
        assertEquals(1, worker.tick());

        List<ReportService.Artifact> all = reports.artifacts(admin);
        assertEquals(2, all.stream().filter(a -> a.scheduleId().map(schedule.id()::equals).orElse(false)).count(), "one artifact per recipient");
        ReportService.Artifact forAlice = all.stream().filter(a -> a.recipientPrincipalId().equals(ALICE)).findFirst().orElseThrow();
        ReportService.Artifact forBob = all.stream().filter(a -> a.recipientPrincipalId().equals(BOB)).findFirst().orElseThrow();
        assertEquals("GENERATED", forAlice.status());
        assertTrue(forAlice.stored());
        assertTrue(objects.keySet().stream().anyMatch(k -> k.startsWith("/aspm-export/tenants/" + TENANT + "/reports/weekly-findings/" + ALICE + "/")), objects.keySet().toString());

        // Alice's file has Alice's finding and not Bob's; Bob's the reverse. The same schedule, two different truths.
        Principal alice = new Principal(TENANT, ALICE, Set.of("vul.finding.read"), Set.of(unitA), false, false, false);
        Principal bob = new Principal(TENANT, BOB, Set.of("vul.finding.read"), Set.of(unitB), false, false, false);
        String aliceText = text(reports.download(alice, forAlice.id()).orElseThrow().bytes());
        String bobText = text(reports.download(bob, forBob.id()).orElseThrow().bytes());
        assertTrue(aliceText.contains("SQL injection in login") && !aliceText.contains("Missing rate limit"), "Alice sees Unit A only");
        assertTrue(bobText.contains("Missing rate limit") && !bobText.contains("SQL injection in login"), "Bob sees Unit B only");
        assertTrue(aliceText.contains("Aggregation basis"), "the About sheet is there (PRD-DSH-047)");
        assertTrue(reports.download(bob, forAlice.id()).isEmpty(), "a file rendered as Alice is not served to Bob");
        assertEquals(1L, count("SELECT count(*) FROM notification WHERE recipient_principal_id = ? AND event_kind = 'report.ready' AND subject_id = ?", ALICE, forAlice.id()));
        assertEquals(2L, count("SELECT count(*) FROM audit_event WHERE event_type = 'report.generated' AND object_id IN (?, ?)", forAlice.id(), forBob.id()),
                "each generation is audited, for the recipient it was rendered as");
        assertTrue(reports.list(admin).stream().filter(s -> s.id().equals(schedule.id())).findFirst().orElseThrow().nextRunAt().orElseThrow()
                .toInstant().isAfter(Instant.now()), "the next run is scheduled ahead");
    }

    @Test
    @DisplayName("PRD-DSH-045: a recipient who lost access is dropped with the reason and the owner is told; nothing is rendered for them")
    void lostAccessIsDroppedAndOwnerTold() throws Exception {
        ReportService.Schedule schedule = as(() -> reports.create(admin, "unit-b-exceptions", "Unit B exceptions", "EXCEPTION_REGISTER", Optional.of(unitB), 90,
                "DAILY", 3, 1, 1, List.of(BOB), Optional.empty()));
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "UPDATE role_assignment SET revoked_at = now(), revoked_reason = 'left the team' WHERE id = ?", bobAssignment);
            c.commit();
        }
        try {
            as(() -> reports.runNow(admin, schedule.id()));
            worker.tick();
            ReportService.Schedule after = reports.list(admin).stream().filter(s -> s.id().equals(schedule.id())).findFirst().orElseThrow();
            ReportService.Recipient bob = after.recipients().get(0);
            assertTrue(bob.droppedAt().isPresent(), "dropped");
            assertTrue(bob.droppedReason().orElseThrow().contains("vul.finding.read"), bob.droppedReason().orElseThrow());
            assertEquals(1L, count("SELECT count(*) FROM notification WHERE recipient_principal_id = ? AND event_kind = 'report.recipient_dropped' AND subject_id = ?", ADMIN, schedule.id()));
            assertEquals(0L, count("SELECT count(*) FROM report_artifact WHERE schedule_id = ?", schedule.id()), "nothing rendered for a dropped recipient");
            assertTrue(after.lastOutcome().orElseThrow().contains("1 dropped"), after.lastOutcome().orElseThrow());
        } finally {
            try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
                execute(c, "UPDATE role_assignment SET revoked_at = NULL, revoked_reason = NULL WHERE id = ?", bobAssignment);
                c.commit();
            }
        }
    }

    @Test
    @DisplayName("PRD-DSH-045: a recipient whose scope no longer reaches the schedule's subtree is dropped too")
    void scopeNoLongerReaches() throws Exception {
        // Alice reaches Unit A; the schedule is about Unit B.
        ReportService.Schedule schedule = as(() -> reports.create(admin, "unit-b-coverage", "Unit B coverage", "COVERAGE", Optional.of(unitB), 30,
                "MONTHLY", 6, 1, 1, List.of(ALICE), Optional.empty()));
        as(() -> reports.runNow(admin, schedule.id()));
        worker.tick();
        ReportService.Schedule after = reports.list(admin).stream().filter(s -> s.id().equals(schedule.id())).findFirst().orElseThrow();
        assertTrue(after.recipients().get(0).droppedReason().orElseThrow().contains("no longer reaches"), after.recipients().get(0).droppedReason().orElseThrow());
    }

    @Test
    @DisplayName("PRD-DSH-046 / PRD-DSH-047 / PRD-DSH-048: audit evidence is assembled for a scope and period, records its basis, is audited, and carries no finding text")
    void auditEvidence() throws Exception {
        ReportService.Generated evidence = as(() -> reports.generateEvidence(admin, Optional.of(unitA), LocalDate.now(ZoneOffset.UTC).minusDays(30), LocalDate.now(ZoneOffset.UTC)));
        String text = text(evidence.bytes());
        for (String sheet : List.of("Assessments", "Findings", "Exceptions", "Service levels", "Access review", "Configuration changes", "Evidence references")) {
            assertTrue(text.contains("Rows in '" + sheet + "'"), "About lists " + sheet);
        }
        assertTrue(text.contains("SQL injection in login"), "the finding's identity is there");
        assertFalse(text.contains("SECRET-POC-TEXT"), "PRD-DSH-048: the finding's proof of concept is not");
        assertTrue(text.contains("Group › Unit A"), "the scope is recorded");
        assertTrue(text.contains("rpt.alice"), "access review lists who held what over the scope");
        assertEquals(1L, count("SELECT count(*) FROM audit_event WHERE event_type = 'report.generated' AND object_id = ?", evidence.artifactId()), "PRD-DSH-047: generation audited");
        ReportService.Artifact row = reports.artifacts(admin).stream().filter(a -> a.id().equals(evidence.artifactId())).findFirst().orElseThrow();
        assertEquals("AUDIT_EVIDENCE", row.reportKind());
        assertEquals(unitA.toString(), row.basis().get("scope"));
        assertTrue(row.basis().containsKey("aggregation_basis"));
        assertThrows(IllegalArgumentException.class, () -> as(() -> reports.generateEvidence(admin, Optional.of(unitA), LocalDate.now(), LocalDate.now().minusDays(1))));
    }

    @Test
    @DisplayName("the next run is strictly after now, on the configured day and hour")
    void nextRun() {
        Instant monday9 = Instant.parse("2026-09-14T09:00:00Z");   // a Monday
        assertEquals(Instant.parse("2026-09-15T06:00:00Z"), ReportService.nextRun("DAILY", 6, 1, 1, monday9), "today's 06:00 has passed");
        assertEquals(Instant.parse("2026-09-14T12:00:00Z"), ReportService.nextRun("DAILY", 12, 1, 1, monday9), "today's 12:00 has not");
        assertEquals(Instant.parse("2026-09-17T06:00:00Z"), ReportService.nextRun("WEEKLY", 6, 4, 1, monday9), "Thursday");
        assertEquals(Instant.parse("2026-09-21T06:00:00Z"), ReportService.nextRun("WEEKLY", 6, 1, 1, monday9), "next Monday, not today");
        assertEquals(Instant.parse("2026-10-01T06:00:00Z"), ReportService.nextRun("MONTHLY", 6, 1, 1, monday9));
        assertEquals(Instant.parse("2026-09-28T06:00:00Z"), ReportService.nextRun("MONTHLY", 6, 1, 28, monday9));
    }

    // ==============================================================================================

    private static String text(byte[] xlsx) throws Exception {
        StringBuilder out = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().endsWith(".xml")) {
                    out.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        return out.toString().replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"");
    }

    private static UUID finding(Connection c, UUID scope, String title, String poc) throws SQLException {
        byte[] digest = new byte[32];
        new java.security.SecureRandom().nextBytes(digest);
        return returning(c, "INSERT INTO finding (tenant_id, fingerprint_digest, fingerprint_algorithm_version, finding_class, title, description, proof_of_concept, "
                + "state, source_tool, raw_source_record_ref, first_detected_at, last_detected_at, scope_node_id) "
                + "VALUES (?, ?, 1, 'CODE', ?, 'desc', ?, 'OPEN', 'manual-entry', 'test', now() - interval '2 days', now() - interval '1 day', ?) RETURNING id",
                TENANT, digest, title, poc, scope);
    }

    private static <T> T as(java.util.concurrent.Callable<T> body) throws SQLException {
        var context = aspm.kernel.tenantcontext.contract.TenantContext.of(new aspm.sharedkernel.TenantId(TENANT), "vn",
                aspm.kernel.tenantcontext.contract.EstablishedFrom.AUTHENTICATED_PRINCIPAL, Instant.now());
        try {
            return aspm.kernel.tenantcontext.contract.TenantContextHolder.callWith(context, body);
        } catch (SQLException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
        }
    }

    private static UUID returning(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getObject(1, UUID.class);
            }
        }
    }

    private long count(String sql, Object... parameters) throws SQLException {
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }
}
