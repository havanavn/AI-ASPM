-- =============================================================================================
-- V070 — a plan is not a queue of requests.
--
-- WHAT WAS MISSING, AND HOW IT WAS FOUND. Reported from use on 2026-08-25. The planning screen drew
-- a Gantt of assessment REQUESTS and offered a "Schedule" button that opened the intake form with a
-- few fields pre-filled. Laying out a year of periodic reviews therefore meant creating one full
-- request per review — each demanding a project scope, a title and a payload that the person doing
-- the planning does not have in October for work happening the following September. The reporter's
-- words: "khi schedule có thể cho cả năm nên nếu điền đủ thông tin thì sẽ không có thông tin để
-- điền" — if the form insists on complete information, there is no information to give it yet.
--
-- The consequence was not merely friction. It made the periodic obligation unplannable, which is the
-- one thing DOC-01's cadence requirements exist to make visible: `full_review_policy` says a
-- CRITICAL application is owed a review every N months, V024 records which requests discharge that
-- obligation, and there was nowhere at all to write down WHEN the estate intends to discharge it.
-- The plan lived in a spreadsheet, and a spreadsheet is where the coverage question stops being
-- answerable.
--
-- WHAT A WINDOW IS. An intention: this target, between these two dates. Nothing more. It carries no
-- payload, no scope descriptor, no workflow state and no assignee, because none of those are known
-- when a year is being laid out and inventing them would be the fabrication product principle 1
-- forbids. It is the smallest thing that can answer "when are we going to do this, and is the
-- calendar over-committed".
--
-- WHY A SEPARATE TABLE AND NOT A DRAFT REQUEST. Three reasons, and the first is the load-bearing one.
--
--   1. A request is the record of work. Its state machine, its transition log, its SLA clocks and
--      its assignment all begin when the work does. Four hundred draft requests dated next year put
--      four hundred rows into every "open requests" figure on the platform, and the request board —
--      the queue an assessor works from — becomes a list of things nobody is meant to start. The
--      count that says how much work is in flight has to keep meaning that.
--   2. Cancelling a plan is ordinary. Plans move: a release slips, a team is reassigned, a quarter is
--      given to an incident. Cancelling a REQUEST is a disposition with a reason, an audit trail and
--      a workflow transition, and correctly so. Cancelling an INTENTION should cost a click.
--   3. The two answer different questions and must be able to disagree. "We planned four reviews and
--      did two" is the finding a planning cycle exists to surface. If the plan and the record are the
--      same rows, the plan silently becomes whatever happened, and the gap it was supposed to expose
--      closes itself.
--
-- The join between the two is `request_id`: a window that became work points at the work. That is
-- what lets the plan report planned-versus-actual instead of one or the other.
--
-- WHY WINDOWS ARE STORED AND NOT DERIVED FROM A RECURRENCE RULE. Ratified with the reporter on
-- 2026-08-25, choosing stored windows over a stored rule. A rule ("every three months from March")
-- is fewer keystrokes and it was offered; it was rejected because the plan is a commitment somebody
-- reviews, and a derived plan changes retroactively when the rule is edited. Somebody asking in
-- November what Q1 was supposed to look like would be shown Q1 recomputed under today's rule, with
-- nothing on the screen admitting it. Product principle 5 makes the record of what happened
-- inviolable; a plan of record is close enough to that to be held to it.
--
-- The convenience is kept where it costs nothing: the interface PROPOSES evenly spaced windows from
-- a times-per-year figure — itself defaulted from `full_review_policy.interval_months`, so the tenant
-- states the cadence once — and the planner edits them before saving. What is stored is the windows
-- that were agreed, which is what a plan is.
--
-- WHY THE TARGET IS ONE COLUMN AND NOT `application_id` PLUS `project_id`. ADR-009: there is one
-- Asset aggregate with a type registry, not five parallel inventories. An application and a project
-- are both assets, so a window points at an asset and the registry says which kind it is. Two
-- nullable columns would need a CHECK to keep exactly one populated, would need every query to
-- coalesce them, and would need a third column the day somebody plans an assessment of a service.
--
-- Both granularities are needed and that is why this matters. The facts a planner sizes work from —
-- `api_count`, `access_path` — are declared on the PROJECT, because that is where the Excel inventory
-- records them. An estate is planned at application level and sized at project level, and one column
-- carries both without the schema choosing for the planner.
--
-- WHAT THIS DOES NOT CONSTRAIN, AND WHERE THAT IS ENFORCED. Nothing here stops a window pointing at
-- an asset of some other type. A CHECK cannot express it — the type is a join away — and ADR-030 puts
-- integrity that spans a boundary in the domain layer with reconciliation rather than in a trigger.
-- `PlanWindows` refuses a target that is not an application or a project on the one write path, and
-- `PlanWindowTest` asserts the refusal, because an invariant with no test is a comment.
-- =============================================================================================

