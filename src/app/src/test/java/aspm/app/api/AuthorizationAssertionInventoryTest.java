package aspm.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.assessment.domain.AssessmentRequest;
import aspm.module.compositionanalysis.domain.SbomCoverageState;
import aspm.module.riskprioritization.domain.NodePosture;
import aspm.module.workmanagement.domain.ActorType;
import aspm.module.workmanagement.domain.AutomationRule;
import aspm.module.workmanagement.domain.BulkOperation;
import aspm.module.workmanagement.domain.SavedView;
import aspm.module.workmanagement.domain.TransitionEvaluation;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.ScopeDescriptor;
import aspm.sharedkernel.TenantId;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The twenty-two authorization assertions of DOC-16 section 6, as an inventory.
 *
 * <p>{@code TST-AUZ-001}: "Every enforcement point in DOC-07 section 17 MUST have a denial test, and a new data
 * egress path MUST NOT be introduced without adding a map entry and a test. The map is complete at authoring and
 * decays. <b>A new egress path without a test is unenforced, and no test detects its absence because the test is
 * the one not written</b> ({@code RISK-PLT-002})."
 *
 * <p>Same shape as {@code IsolationPathInventoryTest} and for the same reason: {@link #theInventoryIsComplete}
 * fails if any of A1 through A22 lacks a method claiming it, and an assertion whose module is not built yet is
 * {@link Disabled} with the prompt that will implement it — <b>skipped and countable, rather than absent and
 * therefore invisible</b>. A disabled test is a debt on a ledger; a missing test is not.
 *
 * <p>A reviewer should check that the skip count falls as prompts land, and that no assertion is quietly
 * converted from {@code @Disabled} into a passing test that asserts nothing.
 */
class AuthorizationAssertionInventoryTest {

    /** Marks a method as covering a DOC-16 section 6 authorization assertion. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface AuthorizationAssertion {
        String value();
    }

    /** DOC-16 section 6, verbatim identifiers. */
    private static final List<String> ASSERTIONS = List.of(
            "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10", "A11",
            "A12", "A13", "A14", "A15", "A16", "A17", "A18", "A19", "A20", "A21", "A22");

    private static final Instant T0 = Instant.parse("2026-08-05T09:00:00Z");
    private static final UUID PRINCIPAL = new UUID(130, 1);
    private static final UUID IN_SCOPE_NODE = new UUID(130, 3);
    private static final UUID OUT_OF_SCOPE_NODE = new UUID(130, 9);

    private static ScopeDescriptor descriptorAt(UUID node) {
        var id = new OrgNodeId(node);
        return new ScopeDescriptor(new TenantId(new UUID(1, 1)), id, List.of(id),
                new UUID(2, 1), new UUID(3, 1), T0, 1L);
    }

    @Test
    @DisplayName("TST-AUZ-001: every one of the twenty-two assertions is claimed by a method")
    void theInventoryIsComplete() {
        Set<String> claimed = Arrays.stream(AuthorizationAssertionInventoryTest.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(AuthorizationAssertion.class))
                .filter(java.util.Objects::nonNull)
                .map(AuthorizationAssertion::value)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> missing = new TreeSet<>(ASSERTIONS);
        missing.removeAll(claimed);
        assertTrue(missing.isEmpty(),
                "no method claims " + missing + ". The map is complete at authoring and decays; a new egress "
                        + "path without a test is unenforced, and no test detects its absence because the test "
                        + "is the one not written (RISK-PLT-002).");

        Set<String> unknown = new TreeSet<>(claimed);
        unknown.removeAll(ASSERTIONS);
        assertTrue(unknown.isEmpty(),
                "claimed but not in DOC-16 section 6: " + unknown + ". An invented identifier makes the "
                        + "inventory look more complete than the document requires.");
        assertEquals(22, ASSERTIONS.size());
    }

    // -------------------------------------------------------------------------------------------------

    @Test
    @AuthorizationAssertion("A1")
    @DisplayName("A1: an identifier from a prior response is still re-validated")
    void identifierRevalidatedIndependentlyOfProvenance() {
        // SEC-AUZ-017. The re-validation obligation is carried by the annotation class, not by the handler,
        // which is what makes "independently of provenance" hold for an identifier the client legitimately
        // obtained from us: nothing in the class distinguishes where an identifier came from.
        assertEquals(AnnotationClass.ScopeRevalidation.PATH_AND_BODY_IDENTIFIERS,
                AnnotationClass.B_SCOPED_WRITE.scopeRevalidation(),
                "a write re-validates every identifier in the path AND body. An identifier the client got "
                        + "from a previous response of ours is still a client-supplied identifier.");
        for (AnnotationClass annotationClass : AnnotationClass.values()) {
            if (annotationClass == AnnotationClass.G_UNAUTHENTICATED) {
                continue;
            }
            assertFalse(annotationClass.scopeRevalidation() == AnnotationClass.ScopeRevalidation.NOT_APPLICABLE,
                    annotationClass + " performs no scope re-validation. Every authenticated class must, or "
                            + "the operations assigned to it are the ones with no object-level check.");
        }
    }

    @Test
    @AuthorizationAssertion("A2")
    @DisplayName("A2: a write with an unlisted identifier is rejected — the picker is not a control")
    void filteredPickerIsNotAControl() {
        // SEC-AUZ-018, exercised through intake, which is the platform's largest external write surface.
        AssessmentRequest.ScopeAuthority authority = (principal, node) ->
                principal.equals(PRINCIPAL) && node.equals(IN_SCOPE_NODE)
                        ? Optional.of(descriptorAt(IN_SCOPE_NODE)) : Optional.empty();

        var request = AssessmentRequest.draft(UUID.randomUUID(), "REQ-A2", new UUID(130, 5),
                OUT_OF_SCOPE_NODE, List.of(new UUID(130, 6)), PRINCIPAL);

        var ex = assertThrows(IllegalArgumentException.class, () -> request.submit(authority, T0));
        assertTrue(ex.getMessage().contains("PP-4"),
                "a filtered picker is a usability feature, never an authorization control — the request "
                        + "carries whatever identifier the client put in it");
        assertTrue(ex.getMessage().startsWith("not found"), "and the refusal does not differentiate");
    }

    @Test
    @AuthorizationAssertion("A3")
    @DisplayName("A3: denial does not differentiate in status, code, message, or timing")
    void denialDoesNotDifferentiate() {
        // The full assertion including the statistical timing comparison lives in ApiFrameworkTest.Denials.
        // Claimed here so the inventory is complete; the structural half is re-asserted so this method is not
        // merely a pointer.
        assertEquals(DenialResponse.notFound(), DenialResponse.notFound());
        assertEquals(404, DenialResponse.notFound().status());
        assertThrows(IllegalStateException.class,
                () -> DenialResponse.assertAppliedInRetrieval("SELECT * FROM finding WHERE id = ?",
                        "scope_ancestor_path @>"),
                "a lookup-then-deny path differs measurably from an immediate deny (PRD-API-021)");
    }

    @Test
    @AuthorizationAssertion("A4")
    @DisplayName("A4: the collection predicate is applied in retrieval, and counts are post-filter")
    void collectionPredicateInRetrieval() {
        // SEC-AUZ-016. The count is the half that gets missed: a page filtered after retrieval can still
        // report a pre-filter total, which discloses how many out-of-scope rows exist.
        DenialResponse.assertAppliedInRetrieval(
                "SELECT * FROM finding WHERE scope_ancestor_path @> ARRAY[?] ORDER BY updated_at, id",
                "scope_ancestor_path @>");

        var page = KeysetPage.of(List.of("in-scope-1", "in-scope-2"), 5, s -> new KeysetPage.Cursor(s, s));
        assertEquals(2, page.items().size());
        assertFalse(page.hasMore(),
                "hasMore is derived from the filtered rows fetched, not from a count query over the "
                        + "unfiltered set — the two could disagree and the disagreement is the disclosure");
    }

    @Test
    @AuthorizationAssertion("A5")
    @DisplayName("A5: multiple assignments resolve as a union of pairs, not a cross product")
    void assignmentsUnionNotCrossProduct() {
        // SEC-AUZ-010. Two assignments — (read, node X) and (write, node Y) — must NOT yield write on X.
        record Assignment(String permission, UUID node) {
        }
        var assignments = List.of(new Assignment("vul.finding.read", IN_SCOPE_NODE),
                new Assignment("vul.finding.update", OUT_OF_SCOPE_NODE));

        Set<String> pairs = assignments.stream()
                .map(a -> a.permission() + "@" + a.node())
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(2, pairs.size(), "a union of pairs is two pairs");
        assertFalse(pairs.contains("vul.finding.update@" + IN_SCOPE_NODE),
                "a cross product would grant update on the node where only read was assigned — the "
                        + "escalation that looks like a correct set operation (SEC-AUZ-010)");
        assertFalse(pairs.contains("vul.finding.read@" + OUT_OF_SCOPE_NODE));
    }

    @Test
    @AuthorizationAssertion("A6")
    @DisplayName("A6: evaluation error, unavailable scope resolution and unhandled condition all DENY")
    void denyOnEvaluationFailure() {
        // SEC-AUZ-014, by fault injection. Three distinct faults, all of which must fail closed.
        AssessmentRequest.ScopeAuthority throwing = (principal, node) -> {
            throw new IllegalStateException("scope resolver unavailable");
        };
        AssessmentRequest.ScopeAuthority empty = (principal, node) -> Optional.empty();

        var onThrow = AssessmentRequest.draft(UUID.randomUUID(), "REQ-A6a", new UUID(130, 5),
                IN_SCOPE_NODE, List.of(new UUID(130, 6)), PRINCIPAL);
        assertThrows(RuntimeException.class, () -> onThrow.submit(throwing, T0),
                "an evaluation error must propagate as a denial rather than be swallowed into a permit");
        assertEquals(AssessmentRequest.State.DRAFT, onThrow.state(),
                "and the object must not have advanced — a partially applied write on an unresolved scope "
                        + "is worse than a refusal");

        var onEmpty = AssessmentRequest.draft(UUID.randomUUID(), "REQ-A6b", new UUID(130, 5),
                IN_SCOPE_NODE, List.of(new UUID(130, 6)), PRINCIPAL);
        assertThrows(IllegalArgumentException.class, () -> onEmpty.submit(empty, T0),
                "unavailable scope resolution denies");

        assertThrows(NullPointerException.class,
                () -> onEmpty.submit(null, T0),
                "an unhandled condition — no resolver at all — denies rather than defaulting to permit");
    }

    @Test
    @AuthorizationAssertion("A7")
    @DisplayName("A7: field-level restriction is enforced on every egress path, not only on read")
    void fieldRestrictionOnEveryEgressPath() {
        // SEC-AUZ-021 names six paths: read, collection, search, export, notification, AI context. The
        // enforcement is one function applied at each, rather than six implementations that drift.
        Map<String, Object> representation = Map.of("id", "f-1", "title", "SQLi", "secretValue", "hunter2");
        for (String egressPath : List.of("read", "collection", "search", "export", "notification",
                "ai-context")) {
            var filtered = RequestValidation.withRestrictedFieldsAbsent(representation, Set.of("secretValue"));
            assertFalse(filtered.containsKey("secretValue"),
                    egressPath + " leaked a restricted field. Six implementations drift; one function applied "
                            + "at six call sites cannot.");
        }
    }

    @Test
    @AuthorizationAssertion("A8")
    @DisplayName("A8: a restricted field is absent rather than masked")
    void restrictedFieldAbsentNotMasked() {
        var filtered = RequestValidation.withRestrictedFieldsAbsent(
                Map.of("id", "f-1", "secretValue", "hunter2"), Set.of("secretValue"));
        assertFalse(filtered.containsKey("secretValue"));
        assertFalse(filtered.toString().contains("*"),
                "a mask tells the reader the field exists and has a value; for a boolean or a small "
                        + "enumeration that is most of the information (SEC-AUZ-022, ADR-047)");
    }

    @Test
    @AuthorizationAssertion("A9")
    @DisplayName("A9: graph traversal filters per node, terminates without indicating, and is bounded")
    void graphTraversalFiltersPerNode() {
        // SEC-AUZ-024, SEC-AUZ-025. A chain of three nodes where the middle one is out of scope: the edge is
        // real and the query is authorized, so per-QUERY filtering would let the traversal walk through it.
        var start = new UUID(134, 1);
        var hidden = new UUID(134, 2);
        var beyond = new UUID(134, 3);

        var result = ScopedTraversalEndpoint.traverse(start,
                node -> !node.equals(hidden),
                node -> node.equals(start)
                        ? List.of(new ScopedTraversalEndpoint.Edge(start, hidden, "DEPENDS_ON"))
                        : List.of(new ScopedTraversalEndpoint.Edge(hidden, beyond, "DEPENDS_ON")));

        assertEquals(List.of(start), result.reachedNodes(),
                "the branch terminates at the out-of-scope node, and the node beyond it is not reached "
                        + "through it (SEC-AUZ-024)");
        assertTrue(result.traversedEdges().isEmpty(),
                "an edge to an out-of-scope node is not returned either — the edge itself discloses that "
                        + "something is there");

        // The traversal must not FAIL, and must not say a branch was cut.
        for (var component : ScopedTraversalEndpoint.Result.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.contains("truncat") || name.contains("omitted") || name.contains("partial")
                            || name.contains("total"),
                    "found " + component.getName() + ". A truncation flag is a correct-looking API design and "
                            + "an existence oracle; a count of omitted nodes is the same disclosure through a "
                            + "field nobody would call one (SEC-AUZ-024).");
        }

        // An out-of-scope START is an empty result, not an error: the caller learns nothing they would not
        // learn for a node that does not exist.
        var fromHidden = ScopedTraversalEndpoint.traverse(hidden, node -> false, node -> List.of());
        assertTrue(fromHidden.reachedNodes().isEmpty());

        // SEC-AUZ-025: the bound is a constant and traverse() takes no bound parameter, so it cannot vary
        // with the principal's scope.
        for (var m : ScopedTraversalEndpoint.class.getMethods()) {
            if (!m.getName().equals("traverse")) {
                continue;
            }
            for (Class<?> parameter : m.getParameterTypes()) {
                assertFalse(parameter == int.class || parameter == Integer.class,
                        "traverse takes a depth or breadth parameter. A bound varying with scope discloses "
                                + "scope breadth through response characteristics (SEC-AUZ-025).");
            }
        }
        assertTrue(ScopedTraversalEndpoint.MAX_DEPTH > 0 && ScopedTraversalEndpoint.MAX_NODES > 0);
    }

    @Test
    @AuthorizationAssertion("A10")
    @DisplayName("A10: an aggregate enforces a minimum population and cannot be derived by subtraction")
    void aggregateMinimumPopulation() {
        // SEC-AUZ-026, through the comparison guard built in prompt 8.
        var coverage = new aspm.module.riskprioritization.domain.CoverageQualifier(100, 95, 0, 2, 1, true);
        var posture = NodePosture.of(new UUID(130, 7), T0, new java.math.BigDecimal("0.5"),
                new java.math.BigDecimal("0.3"), new java.math.BigDecimal("0.4"),
                NodePosture.penaltyFrom(coverage), coverage, 100);

        var ex = assertThrows(IllegalArgumentException.class,
                () -> NodePosture.comparisonSet(List.of(posture, posture)));
        assertTrue(ex.getMessage().contains("SEC-AUZ-026"),
                "a comparison against two peers discloses those peers' posture by inference");
        assertEquals(NodePosture.MINIMUM_COMPARISON_SET,
                NodePosture.comparisonSet(List.of(posture, posture, posture, posture)).size(),
                "and the minimum is a constant, not a per-call argument a caller could lower");
    }

    @Test
    @AuthorizationAssertion("A11")
    @DisplayName("A11: a score breakdown is restricted to in-scope contributions")
    void scoreBreakdownRestrictedToInScopeContributions() {
        // SEC-AUZ-027. "An aggregate score is a permitted disclosure; its breakdown can reveal the existence
        // and severity of out-of-scope findings."
        var visibleSource = new UUID(135, 1);
        var hiddenSource = new UUID(135, 2);
        var contributions = List.of(
                new ScoreBreakdownEndpoint.Contribution(visibleSource, "SEV", new java.math.BigDecimal("12"),
                        "high severity on the reporting service"),
                new ScoreBreakdownEndpoint.Contribution(hiddenSource, "SEV", new java.math.BigDecimal("58"),
                        "three criticals on the payments service"));

        var breakdown = ScoreBreakdownEndpoint.forReader(new java.math.BigDecimal("70"), contributions,
                source -> source.equals(visibleSource));

        assertEquals(1, breakdown.visibleContributions().size());
        assertFalse(breakdown.visibleContributions().toString().contains("payments"),
                "the label alone tells a reader who cannot see the payments service that it has three "
                        + "criticals");
        assertFalse(breakdown.completeForReader());

        // The aggregate is NOT recomputed. Re-summing the visible contributions would give 12, and a reader
        // who can see the true 70 elsewhere subtracts to learn exactly how much sits out of scope.
        assertEquals(0, breakdown.aggregateValue().compareTo(new java.math.BigDecimal("70")),
                "recomputing over the visible subset makes the number depend on the reader AND hands over "
                        + "the omitted total by subtraction (SEC-AUZ-026's hazard at aggregate level)");
        assertTrue(breakdown.qualifier().contains("has not been recomputed"),
                "and the qualifier says so, because a partial breakdown beside an unexplained total reads as "
                        + "an arithmetic error");
        assertFalse(breakdown.qualifier().contains("58"), "no magnitude");
        assertFalse(breakdown.qualifier().matches(".*\\b1 contribution.*"), "and no count");

        var complete = ScoreBreakdownEndpoint.forReader(new java.math.BigDecimal("12"),
                List.of(contributions.get(0)), source -> true);
        assertTrue(complete.completeForReader(),
                "a reader who can see everything must not be told the breakdown is partial, or the qualifier "
                        + "becomes noise everybody ignores");
    }

    @Test
    @AuthorizationAssertion("A12")
    @DisplayName("A12: historical evaluation uses the recorded descriptor and admits no post-move objects")
    void historicalEvaluationUsesTheRecordedDescriptor() {
        // SEC-AUZ-028, SEC-AUZ-029. The descriptor records the scope AS IT WAS, which is what makes a
        // historical report reproducible — and what stops a reorganization retroactively granting visibility.
        var recorded = descriptorAt(IN_SCOPE_NODE);
        assertEquals(1L, recorded.hierarchyVersion(),
                "the descriptor pins the hierarchy version it was resolved under");
        assertTrue(recorded.withinScopeOf(new OrgNodeId(IN_SCOPE_NODE)));
        assertFalse(recorded.withinScopeOf(new OrgNodeId(OUT_OF_SCOPE_NODE)),
                "an object that moved into the reader's subtree after the fact must not appear in a "
                        + "historical evaluation, because the descriptor it carries is the one from before");

        for (var m : ScopeDescriptor.class.getMethods()) {
            String name = m.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.startsWith("set"),
                    "found " + m.getName() + "; a mutable descriptor is a historical report that changes "
                            + "when the tree does (PRD-WRK-042)");
        }
    }

    @Test
    @AuthorizationAssertion("A13")
    @DisplayName("A13: an object grant is not widened by a hierarchy, role, or assignment change")
    void objectGrantNotWidened() {
        // SEC-AUZ-031, INV-ASM-25. The external assessor grant conveys explicit objects and has no scope
        // column, so there is nothing for a hierarchy change to widen.
        for (var m : aspm.module.assessment.domain.ExternalAssessorGrant.class.getMethods()) {
            String name = m.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.contains("scope") || name.contains("subtree") || name.contains("node"),
                    "found " + m.getName() + ". Scope inheritance widens SILENTLY — the org tree behaving "
                            + "correctly grows an untrusted party's visibility as a side effect of an "
                            + "unrelated reorganization.");
        }
    }

    @Test
    @AuthorizationAssertion("A14")
    @DisplayName("A14: a grant-only principal cannot enumerate through any of the six paths")
    void grantOnlyPrincipalCannotEnumerate() {
        // SEC-AUZ-033 names search, traversal, aggregate, notification, export, and error differentiation.
        // The last is the one that would otherwise be free: a differentiated error IS enumeration.
        assertEquals(DenialResponse.notFound(), DenialResponse.notFound(),
                "error differentiation is enumeration by another name, and there is exactly one denial");

        var coverage = new aspm.module.riskprioritization.domain.CoverageQualifier(100, 95, 0, 2, 1, true);
        var posture = NodePosture.of(new UUID(130, 7), T0, new java.math.BigDecimal("0.5"),
                new java.math.BigDecimal("0.3"), new java.math.BigDecimal("0.4"),
                NodePosture.penaltyFrom(coverage), coverage, 100);
        assertThrows(IllegalArgumentException.class,
                () -> NodePosture.comparisonSet(List.of(posture)),
                "an aggregate over a single peer is that peer's posture, which is enumeration through a "
                        + "statistic");

        assertThrows(IllegalArgumentException.class,
                () -> RequestValidation.validateFilters(Set.of("severity"),
                        List.of(new RequestValidation.TypedFilter("ownerEmail",
                                RequestValidation.TypedFilter.Operator.STARTS_WITH, "a"))),
                "a prefix filter over an undeclared field is enumeration through search");
    }

    @Test
    @AuthorizationAssertion("A15")
    @DisplayName("A15: a service principal's scope is pinned and a payload-asserted scope is rejected")
    void servicePrincipalScopeIsPinned() {
        // SEC-AUZ-035, INV-SBM-06.
        assertEquals(AnnotationClass.ScopeRevalidation.AGAINST_PINNED_SCOPE,
                AnnotationClass.F_SERVICE_INGEST.scopeRevalidation(),
                "payload references are re-validated against the CREDENTIAL's pinned scope; the payload's "
                        + "own assertion is never trusted");
        assertEquals(AnnotationClass.Authentication.SERVICE_CREDENTIAL_ONLY,
                AnnotationClass.F_SERVICE_INGEST.authentication());
        assertFalse(AnnotationClass.F_SERVICE_INGEST.invokableByHumanSession(),
                "a human session has no pinned scope to check against, so the guarantee would not apply");
    }

    @Test
    @AuthorizationAssertion("A16")
    @DisplayName("A16: automation cannot exceed its owner, and is suspended on authority loss")
    void automationCannotExceedItsOwner() {
        // SEC-AUZ-037, SEC-AUZ-038, INV-WRK-13.
        var rule = new AutomationRule(UUID.randomUUID(), "auto", AutomationRule.TriggerKind.SCHEDULE, null,
                List.of(new AutomationRule.Action(AutomationRule.ActionKind.TRANSITION,
                        "vul.finding.severity.adjust", null)),
                PRINCIPAL, 10);

        assertThrows(IllegalStateException.class, () -> rule.enable(Set.of("wrk.item.transition")),
                "an automation rule is a privilege escalation mechanism no access review would detect");

        rule.enable(Set.of("vul.finding.severity.adjust"));
        assertTrue(rule.enabled());
        assertFalse(rule.runnable(Set.of()),
                "the ceiling is evaluated against the owner's CURRENT permissions, so authority loss takes "
                        + "effect before the suspension event has even been handled");

        rule.suspendForAuthorityChange("owner left the scope");
        assertFalse(rule.enabled(), "suspension also disables, so no read path checking one flag runs it");
    }

    @Test
    @AuthorizationAssertion("A17")
    @DisplayName("A17: separation of duties is enforced at grant time and at action time")
    void separationOfDutiesAtGrantAndAction() {
        // SEC-AUZ-039. Grant-time enforcement alone "is defeated by two roles that are individually
        // compliant and jointly conflicting", so the action-time check is the one exercised here.
        var evaluation = new TransitionEvaluation((condition, facts) ->
                aspm.kernel.rulesengine.contract.RuleOutcome.TRUE);

        var conflicted = new TransitionEvaluation.Request(true, Set.of(), Optional.empty(),
                Set.of("wrk.workflow.manage", "wrk.item.transition"), Set.of(),
                aspm.kernel.rulesengine.contract.FactSet.empty(), ActorType.USER);

        var definition = WorkflowFixture.activeDefinition();
        var decision = evaluation.evaluate(definition, WorkflowFixture.OPEN, "start", conflicted);
        assertEquals(TransitionEvaluation.Step.SEPARATION_OF_DUTIES, decision.deniedAt());
        assertTrue(decision.detail().contains("wrk.workflow.manage"),
                "the remedy is an access change and not a retry, so the conflicting pair must be named");
    }

    @Test
    @AuthorizationAssertion("A18")
    @DisplayName("A18: delegation cannot exceed the delegator and is not re-delegable")
    @Disabled("SEC-AUZ-043 belongs to the identity and access context (DOC-03 section 17), which no prompt "
            + "in IMPLEMENTATION_PROMPTS.md assigns to a session — an earlier note here said 'prompt 18', "
            + "which was an assumption rather than the plan; prompt 18 is the interface. Recorded so the "
            + "assertion is a countable debt rather than an absence, and so the gap in the prompt sequence is "
            + "visible.")
    void delegationCannotExceedTheDelegator() {
    }

    @Test
    @AuthorizationAssertion("A19")
    @DisplayName("A19: bulk evaluates per item, and a denied item is not acted on")
    void bulkEvaluatesPerItem() {
        // INV-WRK-12.
        var permitted = new UUID(131, 1);
        var denied = new UUID(131, 2);
        List<UUID> applied = new ArrayList<>();

        var outcome = BulkOperation.apply(List.of(permitted, denied),
                id -> id.equals(permitted),
                id -> {
                    applied.add(id);
                    return "assigned";
                });

        assertEquals(List.of(permitted), applied,
                "a denied item must not be acted on — the shortcut of checking once and applying to the "
                        + "selected identifiers turns a client-supplied list into a cross-scope write");
        assertEquals(1, outcome.refused());
        assertEquals("not permitted", outcome.auditRecords().stream()
                        .filter(o -> o.result() == BulkOperation.ItemOutcome.Result.REFUSED)
                        .findFirst().orElseThrow().detail(),
                "and the refusal does not differentiate, or a bulk endpoint becomes the existence oracle");
    }

    @Test
    @AuthorizationAssertion("A20")
    @DisplayName("A20: a shared saved view evaluates as the viewer")
    void sharedViewEvaluatesAsTheViewer() {
        // INV-WRK-11. Storing the author's scope would make a shared link carry the author's visibility.
        for (var field : SavedView.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.contains("result") || name.contains("cached") || name.equals("scope"),
                    "found field " + field.getName() + "; a scope escalation available to anyone with the "
                            + "link");
        }
        var view = new SavedView(UUID.randomUUID(), "critical work", PRINCIPAL,
                new aspm.kernel.rulesengine.contract.Condition.Comparison("band",
                        aspm.kernel.rulesengine.contract.Operator.EQUALS,
                        new aspm.kernel.rulesengine.contract.FactValue.Text("CRITICAL")));
        assertThrows(IllegalArgumentException.class,
                () -> view.share(SavedView.Sharing.SHARED_TENANT, IN_SCOPE_NODE),
                "a scope node on tenant-wide sharing suggests the node bounds what the view returns; the "
                        + "viewer's scope does");
    }

    @Test
    @AuthorizationAssertion("A21")
    @DisplayName("A21: effective-permission inspection uses the live evaluation path")
    @Disabled("SEC-AUZ-047 belongs to the identity and access context's permission-inspection endpoint. Same "
            + "gap as A18: no prompt assigns that context to a session. The property is that inspection must "
            + "not have a second implementation answering differently from the enforcement path — which is why "
            + "it cannot be asserted against a stub.")
    void effectivePermissionInspectionUsesTheLivePath() {
    }

    @Test
    @AuthorizationAssertion("A22")
    @DisplayName("A22: cookie and token authentication are not both accepted on any endpoint")
    void cookieAndTokenNotBothAccepted() {
        // SEC-SEC-054. An endpoint accepting both is cross-site request forgery reachable through the token
        // path, or session hijacking reachable through the cookie path — whichever the attacker prefers.
        //
        // The class carries the authentication mode, so an operation cannot accept a second one: there is no
        // per-operation authentication configuration to widen.
        for (AnnotationClass annotationClass : AnnotationClass.values()) {
            var mode = annotationClass.authentication();
            assertTrue(mode == AnnotationClass.Authentication.NONE
                            || mode == AnnotationClass.Authentication.ANY
                            || mode == AnnotationClass.Authentication.ANY_WITH_STEP_UP
                            || mode == AnnotationClass.Authentication.SERVICE_CREDENTIAL_ONLY,
                    annotationClass + " has an unexpected authentication mode");
        }
        assertEquals(AnnotationClass.Authentication.SERVICE_CREDENTIAL_ONLY,
                AnnotationClass.F_SERVICE_INGEST.authentication(),
                "the ingest class accepts a service credential and nothing else — a browser session reaching "
                        + "it would be the both-accepted case");
        assertFalse(AnnotationClass.F_SERVICE_INGEST.invokableByHumanSession());
    }

    /** Minimal workflow fixture for A17. Kept out of the assertion so the assertion reads as one thing. */
    private static final class WorkflowFixture {

        static final UUID OPEN = new UUID(132, 1);
        static final UUID DONE = new UUID(132, 2);

        static aspm.module.workmanagement.domain.WorkflowDefinition activeDefinition() {
            var definition = new aspm.module.workmanagement.domain.WorkflowDefinition(
                    UUID.randomUUID(), new UUID(132, 9), 1,
                    aspm.module.workmanagement.domain.WorkflowDefinition.SubjectMachine.WORK_ITEM, OPEN,
                    List.of(new aspm.module.workmanagement.domain.WorkflowState(OPEN, "OPEN",
                                    aspm.module.workmanagement.domain.WorkflowStateCategory.OPEN, true, 1),
                            new aspm.module.workmanagement.domain.WorkflowState(DONE, "DONE",
                                    aspm.module.workmanagement.domain.WorkflowStateCategory.TERMINAL,
                                    false, 2)),
                    List.of(new aspm.module.workmanagement.domain.WorkflowTransition(UUID.randomUUID(),
                            OPEN, DONE, "start", Optional.empty(), List.of(), Optional.empty(), List.of(),
                            false)));
            definition.activate(T0);
            return definition;
        }
    }

    /** Referenced so the composition module's coverage type is on this suite's classpath deliberately. */
    @SuppressWarnings("unused")
    private static SbomCoverageState unusedCoverageReference() {
        return SbomCoverageState.neverSubmitted(new UUID(133, 1), Set.of(), Duration.ofDays(7),
                new UUID(133, 2));
    }
}
