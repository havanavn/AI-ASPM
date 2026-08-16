package aspm.kernel.tenantcontext.contract;

/**
 * Raised where data access is attempted with no established tenant context.
 *
 * <p>{@code SEC-TEN-005} requires data access to fail closed with no permissive default, no
 * "unscoped" mode, and no administrative bypass reachable from application code. This is the
 * application-layer counterpart of {@code CON-DAT-013}, which requires the database's tenant
 * function to raise rather than return null — for the same reason. A null or empty result is
 * indistinguishable from legitimately empty data; an exception is a visible malfunction.
 *
 * <p>Deliberately unchecked. A checked exception here would be caught and swallowed at the call
 * sites least likely to handle it correctly, and an empty catch block would reintroduce exactly the
 * silent behaviour this type exists to prevent.
 */
public final class MissingTenantContextException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public MissingTenantContextException(String attemptedOperation) {
        super("no tenant context established for: " + attemptedOperation
                + " — data access fails closed (SEC-TEN-005). A tenant context is established at "
                + "request entry from the credential and is never derived from the request "
                + "(SEC-TEN-004); asynchronous work carries an explicit binding (SEC-TEN-006).");
    }
}
