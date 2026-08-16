package aspm.module.organizationscope.domain;

import aspm.sharedkernel.OrgNodeId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The reorganization saga of DOC-09 section 17.
 *
 * <p>"Not a state machine on an aggregate but a process across many, because a subtree is not one
 * aggregate" (DOC-03 section 7.3, DOC-02 section 9.4). A subtree can contain thousands of nodes, so
 * including descendants in the {@code OrgNode} boundary would serialize every operation beneath a
 * mid-tree node against its move.
 *
 * <p>Four requirements shape this class and each is expressed structurally rather than by convention:
 *
 * <ul>
 *   <li>{@code PRD-WRK-039} — validate the complete target structure <b>before any mutation</b>. Hence
 *       {@link State#VALIDATING} is the only entry state and it has no compensation, because nothing has
 *       been mutated.
 *   <li>{@code PRD-WRK-040} — a hierarchy version is <b>never reused</b> after a failure. Hence the
 *       version increment is not compensated: "a gap in versions is harmless; reuse would make snapshots
 *       ambiguous", and those snapshots are what historical authorization depends on.
 *   <li>{@code PRD-WRK-041} — where compensation fails, enter manual intervention and <b>block further
 *       reorganization for the tenant</b>. A tenant with a partially reorganized tree "has a corrupted
 *       authorization substrate; permitting a second reorganization on top would compound it beyond
 *       diagnosis".
 *   <li>{@code PRD-WRK-042} — scope descriptors on existing objects are <b>not modified</b>. This class
 *       never touches one; {@link aspm.sharedkernel.ScopeDescriptor} offers no mutator, so the guarantee
 *       is the type's, not this class's discipline.
 * </ul>
 */
public final class ReorganizationSaga {

    /** The states of DOC-09 section 17's diagram, exactly. */
    public enum State {
        VALIDATING,
        REJECTED,
        VERSION_INCREMENTED,
        REPARENTING,
        CLOSURE_REBUILDING,
        COMPLETED,
        COMPENSATING,
        ROLLED_BACK,
        /**
         * Terminal and blocking. {@code PRD-WRK-041}: blocks further reorganization for the tenant and
         * alerts. Deliberately has no outbound transition in this class — recovery is an operator
         * procedure, and a code path out of it would be a code path that resumes a corrupted tree.
         */
        MANUAL_INTERVENTION;

        public boolean isTerminal() {
            return this == REJECTED || this == COMPLETED || this == ROLLED_BACK
                    || this == MANUAL_INTERVENTION;
        }
    }

    /** What the saga is attempting. DOC-03 section 7.5's three operations. */
    public enum Operation {
        MOVE,
        MERGE,
        SPLIT
    }

    private final Operation operation;
    private final OrgNodeId subject;
    private final OrgNodeId targetParent;
    private final OrgNodeId priorParent;
    private final List<String> log = new ArrayList<>();

    private State state = State.VALIDATING;
    private long incrementedVersion = -1;

    public ReorganizationSaga(
            Operation operation, OrgNodeId subject, OrgNodeId targetParent, OrgNodeId priorParent) {
        this.operation = Objects.requireNonNull(operation, "operation is required");
        this.subject = Objects.requireNonNull(subject, "subject is required");
        this.targetParent = targetParent;
        // Recorded at construction, because REPARENTING's compensation is "restore prior parent" and a
        // compensation that has to look up what it is restoring can be defeated by the failure it is
        // compensating for.
        this.priorParent = priorParent;
    }

    public State state() {
        return state;
    }

    public Operation operation() {
        return operation;
    }

    public OrgNodeId subject() {
        return subject;
    }

    /** The version taken, or -1 where validation rejected before it was incremented. */
    public long incrementedVersion() {
        return incrementedVersion;
    }

    /** An ordered account of what happened, for the operator who reads it after a failure. */
    public List<String> log() {
        return List.copyOf(log);
    }

    /** Validation failed. Nothing was mutated, so there is nothing to compensate. */
    public void reject(String diagnosis) {
        require(State.VALIDATING);
        state = State.REJECTED;
        log.add("REJECTED: " + Objects.requireNonNull(diagnosis, "a rejection states its diagnosis"));
    }

    /**
     * Validation passed; the version is incremented before the structural change.
     *
     * <p>DOC-03 Figure 7.1: "the version increment precedes the structural change so that descriptors
     * resolved during the operation are attributable to one side of it." A descriptor resolved
     * mid-operation must belong to either the old shape or the new one, never to an indeterminate state.
     */
    public void versionIncrementedTo(long newVersion) {
        require(State.VALIDATING);
        if (newVersion < 1) {
            throw new IllegalArgumentException("hierarchy version is monotonic from 1");
        }
        incrementedVersion = newVersion;
        state = State.VERSION_INCREMENTED;
        log.add("VERSION_INCREMENTED to " + newVersion);
    }

    public void beginReparenting() {
        require(State.VERSION_INCREMENTED);
        state = State.REPARENTING;
        log.add("REPARENTING " + subject.value()
                + (targetParent == null ? " to root" : " to " + targetParent.value()));
    }

    public void reparented() {
        require(State.REPARENTING);
        state = State.CLOSURE_REBUILDING;
        log.add("CLOSURE_REBUILDING");
    }

    public void closureRebuilt() {
        require(State.CLOSURE_REBUILDING);
        state = State.COMPLETED;
        log.add("COMPLETED; OrgNodeMoved published");
    }

    /** A step after mutation failed. Compensation is entered from REPARENTING or CLOSURE_REBUILDING only. */
    public void failed(String cause) {
        if (state != State.REPARENTING && state != State.CLOSURE_REBUILDING) {
            throw new IllegalStateException(
                    "compensation is entered only from REPARENTING or CLOSURE_REBUILDING; from "
                            + state + " nothing has been mutated and REJECTED is the correct outcome");
        }
        log.add("FAILED in " + state + ": " + Objects.requireNonNull(cause, "a failure states its cause"));
        state = State.COMPENSATING;
    }

    /**
     * Compensation succeeded.
     *
     * <p>Note what is <b>not</b> reversed: the version increment. {@code PRD-WRK-040} — "a reused version
     * makes two different tree shapes share an identifier, which makes historical scope descriptors
     * ambiguous, and they are the mechanism historical authorization depends on". The gap is the correct
     * outcome and is recorded so a later reader does not treat it as a defect.
     */
    public void compensated() {
        require(State.COMPENSATING);
        state = State.ROLLED_BACK;
        log.add("ROLLED_BACK; hierarchy version " + incrementedVersion
                + " is consumed and NOT reused (PRD-WRK-040)");
    }

    /**
     * Compensation itself failed.
     *
     * <p>{@code PRD-WRK-041}. The tenant's tree is in an indeterminate state, so this is terminal and
     * blocking rather than retryable: a retry would be a second reorganization on a corrupted
     * authorization substrate.
     */
    public void compensationFailed(String cause) {
        require(State.COMPENSATING);
        state = State.MANUAL_INTERVENTION;
        log.add("MANUAL_INTERVENTION: compensation failed: "
                + Objects.requireNonNull(cause, "a compensation failure states its cause")
                + ". Further reorganization is blocked for this tenant (PRD-WRK-041).");
    }

    /**
     * Whether another reorganization may start for this tenant.
     *
     * <p>The blocking half of {@code PRD-WRK-041}. Expressed as a query on the saga rather than as a flag
     * elsewhere, so the authority on whether reorganization is permitted is the record of what happened.
     */
    public boolean permitsFurtherReorganization() {
        return state != State.MANUAL_INTERVENTION;
    }

    /** The prior parent a REPARENTING compensation restores. */
    public OrgNodeId priorParentForCompensation() {
        return priorParent;
    }

    private void require(State expected) {
        if (state != expected) {
            throw new IllegalStateException(
                    "reorganization step requires state " + expected + " but the saga is in " + state
                            + ". DOC-09 section 17's transitions are the whole of the permitted set; a step "
                            + "out of order would leave the tree in a shape no compensation is defined for.");
        }
    }
}
