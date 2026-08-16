package aspm.kernel.audit.domain;

import aspm.kernel.audit.contract.AuditEnvelope;
import aspm.sharedkernel.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Chain verification, per DOC-14 sections 4.2 and 6.
 *
 * <p>Three levels because "the cost differs by two orders of magnitude" (DOC-14 section 4.2):
 * {@link Level#SPOT} checks sequence continuity and linkage over a bounded range and runs continuously;
 * {@link Level#RANGE} recomputes over a period against a checkpoint; {@link Level#FULL} recomputes from
 * genesis. The distinction exists so that continuous verification is affordable — a verifier with only
 * a full mode is a verifier that runs quarterly.
 *
 * <p><b>Two independent mechanisms, not one.</b> {@code SEC-AUD-002} requires the sequence to be
 * gapless and a gap to be a verification failure, and states why this is separate from the hash chain:
 * "a gapless sequence is what makes removal detectable independently of the hash chain — the chain
 * alone cannot distinguish a removed tail from a shorter history". Both are checked below, and a gap is
 * reported as its own finding rather than folded into a linkage failure.
 *
 * <p><b>Erasure is not tampering.</b> {@code SEC-AUD-020}: "Verification MUST report erased payloads
 * distinctly from missing or altered data, and MUST NOT report an erased payload as a verification
 * failure." Conflating them "either produces false alarms or trains the reader to ignore real ones",
 * and given {@code SEC-AUD-018} makes a failure page to a destination outside operator control, false
 * alarms here are expensive. An erased payload is therefore counted and reported as an observation, and
 * the chain over it still verifies because the chain covers the hash.
 */
public final class ChainVerifier {

    public enum Level {
        /** Sequence continuity and linkage over a bounded range. Cheap; runs continuously. */
        SPOT,
        /** Full recomputation over a period against a checkpoint. Moderate. */
        RANGE,
        /** Recomputation from genesis. Expensive; on suspicion or for audit. */
        FULL
    }

    /**
     * What a verifier is given per event: the envelope plus the stored integrity and erasure fields.
     *
     * <p><b>Deliberately a class, not a record.</b> A record with {@code byte[]} components gets
     * value-semantics {@code equals} that compares arrays by identity, so two events with identical
     * hashes would compare unequal while the type advertises value equality. A test written later as
     * {@code assertEquals(expected, actual)} would then pass or fail for a reason unrelated to the
     * chain. A class inherits identity equality, which is unambiguous and does not mislead — and
     * comparison of hashes goes through {@link ChainHasher#matches} in constant time regardless.
     */
    public static final class StoredEvent {

        private final AuditEnvelope envelope;
        private final int canonicalVersion;
        private final byte[] payloadHash;
        private final byte[] prevChainHash;
        private final byte[] chainHash;
        private final Instant payloadErasedAt;
        private final String payloadErasureBasis;

        public StoredEvent(
                AuditEnvelope envelope,
                int canonicalVersion,
                byte[] payloadHash,
                byte[] prevChainHash,
                byte[] chainHash,
                Instant payloadErasedAt,
                String payloadErasureBasis) {
            this.envelope = Objects.requireNonNull(envelope, "envelope is required");
            this.canonicalVersion = canonicalVersion;
            // Defensive copies: a caller retaining the array could otherwise mutate a hash after
            // verification read it, which would make a report describe state that no longer exists.
            this.payloadHash = Objects.requireNonNull(payloadHash, "payload hash is required").clone();
            this.prevChainHash =
                    Objects.requireNonNull(prevChainHash, "previous chain hash is required").clone();
            this.chainHash = Objects.requireNonNull(chainHash, "chain hash is required").clone();
            this.payloadErasedAt = payloadErasedAt;
            this.payloadErasureBasis = payloadErasureBasis;
        }

        public AuditEnvelope envelope() {
            return envelope;
        }

        public int canonicalVersion() {
            return canonicalVersion;
        }

        public byte[] payloadHash() {
            return payloadHash.clone();
        }

        public byte[] prevChainHash() {
            return prevChainHash.clone();
        }

        public byte[] chainHash() {
            return chainHash.clone();
        }

        public Instant payloadErasedAt() {
            return payloadErasedAt;
        }

        public String payloadErasureBasis() {
            return payloadErasureBasis;
        }

        boolean payloadErased() {
            return payloadErasedAt != null;
        }
    }

    /** A single finding. Deliberately typed, so a caller cannot treat an observation as a failure. */
    public record Finding(Kind kind, long sequence, String detail) {

        public enum Kind {
            /** The recomputed chain hash does not match the stored one. Tampering or reordering. */
            LINKAGE_MISMATCH(true),
            /** The stored previous hash does not match the actual predecessor's hash. */
            PREDECESSOR_MISMATCH(true),
            /** A gap in the sequence. SEC-AUD-002 makes this a failure in its own right. */
            SEQUENCE_GAP(true),
            /** Two events share a sequence. SEC-AUD-014's forked chain. */
            SEQUENCE_DUPLICATE(true),
            /** The chain does not begin at the tenant genesis. */
            GENESIS_MISMATCH(true),
            /** A canonical version this verifier cannot serialize. Not a tampering signal. */
            UNKNOWN_CANONICAL_VERSION(true),
            /** An erased payload. NOT a failure — SEC-AUD-020. */
            PAYLOAD_ERASED(false),
            /** An erasure recorded without its basis. A record-keeping defect, not tampering. */
            ERASURE_BASIS_MISSING(true);

            private final boolean failure;

            Kind(boolean failure) {
                this.failure = failure;
            }

            /** True where this finding invalidates the trail. False for an observation. */
            public boolean isFailure() {
                return failure;
            }
        }
    }

    /** The verification report, reportable as evidence per {@code SEC-AUD-017}. */
    public record Report(
            Level level,
            TenantId tenantId,
            long eventsExamined,
            long erasedPayloads,
            List<Finding> findings) {

        public Report {
            findings = List.copyOf(Objects.requireNonNull(findings, "findings is required"));
        }

        /**
         * True where no finding invalidates the trail.
         *
         * <p>Erased payloads do not affect this. That is {@code SEC-AUD-020} and it is the single most
         * important property of this type: a tenant that has exercised erasure must still be able to
         * produce a passing verification report, or the platform has made compliance and auditability
         * mutually exclusive.
         */
        public boolean verified() {
            return findings.stream().noneMatch(f -> f.kind().isFailure());
        }

        public List<Finding> failures() {
            return findings.stream().filter(f -> f.kind().isFailure()).toList();
        }
    }

    private ChainVerifier() {
        throw new AssertionError("not instantiable");
    }

    /**
     * Verifies a contiguous run of events.
     *
     * @param tenantId the tenant whose chain this is; binds the genesis under {@code SEC-AUD-012}
     * @param events events in ascending sequence order
     * @param level the level being performed, recorded in the report
     * @param expectedStartHash the chain hash preceding the first event. For {@link Level#FULL} pass
     *     {@code null} and the genesis is computed; for {@link Level#SPOT} and {@link Level#RANGE} pass
     *     the checkpoint or predecessor hash the run continues from
     */
    public static Report verify(
            TenantId tenantId, List<StoredEvent> events, Level level, byte[] expectedStartHash) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(events, "events are required");
        Objects.requireNonNull(level, "level is required");

        List<Finding> findings = new ArrayList<>();
        long erased = 0;

        byte[] running = expectedStartHash != null ? expectedStartHash : ChainHasher.genesis(tenantId);

        if (level == Level.FULL && !events.isEmpty() && events.get(0).envelope().sequence() != 0) {
            findings.add(new Finding(Finding.Kind.GENESIS_MISMATCH, events.get(0).envelope().sequence(),
                    "a full verification must start at sequence 0; started at "
                            + events.get(0).envelope().sequence()));
        }

        Long previousSequence = null;
        for (StoredEvent event : events) {
            long sequence = event.envelope().sequence();

            // --- SEC-AUD-002: the sequence mechanism, independent of the hash chain ---
            if (previousSequence != null) {
                if (sequence == previousSequence) {
                    findings.add(new Finding(Finding.Kind.SEQUENCE_DUPLICATE, sequence,
                            "two events share sequence " + sequence + "; SEC-AUD-014 calls a forked "
                                    + "chain undetectable as tampering and unrepairable"));
                } else if (sequence != previousSequence + 1) {
                    findings.add(new Finding(Finding.Kind.SEQUENCE_GAP, sequence,
                            "gap between " + previousSequence + " and " + sequence
                                    + "; a gap is a verification failure in its own right because the "
                                    + "chain alone cannot distinguish a removed tail from a shorter "
                                    + "history (SEC-AUD-002)"));
                }
            }
            previousSequence = sequence;

            // --- SEC-AUD-020: erasure is an observation, not a failure ---
            if (event.payloadErased()) {
                erased++;
                findings.add(new Finding(Finding.Kind.PAYLOAD_ERASED, sequence,
                        "payload erased" + (event.payloadErasureBasis() == null ? ""
                                : " on basis: " + event.payloadErasureBasis())));
                if (event.payloadErasureBasis() == null) {
                    // SEC-AUD-019 requires the basis to be recorded. Its absence is a record-keeping
                    // defect, reported separately from the erasure itself.
                    findings.add(new Finding(Finding.Kind.ERASURE_BASIS_MISSING, sequence,
                            "erasure recorded without its basis (SEC-AUD-019)"));
                }
            }

            // --- The stored predecessor must match what we actually have ---
            if (!ChainHasher.matches(running, event.prevChainHash())) {
                findings.add(new Finding(Finding.Kind.PREDECESSOR_MISMATCH, sequence,
                        "stored previous hash does not match the actual predecessor"));
            }

            // --- Recompute the link. Note this works over an erased payload, because the chain
            // --- covers payloadHash and that column survives erasure (CON-DAT-027).
            byte[] recomputed;
            try {
                recomputed = ChainHasher.link(
                        event.prevChainHash(), event.envelope(), event.payloadHash(),
                        event.canonicalVersion());
            } catch (IllegalArgumentException unknownVersion) {
                findings.add(new Finding(Finding.Kind.UNKNOWN_CANONICAL_VERSION, sequence,
                        unknownVersion.getMessage()));
                running = event.chainHash();
                continue;
            }

            if (!ChainHasher.matches(recomputed, event.chainHash())) {
                findings.add(new Finding(Finding.Kind.LINKAGE_MISMATCH, sequence,
                        "recomputed chain hash does not match the stored value"));
            }

            running = event.chainHash();
        }

        return new Report(level, tenantId, events.size(), erased, findings);
    }
}
