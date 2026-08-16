-- =============================================================================
-- V012 — read models (projections)
--
-- Owner: module/insight. DOC-04 section 21, DOC-02 section 11.2, DOC-12.
--
-- Seven projections, and three properties every one of them must have:
--
--   CON-DAT-030  tenant_id as a STRUCTURAL partition or index prefix. "Projections are aggregation
--                surfaces, which is where cross-tenant leakage through counts occurs" — a count is
--                a read that returns no rows and still discloses.
--   CON-DAT-031  rebuildable from the operational store or the event stream, and the rebuild
--                verifiable by comparison. "Projections are where subtle aggregation errors live,
--                and a defect is otherwise permanent."
--   CON-DAT-032  the latest value of any measure whose history is subject to partition drop is
--                retained here — "otherwise retention removes current values along with historical
--                ones, a data loss disguised as retention".
--
-- CON-PLT-028 is the fourth and it shapes the columns rather than the table set: coverage is
-- materialized ALONGSIDE the measure it qualifies, in the same row. Computed at read time it is a
-- join the renderer can skip; in the row it arrives with the number.
--
-- ON REBUILDABILITY AND WHAT IT PERMITS. Because every table here is derived, none carries an audit
-- column and none is a system of record. That is why they take DELETE freely where the operational
-- tables do not: dropping a projection loses nothing, and being able to drop and rebuild one is the
-- remedy for the aggregation defect CON-DAT-031 anticipates.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. rm_posture_aggregate — tenant x node x period
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS rm_posture_aggregate (
    tenant_id    uuid        NOT NULL,
    node_id      uuid        NOT NULL,
    -- The subtree containment path, so a scoped read is one index scan rather than a recursive walk.
    ancestor_path uuid[]     NOT NULL,
    period_start date        NOT NULL,
    posture_value numeric(9,6),
    -- CON-PLT-028: the coverage columns are IN THIS ROW, beside the measure.
    assets_in_scope int      NOT NULL,
    assets_with_current_data int NOT NULL,
    assets_never_measured int NOT NULL,
    confidence   text        NOT NULL,
    -- H6: as-was or as-is. Both are legitimate and answer different questions, so the projection
    -- records which basis produced the row rather than leaving the reader to assume.
    aggregation_basis text   NOT NULL,
    -- H3: an improvement carries its cause. Materialized, because computing it at read time needs
    -- the previous period's coverage and would silently degrade to "improved" when that is missing.
    change_cause text,
    projected_at timestamptz NOT NULL DEFAULT now(),
    source_version bigint    NOT NULL,

    PRIMARY KEY (tenant_id, node_id, period_start),

    CONSTRAINT ck_rm_posture__confidence CHECK
        (confidence IN ('HIGH', 'MEDIUM', 'LOW', 'INSUFFICIENT')),
    CONSTRAINT ck_rm_posture__basis CHECK (aggregation_basis IN ('AS_WAS', 'AS_IS')),
    CONSTRAINT ck_rm_posture__cause CHECK (change_cause IS NULL OR change_cause IN
        ('REMEDIATION', 'LOST_COVERAGE', 'REMEDIATION_WITH_COVERAGE_GAIN', 'NO_CHANGE',
         'INDETERMINATE')),
    CONSTRAINT ck_rm_posture__populations CHECK
        (assets_in_scope >= 0 AND assets_with_current_data >= 0 AND assets_never_measured >= 0
         AND assets_with_current_data <= assets_in_scope),
    -- H2: an INSUFFICIENT row must not carry a posture figure. Making it unrepresentable here is
    -- stronger than the renderer refusing to draw it, because an export reading this table directly
    -- would otherwise find a number and print it.
    CONSTRAINT ck_rm_posture__insufficient_has_no_figure CHECK
        (confidence <> 'INSUFFICIENT' OR posture_value IS NULL)
) PARTITION BY HASH (tenant_id);

SELECT apply_tenant_isolation('rm_posture_aggregate');

DO $$
DECLARE
    i int;
BEGIN
    FOR i IN 0..31 LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS rm_posture_aggregate_p%s PARTITION OF rm_posture_aggregate '
            'FOR VALUES WITH (MODULUS 32, REMAINDER %s)', i, i);
        PERFORM apply_tenant_isolation(format('rm_posture_aggregate_p%s', i)::regclass);
    END LOOP;
