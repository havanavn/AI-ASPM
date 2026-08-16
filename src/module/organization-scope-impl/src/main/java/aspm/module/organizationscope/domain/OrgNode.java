package aspm.module.organizationscope.domain;

import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.PrincipalId;
import aspm.sharedkernel.TenantId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One node in the organization hierarchy — "the unit of accountability and the anchor of scope"
 * (DOC-03 section 7.3).
 *
 * <p><b>Descendants are outside the boundary, deliberately.</b> DOC-03 section 7.3: including them "would
 * mean renaming a leaf requires loading and locking its entire ancestry's subtree, and moving a mid-tree
 * node would serialize against every operation anywhere beneath it". The accepted consequence is that
 * subtree operations are not atomic, which is why reorganization is a saga ({@link ReorganizationSaga})
 * rather than a transaction.
 */
public final class OrgNode {

    /** {@code ACTIVE → DEPRECATED → ARCHIVED}, one direction only. */
    public enum Lifecycle {
        ACTIVE,
        /** No new assignment, but remains in operational views so in-flight work completes. */
        DEPRECATED,
        /**
         * Leaves operational views entirely, but stays resolvable for historical scope descriptors.
         *
         * <p>{@code INV-ORG-11}. This is why archival is not deletion: a descriptor recorded years ago names
         * this node, and a report over that period must still resolve it.
         */
        ARCHIVED;

        boolean permits(Lifecycle next) {
            return switch (this) {
                case ACTIVE -> next == DEPRECATED || next == ARCHIVED;
                case DEPRECATED -> next == ARCHIVED;
                case ARCHIVED -> false;
            };
        }
    }

    public enum OwnerKind {
        BUSINESS,
        TECHNICAL
    }

    /** An owner assignment. Business and technical sets may overlap ({@code INV-ORG-12}). */
    public record Owner(PrincipalId principalId, OwnerKind kind) {
        public Owner {
            Objects.requireNonNull(principalId, "principalId is required");
            Objects.requireNonNull(kind, "kind is required");
        }
    }

    /** A domain event this aggregate emits. */
    public sealed interface Event {
        record Created(OrgNodeId nodeId, UUID typeId, OrgNodeId parentId) implements Event {}

        record Renamed(OrgNodeId nodeId, String from, String to) implements Event {}

        record OwnerAssigned(OrgNodeId nodeId, Owner owner) implements Event {}

        record OwnerRemoved(OrgNodeId nodeId, Owner owner) implements Event {}

        /**
         * The node owns assets but has no business owner.
         *
         * <p>An <b>event, not a rejected write</b> — {@code INV-ORG-12}. DOC-03 section 7.3 is explicit about
         * why: "Requiring an owner at write time appears safer and is worse. Nodes are frequently created by
         * import or by structural change before ownership is settled, and rejecting the write means the node
         * is not created, which means its assets have no home at all." Making the unsafe state visible beats
         * making it unrepresentable when the unsafe state is a normal transient.
         */
        record OwnershipGapDetected(OrgNodeId nodeId, String reason) implements Event {}

        record LifecycleDeprecated(OrgNodeId nodeId) implements Event {}

        record LifecycleArchived(OrgNodeId nodeId) implements Event {}
    }

    private final OrgNodeId id;
    private final TenantId tenantId;
    private final UUID typeId;
    private OrgNodeId parentId;
    private String name;
    private final String externalReference;
    private CriticalityResolution.Assignment criticality;
    private final Set<Owner> owners = new LinkedHashSet<>();
    private Lifecycle lifecycleState = Lifecycle.ACTIVE;
    private final List<Event> pending = new java.util.ArrayList<>();

    private OrgNode(OrgNodeId id, TenantId tenantId, UUID typeId, OrgNodeId parentId, String name,
            String externalReference, CriticalityResolution.Assignment criticality) {
        this.id = id;
        this.tenantId = tenantId;
        this.typeId = typeId;
        this.parentId = parentId;
        this.name = name;
        this.externalReference = externalReference;
        this.criticality = criticality;
    }

