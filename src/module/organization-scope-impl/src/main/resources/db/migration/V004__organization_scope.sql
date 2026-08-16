-- =============================================================================
-- V004 — organization-scope: the tree, the closure, and the scope descriptor columns
--
-- Owner: module/organization-scope. DOC-04 sections 11.2 and 6.6, DOC-03 sections 6.7 and 7.
--
-- Order-critical (PRD-PLT-001). The scope descriptor columns defined here are carried by every
-- scope-bearing table from this point on, and DOC-03 section 6.7 states the consequence of omitting
-- them: "the mechanism cannot be added later — the data does not exist retroactively".
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. criticality_tier — DOC-04 section 11.2.4, DOC-03 section 7.6
--
-- Taxonomy table per DOC-04 section 8.1. Tier names and count are tenant-configured (CFG-AST-001);
-- the ordinal is the product-fixed comparison mechanism. DOC-03 section 7.6: "tenants need their own
-- vocabulary, and the platform needs a stable basis for comparison, normalization, and cross-tenant
-- support. Configurable presentation over a fixed ordinal satisfies both; a fully configurable scale
-- satisfies neither."
--
-- LOWER ordinal means MORE critical (INV-ORG-16). Stated here because the opposite convention is
-- equally plausible and a reversed comparison would invert every prioritisation silently.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS criticality_tier (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid,                                  -- null => platform-supplied default
    code            text        NOT NULL,                   -- immutable (INV-ORG-04 pattern)
    label_i18n      jsonb       NOT NULL,                   -- { "en": "...", "vi": "..." }
    ordinal         int         NOT NULL,                   -- lower => more critical
    lifecycle_state text        NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      uuid,
    row_version     int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_criticality_tier__lifecycle CHECK (lifecycle_state IN ('ACTIVE', 'DEPRECATED')),
    CONSTRAINT ck_criticality_tier__label CHECK (jsonb_typeof(label_i18n) = 'object'
                                                 AND label_i18n <> '{}'::jsonb)
);

-- INV-ORG-16: ordinals unique within a tenant and totally ordered. Two tiers sharing an ordinal make
-- the comparison non-deterministic, and the comparison decides prioritisation.
CREATE UNIQUE INDEX IF NOT EXISTS ux_criticality_tier__code
    ON criticality_tier (tenant_id, code);
CREATE UNIQUE INDEX IF NOT EXISTS ux_criticality_tier__ordinal
    ON criticality_tier (tenant_id, ordinal);

SELECT apply_tenant_isolation('criticality_tier');

