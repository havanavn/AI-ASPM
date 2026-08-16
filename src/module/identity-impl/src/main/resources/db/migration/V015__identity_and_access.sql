-- =============================================================================
-- V015 — identity and access. DOC-03 §17, DOC-07, ADR-059.
--
-- The context no prompt in IMPLEMENTATION_PROMPTS.md assigned to a session. Two authorization
-- assertions — A18 and A21 — have been disabled since prompt 12 waiting for it, and every principal
-- in the running system so far has been asserted by a development header.
--
-- ADR-059 narrows ADR-004: human authentication is a local credential plus TOTP, so the platform
-- becomes a credential holder. The tables that look like over-engineering are the ASVS Level 3
-- uplift CON-SEC-001 requires for authentication and session management.
--
-- WHAT IS DELIBERATELY NOT HERE: a role name in any constraint. PRD-AUZ-001 makes the permission
-- catalogue product-fixed and roles "composed from the catalogue by tenants, with tenant-defined
-- names". Admin, Pentester, Dev and the rest are SEEDED ROWS. A CHECK enumerating them would be the
-- same defect V014 corrected for request states.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. principal — a human or a service identity
--
-- One table, not two. A service credential and a human differ in how they authenticate and in what
-- they may hold, not in what they are: both are subjects of authorization decisions, and splitting
-- them means every authorization query becomes a union somebody will write as one branch.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS principal (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL,
    kind            text        NOT NULL DEFAULT 'HUMAN',
    -- Both are unique per tenant and either may be used to sign in. Case-folded for lookup, because
    -- a case-sensitive identifier produces support tickets while a case-insensitive PASSWORD
    -- destroys entropy — the two are not the same decision.
    username        text        NOT NULL,
    email           text,
    display_name    text        NOT NULL,
    lifecycle_state text        NOT NULL DEFAULT 'ACTIVE',
    -- ADR-059: enrolment is a STATE of the principal, not a setting. An un-enrolled principal is
    -- redirected to enrolment and every other authenticated route refuses, which is what makes the
    -- second factor impossible to decline rather than merely required by policy.
    mfa_enrolled_at timestamptz,
    must_change_password bool   NOT NULL DEFAULT false,
    last_authenticated_at timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      uuid,
    row_version     int         NOT NULL DEFAULT 1,

    CONSTRAINT uq_principal__username UNIQUE (tenant_id, username),
    CONSTRAINT uq_principal__email UNIQUE (tenant_id, email),
    CONSTRAINT ck_principal__kind CHECK (kind IN ('HUMAN', 'SERVICE')),
    CONSTRAINT ck_principal__lifecycle CHECK
        (lifecycle_state IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'DEPROVISIONED')),
    CONSTRAINT ck_principal__username_shape CHECK
        (username = lower(btrim(username)) AND length(username) BETWEEN 3 AND 128),
    CONSTRAINT ck_principal__email_shape CHECK
        (email IS NULL OR (email = lower(btrim(email)) AND email LIKE '%@%.%')),
    -- A service identity does not enrol a human second factor. Requiring one makes service
    -- provisioning impossible; permitting one suggests a service can be phished into revealing it.
    CONSTRAINT ck_principal__service_no_mfa CHECK (kind <> 'SERVICE' OR mfa_enrolled_at IS NULL)
);

SELECT apply_tenant_isolation('principal');

CREATE INDEX IF NOT EXISTS ix_principal__lookup
    ON principal (tenant_id, username) WHERE lifecycle_state <> 'DEPROVISIONED';
COMMENT ON INDEX ix_principal__lookup IS
    'Serves: sign-in by username. Partial on lifecycle so the hot path does not read rows it will '
    'reject, and so a deprovisioned identifier is not resurrected by a probe.';

CREATE INDEX IF NOT EXISTS ix_principal__email_lookup
    ON principal (tenant_id, email) WHERE email IS NOT NULL AND lifecycle_state <> 'DEPROVISIONED';
