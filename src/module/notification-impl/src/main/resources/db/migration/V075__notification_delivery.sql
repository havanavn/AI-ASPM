-- =============================================================================================
-- V075 — notification delivery: channels as options, routing per category, an outbox the worker
-- drains, and the in-product notification centre.
--
-- WHAT WAS MISSING. DOC-13 specifies a notification subsystem end to end and the module implemented
-- its pure half — audience grouping, coalescing, bulk suppression, escalation arithmetic
-- (`NotificationDispatch`, `EscalationChain`) — and stopped where a rendered notification would have
-- to go somewhere. There was no channel, no delivery record, no queue, no preference and no
-- notification centre; `alert_webhook` (V040) delivered one event kind to one destination kind and
-- was the only outbound path in the platform. `PRD-NTF-003` asks for in-product, email and outbound
-- integration channels selected per event category per user; `PRD-NTF-042` for retry with backoff
-- and an outcome recorded per notification; `PRD-NTF-043` for a verified address before content is
-- sent to it; `PRD-NTF-018` for an in-product channel that is always on. This migration is the
-- storage for all of that. The senders — SMTP, Slack (incoming webhook and bot API), Microsoft Teams,
-- a signed generic webhook — are `aspm.app.notification.channel`.
--
-- WHY CHANNEL KINDS ARE A PRODUCT-FIXED LIST AND ROUTES ARE TENANT DATA. A kind is an adapter the
-- platform ships (DOC-13 §14.1: "a new channel implements the delivery contract"), the same kind of
-- product property as an identity-provider preset; the CHECK is correct. Which channels a tenant
-- configures, which category goes where, and what each person wants are configuration
-- (`CFG-NTF-001`) and are rows.
--
-- WHY THE OUTBOX IS A TABLE (ADR-054). The queue is platform-owned rows in the operational store,
-- claimed with `FOR UPDATE SKIP LOCKED`, leased with an expiry (`CON-PLT-031`: reclaimed by time, not
-- by heartbeat), retried with bounded backoff to a terminal state (`CON-PLT-032`). It is the DISPATCH
-- work class of DOC-02 §12.1. Several worker replicas can drain it without leader election because a
-- lease is a row lock plus a timestamp; `OPS-DEP-007`'s singleton scheduler is for work that must not
-- run twice, and a delivery attempt that runs twice is an at-least-once delivery, which is what the
-- class promises.
--
-- WHY THE SECRET IS A REFERENCE. A Slack incoming-webhook URL is a credential (anybody holding it can
-- post as the integration); so is an SMTP password, a bot token, a signing key. Each lives in the
-- secrets store by reference (`PRD-CON-021`, V073), and the row's CHECK refuses a value pasted where a
-- reference belongs. `config` holds only what is safe to display.
--
-- WHY VERIFICATION IS A CODE, NOT A CHECKBOX. `PRD-NTF-043`: content is sent to a new address only
-- after the address has proved it is reachable and intended. The channel receives a six-digit code
-- through itself; the administrator types it back; nothing else ever reaches the channel first.
--
-- Requirements: PRD-NTF-003, PRD-NTF-011, PRD-NTF-013, PRD-NTF-014, PRD-NTF-018, PRD-NTF-019,
-- PRD-NTF-020, PRD-NTF-021, PRD-NTF-029, PRD-NTF-031, PRD-NTF-032, PRD-NTF-041, PRD-NTF-042,
-- PRD-NTF-043, PRD-NTF-045, CFG-NTF-001, PRD-CON-021, CON-PLT-030, CON-PLT-031, CON-PLT-032.
-- Decisions: ADR-054, ADR-052, ADR-027.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- 1. notification_channel — one configured destination kind of a tenant.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_channel (
    id                    uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id             uuid        NOT NULL,
    code                  text        NOT NULL,
    display_name          text        NOT NULL,
    -- The adapter. Product-fixed; see the header.
    kind                  text        NOT NULL,
    -- Kind-specific, displayable configuration: SMTP host/port/tls mode/from address; a Slack bot's
    -- default channel; a generic webhook's URL. Never a secret. Validated by the adapter at save.
    config                jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- The credential: SMTP password, Slack webhook URL or bot token, Teams webhook URL, webhook
    -- signing key. A reference into the secrets store, or NULL for a channel that needs none.
    secret_ref            text,
    -- PRD-NTF-032: external content is subject identity and a link by default; a tenant opts a
    -- channel into detail explicitly.
    include_detail        boolean     NOT NULL DEFAULT false,
    -- PRD-NTF-043. Nothing but the verification code is sent before verified_at is set.
    verification_hash     bytea,
    verification_sent_at  timestamptz,
    verification_target   text,
    verified_at           timestamptz,
    lifecycle_state       text        NOT NULL DEFAULT 'ACTIVE',
    -- PRD-NTF-042, PRD-CON-028: health an administrator can see without reading a log.
    last_delivery_at      timestamptz,
    last_status           text,
    last_detail           text,
    consecutive_failures  integer     NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL DEFAULT now(),
    created_by            uuid,
    updated_at            timestamptz NOT NULL DEFAULT now(),
    updated_by            uuid,
    row_version           integer     NOT NULL DEFAULT 1,

    CONSTRAINT uq_notification_channel__tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_notification_channel__code UNIQUE (tenant_id, code),
    CONSTRAINT ck_notification_channel__code CHECK (code ~ '^[a-z][a-z0-9-]{1,31}$'),
    CONSTRAINT ck_notification_channel__display_name CHECK (length(btrim(display_name)) BETWEEN 1 AND 80),
    CONSTRAINT ck_notification_channel__kind CHECK (kind IN
        ('EMAIL_SMTP', 'SLACK_WEBHOOK', 'SLACK_API', 'TEAMS_WEBHOOK', 'GENERIC_WEBHOOK')),
    CONSTRAINT ck_notification_channel__secret_is_reference CHECK (
        secret_ref IS NULL OR is_secret_reference(secret_ref)),
    CONSTRAINT ck_notification_channel__lifecycle CHECK (lifecycle_state IN ('ACTIVE', 'DISABLED', 'RETIRED')),
    CONSTRAINT ck_notification_channel__verification CHECK (
        (verification_hash IS NULL) = (verification_sent_at IS NULL))
);

