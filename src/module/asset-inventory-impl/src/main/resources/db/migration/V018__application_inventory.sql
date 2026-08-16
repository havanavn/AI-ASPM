-- =============================================================================
-- V018 — the application inventory read surface, and the indexes its filters need.
--
-- WHAT AN "APPLICATION" IS HERE. An asset whose type is APPLICATION. Not a new table: ADR-009 is "one
-- Asset aggregate with a type registry, not five parallel inventories", and a product/application
-- table beside `asset` would be the second inventory that decision exists to prevent — with its own
-- ownership column, its own criticality, and its own answer to "is this internet-facing".
--
-- So everything the inventory shows is already modelled:
--
--   business unit        asset.owning_node_id -> org_node          (ADR-001, ADR-010)
--   criticality          asset.criticality_tier_id, or inherited from the owning node
--   public / internal    asset.exposure_declared, with exposure_observed and the conflict flag
--   security score       rm_latest_risk_score, WITH its coverage_confidence
--   prod / staging URL   a related DOMAIN asset, through asset_relationship
--   git repository       a related REPOSITORY asset, through asset_relationship
--   services             related SERVICE assets, through asset_relationship
--   pentest requests     assessment_scope_asset -> assessment_request
--
-- A domain is network-reachable and can carry findings, so it must be an ASSET and not a text column
-- on another asset: a finding raised against pay.example.com has to attach to something. The
-- application form still asks for one text box per environment — the service creates or reuses the
-- DOMAIN asset and the edge behind it. A simple form over a correct model is the point; a simple
-- model under a simple form is how the second inventory gets built.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. The environment an edge carries.
--
-- asset_relationship.attributes already exists and its edge types already include PUBLISHED_ON. What
-- was missing is a stated convention for WHICH environment a publication is, so prod and staging can
-- be told apart without a second edge type per environment — an enumeration in a CHECK is exactly the
-- fixed-enumeration-for-a-configurable-surface pattern DOC-00 prohibits, and environments are tenant
-- vocabulary.
--
-- The convention: attributes->>'environment'. Indexed rather than constrained, so a tenant may use
-- whatever environment names they use, and the interface groups by the distinct values present.
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS ix_asset_relationship__environment
    ON asset_relationship (tenant_id, from_asset_id, (attributes ->> 'environment'))
    WHERE valid_until IS NULL;

COMMENT ON INDEX ix_asset_relationship__environment IS
    'Serves: the production and staging endpoint columns of the application inventory, and the '
    'endpoint list on an application detail page. Partial on the current edge, which is what both ask.';

-- -----------------------------------------------------------------------------
-- 2. Indexes for the inventory filters.
--
-- The interface filters on owning node, type, exposure, criticality and lifecycle, and DOC-00 forbids
-- a table without an indexing strategy naming the query it serves. These name it.
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS ix_asset__inventory_filter
    ON asset (tenant_id, type_id, owning_node_id, lifecycle_state);
COMMENT ON INDEX ix_asset__inventory_filter IS
    'Serves: the application inventory list, whose default view is one asset type within a scope.';

CREATE INDEX IF NOT EXISTS ix_asset__exposure
    ON asset (tenant_id, exposure_declared) WHERE lifecycle_state <> 'RETIRED';
COMMENT ON INDEX ix_asset__exposure IS
    'Serves: filtering the inventory to internet-facing applications, which is the first question '
    'asked of it after an advisory lands.';

-- -----------------------------------------------------------------------------
-- 3. application_inventory — one row per asset, with everything the list column needs.
--
-- SECURITY INVOKER (the default, and load-bearing): a DEFINER view over tenant-isolated tables would
-- run with the definer's row-level context and hand every tenant's inventory to any caller. The same
-- reasoning as principal_administration in V016, restated because the failure is silent.
--
-- WHAT THIS VIEW DOES NOT DO: it does not apply the caller's SCOPE. Row-level security gives tenant
-- isolation; scope within a tenant is a permission decision the application layer composes into the
-- query (SEC-AUZ-016 requires the predicate in the query rather than a filter over the result). A
-- view that appeared to be "the assets you can see" would be trusted as one.
-- -----------------------------------------------------------------------------
-- DROP then CREATE, not CREATE OR REPLACE. The migrations are replayed in full on every start, and
-- a replay runs this file again AFTER the later migration that widened the view. CREATE OR REPLACE
-- cannot remove a column from an existing view, so the second run failed with "cannot drop columns
-- from view" and took the whole migration container down — a green deployment that could not be
-- restarted. Dropping first makes each migration's definition authoritative at the moment it runs,
-- which is what a replayable migration has to be.
DROP VIEW IF EXISTS application_inventory;

