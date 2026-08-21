-- ==============================================================================================
-- A TENANT WITH NO FABRICATED DATA IN IT.
--
-- WHY THIS FILE EXISTS
--
-- Every other seed in this directory was written to make the interface demonstrable, and the
-- vocabulary a tenant CANNOT START WITHOUT ended up inside the largest of them. `seed-demo.sql`
-- creates the organization node types, the criticality tiers, the severity scale and four asset
-- types — and, in the same DO block, seventy assets, seven hundred findings and two hundred
-- assessment requests that describe a company that does not exist.
--
-- So there was no way to stand up a tenant for real data. Running the demo seed pollutes every count,
-- every score and every coverage figure with fiction, which is the exact failure product principle 1
-- exists to prevent — and NOT running it leaves a tenant with no node types, no tiers, no severity
-- scale and no asset types, in which nothing can be created at all. `seed-demo.sql` is also no longer
-- replayable: it writes an assessment request in a state the workflow trigger added later rejects, so
-- it aborts partway through.
--
-- This file is the other half: structure and vocabulary, nothing that claims a fact about an estate.
--
-- WHAT IT DELIBERATELY DOES NOT DO
--
--   * It does not invent your organization. Three node types and one root node are created as
--     OPINIONATED DEFAULTS (product principle 3) with the names below; rename them, add levels, or
--     replace them at Configuration and Access. Nothing in code reads any code below by name.
--   * It does not create people other than one administrator you name. `CredentialBootstrap` gives
--     `ASPM_BOOTSTRAP_PASSWORD` to every principal that has no credential, so a seed that creates
--     example accounts creates example accounts that can sign in.
--   * It does not create roles beyond ADMIN. Role composition is tenant data (ADR-027) and is
--     editable at Access -> Roles; a seed that imposed a role taxonomy would impose one company's.
--
-- WHAT YOU MUST RUN ALONGSIDE IT, AND WHY
--
--   seed-identity.sql   THE PERMISSION CATALOGUE IS IN THERE. It is product-fixed data (PRD-AUZ-001)
--                       and 32 of the 49 permissions the platform has come from that file rather than
--                       from a migration, so a tenant without it has almost no permissions and no
--                       role can be composed. Run it with -v demo_people=off (see that file).
--   seed-workflow.sql   The finding and request workflows — states, transitions, guards. Without it
--                       every transition is refused as undefined.
--   seed-cadence.sql    Assessment triggers, the per-criticality review interval, and which terminal
--                       states discharge a review obligation.
--
--   seed-project-attributes.sql and seed-composition.sql carry declared-field catalogues that are
--   useful defaults but are one company's questions. Read them before running them.
--
-- WHAT YOU MUST NOT RUN AGAINST A TENANT HOLDING REAL DATA
--
--   seed-demo.sql  seed-applications.sql  seed-projects.sql  seed-findings-link.sql
--   seed-sbom-demo.sql  seed-sbom-demo-advisory-detail.sql  seed-workload-demo.sql
--   seed-requests.sql  seed-organization-names.sql  seed-object-grants.sql  seed-finding-import.sql
--
-- USAGE
--
--   psql -v tenant_id=<uuid> -v tenant_name='<name>' -v residency=<VN|EU|US> \
--        -v admin_user=<username> -v admin_email=<address> -v admin_name='<display name>' \
--        -f seed-bootstrap.sql
--
--   The tenant id must then be ASPM_TENANT_ID in .env. A deployment serves one tenant, and the two
--   disagreeing produces a platform that reads an empty tenant and reports an empty estate.
-- ==============================================================================================

\set ON_ERROR_STOP on

