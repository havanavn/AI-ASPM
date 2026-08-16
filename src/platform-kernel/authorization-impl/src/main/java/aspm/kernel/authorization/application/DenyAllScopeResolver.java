package aspm.kernel.authorization.application;

import aspm.kernel.authorization.contract.AuthorizationDecision;
import aspm.kernel.authorization.contract.AuthorizationRequest;
import aspm.kernel.authorization.contract.DenialReason;
import aspm.kernel.authorization.contract.ScopeResolver;
import aspm.kernel.tenantcontext.contract.TenantContext;

/**
 * The resolver in force until the organization closure table exists (prompt 4).
 *
 * <p>Denies every request with {@link DenialReason#SCOPE_RESOLUTION_UNAVAILABLE}, which is the
 * literal condition: scope resolution is unavailable because its data source has not been built.
 * {@code SEC-AUZ-014} names this case explicitly among the four that must deny.
 *
 * <p><b>Why this is not a permissive placeholder.</b> A resolver that allowed would let every
 * authorization test written before prompt 4 pass, and the tests would then be rewritten to match a
 * permissive baseline. Denying means any code path that needs authorization is visibly not working
 * yet, which is the honest state.
 */
public final class DenyAllScopeResolver implements ScopeResolver {

    @Override
    public Resolution resolveFor(TenantContext context, AuthorizationRequest request) {
        return req -> AuthorizationDecision.denyOn(
                req.permission(), DenialReason.SCOPE_RESOLUTION_UNAVAILABLE);
    }
}
