package aspm.module.workmanagement.domain;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A versioned workflow definition. Aggregate root; DOC-03 section 13.1, DOC-04 section 16.1.
 *
 * <p><b>Workflow is data</b> (ADR-027, {@code PRD-WRK-008}). States, transitions, guards, required fields,
 * transition permissions and side effects are tenant configuration interpreted by the shared rules engine — not
 * code. What is <i>not</i> configurable is the machinery in this class: the validation of
 * {@code PRD-WRK-034}, version immutability after activation, and the refusal to define a workflow for a fixed
 * machine ({@code PRD-WRK-035}).
 *
 * <p><b>On fixed machines.</b> {@code PRD-WRK-035}: their transitions "carry DOC-03 invariants — bounded
 * exception expiry, closure verification, coverage-confirmed closure. A tenant-editable version could remove the
 * invariant through configuration." So the platform rejects the attempt rather than accepting a definition that
 * would be ignored, which would leave a tenant believing they had changed behaviour they had not.
 *
 * <p><b>On version immutability.</b> {@code INV-WRK-01} pins the version on each item at creation, so an
 * activated definition must never change underneath the items pinned to it. A change is a new version, and
 * in-flight items keep the one they started with — "a workflow change does not strand in-flight items".
 */
public final class WorkflowDefinition {

    /** Machines whose transitions carry DOC-03 invariants. {@code PRD-WRK-035}, DOC-09 section LC-03. */
    public enum SubjectMachine {
        /** DOC-09 section 13 — the configurable one, and the only one this class may define. */
        WORK_ITEM(true),
        FINDING(false),
        SECRET_FINDING(false),
        RISK_EXCEPTION(false),
        SERVICE_LEVEL_CLOCK(false),
        MATCH_RUN(false),
        EVIDENCE_AVAILABILITY(false),
        GRANT(false),
        IMPORT_SESSION(false),
        AI_SUGGESTION(false);

        private final boolean tenantConfigurable;

        SubjectMachine(boolean tenantConfigurable) {
            this.tenantConfigurable = tenantConfigurable;
        }

        public boolean tenantConfigurable() {
            return tenantConfigurable;
        }
    }

    public enum State {
        DRAFT,
        ACTIVE,
        RETIRED
    }

    private final UUID id;
    private final UUID workItemTypeId;
    private final int version;
    private final SubjectMachine machine;
    private final UUID initialStateId;
    private final List<WorkflowState> states;
    private final List<WorkflowTransition> transitions;

    private State state = State.DRAFT;
    private Instant validatedAt;
    private Instant activatedAt;
    private Instant retiredAt;

    private final Map<UUID, WorkflowState> statesById;
    private final Map<UUID, List<WorkflowTransition>> outbound;

    public WorkflowDefinition(UUID id, UUID workItemTypeId, int version, SubjectMachine machine,
            UUID initialStateId, List<WorkflowState> states, List<WorkflowTransition> transitions) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.workItemTypeId = Objects.requireNonNull(workItemTypeId, "workItemTypeId is required");
        this.machine = Objects.requireNonNull(machine, "the subject machine is required (PRD-WRK-035)");
        this.initialStateId = Objects.requireNonNull(initialStateId, "an initial state is required");
        this.states = List.copyOf(Objects.requireNonNull(states, "states are required"));
        this.transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions are required"));
        if (version < 1) {
            throw new IllegalArgumentException("a version is required and pinned on each item (INV-WRK-01)");
        }
        this.version = version;

        if (!machine.tenantConfigurable()) {
            // Rejected at construction, not at activation. A tenant that got as far as a DRAFT definition for a
            // fixed machine would reasonably expect activation to be the only obstacle.
            throw new IllegalArgumentException(
                    machine + " is a fixed machine and MUST NOT be tenant-configurable (PRD-WRK-035). Its "
                            + "transitions carry DOC-03 invariants — bounded exception expiry, closure "
                            + "verification, coverage-confirmed closure — and a tenant-editable version could "
                            + "remove an invariant through configuration.");
        }

        Map<UUID, WorkflowState> byId = new LinkedHashMap<>();
        for (WorkflowState s : this.states) {
            byId.put(s.id(), s);
        }
        this.statesById = Map.copyOf(byId);

