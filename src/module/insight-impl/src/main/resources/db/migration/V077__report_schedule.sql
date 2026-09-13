-- =============================================================================================
-- V077 — scheduled reports and the audit evidence artifact. DOC-12 §10–§12.
--
-- WHAT WAS MISSING. DOC-12 catalogues eleven reports, six of them "Scheduled", and requires that a
-- scheduled report be generated PER RECIPIENT with scope evaluated at generation (PRD-DSH-043), that
-- delivery failure and a recipient who lost access be surfaced to the schedule owner (PRD-DSH-045),
-- and that audit evidence be assembled on demand for a scope and period with its scope, filters,
-- generation time and aggregation basis recorded and its generation audited (PRD-DSH-046, PRD-DSH-047).
-- The platform had on-demand tabular exports and nothing scheduled, nothing per recipient, and no
-- evidence composition. This migration is the storage for schedules, their recipients, and every
-- artifact generated — scheduled or on demand.
--
-- WHY ONE ARTIFACT ROW PER RECIPIENT. "One artifact to multiple recipients is a disclosure to the
-- least-authorized among them, and a delivered report cannot be recalled." The row carries the
-- recipient; the object it points at was rendered as that recipient, under that recipient's scope at
-- that moment. Two recipients of one schedule get two rows and two objects, always.
--
-- WHY THE ARTIFACT RECORDS ITS BASIS. PRD-DSH-047: an evidence artifact whose scope and basis are not
-- recorded cannot be relied on by the auditor receiving it. `basis` holds scope, filters, period, the
-- aggregation statement and record counts — the same facts the file's About sheet states — so the row
-- and the file cannot disagree about what was assembled.
--
-- WHAT IS NOT HERE. Templates (PRD-DSH-041, SHOULD) — every report here has a product-fixed structure
-- whose four honesty mechanisms (coverage, generated-content label, normalization, aggregation basis)
-- cannot be removed because there is nothing to remove them with (PRD-DSH-042, PRD-DSH-044 satisfied
-- by construction). Branding is not offered yet; a template capability is additive to this schema.
--
-- Requirements: PRD-DSH-043, PRD-DSH-045, PRD-DSH-046, PRD-DSH-047, PRD-DSH-048, PRD-DSH-040,
-- CON-PLT-030, CON-PLT-031. Decisions: ADR-054, ADR-027.
-- =============================================================================================

