-- =============================================================================
-- V005 — asset-inventory: the type registry, the asset, the graph, claims, merge
--
-- Owner: module/asset-inventory. DOC-04 section 11.3, DOC-03 section 8.
--
-- *** OQ-015 DOES NOT GATE THIS MIGRATION. ***
-- DOC-04 section 11.3.2: "Partitioning. None — 100,000 rows at Extra large." Neither `asset` nor
-- `asset_relationship` is partitioned, so no irreversible hash partition count is set here. The
-- counts OQ-015 does gate are on `finding` and `finding_asset_impact` (aligned hash by tenant) and
-- `component_entry` (hash by tenant, 32), per DOC-04 section 22.2 — which arrive with vulnerability
-- management and composition analysis, not here. Recorded because an earlier note in V002 named the
-- wrong prompts for those tables.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. asset_type — DOC-04 section 11.3.1
--
-- The registry that makes ADR-009 work: one Asset aggregate serves every inventory the original brief
-- listed separately, and this table carries what differs between them. A new type is a row, not a
-- migration — which is the return on ADR-009.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS asset_type (
    id                       uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                uuid,                              -- null => platform-supplied type
    code                     text        NOT NULL,
    label_i18n               jsonb       NOT NULL,
    ordinal                  int         NOT NULL DEFAULT 0,
    -- Natural key attributes, normalizations, match strategy and RULE VERSION. The version is what
    -- makes re-resolution possible; without it "the first version of a rule is permanent — and the
    -- first version is always the least informed" (DOC-03 section 8.5).
    identity_rule            jsonb       NOT NULL,
    attribute_schema_ref     uuid,
    permitted_edges          jsonb       NOT NULL DEFAULT '[]'::jsonb,
    is_network_reachable     bool        NOT NULL,
    may_carry_findings       bool        NOT NULL,
    applicable_checklist_ids uuid[]      NOT NULL DEFAULT '{}',
    lifecycle_state          text        NOT NULL DEFAULT 'ACTIVE',
    created_at               timestamptz NOT NULL DEFAULT now(),
    created_by               uuid,
    updated_at               timestamptz NOT NULL DEFAULT now(),
    updated_by               uuid,
    row_version              int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_asset_type__lifecycle CHECK (lifecycle_state IN ('ACTIVE', 'DEPRECATED')),
    CONSTRAINT ck_asset_type__identity_rule CHECK (
        jsonb_typeof(identity_rule) = 'object'
        AND identity_rule ? 'version'
        AND identity_rule ? 'natural_key_attributes'),
    -- A network-reachable type that cannot carry findings is a contradiction: exposure classification
    -- exists so a conflict becomes a finding (INV-AST-08), and such a type has nowhere to put one.
    CONSTRAINT ck_asset_type__reachable_carries_findings CHECK
        (NOT is_network_reachable OR may_carry_findings)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_asset_type__code ON asset_type (tenant_id, code);
SELECT apply_tenant_isolation('asset_type');

-- INV-AST-04, reusing the trigger function V004 defined. One function, three tables — a second copy
-- would be a second place the rule could drift.
DROP TRIGGER IF EXISTS tr_asset_type__immutable_code ON asset_type;
CREATE TRIGGER tr_asset_type__immutable_code
    BEFORE UPDATE ON asset_type
    FOR EACH ROW EXECUTE FUNCTION reject_code_change();

-- -----------------------------------------------------------------------------
-- 2. asset — DOC-04 section 11.3.2
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS asset (
    id                        uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                 uuid        NOT NULL,
    -- INV-AST-01: RESTRICT plus the immutability trigger below.
    type_id                   uuid        NOT NULL REFERENCES asset_type (id) ON DELETE RESTRICT,
    identity_key              text        NOT NULL,
    identity_rule_version     int         NOT NULL,
    display_name              text        NOT NULL,
    -- INV-AST-05: ONE nullable column. DOC-04 section 11.3.2: "a single nullable owning_node_id makes
    -- more than one owner unrepresentable." Null means UNCLAIMED, which is a state and not a defect.
    owning_node_id            uuid,
    criticality_mode          text        NOT NULL DEFAULT 'INHERITED',
    criticality_tier_id       uuid        REFERENCES criticality_tier (id) ON DELETE RESTRICT,
    criticality_justification text,
    exposure_declared         text,
    exposure_declared_by      uuid,
    exposure_declared_at      timestamptz,
    exposure_observed         text,
    exposure_observed_source  text,
    exposure_observed_at      timestamptz,
    -- Derived, but STORED, per DOC-04's P6 note: the exposure conflict queue is read far more often
    -- than exposure is written, and a computed-on-read flag would make the queue a full scan. The
    -- trigger below is what keeps it honest.
    exposure_conflict         bool        NOT NULL DEFAULT false,
    lifecycle_state           text        NOT NULL DEFAULT 'DISCOVERED',
    attributes                jsonb       NOT NULL DEFAULT '{}',
    tags                      text[]      NOT NULL DEFAULT '{}',
    technical_contact_id      uuid,
    discovery_source          text        NOT NULL,
    discovery_method          text        NOT NULL,
    first_seen_at             timestamptz NOT NULL,
    last_confirmed_at         timestamptz NOT NULL,
    retired_reason            text,
    -- INV-AST-22: set on absorption so historical references resolve.
    merged_into_asset_id      uuid        REFERENCES asset (id) ON DELETE RESTRICT,
    created_at                timestamptz NOT NULL DEFAULT now(),
    created_by                uuid,
    updated_at                timestamptz NOT NULL DEFAULT now(),
    updated_by                uuid,
    row_version               int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_asset__criticality_mode CHECK (criticality_mode IN ('ASSIGNED', 'INHERITED')),
    CONSTRAINT ck_asset__criticality_tier CHECK
        (criticality_mode = 'INHERITED' OR criticality_tier_id IS NOT NULL),
    CONSTRAINT ck_asset__lifecycle CHECK
        (lifecycle_state IN ('DISCOVERED', 'ACTIVE', 'DEPRECATED', 'RETIRED')),
    CONSTRAINT ck_asset__retired_has_reason CHECK
        (lifecycle_state <> 'RETIRED' OR merged_into_asset_id IS NOT NULL OR retired_reason IS NOT NULL),
    CONSTRAINT ck_asset__no_self_merge CHECK
        (merged_into_asset_id IS NULL OR merged_into_asset_id <> id),
    CONSTRAINT ck_asset__exposure_levels CHECK (
        (exposure_declared IS NULL OR exposure_declared IN
            ('INTERNET_PUBLIC', 'PARTNER_B2B', 'INTERNAL_ONLY', 'AIR_GAPPED'))
        AND (exposure_observed IS NULL OR exposure_observed IN
            ('INTERNET_PUBLIC', 'PARTNER_B2B', 'INTERNAL_ONLY', 'AIR_GAPPED'))),
    CONSTRAINT ck_asset__identity_rule_version CHECK (identity_rule_version >= 1),
    CONSTRAINT ck_asset__confirmed_after_first_seen CHECK (last_confirmed_at >= first_seen_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_asset__identity
    ON asset (tenant_id, type_id, identity_key);
CREATE INDEX IF NOT EXISTS ix_asset__owner_state
    ON asset (tenant_id, owning_node_id, lifecycle_state);
-- The unowned asset queue (PRD-AST-011), oldest first. Partial, so it stays a small fraction.
CREATE INDEX IF NOT EXISTS ix_asset__unclaimed
    ON asset (tenant_id, first_seen_at)
    WHERE owning_node_id IS NULL AND lifecycle_state <> 'RETIRED';
CREATE INDEX IF NOT EXISTS ix_asset__type_state
    ON asset (tenant_id, type_id, lifecycle_state);
-- The exposure conflict queue (INV-AST-08). Partial for the same reason as the unclaimed queue.
CREATE INDEX IF NOT EXISTS ix_asset__exposure_conflict
    ON asset (tenant_id, exposure_observed_at)
    WHERE exposure_conflict;

SELECT apply_tenant_isolation('asset');
-- Scope descriptor columns plus the GIN index and the CON-DAT-009 immutability trigger, all from the
-- one function V004 defined. Serves ix_asset__scope_subtree: subtree-scoped reads with no closure join.
SELECT add_scope_descriptor('asset');

-- INV-AST-01 at the engine. DOC-04 section 11.3.1 maps it to "FK plus a trigger rejecting type_id
-- change", because changing a type changes the identity rule, permitted edges and attribute schema at
-- once — DOC-03 section 8.1 models that as retire-and-recreate-with-a-merge, not in-place mutation.
CREATE OR REPLACE FUNCTION reject_asset_type_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.type_id IS DISTINCT FROM OLD.type_id THEN
        RAISE EXCEPTION
            'asset type is immutable after creation (INV-AST-01). Changing it would change the '
            'identity rule, the permitted edges and the attribute schema simultaneously; the asset '
            'would need re-identification, its edges revalidated and its attributes remapped. Retire '
            'and recreate with a merge instead.'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS tr_asset__immutable_type ON asset;
CREATE TRIGGER tr_asset__immutable_type
    BEFORE UPDATE ON asset
    FOR EACH ROW EXECUTE FUNCTION reject_asset_type_change();

-- -----------------------------------------------------------------------------
-- INV-AST-12 as a trigger, and DOC-04 section 11.3.2 explains why this is one of only two places a
-- trigger is preferred to domain-only enforcement:
--
--   "the invariant protects a COVERAGE SIGNAL and a domain-layer defect would make a stale asset
--    appear fresh — a PP-1 violation through a field nobody thinks of as a metric. Cheap to enforce,
--    expensive to miss."
--
-- The rule: last_confirmed_at may advance only where discovery_source indicates evidence, and it may
-- never move backwards. A manual edit that leaves it alone passes; one that advances it is rejected.
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION reject_manual_confirmation_advance() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.last_confirmed_at < OLD.last_confirmed_at THEN
        RAISE EXCEPTION
            'last_confirmed_at cannot move backwards (INV-AST-12); an older observation arriving late '
            'is not evidence the asset was last seen earlier than it was'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.last_confirmed_at > OLD.last_confirmed_at AND NEW.discovery_source = 'MANUAL_EDIT' THEN
        RAISE EXCEPTION
            'last_confirmed_at cannot advance on a manual edit (INV-AST-12). It is a coverage signal, '
            'and coverage must not be improvable by editing: a stale asset could otherwise be made to '
            'look fresh without any evidence that it still exists.'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS tr_asset__coverage_signal ON asset;
CREATE TRIGGER tr_asset__coverage_signal
    BEFORE UPDATE ON asset
    FOR EACH ROW EXECUTE FUNCTION reject_manual_confirmation_advance();

-- INV-AST-08: keep the stored conflict flag honest. Computed on write rather than trusted from the
-- caller, because a stored flag that can disagree with the values it summarises would put an asset in
-- or out of the conflict queue for reasons no query could explain.
--
-- Lower rank is more exposed, matching ExposureClassification.Level.exposureRank in the domain. The
-- two must agree; a divergence would mean the queue and the domain disagree about what a conflict is.
CREATE OR REPLACE FUNCTION exposure_rank(level text) RETURNS int
    LANGUAGE sql IMMUTABLE
AS $$
    SELECT CASE level
        WHEN 'INTERNET_PUBLIC' THEN 0
        WHEN 'PARTNER_B2B'     THEN 1
        WHEN 'INTERNAL_ONLY'   THEN 2
        WHEN 'AIR_GAPPED'      THEN 3
    END;
$$;

CREATE OR REPLACE FUNCTION derive_exposure_conflict() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- Deliberately asymmetric: declared public but observed internal is over-declaration, which is
    -- conservative. Only UNDER-declaration understates risk, and only understated risk is a finding.
    NEW.exposure_conflict := (
        NEW.exposure_observed IS NOT NULL
        AND NEW.exposure_declared IS NOT NULL
        AND exposure_rank(NEW.exposure_observed) < exposure_rank(NEW.exposure_declared));

    -- What this trigger must NOT do: write exposure_declared from exposure_observed. INV-AST-08 —
    -- auto-correcting the declaration erases the discrepancy, and with it the finding that someone
    -- exposed a system that was not intended to be exposed.
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS tr_asset__exposure_conflict ON asset;
CREATE TRIGGER tr_asset__exposure_conflict
    BEFORE INSERT OR UPDATE ON asset
    FOR EACH ROW EXECUTE FUNCTION derive_exposure_conflict();

-- -----------------------------------------------------------------------------
-- 3. asset_relationship — DOC-04 section 11.3.3
--
-- Temporal: closed rows accumulate, and DOC-04 requires indexing for `valid_until IS NULL` as the
-- common case. Not partitioned (~500,000 rows at Extra large).
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS asset_relationship (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid        NOT NULL,
    from_asset_id  uuid        NOT NULL REFERENCES asset (id) ON DELETE RESTRICT,
    to_asset_id    uuid        NOT NULL REFERENCES asset (id) ON DELETE RESTRICT,
    edge_type      text        NOT NULL,
    discovery_source text      NOT NULL,
    attributes     jsonb       NOT NULL DEFAULT '{}',
    valid_from     timestamptz NOT NULL,
    valid_until    timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    updated_by     uuid,
    row_version    int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_asset_relationship__edge_type CHECK (edge_type IN
        ('BUILDS', 'DEPLOYS_AS', 'EXPOSES', 'PUBLISHED_ON', 'DESCRIBED_BY', 'CONTAINS', 'DEPENDS_ON')),
    CONSTRAINT ck_asset_relationship__no_self CHECK (from_asset_id <> to_asset_id),
    CONSTRAINT ck_asset_relationship__validity CHECK
        (valid_until IS NULL OR valid_until >= valid_from)
);

-- Both directions, because INV-AST-15 makes edges many-to-many both ways and DOC-03 section 8.3 notes
-- that a single direction "makes reverse traversal require scanning every asset". Partial on the
-- current case, which is what almost every query wants.
CREATE INDEX IF NOT EXISTS ix_asset_relationship__from_current
    ON asset_relationship (tenant_id, from_asset_id, edge_type) WHERE valid_until IS NULL;
CREATE INDEX IF NOT EXISTS ix_asset_relationship__to_current
    ON asset_relationship (tenant_id, to_asset_id, edge_type) WHERE valid_until IS NULL;
-- History: "what was deployed when this finding was open", for retest scoping and historical posture.
CREATE INDEX IF NOT EXISTS ix_asset_relationship__temporal
    ON asset_relationship (tenant_id, from_asset_id, valid_from DESC);

SELECT apply_tenant_isolation('asset_relationship');

-- INV-AST-16: no DELETE grant below, and a trigger rejecting reopening. A closed edge that could be
-- reopened would make "what was deployed when" answerable two ways.
CREATE OR REPLACE FUNCTION reject_edge_reopen() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.valid_until IS NOT NULL AND NEW.valid_until IS DISTINCT FROM OLD.valid_until THEN
        RAISE EXCEPTION
            'a closed edge cannot be reopened or re-closed (INV-AST-16). Superseding closes an edge '
            'with valid_until rather than deleting it; changing that instant afterwards would make '
            'historical topology answerable two ways.'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS tr_asset_relationship__no_reopen ON asset_relationship;
CREATE TRIGGER tr_asset_relationship__no_reopen
    BEFORE UPDATE ON asset_relationship
    FOR EACH ROW EXECUTE FUNCTION reject_edge_reopen();

-- -----------------------------------------------------------------------------
-- 4. asset_external_identifier — DOC-04 section 11.3.5, INV-AST-11
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS asset_external_identifier (
    id            uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL,
    asset_id      uuid        NOT NULL REFERENCES asset (id) ON DELETE RESTRICT,
    source_system text        NOT NULL,
    identifier    text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    updated_by    uuid,
    row_version   int         NOT NULL DEFAULT 1
);

-- INV-AST-11: unique per source per tenant. "Two assets claiming the same external identifier from the
-- same source is a duplicate to resolve, not a permitted state" — so this is a hard constraint, and the
-- resolution path is a merge rather than an upsert that silently picks a winner.
CREATE UNIQUE INDEX IF NOT EXISTS ux_asset_external_identifier__source_value
    ON asset_external_identifier (tenant_id, source_system, identifier);
CREATE INDEX IF NOT EXISTS ix_asset_external_identifier__asset
    ON asset_external_identifier (tenant_id, asset_id);

SELECT apply_tenant_isolation('asset_external_identifier');

-- -----------------------------------------------------------------------------
-- 5. ownership_claim — DOC-04 section 11.3.6
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ownership_claim (
    id               uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id        uuid        NOT NULL,
    asset_id         uuid        NOT NULL REFERENCES asset (id) ON DELETE RESTRICT,
    proposed_node_id uuid        NOT NULL,
    basis            text        NOT NULL,
    confidence       numeric(4, 3),
    state            text        NOT NULL DEFAULT 'PROPOSED',
    claimed_by       uuid,
    claimed_at       timestamptz NOT NULL,
    resolved_by      uuid,
    resolved_at      timestamptz,
    escalation_level int         NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    updated_by       uuid,
    row_version      int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_ownership_claim__basis CHECK (basis IN
        ('EXPLICIT', 'INFERRED_PATH_PATTERN', 'INFERRED_PIPELINE', 'INFERRED_PRIOR_FINDING',
         'INFERRED_MANUAL_PROPOSAL')),
    CONSTRAINT ck_ownership_claim__state CHECK (state IN
        ('PROPOSED', 'CONFIRMED', 'REJECTED', 'EXPIRED')),
    -- An EXPLICIT claim needs a claimant, or INV-AST-18 has nobody to authorize against the node.
    CONSTRAINT ck_ownership_claim__explicit_claimant CHECK
        (basis <> 'EXPLICIT' OR claimed_by IS NOT NULL),
    CONSTRAINT ck_ownership_claim__resolution CHECK
        ((state = 'PROPOSED') = (resolved_at IS NULL)),
    CONSTRAINT ck_ownership_claim__escalation CHECK (escalation_level >= 0),
    CONSTRAINT ck_ownership_claim__confidence CHECK
        (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

-- INV-AST-19: at most one PROPOSED claim per asset, as a partial unique index. The domain asserts it
-- over a candidate set; this is what holds when two requests arrive concurrently.
CREATE UNIQUE INDEX IF NOT EXISTS ux_ownership_claim__one_proposed
    ON ownership_claim (tenant_id, asset_id) WHERE state = 'PROPOSED';
-- The claim queue, and the escalation scheduler's read: oldest unresolved first.
CREATE INDEX IF NOT EXISTS ix_ownership_claim__pending
    ON ownership_claim (tenant_id, claimed_at) WHERE state = 'PROPOSED';
CREATE INDEX IF NOT EXISTS ix_ownership_claim__node
    ON ownership_claim (tenant_id, proposed_node_id, state);

SELECT apply_tenant_isolation('ownership_claim');

-- -----------------------------------------------------------------------------
-- 6. asset_merge — DOC-04 section 11.3.7
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS asset_merge (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id           uuid        NOT NULL,
    surviving_asset_id  uuid        NOT NULL REFERENCES asset (id) ON DELETE RESTRICT,
    absorbed_asset_ids  uuid[]      NOT NULL,
    reason              text        NOT NULL,
    -- INV-AST-24: where owners conflicted, BOTH of these are required. A merge that resolved an owner
    -- conflict without recording who decided is the unattributed accountability transfer the invariant
    -- exists to prevent.
    resolved_owner_node_id uuid,
    owner_resolved_by      uuid,
    attribute_resolutions  jsonb    NOT NULL DEFAULT '[]'::jsonb,
    -- INV-AST-23: enough state to reverse, for a bounded period.
    reversal_state      jsonb       NOT NULL,
    reversible_until    timestamptz NOT NULL,
    reversed_at         timestamptz,
    performed_by        uuid        NOT NULL,
    performed_at        timestamptz NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          uuid,
    row_version         int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_asset_merge__reason CHECK (reason IN
        ('DUPLICATE_IDENTITY', 'RULE_VERSION_CHANGE', 'MANUAL')),
    CONSTRAINT ck_asset_merge__absorbed_not_empty CHECK (cardinality(absorbed_asset_ids) > 0),
    CONSTRAINT ck_asset_merge__no_self_absorb CHECK
        (NOT (surviving_asset_id = ANY (absorbed_asset_ids))),
    -- The pair travels together: a resolved owner with no resolver is unattributed, and a resolver with
    -- no owner records a decision that was never made.
    CONSTRAINT ck_asset_merge__owner_resolution_pair CHECK
        ((resolved_owner_node_id IS NULL) = (owner_resolved_by IS NULL)
         OR resolved_owner_node_id IS NOT NULL),
    CONSTRAINT ck_asset_merge__reversal_window CHECK (reversible_until > performed_at)
);

CREATE INDEX IF NOT EXISTS ix_asset_merge__survivor
    ON asset_merge (tenant_id, surviving_asset_id, performed_at DESC);
CREATE INDEX IF NOT EXISTS ix_asset_merge__reversible
    ON asset_merge (tenant_id, reversible_until) WHERE reversed_at IS NULL;

SELECT apply_tenant_isolation('asset_merge');

-- -----------------------------------------------------------------------------
-- 7. Grants
--
-- INV-AST-10 and INV-AST-03: no DELETE grant on asset, asset_type or asset_relationship. Retirement
-- and closure are the mechanisms; ON DELETE RESTRICT from referencing tables is the other half of the
-- pair, and neither half alone is sufficient — RESTRICT permits deleting a not-yet-referenced asset
-- whose historical descriptors already name it.
-- -----------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON asset_type, asset, asset_relationship, asset_external_identifier,
    ownership_claim, asset_merge TO app_runtime;
-- The one DELETE the application needs: an external identifier moved during a merge. The row is not
-- history — the merge record is — so removing it does not destroy anything INV-AST-21 requires kept.
GRANT DELETE ON asset_external_identifier TO app_runtime;

GRANT SELECT ON asset_type, asset, asset_relationship, asset_external_identifier, ownership_claim,
    asset_merge TO integrity_verifier;
GRANT SELECT, INSERT, UPDATE, DELETE ON asset_type, asset, asset_relationship,
    asset_external_identifier, ownership_claim, asset_merge TO migration_runner;
