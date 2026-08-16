-- =============================================================================
-- V049 — the three capabilities an executive actually needs.
--
-- WHAT THE OVERVIEW WAS MISSING. An org manager opens the platform and asks four things: is my
-- organization getting better or worse, what is worst and whose is it, what is waiting for my
-- decision, and can I trust these numbers. The estate answered the first two. It answered neither of
-- the others, and the gaps are not cosmetic:
--
--   * COVERAGE. "97 critical and high" sat beside the fact that 9 of 17 applications have never been
--     reviewed and 29 of 47 parts have never submitted a bill of materials. An executive reading the
--     first without the second is reassured, and reassurance is the dangerous direction. Product
--     principle 1 exists for this, and the executive surface is where breaking it costs most.
--
--   * DECISIONS. One risk exception sits in REQUESTED — somebody is waiting on an approval — and no
--     surface showed it. The most valuable thing an executive does here is DECIDE, and the one place
--     they were invited to decide was not on the page they open.
--
-- WHY THESE ARE RULES AND NOT PROSE. A summary is the least valuable thing to give an executive and
-- the easiest to get wrong: they can already see the figures, and a model asked "how are we doing"
-- writes fluently and omits exactly what nobody measured. So `coverage.caveat` composes recorded
-- facts and states an absence; `exception.brief` assembles what a decision needs. Neither writes
-- anything a query could not defend.
-- =============================================================================

-- These two DO drop and re-add unconditionally, and that is correct: this migration is the current
-- authority for both constraints and its lists are the widest. The rule the V046 replay failure
-- taught: a migration may only re-impose a constraint it OWNS, and owning it means being the last one
-- to widen it. An earlier migration must guard instead (see V046).
--
-- A risk exception can be the subject of a suggestion. It is a decision waiting on a person, which is
-- precisely what the ledger's promotion step is for.
ALTER TABLE ai_suggestion DROP CONSTRAINT IF EXISTS ck_ai_suggestion__subject;
ALTER TABLE ai_suggestion ADD CONSTRAINT ck_ai_suggestion__subject CHECK (
    subject_kind = ANY (ARRAY['ASSET', 'FINDING', 'ORG_NODE', 'ASSESSMENT_REQUEST',
                              'RISK_EXCEPTION']));

ALTER TABLE ai_suggestion DROP CONSTRAINT IF EXISTS ck_ai_suggestion__kind;
ALTER TABLE ai_suggestion ADD CONSTRAINT ck_ai_suggestion__kind CHECK (
    suggestion_kind = ANY (ARRAY[
        'RECURRING_WEAKNESS', 'REMEDIATION_DRAFT', 'DUPLICATE_CANDIDATE',
        'SEVERITY_REVIEW', 'NARRATIVE_DRAFT',
        'OWNERSHIP_ROUTING', 'INTAKE_CLASSIFICATION',
        'COVERAGE_CAVEAT', 'EXCEPTION_BRIEF']));

ALTER TABLE ai_capability DROP CONSTRAINT IF EXISTS ck_ai_capability__subject;
ALTER TABLE ai_capability ADD CONSTRAINT ck_ai_capability__subject CHECK (
    subject_kind = ANY (ARRAY['ASSET', 'FINDING', 'ORG_NODE', 'ASSESSMENT_REQUEST',
                              'RISK_EXCEPTION']));

DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        INSERT INTO ai_capability (tenant_id, code, suggestion_kind, subject_kind, surface,
                                   data_category, max_per_run)
        VALUES
            -- Reads counts and dates only. It exists to state what was NOT measured, which needs no
            -- record content and must never depend on a provider being configured.
            (t, 'coverage.caveat', 'COVERAGE_CAVEAT', 'ORG_NODE', '/overview', 'AGGREGATE', 10),
            -- What a person needs in front of them to approve or refuse an exception.
            (t, 'exception.brief', 'EXCEPTION_BRIEF', 'RISK_EXCEPTION', '/overview', 'AGGREGATE', 10)
        ON CONFLICT (tenant_id, code) DO NOTHING;

        -- The narrative moves to the overview and is bound to the caveat: see TriageAgent, which
        -- refuses to produce one for a scope whose coverage is unstated.
        UPDATE ai_capability SET surface = '/overview' WHERE code = 'narrative.draft';
    END LOOP;
END
$$;
