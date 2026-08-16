package aspm.sharedkernel;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The scope snapshot of DOC-03 section 6.7 — the resolved organizational context at one instant.
 *
 * <p><b>This mechanism cannot be added later.</b> {@code PRD-PLT-001} lists it among the four that
 * capture data which cannot be reconstructed, and DOC-03 section 6.7 states why in one line: "the
 * mechanism cannot be added later — the data does not exist retroactively". A scope-bearing object
 * written without a descriptor has no descriptor for the elapsed period, permanently.
 *
 * <p><b>What a current-parentage model gets wrong.</b> DOC-03 section 6.7 sets out three questions and a
 * model storing only current parentage answers all three incorrectly:
 *
 * <ul>
 *   <li>Can the former parent's manager still see findings that arose under their accountability? Yes for
 *       historical, no for new — a current-parentage model either loses all access or retains all.
 *   <li>Does last quarter's posture report change retroactively? No: it must reproduce identically.
 *   <li>Which service level policy applies to a finding opened before a move? The one in effect when it
 *       opened.
 * </ul>
 *
 * <p><b>Why the ancestor path and not only the owning node.</b> Authorization is subtree-based: a
 * principal assigned to a node is authorized for everything beneath it. Answering <em>was this principal
 * authorized for this object at that time</em> needs the ancestors <em>at that time</em>, and those are
 * not derivable from the current tree. DOC-04 section 6.6 makes the predicate a single indexable
 * containment test on the stored array rather than a reconstruction of a historical closure.
 *
 * <p><b>Immutable after write.</b> {@code CON-DAT-009} and {@code PRD-WRK-042}: reorganization must not
 * modify a descriptor on an existing object. There is no setter, no wither, and no copy constructor
 * taking a new node — the type offers no way to express the mutation, which is the point.
 *
 * <p><b>Deliberate limit.</b> The descriptor records <em>what scope applied</em>. It does not record
 * <em>who held which role</em>, which is Authorization's history and DOC-07's concern. DOC-03
 * section 6.7 keeps them separate to prevent the descriptor becoming a denormalized copy of the
 * permission model.
 */
public record ScopeDescriptor(
        TenantId tenantId,
        OrgNodeId owningNodeId,
        List<OrgNodeId> ancestorPath,
        UUID nodeTypeAtTime,
        UUID criticalityAtTime,
        Instant resolvedAt,
        long hierarchyVersion) {

    public ScopeDescriptor {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(owningNodeId, "owningNodeId is required");
        ancestorPath = List.copyOf(Objects.requireNonNull(ancestorPath, "ancestorPath is required"));
        Objects.requireNonNull(nodeTypeAtTime, "nodeTypeAtTime is required");
        Objects.requireNonNull(criticalityAtTime,
                "criticalityAtTime is required; a descriptor without it cannot answer which service "
                        + "level policy applied when the object was created (DOC-03 section 6.7)");
        Objects.requireNonNull(resolvedAt, "resolvedAt is required");

        if (hierarchyVersion < 1) {
            throw new IllegalArgumentException(
                    "hierarchy version is monotonic from 1 (INV-TEN-03). Version 0 would make the "
                            + "pre-first-change tree indistinguishable from an unset value.");
        }
        if (ancestorPath.isEmpty()) {
            throw new IllegalArgumentException(
                    "the ancestor path is root-to-owning-node inclusive, so it always contains at least "
                            + "the owning node itself. An empty path would make the containment predicate "
                            + "of DOC-04 section 6.6 match nothing, silently denying every historical read.");
        }
        if (!ancestorPath.get(ancestorPath.size() - 1).equals(owningNodeId)) {
            throw new IllegalArgumentException(
                    "the ancestor path must end at the owning node. A path that does not would make "
                            + "subtree containment and node identity disagree, and authorization would "
                            + "follow whichever the caller happened to read.");
        }
        if (ancestorPath.stream().distinct().count() != ancestorPath.size()) {
            throw new IllegalArgumentException(
                    "the ancestor path contains a repeated node, which means the tree contained a cycle "
                            + "when this descriptor was resolved (INV-ORG-07)");
        }
    }

    /**
     * Whether a principal authorized for {@code node} was authorized for this object at the recorded
     * time.
     *
     * <p>This is the {@code scope_ancestor_path @> ARRAY[N]} containment test of DOC-04 section 6.6,
     * expressed in the domain so it can be asserted without a database. The database evaluates the same
     * predicate through a GIN index; both must agree, and a test asserting they do belongs to the
     * verification suite rather than here.
     */
    public boolean withinScopeOf(OrgNodeId node) {
        Objects.requireNonNull(node, "node is required");
        return ancestorPath.contains(node);
    }

    /** Depth of the owning node, root being depth zero. */
    public int depth() {
        return ancestorPath.size() - 1;
    }

    /** The root of the tree this descriptor was resolved in. */
    public OrgNodeId rootNodeId() {
        return ancestorPath.get(0);
    }
}
