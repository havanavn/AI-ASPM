-- =============================================================================
-- V048 — the board shows the application a request is about.
--
-- THE DEFECT. `request_board.primary_application` and `scope_assets` read only the EXECUTION scope
-- (`assessment_scope_asset`, reached through an `assessment` row). That table holds 5 rows. The
-- REQUESTED scope — what the request was raised against, written at intake — holds 207 rows over 203
-- requests, 102 of them naming an application. So the board's application column was blank for 206 of
-- 211 requests, and its asset count was zero for almost all of them.
--
-- THE SAME DEFECT HID A SECOND ONE. A full application review expands its scope at intake to every
-- project under the application (`IntakeService.insertScopeAssets`), and writes those rows to the
-- REQUESTED scope. Choosing "full application review" therefore appeared to do nothing: the expansion
-- ran correctly and landed in a table the board did not read. Two reports, one cause.
--
-- WHY EXECUTION SCOPE STILL COMES FIRST. What an assessment actually covered outranks what a request
-- asked it to cover; where an execution exists it is the better answer and it stays the answer. The
-- requested scope is a FALLBACK, not a replacement — and the third fallback derives the application
-- from a requested project through the composition graph, for a request that named a project and no
-- application.
--
-- WHAT THIS DOES NOT DO. It does not report requested scope as assessed coverage anywhere a coverage
-- claim is made. `primary_application` answers "which application is this request about", which is an
-- identity question; the cadence and posture views continue to count executions, because "was it
-- reviewed" is a different question and PP-1 governs it.
-- =============================================================================

CREATE OR REPLACE VIEW request_board AS
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
    n.name AS org_node_name,
    ( SELECT array_agg(anc.name ORDER BY c.depth DESC) AS array_agg
           FROM org_closure c
             JOIN org_node anc ON anc.id = c.ancestor_id
          WHERE c.descendant_id = r.requested_org_node_id AND c.depth > 0) AS org_ancestors,
    r.requested_by,
    r.requester_contact_id,
    ( SELECT a.lead_principal_id
           FROM assessment a
          WHERE a.request_id = r.id
          ORDER BY a.created_at DESC
         LIMIT 1) AS lead_principal_id,
    ( SELECT count(DISTINCT x.asset_id)
           FROM (SELECT sa.asset_id
                   FROM assessment_scope_asset sa
                   JOIN assessment ex ON ex.id = sa.assessment_id
                  WHERE ex.request_id = r.id
                 UNION
                 SELECT rsa.asset_id
                   FROM assessment_request_scope_asset rsa
                  WHERE rsa.request_id = r.id) x) AS scope_assets,
    COALESCE(
        ( SELECT a.display_name
               FROM assessment_scope_asset sa
                 JOIN assessment ex ON ex.id = sa.assessment_id
                 JOIN asset a ON a.id = sa.asset_id
                 JOIN asset_type t ON t.id = a.type_id
              WHERE ex.request_id = r.id AND t.code = 'APPLICATION'::text
              ORDER BY a.display_name
             LIMIT 1),
        ( SELECT a.display_name
               FROM assessment_request_scope_asset rsa
                 JOIN asset a ON a.id = rsa.asset_id
                 JOIN asset_type t ON t.id = a.type_id
              WHERE rsa.request_id = r.id AND t.code = 'APPLICATION'::text
              ORDER BY rsa.named_by_requester DESC, a.display_name
             LIMIT 1),
        ( SELECT ra.display_name
               FROM assessment_request_scope_asset rsa
                 JOIN asset_composition c ON c.asset_id = rsa.asset_id
                 JOIN asset ra ON ra.id = c.root_id
                 JOIN asset_type rt ON rt.id = ra.type_id
              WHERE rsa.request_id = r.id AND rt.code = 'APPLICATION'::text
              ORDER BY c.depth, ra.display_name
             LIMIT 1)) AS primary_application,
    COALESCE(( SELECT count(*) AS count
           FROM finding f
          WHERE f.discovered_in_request_id = r.id), 0::bigint) AS finding_total,
    COALESCE(( SELECT count(*) AS count
           FROM finding f
          WHERE f.discovered_in_request_id = r.id AND f.state = 'OPEN'::text), 0::bigint) AS finding_open,
    COALESCE(( SELECT count(*) AS count
           FROM finding f
          WHERE f.discovered_in_request_id = r.id AND f.closure_reason = 'RISK_ACCEPTED'::text), 0::bigint) AS finding_accepted,
    COALESCE(( SELECT count(*) AS count
           FROM finding f
             JOIN severity_level s ON s.id = f.effective_severity_id
          WHERE f.discovered_in_request_id = r.id AND f.state = 'OPEN'::text AND (s.code = ANY (ARRAY['CRITICAL'::text, 'HIGH'::text]))), 0::bigint) AS finding_severe_open,
    r.trigger_id,
    tg.code AS trigger_code,
    tg.label_i18n AS trigger_label,
    COALESCE(tg.counts_as_full_review, false) AS trigger_is_full_review,
    ( SELECT max(t.occurred_at) AS max
           FROM assessment_request_transition t
             JOIN workflow_state ts ON ts.code = t.to_state AND ts.tenant_id = t.tenant_id
          WHERE t.request_id = r.id AND (EXISTS ( SELECT 1
                   FROM review_completion_state c
                  WHERE c.state_code = ts.code))) AS closed_at,
    ( SELECT ws.category
           FROM workflow_state ws
             JOIN assessment_type ty ON ty.workflow_definition_id = ws.definition_id
          WHERE ty.id = r.type_id AND ws.code = r.state) AS state_category,
    ( SELECT COALESCE(ws.label_i18n ->> 'en'::text, ws.code) AS "coalesce"
           FROM workflow_state ws
             JOIN assessment_type ty ON ty.workflow_definition_id = ws.definition_id
          WHERE ty.id = r.type_id AND ws.code = r.state) AS state_label
   FROM assessment_request r
     LEFT JOIN org_node n ON n.id = r.requested_org_node_id
     LEFT JOIN assessment_trigger tg ON tg.id = r.trigger_id;
