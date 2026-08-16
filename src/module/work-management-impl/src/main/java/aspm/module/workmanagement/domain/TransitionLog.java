package aspm.module.workmanagement.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The append-only transition log for one work item. {@code INV-WRK-03}, {@code INV-WRK-04},
 * {@code PRD-WRK-011}.
 *
 * <p><b>There is no removal path at any privilege.</b> No {@code delete}, no {@code truncate}, no {@code clear},
 * no {@code replace}, no setter on an entry. The prompt asks for a test asserting that, and the test checks the
 * shape of this class reflectively rather than trusting the absence to persist through future edits. At the
 * engine, {@code V009} withholds {@code UPDATE} and {@code DELETE} from {@code app_runtime} and additionally
 * declares a rejecting trigger, "so that a privilege misconfiguration does not silently permit modification"
 * (DOC-04 section 16.3).
 *
 * <p><b>What appending computes, so the caller cannot get it wrong.</b> The sequence, the duration in the
 * previous state, and the {@code from} state all derive from the entry already at the tail. A caller supplying
 * them would eventually supply one that disagrees with the log, and a disagreement in an append-only record is
 * unfixable by construction.
 */
public final class TransitionLog {

    private final UUID workItemId;
    private final List<WorkItemStateTransition> entries = new ArrayList<>();

    public TransitionLog(UUID workItemId) {
        this.workItemId = Objects.requireNonNull(workItemId, "workItemId is required");
    }

    /**
     * Rehydrates a log from storage.
     *
     * @throws IllegalArgumentException where the entries are not a contiguous sequence from 1. A log with a gap
     *     has lost a transition, and every duration after the gap is wrong by the missing interval — silently,
     *     because the arithmetic still works
     */
    public static TransitionLog of(UUID workItemId, List<WorkItemStateTransition> entries) {
        TransitionLog log = new TransitionLog(workItemId);
        Objects.requireNonNull(entries, "entries are required, possibly empty");
        int expected = 1;
        for (WorkItemStateTransition entry : entries) {
            if (!entry.workItemId().equals(workItemId)) {
                throw new IllegalArgumentException(
                        "entry " + entry.sequence() + " belongs to item " + entry.workItemId()
                                + ", not " + workItemId);
            }
            if (entry.sequence() != expected) {
                throw new IllegalArgumentException(
                        "expected sequence " + expected + " but found " + entry.sequence()
                                + ". A gap means a transition was lost, and every duration after it is wrong "
                                + "by the missing interval while still looking arithmetically sound.");
            }
            log.entries.add(entry);
            expected++;
        }
        return log;
    }

    /** Records item creation. Must be first. */
    public WorkItemStateTransition recordCreation(UUID entryId, UUID initialStateId, UUID actorId,
            ActorType actorType, Instant at, boolean clockRunningInInitialState) {
        if (!entries.isEmpty()) {
            throw new IllegalStateException("creation is already recorded at sequence 1");
        }
        WorkItemStateTransition entry = WorkItemStateTransition.creation(entryId, workItemId, initialStateId,
                actorId, actorType, at, clockRunningInInitialState);
        entries.add(entry);
        return entry;
    }

