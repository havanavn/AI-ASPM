-- =============================================================================
-- V035 — how a finding reaches an application, stated once.
--
-- THE DEFECT THIS CORRECTS. Every count on the application inventory and the application detail page
-- read `finding_asset_impact` and `assessment_scope_asset`. Against the real database those tables
-- hold 15 and 5 rows, over 658 findings and 211 requests. So every application reported zero
-- findings and almost every one reported zero requests, while 649 of those findings were in fact
-- attributable to an application. An inventory that reports a clean estate because it read the wrong
-- edge is the single most dangerous output this product can produce (product principle 1), and it is
-- the reason this file exists rather than a widened query in one page.
--
-- WHY THE COUNTS WERE ZERO. There are two ways a finding comes to concern an asset, and only one of
-- them was being read:
--
--   1. `finding_asset_impact` — the direct edge. Written by the ingestion pipeline, and by the
--      manual finding form ONLY when the assessor names a specific asset (AssessmentService, the
--      `assetId != null` branch). Most work does not name one, so most findings have no row here.
--
--   2. The request the finding was discovered in. `finding.discovered_in_request_id` names the
--      request; V029's `assessment_request_scope_asset` records what that request was raised
--      against — both the project the requester named and the application derived from the
--      composition graph. This is the edge intake actually creates, on every request.
--
-- `OverviewInsights#facts` already reaches an asset through path 2 and says why in its own comment.
-- That judgement was correct and was made in one query; product principle 10 — one name, one
-- meaning, one place — says it belongs in one place instead. `asset_finding_link` below is that
-- place, and everything that counts findings per asset is repointed at it in this file.
--
-- WHAT IS DELIBERATELY NOT DONE HERE: a backfill of `finding_asset_impact` from path 2. The two
-- edges do not mean the same thing. "This finding was found while assessing that application" is
-- weaker than "this finding affects that asset" — a pentest of an application can raise a finding
-- whose true subject is a shared platform service. Writing the weaker claim into the table that
-- holds the stronger one would erase the difference permanently and silently, and a backfill under
-- row-level security has already shipped one silent defect in this deployment. The union is
-- computed at read time, and `direct_impact` keeps the two distinguishable.
--
-- SECURITY INVOKER throughout (the default, and load-bearing). A DEFINER view over tenant-isolated
-- tables runs with the definer's row-level context and hands every tenant's findings to any caller.
-- Restated here because the failure is silent — the same reasoning as V018 section 3.
--
-- SCOPE IS STILL NOT APPLIED HERE. Row-level security gives tenant isolation; the caller's scope
-- within a tenant is composed into the query by the application layer, because SEC-AUZ-016 requires
-- the predicate in the retrieval rather than a filter over its result. A view named as though it
-- were "the findings you may see" would be trusted as one.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0. Drop order.
--
-- These are replayed in full on every start, so each definition must be authoritative at the moment
-- it runs. DROP then CREATE, never CREATE OR REPLACE, for any view whose column list this file
-- changes: CREATE OR REPLACE cannot drop or reorder a column, and the second run of a widened view
-- failed with "cannot drop columns from view" and took the migration container down once already
-- (V018 section 3 records it).
--
-- Dropped children first. `application_posture` reads `asset_finding_tally`; nothing reads
-- `application_inventory`. No CASCADE anywhere — a CASCADE here would silently drop a view added
-- after this migration was written, and the next replay would not put it back.
-- -----------------------------------------------------------------------------
DROP VIEW IF EXISTS application_inventory;
DROP VIEW IF EXISTS application_posture;
DROP VIEW IF EXISTS application_assurance;
DROP VIEW IF EXISTS asset_assurance;
DROP VIEW IF EXISTS asset_remediation;
DROP VIEW IF EXISTS asset_finding_tally;
DROP VIEW IF EXISTS application_finding;
DROP VIEW IF EXISTS application_request;
DROP VIEW IF EXISTS asset_request_link;
DROP VIEW IF EXISTS asset_finding_link;

