package aspm.module.compositionanalysis.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Is a component's version within a vulnerability's affected ranges? DOC-22 section 6.3.
 *
 * <p>This is where the last three difficulty classes live: <b>boundary inclusivity</b>, <b>disjoint ranges</b>,
 * and <b>backported fixes</b>. {@link VersionOrdering} answers "which of these two versions is larger"; this
 * class answers "is this one affected", and the two questions fail differently.
 *
 * <h2>{@code PRD-SBM-040} — distribution patch metadata takes precedence, and it is checked FIRST</h2>
 *
 * <p>"Backported fixes are the largest single source of false positives in dependency matching. Reporting a
 * patched package as vulnerable, repeatedly, is how a team learns to ignore the module's output."
 *
 * <p>The order matters. Checking the ranges first and the patch metadata second would produce the same answer
 * and cost a range evaluation, but more importantly it invites a future change that returns early on a range
 * match — at which point the precedence silently stops applying. Checking it first makes the precedence
 * structural.
 */
public final class VersionComparison {

    /** The result. {@code INDETERMINATE} is a first-class answer, not an error. */
    public enum Outcome {
        /** The version falls in an affected range and no patch metadata excludes it. */
        AFFECTED,
        /** The version is outside every affected range. */
        NOT_AFFECTED,
        /**
         * Ordering could not be determined. {@code PRD-SBM-039}.
         *
         * <p>"Guessing produces either a false positive that wastes remediation effort or a false negative that
         * hides a vulnerability. An explicit indeterminate state is honest and actionable — it can be triaged."
         *
         * <p>It is <b>not</b> a synonym for not-affected. A caller treating it as one has reintroduced the false
         * negative, which is why {@code Result.affected()} returns false for both and {@code Result.requiresTriage()}
         * exists to separate them.
         */
        INDETERMINATE,
        /**
         * Excluded by distribution patch metadata ({@code PRD-SBM-040}).
         *
         * <p>Distinguished from {@code NOT_AFFECTED} deliberately: the component <i>is</i> in the upstream
         * affected range and the distribution has patched it. A reader auditing why a known-vulnerable version
         * produced no finding needs to see that, and a reviewer checking whether the patch claim is trustworthy
         * needs to find these cases.
         */
        PATCHED_BY_DISTRIBUTION
    }

    /** A range endpoint. Inclusivity is explicit because DOC-22 names the off-by-one as its own class. */
    public record Bound(String version, boolean inclusive) {

        public Bound {
            Objects.requireNonNull(version, "a bound version is required");
        }
    }

    /**
     * One affected range.
     *
     * <p><b>Both bounds are optional</b> — "affected below 2.0" has no lower bound, and an unfixed vulnerability
     * has no upper one. Modelling an absent bound as an empty string or a sentinel version would make it order
     * against real versions, which is how "affected below 2.0" becomes "affected between '' and 2.0" and then
     * excludes nothing or everything depending on the comparator.
     */
    public record AffectedRange(Optional<Bound> lower, Optional<Bound> upper) {

        public AffectedRange {
            Objects.requireNonNull(lower, "lower is required, empty where unbounded below");
            Objects.requireNonNull(upper, "upper is required, empty where unbounded above");
            if (lower.isEmpty() && upper.isEmpty()) {
                throw new IllegalArgumentException(
                        "a range unbounded at both ends affects every version ever released, including ones "
                                + "predating the software. That is always a data error in the intelligence "
                                + "feed, and accepting it would mark the entire estate vulnerable.");
            }
        }

        public static AffectedRange below(String version, boolean inclusive) {
            return new AffectedRange(Optional.empty(), Optional.of(new Bound(version, inclusive)));
        }

        public static AffectedRange between(String from, boolean fromInclusive, String to,
                boolean toInclusive) {
            return new AffectedRange(Optional.of(new Bound(from, fromInclusive)),
                    Optional.of(new Bound(to, toInclusive)));
        }

        public static AffectedRange atOrAbove(String version) {
            return new AffectedRange(Optional.of(new Bound(version, true)), Optional.empty());
        }
    }

    /**
     * A distribution's statement that it has patched a component at a given version.
     *
     * @param fixedInVersion the distribution version carrying the fix — {@code 1.2.3-1+deb12u2}, where the
     *     upstream {@code 1.2.3} is still inside the vulnerability's affected range
     * @param sourceReference where the claim came from. Required: {@code PRD-SBM-040} makes this metadata
     *     override the range comparison, so an unattributed claim is an unauditable suppression
     */
    public record DistributionPatch(Ecosystem ecosystem, String fixedInVersion, String sourceReference) {

        public DistributionPatch {
            Objects.requireNonNull(ecosystem, "an ecosystem is required");
            Objects.requireNonNull(fixedInVersion, "the fixed-in version is required");
            Objects.requireNonNull(sourceReference,
                    "a source reference is required (PRD-SBM-040). This metadata OVERRIDES the range "
                            + "comparison, so an unattributed claim is a suppression nobody can audit — and "
                            + "the whole reason it is trusted is that a distribution maintainer published it.");
            if (!ecosystem.carriesDistributionPatchMetadata()) {
                throw new IllegalArgumentException(
                        ecosystem + " does not carry distribution patch metadata. Accepting one here would let "
                                + "an arbitrary claim suppress a match in an ecosystem where backporting is "
                                + "not how fixes are shipped.");
            }
        }
    }

