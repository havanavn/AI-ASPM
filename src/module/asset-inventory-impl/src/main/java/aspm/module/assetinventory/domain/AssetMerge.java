package aspm.module.assetinventory.domain;

import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.PrincipalId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Asset merge, per DOC-03 section 8.6.
 *
 * <p><b>{@code INV-AST-24} is the one that must not be convenient.</b> DOC-03 section 8.6:
 *
 * <blockquote>Two assets with different owners merging into one is an ownership decision, not a data
 * operation. Automatically taking the survivor's owner silently transfers accountability for the absorbed
 * asset's findings, which is exactly the invisible accountability decay the platform exists to prevent.
 * </blockquote>
 *
 * <p>So {@link #prepare} <b>returns a conflict rather than a merge</b> where owners differ, and the only way
 * past it is {@link #prepareWithOwnerResolution}, which requires a principal to name the owner explicitly.
 * There is no flag, no "prefer survivor" option, and no default — a default is how the decision stops being
 * made.
 */
public final class AssetMerge {

    public enum Reason {
        DUPLICATE_IDENTITY,
        /** An identity rule improved, so two assets turn out to be one (DOC-03 section 8.5). */
        RULE_VERSION_CHANGE,
        MANUAL
    }

    /** The outcome of preparing a merge. */
    public sealed interface Preparation {

        /** Ready to execute. */
        record Ready(AssetMerge merge) implements Preparation {}

        /**
         * Blocked: the absorbed assets have owners that disagree.
         *
         * <p>{@code INV-AST-24}. Carries every distinct owner so the resolving principal chooses from the
         * actual set rather than being asked a yes/no question about one of them.
         */
        record OwnerConflict(Set<OrgNodeId> distinctOwners, String explanation) implements Preparation {}
    }

    /** How one conflicting attribute was resolved, retained so the merge is reversible. */
    public record AttributeResolution(String attributeKey, String chosenValue, String fromAssetId) {

        public AttributeResolution {
            Objects.requireNonNull(attributeKey, "attributeKey is required");
        }
    }

    /** Enough state to undo the merge within its window ({@code INV-AST-23}). */
    public record Reversal(
            Map<UUID, OrgNodeId> ownershipBeforeMerge,
            Map<UUID, Asset.Lifecycle> lifecycleBeforeMerge,
            Instant reversibleUntil) {

        public Reversal {
            ownershipBeforeMerge = Map.copyOf(
                    Objects.requireNonNull(ownershipBeforeMerge, "prior ownership is required"));
            lifecycleBeforeMerge = Map.copyOf(
                    Objects.requireNonNull(lifecycleBeforeMerge, "prior lifecycle is required"));
            Objects.requireNonNull(reversibleUntil, "the reversal window end is required");
        }

        public boolean isReversibleAt(Instant at) {
            return at.isBefore(reversibleUntil);
        }
    }

    private final UUID id;
    private final UUID survivingAssetId;
    private final List<UUID> absorbedAssetIds;
    private final Reason reason;
    private final OrgNodeId resolvedOwner;
    private final PrincipalId ownerResolvedBy;
    private final List<AttributeResolution> attributeResolutions;
    private final PrincipalId performedBy;
    private final Instant performedAt;
    private final Reversal reversal;

    private AssetMerge(UUID id, UUID survivingAssetId, List<UUID> absorbedAssetIds, Reason reason,
            OrgNodeId resolvedOwner, PrincipalId ownerResolvedBy,
            List<AttributeResolution> attributeResolutions, PrincipalId performedBy, Instant performedAt,
            Reversal reversal) {
        this.id = id;
        this.survivingAssetId = survivingAssetId;
        this.absorbedAssetIds = List.copyOf(absorbedAssetIds);
        this.reason = reason;
        this.resolvedOwner = resolvedOwner;
        this.ownerResolvedBy = ownerResolvedBy;
        this.attributeResolutions = List.copyOf(attributeResolutions);
        this.performedBy = performedBy;
        this.performedAt = performedAt;
        this.reversal = reversal;
    }

    /**
     * Prepares a merge, refusing where owners conflict.
     *
     * @param reversibleFor how long the merge may be reversed ({@code INV-AST-23})
     */
    public static Preparation prepare(UUID id, Asset survivor, List<Asset> absorbed, Reason reason,
            List<AttributeResolution> attributeResolutions, PrincipalId performedBy, Instant at,
            java.time.Duration reversibleFor) {
        validateInputs(survivor, absorbed, performedBy, at, reversibleFor);

        Set<OrgNodeId> owners = new LinkedHashSet<>();
        survivor.owningNodeId().ifPresent(owners::add);
        absorbed.forEach(a -> a.owningNodeId().ifPresent(owners::add));

        if (owners.size() > 1) {
            return new Preparation.OwnerConflict(Set.copyOf(owners),
                    "the assets being merged have " + owners.size() + " distinct owners. Two assets with "
                            + "different owners merging into one is an ownership DECISION, not a data "
                            + "operation: automatically taking the survivor's owner silently transfers "
                            + "accountability for the absorbed asset's findings, which is the invisible "
                            + "accountability decay the platform exists to prevent (INV-AST-24). Resolve it "
                            + "explicitly with prepareWithOwnerResolution.");
        }

        return new Preparation.Ready(new AssetMerge(id, survivor.id(), absorbed.stream().map(Asset::id).toList(),
                reason, owners.stream().findFirst().orElse(null), null, attributeResolutions, performedBy, at,
                reversalOf(survivor, absorbed, at, reversibleFor)));
    }

    /**
     * Prepares a merge with the owner named explicitly, which is the only way past an owner conflict.
     *
     * @param resolvedOwner must be one of the conflicting owners. Naming a third node would be a transfer
     *     disguised as a merge, and a transfer has its own event and its own audit trail
     * @param ownerResolvedBy the principal accountable for the decision
     */
    public static Preparation prepareWithOwnerResolution(UUID id, Asset survivor, List<Asset> absorbed,
            Reason reason, List<AttributeResolution> attributeResolutions, OrgNodeId resolvedOwner,
            PrincipalId ownerResolvedBy, PrincipalId performedBy, Instant at,
            java.time.Duration reversibleFor) {
        validateInputs(survivor, absorbed, performedBy, at, reversibleFor);
        Objects.requireNonNull(resolvedOwner, "the resolved owner is required");
        Objects.requireNonNull(ownerResolvedBy,
                "the principal resolving ownership is required; an unattributed ownership decision is the "
                        + "accountability decay INV-AST-24 exists to prevent");

        Set<OrgNodeId> owners = new LinkedHashSet<>();
        survivor.owningNodeId().ifPresent(owners::add);
        absorbed.forEach(a -> a.owningNodeId().ifPresent(owners::add));

        if (!owners.isEmpty() && !owners.contains(resolvedOwner)) {
            throw new IllegalArgumentException(
                    "the resolved owner is not one of the merging assets' owners. Naming a third node is a "
                            + "transfer disguised as a merge, and a transfer has its own event and audit trail "
                            + "(AssetOwnershipTransferred).");
        }

        return new Preparation.Ready(new AssetMerge(id, survivor.id(),
                absorbed.stream().map(Asset::id).toList(), reason, resolvedOwner, ownerResolvedBy,
                attributeResolutions, performedBy, at, reversalOf(survivor, absorbed, at, reversibleFor)));
    }

    private static void validateInputs(Asset survivor, List<Asset> absorbed, PrincipalId performedBy,
            Instant at, java.time.Duration reversibleFor) {
        Objects.requireNonNull(survivor, "a surviving asset is required");
        Objects.requireNonNull(absorbed, "the absorbed assets are required");
        Objects.requireNonNull(performedBy, "the performing principal is required");
        Objects.requireNonNull(at, "the merge instant is required");
        Objects.requireNonNull(reversibleFor, "a reversal window is required (INV-AST-23)");
        if (absorbed.isEmpty()) {
            throw new IllegalArgumentException("a merge with nothing absorbed is not a merge");
        }
        if (absorbed.stream().anyMatch(a -> a.id().equals(survivor.id()))) {
            throw new IllegalArgumentException("an asset cannot absorb itself");
        }
        if (absorbed.stream().anyMatch(a -> !a.tenantId().equals(survivor.tenantId()))) {
            throw new IllegalArgumentException(
                    "all merging assets must be in one tenant; a cross-tenant merge would move findings "
                            + "across the isolation boundary (INV-TEN-02)");
        }
        if (reversibleFor.isNegative() || reversibleFor.isZero()) {
            throw new IllegalArgumentException(
                    "the reversal window must be positive; INV-AST-23 makes merge reversible for a bounded "
                            + "period, and a zero window makes an irreversible operation look reversible");
        }
    }

    private static Reversal reversalOf(Asset survivor, List<Asset> absorbed, Instant at,
            java.time.Duration reversibleFor) {
        Map<UUID, OrgNodeId> ownership = new LinkedHashMap<>();
        Map<UUID, Asset.Lifecycle> lifecycles = new LinkedHashMap<>();
        survivor.owningNodeId().ifPresent(n -> ownership.put(survivor.id(), n));
        lifecycles.put(survivor.id(), survivor.lifecycleState());
        for (Asset a : absorbed) {
            a.owningNodeId().ifPresent(n -> ownership.put(a.id(), n));
            lifecycles.put(a.id(), a.lifecycleState());
        }
        return new Reversal(ownership, lifecycles, at.plus(reversibleFor));
    }

    /**
     * What the caller must transfer to the survivor. {@code INV-AST-21}: nothing is discarded.
     *
     * <p>Returned as an explicit checklist rather than performed here, because findings, edges and external
     * identifiers live in other aggregates and modules — {@code CON-PLT-015} forbids reaching into them. A
     * checklist that a caller can assert against is the honest shape; a method claiming to have moved
     * everything would be claiming authority it does not have.
     */
    public List<String> transferObligations() {
        return List.of(
                "findings: reassign every finding of every absorbed asset to the survivor (INV-AST-21)",
                "edges: re-point every current edge, closing and reopening rather than mutating (INV-AST-16)",
                "external identifiers: move to the survivor, resolving INV-AST-11 duplicates explicitly",
                "history: retain, and set a redirect on each absorbed asset (INV-AST-22)");
    }

    /**
     * {@code INV-AST-22}: absorbed assets become {@code RETIRED} with a redirect, never deleted.
     *
     * <p>"So historical references resolve" — a finding, report or audit event naming an absorbed asset must
     * still lead somewhere, and deletion would make every such reference dangle.
     */
    public List<UUID> assetsToRetireWithRedirect() {
        return absorbedAssetIds;
    }

    public UUID id() {
        return id;
    }

    public UUID survivingAssetId() {
        return survivingAssetId;
    }

    public List<UUID> absorbedAssetIds() {
        return absorbedAssetIds;
    }

    public Reason reason() {
        return reason;
    }

    public Optional<OrgNodeId> resolvedOwner() {
        return Optional.ofNullable(resolvedOwner);
    }

    /** Present only where an owner conflict was explicitly resolved, which is what makes it auditable. */
    public Optional<PrincipalId> ownerResolvedBy() {
        return Optional.ofNullable(ownerResolvedBy);
    }

    public List<AttributeResolution> attributeResolutions() {
        return attributeResolutions;
    }

    public PrincipalId performedBy() {
        return performedBy;
    }

    public Instant performedAt() {
        return performedAt;
    }

    public Reversal reversal() {
        return reversal;
    }
}
