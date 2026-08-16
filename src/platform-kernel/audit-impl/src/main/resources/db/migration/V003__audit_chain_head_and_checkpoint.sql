-- =============================================================================
-- V003 — audit chain head, and the checkpoint shape SEC-AUD-015 requires
--
-- Two additions to DOC-04 section 20.1. Neither changes a requirement; both implement one that
-- section's table cannot carry. Reported to the corpus owner alongside the automation_rule_id
-- omission recorded in V002.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. audit_chain_head — the serialization point SEC-AUD-014 requires
--
-- "Chain computation MUST occur in the same transaction as the event insert, and a concurrent
-- insert MUST NOT produce two events with the same sequence or a forked chain. Serialization of
-- the chain head per tenant is required, which is a deliberate write-throughput cost."
--
-- DOC-04 section 20.1 specifies no such table, so the head would have to be derived as
-- max(sequence) per tenant. Two reasons that does not work:
--
--   1. It cannot be locked. SELECT ... FOR UPDATE does not apply to an aggregate, so two
--      concurrent writers both read the same max and both write sequence n+1. SEC-AUD-014 calls
--      the result "undetectable as tampering and unrepairable".
--   2. It would not meet the budget. audit_event is range-partitioned monthly and reaches
--      150,000,000 rows per month at the Extra large profile, so max(sequence) is an index scan
--      per partition on the hottest write path in the platform. NFR-AUD-001 budgets 15 ms at p95
--      for the audit write.
--
-- A single row per tenant, locked FOR UPDATE, gives both: mutual exclusion and an O(1) read.
-- DOC-14 section 4.2 already accepts the throughput cost — "per-tenant chain head serialization
-- bounds audit write throughput to one event at a time per tenant" — and records that a single
-- tenant's audited operation rate is well below the contention threshold at DOC-01 section 12.1
-- volumes.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS audit_chain_head (
    tenant_id       uuid        PRIMARY KEY,
    -- The sequence of the most recently written event. -1 before the first, so that the first
    -- event takes sequence 0 and SEC-AUD-002's gapless-from-zero property holds without a
    -- special case in the writer.
    last_sequence   bigint      NOT NULL DEFAULT -1,
    -- chain_hash of the most recent event, or the genesis where none has been written.
    last_chain_hash bytea       NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_audit_chain_head__sequence CHECK (last_sequence >= -1)
);

SELECT apply_tenant_isolation('audit_chain_head');

-- The application appends events, so it must be able to advance the head. It must NOT be able to
-- move it backwards or delete it: either would permit rewriting a range of history and then
-- continuing the chain consistently from the rewritten point.
GRANT SELECT, INSERT, UPDATE (last_sequence, last_chain_hash, updated_at)
    ON audit_chain_head TO app_runtime;
GRANT SELECT ON audit_chain_head TO integrity_verifier;
GRANT SELECT, INSERT, UPDATE ON audit_chain_head TO migration_runner;

-- Monotonicity is enforced by a trigger rather than a CHECK, because a CHECK cannot see the prior
-- value. This is one of the engine-enforced guards of DOC-04 section 22.4: domain-only enforcement
-- would lose the survive-a-defect property.
CREATE OR REPLACE FUNCTION audit_chain_head_forward_only() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.last_sequence < OLD.last_sequence THEN
        RAISE EXCEPTION
            'audit chain head cannot move backwards: % -> % (SEC-AUD-013, INV-AUD-01)',
            OLD.last_sequence, NEW.last_sequence
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS tr_audit_chain_head__forward_only ON audit_chain_head;
CREATE TRIGGER tr_audit_chain_head__forward_only
    BEFORE UPDATE ON audit_chain_head
    FOR EACH ROW EXECUTE FUNCTION audit_chain_head_forward_only();

