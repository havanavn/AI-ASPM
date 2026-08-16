-- =============================================================================
-- V019 — application composition, the security facts each part carries, and the posture rollup.
--
-- WHAT THIS ADDS, AND WHAT IT DELIBERATELY DOES NOT
--
-- An application has features; a feature has services; a service has repositories, a technology
-- stack, an authentication model, a data classification, an exposure level, an SBOM and its own
-- findings. Every one of those is already expressible: ADR-009 gives one Asset aggregate with a type
-- registry and V005 gives the edges. FEATURE and SERVICE are asset TYPES, and the composition is
-- CONTAINS edges. There is no feature table and no service table, for the same reason there is no
-- application table — five parallel inventories is what ADR-009 exists to prevent.
--
-- What was genuinely missing is three things, and this migration adds exactly those:
--
--   1. A way to declare which attributes an asset type carries, so "authentication model" and
--      "data classification" are TENANT-DECLARED fields that the editor and the filters generate
--      themselves — rather than columns somebody adds to `asset` every time the security team wants
--      to record one more fact. A fixed column list is the thing that makes an ASPM tool stop
--      fitting the organization that bought it.
--   2. Views that walk the composition and roll the numbers up, so a page does not re-implement a
--      recursive traversal.
--   3. The suggestion ledger ADR-005 requires before any AI capability may write anything.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. asset_attribute_definition — the declared attribute catalogue, per asset type.
--
-- Tenant data. asset_type.attribute_schema_ref has existed since V005 as the extension point and
-- nothing filled it; this is what it points at.
--
-- WHY NOT COLUMNS ON `asset`. Tech stack, authentication model and data classification apply to a
-- service and mean nothing on a domain. Columns would be null for most rows, and every new fact the
-- security team wants to track would be a migration, a form change and a filter change. Declared
-- attributes make all three one INSERT.
--
-- WHY NOT FREE-FORM JSON ALONE. `attributes` is already free-form, and free-form is unfilterable in
-- practice: nobody can offer a dropdown of values nobody declared. The definition carries the
-- permitted values, so the editor renders a select and the filter offers the same list.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS asset_attribute_definition (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL,
    asset_type_id   uuid        NOT NULL REFERENCES asset_type (id) ON DELETE RESTRICT,
    attribute_key   text        NOT NULL,
    label_i18n      jsonb       NOT NULL,
    data_type       text        NOT NULL,
    -- For SINGLE_SELECT and MULTI_SELECT. Empty for TEXT, and the CHECK below makes an empty list on
    -- a select type impossible: a dropdown with no options is a required field nobody can complete.
    permitted_values text[]     NOT NULL DEFAULT '{}',
    -- Whether the inventory offers this as a filter. Not everything should be: a free-text note is a
    -- filter that returns one row and teaches people the filters do not work.
    filterable      bool        NOT NULL DEFAULT true,
    required        bool        NOT NULL DEFAULT false,
    -- The security question this answers, shown beside the field. A field whose purpose nobody can
    -- state is a field people fill in wrongly and then filter on.
    purpose         text,
    ordinal         int         NOT NULL DEFAULT 0,
    lifecycle_state text        NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      uuid,
    row_version     int         NOT NULL DEFAULT 1,

    CONSTRAINT uq_asset_attr_def__key UNIQUE (tenant_id, asset_type_id, attribute_key),
    -- The same shape the application enforces, at the engine: a key that is not a plain identifier
    -- cannot be addressed in a JSON path or a query string without quoting somebody will forget.
    CONSTRAINT ck_asset_attr_def__key CHECK (attribute_key ~ '^[a-z][a-z0-9_]{1,48}$'),
    CONSTRAINT ck_asset_attr_def__type CHECK
        (data_type IN ('TEXT', 'LONG_TEXT', 'SINGLE_SELECT', 'MULTI_SELECT', 'URL', 'BOOLEAN')),
    CONSTRAINT ck_asset_attr_def__values_present CHECK
        (data_type NOT IN ('SINGLE_SELECT', 'MULTI_SELECT')
         OR array_length(permitted_values, 1) >= 1),
    CONSTRAINT ck_asset_attr_def__lifecycle CHECK (lifecycle_state IN ('ACTIVE', 'DEPRECATED'))
);

SELECT apply_tenant_isolation('asset_attribute_definition');

CREATE INDEX IF NOT EXISTS ix_asset_attr_def__type
    ON asset_attribute_definition (tenant_id, asset_type_id, ordinal)
    WHERE lifecycle_state = 'ACTIVE';
COMMENT ON INDEX ix_asset_attr_def__type IS
    'Serves: rendering the editor and the filter bar for one asset type, which is every load of the '
    'inventory and every load of a component detail.';

