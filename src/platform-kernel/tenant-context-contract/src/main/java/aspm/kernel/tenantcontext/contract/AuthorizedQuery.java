package aspm.kernel.tenantcontext.contract;

import java.util.Objects;
import java.util.UUID;

/**
 * Proof that an authorization decision was reached, and the only key that opens the data access
 * gate. This type is the realization of {@code CON-PLT-037}.
 *
 * <p>{@code CON-PLT-037} requires an authorization decision to be an <b>input</b> to query
 * execution rather than a preceding call, and states why: making the decision a required input
 * "converts 'remembering to check' into 'unable to proceed without checking'". A preceding call can
 * be omitted and the omission compiles. A required parameter that cannot be constructed cannot be
 * omitted.
 *
 * <p><b>How it cannot be forged.</b> The constructor is package-private, so no application code can
 * call it. The only route is {@link AuthorizationGateway#grant}, which is {@code protected} and
 * therefore reachable only by a subclass — and {@code S9} in {@code :architecture-tests} restricts
 * subclassing to the authorization kernel module.
 *
 * <p><b>The one honestly-stated weakness.</b> {@code protected} access means a subclass could in
 * principle be declared outside the authorization module, so the final link is enforced on bytecode
 * by ArchUnit rather than by the compiler. That is one notch weaker than the compile-classpath
 * mechanism that carries {@code CON-PLT-013}. The alternative — a sealed hierarchy — cannot span
 * packages on the classpath, and the alternative of placing this type in the authorization module
 * would put the data access gate there too, contradicting DOC-02 section 6.2, which assigns the gate
 * to {@code tenant-context}. The trade is recorded rather than hidden.
 */
public final class AuthorizedQuery {

    private final UUID decisionRef;
    private final String permissionCode;
    private final ScopePredicate scope;
    private final boolean historical;

    /** Package-private: see the class comment. Only {@link AuthorizationGateway} may reach this. */
    AuthorizedQuery(UUID decisionRef, String permissionCode, ScopePredicate scope, boolean historical) {
        this.decisionRef = Objects.requireNonNull(decisionRef, "decision reference is required for audit");
        this.permissionCode = Objects.requireNonNull(permissionCode, "permission code is required");
        this.scope = Objects.requireNonNull(scope, "scope predicate is required");
        this.historical = historical;
    }

    /** Correlates this access with the audited decision that permitted it ({@code SEC-AUZ-015}). */
    public UUID decisionReference() {
        return decisionRef;
    }

    /**
     * The product-fixed permission code that was evaluated.
     *
     * <p>A permission code, never a role. {@code SEC-AUZ-002} and {@code SEC-AUZ-050} prohibit
     * branching on role identity, and the catalogue is product-fixed while roles are tenant data.
     */
    public String permissionCode() {
        return permissionCode;
    }

    /** The predicate the retrieval layer must compose into the query, not apply afterwards. */
    public ScopePredicate scope() {
        return scope;
    }

    /**
     * True where this access was authorized against a recorded historical descriptor.
     *
     * <p>{@code SEC-AUZ-028} and {@code SEC-AUZ-029}: historical evaluation is read-only and must
     * not surface objects created after the move, so the retrieval layer needs to know which
     * evaluation applied.
     */
    public boolean historical() {
        return historical;
    }
}
