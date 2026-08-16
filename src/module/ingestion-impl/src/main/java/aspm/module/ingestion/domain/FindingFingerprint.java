package aspm.module.ingestion.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Finding identity, per DOC-03 section 10.2.
 *
 * <p><b>This is the only site in the platform that computes a fingerprint.</b> {@code INV-ING-01}, which DOC-16
 * section 4.2 lists among the twelve unrecoverable invariants and describes as "the one unrecoverable invariant
 * with no database enforcement". There is no column constraint that can express "only this module wrote this
 * value", so the enforcement is a build-time structural assertion plus the fact that this class lives in
 * {@code ingestion-impl} and nothing outside the module can reach it (ADR-050's {@code CON-PLT-013}).
 *
 * <p>{@code INV-VUL-06} and ADR-011: the fingerprint is computed in Ingestion and is identical regardless of
 * source path. A file import and a native match run reach the same digest, because they reach the same code.
 *
 * <p><b>The two failure modes, from DOC-03 section 10.2 verbatim, because they shaped every decision here:</b>
 *
 * <blockquote>Too specific: every rescan creates new records, triage state is lost, counts inflate without
 * cause, trend becomes noise, and the team stops believing the number — after which no subsequent correctness
 * recovers the deployment. Too loose: distinct issues collapse, fixing one appears to fix all, and closure is
 * wrong.</blockquote>
 *
 * <p>Both are destroyed data trust in opposite directions, and neither is repaired by a later fix. The rescan
 * corpus of DOC-16 section 7.1 is what sits between them.
 */
public final class FindingFingerprint {

    /**
     * The current algorithm version, recorded on every finding ({@code INV-VUL-03}).
     *
     * <p>A finding created under one version is never compared against another version's digest. That is why
     * this is recorded per finding rather than assumed: cross-version comparison would be a silent mass
     * de-duplication failure or a silent mass merge, depending on which direction the algorithm moved.
     */
    public static final int CURRENT_ALGORITHM_VERSION = 1;

    private static final String ALGORITHM = "SHA-256";

    /**
     * Marker for an input the source did not supply.
     *
     * <p>Distinct from an empty string, because {@code PRD-ING-021} forbids inferring: an empty string asserts
     * the source supplied nothing, where in fact the source may not have been asked. Two findings differing only
     * in whether a field was absent or empty are different findings.
     */
    private static final String ABSENT_MARKER = "<absent>";

    private final int algorithmVersion;
    private final FingerprintInputs.FindingClass findingClass;
    private final byte[] digest;
    private final FingerprintInputs inputSnapshot;

    private FindingFingerprint(int algorithmVersion, FingerprintInputs.FindingClass findingClass,
            byte[] digest, FingerprintInputs inputSnapshot) {
        this.algorithmVersion = algorithmVersion;
        this.findingClass = findingClass;
        this.digest = digest.clone();
        this.inputSnapshot = inputSnapshot;
    }

    /**
     * Computes a fingerprint at the current algorithm version.
     *
     * <p>Package-private so that {@link FingerprintComputation} is the only caller — see that class for why the
     * indirection exists.
     */
    static FindingFingerprint compute(FingerprintInputs inputs) {
        return computeAtVersion(inputs, CURRENT_ALGORITHM_VERSION);
    }

    /**
     * Computes at a named version, for re-fingerprinting a historical finding from its retained inputs.
     *
     * <p>{@code INV-VUL-05} makes re-fingerprinting "a <b>migration</b>, never a recompute-and-replace" — it
     * must preserve triage state, assignment, comments, exceptions and history. This method produces the new
     * digest; it deliberately does not touch a finding, because a method that both recomputed and wrote would be
     * the recompute-and-replace the invariant forbids.
     */
    static FindingFingerprint computeAtVersion(FingerprintInputs inputs, int algorithmVersion) {
        Objects.requireNonNull(inputs, "inputs are required");
        if (algorithmVersion != 1) {
            throw new IllegalArgumentException(
                    "unknown fingerprint algorithm version " + algorithmVersion + ". A verifier or migration "
                            + "must not guess: guessing produces digests that match nothing, which reads as a "
                            + "mass de-duplication failure rather than as a defect.");
        }
        return new FindingFingerprint(algorithmVersion, inputs.findingClass(),
                digestV1(inputs), inputs);
    }

    /**
     * Version 1 of the digest.
     *
     * <p>Three properties, each required by {@code INV-VUL-02} ("deterministic… with no dependence on ordering,
     * locale, or time"):
     *
     * <ul>
     *   <li><b>Declared order, not map order.</b> Inputs are hashed in the order the class declares, so a map
     *       implementation change cannot alter identity.
     *   <li><b>Length-prefixed.</b> The same reason as the audit canonical form: concatenation with a separator
     *       is forgeable, so two different input sets could produce identical bytes and collapse into one
     *       finding.
     *   <li><b>Tenant first, and unconditionally.</b> {@code INV-VUL-01}.
     * </ul>
     */
    private static byte[] digestV1(FingerprintInputs inputs) {
        StringBuilder canonical = new StringBuilder();
        field(canonical, "algorithm_version", "1");
        // INV-VUL-01: in the inputs, not the query filter.
        field(canonical, "tenant", inputs.tenantId().value().toString());
        field(canonical, "finding_class", inputs.findingClass().name());
        // Declared order. Never the map's iteration order, and never sorted — sorting would be stable but
        // would make the declared order in FindingClass decorative, and a reader comparing the two would be
        // misled about which one identity depends on.
        for (String key : inputs.findingClass().declaredInputs()) {
            field(canonical, key, inputs.value(key).orElse(ABSENT_MARKER));
        }
        return newDigest().digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void field(StringBuilder out, String name, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.append(name).append(':').append(bytes.length).append(':').append(value).append(';');
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    ALGORITHM + " is unavailable; finding identity cannot be computed and ingestion must not "
                            + "continue. Proceeding would create findings with no stable identity, which is the "
                            + "unrecoverable outcome of DOC-03 section 10.2.", e);
        }
    }

    public int algorithmVersion() {
        return algorithmVersion;
    }

    public FingerprintInputs.FindingClass findingClass() {
        return findingClass;
    }

    public byte[] digest() {
        return digest.clone();
    }

    /** Retained per {@code INV-VUL-04}, so the algorithm can be improved later. */
    public FingerprintInputs inputSnapshot() {
        return inputSnapshot;
    }

    /**
     * Whether two fingerprints identify the same finding.
     *
     * <p>Requires the same algorithm version, per {@code INV-VUL-03}: "a finding created under one version is
     * not compared against another version's digest". Comparing across versions would silently split or merge
     * en masse depending on which direction the algorithm moved, and neither is detectable from the result.
     *
     * <p>Constant-time on the digest, so the deduplication path does not leak a digest prefix through timing —
     * and a digest prefix is a step towards the cross-tenant probe {@code INV-VUL-01} closes.
     */
    public boolean identifiesSameAs(FindingFingerprint other) {
        Objects.requireNonNull(other, "other is required");
        if (algorithmVersion != other.algorithmVersion) {
            return false;
        }
        return MessageDigest.isEqual(digest, other.digest);
    }

    /** Hex form, for storage and for a diagnostic that must not print raw bytes. */
    public String digestHex() {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
