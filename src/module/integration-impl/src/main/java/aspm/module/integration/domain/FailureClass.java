package aspm.module.integration.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Failure classification, and the retry behaviour each class implies. DOC-21 section 5,
 * {@code PRD-CON-024} through {@code PRD-CON-027}.
 *
 * <p>{@code PRD-CON-025}: "Every failure MUST be classified, and retry behaviour MUST be determined by class.
 * <b>Undifferentiated retry MUST NOT occur.</b>"
 *
 * <h2>The class that exists to prevent one specific outcome</h2>
 *
 * <p>DOC-21 on {@code PRD-CON-024}: "Blind retry on an authentication failure locks the account on the target
 * system, converting a configuration problem in the platform into an outage in the customer's engineering
 * estate." And then: "<b>This is the failure most likely to damage a customer relationship: the platform's
 * misconfiguration causing an outage in <i>their</i> systems.</b> Classification-driven retry exists primarily to
 * prevent it."
 *
 * <p>So {@link #AUTHENTICATION} and {@link #AUTHORIZATION} have a retry policy of <b>none</b>, expressed as
 * {@code maxAttempts == 0} rather than as a flag a caller might not read. A caller looping "while attempts <
 * maxAttempts" does the right thing without knowing why.
 */
public enum FailureClass {

    /**
     * The credential was rejected. <b>Never retried.</b>
     *
     * <p>Marks the connector unhealthy, notifies the owner, and stops until the credential is corrected.
     */
    AUTHENTICATION(0, Duration.ZERO, true, true),

    /**
     * The credential authenticated and was refused the operation. <b>Never retried.</b>
     *
     * <p>Distinct from {@code AUTHENTICATION} because the remedy differs — a scope change on the target rather
     * than a new credential — and because retrying an authorization failure can also trip a target's abuse
     * detection.
     */
    AUTHORIZATION(0, Duration.ZERO, true, true),

    /**
     * The target signalled throttling. Retried, but with the platform's budget <b>reduced</b>.
     *
     * <p>{@code PRD-CON-027}: "Exhausting a customer's API quota affects their other systems, which converts a
     * security tool into an operational incident and ends the deployment."
     */
    RATE_LIMITED(5, Duration.ofSeconds(30), false, false),

    /** A network or transport fault. The class ordinary backoff was designed for. */
    TRANSIENT(5, Duration.ofSeconds(2), false, false),

    /**
     * The target rejected one record. <b>Does not fail the run.</b>
     *
     * <p>{@code PRD-CON-026}: the record is quarantined with its reason and the run continues — "one record the
     * target rejects must not discard the run's other work".
     */
    DATA(0, Duration.ZERO, false, false),

    /**
     * The target returned something the connector cannot interpret, or the platform's own configuration is
     * wrong. Retried once, because a single malformed response is occasionally transient and a persistent one
     * needs a human either way.
     */
    PROTOCOL(1, Duration.ofSeconds(5), true, true);

    private final int maxAttempts;
    private final Duration baseBackoff;
    private final boolean marksConnectorUnhealthy;
    private final boolean notifiesOwner;

    FailureClass(int maxAttempts, Duration baseBackoff, boolean marksConnectorUnhealthy,
            boolean notifiesOwner) {
        this.maxAttempts = maxAttempts;
        this.baseBackoff = baseBackoff;
        this.marksConnectorUnhealthy = marksConnectorUnhealthy;
        this.notifiesOwner = notifiesOwner;
    }

    /**
     * Retries permitted <b>after</b> the first attempt. Zero means the operation is not retried.
     *
     * <p>Expressed as a count rather than a boolean so a generic retry loop honours it without a special case,
     * which is what keeps the credential classes safe in code somebody writes later without reading this file.
     */
    public int maxRetryAttempts() {
        return maxAttempts;
    }

    public boolean retryable() {
        return maxAttempts > 0;
    }

    /** Whether the connector is marked unhealthy and stopped. {@code PRD-CON-024}. */
    public boolean marksConnectorUnhealthy() {
        return marksConnectorUnhealthy;
    }

    public boolean notifiesOwner() {
        return notifiesOwner;
    }

    /** {@code PRD-CON-026}: a data failure quarantines the record and lets the run continue. */
    public boolean quarantinesRecordAndContinues() {
        return this == DATA;
    }

    /**
     * The delay before attempt {@code attemptNumber}, counting the first retry as 1.
     *
     * @return empty where the class is not retryable, so a caller that ignores {@link #retryable} still cannot
     *     obtain a delay for a credential failure
     */
    public Optional<Duration> backoffBefore(int attemptNumber, Optional<Duration> targetRetryAfter) {
        Objects.requireNonNull(targetRetryAfter, "targetRetryAfter is required, empty where not signalled");
        if (attemptNumber < 1 || attemptNumber > maxAttempts) {
            return Optional.empty();
        }
        if (this == RATE_LIMITED && targetRetryAfter.isPresent()) {
            // PRD-CON-027: honour the target's signal rather than the platform's schedule. Backing off less
            // than the target asked is how a quota gets exhausted while appearing to behave.
            return targetRetryAfter;
        }
        // Exponential, capped. The cap matters because an unbounded backoff on a long-running sweep is
        // indistinguishable from a stall.
        long seconds = Math.min(baseBackoff.toSeconds() * (1L << (attemptNumber - 1)), 300L);
        return Optional.of(Duration.ofSeconds(seconds));
    }
}