CREATE VIEW application_inventory AS
SELECT a.id,
       a.tenant_id,
       a.display_name,
       a.identity_key,
       a.lifecycle_state,
       a.owning_node_id,
       a.type_id,
       t.code                                  AS type_code,
       n.name                                  AS owning_node_name,
       nt.code                                 AS owning_node_type_code,
       -- The ancestor chain, so the list can show and filter by any level of the hierarchy without
       -- the page walking the closure table per row. ADR-027: the LEVEL NAMES are tenant data, so
       -- this returns the path and never a column called "business_unit".
       (SELECT array_agg(anc.name ORDER BY c.depth DESC)
          FROM org_closure c
          JOIN org_node anc ON anc.id = c.ancestor_id
         WHERE c.descendant_id = a.owning_node_id
           AND c.depth > 0)                    AS ancestor_names,
       a.exposure_declared,
       a.exposure_observed,
       a.exposure_conflict,
       a.criticality_mode,
       -- The EFFECTIVE tier: assigned on the asset, or inherited from the owning node. Resolved here
       -- because a list that showed a blank for every inherited asset would be read as "no criticality
       -- set" when the answer exists one level up.
       coalesce(ct_own.code, ct_node.code)     AS criticality_code,
       (ct_own.code IS NULL AND ct_node.code IS NOT NULL) AS criticality_inherited,
       a.tags,
       a.technical_contact_id,
       a.attributes,
       -- The score AND its coverage. PRD-UIX-022 forbids rendering a numeral for something unmeasured,
       -- so the interface needs to know the difference between "score 0" and "never scored" — which is
       -- a NULL here, not a zero.
       s.value                                 AS risk_value,
       s.band                                  AS risk_band,
       s.coverage_confidence                   AS risk_coverage,
       s.computed_at                           AS risk_computed_at,
       (SELECT count(*) FROM assessment_scope_asset sa
         WHERE sa.asset_id = a.id)             AS request_count,
       (SELECT count(*) FROM finding_asset_impact fi
         WHERE fi.asset_id = a.id)             AS finding_count,
       a.created_at,
       a.updated_at,
       a.row_version
  FROM asset a
  JOIN asset_type t          ON t.id = a.type_id
  LEFT JOIN org_node n       ON n.id = a.owning_node_id
  LEFT JOIN org_node_type nt ON nt.id = n.type_id
  LEFT JOIN criticality_tier ct_own  ON ct_own.id = a.criticality_tier_id
  LEFT JOIN criticality_tier ct_node ON ct_node.id = n.criticality_tier_id
  LEFT JOIN rm_latest_risk_score s
         ON s.subject_kind = 'ASSET' AND s.subject_id = a.id;

COMMENT ON VIEW application_inventory IS
    'The application/product inventory list. SECURITY INVOKER so row-level policies apply to the '
    'CALLER. Carries the score WITH its coverage_confidence and a NULL where nothing was scored, '
    'because PRD-UIX-022 forbids a numeral for an unmeasured value.';

GRANT SELECT ON application_inventory TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 4. Grants the inventory editor needs.
--
-- INSERT and UPDATE on asset and asset_relationship already exist for app_runtime (V005). What the
-- editor adds is nothing destructive: "delete an application" RETIRES it.
--
-- asset.lifecycle_state carries RETIRED and ck_asset__retired_has_reason requires a reason or a merge
-- target, so a retirement cannot be recorded without saying why. That is the correct shape and not a
-- workaround for a missing DELETE: an asset that ever carried a finding cannot be deleted without
-- deleting the finding's subject, and the finding is the record of a real weakness that really
-- existed. There is deliberately no DELETE grant on asset anywhere in this schema.
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT has_table_privilege('app_runtime', 'asset', 'UPDATE') THEN
        RAISE EXCEPTION 'app_runtime cannot UPDATE asset, so the inventory editor cannot retire or '
                        'amend anything. V005 was expected to grant it.';
    END IF;
    IF NOT has_table_privilege('app_runtime', 'asset_relationship', 'INSERT') THEN
        RAISE EXCEPTION 'app_runtime cannot INSERT asset_relationship, so an application cannot be '
                        'linked to its domains, repository or services.';
    END IF;
    -- Checked rather than assumed, because the role editor shipped with a delete button and no
    -- DELETE grant behind it (V017) — the same class of omission, found only against a real engine.
END
$$;

-- -----------------------------------------------------------------------------
-- 5. org_node: the business-unit editor writes here.
--
-- Same check, same reason. Retirement is lifecycle_state = 'DEPRECATED'; there is no DELETE, because
-- org_node.parent_id is ON DELETE RESTRICT and a node that ever scoped a grant or owned an asset is
-- referenced by the record of a decision.
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT has_table_privilege('app_runtime', 'org_node', 'INSERT')
       OR NOT has_table_privilege('app_runtime', 'org_node', 'UPDATE') THEN
        RAISE EXCEPTION 'app_runtime cannot write org_node, so the organization editor cannot create '
                        'or amend a node.';
    END IF;
END
$$;
