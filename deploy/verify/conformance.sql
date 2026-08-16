-- ==============================================================================================
-- Post-deployment conformance, run as integrity_verifier against the DEPLOYED schema.
--
-- This is the verification job of DOC-15 section 5.1 — the role's stated purpose, and the reason
-- integrity_verifier is BYPASSRLS and read-only: the cross-tenant assertion of SEC-TEN-047 has to
-- see across tenants to assert that nothing else can.
--
-- WHAT THIS IS NOT. It is not the test suite. The suite in :kernel-verification creates and drops
-- schema and switches between all four roles, which requires authority no deployed environment
-- should hand to a running job — an earlier version of this compose file pointed the suite at the
-- deployed database and it failed correctly, because integrity_verifier cannot create a table.
-- The suite belongs to the build. This belongs to the deployment, and the two ask different
-- questions: the suite asks whether the schema is correct, this asks whether the schema that got
-- deployed is the one that was verified.
--
-- Every check RAISES on failure. A conformance job that prints a warning and exits zero is a
-- conformance job nobody reads (OPS-DEP-026: a warning is a violation with extra steps).
-- ==============================================================================================

\set ON_ERROR_STOP on
\timing off

-- ----------------------------------------------------------------------------------------------
-- 1. Native uuidv7, and that it is native.
--
-- ADR-049 sets PostgreSQL 18 as the floor because uuidv7() is native from 18. The build verifies
-- everything else against an embedded 17.5 with a documented plpgsql shim, so THIS is the one
-- claim the build cannot make: that the function is the engine's, and that it is time-ordered.
--
-- The check is not "does uuidv7() exist" — the shim would satisfy that, and applying the shim to a
-- real 18 would silently replace the engine's implementation with a weaker one. prokind and
-- prolang distinguish them: the native function is internal, the shim is plpgsql.
-- ----------------------------------------------------------------------------------------------
DO $$
DECLARE
    lang     text;
    previous uuid;
    current  uuid;
    ordered  boolean := true;
BEGIN
    SELECT l.lanname INTO lang
      FROM pg_proc p JOIN pg_language l ON l.oid = p.prolang
     WHERE p.proname = 'uuidv7' AND p.pronargs = 0;

    IF lang IS NULL THEN
        RAISE EXCEPTION 'uuidv7() is absent. Every primary key default in the schema depends on it '
                        '(CON-DAT-006), so this database cannot accept a single insert.';
    END IF;
    IF lang <> 'internal' THEN
        RAISE EXCEPTION 'uuidv7() is implemented in % rather than being native. The test-only shim '
                        'has been applied to a real PostgreSQL 18, which trades the engine''s '
                        'monotonicity guarantee for the shim''s weaker one — permanently, and '
                        'invisibly to every reader of the schema.', lang;
    END IF;

    -- Time-ordering, which is the property ADR-031 needs and the property the shim only
    -- approximates. Sampled rather than proven: a thousand draws that are ordered do not prove
    -- monotonicity under concurrency, and this states what it checked rather than implying more.
    previous := uuidv7();
    FOR i IN 1..1000 LOOP
        current := uuidv7();
        IF current <= previous THEN
            ordered := false;
            EXIT;
        END IF;
        previous := current;
    END LOOP;

    IF NOT ordered THEN
        RAISE EXCEPTION 'uuidv7() produced a non-increasing sequence within one session. '
                        'Time-ordered primary keys are what keep index inserts at the right-hand '
                        'edge of the btree (ADR-031); without it every insert is a random-page '
                        'write on the largest tables in the platform.';
    END IF;

    RAISE NOTICE 'uuidv7: native (%), 1000 sequential draws strictly increasing', lang;
END
$$;

-- ----------------------------------------------------------------------------------------------
-- 2. Tenant isolation, over every table — the platform's highest-severity property.
--
-- tenant_isolation_gaps() ships with the schema. Running it here rather than trusting that it was
-- run at migration time is the point: OPS-DEP-031 requires a cross-tenant assertion AFTER every
-- migration, "because migrations run with row-level enforcement bypassed and are the highest-risk
-- operation in the platform".
-- ----------------------------------------------------------------------------------------------
DO $$
DECLARE
    gaps text;
    n    int;
BEGIN
    SELECT count(*), string_agg(g::text, ', ') INTO n, gaps FROM tenant_isolation_gaps() g;
    IF n > 0 THEN
        RAISE EXCEPTION 'tenant_isolation_gaps() reported % gap(s): %. One customer''s '
                        'vulnerability inventory readable by another is unrecoverable and '
                        'disclosable (DOC-26 T2). Contain before diagnosing.', n, gaps;
    END IF;
    RAISE NOTICE 'tenant isolation: no gaps';
END
$$;

