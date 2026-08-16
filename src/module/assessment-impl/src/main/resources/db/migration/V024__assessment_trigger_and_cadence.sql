-- =============================================================================
-- V024 — why an assessment is happening, and when the next one is owed.
--
-- WHAT THIS ADDS
--
-- A request already records WHAT kind of work it is (assessment_type: penetration test, code review)
-- and WHERE it lands in the workflow. It does not record WHY it was raised, and the three reasons an
-- application security team actually distinguishes are:
--
--   * a change went in and needs reviewing        (targeted, narrow scope)
--   * something is about to go live for the first time (targeted, but blocking)
--   * the periodic whole-application review        (broad scope, and the one that is OWED)
--
-- Those are not the same engagement. Only the third discharges a recurring obligation, and without
-- the distinction the platform cannot answer the question a security lead is asked most often —
-- "when was this application last reviewed end to end, and when is the next one due?" Counting all
-- requests answers it wrongly and reassuringly: an application with fourteen change reviews and no
-- full review reads as the most-assessed application in the portfolio.
--
-- WHY A SEPARATE TABLE AND NOT MORE assessment_type ROWS
--
-- assessment_type carries a payload schema and a workflow definition: it is the KIND OF WORK, and a
-- change review and a periodic review are the same kind of work done for different reasons. Folding
-- the reason into the type would multiply every type by every reason, and each product of the two
-- would need its own workflow row. The two are orthogonal, so they are two columns.
--
-- ADR-027: the rows are tenant data. No trigger code appears in application code, the obligation
-- interval is per criticality tier and per tenant, and a tenant that reviews quarterly says so by
-- changing a number rather than by getting a build.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. assessment_trigger — why this request exists.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS assessment_trigger (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL,
    code            text        NOT NULL,
    label_i18n      jsonb       NOT NULL,
    -- Whether a request raised for this reason DISCHARGES the periodic obligation. This is the whole
    -- point of the table. A tenant that treats a major-release review as equivalent to the periodic
    -- one says so here; a tenant that does not, does not.
    counts_as_full_review bool  NOT NULL DEFAULT false,
    -- What the trigger means, shown beside it on the intake form. A person choosing between "change
    -- review" and "periodic review" with no guidance chooses the first one every time, because it is
    -- the one that describes what they were doing when they opened the form.
    guidance        text,
    display_order   int         NOT NULL DEFAULT 0,
    lifecycle_state text        NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      uuid,
    row_version     int         NOT NULL DEFAULT 1,

    CONSTRAINT uq_assessment_trigger__code UNIQUE (tenant_id, code),
    CONSTRAINT ck_assessment_trigger__code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,48}$'),
    CONSTRAINT ck_assessment_trigger__lifecycle
        CHECK (lifecycle_state IN ('ACTIVE', 'DEPRECATED'))
);

SELECT apply_tenant_isolation('assessment_trigger');

CREATE INDEX IF NOT EXISTS ix_assessment_trigger__order
    ON assessment_trigger (tenant_id, display_order)
    WHERE lifecycle_state = 'ACTIVE';
COMMENT ON INDEX ix_assessment_trigger__order IS
    'Serves: the trigger picker on the intake form and the trigger filter on the request board, both '
    'of which read every active row in display order on each render.';

COMMENT ON TABLE assessment_trigger IS
    'Why a request was raised, as distinct from what kind of work it is. Tenant-configured (ADR-027).';
COMMENT ON COLUMN assessment_trigger.counts_as_full_review IS
    'Whether a request raised for this reason discharges the periodic whole-application obligation. '
    'The column exists so that "assessed fourteen times" cannot be read as "reviewed end to end".';

GRANT SELECT, INSERT, UPDATE ON assessment_trigger TO app_runtime;
GRANT SELECT ON assessment_trigger TO integrity_verifier;

-- -----------------------------------------------------------------------------
-- 2. The request carries its trigger.
--
-- Nullable, and deliberately so. Requests raised before this migration have no recorded reason, and
-- inventing one for them would be fabrication: PP-1 says measured-and-clean must be distinguishable
-- from not-measured, and the same applies to recorded-as-periodic versus never-recorded. A NULL
-- trigger reads as "not stated" everywhere it is displayed.
-- -----------------------------------------------------------------------------
ALTER TABLE assessment_request ADD COLUMN IF NOT EXISTS trigger_id uuid;

