-- =============================================================================================
-- V065 — a foreign key between two tenant-scoped tables carries the tenant.
--
-- WHAT WAS WRONG, AND HOW IT WAS FOUND. Measured on 2026-08-16 against this deployment: a normal
-- API call created an `org_node` belonging to tenant 1111… whose `type_id` referenced an
-- `org_node_type` belonging to tenant 2222…, and it was accepted.
--
--     node_tenant = 1111…    type_tenant = 2222…    code = TENANT_B_ONLY
--
-- WHY ROW-LEVEL SECURITY DID NOT STOP IT. It cannot. PostgreSQL enforces referential integrity
-- with internal triggers that run as the referenced table's owner with row security disabled — if
-- they did not, a policy that hid a parent row would turn every insert into a spurious violation
-- and the constraint would depend on who was looking. So the check that decides whether the parent
-- row EXISTS is exactly the one place in the engine where the tenant policy is not applied. Every
-- read stays filtered, which is why this leaks no data; what it breaks is the assumption underneath
-- ADR-002 that a tenant is a hard boundary, and it breaks it on the write path.
--
-- WHAT IT COST BEFORE THIS FIX. Three things, none of them "tenant B's data was read":
--   1. Integrity. The node above is invisible on its OWN tenant's organization page, because that
--      query joins the node type and the join finds nothing. The row exists, holds a place in the
--      closure, and cannot be seen or corrected by the people who own it.
--   2. An existence oracle. A caller learns whether a UUID is a real identifier in some other
--      tenant by whether the write is accepted.
--   3. A cross-tenant denial of service. `ON DELETE RESTRICT` means tenant B can no longer retire
--      its own catalogue row, because a tenant it cannot see is referencing it.
--
-- WHY A COMPOSITE FOREIGN KEY RATHER THAN A CHECK IN THE APPLICATION. DOC-26 section 13.2 puts
-- code review — and by the same argument an application-layer check — in the weaker class of
-- control: it holds until the next endpoint forgets it, and the next endpoint is written by
-- somebody who does not know this note exists. `(tenant_id, x_id) REFERENCES parent (tenant_id, id)`
-- makes the tenant part of the identity the engine matches on, so a cross-tenant reference is not
-- refused, it is unrepresentable.
--
-- WHY ALL OF THEM AND NOT THE FEW AN ENDPOINT ACCEPTS TODAY. Because that set is a moving target:
-- the identifiers a client can supply grow with every endpoint, and a migration that converted only
-- today's would leave the next one to be noticed by a reviewer who would have to know to look. This
-- statement is declarative and re-runs on every deploy — a single-column foreign key added between
-- two tenant-scoped tables next month is converted the next time the migrations run.
--
-- WHAT THIS DOES NOT FIX. A reference to a table with no `tenant_id` is out of scope here, and
-- correctly so — a product-fixed catalogue such as the permission list is shared by construction.
-- Nor does it constrain a reference held in a polymorphic column with no foreign key at all
-- (`comment.subject_id` is the example); those are bounded by the domain layer under ADR-030, and
-- the reconciliation that ADR-030 requires is where they have to be caught.
--
-- MEASURED BEFORE APPLYING: 139 single-column foreign keys between tenant-scoped tables, 30 distinct
-- parents, all ordinary tables referencing `id`, and ZERO rows already violating the property.
-- =============================================================================================

DO $$
DECLARE
    reference   record;
    parent      record;
    new_name    text;
    unique_name text;
    on_delete   text;
    on_update   text;
    converted   int := 0;
    skipped     int := 0;
    validated   int := 0;
    -- Whether THIS role can see across tenants, which decides whether the constraints can be
    -- validated here. See the note at the VALIDATE below.
    may_validate boolean := (SELECT bool_or(rolsuper OR rolbypassrls)
                               FROM pg_roles WHERE rolname = current_user);
