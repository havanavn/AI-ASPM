package aspm.module.workmanagement.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One entry in the append-only transition log. DOC-03 section 13.1, DOC-04 section 16.3.
 *
 * <h2>Why this is build-block work and not analytics work</h2>
 *
 * <p>DOC-03 section 13.2: "The transition log's data cannot be reconstructed later. <i>How many items were in
 * remediation at the end of last quarter</i> is answerable only from a transition record; it is not derivable
 * from current state with a modification timestamp. A platform omitting this in v1 and adding workload analytics
 * in v2 finds its historical charts begin on the day the log was introduced, with the preceding period
 * permanently unavailable."
 *
 * <p>That is why it ships now even though Capacity — its only consumer — comes later. The capability can wait;
 * the data cannot.
 *
 * <h2>Not an aggregate</h2>
 *
 * <p>DOC-03 section 13.3: it is an append-only fact stream, not a consistency boundary. "Modelling it as an
 * aggregate would imply it can be loaded and modified as a unit, which is precisely what {@code INV-WRK-04}
 * prohibits." Hence an immutable record with no mutators and a separate {@link TransitionLog} that only appends.
 *
 * @param sequence monotonic per item, starting at 1 for creation
 * @param fromStateId absent on creation — there is no prior state, and a sentinel would be indistinguishable
 *     from a real one in a cumulative-flow query
 * @param durationInPreviousState <b>denormalized</b>. Derivable by self-joining to the previous sequence; stored
 *     because cycle-time and flow computations "read the whole history of many items at once" (DOC-04 section
 *     16.3), and the cost is eight bytes against a value the transition already has in hand
 * @param slaClockRunning whether the clock was running <b>in the state just left</b>.
 *     <p>Recorded here rather than resolved from the state, because the state's flag is tenant configuration and
 *     can change: "A historical service level computation must use the flag as it was, not as it is — otherwise a
 *     configuration change retroactively alters past breach attribution" (DOC-04 section 16.3). This is the same
 *     reasoning as the calendar snapshot on the service level clock, applied to a different piece of
 *     configuration.
 */
public record WorkItemStateTransition(UUID id, UUID workItemId, int sequence, Optional<UUID> fromStateId,
        UUID toStateId, String eventCode, Optional<UUID> actorId, ActorType actorType,
        Optional<UUID> automationRuleId, Optional<String> reason, Instant transitionedAt,
        Optional<Duration> durationInPreviousState, boolean slaClockRunning,
        Optional<TransitionBlockingAttribution> blockingAttribution) {

    public WorkItemStateTransition {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(workItemId, "workItemId is required");
        Objects.requireNonNull(fromStateId, "fromStateId is required, empty on creation");
        Objects.requireNonNull(toStateId, "toStateId is required");
        Objects.requireNonNull(eventCode, "eventCode is required");
        Objects.requireNonNull(actorId, "actorId is required, empty for SYSTEM");
        Objects.requireNonNull(actorType, "actorType is required");
        Objects.requireNonNull(automationRuleId, "automationRuleId is required, empty unless AUTOMATION");
        Objects.requireNonNull(reason, "reason is required, empty where the transition does not demand one");
        Objects.requireNonNull(transitionedAt, "transitionedAt is required");
        Objects.requireNonNull(durationInPreviousState, "duration is required, empty on creation");
        Objects.requireNonNull(blockingAttribution, "blockingAttribution is required, empty where not blocked");

        if (sequence < 1) {
            throw new IllegalArgumentException("sequence is monotonic from 1; got " + sequence);
        }
        if (sequence == 1 && fromStateId.isPresent()) {
            throw new IllegalArgumentException(
                    "sequence 1 is the creation entry and has no prior state; got " + fromStateId.get());
        }
        if (sequence > 1 && fromStateId.isEmpty()) {
            throw new IllegalArgumentException(
                    "only the creation entry has no prior state; sequence " + sequence + " must name one, or a "
                            + "cumulative-flow query cannot tell which state was vacated");
        }
        if (actorType.requiresAutomationRule() && automationRuleId.isEmpty()) {
            throw new IllegalArgumentException(
                    "an AUTOMATION transition must name its rule (DOC-04 section 16.3). Without it the "
                            + "transition is indistinguishable from a human one at exactly the moment somebody "
                            + "is asking why the item moved.");
        }
        if (!actorType.requiresAutomationRule() && automationRuleId.isPresent()) {
            throw new IllegalArgumentException(
                    "a rule identifier on a " + actorType + " transition attributes automated activity to a "
                            + "principal who did not act");
        }
        if (actorType.carriesPrincipal() && actorId.isEmpty()) {
            throw new IllegalArgumentException(
                    actorType + " requires a principal. An unattributed transition defeats the per-principal "
                            + "transition rate that SEC-PLT-005 depends on.");
        }
        if (!actorType.carriesPrincipal() && actorId.isPresent()) {
            throw new IllegalArgumentException(
                    "SYSTEM has no principal; naming one attributes platform activity to a person");
        }
        if (durationInPreviousState.isPresent() && durationInPreviousState.get().isNegative()) {
            throw new IllegalArgumentException(
                    "a negative duration in the previous state; the entries are out of order, and cycle-time "
                            + "arithmetic over them would silently produce shorter cycles than reality");
        }
        if (sequence == 1 && durationInPreviousState.isPresent()) {
            throw new IllegalArgumentException("the creation entry has no previous state to have spent time in");
        }
    }

    /** The creation entry. Sequence 1, no prior state, no duration. */
    public static WorkItemStateTransition creation(UUID id, UUID workItemId, UUID initialStateId,
            UUID actorId, ActorType actorType, Instant at, boolean clockRunningInInitialState) {
        return new WorkItemStateTransition(id, workItemId, 1, Optional.empty(), initialStateId, "create",
                Optional.ofNullable(actorId), actorType, Optional.empty(), Optional.empty(), at,
                Optional.empty(), clockRunningInInitialState, Optional.empty());
    }
}
