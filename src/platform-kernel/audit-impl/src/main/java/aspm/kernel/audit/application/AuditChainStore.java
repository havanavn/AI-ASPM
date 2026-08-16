package aspm.kernel.audit.application;

import aspm.kernel.audit.contract.AuditEnvelope;
import aspm.sharedkernel.TenantId;
import java.util.Objects;

/**
 * The persistence port for the audit chain.
 *
 * <p>A port rather than a direct JDBC dependency so that {@link ChainedAuditWriter}'s sequencing and
 * chaining logic is testable without a database. {@code TST-PLT-005}: "invariant tests MUST execute
 * against the domain layer without a database where the invariant is domain-enforced" — because
 * {@code CON-PLT-017} exists so those tests are fast, and slow tests are run less often, and invariants
 * then regress.
 *
 * <p>Every method runs inside an established tenant context and inside the caller's transaction, because
 * the implementation obtains its connection through the tenant-context gate.
 */
public interface AuditChainStore {

    /**
     * Reads and <b>locks</b> the tenant's chain head for the remainder of the transaction.
     *
     * <p>{@code SEC-AUD-014} requires that "a concurrent insert MUST NOT produce two events with the same
     * sequence or a forked chain", and names per-tenant chain head serialization as the mechanism. The
     * lock is that serialization. It is taken here rather than in the writer because only the persistence
     * layer can take it, and it is part of this method rather than a separate call because a read
     * followed by an optional lock is a race that would compile.
     *
     * @return the current head, at the genesis where the tenant has no events yet
     */
    ChainHead lockHead(TenantId tenantId);

    /**
     * Appends the event, its payload, and the advanced head, in the caller's transaction.
     *
     * <p>One method rather than three, because {@code SEC-AUD-014} requires chain computation and insert
     * in the same transaction and {@code CON-PLT-021} makes audit the platform's single deliberate
     * availability-for-integrity trade. Three methods would permit two of them to succeed.
     */
    void append(
            AuditEnvelope envelope,
            int canonicalVersion,
            byte[] payloadHash,
            byte[] prevChainHash,
            byte[] chainHash,
            byte[] canonicalPayload);

    /**
     * The tenant's chain head.
     *
     * <p>A class rather than a record for the same reason as {@code ChainVerifier.StoredEvent}: a record
     * with a {@code byte[]} component advertises value equality and delivers identity comparison.
     */
    final class ChainHead {

        private final long lastSequence;
        private final byte[] lastChainHash;

        public ChainHead(long lastSequence, byte[] lastChainHash) {
            if (lastSequence < -1) {
                throw new IllegalArgumentException(
                        "the head is -1 before the first event, so the first event takes sequence 0 and "
                                + "SEC-AUD-002's gapless-from-zero property needs no special case");
            }
            this.lastSequence = lastSequence;
            this.lastChainHash =
                    Objects.requireNonNull(lastChainHash, "head chain hash is required").clone();
        }

        public long lastSequence() {
            return lastSequence;
        }

        public byte[] lastChainHash() {
            return lastChainHash.clone();
        }

        /** The sequence the next event takes. */
        public long nextSequence() {
            return lastSequence + 1;
        }
    }
}
