-- =============================================================================
-- V051 — the reap permission has to reach the credential's PRINCIPAL too.
--
-- THE DEFECT. V050 appended `iam.session.reap` to the ticking credential's `permissions` array and
-- stopped there. The reap call then returned 404 on every tick.
--
-- WHY. `ServiceCredentialResolver` loads a credential "with its permissions already intersected
-- against its principal's" — a service credential can never exceed the human identity it acts as. That
-- is the right design and it is load-bearing: it means issuing a credential cannot grant a power its
-- owner does not have, so a compromised issuing flow cannot escalate. The consequence is that a
-- credential permission exists only where BOTH sides carry it, and V050 wrote one side.
--
-- The same shape as the V046/V047 mistake: a permission granted in one place whose effective value is
-- computed from two. Worth naming as a pattern — when adding a service-credential permission, grant it
-- to the credential AND to a role its principal holds, or it is inert.
--
-- WHY `sbm.scan.run` IS THE RIGHT ROLE TO FOLLOW. It is the permission the ticking credential's other
-- capability already travels on, so any role that survives the intersection for the scan survives it
-- for the reap. Following it means the two capabilities of one ticker cannot drift apart.
-- =============================================================================

DO $$
DECLARE
    t uuid;
    holders integer;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);

        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'iam.session.reap'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'sbm.scan.run')
        ON CONFLICT DO NOTHING;

        -- Loud rather than inert. A grant predicate that matches nothing looks identical to one that
        -- matched, which is exactly how V050 shipped a permission nobody could use.
        SELECT count(*) INTO holders FROM role_permission
         WHERE permission_code = 'iam.session.reap';
        IF holders = 0 THEN
            RAISE EXCEPTION
                'iam.session.reap reached no role in tenant %, so the ticking credential''s copy of '
                'it is intersected away and the reaper cannot run.', t;
        END IF;
    END LOOP;
END
$$;