SELECT apply_tenant_isolation('notification_channel');

COMMENT ON TABLE notification_channel IS
    'PRD-NTF-003, CFG-NTF-001, DOC-13 §5. A tenant''s configured delivery channel: SMTP, Slack '
    '(incoming webhook or bot API), Microsoft Teams, or a signed generic webhook. The credential is a '
    'reference into the secrets store (PRD-CON-021). Nothing is delivered before verified_at (PRD-NTF-043).';
COMMENT ON COLUMN notification_channel.include_detail IS
    'PRD-NTF-032: external content defaults to subject identity and a link; detail only where the tenant '
    'enabled it for this channel.';

CREATE INDEX IF NOT EXISTS ix_notification_channel__active
    ON notification_channel (tenant_id, kind) WHERE lifecycle_state = 'ACTIVE';
COMMENT ON INDEX ix_notification_channel__active IS
    'Serves: route resolution at enqueue (active channels of the tenant) and the settings page.';

GRANT SELECT, INSERT, UPDATE ON notification_channel TO app_runtime;
GRANT SELECT ON notification_channel TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- 2. notification_route — which channel carries which category, for everyone or for one person.
--    PRD-NTF-019: channel selection is per event category per recipient. A row with no recipient is
--    the tenant's default for the category; a row with one overrides it for that person.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_route (
    id                      uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id               uuid        NOT NULL,
    category                text        NOT NULL,
    channel_id              uuid        NOT NULL,
    recipient_principal_id  uuid,
    -- Where on the channel: an email address override, a Slack channel id. NULL means the
    -- recipient's own address (email) or the channel's configured default (chat).
    address                 text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,

    CONSTRAINT fk_notification_route__channel
        FOREIGN KEY (tenant_id, channel_id) REFERENCES notification_channel (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_notification_route__category CHECK (category ~ '^[A-Z][A-Z_]{1,31}$'),
    CONSTRAINT ck_notification_route__address CHECK (address IS NULL OR length(address) BETWEEN 3 AND 320)
);

SELECT apply_tenant_isolation('notification_route');

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_route__default
    ON notification_route (tenant_id, category, channel_id) WHERE recipient_principal_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_route__personal
    ON notification_route (tenant_id, category, channel_id, recipient_principal_id) WHERE recipient_principal_id IS NOT NULL;
