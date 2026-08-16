package aspm.module.compositionanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.compositionanalysis.domain.Ecosystem;
import aspm.module.compositionanalysis.domain.VersionComparison;
import aspm.module.compositionanalysis.domain.VersionOrdering;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The version comparison corpus. {@code TST-SBM-001} and {@code PRD-SBM-041}.
 *
 * <p>DOC-16 section 7.3 states the requirement and the reason in one sentence: the corpus must cover the six
 * difficulty classes per ecosystem, because "these are the cases that fail in production and pass a naive suite,
 * <b>because a naive suite tests versions that differ obviously</b>".
 *
 * <p>So there are no cases here of the form "is 1.0 less than 2.0". Every case below is one where a plausible
 * implementation gets it wrong, and each carries what the wrong answer would produce.
 */
class VersionComparisonCorpusTest {

    private static void ordersBefore(Ecosystem ecosystem, String lower, String higher, String why) {
        var forward = VersionOrdering.compare(ecosystem, lower, higher);
        assertTrue(forward.isPresent(), ecosystem + ": " + lower + " vs " + higher + " was indeterminate. " + why);
        assertTrue(forward.get() < 0, ecosystem + ": expected " + lower + " < " + higher + ". " + why);

        var reverse = VersionOrdering.compare(ecosystem, higher, lower);
        assertTrue(reverse.isPresent() && reverse.get() > 0,
                ecosystem + ": the comparison is not antisymmetric for " + lower + " and " + higher);
    }

