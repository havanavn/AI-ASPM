package aspm.kernel.authorization.contract;

import aspm.kernel.tenantcontext.contract.TenantContext;

/**
 * Resolves a principal's assignments into the scope an operation may reach.
 *
 * <p><b>In the contract, not the implementation, and the build is what established that.</b> This port was
 * first placed in {@code authorization-impl}, where it compiled and looked correct — until
 * {@code organization-scope} came to implement it and could not see it, because no module depends on
 * another module's {@code -impl} (ADR-050's {@code CON-PLT-013} mechanism). A port whose implementors are
 * outside the module belongs to the published surface by definition; the compile failure said so before a
 * reviewer had to.
 *
 * <p>The dependency direction is the only one available: {@code CON-PLT-011} forbids a kernel module from
 * depending on a domain module, so the kernel publishes this port and {@code organization-scope}
 * implements it. That is DOC-03 section 5.3 row 3's Published Language — "every context enforces
 * authorization through a single published evaluation contract" — pointing inward as it must.
 *
 * <p>Implemented against the organization closure table. {@code SEC-AUZ-010}'s union-not-cross-product rule
 * and {@code SEC-AUZ-028}'s historical resolution each have exactly one implementation, testable without
 * the evaluator; DOC-07 section 7.2 is the specification.
 */
public interface ScopeResolver {

    /**
     * Resolves scope for a request within an established tenant context.
     *
     * <p>Returns a {@link Resolution} rather than a decision so that the evaluator remains the single place
     * a decision is constructed ({@code SEC-AUZ-013}), and so that a resolver cannot mint an ALLOW.
     */
    Resolution resolveFor(TenantContext context, AuthorizationRequest request);

    /** The outcome of resolution, convertible to a decision by the evaluator. */
    interface Resolution {

        AuthorizationDecision toDecision(AuthorizationRequest request);
    }
}
