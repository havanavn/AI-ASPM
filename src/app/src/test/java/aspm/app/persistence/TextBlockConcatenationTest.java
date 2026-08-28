package aspm.app.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A Java text block strips trailing whitespace, and SQL built by concatenating one loses the space.
 *
 * <h2>The defect this exists to stop, measured rather than imagined</h2>
 *
 * On 2026-08-27 the planning endpoint returned 500 for every caller and the screen read "The
 * assessment plan could not be loaded." The cause was one missing space:
 *
 * <pre>
 *     WHERE w.state = 'PLANNED' AND """ + PlanWindows.IN_PLAN_HORIZON + """
 * </pre>
 *
 * The source has a space after {@code AND}. The compiled string does not — a text block strips
 * trailing whitespace from every line, including the line it closes on. Postgres received
 * {@code ANDw.ends_on >= ...} and answered {@code syntax error at or near "ANDw"}.
 *
 * <h2>Why a source scan and not a test that runs the query</h2>
 *
 * Running it would be better and is not available here: the query needs the whole schema, which is
 * sixty-nine migrations and a live engine, and the engine-backed tests in this repository use minimal
 * fixtures for exactly that reason. What is available is the observation that the mistake has a
 * *shape* — a text block closing on whitespace, immediately concatenated — and that shape is never
 * intentional. Where a space is genuinely wanted it should be written as one, which is what the fixed
 * call sites now do:
 *
 * <pre>
 *     ... AND""" + " " + PlanWindows.IN_PLAN_HORIZON + """
 * </pre>
 *
 * <p>Compilation cannot catch this and neither can a review that reads the source, because the source
 * looks correct. Only the compiler's own whitespace rule makes it wrong.
 */
class TextBlockConcatenationTest {

    /** Where to look. The whole application tier, because the trap is not specific to one package. */
    private static final Path ROOT = Path.of("src/main/java");

    @Test
    @DisplayName("no text block relies on trailing whitespace before a concatenation")
    void noStrippedSpaceBeforeConcatenation() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    int close = line.indexOf("\"\"\" +");
                    if (close <= 0) {
                        continue;
                    }
                    // Comments are not compiled, and the field this check exists to protect carries
                    // the broken pattern in its own explanation — the first thing this test found was
                    // that comment. Skipping them is not a weakening: a trailing space in a comment
                    // cannot reach a database.
                    String trimmed = line.stripLeading();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                        continue;
                    }
                    // The character immediately before the closing delimiter. A space here is the
                    // trap: the author wrote one, and the compiler will remove it.
                    char preceding = line.charAt(close - 1);
                    if (preceding == ' ' || preceding == '\t') {
                        offenders.add(file.getFileName() + ":" + (i + 1) + "  " + line.strip());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "a text block closes on whitespace and is then concatenated. The compiler strips that "
                        + "whitespace, so the two fragments join with no separator — which produced a "
                        + "500 on every planning request on 2026-08-27 (`ANDw.ends_on`). Write the "
                        + "separator explicitly instead: `...AND\"\"\" + \" \" + FRAGMENT`.\n  "
                        + String.join("\n  ", offenders));
    }

    @Test
    @DisplayName("the scan is looking at something")
    void notVacuous() throws IOException {
        assertTrue(Files.isDirectory(ROOT), ROOT + " is missing, so the check above passes vacuously");
        long files;
        try (Stream<Path> walk = Files.walk(ROOT)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).count();
        }
        // A guard on the guard. If a refactor moves the sources, the test above would report a clean
        // repository rather than an unread one, and that is the failure mode of every source scan.
        assertTrue(files > 50, "expected the application tier's sources here, found " + files
                + " java file(s). A source scan that reads nothing reports success.");
    }
}
