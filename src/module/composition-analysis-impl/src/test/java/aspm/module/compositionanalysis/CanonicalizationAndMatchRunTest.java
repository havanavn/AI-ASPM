package aspm.module.compositionanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.compositionanalysis.domain.ClosureAuthority;
import aspm.module.compositionanalysis.domain.ComponentIdentity;
import aspm.module.compositionanalysis.domain.Ecosystem;
import aspm.module.compositionanalysis.domain.MatchRun;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Canonicalization, tenant-scoped interning, and the match run machine. {@code INV-SBM-01} to {@code -12}. */
class CanonicalizationAndMatchRunTest {

    private static final Instant T0 = Instant.parse("2026-08-05T09:00:00Z");
    private static final UUID SNAPSHOT = new UUID(121, 1);
    private static final UUID WORKER = new UUID(121, 2);
    private static final UUID OTHER_WORKER = new UUID(121, 3);
    private static final Duration LEASE = Duration.ofMinutes(15);

    private static MatchRun.IdempotencyKey key(String contentHash) {
        return new MatchRun.IdempotencyKey(contentHash, "intel-2026-08-05", 1,
                ComponentIdentity.CANONICALIZATION_VERSION);
    }

    private static MatchRun queued(MatchRun.QueueClass queueClass) {
        return new MatchRun(UUID.randomUUID(), SNAPSHOT, queueClass, key("sha256:abc"), T0);
    }

    private static ComponentIdentity identityOf(String purl) {
        return ComponentIdentity.canonicalize(purl).identity().orElseThrow();
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-SBM-036 / -037 — canonicalization")
    class Canonicalization {

        @Test
        @DisplayName("case-insensitive registries fold; case-sensitive ones do not")
        void caseFoldingIsPerEcosystem() {
            assertEquals(identityOf("pkg:npm/Express@4.18.2").name(),
                    identityOf("pkg:npm/express@4.18.2").name(),
                    "the same package under two spellings becomes two components, one of which matches "
                            + "intelligence and one of which does not (DOC-22 section 6.2)");

            assertNotEquals(identityOf("pkg:maven/org.example/MyLib@1.0").name(),
                    identityOf("pkg:maven/org.example/mylib@1.0").name(),
                    "Maven coordinates are case-sensitive; folding them would MERGE two distinct artifacts, "
                            + "producing false positives on both");
        }

        @Test
        @DisplayName("PyPI treats separator characters as equivalent")
        void pypiSeparatorsNormalize() {
            assertEquals(identityOf("pkg:pypi/my_package@1.0").name(),
                    identityOf("pkg:pypi/my-package@1.0").name());
            assertEquals(identityOf("pkg:pypi/my.package@1.0").name(),
                    identityOf("pkg:pypi/my-package@1.0").name());
        }

        @Test
        @DisplayName("the namespace is preserved, so two packages of the same name do not merge")
        void namespacePreserved() {
            var first = identityOf("pkg:maven/org.apache/commons@1.0");
            var second = identityOf("pkg:maven/com.example/commons@1.0");
            assertEquals(first.name(), second.name());
            assertNotEquals(first.namespace(), second.namespace(),
                    "dropping the namespace merges two packages of the same name in different namespaces "
                            + "into one, producing false positives on both");
            assertNotEquals(first.tenantScopedKey(new UUID(9, 9)), second.tenantScopedKey(new UUID(9, 9)));
        }

        @Test
        @DisplayName("distribution qualifiers are retained")
        void qualifiersRetained() {
            var debian = identityOf("pkg:deb/openssl@3.0.11-1?distro=debian-12");
            var ubuntu = identityOf("pkg:deb/openssl@3.0.11-1?distro=ubuntu-22.04");
            assertNotEquals(debian.qualifiers(), ubuntu.qualifiers(),
                    "a package version patched by one distribution and not another cannot otherwise be "
                            + "distinguished — the backport case arriving through identity rather than through "
                            + "version comparison");
        }

        @Test
        @DisplayName("PRD-SBM-037: an unmatchable identifier is recorded with a reason, never skipped")
        void unmatchableIsRecordedWithAReason() {
            record Case(String purl, ComponentIdentity.UnmatchableReason reason) {
            }
            var cases = java.util.List.of(
                    new Case("not-a-purl", ComponentIdentity.UnmatchableReason.NOT_A_PACKAGE_URL),
                    new Case("pkg:some-new-registry/thing@1.0",
                            ComponentIdentity.UnmatchableReason.UNKNOWN_ECOSYSTEM),
                    new Case("pkg:npm/express", ComponentIdentity.UnmatchableReason.MISSING_VERSION),
                    new Case("pkg:npm", ComponentIdentity.UnmatchableReason.MISSING_NAME));

            for (var testCase : cases) {
                var result = ComponentIdentity.canonicalize(testCase.purl());
                assertFalse(result.matchable(), testCase.purl() + " must not canonicalize");
                assertEquals(testCase.reason(), result.unmatchableReason().orElseThrow(),
                        "silent skipping is the mechanism by which a partially matched SBOM appears fully "
                                + "matched (PRD-SBM-037)");
                assertEquals(testCase.purl(), result.rawIdentifier(),
                        "the raw identifier is retained so the unmatchable-component list is actionable");
            }
        }

        @Test
        @DisplayName("canonicalization never throws, so one bad component does not lose the snapshot")
        void badInputIsDataNotAnError() {
            assertFalse(ComponentIdentity.canonicalize(null).matchable());
            assertFalse(ComponentIdentity.canonicalize("").matchable());
            assertFalse(ComponentIdentity.canonicalize("pkg:npm/@1.0").matchable());
        }

        @Test
        @DisplayName("a canonicalization is either matched or unmatchable, never both or neither")
        void outcomeIsExclusive() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ComponentIdentity.Canonicalization(Optional.empty(), Optional.empty(), "x"),
                    "neither would be a silent skip");
            assertThrows(IllegalArgumentException.class,
                    () -> new ComponentIdentity.Canonicalization(
                            Optional.of(identityOf("pkg:npm/express@4.18.2")),
                            Optional.of(ComponentIdentity.UnmatchableReason.MISSING_NAME), "x"),
                    "both would be a component simultaneously matched and not");
        }

