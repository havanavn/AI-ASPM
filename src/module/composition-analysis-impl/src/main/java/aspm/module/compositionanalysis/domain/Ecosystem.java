package aspm.module.compositionanalysis.domain;

/**
 * A package ecosystem. {@code PRD-SBM-038}: "Version comparison MUST use the ordering rules of the component's
 * ecosystem, and the platform MUST NOT apply a single comparison scheme across ecosystems."
 *
 * <p>The rationale is worth quoting because it is the reason this enumeration exists rather than a
 * {@code String}: "Ecosystem version schemes are genuinely incompatible. A uniform comparator is wrong for most
 * of them, and wrong in the pre-release and epoch cases that occur constantly."
 *
 * <p><b>Product-fixed, not tenant data.</b> An ecosystem is a set of ordering rules the platform implements;
 * a tenant cannot invent one, because inventing one would mean inventing a comparator. {@link #UNKNOWN} is the
 * honest destination for anything the platform does not implement, and it produces
 * {@link VersionComparison.Outcome#INDETERMINATE} rather than a guess.
 */
public enum Ecosystem {

    /** Semantic versioning with pre-release and build metadata. */
    SEMVER("npm", true),

    /** Python. Epoch, release segments, and a rich pre/post/dev suffix grammar. */
    PYPI("pypi", true),

    /** Java. Qualifier ordering is positional and unlike semver's. */
    MAVEN("maven", true),

    /** Debian and derivatives. Epoch, upstream version, and a Debian revision. */
    DEB("deb", true),

    /** Red Hat and derivatives. Epoch, version, release — and release is where backports live. */
    RPM("rpm", true),

    /** Go modules. Semver with a required leading 'v' and pseudo-versions. */
    GOLANG("golang", true),

    /**
     * Anything the platform does not implement ordering for.
     *
     * <p>Not a fallback comparator — a refusal to compare. {@code PRD-SBM-039} requires
     * {@code INDETERMINATE} rather than an assertion either way, because "guessing produces either a false
     * positive that wastes remediation effort or a false negative that hides a vulnerability".
     */
    UNKNOWN("unknown", false);

    private final String purlType;
    private final boolean orderable;

    Ecosystem(String purlType, boolean orderable) {
        this.purlType = purlType;
        this.orderable = orderable;
    }

    /** The package-URL type this corresponds to. */
    public String purlType() {
        return purlType;
    }

    /** Whether the platform can order versions in this ecosystem. */
    public boolean orderable() {
        return orderable;
    }

    /**
     * Whether the ecosystem carries distribution-level patch metadata.
     *
     * <p>{@code PRD-SBM-040}: where it is available it takes precedence over upstream range comparison, because
     * "backported fixes are the largest single source of false positives in dependency matching". The two
     * distribution ecosystems are where a maintainer patches an old version without changing the upstream
     * number.
     */
    public boolean carriesDistributionPatchMetadata() {
        return this == DEB || this == RPM;
    }

    /** Resolves from a package-URL type, returning {@link #UNKNOWN} rather than throwing. */
    public static Ecosystem fromPurlType(String type) {
        if (type == null) {
            return UNKNOWN;
        }
        String normalized = type.strip().toLowerCase(java.util.Locale.ROOT);
        for (Ecosystem ecosystem : values()) {
            if (ecosystem.purlType.equals(normalized)) {
                return ecosystem;
            }
        }
        // Deliberately not throwing. An unrecognised ecosystem is a component the platform records as
        // unmatchable (PRD-SBM-037), not an error that stops a snapshot being processed — "silent skipping is
        // the mechanism by which a partially matched SBOM appears fully matched".
        return UNKNOWN;
    }
}
