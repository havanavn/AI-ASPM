package aspm.app.identity.federation;

import aspm.app.egress.EgressGuard;
import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import aspm.app.secrets.Secrets;
import aspm.sharedkernel.secrets.SecretReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * A tenant's identity providers: the configuration half of federated sign-in. V074.
 * {@code PRD-IAM-001}, {@code SEC-SEC-002}, {@code PRD-CON-010}, {@code PRD-CON-017}, {@code PRD-CON-021},
 * {@code PRD-CON-032}.
 *
 * <h2>Presets</h2>
 *
 * <p>A preset is what the platform knows about a provider that the generic protocol does not say:
 * which claim carries the username, whether groups arrive and under what name, how a second factor is
 * asserted. Choosing {@code OKTA} fills the claim mapping with Okta's defaults; the administrator then
 * edits what differs. Every preset is still plain OpenID Connect underneath, and {@code GENERIC_OIDC}
 * is the preset for a provider the platform has no opinion about.
 *
 * <h2>What is validated at save</h2>
 *
 * <ul>
 *   <li>The issuer is an https public destination ({@link EgressGuard}); a private address is refused
 *       here and again before each call.
 *   <li>The client secret, when supplied, is stored through {@link Secrets} and only its REFERENCE is
 *       written — the row's CHECK refuses anything that is not shaped like a reference, so a value
 *       cannot land in the row even through a path that bypassed this class ({@code PRD-CON-021}).
 *   <li>Discovery is attempted, and its outcome recorded on the row: a provider that cannot be reached
 *       at configuration time is saved DISABLED with the reason, not ACTIVE with a sign-in button that
 *       fails ({@code PRD-CON-017}: "invalid configuration rejected with a specific diagnosis").
 * </ul>
 */
public final class IdentityProviderService {

    /** The permission that governs providers (V074). */
    public static final String MANAGE = "iam.idp.manage";

    /** What the platform knows about a known provider. Product-fixed; see the class note. */
    public enum Preset {
        OKTA("Okta", List.of("openid", "profile", "email", "groups"),
                "sub", "preferred_username", "email", "name", "groups",
                "https://<org>.okta.com/oauth2/default (a custom authorization server) or https://<org>.okta.com"),
        ENTRA_ID("Microsoft Entra ID", List.of("openid", "profile", "email"),
                "sub", "preferred_username", "email", "name", "groups",
                "https://login.microsoftonline.com/<directory tenant id>/v2.0"),
        KEYCLOAK("Keycloak", List.of("openid", "profile", "email"),
                "sub", "preferred_username", "email", "name", "realm_access.roles",
                "https://<host>/realms/<realm>"),
        GOOGLE("Google Workspace", List.of("openid", "profile", "email"),
                "sub", "email", "email", "name", null,
                "https://accounts.google.com"),
        GENERIC_OIDC("OpenID Connect (generic)", List.of("openid", "profile", "email"),
                "sub", "preferred_username", "email", "name", "groups",
                "the provider's issuer URL, as it appears in its discovery document");

        public final String label;
        public final List<String> scopes;
        public final String claimSubject;
        public final String claimUsername;
        public final String claimEmail;
        public final String claimDisplayName;
        public final String claimGroups;
        public final String issuerHint;

        Preset(String label, List<String> scopes, String claimSubject, String claimUsername, String claimEmail,
                String claimDisplayName, String claimGroups, String issuerHint) {
            this.label = label;
            this.scopes = scopes;
            this.claimSubject = claimSubject;
            this.claimUsername = claimUsername;
            this.claimEmail = claimEmail;
            this.claimDisplayName = claimDisplayName;
            this.claimGroups = claimGroups;
            this.issuerHint = issuerHint;
        }
    }

    /** One configured provider, as the platform uses it. The secret is a reference, never a value. */
    public record Provider(UUID id, UUID tenantId, String code, String displayName, Preset preset, String issuer,
            Optional<String> discoveryUrl, String clientId, Optional<SecretReference> clientSecretRef,
            List<String> scopes, String claimSubject, String claimUsername, String claimEmail, String claimDisplayName,
            Optional<String> claimGroups, boolean jitProvisioning, List<String> allowedEmailDomains,
            boolean mfaAssertedByProvider, String lifecycleState, Optional<String> lastTestStatus,
            Optional<String> lastTestDetail, int rowVersion) {

        public boolean active() {
            return "ACTIVE".equals(lifecycleState);
        }
    }

