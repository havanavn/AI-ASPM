package aspm.app.resource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An asserted review stays distinguishable from an observed one. {@code PRD-ASM-019} to {@code -022}.
 *
 * <p>A source scan over the write path and over the migration that defines the view, for the reason
 * {@code PlanWindowTest} gives: what needs protecting is the next method somebody adds, and the next
 * consumer who reads {@code last_full_review_at} without noticing there is a source column beside it.
 *
 * <p>Verified against a running deployment on 2026-08-27: an application reading {@code NEVER} was
 * given an attestation covering April 2026 and moved to {@code CURRENT} with
 * {@code last_full_review_source = ATTESTED}, {@code full_review_count} unchanged at 0,
 * {@code attested_review_count} 1, and a next-due date twelve months after the asserted end. A
 * future-dated attestation was refused by the CHECK constraint.
 */
class ReviewAttestationTest {

    private static final Path SERVICE =
            Path.of("src/main/java/aspm/app/resource/ReviewAttestations.java");
    private static final Path MIGRATION = Path.of(
            "../module/assessment-impl/src/main/resources/db/migration/V071__review_attestation.sql");

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), path + " is missing, so this check would pass vacuously");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("PRD-ASM-020: the observed count never absorbs asserted reviews")
    void observedCountKeepsItsMeaning() throws IOException {
        String sql = read(MIGRATION);
        assertTrue(sql.contains("coalesce(c.full_review_count, 0)              AS full_review_count"),
                "full_review_count must still come from `completed`, which reads "
                        + "application_full_review and therefore only counts reviews the platform "
                        + "observed. Consumers already read this column; changing what the name means "
                        + "is what product principle 10 forbids.");
        assertTrue(sql.contains("attested_review_count"),
                "the asserted reviews need their own count beside the observed one, or a reader has "
                        + "no way to see that any of the coverage rests on somebody's word");
        assertFalse(sql.contains("coalesce(c.full_review_count, 0) + coalesce(at.attested"),
                "summing the two into one column is exactly the mixture PRD-ASM-020 forbids: the "
                        + "figure would then be part evidence and part recollection with nothing "
                        + "able to separate them again");
    }

    @Test
    @DisplayName("PRD-ASM-020: a date is never shown without a source to explain it")
    void everyDateCarriesItsSource() throws IOException {
        String sql = read(MIGRATION);
        assertTrue(sql.contains("last_full_review_source"),
                "last_full_review_at now takes the later of observed and asserted, so the view has "
                        + "to say which one produced it. Without that column the interface would "
                        + "present hearsay and evidence identically.");
        // One expression, three consumers. The status, the due date and the source all read the same
        // CTE, because three separate greatest() calls over the same two inputs is three chances for
        // one of them to be written differently and disagree on screen.
        assertTrue(sql.contains("effective AS ("),
                "the combined date is computed once in a CTE. Recomputing it per column is how a "
                        + "status comes to contradict the date printed beside it.");
        assertTrue(sql.contains("greatest("),
                "the later of the two sources is the answer to \"when was this last reviewed\"");
    }

    @Test
    @DisplayName("PRD-ASM-021: an assertion is attributed, and withdrawal keeps the record")
    void attributionAndWithdrawal() throws IOException {
        String sql = read(MIGRATION);
        String code = read(SERVICE);
        assertTrue(sql.contains("attested_by         uuid        NOT NULL"),
                "an assertion about coverage with nobody's name on it cannot be questioned, because "
                        + "there is nobody to ask when it proves wrong");
        assertFalse(code.contains("DELETE FROM application_review_attestation"),
                "withdrawal is a state, not a delete. A claim made and retracted is a different "
                        + "finding from a claim never made — the first says a control was believed to "
                        + "exist, which is what a post-incident review looks for.");
        assertTrue(code.contains("withdrawing an attestation needs a reason"),
                "\"it was wrong\" and \"the evidence did not support it\" have different "
                        + "consequences for the figure the assertion was propping up");
        assertTrue(sql.contains("WHERE at.withdrawn_at IS NULL"),
                "a withdrawn assertion keeps its row and must drop out of the coverage figures");
    }

    @Test
    @DisplayName("PRD-ASM-022: an assertion cannot claim work that has not finished")
    void noFutureReviews() throws IOException {
        String sql = read(MIGRATION);
        String code = read(SERVICE);
        assertTrue(sql.contains("ck_review_attestation__not_future"),
                "a future-dated assertion would discharge an obligation that has not come due, "
                        + "moving an application out of the population owed work — the one way this "
                        + "capability could corrupt the figures it exists to correct");
        assertTrue(code.contains("cannot have finished in the future"),
                "refused in the service as well, so the caller gets a sentence rather than a "
                        + "constraint violation. The CHECK stays because it cannot be forgotten by "
                        + "the next writer.");
    }

    @Test
    @DisplayName("SEC-AUZ-016: the write path resolves its target through the caller's own closure")
    void writesAreScoped() throws IOException {
        String code = read(SERVICE);
        int writes = code.split("INSERT INTO application_review_attestation", -1).length - 1
                + code.split("UPDATE application_review_attestation", -1).length - 1;
        int predicates = code.split("SELECT descendant_id FROM org_closure", -1).length - 1;
        assertTrue(writes > 0, "no write in a class that exists to write");
        assertTrue(predicates >= writes,
                "a write appeared without a closure expansion beside it: " + writes + " write(s), "
                        + predicates + " predicate(s)");
        assertTrue(code.contains("ty.code = 'APPLICATION'"),
                "the obligation is per application, so an attestation against a project would "
                        + "discharge nothing while sitting in the record as though it had");
    }

    @Test
    @DisplayName("attesting is its own authority, not folded into an existing one")
    void itsOwnPermission() throws IOException {
        String code = read(SERVICE);
        assertTrue(ReviewAttestations.ATTEST.startsWith("asm."),
                "the permission belongs to the assessment module's namespace");
        for (String reused : List.of("asm.request.qa", "asm.request.approve", "asm.request.create",
                "asm.request.update")) {
            assertNotEquals(reused, ReviewAttestations.ATTEST,
                    "reusing " + reused + " would grant the authority to move a coverage figure to "
                            + "everybody who already holds it, which nobody decided. ADR-027 fixes "
                            + "the catalogue at product level so a new authority is handed out on "
                            + "purpose.");
        }
        assertTrue(code.contains("ADR-027"),
                "the reason this permission is new rather than reused has to stay beside it, or the "
                        + "next reader folds it into a neighbouring one to save a grant");
    }

    @Test
    @DisplayName("a write that matched nothing still closes its transaction")
    void noMatchDoesNotBecomeAFivehundred() throws IOException {
        String code = read(SERVICE);
        // The same trap that produced a 500 on the plan-window path, guarded here before it could.
        int commits = code.split("connection.commit\\(\\);", -1).length - 1;
        assertTrue(commits >= 4,
                "attest() and withdraw() each have TWO paths that must end the transaction: the one "
                        + "that wrote and the one whose statement matched nothing. TenantConnections "
                        + "counts a statement that ran as a write. Found " + commits + " commit(s).");
        assertTrue(code.contains("the same trap"),
                "the reason a commit follows a failed match has to stay beside it, or the next "
                        + "reader removes it as a mistake");
    }
}
