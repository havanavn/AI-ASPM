-- =============================================================================
-- Composition: the FEATURE asset type, the declared security attributes, and demo features.
--
-- The attribute set below is the answer to "what else does an ASPM record need". Every one of them
-- is here because it changes a decision, and the `purpose` column says which decision — a field
-- whose purpose nobody can state is a field people fill in wrongly and then filter on.
--
-- What is deliberately NOT an attribute, because it is already first-class and duplicating it would
-- give two answers to one question:
--
--   internet-facing        asset.exposure_declared, with the observed value and conflict flag
--   business criticality   asset.criticality_tier_id, inheritable from the owning node
--   SBOM / SCA link        sbom_coverage_state and sbom_snapshot, keyed by asset
--   open vulnerability counts   asset_finding_tally and application_posture (V019)
--   owner                  asset.owning_node_id and technical_contact_id
-- =============================================================================

\set ON_ERROR_STOP on

DO $seed$
DECLARE
    t         uuid := '11111111-1111-1111-1111-111111111111';
    at_app    uuid;
    at_feat   uuid;
    at_svc    uuid;
    app_api   uuid;
    app_portal uuid;
    app_policy uuid;
    f_authz   uuid;
    f_refund  uuid;
    f_recon   uuid;
    f_quote   uuid;
    n_pay_api uuid;
    n_portal  uuid;
    n_policy  uuid;
