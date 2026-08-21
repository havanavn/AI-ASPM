-- =============================================================================
-- V069 — the environment an endpoint is published in becomes tenant data.
--
-- WHAT WAS WRONG. V018 established the convention `asset_relationship.attributes->>'environment'`
-- and stated, correctly, that "an enumeration in a CHECK is exactly the fixed-enumeration-for-a-
-- configurable-surface pattern DOC-00 prohibits, and environments are tenant vocabulary". The
-- column was left unconstrained on that reasoning. The enumeration then reappeared in the only
-- place it could still do damage — the FORMS:
--
--     application editor    Production domain, Staging domain
--     project editor        Production domain, UAT domain
--
-- Two different hardcoded pairs. The consequence is not cosmetic. The interface offers a
-- `domain.<ENV>` column only for environments that already appear in the data, so an environment
-- with no write path can never acquire one, and never becomes a column — an application's UAT host
-- was unrecordable and therefore uncountable, unfilterable and absent from every list. A UAT
-- estate is routinely a copy of production data behind weaker controls, so "which of our systems
-- have a UAT host, and where is it" is a question the platform existed to answer and could not.
--
-- WHY A CATALOGUE TABLE AND NOT A LONGER LIST IN THE FORM. Adding UAT to the application form would
-- have fixed the reported symptom and left the defect: the next tenant runs SIT, or PREPROD, or
-- calls production "LIVE", and is back to a release. The vocabulary is tenant data (ADR-027), so it
-- is stored as tenant data, and both forms render whatever it holds.
--
-- WHY THE EDGE ATTRIBUTE STAYS UNCONSTRAINED. No foreign key from `asset_relationship.attributes`
-- to this table, and deliberately: an importer may legitimately carry an environment name nobody
-- has declared yet, and the choice is between rejecting the edge and admitting it. Rejecting it
-- loses the recorded fact, which product principle 1 forbids — measured-and-unfamiliar is not the
-- same as not-measured. So the catalogue is what the interface OFFERS, and the union of the
-- catalogue with what is actually recorded is what it SHOWS. An environment present in the data and
-- absent from the catalogue still gets its column; it just is not offered in a form until somebody
-- declares it.
--
-- WHY THE DEFAULT ROWS ARE NOT "HARDCODED VOCABULARY". Product principle 3 — configurable
-- structure, opinionated defaults. The three codes seeded below are exactly the two lists that were
-- compiled into the two editors, moved into data where a tenant can rename, reorder or deprecate
-- them. Nothing in code reads a code below by name.
--
-- WHY `cfg.asset.field.manage` GOVERNS IT RATHER THAN A NEW PERMISSION. V068 separated declaring a
-- field from editing an asset because the populations differ. Declaring an environment is the same
-- decision about the same surface, administered by the same people: whoever decides that the
-- inventory asks "which host serves this in UAT" is whoever decides it asks "which CDN is in front
-- of it". A second permission that is always granted with the first is administrative overhead with
-- no separation to show for it. Its label widens here to say so.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. The catalogue.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS asset_endpoint_environment (
    id              uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid        NOT NULL,
    -- Matches `asset_relationship.attributes->>'environment'` exactly. Upper case because that is
    -- what the recorded data already uses and a catalogue that disagreed with the edges by case
    -- would offer a column that joined nothing.
    code            text        NOT NULL,
    label_i18n      jsonb       NOT NULL,
    -- The security question this environment answers, shown beside the field. An environment whose
    -- purpose nobody can state is one people record inconsistently and then filter on.
    purpose         text,
    ordinal         int         NOT NULL DEFAULT 0,
    lifecycle_state text        NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      uuid,
    row_version     int         NOT NULL DEFAULT 1,

    CONSTRAINT uq_asset_endpoint_env__code UNIQUE (tenant_id, code),
    CONSTRAINT ck_asset_endpoint_env__code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,30}$'),
    CONSTRAINT ck_asset_endpoint_env__lifecycle CHECK
        (lifecycle_state IN ('ACTIVE', 'DEPRECATED'))
);

SELECT apply_tenant_isolation('asset_endpoint_environment');

CREATE INDEX IF NOT EXISTS ix_asset_endpoint_env__active
    ON asset_endpoint_environment (tenant_id, ordinal, code)
    WHERE lifecycle_state = 'ACTIVE';
