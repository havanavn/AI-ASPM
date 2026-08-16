-- =============================================================================
-- Demonstration data. Idempotent: safe to run repeatedly.
--
-- WHAT THIS DOES NOT DO. It does not write coverage figures, quality scores, priority scores or
-- transition history directly. Those are produced by the real write paths — the SBOM push API, the
-- transition endpoint, the estimation columns — because a dashboard whose numbers were inserted by a
-- seed script is the failure the honesty surfaces exist to prevent, and it would be
-- indistinguishable from a working one to anyone evaluating the product.
--
-- What it seeds is the RECORD: the organization, the configuration vocabulary, the assets, the
-- findings, the requests and their accounts and environments. The measurements come from running.
--
-- VOCABULARY IS DATA. "Business Unit", "Product", "Project" appear here as tenant-configured node
-- type codes and nowhere in the code (ADR-027, PRD-UIX-009). A different customer seeds different
-- ones and the interface follows.
-- =============================================================================
\set ON_ERROR_STOP on

DO $seed$
DECLARE
    t          uuid := '11111111-1111-1111-1111-111111111111';
    -- Node types, three levels deep. Depth is not assumed anywhere.
    nt_bu      uuid := '10000000-0000-4000-8000-000000000001';
    nt_prod    uuid := '10000000-0000-4000-8000-000000000002';
    nt_proj    uuid := '10000000-0000-4000-8000-000000000003';
    -- Criticality tiers.
    ct1        uuid := 'dddddddd-0000-4000-8000-00000000000d';
    ct2        uuid := '20000000-0000-4000-8000-000000000002';
    ct3        uuid := '20000000-0000-4000-8000-000000000003';
    -- The tree.
    n_root     uuid := 'aaaaaaaa-1111-4000-8000-00000000000a';   -- already exists
    n_bank     uuid := '30000000-0000-4000-8000-000000000001';
    n_pay      uuid := '30000000-0000-4000-8000-000000000002';
    n_pay_api  uuid := '30000000-0000-4000-8000-000000000003';
    n_pay_web  uuid := '30000000-0000-4000-8000-000000000004';
    n_ins      uuid := '30000000-0000-4000-8000-000000000005';
    n_ins_core uuid := '30000000-0000-4000-8000-000000000006';
    at_service uuid;
    at_repo    uuid := '40000000-0000-4000-8000-000000000002';
    at_domain  uuid := '40000000-0000-4000-8000-000000000003';
    sev_crit   uuid := '50000000-0000-4000-8000-000000000001';
    sev_high   uuid := '50000000-0000-4000-8000-000000000002';
    sev_med    uuid := '50000000-0000-4000-8000-000000000003';
    sev_low    uuid := '50000000-0000-4000-8000-000000000004';
    asm_type   uuid := 'cccccccc-0000-4000-8000-000000000001';
    principal  uuid := '33333333-3333-3333-3333-333333333333';
    engineer_a uuid := '60000000-0000-4000-8000-00000000000a';
    engineer_b uuid := '60000000-0000-4000-8000-00000000000b';
    a          record;
    r          record;
    hv         bigint;
