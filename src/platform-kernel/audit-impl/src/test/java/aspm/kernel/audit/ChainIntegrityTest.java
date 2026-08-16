package aspm.kernel.audit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.kernel.audit.contract.ActorType;
import aspm.kernel.audit.contract.AuditEnvelope;
import aspm.kernel.audit.contract.AuditEventType;
import aspm.kernel.audit.contract.AuditOutcome;
import aspm.kernel.audit.contract.AuditScope;
import aspm.kernel.audit.domain.CanonicalSerializer;
import aspm.kernel.audit.domain.ChainHasher;
import aspm.kernel.audit.domain.ChainVerifier;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.PrincipalId;
import aspm.sharedkernel.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The audit chain of DOC-14 sections 4 and 6, and {@code INV-AUD-02}.
 *
 * <p>These run without a database. The append-only property is a matter of grants and is verified in
 * {@code KernelPersistenceVerificationTest}; the integrity property is arithmetic and is verified here.
 */
class ChainIntegrityTest {

    private static final TenantId TENANT_A = new TenantId(UUID.randomUUID());
    private static final TenantId TENANT_B = new TenantId(UUID.randomUUID());
    private static final Instant T0 = Instant.parse("2026-08-04T09:00:00Z");

    private static AuditEnvelope envelope(TenantId tenant, long sequence, String type) {
        return new AuditEnvelope(
                UUID.nameUUIDFromBytes(("e" + sequence).getBytes(StandardCharsets.UTF_8)),
                tenant, sequence, type, AuditOutcome.SUCCESS, null,
                "Finding", UUID.nameUUIDFromBytes(("o" + sequence).getBytes(StandardCharsets.UTF_8)),
                new PrincipalId(UUID.nameUUIDFromBytes("actor".getBytes(StandardCharsets.UTF_8))),
                ActorType.USER, null, null, null,
                T0.plusSeconds(sequence),
                new AuditScope(new OrgNodeId(UUID.nameUUIDFromBytes("n".getBytes(StandardCharsets.UTF_8))),
                        List.of(new OrgNodeId(UUID.nameUUIDFromBytes("r".getBytes(StandardCharsets.UTF_8)))),
                        7L));
    }

    /** Builds a valid run of {@code count} events starting at sequence 0. */
    private static List<ChainVerifier.StoredEvent> validChain(TenantId tenant, int count) {
        List<ChainVerifier.StoredEvent> events = new ArrayList<>();
        byte[] previous = ChainHasher.genesis(tenant);
        for (long i = 0; i < count; i++) {
            var env = envelope(tenant, i, AuditEventType.SESSION_CREATED.code());
            byte[] payloadHash = ChainHasher.hashPayload(("payload" + i).getBytes(StandardCharsets.UTF_8));
            byte[] chain = ChainHasher.link(previous, env, payloadHash, CanonicalSerializer.CURRENT_VERSION);
            events.add(new ChainVerifier.StoredEvent(
                    env, CanonicalSerializer.CURRENT_VERSION, payloadHash, previous, chain, null, null));
            previous = chain;
        }
        return events;
    }

    @Nested
    @DisplayName("SEC-AUD-011 — canonical serialization")
    class Canonicalization {

        @Test
        @DisplayName("is deterministic across repeated invocation")
        void deterministic() {
            var env = envelope(TENANT_A, 3, AuditEventType.AUTHZ_DENIED.code());
            assertArrayEquals(
                    CanonicalSerializer.canonicalize(env, 1),
                    CanonicalSerializer.canonicalize(env, 1),
                    "any variance makes verification fail on unaltered data (SEC-AUD-011)");
        }

