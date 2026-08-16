-- =============================================================================
-- V062 — the import session and its quarantine queue, so scan reports can be ingested.
--
-- WHAT WAS MISSING. `finding.source_import_session_id` has existed since V006 and pointed at nothing:
-- there was no session table, so the column recorded provenance nobody could resolve, and the CI/CD
-- dashboard's only honest predicate (`source_import_session_id IS NOT NULL`) filtered a population that
-- could not be created through any API. The parser FRAMEWORK was built — ParserDefinition, the
-- fingerprint classes, AssetAnchorResolution, QuarantinedRecord — and had no concrete parser and nowhere
-- to record a run. This migration is the storage half of closing that.
--
-- WHY A SESSION IS A ROW AND NOT A LOG LINE. Three requirements need it to be queryable rather than
-- narrated:
--
--   * PRD-ING-041 requires counts BY DISPOSITION, never only a total: "a session reporting 40,000
--     records processed says nothing about whether anything was ingested". So there are five counters
--     and deliberately no `total` column — a reader must add them up and therefore must see them.
--   * PRD-ING-020 requires a session to be diagnosable to a STAGE. `state` carries the stage a failed
--     session stopped at, and `failure_reason` says why, because "the import failed" is not actionable.
--   * PRD-ING-039 requires a quarantined record to be retrievable WITH its raw content and correctable
--     without re-importing the source. That is the second table, and the raw content is why it is not
--     merely a counter.
--
-- WHY THERE IS NO PARTIAL-INGEST STATE. ImportSession has no `parsedPartially` and neither does this
-- table: a document either parses whole or is refused whole. DOC-11 section 9 gives the reason — "a
-- truncated record set could be read as a complete one" — and reading one as complete is what closes
-- findings that were never re-reported. Per-RECORD quarantine is the partial mechanism, inside a
-- document that parsed.
--
-- WHY THE SESSION IS NOT PARTITIONED WHILE `finding` IS. Hash partition counts are irreversible once
-- production data exists, and OQ-015 (portfolio sizing) is still unanswered — DOC-01 marks it as
-- blocking implementation for exactly this decision. `finding` was partitioned when the profile it was
-- sized for was written down; a session table is one row per submission rather than one per weakness,
-- which is three to four orders of magnitude smaller, so it does not force the same bet. Recorded here
-- so that a later decision to partition it is a decision and not a discovery.
-- =============================================================================

CREATE TABLE IF NOT EXISTS import_session (
    id                       uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id                uuid        NOT NULL,

    -- The submitter's key, so a retried push is recognised as the same submission rather than ingested
    -- twice. A CI job that times out waiting for a response and retries is the normal case, not the
    -- exception, and without this the second attempt would re-detect every finding and inflate
    -- recurrence counts — a number that then reads as "this keeps coming back" when it came back once.
    idempotency_key          text        NOT NULL,

    source_format            text        NOT NULL,
    source_format_version    text        NOT NULL,

    -- Which parser, at which version, produced everything in this session. PRD-ING-025: without it a
    -- systematic mapping error introduced by a parser change cannot be scoped — there is no way to ask
    -- which findings that version produced, so there is no way to correct them as a set.
    parser_code              text        NOT NULL,
    parser_version           int         NOT NULL,

    -- What the submission was filed against. Named by the caller and resolved by the platform; the
    -- DOCUMENT does not get to assert it (PRD-ING-031), because a scan report is attacker-influenced
    -- input and a self-asserted target is a finding filed against somebody else's asset.
    target_asset_id          uuid,

    -- The document as submitted, hashed, so an identical resubmission is recognisable byte for byte and
    -- so the session can be tied to an archived document later. The bytes themselves are not here:
    -- ADR-056 puts a multi-megabyte report in object storage, and a scanner report inside the
    -- platform's hottest schema is a read amplification nobody budgeted.
    document_sha256          bytea       NOT NULL,
    document_bytes           int         NOT NULL,
    -- Where the document itself was archived, so `finding.raw_source_record_ref` can be a pointer INTO
    -- it (`<ref>#/runs/0/results/12`) rather than a label. PRD-ING-022 retains the raw source record;
    -- a reference that resolves to nothing retains a promise. NULL is a real answer and it is why the
    -- reference falls back to naming the session: it records exactly which sessions cannot produce
    -- their raw records, rather than leaving that indistinguishable from ones that can.
    document_ref             text,

    state                    text        NOT NULL,
    failure_reason           text,

    records_extracted        int         NOT NULL DEFAULT 0,
    -- Five dispositions, five counters, no total. See the header.
    ingested_count           int         NOT NULL DEFAULT 0,
    updated_count            int         NOT NULL DEFAULT 0,
    merged_count             int         NOT NULL DEFAULT 0,
    quarantined_count        int         NOT NULL DEFAULT 0,
    -- A mapping gap is NOT a quarantine and is counted separately. PRD-ING-040 requires the finding to
    -- be ingested with no severity and the gap reported; folding the two together would either hide an
    -- ingested finding in a quarantine count or hide the gap entirely.
    mapping_gap_count        int         NOT NULL DEFAULT 0,

    submitted_by             uuid,
    -- The credential, where a service credential made the call. Separate from the principal because ten
    -- credentials in this deployment share one principal (see V059): attributing a session to the
    -- principal alone cannot say which pipeline pushed it, and that is the question asked when an
    -- unexpected finding appears.
    submitted_by_credential  uuid,

    initiated_at             timestamptz NOT NULL DEFAULT now(),
    completed_at             timestamptz,
    reversible_until         timestamptz,

    created_at               timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_import_session PRIMARY KEY (id, tenant_id),
    CONSTRAINT ck_import_session__state CHECK (state IN
        ('QUEUED', 'PARSING', 'NORMALIZING', 'FAILED', 'COMPLETED',
         'COMPLETED_WITH_QUARANTINE', 'REVERSED')),
    CONSTRAINT ck_import_session__counts CHECK (
        records_extracted >= 0 AND ingested_count >= 0 AND updated_count >= 0
        AND merged_count >= 0 AND quarantined_count >= 0 AND mapping_gap_count >= 0),
    CONSTRAINT ck_import_session__parser_version CHECK (parser_version >= 1),
    -- A terminal session has finished; a live one has not. A row claiming COMPLETED with no
    -- completed_at is a session that cannot be aged out or reported on, and it looks identical to one
    -- still running.
    CONSTRAINT ck_import_session__terminal_is_dated CHECK (
        (state IN ('FAILED', 'COMPLETED', 'COMPLETED_WITH_QUARANTINE', 'REVERSED'))
        = (completed_at IS NOT NULL)),
    -- FAILED means nothing was ingested (DOC-11 section 9). If a failed session carried ingested rows,
    -- the reversal path would have nothing to reverse them from.
    CONSTRAINT ck_import_session__failed_ingested_nothing CHECK (
        state <> 'FAILED' OR (ingested_count = 0 AND updated_count = 0)),
    -- The state must agree with the queue. COMPLETED_WITH_QUARANTINE with an empty queue sends somebody
    -- to look for records that are not there; COMPLETED with a populated one hides them.
    CONSTRAINT ck_import_session__quarantine_agrees CHECK (
        state <> 'COMPLETED' OR quarantined_count = 0),
    CONSTRAINT ck_import_session__quarantine_present CHECK (
        state <> 'COMPLETED_WITH_QUARANTINE' OR quarantined_count > 0),
    CONSTRAINT ck_import_session__failure_reason CHECK (
        (state = 'FAILED') = (failure_reason IS NOT NULL))
);

