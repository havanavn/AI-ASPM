package aspm.module.assetinventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.assetinventory.domain.Asset;
import aspm.module.assetinventory.domain.AssetMerge;
import aspm.module.assetinventory.domain.AssetRelationship;
import aspm.module.assetinventory.domain.AssetType;
import aspm.module.assetinventory.domain.IdentityRule;
import aspm.module.assetinventory.domain.OwnershipClaim;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.PrincipalId;
import aspm.sharedkernel.ScopeDescriptor;
import aspm.sharedkernel.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** INV-AST-01 to -04, -13 to -16, -18 to -24. */
class EdgeClaimMergeTest {

    private static final TenantId TENANT_A = new TenantId(new UUID(7, 1));
    private static final TenantId TENANT_B = new TenantId(new UUID(7, 2));
    private static final Instant T0 = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-01T00:00:00Z");
    private static final UUID REPO_TYPE = new UUID(8, 1);
    private static final UUID ARTIFACT_TYPE = new UUID(8, 2);

    private static Asset.DiscoveryProvenance seen(Instant at) {
        return new Asset.DiscoveryProvenance("scanner-a", "run-1", at);
    }

    private static Asset asset(TenantId tenant, UUID typeId, String identity) {
        return Asset.discover(UUID.randomUUID(), tenant, typeId, false, identity, 1, identity, seen(T0));
    }

    private static AssetType repoType(Set<AssetType.EdgeConstraint> edges) {
        return new AssetType(REPO_TYPE, TENANT_A.value(), "REPOSITORY", Map.of("en", "Repository"),
                IdentityRule.productDefault(IdentityRule.KnownType.REPOSITORY), edges, false, true,
                AssetType.Lifecycle.ACTIVE);
    }

    private static ScopeDescriptor descriptorFor(OrgNodeId node) {
        return new ScopeDescriptor(TENANT_A, node, List.of(node), REPO_TYPE, new UUID(9, 9), T0, 1L);
    }

    @Nested
    @DisplayName("INV-AST-01 to -04 — the type registry")
    class TypeRegistry {

        @Test
        @DisplayName("INV-AST-01: Asset exposes no way to change its type")
        void typeIsImmutable() {
            for (var m : Asset.class.getMethods()) {
                if (m.getDeclaringClass() != Asset.class) {
                    continue;
                }
                String name = m.getName().toLowerCase(java.util.Locale.ROOT);
                assertFalse(name.startsWith("settype") || name.startsWith("changetype")
                                || name.startsWith("retype"),
                        "found " + m.getName() + ". Changing a type changes the identity rule, permitted "
                                + "edges and attribute schema at once; DOC-03 section 8.1 models it as "
                                + "retire-and-recreate-with-a-merge, not in-place mutation.");
            }
        }

        @Test
        @DisplayName("INV-AST-14: a dangling edge constraint is reported by registry validation")
        void danglingEdgeConstraintReported() {
            var withDangling = repoType(Set.of(
                    new AssetType.EdgeConstraint(AssetType.EdgeType.BUILDS, UUID.randomUUID())));
            var findings = AssetType.validateRegistry(Set.of(withDangling));
            assertTrue(findings.stream().anyMatch(f -> f.contains("INV-AST-14")),
                    "a dangling constraint rejects every edge a discovery source tries to create, and the "
                            + "graph silently stays empty");
        }

        @Test
        @DisplayName("a network-reachable type that cannot carry findings is rejected")
        void reachableTypeMustCarryFindings() {
            assertThrows(IllegalArgumentException.class,
                    () -> new AssetType(REPO_TYPE, null, "DOMAIN", Map.of("en", "Domain"),
                            IdentityRule.productDefault(IdentityRule.KnownType.DOMAIN), Set.of(), true, false,
                            AssetType.Lifecycle.ACTIVE),
                    "exposure classification exists so a conflict becomes a finding (INV-AST-08), and a type "
                            + "that cannot carry one has nowhere to put it");
        }

        @Test
        @DisplayName("INV-AST-03: a DEPRECATED type accepts no new assets but is not deleted")
        void deprecatedTypeAcceptsNothingNew() {
            var deprecated = new AssetType(REPO_TYPE, null, "REPOSITORY", Map.of("en", "R"),
                    IdentityRule.productDefault(IdentityRule.KnownType.REPOSITORY), Set.of(), false, true,
                    AssetType.Lifecycle.DEPRECATED);
            assertFalse(deprecated.acceptsNewAssets());
        }
    }

