-- =============================================================================================
-- V074 — federated authentication: tenant-configured OpenID Connect providers.
--
-- WHAT WAS MISSING. `PRD-IAM-001` and `SEC-SEC-002` make federated authentication through the
-- tenant's own identity provider the PRIMARY method, with local credentials only where no provider is
-- configured and for break-glass. ADR-004 requires OIDC/OAuth2 for humans. ADR-059 narrowed that to a
-- local password plus TOTP for the internal-first deployment and wrote, as its revisit trigger,
-- "revisit when the first commercial tenant with an existing identity provider is onboarded" — and
-- designed the credential model to be additive so that federation would be "a second path and not a
-- migration". This is the second path. Nothing about the local path changes shape: the same
-- `principal`, the same `principal_session`, the same resolver. What is added is how a session comes
-- to exist.
--
-- WHY PROVIDERS ARE TENANT DATA WITH A PRODUCT-FIXED PRESET LIST. A conglomerate brings Okta, or
-- Microsoft Entra ID, or a self-hosted Keycloak, or Google Workspace — and sometimes two of them,
-- because two subsidiaries have not finished merging directories. So a tenant may configure several
-- providers, each a row here (ADR-027: configuration, not code). The `preset` column is NOT a
-- tenant vocabulary: it is the platform's knowledge of how a known provider deviates from the generic
-- protocol (claim names, discovery quirks, whether it asserts MFA), the same kind of product property
-- as an annotation class, and `GENERIC_OIDC` covers every provider the platform has no preset for.
-- A CHECK is therefore correct here and would be wrong on a tenant-defined field (DOC-00 §9.3).
--
-- WHY THE CLIENT SECRET IS A REFERENCE. `PRD-CON-021`: a credential is held in the secrets store by
-- reference and never in a configuration row. The column is CHECKed to have the SHAPE of a reference
-- (`is_secret_reference`, V073), so a value pasted into it is refused by the engine before any code
-- runs. A public client (PKCE only, no secret) leaves it NULL; the flow always uses PKCE regardless.
--
-- WHY GROUP-TO-ROLE MAPPING IS HERE AND NOT IN IDENTITY SYNCHRONIZATION. ADR-041: identity
-- synchronization manages principal EXISTENCE and never writes role assignments; "group-to-role
-- mapping is platform-side configuration evaluated at authentication". That evaluation needs a place
-- to live, and this is it. An assignment produced by the mapping is written with `source_provider_id`
-- set, so it is distinguishable from one an administrator granted, is reconciled on the next sign-in
-- (a group the person left revokes the assignment it produced, with the reason recorded), and never
-- touches an assignment somebody granted by hand.
--
-- WHY THE LOGIN STATE IS A TABLE. The authorization-code flow spans two requests through a browser
-- that may land on a different replica. `state` (CSRF), `nonce` (token replay) and the PKCE verifier
-- have to be found again on the callback, and a row with an expiry and a consumed_at is how they are
-- found once and never twice (`SEC-SEC-009`'s single-use property, applied to the handshake).
--
-- WHAT LOCAL SIGN-IN BECOMES. `password_policy.local_sign_in_enabled` — on by default, so nothing
-- changes for a deployment with no provider. A tenant that turns it off keeps exactly one exception:
-- a principal flagged `break_glass_local_sign_in`, set by an administrator and audited, may still use
-- a password — because the operator's own access cannot depend on an external provider being
-- reachable (ADR-059's revisit trigger says so in as many words).
--
-- Requirements: PRD-IAM-001, PRD-IAM-002, PRD-IAM-004, PRD-IAM-012, SEC-SEC-002, SEC-SEC-008, SEC-SEC-009,
-- PRD-CON-010, PRD-CON-021, PRD-CON-032. Decisions: ADR-004, ADR-027, ADR-041, ADR-052, ADR-059.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- 1. identity_provider — one configured OpenID Connect provider of a tenant.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS identity_provider (
    id                          uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                   uuid        NOT NULL,

    -- The slug in the sign-in URL: /auth/<code>/start. Stable, lower-case, unique per tenant.
    code                        text        NOT NULL,
    display_name                text        NOT NULL,
    -- The platform's knowledge of a known provider. Product-fixed; see the header.
    preset                      text        NOT NULL,

    -- OpenID Connect core. The issuer is the identity the ID token must carry; discovery defaults to
    -- <issuer>/.well-known/openid-configuration and may be overridden for a provider that publishes
    -- it elsewhere. Both are https and are validated against the egress guard at save AND at use.
    issuer                      text        NOT NULL,
    discovery_url               text,
    client_id                   text        NOT NULL,
    client_secret_ref           text,
    scopes                      text[]      NOT NULL DEFAULT ARRAY['openid', 'profile', 'email'],

    -- Claim mapping. Presets fill these; a generic provider states them.
    claim_subject               text        NOT NULL DEFAULT 'sub',
    claim_username              text        NOT NULL DEFAULT 'preferred_username',
    claim_email                 text        NOT NULL DEFAULT 'email',
    claim_display_name          text        NOT NULL DEFAULT 'name',
    claim_groups                text,

    -- PRD-IAM-004: create the principal on first sign-in, or require an administrator to have created
    -- it beforehand. Restricted by email domain when the list is present.
    jit_provisioning            boolean     NOT NULL DEFAULT true,
    allowed_email_domains       text[],

    -- PRD-IAM-002 / SEC-SEC-003. When true, a session from this provider is FULLY_AUTHENTICATED at the
    -- callback and the local TOTP challenge is not presented, PROVIDED the ID token asserts a second
    -- factor (amr contains mfa/otp/hwk/swk, or acr names a multi-factor class). When false, the local
    -- challenge follows sign-in exactly as for a password.
    mfa_asserted_by_provider    boolean     NOT NULL DEFAULT false,

    lifecycle_state             text        NOT NULL DEFAULT 'ACTIVE',

    last_discovery_at           timestamptz,
    last_test_at                timestamptz,
    last_test_status            text,
    last_test_detail            text,

    created_at                  timestamptz NOT NULL DEFAULT now(),
    created_by                  uuid,
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    updated_by                  uuid,
    row_version                 integer     NOT NULL DEFAULT 1,

    CONSTRAINT uq_identity_provider__tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_identity_provider__code UNIQUE (tenant_id, code),
    CONSTRAINT ck_identity_provider__code CHECK (code ~ '^[a-z][a-z0-9-]{1,31}$'),
    CONSTRAINT ck_identity_provider__display_name CHECK (length(btrim(display_name)) BETWEEN 1 AND 80),
    CONSTRAINT ck_identity_provider__preset CHECK (preset IN
        ('OKTA', 'ENTRA_ID', 'KEYCLOAK', 'GOOGLE', 'GENERIC_OIDC')),
    CONSTRAINT ck_identity_provider__issuer_https CHECK (issuer ~ '^https://[^[:space:]]+$'),
    CONSTRAINT ck_identity_provider__discovery_https CHECK (
        discovery_url IS NULL OR discovery_url ~ '^https://[^[:space:]]+$'),
    CONSTRAINT ck_identity_provider__client_id CHECK (length(btrim(client_id)) BETWEEN 1 AND 256),
    -- A value pasted where a reference belongs is refused by the engine (PRD-CON-021).
    CONSTRAINT ck_identity_provider__secret_is_reference CHECK (
        client_secret_ref IS NULL OR is_secret_reference(client_secret_ref)),
    CONSTRAINT ck_identity_provider__scopes_include_openid CHECK ('openid' = ANY (scopes)),
    CONSTRAINT ck_identity_provider__lifecycle CHECK (lifecycle_state IN ('ACTIVE', 'DISABLED', 'RETIRED'))
);

SELECT apply_tenant_isolation('identity_provider');

COMMENT ON TABLE identity_provider IS
    'PRD-IAM-001, SEC-SEC-002, ADR-004, ADR-059. A tenant''s configured OpenID Connect provider. The '
    'client secret is a reference into the secrets store (PRD-CON-021), never a value. The preset names '
    'the platform''s knowledge of a known provider and is product-fixed; GENERIC_OIDC covers the rest.';
COMMENT ON COLUMN identity_provider.mfa_asserted_by_provider IS
    'PRD-IAM-002, SEC-SEC-003: when true and the ID token asserts a second factor (amr/acr), the '
    'session is fully authenticated at the callback and the local TOTP challenge is skipped.';
COMMENT ON COLUMN identity_provider.issuer IS
    'The iss the ID token must carry, byte for byte. Also the default base for discovery. Validated as '
    'an https public destination by the egress guard at save and again before every outbound call '
    '(PRD-CON-032, PRD-CON-033).';

-- Serves: the sign-in page listing a tenant's active providers, and the resolution of /auth/<code>/start.
CREATE INDEX IF NOT EXISTS ix_identity_provider__active
    ON identity_provider (tenant_id, code)
    WHERE lifecycle_state = 'ACTIVE';
COMMENT ON INDEX ix_identity_provider__active IS
    'Serves: the sign-in page (active providers of the tenant) and /auth/{code}/start (one provider by code).';

GRANT SELECT, INSERT, UPDATE ON identity_provider TO app_runtime;
GRANT SELECT ON identity_provider TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- 2. identity_provider_group_role — a group claim value that grants a role, evaluated at sign-in.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS identity_provider_group_role (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL,
    provider_id     uuid        NOT NULL,
    -- The value as the provider sends it: an Okta group name, an Entra group object id, a Keycloak
    -- realm role. Compared exactly; the platform does not interpret it.
    group_value     text        NOT NULL,
    role_id         uuid        NOT NULL,
    scope_node_id   uuid,
    scope_mode      text        NOT NULL DEFAULT 'TENANT',
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,

    CONSTRAINT fk_idp_group_role__provider
        FOREIGN KEY (tenant_id, provider_id) REFERENCES identity_provider (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_idp_group_role__role
        FOREIGN KEY (tenant_id, role_id) REFERENCES role (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_idp_group_role__mapping UNIQUE (tenant_id, provider_id, group_value, role_id),
    CONSTRAINT ck_idp_group_role__group CHECK (length(btrim(group_value)) BETWEEN 1 AND 256),
    CONSTRAINT ck_idp_group_role__scope_mode CHECK (scope_mode IN ('SUBTREE', 'NODE_ONLY', 'TENANT')),
    CONSTRAINT ck_idp_group_role__scope_present CHECK (
        (scope_mode = 'TENANT' AND scope_node_id IS NULL) OR (scope_mode <> 'TENANT' AND scope_node_id IS NOT NULL))
);

SELECT apply_tenant_isolation('identity_provider_group_role');

COMMENT ON TABLE identity_provider_group_role IS
    'ADR-041: group-to-role mapping is platform-side configuration evaluated at authentication. An '
    'assignment it produces carries role_assignment.source_provider_id and is reconciled on every '
    'sign-in; assignments granted by an administrator are never touched.';

GRANT SELECT, INSERT, UPDATE, DELETE ON identity_provider_group_role TO app_runtime;
GRANT SELECT ON identity_provider_group_role TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- 3. federated_identity — the link between a principal and a subject at a provider.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS federated_identity (
    id                      uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id               uuid        NOT NULL,
    principal_id            uuid        NOT NULL,
    provider_id             uuid        NOT NULL,
    -- The provider's stable subject identifier. Never the email: an email is reassignable, a sub is
    -- not, and linking on email is how a departed employee's replacement inherits their access.
    subject                 text        NOT NULL,
    email_at_link           text,
    linked_at               timestamptz NOT NULL DEFAULT now(),
    last_authenticated_at   timestamptz,

    CONSTRAINT fk_federated_identity__principal
        FOREIGN KEY (tenant_id, principal_id) REFERENCES principal (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_federated_identity__provider
        FOREIGN KEY (tenant_id, provider_id) REFERENCES identity_provider (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_federated_identity__subject UNIQUE (tenant_id, provider_id, subject),
    CONSTRAINT ck_federated_identity__subject CHECK (length(subject) BETWEEN 1 AND 512)
);

SELECT apply_tenant_isolation('federated_identity');

COMMENT ON TABLE federated_identity IS
    'PRD-IAM-001, PRD-IAM-004. Links a principal to a provider subject. Linked on sub, never on email, '
    'because an email is reassignable and a sub is not.';

-- Serves: the callback, which looks a subject up by provider.
CREATE INDEX IF NOT EXISTS ix_federated_identity__principal
    ON federated_identity (tenant_id, principal_id);
COMMENT ON INDEX ix_federated_identity__principal IS
    'Serves: the user administration page listing a principal''s linked identities.';

GRANT SELECT, INSERT, UPDATE ON federated_identity TO app_runtime;
GRANT SELECT ON federated_identity TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- 4. federated_login_state — one authorization-code handshake, found once and never twice.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS federated_login_state (
    -- The `state` parameter itself, random, 32 bytes base64url. The primary key is the replay control.
    state           text        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    provider_id     uuid        NOT NULL,
    nonce           text        NOT NULL,
    pkce_verifier   text        NOT NULL,
    redirect_target text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL DEFAULT now() + interval '10 minutes',
    consumed_at     timestamptz,

    CONSTRAINT fk_login_state__provider
        FOREIGN KEY (tenant_id, provider_id) REFERENCES identity_provider (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_login_state__lengths CHECK (length(state) >= 32 AND length(nonce) >= 32 AND length(pkce_verifier) >= 43),
    CONSTRAINT ck_login_state__bounded CHECK (expires_at <= created_at + interval '15 minutes')
);

SELECT apply_tenant_isolation('federated_login_state');

COMMENT ON TABLE federated_login_state IS
    'SEC-SEC-009 applied to the OIDC handshake: state, nonce and PKCE verifier live for ten minutes and '
    'are consumed exactly once. The primary key on state is the replay control.';

-- Serves: SessionReaper, which deletes expired or consumed handshakes on its tick.
CREATE INDEX IF NOT EXISTS ix_login_state__expiry ON federated_login_state (expires_at);
COMMENT ON INDEX ix_login_state__expiry IS 'Serves: the reaper''s sweep of expired handshakes.';

GRANT SELECT, INSERT, UPDATE, DELETE ON federated_login_state TO app_runtime;
GRANT SELECT ON federated_login_state TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- 5. Provenance on role_assignment, source context on the session, the break-glass flag, and the
--    tenant's local sign-in switch. All additive, all replay-safe.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE role_assignment ADD COLUMN IF NOT EXISTS source_provider_id uuid;
-- NOT VALID, for the reason V065 states: adding a foreign key scans the referencing table through a
-- query that runs as the migration role, and aspm_migrate does NOT bypass row-level security (the
-- attribute is not inherited from migration_runner), so the scan asks current_tenant_id() and it
-- raises. The first deployment of this file failed on exactly this statement; the embedded-engine test
-- did not catch it because that fixture is the superuser. The column is new and every existing row
-- holds NULL, so there is nothing a validation scan could find — the constraint is enforced for every
-- row written from now on, which is the property that matters.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_role_assignment__source_provider') THEN
        ALTER TABLE role_assignment ADD CONSTRAINT fk_role_assignment__source_provider
            FOREIGN KEY (tenant_id, source_provider_id) REFERENCES identity_provider (tenant_id, id)
            ON DELETE RESTRICT NOT VALID;
    END IF;
END
$$;
COMMENT ON COLUMN role_assignment.source_provider_id IS
    'ADR-041. Set when the assignment was produced by identity_provider_group_role at sign-in; NULL when '
    'an administrator granted it. Only rows with a source are reconciled against group claims.';

ALTER TABLE principal_session ADD COLUMN IF NOT EXISTS authentication_method text;
COMMENT ON COLUMN principal_session.authentication_method IS
    'PRD-IAM-012, SEC-SEC-008: how this session came to exist — LOCAL for password+TOTP, or '
    'FEDERATED:<provider code>. Source context for the session lifecycle record.';

ALTER TABLE principal ADD COLUMN IF NOT EXISTS break_glass_local_sign_in boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN principal.break_glass_local_sign_in IS
    'SEC-SEC-002: the one exception to a tenant that disabled local sign-in. Set by an administrator, '
    'audited, and the reason the operator''s own access never depends on an external provider.';

ALTER TABLE password_policy ADD COLUMN IF NOT EXISTS local_sign_in_enabled boolean NOT NULL DEFAULT true;
COMMENT ON COLUMN password_policy.local_sign_in_enabled IS
    'SEC-SEC-002: local authentication is available only where no provider is configured and for '
    'break-glass. Default true so a deployment without a provider is unchanged; a tenant that has one '
    'turns it off and keeps break_glass_local_sign_in principals as the exception.';

-- ---------------------------------------------------------------------------------------------
-- 6. authentication_attempt learns the federated factor and its rejection outcome (V016 pattern).
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    definition text;
BEGIN
    SELECT pg_get_constraintdef(oid) INTO definition
      FROM pg_constraint
     WHERE conrelid = 'authentication_attempt'::regclass AND conname = 'ck_attempt__factor';
    IF definition IS NULL THEN
        RAISE EXCEPTION 'ck_attempt__factor is absent from authentication_attempt; V074 widens it and must be revisited';
    END IF;
    IF position('FEDERATED' IN definition) = 0 THEN
        ALTER TABLE authentication_attempt DROP CONSTRAINT ck_attempt__factor;
        ALTER TABLE authentication_attempt ADD CONSTRAINT ck_attempt__factor
            CHECK (factor IN ('PASSWORD', 'TOTP', 'RECOVERY_CODE', 'STEP_UP', 'FEDERATED'));
    END IF;

    SELECT pg_get_constraintdef(oid) INTO definition
      FROM pg_constraint
     WHERE conrelid = 'authentication_attempt'::regclass AND conname = 'ck_attempt__outcome';
    IF definition IS NULL THEN
        RAISE EXCEPTION 'ck_attempt__outcome is absent from authentication_attempt; V074 widens it and must be revisited';
    END IF;
    IF position('FEDERATION_REJECTED' IN definition) = 0 THEN
        ALTER TABLE authentication_attempt DROP CONSTRAINT ck_attempt__outcome;
        ALTER TABLE authentication_attempt ADD CONSTRAINT ck_attempt__outcome
            CHECK (outcome IN ('SUCCESS', 'BAD_CREDENTIAL', 'UNKNOWN_IDENTIFIER', 'BAD_SECOND_FACTOR',
                               'THROTTLED', 'SUSPENDED', 'BREACHED_CREDENTIAL', 'FEDERATION_REJECTED',
                               'LOCAL_SIGN_IN_DISABLED'));
    END IF;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 7. The permission that governs providers (V038 pattern): its own code, restricted and step-up,
--    granted to every role that already administers users so an existing administrator is not
--    locked out of the new surface.
-- ---------------------------------------------------------------------------------------------
INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up)
VALUES ('iam.idp.manage', 'iam',
        '{"en":"Configure identity providers","vi":"Cấu hình nhà cung cấp định danh"}'::jsonb, true, true)
ON CONFLICT (code) DO UPDATE
   SET is_restricted    = true,
       requires_step_up = true,
       label_i18n       = EXCLUDED.label_i18n;

DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'iam.idp.manage'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'iam.user.manage')
        ON CONFLICT DO NOTHING;
    END LOOP;
END
$$;
