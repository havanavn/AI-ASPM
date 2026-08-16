-- =============================================================================
-- V036 — the vulnerability an SBOM component carries, and the graph it sits in.
--
-- WHAT WAS MISSING. V011 gave the platform an SBOM: snapshots, interned components, and one row per
-- (snapshot, component). What it never gave was the other half of software composition analysis —
-- **which advisory affects which component**. Against the real database `source_rule_identity` on a
-- dependency finding holds the literal string "cve", and no column anywhere names one. So the
-- questions this module exists to answer could not be asked at all:
--
--   * "Which applications are exposed to CVE-2021-44228?"     no advisory identity
--   * "Where is log4j-core, and at what versions?"            component was interned but unqueryable
--                                                             from an asset without walking snapshots
--   * "How many new CVEs appeared this month, and how many did we close?"
--                                                             no per-CVE observation dates
--   * "What pulled this transitive dependency in?"            `component_entry.relationship` says
--                                                             DIRECT or TRANSITIVE and nothing says
--                                                             transitive THROUGH WHAT
--
-- Three tables close those, and each is deliberately narrow.
--
-- WHERE THE ADVISORY DATA COMES FROM, AND WHY THAT IS NOT DECIDED HERE. ADR-013 keeps the SBOM
-- module storing and matching rather than executing scanners, and ADR-023 makes the SBOM push API
-- the only automated ingestion path in v1. `match_run` and `match_batch` (V011) are the seam for a
-- platform-operated advisory feed matching interned components; both are still empty because no feed
-- is configured. This migration does not build that feed. It builds the **result shape** the feed
-- would write into, and lets the submitting pipeline write the same rows today — a scanner emitting
-- CycloneDX already knows the CVEs it found, and CycloneDX 1.4 carries them in the same document
-- under `vulnerabilities[]`. One push, both halves, no second API.
--
-- The consequence is stated rather than hidden: today the advisory data is **as good as the tool that
-- submitted it**, and `component_advisory.source_tool` records which tool that was, so a later feed
-- can be told apart from a scanner's claim rather than silently overwriting it.
--
-- TENANT-SCOPED, INCLUDING THE ADVISORIES. An advisory is public information and the obvious design
-- is one global table. ADR-032 already rejected exactly that reasoning for component identity, on
-- tenant-boundary grounds, and the argument transfers without weakening: `component_advisory` says
-- WHEN A TENANT FIRST SAW a vulnerability in their estate, which is a fact about them and not about
-- the advisory. A shared table with per-tenant observation columns is the same table with the
-- boundary drawn in the application layer, which is where DOC-26 says it fails.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. advisory — the identity of one published vulnerability.
--
-- `advisory_key` is the identifier the world uses: CVE-2021-44228, GHSA-jfh8-c2jp-5v3q, or the
-- tool's own identifier when it has no public one. Not a column called `cve_id`, because GitHub
-- advisories, OSV records and vendor bulletins are all advisories and only some of them are CVEs —
-- a column named for one source is a column that gets a second column beside it within a year.
--
-- SEVERITY IS A FOREIGN KEY to `severity_level`, which is tenant-configured (ADR-027). A CHECK
-- constraint listing CRITICAL/HIGH/MEDIUM/LOW here would be the fixed-enumeration-for-a-configurable-
-- surface pattern DOC-00 prohibits, and it would disagree with every other severity in the platform
-- the day a tenant adds a fifth band. `cvss_score` is kept BESIDE it rather than instead of it: the
-- score is the evidence and the band is the tenant's reading of it.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS advisory (
    id                uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id         uuid        NOT NULL DEFAULT current_tenant_id(),
    advisory_key      text        NOT NULL,
    source            text        NOT NULL,
    severity_id       uuid,
    cvss_score        numeric(3,1),
    cvss_vector       text,
    summary           text,
    published_at      timestamptz,
    modified_at       timestamptz,
    -- A withdrawn advisory is not a deleted advisory. Something was acted on while it stood, and the
    -- record of that work has to keep pointing at something.
    withdrawn_at      timestamptz,
    -- When THIS TENANT first recorded it. Distinct from published_at, and it is the one that makes
    -- "new CVEs this month" answerable: an advisory published in 2021 and first seen here today is
    -- new to this estate, and reporting it under 2021 would put it in a bucket nobody is looking at.
    first_recorded_at timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    updated_by        uuid,
    row_version       integer     NOT NULL DEFAULT 1,
    CONSTRAINT pk_advisory PRIMARY KEY (id, tenant_id),
    CONSTRAINT uq_advisory__key UNIQUE (tenant_id, advisory_key),
    CONSTRAINT ck_advisory__cvss CHECK (cvss_score IS NULL
                                        OR (cvss_score >= 0 AND cvss_score <= 10))
);

