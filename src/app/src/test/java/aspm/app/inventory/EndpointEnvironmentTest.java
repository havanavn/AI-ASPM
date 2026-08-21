package aspm.app.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The environment an endpoint is published in is tenant vocabulary. {@code CFG-AST-002}, ADR-061.
 *
 * <p><b>The defect these assertions exist for.</b> The schema treated the environment as tenant
 * vocabulary — an indexed JSON attribute on the published-on edge, deliberately unconstrained — and
 * the two editors then enumerated it anyway, in code, differently from each other: Production and
 * Staging on applications, Production and UAT on projects. Because the inventory offered an endpoint
 * column only for environments its recorded data already carried, an environment with no write path
 * never acquired one, never became a column, and was therefore absent from every list, filter and
 * count. An application's UAT host could not be recorded at all.
 *
 * <p>Two further defects in the same surface are covered here. The endpoint columns were sent with
 * {@code filterable: false} while both list endpoints validated filter keys against the declared-field
 * catalogue, so a filter naming one was silently discarded and the unfiltered list came back looking
 * filtered. And both editors read one host per environment, so a second host in one environment was
 * never displayed and its edge was closed on save.
 */
class EndpointEnvironmentTest {

    @Nested
    @DisplayName("The endpoint filter")
    class Filters {

        private static Map<String, TreeSet<String>> hosts(String environment, String... names) {
            TreeSet<String> set = new TreeSet<>();
            set.addAll(List.of(names));
            return Map.of(environment, set);
        }

        @Test
        @DisplayName("a filter with neither presence nor fragment is not applied at all")
        void inertFilterIsInert() {
            assertFalse(new InventoryService.HostFilter("UAT", "", "").active(),
                    "an inactive filter must be dropped rather than applied, or clearing a control "
                            + "would narrow the list instead of widening it");
            assertTrue(new InventoryService.HostFilter("UAT", "RECORDED", "").active());
            assertTrue(new InventoryService.HostFilter("UAT", "", "uat.").active());
        }

        @Test
        @DisplayName("RECORDED matches an asset with a host in that environment, and only that")
        void recorded() {
            var filter = new InventoryService.HostFilter("UAT", "RECORDED", "");
            assertTrue(filter.matches(hosts("UAT", "uat.pay.example.internal")));
            assertFalse(filter.matches(hosts("PRODUCTION", "pay.example.com")),
                    "a host in another environment is not a host in this one; matching it would "
                            + "report an estate as inventoried in an environment nobody recorded");
            assertFalse(filter.matches(Map.of()));
            assertFalse(filter.matches(null), "an asset with no hosts at all must not match");
        }

        @Test
        @DisplayName("PP-1: ABSENT is a predicate of its own, not the failure of the other one")
        void absent() {
            var filter = new InventoryService.HostFilter("UAT", "ABSENT", "");
            assertTrue(filter.matches(hosts("PRODUCTION", "pay.example.com")),
                    "an application in production with no UAT host recorded is exactly what this "
                            + "filter is for: not-measured is a finding, and it is the one a "
                            + "pre-production estate nobody has inventoried is hiding in");
            assertTrue(filter.matches(Map.of()));
            assertTrue(filter.matches(null));
            assertFalse(filter.matches(hosts("UAT", "uat.pay.example.internal")));
        }

        @Test
        @DisplayName("a fragment matches a substring, case-insensitively, in that environment only")
        void contains() {
            var filter = new InventoryService.HostFilter("UAT", "", "UAT.PAY");
            assertTrue(filter.matches(hosts("UAT", "uat.pay.example.internal")),
                    "somebody pastes a host out of an alert in whatever case the alert used; an "
                            + "equality test would answer \"nothing\" for a host that is recorded");
            assertFalse(filter.matches(hosts("UAT", "stg.pay.example.internal")));
            assertFalse(filter.matches(hosts("PRODUCTION", "uat.pay.example.internal")),
                    "the fragment is scoped to the environment the column is of");
        }

