-- =============================================================================
-- V045 — where a tenant names the model it wants used, and the credential to reach it.
--
-- WHY THIS EXISTS BEFORE ANY AI FEATURE DOES. ADR-044 defers AI capability from v1 while the AI
-- architecture is built. This is architecture, not capability: it stores nothing but configuration and
-- invokes nothing. Shipping it first means the agent, when it arrives, has a per-tenant provider to
-- resolve rather than an environment variable shared by every tenant on the deployment — which is the
-- shape that would have to be undone later, after keys were already in it.
--
-- OQ-027, working assumption: the platform offers PROVIDER CHOICE, including self-hosted endpoints,
-- and does not operate models itself. So the row carries an endpoint and a model name, and neither is
-- an enumeration: a tenant pointing at its own inference server is the case this must not exclude.
--
-- =============================================================================
-- THREE THINGS THIS DELIBERATELY GETS RIGHT, AND WHY EACH IS EASY TO GET WRONG
-- =============================================================================
--
-- 1. THE KEY IS SEALED, NOT HASHED — and that is the opposite of every other credential here.
--
--    `service_credential` (V037) stores a DIGEST, because the platform only ever needs to verify a
--    signature somebody else computed. This key is the reverse direction: the platform must present
--    it to a third party, so it must be able to recover the plaintext. A digest would be useless.
--
--    That makes this row a higher-value target than an inbound credential, because a digest cannot be
--    replayed and this can. It is sealed with the AES-256-GCM facility of `CredentialCustody`
--    (SEC-PTR-007) rather than stored bare, and the ciphertext is never included in any
--    representation — ADR-047: restricted fields are ABSENT, not masked.
--
--    ⚠️ Working assumption (OQ-026): sealed at rest with a platform-held key. OQ-026 — whether secret
--    custody is platform-provided or an enterprise vault integration — is unanswered and marked as
--    blocking. `key_reference` exists for that answer: when a vault is chosen, the ciphertext columns
--    go empty and this holds the vault path instead, with no change to callers.
--
-- 2. A BEARER KEY HERE DOES NOT CONTRADICT ADR-004.
--
--    ADR-004 forbids bearer API keys for callers INTO the platform, because the platform gets to
--    choose its own authentication scheme and a bearer token is replayable from anywhere it leaks.
--    Outbound, the provider chooses the scheme, and every commercial model provider chose a bearer
--    key. Recorded here so a reviewer meeting this column does not read it as the prohibition being
--    quietly dropped.
--
-- 3. `send_record_content` DEFAULTS TO FALSE, and it is the most important column in the table.
--
--    Risk surface 5: finding content legitimately includes attacker-authored text — a payload, a
--    request body, a secret recovered from a customer's code. An agent that summarises a dashboard
--    will read exactly that material, so enabling a provider is a decision to EGRESS the group's
--    exploitable attack surface to a third party. Defaulting it on would make that decision by
--    omission, on behalf of somebody who only wanted nicer prose.
--
--    False means: aggregates and counts may be sent, record content may not. It is a declaration the
--    agent must honour, not an enforcement point on its own — the enforcement belongs with the caller
--    and TST-AUZ-001 requires a new egress path to have one.
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_provider (
    id                  uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id           uuid        NOT NULL DEFAULT current_tenant_id(),

    -- What a person calls this configuration. Not the provider's name: a tenant may hold two
    -- configurations for the same provider — one cheap model for drafting, one for analysis — and
    -- "which one is this" has to be answerable without reading a model string.
    label               text        NOT NULL,

    -- FREE TEXT, not an enum. ADR-027 forbids a fixed enumeration for a tenant-configurable surface,
    -- and OQ-027's assumption explicitly includes self-hosted endpoints — a CHECK constraint listing
    -- today's commercial providers would reject the deployment that matters most to a regulated
    -- tenant. Interpretation belongs to the client that builds the request, not to the schema.
    provider_kind       text        NOT NULL,

    -- NULL means the provider's documented default endpoint. Present means a self-hosted or regional
    -- endpoint. Validated at the application layer against the same egress guard the webhook
    -- destinations use, because a base URL is an outbound destination with the same SSRF exposure.
    base_url            text,
    model               text        NOT NULL,

    -- The sealed key. All three or none: an algorithm without a ciphertext is a row that cannot be
    -- opened, and a ciphertext without its algorithm is a row nobody can decrypt after a rotation.
    api_key_ciphertext  bytea,
    api_key_nonce       bytea,
    api_key_algorithm   text,

    -- For OQ-026's answer: a vault path, used INSTEAD of the ciphertext columns.
    key_reference       text,

    -- Enough to recognise a key without revealing it: a short digest prefix, never the key's own
    -- characters. Showing the last four would leak four characters of a live credential, and four
    -- characters of a bearer token is four more than a reader needs to answer "did I paste the new
    -- one or the old one".
    key_fingerprint     text,

    -- See note 3 above. The whole point of the table's caution.
    send_record_content boolean     NOT NULL DEFAULT false,

    active              boolean     NOT NULL DEFAULT true,

    -- The result of the last explicit connection test. A key that was accepted at paste time and has
    -- since been revoked upstream is indistinguishable from a working one until something tries it,
    -- and "the AI panel is empty" is a bad way to learn that.
    last_tested_at      timestamptz,
    last_test_status    text,
    last_test_detail    text,

    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          uuid,
    row_version         integer     NOT NULL DEFAULT 1,

    CONSTRAINT pk_ai_provider PRIMARY KEY (id),
    CONSTRAINT ck_ai_provider__label CHECK (length(btrim(label)) BETWEEN 1 AND 120),
    CONSTRAINT ck_ai_provider__model CHECK (length(btrim(model)) BETWEEN 1 AND 200),
    -- A credential must be reachable one way or the other. A row with neither is a provider that can
    -- never be called, which is a configuration error worth refusing at write time rather than
    -- discovering when an agent runs.
    CONSTRAINT ck_ai_provider__has_credential CHECK (
        key_reference IS NOT NULL
        OR (api_key_ciphertext IS NOT NULL AND api_key_nonce IS NOT NULL
            AND api_key_algorithm IS NOT NULL)),
    CONSTRAINT ck_ai_provider__sealed_complete CHECK (
        (api_key_ciphertext IS NULL AND api_key_nonce IS NULL AND api_key_algorithm IS NULL)
        OR (api_key_ciphertext IS NOT NULL AND api_key_nonce IS NOT NULL
            AND api_key_algorithm IS NOT NULL))
);

