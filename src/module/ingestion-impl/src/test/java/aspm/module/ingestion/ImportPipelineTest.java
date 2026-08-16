package aspm.module.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.ingestion.domain.AssetClassAssignment;
import aspm.module.ingestion.domain.ImportSession;
import aspm.module.ingestion.domain.QuarantinedRecord;
import aspm.sharedkernel.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** DOC-09 section 15, DOC-11 sections 7 and 9. */
class ImportPipelineTest {

    private static final TenantId TENANT = new TenantId(new UUID(40, 1));
    private static final Instant T0 = Instant.parse("2026-08-04T00:00:00Z");

    private static ImportSession session() {
        return new ImportSession(UUID.randomUUID(), TENANT, "idem-1", "SARIF", "2.1.0", T0,
                Duration.ofHours(24));
    }

    @Nested
    @DisplayName("PRD-ING-038 — a parse failure ingests nothing")
    class ParseFailure {

        @Test
        @DisplayName("there is no partial-ingest path from PARSING")
        void noPartialIngestPathExists() {
            // The assertion is about the machine's shape: no method moves PARSING to a completed state.
            for (var m : ImportSession.class.getMethods()) {
                String name = m.getName().toLowerCase(java.util.Locale.ROOT);
                assertFalse(name.contains("partial") && !name.contains("count"),
                        "found " + m.getName() + ". A truncated record set reaching normalization is "
                                + "indistinguishable to closure logic from records having been removed, and "
                                + "closure that treats absence as removal is the failure product principle 1 "
                                + "exists to prevent (PRD-ING-038).");
            }
        }

        @Test
        @DisplayName("a failed session ingested nothing and cannot be reversed")
        void failedSessionIngestedNothing() {
            var s = session();
            s.startParsing();
            s.parseFailed("record 12,001 exceeded the 64 KB per-record limit");

            assertEquals(ImportSession.State.FAILED, s.state());
            assertFalse(s.ingestedAnything());
            assertTrue(s.failureReason().orElseThrow().contains("64 KB"),
                    "DOC-11 section 9 requires the limit to be NAMED; 'rejected' is not actionable");
            assertThrows(IllegalStateException.class, () -> s.reverse(T0.plusSeconds(60), true),
                    "reversing a FAILED session would suggest something was undone when nothing was done");
        }

        @Test
        @DisplayName("a rejection before parsing names its reason")
        void rejectionNamesItsReason() {
            var s = session();
            s.rejectBeforeParsing("format version 3.0.0 unsupported; supported: 2.1.0, 2.0.0");
            assertEquals(ImportSession.State.FAILED, s.state());
            assertTrue(s.failureReason().orElseThrow().contains("supported"),
                    "DOC-11 section 9: an unsupported version rejection names the supported versions");
        }
    }

    @Nested
    @DisplayName("PRD-ING-041 — counts by disposition, never only a total")
    class Dispositions {

        @Test
        @DisplayName("a session reports every disposition separately")
        void everyDispositionIsReported() {
            var s = session();
            s.startParsing();
            s.parsed(40_000, 3);
            s.normalized(Map.of(
                    ImportSession.Disposition.INGESTED, 39_000,
                    ImportSession.Disposition.UPDATED, 900,
                    ImportSession.Disposition.REOPENED, 50,
                    ImportSession.Disposition.MERGED, 47));

            var counts = s.dispositionCounts();
            assertEquals(6, counts.size(), "all six dispositions are present, including zeroes");
            assertEquals(3, counts.get(ImportSession.Disposition.QUARANTINED));
            assertEquals(39_000, counts.get(ImportSession.Disposition.INGESTED));
            // "A total of 39,997 out of 40,000 is not actionable; knowing that three were quarantined for one
            // reason is." So there is no total() accessor that could be reported alone.
            for (var m : ImportSession.class.getMethods()) {
                assertFalse(m.getName().equals("total") || m.getName().equals("totalRecords"),
                        "a total accessor invites reporting only the total, which PRD-ING-041 forbids");
            }
        }

        @Test
        @DisplayName("the terminal state is derived from the quarantine count, not passed in")
        void terminalStateIsDerived() {
            var withQuarantine = session();
            withQuarantine.startParsing();
            withQuarantine.parsed(100, 1);
            withQuarantine.normalized(Map.of(ImportSession.Disposition.INGESTED, 99));
            assertEquals(ImportSession.State.COMPLETED_WITH_QUARANTINE, withQuarantine.state(),
                    "a caller reporting COMPLETED on a session that quarantined records would hide the queue "
                            + "PRD-ING-039 requires to be resolvable");

            var clean = session();
            clean.startParsing();
            clean.parsed(100, 0);
            clean.normalized(Map.of(ImportSession.Disposition.INGESTED, 100));
            assertEquals(ImportSession.State.COMPLETED, clean.state());
        }

        @Test
        @DisplayName("PRD-ING-020: per-stage counts make a session diagnosable to a stage")
        void perStageCountsExist() {
            var s = session();
            s.startParsing();
            s.parsed(40_000, 0);
            s.normalized(Map.of(ImportSession.Disposition.INGESTED, 40_000));
            assertEquals(40_000, s.stageRecordCounts().get("PARSE"),
                    "a session that ingested 12,000 of 40,000 records must be diagnosable to a stage; without "
                            + "per-stage counts the failure could be in any of six places");
        }
    }

    @Nested
    @DisplayName("DOC-09 section 15 — reversal")
    class Reversal {

        @Test
        @DisplayName("reversal requires a permission distinct from import")
        void reversalNeedsADistinctPermission() {
            var s = completed();
            assertThrows(IllegalStateException.class, () -> s.reverse(T0.plusSeconds(60), false),
                    "reversal removes findings, so a principal who may import must not thereby be able to "
                            + "un-import");
            s.reverse(T0.plusSeconds(60), true);
            assertEquals(ImportSession.State.REVERSED, s.state());
        }

