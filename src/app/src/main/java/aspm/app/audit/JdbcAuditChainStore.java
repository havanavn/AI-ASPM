package aspm.app.audit;

import aspm.kernel.audit.application.AuditChainStore;
import aspm.kernel.audit.contract.AuditEnvelope;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * The audit chain, in PostgreSQL.
 *
 * <p><b>Why this class did not exist.</b> The chain had a domain layer, an application writer, a
 * verifier and a test double, and no way to reach a database: the {@code infrastructure} package held
 * a {@code package-info.java} and nothing else. {@code INSERT INTO audit_event} appeared once in the
 * whole repository, in a kernel test. The tables were created by V011, partitioned by month through
 * 2026, indexed, and empty — a running deployment with seven hundred findings, two hundred assessment
 * requests and a dozen issued credentials had recorded no audit event of any kind.
 *
 * <p>That is {@code PP-5} — "the record of what happened is inviolable" — with nothing to be
 * inviolable about, and ADR-034's erasure-survivable hash chain protecting an empty table.
 *
 * <h2>Why it lives in the application tier</h2>

 * <p>{@code CON-PLT-036} confines JDBC to the tenant-context gate for everything the kernel and the
 * modules contain, and an architecture test enforces it on bytecode — it rejected this class when it
 * was first written into {@code aspm.kernel.audit.infrastructure}, which was the rule doing its job.
 * The kernel keeps the chain: the canonical form, the hash, the verifier, the writer and the
 * persistence port. Only the port's PostgreSQL implementation is here, beside the paths that already
 * hold a connection and are already inside a transaction.
 *
 * <h2>The connection is the caller's</h2>
 *
 * <p>Constructed around a live {@link Connection} rather than a {@code DataSource}, because
 * {@code CON-PLT-021} requires the event and the change it describes to commit or fail together. A
 * store that opened its own connection would write the audit event in a second transaction: an action
 * could then succeed with no record, or leave a record of an action that rolled back. Both are worse
 * than no audit at all, because both are trusted.
 *
 * <p>It follows that this class is created per transaction and is not thread-safe. That is the point:
 * a shared instance would have to choose a connection, and the only correct connection is the one the
 * caller is already inside.
 */
public final class JdbcAuditChainStore implements AuditChainStore {

    private final Connection connection;
    private final java.util.Map<String, Object> payload;

