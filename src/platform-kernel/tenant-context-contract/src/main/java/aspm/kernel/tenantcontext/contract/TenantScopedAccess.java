package aspm.kernel.tenantcontext.contract;

/**
 * The data access gate of {@code CON-PLT-036} and DOC-02 section 6.2.
 *
 * <p>{@code CON-PLT-036}: "Data access MUST be reachable only through a gate that requires an
 * established tenant context, and there MUST NOT be an alternative access path in application
 * code." The stated reason is that an alternative path "exists for convenience and then becomes the
 * normal path".
 *
 * <p>Two things are required to open this gate and neither is optional:
 *
 * <ol>
 *   <li>An <b>established tenant context</b>, read from {@link TenantContextHolder} by the
 *       implementation rather than accepted as a parameter. A parameter could be supplied from a
 *       request field, which {@code SEC-TEN-004} prohibits; reading the holder makes the prohibited
 *       source unavailable rather than merely forbidden.
 *   <li>An <b>{@link AuthorizedQuery}</b>, which cannot be constructed without an ALLOW decision.
 * </ol>
 *
 * <p>This is why the two controls are one gate rather than two checks. DOC-24 section 5.1 makes
 * persistence-layer enforcement layer 1 and notes it "governs the data store only" — once a row is
 * legitimately retrieved the store has no further say. Requiring the authorization key at the same
 * point means the row is never retrieved without both.
 *
 * <p>The implementation additionally binds the session tenant setting that the database policies of
 * DOC-04 section 7.1 read, so that {@code CON-DAT-012} is enforced by the engine on the same access.
 * A pooled connection is reset on return per {@code SEC-TEN-007} and {@code OPS-DEP-010}.
 */
public interface TenantScopedAccess {

    /**
     * Executes a read with the tenant session bound and the scope predicate available for
     * composition into the query.
     *
     * @throws MissingTenantContextException where no tenant context is established
     */
    <T> T read(AuthorizedQuery authorization, ScopedRead<T> read);

    /**
     * Executes a write with the tenant session bound.
     *
     * @throws MissingTenantContextException where no tenant context is established
     */
    <T> T write(AuthorizedQuery authorization, ScopedWrite<T> write);

    /** A read body, given the session and the predicate it must compose rather than post-filter. */
    @FunctionalInterface
    interface ScopedRead<T> {
        T apply(TenantSession session, ScopePredicate scope) throws Exception;
    }

    /** A write body. */
    @FunctionalInterface
    interface ScopedWrite<T> {
        T apply(TenantSession session) throws Exception;
    }

    /**
     * A database session with the tenant setting bound for its duration.
     *
     * <p>Exposes no means of clearing or reassigning the tenant setting. That absence is deliberate:
     * a session whose tenant can be changed mid-use is the connection-pool leak of DOC-24 section 6.2
     * entry 5 reintroduced inside a single request.
     */
    interface TenantSession {

        /** The context this session is bound to, for audit attribution. */
        TenantContext context();

        /**
         * The JDBC connection, with {@code SET LOCAL} already applied for the bound tenant.
         *
         * <p>Typed as {@link Object} in the contract so that {@code CON-PLT-017} holds: a domain
         * layer receiving this interface acquires no {@code java.sql} dependency. The infrastructure
         * layer casts it. Stated because a reviewer will read the {@code Object} as sloppiness.
         */
        Object connection();
    }
}
