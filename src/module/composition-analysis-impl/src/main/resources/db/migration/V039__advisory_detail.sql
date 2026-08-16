-- =============================================================================
-- V039 — the advisory fields every scanner already sends, and the one that changes what to do.
--
-- WHY NOW. V036 stored the identity, a severity, a score and a summary — enough to rank an advisory
-- and not enough to act on one. Checking a real Trivy payload field by field showed five things
-- arriving and being dropped, and every scanner considered for support (Grype, OSV-Scanner,
-- npm audit, pip-audit) carries the same five. Widening once serves all of them; adding a column per
-- tool as each is integrated is how a schema acquires four columns that mean the same thing.
--
-- THE ONE THAT IS NOT COSMETIC IS `status`. Trivy reports `will_not_fix` and `end_of_life`, and OSV
-- and Grype have equivalents. A vulnerability the upstream maintainer has declined to fix is not a
-- vulnerability awaiting an upgrade — it needs a compensating control or an accepted risk with an
-- expiry, which is a different conversation with a different person. Until now the interface showed
-- it identically to one whose fix simply has not shipped yet, and the two look the same only to a
-- platform that never recorded the difference.
--
-- ADDITIVE ONLY. Every column is nullable with no default backfill: an advisory recorded before this
-- migration genuinely does not have these values, and inventing them — defaulting `status` to
-- 'affected', say — would assert something no tool ever said. An absent value is the honest record of
-- an absent value (PP-1 at column level).
-- =============================================================================

ALTER TABLE advisory
    -- The weakness class. Plural because an advisory routinely names two — Log4Shell is CWE-917 and
    -- CWE-502 — and picking one would be the platform deciding which half of the finding matters.
    ADD COLUMN IF NOT EXISTS cwe_ids     text[],
    -- Where to read more. The single most requested thing missing from an advisory row: an engineer
    -- triaging one wants the upstream write-up, and pasting the identifier into a search engine is
    -- what they do instead when the platform does not carry it.
    ADD COLUMN IF NOT EXISTS references_urls text[],
    -- The long form, kept SEPARATE from `summary`. Trivy sends Title and Description and they are not
    -- the same field: a table needs the one that fits a line and a detail panel needs the one that
    -- explains. Storing only one forces every reader to accept the wrong length.
    ADD COLUMN IF NOT EXISTS description text,
    -- Which database the claim came from — GHSA, NVD, OSV, a vendor bulletin. Distinct from
    -- `source`, which records the SUBMITTING tool. Two different questions: who scanned, and who
    -- published. Conflating them makes "is this advisory authoritative" unanswerable.
    ADD COLUMN IF NOT EXISTS data_source text;

COMMENT ON COLUMN advisory.references_urls IS
    'Upstream write-ups. Named references_urls rather than references because REFERENCES is a '
    'reserved word in SQL, and a column that has to be quoted at every use is a column somebody '
    'eventually fails to quote.';
COMMENT ON COLUMN advisory.data_source IS
    'The advisory database that published it. advisory.source records the tool that SUBMITTED it; '
    'these are different questions and answering both is what makes authority checkable.';

ALTER TABLE component_advisory
    -- What upstream says about a fix existing at all. Not a CHECK constraint: the vocabulary belongs
    -- to whichever scanner reported it, and enumerating today's five values in the schema would
    -- reject the sixth a tool adds next year — the fixed-enumeration pattern DOC-00 prohibits. The
    -- interface treats the values it knows and shows the rest verbatim.
    ADD COLUMN IF NOT EXISTS status text;

COMMENT ON COLUMN component_advisory.status IS
    'Upstream fix status as the reporting tool stated it — fixed, affected, will_not_fix, '
    'fix_deferred, end_of_life. will_not_fix and end_of_life mean the work is a decision rather than '
    'an upgrade, which is a different conversation with a different person.';

