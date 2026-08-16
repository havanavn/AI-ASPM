package aspm.module.organizationscope.application;

import aspm.module.organizationscope.contract.ScopeResolutionQuery;
import aspm.module.organizationscope.domain.OrgClosure;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.PrincipalId;
import aspm.sharedkernel.ScopeDescriptor;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Scope resolution over the closure projection.
 *
 * <p>This is what turns prompt 3's authorization kernel from denying everything into functioning: its
 * {@code DenyAllScopeResolver} returned {@code SCOPE_RESOLUTION_UNAVAILABLE} because the closure did not
 * exist, and {@code SEC-AUZ-014} names that condition as one of the four that must deny.
 *
 * <p><b>Assignments resolve as a union, never a cross product.</b> {@code SEC-AUZ-010} and A5 of DOC-16
 * section 6. A principal with (read, unit-A) and (approve, unit-B) must not acquire (approve, unit-A). This
 * class is given the assignments for <em>one</em> permission and unions their subtrees, so the cross product
 * is not expressible here — the permission is a parameter, not part of the returned data.
 */
public final class ClosureBackedScopeResolution implements ScopeResolutionQuery {

    /** Supplies a principal's node assignments for one permission. */
    public interface AssignmentSource {

        /**
         * Returns the nodes assigned to {@code principal} for {@code permissionCode}, or empty where none.
         *
         * <p>Per permission by design: a source returning all assignments would hand the caller the material
         * for the cross product {@code SEC-AUZ-010} prohibits.
         */
        List<OrgNodeId> assignedNodes(PrincipalId principal, String permissionCode);
    }

    /** Supplies the current closure and hierarchy version, or signals unavailability. */
    public interface ClosureSource {

        /**
         * Returns the current closure, or null where it cannot be read.
         *
         * <p>Null rather than an exception, because unavailability is an outcome the resolver converts into
         * {@code Resolution.unavailable} — and SEC-AUZ-014 requires denial on unavailable scope resolution
         * rather than a propagated failure a caller might catch and continue past.
         */
        OrgClosure currentClosure();

        /** Returns the hierarchy version the closure was read at. */
        long hierarchyVersion();
    }

    private final AssignmentSource assignments;
    private final ClosureSource closures;

    public ClosureBackedScopeResolution(AssignmentSource assignments, ClosureSource closures) {
        this.assignments = Objects.requireNonNull(assignments, "assignments source is required");
        this.closures = Objects.requireNonNull(closures, "closure source is required");
    }

    @Override
    public Resolution resolveCurrent(PrincipalId principal, String permissionCode) {
        Objects.requireNonNull(principal, "principal is required");
        Objects.requireNonNull(permissionCode, "permissionCode is required");

        long version = closures.hierarchyVersion();
        OrgClosure closure = closures.currentClosure();
        if (closure == null) {
            // Not an empty permitted set: SEC-AUZ-014 requires denial on unavailable scope resolution, and a
            // closure outage presented as "reaches nothing" would look like a permissions problem.
            return Resolution.unavailable(
                    "the closure projection could not be read; denying rather than resolving to nothing "
                            + "(SEC-AUZ-014)", version);
        }

        List<OrgNodeId> assigned = assignments.assignedNodes(principal, permissionCode);
        if (assigned == null) {
            return Resolution.unavailable("assignment lookup failed for " + permissionCode, version);
        }

        // Union of subtrees. Each assigned node contributes itself and its descendants, which is what makes
        // INV-ORG-13's self-reference load-bearing — without it an assignment to a node would not authorize
        // the node.
        Set<OrgNodeId> permitted = new LinkedHashSet<>();
        for (OrgNodeId node : assigned) {
            permitted.addAll(closure.subtreeOf(node));
        }
        return Resolution.of(List.copyOf(permitted), version);
    }

    @Override
    public HistoricalVerdict wasAuthorized(
            PrincipalId principal, String permissionCode, ScopeDescriptor descriptor, Instant at) {
        Objects.requireNonNull(principal, "principal is required");
        Objects.requireNonNull(permissionCode, "permissionCode is required");
        Objects.requireNonNull(descriptor, "descriptor is required");
        Objects.requireNonNull(at, "the evaluation instant is required");

        // The descriptor's recorded ancestor path is the whole of the structural input. The current closure is
        // deliberately NOT consulted: DOC-04 section 6.6 records that a join to a historical closure "would
        // require reconstructing the closure at scope_hierarchy_ver, which is precisely the reconstruction
        // the embedded descriptor exists to avoid".
        List<OrgNodeId> assigned = assignments.assignedNodes(principal, permissionCode);
        if (assigned == null) {
            return new HistoricalVerdict(false,
                    "assignment lookup failed; denying (SEC-AUZ-014)");
        }

        for (OrgNodeId node : assigned) {
            if (descriptor.withinScopeOf(node)) {
                return new HistoricalVerdict(true,
                        "assignment at node " + node.value() + " lies on the recorded ancestor path of the "
                                + "object, hierarchy version " + descriptor.hierarchyVersion());
            }
        }
        return new HistoricalVerdict(false,
                "no assignment lies on the object's recorded ancestor path at hierarchy version "
                        + descriptor.hierarchyVersion());
    }

    /**
     * A caveat a reviewer should hold onto.
     *
     * <p>{@link #wasAuthorized} evaluates the principal's <b>current</b> assignments against the object's
     * <b>historical</b> scope. That is what DOC-03 section 6.7's "deliberate limit" prescribes — the descriptor
     * "does not record who held which role, which is Authorization's history and is owned by DOC-07" — so a
     * principal whose assignments changed is evaluated on today's assignments over yesterday's tree.
     *
     * <p>Whether that composition is correct for every case is a DOC-07 question, not this module's. It is
     * named here because the alternative reading — that this method reproduces the full authorization state of
     * a past instant — would be wrong, and a caller assuming it would build a historical report that is
     * subtly not reproducible.
     */
    public static String historicalEvaluationCaveat() {
        return "wasAuthorized composes CURRENT assignments with HISTORICAL scope; assignment history is "
                + "DOC-07's concern per DOC-03 section 6.7's deliberate limit";
    }
}
