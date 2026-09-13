package aspm.app.notification;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The event catalogue of DOC-13 §4: every notifiable event, its category, whether it may be digested,
 * and whether it is mandatory. {@code PRD-NTF-015}, {@code PRD-NTF-016}, {@code PRD-NTF-017}.
 *
 * <p>Product-fixed, as the requirement says: a tenant adjusts audience and channel, never whether a
 * category exists or whether it can be muted. An event that is not here does not notify
 * ({@code PRD-NTF-015}), which is what makes "add an emitter" a reviewable change rather than a stray
 * string. Categories are the unit routes and preferences work on; events are the unit templates work
 * on.
 *
 * <p>Only the events that have an emitter today are listed with {@code emitted = true}; the rest are
 * catalogued so a route can be configured ahead of the emitter, and so the list on the settings page
 * says what will arrive rather than what happens to arrive.
 */
public final class NotificationCatalogue {

    /** A category, the unit of routing and of preference. */
    public enum Category {
        REQUEST("Assessment requests", false),
        COLLABORATION("Comments and mentions", false),
        FINDING("Findings and exceptions", false),
        SERVICE_LEVEL("Service levels", false),
        INTEGRATION("Integrations and coverage", false),
        REPORTING("Reports and exports", false),
        SECURITY("Security and access", true),
        SYSTEM("Platform", true);

        public final String label;
        /** {@code PRD-NTF-017}: not unsubscribable, never deferred by quiet hours, never muted. */
        public final boolean mandatory;

        Category(String label, boolean mandatory) {
            this.label = label;
            this.mandatory = mandatory;
        }
    }

    /** One catalogued event. */
    public record Event(String kind, Category category, boolean digestible, String label, boolean emitted) {
        public boolean mandatory() {
            return category.mandatory;
        }
    }

    private static final Map<String, Event> EVENTS = Map.ofEntries(
            entry("request.transitioned", Category.REQUEST, true, "A request changed state", true),
            entry("request.participant_added", Category.REQUEST, true, "You were added to a request", true),
            entry("request.information_requested", Category.REQUEST, false, "A request needs information from you", false),
            entry("comment.posted", Category.COLLABORATION, true, "Somebody commented", true),
            entry("comment.mentioned", Category.COLLABORATION, false, "You were mentioned", false),
            entry("finding.assigned", Category.FINDING, true, "A finding was assigned to you", false),
            entry("finding.secret.confirmed_live", Category.FINDING, false, "A live secret was confirmed", false),
            entry("exception.expiring", Category.FINDING, true, "A risk exception is expiring", false),
            entry("advisory.detected", Category.FINDING, true, "A new vulnerability advisory matched", true),
            entry("sla.breached", Category.SERVICE_LEVEL, false, "A service level was breached", false),
            entry("sla.escalated", Category.SERVICE_LEVEL, false, "A service level escalated", false),
            entry("integration.unhealthy", Category.INTEGRATION, false, "An integration is unhealthy", true),
            entry("integration.divergence", Category.INTEGRATION, false, "An external ticket diverged from the platform record", true),
            entry("integration.submission_failing", Category.INTEGRATION, true, "Submissions are failing", false),
            entry("report.ready", Category.REPORTING, false, "A scheduled report is ready for you", true),
            entry("report.failed", Category.REPORTING, false, "A scheduled report could not be delivered", true),
            entry("report.recipient_dropped", Category.REPORTING, false, "A report recipient was dropped for lost access", true),
            entry("credential.rotation_required", Category.SECURITY, false, "A credential must be rotated", true),
            entry("break_glass.activated", Category.SECURITY, false, "Break-glass access was activated", false),
            entry("audit.integrity_failed", Category.SECURITY, false, "Audit chain verification failed", false),
            entry("channel.verification", Category.SYSTEM, false, "Channel verification code", true),
            entry("tenant.suspended", Category.SYSTEM, false, "The tenant was suspended", false));

    private static Map.Entry<String, Event> entry(String kind, Category category, boolean digestible, String label, boolean emitted) {
        return Map.entry(kind, new Event(kind, category, digestible, label, emitted));
    }

    private NotificationCatalogue() {
    }

    /** The event, or empty for an uncatalogued kind — which must not notify. */
    public static Optional<Event> find(String kind) {
        return Optional.ofNullable(EVENTS.get(kind));
    }

    /** The event, or an exception: an emitter naming an uncatalogued kind is a defect at the emitter. */
    public static Event require(String kind) {
        return find(kind).orElseThrow(() -> new IllegalArgumentException(
                "'" + kind + "' is not a catalogued notification event (PRD-NTF-015); add it to the catalogue first"));
    }

    public static List<Event> all() {
        return EVENTS.values().stream().sorted(java.util.Comparator.comparing(Event::kind)).toList();
    }

    public static List<Category> categories() {
        return List.of(Category.values());
    }
}