        Map<UUID, List<WorkflowTransition>> out = new LinkedHashMap<>();
        for (WorkflowTransition t : this.transitions) {
            out.computeIfAbsent(t.fromStateId(), k -> new ArrayList<>()).add(t);
        }
        this.outbound = out;
    }

    /**
     * Runs the four checks of {@code PRD-WRK-034} plus three structural ones. Pure — it mutates nothing, so it
     * can be run on a draft as the administrator edits.
     */
    public WorkflowValidation validate() {
        List<WorkflowValidation.Finding> findings = new ArrayList<>();

        if (states.size() != statesById.size()) {
            findings.add(new WorkflowValidation.Finding(
                    WorkflowValidation.Finding.Kind.DUPLICATE_DEFINITION, "states",
                    "two states share an identifier, so which one a transition resolves to depends on "
                            + "iteration order"));
        }

        Set<String> transitionKeys = new HashSet<>();
        for (WorkflowTransition t : transitions) {
            if (!transitionKeys.add(t.fromStateId() + "/" + t.eventCode())) {
                findings.add(new WorkflowValidation.Finding(
                        WorkflowValidation.Finding.Kind.DUPLICATE_DEFINITION, t.eventCode(),
                        "two transitions leave the same state on the same event; the one that fires would "
                                + "depend on ordering, and they may have different guards and permissions"));
            }
        }

        if (!statesById.containsKey(initialStateId)) {
            findings.add(new WorkflowValidation.Finding(
                    WorkflowValidation.Finding.Kind.INITIAL_STATE_NOT_DEFINED, initialStateId.toString(),
                    "the initial state is not among the definition's states, so no item can be created"));
        }

        // Check 4 first: reachability below walks the transitions, and a dangling reference would otherwise
        // surface as a confusing unreachable-state finding.
        for (WorkflowTransition t : transitions) {
            if (!statesById.containsKey(t.fromStateId())) {
                findings.add(new WorkflowValidation.Finding(
                        WorkflowValidation.Finding.Kind.DANGLING_STATE_REFERENCE, t.eventCode(),
                        "from-state " + t.fromStateId() + " is not in this definition"));
            }
            if (!statesById.containsKey(t.toStateId())) {
                findings.add(new WorkflowValidation.Finding(
                        WorkflowValidation.Finding.Kind.DANGLING_STATE_REFERENCE, t.eventCode(),
                        "to-state " + t.toStateId() + " is not in this definition; the failure would surface "
                                + "at transition time, on a real item, in front of a user"));
            }
        }

        // Check 2.
        boolean anyTerminal = states.stream().anyMatch(s -> s.category().isTerminal());
        if (!anyTerminal) {
            findings.add(new WorkflowValidation.Finding(
                    WorkflowValidation.Finding.Kind.NO_TERMINAL_STATE, "definition",
                    "no state is TERMINAL, so nothing can finish and every completion metric reports zero"));
        }

        // Check 1: breadth-first from the initial state.
        Set<UUID> reachable = new HashSet<>();
        if (statesById.containsKey(initialStateId)) {
            Deque<UUID> frontier = new ArrayDeque<>();
            frontier.add(initialStateId);
            reachable.add(initialStateId);
            while (!frontier.isEmpty()) {
                UUID current = frontier.removeFirst();
                for (WorkflowTransition t : outbound.getOrDefault(current, List.of())) {
                    if (statesById.containsKey(t.toStateId()) && reachable.add(t.toStateId())) {
                        frontier.addLast(t.toStateId());
                    }
                }
            }
        }
        for (WorkflowState s : states) {
            if (!reachable.contains(s.id())) {
                findings.add(new WorkflowValidation.Finding(
                        WorkflowValidation.Finding.Kind.UNREACHABLE_STATE, s.code(),
                        "not reachable from the initial state; it appears in every board view and filter and "
                                + "no item can ever occupy it"));
            }
        }

        // Check 3 — the trap.
        for (WorkflowState s : states) {
            if (s.category().isTerminal()) {
                continue;
            }
            if (outbound.getOrDefault(s.id(), List.of()).isEmpty()) {
                findings.add(new WorkflowValidation.Finding(
                        WorkflowValidation.Finding.Kind.DEAD_END_STATE, s.code(),
                        "non-terminal with no outbound transition. Items enter and cannot leave, and the "
                                + "defect surfaces days later as stalled work with no visible cause"));
            }
        }

        // DOC-09 section 3: a reason is always required on a transition into a non-success terminal state.
        for (WorkflowTransition t : transitions) {
            WorkflowState target = statesById.get(t.toStateId());
            if (target == null || !target.category().isTerminal() || t.reasonRequired()) {
                continue;
            }
            if (nonSuccessTerminal(target.code())) {
                findings.add(new WorkflowValidation.Finding(
                        WorkflowValidation.Finding.Kind.TERMINAL_WITHOUT_REASON, t.eventCode(),
                        "enters non-success terminal state '" + target.code() + "' without requiring a reason. "
                                + "An unexplained rejection or cancellation is the record nobody can review "
                                + "afterwards (DOC-09 section 3)"));
            }
        }

        return new WorkflowValidation(findings);
    }

    /**
     * Whether a terminal state code denotes a non-success outcome.
     *
     * <p><b>A heuristic over tenant-authored codes, and marked as one.</b> DOC-09 section 3 requires a reason on
     * a transition into a terminal state that is not a success outcome, but nothing in the schema records which
     * terminal states are successes — DOC-04 section 16.1 gives states a category and not an outcome polarity.
     * Rather than invent a column the corpus does not define, this recognises the codes the shipped defaults use
     * (DOC-09 section 13: {@code REJECTED}, {@code CANCELLED}) and stays silent on codes it does not know.
     *
     * <p>The consequence, stated: a tenant whose non-success terminal state is called something else gets no
     * finding, and their rejections may go unexplained. That is a gap in this check, not a gap the check
     * conceals — it fails open and says so, rather than guessing at a tenant's vocabulary, which ADR-027 puts
     * out of bounds. Closing it properly needs an outcome polarity on {@code workflow_state}, which is a
     * corpus change and not one to make from inside an implementation session.
     */
    private static boolean nonSuccessTerminal(String stateCode) {
        String normalized = stateCode.toUpperCase(java.util.Locale.ROOT);
        return normalized.equals("REJECTED") || normalized.equals("CANCELLED") || normalized.equals("CANCELED");
    }

    /**
     * Activates the definition.
     *
     * @throws IllegalStateException where validation fails. {@code INV-WRK-02} requires validation
     *     <b>before</b> activation, and DOC-04 section 16.1 makes an unvalidated activation unrepresentable at
     *     the engine too
     */
    public void activate(Instant at) {
        Objects.requireNonNull(at, "the activation instant is required");
        if (state != State.DRAFT) {
            throw new IllegalStateException("only a DRAFT definition activates; this one is " + state);
        }
        WorkflowValidation validation = validate();
        if (!validation.activatable()) {
            throw new IllegalStateException(validation.diagnosis());
        }
        this.validatedAt = at;
        this.activatedAt = at;
        this.state = State.ACTIVE;
    }

    /**
     * Retires the definition. In-flight items pinned to this version continue to use it ({@code INV-WRK-01}),
     * which is why retirement does not delete and does not migrate anything.
     */
    public void retire(Instant at) {
        Objects.requireNonNull(at, "the retirement instant is required");
        if (state != State.ACTIVE) {
            throw new IllegalStateException("only an ACTIVE definition retires; this one is " + state);
        }
        this.retiredAt = at;
        this.state = State.RETIRED;
    }

    /**
     * The transition available from a state for an event, if any.
     *
     * <p>Step 3 of the evaluation order of DOC-09 section 2.1. Returns empty rather than throwing: an
     * unavailable transition is a {@code 409}, and the caller distinguishes it from a denial.
     */
    public Optional<WorkflowTransition> transitionFor(UUID fromStateId, String eventCode) {
        Objects.requireNonNull(fromStateId, "fromStateId is required");
        Objects.requireNonNull(eventCode, "eventCode is required");
        return outbound.getOrDefault(fromStateId, List.of()).stream()
                .filter(t -> t.eventCode().equals(eventCode))
                .findFirst();
    }

    public Optional<WorkflowState> stateOf(UUID stateId) {
        return Optional.ofNullable(statesById.get(stateId));
    }

    public List<WorkflowTransition> availableFrom(UUID fromStateId) {
        return List.copyOf(outbound.getOrDefault(fromStateId, List.of()));
    }

    public UUID id() {
        return id;
    }

    public UUID workItemTypeId() {
        return workItemTypeId;
    }

    public int version() {
        return version;
    }

    public SubjectMachine machine() {
        return machine;
    }

    public UUID initialStateId() {
        return initialStateId;
    }

    public List<WorkflowState> states() {
        return states;
    }

    public List<WorkflowTransition> transitions() {
        return transitions;
    }

    public State state() {
        return state;
    }

    public Optional<Instant> validatedAt() {
        return Optional.ofNullable(validatedAt);
    }

    public Optional<Instant> activatedAt() {
        return Optional.ofNullable(activatedAt);
    }

    public Optional<Instant> retiredAt() {
        return Optional.ofNullable(retiredAt);
    }
}
