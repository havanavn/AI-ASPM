package aspm.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Prompt 19 — the ten gates, the partition plan, and the fifteen runbooks. DOC-15 sections 9.1, 5.2 and 15. */
class PipelineAndRunbookTest {

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-15 section 9.1 — ten gates, every one blocking")
    class Gates {

        @Test
        @DisplayName("there are ten, and each one blocks")
        void tenBlockingGates() {
            assertEquals(10, PipelineGate.all().size(),
                    "DOC-15 section 9.1 tabulates ten. A gate missing from this enum is a gate the pipeline "
                            + "does not run, and nothing else would notice.");
            for (PipelineGate gate : PipelineGate.all()) {
                assertTrue(gate.blocks(), gate + " warns instead of blocking");
                assertTrue(gate.evaluate(false, Optional.empty()).isPresent(),
                        gate + " failed and the pipeline proceeded. A warning is a violation with extra steps "
                                + "(OPS-DEP-026).");
            }
        }

        @Test
        @DisplayName("blocking is not a field, so it cannot be turned off in a diff that reads as configuration")
        void blockingIsNotConfigurable() {
            boolean hasBlockingField = java.util.Arrays.stream(PipelineGate.class.getDeclaredFields())
                    .anyMatch(f -> !f.isEnumConstant()
                            && (f.getType() == boolean.class || f.getName().toLowerCase(
                                    java.util.Locale.ROOT).contains("severity")));
            assertFalse(hasBlockingField,
                    "a blocking or severity field is the mechanism by which a gate becomes advisory during a "
                            + "release crunch and stays advisory afterwards");
        }

        @Test
        @DisplayName("OPS-DEP-026: a bypass is a recorded, reviewed exception naming the gate and the reason")
        void anExemptionIsRecordedAndReviewed() {
            var valid = new PipelineGate.Exemption(PipelineGate.CONTAINER_SCAN, "release-eng", "sec-lead",
                    "Base image advisory has no fixed version; digest pinned to the last unaffected build.", 2);
            assertTrue(PipelineGate.CONTAINER_SCAN.evaluate(false, Optional.of(valid)).isEmpty());

            assertThrows(IllegalArgumentException.class,
                    () -> new PipelineGate.Exemption(PipelineGate.CONTAINER_SCAN, "same", "same",
                            "A reason long enough to pass the length floor and no longer.", 2),
                    "a self-approved exception is the bypass with a form attached, and the form is what makes "
                            + "it look reviewed");
            assertThrows(IllegalArgumentException.class,
                    () -> new PipelineGate.Exemption(PipelineGate.CONTAINER_SCAN, "a", "b", "urgent", 2),
                    "'urgent' tells the next reader nothing about whether the exception still applies");
            assertThrows(IllegalArgumentException.class,
                    () -> new PipelineGate.Exemption(PipelineGate.CONTAINER_SCAN, "a", "b",
                            "A reason long enough to pass the length floor and no longer.", 0),
                    "an exemption without an expiry is a permanently disabled gate nobody remembers disabling");
        }

        @Test
        @DisplayName("an exemption is per gate")
        void anExemptionDoesNotCoverAnotherGate() {
            var forContainer = new PipelineGate.Exemption(PipelineGate.CONTAINER_SCAN, "release-eng",
                    "sec-lead", "Base image advisory has no fixed version; digest pinned.", 2);
            var blocked = PipelineGate.SECRET_SCANNING.evaluate(false, Optional.of(forContainer));
            assertTrue(blocked.isPresent(), "an exemption for one gate covered another");
            assertTrue(blocked.orElseThrow().contains("per gate"));
        }

