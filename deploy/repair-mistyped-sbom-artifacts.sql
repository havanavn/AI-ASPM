-- =============================================================================
-- repair-mistyped-sbom-artifacts.sql — retypes artifacts the ingestion defect created as APPLICATION.
--
-- WHAT WENT WRONG. `SbomIngestion#resolveArtifact` typed an unknown artifact with
-- `SELECT id FROM asset_type ORDER BY ordinal LIMIT 1` — whatever sorted first, which in this tenant
-- is APPLICATION. Every SBOM naming an artifact the platform had not seen therefore created a new
-- APPLICATION, and the application inventory grew a row per repository that had ever pushed. Six
-- accumulated during this module's own testing. The code is fixed to prefer REPOSITORY; this repairs
-- what it made before that.
--
-- RETIRED, NOT RETYPED AND NOT DELETED — and the schema decided that, not me. The first attempt at
-- this file changed `type_id` and was refused by INV-AST-01: an asset's type is immutable because
-- changing it changes the identity rule, the permitted edges and the attribute schema at once, and
-- the trigger's own message names the remedy — "retire and recreate with a merge instead". It is
-- right to refuse. So:
--
--   * the row is RETIRED with a stated reason, which is what takes it out of every inventory while
--     leaving the record of what was submitted intact (PP-5, and there is deliberately no DELETE
--     grant on `asset` anywhere in this schema);
--   * its identity_key is prefixed, so the three-part address it occupied is free again and the next
--     submission creates a correctly typed REPOSITORY rather than reusing the retired row.
--
-- SCOPED NARROWLY: only assets whose discovery_source is SBOM_SUBMISSION. An APPLICATION somebody
-- created deliberately is not touched, whatever it is named.
-- =============================================================================

BEGIN;
SET LOCAL aspm.current_tenant = '11111111-1111-1111-1111-111111111111';

UPDATE asset
   SET lifecycle_state  = 'RETIRED',
       retired_reason  = 'Created as an APPLICATION by the SBOM ingestion defect corrected in '
                           || 'SbomIngestion#resolveArtifact: an unknown artifact was typed with the '
                           || 'first asset type by ordinal. Retired rather than retyped because '
                           || 'INV-AST-01 makes the type immutable. Its snapshots remain readable.',
       identity_key     = 'mistyped:' || identity_key,
       updated_at       = now(),
       row_version      = row_version + 1
 WHERE discovery_source = 'SBOM_SUBMISSION'
   AND lifecycle_state <> 'RETIRED'
   AND type_id = (SELECT id FROM asset_type WHERE code = 'APPLICATION');

-- Attach any that name their place in the tree, so they stop being orphans invisible to every
-- rollup. The identity key of the three-part address is repo:<application>/<project>/<repository>.
INSERT INTO asset_relationship (tenant_id, from_asset_id, to_asset_id, edge_type,
                                discovery_source, valid_from, attributes)
SELECT current_tenant_id(), p.id, r.id, 'CONTAINS', 'SBOM_SUBMISSION', now(), '{}'::jsonb
  FROM asset r
  JOIN asset_type rt ON rt.id = r.type_id AND rt.code = 'REPOSITORY'
  JOIN asset p ON p.display_name = split_part(replace(r.identity_key, 'repo:', ''), '/', 2)
  JOIN asset_type pt ON pt.id = p.type_id AND pt.code = 'PROJECT'
 WHERE r.identity_key LIKE 'repo:%/%/%'
   AND NOT EXISTS (SELECT 1 FROM asset_relationship e
                    WHERE e.to_asset_id = r.id AND e.edge_type = 'CONTAINS'
                      AND e.valid_until IS NULL)
 ON CONFLICT DO NOTHING;

COMMIT;
