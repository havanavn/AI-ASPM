package aspm.kernel.authorization.contract;

/**
 * The single enforcement contract of {@code SEC-AUZ-013}.
 *
 * <p>"Authorization MUST be evaluated through a single contract, and application code MUST NOT
 * implement its own check. Data access MUST be unable to proceed without an evaluation result."
 *
 * <p>The second sentence is not satisfied by this interface alone — an interface can be called or not
 * called. It is satisfied by the return type: the implementation in the authorization kernel returns
 * an {@code AuthorizedQuery} that only it can mint, and the data access gate requires one. A caller
 * that skips this interface has no key.
 *
 * <p>Every denial emits an audit event with principal, permission, object reference and reason
 * ({@code SEC-AUZ-015}). That is the implementation's obligation, not the caller's, because a caller
 * that forgets is a denial that never reaches the enumeration-detection index of DOC-04 section 20.1.
 */
public interface AuthorizationGate {

    /**
     * Evaluates a request against the established tenant context and the principal's assignments.
     *
     * <p>Never raises for an authorization outcome. An evaluation failure is returned as a
     * {@link AuthorizationDecision.Deny} with {@link DenialReason#EVALUATION_ERROR}, because
     * {@code SEC-AUZ-014} requires denial on error and a thrown exception invites a catch block that
     * proceeds.
     *
     * <p>It does raise where no tenant context is established — {@code MissingTenantContextException}
     * — because that is not an authorization outcome but a malfunction, and {@code SEC-TEN-005}
     * requires it to be visible rather than returned as an empty or negative result.
     */
    AuthorizationDecision evaluate(AuthorizationRequest request);
}
