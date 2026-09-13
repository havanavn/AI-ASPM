package aspm.app.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.app.egress.EgressGuard;
import aspm.app.persistence.AllMigrations;
import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Json;
import aspm.app.runtime.Principal;
import aspm.app.secrets.Secrets;
import aspm.module.integration.domain.FailureClass;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Outbound connectors end to end against the platform's schema and a fake Jira: lifecycle, the
 * reference, minimal content, observation, divergence, the credential circuit, rotation, retirement.
 * {@code PRD-CON-017}, {@code PRD-CON-019}, {@code PRD-CON-020}, {@code PRD-CON-021}, {@code PRD-CON-022},
 * {@code PRD-CON-024}, {@code PRD-CON-025}, {@code PRD-CON-028}, {@code PRD-CON-029}, {@code PRD-CON-032},
 * {@code PRD-CON-033}, {@code PRD-CON-037}, {@code PRD-CON-038}, {@code PRD-CON-042}, {@code PRD-CON-043},
 * {@code PRD-CON-044}, {@code PRD-CON-045}, ADR-040.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectorTest {

    private static final UUID TENANT = UUID.fromString("7c000000-0000-4000-8000-000000000001");
    private static final UUID ADMIN = UUID.fromString("7c000000-0000-4000-8000-0000000000ad");
    private static final UUID ALICE = UUID.fromString("7c000000-0000-4000-8000-00000000a11c");
    private static final String JIRA = "https://jira.test";

    private DataSource dataSource;
    private HttpServer jira;
    private String loopback;
    private final List<String> posted = new CopyOnWriteArrayList<>();
    private final AtomicInteger authAnswer = new AtomicInteger(200);
    private final AtomicInteger issueCounter = new AtomicInteger(0);
    /** key → [status name, status category]; absent = deleted. */
    private final Map<String, String[]> issues = new ConcurrentHashMap<>();
    private final List<String> authorizationsSeen = new CopyOnWriteArrayList<>();

    private Secrets secrets;
    private ConnectorService connectors;
    private OutboundReferenceService references;
    private ConnectorWorker worker;
    private Principal admin;
    private Principal alice;
    private UUID root;
    private UUID childA;
    private UUID childB;
    private UUID findingA;
    private UUID findingA2;
    private UUID findingB;

    @BeforeAll
    void start() throws Exception {
        dataSource = AllMigrations.dataSource();
        jira = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jira.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            authorizationsSeen.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] out;
            int status;
            if (authAnswer.get() != 200) {
                status = authAnswer.get();
                out = "{}".getBytes(StandardCharsets.UTF_8);
            } else if (method.equals("GET") && path.equals("/rest/api/2/myself")) {
                status = 200;
                out = "{\"accountId\":\"x\"}".getBytes(StandardCharsets.UTF_8);
            } else if (method.equals("POST") && path.equals("/rest/api/2/issue")) {
                posted.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String key = "SEC-" + issueCounter.incrementAndGet();
                issues.put(key, new String[] {"To Do", "new"});
                status = 201;
                out = ("{\"id\":\"1000" + issueCounter.get() + "\",\"key\":\"" + key + "\",\"self\":\"" + JIRA + "/rest/api/2/issue/" + key + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
            } else if (method.equals("GET") && path.startsWith("/rest/api/2/issue/")) {
                String key = path.substring("/rest/api/2/issue/".length());
                String[] state = issues.get(key);
                if (state == null) {
                    status = 404;
                    out = "{}".getBytes(StandardCharsets.UTF_8);
                } else {
                    status = 200;
                    out = ("{\"key\":\"" + key + "\",\"fields\":{\"status\":{\"name\":\"" + state[0] + "\",\"statusCategory\":{\"key\":\"" + state[1] + "\"}}}}")
                            .getBytes(StandardCharsets.UTF_8);
                }
            } else {
                status = 404;
                out = "{}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        jira.start();
        loopback = "http://127.0.0.1:" + jira.getAddress().getPort();

        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        secrets = Secrets.fromEnvironment(Map.of("ASPM_ENVIRONMENT", "production", "ASPM_SECRETS_PROVIDERS", "sealed",
                "ASPM_CREDENTIAL_KEY", Base64.getEncoder().encodeToString(key)), dataSource);
        EgressGuard egress = EgressGuard.permittingLoopbackForTests(host -> {
            try {
                return new java.net.InetAddress[] {java.net.InetAddress.getByName("127.0.0.1")};
            } catch (java.net.UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        });
        // The real Jira Cloud adapter, with its https destination rewritten to the fake at call time — the
        // configuration keeps the https URL the CHECK and the guard demand.
        ConnectorAdapter real = TrackerAdapters.all(egress).stream().filter(a -> a.kind().equals("JIRA_CLOUD")).findFirst().orElseThrow();
        ConnectorAdapter rewritten = new ConnectorAdapter() {
            private Map<String, Object> to(Map<String, Object> config) {
                Map<String, Object> copy = new java.util.LinkedHashMap<>(config);
                copy.put("baseUrl", String.valueOf(config.get("baseUrl")).replace(JIRA, loopback));
                return copy;
            }

            @Override public String kind() { return real.kind(); }
            @Override public int version() { return real.version(); }
            @Override public String label() { return real.label(); }
            @Override public List<String> minimumPermissions() { return real.minimumPermissions(); }
            @Override public Map<String, String> outboundContent() { return real.outboundContent(); }
            @Override public String credentialLabel() { return real.credentialLabel(); }
            @Override public Map<String, Object> validate(Map<String, Object> config, boolean credentialPresent) { return real.validate(config, credentialPresent); }
            @Override public Result<Created> create(Map<String, Object> config, Optional<char[]> credential, Reference reference) {
                Result<Created> r = real.create(to(config), credential, reference);
                // The link the adapter builds names the fake; the row should carry the configured host, as production would.
                return r.value().map(c -> Result.ok(new Created(c.externalId(), c.externalKey(), c.url().map(u -> u.replace(loopback, JIRA)), c.state(), c.resolved()), r.detail()))
                        .orElse(r);
            }
            @Override public Result<Observation> observe(Map<String, Object> config, Optional<char[]> credential, String externalId) { return real.observe(to(config), credential, externalId); }
            @Override public Result<String> probe(Map<String, Object> config, Optional<char[]> credential) { return real.probe(to(config), credential); }
        };
        connectors = new ConnectorService(dataSource, secrets, List.of(rewritten), Set.of("JIRA_CLOUD"));
        references = new OutboundReferenceService(dataSource);
        worker = new ConnectorWorker(dataSource, TENANT, secrets, List.of(rewritten), Set.of("JIRA_CLOUD"), Optional.of("https://aspm.test"), 14);

        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "INSERT INTO tenant (id, display_name, lifecycle_state, residency_region, key_reference, entitlement_tier) "
                    + "VALUES (?, 'Connector test', 'ACTIVE', 'VN', 'vault://test', 'STANDARD') ON CONFLICT (id) DO NOTHING", TENANT);
            execute(c, "INSERT INTO password_policy (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", TENANT);
            for (Object[] p : new Object[][] {{ADMIN, "con.admin", "Admin"}, {ALICE, "con.alice", "Alice"}}) {
                execute(c, "INSERT INTO principal (id, tenant_id, kind, username, email, display_name, lifecycle_state) "
                        + "VALUES (?, ?, 'HUMAN', ?, ?, ?, 'ACTIVE')", p[0], TENANT, p[1], p[1] + "@example.com", p[2]);
            }
            UUID typeId = returning(c, "INSERT INTO org_node_type (tenant_id, code, label_i18n, ordinal, may_own_assets, may_scope_work) "
                    + "VALUES (?, 'UNIT', '{\"en\":\"Unit\"}', 1, true, true) RETURNING id", TENANT);
            root = returning(c, "INSERT INTO org_node (tenant_id, type_id, name, criticality_mode) VALUES (?, ?, 'Group', 'INHERITED') RETURNING id", TENANT, typeId);
            childA = returning(c, "INSERT INTO org_node (tenant_id, type_id, parent_id, name, criticality_mode) VALUES (?, ?, ?, 'Unit A', 'INHERITED') RETURNING id", TENANT, typeId, root);
            childB = returning(c, "INSERT INTO org_node (tenant_id, type_id, parent_id, name, criticality_mode) VALUES (?, ?, ?, 'Unit B', 'INHERITED') RETURNING id", TENANT, typeId, root);
            findingA = finding(c, childA, "Reflected XSS in search", "The payload <script>alert('SECRET-EVIDENCE-TEXT')</script> is reflected.");
            findingA2 = finding(c, childA, "Weak TLS configuration", "TLS 1.0 accepted.");
            findingB = finding(c, childB, "Open redirect", "The next parameter is not validated.");
            c.commit();
        }
        admin = new Principal(TENANT, ADMIN, Set.of(ConnectorService.MANAGE, OutboundReferenceService.CREATE, "vul.finding.read"), Set.of(root), true, false, false);
        alice = new Principal(TENANT, ALICE, Set.of(OutboundReferenceService.CREATE, "vul.finding.read"), Set.of(childA), false, false, false);
    }

    @AfterAll
    void stop() {
        if (jira != null) {
            jira.stop(0);
        }
    }

    // ==============================================================================================

    @Test
    @DisplayName("PRD-CON-017 / PRD-CON-033: configuration is validated with a diagnosis — http, a private address, a malformed key are refused before anything is stored")
    void validationDiagnoses() {
        IllegalArgumentException http = assertThrows(IllegalArgumentException.class, () -> asAdmin(() -> connectors.create(admin, "bad-http", "Bad", "JIRA_CLOUD",
                Map.of("baseUrl", "http://jira.test", "projectKey", "SEC", "email", "bot@example.com"), Optional.of("t".toCharArray()),
                Optional.empty(), Optional.empty(), Optional.empty(), 60)));
        assertTrue(http.getMessage().contains("https"), http.getMessage());
        // The test guard resolves every name to loopback and permits it; the production guard is what a
        // deployment runs, so the private-range refusal is asserted against that one directly.
        IllegalArgumentException internal = assertThrows(IllegalArgumentException.class,
                () -> ConnectorHttp.baseUrl(EgressGuard.production(), "https://10.0.0.1", "the Jira base URL"));
        assertTrue(internal.getMessage().contains("egress"), internal.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> ConnectorHttp.baseUrl(EgressGuard.production(), "https://user:pw@jira.example.com", "the Jira base URL"),
                "credentials in the authority are refused");
        IllegalArgumentException key = assertThrows(IllegalArgumentException.class, () -> asAdmin(() -> connectors.create(admin, "bad-key", "Bad", "JIRA_CLOUD",
                Map.of("baseUrl", JIRA, "projectKey", "sec key", "email", "bot@example.com"), Optional.of("t".toCharArray()),
                Optional.empty(), Optional.empty(), Optional.empty(), 60)));
        assertTrue(key.getMessage().contains("project key"), key.getMessage());
        assertThrows(IllegalArgumentException.class, () -> asAdmin(() -> connectors.create(admin, "no-cred", "Bad", "JIRA_CLOUD",
                Map.of("baseUrl", JIRA, "projectKey", "SEC", "email", "bot@example.com"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 60)),
                "a kind that needs a credential refuses to exist without one");
        assertThrows(IllegalArgumentException.class, () -> asAdmin(() -> connectors.create(admin, "gl", "GitLab", "GITLAB",
                Map.of("projectId", "1"), Optional.of("t".toCharArray()), Optional.empty(), Optional.empty(), Optional.empty(), 60)),
                "PRD-CON-054: a kind this deployment disabled is refused, by name");
    }

    @Test
    @DisplayName("DOC-21 §3 / §10: configured → active by probe; a person asks for a reference; the worker creates the ticket with a reference and a summary, and nothing else")
    void lifecycleAndReference() throws Exception {
        ConnectorService.Connector created = asAdmin(() -> connectors.create(admin, "jira-a", "Jira for Unit A", "JIRA_CLOUD",
                Map.of("baseUrl", JIRA + "/", "projectKey", "SEC", "email", "bot@example.com"), Optional.of("api-token-1".toCharArray()),
                Optional.empty(), Optional.of(childA), Optional.empty(), 60));
        assertEquals("CONFIGURED", created.lifecycleState());
        assertTrue(created.credentialHeld());
        assertEquals(JIRA, created.config().get("baseUrl"), "normalized: no trailing slash");
        assertEquals("Task", created.config().get("issueType"), "the default issue type is filled in");
        assertEquals(0L, count("SELECT count(*) FROM connector WHERE id = ? AND config::text LIKE '%api-token%'", created.id()),
                "PRD-CON-021: the credential is not in the configuration");

        // Not active: a reference is refused, with the state named.
        IllegalArgumentException inactive = assertThrows(IllegalArgumentException.class, () -> asAlice(() -> references.create(alice, findingA, created.id())));
        assertTrue(inactive.getMessage().contains("configured"), inactive.getMessage());

        ConnectorService.Connector active = asAdmin(() -> connectors.transition(admin, created.id(), "ACTIVE"));
        assertEquals("ACTIVE", active.lifecycleState());
        assertTrue(active.health().lastSuccessAt().isPresent(), "the activation probe is a recorded success (PRD-CON-028)");

        // Alice, scoped to Unit A, asks. The row exists at once as PENDING; the worker fills it in.
        OutboundReferenceService.Reference pending = asAlice(() -> references.create(alice, findingA, created.id()));
        assertEquals("PENDING", pending.status());
        assertTrue(references.forFinding(alice, findingA).orElseThrow().offers().stream().noneMatch(o -> o.connectorId().equals(created.id())),
                "the same connector is not offered twice");
        posted.clear();
        assertTrue(worker.tick() >= 1);
        OutboundReferenceService.Reference linked = references.forFinding(alice, findingA).orElseThrow().references().get(0);
        assertEquals("LINKED", linked.status());
        String key = linked.externalKey().orElseThrow();
        assertTrue(key.startsWith("SEC-"), key);
        assertEquals(JIRA + "/browse/" + key, linked.externalUrl().orElseThrow());
        assertEquals("Open", linked.externalState().orElseThrow());

        // PRD-CON-045 / PRD-CON-037: summary, severity, scope, tool, link. Never the description.
        Map<String, Object> body = Json.readObject(posted.get(0));
        @SuppressWarnings("unchecked") Map<String, Object> fields = (Map<String, Object>) body.get("fields");
        assertTrue(String.valueOf(fields.get("summary")).startsWith("[ASPM "), fields.toString());
        assertTrue(String.valueOf(fields.get("summary")).contains("Reflected XSS in search"));
        String description = String.valueOf(fields.get("description"));
        assertTrue(description.contains("Group › Unit A"), description);
        assertTrue(description.contains("https://aspm.test/pipeline/findings/" + findingA), description);
        assertTrue(description.contains("not updated from this ticket"), description);
        assertFalse(posted.get(0).contains("SECRET-EVIDENCE-TEXT"), "the finding's description never leaves");
        assertFalse(posted.get(0).contains("<script>"), "the finding's payload never leaves");
        assertEquals("DONE", scalar("SELECT status FROM outbound_operation WHERE reference_id = ? ORDER BY created_at DESC LIMIT 1", linked.id()));
        assertEquals(1L, count("SELECT count(*) FROM audit_event WHERE event_type LIKE 'outbound_reference.%' AND object_id = ?", linked.id()),
                "the person's request is the audited action");
    }

    @Test
    @DisplayName("PRD-CON-038: a connector scoped to one unit refuses a finding from another, before anything is queued")
    void scopeIsEnforcedBeforeQueueing() throws Exception {
        ConnectorService.Connector scoped = activeConnector("jira-scope", Optional.of(childA));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> asAdmin(() -> references.create(admin, findingB, scoped.id())));
        assertTrue(refused.getMessage().contains("PRD-CON-038"), refused.getMessage());
        assertEquals(0L, count("SELECT count(*) FROM outbound_reference WHERE connector_id = ?", scoped.id()));
        // And a caller whose own scope does not reach the finding sees nothing at all — the same answer as a missing id.
        assertTrue(references.forFinding(alice, findingB).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> asAlice(() -> references.create(alice, findingB, scoped.id())));
    }

    @Test
    @DisplayName("PRD-CON-042 / PRD-CON-043 / PRD-CON-044: what the tracker says is recorded, compared and surfaced — the finding is never touched")
    void divergenceIsSurfacedNotApplied() throws Exception {
        ConnectorService.Connector connector = activeConnector("jira-div", Optional.empty());
        OutboundReferenceService.Reference ref = asAlice(() -> references.create(alice, findingA2, connector.id()));
        worker.tick();
        String key = scalar("SELECT external_key FROM outbound_reference WHERE id = ?", ref.id());

        // The ticket is closed in Jira. The next observation records a divergence and tells people.
        issues.put(key, new String[] {"Done", "done"});
        backdateObservation(ref.id());
        worker.tick();
        assertEquals("CLOSED_EXTERNALLY", scalar("SELECT divergence_kind FROM outbound_reference WHERE id = ?", ref.id()));
        assertEquals("Done", scalar("SELECT external_state FROM outbound_reference WHERE id = ?", ref.id()));
        assertEquals("OPEN", scalar("SELECT lifecycle_state FROM finding WHERE id = ?", findingA2), "PRD-CON-042: the platform record is authoritative");
        assertEquals("OPEN", scalar("SELECT state FROM finding WHERE id = ?", findingA2));
        assertEquals(1L, count("SELECT count(*) FROM notification WHERE recipient_principal_id = ? AND event_kind = 'integration.divergence' AND subject_id = ?", ALICE, findingA2));
        assertEquals(1L, count("SELECT count(*) FROM notification WHERE recipient_principal_id = ? AND event_kind = 'integration.divergence' AND subject_id = ?", ADMIN, findingA2),
                "the connector owner is told too");
        assertEquals(1, references.divergences(alice).size());

        // Observed again while the divergence is open: no second divergence, no second notification.
        backdateObservation(ref.id());
        worker.tick();
        assertEquals(1L, count("SELECT count(*) FROM notification WHERE recipient_principal_id = ? AND event_kind = 'integration.divergence' AND subject_id = ?", ALICE, findingA2));

        // A person decides. The note is required; the finding is still untouched.
        assertThrows(IllegalArgumentException.class, () -> asAlice(() -> references.resolveDivergence(alice, ref.id(), "ok")));
        OutboundReferenceService.Reference resolved = asAlice(() -> references.resolveDivergence(alice, ref.id(),
                "Ticket closed by the team without a fix; reopened it in Jira and told the owner. Finding stays open."));
        assertTrue(resolved.divergenceKind().isPresent(), "the record of the divergence is kept");
        assertEquals(0, references.divergences(alice).size());
        assertEquals("OPEN", scalar("SELECT lifecycle_state FROM finding WHERE id = ?", findingA2));

        // PRD-CON-044: the ticket is deleted. Surfaced as its own kind; the finding still untouched.
        issues.remove(key);
        backdateObservation(ref.id());
        worker.tick();
        assertEquals("DELETED_EXTERNALLY", scalar("SELECT divergence_kind FROM outbound_reference WHERE id = ?", ref.id()));
        assertEquals("DELETED", scalar("SELECT external_state FROM outbound_reference WHERE id = ?", ref.id()));
        assertEquals("OPEN", scalar("SELECT lifecycle_state FROM finding WHERE id = ?", findingA2));
    }

    @Test
    @DisplayName("the four divergence kinds, from what the tracker says and where the finding is")
    void divergenceKinds() {
        var open = new ConnectorAdapter.Observation(Optional.of("Open"), false);
        var done = new ConnectorAdapter.Observation(Optional.of("Done"), true);
        assertEquals(Optional.empty(), ConnectorWorker.divergence(open, "OPEN", null), "agreeing: open both sides");
        assertEquals(Optional.empty(), ConnectorWorker.divergence(done, "CLOSED", true), "agreeing: closed both sides");
        assertEquals(Optional.of("CLOSED_EXTERNALLY"), ConnectorWorker.divergence(done, "OPEN", false));
        assertEquals(Optional.of("REOPENED_EXTERNALLY"), ConnectorWorker.divergence(open, "CLOSED", true));
        assertEquals(Optional.of("STATE_MISMATCH"), ConnectorWorker.divergence(open, "CLOSED", false));
        assertEquals(Optional.of("DELETED_EXTERNALLY"), ConnectorWorker.divergence(ConnectorAdapter.Observation.gone(), "OPEN", null));
    }

    @Test
    @DisplayName("PRD-CON-024 / PRD-CON-029 / PRD-CON-022: a credential failure is not retried, opens the circuit, tells the owner, and stops until a proved rotation")
    void credentialFailureOpensCircuitUntilRotation() throws Exception {
        ConnectorService.Connector connector = activeConnector("jira-auth", Optional.empty());
        UUID subject = finding(childA, "Hardcoded key", "AKIA-EXAMPLE");
        OutboundReferenceService.Reference ref = asAlice(() -> references.create(alice, subject, connector.id()));

        authAnswer.set(401);
        try {
            worker.tick();
            assertEquals("FAILED", scalar("SELECT status FROM outbound_reference WHERE id = ?", ref.id()));
            assertEquals("AUTHENTICATION", scalar("SELECT last_failure_class FROM outbound_operation WHERE reference_id = ?", ref.id()));
            assertEquals("FAILED", scalar("SELECT status FROM outbound_operation WHERE reference_id = ?", ref.id()), "no retry (PRD-CON-024)");
            assertEquals("OPEN", scalar("SELECT circuit_state FROM connector_health WHERE connector_id = ?", connector.id()));
            assertEquals(1L, count("SELECT count(*) FROM notification WHERE recipient_principal_id = ? AND event_kind = 'integration.unhealthy' AND subject_id = ?",
                    ADMIN, connector.id()), "PRD-CON-029: the owner is told, once");
            assertTrue(scalar("SELECT owner_notified_at::text FROM connector_health WHERE connector_id = ?", connector.id()) != null);

            // Work queued behind an open credential circuit waits; the target is not called again.
            OutboundReferenceService.Reference retried = asAlice(() -> references.retry(alice, ref.id()));
            int callsBefore = authorizationsSeen.size();
            worker.tick();
            assertEquals(callsBefore, authorizationsSeen.size(), "an open credential circuit does not reach the target");
            assertEquals("QUEUED", scalar("SELECT status FROM outbound_operation WHERE reference_id = ? ORDER BY created_at DESC LIMIT 1", retried.id()));
            assertEquals(1L, count("SELECT count(*) FROM notification WHERE recipient_principal_id = ? AND event_kind = 'integration.unhealthy' AND subject_id = ?",
                    ADMIN, connector.id()), "told once, not on every tick");

            // Rotation with a credential the target still refuses: refused here, current credential untouched.
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> asAdmin(() -> connectors.rotate(admin, connector.id(), "still-bad".toCharArray(), Optional.empty())));
            assertTrue(refused.getMessage().contains("AUTHENTICATION"), refused.getMessage());
            assertEquals("OPEN", scalar("SELECT circuit_state FROM connector_health WHERE connector_id = ?", connector.id()));
        } finally {
            authAnswer.set(200);
        }

        // PRD-CON-022: the new credential is proved, becomes current; the old one stays as previous (overlap).
        ConnectorService.Connector rotated = asAdmin(() -> connectors.rotate(admin, connector.id(), "api-token-2".toCharArray(), Optional.empty()));
        assertTrue(rotated.rotationInProgress());
        assertEquals("CLOSED", rotated.health().circuitState(), "a proved credential is the correction the circuit waited for");
        authorizationsSeen.clear();
        worker.tick();
        assertEquals("LINKED", scalar("SELECT status FROM outbound_reference WHERE id = ?", ref.id()));
        String expected = "Basic " + Base64.getEncoder().encodeToString("bot@example.com:api-token-2".getBytes(StandardCharsets.UTF_8));
        assertTrue(authorizationsSeen.contains(expected), "the NEW credential is what the worker uses");

        ConnectorService.Connector ended = asAdmin(() -> connectors.retirePreviousCredential(admin, connector.id()));
        assertFalse(ended.rotationInProgress());
    }

    @Test
    @DisplayName("PRD-CON-019 / PRD-CON-020: suspension halts and keeps; retirement destroys the credential and keeps every reference")
    void suspendAndRetire() throws Exception {
        ConnectorService.Connector connector = activeConnector("jira-ret", Optional.empty());
        UUID subject = finding(childA, "Verbose error page", "Stack trace shown.");
        OutboundReferenceService.Reference ref = asAlice(() -> references.create(alice, subject, connector.id()));
        worker.tick();
        assertEquals("LINKED", scalar("SELECT status FROM outbound_reference WHERE id = ?", ref.id()));

        ConnectorService.Connector suspended = asAdmin(() -> connectors.transition(admin, connector.id(), "SUSPENDED"));
        assertEquals("SUSPENDED", suspended.lifecycleState());
        assertTrue(suspended.credentialHeld(), "suspension keeps the credential reference (PRD-CON-019)");
        backdateObservation(ref.id());
        int before = authorizationsSeen.size();
        worker.tick();
        assertEquals(before, authorizationsSeen.size(), "a suspended connector is not called");

        ConnectorService.Connector retired = asAdmin(() -> connectors.transition(admin, connector.id(), "RETIRED"));
        assertEquals("RETIRED", retired.lifecycleState());
        assertFalse(retired.credentialHeld(), "the credential is destroyed");
        assertEquals(1L, count("SELECT count(*) FROM outbound_reference WHERE connector_id = ?", connector.id()), "PRD-CON-020: the reference stays");
        assertEquals(0L, count("SELECT count(*) FROM outbound_operation WHERE connector_id = ? AND status IN ('QUEUED', 'LEASED')", connector.id()));
        assertThrows(IllegalArgumentException.class, () -> asAdmin(() -> connectors.transition(admin, connector.id(), "ACTIVE")), "retired stays retired");
    }

    @Test
    @DisplayName("PRD-CON-025 / PRD-CON-027: HTTP outcomes map to one class each; a 429 carries the target's Retry-After")
    void classification() {
        assertEquals(FailureClass.AUTHENTICATION, ConnectorHttp.classify(401));
        assertEquals(FailureClass.AUTHORIZATION, ConnectorHttp.classify(403));
        assertEquals(FailureClass.RATE_LIMITED, ConnectorHttp.classify(429));
        assertEquals(FailureClass.CONFIGURATION, ConnectorHttp.classify(404));
        assertEquals(FailureClass.CONFIGURATION, ConnectorHttp.classify(302), "PRD-CON-034: a redirect is not followed");
        assertEquals(FailureClass.TRANSIENT, ConnectorHttp.classify(503));
        assertEquals(FailureClass.DATA, ConnectorHttp.classify(422));
        assertEquals(Optional.of(java.time.Duration.ofSeconds(120)), ConnectorWorker.retryAfter("HTTP 429 retry-after=120"));
        assertEquals(Optional.empty(), ConnectorWorker.retryAfter("HTTP 503"));
    }

    // ==============================================================================================

    private ConnectorService.Connector activeConnector(String code, Optional<UUID> scope) throws Exception {
        ConnectorService.Connector created = asAdmin(() -> connectors.create(admin, code, code, "JIRA_CLOUD",
                Map.of("baseUrl", JIRA, "projectKey", "SEC", "email", "bot@example.com"), Optional.of("api-token-1".toCharArray()),
                Optional.empty(), scope, Optional.empty(), 60));
        return asAdmin(() -> connectors.transition(admin, created.id(), "ACTIVE"));
    }

    private void backdateObservation(UUID referenceId) throws SQLException {
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "UPDATE outbound_reference SET last_observed_at = now() - interval '2 hours' WHERE id = ?", referenceId);
            c.commit();
        }
    }

    private UUID finding(UUID scope, String title, String description) throws SQLException {
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            UUID id = finding(c, scope, title, description);
            c.commit();
            return id;
        }
    }

    private static UUID finding(Connection c, UUID scope, String title, String description) throws SQLException {
        byte[] digest = new byte[32];
        new java.security.SecureRandom().nextBytes(digest);
        return returning(c, "INSERT INTO finding (tenant_id, fingerprint_digest, fingerprint_algorithm_version, finding_class, title, description, "
                + "state, source_tool, raw_source_record_ref, first_detected_at, last_detected_at, scope_node_id) "
                + "VALUES (?, ?, 1, 'CODE', ?, ?, 'OPEN', 'manual-entry', 'test', now(), now(), ?) RETURNING id", TENANT, digest, title, description, scope);
    }

    private <T> T asAdmin(java.util.concurrent.Callable<T> body) throws SQLException {
        return as(body);
    }

    private <T> T asAlice(java.util.concurrent.Callable<T> body) throws SQLException {
        return as(body);
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
        Object value = scalarObject(sql, parameters);
        return ((Number) value).longValue();
    }

    private String scalar(String sql, Object... parameters) throws SQLException {
        Object value = scalarObject(sql, parameters);
        return value == null ? null : String.valueOf(value);
    }

    private Object scalarObject(String sql, Object... parameters) throws SQLException {
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getObject(1) : null;
            }
        }
    }
}