SELECT apply_tenant_isolation('import_session');

-- Recognises a retry. Unique per tenant and per submitter key: two tenants may legitimately use the
-- same key, and nothing about one tenant's submission may be visible in the other's collision.
CREATE UNIQUE INDEX IF NOT EXISTS ux_import_session__idempotency
    ON import_session (tenant_id, idempotency_key);
COMMENT ON INDEX ux_import_session__idempotency IS
    'Serves: the retry check on every submission — does this idempotency key already have a session. '
    'Unique so the answer cannot be two rows.';

-- Serves: the CI/CD dashboard''s "recent imports", and the per-asset question "when was this
-- repository last scanned, and did it work".
CREATE INDEX IF NOT EXISTS ix_import_session__recent
    ON import_session (tenant_id, initiated_at DESC);
COMMENT ON INDEX ix_import_session__recent IS
    'Serves: recent import sessions, newest first, for the ingestion health panel.';

CREATE INDEX IF NOT EXISTS ix_import_session__target
    ON import_session (tenant_id, target_asset_id, initiated_at DESC)
    WHERE target_asset_id IS NOT NULL;
COMMENT ON INDEX ix_import_session__target IS
    'Serves: the last import for one repository, which is what distinguishes "scanned and clean" from '
    '"never scanned" (product principle 1). Partial because a session with no resolved target answers '
    'no per-asset question.';

COMMENT ON TABLE import_session IS
    'One scan-report submission. Counts are per disposition and there is deliberately no total column '
    '(PRD-ING-041). See V062 for why there is no partial-ingest state.';

-- =============================================================================
-- The quarantine queue.
--
-- "A single malformed record in a 40,000-record file must not discard the file, AND must not be
-- silently dropped" (DOC-11 section 9). Both halves matter: discarding the file loses 39,999 good
-- records, and dropping the one record is an invisible coverage gap — the estate looks clean because
-- the finding never arrived, which is product principle 1's exact failure.
--
-- The raw content is held so the record is correctable WITHOUT the original file (PRD-ING-039). A
-- quarantine row with no content is a queue entry nobody can act on, and a queue that can only grow is
-- deletion with extra steps.
-- =============================================================================

