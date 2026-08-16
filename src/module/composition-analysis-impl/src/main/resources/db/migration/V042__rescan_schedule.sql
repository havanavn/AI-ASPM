-- =============================================================================
-- V042 — re-scanning what is already stored, on a schedule the tenant sets.
--
-- THE DEFECT THIS CLOSES. A component's advisory list only ever changed when a pipeline re-pushed.
-- So a repository that stopped building kept its vulnerability picture frozen, and — worse — a CVE
-- published AFTER its last push was invisible for ever. Log4Shell was published in December 2021; a
-- repository whose last submission was November 2021 would report clean today. That is the false
-- negative this product exists to prevent, and no amount of dashboard fixes it: the data is old.
--
-- WHO RUNS IT, AND WHY NOT THE APPLICATION. The application tier has no scheduler, and adding one
-- would make every replica run it — two replicas, two scans, two sets of duplicate work. So the
-- schedule is DATA here, decided by an administrator, and a separate container ticks and asks "is
-- anything due". The tick is dumb and the decision is in the product, which is also what makes the
-- schedule configurable in the dashboard rather than in a crontab nobody with the permission can see.
--
-- WHY THE SCANNER IS A CLIENT AND NOT A LIBRARY. The scanner container fetches the work, reads the
-- archived document, runs Trivy against it and posts the result back through the ordinary signed
-- credential path (ADR-004, V037). No new authentication, no new ingestion route, and the results
-- land through the same `SbomGraph` writer the pipeline path uses — so a finding from the scheduled
-- scan and a finding from a CI push cannot disagree about what they mean.
--
-- ADR-013 and ADR-024 hold. Trivy is run against a STORED BILL OF MATERIALS, never against source:
-- the platform still fetches no code and holds no Git credential. Scanning a document the submitter
-- gave us is not executing a scanner over their repository.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. The schedule. One row per tenant, because "how often do we re-check" is an estate-wide policy
--    and a per-repository schedule is a configuration surface nobody would maintain.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rescan_schedule (
    tenant_id       uuid        NOT NULL DEFAULT current_tenant_id(),
    enabled         boolean     NOT NULL DEFAULT false,
    -- How stale a snapshot's last scan may be before it is due again. Hours rather than a cron
    -- expression: a cron string is a small language to validate, to explain in an interface, and to
    -- get wrong in a timezone. "Not older than N hours" is the property anybody actually wants.
    interval_hours  integer     NOT NULL DEFAULT 24,
    -- How many snapshots one tick may hand out. A bound rather than a rate limit: the scanner is
    -- slow and the platform should not queue a thousand documents to a worker that will take a day.
    batch_size      integer     NOT NULL DEFAULT 25,
    last_tick_at    timestamptz,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      uuid,
    CONSTRAINT pk_rescan_schedule PRIMARY KEY (tenant_id),
    CONSTRAINT ck_rescan_schedule__interval CHECK (interval_hours BETWEEN 1 AND 8760),
    CONSTRAINT ck_rescan_schedule__batch CHECK (batch_size BETWEEN 1 AND 500)
);

COMMENT ON TABLE rescan_schedule IS
    'How often stored bills of materials are re-checked against a fresh vulnerability database. '
    'Hours rather than a cron expression: "not older than N hours" is the property anybody wants, '
    'and a cron string is a language to validate, explain and get wrong in a timezone.';

GRANT SELECT, INSERT, UPDATE ON rescan_schedule TO app_runtime;
GRANT SELECT ON rescan_schedule TO integrity_verifier;
SELECT apply_tenant_isolation('rescan_schedule');

