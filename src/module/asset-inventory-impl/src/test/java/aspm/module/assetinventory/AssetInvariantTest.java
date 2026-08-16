package aspm.module.assetinventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.assetinventory.domain.Asset;
import aspm.module.assetinventory.domain.AssetGraphTraversal;
import aspm.module.assetinventory.domain.ExposureClassification;
import aspm.module.assetinventory.domain.IdentityRule;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.PrincipalId;
import aspm.sharedkernel.ScopeDescriptor;
import aspm.sharedkernel.TenantId;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The four INV-AST invariants prompt 5 names explicitly, plus the identity rules of DOC-03 section 8.5.
 *
 * <p>TST-PLT-004 requires each invariant to be asserted "through any available write path, including bulk
 * operations, import, migration, and administrative tooling". Where the invariant is structural — INV-AST-05
 * and INV-AST-12 both are — the strongest assertion is that no such path EXISTS, which is a reflective check
 * on the aggregate's shape rather than a call against each path in turn. Both forms appear below.
 */
class AssetInvariantTest {

    private static final TenantId TENANT = new TenantId(new UUID(3, 3));
    private static final Instant T0 = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-01T00:00:00Z");
    private static final UUID TYPE = new UUID(4, 4);

    private static Asset.DiscoveryProvenance seen(Instant at) {
        return new Asset.DiscoveryProvenance("scanner-a", "run-1", at);
    }

    private static Asset asset(boolean networkReachable) {
        return Asset.discover(UUID.randomUUID(), TENANT, TYPE, networkReachable,
                "github.com/acme/api", 1, "acme/api", seen(T0));
    }

    private static ScopeDescriptor descriptorFor(OrgNodeId node) {
        return new ScopeDescriptor(TENANT, node, List.of(node), TYPE, new UUID(5, 5), T0, 1L);
    }

    // ================================================================== INV-AST-05

    @Nested
    @DisplayName("INV-AST-05 — exactly one owner, or none while UNCLAIMED")
    class SingleOwnership {

        @Test
        @DisplayName("no write path can produce more than one owner, because none accepts a collection")
        void multipleOwnershipIsNotRepresentable() {
            List<String> suspicious = new ArrayList<>();
            for (Method m : Asset.class.getMethods()) {
                if (m.getDeclaringClass() != Asset.class) {
                    continue;
                }
                String name = m.getName().toLowerCase(java.util.Locale.ROOT);
                boolean touchesOwnership = name.contains("owner") || name.contains("ownership");
                boolean takesACollection = java.util.Arrays.stream(m.getParameterTypes())
                        .anyMatch(t -> Iterable.class.isAssignableFrom(t) || t.isArray()
                                || java.util.Map.class.isAssignableFrom(t));
                if (touchesOwnership && takesACollection) {
                    suspicious.add(m.getName());
                }
                if (name.startsWith("addowner") || name.startsWith("addowning")) {
                    suspicious.add(m.getName());
                }
            }
            assertTrue(suspicious.isEmpty(),
                    "INV-AST-05 must hold through any path including bulk and import. A method accepting a "
                            + "collection of owners, or an add-style mutator, is how a bulk importer produces "
                            + "two: " + suspicious);
        }

        @Test
        @DisplayName("assignment replaces rather than accumulates, and records a transfer")
        void assignmentReplaces() {
            var a = asset(false);
            var first = new OrgNodeId(UUID.randomUUID());
            var second = new OrgNodeId(UUID.randomUUID());

            a.assignOwnership(first, descriptorFor(first));
            a.assignOwnership(second, descriptorFor(second));

            assertEquals(second, a.owningNodeId().orElseThrow());
            assertTrue(a.drainEvents().stream()
                            .anyMatch(e -> e instanceof Asset.Event.OwnershipTransferred),
                    "a transfer is a distinct event from an assignment, because accountability moved and "
                            + "somebody must be able to see when");
        }

        @Test
        @DisplayName("a descriptor disagreeing with the assigned node is rejected")
        void descriptorMustMatchTheNode() {
            var a = asset(false);
            var node = new OrgNodeId(UUID.randomUUID());
            var otherNode = new OrgNodeId(UUID.randomUUID());
            assertThrows(IllegalArgumentException.class,
                    () -> a.assignOwnership(node, descriptorFor(otherNode)),
                    "scope-based authorization and ownership queries would return different answers for the "
                            + "same asset");
        }

