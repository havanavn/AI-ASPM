package aspm.module.workmanagement.domain;

import java.util.List;
import java.util.Objects;

/**
 * The pre-activation validation of {@code PRD-WRK-034} and {@code INV-WRK-02}.
 *
 * <p>Four checks, each naming a specific way a workflow is silently broken:
 *
 * <ol>
 *   <li><b>Every state reachable from the initial state.</b> An unreachable state is dead configuration that
 *       appears in every board view and every filter.
 *   <li><b>At least one terminal state.</b> Without one nothing can finish, and every metric that counts
 *       completions reports zero forever.
 *   <li><b>Every non-terminal state has an outbound transition.</b> {@code PRD-WRK-034}'s rationale names this
 *       as the worst of the four: "A state with no outbound transition is a trap: items enter and cannot leave,
 *       and the defect surfaces days later as stalled work with no visible cause."
 *   <li><b>No transition references a state outside the definition.</b> A dangling reference fails at transition
 *       time, on a real item, in front of a user.
 * </ol>
 *
 * <p><b>Why validation is a value and not a boolean.</b> An activation refused with "invalid" leaves a tenant
 * administrator editing a state machine by guesswork. Each finding names the state or transition, so the
 * diagnosis points at the thing to fix.
 */
public record WorkflowValidation(List<Finding> findings) {

    /** One validation failure. {@code subject} is the state code or event code at fault. */
    public record Finding(Kind kind, String subject, String detail) {

        public enum Kind {
            /** Check 1. */
            UNREACHABLE_STATE,
            /** Check 2. */
            NO_TERMINAL_STATE,
            /** Check 3 — the trap. */
            DEAD_END_STATE,
            /** Check 4. */
            DANGLING_STATE_REFERENCE,
            /** The initial state must itself be one of the definition's states. */
            INITIAL_STATE_NOT_DEFINED,
            /** Two states or two transitions sharing an identity make resolution order-dependent. */
            DUPLICATE_DEFINITION,
            /**
             * A transition into a non-success terminal state that does not require a reason.
             *
             * <p>DOC-09 section 3: a reason is "always [required] on any transition to a terminal state that is
             * not a success outcome". Validated here rather than trusted from the flag, because an unexplained
             * rejection or cancellation is the record nobody can review afterwards.
             */
            TERMINAL_WITHOUT_REASON
        }
    }

    public WorkflowValidation {
        findings = List.copyOf(Objects.requireNonNull(findings, "findings are required, possibly empty"));
    }

    /** Whether activation is permitted. {@code INV-WRK-02} makes an unvalidated activation unrepresentable. */
    public boolean activatable() {
        return findings.isEmpty();
    }

    /** A diagnosis naming every fault, so a tenant administrator is not left editing by guesswork. */
    public String diagnosis() {
        if (findings.isEmpty()) {
            return "valid";
        }
        StringBuilder out = new StringBuilder("workflow cannot be activated (PRD-WRK-034, INV-WRK-02): ");
        for (int i = 0; i < findings.size(); i++) {
            if (i > 0) {
                out.append("; ");
            }
            Finding f = findings.get(i);
            out.append(f.kind()).append(" [").append(f.subject()).append("] ").append(f.detail());
        }
        return out.toString();
    }
}
