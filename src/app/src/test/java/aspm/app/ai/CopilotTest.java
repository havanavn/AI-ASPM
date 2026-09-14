package aspm.app.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.app.assessment.CredentialCustody;
import aspm.app.persistence.AllMigrations;
import aspm.app.persistence.TenantConnections;
import aspm.app.resource.AiProviderService;
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
import java.util.Locale;
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
 * The copilot of {@code PRD-AIC-058}, against the real schema and a fake provider.
 *
 * <p>The four properties the requirement is written as, each asserted rather than described: retrieval
 * is per question, a record is resolved by the platform and only inside the asker's scope, a pack the
 * asker cannot read is named rather than answered around, and the transcript is the platform's.
 *
 * <p>Two principals with different scopes and different permissions, because every one of those
 * properties is invisible when only one caller exists.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CopilotTest {

    private static final UUID TENANT = UUID.fromString("7f000000-0000-4000-8000-000000000001");
    /** Reaches the whole group, reads the roster and individual load. */
    private static final UUID LEAD = UUID.fromString("7f000000-0000-4000-8000-00000000001e");
    /** Reaches Unit A only, and may not read the roster at all. */
    private static final UUID OWNER = UUID.fromString("7f000000-0000-4000-8000-0000000000a1");

    private DataSource dataSource;
    private HttpServer server;
    private final AtomicReference<Function<String, String>> answerer = new AtomicReference<>(u -> "{}");
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private Copilot copilot;
    private Principal lead;
    private Principal owner;
    private UUID root;
    private UUID unitA;
    private UUID unitB;
    private UUID payments;
    private UUID ledger;

    @BeforeAll
    void start() throws Exception {
        dataSource = AllMigrations.dataSource();
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        CredentialCustody.bindDeployment(CredentialCustody.from(
                Map.of(CredentialCustody.KEY_VARIABLE, Base64.getEncoder().encodeToString(key))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            Map<String, Object> request = Json.readObject(body);
            String user = "";
            if (request.get("messages") instanceof List<?> ms && !ms.isEmpty()
                    && ms.get(ms.size() - 1) instanceof Map<?, ?> last) {
                user = String.valueOf(last.get("content"));
            }
            String reply = Json.write(Map.of("id", "x", "model", "fake-1",
                    "choices", List.of(Map.of("index", 0, "message",
                            Map.of("role", "assistant", "content", answerer.get().apply(user)))),
                    "usage", Map.of("prompt_tokens", 500, "completion_tokens", 120)));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        ModelEndpoints.bind(Map.of(ModelEndpoints.VARIABLE, "http://127.0.0.1:" + port));

        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "INSERT INTO tenant (id, display_name, lifecycle_state, residency_region, key_reference, entitlement_tier) "
                    + "VALUES (?, 'Copilot test', 'ACTIVE', 'VN', 'vault://test', 'STANDARD') ON CONFLICT (id) DO NOTHING", TENANT);
            execute(c, "INSERT INTO password_policy (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", TENANT);
            for (Object[] p : new Object[][] {{LEAD, "cop.lead", "Lan Lead"}, {OWNER, "cop.owner", "Owen Owner"}}) {
                execute(c, "INSERT INTO principal (id, tenant_id, kind, username, email, display_name, lifecycle_state) "
                        + "VALUES (?, ?, 'HUMAN', ?, ?, ?, 'ACTIVE')", p[0], TENANT, p[1], p[1] + "@example.com", p[2]);
            }
            UUID nodeType = returning(c, "INSERT INTO org_node_type (tenant_id, code, label_i18n, ordinal, may_own_assets, may_scope_work) "
                    + "VALUES (?, 'UNIT', '{\"en\":\"Unit\"}', 1, true, true) RETURNING id", TENANT);
            root = node(c, nodeType, null, "Group");
            unitA = node(c, nodeType, root, "Retail");
            unitB = node(c, nodeType, root, "Logistics");
            closure(c, root, root, 0);
            closure(c, unitA, unitA, 0);
            closure(c, root, unitA, 1);
            closure(c, unitB, unitB, 0);
            closure(c, root, unitB, 1);
            UUID critical = returning(c, "INSERT INTO severity_level (tenant_id, code, label_i18n, ordinal) "
                    + "VALUES (?, 'CRITICAL', '{\"en\":\"Critical\"}', 1) RETURNING id", TENANT);
            UUID high = returning(c, "INSERT INTO severity_level (tenant_id, code, label_i18n, ordinal) "
                    + "VALUES (?, 'HIGH', '{\"en\":\"High\"}', 2) RETURNING id", TENANT);
            UUID tier = returning(c, "INSERT INTO criticality_tier (tenant_id, code, label_i18n, ordinal) "
                    + "VALUES (?, 'TIER1', '{\"en\":\"Tier 1\"}', 1) RETURNING id", TENANT);
            execute(c, "INSERT INTO full_review_policy (tenant_id, criticality_tier_id, interval_months, warn_days_before) "
                    + "VALUES (?, ?, 6, 30)", TENANT, tier);
            UUID appType = returning(c, "INSERT INTO asset_type (tenant_id, code, label_i18n, identity_rule, is_network_reachable, may_carry_findings) "
                    + "VALUES (?, 'APPLICATION', '{\"en\":\"Application\"}', "
                    + "'{\"version\":1,\"natural_key_attributes\":[\"display_name\"]}', true, true) RETURNING id", TENANT);
            payments = application(c, appType, tier, unitA, "Retail Payments Portal");
            ledger = application(c, appType, tier, unitB, "Logistics Ledger");
            finding(c, unitA, critical, "SQL injection in the payment search", payments);
            finding(c, unitA, high, "Missing rate limit on the OTP endpoint", payments);
            finding(c, unitB, critical, "Path traversal in the manifest upload", ledger);
            // A role catalogue, because "what can the developers do" has no answer without one. The
            // permission codes are the product-fixed catalogue's own; the role composing them is tenant data.
            UUID developer = returning(c, "INSERT INTO role (tenant_id, code, label_i18n) "
                    + "VALUES (?, 'DEVELOPER', '{\"en\":\"Developer\"}') RETURNING id", TENANT);
            for (String permission : List.of("vul.finding.read", "vul.finding.claimfix", "ast.asset.read")) {
                execute(c, "INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up) "
                        + "VALUES (?, ?, '{\"en\":\"x\"}'::jsonb, false, false) ON CONFLICT (code) DO NOTHING",
                        permission, permission.split("\\.")[0]);
                execute(c, "INSERT INTO role_permission (tenant_id, role_id, permission_code) VALUES (?, ?, ?)",
                        TENANT, developer, permission);
            }
            execute(c, "INSERT INTO role_assignment (tenant_id, principal_id, role_id, scope_mode, scope_node_id) "
                    + "VALUES (?, ?, ?, 'SUBTREE', ?)", TENANT, OWNER, developer, unitA);
            // A window already planned on the busier application. Both are never-assessed, internet-facing
            // and tier one, so the only clause left to decide the order is "unplanned before planned" —
            // which is the clause that makes the answer a plan rather than a restatement of the backlog.
            execute(c, "INSERT INTO assessment_plan_window (tenant_id, target_asset_id, starts_on, ends_on) "
                    + "VALUES (?, ?, current_date + 20, current_date + 30)", TENANT, payments);
            // A bill of materials with one vulnerable component, so the component pack has an estate to
            // report on: a coverage figure with nothing behind it tests the sentence, not the query.
            UUID snapshot = returning(c, "INSERT INTO sbom_snapshot (tenant_id, artifact_asset_id, content_hash, "
                    + "submitted_by_principal_id, component_count, quality_score, scope_node_id, scope_ancestor_path, "
                    + "scope_node_type_id, scope_criticality_id, scope_hierarchy_ver, scope_resolved_at, format, "
                    + "format_version, source, ecosystems) "
                    + "VALUES (?, ?, ?, ?, 1, 100, ?, ARRAY[?]::uuid[], ?, ?, 1, now(), 'CYCLONEDX', '1.5', 'API_PUSH', "
                    + "ARRAY['maven']) RETURNING id",
                    TENANT, payments, new byte[32], LEAD, unitA, unitA, nodeType, tier);
            UUID component = returning(c, "INSERT INTO component (tenant_id, ecosystem, name, version, "
                    + "purl_canonical, purl_original, canonicalization_version, is_canonicalizable) "
                    + "VALUES (?, 'maven', 'org.apache.logging.log4j.log4j-core', '2.14.1', "
                    + "'pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1', "
                    + "'pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1', 1, true) RETURNING id", TENANT);
            execute(c, "INSERT INTO component_entry (tenant_id, snapshot_id, component_id, relationship, depth) "
                    + "VALUES (?, ?, ?, 1, 0)", TENANT, snapshot, component);
            execute(c, "INSERT INTO sbom_coverage_state (tenant_id, asset_id, latest_snapshot_id, latest_snapshot_at, "
                    + "freshness_threshold_days, accountable_owner_id, quality, covered_ecosystems, declared_stack_ecosystems) "
                    + "VALUES (?, ?, ?, now(), 30, ?, 'ABOVE_WARNING', ARRAY['maven'], ARRAY['maven'])",
                    TENANT, payments, snapshot, LEAD);
            UUID advisory = returning(c, "INSERT INTO advisory (tenant_id, advisory_key, source, summary, description, "
                    + "severity_id, cvss_score, published_at, first_recorded_at, data_source) "
                    + "VALUES (?, 'CVE-2021-44228', 'NVD', 'Remote code execution in log4j', 'JNDI lookup', ?, 10.0, "
                    + "now() - interval '200 days', now() - interval '200 days', 'nvd') RETURNING id", TENANT, critical);
            execute(c, "INSERT INTO component_advisory (tenant_id, component_id, advisory_id, detected_at, "
                    + "fixed_version, source_tool) VALUES (?, ?, ?, now(), '2.17.1', 'match')",
                    TENANT, component, advisory);
            UUID team = returning(c, "INSERT INTO assessor_team (tenant_id, name) VALUES (?, 'Red Team') RETURNING id", TENANT);
            execute(c, "INSERT INTO assessor_team_member (tenant_id, team_id, principal_id) VALUES (?, ?, ?)", TENANT, team, LEAD);
            execute(c, "INSERT INTO ai_budget (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", TENANT);
            execute(c, "INSERT INTO ai_capability (tenant_id, code, suggestion_kind, subject_kind, surface, data_category, max_per_run, enabled) "
                    + "VALUES (?, 'copilot.chat', 'POSTURE_ANSWER', 'ORG_NODE', '/copilot', 'RECORD', 1, true) "
                    + "ON CONFLICT (tenant_id, code) DO UPDATE SET enabled = true", TENANT);
            c.commit();
        }
        copilot = new Copilot(dataSource);
        lead = new Principal(TENANT, LEAD,
                Set.of(Copilot.USE, "vul.finding.read", "ast.asset.read", "asm.request.read", "org.node.read",
                        "cap.team.read", Copilot.WORKLOAD_INDIVIDUAL, "sbm.coverage.read"),
                Set.of(root), true, false, false);
        owner = new Principal(TENANT, OWNER,
                Set.of(Copilot.USE, "vul.finding.read", "ast.asset.read"), Set.of(unitA), true, false, false);
        Object created = as(() -> new AiProviderService(dataSource).create(lead, "Fake", "OPENAI_COMPATIBLE",
                "http://127.0.0.1:" + port + "/v1", "fake-1", "sk-test", null, true));
        assertTrue(created instanceof String, "provider stored: " + created);
    }

    @AfterAll
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ==============================================================================================

    @Test
    @DisplayName("routing reads Vietnamese and English, with and without tone marks, and never returns nothing")
    void keywordRouting() {
        assertTrue(Copilot.keywordPacks("tổng hợp lỗ hổng high/critical còn đang open").contains("POSTURE"),
                "the question the product owner asked in Vietnamese, with tone marks");
        assertTrue(Copilot.keywordPacks("tong hop lo hong critical dang open").contains("POSTURE"),
                "and the same question typed without them, which is how it is usually typed");
        assertTrue(Copilot.keywordPacks("ứng dụng này đã được đánh giá bảo mật chưa").containsAll(
                        List.of("APPLICATIONS", "COVERAGE")),
                "named the application and asked about assessment: both packs");
        assertTrue(Copilot.keywordPacks("các request trễ SLA hoặc nguy cơ trễ").contains("SLA"));
        assertTrue(Copilot.keywordPacks("workload của từng người từng team").contains("WORKLOAD"));
        assertTrue(Copilot.keywordPacks("which requests are late").contains("SLA"));
        // A question that matches nothing is answered generally rather than refused; the engine
        // substitutes the default packs, which is asserted through ask() below.
        assertTrue(Copilot.keywordPacks("hello").isEmpty());
        assertTrue(Copilot.fold("Lỗ hổng ĐANG mở").contains("lo hong dang mo"), Copilot.fold("Lỗ hổng ĐANG mở"));
    }

    @Test
    @DisplayName("PRD-AIC-058 / SEC-AUZ-016: an application outside the asker's scope cannot be resolved, named or counted")
    void scopeBindsRetrieval() throws Exception {
        rulesOnly();
        Copilot.Turn mine = as(() -> copilot.ask(owner, Optional.empty(), "Does Retail Payments Portal carry any risk?"));
        assertTrue(facts(mine).contains("Retail Payments Portal"), facts(mine));

        // The same question about the other unit's application, asked by somebody who cannot reach it.
        Copilot.Turn theirs = as(() -> copilot.ask(owner, Optional.empty(), "Does Logistics Ledger carry any risk?"));
        String text = facts(theirs);
        assertFalse(text.contains("Logistics Ledger"),
                "an application outside the asker's scope was named in the facts: " + text);
        assertFalse(text.contains("Path traversal"),
                "a finding outside the asker's scope reached the answer: " + text);
        // And the lead, who can reach it, gets it — so the absence above is the scope predicate and
        // not a spelling mistake in the query.
        Copilot.Turn lead1 = as(() -> copilot.ask(lead, Optional.empty(), "Does Logistics Ledger carry any risk?"));
        assertTrue(facts(lead1).contains("Logistics Ledger"), facts(lead1));
    }

    @Test
    @DisplayName("PRD-AIC-048 / DOC-07 §5.2: a pack the asker may not read is named as withheld, not answered around")
    void permissionsGatePacksAndSayThatTheyDid() throws Exception {
        rulesOnly();
        Copilot.Turn withoutRoster = as(() -> copilot.ask(owner, Optional.empty(),
                "What is the workload of each team and each person?"));
        assertTrue(withoutRoster.answer().withheld().stream().anyMatch(w -> w.contains("cap.team.read")),
                "the roster pack must be named as withheld: " + withoutRoster.answer().withheld());
        assertFalse(facts(withoutRoster).contains("Red Team"),
                "a team was described to somebody who may not read the roster: " + facts(withoutRoster));
        assertTrue(withoutRoster.answer().text().contains("could not be answered"),
                "the answer must say which part it could not look at: " + withoutRoster.answer().text());

        // The lead reads teams and individuals: both appear, and the capacity statement PRD-CAP-014
        // requires travels with them.
        Copilot.Turn withRoster = as(() -> copilot.ask(lead, Optional.empty(),
                "What is the workload of each team and each person?"));
        assertTrue(withRoster.answer().withheld().isEmpty(), withRoster.answer().withheld().toString());
        assertTrue(facts(withRoster).contains("Red Team"), facts(withRoster));
        assertTrue(facts(withRoster).contains("Lan Lead"), "individual load is readable for this caller");
        assertTrue(facts(withRoster).contains("capacity planning"),
                "PRD-CAP-014: the statement sits with the measures, which in a conversation is the answer");

        // A principal who reads the roster but not individuals gets the team half and is told about
        // the other half. ADR-047: absent, not blanked.
        Principal teamOnly = new Principal(TENANT, LEAD,
                Set.of(Copilot.USE, "vul.finding.read", "ast.asset.read", "cap.team.read"),
                Set.of(root), true, false, false);
        Copilot.Turn partial = as(() -> copilot.ask(teamOnly, Optional.empty(), "workload per person please"));
        assertTrue(facts(partial).contains("Red Team"));
        assertFalse(facts(partial).contains("Lan Lead"), "individual load must be absent: " + facts(partial));
        assertTrue(partial.answer().withheld().stream().anyMatch(w -> w.contains(Copilot.WORKLOAD_INDIVIDUAL)),
                partial.answer().withheld().toString());
    }

    @Test
    @DisplayName("PRD-AIC-058: the platform resolves the record, and a follow-up inherits what it resolved")
    void entityResolutionAndFocus() throws Exception {
        rulesOnly();
        Copilot.Turn first = as(() -> copilot.ask(owner, Optional.empty(),
                "Ứng dụng Retail Payments Portal có rủi ro gì về bảo mật không?"));
        assertTrue(facts(first).contains("Retail Payments Portal"), facts(first));
        assertTrue(facts(first).contains("never assessed") || facts(first).contains("last assessed"),
                "the application's review state is part of what it means to ask about its risk: " + facts(first));

        // "Has it been assessed?" — no name, a demonstrative, and the conversation already on one.
        UUID conversation = UUID.fromString(first.conversation().id());
        Copilot.Turn second = as(() -> copilot.ask(owner, Optional.of(conversation), "Ứng dụng này đã được đánh giá chưa?"));
        assertTrue(facts(second).contains("Retail Payments Portal"),
                "the follow-up lost the subject the conversation was about: " + facts(second));

        // A name that matches nothing resolves to nothing rather than to something adjacent.
        Copilot.Turn nowhere = as(() -> copilot.ask(owner, Optional.empty(), "Does Zzz Nonexistent App carry any risk?"));
        assertFalse(facts(nowhere).contains("Zzz Nonexistent"), facts(nowhere));
    }

    @Test
    @DisplayName("PRD-AIC-058: the transcript is the platform's — a later turn is grounded in the stored history, not in anything the client sent")
    void historyComesFromTheServer() throws Exception {
        answerer.set(u -> u.contains("\"packs\"")
                ? "{\"packs\": [\"POSTURE\"], \"entities\": []}"
                : "{\"answer\": \"Two findings are open at CRITICAL [F2].\", \"citations\": [\"F2\"], \"insufficient\": false}");
        Copilot.Turn first = as(() -> copilot.ask(owner, Optional.empty(), "What is open on the retail estate?"));
        assertNotNull(first.answer().modelIdentity(), "the model wrote this one: " + first.answer().text());
        requests.clear();
        UUID conversation = UUID.fromString(first.conversation().id());
        as(() -> copilot.ask(owner, Optional.of(conversation), "And how old are they?"));
        String sent = String.join("\n", requests);
        assertTrue(sent.contains("What is open on the retail estate?"),
                "the second turn was not grounded in the first, so the conversation has no memory");
        assertTrue(sent.contains("conversation_history"),
                "the history must arrive through the fenced channel, like every other text a model reads (PRD-AIC-037)");

        // And it is in the rows, which is where it came from.
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            assertEquals(4L, scalar(c, "SELECT count(*) FROM ai_conversation_message WHERE conversation_id = ?", conversation),
                    "two questions and two answers are stored");
            // Both answers here were written by the model, and each carries the invocation it produced:
            // the transcript and the metering record are one join apart rather than correlated by time.
            assertEquals(2L, scalar(c, "SELECT count(*) FROM ai_conversation_message WHERE conversation_id = ? "
                    + "AND role = 'ASSISTANT' AND invocation_id IS NOT NULL", conversation),
                    "an answer the model wrote carries the invocation it produced (PRD-AIC-043)");
        }
    }

    @Test
    @DisplayName("SEC-AUZ-026: a conversation is one person's; another principal cannot replay it or continue it")
    void conversationsAreNotShared() throws Exception {
        rulesOnly();
        Copilot.Turn ownersOwn = as(() -> copilot.ask(owner, Optional.empty(), "What is open?"));
        UUID conversation = UUID.fromString(ownersOwn.conversation().id());
        assertFalse(as(() -> copilot.messages(owner, conversation)).isEmpty());
        assertTrue(as(() -> copilot.messages(lead, conversation)).isEmpty(),
                "another principal replayed a conversation that is not theirs");
        // Continuing it does not reach it either: the turn begins a new conversation of the lead's own.
        Copilot.Turn leadTurn = as(() -> copilot.ask(lead, Optional.of(conversation), "And what else?"));
        assertFalse(leadTurn.conversation().id().equals(conversation.toString()),
                "a conversation identifier from another person was accepted");
        assertFalse(as(() -> copilot.clear(lead, conversation)), "another principal cleared it");
    }

    @Test
    @DisplayName("PP-9 / PRD-AIC-036: with the capability off the answer is composed from the figures, is correct, and does not wear the generated label")
    void rulesFallbackAnswersAndSaysSo() throws Exception {
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "UPDATE ai_capability SET enabled = false WHERE code = 'copilot.chat'");
            c.commit();
        }
        try {
            Copilot.Turn turn = as(() -> copilot.ask(owner, Optional.empty(), "tong hop lo hong critical dang open"));
            assertNull(turn.answer().modelIdentity(), "no model was used");
            assertFalse(turn.answer().generated(), "the rules path is not generated content and must not be labelled as it");
            assertEquals("CAPABILITY_OFF", turn.answer().refusalCode());
            assertTrue(turn.answer().text().contains("2 findings are open"),
                    "the figures are still right: " + turn.answer().text());
            assertTrue(turn.answer().text().contains("Composed from the recorded figures"), turn.answer().text());
            assertFalse(turn.answer().facts().isEmpty(), "and the facts are still there to be checked");
        } finally {
            try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
                execute(c, "UPDATE ai_capability SET enabled = true WHERE code = 'copilot.chat'");
                c.commit();
            }
        }
    }

    @Test
    @DisplayName("PRD-AIC-033 / ADR-038: an answer citing a fact it was not given is rejected, and the figures answer instead")
    void groundingControlsRejectAndFallBack() throws Exception {
        answerer.set(u -> u.contains("\"packs\"")
                ? "{\"packs\": [\"POSTURE\"], \"entities\": []}"
                : "{\"answer\": \"Everything is fine [F99].\", \"citations\": [\"F99\"], \"insufficient\": false}");
        Copilot.Turn turn = as(() -> copilot.ask(owner, Optional.empty(), "What is open right now?"));
        assertEquals("UNRESOLVED_CITATION", turn.answer().refusalCode(), turn.answer().text());
        assertNull(turn.answer().modelIdentity());
        assertFalse(turn.answer().text().contains("Everything is fine"),
                "the rejected answer must not be shown: " + turn.answer().text());
        assertTrue(turn.answer().text().contains("findings are open"), turn.answer().text());

        // A figure the facts do not carry is the other half of ADR-038.
        answerer.set(u -> u.contains("\"packs\"")
                ? "{\"packs\": [\"POSTURE\"], \"entities\": []}"
                : "{\"answer\": \"There are 47 open findings [F2].\", \"citations\": [\"F2\"], \"insufficient\": false}");
        Copilot.Turn invented = as(() -> copilot.ask(owner, Optional.empty(), "How many are open in total?"));
        assertEquals("INVENTED_NUMBER", invented.answer().refusalCode(), invented.answer().text());
    }

    @Test
    @DisplayName("retrieval is per question: a question about commitments does not drag the component inventory into the prompt")
    void retrievalIsNarrow() throws Exception {
        rulesOnly();
        Copilot.Turn sla = as(() -> copilot.ask(lead, Optional.empty(), "Which requests are late or at risk of being late?"));
        assertTrue(sla.answer().topics().contains("SLA"), sla.answer().topics().toString());
        assertFalse(sla.answer().topics().contains("SBOM"),
                "the component inventory was retrieved for a question about deadlines: " + sla.answer().topics());
        Copilot.Turn everything = as(() -> copilot.ask(lead, Optional.empty(), "hello"));
        assertFalse(everything.answer().topics().isEmpty(),
                "a question that matches no keyword is answered generally, not refused");
    }

    @Test
    @DisplayName("starters and the pack list are filtered by what the caller may read")
    void startersMatchPermissions() throws Exception {
        List<Copilot.Starter> forOwner = as(() -> copilot.starters(owner));
        assertFalse(forOwner.isEmpty());
        assertTrue(forOwner.stream().noneMatch(s -> s.pack().equals("WORKLOAD")),
                "a starter nobody could answer for this caller: " + forOwner);
        assertTrue(forOwner.stream().anyMatch(s -> s.text().contains("Retail Payments Portal")),
                "the opening offer names an application the caller can actually reach: " + forOwner);
        List<Copilot.Starter> forLead = as(() -> copilot.starters(lead));
        assertTrue(forLead.stream().anyMatch(s -> s.pack().equals("WORKLOAD")));
    }

    @Test
    @DisplayName("a how-to question is answered from the product's own guide, in the language it was asked in")
    void guideAnswersHowToQuestions() throws Exception {
        rulesOnly();
        Copilot.Turn english = as(() -> copilot.ask(owner, Optional.empty(), "How do I raise an assessment request?"));
        assertTrue(english.answer().topics().contains("HOWTO"), english.answer().topics().toString());
        assertTrue(facts(english).contains("user guide, section"), facts(english));
        assertTrue(facts(english).toLowerCase(Locale.ROOT).contains("request"), facts(english));

        // The same question in Vietnamese finds the Vietnamese translation, without a language switch
        // anywhere: the question's own words score against the document written in them.
        Copilot.Turn vietnamese = as(() -> copilot.ask(owner, Optional.empty(),
                "Làm sao để tạo một yêu cầu đánh giá?"));
        assertTrue(vietnamese.answer().topics().contains("HOWTO"), vietnamese.answer().topics().toString());
        List<Knowledge.Hit> hits = Knowledge.search("Làm sao để tạo một yêu cầu đánh giá?", Set.of("guide"), 3);
        assertFalse(hits.isEmpty());
        assertEquals("vi", hits.get(0).section().locale(),
                "the Vietnamese question ranked an English section first: " + hits.get(0).section().heading());
    }

    @Test
    @DisplayName("an API question answers from the integration guide AND from the registry the dispatcher enforces")
    void apiAnswersFromGuideAndRegistry() throws Exception {
        rulesOnly();
        Copilot.Turn turn = as(() -> copilot.ask(owner, Optional.empty(),
                "How do I submit scan results through the API, and how do I sign the request?"));
        assertTrue(turn.answer().topics().contains("API"), turn.answer().topics().toString());
        String text = facts(turn);
        assertTrue(text.contains("integration guide, section"), text);
        // The question says "scan results" and the endpoint is called finding-imports: the guide section
        // that answers it names the path, and the registry is then asked about that path. Neither a
        // synonym table nor the model supplies the link.
        assertTrue(text.contains("API operation: POST /api/v1/finding-imports"),
                "the operation itself must come from the registry, not from prose: " + text);
        assertFalse(turn.answer().topics().contains("APPLICATIONS"),
                "a question about calling the API must not drag in the application inventory: " + turn.answer().topics());
        assertTrue(text.contains("HMAC-SHA256"), "the authentication model travels with every API answer: " + text);
        // Whether the caller holds the permission is stated, and the operation is not hidden from them.
        assertTrue(text.contains("you do not hold it") || text.contains("you hold it"), text);
    }

    @Test
    @DisplayName("an access question answers from the tenant's own roles, and the role detail is withheld without auz.role.manage")
    void accessAnswersFromRolesAndGatesTheDetail() throws Exception {
        rulesOnly();
        Principal userReader = new Principal(TENANT, LEAD,
                Set.of(Copilot.USE, "iam.user.read"), Set.of(root), true, false, false);
        Copilot.Turn shallow = as(() -> copilot.ask(userReader, Optional.empty(),
                "Phân quyền cho team dev thế nào?"));
        assertTrue(shallow.answer().topics().contains("ACCESS"), shallow.answer().topics().toString());
        assertTrue(facts(shallow).contains("active people"), facts(shallow));
        assertTrue(shallow.answer().withheld().stream().anyMatch(w -> w.contains(Copilot.ACCESS_ROLE_DETAIL)),
                shallow.answer().withheld().toString());

        Principal roleAdmin = new Principal(TENANT, LEAD,
                Set.of(Copilot.USE, "iam.user.read", Copilot.ACCESS_ROLE_DETAIL), Set.of(root), true, false, false);
        Copilot.Turn deep = as(() -> copilot.ask(roleAdmin, Optional.empty(),
                "What does the DEVELOPER role carry, and who holds it?"));
        String text = facts(deep);
        assertTrue(text.contains("role \"DEVELOPER\""), text);
        assertTrue(text.contains("carries exactly these permissions"),
                "a named role is answered with its permissions, not with a count: " + text);
        assertTrue(text.contains("scope mode"), "a grant is a role and a scope; the scope half is part of the answer");
        assertTrue(deep.answer().withheld().isEmpty(), deep.answer().withheld().toString());
    }

    @Test
    @DisplayName("the documentation packs need no permission, and carry no tenant data")
    void documentationIsReadableByAnybodySignedIn() throws Exception {
        rulesOnly();
        Principal bare = new Principal(TENANT, OWNER, Set.of(Copilot.USE), Set.of(unitA), true, false, false);
        Copilot.Turn turn = as(() -> copilot.ask(bare, Optional.empty(), "How do I use this platform?"));
        assertTrue(turn.answer().topics().contains("HOWTO"),
                "a caller with no read permission at all still gets the guide: " + turn.answer().topics());
        assertFalse(facts(turn).contains("Retail Payments Portal"),
                "the guide packs must carry no tenant data: " + facts(turn));
    }

    @Test
    @DisplayName("\"which application should I assess this month\" is answered from what is owed, ranked, not from the plan alone")
    void assessNextRanksWhatIsOwed() throws Exception {
        rulesOnly();
        Copilot.Turn turn = as(() -> copilot.ask(lead, Optional.empty(),
                "tôi nên đánh giá bảo mật với ứng dụng nào trong tháng 9 này"));
        assertTrue(turn.answer().topics().contains("ASSESS_NEXT"), turn.answer().topics().toString());
        String text = facts(turn);
        // The applications are named, with the facts that make each urgent beside them.
        assertTrue(text.contains("owed an assessment: \"Logistics Ledger\""), text);
        assertTrue(text.contains("owed an assessment: \"Retail Payments Portal\""), text);
        assertTrue(text.contains("never assessed"), text);
        assertTrue(text.contains("exposure INTERNET_PUBLIC"), text);
        assertTrue(text.contains("open findings at the two most severe levels"), text);
        // Today's date is a fact, so "this month" is a comparison the answer can make rather than a
        // period it has to guess the boundaries of.
        assertTrue(text.contains("today is " + java.time.LocalDate.now()), text);
        // The one with a window already planned says so, and comes after the one without.
        assertTrue(text.contains("an assessment window is already planned from"), text);
        assertTrue(text.contains("NO assessment window planned"), text);
        assertTrue(text.indexOf("owed an assessment: \"Logistics Ledger\"")
                        < text.indexOf("owed an assessment: \"Retail Payments Portal\""),
                "an application nobody has planned must come before one already in the plan: " + text);
        // And the ordering is stated, so the answer can cite it rather than invent a rationale.
        assertTrue(text.contains("is not the risk score"), text);
        assertTrue(turn.answer().text().contains("owed an assessment"), turn.answer().text());
    }

    @Test
    @DisplayName("PRD-AIC-033: an answer that stops mid-sentence is rejected rather than shown as though it were complete")
    void incompleteAnswersAreRejected() throws Exception {
        // The live failure, reproduced: every other control passes — it cites a fact it was given,
        // invents no figure, contradicts no severity — and it stops before the word that was asked for.
        answerer.set(u -> u.contains("\"packs\"")
                ? "{\"packs\": [\"ASSESS_NEXT\"], \"entities\": []}"
                : "{\"answer\": \"Dựa trên kế hoạch hiện tại, bạn nên đánh giá bảo mật ứng dụng\","
                        + " \"citations\": [\"F3\"], \"insufficient\": false}");
        Copilot.Turn turn = as(() -> copilot.ask(lead, Optional.empty(), "ứng dụng nào nên đánh giá tháng này?"));
        assertEquals("INCOMPLETE_ANSWER", turn.answer().refusalCode(), turn.answer().text());
        assertFalse(turn.answer().text().contains("Dựa trên kế hoạch hiện tại"),
                "the fragment must not be shown: " + turn.answer().text());
        assertTrue(turn.answer().text().contains("owed an assessment"),
                "and the figures answer instead: " + turn.answer().text());

        // A finished sentence ending on a citation is not a fragment and must still pass.
        answerer.set(u -> u.contains("\"packs\"")
                ? "{\"packs\": [\"ASSESS_NEXT\"], \"entities\": []}"
                : "{\"answer\": \"Two applications are owed an assessment [F3].\","
                        + " \"citations\": [\"F3\"], \"insufficient\": false}");
        Copilot.Turn good = as(() -> copilot.ask(lead, Optional.empty(), "ứng dụng nào nên đánh giá trong quý này?"));
        assertNull(good.answer().refusalCode(), good.answer().text());
        assertNotNull(good.answer().modelIdentity());
    }

    @Test
    @DisplayName("direction over time is retrieved, and an absence of closures is stated rather than shown as speed")
    void trendCarriesDirectionAndTiming() throws Exception {
        rulesOnly();
        Copilot.Turn turn = as(() -> copilot.ask(lead, Optional.empty(),
                "So với ba tháng trước thì tăng hay giảm, và tốc độ khắc phục là bao lâu?"));
        assertTrue(turn.answer().topics().contains("TREND"), turn.answer().topics().toString());
        String text = facts(turn);
        assertTrue(text.contains("backlog direction:"), text);
        assertTrue(text.contains("ninety days ago"), text);
        assertTrue(text.contains("month " + java.time.YearMonth.now()), "the months are labelled: " + text);
        // Nothing has closed in this fixture. A median over no closures is not a fast team.
        assertTrue(text.contains("nothing has been closed in the last 180 days"), text);
        assertFalse(text.contains("median 0"), "an absent measurement must not be rendered as a fast one: " + text);
    }

    @Test
    @DisplayName("retrieval is capped: a question touching everything does not hand the model a filing cabinet")
    void retrievalIsCapped() throws Exception {
        rulesOnly();
        // Every keyword family at once. Measured on twenty manager questions: the two that retrieved
        // seven and eight packs were the two whose answers cited nothing and were rejected.
        Copilot.Turn turn = as(() -> copilot.ask(lead, Optional.empty(),
                "rủi ro lỗ hổng ứng dụng đánh giá SLA trễ request workload team đơn vị ngoại lệ kế hoạch "
                        + "thành phần API phân quyền hướng dẫn xu hướng nên đánh giá ứng dụng nào"));
        assertTrue(turn.answer().topics().size() <= 5,
                "retrieval must be capped at five packs, got " + turn.answer().topics());
        assertFalse(turn.answer().topics().isEmpty());
    }

    @Test
    @DisplayName("PP-1: where almost nothing is assigned, the per-person figures say so rather than reading as spare capacity")
    void unmeasuredLoadSaysSo() throws Exception {
        rulesOnly();
        Copilot.Turn turn = as(() -> copilot.ask(lead, Optional.empty(), "đội bảo mật quá tải hay còn dư năng lực?"));
        assertTrue(turn.answer().topics().contains("WORKLOAD"), turn.answer().topics().toString());
        String text = facts(turn);
        assertTrue(text.contains("The load here is unmeasured"), text);
        assertTrue(text.contains("must NOT be read as"), text);
        // And it is stated before the zeros it is about.
        assertTrue(text.indexOf("The load here is unmeasured") < text.indexOf("assessor team \"Red Team\""), text);
    }

    @Test
    @DisplayName("a question about dependencies names the vulnerable component, what carries it, and whether a fix exists")
    void componentsAreNamedWithTheirFixes() throws Exception {
        rulesOnly();
        Copilot.Turn turn = as(() -> copilot.ask(lead, Optional.empty(),
                "Chúng ta có phụ thuộc vào thư viện nào đang có lỗ hổng không?"));
        assertTrue(turn.answer().topics().contains("SBOM"), turn.answer().topics().toString());
        String text = facts(turn);
        assertTrue(text.contains("vulnerable component \"org.apache.logging.log4j.log4j-core@2.14.1\""), text);
        assertTrue(text.contains("CVE-2021-44228"), text);
        assertTrue(text.contains("fixed in version 2.17.1"),
                "whether a fix exists is the fact that decides whether this is an upgrade or a decision: " + text);
        assertTrue(text.contains("a direct dependency somebody chose"), text);
        // The coverage caveat travels with the counts, always.
        assertTrue(text.contains("no component vulnerability has been matched against them"), text);
        assertTrue(turn.answer().text().contains("component advisories are open"), turn.answer().text());

        // A component the question names is resolved, and the assets carrying it are listed.
        Copilot.Turn named = as(() -> copilot.ask(lead, Optional.empty(),
                "Chúng ta có bị ảnh hưởng bởi log4j không, ứng dụng nào?"));
        assertTrue(facts(named).contains("the question names \"log4j\""), facts(named));
        assertTrue(facts(named).contains("Retail Payments Portal"), facts(named));
    }

    @Test
    @DisplayName("V082: every copilot operation is gated on the copilot's own permission, and the draft surfaces keep theirs")
    void copilotHasItsOwnPermission() throws Exception {
        assertFalse(Copilot.USE.equals(Assistant.USE),
                "sharing one permission is what made 'drafting help yes, conversation no' inexpressible");
        List<String> routes = List.of("/api/ui/ai/copilot", "/api/ui/ai/copilot/{id}",
                "/api/ui/ai/copilot/{id}/clear");
        int gated = 0;
        for (var operation : aspm.app.api.PlatformOperations.registry().all()) {
            if (!routes.contains(operation.pathTemplate())) {
                continue;
            }
            gated++;
            assertEquals(Optional.of(Copilot.USE), operation.requiredPermission(),
                    operation.method() + " " + operation.pathTemplate() + " is not behind the copilot's permission, "
                            + "so revoking it would not stop anybody reaching the copilot");
        }
        assertEquals(4, gated, "the copilot's four operations must all be gated");
        // The single-question box and the draft button are untouched: a tenant taking the copilot away
        // from a role must not take drafting help with it.
        for (var operation : aspm.app.api.PlatformOperations.registry().all()) {
            if (operation.pathTemplate().equals("/api/ui/ai/ask") || operation.pathTemplate().equals("/api/ui/ai/draft")) {
                assertEquals(Optional.of(Assistant.USE), operation.requiredPermission(), operation.pathTemplate());
            }
        }
        // And the permission is in the product-fixed catalogue, so a tenant can compose a role from it.
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            assertEquals(1L, scalar(c, "SELECT count(*) FROM permission_catalogue WHERE code = ?", Copilot.USE),
                    "a permission absent from the catalogue is a capability nobody can grant on purpose");
            assertEquals(0L, scalar(c, "SELECT count(*) FROM permission_catalogue "
                    + "WHERE code = ? AND (is_restricted OR requires_step_up)", Copilot.USE),
                    "it reveals nothing the holder could not open a screen and read");
        }
    }

    // ==============================================================================================

    /** Makes the provider useless for this turn, so the rules path is what is under test. */
    private void rulesOnly() {
        answerer.set(u -> "not json at all");
    }

    private static String facts(Copilot.Turn turn) {
        StringBuilder text = new StringBuilder();
        for (Assistant.Fact fact : turn.answer().facts()) {
            text.append(fact.ref()).append(": ").append(fact.text()).append('\n');
        }
        return text.toString();
    }

    private static UUID node(Connection c, UUID type, UUID parent, String name) throws SQLException {
        return returning(c, "INSERT INTO org_node (tenant_id, type_id, parent_id, name, criticality_mode) "
                + "VALUES (?, ?, ?, ?, 'INHERITED') RETURNING id", TENANT, type, parent, name);
    }

    private static void closure(Connection c, UUID ancestor, UUID descendant, int depth) throws SQLException {
        execute(c, "INSERT INTO org_closure (tenant_id, ancestor_id, descendant_id, depth, hierarchy_version) "
                + "VALUES (?, ?, ?, ?, 1) ON CONFLICT DO NOTHING", TENANT, ancestor, descendant, depth);
    }

    private static UUID application(Connection c, UUID type, UUID tier, UUID node, String name) throws SQLException {
        return returning(c, "INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, display_name, "
                + "owning_node_id, criticality_mode, criticality_tier_id, exposure_declared, lifecycle_state, "
                + "discovery_source, discovery_method, first_seen_at, last_confirmed_at) "
                + "VALUES (?, ?, ?, 1, ?, ?, 'ASSIGNED', ?, 'INTERNET_PUBLIC', 'ACTIVE', 'manual', 'MANUAL', now(), now()) "
                + "RETURNING id", TENANT, type, name.toLowerCase(Locale.ROOT).replace(' ', '-'), name, node, tier);
    }

    private static void finding(Connection c, UUID scope, UUID severity, String title, UUID asset) throws SQLException {
        byte[] digest = new byte[32];
        new java.security.SecureRandom().nextBytes(digest);
        UUID id = returning(c, "INSERT INTO finding (tenant_id, fingerprint_digest, fingerprint_algorithm_version, "
                + "finding_class, title, description, state, source_tool, raw_source_record_ref, first_detected_at, "
                + "last_detected_at, scope_node_id, reported_severity_id) "
                + "VALUES (?, ?, 1, 'CODE', ?, 'seeded', 'OPEN', 'manual-entry', 'test', now() - interval '100 days', "
                + "now() - interval '1 day', ?, ?) RETURNING id", TENANT, digest, title, scope, severity);
        execute(c, "INSERT INTO finding_asset_impact (tenant_id, finding_id, asset_id, first_detected_at, "
                + "last_detected_at, scope_node_id) VALUES (?, ?, ?, now() - interval '100 days', now(), ?)",
                TENANT, id, asset, scope);
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

    private static long scalar(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
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
