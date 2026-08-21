-- =============================================================================
-- Identity seed. Idempotent.
--
-- The permission catalogue is PRODUCT-FIXED (PRD-AUZ-001), so these rows are product data and a
-- tenant may not add to them. The ROLES below are tenant data: the codes ADMIN, PENTESTER, DEVELOPER,
-- PROJECT_SECURITY_OWNER and BUSINESS_UNIT_MANAGER are seeded defaults a tenant renames or replaces,
-- and none of them appears in a constraint or in code. A second customer with different structure
-- seeds different roles and the platform does not change.
-- =============================================================================
\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------------------------
-- THE THREE EXAMPLE PEOPLE ARE OPT-OUT. `-v demo_people=off` skips them.
--
-- WHY THIS SWITCH EXISTS. `CredentialBootstrap` gives ASPM_BOOTSTRAP_PASSWORD to every principal
-- that has no credential, so `pentester` and `developer` are not inert rows in a deployment that
-- ran this file — they are accounts that can sign in, with a password taken from an environment
-- variable, holding real scope over real assets. On a demo estate they are the point; on a tenant
-- holding a company's actual attack surface they are two accounts nobody asked for.
--
-- Left ON by default so every existing demo flow behaves exactly as before. seed-bootstrap.sql,
-- which is the entry point for a tenant with real data, tells you to pass `off`.
-- ---------------------------------------------------------------------------------------------
\if :{?demo_people}
\else
  \set demo_people on
\endif

-- WHICH TENANT THE ROLES AND THE POLICY GO INTO. Defaults to the demo tenant so every existing flow
-- behaves as before; seed-bootstrap.sql passes a real one. Without this the permission catalogue
-- (product-fixed, tenant-independent) would land correctly and the ROLES — which are tenant data —
-- would land in a tenant nobody is serving.
\if :{?tenant_id}
\else
  \set tenant_id '11111111-1111-1111-1111-111111111111'
\endif

INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up) VALUES
  ('org.node.read',       'ORG', '{"en":"Read organization nodes"}',        false, false),
  ('org.node.create',     'ORG', '{"en":"Create organization nodes"}',      false, false),
  ('org.node.update',     'ORG', '{"en":"Update organization nodes"}',      false, false),
  ('org.nodetype.read',   'ORG', '{"en":"Read structure types"}',           false, false),
  ('org.nodetype.manage', 'ORG', '{"en":"Manage structure types"}',         false, true),
  ('ast.asset.read',      'AST', '{"en":"Read assets"}',                    false, false),
  ('ast.asset.create',    'AST', '{"en":"Create assets"}',                  false, false),
  ('ast.asset.update',    'AST', '{"en":"Update assets"}',                  false, false),
  ('ast.assettype.read',  'AST', '{"en":"Read asset types"}',               false, false),
  ('ast.assettype.manage','AST', '{"en":"Manage asset types"}',             false, true),
  ('vul.finding.read',    'VUL', '{"en":"Read findings"}',                  false, false),
  ('vul.finding.triage',  'VUL', '{"en":"Triage findings"}',                false, false),
  ('asm.request.read',    'ASM', '{"en":"Read assessment requests"}',       false, false),
  ('asm.request.create',  'ASM', '{"en":"Create assessment requests"}',     false, false),
  ('asm.request.update',  'ASM', '{"en":"Update assessment requests"}',     false, false),
  ('asm.request.submit',  'ASM', '{"en":"Submit a request"}',               false, false),
  ('asm.request.triage',  'ASM', '{"en":"Triage a request"}',               false, false),
  ('asm.request.accept',  'ASM', '{"en":"Accept a request"}',               false, false),
  ('asm.request.schedule','ASM', '{"en":"Schedule a request"}',             false, false),
  ('asm.request.execute', 'ASM', '{"en":"Conduct an assessment"}',          false, false),
  ('asm.request.qa',      'ASM', '{"en":"Approve a report in QA"}',         false, false),
  ('asm.request.approve', 'ASM', '{"en":"Approve a request"}',              false, false),
  ('asm.request.cancel',  'ASM', '{"en":"Cancel a request"}',               false, false),
  ('asm.request.acceptrisk','ASM','{"en":"Accept residual risk"}',          false, true),
  ('sbm.sbom.submit',     'SBM', '{"en":"Submit an SBOM"}',                 false, false),
  ('sbm.coverage.read',   'SBM', '{"en":"Read dependency coverage"}',       false, false),
  ('cap.team.read',       'CAP', '{"en":"Read team capacity"}',             false, false),
  -- RESTRICTED, and never implied by seniority (PRD-CAP-013, DOC-07 §5.2).
  ('cap.member.read.all', 'CAP', '{"en":"Read per-member workload"}',       true,  false),
  ('iam.user.read',       'IAM', '{"en":"Read users"}',                     false, false),
  ('iam.user.manage',     'IAM', '{"en":"Create and update users"}',        false, true),
  ('iam.credential.reset','IAM', '{"en":"Reset a user credential"}',        true,  true),
  ('auz.role.manage',     'AUZ', '{"en":"Manage roles and assignments"}',   false, true)
