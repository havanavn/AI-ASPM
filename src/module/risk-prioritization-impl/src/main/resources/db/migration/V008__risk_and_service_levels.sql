-- =============================================================================
-- V008 — risk scoring and service levels
--
-- Owner: module/risk-prioritization. DOC-04 sections 18.1 to 18.4, DOC-28 sections 6 to 11,
-- DOC-09 section 9.
--
-- Four things are enforced here rather than only in the domain, each because the write path that
-- would bypass the domain is a real one:
--
--   PRD-RSK-046  an ACTIVE scoring model must have been validated against the tenant's own history.
--                A CHECK, so an unvalidated activation is unrepresentable rather than merely refused
--                by the service that happens to activate models.
--   INV-RSK-05   an activated model version is immutable. A trigger, because the bypass path is a
--                support engineer with a psql session and a good reason.
--   PRD-RSK-034  a paused interval carries a blocking attribution. A CHECK on the interval table,
--                because unattributed pause time is what makes breach reporting arguable rather
--                than factual (PP-6).
--   INV-RSK-03   a score is immutable except for the supersession pointer. Enforced by a trigger
--                rather than by withholding UPDATE, because DOC-04 section 18.2 grants exactly one
--                column update and column-level grants do not compose with row-level security in a
--                way that is auditable.
--
-- Partitioning here is RANGE by computed_at, monthly (DOC-04 section 18.2). It carries NO
-- OQ-015 dependency: unlike the hash partition counts on `finding`, a range partition scheme is
-- extended by adding partitions, so nothing about it is irreversible.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. scoring_model and weights — DOC-04 section 18.1
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS scoring_model (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid        NOT NULL,
    version      int         NOT NULL,
    state        text        NOT NULL DEFAULT 'DRAFT',
    -- PRD-RSK-046. A weight set that has not been tested against the tenant's own findings "is a
    -- guess presented as methodology, and it will be defended in a meeting where nobody can produce
    -- evidence for it" (DOC-04 section 18.1).
    validated_against_history_at timestamptz,
    -- Band thresholds travel WITH the version, so a threshold change is a version change and a
    -- historical score keeps the band it was assigned. Recomputing an old value under today's
    -- thresholds would silently rewrite history.
    band_thresholds jsonb     NOT NULL,
    activated_at timestamptz,
    retired_at   timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   uuid,
    row_version  int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_scoring_model__state CHECK (state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT uq_scoring_model__version UNIQUE (tenant_id, version),
    CONSTRAINT ck_scoring_model__validated_before_active CHECK
        (state <> 'ACTIVE' OR validated_against_history_at IS NOT NULL),
    CONSTRAINT ck_scoring_model__activated_when_active CHECK
        (state = 'DRAFT' OR activated_at IS NOT NULL),
    CONSTRAINT ck_scoring_model__retired_after_activated CHECK
        (retired_at IS NULL OR (activated_at IS NOT NULL AND retired_at >= activated_at)),
    -- The four band thresholds must be present and strictly descending. Duplicated from
    -- BandThresholds for the reason given in V007's header: the domain check catches the mistake
    -- early, the engine check catches the path nobody routed through the domain.
    CONSTRAINT ck_scoring_model__band_thresholds CHECK (
        (band_thresholds ? 'criticalFrom') AND (band_thresholds ? 'highFrom')
        AND (band_thresholds ? 'mediumFrom') AND (band_thresholds ? 'lowFrom')
        AND (band_thresholds->>'criticalFrom')::int > (band_thresholds->>'highFrom')::int
        AND (band_thresholds->>'highFrom')::int    > (band_thresholds->>'mediumFrom')::int
        AND (band_thresholds->>'mediumFrom')::int  > (band_thresholds->>'lowFrom')::int
        AND (band_thresholds->>'lowFrom')::int     >= 1
        AND (band_thresholds->>'criticalFrom')::int <= 100)
);

SELECT apply_tenant_isolation('scoring_model');

-- Only one ACTIVE model version per tenant. Two active versions would make "which model scored
-- this finding" depend on which row a query happened to read first, and every comparison between
-- two findings would silently span two models.
CREATE UNIQUE INDEX IF NOT EXISTS uq_scoring_model__one_active
    ON scoring_model (tenant_id) WHERE state = 'ACTIVE';

COMMENT ON INDEX uq_scoring_model__one_active IS
    'Serves: the active model lookup on every score computation. Also the invariant — one ACTIVE '
    'version per tenant (DOC-28 section 8.1).';

CREATE TABLE IF NOT EXISTS scoring_model_factor_weight (
    id          uuid          PRIMARY KEY DEFAULT uuidv7(),
    tenant_id   uuid          NOT NULL,
    -- ADR-030 permits a foreign key WITHIN a module boundary; both tables are risk-prioritization's.
    model_id    uuid          NOT NULL REFERENCES scoring_model (id) ON DELETE RESTRICT,
    factor_code text          NOT NULL,
    weight      numeric(4,3)  NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  uuid,

    CONSTRAINT uq_smfw__factor UNIQUE (tenant_id, model_id, factor_code),
    -- The factor SET is product-fixed (PRD-RSK-004, DOC-28 section 7.1), so this enumeration is
    -- correct here and does NOT violate ADR-027: what a tenant configures is the weight, not which
    -- factors exist. A tenant-invented factor code would produce a weight the formula never reads.
    CONSTRAINT ck_smfw__factor_code CHECK
        (factor_code IN ('SEV', 'EXP', 'KEV', 'EXPO', 'CRIT', 'DATA', 'REACH')),
    -- The per-factor bounds of DOC-28 section 7.2. Enforced per factor rather than as one 0..1
    -- range, because the bounds are the control: PRD-RSK-020's rationale is that "setting EXP and
    -- KEV to zero converts it back into the severity sorting that produced the four thousand
    -- findings", and a generic 0..1 CHECK permits exactly that.
    CONSTRAINT ck_smfw__bounds CHECK (
        CASE factor_code
            WHEN 'SEV'   THEN weight BETWEEN 0.150 AND 0.450
            WHEN 'EXP'   THEN weight BETWEEN 0.050 AND 0.350
            WHEN 'KEV'   THEN weight BETWEEN 0.050 AND 0.350
            WHEN 'EXPO'  THEN weight BETWEEN 0.050 AND 0.300
            WHEN 'CRIT'  THEN weight BETWEEN 0.050 AND 0.300
            WHEN 'DATA'  THEN weight BETWEEN 0.000 AND 0.250
            WHEN 'REACH' THEN weight BETWEEN 0.000 AND 0.200
        END)
);

SELECT apply_tenant_isolation('scoring_model_factor_weight');

CREATE INDEX IF NOT EXISTS ix_smfw__model ON scoring_model_factor_weight (tenant_id, model_id);

COMMENT ON INDEX ix_smfw__model IS
    'Serves: load every weight for a model version — read once per scoring batch.';

-- INV-RSK-05: an activated version is immutable, on BOTH tables.
CREATE OR REPLACE FUNCTION reject_activated_model_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    model_state text;
BEGIN
    IF TG_TABLE_NAME = 'scoring_model' THEN
        -- The permitted transitions out of ACTIVE are retirement and nothing else. Weight,
        -- threshold and version changes create a NEW version (DOC-28 section 8.1).
        IF OLD.state IN ('ACTIVE', 'RETIRED') THEN
            IF NEW.version         IS DISTINCT FROM OLD.version
            OR NEW.band_thresholds IS DISTINCT FROM OLD.band_thresholds
            OR NEW.activated_at    IS DISTINCT FROM OLD.activated_at
            OR NEW.validated_against_history_at IS DISTINCT FROM OLD.validated_against_history_at THEN
                RAISE EXCEPTION
                    'scoring model version % is % and immutable (INV-RSK-05). Changing weights, band '
                    'thresholds or the factor set creates a NEW version, so that every score already '
                    'computed remains reproducible under the model it was computed with.',
                    OLD.version, OLD.state
                    USING ERRCODE = 'integrity_constraint_violation';
            END IF;
        END IF;
        RETURN NEW;
    END IF;

    -- scoring_model_factor_weight: no change at all once its model is activated.
    SELECT state INTO model_state FROM scoring_model
     WHERE id = COALESCE(NEW.model_id, OLD.model_id);

    IF model_state IN ('ACTIVE', 'RETIRED') THEN
        RAISE EXCEPTION
            'the weights of an activated scoring model are immutable (INV-RSK-05); model is %. '
            'A weight change silently alters enterprise-wide prioritization (PRD-RSK-021), which is '
            'why it is a new version and an explicit recomputation rather than an UPDATE.', model_state
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END
$$;

DROP TRIGGER IF EXISTS trg_scoring_model__immutable_when_active ON scoring_model;
CREATE TRIGGER trg_scoring_model__immutable_when_active
    BEFORE UPDATE ON scoring_model
    FOR EACH ROW EXECUTE FUNCTION reject_activated_model_change();

DROP TRIGGER IF EXISTS trg_smfw__immutable_when_active ON scoring_model_factor_weight;
CREATE TRIGGER trg_smfw__immutable_when_active
    BEFORE UPDATE OR DELETE ON scoring_model_factor_weight
    FOR EACH ROW EXECUTE FUNCTION reject_activated_model_change();

-- -----------------------------------------------------------------------------
-- 2. risk_score — DOC-04 section 18.2
--
-- Immutable (INV-RSK-03), self-contained (PRD-RSK-023), high volume: 50,000,000 rows at Extra
-- large, of which factor_inputs + factor_contributions + coverage_detail are 30 to 40 GB. DOC-04
-- section 18.2 names that "the largest single storage consequence of the reproducibility
-- requirement", which is why retention drops partitions aggressively while a projection holds the
-- latest score per subject.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS risk_score (
    id                  uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id           uuid        NOT NULL,
    subject_kind        text        NOT NULL,
    subject_id          uuid        NOT NULL,
    model_version       int         NOT NULL,
    -- The values USED, with source and freshness per factor — not references to the rows they came
    -- from. Referencing the asset's criticality by identifier "would give a different answer once
    -- criticality is reassigned" (DOC-04 section 18.2), and PRD-RSK-023 requires the same answer.
    factor_inputs       jsonb       NOT NULL,
    factor_contributions jsonb      NOT NULL,
    value               smallint    NOT NULL,
    band                text        NOT NULL,
    coverage_confidence text        NOT NULL,
    -- Materialized WITH the score (CON-PLT-028) rather than joined at read time, so a score's
    -- presentability cannot change because coverage moved after it was computed.
    coverage_detail     jsonb       NOT NULL,
    population_version  bigint,
    computed_at         timestamptz NOT NULL,
    superseded_by_score_id uuid,
    change_attribution  jsonb,

    PRIMARY KEY (tenant_id, id, computed_at),

    CONSTRAINT ck_risk_score__subject_kind CHECK
        (subject_kind IN ('FINDING_IMPACT', 'ASSET', 'ORG_NODE')),
    CONSTRAINT ck_risk_score__value CHECK (value BETWEEN 0 AND 100),
    CONSTRAINT ck_risk_score__band CHECK
        (band IN ('INFORMATIONAL', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    -- INV-RSK-06. The four bands of DOC-28 section 9 and no fifth: a score with an unrecognised
    -- confidence value cannot be tested for presentability, and the failure mode of an unknown
    -- confidence is that it gets presented.
    CONSTRAINT ck_risk_score__coverage_confidence CHECK
        (coverage_confidence IN ('HIGH', 'MEDIUM', 'LOW', 'INSUFFICIENT')),
    -- PRD-RSK-018: every factor carries a fallback classification, so a missing input is never a
    -- silent zero. Asserted structurally — one entry per factor in the product-fixed set.
    CONSTRAINT ck_risk_score__all_factors_present CHECK
        (jsonb_array_length(factor_inputs) = 7),
    -- A rank-transformed factor without a population version cannot be told apart from one that
    -- moved for a real reason, which is the conflation PRD-RSK-025 forbids.
    CONSTRAINT ck_risk_score__population_version CHECK
        (population_version IS NULL OR population_version >= 0)
) PARTITION BY RANGE (computed_at);

SELECT apply_tenant_isolation('risk_score');

-- Monthly range partitions from the outset (DOC-04 section 18.2). Reversible: extending a range
-- scheme is adding a partition. This is the difference between this table and `finding`, whose
-- hash partition count is irreversible after production data (OPS-DEP-012, OQ-015).
CREATE OR REPLACE FUNCTION ensure_risk_score_partitions(lead_months int DEFAULT 3) RETURNS int
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
        part_name := 'risk_score_' || to_char(m, 'YYYY_MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF risk_score '
            'FOR VALUES FROM (%L) TO (%L)',
            part_name, m, (m + interval '1 month')::date);
        -- Isolation is NOT inherited by a partition. Without this, every month's new partition
        -- would be a fresh cross-tenant read path — see the note in apply_tenant_isolation.
        PERFORM apply_tenant_isolation(format('%I', part_name)::regclass);
        created := created + 1;
    END LOOP;

    RETURN created;
END
$$;

GRANT EXECUTE ON FUNCTION ensure_risk_score_partitions(int) TO migration_runner;

SELECT ensure_risk_score_partitions(3);

-- INV-RSK-03 / PRD-RSK-024. Exactly one column is updatable: the supersession pointer. DOC-04
-- section 18.2 permits that narrowly "because full immutability would require a separate
-- supersession table for a single pointer".
CREATE OR REPLACE FUNCTION reject_risk_score_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- Retention is by partition DROP, which does not fire row triggers. A row-level DELETE is
        -- therefore always either a mistake or an attempt to remove evidence of a prior score.
        RAISE EXCEPTION
            'risk_score rows are not deleted individually (INV-RSK-03). Retention drops whole '
            'partitions after the reproducibility window; a single-row delete removes the ability '
            'to answer what a score used to be.'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.tenant_id            IS DISTINCT FROM OLD.tenant_id
    OR NEW.subject_kind         IS DISTINCT FROM OLD.subject_kind
    OR NEW.subject_id           IS DISTINCT FROM OLD.subject_id
    OR NEW.model_version        IS DISTINCT FROM OLD.model_version
    OR NEW.factor_inputs        IS DISTINCT FROM OLD.factor_inputs
    OR NEW.factor_contributions IS DISTINCT FROM OLD.factor_contributions
    OR NEW.value                IS DISTINCT FROM OLD.value
    OR NEW.band                 IS DISTINCT FROM OLD.band
    OR NEW.coverage_confidence  IS DISTINCT FROM OLD.coverage_confidence
    OR NEW.coverage_detail      IS DISTINCT FROM OLD.coverage_detail
    OR NEW.population_version   IS DISTINCT FROM OLD.population_version
    OR NEW.computed_at          IS DISTINCT FROM OLD.computed_at THEN
        RAISE EXCEPTION
            'a risk score is immutable; only superseded_by_score_id and change_attribution may be '
            'set after insert (INV-RSK-03, PRD-RSK-024). An in-place update destroys the prior value '
            'and with it the ability to answer what changed.'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    -- The pointer is set once. Repointing it would let a chain of supersessions be rewritten.
    IF OLD.superseded_by_score_id IS NOT NULL
       AND NEW.superseded_by_score_id IS DISTINCT FROM OLD.superseded_by_score_id THEN
        RAISE EXCEPTION
            'superseded_by_score_id is set once (INV-RSK-03); it already points at %.',
            OLD.superseded_by_score_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_risk_score__immutable ON risk_score;
CREATE TRIGGER trg_risk_score__immutable
    BEFORE UPDATE OR DELETE ON risk_score
    FOR EACH ROW EXECUTE FUNCTION reject_risk_score_change();

CREATE INDEX IF NOT EXISTS ix_risk_score__current
    ON risk_score (tenant_id, subject_kind, subject_id, computed_at DESC);

COMMENT ON INDEX ix_risk_score__current IS
    'Serves: latest score for a subject — read on every finding view and every aggregation '
    '(DOC-04 section 18.2).';

CREATE INDEX IF NOT EXISTS ix_risk_score__band
    ON risk_score (tenant_id, subject_kind, band, computed_at DESC)
    WHERE superseded_by_score_id IS NULL;

COMMENT ON INDEX ix_risk_score__band IS
    'Serves: current scores by band — dashboard aggregation. Partial, because a superseded score '
    'must never appear in a current-state count.';

CREATE INDEX IF NOT EXISTS ix_risk_score__insufficient
    ON risk_score (tenant_id, computed_at DESC)
    WHERE coverage_confidence = 'INSUFFICIENT';

COMMENT ON INDEX ix_risk_score__insufficient IS
    'Serves: scores that MUST NOT be presented as posture figures (PRD-RSK-027). An index over the '
    'exclusion set, so a report can prove what it withheld rather than assert it.';

-- -----------------------------------------------------------------------------
-- 3. Service level policy, calendars, escalation — DOC-04 section 18.3
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS business_calendar (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid        NOT NULL,
    code         text        NOT NULL,
    label_i18n   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- IANA zone identifier. NFR-INT-003 stores instants in UTC and computes business calendar
    -- arithmetic in the tenant's zone; a calendar without a zone cannot say when a day ends.
    timezone     text        NOT NULL,
    working_days smallint[]  NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   uuid,
    row_version  int         NOT NULL DEFAULT 1,

    CONSTRAINT uq_business_calendar__code UNIQUE (tenant_id, code),
    -- ISO day numbers, 1 (Monday) to 7 (Sunday). A calendar with no working days would make every
    -- deadline infinite, which presents as a service level engine that silently never breaches.
    CONSTRAINT ck_business_calendar__working_days CHECK (
        array_length(working_days, 1) BETWEEN 1 AND 7
        AND working_days <@ ARRAY[1,2,3,4,5,6,7]::smallint[]),
    CONSTRAINT ck_business_calendar__timezone CHECK (timezone <> '')
);

SELECT apply_tenant_isolation('business_calendar');

CREATE TABLE IF NOT EXISTS business_calendar_holiday (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid        NOT NULL,
    calendar_id  uuid        NOT NULL REFERENCES business_calendar (id) ON DELETE RESTRICT,
    holiday_date date        NOT NULL,
    description  text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,

    CONSTRAINT uq_bch__date UNIQUE (tenant_id, calendar_id, holiday_date)
);

SELECT apply_tenant_isolation('business_calendar_holiday');

CREATE INDEX IF NOT EXISTS ix_bch__calendar_date
    ON business_calendar_holiday (tenant_id, calendar_id, holiday_date);

COMMENT ON INDEX ix_bch__calendar_date IS
    'Serves: business-day arithmetic over a date range — read once per clock start and per '
    'recomputation.';

CREATE TABLE IF NOT EXISTS service_level_policy (
    id                   uuid         PRIMARY KEY DEFAULT uuidv7(),
    tenant_id            uuid         NOT NULL,
    code                 text         NOT NULL,
    label_i18n           jsonb        NOT NULL DEFAULT '{}'::jsonb,
    version              int          NOT NULL,
    -- Shared rules engine (ADR-011's sibling decision for matching). Most-specific-wins.
    matching_rules       jsonb        NOT NULL,
    -- Precomputed at save. DOC-04 section 18.3: computing specificity at match time "would require
    -- interpreting the rule document per candidate policy per finding".
    specificity          int          NOT NULL,
    target_business_days numeric(6,2) NOT NULL,
    business_calendar_id uuid         NOT NULL REFERENCES business_calendar (id) ON DELETE RESTRICT,
    state                text         NOT NULL DEFAULT 'DRAFT',
    created_at           timestamptz  NOT NULL DEFAULT now(),
    created_by           uuid,
    updated_at           timestamptz  NOT NULL DEFAULT now(),
    updated_by           uuid,
    row_version          int          NOT NULL DEFAULT 1,

    CONSTRAINT uq_slp__code_version UNIQUE (tenant_id, code, version),
    CONSTRAINT ck_slp__state CHECK (state IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_slp__specificity CHECK (specificity >= 0),
    -- Zero would be a deadline at the moment of creation, which is a breach on arrival rather than
    -- a commitment. DOC-28 section 11.2 expresses "no commitment" by having NO policy match, not by
    -- a zero-day one.
    CONSTRAINT ck_slp__target_positive CHECK (target_business_days > 0)
);

SELECT apply_tenant_isolation('service_level_policy');

CREATE INDEX IF NOT EXISTS ix_slp__matching
    ON service_level_policy (tenant_id, specificity DESC, target_business_days ASC)
    WHERE state = 'ACTIVE';

COMMENT ON INDEX ix_slp__matching IS
    'Serves: policy matching — most-specific-wins with shortest-duration as the tiebreak (DOC-28 '
    'section 11.1). The ordering IS the tiebreak, so an ordered scan answers the match directly.';

CREATE TABLE IF NOT EXISTS escalation_step (
    id                      uuid         PRIMARY KEY DEFAULT uuidv7(),
    tenant_id               uuid         NOT NULL,
    policy_id               uuid         NOT NULL REFERENCES service_level_policy (id) ON DELETE RESTRICT,
    trigger_at_budget_ratio numeric(4,3) NOT NULL,
    target_kind             text         NOT NULL,
    target_ref              uuid,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    created_by              uuid,

    CONSTRAINT uq_escalation_step__ratio UNIQUE (tenant_id, policy_id, trigger_at_budget_ratio),
    -- ADR-027: ANCESTOR_OWNER walks the tenant's own hierarchy, and ROLE names a tenant-defined
    -- role by identifier. The KINDS are product-fixed; no role name appears here.
    CONSTRAINT ck_escalation_step__target_kind CHECK
        (target_kind IN ('ASSIGNEE', 'OWNER', 'ANCESTOR_OWNER', 'ROLE')),
    CONSTRAINT ck_escalation_step__ratio_positive CHECK (trigger_at_budget_ratio > 0),
    -- A ROLE target needs a role to notify; the others are derived from the subject.
    CONSTRAINT ck_escalation_step__role_ref CHECK
        (target_kind <> 'ROLE' OR target_ref IS NOT NULL)
);

SELECT apply_tenant_isolation('escalation_step');

CREATE INDEX IF NOT EXISTS ix_escalation_step__policy
    ON escalation_step (tenant_id, policy_id, trigger_at_budget_ratio);

COMMENT ON INDEX ix_escalation_step__policy IS
    'Serves: the next escalation step for a clock — read by the escalation scheduler per due clock.';

-- -----------------------------------------------------------------------------
-- 4. service_level_clock and intervals — DOC-04 section 18.4
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS service_level_clock (
    id                      uuid         PRIMARY KEY DEFAULT uuidv7(),
    tenant_id               uuid         NOT NULL,
    subject_kind            text         NOT NULL,
    subject_id              uuid         NOT NULL,
    policy_id               uuid         NOT NULL REFERENCES service_level_policy (id) ON DELETE RESTRICT,
    -- PRD-RSK-032 / INV-RSK-08. Pinned at start: "a policy change retroactively moving deadlines
    -- makes commitments unstable and breaches unattributable".
    policy_version          int          NOT NULL,
    -- PRD-RSK-033. The calendar AS IT WAS. A holiday added after a clock started must not move its
    -- deadline, and snapshotting is the only way to guarantee that.
    calendar_snapshot       jsonb        NOT NULL,
    started_at              timestamptz  NOT NULL,
    due_at                  timestamptz  NOT NULL,
    -- PRD-RSK-035. Retained so the recomputation is auditable: a reader can see both what was
    -- committed and what the shorter policy required.
    original_due_at         timestamptz  NOT NULL,
    state                   text         NOT NULL DEFAULT 'RUNNING',
    elapsed_running_seconds bigint       NOT NULL DEFAULT 0,
    elapsed_paused_seconds  bigint       NOT NULL DEFAULT 0,
    breached_at             timestamptz,
    resolved_at             timestamptz,
    extension_approved_by   uuid,
    extension_approved_at   timestamptz,
    extension_reason        text,
    last_escalation_ratio   numeric(4,3),
    created_at              timestamptz  NOT NULL DEFAULT now(),
    created_by              uuid,
    updated_at              timestamptz  NOT NULL DEFAULT now(),
    updated_by              uuid,
    row_version             int          NOT NULL DEFAULT 1,

    CONSTRAINT ck_slc__subject_kind CHECK
        (subject_kind IN ('FINDING', 'WORK_ITEM', 'ASSESSMENT_REQUEST')),
    -- CANCELLED is present in the domain machine (DOC-09 section 9) and absent from DOC-04
    -- section 18.4's column note. Included here: a clock whose subject is withdrawn has to go
    -- somewhere, and forcing it to MET would count a withdrawal as a met commitment.
    CONSTRAINT ck_slc__state CHECK
        (state IN ('RUNNING', 'PAUSED', 'MET', 'BREACHED', 'EXTENDED', 'CANCELLED')),
    CONSTRAINT ck_slc__elapsed_non_negative CHECK
        (elapsed_running_seconds >= 0 AND elapsed_paused_seconds >= 0),
    CONSTRAINT ck_slc__due_after_start CHECK (due_at > started_at OR state = 'BREACHED'),
    -- INV-RSK-11. An extension without an approver and a reason is indistinguishable from a met
    -- deadline in every aggregate, which is the gaming path DOC-28 section 13.2 names.
    CONSTRAINT ck_slc__extension_attributed CHECK (
        state <> 'EXTENDED'
        OR (extension_approved_by IS NOT NULL AND extension_approved_at IS NOT NULL
            AND extension_reason IS NOT NULL AND extension_reason <> '')),
    CONSTRAINT ck_slc__breached_at_when_breached CHECK
        (state <> 'BREACHED' OR breached_at IS NOT NULL),
    -- PRD-RSK-032 again, at the row level: a policy version below 1 is an unpinned clock.
    CONSTRAINT ck_slc__policy_version CHECK (policy_version >= 1),
    CONSTRAINT ck_slc__escalation_ratio CHECK
        (last_escalation_ratio IS NULL OR last_escalation_ratio > 0)
);

SELECT apply_tenant_isolation('service_level_clock');

-- One live clock per subject. Two would give a subject two deadlines and make "is it breached"
-- depend on which row was read.
CREATE UNIQUE INDEX IF NOT EXISTS uq_slc__one_live_per_subject
    ON service_level_clock (tenant_id, subject_kind, subject_id)
    WHERE state IN ('RUNNING', 'PAUSED', 'EXTENDED');

COMMENT ON INDEX uq_slc__one_live_per_subject IS
    'Serves: the clock for a subject, and the invariant that a subject has at most one live clock.';

CREATE INDEX IF NOT EXISTS ix_slc__subject
    ON service_level_clock (tenant_id, subject_kind, subject_id);

COMMENT ON INDEX ix_slc__subject IS
    'Serves: the clock history for a subject — read on every item view (DOC-04 section 18.4).';

CREATE INDEX IF NOT EXISTS ix_slc__due
    ON service_level_clock (tenant_id, due_at)
    WHERE state = 'RUNNING';

COMMENT ON INDEX ix_slc__due IS
    'Serves: forward exposure (PRD-DSH-007) and the breach detection sweep. Partial on RUNNING, '
    'because a paused clock has no due date worth sweeping.';

CREATE INDEX IF NOT EXISTS ix_slc__escalation_due
    ON service_level_clock (tenant_id, due_at, last_escalation_ratio)
    WHERE state = 'RUNNING';

COMMENT ON INDEX ix_slc__escalation_due IS
    'Serves: the escalation scheduler — finds clocks whose next step is due, without rechecking '
    'those whose highest step already fired.';

CREATE INDEX IF NOT EXISTS ix_slc__breached
    ON service_level_clock (tenant_id, breached_at DESC)
    WHERE state = 'BREACHED';

COMMENT ON INDEX ix_slc__breached IS
    'Serves: breach reporting. A late resolution stays BREACHED (DOC-09 section 9), so this index '
    'covers resolved-late clocks too — which is the point.';

CREATE TABLE IF NOT EXISTS service_level_clock_interval (
    id                   uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id            uuid        NOT NULL,
    clock_id             uuid        NOT NULL REFERENCES service_level_clock (id) ON DELETE RESTRICT,
    sequence             int         NOT NULL,
    interval_kind        text        NOT NULL,
    started_at           timestamptz NOT NULL,
    ended_at             timestamptz,
    blocking_attribution text,
    created_at           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_slci__sequence UNIQUE (tenant_id, clock_id, sequence),
    CONSTRAINT ck_slci__kind CHECK (interval_kind IN ('RUNNING', 'PAUSED')),
    CONSTRAINT ck_slci__sequence_positive CHECK (sequence >= 1),
    CONSTRAINT ck_slci__ended_after_started CHECK (ended_at IS NULL OR ended_at >= started_at),
    -- PRD-RSK-034. Making the attribution a constraint means a pause without one is
    -- unrepresentable — "and unattributed pause time is exactly what makes breach reporting
    -- arguable rather than factual" (DOC-04 section 18.4, PP-6).
    CONSTRAINT ck_slci__attribution_when_paused CHECK
        (interval_kind <> 'PAUSED' OR blocking_attribution IS NOT NULL),
    -- The enumerated set of PRD-RSK-034. Free text "prevents attribution becoming free text nobody
    -- can aggregate"; a CHECK is what makes it enumerated rather than merely documented.
    CONSTRAINT ck_slci__attribution_enumerated CHECK
        (blocking_attribution IS NULL
         OR blocking_attribution IN ('REQUESTER', 'THIRD_PARTY', 'SECURITY_FUNCTION')),
    -- A RUNNING interval has no blocking party by definition; an attribution on one would appear in
    -- pause-time reporting for time nobody was blocked.
    CONSTRAINT ck_slci__no_attribution_when_running CHECK
        (interval_kind <> 'RUNNING' OR blocking_attribution IS NULL)
);

SELECT apply_tenant_isolation('service_level_clock_interval');

CREATE INDEX IF NOT EXISTS ix_slci__clock
    ON service_level_clock_interval (tenant_id, clock_id, sequence);

COMMENT ON INDEX ix_slci__clock IS
    'Serves: interval history for attribution reporting (DOC-04 section 18.4) — paused time '
    'reportable separately from elapsed, per PRD-RSK-034.';

-- At most one open interval per clock. Two open intervals would double-count elapsed time, and the
-- double-count would appear as a clock that ran slower than the wall.
CREATE UNIQUE INDEX IF NOT EXISTS uq_slci__one_open_per_clock
    ON service_level_clock_interval (tenant_id, clock_id)
    WHERE ended_at IS NULL;

COMMENT ON INDEX uq_slci__one_open_per_clock IS
    'Serves: the open-interval lookup on pause and resume, and the invariant that a clock has at '
    'most one.';

-- An interval is a record of what happened (PP-5). Closing one is permitted; rewriting when it
-- started, or which party was blocking, is not.
CREATE OR REPLACE FUNCTION reject_clock_interval_rewrite() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'clock intervals are not deleted (PP-6, PRD-RSK-034). Deleting a paused interval '
            'reassigns its delay to the accountable team, which is the specific harm the '
            'attribution requirement exists to prevent.'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.clock_id             IS DISTINCT FROM OLD.clock_id
    OR NEW.sequence             IS DISTINCT FROM OLD.sequence
    OR NEW.interval_kind        IS DISTINCT FROM OLD.interval_kind
    OR NEW.started_at           IS DISTINCT FROM OLD.started_at
    OR NEW.blocking_attribution IS DISTINCT FROM OLD.blocking_attribution THEN
        RAISE EXCEPTION
            'only ended_at may be set on an existing clock interval (PP-5). Changing the '
            'attribution after the fact moves blame for a delay that already happened.'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.ended_at IS NOT NULL AND NEW.ended_at IS DISTINCT FROM OLD.ended_at THEN
        RAISE EXCEPTION
            'the interval ending % is already closed (PP-5); reopening it would change elapsed '
            'time for a period that has already been reported.', OLD.ended_at
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_slci__append_only ON service_level_clock_interval;
CREATE TRIGGER trg_slci__append_only
    BEFORE UPDATE OR DELETE ON service_level_clock_interval
    FOR EACH ROW EXECUTE FUNCTION reject_clock_interval_rewrite();

-- -----------------------------------------------------------------------------
-- 5. Score-reducing action observations — PRD-RSK-041
--
-- The detector is deterministic arithmetic over counts (see ScoreReducingRateAnomaly). What the
-- engine owns is the observation record, because the counts must survive the process that computed
-- them and must be queryable per principal AND per node.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS score_reducing_action_event (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id         uuid        NOT NULL,
    action            text        NOT NULL,
    principal_id      uuid        NOT NULL,
    -- Per-node as well as per-principal: PRD-RSK-041 requires both, because a principal with broad
    -- scope can spread a gaming rate thinly enough that their overall rate looks ordinary.
    scope_node_id     uuid        NOT NULL,
    subject_kind      text        NOT NULL,
    subject_id        uuid        NOT NULL,
    occurred_at       timestamptz NOT NULL DEFAULT now(),
    -- The audit record this observation summarises. DOC-14 owns the audit event itself; this is a
    -- pointer for the reviewer, not a second copy of the evidence.
    audit_event_id    uuid,

    CONSTRAINT ck_srae__action CHECK (action IN (
        'CLOSE_NOT_APPLICABLE', 'SEVERITY_DOWNGRADE', 'RISK_EXCEPTION', 'CRITICALITY_DOWNGRADE',
        'EXPOSURE_DOWNGRADE', 'DATA_CLASSIFICATION_REMOVAL', 'SCOPE_EXCLUSION', 'ASSET_RETIREMENT',
        'FALSE_POSITIVE_SUPPRESSION', 'DEADLINE_EXTENSION', 'FINDING_SPLIT',
        'SCORE_CONFIGURATION_CHANGE'))
);

SELECT apply_tenant_isolation('score_reducing_action_event');

CREATE INDEX IF NOT EXISTS ix_srae__principal_rate
    ON score_reducing_action_event (tenant_id, principal_id, action, occurred_at DESC);

COMMENT ON INDEX ix_srae__principal_rate IS
    'Serves: the per-principal rate window of PRD-RSK-041, and the same principal''s trailing '
    'baseline — the two counts the detector compares.';

CREATE INDEX IF NOT EXISTS ix_srae__node_rate
    ON score_reducing_action_event (tenant_id, scope_node_id, action, occurred_at DESC);

COMMENT ON INDEX ix_srae__node_rate IS
    'Serves: the per-node peer median of PRD-RSK-041. Without it the peer comparison is a full scan '
    'per evaluated observation.';

-- An observation of a score-reducing action is evidence about a principal. Nothing rewrites it.
CREATE OR REPLACE FUNCTION reject_score_reducing_event_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'score_reducing_action_event is append-only (PP-5, PRD-RSK-041). A principal able to amend '
        'the record of their own score-reducing actions defeats rate detection entirely.'
        USING ERRCODE = 'integrity_constraint_violation';
END
$$;

DROP TRIGGER IF EXISTS trg_srae__append_only ON score_reducing_action_event;
CREATE TRIGGER trg_srae__append_only
    BEFORE UPDATE OR DELETE ON score_reducing_action_event
    FOR EACH ROW EXECUTE FUNCTION reject_score_reducing_event_change();

-- -----------------------------------------------------------------------------
-- 6. Conformance
-- -----------------------------------------------------------------------------

-- The presentability exclusion set, as a function rather than a query pasted into each report.
-- PRD-RSK-027 requires an INSUFFICIENT score presented as a coverage gap; this is what a report
-- calls to find out which subjects it must not present a figure for.
CREATE OR REPLACE FUNCTION scores_not_presentable_as_posture()
    RETURNS TABLE (subject_kind text, subject_id uuid, coverage_confidence text, computed_at timestamptz)
    LANGUAGE sql STABLE
AS $$
    SELECT s.subject_kind, s.subject_id, s.coverage_confidence, s.computed_at
      FROM risk_score s
     WHERE s.coverage_confidence = 'INSUFFICIENT'
       AND s.superseded_by_score_id IS NULL;
$$;

GRANT EXECUTE ON FUNCTION scores_not_presentable_as_posture()
    TO migration_runner, integrity_verifier, app_runtime;

-- Every clock whose paused time is unattributed. Should always be empty — the CHECK makes it
-- unrepresentable — and asserted anyway, because a constraint added after data exists is validated
-- only if somebody asked for it to be.
CREATE OR REPLACE FUNCTION unattributed_pause_intervals()
    RETURNS TABLE (interval_id uuid, clock_id uuid, started_at timestamptz)
    LANGUAGE sql STABLE
AS $$
    SELECT i.id, i.clock_id, i.started_at
      FROM service_level_clock_interval i
     WHERE i.interval_kind = 'PAUSED'
       AND (i.blocking_attribution IS NULL OR i.blocking_attribution = '');
$$;

GRANT EXECUTE ON FUNCTION unattributed_pause_intervals()
    TO migration_runner, integrity_verifier, app_runtime;

-- -----------------------------------------------------------------------------
-- 7. Grants
--
-- No DELETE for app_runtime on anything here. Three of these tables are records of what happened —
-- a score, a clock interval, a score-reducing action — and PP-5 makes that record inviolable. The
-- two configuration tables withhold DELETE for a different reason: a scoring model or a service
-- level policy referenced by a historical score or clock must remain resolvable, so retirement is a
-- state change and never a row removal.
--
-- The score-reducing action record is INSERT and SELECT only, with no UPDATE even for
-- migration_runner: rate detection over a record its subject can amend detects nothing.
-- -----------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON scoring_model, scoring_model_factor_weight, business_calendar,
    business_calendar_holiday, service_level_policy, escalation_step, service_level_clock,
    service_level_clock_interval TO app_runtime;
GRANT SELECT, INSERT ON risk_score, score_reducing_action_event TO app_runtime;
-- The narrowly-granted supersession pointer of DOC-04 section 18.2. The trigger is what confines the
-- update to that column; the grant is what makes the trigger the only thing standing there.
GRANT UPDATE ON risk_score TO app_runtime;

GRANT SELECT ON scoring_model, scoring_model_factor_weight, business_calendar,
    business_calendar_holiday, service_level_policy, escalation_step, service_level_clock,
    service_level_clock_interval, risk_score, score_reducing_action_event TO integrity_verifier;

GRANT SELECT, INSERT, UPDATE, DELETE ON scoring_model, scoring_model_factor_weight,
    business_calendar, business_calendar_holiday, service_level_policy, escalation_step,
    service_level_clock, service_level_clock_interval TO migration_runner;
-- DELETE on risk_score for migration_runner so a partition-level retention DROP is available; the
-- row trigger still rejects a single-row delete, which is the case that matters.
GRANT SELECT, INSERT, UPDATE, DELETE ON risk_score TO migration_runner;
GRANT SELECT, INSERT ON score_reducing_action_event TO migration_runner;
