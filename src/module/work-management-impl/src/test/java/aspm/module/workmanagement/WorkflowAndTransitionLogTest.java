package aspm.module.workmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.kernel.rulesengine.contract.Condition;
import aspm.kernel.rulesengine.contract.ConditionEvaluation;
import aspm.kernel.rulesengine.contract.FactSet;
import aspm.kernel.rulesengine.contract.FactValue;
import aspm.kernel.rulesengine.contract.Operator;
import aspm.kernel.rulesengine.contract.RuleOutcome;
import aspm.module.workmanagement.domain.ActorType;
import aspm.module.workmanagement.domain.TransitionBlockingAttribution;
import aspm.module.workmanagement.domain.TransitionEvaluation;
import aspm.module.workmanagement.domain.TransitionLog;
import aspm.module.workmanagement.domain.WorkItemStateTransition;
import aspm.module.workmanagement.domain.WorkflowDefinition;
import aspm.module.workmanagement.domain.WorkflowState;
import aspm.module.workmanagement.domain.WorkflowStateCategory;
import aspm.module.workmanagement.domain.WorkflowTransition;
import aspm.module.workmanagement.domain.WorkflowValidation;
import java.lang.reflect.Method;
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

/**
 * Prompt 9 session 1 — workflow as data, the evaluation order of DOC-09 section 2.1, and the append-only
 * transition log.
 */
class WorkflowAndTransitionLogTest {

    private static final Instant T0 = Instant.parse("2026-07-01T09:00:00Z");
    private static final UUID ITEM = new UUID(90, 1);
    private static final UUID TYPE = new UUID(90, 2);

    private static final UUID OPEN = new UUID(91, 1);
    private static final UUID IN_PROGRESS = new UUID(91, 2);
    private static final UUID BLOCKED = new UUID(91, 3);
    private static final UUID DONE = new UUID(91, 4);
    private static final UUID REJECTED = new UUID(91, 5);

    /**
     * A stand-in for the shared evaluator.
     *
     * <p>Not the real one: the build refuses to put {@code rules-engine-impl} on this module's classpath, which
     * is the boundary of ADR-003 doing its job — and a test that reached around it would be asserting against a
     * dependency the production code cannot have. What matters here is that this module <b>consumes</b> the port
     * rather than carrying an evaluator of its own; that there is exactly one implementation of the port in the
     * build is asserted in {@code architecture-tests}, where it can see every module at once.
     *
     * <p>Three-valued, because a double that collapsed UNDEFINED to false would make the guard tests below pass
     * against behaviour the real evaluator does not have.
     */
    private static final ConditionEvaluation EVALUATION = (condition, facts) -> {
        if (!(condition instanceof Condition.Comparison c)) {
            throw new UnsupportedOperationException("the double covers only the comparisons these tests use");
        }
        if (!facts.isPresent(c.factKey())) {
            return RuleOutcome.UNDEFINED;
        }
        return RuleOutcome.of(facts.get(c.factKey()).equals(c.operand()));
    };

    private static WorkflowState state(UUID id, String code, WorkflowStateCategory category, boolean running) {
        return new WorkflowState(id, code, category, running, 1);
    }

    private static WorkflowTransition transition(UUID from, UUID to, String event) {
        return new WorkflowTransition(UUID.randomUUID(), from, to, event, Optional.empty(), List.of(),
                Optional.empty(), List.of(), false);
    }