    /**
     * Creates a node.
     *
     * <p>{@code INV-ORG-06} is checked here rather than in the constructor because it needs the parent's type
     * and the child type's permitted set — neither of which the node itself holds. DOC-04 section 11.2.2
     * places it in the domain per {@code CON-PLT-018} rather than as a trigger.
     *
     * @param parentType the parent's type, or null where this node is a tree root
     */
    public static OrgNode create(
            OrgNodeId id,
            TenantId tenantId,
            OrgNodeTypeCatalogue.NodeType ownType,
            OrgNodeId parentId,
            OrgNodeTypeCatalogue.NodeType parentType,
            String name,
            String externalReference,
            CriticalityResolution.Assignment criticality) {

        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(ownType, "the node's type is required");
        Objects.requireNonNull(criticality, "a criticality assignment is required, even if INHERITED");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a node name is required");
        }

        // INV-ORG-05: exactly one parent, except roots which have none. Expressed as the coherence of the
        // parent reference with the type's rootability, because a null parent on a non-rootable type and a
        // present parent on a rootable one are different mistakes.
        if ((parentId == null) != (parentType == null)) {
            throw new IllegalArgumentException(
                    "parent identity and parent type must be supplied together or not at all; supplying one "
                            + "without the other means INV-ORG-06 cannot be checked, and an unchecked "
                            + "parent-type rule is how a tenant's structure silently stops meaning anything");
        }
        if (parentId != null && parentId.equals(id)) {
            throw new IllegalArgumentException("a node cannot be its own parent (INV-ORG-07)");
        }
        if (!OrgNodeTypeCatalogue.permitsParent(ownType, parentType)) {
            throw new IllegalArgumentException(
                    "type '" + ownType.code() + "' does not permit "
                            + (parentType == null ? "being a tree root" : "parent type '" + parentType.code() + "'")
                            + " (INV-ORG-06)");
        }
        if (parentType != null && parentType.lifecycleState() == OrgNodeTypeCatalogue.NodeType.Lifecycle.DEPRECATED) {
            throw new IllegalArgumentException(
                    "parent type '" + parentType.code() + "' is DEPRECATED and accepts no new child nodes");
        }

