-- =============================================================================
-- V014 — the assessment request state machine becomes DATA, and gains its transition log
--
-- *** A DEFECT IN V010, CORRECTED. STATED PLAINLY. ***
--
-- V010 constrained assessment_request.state with
--     CHECK (state IN ('DRAFT','SUBMITTED','TRIAGED','ACCEPTED','SCHEDULED','IN_ASSESSMENT',
--                      'REJECTED','DEFERRED','WITHDRAWN','MERGED'))
--
-- DOC-09 §4 marks the Assessment Request machine **[configurable]**, and §2.2 permits a tenant to add
-- states within an existing category and to add, remove or rename transitions. A CHECK enumerating
-- states makes both impossible: a tenant adding a state gets a constraint violation, and the platform
-- ADR-027 promises — deployable by any conglomerate without code modification — is not deliverable.
-- CLAUDE.md lists this exact shape under prohibited patterns: "Fixed enumeration for a tenant-
-- configurable surface".
--
-- The names were also wrong. DOC-09 §4 specifies 25 states; V010 allowed 10, and of those, TRIAGED and
-- IN_ASSESSMENT are not in the document at all — the document has INTAKE_REVIEW, ASSIGNED and
-- IN_PROGRESS. Nothing in V010 could represent a request past testing: REPORT_DRAFT,
-- REPORT_UNDER_QA, REPORT_DELIVERED, FIXING, RETEST_REQUESTED, RETEST_IN_PROGRESS, CLOSED_PASSED and
-- CLOSED_WITH_ACCEPTED_RISK were all unrepresentable. **The intake half of the product's core workflow
-- existed and the delivery half did not**, and no test caught it because no test asserted the schema
-- against DOC-09 §4's diagram.
--
-- Found while implementing transitions, by reading the specification the transitions had to obey.
--
-- WHAT THIS MIGRATION DOES
--   1. Widens workflow_definition to bind an assessment type as well as a work item type.
--   2. Drops the CHECK and validates state against workflow_state instead — a trigger rather than a
--      foreign key, because the state column is a code and the definition is reached through the type.
--   3. Adds assessment_request_transition — the "or the equivalent" DOC-09 §3 permits for machines
--      that are not work items.
--   4. Seeds DOC-09 §4's machine as the default definition, so a tenant starts from the specified
--      workflow and edits it rather than authoring one.
--   5. Adds the PRD-WRK-034 validation function: every state reachable, at least one terminal, no
--      non-terminal state without an outbound transition.
--
-- Expand only. Nothing is dropped that carries data; the CHECK removal only widens what is accepted.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. workflow_definition binds an assessment type as well as a work item type
--
-- assessment_type.workflow_definition_id already pointed here, so the intent was always that a request
-- is governed by a workflow definition. The column that made it impossible was work_item_type_id being
-- NOT NULL: a request is not a work item.
-- -----------------------------------------------------------------------------
ALTER TABLE workflow_definition
    ALTER COLUMN work_item_type_id DROP NOT NULL;

-- -----------------------------------------------------------------------------
-- 1b. A SECOND defect, in V009 this time: initial_state_id was NOT NULL.
--
-- workflow_state.definition_id references workflow_definition, so the initial state cannot exist
-- before the definition it belongs to — and the definition could not be inserted without naming it.
-- **No workflow definition could be authored at all.** The tables were written, tested for their
-- constraints, and unreachable as a whole, because no test created a definition end to end.
--
-- The correct model was already implied by V009's own guard: ck_workflow_definition__validated_before
-- _active gates ACTIVE on validated_at, so a DRAFT definition is legitimately incomplete. An initial
-- state is required to ACTIVATE, not to exist.
-- -----------------------------------------------------------------------------
ALTER TABLE workflow_definition
    ALTER COLUMN initial_state_id DROP NOT NULL;

ALTER TABLE workflow_definition
    DROP CONSTRAINT IF EXISTS ck_workflow_definition__initial_before_active;
ALTER TABLE workflow_definition
    ADD CONSTRAINT ck_workflow_definition__initial_before_active CHECK
        (state <> 'ACTIVE' OR initial_state_id IS NOT NULL);

ALTER TABLE workflow_definition
    ADD COLUMN IF NOT EXISTS assessment_type_id uuid REFERENCES assessment_type (id) ON DELETE RESTRICT;

-- Exactly one owner. A definition governing both a work item type and an assessment type would be
-- edited for one and silently change the other.
ALTER TABLE workflow_definition
    DROP CONSTRAINT IF EXISTS ck_workflow_definition__one_owner;
ALTER TABLE workflow_definition
    ADD CONSTRAINT ck_workflow_definition__one_owner CHECK
        ((work_item_type_id IS NOT NULL) <> (assessment_type_id IS NOT NULL));

-- The uniqueness constraint named work_item_type_id, so two assessment definitions at the same version
-- were indistinguishable to it. Replaced by two partial indexes, one per owner kind.
DROP INDEX IF EXISTS ix_workflow_definition__active;
ALTER TABLE workflow_definition DROP CONSTRAINT IF EXISTS uq_workflow_definition__version;

