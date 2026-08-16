package aspm.kernel.authorization.contract;

import aspm.sharedkernel.OrgNodeId;
import java.util.List;
import java.util.Objects;

/**
 * The scope that was applied, recorded for audit and for effective-permission inspection.
 *
 * <p>{@code SEC-AUZ-047} requires effective-permission inspection to use the live evaluation path
 * rather than a reimplementation, so the applied scope must be part of the decision rather than
 * recomputed for display. A separate display path is a second implementation that will diverge, and
 * the divergence would be invisible — the inspection screen would show what the reimplementation
 * believes rather than what enforcement did.
 *
 * @param resolvedNodes the org nodes resolved from the principal's assignments
 * @param unrestricted true only for an enumerated platform operation
 * @param hierarchyVersion the tree version the resolution was computed against, so an audited
 *     decision can be re-derived after a reorganization ({@code INV-TEN-03})
 */
public record ScopeGrant(List<OrgNodeId> resolvedNodes, boolean unrestricted, long hierarchyVersion) {

    public ScopeGrant {
        resolvedNodes = List.copyOf(Objects.requireNonNull(resolvedNodes, "resolvedNodes is required"));
        if (hierarchyVersion < 1) {
            throw new IllegalArgumentException("hierarchy version is monotonic from 1 (INV-TEN-03)");
        }
    }

    public static ScopeGrant none(long hierarchyVersion) {
        return new ScopeGrant(List.of(), false, hierarchyVersion);
    }
}
