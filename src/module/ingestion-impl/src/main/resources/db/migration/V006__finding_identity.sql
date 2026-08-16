-- =============================================================================
-- V006 — finding identity: the finding, its retained fingerprint inputs, its asset impacts
--
-- Owner: module/ingestion writes it; vulnerability-management owns the Finding aggregate. The tables
-- live here because ingestion creates them and DOC-04 section 13 places them together; the aggregate's
-- behaviour is prompt 7's.
--
-- =============================================================================
-- *** THE IRREVERSIBLE DECISION IN THIS MIGRATION. READ BEFORE APPLYING. ***
--
-- `finding` and `finding_asset_impact` are HASH partitioned by tenant, aligned (DOC-04 section 22.2).
-- CON-DAT-035: "Changing a hash partition count redistributes every row." DOC-04 section 23.2 lists it
-- among the operations requiring particular care and calls it a FULL TABLE REWRITE. These are the
-- largest tables in the platform — 8,000,000 rows at the Extra large profile.
--
-- OPS-DEP-012 requires the count to be set before first production data and recorded with its basis.
-- The basis follows. It is a WORKING ASSUMPTION and it is not confirmed.
--
--   ⚠ WORKING ASSUMPTION (OQ-015)
--   ---------------------------------------------------------------------------------------------
--   Chosen count: 32 partitions for `finding`, and 32 aligned for `finding_asset_impact`.
--
--   Basis: the Medium reference profile of DOC-01 section 12.1 with headroom to Extra large, which
--   is exactly what DOC-15 section 5.2 records as the standing assumption. Medium is 300,000 total
--   findings (lifetime); Extra large is 8,000,000. At 32 partitions Extra large gives ~250,000 rows
--   per partition, which keeps a single-partition scan tractable and matches the count DOC-04
--   section 22.2 already fixes for `component_entry` — chosen to match so that operators reason about
--   one number rather than two.
--
--   Why 32 and not 8 or 128. Too few partitions defeats the purpose: DOC-04 section 10 gives the
--   reason for hash-by-tenant as "bounds any single query's scan; isolates one tenant's growth", and
--   8 partitions at Extra large leaves 1,000,000 rows per partition. Too many multiplies planning
--   cost on every query and partition count on every maintenance operation, for a table that is
--   already partition-pruned by a mandatory tenant predicate.
--
--   *** This choice is reversible until the first production row exists, and not afterwards. ***
--   OQ-015 remains open. An order-of-magnitude answer changes this number; nothing else in this
--   migration depends on it. Before any production deployment, confirm the sizing and re-run this
--   migration with a corrected count if the answer differs by an order of magnitude in either
--   direction. A test in :kernel-verification asserts the count actually applied, so a drift between
--   this comment and the database is a failure rather than a discovery.
--   ---------------------------------------------------------------------------------------------
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. severity_level — DOC-04 section 13.1, taxonomy per section 8.1
--
-- Tenant-configurable labels over a product-fixed ordinal, the same pattern as criticality_tier and
-- for the same reason (PRD-VUL-005). LOWER ordinal is MORE severe, matching criticality_tier so the
-- two are read the same way.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS severity_level (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid,
    code            text        NOT NULL,
    label_i18n      jsonb       NOT NULL,
    ordinal         int         NOT NULL,
    lifecycle_state text        NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      uuid,
    row_version     int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_severity_level__lifecycle CHECK (lifecycle_state IN ('ACTIVE', 'DEPRECATED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_severity_level__code ON severity_level (tenant_id, code);
CREATE UNIQUE INDEX IF NOT EXISTS ux_severity_level__ordinal ON severity_level (tenant_id, ordinal);
SELECT apply_tenant_isolation('severity_level');

DROP TRIGGER IF EXISTS tr_severity_level__immutable_code ON severity_level;
CREATE TRIGGER tr_severity_level__immutable_code
    BEFORE UPDATE ON severity_level
    FOR EACH ROW EXECUTE FUNCTION reject_code_change();

-- -----------------------------------------------------------------------------
-- 2. finding — DOC-04 section 13.2
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS finding (
    id                       uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id                uuid        NOT NULL,

    -- Identity. INV-VUL-01: the digest is computed over tenant-inclusive inputs, so two tenants with
    -- the same underlying weakness hold different digests. The unique index below is therefore
    -- tenant-scoped for defence in depth rather than as the isolation mechanism.
    fingerprint_digest       bytea       NOT NULL,
    fingerprint_algorithm_version int    NOT NULL,
    finding_class            text        NOT NULL,

    title                    text        NOT NULL,
    description              text,

    -- INV-VUL-08: reported severity is immutable, as received. Effective severity is adjustable and
    -- carries its actor and reason, so an adjustment is attributable. Two columns rather than one
    -- because overwriting the reported value destroys the ability to see that it was changed.
    reported_severity_id     uuid        REFERENCES severity_level (id) ON DELETE RESTRICT,
    reported_severity_raw    text,
    effective_severity_id    uuid        REFERENCES severity_level (id) ON DELETE RESTRICT,
    effective_severity_by    uuid,
    effective_severity_at    timestamptz,
    effective_severity_reason text,

    state                    text        NOT NULL DEFAULT 'OPEN',
    closure_reason           text,
    closure_verified_by      uuid,
    closure_verification_method text,
    closed_at                timestamptz,

    assignee_id              uuid,
    recurrence_count         int         NOT NULL DEFAULT 0,

    -- Provenance. PRD-ING-022 retains the raw source record; the raw document itself is in object
    -- storage per ADR-056 and this is its reference, because a multi-megabyte scanner report inside
    -- the platform's hottest table is a read amplification nobody budgeted.
    source_tool              text        NOT NULL,
    source_tool_version      text,
    source_rule_identity     text,
    source_import_session_id uuid,
    raw_source_record_ref    text        NOT NULL,

    first_detected_at        timestamptz NOT NULL,
    last_detected_at         timestamptz NOT NULL,

    created_at               timestamptz NOT NULL DEFAULT now(),
    created_by               uuid,
    updated_at               timestamptz NOT NULL DEFAULT now(),
    updated_by               uuid,
    row_version              int         NOT NULL DEFAULT 1,

    CONSTRAINT pk_finding PRIMARY KEY (id, tenant_id),
    CONSTRAINT ck_finding__class CHECK (finding_class IN
        ('CODE', 'DEPENDENCY', 'RUNTIME', 'INFRASTRUCTURE', 'SECRET', 'MANUAL', 'CONFIGURATION')),
    CONSTRAINT ck_finding__algorithm_version CHECK (fingerprint_algorithm_version >= 1),
    CONSTRAINT ck_finding__recurrence CHECK (recurrence_count >= 0),
    CONSTRAINT ck_finding__detection_order CHECK (last_detected_at >= first_detected_at),
    -- INV-VUL-11: FIXED_VERIFIED requires a verification method and a verifier. Enforced here because
    -- a closure claiming verification without one is the closure nobody can defend in an audit.
    CONSTRAINT ck_finding__verified_closure CHECK (
        closure_reason IS DISTINCT FROM 'FIXED_VERIFIED'
        OR (closure_verified_by IS NOT NULL AND closure_verification_method IS NOT NULL)),
    -- INV-VUL-16: a SECRET class finding cannot be closed as RISK_ACCEPTED. A live credential is not a
    -- risk to weigh; its only remediation is rotation (PRD-VUL-019).
    CONSTRAINT ck_finding__secret_not_accepted CHECK (
        finding_class <> 'SECRET' OR closure_reason IS DISTINCT FROM 'RISK_ACCEPTED'),
    CONSTRAINT ck_finding__closure_pair CHECK ((closed_at IS NULL) = (closure_reason IS NULL))
) PARTITION BY HASH (tenant_id);

-- 32 partitions. See the basis recorded at the top of this file.
DO $$
DECLARE
    partition_count constant int := 32;
BEGIN
    FOR i IN 0..(partition_count - 1) LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS finding_p%s PARTITION OF finding '
            'FOR VALUES WITH (MODULUS %s, REMAINDER %s)', i, partition_count, i);
    END LOOP;
END
$$;

-- Identity resolution on every ingestion — DOC-04 calls this "the highest-frequency write-path lookup".
CREATE UNIQUE INDEX IF NOT EXISTS ux_finding__fingerprint
    ON finding (tenant_id, fingerprint_algorithm_version, fingerprint_digest);
CREATE INDEX IF NOT EXISTS ix_finding__state_severity
    ON finding (tenant_id, state, effective_severity_id);
CREATE INDEX IF NOT EXISTS ix_finding__assignee
    ON finding (tenant_id, assignee_id, state);
CREATE INDEX IF NOT EXISTS ix_finding__last_detected
    ON finding (tenant_id, last_detected_at DESC);

SELECT apply_tenant_isolation('finding');
SELECT add_scope_descriptor('finding');

-- INV-VUL-08 at the engine: the reported severity is immutable once recorded.
CREATE OR REPLACE FUNCTION reject_reported_severity_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.reported_severity_id IS DISTINCT FROM OLD.reported_severity_id
    OR NEW.reported_severity_raw IS DISTINCT FROM OLD.reported_severity_raw THEN
        RAISE EXCEPTION
            'reported severity is immutable, as received (INV-VUL-08). Adjust effective_severity_id '
            'instead, which carries its actor and reason. Overwriting what the tool reported destroys '
            'the ability to see that it was changed, and with it the ability to audit the adjustment.'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS tr_finding__immutable_reported_severity ON finding;
CREATE TRIGGER tr_finding__immutable_reported_severity
    BEFORE UPDATE ON finding
    FOR EACH ROW EXECUTE FUNCTION reject_reported_severity_change();

-- INV-VUL-01 and INV-ING-01 at the engine, as far as the engine can reach: the fingerprint digest and
-- its algorithm version are immutable. Re-fingerprinting is a MIGRATION run as migration_runner
-- (INV-VUL-05), not an application update — and the migration credential bypasses this trigger by
-- being the one that may drop and recreate it, which is a deliberate, enumerated act (SEC-TEN-008).
CREATE OR REPLACE FUNCTION reject_fingerprint_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.fingerprint_digest IS DISTINCT FROM OLD.fingerprint_digest
    OR NEW.fingerprint_algorithm_version IS DISTINCT FROM OLD.fingerprint_algorithm_version THEN
        RAISE EXCEPTION
            'a finding fingerprint is immutable from the application (INV-ING-01, INV-VUL-05). '
            'Re-fingerprinting preserves triage state, assignment, comments, exceptions and history: '
            'it is a migration, never a recompute-and-replace, and DOC-04 section 23.2 calls it the '
            'most complex data migration the platform will perform.'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS tr_finding__immutable_fingerprint ON finding;
CREATE TRIGGER tr_finding__immutable_fingerprint
    BEFORE UPDATE ON finding
    FOR EACH ROW EXECUTE FUNCTION reject_fingerprint_change();

-- -----------------------------------------------------------------------------
-- 3. finding_fingerprint_input — DOC-04 section 13.3, INV-VUL-04
--
-- A separate NARROW table, and DOC-04 explains why: "the inputs document is read only during
-- re-fingerprinting, which is rare, and it is comparatively wide. Keeping it out of `finding` keeps the
-- main table's rows narrow, which matters because `finding` is read constantly and
-- `finding_fingerprint_input` almost never."
--
-- The storage cost is stated in DOC-04 and accepted: 200–400 bytes per finding, 2–3 GB at Extra large.
-- "This is the price of being able to improve the fingerprint algorithm at all, and without it the
-- first algorithm version is permanent."
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS finding_fingerprint_input (
    finding_id        uuid  NOT NULL,
    tenant_id         uuid  NOT NULL,
    algorithm_version int   NOT NULL,
    inputs            jsonb NOT NULL,

    CONSTRAINT pk_finding_fingerprint_input PRIMARY KEY (finding_id, tenant_id),
    CONSTRAINT ck_fp_input__version CHECK (algorithm_version >= 1),
    CONSTRAINT ck_fp_input__inputs_object CHECK (jsonb_typeof(inputs) = 'object' AND inputs <> '{}'::jsonb)
) PARTITION BY HASH (tenant_id);

DO $$
DECLARE
    partition_count constant int := 32;
BEGIN
    FOR i IN 0..(partition_count - 1) LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS finding_fingerprint_input_p%s '
            'PARTITION OF finding_fingerprint_input '
            'FOR VALUES WITH (MODULUS %s, REMAINDER %s)', i, partition_count, i);
    END LOOP;
END
$$;

-- The migration driver: findings requiring re-fingerprinting after an algorithm change.
CREATE INDEX IF NOT EXISTS ix_fp_input__version
    ON finding_fingerprint_input (tenant_id, algorithm_version);

SELECT apply_tenant_isolation('finding_fingerprint_input');

-- INV-VUL-04: the inputs are retained, so they must not be deletable or editable by the application.
-- No UPDATE or DELETE grant below. Losing them makes the first algorithm version permanent, which is
-- the unrecoverable outcome the table exists to prevent.

-- The assertion that INV-VUL-04 actually holds: every finding has retained inputs. A finding without
-- them cannot be re-fingerprinted, so it is permanently stuck on its creating algorithm version — and
-- one such finding is enough to make a whole-estate re-fingerprinting migration incomplete.
CREATE OR REPLACE FUNCTION findings_without_retained_inputs(target_tenant uuid)
    RETURNS TABLE (finding_id uuid, algorithm_version int)
    LANGUAGE sql STABLE
AS $$
    SELECT f.id, f.fingerprint_algorithm_version
      FROM finding f
     WHERE f.tenant_id = target_tenant
       AND NOT EXISTS (SELECT 1 FROM finding_fingerprint_input i
                        WHERE i.finding_id = f.id AND i.tenant_id = f.tenant_id);
$$;

GRANT EXECUTE ON FUNCTION findings_without_retained_inputs(uuid)
    TO app_runtime, integrity_verifier, migration_runner;

-- -----------------------------------------------------------------------------
-- 4. finding_asset_impact — DOC-04 section 13.4
--
-- Inside the Finding aggregate boundary (DOC-03 section 10.3), so written in the same transaction.
-- ALIGNED hash partitioning with `finding`: same key, same modulus, so the join between them is
-- partition-wise (CON-DAT-024). Misaligning them would make the platform's most common join a
-- cross-partition one.
--
-- Each impact carries its OWN scope descriptor, because a finding affecting six repositories owned by
-- three business units has three scopes, and DOC-07 section 9.2 derives a finding's scope from "the
-- union of affected assets' owners; each impact carries its own descriptor".
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS finding_asset_impact (
    id                uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id         uuid        NOT NULL,
    finding_id        uuid        NOT NULL,
    asset_id          uuid        NOT NULL,
    -- The location within the asset. NOT part of identity for any finding class — the fingerprint
    -- excludes it deliberately (DOC-03 section 10.2) — but retained so a remediator can find the thing.
    location_detail   jsonb       NOT NULL DEFAULT '{}',
    first_detected_at timestamptz NOT NULL,
    last_detected_at  timestamptz NOT NULL,
    resolved_at       timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    updated_by        uuid,
    row_version       int         NOT NULL DEFAULT 1,

    CONSTRAINT pk_finding_asset_impact PRIMARY KEY (id, tenant_id),
    CONSTRAINT ck_impact__detection_order CHECK (last_detected_at >= first_detected_at)
) PARTITION BY HASH (tenant_id);

DO $$
DECLARE
    -- MUST equal `finding`'s modulus. Aligned partitioning is what makes the join partition-wise, and a
    -- mismatch degrades it silently — the query still works and simply scans more.
    partition_count constant int := 32;
BEGIN
    FOR i IN 0..(partition_count - 1) LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS finding_asset_impact_p%s PARTITION OF finding_asset_impact '
            'FOR VALUES WITH (MODULUS %s, REMAINDER %s)', i, partition_count, i);
    END LOOP;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_impact__finding_asset
    ON finding_asset_impact (tenant_id, finding_id, asset_id);