    /** The remediation-obligation default of DOC-09 section 13, trimmed to what these tests exercise. */
    private static WorkflowDefinition remediationWorkflow() {
        return new WorkflowDefinition(UUID.randomUUID(), TYPE, 1, WorkflowDefinition.SubjectMachine.WORK_ITEM,
                OPEN,
                List.of(state(OPEN, "OPEN", WorkflowStateCategory.OPEN, true),
                        state(IN_PROGRESS, "IN_PROGRESS", WorkflowStateCategory.IN_PROGRESS, true),
                        // WAITING_EXTERNAL with the clock stopped — the category exists so blocking attribution
                        // is available out of the box (PRD-WRK-038).
                        state(BLOCKED, "BLOCKED", WorkflowStateCategory.WAITING_EXTERNAL, false),
                        state(DONE, "DONE", WorkflowStateCategory.TERMINAL, false),
                        state(REJECTED, "REJECTED", WorkflowStateCategory.TERMINAL, false)),
                List.of(transition(OPEN, IN_PROGRESS, "start"),
                        transition(IN_PROGRESS, BLOCKED, "block"),
                        transition(BLOCKED, IN_PROGRESS, "unblock"),
                        transition(IN_PROGRESS, DONE, "complete"),
                        new WorkflowTransition(UUID.randomUUID(), OPEN, REJECTED, "reject", Optional.empty(),
                                List.of(), Optional.empty(), List.of(), true)));
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-WRK-02 / PRD-WRK-034 — validation before activation")
    class Validation {

        @Test
        @DisplayName("a valid definition activates and records when it was validated")
        void validDefinitionActivates() {
            var definition = remediationWorkflow();
            assertTrue(definition.validate().activatable(), definition.validate().diagnosis());

            definition.activate(T0);
            assertEquals(WorkflowDefinition.State.ACTIVE, definition.state());
            assertTrue(definition.validatedAt().isPresent(),
                    "DOC-04 section 16.1 makes an unvalidated activation unrepresentable at the engine too, "
                            + "which needs the timestamp");
        }

        @Test
        @DisplayName("the trap: a non-terminal state with no outbound transition blocks activation")
        void deadEndStateBlocksActivation() {
            var definition = new WorkflowDefinition(UUID.randomUUID(), TYPE, 1,
                    WorkflowDefinition.SubjectMachine.WORK_ITEM, OPEN,
                    List.of(state(OPEN, "OPEN", WorkflowStateCategory.OPEN, true),
                            state(BLOCKED, "BLOCKED", WorkflowStateCategory.WAITING_EXTERNAL, false),
                            state(DONE, "DONE", WorkflowStateCategory.TERMINAL, false)),
                    List.of(transition(OPEN, BLOCKED, "block"), transition(OPEN, DONE, "complete")));

            var validation = definition.validate();
            assertFalse(validation.activatable());
            assertTrue(validation.findings().stream()
                            .anyMatch(f -> f.kind() == WorkflowValidation.Finding.Kind.DEAD_END_STATE
                                    && f.subject().equals("BLOCKED")),
                    "items enter and cannot leave, and the defect surfaces days later as stalled work with no "
                            + "visible cause (PRD-WRK-034)");
            assertThrows(IllegalStateException.class, () -> definition.activate(T0));
        }

        @Test
        @DisplayName("an unreachable state blocks activation and is named")
        void unreachableStateBlocksActivation() {
            var orphan = new UUID(91, 9);
            var definition = new WorkflowDefinition(UUID.randomUUID(), TYPE, 1,
                    WorkflowDefinition.SubjectMachine.WORK_ITEM, OPEN,
                    List.of(state(OPEN, "OPEN", WorkflowStateCategory.OPEN, true),
                            state(orphan, "ORPHAN", WorkflowStateCategory.IN_PROGRESS, true),
                            state(DONE, "DONE", WorkflowStateCategory.TERMINAL, false)),
                    List.of(transition(OPEN, DONE, "complete"), transition(orphan, DONE, "complete")));

            var validation = definition.validate();
            assertTrue(validation.diagnosis().contains("ORPHAN"),
                    "an activation refused with 'invalid' leaves an administrator editing a state machine by "
                            + "guesswork; got " + validation.diagnosis());
        }

        @Test
        @DisplayName("a definition with no terminal state blocks activation")
        void noTerminalStateBlocksActivation() {
            var definition = new WorkflowDefinition(UUID.randomUUID(), TYPE, 1,
                    WorkflowDefinition.SubjectMachine.WORK_ITEM, OPEN,
                    List.of(state(OPEN, "OPEN", WorkflowStateCategory.OPEN, true),
                            state(IN_PROGRESS, "IN_PROGRESS", WorkflowStateCategory.IN_PROGRESS, true)),
                    List.of(transition(OPEN, IN_PROGRESS, "start"), transition(IN_PROGRESS, OPEN, "reopen")));

            assertTrue(definition.validate().findings().stream()
                            .anyMatch(f -> f.kind() == WorkflowValidation.Finding.Kind.NO_TERMINAL_STATE),
                    "nothing can finish, and every completion metric reports zero forever");
        }

        @Test
        @DisplayName("a transition referencing a state outside the definition blocks activation")
        void danglingReferenceBlocksActivation() {
            var definition = new WorkflowDefinition(UUID.randomUUID(), TYPE, 1,
                    WorkflowDefinition.SubjectMachine.WORK_ITEM, OPEN,
                    List.of(state(OPEN, "OPEN", WorkflowStateCategory.OPEN, true),
                            state(DONE, "DONE", WorkflowStateCategory.TERMINAL, false)),
                    List.of(transition(OPEN, DONE, "complete"),
                            transition(OPEN, new UUID(91, 99), "escape")));

            assertTrue(definition.validate().findings().stream()
                            .anyMatch(f -> f.kind()
                                    == WorkflowValidation.Finding.Kind.DANGLING_STATE_REFERENCE),
                    "the failure would otherwise surface at transition time, on a real item, in front of a user");
        }

        @Test
        @DisplayName("two transitions on the same event from the same state are rejected")
        void ambiguousTransitionsBlockActivation() {
            var definition = new WorkflowDefinition(UUID.randomUUID(), TYPE, 1,
                    WorkflowDefinition.SubjectMachine.WORK_ITEM, OPEN,
                    List.of(state(OPEN, "OPEN", WorkflowStateCategory.OPEN, true),
                            state(DONE, "DONE", WorkflowStateCategory.TERMINAL, false),
                            state(REJECTED, "REJECTED", WorkflowStateCategory.TERMINAL, false)),
                    List.of(transition(OPEN, DONE, "finish"), transition(OPEN, REJECTED, "finish")));

            assertTrue(definition.validate().findings().stream()
                            .anyMatch(f -> f.kind() == WorkflowValidation.Finding.Kind.DUPLICATE_DEFINITION),
                    "which one fires would depend on ordering, and they may carry different guards and "
                            + "permissions");
        }

        @Test
        @DisplayName("DOC-09 section 3: a non-success terminal transition must require a reason")
        void nonSuccessTerminalRequiresReason() {
            var definition = new WorkflowDefinition(UUID.randomUUID(), TYPE, 1,
                    WorkflowDefinition.SubjectMachine.WORK_ITEM, OPEN,
                    List.of(state(OPEN, "OPEN", WorkflowStateCategory.OPEN, true),
                            state(DONE, "DONE", WorkflowStateCategory.TERMINAL, false),
                            state(REJECTED, "REJECTED", WorkflowStateCategory.TERMINAL, false)),
                    // 'reject' with reasonRequired = false.
                    List.of(transition(OPEN, DONE, "complete"), transition(OPEN, REJECTED, "reject")));

            assertTrue(definition.validate().findings().stream()
                            .anyMatch(f -> f.kind()
                                    == WorkflowValidation.Finding.Kind.TERMINAL_WITHOUT_REASON),
                    "an unexplained rejection is the record nobody can review afterwards");

            // And a success terminal needs no reason, or every completion would demand a justification.
            assertTrue(remediationWorkflow().validate().activatable());
        }

        @Test
        @DisplayName("PRD-WRK-035: a workflow cannot be defined for a fixed machine")
        void fixedMachinesRejectDefinition() {
            for (var machine : WorkflowDefinition.SubjectMachine.values()) {
                if (machine.tenantConfigurable()) {
                    continue;
                }
                var ex = assertThrows(IllegalArgumentException.class,
                        () -> new WorkflowDefinition(UUID.randomUUID(), TYPE, 1, machine, OPEN,
                                List.of(state(OPEN, "OPEN", WorkflowStateCategory.OPEN, true),
                                        state(DONE, "DONE", WorkflowStateCategory.TERMINAL, false)),
                                List.of(transition(OPEN, DONE, "complete"))),
                        machine + " must be rejected: a tenant-editable version could remove a DOC-03 "
                                + "invariant through configuration");
                assertTrue(ex.getMessage().contains("PRD-WRK-035"));
            }
            assertEquals(1, java.util.Arrays.stream(WorkflowDefinition.SubjectMachine.values())
                            .filter(WorkflowDefinition.SubjectMachine::tenantConfigurable).count(),
                    "only the work item machine is configurable (DOC-09 section 13)");
        }

        @Test
        @DisplayName("INV-WRK-01: an activated definition is not re-activated, so pinned items are not stranded")
        void activatedDefinitionIsNotReactivated() {
            var definition = remediationWorkflow();
            definition.activate(T0);
            assertThrows(IllegalStateException.class, () -> definition.activate(T0.plusSeconds(60)));

            definition.retire(T0.plusSeconds(120));
            assertEquals(WorkflowDefinition.State.RETIRED, definition.state(),
                    "retirement does not migrate in-flight items; they keep the version they pinned");
        }

        @Test
        @DisplayName("a terminal state with the clock running is rejected at construction")
        void terminalStateCannotRunTheClock() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> state(DONE, "DONE", WorkflowStateCategory.TERMINAL, true));
            assertTrue(ex.getMessage().contains("accrue"),
                    "nothing leaves a terminal state, so the clock would breach items that finished");
        }

