-- =============================================================================
-- V031 — the people on the delivery side of one assessment request.
--
-- WHY A REQUEST NEEDS ITS OWN LIST
--
-- An assessment is a conversation between the team that tested and the team that has to fix. The
-- second group is not the security team and does not hold its permissions: they need to read the
-- findings on THIS request, answer questions on them, and say when a fix is in place. Granting them
-- vul.finding.triage over the organization would give them that authority over every finding in it,
-- including the ones raised against somebody else.
--
-- So participation is per REQUEST. It is the narrowest thing that works, and narrowness is the whole
-- point: product principle 7 says the largest user population has the narrowest permissions and the
-- least training, and developers are that population.
--
-- WHAT A PARTICIPANT MAY DO, AND THE ONE THING THEY MAY NOT
--
-- Read the request and its findings, comment on both, and CLAIM a fix is in place. They may not
-- close a finding. The claim and the verification are separate acts by separate parties — see V032,
-- which adds the claim to `finding` precisely so that it is not a closure. A platform where the team
-- being assessed can close its own findings measures nothing.
--
-- Removed, not deleted. Somebody commented under this grant and the comment stays; deleting the row
-- would leave a comment by a person with no recorded reason to have been there.
-- =============================================================================

CREATE TABLE IF NOT EXISTS assessment_request_participant (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid NOT NULL,
    request_id     uuid NOT NULL REFERENCES assessment_request (id) ON DELETE RESTRICT,
    principal_id   uuid NOT NULL,
    participation  text NOT NULL DEFAULT 'DELIVERY',
    added_by       uuid,
    added_at       timestamptz NOT NULL DEFAULT now(),
    removed_at     timestamptz,
    removed_reason text,
    CONSTRAINT ck_asm_participant__participation
        CHECK (participation IN ('DELIVERY')),
    CONSTRAINT ck_asm_participant__removal_attributed
        CHECK ((removed_at IS NULL) = (removed_reason IS NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_asm_participant__live
    ON assessment_request_participant (tenant_id, request_id, principal_id)
    WHERE removed_at IS NULL;

-- Serves: the participant panel on a request, and the authority test that runs on every comment.
CREATE INDEX IF NOT EXISTS ix_asm_participant__request
    ON assessment_request_participant (tenant_id, request_id)
    WHERE removed_at IS NULL;

-- Serves: "which assessments is this person on", which is how a developer finds their own work and
-- how an offboarding check finds what to reassign.
CREATE INDEX IF NOT EXISTS ix_asm_participant__principal
    ON assessment_request_participant (tenant_id, principal_id)
    WHERE removed_at IS NULL;

SELECT apply_tenant_isolation('assessment_request_participant');

COMMENT ON TABLE assessment_request_participant IS
    'The delivery-side people on one request: they may read it, comment, and claim a fix. Claiming '
    'is not closing — see V032.';

GRANT SELECT, INSERT, UPDATE ON assessment_request_participant TO app_runtime;
GRANT SELECT ON assessment_request_participant TO integrity_verifier;
