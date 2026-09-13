package aspm.app.notification;

import aspm.app.notification.channel.ChannelSender;
import aspm.app.notification.channel.HttpSenders;
import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Json;
import aspm.app.secrets.Secrets;
import aspm.app.ui.Messages;
import aspm.kernel.tenantcontext.contract.EstablishedFrom;
import aspm.kernel.tenantcontext.contract.TenantContext;
import aspm.kernel.tenantcontext.contract.TenantContextHolder;
import aspm.module.integration.domain.FailureClass;
import aspm.sharedkernel.TenantId;
import aspm.sharedkernel.secrets.SecretReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Drains the notification outbox: claim, re-check visibility, render for the recipient, send, record.
 * ADR-054 (DISPATCH class), {@code PRD-NTF-029}, {@code PRD-NTF-031}, {@code PRD-NTF-032},
 * {@code PRD-NTF-041}, {@code PRD-NTF-042}, {@code PRD-NTF-045}, {@code CON-PLT-031}, {@code CON-PLT-032},
 * {@code SEC-TEN-006}.
 *
 * <h2>Where it runs</h2>
 *
 * <p>In the runtime unit DOC-15 §4 calls the general workers — the one whose egress allowlist has the
 * mail relay and the webhook destinations (`EgressAllowlist`). {@code ASPM_ROLE=worker} starts it;
 * {@code ASPM_ROLE=all} (the compose default) runs it beside the application tier in one process;
 * {@code ASPM_ROLE=app} does not start it. The Kubernetes chart runs {@code app} and {@code worker}
 * as separate Deployments so the tier that answers requests holds no outbound-egress permission.
 *
 * <h2>Why several replicas need no leader</h2>
 *
 * <p>A claim is {@code UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)}: two workers never take
 * the same row, and a worker that dies mid-attempt leaves a lease that expires and is reclaimed by
 * time ({@code CON-PLT-031}). The result is at-least-once delivery, which is what the class promises;
 * a chat message posted twice under a crashed worker is the accepted cost, and it is bounded by the
 * lease being short.
 *
 * <h2>Tenant binding</h2>
 *
 * <p>Every tick runs under an explicit {@link TenantContext} established from the deployment's tenant
 * ({@code SEC-TEN-006}: asynchronous work carries an explicit binding), never from a row it read.
 */
public final class DeliveryWorker implements Runnable, AutoCloseable {

    static final Duration LEASE = Duration.ofSeconds(60);
    static final int BATCH = 25;
    static final Duration IDLE_SLEEP = Duration.ofSeconds(5);

    private final DataSource dataSource;
    private final UUID tenantId;
    private final Secrets secrets;
    private final Map<String, ChannelSender> senders;
    private final String owner;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;