COMMENT ON INDEX ix_asset_endpoint_env__active IS
    'Serves: the environment list behind the domain inputs on the application and project editors, '
    'the domain column offering on both inventory lists, and the validation of a host filter — '
    'every load of four screens.';

COMMENT ON TABLE asset_endpoint_environment IS
    'Tenant vocabulary for the environment an endpoint is published in, matching '
    'asset_relationship.attributes->>''environment''. What the interface OFFERS; the union with the '
    'environments actually recorded is what it SHOWS, so an imported edge naming an undeclared '
    'environment is never hidden (product principle 1).';

GRANT SELECT, INSERT, UPDATE ON asset_endpoint_environment TO app_runtime;
GRANT SELECT ON asset_endpoint_environment TO integrity_verifier;

-- -----------------------------------------------------------------------------
-- 2. The defaults, and everything already recorded.
--
-- Per tenant, because this is tenant data and there is no cross-tenant statement that could write
-- it. Two sources:
--
--   a. The three codes the two editors had compiled into them, so no form input disappears in the
--      release that removes them from the code.
--   b. Every environment already present on a current PUBLISHED_ON edge, so an estate that arrived
--      by import is describable in the interface the moment this runs rather than after somebody
--      notices a column missing.
--
-- Ordinals leave gaps of ten. A tenant inserting SIT between UAT and STAGING should not have to
-- renumber the rows around it.
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    t        uuid;
    observed record;
    next_ord int;
BEGIN
    FOR t IN SELECT id FROM tenant LOOP
        PERFORM set_config('aspm.current_tenant', t::text, true);

        INSERT INTO asset_endpoint_environment
            (tenant_id, code, label_i18n, purpose, ordinal)
        VALUES
          (t, 'PRODUCTION', '{"en":"Production","vi":"Production"}'::jsonb,
           'The host real users reach. A finding here is exploitable by whoever can reach the host, '
           'which for an internet-facing application is everybody.', 10),
          (t, 'UAT', '{"en":"UAT","vi":"UAT"}'::jsonb,
           'The host acceptance testing runs against. Routinely holds a copy of production data '
           'behind weaker controls — no WAF, default credentials, debug endpoints left enabled — so '
           'it is frequently the cheapest route to the same records.', 20),
          (t, 'STAGING', '{"en":"Staging","vi":"Staging"}'::jsonb,
           'The host a release is verified on before production. Usually reachable by more people '
           'than production and watched by fewer.', 30)
        ON CONFLICT (tenant_id, code) DO NOTHING;

        -- Anything the data already carries and the three above do not. UNSPECIFIED is skipped: it
        -- is the substitute the queries use for an edge with no environment recorded at all, not a
        -- name anybody chose, and declaring it would invite somebody to select it in a form.
        next_ord := 100;
        FOR observed IN
            SELECT DISTINCT r.attributes ->> 'environment' AS code
              FROM asset_relationship r
              JOIN asset d ON d.id = r.to_asset_id
              JOIN asset_type dt ON dt.id = d.type_id AND dt.code = 'DOMAIN'
             WHERE r.valid_until IS NULL
               AND r.attributes ->> 'environment' IS NOT NULL
               AND r.attributes ->> 'environment' <> 'UNSPECIFIED'
               AND r.attributes ->> 'environment' ~ '^[A-Z][A-Z0-9_]{1,30}$'
             ORDER BY 1
        LOOP
            INSERT INTO asset_endpoint_environment (tenant_id, code, label_i18n, purpose, ordinal)
            VALUES (t, observed.code,
                    jsonb_build_object('en', observed.code, 'vi', observed.code),
                    'Declared from an environment already recorded on a published-on edge when the '
                    'catalogue was introduced. Give it a purpose somebody can act on.',
                    next_ord)
            ON CONFLICT (tenant_id, code) DO NOTHING;
            next_ord := next_ord + 10;
        END LOOP;
    END LOOP;
END
$$;

-- -----------------------------------------------------------------------------
-- 3. The permission widens to say what it governs.
--
-- The CODE does not change — an identifier in a role grant, a test and an operation registry, and
-- renaming it would silently unauthorize every role holding it. Only the label moves.
-- -----------------------------------------------------------------------------
UPDATE permission_catalogue
   SET label_i18n = '{"en":"Manage declared asset fields and endpoint environments",
                      "vi":"Quản lý trường dữ liệu khai báo và môi trường triển khai"}'::jsonb
 WHERE code = 'cfg.asset.field.manage';
