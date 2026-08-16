package aspm.module.workmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.kernel.rulesengine.contract.Condition;
import aspm.kernel.rulesengine.contract.FactValue;
import aspm.kernel.rulesengine.contract.Operator;
import aspm.module.workmanagement.domain.ActorType;
import aspm.module.workmanagement.domain.AutomationRule;
import aspm.module.workmanagement.domain.BulkOperation;
import aspm.module.workmanagement.domain.SavedView;
import aspm.module.workmanagement.domain.TransitionBlockingAttribution;
import aspm.module.workmanagement.domain.TransitionLog;
import aspm.module.workmanagement.domain.WorkItem;
import aspm.module.workmanagement.domain.WorkflowDefinition;
import aspm.module.workmanagement.domain.WorkflowState;
import aspm.module.workmanagement.domain.WorkflowStateCategory;
import aspm.module.workmanagement.domain.WorkflowTransition;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.ScopeDescriptor;
import aspm.sharedkernel.TenantId;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Prompt 9 session 3 — the {@code WorkItem} aggregate, automation, saved views, bulk, and the concurrency rows
 * of DOC-09 section 18.
 */
class WorkItemAndAutomationTest {

    private static final Instant T0 = Instant.parse("2026-07-01T09:00:00Z");
    private static final UUID TYPE = new UUID(90, 2);
    private static final UUID OPEN = new UUID(91, 1);
    private static final UUID IN_PROGRESS = new UUID(91, 2);
    private static final UUID BLOCKED = new UUID(91, 3);
    private static final UUID DONE = new UUID(91, 4);
    private static final UUID ACTOR = new UUID(94, 1);
    private static final UUID OTHER = new UUID(94, 2);

    private static final OrgNodeId SUBJECT_NODE = new OrgNodeId(new UUID(99, 1));
    private static final OrgNodeId CREATOR_NODE = new OrgNodeId(new UUID(99, 2));

    private static ScopeDescriptor scopeAt(OrgNodeId node) {
        return new ScopeDescriptor(new TenantId(new UUID(1, 1)), node, List.of(node),
                new UUID(2, 1), new UUID(3, 1), T0, 1L);
    }

    private static WorkflowDefinition activeWorkflow() {
        var definition = new WorkflowDefinition(UUID.randomUUID(), TYPE, 7,
                WorkflowDefinition.SubjectMachine.WORK_ITEM, OPEN,
                List.of(new WorkflowState(OPEN, "OPEN", WorkflowStateCategory.OPEN, true, 1),
                        new WorkflowState(IN_PROGRESS, "IN_PROGRESS", WorkflowStateCategory.IN_PROGRESS,
                                true, 2),
                        new WorkflowState(BLOCKED, "BLOCKED", WorkflowStateCategory.WAITING_EXTERNAL,
                                false, 3),
                        new WorkflowState(DONE, "DONE", WorkflowStateCategory.TERMINAL, false, 4)),
                List.of(new WorkflowTransition(UUID.randomUUID(), OPEN, IN_PROGRESS, "start",
                                Optional.empty(), List.of(), Optional.empty(), List.of(), false),
                        new WorkflowTransition(UUID.randomUUID(), IN_PROGRESS, BLOCKED, "block",
                                Optional.empty(), List.of(), Optional.empty(), List.of(), false),
                        new WorkflowTransition(UUID.randomUUID(), BLOCKED, IN_PROGRESS, "unblock",
                                Optional.empty(), List.of(), Optional.empty(), List.of(), false),
                        new WorkflowTransition(UUID.randomUUID(), IN_PROGRESS, DONE, "complete",
                                Optional.empty(), List.of(), Optional.empty(), List.of(), false)));
        definition.activate(T0);
        return definition;
    }

