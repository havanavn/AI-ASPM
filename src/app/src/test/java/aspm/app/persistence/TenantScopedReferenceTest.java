package aspm.app.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A reference between two tenant-scoped tables carries the tenant. ADR-002, {@code SEC-TEN-*}.
 *
 * <p><b>The defect this was written for.</b> On 2026-08-16 a normal API call created an
 * organization node in tenant A whose node <em>type</em> belonged to tenant B, and it was accepted.
 * Row-level security cannot prevent it: PostgreSQL enforces referential integrity with internal
 * triggers that run with row security disabled, because a policy that hid a parent row would
 * otherwise turn a valid insert into a spurious violation. So the one check in the engine that asks
 * "does this parent row exist" is the one that does not ask "whose is it".
 *
 * <p>The consequence was not disclosure — reads stayed filtered — but the row became invisible on
 * its own tenant's page, a caller could test whether a UUID existed in another tenant, and
 * {@code ON DELETE RESTRICT} let one tenant pin another tenant's catalogue row in place.
 *
 * <p>V065 replaced every such key with {@code (tenant_id, x_id) REFERENCES parent (tenant_id, id)}.
 * The engine half is asserted here against a real PostgreSQL, on a fixture that reproduces the
 * shape rather than the schema — what is under test is the constraint, and pulling in the real
 * schema would test the migration's ability to run rather than the property it establishes.
 */
class TenantScopedReferenceTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Nested
    @DisplayName("Against a real engine")
    class Engine {

        private static io.zonky.test.db.postgres.embedded.EmbeddedPostgres embedded;
        private static DataSource dataSource;
        private static UUID typeOfB;

        @BeforeAll
        static void start() throws IOException, SQLException {
            embedded = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start();
            dataSource = embedded.getPostgresDatabase();
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                s.execute("""
                        CREATE TABLE node_type (
                            id uuid PRIMARY KEY,
                            tenant_id uuid NOT NULL,
                            code text NOT NULL,
                            CONSTRAINT uq_node_type__tenant_id UNIQUE (tenant_id, id));
                        CREATE TABLE node (
                            id uuid PRIMARY KEY,
                            tenant_id uuid NOT NULL,
                            type_id uuid NOT NULL,
                            name text NOT NULL,
                            CONSTRAINT fk_node__type_id__tenant
                                FOREIGN KEY (tenant_id, type_id)
                                REFERENCES node_type (tenant_id, id) ON DELETE RESTRICT);
                        """);
                typeOfB = UUID.randomUUID();
                s.execute("INSERT INTO node_type (id, tenant_id, code) VALUES ('" + typeOfB + "', '"
                        + TENANT_B + "', 'TENANT_B_ONLY')");
            }
            try (Connection c = dataSource.getConnection();
                    PreparedStatement s = c.prepareStatement(
                            "INSERT INTO node_type (id, tenant_id, code) VALUES (?, ?, 'DIVISION')")) {
                s.setObject(1, typeOfA());
                s.setObject(2, TENANT_A);
                s.executeUpdate();
            }
        }

        private static UUID typeOfA() {
            return UUID.fromString("aaaaaaaa-0000-4000-8000-00000000000a");
        }

        @AfterAll
        static void stop() throws IOException {
            if (embedded != null) {
                embedded.close();
            }
        }

        private static SQLException insertNode(UUID tenant, UUID type, String name) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement s = c.prepareStatement(
                            "INSERT INTO node (id, tenant_id, type_id, name) VALUES (?, ?, ?, ?)")) {
                s.setObject(1, UUID.randomUUID());
                s.setObject(2, tenant);
                s.setObject(3, type);
                s.setString(4, name);
                s.executeUpdate();
                return null;
            } catch (SQLException e) {
                return e;
            }
        }

        @Test
        @DisplayName("ADR-002: a node cannot be created against another tenant's type")
        void crossTenantReferenceRefused() {
            SQLException refused = insertNode(TENANT_A, typeOfB, "should be refused");
            assertTrue(refused != null, "this insert is the exact shape that was accepted before "
                    + "V065; if it succeeds again the composite key has been dropped or replaced");
            assertEquals("23503", refused.getSQLState(),
                    "and it must fail as a foreign key violation, which is the SQLSTATE the "
                            + "dispatcher maps to a 400 rather than a 500");
        }

        @Test
        @DisplayName("The tenant's own type still works, which is the half a fix can break")
        void sameTenantReferenceAccepted() {
            assertEquals(null, insertNode(TENANT_A, typeOfA(), "should be accepted"));
        }

        @Test
        @DisplayName("The existence oracle closes with it: absent and foreign look identical")
        void unknownAndForeignAreIndistinguishable() {
            SQLException foreign = insertNode(TENANT_A, typeOfB, "foreign");
            SQLException absent = insertNode(TENANT_A, UUID.randomUUID(), "absent");
            // Same SQLSTATE and the same constraint name, so nothing downstream can tell a caller
            // which of the two happened — which is what stops the error being a way to ask whether
            // a UUID belongs to somebody else.
            assertEquals(foreign.getSQLState(), absent.getSQLState());
            assertTrue(foreign.getMessage().contains("fk_node__type_id__tenant")
                    && absent.getMessage().contains("fk_node__type_id__tenant"),
                    "both violations must name the same constraint: " + foreign.getMessage()
                            + " / " + absent.getMessage());
        }
    }

    @Nested
    @DisplayName("The migration says what it does, and keeps saying it")
    class Migration {

        private static final Path V065 = Path.of(
                "../platform-kernel/tenant-context-impl/src/main/resources/db/migration/"
                        + "V065__tenant_scoped_foreign_keys.sql");

        @Test
        @DisplayName("V065 refuses to leave a single-column reference behind")
        void migrationAssertsItsOwnProperty() throws IOException {
            String sql = Files.readString(V065, StandardCharsets.UTF_8);
            assertTrue(sql.contains("RAISE EXCEPTION"),
                    "the migration ends with a check that no single-column foreign key between two "
                            + "tenant-scoped tables remains. Without it the conversion is a one-off "
                            + "and the next table added quietly reopens the defect");
            assertTrue(sql.contains("conparentid = 0"),
                    "constraints copied onto partitions cannot be altered on the partition; the "
                            + "first run failed on finding_p0 for exactly that reason");
            assertFalse(sql.contains("VALIDATE CONSTRAINT %I', reference.child, new_name);\n"
                            + "        EXECUTE format('ALTER TABLE %I DROP"),
                    "validation must stay conditional on the role being able to see across tenants; "
                            + "the migration role cannot, and an unconditional VALIDATE failed the "
                            + "migrate service");
        }
    }
}