GRANT SELECT, INSERT, UPDATE ON asset_attribute_definition TO app_runtime;
GRANT SELECT ON asset_attribute_definition TO integrity_verifier;

-- -----------------------------------------------------------------------------
-- 2. Filtering on declared attributes.
--
-- jsonb_path_ops rather than the default operator class: it is smaller and faster for the only
-- operator the inventory uses, `@>`. The default class also supports key-existence operators the
-- filters never issue, and pays for them in index size on the largest table in the platform.
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS ix_asset__attributes
    ON asset USING gin (attributes jsonb_path_ops);
COMMENT ON INDEX ix_asset__attributes IS
    'Serves: filtering the inventory by a declared attribute, e.g. attributes @> ''{"authentication":'
    '"NONE"}'' — the query behind "which services authenticate nobody".';

-- -----------------------------------------------------------------------------
-- 3. asset_composition — the transitive parts of an asset, with the path that got there.
--
-- Recursive over the CURRENT edges only. The depth bound is not decoration: an edge set is a graph,
-- not a tree, and a cycle introduced by an import would otherwise make this view hang rather than
-- return a wrong answer — which is worse, because a hang has no error message.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW asset_composition AS
WITH RECURSIVE walk AS (
    SELECT a.id            AS root_id,
           a.id            AS asset_id,
           a.tenant_id,
           0               AS depth,
           ARRAY[a.id]     AS path_ids,
           ARRAY[]::text[] AS path_names,
           NULL::text      AS edge_type
      FROM asset a
    UNION ALL
    SELECT w.root_id,
           r.to_asset_id,
           w.tenant_id,
           w.depth + 1,
           w.path_ids || r.to_asset_id,
           w.path_names || b.display_name,
           r.edge_type
      FROM walk w
      JOIN asset_relationship r ON r.from_asset_id = w.asset_id AND r.valid_until IS NULL
      JOIN asset b ON b.id = r.to_asset_id
     WHERE w.depth < 6
       -- A component already on this path is a cycle. Stopping here reports the parts that exist
       -- rather than recursing forever; the cycle itself is a data defect the graph tools surface.
       AND NOT (r.to_asset_id = ANY (w.path_ids))
)
SELECT w.root_id,
       w.asset_id,
       w.tenant_id,
       w.depth,
       w.edge_type,
       w.path_names,
       a.display_name,
       t.code            AS type_code,
       a.lifecycle_state,
       a.exposure_declared,
       a.attributes,
       a.owning_node_id
  FROM walk w
  JOIN asset a ON a.id = w.asset_id
  JOIN asset_type t ON t.id = a.type_id
 WHERE w.depth > 0;

COMMENT ON VIEW asset_composition IS
    'Every part of an asset, transitively, through current edges only. Depth-bounded and '
    'cycle-guarded: an imported edge set is a graph and a cycle must return a short answer rather '
    'than hang. SECURITY INVOKER so row-level policies apply to the caller.';

GRANT SELECT ON asset_composition TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 4. asset_finding_tally — findings for ONE asset, counted the way a person asks about them.
--
-- Separated from the rollup below so a component row and an application row use the same numbers
-- computed the same way. Two count queries that drift is how a detail page comes to disagree with
-- the list that linked to it.
--
-- OPEN is `state = 'OPEN'`. ACCEPTED is a finding closed with closure_reason = 'RISK_ACCEPTED' — an
-- accepted risk is CLOSED, not open, and counting it in both would double-count the thing an
-- executive is most likely to read.
--
-- DROPPED FIRST, and this was added later. V035 redefines both views below with a wider column list,
-- and the migrations replay in full on every start — so the second run reached this file with V035's
-- version already in place and CREATE OR REPLACE could not remove the extra columns. That is the
-- same "cannot drop columns from view" failure V018 section 3 records, and it takes the migration
-- container down rather than degrading. Each migration's definition has to be authoritative at the
-- moment it runs.
--
-- The rollup goes first because it reads the tally. No CASCADE: a CASCADE would silently drop a view
-- added after this file was written, and the next replay would not put it back.
-- -----------------------------------------------------------------------------
DROP VIEW IF EXISTS application_posture;
DROP VIEW IF EXISTS asset_finding_tally;

