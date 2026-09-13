-- =============================================================================================
-- V080 — a planned window names who is expected to do the work.
--
-- WHAT WAS MISSING. V070 made a plan a dated intention for a target. The planner's next two
-- questions — "which pentest team is this for" and "who on that team" — had no column, so the plan
-- could be read per application and per business unit but not per team or per person, and the
-- capacity view had nothing to count against the year that was planned. Reported from use: the plan
-- must be readable "per business unit, per pentest team, per person", from an overview down to one
-- window.
--
-- WHY PLAIN UUIDs. `assessor_team` is the capacity module's and `principal` is identity's; ADR-030
-- forbids a foreign key across module boundaries, so integrity is in the domain layer (the service
-- resolves both names at read time and shows "no longer exists" rather than failing) and a retired
-- team keeps its historical plan.
--
-- BOTH NULLABLE, as the type and trigger are: a plan laid out for next year legitimately does not yet
-- know who will run each window. NULL reads as "unassigned" on the plan overview, which is the
-- planning finding, not a default somebody did not choose.
--
-- Requirements: PRD-ASM-016, PRD-ASM-017, PRD-CAP-001, PRD-CAP-002. Decisions: ADR-030, ADR-065.
-- =============================================================================================
ALTER TABLE assessment_plan_window
    ADD COLUMN IF NOT EXISTS team_id               uuid,
    ADD COLUMN IF NOT EXISTS assessor_principal_id uuid;

COMMENT ON COLUMN assessment_plan_window.team_id IS
    'The assessor team expected to run this window. Planning intent, not an assignment on a request; '
    'resolved by name at read time (ADR-030, no cross-module FK). NULL = not yet decided.';
COMMENT ON COLUMN assessment_plan_window.assessor_principal_id IS
    'The person expected to lead this window. Same status as team_id: intent, nullable.';

CREATE INDEX IF NOT EXISTS ix_plan_window__team
    ON assessment_plan_window (tenant_id, team_id, starts_on) WHERE state = 'PLANNED' AND team_id IS NOT NULL;
COMMENT ON INDEX ix_plan_window__team IS 'Serves: the plan overview by team and the team load by month.';
CREATE INDEX IF NOT EXISTS ix_plan_window__assessor
    ON assessment_plan_window (tenant_id, assessor_principal_id, starts_on) WHERE state = 'PLANNED' AND assessor_principal_id IS NOT NULL;
COMMENT ON INDEX ix_plan_window__assessor IS 'Serves: the plan overview by person.';