COMMENT ON TABLE notification_route IS
    'PRD-NTF-019, CFG-NTF-001. A category carried by a channel, for the tenant (no recipient) or for one '
    'person. Resolved at enqueue; the in-product channel needs no row because it is always on (PRD-NTF-018).';

GRANT SELECT, INSERT, UPDATE, DELETE ON notification_route TO app_runtime;
GRANT SELECT ON notification_route TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- 3. notification_preference — what one person wants. PRD-NTF-020, PRD-NTF-021.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_preference (
    tenant_id               uuid        NOT NULL,
    principal_id            uuid        NOT NULL,
    -- PRD-NTF-020's single control: everything that is not mandatory, off.
    mute_non_mandatory      boolean     NOT NULL DEFAULT false,
    -- Categories this person unsubscribed from individually. Mandatory categories are refused by code.
    muted_categories        text[]      NOT NULL DEFAULT '{}',
    -- PRD-NTF-021: minutes after midnight in the person''s zone; non-mandatory deliveries are deferred
    -- to the end of the window. NULL means no quiet hours.
    quiet_start_minute      integer,
    quiet_end_minute        integer,
    timezone                text        NOT NULL DEFAULT 'UTC',
    -- PRD-NTF-034: content is rendered in the recipient's locale. The platform's locales are en and
    -- vi (NFR-INT-003); the default is the source locale until the person chooses.
    locale                  text        NOT NULL DEFAULT 'en',
    updated_at              timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_notification_preference PRIMARY KEY (tenant_id, principal_id),
    CONSTRAINT ck_notification_preference__locale CHECK (locale IN ('en', 'vi')),
    CONSTRAINT ck_notification_preference__quiet CHECK (
        (quiet_start_minute IS NULL) = (quiet_end_minute IS NULL)
        AND (quiet_start_minute IS NULL OR (quiet_start_minute BETWEEN 0 AND 1439 AND quiet_end_minute BETWEEN 0 AND 1439))),
    CONSTRAINT ck_notification_preference__timezone CHECK (length(timezone) BETWEEN 1 AND 64)
);

SELECT apply_tenant_isolation('notification_preference');

COMMENT ON TABLE notification_preference IS
    'PRD-NTF-020, PRD-NTF-021. Per-person mute and quiet hours. Mandatory categories are not muteable '
    'and are never deferred (PRD-NTF-017); the code refuses to store them here.';

GRANT SELECT, INSERT, UPDATE ON notification_preference TO app_runtime;
GRANT SELECT ON notification_preference TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- 4. notification — one rendered notification for one recipient. The in-product channel IS this
--    table (PRD-NTF-018). Rendered per recipient (PRD-NTF-014): one row per person, never shared.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification (
    id                      uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id               uuid        NOT NULL,
    recipient_principal_id  uuid        NOT NULL,
    category                text        NOT NULL,
    event_kind              text        NOT NULL,
    subject_kind            text        NOT NULL,
    subject_id              uuid,
    -- Where the subject lives in the organization tree, for the visibility re-check at delivery
    -- (PRD-NTF-029). NULL for a subject with no scope (a platform event).
    scope_node_id           uuid,
    mandatory               boolean     NOT NULL DEFAULT false,
    locale                  text        NOT NULL DEFAULT 'en',
    title                   text        NOT NULL,
    body                    text,
    link                    text,
    -- PRD-NTF-024: merged events in the coalescing window, stated as a count.
    merged_count            integer     NOT NULL DEFAULT 1,
    actor_principal_id      uuid,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    read_at                 timestamptz,

    -- V065's convention: the parent side of a tenant-carrying foreign key.
    CONSTRAINT uq_notification__tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_notification__title CHECK (length(title) BETWEEN 1 AND 300),
    CONSTRAINT ck_notification__link CHECK (link IS NULL OR link ~ '^/[^[:space:]]*$'),
    CONSTRAINT ck_notification__merged CHECK (merged_count >= 1)
);

SELECT apply_tenant_isolation('notification');

COMMENT ON TABLE notification IS
    'PRD-NTF-014, PRD-NTF-018, PRD-NTF-024. One rendered notification per recipient: the in-product '
    'channel and the source every external delivery renders from. Links are paths on this origin only.';

