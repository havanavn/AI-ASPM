-- =============================================================================
-- V047 — grant the promotion permission to somebody.
--
-- THE DEFECT. V046 granted `aic.suggestion.promote` to every role already holding
-- `vul.finding.update`. No such permission exists: the catalogue has `vul.finding.read` and
-- `vul.finding.triage`. The predicate matched nothing, so the permission was created and granted to
-- ZERO roles, and no principal in the deployment could promote or reject a suggestion.
--
-- That is precisely the defect V046 was written to close, one level up. The ledger existed and had no
-- reader; then the reader existed and had no one permitted to act on it. A suggestion queue nobody can
-- decide on is the same thing as no queue — ADR-005's audited human action was still unreachable.
--
-- Found by checking the grant after running, not by reading the migration. A grant predicate that
-- matches nothing fails silently and looks identical to one that matched: both leave a valid catalogue
-- row behind.
--
-- WHY `triage` IS THE RIGHT PAIRING. Promotion decides that a proposal about a finding is worth acting
-- on — routing it, questioning its grade, joining it to another. That is triage. Somebody who may not
-- triage a finding must not be able to promote a suggestion that changes how it is triaged, and
-- somebody who may triage already holds the judgement this asks for.
-- =============================================================================

DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'aic.suggestion.promote'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'vul.finding.triage')
        ON CONFLICT DO NOTHING;
    END LOOP;
END
$$;

-- A grant that matched nothing is invisible. This makes the next one loud: if a tenant ends up with
-- nobody holding the permission, the migration fails rather than completing and leaving a feature
-- nobody can reach.
--
-- Counted INSIDE the tenant loop. A first version counted across all tenants in a block of its own and
-- was refused by the platform's own guard — `role_permission` is tenant-isolated, and a statement with
-- no tenant context established cannot read it (CON-DAT-013, SEC-TEN-005). The check was written as
-- though the migration ran outside the isolation it is subject to.
DO $$
DECLARE
    t uuid;
    holders integer;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        SELECT count(*) INTO holders FROM role_permission
         WHERE permission_code = 'aic.suggestion.promote';
        IF holders = 0 THEN
            RAISE EXCEPTION
                'aic.suggestion.promote was granted to no role in tenant %. The suggestion ledger '
                'would have a review surface nobody is permitted to use, which is the defect this '
                'migration exists to correct.', t;
        END IF;
    END LOOP;
END
$$;
