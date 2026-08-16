package aspm.app.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.app.runtime.Principal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The tenant-bound unit of work. {@code OPS-DEP-010}, {@code SEC-TEN-007}, {@code CON-PLT-007}.
 *
 * <p>Two halves, and both are needed. The engine half runs against a real PostgreSQL because every
 * property under test is engine behaviour — a {@code SET LOCAL} value dying at commit, an uncommitted
 * write disappearing — and a double would assert what its author believed the engine does. The source
 * half is a scan, because the engine half only covers the paths a test calls: a thirty-second service
 * added next month that opens its own connection would pass every assertion below while reopening the
 * defect, and the scan is what notices.
 */
class TenantConnectionsTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static Principal principal(UUID tenant) {
        return new Principal(tenant, UUID.randomUUID(), Set.of(), Set.of(), true, false, false);
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Against a real engine")
    class Engine {

        private static io.zonky.test.db.postgres.embedded.EmbeddedPostgres embedded;
        private static DataSource dataSource;

        @BeforeAll
        static void start() throws IOException, SQLException {
            embedded = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start();
            dataSource = embedded.getPostgresDatabase();
            try (Connection c = dataSource.getConnection();
                    java.sql.Statement s = c.createStatement()) {
                // The fixture is deliberately the SHAPE of the platform's own function rather than the
                // platform's schema: what is under test is the connection wrapper, and pulling in the
                // tenant table would test the migration instead.
                s.execute("""
                        CREATE FUNCTION establish_tenant_session(tenant_id text, transaction_local boolean)
                            RETURNS void LANGUAGE plpgsql AS $$
                        BEGIN
                            PERFORM set_config('aspm.current_tenant', tenant_id, transaction_local);
                            PERFORM set_config('timezone', 'UTC', transaction_local);
                        END;
                        $$;
                        """);
                s.execute("CREATE TABLE probe (id serial primary key, note text)");
            }
        }

        @AfterAll
        static void stop() throws IOException {
            if (embedded != null) {
                embedded.close();
            }
        }

        private static String tenantOf(Connection connection) throws SQLException {
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT current_setting('aspm.current_tenant', true)");
                    ResultSet r = s.executeQuery()) {
                r.next();
                String value = r.getString(1);
                return value == null ? "" : value;
            }
        }

