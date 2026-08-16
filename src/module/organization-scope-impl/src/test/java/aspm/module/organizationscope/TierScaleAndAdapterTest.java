package aspm.module.organizationscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.kernel.authorization.contract.AuthorizationDecision;
import aspm.kernel.authorization.contract.AuthorizationRequest;
import aspm.kernel.authorization.contract.DenialReason;
import aspm.kernel.authorization.contract.PermissionId;
import aspm.kernel.tenantcontext.contract.EstablishedFrom;
import aspm.kernel.tenantcontext.contract.TenantContext;
import aspm.module.organizationscope.application.OrgScopeResolverAdapter;
import aspm.module.organizationscope.contract.ScopeResolutionQuery;
import aspm.module.organizationscope.domain.CriticalityTierScale;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.PrincipalId;
import aspm.sharedkernel.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** INV-ORG-16, INV-ORG-17, and the adapter that removes DenyAllScopeResolver from the assembly. */
class TierScaleAndAdapterTest {

    private static CriticalityTierScale.Tier tier(String code, int ordinal) {
        return new CriticalityTierScale.Tier(UUID.randomUUID(), code, Map.of("en", code), ordinal,
                CriticalityTierScale.Tier.Lifecycle.ACTIVE);
    }

    @Nested
    @DisplayName("INV-ORG-16, INV-ORG-17 — the criticality scale")
    class Scale {

