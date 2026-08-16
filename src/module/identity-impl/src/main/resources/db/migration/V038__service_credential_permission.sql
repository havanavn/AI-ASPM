-- =============================================================================
-- V038 — the permission that governs issuing a pipeline credential.
--
-- WHY A NEW CODE RATHER THAN REUSING ONE. The nearest existing codes are `iam.credential.reset` and
-- `iam.user.manage`, and neither fits:
--
--   * `iam.credential.reset` resets a HUMAN's password, which revokes their sessions and forces a
--     change. Issuing a pipeline key creates a NEW long-lived credential that acts as a principal
--     with a pinned scope. Somebody trusted to help a colleague back into their account is not
--     thereby trusted to mint a non-interactive identity for a build server.
--   * `iam.user.manage` would work and is far too wide: it would mean anybody who can administer
--     users can issue ingestion credentials, which is precisely the conflation that makes an
--     over-broad role impossible to descope later.
--
-- ADR-027 fixes the permission CATALOGUE in the product while leaving roles to the tenant, so adding
-- a code is a product change and is made here deliberately rather than by widening an existing one.
--
-- `requires_step_up` is true, and `is_restricted` is true. Issuing returns a secret exactly once —
-- the only time the platform ever discloses one — which is the definition of a restricted reveal
-- (class C in ADR-036, and SEC-SEC-004 puts credential issuance among the operations needing
-- re-authentication).
-- =============================================================================

INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up)
VALUES ('sbm.credential.manage', 'sbm',
        '{"en":"Issue and revoke ingestion credentials"}'::jsonb, true, true)
ON CONFLICT (code) DO UPDATE
   SET is_restricted    = true,
       requires_step_up = true,
       label_i18n       = EXCLUDED.label_i18n;

-- Granted to whichever role already administers users, per tenant, so a fresh deployment is not left
-- with a permission nobody holds — which reads as a broken feature rather than as a deliberate gate.
-- A tenant that wants it elsewhere moves it; the catalogue is product-fixed, the roles are not.
--
-- ENUMERATED FROM `tenant`, with the context set per tenant inside the loop. `role_permission` is
-- tenant-isolated and its policy calls current_tenant_id(), which REFUSES rather than defaulting
-- (SEC-TEN-005 failing closed, exactly as designed) — so a single INSERT across tenants cannot work
-- and should not. The same shape as the V021 backfill, for the same reason.
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'sbm.credential.manage'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'iam.user.manage')
        ON CONFLICT DO NOTHING;
    END LOOP;
END
$$;
