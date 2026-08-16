-- =============================================================================
-- V009 — work management
--
-- Owner: module/work-management. DOC-04 sections 16.1 to 16.8, DOC-09 sections 2, 3 and 13,
-- DOC-03 section 13.
--
-- Five things are enforced at the engine rather than only in the domain. Each has a bypass path
-- that is real rather than hypothetical, and four of the five are migration import (ADR-028), which
-- is the path that carries a decade of an incumbent tracker's data straight past the domain layer:
--
--   INV-WRK-02  a workflow cannot be ACTIVE without validation. A CHECK, so an unvalidated
--               activation is unrepresentable.
--   INV-WRK-04  the transition log is append-only. No UPDATE or DELETE grant to app_runtime AND a
--               rejecting trigger, "so that a privilege misconfiguration does not silently permit
--               modification" (DOC-04 section 16.3).
--   INV-WRK-08  comments are never hard-deletable. Same belt and braces, for the same reason.
--   INV-WRK-07  a link's inverse exists. A deferred constraint trigger, because the pair is written
--               as two statements and an immediate check would reject the intermediate state.
--   PRD-WRK-042 scope descriptor columns are immutable after insert, via the shared
--               reject_scope_descriptor_change() primitive from V001.
--
-- work_item_state_transition is RANGE partitioned monthly (DOC-04 section 16.3). Retention is
-- ARCHIVED, NOT DROPPED: "the log is retained for the work item's life, and items are not deleted."
-- That differs from risk_score, whose partitions are dropped after the reproducibility window, and
-- the difference is deliberate — a score can be recomputed from its own record, a transition cannot
-- be reconstructed from anything.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Workflow as data — DOC-04 section 16.1
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS work_item_type (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid        NOT NULL,
    code         text        NOT NULL,
    label_i18n   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    field_schema jsonb       NOT NULL DEFAULT '{}'::jsonb,
    lifecycle_state text     NOT NULL DEFAULT 'ACTIVE',
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   uuid,
    row_version  int         NOT NULL DEFAULT 1,

    CONSTRAINT uq_work_item_type__code UNIQUE (tenant_id, code),
    CONSTRAINT ck_work_item_type__lifecycle CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

SELECT apply_tenant_isolation('work_item_type');

CREATE TABLE IF NOT EXISTS workflow_definition (
    id               uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id        uuid        NOT NULL,
    work_item_type_id uuid       NOT NULL REFERENCES work_item_type (id) ON DELETE RESTRICT,
    version          int         NOT NULL,
    initial_state_id uuid        NOT NULL,
    state            text        NOT NULL DEFAULT 'DRAFT',
    -- INV-WRK-02. Reachability and terminal-state validation happen before activation; the CHECK
    -- below makes activating without them unrepresentable. "A workflow with an unreachable state is
    -- silently broken — items enter and cannot leave, and the defect surfaces days later as stalled
    -- work with no visible cause" (DOC-04 section 16.1).
    validated_at     timestamptz,
    activated_at     timestamptz,
    retired_at       timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    updated_by       uuid,
    row_version      int         NOT NULL DEFAULT 1,

    CONSTRAINT uq_workflow_definition__version UNIQUE (tenant_id, work_item_type_id, version),
    CONSTRAINT ck_workflow_definition__state CHECK (state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_workflow_definition__validated_before_active CHECK
        (state <> 'ACTIVE' OR validated_at IS NOT NULL),
    CONSTRAINT ck_workflow_definition__version_positive CHECK (version >= 1)
);

SELECT apply_tenant_isolation('workflow_definition');

CREATE INDEX IF NOT EXISTS ix_workflow_def__active
    ON workflow_definition (tenant_id, work_item_type_id) WHERE state = 'ACTIVE';

COMMENT ON INDEX ix_workflow_def__active IS
    'Serves: the current definition for a type, resolved at item creation (DOC-04 section 16.1).';

-- One ACTIVE definition per type. Two would make "which workflow does a new item get" depend on
-- which row was read first, and the two items would then be uncomparable in every flow report.
CREATE UNIQUE INDEX IF NOT EXISTS uq_workflow_def__one_active_per_type
    ON workflow_definition (tenant_id, work_item_type_id) WHERE state = 'ACTIVE';

CREATE TABLE IF NOT EXISTS workflow_state (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL,
    definition_id uuid        NOT NULL REFERENCES workflow_definition (id) ON DELETE RESTRICT,
    code          text        NOT NULL,
    label_i18n    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    category      text        NOT NULL,
    -- On the STATE, not inferred from the category: "a tenant may treat one waiting state as their
    -- responsibility and another as the requester's" (DOC-04 section 16.1), and PRD-RSK-034 needs
    -- the distinction for accurate attribution.
    sla_clock_running bool     NOT NULL,
    display_order int         NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,

    CONSTRAINT uq_workflow_state__code UNIQUE (tenant_id, definition_id, code),
    CONSTRAINT ck_workflow_state__category CHECK
        (category IN ('OPEN', 'IN_PROGRESS', 'WAITING_EXTERNAL', 'TERMINAL')),
    -- Nothing leaves a terminal state, so a running clock there accrues forever and breaches every
    -- item that reached a successful outcome.
    CONSTRAINT ck_workflow_state__terminal_clock CHECK
        (category <> 'TERMINAL' OR NOT sla_clock_running)
);

SELECT apply_tenant_isolation('workflow_state');

CREATE INDEX IF NOT EXISTS ix_workflow_state__definition
    ON workflow_state (tenant_id, definition_id, display_order);

COMMENT ON INDEX ix_workflow_state__definition IS
    'Serves: the ordered state list — board view columns and the configuration interface.';

CREATE TABLE IF NOT EXISTS workflow_transition (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL,
    definition_id   uuid        NOT NULL REFERENCES workflow_definition (id) ON DELETE RESTRICT,
    from_state_id   uuid        NOT NULL REFERENCES workflow_state (id) ON DELETE RESTRICT,
    to_state_id     uuid        NOT NULL REFERENCES workflow_state (id) ON DELETE RESTRICT,
    event_code      text        NOT NULL,
    guard_rule      jsonb,
    required_fields text[]      NOT NULL DEFAULT '{}',
    -- Authorization configuration living in the work management schema (DOC-26 T9). "Editing this
    -- column changes who can effect a transition without any change to a role", which is why
    -- wrk.workflow.manage is one of the three most consequential permissions in the catalogue.
    required_permission text,
    side_effects    jsonb       NOT NULL DEFAULT '[]'::jsonb,
    reason_required bool        NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,

    -- One transition per (state, event). Two would make which one fires depend on ordering, and
    -- they may carry different guards and different required permissions.
    CONSTRAINT uq_workflow_transition__event UNIQUE (tenant_id, definition_id, from_state_id, event_code),
    -- A self-transition records a zero-duration entry in the append-only log for a state change
    -- that did not happen, which makes flow analysis count it as movement.
    CONSTRAINT ck_workflow_transition__not_self CHECK (from_state_id <> to_state_id)
);

SELECT apply_tenant_isolation('workflow_transition');

CREATE INDEX IF NOT EXISTS ix_workflow_transition__from
    ON workflow_transition (tenant_id, definition_id, from_state_id);

COMMENT ON INDEX ix_workflow_transition__from IS
    'Serves: available transitions for an item''s current state — evaluated on every item view '
    '(DOC-04 section 16.1).';

-- INV-WRK-01: an activated definition is immutable, so items pinned to it are not stranded.
CREATE OR REPLACE FUNCTION reject_activated_workflow_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    definition_state text;
BEGIN
    IF TG_TABLE_NAME = 'workflow_definition' THEN
        IF OLD.state IN ('ACTIVE', 'RETIRED')
           AND (NEW.version          IS DISTINCT FROM OLD.version
             OR NEW.initial_state_id IS DISTINCT FROM OLD.initial_state_id
             OR NEW.validated_at     IS DISTINCT FROM OLD.validated_at
             OR NEW.activated_at     IS DISTINCT FROM OLD.activated_at) THEN
            RAISE EXCEPTION
                'workflow definition version % is % and immutable (INV-WRK-01). A change is a NEW '
                'version; in-flight items keep the one they pinned at creation, so a workflow change '
                'does not strand them.', OLD.version, OLD.state
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    SELECT d.state INTO definition_state
      FROM workflow_definition d
     WHERE d.id = COALESCE(NEW.definition_id, OLD.definition_id);

    IF definition_state IN ('ACTIVE', 'RETIRED') THEN
        RAISE EXCEPTION
            'the states and transitions of an activated workflow are immutable (INV-WRK-01); the '
            'definition is %. Editing required_permission on a live definition would change who can '
            'effect a transition with no change to any role, and no access review would see it.',
            definition_state
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END
$$;

DROP TRIGGER IF EXISTS trg_workflow_definition__immutable ON workflow_definition;
CREATE TRIGGER trg_workflow_definition__immutable
    BEFORE UPDATE ON workflow_definition
    FOR EACH ROW EXECUTE FUNCTION reject_activated_workflow_change();

DROP TRIGGER IF EXISTS trg_workflow_state__immutable ON workflow_state;
CREATE TRIGGER trg_workflow_state__immutable
    BEFORE UPDATE OR DELETE ON workflow_state
    FOR EACH ROW EXECUTE FUNCTION reject_activated_workflow_change();

DROP TRIGGER IF EXISTS trg_workflow_transition__immutable ON workflow_transition;
CREATE TRIGGER trg_workflow_transition__immutable
    BEFORE UPDATE OR DELETE ON workflow_transition
    FOR EACH ROW EXECUTE FUNCTION reject_activated_workflow_change();

-- -----------------------------------------------------------------------------
-- 2. work_item — DOC-04 section 16.2
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS work_item (
    id                    uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id             uuid        NOT NULL,
    item_code             text        NOT NULL,
    type_id               uuid        NOT NULL REFERENCES work_item_type (id) ON DELETE RESTRICT,
    workflow_definition_id uuid       NOT NULL REFERENCES workflow_definition (id) ON DELETE RESTRICT,
    -- INV-WRK-01, pinned.
    workflow_definition_version int   NOT NULL,
    state_id              uuid        NOT NULL REFERENCES workflow_state (id) ON DELETE RESTRICT,
    title                 text        NOT NULL,
    description           text,
    -- INV-WRK-05: a single column IS the enforcement. There is no join table to add a second
    -- assignee to, so the invariant cannot be violated by a caller who did not know about it.
    assignee_id           uuid,
    labels                text[]      NOT NULL DEFAULT '{}',
    attributes            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    subject_kind          text        NOT NULL DEFAULT 'NONE',
    -- Cross-module, deliberately no FK (ADR-030, INV-WRK-16). Integrity is a domain concern with
    -- reconciliation (CON-DAT-004); a foreign key here would couple work management's write path to
    -- vulnerability management's table and defeat the extraction seam of ADR-003.
    subject_id            uuid,
    parent_item_id        uuid        REFERENCES work_item (id) ON DELETE RESTRICT,
    -- INV-WRK-15: two columns, and the manual one never overwrites the derived one. A single column
    -- with a flag would lose the comparison that makes the adjustment meaningful.
    effort_derived_days   numeric(6,2) NOT NULL DEFAULT 0,
    effort_manual_days    numeric(6,2),
    estimated_effort_days numeric(6,2),
    planning_period_id    uuid,
    -- Maintained by trigger rather than as an expression index, because PRD-WRK-018 requires search
    -- to cover comments, which live in a child table (DOC-04 section 16.2).
    search_vector         tsvector,

    -- Scope descriptor columns, section 6.6, FROM THE SUBJECT (INV-WRK-06).
    scope_node_id         uuid        NOT NULL,
    scope_ancestor_path   uuid[]      NOT NULL,
    scope_node_type_id    uuid        NOT NULL,
    scope_criticality_id  uuid        NOT NULL,
    scope_hierarchy_ver   bigint      NOT NULL,
    scope_resolved_at     timestamptz NOT NULL,

    created_at            timestamptz NOT NULL DEFAULT now(),
    created_by            uuid        NOT NULL,
    updated_at            timestamptz NOT NULL DEFAULT now(),
    updated_by            uuid,
    row_version           int         NOT NULL DEFAULT 1,

    CONSTRAINT uq_work_item__code UNIQUE (tenant_id, item_code),
    CONSTRAINT ck_work_item__subject_kind CHECK
        (subject_kind IN ('FINDING', 'ASSESSMENT', 'ASSET', 'EXCEPTION', 'NONE')),
    CONSTRAINT ck_work_item__subject_present CHECK
        (subject_kind = 'NONE' OR subject_id IS NOT NULL),
    CONSTRAINT ck_work_item__subject_absent CHECK
        (subject_kind <> 'NONE' OR subject_id IS NULL),
    CONSTRAINT ck_work_item__not_own_parent CHECK (parent_item_id IS NULL OR parent_item_id <> id),
    CONSTRAINT ck_work_item__title_present CHECK (title <> ''),
    CONSTRAINT ck_work_item__effort_non_negative CHECK
        (effort_derived_days >= 0 AND (effort_manual_days IS NULL OR effort_manual_days >= 0)),
    CONSTRAINT ck_work_item__estimate_positive CHECK
        (estimated_effort_days IS NULL OR estimated_effort_days > 0)
);

SELECT apply_tenant_isolation('work_item');

-- PRD-WRK-042 / CON-DAT-009, using the shared primitive from V001.
DROP TRIGGER IF EXISTS trg_work_item__scope_immutable ON work_item;
CREATE TRIGGER trg_work_item__scope_immutable
    BEFORE UPDATE ON work_item
    FOR EACH ROW EXECUTE FUNCTION reject_scope_descriptor_change();

CREATE INDEX IF NOT EXISTS ix_work_item__assignee_open
    ON work_item (tenant_id, assignee_id, state_id)
    WHERE assignee_id IS NOT NULL;

COMMENT ON INDEX ix_work_item__assignee_open IS
    'Serves: "my work" — the most frequently loaded view in the platform (PRD-WRK-013).';

CREATE INDEX IF NOT EXISTS ix_work_item__board
    ON work_item (tenant_id, type_id, state_id, updated_at DESC);

COMMENT ON INDEX ix_work_item__board IS
    'Serves: the board view, grouped by state column (PRD-WRK-014).';

CREATE INDEX IF NOT EXISTS ix_work_item__scope_subtree
    ON work_item USING gin (scope_ancestor_path);

COMMENT ON INDEX ix_work_item__scope_subtree IS
    'Serves: subtree-scoped work reads — the containment predicate scope_ancestor_path @> ARRAY[n].';

CREATE INDEX IF NOT EXISTS ix_work_item__subject
    ON work_item (tenant_id, subject_kind, subject_id) WHERE subject_id IS NOT NULL;

COMMENT ON INDEX ix_work_item__subject IS
    'Serves: "work on this finding" — the bidirectional link of INV-WRK-16, which has no foreign key '
    'to lean on (ADR-030).';

CREATE INDEX IF NOT EXISTS ix_work_item__unassigned
    ON work_item (tenant_id, created_at) WHERE assignee_id IS NULL;

COMMENT ON INDEX ix_work_item__unassigned IS
    'Serves: the unassigned queue. Oldest first, because the oldest unassigned item is the one most '
    'likely to have been forgotten.';

CREATE INDEX IF NOT EXISTS ix_work_item__search
    ON work_item USING gin (search_vector);

COMMENT ON INDEX ix_work_item__search IS
    'Serves: full-text search (PRD-WRK-018). Combined with the scope predicate as a CONJUNCT in the '
    'same query, so the engine applies both before ranking — DOC-04 section 16.2 records this as the '
    'single strongest constraint on the search technology choice, and ADR-051 selected in-engine '
    'search on it.';

CREATE INDEX IF NOT EXISTS ix_work_item__labels
    ON work_item USING gin (labels);

COMMENT ON INDEX ix_work_item__labels IS
    'Serves: label filtering and saved queries.';

CREATE INDEX IF NOT EXISTS ix_work_item__parent
    ON work_item (tenant_id, parent_item_id) WHERE parent_item_id IS NOT NULL;

COMMENT ON INDEX ix_work_item__parent IS 'Serves: sub-items on an item view.';

CREATE INDEX IF NOT EXISTS ix_work_item__planning
    ON work_item (tenant_id, planning_period_id, state_id) WHERE planning_period_id IS NOT NULL;

COMMENT ON INDEX ix_work_item__planning IS 'Serves: iteration progress (PRD-WRK-029).';

-- -----------------------------------------------------------------------------
-- 3. work_item_state_transition — append-only, DOC-04 section 16.3
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS work_item_state_transition (
    id                uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id         uuid        NOT NULL,
    work_item_id      uuid        NOT NULL,
    sequence          int         NOT NULL,
    from_state_id     uuid,
    to_state_id       uuid        NOT NULL,
    event_code        text        NOT NULL,
    actor_id          uuid,
    actor_type        text        NOT NULL,
    automation_rule_id uuid,
    reason            text,
    transitioned_at   timestamptz NOT NULL,
    -- Denormalized. Derivable by self-joining to the previous sequence; stored because cycle-time
    -- and flow computations "read the whole history of many items at once" (DOC-04 section 16.3).
    duration_in_previous_state_seconds bigint,
    -- The flag AS IT WAS. The state's own flag is tenant configuration and can change; a historical
    -- service level computation must use the value in force at the time, or a configuration change
    -- retroactively alters past breach attribution.
    sla_clock_running bool        NOT NULL,
    blocking_attribution text,

    PRIMARY KEY (tenant_id, id, transitioned_at),

    CONSTRAINT uq_wist__item_sequence UNIQUE (tenant_id, work_item_id, sequence, transitioned_at),
    CONSTRAINT ck_wist__sequence_positive CHECK (sequence >= 1),
    CONSTRAINT ck_wist__actor_type CHECK (actor_type IN ('USER', 'SERVICE', 'AUTOMATION', 'SYSTEM')),
    -- An automated transition that did not name its rule is indistinguishable from a human one at
    -- exactly the moment somebody is asking why the item moved.
    CONSTRAINT ck_wist__automation_rule CHECK
        (actor_type <> 'AUTOMATION' OR automation_rule_id IS NOT NULL),
    CONSTRAINT ck_wist__no_rule_without_automation CHECK
        (actor_type = 'AUTOMATION' OR automation_rule_id IS NULL),
    -- SYSTEM has no principal; naming one attributes platform activity to a person. USER, SERVICE
    -- and AUTOMATION all have one, and without it the per-principal transition rate that
    -- SEC-PLT-005 depends on has a hole in it.
    CONSTRAINT ck_wist__actor_principal CHECK
        ((actor_type = 'SYSTEM' AND actor_id IS NULL)
         OR (actor_type <> 'SYSTEM' AND actor_id IS NOT NULL)),
    CONSTRAINT ck_wist__duration_non_negative CHECK
        (duration_in_previous_state_seconds IS NULL OR duration_in_previous_state_seconds >= 0),
    -- The creation entry, and only it, has no prior state.
    CONSTRAINT ck_wist__creation_has_no_prior CHECK
        ((sequence = 1 AND from_state_id IS NULL AND duration_in_previous_state_seconds IS NULL)
         OR (sequence > 1 AND from_state_id IS NOT NULL)),
    CONSTRAINT ck_wist__blocking_attribution CHECK
        (blocking_attribution IS NULL OR blocking_attribution IN
            ('REQUESTER_READINESS', 'THIRD_PARTY', 'ENVIRONMENT', 'SCOPE_CHANGE', 'CAPACITY',
             'EXTERNAL_DEPENDENCY'))
) PARTITION BY RANGE (transitioned_at);

SELECT apply_tenant_isolation('work_item_state_transition');

CREATE OR REPLACE FUNCTION ensure_transition_log_partitions(lead_months int DEFAULT 3) RETURNS int
    LANGUAGE plpgsql
AS $$
DECLARE
    m         date;
    created   int := 0;
    part_name text;
BEGIN
    IF lead_months < 1 THEN
        RAISE EXCEPTION 'lead time must be at least one month (OPS-DEP-011)';
    END IF;

    FOR i IN 0..lead_months LOOP
        m := date_trunc('month', now())::date + (i || ' months')::interval;
        part_name := 'work_item_state_transition_' || to_char(m, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF work_item_state_transition '
            'FOR VALUES FROM (%L) TO (%L)',
            part_name, m, (m + interval '1 month')::date);
        -- Isolation is not inherited by a partition; without this call every month's new partition
        -- is a fresh cross-tenant read path. See the note in apply_tenant_isolation.
        PERFORM apply_tenant_isolation(format('%I', part_name)::regclass);
        created := created + 1;
    END LOOP;

    RETURN created;
END
$$;

GRANT EXECUTE ON FUNCTION ensure_transition_log_partitions(int) TO migration_runner;

SELECT ensure_transition_log_partitions(3);

-- INV-WRK-04. The grants below already withhold UPDATE and DELETE; this trigger is the second
-- mechanism DOC-04 section 16.3 asks for, "so that a privilege misconfiguration does not silently
-- permit modification". The data cannot be reconstructed later, which is what makes belt and braces
-- proportionate here and not elsewhere.
CREATE OR REPLACE FUNCTION reject_transition_log_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'the work item transition log is append-only (INV-WRK-04, PRD-WRK-011). "How many items were '
        'in remediation at the end of last quarter" is answerable only from this table; it is not '
        'derivable from current state with a modification timestamp. A % here would remove an answer '
        'that cannot be recovered.', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END
$$;

DROP TRIGGER IF EXISTS trg_wist__append_only ON work_item_state_transition;
CREATE TRIGGER trg_wist__append_only
    BEFORE UPDATE OR DELETE ON work_item_state_transition
    FOR EACH ROW EXECUTE FUNCTION reject_transition_log_change();

CREATE INDEX IF NOT EXISTS ix_wist__state_occupancy
    ON work_item_state_transition (tenant_id, transitioned_at, to_state_id);

COMMENT ON INDEX ix_wist__state_occupancy IS
    'Serves: cumulative flow — state occupancy over time, the view that distinguishes a security-team '
    'bottleneck from an engineering one (DOC-04 section 16.3).';

CREATE INDEX IF NOT EXISTS ix_wist__actor
    ON work_item_state_transition (tenant_id, actor_id, transitioned_at DESC);

COMMENT ON INDEX ix_wist__actor IS
    'Serves: per-principal transition rate — gaming detection (SEC-PLT-005).';

CREATE INDEX IF NOT EXISTS ix_wist__blocking
    ON work_item_state_transition (tenant_id, blocking_attribution, transitioned_at)
    WHERE blocking_attribution IS NOT NULL;

COMMENT ON INDEX ix_wist__blocking IS
    'Serves: breach attribution reporting (PRD-CAP-009). Partial, because most transitions are not '
    'blocked and an unblocked row in this index is dead weight on the hottest write path.';

-- -----------------------------------------------------------------------------
-- 4. comment and comment_revision — DOC-04 section 16.4
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS comment (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid        NOT NULL,
    work_item_id   uuid        NOT NULL REFERENCES work_item (id) ON DELETE RESTRICT,
    thread_root_id uuid        REFERENCES comment (id) ON DELETE RESTRICT,
    -- Constrained rich text (INV-WRK-10). Stored as the node document, not as markup: the allowlist
    -- works because content arrives as nodes and the renderer emits markup.
    body           jsonb       NOT NULL,
    body_format    text        NOT NULL DEFAULT 'ASPM_RICH_TEXT_V1',
    mentioned_principal_ids uuid[] NOT NULL DEFAULT '{}',
    is_redacted    bool        NOT NULL DEFAULT false,
    redacted_by    uuid,
    redacted_at    timestamptz,
    redaction_reason text,
    edit_count     int         NOT NULL DEFAULT 0,
    author_id      uuid        NOT NULL,
    -- DOC-26 section 8's abuse case: the capability that preserves history could fabricate a record
    -- of a decision never made. The flag lives on the comment rather than on the import session
    -- because it must survive into every presentation.
    migrated_from_external_id text,
    is_migrated    bool        NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    row_version    int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_comment__redaction_attributed CHECK
        (NOT is_redacted OR (redacted_by IS NOT NULL AND redacted_at IS NOT NULL
                             AND redaction_reason IS NOT NULL AND redaction_reason <> '')),
    CONSTRAINT ck_comment__migration_identified CHECK
        (NOT is_migrated OR migrated_from_external_id IS NOT NULL),
    CONSTRAINT ck_comment__no_external_id_without_flag CHECK
        (is_migrated OR migrated_from_external_id IS NULL),
    CONSTRAINT ck_comment__not_own_thread_root CHECK (thread_root_id IS NULL OR thread_root_id <> id),
    CONSTRAINT ck_comment__edit_count_non_negative CHECK (edit_count >= 0)
);

SELECT apply_tenant_isolation('comment');

CREATE TABLE IF NOT EXISTS comment_revision (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id  uuid        NOT NULL,
    comment_id uuid        NOT NULL REFERENCES comment (id) ON DELETE RESTRICT,
    revision   int         NOT NULL,
    body       jsonb       NOT NULL,
    edited_by  uuid        NOT NULL,
    edited_at  timestamptz NOT NULL,

    CONSTRAINT uq_comment_revision__number UNIQUE (tenant_id, comment_id, revision),
    CONSTRAINT ck_comment_revision__positive CHECK (revision >= 1)
);

SELECT apply_tenant_isolation('comment_revision');

-- INV-WRK-08. A comment thread on a security finding is audit evidence, and selective deletion
-- permits reconstruction of a different history. Removal is redaction, which leaves a visible
-- record; a revision is never rewritten, because the retained original is what makes editing safe
-- to permit at all.
CREATE OR REPLACE FUNCTION reject_comment_removal() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_TABLE_NAME = 'comment_revision' THEN
        RAISE EXCEPTION
            'comment revisions are append-only (INV-WRK-08). The retained original is what makes an '
            'edit safe to permit: without it, editing is deletion plus insertion.'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RAISE EXCEPTION
        'comments are never hard-deletable (INV-WRK-08). A comment thread on a security finding is '
        'audit evidence, and selective deletion permits reconstruction of a different history. Use '
        'redaction, which sets is_redacted with a reason and keeps the original in comment_revision.'
        USING ERRCODE = 'integrity_constraint_violation';
END
$$;

DROP TRIGGER IF EXISTS trg_comment__no_delete ON comment;
CREATE TRIGGER trg_comment__no_delete
    BEFORE DELETE ON comment
    FOR EACH ROW EXECUTE FUNCTION reject_comment_removal();

DROP TRIGGER IF EXISTS trg_comment_revision__append_only ON comment_revision;
CREATE TRIGGER trg_comment_revision__append_only
    BEFORE UPDATE OR DELETE ON comment_revision
    FOR EACH ROW EXECUTE FUNCTION reject_comment_removal();

-- A redaction is not reversible, and the migration flag is not editable. Un-redacting would restore
-- content a redactor removed for a stated reason; clearing is_migrated would launder an imported
-- comment into one apparently written here, which is DOC-26 section 8's abuse case exactly.
CREATE OR REPLACE FUNCTION reject_comment_provenance_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.is_redacted AND NOT NEW.is_redacted THEN
        RAISE EXCEPTION
            'a redaction is not reversible (INV-WRK-08); it removed content for a stated reason and '
            'the reason is part of the record'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.is_migrated IS DISTINCT FROM OLD.is_migrated
    OR NEW.migrated_from_external_id IS DISTINCT FROM OLD.migrated_from_external_id
    OR NEW.author_id IS DISTINCT FROM OLD.author_id THEN
        RAISE EXCEPTION
            'authorship and migration provenance are immutable (DOC-26 section 8). Clearing '
            'is_migrated would launder an imported comment into one apparently written here, which '
            'is how the capability that preserves history fabricates a decision never made.'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_comment__provenance_immutable ON comment;
CREATE TRIGGER trg_comment__provenance_immutable
    BEFORE UPDATE ON comment
    FOR EACH ROW EXECUTE FUNCTION reject_comment_provenance_change();

CREATE INDEX IF NOT EXISTS ix_comment__work_item
    ON comment (tenant_id, work_item_id, created_at);

COMMENT ON INDEX ix_comment__work_item IS
    'Serves: the comment thread — loaded with every item view (DOC-04 section 16.4).';

CREATE INDEX IF NOT EXISTS ix_comment__thread
    ON comment (tenant_id, thread_root_id, created_at) WHERE thread_root_id IS NOT NULL;

COMMENT ON INDEX ix_comment__thread IS 'Serves: threaded replies.';

CREATE INDEX IF NOT EXISTS ix_comment__mentions
    ON comment USING gin (mentioned_principal_ids);

COMMENT ON INDEX ix_comment__mentions IS
    'Serves: "comments mentioning me" — the notification and inbox path (PRD-WRK-019).';

CREATE INDEX IF NOT EXISTS ix_comment__author
    ON comment (tenant_id, author_id, created_at DESC);

COMMENT ON INDEX ix_comment__author IS
    'Serves: author history, and migration verification — "which comments does this import claim '
    'this person wrote" is the query that checks DOC-26 section 8''s control.';

-- -----------------------------------------------------------------------------
-- 5. Participation, watchers, read state — DOC-04 section 16.5
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS work_item_participant (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid        NOT NULL,
    work_item_id uuid        NOT NULL REFERENCES work_item (id) ON DELETE RESTRICT,
    principal_id uuid        NOT NULL,
    role         text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_wip__participation UNIQUE (tenant_id, work_item_id, principal_id, role),
    CONSTRAINT ck_wip__role CHECK (role IN ('LEAD', 'SUPPORT', 'REVIEWER', 'SHADOW'))
);

SELECT apply_tenant_isolation('work_item_participant');

CREATE INDEX IF NOT EXISTS ix_wip__principal
    ON work_item_participant (tenant_id, principal_id);

COMMENT ON INDEX ix_wip__principal IS
    'Serves: "items I participate in" — the participant licence tier''s primary view (LIC-PLT-002).';

-- Seventh and eighth primary key exceptions (DOC-04 Part 2): a pure membership fact with no
-- identity beyond its parents.
CREATE TABLE IF NOT EXISTS work_item_watcher (
    tenant_id     uuid        NOT NULL,
    work_item_id  uuid        NOT NULL REFERENCES work_item (id) ON DELETE RESTRICT,
    principal_id  uuid        NOT NULL,
    subscribed_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, work_item_id, principal_id)
);

SELECT apply_tenant_isolation('work_item_watcher');

CREATE INDEX IF NOT EXISTS ix_watcher__principal
    ON work_item_watcher (tenant_id, principal_id);

COMMENT ON INDEX ix_watcher__principal IS
    'Serves: "items I watch" — the personal view and notification fan-out.';

CREATE TABLE IF NOT EXISTS work_item_read_state (
    tenant_id    uuid        NOT NULL,
    work_item_id uuid        NOT NULL REFERENCES work_item (id) ON DELETE RESTRICT,
    principal_id uuid        NOT NULL,
    last_read_at timestamptz NOT NULL,

    PRIMARY KEY (tenant_id, work_item_id, principal_id)
);

SELECT apply_tenant_isolation('work_item_read_state');

CREATE INDEX IF NOT EXISTS ix_read_state__principal
    ON work_item_read_state (tenant_id, principal_id, last_read_at);

COMMENT ON INDEX ix_read_state__principal IS
    'Serves: unread computation for the inbox (PRD-WRK-019). DOC-04 section 16.5 notes this is the '
    'highest-frequency write in the platform at Extra large, excluded from audit, and a candidate '
    'for a write-behind cache — which the monotonic mark below makes safe.';

-- The mark never moves backwards. Two tabs reading in either order must not resurrect notifications
-- the user has already dismissed, and a write-behind cache replaying an out-of-order batch would do
-- exactly that without this.
CREATE OR REPLACE FUNCTION keep_read_mark_monotonic() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.last_read_at < OLD.last_read_at THEN
        NEW.last_read_at := OLD.last_read_at;
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_read_state__monotonic ON work_item_read_state;
CREATE TRIGGER trg_read_state__monotonic
    BEFORE UPDATE ON work_item_read_state
    FOR EACH ROW EXECUTE FUNCTION keep_read_mark_monotonic();

-- -----------------------------------------------------------------------------
-- 6. work_item_link — DOC-04 section 16.6
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS work_item_link (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid        NOT NULL,
    from_item_id uuid        NOT NULL REFERENCES work_item (id) ON DELETE RESTRICT,
    to_item_id   uuid        NOT NULL REFERENCES work_item (id) ON DELETE RESTRICT,
    link_type    text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,

    CONSTRAINT uq_work_item_link__pair UNIQUE (tenant_id, from_item_id, to_item_id, link_type),
    CONSTRAINT ck_work_item_link__not_self CHECK (from_item_id <> to_item_id),
    -- Both directions are STORED (INV-WRK-07), so both are enumerated here. Deriving the inverse at
    -- read time would cost an OR across two columns on the blocked-work queue, which is a frequent
    -- read (DOC-04 section 16.6).
    CONSTRAINT ck_work_item_link__type CHECK (link_type IN
        ('BLOCKS', 'IS_BLOCKED_BY', 'RELATES_TO', 'DUPLICATES', 'IS_DUPLICATED_BY',
         'CAUSED_BY', 'CAUSES'))
);

SELECT apply_tenant_isolation('work_item_link');

-- INV-WRK-07 at the engine. DEFERRABLE INITIALLY DEFERRED because the pair is written as two
-- statements: an immediate check would reject the intermediate state that the transaction corrects,
-- which is the same reasoning V007 gives for INV-VUL-26.
CREATE OR REPLACE FUNCTION require_link_inverse() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    expected_inverse text;
BEGIN
    expected_inverse := CASE NEW.link_type
        WHEN 'BLOCKS'           THEN 'IS_BLOCKED_BY'
        WHEN 'IS_BLOCKED_BY'    THEN 'BLOCKS'
        WHEN 'RELATES_TO'       THEN 'RELATES_TO'
        WHEN 'DUPLICATES'       THEN 'IS_DUPLICATED_BY'
        WHEN 'IS_DUPLICATED_BY' THEN 'DUPLICATES'
        WHEN 'CAUSED_BY'        THEN 'CAUSES'
        WHEN 'CAUSES'           THEN 'CAUSED_BY'
    END;

    IF NOT EXISTS (
        SELECT 1 FROM work_item_link l
         WHERE l.tenant_id    = NEW.tenant_id
           AND l.from_item_id = NEW.to_item_id
           AND l.to_item_id   = NEW.from_item_id
           AND l.link_type    = expected_inverse
    ) THEN
        RAISE EXCEPTION
            'link %->% of type % has no % inverse (INV-WRK-07). A link present in one direction only '
            'is worse than no link: the blocked-work queue would miss it while the item view showed '
            'it, and neither could be shown to be wrong.',
            NEW.from_item_id, NEW.to_item_id, NEW.link_type, expected_inverse
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END
$$;

DROP TRIGGER IF EXISTS trg_work_item_link__inverse ON work_item_link;
CREATE CONSTRAINT TRIGGER trg_work_item_link__inverse
    AFTER INSERT ON work_item_link
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_link_inverse();

CREATE INDEX IF NOT EXISTS ix_work_link__from
    ON work_item_link (tenant_id, from_item_id, link_type);

COMMENT ON INDEX ix_work_link__from IS 'Serves: outgoing links on an item view.';

CREATE INDEX IF NOT EXISTS ix_work_link__to
    ON work_item_link (tenant_id, to_item_id, link_type);

COMMENT ON INDEX ix_work_link__to IS
    'Serves: incoming links, and the blocked-work queue (PRD-CAP-015).';

-- -----------------------------------------------------------------------------
-- 7. automation_rule and automation_execution — DOC-04 section 16.7
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS automation_rule (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid        NOT NULL,
    name         text        NOT NULL,
    trigger_kind text        NOT NULL,
    conditions   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    actions      jsonb       NOT NULL,
    -- INV-WRK-13. Without this column a rule is "a privilege escalation mechanism that no access
    -- review would detect" (DOC-03 section 13.2) — NOT NULL is the whole control's foundation.
    owning_principal_id uuid NOT NULL,
    -- SEC-AUZ-038. Stored rather than computed per execution: computing it would mean an
    -- authorization evaluation per rule per trigger. The flag can be stale by up to NFR-SEC-002's
    -- sixty seconds, and the execution-time ceiling is the backstop that makes that survivable.
    authority_suspended bool NOT NULL DEFAULT false,
    suspended_reason text,
    execution_budget_per_trigger int NOT NULL DEFAULT 50,
    is_enabled   bool        NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   uuid,
    row_version  int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_automation_rule__trigger CHECK (trigger_kind IN
        ('WORK_ITEM_CREATED', 'WORK_ITEM_TRANSITIONED', 'WORK_ITEM_FIELD_CHANGED', 'WORK_ITEM_ASSIGNED',
         'COMMENT_POSTED', 'FINDING_INGESTED', 'SERVICE_LEVEL_THRESHOLD_REACHED', 'SCHEDULE')),
    CONSTRAINT ck_automation_rule__suspension_explained CHECK
        (NOT authority_suspended OR (suspended_reason IS NOT NULL AND suspended_reason <> '')),
    -- A suspended rule that is still enabled means two flags must agree for it to be safe, and any
    -- read path checking only one would run it.
    CONSTRAINT ck_automation_rule__suspended_not_enabled CHECK
        (NOT authority_suspended OR NOT is_enabled),
    CONSTRAINT ck_automation_rule__budget CHECK (execution_budget_per_trigger >= 1),
    CONSTRAINT ck_automation_rule__has_actions CHECK (jsonb_array_length(actions) >= 1)
);

SELECT apply_tenant_isolation('automation_rule');

CREATE INDEX IF NOT EXISTS ix_automation_rule__trigger
    ON automation_rule (tenant_id, trigger_kind)
    WHERE is_enabled AND NOT authority_suspended;

COMMENT ON INDEX ix_automation_rule__trigger IS
    'Serves: rule resolution on every trigger — must be fast because it runs on every state change '
    '(DOC-04 section 16.7). The partial predicate is what keeps disabled and suspended rules off the '
    'hot path entirely rather than filtered from it.';

CREATE TABLE IF NOT EXISTS automation_execution (
    id                uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id         uuid        NOT NULL,
    rule_id           uuid        NOT NULL,
    trigger_event_id  uuid,
    actions_attempted int         NOT NULL,
    actions_succeeded int         NOT NULL,
    -- Authority ceiling rejections. "A rule repeatedly attempting actions its owner cannot perform
    -- is either a misconfiguration or an escalation attempt. Counting them makes it visible; without
    -- the column the denials are invisible because the rule appears to run" (DOC-04 section 16.7).
    actions_denied    int         NOT NULL DEFAULT 0,
    denial_reasons    text[]      NOT NULL DEFAULT '{}',
    loop_depth        int         NOT NULL DEFAULT 0,
    executed_at       timestamptz NOT NULL,

    PRIMARY KEY (tenant_id, id, executed_at),

    CONSTRAINT ck_automation_exec__counts CHECK
        (actions_attempted >= 0 AND actions_succeeded >= 0 AND actions_denied >= 0
         AND actions_succeeded + actions_denied <= actions_attempted),
    -- A denial without a reason is undiagnosable, and these denials are the escalation-attempt
    -- signal of SEC-AUZ-037.
    CONSTRAINT ck_automation_exec__denials_explained CHECK
        (actions_denied = coalesce(array_length(denial_reasons, 1), 0)),
    CONSTRAINT ck_automation_exec__loop_depth CHECK (loop_depth >= 0)
) PARTITION BY RANGE (executed_at);

SELECT apply_tenant_isolation('automation_execution');

CREATE OR REPLACE FUNCTION ensure_automation_execution_partitions(lead_months int DEFAULT 3)
    RETURNS int
    LANGUAGE plpgsql
AS $$
DECLARE
    m         date;
    created   int := 0;
    part_name text;
BEGIN
    IF lead_months < 1 THEN
        RAISE EXCEPTION 'lead time must be at least one month (OPS-DEP-011)';
    END IF;

    FOR i IN 0..lead_months LOOP
        m := date_trunc('month', now())::date + (i || ' months')::interval;
        part_name := 'automation_execution_' || to_char(m, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF automation_execution '
            'FOR VALUES FROM (%L) TO (%L)',
            part_name, m, (m + interval '1 month')::date);
        PERFORM apply_tenant_isolation(format('%I', part_name)::regclass);
        created := created + 1;
    END LOOP;

    RETURN created;
END
$$;

GRANT EXECUTE ON FUNCTION ensure_automation_execution_partitions(int) TO migration_runner;

SELECT ensure_automation_execution_partitions(3);

CREATE INDEX IF NOT EXISTS ix_automation_exec__rule
    ON automation_execution (tenant_id, rule_id, executed_at DESC);

COMMENT ON INDEX ix_automation_exec__rule IS 'Serves: execution history; rule debugging.';

CREATE INDEX IF NOT EXISTS ix_automation_exec__denied
    ON automation_execution (tenant_id, executed_at DESC) WHERE actions_denied > 0;

COMMENT ON INDEX ix_automation_exec__denied IS
    'Serves: authority-ceiling rejections — the escalation-attempt signal (SEC-AUZ-037).';

-- -----------------------------------------------------------------------------
-- 8. saved_view — DOC-04 section 16.8
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS saved_view (
    id                 uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id          uuid        NOT NULL,
    name               text        NOT NULL,
    owner_principal_id uuid        NOT NULL,
    -- FILTERS ONLY. There is deliberately no stored result set and no stored scope: "Storing the
    -- author's scope with the query would make a shared link carry the author's visibility — a scope
    -- escalation available to anyone with the link" (DOC-04 section 16.8, INV-WRK-11).
    query_definition   jsonb       NOT NULL,
    sharing            text        NOT NULL DEFAULT 'PRIVATE',
    -- Who may OPEN the view. Never what it returns — the viewer's scope decides that.
    shared_scope_node_id uuid,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    row_version        int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_saved_view__sharing CHECK
        (sharing IN ('PRIVATE', 'SHARED_TENANT', 'SHARED_SCOPE')),
    CONSTRAINT ck_saved_view__scope_node CHECK
        ((sharing = 'SHARED_SCOPE' AND shared_scope_node_id IS NOT NULL)
         OR (sharing <> 'SHARED_SCOPE' AND shared_scope_node_id IS NULL))
);

SELECT apply_tenant_isolation('saved_view');

CREATE INDEX IF NOT EXISTS ix_saved_view__owner
    ON saved_view (tenant_id, owner_principal_id);

COMMENT ON INDEX ix_saved_view__owner IS 'Serves: a principal''s own views in the navigation.';

CREATE INDEX IF NOT EXISTS ix_saved_view__shared
    ON saved_view (tenant_id, sharing) WHERE sharing <> 'PRIVATE';

COMMENT ON INDEX ix_saved_view__shared IS
    'Serves: the shared-view list. Partial, because private views are the majority and never appear '
    'in it.';

-- -----------------------------------------------------------------------------
-- 9. Conformance
-- -----------------------------------------------------------------------------

-- Links missing their inverse. Should always be empty — the constraint trigger makes it
-- unrepresentable — and asserted anyway, because a trigger added after data exists validates
-- nothing retroactively unless somebody asks it to.
CREATE OR REPLACE FUNCTION links_without_inverse()
    RETURNS TABLE (from_item_id uuid, to_item_id uuid, link_type text)
    LANGUAGE sql STABLE
AS $$
    SELECT l.from_item_id, l.to_item_id, l.link_type
      FROM work_item_link l
     WHERE NOT EXISTS (
        SELECT 1 FROM work_item_link inv
         WHERE inv.tenant_id    = l.tenant_id
           AND inv.from_item_id = l.to_item_id
           AND inv.to_item_id   = l.from_item_id
           AND inv.link_type    = CASE l.link_type
                WHEN 'BLOCKS'           THEN 'IS_BLOCKED_BY'
                WHEN 'IS_BLOCKED_BY'    THEN 'BLOCKS'
                WHEN 'RELATES_TO'       THEN 'RELATES_TO'
                WHEN 'DUPLICATES'       THEN 'IS_DUPLICATED_BY'
                WHEN 'IS_DUPLICATED_BY' THEN 'DUPLICATES'
                WHEN 'CAUSED_BY'        THEN 'CAUSES'
                WHEN 'CAUSES'           THEN 'CAUSED_BY'
           END);
$$;

GRANT EXECUTE ON FUNCTION links_without_inverse()
    TO migration_runner, integrity_verifier, app_runtime;

-- Transition logs with a gap in their sequence. A gap means a transition was lost, and every
-- duration after it is wrong by the missing interval while still looking arithmetically sound —
-- which is the corruption nothing downstream detects.
CREATE OR REPLACE FUNCTION transition_log_sequence_gaps()
    RETURNS TABLE (work_item_id uuid, expected_max int, actual_count int)
    LANGUAGE sql STABLE
AS $$
    SELECT t.work_item_id, max(t.sequence)::int, count(*)::int
      FROM work_item_state_transition t
     GROUP BY t.work_item_id
    HAVING max(t.sequence) <> count(*) OR min(t.sequence) <> 1;
$$;

GRANT EXECUTE ON FUNCTION transition_log_sequence_gaps()
    TO migration_runner, integrity_verifier, app_runtime;

-- -----------------------------------------------------------------------------
-- 10. Grants
--
-- No DELETE anywhere for app_runtime. Three tables also withhold UPDATE:
--
--   work_item_state_transition  INV-WRK-04, and the data cannot be reconstructed
--   comment_revision            INV-WRK-08, the retained original
--   automation_execution        the record of what a rule attempted, including its denials
--
-- comment DOES take UPDATE, because redaction and edit are updates. The triggers above are what
-- confine those updates to the permitted shapes; the grant is what makes the triggers the only
-- thing standing there.
-- -----------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON work_item_type, workflow_definition, workflow_state,
    workflow_transition, work_item, comment, work_item_participant, work_item_watcher,
    work_item_read_state, work_item_link, automation_rule, saved_view TO app_runtime;
GRANT SELECT, INSERT ON work_item_state_transition, comment_revision, automation_execution
    TO app_runtime;
-- Participation, watching and read state are current state rather than a record of what happened —
-- who WAS a watcher at a past moment is an audit-log question — so removal is permitted here and
-- nowhere else in this migration.
GRANT DELETE ON work_item_participant, work_item_watcher, work_item_read_state, work_item_link,
    saved_view TO app_runtime;

GRANT SELECT ON work_item_type, workflow_definition, workflow_state, workflow_transition,
    work_item, work_item_state_transition, comment, comment_revision, work_item_participant,
    work_item_watcher, work_item_read_state, work_item_link, automation_rule, automation_execution,
    saved_view TO integrity_verifier;

GRANT SELECT, INSERT, UPDATE, DELETE ON work_item_type, workflow_definition, workflow_state,
    workflow_transition, work_item, work_item_participant, work_item_watcher, work_item_read_state,
    work_item_link, automation_rule, saved_view TO migration_runner;
-- migration_runner gets DELETE on the append-only tables so a partition-level retention operation
-- is available; the row triggers still reject a single-row delete, which is the case that matters.
-- Note the retention DIFFERENCE recorded in this file's header: transition log partitions are
-- ARCHIVED, not dropped.
GRANT SELECT, INSERT, UPDATE, DELETE ON work_item_state_transition, comment, comment_revision,
    automation_execution TO migration_runner;