-- -----------------------------------------------------------------------------
-- 1. asset_finding_link — the one definition of "this finding concerns this asset".
--
-- One row per (asset, finding), whichever way the finding reaches it. `direct_impact` is true when
-- the strong edge exists, so a caller that needs "affects" rather than "was found while assessing"
-- can still ask for it — and so the difference survives, which is the whole reason this is a union
-- rather than a backfill.
--
-- The GROUP BY, not DISTINCT: a finding with both a direct impact row and a request scope row must
-- be ONE row carrying direct_impact = true, and DISTINCT over the four columns would return two
-- rows and double every count built on it.
--
-- Legacy path included: `assessment_scope_asset` hangs off the assessment rather than the request,
-- which is why V029 added the request-level table. Five rows still use it. They are five real
-- assessments and dropping them from the count to keep the query tidy would be the same class of
-- error this file corrects.
-- -----------------------------------------------------------------------------
CREATE VIEW asset_finding_link AS
SELECT tenant_id,
       asset_id,
       finding_id,
       bool_or(direct_impact) AS direct_impact
  FROM (
        SELECT i.tenant_id, i.asset_id, i.finding_id, true AS direct_impact
          FROM finding_asset_impact i
         WHERE i.resolved_at IS NULL
        UNION ALL
        SELECT f.tenant_id, ra.asset_id, f.id, false
          FROM finding f
          JOIN assessment_request_scope_asset ra
            ON ra.request_id = f.discovered_in_request_id
         WHERE f.discovered_in_request_id IS NOT NULL
        UNION ALL
        SELECT f.tenant_id, sa.asset_id, f.id, false
          FROM finding f
          JOIN assessment asm ON asm.request_id = f.discovered_in_request_id
          JOIN assessment_scope_asset sa ON sa.assessment_id = asm.id
         WHERE f.discovered_in_request_id IS NOT NULL
       ) reached
 GROUP BY tenant_id, asset_id, finding_id;

COMMENT ON VIEW asset_finding_link IS
    'Every (asset, finding) pair, by direct impact or by the request the finding was discovered in. '
    'direct_impact distinguishes "affects this asset" from "was found while assessing it" — the two '
    'are not the same claim and this view keeps them apart rather than merging them into the '
    'stronger one. SECURITY INVOKER so row-level policies apply to the caller.';

GRANT SELECT ON asset_finding_link TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 2. asset_request_link — the same correction for assessment requests.
--
-- `application_inventory.request_count` counted `assessment_scope_asset`, which is populated only
-- once an assessment exists — that is, once an assessor has been named. A request sitting in intake
-- against an application counted as zero requests against that application, so the column read
-- "never assessed" for work that was queued and waiting. Product principle 6 — waiting is visible
-- and attributed — is precisely what that hid.
-- -----------------------------------------------------------------------------
CREATE VIEW asset_request_link AS
SELECT tenant_id,
       asset_id,
       request_id,
       bool_or(named_by_requester) AS named_by_requester
  FROM (
        SELECT ra.tenant_id, ra.asset_id, ra.request_id, ra.named_by_requester
          FROM assessment_request_scope_asset ra
        UNION ALL
        SELECT sa.tenant_id, sa.asset_id, asm.request_id, false
          FROM assessment_scope_asset sa
          JOIN assessment asm ON asm.id = sa.assessment_id
       ) reached
 WHERE request_id IS NOT NULL
 GROUP BY tenant_id, asset_id, request_id;

COMMENT ON VIEW asset_request_link IS
    'Every (asset, request) pair, from the request-level scope of V029 and from the older '
    'assessment-level scope. named_by_requester marks the asset the requester chose, as against the '
    'application derived from the composition graph.';