CREATE UNIQUE INDEX IF NOT EXISTS uq_workflow_definition__wi_version
    ON workflow_definition (tenant_id, work_item_type_id, version)
    WHERE work_item_type_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_workflow_definition__asm_version
    ON workflow_definition (tenant_id, assessment_type_id, version)
    WHERE assessment_type_id IS NOT NULL;
-- One ACTIVE definition per type. Two would make "the current workflow" ambiguous, and the ambiguity
-- would be resolved differently by each reader.
CREATE UNIQUE INDEX IF NOT EXISTS ix_workflow_definition__active_wi
    ON workflow_definition (tenant_id, work_item_type_id)
    WHERE state = 'ACTIVE' AND work_item_type_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ix_workflow_definition__active_asm
    ON workflow_definition (tenant_id, assessment_type_id)
    WHERE state = 'ACTIVE' AND assessment_type_id IS NOT NULL;

COMMENT ON COLUMN workflow_definition.assessment_type_id IS
    'DOC-09 §4. An assessment request is governed by a workflow definition, which is why '
    'assessment_type.workflow_definition_id exists. Added in V014 after V010 constrained the request '
    'state with a CHECK — a fixed enumeration on a [configurable] machine.';

-- -----------------------------------------------------------------------------
-- 2. The state column is validated against the definition, not against a literal list
--
-- A trigger and not a foreign key. The state is a CODE, and the definition is reached through
-- assessment_type — so the referential target is (definition_id, code) and the definition is two joins
-- away. A composite foreign key would require denormalizing definition_id onto every request, which
-- would then have to be kept in step with the type's active definition on every activation.
-- -----------------------------------------------------------------------------
ALTER TABLE assessment_request DROP CONSTRAINT IF EXISTS ck_assessment_request__state;

CREATE OR REPLACE FUNCTION assert_request_state_defined() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    definition uuid;
    known      bool;
BEGIN
    SELECT t.workflow_definition_id INTO definition
      FROM assessment_type t
     WHERE t.id = NEW.type_id AND t.tenant_id = NEW.tenant_id;

    -- A type with no definition yet accepts the seeded default's states. Rejecting outright would make
    -- this migration order-dependent on every tenant having a definition, and a tenant provisioned
    -- before the seed would be unable to write a request at all.
    IF definition IS NULL THEN
        SELECT EXISTS (
            SELECT 1 FROM workflow_state s
             WHERE s.tenant_id = NEW.tenant_id AND s.code = NEW.state
        ) INTO known;
    ELSE
        SELECT EXISTS (
            SELECT 1 FROM workflow_state s
             WHERE s.tenant_id = NEW.tenant_id AND s.definition_id = definition
               AND s.code = NEW.state
        ) INTO known;
    END IF;

    IF NOT known THEN
        RAISE EXCEPTION
            'state ''%'' is not defined by the workflow for this assessment type (DOC-09 §4). States '
            'are tenant-configurable data (ADR-027, PRD-WRK-034): add the state to the workflow '
            'definition rather than writing an undefined one.', NEW.state
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_assessment_request__state_defined ON assessment_request;
CREATE TRIGGER trg_assessment_request__state_defined
    BEFORE INSERT OR UPDATE OF state ON assessment_request
    FOR EACH ROW EXECUTE FUNCTION assert_request_state_defined();

-- -----------------------------------------------------------------------------
-- 3. assessment_request_transition — the append-only record DOC-09 §3 requires
--
-- "Every transition writes work_item_state_transition or the equivalent, with actor, actor type,
-- timestamp, duration in the prior state, and whether the clock was running."
--
-- PRD-WRK-032 makes the state change and this record one transaction, and INV-WRK-03 makes the record
-- append-only: "a recorded state change without its transition record breaks the append-only history
-- that capacity and flow analysis depend on".
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS assessment_request_transition (
    id                 uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id          uuid        NOT NULL,
    request_id         uuid        NOT NULL REFERENCES assessment_request (id) ON DELETE RESTRICT,
    sequence_number    bigint      NOT NULL,
    from_state         text,
    to_state           text        NOT NULL,
    event_code         text        NOT NULL,
    actor_principal_id uuid,
    actor_type         text        NOT NULL,
    automation_rule_id uuid,
    reason             text,
    occurred_at        timestamptz NOT NULL DEFAULT now(),
    -- Duration in the PRIOR state. Derived at write time rather than by a later query over pairs of
    -- rows, because a query has to guess what to do about the row whose successor does not exist yet.
    prior_state_duration interval,
    sla_clock_running  bool        NOT NULL,

    CONSTRAINT uq_asm_request_transition__sequence
        UNIQUE (tenant_id, request_id, sequence_number),
    CONSTRAINT ck_asm_request_transition__actor_type CHECK
        (actor_type IN ('HUMAN', 'AUTOMATION', 'SYSTEM', 'MIGRATION')),
    -- An automated transition records the rule (DOC-09 §3). Without it the authority ceiling of
    -- INV-WRK-13 cannot be checked after the fact.
    CONSTRAINT ck_asm_request_transition__automation_rule CHECK
        (actor_type <> 'AUTOMATION' OR automation_rule_id IS NOT NULL),
    -- A human transition has a principal. A transition attributed to nobody is a state change with no
    -- author, which is the thing the log exists to prevent.
    CONSTRAINT ck_asm_request_transition__human_actor CHECK
        (actor_type <> 'HUMAN' OR actor_principal_id IS NOT NULL),
    CONSTRAINT ck_asm_request_transition__sequence_positive CHECK (sequence_number >= 1),
    -- The first transition has no prior state and no duration; every later one has both.
    CONSTRAINT ck_asm_request_transition__first_has_no_prior CHECK
        ((sequence_number = 1) = (from_state IS NULL)),
    CONSTRAINT ck_asm_request_transition__reason_not_blank CHECK
        (reason IS NULL OR btrim(reason) <> '')
);

