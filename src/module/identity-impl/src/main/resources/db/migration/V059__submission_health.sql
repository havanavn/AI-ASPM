-- =============================================================================
-- V059 — submission health per integration credential (PRD-SBM-024).
--
-- WHY THIS IS A REQUIREMENT AND NOT A NICE-TO-HAVE. ADR-023 makes the SBOM push API the only automated
-- ingestion path in v1, and records the consequence in its own text: the endpoint is "a single point of
-- failure for the entire SCA capability, which is why submission health must be visible per credential
-- (PRD-SBM-024)". That requirement is MUST_HAVE and reads:
--
--     The platform MUST expose submission health per integration credential, including last successful
--     submission, failure count, and last failure reason.
--
-- WHAT THE TABLE COULD ANSWER BEFORE THIS. One column: `last_used_at`, written whenever a signature
-- verified. It cannot distinguish a pipeline that submitted a bill of materials from one whose document
-- was rejected forty times, and it says nothing at all about a pipeline whose secret went stale — those
-- never reach the point where it is written. So the three commonest ways an integration dies were all
-- indistinguishable from "nobody has pushed lately", which is the confusion product principle 1 exists
-- to forbid: measured-and-clean must never look like not-measured.
--
-- The evidence is this deployment. Two credentials named for real pipelines — "Central build platform"
-- and "GSM build pipeline" — have `last_used_at` NULL and are revoked. Nobody could learn that from any
-- screen; it took a query against the database.
--
-- WHY THERE IS NO BACKFILL, AND WHY THAT IS THE HONEST ANSWER.
--
-- `sbom_snapshot.submitted_by_principal_id` records who submitted each of the 64 existing snapshots, so
-- a backfill looks available. It is not: ALL TEN service credentials in this deployment act as the SAME
-- principal. Attributing per principal would credit every one of the ten with the same 61 snapshots and
-- report nine integrations as healthy on the strength of a tenth one's work — the precise failure this
-- dashboard exists to prevent, installed by the migration that builds it.
--
-- So the columns start empty and the interface says "no outcome recorded yet" rather than "0 failures".
-- Zero failures and no data are different facts and this schema will not conflate them.
--
-- (That ten credentials share one principal is a separate problem: it also means the audit trail cannot
-- say which pipeline did what, and that revoking one isolates nothing. Not fixed here — noted, because
-- from now on this table is the only place where per-credential attribution will exist.)
-- =============================================================================

ALTER TABLE service_credential
    ADD COLUMN IF NOT EXISTS last_success_at        timestamptz,
    ADD COLUMN IF NOT EXISTS last_failure_at        timestamptz,
    ADD COLUMN IF NOT EXISTS last_failure_reason    text,
    ADD COLUMN IF NOT EXISTS success_count          integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS failure_count          integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS consecutive_failures   integer NOT NULL DEFAULT 0;

-- Two counters, because one cannot answer both questions. `failure_count` is the lifetime total and
-- says whether an integration has ever been reliable; `consecutive_failures` resets on every success
-- and says whether it is broken RIGHT NOW. A single number reporting 200 failures cannot distinguish a
-- pipeline that has been down for a fortnight from one that had a rough month last year and has been
-- fine since — and only the first is somebody's job this morning.
COMMENT ON COLUMN service_credential.failure_count IS
    'Lifetime rejected submissions. Never reset — it is the reliability record.';
COMMENT ON COLUMN service_credential.consecutive_failures IS
    'Rejected submissions since the last success, reset to 0 by each success. This is the number that '
    'says whether an integration is broken now, which failure_count cannot.';
COMMENT ON COLUMN service_credential.last_failure_reason IS
    'Why the most recent submission was refused. Platform-authored text only — never a string echoed '
    'from the request. A CI pipeline is an unauthenticated caller until its signature verifies, and a '
    'field rendered on an administrator screen is the wrong place to put anything it supplied.';
COMMENT ON COLUMN service_credential.last_success_at IS
    'The last accepted submission. NULL means no outcome has been recorded for this credential since '
    'V059 — deliberately not backfilled, because every credential in this deployment shares one '
    'principal and per-principal attribution would have credited all of them with one pipeline''s work.';

-- Serves: the submission-health list, worst first — the only query this data has, and the one an
-- administrator opens to find the integration that has stopped working.
CREATE INDEX IF NOT EXISTS ix_service_credential__health
    ON service_credential (tenant_id, consecutive_failures DESC, last_success_at NULLS FIRST)
    WHERE revoked_at IS NULL;
COMMENT ON INDEX ix_service_credential__health IS
    'Serves: live credentials ordered by how broken they are. Partial on revoked_at IS NULL because a '
    'revoked credential has no health to report — it is not failing, it is finished.';

-- The counters must never be able to disagree with the timestamps. A row claiming successes with no
-- last_success_at, or a reset counter with failures pending, is a row that lies quietly on a dashboard
-- whose whole purpose is to be believed.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'ck_service_credential__health_agrees'
                      AND conrelid = 'service_credential'::regclass) THEN
        BEGIN
            ALTER TABLE service_credential ADD CONSTRAINT ck_service_credential__health_agrees CHECK (
                success_count >= 0 AND failure_count >= 0 AND consecutive_failures >= 0
                AND consecutive_failures <= failure_count
                AND (success_count > 0) = (last_success_at IS NOT NULL)
                AND (failure_count > 0) = (last_failure_at IS NOT NULL));
        EXCEPTION WHEN check_violation THEN
            RAISE EXCEPTION 'a service credential already carries inconsistent health counters';
        END;
    END IF;
END
$$;
