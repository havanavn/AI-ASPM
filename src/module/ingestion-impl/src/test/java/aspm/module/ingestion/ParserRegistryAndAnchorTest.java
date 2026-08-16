package aspm.module.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.ingestion.domain.AssetAnchorResolution;
import aspm.module.ingestion.domain.AssetClassAssignment;
import aspm.module.ingestion.domain.FingerprintInputs;
import aspm.module.ingestion.domain.ParserDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** DOC-11 sections 4 and 6: PRD-ING-024 to -031, and PRD-ING-040. */
class ParserRegistryAndAnchorTest {

    private static ParserDefinition parser(String code, int version, Set<String> formatVersions) {
        return new ParserDefinition(code, version, "SARIF", formatVersions,
                FingerprintInputs.FindingClass.CODE,
                Map.of("error", 1, "warning", 2, "note", 3),
                AssetAnchorResolution.Strategy.NATURAL_KEY_FROM_COORDINATE,
                AssetClassAssignment.AssetClass.APPLICATION,
                new ParserDefinition.Limits(50_000_000, 100_000, 64, 65_536, Duration.ofMinutes(2)));
    }

    @Nested
    @DisplayName("PRD-ING-027 — an undeclared source version is rejected, never parsed best-effort")
    class VersionSupport {

        @Test
        @DisplayName("acceptance is exact set membership, with no fuzzy fallback")
        void acceptanceIsExact() {
            var p = parser("sarif", 3, Set.of("2.1.0", "2.0.0"));

            assertTrue(p.acceptsFormatVersion("2.1.0"));
            assertFalse(p.acceptsFormatVersion("2.1.1"),
                    "a patch-level fallback is a best-effort parse wearing a different hat");
            assertFalse(p.acceptsFormatVersion("2.1"),
                    "a prefix match is the same thing again");
            assertFalse(p.acceptsFormatVersion("3.0.0"),
                    "and a highest-known-version fallback is the third shape it takes");
        }

        @Test
        @DisplayName("the rejection names the supported versions")
        void rejectionNamesSupportedVersions() {
            var message = parser("sarif", 3, Set.of("2.1.0", "2.0.0")).unsupportedVersionRejection("3.0.0");
            assertTrue(message.contains("2.1.0") && message.contains("2.0.0"),
                    "PRD-ING-027 requires the supported versions NAMED; a rejection that does not is a dead "
                            + "end for whoever has to act on it");
            assertTrue(message.contains("best-effort"),
                    "and the message states why it was not attempted, because 'unsupported' invites a request "
                            + "to try anyway");
        }

        @Test
        @DisplayName("a parser declaring no supported version is not constructible")
        void mustDeclareAtLeastOneVersion() {
            assertThrows(IllegalArgumentException.class, () -> parser("sarif", 1, Set.of()),
                    "an empty list makes the rejection message name no supported versions, which is the "
                            + "unactionable rejection PRD-ING-027 exists to prevent");
        }

        @Test
        @DisplayName("PRD-ING-025: a parser without a version is not constructible")
        void parserVersionIsRequired() {
            assertThrows(IllegalArgumentException.class, () -> parser("sarif", 0, Set.of("2.1.0")),
                    "without it, a systematic mapping error introduced by a parser change cannot be scoped — "
                            + "there is no way to ask which findings that version produced");
        }

        @Test
        @DisplayName("two parsers claiming one format and version is reported")
        void ambiguousRegistryIsReported() {
            var findings = ParserDefinition.validateRegistry(Set.of(
                    parser("sarif-a", 1, Set.of("2.1.0")),
                    parser("sarif-b", 1, Set.of("2.1.0"))));
            assertFalse(findings.isEmpty(),
                    "DOC-09 section 15 resolves a parser per format and version before parsing starts; an "
                            + "ambiguous claim makes which parser ran depend on registry iteration order");
        }
    }

    @Nested
    @DisplayName("PRD-ING-040 — an unmappable severity is a gap, never a middle value")
    class SeverityMapping {

        @Test
        @DisplayName("an unrecognised source severity maps to nothing")
        void unrecognisedSeverityIsEmpty() {
            var p = parser("sarif", 1, Set.of("2.1.0"));
            assertEquals(Optional.of(1), p.mapSeverity("error"));
            assertTrue(p.mapSeverity("SEVERE").isEmpty(),
                    "a defaulted severity is indistinguishable from a reported one and silently corrupts "
                            + "prioritization for every finding from that source (PRD-ING-040)");
        }

        @Test
        @DisplayName("PRD-ING-021: an absent severity is a gap, not an inference")
        void absentSeverityIsAlsoAGap() {
            assertTrue(parser("sarif", 1, Set.of("2.1.0")).mapSeverity(null).isEmpty(),
                    "a field absent in the source is null and the parser does not infer; an absent severity is "
                            + "a gap exactly as an unrecognised one is");
        }
    }

    @Nested
    @DisplayName("DOC-11 section 6 — asset anchor resolution")
    class AnchorResolution {

        private final UUID existing = new UUID(50, 1);
        private final UUID created = new UUID(50, 2);

