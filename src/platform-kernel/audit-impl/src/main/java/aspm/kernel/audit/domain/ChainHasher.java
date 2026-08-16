package aspm.kernel.audit.domain;

import aspm.kernel.audit.contract.AuditEnvelope;
import aspm.sharedkernel.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * The hash chain of DOC-14 section 4.1.
 *
 * <pre>
 *   chainHash(n) = H( chainHash(n-1) || canonical(envelope(n)) || payloadHash(n) )
 *   chainHash(0) = H( tenantId || genesisMarker )
 * </pre>
 *
 * <p><b>The chain covers the payload hash, not the payload.</b> This is {@code CON-DAT-027} and
 * ADR-034, and it is the mechanism reconciling erasure with verifiability: deleting the payload row
 * leaves every chain hash unchanged and every link verifiable. What is lost is the ability to verify
 * that the erased payload matched its hash — DOC-04 section 20.1 states that limit plainly and it is
 * not softened here.
 *
 * <p><b>Per tenant, never across.</b> {@code SEC-AUD-012}: a shared chain would make one tenant's
 * verification depend on another's events, and one tenant's offboarding would break every other
 * tenant's chain. The genesis binds the tenant identifier, so a chain cannot be replayed under a
 * different tenant even if every event were copied — which also closes the cross-tenant inference that
 * a global chain would create.
 */
public final class ChainHasher {

    /**
     * "A current recommended cryptographic hash" per DOC-14 section 4.1.
     *
     * <p>Part of the canonical version rather than an independent setting: changing it means a new
     * {@link CanonicalSerializer#CURRENT_VERSION}, not a rehash of history, because a verifier
     * dispatches on the version recorded per event.
     */
    public static final String ALGORITHM = "SHA-256";

    private static final byte[] GENESIS_MARKER =
            "aspm.audit.genesis.v1".getBytes(StandardCharsets.UTF_8);

    private ChainHasher() {
        throw new AssertionError("not instantiable");
    }

    /** The genesis link, {@code H(tenantId || genesisMarker)}. */
    public static byte[] genesis(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId is required; SEC-AUD-012 binds the chain to it");
        MessageDigest digest = newDigest();
        digest.update(tenantId.value().toString().getBytes(StandardCharsets.UTF_8));
        digest.update(GENESIS_MARKER);
        return digest.digest();
    }

    /** One link, {@code H(previous || canonical(envelope) || payloadHash)}. */
    public static byte[] link(
            byte[] previousChainHash, AuditEnvelope envelope, byte[] payloadHash, int canonicalVersion) {
        Objects.requireNonNull(previousChainHash, "previous chain hash is required");
        Objects.requireNonNull(envelope, "envelope is required");
        Objects.requireNonNull(payloadHash, "payload hash is required");

        MessageDigest digest = newDigest();
        digest.update(previousChainHash);
        digest.update(CanonicalSerializer.canonicalize(envelope, canonicalVersion));
        digest.update(payloadHash);
        return digest.digest();
    }

    /**
     * The payload hash.
     *
     * <p>Takes bytes rather than a map: canonicalizing a payload is the caller's decision, and a silent
     * default here would let two logically identical payloads hash differently depending on map
     * iteration order — reintroducing at the payload the variance {@link CanonicalSerializer} removes
     * at the envelope.
     */
    public static byte[] hashPayload(byte[] canonicalPayloadBytes) {
        Objects.requireNonNull(canonicalPayloadBytes, "payload bytes are required");
        return newDigest().digest(canonicalPayloadBytes);
    }

    /** The hash of an absent payload, so an event without one still has a defined chain input. */
    public static byte[] emptyPayloadHash() {
        return newDigest().digest(new byte[0]);
    }

    /** Constant-time comparison, so verification does not leak a hash prefix through timing. */
    public static boolean matches(byte[] expected, byte[] actual) {
        return MessageDigest.isEqual(expected, actual);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // Not recoverable and not a policy outcome. Without the digest there is no integrity
            // property at all, and continuing would write events that can never be verified.
            throw new IllegalStateException(ALGORITHM + " is unavailable; audit integrity cannot be "
                    + "computed and writing must not continue (INV-AUD-02)", e);
        }
    }
}
