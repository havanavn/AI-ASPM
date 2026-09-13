-- =============================================================================================
-- V078 — the AI capabilities become real: invocation records, budgets, evaluation runs, and the
-- capabilities DOC-10 §8 specifies that had no row.
--
-- WHAT WAS MISSING. ADR-044 built the architecture and deferred the capabilities. The ledger, the
-- catalogue, the provider row and one prompt existed; there was no record of what left the boundary
-- (PRD-AIC-043), no budget (PRD-AIC-053), no cache (PRD-AIC-055), no harness result (PRD-AIC-049,
-- PRD-AIC-050), and four of the six capabilities of DOC-10 §8 — score explanation, prioritization,
-- drafting assistance, and a grounded question over the posture — had no catalogue row. This
-- migration is the storage; `aspm.app.ai` is the code.
--
-- WHY INVOCATIONS ARE ROWS AND NOT LOG LINES. PRD-AIC-044: a tenant's data-governance function asks
-- "what categories of our data left, to which provider, over what period" and the answer has to come
-- from a query, not from grepping a log that rotated. Prompt and output retention are per tenant
-- (PRD-AIC-043 "MUST be configurable"); the hash is always kept so a cache hit and a repeated question
-- are the same row shape.
--
-- WHY THE BUDGET IS ONE ROW PER TENANT WITH DEFAULTS. PRD-AIC-054: on exhaustion the capability becomes
-- unavailable with the reason stated; nothing silently switches to a cheaper model. A tenant that never
-- looked at the budget still has one, so a runaway loop stops at a number somebody can point to.
--
-- Requirements: PRD-AIC-014, PRD-AIC-015, PRD-AIC-018, PRD-AIC-019, PRD-AIC-023, PRD-AIC-026,
-- PRD-AIC-043, PRD-AIC-044, PRD-AIC-049, PRD-AIC-050, PRD-AIC-053, PRD-AIC-054, PRD-AIC-055, PRD-AIC-057.
-- Decisions: ADR-005, ADR-038, ADR-044 (superseded in part by ADR-075), ADR-075.
-- =============================================================================================

CREATE TABLE IF NOT EXISTS ai_invocation (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id           uuid        NOT NULL DEFAULT current_tenant_id(),
    capability          text        NOT NULL,
    principal_id        uuid,
    provider_id         uuid,
    model_identity      text        NOT NULL,
    prompt_version      text        NOT NULL,
    -- SHA-256 of the assembled request (system + user message). The cache key with the tenant (I11).
    prompt_hash         bytea       NOT NULL,
    -- What was retrieved to ground the call: record references, never the content.
    context_refs        jsonb       NOT NULL DEFAULT '[]'::jsonb,
    data_category       text        NOT NULL,
    injection_signals   integer     NOT NULL DEFAULT 0,
    outcome             text        NOT NULL,
    refusal_code        text,
    prompt_tokens       integer     NOT NULL DEFAULT 0,
    completion_tokens   integer     NOT NULL DEFAULT 0,
    latency_ms          integer     NOT NULL DEFAULT 0,
    cached              boolean     NOT NULL DEFAULT false,
    -- Retained only when the tenant's budget row says so.
    prompt_text         text,
    output_text         text,
    created_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_ai_invocation__category CHECK (data_category = ANY (ARRAY['NONE', 'AGGREGATE', 'RECORD'])),
    CONSTRAINT ck_ai_invocation__outcome CHECK (outcome = ANY (ARRAY['OK', 'REFUSED', 'ERROR', 'BUDGET', 'CACHED'])),
    CONSTRAINT ck_ai_invocation__hash CHECK (length(prompt_hash) = 32),
    CONSTRAINT ck_ai_invocation__tokens CHECK (prompt_tokens >= 0 AND completion_tokens >= 0 AND latency_ms >= 0)
);

SELECT apply_tenant_isolation('ai_invocation');

COMMENT ON TABLE ai_invocation IS
    'PRD-AIC-043, PRD-AIC-044, PRD-AIC-055. One row per model call or refused call: capability, who, which '
    'provider and model, prompt hash, context references, data category, outcome, tokens. Prompt and output '
    'text only when the tenant retains them. The cache reads the most recent OK row with the same hash.';

