package aspm.module.integration.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One-way propagation to an external tracker. {@code PRD-CON-042}, ADR-040.
 *
 * <p>"Outbound propagation MUST be one-way. External state MUST NOT overwrite platform state, and the platform
 * record MUST remain authoritative. <b>Bidirectional synchronization reproduces the failure of a generic tracker
 * used for vulnerability management: closing the external ticket closes the finding, whether or not the
 * vulnerability is gone.</b>"
 *
 * <h2>What "one-way" means in the type</h2>
 *
 * <p>There is no method that applies external state. {@link #observeExternal} records what the external system
 * says and, where it disagrees, produces a {@link Divergence} for a human — it does not reconcile. A test scans
 * for {@code apply}, {@code sync}, {@code pull} and {@code merge}, because a bidirectional method added later
 * would look like a helpful completion of an obviously half-finished class.
 *
 * <p>The divergence is surfaced rather than resolved because both readings can be right: the ticket may be
 * closed because the fix shipped, or because somebody was tidying the backlog. Only a person knows which, and
 * the platform guessing is how a live vulnerability gets marked remediated.
 */
public final class OutboundPropagation {

    /** What the platform sends. Never a credential, secret, evidence body, or per-person figure. */
    public record OutboundPayload(UUID findingId, String title, String severity, String scopeReference,
            Set<String> includedFields) {

        /** {@code PRD-CON-037}. The same exclusion list as export and notification, for the same reason. */
        private static final Set<String> NEVER_TRANSMITTED = Set.of(
                "credentialRef", "secretValue", "evidenceContent", "memberUtilization", "assigneeWorkload");

        public OutboundPayload {
            Objects.requireNonNull(findingId, "findingId is required");
            Objects.requireNonNull(title, "a title is required");
            Objects.requireNonNull(severity, "a severity is required");
            Objects.requireNonNull(scopeReference, "a scope reference is required (PRD-CON-038)");
            includedFields = Set.copyOf(
                    Objects.requireNonNull(includedFields, "the included field list is required"));

            for (String forbidden : NEVER_TRANSMITTED) {
                if (includedFields.contains(forbidden)) {
                    throw new IllegalArgumentException(
                            "outbound content must not include " + forbidden + " at any configuration "
                                    + "(PRD-CON-037). An outbound payload leaves the platform's control and "
                                    + "cannot be recalled.");
                }
            }
        }
    }

    /**
     * A disagreement between the platform and the external system.
     *
     * @param resolution always {@link Resolution#AWAITING_HUMAN} on creation. There is no constructor producing
     *     a resolved divergence, because resolving one is a decision somebody makes
     */
    public record Divergence(UUID findingId, String platformState, String externalState, Instant observedAt,
            Resolution resolution) {

        public enum Resolution {
            AWAITING_HUMAN
        }

        public Divergence {
            Objects.requireNonNull(findingId, "findingId is required");
            Objects.requireNonNull(platformState, "the platform state is required");
            Objects.requireNonNull(externalState, "the external state is required");
            Objects.requireNonNull(observedAt, "observedAt is required");
            Objects.requireNonNull(resolution, "a resolution is required");
        }

        /** What a reviewer is asked. Stated rather than left implicit, because both readings can be right. */
        public String question() {
            return "The platform holds '" + platformState + "' and the external tracker holds '" + externalState
                    + "'. The ticket may be closed because the fix shipped, or because somebody was tidying "
                    + "the backlog — only a person knows which, and the platform guessing is how a live "
                    + "vulnerability gets marked remediated (ADR-040).";
        }
    }

    private OutboundPropagation() {
    }

    /**
     * Records what the external system reports.
     *
     * @return a divergence where the states disagree, and empty where they do not. <b>Nothing is written to the
     *     platform record either way</b> — this method returns an observation, not an outcome
     */
    public static Optional<Divergence> observeExternal(UUID findingId, String platformState,
            String externalState, Instant at) {
        Objects.requireNonNull(findingId, "findingId is required");
        Objects.requireNonNull(platformState, "the platform state is required");
        Objects.requireNonNull(externalState, "the external state is required");
        Objects.requireNonNull(at, "the observation instant is required");

        return platformState.equals(externalState)
                ? Optional.empty()
                : Optional.of(new Divergence(findingId, platformState, externalState, at,
                        Divergence.Resolution.AWAITING_HUMAN));
    }

    /**
     * Filters a batch to the records within the target integration's configured scope. {@code PRD-CON-038}.
     *
     * <p>"A connector configured for one business unit must not transmit another's findings, and <b>the target
     * system has no scope enforcement to compensate</b>." That second clause is why the filter is here rather
     * than relied upon downstream.
     */
    public static List<OutboundPayload> withinConfiguredScope(List<OutboundPayload> payloads,
            Set<String> configuredScopeReferences) {
        Objects.requireNonNull(payloads, "payloads are required");
        Objects.requireNonNull(configuredScopeReferences, "the configured scope is required");
        if (configuredScopeReferences.isEmpty()) {
            throw new IllegalArgumentException(
                    "a connector with no configured scope would transmit everything. An empty scope is a "
                            + "configuration error, not a wildcard (PRD-CON-038).");
        }
        return payloads.stream()
                .filter(p -> configuredScopeReferences.contains(p.scopeReference()))
                .toList();
    }
}
