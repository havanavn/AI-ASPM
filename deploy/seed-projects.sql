-- =============================================================================
-- Projects: the level between an application and the parts it is built from.
--
-- WHY THIS IS A SEED AND NOT A MIGRATION
--
-- An asset type is TENANT DATA (ADR-009: one Asset aggregate with a type registry; ADR-027: no
-- hardcoded vocabulary). A deployment that has no projects, or that calls the same idea a workstream
-- or a squad backlog, needs no schema change and must not receive this row. A migration would run
-- against every deployment and impose one conglomerate's vocabulary on all of them.
--
-- WHAT IT MODELS
--
-- An application is delivered by several teams, each responsible for a branch of it. That branch is a
-- PROJECT: it carries findings, has an owner, has its own exposure and criticality, and is the unit an
-- assessment is actually requested against. The application above it is then DERIVED rather than
-- chosen — which is the point of the change this seed exists to demonstrate.
--
--   APPLICATION  Payments API           (asset, owned by the platform team)
--     └─CONTAINS─ PROJECT  Card authorization platform   (asset, owned by the platform team)
--          └─CONTAINS─ FEATURE / SERVICE / DOMAIN …      (already present)
--
-- ADR-001 stays intact. The ORG TREE says who is accountable — the project's owning node is the team.
-- The ASSET GRAPH says what exists. A project is a thing that exists and can be assessed, so it is an
-- asset; the team that runs it is an org node, and the two are joined by ownership rather than merged.
--
-- WHAT IT CHANGES IN EXISTING DATA, AND HOW TO UNDO IT
--
-- Each application previously contained its features directly. Those edges are CLOSED, not deleted —
-- asset_relationship is temporal and valid_until is how an edge ends, so the history of what
-- contained what is preserved and the composition view stops walking through them. To undo the whole
-- thing:
--
--   UPDATE asset_relationship SET valid_until = NULL
--    WHERE valid_until IS NOT NULL AND attributes ->> 'closed_by' = 'seed-projects';
--   DELETE FROM asset_relationship WHERE attributes ->> 'created_by_seed' = 'seed-projects';
--   DELETE FROM asset a USING asset_type t
--    WHERE t.id = a.type_id AND t.code = 'PROJECT';
--   DELETE FROM asset_type WHERE code = 'PROJECT';
--
-- Re-runnable: every statement is guarded, so applying it twice changes nothing the second time.
-- =============================================================================

\set ON_ERROR_STOP on

DO $seed$
DECLARE
    t            uuid := '11111111-1111-1111-1111-111111111111';
    at_domain    uuid;
    at_project   uuid;
    at_app       uuid;
    n_platform   uuid;
    n_operations uuid;
    n_policy     uuid;
    tier1        uuid;
    tier2        uuid;
    app_api      uuid;
    app_portal   uuid;
    app_policy   uuid;
    p_card       uuid;
    p_refunds    uuid;
    p_recon      uuid;
    p_quoting    uuid;
