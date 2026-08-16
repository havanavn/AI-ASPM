package aspm.sharedkernel;

import java.util.Objects;
import java.util.UUID;

/**
 * Principal identity.
 *
 * <p>Published here rather than by the identity module so that the authorization kernel
 * can accept a principal without depending on a domain module, which CON-PLT-011
 * prohibits. DOC-03 section 5.3 records Identity as upstream of Authorization in
 * Customer-Supplier; the shared kernel is how that is satisfied without the dependency.
 */
public record PrincipalId(UUID value) {
    public PrincipalId {
        Objects.requireNonNull(value, "principal identity is required");
    }
}
