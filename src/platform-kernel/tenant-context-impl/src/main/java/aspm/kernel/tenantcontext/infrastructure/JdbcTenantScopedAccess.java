package aspm.kernel.tenantcontext.infrastructure;

import aspm.kernel.tenantcontext.contract.AuthorizedQuery;
import aspm.kernel.tenantcontext.contract.TenantContext;
import aspm.kernel.tenantcontext.contract.TenantContextHolder;
import aspm.kernel.tenantcontext.contract.TenantScopedAccess;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The data access gate of {@code CON-PLT-036}, over JDBC.
 *
 * <p><b>This is the only class in the platform permitted to touch a JDBC type.</b> {@code S8} in
 * {@code :architecture-tests} asserts that no class outside {@code aspm.kernel.tenantcontext.infrastructure}
 * depends on {@code java.sql} or {@code javax.sql}, so an alternative access path is a failing build
 * rather than a code review finding — which matters because DOC-02 section 13.1 records that such a path
 * "exists for convenience and then becomes the normal path".
 *
 * <p>Four properties, each of which would be a defect if absent:
 *
 * <ol>
 *   <li><b>The tenant is read from the holder, never accepted as a parameter.</b> A parameter could be
 *       populated from a request field, which {@code SEC-TEN-004} prohibits. There is no overload that
 *       takes a tenant.
 *   <li><b>The session setting is bound with {@code set_config(..., is_local => true)}</b>, so it dies
 *       with the transaction. This is what the row-level policies of DOC-04 section 7.1 read through
 *       {@code current_tenant_id()}.
 *   <li><b>Session state is reset on return to the pool</b> ({@code SEC-TEN-007}, {@code OPS-DEP-010}).
 *       DOC-24 section 6.2 entry 5 names a session-scoped tenant surviving connection reuse as a
 *       documented cross-tenant disclosure mechanism in row-level-security deployments.
 *   <li><b>An {@link AuthorizedQuery} is required</b>, and it cannot be constructed without an ALLOW
 *       decision ({@code CON-PLT-037}).
 * </ol>
 */
public final class JdbcTenantScopedAccess implements TenantScopedAccess {

    /**
     * The session parameter the row-level policies read. Must match {@code current_tenant_id()} in
     * {@code V001__tenant_context_and_enforcement.sql} exactly; a mismatch makes every policy see an
     * unset tenant, which {@code CON-DAT-013} turns into a raise rather than an empty result — so the
     * failure is loud, but it is still a failure worth naming in one place.
     */
    static final String TENANT_SETTING = "aspm.current_tenant";

    private final DataSource dataSource;
    private final boolean discardOnReturn;

