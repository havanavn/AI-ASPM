-- =============================================================================
-- V046 — which AI capabilities exist, where each attaches, and what each may read.
--
-- WHAT WAS ALREADY HERE. `ai_suggestion` (V019) is the ledger ADR-005 requires and it is well made:
-- `grounding_refs` must be an array, `model_identity` and `prompt_version` are NOT NULL so a
-- suggestion can never exist without saying what produced it, promotion requires both a promoter and
-- a timestamp, and rejection requires a reason. Nothing needed rebuilding.
--
-- WHAT WAS MISSING. It held zero rows, and it had no readers. A ledger nothing writes to and nobody
-- can act on is ADR-005 satisfied on paper and absent in fact — the promotion step that makes an AI
-- output enter the record of what happened is a HUMAN action, and there was no way for a human to
-- take it. So this migration adds the catalogue of capabilities and the permissions for the decision,
-- and the application supplies the agents and the review surface.
--
-- =============================================================================
-- WHY A CATALOGUE RATHER THAN A LIST IN CODE
-- =============================================================================
--
-- CFG-AIC-001 makes provider selection, MODEL SELECTION PER CAPABILITY, permitted data categories and
-- consumption budgets tenant-configurable. All four are per-capability, so the capability has to be a
-- row. Two consequences that matter more than the storage:
--
--   * `data_category` is declared per capability, not per provider. "May this agent read the text of a
--     finding" is a different question for a remediation drafter (which cannot work without it) than
--     for an ownership router (which never needs it). Declaring it once at the provider would force
--     the most permissive answer onto every capability — risk surface 5, granted by convenience.
--
--   * `enabled` defaults to FALSE. ADR-044 defers AI capability from v1 and PRD-AIC-056 forbids
--     invoking one on view. A capability that arrived switched on would be a capability nobody chose,
--     spending a tenant's money and sending their findings to a third party because a migration ran.
--
-- WHY THE AGENTS SHIP AS RULES FIRST. Every capability below can be produced by a model or by
-- deterministic rules, and both write the same row through the same ledger. The rules version proves
-- the whole path — proposal, grounding, human decision, audit — without a provider, a budget or an
-- egress decision. `model_identity` says which one produced any given suggestion, and it is never
-- absent, so the two can never be confused after the fact.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Ownership routing joins the ledger's kinds.
--
-- The five kinds V019 enumerated cover interpretation and drafting. They do not cover the estate's
-- actual bottleneck: 240 of 248 open findings have no owner, and deciding who takes one is a judgement
-- over text that a person currently makes 240 times. It is a suggestion like any other — proposed,
-- grounded, promoted by a human — so it belongs in the same ledger rather than a parallel one.
-- -----------------------------------------------------------------------------
-- ADDED ONLY IF ABSENT, AND ONLY IF THE DATA STILL FITS. Neither guard is defensiveness.
--
-- Every migration re-runs on every container start. V049 later widened this same constraint to admit
-- two more kinds, and rows now carry them. An unconditional DROP-then-ADD here re-imposes the NARROWER
-- list on replay and Postgres refuses it — "check constraint is violated by some row" — so the runner
-- exits non-zero on every restart of a perfectly healthy deployment.
--
-- The absence check alone was not enough, and finding that out cost a second failed run: the first
-- failure had already executed the DROP before the ADD raised, so the constraint was genuinely gone
-- and the guard happily tried the narrow definition again. The exception handler covers that state.
--
-- The rule this settles: a migration may only re-impose a constraint it OWNS, and owning it means
-- being the last one to widen it. An earlier migration establishes the constraint on a first run and
-- stands aside forever after.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'ck_ai_suggestion__kind'
                      AND conrelid = 'ai_suggestion'::regclass) THEN
        BEGIN
            ALTER TABLE ai_suggestion ADD CONSTRAINT ck_ai_suggestion__kind CHECK (
                suggestion_kind = ANY (ARRAY[
                    'RECURRING_WEAKNESS', 'REMEDIATION_DRAFT', 'DUPLICATE_CANDIDATE',
                    'SEVERITY_REVIEW', 'NARRATIVE_DRAFT',
                    'OWNERSHIP_ROUTING', 'INTAKE_CLASSIFICATION']));
        EXCEPTION WHEN check_violation THEN
            -- Rows already carry a kind wider than this list, so a later migration owns the
            -- definition. Left for that one to re-establish rather than narrowed back onto data
            -- that a subsequent step legitimately created.
            RAISE NOTICE 'ck_ai_suggestion__kind left to a later migration: existing rows carry '
                         'kinds beyond this list';
        END;
    END IF;
