-- =============================================================================
-- V027 — a request workflow somebody can read.
--
-- WHY
--
-- Version 1 shipped twenty-two states and thirty-two transitions: draft, submitted, intake review,
-- returned for info, pending approval, accepted, deferred, scheduled, assigned, in progress, blocked,
-- testing complete, report draft, report under QA, report delivered, fixing, retest requested, retest
-- in progress, and four ways to finish. It is a faithful model of a large consultancy's engagement
-- process and it is the wrong model for the team using this platform.
--
-- The cost was not theoretical. Getting one request from "being tested" to "closed" took five
-- separate transitions through three states that exist only to hand a document between two people —
-- and every one of them was a button on the page. The state a request is in stopped being something
-- a person could see at a glance, which is the one thing a request board is for.
--
-- Five states carry the work: it is waiting, somebody is testing, the team is fixing, we are checking
-- the fix, it is done. This version has those five, plus CANCELLED — see below.
--
-- WHY SIX AND NOT FIVE
--
-- CANCELLED is not a sixth kind of progress; it is the difference between "we finished" and "we gave
-- up", and that distinction is load-bearing here. review_completion_state decides which terminal
-- states discharge a periodic review obligation, and V026 exists because a cancelled review was
-- counting as a completed one — an application could read "reviewed, next due in twelve months" on
-- the strength of a request somebody cancelled. Collapsing both into one CLOSED reinstates exactly
-- that defect. One extra option in a dropdown is a much smaller cost than a coverage number that
-- lies.
--
-- HOW, GIVEN INV-WRK-01
--
-- An activated definition is immutable, and rightly: editing it would retroactively change the
-- meaning of every request that ever passed through it. So this is a NEW VERSION. Version 1 is
-- retired, version 2 is built in DRAFT and then activated, and the assessment type is repointed.
--
-- In-flight requests are MIGRATED rather than stranded, and each migration writes a transition
-- record attributed to SYSTEM naming this migration. A state column that changed with nothing in the
-- log to say so would be the one thing the log exists to prevent (PP-5).
-- =============================================================================

DO $$
DECLARE
    tenant_row      uuid;
    type_row        uuid;
    old_definition  uuid;
    new_definition  uuid;
    state_open      uuid;
    state_progress  uuid;
    state_fixing    uuid;
    state_retest    uuid;
    state_closed    uuid;
    state_cancelled uuid;
    migrated        int;
