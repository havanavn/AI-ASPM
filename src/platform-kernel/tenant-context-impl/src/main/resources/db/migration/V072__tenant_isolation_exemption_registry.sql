-- =============================================================================================
-- V072 — the tables deliberately outside row-level security, registered with their reasons.
--
-- WHAT WAS WRONG, AND HOW IT WAS FOUND. The post-deployment conformance job (deploy/verify/
-- conformance.sql, check 3) and the migration post-conditions (deploy/migrate/apply.sh) both name
-- the tables that are allowed to lack a forced row-level policy. They named THREE — `tenant`,
-- `tenant_id_reservation`, `hash_partition_basis` — because three was the count when they were
-- written. V015 and V052 then added four product-fixed reference tables with no tenant column at all
-- (`breached_password`, `permission_catalogue`, `cwe`, `owasp_top10_2025`), each with a COMMENT
-- explaining why it is global, and nobody updated the two lists. Run on 2026-09-11 against a fresh
-- stack, check 3 raised on all four and the job exited 3. The schema was correct; the assertion was
-- stale, and a stale assertion that fires is only the good half of the problem — the same staleness
-- in the other direction would be a table that SHOULD be tenant-scoped passing unnoticed because the
-- list had been widened by hand to make the job go green.
--
-- WHY A TABLE AND NOT A LONGER LITERAL LIST. The previous design was defended in its own comment:
-- "named rather than filtered by a predicate", because `tenant_id_reservation` has a tenant_id column
-- as the SUBJECT of its rows and any predicate clever enough to exclude it excludes the next table
-- for the wrong reason. That argument stands, and this migration keeps it: the exemption is still an
-- explicit, named decision. What changes is WHERE the name lives. It lives beside the table it
-- exempts, written by the migration that creates the table, so a new global table without a
-- registered reason fails conformance — and the two copies of the list that drifted apart are
-- replaced by one place that both readers query. That is product principle 10 applied to an
-- operational control: one name, one meaning, one place.
--
-- WHAT THE REGISTRY ASSERTS ABOUT ITSELF. Every row must name a table that exists (a row for a table
-- that was renamed would silently exempt nothing and hide nothing), and every row must carry a reason
-- long enough to be one — the same `length >= 40` rule `hash_partition_basis` applies to its sizing
-- basis, because "reference data" is a label and not a reason.
--
-- WHAT IT DOES NOT DO. It does not disable, weaken or replace `tenant_isolation_gaps()` from V001,
-- which reports tables that carry a tenant_id column and lack a policy. A table can be in this
-- registry AND flagged by that function — `tenant_id_reservation` is the standing example — and the
-- two questions are different: "is this table allowed to lack a forced policy" versus "does this
-- table look tenant-scoped and lack one". Conformance asks both.
--
-- Requirements: CON-DAT-012, SEC-TEN-008 (forced row-level security on every tenant-scoped table),
-- OPS-DEP-031 (a cross-tenant assertion after every migration), SEC-TEN-043 (the id reservation
-- spans tenants by design). Product principle 10.
-- =============================================================================================

CREATE TABLE IF NOT EXISTS tenant_isolation_exemption (
    table_name      text        PRIMARY KEY,
    -- Why this table is allowed to lack a forced row-level policy. A reason, not a category.
    reason          text        NOT NULL,
    -- The migration that registered it, so a reviewer can find the decision in context.
    registered_by   text        NOT NULL,
    registered_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT tenant_isolation_exemption__reason_substantive
        CHECK (length(btrim(reason)) >= 40),
    CONSTRAINT tenant_isolation_exemption__migration_named
        CHECK (registered_by ~ '^V[0-9]{3}__')
);

COMMENT ON TABLE tenant_isolation_exemption IS
    'CON-DAT-012, SEC-TEN-008, OPS-DEP-031. The tables deliberately outside forced row-level security, '
    'each with the reason it is global. Read by deploy/verify/conformance.sql check 3 and by '
    'deploy/migrate/apply.sh post-conditions, so the two never carry their own copy of the list. '
    'A global table absent from here is a conformance failure, which is the point: an undocumented '
    'exception fails rather than passes. This table is itself global and is its own first row.';

-- Deployment metadata, like hash_partition_basis: readable by everyone who verifies, writable only by
-- the migration role, and never by application code — an application that could exempt a table from
-- isolation would hold the one grant this whole mechanism exists to withhold.
REVOKE ALL ON tenant_isolation_exemption FROM PUBLIC;
GRANT SELECT ON tenant_isolation_exemption TO app_runtime, integrity_verifier, migration_runner;
GRANT INSERT, UPDATE, DELETE ON tenant_isolation_exemption TO migration_runner;