        @Test
        @DisplayName("releasing ownership yields absence, never a second owner")
        void releaseYieldsAbsence() {
            var a = asset(false);
            var node = new OrgNodeId(UUID.randomUUID());
            a.assignOwnership(node, descriptorFor(node));
            a.releaseOwnership();
            assertTrue(a.isUnclaimed());
            assertTrue(a.owningNodeId().isEmpty());
        }
    }

    // ================================================================== INV-AST-08

    @Nested
    @DisplayName("INV-AST-08 — an exposure conflict is raised, and the declaration is NOT corrected")
    class ExposureConflict {

        @Test
        @DisplayName("observing a more exposed level raises a conflict and leaves the declaration alone")
        void observationDoesNotCorrectTheDeclaration() {
            var a = asset(true);
            a.declareExposure(ExposureClassification.Level.INTERNAL_ONLY,
                    new PrincipalId(UUID.randomUUID()), T0);
            a.drainEvents();

            a.observeExposure(ExposureClassification.Level.INTERNET_PUBLIC, "dns-sweep", T1);

            var exposure = a.exposure().orElseThrow();
            assertEquals(ExposureClassification.Level.INTERNAL_ONLY, exposure.declared(),
                    "auto-correcting the declaration erases the discrepancy, and with it the finding that "
                            + "someone exposed a system that was not intended to be exposed (INV-AST-08)");
            assertEquals(ExposureClassification.Level.INTERNET_PUBLIC, exposure.observed().orElseThrow());
            assertTrue(exposure.conflict());
            assertTrue(a.drainEvents().stream()
                            .anyMatch(e -> e instanceof Asset.Event.ExposureConflictDetected),
                    "the event is what puts the asset in the exposure conflict queue");
        }

        @Test
        @DisplayName("no method on the classification writes the declared value from an observation")
        void noAutoCorrectionMethodExists() {
            for (Method m : ExposureClassification.class.getMethods()) {
                if (m.getDeclaringClass() != ExposureClassification.class) {
                    continue;
                }
                String name = m.getName().toLowerCase(java.util.Locale.ROOT);
                assertFalse(name.contains("reconcile") || name.contains("correct") || name.contains("sync"),
                        "found " + m.getName() + ". The absence of an auto-correction path IS INV-AST-08; a "
                                + "method with this shape would be the intuitive implementation and the wrong "
                                + "one.");
            }
        }

        @Test
        @DisplayName("over-declaration is NOT a conflict, deliberately")
        void overDeclarationIsNotAConflict() {
            var a = asset(true);
            a.declareExposure(ExposureClassification.Level.INTERNET_PUBLIC,
                    new PrincipalId(UUID.randomUUID()), T0);
            a.observeExposure(ExposureClassification.Level.INTERNAL_ONLY, "dns-sweep", T1);

            assertFalse(a.exposure().orElseThrow().conflict(),
                    "declared public but observed internal is conservative over-declaration: the risk score "
                            + "is too high rather than too low. Only understatement is a finding.");
        }

        @Test
        @DisplayName("scoring uses the more exposed value while a conflict stands")
        void scoringUsesTheMoreExposedValue() {
            var exposure = ExposureClassification
                    .declare(ExposureClassification.Level.INTERNAL_ONLY, new PrincipalId(UUID.randomUUID()), T0)
                    .observe(ExposureClassification.Level.INTERNET_PUBLIC, "dns-sweep", T1);

            assertEquals(ExposureClassification.Level.INTERNET_PUBLIC, exposure.effectiveForScoring(),
                    "the declaration is not corrected, but scoring must not use a value the platform has "
                            + "evidence is wrong — that would be knowingly understating risk");
            assertEquals(ExposureClassification.Level.INTERNAL_ONLY, exposure.declared(),
                    "and the declaration is still not corrected");
        }