    @Nested
    @DisplayName("INV-AST-13 to -16 — the edge aggregate")
    class Edges {

        @Test
        @DisplayName("INV-AST-13: an edge spanning tenants is rejected")
        void edgeCannotSpanTenants() {
            var from = asset(TENANT_A, REPO_TYPE, "a");
            var to = asset(TENANT_B, ARTIFACT_TYPE, "b");
            var type = repoType(Set.of(
                    new AssetType.EdgeConstraint(AssetType.EdgeType.BUILDS, ARTIFACT_TYPE)));

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> AssetRelationship.connect(UUID.randomUUID(), from, to,
                            AssetType.EdgeType.BUILDS, type, seen(T0), T0));
            assertTrue(ex.getMessage().contains("INV-AST-13"),
                    "an edge spanning tenants makes graph traversal a cross-tenant read no matter what else "
                            + "is enforced, because traversal follows edges");
        }

        @Test
        @DisplayName("INV-AST-14: an edge type not permitted between the endpoint types is rejected")
        void edgeTypeMustBePermitted() {
            var from = asset(TENANT_A, REPO_TYPE, "a");
            var to = asset(TENANT_A, ARTIFACT_TYPE, "b");
            var typeWithNoEdges = repoType(Set.of());

            assertThrows(IllegalArgumentException.class,
                    () -> AssetRelationship.connect(UUID.randomUUID(), from, to,
                            AssetType.EdgeType.BUILDS, typeWithNoEdges, seen(T0), T0),
                    "PRD-AST-004 makes permitted edges type-level configuration so a discovery source cannot "
                            + "invent topology");
        }

        @Test
        @DisplayName("INV-AST-15: many-to-many in both directions; nothing constrains an endpoint to one")
        void edgesAreManyToMany() {
            var repo = asset(TENANT_A, REPO_TYPE, "repo");
            var artifactOne = asset(TENANT_A, ARTIFACT_TYPE, "one");
            var artifactTwo = asset(TENANT_A, ARTIFACT_TYPE, "two");
            var type = repoType(Set.of(
                    new AssetType.EdgeConstraint(AssetType.EdgeType.BUILDS, ARTIFACT_TYPE)));

            var first = AssetRelationship.connect(UUID.randomUUID(), repo, artifactOne,
                    AssetType.EdgeType.BUILDS, type, seen(T0), T0);
            var second = AssetRelationship.connect(UUID.randomUUID(), repo, artifactTwo,
                    AssetType.EdgeType.BUILDS, type, seen(T0), T0);

            assertTrue(first.isCurrent() && second.isCurrent(),
                    "one repository builds three services (DOC-03 section 6.2); constraining either endpoint "
                            + "to one is the linear-chain model ADR-001 rejects");
        }

        @Test
        @DisplayName("INV-AST-16: superseding closes the edge; there is no delete")
        void supersedingClosesRatherThanDeletes() {
            var repo = asset(TENANT_A, REPO_TYPE, "repo");
            var artifact = asset(TENANT_A, ARTIFACT_TYPE, "artifact");
            var type = repoType(Set.of(
                    new AssetType.EdgeConstraint(AssetType.EdgeType.BUILDS, ARTIFACT_TYPE)));
            var edge = AssetRelationship.connect(UUID.randomUUID(), repo, artifact,
                    AssetType.EdgeType.BUILDS, type, seen(T0), T0);

            edge.supersede(T1);

            assertFalse(edge.isCurrent());
            assertTrue(edge.isCurrentAt(T0.plusSeconds(1)),
                    "the closed edge remains answerable for 'what was deployed when this finding was open', "
                            + "which is required for retest scoping and historical posture");
            assertFalse(edge.isCurrentAt(T1.plusSeconds(1)));

            for (var m : AssetRelationship.class.getMethods()) {
                if (m.getDeclaringClass() != AssetRelationship.class) {
                    continue;
                }
                assertFalse(m.getName().toLowerCase(java.util.Locale.ROOT).startsWith("delete"),
                        "deleting superseded edges makes historical topology unanswerable (INV-AST-16)");
            }
        }

