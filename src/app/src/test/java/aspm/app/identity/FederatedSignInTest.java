package aspm.app.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.app.egress.EgressGuard;
import aspm.app.identity.federation.IdentityProviderService;
import aspm.app.identity.federation.JsonWebTokenTest;
import aspm.app.identity.federation.OidcClient;
import aspm.app.persistence.AllMigrations;
import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Json;
import aspm.app.runtime.Principal;
import aspm.app.secrets.Secrets;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Federated sign-in end to end against the platform's own schema and a fake OpenID provider on the
 * loopback interface. {@code PRD-IAM-001}, {@code PRD-IAM-002}, {@code PRD-IAM-004}, {@code PRD-IAM-012},
 * {@code SEC-SEC-002}, {@code SEC-SEC-008}, {@code SEC-SEC-009}, {@code PRD-CON-021}, ADR-041.
 *
 * <p>Also, by construction, the first test in the repository that applies all migrations to an empty
 * database ({@link AllMigrations}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FederatedSignInTest {

    private static final UUID TENANT = UUID.fromString("7a000000-0000-4000-8000-000000000001");
    private static final UUID ADMIN = UUID.fromString("7a000000-0000-4000-8000-0000000000ad");

    private DataSource dataSource;
    private HttpServer idp;
    private String issuer;
    private JsonWebTokenTest.Signer signer;
    private final List<String> idpRequests = new CopyOnWriteArrayList<>();
    private final Map<String, String> codeToNonce = new java.util.concurrent.ConcurrentHashMap<>();
    /** What the next ID token should say — the test sets this before completing a handshake. */
    private volatile Map<String, Object> nextClaims = new LinkedHashMap<>();

    private Secrets secrets;
    private IdentityProviderService providers;
    private FederatedSignIn federation;
    private UUID roleId;
    private Principal admin;

    @BeforeAll
    void start() throws Exception {
        dataSource = AllMigrations.dataSource();
        signer = new JsonWebTokenTest.Signer("RS256");
        idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The issuer every production check sees is https://idp.test; the fake behind it is reached by
        // rewriting that host to the loopback port AFTER the egress guard has passed the real URL.
        issuer = "https://idp.test";
        String loopback = "http://127.0.0.1:" + idp.getAddress().getPort();
        idp.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            idpRequests.add(exchange.getRequestMethod() + " " + path + " | " + body + " | auth="
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            String response;
            int status = 200;
            switch (path) {
                case "/.well-known/openid-configuration" -> response = Json.write(Map.of(
                        "issuer", issuer,
                        "authorization_endpoint", issuer + "/authorize",
                        "token_endpoint", issuer + "/token",
                        "jwks_uri", issuer + "/jwks",
                        "id_token_signing_alg_values_supported", List.of("RS256")));
                case "/jwks" -> response = Json.write(Map.of("keys", List.of(signer.jwk())));
                case "/token" -> {
                    Map<String, String> form = form(body);
                    String nonce = codeToNonce.get(form.get("code"));
                    if (nonce == null || form.get("code_verifier") == null) {
                        status = 400;
                        response = "{\"error\":\"invalid_grant\"}";
                    } else {
                        Map<String, Object> claims = new LinkedHashMap<>(nextClaims);
                        claims.put("iss", issuer);
                        claims.put("aud", "aspm-test");
                        claims.putIfAbsent("exp", Instant.now().getEpochSecond() + 300);
                        claims.putIfAbsent("iat", Instant.now().getEpochSecond());
                        claims.put("nonce", claims.getOrDefault("nonce", nonce));
                        try {
                            response = Json.write(Map.of("id_token", signer.sign(claims), "access_token", "at", "token_type", "Bearer"));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
                default -> {
                    status = 404;
                    response = "{}";
                }
            }
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        idp.start();

        // Deployment: a sealing key so the client secret can be held by reference (V073).
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        secrets = Secrets.fromEnvironment(Map.of(
                "ASPM_ENVIRONMENT", "production",
                "ASPM_SECRETS_PROVIDERS", "sealed",
                "ASPM_CREDENTIAL_KEY", Base64.getEncoder().encodeToString(key)), dataSource);
        EgressGuard egress = EgressGuard.permittingLoopbackForTests(host -> {
            try {
                return new java.net.InetAddress[] {java.net.InetAddress.getByName("127.0.0.1")};
            } catch (java.net.UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        });
        OidcClient oidc = OidcClient.rewritingEndpointsForTests(egress, url -> url.replace(issuer, loopback));
        providers = new IdentityProviderService(dataSource, secrets, oidc, egress);
        federation = new FederatedSignIn(dataSource, TENANT, secrets, oidc, providers, "http://localhost:8080");

        // Tenant, policy, one role to map a group onto, and the administrator who configures it.
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "INSERT INTO tenant (id, display_name, lifecycle_state, residency_region, key_reference, entitlement_tier) "
                    + "VALUES (?, 'Federation test', 'ACTIVE', 'VN', 'vault://test', 'STANDARD') ON CONFLICT (id) DO NOTHING", TENANT);
            execute(c, "INSERT INTO password_policy (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", TENANT);
            try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO role (tenant_id, code, label_i18n) VALUES (?, 'SECURITY_ADMIN', '{\"en\":\"Security administrator\"}') RETURNING id")) {
                s.setObject(1, TENANT);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    roleId = r.getObject(1, UUID.class);
                }
            }
            execute(c, "INSERT INTO role_permission (tenant_id, role_id, permission_code) VALUES (?, ?, 'iam.idp.manage')", TENANT, roleId);
            execute(c, "INSERT INTO principal (id, tenant_id, kind, username, display_name, lifecycle_state) "
                    + "VALUES (?, ?, 'HUMAN', 'fed.admin', 'Federation admin', 'ACTIVE')", ADMIN, TENANT);
            c.commit();
        }
        admin = new Principal(TENANT, ADMIN, Set.of(IdentityProviderService.MANAGE), Set.of(), true, false, false);
    }

    @AfterAll
    void stop() {
        if (idp != null) {
            idp.stop(0);
        }
    }

    @Test
    @DisplayName("OPS-DEP-029: every migration applies to an empty database, in filename order, including those added for federation and notification")
    void allMigrationsApplied() {
        List<String> applied = AllMigrations.applied();
        assertTrue(applied.size() >= 75, "applied " + applied.size());
        for (String expected : List.of("V072__", "V073__", "V074__", "V075__")) {
            assertTrue(applied.stream().anyMatch(n -> n.startsWith(expected)), expected + " was applied");
        }
        // Filename order is the order apply.sh uses; a fixture that applied them any other way would
        // verify a sequence no deployment runs.
        for (int i = 1; i < applied.size(); i++) {
            assertTrue(applied.get(i - 1).compareTo(applied.get(i)) < 0, applied.get(i - 1) + " before " + applied.get(i));
        }
    }

    @Test
    @DisplayName("PRD-IAM-001 / PRD-IAM-004 / ADR-041: first sign-in provisions, links on sub, maps a group to a role, and mints a fully authenticated session when the provider asserts MFA")
    void firstSignInProvisionsAndMaps() throws Exception {
        IdentityProviderService.Provider okta = createProvider("okta", true, true, List.of());
        asAdmin(() -> providers.setGroupRoles(admin, okta.id(), List.of(Map.of("group", "sec-admins", "roleId", roleId.toString()))));

        // Client secret: held by reference, never in the row.
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT);
                PreparedStatement s = c.prepareStatement("SELECT client_secret_ref FROM identity_provider WHERE id = ?")) {
            s.setObject(1, okta.id());
            try (ResultSet r = s.executeQuery()) {
                r.next();
                assertTrue(r.getString(1).startsWith("sealed:"), r.getString(1));
                assertFalse(r.getString(1).contains("top-secret"));
            }
            c.commit();
        }

        nextClaims = Map.of("sub", "00u-alice", "preferred_username", "Alice.Nguyen", "email", "alice@example.com",
                "email_verified", true, "name", "Alice Nguyễn", "groups", List.of("sec-admins", "everyone"), "amr", List.of("pwd", "mfa"));
        FederatedSignIn.Outcome outcome = complete(okta, "/board");
        FederatedSignIn.Outcome.Established done = assertInstanceOf(FederatedSignIn.Outcome.Established.class, outcome,
                String.valueOf(outcome));
        assertTrue(done.fullyAuthenticated(), "amr contains mfa and the provider is configured to assert it");
        assertEquals(Optional.of("/board"), done.redirectTarget());

        // The token exchange carried PKCE and the client secret as HTTP Basic.
        String tokenCall = idpRequests.stream().filter(r -> r.startsWith("POST /token")).reduce((a, b) -> b).orElseThrow();
        assertTrue(tokenCall.contains("code_verifier=") && tokenCall.contains("auth=Basic "), tokenCall);

        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            UUID principalId = scalar(c, "SELECT principal_id FROM federated_identity WHERE provider_id = ? AND subject = '00u-alice'", okta.id());
            assertEquals("alice.nguyen", scalar(c, "SELECT username FROM principal WHERE id = ?", principalId));
            assertEquals("alice@example.com", scalar(c, "SELECT email FROM principal WHERE id = ?", principalId));
            assertEquals("FULLY_AUTHENTICATED", scalar(c,
                    "SELECT factor_state FROM principal_session WHERE token_hash = ?", PasswordHash.tokenHash(done.sessionToken())));
            assertEquals("FEDERATED:okta", scalar(c,
                    "SELECT authentication_method FROM principal_session WHERE token_hash = ?", PasswordHash.tokenHash(done.sessionToken())));
            assertEquals(1L, count(c, "SELECT count(*) FROM role_assignment WHERE principal_id = ? AND source_provider_id = ? AND revoked_at IS NULL",
                    principalId, okta.id()));
            assertEquals("SUCCESS", scalar(c,
                    "SELECT outcome FROM authentication_attempt WHERE principal_id = ? AND factor = 'FEDERATED' ORDER BY occurred_at DESC LIMIT 1", principalId));
            // A resolver sees a principal holding the mapped role's permission.
            IdentityService identity = new IdentityService(dataSource);
            var session = identity.session(TENANT, done.sessionToken()).orElseThrow();
            var principal = identity.principal(TENANT, session).orElseThrow();
            assertTrue(principal.holds("iam.idp.manage"));
            c.commit();
        }
    }

    @Test
    @DisplayName("ADR-041: a group the person left revokes the assignment the mapping produced, and never an administrator's grant")
    void groupsReconcileOnEverySignIn() throws Exception {
        IdentityProviderService.Provider kc = createProvider("keycloak", true, true, List.of());
        asAdmin(() -> providers.setGroupRoles(admin, kc.id(), List.of(Map.of("group", "appsec", "roleId", roleId.toString()))));

        nextClaims = Map.of("sub", "kc-bob", "preferred_username", "bob", "email", "bob@example.com", "email_verified", true,
                "name", "Bob", "realm_access", Map.of("roles", List.of("appsec")), "amr", List.of("mfa"));
        assertInstanceOf(FederatedSignIn.Outcome.Established.class, complete(kc, null));
        UUID bob;
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            bob = scalar(c, "SELECT principal_id FROM federated_identity WHERE provider_id = ? AND subject = 'kc-bob'", kc.id());
            assertEquals(1L, count(c, "SELECT count(*) FROM role_assignment WHERE principal_id = ? AND revoked_at IS NULL", bob));
            // An administrator's own grant of the same role, with no source.
            execute(c, "INSERT INTO role_assignment (tenant_id, principal_id, role_id, scope_mode) VALUES (?, ?, ?, 'TENANT')", TENANT, bob, roleId);
            c.commit();
        }

        nextClaims = Map.of("sub", "kc-bob", "preferred_username", "bob", "email", "bob@example.com", "email_verified", true,
                "name", "Bob", "realm_access", Map.of("roles", List.of("developers")), "amr", List.of("mfa"));
        assertInstanceOf(FederatedSignIn.Outcome.Established.class, complete(kc, null));
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            assertEquals(1L, count(c, "SELECT count(*) FROM role_assignment WHERE principal_id = ? AND source_provider_id IS NOT NULL AND revoked_reason = 'IDP_GROUP_REMOVED'", bob));
            assertEquals(1L, count(c, "SELECT count(*) FROM role_assignment WHERE principal_id = ? AND source_provider_id IS NULL AND revoked_at IS NULL", bob),
                    "the administrator's grant is untouched");
            assertEquals(1L, count(c, "SELECT count(*) FROM principal WHERE id = ?", bob), "the second sign-in linked, it did not provision again");
            c.commit();
        }
    }

    @Test
    @DisplayName("SEC-SEC-009: a handshake state is consumed once; a replayed callback, a wrong nonce and an expired token are refused with the same outward result")
    void handshakeIsSingleUse() throws Exception {
        IdentityProviderService.Provider p = createProvider("generic", true, true, List.of());
        FederatedSignIn.Start start = federation.begin("generic", Optional.empty()).orElseThrow();
        Map<String, String> params = query(start.authorizationUrl());
        codeToNonce.put("code-1", params.get("nonce"));
        nextClaims = Map.of("sub", "s-1", "preferred_username", "carol", "email", "carol@example.com", "email_verified", true, "amr", List.of("mfa"));
        assertInstanceOf(FederatedSignIn.Outcome.Established.class, federation.complete(params.get("state"), "code-1", "10.0.0.1", "test"));

        FederatedSignIn.Outcome replay = federation.complete(params.get("state"), "code-1", "10.0.0.1", "test");
        assertEquals("STATE_UNKNOWN_OR_USED", assertInstanceOf(FederatedSignIn.Outcome.Rejected.class, replay).code());

        // Wrong nonce in the token: the state row is fresh, the provider is real, the token is not ours.
        FederatedSignIn.Start second = federation.begin("generic", Optional.empty()).orElseThrow();
        Map<String, String> params2 = query(second.authorizationUrl());
        codeToNonce.put("code-2", params2.get("nonce"));
        Map<String, Object> badNonce = new LinkedHashMap<>(Map.of("sub", "s-1", "email", "carol@example.com", "email_verified", true));
        badNonce.put("nonce", "not-the-nonce-we-issued-0000000000000000");
        nextClaims = badNonce;
        FederatedSignIn.Outcome rejected = federation.complete(params2.get("state"), "code-2", null, null);
        assertEquals("NONCE_MISMATCH", assertInstanceOf(FederatedSignIn.Outcome.Rejected.class, rejected).code());

        FederatedSignIn.Start third = federation.begin("generic", Optional.empty()).orElseThrow();
        Map<String, String> params3 = query(third.authorizationUrl());
        codeToNonce.put("code-3", params3.get("nonce"));
        nextClaims = Map.of("sub", "s-1", "email", "carol@example.com", "email_verified", true, "exp", Instant.now().getEpochSecond() - 600);
        FederatedSignIn.Outcome expired = federation.complete(params3.get("state"), "code-3", null, null);
        assertEquals("TOKEN_EXPIRED", assertInstanceOf(FederatedSignIn.Outcome.Rejected.class, expired).code());

        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            assertTrue(count(c, "SELECT count(*) FROM authentication_attempt WHERE factor = 'FEDERATED' AND outcome = 'FEDERATION_REJECTED'") >= 3,
                    "every refusal is recorded (SEC-SEC-008)");
            c.commit();
        }
        assertTrue(p.active());
    }

    @Test
    @DisplayName("PRD-IAM-004: without just-in-time provisioning an unknown subject is refused; an allowed-domain list refuses an outside address; an unverified email never links")
    void provisioningPolicy() throws Exception {
        IdentityProviderService.Provider strict = createProvider("strict", false, true, List.of("example.com"));
        nextClaims = Map.of("sub", "unknown-1", "email", "dave@example.com", "email_verified", true, "amr", List.of("mfa"));
        assertEquals("NOT_PROVISIONED", assertInstanceOf(FederatedSignIn.Outcome.Rejected.class, complete(strict, null)).code());

        IdentityProviderService.Provider domains = createProvider("domains", true, true, List.of("example.com"));
        nextClaims = Map.of("sub", "outsider", "email", "eve@evil.example.org", "email_verified", true, "amr", List.of("mfa"));
        assertEquals("EMAIL_DOMAIN_NOT_ALLOWED", assertInstanceOf(FederatedSignIn.Outcome.Rejected.class, complete(domains, null)).code());

        // A pre-created local account with fed.admin's email; an UNVERIFIED claim must not link to it.
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "UPDATE principal SET email = 'fed.admin@example.com' WHERE id = ?", ADMIN);
            c.commit();
        }
        nextClaims = Map.of("sub", "impostor", "preferred_username", "impostor", "email", "fed.admin@example.com",
                "email_verified", false, "amr", List.of("mfa"));
        FederatedSignIn.Outcome outcome = complete(domains, null);
        assertInstanceOf(FederatedSignIn.Outcome.Established.class, outcome, "provisioned as a NEW principal, not linked");
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            UUID linked = scalar(c, "SELECT principal_id FROM federated_identity WHERE provider_id = ? AND subject = 'impostor'", domains.id());
            assertFalse(ADMIN.equals(linked), "the unverified email did not capture the administrator's account");
            assertTrue(scalar(c, "SELECT email FROM principal WHERE id = ?", linked) == null, "and the colliding email was not copied onto the new row");
            c.commit();
        }
    }

    @Test
    @DisplayName("PRD-IAM-002: without a second-factor assertion the session is first-factor only and the local challenge follows")
    void secondFactorFollowsWhenNotAsserted() throws Exception {
        IdentityProviderService.Provider p = createProvider("nomfa", true, false, List.of());
        nextClaims = Map.of("sub", "frank", "preferred_username", "frank", "email", "frank@example.com", "email_verified", true, "amr", List.of("mfa"));
        FederatedSignIn.Outcome.Established done = assertInstanceOf(FederatedSignIn.Outcome.Established.class, complete(p, null));
        assertFalse(done.fullyAuthenticated(), "the provider is not trusted to assert MFA, whatever amr says");
        assertTrue(done.enrolmentNeeded());
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            assertEquals("PASSWORD_ONLY", scalar(c, "SELECT factor_state FROM principal_session WHERE token_hash = ?", PasswordHash.tokenHash(done.sessionToken())));
            c.commit();
        }
    }

    @Test
    @DisplayName("SEC-SEC-002: local sign-in cannot be disabled without a break-glass account; once disabled, a password is refused for everyone else")
    void localSignInPolicy() throws Exception {
        // The switch needs an active provider to fall back on; this test brings its own so the order
        // the suite runs in does not decide the outcome.
        createProvider("bg-provider", true, true, List.of());
        assertThrows(IllegalArgumentException.class, () -> asAdmin(() -> {
            providers.setLocalSignIn(admin, false);
            return null;
        }), "no break-glass principal yet: refusing is what stops a tenant locking itself out");
        asAdmin(() -> {
            providers.setBreakGlass(admin, ADMIN, true);
            providers.setLocalSignIn(admin, false);
            return null;
        });
        assertFalse(providers.localSignInEnabled(TENANT));

        // A local account with a password, not break-glass.
        UUID local = UUID.randomUUID();
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "INSERT INTO principal (id, tenant_id, kind, username, display_name, lifecycle_state) VALUES (?, ?, 'HUMAN', 'local.user', 'Local', 'ACTIVE')", local, TENANT);
            IdentityService.replaceCredential(c, local, "Correct-Horse-Battery-Staple-99".toCharArray(), "TEST");
            c.commit();
        }
        IdentityService identity = new IdentityService(dataSource);
        assertInstanceOf(IdentityService.SignIn.Rejected.class,
                identity.signIn(TENANT, "local.user", "Correct-Horse-Battery-Staple-99".toCharArray(), null, null));
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            assertEquals("LOCAL_SIGN_IN_DISABLED", scalar(c,
                    "SELECT outcome FROM authentication_attempt WHERE principal_id = ? ORDER BY occurred_at DESC LIMIT 1", local));
            // The break-glass administrator is past the switch: with no credential the outcome is
            // BAD_CREDENTIAL, which is the gate AFTER the one under test.
            c.commit();
        }
        assertInstanceOf(IdentityService.SignIn.Rejected.class, identity.signIn(TENANT, "fed.admin", "anything".toCharArray(), null, null));
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            assertEquals("BAD_CREDENTIAL", scalar(c,
                    "SELECT outcome FROM authentication_attempt WHERE principal_id = ? ORDER BY occurred_at DESC LIMIT 1", ADMIN));
            c.commit();
        }
        asAdmin(() -> {
            providers.setLocalSignIn(admin, true);
            return null;
        });
    }

    // ==============================================================================================

    private IdentityProviderService.Provider createProvider(String code, boolean jit, boolean mfaAsserted, List<String> domains)
            throws SQLException {
        IdentityProviderService.Preset preset = switch (code) {
            case "okta" -> IdentityProviderService.Preset.OKTA;
            case "keycloak" -> IdentityProviderService.Preset.KEYCLOAK;
            default -> IdentityProviderService.Preset.GENERIC_OIDC;
        };
        IdentityProviderService.Draft base = IdentityProviderService.Draft.withPresetDefaults(code, "Provider " + code, preset,
                issuer, "aspm-test", Optional.of("top-secret".toCharArray()));
        IdentityProviderService.Draft draft = new IdentityProviderService.Draft(base.code(), base.displayName(), preset, base.issuer(),
                Optional.empty(), base.clientId(), base.clientSecret(), base.scopes(), base.claimSubject(), base.claimUsername(),
                base.claimEmail(), base.claimDisplayName(), base.claimGroups(), jit, domains, mfaAsserted);
        IdentityProviderService.Provider created = asAdmin(() -> providers.create(admin, draft));
        assertEquals("ACTIVE", created.lifecycleState(), "discovery against the fake succeeded: " + created.lastTestDetail());
        return created;
    }

    private FederatedSignIn.Outcome complete(IdentityProviderService.Provider provider, String next) throws SQLException {
        FederatedSignIn.Start start = federation.begin(provider.code(), Optional.ofNullable(next)).orElseThrow();
        Map<String, String> params = query(start.authorizationUrl());
        assertEquals("S256", params.get("code_challenge_method"));
        assertEquals("http://localhost:8080/auth/callback", params.get("redirect_uri"));
        String code = "code-" + UUID.randomUUID();
        codeToNonce.put(code, params.get("nonce"));
        return federation.complete(params.get("state"), code, "203.0.113.7, 10.0.0.1", "JUnit");
    }

    /** The dispatcher binds the tenant context for a class A/E call; the test does it here. */
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

    private static Map<String, String> query(String url) {
        return form(URI.create(url).getRawQuery());
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> out = new LinkedHashMap<>();
        if (encoded == null) {
            return out;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
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
    private static <T> T scalar(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    throw new AssertionError("no row for: " + sql);
                }
                return (T) r.getObject(1);
            }
        }
    }

    private static long count(Connection c, String sql, Object... parameters) throws SQLException {
        Number n = scalar(c, sql, parameters);
        return n.longValue();
    }
}
