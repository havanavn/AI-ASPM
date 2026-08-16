package aspm.module.organizationscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.organizationscope.application.ClosureBackedScopeResolution;
import aspm.module.organizationscope.domain.CriticalityResolution;
import aspm.module.organizationscope.domain.OrgClosure;
import aspm.module.organizationscope.domain.OrgNode;
import aspm.module.organizationscope.domain.OrgNodeTypeCatalogue;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.PrincipalId;
import aspm.sharedkernel.ScopeDescriptor;
import aspm.sharedkernel.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** INV-ORG-01 to -06, -11, -12, -16; SEC-AUZ-010; SEC-AUZ-028. */
class OrgNodeAndResolutionTest {

    private static final TenantId TENANT = new TenantId(new UUID(1, 1));
    private static final Instant T0 = Instant.parse("2026-05-01T00:00:00Z");
    private static final UUID TIER = new UUID(9, 9);

    private static OrgNodeId node(String seed) {
        return new OrgNodeId(UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static OrgNodeTypeCatalogue.NodeType type(
            String code, Set<UUID> parents, OrgNodeTypeCatalogue.NodeType.Lifecycle state) {
        return new OrgNodeTypeCatalogue.NodeType(
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                code, Map.of("en", code, "vi", code), parents, true, true, 0, state);
    }

    private static final OrgNodeTypeCatalogue.NodeType GROUP_TYPE =
            type("GROUP", Set.of(), OrgNodeTypeCatalogue.NodeType.Lifecycle.ACTIVE);
    private static final OrgNodeTypeCatalogue.NodeType UNIT_TYPE =
            type("UNIT", Set.of(GROUP_TYPE.id()), OrgNodeTypeCatalogue.NodeType.Lifecycle.ACTIVE);

    @Nested
    @DisplayName("INV-ORG-01 to -04 — the type catalogue is validated as a set")
    class Catalogue {

        @Test
        @DisplayName("INV-ORG-01: a catalogue with no rootable type is rejected")
        void requiresARootableType() {
            var a = type("A", Set.of(UUID.nameUUIDFromBytes("B".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    OrgNodeTypeCatalogue.NodeType.Lifecycle.ACTIVE);
            var b = type("B", Set.of(UUID.nameUUIDFromBytes("A".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    OrgNodeTypeCatalogue.NodeType.Lifecycle.ACTIVE);
            var findings = OrgNodeTypeCatalogue.validate(Set.of(a, b));
            assertTrue(findings.stream().anyMatch(f -> f.invariant().equals("INV-ORG-01")),
                    "without a rootable type every node creation fails with a parent-type error naming the "
                            + "wrong problem");
        }

        @Test
        @DisplayName("INV-ORG-02: a type-level cycle is rejected at configuration time")
        void rejectsTypeLevelCycle() {
            var a = type("A", Set.of(UUID.nameUUIDFromBytes("B".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    OrgNodeTypeCatalogue.NodeType.Lifecycle.ACTIVE);
            var b = type("B", Set.of(UUID.nameUUIDFromBytes("A".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                    OrgNodeTypeCatalogue.NodeType.Lifecycle.ACTIVE);
            assertTrue(OrgNodeTypeCatalogue.validate(Set.of(a, b)).stream()
                            .anyMatch(f -> f.invariant().equals("INV-ORG-02")),
                    "type-level cycles are rejected independently of instance-level cycle rejection: a "
                            + "cyclic type graph makes every instance tree unreachable");
        }

        @Test
        @DisplayName("a type permitting itself as parent is not constructible")
        void rejectsTrivialSelfCycle() {
            UUID id = UUID.nameUUIDFromBytes("SELF".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> new OrgNodeTypeCatalogue.NodeType(
                    id, "SELF", Map.of("en", "Self"), Set.of(id), true, true, 0,
                    OrgNodeTypeCatalogue.NodeType.Lifecycle.ACTIVE));
        }

        @Test
        @DisplayName("INV-ORG-04: a code must be a stable identifier; a label is free")
        void codeIsStableLabelIsFree() {
            assertThrows(IllegalArgumentException.class,
                    () -> type("Business Unit", Set.of(), OrgNodeTypeCatalogue.NodeType.Lifecycle.ACTIVE));
            // PRD-ORG-004: a tenant renaming "Business Unit" to "P&L" is exactly the configurability
            // ADR-027 requires, and it touches only the label.
            var withLabel = new OrgNodeTypeCatalogue.NodeType(UUID.randomUUID(), "BUSINESS_UNIT",
                    Map.of("en", "P&L", "vi", "Đơn vị kinh doanh"), Set.of(), true, true, 0,
                    OrgNodeTypeCatalogue.NodeType.Lifecycle.ACTIVE);
            assertEquals("P&L", withLabel.label().get("en"));
        }

        @Test
        @DisplayName("a valid catalogue with no fixed depth produces no findings")
        void validCatalogueIsAccepted() {
            var level3 = type("PRODUCT", Set.of(UNIT_TYPE.id()),
                    OrgNodeTypeCatalogue.NodeType.Lifecycle.ACTIVE);
            assertTrue(OrgNodeTypeCatalogue.validate(Set.of(GROUP_TYPE, UNIT_TYPE, level3)).isEmpty(),
                    "PRD-ORG-001: depth is tenant-configured, so a three-level catalogue and a ten-level one "
                            + "are both valid configurations rather than one being the product's shape");
        }

        @Test
        @DisplayName("a dangling permitted-parent reference is reported")
        void danglingParentTypeReported() {
            var orphan = type("ORPHAN", Set.of(UUID.randomUUID()),
                    OrgNodeTypeCatalogue.NodeType.Lifecycle.ACTIVE);
            assertTrue(OrgNodeTypeCatalogue.validate(Set.of(GROUP_TYPE, orphan)).stream()
                    .anyMatch(f -> f.detail().contains("not in the catalogue")));
        }
    }

    @Nested
    @DisplayName("INV-ORG-05, -06, -11, -12 — the node aggregate")
    class Node {

        private OrgNode root() {
            return OrgNode.create(node("group"), TENANT, GROUP_TYPE, null, null, "Group", null,
                    CriticalityResolution.Assignment.assigned(TIER, "tenant root", null, T0));
        }

        @Test
        @DisplayName("INV-ORG-06: a node whose type does not permit the parent's type is rejected")
        void parentTypeMustBePermitted() {
            // GROUP permits no parent, so it cannot sit under UNIT.
            assertThrows(IllegalArgumentException.class,
                    () -> OrgNode.create(node("x"), TENANT, GROUP_TYPE, node("unit"), UNIT_TYPE, "X", null,
                            CriticalityResolution.Assignment.inherited()));
        }

        @Test
        @DisplayName("INV-ORG-05: parent identity and parent type must travel together")
        void parentIdentityAndTypeTravelTogether() {
            assertThrows(IllegalArgumentException.class,
                    () -> OrgNode.create(node("x"), TENANT, UNIT_TYPE, node("group"), null, "X", null,
                            CriticalityResolution.Assignment.inherited()),
                    "supplying one without the other means INV-ORG-06 cannot be checked");
        }

        @Test
        @DisplayName("INV-ORG-07: a node cannot be its own parent")
        void noSelfParent() {
            OrgNodeId self = node("self");
            assertThrows(IllegalArgumentException.class,
                    () -> OrgNode.create(self, TENANT, UNIT_TYPE, self, GROUP_TYPE, "X", null,
                            CriticalityResolution.Assignment.inherited()));
        }

        @Test
        @DisplayName("a DEPRECATED parent type accepts no new child nodes")
        void deprecatedParentTypeRejectsChildren() {
            var deprecated = type("GROUP", Set.of(), OrgNodeTypeCatalogue.NodeType.Lifecycle.DEPRECATED);
            assertThrows(IllegalArgumentException.class,
                    () -> OrgNode.create(node("x"), TENANT, UNIT_TYPE, node("g"), deprecated, "X", null,
                            CriticalityResolution.Assignment.inherited()));
        }

        @Test
        @DisplayName("INV-ORG-12: an ownership gap is an EVENT, not a rejected write")
        void ownershipGapIsAnEvent() {
            var n = root();
            n.drainEvents();
            n.evaluateOwnershipGap(true);

            var events = n.drainEvents();
            assertEquals(1, events.size());
            assertTrue(events.get(0) instanceof OrgNode.Event.OwnershipGapDetected,
                    "rejecting the write means the node is not created, which means its assets have no home "
                            + "at all (DOC-03 section 7.3 on INV-ORG-12)");
        }

        @Test
        @DisplayName("INV-ORG-12: the gap is not raised where a business owner exists, and is idempotent")
        void gapIsIdempotentAndConditional() {
            var n = root();
            n.assignOwner(new PrincipalId(UUID.randomUUID()), OrgNode.OwnerKind.BUSINESS);
            n.drainEvents();
            n.evaluateOwnershipGap(true);
            assertTrue(n.drainEvents().isEmpty());

            var m = root();
            m.drainEvents();
            m.evaluateOwnershipGap(true);
            m.evaluateOwnershipGap(true);
            assertEquals(1, m.drainEvents().size(), "a repeated check must not flood the queue");
        }

        @Test
        @DisplayName("INV-ORG-12: business and technical owner sets may overlap")
        void ownerSetsMayOverlap() {
            var n = root();
            var principal = new PrincipalId(UUID.randomUUID());
            n.assignOwner(principal, OrgNode.OwnerKind.BUSINESS);
            n.assignOwner(principal, OrgNode.OwnerKind.TECHNICAL);
            assertEquals(2, n.owners().size(), "one person may hold both roles; INV-ORG-12 permits it");
        }

        @Test
        @DisplayName("INV-ORG-11: lifecycle is one-directional and ARCHIVED is terminal")
        void lifecycleIsOneDirectional() {
            var n = root();
            n.deprecate();
            assertThrows(IllegalStateException.class,
                    () -> n.assignOwner(new PrincipalId(UUID.randomUUID()), OrgNode.OwnerKind.BUSINESS),
                    "a DEPRECATED node accepts no new assignment");
            assertTrue(n.inOperationalScope(), "but stays in operational views so in-flight work completes");

            n.archive();
            assertFalse(n.inOperationalScope());
            assertThrows(IllegalStateException.class, n::deprecate,
                    "there is no path out of ARCHIVED, because historical descriptors name archived nodes");
        }
    }

    @Nested
    @DisplayName("SEC-AUZ-010, SEC-AUZ-028 — scope resolution over the closure")
    class Resolution {

        private final OrgNodeId group = node("group");
        private final OrgNodeId unitA = node("unit-a");
        private final OrgNodeId unitB = node("unit-b");
        private final OrgNodeId project = node("project");

        private OrgClosure closure(OrgNodeId projectParent, long version) {
            Map<OrgNodeId, OrgNodeId> parents = new LinkedHashMap<>();
            parents.put(unitA, group);
            parents.put(unitB, group);
            parents.put(project, projectParent);
            return OrgClosure.buildFrom(parents, Set.of(group, unitA, unitB, project), version);
        }

        private ClosureBackedScopeResolution resolver(
                Map<String, List<OrgNodeId>> byPermission, OrgClosure closure, long version) {
            return new ClosureBackedScopeResolution(
                    (principal, permission) -> byPermission.getOrDefault(permission, List.of()),
                    new ClosureBackedScopeResolution.ClosureSource() {
                        @Override
                        public OrgClosure currentClosure() {
                            return closure;
                        }

                        @Override
                        public long hierarchyVersion() {
                            return version;
                        }
                    });
        }

        @Test
        @DisplayName("an assignment resolves to the node plus its whole subtree")
        void assignmentResolvesToSubtree() {
            var r = resolver(Map.of("vul.finding.read", List.of(unitA)), closure(unitA, 7L), 7L)
                    .resolveCurrent(new PrincipalId(UUID.randomUUID()), "vul.finding.read");

            assertFalse(r.isUnavailable());
            assertTrue(r.permittedNodes().contains(unitA), "INV-ORG-13's self-reference is what includes the "
                    + "assigned node itself; without it an assignment would not authorize the node");
            assertTrue(r.permittedNodes().contains(project));
            assertFalse(r.permittedNodes().contains(unitB));
            assertFalse(r.permittedNodes().contains(group), "scope is downward, not upward");
        }

        @Test
        @DisplayName("SEC-AUZ-010: two permissions resolve independently, never as a cross product")
        void permissionsDoNotCrossProduct() {
            var byPermission = Map.of(
                    "vul.finding.read", List.of(unitA),
                    "vul.finding.approve", List.of(unitB));
            var resolver = resolver(byPermission, closure(unitA, 7L), 7L);
            var principal = new PrincipalId(UUID.randomUUID());

            var read = resolver.resolveCurrent(principal, "vul.finding.read");
            var approve = resolver.resolveCurrent(principal, "vul.finding.approve");

            assertTrue(read.permittedNodes().contains(project));
            assertFalse(approve.permittedNodes().contains(project),
                    "A5 of DOC-16 section 6: multiple assignments resolve as a union of permission-scope "
                            + "pairs and NOT a cross product. Approve on unit-B must not become approve on "
                            + "unit-A's project.");
        }

        @Test
        @DisplayName("SEC-AUZ-014: an unreadable closure is UNAVAILABLE, not an empty permitted set")
        void unreadableClosureIsUnavailable() {
            var resolver = new ClosureBackedScopeResolution(
                    (p, perm) -> List.of(unitA),
                    new ClosureBackedScopeResolution.ClosureSource() {
                        @Override
                        public OrgClosure currentClosure() {
                            return null;
                        }

                        @Override
                        public long hierarchyVersion() {
                            return 7L;
                        }
                    });
            var r = resolver.resolveCurrent(new PrincipalId(UUID.randomUUID()), "vul.finding.read");
            assertTrue(r.isUnavailable(),
                    "an empty set means 'this principal reaches nothing', which is a legitimate "
                            + "configuration; unavailable means 'we do not know'. Conflating them makes a "
                            + "closure outage look like a permissions problem.");
        }

        @Test
        @DisplayName("an unavailable resolution cannot also carry nodes")
        void unavailableCarriesNoNodes() {
            assertThrows(IllegalArgumentException.class,
                    () -> new aspm.module.organizationscope.contract.ScopeResolutionQuery.Resolution(
                            List.of(unitA), 7L, java.util.Optional.of("partial")),
                    "a partial resolution presented as successful is how a principal silently loses access "
                            + "to part of their scope");
        }

        @Test
        @DisplayName("SEC-AUZ-028: historical evaluation uses the recorded path, not the current tree")
        void historicalEvaluationUsesTheDescriptor() {
            // The project moved from unit-A to unit-B, so the CURRENT closure no longer places it under A.
            var currentClosure = closure(unitB, 8L);
            var resolver = resolver(Map.of("vul.finding.read", List.of(unitA)), currentClosure, 8L);
            var principal = new PrincipalId(UUID.randomUUID());

            // A May descriptor, recorded when the project was under unit-A.
            var mayDescriptor = new ScopeDescriptor(TENANT, project,
                    closure(unitA, 7L).ancestorPathTo(project), UNIT_TYPE.id(), TIER, T0, 7L);

            var verdict = resolver.wasAuthorized(principal, "vul.finding.read", mayDescriptor, T0);
            assertTrue(verdict.authorized(),
                    "unit-A's manager retains access to what arose under their accountability, and the "
                            + "current closure — which no longer links them — is deliberately not consulted");

            // And gains nothing for an object created after the move.
            var augustDescriptor = new ScopeDescriptor(TENANT, project,
                    currentClosure.ancestorPathTo(project), UNIT_TYPE.id(), TIER,
                    Instant.parse("2026-08-01T00:00:00Z"), 8L);
            assertFalse(
                    resolver.wasAuthorized(principal, "vul.finding.read", augustDescriptor, T0).authorized());
        }

        @Test
        @DisplayName("the historical verdict carries a basis, so an audited decision is explainable")
        void verdictCarriesABasis() {
            var resolver = resolver(Map.of("vul.finding.read", List.of(unitA)), closure(unitA, 7L), 7L);
            var descriptor = new ScopeDescriptor(TENANT, project,
                    closure(unitA, 7L).ancestorPathTo(project), UNIT_TYPE.id(), TIER, T0, 7L);
            var verdict = resolver.wasAuthorized(
                    new PrincipalId(UUID.randomUUID()), "vul.finding.read", descriptor, T0);
            assertTrue(verdict.basis().contains("hierarchy version 7"));
        }
    }
}
