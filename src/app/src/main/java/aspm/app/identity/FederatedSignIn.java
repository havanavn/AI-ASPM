package aspm.app.identity;

import aspm.app.identity.federation.IdentityProviderService;
import aspm.app.identity.federation.JsonWebToken;
import aspm.app.identity.federation.OidcClient;
import aspm.app.persistence.TenantConnections;
import aspm.app.secrets.Secrets;
import aspm.kernel.tenantcontext.contract.EstablishedFrom;
import aspm.kernel.tenantcontext.contract.TenantContext;
import aspm.kernel.tenantcontext.contract.TenantContextHolder;
import aspm.sharedkernel.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Federated sign-in: the authorization-code handshake from "start" to a platform session.
 * {@code PRD-IAM-001}, {@code PRD-IAM-002}, {@code PRD-IAM-004}, {@code PRD-IAM-012}, {@code SEC-SEC-002},
 * {@code SEC-SEC-008}, {@code SEC-SEC-009}, ADR-004, ADR-041, ADR-059.
 *
 * <h2>The shape</h2>
 *
 * <ol>
 *   <li>{@link #begin}: a row in {@code federated_login_state} holding state, nonce and the PKCE
 *       verifier; the browser is sent to the provider's authorization endpoint.
 *   <li>{@link #complete}: the state row is found and consumed in one statement (found once, never
 *       twice); the code is exchanged; the ID token is verified — signature, issuer, audience, expiry,
 *       nonce; the subject is linked to a principal, or a principal is provisioned; group claims are
 *       reconciled into role assignments the mapping produced; a session is minted.
 * </ol>
 *
 * <h2>Where the local path and this one meet</h2>
 *
 * <p>Both end in the same {@code principal_session} row minted by the same
 * {@link IdentityService#createSession} and read by the same resolver. A federated session is
 * {@code FULLY_AUTHENTICATED} at once only when the provider is configured as asserting a second
 * factor AND the token carries the assertion ({@code amr} or {@code acr}); otherwise it is first-factor
 * only and the local TOTP challenge follows, exactly as after a password. ADR-059 asked that
 * federation "touch the credential resolution path and nothing above it", and it does.
 *
 * <h2>Linking</h2>
 *
 * <p>A returning subject is found by {@code (provider, sub)}. A first-time subject is linked to an
 * existing principal only through a VERIFIED email ({@code email_verified: true}) that matches exactly
 * one active principal with no other identity at this provider — the normal way a pre-created account
 * meets its directory identity. Otherwise, with just-in-time provisioning on and the email domain
 * allowed, a principal is created with no roles ({@code SEC-AUZ-014}: authenticated and authorized for
 * nothing until the group mapping or an administrator says otherwise). Never on an unverified email:
 * an attacker who controls a provider account with a victim's unverified address would otherwise
 * inherit the victim's roles.
 *
 * <h2>Tenant context for the writes</h2>
 *
 * <p>The callback is class G: the dispatcher binds no context because there is no session yet. After
 * the ID token has been verified there IS an authenticated principal — the provider authenticated it
 * and the platform verified the provider's word — so this class binds the tenant context itself for
 * the provisioning and audit writes, from the deployment's tenant, {@code SEC-TEN-004}'s
 * "established from an authenticated principal". The binding is scoped to those writes and cleared.
 */
public final class FederatedSignIn {

    /** The provider sent us here; the browser goes there. */
    public record Start(String authorizationUrl) {
    }

    /** What the callback produced. */
    public sealed interface Outcome {
        record Established(String sessionToken, boolean fullyAuthenticated, boolean enrolmentNeeded,
                Optional<String> redirectTarget) implements Outcome {
        }

        record Rejected(String code) implements Outcome {
        }
    }

    /** Values of {@code amr} that assert a second factor, and {@code acr} values known to mean one. */
    static final Set<String> MFA_AMR = Set.of("mfa", "otp", "hwk", "swk", "sms", "tel", "fido", "webauthn", "pop", "face", "fpt");
    static final Set<String> MFA_ACR = Set.of(
            "http://schemas.openid.net/pape/policies/2007/06/multi-factor",
            "http://schemas.openid.net/pape/policies/2007/06/multi-factor-physical",
            "urn:okta:loa:2fa:any", "urn:okta:loa:2fa:any:ifpossible",
            "phr", "phrh", "2", "loa-2", "urn:mace:incommon:iap:silver", "urn:mace:incommon:iap:gold");

    private static final Duration FIRST_FACTOR_WINDOW = Duration.ofMinutes(10);

    private final DataSource dataSource;
    private final UUID tenantId;
    private final Secrets secrets;
    private final OidcClient oidc;
    private final IdentityProviderService providers;
    private final String redirectUri;
    private final aspm.app.audit.AuditTrail audit = new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public FederatedSignIn(DataSource dataSource, UUID tenantId, Secrets secrets, OidcClient oidc,
            IdentityProviderService providers, String publicBaseUrl) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.secrets = Objects.requireNonNull(secrets);
        this.oidc = Objects.requireNonNull(oidc);
        this.providers = Objects.requireNonNull(providers);
        this.redirectUri = Objects.requireNonNull(publicBaseUrl).replaceAll("/+$", "") + "/auth/callback";
    }

    /** The exact redirect URI to register at the provider. */
    public String redirectUri() {
        return redirectUri;
    }

    // ==============================================================================================
    // Start
    // ==============================================================================================

    public Optional<Start> begin(String providerCode, Optional<String> redirectTarget) throws SQLException {
        Optional<IdentityProviderService.Provider> found = providers.activeByCode(tenantId, providerCode);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IdentityProviderService.Provider provider = found.get();
        OidcClient.Discovery discovery;
        try {
            discovery = oidc.discover(provider.issuer(), provider.discoveryUrl());
        } catch (OidcClient.ProviderUnavailable e) {
            recordAttempt(null, provider.code(), null, "FEDERATION_REJECTED", null, null);
            return Optional.empty();
        }
        String state = oidc.randomToken(32);
        String nonce = oidc.randomToken(32);
        String verifier = oidc.randomToken(48);
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO federated_login_state (state, tenant_id, provider_id, nonce, pkce_verifier, redirect_target) "
                                + "VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, state);
            statement.setObject(2, tenantId);
            statement.setObject(3, provider.id());
            statement.setString(4, nonce);
            statement.setString(5, verifier);
            statement.setString(6, redirectTarget.filter(FederatedSignIn::safeRedirect).orElse(null));
            statement.executeUpdate();
            connection.commit();
        }
        Map<String, String> extra = new LinkedHashMap<>();
        if (provider.preset() == IdentityProviderService.Preset.GOOGLE && !provider.allowedEmailDomains().isEmpty()) {
            // Google's hosted-domain hint narrows the account chooser; it is a hint, and the domain is
            // still enforced on the verified email below.
            extra.put("hd", provider.allowedEmailDomains().get(0));
        }
        return Optional.of(new Start(OidcClient.authorizationUrl(discovery, provider.clientId(), redirectUri,
                provider.scopes(), state, nonce, verifier, extra)));
    }

    // ==============================================================================================
    // Callback
    // ==============================================================================================

    public Outcome complete(String state, String code, String sourceAddress, String userAgent) throws SQLException {
        if (state == null || state.isBlank() || code == null || code.isBlank()) {
            return new Outcome.Rejected("CALLBACK_INCOMPLETE");
        }
        // 1. Consume the handshake. One statement finds and marks it, so a second callback with the
        //    same state finds nothing even if it arrives on another replica in the same instant.
        UUID providerId;
        String nonce;
        String verifier;
        Optional<String> redirectTarget;
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE federated_login_state SET consumed_at = now() "
                                + "WHERE state = ? AND consumed_at IS NULL AND expires_at > now() "
                                + "RETURNING provider_id, nonce, pkce_verifier, redirect_target")) {
            statement.setString(1, state);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    connection.commit();
                    recordAttempt(null, "state", null, "FEDERATION_REJECTED", sourceAddress, userAgent);
                    return new Outcome.Rejected("STATE_UNKNOWN_OR_USED");
                }
                providerId = r.getObject(1, UUID.class);
                nonce = r.getString(2);
                verifier = r.getString(3);
                redirectTarget = Optional.ofNullable(r.getString(4));
            }
            connection.commit();
        }

        // 2. The provider, still active.
        IdentityProviderService.Provider provider = providers.activeForSignIn(tenantId).stream()
                .filter(p -> p.id().equals(providerId)).findFirst().orElse(null);
        if (provider == null) {
            return new Outcome.Rejected("PROVIDER_INACTIVE");
        }

        // 3. Exchange and verify.
        Map<String, Object> claims;
        try {
            OidcClient.Discovery discovery = oidc.discover(provider.issuer(), provider.discoveryUrl());
            Optional<char[]> clientSecret = provider.clientSecretRef().flatMap(ref -> secrets.resolveTenant(tenantId, ref));
            if (provider.clientSecretRef().isPresent() && clientSecret.isEmpty()) {
                recordAttempt(null, provider.code(), null, "FEDERATION_REJECTED", sourceAddress, userAgent);
                return new Outcome.Rejected("CLIENT_SECRET_UNRESOLVABLE");
            }
            OidcClient.Tokens tokens = oidc.exchangeCode(discovery, provider.clientId(), clientSecret, code, redirectUri, verifier);
            JsonWebToken token = JsonWebToken.parse(tokens.idToken());
            List<Map<String, Object>> keys = oidc.keys(discovery, token.kid());
            token.verify(keys, discovery.issuer(), provider.clientId(), nonce, Instant.now());
            claims = token.claims();
        } catch (OidcClient.ProviderUnavailable e) {
            recordAttempt(null, provider.code(), null, "FEDERATION_REJECTED", sourceAddress, userAgent);
            return new Outcome.Rejected(e.code());
        } catch (JsonWebToken.Rejected e) {
            recordAttempt(null, provider.code(), null, "FEDERATION_REJECTED", sourceAddress, userAgent);
            return new Outcome.Rejected(e.code());
        }

        // 4. Claims → identity.
        String subject = string(claim(claims, provider.claimSubject())).orElse(null);
        if (subject == null || subject.isBlank()) {
            recordAttempt(null, provider.code(), null, "FEDERATION_REJECTED", sourceAddress, userAgent);
            return new Outcome.Rejected("SUBJECT_MISSING");
        }
        Optional<String> email = string(claim(claims, provider.claimEmail())).map(e -> e.strip().toLowerCase(Locale.ROOT));
        boolean emailVerified = Boolean.TRUE.equals(claims.get("email_verified"))
                // Google and Entra ID assert verification differently or not at all for their own
                // directories; a preset says when the email is trustworthy by construction.
                || (provider.preset() == IdentityProviderService.Preset.ENTRA_ID && claims.get("preferred_username") != null);
        Optional<String> username = string(claim(claims, provider.claimUsername())).map(u -> u.strip().toLowerCase(Locale.ROOT));
        Optional<String> displayName = string(claim(claims, provider.claimDisplayName()));
        List<String> groups = provider.claimGroups().map(path -> strings(claim(claims, path))).orElse(List.of());
        boolean secondFactorAsserted = provider.mfaAssertedByProvider() && assertsSecondFactor(claims);

        if (!provider.allowedEmailDomains().isEmpty()) {
            boolean allowed = email.map(e -> e.contains("@") && provider.allowedEmailDomains().contains(e.substring(e.indexOf('@') + 1)))
                    .orElse(false);
            if (!allowed) {
                recordAttempt(null, provider.code(), null, "FEDERATION_REJECTED", sourceAddress, userAgent);
                return new Outcome.Rejected("EMAIL_DOMAIN_NOT_ALLOWED");
            }
        }

        // 5. Link, provision, reconcile, mint — under a tenant context bound from the now-authenticated
        //    principal, so the audit chain can record what happened.
        TenantContext context = TenantContext.of(new TenantId(tenantId), "vn", EstablishedFrom.AUTHENTICATED_PRINCIPAL, Instant.now());
        try {
            return TenantContextHolder.callWith(context, () -> establish(provider, subject, email, emailVerified,
                    username, displayName, groups, secondFactorAsserted, redirectTarget, sourceAddress, userAgent));
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Outcome establish(IdentityProviderService.Provider provider, String subject, Optional<String> email,
            boolean emailVerified, Optional<String> username, Optional<String> displayName, List<String> groups,
            boolean secondFactorAsserted, Optional<String> redirectTarget, String sourceAddress, String userAgent)
            throws SQLException {
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId)) {
            UUID principalId = linkedPrincipal(connection, provider.id(), subject).orElse(null);
            boolean provisioned = false;
            if (principalId == null) {
                if (emailVerified && email.isPresent()) {
                    principalId = principalByVerifiedEmail(connection, provider.id(), email.get()).orElse(null);
                }
                if (principalId == null) {
                    if (!provider.jitProvisioning()) {
                        connection.rollback();
                        recordAttempt(null, provider.code() + ":" + subject, null, "FEDERATION_REJECTED", sourceAddress, userAgent);
                        return new Outcome.Rejected("NOT_PROVISIONED");
                    }
                    principalId = provision(connection, provider, subject, email, username, displayName);
                    provisioned = true;
                }
                link(connection, provider.id(), principalId, subject, email);
            }

            // Suspended or deprovisioned principals do not get a session, whatever the provider says.
            String lifecycle;
            boolean mfaEnrolled;
            boolean mustChange;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT lifecycle_state, mfa_enrolled_at IS NOT NULL, must_change_password FROM principal WHERE id = ?")) {
                statement.setObject(1, principalId);
                try (ResultSet r = statement.executeQuery()) {
                    r.next();
                    lifecycle = r.getString(1);
                    mfaEnrolled = r.getBoolean(2);
                    mustChange = r.getBoolean(3);
                }
            }
            if (!"ACTIVE".equals(lifecycle) && !"INVITED".equals(lifecycle)) {
                connection.rollback();
                recordAttempt(principalId, provider.code() + ":" + subject, null, "SUSPENDED", sourceAddress, userAgent);
                return new Outcome.Rejected("PRINCIPAL_NOT_ACTIVE");
            }
            if ("INVITED".equals(lifecycle)) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE principal SET lifecycle_state = 'ACTIVE', updated_at = now(), row_version = row_version + 1 WHERE id = ?")) {
                    statement.setObject(1, principalId);
                    statement.executeUpdate();
                }
            }

            if (provider.claimGroups().isPresent()) {
                reconcileGroupRoles(connection, provider.id(), principalId, groups);
            }

            PasswordPolicy.Settings settings = PasswordPolicy.load(connection);
            boolean fully = secondFactorAsserted;
            String factorState = fully ? "FULLY_AUTHENTICATED" : "PASSWORD_ONLY";
            Duration absolute = fully ? Duration.ofSeconds(settings.sessionAbsoluteSeconds()) : FIRST_FACTOR_WINDOW;
            String token = IdentityService.createSession(connection, principalId, tenantId, factorState, settings,
                    sourceAddress, userAgent, absolute);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE principal_session SET authentication_method = ? WHERE token_hash = ?")) {
                statement.setString(1, "FEDERATED:" + provider.code());
                statement.setBytes(2, PasswordHash.tokenHash(token));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE federated_identity SET last_authenticated_at = now() WHERE provider_id = ? AND subject = ?")) {
                statement.setObject(1, provider.id());
                statement.setString(2, subject);
                statement.executeUpdate();
            }
            if (fully) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE principal SET last_authenticated_at = now() WHERE id = ?")) {
                    statement.setObject(1, principalId);
                    statement.executeUpdate();
                }
            }
            recordAttempt(connection, principalId, provider.code() + ":" + subject, "SUCCESS", sourceAddress, userAgent);
            if (provisioned) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("provider", provider.code());
                payload.put("provisioning", "just_in_time");
                payload.put("email_verified", emailVerified);
                audit.domainChangeBy(connection, principalId, "principal",
                        aspm.kernel.audit.contract.DomainChangeKind.CREATED, principalId, null, payload);
            }
            connection.commit();
            // A federated principal never has a local password to change; the flag is meaningless for
            // it and is not consulted. A local password remains possible only through an administrator's
            // reset, which sets the flag again for that credential.
            return new Outcome.Established(token, fully, !fully && !mfaEnrolled, redirectTarget);
        }
    }

    // ==============================================================================================
    // Pieces
    // ==============================================================================================

    private static Optional<UUID> linkedPrincipal(Connection connection, UUID providerId, String subject) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT principal_id FROM federated_identity WHERE provider_id = ? AND subject = ?")) {
            statement.setObject(1, providerId);
            statement.setString(2, subject);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? Optional.of(r.getObject(1, UUID.class)) : Optional.empty();
            }
        }
    }

    /** Exactly one active principal with this email and no identity at this provider yet. */
    private static Optional<UUID> principalByVerifiedEmail(Connection connection, UUID providerId, String email) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT p.id FROM principal p
                 WHERE p.email = ? AND p.kind = 'HUMAN' AND p.lifecycle_state IN ('ACTIVE', 'INVITED')
                   AND NOT EXISTS (SELECT 1 FROM federated_identity f WHERE f.principal_id = p.id AND f.provider_id = ?)
                """)) {
            statement.setString(1, email);
            statement.setObject(2, providerId);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                UUID id = r.getObject(1, UUID.class);
                return r.next() ? Optional.empty() : Optional.of(id);
            }
        }
    }

    private UUID provision(Connection connection, IdentityProviderService.Provider provider, String subject,
            Optional<String> email, Optional<String> username, Optional<String> displayName) throws SQLException {
        String base = username.filter(u -> !u.isBlank())
                .or(() -> email.map(e -> e.contains("@") ? e.substring(0, e.indexOf('@')) : e))
                .orElse("user-" + subject.toLowerCase(Locale.ROOT))
                .replaceAll("[^a-z0-9._@+-]", ".");
        if (base.length() < 3) {
            base = base + "-" + provider.code();
        }
        if (base.length() > 120) {
            base = base.substring(0, 120);
        }
        String candidate = base;
        for (int attempt = 1; attempt < 100; attempt++) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM principal WHERE username = ?)")) {
                statement.setString(1, candidate);
                try (ResultSet r = statement.executeQuery()) {
                    r.next();
                    if (!r.getBoolean(1)) {
                        break;
                    }
                }
            }
            candidate = base + "." + attempt;
        }
        // The email is unique per tenant; an existing principal with the same email but an unverified
        // claim was deliberately not linked above, and creating a second row with it would collide.
        Optional<String> emailToStore = email;
        if (email.isPresent()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM principal WHERE email = ?)")) {
                statement.setString(1, email.get());
                try (ResultSet r = statement.executeQuery()) {
                    r.next();
                    if (r.getBoolean(1)) {
                        emailToStore = Optional.empty();
                    }
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO principal (tenant_id, kind, username, email, display_name, lifecycle_state, must_change_password) "
                        + "VALUES (?, 'HUMAN', ?, ?, ?, 'ACTIVE', false) RETURNING id")) {
            statement.setObject(1, tenantId);
            statement.setString(2, candidate);
            statement.setString(3, emailToStore.orElse(null));
            statement.setString(4, displayName.filter(d -> !d.isBlank()).orElse(candidate));
            try (ResultSet r = statement.executeQuery()) {
                r.next();
                return r.getObject(1, UUID.class);
            }
        }
    }

    private void link(Connection connection, UUID providerId, UUID principalId, String subject, Optional<String> email) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO federated_identity (tenant_id, principal_id, provider_id, subject, email_at_link) VALUES (?, ?, ?, ?, ?)")) {
            statement.setObject(1, tenantId);
            statement.setObject(2, principalId);
            statement.setObject(3, providerId);
            statement.setString(4, subject);
            statement.setString(5, email.orElse(null));
            statement.executeUpdate();
        }
    }

    /**
     * ADR-041, evaluated at authentication. Assignments this provider's mapping produced and that the
     * current groups no longer justify are revoked with the reason; justified ones are created if
     * absent. Administrator-granted assignments (no source) are never touched.
     */
    void reconcileGroupRoles(Connection connection, UUID providerId, UUID principalId, List<String> groups) throws SQLException {
        record Mapping(UUID roleId, Optional<UUID> scopeNodeId, String scopeMode) {
        }
        Set<Mapping> wanted = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role_id, scope_node_id, scope_mode FROM identity_provider_group_role WHERE provider_id = ? AND group_value = ANY (?)")) {
            statement.setObject(1, providerId);
            statement.setArray(2, connection.createArrayOf("text", groups.toArray()));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    wanted.add(new Mapping(r.getObject(1, UUID.class), Optional.ofNullable(r.getObject(2, UUID.class)), r.getString(3)));
                }
            }
        }
        Map<Mapping, UUID> existing = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, role_id, scope_node_id, scope_mode FROM role_assignment "
                        + "WHERE principal_id = ? AND source_provider_id = ? AND revoked_at IS NULL")) {
            statement.setObject(1, principalId);
            statement.setObject(2, providerId);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    existing.put(new Mapping(r.getObject(2, UUID.class), Optional.ofNullable(r.getObject(3, UUID.class)), r.getString(4)),
                            r.getObject(1, UUID.class));
                }
            }
        }
        List<UUID> toRevoke = new ArrayList<>();
        existing.forEach((mapping, id) -> {
            if (!wanted.contains(mapping)) {
                toRevoke.add(id);
            }
        });
        if (!toRevoke.isEmpty()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE role_assignment SET revoked_at = now(), revoked_reason = 'IDP_GROUP_REMOVED' WHERE id = ANY (?)")) {
                statement.setArray(1, connection.createArrayOf("uuid", toRevoke.toArray()));
                statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO role_assignment (tenant_id, principal_id, role_id, scope_node_id, scope_mode, source_provider_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            for (Mapping mapping : wanted) {
                if (existing.containsKey(mapping)) {
                    continue;
                }
                statement.setObject(1, tenantId);
                statement.setObject(2, principalId);
                statement.setObject(3, mapping.roleId());
                statement.setObject(4, mapping.scopeNodeId().orElse(null));
                statement.setString(5, mapping.scopeMode());
                statement.setObject(6, providerId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        if (!toRevoke.isEmpty() || wanted.stream().anyMatch(m -> !existing.containsKey(m))) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("provider_id", providerId.toString());
            payload.put("granted", wanted.stream().filter(m -> !existing.containsKey(m)).count());
            payload.put("revoked", toRevoke.size());
            audit.domainChangeBy(connection, principalId, "role", aspm.kernel.audit.contract.DomainChangeKind.ASSIGNED,
                    principalId, null, payload);
        }
    }

    static boolean assertsSecondFactor(Map<String, Object> claims) {
        if (claims.get("amr") instanceof List<?> amr && amr.stream().anyMatch(a -> a instanceof String s && MFA_AMR.contains(s.toLowerCase(Locale.ROOT)))) {
            return true;
        }
        return claims.get("acr") instanceof String acr && MFA_ACR.contains(acr);
    }

    /** Reads a claim by name or dotted path ({@code realm_access.roles}). */
    static Object claim(Map<String, Object> claims, String path) {
        if (claims.containsKey(path)) {
            return claims.get(path);
        }
        Object current = claims;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> m) || !m.containsKey(segment)) {
                return null;
            }
            current = m.get(segment);
        }
        return current;
    }

    private static Optional<String> string(Object value) {
        return value instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }

    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            }
            return out;
        }
        return value instanceof String s && !s.isBlank() ? List.of(s) : List.of();
    }

    /** Only a path on this origin may be a post-sign-in destination. */
    static boolean safeRedirect(String target) {
        return target != null && target.startsWith("/") && !target.startsWith("//") && !target.contains("\\")
                && target.length() <= 512;
    }

    private void recordAttempt(UUID principalId, String presented, String unused, String outcome, String sourceAddress,
            String userAgent) throws SQLException {
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId)) {
            recordAttempt(connection, principalId, presented, outcome, sourceAddress, userAgent);
            connection.commit();
        }
    }

    private void recordAttempt(Connection connection, UUID principalId, String presented, String outcome,
            String sourceAddress, String userAgent) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO authentication_attempt (tenant_id, presented_identifier, principal_id, outcome, factor, "
                        + "source_address, source_user_agent) VALUES (?, ?, ?, ?, 'FEDERATED', ?::inet, ?)")) {
            statement.setObject(1, tenantId);
            statement.setString(2, presented == null ? "-" : presented.length() > 512 ? presented.substring(0, 512) : presented);
            statement.setObject(3, principalId);
            statement.setString(4, outcome);
            statement.setString(5, sourceAddress == null || sourceAddress.isBlank() ? null : sourceAddress.split(",")[0].strip());
            statement.setString(6, userAgent == null ? null : userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent);
            statement.executeUpdate();
        }
    }
}
