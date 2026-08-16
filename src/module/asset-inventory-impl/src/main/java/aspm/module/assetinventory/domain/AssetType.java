package aspm.module.assetinventory.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The registry that makes ADR-009 work, per DOC-03 section 8.1.
 *
 * <p>"One {@code Asset} aggregate serves every inventory the original brief listed separately; the type
 * registry carries what differs between them." A new type — container image, mobile application, model
 * artifact, data store, operational technology device — "arrives without schema migration and without
 * touching ownership, permission, or deduplication paths. This is the return on ADR-009."
 *
 * <p><b>{@code INV-AST-01}: the type is immutable after asset creation.</b> DOC-03 section 8.1 states why in
 * full: changing it "would change its identity rule, its permitted edges, and its attribute schema
 * simultaneously — the asset would need re-identification, its edges revalidated, and its attributes
 * remapped. A type change is therefore modelled as <em>retire and recreate with a merge</em>." {@link Asset}
 * has no type mutator, which is that invariant's enforcement.
 */
public final class AssetType {

    /** The edge types of DOC-03 section 8.3. */
    public enum EdgeType {
        BUILDS,
        DEPLOYS_AS,
        EXPOSES,
        PUBLISHED_ON,
        DESCRIBED_BY,
        CONTAINS,
        DEPENDS_ON
    }

    /**
     * A permitted edge from this type to another. {@code INV-AST-14}.
     *
     * <p>Directional, because {@code REPOSITORY --BUILDS--> ARTIFACT} is meaningful and its reverse is not.
     * {@code INV-AST-15}'s many-to-many is about <em>cardinality</em>, not direction: no edge type constrains
     * either endpoint to one, and nothing here implies otherwise.
     */
    public record EdgeConstraint(EdgeType edgeType, UUID toTypeId) {

        public EdgeConstraint {
            Objects.requireNonNull(edgeType, "edgeType is required");
            Objects.requireNonNull(toTypeId, "toTypeId is required");
        }
    }

    public enum Lifecycle {
        ACTIVE,
        /** {@code INV-AST-03}: a type in use may be deprecated, never deleted. */
        DEPRECATED
    }

    private final UUID id;
    private final UUID tenantId;
    private final String code;
    private final Map<String, String> label;
    private final IdentityRule identityRule;
    private final Set<EdgeConstraint> permittedEdges;
    private final boolean networkReachable;
    private final boolean mayCarryFindings;
    private final Lifecycle lifecycleState;

    public AssetType(UUID id, UUID tenantId, String code, Map<String, String> label,
            IdentityRule identityRule, Set<EdgeConstraint> permittedEdges, boolean networkReachable,
            boolean mayCarryFindings, Lifecycle lifecycleState) {
        this.id = Objects.requireNonNull(id, "id is required");
        // Null tenant means a platform-supplied type, per DOC-03 section 8.1. Not a defect.
        this.tenantId = tenantId;
        this.code = Objects.requireNonNull(code, "code is required");
        this.label = Map.copyOf(Objects.requireNonNull(label, "label is required"));
        this.identityRule = Objects.requireNonNull(identityRule,
                "an identity rule is required; without one two reports of the same asset cannot be matched "
                        + "and finding history fragments (PRD-AST-006)");
        this.permittedEdges =
                Set.copyOf(Objects.requireNonNull(permittedEdges, "permittedEdges is required"));
        this.networkReachable = networkReachable;
        this.mayCarryFindings = mayCarryFindings;
        this.lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState is required");

        if (!code.matches("^[A-Z][A-Z0-9_]{0,62}$")) {
            throw new IllegalArgumentException(
                    "asset type code '" + code + "' is not a stable upper-snake identifier (INV-AST-04)");
        }
        if (label.isEmpty()) {
            throw new IllegalArgumentException("a label is required in at least one locale");
        }
        if (networkReachable && !mayCarryFindings) {
            throw new IllegalArgumentException(
                    "a network-reachable type that cannot carry findings is a contradiction: exposure "
                            + "classification exists so that a conflict becomes a finding (INV-AST-08), and a "
                            + "type that cannot carry one has nowhere to put it");
        }
    }

    /** True where {@code edgeType} to {@code toTypeId} is permitted from this type ({@code INV-AST-14}). */
    public boolean permitsEdge(EdgeType edgeType, UUID toTypeId) {
        return permittedEdges.contains(new EdgeConstraint(edgeType, toTypeId));
    }

    public UUID id() {
        return id;
    }

    public Optional<UUID> tenantId() {
        return Optional.ofNullable(tenantId);
    }

    public String code() {
        return code;
    }

    public Map<String, String> label() {
        return label;
    }

    public IdentityRule identityRule() {
        return identityRule;
    }

    public Set<EdgeConstraint> permittedEdges() {
        return permittedEdges;
    }

    /** {@code INV-AST-07}: exposure classification applies only where this is true. */
    public boolean isNetworkReachable() {
        return networkReachable;
    }

    public boolean mayCarryFindings() {
        return mayCarryFindings;
    }

    public Lifecycle lifecycleState() {
        return lifecycleState;
    }

    /** {@code INV-AST-03}: deprecation is the only retirement, and it accepts no new assets. */
    public boolean acceptsNewAssets() {
        return lifecycleState == Lifecycle.ACTIVE;
    }

    /**
     * The registry, validated as a set.
     *
     * <p>Edge constraints reference other type identifiers, so a dangling reference is only visible with the
     * whole registry in hand — the same reason {@code OrgNodeTypeCatalogue} validates as a set.
     */
    public static List<String> validateRegistry(Set<AssetType> registry) {
        Objects.requireNonNull(registry, "registry is required");
        List<String> findings = new java.util.ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();
        Set<UUID> ids = new LinkedHashSet<>();

        for (AssetType type : registry) {
            if (!codes.add(type.code())) {
                findings.add("INV-AST-04: asset type code '" + type.code() + "' is not unique");
            }
            if (!ids.add(type.id())) {
                findings.add("two asset types share id " + type.id());
            }
        }
        for (AssetType type : registry) {
            for (EdgeConstraint edge : type.permittedEdges()) {
                if (!ids.contains(edge.toTypeId())) {
                    findings.add("INV-AST-14: type '" + type.code() + "' permits edge " + edge.edgeType()
                            + " to type " + edge.toTypeId() + ", which is not in the registry. A dangling "
                            + "constraint would reject every edge a discovery source tries to create, and "
                            + "the graph would silently stay empty.");
                }
            }
        }
        return List.copyOf(findings);
    }
}