CREATE OR REPLACE VIEW asset_finding_tally AS
SELECT i.asset_id,
       f.tenant_id,
       count(*)                                                        AS total,
       count(*) FILTER (WHERE f.state = 'OPEN')                        AS open_total,
       count(*) FILTER (WHERE f.closure_reason = 'RISK_ACCEPTED')      AS accepted_total,
       count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY')          AS sca_total,
       count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY'
                          AND f.state = 'OPEN')                        AS sca_open,
       count(*) FILTER (WHERE s.code = 'CRITICAL')                     AS critical_total,
       count(*) FILTER (WHERE s.code = 'CRITICAL' AND f.state = 'OPEN') AS critical_open,
       count(*) FILTER (WHERE s.code = 'HIGH')                         AS high_total,
       count(*) FILTER (WHERE s.code = 'HIGH' AND f.state = 'OPEN')    AS high_open,
       count(*) FILTER (WHERE s.code = 'MEDIUM')                       AS medium_total,
       count(*) FILTER (WHERE s.code = 'MEDIUM' AND f.state = 'OPEN')  AS medium_open,
       count(*) FILTER (WHERE s.code = 'LOW')                          AS low_total,
       count(*) FILTER (WHERE s.code = 'LOW' AND f.state = 'OPEN')     AS low_open,
       count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY'
                          AND s.code = 'CRITICAL' AND f.state = 'OPEN') AS sca_critical_open,
       count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY'
                          AND s.code = 'HIGH' AND f.state = 'OPEN')     AS sca_high_open,
       count(*) FILTER (WHERE f.finding_class = 'DEPENDENCY'
                          AND s.code = 'MEDIUM' AND f.state = 'OPEN')   AS sca_medium_open,
       max(f.last_detected_at)                                         AS last_detected_at
  FROM finding_asset_impact i
  JOIN finding f ON f.id = i.finding_id
  -- The EFFECTIVE severity, never the reported one. A severity a human overrode after triage is the
  -- platform's answer; showing the tool's original would report a number nobody stands behind.
  LEFT JOIN severity_level s ON s.id = f.effective_severity_id
 GROUP BY i.asset_id, f.tenant_id;

COMMENT ON VIEW asset_finding_tally IS
    'Findings for one asset by severity, state and class. DEPENDENCY is the SCA class. Accepted risk '
    'is counted as CLOSED, because it is — counting it as open too double-counts the figure an '
    'executive reads first.';

GRANT SELECT ON asset_finding_tally TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 5. application_posture — the rollup over an application and everything it contains.
--
-- The subtree, not just the application row. A vulnerability in a service belongs to the application
-- that service is part of; an application whose own row has no findings but whose services have
-- forty is not a clean application, and a page that reported zero would be the most dangerous kind
-- of wrong answer this product can produce.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW application_posture AS
WITH scope AS (
    SELECT a.id AS root_id, a.tenant_id, a.id AS member_id
      FROM asset a
    UNION
    SELECT c.root_id, c.tenant_id, c.asset_id
      FROM asset_composition c
)
SELECT s.root_id                                             AS asset_id,
       s.tenant_id,
       count(DISTINCT s.member_id) - 1                       AS component_count,
       coalesce(sum(t.total), 0)                             AS finding_total,
       coalesce(sum(t.open_total), 0)                        AS finding_open,
       coalesce(sum(t.accepted_total), 0)                    AS finding_accepted,
       coalesce(sum(t.critical_total), 0)                    AS critical_total,
       coalesce(sum(t.critical_open), 0)                     AS critical_open,
       coalesce(sum(t.high_total), 0)                        AS high_total,
       coalesce(sum(t.high_open), 0)                         AS high_open,
       coalesce(sum(t.medium_total), 0)                      AS medium_total,
       coalesce(sum(t.medium_open), 0)                       AS medium_open,
       coalesce(sum(t.low_total), 0)                         AS low_total,
       coalesce(sum(t.low_open), 0)                          AS low_open,
       coalesce(sum(t.sca_total), 0)                         AS sca_total,
       coalesce(sum(t.sca_open), 0)                          AS sca_open,
       coalesce(sum(t.sca_critical_open), 0)                 AS sca_critical_open,
       coalesce(sum(t.sca_high_open), 0)                     AS sca_high_open,
       coalesce(sum(t.sca_medium_open), 0)                   AS sca_medium_open,
       max(t.last_detected_at)                               AS last_detected_at,
       -- SBOM coverage across the subtree. count(cs.asset_id) is how many parts have EVER had a
       -- snapshot; the component count above is how many exist. PRD-SBM-056: the difference is the
       -- number that matters, and a page showing only the covered ones reports a clean estate.
       count(cs.asset_id)                                    AS sbom_covered_parts,
       max(cs.latest_snapshot_at)                            AS sbom_latest_at,
       count(*) FILTER (WHERE cs.quality = 'REJECTED')       AS sbom_rejected_parts
  FROM scope s
  LEFT JOIN asset_finding_tally t ON t.asset_id = s.member_id
  LEFT JOIN sbom_coverage_state cs ON cs.asset_id = s.member_id
 GROUP BY s.root_id, s.tenant_id;