END
$$;

CREATE INDEX IF NOT EXISTS ix_rm_posture__subtree
    ON rm_posture_aggregate USING gin (ancestor_path);

COMMENT ON INDEX ix_rm_posture__subtree IS
    'Serves: the executive posture composition over a subtree — ancestor_path @> ARRAY[root], where '
    'the root is derived from the caller''s authorization context and never supplied (PRD-DSH-021).';

CREATE INDEX IF NOT EXISTS ix_rm_posture__insufficient
    ON rm_posture_aggregate (tenant_id, period_start DESC)
    WHERE confidence = 'INSUFFICIENT';

COMMENT ON INDEX ix_rm_posture__insufficient IS
    'Serves: nodes that must be presented as a coverage gap rather than a figure (H2, PRD-DSH-025). '
    'An index over the exclusion set, so a report can prove what it withheld.';

-- -----------------------------------------------------------------------------
-- 2. rm_finding_index — finding x impact
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS rm_finding_index (
    tenant_id    uuid        NOT NULL,
    finding_id   uuid        NOT NULL,
    asset_id     uuid        NOT NULL,
    scope_node_id uuid       NOT NULL,
    ancestor_path uuid[]     NOT NULL,
    severity_ordinal smallint NOT NULL,
    score_band   text,
    state_category text      NOT NULL,
    assignee_id  uuid,
    -- Coverage columns for a finding are its freshness: INV-VUL-18 requires staleness visible
    -- wherever the finding is used, and a list view is one of those places.
    last_detected_at timestamptz NOT NULL,
    source_freshness_days int NOT NULL,
    intelligence_stale bool  NOT NULL DEFAULT false,
    projected_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, finding_id, asset_id),

    CONSTRAINT ck_rm_finding__band CHECK (score_band IS NULL OR score_band IN
        ('INFORMATIONAL', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_rm_finding__freshness CHECK (source_freshness_days >= 0)
) PARTITION BY HASH (tenant_id);

SELECT apply_tenant_isolation('rm_finding_index');

DO $$
DECLARE
    i int;
BEGIN
    FOR i IN 0..31 LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS rm_finding_index_p%s PARTITION OF rm_finding_index '
            'FOR VALUES WITH (MODULUS 32, REMAINDER %s)', i, i);
        PERFORM apply_tenant_isolation(format('rm_finding_index_p%s', i)::regclass);
    END LOOP;
END
$$;

CREATE INDEX IF NOT EXISTS ix_rm_finding__subtree_band
    ON rm_finding_index USING gin (ancestor_path);

COMMENT ON INDEX ix_rm_finding__subtree_band IS
    'Serves: the scoped finding list. The scope predicate is applied IN retrieval (SEC-AUZ-016), '
    'which is also what keeps a denial''s latency indistinguishable from a not-found (PRD-API-021).';

CREATE INDEX IF NOT EXISTS ix_rm_finding__assignee
    ON rm_finding_index (tenant_id, assignee_id, state_category)
    WHERE assignee_id IS NOT NULL;

COMMENT ON INDEX ix_rm_finding__assignee IS 'Serves: "findings assigned to me", by state.';

-- -----------------------------------------------------------------------------
-- 3. rm_work_queue — the twelve queues of DOC-12 section 6.1
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS rm_work_queue (
    tenant_id    uuid        NOT NULL,
    work_item_id uuid        NOT NULL,
    -- Which of the twelve this row belongs in. An item may appear in more than one, so the queue is
    -- part of the key rather than a column: a single-queue-per-item model would silently drop an
    -- item from the second queue it belongs to, and the dropped one is the one nobody is watching.
    queue_number smallint    NOT NULL,
    scope_node_id uuid       NOT NULL,
    ancestor_path uuid[]     NOT NULL,
    state_category text      NOT NULL,
    assignee_id  uuid,
    due_at       timestamptz,
    sla_status   text,
    -- The highlight verdict, computed by the projection rather than by the renderer, so an export
    -- and a screen cannot disagree about what is red.
    highlighted  bool        NOT NULL DEFAULT false,
    highlight_reason text,
    projected_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, queue_number, work_item_id),

    CONSTRAINT ck_rm_work_queue__number CHECK (queue_number BETWEEN 1 AND 12),
    CONSTRAINT ck_rm_work_queue__sla_status CHECK (sla_status IS NULL OR sla_status IN
        ('RUNNING', 'PAUSED', 'MET', 'BREACHED', 'EXTENDED', 'CANCELLED')),
    -- A highlighted row states why. A red marker with no reason is one a reader learns to ignore,
    -- and the reasons are what let a tenant argue with a threshold rather than mute it.
    CONSTRAINT ck_rm_work_queue__highlight_reason CHECK
        (NOT highlighted OR (highlight_reason IS NOT NULL AND highlight_reason <> ''))
) PARTITION BY HASH (tenant_id);