CREATE INDEX IF NOT EXISTS ix_impact__asset
    ON finding_asset_impact (tenant_id, asset_id) WHERE resolved_at IS NULL;

SELECT apply_tenant_isolation('finding_asset_impact');
SELECT add_scope_descriptor('finding_asset_impact');

-- -----------------------------------------------------------------------------
-- 5. The partition alignment assertion
--
-- CON-DAT-024 requires aligned partitioning, and OPS-DEP-012 requires the count recorded with its
-- basis. A comment recording 32 and a database holding 16 is worse than no comment, so this function
-- makes the drift a failing test rather than a discovery during a capacity incident.
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION hash_partition_counts()
    RETURNS TABLE (table_name text, partition_count bigint)
    LANGUAGE sql STABLE
AS $$
    SELECT parent.relname::text, count(*)
      FROM pg_class parent
      JOIN pg_inherits i ON i.inhparent = parent.oid
      JOIN pg_class child ON child.oid = i.inhrelid
      JOIN pg_namespace n ON n.oid = parent.relnamespace
     WHERE n.nspname = current_schema()
       AND parent.relkind = 'p'
       AND parent.relname IN ('finding', 'finding_asset_impact', 'finding_fingerprint_input')
     GROUP BY parent.relname;
$$;

GRANT EXECUTE ON FUNCTION hash_partition_counts()
    TO app_runtime, integrity_verifier, migration_runner;

-- -----------------------------------------------------------------------------
-- 6. Grants
-- -----------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON severity_level, finding, finding_asset_impact TO app_runtime;
-- INV-VUL-04: INSERT and SELECT only. No UPDATE, no DELETE — the retained inputs are the record of how
-- a finding's identity was computed, and an editable record of that is not a record.
GRANT SELECT, INSERT ON finding_fingerprint_input TO app_runtime;

GRANT SELECT ON severity_level, finding, finding_fingerprint_input, finding_asset_impact
    TO integrity_verifier;
GRANT SELECT, INSERT, UPDATE, DELETE ON severity_level, finding, finding_fingerprint_input,
    finding_asset_impact TO migration_runner;