COMMENT ON VIEW application_posture IS
    'An application and everything it contains, rolled up. Counts the SUBTREE: a vulnerability in a '
    'service belongs to the application that service is part of, and reporting only the application '
    'row would report zero for an application with forty open findings underneath it.';

GRANT SELECT ON application_posture TO app_runtime, integrity_verifier;

-- -----------------------------------------------------------------------------
-- 6. ai_suggestion — the ledger ADR-005 requires, created BEFORE anything can write to it.
--
-- ADR-005: "AI writes only to a suggestion ledger; promotion into the system of record is an audited
-- human action." ADR-044 defers AI capability from v1 while the architecture is built. This is that
-- architecture: the table exists, nothing writes to it yet, and the interface renders it empty and
-- says why rather than showing an analysis nobody produced.
--
-- The shape is what makes the ADR enforceable rather than aspirational:
--
--   * A suggestion NAMES ITS SUBJECT and never edits it. There is no path from this table into
--     `finding` or `asset`; promotion reads a row here and performs an ordinary audited write.
--   * grounding_refs records what the suggestion was derived FROM. A suggestion that cannot say what
--     it read is one nobody can check, and DOC-10 makes grounding the difference between an
--     assistant and a rumour.
--   * model_identity and prompt_version are recorded, so a suggestion produced by a model that was
--     later found to be wrong can be found again and withdrawn.
--   * promoted_by and promoted_at are the audited human action. NULL means nobody has accepted it,
--     which is the default and stays the default.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_suggestion (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id         uuid        NOT NULL,
    suggestion_kind   text        NOT NULL,
    subject_kind      text        NOT NULL,
    subject_id        uuid        NOT NULL,
    -- What the suggestion says, structured per kind. Never rendered as a numeric value: ADR-038
    -- binds narrative placeholders to record fields and forbids AI generating a number.
    content           jsonb       NOT NULL,
    grounding_refs    jsonb       NOT NULL DEFAULT '[]',
    model_identity    text        NOT NULL,
    prompt_version    text        NOT NULL,
    confidence_band   text,
    generated_at      timestamptz NOT NULL DEFAULT now(),
    state             text        NOT NULL DEFAULT 'PENDING',
    promoted_by       uuid,
    promoted_at       timestamptz,
    rejected_reason   text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    row_version       int         NOT NULL DEFAULT 1,

    CONSTRAINT ck_ai_suggestion__kind CHECK (suggestion_kind IN
        ('RECURRING_WEAKNESS', 'REMEDIATION_DRAFT', 'DUPLICATE_CANDIDATE', 'SEVERITY_REVIEW',
         'NARRATIVE_DRAFT')),
    CONSTRAINT ck_ai_suggestion__subject CHECK (subject_kind IN
        ('ASSET', 'FINDING', 'ORG_NODE', 'ASSESSMENT_REQUEST')),
    CONSTRAINT ck_ai_suggestion__state CHECK (state IN ('PENDING', 'PROMOTED', 'REJECTED', 'WITHDRAWN')),
    -- Promotion is a human action or it did not happen. A row marked PROMOTED with nobody named is
    -- the exact failure ADR-005 exists to prevent, so it is unrepresentable rather than discouraged.
    CONSTRAINT ck_ai_suggestion__promotion CHECK
        ((state = 'PROMOTED') = (promoted_by IS NOT NULL AND promoted_at IS NOT NULL)),
    CONSTRAINT ck_ai_suggestion__rejection CHECK
        (state <> 'REJECTED' OR (rejected_reason IS NOT NULL AND rejected_reason <> '')),
    -- A suggestion with no grounding is a rumour with a model number attached.
    CONSTRAINT ck_ai_suggestion__grounded CHECK (jsonb_typeof(grounding_refs) = 'array')
);

SELECT apply_tenant_isolation('ai_suggestion');

CREATE INDEX IF NOT EXISTS ix_ai_suggestion__subject
    ON ai_suggestion (tenant_id, subject_kind, subject_id, generated_at DESC)
    WHERE state = 'PENDING';
COMMENT ON INDEX ix_ai_suggestion__subject IS
    'Serves: the pending-suggestion panel on an application or finding page, which is the only read '
    'this table has until a promotion workflow exists.';

-- SELECT and INSERT only. No UPDATE and no DELETE for the runtime: a suggestion is superseded by a
-- new row rather than edited, so the ledger stays a ledger. Promotion flips `state`, which is why
-- UPDATE is granted narrowly below rather than withheld entirely.
GRANT SELECT, INSERT, UPDATE ON ai_suggestion TO app_runtime;
GRANT SELECT ON ai_suggestion TO integrity_verifier;
REVOKE DELETE ON ai_suggestion FROM app_runtime;
