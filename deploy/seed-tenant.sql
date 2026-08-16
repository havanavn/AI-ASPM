-- ==============================================================================================
-- The tenant registration row.
--
-- WHY THIS FILE EXISTS
--
-- Every other seed writes rows carrying tenant_id = 11111111-…, and the application works, because
-- row-level security keys off a SESSION VARIABLE and never consults the registry. So the platform
-- ran for weeks with a populated database and an empty `tenant` table, and nothing failed.
--
-- What did fail, silently, is every migration backfill written as
--
--     FOR t IN SELECT id FROM tenant LOOP  … set_config … UPDATE …  END LOOP
--
-- which is the correct shape for updating a tenant-isolated table — and which iterates zero times
-- against an empty registry, updating nothing and reporting success. Two migrations used it.
--
-- This seed must run BEFORE any other, and is named so that it does.
-- ==============================================================================================
INSERT INTO tenant (id, display_name, lifecycle_state, residency_region, key_reference,
                    entitlement_tier)
VALUES ('11111111-1111-1111-1111-111111111111', 'Demonstration group', 'ACTIVE', 'VN',
        -- A REFERENCE, never a key. ADR-002 gives each tenant its own key; what is recorded here is
        -- where to ask for it, and the vault providing it is OQ-026.
        'vault://aspm/tenant/11111111-1111-1111-1111-111111111111/data-key', 'STANDARD')
ON CONFLICT (id) DO NOTHING;