GRANT SELECT ON asset_request_link TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 3. asset_finding_tally — unchanged in meaning, repointed at the link view.
--
-- Column list identical to V019 section 4, so nothing that reads it has to change. The only
-- difference is the source: `asset_finding_link` instead of `finding_asset_impact` directly.
--
-- OPEN is `state = 'OPEN'`. ACCEPTED is a finding closed with closure_reason = 'RISK_ACCEPTED' — an
-- accepted risk is CLOSED, not open, and counting it in both double-counts the figure an executive
-- reads first. Restated rather than referenced, because this definition is now the live one.
-- -----------------------------------------------------------------------------
CREATE VIEW asset_finding_tally AS
SELECT l.asset_id,
       f.tenant_id,
       count(*)                                                        AS total,
       count(*) FILTER (WHERE f.state = 'OPEN')                        AS open_total,
       count(*) FILTER (WHERE f.closure_reason = 'RISK_ACCEPTED')      AS accepted_total,
       count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY')          AS sca_total,
       count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY'
                          AND f.state = 'OPEN')                        AS sca_open,
       count(*) FILTER (WHERE s.code = 'CRITICAL')                     AS critical_total,
       count(*) FILTER (WHERE s.code = 'CRITICAL' AND f.state = 'OPEN') AS critical_open,
       count(*) FILTER (WHERE s.code = 'HIGH')                         AS high_total,
       count(*) FILTER (WHERE s.code = 'HIGH' AND f.state = 'OPEN')    AS high_open,
       count(*) FILTER (WHERE s.code = 'MEDIUM')                       AS medium_total,
       count(*) FILTER (WHERE s.code = 'MEDIUM' AND f.state = 'OPEN')  AS medium_open,
       count(*) FILTER (WHERE s.code = 'LOW')                          AS low_total,
       count(*) FILTER (WHERE s.code = 'LOW' AND f.state = 'OPEN')     AS low_open,
       count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY'
                          AND s.code = 'CRITICAL' AND f.state = 'OPEN') AS sca_critical_open,
       count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY'
                          AND s.code = 'HIGH' AND f.state = 'OPEN')     AS sca_high_open,
       count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY'
                          AND s.code = 'MEDIUM' AND f.state = 'OPEN')   AS sca_medium_open,
       max(f.last_detected_at)                                         AS last_detected_at
  FROM asset_finding_link l
  JOIN finding f ON f.id = l.finding_id
  -- The EFFECTIVE severity where a human set one, otherwise the reported one. V019 read
  -- effective_severity_id alone, which reported every un-triaged finding as unrated and therefore
  -- absent from all four severity columns — the severity mix of an estate nobody has triaged came
  -- out empty rather than showing the tool's own answer with the caveat that nobody has confirmed it.
  LEFT JOIN severity_level s
         ON s.id = coalesce(f.effective_severity_id, f.reported_severity_id)
 GROUP BY l.asset_id, f.tenant_id;

COMMENT ON VIEW asset_finding_tally IS
    'Findings for one asset by severity, state and class, over asset_finding_link. DEPENDENCY is the '
    'SCA class. Accepted risk is counted as CLOSED, because it is. Severity falls back to the '
    'reported one where nobody has triaged, so an untriaged estate is not reported as unrated.';

GRANT SELECT ON asset_finding_tally TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 4. application_finding — one row per (application, finding), over the whole subtree.
--
-- DISTINCT, and this is the second defect corrected here. V019's `application_posture` summed the
-- per-component tallies across the subtree. A finding that concerns two parts of the same
-- application — and one that concerns a project AND the application derived from it, which is what
-- intake records for every request — was counted twice. The rollup therefore reported more open
-- findings than the application has, and a headline larger than the truth destroys trust in a page
-- exactly as fast as one that is too small. The same trap is recorded at `ProjectQuery`.
-- -----------------------------------------------------------------------------
CREATE VIEW application_finding AS
SELECT DISTINCT s.root_id AS asset_id,
       s.tenant_id,
       l.finding_id
  FROM (SELECT a.id AS root_id, a.tenant_id, a.id AS member_id FROM asset a
        UNION
        SELECT c.root_id, c.tenant_id, c.asset_id FROM asset_composition c) s
  JOIN asset_finding_link l
    ON l.asset_id = s.member_id AND l.tenant_id = s.tenant_id;

COMMENT ON VIEW application_finding IS
    'One row per (application, finding) over the application and everything it contains. DISTINCT: a '
    'finding concerning both a project and the application it belongs to is one finding, and summing '
    'component tallies reported it twice.';