    /** A group-to-role mapping row. */
    public record GroupRole(UUID id, String groupValue, UUID roleId, String roleCode, Optional<UUID> scopeNodeId, String scopeMode) {
    }

    /** What an administrator submits. {@code clientSecret} is a value here and a reference in the row. */
    public record Draft(String code, String displayName, Preset preset, String issuer, Optional<String> discoveryUrl,
            String clientId, Optional<char[]> clientSecret, List<String> scopes, String claimSubject, String claimUsername,
            String claimEmail, String claimDisplayName, Optional<String> claimGroups, boolean jitProvisioning,
            List<String> allowedEmailDomains, boolean mfaAssertedByProvider) {

        /** A draft with the preset's defaults for everything the administrator did not state. */
        public static Draft withPresetDefaults(String code, String displayName, Preset preset, String issuer,
                String clientId, Optional<char[]> clientSecret) {
            return new Draft(code, displayName, preset, issuer, Optional.empty(), clientId, clientSecret, preset.scopes,
                    preset.claimSubject, preset.claimUsername, preset.claimEmail, preset.claimDisplayName,
                    Optional.ofNullable(preset.claimGroups), true, List.of(), false);
        }
    }

    private final DataSource dataSource;
    private final Secrets secrets;
    private final OidcClient oidc;
    private final EgressGuard egress;
    private final aspm.app.audit.AuditTrail audit = new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public IdentityProviderService(DataSource dataSource, Secrets secrets, OidcClient oidc, EgressGuard egress) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.secrets = Objects.requireNonNull(secrets);
        this.oidc = Objects.requireNonNull(oidc);
        this.egress = Objects.requireNonNull(egress);
    }

    // ==============================================================================================
    // Reads
    // ==============================================================================================

    /** Every provider of the tenant, retired ones last. For the administration page. */
    public List<Provider> list(Principal principal) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, principal)) {
            return query(connection, "ORDER BY CASE lifecycle_state WHEN 'ACTIVE' THEN 0 WHEN 'DISABLED' THEN 1 ELSE 2 END, display_name", null);
        }
    }

    /** The active providers a sign-in page offers. Unauthenticated read, tenant from the deployment. */
    public List<Provider> activeForSignIn(UUID tenantId) throws SQLException {
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId)) {
            List<Provider> out = query(connection, "WHERE lifecycle_state = 'ACTIVE' ORDER BY display_name", null);
            connection.commit();
            return out;
        }
    }

    /** One active provider by its sign-in code. */
    public Optional<Provider> activeByCode(UUID tenantId, String code) throws SQLException {
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId)) {
            Optional<Provider> out = query(connection, "WHERE lifecycle_state = 'ACTIVE' AND code = ?", code).stream().findFirst();
            connection.commit();
            return out;
        }
    }

    /** Whether the tenant still allows a password at the sign-in form. */
    public boolean localSignInEnabled(UUID tenantId) throws SQLException {
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT local_sign_in_enabled FROM password_policy WHERE tenant_id = ?")) {
            statement.setObject(1, tenantId);
            try (ResultSet r = statement.executeQuery()) {
                boolean enabled = !r.next() || r.getBoolean(1);
                connection.commit();
                return enabled;
            }
        }
    }

    public List<GroupRole> groupRoles(Principal principal, UUID providerId) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, principal)) {
            return groupRoles(connection, providerId);
        }
    }

    List<GroupRole> groupRoles(Connection connection, UUID providerId) throws SQLException {
        List<GroupRole> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT g.id, g.group_value, g.role_id, r.code, g.scope_node_id, g.scope_mode
                  FROM identity_provider_group_role g
                  JOIN role r ON r.id = g.role_id
                 WHERE g.provider_id = ?
                 ORDER BY g.group_value, r.code
                """)) {
            statement.setObject(1, providerId);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.add(new GroupRole(r.getObject(1, UUID.class), r.getString(2), r.getObject(3, UUID.class),
                            r.getString(4), Optional.ofNullable(r.getObject(5, UUID.class)), r.getString(6)));
                }
            }
        }
        return out;
    }

    // ==============================================================================================
    // Writes
    // ==============================================================================================

    /**
     * Creates a provider. Discovery is attempted at once; a provider that cannot be discovered is saved
     * DISABLED with the diagnosis, so the administrator sees the failure on the page rather than the
     * user seeing it on the sign-in button.
     */
    public Provider create(Principal actor, Draft draft) throws SQLException {
        validate(draft);
        UUID tenantId = actor.tenantId();
        Optional<SecretReference> secretRef = draft.clientSecret()
                .filter(s -> s.length > 0)
                .map(s -> secrets.store(tenantId, "identity_provider", draft.code() + "-client-secret", s));
        String testStatus;
        String testDetail;
        try {
            OidcClient.Discovery discovery = oidc.discover(draft.issuer(), draft.discoveryUrl());
            oidc.keys(discovery, Optional.empty());
            testStatus = "OK";
            testDetail = "discovery and key set fetched";
        } catch (OidcClient.ProviderUnavailable e) {
            testStatus = "FAILED";
            testDetail = e.code();
        }
        String lifecycle = "OK".equals(testStatus) ? "ACTIVE" : "DISABLED";
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            UUID id;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO identity_provider (tenant_id, code, display_name, preset, issuer, discovery_url,
                        client_id, client_secret_ref, scopes, claim_subject, claim_username, claim_email,
                        claim_display_name, claim_groups, jit_provisioning, allowed_email_domains,
                        mfa_asserted_by_provider, lifecycle_state, last_discovery_at, last_test_at,
                        last_test_status, last_test_detail, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now(), ?, ?, ?, ?)
                    RETURNING id
                    """)) {
                statement.setObject(1, tenantId);
                statement.setString(2, draft.code());
                statement.setString(3, draft.displayName().strip());
                statement.setString(4, draft.preset().name());
                statement.setString(5, draft.issuer().strip());
                statement.setString(6, draft.discoveryUrl().filter(u -> !u.isBlank()).orElse(null));
                statement.setString(7, draft.clientId().strip());
                statement.setString(8, secretRef.map(SecretReference::toString).orElse(null));
                statement.setArray(9, connection.createArrayOf("text", draft.scopes().toArray()));
                statement.setString(10, draft.claimSubject());
                statement.setString(11, draft.claimUsername());
                statement.setString(12, draft.claimEmail());
                statement.setString(13, draft.claimDisplayName());
                statement.setString(14, draft.claimGroups().filter(g -> !g.isBlank()).orElse(null));
                statement.setBoolean(15, draft.jitProvisioning());
                statement.setArray(16, draft.allowedEmailDomains().isEmpty() ? null
                        : connection.createArrayOf("text", draft.allowedEmailDomains().stream()
                                .map(d -> d.strip().toLowerCase(Locale.ROOT)).toArray()));
                statement.setBoolean(17, draft.mfaAssertedByProvider());
                statement.setString(18, lifecycle);
                statement.setString(19, testStatus);
                statement.setString(20, testDetail);
                statement.setObject(21, actor.principalId());
                statement.setObject(22, actor.principalId());
                try (ResultSet r = statement.executeQuery()) {
                    r.next();
                    id = r.getObject(1, UUID.class);
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", draft.code());
            payload.put("preset", draft.preset().name());
            payload.put("issuer", draft.issuer());
            payload.put("lifecycle_state", lifecycle);
            payload.put("client_secret_held", secretRef.isPresent());
            audit.domainChangeBy(connection, actor.principalId(), "identity_provider",
                    aspm.kernel.audit.contract.DomainChangeKind.CREATED, id, null, payload);
            connection.commit();
            return query(connection, "WHERE id = ?", id).get(0);
        }
    }

    /** Updates the editable fields. A new secret replaces the reference and destroys the old one. */
    public Provider update(Principal actor, UUID id, Draft draft, int expectedRowVersion) throws SQLException {
        validate(draft);
        UUID tenantId = actor.tenantId();
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Provider current = query(connection, "WHERE id = ?", id).stream().findFirst()
                    .orElseThrow(() -> new aspm.app.runtime.Dispatcher.UnauthorizedException("no such provider"));
            if (current.rowVersion() != expectedRowVersion) {
                throw new aspm.app.runtime.Dispatcher.UnauthorizedException("stale row_version");
            }
            Optional<SecretReference> secretRef = current.clientSecretRef();
            if (draft.clientSecret().isPresent() && draft.clientSecret().get().length > 0) {
                SecretReference fresh = secrets.store(tenantId, "identity_provider", draft.code() + "-client-secret",
                        draft.clientSecret().get());
                current.clientSecretRef().ifPresent(old -> secrets.destroy(tenantId, old));
                secretRef = Optional.of(fresh);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE identity_provider
                       SET display_name = ?, preset = ?, issuer = ?, discovery_url = ?, client_id = ?,
                           client_secret_ref = ?, scopes = ?, claim_subject = ?, claim_username = ?,
                           claim_email = ?, claim_display_name = ?, claim_groups = ?, jit_provisioning = ?,
                           allowed_email_domains = ?, mfa_asserted_by_provider = ?,
                           updated_at = now(), updated_by = ?, row_version = row_version + 1
                     WHERE id = ? AND row_version = ?
                    """)) {
                statement.setString(1, draft.displayName().strip());
                statement.setString(2, draft.preset().name());
                statement.setString(3, draft.issuer().strip());
                statement.setString(4, draft.discoveryUrl().filter(u -> !u.isBlank()).orElse(null));
                statement.setString(5, draft.clientId().strip());
                statement.setString(6, secretRef.map(SecretReference::toString).orElse(null));
                statement.setArray(7, connection.createArrayOf("text", draft.scopes().toArray()));
                statement.setString(8, draft.claimSubject());
                statement.setString(9, draft.claimUsername());
                statement.setString(10, draft.claimEmail());
                statement.setString(11, draft.claimDisplayName());
                statement.setString(12, draft.claimGroups().filter(g -> !g.isBlank()).orElse(null));
                statement.setBoolean(13, draft.jitProvisioning());
                statement.setArray(14, draft.allowedEmailDomains().isEmpty() ? null
                        : connection.createArrayOf("text", draft.allowedEmailDomains().stream()
                                .map(d -> d.strip().toLowerCase(Locale.ROOT)).toArray()));
                statement.setBoolean(15, draft.mfaAssertedByProvider());
                statement.setObject(16, actor.principalId());
                statement.setObject(17, id);
                statement.setInt(18, expectedRowVersion);
                if (statement.executeUpdate() != 1) {
                    throw new aspm.app.runtime.Dispatcher.UnauthorizedException("stale row_version");
                }
            }
            oidc.forget(current.issuer());
            audit.domainChangeBy(connection, actor.principalId(), "identity_provider",
                    aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id, null,
                    Map.of("issuer", draft.issuer(), "client_secret_replaced", draft.clientSecret().isPresent()));
            connection.commit();
            return query(connection, "WHERE id = ?", id).get(0);
        }
    }

    /** ACTIVE ⇄ DISABLED, or → RETIRED (terminal). */
    public Provider transition(Principal actor, UUID id, String target) throws SQLException {
        if (!List.of("ACTIVE", "DISABLED", "RETIRED").contains(target)) {
            throw new IllegalArgumentException("the target state is not one this provider can take");
        }
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Provider current = query(connection, "WHERE id = ?", id).stream().findFirst()
                    .orElseThrow(() -> new aspm.app.runtime.Dispatcher.UnauthorizedException("no such provider"));
            if ("RETIRED".equals(current.lifecycleState())) {
                throw new IllegalArgumentException("a retired provider stays retired; create a new one");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE identity_provider SET lifecycle_state = ?, updated_at = now(), updated_by = ?, "
                            + "row_version = row_version + 1 WHERE id = ?")) {
                statement.setString(1, target);
                statement.setObject(2, actor.principalId());
                statement.setObject(3, id);
                statement.executeUpdate();
            }
            if ("RETIRED".equals(target)) {
                current.clientSecretRef().ifPresent(ref -> secrets.destroy(actor.tenantId(), ref));
            }
            audit.domainChangeBy(connection, actor.principalId(), "identity_provider",
                    "RETIRED".equals(target) ? aspm.kernel.audit.contract.DomainChangeKind.RETIRED
                            : aspm.kernel.audit.contract.DomainChangeKind.TRANSITIONED,
                    id, null, Map.of("from", current.lifecycleState(), "to", target));
            connection.commit();
            return query(connection, "WHERE id = ?", id).get(0);
        }
    }

    /** Re-runs discovery and the key fetch, recording the outcome. Reactivates nothing by itself. */
    public Provider test(Principal actor, UUID id) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Provider current = query(connection, "WHERE id = ?", id).stream().findFirst()
                    .orElseThrow(() -> new aspm.app.runtime.Dispatcher.UnauthorizedException("no such provider"));
            oidc.forget(current.issuer());
            String status;
            String detail;
            try {
                OidcClient.Discovery discovery = oidc.discover(current.issuer(), current.discoveryUrl());
                List<Map<String, Object>> keys = oidc.keys(discovery, Optional.empty());
                boolean algorithmOk = discovery.idTokenSigningAlgorithms().stream().anyMatch(JsonWebToken.ALLOWED_ALGORITHMS::contains);
                status = algorithmOk ? "OK" : "FAILED";
                detail = algorithmOk ? "discovery ok; " + keys.size() + " signing key(s); algorithms "
                        + String.join(",", discovery.idTokenSigningAlgorithms())
                        : "the provider advertises no signing algorithm this platform accepts";
            } catch (OidcClient.ProviderUnavailable e) {
                status = "FAILED";
                detail = e.code();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE identity_provider SET last_test_at = now(), last_test_status = ?, last_test_detail = ?, "
                            + "last_discovery_at = CASE WHEN ? = 'OK' THEN now() ELSE last_discovery_at END WHERE id = ?")) {
                statement.setString(1, status);
                statement.setString(2, detail);
                statement.setString(3, status);
                statement.setObject(4, id);
                statement.executeUpdate();
            }
            connection.commit();
            return query(connection, "WHERE id = ?", id).get(0);
        }
    }

    /** Replaces a provider's group-to-role mappings wholesale. */
    public List<GroupRole> setGroupRoles(Principal actor, UUID providerId, List<Map<String, String>> mappings) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            if (query(connection, "WHERE id = ?", providerId).isEmpty()) {
                throw new aspm.app.runtime.Dispatcher.UnauthorizedException("no such provider");
            }
            try (PreparedStatement clear = connection.prepareStatement(
                    "DELETE FROM identity_provider_group_role WHERE provider_id = ?")) {
                clear.setObject(1, providerId);
                clear.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO identity_provider_group_role (tenant_id, provider_id, group_value, role_id,
                        scope_node_id, scope_mode, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                for (Map<String, String> m : mappings) {
                    String group = Objects.requireNonNull(m.get("group"), "a group value is required").strip();
                    UUID roleId = UUID.fromString(Objects.requireNonNull(m.get("roleId"), "a role is required"));
                    String scopeMode = m.getOrDefault("scopeMode", "TENANT");
                    String node = m.get("scopeNodeId");
                    insert.setObject(1, actor.tenantId());
                    insert.setObject(2, providerId);
                    insert.setString(3, group);
                    insert.setObject(4, roleId);
                    insert.setObject(5, node == null || node.isBlank() ? null : UUID.fromString(node));
                    insert.setString(6, scopeMode);
                    insert.setObject(7, actor.principalId());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            audit.domainChangeBy(connection, actor.principalId(), "identity_provider",
                    aspm.kernel.audit.contract.DomainChangeKind.UPDATED, providerId, null,
                    Map.of("group_role_mappings", mappings.size()));
            connection.commit();
            return groupRoles(connection, providerId);
        }
    }

    /** Turns the tenant's local sign-in on or off. Administrators flagged break-glass keep theirs. */
    public void setLocalSignIn(Principal actor, boolean enabled) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            if (!enabled) {
                // Refuse to lock the tenant out: at least one active provider, and at least one active
                // principal who can still use a password, must exist.
                boolean anyProvider = !query(connection, "WHERE lifecycle_state = 'ACTIVE'", null).isEmpty();
                boolean anyBreakGlass;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT EXISTS (SELECT 1 FROM principal WHERE lifecycle_state = 'ACTIVE' AND break_glass_local_sign_in)");
                        ResultSet r = statement.executeQuery()) {
                    r.next();
                    anyBreakGlass = r.getBoolean(1);
                }
                if (!anyProvider || !anyBreakGlass) {
                    throw new IllegalArgumentException("local sign-in can be disabled only when an identity provider is "
                            + "active and at least one active account is marked for break-glass local sign-in; "
                            + "otherwise nobody could sign in when the provider is unreachable (SEC-SEC-002)");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE password_policy SET local_sign_in_enabled = ?, updated_at = now(), updated_by = ? WHERE tenant_id = ?")) {
                statement.setBoolean(1, enabled);
                statement.setObject(2, actor.principalId());
                statement.setObject(3, actor.tenantId());
                statement.executeUpdate();
            }
            audit.domainChangeBy(connection, actor.principalId(), "password_policy",
                    aspm.kernel.audit.contract.DomainChangeKind.UPDATED, null, null,
                    Map.of("local_sign_in_enabled", enabled));
            connection.commit();
        }
    }

    /** Marks or unmarks a principal as able to use a password when local sign-in is off. */
    public void setBreakGlass(Principal actor, UUID principalId, boolean allowed) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE principal SET break_glass_local_sign_in = ?, updated_at = now(), updated_by = ?, "
                            + "row_version = row_version + 1 WHERE id = ?")) {
                statement.setBoolean(1, allowed);
                statement.setObject(2, actor.principalId());
                statement.setObject(3, principalId);
                if (statement.executeUpdate() != 1) {
                    throw new aspm.app.runtime.Dispatcher.UnauthorizedException("no such principal");
                }
            }
            audit.domainChangeBy(connection, actor.principalId(), "principal",
                    aspm.kernel.audit.contract.DomainChangeKind.UPDATED, principalId, null,
                    Map.of("break_glass_local_sign_in", allowed));
            connection.commit();
        }
    }

    // ==============================================================================================

    private void validate(Draft draft) {
        Objects.requireNonNull(draft.preset(), "a preset is required");
        if (draft.code() == null || !draft.code().matches("[a-z][a-z0-9-]{1,31}")) {
            throw new IllegalArgumentException("the sign-in code is 2–32 lower-case letters, digits or dashes, starting with a letter");
        }
        if (draft.displayName() == null || draft.displayName().isBlank() || draft.displayName().strip().length() > 80) {
            throw new IllegalArgumentException("a display name of at most 80 characters is required");
        }
        if (draft.clientId() == null || draft.clientId().isBlank()) {
            throw new IllegalArgumentException("a client id is required");
        }
        egress.refusal(draft.issuer()).ifPresent(reason -> {
            throw new IllegalArgumentException("the issuer was refused: " + reason);
        });
        draft.discoveryUrl().filter(u -> !u.isBlank()).flatMap(egress::refusal).ifPresent(reason -> {
            throw new IllegalArgumentException("the discovery URL was refused: " + reason);
        });
        if (!draft.scopes().contains("openid")) {
            throw new IllegalArgumentException("the openid scope is required");
        }
        for (String claim : List.of(draft.claimSubject(), draft.claimUsername(), draft.claimEmail(), draft.claimDisplayName())) {
            if (claim == null || !claim.matches("[A-Za-z_][A-Za-z0-9_.:/-]{0,127}")) {
                throw new IllegalArgumentException("a claim name is letters, digits, underscores, dots, colons, slashes or dashes");
            }
        }
        for (String domain : draft.allowedEmailDomains()) {
            if (!domain.strip().toLowerCase(Locale.ROOT).matches("[a-z0-9.-]+\\.[a-z]{2,}")) {
                throw new IllegalArgumentException("an allowed email domain is a domain name such as example.com");
            }
        }
    }

    private static List<Provider> query(Connection connection, String where, Object parameter) throws SQLException {
        List<Provider> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, tenant_id, code, display_name, preset, issuer, discovery_url, client_id, client_secret_ref, "
                        + "scopes, claim_subject, claim_username, claim_email, claim_display_name, claim_groups, "
                        + "jit_provisioning, allowed_email_domains, mfa_asserted_by_provider, lifecycle_state, "
                        + "last_test_status, last_test_detail, row_version FROM identity_provider " + where)) {
            if (parameter != null) {
                statement.setObject(1, parameter);
            }
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    String[] scopes = r.getArray(10) == null ? new String[0] : (String[]) r.getArray(10).getArray();
                    String[] domains = r.getArray(17) == null ? new String[0] : (String[]) r.getArray(17).getArray();
                    out.add(new Provider(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getString(4),
                            Preset.valueOf(r.getString(5)), r.getString(6), Optional.ofNullable(r.getString(7)), r.getString(8),
                            Optional.ofNullable(r.getString(9)).map(SecretReference::parse), Arrays.asList(scopes),
                            r.getString(11), r.getString(12), r.getString(13), r.getString(14),
                            Optional.ofNullable(r.getString(15)), r.getBoolean(16), Arrays.asList(domains), r.getBoolean(18),
                            r.getString(19), Optional.ofNullable(r.getString(20)), Optional.ofNullable(r.getString(21)),
                            r.getInt(22)));
                }
            }
        }
        return out;
    }
}
