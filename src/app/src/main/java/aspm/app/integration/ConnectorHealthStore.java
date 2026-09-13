package aspm.app.integration;

import aspm.module.integration.domain.ConnectorHealth;
import aspm.module.integration.domain.FailureClass;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code connector_health} in and out of the domain's {@link ConnectorHealth}, so the circuit arithmetic
 * lives in one place — the module — and this tier only stores it. {@code PRD-CON-028}, {@code PRD-CON-029},
 * {@code PRD-CON-031}.
 */
final class ConnectorHealthStore {

    /** How long an open circuit waits before admitting a single probe (non-credential failures only). */
    static final Duration COOLDOWN = Duration.ofMinutes(5);

    /** The outcome of recording one attempt. */
    record Recorded(ConnectorHealth health, boolean circuitJustOpened) {
    }

    private ConnectorHealthStore() {
    }

    static ConnectorHealth load(Connection connection, UUID tenantId, UUID connectorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT last_success_at, consecutive_failures, last_failure_class, circuit_state, circuit_open_reason, "
                        + "owner_notified_at IS NOT NULL, period_attempts, period_successes FROM connector_health WHERE connector_id = ? FOR UPDATE")) {
            statement.setObject(1, connectorId);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    return new ConnectorHealth(connectorId, tenantId);
                }
                Timestamp success = r.getTimestamp(1);
                String failure = r.getString(3);
                return ConnectorHealth.restore(connectorId, tenantId, success == null ? null : success.toInstant(), r.getInt(2),
                        failure == null ? null : FailureClass.valueOf(failure), ConnectorHealth.CircuitState.valueOf(r.getString(4)),
                        r.getString(5), r.getBoolean(6), r.getInt(7), r.getInt(8));
            }
        }
    }

    /** Records one attempt and persists. {@code circuitJustOpened} is the trigger for {@code PRD-CON-029}. */
    static Recorded record(Connection connection, UUID tenantId, UUID connectorId, Optional<FailureClass> failure, String detail)
            throws SQLException {
        ConnectorHealth health = load(connection, tenantId, connectorId);
        boolean wasOpen = health.circuitOpen();
        Instant now = Instant.now();
        if (failure.isEmpty()) {
            health.recordSuccess(now);
        } else {
            health.recordFailure(failure.get(), now);
        }
        boolean justOpened = health.circuitOpen() && !wasOpen;
        Optional<Duration> backoff = health.circuitOpen() && !health.lastFailureClass().map(FailureClass::marksConnectorUnhealthy).orElse(false)
                ? Optional.of(COOLDOWN) : Optional.empty();
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE connector_health SET last_success_at = ?, last_attempt_at = now(), consecutive_failures = ?, "
                        + "last_failure_class = ?, last_failure_detail = CASE WHEN ? THEN ? ELSE last_failure_detail END, "
                        + "last_failure_at = CASE WHEN ? THEN now() ELSE last_failure_at END, "
                        + "circuit_state = ?, circuit_open_reason = ?, "
                        + "circuit_opened_at = CASE WHEN ? THEN now() WHEN circuit_state <> 'OPEN' THEN NULL ELSE circuit_opened_at END, "
                        + "owner_notified_at = CASE WHEN ? THEN owner_notified_at ELSE NULL END, "
                        + "backoff_until = ?, period_attempts = ?, period_successes = ? WHERE connector_id = ?")) {
            statement.setTimestamp(1, health.lastSuccessAt().map(Timestamp::from).orElse(null));
            statement.setInt(2, health.consecutiveFailures());
            statement.setString(3, health.lastFailureClass().map(Enum::name).orElse(null));
            statement.setBoolean(4, failure.isPresent());
            statement.setString(5, detail == null ? null : detail.length() > 300 ? detail.substring(0, 300) : detail);
            statement.setBoolean(6, failure.isPresent());
            statement.setString(7, health.circuitState().name());
            statement.setString(8, health.circuitOpenReason().orElse(null));
            statement.setBoolean(9, justOpened);
            statement.setBoolean(10, health.circuitOpen() && health.ownerNotified());
            statement.setTimestamp(11, backoff.map(b -> Timestamp.from(now.plus(b))).orElse(null));
            statement.setInt(12, health.attemptsInPeriod());
            statement.setInt(13, health.successesInPeriod());
            statement.setObject(14, connectorId);
            if (statement.executeUpdate() == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO connector_health (connector_id, tenant_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                    insert.setObject(1, connectorId);
                    insert.setObject(2, tenantId);
                    insert.executeUpdate();
                }
                return record(connection, tenantId, connectorId, failure, detail);
            }
        }
        return new Recorded(health, justOpened);
    }

    /** Moves an open circuit to half-open when its cooldown has passed; false when it must stay open. */
    static boolean admitProbe(Connection connection, ConnectorHealth health) throws SQLException {
        if (!health.circuitOpen()) {
            return true;
        }
        if (health.lastFailureClass().map(FailureClass::marksConnectorUnhealthy).orElse(false)) {
            return false;   // PRD-CON-024: stops until the credential or configuration is corrected
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE connector_health SET circuit_state = 'HALF_OPEN' WHERE connector_id = ? AND circuit_state = 'OPEN' "
                        + "AND (backoff_until IS NULL OR backoff_until <= now())")) {
            statement.setObject(1, health.connectorId());
            if (statement.executeUpdate() == 1) {
                health.attemptRecovery(Instant.now(), COOLDOWN);
                return true;
            }
        }
        return false;
    }

    /**
     * An administrator proved the credential or the configuration against the target: the condition a
     * credential/configuration circuit waits for (PRD-CON-024, "until the credential is corrected").
     */
    static void closeCircuit(Connection connection, UUID connectorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE connector_health SET circuit_state = 'CLOSED', circuit_open_reason = NULL, circuit_opened_at = NULL, "
                        + "owner_notified_at = NULL, backoff_until = NULL, consecutive_failures = 0 WHERE connector_id = ?")) {
            statement.setObject(1, connectorId);
            statement.executeUpdate();
        }
        // The work that waited behind the circuit is due now, not after the cooldown it was parked for.
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE outbound_operation SET next_attempt_at = now(), updated_at = now() WHERE connector_id = ? AND status = 'QUEUED'")) {
            statement.setObject(1, connectorId);
            statement.executeUpdate();
        }
    }

    static void markOwnerNotified(Connection connection, UUID connectorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE connector_health SET owner_notified_at = now() WHERE connector_id = ?")) {
            statement.setObject(1, connectorId);
            statement.executeUpdate();
        }
    }
}