INSERT INTO tenant_isolation_exemption (table_name, reason, registered_by) VALUES
    ('tenant_isolation_exemption',
     'The registry itself. Deployment metadata with no tenant column: it names tables, not rows of '
     'any tenant, and scoping it would hide an exemption from the verifier that must see them all.',
     'V072__tenant_isolation_exemption_registry.sql'),
    ('tenant',
     'The registry of tenants. app_runtime is granted the tenant_self VIEW and never the table, so '
     'the control is the grant rather than a policy (V001).',
     'V072__tenant_isolation_exemption_registry.sql'),
    ('tenant_id_reservation',
     'Every tenant id ever issued, INCLUDING offboarded ones (SEC-TEN-043). Its purpose is to span '
     'tenants so an id cannot be reused; scoping it per tenant would defeat it. Its tenant_id column '
     'is the subject of the row, not the scope of it (V001).',
     'V072__tenant_isolation_exemption_registry.sql'),
    ('hash_partition_basis',
     'Partition counts are a deployment property, not tenant data (OPS-DEP-012). Recorded once per '
     'deployment with the sizing basis that produced them (V013).',
     'V072__tenant_isolation_exemption_registry.sql'),
    ('breached_password',
     'SEC-SEC-006. A breach corpus is public data with no tenant column; per-tenant copies multiply '
     'storage for no isolation benefit and nothing in a row identifies a tenant (V015).',
     'V072__tenant_isolation_exemption_registry.sql'),
    ('permission_catalogue',
     'PRD-AUZ-001, SEC-AUZ-001. The permission catalogue is product-fixed and no tenant may add a '
     'row; ADR-027 makes roles tenant data and the catalogue they draw from product data (V015).',
     'V072__tenant_isolation_exemption_registry.sql'),
    ('cwe',
     'CWE identifiers as published by MITRE. Product-fixed reference data with no tenant column; a '
     'tenant that renamed one would publish a report citing CWE that does not match CWE (V052).',
     'V072__tenant_isolation_exemption_registry.sql'),
    ('owasp_top10_2025',
     'The published OWASP Top 10:2025. Product-fixed reference data with no tenant column and no '
     'UPDATE grant, for the same reason as cwe (V052).',
     'V072__tenant_isolation_exemption_registry.sql')
ON CONFLICT (table_name) DO UPDATE
    SET reason = EXCLUDED.reason, registered_by = EXCLUDED.registered_by;

-- ---------------------------------------------------------------------------------------------
-- The one query both readers run. Returns every ordinary table in `public` that lacks a FORCEd
-- policy and is not registered above. Empty is the only acceptable answer.
--
-- Also returns a row for a REGISTERED table that does not exist, tagged as such: a registry entry
-- for a table that was renamed exempts nothing and would otherwise disappear from every report.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION unregistered_unforced_tables()
    RETURNS TABLE (table_name text, finding text)
    LANGUAGE sql
    STABLE
AS $$
    SELECT c.relname::text,
           'lacks forced row-level security and is not registered in tenant_isolation_exemption'
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind = 'r'
       AND n.nspname = 'public'
       AND NOT c.relforcerowsecurity
       AND c.relname NOT IN (SELECT e.table_name FROM tenant_isolation_exemption e)
    UNION ALL
    SELECT e.table_name,
           'registered in tenant_isolation_exemption but no such table exists'
      FROM tenant_isolation_exemption e
     WHERE NOT EXISTS (
           SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind = 'r' AND n.nspname = 'public' AND c.relname = e.table_name)
     ORDER BY 1
$$;

COMMENT ON FUNCTION unregistered_unforced_tables() IS
    'CON-DAT-012, OPS-DEP-031. Tables without a forced row-level policy that are not registered as '
    'deliberately global, plus registry rows naming a table that no longer exists. Must be empty after '
    'every migration; a non-empty result is a cross-tenant read path or a rotted exemption, not a style '
    'issue.';

GRANT EXECUTE ON FUNCTION unregistered_unforced_tables() TO migration_runner, integrity_verifier;

-- Fail this migration, loudly, if the schema it lands on has an unregistered global table. Better
-- here than in a job somebody runs later — a migration that completes over a gap it could see is a
-- migration that reported success it did not have.
DO $$
DECLARE
    offending text;
BEGIN
    SELECT string_agg(u.table_name || ' (' || u.finding || ')', '; ' ORDER BY u.table_name)
      INTO offending
      FROM unregistered_unforced_tables() u;
    IF offending IS NOT NULL THEN
        RAISE EXCEPTION 'V072: tables outside forced row-level security without a registered reason: %. '
                        'Register each in tenant_isolation_exemption with its reason, or apply '
                        'apply_tenant_isolation() to it. (CON-DAT-012, SEC-TEN-008)', offending;
    END IF;
END
$$;