        @Test
        @DisplayName("ABSENT with a fragment means no host here matching it, which is coherent")
        void absentAndFragmentCompose() {
            var filter = new InventoryService.HostFilter("UAT", "ABSENT", "example.com");
            assertTrue(filter.matches(hosts("UAT", "uat.pay.example.internal")),
                    "the UAT host exists and does not match the fragment, so this asset has no "
                            + "matching UAT host — which is what was asked");
            assertFalse(filter.matches(hosts("UAT", "uat.pay.example.com")));
        }
    }

    @Nested
    @DisplayName("The catalogue row")
    class Rows {

        private static InventoryService.EndpointEnvironment environment(String state,
                boolean recorded) {
            return new InventoryService.EndpointEnvironment(
                    "UNDECLARED".equals(state) ? null : UUID.randomUUID(), "UAT", "UAT", "", null, 20,
                    state, recorded, 1);
        }

        @Test
        @DisplayName("CFG-AST-002: an active environment gets a column with nothing recorded in it")
        void activeAndEmptyIsStillOffered() {
            assertTrue(environment("ACTIVE", false).columnWorthy(),
                    "suppressing the empty column is the defect: \"no UAT host recorded against this "
                            + "project\" is the answer somebody needs, and there was no way for the "
                            + "platform to give it");
            assertTrue(environment("ACTIVE", true).columnWorthy());
        }

        @Test
        @DisplayName("a retired environment keeps its column only while it still has data to show")
        void retiredFollowsItsData() {
            assertTrue(environment("DEPRECATED", true).columnWorthy(),
                    "retiring an environment does not touch a single edge, so hiding the column "
                            + "would hide hosts that are still current");
            assertFalse(environment("DEPRECATED", false).columnWorthy());
        }

        @Test
        @DisplayName("an environment only the data carries is shown, and is not offered in a form")
        void undeclaredIsVisibleButNotOffered() {
            var undeclared = environment("UNDECLARED", true);
            assertTrue(undeclared.columnWorthy(),
                    "an importer may write an environment nobody declared; dropping it would "
                            + "discard a recorded host (PP-1)");
            assertFalse(undeclared.active(),
                    "and it must not be offered in a form, because a form option outside the "
                            + "catalogue is a vocabulary nobody agreed to");
            assertFalse(undeclared.declared());
        }
    }

    @Nested
    @DisplayName("Nothing enumerates the vocabulary any more")
    class Vocabulary {

        /**
         * The surfaces that used to name environments, and one that legitimately still does.
         *
         * <p>{@code IntakeService} is excluded and the exclusion is the point: its set is the
         * environment an <em>assessment request will be tested against</em>, constrained by a CHECK in
         * V010, which is a different surface from where an asset is published. It is a fixed
         * enumeration for a plausibly configurable surface and it is not this change's to fix —
         * naming it here rather than leaving it out silently is what stops it being mistaken for
         * coverage.
         */
        private static final List<Path> SURFACES = List.of(
                Path.of("src/main/java/aspm/app/ui/EditorApi.java"),
                Path.of("src/main/java/aspm/app/ui/UiApi.java"),
                Path.of("src/main/java/aspm/app/ui/ApplicationPages.java"),
                Path.of("../webui/src/pages/ApplicationEditPage.tsx"),
                Path.of("../webui/src/pages/ProjectEditPage.tsx"),
                Path.of("../webui/src/pages/ProjectPage.tsx"));

        @Test
        @DisplayName("CFG-AST-002: no editor or list names an environment in code")
        void noSurfaceNamesAnEnvironment() throws IOException {
            List<String> offences = new java.util.ArrayList<>();
            for (Path surface : SURFACES) {
                if (!Files.exists(surface)) {
                    // Named rather than skipped: a moved file must fail this test, because a check
                    // that silently passes when its input is gone is the vacuous kind.
                    offences.add(surface + ": not found, so this surface is unchecked");
                    continue;
                }
                String source = Files.readString(surface, StandardCharsets.UTF_8);
                for (String name : List.of("\"PRODUCTION\"", "\"STAGING\"", "\"UAT\"",
                        "\"PREPROD\"", "productionDomain", "stagingDomain", "uatDomain")) {
                    if (source.contains(name)) {
                        offences.add(surface + ": names " + name);
                    }
                }
            }
            assertEquals(List.of(), offences,
                    "an environment named in a form is an environment the tenant cannot rename, and "
                            + "one the tenant cannot add is one that never becomes a column — which "
                            + "is how an application's UAT host came to be unrecordable (ADR-061). "
                            + "Read the environments from asset_endpoint_environment instead.");
        }
    }

