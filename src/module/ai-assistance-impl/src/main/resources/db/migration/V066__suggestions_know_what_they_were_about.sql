-- =============================================================================================
-- V066 — a suggestion records the version of the record it was about.
--
-- WHAT WAS MISSING. The ledger stores what a suggestion says, what it rests on, and what produced
-- it. It does not store WHEN, in the subject's own history, it was true. So a suggestion generated
-- against a finding that has since been re-severitied, reassigned, fixed and closed sits in the
-- review queue looking exactly like one generated a minute ago, and a reviewer accepting it accepts
-- a claim about a state that no longer exists.
--
-- Measured on this deployment: 44 suggestions, all generated on 2026-08-11, all still PENDING, and
-- nothing in the schema or the interface can say which of them are still about the record they
-- describe.
--
-- WHY A VERSION AND NOT A TIMESTAMP. `generated_at` is already stored and cannot answer the
-- question: a suggestion from Monday about a record nobody has touched since is perfectly current,
-- and one from an hour ago about a record edited since is not. Age is a proxy that is wrong in both
-- directions. `row_version` is the subject's own counter, incremented by every write path that
-- changes it, so comparing it answers "has this changed since" exactly rather than approximately.
--
-- WHY NULLABLE, AND WHY THE 44 EXISTING ROWS ARE NOT BACKFILLED. Backfilling them with the
-- subject's CURRENT version would assert that each was generated against the state the record is in
-- now, which nobody knows to be true — and the platform's first principle is that measured-and-clean
-- must stay distinguishable from not-measured. NULL means "generated before the ledger recorded
-- this", the interface says so in those words, and the ambiguity dies out on its own as those rows
-- are decided.
--
-- WHAT THIS ENABLES, in the application: a stale suggestion is marked in the queue, promotion of one
-- is refused (rejection is not — a reviewer may always dismiss), and an agent re-running over a
-- subject whose pending suggestion has gone stale WITHDRAWS the old one instead of being blocked by
-- it. That last one matters: the deduplication guard in SuggestionLedger.propose refuses to write a
-- second pending suggestion for the same subject and kind, so without a way to retire the old one
-- the queue would keep the stale claim and never acquire the current one.
--
-- WHY `WITHDRAWN` RATHER THAN A NEW STATE. It is already in the state check constraint of V0xx and
-- has had no writer. It means precisely this — the suggestion was neither accepted nor rejected by a
-- person, it stopped applying — and adding a fifth state for that would be two names for one fact.
-- =============================================================================================

ALTER TABLE ai_suggestion
    ADD COLUMN IF NOT EXISTS subject_row_version integer;

COMMENT ON COLUMN ai_suggestion.subject_row_version IS
    'The subject''s row_version when this suggestion was generated. NULL for rows written before '
    'this column existed, which means "unknown", never "current". Compared against the subject''s '
    'row_version now to decide whether the suggestion is still about the record it describes.';

-- The queue reads pending suggestions and joins each to its subject to test staleness. Without this
-- the read is a sequential scan of the ledger per page load; with it the pending set is found
-- directly. Partial on the state, because PENDING is the only state the queue reads and the decided
-- rows are the ones that accumulate forever.
CREATE INDEX IF NOT EXISTS ix_ai_suggestion__pending_subject
    ON ai_suggestion (tenant_id, subject_kind, subject_id)
    WHERE state = 'PENDING';
