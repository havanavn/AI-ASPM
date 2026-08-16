package aspm.kernel.tenantcontext.contract;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Implicit propagation of the tenant context through the call chain, per {@code CON-PLT-035}.
 *
 * <p><b>Why implicit and not a parameter.</b> {@code CON-PLT-035} requires implicit propagation
 * precisely because explicit propagation is forgotten in the deep call paths where it matters, and
 * an omitted parameter compiles. The mandatory gate is at data access ({@code CON-PLT-036}), not at
 * every signature.
 *
 * <p><b>Why a scoped value and not a plain thread local.</b> A plain thread local leaks across a
 * pooled thread's next task, which is DOC-24 section 6.2 entry 5 applied to threads rather than to
 * connections. {@link ScopedValue} is bounded by the {@code where} call: outside the enclosing
 * operation the binding does not exist, so a leak is not expressible rather than merely unlikely.
 * The same property is what makes {@link #requireCurrent} safe to fail closed.
 *
 * <p>Asynchronous work does <em>not</em> inherit this binding by accident. {@code SEC-TEN-006}
 * requires an explicit binding to enqueue, and a work item without one must not execute; see
 * {@code TenantBoundWork}.
 */
public final class TenantContextHolder {

    private static final ScopedValue<TenantContext> CURRENT = ScopedValue.newInstance();

    private TenantContextHolder() {
        throw new AssertionError("not instantiable");
    }

    /** Runs {@code body} with {@code context} established, and only for its duration. */
    public static <T> T with(TenantContext context, Supplier<T> body) {
        java.util.Objects.requireNonNull(context, "context is required; SEC-TEN-005 fails closed");
        java.util.Objects.requireNonNull(body, "body is required");
        return ScopedValue.where(CURRENT, context).call(body::get);
    }

    /** Runs {@code body} with {@code context} established, propagating a checked exception. */
    public static <T> T callWith(TenantContext context, Callable<T> body) throws Exception {
        java.util.Objects.requireNonNull(context, "context is required; SEC-TEN-005 fails closed");
        java.util.Objects.requireNonNull(body, "body is required");
        return ScopedValue.where(CURRENT, context).call(body::call);
    }

    public static void runWith(TenantContext context, Runnable body) {
        java.util.Objects.requireNonNull(context, "context is required; SEC-TEN-005 fails closed");
        java.util.Objects.requireNonNull(body, "body is required");
        ScopedValue.where(CURRENT, context).run(body);
    }

    /**
     * The established context, or empty.
     *
     * <p>Reserved for the narrow cases that legitimately behave differently with and without a
     * context — request logging and error rendering. <b>Data access must use
     * {@link #requireCurrent}</b>, because an {@code Optional} invites an {@code orElse} that
     * substitutes a default, and a default tenant is a cross-tenant read.
     */
    public static Optional<TenantContext> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /**
     * The established context, or raise.
     *
     * @throws MissingTenantContextException always, where no context is established
     */
    public static TenantContext requireCurrent(String attemptedOperation) {
        if (!CURRENT.isBound()) {
            throw new MissingTenantContextException(attemptedOperation);
        }
        return CURRENT.get();
    }

    public static boolean isEstablished() {
        return CURRENT.isBound();
    }
}