    @Nested
    @DisplayName("The migration says what it does, and keeps saying it")
    class Migration {

        private static final Path V069 = Path.of(
                "../module/asset-inventory-impl/src/main/resources/db/migration/"
                        + "V069__endpoint_environment_catalogue.sql");

        @Test
        @DisplayName("CON-DAT-012: the catalogue is a tenant-isolated table")
        void isolated() throws IOException {
            String sql = Files.readString(V069, StandardCharsets.UTF_8);
            assertTrue(sql.contains("apply_tenant_isolation('asset_endpoint_environment')"),
                    "a tenant-scoped table added without the isolation call is a silent hole; the "
                            + "conformance query would find it, and only after a deployment");
            assertFalse(sql.contains("REFERENCES asset_endpoint_environment"),
                    "the edge attribute must NOT reference the catalogue. An importer may carry an "
                            + "environment nobody has declared, and rejecting the edge would discard "
                            + "a recorded fact (PP-1) — the catalogue governs what is OFFERED");
        }

        @Test
        @DisplayName("the backfill takes both the retired code lists and whatever the data holds")
        void backfillLosesNothing() throws IOException {
            String sql = Files.readString(V069, StandardCharsets.UTF_8);
            for (String code : List.of("'PRODUCTION'", "'UAT'", "'STAGING'")) {
                assertTrue(sql.contains(code),
                        "the three codes the two editors had compiled into them must survive the "
                                + "release that removes them from the code, or a form input "
                                + "disappears: " + code);
            }
            assertTrue(sql.contains("SELECT DISTINCT r.attributes ->> 'environment'"),
                    "an estate that arrived by import carries environments nobody typed into a "
                            + "form, and they have to be declared or their columns read as "
                            + "undeclared for ever");
        }
    }

    @Nested
    @DisplayName("Against a real engine")
    class Engine {

        private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

        private static io.zonky.test.db.postgres.embedded.EmbeddedPostgres embedded;
        private static InventoryService inventory;
        private static aspm.app.runtime.Principal principal;

