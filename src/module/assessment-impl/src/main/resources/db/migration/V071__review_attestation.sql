-- =============================================================================================
-- V071 — an assessment the platform did not observe, asserted by somebody who did.
--
-- WHAT WAS MISSING, AND HOW IT WAS FOUND. Reported from use on 2026-08-27, while preparing to load a
-- real estate: *"với những request chưa được note trước đó, hoặc trước khi có hệ thống này, tôi muốn
-- thêm vào là đã đánh giá trong khoảng thời gian trước đó thì làm như nào"* — how do I record that
-- something was assessed in an earlier period, including before this platform existed.
--
-- Two cases hide inside that question and only one of them was already answerable.
--
--   1. A request IS in the platform but its reason was never recorded. Already solvable: the board
--      filters on trigger = none, and `AssessmentService.setTrigger` sets it. The review then counts,
--      PROVIDED the request also carries an execution record — `application_full_review` inner-joins
--      `assessment`, so a request that never ran cannot be made to count by relabelling it, and that
--      is correct.
--   2. The assessment happened BEFORE the platform. There is nothing to relabel. Making it count
--      through the existing model would mean fabricating a request, an execution record, a scope link
--      and a terminal transition — inventing entries in `assessment_request_transition`, which
--      `PRD-PLT-001` names as data that cannot be reconstructed. A migration that writes a workflow
--      history nobody lived is worse than the gap it closes.
--
-- The consequence of leaving case 2 open is not cosmetic. On first load, every application assessed
-- outside the platform reads `NEVER` and a large share of them read `OVERDUE`, so the first thing the
-- estate's own security team sees is a coverage figure they know to be wrong — and a figure known to
-- be wrong stops being read at all.
--
-- WHY AN ATTESTATION IS A DISTINCT CONCEPT AND NOT A BACKDATED REQUEST. Product principle 1 is
-- usually quoted for measured-versus-not-measured. It has a third state, and this is it: **asserted**.
--
--   * OBSERVED — the platform holds the request, the execution record, the transitions and the
--     findings. "This was reviewed" is a claim the platform can substantiate from its own data.
--   * ATTESTED — a person states that a review happened between two dates. The platform holds the
--     statement and who made it, and nothing else. It may hold an attached report; it did not watch
--     the work.
--   * NOT MEASURED — nothing at all.
--
-- Collapsing the first two would make the coverage figure a mixture of evidence and hearsay with no
-- way to separate them, which is precisely the failure the principle exists to prevent. So the
-- attestation is its own table, the cadence view reports which of the two produced the latest date,
-- and the interface says "attested" wherever it shows one.
--
-- WHAT AN ATTESTATION DELIBERATELY DOES NOT DO. It does not create findings, it does not enter the
-- coverage-by-severity figures, and it does not increment `full_review_count`. That column keeps its
-- existing meaning — reviews the platform observed — because consumers already read it and changing
-- what a name means silently is what product principle 10 forbids. The attested reviews are counted
-- in their own column beside it.
--
-- WHY `last_full_review_at` DOES change to include attestations. It is the one place where the
-- combined answer is the useful one: the question that column exists to answer is "when was this last
-- reviewed", and an answer of "never" beside a status of "current" would be a screen contradicting
-- itself. The column therefore carries the latest review from either source, and the new
-- `last_full_review_source` column says which — so a reader is never shown a date without being able
-- to find out how the platform knows it.
-- =============================================================================================

