package aspm.app.resource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.kernel.tenantcontext.contract.ScopePredicate;
import aspm.sharedkernel.OrgNodeId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A create lands inside the caller's scope. {@code SEC-AUZ-016}, PP-4, ADR-036 class B.
 *
 * <p><b>What this is guarding against, in the past tense.</b> {@code ResourceEndpoint.create}
 * authorized the collection permission — "may this principal create assets at all" — and then inserted
 * whatever {@code owning_node_id} or {@code parent_id} arrived in the body. Nothing compared the named
 * node to the caller's scope, and row-level security bounds a write to the tenant and nothing narrower.
 * A principal authorized for one division could file an asset into another's estate, or hang a subtree
 * under somebody else's node and move their rollups. Class B declares
 * {@code PATH_AND_BODY_IDENTIFIERS} and only the path half was ever performed.
 *
 * <p>It was latent rather than exploited: the same two endpoints were refused by the schema for
 * unrelated reasons and had never inserted a row. Fixing those made this reachable, which is why the
 * check landed in the same change.
 */
class CreateScopeTest {

    private static final UUID FINTECH = UUID.fromString("22222222-0000-4000-8000-000000000001");
    private static final UUID VINPEARL = UUID.fromString("22222222-0000-4000-8000-000000000002");

    private static ScopePredicate pinnedTo(UUID... nodes) {
        return new ScopePredicate(List.of(nodes).stream().map(OrgNodeId::new).toList(), false);
    }

    private static final Set<String> OWNER = Set.of("owning_node_id");

    @Test
    @DisplayName("a create inside the caller's scope is allowed")
    void inScopeIsAllowed() {
        assertDoesNotThrow(() -> ResourceEndpoint.requireInScope(OWNER, pinnedTo(FINTECH),
                Map.of("display_name", "Payments Portal", "owning_node_id", FINTECH.toString())));
    }

    @Test
    @DisplayName("a create aimed at another branch is refused")
    void outOfScopeIsRefused() {
        assertThrows(ResourceEndpoint.OutOfScope.class,
                () -> ResourceEndpoint.requireInScope(OWNER, pinnedTo(FINTECH),
                        Map.of("display_name", "Someone Else's Portal",
                                "owning_node_id", VINPEARL.toString())));
    }

    @Test
    @DisplayName("omitting the scope-bearing field is refused, not treated as unowned")
    void absentIsRefused() {
        // Otherwise the way around the check is to leave the field out: the row lands outside every
        // scope, which for an org node is a new root beside the tenant's hierarchy.
        assertThrows(ResourceEndpoint.OutOfScope.class,
                () -> ResourceEndpoint.requireInScope(OWNER, pinnedTo(FINTECH),
                        Map.of("display_name", "Unowned Portal")));
    }

    @Test
    @DisplayName("an unrestricted caller may create anywhere, including unowned")
    void unrestrictedIsUnaffected() {
        ScopePredicate everything = new ScopePredicate(List.of(), true);

        assertDoesNotThrow(() -> ResourceEndpoint.requireInScope(OWNER, everything,
                Map.of("owning_node_id", VINPEARL.toString())));
        assertDoesNotThrow(() -> ResourceEndpoint.requireInScope(OWNER, everything,
                Map.of("display_name", "Deliberately unowned")));
    }

    @Test
    @DisplayName("a malformed identifier is a validation error, not a silent pass")
    void malformedIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceEndpoint.requireInScope(OWNER, pinnedTo(FINTECH),
                        Map.of("owning_node_id", "not-a-uuid")));
    }

    @Test
    @DisplayName("assets and org nodes declare the field that decides where the row lands")
    void theGroupsDeclareIt() {
        assertEquals(Set.of("owning_node_id"), ResourceCatalogue.ASSETS.scopeBearingOnCreate());
        assertEquals(Set.of("parent_id"), ResourceCatalogue.ORG_NODES.scopeBearingOnCreate());
        assertTrue(ResourceCatalogue.ASSET_TYPES.scopeBearingOnCreate().isEmpty(),
                "an asset type is tenant-wide vocabulary and belongs to no node");
    }

    @Test
    @DisplayName("assets are scoped on the column the interface has always scoped on")
    void assetsScopeOnTheOwner() {
        // The two doors onto the same rows must agree. The API scoped on scope_node_id — the embedded
        // historical descriptor, immutable per CON-DAT-009 — while the interface scoped on
        // owning_node_id, and the two are written by different code paths. Each half of the estate was
        // invisible through one of the doors.
        assertEquals(Optional.of("owning_node_id"), ResourceCatalogue.ASSETS.scopeColumn());
    }

    @Test
    @DisplayName("a scope-bearing field the caller cannot set would be a check on nothing")
    void declarationMustBeWritable() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceGroup(
                "probes", "asset", Optional.of("owning_node_id"), Set.of("owning_node_id"),
                "display_name", Map.of("id", ResourceGroup.ColumnKind.UUID),
                Set.of(), Set.of("display_name"), Set.of(), body -> Map.of(),
                "ast.asset.read", "ast.asset.create", "ast.asset.update"));
    }
}
