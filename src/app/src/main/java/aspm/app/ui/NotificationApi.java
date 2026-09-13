package aspm.app.ui;

import aspm.app.identity.IdentityService;
import aspm.app.identity.SessionPrincipalResolver;
import aspm.app.notification.NotificationCatalogue;
import aspm.app.notification.NotificationChannelService;
import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Notifications over JSON: the notification centre and a person's preferences (class G, authorized by
 * identity), and channel/route administration (class A/E under {@code ntf.channel.manage}).
 * {@code PRD-NTF-010}, {@code PRD-NTF-018}, {@code PRD-NTF-019}, {@code PRD-NTF-020}, {@code PRD-NTF-021},
 * {@code PRD-NTF-042}, {@code PRD-NTF-043}, {@code CFG-NTF-001}.
 */
public final class NotificationApi {

    private final DataSource dataSource;
    private final SessionPrincipalResolver resolver;
    private final NotificationChannelService channels;

    public NotificationApi(DataSource dataSource, SessionPrincipalResolver resolver, NotificationChannelService channels) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.resolver = Objects.requireNonNull(resolver);
        this.channels = Objects.requireNonNull(channels);
    }

    // ==============================================================================================
    // The notification centre — class G, the caller's own rows
    // ==============================================================================================

    /** {@code GET /api/ui/notifications?unread=1}. */
    public Dispatcher.Response list(Dispatcher.Request request) throws SQLException {
        Optional<IdentityService.Session> session = fullyAuthenticated(request);
        if (session.isEmpty()) {
            return unauthenticated();
        }
        boolean unreadOnly = request.query().containsKey("unread");
        List<Map<String, Object>> rows = new ArrayList<>();
        int unread = 0;
        try (Connection c = TenantConnections.openForTenant(dataSource, resolver.tenantId())) {
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT id, category, event_kind, subject_kind, subject_id, mandatory, title, body, link, merged_count, created_at, "
                            + "updated_at, read_at FROM notification WHERE recipient_principal_id = ? "
                            + (unreadOnly ? "AND read_at IS NULL " : "") + "ORDER BY updated_at DESC LIMIT 100")) {
                statement.setObject(1, session.get().principalId());
                try (ResultSet r = statement.executeQuery()) {
                    while (r.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", r.getObject(1, UUID.class).toString());
                        row.put("category", r.getString(2));
                        row.put("eventKind", r.getString(3));
                        row.put("subjectKind", r.getString(4));
                        row.put("subjectId", r.getObject(5, UUID.class) == null ? null : r.getObject(5, UUID.class).toString());
                        row.put("mandatory", r.getBoolean(6));
                        row.put("title", r.getString(7));
                        row.put("body", r.getString(8));
                        row.put("link", r.getString(9));
                        row.put("mergedCount", r.getInt(10));
                        row.put("createdAt", r.getObject(11, java.time.OffsetDateTime.class).toString());
                        row.put("updatedAt", r.getObject(12, java.time.OffsetDateTime.class).toString());
                        row.put("readAt", r.getObject(13, java.time.OffsetDateTime.class) == null ? null
                                : r.getObject(13, java.time.OffsetDateTime.class).toString());
                        rows.add(row);
                    }
                }
            }
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT count(*) FROM notification WHERE recipient_principal_id = ? AND read_at IS NULL")) {
                statement.setObject(1, session.get().principalId());
                try (ResultSet r = statement.executeQuery()) {
                    r.next();
                    unread = r.getInt(1);
                }
            }
            c.commit();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", rows);
        body.put("unread", unread);
        return json(body);
    }

    /** {@code POST /api/ui/notifications/{id}/read} — and {@code all} as the id marks everything. */
    public Dispatcher.Response markRead(Dispatcher.Request request) throws SQLException {
        Optional<IdentityService.Session> session = fullyAuthenticated(request);
        if (session.isEmpty()) {
            return unauthenticated();
        }
        String id = request.pathVariables().getOrDefault("id", "");
        try (Connection c = TenantConnections.openForTenant(dataSource, resolver.tenantId())) {
            int updated;
            if ("all".equals(id)) {
                try (PreparedStatement statement = c.prepareStatement(
                        "UPDATE notification SET read_at = now(), updated_at = now() WHERE recipient_principal_id = ? AND read_at IS NULL")) {
                    statement.setObject(1, session.get().principalId());
                    updated = statement.executeUpdate();
                }
            } else {
                UUID notificationId;
                try {
                    notificationId = UUID.fromString(id);
                } catch (IllegalArgumentException e) {
                    return Dispatcher.Response.notFound();
                }
                // The recipient is in the predicate: a notification id from somebody else's centre marks
                // nothing (product principle 4, applied to a read mark).
                try (PreparedStatement statement = c.prepareStatement(
                        "UPDATE notification SET read_at = coalesce(read_at, now()), updated_at = now() "
                                + "WHERE id = ? AND recipient_principal_id = ?")) {
                    statement.setObject(1, notificationId);
                    statement.setObject(2, session.get().principalId());
                    updated = statement.executeUpdate();
                }
            }
            c.commit();
            return json(Map.of("updated", updated));
        }
    }

    /** {@code GET /api/ui/account/notification-preferences}. */
    public Dispatcher.Response preferences(Dispatcher.Request request) throws SQLException {
        Optional<IdentityService.Session> session = fullyAuthenticated(request);
        if (session.isEmpty()) {
            return unauthenticated();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        try (Connection c = TenantConnections.openForTenant(dataSource, resolver.tenantId());
                PreparedStatement statement = c.prepareStatement(
                        "SELECT mute_non_mandatory, muted_categories, quiet_start_minute, quiet_end_minute, timezone, locale "
                                + "FROM notification_preference WHERE principal_id = ?")) {
            statement.setObject(1, session.get().principalId());
            try (ResultSet r = statement.executeQuery()) {
                if (r.next()) {
                    body.put("muteNonMandatory", r.getBoolean(1));
                    body.put("mutedCategories", r.getArray(2) == null ? List.of() : List.of((String[]) r.getArray(2).getArray()));
                    body.put("quietStartMinute", r.getObject(3, Integer.class));
                    body.put("quietEndMinute", r.getObject(4, Integer.class));
                    body.put("timezone", r.getString(5));
                    body.put("locale", r.getString(6));
                } else {
                    body.put("muteNonMandatory", false);
                    body.put("mutedCategories", List.of());
                    body.put("quietStartMinute", null);
                    body.put("quietEndMinute", null);
                    body.put("timezone", "UTC");
                    body.put("locale", "en");
                }
            }
            c.commit();
        }
        body.put("categories", NotificationCatalogue.categories().stream().map(cat -> Map.of(
                "code", cat.name(), "label", cat.label, "mandatory", cat.mandatory)).toList());
        return json(body);
    }

    /** {@code POST /api/ui/account/notification-preferences}. */
    public Dispatcher.Response savePreferences(Dispatcher.Request request) throws SQLException {
        Optional<IdentityService.Session> session = fullyAuthenticated(request);
        if (session.isEmpty()) {
            return unauthenticated();
        }
        Map<String, Object> body = request.body().orElse(Map.of());
        boolean mute = Boolean.TRUE.equals(body.get("muteNonMandatory"));
        List<String> muted = new ArrayList<>();
        if (body.get("mutedCategories") instanceof List<?> list) {
            for (Object o : list) {
                NotificationCatalogue.Category category = NotificationCatalogue.Category.valueOf(String.valueOf(o));
                if (category.mandatory) {
                    // PRD-NTF-017: refused, not silently dropped, so the interface learns the rule.
                    return new Dispatcher.Response(400, Map.of("code", "MANDATORY_CATEGORY",
                            "message", category.label + " cannot be muted"), Map.of());
                }
                muted.add(category.name());
            }
        }
        Integer start = body.get("quietStartMinute") instanceof Number n ? n.intValue() : null;
        Integer end = body.get("quietEndMinute") instanceof Number n ? n.intValue() : null;
        if ((start == null) != (end == null)) {
            return new Dispatcher.Response(400, Map.of("code", "QUIET_HOURS_INCOMPLETE",
                    "message", "quiet hours need both a start and an end"), Map.of());
        }
        String timezone = body.get("timezone") instanceof String tz && !tz.isBlank() ? tz.strip() : "UTC";
        try {
            java.time.ZoneId.of(timezone);
        } catch (java.time.DateTimeException e) {
            return new Dispatcher.Response(400, Map.of("code", "TIMEZONE_UNKNOWN", "message", "that time zone is not recognised"), Map.of());
        }
        String locale = "vi".equals(body.get("locale")) ? "vi" : "en";
        try (Connection c = TenantConnections.openForTenant(dataSource, resolver.tenantId());
                PreparedStatement statement = c.prepareStatement("""
                        INSERT INTO notification_preference (tenant_id, principal_id, mute_non_mandatory, muted_categories,
                            quiet_start_minute, quiet_end_minute, timezone, locale)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, principal_id) DO UPDATE SET mute_non_mandatory = EXCLUDED.mute_non_mandatory,
                            muted_categories = EXCLUDED.muted_categories, quiet_start_minute = EXCLUDED.quiet_start_minute,
                            quiet_end_minute = EXCLUDED.quiet_end_minute, timezone = EXCLUDED.timezone, locale = EXCLUDED.locale,
                            updated_at = now()
                        """)) {
            statement.setObject(1, resolver.tenantId());
            statement.setObject(2, session.get().principalId());
            statement.setBoolean(3, mute);
            statement.setArray(4, c.createArrayOf("text", muted.toArray()));
            statement.setObject(5, start);
            statement.setObject(6, end);
            statement.setString(7, timezone);
            statement.setString(8, locale);
            statement.executeUpdate();
            c.commit();
        }
        return preferences(request);
    }

    // ==============================================================================================
    // Administration — class A/E, ntf.channel.manage
    // ==============================================================================================

    /** {@code GET /api/ui/settings/notification-channels}. */
    public Dispatcher.Response listChannels(Dispatcher.Request request) throws SQLException {
        Principal principal = request.principal();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", channels.list(principal).stream().map(NotificationApi::channel).toList());
        body.put("routes", channels.routes(principal).stream().map(NotificationApi::route).toList());
        body.put("kinds", channels.kinds());
        body.putAll(NotificationChannelService.catalogue());
        body.put("mayManage", principal.holds(NotificationChannelService.MANAGE));
        body.put("elevated", principal.stepUpAuthenticated());
        return json(body);
    }

    /** {@code POST /api/ui/settings/notification-channels}. */
    public Dispatcher.Response createChannel(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        return json(channel(channels.create(request.principal(), text(body, "code").orElse(""), text(body, "displayName").orElse(""),
                text(body, "kind").orElse(""), config(body), text(body, "secret").map(String::toCharArray),
                Boolean.TRUE.equals(body.get("includeDetail")))));
    }

    /** {@code POST /api/ui/settings/notification-channels/{id}}. */
    public Dispatcher.Response updateChannel(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        int rowVersion = body.get("rowVersion") instanceof Number n ? n.intValue() : -1;
        if (rowVersion < 0) {
            return new Dispatcher.Response(400, Map.of("code", "ROW_VERSION_REQUIRED", "message", "the row version being edited is required"), Map.of());
        }
        return json(channel(channels.update(request.principal(), id(request), text(body, "displayName").orElse(null),
                body.containsKey("config") ? config(body) : null, text(body, "secret").map(String::toCharArray),
                Boolean.TRUE.equals(body.get("includeDetail")), rowVersion)));
    }

    /** {@code POST /api/ui/settings/notification-channels/{id}/transition}. */
    public Dispatcher.Response transitionChannel(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        return json(channel(channels.transition(request.principal(), id(request), String.valueOf(body.getOrDefault("state", "")))));
    }

    /** {@code POST /api/ui/settings/notification-channels/{id}/verify}. */
    public Dispatcher.Response verifyChannel(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        return json(channel(channels.sendVerification(request.principal(), id(request), text(body, "target"))));
    }

    /** {@code POST /api/ui/settings/notification-channels/{id}/confirm}. */
    public Dispatcher.Response confirmChannel(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        return json(channel(channels.confirmVerification(request.principal(), id(request), text(body, "code").orElse(""))));
    }

    /** {@code GET /api/ui/settings/notification-channels/{id}/deliveries}. */
    public Dispatcher.Response deliveries(Dispatcher.Request request) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NotificationChannelService.Delivery d : channels.deliveries(request.principal(), id(request), 50)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.id().toString());
            row.put("status", d.status());
            row.put("attempts", d.attempts());
            row.put("failureClass", d.failureClass().orElse(null));
            row.put("detail", d.detail().orElse(null));
            row.put("address", d.address().orElse(null));
            row.put("eventKind", d.eventKind());
            row.put("title", d.title());
            row.put("createdAt", d.createdAt().toString());
            row.put("sentAt", d.sentAt().map(Object::toString).orElse(null));
            rows.add(row);
        }
        return json(Map.of("rows", rows));
    }

    /** {@code POST /api/ui/settings/notification-routes}. */
    public Dispatcher.Response setRoutes(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        List<Map<String, String>> mappings = new ArrayList<>();
        if (body.get("routes") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, String> mapping = new LinkedHashMap<>();
                    m.forEach((k, v) -> mapping.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
                    mappings.add(mapping);
                }
            }
        }
        return json(Map.of("routes", channels.setRoutes(request.principal(), mappings).stream().map(NotificationApi::route).toList()));
    }

    // ==============================================================================================

    private Optional<IdentityService.Session> fullyAuthenticated(Dispatcher.Request request) {
        return resolver.sessionFor(request.headers()).filter(s -> "FULLY_AUTHENTICATED".equals(s.factorState()));
    }

    private static Dispatcher.Response unauthenticated() {
        return new Dispatcher.Response(401, Map.of("code", "UNAUTHENTICATED", "message", "sign in to see your notifications"), Map.of());
    }

    private static UUID id(Dispatcher.Request request) {
        try {
            return UUID.fromString(request.pathVariables().getOrDefault("id", ""));
        } catch (IllegalArgumentException e) {
            throw new Dispatcher.UnauthorizedException("no such channel");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config(Map<String, Object> body) {
        return body.get("config") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Map<String, Object> channel(NotificationChannelService.Channel c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", c.id().toString());
        row.put("code", c.code());
        row.put("displayName", c.displayName());
        row.put("kind", c.kind());
        row.put("config", c.config());
        row.put("secretHeld", c.secretHeld());
        row.put("includeDetail", c.includeDetail());
        row.put("verified", c.verified());
        row.put("verificationTarget", c.verificationTarget().orElse(null));
        row.put("verificationSentAt", c.verificationSentAt().map(Object::toString).orElse(null));
        row.put("lifecycleState", c.lifecycleState());
        row.put("lastStatus", c.lastStatus().orElse(null));
        row.put("lastDetail", c.lastDetail().orElse(null));
        row.put("consecutiveFailures", c.consecutiveFailures());
        row.put("rowVersion", c.rowVersion());
        return row;
    }

    private static Map<String, Object> route(NotificationChannelService.Route r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.id().toString());
        row.put("category", r.category());
        row.put("channelId", r.channelId().toString());
        row.put("channelCode", r.channelCode());
        row.put("recipientPrincipalId", r.recipientPrincipalId().map(UUID::toString).orElse(null));
        row.put("address", r.address().orElse(null));
        return row;
    }

    private static Optional<String> text(Map<String, Object> body, String key) {
        return body.get(key) instanceof String s && !s.isBlank() ? Optional.of(s.strip()) : Optional.empty();
    }

    private static Dispatcher.Response json(Object body) {
        return new Dispatcher.Response(200, body, Map.of("Content-Type", "application/json; charset=utf-8"));
    }
}
