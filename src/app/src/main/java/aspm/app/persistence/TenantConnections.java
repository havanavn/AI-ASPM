package aspm.app.persistence;

import aspm.app.runtime.Principal;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * The one door to a tenant-bound connection.
 *
 * <p>{@code OPS-DEP-010} and {@code SEC-TEN-007} require that tenant context cannot survive a
 * connection's return to a pool, and ADR (DOC-19, pooled connection safety) settles how: a
 * transaction-scoped {@code SET LOCAL} rather than a session-scoped {@code SET}. Until this class
 * existed the platform did the opposite at thirty-two of forty call sites — each service opened its
 * own connection and established the tenant with {@code establish_tenant_session(?, false)}, whose
 * value outlives every transaction on that connection and, under a pool, the request itself.
 *
 * <h2>Why it was not a live disclosure, and why it was still worth fixing</h2>
 *
 * <p>This deployment uses {@code PGSimpleDataSource}, which is not a pool: every
 * {@code getConnection()} opens a new physical connection and {@code close()} closes it, so no second
 * borrower exists to inherit the residue. The defect was therefore latent, and stating otherwise
 * would overstate it. What makes it worth closing now is that the mechanism is invisible at the point
 * where it becomes live: the day a pool is introduced — the Large profile of DOC-01 §12.1 requires an
 * external pooler, per DOC-19 — thirty-two files start leaking at once, and nothing in any of them
 * changes or fails to draw attention to it. A control that depends on a deployment property nobody
 * re-checks is not a control.
 *
 * <h2>The unit of work</h2>
 *
 * <p>A transaction-local setting exists only inside a transaction, so establishing one requires a
 * transaction to establish it in. {@link #open} therefore turns autocommit off before establishing,
 * and every caller of it is a unit of work: it commits, or nothing it wrote happens.
 *
 * <p>That is a behavioural change for the paths that previously relied on autocommit, and the
 * dangerous form of getting it wrong is silent — a write that was committed by the driver and now is
 * not, with no error anywhere. So it is made loud instead. The returned connection is a proxy that
 * notices statements that write, and refuses to close with uncommitted work, throwing rather than
 * discarding it (product principle 9). Every path this batch converted was found or confirmed by that
 * guard, by a test, or by both.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>It does not take the tenant as a parameter on the principal-bearing door. {@code SEC-TEN-004}
 * forbids deriving tenant from any request field, and a {@code UUID} parameter is exactly the shape
 * that lets a caller supply one. {@link #openForTenant} exists for the paths that run before a
 * principal exists — sign-in, session resolution, credential resolution, the startup bootstrap — and
 * is named so that a reviewer can enumerate them instead of auditing every call.
 */
public final class TenantConnections {

    /**
     * Statements that change data, for the uncommitted-work guard.
     *
     * <p>Best effort by construction, and that is acceptable: a write this pattern misses behaves
     * exactly as the platform behaved before this class existed. It is a net that catches the
     * conversion mistakes, not the control that makes the conversion correct — that one is the
     * transaction itself, plus a test per converted write path.
     */
    private static final Pattern WRITES = Pattern.compile(
            "\\b(INSERT\\s+INTO|UPDATE\\s+[a-z_\"]|DELETE\\s+FROM|MERGE\\s+INTO|TRUNCATE\\s|COPY\\s)",
            Pattern.CASE_INSENSITIVE);

    /** {@code SELECT … FOR UPDATE} takes a lock and writes nothing; without this it reads as a write. */
    private static final Pattern FOR_UPDATE = Pattern.compile("\\bFOR\\s+UPDATE\\b",
            Pattern.CASE_INSENSITIVE);

    private TenantConnections() {
    }

    /**
     * A connection inside a transaction, with the caller's tenant and business calendar established
     * transaction-locally.
     *
     * <p>The caller commits. Closing with uncommitted writes throws.
     */
    public static Connection open(DataSource dataSource, Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return establish(dataSource, principal.tenantId());
    }

    /**
     * The same, for the paths that run <em>before</em> a principal exists.
     *
     * <p>Sign-in, multi-factor challenge, session resolution, service-credential resolution, the
     * startup credential bootstrap: each of these is the code that decides who the caller is, so
     * requiring a {@link Principal} here would be circular. The tenant comes from the deployment's
     * configured tenant or from the session row being resolved, never from a request field.
     *
     * <p>Separate from {@link #open} and named so, because a {@code UUID} parameter is the shape
     * {@code SEC-TEN-004} exists to forbid on the authenticated paths. Keeping the two doors distinct
     * is what lets a reviewer enumerate the exceptions instead of auditing every call.
     */
    public static Connection openForTenant(DataSource dataSource, UUID tenantId) throws SQLException {
        Objects.requireNonNull(tenantId, "a tenant is required");
        return establish(dataSource, tenantId);
    }

    private static Connection establish(DataSource dataSource, UUID tenantId) throws SQLException {
        Objects.requireNonNull(dataSource, "a data source is required");
        Connection connection = dataSource.getConnection();
        try {
            // Before the tenant, not after: a transaction-local setting established outside a
            // transaction is discarded by the implicit commit of the statement that set it, and the
            // next statement would run with no tenant at all.
            connection.setAutoCommit(false);
            establishOn(connection, tenantId);
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
        return guard(connection, tenantId);
    }

    private static void establishOn(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement tenant = connection.prepareStatement(
                "SELECT establish_tenant_session(?, true)")) {
            tenant.setString(1, tenantId.toString());
            tenant.execute();
        }
    }

    /** Runs a body in one unit of work, committing on return and rolling back on any throw. */
    public static <T> T inTransaction(DataSource dataSource, Principal principal, Work<T> body)
            throws SQLException {
        try (Connection connection = open(dataSource, principal)) {
            try {
                T result = body.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** The body of a unit of work. */
    @FunctionalInterface
    public interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }

    private static Connection guard(Connection raw, UUID tenantId) {
        return (Connection) Proxy.newProxyInstance(
                TenantConnections.class.getClassLoader(), new Class<?>[] {Connection.class},
                new UnitOfWork(raw, tenantId));
    }

    /**
     * Refuses to lose a write quietly.
     *
     * <p>Four interceptions, and each exists because of a way the conversion could go wrong:
     * closing with uncommitted writes (the write vanishes), returning to autocommit (the tenant
     * setting dies with the transaction and the next statement fails for an unrelated-looking
     * reason), an unprepared statement (nothing to inspect), and ending a transaction.
     *
     * <p>That last one is the interesting case and is why several services could previously argue
     * for session scope: a few of them commit and then keep working on the same connection — the
     * identity service says so in as many words — and a transaction-local setting dies with the
     * transaction that carried it. So the setting is re-established here, as the first statement of
     * the next transaction, which is where it has to be anyway. The caller does not have to know
     * whether its path runs one transaction or four, and no caller can get it wrong by not knowing.
     */
    private static final class UnitOfWork implements InvocationHandler {

        private final Connection raw;
        private final UUID tenantId;
        private boolean wrote;

        private UnitOfWork(Connection raw, UUID tenantId) {
            this.raw = raw;
            this.tenantId = tenantId;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            switch (method.getName()) {
                case "prepareStatement", "prepareCall" -> {
                    if (arguments != null && arguments.length > 0 && arguments[0] instanceof String sql
                            && writes(sql)) {
                        wrote = true;
                    }
                }
                case "createStatement" ->
                    // No SQL to inspect, so the guard cannot see what this executes. Refused rather
                    // than admitted blind: every statement in this tier is prepared, and admitting an
                    // unprepared one would also be admitting the injection surface the prepared form
                    // is why we do not have.
                    throw new UnsupportedOperationException(
                            "createStatement() has no SQL for the unit-of-work guard to inspect, and "
                                    + "an unprepared statement is a parameter concatenated into SQL "
                                    + "sooner or later. Use prepareStatement.");
                case "commit", "rollback" -> {
                    // rollback(Savepoint) is not the end of a transaction: it unwinds part of one and
                    // the setting established at its start is still in force, so it falls through to
                    // the delegate and partial rollback keeps meaning what it means.
                    if (arguments == null || arguments.length == 0) {
                        endTransaction("commit".equals(method.getName()));
                        return null;
                    }
                }
                case "setAutoCommit" -> {
                    if (arguments != null && arguments.length == 1 && Boolean.TRUE.equals(arguments[0])) {
                        throw new SQLException(
                                "this connection is a tenant-bound unit of work: its tenant and business "
                                        + "calendar are transaction-local (OPS-DEP-010, SEC-TEN-007), so "
                                        + "returning to autocommit would commit the work in flight and "
                                        + "discard the tenant, and the next statement would fail with a "
                                        + "missing-tenant error that names nothing about this line.");
                    }
                    // Already false. A caller that opened its own transaction the old way still
                    // compiles and still means the same thing.
                    return null;
                }
                case "close" -> {
                    if (raw.isClosed()) {
                        return null;
                    }
                    boolean lost = wrote;
                    try {
                        raw.rollback();
                    } finally {
                        raw.close();
                    }
                    if (lost) {
                        throw new SQLException(
                                "this unit of work wrote and was closed without commit(); the write has "
                                        + "been rolled back rather than left half-applied. A path "
                                        + "converted to a transaction has to say when its work is "
                                        + "complete — silently committing on close would commit partial "
                                        + "work on the exception paths too.");
                    }
                    return null;
                }
                default -> {
                    // fall through to the delegate
                }
            }
            try {
                return method.invoke(raw, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        /** Ends the transaction and opens the next one with the tenant already in force. */
        private void endTransaction(boolean commit) throws SQLException {
            wrote = false;
            if (commit) {
                raw.commit();
                // Propagated: the work committed, the caller is going to keep using this connection,
                // and continuing without a tenant would either raise on the next statement or — worse,
                // if a policy ever admits the unset case — read nothing and report it as empty.
                establishOn(raw, tenantId);
                return;
            }
            raw.rollback();
            try {
                establishOn(raw, tenantId);
            } catch (SQLException e) {
                // Swallowed, and only here. A rollback is already the failure path, and a second
                // exception thrown from it replaces the diagnosis the caller is in the middle of
                // reporting with one about session setup.
                System.getLogger("aspm.persistence").log(System.Logger.Level.DEBUG,
                        "the tenant session could not be re-established after rollback");
            }
        }

        private static boolean writes(String sql) {
            return WRITES.matcher(FOR_UPDATE.matcher(sql).replaceAll(" ")).find();
        }
    }
}
