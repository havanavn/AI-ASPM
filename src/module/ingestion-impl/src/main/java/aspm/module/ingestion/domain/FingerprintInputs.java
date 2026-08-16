package aspm.module.ingestion.domain;

import aspm.sharedkernel.TenantId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The values hashed into a finding fingerprint, per DOC-03 section 10.2.
 *
 * <p><b>Retained, and that is {@code INV-VUL-04}.</b> DOC-03 is explicit about what retention buys:
 *
 * <blockquote>Retaining the hashed inputs is what makes the algorithm improvable. Without it, a new version can
 * only be applied to findings created after it, so the platform carries two identity regimes permanently and
 * cross-version deduplication is impossible. With it, a migration can recompute historical digests from
 * retained inputs and merge accordingly. The storage cost is real and is the price of not being locked into
 * the first attempt.</blockquote>
 *
 * <p>The corpus lists this among the twelve unrecoverable invariants. Without retention the first algorithm
 * version is permanent, and DOC-03 section 8.5 makes the same point about identity rules: "the first version is
 * always the least informed".
 *
 * <p><b>What is deliberately absent is as important as what is present.</b> Each finding class declares its
 * inputs and its exclusions, and the exclusions are enforced by {@link FindingClass} rather than left to a
 * caller's discipline — an input added by accident is a rescan that produces new findings, and DOC-03 records
 * that outcome as unrecoverable: "the team stops believing the number — after which no subsequent correctness
 * recovers the deployment".
 */
public final class FingerprintInputs {

    /** The seven classes of DOC-03 section 10.2, each with its declared input set. */
    public enum FindingClass {

        /**
         * Static analysis over code.
         *
         * <p>Excludes line number, absolute path, scanner version and timestamp. "Line numbers shift on every
         * unrelated edit. A fingerprint including them produces a new finding on reformatting, which destroys
         * triage state for a change that altered nothing."
         */
        CODE("rule_identity", "asset_identity", "normalized_code_location", "structural_context_hash"),

        /**
         * A vulnerable dependency.
         *
         * <p>Excludes <b>all file location</b>. "The identity of a vulnerable component does not depend on
         * where its manifest sits. Including manifest path splits one finding across a monorepo."
         */
        DEPENDENCY("vulnerability_identity", "package_url", "affected_version_range", "asset_identity"),

        /**
         * Dynamic analysis against a running system.
         *
         * <p>Excludes concrete parameter values, session data and timestamps. "A payload reflected at
         * {@code /search?q=X} is one finding, not one per value of X."
         */
        RUNTIME("rule_identity", "asset_identity", "normalized_request_path", "parameter_name"),

        INFRASTRUCTURE("check_identity", "asset_identity", "port_or_service_identity"),

        /**
         * A recovered secret.
         *
         * <p>The digest, never the cleartext value: "the digest identifies recurrence without storing the value
         * in the fingerprint".
         */
        SECRET("asset_identity", "secret_type", "normalized_location", "secret_digest"),

        /**
         * A human-recorded weakness.
         *
         * <p>Excludes assessor and assessment identity. "A retest finds the same weakness through a second
         * assessment; keying on assessment would make it a new finding and reset its age."
         */
        MANUAL("assessment_type", "asset_identity", "title_digest", "weakness_classification"),

        CONFIGURATION("check_identity", "asset_identity", "configuration_path");

        /**
         * The declared input keys.
         *
         * <p><b>Suppression justified.</b> {@code ImmutableEnumChecker} cannot see that {@code List.of()}
         * returns an immutable list, and its concern is real in general: a mutable field on an enum constant is
         * shared process-wide, so a mutation anywhere corrupts it everywhere — which for <em>this</em> field
         * would silently change what a fingerprint hashes.
         *
         * <p>Two alternatives were tried and are worse. A {@code String[]} draws the same warning, because
         * arrays are mutable and the check is right about that. Moving the data to a static {@code Map} outside
         * the enum separates each class from its declared inputs, and the whole point of this design is that a
         * class and its inputs are read together — a reader checking whether {@code CODE} excludes line numbers
         * should not have to find a second declaration.
         *
         * <p>{@code List.of()} is immutable, the field is never handed out mutable, and the exemption is
         * annotated so it is greppable and countable in the way {@code SEC-AUZ-051} requires of exemptions
         * generally. Preferred to a contortion that satisfies the analyser and reads worse.
         */
        @SuppressWarnings("ImmutableEnumChecker")
        private final java.util.List<String> declaredInputs;

        FindingClass(String... declaredInputs) {
            this.declaredInputs = java.util.List.of(declaredInputs);
        }

