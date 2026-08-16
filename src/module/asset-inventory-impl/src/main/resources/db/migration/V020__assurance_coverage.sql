-- =============================================================================
-- V020 — assurance coverage: what has looked at this application, and when.
--
-- THE GAP THIS CLOSES. Every surface built so far answers "how many findings". None answers "what
-- has been looked for", and the second question governs the first: eight open findings on an
-- application that has had a pentest, a SAST run and an SBOM means something quite different from
-- eight on one where only a dependency scanner has ever run.
--
-- Product principle 1 is "absence of evidence is not evidence of absence… measured-and-clean must be
-- distinguishable from not-measured". The platform has enforced that for individual figures — an
-- unscored application renders a word rather than a zero. It has not enforced it for the ESTATE: an
-- application with no CODE findings looks identical to one nothing has ever scanned for code
-- defects, and today the interface presents both as a low number.
--
-- The finding class IS the assurance signal. DOC-06's classes map one-to-one onto the activity that
-- produces them:
--
--     CODE            static analysis of source
--     DEPENDENCY      software composition analysis
--     RUNTIME         dynamic testing against a running instance
--     SECRET          secret scanning
--     CONFIGURATION   configuration review
--     INFRASTRUCTURE  infrastructure and network assessment
--     MANUAL          human assessment — a penetration test
--
-- So "has anything ever done a penetration test here" is answerable from data already recorded, and
-- the absence of a class is the answer. That absence is what these views make visible.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0. Dropped before recreating, and this was added later.
--
-- V035 redefines these three views over `asset_finding_link`, and its `application_assurance` counts
-- DISTINCT findings — bigint — where the definition below sums per-part tallies, which is numeric.
-- The migrations replay in full on every start, so the second run reached this file with V035's
-- version in place and CREATE OR REPLACE failed with "cannot change data type of view column". That
-- takes the migration container down; it does not degrade. Same class of failure as V018 section 3
-- and for the same reason: each migration's definition has to be authoritative at the moment it runs.
--
-- The rollup goes first because the definition below reads the per-asset view. No CASCADE: a CASCADE
-- would silently drop a view added after this file was written, and the next replay would not put it
-- back.
-- -----------------------------------------------------------------------------
DROP VIEW IF EXISTS application_assurance;
DROP VIEW IF EXISTS asset_assurance;
DROP VIEW IF EXISTS asset_remediation;

-- -----------------------------------------------------------------------------
-- 1. asset_assurance — per asset, per activity, when it last produced evidence.
--
-- One row per (asset, class) that has EVER produced a finding. A class with no row is the point:
-- the interface enumerates the product-fixed class list and renders the missing ones as never.
--
-- last_evidence_at is the last DETECTION, not the last finding created. A scanner that runs weekly
-- and finds nothing new still updates last_detected_at on what it re-confirms, so this answers "when
-- did something last look" rather than "when did something last find".
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW asset_assurance AS
SELECT i.asset_id,
       f.tenant_id,
       f.finding_class,
       count(*)                                       AS finding_count,
       count(*) FILTER (WHERE f.state = 'OPEN')       AS open_count,
       max(f.last_detected_at)                        AS last_evidence_at,
       min(f.first_detected_at)                       AS first_evidence_at,
       -- The tools, so "which scanner produced this" is answerable without opening a finding. A
       -- class covered by one tool and a class covered by three are different confidence levels.
       array_agg(DISTINCT f.source_tool)              AS source_tools
  FROM finding_asset_impact i
  JOIN finding f ON f.id = i.finding_id
 GROUP BY i.asset_id, f.tenant_id, f.finding_class;

COMMENT ON VIEW asset_assurance IS
    'Per asset and finding class: when evidence was last produced and by which tools. A class ABSENT '
    'from this view is the answer to "has anything ever looked" — PP-1 applied to the estate rather '
    'than to a single figure.';

GRANT SELECT ON asset_assurance TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 2. application_assurance — the same, rolled up over an application's whole composition.
--
-- A SAST run against one service of an application is evidence for that service. Whether it counts
-- as evidence for the application is a judgement, and this view takes the position that it does —
-- with the component count beside it, so a reader can see that one of five parts was scanned. The
-- alternative, requiring every part to be covered before the class counts as covered, reports NEVER
-- for an application somebody did scan, which is the failure in the other direction.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW application_assurance AS
WITH scope AS (
    SELECT a.id AS root_id, a.tenant_id, a.id AS member_id FROM asset a
    UNION
    SELECT c.root_id, c.tenant_id, c.asset_id FROM asset_composition c
)
SELECT s.root_id                                   AS asset_id,
       s.tenant_id,
       v.finding_class,
       sum(v.finding_count)                        AS finding_count,
       sum(v.open_count)                           AS open_count,
       max(v.last_evidence_at)                     AS last_evidence_at,
       count(DISTINCT v.asset_id)                  AS covered_parts
  FROM scope s
  JOIN asset_assurance v ON v.asset_id = s.member_id
 GROUP BY s.root_id, s.tenant_id, v.finding_class;