        OrgNode node = new OrgNode(id, tenantId, ownType.id(), parentId, name.strip(), externalReference,
                criticality);
        node.pending.add(new Event.Created(id, ownType.id(), parentId));
        return node;
    }

    public OrgNodeId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public UUID typeId() {
        return typeId;
    }

    public Optional<OrgNodeId> parentId() {
        return Optional.ofNullable(parentId);
    }

    public String name() {
        return name;
    }

    public Optional<String> externalReference() {
        return Optional.ofNullable(externalReference);
    }

    public CriticalityResolution.Assignment criticality() {
        return criticality;
    }

    public Lifecycle lifecycleState() {
        return lifecycleState;
    }

    public Set<Owner> owners() {
        return Set.copyOf(owners);
    }

    public void rename(String newName) {
        requireNotArchived("rename");
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("a node name is required");
        }
        String from = this.name;
        this.name = newName.strip();
        pending.add(new Event.Renamed(id, from, this.name));
    }

    /**
     * Re-parents the node. Called by {@link ReorganizationSaga}'s {@code REPARENTING} step.
     *
     * <p>Does <b>not</b> touch any scope descriptor. {@code PRD-WRK-042}: descriptors record the scope as it
     * was, and that is what makes historical reporting reproducible.
     */
    public void reparentTo(
            OrgNodeId newParentId,
            OrgNodeTypeCatalogue.NodeType ownType,
            OrgNodeTypeCatalogue.NodeType newParentType) {
        requireNotArchived("reparent");
        if (newParentId != null && newParentId.equals(id)) {
            throw new IllegalArgumentException("a node cannot be its own parent (INV-ORG-07)");
        }
        if ((newParentId == null) != (newParentType == null)) {
            throw new IllegalArgumentException("parent identity and parent type must be supplied together");
        }
        if (!OrgNodeTypeCatalogue.permitsParent(ownType, newParentType)) {
            throw new IllegalArgumentException(
                    "target parent type does not permit type '" + ownType.code() + "' (INV-ORG-06)");
        }
        this.parentId = newParentId;
        // No event here: the saga publishes OrgNodeMoved once the closure is rebuilt, because an event
        // published before the closure is consistent would let a subscriber read a half-moved tree.
    }

    public void assignOwner(PrincipalId principalId, OwnerKind kind) {
        requireAcceptsAssignment("owner assignment");
        Owner owner = new Owner(principalId, kind);
        if (owners.add(owner)) {
            pending.add(new Event.OwnerAssigned(id, owner));
        }
    }

    public void removeOwner(PrincipalId principalId, OwnerKind kind) {
        requireNotArchived("owner removal");
        Owner owner = new Owner(principalId, kind);
        if (owners.remove(owner)) {
            pending.add(new Event.OwnerRemoved(id, owner));
        }
    }

    /**
     * Raises the ownership gap of {@code INV-ORG-12} where the node owns assets and has no business owner.
     *
     * <p>Called by the caller that knows whether assets exist — this aggregate's boundary excludes them.
     * Idempotent, so a repeated check does not flood the queue.
     */
    public void evaluateOwnershipGap(boolean ownsAssets) {
        boolean hasBusinessOwner = owners.stream().anyMatch(o -> o.kind() == OwnerKind.BUSINESS);
        boolean alreadyRaised = pending.stream().anyMatch(e -> e instanceof Event.OwnershipGapDetected);
        if (ownsAssets && !hasBusinessOwner && !alreadyRaised) {
            pending.add(new Event.OwnershipGapDetected(id,
                    "the node owns assets and has no BUSINESS owner. Raised as an event rather than "
                            + "rejecting the write, because rejecting means the node is not created and its "
                            + "assets have no home at all (INV-ORG-12)."));
        }
    }

    public void reassignCriticality(CriticalityResolution.Assignment assignment) {
        requireAcceptsAssignment("criticality assignment");
        this.criticality = Objects.requireNonNull(assignment, "assignment is required");
    }

    public void deprecate() {
        transitionTo(Lifecycle.DEPRECATED);
        pending.add(new Event.LifecycleDeprecated(id));
    }

    public void archive() {
        transitionTo(Lifecycle.ARCHIVED);
        pending.add(new Event.LifecycleArchived(id));
    }

    /**
     * Whether this node appears in operational scope resolution.
     *
     * <p>{@code INV-ORG-11}: an ARCHIVED node "does not appear in operational scope resolution — but remains
     * resolvable for historical scope descriptors". The two are different questions and this method answers
     * only the first; historical resolution reads the descriptor, which does not consult lifecycle at all.
     */
    public boolean inOperationalScope() {
        return lifecycleState != Lifecycle.ARCHIVED;
    }

    /** Events raised since construction or the last drain. */
    public List<Event> drainEvents() {
        List<Event> drained = List.copyOf(pending);
        pending.clear();
        return drained;
    }

    private void transitionTo(Lifecycle next) {
        if (!lifecycleState.permits(next)) {
            throw new IllegalStateException(
                    "lifecycle transition " + lifecycleState + " -> " + next + " is not permitted. "
                            + "ACTIVE -> DEPRECATED -> ARCHIVED is one-directional, and there is no path out "
                            + "of ARCHIVED because historical descriptors name archived nodes (INV-ORG-11).");
        }
        lifecycleState = next;
    }

    private void requireNotArchived(String operation) {
        if (lifecycleState == Lifecycle.ARCHIVED) {
            throw new IllegalStateException(operation + " is not permitted on an ARCHIVED node");
        }
    }

    private void requireAcceptsAssignment(String operation) {
        if (lifecycleState != Lifecycle.ACTIVE) {
            throw new IllegalStateException(
                    operation + " is not permitted on a " + lifecycleState + " node. INV-ORG-11: a "
                            + "DEPRECATED node accepts no new assignment but stays in operational views so "
                            + "in-flight work completes; an ARCHIVED node leaves them entirely.");
        }
    }
}
