-- =============================================================================
-- V053 — the capability that classifies a finding.
--
-- Reads the finding's title, description and class, and proposes an executive risk category, an OWASP
-- Top 10:2025 entry and a CWE. Declared RECORD because it cannot work otherwise: the words are the
-- evidence. That is the second capability to declare RECORD, and both are the same shape — a capability
-- that reads text to produce text, where AGGREGATE would be a capability that guesses.
--
-- WHY IT IS ALLOWED TO WRITE THE COLUMNS. See the header of V052. It writes with
-- `classification_source = 'AI'` and no confirmation stamp, and raises a suggestion in the ledger for
-- the confirmation to happen against. The value is present so filters and statistics work; nothing
-- treats it as the organization's assertion until a person confirms it.
-- =============================================================================

ALTER TABLE ai_suggestion DROP CONSTRAINT IF EXISTS ck_ai_suggestion__kind;
ALTER TABLE ai_suggestion ADD CONSTRAINT ck_ai_suggestion__kind CHECK (
    suggestion_kind = ANY (ARRAY[
        'RECURRING_WEAKNESS', 'REMEDIATION_DRAFT', 'DUPLICATE_CANDIDATE',
        'SEVERITY_REVIEW', 'NARRATIVE_DRAFT',
        'OWNERSHIP_ROUTING', 'INTAKE_CLASSIFICATION',
        'COVERAGE_CAVEAT', 'EXCEPTION_BRIEF',
        'CLASSIFICATION']));

DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        INSERT INTO ai_capability (tenant_id, code, suggestion_kind, subject_kind, surface,
                                   data_category, max_per_run, enabled)
        VALUES (t, 'classification.assist', 'CLASSIFICATION', 'FINDING', '/vulnerabilities',
                'RECORD', 100,
                -- Enabled, unlike the other RECORD capability. The requirement is that a submission
                -- missing a classification gets one automatically, and a capability that ships off
                -- would make that silently not happen. The rules version reads text locally and sends
                -- nothing anywhere; when a provider is configured, `data_category = RECORD` is the flag
                -- an administrator has to accept before the same words reach a third party.
                true)
        ON CONFLICT (tenant_id, code) DO UPDATE SET enabled = true, data_category = 'RECORD';
    END LOOP;
END
$$;