        @Test
        @DisplayName("closing an edge twice is rejected rather than silently ignored")
        void doubleClosureRejected() {
            var repo = asset(TENANT_A, REPO_TYPE, "repo");
            var artifact = asset(TENANT_A, ARTIFACT_TYPE, "artifact");
            var type = repoType(Set.of(
                    new AssetType.EdgeConstraint(AssetType.EdgeType.BUILDS, ARTIFACT_TYPE)));
            var edge = AssetRelationship.connect(UUID.randomUUID(), repo, artifact,
                    AssetType.EdgeType.BUILDS, type, seen(T0), T0);
            edge.supersede(T1);
            assertThrows(IllegalStateException.class, () -> edge.supersede(T1.plusSeconds(60)),
                    "silently keeping the first instant would make the temporal record wrong in a way "
                            + "nothing surfaces");
        }

        @Test
        @DisplayName("a self-edge is rejected")
        void selfEdgeRejected() {
            var repo = asset(TENANT_A, REPO_TYPE, "repo");
            var type = repoType(Set.of(new AssetType.EdgeConstraint(AssetType.EdgeType.BUILDS, REPO_TYPE)));
            assertThrows(IllegalArgumentException.class,
                    () -> AssetRelationship.connect(UUID.randomUUID(), repo, repo,
                            AssetType.EdgeType.BUILDS, type, seen(T0), T0));
        }
    }

    @Nested
    @DisplayName("INV-AST-18 to -20 — the ownership claim pipeline")
    class Claims {

        private final OrgNodeId proposedNode = new OrgNodeId(UUID.randomUUID());
        private final PrincipalId claimant = new PrincipalId(UUID.randomUUID());

        private OwnershipClaim claim() {
            return new OwnershipClaim(UUID.randomUUID(), UUID.randomUUID(), proposedNode,
                    OwnershipClaim.Basis.EXPLICIT, claimant, T0);
        }

        @Test
        @DisplayName("INV-AST-18: confirmation requires authorization for the PROPOSED node")
        void confirmationRequiresAuthorizationForTheProposedNode() {
            var c = claim();
            var ex = assertThrows(IllegalStateException.class,
                    () -> c.confirm(claimant, (principal, node) -> false, T1));
            assertTrue(ex.getMessage().contains("INV-AST-18"));
            assertEquals(OwnershipClaim.State.PROPOSED, c.state(), "an unauthorized attempt changes nothing");
        }

        @Test
        @DisplayName("INV-AST-18: the predicate is evaluated against the proposed node, not the claimant's own")
        void predicateReceivesTheProposedNode() {
            var c = claim();
            var seenNodes = new java.util.ArrayList<OrgNodeId>();
            c.confirm(claimant, (principal, node) -> {
                seenNodes.add(node);
                return true;
            }, T1);
            assertEquals(List.of(proposedNode), seenNodes,
                    "unrestricted self-service claiming is a data exfiltration path: claim a competitor "
                            + "business unit's repository and receive its vulnerability data");
        }

        @Test
        @DisplayName("there is no confirm overload that skips the authorization predicate")
        void noUnauthorizedConfirmOverloadExists() {
            for (var m : OwnershipClaim.class.getMethods()) {
                if (!m.getName().equals("confirm")) {
                    continue;
                }
                boolean hasPredicate = java.util.Arrays.stream(m.getParameterTypes())
                        .anyMatch(java.util.function.BiPredicate.class::isAssignableFrom);
                assertTrue(hasPredicate,
                        "confirm/" + m.getParameterCount() + " does not take an authorization predicate. An "
                                + "overload confirming on authentication alone is INV-AST-18 bypassed.");
            }
        }

        @Test
        @DisplayName("INV-AST-19: two PROPOSED claims for one asset are rejected")
        void atMostOneProposedClaim() {
            assertThrows(IllegalStateException.class,
                    () -> OwnershipClaim.assertAtMostOneProposed(List.of(claim(), claim())),
                    "two open claims let two business units each believe they are about to own the asset");
        }

        @Test
        @DisplayName("INV-AST-20: escalation notifies an ancestor and does NOT assign ownership")
        void escalationNotifiesWithoutAssigning() {
            var c = claim();
            var ancestor = new OrgNodeId(UUID.randomUUID());
            var escalation = c.escalate(ancestor, T1);

            assertEquals(ancestor, escalation.notifiedAncestor());
            assertEquals(1, escalation.level());
            assertEquals(OwnershipClaim.State.PROPOSED, c.state(),
                    "assigning to an ancestor on timeout places accountability with someone who has no "
                            + "operational relationship to the asset; findings would route to a divisional "
                            + "manager who cannot act on them. Escalation makes the gap someone's problem; "
                            + "it does not pretend to solve it (INV-AST-20).");
            assertFalse(c.assignsOwnership());
        }

        @Test
        @DisplayName("a settled claim cannot be re-resolved")
        void settledClaimIsFinal() {
            var c = claim();
            c.reject(claimant, T1);
            assertThrows(IllegalStateException.class,
                    () -> c.confirm(claimant, (p, n) -> true, T1),
                    "re-resolving would let a rejection be quietly converted into a confirmation, which is "
                            + "INV-AST-18 bypassed by state manipulation");
        }

        @Test
        @DisplayName("an EXPLICIT claim without a claimant is not constructible")
        void explicitClaimRequiresAClaimant() {
            assertThrows(IllegalArgumentException.class,
                    () -> new OwnershipClaim(UUID.randomUUID(), UUID.randomUUID(), proposedNode,
                            OwnershipClaim.Basis.EXPLICIT, null, T0),
                    "without a claimant INV-AST-18 has nobody to authorize against the proposed node");
        }
    }

    @Nested
    @DisplayName("INV-AST-21 to -24 — merge")
    class Merge {

        private final PrincipalId performer = new PrincipalId(UUID.randomUUID());
        private final OrgNodeId ownerOne = new OrgNodeId(UUID.randomUUID());
        private final OrgNodeId ownerTwo = new OrgNodeId(UUID.randomUUID());

        @Test
        @DisplayName("INV-AST-24: conflicting owners block the merge; it does not pick one")
        void conflictingOwnersBlockTheMerge() {
            var survivor = asset(TENANT_A, REPO_TYPE, "a");
            var absorbed = asset(TENANT_A, REPO_TYPE, "b");
            survivor.assignOwnership(ownerOne, descriptorFor(ownerOne));
            absorbed.assignOwnership(ownerTwo, descriptorFor(ownerTwo));

            var preparation = AssetMerge.prepare(UUID.randomUUID(), survivor, List.of(absorbed),
                    AssetMerge.Reason.DUPLICATE_IDENTITY, List.of(), performer, T1, Duration.ofDays(7));

            assertTrue(preparation instanceof AssetMerge.Preparation.OwnerConflict,
                    "automatically taking the survivor's owner silently transfers accountability for the "
                            + "absorbed asset's findings, which is the invisible accountability decay the "
                            + "platform exists to prevent (INV-AST-24)");
            var conflict = (AssetMerge.Preparation.OwnerConflict) preparation;
            assertEquals(Set.of(ownerOne, ownerTwo), conflict.distinctOwners(),
                    "every distinct owner is offered, so the resolving principal chooses from the actual set");
        }

        @Test
        @DisplayName("no merge entry point defaults the owner or prefers the survivor")
        void noOwnerDefaultingEntryPointExists() {
            for (var m : AssetMerge.class.getMethods()) {
                String name = m.getName().toLowerCase(java.util.Locale.ROOT);
                assertFalse(name.contains("prefersurvivor") || name.contains("autoresolve")
                                || name.contains("forcemerge"),
                        "found " + m.getName() + ". A default is how the ownership decision stops being made.");
            }
        }

        @Test
        @DisplayName("explicit resolution unblocks the merge and records who decided")
        void explicitResolutionUnblocks() {
            var survivor = asset(TENANT_A, REPO_TYPE, "a");
            var absorbed = asset(TENANT_A, REPO_TYPE, "b");
            survivor.assignOwnership(ownerOne, descriptorFor(ownerOne));
            absorbed.assignOwnership(ownerTwo, descriptorFor(ownerTwo));
            var resolver = new PrincipalId(UUID.randomUUID());

            var preparation = AssetMerge.prepareWithOwnerResolution(UUID.randomUUID(), survivor,
                    List.of(absorbed), AssetMerge.Reason.DUPLICATE_IDENTITY, List.of(), ownerTwo, resolver,
                    performer, T1, Duration.ofDays(7));

            var merge = ((AssetMerge.Preparation.Ready) preparation).merge();
            assertEquals(ownerTwo, merge.resolvedOwner().orElseThrow());
            assertEquals(resolver, merge.ownerResolvedBy().orElseThrow(),
                    "an unattributed ownership decision is the accountability decay INV-AST-24 prevents");
        }

        @Test
        @DisplayName("naming a third node as the resolved owner is rejected")
        void resolvedOwnerMustBeOneOfTheConflicting() {
            var survivor = asset(TENANT_A, REPO_TYPE, "a");
            var absorbed = asset(TENANT_A, REPO_TYPE, "b");
            survivor.assignOwnership(ownerOne, descriptorFor(ownerOne));
            absorbed.assignOwnership(ownerTwo, descriptorFor(ownerTwo));

            assertThrows(IllegalArgumentException.class,
                    () -> AssetMerge.prepareWithOwnerResolution(UUID.randomUUID(), survivor,
                            List.of(absorbed), AssetMerge.Reason.MANUAL, List.of(),
                            new OrgNodeId(UUID.randomUUID()), performer, performer, T1, Duration.ofDays(7)),
                    "naming a third node is a transfer disguised as a merge, and a transfer has its own event "
                            + "and audit trail");
        }

        @Test
        @DisplayName("a merge with one owner, or none, proceeds without resolution")
        void singleOwnerNeedsNoResolution() {
            var survivor = asset(TENANT_A, REPO_TYPE, "a");
            var absorbed = asset(TENANT_A, REPO_TYPE, "b");
            survivor.assignOwnership(ownerOne, descriptorFor(ownerOne));

            var preparation = AssetMerge.prepare(UUID.randomUUID(), survivor, List.of(absorbed),
                    AssetMerge.Reason.RULE_VERSION_CHANGE, List.of(), performer, T1, Duration.ofDays(7));
            assertTrue(preparation instanceof AssetMerge.Preparation.Ready,
                    "requiring resolution where nothing conflicts would make the prompt ceremonial");
        }

        @Test
        @DisplayName("INV-AST-22, -21: absorbed assets are retired with a redirect and nothing is discarded")
        void absorbedAssetsAreRetiredNotDeleted() {
            var survivor = asset(TENANT_A, REPO_TYPE, "a");
            var absorbed = asset(TENANT_A, REPO_TYPE, "b");
            var merge = ((AssetMerge.Preparation.Ready) AssetMerge.prepare(UUID.randomUUID(), survivor,
                    List.of(absorbed), AssetMerge.Reason.MANUAL, List.of(), performer, T1,
                    Duration.ofDays(7))).merge();

            assertEquals(List.of(absorbed.id()), merge.assetsToRetireWithRedirect(),
                    "a finding, report or audit event naming an absorbed asset must still lead somewhere");
            assertEquals(4, merge.transferObligations().size(),
                    "findings, edges, external identifiers and history each need transferring (INV-AST-21)");
        }

        @Test
        @DisplayName("INV-AST-23: a reversal window is required and retains prior ownership")
        void reversalWindowIsRequired() {
            var survivor = asset(TENANT_A, REPO_TYPE, "a");
            var absorbed = asset(TENANT_A, REPO_TYPE, "b");
            survivor.assignOwnership(ownerOne, descriptorFor(ownerOne));

            assertThrows(IllegalArgumentException.class,
                    () -> AssetMerge.prepare(UUID.randomUUID(), survivor, List.of(absorbed),
                            AssetMerge.Reason.MANUAL, List.of(), performer, T1, Duration.ZERO),
                    "a zero window makes an irreversible operation look reversible");

            var merge = ((AssetMerge.Preparation.Ready) AssetMerge.prepare(UUID.randomUUID(), survivor,
                    List.of(absorbed), AssetMerge.Reason.MANUAL, List.of(), performer, T1,
                    Duration.ofDays(7))).merge();
            assertEquals(ownerOne, merge.reversal().ownershipBeforeMerge().get(survivor.id()));
            assertTrue(merge.reversal().isReversibleAt(T1.plusSeconds(60)));
            assertFalse(merge.reversal().isReversibleAt(T1.plus(Duration.ofDays(8))));
        }

        @Test
        @DisplayName("a cross-tenant merge is rejected")
        void crossTenantMergeRejected() {
            var survivor = asset(TENANT_A, REPO_TYPE, "a");
            var absorbed = asset(TENANT_B, REPO_TYPE, "b");
            assertThrows(IllegalArgumentException.class,
                    () -> AssetMerge.prepare(UUID.randomUUID(), survivor, List.of(absorbed),
                            AssetMerge.Reason.MANUAL, List.of(), performer, T1, Duration.ofDays(7)),
                    "a cross-tenant merge moves findings across the isolation boundary");
        }
    }
}
