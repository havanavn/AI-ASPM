package aspm.app.identity;

import aspm.app.runtime.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Issuing and revoking the signed-request credentials a pipeline uses. V037, ADR-004.
 *
 * <h2>The secret is disclosed exactly once</h2>
 *
 * <p>{@link #issue} returns it in its response and nowhere else: only {@code sha256(secret)} is
 * stored, so nothing — not this class, not an administrator, not a database dump reader — can recover
 * it afterwards. That is the point, and it is why the interface makes the reader acknowledge having
 * copied it rather than offering a "show again" that would have to be backed by storing the secret.
 *
 * <p>The consequence is stated rather than softened: a lost secret cannot be recovered, only replaced.
 * Rotation is issue-then-revoke, which is also the only way to rotate without an outage — the new key
 * works before the old one stops.
 *
 * <h2>What an administrator cannot do</h2>
 *
 * <p><b>They cannot give a key more than the principal behind it holds.</b> The permission list is
 * intersected at resolution ({@code ServiceCredentialResolver#load}), so this class does not need to
 * validate it and deliberately does not: a check here would be a second, weaker enforcement point
 * that drifts from the first (CON-PLT-009).
 *
 * <p><b>They cannot pin a scope outside their own.</b> The scope node is re-read against the caller's
 * closure expansion before the row is written — SEC-AUZ-017, an identifier that arrived from the
 * client is re-read before it is used. Without that, an administrator scoped to one division could
 * mint a credential that ingests for another.
 */
public final class ServiceCredentialAdmin {

    /**
     * Issuing and revoking a signing key are the two actions in this class that change who can reach
     * the platform, so both leave a chained record. DOC-14 lists credential lifecycle among the events
     * an access review reads, and a review that cannot answer "when did this key appear, and who made
     * it" is a review of the current state rather than of what happened.
     */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    /** The permission this whole surface is gated on. Restricted and step-up, per V038. */
    public static final String MANAGE = "sbm.credential.manage";

    /** One issued key as an administrator sees it. The secret is NOT here. */
    public record Row(String id, String keyId, String label, String principalId,
            String principalName, String scopeNodeId, String scopeNodeName, List<String> permissions,
            String expiresAt, String lastUsedAt, String createdAt, String revokedAt,
            String revokedReason) {
    }

    /** The one moment the secret exists outside the caller's pipeline. */
    public record Issued(String id, String keyId, String secret) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataSource dataSource;

    public ServiceCredentialAdmin(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /** Every key in the caller's scope, live and revoked. */
    public List<Row> list(Principal principal) throws SQLException {
        Set<UUID> scope = principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT c.id::text, c.key_id, c.label, c.principal_id::text, p.username,
                               c.scope_node_id::text, n.name, c.permissions,
                               to_char(c.expires_at, 'YYYY-MM-DD'),
                               to_char(c.last_used_at, 'YYYY-MM-DD HH24:MI'),
                               to_char(c.created_at, 'YYYY-MM-DD'),
                               to_char(c.revoked_at, 'YYYY-MM-DD'), c.revoked_reason
                          FROM service_credential c
                          LEFT JOIN principal p ON p.id = c.principal_id
                          LEFT JOIN org_node n ON n.id = c.scope_node_id
                         WHERE (c.scope_node_id IN (SELECT descendant_id FROM org_closure
                                                     WHERE ancestor_id = ANY (?))
                                -- A tenant-wide credential has no node, so the closure test alone
                                -- would hide it from everybody — including the administrator who
                                -- issued it. Visible to a caller whose own scope reaches every node,
                                -- which is the same condition under which it could be issued.
                                OR (c.scope_node_id IS NULL
                                    AND NOT EXISTS (SELECT 1 FROM org_node n
                                                     WHERE NOT EXISTS (
                                                       SELECT 1 FROM org_closure c2
                                                        WHERE c2.descendant_id = n.id
                                                          AND c2.ancestor_id = ANY (?)))))
                         ORDER BY c.revoked_at NULLS FIRST, c.created_at DESC
                        """)) {
            statement.setArray(1, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            statement.setArray(2, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    java.sql.Array permissions = r.getArray(8);
                    rows.add(new Row(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                            r.getString(5), r.getString(6), r.getString(7),
                            permissions == null ? List.of()
                                    : List.of((String[]) permissions.getArray()),
                            r.getString(9), r.getString(10), r.getString(11), r.getString(12),
                            r.getString(13)));
                }
            }
            // A read still opened a transaction, because open() does. Ended explicitly rather than
            // left for the pool to end on close: which of the two happens is a property of the pool
            // implementation, and a connection returned mid-transaction holds locks until it does.
            connection.rollback();
        }
        return List.copyOf(rows);
    }

    /**
     * Issues a key.
     *
     * <p>256 bits from a CSPRNG, hex encoded. Not a memorable string and not a UUID: a UUID is 122
     * bits with structure, and this value is the whole of the authentication.
     *
     * @param scopeNodeId the node the credential is pinned to. Re-read against the caller's scope
     *     before anything is written — an administrator cannot mint a credential for a division they
     *     cannot reach (SEC-AUZ-017).
     */
    public Optional<Issued> issue(Principal principal, String label, UUID principalId,
            UUID scopeNodeId, List<String> permissions, Integer expiresInDays) throws SQLException {
        Set<UUID> scope = principal.scopeNodeIds();
        if (scope.isEmpty() || label == null || label.isBlank() || principalId == null) {
            return Optional.empty();
        }
        byte[] material = new byte[32];
        RANDOM.nextBytes(material);
        String secret = HexFormat.of().formatHex(material);

        // The key identifier travels in clear in every Authorization header and appears in logs, so
        // it carries the label rather than being opaque: an operator reading an audit trail should be
        // able to say which pipeline acted without opening this table.
        String keyId = slug(label) + "-" + HexFormat.of().formatHex(shortRandom());

        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                if (scopeNodeId == null) {
                    // A tenant-wide credential, and the only case where the check is on the ISSUER
                    // rather than on the value. An administrator scoped to one division must not be
                    // able to mint a key that reaches the rest — so this is permitted only where
                    // their own expansion already reaches every node there is.
                    try (PreparedStatement reach = connection.prepareStatement("""
                            SELECT count(*) FROM org_node n
                             WHERE NOT EXISTS (SELECT 1 FROM org_closure c
                                                WHERE c.descendant_id = n.id
                                                  AND c.ancestor_id = ANY (?))
                            """)) {
                        reach.setArray(1, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
                        try (ResultSet r = reach.executeQuery()) {
                            if (!r.next() || r.getLong(1) > 0) {
                                connection.rollback();
                                return Optional.empty();
                            }
                        }
                    }
                } else {
                    // SEC-AUZ-017. The scope arrived from the client; it is re-read through the
                    // caller's own expansion before it is trusted, and a node outside it does not
                    // resolve.
                    try (PreparedStatement check = connection.prepareStatement(
                            "SELECT 1 FROM org_closure WHERE ancestor_id = ANY (?) "
                                    + "AND descendant_id = ?")) {
                        check.setArray(1,
                                connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
                        check.setObject(2, scopeNodeId);
                        try (ResultSet r = check.executeQuery()) {
                            if (!r.next()) {
                                connection.rollback();
                                return Optional.empty();
                            }
                        }
                    }
                }
                // The principal must exist and be reachable too. A credential acting as an identity
                // the issuer cannot see would be a way to borrow one.
                try (PreparedStatement exists = connection.prepareStatement(
                        "SELECT 1 FROM principal WHERE id = ? AND lifecycle_state = 'ACTIVE'")) {
                    exists.setObject(1, principalId);
                    try (ResultSet r = exists.executeQuery()) {
                        if (!r.next()) {
                            connection.rollback();
                            return Optional.empty();
                        }
                    }
                }

                UUID id;
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO service_credential (tenant_id, key_id, secret_hash, label,
                                                        principal_id, scope_node_id, permissions,
                                                        expires_at, created_by)
                        VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?,
                                CASE WHEN ?::int IS NULL THEN NULL
                                     ELSE now() + make_interval(days => ?::int) END, ?)
                        RETURNING id
                        """)) {
                    insert.setString(1, keyId);
                    insert.setBytes(2, sha256(secret));
                    insert.setString(3, label.strip());
                    insert.setObject(4, principalId);
                    insert.setObject(5, scopeNodeId);
                    insert.setArray(6, connection.createArrayOf("text",
                            permissions == null ? new String[0] : permissions.toArray()));
                    if (expiresInDays == null) {
                        insert.setNull(7, java.sql.Types.INTEGER);
                        insert.setNull(8, java.sql.Types.INTEGER);
                    } else {
                        insert.setInt(7, expiresInDays.intValue());
                        insert.setInt(8, expiresInDays.intValue());
                    }
                    insert.setObject(9, principal.principalId());
                    try (ResultSet keys = insert.executeQuery()) {
                        keys.next();
                        id = keys.getObject(1, UUID.class);
                    }
                }
                // The credential is the most consequential thing this class writes: it is a signing
                // key that acts as somebody's identity until it is revoked. The permissions and the
                // scope go in the payload; the SECRET does not, and neither does its hash — an audit
                // trail that carries a usable credential is a second copy of the credential store
                // (SEC-AUD-022 puts erasable detail in the payload, not material that grants access).
                audit.event(connection, principal,
                        aspm.kernel.audit.contract.AuditEventType.CONNECTOR_CREDENTIAL_ROTATED,
                        id, scopeNodeId, java.util.Map.of(
                                "key_id", keyId,
                                "label", label.strip(),
                                "acts_as", principalId.toString(),
                                "permissions", permissions == null
                                        ? java.util.List.of() : java.util.List.copyOf(permissions),
                                "scope_node_id", scopeNodeId == null
                                        ? "the whole tenant" : scopeNodeId.toString(),
                                "expires_in_days", expiresInDays == null
                                        ? "never" : expiresInDays.toString()));
                connection.commit();
                return Optional.of(new Issued(id.toString(), keyId, secret));
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Revokes a key.
     *
     * <p>Sets {@code revoked_at}; there is no DELETE grant on the table. What a key was permitted to
     * do while it existed is what an incident review reads, and a row that can be removed is a row
     * somebody removes on the day it matters (PP-5).
     */
    public boolean revoke(Principal principal, UUID id, String reason) throws SQLException {
        Set<UUID> scope = principal.scopeNodeIds();
        if (scope.isEmpty() || id == null) {
            return false;
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE service_credential
                           SET revoked_at = now(), revoked_by = ?,
                               revoked_reason = coalesce(nullif(?, ''), 'revoked by an administrator')
                         WHERE id = ? AND revoked_at IS NULL
                           AND (scope_node_id IN (SELECT descendant_id FROM org_closure
                                                   WHERE ancestor_id = ANY (?))
                                -- *** A TENANT-WIDE KEY HAS NO NODE. ***
                                -- The closure test alone matched nothing, so revoking one silently
                                -- did nothing and answered 404 — the worst possible outcome for a
                                -- control whose entire purpose is to stop a credential working. The
                                -- same NULL case was fixed in list() and missed here, which is what
                                -- happens when a nullable column is handled query by query.
                                -- Revocable by a caller whose own scope reaches every node, which is
                                -- the same condition under which it could have been issued.
                                OR (scope_node_id IS NULL
                                    AND NOT EXISTS (SELECT 1 FROM org_node n
                                                     WHERE NOT EXISTS (
                                                       SELECT 1 FROM org_closure c
                                                        WHERE c.descendant_id = n.id
                                                          AND c.ancestor_id = ANY (?)))))
                        """)) {
            statement.setObject(1, principal.principalId());
            statement.setString(2, reason == null ? "" : reason.strip());
            statement.setObject(3, id);
            statement.setArray(4, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            statement.setArray(5, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            if (statement.executeUpdate() != 1) {
                connection.rollback();
                return false;
            }
            // Recorded only where the update matched. A revocation that changed nothing is a 404 to
            // the caller, and an audit line for it would say a key was revoked that was not.
            audit.event(connection, principal,
                    aspm.kernel.audit.contract.AuditEventType.OBJECT_GRANT_REVOKED, id, null,
                    java.util.Map.of("reason", reason == null || reason.isBlank()
                            ? "revoked by an administrator" : reason.strip()));
            connection.commit();
            return true;
        }
    }

    // ----------------------------------------------------------------------------------------------

    private static byte[] shortRandom() {
        byte[] out = new byte[4];
        RANDOM.nextBytes(out);
        return out;
    }

    /** A label to a key-safe prefix. Bounded, because the key identifier is stored and indexed. */
    private static String slug(String label) {
        String slug = label.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {
            slug = "key";
        }
        return slug.length() > 40 ? slug.substring(0, 40) : slug;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        // A transaction, opened by the shared door rather than here, so that the tenant setting can
        // be LOCAL to it — and so that this class is not the one place that has to remember.
        //
        // It was `set_config(..., false)` — session scope — which survives the connection's return to
        // the pool and into the next borrower's request. That is the disclosure mechanism OPS-DEP-010
        // and SEC-TEN-007 name, and ResourceEndpoint carried a comment saying so while this class did
        // the opposite. It was not exploitable in this deployment, because PGSimpleDataSource is not a
        // pool, but it held only while that stayed true — a property nothing checked.
        //
        // The transaction also makes the audit write and the change it describes atomic, which
        // CON-PLT-021 requires and which auto-commit quietly made impossible.
        return aspm.app.persistence.TenantConnections.open(dataSource, principal);
    }
}
