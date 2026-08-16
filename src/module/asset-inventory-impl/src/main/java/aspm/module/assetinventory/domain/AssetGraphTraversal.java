package aspm.module.assetinventory.domain;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Scope-filtered traversal of the asset graph. {@code INV-AST-17}, {@code SEC-AUZ-024},
 * {@code SEC-AUZ-025}.
 *
 * <p>DOC-03 section 8.3 calls this "the subtle authorization defect in any graph model" and describes it
 * exactly:
 *
 * <blockquote>A principal authorized for service S follows {@code S → exposes → API → published_on → Domain}
 * and reaches a domain owned by a different business unit. Filtering the <em>query</em> is insufficient
 * because the query started legitimately. Each traversal step must re-evaluate scope on the node reached, and
 * an out-of-scope node terminates that branch rather than failing the query — otherwise the failure itself
 * discloses that something exists there.</blockquote>
 *
 * <p>Three properties follow, and all three are asserted:
 *
 * <ol>
 *   <li><b>Per node, not per query.</b> {@link #from} evaluates the predicate on every node it reaches.
 *   <li><b>Terminate, do not fail.</b> An out-of-scope node ends its branch and the traversal continues.
 *   <li><b>Do not indicate termination.</b> The result carries no count, marker, or placeholder for a pruned
 *       branch — a "3 results hidden" notice is an existence oracle, which is what {@code SEC-AUZ-020}
 *       forbids in a different guise. The pruned count is available <em>only</em> on the audit-facing view,
 *       never on the caller-facing one.
 * </ol>
 *
 * <p>{@code SEC-AUZ-025} additionally requires the traversal bound to be independent of scope. A depth limit
 * that consumed pruned branches would make reachable depth vary with the principal's scope, which is itself a
 * signal about what lies outside it.
 */
public final class AssetGraphTraversal {

    /** A current edge between two assets. Only current edges are traversed; see {@code INV-AST-16}. */
    public record Edge(UUID fromAssetId, UUID toAssetId, String edgeType, Instant validFrom,
            Instant validUntil) {

        public Edge {
            Objects.requireNonNull(fromAssetId, "fromAssetId is required");
            Objects.requireNonNull(toAssetId, "toAssetId is required");
            Objects.requireNonNull(edgeType, "edgeType is required");
            Objects.requireNonNull(validFrom, "validFrom is required");
        }

        /** {@code INV-AST-16}: a superseded edge is closed with {@code validUntil}, never deleted. */
        public boolean isCurrentAt(Instant at) {
            return !validFrom.isAfter(at) && (validUntil == null || validUntil.isAfter(at));
        }
    }

    /** Supplies edges incident to a node, in both directions. */
    public interface EdgeSource {

        /**
         * Returns every edge with {@code assetId} as either endpoint.
         *
         * <p>Both directions, because {@code INV-AST-15} makes edges many-to-many in both directions and
         * DOC-03 section 8.3 records that placing edges on the {@code from} asset "makes reverse traversal
         * require scanning every asset". DOC-04 indexes both directions for this reason.
         */
        List<Edge> incidentTo(UUID assetId);
    }

    /**
     * The caller-facing result.
     *
     * <p>Carries reached nodes and the edges between them, and <b>nothing about what was pruned</b>. There is
     * no {@code prunedCount} accessor here by design; see {@link AuditView}.
     */
    public record Result(Set<UUID> reachedAssetIds, List<Edge> traversedEdges) {

        public Result {
            reachedAssetIds = Set.copyOf(Objects.requireNonNull(reachedAssetIds, "reached ids are required"));
            traversedEdges = List.copyOf(Objects.requireNonNull(traversedEdges, "edges are required"));
        }
    }

    /**
     * The audit-facing view of the same traversal.
     *
     * <p>Pruning counts belong in the audit trail, not in the response. {@code SEC-AUD-007} makes restricted
     * access auditable at object granularity, and a sustained run of pruned branches from one principal is an
     * enumeration signal that {@code SEC-PLT-003}'s denial index exists to surface. Returning the same number
     * to the caller would hand them the oracle instead.
     */
    public record AuditView(Result result, int prunedBranchCount, int depthReached) {}

    private AssetGraphTraversal() {
        throw new AssertionError("not instantiable");
    }

    /**
     * Traverses outward from {@code origin}, evaluating {@code inScope} on every node reached.
     *
     * @param inScope the per-node scope predicate. Called for every candidate, including the origin
     * @param maxDepth the traversal bound. Independent of scope per {@code SEC-AUZ-025}: pruned branches do
     *     not consume it, so reachable depth does not vary with the principal
     */
    public static AuditView from(UUID origin, EdgeSource edges, Predicate<UUID> inScope, Instant at,
            int maxDepth) {
        Objects.requireNonNull(origin, "origin is required");
        Objects.requireNonNull(edges, "edge source is required");
        Objects.requireNonNull(inScope, "a per-node scope predicate is required (INV-AST-17)");
        Objects.requireNonNull(at, "the traversal instant is required");
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth is non-negative");
        }

        Set<UUID> reached = new LinkedHashSet<>();
        List<Edge> traversed = new ArrayList<>();
        int pruned = 0;
        int deepestReached = 0;

        // The origin is not exempt. A principal reaching the graph at a node outside their scope must get an
        // empty result, not a seeded one — an exempt origin would make the entry point an oracle.
        if (!inScope.test(origin)) {
            return new AuditView(new Result(Set.of(), List.of()), 1, 0);
        }

        record Step(UUID assetId, int depth) {}
        Deque<Step> frontier = new ArrayDeque<>();
        frontier.add(new Step(origin, 0));
        reached.add(origin);

        while (!frontier.isEmpty()) {
            Step step = frontier.poll();
            if (step.depth() >= maxDepth) {
                continue;
            }
            for (Edge edge : edges.incidentTo(step.assetId())) {
                if (!edge.isCurrentAt(at)) {
                    continue;   // INV-AST-16: closed edges are history, not topology
                }
                UUID next = edge.fromAssetId().equals(step.assetId())
                        ? edge.toAssetId()
                        : edge.fromAssetId();

                if (reached.contains(next)) {
                    // Already admitted, so scope was already evaluated. Record the edge for completeness.
                    if (!traversed.contains(edge)) {
                        traversed.add(edge);
                    }
                    continue;
                }

                // The load-bearing line: scope is re-evaluated on the node REACHED, not on the query.
                if (!inScope.test(next)) {
                    pruned++;
                    // Terminate this branch and continue. Note the edge is NOT recorded: an edge to a pruned
                    // node would disclose that the node exists, which is the disclosure the pruning prevents.
                    continue;
                }

                reached.add(next);
                traversed.add(edge);
                deepestReached = Math.max(deepestReached, step.depth() + 1);
                frontier.add(new Step(next, step.depth() + 1));
            }
        }

        return new AuditView(new Result(reached, traversed), pruned, deepestReached);
    }
}
