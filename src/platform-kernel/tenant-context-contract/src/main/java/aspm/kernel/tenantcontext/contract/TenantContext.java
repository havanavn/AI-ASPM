package aspm.kernel.tenantcontext.contract;

import aspm.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The request-scoped, immutable tenant context of DOC-24 section 5.2.
 *
 * <p>Immutability is not stylistic: a mutable context is a cross-tenant transfer primitive for any
 * code holding a reference, and {@code SEC-TEN-001} states there is no legitimate operation that
 * moves a record between tenants.
 *
 * <p>There is deliberately no constructor taking a tenant identifier alone. Every context states
 * where it came from, so that a context assembled from a request field cannot be built without
 * naming a provenance that does not fit — which is the point at which review notices.
 */
public record TenantContext(
        TenantId tenantId,
        String residencyRegion,
        EstablishedFrom establishedFrom,
        Instant establishedAt,
        UUID breakGlassRef) {

    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(residencyRegion, "residencyRegion is required; SEC-TEN-018");
        Objects.requireNonNull(establishedFrom, "establishedFrom is required; SEC-TEN-004");
        Objects.requireNonNull(establishedAt, "establishedAt is required");
        if (establishedFrom == EstablishedFrom.BREAK_GLASS_GRANT && breakGlassRef == null) {
            throw new IllegalArgumentException(
                    "a break-glass context must carry its grant reference; SEC-TEN-030 makes "
                            + "break-glass activity visible to the tenant, which requires the reference");
        }
        if (establishedFrom != EstablishedFrom.BREAK_GLASS_GRANT && breakGlassRef != null) {
            throw new IllegalArgumentException(
                    "a grant reference is present on a context that is not break-glass");
        }
    }

    /** Ordinary context from an authenticated principal or a service credential. */
    public static TenantContext of(
            TenantId tenantId, String residencyRegion, EstablishedFrom establishedFrom, Instant at) {
        if (establishedFrom == EstablishedFrom.BREAK_GLASS_GRANT) {
            throw new IllegalArgumentException("use breakGlass() for a break-glass context");
        }
        return new TenantContext(tenantId, residencyRegion, establishedFrom, at, null);
    }

    public static TenantContext breakGlass(
            TenantId tenantId, String residencyRegion, Instant at, UUID grantRef) {
        return new TenantContext(tenantId, residencyRegion, EstablishedFrom.BREAK_GLASS_GRANT, at,
                Objects.requireNonNull(grantRef, "break-glass grant reference is required"));
    }

    public Optional<UUID> breakGlassReference() {
        return Optional.ofNullable(breakGlassRef);
    }
}
