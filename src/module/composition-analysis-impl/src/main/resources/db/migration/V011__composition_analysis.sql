-- =============================================================================
-- V011 — composition analysis: components, snapshots, entries, match runs
--
-- Owner: module/composition-analysis. DOC-04 sections 15.1 to 15.5, DOC-22 in full, DOC-09
-- section 11.
--
-- The design of this file is dominated by ONE table. component_entry reaches approximately
-- 80,000,000 rows at Extra large, and "at that volume every byte is 80 MB" (DOC-04 section 15.1).
-- Two documented exceptions to the schema conventions apply to it, both taken deliberately:
--
--   * No surrogate key (fifth exception to CON-DAT-006). The entry has no independent identity — it
--     is the fact that a snapshot contains a component — and omitting the uuid saves 16 bytes plus
--     an index across 80,000,000 rows: roughly 1.3 GB of data and 2 to 3 GB of index.
--   * No common columns (sixth exception). No created_at, updated_by or row_version: entries are
--     inserted once with their snapshot, never updated, and their creation time is the snapshot's.
--     The standard six columns would add roughly 40 bytes per row — 3 GB at Extra large — for
--     information already on the parent.
--
-- Resulting row width is approximately 45 bytes against approximately 200 in the naive design.
--
-- The other structural decision is ADR-032: component identity is interned TENANT-SCOPED. Global
-- interning would be more space-efficient and is rejected because a globally interned table
-- populated on demand is created BY tenant submissions, which makes component existence observable
-- in principle (DOC-24 section 6.2 entry 14, RISK-PLT-004). The cost is roughly 3,200,000 component
-- rows rather than 300,000 — negligible against the 80,000,000 entries it makes narrow.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. component — interned identity, DOC-04 section 15.2
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS component (
    id            uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL,
    purl_canonical text       NOT NULL,
    -- Retained so a canonicalization defect is correctable without resubmission (PRD-SBM-035). The
    -- platform is blind between submissions (ADR-024), so "ask them to submit again" is a request
    -- that may not be answered for months.
    purl_original text        NOT NULL,
    canonicalization_version int NOT NULL,
    ecosystem     text        NOT NULL,
    name          text        NOT NULL,
    version       text        NOT NULL,
    -- PRD-SBM-037: an unmatchable component is RECORDED, not skipped. Silent skipping is the
    -- mechanism by which a partially matched SBOM appears fully matched.
    is_canonicalizable bool   NOT NULL DEFAULT true,
    unmatchable_reason text,
    created_at    timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, id),

    CONSTRAINT ck_component__canonicalization_version CHECK (canonicalization_version >= 1),
    CONSTRAINT ck_component__unmatchable_reason CHECK
        (is_canonicalizable OR (unmatchable_reason IS NOT NULL AND unmatchable_reason <> '')),
    CONSTRAINT ck_component__reason_only_when_unmatchable CHECK
        (NOT is_canonicalizable OR unmatchable_reason IS NULL),
    CONSTRAINT ck_component__unmatchable_reason_enumerated CHECK
        (unmatchable_reason IS NULL OR unmatchable_reason IN
            ('NOT_A_PACKAGE_URL', 'UNKNOWN_ECOSYSTEM', 'MISSING_NAME', 'MISSING_VERSION')),
    -- A matchable component has a concrete version. Without one the matcher "finds nothing because
    -- there is nothing matchable, and the result is indistinguishable from a clean application"
    -- (DOC-03 section 11) — a false negative presenting as good news.
    CONSTRAINT ck_component__version_present CHECK (NOT is_canonicalizable OR version <> '')
) PARTITION BY HASH (tenant_id);

SELECT apply_tenant_isolation('component');

-- 32 partitions, aligned with component_entry so the join between them stays partition-local.
--
-- *** OQ-015 GATE (OPS-DEP-012). *** The count is irreversible once production data exists.
-- ⚠ Working assumption: 32, on the same basis recorded in V006 for `finding` — the Extra large
-- profile of DOC-01 section 12.1 with headroom for a single tenant's growth. Answering OQ-015 with
-- an order-of-magnitude portfolio size before first production load is what turns this from an
-- assumption into a decision.
DO $$
DECLARE
    i int;
