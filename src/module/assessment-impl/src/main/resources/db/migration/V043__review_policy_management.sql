-- =============================================================================
-- V043 — letting an administrator actually SET the review cadence.
--
-- THE GAP THIS CLOSES. V024 introduced `full_review_policy` — how many months between full reviews,
-- per criticality tier — and the cadence view has computed against it ever since. But nothing in the
-- product could write it. The rows came from the seed, and a tenant with a criticality tier the seed
-- did not cover had no interval at all: every application on that tier reported `NO_OBLIGATION` and
-- read, to anybody scanning the estate, as though it were compliant. It was not compliant; nothing
-- had ever been required of it.
--
-- That is the failure mode product principle 1 exists to name. "Nothing is owed" and "we never said
-- what was owed" are different states and they were rendering identically, so this adds the
-- permission that makes the policy editable and leaves the distinction visible in the interface.
--
-- WHY A NEW PERMISSION AND NOT `asm.request.schedule`. Scheduling one review is scheduling one piece
-- of work. Setting the interval decides, for every application on a tier, how long it may go
-- unassessed — including retroactively, because the next-due date is derived and every row recomputes
-- the moment this changes. Those are different powers and the second is the one that can make an
-- entire estate look current by widening a number. It is restricted and requires step-up for that
-- reason: a session that has merely been resumed should not be able to do it.
--
-- WHY THE TIER LIST IS NOT ENUMERATED HERE. Criticality tiers are tenant data (ADR-027). The policy
-- is keyed by tier id and a tenant may have three tiers or seven; the editor reads whatever the
-- tenant has. No tier code appears in this file or in the code that reads it.
-- =============================================================================

INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up)
VALUES ('asm.policy.manage', 'asm',
        '{"en":"Set the periodic review interval","vi":"Đặt chu kỳ đánh giá định kỳ"}'::jsonb,
        true, true)
ON CONFLICT (code) DO NOTHING;

-- Granted to whoever may already manage the organization's own structural vocabulary. That is the
-- closest existing power in kind — both decide the shape the estate is measured against rather than
-- any one record in it — and it avoids inventing a role, which ADR-027 forbids.
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'asm.policy.manage'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'org.nodetype.manage')
        ON CONFLICT DO NOTHING;
    END LOOP;
END
$$;

-- The editor writes one row per tier and must be able to remove one, because "this tier has no
-- review obligation" is a decision a tenant is entitled to make explicitly. DELETE was never granted.
GRANT SELECT, INSERT, UPDATE, DELETE ON full_review_policy TO app_runtime;

-- NO NEW INDEX. A first draft of this migration added a unique index on
-- (tenant_id, criticality_tier_id) to serve the editor's per-tier read and the cadence view's join.
-- V024 already created `uq_full_review_policy__tier` over exactly those columns as a constraint, so
-- the addition was a second identical B-tree: doubling the write cost of every policy edit and the
-- planner's work on every read, in exchange for nothing. It is recorded here rather than quietly
-- dropped because "add an index for the query you serve" is right often enough to be applied without
-- checking whether the constraint already built one, and this is the case where that goes wrong.
--
-- The ON CONFLICT (tenant_id, criticality_tier_id) upsert in ReviewPolicyService resolves against
-- that existing constraint.
