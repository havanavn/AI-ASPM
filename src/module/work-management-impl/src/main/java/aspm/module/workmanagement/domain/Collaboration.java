package aspm.module.workmanagement.domain;

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
 * Watchers, participants, read state, and the unified timeline for one work item. {@code PRD-WRK-019},
 * DOC-04 section 16.5.
 *
 * <p>Held together in one type because they answer one question — <i>who is involved and what have they seen</i>
 * — and because the notification fan-out reads all four at once. Splitting them would mean four loads on the path
 * that runs on every event.
 */
public final class Collaboration {

    private final UUID workItemId;

    /** {@code (principal, role)} — a principal may hold more than one role. DOC-04 section 16.5's unique key. */
    private final Set<Participation> participants = new LinkedHashSet<>();
    private final Map<UUID, Instant> watchers = new LinkedHashMap<>();
    private final Map<UUID, Instant> lastReadAt = new LinkedHashMap<>();

    /** One participation fact. */
    public record Participation(UUID principalId, ParticipantRole role) {

        public Participation {
            Objects.requireNonNull(principalId, "principalId is required");
            Objects.requireNonNull(role, "role is required");
        }
    }

    public Collaboration(UUID workItemId) {
        this.workItemId = Objects.requireNonNull(workItemId, "workItemId is required");
    }

    // ------------------------------------------------------------------ participation

    public void addParticipant(UUID principalId, ParticipantRole role) {
        participants.add(new Participation(principalId, role));
    }

    /**
     * Removes a participation.
     *
     * <p>Permitted, unlike a comment: participation is current state, not a record of what happened. Who <i>was</i>
     * a participant at a past moment is answerable from the audit log, which is where that question belongs — and
     * keeping a removed participant here would keep notifying them.
     */
    public boolean removeParticipant(UUID principalId, ParticipantRole role) {
        return participants.remove(new Participation(principalId, role));
    }

    public Set<Participation> participants() {
        return Set.copyOf(participants);
    }

    // ------------------------------------------------------------------ watchers

    /**
     * Subscribes a watcher.
     *
     * <p>{@code PRD-WRK-019} requires "watchers with explicit subscribe and unsubscribe". Explicit both ways: a
     * platform that auto-subscribes on any interaction and offers no way out produces notification volume that
     * teaches people to filter the platform's mail, which costs more than the notifications were worth.
     */
    public void watch(UUID principalId, Instant at) {
        Objects.requireNonNull(principalId, "principalId is required");
        Objects.requireNonNull(at, "the subscription instant is required");
        watchers.putIfAbsent(principalId, at);
    }

    /**
     * Unsubscribes.
     *
     * <p>Removal, not a suppression flag. A suppressed watcher would be re-notified by any code path that read
     * the watcher list without checking the flag, and there will be several such paths.
     */
    public boolean unwatch(UUID principalId) {
        return watchers.remove(principalId) != null;
    }

    public boolean watching(UUID principalId) {
        return watchers.containsKey(principalId);
    }

    public Set<UUID> watchers() {
        return Set.copyOf(watchers.keySet());
    }

    /**
     * Everyone a change on this item should reach, before scope filtering.
     *
     * <p><b>Before</b>, deliberately: notification is a subscriber to domain events (DOC-13 section 3,
     * {@code PRD-WRK-037}), and it applies the reader's own scope when it delivers. Filtering here would put an
     * authorization decision in the wrong module and produce two places that could disagree about who may see an
     * item.
     *
     * @param assigneeId the current assignee, if any
     */
    public Set<UUID> notificationAudience(UUID assigneeId) {
        Set<UUID> audience = new LinkedHashSet<>();
        if (assigneeId != null) {
            audience.add(assigneeId);
        }
        for (Participation p : participants) {
            // A SHADOW is observing to learn and carries no expectation of action. Including them in every
            // notification is how a learning mechanism becomes a mail filter rule.
            if (p.role() != ParticipantRole.SHADOW) {
                audience.add(p.principalId());
            }
        }
        audience.addAll(watchers.keySet());
        return Set.copyOf(audience);
    }

    // ------------------------------------------------------------------ read state

    /**
     * Records that a principal has read the item up to this instant.
     *
     * <p>Monotonic: a later read never moves the mark backwards. Two browser tabs reading in either order must
     * not resurrect notifications the user has already dismissed.
     *
     * <p>DOC-04 section 16.5 notes this is "the highest-frequency write in the platform" at Extra large and
     * "entirely uninteresting data" — excluded from audit, and a candidate for a write-behind cache. The
     * monotonicity here is what makes a write-behind safe: replaying an out-of-order batch cannot regress a mark.
     */
    public void markRead(UUID principalId, Instant at) {
        Objects.requireNonNull(principalId, "principalId is required");
        Objects.requireNonNull(at, "the read instant is required");
        lastReadAt.merge(principalId, at, (existing, candidate) ->
                candidate.isAfter(existing) ? candidate : existing);
    }

