-- =============================================================================
-- Application inventory: the APPLICATION asset type, three applications, and the edges that make
-- them a graph rather than three rows.
--
-- It also REPAIRS three defects in the demo organization data. Tenant data, so this is a seed script
-- and not a migration: a migration that rewrote a tenant's hierarchy would run against every
-- deployment, including ones where these codes mean something else.
--
-- ---------------------------------------------------------------------------------------------
-- THE DEFECT THIS FIXES, AND WHY IT MATTERED
--
-- The demo used PROJECT nodes as stand-ins for applications, because there was no APPLICATION asset
-- type. So "Payments API" was an organization node that owned a service, a domain and a repository —
-- and the obvious next step was to create an APPLICATION also called "Payments API", which would have
-- been the same real-world thing represented twice.
--
-- ADR-001 keeps two orthogonal structures for a reason: the ORG TREE answers "who is accountable and
-- who may see this", the ASSET GRAPH answers "what exists and what has weaknesses in it". A project
-- node standing in for an application collapses them, and the collapse shows up later as a scope
-- question nobody can answer: revoking someone's access to a team should not revoke their access to
-- an application that outlived the team.
--
-- So the org nodes are renamed to what they are — the teams accountable — and the applications become
-- assets under them. No name is duplicated afterwards.
-- =============================================================================

\set ON_ERROR_STOP on

DO $seed$
DECLARE
    t           uuid := '11111111-1111-1111-1111-111111111111';
    at_app      uuid;
    n_pay_api   uuid;
    n_portal    uuid;
    n_policy    uuid;
    tier1       uuid;
    tier2       uuid;
    app_api     uuid;
    app_portal  uuid;
    app_policy  uuid;
    orphan      uuid;
    orphan_nodes int;
