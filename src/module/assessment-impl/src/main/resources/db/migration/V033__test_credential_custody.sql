-- =============================================================================
-- V033 — the platform takes custody of a test credential, and gives it back at closure.
--
-- WHAT CHANGED AND WHY IT IS A DEVIATION WORTH READING
--
-- SEC-PTR-004 and INV-ASM-03 say a test credential is stored "by reference only" and that no
-- credential column exists. That was written on the assumption that a secrets store exists to hold
-- the value. OQ-026 — platform-provided vault, enterprise integration, or both — is still open and
-- still blocking, so no store exists, and "by reference only" therefore resolves in practice to "the
-- platform holds a note saying the password is somewhere in a Teams thread".
--
-- The product owner weighed that and chose custody with automatic destruction, having been shown the
-- alternative. The decision is defensible and the reasoning is worth recording rather than implying:
--
--   * The credential exists either way. Refusing to hold it does not stop it being created, sent,
--     and left live in a chat history nobody purges.
--   * Custody buys the one control the chat thread cannot offer: the platform knows when the
--     engagement ends, so it can destroy the value at that moment without anybody remembering to.
--   * Everything else SEC-PTR-004 requires is kept and is enforced below or in the application:
--     encrypted at rest, absent from exports and notifications, masked by default, reveal gated and
--     audited, rotation still flagged at closure.
--
-- Recorded as SEC-PTR-007 in DOC-06, which supersedes SEC-PTR-004 rather than leaving the deviation
-- to be discovered here. It narrows if OQ-026 is answered: a vault turns this table back into a
-- reference, and SEC-PTR-007's own statement says so.
--
-- WHAT IS STORED
--
-- Ciphertext and nonce, never a value. AES-256-GCM, key from the deployment environment and never
-- from the database — a key beside the ciphertext is an encoding, not encryption. The algorithm is
-- named per row so a key or cipher change is a migration of rows rather than a flag day.
--
-- PURGE IS A TOMBSTONE, NOT A DELETE
--
-- purged_at and purged_reason survive the value. "There was a credential here and it was destroyed
-- on this date" is exactly what an auditor asks and what a NULL cannot answer — an un-purged secret
-- and a purged one would otherwise look identical.
-- =============================================================================

ALTER TABLE assessment_request_role_account
    ADD COLUMN IF NOT EXISTS secret_ciphertext bytea,
    ADD COLUMN IF NOT EXISTS secret_nonce      bytea,
    ADD COLUMN IF NOT EXISTS secret_algorithm  text,
    ADD COLUMN IF NOT EXISTS secret_stored_at  timestamptz,
    ADD COLUMN IF NOT EXISTS secret_stored_by  uuid,
    ADD COLUMN IF NOT EXISTS secret_purged_at  timestamptz,
    ADD COLUMN IF NOT EXISTS secret_purge_reason text;

-- The credential is now OPTIONAL, in two senses the user asked for separately:
-- accounts are not required to raise a request, and an account may carry a reference, a held secret,
-- or neither (a username the assessor already has the password for).
ALTER TABLE assessment_request_role_account
    ALTER COLUMN credential_ref DROP NOT NULL;

-- The old CHECK demanded a URI scheme on every row. It cannot hold once the column is optional, and
-- its shape was also refusing '1password:' — the pattern requires a leading letter and a real
-- product name starts with a digit. Replaced with: if present, it must still look like a reference.
ALTER TABLE assessment_request_role_account
    DROP CONSTRAINT IF EXISTS ck_asm_role_acct__credential_is_reference;
ALTER TABLE assessment_request_role_account
    ADD CONSTRAINT ck_asm_role_acct__credential_is_reference
    CHECK (credential_ref IS NULL OR credential_ref ~ '^[a-z0-9][a-z0-9+.-]{1,31}:');

-- Ciphertext and nonce travel together or not at all. A nonce without ciphertext is noise; ciphertext
-- without its nonce is unrecoverable, which would be a credential nobody can destroy or use.
ALTER TABLE assessment_request_role_account DROP CONSTRAINT IF EXISTS ck_asm_role_acct__secret_complete;
ALTER TABLE assessment_request_role_account ADD CONSTRAINT ck_asm_role_acct__secret_complete
    CHECK (num_nonnulls(secret_ciphertext, secret_nonce, secret_algorithm) IN (0, 3));

-- Attribution on both ends: who lodged it, and that a purge states its reason.
ALTER TABLE assessment_request_role_account DROP CONSTRAINT IF EXISTS ck_asm_role_acct__secret_attributed;
ALTER TABLE assessment_request_role_account ADD CONSTRAINT ck_asm_role_acct__secret_attributed
    CHECK ((secret_stored_at IS NULL) = (secret_stored_by IS NULL));
ALTER TABLE assessment_request_role_account DROP CONSTRAINT IF EXISTS ck_asm_role_acct__purge_attributed;
ALTER TABLE assessment_request_role_account ADD CONSTRAINT ck_asm_role_acct__purge_attributed
    CHECK ((secret_purged_at IS NULL) = (secret_purge_reason IS NULL));

-- A purge must actually destroy. Belt and braces against an UPDATE that sets the tombstone and
-- forgets the value — the failure mode where the interface reports "destroyed" and the bytes remain.
ALTER TABLE assessment_request_role_account DROP CONSTRAINT IF EXISTS ck_asm_role_acct__purge_destroys;
ALTER TABLE assessment_request_role_account ADD CONSTRAINT ck_asm_role_acct__purge_destroys
    CHECK (secret_purged_at IS NULL OR secret_ciphertext IS NULL);

-- Serves the sweep: "every request that reached a terminal state and still holds a secret". Partial,
-- so it is the size of the outstanding custody rather than of every account ever submitted. This is
-- the query a scheduled job runs; the closure path purges inline and this is what catches the ones a
-- crash or an out-of-band state change stepped over.
CREATE INDEX IF NOT EXISTS ix_asm_role_acct__secret_held
    ON assessment_request_role_account (tenant_id, request_id)
    WHERE secret_ciphertext IS NOT NULL;

COMMENT ON COLUMN assessment_request_role_account.secret_ciphertext IS
    'AES-256-GCM ciphertext of a test password the platform holds in custody until the request '
    'closes. Governed by SEC-PTR-007, which supersedes SEC-PTR-004; see the header of V033.';
COMMENT ON COLUMN assessment_request_role_account.secret_purged_at IS
    'When the value was destroyed. Survives the value on purpose: an auditor asks whether a '
    'credential was destroyed, and a NULL cannot distinguish destroyed from never held.';