CREATE TABLE IF NOT EXISTS import_quarantine (
    id                       uuid        NOT NULL DEFAULT uuidv7(),
    tenant_id                uuid        NOT NULL,
    import_session_id        uuid        NOT NULL,

    -- The record's position in the document. "One record failed" is not correctable; "result 1,284
    -- failed" is, and DOC-11 section 9 requires the failing record to be identifiable.
    record_index             int         NOT NULL,

    reason                   text        NOT NULL,
    -- Named fields, not a prose message. A schema validation failure that does not say WHICH field is
    -- not correctable, so it is not resolvable, so it is deletion (see QuarantinedRecord's constructor).
    failing_fields           text[]      NOT NULL DEFAULT '{}',

    -- EXPECTED TO BE MALICIOUS. This column holds attacker-authored text by design: a finding's content
    -- legitimately includes strings an attacker wrote, and this is the raw record that failed to parse.
    -- It is stored, retrievable and NEVER interpolated into anything — the interface renders it as text
    -- through the same restricted renderer the rest of the platform uses (SEC-SEC-032).
    raw_content              text        NOT NULL,

    state                    text        NOT NULL DEFAULT 'QUARANTINED',
    resolution_note          text,
    resolved_at              timestamptz,
    resolved_by              uuid,

    quarantined_at           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_import_quarantine PRIMARY KEY (id, tenant_id),
    CONSTRAINT ck_import_quarantine__reason CHECK (reason IN
        ('SCHEMA_VALIDATION', 'EVIDENCE_LIMIT_EXCEEDED', 'SEVERITY_MAPPING_GAP')),
    CONSTRAINT ck_import_quarantine__state CHECK (state IN
        ('QUARANTINED', 'RESOLVED', 'DISCARDED')),
    CONSTRAINT ck_import_quarantine__index CHECK (record_index >= 1),
    -- A schema failure names its field. Enforced in the database as well as in the domain, because the
    -- migration import path writes here too and a second writer is how an invariant stops holding.
    CONSTRAINT ck_import_quarantine__schema_names_field CHECK (
        reason <> 'SCHEMA_VALIDATION' OR cardinality(failing_fields) > 0),
    -- Settled means somebody decided. A resolved or discarded row with no note is a record that quietly
    -- vanished, which is an unknown coverage gap wearing the clothes of a completed queue.
    CONSTRAINT ck_import_quarantine__settled_is_explained CHECK (
        (state = 'QUARANTINED') = (resolution_note IS NULL)),
    CONSTRAINT ck_import_quarantine__settled_is_dated CHECK (
        (state = 'QUARANTINED') = (resolved_at IS NULL))
);

SELECT apply_tenant_isolation('import_quarantine');

-- Serves: the open quarantine queue, oldest first — the working order for whoever clears it.
CREATE INDEX IF NOT EXISTS ix_import_quarantine__open
    ON import_quarantine (tenant_id, quarantined_at)
    WHERE state = 'QUARANTINED';
COMMENT ON INDEX ix_import_quarantine__open IS
    'Serves: records still held, oldest first. Partial on state because a resolved record is history '
    'and does not belong in the queue that is worked.';

-- Serves: everything one session held back, which is what the submission response links to.
CREATE INDEX IF NOT EXISTS ix_import_quarantine__session
    ON import_quarantine (tenant_id, import_session_id, record_index);
COMMENT ON INDEX ix_import_quarantine__session IS
    'Serves: the quarantine list for one import session, in document order.';

COMMENT ON TABLE import_quarantine IS
    'Records held back from one import, with the raw content needed to correct them without the source '
    'file (PRD-ING-039). raw_content is attacker-influenced by design and is never interpolated.';

-- No foreign key to import_session, and none to finding. ADR-030: no foreign keys across module
-- boundaries, with integrity in the domain layer and reconciliation. This one is within the ingestion
-- module and could carry one — it deliberately does not, because import_session is not partitioned and
-- finding is, and a mixed-partitioning reference is a constraint that has to be dropped the first time
-- either table's partitioning changes. The session id is written by one code path in one transaction.

GRANT SELECT, INSERT, UPDATE ON import_session TO app_runtime;
GRANT SELECT, INSERT, UPDATE ON import_quarantine TO app_runtime;

-- -----------------------------------------------------------------------------------------------
-- The permission. Product-fixed, per ADR-027: the CATALOGUE is code, and which roles hold the code is
-- tenant data — so this migration adds the entry and grants it to nobody. A migration that granted it
-- would decide for every deployment which role may push scan results, which is exactly the
-- organization-specific assumption ADR-027 forbids.
--
-- WHY IT IS NOT `sbm.sbom.submit`. A bill of materials is a list of components a build declared. A scan
-- report is a list of weaknesses in code, with file paths, snippets and rule identities — it is the
-- higher-value document and its submitter needs the higher bar. Reusing the SBOM permission would mean
-- that granting a pipeline the right to declare its dependencies also granted it the right to file
-- findings against the repository, and those are different decisions with different consequences.
--
-- `requires_step_up` is false, and it has to be: this is class F, a signed service credential, and a
-- service credential is never step-up authenticated (ServiceCredentialResolver). Declaring step-up here
-- would make the endpoint uncallable by the only caller it exists for.
-- -----------------------------------------------------------------------------------------------
INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up) VALUES
  ('ing.findings.import', 'ING',
   '{"en":"Submit scan reports for ingestion",
     "vi":"Gửi báo cáo quét để đưa vào hệ thống"}'::jsonb, false, false)
ON CONFLICT (code) DO NOTHING;
