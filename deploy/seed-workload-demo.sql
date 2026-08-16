-- =============================================================================
-- A year of realistic AppSec work, so the workload dashboard can be judged rather than admired.
--
-- WHY GENERATED DATA WITH A STORY, NOT RANDOM DATA
--
-- A dashboard evaluated against uniform random data always looks fine: every chart is flat, nothing
-- stands out, and there is nothing to notice. That proves the charts render, not that they inform.
--
-- So this generates a team with problems a manager should be able to FIND from the dashboard alone:
--
--   1. The backlog grows through H1 and turns over in Q3, when two people join. The "open findings
--      over time" chart should show the turn; no activity count will.
--   2. Fintech carries the most critical work and the fewest assessors. Per-team and per-org
--      coverage should show it.
--   3. Vinpearl and VinHomes have applications nobody has ever reviewed. Coverage should name them.
--   4. Due date attainment sags in the busy months and recovers. Roughly three quarters overall.
--   5. Six findings reached production through bug bounty or incident — the escape ratio is small
--      and non-zero, which is what a real programme looks like.
--   6. Nine fixes are claimed and awaiting retest, some for weeks. That queue is invisible in any
--      activity chart and is the delivery team waiting on us.
--
-- If the dashboard does not surface those six, it is not doing its job, and that is the point of
-- seeding them deliberately rather than randomly.
--
-- DETERMINISTIC, AND ONE-SHOT
--
-- setseed makes the estate reproducible, so two people looking at the demo discuss the same numbers.
--
-- *** IT CANNOT BE RE-RUN. *** The cleanup below deletes this script's own rows, and the transition
-- log refuses deletion: INV-WRK-03 makes it append-only, and the trigger says why — "an undo that
-- removes history makes cycle-time analysis wrong and conceals rework, which is itself a signal".
-- That is the platform being right and this header being wrong on the first attempt; it is recorded
-- rather than worked around, because disabling that trigger to make a demo convenient is exactly the
-- habit the invariant exists to prevent. To start over, drop the database volume and re-migrate.
--
-- REMOVING IT
--
--   DELETE FROM finding WHERE raw_source_record_ref LIKE 'demo-workload:%';
--   -- then the requests, org nodes and principals tagged 'demo-workload' below.
-- =============================================================================

\set ON_ERROR_STOP on

DO $seed$
DECLARE
    t             uuid := '11111111-1111-1111-1111-111111111111';
    nt_division   uuid;
    nt_product    uuid;
    nt_project    uuid;
    at_app        uuid;
    at_project    uuid;
    at_service    uuid;
    ty_pentest    uuid;
    tier1         uuid;
    tier2         uuid;
    tier3         uuid;
    sev           uuid[];
    trig_full     uuid;
    trig_change   uuid;
    trig_adhoc    uuid;
    trig_golive   uuid;
    org           record;
    team_ids      uuid[] := '{}';
    people        uuid[] := '{}';
    p_id          uuid;
    org_id        uuid;
    prod_id       uuid;
    app_id        uuid;
    proj_id       uuid;
    req_id        uuid;
    asset_ids     uuid[] := '{}';
    unreviewed    uuid[] := '{}';
    n             int;
    i             int;
    j             int;
    k             int;
    seq           int;
    raised        timestamptz;
    closed        timestamptz;
    due           date;
    assessor      uuid;
    trig          uuid;
    is_closed     boolean;
    sev_idx       int;
    ctx           text;
    fix_verified  boolean;
    detected      timestamptz;
    monthly_load  int;
    code_n        int := 1000;
