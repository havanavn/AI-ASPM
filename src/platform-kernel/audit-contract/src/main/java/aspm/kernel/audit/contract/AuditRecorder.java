package aspm.kernel.audit.contract;

import java.util.Map;

/**
 * The contract every module uses to record an audited action.
 *
 * <p>Deliberately narrow. DOC-02 section 7.3 makes audit an event subscriber that "observes everything
 * without depending on everything"; this interface is the synchronous counterpart for the actions that
 * must be recorded in the same transaction as the change they describe ({@code CON-PLT-021},
 * {@code SEC-AUD-014}).
 *
 * <p>There is no {@code update} and no {@code delete}. {@code SEC-AUD-013}: "No mechanism MUST exist
 * to modify or delete an audit event: not through the application, the API, administrative tooling, or
 * an operator interface." An interface that offered one would be that mechanism.
 */
public interface AuditRecorder {

    /**
     * Records an event, computing and chaining its hash in the caller's transaction.
     *
     * @param draft everything except the identity and integrity fields, which the recorder assigns
     * @param payload before/after values and request detail. Erasable, and therefore where personal
     *     data belongs per {@code SEC-AUD-022}
     * @return the sequence assigned to the event
     */
    long record(AuditDraft draft, Map<String, Object> payload);

    /**
     * An event as its emitter knows it: what happened, to what, by whom, in what scope.
     *
     * <p>Sequence, event id, and the three hash fields are absent because the recorder assigns them.
     * A draft that carried them would let a caller choose a sequence, and a chosen sequence is a
     * forked chain — which {@code SEC-AUD-014} calls "undetectable as tampering and unrepairable".
     */
    record AuditDraft(
            String eventType,
            AuditOutcome outcome,
            String denialReason,
            String objectKind,
            java.util.UUID objectId,
            aspm.sharedkernel.PrincipalId actorId,
            ActorType actorType,
            aspm.sharedkernel.PrincipalId onBehalfOfId,
            java.util.UUID automationRuleId,
            AuditScope scope) {

        public AuditDraft {
            java.util.Objects.requireNonNull(eventType, "eventType is required");
            java.util.Objects.requireNonNull(outcome, "outcome is required");
            java.util.Objects.requireNonNull(actorType, "actorType is required");
            java.util.Objects.requireNonNull(scope, "scope is required");
        }

        /** A draft for a catalogued type, which is the only route S11 accepts. */
        public static AuditDraft of(
                AuditEventType type, AuditOutcome outcome, ActorType actorType, AuditScope scope) {
            return new AuditDraft(type.code(), outcome, null, null, null, null, actorType, null, null, scope);
        }
    }
}
