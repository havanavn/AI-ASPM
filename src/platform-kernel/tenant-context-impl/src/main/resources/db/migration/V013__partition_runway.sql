-- =============================================================================
-- V013 — the alerting half of OPS-DEP-011, generically
--
-- "Range partition creation MUST be automated ahead of need with a configurable lead time, and a
-- missing future partition MUST alert BEFORE it would be required."
--
-- The automation half was built as each table arrived: ensure_audit_partitions,
-- ensure_transition_log_partitions, ensure_risk_score_partitions,
-- ensure_automation_execution_partitions. The alerting half was built once, for audit_event only
-- (audit_partition_runway_months in V002). The other four range-partitioned tables have
-- provisioning and no alert.
--
-- Found while building the deployment model of prompt 19, not by reading the migrations. Each one
-- is correct on its own; the gap is only visible when the set is enumerated.
--
-- What OPS-DEP-011's rationale says the gap costs: "A missing partition rejects inserts. For
-- audit_event that fails every audited operation under CON-PLT-021 — a total write outage from an
-- omitted maintenance task." audit_event is the one table that already had the alert. The four
-- without it are the transition log (whose loss is unreconstructable per DOC-15 section 4),
-- risk_score, automation_execution, and audit_event_payload.
--
-- Owned by tenant-context-impl because it is a generic catalogue helper alongside
-- apply_tenant_isolation, and because it must not belong to any one of the modules it reports on.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. range_partition_runway_months — months of provisioned future, for one parent
--
-- Reads pg_class bounds rather than parsing partition names. A name-derived answer is wrong for
-- any table not following the _YYYY_MM convention, and it is wrong SILENTLY, which for an alert
-- means the alert reports healthy runway that does not exist. That is worse than no alert: an
-- absent alert is noticed when the outage happens, and a wrong one is trusted until then.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION range_partition_runway_months(parent regclass) RETURNS int
    LANGUAGE plpgsql STABLE
AS $$
DECLARE
    latest_bound timestamptz := NULL;
    bound_text   text;
    child        oid;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_partitioned_table WHERE partrelid = parent AND partstrat = 'r'
    ) THEN
        RAISE EXCEPTION '% is not range-partitioned, so it has no runway (OPS-DEP-011)', parent;
    END IF;

    FOR child IN
        SELECT inhrelid FROM pg_inherits WHERE inhparent = parent
    LOOP
        bound_text := pg_get_expr(
            (SELECT relpartbound FROM pg_class WHERE oid = child), child);

        -- FOR VALUES FROM ('...') TO ('...'). The upper bound is what runway means: the instant
        -- after which an insert has nowhere to go.
        IF bound_text LIKE '%TO (%' THEN
            BEGIN
                latest_bound := GREATEST(
                    COALESCE(latest_bound, '-infinity'::timestamptz),
                    (regexp_match(bound_text, 'TO \(''([^'']+)''\)'))[1]::timestamptz);
            EXCEPTION WHEN others THEN
                -- A bound that will not parse as a timestamp is a partition key this function
                -- does not understand. Reporting zero runway raises an alert somebody
                -- investigates; skipping it reports healthy runway that was never measured, which
                -- is PP-1 in the monitoring layer.
                RETURN 0;
            END;
        END IF;
    END LOOP;

    IF latest_bound IS NULL THEN
        RETURN 0;
    END IF;

    RETURN GREATEST(0, (
        EXTRACT(YEAR FROM age(latest_bound, now())) * 12
        + EXTRACT(MONTH FROM age(latest_bound, now()))
    )::int);
END
$$;

REVOKE ALL ON FUNCTION range_partition_runway_months(regclass) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION range_partition_runway_months(regclass)
    TO migration_runner, integrity_verifier, app_runtime;

-- -----------------------------------------------------------------------------
-- 2. partition_runway_report — every range-partitioned table, enumerated from the catalogue
--
-- The reason this is a report over the catalogue rather than a monitor over a list: a range
-- partitioned table added in a later migration is covered the day it is created. A hand-maintained
-- list is what produced the gap this migration closes, and it would produce it again.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION partition_runway_report()
    RETURNS TABLE (parent_table text, runway_months int, alerting boolean)
    LANGUAGE plpgsql STABLE
AS $$
DECLARE
    -- The lead time OPS-DEP-011 calls configurable. Three months matches the default every
    -- ensure_*_partitions function already uses; alerting one month before the runway reaches it
    -- gives an operator a month to act rather than a deadline they discover on the day.
    alert_below constant int := 3;
BEGIN
    RETURN QUERY
    SELECT c.relname::text,
           range_partition_runway_months(c.oid),
           range_partition_runway_months(c.oid) < alert_below
      FROM pg_class c
      JOIN pg_partitioned_table p ON p.partrelid = c.oid
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE p.partstrat = 'r'
       AND n.nspname = current_schema()
     ORDER BY c.relname;
