-- =============================================================================
-- V037 — a credential a pipeline can actually use, without it being a bearer token.
--
-- THE GAP THIS CLOSES. `POST /api/v1/sbom-submissions` is class F, and the dispatcher answers 404 to
-- anything whose principal is not a service credential. Nothing could produce one:
-- `SessionPrincipalResolver` always builds a Principal with `serviceCredential = false`, and the only
-- code that ever set it true was `DevPrincipalResolver`, behind ASPM_DEV_AUTH. So on any deployment
-- shaped like production there was **no way to submit an SBOM at all** — the ingestion path the whole
-- composition module depends on was unreachable, and nothing said so.
--
-- WHY NOT AN API KEY. ADR-004: "No bearer API keys". The reasoning is not stylistic — a bearer token
-- is replayable from anywhere it leaks: a CI log, a shell history, a proxy that records request
-- headers, a crash dump. ADR-004 permits a signed request with replay protection for exactly this
-- case, and that is what this table holds the key material for.
--
-- THE SCHEME, stated here because the schema is meaningless without it:
--
--   canonical = method \n path \n content_sha256 \n timestamp \n nonce
--   signature = HMAC-SHA256(secret, canonical)
--   Authorization: ASPM-HMAC-SHA256 key=<key_id>, ts=<unix seconds>, nonce=<hex>, signature=<hex>
--   x-aspm-content-sha256: <hex sha256 of the raw body>
--
-- The secret never crosses the wire. A recorded request cannot be replayed, because the nonce is
-- single-use (section 2) and the timestamp is bounded. A recorded request cannot be RETARGETED
-- either: the method, the path and the body hash are all inside the signature, so an attacker who
-- captures an SBOM submission cannot turn it into a submission of different content.
--
-- WHAT THIS IS NOT. It is not sender-constrained in the mTLS or DPoP sense — possession of the secret
-- is still sufficient, and a secret exfiltrated from a CI runner is still a working credential until
-- it is revoked. ADR-004 ranks this below those two and permits it for CI; recording that ranking
-- here rather than implying parity is the point of this paragraph.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. service_credential — one issued key, and the identity it acts as.
--
-- IT ACTS AS A PRINCIPAL rather than being one. `principal_id` is a real row in `principal`, so every
-- audit event a submission produces attributes to something a human can look up, and revoking the
-- principal revokes the credential's reach without anybody remembering this table exists.
--
-- SCOPE IS PINNED ON THE CREDENTIAL and never supplied by the caller. SEC-AUZ-016 and the class F
-- annotation both say so; storing it here is what makes "pinned" mean something. A pipeline that
-- submits for one team cannot address an artifact belonging to another by naming it.
--
-- THE SECRET IS STORED AS A SHA-256 OF A HIGH-ENTROPY VALUE, deliberately not Argon2. Argon2 exists
-- to make a LOW-entropy secret expensive to guess; this secret is 256 bits from a CSPRNG and there is
-- nothing to guess. Paying Argon2's cost on every CI request would be a self-inflicted denial of
-- service on the hot path, and it would buy nothing. `principal_credential` keeps Argon2, because a
-- human password is exactly the case Argon2 is for.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS service_credential (
    id             uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id      uuid        NOT NULL DEFAULT current_tenant_id(),
    -- The public half. Travels in the Authorization header in clear, is safe to log, and is what an
    -- operator reads in an audit trail to say which pipeline did something.
    key_id         text        NOT NULL,
    secret_hash    bytea       NOT NULL,
    label          text        NOT NULL,
    principal_id   uuid        NOT NULL,
    scope_node_id  uuid        NOT NULL,
    -- The permissions this key may exercise. A SUBSET of what its principal holds is intersected at
    -- resolution: a key cannot be given more than the identity behind it, and narrowing it here is
    -- how one pipeline gets submission rights without inheriting everything else that identity can do.
    permissions    text[]      NOT NULL DEFAULT '{}',
    expires_at     timestamptz,
    revoked_at     timestamptz,
    revoked_by     uuid,
    revoked_reason text,
    -- Observed, not asserted. An issued key nobody has used is a key somebody can delete without
    -- breaking a pipeline, and that is only knowable if the platform records use.
    last_used_at   timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     uuid,
    CONSTRAINT pk_service_credential PRIMARY KEY (id, tenant_id),
    CONSTRAINT uq_service_credential__key UNIQUE (tenant_id, key_id),
    CONSTRAINT ck_service_credential__revocation
        CHECK (revoked_at IS NULL OR revoked_reason IS NOT NULL)
);

COMMENT ON TABLE service_credential IS
    'A signed-request credential for a pipeline (ADR-004 permits this for CI and forbids bearer API '
    'keys). The secret is a SHA-256 of a 256-bit random value — not Argon2, which exists to make a '
    'low-entropy secret expensive to guess and would only add cost on a hot path here.';
COMMENT ON COLUMN service_credential.scope_node_id IS
    'Pinned at issue. Never supplied by the caller: a class F operation revalidates against the '
    'pinned scope, and a caller-supplied scope would make that revalidation circular.';

CREATE INDEX IF NOT EXISTS ix_service_credential__live
    ON service_credential (tenant_id, key_id) WHERE revoked_at IS NULL;
COMMENT ON INDEX ix_service_credential__live IS
    'Serves: resolving a key on every signed request, which is the hot path of the ingestion API.';

-- -----------------------------------------------------------------------------
-- 2. service_request_nonce — the replay protection, and it is the primary key that enforces it.
--
-- Not a check in code. A uniqueness violation on INSERT is the enforcement: two concurrent replays of
-- the same captured request race, one inserts, the other gets a constraint violation and is refused.
-- A read-then-write check in the application would let both through under exactly the concurrency an
-- attacker would use.
--
-- Rows are swept by age rather than kept. The signature is only accepted inside a bounded clock
-- window, so a nonce older than that window can never be replayed anyway and keeping it would be an
-- unbounded table protecting nothing.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS service_request_nonce (
    tenant_id  uuid        NOT NULL DEFAULT current_tenant_id(),
    key_id     text        NOT NULL,
    nonce      text        NOT NULL,
    seen_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_service_request_nonce PRIMARY KEY (tenant_id, key_id, nonce)
);

COMMENT ON TABLE service_request_nonce IS
    'Single-use nonces. The PRIMARY KEY is the replay control: a duplicate INSERT fails, which is '
    'what makes two concurrent replays resolve to one acceptance rather than two.';

CREATE INDEX IF NOT EXISTS ix_service_request_nonce__age
    ON service_request_nonce (tenant_id, seen_at);
COMMENT ON INDEX ix_service_request_nonce__age IS
    'Serves: sweeping nonces older than the accepted clock window, which is the only reason a row '
    'here is ever read rather than inserted.';

GRANT SELECT, INSERT, UPDATE ON service_credential TO app_runtime;
GRANT SELECT, INSERT, DELETE ON service_request_nonce TO app_runtime;
GRANT SELECT ON service_credential, service_request_nonce TO integrity_verifier;

-- No DELETE on service_credential. Revocation is `revoked_at`, because the record of what a key was
-- allowed to do while it existed is what an incident review reads (PP-5).

SELECT apply_tenant_isolation('service_credential');
SELECT apply_tenant_isolation('service_request_nonce');

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relname = 'service_credential'
                      AND c.relforcerowsecurity) THEN
        RAISE EXCEPTION 'service_credential has no FORCEd row-level security. It holds the key '
                        'material for cross-tenant ingestion; TST-TEN-001 requires the isolation '
                        'path and this is the table where its absence would matter most.';
    END IF;
END
$$;