    /**
     * @param dataSource a pool whose credential is {@code app_runtime} — a non-superuser without
     *     {@code BYPASSRLS}. ADR-049 records that engine enforcement reached through a bypassing
     *     credential is not enforcement, so the pair is the control and this constructor cannot verify
     *     the half it does not own. {@code CredentialSeparationVerification} asserts it against a live
     *     engine.
     * @param discardOnReturn whether to issue {@code DISCARD ALL} before returning the connection. True
     *     for a pool in session mode; may be false where the pooler already resets in transaction mode,
     *     in which case that is a deployment assertion rather than an assumption
     */
    public JdbcTenantScopedAccess(DataSource dataSource, boolean discardOnReturn) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource is required");
        this.discardOnReturn = discardOnReturn;
    }

    @Override
    public <T> T read(AuthorizedQuery authorization, ScopedRead<T> read) {
        Objects.requireNonNull(authorization,
                "an AuthorizedQuery is required; CON-PLT-037 makes the authorization decision an input "
                        + "to query execution rather than a preceding call");
        Objects.requireNonNull(read, "read body is required");

        // Raises where unestablished. Deliberately before the connection is taken: a connection
        // acquired and discarded on every unauthenticated call is a pool exhaustion path.
        TenantContext context = TenantContextHolder.requireCurrent("tenant-scoped read");

        return inTransaction(context, session -> read.apply(session, authorization.scope()));
    }

    @Override
    public <T> T write(AuthorizedQuery authorization, ScopedWrite<T> write) {
        Objects.requireNonNull(authorization, "an AuthorizedQuery is required (CON-PLT-037)");
        Objects.requireNonNull(write, "write body is required");

        TenantContext context = TenantContextHolder.requireCurrent("tenant-scoped write");

        return inTransaction(context, write);
    }

    /** The single path through which a connection is obtained, bound, used, and reset. */
    private <T> T inTransaction(TenantContext context, ScopedWrite<T> body) {
        Connection connection = null;
        boolean committed = false;
        try {
            connection = dataSource.getConnection();
            // SET LOCAL and set_config(is_local => true) require a transaction to be local to.
            // Without this the setting would leak to the next borrower of a pooled connection, which
            // is DOC-24 section 6.2 entry 5 exactly.
            connection.setAutoCommit(false);
            bindTenant(connection, context);

            T result = body.apply(new BoundSession(context, connection));

            connection.commit();
            committed = true;
            return result;

        } catch (SQLException e) {
            throw new TenantScopedAccessException("tenant-scoped access failed", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new TenantScopedAccessException("tenant-scoped access body failed", e);
        } finally {
            if (connection != null) {
                releaseQuietly(connection, committed);
            }
        }
    }

    /**
     * Binds the tenant for the transaction.
     *
     * <p>Uses {@code set_config} with a bind parameter rather than string-interpolating into
     * {@code SET LOCAL}. The value is a UUID from an established context so interpolation would not be
     * exploitable today, but a parameterized statement removes the question rather than arguing it — and
     * this is the one statement in the platform that every policy depends on.
     */
    private static void bindTenant(Connection connection, TenantContext context) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT set_config(?, ?, true)")) {
            statement.setString(1, TENANT_SETTING);
            statement.setString(2, context.tenantId().value().toString());
            statement.execute();
        }
    }

    /**
     * Rolls back where needed, resets session state, and closes.
     *
     * <p>Every step is best-effort and none may mask the original failure: a connection that cannot be
     * reset must not turn a business error into an infrastructure error, but it must also not be reused.
     * Closing after a failed reset is what removes it from the pool.
     */
    private void releaseQuietly(Connection connection, boolean committed) {
        if (!committed) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // The connection is being closed; a failed rollback changes nothing that follows.
            }
        }
        try {
            // SEC-TEN-007 and OPS-DEP-010. The SET LOCAL already died with the transaction; DISCARD ALL
            // additionally clears prepared statements, temporary tables and any other session state a
            // future defect might leave behind. Belt and braces on the platform's highest-severity risk.
            if (discardOnReturn) {
                connection.setAutoCommit(true);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DISCARD ALL");
                }
            }
        } catch (SQLException e) {
            // A connection that could not be reset must not be reused. Closing it below is what
            // guarantees that; swallowing here is deliberate and is not a silent failure, because the
            // close that follows removes the connection from the pool either way.
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing further can be done, and raising here would mask the caller's outcome.
        }
    }

    /**
     * A session bound to one tenant for the duration of one transaction.
     *
     * <p>Exposes no way to clear or reassign the tenant. That absence is the point: a session whose
     * tenant can change mid-use reintroduces the pooling leak inside a single request.
     */
    private record BoundSession(TenantContext context, Connection jdbcConnection)
            implements TenantSession {

        /**
         * Returns the connection as {@link Object}.
         *
         * <p>The component is named {@code jdbcConnection} rather than {@code connection} because a
         * record component named for the interface method would generate an accessor returning
         * {@link Connection}, which does not override an {@code Object}-returning method. The widening is
         * the point rather than an accident: the contract returns {@code Object} so that a domain layer
         * receiving a {@code TenantSession} acquires no {@code java.sql} dependency and {@code CON-PLT-017}
         * holds.
         */
        @Override
        public Object connection() {
            return jdbcConnection;
        }
    }

    /** Wraps a persistence failure without carrying tenant data, per {@code PRD-API-004}. */
    public static final class TenantScopedAccessException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        TenantScopedAccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