BEGIN
    PERFORM set_config('aspm.current_tenant', t::text, true);
    PERFORM setseed(0.42);

    -- ---------------------------------------------------------------------------------------------
    -- Clear anything a previous run of THIS script created. Matched on the demo marker, so hand-made
    -- data and the original seeds survive.
    -- ---------------------------------------------------------------------------------------------
    DELETE FROM finding_asset_impact WHERE finding_id IN
        (SELECT id FROM finding WHERE raw_source_record_ref LIKE 'demo-workload:%');
    DELETE FROM finding WHERE raw_source_record_ref LIKE 'demo-workload:%';
    DELETE FROM assessment_request_transition WHERE request_id IN
        (SELECT id FROM assessment_request WHERE technical_profile->>'seed' = 'demo-workload');
    DELETE FROM assessment_request_scope_asset WHERE request_id IN
        (SELECT id FROM assessment_request WHERE technical_profile->>'seed' = 'demo-workload');
    DELETE FROM assessment_request_role_account WHERE request_id IN
        (SELECT id FROM assessment_request WHERE technical_profile->>'seed' = 'demo-workload');
    DELETE FROM assessment_request WHERE technical_profile->>'seed' = 'demo-workload';
    DELETE FROM assessor_team_member WHERE principal_id IN
        (SELECT id FROM principal WHERE username LIKE 'as%.demo');
    DELETE FROM assessor_team WHERE description = 'demo-workload';
    DELETE FROM role_assignment WHERE principal_id IN
        (SELECT id FROM principal WHERE username LIKE 'as%.demo');
    DELETE FROM principal WHERE username LIKE 'as%.demo';

    SELECT id INTO nt_division FROM org_node_type WHERE tenant_id = t AND code = 'DIVISION';
    SELECT id INTO nt_product  FROM org_node_type WHERE tenant_id = t AND code = 'PRODUCT';
    SELECT id INTO nt_project  FROM org_node_type WHERE tenant_id = t AND code = 'PROJECT';
    SELECT id INTO at_app     FROM asset_type WHERE tenant_id = t AND code = 'APPLICATION';
    SELECT id INTO at_project FROM asset_type WHERE tenant_id = t AND code = 'PROJECT';
    SELECT id INTO at_service FROM asset_type WHERE tenant_id = t AND code = 'SERVICE';
    SELECT id INTO ty_pentest FROM assessment_type WHERE tenant_id = t LIMIT 1;
    SELECT array_agg(id ORDER BY ordinal) INTO sev FROM severity_level WHERE tenant_id = t;
    SELECT id INTO tier1 FROM criticality_tier WHERE tenant_id = t ORDER BY ordinal LIMIT 1;
    SELECT id INTO tier2 FROM criticality_tier WHERE tenant_id = t ORDER BY ordinal OFFSET 1 LIMIT 1;
    SELECT id INTO tier3 FROM criticality_tier WHERE tenant_id = t ORDER BY ordinal OFFSET 2 LIMIT 1;
    IF tier3 IS NULL THEN tier3 := tier2; END IF;
    SELECT id INTO trig_full   FROM assessment_trigger WHERE tenant_id = t AND counts_as_full_review;
    SELECT id INTO trig_change FROM assessment_trigger WHERE tenant_id = t AND code = 'CHANGE_REQUEST';
    SELECT id INTO trig_adhoc  FROM assessment_trigger WHERE tenant_id = t AND code = 'AD_HOC';
    SELECT id INTO trig_golive FROM assessment_trigger WHERE tenant_id = t AND code = 'NEW_GOLIVE';

    -- ---------------------------------------------------------------------------------------------
    -- The operating companies, each with a product line, a delivery team, an application, a project
    -- and a service. Criticality differs on purpose so the estate is not uniform.
    -- ---------------------------------------------------------------------------------------------
    FOR org IN
        SELECT * FROM (VALUES
            ('GSM',      'Mobility',     'Driver Platform',    'Trip pricing',        1, 3),
            ('Fintech',  'Payments Core','Card Issuing',       'Authorization',       1, 5),
            ('Fintech',  'Payments Core','Merchant Gateway',   'Settlement',          1, 4),
            ('Vinpearl', 'Hospitality',  'Booking Engine',     'Reservations',        2, 2),
            ('VinHomes', 'Property',     'Resident Portal',    'Access control',      2, 2),
            ('Vinmec',   'Healthcare',   'Patient Records',    'Clinical records',    1, 4)
        ) AS v(company, product, application, project, tier, weight)
    LOOP
        -- The company. VinFast, Vinmec, GSM and VinHomes already exist from the naming seed.
        SELECT id INTO org_id FROM org_node
         WHERE tenant_id = t AND name = org.company AND parent_id IS NULL;
        IF org_id IS NULL THEN
            INSERT INTO org_node (tenant_id, type_id, parent_id, name, criticality_mode,
                                  criticality_tier_id, criticality_justification)
            VALUES (t, nt_division, NULL, org.company, 'ASSIGNED', tier2, 'demo-workload')
            RETURNING id INTO org_id;
            -- INV-ORG-13: a node without its depth-zero closure row is a node "the subtree of X"
            -- excludes, and every scope query over it is then subtly wrong.
            INSERT INTO org_closure (tenant_id, ancestor_id, descendant_id, depth, hierarchy_version)
            VALUES (t, org_id, org_id, 0,
                    (SELECT coalesce(max(hierarchy_version), 0) + 1 FROM org_closure));
        END IF;

        SELECT id INTO prod_id FROM org_node
         WHERE tenant_id = t AND name = org.product AND parent_id = org_id;
        IF prod_id IS NULL THEN
            INSERT INTO org_node (tenant_id, type_id, parent_id, name, criticality_mode)
            VALUES (t, nt_product, org_id, org.product, 'INHERITED')
            RETURNING id INTO prod_id;
            -- Self, plus every ancestor of the parent. A partial closure is worse than none: the
            -- scope query silently returns a subset and nobody sees a failure.
            INSERT INTO org_closure (tenant_id, ancestor_id, descendant_id, depth, hierarchy_version)
            SELECT t, c.ancestor_id, prod_id, c.depth + 1,
                   (SELECT coalesce(max(hierarchy_version), 0) + 1 FROM org_closure)
              FROM org_closure c WHERE c.descendant_id = org_id
            UNION ALL SELECT t, prod_id, prod_id, 0,
                   (SELECT coalesce(max(hierarchy_version), 0) + 1 FROM org_closure);
        END IF;

        -- The application, and one project under it.
        INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, display_name,
                           owning_node_id, criticality_mode, criticality_tier_id,
                           exposure_declared, exposure_declared_at, lifecycle_state,
                           discovery_source, discovery_method, first_seen_at, last_confirmed_at)
        VALUES (t, at_app, lower(org.application), 1, org.application, prod_id, 'ASSIGNED',
                CASE org.tier WHEN 1 THEN tier1 WHEN 2 THEN tier2 ELSE tier3 END,
                CASE WHEN org.tier = 1 THEN 'INTERNET_PUBLIC' ELSE 'INTERNAL_ONLY' END,
                now(), 'ACTIVE', 'MANUAL', 'INVENTORY_FORM', now() - interval '400 days', now())
        ON CONFLICT DO NOTHING
        RETURNING id INTO app_id;
        IF app_id IS NULL THEN
            SELECT id INTO app_id FROM asset
             WHERE tenant_id = t AND identity_key = lower(org.application);
        END IF;

        INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, display_name,
                           owning_node_id, criticality_mode, criticality_tier_id,
                           exposure_declared, exposure_declared_at, lifecycle_state,
                           discovery_source, discovery_method, first_seen_at, last_confirmed_at)
        VALUES (t, at_project, lower(org.project), 1, org.project, prod_id, 'ASSIGNED', tier2,
                'INTERNAL_ONLY', now(), 'ACTIVE', 'MANUAL', 'INVENTORY_FORM',
                now() - interval '400 days', now())
        ON CONFLICT DO NOTHING
        RETURNING id INTO proj_id;
        IF proj_id IS NULL THEN
            SELECT id INTO proj_id FROM asset
             WHERE tenant_id = t AND identity_key = lower(org.project);
        END IF;

        INSERT INTO asset_relationship (tenant_id, from_asset_id, to_asset_id, edge_type,
                                        discovery_source, attributes, valid_from)
        SELECT t, app_id, proj_id, 'CONTAINS', 'MANUAL',
               '{"created_by_seed":"demo-workload"}'::jsonb, now()
         WHERE NOT EXISTS (SELECT 1 FROM asset_relationship r
                            WHERE r.from_asset_id = app_id AND r.to_asset_id = proj_id
                              AND r.valid_until IS NULL);

        -- Vinpearl and VinHomes get a second application nobody has ever reviewed. The coverage
        -- table exists to surface exactly this, so the demo has to contain it.
        IF org.company IN ('Vinpearl', 'VinHomes') THEN
            INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, display_name,
                               owning_node_id, criticality_mode, criticality_tier_id,
                               exposure_declared, exposure_declared_at, lifecycle_state,
                               discovery_source, discovery_method, first_seen_at, last_confirmed_at)
            VALUES (t, at_app, lower(org.company || ' legacy portal'), 1,
                    org.company || ' Legacy Portal', prod_id, 'ASSIGNED', tier2,
                    'INTERNET_PUBLIC', now(), 'ACTIVE', 'MANUAL', 'INVENTORY_FORM',
                    now() - interval '700 days', now())
            ON CONFLICT DO NOTHING;
        END IF;

        asset_ids := asset_ids || app_id || proj_id;
        -- weight decides how much work this company generates, so the per-org table is not flat.
        FOR k IN 1..org.weight LOOP
            unreviewed := unreviewed || app_id;
        END LOOP;
    END LOOP;

    -- ---------------------------------------------------------------------------------------------
    -- Seventeen assessors in four teams. Sizes are uneven on purpose: a chart of four equal bars
    -- tells nobody anything, and a real team is never balanced.
    -- ---------------------------------------------------------------------------------------------
    INSERT INTO assessor_team (tenant_id, name, description)
    SELECT t, v.name, 'demo-workload' FROM (VALUES
        ('Web & API'), ('Mobile & Device'), ('Infrastructure'), ('Red Team')
    ) AS v(name)
    ON CONFLICT DO NOTHING;
    -- Ordered by the size each team is about to be given, not alphabetically. Ordering by name put
    -- six people on "Web & API" and two on "Infrastructure", which is backwards for an AppSec team
    -- and made the per-team chart tell a story about the alphabet.
    SELECT array_agg(id ORDER BY array_position(
               ARRAY['Web & API','Mobile & Device','Infrastructure','Red Team'], name))
      INTO team_ids
      FROM assessor_team WHERE tenant_id = t AND description = 'demo-workload';

    FOR i IN 1..17 LOOP
        INSERT INTO principal (tenant_id, kind, username, display_name, lifecycle_state)
        VALUES (t, 'HUMAN', 'as' || lpad(i::text, 2, '0') || '.demo',
                (ARRAY['Nguyen','Tran','Le','Pham','Hoang','Vu','Dang','Bui','Do','Ho',
                       'Ngo','Duong','Ly','Phan','Vo','Dinh','Truong'])[i]
                || ' ' || (ARRAY['Minh','Anh','Khoa','Linh','Huy','Trang','Nam','Thao','Quan','Mai',
                                 'Duc','Ha','Long','Chi','Son','Yen','Tuan'])[i],
                'ACTIVE')
        RETURNING id INTO p_id;
        people := people || p_id;
        -- Team sizes 6 / 5 / 4 / 2.
        INSERT INTO assessor_team_member (tenant_id, team_id, principal_id)
        VALUES (t, team_ids[CASE WHEN i <= 6 THEN 1 WHEN i <= 11 THEN 2
                                 WHEN i <= 15 THEN 3 ELSE 4 END], p_id);
    END LOOP;

    -- ---------------------------------------------------------------------------------------------
    -- A year of requests. Load rises through H1, peaks in month 7, then eases as capacity arrives —
    -- the shape the backlog chart exists to make visible.
    -- ---------------------------------------------------------------------------------------------
    FOR n IN 0..11 LOOP
        monthly_load := (ARRAY[10, 12, 14, 16, 19, 22, 26, 24, 20, 18, 16, 12])[n + 1];
        FOR j IN 1..monthly_load LOOP
            code_n := code_n + 1;
            raised := date_trunc('month', now()) - make_interval(months => 11 - n)
                      + (random() * 27)::int * interval '1 day'
                      + (random() * 8 + 8)::int * interval '1 hour';
            IF raised > now() THEN CONTINUE; END IF;

            assessor := people[1 + floor(random() * 17)::int];
            proj_id := asset_ids[1 + floor(random() * array_length(asset_ids, 1))::int];
            SELECT owning_node_id INTO org_id FROM asset WHERE id = proj_id;

            trig := CASE WHEN random() < 0.18 THEN trig_full
                         WHEN random() < 0.55 THEN trig_change
                         WHEN random() < 0.75 THEN trig_golive
                         ELSE trig_adhoc END;

            -- Attainment sags in the busy months. Modelled rather than random so the chart has a
            -- shape a reader can attribute to something.
            due := (raised + interval '21 days')::date;
            is_closed := random() < CASE WHEN n >= 10 THEN 0.45 ELSE 0.88 END;
            closed := NULL;
            IF is_closed THEN
                closed := raised + (CASE WHEN n BETWEEN 5 AND 7 THEN 14 ELSE 6 END
                                    + random() * 18) * interval '1 day';
                IF closed > now() THEN closed := now() - interval '2 hours'; END IF;
            END IF;

            INSERT INTO assessment_request (tenant_id, request_code, type_id,
                    requested_org_node_id, state, title, technical_profile, requested_by,
                    submitted_at, created_at, due_at, trigger_id,
                    scope_node_id, scope_ancestor_path, scope_node_type_id, scope_criticality_id,
                    scope_hierarchy_ver, scope_resolved_at)
            SELECT t, 'REQ-DEMO-' || code_n, ty_pentest, org_id,
                   CASE WHEN is_closed THEN 'CLOSED' ELSE
                        (ARRAY['OPEN','IN_PROGRESS','IN_PROGRESS','FIXING','RETEST'])[1 + floor(random() * 5)::int]
                   END,
                   (ARRAY['Penetration test','Change review','Pre-go-live review','Security assessment',
                          'API review'])[1 + floor(random() * 5)::int] || ' — ' || a.display_name,
                   jsonb_build_object('seed', 'demo-workload'),
                   assessor, raised, raised, due, trig,
                   org_id,
                   (SELECT array_agg(cl.ancestor_id ORDER BY cl.depth DESC)
                      FROM org_closure cl WHERE cl.descendant_id = org_id),
                   n2.type_id, coalesce(n2.criticality_tier_id, tier2),
                   (SELECT max(hierarchy_version) FROM org_closure), raised
              FROM asset a JOIN org_node n2 ON n2.id = org_id
             WHERE a.id = proj_id
            RETURNING id INTO req_id;

            INSERT INTO assessment_request_scope_asset (tenant_id, request_id, asset_id,
                                                        named_by_requester)
            VALUES (t, req_id, proj_id, true) ON CONFLICT DO NOTHING;

            -- The transition log. closed_at on the board is derived from the transition INTO a
            -- completion state, so a request without one reads as open however its state column
            -- looks — the view is the source of truth and the seed has to satisfy it.
            seq := 1;
            INSERT INTO assessment_request_transition (tenant_id, request_id, sequence_number,
                    from_state, to_state, event_code, actor_type, actor_principal_id,
                    occurred_at, sla_clock_running)
            VALUES (t, req_id, seq, NULL, 'OPEN', 'raise', 'HUMAN', assessor, raised, true);
            IF is_closed THEN
                seq := seq + 1;
                INSERT INTO assessment_request_transition (tenant_id, request_id, sequence_number,
                        from_state, to_state, event_code, actor_type, actor_principal_id,
                        occurred_at, prior_state_duration, sla_clock_running)
                VALUES (t, req_id, seq, 'OPEN', 'CLOSED', 'close', 'HUMAN', assessor,
                        closed, closed - raised, false);
            END IF;

            -- The assessment record, which is where the board reads the assessor from.
            INSERT INTO assessment (tenant_id, type_id, request_id, lead_principal_id, state,
                                    started_at, scope_node_id, scope_ancestor_path,
                                    scope_node_type_id, scope_criticality_id, scope_hierarchy_ver,
                                    scope_resolved_at)
            SELECT t, ty_pentest, req_id, assessor,
                   CASE WHEN is_closed THEN 'COMPLETED' ELSE 'IN_PROGRESS' END,
                   raised, r.scope_node_id, r.scope_ancestor_path, r.scope_node_type_id,
                   r.scope_criticality_id, r.scope_hierarchy_ver, r.scope_resolved_at
              FROM assessment_request r WHERE r.id = req_id;

            -- Findings. Serious ones are rarer, as they are in life; a uniform severity split makes
            -- the severity chart meaningless.
            FOR k IN 1..(1 + floor(random() * 5))::int LOOP
                sev_idx := CASE WHEN random() < 0.08 THEN 1
                                WHEN random() < 0.28 THEN 2
                                WHEN random() < 0.62 THEN 3
                                ELSE 4 END;
                IF sev_idx > array_length(sev, 1) THEN sev_idx := array_length(sev, 1); END IF;
                detected := raised + (random() * 5) * interval '1 day';
                -- Decided ONCE. The first version asked random() separately for the state, the
                -- closure reason and the closed timestamp, so a finding could come out open with a
                -- verified closure — three answers to one question is how a seed writes a row the
                -- schema is right to refuse.
                fix_verified := is_closed AND random() < 0.72;

                -- Six escapes across the year: found by bug bounty or incident, which only happens
                -- after release. Everything else was caught in a planned assessment.
                ctx := CASE WHEN random() < 0.025 AND sev_idx <= 2
                            THEN (ARRAY['BUG_BOUNTY','INCIDENT'])[1 + floor(random() * 2)::int]
                            ELSE (ARRAY['INTERNAL_PENTEST','EXTERNAL_PENTEST','AUTOMATED_SCAN'])
                                 [1 + floor(random() * 3)::int] END;

                INSERT INTO finding (tenant_id, fingerprint_digest, fingerprint_algorithm_version,
                        finding_class, title, reported_severity_id, effective_severity_id, state,
                        closure_reason, closed_at,
                        closure_verified_by, closure_verification_method,
                        source_tool, raw_source_record_ref, first_detected_at, last_detected_at, created_at, created_by,
                        assessment_context, discovered_in_request_id, scope_node_id,
                        scope_ancestor_path, scope_node_type_id, scope_criticality_id,
                        scope_hierarchy_ver, scope_resolved_at,
                        remediation_claimed_at, remediation_claimed_by, remediation_note)
                -- No pgcrypto in this deployment, and a fingerprint only has to be unique and stable
                       -- here: sha256 via the built-in digest is unavailable, so the
                       -- deterministic bytes of the identifier pair serve the same purpose.
                       SELECT t, decode(md5(req_id::text || k::text || 'demo'), 'hex'), 1,
                       (ARRAY['CODE','RUNTIME','DEPENDENCY','MANUAL','CONFIGURATION'])
                              [1 + floor(random() * 5)::int],
                       (ARRAY['Broken object level authorization','Reflected cross-site scripting',
                              'SQL injection','Missing rate limit','Insecure direct object reference',
                              'Sensitive data in logs','Weak session expiry','Server-side request forgery',
                              'Hardcoded credential','Outdated dependency with known CVE'])
                              [1 + floor(random() * 10)::int] || ' in ' || a.display_name,
                       sev[sev_idx], sev[sev_idx],
                       -- Closed only if the request closed, and not all of them: an engagement that
                       -- closes rarely leaves every finding fixed.
                       CASE WHEN fix_verified THEN 'CLOSED' ELSE 'OPEN' END,
                       CASE WHEN fix_verified THEN 'FIXED_VERIFIED' ELSE NULL::text END,
                       CASE WHEN fix_verified THEN closed ELSE NULL::timestamptz END,
                       -- A verified closure needs a verifier, and ck_finding__verified_closure is
                       -- right to insist. Deliberately NOT the person who found it: the separation
                       -- between finding and verifying is the whole point of the closure reason.
                       CASE WHEN fix_verified
                            THEN people[1 + ((j + k) % 17)] ELSE NULL::uuid END,
                       CASE WHEN fix_verified THEN 'RETEST' ELSE NULL::text END,
                       (ARRAY['semgrep','trivy','burp','manual-pentest','gitleaks'])
                              [1 + floor(random() * 5)::int],
                       'demo-workload:' || req_id || ':' || k,
                       detected, detected, detected, assessor, ctx, req_id,
                       r.scope_node_id, r.scope_ancestor_path, r.scope_node_type_id,
                       r.scope_criticality_id, r.scope_hierarchy_ver, r.scope_resolved_at,
                       NULL, NULL, NULL
                  FROM assessment_request r JOIN asset a ON a.id = proj_id
                 WHERE r.id = req_id;
            END LOOP;
        END LOOP;
    END LOOP;

    -- ---------------------------------------------------------------------------------------------
    -- Nine fixes claimed and awaiting retest, some of them for weeks. This queue is invisible in
    -- every activity chart and is the delivery team waiting on the security team (PP-6).
    -- ---------------------------------------------------------------------------------------------
    UPDATE finding f
       SET remediation_claimed_at = now() - (q.rn * 4) * interval '1 day',
           remediation_claimed_by = people[1 + (q.rn % 17)],
           remediation_note = 'Patched and deployed; ready for retest'
      FROM (SELECT id, row_number() OVER (ORDER BY first_detected_at) AS rn
              FROM finding
             WHERE state = 'OPEN' AND raw_source_record_ref LIKE 'demo-workload:%'
             ORDER BY first_detected_at LIMIT 9) q
     WHERE f.id = q.id;

    RAISE NOTICE 'demo: % assessors in % teams, % requests, % findings (% open)',
        (SELECT count(*) FROM principal WHERE username LIKE 'as%.demo'),
        (SELECT count(*) FROM assessor_team WHERE description = 'demo-workload'),
        (SELECT count(*) FROM assessment_request WHERE technical_profile->>'seed' = 'demo-workload'),
        (SELECT count(*) FROM finding WHERE raw_source_record_ref LIKE 'demo-workload:%'),
        (SELECT count(*) FROM finding WHERE raw_source_record_ref LIKE 'demo-workload:%'
                                        AND state = 'OPEN');
END
$seed$;
