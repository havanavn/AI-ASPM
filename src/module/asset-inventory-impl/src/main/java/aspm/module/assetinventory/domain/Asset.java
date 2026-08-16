package aspm.module.assetinventory.domain;

import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.ScopeDescriptor;
import aspm.sharedkernel.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One unit of technical existence, per DOC-03 section 8.2.
 *
 * <p>Two invariants here are structural rather than checked, and both are among the twelve DOC-03 section 19
 * marks as unrecoverable if violated.
 *
 * <p><b>{@code INV-AST-05} — exactly one owner, or none while unclaimed.</b> Ownership is a single field with
 * a single mutator. There is no collection, no {@code addOwner}, and no bulk path that takes a list — so
 * "more than one owner" is not representable through any write path, which is what the invariant demands
 * "through any path including bulk and import".
 *
 * <p><b>{@code INV-AST-12} — {@code lastConfirmedAt} advances only on discovery evidence.</b> The field has
 * no setter. {@link #confirmSeen} requires a {@link DiscoveryProvenance}, and every other mutator on this
 * class leaves it untouched. DOC-03 section 8.2: "coverage must not be improvable by editing. If a manual
 * save advanced it, a stale asset could be made to look fresh without any evidence that it still exists —
 * which is PP-1 violated through a field nobody thinks of as a metric."
 */
public final class Asset {

    /**
     * {@code DISCOVERED} is deliberately distinct from {@code ACTIVE}.
     *
     * <p>DOC-03 section 8.2: an asset asserted by a scanner but not yet confirmed "is real enough to carry
     * findings and not yet trustworthy enough to drive posture reporting. Conflating them either inflates the
     * inventory with scanner artifacts or discards genuine discoveries."
     */
    public enum Lifecycle {
        DISCOVERED,
        ACTIVE,
        DEPRECATED,
        /** Excluded from posture metrics but retains findings and history ({@code INV-AST-09}). */
        RETIRED;

        boolean permits(Lifecycle next) {
            return switch (this) {
                case DISCOVERED -> next == ACTIVE || next == RETIRED;
                case ACTIVE -> next == DEPRECATED || next == RETIRED;
                case DEPRECATED -> next == ACTIVE || next == RETIRED;
                case RETIRED -> false;
            };
        }
    }

    /** Where an assertion about this asset came from. */
    public record DiscoveryProvenance(String sourceSystem, String sourceReference, Instant observedAt) {

        public DiscoveryProvenance {
            Objects.requireNonNull(sourceSystem, "the source system is required");
            Objects.requireNonNull(observedAt, "the observation instant is required");
            if (sourceSystem.isBlank()) {
                throw new IllegalArgumentException(
                        "a blank source system makes provenance unauditable, and INV-AST-12 depends on "
                                + "provenance being the only thing that can advance a coverage signal");
            }
        }
    }

    /** An identifier for this asset in an external system ({@code INV-AST-11}). */
    public record ExternalIdentifier(String sourceSystem, String value) {

        public ExternalIdentifier {
            Objects.requireNonNull(sourceSystem, "the source system is required");
            Objects.requireNonNull(value, "the identifier value is required");
        }
    }

    public sealed interface Event {
        record Discovered(UUID assetId, DiscoveryProvenance provenance) implements Event {}

        record Activated(UUID assetId) implements Event {}

        record OwnershipAssigned(UUID assetId, OrgNodeId nodeId) implements Event {}

        record OwnershipTransferred(UUID assetId, OrgNodeId from, OrgNodeId to) implements Event {}

        record ExposureDeclared(UUID assetId, ExposureClassification.Level level) implements Event {}

        /** {@code INV-AST-08}: raised so the asset enters the exposure conflict queue. */
        record ExposureConflictDetected(
                UUID assetId, ExposureClassification.Level declared,
                ExposureClassification.Level observed, String source) implements Event {}

        record LifecycleDeprecated(UUID assetId) implements Event {}

        record Retired(UUID assetId, String reason) implements Event {}
    }

    private final UUID id;
    private final TenantId tenantId;
    private final UUID typeId;
    private final boolean typeIsNetworkReachable;
    private final String normalizedIdentity;
    private final int identityRuleVersion;

    private String displayName;
    private OrgNodeId owningNodeId;
    private ScopeDescriptor scope;
    private ExposureClassification exposure;
    private Lifecycle lifecycleState = Lifecycle.DISCOVERED;
    private final Set<ExternalIdentifier> externalIdentifiers = new LinkedHashSet<>();
    private final Instant firstSeenAt;
    private Instant lastConfirmedAt;
    private boolean hasEverCarriedFinding;
    private final List<Event> pending = new ArrayList<>();

    private Asset(UUID id, TenantId tenantId, UUID typeId, boolean typeIsNetworkReachable,
            String normalizedIdentity, int identityRuleVersion, String displayName,
            DiscoveryProvenance provenance) {
        this.id = id;
        this.tenantId = tenantId;
        this.typeId = typeId;
        this.typeIsNetworkReachable = typeIsNetworkReachable;
        this.normalizedIdentity = normalizedIdentity;
        this.identityRuleVersion = identityRuleVersion;
        this.displayName = displayName;
        this.firstSeenAt = provenance.observedAt();
        this.lastConfirmedAt = provenance.observedAt();
    }

    /**
     * Discovers an asset.
     *
     * <p>Requires provenance: an asset with no source is an assertion nobody made, and it would enter the
     * inventory with a {@code lastConfirmedAt} that {@code INV-AST-12} could never legitimately advance.
     *
     * @param normalizedIdentity the natural key after {@link IdentityRule} normalization
     * @param identityRuleVersion the rule version that produced it, so a later re-resolution is traceable
     */
    public static Asset discover(UUID id, TenantId tenantId, UUID typeId, boolean typeIsNetworkReachable,
            String normalizedIdentity, int identityRuleVersion, String displayName,
            DiscoveryProvenance provenance) {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(typeId, "typeId is required");
        Objects.requireNonNull(provenance, "discovery provenance is required");
        if (normalizedIdentity == null || normalizedIdentity.isBlank()) {
            throw new IllegalArgumentException(
                    "a normalized identity is required; without one the asset cannot be deduplicated and "
                            + "its finding history fragments on every re-report (PRD-AST-006)");
        }
        if (identityRuleVersion < 1) {
            throw new IllegalArgumentException(
                    "the identity rule version is required and monotonic from 1; without it a rule "
                            + "improvement cannot tell which assets it must re-resolve (DOC-03 section 8.5)");
        }
        Asset asset = new Asset(id, tenantId, typeId, typeIsNetworkReachable, normalizedIdentity,
                identityRuleVersion, displayName == null ? normalizedIdentity : displayName, provenance);
        asset.pending.add(new Event.Discovered(id, provenance));
        return asset;
    }

    // ------------------------------------------------------------------ INV-AST-05

    /**
     * Assigns or transfers ownership. {@code INV-AST-05}.
     *
     * <p>One field, one mutator, no collection. "More than one owner" is not representable, which is what the
     * invariant requires "through any path including bulk and import" — a bulk importer calling this in a
     * loop still cannot produce two owners for one asset.
     *
     * @param descriptor the scope resolved <b>at assignment</b>, per DOC-03 section 8.2. Immutable
     *     thereafter, so a later reorganization does not rewrite this asset's recorded scope
     */
    public void assignOwnership(OrgNodeId nodeId, ScopeDescriptor descriptor) {
        Objects.requireNonNull(nodeId, "an owning node is required; use releaseOwnership() to unclaim");
        Objects.requireNonNull(descriptor, "a scope descriptor resolved at assignment is required");
        if (!descriptor.owningNodeId().equals(nodeId)) {
            throw new IllegalArgumentException(
                    "the descriptor's owning node does not match the node being assigned. A descriptor that "
                            + "disagrees with the ownership field would make scope-based authorization and "
                            + "ownership queries return different answers for the same asset.");
        }
        requireNotRetired("ownership assignment");

        OrgNodeId previous = this.owningNodeId;
        this.owningNodeId = nodeId;
        this.scope = descriptor;
        pending.add(previous == null
                ? new Event.OwnershipAssigned(id, nodeId)
                : new Event.OwnershipTransferred(id, previous, nodeId));
    }

    /** Returns the asset to {@code UNCLAIMED}. Ownership is absent, never multiple. */
    public void releaseOwnership() {
        requireNotRetired("ownership release");
        this.owningNodeId = null;
        this.scope = null;
    }

    public boolean isUnclaimed() {
        return owningNodeId == null;
    }

    public Optional<OrgNodeId> owningNodeId() {
        return Optional.ofNullable(owningNodeId);
    }

    public Optional<ScopeDescriptor> scope() {
        return Optional.ofNullable(scope);
    }

    // ------------------------------------------------------------------ INV-AST-07, INV-AST-08

    /**
     * Declares exposure. {@code INV-AST-07} restricts this to network-reachable types.
     *
     * <p>Rejected rather than ignored for a non-reachable type: silently accepting a declaration that can
     * never be observed would put a value into scoring that nothing can ever contradict.
     */
    public void declareExposure(ExposureClassification.Level level, aspm.sharedkernel.PrincipalId by,
            Instant at) {
        requireNetworkReachable();
        requireNotRetired("exposure declaration");
        this.exposure = exposure == null
                ? ExposureClassification.declare(level, by, at)
                : exposure.redeclare(level, by, at);
        pending.add(new Event.ExposureDeclared(id, level));
    }

    /**
     * Records an observation. {@code INV-AST-08}: <b>the declaration is not corrected.</b>
     *
     * <p>Raises {@link Event.ExposureConflictDetected} where the observation is more exposed, which is what
     * puts the asset in the conflict queue. The declaration is left exactly as it was, because the
     * discrepancy is itself the finding.
     */
    public void observeExposure(ExposureClassification.Level level, String source, Instant at) {
        requireNetworkReachable();
        if (exposure == null) {
            throw new IllegalStateException(
                    "exposure must be declared before it can be observed; a conflict is defined against a "
                            + "declaration and there is nothing to conflict with");
        }
        ExposureClassification.Level declaredBefore = exposure.declared();
        this.exposure = exposure.observe(level, source, at);

        if (exposure.conflict()) {
            pending.add(new Event.ExposureConflictDetected(id, declaredBefore, level, source));
        }
        // Note what does not happen here: no assignment to the declared level. An asset declared internal
        // but observed public is a high-severity finding, and correcting the declaration would erase it.
    }

    public Optional<ExposureClassification> exposure() {
        return Optional.ofNullable(exposure);
    }

    // ------------------------------------------------------------------ INV-AST-12

    /**
     * Advances {@code lastConfirmedAt} on discovery evidence. The <b>only</b> way it advances.
     *
     * <p>Requires provenance, so there is no parameterless "touch". Never moves backwards: an older
     * observation arriving late is not evidence that the asset was last seen earlier than it was.
     */
    public void confirmSeen(DiscoveryProvenance provenance) {
        Objects.requireNonNull(provenance, "discovery provenance is required (INV-AST-12)");
        requireNotRetired("confirmation");
        if (provenance.observedAt().isAfter(lastConfirmedAt)) {
            this.lastConfirmedAt = provenance.observedAt();
        }
    }

    /**
     * A manual edit. Deliberately does <b>not</b> touch {@code lastConfirmedAt}.
     *
     * <p>This method exists partly to be pointed at: it is the write path {@code INV-AST-12} is about, and a
     * test asserts that calling it leaves the coverage signal unmoved.
     */
    public void editDisplayName(String newDisplayName) {
        requireNotRetired("edit");
        if (newDisplayName == null || newDisplayName.isBlank()) {
            throw new IllegalArgumentException("a display name is required");
        }
        this.displayName = newDisplayName.strip();
    }

    public Instant lastConfirmedAt() {
        return lastConfirmedAt;
    }

    public Instant firstSeenAt() {
        return firstSeenAt;
    }

    // ------------------------------------------------------------------ INV-AST-10, INV-AST-11

    /**
     * Adds an external identifier. {@code INV-AST-11} makes these unique per source per tenant.
     *
     * <p>Uniqueness across assets cannot be enforced here — it is a set-level property the aggregate cannot
     * see — so it is a unique index in the schema plus a duplicate-resolution path. What this method enforces
     * is the weaker local rule: one value per source system on <em>this</em> asset, because two values from
     * one source is itself an unresolved duplicate rather than a permitted state.
     */
    public void addExternalIdentifier(ExternalIdentifier identifier) {
        Objects.requireNonNull(identifier, "identifier is required");
        requireNotRetired("external identifier assignment");
        boolean sourceAlreadyPresent = externalIdentifiers.stream()
                .anyMatch(e -> e.sourceSystem().equals(identifier.sourceSystem())
                        && !e.value().equals(identifier.value()));
        if (sourceAlreadyPresent) {
            throw new IllegalStateException(
                    "asset already carries a different identifier from source '" + identifier.sourceSystem()
                            + "'. Two values from one source is a duplicate to resolve, not a permitted "
                            + "state (INV-AST-11).");
        }
        externalIdentifiers.add(identifier);
    }

    public Set<ExternalIdentifier> externalIdentifiers() {
        return Set.copyOf(externalIdentifiers);
    }

    /** Records that this asset has carried a finding, which makes it undeletable ({@code INV-AST-10}). */
    public void recordFindingCarried() {
        this.hasEverCarriedFinding = true;
    }

    /**
     * {@code INV-AST-10}: an asset that has ever carried a finding may not be hard-deleted.
     *
     * <p>Exposed as a query rather than enforced by a {@code delete} method that throws, because this
     * aggregate has no delete method at all — deletion is a repository concern, and the schema withholds the
     * grant. This is the domain's half of a control whose other half is {@code ON DELETE RESTRICT}.
     */
    public boolean mayBeHardDeleted() {
        return !hasEverCarriedFinding;
    }

    // ------------------------------------------------------------------ lifecycle

    public void activate() {
        transitionTo(Lifecycle.ACTIVE);
        pending.add(new Event.Activated(id));
    }

    public void deprecate() {
        transitionTo(Lifecycle.DEPRECATED);
        pending.add(new Event.LifecycleDeprecated(id));
    }

    public void retire(String reason) {
        transitionTo(Lifecycle.RETIRED);
        pending.add(new Event.Retired(id, Objects.requireNonNull(reason, "a retirement reason is required")));
    }

    /**
     * {@code INV-AST-09}: a retired asset is excluded from posture metrics.
     *
     * <p>A separate question from whether its findings and history survive — they do, and nothing here
     * removes them. Conflating "counts towards posture" with "exists" is how retiring an asset silently
     * deletes its history.
     */
    public boolean countsTowardsPosture() {
        return lifecycleState == Lifecycle.ACTIVE || lifecycleState == Lifecycle.DEPRECATED;
    }

    /** {@code DISCOVERED} carries findings but does not drive posture reporting. */
    public boolean carriesFindings() {
        return true;
    }

    public Lifecycle lifecycleState() {
        return lifecycleState;
    }

    public UUID id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public UUID typeId() {
        return typeId;
    }

    public String normalizedIdentity() {
        return normalizedIdentity;
    }

    public int identityRuleVersion() {
        return identityRuleVersion;
    }

    public String displayName() {
        return displayName;
    }

    public List<Event> drainEvents() {
        List<Event> drained = List.copyOf(pending);
        pending.clear();
        return drained;
    }

    private void transitionTo(Lifecycle next) {
        if (!lifecycleState.permits(next)) {
            throw new IllegalStateException(
                    "asset lifecycle transition " + lifecycleState + " -> " + next + " is not permitted. "
                            + "RETIRED is terminal because a retired asset retains its findings and history "
                            + "(INV-AST-09) and un-retiring would make that history ambiguous.");
        }
        lifecycleState = next;
    }

    private void requireNotRetired(String operation) {
        if (lifecycleState == Lifecycle.RETIRED) {
            throw new IllegalStateException(operation + " is not permitted on a RETIRED asset");
        }
    }

    private void requireNetworkReachable() {
        if (!typeIsNetworkReachable) {
            throw new IllegalStateException(
                    "exposure classification applies only where the asset type is network reachable "
                            + "(INV-AST-07). Accepting a declaration that can never be observed would put a "
                            + "value into scoring that nothing can ever contradict.");
        }
    }
}
