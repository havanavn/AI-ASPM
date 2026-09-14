-- =============================================================================================
-- V081 — the copilot's conversations, stored on the server.
--
-- WHAT THIS IS FOR. `posture.answer` (V049, V078) answers one question and forgets it. A person
-- working a portfolio asks a sequence — "what risk do our products carry", then "and this
-- application", then "has it been assessed", then "who is it assigned to" — and every question after
-- the first depends on what the one before it settled. Without a transcript the second question has
-- no referent and the answer is a guess.
--
-- WHY THE TRANSCRIPT IS ON THE SERVER AND NOT IN THE BROWSER. It would have been less work to send
-- the history back with each question. That makes the prior ASSISTANT turns client-supplied text,
-- and a client can put anything in them: a forged "assistant" turn saying the caller is an
-- administrator, or that a fact pack is unavailable, is instruction text arriving inside what the
-- model is told to trust. PRD-AIC-037 keeps attacker-authored content behind a fence; history that
-- round-trips through the browser walks around it. The rows here are written by the platform, read
-- back by the platform, and the browser never gets to assert what was said.
--
-- WHY A CONVERSATION BELONGS TO ONE PERSON. Every answer is composed from facts gathered under the
-- asker's own scope (PRD-AIC-030). A transcript is therefore a record at that person's reach, and
-- sharing one would disclose figures the reader may not see — the aggregate-disclosure path
-- SEC-AUZ-026 names. Tenant isolation is the policy; the per-principal predicate is in every query,
-- and `ix_ai_conversation__mine` is the index that makes it the cheap path.
--
-- WHY THE FOCUS IS A COLUMN. "This application" has to resolve to something. The platform resolves
-- names to identifiers itself — the model is never asked for an identifier, because a model that can
-- name a row can name one the caller cannot see — and what it resolved is kept here so the next
-- question inherits it. A soft reference (ADR-030): `asset` belongs to another module, and a
-- retired application leaves a conversation that says so rather than a conversation that vanishes.
--
-- WHY MESSAGES ARE RETAINED WHEN A CONVERSATION IS CLEARED. `lifecycle_state` rather than a DELETE,
-- for the reason a cancelled plan window is retained (ADR-065): a conversation somebody dropped and
-- a conversation that never existed are different facts, and the invocation records (PRD-AIC-043)
-- that metering and data governance read are already keyed to these turns.
--
-- Requirements: PRD-AIC-030, PRD-AIC-031, PRD-AIC-033, PRD-AIC-036, PRD-AIC-037, PRD-AIC-043,
-- PRD-AIC-048, PRD-AIC-057, PRD-AIC-058, SEC-AUZ-016, SEC-AUZ-026.
-- Decisions: ADR-005, ADR-027, ADR-030, ADR-038, ADR-075, ADR-077.
-- =============================================================================================

CREATE TABLE IF NOT EXISTS ai_conversation (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL DEFAULT current_tenant_id(),
    -- Whose conversation. Not a scope descriptor: the facts inside were gathered under this
    -- person's reach at the time each answer was composed, so the row is theirs and nobody else's.
    principal_id    uuid        NOT NULL,
    -- The first question, trimmed. A generated title would be a model call per conversation for a
    -- string nobody reads twice.
    title           text        NOT NULL,
    -- What "this application" refers to, resolved by the platform from the caller's own inventory.
    -- Soft (ADR-030); the read path renders a dangling reference as such.
    focus_asset_id  uuid,
    lifecycle_state text        NOT NULL DEFAULT 'ACTIVE',
    message_count   integer     NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_ai_conversation__state CHECK (lifecycle_state = ANY (ARRAY['ACTIVE', 'CLEARED'])),
    CONSTRAINT ck_ai_conversation__title CHECK (length(title) BETWEEN 1 AND 200),
    CONSTRAINT ck_ai_conversation__count CHECK (message_count >= 0)
);

SELECT apply_tenant_isolation('ai_conversation');

