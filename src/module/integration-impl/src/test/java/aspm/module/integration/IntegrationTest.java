package aspm.module.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.integration.domain.ConnectorHealth;
import aspm.module.integration.domain.EgressPolicy;
import aspm.module.integration.domain.FailureClass;
import aspm.module.integration.domain.IdentitySynchronization;
import aspm.module.integration.domain.OutboundPropagation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Prompt 16 — integration. DOC-21 in full. */
class IntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-05T09:00:00Z");
    private static final UUID CONNECTOR = new UUID(200, 1);
    private static final UUID TENANT = new UUID(200, 2);
    private static final UUID FINDING = new UUID(200, 3);

    private static EgressPolicy policy() {
        return new EgressPolicy(List.of(
                new EgressPolicy.Destination("tracker", "https", "tracker.example.com", 443),
                new EgressPolicy.Destination("chat", "https", "chat.example.com", 443)));
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-CON-024 / -025 — classification drives retry")
    class Classification {

        @Test
        @DisplayName("a credential failure is NEVER retried, expressed as zero attempts rather than a flag")
        void credentialFailuresAreNotRetried() {
            for (FailureClass failureClass : List.of(FailureClass.AUTHENTICATION,
                    FailureClass.AUTHORIZATION)) {
                assertEquals(0, failureClass.maxRetryAttempts(),
                        failureClass + ": blind retry locks the account on the target system, converting a "
                                + "configuration problem in the platform into an outage in the CUSTOMER's "
                                + "engineering estate (PRD-CON-024)");
                assertFalse(failureClass.retryable());
                assertTrue(failureClass.backoffBefore(1, Optional.empty()).isEmpty(),
                        "a caller that ignored retryable() still cannot obtain a delay");
                assertTrue(failureClass.marksConnectorUnhealthy() && failureClass.notifiesOwner());
            }
        }

        @Test
        @DisplayName("every class has a distinct policy — no undifferentiated retry")
        void everyClassIsClassified() {
            for (FailureClass failureClass : FailureClass.values()) {
                assertTrue(failureClass.maxRetryAttempts() >= 0);
            }
            assertTrue(FailureClass.TRANSIENT.retryable(), "the class ordinary backoff was designed for");
            assertFalse(FailureClass.DATA.retryable(),
                    "retrying a record the target rejected produces the same rejection");
            assertEquals(1, FailureClass.PROTOCOL.maxRetryAttempts(),
                    "once, because a single malformed response is occasionally transient and a persistent one "
                            + "needs a human either way");
        }

        @Test
        @DisplayName("PRD-CON-026: a DATA failure quarantines the record and does not fail the run")
        void dataFailureDoesNotFailTheRun() {
            assertTrue(FailureClass.DATA.quarantinesRecordAndContinues());
            assertFalse(FailureClass.DATA.marksConnectorUnhealthy(),
                    "one record the target rejects must not discard the run's other work");

            var health = new ConnectorHealth(CONNECTOR, TENANT);
            for (int i = 0; i < 20; i++) {
                health.recordFailure(FailureClass.DATA, T0.plusSeconds(i));
            }
            assertFalse(health.circuitOpen(),
                    "counting data failures would open a circuit because one record is malformed, stopping an "
                            + "integration that is working");
        }

        @Test
        @DisplayName("PRD-CON-027: a throttling signal from the target overrides the platform's schedule")
        void rateLimitHonoursTheTargetSignal() {
            var signalled = FailureClass.RATE_LIMITED.backoffBefore(1, Optional.of(Duration.ofMinutes(10)));
            assertEquals(Optional.of(Duration.ofMinutes(10)), signalled,
                    "backing off less than the target asked is how a customer's quota gets exhausted while "
                            + "appearing to behave — and exhausting it affects their OTHER systems");

            var unsignalled = FailureClass.RATE_LIMITED.backoffBefore(1, Optional.empty());
            assertTrue(unsignalled.isPresent());
        }

        @Test
        @DisplayName("backoff is exponential and capped")
        void backoffIsCapped() {
            var late = FailureClass.TRANSIENT.backoffBefore(5, Optional.empty()).orElseThrow();
            assertTrue(late.toSeconds() <= 300,
                    "an unbounded backoff on a long-running sweep is indistinguishable from a stall");
            assertTrue(FailureClass.TRANSIENT.backoffBefore(2, Optional.empty()).orElseThrow()
                    .compareTo(FailureClass.TRANSIENT.backoffBefore(1, Optional.empty()).orElseThrow()) > 0);
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-CON-032 to -034 — egress")
    class Egress {

        @Test
        @DisplayName("there is no method taking a URL, so a record field cannot become a destination")
        void destinationsComeFromConfigurationOnly() {
            for (Method m : EgressPolicy.class.getMethods()) {
                if (!m.getName().equals("resolve")) {
                    continue;
                }
                for (Class<?> parameter : m.getParameterTypes()) {
                    assertFalse(parameter == URI.class || parameter == java.net.URL.class,
                            "a connector accepting a data-derived destination is a server-side request forgery "
                                    + "primitive positioned inside the platform's network, operating with the "
                                    + "platform's credentials (PRD-CON-032)");
                }
            }
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> policy().resolve("https://attacker.example/x"));
            assertTrue(ex.getMessage().contains("server-side request forgery"),
                    "and an unconfigured name is refused rather than treated as a host — that fallback is the "
                            + "data-derived destination arriving through a typo");
        }

        @Test
        @DisplayName("PRD-CON-033: resolution is re-checked at connection time")
        void resolutionRecheckedAtConnectTime() {
            var destination = policy().resolve("tracker");

            var permitted = policy().permitConnection(destination, host -> List.of("203.0.113.10"));
            assertTrue(permitted.permitted());

            // Same configured destination, now resolving internally — the rebinding case.
            var rebound = policy().permitConnection(destination, host -> List.of("169.254.169.254"));
            assertFalse(rebound.permitted());
            assertTrue(rebound.reason().contains("rebinding"),
                    "re-checking at connection time closes the gap between validation and use, which is "
                            + "otherwise a bypass of the allowlist");
        }

        @Test
        @DisplayName("every internal and link-local range is refused")
        void internalRangesRefused() {
            var destination = policy().resolve("tracker");
            for (String address : List.of("127.0.0.1", "10.1.2.3", "192.168.0.5", "169.254.169.254",
                    "0.0.0.0", "::1", "fd00::1", "fe80::1")) {
                assertFalse(policy().permitConnection(destination, host -> List.of(address)).permitted(),
                        address + " was permitted");
            }
        }

        @Test
        @DisplayName("an unresolvable host fails closed")
        void unresolvableHostIsRefused() {
            var verdict = policy().permitConnection(policy().resolve("tracker"), host -> List.of());
            assertFalse(verdict.permitted());
            assertTrue(verdict.reason().contains("would skip this check"),
                    "treating resolution failure as 'carry on and let the connection fail' skips the range "
                            + "check entirely");
        }

        @Test
        @DisplayName("PRD-CON-034: a redirect outside the allowlist is not followed")
        void redirectsAreNotFollowedOutside() {
            var outside = policy().permitRedirect(URI.create("https://169.254.169.254/latest/meta-data"),
                    host -> List.of("169.254.169.254"));
            assertFalse(outside.permitted());
            assertTrue(outside.reason().contains("standard bypass"),
                    "a permitted destination redirecting to an internal address is THE standard bypass of "
                            + "destination allowlisting");

            var withinAllowlist = policy().permitRedirect(URI.create("https://chat.example.com/api"),
                    host -> List.of("203.0.113.20"));
            assertTrue(withinAllowlist.permitted(),
                    "a redirect to another CONFIGURED destination is fine — that destination was validated");
        }

        @Test
        @DisplayName("a redirect target is re-resolved too, so the allowlist is not a name check")
        void redirectTargetIsResolved() {
            var reboundRedirect = policy().permitRedirect(URI.create("https://chat.example.com/api"),
                    host -> List.of("127.0.0.1"));
            assertFalse(reboundRedirect.permitted(),
                    "a configured hostname resolving internally is the same rebinding attack arriving through "
                            + "a redirect");
        }

        @Test
        @DisplayName("plaintext destinations cannot be configured")
        void httpsOnly() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> new EgressPolicy.Destination("x", "http", "tracker.example.com", 80));
            assertTrue(ex.getMessage().contains("access TO the customer's estate"));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-CON-028 to -031 — health, and the failure the circuit cannot see")
    class Health {

        @Test
        @DisplayName("PRD-CON-031: intermittent failure degrades without opening the circuit")
        void intermittentFailureIsCaught() {
            var health = new ConnectorHealth(CONNECTOR, TENANT);
            // Two failures then a success, repeatedly: the consecutive counter never reaches five.
            for (int i = 0; i < 10; i++) {
                health.recordFailure(FailureClass.TRANSIENT, T0.plusSeconds(i * 3L));
                health.recordFailure(FailureClass.TRANSIENT, T0.plusSeconds(i * 3L + 1));
                health.recordSuccess(T0.plusSeconds(i * 3L + 2));
            }

            assertFalse(health.circuitOpen(),
                    "every third attempt succeeds and resets the counter, so the circuit never trips");
            assertTrue(health.degraded(),
                    "a connector at 33% produces data that appears current for the assets it happened to "
                            + "cover and none for the rest, which reads as those assets having nothing to "
                            + "report (PRD-CON-031)");
            assertTrue(health.requiresAlert(),
                    "a health model watching only the circuit has the blind spot in it");
            assertTrue(health.successRatePercent().compareTo(new BigDecimal("40")) < 0);
        }

        @Test
        @DisplayName("a credential failure opens the circuit immediately, not after five attempts")
        void credentialFailureOpensImmediately() {
            var health = new ConnectorHealth(CONNECTOR, TENANT);
            health.recordFailure(FailureClass.AUTHENTICATION, T0);

            assertTrue(health.circuitOpen(),
                    "waiting for five is five authentication attempts against the target, which is how the "
                            + "account gets locked (PRD-CON-024)");
            assertEquals(1, health.consecutiveFailures());
            assertTrue(health.circuitOpenReason().orElseThrow().contains("customer's engineering estate"));
        }

        @Test
        @DisplayName("a credential circuit does not reopen on a timer")
        void credentialCircuitDoesNotSelfHeal() {
            var health = new ConnectorHealth(CONNECTOR, TENANT);
            health.recordFailure(FailureClass.AUTHENTICATION, T0);

            var ex = assertThrows(IllegalStateException.class,
                    () -> health.attemptRecovery(T0.plus(Duration.ofHours(1)), Duration.ofMinutes(5)));
            assertTrue(ex.getMessage().contains("until the credential is corrected"),
                    "half-opening would retry the authentication the circuit opened to prevent");
        }

        @Test
        @DisplayName("PRD-CON-029: an open circuit nobody was told about is detectable")
        void silentSuspensionIsDetectable() {
            var health = new ConnectorHealth(CONNECTOR, TENANT);
            health.recordFailure(FailureClass.AUTHENTICATION, T0);

            assertTrue(health.silentlySuspended(),
                    "a silently suspended integration is a permanent coverage gap, and the coverage metrics "
                            + "will show the gap without anyone knowing its cause");
            health.recordOwnerNotified();
            assertFalse(health.silentlySuspended());
        }

        @Test
        @DisplayName("PRD-CON-030: health feeds the coverage qualifier, and degraded reads worse than open")
        void healthFeedsCoverage() {
            var suspended = new ConnectorHealth(CONNECTOR, TENANT);
            suspended.recordFailure(FailureClass.AUTHENTICATION, T0);
            assertTrue(suspended.coverageQualifier().contains("no absence of findings should be read as clean"),
                    "otherwise coverage reports current data for an asset whose supplying integration has been "
                            + "failing for a month — PP-1 applied to integration health");

            var degraded = new ConnectorHealth(CONNECTOR, TENANT);
            for (int i = 0; i < 10; i++) {
                degraded.recordFailure(FailureClass.TRANSIENT, T0.plusSeconds(i * 2L));
                degraded.recordSuccess(T0.plusSeconds(i * 2L + 1));
            }
            assertTrue(degraded.coverageQualifier().contains("more damaging failure"),
                    "partial data that appears current is worse than none, and the qualifier says so");
        }

        @Test
        @DisplayName("a connector that has never run has no success rate and is not reported as degraded")
        void idleConnectorIsNotDegraded() {
            var health = new ConnectorHealth(CONNECTOR, TENANT);
            assertEquals(0, health.successRatePercent().compareTo(new BigDecimal("0.00")),
                    "no attempts is not a hundred percent; reporting one would make an idle integration look "
                            + "healthy");
            assertFalse(health.degraded(),
                    "and conflating idle with degraded fills the queue with connectors nobody has used yet");
        }

        @Test
        @DisplayName("a half-open probe that fails returns to OPEN")
        void halfOpenFailureReopens() {
            var health = new ConnectorHealth(CONNECTOR, TENANT);
            for (int i = 0; i < ConnectorHealth.CIRCUIT_THRESHOLD; i++) {
                health.recordFailure(FailureClass.TRANSIENT, T0.plusSeconds(i));
            }
            assertTrue(health.circuitOpen());

            health.attemptRecovery(T0.plus(Duration.ofMinutes(10)), Duration.ofMinutes(5));
            assertEquals(ConnectorHealth.CircuitState.HALF_OPEN, health.circuitState());

            health.recordFailure(FailureClass.TRANSIENT, T0.plus(Duration.ofMinutes(11)));
            assertTrue(health.circuitOpen(), "a failed probe does not close the circuit");
        }

        @Test
        @DisplayName("health is per connector per tenant")
        void healthIsPerTenant() {
            assertThrows(NullPointerException.class, () -> new ConnectorHealth(CONNECTOR, null),
                    "PRD-CON-028 is observable per connector PER TENANT; a shared health record would let one "
                            + "tenant's failures suspend another's integration");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-CON-042 / ADR-040 — one-way propagation")
    class Propagation {

        @Test
        @DisplayName("there is no method that applies external state")
        void nothingAppliesExternalState() {
            for (Method m : OutboundPropagation.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("apply") || name.startsWith("sync") || name.startsWith("pull")
                                || name.startsWith("merge") || name.startsWith("reconcile"),
                        "found " + m.getName() + ". Bidirectional synchronization reproduces the failure of a "
                                + "generic tracker used for vulnerability management: closing the external "
                                + "ticket closes the finding, whether or not the vulnerability is gone "
                                + "(ADR-040).");
            }
        }

        @Test
        @DisplayName("a disagreement produces a divergence for a human, not a reconciliation")
        void divergenceIsSurfacedNotResolved() {
            var divergence = OutboundPropagation.observeExternal(FINDING, "OPEN", "CLOSED", T0)
                    .orElseThrow();

            assertEquals(OutboundPropagation.Divergence.Resolution.AWAITING_HUMAN, divergence.resolution());
            assertTrue(divergence.question().contains("tidying the backlog"),
                    "both readings can be right: the ticket may be closed because the fix shipped, or because "
                            + "somebody was tidying — only a person knows which");
            assertEquals(1, OutboundPropagation.Divergence.Resolution.values().length,
                    "there is no resolved state a caller could construct");
        }

        @Test
        @DisplayName("agreement produces nothing")
        void agreementIsSilent() {
            assertTrue(OutboundPropagation.observeExternal(FINDING, "OPEN", "OPEN", T0).isEmpty());
        }

        @Test
        @DisplayName("PRD-CON-037: forbidden fields cannot be included in an outbound payload")
        void forbiddenFieldsCannotBeSent() {
            for (String forbidden : List.of("credentialRef", "secretValue", "evidenceContent",
                    "memberUtilization")) {
                assertThrows(IllegalArgumentException.class,
                        () -> new OutboundPropagation.OutboundPayload(FINDING, "SQLi", "HIGH", "unit-a",
                                Set.of("title", forbidden)),
                        forbidden + " reached an outbound payload; it leaves the platform's control and "
                                + "cannot be recalled");
            }
        }

        @Test
        @DisplayName("PRD-CON-038: outbound content is filtered to the configured scope")
        void outboundIsScopeFiltered() {
            var mine = new OutboundPropagation.OutboundPayload(FINDING, "SQLi", "HIGH", "unit-a",
                    Set.of("title"));
            var theirs = new OutboundPropagation.OutboundPayload(new UUID(200, 9), "XSS", "MEDIUM", "unit-b",
                    Set.of("title"));

            var filtered = OutboundPropagation.withinConfiguredScope(List.of(mine, theirs), Set.of("unit-a"));
            assertEquals(List.of(mine), filtered,
                    "a connector configured for one business unit must not transmit another's findings, and "
                            + "the TARGET system has no scope enforcement to compensate");
        }

        @Test
        @DisplayName("an empty configured scope is a configuration error, not a wildcard")
        void emptyScopeIsNotAWildcard() {
            assertThrows(IllegalArgumentException.class,
                    () -> OutboundPropagation.withinConfiguredScope(List.of(), Set.of()),
                    "a connector with no configured scope would transmit everything");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Identity synchronization manages existence only")
    class IdentitySync {

        @Test
        @DisplayName("no method mentions a role, permission or grant")
        void neverWritesRoleAssignments() {
            for (Method m : IdentitySynchronization.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("role") || name.contains("permission") || name.contains("grant"),
                        "found " + m.getName() + ". A directory group named 'Security Team' is an "
                                + "organizational fact rather than a permission grant; writing role "
                                + "assignments from a directory means a membership change in a system the "
                                + "platform does not control silently alters authorization here, with no "
                                + "record in the platform's own access review.");
            }
            for (var component : IdentitySynchronization.Plan.class.getRecordComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("role") || name.contains("group"));
            }
        }

        @Test
        @DisplayName("a departed principal is deactivated, never deleted")
        void departedPrincipalsAreDeactivated() {
            var known = Map.of("ext-1", new UUID(201, 1), "ext-2", new UUID(201, 2),
                    "ext-3", new UUID(201, 3), "ext-4", new UUID(201, 4),
                    "ext-5", new UUID(201, 5));
            var directory = List.of(
                    new IdentitySynchronization.DirectoryPrincipal("ext-1", "A", true),
                    new IdentitySynchronization.DirectoryPrincipal("ext-2", "B", true),
                    new IdentitySynchronization.DirectoryPrincipal("ext-3", "C", true),
                    new IdentitySynchronization.DirectoryPrincipal("ext-4", "D", true),
                    new IdentitySynchronization.DirectoryPrincipal("ext-5", "E", false));

            var plan = IdentitySynchronization.plan(directory, known);
            assertEquals(1, plan.deactivate().size());
            for (var component : IdentitySynchronization.Plan.class.getRecordComponents()) {
                assertFalse(component.getName().toLowerCase(Locale.ROOT).contains("delete"),
                        "a deleted principal orphans every audit entry, comment and assignment attributed to "
                                + "them, and the audit record is inviolable (PP-5)");
            }
        }

        @Test
        @DisplayName("a truncated directory read is refused rather than deactivating everybody")
        void truncatedReadIsRefused() {
            var known = new java.util.LinkedHashMap<String, UUID>();
            for (int i = 0; i < 100; i++) {
                known.put("ext-" + i, new UUID(202, i));
            }
            var truncated = List.of(
                    new IdentitySynchronization.DirectoryPrincipal("ext-0", "A", true));

            var ex = assertThrows(IllegalStateException.class,
                    () -> IdentitySynchronization.plan(truncated, known));
            assertTrue(ex.getMessage().contains("nobody can log in to fix it"),
                    "acting on a truncated page deactivates the tenant's principals — an outage the platform "
                            + "inflicts on itself, at the moment nobody can log in to fix it");
        }

        @Test
        @DisplayName("a new directory principal is created and an existing one is updated")
        void createAndUpdate() {
            var known = Map.of("ext-1", new UUID(203, 1));
            var directory = List.of(
                    new IdentitySynchronization.DirectoryPrincipal("ext-1", "Renamed", true),
                    new IdentitySynchronization.DirectoryPrincipal("ext-2", "New", true));

            var plan = IdentitySynchronization.plan(directory, known);
            assertEquals(1, plan.create().size());
            assertEquals(1, plan.updateDisplayName().size());
            assertTrue(plan.deactivate().isEmpty());
        }
    }
}
