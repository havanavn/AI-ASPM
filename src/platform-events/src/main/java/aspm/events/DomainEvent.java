package aspm.events;

import aspm.sharedkernel.TenantId;
import java.time.Instant;

/**
 * The published event surface. Every event carries its tenant explicitly, because
 * SEC-TEN-006 forbids asynchronous work from inferring a tenant binding and DOC-24
 * section 6.2 entry 2 identifies asynchronous iteration as where cross-tenant access
 * actually happens.
 */
public interface DomainEvent {
    TenantId tenantId();

    Instant occurredAt();

    /** Correlation identifier propagated across module, process and queue boundaries (CON-PLT-042). */
    String correlationId();
}