-- -----------------------------------------------------------------------------
-- 2. audit_chain_checkpoint — the columns SEC-AUD-015 requires
--
-- DOC-04 section 20.1 gives (tenant_id, sequence, chain_hash, checkpointed_at,
-- external_anchor_ref). DOC-14 section 5 gives (tenant_id, sequence, chain_hash, event_count,
-- checkpointed_at, anchor_target, anchor_reference, anchor_confirmed_at).
--
-- The DOC-14 shape is the one that satisfies its own requirement: SEC-AUD-015 requires the anchor
-- reference "recorded and confirmation tracked", and a single external_anchor_ref column cannot
-- track confirmation — a reference exists as soon as submission is attempted, whereas confirmation
-- is a later, separate fact. Without the distinction, SEC-AUD-016's "anchor failure MUST alert"
-- has nothing to alert on, because an unconfirmed anchor is indistinguishable from a confirmed one.
--
-- anchor_target is likewise required rather than cosmetic: DOC-14 section 5 permits an
-- operator-attested offline record in air-gapped deployment and requires it to "be labelled as
-- such in the verification report rather than presented as equivalent". A report cannot label what
-- the row does not record.
-- -----------------------------------------------------------------------------

ALTER TABLE audit_chain_checkpoint
    ADD COLUMN IF NOT EXISTS event_count         bigint,
    ADD COLUMN IF NOT EXISTS anchor_target       text,
    ADD COLUMN IF NOT EXISTS anchor_reference    text,
    ADD COLUMN IF NOT EXISTS anchor_confirmed_at timestamptz;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_audit_chain_checkpoint__target') THEN
        ALTER TABLE audit_chain_checkpoint ADD CONSTRAINT ck_audit_chain_checkpoint__target
            CHECK (anchor_target IS NULL OR anchor_target IN
                ('TENANT_APPEND_ONLY_STORAGE', 'CUSTOMER_LOG_SERVICE',
                 'DISTRIBUTED_TIMESTAMP', 'OPERATOR_ATTESTED_OFFLINE'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'ck_audit_chain_checkpoint__confirmation') THEN
        -- A confirmation without a reference is unverifiable; the pair travels together.
        ALTER TABLE audit_chain_checkpoint ADD CONSTRAINT ck_audit_chain_checkpoint__confirmation
            CHECK (anchor_confirmed_at IS NULL OR anchor_reference IS NOT NULL);
    END IF;
END
$$;

-- Unconfirmed checkpoints older than the alerting threshold. The signal SEC-AUD-016 alerts on and
-- OPS-DEP-045 pages for: "audit chain verification status and anchor confirmation MUST be
-- monitored, and a verification failure MUST page rather than alert".
CREATE OR REPLACE FUNCTION audit_anchor_gaps(older_than interval DEFAULT interval '24 hours')
    RETURNS TABLE (tenant_id uuid, sequence bigint, checkpointed_at timestamptz, anchor_target text)
    LANGUAGE sql STABLE
AS $$
    SELECT c.tenant_id, c.sequence, c.checkpointed_at, c.anchor_target
      FROM audit_chain_checkpoint c
     WHERE c.anchor_confirmed_at IS NULL
       AND c.checkpointed_at < now() - older_than
     ORDER BY c.checkpointed_at;
$$;

GRANT EXECUTE ON FUNCTION audit_anchor_gaps(interval)
    TO migration_runner, integrity_verifier, app_runtime;

-- -----------------------------------------------------------------------------
-- 3. The scope descriptor immutability trigger on audit_event
--
-- *** GAP FOUND BY RUNNING scope_descriptor_gaps() AGAINST A LIVE ENGINE. ***
-- audit_event declares its own scope columns natively (DOC-04 section 20.1) rather than receiving them
-- from add_scope_descriptor(), so it had the columns and not the trigger. The append-only grants make
-- the descriptor unwritable by the application in practice, which is stronger than a trigger — but
-- payload_eraser holds a column-scoped UPDATE, and "stronger in practice" is the kind of reasoning
-- that stops being true when a grant is widened.
--
-- Applying the trigger costs nothing (it rejects only scope-column changes, so the erasure marker
-- update is unaffected) and makes CON-DAT-009 uniform across every scope-bearing table. Uniform is
-- what lets the conformance query mean something.
-- -----------------------------------------------------------------------------

DROP TRIGGER IF EXISTS tr_audit_event__immutable_scope ON audit_event;
CREATE TRIGGER tr_audit_event__immutable_scope
    BEFORE UPDATE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_scope_descriptor_change();
