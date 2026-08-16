#!/bin/sh
# ==============================================================================================
# Applies V001 to V013 in version order, as migration_runner.
#
# This is the migration pipeline of DOC-15 section 9.1 reduced to its one essential step. What a
# real pipeline adds and this does not: the expand-migrate-contract check of CON-DAT-033, the
# blocking-operation-on-a-large-table check, and the cross-tenant assertion OPS-DEP-031 requires
# after every migration before the release is considered complete. Named here so their absence is
# a known gap rather than an assumed feature.
#
# ON_ERROR_STOP is set. Without it psql reports a failed statement and continues, which produces a
# partially applied schema that looks like a successful run — and a partially applied migration is
# the state the migration-failure runbook exists for.
# ==============================================================================================
set -eu

MIGRATIONS_ROOT=/src

echo "== discovering migrations under ${MIGRATIONS_ROOT}"

# Ordered by FILENAME, not by path. `find | sort` sorts the whole path, which put
# module/assessment-impl/...V010 ahead of platform-kernel/tenant-context-impl/...V001 and failed
# on the first call to apply_tenant_isolation() — a function V001 had not created yet. The version
# prefix is the ordering the migrations declare; the directory a module happens to live in is not.
#
# So: prepend the basename, sort on that, strip it. The awk field separator is '/', so $NF is the
# filename.
#
# Build output is excluded: a stale copy under build/ would be applied twice, and CREATE ...
# IF NOT EXISTS makes the second application silent rather than loud.
FILES=$(find "${MIGRATIONS_ROOT}" -path '*/src/main/resources/db/migration/V*.sql' \
        -not -path '*/build/*' \
        | awk -F/ '{ print $NF "\t" $0 }' | sort | cut -f2)

if [ -z "${FILES}" ]; then
    echo "no migrations found under ${MIGRATIONS_ROOT}. The repository is mounted at /src;" >&2
    echo "check the volume in docker-compose.yml before assuming an empty schema is correct." >&2
    exit 1
fi

COUNT=$(echo "${FILES}" | wc -l | tr -d ' ')
echo "== ${COUNT} migration(s) to apply"

# The test-only uuidv7 shim lives under src/test/resources and is therefore not matched by the
# path above. That is intentional and worth stating: PostgreSQL 18 has uuidv7() natively, and
# applying the shim would REPLACE the native function with a plpgsql one — silently trading the
# engine's monotonicity guarantee for the shim's weaker one, in a deployment, forever.

for file in ${FILES}; do
    name=$(basename "${file}")
    printf '   %-52s' "${name}"
    if psql -v ON_ERROR_STOP=1 --quiet --no-psqlrc -f "${file}" > /tmp/out.log 2>&1; then
        echo "ok"
    else
        echo "FAILED"
        echo
        echo "-- psql output ------------------------------------------------------------"
        cat /tmp/out.log
        echo "---------------------------------------------------------------------------"
        echo
        echo "The schema is now PARTIALLY APPLIED. Do not retry blindly: determine what applied."
        echo "Expand-migrate-contract means a failed expand is safe to retry and a failed contract"
        echo "is not, and the two are distinguished by which step failed rather than by the error."
        echo "See the migration-failure runbook (Runbook.java, DOC-15 section 15)."
        exit 1
    fi
done

echo
echo "== post-conditions"

psql -v ON_ERROR_STOP=1 --quiet --no-psqlrc --tuples-only <<'SQL'
\echo '-- tables with row-level security FORCEd (CON-DAT-012, SEC-TEN-008):'
SELECT '   ' || count(*)::text || ' of ' ||
       (SELECT count(*)::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE c.relkind = 'r' AND n.nspname = 'public')
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'r' AND n.nspname = 'public' AND c.relforcerowsecurity;

\echo '-- the three tables deliberately outside row-level security, each with its reason:'
\echo '     tenant                 registry of tenants. app_runtime is granted the tenant_self'
\echo '                            VIEW and never the table, so the control is the grant.'
\echo '     tenant_id_reservation  every tenant id ever issued, INCLUDING offboarded ones'
\echo '                            (SEC-TEN-043). Scoping it per tenant would defeat it: its'
\echo '                            purpose is to span tenants so an id cannot be reused.'
\echo '     hash_partition_basis   partition counts are a deployment property, not tenant data.'

\echo '-- any OTHER table without forced row-level security (must be empty):'
--
-- The three above are named rather than filtered by a predicate such as "has no tenant_id
-- column", because tenant_id_reservation HAS one — there it is the subject of the row, not the
-- scope of it. A predicate that clever excludes the next table for the wrong reason and reports
-- nothing; an explicit list makes an undocumented exception fail here.
SELECT '   ' || c.relname
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'r' AND n.nspname = 'public'
   AND NOT c.relforcerowsecurity
   AND c.relname NOT IN ('tenant', 'tenant_id_reservation', 'hash_partition_basis')
 ORDER BY c.relname;

\echo '-- range partition runway, in months (OPS-DEP-011; alerting below three):'
SELECT '   ' || parent_table || ': ' || runway_months::text ||
       CASE WHEN alerting THEN '  <-- ALERT' ELSE '' END
  FROM partition_runway_report();

\echo '-- hash partition counts and whether a sizing basis is recorded (OPS-DEP-012):'
SELECT '   ' || b.table_name || ': ' || b.partition_count::text ||
       ' (basis recorded, ' || b.open_question || ')'
  FROM hash_partition_basis b ORDER BY b.table_name;
SQL

echo
echo "== schema applied. This is the DATA TIER only — there is no application tier to start."
echo "   See deploy/README.md for what exists and what does not."