BEGIN
    -- -------------------------------------------------------------------------------------------
    -- Step 1: every parent gets UNIQUE (tenant_id, id).
    --
    -- A foreign key must reference a unique constraint covering exactly its referenced columns, and
    -- the primary key on `id` alone does not. This is an ADDITIONAL constraint, not a replacement:
    -- `id` stays globally unique, which keeps every existing reference, index and join valid and
    -- keeps identifiers non-reusable across tenants (SEC-TEN-043's reasoning, one level down).
    -- -------------------------------------------------------------------------------------------
    FOR parent IN
        SELECT DISTINCT pa.oid, pa.relname, af.attname AS ref_col
          FROM pg_constraint con
          JOIN pg_class pa ON pa.oid = con.confrelid
          JOIN pg_attribute af ON af.attrelid = con.confrelid AND af.attnum = con.confkey[1]
         WHERE con.contype = 'f'
           AND array_length(con.conkey, 1) = 1
           -- Declared on the table, not copied down onto a partition. `finding` is hash
           -- partitioned, and PostgreSQL records a child copy of every constraint on each
           -- partition; the copy cannot be dropped there — "constraint ... of relation finding_p0
           -- does not exist" is what that looks like — because it belongs to the parent. Convert
           -- the parent and the partitions follow.
           AND con.conparentid = 0
           AND NOT (SELECT relispartition FROM pg_class WHERE oid = con.conrelid)
           AND EXISTS (SELECT 1 FROM pg_attribute t WHERE t.attrelid = con.conrelid
                        AND t.attname = 'tenant_id' AND t.attnum > 0 AND NOT t.attisdropped)
           AND EXISTS (SELECT 1 FROM pg_attribute t WHERE t.attrelid = con.confrelid
                        AND t.attname = 'tenant_id' AND t.attnum > 0 AND NOT t.attisdropped)
    LOOP
        unique_name := left('uq_' || parent.relname || '__tenant_' || parent.ref_col, 63);
        IF NOT EXISTS (SELECT 1 FROM pg_constraint u
                        WHERE u.conrelid = parent.oid AND u.contype IN ('p', 'u')
                          AND u.conname = unique_name) THEN
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I UNIQUE (tenant_id, %I)',
                           parent.relname, unique_name, parent.ref_col);
        END IF;
    END LOOP;

    -- -------------------------------------------------------------------------------------------
    -- Step 2: replace each single-column foreign key with the composite form.
    --
    -- Added NOT VALID first and validated afterwards, which is the expand-migrate-contract shape
    -- CON-DAT-033 asks for: NOT VALID takes a brief lock and no scan, VALIDATE takes a weaker lock
    -- and scans. On this deployment either would be instant; the pattern is here because the
    -- estate this platform is built for is not this deployment, and a migration that is only
    -- correct at small scale is a migration that gets reverted in production at 3am.
    --
    -- The old constraint is dropped LAST. An interrupted run therefore leaves both — which is
    -- redundant and harmless — rather than neither, which is the window this is ordered to avoid.
    -- -------------------------------------------------------------------------------------------
    FOR reference IN
        SELECT con.oid, con.conname, con.confdeltype, con.confupdtype,
               ch.relname AS child, pa.relname AS parent,
               a.attname AS child_col, af.attname AS parent_col,
               a.attnotnull AS child_col_not_null,
               (SELECT t.attnotnull FROM pg_attribute t
                 WHERE t.attrelid = con.conrelid AND t.attname = 'tenant_id') AS tenant_not_null
          FROM pg_constraint con
          JOIN pg_class ch ON ch.oid = con.conrelid
          JOIN pg_class pa ON pa.oid = con.confrelid
          JOIN pg_attribute a  ON a.attrelid  = con.conrelid  AND a.attnum  = con.conkey[1]
          JOIN pg_attribute af ON af.attrelid = con.confrelid AND af.attnum = con.confkey[1]
         WHERE con.contype = 'f'
           AND array_length(con.conkey, 1) = 1
           -- Declared on the table, not copied down onto a partition. `finding` is hash
           -- partitioned, and PostgreSQL records a child copy of every constraint on each
           -- partition; the copy cannot be dropped there — "constraint ... of relation finding_p0
           -- does not exist" is what that looks like — because it belongs to the parent. Convert
           -- the parent and the partitions follow.
           AND con.conparentid = 0
           AND NOT (SELECT relispartition FROM pg_class WHERE oid = con.conrelid)
           AND EXISTS (SELECT 1 FROM pg_attribute t WHERE t.attrelid = con.conrelid
                        AND t.attname = 'tenant_id' AND t.attnum > 0 AND NOT t.attisdropped)
           AND EXISTS (SELECT 1 FROM pg_attribute t WHERE t.attrelid = con.confrelid
                        AND t.attname = 'tenant_id' AND t.attnum > 0 AND NOT t.attisdropped)
    LOOP
        -- Idempotent by name. The migrate service re-applies every file on every start, so this
        -- loop meets its own output from the last deploy and has to recognise it.
        new_name := left('fk_' || reference.child || '__' || reference.child_col || '__tenant', 63);
        -- Already converted on an earlier deploy. Step 3 below decides whether this run can also
        -- validate it; the enumeration here only ever sees constraints still in the old shape.
        CONTINUE WHEN EXISTS (SELECT 1 FROM pg_constraint c2
                               WHERE c2.conname = new_name
                                 AND c2.conrelid = (SELECT oid FROM pg_class
                                                     WHERE relname = reference.child LIMIT 1));

        -- A nullable tenant on the child would make the composite key MATCH SIMPLE-satisfiable with
        -- a NULL tenant, which is weaker than the constraint being replaced. Refused loudly rather
        -- than converted quietly: a table in that shape is a tenancy defect of its own and wants a
        -- person, not a silent skip.
        IF NOT reference.tenant_not_null THEN
            RAISE WARNING 'SKIPPED %.% -> %: tenant_id is nullable on %, so a composite key would '
                          'not be enforced for rows with no tenant. Fix the column, then re-run.',
                          reference.child, reference.child_col, reference.parent, reference.child;
            skipped := skipped + 1;
            CONTINUE;
        END IF;

        on_delete := CASE reference.confdeltype
                         WHEN 'a' THEN '' WHEN 'r' THEN ' ON DELETE RESTRICT'
                         WHEN 'c' THEN ' ON DELETE CASCADE' WHEN 'n' THEN ' ON DELETE SET NULL'
                         WHEN 'd' THEN ' ON DELETE SET DEFAULT' ELSE '' END;
        on_update := CASE reference.confupdtype
                         WHEN 'a' THEN '' WHEN 'r' THEN ' ON UPDATE RESTRICT'
                         WHEN 'c' THEN ' ON UPDATE CASCADE' WHEN 'n' THEN ' ON UPDATE SET NULL'
                         WHEN 'd' THEN ' ON UPDATE SET DEFAULT' ELSE '' END;

        EXECUTE format(
            'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (tenant_id, %I) '
            || 'REFERENCES %I (tenant_id, %I)%s%s NOT VALID',
            reference.child, new_name, reference.child_col,
            reference.parent, reference.parent_col, on_delete, on_update);

        -- *** WHY THE VALIDATION IS CONDITIONAL, AND WHY THAT IS NOT A WEAKENING. ***
        --
        -- `VALIDATE CONSTRAINT` scans the child table through the row-level policies of the
        -- scanning role. Migrations run as `aspm_migrate`, which is granted `migration_runner` and
        -- does NOT bypass row security — BYPASSRLS is a role attribute and membership does not
        -- inherit it — so the scan calls `current_tenant_id()`, finds no tenant established, and
        -- fails closed. That is SEC-TEN-005 doing exactly its job, and the first run of this
        -- migration failed on it.
        --
        -- The interesting part is that it CANNOT be worked around by establishing a tenant: a scan
        -- under one tenant's policy sees one tenant's rows, so PostgreSQL would mark the constraint
        -- validated on the strength of a partial scan. A role confined to one tenant cannot verify
        -- a property ABOUT the boundary between tenants. That is the isolation working, not an
        -- obstacle to it.
        --
        -- So: NOT VALID is left in place when the role cannot see across tenants. A NOT VALID
        -- foreign key is still ENFORCED on every subsequent insert and update — which is the whole
        -- of the defect being closed here, because the defect is a write path. What NOT VALID
        -- withholds is the statement that pre-existing rows conform, and that statement was
        -- obtained separately: a full cross-tenant scan of all 139 references (75 declared, the
        -- rest partition copies) found ZERO violations on 2026-08-16, run as a role that can see
        -- across tenants. An operator re-running this file as such a role validates them properly,
        -- and the conformance job of deploy/verify is where that belongs.
        IF may_validate THEN
            EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I', reference.child, new_name);
        END IF;
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', reference.child, reference.conname);
        converted := converted + 1;
    END LOOP;

    -- -------------------------------------------------------------------------------------------
    -- Step 3: finish the job when this run CAN.
    --
    -- A deploy that ran as the tenant-confined migration role left its constraints NOT VALID —
    -- enforced for every new write, unproven for the rows already there. An operator who re-runs
    -- this file as an administrator should not have to know that a separate command exists to close
    -- that gap, so it is closed here. Nothing is weakened when the role cannot: the loop simply
    -- finds nothing it is allowed to do.
    -- -------------------------------------------------------------------------------------------
    IF may_validate THEN
        FOR reference IN
            SELECT c.conname, ch.relname AS child
              FROM pg_constraint c
              JOIN pg_class ch ON ch.oid = c.conrelid
             WHERE c.contype = 'f' AND NOT c.convalidated AND c.conparentid = 0
               AND c.conname LIKE 'fk\_%\_\_tenant'
        LOOP
            EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I',
                           reference.child, reference.conname);
            validated := validated + 1;
        END LOOP;
    END IF;

    RAISE NOTICE 'tenant-scoped foreign keys: % converted, % skipped, % validated against rows that '
                 'already existed (this role sees across tenants: %)',
                 converted, skipped, validated, may_validate;