END
$$;

COMMENT ON COLUMN ai_suggestion.suggestion_kind IS
    'What the suggestion is FOR. Product-fixed rather than tenant-configurable: each kind has its own '
    'promotion behaviour in code, so a tenant inventing an eighth would create suggestions nothing '
    'knows how to promote.';

-- -----------------------------------------------------------------------------
-- 2. The capability catalogue.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_capability (
    code             text        NOT NULL,
    tenant_id        uuid        NOT NULL DEFAULT current_tenant_id(),
    suggestion_kind  text        NOT NULL,
    subject_kind     text        NOT NULL,

    -- The route this capability's suggestions are reviewed on. Recorded as data because "where do I
    -- go to act on this" is a property of the capability, and a reviewer arriving from a digest needs
    -- the answer without reading the source.
    surface          text        NOT NULL,

    -- What the capability may be given. AGGREGATE — counts, severities, dates, identifiers. RECORD —
    -- the text of findings, which legitimately contains attacker-authored strings and secrets
    -- recovered from customer code (risk surface 5). NONE — it reads nothing beyond its subject id.
    data_category    text        NOT NULL DEFAULT 'AGGREGATE',

    -- NULL means "whichever provider is active". A capability may pin its own — a cheap model for
    -- drafting prose, a stronger one for judgement — which is the per-capability model selection
    -- CFG-AIC-001 asks for.
    provider_id      uuid,

    -- Off until somebody turns it on. See the header.
    enabled          boolean     NOT NULL DEFAULT false,

    -- A ceiling on suggestions per run, so a first enable cannot produce a thousand rows nobody will
    -- read. A review queue longer than a person will work is a review queue that gets ignored whole.
    max_per_run      integer     NOT NULL DEFAULT 25,

    updated_at       timestamptz NOT NULL DEFAULT now(),
    updated_by       uuid,

    CONSTRAINT pk_ai_capability PRIMARY KEY (tenant_id, code),
    CONSTRAINT ck_ai_capability__data_category CHECK (
        data_category = ANY (ARRAY['NONE', 'AGGREGATE', 'RECORD'])),
    CONSTRAINT ck_ai_capability__subject CHECK (
        subject_kind = ANY (ARRAY['ASSET', 'FINDING', 'ORG_NODE', 'ASSESSMENT_REQUEST'])),
    CONSTRAINT ck_ai_capability__batch CHECK (max_per_run BETWEEN 1 AND 200)
);

COMMENT ON TABLE ai_capability IS
    'Which AI capabilities exist, where each is reviewed, and what each may read. Rows rather than '
    'code because CFG-AIC-001 makes provider, model, permitted data category and budget '
    'tenant-configurable per capability. Every row ships disabled (ADR-044).';

CREATE INDEX IF NOT EXISTS ix_ai_capability__enabled
    ON ai_capability (tenant_id, enabled, code);
COMMENT ON INDEX ix_ai_capability__enabled IS
    'Serves: the settings page listing capabilities, and a run resolving the enabled ones.';

GRANT SELECT, INSERT, UPDATE ON ai_capability TO app_runtime;
GRANT SELECT ON ai_capability TO integrity_verifier;
SELECT apply_tenant_isolation('ai_capability');

-- -----------------------------------------------------------------------------
-- 3. The ledger needs indexes for the two ways it is read: a reviewer's queue, and one record's
--    suggestions shown beside it.
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS ix_ai_suggestion__queue
    ON ai_suggestion (tenant_id, state, suggestion_kind, generated_at DESC);
COMMENT ON INDEX ix_ai_suggestion__queue IS
    'Serves: the pending review queue, newest first, optionally narrowed to one kind.';

