-- =============================================================================
-- V002 — audit kernel: the append-only chained event log
--
-- Owner: platform-kernel/audit. DOC-04 section 20.1, DOC-14 sections 2 to 6.
-- DOC-04 calls this "the most constrained table in the schema" and it is the only one whose
-- shape is determined by a conflict rather than by a model:
--
--   Audit must be immutable and verifiable. Personal data must be erasable. Audit events
--   reference and sometimes contain personal data. Deleting events destroys the chain;
--   refusing erasure breaches obligation.
--
-- Resolution (ADR-034, CON-DAT-027): hash the payload, chain the hash, erase the payload.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. audit_event
--
-- Range partitioned monthly by occurred_at from the outset (DOC-04 section 20.1). "From the
-- outset" is load-bearing: converting a populated table to partitioned is a rewrite, and
-- CON-DAT-022 forbids a blocking operation on a large table.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS audit_event (
    id                    uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id             uuid        NOT NULL,
    sequence              bigint      NOT NULL,          -- monotonic per tenant
    event_type            text        NOT NULL,
    occurred_at           timestamptz NOT NULL,          -- partition key
    actor_id              uuid,
    actor_type            text        NOT NULL,
    on_behalf_of_id       uuid,                          -- delegation, SEC-AUZ-044
    -- SEC-AUD-004 requires that where automation acts, "the rule and its owning principal must both
    -- be recoverable or an automated escalation has no traceable origin". DOC-14 section 2's envelope
    -- lists automation_rule_id; DOC-04 section 20.1's table does not. The column is added here rather
    -- than putting the value in the payload, because the payload is ERASABLE and erasing it would
    -- destroy the automation attribution SEC-AUD-004 requires to survive. Reported to the corpus
    -- owner as a DOC-04 omission; no requirement is changed by adding it.
    automation_rule_id    uuid,
    break_glass_ref       uuid,                          -- SEC-TEN-030
    object_kind           text,
    object_id             uuid,
    outcome               text        NOT NULL,
    -- Full fidelity, and never returned to a client: a differentiated denial reason discloses
    -- whether an object exists (SEC-AUZ-020, DOC-07 section 8.2).
    denial_reason         text,
    -- Scope as it was at the time (PRD-AUD-004). Recorded rather than resolved on read, because
    -- the tree changes and SEC-AUZ-028 requires historical evaluation to use the recorded
    -- descriptor.
    scope_node_id         uuid,
    scope_ancestor_path   uuid[],
    scope_hierarchy_ver   bigint,
    -- Integrity. SEC-AUD-011 requires the canonical serialization to be versioned and the
    -- version recorded per event, so the format can change without invalidating history.
    canonical_version     int         NOT NULL DEFAULT 1,
    payload_hash          bytea       NOT NULL,
    prev_chain_hash       bytea       NOT NULL,
    chain_hash            bytea       NOT NULL,
    -- Erasure
    payload_erased_at     timestamptz,
    payload_erasure_basis text,

    CONSTRAINT pk_audit_event PRIMARY KEY (id, occurred_at),
    CONSTRAINT ck_audit_event__actor_type CHECK (actor_type IN
        ('USER', 'SERVICE', 'AUTOMATION', 'SYSTEM')),
    CONSTRAINT ck_audit_event__outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED')),
    CONSTRAINT ck_audit_event__erasure_basis CHECK
        (payload_erased_at IS NULL OR payload_erasure_basis IS NOT NULL),
    CONSTRAINT ck_audit_event__sequence CHECK (sequence >= 0),
    CONSTRAINT ck_audit_event__object_pair CHECK
        ((object_kind IS NULL) = (object_id IS NULL)),
    -- SEC-AUD-004: an AUTOMATION actor without its rule is an untraceable automated action.
    CONSTRAINT ck_audit_event__automation_rule CHECK
        (actor_type <> 'AUTOMATION' OR automation_rule_id IS NOT NULL),
    -- SEC-AUD-011: the canonical serialization version is recorded per event so the format can
    -- change without invalidating history.
    CONSTRAINT ck_audit_event__canonical_version CHECK (canonical_version >= 1)
) PARTITION BY RANGE (occurred_at);

-- UNIQUE (tenant_id, sequence) per DOC-04. On a partitioned table a unique index must include
-- the partition key, so the constraint is (tenant_id, sequence, occurred_at). Chain forking is
-- prevented by SEC-AUD-014's per-tenant serialization of the chain head in the same
-- transaction, not by this index alone — recorded because the index name suggests otherwise.
CREATE UNIQUE INDEX IF NOT EXISTS ux_audit_event__sequence
    ON audit_event (tenant_id, sequence, occurred_at);