        private static long probeRows(String note) throws SQLException {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement s = c.prepareStatement(
                            "SELECT count(*) FROM probe WHERE note = ?")) {
                s.setString(1, note);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    return r.getLong(1);
                }
            }
        }

        @Test
        @DisplayName("SEC-TEN-007: the tenant is established, and it is transaction-local")
        void established() throws SQLException {
            try (Connection c = TenantConnections.open(dataSource, principal(TENANT))) {
                assertEquals(TENANT.toString(), tenantOf(c),
                        "the tenant must be in force on the connection this door returns");
                assertFalse(c.getAutoCommit(),
                        "a transaction-local setting needs a transaction to be local to; without one "
                                + "the implicit commit of the establishing statement discards it");
            }
        }

        @Test
        @DisplayName("The mechanism this defends: a LOCAL setting does not survive its transaction")
        void localSettingDiesAtCommit() throws SQLException {
            // Asserted directly, on a RAW connection, because it is the reason the wrapper has to
            // re-establish and the reason session scope looked necessary to five services.
            try (Connection raw = dataSource.getConnection()) {
                raw.setAutoCommit(false);
                try (PreparedStatement s = raw.prepareStatement(
                        "SELECT establish_tenant_session(?, true)")) {
                    s.setString(1, TENANT.toString());
                    s.execute();
                }
                assertEquals(TENANT.toString(), tenantOf(raw));
                raw.commit();
                assertEquals("", tenantOf(raw),
                        "if this ever stops being true the re-establishment below is dead code, and a "
                                + "reviewer should be told by a failing test rather than by reading it");
            }
        }

        @Test
        @DisplayName("OPS-DEP-010: the tenant survives a commit, because the next transaction re-establishes it")
        void survivesCommit() throws SQLException {
            try (Connection c = TenantConnections.open(dataSource, principal(TENANT))) {
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO probe (note) VALUES ('survives-commit')")) {
                    s.executeUpdate();
                }
                c.commit();
                assertEquals(TENANT.toString(), tenantOf(c),
                        "several services commit and keep working on the same connection; without the "
                                + "re-establishment their next statement runs with no tenant at all");
                c.rollback();
                assertEquals(TENANT.toString(), tenantOf(c),
                        "a rollback ends a transaction too, and the failure path is exactly where a "
                                + "missing tenant would be reported as an unrelated error");
            }
            assertEquals(1L, probeRows("survives-commit"));
        }

        @Test
        @DisplayName("A write that is never committed is refused loudly, not lost quietly")
        void uncommittedWriteThrows() throws SQLException {
            SQLException thrown = assertThrows(SQLException.class, () -> {
                try (Connection c = TenantConnections.open(dataSource, principal(TENANT))) {
                    try (PreparedStatement s = c.prepareStatement(
                            "INSERT INTO probe (note) VALUES ('never-committed')")) {
                        s.executeUpdate();
                    }
                }
            });
            assertTrue(thrown.getMessage().contains("without commit()"),
                    "the message has to name what the caller did, because the caller is a path that "
                            + "used to rely on autocommit and the fix is one line: " + thrown.getMessage());
            assertEquals(0L, probeRows("never-committed"),
                    "and the write is rolled back rather than half-applied");
        }

        @Test
        @DisplayName("An explicit rollback is how a path says it meant to discard its work")
        void deliberateAbandonIsSilent() throws SQLException {
            try (Connection c = TenantConnections.open(dataSource, principal(TENANT))) {
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO probe (note) VALUES ('abandoned')")) {
                    s.executeUpdate();
                }
                c.rollback();
            }
            assertEquals(0L, probeRows("abandoned"));
        }

        @Test
        @DisplayName("Returning to autocommit is refused: it would discard the tenant mid-request")
        void autocommitRefused() throws SQLException {
            try (Connection c = TenantConnections.open(dataSource, principal(TENANT))) {
                SQLException thrown = assertThrows(SQLException.class, () -> c.setAutoCommit(true));
                assertTrue(thrown.getMessage().contains("transaction-local"), thrown.getMessage());
                // Turning it off again is what several converted paths still do, and it has to keep
                // meaning "I am in a transaction" rather than throwing.
                c.setAutoCommit(false);
            }
        }

        @Test
        @DisplayName("Two units of work on one pool do not see each other's tenant")
        void noResidueBetweenUnits() throws SQLException {
            try (Connection first = TenantConnections.open(dataSource, principal(TENANT))) {
                assertEquals(TENANT.toString(), tenantOf(first));
            }
            try (Connection second = TenantConnections.openForTenant(dataSource, OTHER)) {
                assertEquals(OTHER.toString(), tenantOf(second),
                        "this is the disclosure OPS-DEP-010 names: the second borrower of a connection "
                                + "must never inherit the first one's tenant");
            }
        }

        @Test
        @DisplayName("An unprepared statement is refused rather than admitted unseen")
        void createStatementRefused() throws SQLException {
            try (Connection c = TenantConnections.open(dataSource, principal(TENANT))) {
                assertThrows(UnsupportedOperationException.class, c::createStatement);
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("The source, because the engine half only covers the paths a test calls")
    class Source {

        private static final Path MAIN = Path.of("src/main/java/aspm/app");

        private static List<Path> javaFiles() throws IOException {
            try (var walk = Files.walk(MAIN)) {
                return walk.filter(p -> p.toString().endsWith(".java")).toList();
            }
        }

        @Test
        @DisplayName("SEC-TEN-007: no session-scoped tenant establishment survives anywhere")
        void noSessionScopedEstablishment() throws IOException {
            List<String> offenders = new ArrayList<>();
            for (Path path : javaFiles()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                // The literal a caller would write. TenantConnections names it in prose, in the
                // explanation of what it replaced, so the check is for the SQL as it appears in a
                // statement rather than for the words.
                if (source.contains("establish_tenant_session(?, false)")
                        && !path.getFileName().toString().equals("TenantConnections.java")) {
                    offenders.add(path.toString());
                }
            }
            assertTrue(offenders.isEmpty(),
                    "a session-scoped tenant context outlives every transaction on its connection and, "
                            + "under a pool, the request that established it. Thirty-two files did this "
                            + "and were converted; these have reintroduced it: " + offenders);
        }

        @Test
        @DisplayName("CON-PLT-007: the application tier takes connections from one door")
        void oneDoor() throws IOException {
            // The exceptions, each for a reason a reviewer can check rather than a name on a list:
            //   TenantConnections — it IS the door
            //   AspmApplication   — the readiness probe, which asks whether the store answers at all
            //                       and must not need a tenant to do it
            Set<String> permitted = Set.of("TenantConnections.java", "AspmApplication.java");
            Pattern raw = Pattern.compile("dataSource\\.getConnection\\(\\)");
            List<String> offenders = new ArrayList<>();
            for (Path path : javaFiles()) {
                String name = path.getFileName().toString();
                if (permitted.contains(name)) {
                    continue;
                }
                Matcher matcher = raw.matcher(Files.readString(path, StandardCharsets.UTF_8));
                if (matcher.find()) {
                    offenders.add(path.toString());
                }
            }
            assertTrue(offenders.isEmpty(),
                    "a connection taken straight from the data source carries no tenant, so either it "
                            + "establishes one itself — the duplication this class removed — or it runs "
                            + "with none and the row-level policy raises. Take it from "
                            + "TenantConnections: " + offenders);
        }

        @Test
        @DisplayName("The scan is not vacuous: it sweeps the whole tier")
        void sweepIsWide() throws IOException {
            // A scan that matches nothing passes for the wrong reason. This is the same lesson as
            // InterfaceTest.staticLinksResolve, which swept zero hrefs after a refactor and stayed
            // green while five dead links went in.
            assertTrue(javaFiles().size() > 60,
                    "the application tier is larger than this; a scan over a handful of files is a scan "
                            + "that has lost its root: " + javaFiles().size());
        }
    }
}
