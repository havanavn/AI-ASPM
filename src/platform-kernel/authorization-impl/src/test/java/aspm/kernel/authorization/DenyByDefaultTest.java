package aspm.kernel.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.kernel.authorization.application.DenialAuditSink;
import aspm.kernel.authorization.application.DenyAllScopeResolver;
import aspm.kernel.authorization.contract.ScopeResolver;
import aspm.kernel.authorization.application.ScopeResolvingAuthorizationGate;
import aspm.kernel.authorization.contract.AuthorizationDecision;
import aspm.kernel.authorization.contract.AuthorizationRequest;
import aspm.kernel.authorization.contract.DenialReason;
import aspm.kernel.authorization.contract.ObjectReference;
import aspm.kernel.authorization.contract.PermissionId;
import aspm.kernel.tenantcontext.contract.EstablishedFrom;
import aspm.kernel.tenantcontext.contract.MissingTenantContextException;
import aspm.kernel.tenantcontext.contract.TenantContext;
import aspm.kernel.tenantcontext.contract.TenantContextHolder;
import aspm.sharedkernel.PrincipalId;
import aspm.sharedkernel.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SEC-AUZ-013 through SEC-AUZ-015, and A6 of DOC-16 section 6 by fault injection. */
class DenyByDefaultTest {

    private final List<AuthorizationDecision.Deny> audited = new ArrayList<>();
    private final DenialAuditSink sink = (ctx, req, denial) -> audited.add(denial);

    private static TenantContext context() {
        return TenantContext.of(new TenantId(UUID.randomUUID()), "vn-south",
                EstablishedFrom.AUTHENTICATED_PRINCIPAL, Instant.now());
    }

    private static AuthorizationRequest request() {
        return AuthorizationRequest.forObject(
                new PrincipalId(UUID.randomUUID()),
                PermissionId.of("vul.finding.read"),
                new ObjectReference("Finding", UUID.randomUUID()));
    }

    @Test
    @DisplayName("SEC-AUZ-014: with scope resolution unavailable, the decision is DENY, not an empty allow")
    void deniesWhenScopeResolutionUnavailable() {
        var gate = new ScopeResolvingAuthorizationGate(new DenyAllScopeResolver(), sink);
        var decision = TenantContextHolder.with(context(), () -> gate.evaluate(request()));

        assertEquals(DenialReason.SCOPE_RESOLUTION_UNAVAILABLE, decision.denialReason().orElseThrow());
        assertTrue(!decision.isAllowed());
    }

    @Test
    @DisplayName("CON-PLT-037: a denial mints no key, so data access has nothing to pass to the gate")
    void denialProducesNoKey() {
        var gate = new ScopeResolvingAuthorizationGate(new DenyAllScopeResolver(), sink);
        var key = TenantContextHolder.with(context(), () -> gate.authorize(request()));

        assertTrue(key.isEmpty(),
                "a denial produced a key. CON-PLT-037 makes the decision an INPUT to query execution; "
                        + "if a denial yields a key then the input is decorative and the caller can proceed.");
    }

    @Test
    @DisplayName("SEC-AUZ-014: an evaluator that raises produces DENY with EVALUATION_ERROR, not a propagated throw")
    void deniesOnEvaluationError() {
        ScopeResolver exploding = (ctx, req) -> {
            throw new IllegalStateException("simulated resolver fault — A6 fault injection");
        };
        var gate = new ScopeResolvingAuthorizationGate(exploding, sink);
        var decision = TenantContextHolder.with(context(), () -> gate.evaluate(request()));

        assertEquals(DenialReason.EVALUATION_ERROR, decision.denialReason().orElseThrow(),
                "a raising evaluator must yield a denial. A propagated exception invites a catch block "
                        + "that proceeds, which is the fail-open SEC-AUZ-014 closes.");
    }

    @Test
    @DisplayName("SEC-AUZ-015: every denial reaches the audit sink")
    void everyDenialIsAudited() {
        var gate = new ScopeResolvingAuthorizationGate(new DenyAllScopeResolver(), sink);
        TenantContextHolder.runWith(context(), () -> gate.evaluate(request()));
        TenantContextHolder.runWith(context(), () -> gate.evaluate(request()));

        assertEquals(2, audited.size(),
                "denials are the primary signal of probing and of misconfiguration; their absence from "
                        + "the trail means neither is detectable (SEC-AUZ-015), and DOC-04 section 20.1 "
                        + "indexes them for enumeration detection under SEC-PLT-003");
    }

    @Test
    @DisplayName("SEC-TEN-005: evaluation with no tenant context raises rather than denying")
    void missingContextIsAMalfunctionNotADenial() {
        var gate = new ScopeResolvingAuthorizationGate(new DenyAllScopeResolver(), sink);
        // Deliberately NOT a denial: a denial would read as a policy outcome and be indistinguishable
        // in the trail from a legitimate refusal, hiding a wiring defect.
        assertThrows(MissingTenantContextException.class, () -> gate.evaluate(request()));
        assertTrue(audited.isEmpty(), "a malfunction must not be recorded as an authorization denial");
    }

    @Test
    @DisplayName("SEC-AUZ-020: the denial reason is enumerated for audit and carries no object detail")
    void denialReasonCarriesNoObjectDetail() {
        // The reason is an enum, so it cannot carry an object identifier, a message, or anything else
        // that could differentiate non-existence from non-authorization if it leaked to a client.
        // OBJECT_NOT_FOUND being in the same enumeration is the point: both travel one path.
        assertTrue(List.of(DenialReason.values()).contains(DenialReason.OBJECT_NOT_FOUND));
        for (DenialReason reason : DenialReason.values()) {
            assertTrue(reason.name().matches("[A-Z_]+"),
                    "a denial reason must be an opaque enumerated value, never formatted detail");
        }
    }
}
