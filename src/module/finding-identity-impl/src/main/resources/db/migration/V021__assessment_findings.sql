-- =============================================================================
-- V021 — findings recorded during an assessment: where they came from, how they are written up,
-- and how a risk acceptance gets a deadline.
--
-- WHAT ALREADY EXISTED AND IS NOT REBUILT HERE. Nearly all of it:
--
--   finding                    title, class, severity, state, closure_reason, detection timestamps
--   finding_asset_impact       which asset it was found in
--   assessment_scope_asset     which assets a request covers
--   comment / comment_revision threaded, redactable, with an edit count and an author
--   evidence                   storage_ref, declared AND verified media type, malware verdict,
--                              isolated_origin, content hash, retention — designed for content that
--                              is expected to be hostile (DOC-26)
--   risk_exception             state machine, expiry, approver, step-up flag
--   service_level_policy       matching rules, target business days, specificity
--
-- Three things were genuinely missing, and this adds exactly those.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Which assessment activity found it.
--
-- source_tool already records WHAT produced a finding — semgrep, trivy, a person. It does not record
-- the ENGAGEMENT it was produced in, and the two are different questions with different answers:
-- "manual-pentest" is the tool for both an internal test and a red team exercise, and those carry
-- different disclosure rules, different reporting lines and different expectations about whether the
-- defenders knew it was happening.
--
-- A text column with a CHECK rather than a lookup table: this is the product's own vocabulary for
-- how ITS assessments are run, not tenant vocabulary like a workflow state. A tenant that runs a
-- fifth kind of engagement needs a product change, and that is the honest answer — the alternative
-- is a configurable list whose values no report can be written against.
-- -----------------------------------------------------------------------------
ALTER TABLE finding
    ADD COLUMN IF NOT EXISTS assessment_context text;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_finding__assessment_context') THEN
        ALTER TABLE finding ADD CONSTRAINT ck_finding__assessment_context CHECK
            (assessment_context IS NULL OR assessment_context IN
                ('INTERNAL_PENTEST', 'EXTERNAL_PENTEST', 'REDTEAM_INTERNAL', 'REDTEAM_EXTERNAL',
                 'AUTOMATED_SCAN', 'BUG_BOUNTY', 'INCIDENT'));
    END IF;
END
$$;

COMMENT ON COLUMN finding.assessment_context IS
    'The engagement a finding was produced in, distinct from source_tool which records what produced '
    'it. NULL for findings that arrived by ingestion and never named one — absent rather than '
    'defaulted, because defaulting would assert an engagement nobody ran.';

-- The finding a request produced. Nullable: a finding can exist without an assessment (ingestion),
-- and an assessment can exist without findings (a clean test is a result).
--
-- Not a foreign key across the module boundary — ADR-030 forbids one, and the assessment context
-- owns assessment_request. Reconciliation belongs to the domain layer, as it does everywhere else.
ALTER TABLE finding
    ADD COLUMN IF NOT EXISTS discovered_in_request_id uuid;

CREATE INDEX IF NOT EXISTS ix_finding__request
    ON finding (tenant_id, discovered_in_request_id)
    WHERE discovered_in_request_id IS NOT NULL;
COMMENT ON INDEX ix_finding__request IS
    'Serves: the finding list on an assessment request page, which is the working surface a '
    'pentester spends the engagement in.';

CREATE INDEX IF NOT EXISTS ix_finding__context
    ON finding (tenant_id, assessment_context, last_detected_at DESC)
    WHERE assessment_context IS NOT NULL;
COMMENT ON INDEX ix_finding__context IS
    'Serves: filtering findings by engagement type, and the assurance panel''s question of whether a '
    'red team exercise has ever run against an application.';

-- -----------------------------------------------------------------------------
-- 2. Write-ups: description and proof of concept.
--
-- finding.description already exists. A proof of concept is a SEPARATE field and not more description,
-- because it is the part that contains working exploit material — the thing DOC-26 calls out as one
-- of the five highest-risk surfaces, and the part that must be redactable and permission-gated
-- independently of the narrative around it.
--
-- FORMAT. Both are Markdown, and the RENDERER is the control rather than the storage. Storing HTML
-- and sanitising on the way out means every consumer needs the sanitiser; storing Markdown and
-- rendering with a strict subset means a consumer that forgets renders escaped text — which is safe
-- and merely ugly. A security product whose finding write-ups are an XSS vector is an embarrassment
-- with a specific name, and the content here is attacker-influenced by design: a pentester pastes
-- the payload that worked.
-- -----------------------------------------------------------------------------
ALTER TABLE finding
    ADD COLUMN IF NOT EXISTS proof_of_concept text;

