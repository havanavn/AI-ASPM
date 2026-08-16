package aspm.module.assetinventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.kernel.schemaregistry.contract.AttributeDataType;
import aspm.kernel.schemaregistry.contract.AttributeSchema;
import aspm.module.assetinventory.application.AttributeValidation;
import aspm.module.assetinventory.application.ScopeAuthorizedAssetGraph;
import aspm.module.assetinventory.contract.AssetPermissions;
import aspm.module.assetinventory.domain.AssetGraphTraversal;
import aspm.module.organizationscope.contract.ScopeResolutionQuery;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.PrincipalId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** INV-AST-17 wired to real scope resolution, INV-AST-02, and the ast.* catalogue. */
class AuthorizedTraversalAndAttributesTest {

    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");
    private static final UUID SERVICE = new UUID(20, 1);
    private static final UUID API = new UUID(20, 2);
    private static final UUID FOREIGN_DOMAIN = new UUID(20, 3);
    private static final OrgNodeId OWN_NODE = new OrgNodeId(new UUID(21, 1));
    private static final OrgNodeId FOREIGN_NODE = new OrgNodeId(new UUID(21, 2));

    private static AssetGraphTraversal.EdgeSource graph() {
        var edges = List.of(
                new AssetGraphTraversal.Edge(SERVICE, API, "EXPOSES", NOW.minusSeconds(60), null),
                new AssetGraphTraversal.Edge(API, FOREIGN_DOMAIN, "PUBLISHED_ON", NOW.minusSeconds(60), null));
        return assetId -> edges.stream()
                .filter(e -> e.fromAssetId().equals(assetId) || e.toAssetId().equals(assetId))
                .toList();
    }

    private static ScopeAuthorizedAssetGraph.AssetOwnershipLookup ownership() {
        return assetId -> {
            if (assetId.equals(FOREIGN_DOMAIN)) {
                return Optional.of(FOREIGN_NODE);
            }
            return Optional.of(OWN_NODE);
        };
    }

    private static ScopeResolutionQuery resolution(ScopeResolutionQuery.Resolution result) {
        return new ScopeResolutionQuery() {
            @Override
            public Resolution resolveCurrent(PrincipalId principal, String permissionCode) {
                assertEquals(AssetPermissions.ASSET_READ.code(), permissionCode,
                        "traversal must resolve scope for ast.asset.read, not for a broader permission");
                return result;
            }

            @Override
            public HistoricalVerdict wasAuthorized(PrincipalId principal, String permissionCode,
                    aspm.sharedkernel.ScopeDescriptor descriptor, Instant at) {
                return new HistoricalVerdict(false, "not exercised");
            }
        };
    }

    @Nested
    @DisplayName("INV-AST-17 wired to real scope resolution")
    class AuthorizedTraversal {

        @Test
        @DisplayName("a foreign-owned asset reached by a legitimate edge is pruned")
        void foreignOwnedAssetIsPruned() {
            var graphService = new ScopeAuthorizedAssetGraph(
                    resolution(ScopeResolutionQuery.Resolution.of(List.of(OWN_NODE), 7L)),
                    ownership(), graph());

            var view = graphService.traverseFor(
                    new PrincipalId(UUID.randomUUID()), SERVICE, NOW, 10);

            assertTrue(view.result().reachedAssetIds().containsAll(List.of(SERVICE, API)));
            assertFalse(view.result().reachedAssetIds().contains(FOREIGN_DOMAIN),
                    "this is the DOC-03 section 8.3 attack: the query started legitimately at a service the "
                            + "principal owns, and the edge to another business unit's domain must not carry "
                            + "them across");
            assertTrue(view.prunedBranchCount() > 0, "the audit view records the pruning");
        }

        @Test
        @DisplayName("SEC-AUZ-014: an unavailable resolution yields an EMPTY traversal, not an unfiltered one")
        void unavailableResolutionYieldsEmpty() {
            var graphService = new ScopeAuthorizedAssetGraph(
                    resolution(ScopeResolutionQuery.Resolution.unavailable("closure unreadable", 7L)),
                    ownership(), graph());

            var view = graphService.traverseFor(new PrincipalId(UUID.randomUUID()), SERVICE, NOW, 10);

            assertTrue(view.result().reachedAssetIds().isEmpty(),
                    "a predicate defaulting to true on a resolution failure would return the whole graph — "
                            + "the fail-open shape that matters here");
        }

        @Test
        @DisplayName("an UNCLAIMED asset is excluded from traversal for every principal")
        void unclaimedAssetIsExcluded() {
            ScopeAuthorizedAssetGraph.AssetOwnershipLookup unclaimedApi = assetId ->
                    assetId.equals(API) ? Optional.empty() : Optional.of(OWN_NODE);

            var graphService = new ScopeAuthorizedAssetGraph(
                    resolution(ScopeResolutionQuery.Resolution.of(List.of(OWN_NODE), 7L)),
                    unclaimedApi, graph());

            var view = graphService.traverseFor(new PrincipalId(UUID.randomUUID()), SERVICE, NOW, 10);

            assertFalse(view.result().reachedAssetIds().contains(API),
                    "an asset nobody owns has no scope; including it would make it visible to everybody. "
                            + "DOC-07 section 9.2 makes UNCLAIMED assets visible only with "
                            + "ast.ownership.claim in the candidate scope, which is a different query");
        }

