package aspm.app.identity;

import aspm.app.persistence.TenantConnections;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
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
 * Credential change, user administration, and the role/permission surfaces. DOC-07, {@code PRD-AUZ-001},
 * {@code SEC-SEC-006}, {@code SEC-SEC-016}.
 *
 * <h2>Why this is not part of {@link IdentityService}</h2>
 *
 * <p>{@link IdentityService} is on the authentication path of <b>every request</b>. These operations are
 * administrative and interactive, and mixing them in would put rarely-exercised code in the hottest class
 * in the application — where a mistake is a mistake in authentication rather than in an admin page.
 *
 * <h2>What an administrator can and cannot do</h2>
 *
 * <p>An administrator can force a reset. An administrator <b>cannot set a password to a value they choose
 * and know</b>, and that is a deliberate refusal rather than a missing feature: a credential the
 * administrator knows is a credential that cannot be attributed to the account holder afterwards, which
 * defeats every audit entry the account subsequently produces. {@link #resetCredential} issues a
 * single-use token and returns it once; the holder chooses the value.
 *
 * <p>{@code SEC-SEC-016} requires a reset path that "MUST NOT disclose whether the principal exists" —
 * that applies to the self-service path in {@code AuthPages}. Here the caller already holds
 * {@code iam.user.read} and is looking at a list of users, so existence is not a secret being kept from
 * them; the control on this path is {@code iam.credential.reset} plus step-up.
 */
public final class AccountService {

    /** The outcome of a credential change. */
    public sealed interface ChangeOutcome {

        /** Accepted. Every other session was revoked. */
        record Accepted(int otherSessionsRevoked) implements ChangeOutcome {
        }

        /** The current password did not verify. Separate from a policy failure: the remedy differs. */
        record CurrentPasswordWrong() implements ChangeOutcome {
        }

        /** The candidate failed the policy. Failure codes, for the interface to translate. */
        record PolicyFailed(List<String> failures, long corpusSize) implements ChangeOutcome {
        }
    }

    /** One row of the administration list. */
    public record UserRow(UUID id, String username, String email, String displayName,
            String lifecycleState, boolean mustChangePassword, boolean mfaEnrolled,
            String lastAuthenticatedAt, long liveAssignments, long liveSessions,
            List<String> roleCodes) {
    }

    /**
     * A tenant-defined role and the product-fixed codes composing it.
     *
     * @param fromTemplate whether the role was seeded from a product template. Shown in the editor because
     *     a tenant editing a template-derived role should know a later product change to that template will
     *     be OFFERED rather than applied — DOC-07 §5.3's templates are a starting point, not a binding
     */
    public record RoleRow(UUID id, String code, String label, String description,
            Set<String> permissionCodes, long assignmentCount, boolean active, boolean fromTemplate) {
    }

    /** One catalogue entry. Product-fixed ({@code PRD-AUZ-001}). */
    public record PermissionRow(String code, String domain, String label, boolean restricted,
            boolean requiresStepUp) {
    }

    /** A reset token, returned exactly once. */
    public record ResetIssued(String token, java.time.Instant expiresAt, int sessionsRevoked) {
    }

    /**
     * How long an administratively issued reset token lives.
     *
     * <p>Thirty minutes, inside V015's one-hour engine bound. A reset link is a bearer credential for the
     * account with no second factor in front of it, so its lifetime is the window in which an intercepted
     * link is usable.
     */
    private static final Duration RESET_LIFETIME = Duration.ofMinutes(30);

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public AccountService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Self-service password change.
     *
     * <p>The current password is required even though the caller holds a live session. A session is
     * something an attacker can steal; the current password is something they have to know. Without this
     * check, session theft escalates to permanent account takeover in one form submission, because the
     * new password locks the owner out.
     *
     * <p>On success every OTHER session is revoked and the caller's own is kept. {@code SEC-SEC-016}
     * requires session invalidation on credential change; keeping the caller's own session avoids
     * bouncing them to sign-in immediately after they did the right thing, and the sessions that matter —
     * an attacker's — are the ones revoked.
     */
    public ChangeOutcome changeOwnPassword(UUID tenantId, UUID principalId, UUID sessionId,
            char[] current, char[] candidate) throws SQLException {
        try (Connection connection = open(tenantId)) {
            connection.setAutoCommit(false);
            try {
                Optional<PasswordHash.Stored> stored = credentialOf(connection, principalId);
                if (stored.isEmpty() || !PasswordHash.verify(current, stored.orElseThrow())) {
                    connection.commit();
                    return new ChangeOutcome.CurrentPasswordWrong();
                }

                String username = null;
                String email = null;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT username, email FROM principal WHERE id = ?")) {
                    statement.setObject(1, principalId);
                    try (ResultSet results = statement.executeQuery()) {
                        if (results.next()) {
                            username = results.getString(1);
                            email = results.getString(2);
                        }
                    }
                }

                PasswordPolicy.Settings settings = PasswordPolicy.load(connection);
                PasswordPolicy.Result verdict = PasswordPolicy.evaluate(connection, settings, candidate,
                        principalId, username, email);
                if (!verdict.acceptable()) {
                    connection.commit();
                    return new ChangeOutcome.PolicyFailed(verdict.failures(), verdict.corpusSize());
                }

                IdentityService.replaceCredential(connection, principalId, candidate, "SELF_SERVICE");
                stampSetBy(connection, principalId, principalId);
                clearMustChange(connection, principalId);
                int revoked = revokeOtherSessions(connection, principalId, sessionId,
                        "CREDENTIAL_CHANGED");
                // The authenticated half of a credential change. Its sibling — redeeming a reset
                // token — runs with no session and therefore no tenant context to write a chained
                // event under; see the note there.
                audit.domainChangeBy(connection, principalId, "principal",
                        aspm.kernel.audit.contract.DomainChangeKind.UPDATED, principalId, null,
                        java.util.Map.of("credential_changed_via", "self service",
                                "sessions_revoked", Integer.valueOf(revoked)));
                connection.commit();
                return new ChangeOutcome.Accepted(revoked);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Administrative reset. {@code iam.credential.reset} — restricted, and step-up in the catalogue.
     *
     * <p>Issues a single-use token, forces a change at next sign-in, and revokes <b>every</b> session the
     * target holds. All three, because a reset is what an administrator does when an account may be
     * compromised, and leaving the existing sessions live would mean the reset changed the credential
     * while the attacker kept the access.
     *
     * <p>The token is returned to the caller and its hash is what is stored. An administrator hands it
     * over out of band; {@code delivery_channel} records that, so the audit trail distinguishes a link
     * the platform emailed from one a person carried.
     */
    public ResetIssued resetCredential(UUID tenantId, UUID actorId, UUID targetId) throws SQLException {
        String token = PasswordHash.randomToken(32);
        java.time.Instant expiresAt = java.time.Instant.now().plus(RESET_LIFETIME);
        try (Connection connection = open(tenantId)) {
            connection.setAutoCommit(false);
            try {
                // Any unused token for this principal is spent. Two live reset links for one account
                // means the older one still works after the newer is used, and the older is the one that
                // has been sitting in somebody's inbox.
                try (PreparedStatement spend = connection.prepareStatement(
                        "UPDATE credential_reset_token SET used_at = now() "
                                + "WHERE principal_id = ? AND used_at IS NULL")) {
                    spend.setObject(1, targetId);
                    spend.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO credential_reset_token (tenant_id, principal_id, token_hash, "
                                + "expires_at, issued_by, delivery_channel) "
                                + "VALUES (?, ?, ?, ?, ?, 'ADMIN_HANDOVER')")) {
                    insert.setObject(1, tenantId);
                    insert.setObject(2, targetId);
                    insert.setBytes(3, PasswordHash.tokenHash(token));
                    insert.setObject(4, java.time.OffsetDateTime.ofInstant(expiresAt,
                            java.time.ZoneOffset.UTC));
                    insert.setObject(5, actorId);
                    insert.executeUpdate();
                }
                try (PreparedStatement flag = connection.prepareStatement(
                        "UPDATE principal SET must_change_password = true, "
                                + "row_version = row_version + 1, updated_at = now(), updated_by = ? "
                                + "WHERE id = ?")) {
                    flag.setObject(1, actorId);
                    flag.setObject(2, targetId);
                    flag.executeUpdate();
                }
                int revoked = revokeOtherSessions(connection, targetId, null, "CREDENTIAL_RESET");
                // An administrator issuing a reset link can take over any account in the tenant, and
                // the link itself is deliberately not stored in a readable form — so this event is
                // the only durable statement that it happened. The token is never in the payload.
                audit.domainChangeBy(connection, actorId, "principal",
                        aspm.kernel.audit.contract.DomainChangeKind.UPDATED, targetId, null,
                        java.util.Map.of("credential_reset_issued", Boolean.TRUE,
                                "must_change_password", Boolean.TRUE,
                                "sessions_revoked", Integer.valueOf(revoked),
                                "expires_at", expiresAt.toString()));
                connection.commit();
                return new ResetIssued(token, expiresAt, revoked);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Redeems a reset token and sets the new credential.
     *
     * <p>The token is looked up by hash and consumed in the same statement that checks it is unused, so
     * two concurrent redemptions cannot both succeed. Checking then updating would let both pass under
     * concurrency, and a reset token is precisely the value an attacker races.
     */
    public Optional<ChangeOutcome> redeemReset(UUID tenantId, String token, char[] candidate)
            throws SQLException {
        try (Connection connection = open(tenantId)) {
            connection.setAutoCommit(false);
            try {
                UUID principalId;
                try (PreparedStatement claim = connection.prepareStatement(
                        "UPDATE credential_reset_token SET used_at = now() "
                                + "WHERE token_hash = ? AND used_at IS NULL AND expires_at > now() "
                                + "RETURNING principal_id")) {
                    claim.setBytes(1, PasswordHash.tokenHash(token));
                    try (ResultSet results = claim.executeQuery()) {
                        if (!results.next()) {
                            connection.commit();
                            // Empty rather than a failure shape: an expired token, a consumed token and a
                            // token that never existed are one answer, because distinguishing them tells
                            // a holder of a guessed token which guesses were closer.
                            return Optional.empty();
                        }
                        principalId = results.getObject(1, UUID.class);
                    }
                }

                String username = null;
                String email = null;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT username, email FROM principal WHERE id = ?")) {
                    statement.setObject(1, principalId);
                    try (ResultSet results = statement.executeQuery()) {
                        if (results.next()) {
                            username = results.getString(1);
                            email = results.getString(2);
                        }
                    }
                }
                PasswordPolicy.Settings settings = PasswordPolicy.load(connection);
                PasswordPolicy.Result verdict = PasswordPolicy.evaluate(connection, settings, candidate,
                        principalId, username, email);
                if (!verdict.acceptable()) {
                    // The token is NOT restored. A policy failure that returns the token to the pool
                    // turns a reset link into an unlimited password-policy oracle; the holder requests a
                    // new link, which costs an administrator's attention and is the point.
                    connection.commit();
                    return Optional.of(new ChangeOutcome.PolicyFailed(verdict.failures(),
                            verdict.corpusSize()));
                }
                IdentityService.replaceCredential(connection, principalId, candidate, "RESET_REDEEMED");
                stampSetBy(connection, principalId, principalId);
                clearMustChange(connection, principalId);
                int revoked = revokeOtherSessions(connection, principalId, null, "CREDENTIAL_RESET");
                // NOT recorded in the chain, and the reason is the same structural one as the
                // sign-in path: redeeming a reset token is done by somebody who has no session, so
                // there is no established tenant context and ChainedAuditWriter fails closed
                // (SEC-TEN-004, SEC-TEN-005). The credential row records that it changed and when.
                connection.commit();
                return Optional.of(new ChangeOutcome.Accepted(revoked));
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    // ----------------------------------------------------------------------------------------------

    /** The administration list. Reads the {@code principal_administration} view of V016. */
    public List<UserRow> users(UUID tenantId) throws SQLException {
        Map<UUID, List<String>> roles = roleCodesByPrincipal(tenantId);
        List<UserRow> rows = new ArrayList<>();
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, username, email, display_name, lifecycle_state, "
                                + "must_change_password, mfa_enrolled, last_authenticated_at, "
                                + "live_assignments, live_sessions FROM principal_administration "
                                + "ORDER BY username");
                ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                UUID id = results.getObject(1, UUID.class);
                var seen = results.getObject(8, java.time.OffsetDateTime.class);
                rows.add(new UserRow(id, results.getString(2), results.getString(3),
                        results.getString(4), results.getString(5), results.getBoolean(6),
                        results.getBoolean(7),
                        // Null is rendered as "never" by the interface rather than as a blank cell.
                        // PP-1: a principal who has never authenticated is a different fact from one
                        // whose last sign-in was not recorded.
                        seen == null ? null : seen.toString(),
                        results.getLong(9), results.getLong(10),
                        roles.getOrDefault(id, List.of())));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Active roles with their composed permission codes, for the RBAC matrix and the grant picker.
     *
     * <p>Active only. A DEPRECATED role must not appear in a picker — granting one would create an
     * assignment that grants nothing, because {@link IdentityService#principal} joins on
     * {@code lifecycle_state = 'ACTIVE'}. The editor uses {@link #allRoles} and marks the retired ones.
     */
    public List<RoleRow> roles(UUID tenantId) throws SQLException {
        return rolesWhere(tenantId, null).stream()
                .filter(RoleRow::active)
                .toList();
    }

    /**
     * @param onlyRole a single role to fetch, or null for all of them including retired
     */
    private List<RoleRow> rolesWhere(UUID tenantId, UUID onlyRole) throws SQLException {
        Map<UUID, Set<String>> codes = new LinkedHashMap<>();
        Map<UUID, Long> counts = new LinkedHashMap<>();
        List<RoleRow> rows = new ArrayList<>();
        try (Connection connection = open(tenantId)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT role_id, permission_code FROM role_permission");
                    ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    codes.computeIfAbsent(results.getObject(1, UUID.class),
                            key -> new LinkedHashSet<>()).add(results.getString(2));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT role_id, count(*) FROM role_assignment WHERE revoked_at IS NULL "
                            + "AND (expires_at IS NULL OR expires_at > now()) GROUP BY role_id");
                    ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    counts.put(results.getObject(1, UUID.class), results.getLong(2));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, code, coalesce(label_i18n->>'en', code), description, lifecycle_state, "
                            + "derived_from_template FROM role "
                            + "WHERE (?::uuid IS NULL OR id = ?) ORDER BY lifecycle_state, code")) {
                statement.setObject(1, onlyRole);
                statement.setObject(2, onlyRole);
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        UUID id = results.getObject(1, UUID.class);
                        rows.add(new RoleRow(id, results.getString(2), results.getString(3),
                                results.getString(4), Set.copyOf(codes.getOrDefault(id, Set.of())),
                                counts.getOrDefault(id, 0L),
                                "ACTIVE".equals(results.getString(5)),
                                results.getString(6) != null));
                    }
                }
            }
        }
        return List.copyOf(rows);
    }

    /** The product-fixed catalogue, in catalogue order. */
    public List<PermissionRow> permissions(UUID tenantId) throws SQLException {
        List<PermissionRow> rows = new ArrayList<>();
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT code, domain, coalesce(label_i18n->>'en', code), is_restricted, "
                                + "requires_step_up FROM permission_catalogue ORDER BY domain, code");
                ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rows.add(new PermissionRow(results.getString(1), results.getString(2),
                        results.getString(3), results.getBoolean(4), results.getBoolean(5)));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Grants a role over a scope. {@code auz.role.manage}.
     *
     * <p>The scope is named here and expanded through the closure table at resolution time, never stored
     * expanded. Product principle 4 makes scope derived: an expansion stored at grant time is a snapshot
     * that stops matching the organization tree the first time somebody moves a node.
     *
     * @param scopeNodeId the subtree root, or null for a tenant-wide grant. V015's
     *     {@code ck_assignment__scope_present} enforces that exactly one of the two shapes is used
     */
    public boolean assignRole(UUID tenantId, UUID actorId, UUID principalId, UUID roleId,
            String scopeMode, UUID scopeNodeId) throws SQLException {
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO role_assignment (tenant_id, principal_id, role_id, scope_node_id, "
                                + "scope_mode, granted_by) VALUES (?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT DO NOTHING")) {
            statement.setObject(1, tenantId);
            statement.setObject(2, principalId);
            statement.setObject(3, roleId);
            statement.setObject(4, "TENANT".equals(scopeMode) ? null : scopeNodeId);
            statement.setString(5, scopeMode);
            statement.setObject(6, actorId);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                // The access review of DOC-07 §15 reads this: who was given what, over which part of
                // the organization, and by whom. The scope MODE is in the payload because a grant over
                // the whole tenant and a grant over one node are the same row with one column
                // different, and that column is the whole difference in blast radius.
                audit.eventBy(connection, actorId,
                        aspm.kernel.audit.contract.AuditEventType.ASSIGNMENT_GRANTED,
                        principalId, "TENANT".equals(scopeMode) ? null : scopeNodeId,
                        java.util.Map.of("principal_id", principalId.toString(),
                                "role_id", roleId.toString(),
                                "scope_mode", scopeMode,
                                "scope_node_id", scopeNodeId == null ? "" : scopeNodeId.toString()));
            }
            connection.commit();
            return applied;
        }
    }

    /**
     * Revokes an assignment. Revoked, not deleted: the grant is evidence of a decision, and
     * {@code uq_assignment__live} is partial on {@code revoked_at IS NULL} so the same grant can be made
     * again afterwards.
     */
    public boolean revokeAssignment(UUID tenantId, UUID assignmentId, String reason)
            throws SQLException {
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE role_assignment SET revoked_at = now(), revoked_reason = ? "
                                + "WHERE id = ? AND revoked_at IS NULL")) {
            statement.setString(1, reason);
            statement.setObject(2, assignmentId);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.eventBy(connection, null,
                        aspm.kernel.audit.contract.AuditEventType.ASSIGNMENT_REVOKED,
                        assignmentId, null,
                        java.util.Map.of("assignment_id", assignmentId.toString(),
                                "reason", reason == null ? "" : reason));
            }
            connection.commit();
            return applied;
        }
    }

    /** The live assignments of one principal, with role and scope, for the detail panel. */
    public List<Map<String, String>> assignmentsOf(UUID tenantId, UUID principalId)
            throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT a.id, r.code, coalesce(r.label_i18n->>'en', r.code), a.scope_mode, "
                                + "       coalesce(n.name, '') , a.granted_at "
                                + "  FROM role_assignment a "
                                + "  JOIN role r ON r.id = a.role_id "
                                + "  LEFT JOIN org_node n ON n.id = a.scope_node_id "
                                + " WHERE a.principal_id = ? AND a.revoked_at IS NULL "
                                + "   AND (a.expires_at IS NULL OR a.expires_at > now()) "
                                + " ORDER BY r.code")) {
            statement.setObject(1, principalId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("id", results.getObject(1, UUID.class).toString());
                    row.put("role_code", results.getString(2));
                    row.put("role_label", results.getString(3));
                    row.put("scope_mode", results.getString(4));
                    row.put("scope_node", results.getString(5));
                    row.put("granted_at", String.valueOf(results.getObject(6)));
                    rows.add(row);
                }
            }
        }
        return List.copyOf(rows);
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Creates a tenant role. {@code auz.role.manage}, {@code PRD-AUZ-001}.
     *
     * <p>The code is normalised and bounded here; the label is stored as an i18n object because
     * {@code NFR-INT-003} requires externalised strings from v1 and a role name a tenant types is
     * user-facing content in exactly the way that requirement is about.
     *
     * @return the new role, or empty if the code already exists
     */
    public Optional<UUID> createRole(UUID tenantId, UUID actorId, String code, String label,
            String description) throws SQLException {
        String normalised = normaliseRoleCode(code);
        if (normalised.isEmpty()) {
            return Optional.empty();
        }
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO role (tenant_id, code, label_i18n, description, created_by, "
                                + "updated_by) VALUES (?, ?, ?::jsonb, ?, ?, ?) "
                                + "ON CONFLICT (tenant_id, code) DO NOTHING RETURNING id")) {
            statement.setObject(1, tenantId);
            statement.setString(2, normalised);
            // Built with the JSON writer rather than string concatenation: a label containing a quote
            // would otherwise produce invalid JSON, and the engine would reject a legitimate role name.
            statement.setString(3, aspm.app.runtime.Json.write(Map.of("en",
                    label == null || label.isBlank() ? normalised : label.strip())));
            statement.setString(4, description == null || description.isBlank()
                    ? null : description.strip());
            statement.setObject(5, actorId);
            statement.setObject(6, actorId);
            Optional<UUID> created;
            try (ResultSet results = statement.executeQuery()) {
                created = results.next()
                        ? Optional.of(results.getObject(1, UUID.class)) : Optional.empty();
            }
            if (created.isPresent()) {
                audit.eventBy(connection, actorId,
                        aspm.kernel.audit.contract.AuditEventType.ROLE_CREATED,
                        created.orElseThrow(), null,
                        java.util.Map.of("code", normalised,
                                "label", label == null || label.isBlank() ? normalised : label.strip()));
            }
            connection.commit();
            return created;
        }
    }

    /** Renames a role and updates its description. The CODE is immutable — see {@link #normaliseRoleCode}. */
    public boolean updateRole(UUID tenantId, UUID actorId, UUID roleId, String label,
            String description) throws SQLException {
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE role SET label_i18n = ?::jsonb, description = ?, updated_at = now(), "
                                + "updated_by = ?, row_version = row_version + 1 WHERE id = ?")) {
            statement.setString(1, aspm.app.runtime.Json.write(Map.of("en",
                    label == null || label.isBlank() ? "role" : label.strip())));
            statement.setString(2, description == null || description.isBlank()
                    ? null : description.strip());
            statement.setObject(3, actorId);
            statement.setObject(4, roleId);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.eventBy(connection, actorId,
                        aspm.kernel.audit.contract.AuditEventType.ROLE_UPDATED, roleId, null,
                        java.util.Map.of("label", label == null || label.isBlank()
                                ? "role" : label.strip()));
            }
            connection.commit();
            return applied;
        }
    }

    /**
     * Replaces a role's permission set with exactly the codes given.
     *
     * <p>Replace, not add: the editor submits the full state of a checkbox matrix, and applying it as a
     * series of additions would make unchecking a box do nothing. Both statements run in one transaction,
     * because a delete that commits without its insert leaves the role holding nothing — and a role that
     * silently grants nothing is a role whose holders lose access with no event saying so.
     *
     * <p>Every code is checked against the catalogue by the foreign key, not by this method.
     * {@code PRD-AUZ-001} makes the catalogue product-fixed, so an unknown code must fail at the engine
     * rather than be filtered here — filtering would accept a typo silently and grant less than intended.
     *
     * @return the number of permissions the role holds afterwards
     */
    public int setRolePermissions(UUID tenantId, UUID actorId, UUID roleId, Set<String> codes)
            throws SQLException {
        try (Connection connection = open(tenantId)) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement clear = connection.prepareStatement(
                        "DELETE FROM role_permission WHERE role_id = ?")) {
                    clear.setObject(1, roleId);
                    clear.executeUpdate();
                }
                int written = 0;
                if (!codes.isEmpty()) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO role_permission (tenant_id, role_id, permission_code, "
                                    + "granted_by) VALUES (?, ?, ?, ?)")) {
                        for (String code : codes) {
                            insert.setObject(1, tenantId);
                            insert.setObject(2, roleId);
                            insert.setString(3, code);
                            insert.setObject(4, actorId);
                            insert.addBatch();
                        }
                        for (int result : insert.executeBatch()) {
                            written += Math.max(0, result);
                        }
                    }
                }
                try (PreparedStatement touch = connection.prepareStatement(
                        "UPDATE role SET updated_at = now(), updated_by = ?, "
                                + "row_version = row_version + 1 WHERE id = ?")) {
                    touch.setObject(1, actorId);
                    touch.setObject(2, roleId);
                    touch.executeUpdate();
                }
                // The whole set, not the difference. A permission matrix is submitted whole, and a
                // reviewer asking "what could this role do on that date" needs the state, which a
                // list of deltas only yields by replaying every event since the role was created.
                audit.eventBy(connection, actorId,
                        aspm.kernel.audit.contract.AuditEventType.ROLE_PERMISSION_CHANGED,
                        roleId, null,
                        java.util.Map.of("permissions", codes.stream().sorted().toList(),
                                "permission_count", Integer.valueOf(written)));
                connection.commit();
                return written;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** What removing a role would do. Computed before it is offered, so the button matches the outcome. */
    public record RoleRemoval(boolean deletable, long liveAssignments, long everAssigned) {
    }

    /**
     * Whether a role can be deleted outright or only retired.
     *
     * <p>{@code role_assignment.role_id} is {@code ON DELETE RESTRICT}, so the engine refuses a delete
     * while any assignment references the role — <b>including a revoked one</b>, because a revoked grant is
     * evidence of a decision and product principle 5 makes the record of what happened inviolable.
     *
     * <p>So a role that has ever been assigned is retired, never deleted. Retiring works because
     * {@code IdentityService.principal} joins {@code role} on {@code lifecycle_state = 'ACTIVE'}: a
     * DEPRECATED role stops granting immediately while its assignments stay readable as history.
     */
    public RoleRemoval roleRemoval(UUID tenantId, UUID roleId) throws SQLException {
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FILTER (WHERE revoked_at IS NULL), count(*) "
                                + "FROM role_assignment WHERE role_id = ?")) {
            statement.setObject(1, roleId);
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                long live = results.getLong(1);
                long ever = results.getLong(2);
                return new RoleRemoval(ever == 0, live, ever);
            }
        }
    }

    /**
     * Retires a role: it stops granting, and its history stays.
     *
     * <p>Not a delete. A delete of a role that was ever assigned would either be refused by the engine or,
     * with a cascade, would erase the record of who once held what — which is the audit question a
     * post-incident review asks first.
     */
    public boolean retireRole(UUID tenantId, UUID actorId, UUID roleId) throws SQLException {
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE role SET lifecycle_state = 'DEPRECATED', updated_at = now(), "
                                + "updated_by = ?, row_version = row_version + 1 "
                                + "WHERE id = ? AND lifecycle_state = 'ACTIVE'")) {
            statement.setObject(1, actorId);
            statement.setObject(2, roleId);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.eventBy(connection, actorId,
                        aspm.kernel.audit.contract.AuditEventType.ROLE_UPDATED, roleId, null,
                        java.util.Map.of("lifecycle_state", "DEPRECATED"));
            }
            connection.commit();
            return applied;
        }
    }

    /** Brings a retired role back. It grants again from the moment it is active. */
    public boolean restoreRole(UUID tenantId, UUID actorId, UUID roleId) throws SQLException {
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE role SET lifecycle_state = 'ACTIVE', updated_at = now(), "
                                + "updated_by = ?, row_version = row_version + 1 "
                                + "WHERE id = ? AND lifecycle_state = 'DEPRECATED'")) {
            statement.setObject(1, actorId);
            statement.setObject(2, roleId);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.eventBy(connection, actorId,
                        aspm.kernel.audit.contract.AuditEventType.ROLE_UPDATED, roleId, null,
                        java.util.Map.of("lifecycle_state", "ACTIVE"));
            }
            connection.commit();
            return applied;
        }
    }

    /**
     * Deletes a role that was never assigned.
     *
     * <p>The {@code NOT EXISTS} is inside the statement rather than in a preceding check, so a grant
     * created between the check and the delete cannot be orphaned. The engine's RESTRICT would catch it
     * too; both is the right number of controls on a destructive operation.
     */
    public boolean deleteRole(UUID tenantId, UUID roleId) throws SQLException {
        try (Connection connection = open(tenantId)) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement clear = connection.prepareStatement(
                        "DELETE FROM role_permission WHERE role_id = ? "
                                + "AND NOT EXISTS (SELECT 1 FROM role_assignment a "
                                + "                 WHERE a.role_id = ?)")) {
                    clear.setObject(1, roleId);
                    clear.setObject(2, roleId);
                    clear.executeUpdate();
                }
                int deleted;
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM role WHERE id = ? "
                                + "AND NOT EXISTS (SELECT 1 FROM role_assignment a "
                                + "                 WHERE a.role_id = ?)")) {
                    statement.setObject(1, roleId);
                    statement.setObject(2, roleId);
                    deleted = statement.executeUpdate();
                }
                if (deleted == 1) {
                    // The one destructive operation on this surface, and the only record that it
                    // happened. The role row is gone afterwards: without this event there is nothing
                    // left to ask what it was called.
                    audit.eventBy(connection, null,
                            aspm.kernel.audit.contract.AuditEventType.ROLE_UPDATED, roleId, null,
                            java.util.Map.of("lifecycle_state", "DELETED",
                                    "permitted_because", "the role was never assigned"));
                }
                connection.commit();
                return deleted == 1;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** Roles including retired ones, for the editor. {@link #roles} returns only active ones. */
    public List<RoleRow> allRoles(UUID tenantId) throws SQLException {
        return rolesWhere(tenantId, null);
    }

    public Optional<RoleRow> role(UUID tenantId, UUID roleId) throws SQLException {
        return rolesWhere(tenantId, roleId).stream().findFirst();
    }

    /**
     * The role code, normalised.
     *
     * <p>Upper snake case, because {@code uq_role__code} is case-sensitive and two roles differing only in
     * case would be two roles nobody can tell apart in a picker. Bounded at 64 characters.
     *
     * <p>The code is <b>immutable after creation</b>: {@code derived_from_template} and every audit entry
     * reference it, and a renamed code turns those references into dangling text. The label is what a
     * tenant renames, and the editor says so.
     */
    static String normaliseRoleCode(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.strip().toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    /** The tenant's password policy as stored. */
    public PasswordPolicy.Settings policy(UUID tenantId) throws SQLException {
        try (Connection connection = open(tenantId)) {
            return PasswordPolicy.load(connection);
        }
    }

    public long breachCorpusSize(UUID tenantId) throws SQLException {
        try (Connection connection = open(tenantId)) {
            return PasswordPolicy.corpusSize(connection);
        }
    }

    /**
     * Writes the tenant's password policy. Class E — configuration, so step-up and an idempotency key.
     *
     * <p>Every value is bounded by a CHECK in V015 rather than here, so a value this method would accept
     * and the engine would not is rejected by the engine. Validating in both places means two bounds that
     * drift; validating only here means a direct SQL write bypasses the bound entirely.
     *
     * @return the number of rows written, so a caller can tell a no-op from a write
     */
    public int updatePolicy(UUID tenantId, PasswordPolicy.Settings settings) throws SQLException {
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO password_policy (tenant_id, minimum_length, maximum_length, "
                                + "reuse_history, breach_check_at_set, "
                                + "breach_check_at_authentication, maximum_age_days, "
                                + "mfa_required_for_all, session_absolute_seconds, "
                                + "session_idle_seconds) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT (tenant_id) DO UPDATE SET "
                                + "minimum_length = excluded.minimum_length, "
                                + "maximum_length = excluded.maximum_length, "
                                + "reuse_history = excluded.reuse_history, "
                                + "breach_check_at_set = excluded.breach_check_at_set, "
                                + "breach_check_at_authentication = "
                                + "    excluded.breach_check_at_authentication, "
                                + "maximum_age_days = excluded.maximum_age_days, "
                                + "mfa_required_for_all = excluded.mfa_required_for_all, "
                                + "session_absolute_seconds = excluded.session_absolute_seconds, "
                                + "session_idle_seconds = excluded.session_idle_seconds")) {
            statement.setObject(1, tenantId);
            statement.setInt(2, settings.minimumLength());
            statement.setInt(3, settings.maximumLength());
            statement.setInt(4, settings.reuseHistory());
            statement.setBoolean(5, settings.breachCheckAtSet());
            statement.setBoolean(6, settings.breachCheckAtAuthentication());
            statement.setInt(7, settings.maximumAgeDays());
            statement.setBoolean(8, settings.mfaRequiredForAll());
            statement.setInt(9, settings.sessionAbsoluteSeconds());
            statement.setInt(10, settings.sessionIdleSeconds());
            int applied = statement.executeUpdate();
            if (applied > 0) {
                // Every authentication in the tenant is decided by these numbers, so a weakening of
                // them is the change an incident review looks for and the one nothing else records.
                audit.domainChangeBy(connection, null, "password_policy",
                        aspm.kernel.audit.contract.DomainChangeKind.UPDATED, null, null,
                        java.util.Map.of("minimum_length", Integer.valueOf(settings.minimumLength()),
                                "reuse_history", Integer.valueOf(settings.reuseHistory()),
                                "mfa_required_for_all", Boolean.valueOf(settings.mfaRequiredForAll()),
                                "maximum_age_days", Integer.valueOf(settings.maximumAgeDays()),
                                "session_absolute_seconds",
                                        Integer.valueOf(settings.sessionAbsoluteSeconds()),
                                "session_idle_seconds",
                                        Integer.valueOf(settings.sessionIdleSeconds())));
            }
            connection.commit();
            return applied;
        }
    }

    /** The display name of one principal, for a page heading. */
    public Optional<UserRow> user(UUID tenantId, UUID principalId) throws SQLException {
        return users(tenantId).stream().filter(u -> u.id().equals(principalId)).findFirst();
    }

    // ----------------------------------------------------------------------------------------------

    private Map<UUID, List<String>> roleCodesByPrincipal(UUID tenantId) throws SQLException {
        Map<UUID, List<String>> byPrincipal = new LinkedHashMap<>();
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT a.principal_id, r.code FROM role_assignment a "
                                + "  JOIN role r ON r.id = a.role_id "
                                + " WHERE a.revoked_at IS NULL "
                                + "   AND (a.expires_at IS NULL OR a.expires_at > now()) "
                                + " ORDER BY r.code");
                ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                byPrincipal.computeIfAbsent(results.getObject(1, UUID.class),
                        key -> new ArrayList<>()).add(results.getString(2));
            }
        }
        return byPrincipal;
    }

    private static Optional<PasswordHash.Stored> credentialOf(Connection connection, UUID principalId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT algorithm, memory_kib, iterations, parallelism, salt, hash "
                        + "FROM principal_credential WHERE principal_id = ? AND retired_at IS NULL")) {
            statement.setObject(1, principalId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PasswordHash.Stored(results.getString(1), results.getInt(2),
                        results.getInt(3), results.getInt(4), results.getBytes(5),
                        results.getBytes(6)));
            }
        }
    }

    private static void stampSetBy(Connection connection, UUID principalId, UUID actorId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE principal_credential SET set_by = ? "
                        + "WHERE principal_id = ? AND retired_at IS NULL")) {
            statement.setObject(1, actorId);
            statement.setObject(2, principalId);
            statement.executeUpdate();
        }
    }

    private static void clearMustChange(Connection connection, UUID principalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE principal SET must_change_password = false, row_version = row_version + 1 "
                        + "WHERE id = ?")) {
            statement.setObject(1, principalId);
            statement.executeUpdate();
        }
    }

    /**
     * Revokes a principal's sessions, optionally keeping one.
     *
     * @param keep the session to leave alive, or null to revoke all of them
     */
    private static int revokeOtherSessions(Connection connection, UUID principalId, UUID keep,
            String reason) throws SQLException {
        // The keep predicate is `id <> ?` with a coalesce rather than two SQL strings. Two statements
        // that differ by one clause is how one of them ends up missing the tenant predicate.
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE principal_session SET revoked_at = now(), revoked_reason = ? "
                        + "WHERE principal_id = ? AND revoked_at IS NULL "
                        + "AND id <> coalesce(?, '00000000-0000-0000-0000-000000000000'::uuid)")) {
            statement.setString(1, reason);
            statement.setObject(2, principalId);
            statement.setObject(3, keep);
            return statement.executeUpdate();
        }
    }

    private Connection open(UUID tenantId) throws SQLException {
        return TenantConnections.openForTenant(dataSource, tenantId);
    }
}
