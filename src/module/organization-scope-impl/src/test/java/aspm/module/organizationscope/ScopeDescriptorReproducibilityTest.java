package aspm.module.organizationscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.organizationscope.domain.CriticalityResolution;
import aspm.module.organizationscope.domain.OrgClosure;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.ScopeDescriptor;
import aspm.sharedkernel.TenantId;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code TST-PTR-003} — scope descriptor and historical reproducibility, in the parts that do not need a
 * database.
 *
 * <p>DOC-16 section 4.3 states the four assertions the suite must make after a reorganization: a historical
 * report reproduces identically; a formerly-authorized principal retains read access to objects that arose
 * under their prior accountability and gains none to objects created after the move; the service level
 * policy in effect at a finding's creation continues to apply; and no scope descriptor on an existing
 * object is modified.
 *
 * <p><b>What is covered here and what is not.</b> Assertions one, two and four are properties of the
 * descriptor mechanism and the closure, and are asserted below over an in-memory tree. Assertion three
 * needs the service level engine (prompt 8), and the <em>persisted</em> form of assertions one and four —
 * that an {@code UPDATE} cannot alter a descriptor column, and that the {@code @>} containment predicate on
 * the stored array agrees with {@link ScopeDescriptor#withinScopeOf} — needs a database. Those remain
 * unobserved in this environment and are listed in {@code src/README.md} rather than implied to be done.
 *
 * <p>DOC-16 section 4.3 is unusually direct about the stakes: the mechanism "cannot be retrofitted, and its
 * failure is silent: a historical report that changes after a reorganization looks like a data error rather
 * than an authorization defect".
 */
class ScopeDescriptorReproducibilityTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Instant T0 = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-01T00:00:00Z");

    private static final UUID TYPE_PRODUCT = UUID.randomUUID();
    private static final UUID TIER_HIGH = UUID.randomUUID();
    private static final UUID TIER_MEDIUM = UUID.randomUUID();

    private static final OrgNodeId GROUP = node("group");
    private static final OrgNodeId UNIT_A = node("unit-a");
    private static final OrgNodeId UNIT_B = node("unit-b");
    private static final OrgNodeId PROJECT = node("project");

    private static OrgNodeId node(String seed) {
        return new OrgNodeId(UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** The tree before the reorganization: group → {unit-a → project, unit-b}. */
    private static OrgClosure treeBefore() {
        Map<OrgNodeId, OrgNodeId> parents = new LinkedHashMap<>();
        parents.put(UNIT_A, GROUP);
        parents.put(UNIT_B, GROUP);
        parents.put(PROJECT, UNIT_A);
        return OrgClosure.buildFrom(parents, Set.of(GROUP, UNIT_A, UNIT_B, PROJECT), 7L);
    }

    /** After: the project moves from unit-a to unit-b. */
    private static OrgClosure treeAfter() {
        Map<OrgNodeId, OrgNodeId> parents = new LinkedHashMap<>();
        parents.put(UNIT_A, GROUP);
        parents.put(UNIT_B, GROUP);
        parents.put(PROJECT, UNIT_B);
        return OrgClosure.buildFrom(parents, Set.of(GROUP, UNIT_A, UNIT_B, PROJECT), 8L);
    }

    private static ScopeDescriptor descriptorFor(OrgClosure closure, OrgNodeId owner, Instant at, long version) {
        return new ScopeDescriptor(TENANT, owner, closure.ancestorPathTo(owner),
                TYPE_PRODUCT, TIER_HIGH, at, version);
    }

    // =========================================================== assertion 4 and immutability

    @Nested
    @DisplayName("PRD-WRK-042 / CON-DAT-009 — a descriptor is immutable after write")
    class Immutability {

        @Test
        @DisplayName("the type exposes no mutator, wither, or node-replacing copy")
        void noMutationIsExpressible() {
            // A first version of this test matched any method whose name began with "with", and failed on
            // withinScopeOf — a predicate. The precise property is that a method producing a MODIFIED
            // descriptor must either return one (a wither or copy) or return void having stored something
            // (a setter). Matching on shape rather than on a name prefix says what is actually meant.
            List<String> mutators = new ArrayList<>();
            for (Method m : ScopeDescriptor.class.getMethods()) {
                if (m.getDeclaringClass() != ScopeDescriptor.class) {
                    continue;
                }
                boolean producesAnotherDescriptor = m.getReturnType() == ScopeDescriptor.class;
                boolean looksLikeASetter =
                        m.getReturnType() == void.class && m.getParameterCount() > 0;
                if (producesAnotherDescriptor || looksLikeASetter) {
                    mutators.add(m.getReturnType().getSimpleName() + " " + m.getName()
                            + "/" + m.getParameterCount());
                }
            }
            assertTrue(mutators.isEmpty(),
                    "PRD-WRK-042 forbids reorganization modifying a descriptor on an existing object, and "
                            + "CON-DAT-009 makes it immutable after insert. The guarantee must be the "
                            + "type's rather than each caller's discipline, so any of these would move it "
                            + "to convention: " + mutators);
        }

        @Test
        @DisplayName("every component is final, so no reflective field write is a supported path")
        void allComponentsAreFinal() {
            for (var field : ScopeDescriptor.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()),
                        "component '" + field.getName() + "' is not final");
            }
        }

        @Test
        @DisplayName("the ancestor path cannot be mutated through the accessor")
        void ancestorPathIsDefensivelyCopied() {
            var descriptor = descriptorFor(treeBefore(), PROJECT, T0, 7L);
            assertThrows(UnsupportedOperationException.class,
                    () -> descriptor.ancestorPath().add(UNIT_B),
                    "a mutable path would let a caller re-point history without any write at all");
        }

        @Test
        @DisplayName("a descriptor whose path does not end at its owning node is not constructible")
        void pathMustEndAtTheOwningNode() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ScopeDescriptor(TENANT, PROJECT, List.of(GROUP, UNIT_A), TYPE_PRODUCT,
                            TIER_HIGH, T0, 7L),
                    "subtree containment and node identity must agree, or authorization follows whichever "
                            + "the caller happened to read");
        }

        @Test
        @DisplayName("a descriptor with a repeated node is not constructible")
        void pathMustNotContainACycle() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ScopeDescriptor(TENANT, PROJECT, List.of(GROUP, UNIT_A, GROUP, PROJECT),
                            TYPE_PRODUCT, TIER_HIGH, T0, 7L));
        }

        @Test
        @DisplayName("an empty ancestor path is not constructible")
        void pathMustNotBeEmpty() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ScopeDescriptor(TENANT, PROJECT, List.of(), TYPE_PRODUCT, TIER_HIGH, T0, 7L),
                    "an empty path makes the containment predicate match nothing, silently denying every "
                            + "historical read rather than failing visibly");
        }
    }

    // =========================================================== assertions 1 and 2

    @Nested
    @DisplayName("TST-PTR-003 — reproducibility and access across a reorganization")
    class AcrossReorganization {

        @Test
        @DisplayName("assertion 1: a historical report reproduces identically after the move")
        void historicalReportReproducesIdentically() {
            OrgClosure before = treeBefore();

            // A finding raised in May, under unit-a's accountability.
            var mayDescriptor = descriptorFor(before, PROJECT, T0, 7L);
            // The May report for unit-a counts objects whose descriptor lies within unit-a's subtree.
            long mayCountBefore = mayDescriptor.withinScopeOf(UNIT_A) ? 1 : 0;

            // The project moves to unit-b in August. The descriptor is untouched — that is the mechanism.
            treeAfter();

            long mayCountAfter = mayDescriptor.withinScopeOf(UNIT_A) ? 1 : 0;

            assertEquals(1, mayCountBefore);
            assertEquals(mayCountBefore, mayCountAfter,
                    "DOC-03 section 6.7: last quarter's posture report must reproduce identically. A "
                            + "current-parentage model recomputes it under the new structure, making every "
                            + "historical report unreproducible and trend data fiction.");
        }

        @Test
        @DisplayName("assertion 2a: the former parent retains access to objects that arose under it")
        void formerParentRetainsHistoricalAccess() {
            var mayDescriptor = descriptorFor(treeBefore(), PROJECT, T0, 7L);
            assertTrue(mayDescriptor.withinScopeOf(UNIT_A),
                    "'Can A's manager still see findings that arose under their accountability?' — yes for "
                            + "historical (DOC-03 section 6.7)");
        }

        @Test
        @DisplayName("assertion 2b: the former parent gains NO access to objects created after the move")
        void formerParentGainsNoAccessToNewObjects() {
            var augustDescriptor = descriptorFor(treeAfter(), PROJECT, T1, 8L);
            assertFalse(augustDescriptor.withinScopeOf(UNIT_A),
                    "'no for new' — the other half of the same sentence, and the half a model that simply "
                            + "retains all access gets wrong");
            assertTrue(augustDescriptor.withinScopeOf(UNIT_B), "the new parent is authorized");
            assertTrue(augustDescriptor.withinScopeOf(GROUP), "the common root is authorized in both eras");
        }

        @Test
        @DisplayName("the two descriptors are attributable to different hierarchy versions")
        void versionsDistinguishTheTwoShapes() {
            assertEquals(7L, descriptorFor(treeBefore(), PROJECT, T0, 7L).hierarchyVersion());
            assertEquals(8L, descriptorFor(treeAfter(), PROJECT, T1, 8L).hierarchyVersion(),
                    "PRD-WRK-040: a reused version would make two different tree shapes share an "
                            + "identifier, making historical descriptors ambiguous");
        }

        @Test
        @DisplayName("the ancestor path is recorded, not derivable from the tree after the move")
        void pathIsNotDerivableAfterwards() {
            var mayPath = descriptorFor(treeBefore(), PROJECT, T0, 7L).ancestorPath();
            var augustPath = treeAfter().ancestorPathTo(PROJECT);

            assertEquals(List.of(GROUP, UNIT_A, PROJECT), mayPath);
            assertEquals(List.of(GROUP, UNIT_B, PROJECT), augustPath);
            assertFalse(mayPath.equals(augustPath),
                    "if the path were derivable from the current tree there would be nothing to record, "
                            + "and DOC-03 section 6.7's whole mechanism would be unnecessary. It is not.");
        }
    }

    // =========================================================== O14

    @Nested
    @DisplayName("INV-ORG-13, INV-ORG-14 — the closure and its rebuild")
    class Closure {

        @Test
        @DisplayName("INV-ORG-13: every node has a depth-zero self-reference")
        void selfReferenceExists() {
            var closure = treeBefore();
            for (OrgNodeId n : List.of(GROUP, UNIT_A, UNIT_B, PROJECT)) {
                assertTrue(closure.subtreeOf(n).contains(n),
                        "without the self-reference, 'the subtree of X' excludes X and every scope query "
                                + "is subtly wrong (INV-ORG-13)");
            }
        }

        @Test
        @DisplayName("O14: a rebuild from parentage equals the stored closure")
        void rebuildEqualsStored() {
            var stored = treeBefore();
            var rebuilt = treeBefore();
            assertTrue(stored.diverges(rebuilt).reconciled(),
                    "the closure is a pure function of node parentage (INV-ORG-14)");
        }

        @Test
        @DisplayName("O14: an extraneous stored row is reported as EXCESS ACCESS, directionally")
        void extraneousRowIsExcessAccess() {
            var rebuilt = treeBefore();
            // The dangerous corruption: a row granting unit-b the project it does not own.
            var corrupted = new java.util.LinkedHashSet<>(rebuilt.edges());
            corrupted.add(new OrgClosure.Edge(UNIT_B, PROJECT, 1, 7L));
            var divergence = OrgClosure.ofStoredRows(corrupted).diverges(rebuilt);

            assertFalse(divergence.reconciled());
            assertTrue(divergence.grantsExcessAccess(),
                    "a row present in the stored closure but absent from the rebuild grants access that "
                            + "should not exist — and DOC-03 section 7.4 records that this is the case "
                            + "nobody reports, which is why reconciliation rather than inspection is the "
                            + "detection mechanism");
            assertTrue(divergence.missing().isEmpty());
        }

        @Test
        @DisplayName("O14: a missing stored row is reported as denied access, not conflated with excess")
        void missingRowIsDeniedAccess() {
            var rebuilt = treeBefore();
            var corrupted = new java.util.LinkedHashSet<>(rebuilt.edges());
            corrupted.removeIf(e -> e.ancestorId().equals(GROUP) && e.descendantId().equals(PROJECT));
            var divergence = OrgClosure.ofStoredRows(corrupted).diverges(rebuilt);

            assertFalse(divergence.reconciled());
            assertFalse(divergence.grantsExcessAccess());
            assertEquals(1, divergence.missing().size(),
                    "reported separately, because this case is noticed by a user within the hour and the "
                            + "other is never noticed at all");
        }

        @Test
        @DisplayName("INV-ORG-07: a cycle in parentage is rejected at build time, not traversed")
        void cycleIsRejected() {
            Map<OrgNodeId, OrgNodeId> cyclic = new HashMap<>();
            cyclic.put(UNIT_A, PROJECT);
            cyclic.put(PROJECT, UNIT_A);
            var ex = assertThrows(IllegalStateException.class,
                    () -> OrgClosure.buildFrom(cyclic, Set.of(UNIT_A, PROJECT), 1L));
            assertTrue(ex.getMessage().contains("INV-ORG-07"),
                    "a builder that looped would detect the cycle by exhausting memory, which is "
                            + "'detected later' in the worst possible form");
        }

        @Test
        @DisplayName("a dangling parent is rejected rather than silently truncating the path")
        void danglingParentIsRejected() {
            Map<OrgNodeId, OrgNodeId> dangling = Map.of(PROJECT, UNIT_A);
            assertThrows(IllegalStateException.class,
                    () -> OrgClosure.buildFrom(dangling, Set.of(PROJECT), 1L),
                    "a truncated ancestor path denies historical reads that should be permitted");
        }
    }

    // =========================================================== criticality

    @Nested
    @DisplayName("INV-ORG-08, INV-ORG-09 — criticality inheritance and justified override")
    class Criticality {

        private Map<OrgNodeId, CriticalityResolution.Assignment> assignments(
                CriticalityResolution.Assignment group, CriticalityResolution.Assignment unit,
                CriticalityResolution.Assignment project) {
            Map<OrgNodeId, CriticalityResolution.Assignment> map = new HashMap<>();
            map.put(GROUP, group);
            map.put(UNIT_A, unit);
            map.put(PROJECT, project);
            return map;
        }

        @Test
        @DisplayName("INV-ORG-08: an INHERITED node resolves from its nearest ASSIGNED ancestor")
        void inheritsFromNearestAssignedAncestor() {
            var path = treeBefore().ancestorPathTo(PROJECT);
            var resolved = CriticalityResolution.resolve(PROJECT, path, assignments(
                    CriticalityResolution.Assignment.assigned(TIER_MEDIUM, null, null, T0),
                    CriticalityResolution.Assignment.assigned(TIER_HIGH, "regulated workload", null, T0),
                    CriticalityResolution.Assignment.inherited()));

            var tier = (CriticalityResolution.Resolved.Tier) resolved;
            assertEquals(TIER_HIGH, tier.tierId(), "nearest, not root-most");
            assertEquals(UNIT_A, tier.sourceNodeId());
            assertTrue(tier.inherited());
        }

        @Test
        @DisplayName("INV-ORG-08: with no ASSIGNED ancestor, criticality is UNDEFINED and not defaulted")
        void undefinedRatherThanDefaulted() {
            var path = treeBefore().ancestorPathTo(PROJECT);
            var resolved = CriticalityResolution.resolve(PROJECT, path, assignments(
                    CriticalityResolution.Assignment.inherited(),
                    CriticalityResolution.Assignment.inherited(),
                    CriticalityResolution.Assignment.inherited()));

            assertTrue(resolved instanceof CriticalityResolution.Resolved.Undefined,
                    "a default would make a misconfigured tree look correctly configured and would "
                            + "understate criticality, which flows into scoring and then into deadlines");
        }

        @Test
        @DisplayName("INV-ORG-09: an override without a justification is rejected")
        void overrideRequiresJustification() {
            var path = treeBefore().ancestorPathTo(PROJECT);
            var invalid = CriticalityResolution.validateOverride(PROJECT, path, assignments(
                    CriticalityResolution.Assignment.assigned(TIER_MEDIUM, null, null, T0),
                    CriticalityResolution.Assignment.inherited(),
                    CriticalityResolution.Assignment.assigned(TIER_HIGH, "  ", null, T0)));

            assertTrue(invalid.isPresent(), "an unjustified override is indistinguishable from a mistake");
            assertTrue(invalid.orElseThrow().contains("INV-ORG-09"));
        }

        @Test
        @DisplayName("INV-ORG-09: an assignment with no ASSIGNED ancestor is not an override")
        void firstAssignmentIsNotAnOverride() {
            var path = treeBefore().ancestorPathTo(PROJECT);
            var invalid = CriticalityResolution.validateOverride(PROJECT, path, assignments(
                    CriticalityResolution.Assignment.inherited(),
                    CriticalityResolution.Assignment.inherited(),
                    CriticalityResolution.Assignment.assigned(TIER_HIGH, null, null, T0)));

            assertTrue(invalid.isEmpty(),
                    "requiring a justification where nothing is being overridden would make the field "
                            + "ceremonial, and a ceremonial field is filled in with 'n/a'");
        }

        @Test
        @DisplayName("an INHERITED assignment carrying a tier is not constructible")
        void inheritedCarriesNoTier() {
            assertThrows(IllegalArgumentException.class,
                    () -> new CriticalityResolution.Assignment(
                            CriticalityResolution.Mode.INHERITED, TIER_HIGH, null, null, T0),
                    "two sources of truth for one value is how they diverge");
        }
    }
}