COMMENT ON TABLE ai_conversation IS
    'PRD-AIC-058. One copilot conversation, owned by one principal. The transcript lives here rather than '
    'in the browser so that prior assistant turns cannot be forged by the client and re-enter the prompt as '
    'instructions (PRD-AIC-037). focus_asset_id is what "this application" resolves to, resolved by the '
    'platform and never supplied by the model.';

CREATE INDEX IF NOT EXISTS ix_ai_conversation__mine
    ON ai_conversation (tenant_id, principal_id, updated_at DESC)
    WHERE lifecycle_state = 'ACTIVE';
COMMENT ON INDEX ix_ai_conversation__mine IS
    'Serves: the copilot panel listing this person''s recent conversations, newest first.';

GRANT SELECT, INSERT, UPDATE ON ai_conversation TO app_runtime;
GRANT SELECT ON ai_conversation TO integrity_verifier;

CREATE TABLE IF NOT EXISTS ai_conversation_message (
    id               uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id        uuid        NOT NULL DEFAULT current_tenant_id(),
    conversation_id  uuid        NOT NULL REFERENCES ai_conversation (id) ON DELETE CASCADE,
    ordinal          integer     NOT NULL,
    role             text        NOT NULL,
    content          text        NOT NULL,
    -- The F-numbers the answer cited, and the facts it was given. Kept with the message because a
    -- citation the reader cannot follow a week later is not a citation (PRD-AIC-033), and because
    -- re-deriving the facts would answer a different question — the estate has moved since.
    citations        jsonb       NOT NULL DEFAULT '[]'::jsonb,
    facts            jsonb       NOT NULL DEFAULT '[]'::jsonb,
    -- Which fact packs the question was routed to, and which were withheld for want of permission.
    -- PRD-AIC-048: an unavailable capability says so; an answer narrower than the question deserves
    -- to say which part of the estate it could not look at.
    topics           jsonb       NOT NULL DEFAULT '[]'::jsonb,
    withheld         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    model_identity   text,
    prompt_version   text,
    -- The invocation this turn produced, so metering, the data-category report and the transcript
    -- are one join apart rather than correlated by timestamp.
    invocation_id    uuid,
    refusal_code     text,
    created_at       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_ai_conv_message__role CHECK (role = ANY (ARRAY['USER', 'ASSISTANT'])),
    CONSTRAINT ck_ai_conv_message__ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_ai_conv_message__content CHECK (length(content) <= 40000),
    CONSTRAINT uq_ai_conv_message__ordinal UNIQUE (conversation_id, ordinal)
);

SELECT apply_tenant_isolation('ai_conversation_message');

COMMENT ON TABLE ai_conversation_message IS
    'PRD-AIC-058. One turn. A USER row is what the person typed, retained verbatim so the answer above it '
    'can be read against the question actually asked; an ASSISTANT row carries the answer, the facts it was '
    'given, the citations it made, the packs it read and the packs withheld for want of permission, and the '
    'invocation record it produced (PRD-AIC-043).';

CREATE INDEX IF NOT EXISTS ix_ai_conv_message__thread
    ON ai_conversation_message (tenant_id, conversation_id, ordinal);
COMMENT ON INDEX ix_ai_conv_message__thread IS
    'Serves: replaying one conversation in order, and building the prompt history for the next turn.';

GRANT SELECT, INSERT ON ai_conversation_message TO app_runtime;
GRANT SELECT ON ai_conversation_message TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- The capability row, per existing tenant.
--
-- RECORD, not AGGREGATE: an answer about "this application" names it, and an answer about a finding
-- may quote its title — which is attacker-authored text, and is why the fence exists. Enabled,
-- because a copilot switched off is a launcher that explains it does nothing; the ordinary controls
-- (budget, the per-principal hourly limit, the capability switch) all still apply.
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        INSERT INTO ai_capability (tenant_id, code, suggestion_kind, subject_kind, surface,
                                   data_category, max_per_run, enabled)
        VALUES (t, 'copilot.chat', 'POSTURE_ANSWER', 'ORG_NODE', '/copilot', 'RECORD', 1, true)
        ON CONFLICT (tenant_id, code) DO NOTHING;
    END LOOP;
    PERFORM set_config('aspm.current_tenant', '', true);
END $$;
