package aspm.app.inventory;

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
 * The estate graph applies the scope predicate at retrieval. {@code SEC-AUZ-016}, {@code SEC-AUZ-020}.
 *
 * <p>A source scan, and the reason is the one {@code TenantConnectionsTest} gives for its own: an
 * engine test covers the paths a test calls, and what needs protecting here is the next query
 * somebody adds to this class. A graph is an aggregate over two structures, and
 * {@code SEC-AUZ-016} requires the predicate to be part of retrieval rather than a filter applied
 * afterwards — a filter is what somebody writes when the query was easier without one.
 *
 * <p>Verified against a running deployment on 2026-08-25 as well: a credential scoped to one team
 * saw seven of an application's eight neighbours with {@code boundary = true}, and a direct request
 * for the eighth answered 404.
 */
class GraphScopeTest {

    private static final Path SOURCE =
            Path.of("src/main/java/aspm/app/inventory/GraphQuery.java");

    private static String source() throws IOException {
        assertTrue(Files.exists(SOURCE), SOURCE + " is missing, so this check would pass vacuously");
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("SEC-AUZ-016: every asset read is bounded by the caller's own closure")
    void assetsAreScoped() throws IOException {
        String code = source();
        int reads = code.split("FROM asset a ", -1).length - 1;
        int predicates = code.split("ASSET_VISIBLE", -1).length - 1
                + code.split("a.owning_node_id = \\?", -1).length - 1;
        assertTrue(predicates > 0, "no scope predicate in a class that reads assets");
        assertTrue(code.contains("owning_node_id IN (SELECT descendant_id FROM org_closure"),
                "the predicate must be the closure expansion the inventory lists use. A different "
                        + "spelling is a second definition of scope, and the two diverge silently.");
        assertTrue(code.contains("OR a.owning_node_id IS NULL"),
                "an unowned asset is visible to the whole tenant — PRD-AST-011's unclaimed queue "
                        + "exists to get it claimed. Omitting the null case would make the graph "
                        + "disagree with the list that shows it.");
        assertTrue(reads >= 1 && predicates >= reads - 1,
                "an asset read appeared without a scope predicate beside it: " + reads
                        + " read(s), " + predicates + " predicate(s)");
    }

    @Test
    @DisplayName("SEC-AUZ-016: organization nodes are bounded the same way")
    void orgNodesAreScoped() throws IOException {
        String code = source();
        assertTrue(code.contains("n.id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))")
                        || code.contains("(n.id IN (SELECT descendant_id FROM org_closure"),
                "an organization node must be resolved through the caller's own expansion, not by "
                        + "identifier alone");
    }

    @Test
    @DisplayName("the boundary flag carries no count and no identity")
    void boundaryDisclosesOnlyThatSomethingIsThere() throws IOException {
        String code = source();
        assertTrue(code.contains("boolean boundary"),
                "the flag is the whole mechanism for saying a graph is partial");
        for (String leak : List.of("boundaryCount", "hiddenCount", "invisibleCount",
                "hiddenIds", "boundaryIds")) {
            assertFalse(code.contains(leak),
                    "a count or an identity at the scope boundary is an oracle: it lets a caller "
                            + "measure what they may not see. Found: " + leak);
        }
    }

    @Test
    @DisplayName("SEC-AUZ-020: an unreachable identifier is empty, not a distinguishable refusal")
    void unreachableIsIndistinguishable() throws IOException {
        String code = source();
        assertTrue(code.contains("return Optional.empty()"),
                "the query returns empty and the endpoint answers 404 for both non-existence and "
                        + "non-authorization; a distinct refusal here would differentiate them");
        assertFalse(code.contains("throw new SecurityException"),
                "a thrown refusal would be observable as a different status or timing");
    }

    @Test
    @DisplayName("a neighbourhood, not the estate: every walk is bounded to one hop")
    void oneHopOnly() throws IOException {
        String code = source();
        assertFalse(code.contains("asset_composition"),
                "asset_composition is the transitive closure of the graph. Reading it here would "
                        + "return a subtree of unknown size for one click, which is the payload "
                        + "this class exists not to send — the interface expands one hop at a time.");
        assertTrue(code.contains("LIMIT 200"),
                "the owned-asset list needs a bound: a node owning ten thousand assets must not "
                        + "produce a response nobody can render");
    }

    @Test
    @DisplayName("PRD-UIX-022: an unmeasured count is null, so the join that supplies it is LEFT")
    void unmeasuredIsNotZero() throws IOException {
        String code = source();
        assertTrue(code.contains("LEFT JOIN application_posture"),
                "an inner join would drop every asset nothing has been found in — a node missing "
                        + "from the graph because it is clean is the inverse of what this is for");
        assertEquals(0, code.split("coalesce\\(p.finding_open", -1).length - 1,
                "coalescing the count to zero would make 'nothing measured' and 'measured, nothing "
                        + "found' the same number, which product principle 1 forbids");
    }
}
