package aspm.module.organizationscope.contract;

import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.PrincipalId;
import aspm.sharedkernel.ScopeDescriptor;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The published language through which other contexts resolve organizational scope, per DOC-03
 * section 5.3 row 1.
 *
 * <p>"Every context needs organizational scope. A shared kernel would let downstream contexts modify the
 * hierarchy model; a published language — {@code OrgNodeId} plus an immutable {@code ScopeDescriptor} — gives
 * them what they need without that. Downstream contexts consume; they do not extend."
 *
 * <p>Two operations, deliberately separate, because DOC-03 section 7.5 makes them different queries over
 * different data: <em>as-is</em> uses the current tree, <em>as-was</em> uses the recorded descriptor. DOC-03
 * is explicit that "both must be available, and which one a given report uses must be stated on the report" —
 * so they cannot be one method with a nullable instant, which is how a caller ends up not knowing which it
 * asked for.
 */
public interface ScopeResolutionQuery {

    /**
     * Current scope: the nodes a principal's assignments reach in the tree as it is now.
     *
     * <p>Returns the resolved subtree union, not the assignments. {@code SEC-AUZ-010} requires multiple
     * assignments to resolve as a union of permission-scope pairs and <b>not a cross product</b>, and the
     * cross-product mistake is only available to a caller that receives raw assignments and combines them
     * itself.
     */
    Resolution resolveCurrent(PrincipalId principal, String permissionCode);

    /**
     * Historical scope: whether the principal's accountability at {@code at} covered the given descriptor.
     *
     * <p>Read-only by construction — the return type carries no grant, only a verdict.
     * {@code SEC-AUZ-028} and {@code SEC-AUZ-029}: historical evaluation is read-only and grants nothing for
     * objects created after a move.
     */
    HistoricalVerdict wasAuthorized(PrincipalId principal, String permissionCode, ScopeDescriptor descriptor,
            Instant at);

    /**
     * The outcome of current resolution.
     *
     * @param permittedNodes the union of subtrees the principal reaches, already expanded
     * @param hierarchyVersion the tree version this was computed against, so an audited decision can be
     *     re-derived later ({@code INV-TEN-03})
     * @param unavailable set where resolution could not be performed — {@code SEC-AUZ-014} requires denial on
     *     unavailable scope resolution, so this is a distinct outcome and not an empty permitted set
     */
    record Resolution(
            List<OrgNodeId> permittedNodes, long hierarchyVersion, Optional<String> unavailable) {

        public Resolution {
            permittedNodes = List.copyOf(Objects.requireNonNull(permittedNodes, "permittedNodes is required"));
            Objects.requireNonNull(unavailable, "unavailable is required; use Optional.empty()");
            if (unavailable.isPresent() && !permittedNodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "an unavailable resolution must carry no nodes; a partial resolution presented as a "
                                + "successful one is how a principal silently loses access to part of their scope");
            }
        }

        public static Resolution of(List<OrgNodeId> nodes, long hierarchyVersion) {
            return new Resolution(nodes, hierarchyVersion, Optional.empty());
        }

        /**
         * Resolution could not be performed.
         *
         * <p>Distinct from an empty permitted set. An empty set means "this principal reaches nothing", which
         * is a legitimate configuration; unavailable means "we do not know", and {@code SEC-AUZ-014} requires
         * denial in that case for a different reason. Conflating them would make a closure outage look like a
         * permissions problem.
         */
        public static Resolution unavailable(String reason, long hierarchyVersion) {
            return new Resolution(List.of(), hierarchyVersion,
                    Optional.of(Objects.requireNonNull(reason, "a reason is required")));
        }

        public boolean isUnavailable() {
            return unavailable.isPresent();
        }
    }

    /** The outcome of historical evaluation. Carries no grant — only whether access was held. */
    record HistoricalVerdict(boolean authorized, String basis) {

        public HistoricalVerdict {
            Objects.requireNonNull(basis, "a basis is required so an audited decision can be explained");
        }
    }
}
