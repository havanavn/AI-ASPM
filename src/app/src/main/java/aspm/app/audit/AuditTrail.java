package aspm.app.audit;

import aspm.app.runtime.Principal;
import aspm.kernel.audit.application.ChainedAuditWriter;
import aspm.kernel.audit.contract.ActorType;
import aspm.kernel.audit.contract.AuditEventType;
import aspm.kernel.audit.contract.AuditOutcome;
import aspm.kernel.audit.contract.AuditRecorder;
import aspm.kernel.audit.contract.AuditScope;
import aspm.kernel.audit.contract.DomainChangeKind;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.PrincipalId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How the application tier records an audited action.
 *
 * <p>The kernel has the chain: a canonical serializer, a hasher, a verifier, a writer that locks the
 * tenant's head and links each event to the last. What it did not have was a caller. This class is the
 * caller, and it is deliberately one line at each site — a control that takes six lines to invoke is a
 * control that gets skipped on the seventh path.
 *
 * <h2>The caller's transaction, always</h2>
 *
 * <p>Every method takes the {@link Connection} the change is being made on. {@code CON-PLT-021} makes
 * audit the platform's one deliberate availability-for-integrity trade: if the event cannot be
 * written, the action does not happen. That only holds while both are in one transaction, so there is
 * no overload that opens its own — a convenience method without a connection parameter would be a
 * quiet way to get an action with no record of it.
 *
 * <h2>What is NOT recorded here</h2>
 *
 * <p>Reads. DOC-14 distinguishes per-object read auditing (annotation class C, sensitive retrieval)
 * from ordinary collection reads, and recording the latter would produce a trail whose volume is the
 * platform's read traffic and whose signal is nil. Class C paths record through
 * {@link #sensitiveRead}; class A paths do not record at all, and that is a decision rather than an
 * omission.
 */
public final class AuditTrail {

    private final Clock clock;

    public AuditTrail(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "a clock is required");
    }

    /**
     * A domain state change on an aggregate — {@code asset.created}, {@code org_node.updated}.
     *
     * <p>The composite code comes from {@link DomainChangeKind}, which is the route DOC-14 §3 defines
     * for per-aggregate events. The aggregate name is the physical table, which is the one name that
     * cannot drift from the thing being written.
     */
    public void domainChange(Connection connection, Principal principal, String aggregate,
            DomainChangeKind kind, UUID objectId, UUID scopeNode, Map<String, Object> payload) {
        record(connection, principal, kind.codeFor(aggregate), AuditOutcome.SUCCESS, null,
                aggregate, objectId, scopeNode, payload);
    }

    /** A catalogued event that is not a per-aggregate change — a credential issued, a session revoked. */
    public void event(Connection connection, Principal principal, AuditEventType type,
            UUID objectId, UUID scopeNode, Map<String, Object> payload) {
        record(connection, principal, type.code(), AuditOutcome.SUCCESS, null,
                type.code().substring(0, type.code().indexOf('.')), objectId, scopeNode, payload);
    }

    /**
     * A retrieval that DOC-14 requires to leave a trace: exploit material, a test credential, a
     * recovered secret, evidence. Annotation class C.
     */
    public void sensitiveRead(Connection connection, Principal principal, AuditEventType type,
            String objectKind, UUID objectId, UUID scopeNode) {
        record(connection, principal, type.code(), AuditOutcome.SUCCESS, null,
                objectKind, objectId, scopeNode, Map.of());
    }

    /**
     * A refusal.
     *
     * <p>Recorded because {@code SEC-AUZ-*} treats a denial as the interesting half: a caller probing
     * for what it may not reach produces a run of these and nothing else, and a trail that only holds
     * successes cannot show it. The reason is stored at full fidelity here and is never the reason
     * returned to the client, which stays indistinguishable from absence ({@code PRD-API-036}).
     */
    public void denied(Connection connection, Principal principal, String objectKind, UUID objectId,
            UUID scopeNode, String reason) {
        record(connection, principal, AuditEventType.AUTHZ_DENIED.code(), AuditOutcome.DENIED, reason,
                objectKind, objectId, scopeNode, Map.of());
    }

    /**
     * The same, where the caller holds the actor's identifier rather than a {@link Principal}.
     *
     * <p>The identity and access surfaces are the callers: they run before a principal is resolved, or
     * they act on one that is not the actor. Giving them a fabricated {@code Principal} to satisfy the
     * signature would put empty permissions and a false step-up flag into an object other code reads,
     * so the two fields the trail actually needs — who, and of what kind — are passed instead.
     */
    public void domainChangeBy(Connection connection, UUID actorId, String aggregate,
            DomainChangeKind kind, UUID objectId, UUID scopeNode, Map<String, Object> payload) {
        record(connection, actorId == null ? null : new PrincipalId(actorId), ActorType.USER,
                kind.codeFor(aggregate), AuditOutcome.SUCCESS, null, aggregate, objectId, scopeNode,
                payload);
    }

    /** A catalogued event recorded against an actor identifier. See {@link #domainChangeBy}. */
    public void eventBy(Connection connection, UUID actorId, AuditEventType type, UUID objectId,
            UUID scopeNode, Map<String, Object> payload) {
        eventBy(connection, actorId, type, AuditOutcome.SUCCESS, objectId, scopeNode, payload);
    }

    /**
     * The same, where the event is a refusal rather than a success.
     *
     * <p>The outcome is a separate column from the type because a query for "what failed" is a
     * different query from "what happened", and an authentication failure recorded with outcome
     * {@code SUCCESS} — which is what a single-outcome API produces — makes the first one answer
     * nothing. {@code SEC-AUD-004} lists the outcome among the fields every event carries.
     */
    public void eventBy(Connection connection, UUID actorId, AuditEventType type,
            AuditOutcome outcome, UUID objectId, UUID scopeNode, Map<String, Object> payload) {
        record(connection, actorId == null ? null : new PrincipalId(actorId),
                actorId == null ? ActorType.SYSTEM : ActorType.USER,
                type.code(), outcome, null,
                type.code().substring(0, type.code().indexOf('.')), objectId, scopeNode, payload);
    }

    private void record(Connection connection, Principal principal, String eventType,
            AuditOutcome outcome, String denialReason, String objectKind, UUID objectId,
            UUID scopeNode, Map<String, Object> payload) {
        record(connection,
                principal == null ? null : new PrincipalId(principal.principalId()),
                actorTypeOf(principal), eventType, outcome, denialReason, objectKind, objectId,
                scopeNode, payload);
    }

    private void record(Connection connection, PrincipalId actor, ActorType actorType,
            String eventType,
            AuditOutcome outcome, String denialReason, String objectKind, UUID objectId,
            UUID scopeNode, Map<String, Object> payload) {
        AuditRecorder recorder = new ChainedAuditWriter(
                new JdbcAuditChainStore(connection, payload), clock, PlatformEventTypes.CATALOGUE);
        recorder.record(
                new AuditRecorder.AuditDraft(
                        eventType,
                        outcome,
                        denialReason,
                        // Together or not at all — the kernel refuses a half-recorded reference, and
                        // it is right to: "what happened to this object" cannot be answered by a kind
                        // with no identity. Several events legitimately have no object: a singleton
                        // configuration row keyed by tenant, and a sign-in attempt against an
                        // identifier that turned out not to exist. The event type says what changed;
                        // inventing an identifier so the pair looks complete would be worse.
                        objectId == null ? null : objectKind,
                        objectId,
                        actor,
                        actorType,
                        null,
                        null,
                        scopeOf(connection, scopeNode)),
                payload);
    }

    /**
     * Who acted.
     *
     * <p>{@code SEC-AUD-004} requires the actor's kind, not only its identity, because "a service
     * credential did this" and "a person did this" are different findings during an investigation and
     * the identifier alone does not distinguish them.
     */
    private static ActorType actorTypeOf(Principal principal) {
        if (principal == null) {
            return ActorType.SYSTEM;
        }
        return principal.serviceCredential() ? ActorType.SERVICE : ActorType.USER;
    }

    /**
     * The scope as it was, at the hierarchy version it was.
     *
     * <p>Resolved here rather than passed in, because an emitter that supplied its own ancestor path
     * could supply a shorter one, and the containment test {@code scope_ancestor_path @> ARRAY[N]} is
     * what answers "was this principal authorized for this event at that time" on every historical
     * read ({@code SEC-AUZ-028}).
     */
    private static AuditScope scopeOf(Connection connection, UUID scopeNode) {
        long version = hierarchyVersion(connection);
        if (scopeNode == null) {
            return AuditScope.unscoped(version);
        }
        List<OrgNodeId> path = new java.util.ArrayList<>();
        try (PreparedStatement ancestors = connection.prepareStatement(
                "SELECT ancestor_id FROM org_closure WHERE descendant_id = ? ORDER BY depth DESC")) {
            ancestors.setObject(1, scopeNode);
            try (ResultSet rows = ancestors.executeQuery()) {
                while (rows.next()) {
                    path.add(new OrgNodeId(rows.getObject(1, UUID.class)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the scope ancestry could not be resolved for " + scopeNode, e);
        }
        if (path.isEmpty()) {
            // A node with no closure rows cannot be placed in the tree. Recording it as unscoped would
            // claim the event belongs to no part of the organization, which is a different and false
            // statement — PP-1 again, one level down.
            throw new IllegalStateException("node " + scopeNode + " has no closure rows, so the scope "
                    + "of this event cannot be recorded truthfully");
        }
        return new AuditScope(new OrgNodeId(scopeNode), path, version);
    }

    private static long hierarchyVersion(Connection connection) {
        // From org_closure, not from `tenant`: the application role has no privilege on the tenant
        // table, and the two paths that already stamp a scope descriptor — IntakeService and
        // SbomIngestion — read the version from the closure. One source, and it is the one that
        // describes the tree the version is a version of.
        try (PreparedStatement version = connection.prepareStatement(
                "SELECT max(hierarchy_version) FROM org_closure")) {
            try (ResultSet row = version.executeQuery()) {
                // AuditScope refuses a version below 1 (INV-TEN-03 makes it monotonic from 1), so a
                // tenant whose hierarchy has never been touched still reports 1 rather than 0.
                return row.next() ? Math.max(1L, row.getLong(1)) : 1L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the hierarchy version could not be read", e);
        }
    }
}
