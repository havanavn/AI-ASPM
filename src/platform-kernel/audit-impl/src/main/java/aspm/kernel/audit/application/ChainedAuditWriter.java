package aspm.kernel.audit.application;

import aspm.kernel.audit.contract.AuditEnvelope;
import aspm.kernel.audit.contract.AuditRecorder;
import aspm.kernel.audit.domain.CanonicalPayload;
import aspm.kernel.audit.domain.CanonicalSerializer;
import aspm.kernel.audit.domain.ChainHasher;
import aspm.kernel.tenantcontext.contract.TenantContext;
import aspm.kernel.tenantcontext.contract.TenantContextHolder;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes an audit event and extends the tenant's hash chain, in the caller's transaction.
 *
 * <p>{@code SEC-AUD-014}: "Chain computation MUST occur in the same transaction as the event insert, and
 * a concurrent insert MUST NOT produce two events with the same sequence or a forked chain." Both halves
 * are satisfied here — the head is locked before the sequence is chosen, and the store's single
 * {@code append} carries the event, the payload and the advanced head together.
 *
 * <p>DOC-14 section 4.2 states the cost this accepts: "per-tenant chain head serialization bounds audit
 * write throughput to one event at a time per tenant. This is accepted: {@code NFR-AUD-001} budgets 15 ms
 * at p95 for the audit write, and a single tenant's audited operation rate is well below the contention
 * threshold at the volumes of DOC-01 section 12.1."
 *
 * <p><b>The tenant comes from the established context, not from the draft.</b> A draft carrying a tenant
 * would let an emitter name one, and {@code SEC-AUD-012} binds the chain to the tenant — a misnamed tenant
 * would write into another tenant's chain, which is the one cross-tenant write no later verification could
 * unpick.
 */
public final class ChainedAuditWriter implements AuditRecorder {

    private final AuditChainStore store;
    private final Clock clock;
    private final EventTypeCatalogue catalogue;

    public ChainedAuditWriter(AuditChainStore store, Clock clock, EventTypeCatalogue catalogue) {
        this.store = Objects.requireNonNull(store, "store is required");
        // Injected rather than Instant.now(): occurred_at is part of the canonical form, so a test that
        // cannot fix the clock cannot assert a chain hash, and a chain hash nobody asserts is a chain
        // nobody has checked.
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.catalogue = Objects.requireNonNull(catalogue,
                "an event type catalogue is required; SEC-AUD-006 requires every audit-emitting path to "
                        + "reference a catalogued type");
    }

    @Override
    public long record(AuditDraft draft, Map<String, Object> payload) {
        Objects.requireNonNull(draft, "draft is required");
        Objects.requireNonNull(payload, "payload is required; use Map.of() for none");

        // Not a denial and not an empty write: SEC-TEN-005 makes a missing context a visible malfunction.
        TenantContext context = TenantContextHolder.requireCurrent("audit write " + draft.eventType());

        if (!catalogue.isCatalogued(draft.eventType())) {
            // SEC-AUD-006. The enum makes this unreachable for a compile-time constant; it is reachable
            // for a composite per-aggregate code, which is assembled at runtime from DomainChangeKind.
            throw new IllegalArgumentException(
                    "event type '" + draft.eventType() + "' is not in the catalogue of DOC-14 section 3. "
                            + "A trail whose coverage is undefined cannot be assessed for sufficiency, and "
                            + "gaps are then discovered during an audit rather than before one.");
        }

        // Locked for the remainder of the transaction. Everything below is serialized per tenant.
        AuditChainStore.ChainHead head = store.lockHead(context.tenantId());

        byte[] canonicalPayload = CanonicalPayload.canonicalize(payload);
        byte[] payloadHash = payload.isEmpty()
                ? ChainHasher.emptyPayloadHash()
                : ChainHasher.hashPayload(canonicalPayload);

        AuditEnvelope envelope = new AuditEnvelope(
                UUID.randomUUID(),
                context.tenantId(),
                head.nextSequence(),
                draft.eventType(),
                draft.outcome(),
                draft.denialReason(),
                draft.objectKind(),
                draft.objectId(),
                draft.actorId(),
                draft.actorType(),
                draft.onBehalfOfId(),
                draft.automationRuleId(),
                // Taken from the context, not the draft: SEC-TEN-030 makes break-glass activity visible to
                // the tenant, and an emitter that could omit the reference could hide it.
                context.breakGlassRef(),
                clock.instant(),
                draft.scope());

        byte[] previous = head.lastChainHash();
        byte[] chainHash = ChainHasher.link(
                previous, envelope, payloadHash, CanonicalSerializer.CURRENT_VERSION);

        store.append(envelope, CanonicalSerializer.CURRENT_VERSION, payloadHash, previous, chainHash,
                canonicalPayload);

        return envelope.sequence();
    }

    /**
     * Whether an event type is catalogued.
     *
     * <p>An interface rather than a direct enum lookup, because DOC-14 section 3 defers per-aggregate
     * domain state changes to "the machine-readable catalogue, enumerated per aggregate" — so the
     * catalogue is the closed enum plus the aggregates registered by the modules that own them. Keeping it
     * behind a port means {@code S11} has one place to assert against as aggregates arrive.
     */
    public interface EventTypeCatalogue {

        boolean isCatalogued(String eventType);
    }
}