SELECT apply_tenant_isolation('rm_work_queue');

DO $$
DECLARE
    i int;
BEGIN
    FOR i IN 0..31 LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS rm_work_queue_p%s PARTITION OF rm_work_queue '
            'FOR VALUES WITH (MODULUS 32, REMAINDER %s)', i, i);
        PERFORM apply_tenant_isolation(format('rm_work_queue_p%s', i)::regclass);
    END LOOP;
END
$$;

CREATE INDEX IF NOT EXISTS ix_rm_work_queue__queue
    ON rm_work_queue (tenant_id, queue_number, highlighted DESC, due_at);

COMMENT ON INDEX ix_rm_work_queue__queue IS
    'Serves: one of the twelve queues, highlighted rows first then by due date. The 5-second lag '
    'budget of DOC-04 section 21 is why this is a projection at all.';

CREATE INDEX IF NOT EXISTS ix_rm_work_queue__subtree
    ON rm_work_queue USING gin (ancestor_path);

COMMENT ON INDEX ix_rm_work_queue__subtree IS 'Serves: a queue scoped to a subtree.';

-- -----------------------------------------------------------------------------
-- 4. rm_workload_current, rm_coverage_state, rm_activity_timeline
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS rm_workload_current (
    tenant_id    uuid        NOT NULL,
    subject_kind text        NOT NULL,
    subject_id   uuid        NOT NULL,
    period_start date        NOT NULL,
    utilization_percent numeric(5,2),
    target_band_low  smallint NOT NULL,
    target_band_high smallint NOT NULL,
    -- H7: the band's reason travels with the band. Without it the band reads as a target to exceed
    -- rather than a range to stay within.
    target_band_reason text  NOT NULL,
    -- H8: the purpose statement, materialized. A per-person metric with no stated purpose is one
    -- whose purpose the reader supplies, and for an individual that purpose is performance review.
    purpose_statement text   NOT NULL,
    contributing_member_count int NOT NULL,
    projected_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, subject_kind, subject_id, period_start),

    CONSTRAINT ck_rm_workload__subject CHECK (subject_kind IN ('MEMBER', 'TEAM')),
    CONSTRAINT ck_rm_workload__band CHECK (target_band_low < target_band_high),
    CONSTRAINT ck_rm_workload__band_reason CHECK (target_band_reason <> ''),
    CONSTRAINT ck_rm_workload__purpose CHECK (purpose_statement <> ''),
    CONSTRAINT ck_rm_workload__members CHECK (contributing_member_count >= 0),
    -- SEC-AUZ-026 at projection level: a team figure derived from fewer than the minimum population
    -- is an individual's figure with a team's label. Blocking it here means no report can produce
    -- one, whatever query it runs.
    CONSTRAINT ck_rm_workload__minimum_population CHECK
        (subject_kind <> 'TEAM' OR contributing_member_count >= 4)
);

SELECT apply_tenant_isolation('rm_workload_current');

CREATE INDEX IF NOT EXISTS ix_rm_workload__period
    ON rm_workload_current (tenant_id, period_start DESC, subject_kind);

COMMENT ON INDEX ix_rm_workload__period IS
    'Serves: the security team workload composition for a period.';

CREATE TABLE IF NOT EXISTS rm_coverage_state (
    tenant_id    uuid        NOT NULL,
    asset_id     uuid        NOT NULL,
    -- Here the measure IS the coverage (DOC-04 section 21), so there is no separate qualifier.
    status       text        NOT NULL,
    last_successful_data_at timestamptz,
    days_since_data int,
    quality_score int,
    failure_reason text,
    accountable_owner_id uuid NOT NULL,
    projected_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, asset_id),

    -- NEVER_SUBMITTED is a STATUS, not an absent row. PRD-SBM-056 is "the single most important
    -- requirement in the module": an asset absent from this table is absent from coverage
    -- reporting, and absence reads as absence of problems.
    CONSTRAINT ck_rm_coverage__status CHECK
        (status IN ('CURRENT', 'PARTIAL', 'STALE', 'NEVER_SUBMITTED')),
    CONSTRAINT ck_rm_coverage__never_submitted_has_no_data CHECK
        (status <> 'NEVER_SUBMITTED' OR last_successful_data_at IS NULL),
    CONSTRAINT ck_rm_coverage__quality CHECK
        (quality_score IS NULL OR quality_score BETWEEN 0 AND 100)
);

