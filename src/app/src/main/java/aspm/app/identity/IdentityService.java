package aspm.app.identity;

import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Sign-in, sessions, and principal resolution. ADR-059, {@code SEC-SEC-005} through {@code -016}.
 *
 * <h2>The order of checks, and why one of them looks wrong</h2>
 *
 * <p>An unknown username is verified against a <b>dummy hash</b> before the failure is returned. That
 * looks like waste and it is the control: without it, an unknown identifier returns in microseconds and a
 * known one takes the full Argon2 cost, so the response time <b>is</b> a user-enumeration oracle. The
 * platform's own threat model calls enumeration a named surface, and {@code SEC-SEC-016} requires the
 * reset path not to disclose existence — the sign-in path has the same obligation for the same reason.
 *
 * <h2>Throttling that cannot be turned into a lockout</h2>
 *
 * <p>{@code SEC-SEC-005}: "progressive delay and risk-based challenge <b>rather than account disable</b>",
 * because an attacker who can disable a named account has a denial-of-service against the platform and the
 * first account they would disable is the one that could stop them. So the delay is computed from recent
 * attempts and applied as a wait; there is no {@code locked_until} column anywhere, and V015 records its
 * absence as the control.
 */
public final class IdentityService {

    /** What a sign-in attempt produced. */
    public sealed interface SignIn {

        /** Password accepted, second factor still required. The session may reach the challenge only. */
        record SecondFactorRequired(String sessionToken, boolean enrolmentNeeded) implements SignIn {
        }

        /** Fully authenticated. */
        record Authenticated(String sessionToken, boolean mustChangePassword) implements SignIn {
        }

        /**
         * Rejected. <b>One shape for every cause</b>: unknown identifier, wrong password and suspended
         * account are indistinguishable to the caller, because distinguishing them tells an attacker
         * which identifiers exist.
         */
        record Rejected(long retryAfterSeconds) implements SignIn {
        }
    }

    /** A resolved session. */
    public record Session(UUID id, UUID principalId, UUID tenantId, String factorState,
            boolean mfaEnrolled, boolean mustChangePassword, Instant stepUpAt) {

        /**
         * Whether the second factor was re-presented recently enough for a class C or class E operation.
         *
         * <p>Evaluated here rather than in SQL so the window is one value in one place. A predicate in the
         * session query would put it in a string, where the next reader of {@code STEP_UP_WINDOW} would
         * change the constant and not the query.
         */
        public boolean stepUpFresh() {
            return stepUpAt != null && stepUpAt.isAfter(Instant.now().minus(STEP_UP_WINDOW));
        }
    }

    private static final Duration CHALLENGE_WINDOW = Duration.ofMinutes(10);