    /**
     * Appends a transition.
     *
     * @param clockRunningInStateJustLeft the flag <b>as it was</b> for the state being vacated, read from the
     *     item's pinned workflow version. Passing today's value for a historical state would let a configuration
     *     change retroactively alter past breach attribution
     * @param blockingAttribution required where the target state pauses the clock ({@code PRD-RSK-034}, DOC-09
     *     section 3), because unattributed delay defaults to blaming the accountable team
     * @param reasonRequired from the transition definition, plus DOC-09 section 3's rule for non-success
     *     terminal states
     */
    public WorkItemStateTransition append(UUID entryId, UUID toStateId, String eventCode, UUID actorId,
            ActorType actorType, UUID automationRuleId, String reason, Instant at,
            boolean clockRunningInStateJustLeft, TransitionBlockingAttribution blockingAttribution,
            boolean reasonRequired, boolean targetPausesClock) {

        if (entries.isEmpty()) {
            throw new IllegalStateException(
                    "the creation entry must be recorded first; without it sequence 1 is a transition out of a "
                            + "state the item was never recorded as entering");
        }
        WorkItemStateTransition previous = entries.get(entries.size() - 1);

        if (at.isBefore(previous.transitionedAt())) {
            throw new IllegalArgumentException(
                    "transition at " + at + " precedes the previous entry at " + previous.transitionedAt()
                            + ". Time does not run backwards through an append-only log, and accepting it "
                            + "would produce a negative duration that later arithmetic would treat as real.");
        }
        if (reasonRequired && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(
                    "event '" + eventCode + "' requires a reason (DOC-09 section 3). A transition whose reason "
                            + "is mandatory and absent is the record nobody can review afterwards.");
        }
        if (targetPausesClock && blockingAttribution == null) {
            throw new IllegalArgumentException(
                    "entering a state that pauses the clock requires a blocking attribution (PRD-RSK-034, "
                            + "DOC-09 section 3). Unattributed delay defaults to blaming the accountable team, "
                            + "which is usually wrong and always corrosive (PP-6).");
        }
        if (!targetPausesClock && blockingAttribution != null) {
            throw new IllegalArgumentException(
                    "a blocking attribution on a transition into a running state would appear in blocked-time "
                            + "reporting for time during which nothing was blocked");
        }

        WorkItemStateTransition entry = new WorkItemStateTransition(
                entryId, workItemId, previous.sequence() + 1, Optional.of(previous.toStateId()), toStateId,
                eventCode, Optional.ofNullable(actorId), actorType, Optional.ofNullable(automationRuleId),
                Optional.ofNullable(reason), at,
                Optional.of(Duration.between(previous.transitionedAt(), at)),
                clockRunningInStateJustLeft, Optional.ofNullable(blockingAttribution));
        entries.add(entry);
        return entry;
    }

    /**
     * Total time the item has spent in a state, over the whole history.
     *
     * <p>Sums the recorded durations rather than recomputing from timestamps, so a reader gets the same answer
     * the stored column gives — a divergence between the two would be invisible and would show up as two charts
     * that disagree.
     */
    public Duration timeSpentIn(UUID stateId) {
        Duration total = Duration.ZERO;
        for (WorkItemStateTransition entry : entries) {
            if (entry.fromStateId().filter(stateId::equals).isPresent()) {
                total = total.plus(entry.durationInPreviousState().orElse(Duration.ZERO));
            }
        }
        return total;
    }

    /**
     * Total time the clock was running, which is what a cycle-time figure may honestly use.
     *
     * <p>Paused time is excluded here and reported separately, per {@code PRD-RSK-034}. A cycle time that
     * included time somebody else blocked would charge a team for a delay they did not cause.
     */
    public Duration clockRunningDuration() {
        Duration total = Duration.ZERO;
        for (WorkItemStateTransition entry : entries) {
            if (entry.slaClockRunning()) {
                total = total.plus(entry.durationInPreviousState().orElse(Duration.ZERO));
            }
        }
        return total;
    }

    /**
     * How many times the item entered this state.
     *
     * <p>Greater than one means rework, which {@code PRD-WRK-036} makes visible deliberately: "An undo that
     * removes history makes cycle-time and flow analysis wrong, and it conceals rework — which is itself a
     * signal."
     */
    public int entriesInto(UUID stateId) {
        return (int) entries.stream().filter(e -> e.toStateId().equals(stateId)).count();
    }

    public Optional<WorkItemStateTransition> last() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(entries.size() - 1));
    }

    /** An unmodifiable view. The copy is what keeps a caller from mutating the list out from under the log. */
    public List<WorkItemStateTransition> entries() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    public UUID workItemId() {
        return workItemId;
    }
}