        /**
         * The input keys this class hashes, excluding the tenant.
         *
         * <p>The tenant is not listed because it is not optional for any class and is not supplied by a caller:
         * {@link FingerprintInputs#builder} takes it as a constructor argument, so it cannot be omitted. See
         * {@code INV-VUL-01}.
         */
        public java.util.List<String> declaredInputs() {
            return declaredInputs;
        }
    }

    private final TenantId tenantId;
    private final FindingClass findingClass;
    private final Map<String, String> values;

    private FingerprintInputs(TenantId tenantId, FindingClass findingClass, Map<String, String> values) {
        this.tenantId = tenantId;
        this.findingClass = findingClass;
        this.values = Map.copyOf(values);
    }

    /**
     * Begins building inputs for a class.
     *
     * <p>The tenant is a constructor argument rather than a named input, so {@code INV-VUL-01} cannot be
     * violated by omission. DOC-03: "If fingerprints were global, a tenant could probe for the existence of a
     * specific vulnerability in another tenant's estate by submitting a crafted finding and observing the
     * deduplication response. The isolation must be in the hash inputs, not merely in the query filter."
     */
    public static Builder builder(TenantId tenantId, FindingClass findingClass) {
        return new Builder(
                Objects.requireNonNull(tenantId, "tenantId is required; INV-VUL-01 puts tenant isolation in "
                        + "the hash inputs, not in the query filter"),
                Objects.requireNonNull(findingClass, "findingClass is required"));
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public FindingClass findingClass() {
        return findingClass;
    }

    /** The values hashed, in declared order. Retained per {@code INV-VUL-04}. */
    public Map<String, String> values() {
        return values;
    }

    public Optional<String> value(String key) {
        return Optional.ofNullable(values.get(key));
    }

    /** Builder that rejects an input the class does not declare. */
    public static final class Builder {

        private final TenantId tenantId;
        private final FindingClass findingClass;
        private final Map<String, String> values = new LinkedHashMap<>();

        private Builder(TenantId tenantId, FindingClass findingClass) {
            this.tenantId = tenantId;
            this.findingClass = findingClass;
        }

        /**
         * Sets a declared input.
         *
         * <p><b>Rejects an undeclared key.</b> This is where the exclusions of DOC-03 section 10.2 are
         * enforced: a caller cannot add {@code line_number} to a {@code CODE} fingerprint, or
         * {@code manifest_path} to a {@code DEPENDENCY} one, because the key is not in the class's declared
         * set. The alternative — accepting any key and trusting the caller — makes the exclusions a comment,
         * and a fingerprint that silently gained an input is a rescan that produces new findings.
         *
         * @param value the value, or null where the source did not supply it. {@code PRD-ING-021}: "a field
         *     absent in the source is null; parsers never infer or default"
         */
        public Builder with(String key, String value) {
            Objects.requireNonNull(key, "key is required");
            if (!findingClass.declaredInputs().contains(key)) {
                throw new IllegalArgumentException(
                        "'" + key + "' is not a declared fingerprint input for " + findingClass
                                + ". Declared: " + findingClass.declaredInputs()
                                + ". DOC-03 section 10.2's exclusions are enforced here rather than trusted: "
                                + "an input added by accident makes every rescan produce new findings, which "
                                + "destroys triage state and, per DOC-03, is not recoverable by later "
                                + "correctness.");
            }
            if (value != null) {
                values.put(key, value);
            }
            // A null value is recorded as ABSENT rather than as an empty string. PRD-ING-021 forbids
            // inferring, and an empty string is an inference: it says the source supplied nothing where the
            // source may not have been asked.
            return this;
        }

        /**
         * Builds, requiring every declared input to be present.
         *
         * <p>A missing input is rejected rather than hashed as absent. An input the class declares is part of
         * identity, so a fingerprint computed without one is not a weaker fingerprint — it is a fingerprint
         * for a different identity, and it would collide with every other finding missing the same input.
         */
        public FingerprintInputs build() {
            var missing = findingClass.declaredInputs().stream()
                    .filter(key -> !values.containsKey(key))
                    .toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "fingerprint inputs for " + findingClass + " are incomplete; missing " + missing
                                + ". An input the class declares is part of identity: a fingerprint computed "
                                + "without one would collide with every other finding missing the same input, "
                                + "which is the 'too loose' failure DOC-03 section 10.2 names — distinct issues "
                                + "collapse, fixing one appears to fix all, and closure is wrong.");
            }
            return new FingerprintInputs(tenantId, findingClass, values);
        }
    }
}
