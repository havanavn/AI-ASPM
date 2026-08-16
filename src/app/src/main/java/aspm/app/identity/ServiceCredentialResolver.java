package aspm.app.identity;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import aspm.app.runtime.PrincipalResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

/**
 * Signed-request authentication for pipelines. ADR-004, V037.
 *
 * <h2>The scheme</h2>
 *
 * <pre>
 *   canonical = method \n path \n content_sha256 \n timestamp \n nonce
 *   signature = HMAC-SHA256(secret, canonical)
 *
 *   Authorization: ASPM-HMAC-SHA256 key=&lt;key&gt;, ts=&lt;unix&gt;, nonce=&lt;hex&gt;, signature=&lt;hex&gt;
 *   x-aspm-content-sha256: &lt;hex sha256 of the raw body&gt;
 * </pre>
 *
 * <p>ADR-004 forbids bearer API keys, and the reason is not stylistic: a bearer token is replayable
 * from anywhere it leaks — a CI log, a shell history, a proxy that records headers, a crash dump. A
 * signature is not, because <b>the secret never crosses the wire</b>, the nonce is single-use, and
 * the timestamp is bounded.
 *
 * <h2>Four things are inside the signature, and each closes a specific attack</h2>
 *
 * <ul>
 *   <li><b>method and path</b> — a captured submission cannot be replayed against a different
 *       operation.
 *   <li><b>content hash</b> — nor against different content. The hash is signed here and the BODY is
 *       verified against it by the dispatcher, because a resolver only sees headers; a scheme that
 *       signed the header without anybody checking the body would be a signature over a promise.
 *   <li><b>timestamp</b> — bounded by {@link #CLOCK_SKEW_SECONDS}, so a capture is useless after a
 *       few minutes even if the nonce table were emptied.
 *   <li><b>nonce</b> — single-use, enforced by a PRIMARY KEY rather than by a check in this class.
 *       Two concurrent replays race on the INSERT and exactly one wins; a read-then-write check
 *       would let both through under precisely the concurrency an attacker would choose.
 * </ul>
 *
 * <h2>What it is not</h2>
 *
 * <p>Not sender-constrained in the mTLS or DPoP sense. Possession of the secret is still sufficient,
 * so a secret exfiltrated from a CI runner works until it is revoked. ADR-004 ranks this below both
 * and permits it for CI; this note exists so nobody reads the class name and assumes parity.
 *
 * <h2>Failure is uniform</h2>
 *
 * <p>Every rejection returns empty. Not a distinguishing message, not a different status: an unknown
 * key, a revoked key, a bad signature, a stale timestamp and a replayed nonce are all
 * indistinguishable to the caller. Telling an attacker which of the five they got right is telling
 * them where to spend their next attempt.
 */
public final class ServiceCredentialResolver implements PrincipalResolver {

    /** The header naming the body's digest. Signed here; verified against the body by the dispatcher. */
    public static final String CONTENT_HASH_HEADER = "x-aspm-content-sha256";

    private static final String SCHEME = "ASPM-HMAC-SHA256";

    /**
     * How far a client's clock may differ from the platform's.
     *
     * <p>Five minutes each way. Long enough that a CI runner with unsynchronised time still works;
     * short enough that a captured request is dead before anybody has finished reading the log it
     * leaked into. It is also the retention window for {@code service_request_nonce} — a nonce older
     * than this cannot be replayed anyway, so keeping it would grow a table that protects nothing.
     */
    public static final long CLOCK_SKEW_SECONDS = 300;

    private final DataSource dataSource;
    private final UUID tenantId;