GRANT SELECT ON application_finding TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 5. application_request — the same rollup for requests.
-- -----------------------------------------------------------------------------
CREATE VIEW application_request AS
SELECT DISTINCT s.root_id AS asset_id,
       s.tenant_id,
       l.request_id
  FROM (SELECT a.id AS root_id, a.tenant_id, a.id AS member_id FROM asset a
        UNION
        SELECT c.root_id, c.tenant_id, c.asset_id FROM asset_composition c) s
  JOIN asset_request_link l
    ON l.asset_id = s.member_id AND l.tenant_id = s.tenant_id;

COMMENT ON VIEW application_request IS
    'One row per (application, assessment request) over the application and its parts. DISTINCT for '
    'the same reason as application_finding: intake records both the named project and the derived '
    'application against one request.';

GRANT SELECT ON application_request TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 6. asset_assurance and application_assurance — repointed, meaning unchanged.
--
-- V020 sections 1 and 2, over the link view. `first_evidence_at` and `source_tools` are kept: they
-- are in the V020 definition and dropping a column while repointing a view is how a page that read
-- it starts failing for a reason nobody connects to this file.
-- -----------------------------------------------------------------------------
CREATE VIEW asset_assurance AS
SELECT l.asset_id,
       f.tenant_id,
       f.finding_class,
       count(*)                                       AS finding_count,
       count(*) FILTER (WHERE f.state = 'OPEN')       AS open_count,
       max(f.last_detected_at)                        AS last_evidence_at,
       min(f.first_detected_at)                       AS first_evidence_at,
       array_agg(DISTINCT f.source_tool)              AS source_tools
  FROM asset_finding_link l
  JOIN finding f ON f.id = l.finding_id
 GROUP BY l.asset_id, f.tenant_id, f.finding_class;

COMMENT ON VIEW asset_assurance IS
    'Per asset and finding class: when evidence was last produced and by which tools, over '
    'asset_finding_link. A class ABSENT from this view is the answer to "has anything ever looked".';

GRANT SELECT ON asset_assurance TO app_runtime, integrity_verifier;

CREATE VIEW application_assurance AS
WITH scope AS (
    SELECT a.id AS root_id, a.tenant_id, a.id AS member_id FROM asset a
    UNION
    SELECT c.root_id, c.tenant_id, c.asset_id FROM asset_composition c
)
SELECT s.root_id                                   AS asset_id,
       s.tenant_id,
       f.finding_class,
       count(DISTINCT f.id)                        AS finding_count,
       count(DISTINCT f.id) FILTER (WHERE f.state = 'OPEN') AS open_count,
       max(f.last_detected_at)                     AS last_evidence_at,
       count(DISTINCT l.asset_id)                  AS covered_parts
  FROM scope s
  JOIN asset_finding_link l ON l.asset_id = s.member_id AND l.tenant_id = s.tenant_id
  JOIN finding f ON f.id = l.finding_id
 GROUP BY s.root_id, s.tenant_id, f.finding_class;

COMMENT ON VIEW application_assurance IS
    'Assurance coverage rolled up over an application and its parts, with covered_parts so "one of '
    'five services was scanned" is distinguishable from "the application was scanned". Counts '
    'DISTINCT findings rather than summing per-part tallies, which double-counted shared parts.';

GRANT SELECT ON application_assurance TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 7. asset_remediation — repointed, meaning unchanged.
--
-- Mean time to remediate over CLOSED findings only. An average that silently counts open findings as
-- closed-today is the flattering version and improves whenever a new finding arrives.
-- `open_oldest_days` is the honest companion: a good average with a two-year-old critical underneath
-- it is a problem the average hides.
-- -----------------------------------------------------------------------------
CREATE VIEW asset_remediation AS
SELECT l.asset_id,
       f.tenant_id,
       count(*) FILTER (WHERE f.closed_at IS NOT NULL)                        AS closed_count,
       round(avg(EXTRACT(EPOCH FROM (f.closed_at - f.first_detected_at)) / 86400.0)
             FILTER (WHERE f.closed_at IS NOT NULL))::int                     AS mean_days_to_close,
       max(EXTRACT(DAY FROM (now() - f.first_detected_at))) FILTER
           (WHERE f.state = 'OPEN')::int                                      AS open_oldest_days,
       count(*) FILTER (WHERE f.state = 'OPEN'
                          AND f.first_detected_at < now() - interval '90 days') AS open_over_90_days
  FROM asset_finding_link l
  JOIN finding f ON f.id = l.finding_id
 GROUP BY l.asset_id, f.tenant_id;