CREATE TABLE IF NOT EXISTS application_review_attestation (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id           uuid        NOT NULL,

    -- The application. Per-application and not per-project, because the periodic obligation
    -- `full_review_policy` expresses is per application and this table exists to discharge it.
    asset_id            uuid        NOT NULL,

    -- When the work happened. A range and not a single date, because that is how it is remembered:
    -- "the pentest was in the second quarter", not "the pentest closed on the 14th". `performed_to`
    -- is what the cadence uses, because an obligation is discharged when the work finished.
    performed_from      date        NOT NULL,
    performed_to        date        NOT NULL,

    -- Who did the work, as free text. Not a foreign key to anything: the commonest case is an
    -- external firm that will never be a principal here, and ADR-024's refusal to assume anything
    -- about the outside world applies to organizations as much as to source control. A tenant that
    -- wants this normalized can put a code in it.
    performed_by        text,

    -- What kind of work it was, where the tenant has a type that matches. Nullable, because a review
    -- from four years ago predates whatever the tenant's type catalogue says today, and forcing a
    -- choice would put a wrong answer in rather than leave a blank.
    assessment_type_id  uuid,

    -- The evidence, if any. A reference to the report that was produced — an attachment already held
    -- by the platform, or a location outside it. Nullable and visibly so: an attestation with no
    -- evidence is still worth recording, and it must be distinguishable from one with a report
    -- behind it, because those are different strengths of claim.
    evidence_ref        text,

    note                text,

    -- Who asserted it, and when they did. THIS IS THE POINT OF THE TABLE. An attested review is only
    -- as good as the person who stood behind it, so the record is worthless without an attributed
    -- author — and `attested_by` is therefore NOT NULL, unlike `created_by` elsewhere in this schema.
    attested_by         uuid        NOT NULL,
    attested_at         timestamptz NOT NULL DEFAULT now(),

    -- Withdrawal, not deletion. Somebody who asserted a review in error must be able to take it back
    -- without the assertion vanishing from the record: the fact that a claim was made and retracted
    -- is exactly what a later review of the coverage figures needs to see.
    withdrawn_at        timestamptz,
    withdrawn_by        uuid,
    withdrawal_reason   text,

    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    updated_by          uuid,
    row_version         int         NOT NULL DEFAULT 1,

    CONSTRAINT fk_review_attestation__asset
        FOREIGN KEY (tenant_id, asset_id) REFERENCES asset (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_review_attestation__type
        FOREIGN KEY (tenant_id, assessment_type_id)
        REFERENCES assessment_type (tenant_id, id) ON DELETE RESTRICT,

    CONSTRAINT ck_review_attestation__ordered CHECK (performed_to >= performed_from),

    -- An assessment cannot have finished in the future. This is the one date check that matters: the
    -- table exists to record history, and a "past review" dated next year would discharge an
    -- obligation that has not come due. Enforced here rather than in the domain layer because it
    -- needs no join and a CHECK cannot be forgotten by the next writer.
    CONSTRAINT ck_review_attestation__not_future CHECK (performed_to <= current_date),

    CONSTRAINT ck_review_attestation__withdrawal
        CHECK ((withdrawn_at IS NULL) = (withdrawn_by IS NULL))
);

SELECT apply_tenant_isolation('application_review_attestation');

CREATE INDEX IF NOT EXISTS ix_review_attestation__asset
    ON application_review_attestation (tenant_id, asset_id, performed_to DESC)
    WHERE withdrawn_at IS NULL;
COMMENT ON INDEX ix_review_attestation__asset IS
    'Serves: the cadence view, which takes max(performed_to) per application over live attestations, '
    'and the per-application list on the planning screen and the application page.';

COMMENT ON TABLE application_review_attestation IS
    'A whole-application review the platform did not observe, asserted by an attributed person. '
    'Distinct from application_full_review on product-principle-1 grounds: observed and asserted are '
    'different strengths of claim and the coverage figures must be able to separate them.';
COMMENT ON COLUMN application_review_attestation.attested_by IS
    'The principal who asserted this. NOT NULL: an unattributed assertion about coverage is not worth '
    'holding, because there is nobody to ask when it turns out to be wrong.';
COMMENT ON COLUMN application_review_attestation.evidence_ref IS
    'Where the report is, if there is one. NULL is a weaker claim and is shown as such rather than '
    'being presented identically to an attestation with evidence behind it.';
COMMENT ON COLUMN application_review_attestation.withdrawn_at IS
    'Retraction, not deletion. That a claim was made and taken back is what a review of the coverage '
    'figures needs to see; a deleted row reads as a claim never made.';

GRANT SELECT, INSERT, UPDATE ON application_review_attestation TO app_runtime;
GRANT SELECT ON application_review_attestation TO integrity_verifier;

-- -----------------------------------------------------------------------------
-- The cadence view learns about attestations.
--
-- Every existing column keeps its name, position and type, so no consumer needs changing.
-- `full_review_count`, `full_review_in_flight` and `full_review_abandoned` keep their existing
-- MEANING too — reviews the platform observed — and the attested ones are counted separately.
--
-- Two columns change what they compute, both deliberately, and both stated in the header above:
-- `last_full_review_at` and everything derived from it now take the later of observed and attested.
-- -----------------------------------------------------------------------------
DROP VIEW IF EXISTS application_review_cadence;

CREATE VIEW application_review_cadence AS
WITH completed AS (
    SELECT fr.asset_id, fr.tenant_id,
           count(*)          AS full_review_count,
           max(fr.closed_at) AS last_full_review_at
      FROM application_full_review fr
     WHERE fr.closed_at IS NOT NULL
     GROUP BY fr.asset_id, fr.tenant_id
),
in_flight AS (
    SELECT fr.asset_id, fr.tenant_id, count(*) AS full_review_in_flight
      FROM application_full_review fr
     WHERE fr.closed_at IS NULL AND fr.state_category IS DISTINCT FROM 'TERMINAL'
     GROUP BY fr.asset_id, fr.tenant_id
),
abandoned AS (
    SELECT fr.asset_id, fr.tenant_id, count(*) AS full_review_abandoned
      FROM application_full_review fr
     WHERE fr.abandoned_at IS NOT NULL
     GROUP BY fr.asset_id, fr.tenant_id
),
-- Asserted reviews. A withdrawn attestation is excluded from the figures and keeps its row: the
-- retraction is part of the record, not a reason to forget the claim.
attested AS (
    SELECT at.asset_id, at.tenant_id,
           count(*)               AS attested_review_count,
           max(at.performed_to)   AS last_attested_review_at
      FROM application_review_attestation at
     WHERE at.withdrawn_at IS NULL
     GROUP BY at.asset_id, at.tenant_id
),
-- The later of the two, and which one it was. Computed once here so the status, the due date and the
-- source cannot disagree with each other — three expressions over the same two inputs is three
-- chances for one of them to be written differently.
effective AS (
    SELECT a.id AS asset_id,
           greatest(c.last_full_review_at,
                    -- A date becomes an instant at the END of the day it names: a review that
                    -- finished on the 14th is not still owed at noon on the 14th.
                    (at.last_attested_review_at + 1)::timestamptz) AS last_review_at,
           CASE
               WHEN c.last_full_review_at IS NULL
                    AND at.last_attested_review_at IS NULL          THEN NULL
               WHEN at.last_attested_review_at IS NULL              THEN 'OBSERVED'
               WHEN c.last_full_review_at IS NULL                   THEN 'ATTESTED'
               WHEN (at.last_attested_review_at + 1)::timestamptz
                    > c.last_full_review_at                         THEN 'ATTESTED'
               ELSE 'OBSERVED'
           END AS last_review_source
      FROM asset a
      LEFT JOIN completed c ON c.asset_id = a.id
      LEFT JOIN attested  at ON at.asset_id = a.id
)
SELECT a.id                                          AS asset_id,
       a.tenant_id,
       coalesce(c.full_review_count, 0)              AS full_review_count,
       coalesce(f.full_review_in_flight, 0)          AS full_review_in_flight,
       coalesce(b.full_review_abandoned, 0)          AS full_review_abandoned,
       e.last_review_at                              AS last_full_review_at,
       p.interval_months,
       p.warn_days_before,
       CASE WHEN p.interval_months IS NULL THEN NULL
            WHEN e.last_review_at IS NULL THEN NULL
            ELSE e.last_review_at + make_interval(months => p.interval_months)
       END                                           AS next_full_review_due,
       CASE
           WHEN p.interval_months IS NULL                 THEN 'NO_OBLIGATION'
           WHEN e.last_review_at IS NULL                  THEN 'NEVER'
           WHEN e.last_review_at
                + make_interval(months => p.interval_months) < now()
                                                          THEN 'OVERDUE'
           WHEN e.last_review_at
                + make_interval(months => p.interval_months)
                - make_interval(days => p.warn_days_before) < now()
                                                          THEN 'DUE_SOON'
           ELSE 'CURRENT'
       END                                           AS full_review_status,
       -- Appended, so every existing consumer is unaffected.
       coalesce(at.attested_review_count, 0)         AS attested_review_count,
       at.last_attested_review_at,
       e.last_review_source                          AS last_full_review_source
  FROM asset a
  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION'
  LEFT JOIN org_node n ON n.id = a.owning_node_id
  LEFT JOIN completed c ON c.asset_id = a.id
  LEFT JOIN in_flight f ON f.asset_id = a.id
  LEFT JOIN abandoned b ON b.asset_id = a.id
  LEFT JOIN attested  at ON at.asset_id = a.id
  LEFT JOIN effective e ON e.asset_id = a.id
  LEFT JOIN full_review_policy p
         ON p.criticality_tier_id = coalesce(a.criticality_tier_id, n.criticality_tier_id);

COMMENT ON VIEW application_review_cadence IS
    'Periodic full-review position per application. last_full_review_at is the later of a review the '
    'platform OBSERVED end to end and one a person ATTESTED to, and last_full_review_source says '
    'which — because product principle 1 makes evidence and assertion different claims, and a '
    'coverage figure that mixes them silently cannot be audited. full_review_count remains '
    'observed-only; attested reviews are counted in attested_review_count beside it.';
COMMENT ON COLUMN application_review_cadence.last_full_review_source IS
    'OBSERVED, ATTESTED, or NULL where there has been no review of either kind. Never inferred by a '
    'consumer from whether a count is zero: an application can carry observed reviews AND a later '
    'attested one.';

GRANT SELECT ON application_review_cadence TO app_runtime, integrity_verifier;