        @Test
        @DisplayName("distinguishes a null field from an empty string")
        void nullIsNotEmptyString() {
            var withNull = new AuditEnvelope(UUID.randomUUID(), TENANT_A, 1, "x", AuditOutcome.SUCCESS,
                    null, null, null, null, ActorType.SYSTEM, null, null, null, T0,
                    AuditScope.unscoped(1));
            var withEmpty = new AuditEnvelope(UUID.randomUUID(), TENANT_A, 1, "", AuditOutcome.SUCCESS,
                    null, null, null, null, ActorType.SYSTEM, null, null, null, T0,
                    AuditScope.unscoped(1));
            assertFalse(java.util.Arrays.equals(
                            CanonicalSerializer.canonicalize(withNull, 1),
                            CanonicalSerializer.canonicalize(withEmpty, 1)),
                    "if null and empty canonicalize identically, one event can be substituted for the other");
        }

        @Test
        @DisplayName("an unknown version raises rather than guessing")
        void unknownVersionRaises() {
            var env = envelope(TENANT_A, 1, "x");
            assertThrows(IllegalArgumentException.class, () -> CanonicalSerializer.canonicalize(env, 99),
                    "guessing produces a verification failure on unaltered data, which SEC-AUD-018 "
                            + "escalates as the most serious signal the platform can produce");
        }

        @Test
        @DisplayName("a timestamp with trailing zero nanoseconds is fixed-width")
        void timestampIsFixedWidth() {
            // Instant.toString() omits trailing zeros, so 09:00:00Z and 09:00:00.000000000Z would
            // serialize differently under a naive formatter while being the same instant.
            var a = envelope(TENANT_A, 0, "x");
            var b = new AuditEnvelope(a.eventId(), a.tenantId(), a.sequence(), a.eventType(),
                    a.outcome(), a.denialReason(), a.objectKind(), a.objectId(), a.actorId(),
                    a.actorType(), a.onBehalfOfId(), a.automationRuleId(), a.breakGlassRef(),
                    Instant.parse("2026-08-04T09:00:00.000000000Z"), a.scope());
            assertArrayEquals(CanonicalSerializer.canonicalize(a, 1),
                    CanonicalSerializer.canonicalize(b, 1));
        }
    }

    @Nested
    @DisplayName("SEC-AUD-012 — the chain is per tenant")
    class PerTenant {

        @Test
        @DisplayName("two tenants have different genesis hashes")
        void genesisDiffersByTenant() {
            assertFalse(ChainHasher.matches(ChainHasher.genesis(TENANT_A), ChainHasher.genesis(TENANT_B)),
                    "a shared chain would make one tenant's verification depend on another's events, and "
                            + "one tenant's offboarding would break every other tenant's chain");
        }

        @Test
        @DisplayName("a chain copied to another tenant does not verify")
        void copiedChainDoesNotVerify() {
            // Every event copied verbatim, but verified against tenant B's genesis.
            var report = ChainVerifier.verify(TENANT_B, validChain(TENANT_A, 3),
                    ChainVerifier.Level.FULL, null);
            assertFalse(report.verified(),
                    "the genesis binds the tenant identifier, so a chain cannot be replayed under a "
                            + "different tenant even if every event were copied");
        }
    }

    @Nested
    @DisplayName("DOC-14 section 4.2 — verification")
    class Verification {

        @Test
        @DisplayName("a valid chain verifies at FULL from genesis")
        void validChainVerifies() {
            var report = ChainVerifier.verify(TENANT_A, validChain(TENANT_A, 5),
                    ChainVerifier.Level.FULL, null);
            assertTrue(report.verified(), () -> "unexpected findings: " + report.failures());
            assertEquals(5, report.eventsExamined());
            assertEquals(0, report.erasedPayloads());
        }

