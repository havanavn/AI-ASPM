package aspm.app.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every workflow event a migration defines has a label in every bundle.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>V027 replaced the twenty-two-state request workflow with six states, and introduced two event
 * codes — {@code close} and {@code reopen} — without adding their message keys. On a build carrying
 * {@link Messages#getOr} the dropdown rendered the raw code; on one without it,
 * {@code Messages#get} threw {@code MissingResourceException} and the request page returned 500.
 * Every request whose only available move was one of those two was unreachable.
 *
 * <p>Nothing caught it. The workflow is DATA and the labels are CODE, so the two can drift, and the
 * only place they meet is a page render that no test drove with the new workflow in place.
 *
 * <h2>What it reads</h2>
 *
 * <p>The migration SQL, not the database. A test needing a live PostgreSQL to check that a string
 * exists would not run in the unit suite, which is exactly where this needs to fail — before the
 * change is packaged, not after it is deployed. The event codes are extracted from the
 * {@code workflow_transition} inserts of every migration that writes them.
 *
 * <p>The consequence for anybody adding a workflow: define the transition in SQL and add
 * {@code request.event.<code>} to all three bundles, or this fails by name.
 */
final class WorkflowLabelTest {

    /** Where migrations live, relative to the Gradle working directory of the :app project. */
    private static final List<String> MIGRATION_ROOTS = List.of(
            "../module/assessment-impl/src/main/resources/db/migration");

    /**
     * The event code is the sixth value of a {@code workflow_transition} insert tuple, and the rows
     * are written one per line in the migrations that create them. Matching the column name would be
     * more robust and is not possible against a positional VALUES list, so this matches the shape
     * those inserts actually have: {@code state_x, state_y, 'event_code',} on one line.
     */
    private static final Pattern EVENT = Pattern.compile(
            "state_[a-z]+,\\s*state_[a-z]+,\\s*'([a-z_]+)'");

    @Test
    @DisplayName("PRD-WRK-034: every workflow event a migration defines has a label in every bundle")
    void everyEventIsLabelled() throws IOException {
        java.util.Set<String> events = new TreeSet<>();
        for (String root : MIGRATION_ROOTS) {
            java.io.File directory = new java.io.File(root);
            java.io.File[] files = directory.listFiles((d, name) -> name.endsWith(".sql"));
            if (files == null) {
                continue;
            }
            for (java.io.File file : files) {
                String sql = java.nio.file.Files.readString(file.toPath());
                Matcher matcher = EVENT.matcher(sql);
                while (matcher.find()) {
                    events.add(matcher.group(1));
                }
            }
        }
        assertTrue(events.size() >= 6, "no workflow event was found in the migrations, so this test "
                + "would pass over an empty set. Either the insert shape changed or the path is "
                + "wrong; both make the check silently vacuous. Found: " + events);

        List<String> missing = new ArrayList<>();
        for (String bundle : List.of("messages.properties", "messages_en.properties",
                "messages_vi.properties")) {
            Properties properties = new Properties();
            try (InputStream stream = WorkflowLabelTest.class
                    .getResourceAsStream("/aspm/app/ui/" + bundle)) {
                assertTrue(stream != null, bundle + " is not on the test classpath");
                properties.load(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
            }
            for (String event : events) {
                if (!properties.containsKey("request.event." + event)) {
                    missing.add(bundle + " → request.event." + event);
                }
            }
        }

        assertTrue(missing.isEmpty(),
                "a workflow migration defines an event with no label. On a build without "
                        + "Messages#getOr this throws MissingResourceException and the request page "
                        + "returns 500; with it, the interface shows a raw code like 'reopen'. "
                        + "Add the key to each bundle listed:\n  "
                        + String.join("\n  ", missing));
    }
}
