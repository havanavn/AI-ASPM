package aspm.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S13 of DOC-16 section 3 — every requirement identifier referenced in code or tests exists in the
 * register.
 *
 * <p>{@code PRD-PLT-012}. This corpus's own tooling found three defects that reading did not, and one of
 * them was "real requirement identifiers used in illustrative examples". The same failure in code is
 * worse: a comment citing a plausible but unregistered identifier looks like justification and is a dangling reference, so a
 * reviewer checking whether the code satisfies its stated requirement is checking against nothing.
 *
 * <p>Skips where the register is unavailable rather than passing, because a check that silently passes
 * when its input is missing is the vacuous-check failure prompt 2's demonstration exposed.
 */
class RequirementReferenceTest {

    /** The DOC-00 section 6.2 identifier form. Classes per the register's own column. */
    private static final Pattern IDENTIFIER = Pattern.compile(
            "\\b(PRD|CFG|NFR|LIC|CON|SEC|INT|OPS|TST|RISK)-([A-Z]{3})-(\\d{3})\\b");

    /** Domain codes the corpus validator itself excludes as illustrative. */
    private static final Set<String> EXCLUDED_DOMAINS = Set.of("XMP", "RES");

    private static Set<String> registered;
    private static Path sourceRoot;

    @BeforeAll
    static void loadRegister() throws IOException {
        String root = System.getProperty("aspm.corpusRoot", "");
        Assumptions.assumeFalse(root.isBlank(),
                "SKIPPED: aspm.corpusRoot is not set, so S13 cannot resolve the register");

        Path register = Path.of(root, "_traceability", "requirements.csv");
        Assumptions.assumeTrue(Files.exists(register),
                "SKIPPED: " + register + " is absent. Run tools/generate_register.py.");

        registered = new TreeSet<>();
        List<String> lines = Files.readAllLines(register, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            int comma = line.indexOf(',');
            if (comma > 0) {
                registered.add(line.substring(0, comma).trim());
            }
        }
        sourceRoot = Path.of(root, "ai-aspm-docs", "src");
        if (!Files.exists(sourceRoot)) {
            sourceRoot = Path.of(root, "src");
        }
    }

    @Test
    @DisplayName("S13 / PRD-PLT-012: every requirement identifier in Java and SQL sources is registered")
    void everyReferencedIdentifierIsRegistered() throws IOException {
        assertTrue(registered.size() > 1000,
                "the register holds " + registered.size() + " identifiers, which is too few to be the "
                        + "corpus register; S13 would pass vacuously");

        List<String> dangling = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".java") || name.endsWith(".sql");
                    })
                    .filter(p -> !p.toString().contains("/build/"))
                    .toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = IDENTIFIER.matcher(content);
                Set<String> seen = new TreeSet<>();
                while (matcher.find()) {
                    String id = matcher.group();
                    if (EXCLUDED_DOMAINS.contains(matcher.group(2)) || !seen.add(id)) {
                        continue;
                    }
                    if (!registered.contains(id)) {
                        dangling.add(sourceRoot.relativize(file) + ": " + id);
                    }
                }
            }
        }

        assertTrue(dangling.isEmpty(),
                "PRD-PLT-012: a comment citing an unregistered identifier looks like justification and "
                        + "resolves to nothing, so a reviewer checking whether the code satisfies its "
                        + "stated requirement is checking against nothing.\n  "
                        + String.join("\n  ", dangling));
    }
}