    /** The answer, with enough detail to explain itself. */
    public record Result(Outcome outcome, Optional<AffectedRange> matchedRange,
            Optional<DistributionPatch> appliedPatch, String explanation) {

        public boolean affected() {
            return outcome == Outcome.AFFECTED;
        }

        /**
         * Whether a human must look at this.
         *
         * <p>True only for {@code INDETERMINATE}. It is the state that "can be triaged", and a queue of them is
         * the module telling you honestly what it could not decide.
         */
        public boolean requiresTriage() {
            return outcome == Outcome.INDETERMINATE;
        }
    }

    private VersionComparison() {
    }

    /**
     * Evaluates a component version against a vulnerability's affected ranges.
     *
     * @param ranges may be several and <b>disjoint</b>. Every one is evaluated: "a vulnerability affecting two
     *     non-contiguous ranges" where "matching only the first misses the second entirely" is DOC-22's sixth
     *     difficulty class
     * @param distributionPatch present where the distribution has published a backported fix
     */
    public static Result evaluate(Ecosystem ecosystem, String componentVersion,
            List<AffectedRange> ranges, Optional<DistributionPatch> distributionPatch) {
        Objects.requireNonNull(ecosystem, "an ecosystem is required");
        Objects.requireNonNull(componentVersion, "a component version is required");
        Objects.requireNonNull(ranges, "affected ranges are required");
        Objects.requireNonNull(distributionPatch, "distributionPatch is required, empty where none");

        if (ranges.isEmpty()) {
            return new Result(Outcome.NOT_AFFECTED, Optional.empty(), Optional.empty(),
                    "the vulnerability declares no affected range for this ecosystem");
        }

        // PRD-SBM-040, FIRST. See the class comment for why the order is structural rather than incidental.
        if (distributionPatch.isPresent()) {
            DistributionPatch patch = distributionPatch.get();
            Optional<Integer> againstFix = VersionOrdering.compare(ecosystem, componentVersion,
                    patch.fixedInVersion());
            if (againstFix.isEmpty()) {
                return new Result(Outcome.INDETERMINATE, Optional.empty(), distributionPatch,
                        "distribution patch metadata is present but the installed version cannot be ordered "
                                + "against the fixed version under " + ecosystem + " rules (PRD-SBM-039)");
            }
            if (againstFix.get() >= 0) {
                return new Result(Outcome.PATCHED_BY_DISTRIBUTION, Optional.empty(), distributionPatch,
                        componentVersion + " is at or above the distribution's fixed version "
                                + patch.fixedInVersion() + " (" + patch.sourceReference() + "). Distribution "
                                + "patch metadata takes precedence over upstream range comparison "
                                + "(PRD-SBM-040): backported fixes are the largest single source of false "
                                + "positives, and reporting a patched package as vulnerable is how a team "
                                + "learns to ignore this module.");
            }
        }

        boolean anyIndeterminate = false;
        for (AffectedRange range : ranges) {
            InRange verdict = within(ecosystem, componentVersion, range);
            if (verdict == InRange.YES) {
                return new Result(Outcome.AFFECTED, Optional.of(range), distributionPatch,
                        componentVersion + " falls within " + describe(range));
            }
            if (verdict == InRange.UNKNOWN) {
                // Not returned immediately. A later range may match definitively, and AFFECTED is a stronger
                // and more useful answer than INDETERMINATE — but only a definite match may overrule it, which
                // is why the flag is carried rather than the loop broken.
                anyIndeterminate = true;
            }
        }

        if (anyIndeterminate) {
            return new Result(Outcome.INDETERMINATE, Optional.empty(), distributionPatch,
                    componentVersion + " could not be ordered against at least one affected range under "
                            + ecosystem + " rules. Recorded as indeterminate rather than assumed clean "
                            + "(PRD-SBM-039) — this is triageable, whereas a wrong 'not affected' is silent.");
        }
        return new Result(Outcome.NOT_AFFECTED, Optional.empty(), distributionPatch,
                componentVersion + " is outside every declared affected range");
    }

    private enum InRange {
        YES,
        NO,
        UNKNOWN
    }

    /**
     * Range membership, with <b>explicit</b> boundary handling.
     *
     * <p>DOC-22: "off-by-one at exactly the version most deployments are on". That is not hyperbole — an
     * affected range's upper bound is usually the last vulnerable release, and the last vulnerable release is
     * the one everybody is running the day the advisory lands.
     */
    private static InRange within(Ecosystem ecosystem, String version, AffectedRange range) {
        if (range.lower().isPresent()) {
            Bound lower = range.lower().get();
            Optional<Integer> c = VersionOrdering.compare(ecosystem, version, lower.version());
            if (c.isEmpty()) {
                return InRange.UNKNOWN;
            }
            boolean satisfied = lower.inclusive() ? c.get() >= 0 : c.get() > 0;
            if (!satisfied) {
                return InRange.NO;
            }
        }
        if (range.upper().isPresent()) {
            Bound upper = range.upper().get();
            Optional<Integer> c = VersionOrdering.compare(ecosystem, version, upper.version());
            if (c.isEmpty()) {
                return InRange.UNKNOWN;
            }
            boolean satisfied = upper.inclusive() ? c.get() <= 0 : c.get() < 0;
            if (!satisfied) {
                return InRange.NO;
            }
        }
        return InRange.YES;
    }

    private static String describe(AffectedRange range) {
        String lower = range.lower()
                .map(b -> (b.inclusive() ? ">= " : "> ") + b.version())
                .orElse("(unbounded below)");
        String upper = range.upper()
                .map(b -> (b.inclusive() ? "<= " : "< ") + b.version())
                .orElse("(unbounded above)");
        return lower + " and " + upper;
    }
}