COMMENT ON INDEX ix_principal__email_lookup IS
    'Serves: sign-in by email, and the reset request which must behave identically for a known and '
    'an unknown address (SEC-SEC-016).';

-- -----------------------------------------------------------------------------
-- 2. principal_credential — the password, and the parameters it was hashed with
--
-- SEC-SEC-014: "a memory-hard password hashing function with per-credential salt and parameters
-- tuned to a target verification cost, and parameters MUST be stored alongside".
--
-- Per row, not global. That is what lets the cost be raised later: a credential verified with old
-- parameters is re-hashed on the next successful sign-in and nothing is invalidated. A global
-- parameter set forces a mass reset, which is why nobody ever raises it.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS principal_credential (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL,
    principal_id    uuid        NOT NULL REFERENCES principal (id) ON DELETE RESTRICT,
    algorithm       text        NOT NULL,
    -- Named columns rather than a blob, so a reviewer sees the cost without parsing anything and a
    -- query can find credentials below the current floor.
    memory_kib      int         NOT NULL,
    iterations      int         NOT NULL,
    parallelism     int         NOT NULL,
    salt            bytea       NOT NULL,
    hash            bytea       NOT NULL,
    set_at          timestamptz NOT NULL DEFAULT now(),
    set_by          uuid,
    -- Retired rather than deleted: reuse checking reads history, and an incident investigation reads
    -- when credentials changed.
    retired_at      timestamptz,
    retired_reason  text,

    CONSTRAINT ck_credential__algorithm CHECK (algorithm = 'ARGON2ID'),
    -- Floors, not defaults. OWASP's Argon2id minimum is 19 MiB and two passes; below that the
    -- function is memory-hard in name only.
    CONSTRAINT ck_credential__memory_floor CHECK (memory_kib >= 19456),
    CONSTRAINT ck_credential__iteration_floor CHECK (iterations >= 2),
    CONSTRAINT ck_credential__parallelism CHECK (parallelism BETWEEN 1 AND 4),
    CONSTRAINT ck_credential__salt_length CHECK (length(salt) >= 16),
    CONSTRAINT ck_credential__hash_length CHECK (length(hash) >= 32),
    CONSTRAINT ck_credential__retirement CHECK ((retired_at IS NULL) = (retired_reason IS NULL))
);

SELECT apply_tenant_isolation('principal_credential');

-- One live credential per principal. Two would make "the password" ambiguous, and the ambiguity
-- would be resolved differently by the verifier and by the reset path.
CREATE UNIQUE INDEX IF NOT EXISTS uq_credential__live
    ON principal_credential (tenant_id, principal_id) WHERE retired_at IS NULL;

-- -----------------------------------------------------------------------------
-- 3. mfa_enrolment — the TOTP secret, encrypted at rest
--
-- ADR-059 accepts that the platform becomes a credential holder; this is the part of that cost
-- which is mitigable. last_accepted_step exists because RFC 6238 §5.2 requires the verifier to
-- refuse a code it has already accepted — without it a captured code works for the rest of its
-- window.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mfa_enrolment (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL,
    principal_id    uuid        NOT NULL REFERENCES principal (id) ON DELETE RESTRICT,
    method          text        NOT NULL DEFAULT 'TOTP',
    secret_ciphertext bytea     NOT NULL,
    secret_key_ref  text        NOT NULL,
    digits          smallint    NOT NULL DEFAULT 6,
    period_seconds  smallint    NOT NULL DEFAULT 30,
    algorithm       text        NOT NULL DEFAULT 'HMAC_SHA1',
    last_accepted_step bigint,
    confirmed_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    retired_at      timestamptz,

    CONSTRAINT ck_mfa__method CHECK (method IN ('TOTP')),
    CONSTRAINT ck_mfa__digits CHECK (digits IN (6, 8)),
    CONSTRAINT ck_mfa__period CHECK (period_seconds IN (30, 60)),
    -- HMAC-SHA1 is what every authenticator implements. Recorded explicitly rather than assumed, so
    -- a future SHA-256 enrolment is a new value and not a silent behaviour change.
    CONSTRAINT ck_mfa__algorithm CHECK (algorithm IN ('HMAC_SHA1', 'HMAC_SHA256')),
    CONSTRAINT ck_mfa__secret_length CHECK (length(secret_ciphertext) >= 16)
);

