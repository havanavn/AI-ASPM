-- =============================================================================
-- Seed: which roles may submit scan reports.
--
-- A SEED, NOT A MIGRATION, and the distinction is ADR-027. The permission CATALOGUE is product-fixed and
-- V062 adds `ing.findings.import` to it. Which roles hold it is tenant configuration: a deployment where
-- the security team alone may push scan results and a deployment where every delivery team's pipeline may
-- are both correct, and a migration that chose for them would be exactly the organization-specific
-- assumption ADR-027 forbids. A deployment that does not want this grant simply does not run this file.
--
-- WHY THESE ROLES. The same roles that already hold `sbm.sbom.submit` — the other automated ingestion
-- path — so a pipeline that can declare its dependencies can also submit its scan report. That is a
-- defensible default for this deployment and not a rule: the two permissions are separate precisely so
-- that a tenant can grant one without the other.
--
-- WHAT THIS DOES NOT DO. It grants nothing to a service credential. A credential's effective permissions
-- are the INTERSECTION of the array declared on the credential and the permissions of the principal
-- behind it, so a pipeline still needs its credential to declare `ing.findings.import` explicitly. Two
-- gates rather than one, and the narrower one wins — which is why issuing a credential is not a way to
-- widen what its principal may do.
--
-- Run as a superuser or as aspm_migrate, with the tenant context set:
--   BEGIN; SET LOCAL aspm.current_tenant='<tenant>'; \i seed-finding-import.sql COMMIT;
-- =============================================================================

INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT r.tenant_id, r.id, 'ing.findings.import'
  FROM role r
 WHERE EXISTS (SELECT 1 FROM role_permission rp
                WHERE rp.role_id = r.id AND rp.permission_code = 'sbm.sbom.submit')
ON CONFLICT DO NOTHING;