    public Optional<Instant> lastReadAt(UUID principalId) {
        return Optional.ofNullable(lastReadAt.get(principalId));
    }

    /**
     * The timeline entries this principal has not seen.
     *
     * <p>A principal who has never opened the item sees everything as unread — <b>not</b> nothing. Treating
     * "never read" as "all read" is the arithmetic that hides an item from the person it was just assigned to.
     *
     * <p>A principal's own actions are excluded: an inbox that reports your own comment as unread trains you to
     * dismiss the count without reading it, and then the count is worth nothing.
     */
    public List<ActivityEntry> unreadFor(UUID principalId, List<ActivityEntry> timeline) {
        Objects.requireNonNull(principalId, "principalId is required");
        Objects.requireNonNull(timeline, "a timeline is required");
        Instant mark = lastReadAt.get(principalId);
        List<ActivityEntry> unread = new ArrayList<>();
        for (ActivityEntry entry : timeline) {
            if (entry.actorId().filter(principalId::equals).isPresent()) {
                continue;
            }
            if (mark == null || entry.occurredAt().isAfter(mark)) {
                unread.add(entry);
            }
        }
        return List.copyOf(unread);
    }

    // ------------------------------------------------------------------ timeline

    /**
     * Interleaves the sources into one chronological timeline.
     *
     * <p>Built on read from the parts rather than stored, per {@code PRD-WRK-019}'s extensibility note. The
     * transition log, the comments and any further event sources are merged and sorted; a new source is a new
     * argument here and a new {@link ActivityEntry.Kind}, with no schema change.
     *
     * <p><b>A redacted comment appears as a redaction, not as an absence.</b> {@code INV-WRK-08} makes the
     * removal itself part of the history, and a timeline that silently omitted redacted comments would let
     * selective redaction reconstruct a different history — which is the thing the invariant exists to stop.
     */
    public static List<ActivityEntry> timeline(TransitionLog transitions, List<Comment> comments,
            List<ActivityEntry> otherEvents) {
        Objects.requireNonNull(transitions, "a transition log is required");
        Objects.requireNonNull(comments, "comments are required, possibly empty");
        Objects.requireNonNull(otherEvents, "other events are required, possibly empty");

        List<ActivityEntry> entries = new ArrayList<>(otherEvents);

        for (WorkItemStateTransition t : transitions.entries()) {
            entries.add(new ActivityEntry(
                    t.actorType() == ActorType.AUTOMATION
                            ? ActivityEntry.Kind.AUTOMATED_ACTION : ActivityEntry.Kind.STATE_CHANGE,
                    t.transitionedAt(), t.actorId(), t.actorType(), Optional.of(t.toStateId()),
                    t.fromStateId().isEmpty()
                            ? "created"
                            : "'" + t.eventCode() + "'"
                                    + t.reason().map(r -> " — " + r).orElse("")));
        }

        for (Comment c : comments) {
            entries.add(new ActivityEntry(ActivityEntry.Kind.COMMENT, c.createdAt(),
                    Optional.of(c.authorId()), ActorType.USER, Optional.of(c.id()),
                    c.migrated()
                            // The migration flag must survive into every presentation (DOC-04 section 16.4),
                            // and the timeline is the presentation a reader uses to reconstruct what happened.
                            ? "commented (migrated from an external tracker)" : "commented"));

            if (c.redacted()) {
                entries.add(new ActivityEntry(ActivityEntry.Kind.REDACTION, c.redactedAt().orElseThrow(),
                        c.redactedBy(), ActorType.USER, Optional.of(c.id()),
                        "redacted a comment — " + c.redactionReason().orElseThrow()));
            }
            for (Comment.Revision revision : c.revisions()) {
                if (c.redacted() && revision.revision() == c.revisions().size()) {
                    // The last revision of a redacted comment IS the redaction's snapshot of the original; it
                    // already has an entry above. Listing it twice would read as an edit that never happened.
                    continue;
                }
                entries.add(new ActivityEntry(ActivityEntry.Kind.COMMENT, revision.editedAt(),
                        Optional.of(revision.editedBy()), ActorType.USER, Optional.of(c.id()),
                        "edited a comment (revision " + revision.revision() + " retained)"));
            }
        }

        entries.sort(null);
        return List.copyOf(entries);
    }

    public UUID workItemId() {
        return workItemId;
    }
}