COMMENT ON VIEW asset_remediation IS
    'Time to remediate over CLOSED findings only, with the oldest still-open age beside it, over '
    'asset_finding_link.';

GRANT SELECT ON asset_remediation TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 8. application_posture — the rollup, now counting distinct findings.
--
-- Every column V019 defined is kept with the same name and meaning, so callers do not change. What
-- changes is that the numbers are computed over `application_finding` rather than by summing
-- component tallies, which means:
--
--   * a finding is counted once per application however many of its parts it touches;
--   * a finding reached through the request that discovered it is counted at all.
--
-- Added, because the application dashboard needs them and computing them here keeps the detail page
-- and the inventory list reading the same arithmetic:
--
--   open_over_30_days / 90 / 180   the age profile of what is still open
--   closed_last_90_days            outflow, so the open count has something to be compared against
--   mean_days_to_close             over closed findings only, for the reason stated in section 7
--   open_oldest_days               the number a mean hides
--   remediation_claimed_open       fixed by a developer, not yet verified by an assessor — waiting,
--                                  visible and attributed (product principle 6)
--   request_total / request_open   assessment demand against this application
--   last_request_closed_at         when work against it last finished
--
-- SBOM columns keep V019's definition: `count(cs.asset_id)` is how many parts have EVER had a
-- snapshot, against `component_count` for how many exist. PRD-SBM-056 — the difference is the number
-- that matters, and a page showing only the covered ones reports a clean estate.
-- -----------------------------------------------------------------------------
CREATE VIEW application_posture AS
WITH scope AS (
    SELECT a.id AS root_id, a.tenant_id, a.id AS member_id FROM asset a
    UNION
    SELECT c.root_id, c.tenant_id, c.asset_id FROM asset_composition c
),
parts AS (
    SELECT s.root_id,
           s.tenant_id,
           count(DISTINCT s.member_id) - 1                  AS component_count,
           count(cs.asset_id)                               AS sbom_covered_parts,
           max(cs.latest_snapshot_at)                       AS sbom_latest_at,
           count(*) FILTER (WHERE cs.quality = 'REJECTED')  AS sbom_rejected_parts
      FROM scope s
      LEFT JOIN sbom_coverage_state cs ON cs.asset_id = s.member_id
     GROUP BY s.root_id, s.tenant_id
),
findings AS (
    SELECT af.asset_id AS root_id,
           af.tenant_id,
           count(*)                                                          AS finding_total,
           count(*) FILTER (WHERE f.state = 'OPEN')                          AS finding_open,
           count(*) FILTER (WHERE f.closure_reason = 'RISK_ACCEPTED')        AS finding_accepted,
           count(*) FILTER (WHERE s.code = 'CRITICAL')                       AS critical_total,
           count(*) FILTER (WHERE s.code = 'CRITICAL' AND f.state = 'OPEN')  AS critical_open,
           count(*) FILTER (WHERE s.code = 'HIGH')                           AS high_total,
           count(*) FILTER (WHERE s.code = 'HIGH' AND f.state = 'OPEN')      AS high_open,
           count(*) FILTER (WHERE s.code = 'MEDIUM')                         AS medium_total,
           count(*) FILTER (WHERE s.code = 'MEDIUM' AND f.state = 'OPEN')    AS medium_open,
           count(*) FILTER (WHERE s.code = 'LOW')                            AS low_total,
           count(*) FILTER (WHERE s.code = 'LOW' AND f.state = 'OPEN')       AS low_open,
           count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY')            AS sca_total,
           count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY'
                              AND f.state = 'OPEN')                          AS sca_open,
           count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY'
                              AND s.code = 'CRITICAL' AND f.state = 'OPEN')  AS sca_critical_open,
           count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY'
                              AND s.code = 'HIGH' AND f.state = 'OPEN')      AS sca_high_open,
           count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY'
                              AND s.code = 'MEDIUM' AND f.state = 'OPEN')    AS sca_medium_open,
           max(f.last_detected_at)                                           AS last_detected_at,
           count(*) FILTER (WHERE f.state = 'OPEN'
               AND f.first_detected_at < now() - interval '30 days')         AS open_over_30_days,
           count(*) FILTER (WHERE f.state = 'OPEN'
               AND f.first_detected_at < now() - interval '90 days')         AS open_over_90_days,
           count(*) FILTER (WHERE f.state = 'OPEN'
               AND f.first_detected_at < now() - interval '180 days')        AS open_over_180_days,
           count(*) FILTER (WHERE f.closed_at > now() - interval '90 days')  AS closed_last_90_days,
           round(avg(EXTRACT(EPOCH FROM (f.closed_at - f.first_detected_at)) / 86400.0)
                 FILTER (WHERE f.closed_at IS NOT NULL))::int                AS mean_days_to_close,
           max(EXTRACT(DAY FROM (now() - f.first_detected_at)))
               FILTER (WHERE f.state = 'OPEN')::int                          AS open_oldest_days,
           count(*) FILTER (WHERE f.state = 'OPEN'
               AND f.remediation_claimed_at IS NOT NULL)                     AS remediation_claimed_open
      FROM application_finding af
      JOIN finding f ON f.id = af.finding_id
      LEFT JOIN severity_level s
             ON s.id = coalesce(f.effective_severity_id, f.reported_severity_id)
     GROUP BY af.asset_id, af.tenant_id
),
requests AS (
    SELECT ar.asset_id AS root_id,
           ar.tenant_id,
           count(*)                                                          AS request_total,
           count(*) FILTER (WHERE coalesce(b.state_category, '') <> 'TERMINAL') AS request_open,
           max(b.closed_at)                                                  AS last_request_closed_at
      FROM application_request ar
      JOIN request_board b ON b.id = ar.request_id
     GROUP BY ar.asset_id, ar.tenant_id
)
SELECT p.root_id                                     AS asset_id,
       p.tenant_id,
       p.component_count,
       coalesce(f.finding_total, 0)                  AS finding_total,
       coalesce(f.finding_open, 0)                   AS finding_open,
       coalesce(f.finding_accepted, 0)               AS finding_accepted,
       coalesce(f.critical_total, 0)                 AS critical_total,
       coalesce(f.critical_open, 0)                  AS critical_open,
       coalesce(f.high_total, 0)                     AS high_total,
       coalesce(f.high_open, 0)                      AS high_open,
       coalesce(f.medium_total, 0)                   AS medium_total,
       coalesce(f.medium_open, 0)                    AS medium_open,
       coalesce(f.low_total, 0)                      AS low_total,
       coalesce(f.low_open, 0)                       AS low_open,
       coalesce(f.sca_total, 0)                      AS sca_total,
       coalesce(f.sca_open, 0)                       AS sca_open,
       coalesce(f.sca_critical_open, 0)              AS sca_critical_open,
       coalesce(f.sca_high_open, 0)                  AS sca_high_open,
       coalesce(f.sca_medium_open, 0)                AS sca_medium_open,
       f.last_detected_at,
       p.sbom_covered_parts,
       p.sbom_latest_at,
       p.sbom_rejected_parts,
       coalesce(f.open_over_30_days, 0)              AS open_over_30_days,
       coalesce(f.open_over_90_days, 0)              AS open_over_90_days,
       coalesce(f.open_over_180_days, 0)             AS open_over_180_days,
       coalesce(f.closed_last_90_days, 0)            AS closed_last_90_days,
       -- NOT coalesced to zero. Nothing closed means the time to close is UNMEASURED, and a zero
       -- here renders as "fixed the same day" — the most flattering possible reading of no evidence
       -- at all. PRD-UIX-022 forbids a numeral for an unmeasured value; a NULL is how the interface
       -- is told to render the word instead.
       f.mean_days_to_close,
       f.open_oldest_days,
       coalesce(f.remediation_claimed_open, 0)       AS remediation_claimed_open,
       coalesce(r.request_total, 0)                  AS request_total,
       coalesce(r.request_open, 0)                   AS request_open,
       r.last_request_closed_at
  FROM parts p
  LEFT JOIN findings f ON f.root_id = p.root_id AND f.tenant_id = p.tenant_id
  LEFT JOIN requests r ON r.root_id = p.root_id AND r.tenant_id = p.tenant_id;

