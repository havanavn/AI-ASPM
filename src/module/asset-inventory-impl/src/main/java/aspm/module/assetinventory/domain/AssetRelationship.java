package aspm.module.assetinventory.domain;

import aspm.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A temporal edge in the asset graph, per DOC-03 section 8.3.
 *
 * <p><b>Its own aggregate, not a collection on {@code Asset}.</b> DOC-03 gives three reasons, "each
 * sufficient": an edge has two owners and no natural single home, so placing it on the {@code from} asset
 * "makes reverse traversal require scanning every asset"; edge churn far exceeds asset churn, so holding
 * edges inside {@code Asset} "would make every deployment a write to the asset aggregate"; and edges are
 * temporal, so "an asset with three years of deployment history would carry thousands of closed edges inside
 * its consistency boundary".
 *
 * <p><b>{@code INV-AST-16} — superseding closes, never deletes.</b> {@link #supersede} sets
 * {@code validUntil}; there is no delete. DOC-03 records the cost and why it is accepted: "closed edges
 * accumulate, and most queries want only current edges… The alternative — deleting superseded edges — makes
 * it impossible to answer <em>what was deployed when this finding was open</em>, which is required for retest
 * scoping and for reproducing a historical posture figure."
 */
public final class AssetRelationship {

    private final UUID id;
    private final TenantId tenantId;
    private final UUID fromAssetId;
    private final UUID toAssetId;
    private final AssetType.EdgeType edgeType;
    private final Asset.DiscoveryProvenance provenance;
    private final Instant validFrom;
    private Instant validUntil;

    private AssetRelationship(UUID id, TenantId tenantId, UUID fromAssetId, UUID toAssetId,
            AssetType.EdgeType edgeType, Asset.DiscoveryProvenance provenance, Instant validFrom) {
        this.id = id;
        this.tenantId = tenantId;
        this.fromAssetId = fromAssetId;
        this.toAssetId = toAssetId;
        this.edgeType = edgeType;
        this.provenance = provenance;
        this.validFrom = validFrom;
    }

    /**
     * Creates an edge, enforcing {@code INV-AST-13} and {@code INV-AST-14}.
     *
     * <p>Both endpoint assets are passed rather than just their identifiers, because {@code INV-AST-13}
     * requires both to be in the same tenant and an identifier alone cannot establish that. Accepting bare
     * identifiers would make the invariant uncheckable here and push it to a database constraint that cannot
     * see the tenant of a row in another module's table (ADR-030).
     */
    public static AssetRelationship connect(UUID id, Asset from, Asset to, AssetType.EdgeType edgeType,
            AssetType fromType, Asset.DiscoveryProvenance provenance, Instant validFrom) {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(from, "the from asset is required");
        Objects.requireNonNull(to, "the to asset is required");
        Objects.requireNonNull(edgeType, "edgeType is required");
        Objects.requireNonNull(fromType, "the from asset's type is required to check INV-AST-14");
        Objects.requireNonNull(provenance, "provenance is required");
        Objects.requireNonNull(validFrom, "validFrom is required");

        // INV-AST-13, which is INV-TEN-02 applied to edges. An edge spanning tenants would make graph
        // traversal a cross-tenant read regardless of every other control, because traversal follows edges.
        if (!from.tenantId().equals(to.tenantId())) {
            throw new IllegalArgumentException(
                    "both endpoints must be in the same tenant (INV-AST-13). An edge spanning tenants makes "
                            + "graph traversal a cross-tenant read no matter what else is enforced.");
        }
        if (!from.typeId().equals(fromType.id())) {
            throw new IllegalArgumentException(
                    "the supplied type does not belong to the from asset, so INV-AST-14 would be checked "
                            + "against the wrong constraint set");
        }
        if (from.id().equals(to.id())) {
            throw new IllegalArgumentException(
                    "an asset cannot relate to itself; a self-edge would make every traversal a cycle");
        }
        if (!fromType.permitsEdge(edgeType, to.typeId())) {
            throw new IllegalArgumentException(
                    "edge " + edgeType + " from type '" + fromType.code() + "' to that type is not "
                            + "permitted (INV-AST-14). PRD-AST-004 makes permitted edges type-level "
                            + "configuration so a discovery source cannot invent topology.");
        }
        return new AssetRelationship(id, from.tenantId(), from.id(), to.id(), edgeType, provenance,
                validFrom);
    }

    /**
     * Closes this edge. {@code INV-AST-16}.
     *
     * <p>Note the absence of a delete: an edge is closed and retained. Closing twice is rejected rather than
     * ignored, because the second call carries a different instant and silently keeping the first would make
     * the history wrong in a way nothing surfaces.
     */
    public void supersede(Instant at) {
        Objects.requireNonNull(at, "the closure instant is required");
        if (validUntil != null) {
            throw new IllegalStateException(
                    "edge is already closed at " + validUntil + "; closing it again at " + at
                            + " would silently discard one of the two instants and make the temporal record "
                            + "wrong (INV-AST-16)");
        }
        if (at.isBefore(validFrom)) {
            throw new IllegalArgumentException(
                    "an edge cannot close before it opened; a negative validity window would make "
                            + "isCurrentAt() false for every instant, hiding the edge from history entirely");
        }
        this.validUntil = at;
    }

    /** True where this edge is part of the topology at {@code at}, rather than of its history. */
    public boolean isCurrentAt(Instant at) {
        return !validFrom.isAfter(at) && (validUntil == null || validUntil.isAfter(at));
    }

    public boolean isCurrent() {
        return validUntil == null;
    }

    /** Projects to the traversal record, which is deliberately narrower than this aggregate. */
    public AssetGraphTraversal.Edge asTraversalEdge() {
        return new AssetGraphTraversal.Edge(fromAssetId, toAssetId, edgeType.name(), validFrom, validUntil);
    }

    public UUID id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public UUID fromAssetId() {
        return fromAssetId;
    }

    public UUID toAssetId() {
        return toAssetId;
    }

    public AssetType.EdgeType edgeType() {
        return edgeType;
    }

    public Asset.DiscoveryProvenance provenance() {
        return provenance;
    }

    public Instant validFrom() {
        return validFrom;
    }

    public Optional<Instant> validUntil() {
        return Optional.ofNullable(validUntil);
    }
}
