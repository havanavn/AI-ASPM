package aspm.module.compositionanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.compositionanalysis.domain.ClosureAuthority;
import aspm.module.compositionanalysis.domain.Ecosystem;
import aspm.module.compositionanalysis.domain.SbomCoverageState;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Closure guards and coverage governance. {@code PRD-SBM-053} to {@code -060}, {@code PRD-SBM-065}, and
 * {@code TST-SBM-002}.
 */
class ClosureAndCoverageTest {

    private static final Instant NOW = Instant.parse("2026-08-05T09:00:00Z");
    private static final UUID ASSET = new UUID(120, 1);
    private static final UUID OWNER = new UUID(120, 2);
    private static final Duration SEVEN_DAYS = Duration.ofDays(7);
    private static final Duration FRESH_INTELLIGENCE = Duration.ofHours(6);
    private static final Duration INTELLIGENCE_THRESHOLD = Duration.ofDays(2);

    /** A run that satisfies every precondition, covering two ecosystems. */
    private static ClosureAuthority.RunContext healthyRun() {
        return new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.COMPLETED, true,
                FRESH_INTELLIGENCE, INTELLIGENCE_THRESHOLD,
                ClosureAuthority.SnapshotQuality.ABOVE_WARNING,
                Set.of(Ecosystem.SEMVER, Ecosystem.MAVEN), NOW);
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-SBM-053 — the four run-level preconditions")
    class RunLevelGuards {

        @Test
        @DisplayName("a healthy run may drive closure")
        void healthyRunMayClose() {
            var decision = ClosureAuthority.authorize(healthyRun());
            assertTrue(decision.mayDriveClosure(), decision.explanation());
            assertTrue(decision.refusals().isEmpty());
        }

        @Test
        @DisplayName("every non-COMPLETED outcome refuses closure, including SKIPPED_NO_CHANGE")
        void everyFailureOutcomeRefuses() {
            for (var outcome : ClosureAuthority.RunOutcome.values()) {
                var context = new ClosureAuthority.RunContext(outcome, true, FRESH_INTELLIGENCE,
                        INTELLIGENCE_THRESHOLD, ClosureAuthority.SnapshotQuality.ABOVE_WARNING,
                        Set.of(Ecosystem.SEMVER), NOW);
                var decision = ClosureAuthority.authorize(context);
                if (outcome == ClosureAuthority.RunOutcome.COMPLETED) {
                    assertTrue(decision.mayDriveClosure());
                } else {
                    assertFalse(decision.mayDriveClosure(),
                            outcome + " must not drive closure. A failed run returns no components, and under "
                                    + "naive logic one failure auto-closes every dependency finding for the "
                                    + "project — which has happened in production tooling (PRD-SBM-053).");
                }
            }
        }

        @Test
        @DisplayName("SKIPPED_NO_CHANGE is recorded as a run and still closes nothing")
        void skippedRunClosesNothing() {
            var skipped = new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.SKIPPED_NO_CHANGE, true,
                    FRESH_INTELLIGENCE, INTELLIGENCE_THRESHOLD,
                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING, Set.of(Ecosystem.SEMVER), NOW);
            assertFalse(ClosureAuthority.authorize(skipped).mayDriveClosure(),
                    "no components were evaluated, so absence proves nothing — even though the skip is "
                            + "legitimately recorded as a run so the coverage timeline shows no gap "
                            + "(PRD-SBM-050)");
        }

