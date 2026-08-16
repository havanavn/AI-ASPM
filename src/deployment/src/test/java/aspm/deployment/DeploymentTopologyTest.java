package aspm.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Prompt 19 — deployment. DOC-15 sections 4, 5 and 6. */
class DeploymentTopologyTest {

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-15 section 5.1 — four credentials, three unreachable")
    class Credentials {

        @Test
        @DisplayName("OPS-DEP-009: no bypass credential is injectable into a runtime environment")
        void bypassCredentialsAreAbsentFromRuntime() {
            assertEquals(3, DatabaseCredential.bypassCredentials().size(),
                    "DOC-15 section 5.1 names three: migration_runner, integrity_verifier, "
                            + "offboarding_executor");
            for (DatabaseCredential credential : DatabaseCredential.bypassCredentials()) {
                assertFalse(credential.injectableInto().contains(DatabaseCredential.InjectionScope.RUNTIME),
                        credential + " is injectable into a runtime environment. Credential separation is what "
                                + "makes bypass unreachability STRUCTURAL rather than procedural — an "
                                + "application that cannot obtain the credential cannot use the bypass "
                                + "regardless of what its code attempts (OPS-DEP-009).");
            }
        }

        @Test
        @DisplayName("the type refuses to construct one, so a manifest cannot express it")
        void aBypassCredentialInRuntimeCannotBeConstructed() {
            // Reflection rather than a public constructor: the four are constants precisely so a fifth cannot
            // be declared. This asserts the guard rather than the absence of a caller.
            var ex = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> {
                var constructor = DatabaseCredential.class.getDeclaredConstructor(
                        String.class, boolean.class, boolean.class, Set.class);
                constructor.setAccessible(true);
                constructor.newInstance("rogue_runner", true, false,
                        EnumSet.of(DatabaseCredential.InjectionScope.RUNTIME));
            });
            assertTrue(ex.getCause().getMessage().contains("structural rather than procedural"),
                    "the guard must state why, because the person adding the credential believes they need it");
        }

        @Test
        @DisplayName("runtime units get app_runtime, and there is no argument that selects another")
        void runtimeUnitsGetTheEnforcedCredential() {
            assertEquals(DatabaseCredential.APP_RUNTIME, DatabaseCredential.forRuntimeUnits());
            assertFalse(DatabaseCredential.forRuntimeUnits().bypassesRowLevelSecurity());

            // The absent method, not the correct return value: a selector taking a unit is how a worker tier
            // ends up with a bypass role for one legitimate-sounding reason.
            boolean hasSelector = java.util.Arrays.stream(DatabaseCredential.class.getDeclaredMethods())
                    .anyMatch(m -> m.getReturnType() == DatabaseCredential.class
                            && java.util.Arrays.asList(m.getParameterTypes()).contains(RuntimeUnit.class));
            assertFalse(hasSelector,
                    "a method selecting a credential per runtime unit exists. There is one credential for "
                            + "every unit and no argument that could choose another (OPS-DEP-009).");
        }

