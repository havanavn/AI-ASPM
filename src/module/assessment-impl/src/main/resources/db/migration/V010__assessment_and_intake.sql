-- =============================================================================
-- V010 — assessment, intake, checklists, evidence, external grants
--
-- Owner: module/assessment. DOC-04 sections 12.1 to 12.11, DOC-03 section 9, DOC-09 sections 4, 5
-- and 14.1.
--
-- Five constraints here are the engine half of invariants the domain also enforces. Each is listed
-- with the bypass path that makes the duplication worth its cost, because "enforce it twice" is a
-- reflex worth resisting where the second enforcement buys nothing:
--
--   INV-ASM-03  credential_ref is a vault reference, not a value. The bypass is a developer pasting
--               a password into the field during integration testing and the value reaching
--               production (DOC-04 section 12.3). A crude CHECK catches the common case.
--   INV-ASM-05  a protective control needs an arranged bypass. DOC-04 section 12.4 calls this "the
--               highest-return field pair in the intake surface" and requires it unrepresentable
--               "rather than merely validated" so an API client or a migration cannot bypass it.
--   INV-ASM-13  NOT_APPLICABLE requires a reason. The bypass is a bulk update at the close of an
--               engagement, which is exactly when the pressure to inflate coverage exists.
--   INV-ASM-17  a published checklist version is immutable. The bypass is an administrator "fixing
--               a typo" in a live definition and silently changing the meaning of every completed
--               assessment that used it.
--   INV-ASM-21  the availability/verdict relationship. Three CHECKs make the intermediate states
--               unrepresentable, so no code path can produce AVAILABLE evidence that was never
--               scanned.
--
-- What is deliberately NOT here: INV-ASM-22's export exclusion, which "is enforced where those
-- artifacts are produced" (DOC-04 section 12.10). A column constraint cannot express "absent from
-- every export"; putting a flag here would read as enforcement and be none.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. assessment_type and request_group — DOC-04 sections 12.1, 12.5
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS assessment_type (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    -- Nullable: null means platform-supplied (DOC-03 section 9.1). The row is still isolated,
    -- because a null tenant_id fails the RLS predicate — platform types are read through a
    -- separate path rather than by weakening the policy.
    tenant_id      uuid        NOT NULL,
    code           text        NOT NULL,
    label_i18n     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    payload_schema jsonb       NOT NULL DEFAULT '{}'::jsonb,
    workflow_definition_id uuid,
    requires_request bool      NOT NULL DEFAULT true,
    lifecycle_state text       NOT NULL DEFAULT 'ACTIVE',
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    updated_by     uuid,
    row_version    int         NOT NULL DEFAULT 1,

    CONSTRAINT uq_assessment_type__code UNIQUE (tenant_id, code),
    CONSTRAINT ck_assessment_type__lifecycle CHECK (lifecycle_state IN ('ACTIVE', 'DEPRECATED'))
);

SELECT apply_tenant_isolation('assessment_type');

CREATE TABLE IF NOT EXISTS request_group (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id  uuid        NOT NULL,
    group_code text        NOT NULL,
    title      text        NOT NULL,
    coordinating_principal_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,

    CONSTRAINT uq_request_group__code UNIQUE (tenant_id, group_code)
);

SELECT apply_tenant_isolation('request_group');

-- -----------------------------------------------------------------------------
-- 2. assessment_request — DOC-04 section 12.2
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS assessment_request (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL,
    request_code  text        NOT NULL,
    type_id       uuid        NOT NULL REFERENCES assessment_type (id) ON DELETE RESTRICT,
    group_id      uuid        REFERENCES request_group (id) ON DELETE RESTRICT,
    -- INV-ASM-06: exactly one org node. A single column, so multi-project work has to be a group of
    -- one request per project rather than a list that grows a second owner.
    requested_org_node_id uuid NOT NULL,
    state         text        NOT NULL DEFAULT 'DRAFT',
    classification jsonb      NOT NULL DEFAULT '{}'::jsonb,
    technical_profile jsonb   NOT NULL DEFAULT '{}'::jsonb,
    -- Readiness (INV-ASM-04). Four booleans plus the attestation, so an incomplete readiness names
    -- what is missing rather than being a single opaque flag.
    readiness_environment_available bool NOT NULL DEFAULT false,
    readiness_accounts_provisioned  bool NOT NULL DEFAULT false,
    readiness_data_seeded           bool NOT NULL DEFAULT false,
    readiness_contact_available     bool NOT NULL DEFAULT false,
    readiness_attested_at   timestamptz,
    readiness_attested_by   uuid,
    -- INV-ASM-08: derived, never set by a client. No API surface writes these columns; the
    -- estimation job does.
    derived_priority_score  int,
    derived_effort_days     numeric(6,2),
    derived_feasible_start  date,
    derived_model_version   int,
    -- INV-ASM-09.
    is_retest             bool        NOT NULL DEFAULT false,
    prior_assessment_id   uuid,
    revision_identifier   text,

    -- Scope descriptor columns, resolved at submission and immutable thereafter (INV-ASM-07).
    scope_node_id         uuid,
    scope_ancestor_path   uuid[],
    scope_node_type_id    uuid,
    scope_criticality_id  uuid,
    scope_hierarchy_ver   bigint,
    scope_resolved_at     timestamptz,

    requested_by  uuid        NOT NULL,
    submitted_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    updated_by    uuid,
    row_version   int         NOT NULL DEFAULT 1,

    CONSTRAINT uq_assessment_request__code UNIQUE (tenant_id, request_code),
    CONSTRAINT ck_assessment_request__state CHECK (state IN
        ('DRAFT', 'SUBMITTED', 'TRIAGED', 'ACCEPTED', 'SCHEDULED', 'IN_ASSESSMENT', 'REJECTED',
         'DEFERRED', 'WITHDRAWN', 'MERGED')),
    -- INV-ASM-07: a submitted request has a resolved scope, and a draft has none. A submitted
    -- request without one would be a request nobody can authorize a read of.
    CONSTRAINT ck_assessment_request__scope_when_submitted CHECK
        (state = 'DRAFT' OR (scope_node_id IS NOT NULL AND scope_resolved_at IS NOT NULL
                             AND submitted_at IS NOT NULL)),
    CONSTRAINT ck_assessment_request__scope_complete CHECK
        (scope_node_id IS NULL
         OR (scope_ancestor_path IS NOT NULL AND scope_node_type_id IS NOT NULL
             AND scope_criticality_id IS NOT NULL AND scope_hierarchy_ver IS NOT NULL)),
    -- INV-ASM-04: acceptance requires complete readiness. Enforced at the engine as well as the
    -- domain because the accept transition is reachable from a migration import of a backlog.
    CONSTRAINT ck_assessment_request__readiness_before_accept CHECK
        (state NOT IN ('ACCEPTED', 'SCHEDULED', 'IN_ASSESSMENT')
         OR (readiness_environment_available AND readiness_accounts_provisioned
             AND readiness_data_seeded AND readiness_contact_available
             AND readiness_attested_at IS NOT NULL AND readiness_attested_by IS NOT NULL)),
    CONSTRAINT ck_assessment_request__attestation_attributed CHECK
        ((readiness_attested_at IS NULL) = (readiness_attested_by IS NULL)),
    -- INV-ASM-09: both references, or neither.
    CONSTRAINT ck_assessment_request__retest CHECK
        ((NOT is_retest AND prior_assessment_id IS NULL AND revision_identifier IS NULL)
         OR (is_retest AND prior_assessment_id IS NOT NULL
             AND revision_identifier IS NOT NULL AND revision_identifier <> '')),
    CONSTRAINT ck_assessment_request__derived_versioned CHECK
        ((derived_priority_score IS NULL AND derived_effort_days IS NULL)
         OR derived_model_version IS NOT NULL)
);

