-- =============================================================================
-- V064 — a tenant has a business calendar, and every date is computed in it.
--
-- WHAT WAS WRONG. `NFR-INT-003` requires "UTC storage with tenant-timezone business calendar
-- computation". The first half was done and the second half did not exist: there was no timezone
-- anywhere in the schema, the database session ran in UTC, and every `date_trunc`, `to_char` and
-- `now()` in the application — 101 of them across twenty files — bucketed by UTC dates.
--
-- Measured on this deployment before the change: 172 of 715 findings have a detection timestamp
-- between 00:00 and 06:59 UTC. For a UTC+7 tenant that is 24% of the estate whose LOCAL date is one
-- day later than the date every chart, every ageing band and every service-level day count assigns
-- it. A finding raised at 08:00 on the first of the month in Ho Chi Minh City was counted in the
-- previous month.
--
-- WHY THIS FIXES ALL 101 WITHOUT TOUCHING ANY OF THEM. `date_trunc`, `to_char` and `now()` on a
-- `timestamptz` are evaluated in the session's timezone. Setting that timezone where the tenant is
-- already established makes every one of those expressions answer in the tenant's calendar, in one
-- place, with no query rewritten and no chance of the twentieth one being missed. The alternative —
-- threading `AT TIME ZONE` through a hundred expressions — is a hundred opportunities to forget, on
-- a correctness property nobody notices is wrong until a monthly report is challenged.
--
-- WHY A DEFAULT OF UTC. Changing the default would silently reinterpret every existing tenant's
-- history on the day this ships. UTC preserves exactly what the platform did until now, and a tenant
-- moving to its real calendar is then a deliberate, visible act.
-- =============================================================================

ALTER TABLE tenant
    ADD COLUMN IF NOT EXISTS business_timezone text NOT NULL DEFAULT 'UTC';

-- Validated against the engine's own zone table, by a trigger.
--
-- A CHECK constraint cannot do it — PostgreSQL rejects a subquery in one, which is how this was first
-- written and how it failed. The validation is worth the trigger: an invalid zone name does not fail
-- on write, it fails on the first query of the day that uses it, inside a report, as an unhandled
-- error nobody connects to a configuration change made weeks earlier.
CREATE OR REPLACE FUNCTION reject_unknown_business_timezone() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_timezone_names z WHERE z.name = NEW.business_timezone) THEN
        RAISE EXCEPTION 'business_timezone % is not a zone this engine knows; use an IANA name such '
            'as Asia/Ho_Chi_Minh', NEW.business_timezone USING ERRCODE = 'invalid_parameter_value';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS tr_tenant__business_timezone ON tenant;
CREATE TRIGGER tr_tenant__business_timezone
    BEFORE INSERT OR UPDATE OF business_timezone ON tenant
    FOR EACH ROW EXECUTE FUNCTION reject_unknown_business_timezone();

COMMENT ON COLUMN tenant.business_timezone IS
    'IANA zone for business calendar computation (NFR-INT-003). Storage stays UTC; day, week and '
    'month boundaries, ageing bands and service-level counts are computed in this zone.';

-- -----------------------------------------------------------------------------
-- The runtime may read the calendar, and only the calendar.
--
-- Column-level. The application role has no privilege on `tenant` — residency region, entitlement
-- tier and lifecycle state belong to the migration credential (OPS-DEP-009) — and that stays true.
-- What it must know is which calendar to compute in, which is a question about the tenant it is
-- already serving.
-- -----------------------------------------------------------------------------

GRANT SELECT (id, business_timezone) ON tenant TO app_runtime;

-- -----------------------------------------------------------------------------
-- Establishing a session: the tenant AND the calendar, in one call.
--
-- One function rather than two statements at thirty-eight call sites, because two statements is one
-- statement plus an opportunity to write only the first. The order inside matters and is why this
-- cannot be a single SELECT with two set_config calls: reading the tenant row requires the row-level
-- policy to pass, and the policy reads the setting the first call establishes. A SELECT list has no
-- guaranteed evaluation order, so the two would race.
--
-- SECURITY INVOKER, deliberately. The policy still applies inside: this function reads the row the
-- caller has just claimed to be, and it can read no other. A definer-rights function would read any
-- tenant's row and would be a way to ask which tenants exist.
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION establish_tenant_session(tenant_id text, transaction_local boolean)
    RETURNS void
    LANGUAGE plpgsql
    SET search_path = public
AS $$
DECLARE
    zone text;
BEGIN
    PERFORM set_config('aspm.current_tenant', tenant_id, transaction_local);

    SELECT t.business_timezone INTO zone FROM tenant t WHERE t.id = tenant_id::uuid;

    -- coalesce, not an exception. A tenant row the policy hides, or one that predates this column on
    -- a partially migrated database, must not take the whole request down: UTC is what every date in
    -- this platform was computed in until now, so falling back to it is the previous behaviour rather
    -- than a new one. The tenant isolation that matters is enforced by the policy above and is not
    -- weakened by this.
    PERFORM set_config('timezone', coalesce(zone, 'UTC'), transaction_local);
END;
$$;

GRANT EXECUTE ON FUNCTION establish_tenant_session(text, boolean) TO app_runtime;