        @Test
        @DisplayName("OPS-DEP-009: bypass use is audited, and it is not configurable")
        void bypassUseIsAudited() {
            for (DatabaseCredential credential : DatabaseCredential.bypassCredentials()) {
                assertTrue(credential.useIsAudited(), credential + " uses the bypass without an audit event");
            }
            assertFalse(DatabaseCredential.APP_RUNTIME.useIsAudited(),
                    "auditing every ordinary query is not what OPS-DEP-009 asks for; the event marks the "
                            + "bypass");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-15 section 5.1 — pooled connections")
    class Pooling {

        @Test
        @DisplayName("OPS-DEP-010: a returned connection carries no tenant")
        void returnResetsSessionState() {
            var connection = new ConnectionPoolPolicy.PooledConnection();
            ConnectionPoolPolicy.borrow(connection);
            connection.bindTenant(UUID.randomUUID());
            ConnectionPoolPolicy.returnToPool(connection);

            assertTrue(connection.boundTenant().isEmpty(),
                    "a session variable carrying tenant context and surviving into the next borrower's "
                            + "request is a documented cross-tenant disclosure mechanism in row-level-security "
                            + "deployments (OPS-DEP-010, SEC-TEN-007)");
            assertFalse(connection.inUse());
        }

        @Test
        @DisplayName("SEC-TEN-007: borrowing a connection with residue fails loudly")
        void borrowRefusesAStaleTenantContext() {
            var connection = new ConnectionPoolPolicy.PooledConnection();
            connection.bindTenant(UUID.randomUUID());

            var ex = assertThrows(IllegalStateException.class, () -> ConnectionPoolPolicy.borrow(connection));
            assertTrue(ex.getMessage().contains("wrong tenant"),
                    "the rows come back correctly filtered, for the wrong tenant — which is why this cannot be "
                            + "left to the reset on return alone. An abandoned transaction, a killed thread or "
                            + "a pool eviction skips the return path.");
        }

        @Test
        @DisplayName("the reset covers every variable row-level security reads")
        void resetCoversEveryVariable() {
            assertTrue(ConnectionPoolPolicy.TENANT_SESSION_VARIABLES.contains("aspm.tenant_id"));
            assertTrue(ConnectionPoolPolicy.TENANT_SESSION_VARIABLES.contains("aspm.bypass_reason"),
                    "a surviving bypass_reason is worse than a surviving tenant: it is a bypass the next "
                            + "borrower did not ask for and cannot see");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-15 sections 4 and 6 — placement and egress")
    class Topology {

        @Test
        @DisplayName("OPS-DEP-006: match workers are not co-scheduled with the application tier")
        void matchWorkersAreIsolated() {
            assertTrue(RuntimeUnit.MATCH_WORKERS.coScheduledWith().isEmpty(),
                    "match workers share a pool with " + RuntimeUnit.MATCH_WORKERS.coScheduledWith()
                            + ". They hold the intelligence database resident, and the failure this prevents "
                            + "occurs during a portfolio sweep triggered by a high-profile disclosure — "
                            + "precisely when the platform must be available (OPS-DEP-006, CON-PLT-008).");
            assertFalse(RuntimeUnit.MATCH_WORKERS.coScheduledWith().contains(RuntimeUnit.APPLICATION_TIER));
        }

        @Test
        @DisplayName("OPS-DEP-014: egress is deny-by-default per unit, not per platform")
        void egressIsPerUnit() {
            // The parser and webhook delivery live in the general workers. The application tier must not
            // inherit their reach, and they must not inherit the intelligence feed.
            assertFalse(RuntimeUnit.APPLICATION_TIER.mayReach(RuntimeUnit.Destination.CONNECTOR_ALLOWLIST),
                    "the application tier reaching the connector allowlist makes the union of every unit's "
                            + "needs the reach of each — which is the platform-wide allowlist OPS-DEP-014 "
                            + "avoids by being per unit");
            assertFalse(RuntimeUnit.GENERAL_WORKERS.mayReach(RuntimeUnit.Destination.INTELLIGENCE_FEED),
                    "the unit processing hostile documents must not reach the intelligence feed");
            assertFalse(RuntimeUnit.MATCH_WORKERS.mayReach(RuntimeUnit.Destination.WEBHOOK_ALLOWLIST));

            assertTrue(RuntimeUnit.SCHEDULER.egressAllowlist().isEmpty(),
                    "the scheduler only enqueues work (OPS-DEP-007), so it needs no destination at all");
            assertTrue(RuntimeUnit.WEB_TIER.egressAllowlist().isEmpty());
        }

        @Test
        @DisplayName("every unit declares a resource profile and restarts on its memory limit")
        void everyUnitDeclaresLimits() {
            for (RuntimeUnit unit : RuntimeUnit.values()) {
                assertTrue(unit.restartOnMemoryLimit(),
                        unit + " may degrade its node instead of restarting. Without a limit an out-of-memory "
                                + "condition takes the node and everything on it (OPS-DEP-005).");
                assertEquals(7, RuntimeUnit.values().length,
                        "DOC-15 section 4 tabulates seven units; an eighth needs a row in the document first");
            }
        }

        @Test
        @DisplayName("OPS-DEP-008: readiness and liveness cannot point at the same endpoint")
        void probesAreSeparate() {
            for (RuntimeUnit unit : RuntimeUnit.values()) {
                var probes = unit.probes();
                assertFalse(probes.readinessPath().equals(probes.livenessPath()), unit + " conflates them");
            }
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> new RuntimeUnit.Probes("/health", "/health"));
            assertTrue(ex.getMessage().contains("turns a degraded state into an outage"));
        }

        @Test
        @DisplayName("egress policies are generated for every unit, including the units with none")
        void everyUnitHasAPolicy() {
            assertEquals(RuntimeUnit.values().length, RuntimeUnit.egressPolicies().size(),
                    "a unit absent from the generated policies is a unit with no policy applied, which under "
                            + "deny-by-default at the cluster means whatever the cluster default is — and the "
                            + "cluster default is allow");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-15 section 6 — the object storage origin")
    class Origins {

        @Test
        @DisplayName("OPS-DEP-016: uploaded content is served from a distinct registrable domain")
        void uploadedContentIsADistinctOrigin() {
            var api = ServingOrigin.application("app.example.com");
            var web = ServingOrigin.application("www.example.com");
            var content = ServingOrigin.uploadedContent("content.example-usercontent.net");

            content.assertDistinctFrom(List.of(api, web));
        }

        @Test
        @DisplayName("a subdomain is the configuration that looks like isolation and is not")
        void aSubdomainIsRejected() {
            var api = ServingOrigin.application("app.example.com");
            var content = ServingOrigin.uploadedContent("content.example.com");

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> content.assertDistinctFrom(List.of(api)));
            assertTrue(ex.getMessage().contains("same-origin execution risk"),
                    "evidence handling is content that is EXPECTED to be malicious and must remain "
                            + "retrievable; served from the application origin it executes with the session");
        }

        @Test
        @DisplayName("uploaded content is attachment with type enforcement, and there is no inline factory")
        void uploadedContentIsNeverInline() {
            var content = ServingOrigin.uploadedContent("content.example-usercontent.net");
            assertEquals(ServingOrigin.Disposition.ATTACHMENT, content.disposition());
            assertTrue(content.contentTypeEnforced(),
                    "disposition and type enforcement are defence in depth; the separate origin is the control");
        }
    }
}
