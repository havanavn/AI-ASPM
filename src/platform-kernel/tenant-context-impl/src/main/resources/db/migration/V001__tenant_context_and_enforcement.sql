-- =============================================================================
-- V001 — tenant-context kernel: roles, the tenant function, and the enforcement pattern
--
-- Owner: platform-kernel/tenant-context. Migrations live with the module that owns their
-- tables, because CON-PLT-015 forbids a module from accessing another module's persistence
-- and a shared migration directory is the first step towards exactly that.
--
-- Engine: PostgreSQL 18 or later (ADR-049). uuidv7() is native from 18, which is why 18 is
-- the floor rather than 16 or 17 (ADR-031, DOC-04 section 22.4).
--
-- Runs as migration_runner. DOC-15 section 5.1 and CON-DAT-014: that credential holds
-- BYPASSRLS and is absent from every runtime environment reachable by application code.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. The four database credentials of DOC-15 section 5.1
--
-- Three of the four are bypass roles and are unreachable from application code. Credential
-- separation is what makes that unreachability structural rather than procedural: an
-- application that cannot obtain the credential cannot use the bypass regardless of what its
-- code attempts (CON-DAT-014, OPS-DEP-009).
--
-- ADR-049 records the residual bypass plainly: FORCE ROW LEVEL SECURITY does not bind a
-- superuser or a role holding BYPASSRLS. The constraint is satisfied only by the pair — engine
-- enforcement AND app_runtime holding neither attribute. Asserted by
-- CredentialSeparationVerification, not assumed.
-- -----------------------------------------------------------------------------

DO $$
BEGIN
    -- app_runtime: the only role the application uses. Policy enforced.
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime') THEN
        CREATE ROLE app_runtime NOLOGIN NOBYPASSRLS NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;

    -- migration_runner: schema migrations only. BYPASSRLS.
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'migration_runner') THEN
        CREATE ROLE migration_runner NOLOGIN BYPASSRLS NOSUPERUSER;
    END IF;

    -- integrity_verifier: the cross-tenant assertion of SEC-TEN-047. Read-only, BYPASSRLS.
    -- Read-only is enforced by withholding write grants below, not by a role attribute,
    -- because PostgreSQL has no read-only role attribute.
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'integrity_verifier') THEN
        CREATE ROLE integrity_verifier NOLOGIN BYPASSRLS NOSUPERUSER;
    END IF;

    -- offboarding_executor: tenant destruction under SEC-TEN-041. BYPASSRLS, dual-controlled.
    -- Dual control is a platform procedure (ADR-052 records it as a gap the provider does not
    -- supply); this role is the second gate, not the whole control.
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'offboarding_executor') THEN
        CREATE ROLE offboarding_executor NOLOGIN BYPASSRLS NOSUPERUSER;
    END IF;

    -- payload_eraser: holds the only DELETE grant on audit_event_payload (DOC-04 section 20.1).
    -- Separate from offboarding_executor because erasure under PRD-AUD-009 is a routine,
    -- narrowly-scoped obligation while offboarding destroys a tenant; one credential for both
    -- would mean routine erasure work carries tenant-destruction authority.
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payload_eraser') THEN
        CREATE ROLE payload_eraser NOLOGIN BYPASSRLS NOSUPERUSER;
    END IF;
END
$$;

-- -----------------------------------------------------------------------------
-- 2. current_tenant_id() — CON-DAT-013
--
-- Raises where the session tenant is unset and never returns null. DOC-04 section 7.1 states
-- the reason: a null makes the policy predicate `tenant_id = NULL`, which is never true, so a
-- missing context returns an empty result SILENTLY — indistinguishable from legitimately empty
-- data. A raise makes the missing context a visible error, per SEC-TEN-005.
--
-- STABLE, not IMMUTABLE: the value varies within a transaction boundary as SET LOCAL applies.
-- Marking it IMMUTABLE would license the planner to fold it, which is a correctness bug in a
-- security predicate.
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION current_tenant_id() RETURNS uuid
    LANGUAGE plpgsql
    STABLE
    -- No SECURITY DEFINER: this function must run with the caller's rights so that a policy
    -- referencing it cannot become a privilege escalation vector.
AS $$
DECLARE
    raw text;
BEGIN
    raw := current_setting('aspm.current_tenant', true);

    IF raw IS NULL OR raw = '' THEN
        RAISE EXCEPTION
            'no tenant context established for this session (CON-DAT-013, SEC-TEN-005)'
            USING
                ERRCODE = 'insufficient_privilege',
                HINT = 'The application must SET LOCAL aspm.current_tenant inside the '
                       'transaction, from an established TenantContext. It is never derived '
                       'from a request parameter, header, path segment or body field '
                       '(SEC-TEN-004).';
    END IF;

    RETURN raw::uuid;
