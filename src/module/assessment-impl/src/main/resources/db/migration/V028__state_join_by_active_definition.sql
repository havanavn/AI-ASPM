-- =============================================================================
-- V028 — every request counted twice.
--
-- THE DEFECT
--
-- Three views resolve a request's state to its category and label by joining workflow_state on the
-- state CODE:
--
--     LEFT JOIN workflow_state ws ON ws.code = r.state AND ws.tenant_id = r.tenant_id
--
-- That was unambiguous while one workflow definition existed. V027 added version 2 and retired
-- version 1 — and a retired definition keeps its state rows, because the transitions that reference
-- them are the record of what happened and INV-WRK-01 makes them immutable. So three codes now exist
-- twice: IN_PROGRESS, FIXING and CANCELLED are defined by both versions.
--
-- A LEFT JOIN matching two rows returns two rows. The board went from eight requests to twelve, with
-- every request in one of those three states listed twice — and the totals above it, the overdue
-- count and the open-finding sum, all counted those requests twice as well. Nothing failed; the
-- numbers were simply wrong, in the direction that makes a queue look busier than it is.
--
-- application_full_review has the same join, so a whole-application review in one of those states
-- was double-counted in the coverage panel.
--
-- THE FIX
--
-- Join through the request's own assessment type to the definition that type currently points at,
-- rather than matching a code across every definition that ever existed. A request's state is
-- meaningful only within one definition, and that is the one its type names.
--
-- Note what this does NOT do: it does not delete version 1's states. They are the vocabulary the
-- historical transition log is written in, and the log is inviolable (PP-5).
-- =============================================================================

DROP VIEW IF EXISTS application_review_cadence;
DROP VIEW IF EXISTS application_full_review;
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
       (SELECT a.lead_principal_id FROM assessment a
         WHERE a.request_id = r.id ORDER BY a.created_at DESC LIMIT 1) AS lead_principal_id,
       (SELECT count(DISTINCT sa.asset_id) FROM assessment_scope_asset sa
          JOIN assessment ex ON ex.id = sa.assessment_id
         WHERE ex.request_id = r.id)            AS scope_assets,
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
                    AND s.code IN ('CRITICAL', 'HIGH')), 0)                 AS finding_severe_open,
       r.trigger_id,
       tg.code                                  AS trigger_code,
       tg.label_i18n                            AS trigger_label,
       coalesce(tg.counts_as_full_review, false) AS trigger_is_full_review,
       (SELECT max(t.occurred_at)
          FROM assessment_request_transition t
          JOIN workflow_state ts ON ts.code = t.to_state AND ts.tenant_id = t.tenant_id
         WHERE t.request_id = r.id
           AND EXISTS (SELECT 1 FROM review_completion_state c
                        WHERE c.state_code = ts.code))                      AS closed_at,
       -- Scalar subquery, not a join. It returns exactly one row or none by construction, so it
       -- cannot multiply the result the way the join it replaces did.
       (SELECT ws.category FROM workflow_state ws
          JOIN assessment_type ty ON ty.workflow_definition_id = ws.definition_id
         WHERE ty.id = r.type_id AND ws.code = r.state)                     AS state_category,
       (SELECT coalesce(ws.label_i18n->>'en', ws.code) FROM workflow_state ws
          JOIN assessment_type ty ON ty.workflow_definition_id = ws.definition_id
         WHERE ty.id = r.type_id AND ws.code = r.state)                     AS state_label
  FROM assessment_request r
  LEFT JOIN org_node n ON n.id = r.requested_org_node_id
  LEFT JOIN assessment_trigger tg ON tg.id = r.trigger_id;

COMMENT ON VIEW request_board IS
    'The assessment request board, one row per request. The state category and label are resolved '
    'through the request''s own assessment type, because a state code is meaningful only within one '
    'workflow definition and retired definitions keep their rows.';

GRANT SELECT ON request_board TO app_runtime, integrity_verifier;

