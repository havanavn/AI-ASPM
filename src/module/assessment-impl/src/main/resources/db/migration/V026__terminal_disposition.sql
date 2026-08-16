-- =============================================================================
-- V026 — a cancelled review is not a completed review.
--
-- THE DEFECT V024 SHIPPED
--
-- application_review_cadence counted a full review as completed whenever the request had reached a
-- state in the TERMINAL category. Four seeded states are terminal — closed-passed, closed-with-
-- accepted-risk, rejected and cancelled — and only the first two mean anybody looked at anything.
--
-- The consequence is not cosmetic. The annual obligation on a critical application could be
-- discharged by raising a request, marking it a periodic review, and cancelling it: the application
-- would then read CURRENT with a next-due date twelve months out, and the number would be wrong in
-- the reassuring direction. That is precisely the failure PP-1 exists to prevent — not-measured
-- presented as measured-and-clean — arriving through the coverage metric itself.
--
-- WHY NOT A COLUMN ON workflow_state
--
-- The first attempt added terminal_disposition to workflow_state and was refused by INV-WRK-01: the
-- states of an ACTIVATED workflow definition are immutable. The refusal is right, and it is right
-- for a reason that applies here as much as to the permission column it was written for — if the
-- meaning of a state could be edited in place, every historical request that passed through it would
-- silently acquire a different meaning too, and no version would record that anything changed.
--
-- WHY NOT A LIST OF CODES IN THE VIEW
--
-- Excluding 'CANCELLED' and 'REJECTED' in SQL hardcodes two codes from a tenant-configurable
-- vocabulary (ADR-027). A tenant whose abandoned state is called WITHDRAWN would silently start
-- counting withdrawals as completed reviews, and nothing would fail.
--
-- WHAT THIS DOES INSTEAD
--
-- The classification becomes what it actually is: REVIEW POLICY, not workflow structure. The
-- workflow says the request stopped; the review policy says which stopping points discharge the
-- obligation. They have different owners and different lifecycles, so they are different tables —
-- and this one sits beside full_review_policy, where the rest of the obligation already lives.
-- =============================================================================

DROP VIEW IF EXISTS application_review_cadence;
DROP VIEW IF EXISTS application_full_review;

-- The abandoned first attempt, removed so that a database which applied the earlier form of this
-- migration converges with a fresh one.
ALTER TABLE workflow_state DROP CONSTRAINT IF EXISTS ck_workflow_state__disposition;
ALTER TABLE workflow_state DROP COLUMN IF EXISTS terminal_disposition;

CREATE TABLE IF NOT EXISTS review_completion_state (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id         uuid        NOT NULL,
    -- The state CODE, not the state row. A workflow definition is versioned and a new version gets
    -- new state rows; the policy "closing as passed completes a review" survives that, and a foreign
    -- key to one version's row would not.
    state_code        text        NOT NULL,
    -- COMPLETED discharges the obligation; ABANDONED explicitly does not. A terminal state with no
    -- row here is UNCLASSIFIED and counts as neither — the safe default, because an unclassified
    -- state that silently counted would be the same defect this migration is fixing.
    disposition       text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    updated_by        uuid,
    row_version       int         NOT NULL DEFAULT 1,

    CONSTRAINT uq_review_completion_state UNIQUE (tenant_id, state_code),
    CONSTRAINT ck_review_completion_state__disposition
        CHECK (disposition IN ('COMPLETED', 'ABANDONED'))
);

SELECT apply_tenant_isolation('review_completion_state');

COMMENT ON TABLE review_completion_state IS
    'Which terminal workflow states discharge a periodic review obligation. Review policy rather than '
    'workflow structure: the workflow says a request stopped, this says whether it stopped because '
    'the work was done. Kept out of workflow_state because an activated definition is immutable '
    '(INV-WRK-01) and because the two are configured by different people at different times.';

GRANT SELECT, INSERT, UPDATE ON review_completion_state TO app_runtime;
GRANT SELECT ON review_completion_state TO integrity_verifier;

-- ----------------------------------------------------------------------------------------------
-- application_full_review — carries the disposition, and reports closure only where it is known.
--
-- closed_at now comes from the transition into a COMPLETED terminal state. A request that was
-- cancelled still appears in the history — the record of what happened is inviolable (PP-5), and a
-- cancelled annual review is something a person auditing coverage needs to SEE — but it carries no
-- closure date and is marked abandoned, so it cannot be mistaken for a discharge.
-- ----------------------------------------------------------------------------------------------
-- Dropped and recreated rather than replaced: CREATE OR REPLACE cannot INSERT a column into the
-- middle of a view's column list, and terminal_disposition belongs beside the category it qualifies
-- rather than appended at the end where nobody reading the view would connect the two.
-- The cadence view is dropped first because it reads this one.
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
  LEFT JOIN workflow_state ws ON ws.code = r.state AND ws.tenant_id = r.tenant_id
  LEFT JOIN review_completion_state rcs ON rcs.state_code = r.state;

COMMENT ON VIEW application_full_review IS
    'Whole-application reviews per application. closed_at is set only where the request reached a '
    'terminal state the tenant classified as COMPLETED; an abandoned review keeps its row and its '
    'abandoned_at, because the attempt is part of the record and its absence from the coverage count '
    'is the point.';

GRANT SELECT ON application_full_review TO app_runtime, integrity_verifier;

-- ----------------------------------------------------------------------------------------------
-- The cadence follows: only completed reviews count, and an abandoned one is reported separately
-- rather than dropped. An application whose last three annual reviews were all cancelled is not the
-- same as one nobody ever raised a review for, and a coverage panel that showed them identically
-- would hide a pattern somebody needs to act on.
-- ----------------------------------------------------------------------------------------------
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
     WHERE fr.closed_at IS NULL AND fr.state_category <> 'TERMINAL'
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
