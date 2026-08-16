package aspm.deployment;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The ten delivery pipeline gates of DOC-15 §9.1. {@code OPS-DEP-026}.
 *
 * <p>"Every gate MUST block rather than warn, and a bypass MUST require a recorded, reviewed exception naming
 * the gate and the reason."
 *
 * <p>Its rationale is six words long and settles the design: "<b>A warning is a violation with extra steps.</b>"
 * The rest of it explains why these gates in particular: "The static analysis and corpus validation gates exist
 * because the failures they catch are invisible to review (DOC-06's twenty-seven unregistered requirements were
 * found by the register, not by a reviewer)."
 *
 * <p>That is this repository's own history. The register found twenty-seven requirements no reviewer had noticed
 * were missing, a real requirement identifier used in an illustrative example, and an unregistered class code.
 * Three defects, none found by reading.
 *
 * <h2>Blocking is not a field</h2>
 *
 * <p>There is no {@code blocking} flag and no {@code severity}. A gate either exists in this enum or does not,
 * and every one that exists blocks. A flag is the mechanism by which a gate becomes advisory during a release
 * crunch and stays advisory afterwards, and the change reads as a configuration tweak in the diff.
 *
 * <p>A bypass is possible — {@code OPS-DEP-026} requires it to be — but only through {@link Exemption}, which
 * cannot be constructed without the gate, a reason, an approver distinct from the requester, and an expiry.
 */
public enum PipelineGate {

    /**
     * Module boundary violation ({@code CON-PLT-013}); role-identifier comparison ({@code SEC-AUZ-050}); domain
     * layer purity ({@code CON-PLT-017}).
     */
    STATIC_ANALYSIS,

    /** Any failure; the {@code MUST_HAVE} coverage gate of {@code PRD-PLT-011}. */
    TEST_SUITE,

    /** Register regeneration and validator failure ({@code PRD-PLT-012}). */
    CORPUS_VALIDATION,

    /** Known-vulnerable above threshold; licence policy ({@code SEC-SEC-058}). */
    DEPENDENCY_POLICY,

    /** Any detected secret in source or history. */
    SECRET_SCANNING,

    /** Known-vulnerable above threshold in the image. */
    CONTAINER_SCAN,

    /** Unsigned artifact or absent attestation ({@code SEC-SEC-056}). */
    SIGNING_AND_PROVENANCE,

    /**
     * Migration not expand-migrate-contract; blocking operation on a large table ({@code CON-DAT-033}).
     */
    MIGRATION_VALIDATION,

    /** WCAG automated failure ({@code INT-UIX-001}). */
    ACCESSIBILITY,

    /** Harness threshold failure where AI capabilities changed ({@code PRD-AIC-049}). */
    AI_EVALUATION;

    /** Every gate blocks. Present as a method so a caller asking the question gets the same answer. */
    public boolean blocks() {
        return true;
    }

    /**
     * A recorded, reviewed exception. The only bypass {@code OPS-DEP-026} permits.
     *
     * @param requestedBy who wants the gate skipped
     * @param approvedBy who reviewed it. Distinct from the requester, because a self-approved exception is the
     *     bypass with a form attached
     * @param reason why. Free text, because the reason is read by a person and no enumeration would survive
     *     contact with a real release
     * @param expiresAfterBuilds how many builds it survives. An exception without one is a permanently disabled
     *     gate that nobody remembers disabling
     */
    public record Exemption(PipelineGate gate, String requestedBy, String approvedBy, String reason,
            int expiresAfterBuilds) {

        public Exemption {
            Objects.requireNonNull(gate, "an exemption names the gate it bypasses (OPS-DEP-026)");
            Objects.requireNonNull(requestedBy, "a requester is required");
            Objects.requireNonNull(approvedBy, "an approver is required");
            Objects.requireNonNull(reason, "a reason is required (OPS-DEP-026)");
            if (reason.isBlank() || reason.length() < 20) {
                throw new IllegalArgumentException(
                        "an exemption reason of fewer than twenty characters is 'urgent' or 'known issue', "
                                + "which tells the next reader nothing about whether the exception still "
                                + "applies (OPS-DEP-026).");
            }
            if (requestedBy.equals(approvedBy)) {
                throw new IllegalArgumentException(
                        "the requester approved their own gate bypass. A self-approved exception is the bypass "
                                + "with a form attached, and the form is what makes it look reviewed.");
            }
            if (expiresAfterBuilds <= 0 || expiresAfterBuilds > 10) {
                throw new IllegalArgumentException(
                        "an exemption expires within ten builds. Without an expiry it is a permanently "
                                + "disabled gate that nobody remembers disabling, and the ten is low because "
                                + "the situation that justified it is a release, not a quarter.");
            }
        }
    }

    /**
     * Whether the pipeline proceeds.
     *
     * @param exemption an exemption for this gate, if one was granted
     * @return empty where the pipeline proceeds; the blocking reason otherwise
     */
    public Optional<String> evaluate(boolean passed, Optional<Exemption> exemption) {
        Objects.requireNonNull(exemption, "pass an empty optional rather than null");
        if (passed) {
            return Optional.empty();
        }
        if (exemption.isPresent()) {
            if (exemption.orElseThrow().gate() != this) {
                return Optional.of(name() + " failed and the exemption presented names "
                        + exemption.orElseThrow().gate() + ". An exemption is per gate: one granted for a "
                        + "container scan does not cover a secret scan (OPS-DEP-026).");
            }
            return Optional.empty();
        }
        return Optional.of(name() + " failed and blocks the pipeline. A warning is a violation with extra "
                + "steps (OPS-DEP-026).");
    }

    /** The ten, in the order DOC-15 §9.1 lists them. */
    public static List<PipelineGate> all() {
        return List.of(values());
    }
}