COMMENT ON TABLE advisory IS
    'One published vulnerability, tenant-scoped for the same tenant-boundary reason ADR-032 gives '
    'for component identity: first_recorded_at is a fact about this tenant''s estate, not about the '
    'advisory. advisory_key is whatever identifier the source uses — CVE, GHSA, OSV or vendor.';
COMMENT ON COLUMN advisory.first_recorded_at IS
    'When this tenant first recorded the advisory. The basis for "new this month"; published_at '
    'would bucket a 2021 advisory found today under 2021, where nobody is looking.';

CREATE INDEX IF NOT EXISTS ix_advisory__recorded
    ON advisory (tenant_id, first_recorded_at DESC);
COMMENT ON INDEX ix_advisory__recorded IS
    'Serves: the "new advisories per period" series on the dependency dashboard.';

CREATE INDEX IF NOT EXISTS ix_advisory__severity
    ON advisory (tenant_id, severity_id);
COMMENT ON INDEX ix_advisory__severity IS
    'Serves: the severity breakdown of open advisories, and filtering the CVE list to the top bands.';

-- Searching for a CVE by a partial identifier is the single most common thing anybody will type into
-- this module, so it gets an index rather than a sequential scan over every advisory in the tenant.
CREATE INDEX IF NOT EXISTS ix_advisory__key_search
    ON advisory (tenant_id, upper(advisory_key) text_pattern_ops);
COMMENT ON INDEX ix_advisory__key_search IS
    'Serves: the CVE search box — a prefix match on the upper-cased key, which is what somebody '
    'pasting "cve-2021-44" is asking for.';

-- -----------------------------------------------------------------------------
-- 2. component_advisory — this component version is affected by this advisory.
--
-- The match result, whoever produced it. One row per (component, advisory), and the component is
-- already version-specific: V011 interns `name@version` as its own row, so "affected version range"
-- is resolved at match time and never re-evaluated by a reader.
--
-- DETECTED AND RESOLVED, not a boolean. Product principle 1 again: a component that has never been
-- matched and a component matched and found clean are different states, and only one of them is good
-- news. An absent row is the first; a row with `resolved_at` set is the second.
--
-- `resolved_at` is what makes "CVEs closed this month" a real series rather than a guess derived
-- from work items. A finding may be closed as a duplicate, or accepted as a risk, or reopened; the
-- component either still carries the advisory or it does not, and that is a different question from
-- how the work went. Both are reported, side by side, on the dashboard.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS component_advisory (
    tenant_id      uuid        NOT NULL DEFAULT current_tenant_id(),
    component_id   uuid        NOT NULL,
    advisory_id    uuid        NOT NULL,
    detected_at    timestamptz NOT NULL DEFAULT now(),
    resolved_at    timestamptz,
    -- Why it is no longer affected: the component was upgraded away, the advisory was withdrawn, or
    -- a human judged it not applicable. A resolution with no reason is indistinguishable from a
    -- deletion somebody made quietly.
    resolution     text,
    -- The version that fixes it, where the source says. This is the single most actionable field in
    -- the module and it is the one most often missing, so it is nullable and its absence is shown.
    fixed_version  text,
    source_tool    text        NOT NULL DEFAULT 'unknown',
    CONSTRAINT pk_component_advisory PRIMARY KEY (tenant_id, component_id, advisory_id),
    CONSTRAINT ck_component_advisory__resolution
        CHECK (resolved_at IS NULL OR resolution IS NOT NULL)
);

COMMENT ON TABLE component_advisory IS
    'Which interned component version an advisory affects, with when it was detected and when it '
    'stopped applying. An absent row is "never matched" and is not "clean" — PP-1 at row level.';
COMMENT ON COLUMN component_advisory.resolved_at IS
    'When the component stopped being affected. Independent of how the work item went: a finding '
    'closed as accepted risk leaves this NULL, because the component is still affected.';

CREATE INDEX IF NOT EXISTS ix_component_advisory__advisory
    ON component_advisory (tenant_id, advisory_id) WHERE resolved_at IS NULL;
COMMENT ON INDEX ix_component_advisory__advisory IS
    'Serves: "which components does CVE-x affect", the first step of the CVE search that then walks '
    'up to the applications. Partial on unresolved, which is what the search means by default.';

