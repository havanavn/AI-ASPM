DO $s$
DECLARE
    t uuid := '11111111-1111-1111-1111-111111111111';
    dev uuid := '70000000-0000-4000-8000-00000000000c';
    pt  uuid := '70000000-0000-4000-8000-00000000000b';
    app_api uuid; app_portal uuid; ex uuid; r record; i int := 0;
    titles text[] := ARRAY[
      'Quarterly penetration test — Payments API',
      'Pre-release assessment — refund flow',
      'External attack surface review',
      'Red team exercise — payment operations',
      'Retest of Q2 critical findings',
      'Dependency review before PCI audit',
      'Configuration review — Kubernetes namespace',
      'Policy Core annual assessment'];
BEGIN
    PERFORM set_config('aspm.current_tenant', t::text, true);
    SELECT id INTO app_api FROM asset WHERE tenant_id=t AND identity_key='payments api';
    SELECT id INTO app_portal FROM asset WHERE tenant_id=t AND identity_key='payments portal';

    FOR r IN SELECT id, state FROM assessment_request WHERE tenant_id=t ORDER BY created_at LOOP
        i := i + 1;
        UPDATE assessment_request
           SET title = titles[least(i, array_length(titles,1))],
               due_at = now() + make_interval(days => (i * 9) - 20),
               requester_contact_id = dev
         WHERE id = r.id;
        -- The scope belongs to the ASSESSMENT (the run), not the request: a request may have an
        -- original assessment and a retest, each with its own scope. So an assessment is created for
        -- any request that has reached execution, and the scope hangs off it.
        IF r.state IN ('SCHEDULED','IN_PROGRESS','FIXING','CLOSED_PASSED') THEN
            SELECT id INTO ex FROM assessment WHERE request_id = r.id LIMIT 1;
            IF ex IS NULL THEN
                -- scope_node_id is NOT NULL: an assessment is always scoped, and it inherits the
                -- request's node rather than being derived from the assessor (INV-ASM-10).
                INSERT INTO assessment (tenant_id, type_id, request_id, state, lead_principal_id,
                                        started_at, scope_node_id, scope_ancestor_path,
                                        scope_node_type_id, scope_criticality_id, scope_hierarchy_ver, scope_resolved_at)
                SELECT t, ar.type_id, r.id, 'IN_PROGRESS', pt, now() - interval '5 days',
                       ar.requested_org_node_id, ar.scope_ancestor_path, ar.scope_node_type_id,
                       ar.scope_criticality_id, ar.scope_hierarchy_ver, now()
                  FROM assessment_request ar WHERE ar.id = r.id
                RETURNING id INTO ex;
            END IF;
            INSERT INTO assessment_scope_asset (tenant_id, assessment_id, asset_id)
            VALUES (t, ex, CASE WHEN i % 2 = 0 THEN app_portal ELSE app_api END)
            ON CONFLICT DO NOTHING;
        END IF;
    END LOOP;

    -- Attribute the manual findings to the first in-progress request, so one request has a result.
    UPDATE finding SET discovered_in_request_id =
        (SELECT id FROM assessment_request WHERE tenant_id=t AND state='IN_PROGRESS' LIMIT 1),
        assessment_context = 'INTERNAL_PENTEST'
     WHERE tenant_id=t AND finding_class IN ('MANUAL','CODE') AND discovered_in_request_id IS NULL;
    UPDATE finding SET assessment_context = 'AUTOMATED_SCAN'
     WHERE tenant_id=t AND assessment_context IS NULL
       AND finding_class IN ('DEPENDENCY','SECRET','INFRASTRUCTURE','RUNTIME','CONFIGURATION');

    RAISE NOTICE 'requests titled: %, findings attributed: %',
        (SELECT count(*) FROM assessment_request WHERE tenant_id=t AND title IS NOT NULL),
        (SELECT count(*) FROM finding WHERE tenant_id=t AND discovered_in_request_id IS NOT NULL);
END
$s$;