COMMENT ON VIEW application_posture IS
    'An application and everything it contains, rolled up over application_finding. Counts DISTINCT '
    'findings rather than summing per-part tallies, and reaches findings through the request that '
    'discovered them as well as through the direct impact edge — without which every application in '
    'this deployment reported zero. mean_days_to_close is NULL where nothing has closed, never zero.';

GRANT SELECT ON application_posture TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 9. application_inventory — same column list as V018, counts taken from the rollups.
--
-- The two subselects V018 wrote against `finding_asset_impact` and `assessment_scope_asset` are the
-- defect named at the top of this file. They are replaced by the rollup views, which means the
-- inventory list and the application detail page now report the same number by construction rather
-- than by two queries agreeing.
--
-- The rest of the definition is V018's, reproduced rather than referenced: a replayable migration
-- has to be authoritative at the moment it runs, and a view that inherited half its definition from
-- an earlier file would depend on the order they happen to be applied in.
-- -----------------------------------------------------------------------------
CREATE VIEW application_inventory AS
SELECT a.id,
       a.tenant_id,
       a.display_name,
       a.identity_key,
       a.lifecycle_state,
       a.owning_node_id,
       a.type_id,
       t.code                                  AS type_code,
       n.name                                  AS owning_node_name,
       nt.code                                 AS owning_node_type_code,
       (SELECT array_agg(anc.name ORDER BY c.depth DESC)
          FROM org_closure c
          JOIN org_node anc ON anc.id = c.ancestor_id
         WHERE c.descendant_id = a.owning_node_id
           AND c.depth > 0)                    AS ancestor_names,
       a.exposure_declared,
       a.exposure_observed,
       a.exposure_conflict,
       a.criticality_mode,
       coalesce(ct_own.code, ct_node.code)     AS criticality_code,
       (ct_own.code IS NULL AND ct_node.code IS NOT NULL) AS criticality_inherited,
       a.tags,
       a.technical_contact_id,
       a.attributes,
       s.value                                 AS risk_value,
       s.band                                  AS risk_band,
       s.coverage_confidence                   AS risk_coverage,
       s.computed_at                           AS risk_computed_at,
       coalesce(p.request_total, 0)            AS request_count,
       coalesce(p.finding_total, 0)            AS finding_count,
       -- Added: the list's severity column had to fetch these per row through a second query, and
       -- two queries over the same subtree are two chances to disagree.
       coalesce(p.finding_open, 0)             AS finding_open,
       coalesce(p.critical_open, 0)            AS critical_open,
       coalesce(p.high_open, 0)                AS high_open,
       coalesce(p.request_open, 0)             AS request_open,
       p.last_detected_at                      AS finding_last_detected_at,
       a.created_at,
       a.updated_at,
       a.row_version
  FROM asset a
  JOIN asset_type t          ON t.id = a.type_id
  LEFT JOIN org_node n       ON n.id = a.owning_node_id
  LEFT JOIN org_node_type nt ON nt.id = n.type_id
  LEFT JOIN criticality_tier ct_own  ON ct_own.id = a.criticality_tier_id
  LEFT JOIN criticality_tier ct_node ON ct_node.id = n.criticality_tier_id
  LEFT JOIN rm_latest_risk_score s
         ON s.subject_kind = 'ASSET' AND s.subject_id = a.id
  LEFT JOIN application_posture p ON p.asset_id = a.id AND p.tenant_id = a.tenant_id;