SELECT apply_tenant_isolation('mfa_enrolment');

CREATE UNIQUE INDEX IF NOT EXISTS uq_mfa__live
    ON mfa_enrolment (tenant_id, principal_id) WHERE retired_at IS NULL;

CREATE TABLE IF NOT EXISTS mfa_recovery_code (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL,
    enrolment_id    uuid        NOT NULL REFERENCES mfa_enrolment (id) ON DELETE RESTRICT,
    -- Hashed. An unhashed recovery code is a password with none of a password's protections.
    code_hash       bytea       NOT NULL,
    used_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_recovery__hash_length CHECK (length(code_hash) >= 32)
);

SELECT apply_tenant_isolation('mfa_recovery_code');

-- -----------------------------------------------------------------------------
-- 4. principal_session
--
-- SEC-SEC-009: at least 128 bits of entropy, encodes no principal or tenant data, regenerated on
-- privilege change. SEC-SEC-010: absolute and idle limits, product maximum 12 hours absolute.
-- SEC-SEC-011: revocation within 60 seconds. SEC-SEC-012: a principal sees and terminates their own
-- sessions with source context.
--
-- The token is stored HASHED. A session table with raw tokens is a table of live bearer credentials,
-- and the operator with database access is inside the threat model of a platform that calls itself a
-- higher-value target than what it protects.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS principal_session (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL,
    principal_id    uuid        NOT NULL REFERENCES principal (id) ON DELETE RESTRICT,
    token_hash      bytea       NOT NULL,
    -- The second factor has not been presented yet. A session in this state reaches the challenge and
    -- nothing else, which is what makes forced enrolment enforceable rather than advisory.
    factor_state    text        NOT NULL DEFAULT 'PASSWORD_ONLY',
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz NOT NULL DEFAULT now(),
    absolute_expires_at timestamptz NOT NULL,
    idle_timeout_seconds int    NOT NULL,
    revoked_at      timestamptz,
    revoked_reason  text,
    -- Source context for SEC-SEC-012. A session list without it tells a user they have four sessions
    -- and nothing about whether one of them is not theirs.
    source_address  inet,
    source_user_agent text,

    CONSTRAINT uq_session__token UNIQUE (tenant_id, token_hash),
    CONSTRAINT ck_session__factor_state CHECK
        (factor_state IN ('PASSWORD_ONLY', 'FULLY_AUTHENTICATED')),
    CONSTRAINT ck_session__token_length CHECK (length(token_hash) >= 32),
    -- The product maximum of SEC-SEC-010, at the engine — so a tenant policy cannot exceed it and a
    -- migration importing sessions cannot either.
    CONSTRAINT ck_session__absolute_bound CHECK
        (absolute_expires_at <= created_at + interval '12 hours'),
    CONSTRAINT ck_session__idle_bound CHECK (idle_timeout_seconds BETWEEN 60 AND 43200),
    CONSTRAINT ck_session__revocation CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);

SELECT apply_tenant_isolation('principal_session');

CREATE INDEX IF NOT EXISTS ix_session__principal
    ON principal_session (tenant_id, principal_id, created_at DESC) WHERE revoked_at IS NULL;
COMMENT ON INDEX ix_session__principal IS
    'Serves: SEC-SEC-012 own-session list, and the bulk revocation SEC-SEC-016 requires on reset.';