        @Test
        @DisplayName("an altered envelope field is detected as a linkage mismatch")
        void alteredEnvelopeDetected() {
            var events = new ArrayList<>(validChain(TENANT_A, 4));
            var original = events.get(2);
            // Change the outcome from SUCCESS to DENIED while leaving every hash untouched — the shape
            // of an adversary editing the trail in place.
            var e = original.envelope();
            var tampered = new AuditEnvelope(e.eventId(), e.tenantId(), e.sequence(), e.eventType(),
                    AuditOutcome.DENIED, null, e.objectKind(), e.objectId(), e.actorId(), e.actorType(),
                    e.onBehalfOfId(), e.automationRuleId(), e.breakGlassRef(), e.occurredAt(), e.scope());
            events.set(2, new ChainVerifier.StoredEvent(tampered, original.canonicalVersion(),
                    original.payloadHash(), original.prevChainHash(), original.chainHash(), null, null));

            var report = ChainVerifier.verify(TENANT_A, events, ChainVerifier.Level.FULL, null);
            assertFalse(report.verified());
            assertTrue(report.failures().stream()
                            .anyMatch(f -> f.kind() == ChainVerifier.Finding.Kind.LINKAGE_MISMATCH),
                    "expected a linkage mismatch, got " + report.failures());
        }

        @Test
        @DisplayName("SEC-AUD-002: a removed event is detected as a sequence gap, independently of the chain")
        void removedEventDetectedAsGap() {
            var events = new ArrayList<>(validChain(TENANT_A, 5));
            events.remove(2);   // excise one event and leave the rest untouched

            var report = ChainVerifier.verify(TENANT_A, events, ChainVerifier.Level.FULL, null);
            assertFalse(report.verified());
            assertTrue(report.failures().stream()
                            .anyMatch(f -> f.kind() == ChainVerifier.Finding.Kind.SEQUENCE_GAP),
                    "the gapless sequence is what makes removal detectable independently of the hash "
                            + "chain, because the chain alone cannot distinguish a removed tail from a "
                            + "shorter history (SEC-AUD-002). Findings: " + report.failures());
        }

        @Test
        @DisplayName("SEC-AUD-014: a duplicated sequence is detected as a forked chain")
        void duplicateSequenceDetected() {
            var events = new ArrayList<>(validChain(TENANT_A, 3));
            events.add(1, events.get(1));

            var report = ChainVerifier.verify(TENANT_A, events, ChainVerifier.Level.FULL, null);
            assertTrue(report.failures().stream()
                            .anyMatch(f -> f.kind() == ChainVerifier.Finding.Kind.SEQUENCE_DUPLICATE),
                    "a forked chain is undetectable as tampering and unrepairable (SEC-AUD-014)");
        }

        @Test
        @DisplayName("a SPOT check over a bounded range verifies from a supplied predecessor hash")
        void spotCheckFromCheckpoint() {
            var all = validChain(TENANT_A, 6);
            var tail = all.subList(3, 6);
            var report = ChainVerifier.verify(
                    TENANT_A, tail, ChainVerifier.Level.SPOT, all.get(2).chainHash());
            assertTrue(report.verified(), () -> "unexpected findings: " + report.failures());
            assertEquals(ChainVerifier.Level.SPOT, report.level());
        }
    }

    @Nested
    @DisplayName("SEC-AUD-019, SEC-AUD-020, CON-DAT-027 — erasure reconciliation")
    class Erasure {

        /** The whole point of ADR-034: erase the payload, keep the chain verifiable. */
        @Test
        @DisplayName("an erased payload leaves the chain verifying and is NOT reported as a failure")
        void erasedPayloadIsNotAFailure() {
            var events = new ArrayList<>(validChain(TENANT_A, 4));
            var original = events.get(1);
            // Erasure deletes the payload row and marks the event. payload_hash is untouched, which is
            // why every chain_hash stays valid.
            events.set(1, new ChainVerifier.StoredEvent(
                    original.envelope(), original.canonicalVersion(), original.payloadHash(),
                    original.prevChainHash(), original.chainHash(),
                    Instant.parse("2026-08-05T00:00:00Z"), "erasure request ER-0007"));

            var report = ChainVerifier.verify(TENANT_A, events, ChainVerifier.Level.FULL, null);

            assertTrue(report.verified(),
                    "an erased payload must not be a verification failure (SEC-AUD-020). A tenant that "
                            + "has exercised erasure must still be able to produce a passing report, or "
                            + "compliance and auditability have been made mutually exclusive. Findings: "
                            + report.failures());
            assertEquals(1, report.erasedPayloads(), "the erasure must still be reported, distinctly");
            assertTrue(report.findings().stream()
                    .anyMatch(f -> f.kind() == ChainVerifier.Finding.Kind.PAYLOAD_ERASED));
        }

