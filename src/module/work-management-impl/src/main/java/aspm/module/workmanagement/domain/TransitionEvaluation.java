package aspm.module.workmanagement.domain;

import aspm.kernel.rulesengine.contract.ConditionEvaluation;
import aspm.kernel.rulesengine.contract.FactSet;
import aspm.kernel.rulesengine.contract.RuleOutcome;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The transition evaluation order of DOC-09 section 2.1. {@code PRD-WRK-031}.
 *
 * <p><b>The order is the control, not a performance choice.</b> Scope precedes permission so that a permission
 * denial cannot confirm an out-of-scope object exists ({@code SEC-AUZ-020}), and a scope failure is reported as
 * <i>not found</i> rather than <i>forbidden</i>. Reversing steps 2 and 4 would leave every check present and
 * turn the endpoint into an object-existence oracle — the failure this product exists to find in customers'
 * software.
 *
 * <ol>
 *   <li>Tenant context established ({@code SEC-TEN-005})
 *   <li>Object within the caller's scope — <b>a failure here returns 404, not 403</b>
 *   <li>Transition exists from the current state for this event
 *   <li>Caller holds the transition's {@code required_permission}
 *   <li>Caller's authority is not exceeded — an automation rule cannot exceed its owner ({@code INV-WRK-13})
 *   <li>Separation-of-duties constraints satisfied at action time ({@code SEC-AUZ-039})
 *   <li>Required fields present
 *   <li>Guard expression evaluates true
 *   <li>Domain invariants hold
 *   <li>Effects applied, transition recorded, events published, all in one transaction
 * </ol>
 *
 * <p>This class covers steps 2 through 8 and reports which step denied. Step 1 is established by the caller's
 * session before any of this runs; steps 9 and 10 belong to the work item aggregate and its transaction, because
 * {@code PRD-WRK-032} requires them to be atomic together and a pure decision function cannot carry a
 * transaction.
 *
 * <p><b>Deliberately pure.</b> It reads no repository and writes nothing, so the decision is reproducible from
 * its inputs — which is what lets a denial be explained to the person who received it.
 */
public final class TransitionEvaluation {

    /** Where evaluation stopped. The ordinal is the step number of DOC-09 section 2.1. */
    public enum Step {
        SCOPE(2),
        TRANSITION_AVAILABLE(3),
        PERMISSION(4),
        AUTHORITY_CEILING(5),
        SEPARATION_OF_DUTIES(6),
        REQUIRED_FIELDS(7),
        GUARD(8),
        PERMITTED(0);

        private final int stepNumber;

        Step(int stepNumber) {
            this.stepNumber = stepNumber;
        }

        public int stepNumber() {
            return stepNumber;
        }
    }

    /**
     * The outcome.
     *
     * @param httpStatus what the API layer returns. <b>404 for a scope failure</b>, 409 for an unavailable
     *     transition, 403 for the authorization steps, 422 for fields and guards. Carried here rather than
     *     derived at the edge, because the mapping is the disclosure control and deriving it twice is how the
     *     two copies come to differ
     * @param detail safe to return to the caller. A scope denial says nothing beyond not-found; anything more
     *     would restore the oracle the ordering removes
     */
    public record Decision(Step deniedAt, int httpStatus, String detail) {

        public boolean permitted() {
            return deniedAt == Step.PERMITTED;
        }

        static Decision permit() {
            return new Decision(Step.PERMITTED, 200, "permitted");
        }
    }

    /**
     * What the caller brings to the decision.
     *
     * @param withinScope resolved from the object's scope descriptor against the caller's grants, <b>never
     *     asserted by the client</b> (PP-4)
     * @param heldPermissions the caller's effective permissions for this object
     * @param automationOwnerPermissions where the actor is an automation rule, the owning principal's
     *     permissions. Empty for a human actor. {@code INV-WRK-13}: a rule "may not effect a change its owning
     *     principal could not perform directly"
     * @param conflictingPermissionsHeld separation-of-duties conflicts that apply to this action
     *     ({@code SEC-AUZ-039}), evaluated at action time and not only at grant time — grant-time enforcement
     *     alone "is defeated by two roles that are individually compliant and jointly conflicting"
     * @param presentFields the fields carrying a value on the item after the requested edit
     * @param facts the fact set the guard is evaluated against
     */
    public record Request(boolean withinScope, Set<String> heldPermissions,
            Optional<Set<String>> automationOwnerPermissions, Set<String> conflictingPermissionsHeld,
            Set<String> presentFields, FactSet facts, ActorType actorType) {

        public Request {
            heldPermissions = Set.copyOf(Objects.requireNonNull(heldPermissions, "heldPermissions is required"));
            Objects.requireNonNull(automationOwnerPermissions, "automationOwnerPermissions is required");
            conflictingPermissionsHeld = Set.copyOf(
                    Objects.requireNonNull(conflictingPermissionsHeld, "conflictingPermissionsHeld is required"));
            presentFields = Set.copyOf(Objects.requireNonNull(presentFields, "presentFields is required"));
            Objects.requireNonNull(facts, "a fact set is required, possibly empty");
            Objects.requireNonNull(actorType, "actorType is required");
            if (actorType == ActorType.AUTOMATION && automationOwnerPermissions.isEmpty()) {
                throw new IllegalArgumentException(
                        "an AUTOMATION request must carry its owning principal's permissions (INV-WRK-13). "
                                + "Without them the authority ceiling cannot be applied, and an automation rule "
                                + "is a privilege escalation mechanism no access review would detect.");
            }
        }
    }