BEGIN
    PERFORM set_config('aspm.current_tenant', t::text, true);

    -- ---------------------------------------------------------------------------------------------
    -- The type. Ordinal 1 puts a project directly beneath an application in every ordered list;
    -- FEATURE and the rest shift down, which is what "a smaller branch" means in the inventory.
    -- ---------------------------------------------------------------------------------------------
    SELECT id INTO at_project FROM asset_type WHERE tenant_id = t AND code = 'PROJECT';
    IF at_project IS NULL THEN
        INSERT INTO asset_type (tenant_id, code, label_i18n, ordinal, identity_rule,
                                permitted_edges, is_network_reachable, may_carry_findings)
        VALUES (t, 'PROJECT',
                '{"en":"Project","vi":"Dự án"}'::jsonb, 1,
                '{"version": 1, "natural_key_attributes": ["display_name"]}'::jsonb,
                '[]'::jsonb,
                -- Not reachable itself. A project is a body of work; the services and domains under
                -- it are what an attacker can address, and marking the container reachable would
                -- inflate every exposure count by the number of projects.
                false,
                -- It carries findings. An assessment is requested against a project, and a finding
                -- that belongs to the project as a whole — a design flaw, a missing control — has
                -- nowhere else to live.
                true)
        RETURNING id INTO at_project;
    END IF;

    SELECT id INTO at_app FROM asset_type WHERE tenant_id = t AND code = 'APPLICATION';

    SELECT id INTO n_platform   FROM org_node WHERE tenant_id = t AND name = 'Payments Platform Team';
    SELECT id INTO n_operations FROM org_node WHERE tenant_id = t AND name = 'Payments Operations Team';
    SELECT id INTO n_policy     FROM org_node WHERE tenant_id = t AND name = 'Policy Systems Team';

    SELECT id INTO tier1 FROM criticality_tier WHERE tenant_id = t ORDER BY ordinal LIMIT 1;
    SELECT id INTO tier2 FROM criticality_tier WHERE tenant_id = t ORDER BY ordinal OFFSET 1 LIMIT 1;

    SELECT id INTO app_api    FROM asset WHERE tenant_id = t AND display_name = 'Payments API';
    SELECT id INTO app_portal FROM asset WHERE tenant_id = t AND display_name = 'Payments Portal';
    SELECT id INTO app_policy FROM asset WHERE tenant_id = t AND display_name = 'Policy Core';

    -- ---------------------------------------------------------------------------------------------
    -- Four projects across three applications, owned by three different teams. Two of them sit under
    -- ONE application and belong to DIFFERENT teams, because that is the case the whole change is
    -- for: an assessment of the refunds work is not an assessment of the card platform, and the two
    -- have different people answering for them.
    -- ---------------------------------------------------------------------------------------------
    INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, display_name,
                       owning_node_id, criticality_mode, criticality_tier_id, exposure_declared,
                       exposure_declared_at, lifecycle_state, attributes, tags,
                       discovery_source, discovery_method, first_seen_at, last_confirmed_at)
    SELECT t, at_project, v.key, 1, v.name, v.owner, 'ASSIGNED', v.tier, 'INTERNAL_ONLY',
           now(), 'ACTIVE', v.attributes, v.tags, 'MANUAL', 'INVENTORY_FORM', now(), now()
      FROM (VALUES
        ('card authorization platform', 'Card authorization platform', n_platform, tier1,
         '{"description":"Authorization, tokenization and the card rails behind them.",
           "delivery_team":"Payments Platform Team",
           "repository":"group/payments-api"}'::jsonb, ARRAY['pci','payments']),
        ('refunds and disputes', 'Refunds and disputes', n_operations, tier2,
         '{"description":"Refund initiation, dispute intake and the operator tooling for both.",
           "delivery_team":"Payments Operations Team"}'::jsonb, ARRAY['payments']),
        ('reconciliation', 'Reconciliation', n_operations, tier2,
         '{"description":"End-of-day settlement matching and the exception queue.",
           "delivery_team":"Payments Operations Team"}'::jsonb, ARRAY['payments']),
        ('policy quoting', 'Policy quoting', n_policy, tier2,
         '{"description":"Quote generation and the rating tables behind it.",
           "delivery_team":"Policy Systems Team"}'::jsonb, ARRAY['insurance'])
      ) AS v(key, name, owner, tier, attributes, tags)
     WHERE NOT EXISTS (SELECT 1 FROM asset a
                        WHERE a.tenant_id = t AND a.type_id = at_project
                          AND a.identity_key = v.key);

    SELECT id INTO p_card    FROM asset WHERE tenant_id = t AND type_id = at_project
                                          AND identity_key = 'card authorization platform';
    SELECT id INTO p_refunds FROM asset WHERE tenant_id = t AND type_id = at_project
                                          AND identity_key = 'refunds and disputes';
    SELECT id INTO p_recon   FROM asset WHERE tenant_id = t AND type_id = at_project
                                          AND identity_key = 'reconciliation';
    SELECT id INTO p_quoting FROM asset WHERE tenant_id = t AND type_id = at_project
                                          AND identity_key = 'policy quoting';

    -- ---------------------------------------------------------------------------------------------
    -- Application CONTAINS project.
    -- ---------------------------------------------------------------------------------------------
    INSERT INTO asset_relationship (tenant_id, from_asset_id, to_asset_id, edge_type,
                                    discovery_source, attributes, valid_from)
    SELECT t, v.parent, v.child, 'CONTAINS', 'MANUAL',
           '{"created_by_seed":"seed-projects"}'::jsonb, now()
      FROM (VALUES (app_api, p_card), (app_api, p_refunds),
                   (app_portal, p_recon), (app_policy, p_quoting)) AS v(parent, child)
     WHERE v.parent IS NOT NULL AND v.child IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM asset_relationship r
                        WHERE r.tenant_id = t AND r.from_asset_id = v.parent
                          AND r.to_asset_id = v.child AND r.edge_type = 'CONTAINS'
                          AND r.valid_until IS NULL);

    -- ---------------------------------------------------------------------------------------------
    -- Project CONTAINS what the application used to contain directly, then the old edge is closed.
    --
    -- Closed rather than deleted: valid_until is how a relationship ends, and "this feature used to
    -- hang off the application" is a fact somebody will need when they ask why a finding's path
    -- changed. The composition view walks only edges with valid_until IS NULL, so the tree is clean.
    -- ---------------------------------------------------------------------------------------------
    SELECT id INTO at_domain FROM asset_type WHERE tenant_id = t AND code = 'DOMAIN';

    INSERT INTO asset_relationship (tenant_id, from_asset_id, to_asset_id, edge_type,
                                    discovery_source, attributes, valid_from)
    SELECT t, v.project, a.id, 'CONTAINS', 'MANUAL',
           '{"created_by_seed":"seed-projects"}'::jsonb, now()
      FROM (VALUES (p_card, 'Card authorization'), (p_refunds, 'Refunds'),
                   (p_recon, 'Reconciliation'), (p_quoting, 'Quoting')) AS v(project, feature)
      JOIN asset a ON a.tenant_id = t AND a.display_name = v.feature AND a.id <> v.project
     WHERE v.project IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM asset_relationship r
                        WHERE r.tenant_id = t AND r.from_asset_id = v.project
                          AND r.to_asset_id = a.id AND r.edge_type = 'CONTAINS'
                          AND r.valid_until IS NULL);

    UPDATE asset_relationship r
       SET valid_until = now(),
           attributes = r.attributes || '{"closed_by":"seed-projects"}'::jsonb
      FROM asset parent, asset child
     WHERE r.tenant_id = t AND r.valid_until IS NULL AND r.edge_type = 'CONTAINS'
       AND parent.id = r.from_asset_id AND parent.type_id = at_app
       AND child.id = r.to_asset_id AND child.type_id <> at_project
       -- Only the ones a project now carries. An application that contains something no project has
       -- taken over keeps its direct edge, or the asset would fall out of the tree entirely.
       AND EXISTS (SELECT 1 FROM asset_relationship moved
                    WHERE moved.tenant_id = t AND moved.to_asset_id = child.id
                      AND moved.edge_type = 'CONTAINS' AND moved.valid_until IS NULL
                      AND moved.from_asset_id IN (p_card, p_refunds, p_recon, p_quoting));

    -- ---------------------------------------------------------------------------------------------
    -- A project's own UAT endpoint (ADR-061, CFG-AST-002).
    --
    -- On a PROJECT rather than on the application above it, because that is where the question is
    -- actually asked: an application is delivered by several teams and each team's branch has its own
    -- pre-production host. Until the environment vocabulary became tenant data this was recordable on
    -- a project and not on an application, and the reverse was true for staging — two hardcoded pairs
    -- in two editors, neither able to record what the other could.
    --
    -- Seeded so the projects dashboard shows a populated UAT column beside the ones that have none.
    -- Both answers matter: three of these four projects have no pre-production host recorded, which
    -- is what the "None recorded" filter finds and what product principle 1 exists to keep visible.
    -- ---------------------------------------------------------------------------------------------
    INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, display_name,
                       owning_node_id, criticality_mode, criticality_tier_id, exposure_declared,
                       exposure_observed, exposure_observed_source, exposure_observed_at,
                       lifecycle_state, tags, discovery_source, discovery_method, first_seen_at,
                       last_confirmed_at, scope_node_id, scope_ancestor_path, scope_node_type_id,
                       scope_criticality_id, scope_hierarchy_ver, scope_resolved_at)
    SELECT t, at_domain, 'uat-cards.example.internal', 1, 'uat-cards.example.internal',
           src.owning_node_id, 'INHERITED', NULL, 'INTERNAL_ONLY',
           'INTERNAL_ONLY', 'external-scan', now(),
           'ACTIVE', ARRAY['pre-production'], 'MANUAL', 'ONBOARDING', now() - interval '45 days',
           now(), src.scope_node_id, src.scope_ancestor_path, src.scope_node_type_id,
           src.scope_criticality_id, src.scope_hierarchy_ver, now()
      FROM asset src
     WHERE at_domain IS NOT NULL AND src.id = p_card
       AND NOT EXISTS (SELECT 1 FROM asset a WHERE a.tenant_id = t
                        AND a.identity_key = 'uat-cards.example.internal');

    INSERT INTO asset_relationship (tenant_id, from_asset_id, to_asset_id, edge_type,
                                    discovery_source, attributes, valid_from)
    SELECT t, p_card, d.id, 'PUBLISHED_ON', 'MANUAL', '{"environment":"UAT"}'::jsonb, now()
      FROM asset d
     WHERE d.tenant_id = t AND d.identity_key = 'uat-cards.example.internal'
       AND p_card IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM asset_relationship r
                        WHERE r.tenant_id = t AND r.from_asset_id = p_card
                          AND r.to_asset_id = d.id AND r.edge_type = 'PUBLISHED_ON'
                          AND r.valid_until IS NULL);

    RAISE NOTICE 'projects: % assets of type PROJECT',
        (SELECT count(*) FROM asset WHERE tenant_id = t AND type_id = at_project);
END
$seed$;
