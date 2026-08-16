package aspm.module.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.assessment.domain.Assessment;
import aspm.module.assessment.domain.ChecklistDefinition;
import aspm.module.assessment.domain.ChecklistInstance;
import aspm.module.assessment.domain.CoverageSummary;
import aspm.module.assessment.domain.ItemResult;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.ScopeDescriptor;
import aspm.sharedkernel.TenantId;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Prompt 10 session 2 — the assessment aggregate, checklists and coverage. {@code INV-ASM-10} to {@code -19}. */
class AssessmentAndCoverageTest {

    private static final Instant T0 = Instant.parse("2026-08-01T09:00:00Z");
    private static final UUID TYPE = new UUID(111, 1);
    private static final UUID ASSET = new UUID(111, 2);
    private static final UUID LEAD = new UUID(111, 3);
    private static final UUID REVIEWER = new UUID(111, 4);
    private static final OrgNodeId ASSET_OWNER_NODE = new OrgNodeId(new UUID(111, 5));
    private static final OrgNodeId ASSESSOR_NODE = new OrgNodeId(new UUID(111, 6));

    private static ScopeDescriptor scopeAt(OrgNodeId node) {
        return new ScopeDescriptor(new TenantId(new UUID(1, 1)), node, List.of(node),
                new UUID(2, 1), new UUID(3, 1), T0, 1L);
    }

    private static ChecklistDefinition published(int itemCount) {
        List<ChecklistDefinition.Item> items = new ArrayList<>();
        for (int i = 1; i <= itemCount; i++) {
            items.add(new ChecklistDefinition.Item(new UUID(112, i), "V" + i, "authentication",
                    "verify control " + i));
        }
        var definition = new ChecklistDefinition(UUID.randomUUID(), "ASVS-L2", 3, items);
        definition.publish(T0);
        return definition;
    }

    private static Assessment planned() {
        return Assessment.create(UUID.randomUUID(), TYPE, null, List.of(ASSET), scopeAt(ASSET_OWNER_NODE),
                LEAD);
    }

