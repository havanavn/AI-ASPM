package aspm.app.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The planned assessment window keeps the properties {@code PRD-ASM-015} through {@code -018} name.
 *
 * <p>A source scan, for the reason {@code GraphScopeTest} gives for its own: an engine test covers the
 * paths a test calls, and what needs protecting here is the next method somebody adds to
 * {@link PlanWindows}. The write path is the whole of the authorization surface for this feature, and
 * a method added without the scope predicate would pass every behavioural test that does not happen to
 * call it.
 *
 * <p>Verified against a running deployment on 2026-08-25 as well: four windows created across an
 * application and one of its projects, the application row reporting four and the project row one; a
 * target outside the caller's scope answering 404; a caller without {@code asm.request.schedule}
 * answering 404; reversed dates answering 400; the cancel retaining the row; and the request count
 * unchanged at 211 across all of it.
 */
class PlanWindowTest {

    private static final Path SOURCE = Path.of("src/main/java/aspm/app/resource/PlanWindows.java");

    private static String source() throws IOException {
        assertTrue(Files.exists(SOURCE), SOURCE + " is missing, so this check would pass vacuously");
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("SEC-AUZ-016: every write resolves its target through the caller's own closure")
    void everyWriteIsScoped() throws IOException {
        String code = source();
        int updates = code.split("UPDATE assessment_plan_window", -1).length - 1;
        int inserts = code.split("INSERT INTO assessment_plan_window", -1).length - 1;
        int predicates = code.split("SELECT descendant_id FROM org_closure", -1).length - 1;
        assertTrue(inserts + updates > 0, "no write in a class that exists to write");
        // Every UPDATE carries its own EXISTS over the closure; the INSERT is guarded by
        // plannableTargets, which carries one too. So the predicate count must reach the write count.
        assertTrue(predicates >= updates + inserts,
                "a write appeared without a closure expansion beside it: " + (updates + inserts)
                        + " write(s), " + predicates + " predicate(s). A target the caller cannot "
                        + "reach must be unreachable at the statement, not filtered afterwards.");
        assertTrue(code.contains("scope.isEmpty()"),
                "a caller with no scope at all must short-circuit rather than build an empty ANY(?), "
                        + "which some engines read as matching nothing and some as matching all");
    }

    @Test
    @DisplayName("PRD-ASM-015: a window may only point at an application or a project")
    void onlyPlannableTypes() throws IOException {
        String code = source();
        assertEquals(java.util.Set.of("APPLICATION", "PROJECT"), PlanWindows.PLANNABLE_TYPES,
                "the plannable set is quoted in V070's comment and in the interface's badges; "
                        + "changing it here without changing those leaves three descriptions of one "
                        + "rule");
        assertTrue(code.contains("t.code = ANY (?)"),
                "the type restriction must be part of the same statement as the scope predicate. Two "
                        + "separate checks are two places for the next asset type to be added to, and "
                        + "only one of them will be");
    }

    @Test
    @DisplayName("PRD-ASM-017: a cancelled window is retained, never deleted")
    void cancellationIsAState() throws IOException {
        String code = source();
        assertFalse(code.contains("DELETE FROM assessment_plan_window"),
                "deleting a cancelled window makes a plan that was dropped indistinguishable from a "
                        + "plan that never existed — and only the first of those indicates a capacity "
                        + "problem, which is the finding a planning review exists to produce");
        assertTrue(code.contains("\"CANCELLED\""),
                "cancellation has to be a state this class can set, or the interface has no way to "
                        + "drop a window without deleting the row");
        assertTrue(code.contains("request_id = coalesce(?, w.request_id)"),
                "a window converted once keeps the request it became. Assigning rather than "
                        + "coalescing would let a later cancel erase the fact that the plan was acted "
                        + "on, which is not the same fact as the plan having been dropped");
    }

    @Test
    @DisplayName("PRD-ASM-016: nothing here writes an assessment request")
    void aPlanIsNotWork() throws IOException {
        String code = source();
        assertFalse(code.contains("INSERT INTO assessment_request"),
                "a window that creates a request would put a year of plan into every in-flight "
                        + "figure on the platform, and the assessor queue would stop being a queue");
        assertFalse(code.contains("INSERT INTO application_request"),
                "same argument through the link table");
    }

    @Test
    @DisplayName("PRD-ASM-018: conversion records the request, and does not raise it")
    void conversionIsRecordedNotPerformed() throws IOException {
        String code = source();
        assertTrue(code.contains("markConverted"),
                "the join from a window to the request it discharged is what makes "
                        + "planned-versus-actual answerable without a manual reconciliation");
        assertTrue(code.contains("Objects.requireNonNull(requestId"),
                "CONVERTED without a request identifier would claim work was raised while the join "
                        + "that proves it is empty, and planned-versus-actual would over-report");
    }

    @Test
    @DisplayName("SEC-AUD-009: a bulk plan emits an event per window AND a summary")
    void bulkIsAuditedBothWays() throws IOException {
        String code = source();
        assertTrue(code.contains("DomainChangeKind.CREATED"),
                "the per-item event. Without it, 'when did THIS window enter the plan' is answerable "
                        + "only from the row, which records the current state and not the date the "
                        + "window was first planned for");
        assertTrue(code.contains("AuditEventType.BULK_EXECUTED"),
                "the summary event SEC-AUD-009 requires alongside the items");
        assertTrue(code.contains("RETURNING id"),
                "a per-item event needs the item's identifier, which is why the insert is a loop "
                        + "rather than a batch. A batch here would silently drop back to a summary "
                        + "only, and the requirement would be unmet in a way nothing detects.");
    }

    @Test
    @DisplayName("a write that matched nothing still closes its transaction")
    void noMatchDoesNotBecomeAFivehundred() throws IOException {
        String code = source();
        // MEASURED, not hypothesised: this returned 500 for a window identifier that does not exist,
        // because TenantConnections counts an UPDATE that matched no rows as a write and refuses to
        // close a written transaction without commit(). The 404 the endpoint intended never happened.
        int empties = code.split("return Optional.empty\\(\\);", -1).length - 1;
        int commits = code.split("connection.commit\\(\\);", -1).length - 1;
        assertTrue(commits >= 3,
                "each of update(), setState() and create() has to end its transaction — including on "
                        + "the path where the statement matched nothing. Found " + commits
                        + " commit(s) against " + empties + " empty return(s).");
        for (String fragment : List.of(
                "// Nothing matched, so there is nothing to record",
                "// See update(): an UPDATE that matched nothing")) {
            assertTrue(code.contains(fragment),
                    "the reason the no-match path commits has to stay beside it, or the next person "
                            + "reads a commit after a failed update as a mistake and removes it: "
                            + fragment);
        }
    }

    @Test
    @DisplayName("a batch is bounded, and says so rather than truncating")
    void bulkIsBounded() throws IOException {
        String code = source();
        assertTrue(PlanWindows.MAX_PER_REQUEST >= 400,
                "a hundred applications reviewed quarterly is four hundred windows and that is an "
                        + "ordinary year. A bound below it would refuse the case the bulk path exists "
                        + "for.");
        assertTrue(code.contains("throw new IllegalArgumentException(\"a single request may create"),
                "an over-large batch is refused with its size stated. Truncating would save a plan "
                        + "nobody asked for and report success for it");
        assertFalse(code.contains(".subList(0, MAX_PER_REQUEST)"),
                "truncation is the failure mode this bound exists to avoid, not its implementation");
    }
}
