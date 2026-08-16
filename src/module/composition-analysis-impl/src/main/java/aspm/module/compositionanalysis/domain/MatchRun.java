package aspm.module.compositionanalysis.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A match run. Aggregate root; DOC-03 section 11, DOC-09 section 11.1, DOC-22 section 7.
 *
 * <p>Carries {@code INV-SBM-07} (idempotency), {@code INV-SBM-09} (coverage confirmation),
 * {@code INV-SBM-10} (lease expiry), {@code INV-SBM-11} (queue class precedence) and {@code INV-SBM-12}
 * (recorded versions).
 *
 * <h2>{@code INV-SBM-10} — leases expire, and liveness is not a heartbeat</h2>
 *
 * <p>{@code PRD-SBM-048}: "Container termination is abrupt and normal. Without lease expiry, a terminated worker
 * leaves the run claimed and the batch stalls silently."
 *
 * <p><b>Silently</b> is the operative word. A stalled batch produces no error, no alert, and a coverage timeline
 * that simply stops advancing — which resembles a stable estate (PP-9). {@link #leaseExpired} is therefore
 * computed from the clock rather than from a flag some sweep must set, the same shape as the external assessor
 * grant's validity in prompt 10.
 */
public final class MatchRun {

    /** DOC-22 section 7.1. {@code INV-SBM-11}: interactive runs are never queued behind batch runs. */
    public enum QueueClass {
        /** A new snapshot, a user re-evaluation, an exploitability statement change. */
        INTERACTIVE(0),
        /**
         * A known-exploited catalogue update. {@code PRD-SBM-046} gives it priority over an ordinary sweep,
         * because {@code NFR-SBM-003} sets a six-hour visibility budget and a KEV update queued behind a full
         * portfolio sweep would exceed it.
         */
        BATCH_ELEVATED(1),
        /** An intelligence update, a matcher version change, scheduled reconciliation. */
        BATCH(2);

        private final int precedence;

        QueueClass(int precedence) {
            this.precedence = precedence;
        }

        /** Lower is sooner. {@code INV-SBM-11} is this ordering being total and INTERACTIVE being first. */
        public int precedence() {
            return precedence;
        }
    }

    public enum State {
        QUEUED,
        LEASED,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED_NO_CHANGE;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == SKIPPED_NO_CHANGE;
        }
    }

    /**
     * The idempotency key of {@code INV-SBM-07} and {@code PRD-SBM-047}.
     *
     * <p>Derived from snapshot content hash, intelligence version, matcher version and canonicalization version.
     * All four, because a change to any of them changes the result: "Retry is inevitable. Without idempotency a
     * retried run produces a second set of candidates, and deduplication then has to reconcile them — work that
     * need not exist."
     *
     * <p>Also the input to {@code PRD-SBM-050}'s skip decision: identical key since the last successful run
     * means nothing has changed, and the run is recorded as {@code SKIPPED_NO_CHANGE} rather than not recorded
     * at all.
     */
    public record IdempotencyKey(String snapshotContentHash, String intelligenceVersion, int matcherVersion,
            int canonicalizationVersion) {

        public IdempotencyKey {
            Objects.requireNonNull(snapshotContentHash, "the snapshot content hash is required");
            Objects.requireNonNull(intelligenceVersion, "the intelligence version is required (INV-SBM-12)");
            if (matcherVersion < 1 || canonicalizationVersion < 1) {
                throw new IllegalArgumentException(
                        "matcher and canonicalization versions are required (INV-SBM-12, PRD-SBM-036). "
                                + "Without them a change in results cannot be distinguished from a change in "
                                + "the estate.");
            }
        }
    }

    private final UUID id;
    private final UUID snapshotId;
    private final QueueClass queueClass;
    private final IdempotencyKey idempotencyKey;
    private final Instant queuedAt;

    private State state = State.QUEUED;
    private UUID leaseHolderId;
    private Instant leaseExpiresAt;
    private int attemptCount;
    private boolean coverageConfirmed;
    private Instant startedAt;
    private Instant finishedAt;
    private String failureReason;
    private Set<Ecosystem> coveredEcosystems = Set.of();

    public MatchRun(UUID id, UUID snapshotId, QueueClass queueClass, IdempotencyKey idempotencyKey,
            Instant queuedAt) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.snapshotId = Objects.requireNonNull(snapshotId, "snapshotId is required");
        this.queueClass = Objects.requireNonNull(queueClass, "a queue class is required (INV-SBM-11)");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey,
                "an idempotency key is required (INV-SBM-07). Retry is inevitable.");
        this.queuedAt = Objects.requireNonNull(queuedAt, "queuedAt is required");
    }

    /**
     * Acquires the lease.
     *
     * @param leaseDuration bounded. An unbounded lease is the stall {@code PRD-SBM-048} describes, with extra
     *     steps
     */
    public void acquireLease(UUID workerId, Duration leaseDuration, Instant at) {
        Objects.requireNonNull(workerId, "a worker is required");
        Objects.requireNonNull(at, "the acquisition instant is required");
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "a bounded lease duration is required (INV-SBM-10). Without expiry, a terminated worker "
                            + "leaves the run claimed and the batch stalls SILENTLY — no error, no alert, and "
                            + "a coverage timeline that stops advancing, which resembles a stable estate.");
        }
        if (state != State.QUEUED && !leaseExpired(at)) {
            throw new IllegalStateException(
                    "the run is " + state + " with a lease held until " + leaseExpiresAt);
        }
        this.leaseHolderId = workerId;
        this.leaseExpiresAt = at.plus(leaseDuration);
        this.attemptCount++;
        this.state = State.LEASED;
    }

    /**
     * Whether the lease has lapsed, computed from the clock.
     *
     * <p>Not a flag. A flag requires a sweep to set it, and the failure this invariant addresses is precisely a
     * process that stopped running.
     */
    public boolean leaseExpired(Instant now) {
        Objects.requireNonNull(now, "the current instant is required");
        return leaseExpiresAt != null && !now.isBefore(leaseExpiresAt) && !state.isTerminal();
    }

    /** Reclaims an expired lease for another worker. {@code INV-SBM-10}. */
    public void reclaim(UUID newWorkerId, Duration leaseDuration, Instant at) {
        if (!leaseExpired(at)) {
            throw new IllegalStateException(
                    "the lease is still held until " + leaseExpiresAt + "; reclaiming a live lease would run "
                            + "the same snapshot twice concurrently");
        }
        this.state = State.QUEUED;
        acquireLease(newWorkerId, leaseDuration, at);
    }

    public void start(Instant at) {
        if (state != State.LEASED) {
            throw new IllegalStateException("a run starts from LEASED; this one is " + state);
        }
        this.startedAt = Objects.requireNonNull(at, "the start instant is required");
        this.state = State.RUNNING;
    }

    /**
     * Completes the run.
     *
     * <p>{@code INV-SBM-09}: {@code coverage_confirmed} is false unless the run completed successfully with
     * non-stale intelligence, and only a confirmed run may drive closure. The staleness half is
     * {@link ClosureAuthority}'s; this records what the run itself observed.
     *
     * @param coveredEcosystems what the snapshot actually covered, for {@code PRD-SBM-055}
     */
    public void complete(boolean everyComponentEnumerated, Set<Ecosystem> coveredEcosystems, Instant at) {
        if (state != State.RUNNING) {
            throw new IllegalStateException("a run completes from RUNNING; this one is " + state);
        }
        this.coveredEcosystems = Set.copyOf(
                Objects.requireNonNull(coveredEcosystems, "the covered ecosystems are required"));
        this.coverageConfirmed = everyComponentEnumerated;
        this.finishedAt = Objects.requireNonNull(at, "the completion instant is required");
        this.state = State.COMPLETED;
    }

    /**
     * Fails the run.
     *
     * <p>{@code coverageConfirmed} stays false and is not settable independently — a failed run that could
     * report confirmed coverage would defeat the closure guard entirely.
     */
    public void fail(String reason, Instant at) {
        if (state.isTerminal()) {
            throw new IllegalStateException("the run is already " + state);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "a failure reason is required. A run that failed for no recorded reason is one nobody can "
                            + "diagnose, and the batch it belongs to reports a count with no cause.");
        }
        this.failureReason = reason;
        this.coverageConfirmed = false;
        this.finishedAt = Objects.requireNonNull(at, "the failure instant is required");
        this.state = State.FAILED;
    }

    /**
     * {@code PRD-SBM-050}: nothing changed, so nothing was matched — <b>and the run is still recorded</b>.
     *
     * <p>"Skipping without recording produces exactly the false signal section 9 exists to prevent — an asset
     * that was correctly evaluated appears unevaluated."
     */
    public void skipNoChange(Instant at) {
        if (state != State.QUEUED && state != State.LEASED) {
            throw new IllegalStateException("a skip decision is made before running; this one is " + state);
        }
        this.coverageConfirmed = false;
        this.finishedAt = Objects.requireNonNull(at, "the skip instant is required");
        this.state = State.SKIPPED_NO_CHANGE;
    }

    /** Whether this run duplicates one already performed. {@code INV-SBM-07}. */
    public boolean duplicates(MatchRun other) {
        Objects.requireNonNull(other, "another run is required");
        return snapshotId.equals(other.snapshotId) && idempotencyKey.equals(other.idempotencyKey);
    }

    /**
     * Maps this run onto the closure gate's outcome.
     *
     * <p>A total mapping with no default branch, so a new run state cannot silently acquire closure authority by
     * falling through to {@code COMPLETED}.
     */
    public ClosureAuthority.RunOutcome closureOutcome(Instant now) {
        if (leaseExpired(now)) {
            return ClosureAuthority.RunOutcome.LEASE_EXPIRED;
        }
        return switch (state) {
            case COMPLETED -> ClosureAuthority.RunOutcome.COMPLETED;
            case SKIPPED_NO_CHANGE -> ClosureAuthority.RunOutcome.SKIPPED_NO_CHANGE;
            case FAILED -> ClosureAuthority.RunOutcome.FAILED;
            // A run still in flight has produced no evidence of anything.
            case QUEUED, LEASED, RUNNING -> ClosureAuthority.RunOutcome.CANCELLED;
        };
    }

    public UUID id() {
        return id;
    }

    public UUID snapshotId() {
        return snapshotId;
    }

    public QueueClass queueClass() {
        return queueClass;
    }

    public IdempotencyKey idempotencyKey() {
        return idempotencyKey;
    }

    public State state() {
        return state;
    }

    /** {@code INV-SBM-09}. Derived from how the run ended; there is no setter. */
    public boolean coverageConfirmed() {
        return coverageConfirmed;
    }

    public Set<Ecosystem> coveredEcosystems() {
        return coveredEcosystems;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public Optional<UUID> leaseHolderId() {
        return Optional.ofNullable(leaseHolderId);
    }

    public Optional<Instant> leaseExpiresAt() {
        return Optional.ofNullable(leaseExpiresAt);
    }

    public Instant queuedAt() {
        return queuedAt;
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> finishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }
}