SELECT apply_tenant_isolation('assessment_request');

-- CON-DAT-009 / INV-ASM-07, using the shared primitive from V001.
DROP TRIGGER IF EXISTS trg_assessment_request__scope_immutable ON assessment_request;
CREATE TRIGGER trg_assessment_request__scope_immutable
    BEFORE UPDATE ON assessment_request
    FOR EACH ROW EXECUTE FUNCTION reject_scope_descriptor_change();

CREATE INDEX IF NOT EXISTS ix_assessment_request__queue
    ON assessment_request (tenant_id, state, derived_priority_score DESC)
    WHERE state IN ('SUBMITTED', 'TRIAGED');

COMMENT ON INDEX ix_assessment_request__queue IS
    'Serves: the triage queue, highest priority first. Partial, because the queue is the two states '
    'and everything else is history.';

CREATE INDEX IF NOT EXISTS ix_assessment_request__requester
    ON assessment_request (tenant_id, requested_by, created_at DESC);

COMMENT ON INDEX ix_assessment_request__requester IS
    'Serves: "my requests" — asm.request.read.own, the participant tier''s primary view.';

CREATE INDEX IF NOT EXISTS ix_assessment_request__scope_subtree
    ON assessment_request USING gin (scope_ancestor_path);

COMMENT ON INDEX ix_assessment_request__scope_subtree IS
    'Serves: subtree-scoped request reads — scope_ancestor_path @> ARRAY[n].';

CREATE INDEX IF NOT EXISTS ix_assessment_request__group
    ON assessment_request (tenant_id, group_id) WHERE group_id IS NOT NULL;

COMMENT ON INDEX ix_assessment_request__group IS
    'Serves: the sibling requests of a multi-project engagement (INV-ASM-06).';

-- -----------------------------------------------------------------------------
-- 3. assessment_request_role_account — DOC-04 section 12.3
--
-- A separate table "because INV-ASM-02 is a set assertion and because credential references
-- require their own access control and audit".
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS assessment_request_role_account (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL,
    request_id    uuid        NOT NULL REFERENCES assessment_request (id) ON DELETE RESTRICT,
    role_name     text        NOT NULL,
    role_description text,
    username      text        NOT NULL,
    -- INV-ASM-03. A VAULT REFERENCE, never a value.
    credential_ref text       NOT NULL,
    mfa_enrolled  bool        NOT NULL DEFAULT false,
    mfa_bypass_ref text,
    tenant_or_org_context text,
    expected_permissions text[] NOT NULL DEFAULT '{}',
    account_status text       NOT NULL DEFAULT 'PROVIDED',
    verified_at   timestamptz,
    verified_by   uuid,
    -- INV-ASM-29.
    rotation_required bool    NOT NULL DEFAULT false,
    rotation_attested_at timestamptz,
    rotation_attested_by uuid,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,

    CONSTRAINT uq_asm_role_acct__account UNIQUE (tenant_id, request_id, role_name, username),
    CONSTRAINT ck_asm_role_acct__status CHECK
        (account_status IN ('PROVIDED', 'VERIFIED', 'EXPIRED', 'LOCKED', 'INVALID')),
    -- A blank role name cannot be counted toward the two-per-role rule, so two blank-named accounts
    -- would satisfy INV-ASM-02 while testing nothing.
    CONSTRAINT ck_asm_role_acct__role_named CHECK (role_name <> '' AND username <> ''),
    -- DOC-04 section 12.3's crude but effective guard. "It cannot prevent a determined mistake, but
    -- it catches the common one — a developer pasting a password into the field during integration
    -- testing and the value reaching production." The domain's SecretRef is the real check; this is
    -- defence in depth on the single most sensitive field in the intake surface.
    CONSTRAINT ck_asm_role_acct__credential_is_reference CHECK
        (credential_ref <> '' AND credential_ref ~ '^[a-z][a-z0-9+.-]{1,31}:'),
    CONSTRAINT ck_asm_role_acct__mfa_bypass CHECK
        (NOT mfa_enrolled OR mfa_bypass_ref IS NOT NULL),
    -- Rotation is closed by an attestation, not by the flag being cleared. A flag nobody has to
    -- answer for is a list that grows.
    CONSTRAINT ck_asm_role_acct__rotation_attested CHECK
        ((rotation_attested_at IS NULL) = (rotation_attested_by IS NULL)),
    CONSTRAINT ck_asm_role_acct__rotation_flagged_first CHECK
        (rotation_attested_at IS NULL OR rotation_required)
);

