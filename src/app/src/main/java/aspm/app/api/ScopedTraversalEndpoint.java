package aspm.app.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The asset graph traversal endpoint. {@code SEC-AUZ-024}, {@code SEC-AUZ-025}, assertion A9.
 *
 * <p>Three requirements that interact, and the third is the one that is easy to break while satisfying the
 * first two:
 *
 * <ol>
 *   <li><b>Scope is evaluated per node reached</b>, and an out-of-scope node terminates that branch. "Per-query
 *       filtering is insufficient because the query is authorized" — the caller may legitimately traverse from a
 *       node they can see, through an edge that is real, to a node they cannot.
 *   <li><b>The traversal does not fail</b> on reaching one. Failing the operation discloses that something
 *       exists out of scope, which is the disclosure the filtering was for.
 *   <li><b>It does not indicate that a branch was terminated.</b> No truncation flag, no count of omitted
 *       nodes, no "partial" marker.
 * </ol>
 *
 * <h2>The third is where an implementation usually leaks</h2>
 *
 * <p>A traversal result carrying {@code truncated: true} is a correct-looking API design and an existence
 * oracle: the caller learns that an edge led somewhere they cannot see, which is precisely what they were not to
 * learn. So {@link Result} has no such field, and a test asserts none exists.
 *
 * <p>The subtler version is a <b>count</b>. Returning "12 nodes reached" where a full traversal would reach 15
 * discloses three out-of-scope neighbours, and it does so through a field nobody would call a disclosure. The
 * result therefore reports what it returns and nothing about what it did not.
 *
 * <h2>{@code SEC-AUZ-025} — the bound does not vary with scope</h2>
 *
 * <p>"A bound varying with scope discloses scope breadth through response characteristics." A broad-scope
 * principal getting deeper results than a narrow one turns response size into a measure of the caller's own
 * authority — and, run against several principals, into a map of who can see what. The bounds here are
 * constants, and {@link #traverse} takes no bound parameter at all.
 */
public final class ScopedTraversalEndpoint {

    /**
     * Maximum depth. A constant, not a parameter: a caller-supplied bound would let a caller ask for a deeper
     * traversal than the platform intends, and a scope-derived one is the disclosure above.
     */
    public static final int MAX_DEPTH = 6;

    /** Maximum nodes returned. Same reasoning. Also the denial-of-service bound {@code SEC-AUZ-025} requires. */
    public static final int MAX_NODES = 500;

    /**
     * A traversal result.
     *
     * <p>Deliberately carries <b>only</b> what was reached. No truncation flag, no omitted count, no
     * total-before-filtering — each of those is an existence oracle with a reassuring name.
     */
    public record Result(List<UUID> reachedNodes, List<Edge> traversedEdges) {

        public Result {
            reachedNodes = List.copyOf(Objects.requireNonNull(reachedNodes, "reachedNodes are required"));
            traversedEdges = List.copyOf(Objects.requireNonNull(traversedEdges, "edges are required"));
        }
    }

    /** An edge between two in-scope nodes. An edge to an out-of-scope node is not returned at all. */
    public record Edge(UUID from, UUID to, String kind) {

        public Edge {
            Objects.requireNonNull(from, "from is required");
            Objects.requireNonNull(to, "to is required");
            Objects.requireNonNull(kind, "kind is required");
        }
    }

    private ScopedTraversalEndpoint() {
    }

    /**
     * Traverses from a starting node, filtering per node reached.
     *
     * @param inScope evaluated for <b>every</b> node reached, including the start. A caller who cannot see the
     *     start gets an empty result rather than an error, for the same reason a terminated branch is not
     *     reported
     * @param neighbours the graph. Returns every neighbour regardless of scope — filtering here rather than in
     *     the caller's supplier is deliberate: a supplier that pre-filtered would make the per-node evaluation
     *     invisible and therefore removable
     */
    public static Result traverse(UUID startNode, Predicate<UUID> inScope,
            Function<UUID, List<Edge>> neighbours) {
        Objects.requireNonNull(startNode, "a start node is required");
        Objects.requireNonNull(inScope, "a per-node scope predicate is required (SEC-AUZ-024)");
        Objects.requireNonNull(neighbours, "a neighbour function is required");

        List<UUID> reached = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        Set<UUID> visited = new LinkedHashSet<>();

        if (!inScope.test(startNode)) {
            // An empty result, not a 404 and not an error. The caller learns nothing about whether the node
            // exists — which is the same answer they would get for a node that does not.
            return new Result(List.of(), List.of());
        }

        record Step(UUID node, int depth) {
        }
        Deque<Step> frontier = new ArrayDeque<>();
        frontier.add(new Step(startNode, 0));
        visited.add(startNode);
        reached.add(startNode);

        while (!frontier.isEmpty() && reached.size() < MAX_NODES) {
            Step current = frontier.removeFirst();
            if (current.depth() >= MAX_DEPTH) {
                continue;
            }
            for (Edge edge : neighbours.apply(current.node())) {
                if (reached.size() >= MAX_NODES) {
                    break;
                }
                // Per node reached. The edge is real and the query was authorized; this is the check that
                // stops the traversal walking through it.
                if (!inScope.test(edge.to())) {
                    // Branch terminated. Nothing is recorded about it — no flag, no count, no marker.
                    continue;
                }
                if (visited.add(edge.to())) {
                    reached.add(edge.to());
                    frontier.addLast(new Step(edge.to(), current.depth() + 1));
                }
                edges.add(edge);
            }
        }

        return new Result(reached, edges);
    }
}
