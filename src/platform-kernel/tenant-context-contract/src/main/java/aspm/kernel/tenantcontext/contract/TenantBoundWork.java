package aspm.kernel.tenantcontext.contract;

import java.util.Objects;

/**
 * Asynchronous work with its tenant binding as a required constructor argument, per
 * {@code SEC-TEN-006} and DOC-24 section 6.2 entry 2.
 *
 * <p>DOC-24 identifies asynchronous work as where cross-tenant iteration actually happens, because
 * it has no ambient request to inherit from and is written by engineers holding a whole-system
 * mental model. The requirement is that a work item without an explicit binding must not execute.
 *
 * <p>This type makes that structural rather than procedural: there is no constructor without a
 * context, so an unbound work item is not constructible. A job that must span tenants is expressed
 * as a loop of per-tenant items, each with its own context — never as one cross-tenant query, which
 * DOC-24 section 6.2 entry 2 names as the failure.
 *
 * @param <T> the work's result type
 */
public final class TenantBoundWork<T> {

    private final TenantContext context;
    private final String workClass;
    private final java.util.function.Supplier<T> body;

    public TenantBoundWork(
            TenantContext context, String workClass, java.util.function.Supplier<T> body) {
        this.context = Objects.requireNonNull(context,
                "asynchronous work requires an explicit tenant binding and must not execute "
                        + "without one (SEC-TEN-006)");
        this.workClass = Objects.requireNonNull(workClass,
                "work class is required; DOC-02 section 12.1 isolates queues per class");
        this.body = Objects.requireNonNull(body, "body is required");
    }

    public TenantContext context() {
        return context;
    }

    /** The DOC-02 section 12.1 work class, which decides queue isolation per {@code CON-PLT-030}. */
    public String workClass() {
        return workClass;
    }

    /** Executes the body with the bound context established, and only for its duration. */
    public T execute() {
        return TenantContextHolder.with(context, body);
    }
}
