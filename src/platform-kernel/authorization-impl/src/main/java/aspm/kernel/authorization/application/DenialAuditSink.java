package aspm.kernel.authorization.application;

import aspm.kernel.authorization.contract.AuthorizationDecision;
import aspm.kernel.authorization.contract.AuthorizationRequest;
import aspm.kernel.tenantcontext.contract.TenantContext;

/**
 * Receives every denial for the audit trail, per {@code SEC-AUZ-015}.
 *
 * <p>An interface rather than a direct dependency on the audit kernel, so that the evaluator can be
 * tested without an audit chain and so that {@code authorization} does not depend on {@code audit}.
 * DOC-02 section 7.3 makes audit an event subscriber that observes everything without being depended
 * upon; this is the same shape applied inside the kernel.
 */
public interface DenialAuditSink {

    void recordDenial(
            TenantContext context, AuthorizationRequest request, AuthorizationDecision.Deny denial);
}