CREATE INDEX IF NOT EXISTS ix_ai_invocation__recent ON ai_invocation (tenant_id, created_at DESC);
COMMENT ON INDEX ix_ai_invocation__recent IS 'Serves: the usage panel and the per-period data-category report (PRD-AIC-044).';
CREATE INDEX IF NOT EXISTS ix_ai_invocation__cache ON ai_invocation (tenant_id, prompt_hash, created_at DESC) WHERE outcome = 'OK';
COMMENT ON INDEX ix_ai_invocation__cache IS 'Serves: PRD-AIC-055 — an identical request within the window returns the recorded output.';
CREATE INDEX IF NOT EXISTS ix_ai_invocation__principal_hour ON ai_invocation (tenant_id, principal_id, created_at DESC);
COMMENT ON INDEX ix_ai_invocation__principal_hour IS 'Serves: the per-principal rate limit (PRD-AIC-055).';

GRANT SELECT, INSERT ON ai_invocation TO app_runtime;
GRANT SELECT ON ai_invocation TO integrity_verifier;

CREATE TABLE IF NOT EXISTS ai_budget (
    tenant_id                        uuid        PRIMARY KEY DEFAULT current_tenant_id(),
    daily_token_budget               integer     NOT NULL DEFAULT 500000,
    -- High enough for one person to run a batch capability over a hundred records in an hour; the
    -- limit is against a refresh loop, not against work (PRD-AIC-055).
    per_principal_hourly_invocations integer     NOT NULL DEFAULT 600,
    cache_minutes                    integer     NOT NULL DEFAULT 15,
    retain_prompts                   boolean     NOT NULL DEFAULT false,
    retain_outputs                   boolean     NOT NULL DEFAULT true,
    updated_at                       timestamptz NOT NULL DEFAULT now(),
    updated_by                       uuid,

    CONSTRAINT ck_ai_budget__values CHECK (daily_token_budget BETWEEN 1000 AND 100000000
        AND per_principal_hourly_invocations BETWEEN 1 AND 10000 AND cache_minutes BETWEEN 0 AND 1440)
);

SELECT apply_tenant_isolation('ai_budget');

COMMENT ON TABLE ai_budget IS
    'PRD-AIC-053, PRD-AIC-054, PRD-AIC-055, PRD-AIC-043. The tenant''s AI spend ceiling per UTC day in tokens, the '
    'per-person hourly call limit, the identical-request cache window, and what is retained per invocation.';

GRANT SELECT, INSERT, UPDATE ON ai_budget TO app_runtime;
GRANT SELECT ON ai_budget TO integrity_verifier;

CREATE TABLE IF NOT EXISTS ai_evaluation_run (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id           uuid        NOT NULL DEFAULT current_tenant_id(),
    provider_id         uuid,
    model_identity      text        NOT NULL,
    prompt_version      text        NOT NULL,
    started_at          timestamptz NOT NULL DEFAULT now(),
    finished_at         timestamptz,
    scenarios           integer     NOT NULL DEFAULT 0,
    -- {measure: {pass, total, threshold, gate}} per DOC-10 §10.2.
    measures            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- Each failing scenario with the measure it failed and why.
    failures            jsonb       NOT NULL DEFAULT '[]'::jsonb,
    gate                text        NOT NULL DEFAULT 'RUNNING',
    run_by              uuid,

    CONSTRAINT ck_ai_evaluation_run__gate CHECK (gate = ANY (ARRAY['RUNNING', 'PASS', 'FAIL', 'ERROR']))
);

SELECT apply_tenant_isolation('ai_evaluation_run');

COMMENT ON TABLE ai_evaluation_run IS
    'PRD-AIC-049, PRD-AIC-050. A run of the evaluation harness against a provider and prompt version: the '
    'eight measures with their thresholds and the gate. Recorded against the change it evaluates.';

CREATE INDEX IF NOT EXISTS ix_ai_evaluation_run__recent ON ai_evaluation_run (tenant_id, started_at DESC);

GRANT SELECT, INSERT, UPDATE ON ai_evaluation_run TO app_runtime;
GRANT SELECT ON ai_evaluation_run TO integrity_verifier;

