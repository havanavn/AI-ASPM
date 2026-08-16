package aspm.module.organizationscope.domain;

import aspm.sharedkernel.OrgNodeId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The closure projection of DOC-03 section 7.4.
 *
 * <p>Not an aggregate: a derived projection with {@code OrgNode} parentage as the source of truth
 * ({@code INV-ORG-14}). A closure table rather than recursive traversal because subtree resolution is
 * "the platform's single most frequent operation: every authorization decision and every dashboard
 * aggregation performs it", and recursive traversal would make both scale with tree depth — which is
 * tenant-configured and therefore unbounded ({@code PRD-ORG-001}, {@code PRD-ORG-002}).
 *
 * <p><b>Why rebuild-and-compare is an invariant rather than a nicety.</b> DOC-03 section 7.4 on
 * {@code INV-ORG-14}: "A corrupted closure table silently breaks authorization — a principal either loses
 * access they should have, which is reported, or gains access they should not, which is not." The second
 * case is why inspection cannot be the detection mechanism, and why {@link #diverges} exists.
 *
 * <p><b>Deliberate exclusion.</b> Structure only. No criticality, ownership or name, though denormalizing
 * them would make some queries faster: it would turn a rename into a subtree-wide write and create a
 * second place where criticality lives.
 */
public final class OrgClosure {

    /** One closure row. {@code depth} zero is the self-reference {@code INV-ORG-13} requires. */
    public record Edge(OrgNodeId ancestorId, OrgNodeId descendantId, int depth, long hierarchyVersion) {

        public Edge {
            Objects.requireNonNull(ancestorId, "ancestorId is required");
            Objects.requireNonNull(descendantId, "descendantId is required");
            if (depth < 0) {
                throw new IllegalArgumentException("depth is non-negative");
            }
            if (depth == 0 && !ancestorId.equals(descendantId)) {
                throw new IllegalArgumentException(
                        "depth zero is the self-reference; a depth-zero edge between two different nodes "
                                + "would make 'the subtree of X' ambiguous (INV-ORG-13)");
            }
            if (depth > 0 && ancestorId.equals(descendantId)) {
                throw new IllegalArgumentException(
                        "a node cannot be its own ancestor at depth > 0; that is the cycle INV-ORG-07 "
                                + "rejects at write time");
            }
        }
    }

    private final Set<Edge> edges;

    private OrgClosure(Set<Edge> edges) {
        this.edges = Set.copyOf(edges);
    }

    /**
     * Builds the closure from parentage alone — the "pure function of the node parentage" of
     * {@code INV-ORG-14}.
     *
     * <p>Rejects a cycle rather than looping. {@code INV-ORG-07} requires cycles "rejected at write time,
     * not detected later", and a builder that hung or overflowed on one would be detecting it later in the
     * worst possible way.
     *
     * @param parentOf each node's parent; a node absent from the map, or mapped to null, is a tree root
     * @param hierarchyVersion the version these rows become valid at
     */
    public static OrgClosure buildFrom(
            Map<OrgNodeId, OrgNodeId> parentOf, Set<OrgNodeId> allNodes, long hierarchyVersion) {
        Objects.requireNonNull(parentOf, "parentOf is required");
        Objects.requireNonNull(allNodes, "allNodes is required");
        if (hierarchyVersion < 1) {
            throw new IllegalArgumentException("hierarchy version is monotonic from 1");
        }

        Set<Edge> built = new LinkedHashSet<>();
        for (OrgNodeId node : allNodes) {
            // INV-ORG-13: the self-reference. Without it "the subtree of X" excludes X and every scope
            // query is subtly wrong — subtly, which is why it is stated as an invariant.
            built.add(new Edge(node, node, 0, hierarchyVersion));

            int depth = 0;
            OrgNodeId current = node;
            Set<OrgNodeId> walked = new LinkedHashSet<>();
            walked.add(node);

            while (true) {
                OrgNodeId parent = parentOf.get(current);
                if (parent == null) {
                    break;
                }
                depth++;
                if (!walked.add(parent)) {
                    throw new IllegalStateException(
                            "cycle in org parentage reached from " + node.value() + " via "
                                    + parent.value() + " (INV-ORG-07). Rejected here rather than "
                                    + "traversed, because a builder that looped would detect the cycle by "
                                    + "exhausting memory.");
                }
                if (!allNodes.contains(parent)) {
                    throw new IllegalStateException(
                            "node " + current.value() + " names parent " + parent.value()
                                    + ", which is not in the node set. A dangling parent would silently "
                                    + "truncate the ancestor path, and a truncated path denies historical "
                                    + "reads that should be permitted.");
                }
                built.add(new Edge(parent, node, depth, hierarchyVersion));
                current = parent;
            }
        }
        return new OrgClosure(built);
    }

    /** The stored rows, for persistence and for comparison. */
    public Set<Edge> edges() {
        return edges;
    }

    /**
     * Reconstructs a closure from stored rows without validating it against parentage.
     *
     * <p>Used by {@link #diverges} to represent what is actually in the table, which may be wrong — that
     * being the whole point of the comparison.
     */
    public static OrgClosure ofStoredRows(Set<Edge> storedRows) {
        return new OrgClosure(Objects.requireNonNull(storedRows, "storedRows is required"));
    }

    /**
     * The {@code INV-ORG-14} reconciliation: {@code CON-DAT-026}'s rebuild-and-compare.
     *
     * <p>Returns the symmetric difference, so both directions are visible. Direction matters
     * operationally: a row present in the stored closure but absent from the rebuild is <b>granted access
     * that should not exist</b>, which nobody reports; the reverse is access denied, which someone
     * reports within the hour. A comparison returning only a boolean would lose that distinction.
     */
    public Divergence diverges(OrgClosure rebuiltFromSource) {
        Objects.requireNonNull(rebuiltFromSource, "the rebuilt closure is required");

        Set<Edge> extraneous = new LinkedHashSet<>(this.edges);
        extraneous.removeAll(rebuiltFromSource.edges);

        Set<Edge> missing = new LinkedHashSet<>(rebuiltFromSource.edges);
        missing.removeAll(this.edges);

        return new Divergence(Set.copyOf(extraneous), Set.copyOf(missing));
    }

    /**
     * The result of a rebuild-and-compare.
     *
     * @param extraneous rows in the stored closure that the rebuild does not produce — <b>excess access</b>
     * @param missing rows the rebuild produces that the stored closure lacks — denied access
     */
    public record Divergence(Set<Edge> extraneous, Set<Edge> missing) {

        public boolean reconciled() {
            return extraneous.isEmpty() && missing.isEmpty();
        }

        /**
         * True where the divergence grants access that should not exist.
         *
         * <p>Separated because this is the case that is never reported by a user and therefore the case a
         * scheduled reconciliation exists to catch.
         */
        public boolean grantsExcessAccess() {
            return !extraneous.isEmpty();
        }
    }

    /** Ancestors of {@code node}, nearest first, excluding the self-reference. */
    public List<OrgNodeId> ancestorsOf(OrgNodeId node) {
        return edges.stream()
                .filter(e -> e.descendantId().equals(node) && e.depth() > 0)
                .sorted(java.util.Comparator.comparingInt(Edge::depth))
                .map(Edge::ancestorId)
                .toList();
    }

    /**
     * The root-to-node path a {@link aspm.sharedkernel.ScopeDescriptor} records, inclusive of the node.
     *
     * <p>This is the only supported way to produce that path, so a descriptor cannot be built from a
     * hand-assembled list that happens to look right.
     */
    public List<OrgNodeId> ancestorPathTo(OrgNodeId node) {
        Objects.requireNonNull(node, "node is required");
        List<OrgNodeId> nearestFirst = ancestorsOf(node);
        List<OrgNodeId> path = new ArrayList<>(nearestFirst.reversed());
        path.add(node);
        return List.copyOf(path);
    }

    /** The subtree of {@code node}, including {@code node} itself per {@code INV-ORG-13}. */
    public Set<OrgNodeId> subtreeOf(OrgNodeId node) {
        Set<OrgNodeId> subtree = new TreeSet<>(java.util.Comparator.comparing(n -> n.value().toString()));
        edges.stream()
                .filter(e -> e.ancestorId().equals(node))
                .forEach(e -> subtree.add(e.descendantId()));
        return subtree;
    }

    /** The immediate parent, or empty for a tree root. */
    public Optional<OrgNodeId> parentOf(OrgNodeId node) {
        return edges.stream()
                .filter(e -> e.descendantId().equals(node) && e.depth() == 1)
                .map(Edge::ancestorId)
                .findFirst();
    }

    /** Nodes with no parent. */
    public Set<OrgNodeId> roots() {
        Map<OrgNodeId, Boolean> hasParent = new HashMap<>();
        edges.forEach(e -> {
            hasParent.putIfAbsent(e.descendantId(), false);
            if (e.depth() == 1) {
                hasParent.put(e.descendantId(), true);
            }
        });
        return hasParent.entrySet().stream()
                .filter(e -> !e.getValue())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
