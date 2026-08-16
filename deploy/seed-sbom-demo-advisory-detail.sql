-- =============================================================================
-- seed-sbom-demo-advisory-detail.sql — the weakness classes and references for the demo advisories.
--
-- A SEED, NOT A MIGRATION, and not a backfill inside V039 either. V039 added the columns nullable and
-- deliberately did not populate them: an advisory recorded before the column existed genuinely had no
-- value, and a migration inventing one would assert something no tool ever said. That reasoning holds
-- for a REAL estate. It does not hold for demo data, where the whole point is that the interface can
-- be judged — a CWE column empty on seventeen of nineteen rows shows the layout and not the feature.
--
-- The values are the real ones for these real CVEs, from the published advisories. They are not
-- guesses, and a demo carrying wrong CWEs would be worse than one carrying none: somebody would read
-- CWE-89 against Log4Shell and lose confidence in every other number on the page.
--
-- Re-runnable. Everything is an UPDATE keyed on the advisory identifier.
-- =============================================================================

BEGIN;
SET LOCAL aspm.current_tenant = '11111111-1111-1111-1111-111111111111';

UPDATE advisory a
   SET cwe_ids         = d.cwes,
       data_source     = 'NVD',
       description     = coalesce(a.description, d.detail),
       references_urls = ARRAY['https://nvd.nist.gov/vuln/detail/' || a.advisory_key]
  FROM (VALUES
    ('CVE-2021-44228', ARRAY['CWE-917','CWE-502','CWE-20'],
     'JNDI features used in configuration, log messages and parameters do not protect against attacker controlled LDAP and other JNDI related endpoints. An attacker who can control log messages or their parameters can execute arbitrary code loaded from an LDAP server.'),
    ('CVE-2021-45046', ARRAY['CWE-917','CWE-502'],
     'The fix in 2.15.0 was incomplete in certain non-default configurations, allowing a crafted Thread Context Map input to leak into a JNDI lookup and result in information disclosure or remote code execution.'),
    ('CVE-2022-42889', ARRAY['CWE-1336','CWE-94'],
     'Variable interpolation in Apache Commons Text allows string lookups that can execute scripts or make network calls, so untrusted input reaching interpolation is remote code execution.'),
    ('CVE-2020-36518', ARRAY['CWE-787'],
     'jackson-databind allows a Java StackOverflow exception and denial of service through a large depth of nested objects.'),
    ('CVE-2022-25857', ARRAY['CWE-400','CWE-674'],
     'snakeyaml does not restrict the depth of nested collections, so a crafted document causes a stack overflow and denial of service.'),
    ('CVE-2022-22965', ARRAY['CWE-94'],
     'Spring MVC and WebFlux applications running on JDK 9 or later may be vulnerable to remote code execution through data binding, depending on how the application is deployed.'),
    ('CVE-2021-23337', ARRAY['CWE-77','CWE-94'],
     'lodash is vulnerable to command injection through the template function.'),
    ('CVE-2024-29041', ARRAY['CWE-601'],
     'Express may perform an open redirect when a malformed URL is passed to response.location or response.redirect, because the URL is not normalized before use.'),
    ('CVE-2023-45857', ARRAY['CWE-200'],
     'axios inserts the X-XSRF-TOKEN header, read from a cookie, into every request made to any host, leaking the token to third parties.'),
    ('CVE-2021-44906', ARRAY['CWE-1321'],
     'minimist is vulnerable to prototype pollution, allowing an attacker to add or modify properties of Object.prototype.'),
    ('CVE-2022-24999', ARRAY['CWE-1321'],
     'qs is vulnerable to prototype pollution, which can be used to cause a denial of service by shadowing properties on Object.prototype.'),
    ('CVE-2022-25883', ARRAY['CWE-1333'],
     'semver is vulnerable to regular expression denial of service through the range parser when given a crafted range string.'),
    ('CVE-2023-26159', ARRAY['CWE-601','CWE-20'],
     'follow-redirects handles URLs improperly, which can leak the Authorization and Cookie headers to a different host across a redirect.'),
    ('CVE-2021-33503', ARRAY['CWE-400'],
     'urllib3 is vulnerable to denial of service through a crafted URL containing many characters that require normalisation.'),
    ('CVE-2020-14343', ARRAY['CWE-20','CWE-502'],
     'pyyaml allows arbitrary code execution when processing untrusted YAML with the full loader, an incomplete fix for an earlier issue.'),
    ('CVE-2022-28346', ARRAY['CWE-89'],
     'Django QuerySet.annotate, aggregate and extra are vulnerable to SQL injection through crafted dictionary expansion of column aliases.'),
    ('CVE-2020-28483', ARRAY['CWE-345'],
     'gin-gonic trusts proxy headers such as X-Forwarded-For without validation, allowing a client to spoof its address.'),
    ('GHSA-ffhg-7mh4-33c4', ARRAY['CWE-400'],
     'The golang.org/x/crypto SSH server can be made to consume excessive resources by a client that sends a large number of channel open requests.')
  ) AS d(key, cwes, detail)
 WHERE a.advisory_key = d.key;

-- GHSA rows come from GitHub rather than NVD, so the source is corrected rather than left uniform —
-- data_source answers "who published this", and a wrong answer there is worse than none.
UPDATE advisory
   SET data_source     = 'GitHub Security Advisories',
       references_urls = ARRAY['https://github.com/advisories/' || advisory_key]
 WHERE advisory_key LIKE 'GHSA-%';

COMMIT;