-- ----------------------------------------------------------------------------------------------
-- The same correction for the review history.
-- ----------------------------------------------------------------------------------------------
CREATE VIEW application_full_review AS
SELECT sa.asset_id,
       r.tenant_id,
       r.id                                     AS request_id,
       r.request_code,
       r.title,
       r.state,
       tg.code                                  AS trigger_code,
       tg.label_i18n                            AS trigger_label,
       (SELECT ws.category FROM workflow_state ws
          JOIN assessment_type ty ON ty.workflow_definition_id = ws.definition_id
         WHERE ty.id = r.type_id AND ws.code = r.state)     AS state_category,
       rcs.disposition                          AS terminal_disposition,
       coalesce(ex.started_at, r.submitted_at)  AS started_at,
       ex.started_at IS NULL                    AS started_at_is_intake_date,
       (SELECT max(t.occurred_at)
          FROM assessment_request_transition t
          JOIN workflow_state ts ON ts.code = t.to_state AND ts.tenant_id = t.tenant_id
         WHERE t.request_id = r.id
           AND EXISTS (SELECT 1 FROM review_completion_state c
                        WHERE c.state_code = ts.code AND c.disposition = 'COMPLETED'))
                                                            AS closed_at,
       (SELECT max(t.occurred_at)
          FROM assessment_request_transition t
          JOIN workflow_state ts ON ts.code = t.to_state AND ts.tenant_id = t.tenant_id
         WHERE t.request_id = r.id
           AND EXISTS (SELECT 1 FROM review_completion_state c
                        WHERE c.state_code = ts.code AND c.disposition = 'ABANDONED'))
                                                            AS abandoned_at,
       ex.outcome,
       r.due_at
  FROM assessment_request r
  JOIN assessment_trigger tg ON tg.id = r.trigger_id AND tg.counts_as_full_review
  JOIN assessment ex ON ex.request_id = r.id
  JOIN assessment_scope_asset sa ON sa.assessment_id = ex.id
  LEFT JOIN review_completion_state rcs ON rcs.state_code = r.state;

COMMENT ON VIEW application_full_review IS
    'Whole-application reviews per application. closed_at is set only where the request reached a '
    'terminal state the tenant classified as COMPLETED; an abandoned review keeps its row and its '
    'abandoned_at, because the attempt is part of the record and its absence from the coverage count '
    'is the point.';

GRANT SELECT ON application_full_review TO app_runtime, integrity_verifier;

CREATE VIEW application_review_cadence AS
WITH completed AS (
    SELECT fr.asset_id, fr.tenant_id,
           count(*)          AS full_review_count,
           max(fr.closed_at) AS last_full_review_at
      FROM application_full_review fr
     WHERE fr.closed_at IS NOT NULL
     GROUP BY fr.asset_id, fr.tenant_id
),
in_flight AS (
    SELECT fr.asset_id, fr.tenant_id, count(*) AS full_review_in_flight
      FROM application_full_review fr
     WHERE fr.closed_at IS NULL AND fr.state_category IS DISTINCT FROM 'TERMINAL'
     GROUP BY fr.asset_id, fr.tenant_id
),
abandoned AS (
    SELECT fr.asset_id, fr.tenant_id, count(*) AS full_review_abandoned
      FROM application_full_review fr
     WHERE fr.abandoned_at IS NOT NULL
     GROUP BY fr.asset_id, fr.tenant_id
)
SELECT a.id                                          AS asset_id,
       a.tenant_id,
       coalesce(c.full_review_count, 0)              AS full_review_count,
       coalesce(f.full_review_in_flight, 0)          AS full_review_in_flight,
       coalesce(b.full_review_abandoned, 0)          AS full_review_abandoned,
       c.last_full_review_at,
       p.interval_months,
       p.warn_days_before,
       CASE WHEN p.interval_months IS NULL THEN NULL
            WHEN c.last_full_review_at IS NULL THEN NULL
            ELSE c.last_full_review_at + make_interval(months => p.interval_months)
       END                                           AS next_full_review_due,
       CASE
           WHEN p.interval_months IS NULL                 THEN 'NO_OBLIGATION'
           WHEN c.last_full_review_at IS NULL             THEN 'NEVER'
           WHEN c.last_full_review_at
                + make_interval(months => p.interval_months) < now()
                                                          THEN 'OVERDUE'
           WHEN c.last_full_review_at
                + make_interval(months => p.interval_months)
                - make_interval(days => p.warn_days_before) < now()
                                                          THEN 'DUE_SOON'
           ELSE 'CURRENT'
       END                                           AS full_review_status
  FROM asset a
  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION'
  LEFT JOIN org_node n ON n.id = a.owning_node_id
  LEFT JOIN completed c ON c.asset_id = a.id
  LEFT JOIN in_flight f ON f.asset_id = a.id
  LEFT JOIN abandoned b ON b.asset_id = a.id
  LEFT JOIN full_review_policy p
         ON p.criticality_tier_id = coalesce(a.criticality_tier_id, n.criticality_tier_id);

COMMENT ON VIEW application_review_cadence IS
    'Periodic full-review position per application. Only reviews that reached a COMPLETED terminal '
    'state count; abandoned attempts are counted separately, because a run of cancelled annual '
    'reviews is a finding and an empty column is not.';

GRANT SELECT ON application_review_cadence TO app_runtime, integrity_verifier;