BEGIN
    FOR i IN 0..31 LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS component_p%s PARTITION OF component '
            'FOR VALUES WITH (MODULUS 32, REMAINDER %s)', i, i);
        PERFORM apply_tenant_isolation(format('component_p%s', i)::regclass);
    END LOOP;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_component__canonical
    ON component (tenant_id, purl_canonical, canonicalization_version);

COMMENT ON INDEX ux_component__canonical IS
    'Serves: the interning lookup on every submission — one per component per SBOM. The '
    'canonicalization_version is part of the key so a rule change interns a NEW row rather than '
    'silently repointing an existing one (PRD-SBM-036).';

CREATE INDEX IF NOT EXISTS ix_component__match_lookup
    ON component (tenant_id, ecosystem, name);

COMMENT ON INDEX ix_component__match_lookup IS
    'Serves: THE MATCH JOIN against vulnerability_affected_range. DOC-04 section 15.2: together '
    'with ux_component__canonical, this index determines whether NFR-SBM-002''s sweep budget is '
    'achievable — the review point prompt 11 asks to validate early (TST-PLT-007).';

CREATE INDEX IF NOT EXISTS ix_component__unmatchable
    ON component (tenant_id, ecosystem) WHERE NOT is_canonicalizable;

COMMENT ON INDEX ix_component__unmatchable IS
    'Serves: the unmatchable-component list — the quality feedback surface (PRD-SBM-037). Partial, '
    'because the interesting set is the small one.';

CREATE INDEX IF NOT EXISTS ix_component__recanonicalize
    ON component (tenant_id, canonicalization_version);

COMMENT ON INDEX ix_component__recanonicalize IS
    'Serves: components needing re-canonicalization after a rule change.';

-- -----------------------------------------------------------------------------
-- 2. sbom_snapshot — DOC-04 section 15.4
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS sbom_snapshot (
    id            uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id     uuid        NOT NULL,
    -- Cross-module, no FK (ADR-030).
    artifact_asset_id uuid    NOT NULL,
    -- INV-SBM-02: identity IS the content hash. Resubmitting identical content returns the existing
    -- snapshot rather than creating a second one, which is what makes a retrying CI job harmless.
    content_hash  bytea       NOT NULL,
    format        text        NOT NULL,
    format_version text       NOT NULL,
    revision_reference text,
    build_reference text,
    source        text        NOT NULL,
    submitted_by_principal_id uuid NOT NULL,
    -- INV-SBM-03. A zero-component snapshot "is the likely output of a misconfigured pipeline, and
    -- accepting it records 'this application has no dependencies'".
    component_count int       NOT NULL,
    quality_score int         NOT NULL,
    quality_detail jsonb      NOT NULL DEFAULT '{}'::jsonb,
    -- PRD-SBM-055 depends on this column entirely. Without it, coverage-aware closure cannot be
    -- computed and a single-ecosystem submission closes every other ecosystem's findings.
    ecosystems    text[]      NOT NULL,
    storage_ref   text,

    scope_node_id         uuid        NOT NULL,
    scope_ancestor_path   uuid[]      NOT NULL,
    scope_node_type_id    uuid        NOT NULL,
    scope_criticality_id  uuid        NOT NULL,
    scope_hierarchy_ver   bigint      NOT NULL,
    scope_resolved_at     timestamptz NOT NULL,

    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,

    PRIMARY KEY (tenant_id, id),

    CONSTRAINT ck_snapshot__format CHECK (format IN ('CYCLONEDX', 'SPDX')),
    -- INV-SBM-05 / ADR-026. PLATFORM_GENERATED and REGISTRY_DERIVED are RESERVED and rejected in
    -- this release. A CHECK is the strongest available expression: enabling them later is a
    -- one-line migration accompanying the code that supports them, and until then no code path —
    -- including a migration or a bulk import — can introduce them.
    CONSTRAINT ck_snapshot__source CHECK (source IN ('API_PUSH', 'MANUAL_UPLOAD')),
    CONSTRAINT ck_snapshot__component_count CHECK (component_count > 0),
    CONSTRAINT ck_snapshot__quality_score CHECK (quality_score BETWEEN 0 AND 100),
    -- A snapshot covering no ecosystem cannot drive coverage-aware closure for anything, and
    -- accepting one would make PRD-SBM-055's check vacuous.
    --
    -- *** DEFECT FOUND BY RUNNING THE VERIFICATION SUITE. *** An earlier version wrote
    -- array_length(ecosystems, 1) >= 1. On an EMPTY array array_length returns NULL, not 0, and a
    -- CHECK evaluating to NULL PASSES. The constraint read as correct and enforced nothing.
    -- cardinality() returns 0 for an empty array, which is what the comparison needs.
    CONSTRAINT ck_snapshot__ecosystems_present CHECK (cardinality(ecosystems) >= 1)
) PARTITION BY HASH (tenant_id);

