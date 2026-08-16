package aspm.module.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Turning a domain event into notifications. DOC-13 sections 3, 7 and 9.
 *
 * <h2>The asymmetry that shapes everything here</h2>
 *
 * <p>DOC-13 section 2: "A system that notifies too little leaves a process stalled with someone unaware —
 * recoverable, because the queue views still show the work. <b>A system that notifies too much gets muted, and a
 * muted system is one whose service level escalations do not arrive, whose information requests go unanswered,
 * and whose findings age unnoticed.</b> Muting is also effectively irreversible: a user who has filtered the
 * sender does not un-filter when the volume improves."
 *
 * <p>That is why coalescing and bulk suppression are in this class rather than in a configuration somebody
 * enables. A volume control a tenant can leave off is one that will be off in the deployment that most needs
 * it.
 *
 * <h2>Notification is a subscriber, and this class cannot be called from a transaction</h2>
 *
 * <p>{@code PRD-NTF-013}, {@code PRD-WRK-037}: "A notification failure inside a transaction either fails the
 * transaction — making a mail outage a work outage — or is swallowed, losing the notification silently. Neither
 * is acceptable."
 *
 * <p>Structurally: {@link #dispatch} takes events that have <b>already happened</b> and returns artifacts to
 * deliver. It has no repository, performs no write, and returns nothing a caller could interpret as a
 * transaction outcome — so there is no shape in which a domain transaction could depend on it.
 */
public final class NotificationDispatch {

    /** The coalescing window of DOC-13 section 7. */
    public static final Duration COALESCING_WINDOW = Duration.ofSeconds(60);

    /**
     * A domain event, as the subscriber sees it.
     *
     * @param bulkOperationId present where the event is one of many from a single bulk action, import, or
     *     automation execution. {@code PRD-NTF-023} collapses those to one summary per recipient
     */
    public record DomainEvent(UUID eventId, UUID subjectId, String subjectKind, String eventKind,
            Instant occurredAt, Optional<UUID> bulkOperationId) {

        public DomainEvent {
            Objects.requireNonNull(eventId, "eventId is required");
            Objects.requireNonNull(subjectId, "subjectId is required");
            Objects.requireNonNull(subjectKind, "subjectKind is required");
            Objects.requireNonNull(eventKind, "eventKind is required");
            Objects.requireNonNull(occurredAt, "occurredAt is required");
            Objects.requireNonNull(bulkOperationId, "bulkOperationId is required, empty for a single action");
        }
    }

    /** What the dispatcher produced, and what it deliberately did not. */
    public record Outcome(List<RenderedNotification> toDeliver, List<Suppression> suppressed) {

        public Outcome {
            toDeliver = List.copyOf(Objects.requireNonNull(toDeliver, "toDeliver is required"));
            suppressed = List.copyOf(Objects.requireNonNull(suppressed, "suppressed is required"));
        }
    }

    /**
     * A notification that was not sent, and why.
     *
     * <p>Recorded rather than discarded, because "the notification did not arrive" and "the notification was
     * never generated" are different diagnoses and only one of them is a bug. The record is internal — it never
     * reaches the recipient, which would defeat {@code PRD-NTF-031}.
     */
    public record Suppression(UUID recipientId, UUID subjectId, Reason reason) {

        public enum Reason {
            /**
             * {@code PRD-NTF-031}. The recipient's scope narrowed and the subject is no longer visible.
             *
             * <p>Suppressed, <b>not</b> sent with content removed: "An empty notification about an object the
             * recipient cannot see confirms that the object exists and concerns them — a disclosure through
             * absence."
             */
            SUBJECT_NO_LONGER_VISIBLE,
            /** Merged into another notification within the coalescing window. */
            COALESCED,
            /** Merged into a bulk summary. */
            BULK_SUMMARISED,
            /** The recipient's subscription excludes this category on this channel. */
            UNSUBSCRIBED
        }
    }

    private NotificationDispatch() {
    }

    /**
     * Produces the notifications for a batch of events.
     *
     * @param audience who each event reaches, before scope filtering. Resolved by the caller from assignee,
     *     watchers, owners and roles
     * @param visibleAtRenderTime the scope check, evaluated <b>now</b> rather than when the event occurred.
     *     {@code PRD-NTF-029}: "Including a finding summary in an email to a recipient who has since lost
     *     access is a disclosure no later authorization change can retract."
     * @param render builds the artifact for one recipient and one subject
     */
    public static Outcome dispatch(List<DomainEvent> events, Map<UUID, Set<UUID>> audience,
            BiPredicate<UUID, UUID> visibleAtRenderTime,
            Renderer render) {
        Objects.requireNonNull(events, "events are required");
        Objects.requireNonNull(audience, "an audience map is required");
        Objects.requireNonNull(visibleAtRenderTime,
                "a render-time scope check is required (PRD-NTF-029). A notification is a delivery to a "
                        + "destination with no scope enforcement at the point of receipt.");
        Objects.requireNonNull(render, "a renderer is required");

        List<RenderedNotification> toDeliver = new ArrayList<>();
        List<Suppression> suppressed = new ArrayList<>();

        // Group by (recipient, subject) so both volume controls operate on the same grouping. Bulk suppression
        // is applied first: a bulk action's events would otherwise each pass the coalescing window separately
        // if they span more than sixty seconds, which a large bulk operation does.
        record Key(UUID recipient, UUID subject, Optional<UUID> bulkOperation) {
        }
        Map<Key, List<DomainEvent>> grouped = new LinkedHashMap<>();
        for (DomainEvent event : events) {
            for (UUID recipient : audience.getOrDefault(event.eventId(), Set.of())) {
                grouped.computeIfAbsent(new Key(recipient, event.subjectId(), event.bulkOperationId()),
                        k -> new ArrayList<>()).add(event);
            }
        }

        // A bulk operation collapses across SUBJECTS too — one summary per recipient per operation, not one
        // per subject. PRD-NTF-023: "never one per affected item."
        Map<UUID, Map<UUID, List<DomainEvent>>> bulkByRecipient = new LinkedHashMap<>();

        for (Map.Entry<Key, List<DomainEvent>> entry : grouped.entrySet()) {
            Key key = entry.getKey();
            if (key.bulkOperation().isPresent()) {
                bulkByRecipient
                        .computeIfAbsent(key.recipient(), r -> new LinkedHashMap<>())
                        .computeIfAbsent(key.bulkOperation().get(), b -> new ArrayList<>())
                        .addAll(entry.getValue());
                continue;
            }

            if (!visibleAtRenderTime.test(key.recipient(), key.subject())) {
                suppressed.add(new Suppression(key.recipient(), key.subject(),
                        Suppression.Reason.SUBJECT_NO_LONGER_VISIBLE));
                continue;
            }

            List<List<DomainEvent>> windows = coalesce(entry.getValue());
            for (List<DomainEvent> window : windows) {
                // The merged notification states the NET change, not the sequence: "A recipient does not need
                // to know an item moved through three states in forty seconds; they need to know where it is
                // now" (PRD-NTF-024).
                DomainEvent latest = window.get(window.size() - 1);
                toDeliver.add(render.render(key.recipient(), latest, window.size()));
                for (int i = 0; i < window.size() - 1; i++) {
                    suppressed.add(new Suppression(key.recipient(), key.subject(),
                            Suppression.Reason.COALESCED));
                }
            }
        }

        bulkByRecipient.forEach((recipient, byOperation) -> byOperation.forEach((operation, batch) -> {
            List<DomainEvent> visible = batch.stream()
                    .filter(e -> visibleAtRenderTime.test(recipient, e.subjectId()))
                    .toList();
            if (visible.isEmpty()) {
                batch.forEach(e -> suppressed.add(new Suppression(recipient, e.subjectId(),
                        Suppression.Reason.SUBJECT_NO_LONGER_VISIBLE)));
                return;
            }
            toDeliver.add(render.renderBulkSummary(recipient, operation, visible.size()));
            visible.forEach(e -> suppressed.add(new Suppression(recipient, e.subjectId(),
                    Suppression.Reason.BULK_SUMMARISED)));
            batch.stream()
                    .filter(e -> !visible.contains(e))
                    .forEach(e -> suppressed.add(new Suppression(recipient, e.subjectId(),
                            Suppression.Reason.SUBJECT_NO_LONGER_VISIBLE)));
        }));

        return new Outcome(toDeliver, suppressed);
    }

    /** Splits an ordered event list into coalescing windows. */
    private static List<List<DomainEvent>> coalesce(List<DomainEvent> events) {
        List<DomainEvent> ordered = new ArrayList<>(events);
        ordered.sort(java.util.Comparator.comparing(DomainEvent::occurredAt)
                .thenComparing(DomainEvent::eventId));

        List<List<DomainEvent>> windows = new ArrayList<>();
        List<DomainEvent> current = new ArrayList<>();
        Instant windowStart = null;
        for (DomainEvent event : ordered) {
            if (windowStart == null
                    || Duration.between(windowStart, event.occurredAt()).compareTo(COALESCING_WINDOW) > 0) {
                if (!current.isEmpty()) {
                    windows.add(List.copyOf(current));
                }
                current = new ArrayList<>();
                windowStart = event.occurredAt();
            }
            current.add(event);
        }
        if (!current.isEmpty()) {
            windows.add(List.copyOf(current));
        }
        return windows;
    }

    /** Builds the per-recipient artifact. Separate from dispatch so scope and volume logic stay one thing. */
    public interface Renderer {

        RenderedNotification render(UUID recipientId, DomainEvent event, int mergedEventCount);

        RenderedNotification renderBulkSummary(UUID recipientId, UUID bulkOperationId, int affectedCount);
    }
}
