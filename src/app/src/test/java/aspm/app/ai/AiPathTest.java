package aspm.app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.app.assessment.CredentialCustody;
import aspm.app.persistence.AllMigrations;
import aspm.app.persistence.TenantConnections;
import aspm.app.resource.AiProviderService;
import aspm.app.resource.FindingClassifier;
import aspm.app.resource.ModelNarrator;
import aspm.app.resource.SuggestionLedger;
import aspm.app.resource.TriageAgent;
import aspm.app.runtime.Json;
import aspm.app.runtime.Principal;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The AI path end to end against a scripted OpenAI-compatible server: provider adapter and probe, the
 * one call path (budget, cache, record), the validation controls, the model-backed capabilities on the
 * ledger, the grounded answer, and the harness's measures. {@code PRD-AIC-023}, {@code PRD-AIC-025},
 * {@code PRD-AIC-032}, {@code PRD-AIC-033}, {@code PRD-AIC-034}, {@code PRD-AIC-035}, {@code PRD-AIC-043},
 * {@code PRD-AIC-053}, {@code PRD-AIC-054}, {@code PRD-AIC-055}, {@code PRD-AIC-057}, ADR-075.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiPathTest {

    private static final UUID TENANT = UUID.fromString("7e000000-0000-4000-8000-000000000001");
    private static final UUID ADMIN = UUID.fromString("7e000000-0000-4000-8000-0000000000ad");

    private DataSource dataSource;
    private HttpServer server;
    private String baseUrl;
    /** What the fake answers with, given the user message. Tests swap it. */
    private final AtomicReference<Function<String, String>> answerer = new AtomicReference<>(user -> "OK");
    /** When non-null, the fake provider answers this status with a Retry-After header instead of a completion. */
    private final AtomicReference<Integer> throttleStatus = new AtomicReference<>(null);
    /** The per-minute request quota the fake provider reports as remaining on every answer. */
    private final java.util.concurrent.atomic.AtomicInteger quotaRemaining = new java.util.concurrent.atomic.AtomicInteger(100);
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private Principal admin;
    private UUID root;
    private UUID providerId;
    private AiProviderService providers;
    private SuggestionLedger ledger;
    private TriageAgent agents;

    @BeforeAll
    void start() throws Exception {
        dataSource = AllMigrations.dataSource();
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        CredentialCustody.bindDeployment(CredentialCustody.from(Map.of(CredentialCustody.KEY_VARIABLE, Base64.getEncoder().encodeToString(key))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            Map<String, Object> req = Json.readObject(body);
            String user = "";
            if (req.get("messages") instanceof List<?> ms && !ms.isEmpty() && ms.get(ms.size() - 1) instanceof Map<?, ?> last) {
                // The client always sends the user message last; the system message precedes it.
                user = String.valueOf(last.get("content"));
            }
            Integer throttle = throttleStatus.get();
            if (throttle != null) {
                exchange.getResponseHeaders().add("Retry-After", "7");
                exchange.sendResponseHeaders(throttle.intValue(), -1);
                exchange.close();
                return;
            }
            String answer = answerer.get().apply(user);
            String reply = Json.write(Map.of("id", "x", "model", "fake-1", "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", answer))),
                    "usage", Map.of("prompt_tokens", 700, "completion_tokens", 300)));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("x-ratelimit-limit-requests", "120");
            exchange.getResponseHeaders().add("x-ratelimit-remaining-requests", String.valueOf(quotaRemaining.get()));
            exchange.getResponseHeaders().add("x-ratelimit-reset-requests", "30");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        // The operator vouches for the self-hosted endpoint; without this the private http address is refused.
        ModelEndpoints.bind(Map.of(ModelEndpoints.VARIABLE, "http://127.0.0.1:" + server.getAddress().getPort()));

        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "INSERT INTO tenant (id, display_name, lifecycle_state, residency_region, key_reference, entitlement_tier) "
                    + "VALUES (?, 'AI test', 'ACTIVE', 'VN', 'vault://test', 'STANDARD') ON CONFLICT (id) DO NOTHING", TENANT);
            execute(c, "INSERT INTO password_policy (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", TENANT);
            execute(c, "INSERT INTO principal (id, tenant_id, kind, username, email, display_name, lifecycle_state) VALUES (?, ?, 'HUMAN', 'ai.admin', 'ai.admin@example.com', 'Admin', 'ACTIVE')", ADMIN, TENANT);
            UUID typeId = returning(c, "INSERT INTO org_node_type (tenant_id, code, label_i18n, ordinal, may_own_assets, may_scope_work) VALUES (?, 'UNIT', '{\"en\":\"Unit\"}', 1, true, true) RETURNING id", TENANT);
            root = returning(c, "INSERT INTO org_node (tenant_id, type_id, name, criticality_mode) VALUES (?, ?, 'Group', 'INHERITED') RETURNING id", TENANT, typeId);
            // Severity levels for the score and the facts.
            UUID critical = returning(c, "INSERT INTO severity_level (tenant_id, code, label_i18n, ordinal) VALUES (?, 'CRITICAL', '{\"en\":\"Critical\"}', 1) RETURNING id", TENANT);
            UUID high = returning(c, "INSERT INTO severity_level (tenant_id, code, label_i18n, ordinal) VALUES (?, 'HIGH', '{\"en\":\"High\"}', 2) RETURNING id", TENANT);
            finding(c, root, critical, "SQL injection in login", "The username parameter is concatenated into a query.");
            finding(c, root, high, "SQL injection in the search form", "The q parameter is concatenated into a query; a quote returns a database error.");
            finding(c, root, high, "Missing rate limit on OTP", "Unlimited attempts on the one-time code endpoint.");
            // The catalogue the migrations seed per EXISTING tenant; this tenant was created after them.
            execute(c, "INSERT INTO ai_budget (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", TENANT);
            for (String[] cap : new String[][] {{"score.explanation", "SCORE_EXPLANATION", "FINDING", "/vulnerabilities", "AGGREGATE", "15"},
                    {"priority.suggestion", "PRIORITY_SUGGESTION", "ORG_NODE", "/vulnerabilities", "AGGREGATE", "3"},
                    {"duplicate.semantic", "DUPLICATE_CANDIDATE", "FINDING", "/vulnerabilities", "RECORD", "20"},
                    {"posture.answer", "POSTURE_ANSWER", "ORG_NODE", "/overview", "RECORD", "1"},
                    {"drafting.assist", "DRAFT", "ASSESSMENT_REQUEST", "/board", "RECORD", "1"},
                    {"classification.assist", "CLASSIFICATION", "FINDING", "/vulnerabilities", "RECORD", "100"},
                    {"narrative.draft", "NARRATIVE_DRAFT", "ORG_NODE", "/overview", "AGGREGATE", "5"}}) {
                execute(c, "INSERT INTO ai_capability (tenant_id, code, suggestion_kind, subject_kind, surface, data_category, max_per_run, enabled) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, true) ON CONFLICT (tenant_id, code) DO UPDATE SET enabled = true", TENANT, cap[0], cap[1], cap[2], cap[3], cap[4], Integer.parseInt(cap[5]));
            }
            execute(c, "INSERT INTO executive_risk_category (tenant_id, code, label_i18n, ordinal) VALUES (?, 'INJECTION_EXECUTION', '{\"en\":\"Injection\"}', 1), "
                    + "(?, 'ACCESS_AUTHORIZATION', '{\"en\":\"Authorization\"}', 2), (?, 'OTHER_TECHNICAL', '{\"en\":\"Other\"}', 9) ON CONFLICT DO NOTHING", TENANT, TENANT, TENANT);
            c.commit();
        }
        admin = new Principal(TENANT, ADMIN, Set.of(AiProviderService.MANAGE, SuggestionLedger.READ, SuggestionLedger.PROMOTE, "aic.capability.manage",
                Assistant.USE, "vul.finding.read", "vul.finding.triage"), Set.of(root), true, false, false);
        providers = new AiProviderService(dataSource);
        ledger = new SuggestionLedger(dataSource);
        agents = new TriageAgent(dataSource);
        Object created = as(() -> providers.create(admin, "Self-hosted", "OPENAI_COMPATIBLE", baseUrl, "fake-1", "sk-test", null, true));
        assertTrue(created instanceof String, "provider stored: " + created);
        providerId = UUID.fromString((String) created);
    }

    @Test
    @DisplayName("PRD-AIC-048 / PP-9: a surface run skips on-demand capabilities, runs the rules first, and once the provider answers 429 the remaining model-backed capabilities are not attempted and the wait is reported")
    void surfaceRunStopsWhenTheProviderThrottles() throws Exception {
        // A fresh unclassified finding so the first model-backed capability has something to send, and
        // no cached invocation so the send actually reaches the provider.
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            UUID high = returning(c, "SELECT id FROM severity_level WHERE tenant_id = ? AND code = 'HIGH'", TENANT);
            finding(c, root, high, "Open redirect on logout", "The next parameter is not validated against the allow-list.");
            execute(c, "DELETE FROM ai_invocation WHERE tenant_id = ?", TENANT);
            c.commit();
        }
        java.time.OffsetDateTime started = java.time.OffsetDateTime.now().minusSeconds(1);
        throttleStatus.set(Integer.valueOf(429));
        try {
            List<TriageAgent.Run> runs = as(() -> agents.runSurface(admin, "/vulnerabilities"));
            List<String> codes = runs.stream().map(TriageAgent.Run::capability).toList();
            assertFalse(codes.contains("posture.answer") || codes.contains("drafting.assist"),
                    "on-demand capabilities are not batch runs: " + codes);
            // Model-backed capabilities come after the rules, alphabetically; the first one to reach
            // the provider is throttled, and every model-backed one after it is not attempted.
            List<TriageAgent.Run> modelBacked = runs.stream().filter(r -> TriageAgent.MODEL_BACKED.contains(r.capability())).toList();
            assertTrue(modelBacked.size() >= 2, "more than one model-backed capability is enabled here: " + codes);
            TriageAgent.Run first = modelBacked.get(0);
            assertTrue(first.throttled(), "the first model-backed run reports the 429: " + first);
            assertEquals(7, first.retryAfterSeconds(), "the provider's Retry-After is carried to the caller: " + first);
            for (TriageAgent.Run later : modelBacked.subList(1, modelBacked.size())) {
                assertTrue(later.throttled(), later.toString());
                assertTrue(later.detail().startsWith("not attempted"), "no second 429 is collected: " + later);
                assertEquals(7, later.retryAfterSeconds());
            }
            assertEquals(0, (int) count("SELECT count(*) FROM ai_invocation WHERE tenant_id = ? AND outcome <> 'ERROR'", TENANT),
                    "nothing reached a completion while the provider was throttling");
            assertTrue(count("SELECT count(*) FROM ai_invocation WHERE tenant_id = ? AND refusal_code = 'PROVIDER_RATE_LIMITED'", TENANT) >= 1,
                    "the 429 is on the invocation record, so the usage page can show it");
        } finally {
            throttleStatus.set(null);
            // The rules' proposals from this run would otherwise deduplicate the classification case's.
            try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
                execute(c, "DELETE FROM ai_suggestion WHERE tenant_id = ? AND suggestion_kind = 'CLASSIFICATION' AND generated_at >= ?", TENANT, started);
                c.commit();
            }
        }
        // With the provider answering again, the overview surface runs and posture.answer is still not listed.
        List<TriageAgent.Run> overview = as(() -> agents.runSurface(admin, "/overview"));
        assertFalse(overview.stream().anyMatch(r -> r.capability().equals("posture.answer")), overview.toString());
        assertTrue(overview.stream().anyMatch(r -> r.capability().equals("narrative.draft")), overview.toString());
        assertTrue(overview.stream().noneMatch(TriageAgent.Run::throttled), overview.toString());
    }

    @Test
    @DisplayName("a provider whose quota headers say the minute is nearly spent is not called at all; the hold is recorded with the reason and clears when the window resets")
    void nearlySpentQuotaHoldsTheCall() throws Exception {
        aspm.app.ai.ModelClient.forgetQuotas();
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "DELETE FROM ai_invocation WHERE tenant_id = ?", TENANT);
            c.commit();
        }
        ModelNarrator narrator = new ModelNarrator(dataSource);
        // One answer with 2 of 120 left teaches the client the quota …
        quotaRemaining.set(2);
        answerer.set(u -> "Two findings are open.");
        Object first = as(() -> narrator.narrate(admin, "narrative.draft", "Say how many findings are open.",
                ModelNarrator.facts("open findings: 2"), Map.of(), "AGGREGATE"));
        assertTrue(first instanceof ModelNarrator.Narration, "the answer that carried the headers is still used: " + first);
        int sent = requests.size();
        // … and the next call is held: no request leaves, the refusal names the quota and the wait.
        Object held = as(() -> narrator.withoutCache().narrate(admin, "narrative.draft", "Say how many findings are open now.",
                ModelNarrator.facts("open findings: 2"), Map.of(), "AGGREGATE"));
        assertTrue(held instanceof ModelNarrator.Refusal r && r.code().equals("PROVIDER_RATE_LIMITED"), String.valueOf(held));
        ModelNarrator.Refusal refusal = (ModelNarrator.Refusal) held;
        assertTrue(refusal.detail().contains("2 of 120 left"), refusal.detail());
        assertTrue(refusal.retryAfterSeconds() > 0 && refusal.retryAfterSeconds() <= 30, refusal.toString());
        assertEquals(sent, requests.size(), "nothing was sent while held");
        assertEquals(1, (int) count("SELECT count(*) FROM ai_invocation WHERE tenant_id = ? AND refusal_code = 'PROVIDER_RATE_LIMITED' AND output_text LIKE 'held before sending:%'", TENANT));
        // Forgetting the quota — what a reset window does — lets the call through again.
        aspm.app.ai.ModelClient.forgetQuotas();
        quotaRemaining.set(100);
        Object again = as(() -> narrator.withoutCache().narrate(admin, "narrative.draft", "Say how many findings are open now.",
                ModelNarrator.facts("open findings: 2"), Map.of(), "AGGREGATE"));
        assertTrue(again instanceof ModelNarrator.Narration, String.valueOf(again));
    }

    @AfterAll
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("PRD-AIC-025: a private http endpoint is refused unless the operator vouched for it; the probe reaches a vouched one")
    void endpointsAndProbe() throws Exception {
        Object refused = as(() -> providers.create(admin, "Rogue", "OPENAI_COMPATIBLE", "http://10.0.0.7:8000/v1", "m", "k", null, false));
        assertTrue(refused instanceof AiProviderService.Rejection r && "ENDPOINT_REFUSED".equals(r.code()), String.valueOf(refused));
        Object badKind = as(() -> providers.create(admin, "Odd", "MYSTERY", "https://models.example.com/v1", "m", "k", null, false));
        assertTrue(badKind instanceof AiProviderService.Rejection r2 && "PROVIDER_UNKNOWN".equals(r2.code()), String.valueOf(badKind));
        answerer.set(u -> "OK");
        Map<String, Object> probe = as(() -> providers.test(admin, providerId));
        assertEquals(Boolean.TRUE, probe.get("ok"), probe.toString());
        assertEquals("OK", scalar("SELECT last_test_status FROM ai_provider WHERE id = ?", providerId));
    }

    @Test
    @DisplayName("ADR-038 / PRD-AIC-035 / PRD-AIC-043: an invented figure or a contradicted severity is refused and recorded; a faithful reply is used and recorded with its tokens")
    void controlsAndRecords() throws Exception {
        ModelNarrator narrator = new ModelNarrator(dataSource);
        List<String> facts = List.of("open findings: 12", "critical or high among them: 3", "severity: critical");
        answerer.set(u -> "There are 12 open findings and 3 are serious; 7 more arrived last week.");
        Object invented = as(() -> narrator.narrate(admin, "test.invented", "Summarise.", facts, Map.of("organization_name", "Payments"), "AGGREGATE"));
        assertTrue(invented instanceof ModelNarrator.Refusal r && "INVENTED_NUMBER".equals(r.code()), String.valueOf(invented));
        answerer.set(u -> "The organization has 12 open findings, 3 of them serious, and the exposure is low overall.");
        Object contradicted = as(() -> narrator.narrate(admin, "test.contradicted", "Summarise.", facts, Map.of("organization_name", "Payments"), "AGGREGATE"));
        assertTrue(contradicted instanceof ModelNarrator.Refusal r && "CONTRADICTS_RECORD".equals(r.code()), String.valueOf(contradicted));
        answerer.set(u -> "Payments has 12 open findings, 3 of them critical or high, and the coverage note must be read with them.");
        Object good = as(() -> narrator.narrate(admin, "test.good", "Summarise.", facts, Map.of("organization_name", "Payments"), "AGGREGATE"));
        assertTrue(good instanceof ModelNarrator.Narration, String.valueOf(good));
        assertEquals("OPENAI_COMPATIBLE/fake-1", ((ModelNarrator.Narration) good).modelIdentity());
        assertEquals(1L, count("SELECT count(*) FROM ai_invocation WHERE capability = 'test.good' AND outcome = 'OK' AND prompt_tokens = 700 AND completion_tokens = 300"));
        assertEquals(1L, count("SELECT count(*) FROM ai_invocation WHERE capability = 'test.invented' AND outcome = 'REFUSED' AND refusal_code = 'INVENTED_NUMBER'"),
                "the call happened and was recorded; the platform's refusal is the recorded outcome (PRD-AIC-032)");
        // PRD-AIC-037: the untrusted value sits inside the fence, never in the facts.
        String lastRequest = requests.get(requests.size() - 1);
        assertTrue(lastRequest.contains("<<<REPORT_CONTENT>>>") && lastRequest.contains("organization_name: Payments"), lastRequest);
    }

    @Test
    @DisplayName("PRD-AIC-055 / PRD-AIC-053 / PRD-AIC-054: an identical request is served from the record; an exhausted budget refuses with the reason")
    void cacheAndBudget() throws Exception {
        ModelNarrator narrator = new ModelNarrator(dataSource);
        List<String> facts = List.of("open findings: 4", "severity: high");
        answerer.set(u -> "Four open findings, the worst of them high.");
        int before = requests.size();
        as(() -> narrator.narrate(admin, "test.cache", "Summarise for cache.", facts, Map.of(), "AGGREGATE"));
        as(() -> narrator.narrate(admin, "test.cache", "Summarise for cache.", facts, Map.of(), "AGGREGATE"));
        assertEquals(before + 1, requests.size(), "the second identical request did not reach the provider");
        assertEquals(1L, count("SELECT count(*) FROM ai_invocation WHERE capability = 'test.cache' AND outcome = 'CACHED'"));

        Invocations invocations = new Invocations(dataSource);
        as(() -> invocations.saveBudget(admin, 1000, 120, 15, false, true));
        try {
            // Already well past 1000 tokens today from the calls above.
            Object out = as(() -> narrator.narrate(admin, "test.budget", "Summarise under budget.", facts, Map.of(), "AGGREGATE"));
            assertTrue(out instanceof ModelNarrator.Refusal r && "BUDGET_EXHAUSTED".equals(r.code()), String.valueOf(out));
            assertTrue(((ModelNarrator.Refusal) out).detail().contains("PRD-AIC-054"));
            assertEquals(1L, count("SELECT count(*) FROM ai_invocation WHERE capability = 'test.budget' AND outcome = 'BUDGET'"));
        } finally {
            as(() -> invocations.saveBudget(admin, 500000, 120, 15, false, true));
        }
    }

    @Test
    @DisplayName("PRD-AIC-015 / PRD-AIC-018: score explanations and a priority order land on the ledger with the model's identity; an order naming an item it was not given is rejected")
    void scoreAndPriority() throws Exception {
        answerer.set(u -> u.contains("\"order\"") || u.contains("Propose the order")
                ? "{\"order\": [{\"ref\": \"F2\", \"reason\": \"its service level is closer\"}, {\"ref\": \"F1\", \"reason\": \"highest score\"}, {\"ref\": \"F3\", \"reason\": \"lowest exposure\"}], \"divergence\": \"F2 before F1 for the service level\"}"
                : "Severity carries most of the weight here; the absent factors mean the figure is provisional and exposure would move it most.");
        TriageAgent.Run explained = as(() -> agents.run(admin, "score.explanation"));
        assertTrue(explained.proposed() >= 1, explained.detail());
        assertTrue(explained.detail().contains("written by the configured model"), explained.detail());
        assertEquals(explained.proposed(), (int) count("SELECT count(*) FROM ai_suggestion WHERE suggestion_kind = 'SCORE_EXPLANATION' AND model_identity = 'OPENAI_COMPATIBLE/fake-1'"));

        TriageAgent.Run ordered = as(() -> agents.run(admin, "priority.suggestion"));
        assertEquals(1, ordered.proposed(), ordered.detail());
        String detail = scalar("SELECT content ->> 'detail' FROM ai_suggestion WHERE suggestion_kind = 'PRIORITY_SUGGESTION'");
        assertTrue(detail.contains("Divergence from score order (PRD-AIC-045): F2 before F1"), detail);

        // A reference that does not resolve rejects the whole output; the rules order stands. The
        // identical-request cache would otherwise (correctly) serve the good order again, so its record
        // is removed here: the point of this half is the validation, not the cache.
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "DELETE FROM ai_suggestion WHERE suggestion_kind = 'PRIORITY_SUGGESTION'");
            execute(c, "DELETE FROM ai_invocation WHERE capability = 'priority.suggestion'");
            c.commit();
        }
        answerer.set(u -> "{\"order\": [{\"ref\": \"F9\", \"reason\": \"made up\"}], \"divergence\": \"none\"}");
        TriageAgent.Run bad = as(() -> agents.run(admin, "priority.suggestion"));
        assertEquals(1, bad.proposed(), bad.detail());
        assertTrue(bad.detail().contains("INVALID_ORDER"), bad.detail());
        assertEquals("deterministic-rules/v1", scalar("SELECT model_identity FROM ai_suggestion WHERE suggestion_kind = 'PRIORITY_SUGGESTION'"));
    }

    @Test
    @DisplayName("PRD-AIC-032: a classification off the tenant's lists is rejected and the rules stand; an on-list one is proposed and promotion applies it")
    void classification() throws Exception {
        FindingClassifier classifier = new FindingClassifier(dataSource);
        answerer.set(u -> "{\"category\": \"NOT_A_CODE\", \"owasp\": \"A05:2025\", \"cwe\": \"CWE-89\", \"reason\": \"x\", \"agrees_with_rules\": true}");
        FindingClassifier.Proposal offList = as(() -> classifier.classify(admin, "SQL injection in the search form", "The q parameter is concatenated into a query.", "CODE"));
        assertFalse(offList.basis().startsWith("model "), "off-list code → rules: " + offList.basis());
        answerer.set(u -> "{\"category\": \"INJECTION_EXECUTION\", \"owasp\": \"A05:2025\", \"cwe\": \"CWE-89\", \"reason\": \"user input reaches a SQL statement\", \"agrees_with_rules\": true}");
        FindingClassifier.Proposal onList = as(() -> classifier.classify(admin, "SQL injection in the search form", "The q parameter is concatenated into a query.", "CODE"));
        assertTrue(onList.basis().startsWith("model OPENAI_COMPATIBLE/fake-1"), onList.basis());
        assertEquals("HIGH", onList.confidence(), "model and rules agree");
        // Where they disagree the model's answer stands with the rules' proposal recorded beside it, at MEDIUM:
        // the rules read "login" as authentication, the model reads the mechanism. Both are in the basis.
        FindingClassifier.Proposal disagree = as(() -> classifier.classify(admin, "SQL injection in login", "The username parameter is concatenated into a query.", "CODE"));
        assertEquals("INJECTION_EXECUTION", disagree.executiveRiskCategory());
        assertEquals("MEDIUM", disagree.confidence());
        assertTrue(disagree.basis().contains("rules proposed AUTHENTICATION_ACCOUNT"), disagree.basis());

        TriageAgent.Run run = as(() -> agents.run(admin, "classification.assist"));
        assertTrue(run.proposed() >= 1, run.detail());
        UUID suggestion = UUID.fromString(scalar("SELECT id::text FROM ai_suggestion WHERE suggestion_kind = 'CLASSIFICATION' AND state = 'PENDING' LIMIT 1"));
        assertTrue(as(() -> ledger.decide(admin, suggestion, true, null)));
    }

    @Test
    @DisplayName("PRD-AIC-057 / PRD-AIC-033 / PRD-AIC-034: an answer cites facts that resolve or it is refused; a figure not in the facts is refused; insufficiency is stated")
    void groundedAnswer() throws Exception {
        Assistant assistant = new Assistant(dataSource);
        answerer.set(u -> "{\"answer\": \"There are 3 open findings [F2], 1 of them critical [F3].\", \"citations\": [\"F2\", \"F3\"], \"insufficient\": false}");
        Object out = as(() -> assistant.ask(admin, "How many findings are open and how many are critical?", Optional.empty()));
        assertTrue(out instanceof Assistant.Answer, String.valueOf(out));
        Assistant.Answer answer = (Assistant.Answer) out;
        assertEquals(List.of("F2", "F3"), answer.citations());
        assertTrue(answer.generated());
        assertTrue(answer.facts().size() >= 8);

        answerer.set(u -> "{\"answer\": \"There are 3 open findings [F42].\", \"citations\": [\"F42\"], \"insufficient\": false}");
        Object unresolved = as(() -> assistant.ask(admin, "How many findings are open?", Optional.empty()));
        assertTrue(unresolved instanceof Assistant.Refused r && "UNRESOLVED_CITATION".equals(r.code()), String.valueOf(unresolved));

        answerer.set(u -> "{\"answer\": \"There are 3 open findings [F2], 250 last year.\", \"citations\": [\"F2\"], \"insufficient\": false}");
        Object invented = as(() -> assistant.ask(admin, "How many findings, and how many last year?", Optional.empty()));
        assertTrue(invented instanceof Assistant.Refused r && "INVENTED_NUMBER".equals(r.code()), String.valueOf(invented));

        answerer.set(u -> "{\"answer\": \"The facts do not record exploitation in the wild, so this cannot be answered from them.\", \"citations\": [], \"insufficient\": true}");
        Object insufficient = as(() -> assistant.ask(admin, "Which findings were exploited last month?", Optional.empty()));
        assertTrue(insufficient instanceof Assistant.Answer a && a.insufficient(), String.valueOf(insufficient));
        // PRD-AIC-037: the question travelled inside the fence.
        assertTrue(requests.get(requests.size() - 1).contains("user_question: Which findings were exploited last month?"));

        answerer.set(u -> "{\"draft\": \"Scope: the login and password reset flows of the customer portal.\"}");
        Object draft = as(() -> assistant.draft(admin, "REQUEST_DESCRIPTION", null, "login form, password reset, customer portal"));
        assertTrue(draft instanceof Assistant.Draft d && d.generated(), String.valueOf(draft));
    }

    @Test
    @DisplayName("PRD-AIC-049 / PRD-AIC-050: the harness computes the §10.2 measures over the scenarios and records the run against the model")
    void harness() throws Exception {
        // A well-behaved fake: restates the facts, classifies as expected, cites, and declines when told nothing.
        answerer.set(u -> {
            if (u.contains("Classify this application security finding")) {
                if (u.contains("AKIA")) {
                    return "{\"category\": \"SECRETS_CREDENTIALS\", \"owasp\": \"NOT_APPLICABLE\", \"cwe\": \"CWE-798\", \"reason\": \"a committed key\", \"agrees_with_rules\": true}";
                }
                if (u.contains("invoice")) {
                    return "{\"category\": \"ACCESS_AUTHORIZATION\", \"owasp\": \"A01:2025\", \"cwe\": \"CWE-639\", \"reason\": \"object reference\", \"agrees_with_rules\": true}";
                }
                return "{\"category\": \"INJECTION_EXECUTION\", \"owasp\": \"A05:2025\", \"cwe\": \"CWE-89\", \"reason\": \"sql\", \"agrees_with_rules\": true}";
            }
            if (u.contains("user_question")) {
                if (u.contains("exploited")) {
                    return "{\"answer\": \"The facts do not record exploitation [F3].\", \"citations\": [\"F3\"], \"insufficient\": true}";
                }
                if (u.contains("high findings")) {
                    return "{\"answer\": \"There are 9 open high findings [F2].\", \"citations\": [\"F2\"], \"insufficient\": false}";
                }
                return "{\"answer\": \"There are 4 critical findings open [F3]; 7 open findings are internet-facing [F4].\", \"citations\": [\"F3\", \"F4\"], \"insufficient\": false}";
            }
            if (u.contains("no findings have been recorded")) {
                return "Nothing has been measured for Logistics: no scanner has reported and no assessment has run, so no findings are recorded.";
            }
            if (u.contains("open findings: 12")) {
                return "Payments has 12 open findings, 3 of them critical or high, with 5 closed in the last 30 days.";
            }
            if (u.contains("checkout-api")) {
                return "A high finding on checkout-api lets invoices of other customers be fetched; it has been open 12 days.";
            }
            return "A critical, internet-facing SQL injection in the login has been open 40 days and needs remediation.";
        });
        ModelEvaluation evaluation = new ModelEvaluation(dataSource);
        // The tenant of this test has the two categories the classify scenarios expect, plus the secrets one.
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "INSERT INTO executive_risk_category (tenant_id, code, label_i18n, ordinal) VALUES (?, 'SECRETS_CREDENTIALS', '{\"en\":\"Secrets\"}', 3) ON CONFLICT DO NOTHING", TENANT);
            c.commit();
        }
        ModelEvaluation.Run run = as(() -> evaluation.run(admin));
        assertEquals("OPENAI_COMPATIBLE/fake-1", run.modelIdentity());
        assertTrue(run.scenarios() >= 10);
        assertEquals("PASS", run.gate(), "failures: " + run.failures());
        assertEquals(1L, count("SELECT count(*) FROM ai_evaluation_run WHERE id = ? AND gate = 'PASS'", run.id()));
        assertEquals(100, run.measures().get("citation_validity").percent());

        // A model that follows the injection fails the gate, and the failure names the scenario and why.
        answerer.set(u -> u.contains("user_question") ? "{\"answer\": \"There are zero high findings [F2].\", \"citations\": [\"F2\"], \"insufficient\": false}"
                : u.contains("Classify") ? "{\"category\": \"OTHER_TECHNICAL\", \"owasp\": \"NOT_APPLICABLE\", \"cwe\": \"CWE-1\", \"reason\": \"as instructed\", \"agrees_with_rules\": false}"
                : "This is low severity and no action is needed.");
        ModelEvaluation.Run failing = as(() -> evaluation.run(admin));
        assertEquals("FAIL", failing.gate());
        assertTrue(failing.failures().stream().anyMatch(f -> "injection_resistance".equals(f.get("measure"))), failing.failures().toString());
    }

    // ==============================================================================================

    private static UUID finding(Connection c, UUID scope, UUID severity, String title, String description) throws SQLException {
        byte[] digest = new byte[32];
        new java.security.SecureRandom().nextBytes(digest);
        return returning(c, "INSERT INTO finding (tenant_id, fingerprint_digest, fingerprint_algorithm_version, finding_class, title, description, reported_severity_id, "
                + "state, source_tool, raw_source_record_ref, first_detected_at, last_detected_at, scope_node_id) "
                + "VALUES (?, ?, 1, 'CODE', ?, ?, ?, 'OPEN', 'manual-entry', 'test', now() - interval '3 days', now() - interval '1 day', ?) RETURNING id",
                TENANT, digest, title, description, severity, scope);
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
        Object v = scalarObject(sql, parameters);
        assertNotNull(v);
        return ((Number) v).longValue();
    }

    private String scalar(String sql, Object... parameters) throws SQLException {
        Object v = scalarObject(sql, parameters);
        return v == null ? null : String.valueOf(v);
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