        @Test
        @DisplayName("the exposure comparison uses an explicit rank, not declaration order")
        void comparisonUsesAnExplicitRank() {
            // Error Prone's EnumOrdinal warning was correct here: this comparison decides whether an asset
            // enters the conflict queue and which value reaches scoring. An alphabetical tidy-up of the
            // constants would silently invert it, and nothing would fail.
            assertTrue(ExposureClassification.Level.INTERNET_PUBLIC
                    .moreExposedThan(ExposureClassification.Level.AIR_GAPPED));
            assertFalse(ExposureClassification.Level.AIR_GAPPED
                    .moreExposedThan(ExposureClassification.Level.INTERNET_PUBLIC));
            assertEquals(0, ExposureClassification.Level.INTERNET_PUBLIC.exposureRank());
            // The ranks must be distinct, or two levels would be neither more nor less exposed than each
            // other and a conflict would be underivable between them.
            assertEquals(ExposureClassification.Level.values().length,
                    java.util.Arrays.stream(ExposureClassification.Level.values())
                            .map(ExposureClassification.Level::exposureRank).distinct().count());
        }

        @Test
        @DisplayName("INV-AST-07: exposure applies only to network-reachable types")
        void exposureRequiresNetworkReachability() {
            var a = asset(false);
            assertThrows(IllegalStateException.class,
                    () -> a.declareExposure(ExposureClassification.Level.INTERNAL_ONLY,
                            new PrincipalId(UUID.randomUUID()), T0),
                    "accepting a declaration that can never be observed puts a value into scoring that "
                            + "nothing can ever contradict");
        }
    }

    // ================================================================== INV-AST-12

    @Nested
    @DisplayName("INV-AST-12 — last_confirmed_at advances only on discovery evidence")
    class CoverageSignalIntegrity {

        @Test
        @DisplayName("a manual edit does not advance the coverage signal")
        void manualEditDoesNotAdvanceIt() {
            var a = asset(true);
            Instant before = a.lastConfirmedAt();
            a.editDisplayName("renamed by a human");
            assertEquals(before, a.lastConfirmedAt(),
                    "if a manual save advanced it, a stale asset could be made to look fresh without any "
                            + "evidence that it still exists — PP-1 violated through a field nobody thinks of "
                            + "as a metric (INV-AST-12)");
        }

        @Test
        @DisplayName("no setter exists for it on any path")
        void noSetterExists() {
            for (Method m : Asset.class.getMethods()) {
                if (m.getDeclaringClass() != Asset.class) {
                    continue;
                }
                String name = m.getName().toLowerCase(java.util.Locale.ROOT);
                boolean writesTheSignal = name.contains("lastconfirmed") && !name.equals("lastconfirmedat");
                assertFalse(writesTheSignal,
                        "found " + m.getName() + ". Coverage must not be improvable by editing, so the only "
                                + "mutator is confirmSeen(DiscoveryProvenance).");
            }
        }

        @Test
        @DisplayName("confirmation requires provenance, so there is no parameterless touch")
        void confirmationRequiresProvenance() {
            var a = asset(true);
            assertThrows(NullPointerException.class, () -> a.confirmSeen(null));
            boolean hasParameterlessConfirm = java.util.Arrays.stream(Asset.class.getMethods())
                    .anyMatch(m -> m.getName().equals("confirmSeen") && m.getParameterCount() == 0);
            assertFalse(hasParameterlessConfirm, "a no-argument confirm would be a manual touch by another name");
        }

        @Test
        @DisplayName("it advances on newer evidence and never moves backwards")
        void advancesForwardOnly() {
            var a = asset(true);
            a.confirmSeen(seen(T1));
            assertEquals(T1, a.lastConfirmedAt());

            a.confirmSeen(seen(T0));
            assertEquals(T1, a.lastConfirmedAt(),
                    "an older observation arriving late is not evidence that the asset was last seen earlier "
                            + "than it was");
        }
    }

    // ================================================================== INV-AST-17

    @Nested
    @DisplayName("INV-AST-17 — traversal filters per node and does not disclose termination")
    class GraphTraversal {

        private final UUID service = new UUID(10, 1);
        private final UUID api = new UUID(10, 2);
        private final UUID ownDomain = new UUID(10, 3);
        private final UUID foreignDomain = new UUID(10, 4);
        private final UUID beyondForeign = new UUID(10, 5);