ALTER TABLE finding
    ADD COLUMN IF NOT EXISTS body_format text NOT NULL DEFAULT 'MARKDOWN_RESTRICTED';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_finding__body_format') THEN
        -- One permitted value today. Enumerated anyway, so adding a second format is a decision with
        -- a migration behind it rather than a string somebody starts writing.
        ALTER TABLE finding ADD CONSTRAINT ck_finding__body_format CHECK
            (body_format IN ('MARKDOWN_RESTRICTED'));
    END IF;
END
$$;

COMMENT ON COLUMN finding.proof_of_concept IS
    'Reproduction steps and working exploit material, Markdown. Separate from description because it '
    'is the part that must be redactable and permission-gated on its own (DOC-26 evidence handling).';

-- -----------------------------------------------------------------------------
-- 3. Comments on anything, not only on a work item.
--
-- `comment` is keyed by work_item_id NOT NULL. Every request and every finding would therefore need
-- a work item created for it before anybody could say a word, and a work item that exists only to
-- carry a comment is a queue entry that appears in somebody's workload.
--
-- So: a nullable subject pair alongside the work item reference, and a CHECK that exactly one of
-- them is set. A comment belongs to one thing.
-- -----------------------------------------------------------------------------
ALTER TABLE comment ADD COLUMN IF NOT EXISTS subject_kind text;
ALTER TABLE comment ADD COLUMN IF NOT EXISTS subject_id uuid;
ALTER TABLE comment ALTER COLUMN work_item_id DROP NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_comment__one_subject') THEN
        ALTER TABLE comment ADD CONSTRAINT ck_comment__one_subject CHECK
            ((work_item_id IS NOT NULL)::int + (subject_id IS NOT NULL)::int = 1);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_comment__subject_kind') THEN
        ALTER TABLE comment ADD CONSTRAINT ck_comment__subject_kind CHECK
            (subject_id IS NULL OR subject_kind IN ('FINDING', 'ASSESSMENT_REQUEST', 'ASSET'));
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS ix_comment__subject
    ON comment (tenant_id, subject_kind, subject_id, created_at)
    WHERE subject_id IS NOT NULL;
COMMENT ON INDEX ix_comment__subject IS
    'Serves: the comment thread on a request or a finding, read in posting order on every page load.';

-- -----------------------------------------------------------------------------
-- 4. Risk acceptance with a deadline — using risk_exception, not a new column.
--
-- The obvious shortcut is `finding.accepted_until`. It is wrong, and the reason is worth stating
-- because the shortcut looks harmless: an acceptance with an end date but no approver is a way to
-- close a ticket. risk_exception already carries requested_by, approved_by, step_up_authenticated,
-- expires_at, max_duration_days and a state machine, and its CHECK refuses APPROVED or ACTIVE
-- without an approver who stepped up.
--
-- What was missing is the link back: finding.closure_reason = 'RISK_ACCEPTED' did not say WHICH
-- exception justified it, so an expiring acceptance could not reopen the finding it covered.
-- -----------------------------------------------------------------------------
ALTER TABLE finding
    ADD COLUMN IF NOT EXISTS accepted_under_exception_id uuid;

-- Existing acceptances are backfilled with a real exception BEFORE the constraint is added.
--
-- THREE THINGS THIS GOT WRONG FIRST, each found by replaying the migration rather than by reading it:
--
--   1. A plain INSERT here failed with "no tenant context established for this session". Migrations
--      run as aspm_migrate, which is GRANTED migration_runner — and BYPASSRLS is a role ATTRIBUTE
--      that membership does not inherit. So the row-level policy applies, and its predicate calls
--      current_tenant_id(), which refuses rather than defaulting (SEC-TEN-005 failing closed, exactly
--      as designed). A data migration that touches tenant-scoped rows must establish the context per
--      tenant, which is what the loop below does.
--
--   2. Backfilling as state = 'ACTIVE' with an approver failed INV-VUL-26: the engine refuses an
--      exception approved by its own requester. The fix is not a different approver — it is that a
--      historical acceptance nobody approved must not be RECORDED as approved. These are created
--      REQUESTED, which needs no approver and is the truthful state.
--
--   3. A row whose acceptance cannot be attributed to anybody at all cannot be repaired here, so the
--      constraint is added only when none remain and the migration fails loudly naming the count
--      otherwise. Skipping the constraint silently would leave the control absent in exactly the
--      deployment that needed it.
DO $backfill$
DECLARE
    t          uuid;
    remaining  int;
    unrepaired int := 0;
