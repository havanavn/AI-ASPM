package aspm.module.organizationscope.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The tenant-defined vocabulary and structural rules of the organization tree, per DOC-03 section 7.2.
 *
 * <p>"Its existence is a direct consequence of ADR-027." There is no fixed depth and no fixed level names
 * ({@code PRD-ORG-001}, {@code PRD-ORG-004}): adding an organizational level is a new type with appropriate
 * permitted parents — configuration, with no schema change and no impact on existing nodes.
 *
 * <p>Validated as a <b>set</b> rather than per row, because {@code INV-ORG-01} and {@code INV-ORG-02} are
 * set-level properties. DOC-04 section 11.2.1 records this explicitly: "at least one type per tenant has no
 * permitted parents" is "a set-level assertion the engine cannot express per row; validated at configuration
 * time ({@code CFG-PLT-009})". A per-row check would either pass on a rootless catalogue or reject the first
 * type ever defined.
 */
public final class OrgNodeTypeCatalogue {

    /** One node type. {@code code} is immutable; {@code label} is freely editable ({@code INV-ORG-04}). */
    public record NodeType(
            UUID id,
            String code,
            Map<String, String> label,
            Set<UUID> permittedParentTypes,
            boolean mayOwnAssets,
            boolean mayScopeWork,
            int displayOrder,
            Lifecycle lifecycleState) {

        public enum Lifecycle {
            ACTIVE,
            /**
             * No new nodes of this type; existing nodes keep working.
             *
             * <p>There is no {@code DELETED}: {@code INV-ORG-03} permits deprecation only for a type in use,
             * and {@code CON-DAT-011} prohibits a soft-delete flag in favour of an explicit lifecycle state.
             */
            DEPRECATED
        }

        public NodeType {
            Objects.requireNonNull(id, "id is required");
            Objects.requireNonNull(code, "code is required");
            Objects.requireNonNull(lifecycleState, "lifecycleState is required");
            label = Map.copyOf(Objects.requireNonNull(label, "label is required"));
            permittedParentTypes =
                    Set.copyOf(Objects.requireNonNull(permittedParentTypes, "permittedParentTypes is required"));

            if (!code.matches("^[A-Z][A-Z0-9_]{0,62}$")) {
                throw new IllegalArgumentException(
                        "node type code '" + code + "' is not a stable upper-snake identifier. DOC-03 "
                                + "section 7.2: the code is what integrations, saved queries, imports and API "
                                + "consumers reference, and a code that can vary breaks all of them silently, "
                                + "as empty results rather than errors.");
            }
            if (label.isEmpty()) {
                throw new IllegalArgumentException(
                        "a label is required in at least one locale; the label is what users see and "
                                + "NFR-INT-003 makes Vietnamese the first target locale");
            }
            if (permittedParentTypes.contains(id)) {
                throw new IllegalArgumentException(
                        "type " + code + " permits itself as a parent, which is the trivial type-level cycle "
                                + "INV-ORG-02 rejects at configuration time");
            }
        }

        /** True where a node of this type may be a tree root ({@code INV-ORG-01}). */
        public boolean mayBeRoot() {
            return permittedParentTypes.isEmpty();
        }
    }

    /** A configuration-time finding. */
    public record Finding(String invariant, String detail) {}

    private OrgNodeTypeCatalogue() {
        throw new AssertionError("not instantiable");
    }

    /**
     * Validates a complete catalogue before activation, per {@code CFG-PLT-009}.
     *
     * <p>Returns every finding rather than the first. A tenant configuring a hierarchy fixes the whole
     * catalogue in one pass; reporting one problem at a time turns onboarding into a guessing game.
     */
    public static List<Finding> validate(Set<NodeType> catalogue) {
        Objects.requireNonNull(catalogue, "catalogue is required");
        List<Finding> findings = new ArrayList<>();

        Map<UUID, NodeType> byId = new java.util.HashMap<>();
        for (NodeType type : catalogue) {
            NodeType clash = byId.put(type.id(), type);
            if (clash != null) {
                findings.add(new Finding("INV-ORG-04",
                        "two types share id " + type.id() + " (" + clash.code() + ", " + type.code() + ")"));
            }
        }

        Set<String> codes = new LinkedHashSet<>();
        for (NodeType type : catalogue) {
            if (!codes.add(type.code())) {
                findings.add(new Finding("INV-ORG-04",
                        "type code '" + type.code() + "' is not unique within the tenant"));
            }
        }

        // INV-ORG-01 — without a rootable type no tree can exist, and every node creation would fail with
        // a parent-type error that names the wrong problem.
        boolean anyRootable = catalogue.stream()
                .filter(t -> t.lifecycleState() == NodeType.Lifecycle.ACTIVE)
                .anyMatch(NodeType::mayBeRoot);
        if (!catalogue.isEmpty() && !anyRootable) {
            findings.add(new Finding("INV-ORG-01",
                    "no ACTIVE type has empty permittedParentTypes, so no tree can be rooted. Every node "
                            + "creation would fail with a parent-type error naming the wrong problem."));
        }

        // Dangling references, before cycle detection so a cycle report is not confused by a missing type.
        for (NodeType type : catalogue) {
            for (UUID parent : type.permittedParentTypes()) {
                if (!byId.containsKey(parent)) {
                    findings.add(new Finding("INV-ORG-02",
                            "type '" + type.code() + "' permits parent type " + parent
                                    + ", which is not in the catalogue"));
                }
            }
        }

        // INV-ORG-02 — type-level cycles, "rejected at configuration time, independently of instance-level
        // cycle rejection". The two are genuinely separate: a legal type graph still permits an illegal
        // instance tree, and a cyclic type graph makes every instance tree unreachable.
        for (NodeType type : catalogue) {
            List<String> cycle = findCycleFrom(type, byId);
            if (cycle != null) {
                findings.add(new Finding("INV-ORG-02",
                        "type-level cycle in the permitted-parent relation: " + String.join(" -> ", cycle)));
                break;   // one cycle report is actionable; every rotation of it is noise
            }
        }

        return List.copyOf(findings);
    }

    /** Depth-first search over the permitted-parent relation. Returns the cycle path, or null. */
    private static List<String> findCycleFrom(NodeType start, Map<UUID, NodeType> byId) {
        Set<UUID> onPath = new LinkedHashSet<>();
        List<String> trail = new ArrayList<>();
        return walk(start, byId, onPath, trail) ? trail : null;
    }

    private static boolean walk(
            NodeType current, Map<UUID, NodeType> byId, Set<UUID> onPath, List<String> trail) {
        if (!onPath.add(current.id())) {
            trail.add(current.code());
            return true;
        }
        trail.add(current.code());
        for (UUID parentId : current.permittedParentTypes()) {
            NodeType parent = byId.get(parentId);
            if (parent != null && walk(parent, byId, onPath, trail)) {
                return true;
            }
        }
        onPath.remove(current.id());
        trail.remove(trail.size() - 1);
        return false;
    }

    /** True where a node of {@code childType} may sit under a parent of {@code parentType} ({@code INV-ORG-06}). */
    public static boolean permitsParent(NodeType childType, NodeType parentType) {
        Objects.requireNonNull(childType, "childType is required");
        if (parentType == null) {
            return childType.mayBeRoot();
        }
        return childType.permittedParentTypes().contains(parentType.id());
    }
}
