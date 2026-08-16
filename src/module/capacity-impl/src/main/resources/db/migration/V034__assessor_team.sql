-- =============================================================================
-- V034 — named groups of assessors, so workload can be read per team.
--
-- WHY A TABLE RATHER THAN A DERIVATION
--
-- The analytics view previously grouped assessors by the organization node their role assignment is
-- scoped to, and labelled it "coverage area" because that is what it was. A coverage area is not a
-- team: two people covering payments may sit in different squads, and one squad routinely covers
-- three areas. Nothing in the org tree or the role model expresses "these five people work
-- together", so nothing could be derived — the choice was a table or a chart that lies about what it
-- is grouping.
--
-- ONE LIVE TEAM PER PERSON, AND THE REASON IS ARITHMETIC
--
-- uq_team_member__live is on the PRINCIPAL, not on the pair. A person in two teams would have their
-- requests counted in both, and the sum across teams would then exceed the number of requests that
-- exist — the same double-count that made the project dashboard report fifteen open findings over an
-- estate holding thirteen. A per-team chart is only worth reading if its bars add up.
--
-- If matrix membership is genuinely needed later, this constraint is the thing to relax, and the
-- charts have to stop summing on the same day.
--
-- MEMBERSHIP IS TOMBSTONED
--
-- removed_at rather than DELETE. "Who was on this team when that engagement ran" is a question a
-- retrospective asks, and a deleted row answers it with today's roster.
-- =============================================================================

CREATE TABLE IF NOT EXISTS assessor_team (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,
    name            text NOT NULL,
    description     text,
    lifecycle_state text NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      uuid,
    row_version     integer NOT NULL DEFAULT 1,
    CONSTRAINT ck_assessor_team__name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_assessor_team__lifecycle
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED'))
);

-- Names are the tenant's own words (ADR-027) and are compared case-insensitively, because "Red Team"
-- and "red team" arriving as two teams is a data-quality problem nobody notices until a chart has
-- two bars for one squad.
CREATE UNIQUE INDEX IF NOT EXISTS uq_assessor_team__name
    ON assessor_team (tenant_id, lower(btrim(name)))
    WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE IF NOT EXISTS assessor_team_member (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid NOT NULL,
    team_id        uuid NOT NULL REFERENCES assessor_team (id) ON DELETE RESTRICT,
    principal_id   uuid NOT NULL,
    added_at       timestamptz NOT NULL DEFAULT now(),
    added_by       uuid,
    removed_at     timestamptz,
    removed_reason text,
    CONSTRAINT ck_team_member__removal_attributed
        CHECK ((removed_at IS NULL) = (removed_reason IS NULL))
);

-- On the principal, not the pair. See the header: a person in two teams breaks the arithmetic of
-- every per-team chart.
CREATE UNIQUE INDEX IF NOT EXISTS uq_team_member__live
    ON assessor_team_member (tenant_id, principal_id)
    WHERE removed_at IS NULL;

-- Serves: the roster panel, and the join every per-team series makes.
CREATE INDEX IF NOT EXISTS ix_team_member__team
    ON assessor_team_member (tenant_id, team_id)
    WHERE removed_at IS NULL;

SELECT apply_tenant_isolation('assessor_team');
SELECT apply_tenant_isolation('assessor_team_member');

COMMENT ON TABLE assessor_team IS
    'A named group of assessors. Tenant vocabulary; the platform enforces no team names (ADR-027).';
COMMENT ON INDEX uq_team_member__live IS
    'One live team per person. Relaxing this makes every per-team chart double-count — see V034.';

GRANT SELECT, INSERT, UPDATE ON assessor_team TO app_runtime;
GRANT SELECT, INSERT, UPDATE ON assessor_team_member TO app_runtime;
GRANT SELECT ON assessor_team, assessor_team_member TO integrity_verifier;

-- The permission that manages them. A migration because PRD-AUZ-001 makes the catalogue
-- product-fixed; which roles carry it stays the tenant's decision.
INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up) VALUES
  ('cap.team.manage', 'CAP',
   '{"en":"Create assessor teams and manage their membership",
     "vi":"Tạo nhóm đánh giá và quản lý thành viên"}'::jsonb, false, false)
ON CONFLICT (code) DO NOTHING;