BEGIN
    PERFORM set_config('aspm.current_tenant', t::text, true);

    -- ---- Node types. Tenant vocabulary, three levels, permitted parents declared. ----
    INSERT INTO org_node_type (id, tenant_id, code, label_i18n, ordinal, may_own_assets, may_scope_work)
    VALUES (nt_bu,   t, 'BUSINESS_UNIT', '{"en":"Business Unit","vi":"Đơn vị kinh doanh"}', 1, false, true),
           (nt_prod, t, 'PRODUCT',       '{"en":"Product","vi":"Sản phẩm"}',                2, false, true),
           (nt_proj, t, 'PROJECT',       '{"en":"Project","vi":"Dự án"}',                   3, true,  true)
    ON CONFLICT DO NOTHING;

    UPDATE org_node_type SET permitted_parent_type_ids = ARRAY[nt_bu]::uuid[]
     WHERE id = nt_prod AND tenant_id = t;
    UPDATE org_node_type SET permitted_parent_type_ids = ARRAY[nt_prod]::uuid[]
     WHERE id = nt_proj AND tenant_id = t;

    -- ---- Criticality tiers. ----
    INSERT INTO criticality_tier (id, tenant_id, code, label_i18n, ordinal)
    VALUES (ct1, t, 'TIER1', '{"en":"Tier 1 — revenue critical","vi":"Bậc 1 — trọng yếu doanh thu"}', 1),
           (ct2, t, 'TIER2', '{"en":"Tier 2 — business important","vi":"Bậc 2 — quan trọng"}',        2),
           (ct3, t, 'TIER3', '{"en":"Tier 3 — supporting","vi":"Bậc 3 — hỗ trợ"}',                   3)
    ON CONFLICT DO NOTHING;

    -- ---- The tree, under the existing root the demo principal is scoped to. ----
    INSERT INTO org_node (id, tenant_id, type_id, parent_id, name, criticality_mode, criticality_tier_id,
                          criticality_justification, criticality_assigned_by, criticality_assigned_at,
                          external_reference, tags)
    VALUES (n_bank,     t, nt_bu,   n_root, 'Banking',            'ASSIGNED', ct1,
            'Handles the group''s payment rails; an outage is a revenue outage.', principal, now(),
            'BU-BANK', ARRAY['regulated','pci']),
           (n_pay,      t, nt_prod, n_bank, 'Payments',           'ASSIGNED', ct1,
            'Card and transfer authorization for every channel.', principal, now(),
            'PRD-PAY', ARRAY['pci']),
           (n_pay_api,  t, nt_proj, n_pay,  'Payments API',       'INHERITED', NULL, NULL, NULL, NULL,
            'PRJ-PAY-API', ARRAY['internet-facing']),
           (n_pay_web,  t, nt_proj, n_pay,  'Payments Portal',    'INHERITED', NULL, NULL, NULL, NULL,
            'PRJ-PAY-WEB', ARRAY['internet-facing']),
           (n_ins,      t, nt_prod, n_bank, 'Insurance',          'ASSIGNED', ct2,
            'Policy administration; important but not on the payment path.', principal, now(),
            'PRD-INS', ARRAY[]::text[]),
           (n_ins_core, t, nt_proj, n_ins,  'Policy Core',        'INHERITED', NULL, NULL, NULL, NULL,
            'PRJ-INS-CORE', ARRAY['internal'])
    ON CONFLICT DO NOTHING;

    -- Closure rows. Self, and every ancestor path. The platform's own maintenance job would do this;
    -- here it is written explicitly so the demo scope resolves.
    INSERT INTO org_closure (tenant_id, ancestor_id, descendant_id, depth, hierarchy_version)
    VALUES (t, n_bank, n_bank, 0, 1), (t, n_pay, n_pay, 0, 1), (t, n_pay_api, n_pay_api, 0, 1),
           (t, n_pay_web, n_pay_web, 0, 1), (t, n_ins, n_ins, 0, 1), (t, n_ins_core, n_ins_core, 0, 1),
           (t, n_root, n_bank, 1, 1), (t, n_root, n_pay, 2, 1), (t, n_root, n_pay_api, 3, 1),
           (t, n_root, n_pay_web, 3, 1), (t, n_root, n_ins, 2, 1), (t, n_root, n_ins_core, 3, 1),
           (t, n_bank, n_pay, 1, 1), (t, n_bank, n_pay_api, 2, 1), (t, n_bank, n_pay_web, 2, 1),
           (t, n_bank, n_ins, 1, 1), (t, n_bank, n_ins_core, 2, 1),
           (t, n_pay, n_pay_api, 1, 1), (t, n_pay, n_pay_web, 1, 1),
           (t, n_ins, n_ins_core, 1, 1)
    ON CONFLICT DO NOTHING;

    SELECT max(hierarchy_version) INTO hv FROM org_closure;

    -- ---- Asset types. ----
    SELECT id INTO at_service FROM asset_type WHERE code = 'SERVICE' AND tenant_id = t;
    INSERT INTO asset_type (id, tenant_id, code, label_i18n, ordinal, identity_rule,
                            is_network_reachable, may_carry_findings)
    VALUES (at_repo,   t, 'REPOSITORY', '{"en":"Git repository","vi":"Repository"}', 2,
            '{"version":1,"natural_key_attributes":["display_name"]}', false, true),
           (at_domain, t, 'DOMAIN',     '{"en":"Domain","vi":"Tên miền"}',           3,
            '{"version":1,"natural_key_attributes":["display_name"]}', true,  true)
    ON CONFLICT DO NOTHING;

    -- ---- Severity scale. Tenant-configured, five levels, ordinal decides rank. ----
    INSERT INTO severity_level (id, tenant_id, code, label_i18n, ordinal)
    VALUES (sev_crit, t, 'CRITICAL', '{"en":"Critical","vi":"Nghiêm trọng"}', 1),
           (sev_high, t, 'HIGH',     '{"en":"High","vi":"Cao"}',              2),
           (sev_med,  t, 'MEDIUM',   '{"en":"Medium","vi":"Trung bình"}',     3),
           (sev_low,  t, 'LOW',      '{"en":"Low","vi":"Thấp"}',              4)
    ON CONFLICT DO NOTHING;

    -- ---- Assets. Every field the interface exposes, populated. ----
    INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, display_name,
                       owning_node_id, criticality_mode, criticality_tier_id, exposure_declared,
                       exposure_declared_by, exposure_declared_at, exposure_observed,
                       exposure_observed_source, exposure_observed_at, exposure_conflict,
                       lifecycle_state, tags, technical_contact_id, discovery_source, discovery_method,
                       first_seen_at, last_confirmed_at, scope_node_id, scope_ancestor_path,
                       scope_node_type_id, scope_criticality_id, scope_hierarchy_ver, scope_resolved_at)
    VALUES
      (t, at_service, 'payments-authorization', 1, 'payments-authorization', n_pay_api, 'ASSIGNED', ct1,
       'INTERNET_PUBLIC', principal, now(), 'INTERNET_PUBLIC', 'external-scan', now(), false,
       'ACTIVE', ARRAY['pci','tier1'], engineer_a, 'MANUAL', 'ONBOARDING',
       now() - interval '180 days', now(), n_pay_api, ARRAY[n_root,n_bank,n_pay,n_pay_api]::uuid[],
       nt_proj, ct1, hv, now()),
      (t, at_domain, 'pay.example.com', 1, 'pay.example.com', n_pay_api, 'INHERITED', NULL,
       'INTERNET_PUBLIC', principal, now(), 'INTERNET_PUBLIC', 'external-scan', now(), false,
       'ACTIVE', ARRAY['internet-facing'], engineer_a, 'CONNECTOR', 'DNS_ENUMERATION',
       now() - interval '90 days', now(), n_pay_api, ARRAY[n_root,n_bank,n_pay,n_pay_api]::uuid[],
       nt_proj, ct1, hv, now()),
      -- An exposure CONFLICT: declared internal, observed internet-facing. INV-AST-08 makes this a
      -- conflict rather than silently correcting the declaration, and the interface shows it.
      -- Declared INTERNAL_ONLY (rank 2), observed INTERNET_PUBLIC (rank 0). Observed is MORE exposed
      -- than declared, which is the UNDER-declaration the trigger treats as a conflict — and the
      -- trigger computes the flag, so the value below is overwritten rather than trusted. Passing it
      -- is only to satisfy NOT NULL.
      (t, at_service, 'payments-admin', 1, 'payments-admin', n_pay_web, 'ASSIGNED', ct1,
       'INTERNAL_ONLY', principal, now() - interval '30 days', 'INTERNET_PUBLIC', 'external-scan', now(),
       false, 'ACTIVE', ARRAY['admin'], engineer_b, 'MANUAL', 'ONBOARDING',
       now() - interval '120 days', now(), n_pay_web, ARRAY[n_root,n_bank,n_pay,n_pay_web]::uuid[],
       nt_proj, ct1, hv, now()),
      (t, at_repo, 'group/payments-api', 1, 'group/payments-api', n_pay_api, 'INHERITED', NULL,
       'INTERNAL_ONLY', principal, now(), NULL, NULL, NULL, false,
       'ACTIVE', ARRAY['java'], engineer_a, 'MANUAL', 'ONBOARDING',
       now() - interval '200 days', now(), n_pay_api, ARRAY[n_root,n_bank,n_pay,n_pay_api]::uuid[],
       nt_proj, ct1, hv, now()),
      -- An UNOWNED asset. PRD-AST-011's unclaimed queue exists for exactly this, and the dashboard
      -- must show it rather than omitting what has no owner.
      (t, at_domain, 'legacy-reports.example.com', 1, 'legacy-reports.example.com', NULL, 'INHERITED',
       NULL, NULL, NULL, NULL, 'INTERNET_PUBLIC', 'external-scan', now(), false,
       'DISCOVERED', ARRAY['unowned'], NULL, 'CONNECTOR', 'DNS_ENUMERATION',
       now() - interval '10 days', now(), n_ins_core, ARRAY[n_root,n_bank,n_ins,n_ins_core]::uuid[],
       nt_proj, ct2, hv, now()),
      (t, at_service, 'policy-core', 1, 'policy-core', n_ins_core, 'INHERITED', NULL,
       'INTERNAL_ONLY', principal, now(), 'INTERNAL_ONLY', 'external-scan', now(), false,
       'ACTIVE', ARRAY['internal'], engineer_b, 'MANUAL', 'ONBOARDING',
       now() - interval '300 days', now(), n_ins_core, ARRAY[n_root,n_bank,n_ins,n_ins_core]::uuid[],
       nt_proj, ct2, hv, now())
    ON CONFLICT DO NOTHING;

    -- ---- Findings. Across classes, severities, states and ages. ----
    FOR a IN
        SELECT * FROM (VALUES
          ('CODE','SQL injection in the settlement report filter','OPEN',sev_crit,'semgrep','p/java',
           engineer_a, 41, 'payments-authorization'),
          ('CODE','Reflected cross-site scripting in the admin search','OPEN',sev_high,'semgrep','p/java',
           engineer_a, 18, 'payments-admin'),
          ('DEPENDENCY','jackson-databind deserialization of untrusted data','OPEN',sev_high,'trivy','cve',
           engineer_b, 9, 'payments-authorization'),
          ('SECRET','Live database credential committed to the repository','OPEN',sev_crit,'gitleaks',
           'generic-api-key', engineer_a, 3, 'group/payments-api'),
          ('CONFIGURATION','Administrative interface reachable from the internet','OPEN',sev_high,
           'external-scan','exposure', engineer_b, 30, 'payments-admin'),
          ('RUNTIME','Missing rate limit on the authorization endpoint','OPEN',sev_med,'manual-pentest',
           'asvs-2.2.1', engineer_a, 55, 'payments-authorization'),
          ('INFRASTRUCTURE','TLS 1.0 accepted on a public listener','OPEN',sev_med,'external-scan','tls',
           NULL, 22, 'pay.example.com'),
          ('MANUAL','Horizontal access control gap between tenant accounts','OPEN',sev_crit,
           'manual-pentest','asvs-4.2.1', engineer_b, 6, 'payments-authorization'),
          ('DEPENDENCY','Express prototype pollution','OPEN',sev_low,'trivy','cve', NULL, 12,
           'policy-core'),
          ('CODE','Insecure direct object reference in the policy export','OPEN',sev_med,'semgrep',
           'p/java', engineer_b, 47, 'policy-core')
        ) AS v(cls, title, st, sev, tool, rule, assignee, age_days, asset_name)
    LOOP
        INSERT INTO finding (tenant_id, fingerprint_digest, fingerprint_algorithm_version, finding_class,
                             title, description, reported_severity_id, reported_severity_raw,
                             effective_severity_id, state, assignee_id, recurrence_count, source_tool,
                             source_tool_version, source_rule_identity, raw_source_record_ref,
                             first_detected_at, last_detected_at, scope_node_id, scope_ancestor_path,
                             scope_node_type_id, scope_criticality_id, scope_hierarchy_ver,
                             scope_resolved_at)
        SELECT t, sha256((a.title || a.asset_name)::bytea), 1, a.cls, a.title,
               'Seeded demonstration record. The description path is the evidence surface of '
               || 'OPS-DEP-016 and is not populated here.',
               a.sev, upper(a.cls), a.sev, a.st, a.assignee, 0, a.tool, '1.0', a.rule,
               'demo://seed/' || a.asset_name,
               now() - (a.age_days || ' days')::interval, now(),
               s.scope_node_id, s.scope_ancestor_path, s.scope_node_type_id, s.scope_criticality_id,
               s.scope_hierarchy_ver, now()
          FROM asset s WHERE s.display_name = a.asset_name AND s.tenant_id = t
        ON CONFLICT DO NOTHING;
    END LOOP;

    -- ---- Assessment requests, one per state worth showing. ----
    FOR r IN
        SELECT * FROM (VALUES
          ('REQ-2026-0042','DRAFT',       n_pay_api,  false, false, false, false, NULL::int, false),
          ('REQ-2026-0043','SUBMITTED',   n_pay_web,  true,  true,  true,  true,  62,        false),
          ('REQ-2026-0044','INTAKE_REVIEW', n_ins_core, true, true, true,  true,  48,        false),
          ('REQ-2026-0045','SCHEDULED',   n_pay_api,  true,  true,  true,  true,  88,        false),
          ('REQ-2026-0046','IN_PROGRESS', n_pay_api,  true,  true,  true,  true,  91,        false),
          ('REQ-2026-0047','FIXING',      n_pay_web,  true,  true,  true,  true,  74,        false),
          -- Not marked as a retest. INV-ASM-09 requires a retest to reference BOTH a prior assessment
          -- and a revision identifier — "both references, or neither" — and this seed creates no
          -- assessment for it to point at. Setting the flag without them was rejected, correctly: a
          -- retest that cannot name what it is retesting is a claim about work nobody can find.
          ('REQ-2026-0048','CLOSED_PASSED', n_ins_core, true, true, true,  true,  33,        false)
        ) AS v(code, st, node, r_env, r_acct, r_data, r_contact, score, retest)
    LOOP
        INSERT INTO assessment_request (tenant_id, request_code, type_id, requested_org_node_id, state,
                                       readiness_environment_available, readiness_accounts_provisioned,
                                       readiness_data_seeded, readiness_contact_available,
                                       readiness_attested_at, readiness_attested_by,
                                       derived_priority_score, derived_effort_days,
                                       derived_feasible_start, derived_model_version, is_retest,
                                       scope_node_id, scope_ancestor_path, scope_node_type_id,
                                       scope_criticality_id, scope_hierarchy_ver, scope_resolved_at,
                                       requested_by, submitted_at)
        SELECT t, r.code, asm_type, r.node, r.st,
               r.r_env, r.r_acct, r.r_data, r.r_contact,
               CASE WHEN r.st = 'DRAFT' THEN NULL ELSE now() - interval '2 days' END,
               CASE WHEN r.st = 'DRAFT' THEN NULL ELSE principal END,
               r.score, CASE WHEN r.score IS NULL THEN NULL ELSE (r.score / 12.0)::numeric(6,2) END,
               CASE WHEN r.score IS NULL THEN NULL ELSE current_date + 7 END,
               CASE WHEN r.score IS NULL THEN NULL ELSE 1 END,
               r.retest,
               CASE WHEN r.st = 'DRAFT' THEN NULL ELSE r.node END,
               CASE WHEN r.st = 'DRAFT' THEN NULL ELSE ARRAY[n_root, r.node]::uuid[] END,
               CASE WHEN r.st = 'DRAFT' THEN NULL ELSE nt_proj END,
               CASE WHEN r.st = 'DRAFT' THEN NULL ELSE ct1 END,
               CASE WHEN r.st = 'DRAFT' THEN NULL ELSE hv END,
               CASE WHEN r.st = 'DRAFT' THEN NULL ELSE now() - interval '3 days' END,
               principal,
               CASE WHEN r.st = 'DRAFT' THEN NULL ELSE now() - interval '3 days' END
        ON CONFLICT DO NOTHING;
    END LOOP;

    -- Role accounts: two per role where readiness claims accounts are provisioned, one where it does
    -- not — so the two-per-role warning has something real to report.
    INSERT INTO assessment_request_role_account (tenant_id, request_id, role_name, role_description,
                                                 username, credential_ref, mfa_enrolled, mfa_bypass_ref,
                                                 tenant_or_org_context, expected_permissions,
                                                 account_status)
    SELECT t, q.id, v.role, v.descr, v.user, v.cred, v.mfa,
           CASE WHEN v.mfa THEN v.cred || '-totp' ELSE NULL END,
           'demo-tenant', v.perms, v.status
      FROM assessment_request q
      CROSS JOIN (VALUES
        ('Administrator','Full administrative access','pt.admin.a','vault://kv/appsec/admin-a',true,
         ARRAY['manage_users','view_all'],'VERIFIED'),
        ('Administrator','Full administrative access','pt.admin.b','vault://kv/appsec/admin-b',true,
         ARRAY['manage_users','view_all'],'PROVIDED'),
        ('Operator','Day-to-day operations','pt.oper.a','vault://kv/appsec/oper-a',false,
         ARRAY['view_own'],'PROVIDED'),
        ('Operator','Day-to-day operations','pt.oper.b','vault://kv/appsec/oper-b',false,
         ARRAY['view_own'],'PROVIDED')
      ) AS v(role, descr, "user", cred, mfa, perms, status)
     WHERE q.tenant_id = t AND q.request_code IN
           ('REQ-2026-0043','REQ-2026-0045','REQ-2026-0046','REQ-2026-0047')
    ON CONFLICT DO NOTHING;

    INSERT INTO assessment_request_environment (tenant_id, request_id, env_type, base_url,
        protective_control_present, protective_control_vendor, bypass_arranged, bypass_method,
        rate_limit_present, rate_limit_threshold, data_destruction_allowed, db_reset_available,
        db_reset_procedure, vpn_required, vpn_access_procedure, test_window_constraints,
        monitoring_suppression_arranged)
    SELECT t, q.id, v.env, v.url, v.ctrl, v.vendor, v.bypass, v.method, v.rl, v.threshold,
           v.destroy, v.reset, v.proc, v.vpn, v.vpnproc, v.window, v.suppress
      FROM assessment_request q
      CROSS JOIN (VALUES
        ('STAGING','https://stg.payments.example.internal', true, 'Cloud WAF', true,
         'source IP allowlist plus a bypass header', true, '200 requests per second',
         true, true, 'Self-service reset from the pipeline', true,
         'Zero-trust client with a per-engagement profile', 'Weekdays 09:00-18:00 ICT', true),
        ('UAT','https://uat.payments.example.internal', false, NULL, false, NULL,
         false, NULL, true, true, 'Nightly restore from an anonymized snapshot', true,
         'Same profile as staging', 'Any time', false)
      ) AS v(env, url, ctrl, vendor, bypass, method, rl, threshold, destroy, reset, proc, vpn, vpnproc,
             "window", suppress)
     WHERE q.tenant_id = t AND q.request_code IN
           ('REQ-2026-0043','REQ-2026-0045','REQ-2026-0046','REQ-2026-0047')
    ON CONFLICT DO NOTHING;

    -- Coverage rows for every asset that has not submitted, so the dashboard reports them as
    -- NEVER_SUBMITTED rather than omitting them. PRD-SBM-056: an asset absent from coverage reporting
    -- is one where absence reads as absence of problems. The row carries NO snapshot and NO quality.
    INSERT INTO sbom_coverage_state (tenant_id, asset_id, quality, declared_stack_ecosystems,
                                     freshness_threshold_days, accountable_owner_id)
    SELECT t, s.id, 'REJECTED',
           CASE WHEN s.display_name LIKE '%api%' THEN ARRAY['maven'] ELSE ARRAY[]::text[] END,
           CASE WHEN s.scope_criticality_id = ct1 THEN 14 ELSE 30 END,
           coalesce(s.technical_contact_id, principal)
      FROM asset s
     WHERE s.tenant_id = t
       AND NOT EXISTS (SELECT 1 FROM sbom_coverage_state c
                        WHERE c.tenant_id = t AND c.asset_id = s.id);

    RAISE NOTICE 'seeded: % org nodes, % assets, % findings, % requests',
        (SELECT count(*) FROM org_node),
        (SELECT count(*) FROM asset),
        (SELECT count(*) FROM finding),
        (SELECT count(*) FROM assessment_request);
END
$seed$;