ON CONFLICT (code) DO NOTHING;

-- psql does NOT substitute :variables inside a dollar-quoted block, so they are carried in as
-- session settings the block reads at run time. Substituting them textually would also mean a value
-- containing a quote became SQL, which is the injection this avoids by construction.
SELECT set_config('aspm.seed_tenant', :'tenant_id', false);
SELECT set_config('aspm.seed_demo_people', :'demo_people', false);

DO $seed$
DECLARE
    t        uuid := current_setting('aspm.seed_tenant')::uuid;
    -- Only reached under demo_people; these are the demo estate's own node identifiers.
    demo_people boolean := current_setting('aspm.seed_demo_people')
                           IN ('on', 'true', 'yes', '1');
    n_root   uuid := 'aaaaaaaa-1111-4000-8000-00000000000a';
    n_bank   uuid := '30000000-0000-4000-8000-000000000001';
    n_pay_api uuid := '30000000-0000-4000-8000-000000000003';
    admin_id uuid := '70000000-0000-4000-8000-00000000000a';
    pt_id    uuid := '70000000-0000-4000-8000-00000000000b';
    dev_id   uuid := '70000000-0000-4000-8000-00000000000c';
    r        record;
    role_id  uuid;
BEGIN
    PERFORM set_config('aspm.current_tenant', t::text, true);

    -- The policy row. Defaults, made explicit so a tenant sees what applies rather than inferring it.
    INSERT INTO password_policy (tenant_id) VALUES (t) ON CONFLICT DO NOTHING;

    -- SEC-SEC-006. An illustrative corpus. A deployment loads a real one; the interface reports the
    -- corpus size rather than passing silently, so a thin corpus is visible instead of looking like a
    -- working check.
    -- SHA-1 hex computed at authoring time: PostgreSQL has no sha1() and pgcrypto is absent
    -- outside the test-only shim. The corpus is published in SHA-1 because that is the format every
    -- breach list uses — a lookup key, not a security primitive, which is the one context where
    -- SHA-1 is still the right answer.
    INSERT INTO breached_password (password_sha1_prefix, password_sha1_suffix, occurrence_count)
    VALUES
        ('5BAA6', '1E4C9B93F3F0682250B6CF8331B7EE68FD8', 1),
        ('70CCD', '9007338D6D81DD3B6271621B9CF9A97EA00', 1),
        ('32CA9', 'FC1A0F5B6330E3F4C8C1BBECDE9BEDB9573', 1),
        ('7C4A8', 'D09CA3762AF61E59520943DC26494F8941B', 1),
        ('7C222', 'FB2927D828AF22F592134E8932480637C0D', 1),
        ('B1B37', '73A05C0ED0176787A4F1574FF0075F7521E', 1),
        ('B7A87', '5FC1EA228B9061041B7CEC4BD3C52AB3CE3', 1),
        ('C0B13', '7FE2D792459F26FF763CCE44574A5B5AB03', 1),
        ('D033E', '22AE348AEB5660FC2140AEC35850C4DA997', 1),
        ('F865B', '53623B121FD34EE5426C792E5C33AF8C227', 1),
        ('EBFC7', '910077770C8340F63CD2DCA2AC1F120444F', 1),
        ('21BD1', '2DC183F740EE76F27B78EB39C8AD972A757', 1),
        ('FA9BE', 'B99E4029AD5A6615399E7BBAE21356086B3', 1),
        ('EE8D8', '728F435FD550F83852AABAB5234CE1DA528', 1),
        ('AB87D', '24BDC7452E55738DEB5F868E1F16DEA5ACE', 1),
        ('AF897', '8B1797B72ACFFF9595A5A2A373EC3D9106D', 1),
        ('2D27B', '62C597EC858F6E7B54E7E58525E6A95E6D8', 1),
        ('A2C90', '1C8C6DEA98958C219F6F2D038C44DC5D362', 1),
        ('8D6E3', '4F987851AA599257D3831A1AF040886842F', 1),
        ('775BB', '961B81DA1CA49217A48E533C832C337154A', 1),
        ('7E8B0', 'A3433F1210A9699D85420E363A1B162ECAC', 1),
        ('FCB8F', '40140297C7D1E3464C53E1F9A8BC4DDBEDF', 1),
        ('B6B17', '47A356D59A84C332863B4A877274951227B', 1),
        ('3A960', '464D36C1B8BAD183ED57EE79C0E39953CCE', 1),
        ('CC9F8', '16A42431CF852CDC7A3FAD42A6F65FFCE24', 1)
    ON CONFLICT DO NOTHING;

    -- ---- Roles. TENANT DATA. The names below are defaults, not product entities. ----
    FOR r IN SELECT * FROM (VALUES
        ('ADMIN', '{"en":"Administrator","vi":"Quản trị"}',
         'Full platform administration, including users and roles.'),
        ('PENTESTER', '{"en":"Pentester","vi":"Kiểm thử xâm nhập"}',
         'Conducts assessments, triages findings, moves requests through the workflow.'),
        ('DEVELOPER', '{"en":"Developer","vi":"Lập trình viên"}',
         'Reads findings and assets in their own scope, raises and submits requests.'),
        ('PROJECT_SECURITY_OWNER', '{"en":"Project security owner","vi":"Phụ trách an toàn dự án"}',
         'Accountable for one project: reads everything in it and submits requests.'),
        ('BUSINESS_UNIT_MANAGER', '{"en":"Business unit manager","vi":"Quản lý đơn vị kinh doanh"}',
         'Reads posture across a business unit subtree. Excludes team capacity by design.')
    ) AS v(code, label, descr) LOOP
        INSERT INTO role (tenant_id, code, label_i18n, description, derived_from_template)
        VALUES (t, r.code, r.label::jsonb, r.descr, r.code)
        ON CONFLICT DO NOTHING;
    END LOOP;

    -- ---- Role composition. Every code comes from the catalogue; the FK enforces it. ----
    SELECT id INTO role_id FROM role WHERE tenant_id = t AND code = 'ADMIN';
    INSERT INTO role_permission (tenant_id, role_id, permission_code)
    SELECT t, role_id, code FROM permission_catalogue
    ON CONFLICT DO NOTHING;

    SELECT id INTO role_id FROM role WHERE tenant_id = t AND code = 'PENTESTER';
    INSERT INTO role_permission (tenant_id, role_id, permission_code)
    SELECT t, role_id, c FROM unnest(ARRAY[
        'org.node.read','org.nodetype.read','ast.asset.read','ast.assettype.read',
        'vul.finding.read','vul.finding.triage','asm.request.read','asm.request.update',
        'asm.request.triage','asm.request.accept','asm.request.schedule','asm.request.execute',
        'asm.request.qa','asm.request.cancel','sbm.coverage.read','sbm.sbom.submit','cap.team.read'
    ]) AS c ON CONFLICT DO NOTHING;

    SELECT id INTO role_id FROM role WHERE tenant_id = t AND code = 'DEVELOPER';
    INSERT INTO role_permission (tenant_id, role_id, permission_code)
    SELECT t, role_id, c FROM unnest(ARRAY[
        'org.node.read','ast.asset.read','vul.finding.read','asm.request.read',
        'asm.request.create','asm.request.submit','sbm.coverage.read','sbm.sbom.submit'
    ]) AS c ON CONFLICT DO NOTHING;

    SELECT id INTO role_id FROM role WHERE tenant_id = t AND code = 'PROJECT_SECURITY_OWNER';
    INSERT INTO role_permission (tenant_id, role_id, permission_code)
    SELECT t, role_id, c FROM unnest(ARRAY[
        'org.node.read','org.nodetype.read','ast.asset.read','ast.asset.update',
        'vul.finding.read','asm.request.read','asm.request.create','asm.request.submit',
        'sbm.coverage.read'
    ]) AS c ON CONFLICT DO NOTHING;

    -- Business unit manager: posture, and NOT cap.team.read. DOC-07 §5.2 excludes it deliberately —
    -- "a business owner who can see aggregate security-team capacity will direct requests by observed
    -- availability, bypassing the prioritization the platform exists to enforce".
    SELECT id INTO role_id FROM role WHERE tenant_id = t AND code = 'BUSINESS_UNIT_MANAGER';
    INSERT INTO role_permission (tenant_id, role_id, permission_code)
    SELECT t, role_id, c FROM unnest(ARRAY[
        'org.node.read','org.nodetype.read','ast.asset.read','vul.finding.read',
        'asm.request.read','sbm.coverage.read'
    ]) AS c ON CONFLICT DO NOTHING;

    -- ---- Principals. No credential here: it is set by the application so Argon2id runs where its
    -- parameters live, rather than being reproduced in SQL where they would drift.
    --
    -- Skipped entirely with -v demo_people=off. Each of these can SIGN IN once the credential
    -- bootstrap runs, so on a tenant holding real data they are three unrequested accounts.
    IF demo_people THEN
        INSERT INTO principal (id, tenant_id, kind, username, email, display_name, lifecycle_state,
                               must_change_password)
        VALUES (admin_id, t, 'HUMAN', 'admin', 'admin@example.com', 'Platform Administrator',
                'ACTIVE', false),
               (pt_id, t, 'HUMAN', 'pentester', 'pentester@example.com', 'Nguyen Van A', 'ACTIVE',
                false),
               (dev_id, t, 'HUMAN', 'developer', 'developer@example.com', 'Tran Thi B', 'ACTIVE',
                false)
        ON CONFLICT DO NOTHING;
    END IF;

    -- ---- Assignments, each with its scope. A role without a scope is a role over everything. ----
    IF demo_people THEN
        INSERT INTO role_assignment (tenant_id, principal_id, role_id, scope_node_id, scope_mode)
        SELECT t, admin_id, id, NULL, 'TENANT' FROM role WHERE tenant_id = t AND code = 'ADMIN'
        ON CONFLICT DO NOTHING;

        INSERT INTO role_assignment (tenant_id, principal_id, role_id, scope_node_id, scope_mode)
        SELECT t, pt_id, id, n_root, 'SUBTREE' FROM role WHERE tenant_id = t AND code = 'PENTESTER'
        ON CONFLICT DO NOTHING;

        -- The developer reaches one project, not the business unit. This is the assignment that makes
        -- the scope filtering on every page observable rather than theoretical.
        INSERT INTO role_assignment (tenant_id, principal_id, role_id, scope_node_id, scope_mode)
        SELECT t, dev_id, id, n_pay_api, 'SUBTREE' FROM role WHERE tenant_id = t AND code = 'DEVELOPER'
        ON CONFLICT DO NOTHING;
    END IF;

    RAISE NOTICE 'identity seeded: % permissions, % roles, % principals, % assignments',
        (SELECT count(*) FROM permission_catalogue),
        (SELECT count(*) FROM role),
        (SELECT count(*) FROM principal),
        (SELECT count(*) FROM role_assignment);
END
$seed$;