COMMENT ON VIEW application_assurance IS
    'Assurance coverage rolled up over an application and its parts, with covered_parts so "one of '
    'five services was scanned" is distinguishable from "the application was scanned".';

GRANT SELECT ON application_assurance TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 3. asset_sbom_state — the SBOM answer, with NEVER_SUBMITTED preserved.
--
-- PRD-SBM-056 requires that an asset which has never submitted an SBOM appears in coverage rather
-- than being absent from it. sbom_coverage_state already holds a row per asset with a NULL
-- latest_snapshot_at for exactly that case; what was missing is a staleness verdict, because
-- "submitted eight months ago" and "submitted yesterday" are both non-null and mean opposite things.
--
-- The threshold is per-row (freshness_threshold_days), not a constant here: DOC-22 makes it
-- configurable, and a hardcoded thirty days would silently override a tenant that chose fourteen.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW asset_sbom_state AS
SELECT c.asset_id,
       c.tenant_id,
       c.latest_snapshot_at,
       c.quality,
       c.freshness_threshold_days,
       c.covered_ecosystems,
       c.declared_stack_ecosystems,
       CASE
         WHEN c.latest_snapshot_at IS NULL THEN 'NEVER_SUBMITTED'
         WHEN c.latest_snapshot_at < now() - make_interval(days => c.freshness_threshold_days)
              THEN 'STALE'
         ELSE 'CURRENT'
       END                                          AS freshness,
       -- Ecosystems declared but never covered by a submission. A Java service whose SBOM only ever
       -- described its npm dependencies is not covered, and a boolean "has an SBOM" says it is.
       ARRAY(SELECT e FROM unnest(c.declared_stack_ecosystems) AS e
              WHERE NOT (e = ANY (c.covered_ecosystems)))  AS uncovered_ecosystems
  FROM sbom_coverage_state c;

COMMENT ON VIEW asset_sbom_state IS
    'SBOM freshness per asset, keeping NEVER_SUBMITTED as a value rather than an absent row '
    '(PRD-SBM-056), and naming ecosystems declared but never covered by any submission.';

GRANT SELECT ON asset_sbom_state TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 4. asset_remediation — how long fixing actually takes, for the ones that were fixed.
--
-- Mean time to remediate, computed only over findings that HAVE been closed. An average that
-- silently includes open findings as though they were closed today is the flattering version, and it
-- improves whenever a new finding arrives.
--
-- open_oldest_days is the honest companion: the oldest thing still open. A team with a good average
-- and a two-year-old critical has a problem the average hides.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW asset_remediation AS
SELECT i.asset_id,
       f.tenant_id,
       count(*) FILTER (WHERE f.closed_at IS NOT NULL)                        AS closed_count,
       round(avg(EXTRACT(EPOCH FROM (f.closed_at - f.first_detected_at)) / 86400.0)
             FILTER (WHERE f.closed_at IS NOT NULL))::int                     AS mean_days_to_close,
       max(EXTRACT(DAY FROM (now() - f.first_detected_at))) FILTER
           (WHERE f.state = 'OPEN')::int                                      AS open_oldest_days,
       count(*) FILTER (WHERE f.state = 'OPEN'
                          AND f.first_detected_at < now() - interval '90 days') AS open_over_90_days
  FROM finding_asset_impact i
  JOIN finding f ON f.id = i.finding_id
 GROUP BY i.asset_id, f.tenant_id;

COMMENT ON VIEW asset_remediation IS
    'Time to remediate over CLOSED findings only, with the oldest still-open age beside it. An '
    'average that counts open findings as closed-today improves every time a new one arrives.';

GRANT SELECT ON asset_remediation TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 5. An index for the assurance queries.
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS ix_finding__class_detected
    ON finding (tenant_id, finding_class, last_detected_at DESC);
COMMENT ON INDEX ix_finding__class_detected IS
    'Serves: the assurance coverage panel, which asks for the most recent evidence per finding class '
    'on every application page load.';