COMMENT ON COLUMN assessment_request.trigger_id IS
    'The reason this request was raised. NULL means not stated — which is what every request raised '
    'before the field existed genuinely is.';

CREATE INDEX IF NOT EXISTS ix_request__trigger
    ON assessment_request (tenant_id, trigger_id, state)
    WHERE trigger_id IS NOT NULL;
COMMENT ON INDEX ix_request__trigger IS
    'Serves: the request board filtered by trigger, and the per-application full-review history, '
    'which selects the requests whose trigger counts as a full review.';

-- -----------------------------------------------------------------------------
-- 3. full_review_policy — how often a full review is owed, by criticality.
--
-- One row per criticality tier. interval_months NULL means there is no recurring obligation for that
-- tier: an explicit "none" rather than a missing row, so that a tier nobody has configured is
-- visibly unconfigured instead of silently exempt.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS full_review_policy (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id           uuid        NOT NULL,
    criticality_tier_id uuid        NOT NULL REFERENCES criticality_tier (id) ON DELETE RESTRICT,
    -- NULL = no recurring obligation. A number = at most this many months between full reviews.
    interval_months     int,
    -- How long before the due date the application starts showing as approaching. Separate from the
    -- interval because "you have 30 days" and "every 12 months" are different decisions, and a team
    -- that wants a quarter's warning should not have to shorten the interval to get it.
    warn_days_before    int         NOT NULL DEFAULT 60,
    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          uuid,
    row_version         int         NOT NULL DEFAULT 1,

    CONSTRAINT uq_full_review_policy__tier UNIQUE (tenant_id, criticality_tier_id),
    CONSTRAINT ck_full_review_policy__interval
        CHECK (interval_months IS NULL OR interval_months BETWEEN 1 AND 120),
    CONSTRAINT ck_full_review_policy__warn CHECK (warn_days_before BETWEEN 0 AND 366)
);

SELECT apply_tenant_isolation('full_review_policy');

COMMENT ON TABLE full_review_policy IS
    'How often a whole-application review is owed, per criticality tier. Tenant-configured: the '
    'twelve-month figure common in regulated groups is a default, not a product constant (ADR-027).';

GRANT SELECT, INSERT, UPDATE ON full_review_policy TO app_runtime;
GRANT SELECT ON full_review_policy TO integrity_verifier;

-- -----------------------------------------------------------------------------
-- 4. application_full_review — the full reviews of one application, with dates.
--
-- One row per (application, request) where the request's trigger counts as a full review. The dates
-- are the two a person actually asks for: when work started and when the request was closed.
--
-- started_at comes from the ASSESSMENT, because that is when somebody began testing. It falls back
-- to the request's submitted_at only when no assessment exists, and the fallback is marked so the
-- interface can say which it is showing rather than presenting an intake date as a start date.
--
-- closed_at comes from the transition log — the moment the request entered a terminal state. Not
-- updated_at, which moves whenever anybody edits anything, and would report a comment added last
-- week as the closure date of a request closed last year.
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
DROP VIEW IF EXISTS application_full_review;

CREATE VIEW application_full_review AS
SELECT sa.asset_id,
       r.tenant_id,
       r.id                                     AS request_id,
       r.request_code,
       r.title,
       r.state,
       tg.code                                  AS trigger_code,
       tg.label_i18n                            AS trigger_label,
       ws.category                              AS state_category,
       coalesce(ex.started_at, r.submitted_at)  AS started_at,
       ex.started_at IS NULL                    AS started_at_is_intake_date,
       (SELECT max(t.occurred_at)
          FROM assessment_request_transition t
          JOIN workflow_state ts ON ts.code = t.to_state AND ts.tenant_id = t.tenant_id
         WHERE t.request_id = r.id AND ts.category = 'TERMINAL') AS closed_at,
       ex.outcome,
       r.due_at
  FROM assessment_request r
  JOIN assessment_trigger tg ON tg.id = r.trigger_id AND tg.counts_as_full_review
  JOIN assessment ex ON ex.request_id = r.id
  JOIN assessment_scope_asset sa ON sa.assessment_id = ex.id
  LEFT JOIN workflow_state ws ON ws.code = r.state AND ws.tenant_id = r.tenant_id;