-- The suggestion kinds gain the DOC-10 §8 capabilities that had none.
-- The suggestion kinds gain the DOC-10 §8 capabilities that had none.
-- Guarded, because the migration runner re-applies every file: an unconditional DROP/ADD here fails the
-- moment a LATER migration has widened this list and rows of the wider kinds exist. The block replaces
-- the constraint only when the one in place is missing a kind this migration needs; a superset stands.
DO $$
DECLARE
    current_def text;
    wanted      text[] := ARRAY['RECURRING_WEAKNESS', 'REMEDIATION_DRAFT', 'DUPLICATE_CANDIDATE', 'SEVERITY_REVIEW', 'NARRATIVE_DRAFT', 'OWNERSHIP_ROUTING', 'INTAKE_CLASSIFICATION', 'COVERAGE_CAVEAT', 'EXCEPTION_BRIEF', 'CLASSIFICATION', 'SCORE_EXPLANATION', 'PRIORITY_SUGGESTION', 'POSTURE_ANSWER', 'DRAFT'];
    k           text;
    complete    boolean;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO current_def FROM pg_constraint
     WHERE conname = 'ck_ai_suggestion__kind' AND conrelid = 'ai_suggestion'::regclass;
    complete := current_def IS NOT NULL;
    IF complete THEN
        FOREACH k IN ARRAY wanted LOOP
            IF position(quote_literal(k) IN current_def) = 0 THEN
                complete := false;
            END IF;
        END LOOP;
    END IF;
    -- Absent (V046 could not create it, or an earlier re-application dropped it and failed to re-add):
    -- this is the widest list any row can carry, so it is created here.
    IF NOT complete THEN
        ALTER TABLE ai_suggestion DROP CONSTRAINT IF EXISTS ck_ai_suggestion__kind;
        EXECUTE 'ALTER TABLE ai_suggestion ADD CONSTRAINT ck_ai_suggestion__kind CHECK (suggestion_kind = ANY (ARRAY['
                || array_to_string(ARRAY(SELECT quote_literal(x) FROM unnest(wanted) x), ', ') || ']))';
    END IF;
END
$$;

-- Permission for the on-demand assistance surfaces (question answering, drafting). Not restricted:
-- every answer and draft is scoped to what the caller may already read, labelled generated, and
-- recorded; the person asking is the person authorized for the records. Granted where finding
-- reading is.
INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up)
VALUES ('aic.assist.use', 'aic',
        '{"en":"Ask the assistant and request drafts","vi":"Hỏi trợ lý và yêu cầu bản thảo"}'::jsonb, false, false)
ON CONFLICT (code) DO UPDATE
   SET is_restricted    = EXCLUDED.is_restricted,
       requires_step_up = EXCLUDED.requires_step_up,
       label_i18n       = EXCLUDED.label_i18n;

DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'aic.assist.use'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'vul.finding.read')
        ON CONFLICT DO NOTHING;

        INSERT INTO ai_budget (tenant_id) VALUES (t) ON CONFLICT DO NOTHING;

        INSERT INTO ai_capability (tenant_id, code, suggestion_kind, subject_kind, surface, data_category, max_per_run)
        VALUES
            -- PRD-AIC-015. The factor breakdown is the source; the model puts it in the reader's words and
            -- never restates the value (ADR-038). AGGREGATE: no finding text is needed to explain a score.
            (t, 'score.explanation', 'SCORE_EXPLANATION', 'FINDING', '/vulnerabilities', 'AGGREGATE', 15),
            -- PRD-AIC-018. An ordering with a reason per item, over scored findings and their service level
            -- state; divergence from score order is stated (PRD-AIC-045). AGGREGATE.
            (t, 'priority.suggestion', 'PRIORITY_SUGGESTION', 'ORG_NODE', '/vulnerabilities', 'AGGREGATE', 3),
            -- PRD-AIC-016 by another route: pairs the structural rules cannot see, judged from the text.
            -- RECORD, because it cannot work without titles and descriptions.
            (t, 'duplicate.semantic', 'DUPLICATE_CANDIDATE', 'FINDING', '/vulnerabilities', 'RECORD', 20),
            -- PRD-AIC-057. A question over the posture the caller may see, answered with citations.
            -- On demand; nothing is written to the ledger. RECORD so titles may be named in the answer.
            (t, 'posture.answer', 'POSTURE_ANSWER', 'ORG_NODE', '/overview', 'RECORD', 1),
            -- PRD-AIC-019. Drafts a person edits and then commits themselves. RECORD.
            (t, 'drafting.assist', 'DRAFT', 'ASSESSMENT_REQUEST', '/board', 'RECORD', 1)
        ON CONFLICT (tenant_id, code) DO NOTHING;
    END LOOP;
END
$$;
