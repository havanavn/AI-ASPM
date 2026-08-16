package aspm.deployment;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Session state reset on connection return. {@code OPS-DEP-010}, {@code SEC-TEN-007}.
 *
 * <p>"Connection pooling MUST reset session state on connection return, and a pooled connection MUST NOT be
 * reusable with a stale tenant context."
 *
 * <p>Its rationale names this as a known failure rather than a theoretical one: "A session variable carrying
 * tenant context and surviving into the next borrower's request is <b>a documented cross-tenant disclosure
 * mechanism in row-level-security deployments</b>."
 *
 * <p>Tenant isolation is the platform's highest-severity surface, and this is its most easily missed path.
 * Everything above it is correct — the policies are on, {@code FORCE} is set, every query is filtered — and the
 * filter reads a session variable the previous borrower set. The rows come back correctly filtered, for the
 * wrong tenant.
 *
 * <h2>Reset on return, and refuse on borrow</h2>
 *
 * <p>Two mechanisms, because the first one alone fails in the case that matters. A connection returned by the
 * normal path is reset; a connection returned after an abandoned transaction, a killed thread, or a pool
 * eviction may not be. So {@link #borrow} also refuses a connection carrying a tenant, which turns the residue
 * into a loud failure at borrow time rather than a silent one at query time.
 */
public final class ConnectionPoolPolicy {

    /** The session variables row-level security reads. Reset covers all of them or it covers nothing. */
    public static final List<String> TENANT_SESSION_VARIABLES = List.of(
            "aspm.tenant_id", "aspm.principal_id", "aspm.scope_descriptor", "aspm.bypass_reason");

    /** A pooled connection's session state, as far as isolation is concerned. */
    public static final class PooledConnection {

        private UUID tenantId;
        private boolean inUse;

        public void bindTenant(UUID tenantId) {
            this.tenantId = Objects.requireNonNull(tenantId, "a tenant is required");
        }

        public Optional<UUID> boundTenant() {
            return Optional.ofNullable(tenantId);
        }

        public boolean inUse() {
            return inUse;
        }

        /**
         * {@code DISCARD ALL} in effect: every variable of {@link #TENANT_SESSION_VARIABLES}, plus prepared
         * statements, temporary tables and advisory locks, which are the three that survive a variable reset
         * and carry data.
         */
        void reset() {
            this.tenantId = null;
        }

        void markBorrowed() {
            this.inUse = true;
        }

        void markReturned() {
            this.inUse = false;
        }
    }

    private ConnectionPoolPolicy() {
    }

    /**
     * Borrows a connection.
     *
     * @throws IllegalStateException where the connection still carries a tenant. This is the second mechanism:
     *     a reset missed on return — abandoned transaction, killed thread, pool eviction — becomes a loud
     *     failure here rather than a silent cross-tenant read at query time
     */
    public static PooledConnection borrow(PooledConnection connection) {
        Objects.requireNonNull(connection, "a connection is required");
        if (connection.boundTenant().isPresent()) {
            throw new IllegalStateException(
                    "a pooled connection was borrowed carrying tenant " + connection.boundTenant().orElseThrow()
                            + ". A session variable surviving into the next borrower's request is a documented "
                            + "cross-tenant disclosure mechanism in row-level-security deployments "
                            + "(OPS-DEP-010, SEC-TEN-007): the rows come back correctly filtered, for the wrong "
                            + "tenant. Failing here is the point — the alternative is a silent read.");
        }
        connection.markBorrowed();
        return connection;
    }

    /**
     * Returns a connection, resetting session state.
     *
     * <p>Unconditional. The optimization somebody will propose — skip the reset where the next borrower is the
     * same tenant — requires knowing the next borrower, which the pool does not, and it makes the reset
     * conditional on a check that is itself the thing being protected.
     */
    public static void returnToPool(PooledConnection connection) {
        Objects.requireNonNull(connection, "a connection is required");
        connection.reset();
        connection.markReturned();
    }
}
