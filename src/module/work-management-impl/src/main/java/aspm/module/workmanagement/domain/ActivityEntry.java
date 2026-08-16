package aspm.module.workmanagement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One entry in the unified activity timeline. {@code PRD-WRK-019}.
 *
 * <p>"The unified timeline is what makes an item comprehensible to someone arriving three months later: a state
 * history and a comment thread as separate views cannot answer what happened, in what order, and why."
 *
 * <p><b>A projection over the domain event stream, not a table of its own.</b> {@code PRD-WRK-019}'s
 * extensibility note: "The activity timeline is a projection over the domain event stream, so a new event type
 * appears in the timeline without timeline changes." A separate timeline table would need a write on every
 * event and would drift from the events the moment one write failed.
 *
 * @param kind what happened. A new kind is a new constant, not a new table
 * @param actorType who did it. An automated action rendered as a human one is how a reader concludes a person
 *     made a decision the platform made
 * @param summary a rendered description. Built by the projection from the event, never authored by a user —
 *     user-authored content reaches the timeline only as a comment reference, so the timeline itself is not a
 *     second injection surface
 */
public record ActivityEntry(Kind kind, Instant occurredAt, Optional<UUID> actorId, ActorType actorType,
        Optional<UUID> subjectId, String summary) implements Comparable<ActivityEntry> {

    /** The event kinds the timeline interleaves. {@code PRD-WRK-019} names the first five. */
    public enum Kind {
        STATE_CHANGE,
        FIELD_CHANGE,
        COMMENT,
        ATTACHMENT,
        AUTOMATED_ACTION,
        /** Assignment moves are the question most often asked of a timeline after "what state is it in". */
        ASSIGNMENT_CHANGE,
        /** A link created or removed. Explains why an item stalled behind another. */
        LINK_CHANGE,
        /** A comment redaction. Present deliberately: the removal is part of the history (INV-WRK-08). */
        REDACTION
    }

    public ActivityEntry {
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(actorId, "actorId is required, empty for SYSTEM");
        Objects.requireNonNull(actorType, "actorType is required");
        Objects.requireNonNull(subjectId, "subjectId is required, empty where the entry has no sub-object");
        Objects.requireNonNull(summary, "a summary is required");
        if (actorType.carriesPrincipal() && actorId.isEmpty()) {
            throw new IllegalArgumentException(
                    actorType + " requires a principal; an unattributed timeline entry is the one a reader "
                            + "arriving three months later most needs attributed");
        }
        if (!actorType.carriesPrincipal() && actorId.isPresent()) {
            throw new IllegalArgumentException("SYSTEM has no principal");
        }
    }

    /**
     * Chronological, with a stable tiebreak on kind then summary.
     *
     * <p>Without the tiebreak, two entries at the same instant — a state change and the automated action that
     * caused it, written in one transaction — would order differently between two reads of the same item, and a
     * timeline that reorders itself is one a reader stops trusting.
     */
    @Override
    public int compareTo(ActivityEntry other) {
        int byTime = occurredAt.compareTo(other.occurredAt);
        if (byTime != 0) {
            return byTime;
        }
        int byKind = kind.compareTo(other.kind);
        return byKind != 0 ? byKind : summary.compareTo(other.summary);
    }
}
