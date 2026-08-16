-- =============================================================================
-- V040 — being told when a new vulnerability arrives, instead of finding out by opening a page.
--
-- WHY THIS SHAPE. Product principle 8 is scale without proportional headcount, and the dependency
-- dashboard as it stands requires somebody to look at it. A CVE that lands in a repository at 02:00
-- on a Saturday is discovered when a human next opens a browser. A webhook moves that from "somebody
-- remembered" to "the platform said so".
--
-- THE THRESHOLD IS PER SUBSCRIPTION, NOT GLOBAL. The team who owns payments wants everything; the
-- group security channel wants criticals only. One global setting forces the second to filter noise
-- they never asked for, and the predictable result is that they mute the channel — an alert nobody
-- reads is worse than no alert, because it is believed to be working.
--
-- STORED AS AN ORDINAL, NOT A LIST OF CODES. Severity is tenant-configured (ADR-027), so "at or above
-- HIGH" has to be a comparison rather than set membership: a tenant that inserts a band between HIGH
-- and CRITICAL should have it included automatically, and a stored list of codes would silently
-- exclude it. `severity_level.ordinal` is already the ranking, and this compares against it.
--
-- EGRESS. This is a new outbound path, and TST-AUZ-001 requires a new egress path to have an
-- enforcement point and a test. The enforcement point is `WebhookAlerts#deliver`: https only, and no
-- private, loopback or link-local destination. An alert endpoint pointed at 169.254.169.254 is the
-- server-side request forgery this product exists to find in other people's software. The URL is
-- tenant data and therefore cannot be an allowlist in code, so the control is the check, and the
-- check sits where the request is made rather than where the row is written.
-- =============================================================================

CREATE TABLE IF NOT EXISTS alert_webhook (
    id                   uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id            uuid        NOT NULL DEFAULT current_tenant_id(),
    label                text        NOT NULL,
    url                  text        NOT NULL,
    -- Signs the payload so a receiver can tell a delivery from this platform from anything else that
    -- discovered the URL. Stored as a digest for the same reason service_credential is: what is kept
    -- is what verification needs, not what an attacker wants.
    secret_hash          bytea,
    -- At or ABOVE this rank. Lower ordinal is more severe (CRITICAL = 1), so the test is <=.
    min_severity_ordinal integer     NOT NULL DEFAULT 2,
    -- Which part of the estate this subscription covers. NULL is the whole tenant — a real choice for
    -- a group security channel and a poor one for a team.
    scope_node_id        uuid,
    active               boolean     NOT NULL DEFAULT true,
    -- Observed, not asserted. A subscription that has never delivered is either new or broken, and
    -- only the platform can tell the difference.
    last_delivery_at     timestamptz,
    last_status          text,
    consecutive_failures integer     NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    created_by           uuid,
    CONSTRAINT pk_alert_webhook PRIMARY KEY (id, tenant_id),
    CONSTRAINT ck_alert_webhook__https CHECK (url LIKE 'https://%'),
    CONSTRAINT ck_alert_webhook__severity CHECK (min_severity_ordinal > 0)
);

COMMENT ON TABLE alert_webhook IS
    'Where to send word of a newly detected advisory, and how severe it has to be. The threshold is '
    'per subscription because one global setting forces every audience to filter noise they did not '
    'ask for, and a muted channel is worse than none — it is believed to be working.';
COMMENT ON COLUMN alert_webhook.min_severity_ordinal IS
    'Compared against severity_level.ordinal, lower being more severe. An ordinal rather than a list '
    'of codes, so a band a tenant inserts later is included rather than silently excluded.';

CREATE INDEX IF NOT EXISTS ix_alert_webhook__active
    ON alert_webhook (tenant_id) WHERE active;
COMMENT ON INDEX ix_alert_webhook__active IS
    'Serves: finding the subscriptions to notify after an ingestion, the only read on the hot path.';

-- -----------------------------------------------------------------------------
-- The delivery log. Every attempt, not every success.
--
-- A webhook that quietly stopped working is the failure this table exists for: the receiver changed
-- a URL, a certificate expired, a firewall rule landed. Recording only successes would make silence
-- indistinguishable from nothing having happened — product principle 1, applied to the platform's
-- own outbound edge.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alert_delivery (
    id           uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id    uuid        NOT NULL DEFAULT current_tenant_id(),
    webhook_id   uuid        NOT NULL,
    advisory_id  uuid,
    asset_id     uuid,
    attempted_at timestamptz NOT NULL DEFAULT now(),
    status       text        NOT NULL,
    detail       text,
    CONSTRAINT pk_alert_delivery PRIMARY KEY (id, tenant_id)
);

CREATE INDEX IF NOT EXISTS ix_alert_delivery__webhook
    ON alert_delivery (tenant_id, webhook_id, attempted_at DESC);
COMMENT ON INDEX ix_alert_delivery__webhook IS
    'Serves: the recent-attempts list beside each subscription, which is how somebody sees that a '
    'webhook stopped working rather than that nothing happened.';

GRANT SELECT, INSERT, UPDATE ON alert_webhook TO app_runtime;
GRANT SELECT, INSERT, DELETE ON alert_delivery TO app_runtime;
GRANT SELECT ON alert_webhook, alert_delivery TO integrity_verifier;

SELECT apply_tenant_isolation('alert_webhook');
SELECT apply_tenant_isolation('alert_delivery');

INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up)
VALUES ('sbm.alert.manage', 'sbm', '{"en":"Configure vulnerability alerts"}'::jsonb, false, false)
ON CONFLICT (code) DO NOTHING;

-- Granted per tenant to whichever role already administers users, for the reason V038 gives — and
-- enumerated from `tenant` with the context set inside the loop, because role_permission is
-- tenant-isolated and current_tenant_id() refuses rather than defaulting.
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'sbm.alert.manage'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'iam.user.manage')
        ON CONFLICT DO NOTHING;
    END LOOP;
END
$$;
