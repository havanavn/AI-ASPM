-- ==============================================================================================
-- Starting configuration for assessment triggers and the periodic full-review obligation.
--
-- These are TENANT DATA, not product constants (ADR-027). The codes below are one group's way of
-- classifying why a review happens; a tenant that distinguishes "vendor onboarding" or "post-incident
-- review" adds rows, and a tenant that reviews quarterly changes a number. Nothing in the application
-- reads any of these codes.
--
-- The four defaults are the ones an application security function almost always needs, and one of
-- them — the periodic review — is the only one seeded with counts_as_full_review = true. That single
-- flag is what makes "this application has been assessed fourteen times" stop being an answer to
-- "when was it last reviewed end to end".
-- ==============================================================================================
SET LOCAL aspm.current_tenant = '11111111-1111-1111-1111-111111111111';

INSERT INTO assessment_trigger
    (tenant_id, code, label_i18n, counts_as_full_review, guidance, display_order)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'CHANGE_REQUEST',
     '{"en": "Change review", "vi": "Đánh giá thay đổi"}'::jsonb, false,
     'A specific change is going in. Scope is the change and what it touches, not the application.',
     10),
    ('11111111-1111-1111-1111-111111111111', 'NEW_GOLIVE',
     '{"en": "Pre-go-live review", "vi": "Đánh giá trước khi go-live"}'::jsonb, false,
     'Something is about to serve real users or real data for the first time. Blocking by intent.',
     20),
    ('11111111-1111-1111-1111-111111111111', 'PERIODIC_FULL',
     '{"en": "Periodic full application review", "vi": "Đánh giá tổng thể định kỳ"}'::jsonb, true,
     'The whole application, on its recurring cycle. This is the review that discharges the '
     'obligation in the review policy; a change review does not, however many there have been.',
     30),
    ('11111111-1111-1111-1111-111111111111', 'AD_HOC',
     '{"en": "Ad hoc / on request", "vi": "Đánh giá đột xuất"}'::jsonb, false,
     'Raised outside the cycle for a reason none of the above describes. State the reason in the '
     'request title, so that a queue of these does not become an unexplained category.',
     40)
ON CONFLICT (tenant_id, code) DO UPDATE
    SET label_i18n = EXCLUDED.label_i18n,
        guidance = EXCLUDED.guidance,
        counts_as_full_review = EXCLUDED.counts_as_full_review,
        display_order = EXCLUDED.display_order;

-- ----------------------------------------------------------------------------------------------
-- How often a full review is owed, per criticality tier.
--
-- Twelve months for the top two tiers is the figure most regulated groups land on and the one the
-- requirement conversation named. It is written here rather than in code precisely because the next
-- tenant's figure will be different — and because a tenant tightening to six months should not need
-- a release.
--
-- Tier 3 gets an explicit NULL: no recurring obligation. Explicit, because a tier with no row at all
-- would be indistinguishable from a tier somebody forgot to configure, and the interface would then
-- report "no obligation" for both.
-- ----------------------------------------------------------------------------------------------
INSERT INTO full_review_policy (tenant_id, criticality_tier_id, interval_months, warn_days_before)
SELECT '11111111-1111-1111-1111-111111111111', ct.id,
       CASE ct.code WHEN 'TIER1' THEN 12 WHEN 'TIER2' THEN 12 ELSE NULL END,
       CASE ct.code WHEN 'TIER1' THEN 90 ELSE 60 END
  FROM criticality_tier ct
 WHERE ct.tenant_id = '11111111-1111-1111-1111-111111111111'
ON CONFLICT (tenant_id, criticality_tier_id) DO UPDATE
    SET interval_months = EXCLUDED.interval_months,
        warn_days_before = EXCLUDED.warn_days_before;

-- ----------------------------------------------------------------------------------------------
-- Existing requests get a trigger so the board is not a column of blanks on first load.
--
-- Retests inherit the trigger of nothing — they are follow-ups, and AD_HOC is the honest label for
-- a request whose original reason was never recorded. Deliberately NOT assigning PERIODIC_FULL to
-- anything: that flag drives an obligation, and back-dating a discharge of an obligation nobody
-- performed would be the platform lying about its own coverage (PP-1).
-- ----------------------------------------------------------------------------------------------
UPDATE assessment_request r
   SET trigger_id = (SELECT id FROM assessment_trigger
                      WHERE tenant_id = r.tenant_id AND code = 'AD_HOC')
 WHERE r.tenant_id = '11111111-1111-1111-1111-111111111111'
   AND r.trigger_id IS NULL;

-- ----------------------------------------------------------------------------------------------
-- Which terminal states discharge a review obligation.
--
-- Tenant configuration, in the same sense as the interval above. A closure with accepted risk IS a
-- completed review: the assessment happened, findings were raised, and somebody accepted what
-- remained under an exception carrying its own expiry and approver. Treating it as though no review
-- occurred would count the same decision twice — once as an unreviewed application and again as an
-- accepted risk.
--
-- Cancelled and rejected are classified explicitly rather than left out. An unclassified state
-- counts as neither, so the rows are not strictly required — but writing them down is the difference
-- between "we decided these do not count" and "nobody has looked at these yet".
-- ----------------------------------------------------------------------------------------------
INSERT INTO review_completion_state (tenant_id, state_code, disposition)
VALUES ('11111111-1111-1111-1111-111111111111', 'CLOSED_PASSED', 'COMPLETED'),
       ('11111111-1111-1111-1111-111111111111', 'CLOSED_WITH_ACCEPTED_RISK', 'COMPLETED'),
       ('11111111-1111-1111-1111-111111111111', 'REJECTED', 'ABANDONED'),
       ('11111111-1111-1111-1111-111111111111', 'CANCELLED', 'ABANDONED')
ON CONFLICT (tenant_id, state_code) DO UPDATE SET disposition = EXCLUDED.disposition;