    private static WorkItem itemAboutAFinding() {
        return WorkItem.createFor(UUID.randomUUID(), "WRK-1", TYPE, activeWorkflow(),
                WorkItem.SubjectKind.FINDING, new UUID(100, 1), scopeAt(SUBJECT_NODE),
                "Remediate CVE-2026-0001", ACTOR, T0);
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("The WorkItem aggregate")
    class Aggregate {

        @Test
        @DisplayName("INV-WRK-06: scope comes from the subject, and there is no way to set it afterwards")
        void scopeComesFromTheSubject() {
            var item = itemAboutAFinding();
            assertEquals(SUBJECT_NODE, item.scope().owningNodeId(),
                    "taking the creator's scope is one line shorter and lets a broad-scope user create an item "
                            + "nobody in the subject's own tree can see");

            for (Method m : WorkItem.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("scope") && (name.startsWith("set") || name.startsWith("update")),
                        "found " + m.getName() + ". PRD-WRK-042: reorganization must not modify scope "
                                + "descriptors, because they record the scope as it WAS and that is what makes "
                                + "historical reporting reproducible.");
            }
        }

        @Test
        @DisplayName("standalone work is a separate factory, so the one exception to INV-WRK-06 is explicit")
        void standaloneIsASeparateFactory() {
            var standalone = WorkItem.createStandalone(UUID.randomUUID(), "WRK-2", TYPE, activeWorkflow(),
                    scopeAt(CREATOR_NODE), "Quarterly threat model review", ACTOR, T0);
            assertEquals(WorkItem.SubjectKind.NONE, standalone.subjectKind());
            assertEquals(CREATOR_NODE, standalone.scope().owningNodeId(),
                    "there is no subject to derive from, so the creator supplies it — and the separate factory "
                            + "is what makes that visible at the call site rather than inferred from a null");

            assertThrows(IllegalArgumentException.class,
                    () -> WorkItem.createFor(UUID.randomUUID(), "WRK-3", TYPE, activeWorkflow(),
                            WorkItem.SubjectKind.NONE, null, scopeAt(CREATOR_NODE), "t", ACTOR, T0));
        }

        @Test
        @DisplayName("INV-WRK-16: a subject kind requires a subject identifier")
        void subjectReferenceIsComplete() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> WorkItem.createFor(UUID.randomUUID(), "WRK-4", TYPE, activeWorkflow(),
                            WorkItem.SubjectKind.FINDING, null, scopeAt(SUBJECT_NODE), "t", ACTOR, T0));
            assertTrue(ex.getMessage().contains("INV-WRK-16"),
                    "without it the object cannot expose its associated work, and the bidirectional link "
                            + "exists in one direction only");
        }

        @Test
        @DisplayName("INV-WRK-01: the definition version is pinned at creation with no setter")
        void definitionVersionIsPinned() {
            var item = itemAboutAFinding();
            assertEquals(7, item.workflowDefinitionVersion());
            for (Method m : WorkItem.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("set") && name.contains("workflow"),
                        "found " + m.getName() + "; a workflow change must not strand in-flight items");
            }
        }

        @Test
        @DisplayName("an item cannot be created against a DRAFT or RETIRED definition")
        void definitionMustBeActive() {
            var draft = new WorkflowDefinition(UUID.randomUUID(), TYPE, 1,
                    WorkflowDefinition.SubjectMachine.WORK_ITEM, OPEN,
                    List.of(new WorkflowState(OPEN, "OPEN", WorkflowStateCategory.OPEN, true, 1),
                            new WorkflowState(DONE, "DONE", WorkflowStateCategory.TERMINAL, false, 2)),
                    List.of(new WorkflowTransition(UUID.randomUUID(), OPEN, DONE, "complete",
                            Optional.empty(), List.of(), Optional.empty(), List.of(), false)));

            assertThrows(IllegalArgumentException.class,
                    () -> WorkItem.createStandalone(UUID.randomUUID(), "WRK-5", TYPE, draft,
                            scopeAt(CREATOR_NODE), "t", ACTOR, T0),
                    "a DRAFT definition has not been validated (INV-WRK-02)");
        }

        @Test
        @DisplayName("INV-WRK-05: assignment is a single field, so a second assignee is unrepresentable")
        void assignmentIsSingular() {
            var item = itemAboutAFinding();
            item.assignTo(ACTOR, item.rowVersion(), T0.plusSeconds(60));
            assertEquals(Optional.of(ACTOR), item.assigneeId());

            item.assignTo(OTHER, item.rowVersion(), T0.plusSeconds(120));
            assertEquals(Optional.of(OTHER), item.assigneeId(),
                    "reassignment replaces; an item assigned to three people is assigned to nobody");

            for (Method m : WorkItem.class.getMethods()) {
                if (m.getName().equals("assigneeId")) {
                    assertEquals(Optional.class, m.getReturnType(),
                            "a collection return type here would be INV-WRK-05 violated in the signature");
                }
            }
        }

        @Test
        @DisplayName("INV-WRK-15: manual effort never overwrites derived effort")
        void effortFieldsAreSeparate() {
            var item = itemAboutAFinding();
            var log = new TransitionLog(item.id());
            log.recordCreation(UUID.randomUUID(), OPEN, ACTOR, ActorType.USER, T0, true);
            // Two days running, then one day blocked with the clock stopped.
            log.append(UUID.randomUUID(), IN_PROGRESS, "start", ACTOR, ActorType.USER, null, null,
                    T0.plus(Duration.ofDays(2)), true, null, false, false);
            log.append(UUID.randomUUID(), BLOCKED, "block", ACTOR, ActorType.USER, null, null,
                    T0.plus(Duration.ofDays(3)), true, TransitionBlockingAttribution.THIRD_PARTY, false, true);
            log.append(UUID.randomUUID(), IN_PROGRESS, "unblock", ACTOR, ActorType.USER, null, null,
                    T0.plus(Duration.ofDays(5)), false, null, false, false);

            item.recomputeDerivedEffort(log, item.rowVersion(), T0.plus(Duration.ofDays(5)));
            assertEquals(0, item.effort().derivedDays().compareTo(new BigDecimal("3.00")),
                    "only clock-running time counts: charging a team for time somebody else blocked makes the "
                            + "figure an argument rather than a measurement. Got " + item.effort());
            assertFalse(item.effort().manuallyAdjusted());

            item.recordManualEffort(new BigDecimal("4.50"), item.rowVersion(), T0.plus(Duration.ofDays(6)));
            var figure = item.effort();
            assertTrue(figure.manuallyAdjusted());
            assertEquals(0, figure.days().compareTo(new BigDecimal("4.50")));
            assertEquals(0, figure.derivedDays().compareTo(new BigDecimal("3.00")),
                    "the derived figure survives the adjustment; overwriting it destroys the comparison that "
                            + "makes the adjustment meaningful (INV-WRK-15, ADR-021)");

            // And recomputing does not clear the adjustment.
            item.recomputeDerivedEffort(log, item.rowVersion(), T0.plus(Duration.ofDays(7)));
            assertTrue(item.effort().manuallyAdjusted());
        }

        @Test
        @DisplayName("the effort figure says whether it was adjusted, so it cannot be presented as measured")
        void effortFigureCarriesItsProvenance() {
            var item = itemAboutAFinding();
            item.recordManualEffort(new BigDecimal("2"), item.rowVersion(), T0.plusSeconds(60));
            assertTrue(item.effort().manuallyAdjusted(),
                    "PRD-RSK-039 makes estimation bias reportable, which needs the two distinguishable");
        }

        @Test
        @DisplayName("negative manual effort is refused")
        void negativeEffortRefused() {
            var item = itemAboutAFinding();
            assertThrows(IllegalArgumentException.class,
                    () -> item.recordManualEffort(new BigDecimal("-3"), item.rowVersion(), T0),
                    "negative effort is not a correction, it is an attempt to reduce a total, and a capacity "
                            + "model built on it would understate load");
        }

        @Test
        @DisplayName("an item cannot be its own parent")
        void selfParentRefused() {
            var item = itemAboutAFinding();
            assertThrows(IllegalArgumentException.class,
                    () -> item.reparent(item.id(), item.rowVersion(), T0.plusSeconds(60)));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-09 section 18 — concurrency")
    class Concurrency {

        @Test
        @DisplayName("row 2: two different transitions from the same state — the loser gets 412")
        void concurrentTransitionsConflict() {
            var item = itemAboutAFinding();
            int readByBoth = item.rowVersion();

            item.applyTransition(IN_PROGRESS, readByBoth, T0.plusSeconds(60));

            var conflict = assertThrows(WorkItem.StaleWriteException.class,
                    () -> item.applyTransition(DONE, readByBoth, T0.plusSeconds(61)));
            assertEquals(readByBoth, conflict.expected());
            assertEquals(readByBoth + 1, conflict.actual());
            assertTrue(conflict.getMessage().contains("Neither change is discarded"),
                    "silent last-write-wins loses work and loses it invisibly — the person whose transition "
                            + "vanished believes it succeeded (PRD-WRK-043)");
        }

        @Test
        @DisplayName("row 4: a field edit concurrent with a transition — one receives 412")
        void fieldEditConcurrentWithTransition() {
            var item = itemAboutAFinding();
            int readByEditor = item.rowVersion();
            item.applyTransition(IN_PROGRESS, readByEditor, T0.plusSeconds(60));

            assertThrows(WorkItem.StaleWriteException.class,
                    () -> item.retitle("a better title", readByEditor, T0.plusSeconds(61)),
                    "both use row_version; one receives 412 (DOC-09 section 18)");
        }

        @Test
        @DisplayName("row 5: a comment concurrent with a transition — independent aggregates, both succeed")
        void commentConcurrentWithTransitionBothSucceed() {
            var item = itemAboutAFinding();
            int readByCommenter = item.rowVersion();
            item.applyTransition(IN_PROGRESS, readByCommenter, T0.plusSeconds(60));

            // A comment is its own aggregate root and does not take the item's row version. That it compiles
            // without one IS the assertion: coupling them would make every comment a transition conflict.
            var comment = aspm.module.workmanagement.domain.Comment.post(UUID.randomUUID(), item.id(), null,
                    aspm.module.workmanagement.domain.ConstrainedRichText.of(List.of(
                            new aspm.module.workmanagement.domain.ConstrainedRichText.Node.Paragraph(
                                    List.of(new aspm.module.workmanagement.domain.ConstrainedRichText.Node
                                            .Text("on it"))))),
                    OTHER, T0.plusSeconds(61));
            assertEquals(item.id(), comment.workItemId());
        }

        @Test
        @DisplayName("every mutating method takes an expected row version")
        void allMutationsAreVersionChecked() {
            List<String> mutators = List.of("applyTransition", "assignTo", "retitle", "describe",
                    "setAttribute", "reparent", "recomputeDerivedEffort", "recordManualEffort", "estimate");
            for (Method m : WorkItem.class.getMethods()) {
                if (!mutators.contains(m.getName())) {
                    continue;
                }
                boolean takesVersion = false;
                for (Class<?> parameter : m.getParameterTypes()) {
                    takesVersion |= parameter == int.class;
                }
                assertTrue(takesVersion,
                        m.getName() + " mutates without a version check. A single unchecked write path is "
                                + "enough for the silent loss PRD-WRK-021 forbids, because that is the path the "
                                + "next feature will use.");
            }
        }

        @Test
        @DisplayName("a successful write advances the version, so a retry of the same request also conflicts")
        void versionAdvancesOnEveryWrite() {
            var item = itemAboutAFinding();
            int before = item.rowVersion();
            item.assignTo(ACTOR, before, T0.plusSeconds(60));
            assertEquals(before + 1, item.rowVersion());
            assertThrows(WorkItem.StaleWriteException.class,
                    () -> item.assignTo(ACTOR, before, T0.plusSeconds(61)),
                    "a replayed request must not apply twice silently");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-WRK-13 / INV-WRK-14 — automation")
    class Automation {

        private AutomationRule rule(String... permissions) {
            List<AutomationRule.Action> actions = new ArrayList<>();
            for (String permission : permissions) {
                actions.add(new AutomationRule.Action(AutomationRule.ActionKind.TRANSITION, permission, null));
            }
            return new AutomationRule(UUID.randomUUID(), "auto-close verified",
                    AutomationRule.TriggerKind.WORK_ITEM_TRANSITIONED,
                    new Condition.Comparison("state", Operator.EQUALS, new FactValue.Text("DONE")),
                    actions, ACTOR, AutomationRule.DEFAULT_EXECUTION_BUDGET);
        }

        @Test
        @DisplayName("a rule the owner could not perform directly cannot be enabled")
        void authorityCeilingBlocksEnable() {
            var r = rule("wrk.item.transition", "vul.finding.severity.adjust");
            var ex = assertThrows(IllegalStateException.class,
                    () -> r.enable(Set.of("wrk.item.transition")));
            assertTrue(ex.getMessage().contains("vul.finding.severity.adjust"),
                    "the diagnosis must name the action, because the remedy is either removing it or granting "
                            + "the owner the permission; got " + ex.getMessage());
            assertFalse(r.enabled(),
                    "enabling it would give the author authority they do not hold, through a mechanism no "
                            + "access review inspects (INV-WRK-13)");
        }

        @Test
        @DisplayName("a rule within its ceiling enables")
        void withinCeilingEnables() {
            var r = rule("wrk.item.transition");
            r.enable(Set.of("wrk.item.transition", "wrk.item.update"));
            assertTrue(r.enabled(), "the control must permit the legitimate case, or it proves nothing");
        }

        @Test
        @DisplayName("a rule is disabled on creation")
        void disabledOnCreation() {
            assertFalse(rule("wrk.item.transition").enabled(),
                    "a rule that ran the moment it was saved would act on the tenant's whole backlog before "
                            + "its author had read it back");
        }

        @Test
        @DisplayName("the ceiling is evaluated against the owner's CURRENT permissions, not a snapshot")
        void ceilingUsesCurrentPermissions() {
            var r = rule("wrk.item.transition");
            r.enable(Set.of("wrk.item.transition"));
            assertTrue(r.enabled());

            // The owner loses the permission. runnable() reflects it immediately, before the suspension event
            // has even been handled — which is what makes the sixty-second staleness of the flag survivable.
            assertFalse(r.runnable(Set.of()),
                    "a ceiling checked against a snapshot would keep granting authority the owner has since "
                            + "lost (SEC-AUZ-038)");
            assertEquals(1, r.actionsExceedingAuthority(Set.of()).size());
        }

        @Test
        @DisplayName("SEC-AUZ-038: suspension requires a reason and also disables")
        void suspensionRequiresAReasonAndDisables() {
            var r = rule("wrk.item.transition");
            r.enable(Set.of("wrk.item.transition"));

            assertThrows(IllegalArgumentException.class, () -> r.suspendForAuthorityChange("  "),
                    "a rule that stopped working with no stated reason is a support ticket whose answer "
                            + "nobody can find");

            r.suspendForAuthorityChange("owner left the engineering scope");
            assertTrue(r.authoritySuspended());
            assertFalse(r.enabled(),
                    "leaving it enabled-but-suspended means two flags must agree for the rule to be safe, and "
                            + "any read path checking only one would run it");
            assertThrows(IllegalStateException.class, () -> r.enable(Set.of("wrk.item.transition")));
        }

        @Test
        @DisplayName("a suspension cannot be cleared while the owner still lacks the authority")
        void suspensionNotClearableWhileExceeding() {
            var r = rule("wrk.item.transition");
            r.suspendForAuthorityChange("owner left the engineering scope");
            assertThrows(IllegalStateException.class, () -> r.clearSuspension(Set.of()),
                    "clearing it would restore the escalation the suspension prevented");
            r.clearSuspension(Set.of("wrk.item.transition"));
            assertFalse(r.authoritySuspended());
        }

        @Test
        @DisplayName("INV-WRK-14: the loop guard stops a chain, loudly")
        void loopGuardStops() {
            var r = rule("wrk.item.transition");
            assertEquals(1, r.plannedActionCount(AutomationRule.MAX_LOOP_DEPTH, 0),
                    "a chain at the limit still runs; the guard is on exceeding it");
            var ex = assertThrows(IllegalStateException.class,
                    () -> r.plannedActionCount(AutomationRule.MAX_LOOP_DEPTH + 1, 0));
            assertTrue(ex.getMessage().contains("live-lock"),
                    "it appears as the platform becoming slow rather than as a rule misbehaving, which is why "
                            + "the message says so");
        }

        @Test
        @DisplayName("INV-WRK-14: the budget is per TRIGGER, not per rule")
        void budgetIsPerTrigger() {
            var r = rule("wrk.item.transition");
            assertThrows(IllegalStateException.class,
                    () -> r.plannedActionCount(0, AutomationRule.DEFAULT_EXECUTION_BUDGET),
                    "a rule that is fine in isolation and pathological in combination is the common case");
            assertEquals(1, r.plannedActionCount(0, AutomationRule.DEFAULT_EXECUTION_BUDGET - 1),
                    "and a partial budget still permits what fits");
        }

        @Test
        @DisplayName("an action must declare the permission it requires")
        void actionsDeclareTheirPermission() {
            assertThrows(NullPointerException.class,
                    () -> new AutomationRule.Action(AutomationRule.ActionKind.ASSIGN, null, null),
                    "an action whose permission is unknown cannot be checked against the owner's authority, "
                            + "and an unenforceable ceiling reads as enforced");
        }

        @Test
        @DisplayName("a rule with no owning principal cannot be constructed")
        void ownerIsRequired() {
            assertThrows(NullPointerException.class,
                    () -> new AutomationRule(UUID.randomUUID(), "x",
                            AutomationRule.TriggerKind.SCHEDULE, null,
                            List.of(new AutomationRule.Action(AutomationRule.ActionKind.ASSIGN, "p", null)),
                            null, 10));
        }

        @Test
        @DisplayName("PRD-WRK-044: an execution records its denials with reasons")
        void executionsRecordDenials() {
            var execution = new AutomationRule.Execution(UUID.randomUUID(), Optional.of(UUID.randomUUID()),
                    3, 2, 1, 0, T0, List.of("owner cannot vul.finding.severity.adjust"));
            assertTrue(execution.escalationAttemptSignal(),
                    "a rule repeatedly attempting actions its owner cannot perform is either a "
                            + "misconfiguration or an escalation attempt (SEC-AUZ-037)");

            assertThrows(IllegalArgumentException.class,
                    () -> new AutomationRule.Execution(UUID.randomUUID(), Optional.empty(), 3, 2, 1, 0, T0,
                            List.of()),
                    "a denial without a reason is undiagnosable, and the rule appears to run");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-WRK-11 / INV-WRK-12 — saved views and bulk")
    class ViewsAndBulk {

        @Test
        @DisplayName("a saved view stores no scope and no result set")
        void savedViewStoresNeitherScopeNorResults() {
            for (var field : SavedView.class.getDeclaredFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("result") || name.contains("cached")
                                || name.equals("scope") || name.contains("authorscope"),
                        "found field " + field.getName() + ". Storing the author's scope or results makes a "
                                + "shared link carry the author's visibility — a scope escalation available to "
                                + "anyone with the link (INV-WRK-11).");
            }
            for (Method m : SavedView.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("result") || name.contains("evaluate"),
                        "found " + m.getName() + "; the view holds filters only and the viewer's scope is "
                                + "applied at query time");
            }
        }

        @Test
        @DisplayName("SHARED_SCOPE names who may open the view, never what it returns")
        void sharedScopeIsAboutAccessNotContents() {
            var view = new SavedView(UUID.randomUUID(), "critical open work", ACTOR,
                    new Condition.Comparison("band", Operator.EQUALS, new FactValue.Text("CRITICAL")));

            view.share(SavedView.Sharing.SHARED_SCOPE, new UUID(99, 5));
            assertEquals(Optional.of(new UUID(99, 5)), view.sharedScopeNodeId());

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> view.share(SavedView.Sharing.SHARED_TENANT, new UUID(99, 5)));
            assertTrue(ex.getMessage().contains("INV-WRK-11"),
                    "a scope node on tenant-wide sharing suggests the node bounds what the view returns; the "
                            + "viewer's scope does");
        }

        @Test
        @DisplayName("INV-WRK-12: permission is evaluated per item, and the signature enforces it")
        void bulkEvaluatesPerItem() {
            var permitted = new UUID(101, 1);
            var refused = new UUID(101, 2);
            List<UUID> checked = new ArrayList<>();

            var outcome = BulkOperation.apply(List.of(permitted, refused),
                    id -> {
                        checked.add(id);
                        return id.equals(permitted);
                    },
                    id -> "assigned");

            assertEquals(List.of(permitted, refused), checked,
                    "the shortcut — check once, then apply to the selected identifiers — turns a "
                            + "client-supplied list into a cross-scope write (INV-WRK-12)");
            assertEquals(1, outcome.applied());
            assertEquals(1, outcome.refused());
        }

        @Test
        @DisplayName("partial application is the correct outcome, and every item is audited")
        void partialApplicationAndPerItemAudit() {
            var ids = List.of(new UUID(101, 1), new UUID(101, 2), new UUID(101, 3));
            var outcome = BulkOperation.apply(ids, id -> !id.equals(new UUID(101, 2)), id -> "assigned");

            assertEquals(2, outcome.applied());
            assertEquals(3, outcome.auditRecords().size(),
                    "a single batch-level record makes 'was this finding's work item modified on the third' "
                            + "unanswerable, and refusals are the escalation-attempt signal of SEC-AUZ-037");
            assertTrue(outcome.auditRecords().stream()
                            .anyMatch(o -> o.result() == BulkOperation.ItemOutcome.Result.REFUSED),
                    "all-or-nothing would let one unauthorized identifier block legitimate work");
        }

        @Test
        @DisplayName("a refusal says only 'not permitted'")
        void refusalIsOpaque() {
            var outcome = BulkOperation.apply(List.of(new UUID(101, 9)), id -> false, id -> "assigned");
            assertEquals("not permitted", outcome.auditRecords().get(0).detail(),
                    "distinguishing out-of-scope from insufficient-permission here would turn a bulk endpoint "
                            + "into the object-existence oracle the transition evaluation order removes");
        }

        @Test
        @DisplayName("a stale item within a batch is reported as stale, not as a failure of the batch")
        void staleItemWithinABatch() {
            var item = itemAboutAFinding();
            // A REAL stale write, not a fabricated exception: StaleWriteException's constructor is
            // package-private so that only the aggregate can claim a conflict occurred. The batch holds a
            // version read before somebody else moved the item, which is exactly the situation.
            int versionAtSelection = item.rowVersion();
            item.assignTo(OTHER, versionAtSelection, T0.plusSeconds(30));

            var outcome = BulkOperation.apply(List.of(item.id(), new UUID(101, 4)), id -> true,
                    id -> {
                        if (id.equals(item.id())) {
                            item.assignTo(ACTOR, versionAtSelection, T0.plusSeconds(60));
                        }
                        return "assigned";
                    });
            assertEquals(1, outcome.stale());
            assertEquals(1, outcome.applied(),
                    "one item moving between selection and application must not discard the rest");
        }

        @Test
        @DisplayName("an oversized batch is rejected, not truncated")
        void oversizedBatchRejected() {
            List<UUID> tooMany = new ArrayList<>();
            for (int i = 0; i <= BulkOperation.MAX_BATCH; i++) {
                tooMany.add(new UUID(102, i));
            }
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> BulkOperation.apply(tooMany, id -> true, id -> "assigned"));
            assertTrue(ex.getMessage().contains("truncated"),
                    "a silently truncated bulk operation reports success for items it never touched");
        }
    }
}
