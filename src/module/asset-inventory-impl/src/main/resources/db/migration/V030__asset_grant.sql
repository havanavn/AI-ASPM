-- =============================================================================
-- V030 — capabilities granted over ONE asset, to one person.
--
-- WHY THE ROLE MODEL COULD NOT CARRY THIS
--
-- DOC-07 authorizes by permission AND scope, where scope is a subtree of the organization tree. That
-- is the right shape for "the payments security lead may read every finding under payments" and the
-- wrong shape for "Lan owns the refunds project". A project is an ASSET, not an org node; granting
-- Lan a role over the team that happens to deliver it would give her the same authority over every
-- other project that team runs, and over anything the team acquires later.
--
-- ADR-001 is the reason the two cannot be collapsed: the org tree answers who is accountable for a
-- part of the ORGANIZATION, the asset graph answers what exists. Ownership of a thing belongs to the
-- second, and this table is the join.
--
-- WHAT A CAPABILITY IS, AND WHY THE LIST IS FIXED
--
--   OWN            accountable for the asset. May delegate RAISE_REQUEST on it, and is treated as an
--                  authority on any request naming it.
--   RAISE_REQUEST  may ask for an assessment of this asset, and only this asset.
--
-- Product-fixed, like the permission catalogue and for the same reason (PRD-AUZ-001): a capability is
-- something the platform knows how to enforce, so a tenant inventing one would be inventing a word
-- nothing acts on. A CHECK constraint rather than a table because the enforcement is in code — a
-- lookup table would imply rows can be added, and adding one would change nothing.
--
-- GRANTS ARE REVOKED, NEVER DELETED
--
-- Same rule as role_assignment, and the same reason: a grant is evidence of a decision somebody made,
-- and "who could raise a request against payments in March" is a question an incident review asks.
-- The uniqueness constraint is partial on revoked_at IS NULL so the same grant can be made again.
-- =============================================================================

CREATE TABLE IF NOT EXISTS asset_grant (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid NOT NULL,
    asset_id       uuid NOT NULL REFERENCES asset (id) ON DELETE RESTRICT,
    principal_id   uuid NOT NULL,
    capability     text NOT NULL,
    granted_by     uuid,
    granted_at     timestamptz NOT NULL DEFAULT now(),
    revoked_at     timestamptz,
    revoked_reason text,
    CONSTRAINT ck_asset_grant__capability
        CHECK (capability IN ('OWN', 'RAISE_REQUEST')),
    CONSTRAINT ck_asset_grant__revocation_attributed
        CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);

-- Partial: one live grant of a capability per person per asset, and any number of revoked ones.
CREATE UNIQUE INDEX IF NOT EXISTS uq_asset_grant__live
    ON asset_grant (tenant_id, asset_id, principal_id, capability)
    WHERE revoked_at IS NULL;

-- Serves: "who may raise a request against this project", read on every render of the project's
-- access panel and again on every intake form to build the picker.
CREATE INDEX IF NOT EXISTS ix_asset_grant__asset
    ON asset_grant (tenant_id, asset_id, capability)
    WHERE revoked_at IS NULL;

-- Serves the other direction: "what does this person own", which the user detail page asks and which
-- an offboarding check has to ask before an account is disabled.
CREATE INDEX IF NOT EXISTS ix_asset_grant__principal
    ON asset_grant (tenant_id, principal_id, capability)
    WHERE revoked_at IS NULL;

SELECT apply_tenant_isolation('asset_grant');

COMMENT ON TABLE asset_grant IS
    'A capability one person holds over one asset. Complements role_assignment, which grants over a '
    'subtree of the organization tree; see the header of V030 for why both are needed.';
COMMENT ON COLUMN asset_grant.capability IS
    'OWN or RAISE_REQUEST. Product-fixed: the platform enforces these two and would not act on a third.';

GRANT SELECT, INSERT, UPDATE ON asset_grant TO app_runtime;
GRANT SELECT ON asset_grant TO integrity_verifier;

-- -----------------------------------------------------------------------------------------------
-- The permission that grants these grants.
--
-- A migration and not a seed: PRD-AUZ-001 makes the catalogue product-fixed, so every deployment
-- gets the code and each tenant decides which of its roles carries it. requires_step_up because this
-- is authorization configuration — the same reasoning that puts a role grant in class E.
--
-- It is the FLOOR, not the whole gate. An asset's owner may delegate RAISE_REQUEST on that asset
-- without holding this permission, and that composite decision lives in ObjectAuthority rather than
-- in the operation registry, which can express one permission per operation and no ownership at all.
-- -----------------------------------------------------------------------------------------------
INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up) VALUES
  ('ast.asset.grant', 'AST',
   '{"en":"Grant ownership and request rights over an asset",
     "vi":"Cấp quyền sở hữu và quyền tạo yêu cầu trên tài sản"}'::jsonb, false, true)
ON CONFLICT (code) DO NOTHING;