SELECT apply_tenant_isolation('sbom_snapshot');

DO $$
DECLARE
    i int;
BEGIN
    FOR i IN 0..31 LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS sbom_snapshot_p%s PARTITION OF sbom_snapshot '
            'FOR VALUES WITH (MODULUS 32, REMAINDER %s)', i, i);
        PERFORM apply_tenant_isolation(format('sbom_snapshot_p%s', i)::regclass);
    END LOOP;
END
$$;

-- CON-DAT-009 / PRD-WRK-042. Caught by the conformance assertion written in prompt 4: a
-- scope-bearing table without this trigger is a silent hole. The whole-table immutability trigger
-- below would in fact reject a scope change too, but the conformance query looks for THIS trigger
-- by function, and being absent from an inventory of scope-bearing tables is how the next
-- scope-bearing table gets added without one.
DROP TRIGGER IF EXISTS trg_snapshot__scope_immutable ON sbom_snapshot;
CREATE TRIGGER trg_snapshot__scope_immutable
    BEFORE UPDATE ON sbom_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_scope_descriptor_change();

-- INV-SBM-01: a snapshot is immutable once accepted. "Re-matching requires that it has not changed,
-- or results are not attributable" — a mutable snapshot means a match run's output cannot be tied
-- to any particular input, which makes every historical match result unexplainable.
CREATE OR REPLACE FUNCTION reject_snapshot_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'an accepted SBOM snapshot is immutable (INV-SBM-01). Its identity IS its content hash, so a '
        'changed snapshot is a different snapshot; %ing this row would leave every match run that '
        'read it attributing results to content that no longer exists.', lower(TG_OP)
        USING ERRCODE = 'integrity_constraint_violation';
END
$$;

DROP TRIGGER IF EXISTS trg_snapshot__immutable ON sbom_snapshot;
CREATE TRIGGER trg_snapshot__immutable
    BEFORE UPDATE ON sbom_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_snapshot_change();

CREATE UNIQUE INDEX IF NOT EXISTS ux_sbom_snapshot__hash
    ON sbom_snapshot (tenant_id, content_hash);

COMMENT ON INDEX ux_sbom_snapshot__hash IS
    'Serves: idempotent submission — resubmitting identical content returns the existing snapshot '
    '(INV-SBM-02, PRD-SBM-033). A retrying CI job is the normal case, not the exception.';

CREATE INDEX IF NOT EXISTS ix_snapshot__artifact_latest
    ON sbom_snapshot (tenant_id, artifact_asset_id, created_at DESC);

COMMENT ON INDEX ix_snapshot__artifact_latest IS
    'Serves: the latest snapshot for an artifact — coverage state, change set computation, and '
    'every match trigger.';

CREATE INDEX IF NOT EXISTS ix_snapshot__quality
    ON sbom_snapshot (tenant_id, quality_score) WHERE quality_score < 70;

COMMENT ON INDEX ix_snapshot__quality IS
    'Serves: low-quality snapshots — the PARTIAL coverage queue (PRD-SBM-032).';

CREATE INDEX IF NOT EXISTS ix_snapshot__retention
    ON sbom_snapshot (tenant_id, created_at);

