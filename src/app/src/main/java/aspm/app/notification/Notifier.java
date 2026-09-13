package aspm.app.notification;

import aspm.app.ui.Messages;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a domain event into notifications: one rendered row per recipient, plus one outbox row per
 * external route. Written on the CALLER's connection, inside the caller's transaction, after the
 * change it describes. {@code PRD-NTF-013}, {@code PRD-NTF-014}, {@code PRD-NTF-018}, {@code PRD-NTF-019},
 * {@code PRD-NTF-020}, {@code PRD-NTF-024}, {@code PRD-NTF-032}, {@code PRD-NTF-034}, ADR-054.
 *
 * <h2>Why on the caller's connection</h2>
 *
 * <p>{@code PRD-NTF-013}: a domain transaction must not depend on notification SUCCESS. It may — and
 * with an outbox, should — depend on the notification being RECORDED: the outbox row commits with the
 * change or not at all, so a transition that happened has its notification queued and a transition
 * that rolled back has none. Delivery, the part that can fail, happens later in the worker and never
 * touches the domain transaction. This is the same shape as the audit writer, for the same reason.
 *
 * <h2>What is rendered here and what is not</h2>
 *
 * <p>The in-product row is rendered here, in the recipient's locale, with the subject's title, because
 * it is served from inside the authorization boundary. The EXTERNAL content is rendered by the worker
 * at delivery time — {@code PRD-NTF-029} says scope is evaluated at delivery, and the worker re-checks
 * that the recipient still sees the subject before it sends anything ({@code PRD-NTF-031}).
 */
public final class Notifier {

    /** What happened, to what, by whom. */
    public record Event(String kind, String subjectKind, UUID subjectId, Optional<UUID> scopeNodeId,
            String subjectLabel, Optional<UUID> actorId, String actorLabel, Map<String, String> arguments,
            Optional<String> link) {
        public Event {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(subjectKind);
            Objects.requireNonNull(subjectLabel);
            Objects.requireNonNull(arguments);
        }
    }

    /** Coalescing window of DOC-13 §7, the same as the domain module's constant. */
    static final long COALESCE_SECONDS = 60;

    private Notifier() {
    }

    /**
     * Records the event for every recipient. The actor is never a recipient of their own action.
     *
     * @return the number of in-product notifications written or merged
     */
    public static int emit(Connection connection, UUID tenantId, Event event, Set<UUID> recipients) throws SQLException {
        NotificationCatalogue.Event catalogued = NotificationCatalogue.require(event.kind());
        int written = 0;
        for (UUID recipient : new LinkedHashSet<>(recipients)) {
            if (recipient == null || event.actorId().map(recipient::equals).orElse(false)) {
                continue;
            }
            Preference preference = Preference.load(connection, recipient);
            if (!catalogued.mandatory() && preference.mutes(catalogued.category())) {
                // PRD-NTF-020: the person unsubscribed. The in-product row is still written — a mute is
                // about interruption, and the notification centre interrupts nobody — but nothing goes
                // out. That keeps PRD-NTF-018's always-on channel honest without making a mute a lie.
            }
            Messages messages = Messages.forLocale("vi".equals(preference.locale()) ? Messages.VIETNAMESE : Messages.SOURCE);
            String title = messages.getOr("ntf." + event.kind() + ".title", event.subjectLabel(),
                    event.subjectLabel(), event.actorLabel(), event.arguments().getOrDefault("state", ""));
            String body = messages.getOr("ntf." + event.kind() + ".body", "",
                    event.subjectLabel(), event.actorLabel(), event.arguments().getOrDefault("state", ""));
            UUID notificationId = coalesceOrInsert(connection, tenantId, recipient, catalogued, event, preference.locale(), title, body);
            written++;
            if (catalogued.mandatory() || !preference.mutes(catalogued.category())) {
                enqueueDeliveries(connection, tenantId, recipient, catalogued, notificationId, preference);
            }
        }
        return written;
    }