        /**
         * The fixture is the SHAPE of the schema, not the schema.
         *
         * <p>What is under test is one query — the outer join that reports the catalogue and the
         * recorded data together — and pulling in sixty-nine migrations would test their ability to
         * replay. The columns below are the ones the query reads and no others; row-level security is
         * deliberately absent, because isolation is asserted where it belongs, against the real
         * schema, and a policy here would only assert that this fixture has one.
         */
        @BeforeAll
        static void start() throws IOException, SQLException {
            embedded = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start();
            javax.sql.DataSource dataSource = embedded.getPostgresDatabase();
            try (java.sql.Connection c = dataSource.getConnection();
                    java.sql.Statement s = c.createStatement()) {
                s.execute("""
                        CREATE FUNCTION establish_tenant_session(tenant_id text,
                                                                 transaction_local boolean)
                            RETURNS void LANGUAGE plpgsql AS $$
                        BEGIN
                            PERFORM set_config('aspm.current_tenant', tenant_id, transaction_local);
                        END;
                        $$;
                        """);
                s.execute("""
                        CREATE FUNCTION current_tenant_id() RETURNS uuid LANGUAGE sql AS
                            $$ SELECT current_setting('aspm.current_tenant')::uuid $$;
                        """);
                s.execute("""
                        CREATE TABLE asset_type (id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
                                                 code text NOT NULL)
                        """);
                s.execute("""
                        CREATE TABLE asset (id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
                                            type_id uuid NOT NULL, display_name text NOT NULL,
                                            identity_key text NOT NULL)
                        """);
                s.execute("""
                        CREATE TABLE asset_relationship (id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
                                                         from_asset_id uuid NOT NULL,
                                                         to_asset_id uuid NOT NULL,
                                                         edge_type text NOT NULL,
                                                         attributes jsonb NOT NULL DEFAULT '{}',
                                                         valid_until timestamptz)
                        """);
                s.execute("""
                        CREATE TABLE asset_endpoint_environment (
                            id uuid PRIMARY KEY, tenant_id uuid NOT NULL, code text NOT NULL,
                            label_i18n jsonb NOT NULL, purpose text, ordinal int NOT NULL,
                            lifecycle_state text NOT NULL, row_version int NOT NULL DEFAULT 1)
                        """);

                UUID typeDomain = UUID.randomUUID();
                UUID typeApp = UUID.randomUUID();
                s.execute("INSERT INTO asset_type VALUES ('" + typeDomain + "', '" + TENANT
                        + "', 'DOMAIN'), ('" + typeApp + "', '" + TENANT + "', 'APPLICATION')");

                // Three declared environments: one with a host, one active and empty, one retired and
                // still holding a host. Plus two environments the catalogue has never heard of — one
                // an importer named, and one edge carrying no environment at all.
                s.execute("INSERT INTO asset_endpoint_environment "
                        + "(id, tenant_id, code, label_i18n, purpose, ordinal, lifecycle_state) VALUES "
                        + "('" + UUID.randomUUID() + "', '" + TENANT + "', 'PRODUCTION', "
                        + "'{\"en\":\"Production\"}', 'real users', 10, 'ACTIVE'), "
                        + "('" + UUID.randomUUID() + "', '" + TENANT + "', 'UAT', "
                        + "'{\"en\":\"UAT\"}', 'acceptance testing', 20, 'ACTIVE'), "
                        + "('" + UUID.randomUUID() + "', '" + TENANT + "', 'STAGING', "
                        + "'{\"en\":\"Staging\"}', NULL, 30, 'DEPRECATED')");

                UUID app = UUID.randomUUID();
                s.execute("INSERT INTO asset VALUES ('" + app + "', '" + TENANT + "', '" + typeApp
                        + "', 'Payments API', 'payments api')");
                for (String host : List.of("pay.example.com", "stg.example.internal",
                        "sit.example.internal", "orphan.example.internal", "closed.example.com")) {
                    s.execute("INSERT INTO asset VALUES ('" + UUID.randomUUID() + "', '" + TENANT
                            + "', '" + typeDomain + "', '" + host + "', '" + host + "')");
                }
                edge(s, app, "pay.example.com", "{\"environment\":\"PRODUCTION\"}", false);
                edge(s, app, "stg.example.internal", "{\"environment\":\"STAGING\"}", false);
                edge(s, app, "sit.example.internal", "{\"environment\":\"SIT\"}", false);
                edge(s, app, "orphan.example.internal", "{}", false);
                // A closed edge, to prove the counts and the offering read the CURRENT graph. An
                // environment nothing is published in any more must not keep a column on the strength
                // of history.
                edge(s, app, "closed.example.com", "{\"environment\":\"PREPROD\"}", true);
            }
            inventory = new InventoryService(dataSource);
            principal = new aspm.app.runtime.Principal(TENANT, UUID.randomUUID(),
                    java.util.Set.of(), java.util.Set.of(), true, false, false);
        }

        private static void edge(java.sql.Statement s, UUID from, String host, String attributes,
                boolean closed) throws SQLException {
            s.execute("INSERT INTO asset_relationship (id, tenant_id, from_asset_id, to_asset_id, "
                    + "edge_type, attributes, valid_until) SELECT '" + UUID.randomUUID() + "', '"
                    + TENANT + "', '" + from + "', d.id, 'PUBLISHED_ON', '" + attributes + "'::jsonb, "
                    + (closed ? "now()" : "NULL")
                    + " FROM asset d WHERE d.display_name = '" + host + "'");
        }

        @AfterAll
        static void stop() throws IOException {
            if (embedded != null) {
                embedded.close();
            }
        }

        private static InventoryService.EndpointEnvironment byCode(
                List<InventoryService.EndpointEnvironment> rows, String code) {
            return rows.stream().filter(e -> code.equals(e.code())).findFirst().orElseThrow(
                    () -> new AssertionError(code + " is absent from " + rows.stream()
                            .map(InventoryService.EndpointEnvironment::code).toList()));
        }

