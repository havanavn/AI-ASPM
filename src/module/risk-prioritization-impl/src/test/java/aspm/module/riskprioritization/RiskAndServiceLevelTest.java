package aspm.module.riskprioritization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.riskprioritization.domain.BandThresholds;
import aspm.module.riskprioritization.domain.ClosureFigure;
import aspm.module.riskprioritization.domain.CoverageQualifier;
import aspm.module.riskprioritization.domain.Factor;
import aspm.module.riskprioritization.domain.NodePosture;
import aspm.module.riskprioritization.domain.PostureImprovementAttribution;
import aspm.module.riskprioritization.domain.RiskScore;
import aspm.module.riskprioritization.domain.ScoreBand;
import aspm.module.riskprioritization.domain.ScoreChangeAttribution;
import aspm.module.riskprioritization.domain.ScoreReducingAction;
import aspm.module.riskprioritization.domain.ScoreReducingRateAnomaly;
import aspm.module.riskprioritization.domain.ServiceLevelClock;
import aspm.module.riskprioritization.domain.WeightSet;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * DOC-28 in full, and the service level clock of DOC-09 section 9.
 *
 * <p>The two counter-intuitive behaviours of {@code PRD-RSK-035} and {@code PRD-RSK-036} have their own nested
 * class, named so that a future reader who thinks they have found a bug reads the reason first.
 */
class RiskAndServiceLevelTest {

    private static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");
    private static final UUID IMPACT = new UUID(80, 1);
    private static final UUID NODE = new UUID(80, 3);

    /** A high-everything finding: severe, exploited, on a critical internet-facing regulated asset. */
    private static List<RiskScore.FactorInput> worstCase() {
        return List.of(
                RiskScore.FactorInput.measured(Factor.SEV, "1.00", "severity:CRITICAL", T0),
                RiskScore.FactorInput.measured(Factor.EXP, "0.95", "epss-rank:0.99", T0),
                RiskScore.FactorInput.measured(Factor.KEV, "1.00", "kev:CVE-2026-0001", T0),
                RiskScore.FactorInput.measured(Factor.EXPO, "1.00", "observed:INTERNET_PUBLIC", T0),
                RiskScore.FactorInput.measured(Factor.CRIT, "1.00", "tier:1", T0),
                RiskScore.FactorInput.measured(Factor.DATA, "1.00", "classification:REGULATED", T0),
                new RiskScore.FactorInput(Factor.REACH, BigDecimal.ZERO, "reserved",
                        T0, RiskScore.Fallback.RESERVED_FACTOR));
    }

    /** A moderate finding with two documented fallbacks. */
    private static List<RiskScore.FactorInput> moderate() {
        return List.of(
                RiskScore.FactorInput.measured(Factor.SEV, "0.50", "severity:MEDIUM", T0),
                new RiskScore.FactorInput(Factor.EXP, new BigDecimal("0.50"), "no-intelligence", T0,
                        RiskScore.Fallback.NEUTRAL_MIDPOINT),
                new RiskScore.FactorInput(Factor.KEV, BigDecimal.ZERO, "kev:absent", T0,
                        RiskScore.Fallback.ABSENT_FROM_CATALOGUE),
                RiskScore.FactorInput.measured(Factor.EXPO, "0.35", "declared:INTERNAL_ONLY", T0),
                RiskScore.FactorInput.measured(Factor.CRIT, "0.40", "tier:3", T0),
                new RiskScore.FactorInput(Factor.DATA, new BigDecimal("0.20"), "classification:none-recorded",
                        T0, RiskScore.Fallback.DOCUMENTED_FLOOR),
                new RiskScore.FactorInput(Factor.REACH, BigDecimal.ZERO, "reserved", T0,
                        RiskScore.Fallback.RESERVED_FACTOR));
    }

    private static CoverageQualifier fullCoverage() {
        return new CoverageQualifier(100, 95, 0, 2, 1, true);
    }