-- -----------------------------------------------------------------------------
-- 5. password_policy — configurable, bounded by the product
--
-- Length-first. ASVS and NIST both moved away from composition rules because they produce
-- predictable substitutions and drive reuse, so the columns that would express them are ABSENT
-- rather than present and defaulted off — a disabled setting is a setting somebody enables.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS password_policy (
    tenant_id       uuid        PRIMARY KEY,
    minimum_length  int         NOT NULL DEFAULT 12,
    maximum_length  int         NOT NULL DEFAULT 128,
    reuse_history   int         NOT NULL DEFAULT 5,
    -- SEC-SEC-006: breached-credential checking at set AND at authentication.
    breach_check_at_set bool    NOT NULL DEFAULT true,
    breach_check_at_authentication bool NOT NULL DEFAULT true,
    -- Rotation defaults to OFF and exists so a tenant with a compliance obligation can enable it.
    -- Forced rotation without evidence of compromise drives weaker passwords, which is why the
    -- default is zero rather than ninety.
    maximum_age_days int        NOT NULL DEFAULT 0,
    mfa_required_for_all bool   NOT NULL DEFAULT true,
    session_absolute_seconds int NOT NULL DEFAULT 28800,
    session_idle_seconds int    NOT NULL DEFAULT 1800,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      uuid,

    CONSTRAINT ck_policy__minimum_length CHECK (minimum_length BETWEEN 12 AND 64),
    CONSTRAINT ck_policy__maximum_length CHECK (maximum_length BETWEEN 64 AND 512),
    CONSTRAINT ck_policy__length_order CHECK (maximum_length > minimum_length),
    CONSTRAINT ck_policy__reuse_history CHECK (reuse_history BETWEEN 0 AND 24),
    CONSTRAINT ck_policy__maximum_age CHECK (maximum_age_days BETWEEN 0 AND 365),
    -- The product bound of SEC-SEC-010, expressed where a tenant edits the value.
    CONSTRAINT ck_policy__absolute_bound CHECK (session_absolute_seconds BETWEEN 900 AND 43200),
    CONSTRAINT ck_policy__idle_bound CHECK (session_idle_seconds BETWEEN 60 AND 43200),
    CONSTRAINT ck_policy__idle_within_absolute CHECK (session_idle_seconds <= session_absolute_seconds)
);

SELECT apply_tenant_isolation('password_policy');

-- SEC-SEC-006. Hashes, so the table is not itself a password list. Prefix and suffix split so a
-- range query serves a check without transmitting a full hash — the shape a hosted corpus also uses.
CREATE TABLE IF NOT EXISTS breached_password (
    password_sha1_prefix char(5) NOT NULL,
    password_sha1_suffix char(35) NOT NULL,
    occurrence_count bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (password_sha1_prefix, password_sha1_suffix)
);

COMMENT ON TABLE breached_password IS
    'SEC-SEC-006. Not tenant-scoped: a breach corpus is public data and per-tenant copies multiply '
    'storage for no isolation benefit.';

CREATE OR REPLACE FUNCTION breach_corpus_size() RETURNS bigint
    LANGUAGE sql STABLE
AS $$ SELECT count(*) FROM breached_password; $$;

GRANT SELECT ON breached_password TO app_runtime;
GRANT EXECUTE ON FUNCTION breach_corpus_size() TO app_runtime;

-- -----------------------------------------------------------------------------
-- 6. credential_reset_token — SEC-SEC-016
--
-- Single-use, time-limited, out of band, invalidates all sessions on use, and does not disclose
-- whether the principal exists. The non-disclosure is a property of the RESPONSE PATH, not of this
-- table: a row cannot exist for a principal that does not. The endpoint answers identically either
-- way and takes the same time, and that is where the test asserts it.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS credential_reset_token (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL,
    principal_id    uuid        NOT NULL REFERENCES principal (id) ON DELETE RESTRICT,
    token_hash      bytea       NOT NULL,
    issued_at       timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    used_at         timestamptz,
    issued_by       uuid,
    delivery_channel text       NOT NULL DEFAULT 'EMAIL',

    CONSTRAINT uq_reset__token UNIQUE (tenant_id, token_hash),
    CONSTRAINT ck_reset__token_length CHECK (length(token_hash) >= 32),
    -- Short: a reset link is a bearer credential for the account, and an hour is already generous.
    CONSTRAINT ck_reset__lifetime CHECK (expires_at <= issued_at + interval '1 hour'),
    CONSTRAINT ck_reset__channel CHECK (delivery_channel IN ('EMAIL', 'ADMIN_HANDOVER'))
);

