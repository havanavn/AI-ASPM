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

\echo '-- the tables deliberately outside row-level security, each with the reason it registered'
\echo '   (tenant_isolation_exemption, V072; a global table missing from here fails below):'
SELECT '     ' || rpad(e.table_name, 28) || left(e.reason, 90) || CASE WHEN length(e.reason) > 90 THEN '…' ELSE '' END
  FROM tenant_isolation_exemption e ORDER BY e.table_name;

\echo '-- any OTHER table without forced row-level security, or a registered table that no longer exists'
\echo '   (must be empty):'
--
-- The exemptions are still NAMED rather than inferred from a predicate such as "has no tenant_id
-- column", because tenant_id_reservation HAS one — there it is the subject of the row, not the
-- scope of it. What changed in V072 is where the names live: beside the tables they exempt, in one
-- registry both this script and deploy/verify/conformance.sql read, after the two hand-maintained
-- copies drifted apart and check 3 fired on four correctly global tables.
SELECT '   ' || u.table_name || ': ' || u.finding
  FROM unregistered_unforced_tables() u
 ORDER BY u.table_name;

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
echo "== schema applied. The application tier starts against it: docker compose up -d app (compose),"
echo "   or the Helm release's rollout continues (deploy/k8s). See deploy/README.md for what exists."
