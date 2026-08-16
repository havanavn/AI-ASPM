package aspm.module.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.ingestion.domain.MigrationImport;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Migration import. DOC-11 section 12, {@code PRD-ING-048} to {@code -052}, ADR-028. */
class MigrationImportTest {

    private static final Instant NOW = Instant.parse("2026-08-05T09:00:00Z");
    private static final Instant HISTORICAL = NOW.minus(Duration.ofDays(900));
    private static final UUID PRINCIPAL = new UUID(220, 1);

    private static MigrationImport.Authorization authorized() {
        return new MigrationImport.Authorization(
                MigrationImport.Authorization.REQUIRED_PERMISSION, true, true);
    }

    private static MigrationImport.MigratedRecord record(MigrationImport.Authorship authorship,
            Set<String> dropped) {
        return new MigrationImport.MigratedRecord("JIRA-1042", "COMMENT", authorship, HISTORICAL,
                Map.of("body", "agreed, accepting the risk"), dropped);
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-ING-051 — the gate")
    class Gate {

        @Test
        @DisplayName("all three conditions are validated to construct an authorization")
        void allThreeConditions() {
            var wrongPermission = assertThrows(IllegalArgumentException.class,
                    () -> new MigrationImport.Authorization("ing.import.execute", true, true));
            assertTrue(wrongPermission.getMessage().contains("scanner report"),
                    "sharing the permission would give every principal who can upload a scanner report the "
                            + "ability to write comments attributed to other people at historical timestamps");

            assertThrows(IllegalArgumentException.class,
                    () -> new MigrationImport.Authorization(
                            MigrationImport.Authorization.REQUIRED_PERMISSION, false, true),
                    "step-up is required: it writes on behalf of other people at historical timestamps");

            var noScope = assertThrows(IllegalArgumentException.class,
                    () -> new MigrationImport.Authorization(
                            MigrationImport.Authorization.REQUIRED_PERMISSION, true, false));
            assertTrue(noScope.getMessage().contains("already exist"),
                    "a bulk historical write is the most attractive place to skip scope validation, because "
                            + "the records 'already exist' somewhere else");
        }

        @Test
        @DisplayName("execute takes the authorization as a value, not a caller's assurance")
        void authorizationIsAValue() {
            assertThrows(NullPointerException.class,
                    () -> MigrationImport.execute(null, List.of()),
                    "taking it as a value means the three conditions were validated to construct it");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-ING-048 to -050 — authorship and timestamps")
    class Provenance {

        @Test
        @DisplayName("the original timestamp is required, not the migration date")
        void originalTimestampPreserved() {
            var migrated = record(MigrationImport.Authorship.resolved("bob@example", PRINCIPAL), Set.of());
            assertEquals(HISTORICAL, migrated.originalTimestamp(),
                    "a comment thread where every entry is attributed to 'migration' on the migration date is "
                            + "unusable as history — the information that makes it valuable is precisely who "
                            + "said what, when (PRD-ING-048)");
        }

        @Test
        @DisplayName("PRD-ING-050: an unresolved author becomes a MARKED placeholder, never a real principal")
        void unresolvedAuthorIsMarked() {
            var unresolved = MigrationImport.Authorship.unresolved("someone@departed.example");
            assertFalse(unresolved.isResolved());
            assertTrue(unresolved.unresolvedPlaceholder().orElseThrow().contains("unresolved external author"),
                    "attributing a comment to the wrong person falsifies the record, and the falsification is "
                            + "invisible to a reader");
            assertTrue(unresolved.unresolvedPlaceholder().orElseThrow().contains("someone@departed.example"),
                    "the placeholder carries the external identifier, so a reader can go and ask the "
                            + "incumbent system rather than knowing only that somebody unknown said this");
        }

        @Test
        @DisplayName("authorship is exactly one of resolved or placeholder")
        void authorshipIsExclusive() {
            assertThrows(IllegalArgumentException.class,
                    () -> new MigrationImport.Authorship("x", java.util.Optional.empty(),
                            java.util.Optional.empty()),
                    "neither leaves the record unattributed");
            assertThrows(IllegalArgumentException.class,
                    () -> new MigrationImport.Authorship("x", java.util.Optional.of(PRINCIPAL),
                            java.util.Optional.of("[unresolved]")),
                    "both would let a presentation pick whichever it found");
        }

        @Test
        @DisplayName("PRD-ING-049: there is no way to build an unflagged migrated record")
        void everyRecordIsFlagged() {
            assertTrue(record(MigrationImport.Authorship.resolved("bob", PRINCIPAL), Set.of()).migrated());
            for (Method m : MigrationImport.MigratedRecord.class.getMethods()) {
                if (m.getName().equals("migrated")) {
                    assertEquals(0, m.getParameterCount(),
                            "migration writes historical authorship, which is ALSO the capability to fabricate "
                                    + "a record of a decision never made (DOC-26 section 8). The flag is the "
                                    + "control.");
                }
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new MigrationImport.MigratedRecord("  ", "COMMENT",
                            MigrationImport.Authorship.resolved("bob", PRINCIPAL), HISTORICAL, Map.of(),
                            Set.of()),
                    "a blank external identifier makes the flag uncheckable against the source");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-ING-052 — fidelity, reported per record")
    class Fidelity {

        @Test
        @DisplayName("the count is not obtainable without its losses")
        void countCarriesItsLosses() {
            for (Method m : MigrationImport.FidelityReport.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.equals("recordsimported") || name.equals("successcount"),
                        "found " + m.getName() + ". A migration reporting only success conceals what was "
                                + "lost, and what was lost is discovered months later when someone looks for "
                                + "a decision that is no longer recorded (PRD-ING-052).");
            }

            var report = MigrationImport.execute(authorized(), List.of(
                    record(MigrationImport.Authorship.resolved("bob", PRINCIPAL),
                            Set.of("customFieldX", "attachments")),
                    record(MigrationImport.Authorship.unresolved("someone@departed.example"), Set.of())));

            assertTrue(report.summary().contains("2 record(s) migrated"));
            assertTrue(report.summary().contains("customFieldX"),
                    "the dropped fields are named, not counted");
            assertTrue(report.summary().contains("someone@departed.example"),
                    "and so are the unresolved authors");
            assertFalse(report.lossless());
        }

        @Test
        @DisplayName("the summary states the OQ-025 working assumption at the point of use")
        void workingAssumptionIsVisible() {
            var report = MigrationImport.execute(authorized(),
                    List.of(record(MigrationImport.Authorship.resolved("bob", PRINCIPAL), Set.of())));
            assertTrue(report.summary().contains("OQ-025"),
                    "the incumbent is unidentified, so the adapter is generic over a structured export and "
                            + "the fidelity limits are per source — a reader of this report needs to know "
                            + "that the dropped list is this source's, not a general one");
            assertTrue(report.lossless(), "and a genuinely lossless migration says so");
        }
    }
}