        @Test
        @DisplayName("reversal is refused after the window closes")
        void reversalWindowIsEnforced() {
            var s = completed();
            assertThrows(IllegalStateException.class, () -> s.reverse(T0.plusSeconds(90_000), true),
                    "beyond the window, findings have been triaged, assigned and commented on, and restoring "
                            + "prior state would discard work done since");
        }

        private ImportSession completed() {
            var s = session();
            s.startParsing();
            s.parsed(10, 0);
            s.normalized(Map.of(ImportSession.Disposition.INGESTED, 10));
            return s;
        }
    }

    @Nested
    @DisplayName("PRD-ING-039 — quarantine that cannot be resolved is deletion with extra steps")
    class Quarantine {

        private QuarantinedRecord record(QuarantinedRecord.Reason reason, List<String> fields) {
            return new QuarantinedRecord(UUID.randomUUID(), TENANT, UUID.randomUUID(), reason, fields,
                    "{\"ruleId\":null,\"severity\":\"SEVERE\"}", T0);
        }

        @Test
        @DisplayName("a quarantined record carries its raw content, so it is correctable without the source")
        void rawContentIsRetained() {
            var r = record(QuarantinedRecord.Reason.SCHEMA_VALIDATION, List.of("ruleId"));
            assertTrue(r.rawContent().contains("ruleId"),
                    "PRD-ING-039 requires resubmission WITHOUT re-importing the source, which needs the content");
            assertEquals(List.of("ruleId"), r.failingFields());
        }

        @Test
        @DisplayName("a schema failure that names no field is not constructible")
        void schemaFailureMustNameTheField() {
            assertThrows(IllegalArgumentException.class,
                    () -> record(QuarantinedRecord.Reason.SCHEMA_VALIDATION, List.of()),
                    "'failed validation' is not correctable, so it is not resolvable, so it is deletion");
        }

        @Test
        @DisplayName("a discard requires a stated reason")
        void discardRequiresAReason() {
            var r = record(QuarantinedRecord.Reason.SCHEMA_VALIDATION, List.of("ruleId"));
            assertThrows(NullPointerException.class, () -> r.discard(null),
                    "a record that quietly vanished is an unknown coverage gap; one discarded for a stated "
                            + "reason is a known one");
            r.discard("the source tool emits this record for every suppressed rule; expected");
            assertEquals(QuarantinedRecord.State.DISCARDED, r.state());
        }

        @Test
        @DisplayName("a settled record cannot be re-settled")
        void settledRecordIsFinal() {
            var r = record(QuarantinedRecord.Reason.SCHEMA_VALIDATION, List.of("ruleId"));
            r.resolve("ruleId supplied; resubmitted");
            assertThrows(IllegalStateException.class, () -> r.discard("changed my mind"));
        }
    }

    @Nested
    @DisplayName("PRD-ING-032, PRD-ING-033 — asset class is assigned by the parser")
    class AssetClass {

        @Test
        @DisplayName("infrastructure findings do not contribute to application posture")
        void infrastructureIsSeparate() {
            assertTrue(AssetClassAssignment.AssetClass.APPLICATION.contributesToApplicationPosture());
            assertFalse(AssetClassAssignment.AssetClass.INFRASTRUCTURE.contributesToApplicationPosture(),
                    "an application posture figure dominated by operating system patch findings tells an "
                            + "application team nothing they can act on");
            assertFalse(AssetClassAssignment.AssetClass.CLOUD.contributesToApplicationPosture());
        }

        @Test
        @DisplayName("PRD-ING-033: container findings are classified by SUBJECT, not by where they were found")
        void containerFindingsSplitBySubject() {
            assertEquals(AssetClassAssignment.AssetClass.APPLICATION,
                    AssetClassAssignment.forContainerFinding(
                            AssetClassAssignment.ContainerSubject.APPLICATION_DEPENDENCY));
            assertEquals(AssetClassAssignment.AssetClass.INFRASTRUCTURE,
                    AssetClassAssignment.forContainerFinding(
                            AssetClassAssignment.ContainerSubject.BASE_IMAGE_OR_OS),
                    "an application dependency in an image is fixed by the application team changing a "
                            + "manifest; a base image CVE is fixed by the platform team changing a base image. "
                            + "One class for both routes half of them to someone who cannot act.");
        }

        @Test
        @DisplayName("there is no default subject, because a default would collapse the split")
        void noDefaultSubjectExists() {
            assertThrows(NullPointerException.class,
                    () -> AssetClassAssignment.forContainerFinding(null));
            boolean hasNoArgOverload = java.util.Arrays.stream(AssetClassAssignment.class.getMethods())
                    .anyMatch(m -> m.getName().equals("forContainerFinding") && m.getParameterCount() == 0);
            assertFalse(hasNoArgOverload,
                    "a default would put every container finding in one class, which is exactly the failure "
                            + "PRD-ING-033 names");
        }

        @Test
        @DisplayName("PRD-ING-032: there is no per-finding class override")
        void noPerFindingOverrideExists() {
            for (var m : AssetClassAssignment.class.getMethods()) {
                String name = m.getName().toLowerCase(java.util.Locale.ROOT);
                assertFalse(name.startsWith("set") || name.contains("override") || name.contains("reclassify"),
                        "found " + m.getName() + ". A tenant reclassifying infrastructure findings as "
                                + "application would inflate or deflate their application posture at will — a "
                                + "gaming path through classification (DOC-28 section 13.2).");
            }
        }
    }
}
