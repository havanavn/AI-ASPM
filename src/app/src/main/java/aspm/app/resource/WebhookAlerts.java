package aspm.app.resource;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Json;
import aspm.app.runtime.Principal;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

/**
 * Telling somebody when a new vulnerability arrives. V040, DOC-13.
 *
 * <h2>The enforcement point for a new egress path</h2>
 *
 * <p>{@code TST-AUZ-001} requires a new egress path to have an enforcement point and a test. This is
 * it, and it is deliberately here rather than at the row that stores the URL: the destination is
 * tenant-configured data, so it cannot be an allowlist in code, and a check performed only when the
 * subscription is saved is a check that a later edit through any other path walks around.
 *
 * <p>Three refusals, and each is a specific attack:
 *
 * <ul>
 *   <li><b>https only.</b> The payload names which application holds which unfixed critical
 *       vulnerability. Over http that is a map of the estate's weaknesses, readable by anything on
 *       the path. The schema also has a CHECK, and both exist because either alone is one place.
 *   <li><b>No private, loopback or link-local destination.</b> A webhook pointed at
 *       {@code 169.254.169.254} turns an alert subscription into cloud credential exfiltration; one
 *       pointed at {@code 127.0.0.1} reaches services that trust the local network. This is
 *       server-side request forgery — the defect class this product exists to find in customers'
 *       software, and it would be indefensible to ship it here.
 *   <li><b>Resolved, then checked.</b> The hostname is resolved and every returned address is
 *       tested. Checking the literal text would be defeated by a name that resolves to a private
 *       address, which is the standard bypass and is trivial to arrange.
 * </ul>
 *
 * <p>What this does NOT close is DNS rebinding: the address is resolved for the check and resolved
 * again by the HTTP client. Closing it means pinning the connection to the checked address, which
 * this client cannot express. Stated rather than left to be discovered.
 *
 * <h2>Delivery is best-effort, and its failures are recorded</h2>
 *
 * <p>An alert that could not be sent must never fail an ingestion: the bill of materials is the
 * record, and losing it because a chat server was down would be the tail wagging the dog. So every
 * delivery is attempted after the submission has committed, with a short timeout, and every attempt
 * — success or failure — is written to {@code alert_delivery}. Recording only successes would make a
 * webhook that quietly stopped working indistinguishable from a quiet week.
 */
public final class WebhookAlerts {

    /** The permission that governs the subscriptions. */
    public static final String MANAGE = "sbm.alert.manage";

    /** One configured subscription, as an administrator sees it. The secret is never returned. */
    public record Subscription(String id, String label, String url, int minSeverityOrdinal,
            String minSeverityCode, String scopeNodeId, String scopeNodeName, boolean active,
            String lastDeliveryAt, String lastStatus, int consecutiveFailures, boolean signed) {
    }

