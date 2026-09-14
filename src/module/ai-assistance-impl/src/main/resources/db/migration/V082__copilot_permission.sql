-- =============================================================================================
-- V082 — the copilot gets its own permission.
--
-- WHAT WAS WRONG. The copilot rode on `aic.assist.use`, the permission the draft button and the
-- single-question ask box already use. Reported from use on 2026-09-13: a tenant wanted its
-- engineering roles to keep drafting help and not to have a conversational window onto the estate,
-- and could not express it — revoking `aic.assist.use` from a role took away all three surfaces.
--
-- WHY THESE ARE TWO AUTHORITIES AND NOT ONE. Reach. The draft button and the ask box act on the
-- record the person is already looking at and add nothing to what they can see. The copilot is a
-- retrieval surface over everything in their scope, on every page, in a form that composes across
-- packs a person would otherwise open one screen at a time. Both are bounded by the same scope
-- predicate and neither discloses a row the caller could not open — but "may be helped with the thing
-- in front of me" and "may interrogate my whole reach conversationally" are different grants, and a
-- catalogue that cannot say so forces the tenant to choose between all of it and none of it.
--
-- WHY EVERY ROLE THAT HELD THE OLD ONE GETS THE NEW ONE. An upgrade that silently switches a working
-- feature off is worse than one that requires an explicit revoke: the first is diagnosed as a bug by
-- everybody who meets it, the second is a decision somebody makes on purpose. So this preserves
-- behaviour exactly, and the tenant narrows it from the Roles screen — which is the only place an
-- organizational decision about who may use what belongs (ADR-027).
--
-- WHAT THIS DELIBERATELY DOES NOT DO. Decide which roles should lose it. "The developers do not need
-- it" is true in the tenant that reported it and is not a product fact; a migration that dropped the
-- permission from a role called DEVELOPER would be the platform legislating an organization's
-- structure from a role name, which is the failure ADR-027 exists to prevent.
--
-- GRANTING IT TO ONE PERSON. Roles are tenant data and may hold a single permission. A role carrying
-- only `aic.copilot.use`, granted to one principal over one subtree, is how "this engineer, for this
-- quarter, over this unit" is expressed — including with an expiry, since a grant may carry one.
--
-- Requirements: PRD-AIC-058, SEC-AUZ-001, SEC-AUZ-016.
-- Decisions: ADR-027, ADR-075, ADR-077.
-- =============================================================================================

-- Product-fixed, tenant-independent: the catalogue is composed from, never added to, by a tenant.
INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up)
VALUES ('aic.copilot.use', 'aic',
        '{"en":"Use the AI copilot","vi":"Sử dụng AI copilot"}'::jsonb,
        -- Not restricted: it reveals nothing the holder could not open a screen and read. Not
        -- step-up: it is a read surface, and a second factor on a question people ask twenty times a
        -- day is a control that gets worked around rather than one that protects anything.
        false, false)
ON CONFLICT (code) DO NOTHING;

COMMENT ON TABLE permission_catalogue IS
    'DOC-07 section 5. The product-fixed permission catalogue. A tenant composes roles from these codes and '
    'cannot add to them: a permission with nothing enforcing it would appear to grant protection and provide '
    'none. `aic.copilot.use` (V082) is separate from `aic.assist.use` because the copilot reaches across the '
    'caller''s whole scope while drafting help acts on the record in front of them.';

-- Behaviour preserved, per tenant, under the row-level policy. A tenant whose roles were composed by
-- hand keeps exactly the population that had the feature yesterday.
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT rp.tenant_id, rp.role_id, 'aic.copilot.use'
          FROM role_permission rp
         WHERE rp.permission_code = 'aic.assist.use'
        ON CONFLICT DO NOTHING;
    END LOOP;
    PERFORM set_config('aspm.current_tenant', '', true);
END $$;