SELECT apply_tenant_isolation('rm_coverage_state');

CREATE INDEX IF NOT EXISTS ix_rm_coverage__gaps
    ON rm_coverage_state (tenant_id, status, accountable_owner_id)
    WHERE status <> 'CURRENT';

COMMENT ON INDEX ix_rm_coverage__gaps IS
    'Serves: queue 8, coverage health — the classic blind spot. If forty assets have silently had no '
    'data for three months the vulnerability dashboard shows green, not because they are secure but '
    'because there is no data (DOC-12 section 6.1).';

CREATE TABLE IF NOT EXISTS rm_activity_timeline (
    tenant_id    uuid        NOT NULL,
    work_item_id uuid        NOT NULL,
    occurred_at  timestamptz NOT NULL,
    -- Part of the key: two events can share an instant, and dropping one would make a timeline that
    -- omits an entry with nothing to indicate it.
    event_seq    int         NOT NULL,
    event_kind   text        NOT NULL,
    actor_id     uuid,
    actor_type   text        NOT NULL,
    summary      text        NOT NULL,
    -- H9, H10, H11: provenance markers materialized, because they must survive export and an export
    -- reads this table.
    ai_generated bool        NOT NULL DEFAULT false,
    migrated     bool        NOT NULL DEFAULT false,
    inbound_email bool       NOT NULL DEFAULT false,

    PRIMARY KEY (tenant_id, work_item_id, occurred_at, event_seq),

    CONSTRAINT ck_rm_timeline__actor_type CHECK
        (actor_type IN ('USER', 'SERVICE', 'AUTOMATION', 'SYSTEM')),
    CONSTRAINT ck_rm_timeline__actor CHECK
        ((actor_type = 'SYSTEM' AND actor_id IS NULL) OR (actor_type <> 'SYSTEM' AND actor_id IS NOT NULL))
);

SELECT apply_tenant_isolation('rm_activity_timeline');

CREATE INDEX IF NOT EXISTS ix_rm_timeline__item
    ON rm_activity_timeline (tenant_id, work_item_id, occurred_at, event_seq);

COMMENT ON INDEX ix_rm_timeline__item IS
    'Serves: the unified activity timeline for an item, in stable order. The sequence breaks ties so '
    'the timeline does not reorder itself between two reads.';