    /** What a delivery says. Flat, because a receiver should not have to walk a tree to route it. */
    public record Event(String advisoryKey, String severity, Double cvss, String summary,
            String componentName, String componentVersion, String fixedVersion,
            String applicationName, String projectName, String repositoryName, UUID advisoryId,
            UUID assetId) {
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // NEVER follows redirects. A 302 is a destination nobody checked, which would walk
            // straight around the address checks above.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public WebhookAlerts(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ==============================================================================================
    // Configuration
    // ==============================================================================================

    public List<Subscription> list(Principal principal) throws SQLException {
        List<Subscription> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT w.id::text, w.label, w.url, w.min_severity_ordinal,
                               (SELECT code FROM severity_level s
                                 WHERE s.ordinal = w.min_severity_ordinal),
                               w.scope_node_id::text, n.name, w.active,
                               to_char(w.last_delivery_at, 'YYYY-MM-DD HH24:MI'), w.last_status,
                               w.consecutive_failures, (w.secret_hash IS NOT NULL)
                          FROM alert_webhook w
                          LEFT JOIN org_node n ON n.id = w.scope_node_id
                         ORDER BY w.active DESC, w.label
                        """)) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Subscription(r.getString(1), r.getString(2), r.getString(3),
                            r.getInt(4), r.getString(5), r.getString(6), r.getString(7),
                            r.getBoolean(8), r.getString(9), r.getString(10), r.getInt(11),
                            r.getBoolean(12)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Creates a subscription.
     *
     * @return the signing secret, disclosed once, or empty where the destination was refused. The
     *     refusal is not distinguished by reason: a caller probing which internal addresses are
     *     rejected learns the shape of the network from the error messages.
     */
    public java.util.Optional<String> create(Principal principal, String label, String url,
            int minSeverityOrdinal, UUID scopeNodeId) throws SQLException {
        if (label == null || label.isBlank() || !permitted(url)) {
            return java.util.Optional.empty();
        }
        byte[] material = new byte[32];
        new java.security.SecureRandom().nextBytes(material);
        String secret = HexFormat.of().formatHex(material);
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO alert_webhook (tenant_id, label, url, secret_hash,
                                                   min_severity_ordinal, scope_node_id, created_by)
                        VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?)
                        """)) {
            statement.setString(1, label.strip());
            statement.setString(2, url.strip());
            statement.setBytes(3, sha256(secret));
            statement.setInt(4, Math.max(1, minSeverityOrdinal));
            statement.setObject(5, scopeNodeId);
            statement.setObject(6, principal.principalId());
            statement.executeUpdate();
            // The destination and the scope, never the secret. An outbound destination is an egress
            // path: what a review asks is where finding data can now be sent, and by whom.
            audit.domainChange(connection, principal, "alert_webhook",
                    aspm.kernel.audit.contract.DomainChangeKind.CREATED, null,
                    aspm.app.audit.AuditScopes.ofNode(connection, scopeNodeId),
                    java.util.Map.of("label", label.strip(),
                            "url", url.strip(),
                            "min_severity_ordinal", Integer.valueOf(Math.max(1, minSeverityOrdinal)),
                            "scope_node_id", scopeNodeId == null
                                    ? "the whole tenant" : scopeNodeId.toString()));
            // Committed before the secret is returned. The caller shows it once and never again, so a
            // secret handed over for a row that did not commit is a secret nobody can use or find.
            connection.commit();
        }
        return java.util.Optional.of(secret);
    }

    /** Turns one off or on. Deactivated rather than deleted: the delivery log has to keep pointing. */
    public boolean setActive(Principal principal, UUID id, boolean active) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE alert_webhook SET active = ?, consecutive_failures = 0 WHERE id = ?")) {
            statement.setBoolean(1, active);
            statement.setObject(2, id);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.domainChange(connection, principal, "alert_webhook",
                        aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id, null,
                        java.util.Map.of("active", Boolean.valueOf(active),
                                "consecutive_failures", "reset to 0"));
            }
            connection.commit();
            return applied;
        }
    }

    // ==============================================================================================
    // Delivery
    // ==============================================================================================

    /**
     * Notifies every subscription that asked for events of this severity in this part of the estate.
     *
     * <p>Called after the submission has committed. Every failure is swallowed and recorded — see
     * the class note on why an undeliverable alert must not fail an ingestion.
     */
    public void publish(Principal principal, List<Event> events) {
        if (events.isEmpty()) {
            return;
        }
        try (Connection connection = open(principal)) {
            for (Event event : events) {
                for (Map<String, Object> subscriber : subscribersFor(connection, event)) {
                    deliver(connection, subscriber, event);
                }
            }
        } catch (SQLException e) {
            // Nothing to do and nothing to fail: the ingestion is committed and the alert is lost.
            // It is recorded per delivery below; a failure to even read the subscriptions leaves no
            // row, which is the one case this cannot report on.
            return;
        }
    }

    private List<Map<String, Object>> subscribersFor(Connection connection, Event event)
            throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT w.id, w.url, w.secret_hash
                  FROM alert_webhook w
                 WHERE w.active
                   AND w.min_severity_ordinal >= coalesce(
                         (SELECT s.ordinal FROM severity_level s WHERE s.code = ?), 9999)
                   AND (w.scope_node_id IS NULL
                        OR EXISTS (SELECT 1 FROM asset a
                                     JOIN org_closure c ON c.descendant_id = a.owning_node_id
                                    WHERE a.id = ? AND c.ancestor_id = w.scope_node_id))
                """)) {
            // The comparison reads "the subscription's threshold is at or below this advisory's
            // rank", with lower being more severe. An unrated advisory scores 9999 and therefore
            // reaches nobody by threshold — deliberately: waking somebody for a vulnerability nobody
            // has rated is how a channel gets muted.
            statement.setString(1, event.severity());
            statement.setObject(2, event.assetId());
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", r.getObject(1, UUID.class));
                    row.put("url", r.getString(2));
                    row.put("secret", r.getBytes(3));
                    out.add(row);
                }
            }
        }
        return out;
    }

    private void deliver(Connection connection, Map<String, Object> subscriber, Event event) {
        UUID id = (UUID) subscriber.get("id");
        String url = String.valueOf(subscriber.get("url"));
        String status;
        String detail = null;
        if (!permitted(url)) {
            // Re-checked at delivery, not only at save. A destination that resolved to a public
            // address when it was configured may resolve elsewhere now, and the check that matters
            // is the one immediately before the request.
            status = "REFUSED_DESTINATION";
            detail = "the destination is not an https address outside private ranges";
        } else {
            String body = Json.write(payload(event));
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "aspm-alerts/1")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                byte[] secret = (byte[]) subscriber.get("secret");
                if (secret != null) {
                    request.header("X-ASPM-Signature", "sha256=" + hmac(secret, body));
                }
                HttpResponse<Void> response = CLIENT.send(request.build(),
                        HttpResponse.BodyHandlers.discarding());
                status = response.statusCode() >= 200 && response.statusCode() < 300
                        ? "DELIVERED" : "REJECTED";
                detail = "HTTP " + response.statusCode();
            } catch (Exception e) {
                status = "FAILED";
                // The class name, not the message. A connection failure's message can contain the
                // resolved address and the internal hostname, and this row is readable by anybody
                // who can see the subscription.
                detail = e.getClass().getSimpleName();
            }
        }
        record(connection, id, event, status, detail);
    }

    private static void record(Connection connection, UUID webhookId, Event event, String status,
            String detail) {
        try {
            try (PreparedStatement log = connection.prepareStatement("""
                    INSERT INTO alert_delivery (tenant_id, webhook_id, advisory_id, asset_id,
                                                status, detail)
                    VALUES (current_tenant_id(), ?, ?, ?, ?, ?)
                    """)) {
                log.setObject(1, webhookId);
                log.setObject(2, event.advisoryId());
                log.setObject(3, event.assetId());
                log.setString(4, status);
                log.setString(5, detail);
                log.executeUpdate();
            }
            try (PreparedStatement mark = connection.prepareStatement("""
                    UPDATE alert_webhook
                       SET last_delivery_at = now(), last_status = ?,
                           consecutive_failures = CASE WHEN ? = 'DELIVERED' THEN 0
                                                       ELSE consecutive_failures + 1 END,
                           -- Switched off after ten consecutive failures. A subscription hammering a
                           -- dead endpoint on every ingestion is a self-inflicted outbound flood, and
                           -- the operator sees `active = false` with the failure count beside it
                           -- rather than a silence they have to investigate.
                           active = CASE WHEN ? = 'DELIVERED' THEN active
                                         WHEN consecutive_failures + 1 >= 10 THEN false
                                         ELSE active END
                     WHERE id = ?
                    """)) {
                mark.setString(1, status);
                mark.setString(2, status);
                mark.setString(3, status);
                mark.setObject(4, webhookId);
                mark.executeUpdate();
            }
        } catch (SQLException e) {
            // Recording the attempt failed. Nothing further can be done here without failing the
            // ingestion this was deliberately decoupled from.
        }
    }

    private static Map<String, Object> payload(Event event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event", "advisory.detected");
        out.put("advisory", event.advisoryKey());
        out.put("severity", event.severity());
        out.put("cvss", event.cvss());
        out.put("summary", event.summary());
        out.put("component", event.componentName());
        out.put("version", event.componentVersion());
        out.put("fixed_version", event.fixedVersion());
        out.put("application", event.applicationName());
        out.put("project", event.projectName());
        out.put("repository", event.repositoryName());
        // Where to go and look. An alert that names a problem without a way to reach it makes the
        // reader search for it, which is the friction that gets alerts ignored.
        out.put("link", "/composition?tab=advisories");
        return out;
    }

    /**
     * Whether this platform is willing to make a request to that URL.
     *
     * <p>See the class note. Refuses anything that is not https, and anything resolving to a private,
     * loopback, link-local or multicast address.
     */
    static boolean permitted(String url) {
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return false;
        }
        try {
            URI uri = URI.create(url.strip());
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    return false;
                }
                // 169.254.169.254 is link-local and already refused above; the check is kept explicit
                // because it is the address that matters and a reader should see it named.
                if ("169.254.169.254".equals(address.getHostAddress())) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            // A name that does not resolve is refused rather than attempted.
            return false;
        }
    }

    private static String hmac(byte[] secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
