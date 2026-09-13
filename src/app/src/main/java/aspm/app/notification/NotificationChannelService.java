package aspm.app.notification;

import aspm.app.notification.channel.ChannelSender;
import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Json;
import aspm.app.runtime.Principal;
import aspm.app.secrets.Secrets;
import aspm.app.ui.Messages;
import aspm.sharedkernel.secrets.SecretReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
 * Channels, routes and the verification handshake: the administration half of notification delivery.
 * {@code PRD-NTF-003}, {@code PRD-NTF-019}, {@code PRD-NTF-042}, {@code PRD-NTF-043}, {@code PRD-CON-017},
 * {@code PRD-CON-021}, {@code CFG-NTF-001}.
 *
 * <p>The secret arrives once, in a create or update body, and leaves as a reference. Configuration is
 * validated by the sender for its kind before anything is written ({@code PRD-CON-017}). A channel
 * starts UNVERIFIED and receives exactly one thing before it is verified: a six-digit code, sent
 * through itself, which the administrator types back ({@code PRD-NTF-043}).
 */
public final class NotificationChannelService {

    public static final String MANAGE = "ntf.channel.manage";

    public record Channel(UUID id, String code, String displayName, String kind, Map<String, Object> config,
            boolean secretHeld, boolean includeDetail, boolean verified, Optional<String> verificationTarget,
            Optional<java.time.OffsetDateTime> verificationSentAt, String lifecycleState, Optional<String> lastStatus,
            Optional<String> lastDetail, int consecutiveFailures, int rowVersion) {
    }

    public record Route(UUID id, String category, UUID channelId, String channelCode, Optional<UUID> recipientPrincipalId,
            Optional<String> address) {
    }

    public record Delivery(UUID id, String status, int attempts, Optional<String> failureClass, Optional<String> detail,
            Optional<String> address, String eventKind, String title, java.time.OffsetDateTime createdAt,
            Optional<java.time.OffsetDateTime> sentAt) {
    }