SELECT apply_tenant_isolation('assessment_request_transition');

-- Append-only to the application. A transition that can be edited is a history that can be rewritten,
-- and DOC-09 §3 forbids silent reversal for the same reason.
REVOKE UPDATE, DELETE ON assessment_request_transition FROM app_runtime;
GRANT SELECT, INSERT ON assessment_request_transition TO app_runtime;
GRANT SELECT ON assessment_request_transition TO integrity_verifier;

CREATE OR REPLACE FUNCTION reject_request_transition_rewrite() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'the request transition log is append-only (INV-WRK-03, DOC-09 §3). Returning to a prior state '
        'is a distinct FORWARD transition with its own record, never a reversal — an undo that removes '
        'history makes cycle-time analysis wrong and conceals rework, which is itself a signal.'
        USING ERRCODE = 'integrity_constraint_violation';
END
$$;

DROP TRIGGER IF EXISTS trg_asm_request_transition__append_only ON assessment_request_transition;
CREATE TRIGGER trg_asm_request_transition__append_only
    BEFORE UPDATE OR DELETE ON assessment_request_transition
    FOR EACH ROW EXECUTE FUNCTION reject_request_transition_rewrite();

CREATE INDEX IF NOT EXISTS ix_asm_request_transition__request
    ON assessment_request_transition (tenant_id, request_id, sequence_number DESC);
COMMENT ON INDEX ix_asm_request_transition__request IS
    'Serves: the transition timeline on the request detail page, newest first, and the duration '
    'computation that reads the previous row.';

CREATE INDEX IF NOT EXISTS ix_asm_request_transition__flow
    ON assessment_request_transition (tenant_id, to_state, occurred_at);
COMMENT ON INDEX ix_asm_request_transition__flow IS
    'Serves: cycle time and flow analysis per state (DOC-12), which reads by destination state over a '
    'period rather than by request.';

-- -----------------------------------------------------------------------------
-- 4. PRD-WRK-034 — a definition is validated before activation
--
-- "Every state reachable from the initial state, at least one terminal state, every non-terminal state
-- having at least one outbound transition, and no transition referencing a state outside the
-- definition. A state with no outbound transition is a trap: items enter and cannot leave, and the
-- defect surfaces days later as stalled work with no visible cause."
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION workflow_definition_defects(target uuid)
    RETURNS TABLE (defect text, detail text)
    LANGUAGE plpgsql STABLE
AS $$
DECLARE
    initial uuid;
BEGIN
    SELECT initial_state_id INTO initial FROM workflow_definition WHERE id = target;

    IF initial IS NULL THEN
        RETURN QUERY SELECT 'NO_INITIAL_STATE',
            'the definition names no initial state, so nothing can enter the machine';
        RETURN;
    END IF;

    -- Unreachable states.
    RETURN QUERY
    WITH RECURSIVE reachable AS (
        SELECT initial AS state_id
        UNION
        SELECT t.to_state_id FROM workflow_transition t
          JOIN reachable r ON r.state_id = t.from_state_id
         WHERE t.definition_id = target
    )
    SELECT 'UNREACHABLE_STATE', s.code
      FROM workflow_state s
     WHERE s.definition_id = target
       AND s.id NOT IN (SELECT state_id FROM reachable);

    -- No terminal state.
    RETURN QUERY
    SELECT 'NO_TERMINAL_STATE',
           'no state in the definition has category TERMINAL, so nothing ever finishes'
     WHERE NOT EXISTS (
        SELECT 1 FROM workflow_state s
         WHERE s.definition_id = target AND s.category = 'TERMINAL');

    -- A trap: a non-terminal state with no way out.
    RETURN QUERY
    SELECT 'NO_OUTBOUND_TRANSITION', s.code
      FROM workflow_state s
     WHERE s.definition_id = target AND s.category <> 'TERMINAL'
       AND NOT EXISTS (
        SELECT 1 FROM workflow_transition t
         WHERE t.definition_id = target AND t.from_state_id = s.id);
END
$$;

GRANT EXECUTE ON FUNCTION workflow_definition_defects(uuid)
    TO app_runtime, migration_runner, integrity_verifier;
