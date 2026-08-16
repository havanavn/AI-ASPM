package aspm.kernel.authorization.application;

import aspm.kernel.authorization.contract.AuthorizationDecision;
import aspm.kernel.authorization.contract.AuthorizationGate;
import aspm.kernel.authorization.contract.AuthorizationRequest;
import aspm.kernel.authorization.contract.DenialReason;
import aspm.kernel.authorization.contract.ScopeResolver;
import aspm.kernel.tenantcontext.contract.AuthorizationGateway;
import aspm.kernel.tenantcontext.contract.AuthorizedQuery;
import aspm.kernel.tenantcontext.contract.ScopePredicate;
import aspm.kernel.tenantcontext.contract.TenantContextHolder;
import java.util.Objects;

/**
 * The single evaluator, and the only class permitted to mint an {@link AuthorizedQuery}.
 *
 * <p>Extends {@link AuthorizationGateway} to reach the protected mint. {@code :architecture-tests}
 * asserts that no class outside {@code aspm.kernel.authorization} does so, which is the final link in
 * the {@code CON-PLT-037} chain and the one enforced on bytecode rather than by the compiler — see
 * {@link AuthorizedQuery} for why that trade was taken.
 *
 * <p><b>Incomplete by design at this point in the build order.</b> Scope resolution needs the
 * organization closure table and role assignments, which are prompt 4 and DOC-04 section 11.2/20.2.
 * Until those exist this evaluator has no grants to match, so under {@code SEC-AUZ-014} it must deny
 * everything — and it does. That is deliberately not a stub that allows: an evaluator that permits
 * while its data source is missing is the fail-open {@code SEC-AUZ-014} exists to prevent, and it
 * would pass every happy-path test written against it.
 */
public final class ScopeResolvingAuthorizationGate extends AuthorizationGateway
        implements AuthorizationGate {

    private final ScopeResolver scopeResolver;
    private final DenialAuditSink denialAuditSink;

    public ScopeResolvingAuthorizationGate(ScopeResolver scopeResolver, DenialAuditSink denialAuditSink) {
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver is required");
        this.denialAuditSink = Objects.requireNonNull(denialAuditSink,
                "a denial audit sink is required; SEC-AUZ-015 makes every denial auditable and "
                        + "DOC-04 section 20.1 indexes denials for enumeration detection (SEC-PLT-003)");
    }

    @Override
    public AuthorizationDecision evaluate(AuthorizationRequest request) {
        Objects.requireNonNull(request, "request is required");

        // Raises where unestablished. Not a denial: SEC-TEN-005 requires a missing context to be a
        // visible malfunction, and returning a denial here would make it look like a policy outcome.
        var context = TenantContextHolder.requireCurrent(
                "authorization evaluation for " + request.permission().code());

        AuthorizationDecision decision;
        try {
            var resolution = scopeResolver.resolveFor(context, request);
            decision = resolution.toDecision(request);
        } catch (RuntimeException e) {
            // SEC-AUZ-014: deny on evaluation error. The exception is not rethrown, because a caller
            // catching it and proceeding is the fail-open path this requirement closes.
            decision = AuthorizationDecision.denyOn(request.permission(), DenialReason.EVALUATION_ERROR);
        }

        if (decision instanceof AuthorizationDecision.Deny denial) {
            // SEC-AUZ-015 is the gate's obligation, not the caller's: a caller that forgets produces a
            // denial invisible to enumeration detection.
            denialAuditSink.recordDenial(context, request, denial);
        }
        return decision;
    }

    /**
     * Evaluates and, on ALLOW, mints the key the data access gate requires.
     *
     * <p>This is the method application code calls. Returning the key rather than the decision means
     * a caller cannot proceed to data access after a denial: there is nothing to pass to
     * {@code TenantScopedAccess}.
     *
     * @return the key, or empty where the decision was a denial
     */
    public java.util.Optional<AuthorizedQuery> authorize(AuthorizationRequest request) {
        var decision = evaluate(request);
        if (decision instanceof AuthorizationDecision.Allow allow) {
            var predicate = allow.appliedScope().unrestricted()
                    ? new ScopePredicate(java.util.List.of(), true)
                    : new ScopePredicate(allow.appliedScope().resolvedNodes(), false);
            return java.util.Optional.of(
                    grant(allow.reference(), allow.permission().code(), predicate, allow.historical()));
        }
        return java.util.Optional.empty();
    }
}
