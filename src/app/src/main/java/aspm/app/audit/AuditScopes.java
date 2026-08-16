package aspm.app.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Where in the organization an audited action happened.
 *
 * <p>{@code SEC-AUZ-028} answers "was this principal authorized for this event, at the time" by
 * testing containment against the event's recorded ancestor path. That test is only as good as the
 * node the event was recorded against, so the node is looked up from the record being written rather
 * than passed in by the caller — an emitter that supplied its own could supply a different one, and
 * DOC-14's historical authorization would then be answering about a scope nobody wrote to.
 *
 * <h2>Why every method can return null, and why that is not a fallback</h2>
 *
 * <p>An unscoped event is a truthful statement: this deployment holds findings and a request whose
 * {@code scope_node_id} is null, and {@code tr_finding__immutable_scope} refuses to backfill them —
 * correctly, because the scope descriptor records where the record was <em>at the time</em> and that
 * is not reconstructible. Recording such an event against a guessed node would be a false statement
 * about the organization, which is worse than recording that it has no place in the tree.
 *
 * <p>The closure check is the second half of the same care. {@link AuditTrail} refuses a node with no
 * closure rows — it cannot build an ancestor path for it — so a node that is not in the tree is
 * reported here as no node at all, rather than thrown from inside a write that has already happened.
 */
public final class AuditScopes {

    private AuditScopes() {
    }

    /** The organizational scope recorded on a finding, or null where it has none in the tree. */
    public static UUID ofFinding(Connection connection, UUID findingId) throws SQLException {
        return lookup(connection, "finding", findingId);
    }

    /** The organizational scope recorded on an assessment request. */
    public static UUID ofRequest(Connection connection, UUID requestId) throws SQLException {
        return lookup(connection, "assessment_request", requestId);
    }

    /**
     * The organization node an asset belongs to.
     *
     * <p>{@code owning_node_id}, not the embedded scope descriptor: the descriptor is immutable
     * ({@code CON-DAT-009}) and records where the asset was when it was first placed, so an asset that
     * has changed owner would file its events under a division that no longer holds it.
     */
    public static UUID ofAsset(Connection connection, UUID assetId) throws SQLException {
        return placed(connection,
                "SELECT a.owning_node_id FROM asset a WHERE a.id = ?", assetId);
    }

    /** An organization node is its own scope, where it is in the closure. */
    public static UUID ofNode(Connection connection, UUID nodeId) throws SQLException {
        if (nodeId == null) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT descendant_id FROM org_closure WHERE descendant_id = ? LIMIT 1")) {
            statement.setObject(1, nodeId);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? r.getObject(1, UUID.class) : null;
            }
        }
    }

    private static UUID lookup(Connection connection, String table, UUID id) throws SQLException {
        // The table name is a compile-time constant from the two callers above and never a parameter;
        // an identifier cannot be bound, and taking one from anywhere else would be concatenation into
        // SQL of exactly the kind this product exists to find.
        return placed(connection, "SELECT t.scope_node_id FROM " + table + " t WHERE t.id = ?", id);
    }

    private static UUID placed(Connection connection, String sql, UUID id) throws SQLException {
        if (id == null) {
            return null;
        }
        UUID node;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet r = statement.executeQuery()) {
                node = r.next() ? r.getObject(1, UUID.class) : null;
            }
        }
        return ofNode(connection, node);
    }
}
