package aspm.module.organizationscope.application;

import aspm.kernel.authorization.contract.AuthorizationDecision;
import aspm.kernel.authorization.contract.AuthorizationRequest;
import aspm.kernel.authorization.contract.DenialReason;
import aspm.kernel.authorization.contract.ScopeGrant;
import aspm.kernel.authorization.contract.ScopeResolver;
import aspm.kernel.tenantcontext.contract.TenantContext;
import aspm.module.organizationscope.contract.ScopeResolutionQuery;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapts organizational scope resolution to the authorization kernel's {@link ScopeResolver}.
 *
 * <p>This is the class that removes {@code DenyAllScopeResolver} from the assembly. Prompt 3's evaluator
 * denied every request with {@code SCOPE_RESOLUTION_UNAVAILABLE} because the closure did not exist; with
 * the closure in place, the resolver can answer.
 *
 * <p><b>Direction of dependency.</b> This adapter lives in {@code organization-scope}, not in
 * {@code authorization}. {@code CON-PLT-011} forbids a kernel module from depending on a domain module, so
 * the kernel publishes the {@code ScopeResolver} port and the domain module implements it — the
 * Published Language of DOC-03 section 5.3 row 3 pointing the only way it can.
 *
 * <p><b>Still denies on absence.</b> An unavailable resolution and an empty permitted set produce different
 * denial reasons, because {@code SEC-AUZ-014} lists them as separate conditions and conflating them would
 * make a closure outage indistinguishable from a correctly restrictive configuration.
 */
public final class OrgScopeResolverAdapter implements ScopeResolver {

    private final ScopeResolutionQuery scopeResolution;

    public OrgScopeResolverAdapter(ScopeResolutionQuery scopeResolution) {
        this.scopeResolution = Objects.requireNonNull(scopeResolution, "scope resolution is required");
    }

    @Override
    public Resolution resolveFor(TenantContext context, AuthorizationRequest request) {
        Objects.requireNonNull(context, "context is required");
        Objects.requireNonNull(request, "request is required");

        var resolved = scopeResolution.resolveCurrent(request.principalId(), request.permission().code());

        if (resolved.isUnavailable()) {
            return req -> AuthorizationDecision.denyOn(
                    req.permission(), DenialReason.SCOPE_RESOLUTION_UNAVAILABLE);
        }
        if (resolved.permittedNodes().isEmpty()) {
            // A different reason from the above: this principal genuinely reaches nothing for this
            // permission, which is a configuration outcome rather than a malfunction.
            return req -> AuthorizationDecision.denyOn(req.permission(), DenialReason.NO_MATCHING_GRANT);
        }

        return req -> new AuthorizationDecision.Allow(
                UUID.randomUUID(),
                req.permission(),
                new ScopeGrant(resolved.permittedNodes(), false, resolved.hierarchyVersion()),
                java.util.List.of(),
                false);
    }
}
