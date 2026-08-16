package aspm.kernel.tenantcontext.contract;

import java.util.UUID;

/**
 * The single minting point for an {@link AuthorizedQuery}.
 *
 * <p>Extended only by the authorization kernel module. {@code SEC-AUZ-013} requires authorization to
 * be evaluated through a single contract with application code unable to implement its own check;
 * confining the mint to one abstract superclass is how "unable" is expressed here, and
 * {@code :architecture-tests} asserts that no class outside {@code aspm.kernel.authorization}
 * extends it.
 *
 * <p>Note what this class deliberately does <b>not</b> reference: nothing from the authorization
 * module. If it took an {@code AuthorizationDecision} as a parameter, {@code tenant-context} would
 * depend on {@code authorization} while {@code authorization} depends on {@code tenant-context} to
 * read the established context — a kernel cycle, which {@code CON-PLT-016} forbids and which would
 * be worse in the kernel than anywhere else because every module depends on all five. The
 * dependency is inverted here instead: the authorization module extends this class and supplies the
 * already-evaluated result.
 */
public abstract class AuthorizationGateway {

    protected AuthorizationGateway() {
        // Subclassing is the permission; instantiation by application code is not useful because
        // grant() is protected.
    }

    /**
     * Mints the key for an ALLOW decision.
     *
     * <p>There is deliberately no overload for a denial. A denial produces no key, so the caller has
     * nothing to pass to the gate and the query is not executable — which is {@code SEC-AUZ-014}'s
     * deny-by-default expressed as an absence rather than as a branch that could be inverted.
     *
     * @param decisionRef the audited decision this access is correlated with
     * @param permissionCode the product-fixed permission that was evaluated
     * @param scope the resolved predicate the retrieval layer must compose into the query
     * @param historical whether evaluation used a recorded historical descriptor
     */
    protected static AuthorizedQuery grant(
            UUID decisionRef, String permissionCode, ScopePredicate scope, boolean historical) {
        if (scope.matchesNothing()) {
            throw new IllegalArgumentException(
                    "an ALLOW decision was minted with a predicate matching nothing. Either the "
                            + "decision should have been a denial, or scope resolution failed — and "
                            + "SEC-AUZ-014 requires denial on unavailable scope resolution rather than "
                            + "an allow over an empty set, which reads as 'no data' to the caller.");
        }
        return new AuthorizedQuery(decisionRef, permissionCode, scope, historical);
    }
}