    private final DataSource dataSource;
    private final Secrets secrets;
    private final Map<String, ChannelSender> senders;
    private final SecureRandom random = new SecureRandom();
    private final aspm.app.audit.AuditTrail audit = new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public NotificationChannelService(DataSource dataSource, Secrets secrets, List<ChannelSender> senders) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.secrets = Objects.requireNonNull(secrets);
        this.senders = senders.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ChannelSender::kind, s -> s));
    }

    public List<String> kinds() {
        return senders.keySet().stream().sorted().toList();
    }

    // ==============================================================================================
    // Reads
    // ==============================================================================================

    public List<Channel> list(Principal principal) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, principal)) {
            return channels(connection, "ORDER BY CASE lifecycle_state WHEN 'ACTIVE' THEN 0 WHEN 'DISABLED' THEN 1 ELSE 2 END, display_name", null);
        }
    }

    public List<Route> routes(Principal principal) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, principal)) {
            List<Route> out = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT r.id, r.category, r.channel_id, c.code, r.recipient_principal_id, r.address
                      FROM notification_route r JOIN notification_channel c ON c.id = r.channel_id
                     ORDER BY r.category, c.code, r.recipient_principal_id NULLS FIRST
                    """); ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.add(new Route(r.getObject(1, UUID.class), r.getString(2), r.getObject(3, UUID.class), r.getString(4),
                            Optional.ofNullable(r.getObject(5, UUID.class)), Optional.ofNullable(r.getString(6))));
                }
            }
            return out;
        }
    }

    public List<Delivery> deliveries(Principal principal, UUID channelId, int limit) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT d.id, d.status, d.attempts, d.last_failure_class, d.last_detail, d.address, n.event_kind, n.title,
                               d.created_at, d.sent_at
                          FROM notification_delivery d JOIN notification n ON n.id = d.notification_id
                         WHERE d.channel_id = ? ORDER BY d.created_at DESC LIMIT ?
                        """)) {
            statement.setObject(1, channelId);
            statement.setInt(2, Math.max(1, Math.min(limit, 200)));
            List<Delivery> out = new ArrayList<>();
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    // The address is a person's email: shown only as its domain, which is what an
                    // administrator needs to see "the relay rejected everything at example.com".
                    Optional<String> address = Optional.ofNullable(r.getString(6))
                            .map(a -> a.contains("@") ? "…@" + a.substring(a.indexOf('@') + 1) : a);
                    out.add(new Delivery(r.getObject(1, UUID.class), r.getString(2), r.getInt(3), Optional.ofNullable(r.getString(4)),
                            Optional.ofNullable(r.getString(5)), address, r.getString(7), r.getString(8),
                            r.getObject(9, java.time.OffsetDateTime.class), Optional.ofNullable(r.getObject(10, java.time.OffsetDateTime.class))));
                }
            }
            return out;
        }
    }

    // ==============================================================================================
    // Writes
    // ==============================================================================================

    public Channel create(Principal actor, String code, String displayName, String kind, Map<String, Object> config,
            Optional<char[]> secret, boolean includeDetail) throws SQLException {
        ChannelSender sender = senderFor(kind);
        if (code == null || !code.matches("[a-z][a-z0-9-]{1,31}")) {
            throw new IllegalArgumentException("the channel code is 2–32 lower-case letters, digits or dashes, starting with a letter");
        }
        if (displayName == null || displayName.isBlank() || displayName.strip().length() > 80) {
            throw new IllegalArgumentException("a display name of at most 80 characters is required");
        }
        boolean secretPresent = secret.map(s -> s.length > 0).orElse(false);
        if (sender.requiresSecret() && !secretPresent) {
            throw new IllegalArgumentException("this channel kind needs a credential");
        }
        Map<String, Object> validated = sender.validate(config == null ? Map.of() : config, secretPresent);
        Optional<SecretReference> ref = secretPresent
                ? Optional.of(secrets.store(actor.tenantId(), "notification_channel", code + "-secret", secret.get()))
                : Optional.empty();
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            UUID id;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO notification_channel (tenant_id, code, display_name, kind, config, secret_ref, include_detail, created_by, updated_by) "
                            + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) RETURNING id")) {
                statement.setObject(1, actor.tenantId());
                statement.setString(2, code);
                statement.setString(3, displayName.strip());
                statement.setString(4, kind);
                statement.setString(5, Json.write(validated));
                statement.setString(6, ref.map(SecretReference::toString).orElse(null));
                statement.setBoolean(7, includeDetail);
                statement.setObject(8, actor.principalId());
                statement.setObject(9, actor.principalId());
                try (ResultSet r = statement.executeQuery()) {
                    r.next();
                    id = r.getObject(1, UUID.class);
                }
            }
            audit.domainChangeBy(connection, actor.principalId(), "notification_channel",
                    aspm.kernel.audit.contract.DomainChangeKind.CREATED, id, null,
                    Map.of("code", code, "kind", kind, "secret_held", ref.isPresent(), "include_detail", includeDetail));
            connection.commit();
            return channels(connection, "WHERE id = ?", id).get(0);
        }
    }

    public Channel update(Principal actor, UUID id, String displayName, Map<String, Object> config, Optional<char[]> secret,
            boolean includeDetail, int rowVersion) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Channel current = one(connection, id);
            if (current.rowVersion() != rowVersion) {
                throw new aspm.app.runtime.Dispatcher.UnauthorizedException("stale row_version");
            }
            ChannelSender sender = senderFor(current.kind());
            boolean newSecret = secret.map(s -> s.length > 0).orElse(false);
            Map<String, Object> validated = sender.validate(config == null ? current.config() : config, newSecret || current.secretHeld());
            Optional<SecretReference> ref = currentRef(connection, id);
            if (newSecret) {
                SecretReference fresh = secrets.store(actor.tenantId(), "notification_channel", current.code() + "-secret", secret.get());
                ref.ifPresent(old -> secrets.destroy(actor.tenantId(), old));
                ref = Optional.of(fresh);
            }
            boolean destinationChanged = !Json.write(validated).equals(Json.write(current.config())) || newSecret;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE notification_channel SET display_name = ?, config = ?::jsonb, secret_ref = ?, include_detail = ?, "
                            // PRD-NTF-043: a changed destination is a new destination and must verify again.
                            + "verified_at = CASE WHEN ? THEN NULL ELSE verified_at END, "
                            + "verification_hash = CASE WHEN ? THEN NULL ELSE verification_hash END, "
                            + "verification_sent_at = CASE WHEN ? THEN NULL ELSE verification_sent_at END, "
                            + "updated_at = now(), updated_by = ?, row_version = row_version + 1 WHERE id = ? AND row_version = ?")) {
                statement.setString(1, displayName == null || displayName.isBlank() ? current.displayName() : displayName.strip());
                statement.setString(2, Json.write(validated));
                statement.setString(3, ref.map(SecretReference::toString).orElse(null));
                statement.setBoolean(4, includeDetail);
                statement.setBoolean(5, destinationChanged);
                statement.setBoolean(6, destinationChanged);
                statement.setBoolean(7, destinationChanged);
                statement.setObject(8, actor.principalId());
                statement.setObject(9, id);
                statement.setInt(10, rowVersion);
                if (statement.executeUpdate() != 1) {
                    throw new aspm.app.runtime.Dispatcher.UnauthorizedException("stale row_version");
                }
            }
            audit.domainChangeBy(connection, actor.principalId(), "notification_channel",
                    aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id, null,
                    Map.of("secret_replaced", newSecret, "destination_changed", destinationChanged, "include_detail", includeDetail));
            connection.commit();
            return channels(connection, "WHERE id = ?", id).get(0);
        }
    }

    public Channel transition(Principal actor, UUID id, String target) throws SQLException {
        if (!List.of("ACTIVE", "DISABLED", "RETIRED").contains(target)) {
            throw new IllegalArgumentException("the target state is not one a channel can take");
        }
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Channel current = one(connection, id);
            if ("RETIRED".equals(current.lifecycleState())) {
                throw new IllegalArgumentException("a retired channel stays retired");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE notification_channel SET lifecycle_state = ?, updated_at = now(), updated_by = ?, row_version = row_version + 1 WHERE id = ?")) {
                statement.setString(1, target);
                statement.setObject(2, actor.principalId());
                statement.setObject(3, id);
                statement.executeUpdate();
            }
            if ("RETIRED".equals(target)) {
                currentRef(connection, id).ifPresent(ref -> secrets.destroy(actor.tenantId(), ref));
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM notification_route WHERE channel_id = ?")) {
                    statement.setObject(1, id);
                    statement.executeUpdate();
                }
            }
            audit.domainChangeBy(connection, actor.principalId(), "notification_channel",
                    "RETIRED".equals(target) ? aspm.kernel.audit.contract.DomainChangeKind.RETIRED
                            : aspm.kernel.audit.contract.DomainChangeKind.TRANSITIONED,
                    id, null, Map.of("from", current.lifecycleState(), "to", target));
            connection.commit();
            return channels(connection, "WHERE id = ?", id).get(0);
        }
    }

    /**
     * PRD-NTF-043, step one: send a six-digit code through the channel, to the target address for a
     * kind that has one. Synchronous, so the administrator sees a failure to reach the destination on
     * the page they are standing on rather than in a delivery log later.
     */
    public Channel sendVerification(Principal actor, UUID id, Optional<String> target) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Channel current = one(connection, id);
            ChannelSender sender = senderFor(current.kind());
            if ("EMAIL_SMTP".equals(current.kind()) && target.filter(t -> t.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")).isEmpty()) {
                throw new IllegalArgumentException("an email address to send the code to is required");
            }
            String code = String.format("%06d", random.nextInt(1_000_000));
            Messages messages = Messages.forLocale(Messages.SOURCE);
            ChannelSender.Message message = new ChannelSender.Message(
                    messages.get("ntf.channel.verification.title"),
                    Optional.of(messages.get("ntf.channel.verification.body", code)), Optional.empty(), "en");
            Optional<char[]> secret = currentRef(connection, id).flatMap(ref -> secrets.resolveTenant(actor.tenantId(), ref));
            ChannelSender.Outcome outcome = sender.send(current.config(), secret, target, message);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE notification_channel SET verification_hash = ?, verification_sent_at = ?, verification_target = ?, "
                            + "last_delivery_at = now(), last_status = ?, last_detail = ?, "
                            + "consecutive_failures = CASE WHEN ? THEN 0 ELSE consecutive_failures + 1 END WHERE id = ?")) {
                statement.setBytes(1, outcome.delivered() ? sha256(code) : null);
                statement.setObject(2, outcome.delivered() ? java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC) : null);
                statement.setString(3, target.orElse(null));
                statement.setString(4, outcome.delivered() ? "VERIFICATION_SENT" : "VERIFICATION_FAILED");
                statement.setString(5, outcome.detail().length() > 300 ? outcome.detail().substring(0, 300) : outcome.detail());
                statement.setBoolean(6, outcome.delivered());
                statement.setObject(7, id);
                statement.executeUpdate();
            }
            audit.domainChangeBy(connection, actor.principalId(), "notification_channel",
                    aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id, null,
                    Map.of("verification", outcome.delivered() ? "sent" : "failed", "failure", outcome.failure().map(Enum::name).orElse("none")));
            connection.commit();
            if (!outcome.delivered()) {
                throw new IllegalArgumentException("the verification message could not be delivered: " + outcome.detail());
            }
            return channels(connection, "WHERE id = ?", id).get(0);
        }
    }

    /** PRD-NTF-043, step two: the code, typed back within fifteen minutes. */
    public Channel confirmVerification(Principal actor, UUID id, String code) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            boolean ok;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE notification_channel SET verified_at = now(), verification_hash = NULL, verification_sent_at = NULL, "
                            + "updated_at = now(), updated_by = ?, row_version = row_version + 1 "
                            + "WHERE id = ? AND verification_hash = ? AND verification_sent_at > now() - interval '15 minutes'")) {
                statement.setObject(1, actor.principalId());
                statement.setObject(2, id);
                statement.setBytes(3, sha256(code == null ? "" : code.strip()));
                ok = statement.executeUpdate() == 1;
            }
            audit.domainChangeBy(connection, actor.principalId(), "notification_channel",
                    aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id, null, Map.of("verification", ok ? "confirmed" : "refused"));
            connection.commit();
            if (!ok) {
                throw new IllegalArgumentException("that code was not accepted; request a new one");
            }
            return channels(connection, "WHERE id = ?", id).get(0);
        }
    }

    /** Replaces the tenant-level routes wholesale: category → channel (+ optional address). */
    public List<Route> setRoutes(Principal actor, List<Map<String, String>> mappings) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            try (PreparedStatement clear = connection.prepareStatement("DELETE FROM notification_route WHERE recipient_principal_id IS NULL")) {
                clear.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO notification_route (tenant_id, category, channel_id, address, created_by) VALUES (?, ?, ?, ?, ?)")) {
                for (Map<String, String> m : mappings) {
                    String category = Objects.requireNonNull(m.get("category"), "a category is required");
                    NotificationCatalogue.Category.valueOf(category);
                    insert.setObject(1, actor.tenantId());
                    insert.setString(2, category);
                    insert.setObject(3, UUID.fromString(Objects.requireNonNull(m.get("channelId"), "a channel is required")));
                    insert.setString(4, m.get("address") == null || m.get("address").isBlank() ? null : m.get("address").strip());
                    insert.setObject(5, actor.principalId());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            audit.domainChangeBy(connection, actor.principalId(), "notification_channel",
                    aspm.kernel.audit.contract.DomainChangeKind.UPDATED, null, null, Map.of("routes", mappings.size()));
            connection.commit();
        }
        return routes(actor);
    }

    // ==============================================================================================

    private ChannelSender senderFor(String kind) {
        ChannelSender sender = senders.get(kind);
        if (sender == null) {
            throw new IllegalArgumentException("'" + kind + "' is not a channel kind this deployment ships; the options are " + kinds());
        }
        return sender;
    }

    private static Optional<SecretReference> currentRef(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT secret_ref FROM notification_channel WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? Optional.ofNullable(r.getString(1)).map(SecretReference::parse) : Optional.empty();
            }
        }
    }

    private static Channel one(Connection connection, UUID id) throws SQLException {
        return channels(connection, "WHERE id = ?", id).stream().findFirst()
                .orElseThrow(() -> new aspm.app.runtime.Dispatcher.UnauthorizedException("no such channel"));
    }

    private static List<Channel> channels(Connection connection, String where, Object parameter) throws SQLException {
        List<Channel> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, code, display_name, kind, config::text, secret_ref IS NOT NULL, include_detail, verified_at IS NOT NULL, "
                        + "verification_target, verification_sent_at, lifecycle_state, last_status, last_detail, consecutive_failures, "
                        + "row_version FROM notification_channel " + where)) {
            if (parameter != null) {
                statement.setObject(1, parameter);
            }
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.add(new Channel(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                            Json.readObject(r.getString(5)), r.getBoolean(6), r.getBoolean(7), r.getBoolean(8),
                            Optional.ofNullable(r.getString(9)), Optional.ofNullable(r.getObject(10, java.time.OffsetDateTime.class)),
                            r.getString(11), Optional.ofNullable(r.getString(12)), Optional.ofNullable(r.getString(13)), r.getInt(14),
                            r.getInt(15)));
                }
            }
        }
        return out;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /** For the settings page: the whole catalogue with its categories. */
    public static Map<String, Object> catalogue() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", NotificationCatalogue.categories().stream().map(c -> Map.of(
                "code", c.name(), "label", c.label, "mandatory", c.mandatory)).toList());
        out.put("events", NotificationCatalogue.all().stream().map(e -> Map.of(
                "kind", e.kind(), "category", e.category().name(), "label", e.label(), "digestible", e.digestible(),
                "mandatory", e.mandatory(), "emitted", e.emitted())).toList());
        return out;
    }
}
