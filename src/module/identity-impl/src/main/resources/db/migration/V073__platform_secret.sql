-- =============================================================================================
-- V073 — the sealed secrets store: ADR-052's platform-provided default, as a table.
--
-- WHAT WAS MISSING, AND WHY IT BLOCKED EVERYTHING ELSE. OQ-026 asked whether the platform provides
-- its own secrets vault or integrates with the enterprise's, and ADR-052 answered "both" and defined
-- a provider-agnostic contract. Nothing implemented the contract. The consequence was visible in
-- three places: the TOTP secret column is named `secret_ciphertext` and holds plaintext under
-- `secret_key_ref = 'PLAINTEXT_PENDING_OQ_026'` (V015); the AI provider key and the test-account
-- password are sealed under one process-wide key read from an environment variable, which
-- `OPS-DEP-020` forbids; and every capability that needs a tenant-entered credential — a federated
-- identity provider's client secret, an SMTP relay password, a chat bot token, a tracker API token —
-- had nowhere to put one and therefore could not be built. This migration is the storage half of the
-- contract; `aspm.app.secrets` is the code half, with adapters for HashiCorp Vault / OpenBao, Azure
-- Key Vault, AWS Secrets Manager and Google Secret Manager beside this one.
--
-- WHAT THIS TABLE IS. One row per sealed value: AES-256-GCM ciphertext, its nonce, the algorithm name
-- (so a cipher change migrates rows rather than silently misreading them), and a short fingerprint so
-- an administrator can tell "the one I pasted today" from "the one from last quarter" without seeing
-- either. The key is NOT here. It is held outside the database — `ASPM_CREDENTIAL_KEY`, or a mounted
-- file reached through `ASPM_CREDENTIAL_KEY_REF` — so a backup of this table without the key is
-- ciphertext, which is `SEC-PTR-007`'s "encrypt at rest under a key held outside the database".
--
-- WHAT IT IS NOT. It is not `OPS-DEP-021`: there is one deployment key, not a per-tenant data key
-- wrapped by a hardware-backed key-encryption key. ADR-052 accepted that gap for the platform default
-- and made the vault adapters the answer for a group that needs the stronger property. The gap is
-- stated on the row (`algorithm` names the cipher, not a key hierarchy) and in the startup banner.
--
-- WHY THE REFERENCE IS AN OPAQUE ROW ID. A reference is `sealed:<id>`. It carries no tenant, no name
-- and no hint of the value, so it is safe to display, log and export (`SEC-SEC-025`); the tenant is a
-- separate argument to every read, and the row-level policy is what makes a reference from tenant A
-- resolve to nothing under tenant B — DOC-24 §6.2 entry 12, isolation path I13, enforced by the engine
-- rather than by a comparison somebody has to remember.
--
-- WHY DESTRUCTION OVERWRITES. `destroyed_at` alone would leave the ciphertext readable to anybody who
-- holds the key and the backup. Destruction zeroes `ciphertext` and `nonce` in the same statement, and
-- a CHECK forbids the half-state (`SEC-TEN-016`, ADR-034 on demonstrable deletion). The row itself is
-- retained: that a secret existed, when, and when it was destroyed is the record (product principle 5).
--
-- Requirements: SEC-SEC-023, SEC-SEC-024, SEC-SEC-026, SEC-PTR-007, PRD-CON-021, OPS-DEP-019, OPS-DEP-020.
-- Decisions: ADR-052, ADR-047 (no read path exposes a value), ADR-034.
-- =============================================================================================