CREATE INDEX IF NOT EXISTS ix_ai_suggestion__subject
    ON ai_suggestion (tenant_id, subject_kind, subject_id, state);
COMMENT ON INDEX ix_ai_suggestion__subject IS
    'Serves: the suggestions shown against the record they are about.';

-- -----------------------------------------------------------------------------
-- 4. Two permissions, because reading a draft and putting it into the record are different powers.
--
-- Promotion is the act ADR-005 exists for: it is the moment a model''s output becomes something the
-- organization asserts. It is restricted, and it is the boundary an auditor will look for.
-- -----------------------------------------------------------------------------
INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up)
VALUES ('aic.suggestion.read', 'aic',
        '{"en":"Read AI suggestions","vi":"Xem đề xuất của AI"}'::jsonb, false, false),
       ('aic.suggestion.promote', 'aic',
        '{"en":"Accept or reject an AI suggestion","vi":"Duyệt hoặc từ chối đề xuất của AI"}'::jsonb,
        true, false),
       ('aic.capability.manage', 'aic',
        '{"en":"Enable AI capabilities","vi":"Bật tắt năng lực AI"}'::jsonb, true, true)
ON CONFLICT (code) DO NOTHING;

DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);

        -- Reading suggestions goes with reading findings: the queue is about findings and a reviewer
        -- who cannot see the finding cannot judge the suggestion.
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'aic.suggestion.read'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'vul.finding.read')
        ON CONFLICT DO NOTHING;

        -- Promoting goes with being able to change the finding it would change. A reviewer who may
        -- not set an owner must not be able to promote a suggestion that sets one.
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'aic.suggestion.promote'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'vul.finding.update')
        ON CONFLICT DO NOTHING;

        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'aic.capability.manage'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'cfg.ai.manage')
        ON CONFLICT DO NOTHING;

        -- -------------------------------------------------------------------------
        -- The capabilities themselves. Seeded so a tenant sees what exists and can choose; all
        -- disabled, and each declaring the narrowest data category that lets it work.
        -- -------------------------------------------------------------------------
        INSERT INTO ai_capability (tenant_id, code, suggestion_kind, subject_kind, surface,
                                   data_category, max_per_run)
        VALUES
            -- The estate's measured bottleneck. Needs no finding text: it routes on scope, asset
            -- ownership and history, so it declares AGGREGATE and cannot leak a payload.
            (t, 'ownership.routing', 'OWNERSHIP_ROUTING', 'FINDING', '/vulnerabilities',
             'AGGREGATE', 25),
            -- Cannot work without the finding's text — that is the thing being rewritten for a
            -- developer. The one capability whose RECORD category is inherent rather than convenient.
            (t, 'remediation.draft', 'REMEDIATION_DRAFT', 'FINDING', '/vulnerabilities', 'RECORD', 10),
            -- Proposes pairs for a human to confirm; the dedup decision itself stays deterministic
            -- (product principle 2).
            (t, 'duplicate.candidate', 'DUPLICATE_CANDIDATE', 'FINDING', '/vulnerabilities',
             'AGGREGATE', 25),
            -- Questions a grade rather than setting one. ADR-038: it never emits a number, it names
            -- the band it thinks is right and why.
            (t, 'severity.review', 'SEVERITY_REVIEW', 'FINDING', '/vulnerabilities', 'AGGREGATE', 15),
            -- Prose around figures that come from queries, never figures of its own (ADR-038).
            (t, 'narrative.draft', 'NARRATIVE_DRAFT', 'ORG_NODE', '/overview', 'AGGREGATE', 5),
            -- PRD-ASM-011: which specialist tracks a request needs, from its declared characteristics.
            (t, 'intake.classification', 'INTAKE_CLASSIFICATION', 'ASSESSMENT_REQUEST', '/board',
             'AGGREGATE', 10),
            -- What keeps failing across the estate. Reads classifications and counts, not text.
            (t, 'weakness.pattern', 'RECURRING_WEAKNESS', 'ORG_NODE', '/vulnerabilities',
             'AGGREGATE', 10)
        ON CONFLICT (tenant_id, code) DO NOTHING;
    END LOOP;
END
$$;
