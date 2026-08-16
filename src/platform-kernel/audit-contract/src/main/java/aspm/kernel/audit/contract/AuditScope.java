package aspm.kernel.audit.contract;

import aspm.sharedkernel.OrgNodeId;
import java.util.List;
import java.util.Objects;

/**
 * The organizational scope in effect when the event occurred, per {@code SEC-AUD-003} and
 * {@code PRD-AUD-004}.
 *
 * <p>Recorded, never resolved on read. "Without it, reorganization retroactively changes who appears
 * to have been authorized for a past action, making the trail unusable as evidence about the prior
 * period." The hierarchy version is what lets a later reader tell which tree the path belonged to
 * ({@code INV-TEN-03}).
 */
public record AuditScope(OrgNodeId nodeId, List<OrgNodeId> ancestorPath, long hierarchyVersion) {

    public AuditScope {
        ancestorPath = List.copyOf(Objects.requireNonNull(ancestorPath, "ancestorPath is required"));
        if (hierarchyVersion < 1) {
            throw new IllegalArgumentException("hierarchy version is monotonic from 1 (INV-TEN-03)");
        }
    }

    /** For an event with no organizational scope, such as a tenant lifecycle event. */
    public static AuditScope unscoped(long hierarchyVersion) {
        return new AuditScope(null, List.of(), hierarchyVersion);
    }
}