        /** A lookup that matches at exactly one named step and nothing else. */
        private AssetAnchorResolution.AssetLookup lookupMatchingAt(AssetAnchorResolution.Step step) {
            return new AssetAnchorResolution.AssetLookup() {
                @Override
                public Optional<UUID> byExplicitIdentifier(String identifier) {
                    return step == AssetAnchorResolution.Step.EXPLICIT_IDENTIFIER
                            ? Optional.of(existing) : Optional.empty();
                }

                @Override
                public Optional<UUID> byExternalIdentifier(String sourceSystem, String identifier) {
                    return step == AssetAnchorResolution.Step.EXTERNAL_IDENTIFIER_MATCH
                            ? Optional.of(existing) : Optional.empty();
                }

                @Override
                public Optional<UUID> byNaturalKey(String key) {
                    return step == AssetAnchorResolution.Step.NATURAL_KEY_MATCH
                            ? Optional.of(existing) : Optional.empty();
                }

                @Override
                public Optional<UUID> byAlias(String alias) {
                    return step == AssetAnchorResolution.Step.ALIAS_MATCH
                            ? Optional.of(existing) : Optional.empty();
                }

                @Override
                public UUID createUnclaimed(String key, String sourceSystem) {
                    return created;
                }
            };
        }

        private AssetAnchorResolution.Outcome resolveWith(AssetAnchorResolution.AssetLookup lookup) {
            return AssetAnchorResolution.resolve(lookup, "explicit-1", "scanner-a", "ext-1",
                    "github.com/acme/api", "alias-1", null);
        }

        @Test
        @DisplayName("the five steps are tried in the order DOC-11 section 6 specifies")
        void stepsAreTriedInOrder() {
            for (var step : AssetAnchorResolution.Step.values()) {
                var outcome = resolveWith(lookupMatchingAt(step));
                assertEquals(step, outcome.step(),
                        "resolution reported " + outcome.step() + " where only " + step + " could match");
            }
        }

        @Test
        @DisplayName("PRD-ING-029: an unresolvable anchor creates an unclaimed asset, never discards")
        void unresolvableAnchorCreatesAnUnclaimedAsset() {
            var outcome = resolveWith(lookupMatchingAt(AssetAnchorResolution.Step.CREATED_UNCLAIMED));

            assertEquals(created, outcome.assetId());
            assertTrue(outcome.createdAsset());
            // resolve() is a total function: it never returns empty and never throws for an unresolvable
            // anchor, because "discarding is silent data loss at the point of least detectability".
            assertFalse(outcome.step().matchedExistingAsset());
        }

        @Test
        @DisplayName("PRD-ING-030: creation is distinguishable from a match, per step")
        void creationIsDistinguishableFromMatch() {
            var matched = resolveWith(lookupMatchingAt(AssetAnchorResolution.Step.NATURAL_KEY_MATCH));
            var madeUp = resolveWith(lookupMatchingAt(AssetAnchorResolution.Step.CREATED_UNCLAIMED));

            assertTrue(matched.step().matchedExistingAsset());
            assertFalse(madeUp.step().matchedExistingAsset(),
                    "a session that created 400 assets resolved almost nothing and should be investigated "
                            + "before its findings are trusted; without the distinction it looks identical to "
                            + "one that matched cleanly");
            // And the value that failed to match is retained, so an operator can see WHAT did not match — a
            // template pointing at the wrong column looks like a normalization change without it.
            assertEquals("github.com/acme/api", madeUp.resolvedFrom());
        }

        @Test
        @DisplayName("PRD-ING-031: a source-asserted organizational scope is rejected, not ignored")
        void sourceAssertedScopeIsRejected() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> AssetAnchorResolution.resolve(
                            lookupMatchingAt(AssetAnchorResolution.Step.NATURAL_KEY_MATCH),
                            null, null, null, "github.com/acme/api", null, "business-unit-b"));

            assertTrue(ex.getMessage().contains("PRD-ING-031"));
            assertTrue(ex.getMessage().contains("injection"),
                    "a file-asserted scope is a cross-scope injection primitive requiring only that the file be "
                            + "edited. Rejected rather than silently dropped, because an operator needs to know "
                            + "the file tried.");
        }

        @Test
        @DisplayName("an outcome cannot be constructed without its resolving step or source value")
        void outcomeRequiresItsProvenance() {
            assertThrows(NullPointerException.class,
                    () -> new AssetAnchorResolution.Outcome(existing, null, "x"));
            assertThrows(NullPointerException.class,
                    () -> new AssetAnchorResolution.Outcome(existing,
                            AssetAnchorResolution.Step.NATURAL_KEY_MATCH, null));
        }
    }

    @Nested
    @DisplayName("Declared limits — a parser is a hardened worker over attacker-influenced input")
    class Limits {

        @Test
        @DisplayName("every limit must be positive and declared")
        void limitsMustBeDeclared() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ParserDefinition.Limits(0, 1, 1, 1, Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class,
                    () -> new ParserDefinition.Limits(1, 1, 1, 1, Duration.ZERO),
                    "an unbounded parse is a denial of service on the ingestion tier, and DOC-11 section 2 "
                            + "runs parsers as hardened isolated workers precisely because the input is hostile");
        }
    }
}
