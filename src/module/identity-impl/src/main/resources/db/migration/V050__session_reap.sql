-- =============================================================================
-- V050 — expired sessions are removed on a schedule.
--
-- THE PROBLEM. `principal_session` had 119 rows of which 115 were already expired, the oldest six days
-- old, and nothing ever removed one. An expired session is not a security hole — every read checks
-- `absolute_expires_at` — but the table grows without bound, and a table nobody prunes is a table whose
-- index eventually costs every sign-in a little more. It also makes "how many people are signed in"
-- unanswerable without a predicate somebody has to remember.
--
-- WHY IT REUSES THE SCANNER'S TICK RATHER THAN ADDING A SCHEDULER. The application tier has no
-- scheduler on purpose (see V042): adding one makes every replica run it, and OPS-DEP-007 requires a
-- singleton with leader election that only enqueues work. There is already exactly one thing in this
-- deployment that ticks — the scanner container — and it already authenticates with a signed service
-- credential. Giving it one more call is one mechanism; a second timer would be two mechanisms for
-- "something happens periodically", which is how a deployment ends up with two answers to when.
--
-- WHY A GRACE PERIOD RATHER THAN DELETING AT EXPIRY. A session that expired four minutes ago is the
-- evidence for "why was I signed out", and that question arrives after the fact. Seven days is long
-- enough to answer it and short enough that the table stays small. Deleting at the instant of expiry
-- would save nothing measurable and would remove the only record of a session that misbehaved.
-- =============================================================================

-- The one grant that was missing. Sessions could be created, read and revoked, never removed.
GRANT DELETE ON principal_session TO app_runtime;

INSERT INTO permission_catalogue (code, domain, label_i18n, is_restricted, requires_step_up)
VALUES ('iam.session.reap', 'iam',
        '{"en":"Remove expired sessions","vi":"Dọn phiên đã hết hạn"}'::jsonb, false, false)
ON CONFLICT (code) DO NOTHING;

-- Granted to the credential that already ticks, not to a human role. Nobody should be pressing this:
-- it is housekeeping with no decision in it, and a button would invite somebody to treat a growing
-- table as their problem to remember.
DO $$
DECLARE
    t uuid;
    touched integer;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);
        UPDATE service_credential
           SET permissions = array_append(permissions, 'iam.session.reap')
         WHERE revoked_at IS NULL
           AND 'sbm.scan.run' = ANY (permissions)
           AND NOT ('iam.session.reap' = ANY (permissions));
        GET DIAGNOSTICS touched = ROW_COUNT;
        IF touched > 0 THEN
            RAISE NOTICE 'iam.session.reap granted to % ticking credential(s) in tenant %', touched, t;
        END IF;
    END LOOP;
END
$$;