BEGIN
    PERFORM set_config('aspm.current_tenant', t::text, true);

    SELECT id INTO at_app  FROM asset_type WHERE tenant_id = t AND code = 'APPLICATION';
    SELECT id INTO at_svc  FROM asset_type WHERE tenant_id = t AND code = 'SERVICE';

    -- -------------------------------------------------------------------------------------------
    -- 1. FEATURE.
    --
    -- may_carry_findings = true: a pentest report says "the refund flow allows negative amounts",
    -- and that finding belongs to the feature, not to whichever service happened to expose it.
    -- is_network_reachable = false: a feature is not a host. Nothing scans a feature.
    -- -------------------------------------------------------------------------------------------
    SELECT id INTO at_feat FROM asset_type WHERE tenant_id = t AND code = 'FEATURE';
    IF at_feat IS NULL THEN
        at_feat := uuidv7();
        INSERT INTO asset_type (id, tenant_id, code, label_i18n, ordinal, identity_rule,
                                is_network_reachable, may_carry_findings)
        VALUES (at_feat, t, 'FEATURE', '{"en":"Feature","vi":"Tính năng"}', 1,
                '{"version":1,"natural_key_attributes":["display_name"]}', false, true);
    END IF;

    -- -------------------------------------------------------------------------------------------
    -- 2. The declared attributes.
    -- -------------------------------------------------------------------------------------------
    INSERT INTO asset_attribute_definition
        (tenant_id, asset_type_id, attribute_key, label_i18n, data_type, permitted_values,
         filterable, required, purpose, ordinal)
    VALUES
      -- ---- APPLICATION ----
      (t, at_app, 'description', '{"en":"Description","vi":"Mô tả"}', 'LONG_TEXT', '{}',
       false, false,
       'What the application does, in one sentence, for somebody triaging an advisory at 2am.', 1),
      (t, at_app, 'user_base', '{"en":"Who uses it","vi":"Ai đang dùng"}', 'TEXT', '{}',
       false, false,
       'Who is harmed if this is breached. Customer-facing and staff-only fail very differently.', 2),
      (t, at_app, 'deployment_model', '{"en":"Deployment","vi":"Mô hình triển khai"}',
       'SINGLE_SELECT', ARRAY['ON_PREM','PRIVATE_CLOUD','PUBLIC_CLOUD','SAAS','HYBRID'],
       true, false,
       'Determines who can actually apply a patch, and how fast.', 3),
      (t, at_app, 'compliance_scope', '{"en":"Compliance scope","vi":"Phạm vi tuân thủ"}',
       'MULTI_SELECT', ARRAY['PCI_DSS','GDPR','PDPD_VN','ISO_27001','SOC_2','NONE'],
       true, false,
       'Drives assessment cadence, evidence retention, and whether a finding has a regulatory clock '
       'as well as an internal one.', 4),
      (t, at_app, 'third_party', '{"en":"Third-party supplied","vi":"Do bên thứ ba cung cấp"}',
       'BOOLEAN', '{}', true, false,
       'You cannot patch somebody else''s code. A third-party application''s remediation path is a '
       'vendor conversation, and planning it as an engineering task wastes the window.', 5),

      -- ---- FEATURE ----
      (t, at_feat, 'description', '{"en":"Description","vi":"Mô tả"}', 'LONG_TEXT', '{}',
       false, false, 'What this feature does.', 1),
      (t, at_feat, 'data_classification', '{"en":"Data handled","vi":"Loại dữ liệu xử lý"}',
       'SINGLE_SELECT',
       ARRAY['PUBLIC','INTERNAL','CONFIDENTIAL','PII','PAYMENT_CARD','CREDENTIALS','HEALTH'],
       true, true,
       'The blast radius of a breach here. The single most useful field for prioritising between two '
       'findings of equal severity.', 2),
      (t, at_feat, 'authentication', '{"en":"Authentication","vi":"Xác thực"}', 'SINGLE_SELECT',
       ARRAY['NONE','API_KEY','BASIC','SESSION_COOKIE','OIDC_OAUTH2','MTLS','INTERNAL_ONLY'],
       true, true,
       'An unauthenticated feature reachable from the internet is the shortest path to everything '
       'behind it.', 3),

      -- ---- SERVICE ----
      (t, at_svc, 'description', '{"en":"Description","vi":"Mô tả"}', 'LONG_TEXT', '{}',
       false, false, 'What this service is responsible for.', 1),
      (t, at_svc, 'tech_stack', '{"en":"Technology stack","vi":"Công nghệ"}', 'MULTI_SELECT',
       ARRAY['JAVA','KOTLIN','NODEJS','PYTHON','GO','DOTNET','PHP','RUBY','RUST',
             'REACT','ANGULAR','VUE','SPRING','DJANGO','EXPRESS'],
       true, false,
       'Which advisories apply to it at all. Without this, every dependency advisory has to be '
       'checked against every service by hand.', 2),
      (t, at_svc, 'authentication', '{"en":"Authentication","vi":"Xác thực"}', 'SINGLE_SELECT',
       ARRAY['NONE','API_KEY','BASIC','SESSION_COOKIE','OIDC_OAUTH2','MTLS','INTERNAL_ONLY'],
       true, true,
       'How a caller proves who they are. NONE on an internet-facing service is a finding in itself.', 3),
      (t, at_svc, 'data_classification', '{"en":"Data handled","vi":"Loại dữ liệu xử lý"}',
       'SINGLE_SELECT',
       ARRAY['PUBLIC','INTERNAL','CONFIDENTIAL','PII','PAYMENT_CARD','CREDENTIALS','HEALTH'],
       true, true, 'The blast radius of a breach in this service.', 4),
      (t, at_svc, 'runtime_platform', '{"en":"Runtime","vi":"Nền tảng chạy"}', 'SINGLE_SELECT',
       ARRAY['KUBERNETES','VM','SERVERLESS','CONTAINER_HOST','MANAGED_PAAS','BARE_METAL'],
       true, false,
       'Where a fix has to be deployed, and which infrastructure controls are even available.', 5),
      (t, at_svc, 'internet_entrypoint', '{"en":"Receives internet traffic directly","vi":"Nhận traffic Internet trực tiếp"}',
       'BOOLEAN', '{}', true, false,
       'Distinct from the application''s exposure: an internal service behind an internet-facing '
       'gateway is not itself an entry point, and treating it as one inflates every count.', 6)
    ON CONFLICT (tenant_id, asset_type_id, attribute_key) DO NOTHING;

    -- -------------------------------------------------------------------------------------------
    -- 3. Demo features, sitting between the applications and the services that already exist.
    -- -------------------------------------------------------------------------------------------
    SELECT id INTO app_api    FROM asset WHERE tenant_id = t AND type_id = at_app AND identity_key = 'payments api';
    SELECT id INTO app_portal FROM asset WHERE tenant_id = t AND type_id = at_app AND identity_key = 'payments portal';
    SELECT id INTO app_policy FROM asset WHERE tenant_id = t AND type_id = at_app AND identity_key = 'policy core';
    SELECT owning_node_id INTO n_pay_api FROM asset WHERE id = app_api;
    SELECT owning_node_id INTO n_portal  FROM asset WHERE id = app_portal;
    SELECT owning_node_id INTO n_policy  FROM asset WHERE id = app_policy;

    INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, display_name,
                       owning_node_id, criticality_mode, lifecycle_state, attributes,
                       discovery_source, discovery_method, first_seen_at, last_confirmed_at)
    VALUES
      (t, at_feat, 'card authorization', 1, 'Card authorization', n_pay_api, 'INHERITED', 'ACTIVE',
       '{"description":"Authorizes card transactions against the issuer.",
         "data_classification":"PAYMENT_CARD","authentication":"MTLS"}'::jsonb,
       'MANUAL', 'INVENTORY_FORM', now(), now()),
      (t, at_feat, 'refunds', 1, 'Refunds', n_pay_api, 'INHERITED', 'ACTIVE',
       '{"description":"Full and partial refunds against a prior authorization.",
         "data_classification":"PAYMENT_CARD","authentication":"OIDC_OAUTH2"}'::jsonb,
       'MANUAL', 'INVENTORY_FORM', now(), now()),
      (t, at_feat, 'reconciliation', 1, 'Reconciliation', n_portal, 'INHERITED', 'ACTIVE',
       '{"description":"Daily settlement matching for operations staff.",
         "data_classification":"CONFIDENTIAL","authentication":"SESSION_COOKIE"}'::jsonb,
       'MANUAL', 'INVENTORY_FORM', now(), now()),
      (t, at_feat, 'quoting', 1, 'Quoting', n_policy, 'INHERITED', 'ACTIVE',
       '{"description":"Premium calculation and quote issue.",
         "data_classification":"PII","authentication":"SESSION_COOKIE"}'::jsonb,
       'MANUAL', 'INVENTORY_FORM', now(), now())
    ON CONFLICT DO NOTHING;

    SELECT id INTO f_authz  FROM asset WHERE tenant_id = t AND type_id = at_feat AND identity_key = 'card authorization';
    SELECT id INTO f_refund FROM asset WHERE tenant_id = t AND type_id = at_feat AND identity_key = 'refunds';
    SELECT id INTO f_recon  FROM asset WHERE tenant_id = t AND type_id = at_feat AND identity_key = 'reconciliation';
    SELECT id INTO f_quote  FROM asset WHERE tenant_id = t AND type_id = at_feat AND identity_key = 'quoting';

    -- Attributes on the services that already exist, so the composition table has something to show
    -- in every column rather than a page of dashes.
    UPDATE asset SET attributes = attributes || '{"tech_stack":["JAVA","SPRING"],"authentication":"MTLS",
        "data_classification":"PAYMENT_CARD","runtime_platform":"KUBERNETES",
        "internet_entrypoint":true,
        "description":"Issuer-facing authorization service."}'::jsonb
     WHERE tenant_id = t AND display_name = 'payments-authorization';
    UPDATE asset SET attributes = attributes || '{"tech_stack":["NODEJS","REACT"],"authentication":"SESSION_COOKIE",
        "data_classification":"CONFIDENTIAL","runtime_platform":"KUBERNETES",
        "internet_entrypoint":false,
        "description":"Back-office console."}'::jsonb
     WHERE tenant_id = t AND display_name = 'payments-admin';
    UPDATE asset SET attributes = attributes || '{"tech_stack":["JAVA","SPRING"],"authentication":"SESSION_COOKIE",
        "data_classification":"PII","runtime_platform":"VM","internet_entrypoint":false,
        "description":"Policy administration core."}'::jsonb
     WHERE tenant_id = t AND display_name = 'policy-core';
    UPDATE asset SET attributes = attributes || '{"compliance_scope":["PCI_DSS","PDPD_VN"],
        "deployment_model":"PRIVATE_CLOUD","third_party":false}'::jsonb
     WHERE id = app_api;
    UPDATE asset SET attributes = attributes || '{"compliance_scope":["PCI_DSS"],
        "deployment_model":"PRIVATE_CLOUD","third_party":false}'::jsonb
     WHERE id = app_portal;
    UPDATE asset SET attributes = attributes || '{"compliance_scope":["PDPD_VN","ISO_27001"],
        "deployment_model":"ON_PREM","third_party":false}'::jsonb
     WHERE id = app_policy;

    -- -------------------------------------------------------------------------------------------
    -- 4. Re-hang the services under their features.
    --
    -- The direct application -> service edges are CLOSED rather than deleted (INV-AST-16 rejects
    -- reopening an edge and there is no DELETE grant): "what was part of what, when" stays
    -- answerable, which is what a retest six months from now has to ask.
    -- -------------------------------------------------------------------------------------------
    UPDATE asset_relationship SET valid_until = now()
     WHERE tenant_id = t AND valid_until IS NULL AND edge_type = 'CONTAINS'
       AND from_asset_id IN (app_api, app_portal, app_policy)
       AND to_asset_id IN (SELECT id FROM asset WHERE tenant_id = t AND type_id = at_svc);

    INSERT INTO asset_relationship (tenant_id, from_asset_id, to_asset_id, edge_type,
                                    discovery_source, attributes, valid_from)
    SELECT t, e.f, e.tt, 'CONTAINS', 'MANUAL', '{}'::jsonb, now()
      FROM (VALUES
        (app_api,    f_authz),
        (app_api,    f_refund),
        (app_portal, f_recon),
        (app_policy, f_quote),
        (f_authz,  (SELECT id FROM asset WHERE tenant_id = t AND display_name = 'payments-authorization')),
        (f_refund, (SELECT id FROM asset WHERE tenant_id = t AND display_name = 'payments-authorization')),
        (f_recon,  (SELECT id FROM asset WHERE tenant_id = t AND display_name = 'payments-admin')),
        (f_quote,  (SELECT id FROM asset WHERE tenant_id = t AND display_name = 'policy-core'))
      ) AS e(f, tt)
     WHERE e.f IS NOT NULL AND e.tt IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM asset_relationship r
                        WHERE r.from_asset_id = e.f AND r.to_asset_id = e.tt
                          AND r.edge_type = 'CONTAINS' AND r.valid_until IS NULL);

    RAISE NOTICE 'attribute definitions: %, features: %, current edges: %',
        (SELECT count(*) FROM asset_attribute_definition WHERE tenant_id = t),
        (SELECT count(*) FROM asset a WHERE a.type_id = at_feat),
        (SELECT count(*) FROM asset_relationship WHERE tenant_id = t AND valid_until IS NULL);
END
$seed$;