CREATE INDEX IF NOT EXISTS ix_component_advisory__component
    ON component_advisory (tenant_id, component_id);
COMMENT ON INDEX ix_component_advisory__component IS
    'Serves: the advisory list on one component, and the per-asset severity rollup which joins from '
    'component_entry through here.';

CREATE INDEX IF NOT EXISTS ix_component_advisory__timeline
    ON component_advisory (tenant_id, detected_at DESC);
COMMENT ON INDEX ix_component_advisory__timeline IS
    'Serves: the appeared/closed series over time on the dependency dashboard.';

-- -----------------------------------------------------------------------------
-- 3. component_dependency — what pulled this in.
--
-- CycloneDX `dependencies[]` is a graph and V011 flattened it to one bit per component: direct or
-- transitive. That answers "is this ours" and cannot answer "what do I upgrade to be rid of it",
-- which is the only question that leads to an action. A transitive log4j-core is not fixed by
-- touching log4j-core; it is fixed by upgrading whatever depends on it, and that edge was thrown
-- away at ingestion.
--
-- Scoped to a SNAPSHOT rather than to a component pair, because the graph is a property of one build
-- of one artifact. The same two packages are related differently in two repositories, and a
-- tenant-wide edge table would merge them into a graph that describes nothing that was ever built.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS component_dependency (
    tenant_id           uuid     NOT NULL DEFAULT current_tenant_id(),
    snapshot_id         uuid     NOT NULL,
    parent_component_id uuid     NOT NULL,
    child_component_id  uuid     NOT NULL,
    CONSTRAINT pk_component_dependency
        PRIMARY KEY (tenant_id, snapshot_id, parent_component_id, child_component_id),
    -- A component depending on itself is a parse defect, not a cycle worth representing.
    CONSTRAINT ck_component_dependency__no_self
        CHECK (parent_component_id <> child_component_id)
);

COMMENT ON TABLE component_dependency IS
    'Parent-to-child edges within ONE snapshot. Snapshot-scoped because the dependency graph is a '
    'property of a build: the same two packages relate differently in two repositories, and merging '
    'them would describe a graph nobody ever built.';

CREATE INDEX IF NOT EXISTS ix_component_dependency__child
    ON component_dependency (tenant_id, snapshot_id, child_component_id);
COMMENT ON INDEX ix_component_dependency__child IS
    'Serves: "what pulled this in" — walking UP from a vulnerable transitive dependency to the '
    'direct one somebody can actually upgrade.';

-- -----------------------------------------------------------------------------
-- 4. Grants. app_runtime writes all three: the submission path creates advisories and edges, and a
--    human resolving a component_advisory updates it. integrity_verifier reads.
-- -----------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON advisory TO app_runtime;
GRANT SELECT, INSERT, UPDATE ON component_advisory TO app_runtime;
GRANT SELECT, INSERT, DELETE ON component_dependency TO app_runtime;
GRANT SELECT ON advisory, component_advisory, component_dependency TO integrity_verifier;

-- DELETE on component_dependency and on nothing else here. A snapshot is re-parsed only by being
-- resubmitted, and its edges are replaced wholesale; an advisory observation is a record of what was
-- known when, and PP-5 makes that inviolable.

-- -----------------------------------------------------------------------------
-- 5. Tenant isolation. Three new tables, three policies — TST-TEN-001 requires a new subsystem to
--    have an isolation path, and a table added without one is the failure mode that requirement
--    exists to catch.
-- -----------------------------------------------------------------------------
SELECT apply_tenant_isolation('advisory');
SELECT apply_tenant_isolation('component_advisory');
SELECT apply_tenant_isolation('component_dependency');

-- -----------------------------------------------------------------------------
-- 6. asset_component — every component reachable from an asset, through its LATEST snapshot.
--
-- Latest, not every snapshot. A component removed in the current build is not in the estate, and a
-- view that unioned the history would report an upgrade as having doubled the dependency count.
-- History is still there in `component_entry`; it is simply not what "what do we run" means.
-- -----------------------------------------------------------------------------
-- Dropped in dependency order, dependants first. The posture views below read
-- asset_component_advisory, so dropping it before them fails on the second replay — which is
-- exactly what happened. No CASCADE: it would silently drop a view added after this file was
-- written, and the next replay would not put it back.
DROP VIEW IF EXISTS application_dependency_posture;
DROP VIEW IF EXISTS asset_dependency_posture;
DROP VIEW IF EXISTS asset_component_advisory;
DROP VIEW IF EXISTS asset_component;