    private static void assessAll(ChecklistInstance instance, ItemResult.Verdict verdict) {
        for (var item : instance.items()) {
            instance.record(ItemResult.assessed(item.id(), verdict,
                    verdict == ItemResult.Verdict.NOT_APPLICABLE ? "out of scope for this release" : null,
                    LEAD, T0));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-ASM-19 / INV-ASM-13 — there is no null, and no unreasoned exclusion")
    class Results {

        @Test
        @DisplayName("NOT_APPLICABLE without a reason cannot be constructed")
        void notApplicableRequiresAReason() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> ItemResult.assessed(new UUID(112, 1), ItemResult.Verdict.NOT_APPLICABLE, "  ",
                            LEAD, T0));
            assertTrue(ex.getMessage().contains("path of least resistance under deadline"),
                    "marking inconvenient items inapplicable inflates coverage while the assessment covers "
                            + "less (INV-ASM-13)");
        }

        @Test
        @DisplayName("the honest downgrade is offered, so nobody invents a reason to pass validation")
        void unreasonedExclusionBecomesNotAssessed() {
            var downgraded = ItemResult.notApplicableOrNotAssessed(new UUID(112, 1), null, LEAD, T0);
            assertEquals(ItemResult.Verdict.NOT_ASSESSED, downgraded.verdict(),
                    "a rule people route around produces worse data than no rule");
            assertFalse(downgraded.covered());

            var reasoned = ItemResult.notApplicableOrNotAssessed(new UUID(112, 1),
                    "the feature is not present in this release", LEAD, T0);
            assertEquals(ItemResult.Verdict.NOT_APPLICABLE, reasoned.verdict());
            assertTrue(reasoned.covered(), "somebody looked and concluded it does not apply — that is a result");
        }

        @Test
        @DisplayName("an assessed verdict records who and when; NOT_ASSESSED records neither")
        void attributionMatchesTheVerdict() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ItemResult(new UUID(112, 1), ItemResult.Verdict.PASS, Optional.empty(),
                            Optional.empty(), Optional.empty()),
                    "an unattributed PASS is a coverage claim nobody made");
            assertThrows(IllegalArgumentException.class,
                    () -> new ItemResult(new UUID(112, 1), ItemResult.Verdict.NOT_ASSESSED, Optional.empty(),
                            Optional.of(LEAD), Optional.of(T0)),
                    "an attributed non-assessment reads as work that was done");
        }

        @Test
        @DisplayName("a null verdict is not representable")
        void noNullVerdict() {
            assertThrows(NullPointerException.class,
                    () -> new ItemResult(new UUID(112, 1), null, Optional.empty(), Optional.empty(),
                            Optional.empty()),
                    "a null result is indistinguishable from a passing one in every aggregate that counts");
        }

        @Test
        @DisplayName("every instantiated item starts NOT_ASSESSED rather than absent")
        void itemsStartNotAssessed() {
            var instance = published(3).instantiate(UUID.randomUUID(), UUID.randomUUID());
            assertEquals(3, instance.results().size());
            assertEquals(3, instance.coverage().itemsNotAssessed(),
                    "an item nobody has touched is a recorded gap from the moment of instantiation, which is "
                            + "what makes an abandoned assessment's partial coverage meaningful");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-ASM-11 — coverage is derived and cannot be set")
    class Coverage {

        @Test
        @DisplayName("there is no way to construct a coverage summary other than from results")
        void coverageIsOnlyDerived() {
            for (var constructor : CoverageSummary.class.getConstructors()) {
                assertEquals(5, constructor.getParameterCount(),
                        "the record's canonical constructor is unavoidable, but no OTHER public constructor "
                                + "may exist — a settable coverage figure is a figure somebody will set to the "
                                + "number they need");
            }
            for (Method m : Assessment.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("set") && name.contains("coverage"),
                        "found " + m.getName() + " (INV-ASM-11)");
            }
        }

        @Test
        @DisplayName("an empty checklist reports zero coverage, not perfect coverage")
        void emptyIsZeroNotPerfect() {
            assertEquals(0, CoverageSummary.from(List.of()).coverageRatio().compareTo(BigDecimal.ZERO),
                    "'100% of nothing' is the same arithmetic error PRD-RSK-027 guards at posture level");
        }

        @Test
        @DisplayName("NOT_APPLICABLE counts as covered; NOT_ASSESSED does not")
        void coverageCountsTheRightThings() {
            var instance = published(4).instantiate(UUID.randomUUID(), UUID.randomUUID());
            var items = instance.items();
            instance.record(ItemResult.assessed(items.get(0).id(), ItemResult.Verdict.PASS, null, LEAD, T0));
            instance.record(ItemResult.assessed(items.get(1).id(), ItemResult.Verdict.FAIL, null, LEAD, T0));
            instance.record(ItemResult.assessed(items.get(2).id(), ItemResult.Verdict.NOT_APPLICABLE,
                    "no file upload in this application", LEAD, T0));
            // items.get(3) stays NOT_ASSESSED.

            var coverage = instance.coverage();
            assertEquals(4, coverage.itemsTotal());
            assertEquals(2, coverage.itemsAssessed());
            assertEquals(1, coverage.itemsNotApplicable());
            assertEquals(1, coverage.itemsNotAssessed());
            assertEquals(0, coverage.coverageRatio().compareTo(new BigDecimal("0.7500")));
            assertFalse(coverage.complete());
        }

        @Test
        @DisplayName("the presentation states what the ratio is a ratio of")
        void presentationCarriesTheDenominator() {
            var instance = published(10).instantiate(UUID.randomUUID(), UUID.randomUUID());
            String presentation = instance.coverage().presentation();
            assertTrue(presentation.contains("of 10 item(s)"),
                    "'340 of 351' is a claim a reader can evaluate; '97%' is one they cannot, and the second "
                            + "is the one that reaches a slide. Got: " + presentation);
        }

        @Test
        @DisplayName("coverage across checklists sums items rather than averaging ratios")
        void coverageSumsRatherThanAverages() {
            var assessment = planned();
            var small = assessment.addChecklist(published(10), UUID.randomUUID());
            // The large checklist is added and deliberately left untouched; it is the denominator under test.
            assessment.addChecklist(published(300), UUID.randomUUID());
            assessAll(small, ItemResult.Verdict.PASS);

            var coverage = assessment.coverage();
            assertEquals(310, coverage.itemsTotal());
            assertEquals(10, coverage.itemsAssessed());
            assertTrue(coverage.coverageRatio().compareTo(new BigDecimal("0.05")) < 0,
                    "averaging the two ratios would report 50%: a fully covered 10-item checklist would "
                            + "half-cancel an untouched 300-item one. Got " + coverage.coverageRatio());
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-ASM-17 / INV-ASM-18 — version immutability and pinning")
    class Versioning {

        @Test
        @DisplayName("an instance pins the definition version, and there is no setter")
        void instancePinsVersion() {
            var instance = published(2).instantiate(UUID.randomUUID(), UUID.randomUUID());
            assertEquals(3, instance.definitionVersion());
            for (Method m : ChecklistInstance.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("set") && name.contains("version"),
                        "an assessment that covered 340 of 351 items would, after an edit adding 20 items, "
                                + "appear to have covered 340 of 371 without anyone having changed the "
                                + "assessment (INV-ASM-17)");
            }
        }

        @Test
        @DisplayName("an item outside the pinned set is refused")
        void foreignItemRefused() {
            var instance = published(2).instantiate(UUID.randomUUID(), UUID.randomUUID());
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> instance.record(ItemResult.assessed(new UUID(999, 1), ItemResult.Verdict.PASS,
                            null, LEAD, T0)));
            assertTrue(ex.getMessage().contains("numerator and denominator"),
                    "an item added to the definition after instantiation is not part of this assessment's "
                            + "coverage denominator");
        }

        @Test
        @DisplayName("a DRAFT definition cannot be instantiated")
        void draftCannotBeInstantiated() {
            var draft = new ChecklistDefinition(UUID.randomUUID(), "ASVS-L2", 1,
                    List.of(new ChecklistDefinition.Item(new UUID(112, 1), "V1", "auth", "verify")));
            assertThrows(IllegalStateException.class,
                    () -> draft.instantiate(UUID.randomUUID(), UUID.randomUUID()),
                    "the item set would change underneath a live assessment");
        }

        @Test
        @DisplayName("two items sharing a code are refused")
        void duplicateItemCodesRefused() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> new ChecklistDefinition(UUID.randomUUID(), "X", 1, List.of(
                            new ChecklistDefinition.Item(new UUID(112, 1), "V1", "auth", "a"),
                            new ChecklistDefinition.Item(new UUID(112, 2), "V1", "auth", "b"))));
            assertTrue(ex.getMessage().contains("still look right"),
                    "a rollup keyed on the code would count one and lose the other, and the total would not "
                            + "reveal it");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-ASM-10 / -12 / -14 / -15 / -16 — the assessment aggregate")
    class Aggregate {

        @Test
        @DisplayName("INV-ASM-10: scope comes from asset ownership, not the assessor")
        void scopeComesFromAssetOwnership() {
            var assessment = Assessment.create(UUID.randomUUID(), TYPE, null, List.of(ASSET),
                    scopeAt(ASSET_OWNER_NODE), LEAD);
            assertEquals(ASSET_OWNER_NODE, assessment.scope().owningNodeId());
            assertFalse(assessment.scope().owningNodeId().equals(ASSESSOR_NODE),
                    "an assessor with broad scope would otherwise produce an assessment whose findings the "
                            + "owning team cannot see");
            for (Method m : Assessment.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("scope") && name.startsWith("set"), "found " + m.getName());
            }
        }

        @Test
        @DisplayName("INV-ASM-12: incomplete coverage cannot complete without an acknowledgement")
        void incompleteCoverageNeedsAnAcknowledgement() {
            var assessment = planned();
            var instance = assessment.addChecklist(published(5), UUID.randomUUID());
            instance.record(ItemResult.assessed(instance.items().get(0).id(), ItemResult.Verdict.PASS, null,
                    LEAD, T0));
            assessment.start(T0);

            var ex = assertThrows(IllegalStateException.class, () -> assessment.complete(null, T0));
            assertTrue(ex.getMessage().contains("we did not look"),
                    "'no findings' is indistinguishable from 'we did not look' unless coverage is recorded; "
                            + "got " + ex.getMessage());
            assertTrue(ex.getMessage().contains("of 5 item(s)"),
                    "and the refusal states the coverage rather than merely refusing");
        }

        @Test
        @DisplayName("an acknowledgement must name every unassessed item, not some of them")
        void acknowledgementMustBeComplete() {
            var assessment = planned();
            var instance = assessment.addChecklist(published(3), UUID.randomUUID());
            assessment.start(T0);

            var partial = new Assessment.IncompletenessAcknowledgement("ran out of time",
                    List.of(instance.items().get(0).id()), REVIEWER, T0);
            var ex = assertThrows(IllegalArgumentException.class, () -> assessment.complete(partial, T0));
            assertTrue(ex.getMessage().contains("understates the gap while appearing to disclose it"),
                    "got " + ex.getMessage());

            var full = new Assessment.IncompletenessAcknowledgement("ran out of time",
                    assessment.unassessedItemIds(), REVIEWER, T0);
            assessment.complete(full, T0);
            assertEquals(Assessment.State.AWAITING_REVIEW, assessment.state());
        }

        @Test
        @DisplayName("an acknowledgement on complete coverage is refused")
        void acknowledgementOnCompleteCoverageRefused() {
            var assessment = planned();
            var instance = assessment.addChecklist(published(2), UUID.randomUUID());
            assessAll(instance, ItemResult.Verdict.PASS);
            assessment.start(T0);

            assertThrows(IllegalArgumentException.class,
                    () -> assessment.complete(new Assessment.IncompletenessAcknowledgement("just in case",
                            List.of(new UUID(112, 1)), REVIEWER, T0), T0),
                    "recording one routinely is what makes the requirement meaningless on the assessment "
                            + "where it matters");

            assessment.complete(null, T0);
            assertEquals(Assessment.State.AWAITING_REVIEW, assessment.state());
        }

        @Test
        @DisplayName("an acknowledgement needs words, not a checkbox")
        void acknowledgementNeedsWords() {
            assertThrows(IllegalArgumentException.class,
                    () -> new Assessment.IncompletenessAcknowledgement("  ", List.of(new UUID(112, 1)),
                            REVIEWER, T0));
            assertThrows(IllegalArgumentException.class,
                    () -> new Assessment.IncompletenessAcknowledgement("ran out of time", List.of(),
                            REVIEWER, T0),
                    "an acknowledgement naming no items acknowledges nothing");
        }

        @Test
        @DisplayName("DOC-09 section 5: the reviewer cannot be the lead")
        void reviewerCannotBeTheLead() {
            var assessment = planned();
            var instance = assessment.addChecklist(published(1), UUID.randomUUID());
            assessAll(instance, ItemResult.Verdict.PASS);
            assessment.start(T0);
            assessment.complete(null, T0);

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> assessment.approve(LEAD, "satisfactory", List.of(), T0));
            assertTrue(ex.getMessage().contains("Self-review is not review"));

            assessment.approve(REVIEWER, "satisfactory", List.of(), T0);
            assertEquals(Assessment.State.COMPLETED, assessment.state());
        }

        @Test
        @DisplayName("INV-ASM-14: conditions are raised here and there is no way to close one")
        void conditionsAreClosedElsewhere() {
            var assessment = planned();
            var instance = assessment.addChecklist(published(1), UUID.randomUUID());
            assessAll(instance, ItemResult.Verdict.PASS);
            assessment.start(T0);
            assessment.complete(null, T0);

            var condition = new Assessment.Condition(UUID.randomUUID(),
                    "rotate the shared service account before release", REVIEWER, LocalDate.of(2026, 9, 1));
            assessment.approve(REVIEWER, "conditionally satisfactory", List.of(condition), T0);

            assertEquals(1, assessment.conditions().size());
            for (Method m : Assessment.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("condition") && (name.startsWith("close")
                                || name.startsWith("satisfy") || name.startsWith("resolve")),
                        "found " + m.getName() + ". Attaching closure to the assessment means conditions "
                                + "close when the assessment does, which is precisely the characteristic "
                                + "failure of architecture review (INV-ASM-14).");
            }
        }

        @Test
        @DisplayName("a condition needs an owner and a date")
        void conditionsNeedAnOwnerAndADate() {
            assertThrows(NullPointerException.class,
                    () -> new Assessment.Condition(UUID.randomUUID(), "rotate the account", null,
                            LocalDate.of(2026, 9, 1)),
                    "an ownerless condition has nobody to chase");
            assertThrows(NullPointerException.class,
                    () -> new Assessment.Condition(UUID.randomUUID(), "rotate the account", REVIEWER, null),
                    "without a date there is never a day it is late");
        }

        @Test
        @DisplayName("INV-ASM-15: findings are not held in the aggregate")
        void findingsAreNotHere() {
            for (Method m : Assessment.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("finding"),
                        "found " + m.getName() + ". Findings are produced through Ingestion (ADR-011); a "
                                + "second creation site would diverge from the first and produce different "
                                + "fingerprints for the same weakness (INV-VUL-06).");
            }
        }

        @Test
        @DisplayName("INV-ASM-16: manual effort does not overwrite derived effort")
        void effortFieldsAreSeparate() {
            var assessment = planned();
            assessment.recordDerivedEffort(new BigDecimal("6.00"));
            assessment.recordManualEffort(new BigDecimal("8.50"));

            var figure = assessment.effort();
            assertTrue(figure.manuallyAdjusted());
            assertEquals(0, figure.days().compareTo(new BigDecimal("8.50")));
            assertEquals(0, figure.derivedDays().compareTo(new BigDecimal("6.00")),
                    "overwriting destroys the comparison that makes the adjustment meaningful (ADR-021)");
        }

        @Test
        @DisplayName("abandonment retains partial coverage")
        void abandonmentRetainsCoverage() {
            var assessment = planned();
            var instance = assessment.addChecklist(published(351), UUID.randomUUID());
            for (int i = 0; i < 200; i++) {
                instance.record(ItemResult.assessed(instance.items().get(i).id(), ItemResult.Verdict.PASS,
                        null, LEAD, T0));
            }
            assessment.start(T0);
            assessment.abandon("the target was decommissioned mid-engagement", T0);

            assertEquals(Assessment.State.ABANDONED, assessment.state());
            assertEquals(200, assessment.coverage().itemsAssessed(),
                    "an abandoned assessment that examined 200 of 351 items is more informative than none "
                            + "(DOC-09 section 5)");
        }

        @Test
        @DisplayName("an assessment with no asset in scope cannot be created")
        void assetRequired() {
            assertThrows(IllegalArgumentException.class,
                    () -> Assessment.create(UUID.randomUUID(), TYPE, null, List.of(),
                            scopeAt(ASSET_OWNER_NODE), LEAD),
                    "there is nothing for its findings to attach to");
        }

        @Test
        @DisplayName("blocking requires an attribution")
        void blockingRequiresAttribution() {
            var assessment = planned();
            assessment.start(T0);
            assertThrows(IllegalArgumentException.class, () -> assessment.block("  ", T0),
                    "unattributed delay defaults to blaming the accountable team (PP-6)");
            assessment.block("THIRD_PARTY", T0);
            assertEquals(Assessment.State.BLOCKED, assessment.state());
        }
    }
}