END
$$;

REVOKE ALL ON FUNCTION partition_runway_report() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION partition_runway_report()
    TO migration_runner, integrity_verifier, app_runtime;

-- -----------------------------------------------------------------------------
-- 3. hash_partition_basis — OPS-DEP-012's second half, which is the half that gets omitted
--
-- "Hash partition counts MUST be set before first production data and RECORDED WITH THE SIZING
-- BASIS USED. Changing a hash partition count redistributes every row (CON-DAT-035). Recording the
-- basis lets a later resize assess whether the assumption or the growth was wrong."
--
-- V006 already reports the counts through hash_partition_counts(). A count without its basis
-- answers "how many" and not "why", and "why" is the only question a resize decision needs: if the
-- basis was the Medium profile and the tenant is now at Extra large, the assumption held and the
-- growth exceeded it. If the basis was a guess, that is a different decision entirely.
--
-- ⚠ Working assumption (OQ-015): the basis below is the Medium reference profile of DOC-01
-- section 12.1 with headroom to Extra large. OQ-015 BLOCKS IMPLEMENTATION for exactly this reason
-- — the value is irreversible after production data. The mechanism does not change on an answer;
-- the number does, and this table is where the change would be argued.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS hash_partition_basis (
    table_name       text        NOT NULL PRIMARY KEY,
    partition_count  int         NOT NULL,
    sizing_basis     text        NOT NULL,
    recorded_at      timestamptz NOT NULL DEFAULT now(),
    open_question    text        NULL,

    CONSTRAINT hash_partition_basis__count_positive
        CHECK (partition_count > 0),
    -- A blank basis satisfies "recorded" and answers nothing. The length floor is what stops
    -- 'default' and 'standard' from being the recorded basis of an irreversible decision.
    CONSTRAINT hash_partition_basis__basis_substantive
        CHECK (length(btrim(sizing_basis)) >= 40)
);

COMMENT ON TABLE hash_partition_basis IS
    'OPS-DEP-012. The count and WHY, because a later resize needs to know whether the assumption '
    'or the growth was wrong. Not tenant-scoped: partition counts are a deployment property, not '
    'tenant data, so no row-level policy applies here.';

-- Grants. Omitted from the first version of this migration and found by the post-deployment
-- conformance job, not by reading it: every FUNCTION in this file was granted explicitly and the
-- table was not, so it inherited nothing and integrity_verifier could not read the record it
-- exists to check.
--
-- Read to all three; written by the migration role only. The basis is a record OF a decision, and
-- a runtime role that can rewrite it can rewrite the justification for an irreversible choice
-- after the fact.
REVOKE ALL ON hash_partition_basis FROM PUBLIC;
GRANT SELECT ON hash_partition_basis TO app_runtime, integrity_verifier, migration_runner;
GRANT INSERT, UPDATE ON hash_partition_basis TO migration_runner;

INSERT INTO hash_partition_basis (table_name, partition_count, sizing_basis, open_question)
VALUES
    ('finding', 32,
     'Medium reference profile (DOC-01 section 12.1) with headroom to Extra large: 32 partitions '
     'keeps the largest single partition within index-maintenance budget at the Extra large '
     'finding volume, and 32 divides evenly into every replica count under consideration.',
     'OQ-015'),
    ('finding_fingerprint_input', 32,
     'Matched to finding. A different modulus on a table joined to finding on tenant_id defeats '
     'partitionwise join, which is the access path the deduplication pipeline depends on.',
     'OQ-015'),
    ('finding_asset_impact', 32,
     'Matched to finding, for the partitionwise join on tenant_id used by every impact rollup.',
     'OQ-015'),
    ('component', 32,
     'Matched to finding. Component identity is interned tenant-scoped (ADR-032), so the '
     'distribution follows tenant count rather than component count.',
     'OQ-015'),
    ('sbom_snapshot', 32,
     'Matched to component, so a snapshot and the components it contains land in the same '
     'partition and the match sweep is partition-local.',
     'OQ-015'),
    ('component_entry', 32,
     'Matched to sbom_snapshot. The entry count is the largest row count in the composition '
     'context and it is always read through its snapshot.',
     'OQ-015'),
    ('rm_posture_aggregate', 32,
     'Matched to the write-side tables it projects from, so a rebuild reads and writes within one '
     'partition per tenant rather than across all of them.',
     'OQ-015'),
    ('rm_finding_index', 32,
     'Matched to finding, which it indexes. A divergent modulus would make every projection '
     'catch-up a cross-partition scan.',
     'OQ-015'),
    ('rm_work_queue', 32,
     'Matched to the other read models. The queue is read per scope and the scope is tenant-bound, '
     'so tenant hashing gives partition-local reads for the interactive path.',
     'OQ-015')
ON CONFLICT (table_name) DO NOTHING;
