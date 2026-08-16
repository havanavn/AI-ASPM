-- =============================================================================
-- V016 — step-up authentication, and the columns user administration needs.
--
-- WHY THIS EXISTS AT ALL. AnnotationClass assigns Authentication.ANY_WITH_STEP_UP to class C
-- (restricted reveal) and class E (configuration), and Dispatcher enforces it:
--
--     if (operation.annotationClass().requiresStepUp() && !principal.stepUpAuthenticated())
--         return 401 STEP_UP_REQUIRED;
--
-- Principal.stepUpAuthenticated was constructed as a literal `false` at every call site. So the
-- enforcement was real and NOTHING COULD SATISFY IT: every class E operation already registered —
-- node type and asset type creation and update among them — answered 401 to every human caller, and
-- the interface had no surface that could clear the condition. The gate was closed and had no key.
--
-- That was not visible from the authorization tests, which assert that a caller WITHOUT step-up is
-- refused. Nothing asserted that a caller WITH it is admitted, because no such caller could be
-- constructed. A gate nothing can pass is indistinguishable from a gate nothing needs.
--
-- Found while adding the administration pages, whose three permissions — iam.user.manage,
-- iam.credential.reset, auz.role.manage — are exactly the ones the catalogue marks
-- requires_step_up = true.
--
-- WHAT STEP-UP IS HERE. A fresh second factor, re-presented. ADR-059 narrows human authentication to
-- a local credential plus TOTP, so the strongest re-assertion available is a TOTP code the caller
-- produces now. It is not a second channel and does not claim to be: it re-proves possession of the
-- enrolled device within a short window, which is what makes a stolen live session insufficient for
-- a credential reset.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. principal_session.step_up_at
--
-- On the SESSION, not on the principal. Step-up is a property of one authenticated interaction: a
-- principal who stepped up in one browser has not stepped up in another, and a flag on the principal
-- would make a second session inherit the elevation. That is the same class of mistake as caching a
-- permission set in a session (SEC-SEC-011) and it fails in the more dangerous direction.
--
-- Nullable with no default: a session that has never stepped up must be distinguishable from one
-- that stepped up at the epoch. PP-1 applies to the platform's own state, not only to findings.
-- -----------------------------------------------------------------------------
ALTER TABLE principal_session
    ADD COLUMN IF NOT EXISTS step_up_at timestamptz;

COMMENT ON COLUMN principal_session.step_up_at IS
    'When the second factor was last re-presented on this session. Freshness is evaluated in the '
    'application against STEP_UP_WINDOW, not by a constraint here: the window is a product decision '
    'that will be tuned, and a CHECK on it would make tuning a migration.';

-- No index. The column is read only through the session row already being fetched by token_hash on
-- uq_session__token, so an index on it would serve no query — which DOC-00 prohibits stating as an
-- indexing strategy.

-- -----------------------------------------------------------------------------
-- 2. authentication_attempt: STEP_UP as a factor, and its outcomes
--
-- The attempt log is append-only and is the evidence for SEC-SEC-005's progressive delay. A step-up
-- failure that is not recorded is an attempt against the highest-value operations in the platform
-- that leaves no trace, so the factor is widened rather than reusing 'TOTP' — the two answer
-- different questions after an incident: "did somebody sign in" and "did somebody try to elevate".
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    definition text;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO definition
      FROM pg_constraint
     WHERE conrelid = 'authentication_attempt'::regclass
       AND conname = 'ck_attempt__factor';

    IF definition IS NULL THEN
        RAISE EXCEPTION 'ck_attempt__factor is absent from authentication_attempt. V016 widens it; '
                        'if V015 changed shape, this migration must be revisited rather than '
                        'silently skipped.';
    END IF;

    IF position('STEP_UP' IN definition) = 0 THEN
        ALTER TABLE authentication_attempt DROP CONSTRAINT ck_attempt__factor;
        ALTER TABLE authentication_attempt ADD CONSTRAINT ck_attempt__factor
            CHECK (factor IN ('PASSWORD', 'TOTP', 'RECOVERY_CODE', 'STEP_UP'));
    END IF;
END
$$;

-- -----------------------------------------------------------------------------
-- 3. principal.credential_reset_required — no. Deliberately NOT added.
--
-- must_change_password already exists on principal (V015) and is the same fact. Adding a second
-- column for the administrative path would create two sources for one answer, and product principle
-- 10 is "one name, one meaning, one place". The administrative reset sets the existing flag.
--
-- What IS added is the audit of who did it, because "this user's password was reset" and "this user
-- reset their own password" are different events and the flag cannot tell them apart.
-- -----------------------------------------------------------------------------
ALTER TABLE principal_credential
    ADD COLUMN IF NOT EXISTS set_by uuid;

COMMENT ON COLUMN principal_credential.set_by IS
    'The principal who caused this credential to be set — the account holder for a self-service '
    'change, an administrator for a reset, NULL for the deployment bootstrap. Not a foreign key: '
    'ADR-030 forbids one across module boundaries and the bootstrap has no principal to name.';

-- -----------------------------------------------------------------------------
-- 4. credential_reset_token.delivery_channel already carries ADMIN_HANDOVER (V015).
--
-- Stated rather than changed, because a reviewer looking for where the administrative reset stores
-- its token will look for a new table. It does not need one: V015's constraint already admits
-- ADMIN_HANDOVER alongside EMAIL, and the one-hour lifetime bound applies to both. An administrator
-- handing a link to a user over a channel the platform does not control is the case the column
-- exists to distinguish in the audit trail.
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- 5. A view for the administration list, so the page does not join four tables in a page class.
--
-- SECURITY INVOKER (the default) is load-bearing: a SECURITY DEFINER view over tenant-isolated
-- tables would run with the definer's row-level context and hand every tenant's principals to any
-- caller. Stated because "why is this not DEFINER" is the question a reviewer asks, and because the
-- answer is the difference between a convenience and a cross-tenant disclosure.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW principal_administration AS
SELECT p.id,
       p.tenant_id,
       p.username,
       p.email,
       p.display_name,
       p.lifecycle_state,
       p.must_change_password,
       p.mfa_enrolled_at IS NOT NULL              AS mfa_enrolled,
       p.last_authenticated_at,
       p.created_at,
       -- Counted, not listed: the count is what the list column shows, and pulling the role names
       -- for every row makes the page a join per user. The detail view fetches the names.
       (SELECT count(*) FROM role_assignment a
         WHERE a.principal_id = p.id AND a.revoked_at IS NULL
           AND (a.expires_at IS NULL OR a.expires_at > now()))  AS live_assignments,
       (SELECT count(*) FROM principal_session s
         WHERE s.principal_id = p.id AND s.revoked_at IS NULL
           AND s.absolute_expires_at > now())                   AS live_sessions
  FROM principal p
 WHERE p.kind = 'HUMAN'
   AND p.lifecycle_state <> 'DEPROVISIONED';

COMMENT ON VIEW principal_administration IS
    'The user administration list of PRD-IAM-*. SECURITY INVOKER so row-level policies apply to the '
    'CALLER; a DEFINER view here would disclose every tenant''s principals.';

GRANT SELECT ON principal_administration TO app_runtime, integrity_verifier;