CREATE INDEX IF NOT EXISTS ix_audit_event__object
    ON audit_event (tenant_id, object_kind, object_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_event__actor
    ON audit_event (tenant_id, actor_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_audit_event__type_time
    ON audit_event (tenant_id, event_type, occurred_at DESC);
-- Enumeration detection (SEC-PLT-003): sustained denials from one principal.
CREATE INDEX IF NOT EXISTS ix_audit_event__denied
    ON audit_event (tenant_id, actor_id, occurred_at DESC) WHERE outcome = 'DENIED';
-- Break-glass activity, visible to the tenant (SEC-TEN-030).
CREATE INDEX IF NOT EXISTS ix_audit_event__break_glass
    ON audit_event (tenant_id, occurred_at DESC) WHERE break_glass_ref IS NOT NULL;
-- Scope-filtered audit search. Audit read is itself a disclosure surface and must be
-- scope-constrained (DOC-04 section 20.1).
CREATE INDEX IF NOT EXISTS ix_audit_event__scope_subtree
    ON audit_event USING gin (scope_ancestor_path);

-- -----------------------------------------------------------------------------
-- 2. audit_event_payload — aligned partitioning (CON-DAT-024)
--
-- Separate table because the payload is erasable and the metadata is not (INV-AUD-03).
-- Partitioned identically because the join is on every detailed audit read.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS audit_event_payload (
    event_id    uuid        NOT NULL,
    tenant_id   uuid        NOT NULL,
    occurred_at timestamptz NOT NULL,     -- partition key, mirrors audit_event
    payload     jsonb       NOT NULL,
    CONSTRAINT pk_audit_event_payload PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX IF NOT EXISTS ix_audit_payload__erasure
    ON audit_event_payload (tenant_id);

-- No foreign key to audit_event: ADR-030 forbids foreign keys across module boundaries, and
-- although both tables are audit-owned, a cross-partition FK on a monthly-partitioned pair adds
-- a constraint check to the hottest insert path in the platform. Integrity is maintained in the
-- domain layer with reconciliation, per ADR-030.

-- -----------------------------------------------------------------------------
-- 3. audit_chain_checkpoint — CON-DAT-028, INV-AUD-02
--
-- Anchored outside the platform's control. DOC-14 section 5: verification material stored only
-- alongside the events it protects is defeated by the same adversary who could alter them —
-- including a compromise of the platform itself.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS audit_chain_checkpoint (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id           uuid        NOT NULL,
    sequence            bigint      NOT NULL,
    chain_hash          bytea       NOT NULL,
    checkpointed_at     timestamptz NOT NULL DEFAULT now(),
    external_anchor_ref text,
    CONSTRAINT ux_audit_chain_checkpoint__seq UNIQUE (tenant_id, sequence)
);

-- -----------------------------------------------------------------------------
-- 4. Isolation
-- -----------------------------------------------------------------------------

SELECT apply_tenant_isolation('audit_event');
SELECT apply_tenant_isolation('audit_event_payload');
SELECT apply_tenant_isolation('audit_chain_checkpoint');

-- -----------------------------------------------------------------------------
-- 5. Append-only enforcement — INV-AUD-01, SEC-AUD-013, CON-DAT-027
--
-- "No mechanism MUST exist to modify or delete an audit event: not through the application, the
-- API, administrative tooling, or an operator interface. Deletion MUST be possible only by
-- partition drop under retention." (SEC-AUD-013)
--
-- Expressed as the ABSENCE of grants rather than as a trigger. A trigger can be disabled by the
-- table owner; a grant that was never made cannot be exercised. DOC-04 section 20.1: "No UPDATE
-- or DELETE grant to app_runtime on audit_event."
--
-- Note what is deliberately NOT granted anywhere, to any role:
--   - UPDATE on audit_event                    (no role, at any privilege)
--   - DELETE on audit_event                    (no role; retention is partition drop)
--   - UPDATE on audit_event_payload            (erasure removes the row, it does not edit it)
-- -----------------------------------------------------------------------------

REVOKE ALL ON audit_event FROM PUBLIC;
REVOKE ALL ON audit_event_payload FROM PUBLIC;
REVOKE ALL ON audit_chain_checkpoint FROM PUBLIC;

GRANT SELECT, INSERT ON audit_event TO app_runtime;
GRANT SELECT, INSERT ON audit_event_payload TO app_runtime;
GRANT SELECT, INSERT ON audit_chain_checkpoint TO app_runtime;

GRANT SELECT ON audit_event, audit_event_payload, audit_chain_checkpoint TO integrity_verifier;

-- The erasure role holds the ONLY DELETE grant on the payload, and none on the event.
-- The chain covers payload_hash, not the payload, so deleting a payload row leaves every
-- chain_hash unchanged and every link verifiable (CON-DAT-027, ADR-034).
GRANT SELECT, DELETE ON audit_event_payload TO payload_eraser;
-- The erasure marker on the event itself must be settable. This is the one UPDATE grant in the
-- audit design and it is confined to two columns, which is why it is column-scoped rather than
-- table-scoped: a table-wide UPDATE grant here would permit rewriting chain_hash.
GRANT UPDATE (payload_erased_at, payload_erasure_basis) ON audit_event TO payload_eraser;

-- migration_runner may create and detach partitions. It is not granted UPDATE or DELETE on
-- rows: partition management is DDL, not DML, and conflating them would give the migration
-- credential the ability to rewrite history.
GRANT SELECT, INSERT ON audit_event, audit_event_payload, audit_chain_checkpoint
    TO migration_runner;

-- -----------------------------------------------------------------------------
-- 6. Partition provisioning
--
-- OPS-DEP-011: range partition creation is automated ahead of need with a configurable lead
-- time, and a missing future partition alerts before it would be required. A missing partition
-- rejects inserts, and for audit_event that fails every audited operation under CON-PLT-021 —
-- a total write outage from an omitted maintenance task (CON-DAT-025).
--
-- OPS-DEP-012 requires the sizing basis to be recorded with the partition counts.
--
--   *** SIZING BASIS, RECORDED PER OPS-DEP-012 ***
--   Working assumption (OQ-015): the Medium reference profile of DOC-01 section 12.1, with
--   headroom to Extra large, exactly as DOC-15 section 5.2 states. Medium is 5,000,000 audit
--   events per month; Extra large is 150,000,000.
--   Monthly RANGE partitioning is unaffected by the answer to OQ-015 — the partition count
--   grows with time, not with volume, so this table carries no irreversible sizing decision.
--   *** The irreversible decision is the HASH partition count on `finding`,
--   `finding_asset_impact` and `component_entry` (CON-DAT-035, OPS-DEP-012). Corrected: an
--   earlier version of this note said "prompts 5 and 11". `asset` is NOT partitioned at all
--   (DOC-04 section 11.3.2: "None — 100,000 rows at Extra large"), so prompt 5 sets no
--   irreversible count. The gated tables arrive with vulnerability management and composition
--   analysis. OQ-015 must be answered before those, and blocks neither this migration nor V005. ***
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION ensure_audit_partitions(lead_months int DEFAULT 3) RETURNS int
    LANGUAGE plpgsql
AS $$
DECLARE
    m           date;
    created     int := 0;
    part_name   text;
BEGIN
    IF lead_months < 1 THEN
        RAISE EXCEPTION 'lead time must be at least one month (OPS-DEP-011)';
    END IF;

    FOR i IN 0..lead_months LOOP
        m := date_trunc('month', now())::date + (i || ' months')::interval;

        FOREACH part_name IN ARRAY ARRAY['audit_event', 'audit_event_payload'] LOOP
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I '
                'FOR VALUES FROM (%L) TO (%L)',
                part_name || '_' || to_char(m, 'YYYY_MM'),
                part_name,
                m,
                (m + interval '1 month')::date);
            -- A partition created after the parent was isolated is NOT isolated by inheritance: RLS on
            -- the parent does not protect a direct query against a child. Without this call, every
            -- month's new partition would be a fresh cross-tenant read path — a hole that opens on a
            -- schedule. Found by the verification suite; see the note in apply_tenant_isolation.
            PERFORM apply_tenant_isolation(
                format('%I', part_name || '_' || to_char(m, 'YYYY_MM'))::regclass);
            created := created + 1;
        END LOOP;
    END LOOP;

    RETURN created;
END
$$;

GRANT EXECUTE ON FUNCTION ensure_audit_partitions(int) TO migration_runner;

-- Provision the current month plus three, so the schema is usable immediately.
SELECT ensure_audit_partitions(3);

-- The alerting half of OPS-DEP-011: months of runway remaining. A monitor asserts this stays
-- above the lead time; the function existing is not the control, the alert on it is.
CREATE OR REPLACE FUNCTION audit_partition_runway_months() RETURNS int
    LANGUAGE sql STABLE
AS $$
    SELECT COALESCE(
        GREATEST(0, (
            SELECT count(*)::int FROM pg_class c
             JOIN pg_inherits i ON i.inhrelid = c.oid
             JOIN pg_class parent ON parent.oid = i.inhparent
            WHERE parent.relname = 'audit_event'
              AND c.relname >= 'audit_event_' || to_char(now(), 'YYYY_MM')
        )), 0);
$$;

GRANT EXECUTE ON FUNCTION audit_partition_runway_months()
    TO migration_runner, integrity_verifier, app_runtime;