    private final ConditionEvaluation guardEvaluation;

    public TransitionEvaluation(ConditionEvaluation guardEvaluation) {
        this.guardEvaluation = Objects.requireNonNull(guardEvaluation,
                "a shared condition evaluation is required (CON-PLT-012). A module-local guard evaluator would "
                        + "be the fourth implementation DOC-02 section 6.2 rejects, and this one governs "
                        + "authorization-relevant transitions.");
    }

    /**
     * Evaluates steps 2 through 8, in order, denying at the first failure.
     *
     * @param currentStateId the item's state now. Together with {@code eventCode} this is step 3
     */
    public Decision evaluate(WorkflowDefinition definition, UUID currentStateId, String eventCode,
            Request request) {
        Objects.requireNonNull(definition, "a workflow definition is required");
        Objects.requireNonNull(currentStateId, "the current state is required");
        Objects.requireNonNull(eventCode, "an event code is required");
        Objects.requireNonNull(request, "a request is required");

        // Step 2 — scope, before everything except the tenant context. A 404, deliberately: "a scope failure
        // MUST be indistinguishable from non-existence" (PRD-WRK-031).
        if (!request.withinScope()) {
            return new Decision(Step.SCOPE, 404, "not found");
        }

        // Step 3.
        Optional<WorkflowTransition> candidate = definition.transitionFor(currentStateId, eventCode);
        if (candidate.isEmpty()) {
            // 409 STATE_TRANSITION_INVALID (DOC-09 section 3). Distinguishable from a denial because the caller
            // IS authorized to see this item — they have already passed the scope check.
            return new Decision(Step.TRANSITION_AVAILABLE, 409,
                    "STATE_TRANSITION_INVALID: no transition '" + eventCode + "' from the current state");
        }
        WorkflowTransition transition = candidate.get();

        // Step 4 — the transition's own required permission, on top of wrk.item.transition.
        Optional<String> required = transition.requiredPermission();
        if (required.isPresent() && !request.heldPermissions().contains(required.get())) {
            return new Decision(Step.PERMISSION, 403, "forbidden");
        }

        // Step 5 — the authority ceiling. Applied even where the rule's own permission set would allow it,
        // because the rule acts with its owner's authority and not its own.
        if (request.actorType() == ActorType.AUTOMATION && required.isPresent()
                && !request.automationOwnerPermissions().orElseThrow().contains(required.get())) {
            return new Decision(Step.AUTHORITY_CEILING, 403,
                    "the automation rule's owning principal cannot perform this transition directly "
                            + "(INV-WRK-13)");
        }

        // Step 6 — separation of duties at action time. Reported specifically: a principal denied here needs to
        // know which pair conflicted, because the remedy is an access change and not a retry.
        if (!request.conflictingPermissionsHeld().isEmpty()) {
            return new Decision(Step.SEPARATION_OF_DUTIES, 403,
                    "separation of duties (SEC-AUZ-039): conflicting permissions held — "
                            + new java.util.TreeSet<>(request.conflictingPermissionsHeld()));
        }

        // Step 7 — required fields.
        Set<String> missing = new LinkedHashSet<>(transition.requiredFields());
        missing.removeAll(request.presentFields());
        if (!missing.isEmpty()) {
            return new Decision(Step.REQUIRED_FIELDS, 422, "required field(s) missing: " + missing);
        }

        // Step 8 — the guard, last of the checks this class performs because it is the most expensive and the
        // least disclosure-sensitive.
        if (transition.guard().isPresent()) {
            RuleOutcome outcome = guardEvaluation.evaluate(transition.guard().get(), request.facts());
            if (outcome != RuleOutcome.TRUE) {
                // UNDEFINED denies. Treating a missing fact as false would be the same denial by accident; the
                // detail distinguishes them, because "the guard said no" and "the guard could not be evaluated"
                // need different fixes.
                return new Decision(Step.GUARD, 422,
                        outcome == RuleOutcome.UNDEFINED
                                ? "the guard could not be evaluated: a referenced fact is absent"
                                : "the guard condition is not satisfied");
            }
        }

        return Decision.permit();
    }
}
