package aspm.kernel.tenantcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.kernel.tenantcontext.contract.EstablishedFrom;
import aspm.kernel.tenantcontext.contract.MissingTenantContextException;
import aspm.kernel.tenantcontext.contract.ScopePredicate;
import aspm.kernel.tenantcontext.contract.TenantBoundWork;
import aspm.kernel.tenantcontext.contract.TenantContext;
import aspm.kernel.tenantcontext.contract.TenantContextHolder;
import aspm.sharedkernel.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SEC-TEN-004 through SEC-TEN-007 at the application layer.
 *
 * <p>These are the assertions the prompt 3 review point asks for on the application side: attempting
 * data access with no tenant context must fail visibly, not return empty. The database side of the
 * same review point is {@code CON-DAT-012} and {@code CON-DAT-013} and requires a live engine — see
 * {@code KernelPersistenceVerificationTest}.
 *
 * <p>Runs without a database, per {@code TST-PLT-005}: an invariant test requiring infrastructure is
 * a test that gets run less often, and {@code CON-PLT-017} exists so these are fast.
 */
class FailClosedTest {

    private static TenantContext context() {
        return TenantContext.of(new TenantId(UUID.randomUUID()), "vn-south",
                EstablishedFrom.AUTHENTICATED_PRINCIPAL, Instant.now());
    }

    @Test
    @DisplayName("SEC-TEN-005: requiring the context with none established raises rather than returning empty")
    void requireCurrentRaisesWhenUnestablished() {
        assertFalse(TenantContextHolder.isEstablished());
        var raised = assertThrows(MissingTenantContextException.class,
                () -> TenantContextHolder.requireCurrent("finding list"));
        assertTrue(raised.getMessage().contains("SEC-TEN-005"),
                "the failure must name the requirement so the reason survives into a log");
    }

    @Test
    @DisplayName("SEC-TEN-005: there is no permissive default and no unscoped mode")
    void thereIsNoUnscopedMode() {
        // current() is Optional by design for logging and error rendering. The assertion is that it
        // is EMPTY rather than carrying a default tenant: a default tenant is a cross-tenant read.
        assertTrue(TenantContextHolder.current().isEmpty());
    }

    @Test
    @DisplayName("CON-PLT-035: the context is established only for the duration of the body")
    void contextDoesNotOutliveItsScope() {
        var ctx = context();
        var seen = TenantContextHolder.with(ctx, () -> TenantContextHolder.requireCurrent("probe"));
        assertEquals(ctx, seen);
        // The binding is bounded by the call. A thread local would still hold it here, which is
        // DOC-24 section 6.2 entry 5 applied to threads rather than to connections.
        assertFalse(TenantContextHolder.isEstablished(),
                "the context outlived its scope, which is the pooled-thread leak this design avoids");
    }

    @Test
    @DisplayName("SEC-TEN-004: a context cannot be built without stating a provenance")
    void provenanceIsRequired() {
        assertThrows(NullPointerException.class,
                () -> new TenantContext(new TenantId(UUID.randomUUID()), "vn-south", null,
                        Instant.now(), null));
    }

    @Test
    @DisplayName("SEC-TEN-030: a break-glass context cannot exist without its grant reference")
    void breakGlassRequiresItsReference() {
        assertThrows(IllegalArgumentException.class,
                () -> new TenantContext(new TenantId(UUID.randomUUID()), "vn-south",
                        EstablishedFrom.BREAK_GLASS_GRANT, Instant.now(), null));
        // And the inverse: a grant reference on a non-break-glass context is equally wrong, because
        // it would make break-glass activity look ordinary in the audit trail.
        assertThrows(IllegalArgumentException.class,
                () -> new TenantContext(new TenantId(UUID.randomUUID()), "vn-south",
                        EstablishedFrom.SERVICE_CREDENTIAL, Instant.now(), UUID.randomUUID()));
    }

    @Test
    @DisplayName("SEC-TEN-006: asynchronous work is not constructible without an explicit tenant binding")
    void asyncWorkRequiresExplicitBinding() {
        assertThrows(NullPointerException.class,
                () -> new TenantBoundWork<String>(null, "BATCH", () -> "x"));
    }

    @Test
    @DisplayName("SEC-TEN-006: queued work does not inherit an ambient context; it establishes its own")
    void asyncWorkEstablishesItsOwnContext() {
        var jobContext = TenantContext.of(new TenantId(UUID.randomUUID()), "vn-south",
                EstablishedFrom.SCHEDULED_JOB_BINDING, Instant.now());
        var work = new TenantBoundWork<>(jobContext, "BATCH",
                () -> TenantContextHolder.requireCurrent("job body"));

        // Executed from a caller with a DIFFERENT context established. The work must see its own
        // binding, not the caller's — otherwise a job enqueued by tenant A and executed while
        // tenant B's request is in flight would read B's data.
        var callerContext = context();
        var seen = TenantContextHolder.with(callerContext, work::execute);

        assertEquals(jobContext, seen, "queued work inherited the ambient context instead of its binding");
    }

    @Test
    @DisplayName("SEC-AUZ-014: the empty scope predicate is distinct from the unrestricted one")
    void emptyAndUnrestrictedAreNotConflated() {
        assertTrue(ScopePredicate.none().matchesNothing());
        assertFalse(new ScopePredicate(java.util.List.of(), true).matchesNothing());
        // Conflating them is how a resolution failure becomes a full read.
        assertThrows(IllegalArgumentException.class,
                () -> new ScopePredicate(
                        java.util.List.of(new aspm.sharedkernel.OrgNodeId(UUID.randomUUID())), true));
    }
}
