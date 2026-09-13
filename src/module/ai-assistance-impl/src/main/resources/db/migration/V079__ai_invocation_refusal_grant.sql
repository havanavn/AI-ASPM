-- =============================================================================================
-- V079 — the application may mark an invocation refused.
--
-- V078 granted SELECT and INSERT on `ai_invocation`, on the reasoning that an invocation record is
-- append-only. It is — except for one column pair: when the platform's own validation rejects what the
-- model produced (an invented figure, a contradicted severity, a code off the tenant's list, a citation
-- that does not resolve), the row written a moment earlier as OK has to say REFUSED with the reason
-- (PRD-AIC-032: "a rejection rate is a quality signal") and lose its output, so the identical-request
-- cache (PRD-AIC-055) never serves the rejected text. Found on the running deployment, not in the test
-- suite: the embedded engine's test user bypasses grants. The grant is column-limited to what that
-- correction touches; nothing else on the row is updatable.
--
-- Requirements: PRD-AIC-032, PRD-AIC-043, PRD-AIC-055. Decisions: ADR-075.
-- =============================================================================================
GRANT UPDATE (outcome, refusal_code, output_text) ON ai_invocation TO app_runtime;

-- The first live run hit the per-person hourly limit at 120 while a batch capability classified a
-- hundred findings — the limit exists against a refresh loop, not against work (PRD-AIC-055). Rows that
-- still carry the first default move to the corrected one; a tenant that set its own value keeps it.
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        -- Row-level security raises without a tenant context (CON-DAT-013); the loop supplies one per tenant.
        PERFORM set_config('aspm.current_tenant', t::text, true);
        UPDATE ai_budget SET per_principal_hourly_invocations = 600
         WHERE tenant_id = t AND per_principal_hourly_invocations = 120;
    END LOOP;
END
$$;