END $$;

-- ---------------------------------------------------------------------------------------------
-- The property, asserted where it is cheapest to assert: right here, on every replay.
--
-- A migration that establishes a property and does not check it has established it once. This runs
-- after the conversion above on every deploy, so a single-column foreign key introduced between two
-- tenant-scoped tables fails the migrate service rather than reaching production and being found by
-- somebody probing it.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE remaining int;
BEGIN
    SELECT count(*) INTO remaining
      FROM pg_constraint con
     WHERE con.contype = 'f'
       AND array_length(con.conkey, 1) = 1
       AND con.conparentid = 0
       AND NOT (SELECT relispartition FROM pg_class WHERE oid = con.conrelid)
       AND EXISTS (SELECT 1 FROM pg_attribute t WHERE t.attrelid = con.conrelid
                    AND t.attname = 'tenant_id' AND t.attnum > 0 AND NOT t.attisdropped)
       AND EXISTS (SELECT 1 FROM pg_attribute t WHERE t.attrelid = con.confrelid
                    AND t.attname = 'tenant_id' AND t.attnum > 0 AND NOT t.attisdropped);
    IF remaining > 0 THEN
        RAISE EXCEPTION 'ADR-002: % foreign key(s) between tenant-scoped tables still carry only the '
                        'referenced id, so a row in one tenant can reference a row in another. The '
                        'conversion above should have taken them; a skip is reported as a WARNING '
                        'immediately before this.', remaining;
    END IF;
END $$;
