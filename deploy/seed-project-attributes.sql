-- =============================================================================
-- The declared attribute catalogue for PROJECT.
--
-- A project is the branch of an application one team delivers, and it is the level at which almost
-- every operational question is actually asked: which domain is this on, who do I call, is there a
-- WAF in front of it, does it use SSO. Those answers were nowhere — PROJECT was the only composition
-- asset type with no declared attributes at all, so the project page could show a name, an owner and
-- a finding count and nothing else.
--
-- TENANT DATA, NOT CODE (ADR-027). Every key, label and permitted value below is one INSERT in one
-- tenant. Another conglomerate deploying this platform declares a different set — different WAF
-- vendors, different user categories, a field for something nobody here tracks — without a code
-- change, a migration or a release. The product supplies the STORAGE KINDS (TEXT, SINGLE_SELECT,
-- INTEGER, …) and the editor that renders them; the tenant supplies the fields.
--
-- WHAT IS DELIBERATELY NOT HERE, because it is already first-class and a second copy would give two
-- answers to one question (product principle 10):
--
--   mức độ critical    asset.criticality_tier_id, inheritable from the owning node
--   owner (người)      asset.technical_contact_id -> the platform's user directory
--   prod / uat domain  DOMAIN assets joined by a PUBLISHED_ON edge carrying the environment
--   git repo + branch  a REPOSITORY asset joined by a BUILDS edge carrying the branch
--   exposure           asset.exposure_declared, which the DOC-28 risk model scores on
--
-- The last three are modelled as related assets rather than as text on the project because they are
-- SHARED: two projects deployed on one host are two edges to one DOMAIN, and an advisory reaching
-- that host reaches both. Flattened into a string column, that reachability is invisible.
--
-- `access_path` sits beside `exposure_declared` rather than inside it. Exposure is a product-fixed,
-- risk-scored taxonomy (DOC-03 §713, DOC-28 §199) and adding a value to it changes every score;
-- "reached through ZTNA" is an access-control fact the tenant tracks and the model does not weigh.
-- =============================================================================

\set ON_ERROR_STOP on

-- WHICH TENANT. Defaults to the demo tenant so every existing flow behaves as before;
-- seed-bootstrap.sql passes a real one. Workflow states, assessment triggers and declared fields are
-- TENANT DATA (ADR-027) — a hardcoded id put them in a tenant a real deployment does not serve, and
-- the symptom is not an error: it is a platform where no transition is defined, no review obligation
-- exists and no field is offered.
\if :{?tenant_id}
\else
  \set tenant_id '11111111-1111-1111-1111-111111111111'
\endif


-- psql does NOT substitute :variables inside a dollar-quoted block, so they are carried in as
-- session settings the block reads at run time. Substituting them textually would also mean a value
-- containing a quote became SQL, which is the injection this avoids by construction.
SELECT set_config('aspm.seed_tenant', :'tenant_id', false);

DO $seed$
DECLARE
    t       uuid := current_setting('aspm.seed_tenant')::uuid;
    at_proj uuid;
