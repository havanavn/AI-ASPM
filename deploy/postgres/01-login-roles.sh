#!/bin/sh
# ==============================================================================================
# The login users, created once at cluster initialization.
#
# V001 creates the four GROUP roles of DOC-04 section 7.2 — app_runtime, migration_runner,
# integrity_verifier, offboarding_executor — all NOLOGIN. That is deliberate: a group role cannot
# be connected as, so the privilege and the credential are separate things.
#
# This script creates the LOGIN users that are granted into them. The separation is what
# OPS-DEP-009 means by structural: revoking a login user does not touch the privilege model, and
# a service that was never given a password for aspm_migrate cannot use migration_runner however
# its code is written.
#
# offboarding_executor deliberately gets NO login user. It is dual-control gated (OPS-DEP-022) and
# is the mechanism of cryptographic erasure at tenant offboarding — "also the mechanism an insider
# would use to destroy evidence". Creating a password for it here would put that capability in a
# compose file, which is the opposite of dual control.
#
# payload_eraser likewise gets none: it exists for the erasure path of ADR-034 and is invoked
# under the erasure runbook, not by a running service.
# ==============================================================================================
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
-- The group roles are created here, before V001, for a reason stronger than ordering.
--
-- CREATE ROLE ... BYPASSRLS requires SUPERUSER. V001 runs as aspm_migrate, which is deliberately
-- not a superuser, so V001 CANNOT create these roles in this topology — its guarded blocks would
-- fail on the first BYPASSRLS role. The init script runs as the bootstrap superuser and is the
-- only place in the deployment where that authority is available.
--
-- V001 uses IF NOT EXISTS guards throughout, so it finds them already present and proceeds.
--
-- The attributes must match V001's declarations exactly. A placeholder created without BYPASSRLS
-- would leave migration_runner unable to run the migrations, and the failure would surface as a
-- migration defect rather than as the provisioning error it is.
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime') THEN
        CREATE ROLE app_runtime NOLOGIN NOBYPASSRLS NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'migration_runner') THEN
        CREATE ROLE migration_runner NOLOGIN BYPASSRLS NOSUPERUSER;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'integrity_verifier') THEN
        CREATE ROLE integrity_verifier NOLOGIN BYPASSRLS NOSUPERUSER;
    END IF;
    -- All FIVE, not the three that get a login user. Creating the group role and issuing a
    -- credential for it are different acts, and V001 declares five: it fails on the first one
    -- missing here, because it cannot create a BYPASSRLS role itself.
    --
    -- offboarding_executor destroys a tenant under SEC-TEN-041; payload_eraser holds the only
    -- DELETE grant on audit_event_payload. Both exist as privileges from this point on. Neither
    -- gets a password, in this file or any other, so neither can be connected as.
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'offboarding_executor') THEN
        CREATE ROLE offboarding_executor NOLOGIN BYPASSRLS NOSUPERUSER;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payload_eraser') THEN
        CREATE ROLE payload_eraser NOLOGIN BYPASSRLS NOSUPERUSER;
    END IF;
END
\$\$;

-- The login users. One per group role that a container legitimately holds.
CREATE ROLE aspm_app     LOGIN PASSWORD '${APP_PASSWORD}'     NOBYPASSRLS NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE aspm_migrate LOGIN PASSWORD '${MIGRATE_PASSWORD}' NOSUPERUSER;
CREATE ROLE aspm_verify  LOGIN PASSWORD '${VERIFY_PASSWORD}'  NOSUPERUSER;

GRANT app_runtime        TO aspm_app;
GRANT migration_runner   TO aspm_migrate;
GRANT integrity_verifier TO aspm_verify;

-- The migration user needs to create objects; the other two never do.
GRANT CREATE ON SCHEMA public TO aspm_migrate;
GRANT USAGE  ON SCHEMA public TO aspm_app, aspm_verify;

-- CON-DAT-012 and SEC-TEN-008: row-level security is FORCEd, which applies policies to the table
-- OWNER as well. The owner of the tables will be aspm_migrate, and FORCE is what stops ownership
-- from being an implicit bypass. Asserted by the verification suite; noted here because the
-- ownership decision is made at provisioning time and cannot be corrected later without a rewrite
-- of every table's ACL.

-- Deny by default at the database level too: no PUBLIC connect.
REVOKE ALL ON DATABASE ${POSTGRES_DB} FROM PUBLIC;
GRANT CONNECT ON DATABASE ${POSTGRES_DB} TO aspm_app, aspm_migrate, aspm_verify;
SQL

echo "login roles created: aspm_app, aspm_migrate, aspm_verify"
echo "NOT created, deliberately: a login user for offboarding_executor or payload_eraser"