        @Test
        @DisplayName("a component without a concrete version cannot be constructed")
        void versionIsRequired() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> new ComponentIdentity(Ecosystem.SEMVER, Optional.empty(), "express", "  ",
                            Optional.empty()));
            assertTrue(ex.getMessage().contains("indistinguishable from a clean application"),
                    "the matcher finds nothing because there is nothing matchable — a false negative "
                            + "presenting as good news (INV-SBM-03)");
        }

        @Test
        @DisplayName("ADR-032: the intern key is tenant-scoped, and there is no global variant")
        void internKeyIsTenantScoped() {
            var identity = identityOf("pkg:npm/express@4.18.2");
            var tenantA = new UUID(1, 1);
            var tenantB = new UUID(2, 2);
            assertNotEquals(identity.tenantScopedKey(tenantA), identity.tenantScopedKey(tenantB),
                    "a shared intern table makes 'does any tenant use this component' answerable and makes "
                            + "the arrival of a new row an observable event — a cross-tenant inference channel "
                            + "built out of what looks like a lookup table (ADR-032)");

            for (Method m : ComponentIdentity.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("globalkey") || name.equals("internkey"),
                        "found " + m.getName() + "; a key without a tenant is the global intern ADR-032 "
                                + "rejects");
            }
            assertThrows(NullPointerException.class, () -> identity.tenantScopedKey(null));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-SBM-07 to -12 — the match run machine")
    class RunMachine {

        @Test
        @DisplayName("INV-SBM-11: INTERACTIVE precedes both batch classes, and KEV precedes ordinary batch")
        void queuePrecedenceIsTotal() {
            assertTrue(MatchRun.QueueClass.INTERACTIVE.precedence()
                    < MatchRun.QueueClass.BATCH_ELEVATED.precedence());
            assertTrue(MatchRun.QueueClass.BATCH_ELEVATED.precedence()
                            < MatchRun.QueueClass.BATCH.precedence(),
                    "a KEV update queued behind a full portfolio sweep would exceed NFR-SBM-003's six-hour "
                            + "visibility budget (PRD-SBM-046)");

            var seen = new java.util.HashSet<Integer>();
            for (var queueClass : MatchRun.QueueClass.values()) {
                assertTrue(seen.add(queueClass.precedence()),
                        "two classes sharing a precedence make the ordering non-total, and INV-SBM-11 becomes "
                                + "a coin toss");
            }
        }

        @Test
        @DisplayName("INV-SBM-12: a run cannot exist without its intelligence and matcher versions")
        void versionsAreRecorded() {
            assertThrows(NullPointerException.class,
                    () -> new MatchRun.IdempotencyKey("sha256:abc", null, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new MatchRun.IdempotencyKey("sha256:abc", "intel-1", 0, 1),
                    "without them a change in results cannot be distinguished from a change in the estate");
        }

        @Test
        @DisplayName("INV-SBM-07: runs with the same key over the same snapshot are duplicates")
        void idempotencyOnTheFullKey() {
            var first = queued(MatchRun.QueueClass.INTERACTIVE);
            var same = new MatchRun(UUID.randomUUID(), SNAPSHOT, MatchRun.QueueClass.BATCH,
                    key("sha256:abc"), T0.plusSeconds(60));
            assertTrue(first.duplicates(same),
                    "the queue class is not part of identity: the same snapshot matched against the same "
                            + "intelligence produces the same candidates however it was triggered");

            var newIntelligence = new MatchRun(UUID.randomUUID(), SNAPSHOT, MatchRun.QueueClass.BATCH,
                    new MatchRun.IdempotencyKey("sha256:abc", "intel-2026-08-06", 1, 1), T0);
            assertFalse(first.duplicates(newIntelligence),
                    "new intelligence over the same snapshot is exactly the re-match PRD-SBM-044 requires");
        }

        @Test
        @DisplayName("INV-SBM-10: a lease expires on the clock and is reclaimable")
        void leaseExpiresAndIsReclaimed() {
            var run = queued(MatchRun.QueueClass.BATCH);
            run.acquireLease(WORKER, LEASE, T0);
            run.start(T0);

            assertFalse(run.leaseExpired(T0.plusSeconds(60)));
            assertTrue(run.leaseExpired(T0.plus(LEASE).plusSeconds(1)),
                    "computed from the clock, not from a flag — the failure this invariant addresses is "
                            + "precisely a process that stopped running");

            run.reclaim(OTHER_WORKER, LEASE, T0.plus(LEASE).plusSeconds(1));
            assertEquals(Optional.of(OTHER_WORKER), run.leaseHolderId());
            assertEquals(2, run.attemptCount());
        }

        @Test
        @DisplayName("a live lease cannot be reclaimed")
        void liveLeaseNotReclaimable() {
            var run = queued(MatchRun.QueueClass.BATCH);
            run.acquireLease(WORKER, LEASE, T0);
            var ex = assertThrows(IllegalStateException.class,
                    () -> run.reclaim(OTHER_WORKER, LEASE, T0.plusSeconds(60)));
            assertTrue(ex.getMessage().contains("same snapshot twice concurrently"));
        }

        @Test
        @DisplayName("an unbounded lease cannot be acquired")
        void leaseMustBeBounded() {
            var run = queued(MatchRun.QueueClass.BATCH);
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> run.acquireLease(WORKER, Duration.ZERO, T0));
            assertTrue(ex.getMessage().contains("SILENTLY"),
                    "no error, no alert, and a coverage timeline that stops advancing — which resembles a "
                            + "stable estate");
        }

        @Test
        @DisplayName("INV-SBM-09: coverage confirmation is derived from how the run ended")
        void coverageConfirmationIsDerived() {
            var run = queued(MatchRun.QueueClass.INTERACTIVE);
            run.acquireLease(WORKER, LEASE, T0);
            run.start(T0);
            assertFalse(run.coverageConfirmed(), "an in-flight run has confirmed nothing");

            run.complete(true, Set.of(Ecosystem.SEMVER), T0.plusSeconds(60));
            assertTrue(run.coverageConfirmed());

            for (Method m : MatchRun.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("set") && name.contains("coverage"),
                        "a settable coverage_confirmed would defeat the closure guard entirely");
            }
        }

        @Test
        @DisplayName("a failed run reports unconfirmed coverage and refuses closure")
        void failedRunConfirmsNothing() {
            var run = queued(MatchRun.QueueClass.BATCH);
            run.acquireLease(WORKER, LEASE, T0);
            run.start(T0);
            run.fail("intelligence store unreachable", T0.plusSeconds(60));

            assertFalse(run.coverageConfirmed());
            assertEquals(ClosureAuthority.RunOutcome.FAILED, run.closureOutcome(T0.plusSeconds(61)));
        }

        @Test
        @DisplayName("a failure without a reason cannot be recorded")
        void failureNeedsAReason() {
            var run = queued(MatchRun.QueueClass.BATCH);
            run.acquireLease(WORKER, LEASE, T0);
            run.start(T0);
            assertThrows(IllegalArgumentException.class, () -> run.fail("  ", T0.plusSeconds(60)),
                    "the batch it belongs to would report a count with no cause");
        }

        @Test
        @DisplayName("PRD-SBM-050: a skip is recorded as a run and confirms no coverage")
        void skipIsRecorded() {
            var run = queued(MatchRun.QueueClass.BATCH);
            run.skipNoChange(T0.plusSeconds(60));

            assertEquals(MatchRun.State.SKIPPED_NO_CHANGE, run.state());
            assertTrue(run.finishedAt().isPresent(),
                    "skipping without recording produces the false signal section 9 exists to prevent — an "
                            + "asset that was correctly evaluated appears unevaluated");
            assertFalse(run.coverageConfirmed());
            assertEquals(ClosureAuthority.RunOutcome.SKIPPED_NO_CHANGE,
                    run.closureOutcome(T0.plusSeconds(61)));
        }

        @Test
        @DisplayName("an in-flight or lapsed run maps onto an outcome that closes nothing")
        void inFlightAndLapsedRunsCloseNothing() {
            var inFlight = queued(MatchRun.QueueClass.BATCH);
            assertEquals(ClosureAuthority.RunOutcome.CANCELLED, inFlight.closureOutcome(T0),
                    "a run still in flight has produced no evidence of anything");

            var lapsed = queued(MatchRun.QueueClass.BATCH);
            lapsed.acquireLease(WORKER, LEASE, T0);
            lapsed.start(T0);
            assertEquals(ClosureAuthority.RunOutcome.LEASE_EXPIRED,
                    lapsed.closureOutcome(T0.plus(LEASE).plusSeconds(1)),
                    "the lease check precedes the state check, so a RUNNING run whose worker died does not "
                            + "report as merely running");

            for (var run : java.util.List.of(inFlight, lapsed)) {
                var context = new ClosureAuthority.RunContext(run.closureOutcome(T0.plus(LEASE).plusSeconds(1)),
                        run.coverageConfirmed(), Duration.ofHours(1), Duration.ofDays(2),
                        ClosureAuthority.SnapshotQuality.ABOVE_WARNING, run.coveredEcosystems(),
                        T0.plus(LEASE).plusSeconds(1));
                assertFalse(ClosureAuthority.authorize(context).mayDriveClosure());
            }
        }

        @Test
        @DisplayName("a completed run carries its covered ecosystems into the closure decision")
        void coveredEcosystemsReachTheGate() {
            var run = queued(MatchRun.QueueClass.INTERACTIVE);
            run.acquireLease(WORKER, LEASE, T0);
            run.start(T0);
            run.complete(true, Set.of(Ecosystem.MAVEN), T0.plusSeconds(60));

            var context = new ClosureAuthority.RunContext(run.closureOutcome(T0.plusSeconds(61)),
                    run.coverageConfirmed(), Duration.ofHours(1), Duration.ofDays(2),
                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING, run.coveredEcosystems(),
                    T0.plusSeconds(61));
            var decision = ClosureAuthority.authorize(context);

            assertTrue(ClosureAuthority.mayCloseComponent(decision, context, Ecosystem.MAVEN));
            assertFalse(ClosureAuthority.mayCloseComponent(decision, context, Ecosystem.PYPI),
                    "the run's covered set is what PRD-SBM-055 reads; losing it between the run and the gate "
                            + "would reopen the partial-submission hole");
        }
    }
}