CREATE TABLE IF NOT EXISTS report_schedule (
    id                    uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id             uuid        NOT NULL,
    code                  text        NOT NULL,
    display_name          text        NOT NULL,
    -- Product-fixed report kinds (DOC-12 §10 catalogue); their structure is code, not configuration.
    report_kind           text        NOT NULL,
    -- The subtree the report is about. NULL: each recipient's whole reach at generation.
    scope_node_id         uuid,
    -- How far back each run looks.
    period_days           integer     NOT NULL DEFAULT 30,
    cadence               text        NOT NULL,
    run_hour_utc          integer     NOT NULL DEFAULT 6,
    run_weekday           integer     NOT NULL DEFAULT 1,
    run_day_of_month      integer     NOT NULL DEFAULT 1,
    -- PRD-DSH-045: who is told when delivery fails or a recipient is dropped.
    owner_principal_id    uuid        NOT NULL,
    lifecycle_state       text        NOT NULL DEFAULT 'ACTIVE',
    next_run_at           timestamptz,
    last_run_at           timestamptz,
    last_outcome          text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    created_by            uuid,
    updated_at            timestamptz NOT NULL DEFAULT now(),
    updated_by            uuid,
    row_version           integer     NOT NULL DEFAULT 1,

    CONSTRAINT uq_report_schedule__tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_report_schedule__code UNIQUE (tenant_id, code),
    CONSTRAINT ck_report_schedule__code CHECK (code ~ '^[a-z][a-z0-9-]{1,31}$'),
    CONSTRAINT ck_report_schedule__display_name CHECK (length(btrim(display_name)) BETWEEN 1 AND 80),
    CONSTRAINT ck_report_schedule__kind CHECK (report_kind IN
        ('FINDING_REGISTER', 'EXCEPTION_REGISTER', 'SERVICE_LEVEL', 'COVERAGE', 'AUDIT_EVIDENCE')),
    CONSTRAINT ck_report_schedule__period CHECK (period_days BETWEEN 1 AND 730),
    CONSTRAINT ck_report_schedule__cadence CHECK (cadence IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_report_schedule__hour CHECK (run_hour_utc BETWEEN 0 AND 23),
    CONSTRAINT ck_report_schedule__weekday CHECK (run_weekday BETWEEN 1 AND 7),
    CONSTRAINT ck_report_schedule__day CHECK (run_day_of_month BETWEEN 1 AND 28),
    CONSTRAINT ck_report_schedule__lifecycle CHECK (lifecycle_state IN ('ACTIVE', 'PAUSED', 'RETIRED'))
);

SELECT apply_tenant_isolation('report_schedule');

COMMENT ON TABLE report_schedule IS
    'DOC-12 §11. A scheduled report: which product-fixed composition, for which subtree, how often, '
    'owned by whom. Rendered per recipient at run time (PRD-DSH-043); never one file for many.';

CREATE INDEX IF NOT EXISTS ix_report_schedule__due ON report_schedule (tenant_id, next_run_at) WHERE lifecycle_state = 'ACTIVE';
COMMENT ON INDEX ix_report_schedule__due IS 'Serves: the worker''s claim of due schedules.';

GRANT SELECT, INSERT, UPDATE ON report_schedule TO app_runtime;
GRANT SELECT ON report_schedule TO integrity_verifier;

CREATE TABLE IF NOT EXISTS report_schedule_recipient (
    id                    uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id             uuid        NOT NULL,
    schedule_id           uuid        NOT NULL,
    principal_id          uuid        NOT NULL,
    added_at              timestamptz NOT NULL DEFAULT now(),
    added_by              uuid,
    -- PRD-DSH-045: dropped, with the reason, when access is gone. Kept as the record that they were.
    dropped_at            timestamptz,
    dropped_reason        text,

    CONSTRAINT fk_report_schedule_recipient__schedule
        FOREIGN KEY (tenant_id, schedule_id) REFERENCES report_schedule (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_report_schedule_recipient__drop CHECK ((dropped_at IS NULL) = (dropped_reason IS NULL))
);

SELECT apply_tenant_isolation('report_schedule_recipient');

CREATE UNIQUE INDEX IF NOT EXISTS uq_report_schedule_recipient__live
    ON report_schedule_recipient (tenant_id, schedule_id, principal_id) WHERE dropped_at IS NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON report_schedule_recipient TO app_runtime;
GRANT SELECT ON report_schedule_recipient TO integrity_verifier;

CREATE TABLE IF NOT EXISTS report_artifact (
    id                    uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id             uuid        NOT NULL,
    -- NULL for an on-demand artifact (audit evidence asked for now).
    schedule_id           uuid,
    report_kind           text        NOT NULL,
    -- Always one person: the file was rendered AS them (PRD-DSH-043).
    recipient_principal_id uuid       NOT NULL,
    scope_node_id         uuid,
    period_from           date        NOT NULL,
    period_to             date        NOT NULL,
    generated_at          timestamptz NOT NULL DEFAULT now(),
    -- The person whose action produced it: the recipient for on-demand, the schedule owner for scheduled.
    generated_by          uuid        NOT NULL,
    -- s3://bucket/key when stored; NULL when streamed to the requester and not retained.
    storage_ref           text,
    byte_size             bigint      NOT NULL DEFAULT 0,
    sha256                text,
    status                text        NOT NULL DEFAULT 'GENERATED',
    failure_detail        text,
    -- PRD-DSH-047: scope, filters, period, aggregation basis, record counts.
    basis                 jsonb       NOT NULL DEFAULT '{}'::jsonb,
    download_count        integer     NOT NULL DEFAULT 0,
    last_downloaded_at    timestamptz,

    CONSTRAINT fk_report_artifact__schedule
        FOREIGN KEY (tenant_id, schedule_id) REFERENCES report_schedule (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_report_artifact__kind CHECK (report_kind IN
        ('FINDING_REGISTER', 'EXCEPTION_REGISTER', 'SERVICE_LEVEL', 'COVERAGE', 'AUDIT_EVIDENCE')),
    CONSTRAINT ck_report_artifact__period CHECK (period_to >= period_from),
    CONSTRAINT ck_report_artifact__status CHECK (status IN ('GENERATED', 'FAILED')),
    CONSTRAINT ck_report_artifact__failed_has_detail CHECK (status <> 'FAILED' OR failure_detail IS NOT NULL)
);

SELECT apply_tenant_isolation('report_artifact');

COMMENT ON TABLE report_artifact IS
    'DOC-12 §11–§12. One generated report for one recipient, with its basis recorded (PRD-DSH-047). '
    'The file lives in the export bucket under a per-tenant prefix; this row is what says it exists.';

CREATE INDEX IF NOT EXISTS ix_report_artifact__recipient ON report_artifact (tenant_id, recipient_principal_id, generated_at DESC);
COMMENT ON INDEX ix_report_artifact__recipient IS 'Serves: "my reports".';
CREATE INDEX IF NOT EXISTS ix_report_artifact__schedule ON report_artifact (tenant_id, schedule_id, generated_at DESC) WHERE schedule_id IS NOT NULL;
COMMENT ON INDEX ix_report_artifact__schedule IS 'Serves: a schedule''s run history for its owner.';

GRANT SELECT, INSERT, UPDATE ON report_artifact TO app_runtime;
GRANT SELECT ON report_artifact TO integrity_verifier;

-- Permissions (V038 pattern). Scheduling a report to recipients is a disclosure decision — restricted,
-- step-up, granted where user management is. Assembling audit evidence reads access-review output and
-- configuration history, so it is granted where user management is as well.
INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up)
VALUES ('rpt.schedule.manage', 'rpt',
        '{"en":"Schedule reports","vi":"Lên lịch báo cáo"}'::jsonb, true, true),
       ('rpt.evidence.export', 'rpt',
        '{"en":"Assemble audit evidence","vi":"Tổng hợp bằng chứng kiểm toán"}'::jsonb, true, false)
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
        SELECT r.tenant_id, r.id, p.code
          FROM role r, (VALUES ('rpt.schedule.manage'), ('rpt.evidence.export')) AS p (code)
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'iam.user.manage')
        ON CONFLICT DO NOTHING;
    END LOOP;
END
$$;
