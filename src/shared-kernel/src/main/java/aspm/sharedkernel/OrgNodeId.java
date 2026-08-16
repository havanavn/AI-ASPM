package aspm.sharedkernel;

import java.util.Objects;
import java.util.UUID;

/** Organization node identity. Half of the Published Language of DOC-03 section 5.3 row 1. */
public record OrgNodeId(UUID value) {
    public OrgNodeId {
        Objects.requireNonNull(value, "org node identity is required");
    }
}
