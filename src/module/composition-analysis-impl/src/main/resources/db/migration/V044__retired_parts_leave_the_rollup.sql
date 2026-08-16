-- =============================================================================
-- V044 — a retired repository stops counting as part of the estate.
--
-- THE DEFECT. `application_dependency_posture` built its member set by unioning `asset` with
-- `asset_composition` and filtering NEITHER on lifecycle. So an asset that had been retired was still
-- counted in `parts`, and — the half that matters — still counted in `sbom_parts` if it had ever
-- carried a bill of materials.
--
-- WHY THAT IS WORSE THAN AN OFF-BY-SEVEN. `sbom_parts of parts` is the coverage figure the software
-- composition dashboard leads with, and PRD-SBM-056 exists because a coverage number that overstates
-- itself is the one output this product must never produce. A decommissioned repository is measured
-- forever: its last SBOM never goes stale, never fails a re-scan (V042 marks it unscannable), and
-- keeps contributing a "covered" to the numerator. Retire ten dead repositories and coverage goes UP.
--
-- Found on the running estate, not by reading: one application reported fifteen parts where twelve
-- were live, and all seven of its retired members were counted as covered. Estate-wide, nine retired
-- assets were contributing to the covered count.
--
-- WHAT IS NOT CHANGED, DELIBERATELY. The snapshots, components and advisories of a retired asset stay
-- exactly where they are. This is not erasure — product principle 5 — and the history remains
-- readable through the asset itself. What changes is only whether a dead repository is counted as
-- part of the LIVE estate when the live estate's coverage is computed. Those are different questions
-- and the view was answering the second with the first's data.
--
-- A retired ROOT is also excluded, because an application that no longer exists is not an
-- application with good coverage.
-- =============================================================================

-- CREATE OR REPLACE cannot be used: the column list is unchanged but Postgres rejects a replace whose
-- underlying query changes a column's nullability inference in some versions, and the replay of this
-- migration on every container start must be deterministic. Dropping first is the only shape that is
-- reliably idempotent — and nothing depends on this view, so there are no dependants to drop before it.
DROP VIEW IF EXISTS application_dependency_posture;

CREATE VIEW application_dependency_posture AS
WITH scope AS (
    -- The root itself, provided it is live. `parts` subtracts one for the root, and that subtraction
    -- is only correct while the root is in the set — so a retired root drops out of the view
    -- entirely rather than reporting a negative part count.
    SELECT a.id AS root_id, a.tenant_id, a.id AS member_id
      FROM asset a
     WHERE a.lifecycle_state <> 'RETIRED'
    UNION
    -- Members, filtered on the MEMBER's lifecycle. Joined to `asset` rather than trusting
    -- `asset_composition`, which records structure and says nothing about whether a part is still
    -- running — that is the asset's own state and the only place it is authoritative.
    SELECT c.root_id, c.tenant_id, c.asset_id
      FROM asset_composition c
      JOIN asset m ON m.id = c.asset_id AND m.tenant_id = c.tenant_id
      JOIN asset r ON r.id = c.root_id AND r.tenant_id = c.tenant_id
     WHERE m.lifecycle_state <> 'RETIRED'
       AND r.lifecycle_state <> 'RETIRED'
)
SELECT s.root_id AS asset_id,
       s.tenant_id,
       count(DISTINCT s.member_id) - 1 AS parts,
       count(DISTINCT cs.asset_id) FILTER (WHERE cs.latest_snapshot_id IS NOT NULL) AS sbom_parts,
       max(cs.latest_snapshot_at) AS latest_snapshot_at,
       count(DISTINCT ac.component_id) AS component_count,
       count(DISTINCT ac.component_id) FILTER (WHERE ac.is_direct) AS direct_count,
       count(DISTINCT v.advisory_id) FILTER (WHERE v.resolved_at IS NULL) AS advisory_open,
       count(DISTINCT v.advisory_id) FILTER (
            WHERE v.resolved_at IS NULL AND v.severity_code = 'CRITICAL') AS critical_open,
       count(DISTINCT v.advisory_id) FILTER (
            WHERE v.resolved_at IS NULL AND v.severity_code = 'HIGH') AS high_open,
       count(DISTINCT v.advisory_id) FILTER (
            WHERE v.resolved_at IS NULL AND v.severity_code = 'MEDIUM') AS medium_open,
       count(DISTINCT v.advisory_id) FILTER (
            WHERE v.resolved_at IS NULL AND v.severity_code = 'LOW') AS low_open,
       count(DISTINCT v.component_id) FILTER (WHERE v.resolved_at IS NULL) AS vulnerable_components,
       count(DISTINCT v.advisory_id) FILTER (
            WHERE v.resolved_at IS NULL AND v.fixed_version IS NOT NULL) AS fixable_open,
       count(DISTINCT v.advisory_id) FILTER (
            WHERE v.resolved_at IS NULL
              AND v.status = ANY (ARRAY['will_not_fix', 'end_of_life'])) AS unfixable_open
  FROM scope s
  LEFT JOIN sbom_coverage_state cs ON cs.asset_id = s.member_id AND cs.tenant_id = s.tenant_id
  LEFT JOIN asset_component ac ON ac.asset_id = s.member_id AND ac.tenant_id = s.tenant_id
  LEFT JOIN asset_component_advisory v ON v.asset_id = s.member_id AND v.tenant_id = s.tenant_id
 GROUP BY s.root_id, s.tenant_id;

COMMENT ON VIEW application_dependency_posture IS
    'Composition rollup over the LIVE estate. Retired members and retired roots are excluded: their '
    'snapshots and advisories are kept, but a decommissioned repository must not keep contributing a '
    '"covered" to the coverage figure — retiring dead repositories would otherwise raise it '
    '(PRD-SBM-056).';

GRANT SELECT ON application_dependency_posture TO app_runtime, integrity_verifier;