    /**
     * @param payload the same map the writer canonicalized, kept so the stored copy can be JSON
     */
    public JdbcAuditChainStore(Connection connection, java.util.Map<String, Object> payload) {
        this.connection = Objects.requireNonNull(connection, "the caller's connection is required");
        // A null-tolerant copy, not Map.copyOf. A null value in an audit payload is a real
        // statement — the field was empty when this happened — and CanonicalPayload renders it as
        // such. Map.copyOf throws on one, which turned "this org node has no external reference"
        // into a failed create.
        this.payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(
                Objects.requireNonNull(payload, "use Map.of() for none")));
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code SELECT … FOR UPDATE} on the tenant's head row, which is the per-tenant serialization
     * {@code SEC-AUD-014} names: two concurrent writers would otherwise read the same {@code
     * last_sequence} and produce either a duplicate sequence or a forked chain, and a forked chain is
     * indistinguishable from tampering after the fact.
     *
     * <p>The head row is created on first use rather than at tenant provisioning, so a tenant whose
     * provisioning predates this class still chains correctly from genesis.
     */
    @Override
    public ChainHead lockHead(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "a tenant is required");
        try {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO audit_chain_head (tenant_id, last_sequence, last_chain_hash) "
                            // -1, which the schema's own check constraint allows for exactly this
                            // reason: nextSequence() is lastSequence + 1, so a tenant's first event is
                            // sequence 0. Seeding with 0 would silently skip it and leave a gap that
                            // reads as a deleted event.
                            + "VALUES (?, -1, ?) ON CONFLICT (tenant_id) DO NOTHING")) {
                insert.setObject(1, tenantId.value());
                insert.setBytes(2, aspm.kernel.audit.domain.ChainHasher.genesis(tenantId));
                insert.executeUpdate();
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT last_sequence, last_chain_hash FROM audit_chain_head "
                            + "WHERE tenant_id = ? FOR UPDATE")) {
                select.setObject(1, tenantId.value());
                try (ResultSet head = select.executeQuery()) {
                    if (!head.next()) {
                        // The insert above ran in this transaction, so the row exists unless the
                        // row-level policy hides it — which would mean this write is being attempted
                        // for a tenant other than the established one. Fail rather than chain onto a
                        // genesis that is not this tenant's.
                        throw new IllegalStateException("no audit chain head is visible for tenant "
                                + tenantId.value() + "; the tenant context and the write disagree");
                    }
                    return new ChainHead(head.getLong(1), head.getBytes(2));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the audit chain head could not be locked", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Three statements, one transaction, no return value: the event, its payload, and the advanced
     * head. The head update is conditional on the sequence it advanced from, so a lock that was somehow
     * not held shows up as a refusal rather than as a silently overwritten head.
     */
    @Override
    public void append(AuditEnvelope envelope, int canonicalVersion, byte[] payloadHash,
            byte[] prevChainHash, byte[] chainHash, byte[] canonicalPayload) {
        Objects.requireNonNull(envelope, "an envelope is required");
        try {
            try (PreparedStatement event = connection.prepareStatement("""
                    INSERT INTO audit_event (
                        id, tenant_id, sequence, event_type, occurred_at,
                        actor_id, actor_type, on_behalf_of_id, automation_rule_id, break_glass_ref,
                        object_kind, object_id, outcome, denial_reason,
                        scope_node_id, scope_ancestor_path, scope_hierarchy_ver,
                        canonical_version, payload_hash, prev_chain_hash, chain_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                int i = 0;
                event.setObject(++i, envelope.eventId());
                event.setObject(++i, envelope.tenantId().value());
                event.setLong(++i, envelope.sequence());
                event.setString(++i, envelope.eventType());
                event.setObject(++i, envelope.occurredAt().atOffset(java.time.ZoneOffset.UTC));
                event.setObject(++i, envelope.actorId() == null ? null : envelope.actorId().value());
                event.setString(++i, envelope.actorType().name());
                event.setObject(++i,
                        envelope.onBehalfOfId() == null ? null : envelope.onBehalfOfId().value());
                event.setObject(++i, envelope.automationRuleId());
                event.setObject(++i, envelope.breakGlassRef());
                event.setString(++i, envelope.objectKind());
                event.setObject(++i, envelope.objectId());
                event.setString(++i, envelope.outcome().name());
                event.setString(++i, envelope.denialReason());
                event.setObject(++i, envelope.scope().nodeId() == null
                        ? null : envelope.scope().nodeId().value());
                event.setArray(++i, connection.createArrayOf("uuid",
                        envelope.scope().ancestorPath().stream()
                                .map(OrgNodeId::value).toArray(UUID[]::new)));
                event.setLong(++i, envelope.scope().hierarchyVersion());
                event.setInt(++i, canonicalVersion);
                event.setBytes(++i, payloadHash);
                event.setBytes(++i, prevChainHash);
                event.setBytes(++i, chainHash);
                event.executeUpdate();
            }

            // The payload is a separate row because ADR-034 covers the payload HASH in the chain, so
            // erasing the payload leaves the chain verifiable. A payload column on the event row would
            // make erasure a rewrite of a chained row, which SEC-AUD-013 forbids outright.
            //
            // *** STORED AS JSON, HASHED AS THE CANONICAL FORM. ***
            //
            // These are two different serializations of one map and the difference is deliberate.
            // CanonicalPayload produces a length-prefixed form — `key:12:value;` — which exists to be
            // hashed: it is unambiguous, order-independent and has no escaping to disagree about. It
            // is not JSON, and the column is `jsonb`. Writing the canonical bytes into it failed
            // outright the first time this ran: `invalid input syntax for type json, Token
            // "created_at" is invalid`.
            //
            // So the queryable copy is JSON and the integrity copy is the hash over the canonical
            // form. Nothing is weakened by that — the chain covers the hash, ADR-034's erasure
            // property is untouched, and a verifier re-canonicalizes the stored payload to check it.
            // What it does mean is that JSON's type system is now in the loop: a value that does not
            // survive a round trip through JSON unchanged will not re-canonicalize to the same bytes.
            // Payloads here are strings, integers, booleans and lists of strings, all of which do.
            if (canonicalPayload != null && canonicalPayload.length > 0) {
                try (PreparedStatement payload = connection.prepareStatement(
                        "INSERT INTO audit_event_payload (event_id, tenant_id, occurred_at, payload) "
                                + "VALUES (?, ?, ?, ?::jsonb)")) {
                    payload.setObject(1, envelope.eventId());
                    payload.setObject(2, envelope.tenantId().value());
                    payload.setObject(3, envelope.occurredAt().atOffset(java.time.ZoneOffset.UTC));
                    payload.setString(4, aspm.app.runtime.Json.write(this.payload));
                    payload.executeUpdate();
                }
            }

            try (PreparedStatement advance = connection.prepareStatement(
                    "UPDATE audit_chain_head SET last_sequence = ?, last_chain_hash = ?, "
                            + "updated_at = now() WHERE tenant_id = ? AND last_sequence = ?")) {
                advance.setLong(1, envelope.sequence());
                advance.setBytes(2, chainHash);
                advance.setObject(3, envelope.tenantId().value());
                advance.setLong(4, envelope.sequence() - 1);
                if (advance.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "the audit chain head moved under this write: sequence "
                                    + envelope.sequence() + " no longer follows the head it was "
                                    + "computed from, so the chain would fork (SEC-AUD-014)");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the audit event could not be appended", e);
        }
    }

}