        @Test
        @DisplayName("the caller-facing result still discloses nothing about pruning")
        void resultStillDisclosesNothing() {
            var graphService = new ScopeAuthorizedAssetGraph(
                    resolution(ScopeResolutionQuery.Resolution.of(List.of(OWN_NODE), 7L)),
                    ownership(), graph());
            var view = graphService.traverseFor(new PrincipalId(UUID.randomUUID()), SERVICE, NOW, 10);

            // The wiring must not have reintroduced a disclosure the domain type was careful to avoid.
            for (var m : view.result().getClass().getMethods()) {
                String name = m.getName().toLowerCase(java.util.Locale.ROOT);
                assertFalse(name.contains("pruned") || name.contains("hidden"),
                        "found " + m.getName() + " on the caller-facing result");
            }
        }
    }

    @Nested
    @DisplayName("INV-AST-02 — attributes validate against the type's schema at every write")
    class AttributeValidationTests {

        private AttributeSchema schema(String key, AttributeDataType type, boolean required) {
            return new AttributeSchema(UUID.randomUUID(), AttributeSchema.TargetKind.ASSET, null, key, type,
                    "{}", required, false, true, null, null, 0, AttributeSchema.LifecycleState.ACTIVE);
        }

        @Test
        @DisplayName("a value of the wrong type is rejected")
        void wrongTypeRejected() {
            var findings = AttributeValidation.validate(
                    List.of(schema("port", AttributeDataType.INTEGER, false)),
                    Map.of("port", "not a number"));
            assertFalse(AttributeValidation.isValid(findings));
            assertEquals("port", findings.get(0).fieldKey());
        }

        @Test
        @DisplayName("DECIMAL requires BigDecimal, because a score must be recomputable identically")
        void decimalRequiresBigDecimal() {
            var schemas = List.of(schema("weight", AttributeDataType.DECIMAL, false));
            assertFalse(AttributeValidation.isValid(
                    AttributeValidation.validate(schemas, Map.of("weight", 1.5d))),
                    "binary floating point is not associative, so a value feeding a score could not be "
                            + "recomputed identically (PRD-RSK-023)");
            assertTrue(AttributeValidation.isValid(
                    AttributeValidation.validate(schemas, Map.of("weight", new BigDecimal("1.5")))));
        }

        @Test
        @DisplayName("an attribute with no schema is REJECTED, not ignored")
        void unknownAttributeRejected() {
            var findings = AttributeValidation.validate(
                    List.of(schema("port", AttributeDataType.INTEGER, false)),
                    Map.of("prot", 443));
            assertFalse(AttributeValidation.isValid(findings));
            assertTrue(findings.stream().anyMatch(f -> f.fieldKey().equals("prot")),
                    "ignoring an unknown attribute means a typo silently discards the value, and the user "
                            + "sees a saved form with a missing field");
        }

        @Test
        @DisplayName("a missing required attribute is reported")
        void missingRequiredReported() {
            assertFalse(AttributeValidation.isValid(AttributeValidation.validate(
                    List.of(schema("environment", AttributeDataType.SINGLE_SELECT, true)), Map.of())));
        }

        @Test
        @DisplayName("a RETIRED schema accepts no new values but does not invalidate a read")
        void retiredSchemaAcceptsNothingNew() {
            var retired = new AttributeSchema(UUID.randomUUID(), AttributeSchema.TargetKind.ASSET, null,
                    "legacy", AttributeDataType.TEXT, "{}", false, false, true, null, null, 0,
                    AttributeSchema.LifecycleState.RETIRED);
            assertFalse(AttributeValidation.isValid(
                    AttributeValidation.validate(List.of(retired), Map.of("legacy", "x"))));
        }

        @Test
        @DisplayName("every finding is reported, not just the first")
        void allFindingsReported() {
            var findings = AttributeValidation.validate(
                    List.of(schema("a", AttributeDataType.INTEGER, true),
                            schema("b", AttributeDataType.BOOLEAN, true)),
                    Map.of("a", "x", "b", "y"));
            assertEquals(2, findings.size(),
                    "a caller that has to re-submit five times to discover five problems will disable "
                            + "validation");
        }
    }

    @Nested
    @DisplayName("The ast.* catalogue")
    class Permissions {

        @Test
        @DisplayName("the eleven permissions of DOC-07 section 5.2 are present and well-formed")
        void catalogueMatchesTheCorpus() {
            assertEquals(11, AssetPermissions.all().size());
            assertTrue(AssetPermissions.all().stream().allMatch(p -> p.code().startsWith("ast.")),
                    "a permission outside the ast. namespace would not be this module's to define");
            assertEquals(AssetPermissions.all().size(),
                    AssetPermissions.all().stream().distinct().count());
        }

        @Test
        @DisplayName("permissions are typed constants, so a typo is a compile error")
        void permissionsAreTyped() {
            // Error Prone flagged an `instanceof` here as equivalent to a null check, correctly — the static
            // type is already PermissionId. The property that can actually regress is the DECLARED FIELD
            // type: someone adding a permission as a String constant. A raw string typo yields a permission
            // matching nothing, which DENIES — so it looks like a configuration problem rather than a defect,
            // and gets "fixed" by widening a role.
            for (var field : AssetPermissions.class.getDeclaredFields()) {
                if (field.isSynthetic() || !java.lang.reflect.Modifier.isPublic(field.getModifiers())) {
                    continue;
                }
                assertEquals(aspm.kernel.authorization.contract.PermissionId.class, field.getType(),
                        "permission constant '" + field.getName() + "' is declared as "
                                + field.getType().getSimpleName() + " rather than PermissionId");
            }
        }
    }
}