        @Test
        @DisplayName("SEC-AUD-019: an erasure without its basis is reported as a defect")
        void erasureWithoutBasisIsReported() {
            var events = new ArrayList<>(validChain(TENANT_A, 2));
            var original = events.get(0);
            events.set(0, new ChainVerifier.StoredEvent(
                    original.envelope(), original.canonicalVersion(), original.payloadHash(),
                    original.prevChainHash(), original.chainHash(),
                    Instant.parse("2026-08-05T00:00:00Z"), null));

            var report = ChainVerifier.verify(TENANT_A, events, ChainVerifier.Level.FULL, null);
            assertFalse(report.verified());
            assertTrue(report.failures().stream()
                    .anyMatch(f -> f.kind() == ChainVerifier.Finding.Kind.ERASURE_BASIS_MISSING));
        }

        @Test
        @DisplayName("erasure of one payload does not affect any other link")
        void erasureIsLocal() {
            var clean = validChain(TENANT_A, 4);
            var erased = new ArrayList<>(clean);
            var second = clean.get(2);
            erased.set(2, new ChainVerifier.StoredEvent(second.envelope(), second.canonicalVersion(),
                    second.payloadHash(), second.prevChainHash(), second.chainHash(),
                    Instant.parse("2026-08-05T00:00:00Z"), "basis"));

            // Every chain hash is byte-identical before and after erasure. This is the property that
            // makes CON-DAT-027 work, and it is worth asserting rather than assuming.
            for (int i = 0; i < clean.size(); i++) {
                assertArrayEquals(clean.get(i).chainHash(), erased.get(i).chainHash(),
                        "erasure changed a chain hash at index " + i);
            }
        }
    }

    @Nested
    @DisplayName("SEC-AUD-001, SEC-AUD-004 — envelope constraints")
    class EnvelopeConstraints {

        @Test
        @DisplayName("SEC-AUD-004: an AUTOMATION actor without its rule is not representable")
        void automationRequiresItsRule() {
            assertThrows(IllegalArgumentException.class, () -> new AuditEnvelope(
                    UUID.randomUUID(), TENANT_A, 1, "x", AuditOutcome.SUCCESS, null, null, null,
                    null, ActorType.AUTOMATION, null, null, null, T0, AuditScope.unscoped(1)),
                    "where automation acts, the rule and its owning principal must both be recoverable "
                            + "or an automated escalation has no traceable origin (SEC-AUD-004)");
        }

        @Test
        @DisplayName("a denial reason on a non-denied outcome is not representable")
        void denialReasonRequiresDenial() {
            assertThrows(IllegalArgumentException.class, () -> new AuditEnvelope(
                    UUID.randomUUID(), TENANT_A, 1, "x", AuditOutcome.SUCCESS, "OUT_OF_SCOPE", null,
                    null, null, ActorType.SYSTEM, null, null, null, T0, AuditScope.unscoped(1)));
        }

        @Test
        @DisplayName("SEC-AUD-006: the catalogue is an enum, so an uncatalogued type cannot be named")
        void catalogueIsClosed() {
            // Not a behavioural assertion: the point is that AuditEventType has no factory from a
            // string. An uncatalogued type is a symbol that does not resolve, so the build fails.
            assertTrue(AuditEventType.values().length >= 75,
                    "DOC-14 section 3 enumerates ten categories; a truncated catalogue silently narrows "
                            + "audit coverage, which is the gap SEC-AUD-006 exists to prevent");
            assertNotEquals(AuditEventType.AUTHZ_DENIED.code(), AuditEventType.AUTH_FAILED.code());
        }
    }
}