    public DeliveryWorker(DataSource dataSource, UUID tenantId, Secrets secrets, List<ChannelSender> senders) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.secrets = Objects.requireNonNull(secrets);
        this.senders = senders.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ChannelSender::kind, s -> s));
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            host = "unknown-host";
        }
        this.owner = host + "/" + ProcessHandle.current().pid();
    }

    /** The senders a deployment ships, from environment (relay allowlists) and the egress guard. */
    public static List<ChannelSender> defaultSenders(aspm.app.egress.EgressGuard egress, Map<String, String> environment) {
        List<ChannelSender> all = new ArrayList<>(HttpSenders.all(egress));
        all.add(aspm.app.notification.channel.SmtpSender.fromEnvironment(egress, environment));
        return all;
    }

    public Map<String, ChannelSender> senders() {
        return senders;
    }

    /** Starts the loop on a virtual thread. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofVirtual().name("aspm-delivery-worker").start(this);
        }
    }

    @Override
    public void close() {
        running.set(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public void run() {
        System.Logger log = System.getLogger("aspm.notify");
        log.log(System.Logger.Level.INFO, "delivery worker started as " + owner + " for tenant " + tenantId);
        while (running.get()) {
            int processed;
            try {
                processed = tick();
            } catch (Exception e) {
                log.log(System.Logger.Level.ERROR, "delivery tick failed: " + e.getClass().getSimpleName());
                processed = 0;
            }
            if (processed == 0) {
                try {
                    Thread.sleep(IDLE_SLEEP);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** One pass: claim up to a batch, attempt each, record. Returns how many were attempted. */
    public int tick() throws Exception {
        TenantContext context = TenantContext.of(new TenantId(tenantId), "vn", EstablishedFrom.SCHEDULED_JOB_BINDING, Instant.now());
        return TenantContextHolder.callWith(context, () -> {
            List<Claim> claims = claim();
            for (Claim claim : claims) {
                attempt(claim);
            }
            housekeeping();
            return claims.size();
        });
    }

    // ==============================================================================================

    record Claim(UUID deliveryId, UUID notificationId, UUID channelId, Optional<String> address, int attempts,
            String kind, Map<String, Object> config, Optional<SecretReference> secretRef, boolean includeDetail,
            boolean channelVerified, UUID recipient, String eventKind, String subjectKind, Optional<UUID> subjectId,
            Optional<UUID> scopeNodeId, boolean mandatory, String locale, String title, Optional<String> body,
            Optional<String> link, int mergedCount) {
    }

    private List<Claim> claim() throws SQLException {
        List<Claim> out = new ArrayList<>();
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE notification_delivery d
                       SET status = 'LEASED', lease_until = now() + make_interval(secs => ?), lease_owner = ?,
                           attempts = attempts + 1, updated_at = now()
                     WHERE d.id IN (
                           SELECT id FROM notification_delivery
                            WHERE next_attempt_at <= now()
                              AND (status = 'QUEUED' OR (status = 'LEASED' AND lease_until < now()))
                            ORDER BY next_attempt_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT ?)
                    RETURNING d.id, d.notification_id, d.channel_id, d.address, d.attempts
                    """)) {
                statement.setDouble(1, LEASE.toSeconds());
                statement.setString(2, owner);
                statement.setInt(3, BATCH);
                record Leased(UUID id, UUID notificationId, UUID channelId, Optional<String> address, int attempts) {
                }
                List<Leased> leased = new ArrayList<>();
                try (ResultSet r = statement.executeQuery()) {
                    while (r.next()) {
                        leased.add(new Leased(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class),
                                Optional.ofNullable(r.getString(4)), r.getInt(5)));
                    }
                }
                for (Leased l : leased) {
                    try (PreparedStatement detail = connection.prepareStatement("""
                            SELECT c.kind, c.config::text, c.secret_ref, c.include_detail, c.verified_at IS NOT NULL,
                                   n.recipient_principal_id, n.event_kind, n.subject_kind, n.subject_id, n.scope_node_id,
                                   n.mandatory, n.locale, n.title, n.body, n.link, n.merged_count
                              FROM notification n, notification_channel c
                             WHERE n.id = ? AND c.id = ?
                            """)) {
                        detail.setObject(1, l.notificationId());
                        detail.setObject(2, l.channelId());
                        try (ResultSet r = detail.executeQuery()) {
                            if (r.next()) {
                                out.add(new Claim(l.id(), l.notificationId(), l.channelId(), l.address(), l.attempts(),
                                        r.getString(1), Json.readObject(r.getString(2)),
                                        Optional.ofNullable(r.getString(3)).map(SecretReference::parse), r.getBoolean(4), r.getBoolean(5),
                                        r.getObject(6, UUID.class), r.getString(7), r.getString(8),
                                        Optional.ofNullable(r.getObject(9, UUID.class)), Optional.ofNullable(r.getObject(10, UUID.class)),
                                        r.getBoolean(11), r.getString(12), r.getString(13), Optional.ofNullable(r.getString(14)),
                                        Optional.ofNullable(r.getString(15)), r.getInt(16)));
                            }
                        }
                    }
                }
            }
            connection.commit();
        }
        return out;
    }

    private void attempt(Claim claim) throws SQLException {
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId)) {
            // PRD-NTF-029 / PRD-NTF-031: the recipient must still exist, be active, and see the subject.
            // Suppressed rather than sent with content removed — an empty notification confirms the
            // subject exists and concerns them.
            if (!claim.channelVerified()) {
                record(connection, claim, "DEAD", Optional.of(FailureClass.DATA), "CHANNEL_UNVERIFIED", Optional.empty());
                connection.commit();
                return;
            }
            if (!stillVisible(connection, claim)) {
                record(connection, claim, "SUPPRESSED", Optional.empty(), "SUBJECT_NO_LONGER_VISIBLE", Optional.empty());
                connection.commit();
                return;
            }
            ChannelSender sender = senders.get(claim.kind());
            if (sender == null) {
                record(connection, claim, "DEAD", Optional.of(FailureClass.DATA), "NO_SENDER_FOR_" + claim.kind(), Optional.empty());
                connection.commit();
                return;
            }
            Optional<char[]> secret = claim.secretRef().flatMap(ref -> secrets.resolveTenant(tenantId, ref));
            if (claim.secretRef().isPresent() && secret.isEmpty()) {
                record(connection, claim, retryOrDead(claim, FailureClass.AUTHENTICATION), Optional.of(FailureClass.AUTHENTICATION),
                        "SECRET_UNRESOLVABLE", Optional.empty());
                connection.commit();
                return;
            }
            ChannelSender.Message message = render(claim);
            ChannelSender.Outcome outcome;
            try {
                outcome = sender.send(claim.config(), secret, claim.address(), message);
            } catch (RuntimeException e) {
                outcome = ChannelSender.Outcome.failed(FailureClass.PROTOCOL, e.getClass().getSimpleName());
            }
            if (outcome.delivered()) {
                record(connection, claim, "SENT", Optional.empty(), outcome.detail(), Optional.empty());
            } else {
                FailureClass failure = outcome.failure().orElse(FailureClass.PROTOCOL);
                record(connection, claim, retryOrDead(claim, failure), Optional.of(failure), outcome.detail(),
                        failure.backoffBefore(claim.attempts(), retryAfter(outcome.detail())));
            }
            connection.commit();
        }
    }

    /** Whether to try again ({@code QUEUED} with a later next_attempt_at) or stop ({@code DEAD}). */
    private static String retryOrDead(Claim claim, FailureClass failure) {
        return failure.backoffBefore(claim.attempts(), Optional.empty()).isPresent() ? "QUEUED" : "DEAD";
    }

    private static Optional<Duration> retryAfter(String detail) {
        int at = detail == null ? -1 : detail.indexOf("retry-after=");
        if (at < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(detail.substring(at + "retry-after=".length()).strip())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** PRD-NTF-032: subject identity and a link; detail only where the channel opted in. */
    private static ChannelSender.Message render(Claim claim) {
        Messages messages = Messages.forLocale(Notifier.locale(claim.locale()));
        String title = claim.mergedCount() > 1
                ? claim.title() + " (" + messages.getOr("ntf.merged", "{0} updates", claim.mergedCount()) + ")"
                : claim.title();
        return new ChannelSender.Message(title, claim.includeDetail() ? claim.body() : Optional.empty(), claim.link(), claim.locale());
    }

    /**
     * The visibility re-check. The recipient is active, and — for a scoped subject — either holds a
     * tenant-wide assignment, holds an assignment on an ancestor of the subject's node, or is a
     * requester/participant of the request. The same rule {@code IdentityService.principal} derives
     * scope from, applied to one subject.
     */
    static boolean stillVisible(Connection connection, Claim claim) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT lifecycle_state = 'ACTIVE' FROM principal WHERE id = ?")) {
            statement.setObject(1, claim.recipient());
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next() || !r.getBoolean(1)) {
                    return false;
                }
            }
        }
        if (claim.scopeNodeId().isEmpty() || claim.mandatory()) {
            return true;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1 FROM role_assignment a
                     WHERE a.principal_id = ? AND a.revoked_at IS NULL AND (a.expires_at IS NULL OR a.expires_at > now())
                       AND (a.scope_mode = 'TENANT'
                            OR EXISTS (SELECT 1 FROM org_closure c WHERE c.ancestor_id = a.scope_node_id AND c.descendant_id = ?)))
                    OR (? = 'ASSESSMENT_REQUEST' AND EXISTS (
                    SELECT 1 FROM assessment_request q
                     WHERE q.id = ? AND (q.requested_by = ? OR EXISTS (
                           SELECT 1 FROM assessment_request_participant p
                            WHERE p.request_id = q.id AND p.principal_id = ? AND p.removed_at IS NULL))))
                """)) {
            statement.setObject(1, claim.recipient());
            statement.setObject(2, claim.scopeNodeId().get());
            statement.setString(3, claim.subjectKind());
            statement.setObject(4, claim.subjectId().orElse(null));
            statement.setObject(5, claim.recipient());
            statement.setObject(6, claim.recipient());
            try (ResultSet r = statement.executeQuery()) {
                return r.next() && r.getBoolean(1);
            }
        }
    }

    private void record(Connection connection, Claim claim, String status, Optional<FailureClass> failure, String detail,
            Optional<Duration> backoff) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE notification_delivery SET status = ?, lease_until = NULL, lease_owner = NULL, "
                        + "last_failure_class = ?, last_detail = ?, sent_at = CASE WHEN ? = 'SENT' THEN now() ELSE sent_at END, "
                        + "next_attempt_at = CASE WHEN ? = 'QUEUED' THEN now() + make_interval(secs => ?) ELSE next_attempt_at END, "
                        + "updated_at = now() WHERE id = ?")) {
            statement.setString(1, status);
            statement.setString(2, failure.map(Enum::name).orElse(null));
            statement.setString(3, detail == null ? null : detail.length() > 300 ? detail.substring(0, 300) : detail);
            statement.setString(4, status);
            statement.setString(5, status);
            statement.setDouble(6, backoff.map(Duration::toSeconds).orElse(0L));
            statement.setObject(7, claim.deliveryId());
            statement.executeUpdate();
        }
        // Channel health, for the settings page (PRD-NTF-042, PRD-CON-028). A credential failure counts
        // against the channel; a suppressed delivery says nothing about it.
        if ("SENT".equals(status) || failure.isPresent()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE notification_channel SET last_delivery_at = now(), last_status = ?, last_detail = ?, "
                            + "consecutive_failures = CASE WHEN ? = 'SENT' THEN 0 ELSE consecutive_failures + 1 END WHERE id = ?")) {
                statement.setString(1, status);
                statement.setString(2, detail == null ? null : detail.length() > 300 ? detail.substring(0, 300) : detail);
                statement.setString(3, status);
                statement.setObject(4, claim.channelId());
                statement.executeUpdate();
            }
        }
    }

    /** Cheap, idempotent sweeps that ride the same tick: expired sign-in handshakes. */
    private void housekeeping() throws SQLException {
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM federated_login_state WHERE expires_at < now() - interval '1 hour' OR consumed_at < now() - interval '1 hour'")) {
            statement.executeUpdate();
            connection.commit();
        }
    }
}