        @Test
        @DisplayName("a self-transition is rejected: it logs movement that did not happen")
        void selfTransitionRejected() {
            assertThrows(IllegalArgumentException.class, () -> transition(OPEN, OPEN, "touch"));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-WRK-031 — the evaluation order IS the control")
    class EvaluationOrder {

        private final TransitionEvaluation evaluation = new TransitionEvaluation(EVALUATION);

        private TransitionEvaluation.Request request(boolean withinScope, Set<String> held) {
            return new TransitionEvaluation.Request(withinScope, held, Optional.empty(), Set.of(),
                    Set.of(), FactSet.empty(), ActorType.USER);
        }

        @Test
        @DisplayName("a scope failure returns 404 and denies BEFORE the permission check")
        void scopeFailureIsNotFoundAndComesFirst() {
            var definition = remediationWorkflow();
            // Out of scope, no permissions, and an event that does not exist. If any later check ran first the
            // response would differ, so a single assertion pins the whole ordering at its most consequential
            // point.
            var decision = evaluation.evaluate(definition, OPEN, "does-not-exist", request(false, Set.of()));

            assertEquals(TransitionEvaluation.Step.SCOPE, decision.deniedAt());
            assertEquals(404, decision.httpStatus(),
                    "ordering scope before permission prevents a permission denial confirming that an "
                            + "out-of-scope object exists (SEC-AUZ-020)");
            assertEquals("not found", decision.detail(),
                    "anything more detailed restores the object-existence oracle the ordering removes");
        }

        @Test
        @DisplayName("an unavailable transition is 409, distinguishable from a denial")
        void unavailableTransitionIsConflict() {
            var decision = evaluation.evaluate(remediationWorkflow(), OPEN, "complete",
                    request(true, Set.of()));
            assertEquals(TransitionEvaluation.Step.TRANSITION_AVAILABLE, decision.deniedAt());
            assertEquals(409, decision.httpStatus());
            assertTrue(decision.detail().contains("STATE_TRANSITION_INVALID"),
                    "the caller has already passed the scope check, so they are entitled to know the "
                            + "transition is unavailable rather than forbidden (DOC-09 section 3)");
        }

        @Test
        @DisplayName("a missing transition permission is 403 with no detail beyond forbidden")
        void permissionDenialIsOpaque() {
            var guarded = new WorkflowDefinition(UUID.randomUUID(), TYPE, 1,
                    WorkflowDefinition.SubjectMachine.WORK_ITEM, OPEN,
                    List.of(state(OPEN, "OPEN", WorkflowStateCategory.OPEN, true),
                            state(DONE, "DONE", WorkflowStateCategory.TERMINAL, false)),
                    List.of(new WorkflowTransition(UUID.randomUUID(), OPEN, DONE, "complete", Optional.empty(),
                            List.of(), Optional.of("wrk.item.transition.complete"), List.of(), false)));

            var denied = evaluation.evaluate(guarded, OPEN, "complete", request(true, Set.of()));
            assertEquals(TransitionEvaluation.Step.PERMISSION, denied.deniedAt());
            assertEquals(403, denied.httpStatus());

            var permitted = evaluation.evaluate(guarded, OPEN, "complete",
                    request(true, Set.of("wrk.item.transition.complete")));
            assertTrue(permitted.permitted(),
                    "and the check must permit the legitimate case, or it proves nothing");
        }

        @Test
        @DisplayName("INV-WRK-13: an automation cannot exceed its owner even holding the permission itself")
        void automationCannotExceedItsOwner() {
            var guarded = new WorkflowDefinition(UUID.randomUUID(), TYPE, 1,
                    WorkflowDefinition.SubjectMachine.WORK_ITEM, OPEN,
                    List.of(state(OPEN, "OPEN", WorkflowStateCategory.OPEN, true),
                            state(DONE, "DONE", WorkflowStateCategory.TERMINAL, false)),
                    List.of(new WorkflowTransition(UUID.randomUUID(), OPEN, DONE, "complete", Optional.empty(),
                            List.of(), Optional.of("wrk.item.transition.complete"), List.of(), false)));

            // The rule holds the permission; its OWNER does not.
            var asAutomation = new TransitionEvaluation.Request(true,
                    Set.of("wrk.item.transition.complete"), Optional.of(Set.of()), Set.of(), Set.of(),
                    FactSet.empty(), ActorType.AUTOMATION);

            var decision = evaluation.evaluate(guarded, OPEN, "complete", asAutomation);
            assertEquals(TransitionEvaluation.Step.AUTHORITY_CEILING, decision.deniedAt(),
                    "an automation rule is code executing with authority, authored through configuration by "
                            + "someone not thinking about authorization; without a ceiling it is a privilege "
                            + "escalation mechanism no access review would detect (INV-WRK-13)");
        }

        @Test
        @DisplayName("an AUTOMATION request without its owner's permissions is refused at construction")
        void automationRequestMustCarryOwnerAuthority() {
            assertThrows(IllegalArgumentException.class,
                    () -> new TransitionEvaluation.Request(true, Set.of("p"), Optional.empty(), Set.of(),
                            Set.of(), FactSet.empty(), ActorType.AUTOMATION),
                    "an unenforceable ceiling is worse than none: it reads as enforced");
        }

        @Test
        @DisplayName("SEC-AUZ-039: separation of duties is enforced at action time and names the conflict")
        void separationOfDutiesAtActionTime() {
            var request = new TransitionEvaluation.Request(true, Set.of(), Optional.empty(),
                    Set.of("wrk.workflow.manage", "wrk.item.transition"), Set.of(), FactSet.empty(),
                    ActorType.USER);

            var decision = evaluation.evaluate(remediationWorkflow(), OPEN, "start", request);
            assertEquals(TransitionEvaluation.Step.SEPARATION_OF_DUTIES, decision.deniedAt(),
                    "grant-time enforcement alone is defeated by two roles that are individually compliant "
                            + "and jointly conflicting");
            assertTrue(decision.detail().contains("wrk.workflow.manage"),
                    "the remedy is an access change and not a retry, so the pair must be named; got "
                            + decision.detail());
        }

        @Test
        @DisplayName("required fields are checked before the guard, and the missing ones are named")
        void requiredFieldsBeforeGuard() {
            var withFieldsAndGuard = new WorkflowDefinition(UUID.randomUUID(), TYPE, 1,
                    WorkflowDefinition.SubjectMachine.WORK_ITEM, OPEN,
                    List.of(state(OPEN, "OPEN", WorkflowStateCategory.OPEN, true),
                            state(DONE, "DONE", WorkflowStateCategory.TERMINAL, false)),
                    List.of(new WorkflowTransition(UUID.randomUUID(), OPEN, DONE, "complete",
                            Optional.of(new Condition.Comparison("verified", Operator.EQUALS,
                                    new FactValue.Bool(true))),
                            List.of("resolution_note"), Optional.empty(), List.of(), false)));

            var decision = evaluation.evaluate(withFieldsAndGuard, OPEN, "complete",
                    request(true, Set.of()));
            assertEquals(TransitionEvaluation.Step.REQUIRED_FIELDS, decision.deniedAt(),
                    "the cheaper check runs first; the guard is the most expensive and least "
                            + "disclosure-sensitive step");
            assertTrue(decision.detail().contains("resolution_note"));
        }

        @Test
        @DisplayName("the guard is evaluated by the SHARED engine, and UNDEFINED denies distinguishably")
        void guardUsesTheSharedEngineAndUndefinedDenies() {
            var guardedDefinition = new WorkflowDefinition(UUID.randomUUID(), TYPE, 1,
                    WorkflowDefinition.SubjectMachine.WORK_ITEM, OPEN,
                    List.of(state(OPEN, "OPEN", WorkflowStateCategory.OPEN, true),
                            state(DONE, "DONE", WorkflowStateCategory.TERMINAL, false)),
                    List.of(new WorkflowTransition(UUID.randomUUID(), OPEN, DONE, "complete",
                            Optional.of(new Condition.Comparison("verified", Operator.EQUALS,
                                    new FactValue.Bool(true))),
                            List.of(), Optional.empty(), List.of(), false)));

            var absent = new TransitionEvaluation.Request(true, Set.of(), Optional.empty(), Set.of(),
                    Set.of(), FactSet.empty(), ActorType.USER);
            var undefined = evaluation.evaluate(guardedDefinition, OPEN, "complete", absent);
            assertEquals(TransitionEvaluation.Step.GUARD, undefined.deniedAt());
            assertTrue(undefined.detail().contains("absent"),
                    "'the guard said no' and 'the guard could not be evaluated' need different fixes");

            var satisfied = new TransitionEvaluation.Request(true, Set.of(), Optional.empty(), Set.of(),
                    Set.of(), new FactSet(Map.of("verified", new FactValue.Bool(true))),
                    ActorType.USER);
            assertTrue(evaluation.evaluate(guardedDefinition, OPEN, "complete", satisfied).permitted());

            var contradicted = new TransitionEvaluation.Request(true, Set.of(), Optional.empty(), Set.of(),
                    Set.of(), new FactSet(Map.of("verified", new FactValue.Bool(false))),
                    ActorType.USER);
            var denied = evaluation.evaluate(guardedDefinition, OPEN, "complete", contradicted);
            assertEquals(TransitionEvaluation.Step.GUARD, denied.deniedAt());
            assertFalse(denied.detail().contains("absent"));
        }

        @Test
        @DisplayName("CON-PLT-012: the module holds no guard evaluator of its own")
        void moduleDoesNotReimplementTheEvaluator() {
            assertThrows(NullPointerException.class, () -> new TransitionEvaluation(null),
                    "a module-local evaluator would be the fourth implementation DOC-02 section 6.2 rejects, "
                            + "and this one governs authorization-relevant transitions");
            // The port is honoured: an evaluator that always returned TRUE would open every guard, so the
            // production wiring must be the shared one. Asserted structurally — the field is required and there
            // is no no-argument constructor to fall back to.
            assertEquals(1, TransitionEvaluation.class.getConstructors().length,
                    "there is no no-argument constructor to fall back to, so nothing can be wired without an "
                            + "evaluator");
            // That exactly one implementation of the port exists in the build is asserted in
            // architecture-tests: this module cannot see the others, which is the point.
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-WRK-03 / INV-WRK-04 — the append-only transition log")
    class Log {

        private TransitionLog started() {
            var log = new TransitionLog(ITEM);
            log.recordCreation(UUID.randomUUID(), OPEN, new UUID(92, 1), ActorType.USER, T0, true);
            return log;
        }

        @Test
        @DisplayName("no removal path exists at any privilege")
        void noRemovalPath() {
            // The prompt asks for this assertion explicitly. Checked by shape rather than by trusting the
            // absence to survive a future edit.
            for (Class<?> type : List.of(TransitionLog.class, WorkItemStateTransition.class)) {
                for (Method m : type.getMethods()) {
                    if (m.getDeclaringClass() == Object.class) {
                        continue;
                    }
                    String name = m.getName().toLowerCase(Locale.ROOT);
                    assertFalse(name.startsWith("delete") || name.startsWith("remove")
                                    || name.startsWith("clear") || name.startsWith("truncate")
                                    || name.startsWith("set") || name.startsWith("replace")
                                    || name.startsWith("purge"),
                            "found " + type.getSimpleName() + "." + m.getName()
                                    + ". INV-WRK-04 makes the log append-only, and its data cannot be "
                                    + "reconstructed later: 'how many items were in remediation at the end of "
                                    + "last quarter' is answerable only from a transition record.");
                }
            }
        }

        @Test
        @DisplayName("entries() returns a copy, so a caller cannot mutate the log through it")
        void entriesViewIsACopy() {
            var log = started();
            var view = log.entries();
            assertThrows(UnsupportedOperationException.class, view::clear);
            assertEquals(1, log.size());
        }

        @Test
        @DisplayName("the sequence is monotonic, and creation is the only entry without a prior state")
        void sequenceIsMonotonic() {
            var log = started();
            var first = log.last().orElseThrow();
            assertEquals(1, first.sequence());
            assertTrue(first.fromStateId().isEmpty(),
                    "a sentinel prior state would be indistinguishable from a real one in a cumulative-flow "
                            + "query");

            var second = log.append(UUID.randomUUID(), IN_PROGRESS, "start", new UUID(92, 1), ActorType.USER,
                    null, null, T0.plus(Duration.ofHours(2)), true, null, false, false);
            assertEquals(2, second.sequence());
            assertEquals(Optional.of(OPEN), second.fromStateId());

            assertThrows(IllegalArgumentException.class,
                    () -> new WorkItemStateTransition(UUID.randomUUID(), ITEM, 2, Optional.empty(), DONE,
                            "complete", Optional.of(new UUID(92, 1)), ActorType.USER, Optional.empty(),
                            Optional.empty(), T0, Optional.of(Duration.ZERO), true, Optional.empty()),
                    "only the creation entry has no prior state");
        }

        @Test
        @DisplayName("duration in the previous state is computed, not supplied")
        void durationIsComputed() {
            var log = started();
            var second = log.append(UUID.randomUUID(), IN_PROGRESS, "start", new UUID(92, 1), ActorType.USER,
                    null, null, T0.plus(Duration.ofHours(6)), true, null, false, false);
            assertEquals(Optional.of(Duration.ofHours(6)), second.durationInPreviousState(),
                    "a caller supplying it would eventually supply one that disagrees with the log, and a "
                            + "disagreement in an append-only record is unfixable by construction");
            assertEquals(Duration.ofHours(6), log.timeSpentIn(OPEN));
        }

        @Test
        @DisplayName("time cannot run backwards through the log")
        void timeDoesNotRunBackwards() {
            var log = started();
            assertThrows(IllegalArgumentException.class,
                    () -> log.append(UUID.randomUUID(), IN_PROGRESS, "start", new UUID(92, 1), ActorType.USER,
                            null, null, T0.minusSeconds(60), true, null, false, false),
                    "accepting it would produce a negative duration that later arithmetic treats as real");
        }

        @Test
        @DisplayName("sla_clock_running is recorded on the transition, not resolved from the state")
        void clockFlagIsRecordedNotResolved() {
            var log = started();
            log.append(UUID.randomUUID(), IN_PROGRESS, "start", new UUID(92, 1), ActorType.USER, null, null,
                    T0.plus(Duration.ofHours(2)), true, null, false, false);
            // Two hours blocked, with the clock stopped in BLOCKED.
            log.append(UUID.randomUUID(), BLOCKED, "block", new UUID(92, 1), ActorType.USER, null, null,
                    T0.plus(Duration.ofHours(5)), true, TransitionBlockingAttribution.THIRD_PARTY, false, true);
            log.append(UUID.randomUUID(), IN_PROGRESS, "unblock", new UUID(92, 1), ActorType.USER, null, null,
                    T0.plus(Duration.ofHours(9)), false, null, false, false);

            assertEquals(Duration.ofHours(5), log.clockRunningDuration(),
                    "the four hours in BLOCKED are excluded, because a cycle time including time somebody "
                            + "else blocked charges a team for a delay they did not cause");
            assertEquals(Duration.ofHours(4), log.timeSpentIn(BLOCKED));

            // The flag as it WAS. A tenant flipping BLOCKED's sla_clock_running tomorrow must not change this.
            assertFalse(log.entries().get(3).slaClockRunning(),
                    "otherwise a configuration change retroactively alters past breach attribution");
        }

        @Test
        @DisplayName("PRD-RSK-034: entering a clock-pausing state requires a blocking attribution")
        void pausingRequiresAttribution() {
            var log = started();
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> log.append(UUID.randomUUID(), BLOCKED, "block", new UUID(92, 1), ActorType.USER,
                            null, null, T0.plusSeconds(60), true, null, false, true));
            assertTrue(ex.getMessage().contains("PP-6"));

            assertThrows(IllegalArgumentException.class,
                    () -> log.append(UUID.randomUUID(), IN_PROGRESS, "start", new UUID(92, 1), ActorType.USER,
                            null, null, T0.plusSeconds(60), true,
                            TransitionBlockingAttribution.CAPACITY, false, false),
                    "an attribution on a transition into a running state would report blocked time during "
                            + "which nothing was blocked");
        }

        @Test
        @DisplayName("DOC-09 section 3: a transition demanding a reason cannot be recorded without one")
        void reasonEnforced() {
            var log = started();
            assertThrows(IllegalArgumentException.class,
                    () -> log.append(UUID.randomUUID(), REJECTED, "reject", new UUID(92, 1), ActorType.USER,
                            null, "  ", T0.plusSeconds(60), true, null, true, false));
            var recorded = log.append(UUID.randomUUID(), REJECTED, "reject", new UUID(92, 1), ActorType.USER,
                    null, "duplicate of WRK-104", T0.plusSeconds(60), true, null, true, false);
            assertEquals(Optional.of("duplicate of WRK-104"), recorded.reason());
        }

        @Test
        @DisplayName("an AUTOMATION entry names its rule; a USER entry may not")
        void automationNamesItsRule() {
            var log = started();
            assertThrows(IllegalArgumentException.class,
                    () -> log.append(UUID.randomUUID(), IN_PROGRESS, "start", new UUID(92, 1),
                            ActorType.AUTOMATION, null, null, T0.plusSeconds(60), true, null, false, false),
                    "an automated transition that did not name its rule is indistinguishable from a human one "
                            + "at exactly the moment somebody is asking why the item moved");
            assertThrows(IllegalArgumentException.class,
                    () -> log.append(UUID.randomUUID(), IN_PROGRESS, "start", new UUID(92, 1), ActorType.USER,
                            new UUID(93, 1), null, T0.plusSeconds(60), true, null, false, false),
                    "a rule identifier on a human transition attributes automated activity to a principal who "
                            + "did not act");
        }

        @Test
        @DisplayName("SYSTEM has no principal, and USER cannot be anonymous")
        void actorAttribution() {
            var log = started();
            assertThrows(IllegalArgumentException.class,
                    () -> log.append(UUID.randomUUID(), DONE, "expire", new UUID(92, 1), ActorType.SYSTEM,
                            null, null, T0.plusSeconds(60), true, null, false, false),
                    "naming a principal for platform activity attributes it to a person");
            assertThrows(IllegalArgumentException.class,
                    () -> log.append(UUID.randomUUID(), IN_PROGRESS, "start", null, ActorType.USER, null,
                            null, T0.plusSeconds(60), true, null, false, false),
                    "an unattributed transition defeats the per-principal rate that SEC-PLT-005 depends on");
        }

        @Test
        @DisplayName("PRD-WRK-036: returning to a state is a forward transition, and rework stays visible")
        void reworkIsVisible() {
            var log = started();
            log.append(UUID.randomUUID(), IN_PROGRESS, "start", new UUID(92, 1), ActorType.USER, null, null,
                    T0.plus(Duration.ofHours(1)), true, null, false, false);
            log.append(UUID.randomUUID(), BLOCKED, "block", new UUID(92, 1), ActorType.USER, null, null,
                    T0.plus(Duration.ofHours(2)), true, TransitionBlockingAttribution.ENVIRONMENT, false, true);
            log.append(UUID.randomUUID(), IN_PROGRESS, "unblock", new UUID(92, 1), ActorType.USER, null, null,
                    T0.plus(Duration.ofHours(3)), false, null, false, false);

            assertEquals(4, log.size(), "nothing was removed or rewritten");
            assertEquals(2, log.entriesInto(IN_PROGRESS),
                    "an undo that removes history conceals rework — which is itself a signal (PRD-WRK-036)");
        }

        @Test
        @DisplayName("a rehydrated log with a gap is rejected")
        void gapInStoredSequenceRejected() {
            var creation = WorkItemStateTransition.creation(UUID.randomUUID(), ITEM, OPEN, new UUID(92, 1),
                    ActorType.USER, T0, true);
            var third = new WorkItemStateTransition(UUID.randomUUID(), ITEM, 3, Optional.of(OPEN),
                    IN_PROGRESS, "start", Optional.of(new UUID(92, 1)), ActorType.USER, Optional.empty(),
                    Optional.empty(), T0.plusSeconds(60), Optional.of(Duration.ofSeconds(60)), true,
                    Optional.empty());

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> TransitionLog.of(ITEM, List.of(creation, third)));
            assertTrue(ex.getMessage().contains("gap"),
                    "every duration after a gap is wrong by the missing interval while still looking "
                            + "arithmetically sound");
        }

        @Test
        @DisplayName("creation must come first and cannot be recorded twice")
        void creationIsFirstAndOnce() {
            var empty = new TransitionLog(ITEM);
            assertThrows(IllegalStateException.class,
                    () -> empty.append(UUID.randomUUID(), IN_PROGRESS, "start", new UUID(92, 1),
                            ActorType.USER, null, null, T0, true, null, false, false));
            var log = started();
            assertThrows(IllegalStateException.class,
                    () -> log.recordCreation(UUID.randomUUID(), OPEN, new UUID(92, 1), ActorType.USER, T0,
                            true));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PP-10 — the two blocking-attribution sets map, they do not diverge")
    class AttributionMapping {

        @Test
        @DisplayName("every transition attribution maps onto a clock attribution")
        void everyValueMaps() {
            for (var attribution : TransitionBlockingAttribution.values()) {
                assertTrue(Set.of("REQUESTER", "THIRD_PARTY", "SECURITY_FUNCTION")
                                .contains(attribution.escalationAttribution()),
                        attribution + " maps to an unknown clock attribution; two independent judgements "
                                + "about the same pause is exactly what PP-10 warns about");
            }
        }

        @Test
        @DisplayName("CAPACITY escalates against the security function rather than suppressing")
        void capacityDoesNotSuppressEscalation() {
            assertFalse(TransitionBlockingAttribution.CAPACITY.suppressesRemediationEscalation(),
                    "mapping capacity to REQUESTER or THIRD_PARTY would suppress the escalation that ought to "
                            + "fire, which is how a backlog becomes invisible: every item paused, nobody "
                            + "escalated");
            assertTrue(TransitionBlockingAttribution.THIRD_PARTY.suppressesRemediationEscalation());
            assertTrue(TransitionBlockingAttribution.REQUESTER_READINESS.suppressesRemediationEscalation());
        }

        @Test
        @DisplayName("the two enumerations are deliberately different sets, not a copy that drifted")
        void setsAreDeliberatelyDifferent() {
            assertEquals(6, TransitionBlockingAttribution.values().length,
                    "DOC-04 section 16.3 lists six: what stopped the work. PRD-RSK-034 lists three: who is "
                            + "accountable. One decides what to fix, the other decides whom to escalate to.");
            assertNotEquals(TransitionBlockingAttribution.ENVIRONMENT.escalationAttribution(),
                    TransitionBlockingAttribution.CAPACITY.escalationAttribution(),
                    "and the mapping is not the identity — collapsing it would lose the distinction");
        }
    }
}