-- Serves the panel filter an engineer reaches for first: everything under here that upstream has
-- declined to fix, because those are the rows that need a decision rather than a version bump.
CREATE INDEX IF NOT EXISTS ix_component_advisory__status
    ON component_advisory (tenant_id, status) WHERE resolved_at IS NULL AND status IS NOT NULL;
COMMENT ON INDEX ix_component_advisory__status IS
    'Serves: filtering the per-node advisory list to the ones upstream will not fix.';

-- -----------------------------------------------------------------------------
-- The views widen with the columns. asset_component_advisory is the one path every question in this
-- module takes (V036 section 7), so a column absent from it is a column no reader can reach.
--
-- Dropped in dependency order, dependants first — the posture views read this one, and dropping the
-- dependency before them fails on the second replay. That has now happened twice in this module.
-- -----------------------------------------------------------------------------
DROP VIEW IF EXISTS application_dependency_posture;
DROP VIEW IF EXISTS asset_dependency_posture;
DROP VIEW IF EXISTS asset_component_advisory;

CREATE VIEW asset_component_advisory AS
SELECT ac.asset_id,
       ac.tenant_id,
       ac.component_id,
       ac.purl_canonical,
       ac.ecosystem,
       ac.name,
       ac.version,
       ac.is_direct,
       ca.advisory_id,
       ca.detected_at,
       ca.resolved_at,
       ca.fixed_version,
       ca.source_tool,
       ca.status,
       a.advisory_key,
       a.cvss_score,
       a.cvss_vector,
       a.summary,
       a.description,
       a.cwe_ids,
       a.references_urls,
       a.data_source,
       a.published_at,
       a.withdrawn_at,
       s.code                                      AS severity_code,
       s.ordinal                                   AS severity_ordinal
  FROM asset_component ac
  JOIN component_advisory ca ON ca.component_id = ac.component_id
                            AND ca.tenant_id = ac.tenant_id
  JOIN advisory a ON a.id = ca.advisory_id AND a.tenant_id = ca.tenant_id
  LEFT JOIN severity_level s ON s.id = a.severity_id;

COMMENT ON VIEW asset_component_advisory IS
    'asset -> component -> advisory, the one path every question in this module takes. A LEFT JOIN '
    'to severity_level so an advisory nobody has rated appears rather than being dropped from a '
    'count of vulnerabilities.';

GRANT SELECT ON asset_component_advisory TO app_runtime, integrity_verifier;

-- Recreated unchanged from V036 sections 8 and 9, because dropping them to widen their dependency
-- means recreating them, and a replayable migration has to be authoritative at the moment it runs.
CREATE VIEW asset_dependency_posture AS
SELECT cs.asset_id,
       cs.tenant_id,
       cs.latest_snapshot_id,
       cs.latest_snapshot_at,
       cs.quality,
       (SELECT count(*) FROM asset_component ac
         WHERE ac.asset_id = cs.asset_id)                            AS component_count,
       (SELECT count(*) FROM asset_component ac
         WHERE ac.asset_id = cs.asset_id AND ac.is_direct)           AS direct_count,
       (SELECT count(DISTINCT v.advisory_id) FROM asset_component_advisory v
         WHERE v.asset_id = cs.asset_id AND v.resolved_at IS NULL)   AS advisory_open,
       (SELECT count(DISTINCT v.advisory_id) FROM asset_component_advisory v
         WHERE v.asset_id = cs.asset_id AND v.resolved_at IS NULL
           AND v.severity_code = 'CRITICAL')                         AS critical_open,
       (SELECT count(DISTINCT v.advisory_id) FROM asset_component_advisory v
         WHERE v.asset_id = cs.asset_id AND v.resolved_at IS NULL
           AND v.severity_code = 'HIGH')                             AS high_open,
       (SELECT count(DISTINCT v.advisory_id) FROM asset_component_advisory v
         WHERE v.asset_id = cs.asset_id AND v.resolved_at IS NULL
           AND v.severity_code = 'MEDIUM')                           AS medium_open,
       (SELECT count(DISTINCT v.advisory_id) FROM asset_component_advisory v
         WHERE v.asset_id = cs.asset_id AND v.resolved_at IS NULL
           AND v.severity_code = 'LOW')                              AS low_open,
       (SELECT count(DISTINCT v.component_id) FROM asset_component_advisory v
         WHERE v.asset_id = cs.asset_id AND v.resolved_at IS NULL)   AS vulnerable_components,
       (SELECT count(DISTINCT v.advisory_id) FROM asset_component_advisory v
         WHERE v.asset_id = cs.asset_id AND v.resolved_at IS NULL
           AND v.fixed_version IS NOT NULL)                          AS fixable_open
  FROM sbom_coverage_state cs;

