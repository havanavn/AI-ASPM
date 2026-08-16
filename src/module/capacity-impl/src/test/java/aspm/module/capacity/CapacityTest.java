package aspm.module.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.capacity.domain.AvailableCapacity;
import aspm.module.capacity.domain.CapacityMeasure;
import aspm.module.capacity.domain.WorkloadRollup;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Prompt 15 — capacity. {@code INV-CAP-01} to {@code INV-CAP-06} and ADR-022. */
class CapacityTest {

    private static final UUID TENANT = new UUID(190, 1);
    private static final UUID MEMBER = new UUID(190, 2);
    private static final UUID TEAM = new UUID(190, 3);
    private static final LocalDate PERIOD = LocalDate.of(2026, 8, 1);

    private static final CapacityMeasure.TargetBand BAND = new CapacityMeasure.TargetBand(70, 85,
            "a function at full utilization absorbs no incident without dropping planned work");

    private static final String PURPOSE =
            "to see whether the security function's load is sustainable, not to rank individuals";

    private static final Map<String, BigDecimal> EFFORT = Map.of(
            "ASSESSMENT", new BigDecimal("9"),
            "REMEDIATION_SUPPORT", new BigDecimal("3"),
            "GOVERNANCE", new BigDecimal("2"));

    /** 31 calendar days, 9 non-working, 2 leave, 4 overhead, full time: 16 available. */
    private static AvailableCapacity capacity() {
        return new AvailableCapacity(new BigDecimal("31"), new BigDecimal("9"), new BigDecimal("2"),
                new BigDecimal("4"), BigDecimal.ONE);
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-CAP-01 — net of everything, never gross headcount")
    class NetCapacity {

        @Test
        @DisplayName("all three deductions apply")
        void deductionsApply() {
            assertEquals(0, capacity().netAvailableDays().compareTo(new BigDecimal("16.00")),
                    "31 calendar less 9 non-working less 2 leave less 4 overhead. Costing the month at 30 is "
                            + "a 36% overstatement before anybody takes leave.");
        }

        @Test
        @DisplayName("there is no factory taking a headcount")
        void noHeadcountFactory() {
            for (Method m : AvailableCapacity.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("headcount") || name.contains("fromteamsize"),
                        "found " + m.getName() + "; gross headcount is never used (INV-CAP-01)");
            }
            for (var component : AvailableCapacity.class.getRecordComponents()) {
                assertFalse(component.getName().toLowerCase(Locale.ROOT).contains("headcount"));
            }
        }

        @Test
        @DisplayName("capacity floors at zero rather than going negative")
        void extendedAbsenceFloorsAtZero() {
            var onLongLeave = new AvailableCapacity(new BigDecimal("31"), new BigDecimal("9"),
                    new BigDecimal("30"), new BigDecimal("4"), BigDecimal.ONE);
            assertEquals(0, onLongLeave.netAvailableDays().compareTo(new BigDecimal("0.00")),
                    "a negative capacity would SUBTRACT from a team total, making the team look smaller than "
                            + "it is while somebody is away — arithmetically tidy and operationally absurd");
        }

        @Test
        @DisplayName("a part-time ratio scales the net, not the gross")
        void partTimeScalesTheNet() {
            var halfTime = new AvailableCapacity(new BigDecimal("31"), new BigDecimal("9"),
                    new BigDecimal("2"), new BigDecimal("4"), new BigDecimal("0.5"));
            assertEquals(0, halfTime.netAvailableDays().compareTo(new BigDecimal("8.00")),
                    "scaling the gross and then deducting would charge a half-time member a full overhead "
                            + "allowance");
        }

        @Test
        @DisplayName("the breakdown accompanies the figure, so a dispute is about the overhead line")
        void breakdownIsPresentable() {
            assertTrue(capacity().breakdown().contains("4 overhead"),
                    "a net number with no visible deductions is one somebody disputes by asserting a bigger "
                            + "one; got: " + capacity().breakdown());
        }

        @Test
        @DisplayName("a capacity ratio outside [0,1] is refused")
        void ratioIsAProportion() {
            assertThrows(IllegalArgumentException.class,
                    () -> new AvailableCapacity(new BigDecimal("31"), BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, new BigDecimal("1.5")));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-CAP-03 / ADR-022 — per-member measures are RESTRICTED")
    class PerMemberProtection {

        private CapacityMeasure memberMeasure() {
            return CapacityMeasure.forMember(MEMBER, PERIOD, capacity(), new BigDecimal("14"), EFFORT,
                    BAND, PURPOSE);
        }

        @Test
        @DisplayName("a member measure is RESTRICTED and a team measure is not")
        void memberMeasuresAreRestricted() {
            assertEquals("RESTRICTED", memberMeasure().classification());
            assertEquals("CONFIDENTIAL", CapacityMeasure.forTeam(TEAM, PERIOD, capacity(),
                    new BigDecimal("60"), EFFORT, 6, BAND, PURPOSE).classification());
        }

        @Test
        @DisplayName("the gate is a named permission that no other permission implies")
        void gateIsNotSeniority() {
            var measure = memberMeasure();
            var withoutIt = measure.releaseTo(CapacityMeasure.Audience.SECURITY_OPERATIONS,
                    Set.of("cap.team.measure.read", "auz.role.manage", "aud.audit.read"));

            assertFalse(withoutIt.permitted(),
                    "a principal holding role management and audit read still cannot see a per-member figure");
            assertTrue(withoutIt.reason().orElseThrow().contains("job title"),
                    "a permission that a sufficiently senior role implies is not a permission (INV-CAP-03); "
                            + "got " + withoutIt.reason().orElseThrow());

            var withIt = measure.releaseTo(CapacityMeasure.Audience.SECURITY_OPERATIONS,
                    Set.of(CapacityMeasure.PER_MEMBER_PERMISSION));
            assertTrue(withIt.permitted());
            assertTrue(withIt.requiresPerAccessAudit(),
                    "audited per access, and the obligation travels with the release rather than being a "
                            + "separate thing the caller remembers");
        }

        @Test
        @DisplayName("per-member data is excluded from business owner and executive views entirely")
        void excludedFromOtherAudiences() {
            var measure = memberMeasure();
            for (var audience : List.of(CapacityMeasure.Audience.BUSINESS_OWNER,
                    CapacityMeasure.Audience.EXECUTIVE)) {
                var release = measure.releaseTo(audience,
                        Set.of(CapacityMeasure.PER_MEMBER_PERMISSION));
                assertFalse(release.permitted(),
                        audience + " received per-member data even holding the permission. A measurement "
                                + "system producing evidence against its own users is worse than none "
                                + "(ADR-022).");
            }
        }

        @Test
        @DisplayName("the TEAM aggregate is withheld from those audiences too")
        void teamAggregateAlsoExcluded() {
            var team = CapacityMeasure.forTeam(TEAM, PERIOD, capacity(), new BigDecimal("60"), EFFORT, 6,
                    BAND, PURPOSE);
            var release = team.releaseTo(CapacityMeasure.Audience.EXECUTIVE, Set.of("cap.team.measure.read"));

            assertFalse(release.permitted(),
                    "the prompt says 'including team aggregate', which is stronger than it first reads: a "
                            + "small team's figure is its members'");
            assertTrue(release.reason().orElseThrow().contains("INV-CAP-04"));
        }

        @Test
        @DisplayName("the permission is a constant, not something a caller can supply")
        void permissionIsNotAParameter() {
            for (Method m : CapacityMeasure.class.getMethods()) {
                if (!m.getName().equals("releaseTo")) {
                    continue;
                }
                assertEquals(2, m.getParameterCount(),
                        "a permission-name parameter would let a caller pass one the reader happens to hold");
            }
            assertEquals("cap.member.measure.read", CapacityMeasure.PER_MEMBER_PERMISSION);
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-CAP-04 — the subtraction attack")
    class MinimumGroupSize {

        @Test
        @DisplayName("a team measure below the minimum cannot be CONSTRUCTED")
        void smallTeamMeasureIsUnrepresentable() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> CapacityMeasure.forTeam(TEAM, PERIOD, capacity(), new BigDecimal("30"), EFFORT, 3,
                            BAND, PURPOSE));
            assertTrue(ex.getMessage().contains("by subtraction"),
                    "a team of three where two members' data is visible discloses the third; suppression is "
                            + "the ONLY mechanism preventing an aggregate becoming a per-person disclosure");

            // Unrepresentable, not merely unrendered: no query, export or report can produce one.
            assertTrue(CapacityMeasure.forTeam(TEAM, PERIOD, capacity(), new BigDecimal("60"), EFFORT,
                            CapacityMeasure.MINIMUM_CONTRIBUTING_MEMBERS, BAND, PURPOSE)
                    .contributingMemberCount() >= 4);
        }

        @Test
        @DisplayName("the minimum is four, not three")
        void minimumIsFour() {
            assertEquals(4, CapacityMeasure.MINIMUM_CONTRIBUTING_MEMBERS,
                    "with three, a member who can see their own figure subtracts it and has a two-person "
                            + "aggregate — which for a pair is one subtraction from individual data");
        }

        @Test
        @DisplayName("a member measure has exactly one contributor")
        void memberMeasureHasOneContributor() {
            assertEquals(1, CapacityMeasure.forMember(MEMBER, PERIOD, capacity(), new BigDecimal("14"),
                    EFFORT, BAND, PURPOSE).contributingMemberCount());
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-CAP-05 / INV-CAP-06 — the band, and every category")
    class BandAndCategories {

        @Test
        @DisplayName("INV-CAP-05: utilization is presented against a band with its reason, never a maximum")
        void bandNotMaximum() {
            var measure = CapacityMeasure.forMember(MEMBER, PERIOD, capacity(), new BigDecimal("14"), EFFORT,
                    BAND, PURPOSE);
            String presentation = measure.presentation();

            assertTrue(presentation.contains("target band of 70–85%"));
            assertTrue(presentation.contains("Why this band:"),
                    "without the reason the upper bound reads as a target to reach, and a team at a hundred "
                            + "percent has no slack to absorb an incident");
            assertFalse(presentation.contains("of maximum") || presentation.contains("of capacity"),
                    "a maximum reads as a target; the whole point of a band is that a hundred percent is a "
                            + "failure state");

            for (Method m : CapacityMeasure.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("maximum") || name.contains("percentofcapacity"),
                        "found " + m.getName() + " (INV-CAP-05)");
            }
        }

        @Test
        @DisplayName("a band with no reason cannot be configured")
        void bandNeedsItsReason() {
            assertThrows(IllegalArgumentException.class,
                    () -> new CapacityMeasure.TargetBand(70, 85, "  "));
            assertThrows(IllegalArgumentException.class,
                    () -> new CapacityMeasure.TargetBand(85, 70, "inverted"));
            assertThrows(IllegalArgumentException.class,
                    () -> new CapacityMeasure.TargetBand(70, 120, "above a hundred"));
        }

        @Test
        @DisplayName("both below and above the band are outside it")
        void neitherEndIsGood() {
            var underloaded = CapacityMeasure.forMember(MEMBER, PERIOD, capacity(), new BigDecimal("4"),
                    EFFORT, BAND, PURPOSE);
            var overloaded = CapacityMeasure.forMember(MEMBER, PERIOD, capacity(), new BigDecimal("16"),
                    EFFORT, BAND, PURPOSE);

            assertFalse(underloaded.withinTargetBand(), "25% is outside the band");
            assertFalse(overloaded.withinTargetBand(), "100% is outside it too, and that is the point");
            assertTrue(overloaded.presentation().contains("OUTSIDE the band"));
        }

        @Test
        @DisplayName("INV-CAP-06: a single-category effort model is refused")
        void effortSpansEveryCategory() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> CapacityMeasure.forMember(MEMBER, PERIOD, capacity(), new BigDecimal("14"),
                            Map.of("ASSESSMENT", new BigDecimal("14")), BAND, PURPOSE));
            assertTrue(ex.getMessage().contains("deny resourcing"),
                    "a model counting only assessments reports a materially over-capacity team at low "
                            + "utilization, and that number is then used to deny resourcing (INV-CAP-06)");
        }

        @Test
        @DisplayName("zero available capacity yields zero, not an exception or an absurd number")
        void zeroCapacityIsHandled() {
            var away = new AvailableCapacity(new BigDecimal("31"), new BigDecimal("9"),
                    new BigDecimal("22"), BigDecimal.ZERO, BigDecimal.ONE);
            var measure = CapacityMeasure.forMember(MEMBER, PERIOD, away, new BigDecimal("2"), EFFORT,
                    BAND, PURPOSE);
            assertEquals(0, measure.utilizationPercent().compareTo(new BigDecimal("0.00")),
                    "zero available with an allocation is somebody working while on leave, not infinite "
                            + "utilization");
        }

        @Test
        @DisplayName("a measure carries its purpose statement")
        void purposeIsRequired() {
            assertThrows(IllegalArgumentException.class,
                    () -> CapacityMeasure.forMember(MEMBER, PERIOD, capacity(), new BigDecimal("14"), EFFORT,
                            BAND, "  "),
                    "a metric about a person with no stated purpose is one whose purpose the reader supplies");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-CAP-02 — idempotent, backfillable rollups")
    class Rollups {

        private static final UUID OPEN = new UUID(191, 1);
        private static final UUID IN_PROGRESS = new UUID(191, 2);

        private static List<WorkloadRollup.ItemStateOnDay> states() {
            return List.of(
                    new WorkloadRollup.ItemStateOnDay(new UUID(192, 1), Optional.of(OPEN)),
                    new WorkloadRollup.ItemStateOnDay(new UUID(192, 2), Optional.of(OPEN)),
                    new WorkloadRollup.ItemStateOnDay(new UUID(192, 3), Optional.of(IN_PROGRESS)),
                    // Did not exist on this day.
                    new WorkloadRollup.ItemStateOnDay(new UUID(192, 4), Optional.empty()));
        }

        @Test
        @DisplayName("running the same day twice produces the same rollup")
        void rollupIsIdempotent() {
            var first = WorkloadRollup.compute(TENANT, PERIOD, states(), 12);
            var second = WorkloadRollup.compute(TENANT, PERIOD, states(), 12);

            assertEquals(first, second,
                    "a rollup that accumulated rather than replaced could not be re-run: a defect found in "
                            + "March would mean every day since is wrong, with no remedy but explaining the "
                            + "discontinuity in a chart forever (INV-CAP-02)");
            assertTrue(first.agreesWith(second));
        }

        @Test
        @DisplayName("an item that did not exist yet occupies no state")
        void nonexistentItemsAreNotCounted() {
            var rollup = WorkloadRollup.compute(TENANT, PERIOD, states(), 12);
            assertEquals(3, rollup.totalOccupied(),
                    "counting it would make a cumulative-flow chart show work before it was created");
            assertEquals(2, rollup.stateOccupancy().get(OPEN));
        }

        @Test
        @DisplayName("a recomputation that disagrees is detectable")
        void divergenceIsDetectable() {
            var original = WorkloadRollup.compute(TENANT, PERIOD, states(), 12);
            var recomputed = WorkloadRollup.compute(TENANT, PERIOD,
                    List.of(new WorkloadRollup.ItemStateOnDay(new UUID(192, 1), Optional.of(OPEN))), 12);

            assertFalse(original.agreesWith(recomputed),
                    "without the comparison a backfill silently replaces one wrong answer with another and "
                            + "nobody knows which was which (CON-DAT-031)");
        }

        @Test
        @DisplayName("the rollup is computed from transition data, not from an incremented counter")
        void backfillableFromTheTransitionLog() {
            for (Method m : WorkloadRollup.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("increment")
                                || (name.startsWith("add") && !name.equals("addall")),
                        "found " + m.getName() + ". A counter is not backfillable — it has no record of what "
                                + "it counted — and the whole argument for building the transition log in v1 "
                                + "is that this computation must be possible for periods that have already "
                                + "passed (DOC-03 section 13.2).");
            }
            assertTrue(WorkloadRollup.compute(TENANT, PERIOD, states(), 12)
                            .computedFromTransitionCount() > 0,
                    "and the rollup records how many transitions it read, so a backfill over an empty log is "
                            + "distinguishable from one over a quiet day");
        }
    }
}