COMMENT ON VIEW application_inventory IS
    'The application/product inventory list. SECURITY INVOKER so row-level policies apply to the '
    'CALLER. Counts come from application_posture, so the list and the detail page cannot disagree. '
    'Carries the score WITH its coverage_confidence and a NULL where nothing was scored, because '
    'PRD-UIX-022 forbids a numeral for an unmeasured value.';

GRANT SELECT ON application_inventory TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 10. The index the new join needs.
--
-- DOC-00 forbids a table without an indexing strategy naming the query it serves. The link views
-- join `finding` on `discovered_in_request_id` — served by `ix_finding__request` (V006) — and
-- `assessment_request_scope_asset` on `request_id`, which is the leading column of its primary key.
-- What has no index is `assessment_scope_asset.assessment_id`, joined in both link views.
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS ix_assessment_scope_asset__assessment
    ON assessment_scope_asset (tenant_id, assessment_id);

COMMENT ON INDEX ix_assessment_scope_asset__assessment IS
    'Serves: the legacy branch of asset_finding_link and asset_request_link, which reaches an asset '
    'through the assessment rather than through the request.';

CREATE INDEX IF NOT EXISTS ix_assessment__request
    ON assessment (tenant_id, request_id);

COMMENT ON INDEX ix_assessment__request IS
    'Serves: joining an assessment back to the request a finding was discovered in, in both link '
    'views.';

