package aspm.app.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A suggestion knows what it was about, and a record that moves says so. ADR-005, PP-1.
 *
 * <h2>What these assertions are protecting</h2>
 *
 * <p>The ledger compares a suggestion's recorded {@code subject_row_version} against the subject's
 * version now. That comparison is only as good as the write paths that increment the version — and
 * when this was built, the finding lifecycle incremented nothing. Every transition changed the
 * record and left the counter where it was, so a suggestion about a finding that had since been
 * fixed, accepted or reopened still reported itself as current. It was found by driving the whole
 * loop against a database rather than by reading either half.
 *
 * <p>So the ratchet is here: a transition added later that forgets the version does not silently
 * switch staleness off for that path. Source scans rather than behaviour, because behaviour needs
 * the whole schema and this is the property a reviewer cannot see by looking at one method.
 */
class SuggestionFreshnessTest {

    private static final Path LIFECYCLE = Path.of("src/main/java/aspm/app/resource/FindingLifecycle.java");
    private static final Path LEDGER = Path.of("src/main/java/aspm/app/resource/SuggestionLedger.java");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Every finding lifecycle transition moves row_version")
    void everyTransitionBumpsTheVersion() throws IOException {
        String source = read(LIFECYCLE);
        // Each statement that writes lifecycle_state is a transition. Anchored on that rather than on
        // a method name, because the next transition will be a new method and the assertion has to
        // find it without being told.
        Matcher writes = Pattern.compile("UPDATE finding\\b(?:(?!\"\"\").)*?lifecycle_state\\s*=",
                Pattern.DOTALL).matcher(source);
        List<String> missing = new ArrayList<>();
        int transitions = 0;
        while (writes.find()) {
            transitions++;
            // The statement runs to the closing text block. Look inside it for the version.
            int end = source.indexOf("\"\"\"", writes.end());
            String statement = source.substring(writes.start(), end < 0 ? source.length() : end);
            if (!statement.contains("row_version = row_version + 1")
                    && !statement.contains("row_version = f.row_version + 1")) {
                missing.add(statement.lines().limit(2).reduce("", String::concat).trim());
            }
        }
        assertTrue(transitions >= 6,
                "the scan found " + transitions + " lifecycle writes, which means it has stopped "
                        + "matching them rather than that they have stopped existing");
        assertEquals(List.of(), missing,
                "these transitions change the record without moving its version. Two things break "
                        + "silently when that happens: optimistic concurrency in "
                        + "AssessmentService.updateFinding, which guards on the version an edit form "
                        + "was opened with, and the AI ledger's staleness check, which is a "
                        + "comparison against exactly this counter");
    }

    @Test
    @DisplayName("ADR-005: promotion of a stale suggestion is refused in the statement, not before it")
    void promotionGuardIsInTheWrite() throws IOException {
        String source = read(LEDGER);
        int promote = source.indexOf("SET state = 'PROMOTED'");
        assertTrue(promote > 0, "the promotion statement has moved; this test has to find it");
        String statement = source.substring(promote, source.indexOf("\"\"\"", promote));
        assertTrue(statement.contains("subject_row_version IS NOT NULL"),
                "a suggestion whose subject version was never recorded cannot be promoted: its "
                        + "freshness is unknown, and unknown is not current (PP-1)");
        assertTrue(statement.contains("subject_row_version >= coalesce("),
                "the comparison against the subject's current version belongs in the UPDATE. In a "
                        + "preceding read it is a check somebody can commit a change between");
        assertTrue(statement.contains("2147483647"),
                "a subject that cannot be resolved must fail the comparison rather than pass it: a "
                        + "withdrawn subject is the case where a stale promotion is most likely");
    }

    @Test
    @DisplayName("Rejection is deliberately not guarded, so a reviewer can always clear the queue")
    void rejectionStaysAvailable() throws IOException {
        String source = read(LEDGER);
        int reject = source.indexOf("SET state = 'REJECTED'");
        assertTrue(reject > 0);
        String statement = source.substring(reject, source.indexOf("\"\"\"", reject));
        assertTrue(!statement.contains("subject_row_version"),
                "refusing to let somebody dismiss a stale suggestion would leave it in the queue "
                        + "permanently, which is the failure the whole freshness idea exists to "
                        + "prevent");
    }

    @Test
    @DisplayName("PP-1: the coverage caveat fires on measurements that expired, not only on missing ones")
    void coverageCoversFreshnessToo() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/aspm/app/resource/TriageAgent.java"), StandardCharsets.UTF_8);
        int having = source.indexOf("HAVING count(*) FILTER (WHERE c.full_review_status = 'NEVER')");
        assertTrue(having > 0, "the coverage caveat's trigger condition has moved");
        String clause = source.substring(having, source.indexOf("ORDER BY", having));
        assertTrue(clause.contains("'OVERDUE'"),
                "an organization that reviewed everything two years ago produces no caveat unless "
                        + "OVERDUE fires it, and its figures then read as fully covered");
        assertTrue(clause.contains("freshness_threshold_days"),
                "a bill of materials from six months ago describes a dependency set that has moved; "
                        + "without this the composition figures are stale and say nothing about it");
        assertTrue(source.contains("\"sbom-threshold-days:\""),
                "the grounding must carry the threshold the staleness is measured against — a "
                        + "sentence asserting staleness against a number the reader cannot see is "
                        + "the kind of claim the grounding list exists to prevent");
    }

    @Test
    @DisplayName("A re-run withdraws a stale pending suggestion rather than being blocked by it")
    void proposeSupersedesRatherThanSkips() throws IOException {
        String source = read(LEDGER);
        assertTrue(source.contains("withdraw(connection, existing"),
                "propose() deduplicates on (kind, subject, PENDING). Without withdrawing a stale one "
                        + "the guard keeps the outdated claim in front of the reviewer forever and "
                        + "the current one can never be written");
        assertTrue(source.contains("'WITHDRAWN'"),
                "the state existed in the check constraint from the start with no writer, and it "
                        + "means precisely this — nobody judged it, it stopped applying");
    }
}
