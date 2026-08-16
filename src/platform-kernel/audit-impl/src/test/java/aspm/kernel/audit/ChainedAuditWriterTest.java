package aspm.kernel.audit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.kernel.audit.application.AuditChainStore;
import aspm.kernel.audit.application.ChainedAuditWriter;
import aspm.kernel.audit.contract.ActorType;
import aspm.kernel.audit.contract.AuditEnvelope;
import aspm.kernel.audit.contract.AuditEventType;
import aspm.kernel.audit.contract.AuditOutcome;
import aspm.kernel.audit.contract.AuditRecorder;
import aspm.kernel.audit.contract.AuditScope;
import aspm.kernel.audit.domain.CanonicalPayload;
import aspm.kernel.audit.domain.CanonicalSerializer;
import aspm.kernel.audit.domain.ChainHasher;
import aspm.kernel.audit.domain.ChainVerifier;
import aspm.kernel.tenantcontext.contract.EstablishedFrom;
import aspm.kernel.tenantcontext.contract.MissingTenantContextException;
import aspm.kernel.tenantcontext.contract.TenantContext;
import aspm.kernel.tenantcontext.contract.TenantContextHolder;
import aspm.sharedkernel.TenantId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The writer, against an in-memory store.
 *
 * <p>{@code TST-PLT-005}: the sequencing and chaining logic is domain-enforced, so it is tested without a
 * database. What the in-memory store cannot demonstrate is the <em>lock</em> — {@code SEC-AUD-014}'s
 * per-tenant serialization is a property of {@code SELECT ... FOR UPDATE} and is asserted in
 * {@code KernelPersistenceVerificationTest}, which remains unobserved in this environment. Stated so the
 * green result here is not mistaken for that.
 */
class ChainedAuditWriterTest {

    private static final Instant FIXED = Instant.parse("2026-08-04T09:00:00Z");

    /** In-memory store. Not thread-safe on purpose: the real serialization is the database lock. */
    private static final class InMemoryStore implements AuditChainStore {

        private final Map<UUID, ChainHead> heads = new HashMap<>();
        final List<ChainVerifier.StoredEvent> written = new ArrayList<>();
        int lockCalls;

        @Override
        public ChainHead lockHead(TenantId tenantId) {
            lockCalls++;
            return heads.computeIfAbsent(tenantId.value(),
                    _ -> new ChainHead(-1, ChainHasher.genesis(tenantId)));
        }

        @Override
        public void append(AuditEnvelope envelope, int canonicalVersion, byte[] payloadHash,
                byte[] prevChainHash, byte[] chainHash, byte[] canonicalPayload) {
            written.add(new ChainVerifier.StoredEvent(
                    envelope, canonicalVersion, payloadHash, prevChainHash, chainHash, null, null));
            heads.put(envelope.tenantId().value(), new ChainHead(envelope.sequence(), chainHash));
        }
    }

    private static TenantContext context(TenantId tenant) {
        return TenantContext.of(tenant, "vn-south", EstablishedFrom.AUTHENTICATED_PRINCIPAL, FIXED);
    }

    private static AuditRecorder.AuditDraft draft() {
        return AuditRecorder.AuditDraft.of(AuditEventType.SESSION_CREATED, AuditOutcome.SUCCESS,
                ActorType.SYSTEM, AuditScope.unscoped(1));
    }

    private static ChainedAuditWriter writer(InMemoryStore store) {
        return new ChainedAuditWriter(store, Clock.fixed(FIXED, ZoneOffset.UTC),
                type -> java.util.Arrays.stream(AuditEventType.values())
                        .anyMatch(t -> t.code().equals(type)));
    }

