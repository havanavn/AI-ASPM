-- =============================================================================
-- V068 — the permission that governs the declared-field catalogue.
--
-- WHY A NEW PERMISSION. Declaring a field is not editing an asset. `ast.asset.update` lets somebody
-- record that this project sits behind Cloudflare; this permission lets somebody decide that
-- "behind which CDN" is a question the platform asks at all, for every project, for ever. The second
-- is tenant configuration and belongs to whoever administers the tenant, and the population holding
-- the first is much larger than the population that should hold the second.
--
-- RESTRICTED, because the destructive direction is quiet: deprecating a field removes a recorded
-- security fact from every page that showed it, without deleting a single value, and nothing about
-- the screens afterwards says anything is missing.
--
-- NOT step-up, unlike `cfg.ai.manage`. That one accepts a live third-party credential and decides
-- what may leave the platform; this one adds a dropdown value. Requiring a fresh second factor for
-- routine catalogue work trains people to clear step-up prompts without reading them, which costs
-- more than it buys on the operations where it genuinely matters. A tenant that disagrees flips
-- `requires_step_up` in this row — no code change.
-- =============================================================================

INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up)
VALUES ('cfg.asset.field.manage', 'cfg',
        '{"en":"Manage declared asset fields","vi":"Quản lý trường dữ liệu khai báo"}'::jsonb,
        true, false)
ON CONFLICT (code) DO NOTHING;

DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        -- Granted where roles are already composed. Whoever decides which permissions a role holds
        -- is the tenant administrator, and deciding what every asset record asks for is the same
        -- class of decision about the shape of the tenant. No role is invented (ADR-027).
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'cfg.asset.field.manage'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'auz.role.manage')
        ON CONFLICT DO NOTHING;
    END LOOP;
END
$$;