BEGIN
FOR tenant_row IN SELECT id FROM tenant LOOP
    PERFORM set_config('aspm.current_tenant', tenant_row::text, true);

    FOR type_row, old_definition IN
        SELECT ty.id, ty.workflow_definition_id FROM assessment_type ty
    LOOP
        -- Idempotent: a replay finds version 2 already active and does nothing. Keyed on the version
        -- number rather than on "is there an active definition", because the second form would
        -- rebuild the workflow on every start.
        IF EXISTS (SELECT 1 FROM workflow_definition
                    WHERE assessment_type_id = type_row AND version >= 2) THEN
            CONTINUE;
        END IF;

        INSERT INTO workflow_definition (tenant_id, assessment_type_id, version, state)
        VALUES (tenant_row, type_row, 2, 'DRAFT')
        RETURNING id INTO new_definition;

        -- sla_clock_running: the clock runs while the request is ours to move. It stops in FIXING
        -- and RETEST — the work is with the project team then, and counting that time against the
        -- security team's service level would make every deadline a function of somebody else's
        -- release schedule (PP-6: waiting is visible and attributed).
        INSERT INTO workflow_state (tenant_id, definition_id, code, label_i18n, category,
                                    display_order, sla_clock_running)
        VALUES
            (tenant_row, new_definition, 'OPEN',
             '{"en": "Open", "vi": "Mở"}'::jsonb,        'OPEN',              10, true),
            (tenant_row, new_definition, 'IN_PROGRESS',
             '{"en": "In progress", "vi": "Đang đánh giá"}'::jsonb,
                                                          'IN_PROGRESS',       20, true),
            (tenant_row, new_definition, 'FIXING',
             '{"en": "Fixing", "vi": "Đang khắc phục"}'::jsonb,
                                                          'WAITING_EXTERNAL',  30, false),
            (tenant_row, new_definition, 'RETEST',
             '{"en": "Retest", "vi": "Kiểm tra lại"}'::jsonb,
                                                          'IN_PROGRESS',       40, true),
            (tenant_row, new_definition, 'CLOSED',
             '{"en": "Closed", "vi": "Đã đóng"}'::jsonb,   'TERMINAL',          50, false),
            (tenant_row, new_definition, 'CANCELLED',
             '{"en": "Cancelled", "vi": "Đã hủy"}'::jsonb, 'TERMINAL',          60, false);

        SELECT id INTO state_open      FROM workflow_state
          WHERE definition_id = new_definition AND code = 'OPEN';
        SELECT id INTO state_progress  FROM workflow_state
          WHERE definition_id = new_definition AND code = 'IN_PROGRESS';
        SELECT id INTO state_fixing    FROM workflow_state
          WHERE definition_id = new_definition AND code = 'FIXING';
        SELECT id INTO state_retest    FROM workflow_state
          WHERE definition_id = new_definition AND code = 'RETEST';
        SELECT id INTO state_closed    FROM workflow_state
          WHERE definition_id = new_definition AND code = 'CLOSED';
        SELECT id INTO state_cancelled FROM workflow_state
          WHERE definition_id = new_definition AND code = 'CANCELLED';

        -- The transitions. No guards: the readiness guards of version 1 blocked moves on conditions
        -- the requester could not see from the board, and the readiness fields are still recorded and
        -- still shown — they inform the decision rather than vetoing it. A team that wants them
        -- enforced adds a guard to a version 3 rather than getting a build.
        --
        -- Permissions are the ones version 1 used, so no role changes and no access review is needed
        -- to keep working.
        INSERT INTO workflow_transition (tenant_id, definition_id, from_state_id, to_state_id,
                                         event_code, required_permission, reason_required)
        VALUES
            (tenant_row, new_definition, state_open,     state_progress,  'start',
             'asm.request.execute', false),
            (tenant_row, new_definition, state_progress, state_fixing,    'findings_open',
             'asm.request.execute', false),
            (tenant_row, new_definition, state_progress, state_closed,    'close',
             'asm.request.execute', false),
            (tenant_row, new_definition, state_fixing,   state_retest,    'request_retest',
             'asm.request.execute', false),
            (tenant_row, new_definition, state_fixing,   state_closed,    'close',
             'asm.request.execute', false),
            (tenant_row, new_definition, state_retest,   state_fixing,    'retest_failed',
             'asm.request.execute', false),
            (tenant_row, new_definition, state_retest,   state_closed,    'close',
             'asm.request.execute', false),
            -- Reopening is a FORWARD transition with its own record, never a reversal
            -- (PRD-WRK-036). A reason is required: a closed request that reopens with no stated
            -- cause is the one entry in the log a reviewer will always ask about.
            (tenant_row, new_definition, state_closed,   state_progress,  'reopen',
             'asm.request.execute', true),
            -- Cancellable from anywhere that is not already terminal, with a reason.
            (tenant_row, new_definition, state_open,     state_cancelled, 'cancel',
             'asm.request.cancel', true),
            (tenant_row, new_definition, state_progress, state_cancelled, 'cancel',
             'asm.request.cancel', true),
            (tenant_row, new_definition, state_fixing,   state_cancelled, 'cancel',
             'asm.request.cancel', true),
            (tenant_row, new_definition, state_retest,   state_cancelled, 'cancel',
             'asm.request.cancel', true);

        -- Version 1 is retired FIRST. A partial unique index permits one ACTIVE definition per
        -- assessment type, so activating version 2 while version 1 is still active is rejected —
        -- which is the index doing its job: two active definitions would mean the transitions
        -- available for a request depended on which row a query happened to read.
        UPDATE workflow_definition SET state = 'RETIRED', retired_at = now()
         WHERE id = old_definition AND state = 'ACTIVE';

        UPDATE workflow_definition
           SET initial_state_id = state_open, validated_at = now(), activated_at = now(),
               state = 'ACTIVE'
         WHERE id = new_definition;

        -- Repointed BEFORE any request state is touched. A trigger validates every write to
        -- assessment_request.state against the states its assessment type's workflow defines
        -- (PRD-WRK-034), so setting a request to OPEN while the type still names version 1 is
        -- refused — correctly, and with a message that says exactly that.
        UPDATE assessment_type SET workflow_definition_id = new_definition WHERE id = type_row;

        -- ------------------------------------------------------------------------------------
        -- Migrate in-flight requests.
        --
        -- The mapping collapses version 1's states onto the six. Every one of the twenty-two is
        -- listed: an unmapped state would leave a request pointing at a code the active definition
        -- does not contain, which is a request with no available move and no message saying why.
        --
        -- Note what CANCELLED and REJECTED map to, and what they do not. A rejected request was
        -- never assessed, so it maps to CANCELLED and not to CLOSED — mapping it to CLOSED would
        -- hand it a COMPLETED disposition and let it discharge a review obligation.
        -- ------------------------------------------------------------------------------------
        -- The mapping is a VALUES list inlined into both statements below rather than a temporary
        -- table: the migration role holds no TEMP privilege on this database, and granting it one so
        -- a migration could hold a scratch table would widen what a migration can do for the sake of
        -- convenience.
        --
        -- The record first, so that a failure leaves no state change without an entry explaining it.
        INSERT INTO assessment_request_transition
            (tenant_id, request_id, sequence_number, from_state, to_state, event_code,
             actor_type, reason, occurred_at, sla_clock_running)
        SELECT r.tenant_id, r.id, seq.n,
               -- The log's FIRST entry must have no prior state: ck_asm_request_transition__
               -- first_has_no_prior makes sequence 1 and a null from_state the same fact. Several
               -- demo requests were seeded straight into a state with no log at all, so their
               -- migration entry IS the first one. The prior state is not lost — it is named in the
               -- reason, which is where a person reading the history will look for it.
               CASE WHEN seq.n = 1 THEN NULL ELSE r.state END,
               m.new, 'workflow_version_migration', 'MIGRATION',
               'Workflow version 2 replaced version 1 (V027). The state was mapped by the migration '
               'and chosen by nobody. Prior state: ' || r.state || '.',
               now(),
               (SELECT s.sla_clock_running FROM workflow_state s
                 WHERE s.definition_id = new_definition AND s.code = m.new)
          FROM assessment_request r
          JOIN (VALUES ('DRAFT','OPEN'), ('SUBMITTED','OPEN'), ('INTAKE_REVIEW','OPEN'),
                       ('RETURNED_FOR_INFO','OPEN'), ('PENDING_APPROVAL','OPEN'),
                       ('ACCEPTED','OPEN'), ('DEFERRED','OPEN'), ('SCHEDULED','OPEN'),
                       ('ASSIGNED','OPEN'), ('IN_PROGRESS','IN_PROGRESS'),
                       ('BLOCKED','IN_PROGRESS'), ('TESTING_COMPLETE','IN_PROGRESS'),
                       ('REPORT_DRAFT','IN_PROGRESS'), ('REPORT_UNDER_QA','IN_PROGRESS'),
                       ('REPORT_DELIVERED','IN_PROGRESS'), ('FIXING','FIXING'),
                       ('RETEST_REQUESTED','RETEST'), ('RETEST_IN_PROGRESS','RETEST'),
                       ('CLOSED_PASSED','CLOSED'), ('CLOSED_WITH_ACCEPTED_RISK','CLOSED'),
                       ('REJECTED','CANCELLED'), ('CANCELLED','CANCELLED')) AS m(old, new) ON m.old = r.state
          CROSS JOIN LATERAL (
              SELECT coalesce((SELECT max(t.sequence_number)
                                 FROM assessment_request_transition t
                                WHERE t.request_id = r.id), 0) + 1 AS n) seq
         WHERE r.type_id = type_row AND r.state <> m.new;

        UPDATE assessment_request r
           SET state = m.new, updated_at = now()
          FROM (VALUES ('DRAFT','OPEN'), ('SUBMITTED','OPEN'), ('INTAKE_REVIEW','OPEN'),
                       ('RETURNED_FOR_INFO','OPEN'), ('PENDING_APPROVAL','OPEN'),
                       ('ACCEPTED','OPEN'), ('DEFERRED','OPEN'), ('SCHEDULED','OPEN'),
                       ('ASSIGNED','OPEN'), ('IN_PROGRESS','IN_PROGRESS'),
                       ('BLOCKED','IN_PROGRESS'), ('TESTING_COMPLETE','IN_PROGRESS'),
                       ('REPORT_DRAFT','IN_PROGRESS'), ('REPORT_UNDER_QA','IN_PROGRESS'),
                       ('REPORT_DELIVERED','IN_PROGRESS'), ('FIXING','FIXING'),
                       ('RETEST_REQUESTED','RETEST'), ('RETEST_IN_PROGRESS','RETEST'),
                       ('CLOSED_PASSED','CLOSED'), ('CLOSED_WITH_ACCEPTED_RISK','CLOSED'),
                       ('REJECTED','CANCELLED'), ('CANCELLED','CANCELLED')) AS m(old, new)
         WHERE m.old = r.state AND r.type_id = type_row AND r.state <> m.new;
        GET DIAGNOSTICS migrated = ROW_COUNT;
        RAISE NOTICE 'V027: workflow v2 activated for assessment type %, % request(s) migrated',
                     type_row, migrated;

    END LOOP;
END LOOP;
END $$;

-- ----------------------------------------------------------------------------------------------
-- The review-completion policy follows the new vocabulary.
--
-- Rows for version 1's codes are LEFT IN PLACE. They are keyed on the state code rather than on a
-- definition, so they stay meaningful for the historical transitions that still name those codes,
-- and application_full_review reads the transition log rather than the current state.
-- ----------------------------------------------------------------------------------------------
DO $$
DECLARE tenant_row uuid;
BEGIN
FOR tenant_row IN SELECT id FROM tenant LOOP
    PERFORM set_config('aspm.current_tenant', tenant_row::text, true);
    INSERT INTO review_completion_state (tenant_id, state_code, disposition)
    VALUES (tenant_row, 'CLOSED', 'COMPLETED'),
           (tenant_row, 'CANCELLED', 'ABANDONED')
    ON CONFLICT (tenant_id, state_code) DO UPDATE SET disposition = EXCLUDED.disposition;
END LOOP;
END $$;
