-- =============================================================================
-- V025 — a draft request that cannot be cancelled.
--
-- THE DEFECT
--
-- ck_assessment_request__scope_when_submitted read:
--
--     state = 'DRAFT' OR (scope_node_id IS NOT NULL AND scope_resolved_at IS NOT NULL
--                         AND submitted_at IS NOT NULL)
--
-- The workflow offers DRAFT --cancel--> CANCELLED. Taking it moves the state out of DRAFT, at which
-- point the constraint demands a submitted_at that a draft, by definition, does not have. Every
-- attempt failed at the engine with a check-constraint violation, which the transport reported as an
-- internal error: the one exit from DRAFT other than submitting was unreachable, and the message
-- said nothing about why.
--
-- WHY THE ORIGINAL FORM WAS WRONG, BEYOND THE BUG
--
-- The constraint enforced the right guarantee through the wrong predicate. What must be true is:
--
--     a request that HAS BEEN SUBMITTED has resolved scope
--
-- The original instead said "any state that is not DRAFT has resolved scope AND has been submitted",
-- which is a different and stronger claim. It is stronger in a way that is false for every terminal
-- state reachable without submission, and it hardcodes the literal 'DRAFT' — a workflow state code,
-- which is tenant-configurable data (ADR-027). A tenant renaming its initial state would have found
-- that no request could leave it.
--
-- The replacement drops the state coupling entirely and keys off submitted_at, which is a fact about
-- what happened rather than a name somebody chose.
--
-- A NEW NAME, NOT A REDEFINITION
--
-- The old constraint is dropped and a differently named one added, so that a deployment which has
-- already applied V010 and a fresh one converge on the same schema and neither carries a constraint
-- whose name promises something other than what it checks.
-- =============================================================================

ALTER TABLE assessment_request
    DROP CONSTRAINT IF EXISTS ck_assessment_request__scope_when_submitted;

ALTER TABLE assessment_request
    DROP CONSTRAINT IF EXISTS ck_assessment_request__scope_once_submitted;

ALTER TABLE assessment_request
    ADD CONSTRAINT ck_assessment_request__scope_once_submitted
    CHECK (submitted_at IS NULL
           OR (scope_node_id IS NOT NULL AND scope_resolved_at IS NOT NULL));

COMMENT ON CONSTRAINT ck_assessment_request__scope_once_submitted ON assessment_request IS
    'A submitted request has resolved scope. Keyed off submitted_at rather than off a state code: '
    'the state vocabulary is tenant data (ADR-027), and the previous form — which required every '
    'non-DRAFT state to have been submitted — made cancelling a draft impossible.';