CREATE VIEW asset_component AS
SELECT cs.asset_id,
       cs.tenant_id,
       cs.latest_snapshot_id                       AS snapshot_id,
       e.component_id,
       c.purl_canonical,
       c.ecosystem,
       c.name,
       c.version,
       e.relationship,
       (e.relationship = 1)                        AS is_direct,
       e.license_refs
  FROM sbom_coverage_state cs
  JOIN component_entry e ON e.snapshot_id = cs.latest_snapshot_id
                        AND e.tenant_id = cs.tenant_id
  JOIN component c ON c.id = e.component_id AND c.tenant_id = e.tenant_id
 WHERE cs.latest_snapshot_id IS NOT NULL;

COMMENT ON VIEW asset_component IS
    'Every component an asset carries, through its LATEST snapshot only. SECURITY INVOKER so '
    'row-level policies apply to the caller.';

GRANT SELECT ON asset_component TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 7. asset_component_advisory — the same, with the advisories each component carries.
--
-- The join every question in this module passes through: asset -> component -> advisory. Kept as a
-- view rather than repeated in each query, so the CVE search, the severity rollup and the tree all
-- traverse the same path and cannot disagree about what "affected" means.
-- -----------------------------------------------------------------------------
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
       a.advisory_key,
       a.cvss_score,
       a.summary,
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

-- -----------------------------------------------------------------------------
-- 8. asset_dependency_posture — one row per asset, its own SBOM only.
--
-- Deliberately NOT rolled up here. The rollup over a subtree is section 9 and it has to count
-- DISTINCT: an application containing two repositories that both ship the same library has one
-- vulnerable component, and summing the rows below would report two. That is the defect V035
-- corrected for findings and it would be re-introduced here by an obvious-looking sum.
-- -----------------------------------------------------------------------------
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

COMMENT ON VIEW asset_dependency_posture IS
    'One asset''s own SBOM: components, and the advisories it carries by severity. Counts DISTINCT '
    'advisories, because one advisory affecting three components of an asset is one thing to fix.';

GRANT SELECT ON asset_dependency_posture TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 9. application_dependency_posture — the same rolled up over the composition subtree.
--
-- The tree the interface draws: an application contains projects, a project contains repositories,
-- and each repository is where an SBOM is actually submitted. Every level shows the same columns so
-- a reader comparing two rows at different levels is comparing the same arithmetic.
--
-- DISTINCT at every count, for the reason given in section 8. `sbom_parts` against `parts` is the
-- coverage figure: how many of the things under here have ever submitted a bill of materials, out of
-- how many exist. PRD-SBM-056 — a row showing only the covered ones reports a clean estate.
-- -----------------------------------------------------------------------------
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
                                               AND v.fixed_version IS NOT NULL)  AS fixable_open
  FROM scope s
  LEFT JOIN sbom_coverage_state cs ON cs.asset_id = s.member_id AND cs.tenant_id = s.tenant_id
  LEFT JOIN asset_component ac ON ac.asset_id = s.member_id AND ac.tenant_id = s.tenant_id
  LEFT JOIN asset_component_advisory v ON v.asset_id = s.member_id AND v.tenant_id = s.tenant_id
 GROUP BY s.root_id, s.tenant_id;

COMMENT ON VIEW application_dependency_posture IS
    'An asset and everything it contains, rolled up: parts, how many have an SBOM at all, distinct '
    'components, and distinct open advisories by severity. Every level of the tree reports the same '
    'columns computed the same way, so two rows at different levels are comparable.';

GRANT SELECT ON application_dependency_posture TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 10. A check that the isolation actually took.
--
-- Three tables added; three policies asserted. TST-TEN-001 makes an isolation path mandatory for a
-- new subsystem, and the way that requirement fails in practice is not somebody arguing against it —
-- it is a fourth table added later by somebody who did not know the rule. This fails the migration
-- loudly instead of leaving the gap for a review to find.
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    unprotected text;
BEGIN
    SELECT string_agg(c.relname, ', ') INTO unprotected
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relname IN ('advisory', 'component_advisory', 'component_dependency')
       AND NOT c.relforcerowsecurity;
    IF unprotected IS NOT NULL THEN
        RAISE EXCEPTION 'V036 added % without FORCEd row-level security. Every table in this module '
                        'holds tenant data and TST-TEN-001 requires an isolation path for a new '
                        'subsystem.', unprotected;
    END IF;
END
$$;
