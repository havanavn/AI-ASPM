package aspm.module.workmanagement.domain;

import aspm.kernel.rulesengine.contract.Condition;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One transition in a workflow definition. DOC-04 section 16.1.
 *
 * @param guard evaluated by the shared rules engine. Absent means unguarded
 * @param requiredFields checked before the guard, per the evaluation order of DOC-09 section 2.1 step 7
 * @param requiredPermission <b>authorization configuration living in the work management schema</b> (DOC-26 T9).
 *     DOC-04 section 16.1: "editing this column changes who can effect a transition without any change to a
 *     role", which is why {@code wrk.workflow.manage} is one of the three most consequential permissions in the
 *     catalogue. Absent means the type-level {@code wrk.item.transition} alone suffices
 * @param sideEffects drawn from a fixed catalogue (DOC-09 section 2.2). Notification is NOT among them
 *     ({@code PRD-WRK-037}) — transitions publish events and notification subscribes, because a notification
 *     failure inside a transition either fails the transition or is swallowed, and neither is acceptable
 * @param reasonRequired always true in effect for a transition into a non-success terminal state, which
 *     {@link WorkflowDefinition} enforces at validation rather than trusting the flag
 */
public record WorkflowTransition(UUID id, UUID fromStateId, UUID toStateId, String eventCode,
        Optional<Condition> guard, List<String> requiredFields, Optional<String> requiredPermission,
        List<SideEffect> sideEffects, boolean reasonRequired) {

    /**
     * The fixed side-effect catalogue of DOC-09 section 2.2.
     *
     * <p>Fixed, not tenant-extensible: "defining a side effect not in the catalogue" is listed among the
     * prohibited changes. A tenant-defined effect would be code authored through configuration by someone not
     * thinking about authorization — the same hazard {@code INV-WRK-13} addresses for automation rules.
     */
    public enum SideEffect {
        ASSIGN_TO_ACTOR,
        CLEAR_ASSIGNEE,
        SET_FIELD,
        ADD_LABEL,
        REMOVE_LABEL,
        ADD_WATCHER,
        CLOSE_CHILD_ITEMS
    }

    public WorkflowTransition {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(fromStateId, "fromStateId is required");
        Objects.requireNonNull(toStateId, "toStateId is required");
        Objects.requireNonNull(eventCode, "eventCode is required");
        Objects.requireNonNull(guard, "guard is required, empty where unguarded");
        Objects.requireNonNull(requiredPermission, "requiredPermission is required, empty where none");
        requiredFields = List.copyOf(Objects.requireNonNull(requiredFields, "requiredFields is required"));
        sideEffects = List.copyOf(Objects.requireNonNull(sideEffects, "sideEffects is required"));
        if (eventCode.isBlank()) {
            throw new IllegalArgumentException("an event code cannot be blank");
        }
        if (fromStateId.equals(toStateId)) {
            // A self-transition would write a log entry with zero duration and no state change, which makes
            // cycle-time analysis count an event that did not happen. PRD-WRK-036's "returning to a previously
            // occupied state is a distinct forward transition" is about a DIFFERENT state, not this one.
            throw new IllegalArgumentException(
                    "transition '" + eventCode + "' has the same from and to state. A self-transition records a "
                            + "zero-duration entry in the append-only log for a state change that did not "
                            + "happen, which makes flow analysis count it as movement.");
        }
    }
}
