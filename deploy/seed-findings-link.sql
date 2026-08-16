-- =============================================================================
-- Links the demo findings to the assets they were found in, and adds a few closed ones.
--
-- WHY THIS IS SEPARATE. Until now the ten demo findings existed with no finding_asset_impact rows,
-- so every rollup reported zero — correctly. That is PP-1 working: the platform will not infer an
-- application's posture from findings nobody attached to it. It also made the dashboards useless,
-- which is the honest cost of the rule rather than a reason to weaken it.
--
-- The added closed findings are what make the counts mean something: an inventory that only ever
-- shows open findings cannot answer "is this getting better", and one that counts accepted risk as
-- open double-counts the figure an executive reads first.
-- =============================================================================
\set ON_ERROR_STOP on

DO $link$
DECLARE
    t uuid := '11111111-1111-1111-1111-111111111111';
    a_authz uuid; a_admin uuid; a_policy uuid; a_domain uuid; a_repo uuid;
    f_authz_feat uuid; f_refund uuid; f_recon uuid;
    sev_crit uuid; sev_high uuid; sev_med uuid; at_svc uuid;
    r record;
BEGIN
    PERFORM set_config('aspm.current_tenant', t::text, true);
    SELECT id INTO a_authz  FROM asset WHERE tenant_id=t AND display_name='payments-authorization';
    SELECT id INTO a_admin  FROM asset WHERE tenant_id=t AND display_name='payments-admin';
    SELECT id INTO a_policy FROM asset WHERE tenant_id=t AND display_name='policy-core';
    SELECT id INTO a_domain FROM asset WHERE tenant_id=t AND display_name='pay.example.com';
    SELECT id INTO a_repo   FROM asset WHERE tenant_id=t AND display_name='group/payments-api';
    SELECT id INTO f_authz_feat FROM asset WHERE tenant_id=t AND identity_key='card authorization';
    SELECT id INTO f_refund FROM asset WHERE tenant_id=t AND identity_key='refunds';
    SELECT id INTO f_recon  FROM asset WHERE tenant_id=t AND identity_key='reconciliation';
    SELECT id INTO sev_crit FROM severity_level WHERE code='CRITICAL';
    SELECT id INTO sev_high FROM severity_level WHERE code='HIGH';
    SELECT id INTO sev_med  FROM severity_level WHERE code='MEDIUM';

    -- Each existing finding attached where it was actually found. A SECRET finding belongs to the
    -- repository it was committed to; a TLS finding belongs to the listener, which is the domain.
    FOR r IN SELECT id, finding_class, title FROM finding WHERE tenant_id = t LOOP
        INSERT INTO finding_asset_impact (tenant_id, finding_id, asset_id, location_detail,
                                          first_detected_at, last_detected_at)
        SELECT t, r.id,
               CASE
                 WHEN r.finding_class = 'SECRET'         THEN a_repo
                 WHEN r.finding_class = 'INFRASTRUCTURE' THEN a_domain
                 WHEN r.title ILIKE '%settlement%'       THEN a_admin
                 WHEN r.title ILIKE '%admin%'            THEN a_admin
                 WHEN r.title ILIKE '%express%'          THEN a_admin
                 WHEN r.title ILIKE '%tenant%'           THEN a_policy
                 ELSE a_authz
               END,
               '{"note":"seeded demo location"}'::jsonb, now() - interval '30 days', now() - interval '2 days'
        WHERE NOT EXISTS (SELECT 1 FROM finding_asset_impact x
                           WHERE x.finding_id = r.id);
    END LOOP;

    -- Closed and accepted findings, so "open" is a subset of something rather than the whole story.
    INSERT INTO finding (tenant_id, fingerprint_digest, fingerprint_algorithm_version, finding_class,
                         title, description, effective_severity_id, state, closure_reason, closed_at,
                         closure_verified_by, closure_verification_method,
                         source_tool, raw_source_record_ref, first_detected_at, last_detected_at)
    VALUES
      (t, '\x91'::bytea, 1, 'DEPENDENCY', 'log4j remote code execution',
       'Vulnerable log4j-core on the authorization service.', sev_crit,
       'CLOSED', 'FIXED_VERIFIED', now() - interval '20 days',
       '70000000-0000-4000-8000-00000000000b', 'RETEST', 'demo-sca', 'seed://demo/sca',
       now() - interval '90 days', now() - interval '21 days'),
      (t, '\x92'::bytea, 1, 'DEPENDENCY', 'lodash prototype pollution',
       'Transitive lodash below the fixed version.', sev_med,
       'CLOSED', 'RISK_ACCEPTED', now() - interval '10 days', NULL, NULL, 'demo-sca', 'seed://demo/sca',
       now() - interval '60 days', now() - interval '11 days'),
      (t, '\x93'::bytea, 1, 'DEPENDENCY', 'spring-web open redirect',
       'spring-web below the patched version.', sev_high, 'OPEN', NULL, NULL, NULL, NULL, 'demo-sca', 'seed://demo/sca',
       now() - interval '14 days', now() - interval '1 day'),
      (t, '\x94'::bytea, 1, 'CODE', 'Missing output encoding in the refund note field',
       'Stored cross-site scripting in the refund reason.', sev_high, 'OPEN', NULL, NULL, NULL, NULL,
       'demo-sast', 'seed://demo/sast', now() - interval '7 days', now() - interval '1 day'),
      (t, '\x95'::bytea, 1, 'CODE', 'Broken object level authorization on refund lookup',
       'A refund can be read by any authenticated caller.', sev_crit, 'OPEN', NULL, NULL, NULL, NULL,
       'demo-pentest', 'seed://demo/pentest', now() - interval '5 days', now())
    ON CONFLICT DO NOTHING;

    INSERT INTO finding_asset_impact (tenant_id, finding_id, asset_id, location_detail,
                                      first_detected_at, last_detected_at)
    SELECT t, f.id,
           CASE WHEN f.title ILIKE '%refund%' THEN f_refund
                WHEN f.title ILIKE '%lodash%' THEN a_admin
                ELSE a_authz END,
           '{"note":"seeded demo location"}'::jsonb, f.first_detected_at, f.last_detected_at
      FROM finding f
     WHERE f.tenant_id = t AND f.fingerprint_digest IN
           ('\x91'::bytea,'\x92'::bytea,'\x93'::bytea,'\x94'::bytea,'\x95'::bytea)
       AND NOT EXISTS (SELECT 1 FROM finding_asset_impact x WHERE x.finding_id = f.id);

    RAISE NOTICE 'impacts: %, findings: %',
        (SELECT count(*) FROM finding_asset_impact WHERE tenant_id=t),
        (SELECT count(*) FROM finding WHERE tenant_id=t);
END
$link$;