    @Test
    @DisplayName("SEC-AUD-002: sequences start at 0 and are gapless")
    void sequencesAreGaplessFromZero() {
        var store = new InMemoryStore();
        var w = writer(store);
        var tenant = new TenantId(UUID.randomUUID());

        var sequences = TenantContextHolder.with(context(tenant), () -> {
            List<Long> seen = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                seen.add(w.record(draft(), Map.of("i", i)));
            }
            return seen;
        });

        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), sequences);
    }

    @Test
    @DisplayName("SEC-AUD-014: the head is locked before the sequence is chosen, once per write")
    void headIsLockedPerWrite() {
        var store = new InMemoryStore();
        var w = writer(store);
        TenantContextHolder.runWith(context(new TenantId(UUID.randomUUID())), () -> {
            w.record(draft(), Map.of());
            w.record(draft(), Map.of());
        });
        assertEquals(2, store.lockCalls,
                "a write that does not lock the head permits two concurrent writers to choose the same "
                        + "sequence, which SEC-AUD-014 calls undetectable as tampering and unrepairable");
    }

    @Test
    @DisplayName("the written chain verifies end to end")
    void writtenChainVerifies() {
        var store = new InMemoryStore();
        var w = writer(store);
        var tenant = new TenantId(UUID.randomUUID());

        TenantContextHolder.runWith(context(tenant), () -> {
            for (int i = 0; i < 6; i++) {
                w.record(draft(), Map.of("index", i, "note", "value " + i));
            }
        });

        var report = ChainVerifier.verify(tenant, store.written, ChainVerifier.Level.FULL, null);
        assertTrue(report.verified(), () -> "unexpected findings: " + report.failures());
        assertEquals(6, report.eventsExamined());
    }

    @Test
    @DisplayName("SEC-AUD-012: two tenants written through the same writer have independent chains")
    void chainsAreIndependentPerTenant() {
        var store = new InMemoryStore();
        var w = writer(store);
        var a = new TenantId(UUID.randomUUID());
        var b = new TenantId(UUID.randomUUID());

        TenantContextHolder.runWith(context(a), () -> w.record(draft(), Map.of()));
        TenantContextHolder.runWith(context(b), () -> w.record(draft(), Map.of()));
        TenantContextHolder.runWith(context(a), () -> w.record(draft(), Map.of()));

        var forA = store.written.stream().filter(e -> e.envelope().tenantId().equals(a)).toList();
        var forB = store.written.stream().filter(e -> e.envelope().tenantId().equals(b)).toList();

        assertEquals(List.of(0L, 1L), forA.stream().map(e -> e.envelope().sequence()).toList());
        assertEquals(List.of(0L), forB.stream().map(e -> e.envelope().sequence()).toList(),
                "tenant B's first event must be sequence 0, not 1 — a shared counter would leak B's "
                        + "existence and volume into A's chain");

        assertTrue(ChainVerifier.verify(a, forA, ChainVerifier.Level.FULL, null).verified());
        assertTrue(ChainVerifier.verify(b, forB, ChainVerifier.Level.FULL, null).verified());
    }

    @Test
    @DisplayName("SEC-TEN-005: an audit write with no tenant context raises")
    void writeWithoutContextRaises() {
        var store = new InMemoryStore();
        assertThrows(MissingTenantContextException.class, () -> writer(store).record(draft(), Map.of()));
        assertTrue(store.written.isEmpty());
    }

    @Test
    @DisplayName("SEC-AUD-006: an uncatalogued event type is rejected")
    void uncataloguedTypeRejected() {
        var store = new InMemoryStore();
        var w = writer(store);
        var uncatalogued = new AuditRecorder.AuditDraft("finding.invented", AuditOutcome.SUCCESS, null,
                null, null, null, ActorType.SYSTEM, null, null, AuditScope.unscoped(1));

        TenantContextHolder.runWith(context(new TenantId(UUID.randomUUID())), () ->
                assertThrows(IllegalArgumentException.class, () -> w.record(uncatalogued, Map.of())));
        assertTrue(store.written.isEmpty());
    }

    @Test
    @DisplayName("SEC-TEN-030: the break-glass reference comes from the context, not the emitter")
    void breakGlassReferenceComesFromContext() {
        var store = new InMemoryStore();
        var w = writer(store);
        var tenant = new TenantId(UUID.randomUUID());
        var grant = UUID.randomUUID();

        TenantContextHolder.runWith(
                TenantContext.breakGlass(tenant, "vn-south", FIXED, grant),
                () -> w.record(draft(), Map.of()));

        assertEquals(grant, store.written.get(0).envelope().breakGlassRef(),
                "an emitter that could omit the reference could hide break-glass activity the tenant is "
                        + "entitled to see");
    }

    // ------------------------------------------------------------------ payload canonicalization

    @Test
    @DisplayName("payload map ordering does not affect the hash")
    void payloadOrderingIsIrrelevant() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("zebra", 1);
        a.put("alpha", 2);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("alpha", 2);
        b.put("zebra", 1);

        assertArrayEquals(CanonicalPayload.canonicalize(a), CanonicalPayload.canonicalize(b),
                "a HashMap orders by hash, which varies with insertion history; if that reached the hash, "
                        + "identical payloads would produce different payload_hash values and verification "
                        + "would fail on unaltered data");
    }

    @Test
    @DisplayName("list order DOES affect the hash, because order is meaningful in a before/after payload")
    void listOrderIsSignificant() {
        assertFalse(java.util.Arrays.equals(
                CanonicalPayload.canonicalize(Map.of("k", List.of("a", "b"))),
                CanonicalPayload.canonicalize(Map.of("k", List.of("b", "a")))));
    }

    @Test
    @DisplayName("a null value and the string 'null' do not hash identically")
    void nullIsDistinguishable() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("k", null);
        assertFalse(java.util.Arrays.equals(
                CanonicalPayload.canonicalize(withNull),
                CanonicalPayload.canonicalize(Map.of("k", "null"))));
    }

    @Test
    @DisplayName("BigDecimal scale does not affect the hash; binary floating point is rejected")
    void numericFormsAreControlled() {
        assertArrayEquals(
                CanonicalPayload.canonicalize(Map.of("k", new BigDecimal("1.0"))),
                CanonicalPayload.canonicalize(Map.of("k", new BigDecimal("1.00"))));
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalPayload.canonicalize(Map.of("k", 1.0d)),
                "the textual form of a double is platform-dependent, so the hash would be too");
    }

    @Test
    @DisplayName("payload nesting is bounded")
    void nestingIsBounded() {
        Map<String, Object> deep = Map.of("leaf", 1);
        for (int i = 0; i <= CanonicalPayload.MAX_DEPTH; i++) {
            deep = Map.of("n", deep);
        }
        Map<String, Object> tooDeep = deep;
        assertThrows(IllegalArgumentException.class, () -> CanonicalPayload.canonicalize(tooDeep));
    }

    @Test
    @DisplayName("an empty payload hashes as the defined empty-payload value")
    void emptyPayloadIsDefined() {
        var store = new InMemoryStore();
        var w = writer(store);
        TenantContextHolder.runWith(context(new TenantId(UUID.randomUUID())),
                () -> w.record(draft(), Map.of()));
        assertArrayEquals(ChainHasher.emptyPayloadHash(), store.written.get(0).payloadHash(),
                "an event without a payload must still have a defined chain input");
    }

    @Test
    @DisplayName("the canonical version is recorded on every event, per SEC-AUD-011")
    void canonicalVersionIsRecorded() {
        var store = new InMemoryStore();
        var w = writer(store);
        TenantContextHolder.runWith(context(new TenantId(UUID.randomUUID())),
                () -> w.record(draft(), Map.of()));
        assertEquals(CanonicalSerializer.CURRENT_VERSION, store.written.get(0).canonicalVersion(),
                "versioning is what lets the format change without invalidating history");
    }
}