EXCEPTION
    WHEN invalid_text_representation THEN
        -- A malformed setting is a malfunction, not an empty result set.
        RAISE EXCEPTION 'session tenant setting is not a uuid (CON-DAT-013)'
            USING ERRCODE = 'insufficient_privilege';
END
$$;

REVOKE ALL ON FUNCTION current_tenant_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION current_tenant_id()
    TO app_runtime, migration_runner, integrity_verifier, offboarding_executor, payload_eraser;

-- -----------------------------------------------------------------------------
-- 3. tenant — DOC-04 section 11.1.1
--
-- NOT tenant-scoped: it defines tenants, so it carries no row-level policy. Per DOC-04.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tenant (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    display_name        text        NOT NULL,
    lifecycle_state     text        NOT NULL,
    residency_region    text        NOT NULL,                        -- SEC-TEN-018
    hierarchy_version   bigint      NOT NULL DEFAULT 1,              -- INV-TEN-03, monotonic
    key_reference       text        NOT NULL,                        -- reference, never the key
    entitlement_tier    text        NOT NULL,                        -- LIC-PLT-003
    established_at      timestamptz NOT NULL DEFAULT now(),
    offboarded_at       timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint      NOT NULL DEFAULT 1,

    CONSTRAINT ck_tenant__lifecycle CHECK (lifecycle_state IN
        ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'OFFBOARDING', 'OFFBOARDED')),
    CONSTRAINT ck_tenant__hierarchy_version CHECK (hierarchy_version >= 1),
    -- An offboarded tenant without a timestamp is an incomplete offboarding and DOC-04
    -- section 11.1.1 requires that it not be representable.
    CONSTRAINT ck_tenant__offboarded_timestamp CHECK
        (lifecycle_state <> 'OFFBOARDED' OR offboarded_at IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_tenant__display_name ON tenant (display_name);
CREATE INDEX IF NOT EXISTS ix_tenant__lifecycle_residency
    ON tenant (lifecycle_state, residency_region);

-- The application needs its own tenant row and no other. DOC-04 section 11.1.1 specifies no
-- policy on this table, so a table-wide SELECT grant to app_runtime would expose every
-- tenant's display name and residency. This view is how the application reaches its own row.
-- Additive to DOC-04, not a departure from it: the table shape and the absence of a policy are
-- unchanged.
CREATE OR REPLACE VIEW tenant_self
    WITH (security_invoker = true)
AS SELECT * FROM tenant WHERE id = current_tenant_id();

GRANT SELECT ON tenant_self TO app_runtime;
GRANT SELECT, INSERT, UPDATE ON tenant TO migration_runner;
GRANT SELECT ON tenant TO integrity_verifier;
GRANT SELECT, UPDATE, DELETE ON tenant TO offboarding_executor;

-- -----------------------------------------------------------------------------
-- 4. tenant_id_reservation — DOC-04 section 11.1.2, SEC-TEN-043
--
-- Every tenant identifier ever issued, including offboarded ones. Exists because relying on
-- the tenant row surviving is fragile: a future cleanup, a restore from an older backup, or an
-- offboarding that removes the row would permit reuse. Append-only makes the prohibition
-- structural.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tenant_id_reservation (
    tenant_id   uuid        PRIMARY KEY,     -- never deleted, ever
    reserved_at timestamptz NOT NULL DEFAULT now()
);

-- No DELETE grant to any role, including offboarding_executor. The reservation outliving the
-- tenant is the entire point.
GRANT SELECT, INSERT ON tenant_id_reservation TO migration_runner, offboarding_executor;
GRANT SELECT ON tenant_id_reservation TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 5. The enforcement pattern, as a function so it cannot be applied inconsistently
--
-- DOC-04 section 7.1 gives the pattern. Applying it by hand per table is how one table ends up
-- without FORCE, and a table without FORCE is a silent hole that no query fails on. Wrapping it
-- means a new tenant-scoped table gets all four statements or none.
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION apply_tenant_isolation(target regclass) RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    policy_name text := format('%s_tenant_isolation', target::text);
    partition regclass;
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
    -- FORCE applies the policy to the table owner too. Without it the owner bypasses the
    -- policy, and the application connects as a role that may be the owner (DOC-04 7.1).
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);

    IF NOT EXISTS (
        SELECT 1 FROM pg_policy p
        WHERE p.polrelid = target AND p.polname = policy_name
    ) THEN
        -- USING filters reads; WITH CHECK rejects writes carrying a foreign tenant_id.
        -- Omitting the latter permits a cross-tenant WRITE, which CON-DAT-012 identifies as a
        -- corruption rather than a disclosure and harder to detect than a read leak.
        EXECUTE format(
            'CREATE POLICY %I ON %s USING (tenant_id = current_tenant_id()) '
            'WITH CHECK (tenant_id = current_tenant_id())',
            policy_name, target);
    END IF;

    -- *** DEFECT FOUND BY RUNNING THE VERIFICATION SUITE. ***
    -- Enabling RLS on a PARTITIONED parent protects queries routed through the parent. It does NOT
    -- protect a query issued directly against a partition: a partition is a table, and only its own
    -- policies apply to it. `SELECT * FROM audit_event_2026_08` would have returned every tenant's
    -- rows.
    --
    -- This was invisible until tenant_isolation_gaps() ran against a live engine, and it is exactly
    -- the shape DOC-24 section 5.1 warns about — the isolation looks enforced because the parent is
    -- enforced. Recursing over partitions closes it, and the conformance query already inspected
    -- partitions, which is why it caught this.
    FOR partition IN
        SELECT c.oid::regclass
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
         WHERE i.inhparent = target
    LOOP
        PERFORM apply_tenant_isolation(partition);
    END LOOP;
