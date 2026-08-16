-- The DOC-09 §4 machine as the default definition. Seeded so a tenant EDITS the specified workflow
-- rather than authoring one — "configurable structure, opinionated defaults" (product principle 3).
DO $seed$
DECLARE
    t         uuid := '11111111-1111-1111-1111-111111111111';
    asm_type  uuid := 'cccccccc-0000-4000-8000-000000000001';
    def       uuid := 'eeeeeeee-0000-4000-8000-00000000000e';
    s         record;
    initial   uuid;
BEGIN
    PERFORM set_config('aspm.current_tenant', t::text, true);

    INSERT INTO workflow_definition (id, tenant_id, assessment_type_id, version, state)
    VALUES (def, t, asm_type, 1, 'DRAFT')
    ON CONFLICT (id) DO NOTHING;

    -- States, with the category that decides clock behaviour. WAITING_EXTERNAL states pause the clock,
    -- which DOC-09 §4 requires for RETURNED_FOR_INFO and PENDING_APPROVAL and which PRD-RSK-034 makes
    -- require a blocking attribution.
    FOR s IN SELECT * FROM (VALUES
        ('DRAFT','OPEN',true,10),
        ('SUBMITTED','OPEN',true,20),
        ('INTAKE_REVIEW','IN_PROGRESS',true,30),
        ('RETURNED_FOR_INFO','WAITING_EXTERNAL',false,40),
        ('PENDING_APPROVAL','WAITING_EXTERNAL',false,50),
        ('ACCEPTED','OPEN',true,60),
        ('DEFERRED','WAITING_EXTERNAL',false,70),
        ('SCHEDULED','OPEN',true,80),
        ('ASSIGNED','OPEN',true,90),
        ('IN_PROGRESS','IN_PROGRESS',true,100),
        ('BLOCKED','WAITING_EXTERNAL',false,110),
        ('TESTING_COMPLETE','IN_PROGRESS',true,120),
        ('REPORT_DRAFT','IN_PROGRESS',true,130),
        ('REPORT_UNDER_QA','IN_PROGRESS',true,140),
        ('REPORT_DELIVERED','IN_PROGRESS',true,150),
        ('FIXING','IN_PROGRESS',true,160),
        ('RETEST_REQUESTED','OPEN',true,170),
        ('RETEST_IN_PROGRESS','IN_PROGRESS',true,180),
        ('CLOSED_PASSED','TERMINAL',false,190),
        ('CLOSED_WITH_ACCEPTED_RISK','TERMINAL',false,200),
        ('REJECTED','TERMINAL',false,210),
        ('CANCELLED','TERMINAL',false,220)
    ) AS v(code, category, clock, ord) LOOP
        INSERT INTO workflow_state (tenant_id, definition_id, code, label_i18n, category,
                                    sla_clock_running, display_order)
        VALUES (t, def, s.code, jsonb_build_object('en', replace(initcap(replace(s.code,'_',' ')),'_',' ')),
                s.category, s.clock, s.ord)
        ON CONFLICT DO NOTHING;
    END LOOP;

    SELECT id INTO initial FROM workflow_state
     WHERE definition_id = def AND code = 'DRAFT';
    UPDATE workflow_definition SET initial_state_id = initial WHERE id = def;

    -- Transitions, exactly as DOC-09 §4 tabulates them. reason_required is set where the document says
    -- "reason required", and on every transition to a non-success terminal state (DOC-09 §3).
    INSERT INTO workflow_transition (tenant_id, definition_id, from_state_id, to_state_id, event_code,
                                     required_permission, reason_required, guard_rule)
    SELECT t, def, f.id, x.id, v.event, v.permission, v.reason, v.guard::jsonb
      FROM (VALUES
        ('DRAFT','submit','SUBMITTED','asm.request.submit',false,'{"guard":"submit_ready"}'),
        ('SUBMITTED','begin_triage','INTAKE_REVIEW','asm.request.triage',false,'{}'),
        ('INTAKE_REVIEW','request_information','RETURNED_FOR_INFO','asm.request.triage',true,'{}'),
        ('RETURNED_FOR_INFO','resubmit','SUBMITTED','asm.request.submit',false,'{"guard":"submit_ready"}'),
        ('INTAKE_REVIEW','require_approval','PENDING_APPROVAL','asm.request.triage',false,'{}'),
        ('PENDING_APPROVAL','approve','ACCEPTED','asm.request.approve',false,'{"guard":"approver_differs"}'),
        ('PENDING_APPROVAL','deny','REJECTED','asm.request.approve',true,'{"guard":"approver_differs"}'),
        ('INTAKE_REVIEW','accept','ACCEPTED','asm.request.accept',false,'{"guard":"submit_ready"}'),
        ('INTAKE_REVIEW','reject','REJECTED','asm.request.triage',true,'{}'),
        ('INTAKE_REVIEW','defer','DEFERRED','asm.request.triage',true,'{}'),
        ('DEFERRED','reconsider','INTAKE_REVIEW','asm.request.triage',false,'{}'),
        ('ACCEPTED','schedule','SCHEDULED','asm.request.schedule',false,'{}'),
        ('SCHEDULED','assign','ASSIGNED','asm.request.schedule',false,'{}'),
        ('ASSIGNED','start','IN_PROGRESS','asm.request.execute',false,'{}'),
        ('IN_PROGRESS','block','BLOCKED','asm.request.execute',true,'{}'),
        ('BLOCKED','unblock','IN_PROGRESS','asm.request.execute',false,'{}'),
        ('IN_PROGRESS','complete_testing','TESTING_COMPLETE','asm.request.execute',false,'{}'),
        ('TESTING_COMPLETE','begin_report','REPORT_DRAFT','asm.request.execute',false,'{}'),
        ('REPORT_DRAFT','submit_for_qa','REPORT_UNDER_QA','asm.request.execute',false,'{}'),
        ('REPORT_UNDER_QA','return_to_author','REPORT_DRAFT','asm.request.qa',true,'{}'),
        ('REPORT_UNDER_QA','approve_report','REPORT_DELIVERED','asm.request.qa',false,'{"guard":"qa_differs"}'),
        ('REPORT_DELIVERED','findings_open','FIXING','asm.request.execute',false,'{}'),
        ('REPORT_DELIVERED','no_findings','CLOSED_PASSED','asm.request.execute',false,'{}'),
        ('FIXING','request_retest','RETEST_REQUESTED','asm.request.submit',false,'{}'),
        ('RETEST_REQUESTED','start_retest','RETEST_IN_PROGRESS','asm.request.execute',false,'{}'),
        ('RETEST_IN_PROGRESS','retest_failed','FIXING','asm.request.execute',true,'{}'),
        ('RETEST_IN_PROGRESS','retest_passed','CLOSED_PASSED','asm.request.execute',false,'{}'),
        ('FIXING','accept_residual_risk','CLOSED_WITH_ACCEPTED_RISK','asm.request.acceptrisk',true,'{}'),
        ('DRAFT','cancel','CANCELLED','asm.request.cancel',true,'{}'),
        ('SUBMITTED','cancel','CANCELLED','asm.request.cancel',true,'{}'),
        ('ACCEPTED','cancel','CANCELLED','asm.request.cancel',true,'{}'),
        ('SCHEDULED','cancel','CANCELLED','asm.request.cancel',true,'{}')
      ) AS v(from_code, event, to_code, permission, reason, guard)
      JOIN workflow_state f ON f.definition_id = def AND f.code = v.from_code
      JOIN workflow_state x ON x.definition_id = def AND x.code = v.to_code
    ON CONFLICT DO NOTHING;

    -- PRD-WRK-034: validated BEFORE activation. Activating an invalid definition is what the CHECK on
    -- validated_at makes unrepresentable, and this is the validation it refers to.
    IF EXISTS (SELECT 1 FROM workflow_definition_defects(def)) THEN
        RAISE EXCEPTION 'the seeded definition has defects: %',
            (SELECT string_agg(defect || ' ' || detail, '; ') FROM workflow_definition_defects(def));
    END IF;

    UPDATE workflow_definition SET validated_at = now(), activated_at = now(), state = 'ACTIVE'
     WHERE id = def;
    UPDATE assessment_type SET workflow_definition_id = def WHERE id = asm_type AND tenant_id = t;
END
$seed$;
