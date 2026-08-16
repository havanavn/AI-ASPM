package aspm.sharedkernel;

import java.util.Objects;
import java.util.UUID;

/**
 * Tenant identity. The hard isolation boundary of ADR-002.
 *
 * <p>Deliberately not a bare {@code UUID}: a distinct type is what lets the
 * authorization and persistence gates require a tenant where one is meant, and lets
 * SEC-TEN-004 be checked by the compiler rather than by attention.
 */
public record TenantId(UUID value) {
    public TenantId {
        Objects.requireNonNull(value, "tenant identity is required; SEC-TEN-005 fails closed");
    }
}
