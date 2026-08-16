package aspm.kernel.audit.contract;

import aspm.sharedkernel.PrincipalId;
import aspm.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The fixed envelope of DOC-14 section 2.
 *
 * <p>{@code SEC-AUD-001}: "Every audit event MUST use the fixed envelope with a typed payload. A new
 * event type MUST NOT introduce envelope fields." This record is {@code final} with no extension
 * point, so an event type wanting its own field has nowhere to put it except the payload — which is
 * the intent, because "the envelope is where integrity, scope, attribution, and erasure separation
 * live" and an event type adding envelope fields "would need its own integrity treatment, and the
 * exception would spread".
 *
 * <p><b>{@code SEC-AUD-022} governs what may appear here.</b> "The envelope MUST NOT contain personal
 * data beyond principal identifiers." If the envelope held personal data it would itself require
 * erasure, and erasing it would break the chain. Source address, user agent and any free text
 * therefore belong in the payload, which is erasable — see the note on the discrepancy below.
 *
 * <p><b>Discrepancy with DOC-14 section 2, recorded not resolved.</b> That section's envelope diagram
 * lists {@code source_context jsonb  address, user agent, request id} among the envelope fields, while
 * {@code SEC-AUD-022} and its own explanatory note place exactly those in the payload. DOC-04
 * section 20.1's {@code audit_event} table has no such column, so the schema and the requirement agree
 * and the diagram is the outlier. This implementation follows the requirement and the schema.
 * Reported for the corpus owner rather than corrected here, because requirement IDs are immutable and
 * a diagram change is theirs to make.
 */
public record AuditEnvelope(
        UUID eventId,
        TenantId tenantId,
        long sequence,
        String eventType,
        AuditOutcome outcome,
        String denialReason,
        String objectKind,
        UUID objectId,
        PrincipalId actorId,
        ActorType actorType,
        PrincipalId onBehalfOfId,
        UUID automationRuleId,
        UUID breakGlassRef,
        Instant occurredAt,
        AuditScope scope) {

    public AuditEnvelope {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(eventType, "eventType is required; SEC-AUD-006 requires a catalogued type");
        Objects.requireNonNull(outcome, "outcome is required");
        Objects.requireNonNull(actorType, "actorType is required; SEC-AUD-004");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(scope, "scope is required; SEC-AUD-003 records scope as it was");

        if (sequence < 0) {
            throw new IllegalArgumentException("sequence is monotonic and gapless from 0 (SEC-AUD-002)");
        }
        if ((objectKind == null) != (objectId == null)) {
            throw new IllegalArgumentException(
                    "object kind and id are recorded together or not at all; a half-recorded object "
                            + "reference cannot answer 'what happened to this object'");
        }
        if (outcome != AuditOutcome.DENIED && denialReason != null) {
            throw new IllegalArgumentException("a denial reason on a non-denied outcome is a contradiction");
        }
        if (actorType == ActorType.AUTOMATION && automationRuleId == null) {
            // SEC-AUD-004: "where automation acts, the rule and its owning principal must both be
            // recoverable or an automated escalation has no traceable origin".
            throw new IllegalArgumentException(
                    "an AUTOMATION actor must carry its rule identifier (SEC-AUD-004)");
        }
        if (actorType == ActorType.USER && actorId == null) {
            throw new IllegalArgumentException("a USER actor must be identified (SEC-AUD-004)");
        }
    }

    /** Convenience accessor: the catalogued type as a code. */
    public static String typeCode(AuditEventType type) {
        return type.code();
    }
}