-- -----------------------------------------------------------------------------
-- A parent of a tenant-composite foreign key needs the composite key to point at.
--
-- `assessment_trigger` had only `UNIQUE (tenant_id, code)` and its primary key, because V065 gave
-- `UNIQUE (tenant_id, id)` to the tables that were already referenced by a single-column foreign key
-- and nothing referenced the trigger table until now. Additive and cheap; without it the reference
-- below cannot be composite, and a single-column reference is the cross-tenant write V065 exists to
-- make unrepresentable.
-- -----------------------------------------------------------------------------
DO $add_key$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conrelid = 'assessment_trigger'::regclass
                      AND contype = 'u'
                      AND pg_get_constraintdef(oid) = 'UNIQUE (tenant_id, id)') THEN
        ALTER TABLE assessment_trigger
            ADD CONSTRAINT uq_assessment_trigger__tenant_id UNIQUE (tenant_id, id);
    END IF;
END
$add_key$;

CREATE TABLE IF NOT EXISTS assessment_plan_window (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id           uuid        NOT NULL,

    -- The application or project this window is for. Composite reference: the tenant is part of the
    -- identity the engine matches on, so a window pointing into another tenant's estate is not
    -- refused, it is unrepresentable (V065).
    target_asset_id     uuid        NOT NULL,

    -- The window itself. Dates, not timestamps: a plan is made in a business calendar and a planner
    -- choosing "the first fortnight of March" is not choosing a time of day. Storing an instant would
    -- invite a timezone to shift the plan across a quarter boundary.
    starts_on           date        NOT NULL,
    ends_on             date        NOT NULL,

    -- What kind of work is intended, and why. BOTH NULLABLE, and this is the point of the table: a
    -- plan laid out for next year legitimately does not yet know whether a target needs a penetration
    -- test or a code review. A default would be a decision nobody made, presented as one they did.
    assessment_type_id  uuid,
    trigger_id          uuid,

    -- What the planner wants the next reader to know. Free text on purpose — the reasons a window sits
    -- where it does ("after the payments migration", "before the audit") are not enumerable.
    note                text,

    -- PLANNED -> CONVERTED when somebody raises the request, or -> CANCELLED when the plan changes.
    -- Cancelled rows are KEPT: "we planned six and cancelled two" is the planning finding, and a
    -- deleted row reads as a plan that never existed.
    state               text        NOT NULL DEFAULT 'PLANNED',

    -- The request this window became. Set together with state = 'CONVERTED' and never cleared: if the
    -- request is later abandoned, the window still records that this plan was acted on, which is a
    -- different fact from the plan having been dropped.
    request_id          uuid,

    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          uuid,
    row_version         int         NOT NULL DEFAULT 1,

    CONSTRAINT fk_plan_window__target
        FOREIGN KEY (tenant_id, target_asset_id) REFERENCES asset (tenant_id, id)
        ON DELETE CASCADE,
    -- CASCADE and not RESTRICT: an asset removed from the inventory takes its plan with it. A window
    -- for a target that no longer exists is not a plan, it is a row nobody can act on or interpret.

    CONSTRAINT fk_plan_window__type
        FOREIGN KEY (tenant_id, assessment_type_id) REFERENCES assessment_type (tenant_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_plan_window__trigger
        FOREIGN KEY (tenant_id, trigger_id) REFERENCES assessment_trigger (tenant_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_plan_window__request
        FOREIGN KEY (tenant_id, request_id) REFERENCES assessment_request (tenant_id, id)
        ON DELETE SET NULL,

    -- A window that ends before it starts is not a window. Same-day is allowed: a one-day review is
    -- a real engagement, and forcing a planner to pad it would put a false duration into the capacity
    -- figures this table feeds.
    CONSTRAINT ck_plan_window__ordered CHECK (ends_on >= starts_on),

    CONSTRAINT ck_plan_window__state
        CHECK (state IN ('PLANNED', 'CONVERTED', 'CANCELLED')),

    -- Converted means there is something to point at. Without this the state could claim work was
    -- raised while the join that proves it is empty, and planned-versus-actual would over-report.
    CONSTRAINT ck_plan_window__converted_has_request
        CHECK (state <> 'CONVERTED' OR request_id IS NOT NULL)
);

SELECT apply_tenant_isolation('assessment_plan_window');

-- -----------------------------------------------------------------------------
-- Indexes. Each names the query it serves, per DOC-00's prohibition on a table whose indexing
-- strategy does not.
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS ix_plan_window__target_date
    ON assessment_plan_window (tenant_id, target_asset_id, starts_on)
    WHERE state <> 'CANCELLED';
COMMENT ON INDEX ix_plan_window__target_date IS
    'Serves: the per-target row on the planning Gantt, which reads every live window for the assets '
    'in the reader''s scope ordered by start date, and the overlap check that warns a planner they '
    'have already scheduled this target in the same period.';

CREATE INDEX IF NOT EXISTS ix_plan_window__calendar
    ON assessment_plan_window (tenant_id, starts_on, ends_on)
    WHERE state = 'PLANNED';
COMMENT ON INDEX ix_plan_window__calendar IS
    'Serves: the planned-capacity columns on the planning page, which count windows per month across '
    'the whole scope without reference to a target, and the year picker that lists which years hold '
    'a plan at all.';

CREATE INDEX IF NOT EXISTS ix_plan_window__request
    ON assessment_plan_window (tenant_id, request_id)
    WHERE request_id IS NOT NULL;
COMMENT ON INDEX ix_plan_window__request IS
    'Serves: planned-versus-actual, which joins a request back to the window it discharged, and the '
    'request detail page''s statement of which plan it came from.';

COMMENT ON TABLE assessment_plan_window IS
    'An intention to assess a target between two dates. Not a request: it carries no payload, scope, '
    'workflow state or assignee, and it is cancelled with a click rather than a disposition. The '
    'join to assessment_request is what lets the plan report planned-versus-actual.';
COMMENT ON COLUMN assessment_plan_window.target_asset_id IS
    'An application OR a project (ADR-009 — one Asset aggregate, type registry decides which). The '
    'type restriction is enforced in the domain layer under ADR-030; a CHECK cannot see the join.';
COMMENT ON COLUMN assessment_plan_window.state IS
    'PLANNED, CONVERTED once a request was raised from it, or CANCELLED. Cancelled rows are retained '
    'because a plan that was dropped and a plan that never existed are different findings.';
COMMENT ON COLUMN assessment_plan_window.request_id IS
    'The request this window became. Never cleared once set: a request later abandoned still means '
    'this plan was acted on, which is not the same as the plan having been dropped.';

GRANT SELECT, INSERT, UPDATE ON assessment_plan_window TO app_runtime;
GRANT SELECT ON assessment_plan_window TO integrity_verifier;