        @Test
        @DisplayName("the two gates whose failures are invisible to review are present")
        void theInvisibleFailureGatesArePresent() {
            assertTrue(PipelineGate.all().contains(PipelineGate.STATIC_ANALYSIS));
            assertTrue(PipelineGate.all().contains(PipelineGate.CORPUS_VALIDATION),
                    "these two exist because the failures they catch are invisible to review — this "
                            + "repository's register found twenty-seven unregistered requirements, a real "
                            + "identifier used in an example, and an unregistered class code, none of them by "
                            + "reading (OPS-DEP-026)");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-15 section 5.2 — partitions")
    class Partitions {

        @Test
        @DisplayName("OPS-DEP-012: every hash count carries a substantive sizing basis")
        void everyHashCountRecordsItsBasis() {
            assertEquals(9, PartitionPlan.hashPartitioned().size(),
                    "nine hash-partitioned tables across V006, V011 and V012");
            for (var table : PartitionPlan.hashPartitioned()) {
                assertTrue(table.sizingBasis().strip().length() >= 40, table.tableName() + " records no basis");
                assertEquals("OQ-015", table.openQuestion(),
                        table.tableName() + " does not mark the working assumption. OQ-015 BLOCKS "
                                + "IMPLEMENTATION because the count is irreversible after production data — an "
                                + "unmarked assumption is an undocumented one.");
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new PartitionPlan.HashPartitioned("t", 32, "default", "OQ-015"),
                    "'default' satisfies 'recorded' and answers neither question a resize asks");
        }

        @Test
        @DisplayName("every count is the same, because a divergent modulus defeats partitionwise join")
        void countsAgree() {
            Set<Integer> counts = new HashSet<>(PartitionPlan.hashCounts().values());
            assertEquals(1, counts.size(),
                    "the counts diverge: " + PartitionPlan.hashCounts() + ". Tables joined on tenant_id need "
                            + "the same modulus or the join is cross-partition, and correcting it later "
                            + "redistributes every row (CON-DAT-035).");
        }

        @Test
        @DisplayName("OPS-DEP-011: every range table is provisioned ahead of need and alerts")
        void everyRangeTableProvisionsAndAlerts() {
            assertEquals(5, PartitionPlan.rangePartitioned().size());
            for (var table : PartitionPlan.rangePartitioned()) {
                assertTrue(table.leadMonths() >= 1, table.tableName() + " provisions on the deadline");
                assertTrue(table.alertingQuery().contains("partition_runway_report()"),
                        table.tableName() + " has no alert. Provisioning without alerting is the gap V013 "
                                + "closes: four of these five had an ensure_* function and no runway function, "
                                + "each correct on its own, and the omission was only visible once the set was "
                                + "enumerated.");
            }
        }

        @Test
        @DisplayName("the tables whose loss is permanent are named as such")
        void unreconstructableTablesAreMarked() {
            var permanent = PartitionPlan.rangePartitioned().stream()
                    .filter(PartitionPlan.RangePartitioned::unreconstructable)
                    .map(PartitionPlan.RangePartitioned::tableName)
                    .toList();
            assertTrue(permanent.contains("work_item_state_transition"),
                    "DOC-15 section 4 gives the transition log the same property as the three unreorderable "
                            + "build blocks: an insert rejected during a partition outage is gone, not delayed");
            assertTrue(permanent.contains("audit_event"),
                    "a missing audit_event partition fails every audited operation under CON-PLT-021 — a total "
                            + "write outage from an omitted maintenance task");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-15 section 15 — fifteen runbooks")
    class Runbooks {

        @Test
        @DisplayName("there are fifteen, each with all five sections")
        void fifteenCompleteRunbooks() {
            List<Runbook> runbooks = Runbook.all();
            assertEquals(15, runbooks.size(),
                    "DOC-15 section 15 tabulates fifteen. The count is asserted because a runbook nobody "
                            + "wrote is indistinguishable from one nobody needed.");
            for (Runbook runbook : runbooks) {
                for (Runbook.Phase phase : Runbook.Phase.values()) {
                    assertTrue(runbook.steps().containsKey(phase),
                            runbook.name() + " has no " + phase + " section");
                }
            }
        }

        @Test
        @DisplayName("OPS-DEP-050: containment precedes diagnosis in the cross-tenant runbook")
        void containmentBeforeDiagnosis() {
            Runbook crossTenant = Runbook.all().stream()
                    .filter(r -> r.severity() == Runbook.Severity.HIGHEST)
                    .filter(r -> r.name().startsWith("Cross-tenant"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the cross-tenant runbook is absent"));

            crossTenant.assertContainmentPrecedesDiagnosis();
            assertTrue(crossTenant.steps().get(Runbook.Phase.IMMEDIATE_ACTION).startsWith("Contain first"),
                    "diagnosing before containing extends the exposure, and the exposure is one customer's "
                            + "vulnerability inventory disclosed to another — unrecoverable and disclosable "
                            + "(OPS-DEP-050, DOC-26 T2)");
        }

        @Test
        @DisplayName("the check reads what the runbook instructs, not a flag the author sets")
        void anInvestigativeImmediateActionIsRejected() {
            Map<Runbook.Phase, String> steps = new EnumMap<>(Runbook.Phase.class);
            steps.put(Runbook.Phase.DETECTION, "The assertion fails.");
            steps.put(Runbook.Phase.IMMEDIATE_ACTION,
                    "Investigate whether the assertion is a false positive before disrupting service.");
            steps.put(Runbook.Phase.DIAGNOSIS, "Identify the read path.");
            steps.put(Runbook.Phase.REMEDIATION, "Fix and re-run the isolation suite.");
            steps.put(Runbook.Phase.AUDIT_RECORD, "Every step, with timestamps.");

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> new Runbook("Draft", "alert", Runbook.Severity.HIGHEST, steps, Optional.empty())
                            .assertContainmentPrecedesDiagnosis());
            assertTrue(ex.getMessage().contains("correct for almost every other alert"),
                    "the author of that immediate action believes they are containing, which is why a flag "
                            + "would not catch it — and their instinct is right everywhere else");
        }

        @Test
        @DisplayName("OPS-DEP-049: no runbook is rehearsed, so none supports a service level commitment")
        void nothingIsRehearsedYet() {
            for (Runbook runbook : Runbook.all()) {
                assertTrue(runbook.rehearsedOn().isEmpty(),
                        runbook.name() + " records a rehearsal date. No rehearsal has happened, and recording "
                                + "one here would assert something that did not occur.");
                assertThrows(IllegalStateException.class, runbook::relyOnInServiceLevelCommitment,
                        runbook.name() + " can back a service level commitment without rehearsal");
            }
        }

        @Test
        @DisplayName("a rehearsed runbook may back a commitment")
        void rehearsalIsWhatLiftsTheBlock() {
            Runbook rehearsed = new Runbook(Runbook.all().getFirst().name(),
                    Runbook.all().getFirst().trigger(), Runbook.all().getFirst().severity(),
                    Runbook.all().getFirst().steps(), Optional.of(LocalDate.of(2026, 1, 1)));
            assertEquals(rehearsed, rehearsed.relyOnInServiceLevelCommitment());
        }

        @Test
        @DisplayName("every runbook states a trigger, because one nobody knows to open is unused")
        void everyRunbookHasATrigger() {
            for (Runbook runbook : Runbook.all()) {
                assertFalse(runbook.trigger().isBlank(), runbook.name() + " has no trigger");
            }
        }
    }
}