-- -----------------------------------------------------------------------------
-- 5. rm_latest_risk_score — CON-DAT-032, the projection that exists because of retention
--
-- DOC-04 section 21: "Score partitions are dropped after the reproducibility window (section 18.2).
-- The latest score per subject must survive that, so it is projected. Without this projection,
-- dropping a 25-month-old partition would remove the current score for any subject not rescored
-- since."
--
-- That is a data loss disguised as retention, and it would arrive twenty-five months after the
-- decision that caused it.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS rm_latest_risk_score (
    tenant_id    uuid        NOT NULL,
    subject_kind text        NOT NULL,
    subject_id   uuid        NOT NULL,
    score_id     uuid        NOT NULL,
    value        smallint    NOT NULL,
    band         text        NOT NULL,
    coverage_confidence text NOT NULL,
    model_version int        NOT NULL,
    computed_at  timestamptz NOT NULL,
    projected_at timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, subject_kind, subject_id),

    CONSTRAINT ck_rm_score__value CHECK (value BETWEEN 0 AND 100),
    CONSTRAINT ck_rm_score__band CHECK
        (band IN ('INFORMATIONAL', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_rm_score__confidence CHECK
        (coverage_confidence IN ('HIGH', 'MEDIUM', 'LOW', 'INSUFFICIENT'))
);

SELECT apply_tenant_isolation('rm_latest_risk_score');

CREATE INDEX IF NOT EXISTS ix_rm_score__band
    ON rm_latest_risk_score (tenant_id, subject_kind, band);

COMMENT ON INDEX ix_rm_score__band IS 'Serves: current scores by band — dashboard aggregation.';

CREATE INDEX IF NOT EXISTS ix_rm_score__not_presentable
    ON rm_latest_risk_score (tenant_id, subject_kind)
    WHERE coverage_confidence = 'INSUFFICIENT';

COMMENT ON INDEX ix_rm_score__not_presentable IS
    'Serves: scores that must not be presented as posture figures (PRD-RSK-027), carried into the '
    'projection so a report reading only this table still knows.';

-- -----------------------------------------------------------------------------
-- 6. Conformance
-- -----------------------------------------------------------------------------

-- CON-DAT-030: every projection carries tenant_id as a structural partition or index prefix.
-- Asserted over the catalogue rather than by inspection, because the failure mode is a projection
-- added later without one — and a projection is where cross-tenant leakage through counts occurs.
CREATE OR REPLACE FUNCTION projections_without_tenant_prefix()
    RETURNS TABLE (projection text, reason text)
    LANGUAGE sql STABLE
AS $$
    SELECT c.relname::text, 'no tenant_id column'::text
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relname LIKE 'rm\_%'
       AND c.relkind IN ('r', 'p')
       AND n.nspname = current_schema()
       AND NOT EXISTS (
            SELECT 1 FROM pg_attribute a
             WHERE a.attrelid = c.oid AND a.attname = 'tenant_id' AND a.attnum > 0
               AND NOT a.attisdropped)
    UNION ALL
    -- And tenant_id must be FIRST in the primary key, not merely present: a key of
    -- (finding_id, tenant_id) permits an index scan that spans tenants before the predicate applies.
    SELECT c.relname::text, 'tenant_id is not the leading primary key column'::text
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_index i ON i.indrelid = c.oid AND i.indisprimary
     WHERE c.relname LIKE 'rm\_%'
       AND c.relkind IN ('r', 'p')
       AND n.nspname = current_schema()
       AND (SELECT a.attname FROM pg_attribute a
             WHERE a.attrelid = c.oid AND a.attnum = i.indkey[0]) <> 'tenant_id';
$$;

GRANT EXECUTE ON FUNCTION projections_without_tenant_prefix()
    TO migration_runner, integrity_verifier, app_runtime;

-- CON-DAT-031: the rebuild must be verifiable by comparison, which needs the projection to record
-- what it was built from. A projection with no source version can be rebuilt and cannot be checked.
CREATE OR REPLACE FUNCTION projection_staleness(lag_budget_seconds int DEFAULT 60)
    RETURNS TABLE (projection text, oldest_projection timestamptz, seconds_behind numeric)
    LANGUAGE plpgsql STABLE
AS $$
DECLARE
    target text;
BEGIN
    FOREACH target IN ARRAY ARRAY['rm_posture_aggregate', 'rm_finding_index', 'rm_work_queue',
                                  'rm_workload_current', 'rm_coverage_state', 'rm_latest_risk_score'] LOOP
        RETURN QUERY EXECUTE format(
            'SELECT %L::text, min(projected_at), '
            'EXTRACT(EPOCH FROM (now() - min(projected_at)))::numeric '
            'FROM %I HAVING min(projected_at) < now() - make_interval(secs => %s)',
            target, target, lag_budget_seconds);
    END LOOP;
END
$$;

GRANT EXECUTE ON FUNCTION projection_staleness(int)
    TO migration_runner, integrity_verifier, app_runtime;

-- -----------------------------------------------------------------------------
-- 7. Grants
--
-- DELETE is granted freely here and nowhere else in the schema. Every table above is derived and
-- rebuildable (CON-DAT-031), so dropping one loses nothing — and being able to drop and rebuild is
-- the remedy for the aggregation defect that requirement anticipates. The operational tables have
-- the opposite property and the opposite grants.
-- -----------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE ON rm_posture_aggregate, rm_finding_index, rm_work_queue,
    rm_workload_current, rm_coverage_state, rm_activity_timeline, rm_latest_risk_score
    TO app_runtime, migration_runner;

GRANT SELECT ON rm_posture_aggregate, rm_finding_index, rm_work_queue, rm_workload_current,
    rm_coverage_state, rm_activity_timeline, rm_latest_risk_score TO integrity_verifier;