-- -----------------------------------------------------------------------------
-- 11. A check that the correction actually took, rather than being assumed.
--
-- The failure this file exists to fix was silent: the queries ran, returned zero, and every page
-- rendered a clean estate. So the migration asserts the shape of the result rather than trusting it
-- — if `application_finding` cannot reach more findings than `finding_asset_impact` alone in a
-- database that has findings discovered in scoped requests, the union did not work and a green
-- migration would hide that exactly as the original defect did.
--
-- Written as a WARNING, not an EXCEPTION. A fresh deployment legitimately has no findings at all,
-- and a migration that refuses to apply to an empty database is a migration that cannot be tested.
--
-- The row counts are attempted and may be unavailable. `finding` is tenant-isolated and
-- current_tenant_id() RAISES rather than returning NULL when no tenant context is set (CON-DAT-013),
-- which is correct and is why the migration pipeline — which must not choose a tenant — cannot read
-- the table. So the structural assertion runs unconditionally and the row counts are best-effort:
-- the structural one catches the defect this file corrects, which was a view reading the wrong
-- table, and it catches it in an empty database too.
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    definition text;
BEGIN
    SELECT pg_get_viewdef('asset_finding_link'::regclass) INTO definition;
    IF position('assessment_request_scope_asset' IN definition) = 0 THEN
        RAISE EXCEPTION 'asset_finding_link does not read assessment_request_scope_asset. That is '
                        'the edge intake actually writes, and without it every application reports '
                        'zero findings — the defect V035 exists to correct.';
    END IF;

    SELECT pg_get_viewdef('application_inventory'::regclass) INTO definition;
    IF position('application_posture' IN definition) = 0 THEN
        RAISE EXCEPTION 'application_inventory is not taking its counts from application_posture, so '
                        'the list and the detail page can disagree about the same application.';
    END IF;

    RAISE NOTICE 'V035: application counts now resolve through asset_finding_link and '
                 'application_posture.';
END
$$;

DO $$
DECLARE
    reachable  bigint;
    direct     bigint;
    candidates bigint;
BEGIN
    SELECT count(*) INTO direct FROM finding_asset_impact;
    SELECT count(*) INTO reachable FROM asset_finding_link;
    SELECT count(*) INTO candidates
      FROM finding f
      JOIN assessment_request_scope_asset ra ON ra.request_id = f.discovered_in_request_id;

    IF candidates > 0 AND reachable <= direct THEN
        RAISE WARNING 'asset_finding_link reaches % pairs from % direct impact rows, while % findings '
                      'are discoverable through their request. The union did not take.',
                      reachable, direct, candidates;
    ELSE
        RAISE NOTICE 'asset_finding_link: % pairs (% direct impact rows, % reachable through a '
                     'request scope).', reachable, direct, candidates;
    END IF;
EXCEPTION
    -- No tenant context in the migration pipeline. Expected, and not a reason to fail: the
    -- structural assertion above already ran, and a pipeline that set a tenant to satisfy a check
    -- would be a migration choosing whose data to look at.
    WHEN OTHERS THEN
        RAISE NOTICE 'V035: row-level verification skipped (%). The structural assertion passed.',
                     SQLERRM;
END
$$;