    /** PRD-NTF-024: an unread notification for the same recipient, subject and event within the window is merged. */
    private static UUID coalesceOrInsert(Connection connection, UUID tenantId, UUID recipient, NotificationCatalogue.Event catalogued,
            Event event, String locale, String title, String body) throws SQLException {
        if (event.subjectId() != null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE notification SET merged_count = merged_count + 1, title = ?, body = ?, updated_at = now(), "
                            + "actor_principal_id = ? WHERE id = (SELECT id FROM notification WHERE recipient_principal_id = ? "
                            + "AND subject_id = ? AND event_kind = ? AND read_at IS NULL "
                            + "AND created_at > now() - make_interval(secs => ?) ORDER BY created_at DESC LIMIT 1) RETURNING id")) {
                statement.setString(1, title);
                statement.setString(2, body.isBlank() ? null : body);
                statement.setObject(3, event.actorId().orElse(null));
                statement.setObject(4, recipient);
                statement.setObject(5, event.subjectId());
                statement.setString(6, event.kind());
                statement.setDouble(7, COALESCE_SECONDS);
                try (ResultSet r = statement.executeQuery()) {
                    if (r.next()) {
                        return r.getObject(1, UUID.class);
                    }
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO notification (tenant_id, recipient_principal_id, category, event_kind, subject_kind, subject_id, "
                        + "scope_node_id, mandatory, locale, title, body, link, actor_principal_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
            statement.setObject(1, tenantId);
            statement.setObject(2, recipient);
            statement.setString(3, catalogued.category().name());
            statement.setString(4, event.kind());
            statement.setString(5, event.subjectKind());
            statement.setObject(6, event.subjectId());
            statement.setObject(7, event.scopeNodeId().orElse(null));
            statement.setBoolean(8, catalogued.mandatory());
            statement.setString(9, locale);
            statement.setString(10, title.length() > 300 ? title.substring(0, 300) : title);
            statement.setString(11, body.isBlank() ? null : body);
            statement.setString(12, event.link().orElse(null));
            statement.setObject(13, event.actorId().orElse(null));
            try (ResultSet r = statement.executeQuery()) {
                r.next();
                return r.getObject(1, UUID.class);
            }
        }
    }

    /**
     * PRD-NTF-019: one outbox row per channel that carries this category for this recipient — their
     * own route for the category, else the tenant's default route. Unverified or inactive channels
     * receive nothing (PRD-NTF-043). A merged notification does not enqueue again if a delivery for
     * it is still queued: that is the coalescing reaching the external channel too.
     */
    private static void enqueueDeliveries(Connection connection, UUID tenantId, UUID recipient, NotificationCatalogue.Event catalogued,
            UUID notificationId, Preference preference) throws SQLException {
        record Route(UUID channelId, String kind, Optional<String> address) {
        }
        List<Route> routes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT r.channel_id, c.kind, r.address, r.recipient_principal_id IS NOT NULL AS personal
                  FROM notification_route r
                  JOIN notification_channel c ON c.id = r.channel_id
                 WHERE r.category = ? AND (r.recipient_principal_id IS NULL OR r.recipient_principal_id = ?)
                   AND c.lifecycle_state = 'ACTIVE' AND c.verified_at IS NOT NULL
                 ORDER BY personal DESC
                """)) {
            statement.setString(1, catalogued.category().name());
            statement.setObject(2, recipient);
            try (ResultSet r = statement.executeQuery()) {
                Set<UUID> seen = new LinkedHashSet<>();
                while (r.next()) {
                    UUID channelId = r.getObject(1, UUID.class);
                    // A personal route on a channel overrides the tenant default on the same channel.
                    if (seen.add(channelId)) {
                        routes.add(new Route(channelId, r.getString(2), Optional.ofNullable(r.getString(3))));
                    }
                }
            }
        }
        if (routes.isEmpty()) {
            return;
        }
        Optional<String> email = Optional.empty();
        try (PreparedStatement statement = connection.prepareStatement("SELECT email FROM principal WHERE id = ?")) {
            statement.setObject(1, recipient);
            try (ResultSet r = statement.executeQuery()) {
                if (r.next()) {
                    email = Optional.ofNullable(r.getString(1));
                }
            }
        }
        java.time.OffsetDateTime due = preference.deferUntil(catalogued.mandatory());
        for (Route route : routes) {
            Optional<String> address = route.address();
            if ("EMAIL_SMTP".equals(route.kind()) && address.isEmpty()) {
                address = email;
                if (address.isEmpty()) {
                    continue;   // no address to send to; the in-product row stands (PRD-NTF-045)
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO notification_delivery (tenant_id, notification_id, channel_id, address, next_attempt_at) "
                            + "SELECT ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM notification_delivery d "
                            + "WHERE d.notification_id = ? AND d.channel_id = ? AND d.status IN ('QUEUED', 'LEASED'))")) {
                statement.setObject(1, tenantId);
                statement.setObject(2, notificationId);
                statement.setObject(3, route.channelId());
                statement.setString(4, address.orElse(null));
                statement.setObject(5, due);
                statement.setObject(6, notificationId);
                statement.setObject(7, route.channelId());
                statement.executeUpdate();
            }
        }
    }

    // ==============================================================================================
    // Audiences, for the emitters
    // ==============================================================================================

    /** The people concerned with a request: who raised it and who is delivering it. */
    public static Set<UUID> requestAudience(Connection connection, UUID requestId) throws SQLException {
        Set<UUID> out = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT requested_by FROM assessment_request WHERE id = ? "
                        + "UNION SELECT principal_id FROM assessment_request_participant WHERE request_id = ? AND removed_at IS NULL")) {
            statement.setObject(1, requestId);
            statement.setObject(2, requestId);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    UUID id = r.getObject(1, UUID.class);
                    if (id != null) {
                        out.add(id);
                    }
                }
            }
        }
        return out;
    }

    /** The request's code and scope node, for the label and the visibility re-check. */
    public record Subject(String label, Optional<UUID> scopeNodeId) {
    }

    public static Optional<Subject> request(Connection connection, UUID requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT request_code, requested_org_node_id FROM assessment_request WHERE id = ?")) {
            statement.setObject(1, requestId);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? Optional.of(new Subject(r.getString(1), Optional.ofNullable(r.getObject(2, UUID.class)))) : Optional.empty();
            }
        }
    }

    public static String displayName(Connection connection, UUID principalId) throws SQLException {
        if (principalId == null) {
            return "The platform";
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT display_name FROM principal WHERE id = ?")) {
            statement.setObject(1, principalId);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() && r.getString(1) != null ? r.getString(1) : "Somebody";
            }
        }
    }

    // ==============================================================================================

    /** A recipient's preference, defaults where none is stored. */
    record Preference(boolean muteNonMandatory, Set<String> mutedCategories, Optional<Integer> quietStart,
            Optional<Integer> quietEnd, String timezone, String locale) {

        static Preference load(Connection connection, UUID principalId) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT mute_non_mandatory, muted_categories, quiet_start_minute, quiet_end_minute, timezone, locale "
                            + "FROM notification_preference WHERE principal_id = ?")) {
                statement.setObject(1, principalId);
                try (ResultSet r = statement.executeQuery()) {
                    if (!r.next()) {
                        return new Preference(false, Set.of(), Optional.empty(), Optional.empty(), "UTC", "en");
                    }
                    String[] muted = r.getArray(2) == null ? new String[0] : (String[]) r.getArray(2).getArray();
                    Integer start = r.getObject(3, Integer.class);
                    Integer end = r.getObject(4, Integer.class);
                    return new Preference(r.getBoolean(1), Set.of(muted), Optional.ofNullable(start), Optional.ofNullable(end),
                            r.getString(5), r.getString(6));
                }
            }
        }

        boolean mutes(NotificationCatalogue.Category category) {
            return !category.mandatory && (muteNonMandatory || mutedCategories.contains(category.name()));
        }

        /** PRD-NTF-021: a non-mandatory delivery inside quiet hours waits for the window to end. */
        java.time.OffsetDateTime deferUntil(boolean mandatory) {
            java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
            if (mandatory || quietStart.isEmpty() || quietEnd.isEmpty()) {
                return now;
            }
            java.time.ZoneId zone;
            try {
                zone = java.time.ZoneId.of(timezone);
            } catch (java.time.DateTimeException e) {
                zone = java.time.ZoneOffset.UTC;
            }
            java.time.ZonedDateTime local = now.atZoneSameInstant(zone);
            int minute = local.getHour() * 60 + local.getMinute();
            int start = quietStart.get();
            int end = quietEnd.get();
            boolean inside = start <= end ? (minute >= start && minute < end) : (minute >= start || minute < end);
            if (!inside) {
                return now;
            }
            java.time.ZonedDateTime endOfWindow = local.truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                    .plusMinutes(end);
            if (!endOfWindow.isAfter(local)) {
                endOfWindow = endOfWindow.plusDays(1);
            }
            return endOfWindow.toOffsetDateTime();
        }
    }

    /** Never used with a locale the bundles lack. */
    static Locale locale(String tag) {
        return "vi".equals(tag) ? Messages.VIETNAMESE : Messages.SOURCE;
    }
}