GRANT SELECT ON asset_dependency_posture TO app_runtime, integrity_verifier;

CREATE VIEW application_dependency_posture AS
WITH scope AS (
    SELECT a.id AS root_id, a.tenant_id, a.id AS member_id FROM asset a
    UNION
    SELECT c.root_id, c.tenant_id, c.asset_id FROM asset_composition c
)
SELECT s.root_id                                                     AS asset_id,
       s.tenant_id,
       count(DISTINCT s.member_id) - 1                               AS parts,
       count(DISTINCT cs.asset_id) FILTER (WHERE cs.latest_snapshot_id IS NOT NULL)
                                                                     AS sbom_parts,
       max(cs.latest_snapshot_at)                                    AS latest_snapshot_at,
       count(DISTINCT ac.component_id)                               AS component_count,
       count(DISTINCT ac.component_id) FILTER (WHERE ac.is_direct)   AS direct_count,
       count(DISTINCT v.advisory_id) FILTER (WHERE v.resolved_at IS NULL)
                                                                     AS advisory_open,
       count(DISTINCT v.advisory_id) FILTER (WHERE v.resolved_at IS NULL
                                               AND v.severity_code = 'CRITICAL') AS critical_open,
       count(DISTINCT v.advisory_id) FILTER (WHERE v.resolved_at IS NULL
                                               AND v.severity_code = 'HIGH')     AS high_open,
       count(DISTINCT v.advisory_id) FILTER (WHERE v.resolved_at IS NULL
                                               AND v.severity_code = 'MEDIUM')   AS medium_open,
       count(DISTINCT v.advisory_id) FILTER (WHERE v.resolved_at IS NULL
                                               AND v.severity_code = 'LOW')      AS low_open,
       count(DISTINCT v.component_id) FILTER (WHERE v.resolved_at IS NULL)
                                                                     AS vulnerable_components,
       count(DISTINCT v.advisory_id) FILTER (WHERE v.resolved_at IS NULL
                                               AND v.fixed_version IS NOT NULL)  AS fixable_open,
       -- New: how many of the open ones upstream has declined to fix. The figure that says how much
       -- of a backlog is decisions rather than upgrades, which no severity count can express.
       count(DISTINCT v.advisory_id) FILTER (WHERE v.resolved_at IS NULL
                                               AND v.status IN ('will_not_fix', 'end_of_life'))
                                                                     AS unfixable_open
  FROM scope s
  LEFT JOIN sbom_coverage_state cs ON cs.asset_id = s.member_id AND cs.tenant_id = s.tenant_id
  LEFT JOIN asset_component ac ON ac.asset_id = s.member_id AND ac.tenant_id = s.tenant_id
  LEFT JOIN asset_component_advisory v ON v.asset_id = s.member_id AND v.tenant_id = s.tenant_id
 GROUP BY s.root_id, s.tenant_id;

COMMENT ON VIEW application_dependency_posture IS
    'An asset and everything it contains, rolled up. Counts DISTINCT advisories, so the same one '
    'affecting three components under an application is one thing to fix. unfixable_open is the part '
    'of the backlog that is a decision rather than an upgrade.';

GRANT SELECT ON application_dependency_posture TO app_runtime, integrity_verifier;