-- -----------------------------------------------------------------------------
-- 2. When each snapshot was last scanned, and by what.
--
-- Separate from `sbom_snapshot`, which is immutable (INV-SBM-01) and cannot carry a moving
-- timestamp. That immutability is right and this is the shape it forces: the snapshot is what was
-- submitted, and this is what has since been done to it.
--
-- `intelligence_version` is what makes a re-scan meaningful rather than repeated work: it records
-- WHICH vulnerability database produced the verdict, so "scanned and clean" carries the date of the
-- knowledge behind it. Product principle 1 — a clean result whose provenance is unknown is not a
-- result.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS snapshot_scan (
    tenant_id            uuid        NOT NULL DEFAULT current_tenant_id(),
    snapshot_id          uuid        NOT NULL,
    scanned_at           timestamptz NOT NULL DEFAULT now(),
    scanner              text        NOT NULL,
    intelligence_version text,
    advisories_found     integer     NOT NULL DEFAULT 0,
    advisories_new       integer     NOT NULL DEFAULT 0,
    status               text        NOT NULL DEFAULT 'COMPLETED',
    detail               text,
    CONSTRAINT pk_snapshot_scan PRIMARY KEY (tenant_id, snapshot_id)
);

COMMENT ON COLUMN snapshot_scan.intelligence_version IS
    'Which vulnerability database produced the verdict. "Scanned and clean" against six-month-old '
    'intelligence is not the same claim as against today''s, and without this they are identical.';

CREATE INDEX IF NOT EXISTS ix_snapshot_scan__due
    ON snapshot_scan (tenant_id, scanned_at);
COMMENT ON INDEX ix_snapshot_scan__due IS
    'Serves: choosing which snapshots are due, which is the only query the scheduler makes.';

GRANT SELECT, INSERT, UPDATE ON snapshot_scan TO app_runtime;
GRANT SELECT ON snapshot_scan TO integrity_verifier;
SELECT apply_tenant_isolation('snapshot_scan');

-- -----------------------------------------------------------------------------
-- 3. Which snapshots are due, as a view — so the endpoint and the interface cannot disagree.
--
-- ONLY ARCHIVED SNAPSHOTS. A snapshot with no `storage_ref` has no document to scan; it predates the
-- archive and is not due, it is unscannable. Reported as its own state rather than quietly excluded,
-- because "56 snapshots cannot be re-scanned" is a fact an administrator needs and an empty queue
-- would hide.
--
-- LATEST PER ASSET ONLY. Re-scanning superseded snapshots would re-derive advisories for components
-- the estate no longer runs, and `asset_component` reads the latest anyway — the older ones are
-- history, not inventory.
-- -----------------------------------------------------------------------------
DROP VIEW IF EXISTS rescan_queue;

CREATE VIEW rescan_queue AS
SELECT s.id                                        AS snapshot_id,
       s.tenant_id,
       s.artifact_asset_id,
       a.display_name                              AS artifact_name,
       s.storage_ref,
       s.format,
       s.created_at                                AS submitted_at,
       sc.scanned_at                               AS last_scanned_at,
       sc.intelligence_version                     AS last_intelligence_version,
       (s.storage_ref IS NULL)                     AS unscannable
  FROM sbom_coverage_state cs
  JOIN sbom_snapshot s ON s.id = cs.latest_snapshot_id
  JOIN asset a ON a.id = s.artifact_asset_id
  LEFT JOIN snapshot_scan sc ON sc.snapshot_id = s.id AND sc.tenant_id = s.tenant_id
 WHERE a.lifecycle_state <> 'RETIRED';

COMMENT ON VIEW rescan_queue IS
    'The latest bill of materials per asset, with when it was last re-scanned. `unscannable` marks '
    'the ones archived before storage began — their bytes were never kept, so they can never be '
    're-scanned, and that is reported rather than hidden behind an empty queue.';

GRANT SELECT ON rescan_queue TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 4. The permission. Reuses the alert one rather than adding a third: an administrator who decides
--    where vulnerability news is sent is the same person who decides how often it is looked for.
-- -----------------------------------------------------------------------------
INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up)
VALUES ('sbm.scan.run', 'sbm', '{"en":"Fetch and submit scheduled scan results"}'::jsonb,
        false, false)
ON CONFLICT (code) DO NOTHING;

DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        INSERT INTO role_permission (tenant_id, role_id, permission_code)
        SELECT r.tenant_id, r.id, 'sbm.scan.run'
          FROM role r
         WHERE EXISTS (SELECT 1 FROM role_permission rp
                        WHERE rp.role_id = r.id AND rp.permission_code = 'sbm.sbom.submit')
        ON CONFLICT DO NOTHING;
    END LOOP;
END
$$;