\if :{?tenant_id}
\else
\echo '  seed-bootstrap: pass -v tenant_id=<uuid>. There is deliberately no default: the demo'
\echo '  tenant id is 11111111-1111-1111-1111-111111111111 and inheriting it silently would put'
\echo '  real data in the tenant the demo seeds write to.'
\quit
\endif
\if :{?tenant_name}
\else
\echo '  seed-bootstrap: pass -v tenant_name=<name>'
\quit
\endif
\if :{?residency}
\else
\echo '  seed-bootstrap: pass -v residency=<VN|EU|US>. Data residency is a tenant designation'
\echo '  (CFG-TEN-001) and a wrong one is a compliance statement nobody made.'
\quit
\endif
\if :{?admin_user}
\else
\echo '  seed-bootstrap: pass -v admin_user=<username> -v admin_email=<address> -v admin_name=<name>'
\quit
\endif

-- psql does NOT substitute :variables inside a dollar-quoted block, so they are carried in as
-- session settings the block reads at run time. Substituting them textually would also mean a value
-- containing a quote became SQL, which is the injection this avoids by construction.
SELECT set_config('aspm.seed_tenant', :'tenant_id', false);
SELECT set_config('aspm.seed_tenant_name', :'tenant_name', false);
SELECT set_config('aspm.seed_residency', :'residency', false);
SELECT set_config('aspm.seed_admin_user', :'admin_user', false);
SELECT set_config('aspm.seed_admin_email', :'admin_email', false);
SELECT set_config('aspm.seed_admin_name', :'admin_name', false);

DO $bootstrap$
DECLARE
    t          uuid := current_setting('aspm.seed_tenant')::uuid;
    nt_top     uuid := uuidv7();
    nt_mid     uuid := uuidv7();
    nt_leaf    uuid := uuidv7();
    n_root     uuid := uuidv7();
    admin_id   uuid := uuidv7();
    role_admin uuid;
    tiers      int;