        @Test
        @DisplayName("INV-ORG-16: duplicate ordinals are rejected, because the comparison decides priority")
        void duplicateOrdinalsRejected() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> CriticalityTierScale.of(Set.of(tier("TIER_1", 1), tier("TIER_2", 1))));
            assertTrue(ex.getMessage().contains("INV-ORG-16"),
                    "two tiers sharing an ordinal make prioritisation depend on read order");
        }

        @Test
        @DisplayName("lower ordinal is more critical, and the ordering is total")
        void lowerOrdinalIsMoreCritical() {
            var scale = CriticalityTierScale.of(
                    Set.of(tier("LOW", 4), tier("CRITICAL", 1), tier("MEDIUM", 3), tier("HIGH", 2)));
            assertEquals(List.of("CRITICAL", "HIGH", "MEDIUM", "LOW"),
                    scale.mostCriticalFirst().stream().map(CriticalityTierScale.Tier::code).toList(),
                    "the convention is stated everywhere it is used because the opposite is equally "
                            + "plausible and a reversed comparison inverts prioritisation silently");
        }

        @Test
        @DisplayName("INV-ORG-17: a DEPRECATED tier is not assignable but remains resolvable")
        void deprecatedTierIsNotAssignable() {
            var retired = new CriticalityTierScale.Tier(UUID.randomUUID(), "RETIRED", Map.of("en", "R"), 9,
                    CriticalityTierScale.Tier.Lifecycle.DEPRECATED);
            var scale = CriticalityTierScale.of(Set.of(tier("HIGH", 1), retired));

            assertFalse(scale.acceptsNewAssignment(retired.id()), "no new assignment");
            assertTrue(scale.byId(retired.id()).isPresent(),
                    "but still resolvable: nodes and historical descriptors already reference it, which is "
                            + "why INV-ORG-17 permits deprecation and not deletion");
        }

        @Test
        @DisplayName("INV-ORG-17: a scale with every tier deprecated is rejected")
        void scaleCannotBeEmptiedByDeprecation() {
            var a = new CriticalityTierScale.Tier(UUID.randomUUID(), "A", Map.of("en", "A"), 1,
                    CriticalityTierScale.Tier.Lifecycle.DEPRECATED);
            assertThrows(IllegalArgumentException.class, () -> CriticalityTierScale.of(Set.of(a)),
                    "deprecation retires one tier; it is not a route to emptying the scale");
        }
    }

    @Nested
    @DisplayName("The adapter that replaces DenyAllScopeResolver")
    class Adapter {

        private static final OrgNodeId UNIT = new OrgNodeId(UUID.randomUUID());

        private static TenantContext context() {
            return TenantContext.of(new TenantId(UUID.randomUUID()), "vn-south",
                    EstablishedFrom.AUTHENTICATED_PRINCIPAL, Instant.parse("2026-08-04T00:00:00Z"));
        }

        private static AuthorizationRequest request() {
            return AuthorizationRequest.forCollection(
                    new PrincipalId(UUID.randomUUID()), PermissionId.of("vul.finding.read"));
        }

        private static OrgScopeResolverAdapter adapter(ScopeResolutionQuery.Resolution resolution) {
            return new OrgScopeResolverAdapter(new ScopeResolutionQuery() {
                @Override
                public Resolution resolveCurrent(PrincipalId principal, String permissionCode) {
                    return resolution;
                }

                @Override
                public HistoricalVerdict wasAuthorized(PrincipalId principal, String permissionCode,
                        aspm.sharedkernel.ScopeDescriptor descriptor, Instant at) {
                    return new HistoricalVerdict(false, "not exercised here");
                }
            });
        }

        @Test
        @DisplayName("a resolved scope now produces an ALLOW, where prompt 3 always denied")
        void resolvedScopeAllows() {
            var decision = adapter(ScopeResolutionQuery.Resolution.of(List.of(UNIT), 7L))
                    .resolveFor(context(), request()).toDecision(request());

            assertTrue(decision.isAllowed(),
                    "prompt 3's DenyAllScopeResolver returned SCOPE_RESOLUTION_UNAVAILABLE for everything "
                            + "because the closure did not exist; this is the class that changes that");
            var allow = (AuthorizationDecision.Allow) decision;
            assertEquals(List.of(UNIT), allow.appliedScope().resolvedNodes());
            assertEquals(7L, allow.appliedScope().hierarchyVersion(),
                    "the hierarchy version travels with the decision so an audited decision can be "
                            + "re-derived after a reorganization");
        }

        @Test
        @DisplayName("SEC-AUZ-014: unavailable resolution and an empty scope give DIFFERENT denial reasons")
        void unavailableAndEmptyAreDistinct() {
            var unavailable = adapter(ScopeResolutionQuery.Resolution.unavailable("closure unreadable", 7L))
                    .resolveFor(context(), request()).toDecision(request());
            var empty = adapter(ScopeResolutionQuery.Resolution.of(List.of(), 7L))
                    .resolveFor(context(), request()).toDecision(request());

            assertEquals(DenialReason.SCOPE_RESOLUTION_UNAVAILABLE,
                    unavailable.denialReason().orElseThrow());
            assertEquals(DenialReason.NO_MATCHING_GRANT, empty.denialReason().orElseThrow(),
                    "conflating them would make a closure outage indistinguishable from a correctly "
                            + "restrictive configuration, and the two need different operator responses");
        }

        @Test
        @DisplayName("no method on the resolver port can return the gate key")
        void resolverPortCannotYieldTheGateKey() {
            // A first version of this test compared the returned Resolution against AuthorizedQuery with
            // instanceof, and did not compile: the two types are unrelated, so the property is a
            // compile-time impossibility rather than a runtime fact. That is stronger, but it means the
            // assertion has to be about the port's SHAPE — no method anywhere on it yields the key.
            var key = aspm.kernel.tenantcontext.contract.AuthorizedQuery.class;
            for (Class<?> type : List.of(
                    aspm.kernel.authorization.contract.ScopeResolver.class,
                    aspm.kernel.authorization.contract.ScopeResolver.Resolution.class,
                    OrgScopeResolverAdapter.class)) {
                for (var method : type.getMethods()) {
                    assertFalse(key.isAssignableFrom(method.getReturnType()),
                            type.getSimpleName() + "." + method.getName() + " returns the gate key. A "
                                    + "resolver that could mint it would be a second place authorization is "
                                    + "decided, which SEC-AUZ-013 exists to prevent, and CON-PLT-037 confines "
                                    + "minting to AuthorizationGateway's subclass.");
                }
            }
        }
    }
}
