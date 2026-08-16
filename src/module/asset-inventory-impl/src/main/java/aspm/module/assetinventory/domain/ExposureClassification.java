package aspm.module.assetinventory.domain;

import aspm.sharedkernel.PrincipalId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Declared and observed network exposure, per DOC-03 section 8.2.
 *
 * <p><b>{@code INV-AST-08} is the invariant where the intuitive implementation is the wrong one.</b> DOC-03
 * section 8.2 spells it out:
 *
 * <blockquote>Auto-correcting a declaration to match observation is the intuitive behaviour and is wrong. An
 * asset declared internal but observed on a public domain is itself a high-severity finding — someone
 * exposed a system that was not intended to be exposed. Silently updating the declaration erases the
 * discrepancy, and with it the finding. Worse, every risk score computed from the declaration during the
 * discrepancy window was wrong, and correcting the declaration without recording the conflict destroys the
 * ability to detect that.</blockquote>
 *
 * <p>So this type has <b>no operation that writes the declared value from an observation</b>. Observing is
 * {@link #observe}, which sets the observed fields and derives {@link #conflict()}; changing the declaration
 * is {@link #declare}, which requires a principal and a timestamp because it is an accountable act. There is
 * no third method, and the absence is the invariant.
 */
public final class ExposureClassification {

    /**
     * Exposure levels with an <b>explicit</b> rank, lower meaning more exposed.
     *
     * <p>The rank is a declared field rather than {@link Enum#ordinal()}. Error Prone flagged the ordinal
     * version and it was right for a reason specific to this type: the comparison derives
     * {@link #conflict()}, which decides whether an asset enters the exposure conflict queue and which value
     * reaches scoring. Depending on declaration order means inserting a level in the middle, or sorting the
     * constants alphabetically in a tidy-up, silently inverts a security-relevant comparison — and nothing
     * would fail, because the enum still compiles and every test that names two levels still passes.
     */
    public enum Level {
        INTERNET_PUBLIC(0),
        PARTNER_B2B(1),
        INTERNAL_ONLY(2),
        AIR_GAPPED(3);

        private final int exposureRank;

        Level(int exposureRank) {
            this.exposureRank = exposureRank;
        }

        /** Lower is more exposed. */
        public int exposureRank() {
            return exposureRank;
        }

        /** True where this level is more exposed than {@code other}. */
        public boolean moreExposedThan(Level other) {
            return this.exposureRank < other.exposureRank;
        }
    }

    private final Level declared;
    private final PrincipalId declaredBy;
    private final Instant declaredAt;
    private final Level observed;
    private final String observedSource;
    private final Instant observedAt;

    private ExposureClassification(Level declared, PrincipalId declaredBy, Instant declaredAt,
            Level observed, String observedSource, Instant observedAt) {
        this.declared = declared;
        this.declaredBy = declaredBy;
        this.declaredAt = declaredAt;
        this.observed = observed;
        this.observedSource = observedSource;
        this.observedAt = observedAt;
    }

    /** The initial declaration. An asset's exposure is always declared before it can be observed. */
    public static ExposureClassification declare(Level declared, PrincipalId by, Instant at) {
        return new ExposureClassification(
                Objects.requireNonNull(declared, "a declared level is required"),
                Objects.requireNonNull(by, "the declaring principal is required; a declaration is an "
                        + "accountable act and an unattributed one cannot be questioned"),
                Objects.requireNonNull(at, "the declaration instant is required"),
                null, null, null);
    }

    /**
     * Records an observation. Returns a new classification; the declaration is carried over unchanged.
     *
     * <p>This is the method that must not correct the declaration, and it does not: {@code declared},
     * {@code declaredBy} and {@code declaredAt} are copied verbatim.
     */
    public ExposureClassification observe(Level observedLevel, String source, Instant at) {
        Objects.requireNonNull(observedLevel, "an observed level is required");
        Objects.requireNonNull(source, "the observing source is required; an unattributed observation "
                + "cannot be re-checked and cannot be weighed against a conflicting one");
        Objects.requireNonNull(at, "the observation instant is required");
        return new ExposureClassification(declared, declaredBy, declaredAt, observedLevel, source, at);
    }

    /**
     * Re-declares exposure. An accountable act, never derived from an observation.
     *
     * <p>A caller resolving a conflict calls this <em>deliberately</em>, having decided the declaration was
     * wrong. The observation is retained, so the conflict's existence remains visible in history even after
     * the declaration catches up.
     */
    public ExposureClassification redeclare(Level newDeclared, PrincipalId by, Instant at) {
        Objects.requireNonNull(newDeclared, "a declared level is required");
        Objects.requireNonNull(by, "the declaring principal is required");
        Objects.requireNonNull(at, "the declaration instant is required");
        return new ExposureClassification(newDeclared, by, at, observed, observedSource, observedAt);
    }

    public Level declared() {
        return declared;
    }

    public PrincipalId declaredBy() {
        return declaredBy;
    }

    public Instant declaredAt() {
        return declaredAt;
    }

    public Optional<Level> observed() {
        return Optional.ofNullable(observed);
    }

    public Optional<String> observedSource() {
        return Optional.ofNullable(observedSource);
    }

    public Optional<Instant> observedAt() {
        return Optional.ofNullable(observedAt);
    }

    /**
     * {@code INV-AST-08}: true where the observation is <b>more exposed</b> than the declaration.
     *
     * <p>Derived, never stored as an independently settable field — a stored flag can disagree with the
     * values it summarises, and the disagreement would put an asset in or out of the conflict queue for
     * reasons no query could explain.
     *
     * <p>Deliberately asymmetric. An asset declared public but observed internal is <em>not</em> a conflict:
     * it is over-declaration, which is conservative and produces a risk score that is too high rather than
     * too low. Only under-declaration understates risk, and only understated risk is a finding.
     */
    public boolean conflict() {
        return observed != null && observed.moreExposedThan(declared);
    }

    /**
     * The exposure a risk score should use.
     *
     * <p>The <b>more exposed</b> of the two while a conflict stands. The declaration is not corrected
     * ({@code INV-AST-08}), but scoring must not use a value the platform has evidence is wrong — that would
     * be knowingly understating risk, which product principle 1 forbids in the same breath as it forbids
     * treating not-measured as clean.
     */
    public Level effectiveForScoring() {
        return conflict() ? observed : declared;
    }
}