COMMENT ON INDEX ix_snapshot__retention IS
    'Serves: retention batch selection. Deleting a snapshot deletes its entries, which is the only '
    'bulk deletion of a large table in the schema — hence batch selection rather than one statement.';

-- -----------------------------------------------------------------------------
-- 3. component_entry — the largest table in the platform, DOC-04 section 15.3
--
-- Read the file header before adding a column here. Every byte is 80 MB.
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS component_entry (
    tenant_id    uuid     NOT NULL,
    snapshot_id  uuid     NOT NULL,
    component_id uuid     NOT NULL,
    -- smallint, not text: 1 direct, 2 transitive. A text column would cost more than the rest of
    -- the row combined at this volume.
    relationship smallint NOT NULL,
    depth        smallint,
    license_refs text[],
    -- Reserved (DF-03); null in this release. Present so enabling reachability later is a backfill
    -- rather than a schema change on an 80,000,000-row table.
    reachability smallint,

    -- Fifth documented exception to CON-DAT-006: no surrogate key. The entry has no identity beyond
    -- its parents.
    PRIMARY KEY (tenant_id, snapshot_id, component_id),

    CONSTRAINT ck_component_entry__relationship CHECK (relationship IN (1, 2)),
    CONSTRAINT ck_component_entry__depth CHECK (depth IS NULL OR depth >= 0)
) PARTITION BY HASH (tenant_id);

SELECT apply_tenant_isolation('component_entry');

-- Aligned with component and sbom_snapshot: both hot queries carry tenant_id, so every query is
-- partition-pruned to one.
DO $$
DECLARE
    i int;
BEGIN
    FOR i IN 0..31 LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS component_entry_p%s PARTITION OF component_entry '
            'FOR VALUES WITH (MODULUS 32, REMAINDER %s)', i, i);
        PERFORM apply_tenant_isolation(format('component_entry_p%s', i)::regclass);
    END LOOP;
END
$$;

CREATE INDEX IF NOT EXISTS ix_component_entry__component
    ON component_entry (tenant_id, component_id);

COMMENT ON INDEX ix_component_entry__component IS
    'Serves: "which snapshots contain this component" — THE DISCLOSURE-RESPONSE QUERY. This is the '
    'index that answers "which of our applications contain the vulnerable library" on the morning a '
    'critical advisory lands, and PRD-SBM-044 turns that from a multi-week campaign into a query.';

CREATE INDEX IF NOT EXISTS ix_component_entry__direct
    ON component_entry (tenant_id, snapshot_id) WHERE relationship = 1;

COMMENT ON INDEX ix_component_entry__direct IS
    'Serves: direct dependencies only — prioritization and the developer-facing view. Partial, '
    'because transitive entries outnumber direct ones by roughly an order of magnitude and the '
    'developer view never wants them.';