-- Serves: the notification centre (a person''s unread and recent notifications, newest first).
CREATE INDEX IF NOT EXISTS ix_notification__recipient
    ON notification (tenant_id, recipient_principal_id, created_at DESC);
COMMENT ON INDEX ix_notification__recipient IS 'Serves: the notification centre, newest first, per person.';
-- Serves: coalescing at enqueue — the open notification for the same recipient, subject and event.
CREATE INDEX IF NOT EXISTS ix_notification__coalesce
    ON notification (tenant_id, recipient_principal_id, subject_id, event_kind) WHERE read_at IS NULL;
COMMENT ON INDEX ix_notification__coalesce IS
    'Serves: PRD-NTF-024 coalescing — find the unread notification for the same recipient, subject and event '
    'within the window and merge into it.';

GRANT SELECT, INSERT, UPDATE ON notification TO app_runtime;
GRANT SELECT ON notification TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- 5. notification_delivery — the outbox. ADR-054, DISPATCH class.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_delivery (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id           uuid        NOT NULL,
    notification_id     uuid        NOT NULL,
    channel_id          uuid        NOT NULL,
    -- The resolved destination: an email address, a Slack channel id, or NULL for a webhook whose
    -- destination is the channel itself.
    address             text,
    status              text        NOT NULL DEFAULT 'QUEUED',
    attempts            integer     NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz NOT NULL DEFAULT now(),
    lease_until         timestamptz,
    lease_owner         text,
    last_failure_class  text,
    last_detail         text,
    sent_at             timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_notification_delivery__notification
        FOREIGN KEY (tenant_id, notification_id) REFERENCES notification (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_notification_delivery__channel
        FOREIGN KEY (tenant_id, channel_id) REFERENCES notification_channel (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_notification_delivery__status CHECK (status IN
        ('QUEUED', 'LEASED', 'SENT', 'FAILED', 'SUPPRESSED', 'DEAD')),
    CONSTRAINT ck_notification_delivery__attempts CHECK (attempts BETWEEN 0 AND 20),
    CONSTRAINT ck_notification_delivery__lease CHECK ((lease_until IS NULL) = (lease_owner IS NULL)),
    CONSTRAINT ck_notification_delivery__sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

SELECT apply_tenant_isolation('notification_delivery');

COMMENT ON TABLE notification_delivery IS
    'ADR-054, PRD-NTF-042, CON-PLT-031, CON-PLT-032. The dispatch outbox: one row per external delivery, '
    'claimed FOR UPDATE SKIP LOCKED, leased by time, retried with bounded backoff to SENT, DEAD (terminal '
    'failure) or SUPPRESSED (the subject is no longer visible to the recipient, PRD-NTF-031). Every attempt '
    'updates the row, so the outcome per notification is a fact and not an inference from a log.';

-- Serves: the worker''s claim — due, unleased or lease-expired rows, oldest first.
CREATE INDEX IF NOT EXISTS ix_notification_delivery__due
    ON notification_delivery (tenant_id, next_attempt_at)
    WHERE status IN ('QUEUED', 'LEASED');
COMMENT ON INDEX ix_notification_delivery__due IS
    'Serves: DeliveryWorker.claim — QUEUED rows due now, and LEASED rows whose lease has expired.';
-- Serves: the channel page listing recent deliveries and failures.
CREATE INDEX IF NOT EXISTS ix_notification_delivery__channel
    ON notification_delivery (tenant_id, channel_id, created_at DESC);
COMMENT ON INDEX ix_notification_delivery__channel IS 'Serves: the channel''s delivery history on the settings page.';

GRANT SELECT, INSERT, UPDATE ON notification_delivery TO app_runtime;
GRANT SELECT ON notification_delivery TO integrity_verifier;

-- ---------------------------------------------------------------------------------------------
-- 6. The permission that governs channels and routes (V038 pattern).
-- ---------------------------------------------------------------------------------------------
INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up)
VALUES ('ntf.channel.manage', 'ntf',
        '{"en":"Configure notification channels","vi":"Cấu hình kênh thông báo"}'::jsonb, true, true)
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
        SELECT r.tenant_id, r.id, 'ntf.channel.manage'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'iam.user.manage')
        ON CONFLICT DO NOTHING;
    END LOOP;
END
$$;