BEGIN
    -- Enumerated from `tenant`, which is one of the three tables deliberately OUTSIDE row-level
    -- security. Reading `finding` to find the tenants would itself need a tenant context, which is
    -- the circularity that made the first two attempts at this fail.
    FOR t IN SELECT id FROM tenant
    LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);

        WITH created AS (
            INSERT INTO risk_exception (tenant_id, subject_kind, subject_id, subject_finding_class,
                                        state, requested_by, requested_at, expires_at,
                                        max_duration_days)
            SELECT f.tenant_id, 'FINDING', f.id, f.finding_class, 'REQUESTED',
                   coalesce(f.created_by, f.closure_verified_by, f.updated_by),
                   coalesce(f.closed_at, now()), now() + interval '90 days', 90
              FROM finding f
             WHERE f.closure_reason = 'RISK_ACCEPTED'
               AND f.accepted_under_exception_id IS NULL
               AND coalesce(f.created_by, f.closure_verified_by, f.updated_by) IS NOT NULL
            RETURNING id, subject_id
        )
        UPDATE finding f SET accepted_under_exception_id = c.id
          FROM created c WHERE f.id = c.subject_id;

        -- Counted INSIDE the loop, while the context is established. The same reason as above: a
        -- count over `finding` with no tenant set is refused, not empty.
        SELECT count(*) INTO remaining FROM finding
         WHERE closure_reason = 'RISK_ACCEPTED' AND accepted_under_exception_id IS NULL;
        unrepaired := unrepaired + remaining;
    END LOOP;

    IF unrepaired > 0 THEN
        RAISE EXCEPTION '% finding(s) are closed as RISK_ACCEPTED with nobody recorded as having '
                        'requested the acceptance, so no exception can be created for them. Decide '
                        'per finding whether to reopen it or to attribute the acceptance, then '
                        're-run. The constraint is NOT added while any remain: an acceptance nothing '
                        'can expire is a way to close a ticket.', unrepaired;
    END IF;
END
$backfill$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_finding__acceptance_linked') THEN
        -- A finding closed as RISK_ACCEPTED must name the exception that justified it. Without this
        -- the acceptance is an assertion in a text column, and nothing can expire it.
        ALTER TABLE finding ADD CONSTRAINT ck_finding__acceptance_linked CHECK
            (closure_reason IS DISTINCT FROM 'RISK_ACCEPTED'
             OR accepted_under_exception_id IS NOT NULL);
    END IF;
END
$$;

COMMENT ON COLUMN finding.accepted_under_exception_id IS
    'The risk_exception that justifies a RISK_ACCEPTED closure. Required by '
    'ck_finding__acceptance_linked: an acceptance nothing can expire is a way to close a ticket.';

CREATE INDEX IF NOT EXISTS ix_risk_exception__expiring
    ON risk_exception (tenant_id, expires_at)
    WHERE state IN ('APPROVED', 'ACTIVE');
COMMENT ON INDEX ix_risk_exception__expiring IS
    'Serves: the expiring-acceptance panel, and the sweep that reopens a finding whose exception has '
    'lapsed — the reason acceptances carry an expiry at all.';

-- -----------------------------------------------------------------------------
-- 5. Grants.
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT has_table_privilege('app_runtime', 'comment', 'INSERT') THEN
        RAISE EXCEPTION 'app_runtime cannot INSERT comment, so nobody can comment on anything.';
    END IF;
    IF NOT has_table_privilege('app_runtime', 'risk_exception', 'INSERT') THEN
        RAISE EXCEPTION 'app_runtime cannot INSERT risk_exception, so risk cannot be accepted.';
    END IF;
    IF NOT has_table_privilege('app_runtime', 'finding', 'INSERT') THEN
        RAISE EXCEPTION 'app_runtime cannot INSERT finding, so a pentester cannot record one.';
    END IF;
END
$$;
