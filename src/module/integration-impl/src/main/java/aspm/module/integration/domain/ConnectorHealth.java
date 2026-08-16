package aspm.module.integration.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Connector health. {@code PRD-CON-028} to {@code PRD-CON-031}.
 *
 * <h2>The success rate exists for the failure the circuit cannot see</h2>
 *
 * <p>{@code PRD-CON-031}, and DOC-21's note beneath it: "Total failure opens the circuit and notifies.
 * <b>Intermittent failure does neither: it delivers some data, so nothing looks broken, and the resulting picture
 * is silently incomplete.</b> The success-rate measure exists for exactly that case."
 *
 * <p>A connector at forty percent never trips a consecutive-failure threshold, because every second or third
 * attempt succeeds and resets the counter. It produces data that appears current for the assets it happened to
 * cover, and no data at all for the rest — which reads as those assets simply having nothing to report.
 *
 * <p>So {@link #degraded} is a separate condition from {@link #circuitOpen}, and
 * {@link #requiresAlert} is true for either. A health model with only the circuit has the blind spot in it.
 */
public final class ConnectorHealth {

    /** Consecutive failures that open the circuit. */
    public static final int CIRCUIT_THRESHOLD = 5;

    /** Success rate below which the connector is degraded, whatever the circuit says. */
    public static final BigDecimal DEGRADED_BELOW_PERCENT = new BigDecimal("90");

    public enum CircuitState {
        CLOSED,
        OPEN,
        /** A single probe is permitted. A half-open circuit that failed returns to OPEN, not to CLOSED. */
        HALF_OPEN
    }

    private final UUID connectorId;
    private final UUID tenantId;

    private Instant lastSuccessAt;
    private int consecutiveFailures;
    private FailureClass lastFailureClass;
    private CircuitState circuitState = CircuitState.CLOSED;
    private String circuitOpenReason;
    private boolean ownerNotifiedOfOpenCircuit;

    private int attemptsInPeriod;
    private int successesInPeriod;

    public ConnectorHealth(UUID connectorId, UUID tenantId) {
        this.connectorId = Objects.requireNonNull(connectorId, "connectorId is required");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId is required (PRD-CON-028 is per tenant)");
    }

    public void recordSuccess(Instant at) {
        Objects.requireNonNull(at, "the success instant is required");
        this.lastSuccessAt = at;
        this.consecutiveFailures = 0;
        this.attemptsInPeriod++;
        this.successesInPeriod++;
        if (circuitState == CircuitState.HALF_OPEN) {
            circuitState = CircuitState.CLOSED;
            circuitOpenReason = null;
            ownerNotifiedOfOpenCircuit = false;
        }
    }

    /**
     * Records a failure and applies the class's consequence.
     *
     * <p>A credential failure opens the circuit <b>immediately</b> rather than after five attempts: the
     * threshold exists to distinguish a blip from a fault, and a rejected credential is not a blip. Waiting for
     * five is five authentication attempts against the target, which is how the account gets locked
     * ({@code PRD-CON-024}).
     */
    public void recordFailure(FailureClass failureClass, Instant at) {
        Objects.requireNonNull(failureClass, "a failure class is required (PRD-CON-025)");
        Objects.requireNonNull(at, "the failure instant is required");

        this.lastFailureClass = failureClass;
        this.attemptsInPeriod++;

        if (failureClass.quarantinesRecordAndContinues()) {
            // A DATA failure is not a connector failure. Counting it would open a circuit because one record
            // is malformed, stopping an integration that is working.
            return;
        }

        this.consecutiveFailures++;

        if (failureClass.marksConnectorUnhealthy()) {
            openCircuit("a " + failureClass + " failure: retrying it would lock the account on the target "
                    + "system, converting a configuration problem in the platform into an outage in the "
                    + "customer's engineering estate (PRD-CON-024)");
            return;
        }
        if (consecutiveFailures >= CIRCUIT_THRESHOLD) {
            openCircuit(consecutiveFailures + " consecutive " + failureClass + " failures");
        }
    }

    private void openCircuit(String reason) {
        if (circuitState != CircuitState.OPEN) {
            circuitState = CircuitState.OPEN;
            circuitOpenReason = reason;
            // PRD-CON-029: "A suspended integration nobody is told about MUST NOT be possible." The flag is
            // set here rather than by whoever notices, so a caller that forgets to notify leaves a false
            // record rather than a silent gap.
            ownerNotifiedOfOpenCircuit = false;
        }
    }

    /** Records that the owner was told. Separate from opening, so an unnotified open circuit is detectable. */
    public void recordOwnerNotified() {
        if (circuitState != CircuitState.OPEN) {
            throw new IllegalStateException("nothing to notify about; the circuit is " + circuitState);
        }
        this.ownerNotifiedOfOpenCircuit = true;
    }

    /** {@code PRD-CON-029}. An open circuit whose owner has not been told is a permanent coverage gap. */
    public boolean silentlySuspended() {
        return circuitState == CircuitState.OPEN && !ownerNotifiedOfOpenCircuit;
    }

    /** Permits one probe after a cooldown. */
    public void attemptRecovery(Instant at, Duration cooldown) {
        Objects.requireNonNull(at, "the instant is required");
        Objects.requireNonNull(cooldown, "a cooldown is required");
        if (circuitState != CircuitState.OPEN) {
            return;
        }
        if (lastFailureClass != null && lastFailureClass.marksConnectorUnhealthy()) {
            // A credential fault does not heal on a timer. Half-opening would retry the authentication the
            // circuit opened to prevent.
            throw new IllegalStateException(
                    "a " + lastFailureClass + " circuit does not reopen on a timer; it stops until the "
                            + "credential is corrected (PRD-CON-024)");
        }
        circuitState = CircuitState.HALF_OPEN;
    }

    /** {@code PRD-CON-031}. Over the current period. */
    public BigDecimal successRatePercent() {
        if (attemptsInPeriod == 0) {
            // No attempts is not a hundred percent. A connector that has not run has no success rate, and
            // reporting one would make an idle integration look healthy.
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(successesInPeriod * 100L)
                .divide(BigDecimal.valueOf(attemptsInPeriod), 2, RoundingMode.HALF_UP);
    }

    /**
     * Degraded: delivering partial data with the circuit closed. {@code PRD-CON-031}.
     *
     * <p>Requires at least one attempt, so an idle connector is not reported as degraded — that is a different
     * condition and conflating them would fill the queue with connectors nobody has used yet.
     */
    public boolean degraded() {
        return attemptsInPeriod > 0
                && circuitState != CircuitState.OPEN
                && successRatePercent().compareTo(DEGRADED_BELOW_PERCENT) < 0;
    }

    /** Either condition alerts. A health model watching only the circuit has the blind spot in it. */
    public boolean requiresAlert() {
        return circuitState == CircuitState.OPEN || degraded();
    }

    /**
     * The coverage effect. {@code PRD-CON-030}: health feeds coverage reporting, "such that an unhealthy
     * connector is reflected in the coverage of the data it supplies".
     *
     * <p>"Otherwise coverage reports current data for an asset whose supplying integration has been failing for
     * a month. This is PP-1 applied to integration health."
     */
    public String coverageQualifier() {
        if (circuitState == CircuitState.OPEN) {
            return "the supplying integration is suspended (" + circuitOpenReason + "); data for these assets "
                    + "is not current and no absence of findings should be read as clean";
        }
        if (degraded()) {
            return "the supplying integration is delivering at " + successRatePercent() + "% success; data for "
                    + "these assets is PARTIAL and appears current, which is the more damaging failure "
                    + "(PRD-CON-031)";
        }
        return "the supplying integration is healthy at " + successRatePercent() + "% success";
    }

    /** Resets the rolling period. Called by the health job; the circuit state deliberately survives. */
    public void rollPeriod() {
        this.attemptsInPeriod = 0;
        this.successesInPeriod = 0;
    }

    public UUID connectorId() {
        return connectorId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public Optional<Instant> lastSuccessAt() {
        return Optional.ofNullable(lastSuccessAt);
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public Optional<FailureClass> lastFailureClass() {
        return Optional.ofNullable(lastFailureClass);
    }

    public CircuitState circuitState() {
        return circuitState;
    }

    public Optional<String> circuitOpenReason() {
        return Optional.ofNullable(circuitOpenReason);
    }

    public boolean circuitOpen() {
        return circuitState == CircuitState.OPEN;
    }
}
