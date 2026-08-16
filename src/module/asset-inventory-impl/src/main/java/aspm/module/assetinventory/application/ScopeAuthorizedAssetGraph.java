package aspm.module.assetinventory.application;

import aspm.module.assetinventory.contract.AssetPermissions;
import aspm.module.assetinventory.domain.AssetGraphTraversal;
import aspm.module.organizationscope.contract.ScopeResolutionQuery;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.PrincipalId;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Wires graph traversal to real scope resolution, closing the gap that left {@code INV-AST-17} proven in the
 * domain and unproven in the product.
 *
 * <p>{@link AssetGraphTraversal} takes a {@code Predicate<UUID>} so that it can be tested without
 * infrastructure ({@code TST-PLT-005}). That is correct for the domain and useless on its own: until something
 * builds the predicate from the principal's resolved scope, the traversal filters against whatever the caller
 * happens to pass, and the caller passing {@code id -> true} would compile.
 *
 * <p>This class is the only intended way to traverse for a principal. Two properties matter:
 *
 * <ul>
 *   <li><b>Scope is resolved once, per traversal.</b> Not per node — that would be a resolution call per edge
 *       on the platform's most frequent operation. The <em>predicate</em> is evaluated per node, which is what
 *       {@code INV-AST-17} requires; what is cached is the principal's permitted node set, which cannot change
 *       mid-traversal because the traversal is inside one request.
 *   <li><b>An unavailable resolution yields an empty traversal, not an unfiltered one.</b>
 *       {@code SEC-AUZ-014} requires denial on unavailable scope resolution, and the failure shape here is the
 *       one that matters: a predicate defaulting to {@code true} on a resolution failure would return the whole
 *       graph.
 * </ul>
 */
public final class ScopeAuthorizedAssetGraph {

    /** Supplies the owning node of an asset, for the per-node scope check. */
    public interface AssetOwnershipLookup {

        /**
         * Returns the owning node of {@code assetId}, or empty where the asset is {@code UNCLAIMED}.
         *
         * <p>An unclaimed asset has no owner and therefore no scope. It is <b>excluded</b> from traversal for
         * every principal, because including it would make an asset nobody owns visible to everybody —
         * DOC-07 section 9.2 makes {@code UNCLAIMED} assets visible only with {@code ast.ownership.claim} in
         * the candidate scope, which is a different query from following an edge.
         */
        Optional<OrgNodeId> owningNodeOf(UUID assetId);
    }

    private final ScopeResolutionQuery scopeResolution;
    private final AssetOwnershipLookup ownership;
    private final AssetGraphTraversal.EdgeSource edges;

    public ScopeAuthorizedAssetGraph(ScopeResolutionQuery scopeResolution, AssetOwnershipLookup ownership,
            AssetGraphTraversal.EdgeSource edges) {
        this.scopeResolution = Objects.requireNonNull(scopeResolution, "scope resolution is required");
        this.ownership = Objects.requireNonNull(ownership, "ownership lookup is required");
        this.edges = Objects.requireNonNull(edges, "edge source is required");
    }

    /**
     * Traverses from {@code origin} as {@code principal}.
     *
     * @return the audit view, whose caller-facing {@link AssetGraphTraversal.Result} discloses nothing about
     *     pruned branches. The pruned count belongs in the audit trail, not the response
     */
    public AssetGraphTraversal.AuditView traverseFor(
            PrincipalId principal, UUID origin, Instant at, int maxDepth) {
        Objects.requireNonNull(principal, "principal is required");
        Objects.requireNonNull(origin, "origin is required");

        var resolution = scopeResolution.resolveCurrent(principal, AssetPermissions.ASSET_READ.code());

        if (resolution.isUnavailable()) {
            // The whole point of returning empty rather than throwing: a caller catching an exception and
            // continuing with an unfiltered traversal is the fail-open SEC-AUZ-014 closes. An empty result is
            // indistinguishable to the caller from a principal who reaches nothing, which is correct — they
            // are not entitled to know which it was.
            return new AssetGraphTraversal.AuditView(
                    new AssetGraphTraversal.Result(Set.of(), java.util.List.of()), 0, 0);
        }

        // Resolved once. The permitted set is fixed for the request, so caching it does not weaken the
        // per-node evaluation below — it removes a resolution call per edge from the platform's most
        // frequent operation.
        Set<OrgNodeId> permitted = new HashSet<>(resolution.permittedNodes());

        return AssetGraphTraversal.from(origin, edges,
                assetId -> ownership.owningNodeOf(assetId).map(permitted::contains).orElse(false),
                at, maxDepth);
    }
}
