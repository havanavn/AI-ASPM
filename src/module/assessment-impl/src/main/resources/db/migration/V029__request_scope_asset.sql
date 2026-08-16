-- =============================================================================
-- V029 — the assets a REQUEST names, recorded when the request is raised.
--
-- WHY THIS TABLE DID NOT EXIST AND HAD TO
--
-- assessment_scope_asset hangs off `assessment` — the execution record — which is created when an
-- assessor is named. So until somebody picked up the work, a request had no recorded subject at all:
-- the board's `primary_application` column resolves through assessment, and a request nobody had
-- accepted showed an empty application forever. Intake asks "what are we assessing"; that answer is
-- the requester's and it exists before any assessor does.
--
-- It is a separate table from assessment_scope_asset rather than a widened one, because the two
-- answer different questions and diverge on purpose. The request says what was ASKED for; the
-- assessment says what was actually COVERED. INV-ASM-10 already requires the assessment's scope to
-- be copied from the request rather than chosen by the assessor, and keeping both means the copy can
-- be compared with its source — a scope that grew during an engagement is a fact somebody needs.
--
-- WHY A PROJECT AND ITS APPLICATION ARE BOTH ROWS
--
-- A requester names a project; the application above it is derived and stored alongside. Storing
-- both looks redundant and is not: the derivation is true at the moment of asking, and a project
-- that later moves under a different application must not silently rewrite what a closed request was
-- for. The graph answers "where does this project sit now"; these rows answer "what was this request
-- raised against", and those are different questions once time passes.
-- =============================================================================

CREATE TABLE IF NOT EXISTS assessment_request_scope_asset (
    tenant_id   uuid NOT NULL,
    request_id  uuid NOT NULL REFERENCES assessment_request (id) ON DELETE RESTRICT,
    asset_id    uuid NOT NULL,
    -- What the requester picked, versus what was derived from it. A reviewer looking at an intake
    -- form needs to know which of the two the person actually chose.
    named_by_requester boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    PRIMARY KEY (tenant_id, request_id, asset_id)
);

-- The query this serves: "every request raised against this project, newest first", which is the
-- panel at the bottom of a project dashboard and the one that grows without bound.
CREATE INDEX IF NOT EXISTS ix_asm_req_scope__asset ON assessment_request_scope_asset (tenant_id, asset_id);

SELECT apply_tenant_isolation('assessment_request_scope_asset');

COMMENT ON TABLE assessment_request_scope_asset IS
    'What a request was raised against, recorded at intake. Distinct from assessment_scope_asset, '
    'which records what an assessment actually covered — see the header of V029.';
COMMENT ON COLUMN assessment_request_scope_asset.named_by_requester IS
    'True for the asset the requester chose; false for one derived from it, such as the application '
    'above a project. A reviewer has to be able to tell the choice from the consequence.';

GRANT SELECT, INSERT, UPDATE, DELETE ON assessment_request_scope_asset TO app_runtime;
GRANT SELECT ON assessment_request_scope_asset TO integrity_verifier;