    /**
     * How long a step-up lasts. Five minutes.
     *
     * <p>Short on purpose. Step-up exists so that possession of a live session is not sufficient for a
     * credential reset or a configuration change; a window long enough to cover a working session would
     * restore exactly the property it removes. Long enough that an administrator resetting three
     * passwords is challenged once rather than three times, because a challenge on every click is a
     * challenge people defeat by keeping the code generator open beside the screen.
     */
    public static final Duration STEP_UP_WINDOW = Duration.ofMinutes(5);
    /** A hash to verify against when the identifier is unknown. See the class note. */
    private static final PasswordHash.Stored DUMMY = PasswordHash.hash("not-a-real-password".toCharArray());

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public IdentityService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Step one: identifier and password.
     *
     * @param tenantId the tenant. Resolved from the host or a single-tenant deployment default, never
     *     from a form field — {@code SEC-TEN-004} forbids a tenant derived from the request
     */
    public SignIn signIn(UUID tenantId, String identifier, char[] password, String sourceAddress,
            String userAgent) throws SQLException {
        Objects.requireNonNull(identifier, "an identifier is required");
        String presented = identifier.strip().toLowerCase(Locale.ROOT);

        try (Connection connection = open(tenantId)) {
            connection.setAutoCommit(false);
            try {
                long delay = throttleDelaySeconds(connection, presented);
                if (delay > 0) {
                    record(connection, presented, null, "THROTTLED", "PASSWORD", sourceAddress,
                            userAgent);
                    connection.commit();
                    return new SignIn.Rejected(delay);
                }

                Optional<Row> found = lookup(connection, presented);

                // The dummy verification. Unknown identifiers cost what known ones cost.
                if (found.isEmpty()) {
                    PasswordHash.verify(password, DUMMY);
                    record(connection, presented, null, "UNKNOWN_IDENTIFIER", "PASSWORD",
                            sourceAddress, userAgent);
                    connection.commit();
                    return new SignIn.Rejected(0);
                }

                Row principal = found.orElseThrow();
                if (!"ACTIVE".equals(principal.lifecycleState())
                        && !"INVITED".equals(principal.lifecycleState())) {
                    PasswordHash.verify(password, DUMMY);
                    record(connection, presented, principal.id(), "SUSPENDED", "PASSWORD",
                            sourceAddress, userAgent);
                    connection.commit();
                    return new SignIn.Rejected(0);
                }

                Optional<PasswordHash.Stored> credential = credential(connection, principal.id());
                if (credential.isEmpty() || !PasswordHash.verify(password, credential.orElseThrow())) {
                    record(connection, presented, principal.id(), "BAD_CREDENTIAL", "PASSWORD",
                            sourceAddress, userAgent);
                    connection.commit();
                    return new SignIn.Rejected(0);
                }

                // SEC-SEC-006, the authentication half: a credential that was fine when set and has
                // since appeared in a corpus is still a compromised credential. The sign-in succeeds
                // and forces a change rather than failing, because failing would lock a user out of the
                // only surface where they could fix it.
                PasswordPolicy.Settings settings = PasswordPolicy.load(connection);
                // The must-change flag is persisted and read back from the SESSION, not returned from
                // here: the sign-in outcome on this path is always "second factor required", and a flag
                // carried in that outcome would be one the challenge step has to remember and forward.
                // Session.mustChangePassword is the single place the interface reads it.
                if (settings.breachCheckAtAuthentication()
                        && PasswordPolicy.isBreached(connection, new String(password))) {
                    record(connection, presented, principal.id(), "BREACHED_CREDENTIAL", "PASSWORD",
                            sourceAddress, userAgent);
                    try (PreparedStatement flag = connection.prepareStatement(
                            "UPDATE principal SET must_change_password = true, "
                                    + "row_version = row_version + 1 WHERE id = ?")) {
                        flag.setObject(1, principal.id());
                        flag.executeUpdate();
                    }
                }

                // Re-hash if the stored cost is below the current floor. This is the mechanism that lets
                // the cost be raised at all.
                if (credential.orElseThrow().belowCurrentCost()) {
                    replaceCredential(connection, principal.id(), password, "COST_UPGRADE");
                }

                boolean enrolled = principal.mfaEnrolledAt() != null;
                String token = createSession(connection, principal.id(), tenantId,
                        "PASSWORD_ONLY", settings, sourceAddress, userAgent, CHALLENGE_WINDOW);
                record(connection, presented, principal.id(), "SUCCESS", "PASSWORD", sourceAddress,
                        userAgent);
                connection.commit();
                // Enrolment is required either way. ADR-059 makes it a state of the principal, so an
                // un-enrolled principal is sent to enrolment rather than to the challenge.
                return new SignIn.SecondFactorRequired(token, !enrolled);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Step two: the second factor.
     *
     * <p>On success the session token is <b>regenerated</b>. {@code SEC-SEC-009} requires regeneration on
     * privilege change, and completing the second factor is the privilege change on this path — a session
     * that keeps its identifier across it is a session an attacker who captured the first token still
     * holds after the user authenticates properly.
     */
    public Optional<String> completeSecondFactor(UUID tenantId, String sessionToken, String code,
            String sourceAddress, String userAgent) throws SQLException {
        try (Connection connection = open(tenantId)) {
            connection.setAutoCommit(false);
            try {
                Optional<Session> session = resolveSession(connection, sessionToken);
                if (session.isEmpty() || !"PASSWORD_ONLY".equals(session.orElseThrow().factorState())) {
                    connection.commit();
                    return Optional.empty();
                }
                UUID principalId = session.orElseThrow().principalId();

                String secret;
                Long lastStep;
                UUID enrolmentId;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, secret_ciphertext, last_accepted_step FROM mfa_enrolment "
                                + "WHERE principal_id = ? AND retired_at IS NULL")) {
                    statement.setObject(1, principalId);
                    try (ResultSet results = statement.executeQuery()) {
                        if (!results.next()) {
                            connection.commit();
                            return Optional.empty();
                        }
                        enrolmentId = results.getObject(1, UUID.class);
                        secret = new String(results.getBytes(2), java.nio.charset.StandardCharsets.UTF_8);
                        long step = results.getLong(3);
                        lastStep = results.wasNull() ? null : Long.valueOf(step);
                    }
                }

                Totp.Result verdict = Totp.verify(secret, code, Instant.now(), lastStep);
                if (!verdict.valid()) {
                    record(connection, principalId.toString(), principalId, "BAD_SECOND_FACTOR",
                            "TOTP", sourceAddress, userAgent);
                    connection.commit();
                    return Optional.empty();
                }

                // RFC 6238 §5.2: the accepted step is consumed.
                try (PreparedStatement consume = connection.prepareStatement(
                        "UPDATE mfa_enrolment SET last_accepted_step = ?, "
                                + "confirmed_at = coalesce(confirmed_at, now()) WHERE id = ?")) {
                    consume.setLong(1, verdict.acceptedStep());
                    consume.setObject(2, enrolmentId);
                    consume.executeUpdate();
                }

                PasswordPolicy.Settings settings = PasswordPolicy.load(connection);
                revokeSession(connection, sessionToken, "REGENERATED_ON_PRIVILEGE_CHANGE");
                String fresh = createSession(connection, principalId, tenantId,
                        "FULLY_AUTHENTICATED", settings, sourceAddress, userAgent,
                        Duration.ofSeconds(settings.sessionAbsoluteSeconds()));
                try (PreparedStatement seen = connection.prepareStatement(
                        "UPDATE principal SET last_authenticated_at = now(), "
                                + "row_version = row_version + 1 WHERE id = ?")) {
                    seen.setObject(1, principalId);
                    seen.executeUpdate();
                }
                record(connection, principalId.toString(), principalId, "SUCCESS", "TOTP",
                        sourceAddress, userAgent);
                connection.commit();
                return Optional.of(fresh);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** Begins enrolment: a secret the caller shows once, not yet confirmed. */
    public String beginEnrolment(UUID tenantId, UUID principalId) throws SQLException {
        String secret = Totp.newSecret();
        try (Connection connection = open(tenantId)) {
            connection.setAutoCommit(false);
            try (PreparedStatement retire = connection.prepareStatement(
                    "UPDATE mfa_enrolment SET retired_at = now() "
                            + "WHERE principal_id = ? AND retired_at IS NULL AND confirmed_at IS NULL")) {
                retire.setObject(1, principalId);
                retire.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO mfa_enrolment (tenant_id, principal_id, secret_ciphertext, "
                            + "secret_key_ref) VALUES (?, ?, ?, ?)")) {
                insert.setObject(1, tenantId);
                insert.setObject(2, principalId);
                // ⚠ Stored as bytes, NOT encrypted. ADR-059 states the secret is encrypted at rest under
                // the tenant key, and no key management exists yet (OQ-026 is open). Recorded here as a
                // gap rather than a claim: the column name says ciphertext and the content is not.
                insert.setBytes(3, secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                insert.setString(4, "PLAINTEXT_PENDING_OQ_026");
                insert.executeUpdate();
            }
            // A second factor being re-enrolled is the step an account takeover takes once it has
            // the password, so this belongs in the chain — and cannot be written from here.
            // SessionPrincipalResolver resolves a principal only for a FULLY_AUTHENTICATED session,
            // and enrolment by definition happens before that state is reached, so there is no tenant
            // context bound and the chain writer fails closed. Measured, not assumed: with the event
            // in place every enrolment returned 500. Same open question as the sign-in path.
            connection.commit();
        }
        return secret;
    }

    /** Confirms enrolment with a code from the authenticator, and marks the principal enrolled. */
    public boolean confirmEnrolment(UUID tenantId, UUID principalId, String code) throws SQLException {
        try (Connection connection = open(tenantId)) {
            connection.setAutoCommit(false);
            try {
                String secret;
                UUID enrolmentId;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, secret_ciphertext FROM mfa_enrolment "
                                + "WHERE principal_id = ? AND retired_at IS NULL "
                                + "ORDER BY created_at DESC LIMIT 1")) {
                    statement.setObject(1, principalId);
                    try (ResultSet results = statement.executeQuery()) {
                        if (!results.next()) {
                            connection.commit();
                            return false;
                        }
                        enrolmentId = results.getObject(1, UUID.class);
                        secret = new String(results.getBytes(2), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
                Totp.Result verdict = Totp.verify(secret, code, Instant.now(), null);
                if (!verdict.valid()) {
                    connection.commit();
                    return false;
                }
                try (PreparedStatement confirm = connection.prepareStatement(
                        "UPDATE mfa_enrolment SET confirmed_at = now(), last_accepted_step = ? "
                                + "WHERE id = ?")) {
                    confirm.setLong(1, verdict.acceptedStep());
                    confirm.setObject(2, enrolmentId);
                    confirm.executeUpdate();
                }
                try (PreparedStatement enrol = connection.prepareStatement(
                        "UPDATE principal SET mfa_enrolled_at = now(), row_version = row_version + 1 "
                                + "WHERE id = ?")) {
                    enrol.setObject(1, principalId);
                    enrol.executeUpdate();
                }
                // Not chained, for the reason given in beginEnrolment.
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * How long a live session has left, on each of its two clocks.
     *
     * @param idleSecondsLeft seconds until the idle limit bites if nothing further happens. Working
     *     postpones this one.
     * @param absoluteSecondsLeft seconds until the absolute limit, which nothing postpones —
     *     {@code SEC-SEC-010} caps it at 12 hours so a session cannot outlive a working day.
     */
    public record Window(long idleSecondsLeft, long absoluteSecondsLeft) {
    }

    /**
     * Reads both remaining windows, so an interface can warn before a session goes rather than after.
     *
     * <p>Computed in the database from the same columns the resolver checks, rather than from a clock in
     * the application. Two clocks that disagree would produce a countdown that reaches zero while the
     * session still works, or worse, the reverse.
     *
     * <p>The tenant is passed rather than defaulted. `principal_session` is row-level isolated and the
     * connection helper has no notion of "no tenant" — a first version passed null and failed on the
     * first call, which is the shape of every tenant-context mistake in this codebase (CON-DAT-013).
     */
    public Window sessionWindow(UUID tenantId, UUID sessionId) throws SQLException {
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT greatest(0, floor(extract(epoch FROM
                                 (last_seen_at + (idle_timeout_seconds || ' seconds')::interval)
                                 - now()))),
                               greatest(0, floor(extract(epoch FROM absolute_expires_at - now())))
                          FROM principal_session
                         WHERE id = ?
                        """)) {
            statement.setObject(1, sessionId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return new Window(0L, 0L);
                }
                return new Window((long) results.getDouble(1), (long) results.getDouble(2));
            }
        }
    }

    /** Resolves a session token to a session, enforcing both lifetime limits. */
    public Optional<Session> session(UUID tenantId, String token) throws SQLException {
        try (Connection connection = open(tenantId)) {
            connection.setAutoCommit(false);
            Optional<Session> session = resolveSession(connection, token);
            session.ifPresent(s -> touch(connection, token));
            connection.commit();
            return session;
        }
    }

    /**
     * The principal a session belongs to, with permissions and scope resolved from role assignments.
     *
     * <p>Resolved per request rather than cached in the session. {@code SEC-SEC-011} requires revocation
     * to take effect within 60 seconds "including cached authorization state", and a permission set
     * copied into a session at sign-in is cached authorization state that a revocation does not reach.
     */
    public Optional<Principal> principal(UUID tenantId, Session session) throws SQLException {
        try (Connection connection = open(tenantId)) {
            Set<String> permissions = new LinkedHashSet<>();
            Set<UUID> scope = new LinkedHashSet<>();
            boolean tenantWide = false;

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT rp.permission_code, a.scope_mode, a.scope_node_id "
                            + "  FROM role_assignment a "
                            + "  JOIN role r ON r.id = a.role_id AND r.lifecycle_state = 'ACTIVE' "
                            + "  JOIN role_permission rp ON rp.role_id = r.id "
                            + " WHERE a.principal_id = ? AND a.revoked_at IS NULL "
                            + "   AND (a.expires_at IS NULL OR a.expires_at > now())")) {
                statement.setObject(1, session.principalId());
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        permissions.add(results.getString(1));
                        if ("TENANT".equals(results.getString(2))) {
                            tenantWide = true;
                        } else {
                            UUID node = results.getObject(3, UUID.class);
                            if (node != null) {
                                scope.add(node);
                            }
                        }
                    }
                }
            }

            // A tenant-wide assignment reaches every root, expanded through the closure table rather
            // than expressed as a wildcard: product principle 4 makes scope derived, and a wildcard is
            // an assertion.
            if (tenantWide) {
                try (PreparedStatement roots = connection.prepareStatement(
                        "SELECT id FROM org_node WHERE parent_id IS NULL")) {
                    try (ResultSet results = roots.executeQuery()) {
                        while (results.next()) {
                            scope.add(results.getObject(1, UUID.class));
                        }
                    }
                }
            }

            // Both flags come from the SESSION, and both were literal `false` here until V016.
            //
            // stepUpAuthenticated being false made the dispatcher's step-up gate unsatisfiable: every
            // class C and class E operation answered 401 to every human caller, and no surface existed
            // that could clear the condition. The gate was closed and had no key.
            boolean stepUp = session.stepUpFresh();
            boolean mustChange = session.mustChangePassword();

            if (permissions.isEmpty()) {
                // SEC-AUZ-014 denies on an empty grant rather than allowing over nothing. A principal
                // with no role is authenticated and authorized for nothing, which is correct and is not
                // the same as unauthenticated.
                return Optional.of(new Principal(tenantId, session.principalId(), Set.of(), Set.of(),
                        stepUp, false, mustChange));
            }
            return Optional.of(new Principal(tenantId, session.principalId(),
                    Set.copyOf(permissions), Set.copyOf(scope), stepUp, false, mustChange));
        }
    }

    /**
     * Re-presents the second factor on an existing session. V016, ADR-036 classes C and E.
     *
     * <p>A TOTP code, verified the same way and with the same replay refusal as the sign-in challenge —
     * {@code last_accepted_step} is shared, so a code used to sign in cannot immediately be reused to
     * elevate. That is deliberate and it is a usability cost worth naming: a caller who signs in and
     * immediately opens an administrative page waits for the next 30-second step. The alternative is a
     * step-up that a captured code satisfies twice.
     *
     * @return true if the session is now elevated
     */
    public boolean recordStepUp(UUID tenantId, String token, String code, String sourceAddress,
            String userAgent) throws SQLException {
        try (Connection connection = open(tenantId)) {
            connection.setAutoCommit(false);
            try {
                Optional<Session> session = resolveSession(connection, token);
                // Only a fully authenticated session may elevate. A PASSWORD_ONLY session presenting a
                // valid code is completing its FIRST factor challenge, not stepping up, and treating the
                // two as the same call would let the challenge be skipped by posting here instead.
                if (session.isEmpty()
                        || !"FULLY_AUTHENTICATED".equals(session.orElseThrow().factorState())) {
                    connection.commit();
                    return false;
                }
                UUID principalId = session.orElseThrow().principalId();

                String secret;
                Long lastStep;
                UUID enrolmentId;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, secret_ciphertext, last_accepted_step FROM mfa_enrolment "
                                + "WHERE principal_id = ? AND retired_at IS NULL "
                                + "AND confirmed_at IS NOT NULL")) {
                    statement.setObject(1, principalId);
                    try (ResultSet results = statement.executeQuery()) {
                        if (!results.next()) {
                            connection.commit();
                            return false;
                        }
                        enrolmentId = results.getObject(1, UUID.class);
                        secret = new String(results.getBytes(2),
                                java.nio.charset.StandardCharsets.UTF_8);
                        long step = results.getLong(3);
                        lastStep = results.wasNull() ? null : Long.valueOf(step);
                    }
                }

                Totp.Result verdict = Totp.verify(secret, code, Instant.now(), lastStep);
                if (!verdict.valid()) {
                    record(connection, principalId.toString(), principalId, "BAD_SECOND_FACTOR",
                            "STEP_UP", sourceAddress, userAgent);
                    connection.commit();
                    return false;
                }
                try (PreparedStatement consume = connection.prepareStatement(
                        "UPDATE mfa_enrolment SET last_accepted_step = ? WHERE id = ?")) {
                    consume.setLong(1, verdict.acceptedStep());
                    consume.setObject(2, enrolmentId);
                    consume.executeUpdate();
                }
                try (PreparedStatement stamp = connection.prepareStatement(
                        "UPDATE principal_session SET step_up_at = now() WHERE token_hash = ?")) {
                    stamp.setBytes(1, PasswordHash.tokenHash(token));
                    stamp.executeUpdate();
                }
                record(connection, principalId.toString(), principalId, "SUCCESS", "STEP_UP",
                        sourceAddress, userAgent);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Revokes one of the caller's own sessions by identifier. {@code SEC-SEC-012}.
     *
     * <p>The {@code principal_id} predicate is the object-level authorization check, and it is in the
     * WHERE clause rather than in a preceding read. Loading the session, comparing the owner in Java and
     * then updating by identifier is the shape that becomes a cross-account revocation the moment
     * somebody moves the comparison — which is the defect class this product exists to find.
     *
     * @return true if a session belonging to this principal was revoked
     */
    public boolean revokeOwnSession(UUID tenantId, UUID principalId, UUID sessionId)
            throws SQLException {
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE principal_session SET revoked_at = now(), "
                                + "revoked_reason = 'REVOKED_BY_OWNER' "
                                + "WHERE id = ? AND principal_id = ? AND revoked_at IS NULL")) {
            statement.setObject(1, sessionId);
            statement.setObject(2, principalId);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.eventBy(connection, principalId,
                        aspm.kernel.audit.contract.AuditEventType.SESSION_REVOKED, sessionId, null,
                        java.util.Map.of("reason", "REVOKED_BY_OWNER"));
            }
            connection.commit();
            return applied;
        }
    }

    public void revoke(UUID tenantId, String token, String reason) throws SQLException {
        try (Connection connection = open(tenantId)) {
            connection.setAutoCommit(false);
            revokeSession(connection, token, reason);
            connection.commit();
        }
    }

    // ----------------------------------------------------------------------------------------------

    private record Row(UUID id, String lifecycleState, Instant mfaEnrolledAt,
            boolean mustChangePassword) {
    }

    private static Optional<Row> lookup(Connection connection, String presented) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, lifecycle_state, mfa_enrolled_at, must_change_password FROM principal "
                        + "WHERE (username = ? OR email = ?) AND kind = 'HUMAN' "
                        + "AND lifecycle_state <> 'DEPROVISIONED'")) {
            statement.setString(1, presented);
            statement.setString(2, presented);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                var enrolled = results.getObject(3, java.time.OffsetDateTime.class);
                return Optional.of(new Row(results.getObject(1, UUID.class), results.getString(2),
                        enrolled == null ? null : enrolled.toInstant(), results.getBoolean(4)));
            }
        }
    }

    private static Optional<PasswordHash.Stored> credential(Connection connection, UUID principalId)
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

    /** Sets a credential, retiring the previous one. Retained for the reuse check of the policy. */
    public static void replaceCredential(Connection connection, UUID principalId, char[] password,
            String reason) throws SQLException {
        try (PreparedStatement retire = connection.prepareStatement(
                "UPDATE principal_credential SET retired_at = now(), retired_reason = ? "
                        + "WHERE principal_id = ? AND retired_at IS NULL")) {
            retire.setString(1, reason);
            retire.setObject(2, principalId);
            retire.executeUpdate();
        }
        PasswordHash.Stored stored = PasswordHash.hash(password);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO principal_credential (tenant_id, principal_id, algorithm, memory_kib, "
                        + "iterations, parallelism, salt, hash) "
                        + "VALUES ((SELECT tenant_id FROM principal WHERE id = ?), ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, principalId);
            insert.setObject(2, principalId);
            insert.setString(3, stored.algorithm());
            insert.setInt(4, stored.memoryKib());
            insert.setInt(5, stored.iterations());
            insert.setInt(6, stored.parallelism());
            insert.setBytes(7, stored.salt());
            insert.setBytes(8, stored.hash());
            insert.executeUpdate();
        }
    }

    /**
     * The progressive delay of {@code SEC-SEC-005}.
     *
     * <p>Computed from recent failures against the presented identifier, and capped. Capped because an
     * uncapped delay becomes the lockout the requirement forbids: an attacker who can drive the delay to
     * an hour has disabled the account without any column saying so.
     */
    private static long throttleDelaySeconds(Connection connection, String presented)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM authentication_attempt "
                        + "WHERE presented_identifier = ? AND outcome <> 'SUCCESS' "
                        + "AND occurred_at > now() - interval '15 minutes'")) {
            statement.setString(1, presented);
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                long failures = results.getLong(1);
                if (failures < 5) {
                    return 0;
                }
                return Math.min(30, (failures - 4) * 2);
            }
        }
    }

    /**
     * One authentication attempt.
     *
     * <p><b>Written here and NOT to the audit chain, and the reason is structural rather than an
     * omission.</b> {@code ChainedAuditWriter} requires an established {@link
     * aspm.kernel.tenantcontext.contract.TenantContextHolder} binding, and {@code SEC-TEN-004} allows
     * that binding to be established only "from an authenticated principal or a scope-pinned service
     * credential". A sign-in attempt has neither by definition — deciding whether the caller is a
     * principal is what it does — so an event written from here fails closed with
     * {@code MissingTenantContextException}, which is {@code SEC-TEN-005} working as specified.
     *
     * <p>It was tried, and every sign-in became a 500 until it was taken out. DOC-14 does list
     * {@code auth.succeeded} and {@code auth.failed} among the events the chain carries, so this is a
     * genuine gap between two requirements rather than a decision either of them makes: closing it
     * needs a pre-authentication establishment route, which is a new {@code EstablishedFrom} value and
     * a change to what {@code SEC-TEN-004} permits — a new requirement ID, not an edit to this method.
     * Recorded as an open question rather than worked around.
     *
     * <p>Until then {@code authentication_attempt} is the record: it is what the throttle reads and it
     * carries the same outcome, factor, address and identifier. What it lacks is the hash chain, so it
     * is evidence that can be altered without detection.
     */
    private void record(Connection connection, String presented, UUID principalId,
            String outcome, String factor, String sourceAddress, String userAgent)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO authentication_attempt (tenant_id, presented_identifier, principal_id, "
                        + "outcome, factor, source_address, source_user_agent) "
                        + "VALUES (current_tenant_id(), ?, ?, ?, ?, ?::inet, ?)")) {
            statement.setString(1, presented);
            statement.setObject(2, principalId);
            statement.setString(3, outcome);
            statement.setString(4, factor);
            statement.setString(5, sourceAddress);
            statement.setString(6, userAgent == null ? null
                    : userAgent.substring(0, Math.min(512, userAgent.length())));
            statement.executeUpdate();
        }
    }

    private static String createSession(Connection connection, UUID principalId, UUID tenantId,
            String factorState, PasswordPolicy.Settings settings, String sourceAddress,
            String userAgent, Duration absolute) throws SQLException {
        // 32 bytes — 256 bits, comfortably above SEC-SEC-009's 128 — and encoding nothing.
        String token = PasswordHash.randomToken(32);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO principal_session (tenant_id, principal_id, token_hash, factor_state, "
                        + "absolute_expires_at, idle_timeout_seconds, source_address, "
                        + "source_user_agent) VALUES (?, ?, ?, ?, now() + ?::interval, ?, ?::inet, ?)")) {
            statement.setObject(1, tenantId);
            statement.setObject(2, principalId);
            statement.setBytes(3, PasswordHash.tokenHash(token));
            statement.setString(4, factorState);
            statement.setString(5, absolute.toSeconds() + " seconds");
            statement.setInt(6, settings.sessionIdleSeconds());
            statement.setString(7, sourceAddress);
            statement.setString(8, userAgent == null ? null
                    : userAgent.substring(0, Math.min(512, userAgent.length())));
            statement.executeUpdate();
        }
        return token;
    }

    private static Optional<Session> resolveSession(Connection connection, String token)
            throws SQLException {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT s.id, s.principal_id, s.tenant_id, s.factor_state, "
                        + "       p.mfa_enrolled_at IS NOT NULL, p.must_change_password, s.step_up_at "
                        + "  FROM principal_session s "
                        + "  JOIN principal p ON p.id = s.principal_id "
                        + " WHERE s.token_hash = ? AND s.revoked_at IS NULL "
                        // Both limits, on every read. SEC-SEC-010 requires absolute AND idle, and a
                        // session that only checks absolute is a session left open on a shared machine.
                        + "   AND s.absolute_expires_at > now() "
                        + "   AND s.last_seen_at > now() - (s.idle_timeout_seconds || ' seconds')::interval "
                        + "   AND p.lifecycle_state = 'ACTIVE'")) {
            statement.setBytes(1, PasswordHash.tokenHash(token));
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                var steppedUp = results.getObject(7, java.time.OffsetDateTime.class);
                return Optional.of(new Session(results.getObject(1, UUID.class),
                        results.getObject(2, UUID.class), results.getObject(3, UUID.class),
                        results.getString(4), results.getBoolean(5), results.getBoolean(6),
                        steppedUp == null ? null : steppedUp.toInstant()));
            }
        }
    }

    private static void touch(Connection connection, String token) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE principal_session SET last_seen_at = now() WHERE token_hash = ?")) {
            statement.setBytes(1, PasswordHash.tokenHash(token));
            statement.executeUpdate();
        } catch (SQLException e) {
            // A failed touch means the idle window is not extended, which fails closed. Logged and not
            // propagated: refusing the request because a timestamp did not update would be worse.
            System.getLogger("aspm.identity").log(System.Logger.Level.WARNING,
                    "session last_seen update failed; the idle window was not extended");
        }
    }

    private static void revokeSession(Connection connection, String token, String reason)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE principal_session SET revoked_at = now(), revoked_reason = ? "
                        + "WHERE token_hash = ? AND revoked_at IS NULL")) {
            statement.setString(1, reason);
            statement.setBytes(2, PasswordHash.tokenHash(token));
            statement.executeUpdate();
        }
    }

    /**
     * The tenant is established transaction-locally ({@code OPS-DEP-010}, {@code SEC-TEN-007}).
     *
     * <p>This used to be session-scoped, and the reason given was true as far as it went: several
     * methods here run more than one transaction on their connection, and a {@code SET LOCAL} value is
     * discarded at the first commit. It is no longer a reason, because
     * {@link aspm.app.persistence.TenantConnections} re-establishes the setting as the first statement
     * of the next transaction — so the multi-transaction shape is preserved without a value that
     * outlives the work.
     *
     * <p>The tenant identifier rather than a principal, because this class is what resolves the
     * principal.
     */
    private Connection open(UUID tenantId) throws SQLException {
        return aspm.app.persistence.TenantConnections.openForTenant(dataSource, tenantId);
    }

    /**
     * The username, for the authenticator entry label.
     *
     * <p>A separate lookup rather than a field on {@link Session}, because the session is resolved on
     * every request and carrying a display string through it would make the hot path read a column it
     * does not need.
     */
    public Optional<String> usernameOf(UUID tenantId, UUID principalId) throws SQLException {
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT username FROM principal WHERE id = ?")) {
            statement.setObject(1, principalId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(results.getString(1)) : Optional.empty();
            }
        }
    }

    /** Sessions a principal can see and terminate. {@code SEC-SEC-012}. */
    public List<Map<String, Object>> ownSessions(UUID tenantId, UUID principalId) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = open(tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, factor_state, created_at, last_seen_at, absolute_expires_at, "
                                + "host(source_address), source_user_agent FROM principal_session "
                                + "WHERE principal_id = ? AND revoked_at IS NULL "
                                + "ORDER BY created_at DESC")) {
            statement.setObject(1, principalId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", results.getObject(1, UUID.class).toString());
                    row.put("factor_state", results.getString(2));
                    row.put("created_at", String.valueOf(results.getObject(3)));
                    row.put("last_seen_at", String.valueOf(results.getObject(4)));
                    row.put("expires_at", String.valueOf(results.getObject(5)));
                    row.put("source_address", results.getString(6));
                    row.put("user_agent", results.getString(7));
                    rows.add(row);
                }
            }
        }
        return List.copyOf(rows);
    }

}