SELECT apply_tenant_isolation('assessment_request_role_account');

CREATE INDEX IF NOT EXISTS ix_asm_role_acct__request
    ON assessment_request_role_account (tenant_id, request_id, role_name);

COMMENT ON INDEX ix_asm_role_acct__request IS
    'Serves: all accounts for a request grouped by role — the INV-ASM-02 count at the accept '
    'transition, and the tester''s working view.';

CREATE INDEX IF NOT EXISTS ix_asm_role_acct__verification_due
    ON assessment_request_role_account (tenant_id, account_status, request_id)
    WHERE account_status IN ('PROVIDED', 'EXPIRED', 'LOCKED');

COMMENT ON INDEX ix_asm_role_acct__verification_due IS
    'Serves: the pre-engagement verification job (PRD-PTR-022).';

CREATE INDEX IF NOT EXISTS ix_asm_role_acct__rotation_due
    ON assessment_request_role_account (tenant_id, request_id)
    WHERE rotation_required AND rotation_attested_at IS NULL;

COMMENT ON INDEX ix_asm_role_acct__rotation_due IS
    'Serves: outstanding credential rotations — the queue that prevents test accounts outliving '
    'their engagement (INV-ASM-29). Every row here is a live credential to a pre-production '
    'environment that nobody has confirmed was rotated.';

-- The INV-ASM-02 conformance query. Returns every request that could not be accepted, with the
-- role that fails, so the gate can be shown to hold over existing data rather than only at the
-- transition that enforces it.
CREATE OR REPLACE FUNCTION requests_failing_two_account_rule()
    RETURNS TABLE (request_id uuid, role_name text, usable_accounts bigint)
    LANGUAGE sql STABLE
AS $$
    SELECT a.request_id, lower(btrim(a.role_name)),
           count(*) FILTER (WHERE a.account_status IN ('PROVIDED', 'VERIFIED'))
      FROM assessment_request_role_account a
     GROUP BY a.request_id, lower(btrim(a.role_name))
    HAVING count(*) FILTER (WHERE a.account_status IN ('PROVIDED', 'VERIFIED')) < 2;
$$;

GRANT EXECUTE ON FUNCTION requests_failing_two_account_rule()
    TO migration_runner, integrity_verifier, app_runtime;

-- -----------------------------------------------------------------------------
-- 4. assessment_request_environment — DOC-04 section 12.4
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS assessment_request_environment (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL,
    request_id    uuid        NOT NULL REFERENCES assessment_request (id) ON DELETE RESTRICT,
    env_type      text        NOT NULL,
    base_url      text        NOT NULL,
    protective_control_present bool NOT NULL DEFAULT false,
    protective_control_vendor  text,
    bypass_arranged bool      NOT NULL DEFAULT false,
    bypass_method text,
    rate_limit_present bool   NOT NULL DEFAULT false,
    rate_limit_threshold text,
    data_destruction_allowed bool NOT NULL DEFAULT false,
    db_reset_available bool   NOT NULL DEFAULT false,
    db_reset_procedure text,
    vpn_required  bool        NOT NULL DEFAULT false,
    vpn_access_procedure text,
    test_window_constraints text,
    monitoring_suppression_arranged bool NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,

    CONSTRAINT ck_asm_req_env__type CHECK
        (env_type IN ('UAT', 'STAGING', 'PREPROD', 'PROD_READONLY')),
    -- INV-ASM-05, at the row level. DOC-04 section 12.4: "the highest-return field pair in the
    -- intake surface: a protective control between the tester and the target produces a test of the
    -- control, and discovering it on day one costs the engagement two days. Making it
    -- unrepresentable rather than merely validated means it cannot be bypassed by an API client or
    -- a migration."
    CONSTRAINT ck_asm_req_env__bypass_arranged CHECK
        (NOT protective_control_present OR bypass_arranged),
    -- An arranged bypass with no method recorded is a claim, not an arrangement.
    CONSTRAINT ck_asm_req_env__bypass_method CHECK
        (NOT bypass_arranged OR (bypass_method IS NOT NULL AND bypass_method <> ''))
);

SELECT apply_tenant_isolation('assessment_request_environment');

CREATE INDEX IF NOT EXISTS ix_asm_req_env__request
    ON assessment_request_environment (tenant_id, request_id);

COMMENT ON INDEX ix_asm_req_env__request IS 'Serves: environments for a request.';