BEGIN
    -- --------------------------------------------------------------------------------------------
    -- 1. The tenant registration row.
    --
    -- Row-level security keys off a session variable and never consults this table, so the platform
    -- runs without it — and every migration backfill written as `FOR t IN SELECT id FROM tenant`
    -- iterates zero times, changes nothing, and reports success. Two of them did. See seed-tenant.sql.
    -- --------------------------------------------------------------------------------------------
    INSERT INTO tenant (id, display_name, lifecycle_state, residency_region, key_reference,
                        entitlement_tier)
    VALUES (t, current_setting('aspm.seed_tenant_name'), 'ACTIVE', current_setting('aspm.seed_residency'),
            -- A REFERENCE, never a key. ADR-002 gives each tenant its own key; what is recorded here
            -- is where to ask for it. ⚠ OQ-026 is open and CredentialCustody currently uses ONE key
            -- for the whole deployment from ASPM_CREDENTIAL_KEY — so this reference does not yet
            -- resolve to anything, and that is a gap rather than a configuration mistake.
            'vault://aspm/tenant/' || t::text || '/data-key', 'STANDARD')
    ON CONFLICT (id) DO NOTHING;

    PERFORM set_config('aspm.current_tenant', t::text, true);

    -- --------------------------------------------------------------------------------------------
    -- 2. Organization node types — THREE LEVELS AS A DEFAULT, NOT AS A MODEL OF YOUR COMPANY.
    --
    -- ADR-010: one OrgNode hierarchy with a closure table and configurable node types. ADR-027: no
    -- hardcoded org levels. Hierarchy depth, names and permitted parents are all editable at
    -- Configuration; `may_own_assets` on the deepest level only is the shape the inventory expects
    -- (an asset is owned by a team, not by a division) and is the one default worth keeping.
    -- --------------------------------------------------------------------------------------------
    INSERT INTO org_node_type (id, tenant_id, code, label_i18n, ordinal, may_own_assets,
                               may_scope_work)
    VALUES (nt_top,  t, 'GROUP', '{"en":"Group","vi":"Tập đoàn"}',            1, false, true),
           (nt_mid,  t, 'UNIT',  '{"en":"Business unit","vi":"Đơn vị"}',      2, false, true),
           (nt_leaf, t, 'TEAM',  '{"en":"Team","vi":"Đội"}',                  3, true,  true)
    ON CONFLICT DO NOTHING;

    -- Re-read rather than trusting the generated ids: on a re-run the ON CONFLICT above inserted
    -- nothing and the local variables name rows that do not exist.
    SELECT id INTO nt_top  FROM org_node_type WHERE tenant_id = t AND code = 'GROUP';
    SELECT id INTO nt_mid  FROM org_node_type WHERE tenant_id = t AND code = 'UNIT';
    SELECT id INTO nt_leaf FROM org_node_type WHERE tenant_id = t AND code = 'TEAM';

    UPDATE org_node_type SET permitted_parent_type_ids = ARRAY[nt_top]::uuid[]
     WHERE id = nt_mid AND tenant_id = t AND permitted_parent_type_ids IS NULL;
    UPDATE org_node_type SET permitted_parent_type_ids = ARRAY[nt_mid]::uuid[]
     WHERE id = nt_leaf AND tenant_id = t AND permitted_parent_type_ids IS NULL;

    -- --------------------------------------------------------------------------------------------
    -- 3. One root node, named after the tenant.
    --
    -- Something has to exist for the first real node to hang from, and for a tenant-wide role
    -- assignment to be scoped to. The closure table maintains itself (V063), so no org_closure rows
    -- are written here — a seed that wrote them would be the fourth writer of a table the engine now
    -- owns.
    -- --------------------------------------------------------------------------------------------
    INSERT INTO org_node (id, tenant_id, type_id, parent_id, name, criticality_mode,
                          lifecycle_state)
    SELECT n_root, t, nt_top, NULL, current_setting('aspm.seed_tenant_name'), 'INHERITED', 'ACTIVE'
     WHERE NOT EXISTS (SELECT 1 FROM org_node WHERE tenant_id = t AND parent_id IS NULL);
    SELECT id INTO n_root FROM org_node WHERE tenant_id = t AND parent_id IS NULL LIMIT 1;

    -- --------------------------------------------------------------------------------------------
    -- 4. Criticality tiers. Three, because the DOC-28 risk model weighs the tier and a scale nobody
    -- can tell apart is a scale nobody uses. Names and count are tenant data (CFG-AST-001).
    -- --------------------------------------------------------------------------------------------
    INSERT INTO criticality_tier (tenant_id, code, label_i18n, ordinal)
    VALUES (t, 'TIER1', '{"en":"Tier 1 — critical","vi":"Bậc 1 — trọng yếu"}',   1),
           (t, 'TIER2', '{"en":"Tier 2 — important","vi":"Bậc 2 — quan trọng"}', 2),
           (t, 'TIER3', '{"en":"Tier 3 — supporting","vi":"Bậc 3 — hỗ trợ"}',    3)
    ON CONFLICT DO NOTHING;

    -- --------------------------------------------------------------------------------------------
    -- 5. Severity scale. Presentation over a product-fixed internal ordinal (CFG-VUL-001), so a
    -- tenant using four levels and a tenant using five stay comparable across sources.
    -- --------------------------------------------------------------------------------------------
    INSERT INTO severity_level (tenant_id, code, label_i18n, ordinal)
    VALUES (t, 'CRITICAL', '{"en":"Critical","vi":"Nghiêm trọng"}', 1),
           (t, 'HIGH',     '{"en":"High","vi":"Cao"}',              2),
           (t, 'MEDIUM',   '{"en":"Medium","vi":"Trung bình"}',     3),
           (t, 'LOW',      '{"en":"Low","vi":"Thấp"}',              4)
    ON CONFLICT DO NOTHING;

    -- --------------------------------------------------------------------------------------------
    -- 6. Asset types.
    --
    -- ADR-009: one Asset aggregate with a type registry, not five parallel inventories. These six
    -- codes are the ones the application tier and the composition pipeline name, so a tenant missing
    -- one gets a form that cannot save; the LABELS are tenant data and the set is extensible at
    -- Configuration.
    --
    -- `is_network_reachable` and `may_carry_findings` are not decoration: a finding must be able to
    -- name the thing it was found in, and reachability is what makes a host answer "what is exposed".
    -- --------------------------------------------------------------------------------------------
    INSERT INTO asset_type (tenant_id, code, label_i18n, ordinal, identity_rule,
                            is_network_reachable, may_carry_findings)
    VALUES
      (t, 'APPLICATION', '{"en":"Application","vi":"Ứng dụng"}',  0,
       '{"version":1,"natural_key_attributes":["display_name"]}', true,  true),
      (t, 'PROJECT',     '{"en":"Project","vi":"Dự án"}',         1,
       '{"version":1,"natural_key_attributes":["display_name"]}', false, true),
      (t, 'SERVICE',     '{"en":"Service","vi":"Dịch vụ"}',       2,
       '{"version":1,"natural_key_attributes":["display_name"]}', true,  true),
      (t, 'FEATURE',     '{"en":"Feature","vi":"Tính năng"}',     3,
       '{"version":1,"natural_key_attributes":["display_name"]}', false, true),
      (t, 'REPOSITORY',  '{"en":"Git repository","vi":"Repository"}', 4,
       '{"version":1,"natural_key_attributes":["display_name"]}', false, true),
      -- A domain is network-reachable and carries findings, so it is an ASSET and not a text column:
      -- a finding raised against a host has to attach to something, and two projects on one host must
      -- be two edges to ONE domain or reachability is invisible (ADR-061).
      (t, 'DOMAIN',      '{"en":"Domain","vi":"Tên miền"}',       5,
       '{"version":1,"natural_key_attributes":["display_name"]}', true,  true)
    ON CONFLICT DO NOTHING;

    -- --------------------------------------------------------------------------------------------
    -- 7. Endpoint environments (ADR-061, CFG-AST-002).
    --
    -- Which environments the platform asks for a host in. V069 backfills existing tenants and this
    -- tenant does not exist when the migrations run, so it is seeded here too. An ACTIVE environment
    -- gets its inventory column even with nothing recorded in it — "no UAT host recorded against this
    -- project" is an answer, and that clause is the load-bearing half of the requirement.
    -- --------------------------------------------------------------------------------------------
    INSERT INTO asset_endpoint_environment (tenant_id, code, label_i18n, purpose, ordinal)
    VALUES (t, 'PRODUCTION', '{"en":"Production","vi":"Production"}'::jsonb,
            'The host real users reach. A finding here is exploitable by whoever can reach the host, '
            'which for an internet-facing application is everybody.', 10),
           (t, 'UAT', '{"en":"UAT","vi":"UAT"}'::jsonb,
            'The host acceptance testing runs against. Routinely holds a copy of production data '
            'behind weaker controls — no WAF, default credentials, debug endpoints left enabled — so '
            'it is frequently the cheapest route to the same records.', 20),
           (t, 'STAGING', '{"en":"Staging","vi":"Staging"}'::jsonb,
            'The host a release is verified on before production. Usually reachable by more people '
            'than production and watched by fewer.', 30)
    ON CONFLICT (tenant_id, code) DO NOTHING;

    -- --------------------------------------------------------------------------------------------
    -- 8. One assessment type, because a workflow definition has to belong to one.
    --
    -- FOUND BY RUNNING THE DOCUMENTED SEED PATH ON AN EMPTY DATABASE: nothing in this directory or in
    -- any migration creates an `assessment_type`. The demo tenant has one — code PENTEST, id
    -- cccccccc-… — that seed-workflow.sql references as a literal and no file inserts, so it was put
    -- there by hand. Every fresh tenant therefore failed at
    -- `fk_workflow_definition__assessment_type_id__tenant`, which is to say the workflow could not be
    -- seeded at all and no request transition would have been defined.
    --
    -- PENTEST is a default, not a product entity (ADR-027): the code, label and payload schema are
    -- tenant data, and a tenant that runs threat models or code reviews as distinct types adds rows.
    -- --------------------------------------------------------------------------------------------
    INSERT INTO assessment_type (tenant_id, code, label_i18n, payload_schema, requires_request)
    VALUES (t, 'PENTEST', '{"en":"Penetration test","vi":"Kiểm thử xâm nhập"}'::jsonb, '{}'::jsonb,
            true)
    ON CONFLICT DO NOTHING;

    -- --------------------------------------------------------------------------------------------
    -- 9. Password policy. The defaults the column definitions carry; change them at Access.
    -- --------------------------------------------------------------------------------------------
    INSERT INTO password_policy (tenant_id) VALUES (t) ON CONFLICT DO NOTHING;

    -- --------------------------------------------------------------------------------------------
    -- 10. One administrator, and one role for them to hold.
    --
    -- ADMIN holds every permission in the catalogue, which is why it is the only role seeded: the
    -- first person needs to be able to create the others, and every role after this one is a decision
    -- about your organization that a seed must not make for you (ADR-027).
    --
    -- The permission catalogue must already be populated — run seed-identity.sql first. If it is
    -- empty this role gets no permissions and the FK is what tells you.
    -- --------------------------------------------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM permission_catalogue) THEN
        RAISE EXCEPTION 'the permission catalogue is empty. Run seed-identity.sql first: 32 of the '
            'product-fixed permissions live in that file rather than in a migration, and a role '
            'composed against an empty catalogue is a role that authorizes nothing.';
    END IF;

    INSERT INTO role (tenant_id, code, label_i18n, description, derived_from_template)
    VALUES (t, 'ADMIN', '{"en":"Administrator","vi":"Quản trị"}',
            'Every permission in the catalogue. Seeded so the first person can compose the roles '
            'this deployment actually needs.', 'ADMIN')
    ON CONFLICT DO NOTHING;
    SELECT id INTO role_admin FROM role WHERE tenant_id = t AND code = 'ADMIN';

    INSERT INTO role_permission (tenant_id, role_id, permission_code)
    SELECT t, role_admin, code FROM permission_catalogue
    ON CONFLICT DO NOTHING;

    INSERT INTO principal (id, tenant_id, kind, username, email, display_name, lifecycle_state,
                           must_change_password)
    SELECT admin_id, t, 'HUMAN', current_setting('aspm.seed_admin_user'), current_setting('aspm.seed_admin_email'), current_setting('aspm.seed_admin_name'), 'ACTIVE',
           -- true, and it is not optional: this account's first password comes from
           -- ASPM_BOOTSTRAP_PASSWORD, an environment variable, and OPS-DEP-020 treats an environment
           -- variable as exposed.
           true
     WHERE NOT EXISTS (SELECT 1 FROM principal WHERE tenant_id = t AND username = current_setting('aspm.seed_admin_user'));
    SELECT id INTO admin_id FROM principal WHERE tenant_id = t AND username = current_setting('aspm.seed_admin_user');

    -- TENANT scope, not SUBTREE: the first administrator has to be able to see a tree that does not
    -- exist yet, including the nodes they are about to create outside any subtree they were given.
    INSERT INTO role_assignment (tenant_id, principal_id, role_id, scope_node_id, scope_mode)
    VALUES (t, admin_id, role_admin, NULL, 'TENANT')
    ON CONFLICT DO NOTHING;

    SELECT count(*) INTO tiers FROM criticality_tier WHERE tenant_id = t;
    RAISE NOTICE 'bootstrap: tenant %, % node type(s), % tier(s), % asset type(s), % environment(s), '
                 'administrator %',
        t,
        (SELECT count(*) FROM org_node_type WHERE tenant_id = t),
        tiers,
        (SELECT count(*) FROM asset_type WHERE tenant_id = t),
        (SELECT count(*) FROM asset_endpoint_environment WHERE tenant_id = t),
        current_setting('aspm.seed_admin_user');
    RAISE NOTICE 'bootstrap: NO asset, finding or assessment request was created. Every count this '
                 'platform reports is now zero because nothing has been measured, which is the only '
                 'honest starting state (product principle 1).';
END
$bootstrap$;