-- ----------------------------------------------------------------------------------------------
-- 3. Forced row-level security, with the three documented exceptions named.
--
-- Named rather than filtered by a predicate. tenant_id_reservation HAS a tenant_id column — there
-- it is the subject of the row, not its scope — so any predicate clever enough to exclude it
-- excludes the next table for the wrong reason and reports nothing.
-- ----------------------------------------------------------------------------------------------
DO $$
DECLARE
    unforced text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO unforced
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind = 'r' AND n.nspname = 'public'
       AND NOT c.relforcerowsecurity
       AND c.relname NOT IN ('tenant', 'tenant_id_reservation', 'hash_partition_basis');

    IF unforced IS NOT NULL THEN
        RAISE EXCEPTION 'row-level security is not FORCEd on: %. FORCE is what applies the policy '
                        'to the table OWNER as well, and the owner here is the migration role '
                        '(CON-DAT-012, SEC-TEN-008).', unforced;
    END IF;
    RAISE NOTICE 'forced row-level security: every table except the three documented exceptions';
END
$$;

-- ----------------------------------------------------------------------------------------------
-- 4. Partition runway (OPS-DEP-011) and the recorded sizing basis (OPS-DEP-012).
--
-- Runway is a warning here rather than a failure: a freshly provisioned database legitimately sits
-- at the lead time. What fails is a table with NO runway at all, which means provisioning never
-- ran for it and the first insert past the current period is rejected.
-- ----------------------------------------------------------------------------------------------
DO $$
DECLARE
    starved text;
    warned  text;
BEGIN
    SELECT string_agg(parent_table, ', ' ORDER BY parent_table) INTO starved
      FROM partition_runway_report() WHERE runway_months = 0;
    IF starved IS NOT NULL THEN
        RAISE EXCEPTION 'no future partitions provisioned for: %. A missing partition rejects '
                        'inserts, and for audit_event that fails every audited operation under '
                        'CON-PLT-021 — a total write outage from an omitted maintenance task.',
                        starved;
    END IF;

    SELECT string_agg(parent_table || ' (' || runway_months || 'mo)', ', ' ORDER BY parent_table)
      INTO warned FROM partition_runway_report() WHERE alerting;
    IF warned IS NOT NULL THEN
        RAISE NOTICE 'partition runway below the lead time: % — provision before it is required',
                     warned;
    ELSE
        RAISE NOTICE 'partition runway: every range-partitioned table above the lead time';
    END IF;
END
$$;

DO $$
DECLARE
    drifted text;
    missing int;
BEGIN
    SELECT string_agg(a.table_name, ', ') INTO drifted
      FROM hash_partition_counts() a
      JOIN hash_partition_basis b ON b.table_name = a.table_name
     WHERE a.partition_count <> b.partition_count;
    IF drifted IS NOT NULL THEN
        RAISE EXCEPTION 'the recorded sizing basis and the deployed partition count disagree for: '
                        '%. The record is consulted during a resize decision, and a wrong one is '
                        'worse than none (OPS-DEP-012).', drifted;
    END IF;

    SELECT count(*) INTO missing
      FROM hash_partition_counts() a
     WHERE NOT EXISTS (SELECT 1 FROM hash_partition_basis b WHERE b.table_name = a.table_name);
    IF missing > 0 THEN
        RAISE EXCEPTION '% hash-partitioned table(s) carry no recorded sizing basis. Changing a '
                        'hash partition count redistributes every row (CON-DAT-035), so the basis '
                        'is what a later resize needs to tell whether the assumption or the growth '
                        'was wrong.', missing;
    END IF;
    RAISE NOTICE 'hash partitioning: counts agree with the recorded basis';
END
$$;

-- ----------------------------------------------------------------------------------------------
-- 5. The bypass credentials have no login. OPS-DEP-009, checked against the cluster rather than
--    against the compose file that was supposed to arrange it.
-- ----------------------------------------------------------------------------------------------
DO $$
DECLARE
    reachable text;
BEGIN
    SELECT string_agg(rolname, ', ' ORDER BY rolname) INTO reachable
      FROM pg_roles
     WHERE rolbypassrls AND rolcanlogin AND NOT rolsuper;
    IF reachable IS NOT NULL THEN
        RAISE EXCEPTION 'these roles bypass row-level security AND can log in directly: %. '
                        'Credential separation is what makes bypass unreachability structural '
                        'rather than procedural (OPS-DEP-009); a login on the group role removes '
                        'the separation.', reachable;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'offboarding_executor' AND rolcanlogin) THEN
        RAISE EXCEPTION 'offboarding_executor can log in. It is the mechanism of cryptographic '
                        'erasure at tenant offboarding and also the mechanism an insider would use '
                        'to destroy evidence; it is dual-control gated (OPS-DEP-022).';
    END IF;
    RAISE NOTICE 'credentials: no bypass role is directly loginable';
END
$$;

\echo ''
\echo 'CONFORMANCE PASSED — the deployed schema matches what the build verified.'