-- -----------------------------------------------------------------------------
-- 5. assessment, conditions, assignments — DOC-04 sections 12.6 to 12.8
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS assessment (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL,
    type_id       uuid        NOT NULL REFERENCES assessment_type (id) ON DELETE RESTRICT,
    request_id    uuid        REFERENCES assessment_request (id) ON DELETE RESTRICT,
    revision_reference text,
    payload       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- Coverage is DERIVED (INV-ASM-11) and stored per DOC-04's P6 materialization principle. The
    -- derivation lives in the domain; these columns are its recorded output, and the conformance
    -- query below is what catches a divergence between the two.
    coverage_items_total          int NOT NULL DEFAULT 0,
    coverage_items_assessed       int NOT NULL DEFAULT 0,
    coverage_items_not_applicable int NOT NULL DEFAULT 0,
    coverage_items_not_assessed   int NOT NULL DEFAULT 0,
    coverage_ratio numeric(5,4),
    -- INV-ASM-12.
    incompleteness_acknowledged bool NOT NULL DEFAULT false,
    incompleteness_reason text,
    outcome       text,
    state         text        NOT NULL DEFAULT 'PLANNED',
    lead_principal_id uuid    NOT NULL,
    effort_derived_days numeric(6,2) NOT NULL DEFAULT 0,
    effort_manual_days  numeric(6,2),
    started_at    timestamptz,
    completed_at  timestamptz,

    -- Scope from the scoped assets' OWNERSHIP (INV-ASM-10), never from the assessor.
    scope_node_id         uuid        NOT NULL,
    scope_ancestor_path   uuid[]      NOT NULL,
    scope_node_type_id    uuid        NOT NULL,
    scope_criticality_id  uuid        NOT NULL,
    scope_hierarchy_ver   bigint      NOT NULL,
    scope_resolved_at     timestamptz NOT NULL,

    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    updated_by    uuid,
    row_version   int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_assessment__state CHECK
        (state IN ('PLANNED', 'IN_PROGRESS', 'BLOCKED', 'AWAITING_REVIEW', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT ck_assessment__coverage_non_negative CHECK
        (coverage_items_total >= 0 AND coverage_items_assessed >= 0
         AND coverage_items_not_applicable >= 0 AND coverage_items_not_assessed >= 0),
    -- The parts must sum to the whole. A total that does not is a coverage figure whose numerator
    -- and denominator came from different populations, which is exactly the failure INV-ASM-18
    -- guards against at the checklist level.
    CONSTRAINT ck_assessment__coverage_sums CHECK
        (coverage_items_assessed + coverage_items_not_applicable + coverage_items_not_assessed
         = coverage_items_total),
    -- INV-ASM-12: a COMPLETED assessment with unassessed items must carry the acknowledgement.
    CONSTRAINT ck_assessment__incompleteness_acknowledged CHECK
        (state <> 'COMPLETED' OR coverage_items_not_assessed = 0
         OR (incompleteness_acknowledged AND incompleteness_reason IS NOT NULL
             AND incompleteness_reason <> '')),
    CONSTRAINT ck_assessment__effort_non_negative CHECK
        (effort_derived_days >= 0 AND (effort_manual_days IS NULL OR effort_manual_days >= 0))
);

SELECT apply_tenant_isolation('assessment');

DROP TRIGGER IF EXISTS trg_assessment__scope_immutable ON assessment;
CREATE TRIGGER trg_assessment__scope_immutable
    BEFORE UPDATE ON assessment
    FOR EACH ROW EXECUTE FUNCTION reject_scope_descriptor_change();

CREATE INDEX IF NOT EXISTS ix_assessment__request
    ON assessment (tenant_id, request_id) WHERE request_id IS NOT NULL;

COMMENT ON INDEX ix_assessment__request IS
    'Serves: the assessment produced by a request — the requester''s view of what happened to it.';

CREATE INDEX IF NOT EXISTS ix_assessment__scope_subtree
    ON assessment USING gin (scope_ancestor_path);

COMMENT ON INDEX ix_assessment__scope_subtree IS 'Serves: subtree-scoped assessment reads.';

CREATE INDEX IF NOT EXISTS ix_assessment__incomplete_completed
    ON assessment (tenant_id, completed_at DESC)
    WHERE state = 'COMPLETED' AND coverage_items_not_assessed > 0;

COMMENT ON INDEX ix_assessment__incomplete_completed IS
    'Serves: completed assessments with acknowledged gaps (INV-ASM-12) — the set a reader must see '
    'before treating "no findings" as "nothing there". PP-1 made queryable.';

CREATE TABLE IF NOT EXISTS assessment_scope_asset (
    tenant_id     uuid NOT NULL,
    assessment_id uuid NOT NULL REFERENCES assessment (id) ON DELETE RESTRICT,
    -- Cross-module, no FK (ADR-030).
    asset_id      uuid NOT NULL,

    PRIMARY KEY (tenant_id, assessment_id, asset_id)
);

SELECT apply_tenant_isolation('assessment_scope_asset');

CREATE INDEX IF NOT EXISTS ix_assessment_scope__asset
    ON assessment_scope_asset (tenant_id, asset_id);

COMMENT ON INDEX ix_assessment_scope__asset IS
    'Serves: "what has been assessed on this asset" — the coverage question asked of an asset '
    'rather than of an assessment.';

CREATE TABLE IF NOT EXISTS assessment_condition (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL,
    assessment_id uuid        NOT NULL REFERENCES assessment (id) ON DELETE RESTRICT,
    statement     text        NOT NULL,
    -- INV-ASM-14: an owner and a date, both mandatory. An ownerless condition has nobody to chase
    -- and a dateless one is never late.
    owner_principal_id uuid   NOT NULL,
    due_by        date        NOT NULL,
    -- Closure is tracked INDEPENDENTLY of the assessment's completion. That is the whole invariant:
    -- "attaching them to the assessment means they close when the assessment does, which is
    -- precisely the failure."
    closed_at     timestamptz,
    closed_by     uuid,
    closure_evidence text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,

    CONSTRAINT ck_assessment_condition__statement CHECK (statement <> ''),
    CONSTRAINT ck_assessment_condition__closure_attributed CHECK
        ((closed_at IS NULL) = (closed_by IS NULL)),
    CONSTRAINT ck_assessment_condition__closure_evidenced CHECK
        (closed_at IS NULL OR (closure_evidence IS NOT NULL AND closure_evidence <> ''))
);

SELECT apply_tenant_isolation('assessment_condition');

CREATE INDEX IF NOT EXISTS ix_assessment_condition__open
    ON assessment_condition (tenant_id, due_by) WHERE closed_at IS NULL;

COMMENT ON INDEX ix_assessment_condition__open IS
    'Serves: open conditions by due date — the chase queue that is the ONLY mechanism closing them '
    '(INV-ASM-14). Deliberately not scoped to an assessment: a condition outlives its assessment.';

CREATE INDEX IF NOT EXISTS ix_assessment_condition__owner
    ON assessment_condition (tenant_id, owner_principal_id) WHERE closed_at IS NULL;

COMMENT ON INDEX ix_assessment_condition__owner IS
    'Serves: "conditions I owe" — without this the owner column is a label nobody acts on.';

-- Conditions outliving a COMPLETED assessment. Not an error — INV-ASM-14 requires exactly this —
-- but the query a governance report needs, because a completed assessment with open conditions is
-- an approval whose terms have not been met.
CREATE OR REPLACE FUNCTION open_conditions_on_completed_assessments()
    RETURNS TABLE (assessment_id uuid, condition_id uuid, owner_principal_id uuid, due_by date)
    LANGUAGE sql STABLE
AS $$
    SELECT c.assessment_id, c.id, c.owner_principal_id, c.due_by
      FROM assessment_condition c
      JOIN assessment a ON a.id = c.assessment_id
     WHERE c.closed_at IS NULL AND a.state = 'COMPLETED';
$$;

GRANT EXECUTE ON FUNCTION open_conditions_on_completed_assessments()
    TO migration_runner, integrity_verifier, app_runtime;

-- -----------------------------------------------------------------------------
-- 6. Checklists — DOC-04 section 12.9
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS checklist_definition (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL,
    code          text        NOT NULL,
    label_i18n    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    version       int         NOT NULL,
    applicability jsonb       NOT NULL DEFAULT '{}'::jsonb,
    state         text        NOT NULL DEFAULT 'DRAFT',
    published_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    updated_by    uuid,
    row_version   int         NOT NULL DEFAULT 1,

    CONSTRAINT uq_checklist_definition__version UNIQUE (tenant_id, code, version),
    CONSTRAINT ck_checklist_definition__state CHECK
        (state IN ('DRAFT', 'PUBLISHED', 'DEPRECATED')),
    CONSTRAINT ck_checklist_definition__version CHECK (version >= 1),
    CONSTRAINT ck_checklist_definition__published_at CHECK
        (state = 'DRAFT' OR published_at IS NOT NULL)
);

SELECT apply_tenant_isolation('checklist_definition');

CREATE TABLE IF NOT EXISTS checklist_item (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL,
    definition_id uuid        NOT NULL REFERENCES checklist_definition (id) ON DELETE RESTRICT,
    group_code    text        NOT NULL,
    item_code     text        NOT NULL,
    statement     text        NOT NULL,
    guidance      text,
    is_mandatory  bool        NOT NULL DEFAULT true,
    display_order int         NOT NULL,

    CONSTRAINT uq_checklist_item__code UNIQUE (tenant_id, definition_id, item_code),
    CONSTRAINT ck_checklist_item__statement CHECK (statement <> '' AND item_code <> '')
);

SELECT apply_tenant_isolation('checklist_item');

CREATE INDEX IF NOT EXISTS ix_checklist_item__definition
    ON checklist_item (tenant_id, definition_id, group_code, display_order);

COMMENT ON INDEX ix_checklist_item__definition IS
    'Serves: the ordered item list for an instance — the assessment working interface.';

-- INV-ASM-17. "Editing a live checklist would silently change the meaning of every completed
-- assessment that used it — an assessment that covered 340 of 351 items would, after an edit adding
-- 20 items, appear to have covered 340 of 371 without anyone having changed the assessment."
--
-- Note the direction of that failure: coverage FALLS, and the team that did the work looks worse,
-- with nothing in the record to explain it.
CREATE OR REPLACE FUNCTION reject_published_checklist_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    definition_state text;
BEGIN
    IF TG_TABLE_NAME = 'checklist_definition' THEN
        IF OLD.state IN ('PUBLISHED', 'DEPRECATED')
           AND (NEW.version IS DISTINCT FROM OLD.version
             OR NEW.published_at IS DISTINCT FROM OLD.published_at
             OR NEW.applicability IS DISTINCT FROM OLD.applicability) THEN
            RAISE EXCEPTION
                'checklist definition %% version % is % and immutable (INV-ASM-17). A change is a new '
                'version; existing instances keep the version they pinned, which is what makes '
                'historical coverage claims interpretable.', OLD.version, OLD.state
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    SELECT d.state INTO definition_state
      FROM checklist_definition d
     WHERE d.id = COALESCE(NEW.definition_id, OLD.definition_id);

    IF definition_state IN ('PUBLISHED', 'DEPRECATED') THEN
        RAISE EXCEPTION
            'the items of a % checklist definition are immutable (INV-ASM-17). Adding one would make '
            'every completed assessment that used this version appear to have covered less, with '
            'nothing in the record to explain it.', definition_state
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END
$$;

DROP TRIGGER IF EXISTS trg_checklist_definition__immutable ON checklist_definition;
CREATE TRIGGER trg_checklist_definition__immutable
    BEFORE UPDATE ON checklist_definition
    FOR EACH ROW EXECUTE FUNCTION reject_published_checklist_change();

-- *** DEFECT FOUND BY RUNNING THE VERIFICATION SUITE. ***
-- An earlier version of this trigger fired on UPDATE OR DELETE only. That left the single most
-- consequential case open: INSERT. Adding an item to a published definition is exactly the failure
-- INV-ASM-17 describes — "an assessment that covered 340 of 351 items would, after an edit adding
-- 20 items, appear to have covered 340 of 371" — and the edit that adds is an INSERT, not an UPDATE.
--
-- The rule read as complete because immutability is usually about changing what is there. Here the
-- damage comes from adding what is not.
DROP TRIGGER IF EXISTS trg_checklist_item__immutable ON checklist_item;
CREATE TRIGGER trg_checklist_item__immutable
    BEFORE INSERT OR UPDATE OR DELETE ON checklist_item
    FOR EACH ROW EXECUTE FUNCTION reject_published_checklist_change();

CREATE TABLE IF NOT EXISTS checklist_instance (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL,
    assessment_id uuid        NOT NULL REFERENCES assessment (id) ON DELETE RESTRICT,
    definition_id uuid        NOT NULL REFERENCES checklist_definition (id) ON DELETE RESTRICT,
    -- Pinned (INV-ASM-18). Denormalized from the definition deliberately: reading the version
    -- through the definition would report today's, which is the whole failure.
    definition_version int    NOT NULL,
    completed_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_checklist_instance__assessment UNIQUE (tenant_id, assessment_id, definition_id)
);

SELECT apply_tenant_isolation('checklist_instance');

CREATE TABLE IF NOT EXISTS checklist_item_result (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id   uuid        NOT NULL,
    instance_id uuid        NOT NULL REFERENCES checklist_instance (id) ON DELETE RESTRICT,
    item_id     uuid        NOT NULL REFERENCES checklist_item (id) ON DELETE RESTRICT,
    outcome     text        NOT NULL DEFAULT 'NOT_ASSESSED',
    reason      text,
    assessed_by uuid,
    assessed_at timestamptz,

    CONSTRAINT uq_checklist_result__item UNIQUE (tenant_id, instance_id, item_id),
    -- INV-ASM-19: four outcomes and no null.
    CONSTRAINT ck_checklist_result__outcome CHECK
        (outcome IN ('PASS', 'FAIL', 'NOT_APPLICABLE', 'NOT_ASSESSED')),
    -- INV-ASM-13 at the row level. "This is the constraint that prevents coverage being inflated
    -- under deadline by marking inconvenient items as inapplicable — the path of least resistance,
    -- and the one that makes assessment coverage meaningless."
    CONSTRAINT ck_checklist_result__na_reasoned CHECK
        (outcome <> 'NOT_APPLICABLE' OR (reason IS NOT NULL AND length(reason) > 0)),
    -- An unattributed PASS is a coverage claim nobody made; an attributed NOT_ASSESSED reads as
    -- work that was done.
    CONSTRAINT ck_checklist_result__attribution CHECK
        ((outcome = 'NOT_ASSESSED' AND assessed_by IS NULL AND assessed_at IS NULL)
         OR (outcome <> 'NOT_ASSESSED' AND assessed_by IS NOT NULL AND assessed_at IS NOT NULL))
);

SELECT apply_tenant_isolation('checklist_item_result');

CREATE INDEX IF NOT EXISTS ix_checklist_result__instance
    ON checklist_item_result (tenant_id, instance_id);

COMMENT ON INDEX ix_checklist_result__instance IS
    'Serves: all results for an instance — coverage computation (INV-ASM-11).';

CREATE INDEX IF NOT EXISTS ix_checklist_result__not_assessed
    ON checklist_item_result (tenant_id, instance_id) WHERE outcome = 'NOT_ASSESSED';

COMMENT ON INDEX ix_checklist_result__not_assessed IS
    'Serves: outstanding items — the completion guard of INV-ASM-12 and the practitioner''s '
    'remaining-work view. Partial, because the interesting set is the uncovered one.';

-- INV-ASM-11 conformance: stored coverage that disagrees with the results it claims to summarise.
-- The domain derives it; this catches a stored value that drifted, which is the failure mode of
-- every materialized aggregate.
CREATE OR REPLACE FUNCTION assessments_with_divergent_coverage()
    RETURNS TABLE (assessment_id uuid, stored_total int, actual_total bigint,
                   stored_not_assessed int, actual_not_assessed bigint)
    LANGUAGE sql STABLE
AS $$
    SELECT a.id, a.coverage_items_total, count(r.id),
           a.coverage_items_not_assessed,
           count(r.id) FILTER (WHERE r.outcome = 'NOT_ASSESSED')
      FROM assessment a
      LEFT JOIN checklist_instance i ON i.assessment_id = a.id
      LEFT JOIN checklist_item_result r ON r.instance_id = i.id
     GROUP BY a.id, a.coverage_items_total, a.coverage_items_not_assessed
    HAVING a.coverage_items_total <> count(r.id)
        OR a.coverage_items_not_assessed <> count(r.id) FILTER (WHERE r.outcome = 'NOT_ASSESSED');
$$;

GRANT EXECUTE ON FUNCTION assessments_with_divergent_coverage()
    TO migration_runner, integrity_verifier, app_runtime;

-- -----------------------------------------------------------------------------
-- 7. evidence — DOC-04 section 12.10
--
-- RESTRICTED unconditionally (INV-ASM-20): there is NO classification column, because a column is
-- a thing that can be lowered. The classification is a property of the table.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS evidence (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL,
    assessment_id uuid        REFERENCES assessment (id) ON DELETE RESTRICT,
    -- Cross-module, no FK (ADR-030).
    finding_id    uuid,
    checklist_item_result_id uuid REFERENCES checklist_item_result (id) ON DELETE RESTRICT,
    -- NO CONTENT IN THE DATABASE. A server-generated object-store reference (INV-ASM-23).
    storage_ref   text        NOT NULL,
    isolated_origin text      NOT NULL,
    declared_media_type text  NOT NULL,
    verified_media_type text,
    byte_size     bigint      NOT NULL,
    content_hash  bytea       NOT NULL,
    malware_verdict text      NOT NULL DEFAULT 'PENDING',
    malware_scanner text,
    malware_scanned_at timestamptz,
    availability  text        NOT NULL DEFAULT 'QUARANTINED',
    -- Metadata only, never usable as a path (INV-ASM-23). It is hostile content (SEC-SEC-029) and
    -- the renderer encodes it.
    original_filename text    NOT NULL,
    retention_until timestamptz NOT NULL,
    destroyed_at  timestamptz,
    uploaded_by   uuid        NOT NULL,
    uploaded_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_evidence__attached CHECK (assessment_id IS NOT NULL OR finding_id IS NOT NULL),
    CONSTRAINT ck_evidence__verdict CHECK
        (malware_verdict IN ('PENDING', 'CLEAN', 'MALICIOUS', 'SCAN_FAILED')),
    CONSTRAINT ck_evidence__availability CHECK
        (availability IN ('QUARANTINED', 'AVAILABLE', 'FLAGGED_AVAILABLE')),
    -- The three-state relationship, made structural. INV-ASM-21 requires flag-rather-than-delete,
    -- and these make the intermediate states unrepresentable rather than merely discouraged.
    CONSTRAINT ck_evidence__available_is_clean CHECK
        (availability <> 'AVAILABLE' OR malware_verdict = 'CLEAN'),
    CONSTRAINT ck_evidence__flagged_is_malicious CHECK
        (availability <> 'FLAGGED_AVAILABLE' OR malware_verdict = 'MALICIOUS'),
    CONSTRAINT ck_evidence__pending_is_quarantined CHECK
        (malware_verdict <> 'PENDING' OR availability = 'QUARANTINED'),
    -- SCAN_FAILED stays quarantined too: an unscannable file is not a clean one, and an encrypted
    -- archive the scanner could not open is exactly the shape a deliberate evasion takes (PP-1).
    CONSTRAINT ck_evidence__scan_failed_is_quarantined CHECK
        (malware_verdict <> 'SCAN_FAILED' OR availability = 'QUARANTINED'),
    -- A verdict whose source is unknown cannot be re-evaluated when the scanner is later found to
    -- have been wrong — and a false positive on pentest evidence is the EXPECTED case.
    CONSTRAINT ck_evidence__verdict_attributed CHECK
        (malware_verdict = 'PENDING'
         OR (malware_scanner IS NOT NULL AND malware_scanner <> '' AND malware_scanned_at IS NOT NULL)),
    CONSTRAINT ck_evidence__byte_size CHECK (byte_size >= 0),
    CONSTRAINT uq_evidence__content UNIQUE (tenant_id, content_hash, assessment_id)
);

SELECT apply_tenant_isolation('evidence');

-- INV-ASM-21 in its strongest form: evidence rows are not deleted, ever. Destruction on
-- retention_until removes the object-store content and marks the row, "so the fact that evidence
-- existed remains auditable" (DOC-04 section 12.10). A deleted row would make a finding's missing
-- proof indistinguishable from proof that never existed.
CREATE OR REPLACE FUNCTION reject_evidence_removal() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'evidence rows are not deleted (INV-ASM-21). Destruction at retention_until removes the '
            'object-store content and marks the row, so the fact that evidence existed remains '
            'auditable — a deleted row makes a finding''s missing proof indistinguishable from proof '
            'that never existed.'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.destroyed_at IS NOT NULL AND NEW.destroyed_at IS NULL THEN
        RAISE EXCEPTION 'destroyed evidence does not come back'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.content_hash IS DISTINCT FROM OLD.content_hash
    OR NEW.storage_ref  IS DISTINCT FROM OLD.storage_ref
    OR NEW.uploaded_by  IS DISTINCT FROM OLD.uploaded_by THEN
        RAISE EXCEPTION
            'the identity and provenance of evidence are immutable. Repointing storage_ref would '
            'substitute one exhibit for another under an unchanged hash and uploader.'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_evidence__no_delete ON evidence;
CREATE TRIGGER trg_evidence__no_delete
    BEFORE UPDATE OR DELETE ON evidence
    FOR EACH ROW EXECUTE FUNCTION reject_evidence_removal();

CREATE INDEX IF NOT EXISTS ix_evidence__assessment
    ON evidence (tenant_id, assessment_id) WHERE assessment_id IS NOT NULL;

COMMENT ON INDEX ix_evidence__assessment IS 'Serves: evidence for an assessment.';

CREATE INDEX IF NOT EXISTS ix_evidence__finding
    ON evidence (tenant_id, finding_id) WHERE finding_id IS NOT NULL;

COMMENT ON INDEX ix_evidence__finding IS
    'Serves: evidence supporting a finding — the dispute and retest path. This is the query that '
    'runs when somebody says the finding is wrong.';

CREATE INDEX IF NOT EXISTS ix_evidence__quarantined
    ON evidence (tenant_id, uploaded_at) WHERE availability = 'QUARANTINED';

COMMENT ON INDEX ix_evidence__quarantined IS
    'Serves: the scan backlog, and the alert where an item has been quarantined longer than '
    'expected — a stuck scanner presents as evidence nobody can retrieve, which reads as a platform '
    'fault rather than a queue.';

CREATE INDEX IF NOT EXISTS ix_evidence__retention_due
    ON evidence (tenant_id, retention_until) WHERE destroyed_at IS NULL;

COMMENT ON INDEX ix_evidence__retention_due IS
    'Serves: expired evidence for destruction — bounded retention as a security control '
    '(INV-ASM-24). Indefinite retention of exploit tooling is an accumulating liability.';

-- -----------------------------------------------------------------------------
-- 8. External assessor grants — DOC-04 section 12.11
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS external_assessor_grant (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid        NOT NULL,
    principal_id uuid        NOT NULL,
    engagement_id uuid       NOT NULL,
    valid_from   timestamptz NOT NULL,
    -- MANDATORY (INV-ASM-26). Manual revocation reliably does not happen, and every dormant
    -- external account an access review finds is a standing compromise of all the customer's
    -- posture data.
    valid_until  timestamptz NOT NULL,
    state        text        NOT NULL DEFAULT 'REQUESTED',
    revoked_by   uuid,
    revoked_at   timestamptz,
    revocation_reason text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,

    CONSTRAINT ck_ext_grant__state CHECK
        (state IN ('REQUESTED', 'PENDING_AGREEMENT', 'ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_ext_grant__window CHECK (valid_until > valid_from),
    -- The maximum duration is enforced in the domain (a tenant-configurable bound); this is the
    -- absolute ceiling, beyond which the grant is not a grant but standing access.
    CONSTRAINT ck_ext_grant__bounded CHECK (valid_until <= valid_from + interval '365 days'),
    CONSTRAINT ck_ext_grant__revocation CHECK
        (state <> 'REVOKED'
         OR (revoked_by IS NOT NULL AND revoked_at IS NOT NULL
             AND revocation_reason IS NOT NULL AND revocation_reason <> ''))
);

SELECT apply_tenant_isolation('external_assessor_grant');

CREATE INDEX IF NOT EXISTS ix_ext_grant__principal_active
    ON external_assessor_grant (tenant_id, principal_id) WHERE state = 'ACTIVE';

COMMENT ON INDEX ix_ext_grant__principal_active IS
    'Serves: authorization — the grant set for an external principal, evaluated on EVERY request '
    'they make (DOC-04 section 12.11).';

CREATE INDEX IF NOT EXISTS ix_ext_grant__expiring
    ON external_assessor_grant (tenant_id, valid_until) WHERE state = 'ACTIVE';

COMMENT ON INDEX ix_ext_grant__expiring IS
    'Serves: the automatic expiry job (INV-ASM-26) and the expiry-approaching alert. Validity is '
    'computed from the clock at authorization time, so this job records the transition rather than '
    'causing it — a broken job does not extend access.';

CREATE TABLE IF NOT EXISTS external_grant_agreement (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL,
    -- CASCADE is the third narrow exception to CON-DAT-005 (DOC-04 section 12.11): "a grant's
    -- agreements and object list have no meaning without the grant, and a grant row is never
    -- deleted — it expires. Cascade exists only for the offboarding path."
    grant_id      uuid        NOT NULL REFERENCES external_assessor_grant (id) ON DELETE CASCADE,
    agreement_code text       NOT NULL,
    agreement_version int     NOT NULL,
    accepted_at   timestamptz,
    accepted_from_address text,

    CONSTRAINT uq_ext_grant_agreement__code UNIQUE (tenant_id, grant_id, agreement_code),
    CONSTRAINT ck_ext_grant_agreement__version CHECK (agreement_version >= 1),
    -- An acceptance nobody can locate is one nobody can attribute if the agreement is later
    -- disputed, and a disputed NDA with an external party is not a hypothetical.
    CONSTRAINT ck_ext_grant_agreement__attributed CHECK
        ((accepted_at IS NULL) = (accepted_from_address IS NULL))
);

SELECT apply_tenant_isolation('external_grant_agreement');

CREATE TABLE IF NOT EXISTS external_grant_object (
    id          uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id   uuid NOT NULL,
    grant_id    uuid NOT NULL REFERENCES external_assessor_grant (id) ON DELETE CASCADE,
    -- INV-ASM-25: an explicit object. THERE IS NO SCOPE COLUMN, so scope inheritance is
    -- unrepresentable — which is the point. Scope widening is not a bug anybody notices; it is the
    -- org tree behaving correctly while an untrusted party's visibility grows as a side effect.
    object_kind text NOT NULL,
    object_id   uuid NOT NULL,

    CONSTRAINT uq_ext_grant_object__object UNIQUE (tenant_id, grant_id, object_kind, object_id)
);

SELECT apply_tenant_isolation('external_grant_object');

CREATE INDEX IF NOT EXISTS ix_ext_grant_object__object
    ON external_grant_object (tenant_id, object_kind, object_id);

COMMENT ON INDEX ix_ext_grant_object__object IS
    'Serves: "who has an external grant on this object" — access review. Without this the review is '
    'a scan of every grant, which is a review that does not get run.';

-- INV-ASM-29 conformance: closed grants whose test accounts nobody has attested to rotating.
-- Every row is a live credential to a pre-production environment, held by a party whose access has
-- ended.
CREATE OR REPLACE FUNCTION outstanding_credential_rotations()
    RETURNS TABLE (request_id uuid, role_name text, username text, closed_since timestamptz)
    LANGUAGE sql STABLE
AS $$
    SELECT a.request_id, a.role_name, a.username, a.created_at
      FROM assessment_request_role_account a
     WHERE a.rotation_required AND a.rotation_attested_at IS NULL;
$$;

GRANT EXECUTE ON FUNCTION outstanding_credential_rotations()
    TO migration_runner, integrity_verifier, app_runtime;

-- -----------------------------------------------------------------------------
-- 9. Grants
--
-- No DELETE for app_runtime anywhere. Evidence additionally has a trigger, because a deleted
-- evidence row makes a finding's missing proof indistinguishable from proof that never existed —
-- and this is the one table whose contents are expected to be malicious, so the pressure to delete
-- is real and will be applied by somebody acting in good faith.
-- -----------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON assessment_type, request_group, assessment_request,
    assessment_request_role_account, assessment_request_environment, assessment,
    assessment_scope_asset, assessment_condition, checklist_definition, checklist_item,
    checklist_instance, checklist_item_result, evidence, external_assessor_grant,
    external_grant_agreement, external_grant_object TO app_runtime;
-- The scope asset list and the grant object list are current membership, so removal is permitted.
GRANT DELETE ON assessment_scope_asset, external_grant_object TO app_runtime;

GRANT SELECT ON assessment_type, request_group, assessment_request,
    assessment_request_role_account, assessment_request_environment, assessment,
    assessment_scope_asset, assessment_condition, checklist_definition, checklist_item,
    checklist_instance, checklist_item_result, evidence, external_assessor_grant,
    external_grant_agreement, external_grant_object TO integrity_verifier;

GRANT SELECT, INSERT, UPDATE, DELETE ON assessment_type, request_group, assessment_request,
    assessment_request_role_account, assessment_request_environment, assessment,
    assessment_scope_asset, assessment_condition, checklist_definition, checklist_item,
    checklist_instance, checklist_item_result, evidence, external_assessor_grant,
    external_grant_agreement, external_grant_object TO migration_runner;