        @Test
        @DisplayName("unconfirmed coverage refuses, even on a completed run")
        void unconfirmedCoverageRefuses() {
            var context = new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.COMPLETED, false,
                    FRESH_INTELLIGENCE, INTELLIGENCE_THRESHOLD,
                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING, Set.of(Ecosystem.SEMVER), NOW);
            var decision = ClosureAuthority.authorize(context);
            assertFalse(decision.mayDriveClosure());
            assertTrue(decision.explanation().contains("PP-1"),
                    "a run can complete having read a snapshot it did not fully enumerate");
        }

        @Test
        @DisplayName("stale intelligence refuses closure while still permitting matching")
        void staleIntelligenceRefusesClosure() {
            var context = new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.COMPLETED, true,
                    Duration.ofDays(180), INTELLIGENCE_THRESHOLD,
                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING, Set.of(Ecosystem.SEMVER), NOW);
            var decision = ClosureAuthority.authorize(context);
            assertFalse(decision.mayDriveClosure());
            assertTrue(decision.explanation().contains("cannot establish that one is gone"),
                    "stale intelligence can still FIND a vulnerability, which is why matching continues "
                            + "(PRD-SBM-063); the asymmetry is the point");
        }

        @Test
        @DisplayName("a snapshot at or below the quality warning refuses")
        void lowQualitySnapshotRefuses() {
            for (var quality : ClosureAuthority.SnapshotQuality.values()) {
                var context = new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.COMPLETED, true,
                        FRESH_INTELLIGENCE, INTELLIGENCE_THRESHOLD, quality, Set.of(Ecosystem.SEMVER), NOW);
                assertEquals(quality == ClosureAuthority.SnapshotQuality.ABOVE_WARNING,
                        ClosureAuthority.authorize(context).mayDriveClosure(),
                        quality + ": a low-quality snapshot under-reports components, and every "
                                + "under-reported component looks like a removed one");
            }
        }

        @Test
        @DisplayName("a refusal names every unmet precondition, not the first")
        void refusalNamesEveryGap() {
            var broken = new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.TIMED_OUT, false,
                    Duration.ofDays(180), INTELLIGENCE_THRESHOLD,
                    ClosureAuthority.SnapshotQuality.AT_OR_BELOW_WARNING, Set.of(), NOW);
            assertEquals(4, ClosureAuthority.authorize(broken).refusals().size(),
                    "the operator asking why nothing closed is usually asking about a coverage gap they can "
                            + "fix, and one at a time is four round trips");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-SBM-055 — the most subtle closure error in the module")
    class EcosystemAwareAbsence {

        @Test
        @DisplayName("a component of an uncovered ecosystem is not closed, on a perfectly healthy run")
        void uncoveredEcosystemIsNotClosed() {
            var context = healthyRun();
            var decision = ClosureAuthority.authorize(context);
            assertTrue(decision.mayDriveClosure(), "every run-level precondition passes");

            assertTrue(ClosureAuthority.mayCloseComponent(decision, context, Ecosystem.SEMVER));
            assertFalse(ClosureAuthority.mayCloseComponent(decision, context, Ecosystem.PYPI),
                    "a team splits its pipeline and one job submits an SBOM covering only its own ecosystem. "
                            + "Every finding in the other ecosystems appears remediated. Nothing failed, the "
                            + "run completed, quality is high — and the closure is wrong for hundreds of "
                            + "findings (PRD-SBM-055).");
        }

        @Test
        @DisplayName("the refusal explains that absence means not-looked-for, not removed")
        void refusalExplainsEcosystemAbsence() {
            var context = healthyRun();
            var decision = ClosureAuthority.authorize(context);
            String reason = ClosureAuthority.refusalFor(decision, context, Ecosystem.PYPI);

            assertTrue(reason.contains("not looked for"),
                    "ecosystem-aware absence is the only safe interpretation; got " + reason);
            assertTrue(reason.contains("SEMVER") || reason.contains("MAVEN"),
                    "and the covered set is named so the operator can see what the submission actually did");
        }

        @Test
        @DisplayName("the per-component gate also refuses when the run itself refused")
        void componentGateRespectsTheRunGate() {
            var failed = new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.FAILED, true,
                    FRESH_INTELLIGENCE, INTELLIGENCE_THRESHOLD,
                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING, Set.of(Ecosystem.SEMVER), NOW);
            var decision = ClosureAuthority.authorize(failed);
            assertFalse(ClosureAuthority.mayCloseComponent(decision, failed, Ecosystem.SEMVER),
                    "an ecosystem being covered does not rescue a run that failed");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("TST-SBM-002 — no failure mode produces closure or a favourable change")
    class NoFailureImprovesPosture {

        /**
         * Every failure mode of DOC-22 section 11 that reaches a match run, expressed as a run context.
         *
         * <p>{@code PRD-SBM-065} is the module-wide invariant: "No failure in this module MUST result in
         * finding closure, coverage improvement, or a favourable posture change." DOC-16 names this the
         * property a reviewer should test the module against, so it is one test over the whole table rather
         * than one assertion buried in each case.
         */
        @Test
        @DisplayName("every DOC-22 section 11 failure mode refuses closure")
        void everyFailureModeRefusesClosure() {
            record FailureMode(String name, ClosureAuthority.RunContext context) {
            }

            var modes = java.util.List.of(
                    new FailureMode("match worker terminated mid-run — lease expired",
                            new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.LEASE_EXPIRED, true,
                                    FRESH_INTELLIGENCE, INTELLIGENCE_THRESHOLD,
                                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING,
                                    Set.of(Ecosystem.SEMVER), NOW)),
                    new FailureMode("match run exceeds timeout",
                            new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.TIMED_OUT, true,
                                    FRESH_INTELLIGENCE, INTELLIGENCE_THRESHOLD,
                                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING,
                                    Set.of(Ecosystem.SEMVER), NOW)),
                    new FailureMode("intelligence unavailable — matching continues against last verified",
                            new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.COMPLETED, true,
                                    Duration.ofDays(200), INTELLIGENCE_THRESHOLD,
                                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING,
                                    Set.of(Ecosystem.SEMVER), NOW)),
                    new FailureMode("submission rejected on quality",
                            new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.COMPLETED, true,
                                    FRESH_INTELLIGENCE, INTELLIGENCE_THRESHOLD,
                                    ClosureAuthority.SnapshotQuality.REJECTED,
                                    Set.of(Ecosystem.SEMVER), NOW)),
                    new FailureMode("snapshot below the quality warning threshold",
                            new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.COMPLETED, true,
                                    FRESH_INTELLIGENCE, INTELLIGENCE_THRESHOLD,
                                    ClosureAuthority.SnapshotQuality.AT_OR_BELOW_WARNING,
                                    Set.of(Ecosystem.SEMVER), NOW)),
                    new FailureMode("coverage not confirmed",
                            new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.COMPLETED, false,
                                    FRESH_INTELLIGENCE, INTELLIGENCE_THRESHOLD,
                                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING,
                                    Set.of(Ecosystem.SEMVER), NOW)),
                    new FailureMode("run cancelled",
                            new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.CANCELLED, true,
                                    FRESH_INTELLIGENCE, INTELLIGENCE_THRESHOLD,
                                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING,
                                    Set.of(Ecosystem.SEMVER), NOW)));

            for (var mode : modes) {
                var decision = ClosureAuthority.authorize(mode.context());
                assertFalse(decision.mayDriveClosure(),
                        "'" + mode.name() + "' drove closure. Every failure mode must degrade toward LESS "
                                + "confidence, never more (PRD-SBM-065).");
                for (Ecosystem ecosystem : Ecosystem.values()) {
                    assertFalse(ClosureAuthority.mayCloseComponent(decision, mode.context(), ecosystem),
                            "'" + mode.name() + "' closed a " + ecosystem + " component");
                }
            }
        }

        @Test
        @DisplayName("partial ecosystem submission is a failure mode too, and it is the quiet one")
        void partialSubmissionClosesNothingOutsideItsEcosystems() {
            var partial = new ClosureAuthority.RunContext(ClosureAuthority.RunOutcome.COMPLETED, true,
                    FRESH_INTELLIGENCE, INTELLIGENCE_THRESHOLD,
                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING, Set.of(Ecosystem.MAVEN), NOW);
            var decision = ClosureAuthority.authorize(partial);

            assertTrue(decision.mayDriveClosure(),
                    "this failure mode does not look like a failure at the run level — which is why it needs "
                            + "its own gate");
            for (Ecosystem ecosystem : Ecosystem.values()) {
                if (ecosystem != Ecosystem.MAVEN) {
                    assertFalse(ClosureAuthority.mayCloseComponent(decision, partial, ecosystem));
                }
            }
        }

        @Test
        @DisplayName("there is no method that grants closure without a Decision")
        void closureCannotBeGrantedWithoutADecision() {
            for (Method m : ClosureAuthority.class.getMethods()) {
                if (m.getDeclaringClass() == Object.class || !java.lang.reflect.Modifier.isStatic(
                        m.getModifiers())) {
                    continue;
                }
                if (m.getReturnType() == boolean.class) {
                    boolean takesDecision = false;
                    for (Class<?> parameter : m.getParameterTypes()) {
                        takesDecision |= parameter == ClosureAuthority.Decision.class;
                    }
                    assertTrue(takesDecision,
                            m.getName() + " returns a closure verdict without requiring a Decision. The four "
                                    + "run preconditions and the per-component one must be obtained together, "
                                    + "or the fifth is the one that gets forgotten.");
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-SBM-056 — the single most important requirement in the module")
    class CoverageGovernance {

        @Test
        @DisplayName("an asset with no snapshot has an explicit state, not an absence")
        void neverSubmittedIsAState() {
            var state = SbomCoverageState.neverSubmitted(ASSET, Set.of(Ecosystem.SEMVER), SEVEN_DAYS, OWNER);
            assertEquals(SbomCoverageState.Status.NEVER_SUBMITTED, state.statusAt(NOW),
                    "a project that has never submitted is not low-risk; it is unmeasured, and without an "
                            + "explicit state it is absent from reporting entirely — where absence reads as "
                            + "absence of problems");
            assertTrue(state.statusAt(NOW).requiresAction());
            assertFalse(state.statusAt(NOW).presentableWithoutQualification());
            assertTrue(state.qualifier(NOW).contains("unmeasured, not clean"));
        }

        @Test
        @DisplayName("a coverage state always names an accountable owner")
        void coverageGapsHaveAnOwner() {
            assertThrows(NullPointerException.class,
                    () -> SbomCoverageState.neverSubmitted(ASSET, Set.of(), SEVEN_DAYS, null),
                    "coverage gaps close only when somebody is accountable, and an ownerless gap sits in a "
                            + "queue nobody reads (PRD-SBM-058)");
        }

        @Test
        @DisplayName("staleness is decided before quality, so a stale perfect snapshot is STALE")
        void stalenessTakesPrecedence() {
            var state = SbomCoverageState.of(ASSET, UUID.randomUUID(), NOW.minus(Duration.ofDays(30)),
                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING, Set.of(Ecosystem.SEMVER),
                    Set.of(Ecosystem.SEMVER), SEVEN_DAYS, OWNER);
            assertEquals(SbomCoverageState.Status.STALE, state.statusAt(NOW),
                    "reporting it PARTIAL would understate the gap");
        }

        @Test
        @DisplayName("incomplete ecosystem coverage is PARTIAL even at perfect quality and freshness")
        void incompleteEcosystemsArePartial() {
            var state = SbomCoverageState.of(ASSET, UUID.randomUUID(), NOW.minus(Duration.ofDays(1)),
                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING, Set.of(Ecosystem.MAVEN),
                    Set.of(Ecosystem.MAVEN, Ecosystem.SEMVER), SEVEN_DAYS, OWNER);
            assertEquals(SbomCoverageState.Status.PARTIAL, state.statusAt(NOW));
            assertEquals(Set.of(Ecosystem.SEMVER), state.uncoveredEcosystems());
            assertTrue(state.qualifier(NOW).contains("not covered"));
        }

        @Test
        @DisplayName("PRD-SBM-060: coverage cannot be improved by removing an uncovered ecosystem")
        void coverageNotImprovableByExclusion() {
            var partial = SbomCoverageState.of(ASSET, UUID.randomUUID(), NOW.minus(Duration.ofDays(1)),
                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING, Set.of(Ecosystem.MAVEN),
                    Set.of(Ecosystem.MAVEN, Ecosystem.SEMVER), SEVEN_DAYS, OWNER);

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> partial.withEcosystemRemovedFromDeclaredStack(Ecosystem.SEMVER));
            assertTrue(ex.getMessage().contains("PRD-SBM-060"),
                    "the cheapest route to high coverage must not be exclusion, or the metric inverts");

            // Removing an ecosystem that WAS covered is permitted: it lowers both sides honestly.
            var reduced = partial.withEcosystemRemovedFromDeclaredStack(Ecosystem.MAVEN);
            assertEquals(Set.of(Ecosystem.SEMVER), reduced.declaredStackEcosystems());
        }

        @Test
        @DisplayName("PRD-SBM-057: every figure carries its coverage and freshness")
        void figuresCarryTheirQualifier() {
            var state = SbomCoverageState.of(ASSET, UUID.randomUUID(), NOW.minus(Duration.ofDays(3)),
                    ClosureAuthority.SnapshotQuality.ABOVE_WARNING, Set.of(Ecosystem.SEMVER),
                    Set.of(Ecosystem.SEMVER), SEVEN_DAYS, OWNER);
            String qualifier = state.qualifier(NOW);
            assertTrue(qualifier.contains("3 day(s) old"),
                    "'twelve critical, from data three days old, covering seventy percent of the portfolio' "
                            + "is materially different from 'twelve critical', and only the first is honest");
            assertTrue(qualifier.contains("1 of 1 declared ecosystem(s)"));
        }

        @Test
        @DisplayName("the status is derived, with no setter")
        void statusIsDerived() {
            for (Method m : SbomCoverageState.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("set"),
                        "found " + m.getName() + "; a settable coverage status is one somebody sets to CURRENT");
            }
        }

        @Test
        @DisplayName("a snapshot reference and its timestamp travel together")
        void snapshotReferenceIsComplete() {
            assertThrows(IllegalArgumentException.class,
                    () -> SbomCoverageState.of(ASSET, UUID.randomUUID(), null,
                            ClosureAuthority.SnapshotQuality.ABOVE_WARNING, Set.of(), Set.of(), SEVEN_DAYS,
                            OWNER),
                    "a snapshot whose age is unknown cannot be tested against a freshness threshold, and "
                            + "would silently read as CURRENT");
        }
    }
}