END
$$;

REVOKE ALL ON FUNCTION apply_tenant_isolation(regclass) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION apply_tenant_isolation(regclass) TO migration_runner;

-- -----------------------------------------------------------------------------
-- 6. The conformance assertion CON-DAT-012 needs
--
-- Returns every tenant-scoped table lacking enabled-and-forced row-level security or lacking
-- either policy clause. A table added without the pattern is a silent hole; this function is
-- what makes it a failing test instead. Called by the verification suite and intended to run
-- after every migration alongside OPS-DEP-031's cross-tenant assertion.
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION tenant_isolation_gaps()
    RETURNS TABLE (table_name text, gap text)
    LANGUAGE sql
    STABLE
AS $$
    SELECT c.relname::text,
           CASE
               WHEN NOT c.relrowsecurity     THEN 'row level security not enabled'
               WHEN NOT c.relforcerowsecurity THEN 'row level security not FORCED (owner bypasses)'
               WHEN NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid)
                                              THEN 'no policy'
               WHEN NOT EXISTS (SELECT 1 FROM pg_policy p
                                WHERE p.polrelid = c.oid AND p.polqual IS NOT NULL)
                                              THEN 'no USING clause (reads unfiltered)'
               WHEN NOT EXISTS (SELECT 1 FROM pg_policy p
                                WHERE p.polrelid = c.oid AND p.polwithcheck IS NOT NULL)
                                              THEN 'no WITH CHECK clause (cross-tenant write possible)'
           END
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND a.attnum > 0
     WHERE n.nspname = current_schema()
       AND c.relkind IN ('r', 'p')            -- ordinary and partitioned tables
       AND c.relname <> 'tenant_id_reservation'
       AND (NOT c.relrowsecurity
            OR NOT c.relforcerowsecurity
            OR NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid)
            OR NOT EXISTS (SELECT 1 FROM pg_policy p
                           WHERE p.polrelid = c.oid AND p.polqual IS NOT NULL)
            OR NOT EXISTS (SELECT 1 FROM pg_policy p
                           WHERE p.polrelid = c.oid AND p.polwithcheck IS NOT NULL));
$$;

GRANT EXECUTE ON FUNCTION tenant_isolation_gaps()
    TO migration_runner, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 7. The scope descriptor immutability trigger function
--
-- Defined here, with apply_tenant_isolation, rather than in the organization-scope migration where it
-- was first written. Two reasons, the second found by running the suite:
--
--   1. It is an ENFORCEMENT PRIMITIVE, like apply_tenant_isolation, and the scope descriptor is a
--      shared-kernel concept rather than an organization-scope one. Every scope-bearing table in every
--      module needs it.
--   2. audit_event declares scope columns in V002, which runs BEFORE the organization-scope migration.
--      A function defined later cannot be used earlier, and the ordering failure surfaced only when the
--      migrations were actually applied in sequence against a live engine.
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION reject_scope_descriptor_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.scope_node_id        IS DISTINCT FROM OLD.scope_node_id
    OR NEW.scope_ancestor_path  IS DISTINCT FROM OLD.scope_ancestor_path
    OR NEW.scope_node_type_id   IS DISTINCT FROM OLD.scope_node_type_id
    OR NEW.scope_criticality_id IS DISTINCT FROM OLD.scope_criticality_id
    OR NEW.scope_hierarchy_ver  IS DISTINCT FROM OLD.scope_hierarchy_ver
    OR NEW.scope_resolved_at    IS DISTINCT FROM OLD.scope_resolved_at THEN
        RAISE EXCEPTION
            'scope descriptor columns are immutable after insert on % (CON-DAT-009, PRD-WRK-042). '
            'They record the scope as it WAS, which is what makes historical reporting reproducible. '
            'Reorganization must not modify them.', TG_TABLE_NAME
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;