SELECT apply_tenant_isolation('credential_reset_token');

-- -----------------------------------------------------------------------------
-- 7. permission catalogue, role, assignment — PRD-AUZ-001
--
-- "The platform MUST define a product-fixed catalogue enumerating every distinct permitted action.
-- Roles MUST be composed from the catalogue by tenants, with tenant-defined names."
--
-- So no role name appears in a constraint. The permission CODE is validated against the catalogue,
-- which is product-fixed; the role that groups codes is tenant data.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS permission_catalogue (
    code            text        PRIMARY KEY,
    domain          text        NOT NULL,
    label_i18n      jsonb       NOT NULL,
    is_restricted   bool        NOT NULL DEFAULT false,
    requires_step_up bool       NOT NULL DEFAULT false,

    -- The shape PermissionId enforces in code, enforced here too, so a seed cannot introduce a code
    -- the application would reject at runtime.
    CONSTRAINT ck_permission__shape CHECK (code ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$')
);

COMMENT ON TABLE permission_catalogue IS
    'PRD-AUZ-001: product-fixed, and no tenant may add a row. SEC-AUZ-001 makes a catalogue entry '
    'that gates nothing a defect, which cannot be checked if tenants invent codes.';

GRANT SELECT ON permission_catalogue TO app_runtime, integrity_verifier;

CREATE TABLE IF NOT EXISTS role (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL,
    code            text        NOT NULL,
    label_i18n      jsonb       NOT NULL,
    description     text,
    -- The template a tenant started from, recorded so a later product change to the template can be
    -- OFFERED rather than applied. DOC-07 §5.3's templates are a starting point, not a binding.
    derived_from_template text,
    lifecycle_state text        NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      uuid,
    row_version     int         NOT NULL DEFAULT 1,

    CONSTRAINT uq_role__code UNIQUE (tenant_id, code),
    CONSTRAINT ck_role__lifecycle CHECK (lifecycle_state IN ('ACTIVE', 'DEPRECATED'))
);

SELECT apply_tenant_isolation('role');

CREATE TABLE IF NOT EXISTS role_permission (
    tenant_id       uuid        NOT NULL,
    role_id         uuid        NOT NULL REFERENCES role (id) ON DELETE RESTRICT,
    permission_code text        NOT NULL REFERENCES permission_catalogue (code) ON DELETE RESTRICT,
    granted_at      timestamptz NOT NULL DEFAULT now(),
    granted_by      uuid,

    PRIMARY KEY (tenant_id, role_id, permission_code)
);

SELECT apply_tenant_isolation('role_permission');

-- The assignment carries the SCOPE. DOC-07 §7: a role without a scope is a role over everything, and
-- product principle 4 makes scope derived rather than asserted — so the grant names the node and the
-- resolver expands it through the closure table.
CREATE TABLE IF NOT EXISTS role_assignment (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL,
    principal_id    uuid        NOT NULL REFERENCES principal (id) ON DELETE RESTRICT,
    role_id         uuid        NOT NULL REFERENCES role (id) ON DELETE RESTRICT,
    scope_node_id   uuid,
    scope_mode      text        NOT NULL DEFAULT 'SUBTREE',
    granted_at      timestamptz NOT NULL DEFAULT now(),
    granted_by      uuid,
    expires_at      timestamptz,
    revoked_at      timestamptz,
    revoked_reason  text,

    CONSTRAINT ck_assignment__scope_mode CHECK (scope_mode IN ('SUBTREE', 'NODE_ONLY', 'TENANT')),
    -- A TENANT-wide assignment names no node; anything else must. A subtree grant with no root is a
    -- grant over the whole tenant written as if it were narrow.
    CONSTRAINT ck_assignment__scope_present CHECK
        ((scope_mode = 'TENANT') = (scope_node_id IS NULL)),
    CONSTRAINT ck_assignment__revocation CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);

SELECT apply_tenant_isolation('role_assignment');

CREATE UNIQUE INDEX IF NOT EXISTS uq_assignment__live
    ON role_assignment (tenant_id, principal_id, role_id, coalesce(scope_node_id, id))
    WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_assignment__principal
    ON role_assignment (tenant_id, principal_id) WHERE revoked_at IS NULL;
COMMENT ON INDEX ix_assignment__principal IS
    'Serves: resolving a principal''s permissions and scope on every authenticated request — the '
    'hottest read in the platform after tenant context itself.';

-- -----------------------------------------------------------------------------
-- 8. authentication_attempt — SEC-SEC-005, PRD-IAM-012, SEC-PLT-003
--
-- "Authentication throttling MUST degrade an attacker without permitting lockout of a named
-- principal at will: progressive delay and risk-based challenge rather than account disable."
--
-- So the delay is computed from this table and THERE IS NO locked_until COLUMN ON principal. Its
-- absence is the control: an attacker who can lock any named account has a denial-of-service against
-- the platform, and the first account they would lock is the one that could stop them.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS authentication_attempt (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL,
    -- The IDENTIFIER as presented, not a principal reference: a failed attempt against an unknown
    -- username has no principal, and those attempts are exactly the enumeration signal SEC-PLT-003
    -- reads. Recording only resolved principals would discard them.
    presented_identifier text   NOT NULL,
    principal_id    uuid,
    outcome         text        NOT NULL,
    factor          text        NOT NULL,
    source_address  inet,
    source_user_agent text,
    occurred_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_attempt__outcome CHECK (outcome IN
        ('SUCCESS', 'BAD_CREDENTIAL', 'UNKNOWN_IDENTIFIER', 'BAD_SECOND_FACTOR',
         'THROTTLED', 'SUSPENDED', 'BREACHED_CREDENTIAL')),
    CONSTRAINT ck_attempt__factor CHECK (factor IN ('PASSWORD', 'TOTP', 'RECOVERY_CODE'))
);

SELECT apply_tenant_isolation('authentication_attempt');

CREATE INDEX IF NOT EXISTS ix_attempt__throttle
    ON authentication_attempt (tenant_id, presented_identifier, occurred_at DESC);
COMMENT ON INDEX ix_attempt__throttle IS
    'Serves: the progressive delay of SEC-SEC-005, computed from recent attempts against the '
    'presented identifier.';

CREATE INDEX IF NOT EXISTS ix_attempt__source
    ON authentication_attempt (tenant_id, source_address, occurred_at DESC);
COMMENT ON INDEX ix_attempt__source IS
    'Serves: SEC-PLT-003 enumeration detection, which reads by SOURCE across varied identifiers '
    'rather than by identifier.';

-- Append-only to the application. An attempt log an intruder can edit is an attempt log they tidy up
-- after themselves.
CREATE OR REPLACE FUNCTION reject_attempt_rewrite() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'the authentication attempt log is append-only (PRD-IAM-012). Every authentication event '
        'including failures is audited with source context, and a log an intruder can tidy is not one.'
        USING ERRCODE = 'integrity_constraint_violation';
END
$$;

DROP TRIGGER IF EXISTS trg_attempt__append_only ON authentication_attempt;
CREATE TRIGGER trg_attempt__append_only
    BEFORE UPDATE OR DELETE ON authentication_attempt
    FOR EACH ROW EXECUTE FUNCTION reject_attempt_rewrite();

-- -----------------------------------------------------------------------------
-- 9. Grants
-- -----------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON principal, principal_credential, mfa_enrolment,
    mfa_recovery_code, principal_session, password_policy, credential_reset_token,
    role, role_permission, role_assignment TO app_runtime;
GRANT DELETE ON role_permission TO app_runtime;
GRANT SELECT, INSERT ON authentication_attempt TO app_runtime;
REVOKE UPDATE, DELETE ON authentication_attempt FROM app_runtime;
GRANT SELECT ON principal, principal_session, role, role_assignment, role_permission
    TO integrity_verifier;
