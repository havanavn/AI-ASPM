package aspm.app.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * The workload page's authorization predicate. {@code SEC-AUZ-016}, product principle 4.
 *
 * <p><b>What this is guarding against, in the past tense.</b> Every query behind the workload dashboard
 * set the tenant and composed no organization predicate at all, so the page aggregated the whole tenant
 * for anybody who could open it. It was measured rather than reasoned: a PENTESTER scoped to one
 * division received the same headline, flow, stage, waiting and finding figures as a tenant-wide
 * administrator, identical on every key. The page calls itself the team's own workload.
 *
 * <p>The queries are literal SQL with no placeholders, so the predicate arrives as a token that is
 * expanded and counted in one place. These tests assert the two properties that make the token safe: a
 * scope-bearing query that forgets it fails loudly, and the widening for a caller who sees every root
 * applies only to that caller.
 */
class WorkloadScopeTest {

    @Test
    @DisplayName("a query with no scope token is refused rather than run unscoped")
    void anUnscopedQueryIsRefused() {
        // The whole defect in one line: this SQL is exactly what the class used to send.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> WorkloadQuery.expand("SELECT count(*) FROM assessment_request", false));
        assertTrue(refused.getMessage().contains("aggregate the whole tenant"),
                "the refusal must say what would have happened, or the next person deletes the guard: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("every token becomes one predicate and one bound array")
    void tokensAndBindingsAgree() {
        var expanded = WorkloadQuery.expand(
                "SELECT (SELECT count(*) FROM finding f WHERE @scope(f)), "
                        + "(SELECT count(*) FROM assessment_request r WHERE @scope(r)) "
                        + "FROM asset a WHERE @scope(a)", false);
        assertEquals(3, expanded.scopeArrays(),
                "the binding count comes from the same expansion as the SQL, so they cannot disagree");
        assertEquals(3, count(expanded.sql(), "scope_node_id IN"));
        assertEquals(3, count(expanded.sql(), "?"), "one placeholder per predicate");
        assertFalse(expanded.sql().contains("@scope("), "no token may survive expansion");
        // The alias travels with the predicate: a query joining two scope-bearing tables must not have
        // both narrowed on the same one.
        assertTrue(expanded.sql().contains("f.scope_node_id"));
        assertTrue(expanded.sql().contains("r.scope_node_id"));
        assertTrue(expanded.sql().contains("a.scope_node_id"));
    }

    @Test
    @DisplayName("rows with no organization are admitted only for a caller who sees every root")
    void unscopedRowsAreAdmittedNarrowly() {
        // A finding with no organization cannot be shown to an organization-scoped reader without
        // inventing an organization for it, and scope is derived, never asserted (PP-4).
        assertFalse(WorkloadQuery.predicate("f", false).contains("IS NULL"));
        // For a reader entitled to the whole tree, excluding them is a silent undercount: one request,
        // seven findings and forty-two assets in this deployment carry no organization, and the assets
        // figure would have fallen from sixty-seven to twenty-five with nothing on screen to say why.
        assertTrue(WorkloadQuery.predicate("f", true).contains("f.scope_node_id IS NULL"));
    }

    @Test
    @DisplayName("every scope-bearing table in the class is read through a token")
    void noQueryReadsAScopedTableWithoutOne() throws IOException {
        // A source scan, because the guard above only fires for a query that RUNS. A new query added
        // with no token would throw the first time somebody opened the page — this fails the build
        // instead, which is the difference between a defect found by a reviewer and one found by a user.
        String source = Files.readString(
                Path.of("src/main/java/aspm/app/resource/WorkloadQuery.java"), StandardCharsets.UTF_8);
        // Split by METHOD rather than by a character window. A first version scanned the 600 characters
        // after each FROM and reported `waiting()` as unscoped — its predicate is real but sits past
        // four joins and a lateral, further away than any window that does not also swallow the next
        // query. A method is the unit the predicate belongs to, so it is the unit to scan.
        List<String> methods = new ArrayList<>();
        Matcher starts = Pattern.compile("\n    (public|private) ").matcher(source);
        int previous = -1;
        while (starts.find()) {
            if (previous >= 0) {
                methods.add(source.substring(previous, starts.start()));
            }
            previous = starts.start();
        }
        if (previous >= 0) {
            methods.add(source.substring(previous));
        }

        Pattern scopeBearing = Pattern.compile("\\bFROM (assessment_request|finding|asset)\\b");
        List<String> unscoped = new ArrayList<>();
        int scanned = 0;
        for (String method : methods) {
            // Only the SQL, so the class's own prose about the defect is not scanned as if it were one.
            String sql = String.join(" ", method.lines()
                    .filter(line -> line.contains("\""))
                    .map(String::strip).toList());
            if (!scopeBearing.matcher(sql).find()) {
                continue;
            }
            scanned++;
            if (!sql.contains("@scope(")) {
                unscoped.add(sql.substring(0, Math.min(90, sql.length())));
            }
        }
        assertTrue(scanned >= 6, "the scan matched only " + scanned + " queries, so it is no longer "
                + "looking at what this class sends");
        assertTrue(unscoped.isEmpty(),
                "these read a scope-bearing table with no @scope token near it: " + unscoped);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }
}