    private static void ordersEqual(Ecosystem ecosystem, String left, String right, String why) {
        var result = VersionOrdering.compare(ecosystem, left, right);
        assertTrue(result.isPresent() && result.get() == 0,
                ecosystem + ": expected " + left + " == " + right + ". " + why);
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Class 1 — pre-release ordering")
    class PreRelease {

        @Test
        @DisplayName("semver: a pre-release orders before its release")
        void semverPreReleaseBeforeRelease() {
            ordersBefore(Ecosystem.SEMVER, "1.0.0-alpha", "1.0.0",
                    "getting this backwards reports a fixed release as vulnerable, because a range of "
                            + "'affected below 1.0.0' would then exclude the pre-release that IS affected");
            ordersBefore(Ecosystem.SEMVER, "1.0.0-alpha", "1.0.0-beta", "alphabetic identifiers compare so");
            ordersBefore(Ecosystem.SEMVER, "1.0.0-alpha.1", "1.0.0-alpha.beta",
                    "a numeric identifier orders before an alphanumeric one");
            ordersBefore(Ecosystem.SEMVER, "1.0.0-alpha", "1.0.0-alpha.1",
                    "a longer identifier list orders after a shorter one that is otherwise equal");
        }

        @Test
        @DisplayName("semver: pre-release identifiers compare numerically, not as strings")
        void numericIdentifiersCompareNumerically() {
            ordersBefore(Ecosystem.SEMVER, "1.0.0-alpha.2", "1.0.0-alpha.10",
                    "string comparison puts alpha.10 before alpha.2, which is the single most common "
                            + "pre-release bug");
        }

        @Test
        @DisplayName("PEP 440: dev < alpha < beta < rc < release, and post orders AFTER")
        void pep440SuffixOrdering() {
            ordersBefore(Ecosystem.PYPI, "1.0.dev1", "1.0a1", "dev is the earliest suffix");
            ordersBefore(Ecosystem.PYPI, "1.0a1", "1.0b1", null);
            ordersBefore(Ecosystem.PYPI, "1.0b1", "1.0rc1", null);
            ordersBefore(Ecosystem.PYPI, "1.0rc1", "1.0", "a release candidate precedes its release");
            ordersBefore(Ecosystem.PYPI, "1.0", "1.0.post1",
                    "a POST-release orders AFTER the release — the opposite direction from every "
                            + "pre-release, and where a naive implementation reports a patched package as "
                            + "vulnerable");
        }

        @Test
        @DisplayName("Maven: an unknown qualifier orders AFTER the release, unlike semver")
        void mavenUnknownQualifierOrdersAfter() {
            ordersBefore(Ecosystem.MAVEN, "1.0-alpha", "1.0", "known pre-release qualifiers precede");
            ordersBefore(Ecosystem.MAVEN, "1.0-rc1", "1.0", null);
            ordersBefore(Ecosystem.MAVEN, "1.0", "1.0-sp1",
                    "a service pack orders after the release, the same trap PEP 440's post sets");
            ordersBefore(Ecosystem.MAVEN, "1.0", "1.0-customqualifier",
                    "Maven orders an UNKNOWN qualifier after the release; semver orders an unknown "
                            + "pre-release identifier before it. Same-looking input, opposite answer, "
                            + "different ecosystem — PRD-SBM-038's whole point.");
        }

        @Test
        @DisplayName("Debian: the tilde sorts before everything, including the empty string")
        void debianTildeSortsFirst() {
            ordersBefore(Ecosystem.DEB, "1.0~rc1", "1.0",
                    "the tilde is how distributions package pre-releases; omitting the rule inverts every "
                            + "pre-release comparison in the two ecosystems where most operating-system "
                            + "components live");
            ordersBefore(Ecosystem.DEB, "1.0~beta", "1.0~rc1", null);
            ordersBefore(Ecosystem.RPM, "1.0~rc1", "1.0", "the same rule applies to RPM");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Class 2 — epoch and revision components")
    class EpochAndRevision {

        @Test
        @DisplayName("Debian: the epoch dominates the upstream version entirely")
        void debianEpochDominates() {
            ordersBefore(Ecosystem.DEB, "2.0", "1:1.0",
                    "epoch ignored INVERTS the entire comparison (DOC-22 section 6.3) — and it does so "
                            + "completely, for every package that has ever needed one");
            ordersBefore(Ecosystem.DEB, "1:1.0", "2:0.1", "a higher epoch wins regardless of upstream");
        }

        @Test
        @DisplayName("PEP 440: the epoch dominates too")
        void pep440EpochDominates() {
            ordersBefore(Ecosystem.PYPI, "2.0", "1!1.0", null);
        }

        @Test
        @DisplayName("Debian: the revision orders after the upstream version compares equal")
        void debianRevisionIsTheTiebreak() {
            ordersBefore(Ecosystem.DEB, "1.2.3-1", "1.2.3-2",
                    "the revision is where a backported security fix lives, so ordering it wrongly hides "
                            + "whether the fix is installed");
            ordersBefore(Ecosystem.DEB, "1.2.3-1", "1.2.3-1+deb12u2", null);
            ordersBefore(Ecosystem.RPM, "1.2.3-1.el9", "1.2.3-2.el9",
                    "RPM's release segment carries the backport in the same way");
        }

        @Test
        @DisplayName("Debian: leading zeros in a numeric run are insignificant")
        void debianLeadingZeros() {
            ordersEqual(Ecosystem.DEB, "1.007", "1.7",
                    "treating them as distinct would split one package version into two");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Class 3 — non-semantic schemes yield INDETERMINATE, not a guess")
    class NonSemantic {

        @Test
        @DisplayName("an unknown ecosystem does not fall back to a default comparator")
        void unknownEcosystemIsIndeterminate() {
            assertTrue(VersionOrdering.compare(Ecosystem.UNKNOWN, "1.0", "2.0").isEmpty(),
                    "a default comparator here would be the 'single comparison scheme across ecosystems' "
                            + "PRD-SBM-038 forbids, wearing a fallback's clothing");
            assertFalse(Ecosystem.UNKNOWN.orderable());
        }

        @Test
        @DisplayName("a version that does not parse under its scheme is indeterminate, not zero")
        void unparseableIsIndeterminate() {
            assertTrue(VersionOrdering.compare(Ecosystem.SEMVER, "1.x.0", "1.2.0").isEmpty(),
                    "applying semantic rules to a non-semantic scheme produces arbitrary orderings; saying "
                            + "so is PRD-SBM-039");
            assertTrue(VersionOrdering.compare(Ecosystem.PYPI, "2026-W03", "1.0").isEmpty(),
                    "a date-based scheme is not PEP 440");
        }

        @Test
        @DisplayName("a trailing separator does not silently become a shorter version")
        void trailingSeparatorIsNotDropped() {
            assertTrue(VersionOrdering.compare(Ecosystem.SEMVER, "1.0.", "1.0.0").isEmpty(),
                    "String.split drops trailing empty segments, so '1.0.' would compare EQUAL to '1.0' — a "
                            + "malformed version silently becoming a well-formed one, inside a comparator "
                            + "whose output is a match verdict");
        }

        @Test
        @DisplayName("an unrecognised package-URL type resolves to UNKNOWN rather than throwing")
        void unknownPurlTypeIsNotAnError() {
            assertEquals(Ecosystem.UNKNOWN, Ecosystem.fromPurlType("some-new-registry"),
                    "silent skipping is the mechanism by which a partially matched SBOM appears fully "
                            + "matched (PRD-SBM-037); an exception here would stop the whole snapshot");
            assertEquals(Ecosystem.UNKNOWN, Ecosystem.fromPurlType(null));
            assertEquals(Ecosystem.SEMVER, Ecosystem.fromPurlType("  NPM  "));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Class 4 — backported fixes, the most consequential")
    class BackportedFixes {

        private static final List<VersionComparison.AffectedRange> AFFECTED_BELOW_2 =
                List.of(VersionComparison.AffectedRange.below("2.0.0", false));

        @Test
        @DisplayName("PRD-SBM-040: distribution patch metadata overrides an upstream range match")
        void patchMetadataOverridesRange() {
            var patch = new VersionComparison.DistributionPatch(Ecosystem.DEB, "1.2.3-1+deb12u2",
                    "DSA-5555-1");
            var result = VersionComparison.evaluate(Ecosystem.DEB, "1.2.3-1+deb12u2", AFFECTED_BELOW_2,
                    Optional.of(patch));

            assertEquals(VersionComparison.Outcome.PATCHED_BY_DISTRIBUTION, result.outcome(),
                    "the upstream 1.2.3 IS inside 'affected below 2.0.0'. Reporting a patched package as "
                            + "vulnerable, repeatedly, is how a team learns to ignore the module's output");
            assertFalse(result.affected());
            assertTrue(result.explanation().contains("DSA-5555-1"),
                    "the suppression must name its source, or it is unauditable");
        }

        @Test
        @DisplayName("a version BELOW the distribution's fix is still affected")
        void belowTheFixIsStillAffected() {
            var patch = new VersionComparison.DistributionPatch(Ecosystem.DEB, "1.2.3-1+deb12u2",
                    "DSA-5555-1");
            var result = VersionComparison.evaluate(Ecosystem.DEB, "1.2.3-1", AFFECTED_BELOW_2,
                    Optional.of(patch));

            assertEquals(VersionComparison.Outcome.AFFECTED, result.outcome(),
                    "the precedence must not suppress everything in the package — only versions at or above "
                            + "the fix");
        }

        @Test
        @DisplayName("PATCHED_BY_DISTRIBUTION is distinct from NOT_AFFECTED")
        void patchedIsNotTheSameAsUnaffected() {
            var patch = new VersionComparison.DistributionPatch(Ecosystem.RPM, "1.2.3-2.el9", "RHSA-2026:001");
            var patched = VersionComparison.evaluate(Ecosystem.RPM, "1.2.3-2.el9", AFFECTED_BELOW_2,
                    Optional.of(patch));
            var unaffected = VersionComparison.evaluate(Ecosystem.RPM, "3.0.0", AFFECTED_BELOW_2,
                    Optional.empty());

            assertEquals(VersionComparison.Outcome.PATCHED_BY_DISTRIBUTION, patched.outcome());
            assertEquals(VersionComparison.Outcome.NOT_AFFECTED, unaffected.outcome(),
                    "a reader auditing why a known-vulnerable version produced no finding needs to see the "
                            + "difference, and a reviewer checking whether the patch claim is trustworthy "
                            + "needs to find these cases");
        }

        @Test
        @DisplayName("patch metadata is only accepted for ecosystems that ship backports")
        void patchMetadataIsEcosystemBound() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> new VersionComparison.DistributionPatch(Ecosystem.SEMVER, "1.2.3", "someone said so"));
            assertTrue(ex.getMessage().contains("not how fixes are shipped"),
                    "accepting one would let an arbitrary claim suppress a match");
        }

        @Test
        @DisplayName("a patch claim without a source reference cannot be constructed")
        void patchClaimNeedsASource() {
            assertThrows(NullPointerException.class,
                    () -> new VersionComparison.DistributionPatch(Ecosystem.DEB, "1.2.3-1", null),
                    "this metadata OVERRIDES the range comparison, so an unattributed claim is a suppression "
                            + "nobody can audit");
        }

        @Test
        @DisplayName("patch metadata that cannot be ordered yields INDETERMINATE, not suppression")
        void unorderablePatchDoesNotSuppress() {
            var patch = new VersionComparison.DistributionPatch(Ecosystem.DEB, "not-a-version:", "DSA-1");
            var result = VersionComparison.evaluate(Ecosystem.DEB, "1.2.3-1", AFFECTED_BELOW_2,
                    Optional.of(patch));
            assertEquals(VersionComparison.Outcome.INDETERMINATE, result.outcome(),
                    "failing open here would let malformed patch metadata suppress real findings");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Class 5 — range boundary inclusivity")
    class BoundaryInclusivity {

        @Test
        @DisplayName("an exclusive upper bound excludes exactly the boundary version")
        void exclusiveUpperExcludesTheBoundary() {
            var exclusive = List.of(VersionComparison.AffectedRange.below("2.0.0", false));
            assertEquals(VersionComparison.Outcome.NOT_AFFECTED,
                    VersionComparison.evaluate(Ecosystem.SEMVER, "2.0.0", exclusive, Optional.empty())
                            .outcome(),
                    "off-by-one at exactly the version most deployments are on: the upper bound of an "
                            + "affected range is usually the last vulnerable release, and the last vulnerable "
                            + "release is what everybody is running the day the advisory lands");
            assertEquals(VersionComparison.Outcome.AFFECTED,
                    VersionComparison.evaluate(Ecosystem.SEMVER, "1.9.9", exclusive, Optional.empty())
                            .outcome());
        }

        @Test
        @DisplayName("an inclusive upper bound includes it")
        void inclusiveUpperIncludesTheBoundary() {
            var inclusive = List.of(VersionComparison.AffectedRange.below("2.0.0", true));
            assertEquals(VersionComparison.Outcome.AFFECTED,
                    VersionComparison.evaluate(Ecosystem.SEMVER, "2.0.0", inclusive, Optional.empty())
                            .outcome());
        }

        @Test
        @DisplayName("both lower bound forms are honoured")
        void lowerBoundInclusivity() {
            var inclusiveLower = List.of(
                    VersionComparison.AffectedRange.between("1.0.0", true, "2.0.0", false));
            var exclusiveLower = List.of(
                    VersionComparison.AffectedRange.between("1.0.0", false, "2.0.0", false));

            assertEquals(VersionComparison.Outcome.AFFECTED,
                    VersionComparison.evaluate(Ecosystem.SEMVER, "1.0.0", inclusiveLower, Optional.empty())
                            .outcome());
            assertEquals(VersionComparison.Outcome.NOT_AFFECTED,
                    VersionComparison.evaluate(Ecosystem.SEMVER, "1.0.0", exclusiveLower, Optional.empty())
                            .outcome());
        }

        @Test
        @DisplayName("a range unbounded at both ends is refused as a data error")
        void unboundedBothEndsRefused() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> new VersionComparison.AffectedRange(Optional.empty(), Optional.empty()));
            assertTrue(ex.getMessage().contains("entire estate"),
                    "it affects every version ever released, including ones predating the software");
        }

        @Test
        @DisplayName("an unbounded-above range matches everything at or above its lower bound")
        void unboundedAboveMatches() {
            var unfixed = List.of(VersionComparison.AffectedRange.atOrAbove("1.5.0"));
            assertEquals(VersionComparison.Outcome.AFFECTED,
                    VersionComparison.evaluate(Ecosystem.SEMVER, "9.9.9", unfixed, Optional.empty())
                            .outcome(),
                    "an unfixed vulnerability has no upper bound, and modelling one as a sentinel version "
                            + "would make it order against real versions");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Class 6 — multiple disjoint affected ranges")
    class DisjointRanges {

        private static final List<VersionComparison.AffectedRange> TWO_RANGES = List.of(
                VersionComparison.AffectedRange.between("1.0.0", true, "1.4.0", false),
                VersionComparison.AffectedRange.between("2.0.0", true, "2.3.0", false));

        @Test
        @DisplayName("a version in the SECOND range is affected")
        void secondRangeMatches() {
            assertEquals(VersionComparison.Outcome.AFFECTED,
                    VersionComparison.evaluate(Ecosystem.SEMVER, "2.1.0", TWO_RANGES, Optional.empty())
                            .outcome(),
                    "matching only the first misses the second entirely (DOC-22 section 6.3)");
        }

        @Test
        @DisplayName("a version between the two ranges is not affected")
        void gapBetweenRangesIsClean() {
            assertEquals(VersionComparison.Outcome.NOT_AFFECTED,
                    VersionComparison.evaluate(Ecosystem.SEMVER, "1.5.0", TWO_RANGES, Optional.empty())
                            .outcome(),
                    "1.5.0 sits in the fixed gap; reporting it affected would be the false positive that "
                            + "makes teams disbelieve the range data");
        }

        @Test
        @DisplayName("a definite match in one range overrules an indeterminate result in another")
        void definiteMatchOverrulesIndeterminate() {
            var mixed = List.of(
                    VersionComparison.AffectedRange.between("1.0.0", true, "1.4.0", false),
                    // Unparseable bound: this range alone would be indeterminate.
                    VersionComparison.AffectedRange.below("not-a-version", false));
            var result = VersionComparison.evaluate(Ecosystem.SEMVER, "1.2.0", mixed, Optional.empty());

            assertEquals(VersionComparison.Outcome.AFFECTED, result.outcome(),
                    "AFFECTED is a stronger and more useful answer than INDETERMINATE, and the definite "
                            + "match is not lost by the loop breaking early on the unparseable one");
        }

        @Test
        @DisplayName("an indeterminate range with no definite match yields INDETERMINATE, not clean")
        void indeterminateSurvivesWhenNothingMatches() {
            var mixed = List.of(
                    VersionComparison.AffectedRange.between("1.0.0", true, "1.4.0", false),
                    VersionComparison.AffectedRange.below("not-a-version", false));
            var result = VersionComparison.evaluate(Ecosystem.SEMVER, "9.0.0", mixed, Optional.empty());

            assertEquals(VersionComparison.Outcome.INDETERMINATE, result.outcome(),
                    "assuming clean would be the silent false negative PRD-SBM-039 exists to prevent");
            assertTrue(result.requiresTriage());
            assertFalse(result.affected(),
                    "INDETERMINATE is not affected AND not clean; a caller collapsing it either way has "
                            + "reintroduced one of the two failures");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Corpus completeness and comparator properties")
    class CorpusProperties {

        @Test
        @DisplayName("TST-SBM-001: every orderable ecosystem has ordering implemented")
        void everyOrderableEcosystemOrders() {
            for (Ecosystem ecosystem : Ecosystem.values()) {
                var result = VersionOrdering.compare(ecosystem, "1.0.0", "2.0.0");
                if (ecosystem.orderable()) {
                    assertTrue(result.isPresent() && result.get() < 0,
                            ecosystem + " is declared orderable but cannot order two ordinary versions. A "
                                    + "declared-but-unimplemented ecosystem produces silent INDETERMINATE for "
                                    + "every component in it.");
                } else {
                    assertTrue(result.isEmpty(), ecosystem + " is not orderable and must say so");
                }
            }
        }

        @Test
        @DisplayName("the corpus covers every ecosystem that ships distribution patches")
        void patchEcosystemsAreCovered() {
            Set<Ecosystem> withPatches = EnumSet.noneOf(Ecosystem.class);
            for (Ecosystem ecosystem : Ecosystem.values()) {
                if (ecosystem.carriesDistributionPatchMetadata()) {
                    withPatches.add(ecosystem);
                }
            }
            assertEquals(EnumSet.of(Ecosystem.DEB, Ecosystem.RPM), withPatches,
                    "a new ecosystem shipping backports needs its own corpus cases before it is declared, or "
                            + "PRD-SBM-040 applies to it untested");
        }

        @Test
        @DisplayName("comparison is reflexive and the ordering rules version is recorded")
        void reflexiveAndVersioned() {
            for (Ecosystem ecosystem : Ecosystem.values()) {
                if (!ecosystem.orderable()) {
                    continue;
                }
                var self = VersionOrdering.compare(ecosystem, "1.2.3", "1.2.3");
                assertTrue(self.isPresent() && self.get() == 0,
                        ecosystem + " does not compare a version equal to itself, which would make a range "
                                + "boundary match or not match depending on which side it was evaluated from");
            }
            assertTrue(VersionOrdering.RULES_VERSION >= 1,
                    "PRD-SBM-036: without a recorded version, a change in results cannot be distinguished "
                            + "from a change in the estate");
        }

        @Test
        @DisplayName("semver ignores build metadata for ordering")
        void buildMetadataIsNotOrdered() {
            ordersEqual(Ecosystem.SEMVER, "1.0.0+build.1", "1.0.0+build.2",
                    "treating build metadata as ordered would make two builds of the same version straddle "
                            + "a range boundary");
        }

        @Test
        @DisplayName("Go's leading v is not part of the version")
        void goPrefixStripped() {
            ordersEqual(Ecosystem.GOLANG, "v1.2.3", "1.2.3", null);
            ordersBefore(Ecosystem.GOLANG, "v1.2.3", "v1.2.4", null);
        }

        @Test
        @DisplayName("Maven pads a missing segment too, and a large segment does not overflow")
        void mavenPaddingAndLargeSegments() {
            ordersEqual(Ecosystem.MAVEN, "1.0", "1.0.0",
                    "Maven treats a trailing zero segment as absent; ordering them differently would move a "
                            + "version across a range boundary depending on how the SBOM spelled it");
            ordersBefore(Ecosystem.MAVEN, "1.0", "1.0.1", null);
            ordersBefore(Ecosystem.SEMVER, "1.0.0", "1.0.99999999999999999999",
                    "version segments are compared as arbitrary-precision integers; int parsing would "
                            + "overflow and invert the comparison on a date-derived segment");
        }

        @Test
        @DisplayName("a missing segment is zero, not a lesser version")
        void missingSegmentIsZero() {
            ordersEqual(Ecosystem.SEMVER, "1.0", "1.0.0",
                    "a version with fewer segments is not a lesser version; treating it as one would put "
                            + "1.0 below 1.0.0 and change which side of a boundary it falls");
        }

        @Test
        @DisplayName("no affected range means not affected, not indeterminate")
        void noRangesMeansClean() {
            var result = VersionComparison.evaluate(Ecosystem.SEMVER, "1.0.0", List.of(), Optional.empty());
            assertEquals(VersionComparison.Outcome.NOT_AFFECTED, result.outcome(),
                    "a vulnerability declaring no range for this ecosystem does not affect it; "
                            + "INDETERMINATE here would fill the triage queue with nothing");
        }
    }
}
