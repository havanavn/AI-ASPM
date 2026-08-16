-- =============================================================================
-- Which roles carry the new grant permission, and one worked example of the delegation chain.
--
-- WHY THIS IS A SEED
--
-- V030 adds `ast.asset.grant` to the permission CATALOGUE, which is the product's (PRD-AUZ-001).
-- Which ROLES carry it is the tenant's decision (ADR-027), so it belongs here. A migration that
-- assigned it would be the product deciding a customer's authorization model for them.
--
-- The two roles below are this demo tenant's choice and nothing more. A deployment where the
-- security team is called something else moves the permission and changes no code — that portability
-- is the entire reason no role name appears in the application.
--
-- Re-runnable: every statement is guarded.
-- =============================================================================

\set ON_ERROR_STOP on

DO $seed$
DECLARE
    t          uuid := '11111111-1111-1111-1111-111111111111';
    admin_role uuid;
    pen_role   uuid;
    p_card     uuid;
    dev        uuid;
    lead       uuid;
BEGIN
    PERFORM set_config('aspm.current_tenant', t::text, true);

    -- The administrator role is defined in this tenant as "everything in the catalogue", so a
    -- permission added by a later migration has to be re-synced. Doing it by SELECT rather than by
    -- listing codes is what stops this file going stale on the next catalogue addition.
    SELECT id INTO admin_role FROM role WHERE tenant_id = t AND code = 'ADMIN';
    IF admin_role IS NOT NULL THEN
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT t, admin_role, code FROM permission_catalogue
        ON CONFLICT DO NOTHING;
    END IF;

    -- The assessor role. The user's words: the pentester is the assessor, and the assessor may
    -- appoint an owner for a project. That is this permission and no other.
    --
    -- cap.team.manage joins it (V034): the person who runs the assessment team is the one who knows
    -- who is on which squad, and routing a roster change through a platform administrator is the
    -- kind of friction that leaves the roster wrong.
    SELECT id INTO pen_role FROM role WHERE tenant_id = t AND code = 'PENTESTER';
    IF pen_role IS NOT NULL THEN
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT t, pen_role, c FROM unnest(ARRAY['ast.asset.grant', 'cap.team.manage']) AS c
        ON CONFLICT DO NOTHING;
    END IF;

    -- ---------------------------------------------------------------------------------------------
    -- One worked example, so the delegation chain is visible rather than described.
    --
    -- The developer owns the card platform project. From there they can delegate the right to raise
    -- requests to their own team without going through the security team — which is the bottleneck
    -- the owner level exists to remove.
    -- ---------------------------------------------------------------------------------------------
    SELECT id INTO p_card FROM asset
     WHERE tenant_id = t AND identity_key = 'card authorization platform';
    SELECT id INTO dev  FROM principal WHERE tenant_id = t AND username = 'developer';
    SELECT id INTO lead FROM principal WHERE tenant_id = t AND username = 'admin';

    IF p_card IS NOT NULL AND dev IS NOT NULL THEN
        INSERT INTO asset_grant (tenant_id, asset_id, principal_id, capability, granted_by)
        VALUES (t, p_card, dev, 'OWN', lead)
        ON CONFLICT DO NOTHING;
    END IF;

    RAISE NOTICE 'object grants: % live, ast.asset.grant held by % role(s)',
        (SELECT count(*) FROM asset_grant WHERE revoked_at IS NULL),
        (SELECT count(*) FROM role_permission WHERE permission_code = 'ast.asset.grant');
END
$seed$;