        /** S -> api -> {ownDomain, foreignDomain -> beyondForeign}. The DOC-03 section 8.3 scenario. */
        private AssetGraphTraversal.EdgeSource graph() {
            var edges = List.of(
                    new AssetGraphTraversal.Edge(service, api, "EXPOSES", T0, null),
                    new AssetGraphTraversal.Edge(api, ownDomain, "PUBLISHED_ON", T0, null),
                    new AssetGraphTraversal.Edge(api, foreignDomain, "PUBLISHED_ON", T0, null),
                    new AssetGraphTraversal.Edge(foreignDomain, beyondForeign, "CONTAINS", T0, null));
            return assetId -> edges.stream()
                    .filter(e -> e.fromAssetId().equals(assetId) || e.toAssetId().equals(assetId))
                    .toList();
        }

        @Test
        @DisplayName("an out-of-scope node reached by a legitimate edge is not returned")
        void reachingOutOfScopeByEdgeIsPrevented() {
            var view = AssetGraphTraversal.from(service, graph(),
                    id -> !id.equals(foreignDomain) && !id.equals(beyondForeign), T1, 10);

            assertTrue(view.result().reachedAssetIds().containsAll(Set.of(service, api, ownDomain)));
            assertFalse(view.result().reachedAssetIds().contains(foreignDomain),
                    "filtering the QUERY is insufficient because the query started legitimately; scope must "
                            + "be re-evaluated on the node reached (INV-AST-17)");
            assertFalse(view.result().reachedAssetIds().contains(beyondForeign),
                    "and the branch terminates, so nothing beyond it is reachable either");
        }

        @Test
        @DisplayName("the branch terminates rather than failing the whole traversal")
        void branchTerminatesWithoutFailing() {
            var view = AssetGraphTraversal.from(service, graph(), id -> !id.equals(foreignDomain), T1, 10);
            assertTrue(view.result().reachedAssetIds().contains(ownDomain),
                    "an out-of-scope node terminates that branch rather than failing the query — otherwise "
                            + "the failure itself discloses that something exists there");
        }

        @Test
        @DisplayName("the caller-facing result carries NOTHING about what was pruned")
        void resultDisclosesNothingAboutPruning() {
            var view = AssetGraphTraversal.from(service, graph(), id -> !id.equals(foreignDomain), T1, 10);

            // The audit view knows; the caller-facing Result must not.
            assertTrue(view.prunedBranchCount() > 0, "the audit view records it for enumeration detection");
            for (Method m : AssetGraphTraversal.Result.class.getMethods()) {
                if (m.getDeclaringClass() != AssetGraphTraversal.Result.class) {
                    continue;
                }
                String name = m.getName().toLowerCase(java.util.Locale.ROOT);
                assertFalse(name.contains("pruned") || name.contains("hidden") || name.contains("withheld")
                                || name.contains("omitted") || name.contains("truncated"),
                        "found " + m.getName() + " on the caller-facing result. A '3 results hidden' notice "
                                + "is an existence oracle, which SEC-AUZ-020 forbids in a different guise.");
            }
        }

        @Test
        @DisplayName("no edge to a pruned node is returned either")
        void edgesToPrunedNodesAreNotReturned() {
            var view = AssetGraphTraversal.from(service, graph(), id -> !id.equals(foreignDomain), T1, 10);
            assertTrue(view.result().traversedEdges().stream()
                            .noneMatch(e -> e.toAssetId().equals(foreignDomain)
                                    || e.fromAssetId().equals(foreignDomain)),
                    "an edge to a pruned node discloses that the node exists, which is the disclosure the "
                            + "pruning prevents");
        }

        @Test
        @DisplayName("an out-of-scope origin yields an empty result, not a seeded one")
        void originIsNotExempt() {
            var view = AssetGraphTraversal.from(service, graph(), id -> false, T1, 10);
            assertTrue(view.result().reachedAssetIds().isEmpty(),
                    "an exempt origin would make the entry point an oracle");
        }

        @Test
        @DisplayName("SEC-AUZ-025: the depth bound does not vary with scope")
        void depthBoundIsScopeIndependent() {
            // Wide scope reaches depth 3; narrow scope prunes at depth 2. The BOUND is the same, so a caller
            // cannot infer anything from how deep they got relative to the limit.
            var wide = AssetGraphTraversal.from(service, graph(), id -> true, T1, 3);
            var narrow = AssetGraphTraversal.from(service, graph(), id -> !id.equals(foreignDomain), T1, 3);
            assertTrue(wide.depthReached() >= narrow.depthReached());
            assertTrue(wide.result().reachedAssetIds().contains(beyondForeign),
                    "with scope over everything, depth 3 is reachable within the same bound");
        }