    public ServiceCredentialResolver(DataSource dataSource, UUID tenantId) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
        this.tenantId = Objects.requireNonNull(tenantId, "a tenant is required");
    }

    @Override
    public String description() {
        return "Signed requests for service credentials: HMAC-SHA256 over method, path, body digest, "
                + "timestamp and a single-use nonce, with a " + CLOCK_SKEW_SECONDS + "-second clock "
                + "window (ADR-004 permits this for CI and forbids bearer API keys). NOT "
                + "sender-constrained: a leaked secret works until revoked.";
    }

    @Override
    public Optional<Principal> resolve(Map<String, String> headers) {
        String authorization = headers.get("authorization");
        if (authorization == null || !authorization.startsWith(SCHEME + " ")) {
            return Optional.empty();
        }
        Map<String, String> parts = parameters(authorization.substring(SCHEME.length() + 1));
        String keyId = parts.get("key");
        String timestamp = parts.get("ts");
        String nonce = parts.get("nonce");
        String signature = parts.get("signature");
        String contentHash = headers.getOrDefault(CONTENT_HASH_HEADER, "");
        if (keyId == null || timestamp == null || nonce == null || signature == null
                || contentHash.isBlank()) {
            return Optional.empty();
        }
        // A nonce is a fixed-width opaque value. Bounding it stops an attacker filling the nonce
        // table with one enormous row per request, which would be a denial of service on the control
        // rather than on the endpoint.
        if (nonce.length() < 8 || nonce.length() > 128) {
            return Optional.empty();
        }

        long skew;
        try {
            skew = Math.abs(System.currentTimeMillis() / 1000L - Long.parseLong(timestamp));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (skew > CLOCK_SKEW_SECONDS) {
            return Optional.empty();
        }

        String method = headers.getOrDefault(":method", "POST");
        String path = headers.getOrDefault(":path", "");
        String canonical = String.join("\n", method, path, contentHash, timestamp, nonce);

        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                Credential credential = load(connection, keyId);
                if (credential == null) {
                    connection.rollback();
                    return Optional.empty();
                }
                if (!matches(credential.secretHash(), canonical, signature)) {
                    // RECORDED, then committed rather than rolled back. This is the failure mode
                    // PRD-SBM-024 exists for: a secret rotated in the platform and not updated in the
                    // pipeline fails one hundred per cent of the time, forever, and reaches no handler
                    // — so without recording it here the dashboard would show "last success three weeks
                    // ago, 0 failures", which reads as "nobody has pushed" (PP-1).
                    //
                    // The reason is a fixed platform string. Nothing from the request is stored: the
                    // caller is unauthenticated until this check passes, and an administrator's screen
                    // is the last place to render text an unauthenticated party supplied.
                    //
                    // One UPDATE on a row this method already had to read. An attacker who knows a key
                    // identifier can drive that write, which is worth knowing and is not worth trading
                    // the signal for — the alternative is a stale secret nobody ever sees.
                    recordAuthFailure(connection, credential.id(),
                            "the request signature did not verify — the pipeline's secret may be stale "
                            + "or the request may have been altered in transit");
                    connection.commit();
                    return Optional.empty();
                }
                // The nonce is consumed AFTER the signature verifies. Consuming it first would let an
                // unauthenticated caller burn arbitrary nonces, and a legitimate client that happened
                // to pick the same value would then be refused.
                if (!consumeNonce(connection, keyId, nonce)) {
                    // A replayed nonce is either an attack or a pipeline reusing a value it should
                    // generate per request. Both are worth seeing, and they look identical from here.
                    recordAuthFailure(connection, credential.id(),
                            "the request nonce had already been used — a replay, or a pipeline that is "
                            + "not generating a fresh nonce for each request");
                    connection.commit();
                    return Optional.empty();
                }
                touch(connection, credential.id());
                connection.commit();
                return Optional.of(new Principal(tenantId, credential.principalId(),
                        credential.permissions(), credential.scope(), false, true, false,
                        credential.id()));
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            // Refused rather than admitted. A resolver that fell open on a database error would make
            // an outage into an authentication bypass.
            return Optional.empty();
        }
    }

    // ----------------------------------------------------------------------------------------------

    private record Credential(UUID id, UUID principalId, byte[] secretHash, Set<String> permissions,
            Set<UUID> scope) {
    }

    /**
     * Loads a live key, with its permissions already intersected against its principal's.
     *
     * <p>The intersection is the point: a key cannot exercise more than the identity behind it, so
     * revoking that identity's role revokes the key's reach without anybody remembering this table
     * exists. The scope is the credential's pinned node expanded through the closure, which is the
     * same expansion every scoped query composes.
     */
    private Credential load(Connection connection, String keyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.id, c.principal_id, c.secret_hash, c.permissions, c.scope_node_id
                  FROM service_credential c
                 WHERE c.key_id = ?
                   AND c.revoked_at IS NULL
                   AND (c.expires_at IS NULL OR c.expires_at > now())
                """)) {
            statement.setString(1, keyId);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                UUID id = r.getObject(1, UUID.class);
                UUID principalId = r.getObject(2, UUID.class);
                byte[] hash = r.getBytes(3);
                java.sql.Array granted = r.getArray(4);
                UUID scopeNode = r.getObject(5, UUID.class);

                Set<String> declared = new LinkedHashSet<>();
                if (granted != null) {
                    declared.addAll(java.util.List.of((String[]) granted.getArray()));
                }
                Set<String> held = new LinkedHashSet<>();
                try (PreparedStatement roles = connection.prepareStatement("""
                        SELECT DISTINCT rp.permission_code
                          FROM role_assignment ra
                          JOIN role_permission rp ON rp.role_id = ra.role_id
                         WHERE ra.principal_id = ? AND ra.revoked_at IS NULL
                        """)) {
                    roles.setObject(1, principalId);
                    try (ResultSet permissions = roles.executeQuery()) {
                        while (permissions.next()) {
                            held.add(permissions.getString(1));
                        }
                    }
                }

                return new Credential(id, principalId, hash, effective(declared, held),
                        scope(connection, scopeNode));
            }
        }
    }

    /**
     * What a credential may actually exercise: what it declares, intersected with what its principal
     * holds.
     *
     * <p>The intersection is the point — a key cannot exercise more than the identity behind it, so
     * revoking that identity's role revokes the key's reach without anybody remembering the credential
     * table exists.
     *
     * <p><b>An empty declaration grants nothing.</b> It used to mean "everything the principal holds",
     * which reads as convenience and behaves as a fail-open default: the one shape a caller can produce
     * without deciding anything was also the widest one, so a credential minted by a script that forgot
     * the field carried its owner's entire authority. The interface now refuses to issue without a
     * selection, which means the only rows that can still reach here empty are the ones nobody chose
     * the permissions for — exactly the case that must not be broad.
     *
     * <p>Package-private and pure so the rule is asserted directly. Reached only through {@code load},
     * which has a database behind it and therefore no test.
     */
    static Set<String> effective(Set<String> declared, Set<String> held) {
        if (declared.isEmpty()) {
            return Set.of();
        }
        Set<String> effective = new LinkedHashSet<>(held);
        effective.retainAll(declared);
        return Set.copyOf(effective);
    }

    /**
     * Expands the credential's pinned node through the closure.
     *
     * <p>A NULL pin means the whole tenant (V041). Expanded here rather than stored as a list of
     * roots, because a node added later must be included without anybody remembering to reissue the
     * key. Still bounded by the row-level policy, so "whole tenant" can never mean more than one
     * tenant.
     */
    private static Set<UUID> scope(Connection connection, UUID scopeNode) throws SQLException {
        Set<UUID> scope = new LinkedHashSet<>();
        String sql = scopeNode == null
                ? "SELECT id FROM org_node"
                : "SELECT descendant_id FROM org_closure WHERE ancestor_id = ?";
        try (PreparedStatement closure = connection.prepareStatement(sql)) {
            if (scopeNode != null) {
                closure.setObject(1, scopeNode);
            }
            try (ResultSet nodes = closure.executeQuery()) {
                while (nodes.next()) {
                    scope.add(nodes.getObject(1, UUID.class));
                }
            }
        }
        if (scope.isEmpty() && scopeNode != null) {
            scope.add(scopeNode);
        }
        return Set.copyOf(scope);
    }

    /**
     * Verifies the signature by recomputing it, in constant time.
     *
     * <p>The stored value is {@code sha256(secret)}, so the secret itself is not recoverable from the
     * table — but HMAC needs the secret, not its digest. So the signature is computed with the DIGEST
     * as the key, and the client computes it the same way from the secret it holds: the client's key
     * material is the secret, the server's is a value derived from it that is useless for anything
     * else. A dump of this table yields signing keys, which is why the table is tenant-isolated and
     * carries no SELECT grant beyond app_runtime and the verifier.
     */
    private static boolean matches(byte[] secretHash, String canonical, String supplied) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretHash, "HmacSHA256"));
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] presented = HexFormat.of().parseHex(supplied.strip().toLowerCase(
                    java.util.Locale.ROOT));
            // Constant time. A byte-by-byte comparison that returns early leaks, through timing, how
            // many leading bytes were right — which turns a 2^256 search into 32 searches of 2^8.
            return MessageDigest.isEqual(expected, presented);
        } catch (Exception e) {
            // A malformed hex signature lands here and is refused, indistinguishably from a wrong one.
            return false;
        }
    }

    /** Consumes the nonce. The PRIMARY KEY is the control; a duplicate is a refused replay. */
    private static boolean consumeNonce(Connection connection, String keyId, String nonce)
            throws SQLException {
        sweep(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO service_request_nonce (tenant_id, key_id, nonce) "
                        + "VALUES (current_tenant_id(), ?, ?) ON CONFLICT DO NOTHING")) {
            statement.setString(1, keyId);
            statement.setString(2, nonce);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Removes nonces older than the accepted clock window.
     *
     * <p>On the request path rather than on a timer, because there is no scheduler in this tier and a
     * table that only grows is a slower outage than a slightly slower request. The delete is bounded
     * by the same window the signature check uses, so it can never remove a nonce that could still be
     * replayed.
     */
    private static void sweep(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM service_request_nonce WHERE seen_at < now() - make_interval(secs => ?)")) {
            statement.setDouble(1, (double) CLOCK_SKEW_SECONDS * 2);
            statement.executeUpdate();
        }
    }

    private static void touch(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE service_credential SET last_used_at = now() WHERE id = ?")) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    /**
     * Records a submission refused before it reached a handler.
     *
     * <p>`last_used_at` is deliberately NOT advanced. It has always meant "a signature verified on this
     * key", and a request whose signature did not verify has not used the credential — moving it would
     * make a stale secret look like an active integration, which is the fault this recording exists to
     * expose.
     */
    /**
     * A rejected signed request, on the credential and in the trail.
     *
     * <p>The counters on the row are what an administrator sees beside the key; the event is what an
     * investigation reads. A run of these against one key identifier is either an attacker with a
     * stale secret or a pipeline nobody has fixed, and both are only visible over time — which the
     * counter, being a number that is reset on the next success, cannot show.
     *
     * <p><b>Not written to the audit chain</b>, for the same structural reason as the human sign-in
     * path — see {@code IdentityService#record}. This runs while deciding whether the caller is
     * anybody, so there is no established tenant context for the chain writer to require, and
     * {@code SEC-TEN-005} fails closed rather than guessing one.
     */
    private static void recordAuthFailure(Connection connection, UUID id, String reason)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE service_credential "
                        + "   SET failure_count = failure_count + 1, "
                        + "       consecutive_failures = consecutive_failures + 1, "
                        + "       last_failure_at = now(), last_failure_reason = ? "
                        + " WHERE id = ?")) {
            statement.setString(1, reason);
            statement.setObject(2, id);
            statement.executeUpdate();
        }
    }

    /** {@code key=abc, ts=1, nonce=x, signature=y} to a map. Unknown parameters are ignored. */
    private static Map<String, String> parameters(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : raw.split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                out.put(pair.substring(0, equals).strip().toLowerCase(java.util.Locale.ROOT),
                        pair.substring(equals + 1).strip());
            }
        }
        return out;
    }

    /** The hex digest of a body, as the client computes it for {@link #CONTENT_HASH_HEADER}. */
    public static String contentHash(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private Connection open() throws SQLException {
        return TenantConnections.openForTenant(dataSource, tenantId);
    }
}