        @Test
        @DisplayName("CFG-AST-002: the catalogue and the recorded data are reported together")
        void catalogueAndDataAreOneList() throws SQLException {
            List<InventoryService.EndpointEnvironment> rows =
                    inventory.endpointEnvironments(principal);

            var production = byCode(rows, "PRODUCTION");
            assertTrue(production.active());
            assertTrue(production.recorded());
            assertEquals("Production", production.label());
            assertEquals("real users", production.purpose());

            // The requirement's operative half. Nothing is published in UAT in this fixture and it
            // still has to come back, or the column that says so cannot be offered.
            var uat = byCode(rows, "UAT");
            assertTrue(uat.active());
            assertFalse(uat.recorded());
            assertTrue(uat.columnWorthy(),
                    "an active environment with nothing recorded is exactly the case that was "
                            + "invisible: no write path, so no data, so no column, so no way to ask");

            var staging = byCode(rows, "STAGING");
            assertEquals("DEPRECATED", staging.lifecycleState());
            assertTrue(staging.recorded());
            assertTrue(staging.columnWorthy(),
                    "retiring an environment does not close an edge, so its hosts must stay visible");

            // An importer's environment. Reported, marked as nobody's declaration, and offered as a
            // column — hiding it would hide a recorded host.
            var sit = byCode(rows, "SIT");
            assertEquals("UNDECLARED", sit.lifecycleState());
            assertFalse(sit.declared());
            assertFalse(sit.active());
            assertTrue(sit.recorded());
            assertEquals("SIT", sit.label(), "with no catalogue row, the code is the only label");

            // An edge carrying no environment at all lands under the same substitute the columns and
            // the filters use, rather than vanishing from one and appearing in the other.
            assertTrue(byCode(rows, "UNSPECIFIED").recorded());

            assertTrue(rows.stream().map(InventoryService.EndpointEnvironment::code).toList()
                            .indexOf("PRODUCTION") < rows.stream()
                            .map(InventoryService.EndpointEnvironment::code).toList().indexOf("SIT"),
                    "declared environments sort by the ordinal the tenant chose; an undeclared one "
                            + "sorts after all of them rather than taking precedence over the "
                            + "vocabulary somebody agreed");
        }

        @Test
        @DisplayName("a closed edge keeps no column and is counted in no total")
        void historyDoesNotOfferAColumn() throws SQLException {
            List<InventoryService.EndpointEnvironment> rows =
                    inventory.endpointEnvironments(principal);
            assertFalse(rows.stream().anyMatch(e -> "PREPROD".equals(e.code())),
                    "the only PREPROD edge is closed. Offering the column on the strength of history "
                            + "would report an estate as published where it no longer is");
            assertEquals(0L, inventory.endpointsInEnvironment(principal, "PREPROD"));
            assertEquals(1L, inventory.endpointsInEnvironment(principal, "PRODUCTION"));
            assertEquals(1L, inventory.endpointsInEnvironment(principal, "UNSPECIFIED"),
                    "the count uses the same substitute as the read, or the number beside the retire "
                            + "button disagrees with the column it governs");
        }

        @Test
        @DisplayName("hosts are grouped by environment, over the asset and its parts")
        void hostsGroupByEnvironment() throws SQLException {
            UUID app;
            try (java.sql.Connection c = embedded.getPostgresDatabase().getConnection();
                    java.sql.Statement s = c.createStatement();
                    java.sql.ResultSet r = s.executeQuery(
                            "SELECT id FROM asset WHERE identity_key = 'payments api'")) {
                assertTrue(r.next());
                app = r.getObject(1, UUID.class);
            }
            var hosts = inventory.hostsByAsset(principal, List.of(app), false).get(app);
            assertEquals(java.util.Set.of("pay.example.com"), hosts.get("PRODUCTION"));
            assertEquals(java.util.Set.of("stg.example.internal"), hosts.get("STAGING"));
            assertEquals(java.util.Set.of("orphan.example.internal"), hosts.get("UNSPECIFIED"));
            assertFalse(hosts.containsKey("PREPROD"), "the closed edge is history, not a host");
            assertFalse(hosts.containsKey("UAT"),
                    "and an environment with nothing recorded has no entry, which is what the cell "
                            + "renders as \"not recorded\" rather than as an empty list");
        }
    }
}
