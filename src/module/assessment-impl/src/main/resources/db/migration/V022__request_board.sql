-- =============================================================================
-- V022 — the fields a request board needs, and the view that assembles it.
--
-- assessment_request carried a code, a state, an org node and a workflow. It did not carry a TITLE,
-- a DUE DATE, or the person on the requesting side. Those are not decoration: a board of request
-- codes is unreadable, a request with no deadline cannot be late, and a request with no named
-- requester has nobody to ask when the environment is not ready.
--
-- assessment.lead_principal_id already carries the assessor, so that one is not duplicated here.
-- =============================================================================

ALTER TABLE assessment_request ADD COLUMN IF NOT EXISTS title text;
ALTER TABLE assessment_request ADD COLUMN IF NOT EXISTS due_at timestamptz;
ALTER TABLE assessment_request ADD COLUMN IF NOT EXISTS requester_contact_id uuid;

COMMENT ON COLUMN assessment_request.title IS
    'What this engagement is, in a person''s words. A board of REQ-2026-0042 is a board nobody reads.';
COMMENT ON COLUMN assessment_request.due_at IS
    'When the report is owed. Distinct from derived_feasible_start, which is when work could BEGIN — '
    'a request can be feasible to start and already late to deliver, and one date cannot say both.';
COMMENT ON COLUMN assessment_request.requester_contact_id IS
    'The person on the requesting side. Not the same as requested_by, which records who filed the '
    'form: the filer is often a manager and the contact is the engineer who can actually unblock a '
    'stalled environment.';

CREATE INDEX IF NOT EXISTS ix_request__due
    ON assessment_request (tenant_id, due_at)
    WHERE due_at IS NOT NULL;
COMMENT ON INDEX ix_request__due IS
    'Serves: the request board ordered by deadline, and the overdue count on the workload dashboard.';

-- -----------------------------------------------------------------------------
-- request_board — one row per request with everything the list column shows.
--
-- The finding counts come from findings DISCOVERED IN the request, not from findings against the
-- assets it covers. The difference matters: an application with two hundred historical findings does
-- not make this week's pentest a two-hundred-finding engagement, and a board that counted the asset's
-- findings would report exactly that.
--
-- SECURITY INVOKER, so row-level policies apply to the caller. Scope within the tenant is composed
-- by the application layer (SEC-AUZ-016), as everywhere else.
-- -----------------------------------------------------------------------------
-- DROP then CREATE, not CREATE OR REPLACE. The migrations are replayed in full on every start, and
-- a replay runs this file again AFTER the later migration that widened the view. CREATE OR REPLACE
-- cannot remove a column from an existing view, so the second run failed with "cannot drop columns
-- from view" and took the whole migration container down — a green deployment that could not be
-- restarted. Dropping first makes each migration's definition authoritative at the moment it runs,
-- which is what a replayable migration has to be.
DROP VIEW IF EXISTS application_review_cadence;
DROP VIEW IF EXISTS request_board;

CREATE VIEW request_board AS
SELECT r.id,
       r.tenant_id,
       r.request_code,
       r.title,
       r.state,
       r.created_at,
       r.submitted_at,
       r.due_at,
       r.is_retest,
       r.requested_org_node_id,
       n.name                                   AS org_node_name,
       (SELECT array_agg(anc.name ORDER BY c.depth DESC)
          FROM org_closure c JOIN org_node anc ON anc.id = c.ancestor_id
         WHERE c.descendant_id = r.requested_org_node_id AND c.depth > 0) AS org_ancestors,
       r.requested_by,
       r.requester_contact_id,
       -- The assessor comes from the assessment, which is where the platform records who is doing
       -- the work. A request with no assessment yet has nobody assigned, and NULL says so.
       (SELECT a.lead_principal_id FROM assessment a
         WHERE a.request_id = r.id ORDER BY a.created_at DESC LIMIT 1) AS lead_principal_id,
       (SELECT count(DISTINCT sa.asset_id) FROM assessment_scope_asset sa
          JOIN assessment ex ON ex.id = sa.assessment_id
         WHERE ex.request_id = r.id)            AS scope_assets,
       -- The first application in scope, for the board's "which app" column. A request may cover
       -- several; the column shows one and the detail page lists them all rather than the board
       -- truncating a list into a misleading single name.
       (SELECT a.display_name FROM assessment_scope_asset sa
          JOIN assessment ex ON ex.id = sa.assessment_id
          JOIN asset a ON a.id = sa.asset_id
          JOIN asset_type t ON t.id = a.type_id
         WHERE ex.request_id = r.id AND t.code = 'APPLICATION'
         ORDER BY a.display_name LIMIT 1)       AS primary_application,
       coalesce((SELECT count(*) FROM finding f
                  WHERE f.discovered_in_request_id = r.id), 0)              AS finding_total,
       coalesce((SELECT count(*) FROM finding f
                  WHERE f.discovered_in_request_id = r.id AND f.state = 'OPEN'), 0) AS finding_open,
       coalesce((SELECT count(*) FROM finding f
                  WHERE f.discovered_in_request_id = r.id
                    AND f.closure_reason = 'RISK_ACCEPTED'), 0)             AS finding_accepted,
       coalesce((SELECT count(*) FROM finding f
                  JOIN severity_level s ON s.id = f.effective_severity_id
                  WHERE f.discovered_in_request_id = r.id AND f.state = 'OPEN'
                    AND s.code IN ('CRITICAL', 'HIGH')), 0)                 AS finding_severe_open
  FROM assessment_request r
  LEFT JOIN org_node n ON n.id = r.requested_org_node_id;

COMMENT ON VIEW request_board IS
    'The assessment request board. Finding counts are of findings DISCOVERED IN the request, not of '
    'findings against the assets it covers — counting the latter would report an application''s whole '
    'history as this engagement''s result.';

GRANT SELECT ON request_board TO app_runtime, integrity_verifier;