COMMENT ON TABLE ai_provider IS
    'Per-tenant model provider configuration. Stores the credential SEALED rather than hashed, because '
    'the platform must present it to a third party — the opposite of service_credential, which only '
    'verifies. Holds no prompt and invokes nothing: ADR-044 defers the capability, this is the seam.';

COMMENT ON COLUMN ai_provider.send_record_content IS
    'Whether record content — finding descriptions, evidence, recovered secrets — may be sent to this '
    'provider, or only aggregates. Defaults FALSE: enabling a provider must not silently become a '
    'decision to egress the group''s exploitable attack surface (risk surface 5).';

COMMENT ON COLUMN ai_provider.key_fingerprint IS
    'A short digest prefix, never characters of the key itself. Answers "is this the key I just '
    'pasted" without leaking any part of a live bearer credential.';

-- Serves: the settings page listing a tenant's providers, and the agent resolving the active one.
-- Both go tenant-first and read active rows; ordering by label is what the page renders.
CREATE INDEX IF NOT EXISTS ix_ai_provider__tenant_active
    ON ai_provider (tenant_id, active, label);
COMMENT ON INDEX ix_ai_provider__tenant_active IS
    'Serves: the provider list on the settings page, and the agent''s lookup of an active provider '
    'for the current tenant.';

GRANT SELECT, INSERT, UPDATE ON ai_provider TO app_runtime;
-- No DELETE. A provider that was used to produce an observation must remain resolvable, or `basis`
-- on a stored suggestion points at nothing — and ADR-005 makes the suggestion ledger the record of
-- what the AI said. Deactivation is the removal that is available.
GRANT SELECT ON ai_provider TO integrity_verifier;
SELECT apply_tenant_isolation('ai_provider');

-- -----------------------------------------------------------------------------
-- The permission. Restricted AND step-up, for two independent reasons: the operation accepts a live
-- third-party credential, and it decides what may leave the platform. Either alone would justify
-- step-up; a session that has merely been resumed should carry neither.
-- -----------------------------------------------------------------------------
INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up)
VALUES ('cfg.ai.manage', 'cfg',
        '{"en":"Configure AI model providers","vi":"Cấu hình nhà cung cấp mô hình AI"}'::jsonb,
        true, true)
ON CONFLICT (code) DO NOTHING;

DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        -- Granted where the alert destinations are already managed: deciding where the group's
        -- vulnerability news is sent and deciding which third party may read it are the same kind of
        -- decision about egress, held by the same person. No role is invented (ADR-027).
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'cfg.ai.manage'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'sbm.alert.manage')
        ON CONFLICT DO NOTHING;
    END LOOP;
END
$$;
