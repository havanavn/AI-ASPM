package aspm.module.workmanagement.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One state in a workflow definition. DOC-04 section 16.1.
 *
 * @param code immutable once the definition is activated. Reporting, saved views and automation rules reference
 *     a state by code, so a rename would silently repoint them
 * @param slaClockRunning whether the service level clock runs while an item sits here.
 *     <p><b>Stored per state rather than inferred from {@link #category}</b> (DOC-04 section 16.1): "Two states in
 *     the same category may differ: a tenant may treat one waiting state as their responsibility and another as
 *     the requester's." Inferring it from the category would make attribution wrong for exactly the states where
 *     attribution matters ({@code PRD-RSK-034}).
 */
public record WorkflowState(UUID id, String code, WorkflowStateCategory category, boolean slaClockRunning,
        int displayOrder) {

    public WorkflowState {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(code, "code is required");
        Objects.requireNonNull(category, "a category is required (PRD-WRK-038)");
        if (code.isBlank()) {
            throw new IllegalArgumentException("a state code cannot be blank");
        }
        if (category.isTerminal() && slaClockRunning) {
            // A terminal state has no outbound transition, so a running clock there would accrue forever and
            // breach every item that reached a successful outcome.
            throw new IllegalArgumentException(
                    "state '" + code + "' is TERMINAL with the clock running. Nothing leaves a terminal state, "
                            + "so the clock would accrue indefinitely and breach items that finished.");
        }
    }
}
