package aspm.module.organizationscope.domain;

import aspm.sharedkernel.OrgNodeId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Criticality inheritance with justified override, per DOC-03 section 7.3 and {@code PRD-ORG-006},
 * {@code PRD-ORG-007}.
 *
 * <p>{@code INV-ORG-08}: a node with {@code mode = INHERITED} resolves from its nearest ancestor with
 * {@code mode = ASSIGNED}, and "at least one ancestor on every path to the root must be ASSIGNED, or
 * resolution is undefined".
 *
 * <p><b>Undefined is returned, not defaulted.</b> A resolver that substituted the least critical tier when
 * no ancestor is ASSIGNED would make a misconfigured tree look correctly configured, and it would
 * understate criticality — which flows into scoring and then into service level deadlines. Product
 * principle 1 applies: not-measured must be distinguishable from measured, and an unresolvable criticality
 * is the not-measured case.
 */
public final class CriticalityResolution {

    /** How a node's criticality is determined. */
    public enum Mode {
        ASSIGNED,
        INHERITED
    }

    /**
     * A node's criticality assignment, per DOC-03 section 7.3.
     *
     * @param tierId set if and only if {@link Mode#ASSIGNED}
     * @param justification required if and only if ASSIGNED <em>and overriding an ancestor</em>
     *     ({@code INV-ORG-09}); the overriding condition is not knowable from the node alone, so it is
     *     enforced by {@link #resolve} where the ancestry is in hand
     */
    public record Assignment(
            Mode mode, UUID tierId, String justification, UUID assignedBy, java.time.Instant assignedAt) {

        public Assignment {
            Objects.requireNonNull(mode, "mode is required");
            if (mode == Mode.ASSIGNED && tierId == null) {
                throw new IllegalArgumentException("an ASSIGNED criticality requires a tier (INV-ORG-08)");
            }
            if (mode == Mode.INHERITED && tierId != null) {
                throw new IllegalArgumentException(
                        "an INHERITED criticality must not carry a tier; two sources of truth for one "
                                + "value is how they diverge");
            }
        }

        public static Assignment inherited() {
            return new Assignment(Mode.INHERITED, null, null, null, null);
        }

        public static Assignment assigned(
                UUID tierId, String justification, UUID assignedBy, java.time.Instant at) {
            return new Assignment(Mode.ASSIGNED, tierId, justification, assignedBy, at);
        }
    }

    /** The outcome of resolution. */
    public sealed interface Resolved {

        /** Resolved to a tier, naming the node it came from so a reader can see why. */
        record Tier(UUID tierId, OrgNodeId sourceNodeId, boolean inherited) implements Resolved {}

        /**
         * No ancestor on the path is ASSIGNED, so criticality is undefined.
         *
         * <p>{@code INV-ORG-08} names this condition explicitly. Returned rather than defaulted, because a
         * default would understate criticality and flow into scoring and then into deadlines.
         */
        record Undefined(OrgNodeId nodeId, String reason) implements Resolved {}
    }

    private CriticalityResolution() {
        throw new AssertionError("not instantiable");
    }

    /**
     * Resolves criticality for {@code node}.
     *
     * @param ancestorPathRootFirst the root-to-node path, inclusive of the node, as produced by
     *     {@link OrgClosure#ancestorPathTo}
     * @param assignments each node's assignment
     */
    public static Resolved resolve(
            OrgNodeId node, List<OrgNodeId> ancestorPathRootFirst, Map<OrgNodeId, Assignment> assignments) {
        Objects.requireNonNull(node, "node is required");
        Objects.requireNonNull(ancestorPathRootFirst, "ancestorPath is required");
        Objects.requireNonNull(assignments, "assignments are required");

        if (ancestorPathRootFirst.isEmpty()
                || !ancestorPathRootFirst.get(ancestorPathRootFirst.size() - 1).equals(node)) {
            throw new IllegalArgumentException(
                    "the ancestor path must be root-first and end at the node being resolved");
        }

        // Nearest-first: the node itself, then upward. INV-ORG-08's "nearest ancestor with mode = ASSIGNED".
        List<OrgNodeId> nearestFirst = ancestorPathRootFirst.reversed();

        for (OrgNodeId candidate : nearestFirst) {
            Assignment assignment = assignments.get(candidate);
            if (assignment != null && assignment.mode() == Mode.ASSIGNED) {
                return new Resolved.Tier(assignment.tierId(), candidate, !candidate.equals(node));
            }
        }

        return new Resolved.Undefined(node,
                "no ancestor on the path to the root has mode = ASSIGNED, so criticality is undefined "
                        + "(INV-ORG-08). Returned rather than defaulted: a default would understate "
                        + "criticality, which flows into scoring and then into service level deadlines.");
    }

    /**
     * Validates {@code INV-ORG-09}: an override requires a non-empty justification.
     *
     * <p>An override is an ASSIGNED node with an ASSIGNED <em>ancestor</em>. That is why this cannot be a
     * constructor check on {@link Assignment} — the node alone does not know whether it is overriding, and
     * a check that cannot see the ancestry would either reject every assignment or none.
     *
     * @return the reason it is invalid, or empty where valid
     */
    public static Optional<String> validateOverride(
            OrgNodeId node, List<OrgNodeId> ancestorPathRootFirst, Map<OrgNodeId, Assignment> assignments) {
        Assignment own = assignments.get(node);
        if (own == null || own.mode() != Mode.ASSIGNED) {
            return Optional.empty();
        }

        // Strict ancestors only: the node's own assignment is not an override of itself.
        List<OrgNodeId> strictAncestors =
                ancestorPathRootFirst.subList(0, ancestorPathRootFirst.size() - 1);

        boolean overridesAnAncestor = strictAncestors.stream()
                .map(assignments::get)
                .filter(Objects::nonNull)
                .anyMatch(a -> a.mode() == Mode.ASSIGNED);

        if (overridesAnAncestor
                && (own.justification() == null || own.justification().isBlank())) {
            return Optional.of(
                    "node " + node.value() + " assigns a criticality that overrides an ancestor's, which "
                            + "requires a non-empty justification (INV-ORG-09). An unjustified override is "
                            + "indistinguishable from a mistake, and criticality flows into scoring.");
        }
        return Optional.empty();
    }
}
