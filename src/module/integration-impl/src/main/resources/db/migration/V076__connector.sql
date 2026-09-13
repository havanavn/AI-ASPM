-- =============================================================================================
-- V076 — connectors: one-way outbound propagation to work trackers, as options.
--
-- WHAT WAS MISSING. DOC-21 specifies the connector contract, its lifecycle, credential handling,
-- failure classification, health and circuit breaking, egress constraint, data minimization and the
-- one-way outbound reference (§2–§10), and the integration module implemented its pure half —
-- `FailureClass`, `ConnectorHealth`, `EgressPolicy`, `OutboundPropagation` — and stopped where a
-- connector would have to be stored, a credential referenced, a ticket created or an external state
-- observed. DOC-04 §12 named the three tables (`connector`, `connector_health`, `outbound_reference`);
-- none existed. This migration is that storage, plus the outbox the worker drains. The adapters —
-- Jira Cloud, Jira Data Center, GitLab, ServiceNow, a signed generic webhook — are
-- `aspm.app.integration.TrackerAdapters`.
--
-- WHY KINDS ARE A PRODUCT-FIXED LIST AND EVERYTHING ELSE IS A ROW (ADR-027). A kind is an adapter the
-- platform ships and documents — its minimum permission set on the target (PRD-CON-016) and its
-- outbound content per operation (PRD-CON-036) are properties of the code, not of a tenant. Which
-- connectors a tenant runs, against which instance, for which part of the organization tree, owned
-- by whom, is configuration and is a row. A deployment may additionally disable kinds
-- (ASPM_CONNECTOR_KINDS) for an air-gapped estate; the interface then lists each disabled kind with
-- the consequence stated (PRD-CON-054) rather than hiding it (PRD-CON-055: same code path).
--
-- WHY THERE IS NO INBOUND STATE. `outbound_reference` records what the external tracker says
-- (`external_state`, `last_observed_at`) and where it disagrees with the platform record it records a
-- DIVERGENCE for a person (`divergence_kind`, `divergence_detected_at`). Nothing in this migration —
-- no trigger, no function — writes to `finding`. A closed ticket does not close a finding
-- (PRD-CON-042, PRD-CON-043, PRD-CON-044, ADR-040).
--
-- WHY THE CREDENTIAL IS TWO REFERENCES. `credential_ref` is what the connector authenticates with;
-- `credential_previous_ref` is the one being rotated out, kept valid for an overlap period and
-- retired after the new one has proved itself (PRD-CON-022). Both are references into the secrets
-- store (PRD-CON-021, V073); the CHECK refuses a value pasted where a reference belongs.
--
-- WHY SCOPE IS ON THE CONNECTOR. PRD-CON-038: "a connector configured for one business unit must not
-- transmit another's findings, and the target system has no scope enforcement to compensate". A
-- reference is refused unless the finding's scope node is within the connector's subtree.
--
-- Requirements: PRD-CON-015, PRD-CON-016, PRD-CON-017, PRD-CON-018, PRD-CON-019, PRD-CON-020,
-- PRD-CON-021, PRD-CON-022, PRD-CON-023, PRD-CON-024, PRD-CON-025, PRD-CON-026, PRD-CON-027,
-- PRD-CON-028, PRD-CON-029, PRD-CON-031, PRD-CON-032, PRD-CON-035, PRD-CON-036, PRD-CON-037,
-- PRD-CON-038, PRD-CON-042, PRD-CON-043, PRD-CON-044, PRD-CON-045, PRD-CON-054, PRD-CON-055,
-- CON-PLT-030, CON-PLT-031, CON-PLT-032.
-- Decisions: ADR-040, ADR-052, ADR-054, ADR-027.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- 1. connector — one configured integration of a tenant. DOC-21 §2, §3.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS connector (
    id                       uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                uuid        NOT NULL,
    code                     text        NOT NULL,
    display_name             text        NOT NULL,
    -- The adapter. Product-fixed; see the header.
    kind                     text        NOT NULL,
    -- PRD-CON-018: the adapter version that last validated this configuration. An adapter upgrade
    -- re-validates and rewrites this; the tenant does not reconfigure.
    adapter_version          integer     NOT NULL DEFAULT 1,
    -- Kind-specific, displayable configuration: base URL, project key, issue type, table name.
    -- The egress destination is here and only here (PRD-CON-032). Never a secret.
    config                   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- PRD-CON-021 / PRD-CON-022: the credential in use and the one being rotated out.
    credential_ref           text,
    credential_previous_ref  text,
    rotation_verified_at     timestamptz,
    -- PRD-CON-023: when the credential stops working, as told by the administrator; the worker
    -- warns the owner ahead of it, once.
    credential_expires_at    timestamptz,
    expiry_notified_at       timestamptz,
    -- PRD-CON-038: the subtree this connector may carry findings for. NULL is the whole tenant.
    scope_node_id            uuid,
    -- PRD-CON-029: who is told when the circuit opens.
    owner_principal_id       uuid        NOT NULL,
    -- DOC-21 §3.
    lifecycle_state          text        NOT NULL DEFAULT 'CONFIGURED',
    -- The specific diagnosis behind FAILED_VALIDATION (PRD-CON-017).
    validation_diagnosis     text,
    -- How often the worker asks the tracker about each reference (PRD-CON-043).
    observe_every_minutes    integer     NOT NULL DEFAULT 60,
    created_at               timestamptz NOT NULL DEFAULT now(),
    created_by               uuid,
    updated_at               timestamptz NOT NULL DEFAULT now(),
    updated_by               uuid,
    row_version              integer     NOT NULL DEFAULT 1,

    CONSTRAINT uq_connector__tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_connector__code UNIQUE (tenant_id, code),
    CONSTRAINT ck_connector__code CHECK (code ~ '^[a-z][a-z0-9-]{1,31}$'),
    CONSTRAINT ck_connector__display_name CHECK (length(btrim(display_name)) BETWEEN 1 AND 80),
    CONSTRAINT ck_connector__kind CHECK (kind IN
        ('JIRA_CLOUD', 'JIRA_DATA_CENTER', 'GITLAB', 'SERVICENOW', 'GENERIC_WEBHOOK')),
    CONSTRAINT ck_connector__credential_is_reference CHECK (
        credential_ref IS NULL OR is_secret_reference(credential_ref)),
    CONSTRAINT ck_connector__previous_is_reference CHECK (
        credential_previous_ref IS NULL OR is_secret_reference(credential_previous_ref)),
    CONSTRAINT ck_connector__lifecycle CHECK (lifecycle_state IN
        ('CONFIGURED', 'FAILED_VALIDATION', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT ck_connector__diagnosis_when_failed CHECK (
        lifecycle_state <> 'FAILED_VALIDATION' OR validation_diagnosis IS NOT NULL),
    CONSTRAINT ck_connector__observe_interval CHECK (observe_every_minutes BETWEEN 5 AND 10080)
);

SELECT apply_tenant_isolation('connector');

COMMENT ON TABLE connector IS
    'DOC-21 §2–§3. A tenant''s configured outbound connector: Jira (Cloud or Data Center), GitLab, '
    'ServiceNow or a signed generic webhook. The destination is configuration (PRD-CON-032); the '
    'credential is a reference into the secrets store (PRD-CON-021); retiring keeps every reference it '
    'produced (PRD-CON-020).';
COMMENT ON COLUMN connector.scope_node_id IS
    'PRD-CON-038: findings outside this subtree are refused before anything leaves. NULL = tenant-wide.';

CREATE INDEX IF NOT EXISTS ix_connector__active ON connector (tenant_id, kind) WHERE lifecycle_state = 'ACTIVE';
COMMENT ON INDEX ix_connector__active IS 'Serves: the connectors offered on a finding, and the worker''s sweeps.';

GRANT SELECT, INSERT, UPDATE ON connector TO app_runtime;
GRANT SELECT ON connector TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- 2. connector_health — PRD-CON-028: observable per connector per tenant. Primary key is the
--    connector (the fourteenth PK exception of DOC-04 §16): one health row per connector, always.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS connector_health (
    connector_id             uuid        PRIMARY KEY,
    tenant_id                uuid        NOT NULL,
    last_success_at          timestamptz,
    last_attempt_at          timestamptz,
    consecutive_failures     integer     NOT NULL DEFAULT 0,
    last_failure_class       text,
    last_failure_detail      text,
    last_failure_at          timestamptz,
    circuit_state            text        NOT NULL DEFAULT 'CLOSED',
    circuit_open_reason      text,
    circuit_opened_at        timestamptz,
    -- PRD-CON-029: the moment the owner was told. A row with an open circuit and NULL here is the
    -- "silently suspended integration" the requirement forbids, and the worker fixes it on its next tick.
    owner_notified_at        timestamptz,
    backoff_until            timestamptz,
    -- PRD-CON-031: the success rate over the current period.
    period_started_at        timestamptz NOT NULL DEFAULT now(),
    period_attempts          integer     NOT NULL DEFAULT 0,
    period_successes         integer     NOT NULL DEFAULT 0,
    degraded_alerted_at      timestamptz,

    CONSTRAINT fk_connector_health__connector
        FOREIGN KEY (tenant_id, connector_id) REFERENCES connector (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_connector_health__circuit CHECK (circuit_state IN ('CLOSED', 'HALF_OPEN', 'OPEN')),
    CONSTRAINT ck_connector_health__counts CHECK (
        consecutive_failures >= 0 AND period_attempts >= 0 AND period_successes BETWEEN 0 AND period_attempts),
    CONSTRAINT ck_connector_health__open_has_reason CHECK (
        circuit_state <> 'OPEN' OR circuit_open_reason IS NOT NULL)
);

SELECT apply_tenant_isolation('connector_health');

COMMENT ON TABLE connector_health IS
    'PRD-CON-028, PRD-CON-029, PRD-CON-031. Last success, consecutive failures, failure class, circuit '
    'state and period success rate per connector. Read by the settings page and by coverage reporting.';

CREATE INDEX IF NOT EXISTS ix_connector_health__unhealthy ON connector_health (tenant_id) WHERE consecutive_failures > 0;
COMMENT ON INDEX ix_connector_health__unhealthy IS
    'Serves: the integration health view — silent failure is how coverage gaps form (PRD-CON-028).';

GRANT SELECT, INSERT, UPDATE ON connector_health TO app_runtime;
GRANT SELECT ON connector_health TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- 3. outbound_reference — the one-way reference. DOC-21 §10.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS outbound_reference (
    id                       uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                uuid        NOT NULL,
    subject_kind             text        NOT NULL,
    subject_id               uuid        NOT NULL,
    -- Where the subject lives, copied at creation for the scope check that never reaches the tracker.
    scope_node_id            uuid,
    connector_id             uuid        NOT NULL,
    -- NULL until the tracker has answered; the row exists from the moment a person asked.
    external_id              text,
    external_key             text,
    external_url             text,
    -- Observed, informational only (PRD-CON-042). Never copied onto the subject.
    external_state           text,
    external_resolved        boolean,
    last_observed_at         timestamptz,
    -- Where the platform record was when the reference was created, for the reader of a divergence.
    platform_state_at_creation text,
    -- PRD-CON-043 / PRD-CON-044: surfaced, never reconciled.
    divergence_kind          text,
    divergence_detected_at   timestamptz,
    divergence_platform_state text,
    divergence_resolved_at   timestamptz,
    divergence_resolved_by   uuid,
    divergence_resolution_note text,
    status                   text        NOT NULL DEFAULT 'PENDING',
    failure_detail           text,
    created_at               timestamptz NOT NULL DEFAULT now(),
    created_by               uuid        NOT NULL,
    updated_at               timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_outbound_reference__tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_outbound_reference__external UNIQUE (tenant_id, connector_id, external_id),
    CONSTRAINT fk_outbound_reference__connector
        FOREIGN KEY (tenant_id, connector_id) REFERENCES connector (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_outbound_reference__subject CHECK (subject_kind IN ('FINDING')),
    CONSTRAINT ck_outbound_reference__status CHECK (status IN ('PENDING', 'LINKED', 'FAILED')),
    CONSTRAINT ck_outbound_reference__linked_has_id CHECK (status <> 'LINKED' OR external_id IS NOT NULL),
    CONSTRAINT ck_outbound_reference__divergence_kind CHECK (divergence_kind IS NULL OR divergence_kind IN
        ('CLOSED_EXTERNALLY', 'REOPENED_EXTERNALLY', 'DELETED_EXTERNALLY', 'STATE_MISMATCH')),
    CONSTRAINT ck_outbound_reference__divergence_pair CHECK (
        (divergence_kind IS NULL) = (divergence_detected_at IS NULL)),
    CONSTRAINT ck_outbound_reference__resolution CHECK (
        divergence_resolved_at IS NULL OR (divergence_detected_at IS NOT NULL AND divergence_resolved_by IS NOT NULL
            AND length(btrim(coalesce(divergence_resolution_note, ''))) >= 10))
);

SELECT apply_tenant_isolation('outbound_reference');

COMMENT ON TABLE outbound_reference IS
    'DOC-21 §10, ADR-040. A ticket created in an external tracker for a platform record. The platform '
    'record is authoritative; external_state is what the tracker last said; a disagreement is a '
    'divergence for a person (PRD-CON-043). No write path from this table into finding exists.';
COMMENT ON COLUMN outbound_reference.divergence_resolution_note IS
    'PRD-CON-043: what the person decided and why, at least ten characters. Resolving a divergence '
    'changes nothing on the finding; if the finding should move, that is a finding transition, audited there.';

-- One live reference per connector per subject. A FAILED one may be retried; a LINKED one is the link.
CREATE UNIQUE INDEX IF NOT EXISTS uq_outbound_reference__subject_live
    ON outbound_reference (tenant_id, connector_id, subject_kind, subject_id) WHERE status <> 'FAILED';
CREATE INDEX IF NOT EXISTS ix_outbound_reference__subject
    ON outbound_reference (tenant_id, subject_kind, subject_id);
COMMENT ON INDEX ix_outbound_reference__subject IS 'Serves: the references shown on a finding.';
CREATE INDEX IF NOT EXISTS ix_outbound_ref__divergent
    ON outbound_reference (tenant_id, divergence_detected_at DESC)
    WHERE divergence_detected_at IS NOT NULL AND divergence_resolved_at IS NULL;
COMMENT ON INDEX ix_outbound_ref__divergent IS 'Serves: divergence for human resolution (PRD-CON-043).';
CREATE INDEX IF NOT EXISTS ix_outbound_reference__observe
    ON outbound_reference (tenant_id, connector_id, last_observed_at) WHERE status = 'LINKED';
COMMENT ON INDEX ix_outbound_reference__observe IS 'Serves: the worker''s observation sweep.';

GRANT SELECT, INSERT, UPDATE ON outbound_reference TO app_runtime;
GRANT SELECT ON outbound_reference TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- 4. outbound_operation — the outbox (ADR-054 DISPATCH class): one attempt-able unit of work against
--    one connector, leased with FOR UPDATE SKIP LOCKED, retried by failure class (PRD-CON-025).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS outbound_operation (
    id                       uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                uuid        NOT NULL,
    connector_id             uuid        NOT NULL,
    reference_id             uuid,
    kind                     text        NOT NULL,
    status                   text        NOT NULL DEFAULT 'QUEUED',
    attempts                 integer     NOT NULL DEFAULT 0,
    next_attempt_at          timestamptz NOT NULL DEFAULT now(),
    lease_until              timestamptz,
    lease_owner              text,
    last_failure_class       text,
    last_detail              text,
    requested_by             uuid,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    finished_at              timestamptz,

    CONSTRAINT fk_outbound_operation__connector
        FOREIGN KEY (tenant_id, connector_id) REFERENCES connector (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_outbound_operation__reference
        FOREIGN KEY (tenant_id, reference_id) REFERENCES outbound_reference (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_outbound_operation__kind CHECK (kind IN ('CREATE_REFERENCE', 'OBSERVE', 'PROBE')),
    CONSTRAINT ck_outbound_operation__status CHECK (status IN ('QUEUED', 'LEASED', 'DONE', 'FAILED', 'QUARANTINED')),
    CONSTRAINT ck_outbound_operation__attempts CHECK (attempts >= 0)
);

SELECT apply_tenant_isolation('outbound_operation');

COMMENT ON TABLE outbound_operation IS
    'ADR-054 DISPATCH. The connector outbox: create a reference, observe one, probe a credential. '
    'Leased by time (CON-PLT-031), retried by failure class to a terminal state (CON-PLT-032, '
    'PRD-CON-025); a DATA failure quarantines the one record (PRD-CON-026).';

CREATE INDEX IF NOT EXISTS ix_outbound_operation__due
    ON outbound_operation (tenant_id, next_attempt_at) WHERE status IN ('QUEUED', 'LEASED');
COMMENT ON INDEX ix_outbound_operation__due IS 'Serves: the worker''s claim.';
CREATE INDEX IF NOT EXISTS ix_outbound_operation__connector
    ON outbound_operation (tenant_id, connector_id, created_at DESC);
COMMENT ON INDEX ix_outbound_operation__connector IS 'Serves: the connector''s operation history on the settings page.';

GRANT SELECT, INSERT, UPDATE ON outbound_operation TO app_runtime;
GRANT SELECT ON outbound_operation TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- 5. Permissions (V038 pattern). Managing a connector is authority over an egress destination for
--    content about the estate — restricted, step-up. Creating a reference is a triage decision about
--    one finding, granted where triage is.
-- ---------------------------------------------------------------------------------------------
INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up)
VALUES ('int.connector.manage', 'int',
        '{"en":"Configure outbound connectors","vi":"Cấu hình kết nối ra ngoài"}'::jsonb, true, true),
       ('int.reference.create', 'int',
        '{"en":"Create and resolve external tracker references","vi":"Tạo và xử lý tham chiếu tới hệ thống theo dõi ngoài"}'::jsonb, false, false)
ON CONFLICT (code) DO UPDATE
   SET is_restricted    = EXCLUDED.is_restricted,
       requires_step_up = EXCLUDED.requires_step_up,
       label_i18n       = EXCLUDED.label_i18n;

DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'int.connector.manage'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'iam.user.manage')
        ON CONFLICT DO NOTHING;
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'int.reference.create'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'vul.finding.triage')
        ON CONFLICT DO NOTHING;
    END LOOP;
END
$$;