-- -----------------------------------------------------------------------------
-- 2. org_node_type — DOC-04 section 11.2.1
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS org_node_type (
    id                        uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                 uuid        NOT NULL,
    code                      text        NOT NULL,
    label_i18n                jsonb       NOT NULL,
    ordinal                   int         NOT NULL,
    -- Empty => may be a tree root (INV-ORG-01). An array rather than a join table: the set is small,
    -- read on every node creation, and never queried from the other direction.
    permitted_parent_type_ids uuid[]      NOT NULL DEFAULT '{}',
    may_own_assets            bool        NOT NULL,
    may_scope_work            bool        NOT NULL,
    lifecycle_state           text        NOT NULL DEFAULT 'ACTIVE',
    created_at                timestamptz NOT NULL DEFAULT now(),
    created_by                uuid,
    updated_at                timestamptz NOT NULL DEFAULT now(),
    updated_by                uuid,
    row_version               int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_org_node_type__lifecycle CHECK (lifecycle_state IN ('ACTIVE', 'DEPRECATED')),
    -- The trivial type-level cycle. Full INV-ORG-02 detection is a traversal and lives in the domain.
    CONSTRAINT ck_org_node_type__no_self_parent CHECK (NOT (id = ANY (permitted_parent_type_ids)))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_org_node_type__code ON org_node_type (tenant_id, code);
CREATE INDEX IF NOT EXISTS ix_org_node_type__ordinal ON org_node_type (tenant_id, ordinal);

SELECT apply_tenant_isolation('org_node_type');

-- INV-ORG-04 at the engine: code is immutable, label is freely editable. DOC-04 section 11.2.1 maps
-- this to "an update trigger rejecting a code change". A domain-only check would be bypassed by an
-- import path, and the breakage appears as empty query results rather than as an error.
CREATE OR REPLACE FUNCTION reject_code_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.code IS DISTINCT FROM OLD.code THEN
        -- Three % placeholders for three arguments. An earlier version wrote ''%%'' intending a
        -- quoted literal, which PL/pgSQL reads as an ESCAPED PERCENT rather than a placeholder — so the
        -- format string had one placeholder and three arguments, and the function failed to compile.
        -- Double quotes in the message avoid the quote-escaping that caused it.
        RAISE EXCEPTION
            'code is immutable on %: "%" -> "%" (INV-ORG-04). Integrations, saved queries, imports and '
            'API consumers reference the code; changing it breaks them silently, as empty results rather '
            'than errors. Change the label instead.',
            TG_TABLE_NAME, OLD.code, NEW.code
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS tr_org_node_type__immutable_code ON org_node_type;
CREATE TRIGGER tr_org_node_type__immutable_code
    BEFORE UPDATE ON org_node_type
    FOR EACH ROW EXECUTE FUNCTION reject_code_change();

DROP TRIGGER IF EXISTS tr_criticality_tier__immutable_code ON criticality_tier;
CREATE TRIGGER tr_criticality_tier__immutable_code
    BEFORE UPDATE ON criticality_tier
    FOR EACH ROW EXECUTE FUNCTION reject_code_change();

-- -----------------------------------------------------------------------------
-- 3. org_node — DOC-04 section 11.2.2
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS org_node (
    id                        uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                 uuid        NOT NULL,
    -- INV-ORG-03: RESTRICT means a type in use cannot be deleted, only deprecated.
    type_id                   uuid        NOT NULL REFERENCES org_node_type (id) ON DELETE RESTRICT,
    -- INV-ORG-05: a single column IS the "exactly one parent" invariant. Null => tree root.
    -- INV-ORG-10: RESTRICT means a node with children cannot be deleted.
    parent_id                 uuid        REFERENCES org_node (id) ON DELETE RESTRICT,
    name                      text        NOT NULL,
    external_reference        text,
    criticality_mode          text        NOT NULL,
    criticality_tier_id       uuid        REFERENCES criticality_tier (id) ON DELETE RESTRICT,
    criticality_justification text,
    criticality_assigned_by   uuid,
    criticality_assigned_at   timestamptz,
    lifecycle_state           text        NOT NULL DEFAULT 'ACTIVE',
    tags                      text[]      NOT NULL DEFAULT '{}',
    created_at                timestamptz NOT NULL DEFAULT now(),
    created_by                uuid,
    updated_at                timestamptz NOT NULL DEFAULT now(),
    updated_by                uuid,
    row_version               int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_org_node__criticality_mode CHECK (criticality_mode IN ('ASSIGNED', 'INHERITED')),
    CONSTRAINT ck_org_node__criticality_tier CHECK
        (criticality_mode = 'INHERITED' OR criticality_tier_id IS NOT NULL),
    CONSTRAINT ck_org_node__inherited_carries_no_tier CHECK
        (criticality_mode = 'ASSIGNED' OR criticality_tier_id IS NULL),
    -- The trivial self-cycle. Full INV-ORG-07 detection is a traversal and lives in the domain.
    CONSTRAINT ck_org_node__no_self_parent CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_org_node__lifecycle CHECK
        (lifecycle_state IN ('ACTIVE', 'DEPRECATED', 'ARCHIVED')),
    -- INV-ORG-09, deliberately STRICTER than the invariant. DOC-04 section 11.2.2: the invariant
    -- requires justification only when overriding an ancestor, which is not expressible per row; this
    -- CHECK requires it on every explicit assignment, including the root where there is nothing to
    -- override. "This is deliberate: the root's criticality assignment is the most consequential in
    -- the tenant, and requiring a justification for it costs one sentence at onboarding."
    CONSTRAINT ck_org_node__assigned_justification CHECK
        (criticality_mode <> 'ASSIGNED'
         OR (criticality_justification IS NOT NULL AND btrim(criticality_justification) <> ''))
);

CREATE INDEX IF NOT EXISTS ix_org_node__parent ON org_node (tenant_id, parent_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_org_node__sibling_name
    ON org_node (tenant_id, parent_id, name);
CREATE UNIQUE INDEX IF NOT EXISTS ux_org_node__external_ref
    ON org_node (tenant_id, external_reference) WHERE external_reference IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_org_node__type_state
    ON org_node (tenant_id, type_id, lifecycle_state);
CREATE INDEX IF NOT EXISTS ix_org_node__criticality
    ON org_node (tenant_id, criticality_tier_id) WHERE criticality_mode = 'ASSIGNED';

SELECT apply_tenant_isolation('org_node');

-- -----------------------------------------------------------------------------
-- 4. org_node_owner — DOC-04 section 11.2.2
--
-- A separate table rather than array columns "because INV-ORG-12 permits an empty set, distinguishes
-- business from technical ownership, and ownership changes are audited independently of node changes".
-- Note the deliberate ABSENCE of a NOT NULL requirement anywhere: DOC-04 maps INV-ORG-12 to
-- "no NOT NULL on ownership, by design". An unowned node is a normal transient that raises an event.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS org_node_owner (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid        NOT NULL,
    org_node_id  uuid        NOT NULL REFERENCES org_node (id) ON DELETE RESTRICT,
    principal_id uuid        NOT NULL,
    owner_kind   text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    updated_by   uuid,
    row_version  int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_org_node_owner__kind CHECK (owner_kind IN ('BUSINESS', 'TECHNICAL'))
);

-- Business and technical sets may overlap for the same principal (INV-ORG-12), so the kind is part
-- of the key rather than a second row being a duplicate.
CREATE UNIQUE INDEX IF NOT EXISTS ux_org_node_owner__assignment
    ON org_node_owner (tenant_id, org_node_id, principal_id, owner_kind);
CREATE INDEX IF NOT EXISTS ix_org_node_owner__principal
    ON org_node_owner (tenant_id, principal_id);

SELECT apply_tenant_isolation('org_node_owner');

-- -----------------------------------------------------------------------------
-- 5. org_closure — DOC-04 section 11.2.3
--
-- A composite primary key, which DOC-04 records as "the one place a composite key is used, because
-- the table has no independent identity and CON-DAT-006's reasons do not apply to a derived
-- projection. Recorded as a deliberate exception."
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS org_closure (
    tenant_id         uuid   NOT NULL,
    ancestor_id       uuid   NOT NULL REFERENCES org_node (id) ON DELETE RESTRICT,
    descendant_id     uuid   NOT NULL REFERENCES org_node (id) ON DELETE RESTRICT,
    depth             int    NOT NULL,
    hierarchy_version bigint NOT NULL,

    CONSTRAINT pk_org_closure PRIMARY KEY (tenant_id, ancestor_id, descendant_id),
    CONSTRAINT ck_org_closure__depth CHECK (depth >= 0),
    -- depth zero is the self-reference and nothing else (INV-ORG-13).
    CONSTRAINT ck_org_closure__self_at_zero CHECK
        ((depth = 0) = (ancestor_id = descendant_id))
);

CREATE INDEX IF NOT EXISTS ix_org_closure__descendant
    ON org_closure (tenant_id, descendant_id, depth);
CREATE INDEX IF NOT EXISTS ix_org_closure__ancestor_depth
    ON org_closure (tenant_id, ancestor_id, depth);

SELECT apply_tenant_isolation('org_closure');

-- INV-ORG-13 at the engine, as DOC-04 section 11.2.3 requires: "a constraint trigger asserting a
-- depth-zero row exists for every node". DEFERRABLE and checked at commit, because during a closure
-- rebuild the rows are deleted and reinserted and an immediate check would fail mid-transaction.
CREATE OR REPLACE FUNCTION assert_closure_self_reference() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    missing_count int;
BEGIN
    SELECT count(*) INTO missing_count
      FROM org_node n
     WHERE NOT EXISTS (
        SELECT 1 FROM org_closure c
         WHERE c.tenant_id = n.tenant_id
           AND c.ancestor_id = n.id
           AND c.descendant_id = n.id
           AND c.depth = 0);

    IF missing_count > 0 THEN
        RAISE EXCEPTION
            '% node(s) lack a depth-zero closure self-reference (INV-ORG-13). Without it "the '
            'subtree of X" excludes X and every scope query is subtly wrong.', missing_count
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END
$$;

DROP TRIGGER IF EXISTS tr_org_closure__self_reference ON org_closure;
CREATE CONSTRAINT TRIGGER tr_org_closure__self_reference
    AFTER INSERT OR UPDATE OR DELETE ON org_closure
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_closure_self_reference();

DROP TRIGGER IF EXISTS tr_org_node__self_reference ON org_node;
CREATE CONSTRAINT TRIGGER tr_org_node__self_reference
    AFTER INSERT ON org_node
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_closure_self_reference();

-- The CON-DAT-026 reconciliation, as a function so the job is a query rather than application code.
-- INV-ORG-14: the closure is a pure function of parentage, and rebuild-and-compare "is the only
-- practical detection mechanism". Direction is preserved because it decides severity: an EXTRANEOUS
-- row grants access nobody reports, a MISSING row denies access somebody reports within the hour.
CREATE OR REPLACE FUNCTION org_closure_divergence(target_tenant uuid)
    RETURNS TABLE (divergence text, ancestor_id uuid, descendant_id uuid, depth int)
    LANGUAGE sql STABLE
AS $$
    WITH RECURSIVE rebuilt AS (
        SELECT n.id AS ancestor_id, n.id AS descendant_id, 0 AS depth
          FROM org_node n
         WHERE n.tenant_id = target_tenant
        UNION ALL
        SELECT r.ancestor_id, n.id, r.depth + 1
          FROM rebuilt r
          JOIN org_node n ON n.parent_id = r.descendant_id AND n.tenant_id = target_tenant
    ),
    stored AS (
        SELECT c.ancestor_id, c.descendant_id, c.depth
          FROM org_closure c
         WHERE c.tenant_id = target_tenant
    )
    SELECT 'EXTRANEOUS', s.ancestor_id, s.descendant_id, s.depth
      FROM stored s
     WHERE NOT EXISTS (SELECT 1 FROM rebuilt r
                        WHERE r.ancestor_id = s.ancestor_id
                          AND r.descendant_id = s.descendant_id
                          AND r.depth = s.depth)
    UNION ALL
    SELECT 'MISSING', r.ancestor_id, r.descendant_id, r.depth
      FROM rebuilt r
     WHERE NOT EXISTS (SELECT 1 FROM stored s
                        WHERE s.ancestor_id = r.ancestor_id
                          AND s.descendant_id = r.descendant_id
                          AND s.depth = r.depth);
$$;

GRANT EXECUTE ON FUNCTION org_closure_divergence(uuid) TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 6. The scope descriptor columns — DOC-04 section 6.6, CON-DAT-009
--
-- Applied by a function so that every scope-bearing table gets the same six columns with the same
-- immutability trigger. Applying them by hand per table is how one table ends up without the trigger,
-- and a mutable descriptor silently destroys historical reproducibility — DOC-16 section 4.3: "a
-- historical report that changes after a reorganization looks like a data error rather than an
-- authorization defect".
--
-- No foreign key to org_node on scope_node_id: ADR-030 forbids foreign keys across module boundaries,
-- and every scope-bearing table belongs to another module. Integrity is maintained in the domain with
-- reconciliation, which is also what lets an ARCHIVED node stay referenced by history (INV-ORG-11).
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION add_scope_descriptor(target regclass) RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    table_name text := target::text;
BEGIN
    EXECUTE format(
        'ALTER TABLE %s '
        '  ADD COLUMN IF NOT EXISTS scope_node_id        uuid,'
        '  ADD COLUMN IF NOT EXISTS scope_ancestor_path  uuid[],'
        '  ADD COLUMN IF NOT EXISTS scope_node_type_id   uuid,'
        '  ADD COLUMN IF NOT EXISTS scope_criticality_id uuid,'
        '  ADD COLUMN IF NOT EXISTS scope_hierarchy_ver  bigint,'
        '  ADD COLUMN IF NOT EXISTS scope_resolved_at    timestamptz', target);

    -- The GIN index serving DOC-04 section 6.6's containment predicate:
    --   "was this principal, authorized for node N, authorized for this object at that time?"
    -- is scope_ancestor_path @> ARRAY[N] — a single indexable test, with no historical closure to
    -- reconstruct. This index is the reason the array is stored rather than joined.
    EXECUTE format(
        'CREATE INDEX IF NOT EXISTS ix_%s__scope_path ON %s USING gin (scope_ancestor_path)',
        table_name, target);

    EXECUTE format('DROP TRIGGER IF EXISTS tr_%s__immutable_scope ON %s', table_name, target);
    EXECUTE format(
        'CREATE TRIGGER tr_%s__immutable_scope BEFORE UPDATE ON %s '
        'FOR EACH ROW EXECUTE FUNCTION reject_scope_descriptor_change()', table_name, target);
END
$$;

GRANT EXECUTE ON FUNCTION add_scope_descriptor(regclass) TO migration_runner;

-- The conformance assertion. A scope-bearing table added without the immutability trigger is a silent
-- hole, exactly as a tenant-scoped table without FORCE is; this is the org-scope counterpart of
-- tenant_isolation_gaps().
CREATE OR REPLACE FUNCTION scope_descriptor_gaps()
    RETURNS TABLE (table_name text, gap text)
    LANGUAGE sql STABLE
AS $$
    -- Checks for a trigger EXECUTING reject_scope_descriptor_change, not for a trigger with a particular
    -- NAME.
    --
    -- *** THE NAME-BASED VERSION WAS WRONG, AND RUNNING IT SHOWED WHY. ***
    -- PostgreSQL propagates a row-level trigger from a partitioned parent to each partition, and the
    -- clone carries the PARENT's trigger name. The original query built the expected name from the
    -- partition's own relname, so it never matched a partition and reported every partition of
    -- audit_event and finding as an unprotected table. The triggers were present the whole time.
    --
    -- This is the third defect in this build caused by asserting a naming convention instead of the
    -- property it stands for — the others were an ArchUnit rule whose (*) was a plain wildcard, and a
    -- descriptor lookup keyed on a class-name suffix. Checking tgfoid is checking the property.
    SELECT c.relname::text,
           'scope descriptor columns present without the immutability guard'
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'scope_ancestor_path' AND a.attnum > 0
     WHERE n.nspname = current_schema()
       AND c.relkind IN ('r', 'p')
       AND NOT EXISTS (
            SELECT 1
              FROM pg_trigger t
             WHERE t.tgrelid = c.oid
               AND NOT t.tgisinternal
               AND t.tgfoid = 'reject_scope_descriptor_change'::regproc);
$$;

GRANT EXECUTE ON FUNCTION scope_descriptor_gaps() TO migration_runner, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 7. Grants
-- -----------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON criticality_tier, org_node_type, org_node, org_node_owner
    TO app_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON org_closure TO app_runtime;   -- rebuilt, so DELETE is required

-- INV-ORG-10 and INV-ORG-17: no DELETE grant on org_node, org_node_type or criticality_tier to the
-- application. Deletion is prevented by ON DELETE RESTRICT from referencing tables AND by the absence
-- of the grant; the pair is the control, because RESTRICT alone permits deleting a not-yet-referenced
-- node whose historical descriptors already name it.
GRANT SELECT ON criticality_tier, org_node_type, org_node, org_node_owner, org_closure
    TO integrity_verifier;
GRANT SELECT, INSERT, UPDATE, DELETE ON criticality_tier, org_node_type, org_node, org_node_owner,
    org_closure TO migration_runner;