BEGIN
    PERFORM set_config('aspm.current_tenant', t::text, true);

    -- -------------------------------------------------------------------------------------------
    -- 1. Ordinals that collide.
    --
    -- DIVISION and BUSINESS_UNIT were both ordinal 1; BUSINESSUNIT and PRODUCT were both 2. The
    -- ordinal is what every picker and every tree render sorts by, so a collision means two levels
    -- appear in an order the database chooses — which is stable until it is not, and then a
    -- hierarchy renders inside out with no code change to blame.
    -- -------------------------------------------------------------------------------------------
    UPDATE org_node_type SET ordinal = 1, updated_at = now() WHERE tenant_id = t AND code = 'DIVISION';
    UPDATE org_node_type SET ordinal = 2, updated_at = now() WHERE tenant_id = t AND code = 'BUSINESS_UNIT';
    UPDATE org_node_type SET ordinal = 3, updated_at = now() WHERE tenant_id = t AND code = 'PRODUCT';
    UPDATE org_node_type SET ordinal = 4, updated_at = now() WHERE tenant_id = t AND code = 'PROJECT';

    -- -------------------------------------------------------------------------------------------
    -- 2. The duplicate node type.
    --
    -- BUSINESS_UNIT and BUSINESSUNIT differ only by an underscore. Nobody can tell them apart in a
    -- picker, and assets end up split across both — a split that stays invisible until a scope query
    -- returns half a business unit.
    --
    -- Guarded on having NO nodes. Deprecating a type that is in use would leave those nodes typed by
    -- something the tree no longer offers; if the guard fails, the merge is a data migration that
    -- moves every node, and it is not something a seed script should do behind somebody's back.
    -- -------------------------------------------------------------------------------------------
    SELECT id INTO orphan FROM org_node_type WHERE tenant_id = t AND code = 'BUSINESSUNIT';
    IF orphan IS NOT NULL THEN
        SELECT count(*) INTO orphan_nodes FROM org_node WHERE type_id = orphan;
        IF orphan_nodes = 0 THEN
            DELETE FROM org_node_type WHERE id = orphan;
            RAISE NOTICE 'removed the duplicate node type BUSINESSUNIT (it had no nodes)';
        ELSE
            RAISE WARNING 'BUSINESSUNIT has % node(s) and was left alone. Merging it into '
                          'BUSINESS_UNIT moves those nodes and every grant scoped to them, which is '
                          'a migration rather than a seed step.', orphan_nodes;
        END IF;
    END IF;

    -- -------------------------------------------------------------------------------------------
    -- 3. Which levels may own an asset.
    --
    -- Only PROJECT could, so every application had to be filed under the deepest level whether or not
    -- the tenant manages it there. A business unit that owns an application directly is an ordinary
    -- arrangement, and forcing an intermediate node to exist for filing purposes is exactly the
    -- pointless step a person then works around by inventing a fake project.
    --
    -- DIVISION stays false: a division scopes work and does not hold systems. That is a judgement
    -- about this tenant's shape, and it is theirs to change on the node type editor.
    -- -------------------------------------------------------------------------------------------
    UPDATE org_node_type SET may_own_assets = true, updated_at = now()
     WHERE tenant_id = t AND code IN ('BUSINESS_UNIT', 'PRODUCT', 'PROJECT');

    -- -------------------------------------------------------------------------------------------
    -- 4. The project nodes, renamed to what they are.
    --
    -- Their identifiers do not change, so the grant scoping the demo developer to one of them still
    -- points at the same row — a rename is a label change and nothing references the label.
    -- -------------------------------------------------------------------------------------------
    UPDATE org_node SET name = 'Payments Platform Team', updated_at = now()
     WHERE tenant_id = t AND name = 'Payments API';
    UPDATE org_node SET name = 'Payments Operations Team', updated_at = now()
     WHERE tenant_id = t AND name = 'Payments Portal';
    UPDATE org_node SET name = 'Policy Systems Team', updated_at = now()
     WHERE tenant_id = t AND name = 'Policy Core';

    SELECT id INTO n_pay_api FROM org_node WHERE tenant_id = t AND name = 'Payments Platform Team';
    SELECT id INTO n_portal  FROM org_node WHERE tenant_id = t AND name = 'Payments Operations Team';
    SELECT id INTO n_policy  FROM org_node WHERE tenant_id = t AND name = 'Policy Systems Team';
    SELECT id INTO tier1 FROM criticality_tier WHERE code = 'TIER1';
    SELECT id INTO tier2 FROM criticality_tier WHERE code = 'TIER2';

    -- -------------------------------------------------------------------------------------------
    -- 5. The APPLICATION asset type.
    --
    -- Network-reachable and may carry findings: an application is a thing an assessment is run
    -- against, so a finding has to be able to name it.
    -- -------------------------------------------------------------------------------------------
    SELECT id INTO at_app FROM asset_type WHERE code = 'APPLICATION' AND tenant_id = t;
    IF at_app IS NULL THEN
        at_app := uuidv7();
        INSERT INTO asset_type (id, tenant_id, code, label_i18n, ordinal, identity_rule,
                                is_network_reachable, may_carry_findings)
        VALUES (at_app, t, 'APPLICATION', '{"en":"Application","vi":"Ứng dụng"}', 0,
                '{"version":1,"natural_key_attributes":["display_name"]}', true, true);
    END IF;

    -- -------------------------------------------------------------------------------------------
    -- 6. The applications.
    -- -------------------------------------------------------------------------------------------
    INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, display_name,
                       owning_node_id, criticality_mode, criticality_tier_id, exposure_declared,
                       exposure_declared_at, lifecycle_state, attributes, tags,
                       discovery_source, discovery_method, first_seen_at, last_confirmed_at)
    VALUES
      (t, at_app, 'payments api', 1, 'Payments API', n_pay_api, 'ASSIGNED', tier1,
       'INTERNET_PUBLIC', now(), 'ACTIVE',
       '{"description":"Card and transfer authorization for retail customers and partner merchants.",
         "user_base":"Retail customers, partner merchants",
         "features":"Authorization, Refunds, Tokenization, Webhooks"}'::jsonb,
       ARRAY['pci','payments'], 'MANUAL', 'INVENTORY_FORM', now(), now()),
      (t, at_app, 'payments portal', 1, 'Payments Portal', n_portal, 'ASSIGNED', tier2,
       'INTERNAL_ONLY', now(), 'ACTIVE',
       '{"description":"Back-office console for payment operations staff.",
         "user_base":"Payment operations staff",
         "features":"Reconciliation, Chargebacks, Merchant onboarding"}'::jsonb,
       ARRAY['internal','payments'], 'MANUAL', 'INVENTORY_FORM', now(), now()),
      (t, at_app, 'policy core', 1, 'Policy Core', n_policy, 'INHERITED', NULL,
       'INTERNAL_ONLY', now(), 'ACTIVE',
       '{"description":"Policy administration for the insurance book.",
         "user_base":"Underwriters, claims handlers",
         "features":"Quoting, Endorsements, Renewals"}'::jsonb,
       ARRAY['insurance'], 'MANUAL', 'INVENTORY_FORM', now(), now())
    ON CONFLICT DO NOTHING;

    SELECT id INTO app_api    FROM asset WHERE tenant_id = t AND type_id = at_app AND identity_key = 'payments api';
    SELECT id INTO app_portal FROM asset WHERE tenant_id = t AND type_id = at_app AND identity_key = 'payments portal';
    SELECT id INTO app_policy FROM asset WHERE tenant_id = t AND type_id = at_app AND identity_key = 'policy core';

    -- -------------------------------------------------------------------------------------------
    -- 7. The edges — reusing the services, domains and repository that ALREADY EXIST.
    --
    -- This is the point of the whole exercise. pay.example.com is not a text field on the Payments
    -- API row; it is the domain asset that was already in the inventory, now attached by an edge. A
    -- finding raised against that host attaches to the host, and the application detail page reaches
    -- it by traversal.
    -- -------------------------------------------------------------------------------------------
    -- -------------------------------------------------------------------------------------------
    -- The two pre-production hosts.
    --
    -- HERE RATHER THAN BESIDE THE OTHER DOMAIN ASSETS IN seed-demo.sql, and the reason is worth
    -- recording: that file is one DO block and no longer replays — it writes an assessment request in
    -- state DRAFT, which the workflow trigger added later rejects because states are tenant data and
    -- this tenant's workflow does not define one by that name. So anything added there cannot be
    -- applied to an existing deployment at all, and the edges below would silently skip on their
    -- IS NOT NULL guard, which reads as "the seed ran and the estate has no UAT host".
    --
    -- The scope columns are copied from a host that already carries them rather than re-derived, so
    -- this cannot disagree with the row it sits beside about which node owns the branch.
    -- -------------------------------------------------------------------------------------------
    INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, display_name,
                       owning_node_id, criticality_mode, criticality_tier_id, exposure_declared,
                       exposure_declared_by, exposure_declared_at, exposure_observed,
                       exposure_observed_source, exposure_observed_at, lifecycle_state, tags,
                       technical_contact_id, discovery_source, discovery_method, first_seen_at,
                       last_confirmed_at, scope_node_id, scope_ancestor_path, scope_node_type_id,
                       scope_criticality_id, scope_hierarchy_ver, scope_resolved_at)
    SELECT t, src.type_id, v.host, 1, v.host,
           src.owning_node_id, src.criticality_mode, src.criticality_tier_id, 'INTERNAL_ONLY',
           src.exposure_declared_by, now() - interval '20 days', v.observed,
           'external-scan', now(), 'ACTIVE', ARRAY['pre-production'],
           src.technical_contact_id, v.source, v.method, now() - interval '60 days',
           now(), src.scope_node_id, src.scope_ancestor_path, src.scope_node_type_id,
           src.scope_criticality_id, src.scope_hierarchy_ver, now()
      FROM asset src
      CROSS JOIN (VALUES
        -- Acceptance testing. Genuinely reachable only from inside, which is the case a reader
        -- should not confuse with the next one.
        ('uat.payments.example.internal', 'INTERNAL_ONLY',  'MANUAL',    'ONBOARDING'),
        -- Release verification, declared internal and OBSERVED internet-facing. This is why a
        -- pre-production column is worth having: it is the finding the inventory exists to surface,
        -- and until ADR-061 it was unrecordable on an application and invisible in every column.
        -- The conflict flag is computed by a trigger, so it is not passed here.
        ('stg.payments.example.internal', 'INTERNET_PUBLIC', 'CONNECTOR', 'DNS_ENUMERATION')
      ) AS v(host, observed, source, method)
     WHERE src.tenant_id = t AND src.display_name = 'pay.example.com'
       AND NOT EXISTS (SELECT 1 FROM asset a
                        WHERE a.tenant_id = t AND a.identity_key = v.host);

    -- -------------------------------------------------------------------------------------------
    -- The endpoint environment catalogue. Tenant vocabulary, not code (ADR-061, CFG-AST-002).
    --
    -- HERE RATHER THAN IN THE MIGRATION ALONE. V069 backfills every EXISTING tenant, and this
    -- tenant does not exist when the migrations run — the same trap seed-tenant.sql documents, where
    -- `FOR t IN SELECT id FROM tenant` iterates zero times and reports success. A tenant with no
    -- rows here gets no domain input on either inventory editor and no domain column on either list,
    -- which is correct behaviour for "declared nothing" and a poor way to arrive out of the box.
    --
    -- Three rows, and they are DEFAULTS rather than product vocabulary (PP-3): they reproduce the
    -- two lists that used to be compiled into the two editors. Rename, reorder or retire them at
    -- Configuration -> Asset fields; nothing in code reads a code below by name.
    -- -------------------------------------------------------------------------------------------
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

    INSERT INTO asset_relationship (tenant_id, from_asset_id, to_asset_id, edge_type,
                                    discovery_source, attributes, valid_from)
    SELECT t, e.from_id, e.to_id, e.edge, 'MANUAL', e.attrs::jsonb, now()
      FROM (VALUES
        (app_api,    (SELECT id FROM asset WHERE tenant_id = t AND display_name = 'pay.example.com'),
         'PUBLISHED_ON', '{"environment":"PRODUCTION"}'),
        -- Pre-production, on the same application. Until ADR-061 these two edges were unwritable from
        -- any form: the application editor named PRODUCTION and STAGING, the project editor named
        -- PRODUCTION and UAT, and the domain columns were derived from recorded data alone — so an
        -- environment with no write path never became a column and never appeared anywhere.
        (app_api,    (SELECT id FROM asset WHERE tenant_id = t
                       AND display_name = 'uat.payments.example.internal'),
         'PUBLISHED_ON', '{"environment":"UAT"}'),
        (app_api,    (SELECT id FROM asset WHERE tenant_id = t
                       AND display_name = 'stg.payments.example.internal'),
         'PUBLISHED_ON', '{"environment":"STAGING"}'),
        (app_api,    (SELECT id FROM asset WHERE tenant_id = t AND display_name = 'group/payments-api'),
         'BUILDS',       '{}'),
        (app_api,    (SELECT id FROM asset WHERE tenant_id = t AND display_name = 'payments-authorization'),
         'CONTAINS',     '{}'),
        (app_portal, (SELECT id FROM asset WHERE tenant_id = t AND display_name = 'payments-admin'),
         'CONTAINS',     '{}'),
        (app_policy, (SELECT id FROM asset WHERE tenant_id = t AND display_name = 'policy-core'),
         'CONTAINS',     '{}')
      ) AS e(from_id, to_id, edge, attrs)
     WHERE e.from_id IS NOT NULL AND e.to_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM asset_relationship r
                        WHERE r.from_asset_id = e.from_id AND r.to_asset_id = e.to_id
                          AND r.edge_type = e.edge AND r.valid_until IS NULL);

    -- The two unowned assets are LEFT unowned, deliberately. A discovered asset nobody has claimed is
    -- a real and important state — it is what an ownership claim exists to resolve — and quietly
    -- assigning them to a node to make a list look tidy would erase the one signal that says somebody
    -- needs to go and find out whose they are.

    RAISE NOTICE 'applications: %, edges: %, node types: %',
        (SELECT count(*) FROM asset a JOIN asset_type ty ON ty.id = a.type_id
          WHERE ty.code = 'APPLICATION'),
        (SELECT count(*) FROM asset_relationship WHERE valid_until IS NULL),
        (SELECT count(*) FROM org_node_type WHERE tenant_id = t);
END
$seed$;
