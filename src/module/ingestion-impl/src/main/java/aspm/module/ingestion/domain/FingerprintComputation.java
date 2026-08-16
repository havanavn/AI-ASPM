package aspm.module.ingestion.domain;

import aspm.sharedkernel.TenantId;
import java.util.Objects;

/**
 * The single entry point for computing a finding fingerprint. {@code INV-ING-01}, {@code INV-VUL-06}, ADR-011.
 *
 * <p>DOC-16 section 4.2 calls {@code INV-ING-01} "the one unrecoverable invariant with <b>no database
 * enforcement</b>" — there is no column constraint that can express "only this module wrote this value". The
 * enforcement is therefore three things in combination, and none alone is sufficient:
 *
 * <ol>
 *   <li><b>Location.</b> This class is in {@code ingestion-impl}. No module declares a dependency on another
 *       module's {@code -impl}, so it is not on any other module's compile classpath (ADR-050).
 *   <li><b>A build-time assertion</b> in {@code :architecture-tests} that no class outside
 *       {@code aspm.module.ingestion} references the fingerprint types.
 *   <li><b>The contract surface.</b> {@code ingestion-contract} publishes the <em>result</em> — a digest and a
 *       version — never the means to compute one. A downstream module can read a fingerprint and cannot make one.
 * </ol>
 *
 * <p>ADR-011 requires one normalization and deduplication pipeline shared by file import and native matching, and
 * {@code INV-VUL-06} requires the fingerprint to be "identical regardless of source path". Both hold for the same
 * reason: there is one implementation, so there is nothing to diverge.
 */
public final class FingerprintComputation {

    private FingerprintComputation() {
        throw new AssertionError("not instantiable");
    }

    /**
     * Computes the fingerprint for a set of inputs at the current algorithm version.
     *
     * <p>There is deliberately no overload accepting a pre-built digest. A method taking one would let a parser
     * supply identity, and a parser-supplied identity is the <em>source tool's</em> identity rather than the
     * platform's — which is how one tool renaming a rule becomes a mass duplication event across the estate.
     */
    public static FindingFingerprint of(FingerprintInputs inputs) {
        return FindingFingerprint.compute(Objects.requireNonNull(inputs, "inputs are required"));
    }

    /**
     * Recomputes a historical finding's digest from its retained inputs, at a named version.
     *
     * <p>{@code INV-VUL-04} makes this possible; {@code INV-VUL-05} constrains what may be done with the result:
     * re-fingerprinting "preserves triage state, assignment, comments, exceptions, and history. It is a
     * <b>migration</b>, never a recompute-and-replace."
     *
     * <p>This returns a fingerprint and touches nothing. The migration consuming it must merge rather than
     * overwrite — DOC-04 section 23.2 calls re-fingerprinting "the most complex data migration the platform will
     * perform", and a method that both recomputed and wrote would be the recompute-and-replace the invariant
     * forbids.
     */
    public static FindingFingerprint reFingerprint(FingerprintInputs retainedInputs, int algorithmVersion) {
        return FindingFingerprint.computeAtVersion(
                Objects.requireNonNull(retainedInputs, "retained inputs are required"), algorithmVersion);
    }

    /**
     * A code finding.
     *
     * <p>The parameters are the declared inputs and nothing else. There is no line-number parameter, and adding
     * one would require adding it to {@link FingerprintInputs.FindingClass#CODE}'s declared set — a visible
     * change to a class whose exclusions are documented with their reasons.
     */
    public static FindingFingerprint forCode(TenantId tenant, String ruleIdentity, String assetIdentity,
            String normalizedCodeLocation, String structuralContextHash) {
        return of(FingerprintInputs.builder(tenant, FingerprintInputs.FindingClass.CODE)
                .with("rule_identity", ruleIdentity)
                .with("asset_identity", assetIdentity)
                .with("normalized_code_location", normalizedCodeLocation)
                .with("structural_context_hash", structuralContextHash)
                .build());
    }

    /** A dependency finding. Note the absence of any location parameter — that is {@code INV-VUL-02}'s point. */
    public static FindingFingerprint forDependency(TenantId tenant, String vulnerabilityIdentity,
            String packageUrl, String affectedVersionRange, String assetIdentity) {
        return of(FingerprintInputs.builder(tenant, FingerprintInputs.FindingClass.DEPENDENCY)
                .with("vulnerability_identity", vulnerabilityIdentity)
                .with("package_url", packageUrl)
                .with("affected_version_range", affectedVersionRange)
                .with("asset_identity", assetIdentity)
                .build());
    }

    /** A runtime finding. Note the parameter NAME, never its value. */
    public static FindingFingerprint forRuntime(TenantId tenant, String ruleIdentity, String assetIdentity,
            String normalizedRequestPath, String parameterName) {
        return of(FingerprintInputs.builder(tenant, FingerprintInputs.FindingClass.RUNTIME)
                .with("rule_identity", ruleIdentity)
                .with("asset_identity", assetIdentity)
                .with("normalized_request_path", normalizedRequestPath)
                .with("parameter_name", parameterName)
                .build());
    }

    /** A secret finding. The digest, never the cleartext value. */
    public static FindingFingerprint forSecret(TenantId tenant, String assetIdentity, String secretType,
            String normalizedLocation, String secretDigest) {
        return of(FingerprintInputs.builder(tenant, FingerprintInputs.FindingClass.SECRET)
                .with("asset_identity", assetIdentity)
                .with("secret_type", secretType)
                .with("normalized_location", normalizedLocation)
                .with("secret_digest", secretDigest)
                .build());
    }

    /**
     * A manual finding.
     *
     * <p>No assessment parameter, and that is deliberate: "a retest finds the same weakness through a second
     * assessment; keying on assessment would make it a new finding and reset its age."
     */
    public static FindingFingerprint forManual(TenantId tenant, String assessmentType, String assetIdentity,
            String titleDigest, String weaknessClassification) {
        return of(FingerprintInputs.builder(tenant, FingerprintInputs.FindingClass.MANUAL)
                .with("assessment_type", assessmentType)
                .with("asset_identity", assetIdentity)
                .with("title_digest", titleDigest)
                .with("weakness_classification", weaknessClassification)
                .build());
    }
}