BEGIN
    PERFORM set_config('aspm.current_tenant', t::text, true);

    SELECT id INTO at_proj FROM asset_type WHERE tenant_id = t AND code = 'PROJECT';
    IF at_proj IS NULL THEN
        RAISE EXCEPTION 'the PROJECT asset type does not exist in tenant %', t;
    END IF;

    INSERT INTO asset_attribute_definition
        (tenant_id, asset_type_id, attribute_key, label_i18n, data_type, permitted_values,
         filterable, required, purpose, ordinal)
    VALUES
      (t, at_proj, 'description', '{"en":"Description","vi":"Mô tả"}', 'LONG_TEXT', '{}',
       false, false,
       'What this project delivers, in one sentence, for somebody triaging an advisory at 2am who '
       'has never heard of it.', 1),

      (t, at_proj, 'delivery_team', '{"en":"Delivery team","vi":"Đội phát triển"}', 'TEXT', '{}',
       false, false,
       'The team accountable for shipping the fix. Distinct from the named contact on the record: a '
       'team survives the person leaving, and a person answers the phone.', 2),

      -- Who is harmed, not how many. The categories are separable because they fail differently: an
      -- administrative console breached is lateral movement, an end-customer app breached is a
      -- notification obligation, and an internal tool breached is neither.
      (t, at_proj, 'user_base', '{"en":"Who uses it","vi":"Đối tượng sử dụng"}', 'MULTI_SELECT',
       ARRAY['END_CUSTOMER','INTERNAL_STAFF','ADMINISTRATOR','PARTNER','ANONYMOUS_PUBLIC',
             'SYSTEM_TO_SYSTEM'],
       true, false,
       'Who is harmed if this is breached, and which obligations follow. An administrative audience '
       'and a customer audience produce completely different incident responses at equal severity.',
       3),

      (t, at_proj, 'tech_stack', '{"en":"Tech stack","vi":"Công nghệ sử dụng"}', 'MULTI_SELECT',
       ARRAY['JAVA','KOTLIN','NODEJS','PYTHON','GO','DOTNET','PHP','RUBY','RUST','REACT','ANGULAR',
             'VUE','SPRING','DJANGO','EXPRESS','LARAVEL','NEXTJS','FLUTTER','SWIFT','ANDROID_KOTLIN'],
       true, false,
       'Which projects an ecosystem-wide advisory actually reaches. When a Spring or a Log4j lands, '
       'this is the difference between a targeted list and asking every team to check.', 4),

      -- Not a duplicate of exposure_declared. Exposure says how far the reachability extends;
      -- this says what stands in the path. INTERNAL_ONLY behind nothing and INTERNAL_ONLY behind a
      -- ZTNA broker are the same exposure and very different attack paths.
      (t, at_proj, 'access_path', '{"en":"How it is reached","vi":"Cách truy cập"}', 'SINGLE_SELECT',
       ARRAY['DIRECT_INTERNET','ZTNA','VPN','INTERNAL_NETWORK','PARTNER_LINK'],
       true, false,
       'What an attacker must already have before the application is even reachable. A ZTNA broker '
       'in front makes an unauthenticated flaw behind it a much longer path than the same flaw on a '
       'public host.', 5),

      (t, at_proj, 'architecture_url', '{"en":"Architecture document","vi":"Link kiến trúc"}', 'URL',
       '{}', false, false,
       'Where the design lives, so a reviewer scoping an assessment does not start by asking for it. '
       'A link, never a copy — the platform does not become a second document store nobody updates.',
       6),

      (t, at_proj, 'api_count', '{"en":"API endpoints exposed","vi":"Số lượng API"}', 'INTEGER',
       '{}', false, false,
       'Rough attack surface, and the sizing input for an assessment: a project with four endpoints '
       'and one with four hundred are not the same week of work.', 7),

      -- MFA and SSO together, because the question people ask is "can somebody get in with a
      -- stolen password", and either control answers it. Separate fields would let a project claim
      -- SSO with no second factor and read as protected.
      (t, at_proj, 'authentication_controls', '{"en":"Sign-in controls","vi":"MFA / SSO"}',
       'MULTI_SELECT',
       ARRAY['SSO_OIDC','SSO_SAML','MFA_TOTP','MFA_PUSH','MFA_HARDWARE','PASSWORD_ONLY','NONE'],
       true, false,
       'Whether a stolen password is sufficient to get in. Credential stuffing is the highest-volume '
       'attack against everything in this inventory, and this field is what makes "which of our '
       'customer-facing projects still accept a password alone" answerable.', 8),

      (t, at_proj, 'cdn_edge', '{"en":"CDN / edge","vi":"CDN / Edge"}', 'SINGLE_SELECT',
       ARRAY['NONE','CLOUDFLARE','AKAMAI','AWS_CLOUDFRONT','AZURE_FRONT_DOOR','FASTLY','OTHER'],
       true, false,
       'Who terminates TLS and sees the traffic first. It decides where a virtual patch can be '
       'applied within the hour, and who has to be told during an incident.', 9),

      (t, at_proj, 'waf', '{"en":"WAF","vi":"WAF"}', 'SINGLE_SELECT',
       ARRAY['NONE','CLOUDFLARE','AWS_WAF','AZURE_WAF','F5','IMPERVA','MODSECURITY','OTHER'],
       true, false,
       'Whether a mitigation exists while the real fix is built. It never closes a finding — a WAF '
       'rule is a delay, not a remediation — but it changes how long the delay may safely be.', 10),

      (t, at_proj, 'abuse_controls', '{"en":"Abuse controls","vi":"Captcha / giới hạn tần suất"}',
       'MULTI_SELECT', ARRAY['CAPTCHA','RATE_LIMIT','BOT_MANAGEMENT','NONE'],
       true, false,
       'Whether an authentication or enumeration flaw here can be exercised at scale. The same flaw '
       'is a nuisance behind a rate limit and a breach without one.', 11)

    ON CONFLICT (tenant_id, asset_type_id, attribute_key) DO NOTHING;

    RAISE NOTICE 'PROJECT declared attributes: % defined',
        (SELECT count(*) FROM asset_attribute_definition
          WHERE tenant_id = t AND asset_type_id = at_proj);
END
$seed$;
