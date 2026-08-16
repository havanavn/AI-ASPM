package aspm.module.workmanagement.domain;

import aspm.kernel.rulesengine.contract.Condition;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An automation rule. Aggregate root; DOC-03 section 13.1, DOC-04 section 16.7.
 *
 * <h2>{@code INV-WRK-13} — the authority ceiling</h2>
 *
 * <p>DOC-03 section 13.2 states the hazard exactly: "An automation rule is code executing with authority,
 * authored through configuration by someone not thinking about authorization. Without an authority ceiling it is
 * a privilege escalation mechanism that no access review would detect."
 *
 * <p>The escalation is invisible because nothing about it looks like a permission grant. A person authors a rule;
 * the rule acts; the actions succeed. An access review lists the author's permissions and finds nothing wrong,
 * because the permissions the rule <i>used</i> are not recorded anywhere the review looks. The ceiling is what
 * makes the two the same set.
 *
 * <p>{@code wrk.automation.manage} is therefore one of the three most consequential permissions in the catalogue
 * (DOC-07 section 21), alongside {@code wrk.workflow.manage} and {@code auz.role.manage} — all three alter
 * authorization outcomes without appearing in a permission review.
 *
 * <h2>{@code INV-WRK-14} — loop detection and a bounded budget</h2>
 *
 * <p>A rule that transitions an item on a state change can trigger itself, or trigger a second rule that
 * triggers it back. Unbounded, that is a live-lock consuming the transaction budget of the whole tenant, and it
 * appears as the platform becoming slow rather than as a rule misbehaving. Both bounds are enforced per trigger,
 * not per rule: a rule that is fine in isolation and pathological in combination is the common case.
 *
 * <h2>{@code SEC-AUZ-038} — suspension when the owner loses authority</h2>
 *
 * <p>{@link #authoritySuspended} is a stored flag set by the authorization-change event rather than computed per
 * execution, because computing it would mean an authorization evaluation per rule per trigger. DOC-04
 * section 16.7 records the consequence honestly: the flag can be stale between the authority change and the
 * handler, bounded by {@code NFR-SEC-002} at sixty seconds — and the execution-time ceiling below is the backstop
 * that makes a stale flag survivable rather than exploitable.
 */
public final class AutomationRule {

    /** What starts an evaluation. Product-fixed: each corresponds to a domain event the platform publishes. */
    public enum TriggerKind {
        WORK_ITEM_CREATED,
        WORK_ITEM_TRANSITIONED,
        WORK_ITEM_FIELD_CHANGED,
        WORK_ITEM_ASSIGNED,
        COMMENT_POSTED,
        FINDING_INGESTED,
        SERVICE_LEVEL_THRESHOLD_REACHED,
        SCHEDULE
    }

    /** What a rule may do. Fixed catalogue, for the reason {@link WorkflowTransition.SideEffect} gives. */
    public enum ActionKind {
        TRANSITION,
        ASSIGN,
        SET_FIELD,
        ADD_LABEL,
        ADD_WATCHER,
        POST_COMMENT,
        LINK_ITEMS
    }

    /**
     * One action, with the permission it requires.
     *
     * <p>The permission is declared on the action rather than resolved when it runs, so the authority ceiling
     * can be checked against the owner <b>before</b> anything is attempted. Resolving it at execution time would
     * mean discovering a ceiling breach halfway through a rule's actions, with the earlier ones already applied.
     */
    public record Action(ActionKind kind, String requiredPermission, Object parameter) {

        public Action {
            Objects.requireNonNull(kind, "kind is required");
            Objects.requireNonNull(requiredPermission,
                    "an action must declare the permission it requires (INV-WRK-13). An action whose permission "
                            + "is unknown cannot be checked against the owner's authority, and an unenforceable "
                            + "ceiling reads as enforced.");
        }
    }

    /** The result of one evaluation. Recorded whether or not anything happened. */
    public record Execution(UUID ruleId, Optional<UUID> triggerEventId, int actionsAttempted,
            int actionsSucceeded, int actionsDenied, int loopDepth, Instant executedAt,
            List<String> denialReasons) {

        public Execution {
            Objects.requireNonNull(ruleId, "ruleId is required");
            Objects.requireNonNull(triggerEventId, "triggerEventId is required, empty for a schedule");
            Objects.requireNonNull(executedAt, "executedAt is required");
            denialReasons = List.copyOf(Objects.requireNonNull(denialReasons, "denialReasons are required"));
            if (actionsDenied != denialReasons.size()) {
                throw new IllegalArgumentException(
                        "counted " + actionsDenied + " denials with " + denialReasons.size() + " reasons. A "
                                + "denial without a reason is undiagnosable, and these denials are also the "
                                + "escalation-attempt signal of SEC-AUZ-037.");
            }
        }

        /** {@code PRD-WRK-044}: a rule losing to a human records the denial rather than retrying. */
        public boolean escalationAttemptSignal() {
            return actionsDenied > 0;
        }
    }

    /** Default budget of DOC-04 section 16.7. */
    public static final int DEFAULT_EXECUTION_BUDGET = 50;

    /**
     * Maximum chained depth. A rule triggering a rule triggering a rule is legitimate; four levels is a loop
     * somebody built by accident, and past that the cost of being wrong exceeds the cost of stopping.
     */
    public static final int MAX_LOOP_DEPTH = 3;

    private final UUID id;
    private final String name;
    private final TriggerKind triggerKind;
    private final Optional<Condition> conditions;
    private final List<Action> actions;
    private final UUID owningPrincipalId;
    private final int executionBudgetPerTrigger;

    private boolean enabled;
    private boolean authoritySuspended;
    private String suspendedReason;

    public AutomationRule(UUID id, String name, TriggerKind triggerKind, Condition conditions,
            List<Action> actions, UUID owningPrincipalId, int executionBudgetPerTrigger) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.name = Objects.requireNonNull(name, "a name is required");
        this.triggerKind = Objects.requireNonNull(triggerKind, "a trigger is required");
        this.conditions = Optional.ofNullable(conditions);
        this.actions = List.copyOf(Objects.requireNonNull(actions, "actions are required"));
        this.owningPrincipalId = Objects.requireNonNull(owningPrincipalId,
                "an owning principal is required (INV-WRK-13). A rule without one executes with unbounded "
                        + "authority, which is the privilege escalation mechanism no access review detects.");
        if (this.actions.isEmpty()) {
            throw new IllegalArgumentException("a rule with no actions fires and does nothing");
        }
        if (executionBudgetPerTrigger < 1) {
            throw new IllegalArgumentException("the execution budget must be at least 1 (INV-WRK-14)");
        }
        this.executionBudgetPerTrigger = executionBudgetPerTrigger;
        // Disabled on creation, matching DOC-04 section 16.7's default. A rule that ran the moment it was saved
        // would act on the tenant's whole backlog before its author had read it back.
        this.enabled = false;
    }

    /**
     * Evaluates the ceiling for every action against the owner's current permissions.
     *
     * @param ownerPermissions the owning principal's effective permissions <b>now</b>, not at authoring time. A
     *     ceiling checked against a snapshot would keep granting authority the owner has since lost, which is
     *     the case {@code SEC-AUZ-038} exists for
     * @return the actions the owner could not perform directly. Empty means the rule is within its ceiling
     */
    public List<Action> actionsExceedingAuthority(Set<String> ownerPermissions) {
        Objects.requireNonNull(ownerPermissions, "the owner's permissions are required");
        return actions.stream()
                .filter(a -> !ownerPermissions.contains(a.requiredPermission()))
                .toList();
    }

    /**
     * Whether this rule may run at all right now.
     *
     * <p>Three conditions, and the third is the one that survives a stale flag: enabled, not suspended, and
     * within its authority. The per-action ceiling check still runs at execution ({@link TransitionEvaluation}
     * step 5), because this method's answer can be sixty seconds old.
     */
    public boolean runnable(Set<String> ownerPermissions) {
        return enabled && !authoritySuspended && actionsExceedingAuthority(ownerPermissions).isEmpty();
    }

    /**
     * Plans one execution against the budget and the loop depth.
     *
     * @param loopDepth how many rule-triggered events deep this trigger already is. Zero for a human action
     * @param actionsAlreadyTakenThisTrigger the count consumed by earlier rules in the same trigger chain,
     *     because {@code INV-WRK-14} bounds the <b>trigger</b> and not the rule
     * @throws IllegalStateException where the loop guard or the budget stops it. An exception rather than a
     *     silent no-op: {@code PRD-WRK-044} requires the denial recorded, and a silent stop is exactly the
     *     undiagnosable case it names
     */
    public int plannedActionCount(int loopDepth, int actionsAlreadyTakenThisTrigger) {
        if (loopDepth > MAX_LOOP_DEPTH) {
            throw new IllegalStateException(
                    "loop depth " + loopDepth + " exceeds " + MAX_LOOP_DEPTH + " (INV-WRK-14). A rule chain "
                            + "this deep is a live-lock consuming the tenant's transaction budget, and it "
                            + "appears as the platform becoming slow rather than as a rule misbehaving.");
        }
        int remaining = executionBudgetPerTrigger - actionsAlreadyTakenThisTrigger;
        if (remaining <= 0) {
            throw new IllegalStateException(
                    "the execution budget of " + executionBudgetPerTrigger + " for this trigger is exhausted "
                            + "(INV-WRK-14). The budget is per TRIGGER, not per rule: a rule that is fine in "
                            + "isolation and pathological in combination is the common case.");
        }
        return Math.min(actions.size(), remaining);
    }

    /** Enables the rule. Refused where the owner cannot perform every action directly. */
    public void enable(Set<String> ownerPermissions) {
        List<Action> exceeding = actionsExceedingAuthority(ownerPermissions);
        if (!exceeding.isEmpty()) {
            throw new IllegalStateException(
                    "the owning principal cannot perform " + exceeding.size() + " of this rule's actions "
                            + "directly (INV-WRK-13): "
                            + exceeding.stream().map(Action::requiredPermission).sorted().toList()
                            + ". Enabling it would give the author authority they do not hold, through a "
                            + "mechanism no access review inspects.");
        }
        if (authoritySuspended) {
            throw new IllegalStateException(
                    "suspended: " + suspendedReason + ". Resolve the authority change first (SEC-AUZ-038).");
        }
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    /**
     * Suspends the rule because its owner's authority changed. {@code SEC-AUZ-038}.
     *
     * <p>A reason is required, and DOC-04 section 16.7 makes it a {@code CHECK} at the engine as well: a rule
     * that stopped working with no stated reason is a support ticket whose answer nobody can find.
     */
    public void suspendForAuthorityChange(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "a suspension requires a reason (SEC-AUZ-038, DOC-04 section 16.7). A rule that stopped "
                            + "working with no stated reason is a support ticket whose answer nobody can find.");
        }
        this.authoritySuspended = true;
        this.suspendedReason = reason;
        // Also disabled: leaving it enabled-but-suspended means two flags must agree for the rule to be safe,
        // and any read path that checked only one would run it.
        this.enabled = false;
    }

    public void clearSuspension(Set<String> ownerPermissions) {
        if (!actionsExceedingAuthority(ownerPermissions).isEmpty()) {
            throw new IllegalStateException(
                    "the owner still cannot perform every action directly; clearing the suspension would "
                            + "restore the escalation the suspension prevented");
        }
        this.authoritySuspended = false;
        this.suspendedReason = null;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public TriggerKind triggerKind() {
        return triggerKind;
    }

    public Optional<Condition> conditions() {
        return conditions;
    }

    public List<Action> actions() {
        return actions;
    }

    public UUID owningPrincipalId() {
        return owningPrincipalId;
    }

    public int executionBudgetPerTrigger() {
        return executionBudgetPerTrigger;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean authoritySuspended() {
        return authoritySuspended;
    }

    public Optional<String> suspendedReason() {
        return Optional.ofNullable(suspendedReason);
    }
}
