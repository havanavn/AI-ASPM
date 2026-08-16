package aspm.module.organizationscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.organizationscope.domain.ReorganizationSaga;
import aspm.sharedkernel.OrgNodeId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DOC-09 section 17, and {@code PRD-WRK-039} through {@code PRD-WRK-042}. */
class ReorganizationSagaTest {

    private static final OrgNodeId SUBJECT = new OrgNodeId(UUID.randomUUID());
    private static final OrgNodeId OLD_PARENT = new OrgNodeId(UUID.randomUUID());
    private static final OrgNodeId NEW_PARENT = new OrgNodeId(UUID.randomUUID());

    private static ReorganizationSaga saga() {
        return new ReorganizationSaga(
                ReorganizationSaga.Operation.MOVE, SUBJECT, NEW_PARENT, OLD_PARENT);
    }

    @Test
    @DisplayName("the happy path follows DOC-09 section 17 exactly")
    void happyPath() {
        var s = saga();
        assertEquals(ReorganizationSaga.State.VALIDATING, s.state());
        s.versionIncrementedTo(8L);
        s.beginReparenting();
        s.reparented();
        s.closureRebuilt();
        assertEquals(ReorganizationSaga.State.COMPLETED, s.state());
        assertTrue(s.state().isTerminal());
    }

    @Test
    @DisplayName("PRD-WRK-039: a rejection happens before any mutation, so no compensation is defined")
    void rejectionNeedsNoCompensation() {
        var s = saga();
        s.reject("target parent type does not permit the moved node's type (INV-ORG-06)");
        assertEquals(ReorganizationSaga.State.REJECTED, s.state());
        assertEquals(-1L, s.incrementedVersion(),
                "validation precedes the version increment, so a rejected reorganization consumes no "
                        + "version at all");
    }

    @Test
    @DisplayName("compensation cannot be entered from VALIDATING, where nothing has been mutated")
    void cannotCompensateBeforeMutating() {
        var s = saga();
        assertThrows(IllegalStateException.class, () -> s.failed("spurious"),
                "entering compensation with nothing mutated would run a rebuild nobody needed and would "
                        + "consume a hierarchy version for no structural change");
    }

    @Test
    @DisplayName("PRD-WRK-040: a rolled-back reorganization does NOT release its hierarchy version")
    void versionIsNotReused() {
        var s = saga();
        s.versionIncrementedTo(8L);
        s.beginReparenting();
        s.failed("write conflict on the parent row");
        s.compensated();

        assertEquals(ReorganizationSaga.State.ROLLED_BACK, s.state());
        assertEquals(8L, s.incrementedVersion(),
                "the version stays consumed. A reused version makes two different tree shapes share an "
                        + "identifier, which makes historical scope descriptors ambiguous — and they are "
                        + "the mechanism historical authorization depends on (PRD-WRK-040).");
        assertTrue(s.log().stream().anyMatch(l -> l.contains("NOT reused")),
                "the gap must be recorded so a later reader does not treat it as a defect");
    }

    @Test
    @DisplayName("PRD-WRK-041: a failed compensation blocks further reorganization for the tenant")
    void failedCompensationBlocksFurtherReorganization() {
        var s = saga();
        s.versionIncrementedTo(9L);
        s.beginReparenting();
        s.reparented();
        s.failed("closure rebuild timed out");
        s.compensationFailed("rebuild from org_node also failed: connection lost");

        assertEquals(ReorganizationSaga.State.MANUAL_INTERVENTION, s.state());
        assertFalse(s.permitsFurtherReorganization(),
                "a tenant with a partially reorganized tree has a corrupted authorization substrate; "
                        + "permitting a second reorganization on top would compound it beyond diagnosis "
                        + "(PRD-WRK-041)");
        assertTrue(s.state().isTerminal(),
                "there is deliberately no code path out of MANUAL_INTERVENTION — recovery is an operator "
                        + "procedure, and an automated exit would resume work on a corrupted tree");
    }

    @Test
    @DisplayName("the prior parent is captured at construction, before anything can fail")
    void priorParentIsCapturedUpFront() {
        var s = saga();
        s.versionIncrementedTo(10L);
        s.beginReparenting();
        s.failed("boom");
        assertEquals(OLD_PARENT, s.priorParentForCompensation(),
                "REPARENTING's compensation is 'restore prior parent'; a compensation that has to look up "
                        + "what it is restoring can be defeated by the same failure it is compensating for");
    }

    @Test
    @DisplayName("steps out of order are rejected rather than silently reordered")
    void stepsAreOrdered() {
        var s = saga();
        assertThrows(IllegalStateException.class, s::beginReparenting,
                "reparenting before the version increment would produce descriptors attributable to "
                        + "neither side of the operation");

        var t = saga();
        t.versionIncrementedTo(11L);
        assertThrows(IllegalStateException.class, t::closureRebuilt,
                "skipping REPARENTING would mark a move complete that never happened");
    }
}