CREATE TABLE IF NOT EXISTS platform_secret (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id         uuid        NOT NULL,

    -- What kind of thing owns it and what the owner calls it: `identity_provider` / `client_secret`,
    -- `notification_channel` / `smtp_password`. Not unique — a rotation writes a new row and the caller
    -- swaps the reference, so both are resolvable during PRD-CON-022's overlap until the old one is
    -- destroyed.
    namespace         text        NOT NULL,
    name              text        NOT NULL,

    ciphertext        bytea       NOT NULL,
    nonce             bytea       NOT NULL,
    algorithm         text        NOT NULL,
    -- Eight hex characters of SHA-256 of the plaintext. Recognition, never recovery.
    fingerprint       text        NOT NULL,

    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid,
    last_accessed_at  timestamptz,
    access_count      bigint      NOT NULL DEFAULT 0,
    destroyed_at      timestamptz,

    CONSTRAINT ck_platform_secret__namespace CHECK (namespace ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    CONSTRAINT ck_platform_secret__name      CHECK (name ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    CONSTRAINT ck_platform_secret__algorithm CHECK (algorithm IN ('AES-256-GCM')),
    CONSTRAINT ck_platform_secret__fingerprint CHECK (fingerprint ~ '^[0-9a-f]{8}$'),
    -- A live row has material; a destroyed row has none. No third state.
    CONSTRAINT ck_platform_secret__destroyed_is_empty CHECK (
        (destroyed_at IS NULL AND length(ciphertext) > 0 AND length(nonce) = 12)
        OR (destroyed_at IS NOT NULL AND length(ciphertext) = 0 AND length(nonce) = 0))
);

SELECT apply_tenant_isolation('platform_secret');

COMMENT ON TABLE platform_secret IS
    'SEC-SEC-023, SEC-PTR-007, ADR-052. The platform-provided secrets store: AES-256-GCM ciphertext under '
    'a key held outside the database. A reference is sealed:<id> and carries no tenant; the row-level '
    'policy is what scopes it. ONE deployment key, not per-tenant wrapped keys — the OPS-DEP-021 gap '
    'ADR-052 accepted for the default. Destruction zeroes the material and keeps the row.';
COMMENT ON COLUMN platform_secret.fingerprint IS
    'Eight hex characters of SHA-256 of the plaintext, for an administrator to recognise which value a '
    'row holds. Never the value''s own characters (SEC-SEC-025).';
COMMENT ON COLUMN platform_secret.access_count IS
    'PRD-CON-021, ADR-052: access to a held credential is observable per object. Incremented on every '
    'successful resolution together with last_accessed_at.';

-- Serves: SealedSecretsProvider.resolve — a point read by id under the tenant policy; the primary key
-- serves it. Serves: the administration view of a tenant's held secrets by owner, which lists live
-- rows per namespace.
CREATE INDEX IF NOT EXISTS ix_platform_secret__owner
    ON platform_secret (tenant_id, namespace, name)
    WHERE destroyed_at IS NULL;
COMMENT ON INDEX ix_platform_secret__owner IS
    'Serves: the per-owner listing of live sealed secrets (namespace, name), without values.';

-- No DELETE: a destroyed secret is a row with no material, not an absent row (product principle 5).
GRANT SELECT, INSERT, UPDATE ON platform_secret TO app_runtime;
GRANT SELECT ON platform_secret TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- The reference vocabulary, so a column that holds a reference can say so in a CHECK.
-- <provider>:<path>; providers are the adapters in aspm.app.secrets. Not an enumeration of tenant
-- vocabulary (ADR-027): the set of adapters is a product property, like the annotation classes.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION is_secret_reference(candidate text) RETURNS boolean
    LANGUAGE sql IMMUTABLE
AS $$
    -- No bounded repetition above 255: PostgreSQL's regex engine refuses {1,512} at CALL time with
    -- "invalid repetition count(s)", which the first CHECK to use this function surfaced. The length
    -- bound is a plain comparison instead.
    SELECT candidate ~ '^(sealed|vault|azkv|awssm|gcpsm|file|env):[^[:space:]]+$'
       AND length(candidate) <= 530
$$;

COMMENT ON FUNCTION is_secret_reference(text) IS
    'SEC-SEC-023: the shape of a secret reference. Used by CHECK constraints on columns that must hold '
    'a reference and never a value.';

GRANT EXECUTE ON FUNCTION is_secret_reference(text) TO app_runtime, integrity_verifier, migration_runner;