        @Test
        @DisplayName("INV-AST-16: a closed edge is not traversed")
        void closedEdgesAreNotTraversed() {
            AssetGraphTraversal.EdgeSource withClosedEdge = assetId -> List.of(
                    new AssetGraphTraversal.Edge(service, api, "EXPOSES", T0, T1));
            var view = AssetGraphTraversal.from(service, withClosedEdge, id -> true,
                    T1.plusSeconds(1), 10);
            assertFalse(view.result().reachedAssetIds().contains(api),
                    "a superseded edge is history, not topology; it is retained so 'what was deployed when "
                            + "this finding was open' remains answerable");
        }
    }

    // ================================================================== DOC-03 section 8.5

    @Nested
    @DisplayName("DOC-03 8.5 — identity rules and normalizations")
    class Identity {

        @Test
        @DisplayName("three forms of one repository URL normalize to one identity")
        void repositoryFormsConverge() {
            var rule = IdentityRule.productDefault(IdentityRule.KnownType.REPOSITORY);
            var forms = List.of(
                    "https://GitHub.com/Acme/Api.git",
                    "git@github.com/acme/api",
                    "https://token:x@github.com/acme/api/");
            var normalized = forms.stream().map(rule::normalizeValue).distinct().toList();
            assertEquals(1, normalized.size(),
                    "without normalization each becomes a separate asset and finding history fragments three "
                            + "ways (DOC-03 section 8.5). Got: " + normalized);
            assertEquals("github.com/acme/api", normalized.get(0));
        }

        @Test
        @DisplayName("path parameters collapse, so a REST inventory is bounded")
        void pathParametersCollapse() {
            assertEquals("/users/{id}/orders/{id}",
                    IdentityRule.collapsePathParameters("/users/123/orders/456"));
            assertEquals("/users/{id}",
                    IdentityRule.collapsePathParameters(
                            "/users/f81d4fae-7dec-11d0-a765-00a0c91e6bf6"));
        }

        @Test
        @DisplayName("collapsing is conservative: a route name is not a parameter")
        void collapsingIsConservative() {
            assertEquals("/users/profile/settings",
                    IdentityRule.collapsePathParameters("/users/profile/settings"),
                    "over-collapsing merges genuinely distinct endpoints into one and destroys their "
                            + "separate finding histories, which is not visible; under-collapsing fragments "
                            + "an endpoint, which is");
            assertEquals("/v2/api/health", IdentityRule.collapsePathParameters("/v2/api/health"));
        }

        @Test
        @DisplayName("a rule declares its unimplemented normalizations rather than hiding them")
        void unimplementedNormalizationsAreVisible() {
            var component = IdentityRule.productDefault(IdentityRule.KnownType.COMPONENT);
            assertTrue(component.declaredButNotImplemented()
                            .contains(IdentityRule.Normalization.CANONICALIZE_PURL),
                    "identity resolution is only as good as its weakest normalization, and an unimplemented "
                            + "one silently produces false splits. PURL canonicalization is prompt 11's.");
            var repository = IdentityRule.productDefault(IdentityRule.KnownType.REPOSITORY);
            assertTrue(repository.declaredButNotImplemented().isEmpty(),
                    "the repository rule is fully implemented, so it must not claim otherwise");
        }

        @Test
        @DisplayName("every known type has a product-supplied rule with a version")
        void everyTypeHasARule() {
            for (var type : IdentityRule.KnownType.values()) {
                var rule = IdentityRule.productDefault(type);
                assertEquals(1, rule.version());
                assertFalse(rule.naturalKeyAttributes().isEmpty(),
                        type + " has no natural key, which would make every asset of that type identical");
            }
        }

        @Test
        @DisplayName("an asset cannot be discovered without an identity rule version")
        void identityVersionIsRequired() {
            assertThrows(IllegalArgumentException.class,
                    () -> Asset.discover(UUID.randomUUID(), TENANT, TYPE, true, "x", 0, "x", seen(T0)),
                    "without it a rule improvement cannot tell which assets it must re-resolve");
        }
    }
}