    private static RiskScore score(List<RiskScore.FactorInput> inputs, CoverageQualifier coverage) {
        return RiskScore.compute(UUID.randomUUID(), RiskScore.SubjectKind.FINDING_IMPACT, IMPACT, 1, T0,
                inputs, WeightSet.defaults(), BandThresholds.defaults(), coverage, 1L);
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-28 sections 4.2 and 5 — the factor set")
    class FactorSet {

        @Test
        @DisplayName("the factor set is exactly the seven codes of DOC-28 section 4.2")
        void factorSetMatchesTheDocument() {
            // PP-10, and the schema's ck_smfw__factor_code CHECK holds the same list. A factor added in code
            // and not in the schema produces a weight the formula reads and the engine rejects; the reverse
            // produces a weight nothing reads.
            assertEquals(List.of("SEV", "EXP", "KEV", "EXPO", "CRIT", "DATA", "REACH"),
                    java.util.Arrays.stream(Factor.values()).map(Enum::name).toList(),
                    "DOC-28 section 4.2 fixes both the set and — because Factor declaration order is the "
                            + "evaluation order — the order");
        }

        @Test
        @DisplayName("the default weights are DOC-28 section 5.7's, and total 1.10 rather than 1")
        void defaultWeightsTotalOnePointOne() {
            assertEquals(new BigDecimal("1.10"), WeightSet.defaults().total(),
                    "DOC-28 section 5.7: 'Weights sum to 1.10 by design; the formula normalizes.' An earlier "
                            + "version of WeightSet required a sum of exactly one, which would have rejected "
                            + "the document's own defaults.");
            assertEquals(new BigDecimal("0.30"), Factor.SEV.defaultWeight());
            assertEquals(new BigDecimal("0.00"), Factor.REACH.defaultWeight(),
                    "REACH is reserved at weight zero (DF-03), so enabling reachability later is a weight "
                            + "change plus an input, not a model restructuring");
        }

        @Test
        @DisplayName("REACH is technical, so reserving it does not enter the contextual multiplier")
        void reservedFactorIsNotContextual() {
            assertFalse(Factor.REACH.contextual());
            assertEquals(List.of(Factor.EXPO, Factor.CRIT, Factor.DATA),
                    java.util.Arrays.stream(Factor.values()).filter(Factor::contextual).toList(),
                    "context = max(EXPO, CRIT, DATA) per DOC-28 section 6.1; a fourth member would change "
                            + "every score in the platform");
        }

        @Test
        @DisplayName("PRD-RSK-020: an out-of-bounds weight is rejected naming the factor and the bound")
        void weightBoundsAreEnforcedWithADiagnosis() {
            Map<Factor, BigDecimal> zeroingIntelligence = new EnumMap<>(WeightSet.defaults().asMap());
            zeroingIntelligence.put(Factor.EXP, BigDecimal.ZERO);
            zeroingIntelligence.put(Factor.KEV, BigDecimal.ZERO);

            var ex = assertThrows(IllegalArgumentException.class, () -> WeightSet.of(zeroingIntelligence));
            assertTrue(ex.getMessage().contains("EXP") && ex.getMessage().contains("KEV"),
                    "the diagnosis must name the factors: 'setting EXP and KEV to zero converts it back into "
                            + "the severity sorting that produced the four thousand findings' (PRD-RSK-020)");
            assertTrue(ex.getMessage().contains("0.05"), "and the bound it broke");
        }

        @Test
        @DisplayName("a factor omitted from a weight configuration is rejected, not defaulted")
        void omittedWeightIsRejected() {
            Map<Factor, BigDecimal> missingData = new EnumMap<>(WeightSet.defaults().asMap());
            missingData.remove(Factor.DATA);
            var ex = assertThrows(IllegalArgumentException.class, () -> WeightSet.of(missingData));
            assertTrue(ex.getMessage().contains("DATA"),
                    "defaulting silently would apply a weight the tenant never reviewed to every score in "
                            + "the tenancy");
        }

        @Test
        @DisplayName("PRD-RSK-018: a factor input needs an explicit fallback classification")
        void fallbackMustBeClassified() {
            assertThrows(NullPointerException.class,
                    () -> new RiskScore.FactorInput(Factor.KEV, BigDecimal.ZERO, "kev:absent", T0, null),
                    "silent zero substitution means missing data lowers the score, so absent intelligence "
                            + "looks like absent risk — PP-1 violated inside the formula");
            assertTrue(score(moderate(), fullCoverage()).anyFallbackApplied(),
                    "and where a fallback was used the score says so");
            assertFalse(score(worstCase(), fullCoverage()).inputs().stream()
                    .filter(i -> i.factor() != Factor.REACH)
                    .anyMatch(i -> i.fallback() != RiskScore.Fallback.NONE));
        }

        @Test
        @DisplayName("PRD-RSK-017: a factor outside [0,1] is rejected")
        void factorsNormalizeToUnitInterval() {
            assertThrows(IllegalArgumentException.class,
                    () -> RiskScore.FactorInput.measured(Factor.SEV, "1.5", "severity:?", T0),
                    "mixing raw scales makes weights uninterpretable and the explanation unusable");
        }

        @Test
        @DisplayName("every factor needs an input; an omission is the silent zero PRD-RSK-018 forbids")
        void everyFactorNeedsAnInput() {
            List<RiskScore.FactorInput> missingKev = new ArrayList<>(worstCase());
            missingKev.removeIf(i -> i.factor() == Factor.KEV);
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> score(missingKev, fullCoverage()));
            assertTrue(ex.getMessage().contains("KEV"));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-28 section 6 — the formula shape")
    class Formula {

        @Test
        @DisplayName("PRD-RSK-016: score = 100 x normalize(raw) x (0.4 + 0.6 x max(EXPO,CRIT,DATA))")
        void formulaMatchesTheDocument() {
            var s = score(worstCase(), fullCoverage());
            // raw = 0.30(1.00) + 0.20(0.95) + 0.20(1.00) + 0.15(1.00) + 0.15(1.00) + 0.10(1.00) + 0
            //     = 0.30 + 0.19 + 0.20 + 0.15 + 0.15 + 0.10 = 1.09
            assertEquals(0, s.raw().compareTo(new BigDecimal("1.09")), "raw was " + s.raw());
            // context = max(1.00, 1.00, 1.00) = 1.00, so the multiplier is 0.4 + 0.6 = 1.00
            assertEquals(0, s.contextMultiplier().compareTo(BigDecimal.ONE));
            // 100 x (1.09 / 1.10) x 1.00 = 99.09 -> 99
            assertEquals(99, s.valueForPrioritisationOnly(), "the worst case scores 99, not 100: EXP was 0.95");
            assertEquals(ScoreBand.CRITICAL, s.band());
        }

        @Test
        @DisplayName("a factor at zero does not zero the score — a sum, not a product")
        void aZeroFactorDoesNotZeroTheScore() {
            var s = score(moderate(), fullCoverage());
            assertTrue(s.valueForPrioritisationOnly() > 0,
                    "KEV is 0 and REACH is 0. 'With six factors, several of which are legitimately zero, a "
                            + "product produces zero for most findings and no ordering at all' (DOC-28 6.2)");
        }

        @Test
        @DisplayName("the contextual multiplier floors at 0.4, so a no-context asset stays visible")
        void contextualMultiplierFloorsAtPointFour() {
            List<RiskScore.FactorInput> criticalOnNothingImportant = List.of(
                    RiskScore.FactorInput.measured(Factor.SEV, "1.00", "severity:CRITICAL", T0),
                    RiskScore.FactorInput.measured(Factor.EXP, "1.00", "epss-rank:1.0", T0),
                    RiskScore.FactorInput.measured(Factor.KEV, "1.00", "kev:listed", T0),
                    RiskScore.FactorInput.measured(Factor.EXPO, "0.00", "AIR_GAPPED-normalized-to-zero", T0),
                    RiskScore.FactorInput.measured(Factor.CRIT, "0.00", "lowest-tier", T0),
                    RiskScore.FactorInput.measured(Factor.DATA, "0.00", "confirmed-none", T0),
                    new RiskScore.FactorInput(Factor.REACH, BigDecimal.ZERO, "reserved", T0,
                            RiskScore.Fallback.RESERVED_FACTOR));
            var s = score(criticalOnNothingImportant, fullCoverage());

            assertEquals(0, s.contextMultiplier().compareTo(new BigDecimal("0.4")));
            assertTrue(s.valueForPrioritisationOnly() > 0,
                    "a zero floor 'makes a low-context asset score zero regardless of the finding, which "
                            + "hides genuine technical problems on assets nobody classified' (DOC-28 6.2)");
            // 100 x (0.70/1.10) x 0.4 = 25.45 -> 25
            assertEquals(25, s.valueForPrioritisationOnly(), "still deprioritized, still visible");
        }

        @Test
        @DisplayName("the contextual factors take max, not sum, so being high on all three does not triple-count")
        void contextualFactorsTakeMax() {
            List<RiskScore.FactorInput> oneHighContext = new ArrayList<>(moderate());
            oneHighContext.replaceAll(i -> i.factor() == Factor.EXPO
                    ? RiskScore.FactorInput.measured(Factor.EXPO, "1.00", "observed:INTERNET_PUBLIC", T0) : i);
            List<RiskScore.FactorInput> threeHighContext = new ArrayList<>(oneHighContext);
            threeHighContext.replaceAll(i -> switch (i.factor()) {
                case CRIT -> RiskScore.FactorInput.measured(Factor.CRIT, "1.00", "tier:1", T0);
                case DATA -> RiskScore.FactorInput.measured(Factor.DATA, "1.00", "REGULATED", T0);
                default -> i;
            });

            var one = score(oneHighContext, fullCoverage());
            var three = score(threeHighContext, fullCoverage());
            assertEquals(0, one.contextMultiplier().compareTo(three.contextMultiplier()),
                    "exposure, criticality and data sensitivity are substantially correlated — an "
                            + "internet-facing payment service is high on all three — so summing them "
                            + "triple-counts one underlying property (DOC-28 6.2)");
            assertTrue(three.valueForPrioritisationOnly() > one.valueForPrioritisationOnly(),
                    "the weighted sum still rises; it is only the MULTIPLIER that saturates");
        }

        @Test
        @DisplayName("PRD-RSK-019: bands are derived from configurable thresholds within bounds")
        void bandsComeFromThresholds() {
            assertEquals(ScoreBand.CRITICAL, BandThresholds.defaults().bandOf(90));
            assertEquals(ScoreBand.HIGH, BandThresholds.defaults().bandOf(89));
            assertEquals(ScoreBand.INFORMATIONAL, BandThresholds.defaults().bandOf(14));
            assertThrows(IllegalArgumentException.class, () -> new BandThresholds(50, 60, 40, 15),
                    "non-descending thresholds put a value in two bands, and the first match wins by "
                            + "accident of ordering");
            assertFalse(ScoreBand.INFORMATIONAL.carriesCommitmentByDefault(),
                    "'a deadline nobody intends to meet devalues every other deadline' (DOC-28 11.2)");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-RSK-023, -024 — reproducible and immutable")
    class Reproducibility {

        @Test
        @DisplayName("recomputing from retained inputs yields an identical value")
        void recomputationIsIdentical() {
            var first = score(worstCase(), fullCoverage());
            var again = first.recomputeWith(UUID.randomUUID(), 1, T0.plusSeconds(86_400),
                    WeightSet.defaults(), BandThresholds.defaults());

            assertEquals(first.valueForPrioritisationOnly(), again.valueForPrioritisationOnly(),
                    "PRD-RSK-023 requires recomputation without access to data that has since changed — "
                            + "nothing is re-read, so a re-tiered asset cannot change the answer");
            assertEquals(first.raw(), again.raw());
            assertNotEquals(first.id(), again.id(), "PRD-RSK-024: a recomputation is a NEW score");
        }

        @Test
        @DisplayName("the evaluation order is fixed, so argument order cannot change the value")
        void evaluationOrderIsFixed() {
            var forward = score(worstCase(), fullCoverage());
            List<RiskScore.FactorInput> reversed = new ArrayList<>(worstCase());
            Collections.reverse(reversed);
            var backward = score(reversed, fullCoverage());

            assertEquals(forward.raw(), backward.raw(),
                    "BigDecimal addition is associative only because the order never varies; a value that "
                            + "depended on how the caller assembled its arguments would not be reproducible");
            assertEquals(List.of(Factor.values()),
                    forward.contributions().stream().map(RiskScore.Contribution::factor).toList(),
                    "and the retained contributions are in the same fixed order");
        }

        @Test
        @DisplayName("a duplicate factor input is rejected rather than resolved by iteration order")
        void duplicateInputRejected() {
            List<RiskScore.FactorInput> doubled = new ArrayList<>(worstCase());
            doubled.add(RiskScore.FactorInput.measured(Factor.SEV, "0.10", "severity:INFO", T0));
            var ex = assertThrows(IllegalArgumentException.class, () -> score(doubled, fullCoverage()));
            assertTrue(ex.getMessage().contains("SEV"));
        }

        @Test
        @DisplayName("PRD-RSK-024: a score is immutable; there is no setter and no recompute-in-place")
        void scoresAreImmutable() {
            for (var m : RiskScore.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("set") || name.equals("recompute"),
                        "found " + m.getName() + ". An in-place update destroys the prior value and with it "
                                + "the ability to answer what changed.");
            }
        }

        @Test
        @DisplayName("every factor input carries its source and its own freshness")
        void factorsCarryProvenance() {
            assertThrows(NullPointerException.class,
                    () -> new RiskScore.FactorInput(Factor.SEV, BigDecimal.ONE, null, T0,
                            RiskScore.Fallback.NONE),
                    "the score must carry what it read rather than a pointer to something that can move");
            assertThrows(NullPointerException.class,
                    () -> new RiskScore.FactorInput(Factor.SEV, BigDecimal.ONE, "s", null,
                            RiskScore.Fallback.NONE),
                    "INV-VUL-18 requires staleness visible wherever a value is used, and 'wherever' includes "
                            + "a score explanation two years later");
        }

        @Test
        @DisplayName("the subject is a finding-ASSET impact, which is what defeats finding splitting")
        void subjectIsAnImpactNotAFinding() {
            assertEquals(RiskScore.SubjectKind.FINDING_IMPACT, score(worstCase(), fullCoverage()).subjectKind(),
                    "DOC-28 13.2: the score is per finding-asset pair, so splitting a finding increases the "
                            + "count without lowering the maximum");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-RSK-025 — change attribution")
    class Attribution {

        private RiskScore withKevRaised() {
            List<RiskScore.FactorInput> nowExploited = new ArrayList<>(moderate());
            nowExploited.replaceAll(i -> i.factor() == Factor.KEV
                    ? RiskScore.FactorInput.measured(Factor.KEV, "1.00", "kev:CVE-2026-0002",
                            T0.plusSeconds(3600))
                    : i);
            return RiskScore.compute(UUID.randomUUID(), RiskScore.SubjectKind.FINDING_IMPACT, IMPACT, 1,
                    T0.plusSeconds(3600), nowExploited, WeightSet.defaults(), BandThresholds.defaults(),
                    fullCoverage(), 1L);
        }

        @Test
        @DisplayName("a newly known-exploited finding attributes to INTELLIGENCE_UPDATE")
        void intelligenceUpdateAttributed() {
            var attribution = ScoreChangeAttribution.between(score(moderate(), fullCoverage()), withKevRaised());

            assertTrue(attribution.totalDelta() > 0);
            var kev = attribution.components().stream().filter(c -> c.factor() == Factor.KEV).findFirst()
                    .orElseThrow();
            assertEquals(ScoreChangeAttribution.Cause.INTELLIGENCE_UPDATE, kev.cause());
            assertTrue(kev.delta() > 0 && kev.detail().contains("KEV changed"));
            assertTrue(attribution.attributableToTheSubject());
        }

        @Test
        @DisplayName("an EXP move with a new population version attributes to POPULATION_SHIFT, not intelligence")
        void populationShiftIsNotConflatedWithIntelligence() {
            var before = score(moderate(), fullCoverage());
            List<RiskScore.FactorInput> rankMoved = new ArrayList<>(moderate());
            rankMoved.replaceAll(i -> i.factor() == Factor.EXP
                    ? RiskScore.FactorInput.measured(Factor.EXP, "0.80", "epss-rank:0.80", T0) : i);
            var after = RiskScore.compute(UUID.randomUUID(), RiskScore.SubjectKind.FINDING_IMPACT, IMPACT, 1,
                    T0.plusSeconds(60), rankMoved, WeightSet.defaults(), BandThresholds.defaults(),
                    fullCoverage(), 2L);

            var attribution = ScoreChangeAttribution.between(before, after);
            var exp = attribution.components().stream().filter(c -> c.factor() == Factor.EXP).findFirst()
                    .orElseThrow();
            assertEquals(ScoreChangeAttribution.Cause.POPULATION_SHIFT, exp.cause(),
                    "population shift is 'the one cause where nothing about the finding changed, and "
                            + "conflating it with a real change destroys trust in attribution generally'");
            assertFalse(attribution.attributableToTheSubject(),
                    "and the caller can ask that question directly rather than inferring it");
        }

        @Test
        @DisplayName("a weight change attributes to MODEL_CHANGE separately from any factor")
        void modelChangeAttributedSeparately() {
            var before = score(moderate(), fullCoverage());
            Map<Factor, BigDecimal> heavierSeverity = new EnumMap<>(WeightSet.defaults().asMap());
            heavierSeverity.put(Factor.SEV, new BigDecimal("0.45"));
            var after = RiskScore.compute(UUID.randomUUID(), RiskScore.SubjectKind.FINDING_IMPACT, IMPACT, 2,
                    T0.plusSeconds(60), moderate(), WeightSet.of(heavierSeverity), BandThresholds.defaults(),
                    fullCoverage(), 1L);

            var attribution = ScoreChangeAttribution.between(before, after);
            assertTrue(attribution.components().stream()
                            .anyMatch(c -> c.cause() == ScoreChangeAttribution.Cause.MODEL_CHANGE),
                    "configuration change 'is the most efficient gaming path: one weight change affects every "
                            + "score at once and appears nowhere in a finding-level audit review'");
            assertFalse(attribution.attributableToTheSubject(),
                    "nothing about the finding changed");
        }

        @Test
        @DisplayName("a coverage change is attributed even where the value did not move")
        void coverageChangeAttributedWithNoValueChange() {
            var before = score(moderate(), fullCoverage());
            var after = RiskScore.compute(UUID.randomUUID(), RiskScore.SubjectKind.FINDING_IMPACT, IMPACT, 1,
                    T0.plusSeconds(60), moderate(), WeightSet.defaults(), BandThresholds.defaults(),
                    new CoverageQualifier(100, 30, 70, 400, 1, true), 1L);

            var attribution = ScoreChangeAttribution.between(before, after);
            assertEquals(0, attribution.totalDelta());
            var coverage = attribution.components().stream()
                    .filter(c -> c.cause() == ScoreChangeAttribution.Cause.COVERAGE_CHANGE).findFirst()
                    // A score that became unpresentable while its number held steady is the change a reader
                    // most needs told, so its absence here is a failure and not a missing optional.
                    .orElseThrow();
            assertTrue(coverage.detail().contains("PRD-RSK-027"));
        }

        @Test
        @DisplayName("the interaction residual is reported, not distributed silently across factors")
        void interactionResidualIsReported() {
            var before = score(moderate(), fullCoverage());
            var after = score(worstCase(), fullCoverage());
            var attribution = ScoreChangeAttribution.between(
                    before,
                    RiskScore.compute(after.id(), RiskScore.SubjectKind.FINDING_IMPACT, IMPACT, 1,
                            T0.plusSeconds(60), worstCase(), WeightSet.defaults(), BandThresholds.defaults(),
                            fullCoverage(), 1L));

            int attributed = attribution.components().stream()
                    .mapToInt(ScoreChangeAttribution.Component::delta).sum();
            assertEquals(attribution.totalDelta(), attributed + attribution.interactionResidual(),
                    "the components plus the residual must reconstruct the total exactly, or the attribution "
                            + "is not arithmetic a reader can check");
            assertNotEquals(0, attribution.interactionResidual(),
                    "several contextual factors moved together, and the multiplier makes the formula "
                            + "non-additive; absorbing the difference would report an arithmetic convenience "
                            + "as a finding about the world");
        }

        @Test
        @DisplayName("attributing across two different subjects is refused")
        void subjectsMustMatch() {
            var other = RiskScore.compute(UUID.randomUUID(), RiskScore.SubjectKind.FINDING_IMPACT,
                    new UUID(80, 9), 1, T0, moderate(), WeightSet.defaults(), BandThresholds.defaults(),
                    fullCoverage(), 1L);
            assertThrows(IllegalArgumentException.class,
                    () -> ScoreChangeAttribution.between(score(moderate(), fullCoverage()), other));
        }

        @Test
        @DisplayName("reversed arguments are refused rather than reporting every increase as a decrease")
        void reversedArgumentsRefused() {
            var before = score(moderate(), fullCoverage());
            var after = withKevRaised();
            assertThrows(IllegalArgumentException.class,
                    () -> ScoreChangeAttribution.between(after, before));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-RSK-027, -028 — the coverage qualifier")
    class Coverage {

        @Test
        @DisplayName("below 40% current, the score is not presentable as a posture figure")
        void insufficientCoverageWithholdsTheFigure() {
            var insufficient = new CoverageQualifier(100, 30, 70, 400, 1, true);
            assertEquals(CoverageQualifier.Confidence.INSUFFICIENT, insufficient.confidence());

            var s = score(worstCase(), insufficient);
            assertTrue(s.asPostureFigure().isEmpty(),
                    "presenting a favourable number over 30% coverage is the specific mechanism by which the "
                            + "platform would produce a confident, wrong executive report (PRD-RSK-027)");
            assertTrue(s.valueForPrioritisationOnly() > 0,
                    "the ordering is still available for a queue: poor coverage makes a score unusable as a "
                            + "statement about a POPULATION, not as an ordering of what was measured");
        }

        @Test
        @DisplayName("stale intelligence caps confidence at LOW regardless of asset coverage")
        void staleIntelligenceCapsConfidence() {
            var wellMeasuredButStale = new CoverageQualifier(100, 100, 0, 1, 400, false);
            assertEquals(CoverageQualifier.Confidence.LOW, wellMeasuredButStale.confidence(),
                    "DOC-28 section 9's LOW row is 'at least 40% current OR intelligence beyond threshold'; "
                            + "in an air-gapped deployment stale intelligence is the normal condition");
        }

        @Test
        @DisplayName("an empty scope yields zero coverage, not perfect coverage")
        void emptyScopeIsNotPerfect() {
            var empty = new CoverageQualifier(0, 0, 0, 0, 1, true);
            assertEquals(0.0, empty.currentDataRatio(),
                    "'100% of nothing' is the arithmetic that produces a perfect posture score for a node "
                            + "with no assets");
            assertEquals(CoverageQualifier.Confidence.INSUFFICIENT, empty.confidence());
            assertEquals(0, BigDecimal.ONE.compareTo(NodePosture.penaltyFrom(empty)),
                    "and an empty scope takes the MAXIMUM coverage penalty, not the minimum");
        }

        @Test
        @DisplayName("PRD-RSK-028: excluding UNMEASURED assets cannot raise coverage")
        void exclusionCannotRaiseCoverage() {
            var partial = new CoverageQualifier(100, 50, 50, 10, 1, true);
            var ex = assertThrows(IllegalArgumentException.class, () -> partial.withAssetsExcluded(50, 0));
            assertTrue(ex.getMessage().contains("PRD-RSK-028"),
                    "otherwise the cheapest route to HIGH confidence is excluding everything unmeasured, "
                            + "which inverts the metric's meaning");

            var afterMeasuredExclusion = partial.withAssetsExcluded(10, 10);
            assertEquals(40, afterMeasuredExclusion.assetsWithCurrentData());
            assertEquals(90, afterMeasuredExclusion.assetsInScope());
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-RSK-029, -030, -031 — aggregation and fair comparison")
    class Aggregation {

        private NodePosture posture(String pressure, String slaHealth, CoverageQualifier coverage) {
            return NodePosture.of(NODE, T0, new BigDecimal(pressure), new BigDecimal("0.30"),
                    new BigDecimal(slaHealth), NodePosture.penaltyFrom(coverage), coverage,
                    coverage.assetsInScope());
        }

        @Test
        @DisplayName("posture is the four weighted components of DOC-28 section 10.2, not a sum of findings")
        void postureIsTheWeightedComponents() {
            var p = posture("0.80", "0.40", fullCoverage());
            // 0.40(0.80) + 0.20(0.30) + 0.25(0.40) + 0.15(1 - 0.95) = 0.32 + 0.06 + 0.10 + 0.0075
            assertEquals(0, p.valueForPrioritisationOnly().compareTo(new BigDecimal("0.487500")),
                    "got " + p.valueForPrioritisationOnly());
        }

        @Test
        @DisplayName("a component outside [0,1] is rejected rather than pushing posture off its own scale")
        void componentsMustBeInUnitInterval() {
            assertThrows(IllegalArgumentException.class,
                    () -> NodePosture.of(NODE, T0, new BigDecimal("1.4"), BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, fullCoverage(), 100),
                    "a component out of interval makes the weights uninterpretable, and the symptom is "
                            + "an odd-looking chart rather than an error");
        }

        @Test
        @DisplayName("the coverage penalty rises as coverage falls, so not scanning is not free")
        void coveragePenaltyMakesConcealmentExpensive() {
            var wellMeasured = posture("0.80", "0.40", fullCoverage());
            var barelyMeasured = posture("0.80", "0.40", new CoverageQualifier(100, 45, 55, 300, 1, true));
            assertTrue(barelyMeasured.valueForPrioritisationOnly()
                            .compareTo(wellMeasured.valueForPrioritisationOnly()) > 0,
                    "summation would make NOT LOOKING the cheapest improvement available; the penalty is the "
                            + "direct inversion of that (DOC-28 10.1)");
        }

        @Test
        @DisplayName("PRD-RSK-030: the normalization applied is stated, not left implicit")
        void normalizationIsStated() {
            var basis = posture("0.80", "0.40", fullCoverage()).normalizationBasis();
            assertTrue(basis.contains("100 in-scope asset(s)") && basis.contains("95%"),
                    "an unstated normalization is indistinguishable from an unfair comparison; got " + basis);
        }

        @Test
        @DisplayName("PRD-RSK-031: a comparison set below the minimum is refused, not truncated")
        void smallComparisonSetsAreRefused() {
            var p = posture("0.80", "0.40", fullCoverage());
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> NodePosture.comparisonSet(List.of(p, p)));
            assertTrue(ex.getMessage().contains("SEC-AUZ-026"),
                    "a comparison against two peers discloses those peers' posture by inference");
            assertEquals(4, NodePosture.comparisonSet(List.of(p, p, p, p)).size());
        }

        @Test
        @DisplayName("PRD-RSK-026: an improvement over falling coverage is not presentable as an improvement")
        void improvementOverLostCoverageIsNotAnImprovement() {
            var before = posture("0.80", "0.40", new CoverageQualifier(100, 90, 10, 5, 1, true));
            var after = posture("0.30", "0.40", new CoverageQualifier(100, 50, 50, 200, 1, true));

            var verdict = PostureImprovementAttribution.between(before, after);
            assertEquals(PostureImprovementAttribution.Verdict.COVERAGE_LOSS, verdict.verdict());
            assertFalse(verdict.presentableAsImprovement(),
                    "'a finding count falling because a scanner stopped running looks identical to one "
                            + "falling because vulnerabilities were fixed'. DOC-28 calls PRD-RSK-026 the most "
                            + "important requirement in the document.");
            assertTrue(verdict.presentation().contains("NOT an improvement"),
                    "and the sentence presented says so, because a footnote does not stop a narrative");
        }

        @Test
        @DisplayName("PRD-RSK-026: an improvement at held coverage IS attributable to remediation")
        void improvementAtHeldCoverageIsRemediation() {
            var coverage = new CoverageQualifier(100, 90, 10, 5, 1, true);
            var before = posture("0.80", "0.40", coverage);
            var after = posture("0.30", "0.40", coverage);
            var verdict = PostureImprovementAttribution.between(before, after);
            assertEquals(PostureImprovementAttribution.Verdict.REMEDIATION, verdict.verdict());
            assertTrue(verdict.presentableAsImprovement());
        }

        @Test
        @DisplayName("no improvement claim is available at all where either period was INSUFFICIENT")
        void insufficientPeriodsAreIndeterminate() {
            var insufficient = new CoverageQualifier(100, 20, 80, 400, 1, true);
            var verdict = PostureImprovementAttribution.between(
                    posture("0.80", "0.40", insufficient), posture("0.10", "0.40", fullCoverage()));
            assertEquals(PostureImprovementAttribution.Verdict.INDETERMINATE, verdict.verdict(),
                    "distinguished from COVERAGE_LOSS: there a claim exists and is wrong, here there was "
                            + "never a figure to compare");
            assertFalse(verdict.presentableAsImprovement());
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("The two behaviours that look wrong and are correct")
    class CounterIntuitiveButCorrect {

        private ServiceLevelClock clock(Duration target) {
            return new ServiceLevelClock(UUID.randomUUID(), IMPACT, T0, 1, "calendar-snapshot-1",
                    target, T0.plus(target));
        }

        @Test
        @DisplayName("PRD-RSK-035: a shorter policy recomputes from the ORIGINAL start and may breach at once")
        void shorterPolicyMayBreachImmediately() {
            // Open for six weeks under a 60-day policy.
            var c = clock(Duration.ofDays(60));
            Instant sixWeeksLater = T0.plus(Duration.ofDays(42));

            // Now known-exploited: a 3-business-day policy applies, measured FROM THE ORIGINAL START.
            boolean moved = c.recomputeForScoreChange(Duration.ofDays(3), T0.plus(Duration.ofDays(3)),
                    sixWeeksLater);

            assertTrue(moved);
            assertTrue(c.isBreachedAt(sixWeeksLater),
                    "immediately breached, and that is the correct and honest outcome: a finding open six "
                            + "weeks that turns out to be actively exploited IS past a three-day deadline. "
                            + "Restarting would give it a fresh three days, which is the wrong direction "
                            + "entirely (PRD-RSK-035).");
            assertEquals(T0.plus(Duration.ofDays(60)), c.originalDueAt(),
                    "and the original commitment stays visible, so the recomputation is auditable");
        }

        @Test
        @DisplayName("PRD-RSK-036: a score decrease never extends the deadline")
        void scoreDecreaseNeverExtends() {
            var c = clock(Duration.ofDays(7));
            Instant before = c.dueAt();

            boolean moved = c.recomputeForScoreChange(Duration.ofDays(60), T0.plus(Duration.ofDays(60)),
                    T0.plusSeconds(3600));

            assertFalse(moved);
            assertEquals(before, c.dueAt(),
                    "otherwise downgrading severity becomes a deadline-extension mechanism, which DOC-28 "
                            + "section 13.2 lists as a gaming path (PRD-RSK-036)");
        }

        @Test
        @DisplayName("a score decrease is ignored silently, not rejected")
        void scoreDecreaseIsIgnoredNotRejected() {
            var c = clock(Duration.ofDays(7));
            // Returns false rather than throwing: a score decrease is legitimate and frequent — it is the
            // deadline extension that must not follow. Raising would make callers avoid reporting decreases.
            assertFalse(c.recomputeForScoreChange(Duration.ofDays(30), T0.plus(Duration.ofDays(30)), T0));
            assertEquals(ServiceLevelClock.State.RUNNING, c.state());
        }

        @Test
        @DisplayName("a recomputation preserves paused time already granted")
        void recomputationPreservesPausedTime() {
            var c = clock(Duration.ofDays(60));
            c.pause(ServiceLevelClock.BlockingAttribution.THIRD_PARTY, T0.plus(Duration.ofDays(1)));
            c.resume(T0.plus(Duration.ofDays(6)));

            c.recomputeForScoreChange(Duration.ofDays(10), T0.plus(Duration.ofDays(10)),
                    T0.plus(Duration.ofDays(6)));

            assertEquals(T0.plus(Duration.ofDays(15)), c.dueAt(),
                    "10 days from the original start plus the 5 days somebody else blocked. A team is not "
                            + "charged for a delay they did not cause even when the deadline shortens "
                            + "underneath them (PRD-RSK-034 and PRD-RSK-035 together)");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-09 section 9 — the clock's other guards")
    class ClockGuards {

        private ServiceLevelClock clock() {
            return new ServiceLevelClock(UUID.randomUUID(), IMPACT, T0, 1, "calendar-snapshot-1",
                    Duration.ofDays(7), T0.plus(Duration.ofDays(7)));
        }

        @Test
        @DisplayName("PRD-RSK-034: pausing requires a blocking attribution")
        void pauseRequiresAttribution() {
            var c = clock();
            assertThrows(NullPointerException.class, () -> c.pause(null, T0.plusSeconds(60)),
                    "unattributed delay defaults to blaming the accountable team, which is usually wrong and "
                            + "always corrosive");
        }

        @Test
        @DisplayName("paused time shifts the deadline and is reportable separately")
        void pausedTimeShiftsAndIsReportable() {
            var c = clock();
            Instant originalDue = c.dueAt();
            c.pause(ServiceLevelClock.BlockingAttribution.THIRD_PARTY, T0.plus(Duration.ofDays(1)));
            c.resume(T0.plus(Duration.ofDays(3)));

            assertEquals(originalDue.plus(Duration.ofDays(2)), c.dueAt());
            assertEquals(Duration.ofDays(2), c.totalPausedDuration(),
                    "PRD-RSK-034 requires paused time reportable separately from elapsed");
            assertEquals(T0.plus(Duration.ofDays(7)), c.originalDueAt(), "the original stays visible");
        }

        @Test
        @DisplayName("PRD-RSK-037: escalation does not fire while blocked on the requester or a third party")
        void escalationSuppressedWhileBlockedElsewhere() {
            var c = clock();
            c.pause(ServiceLevelClock.BlockingAttribution.REQUESTER, T0.plusSeconds(60));
            assertFalse(c.escalationFires(),
                    "escalating the accountable team for a delay they did not cause is how a team learns to "
                            + "ignore escalations; a separate chain escalates the blocking party");

            var blockedOnSecurity = clock();
            blockedOnSecurity.pause(ServiceLevelClock.BlockingAttribution.SECURITY_FUNCTION,
                    T0.plusSeconds(60));
            assertTrue(blockedOnSecurity.escalationFires(),
                    "the security function is escalated against too — the attribution set includes it so the "
                            + "platform can be blamed");
        }

        @Test
        @DisplayName("INV-RSK-11: EXTENDED is distinct from MET, and requires a reason")
        void extensionIsDistinctFromMet() {
            var c = clock();
            assertThrows(IllegalArgumentException.class,
                    () -> c.extend(T0.plus(Duration.ofDays(30)), "  ", T0.plusSeconds(60)),
                    "an unexplained extension is indistinguishable from a met deadline in every aggregate");

            c.extend(T0.plus(Duration.ofDays(30)), "vendor patch scheduled for the next release", T0);
            assertEquals(ServiceLevelClock.State.EXTENDED, c.state(),
                    "a clock reaching MET after an extension would make the extension invisible in every "
                            + "service-level figure (DOC-28 section 13.2)");
        }

        @Test
        @DisplayName("a late resolution retains the breach; it does not become MET")
        void lateResolutionRetainsTheBreach() {
            var c = clock();
            c.breach(T0.plus(Duration.ofDays(8)));
            c.meetLate(T0.plus(Duration.ofDays(10)));

            assertEquals(ServiceLevelClock.State.BREACHED, c.state(),
                    "converting a late resolution into a met deadline would make the breach rate improvable "
                            + "by finishing late");
            assertTrue(c.resolvedAt().isPresent());
            assertTrue(c.breachedAt().isPresent());
        }

        @Test
        @DisplayName("meet is refused after the deadline, so it cannot be used to erase a breach")
        void meetRefusedAfterDeadline() {
            var c = clock();
            assertThrows(IllegalArgumentException.class, () -> c.meet(T0.plus(Duration.ofDays(9))),
                    "recording a late resolution as met would erase the breach from every service-level "
                            + "figure");
        }

        @Test
        @DisplayName("breach is idempotent, so a sweep running twice does not double-escalate")
        void breachIsIdempotent() {
            var c = clock();
            c.breach(T0.plus(Duration.ofDays(8)));
            Instant firstBreach = c.breachedAt().orElseThrow();
            c.breach(T0.plus(Duration.ofDays(9)));
            assertEquals(firstBreach, c.breachedAt().orElseThrow());
        }

        @Test
        @DisplayName("PRD-RSK-032, -033: the policy version and calendar are pinned at start")
        void policyAndCalendarArePinned() {
            var c = clock();
            assertEquals(1, c.policyVersion());
            assertEquals("calendar-snapshot-1", c.calendarSnapshotReference());
            assertThrows(NullPointerException.class,
                    () -> new ServiceLevelClock(UUID.randomUUID(), IMPACT, T0, 1, null, Duration.ofDays(7),
                            T0.plus(Duration.ofDays(7))),
                    "without a snapshot, a tenant editing its holiday list silently moves every open "
                            + "deadline (PRD-RSK-033)");
            for (var m : ServiceLevelClock.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("set") && (name.contains("policy") || name.contains("calendar")),
                        "found " + m.getName() + "; a later policy or calendar change must not move an "
                                + "existing deadline (PRD-RSK-032, PRD-RSK-033)");
            }
        }

        @Test
        @DisplayName("a terminal clock refuses further events rather than silently ignoring them")
        void terminalClockRefusesEvents() {
            var c = clock();
            c.meet(T0.plus(Duration.ofDays(2)));
            assertThrows(IllegalStateException.class,
                    () -> c.pause(ServiceLevelClock.BlockingAttribution.REQUESTER, T0.plus(Duration.ofDays(3))));
            assertThrows(IllegalStateException.class, () -> c.cancel(T0.plus(Duration.ofDays(3))));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-28 section 13 — anti-gaming controls")
    class AntiGaming {

        @Test
        @DisplayName("PRD-RSK-041: every gaming path of DOC-28 section 13.2 has an action class")
        void everyGamingPathIsEnumerated() {
            // The eleven rows of section 13.2, plus configuration change from PRD-RSK-043. A new way to lower
            // a score that does not appear here is an undetected gaming path.
            assertEquals(12, ScoreReducingAction.values().length,
                    "eleven paths in DOC-28 section 13.2 plus SCORE_CONFIGURATION_CHANGE from PRD-RSK-043");
            for (String required : List.of("CLOSE_NOT_APPLICABLE", "SEVERITY_DOWNGRADE", "RISK_EXCEPTION",
                    "CRITICALITY_DOWNGRADE", "EXPOSURE_DOWNGRADE", "DATA_CLASSIFICATION_REMOVAL",
                    "SCOPE_EXCLUSION", "ASSET_RETIREMENT", "FALSE_POSITIVE_SUPPRESSION", "DEADLINE_EXTENSION",
                    "FINDING_SPLIT", "SCORE_CONFIGURATION_CHANGE")) {
                assertEquals(required, ScoreReducingAction.valueOf(required).name());
            }
        }

        @Test
        @DisplayName("PRD-RSK-043: only configuration change carries the elevated-permission requirement")
        void configurationChangeIsTheDistinctCase() {
            assertTrue(ScoreReducingAction.SCORE_CONFIGURATION_CHANGE
                    .requiresElevatedPermissionAndConfigurationSummary());
            assertFalse(ScoreReducingAction.CLOSE_NOT_APPLICABLE
                            .requiresElevatedPermissionAndConfigurationSummary(),
                    "closing a finding as not-applicable is ordinary work; requiring elevated permission for "
                            + "it would impede the team rather than detect gaming");
        }

        @Test
        @DisplayName("an anomaly needs BOTH a self-deviation and a peer deviation")
        void anomalyNeedsBothComparisons() {
            var observation = new ScoreReducingAction[] {ScoreReducingAction.CLOSE_NOT_APPLICABLE};
            var window = new ScoreReducingRateAnomaly.Observation(UUID.randomUUID(), NODE, observation[0],
                    120, Duration.ofDays(7));

            // 17.1/day against a trailing 1/day and a peer median of 1/day: both exceeded.
            assertTrue(ScoreReducingRateAnomaly.evaluate(window, 1.0, List.of(0.9, 1.0, 1.1)).isPresent());

            // Same burst, but this principal always operates at this rate: no self-deviation. The control
            // does not fire, and the gap is named in the class documentation rather than left implicit.
            assertTrue(ScoreReducingRateAnomaly.evaluate(window, 20.0, List.of(0.9, 1.0, 1.1)).isEmpty(),
                    "self-comparison alone would fire; requiring both means a consistently high rate is not "
                            + "detected by THIS control");

            // Same burst, but the whole node operates at this rate — a triage team, legitimately.
            assertTrue(ScoreReducingRateAnomaly.evaluate(window, 1.0, List.of(18.0, 20.0, 22.0)).isEmpty(),
                    "peer comparison alone would misfire on a role that legitimately concentrates one "
                            + "action, and flagging it weekly trains the reviewer to dismiss the signal");
        }

        @Test
        @DisplayName("a small absolute count raises nothing regardless of ratio")
        void smallCountsAreNotAnomalies() {
            var small = new ScoreReducingRateAnomaly.Observation(UUID.randomUUID(), NODE,
                    ScoreReducingAction.SEVERITY_DOWNGRADE, 3, Duration.ofDays(7));
            assertTrue(ScoreReducingRateAnomaly.evaluate(small, 0.01, List.of(0.01, 0.02)).isEmpty(),
                    "three downgrades against a baseline of one a month is a large ratio and means nothing");
        }

        @Test
        @DisplayName("a node with no peers raises nothing rather than treating absence as confirmation")
        void noPeersMeansNoAnomaly() {
            var observation = new ScoreReducingRateAnomaly.Observation(UUID.randomUUID(), NODE,
                    ScoreReducingAction.ASSET_RETIREMENT, 60, Duration.ofDays(7));
            assertTrue(ScoreReducingRateAnomaly.evaluate(observation, 0.5, List.of()).isEmpty(),
                    "otherwise every small node's only practitioner is flagged permanently");
        }

        @Test
        @DisplayName("a first-week principal with no baseline is not flagged for having no history")
        void zeroBaselineIsNotInfiniteDeviation() {
            var observation = new ScoreReducingRateAnomaly.Observation(UUID.randomUUID(), NODE,
                    ScoreReducingAction.CLOSE_NOT_APPLICABLE, 40, Duration.ofDays(7));
            assertTrue(ScoreReducingRateAnomaly.evaluate(observation, 0.0, List.of(0.5, 0.6)).isEmpty(),
                    "a zero baseline is 'no baseline to deviate from', not an infinite deviation");
        }

        @Test
        @DisplayName("a raised anomaly carries its own arithmetic")
        void anomalyCarriesItsArithmetic() {
            var observation = new ScoreReducingRateAnomaly.Observation(UUID.randomUUID(), NODE,
                    ScoreReducingAction.FALSE_POSITIVE_SUPPRESSION, 70, Duration.ofDays(7));
            var anomaly = ScoreReducingRateAnomaly.evaluate(observation, 1.0, List.of(1.0, 1.2, 0.8))
                    .orElseThrow();
            assertTrue(anomaly.explanation().contains("PRD-RSK-041"));
            assertTrue(anomaly.explanation().contains("Each action is legitimate; the rate is the signal"),
                    "a principal told their rate was flagged is entitled to the arithmetic, and 'the model "
                            + "found it anomalous' is not an answer that survives being challenged (PP-2)");
            assertTrue(anomaly.multipleOfOwnBaseline() >= 3.0 && anomaly.multipleOfPeerMedian() >= 3.0);
        }

        @Test
        @DisplayName("PRD-RSK-042: a closure total is unavailable without its breakdown")
        void closureTotalRequiresItsBreakdown() {
            for (var m : ClosureFigure.class.getMethods()) {
                assertNotEquals("totalClosed", m.getName(),
                        "an undifferentiated closure rate is 'the metric most easily optimized by closing "
                                + "rather than fixing, and the figure most likely to appear in an executive "
                                + "summary'");
            }

            Map<String, Integer> other = new LinkedHashMap<>();
            other.put("NOT_APPLICABLE", 300);
            other.put("RISK_ACCEPTED", 50);
            var figure = ClosureFigure.of(40, other);

            var presented = figure.totalWithBreakdown();
            assertEquals(390, presented.total());
            assertEquals(40, presented.verified());
            assertTrue(presented.presentation().contains("40 verified remediated"),
                    "the breakdown travels with the total: got " + presented.presentation());
            assertEquals(350, figure.otherCount());
        }

        @Test
        @DisplayName("a period with no closures reports zero verified proportion, not perfect verification")
        void emptyPeriodIsNotPerfect() {
            assertEquals(0.0, ClosureFigure.of(0, Map.of()).verifiedProportion(),
                    "'all of nothing was verified' is the same arithmetic error PRD-RSK-027 guards at "
                            + "coverage level");
        }

        @Test
        @DisplayName("a blank closure reason code is refused — it is an undifferentiated bucket by another name")
        void blankReasonCodeRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> ClosureFigure.of(10, Map.of("  ", 5)));
        }
    }
}