COMMENT ON VIEW application_full_review IS
    'Whole-application reviews per application, with start and close dates. Rows exist only where the '
    'request names a trigger the tenant marked as counting for the periodic obligation.';

GRANT SELECT ON application_full_review TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 5. application_review_cadence — the count, the last one, and when the next is owed.
--
-- Kept separate from application_inventory rather than folded into it, so that the inventory view
-- keeps one job. The inventory joins this by asset id.
--
-- full_review_status is computed HERE and nowhere else (PP-10). Five values, and the distinction
-- between the first two is the one PP-1 exists for:
--
--   NO_OBLIGATION — the tier has no recurring requirement configured
--   NEVER         — an obligation exists and no full review has ever completed. NOT "overdue": the
--                   clock never started, and reporting it as overdue by an arbitrary amount would be
--                   inventing a date. It is its own state because it needs its own action.
--   CURRENT       — reviewed, and the next one is not near
--   DUE_SOON      — inside the tier's warning window
--   OVERDUE       — past the interval
-- -----------------------------------------------------------------------------
DROP VIEW IF EXISTS application_review_cadence;

CREATE VIEW application_review_cadence AS
WITH completed AS (
    SELECT fr.asset_id,
           fr.tenant_id,
           count(*)                                  AS full_review_count,
           max(fr.closed_at)                         AS last_full_review_at
      FROM application_full_review fr
     WHERE fr.closed_at IS NOT NULL
     GROUP BY fr.asset_id, fr.tenant_id
),
in_flight AS (
    SELECT fr.asset_id, fr.tenant_id, count(*) AS full_review_in_flight
      FROM application_full_review fr
     WHERE fr.closed_at IS NULL
     GROUP BY fr.asset_id, fr.tenant_id
)
SELECT a.id                                          AS asset_id,
       a.tenant_id,
       coalesce(c.full_review_count, 0)              AS full_review_count,
       coalesce(f.full_review_in_flight, 0)          AS full_review_in_flight,
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
  -- The tier is the asset's own where it has one and the owning node's otherwise, matching the
  -- criticality_inherited rule application_inventory already applies. Two places deriving the
  -- effective tier differently would put an application in one tier on the list and another on the
  -- cadence panel.
  LEFT JOIN full_review_policy p
         ON p.criticality_tier_id = coalesce(a.criticality_tier_id, n.criticality_tier_id);

COMMENT ON VIEW application_review_cadence IS
    'Periodic full-review position per application. NEVER is distinct from OVERDUE because an '
    'application that has never been reviewed has no elapsed interval to report and needs a different '
    'action (PP-1).';

GRANT SELECT ON application_review_cadence TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 6. The request board carries the trigger.
-- -----------------------------------------------------------------------------
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
       -- Added in V024. The code is what the board filters on; the label is what it displays.
       r.trigger_id,
       tg.code                                  AS trigger_code,
       tg.label_i18n                            AS trigger_label,
       coalesce(tg.counts_as_full_review, false) AS trigger_is_full_review,
       -- When this request entered a terminal state, or NULL while it is still open. The board's
       -- closed column and the application's review history read the same value from the same place.
       (SELECT max(t.occurred_at)
          FROM assessment_request_transition t
          JOIN workflow_state ts ON ts.code = t.to_state AND ts.tenant_id = t.tenant_id
         WHERE t.request_id = r.id AND ts.category = 'TERMINAL') AS closed_at,
       ws.category                              AS state_category
  FROM assessment_request r
  LEFT JOIN org_node n ON n.id = r.requested_org_node_id
  LEFT JOIN assessment_trigger tg ON tg.id = r.trigger_id
  LEFT JOIN workflow_state ws ON ws.code = r.state AND ws.tenant_id = r.tenant_id;

COMMENT ON VIEW request_board IS
    'The assessment request board. Finding counts are of findings DISCOVERED IN the request, not of '
    'findings against the assets it covers — counting the latter would report an application''s whole '
    'history as this engagement''s result. V024 added the trigger and the closure date.';

GRANT SELECT ON request_board TO app_runtime, integrity_verifier;
