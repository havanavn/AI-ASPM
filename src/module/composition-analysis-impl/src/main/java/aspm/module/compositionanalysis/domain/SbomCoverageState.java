package aspm.module.compositionanalysis.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Composition coverage for one asset. DOC-22 section 9, and {@code PRD-SBM-056} — which the document calls
 * <b>"the single most important requirement in the module"</b>.
 *
 * <p>"A project that has never submitted is not low-risk; it is unmeasured. Without an explicit state it is
 * absent from reporting entirely, and <b>absence reads as absence of problems</b>."
 *
 * <p>That is PP-1 at its sharpest. Everything in this class follows from refusing to let an unmeasured asset
 * disappear: {@link Status#NEVER_SUBMITTED} is a value rather than a null, {@link #of} requires an asset even
 * when there is no snapshot, and there is no factory that produces "no state".
 */
public final class SbomCoverageState {

    /** DOC-22 section 9.1. */
    public enum Status {
        /** Within threshold, quality above warning, ecosystems consistent with the declared stack. */
        CURRENT,
        /** Within threshold but quality below warning <b>or</b> ecosystem coverage incomplete. */
        PARTIAL,
        /** Beyond the freshness threshold derived from criticality. */
        STALE,
        /**
         * No snapshot has ever been accepted.
         *
         * <p>An explicit state, never an absence from reporting. An asset in this state has no findings, and a
         * report that omitted it would show a project with a clean dependency posture that has never been
         * looked at.
         */
        NEVER_SUBMITTED;

        /** Whether a figure derived from this asset's composition data may be presented without qualification. */
        public boolean presentableWithoutQualification() {
            return this == CURRENT;
        }

        /** Whether the asset belongs in an actionable queue ({@code PRD-SBM-058}). */
        public boolean requiresAction() {
            return this != CURRENT;
        }
    }

    private final UUID assetId;
    private final Optional<UUID> latestSnapshotId;
    private final Optional<Instant> latestSnapshotAt;
    private final ClosureAuthority.SnapshotQuality quality;
    private final Set<Ecosystem> coveredEcosystems;
    private final Set<Ecosystem> declaredStackEcosystems;
    private final Duration freshnessThreshold;
    private final UUID accountableOwnerId;

    private SbomCoverageState(UUID assetId, UUID latestSnapshotId, Instant latestSnapshotAt,
            ClosureAuthority.SnapshotQuality quality, Set<Ecosystem> coveredEcosystems,
            Set<Ecosystem> declaredStackEcosystems, Duration freshnessThreshold, UUID accountableOwnerId) {
        this.assetId = Objects.requireNonNull(assetId, "assetId is required");
        this.latestSnapshotId = Optional.ofNullable(latestSnapshotId);
        this.latestSnapshotAt = Optional.ofNullable(latestSnapshotAt);
        this.quality = Objects.requireNonNull(quality, "a quality is required");
        this.coveredEcosystems = Set.copyOf(
                Objects.requireNonNull(coveredEcosystems, "covered ecosystems are required, possibly empty"));
        this.declaredStackEcosystems = Set.copyOf(
                Objects.requireNonNull(declaredStackEcosystems, "the declared stack is required"));
        this.freshnessThreshold = Objects.requireNonNull(freshnessThreshold,
                "a freshness threshold is required; it derives from asset criticality (DOC-22 section 9.2)");
        this.accountableOwnerId = Objects.requireNonNull(accountableOwnerId,
                "an accountable owner is required (PRD-SBM-058). Coverage gaps close only when somebody is "
                        + "accountable, and an ownerless gap sits in a queue nobody reads.");
        if (this.latestSnapshotId.isPresent() != this.latestSnapshotAt.isPresent()) {
            throw new IllegalArgumentException("a snapshot reference needs its timestamp, and vice versa");
        }
    }

    /**
     * The state of an asset that has never submitted.
     *
     * <p>A named factory rather than a null or an empty {@code Optional} at the call site, because the whole
     * requirement is that this state exists and is reported. Code that handles it has to name it.
     */
    public static SbomCoverageState neverSubmitted(UUID assetId, Set<Ecosystem> declaredStack,
            Duration freshnessThreshold, UUID accountableOwnerId) {
        return new SbomCoverageState(assetId, null, null, ClosureAuthority.SnapshotQuality.REJECTED,
                Set.of(), declaredStack, freshnessThreshold, accountableOwnerId);
    }

    public static SbomCoverageState of(UUID assetId, UUID latestSnapshotId, Instant latestSnapshotAt,
            ClosureAuthority.SnapshotQuality quality, Set<Ecosystem> coveredEcosystems,
            Set<Ecosystem> declaredStack, Duration freshnessThreshold, UUID accountableOwnerId) {
        return new SbomCoverageState(assetId, latestSnapshotId, latestSnapshotAt, quality, coveredEcosystems,
                declaredStack, freshnessThreshold, accountableOwnerId);
    }

    /** Derived from the state, never stored as a settable field. */
    public Status statusAt(Instant now) {
        Objects.requireNonNull(now, "the evaluation instant is required");
        if (latestSnapshotAt.isEmpty()) {
            return Status.NEVER_SUBMITTED;
        }
        // Staleness first: a stale snapshot of perfect quality is still stale, and reporting it PARTIAL would
        // understate the gap.
        if (Duration.between(latestSnapshotAt.get(), now).compareTo(freshnessThreshold) > 0) {
            return Status.STALE;
        }
        if (quality != ClosureAuthority.SnapshotQuality.ABOVE_WARNING
                || !coveredEcosystems.containsAll(declaredStackEcosystems)) {
            return Status.PARTIAL;
        }
        return Status.CURRENT;
    }

    /** The ecosystems in the declared stack that this snapshot did not cover. Drives {@code PRD-SBM-055}. */
    public Set<Ecosystem> uncoveredEcosystems() {
        Set<Ecosystem> missing = new java.util.LinkedHashSet<>(declaredStackEcosystems);
        missing.removeAll(coveredEcosystems);
        return Set.copyOf(missing);
    }

    /**
     * {@code PRD-SBM-060}: coverage is not improvable by any action other than acquiring data.
     *
     * <p>Excluding an ecosystem from the declared stack would raise this asset from {@code PARTIAL} to
     * {@code CURRENT} without a single additional component being examined. So the operation refuses unless the
     * ecosystem was actually covered — the same shape as {@code PRD-RSK-028}'s refusal to exclude unmeasured
     * assets from a coverage ratio.
     */
    public SbomCoverageState withEcosystemRemovedFromDeclaredStack(Ecosystem ecosystem) {
        Objects.requireNonNull(ecosystem, "an ecosystem is required");
        if (!coveredEcosystems.contains(ecosystem)) {
            throw new IllegalArgumentException(
                    "removing " + ecosystem + " from the declared stack would raise this asset's coverage "
                            + "status without acquiring any data, which PRD-SBM-060 prohibits. The cheapest "
                            + "route to high coverage must not be exclusion, or the metric inverts.");
        }
        Set<Ecosystem> reduced = new java.util.LinkedHashSet<>(declaredStackEcosystems);
        reduced.remove(ecosystem);
        return new SbomCoverageState(assetId, latestSnapshotId.orElse(null), latestSnapshotAt.orElse(null),
                quality, coveredEcosystems, reduced, freshnessThreshold, accountableOwnerId);
    }

    /**
     * The qualifier that must accompany every figure derived from this data. {@code PRD-SBM-057}.
     *
     * <p>"'Twelve critical, from data three days old, covering seventy percent of the portfolio' is a materially
     * different statement from 'twelve critical', and only the first is honest."
     *
     * <p>Materialized with the figure rather than computed at presentation ({@code CON-PLT-028}), which is what
     * "makes omitting it require deliberate effort".
     */
    public String qualifier(Instant now) {
        Status status = statusAt(now);
        if (status == Status.NEVER_SUBMITTED) {
            return "no SBOM has ever been submitted for this asset — it is unmeasured, not clean "
                    + "(PRD-SBM-056)";
        }
        long ageDays = Duration.between(latestSnapshotAt.orElseThrow(), now).toDays();
        StringBuilder qualifier = new StringBuilder("from a snapshot ")
                .append(ageDays).append(" day(s) old, covering ")
                .append(coveredEcosystems.size()).append(" of ")
                .append(declaredStackEcosystems.size()).append(" declared ecosystem(s)");
        if (status != Status.CURRENT) {
            qualifier.append(" — coverage is ").append(status);
        }
        if (!uncoveredEcosystems().isEmpty()) {
            qualifier.append("; not covered: ").append(uncoveredEcosystems());
        }
        return qualifier.toString();
    }

    public UUID assetId() {
        return assetId;
    }

    public Optional<UUID> latestSnapshotId() {
        return latestSnapshotId;
    }

    public Optional<Instant> latestSnapshotAt() {
        return latestSnapshotAt;
    }

    public ClosureAuthority.SnapshotQuality quality() {
        return quality;
    }

    public Set<Ecosystem> coveredEcosystems() {
        return coveredEcosystems;
    }

    public Set<Ecosystem> declaredStackEcosystems() {
        return declaredStackEcosystems;
    }

    public Duration freshnessThreshold() {
        return freshnessThreshold;
    }

    public UUID accountableOwnerId() {
        return accountableOwnerId;
    }
}
