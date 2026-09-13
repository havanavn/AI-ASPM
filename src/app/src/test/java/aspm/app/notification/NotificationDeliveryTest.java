package aspm.app.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.app.egress.EgressGuard;
import aspm.app.notification.channel.ChannelSender;
import aspm.app.notification.channel.HttpSenders;
import aspm.app.persistence.AllMigrations;
import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Json;
import aspm.app.runtime.Principal;
import aspm.app.secrets.Secrets;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Notification delivery end to end against the platform's schema: channel, verification, routes,
 * emission, the outbox worker, and every way a delivery may not happen. {@code PRD-NTF-013},
 * {@code PRD-NTF-014}, {@code PRD-NTF-018}, {@code PRD-NTF-019}, {@code PRD-NTF-020}, {@code PRD-NTF-021},
 * {@code PRD-NTF-024}, {@code PRD-NTF-031}, {@code PRD-NTF-032}, {@code PRD-NTF-042}, {@code PRD-NTF-043},
 * {@code CON-PLT-031}, {@code CON-PLT-032}, ADR-054.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationDeliveryTest {

    private static final UUID TENANT = UUID.fromString("7b000000-0000-4000-8000-000000000001");
    private static final UUID ADMIN = UUID.fromString("7b000000-0000-4000-8000-0000000000ad");
    private static final UUID ALICE = UUID.fromString("7b000000-0000-4000-8000-00000000a11c");
    private static final UUID BOB = UUID.fromString("7b000000-0000-4000-8000-000000000b0b");

    private DataSource dataSource;
    private HttpServer receiver;
    private String receiverUrl;
    private final List<String> received = new CopyOnWriteArrayList<>();
    /** The HTTP status the fake answers with; tests change it. */
    private final AtomicInteger answer = new AtomicInteger(200);

    private Secrets secrets;
    private NotificationChannelService channels;
    private DeliveryWorker worker;
    private Principal admin;
    private UUID orgNode;
    private UUID requestId;

    @BeforeAll
    void start() throws Exception {
        dataSource = AllMigrations.dataSource();
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(exchange.getRequestHeaders().getFirst("X-ASPM-Signature") + " | " + body);
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer.get(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        receiver.start();
        receiverUrl = "https://hooks.test/notify";
        String loopback = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/notify";

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
        // A generic webhook sender whose destination is rewritten to the fake, as the OIDC tests do.
        ChannelSender webhook = new ChannelSender() {
            private final ChannelSender real = HttpSenders.all(egress).stream().filter(s -> s.kind().equals("GENERIC_WEBHOOK")).findFirst().orElseThrow();

            @Override public String kind() { return real.kind(); }
            @Override public boolean requiresSecret() { return real.requiresSecret(); }
            @Override public Map<String, Object> validate(Map<String, Object> config, boolean secretPresent) { return real.validate(config, secretPresent); }

            @Override
            public Outcome send(Map<String, Object> config, Optional<char[]> secret, Optional<String> address, Message message) {
                Map<String, Object> rewritten = new java.util.LinkedHashMap<>(config);
                rewritten.put("url", String.valueOf(config.get("url")).replace(receiverUrl, loopback));
                return real.send(rewritten, secret, address, message);
            }
        };
        channels = new NotificationChannelService(dataSource, secrets, List.of(webhook));
        worker = new DeliveryWorker(dataSource, TENANT, secrets, List.of(webhook));
        admin = new Principal(TENANT, ADMIN, Set.of(NotificationChannelService.MANAGE), Set.of(), true, false, false);

        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "INSERT INTO tenant (id, display_name, lifecycle_state, residency_region, key_reference, entitlement_tier) "
                    + "VALUES (?, 'Notification test', 'ACTIVE', 'VN', 'vault://test', 'STANDARD') ON CONFLICT (id) DO NOTHING", TENANT);
            execute(c, "INSERT INTO password_policy (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", TENANT);
            for (Object[] p : new Object[][] {{ADMIN, "ntf.admin", "Admin"}, {ALICE, "alice", "Alice"}, {BOB, "bob", "Bob"}}) {
                execute(c, "INSERT INTO principal (id, tenant_id, kind, username, email, display_name, lifecycle_state) "
                        + "VALUES (?, ?, 'HUMAN', ?, ?, ?, 'ACTIVE')", p[0], TENANT, p[1], p[1] + "@example.com", p[2]);
            }
            // A root node and a request in it, so there is a scoped subject to be visible or not.
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO org_node_type (tenant_id, code, label_i18n, ordinal, may_own_assets, may_scope_work) "
                            + "VALUES (?, 'GROUP', '{\"en\":\"Group\"}', 1, true, true) RETURNING id")) {
                s.setObject(1, TENANT);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    UUID typeId = r.getObject(1, UUID.class);
                    try (PreparedStatement n = c.prepareStatement(
                            "INSERT INTO org_node (tenant_id, type_id, name, criticality_mode) VALUES (?, ?, 'Root', 'INHERITED') RETURNING id")) {
                        n.setObject(1, TENANT);
                        n.setObject(2, typeId);
                        try (ResultSet rr = n.executeQuery()) {
                            rr.next();
                            orgNode = rr.getObject(1, UUID.class);
                        }
                    }
                }
            }
            // A tenant-wide assignment for Alice and Bob: the delivery-time visibility check (PRD-NTF-029)
            // is real, so a recipient with no scope over the subject is correctly suppressed — which is a
            // separate test, not an accident of the fixture.
            UUID roleId;
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO role (tenant_id, code, label_i18n) VALUES (?, 'NTF_READER', '{\"en\":\"Reader\"}') RETURNING id")) {
                s.setObject(1, TENANT);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    roleId = r.getObject(1, UUID.class);
                }
            }
            for (UUID who : List.of(ALICE, BOB)) {
                execute(c, "INSERT INTO role_assignment (tenant_id, principal_id, role_id, scope_mode) VALUES (?, ?, ?, 'TENANT')", TENANT, who, roleId);
            }
            c.commit();
        }
        requestId = UUID.randomUUID();
    }

    @AfterAll
    void stop() {
        if (receiver != null) {
            receiver.stop(0);
        }
    }

    @Test
    @DisplayName("PRD-NTF-043 / PRD-NTF-019 / PRD-NTF-032 / ADR-054: a channel is verified by a code sent through itself, routed for a category, and the worker delivers minimal content signed to it")
    void verifyRouteDeliver() throws Exception {
        NotificationChannelService.Channel channel = asAdmin(() -> channels.create(admin, "ops-hook", "Ops webhook", "GENERIC_WEBHOOK",
                Map.of("url", receiverUrl), Optional.of("signing-key".toCharArray()), false));
        assertFalse(channel.verified());
        assertTrue(channel.secretHeld());

        // An unverified channel routes nothing, even with a route configured.
        asAdmin(() -> channels.setRoutes(admin, List.of(Map.of("category", "REQUEST", "channelId", channel.id().toString()))));
        emit("request.transitioned", Map.of("state", "ACCEPTED"), Set.of(ALICE));
        assertEquals(0L, count("SELECT count(*) FROM notification_delivery d JOIN notification n ON n.id = d.notification_id WHERE n.subject_id = ?", requestId));
        assertEquals(1L, count("SELECT count(*) FROM notification WHERE recipient_principal_id = ? AND subject_id = ?", ALICE, requestId),
                "PRD-NTF-018: the in-product row is written regardless");

        // Verification: the code travels through the channel and is typed back.
        received.clear();
        asAdmin(() -> channels.sendVerification(admin, channel.id(), Optional.empty()));
        String code = codeFrom(received.get(0));
        assertThrows(IllegalArgumentException.class, () -> asAdmin(() -> channels.confirmVerification(admin, channel.id(), "000000")),
                "a wrong code is refused");
        NotificationChannelService.Channel verified = asAdmin(() -> channels.confirmVerification(admin, channel.id(), code));
        assertTrue(verified.verified());

        // Now an event produces a delivery, and the worker sends it — signed, minimal.
        received.clear();
        emit("request.transitioned", Map.of("state", "SCHEDULED"), Set.of(ALICE));
        assertEquals(1L, count("SELECT count(*) FROM notification_delivery d JOIN notification n ON n.id = d.notification_id WHERE n.subject_id = ? AND d.status = 'QUEUED'", requestId));
        assertTrue(worker.tick() >= 1);
        assertEquals("SENT", statusOf(requestId));
        String call = received.get(0);
        assertTrue(call.startsWith("sha256="), "the webhook is signed: " + call);
        Map<String, Object> payload = Json.readObject(call.substring(call.indexOf("| ") + 2));
        assertTrue(String.valueOf(payload.get("title")).contains("REQ-N1"), payload.toString());
        assertEquals("/board/" + requestId, payload.get("link"));
        assertFalse(payload.containsKey("body"), "PRD-NTF-032: no detail unless the channel opted in");
        assertEquals("SENT", scalar("SELECT last_status FROM notification_channel WHERE id = ?", channel.id()));
    }

    @Test
    @DisplayName("PRD-NTF-024: two events on the same subject within the window merge into one notification and one delivery")
    void coalescing() throws Exception {
        NotificationChannelService.Channel channel = verifiedChannel("coalesce-hook");
        asAdmin(() -> channels.setRoutes(admin, List.of(Map.of("category", "REQUEST", "channelId", channel.id().toString()))));
        UUID subject = UUID.randomUUID();
        emit("request.transitioned", subject, "REQ-C", Map.of("state", "A"), Set.of(BOB));
        emit("request.transitioned", subject, "REQ-C", Map.of("state", "B"), Set.of(BOB));
        assertEquals(1L, count("SELECT count(*) FROM notification WHERE recipient_principal_id = ? AND subject_id = ?", BOB, subject));
        assertEquals(2, ((Number) scalar("SELECT merged_count FROM notification WHERE recipient_principal_id = ? AND subject_id = ?", BOB, subject)).intValue());
        assertEquals(1L, count("SELECT count(*) FROM notification_delivery d JOIN notification n ON n.id = d.notification_id WHERE n.subject_id = ?", subject));
        received.clear();
        worker.tick();
        assertTrue(received.get(0).contains("2 updates"), "the merged count reaches the channel: " + received.get(0));
    }

    @Test
    @DisplayName("PRD-NTF-031: a recipient who can no longer see the subject gets a SUPPRESSED delivery, never a redacted one")
    void suppressedWhenNotVisible() throws Exception {
        NotificationChannelService.Channel channel = verifiedChannel("suppress-hook");
        asAdmin(() -> channels.setRoutes(admin, List.of(Map.of("category", "REQUEST", "channelId", channel.id().toString()))));
        UUID carol = UUID.randomUUID();
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "INSERT INTO principal (id, tenant_id, kind, username, email, display_name, lifecycle_state) "
                    + "VALUES (?, ?, 'HUMAN', 'carol', 'carol@example.com', 'Carol', 'ACTIVE')", carol, TENANT);
            c.commit();
        }
        UUID subject = UUID.randomUUID();
        emit("request.transitioned", subject, "REQ-S", Map.of("state", "A"), Set.of(carol));
        // Between enqueue and delivery, Carol is deprovisioned.
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "UPDATE principal SET lifecycle_state = 'DEPROVISIONED' WHERE id = ?", carol);
            c.commit();
        }
        received.clear();
        worker.tick();
        assertEquals("SUPPRESSED", scalar("SELECT d.status FROM notification_delivery d JOIN notification n ON n.id = d.notification_id "
                + "WHERE n.subject_id = ?", subject));
        assertTrue(received.isEmpty(), "nothing left the platform");
    }

    @Test
    @DisplayName("PRD-NTF-042 / CON-PLT-032: a transient failure is retried with backoff; a credential failure is terminal at once")
    void retryAndTerminal() throws Exception {
        NotificationChannelService.Channel channel = verifiedChannel("retry-hook");
        asAdmin(() -> channels.setRoutes(admin, List.of(Map.of("category", "REQUEST", "channelId", channel.id().toString()))));
        UUID subject = UUID.randomUUID();
        emit("request.transitioned", subject, "REQ-R", Map.of("state", "A"), Set.of(ALICE));
        answer.set(503);
        worker.tick();
        assertEquals("QUEUED", statusOf(subject));
        assertEquals("TRANSIENT", scalar("SELECT d.last_failure_class FROM notification_delivery d JOIN notification n ON n.id = d.notification_id WHERE n.subject_id = ?", subject));
        assertTrue(time("SELECT d.next_attempt_at FROM notification_delivery d JOIN notification n ON n.id = d.notification_id WHERE n.subject_id = ?", subject)
                .isAfter(java.time.OffsetDateTime.now().plusSeconds(1)), "backed off into the future");
        // Nothing is due for this subject yet: a tick leaves it queued with its attempt count.
        worker.tick();
        assertEquals("QUEUED", statusOf(subject));
        // Bring it due, let the target recover.
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "UPDATE notification_delivery SET next_attempt_at = now() WHERE notification_id IN (SELECT id FROM notification WHERE subject_id = ?)", subject);
            c.commit();
        }
        answer.set(200);
        worker.tick();
        assertEquals("SENT", statusOf(subject));
        assertEquals(2, ((Number) scalar("SELECT d.attempts FROM notification_delivery d JOIN notification n ON n.id = d.notification_id WHERE n.subject_id = ?", subject)).intValue());

        UUID subject2 = UUID.randomUUID();
        emit("request.transitioned", subject2, "REQ-R2", Map.of("state", "A"), Set.of(ALICE));
        answer.set(401);
        worker.tick();
        assertEquals("DEAD", statusOf(subject2));
        assertEquals("AUTHENTICATION", scalar("SELECT d.last_failure_class FROM notification_delivery d JOIN notification n ON n.id = d.notification_id WHERE n.subject_id = ?", subject2));
        assertTrue(((Number) scalar("SELECT consecutive_failures FROM notification_channel WHERE id = ?", channel.id())).intValue() >= 1);
        answer.set(200);
    }

    @Test
    @DisplayName("PRD-NTF-020 / PRD-NTF-021 / PRD-NTF-017: a mute stops external delivery of a non-mandatory category, quiet hours defer it, and a mandatory category ignores both")
    void preferences() throws Exception {
        NotificationChannelService.Channel channel = verifiedChannel("pref-hook");
        asAdmin(() -> channels.setRoutes(admin, List.of(
                Map.of("category", "REQUEST", "channelId", channel.id().toString()),
                Map.of("category", "SYSTEM", "channelId", channel.id().toString()))));
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "INSERT INTO notification_preference (tenant_id, principal_id, muted_categories) VALUES (?, ?, '{REQUEST}') "
                    + "ON CONFLICT (tenant_id, principal_id) DO UPDATE SET muted_categories = '{REQUEST}', quiet_start_minute = NULL, quiet_end_minute = NULL", TENANT, BOB);
            c.commit();
        }
        UUID muted = UUID.randomUUID();
        emit("request.transitioned", muted, "REQ-M", Map.of("state", "A"), Set.of(BOB));
        assertEquals(1L, count("SELECT count(*) FROM notification WHERE subject_id = ?", muted), "the centre still shows it");
        assertEquals(0L, count("SELECT count(*) FROM notification_delivery d JOIN notification n ON n.id = d.notification_id WHERE n.subject_id = ?", muted));

        // Quiet hours covering the whole day: a non-mandatory delivery is deferred, a mandatory one is not.
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "UPDATE notification_preference SET muted_categories = '{}', quiet_start_minute = 0, quiet_end_minute = 1439, timezone = 'UTC' WHERE principal_id = ?", BOB);
            c.commit();
        }
        UUID quiet = UUID.randomUUID();
        emit("request.transitioned", quiet, "REQ-Q", Map.of("state", "A"), Set.of(BOB));
        java.time.OffsetDateTime due = time("SELECT d.next_attempt_at FROM notification_delivery d JOIN notification n ON n.id = d.notification_id WHERE n.subject_id = ?", quiet);
        assertTrue(due.isAfter(java.time.OffsetDateTime.now().plusMinutes(1)), "deferred to the end of the quiet window: " + due);

        UUID mandatory = UUID.randomUUID();
        Notifier.Event event = new Notifier.Event("channel.verification", "PLATFORM", mandatory, Optional.empty(), "verification",
                Optional.empty(), "The platform", Map.of(), Optional.empty());
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            Notifier.emit(c, TENANT, event, Set.of(BOB));
            c.commit();
        }
        java.time.OffsetDateTime dueNow = time("SELECT d.next_attempt_at FROM notification_delivery d JOIN notification n ON n.id = d.notification_id WHERE n.subject_id = ?", mandatory);
        assertFalse(dueNow.isAfter(java.time.OffsetDateTime.now().plusSeconds(5)), "mandatory ignores quiet hours");
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "UPDATE notification_preference SET quiet_start_minute = NULL, quiet_end_minute = NULL WHERE principal_id = ?", BOB);
            c.commit();
        }
    }

    @Test
    @DisplayName("PRD-NTF-015: an uncatalogued event kind is refused at the emitter, and the actor is never their own recipient")
    void catalogueAndActor() throws Exception {
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            assertThrows(IllegalArgumentException.class, () -> Notifier.emit(c, TENANT,
                    new Notifier.Event("made.up", "X", UUID.randomUUID(), Optional.empty(), "x", Optional.empty(), "x", Map.of(), Optional.empty()), Set.of(ALICE)));
            UUID subject = UUID.randomUUID();
            int written = Notifier.emit(c, TENANT, new Notifier.Event("comment.posted", "ASSESSMENT_REQUEST", subject, Optional.of(orgNode),
                    "REQ-X", Optional.of(ALICE), "Alice", Map.of(), Optional.of("/board/" + subject)), Set.of(ALICE, BOB));
            c.commit();
            assertEquals(1, written, "Alice commented; only Bob is told");
        }
    }

    // ==============================================================================================

    private NotificationChannelService.Channel verifiedChannel(String code) throws Exception {
        NotificationChannelService.Channel channel = asAdmin(() -> channels.create(admin, code, "Hook " + code, "GENERIC_WEBHOOK",
                Map.of("url", receiverUrl), Optional.empty(), false));
        received.clear();
        answer.set(200);
        asAdmin(() -> channels.sendVerification(admin, channel.id(), Optional.empty()));
        String verification = codeFrom(received.get(0));
        received.clear();
        return asAdmin(() -> channels.confirmVerification(admin, channel.id(), verification));
    }

    private static String codeFrom(String call) {
        Matcher m = Pattern.compile("code is (\\d{6})").matcher(call);
        assertTrue(m.find(), "the verification message carries the code: " + call);
        return m.group(1);
    }

    private void emit(String kind, Map<String, String> arguments, Set<UUID> recipients) throws SQLException {
        emit(kind, requestId, "REQ-N1", arguments, recipients);
    }

    private void emit(String kind, UUID subject, String label, Map<String, String> arguments, Set<UUID> recipients) throws SQLException {
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            Notifier.emit(c, TENANT, new Notifier.Event(kind, "ASSESSMENT_REQUEST", subject, Optional.of(orgNode), label,
                    Optional.of(ADMIN), "Admin", arguments, Optional.of("/board/" + subject)), recipients);
            c.commit();
        }
    }

    private String statusOf(UUID subject) throws SQLException {
        return scalar("SELECT d.status FROM notification_delivery d JOIN notification n ON n.id = d.notification_id WHERE n.subject_id = ?", subject);
    }

    private <T> T asAdmin(java.util.concurrent.Callable<T> body) throws SQLException {
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

    @SuppressWarnings("unchecked")
    private <T> T scalar(String sql, Object... parameters) throws SQLException {
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    throw new AssertionError("no row for: " + sql);
                }
                T value = (T) r.getObject(1);
                c.commit();
                return value;
            }
        }
    }

    private java.time.OffsetDateTime time(String sql, Object... parameters) throws SQLException {
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    throw new AssertionError("no row for: " + sql);
                }
                java.time.OffsetDateTime value = r.getObject(1, java.time.OffsetDateTime.class);
                c.commit();
                return value;
            }
        }
    }

    private long count(String sql, Object... parameters) throws SQLException {
        Number n = scalar(sql, parameters);
        return n.longValue();
    }
}