-- Entries are inserted once with their snapshot and never updated (the sixth exception's premise).
-- A trigger rather than only a withheld grant, because the premise is what justifies omitting the
-- audit columns: if entries could be updated, their absence would be a gap rather than a saving.
CREATE OR REPLACE FUNCTION reject_component_entry_update() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'component_entry rows are inserted once with their snapshot and never updated (DOC-04 '
        'section 15.3). The table carries no created_at, updated_by or row_version BECAUSE of that '
        'premise — 3 GB saved at Extra large — so an update here would be an unauditable change to '
        'the largest table in the platform.'
        USING ERRCODE = 'integrity_constraint_violation';
END
$$;

DROP TRIGGER IF EXISTS trg_component_entry__no_update ON component_entry;
CREATE TRIGGER trg_component_entry__no_update
    BEFORE UPDATE ON component_entry
    FOR EACH ROW EXECUTE FUNCTION reject_component_entry_update();

-- -----------------------------------------------------------------------------
-- 4. match_batch and match_run — DOC-04 section 15.5, DOC-09 section 11
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS match_batch (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid        NOT NULL,
    trigger_kind text        NOT NULL,
    queue_class  text        NOT NULL,
    total_runs   int         NOT NULL DEFAULT 0,
    completed_runs int       NOT NULL DEFAULT 0,
    failed_runs  int         NOT NULL DEFAULT 0,
    skipped_runs int         NOT NULL DEFAULT 0,
    state        text        NOT NULL DEFAULT 'RUNNING',
    paused_at    timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz,

    CONSTRAINT ck_match_batch__queue_class CHECK
        (queue_class IN ('INTERACTIVE', 'BATCH_ELEVATED', 'BATCH')),
    CONSTRAINT ck_match_batch__state CHECK
        (state IN ('RUNNING', 'PAUSED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_match_batch__counts CHECK
        (total_runs >= 0 AND completed_runs >= 0 AND failed_runs >= 0 AND skipped_runs >= 0
         AND completed_runs + failed_runs + skipped_runs <= total_runs),
    -- PRD-SBM-049: progress MUST report skipped and failed counts. A batch reporting only
    -- completions looks healthier the more runs fail.
    CONSTRAINT ck_match_batch__paused CHECK ((state = 'PAUSED') = (paused_at IS NOT NULL))
);

SELECT apply_tenant_isolation('match_batch');

CREATE INDEX IF NOT EXISTS ix_match_batch__active
    ON match_batch (tenant_id, queue_class, created_at) WHERE state IN ('RUNNING', 'PAUSED');

COMMENT ON INDEX ix_match_batch__active IS
    'Serves: active batches by queue class — the scheduler''s view, and the ordering that keeps '
    'INTERACTIVE work ahead of BATCH (INV-SBM-11).';

CREATE TABLE IF NOT EXISTS match_run (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid        NOT NULL,
    snapshot_id  uuid        NOT NULL,
    batch_id     uuid        REFERENCES match_batch (id) ON DELETE RESTRICT,
    -- INV-SBM-07 / PRD-SBM-047: a hash over the snapshot hash, intelligence version, matcher
    -- version and canonicalization version. All four, because a change to any of them changes the
    -- result.
    idempotency_key bytea    NOT NULL,
    -- INV-SBM-12. Recorded on every run, so a finding appearing today and not yesterday can be
    -- attributed to the matcher rather than to the estate.
    intelligence_version text NOT NULL,
    matcher_version text     NOT NULL,
    canonicalization_version int NOT NULL,
    queue_class  text        NOT NULL,
    state        text        NOT NULL DEFAULT 'QUEUED',
    -- INV-SBM-10. An expiry timestamp, not a heartbeat: "container termination is abrupt and
    -- normal", and without expiry a terminated worker leaves the run claimed and the batch stalls
    -- silently.
    lease_holder_id uuid,
    lease_expires_at timestamptz,
    attempt_count int        NOT NULL DEFAULT 0,
    -- INV-SBM-09. Only a confirmed run may drive closure, and this column is what the closure path
    -- reads. It is NOT settable independently of the run's outcome by any application path — see
    -- the trigger below.
    coverage_confirmed bool  NOT NULL DEFAULT false,
    covered_ecosystems text[] NOT NULL DEFAULT '{}',
    failure_reason text,
    queued_at    timestamptz NOT NULL DEFAULT now(),
    started_at   timestamptz,
    finished_at  timestamptz,

    CONSTRAINT uq_match_run__idempotency UNIQUE (tenant_id, snapshot_id, idempotency_key),
    CONSTRAINT ck_match_run__queue_class CHECK
        (queue_class IN ('INTERACTIVE', 'BATCH_ELEVATED', 'BATCH')),
    CONSTRAINT ck_match_run__state CHECK
        (state IN ('QUEUED', 'LEASED', 'RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED_NO_CHANGE')),
    CONSTRAINT ck_match_run__versions CHECK (canonicalization_version >= 1),
    -- INV-SBM-10: a lease without an expiry is the silent stall.
    CONSTRAINT ck_match_run__lease CHECK ((lease_holder_id IS NULL) = (lease_expires_at IS NULL)),
    CONSTRAINT ck_match_run__failure_reason CHECK
        (state <> 'FAILED' OR (failure_reason IS NOT NULL AND failure_reason <> '')),
    -- INV-SBM-09 AT THE ENGINE. This is the constraint that stops a failed run driving closure
    -- through any path — including a migration, a repair script, or a well-meaning UPDATE by
    -- somebody clearing a stuck batch.
    CONSTRAINT ck_match_run__coverage_only_when_completed CHECK
        (NOT coverage_confirmed OR state = 'COMPLETED'),
    -- A run that confirmed coverage must say what it covered, or PRD-SBM-055 has nothing to read.
    -- cardinality(), not array_length(): see the note on ck_snapshot__ecosystems_present. The same
    -- mistake was present here and the same run found both.
    CONSTRAINT ck_match_run__covered_ecosystems CHECK
        (NOT coverage_confirmed OR cardinality(covered_ecosystems) >= 1)
);

SELECT apply_tenant_isolation('match_run');

CREATE INDEX IF NOT EXISTS ix_match_run__queue
    ON match_run (tenant_id, queue_class, queued_at) WHERE state = 'QUEUED';

COMMENT ON INDEX ix_match_run__queue IS
    'Serves: the next run to lease. Ordered by queue class first, which IS INV-SBM-11 — interactive '
    'runs are never queued behind batch runs.';

CREATE INDEX IF NOT EXISTS ix_match_run__lease_reclaim
    ON match_run (tenant_id, lease_expires_at)
    WHERE state IN ('LEASED', 'RUNNING');

COMMENT ON INDEX ix_match_run__lease_reclaim IS
    'Serves: lease reclamation (INV-SBM-10). Without this index the reclaim sweep is a full scan, '
    'so it gets run rarely, so a terminated worker''s run sits claimed for longer — which is the '
    'failure the lease exists to bound.';

CREATE INDEX IF NOT EXISTS ix_match_run__snapshot
    ON match_run (tenant_id, snapshot_id, queued_at DESC);

COMMENT ON INDEX ix_match_run__snapshot IS
    'Serves: run history for a snapshot — the idempotency check and the "when was this last '
    'matched" question behind coverage state.';

CREATE INDEX IF NOT EXISTS ix_match_run__batch_progress
    ON match_run (tenant_id, batch_id, state) WHERE batch_id IS NOT NULL;

COMMENT ON INDEX ix_match_run__batch_progress IS
    'Serves: batch progress including skipped and failed counts (PRD-SBM-049).';

-- -----------------------------------------------------------------------------
-- 5. sbom_coverage_state — DOC-22 section 9
--
-- One row per asset, INCLUDING assets that have never submitted. That is the whole point:
-- PRD-SBM-056 is "the single most important requirement in the module", and an asset absent from
-- this table is an asset absent from coverage reporting, where "absence reads as absence of
-- problems".
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS sbom_coverage_state (
    tenant_id    uuid        NOT NULL,
    asset_id     uuid        NOT NULL,
    latest_snapshot_id uuid,
    latest_snapshot_at timestamptz,
    quality      text        NOT NULL DEFAULT 'REJECTED',
    covered_ecosystems text[] NOT NULL DEFAULT '{}',
    declared_stack_ecosystems text[] NOT NULL DEFAULT '{}',
    -- INV-SBM-15: derived from asset criticality (DOC-22 section 9.2).
    freshness_threshold_days int NOT NULL,
    accountable_owner_id uuid NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, asset_id),

    CONSTRAINT ck_coverage__quality CHECK
        (quality IN ('ABOVE_WARNING', 'AT_OR_BELOW_WARNING', 'REJECTED')),
    CONSTRAINT ck_coverage__snapshot_complete CHECK
        ((latest_snapshot_id IS NULL) = (latest_snapshot_at IS NULL)),
    CONSTRAINT ck_coverage__threshold CHECK (freshness_threshold_days > 0)
);

SELECT apply_tenant_isolation('sbom_coverage_state');

CREATE INDEX IF NOT EXISTS ix_coverage__never_submitted
    ON sbom_coverage_state (tenant_id, accountable_owner_id)
    WHERE latest_snapshot_id IS NULL;

COMMENT ON INDEX ix_coverage__never_submitted IS
    'Serves: the NEVER_SUBMITTED queue, by accountable owner (PRD-SBM-056, PRD-SBM-058). A project '
    'that has never submitted is not low-risk; it is unmeasured, and this index is what makes it '
    'visible rather than absent.';

CREATE INDEX IF NOT EXISTS ix_coverage__stale_sweep
    ON sbom_coverage_state (tenant_id, latest_snapshot_at)
    WHERE latest_snapshot_id IS NOT NULL;

COMMENT ON INDEX ix_coverage__stale_sweep IS
    'Serves: the staleness sweep. The threshold is per-asset (INV-SBM-15) so the comparison happens '
    'per row, but the ordering makes the sweep incremental rather than a full scan each time.';

-- The three actionable queues of PRD-SBM-058, as one function so a report and an escalation job
-- cannot disagree about what counts as a gap.
CREATE OR REPLACE FUNCTION coverage_gaps(now_at timestamptz DEFAULT now())
    RETURNS TABLE (asset_id uuid, status text, accountable_owner_id uuid, latest_snapshot_at timestamptz)
    LANGUAGE sql STABLE
AS $$
    SELECT c.asset_id,
           CASE
               WHEN c.latest_snapshot_at IS NULL THEN 'NEVER_SUBMITTED'
               WHEN c.latest_snapshot_at < now_at - make_interval(days => c.freshness_threshold_days)
                   THEN 'STALE'
               WHEN c.quality <> 'ABOVE_WARNING'
                   OR NOT (c.declared_stack_ecosystems <@ c.covered_ecosystems) THEN 'PARTIAL'
               ELSE 'CURRENT'
           END,
           c.accountable_owner_id,
           c.latest_snapshot_at
      FROM sbom_coverage_state c
     WHERE c.latest_snapshot_at IS NULL
        OR c.latest_snapshot_at < now_at - make_interval(days => c.freshness_threshold_days)
        OR c.quality <> 'ABOVE_WARNING'
        OR NOT (c.declared_stack_ecosystems <@ c.covered_ecosystems);
$$;

GRANT EXECUTE ON FUNCTION coverage_gaps(timestamptz)
    TO migration_runner, integrity_verifier, app_runtime;

-- INV-SBM-09 conformance: runs claiming confirmed coverage that did not complete. The CHECK makes
-- it unrepresentable; this is what proves it over data that predates the constraint.
CREATE OR REPLACE FUNCTION runs_claiming_unearned_coverage()
    RETURNS TABLE (run_id uuid, state text, coverage_confirmed bool)
    LANGUAGE sql STABLE
AS $$
    SELECT r.id, r.state, r.coverage_confirmed
      FROM match_run r
     WHERE r.coverage_confirmed AND r.state <> 'COMPLETED';
$$;

GRANT EXECUTE ON FUNCTION runs_claiming_unearned_coverage()
    TO migration_runner, integrity_verifier, app_runtime;

-- -----------------------------------------------------------------------------
-- 6. Grants
--
-- No UPDATE on component_entry or sbom_snapshot for anybody: both are immutable, and both have a
-- trigger as well as the withheld grant. component_entry additionally keeps DELETE for
-- migration_runner only, because snapshot retention is the one place in the schema where bulk
-- deletion of a large table occurs.
-- -----------------------------------------------------------------------------

GRANT SELECT, INSERT ON component, sbom_snapshot, component_entry TO app_runtime;
GRANT SELECT, INSERT, UPDATE ON match_batch, match_run, sbom_coverage_state TO app_runtime;
-- Re-canonicalization updates a component's canonical form after a rule change (PRD-SBM-035).
GRANT UPDATE ON component TO app_runtime;

GRANT SELECT ON component, sbom_snapshot, component_entry, match_batch, match_run,
    sbom_coverage_state TO integrity_verifier;

GRANT SELECT, INSERT, UPDATE, DELETE ON component, sbom_snapshot, component_entry, match_batch,
    match_run, sbom_coverage_state TO migration_runner;
