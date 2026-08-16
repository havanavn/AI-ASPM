-- =============================================================================
-- Organization names a person recognises.
--
-- WHY THIS IS A SEED AND NOT A MIGRATION
--
-- Every name below is tenant data (ADR-027). A conglomerate's operating companies are its own
-- vocabulary, and a migration that wrote them would impose one customer's brands on every
-- deployment. Nothing in the application refers to any of these strings.
--
-- WHAT IT REPLACES, AND WHY THE OLD NAMES WERE A PROBLEM
--
-- The demo tree carried "Renamed", "Sibling nobody granted" and "Tenant B root" — names left over
-- from testing the rename and scope-isolation paths. They are accurate about what they were for and
-- useless as an interface: the sidebar's scope label now names the organizations a person reaches,
-- so those strings became the first thing anybody saw on signing in.
--
-- Short operating-company names are also what a real group looks like, and they are what makes the
-- scope label legible: "VinFast, Vinmec" fits, "Vietnam Manufacturing and Mobility Division" does
-- not. That is a property of the interface worth designing the data around.
--
-- IDENTIFIERS ARE UNCHANGED
--
-- Only `name` moves. Scope grants, closure rows and asset ownership are all by identifier, so no
-- authorization changes and nothing needs re-granting.
--
-- Re-runnable; each rename is matched on the old name and does nothing the second time.
-- =============================================================================

\set ON_ERROR_STOP on

DO $seed$
DECLARE
    t          uuid := '11111111-1111-1111-1111-111111111111';
    root_a     uuid;
    root_b     uuid;
    nt_division uuid;
    nt_project  uuid;
    tier2      uuid;
    n_gsm      uuid;
    n_vinhomes uuid;
BEGIN
    PERFORM set_config('aspm.current_tenant', t::text, true);

    -- The two demo roots become operating companies. Chosen to match the shape the product owner
    -- described: short, recognisable, no level name embedded in the string.
    UPDATE org_node SET name = 'VinFast', updated_at = now()
     WHERE tenant_id = t AND name = 'Renamed';
    UPDATE org_node SET name = 'Vinmec', updated_at = now()
     WHERE tenant_id = t AND name = 'Sibling nobody granted';

    -- The middle of the tree keeps its function but loses the placeholder wording.
    UPDATE org_node SET name = 'Digital Platform', updated_at = now()
     WHERE tenant_id = t AND name = 'Banking';

    SELECT id INTO nt_division FROM org_node_type WHERE tenant_id = t AND code = 'DIVISION';
    SELECT id INTO nt_project  FROM org_node_type WHERE tenant_id = t AND code = 'PROJECT';
    SELECT id INTO tier2 FROM criticality_tier WHERE tenant_id = t ORDER BY ordinal OFFSET 1 LIMIT 1;

    -- Two more operating companies, so the scope label has something to truncate and the
    -- organization page shows a shape rather than a single branch. A tenant-wide grant reaches every
    -- root, so these appear in scope for an administrator immediately.
    SELECT id INTO n_gsm FROM org_node WHERE tenant_id = t AND name = 'GSM';
    IF n_gsm IS NULL AND nt_division IS NOT NULL THEN
        INSERT INTO org_node (tenant_id, type_id, parent_id, name, criticality_mode,
                              criticality_tier_id, criticality_justification)
        VALUES (t, nt_division, NULL, 'GSM', 'ASSIGNED', tier2,
                'Mobility services operating company; demo data')
        RETURNING id INTO n_gsm;
        -- The closure self-reference, which the application layer writes and a raw INSERT does not.
        -- INV-ORG-13 is asserted at commit and refused this seed until it was here: without the
        -- depth-zero row, "the subtree of GSM" excludes GSM and every scope query over it is subtly
        -- wrong. A root has no ancestors, so self is the whole of its closure.
        INSERT INTO org_closure (tenant_id, ancestor_id, descendant_id, depth, hierarchy_version)
        VALUES (t, n_gsm, n_gsm, 0,
                (SELECT coalesce(max(hierarchy_version), 0) + 1 FROM org_closure))
        ON CONFLICT DO NOTHING;
    END IF;

    SELECT id INTO n_vinhomes FROM org_node WHERE tenant_id = t AND name = 'VinHomes';
    IF n_vinhomes IS NULL AND nt_division IS NOT NULL THEN
        INSERT INTO org_node (tenant_id, type_id, parent_id, name, criticality_mode,
                              criticality_tier_id, criticality_justification)
        VALUES (t, nt_division, NULL, 'VinHomes', 'ASSIGNED', tier2,
                'Property operating company; demo data')
        RETURNING id INTO n_vinhomes;
        INSERT INTO org_closure (tenant_id, ancestor_id, descendant_id, depth, hierarchy_version)
        VALUES (t, n_vinhomes, n_vinhomes, 0,
                (SELECT coalesce(max(hierarchy_version), 0) + 1 FROM org_closure))
        ON CONFLICT DO NOTHING;
    END IF;

    -- Tenant B's root is a different tenant's data and is renamed only so a leak would be obvious:
    -- if this string ever appears in tenant A's interface, the isolation test failed and the name
    -- says so on sight.
    UPDATE org_node SET name = 'OTHER TENANT — must never be visible here', updated_at = now()
     WHERE name = 'Tenant B root';

    RAISE NOTICE 'organization: % root(s) — %',
        (SELECT count(*) FROM org_node WHERE tenant_id = t AND parent_id IS NULL),
        (SELECT string_agg(name, ', ' ORDER BY name) FROM org_node
          WHERE tenant_id = t AND parent_id IS NULL);
END
$seed$;
