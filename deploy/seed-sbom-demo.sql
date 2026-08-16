-- =============================================================================
-- seed-sbom-demo.sql — a software composition estate with a story, not a fixture.
--
-- A SEED AND NEVER A MIGRATION. A deployment that has no repositories must not receive these rows.
-- Migrations run on every start; this is applied by hand, once, against a demo database.
--
-- WHAT IT BUILDS, AND WHY THAT SHAPE. The tree the dependency module is built around is
-- APPLICATION -> PROJECT -> REPOSITORY, and the SBOM is submitted against the repository, because
-- that is the thing a pipeline builds. Before this, the estate had exactly one REPOSITORY asset and
-- three SBOM snapshots, so every rollup in V036 was arithmetic over nothing and no chart could be
-- judged — only admired.
--
-- The story it tells, so the dashboard says something rather than merely rendering:
--
--   * log4j-core 2.14.1 is in four repositories across three applications, and one of them upgraded
--     away from it in March. That is what a CVE search is for, and what a partial remediation looks
--     like.
--   * jackson-databind appears at three different versions, because three teams upgrade on three
--     schedules. A component search that reported one row would be hiding the problem.
--   * Every vulnerable component that has a fix records the fixed version; three deliberately do
--     not, because that is the real state of a good deal of advisory data and the interface has to
--     say so rather than render a blank cell.
--   * Snapshots are spread across twelve months so the timeline has a shape. Two repositories stop
--     submitting halfway through — a pipeline that was switched off is the most common way SBOM
--     coverage decays, and it is invisible on any chart that only counts what arrived.
--
-- ONE-SHOT. Re-running it inserts a second generation of repositories. To start over, drop the
-- volume and re-migrate, as with seed-workload-demo.sql.
-- =============================================================================

BEGIN;
SET LOCAL aspm.current_tenant = '11111111-1111-1111-1111-111111111111';

-- -----------------------------------------------------------------------------
-- 1. A REPOSITORY asset type, if the tenant has not registered one.
--
-- Tenant data (ADR-027), so it is created rather than assumed — and `may_carry_findings` is true
-- because a repository is where a vulnerable dependency lives.
-- -----------------------------------------------------------------------------
INSERT INTO asset_type (tenant_id, code, label_i18n, ordinal, identity_rule, is_network_reachable,
                        may_carry_findings, lifecycle_state)
SELECT current_tenant_id(), 'REPOSITORY', '{"en":"Repository"}'::jsonb, 60,
       '{"kind":"REPOSITORY_URL"}'::jsonb, false, true, 'ACTIVE'
 WHERE NOT EXISTS (SELECT 1 FROM asset_type WHERE code = 'REPOSITORY');

-- -----------------------------------------------------------------------------
-- 2. Repositories, one or two per project, owned by the team that owns the project.
--
-- `owning_node_id` copied from the project rather than left null: an unowned asset is invisible to
-- every scoped query, and a demo estate that half-disappears under a scoped principal teaches the
-- wrong thing about the product.
-- -----------------------------------------------------------------------------
-- The INSERT is a data-modifying CTE and the temp table is SELECTed from its RETURNING. A plain
-- CREATE TABLE AS INSERT is not valid SQL, and the rows are needed twice below.
CREATE TEMP TABLE seeded_repo ON COMMIT DROP AS
WITH project AS (
    SELECT a.id, a.display_name, a.owning_node_id,
           row_number() OVER (ORDER BY a.display_name) AS n
      FROM asset a JOIN asset_type t ON t.id = a.type_id
     WHERE t.code = 'PROJECT'
),
spec AS (
    SELECT p.id AS project_id, p.owning_node_id, p.n,
           lower(regexp_replace(p.display_name, '[^a-zA-Z0-9]+', '-', 'g')) || suffix AS slug,
           suffix
      FROM project p
      CROSS JOIN LATERAL (VALUES ('-api'), ('-web')) AS s(suffix)
     -- Two repositories for the first six projects, one for the rest. A uniform estate is a fixture;
     -- an uneven one is what a rollup has to survive.
     WHERE suffix = '-api' OR p.n <= 6
),
inserted AS (
    -- discovery_source is SBOM_SUBMISSION, which is the truth: in a real deployment a repository
    -- asset comes into existence because a pipeline pushed a bill of materials naming it
    -- (PRD-API-039 creates an unknown artifact unclaimed rather than rejecting it).
    INSERT INTO asset (tenant_id, type_id, display_name, identity_key, identity_rule_version,
                       discovery_source, discovery_method, first_seen_at, last_confirmed_at,
                       lifecycle_state, owning_node_id, exposure_declared, attributes)
    SELECT current_tenant_id(),
           (SELECT id FROM asset_type WHERE code = 'REPOSITORY'),
           spec.slug,
           'repo:' || spec.slug,
           1,
           'SBOM_SUBMISSION', 'demo-seed',
           now() - interval '330 days', now(),
           'ACTIVE',
           spec.owning_node_id,
           'INTERNAL_ONLY',
           jsonb_build_object('description', 'Source repository for ' || spec.slug,
                              'default_branch', 'main')
      FROM spec
    RETURNING id, display_name, identity_key, owning_node_id
)
SELECT * FROM inserted;

-- The CONTAINS edge that puts each repository under its project. asset_composition walks these, so
-- without them a repository is an orphan that no rollup reaches.
INSERT INTO asset_relationship (tenant_id, from_asset_id, to_asset_id, edge_type,
                                discovery_source, valid_from, attributes)
SELECT current_tenant_id(), p.id, r.id, 'CONTAINS', 'SBOM_SUBMISSION',
       now() - interval '330 days', '{}'::jsonb
  FROM seeded_repo r
  JOIN asset p ON p.display_name =
       initcap(replace(regexp_replace(r.display_name, '-(api|web)$', ''), '-', ' '))
  JOIN asset_type pt ON pt.id = p.type_id AND pt.code = 'PROJECT'
 ON CONFLICT DO NOTHING;

-- The join above matches on a name round-trip and will miss a project whose name does not survive
-- slugging. Anything unattached is attached to a project of the same owning node instead, so no
-- repository is left outside the tree — an orphan here would silently shrink every rollup.
INSERT INTO asset_relationship (tenant_id, from_asset_id, to_asset_id, edge_type,
                                discovery_source, valid_from, attributes)
SELECT current_tenant_id(), p.id, r.id, 'CONTAINS', 'SBOM_SUBMISSION',
       now() - interval '330 days', '{}'::jsonb
  FROM seeded_repo r
  JOIN LATERAL (SELECT p.id FROM asset p JOIN asset_type pt ON pt.id = p.type_id
                 WHERE pt.code = 'PROJECT' AND p.owning_node_id = r.owning_node_id
                 ORDER BY p.display_name LIMIT 1) p ON true
 WHERE NOT EXISTS (SELECT 1 FROM asset_relationship e
                    WHERE e.to_asset_id = r.id AND e.edge_type = 'CONTAINS')
 ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3. The component catalogue. Real packages at real versions, because a made-up package name is a
--    search nobody can sanity-check against the outside world.
-- -----------------------------------------------------------------------------
INSERT INTO component (tenant_id, purl_canonical, purl_original, canonicalization_version,
                       ecosystem, name, version, is_canonicalizable)
SELECT current_tenant_id(), purl, purl, 1, eco, nm, ver, true
  FROM (VALUES
    ('pkg:maven/org.apache.logging.log4j.log4j-core@2.14.1','maven','org.apache.logging.log4j.log4j-core','2.14.1'),
    ('pkg:maven/org.apache.logging.log4j.log4j-core@2.17.1','maven','org.apache.logging.log4j.log4j-core','2.17.1'),
    ('pkg:maven/com.fasterxml.jackson.core.jackson-databind@2.9.10','maven','com.fasterxml.jackson.core.jackson-databind','2.9.10'),
    ('pkg:maven/com.fasterxml.jackson.core.jackson-databind@2.13.2','maven','com.fasterxml.jackson.core.jackson-databind','2.13.2'),
    ('pkg:maven/com.fasterxml.jackson.core.jackson-databind@2.15.3','maven','com.fasterxml.jackson.core.jackson-databind','2.15.3'),
    ('pkg:maven/org.springframework.spring-web@5.3.18','maven','org.springframework.spring-web','5.3.18'),
    ('pkg:maven/org.springframework.spring-web@5.3.31','maven','org.springframework.spring-web','5.3.31'),
    ('pkg:maven/org.apache.commons.commons-text@1.9','maven','org.apache.commons.commons-text','1.9'),
    ('pkg:maven/org.yaml.snakeyaml@1.30','maven','org.yaml.snakeyaml','1.30'),
    ('pkg:maven/com.google.guava.guava@31.1-jre','maven','com.google.guava.guava','31.1-jre'),
    ('pkg:npm/lodash@4.17.19','npm','lodash','4.17.19'),
    ('pkg:npm/lodash@4.17.21','npm','lodash','4.17.21'),
    ('pkg:npm/express@4.17.1','npm','express','4.17.1'),
    ('pkg:npm/express@4.19.2','npm','express','4.19.2'),
    ('pkg:npm/axios@0.21.1','npm','axios','0.21.1'),
    ('pkg:npm/minimist@1.2.5','npm','minimist','1.2.5'),
    ('pkg:npm/qs@6.5.2','npm','qs','6.5.2'),
    ('pkg:npm/semver@7.3.5','npm','semver','7.3.5'),
    ('pkg:npm/react@18.2.0','npm','react','18.2.0'),
    ('pkg:npm/follow-redirects@1.14.7','npm','follow-redirects','1.14.7'),
    ('pkg:pypi/requests@2.25.1','pypi','requests','2.25.1'),
    ('pkg:pypi/urllib3@1.26.5','pypi','urllib3','1.26.5'),
    ('pkg:pypi/pyyaml@5.3.1','pypi','pyyaml','5.3.1'),
    ('pkg:pypi/django@3.2.12','pypi','django','3.2.12'),
    ('pkg:golang/github.com-gin-gonic-gin@1.7.7','golang','github.com-gin-gonic-gin','1.7.7'),
    ('pkg:golang/golang.org-x-crypto@0.0.0-20210513164829','golang','golang.org-x-crypto','0.0.0-20210513164829')
  ) AS c(purl, eco, nm, ver)
 ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 4. Advisories. Real identifiers, real severities, and three with no fix available.
-- -----------------------------------------------------------------------------
INSERT INTO advisory (tenant_id, advisory_key, source, severity_id, cvss_score, summary,
                      published_at, first_recorded_at)
SELECT current_tenant_id(), key, 'NVD',
       (SELECT id FROM severity_level WHERE code = sev),
       score, summary,
       published::timestamptz,
       -- First recorded when the estate first submitted an SBOM carrying it, staggered across the
       -- year so "new this month" has something to report other than a single spike.
       now() - make_interval(days => recorded_days_ago)
  FROM (VALUES
    ('CVE-2021-44228','CRITICAL',10.0,'Log4Shell: remote code execution through JNDI lookup in log4j-core','2021-12-10',330),
    ('CVE-2021-45046','CRITICAL',9.0,'Incomplete fix for CVE-2021-44228 allows denial of service and RCE','2021-12-14',330),
    ('CVE-2022-42889','CRITICAL',9.8,'Text4Shell: variable interpolation in commons-text executes scripts','2022-10-13',300),
    ('CVE-2020-36518','HIGH',7.5,'jackson-databind deeply nested objects cause denial of service','2022-03-11',285),
    ('CVE-2022-25857','HIGH',7.5,'snakeyaml denial of service through stack overflow on nested collections','2022-08-30',240),
    ('CVE-2022-22965','CRITICAL',9.8,'Spring4Shell: data binding leads to remote code execution','2022-03-31',210),
    ('CVE-2021-23337','HIGH',7.2,'lodash command injection through template','2021-02-15',195),
    ('CVE-2024-29041','MEDIUM',6.1,'express open redirect through malformed URLs in response.location','2024-03-25',150),
    ('CVE-2023-45857','MEDIUM',5.3,'axios leaks the X-XSRF-TOKEN header to third-party hosts','2023-11-08',120),
    ('CVE-2021-44906','CRITICAL',9.8,'minimist prototype pollution','2022-03-17',110),
    ('CVE-2022-24999','HIGH',7.5,'qs prototype pollution leading to denial of service','2022-11-26',95),
    ('CVE-2022-25883','MEDIUM',5.3,'semver regular expression denial of service in range parsing','2023-06-21',80),
    ('CVE-2023-26159','HIGH',7.3,'follow-redirects improper handling of URLs leaks credentials','2024-01-02',70),
    ('CVE-2021-33503','HIGH',7.5,'urllib3 denial of service through a crafted URL','2021-06-29',60),
    ('CVE-2020-14343','CRITICAL',9.8,'pyyaml arbitrary code execution through the full loader','2021-02-09',55),
    ('CVE-2022-28346','CRITICAL',9.8,'Django SQL injection through QuerySet.annotate with crafted names','2022-04-11',40),
    ('CVE-2020-28483','HIGH',7.1,'gin-gonic header injection through untrusted proxy headers','2021-01-05',30),
    ('GHSA-ffhg-7mh4-33c4','MEDIUM',6.5,'golang.org/x/crypto SSH server denial of service','2021-11-02',20)
  ) AS a(key, sev, score, summary, published, recorded_days_ago)
 ON CONFLICT (tenant_id, advisory_key) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 5. Which component each advisory affects, and which have since been fixed.
--
-- `fixed_version` is deliberately absent for three of them. An advisory with no stated fix is the
-- one an engineer needs to be told about explicitly, and a dashboard that renders every row the same
-- hides exactly the rows that need a decision rather than an upgrade.
-- -----------------------------------------------------------------------------
INSERT INTO component_advisory (tenant_id, component_id, advisory_id, detected_at, fixed_version,
                                source_tool)
SELECT current_tenant_id(), c.id, a.id,
       greatest(a.first_recorded_at, now() - make_interval(days => 340)),
       m.fixed, 'trivy'
  FROM (VALUES
    ('pkg:maven/org.apache.logging.log4j.log4j-core@2.14.1','CVE-2021-44228','2.17.1'),
    ('pkg:maven/org.apache.logging.log4j.log4j-core@2.14.1','CVE-2021-45046','2.17.1'),
    ('pkg:maven/com.fasterxml.jackson.core.jackson-databind@2.9.10','CVE-2020-36518','2.13.2'),
    ('pkg:maven/com.fasterxml.jackson.core.jackson-databind@2.13.2','CVE-2020-36518',NULL),
    ('pkg:maven/org.springframework.spring-web@5.3.18','CVE-2022-22965','5.3.31'),
    ('pkg:maven/org.apache.commons.commons-text@1.9','CVE-2022-42889','1.10.0'),
    ('pkg:maven/org.yaml.snakeyaml@1.30','CVE-2022-25857','1.31'),
    ('pkg:npm/lodash@4.17.19','CVE-2021-23337','4.17.21'),
    ('pkg:npm/express@4.17.1','CVE-2024-29041','4.19.2'),
    ('pkg:npm/axios@0.21.1','CVE-2023-45857','1.6.0'),
    ('pkg:npm/minimist@1.2.5','CVE-2021-44906','1.2.6'),
    ('pkg:npm/qs@6.5.2','CVE-2022-24999','6.5.3'),
    ('pkg:npm/semver@7.3.5','CVE-2022-25883','7.5.2'),
    ('pkg:npm/follow-redirects@1.14.7','CVE-2023-26159',NULL),
    ('pkg:pypi/urllib3@1.26.5','CVE-2021-33503','1.26.5'),
    ('pkg:pypi/pyyaml@5.3.1','CVE-2020-14343','5.4'),
    ('pkg:pypi/django@3.2.12','CVE-2022-28346','3.2.13'),
    ('pkg:golang/github.com-gin-gonic-gin@1.7.7','CVE-2020-28483',NULL),
    ('pkg:golang/golang.org-x-crypto@0.0.0-20210513164829','GHSA-ffhg-7mh4-33c4','0.0.0-20211202192323')
  ) AS m(purl, key, fixed)
  JOIN component c ON c.purl_canonical = m.purl
  JOIN advisory a ON a.advisory_key = m.key
 ON CONFLICT DO NOTHING;

-- The upgrades that happened. These are the "CVEs closed" series: the component stopped being
-- affected because somebody moved off it, which is a different fact from a work item being closed.
UPDATE component_advisory ca
   SET resolved_at = now() - make_interval(days => d.days_ago),
       resolution  = 'COMPONENT_UPGRADED'
  FROM (VALUES
    ('pkg:npm/lodash@4.17.19','CVE-2021-23337',200),
    ('pkg:pypi/urllib3@1.26.5','CVE-2021-33503',150),
    ('pkg:npm/qs@6.5.2','CVE-2022-24999',95),
    ('pkg:maven/org.yaml.snakeyaml@1.30','CVE-2022-25857',60),
    ('pkg:npm/semver@7.3.5','CVE-2022-25883',35),
    ('pkg:golang/golang.org-x-crypto@0.0.0-20210513164829','GHSA-ffhg-7mh4-33c4',15)
  ) AS d(purl, key, days_ago)
  JOIN component c ON c.purl_canonical = d.purl
  JOIN advisory a ON a.advisory_key = d.key
 WHERE ca.component_id = c.id AND ca.advisory_id = a.id;

-- -----------------------------------------------------------------------------
-- 6. Snapshots, and what is in them.
--
-- The component set is decided BEFORE the snapshot row is written, because an accepted snapshot is
-- immutable: INV-SBM-01 refuses any UPDATE, on the grounds that a snapshot's identity IS its content
-- hash and a changed one is a different one. So `component_count` and `ecosystems` have to be
-- correct at insert. Trying to insert a placeholder and correct it afterwards is exactly what the
-- invariant exists to stop, and it stopped it.
--
-- The membership predicate depends on the REPOSITORY and the package, not on the snapshot, so the
-- same repository keeps the same stack across its generations — an upgrade shows as a version
-- change rather than as the whole dependency list churning.
--
-- Three generations per repository across the year, except every seventh, whose pipeline stops after
-- the first: a switched-off pipeline is the most common way SBOM coverage decays, and it is
-- invisible on any chart that only counts what arrived.
-- -----------------------------------------------------------------------------
CREATE TEMP TABLE seeded_pick ON COMMIT DROP AS
SELECT r.id AS asset_id, c.id AS component_id, c.ecosystem, c.purl_canonical,
       CASE WHEN abs(hashtext(r.id::text || c.purl_canonical || 'rel')) % 3 = 0 THEN 1 ELSE 2 END
           AS relationship
  FROM seeded_repo r
  JOIN component c ON true
 WHERE abs(hashtext(r.id::text || c.purl_canonical)) % 100 < 45;

CREATE TEMP TABLE seeded_snapshot ON COMMIT DROP AS
WITH repo AS (
    SELECT r.id, r.display_name, row_number() OVER (ORDER BY r.display_name) AS n FROM seeded_repo r
),
gen AS (
    SELECT repo.id AS asset_id, repo.n, g AS generation,
           now() - make_interval(days => (330 - g * 110)) AS created_at
      FROM repo CROSS JOIN generate_series(0, 2) AS g
     WHERE NOT (repo.n % 7 = 0 AND g > 0)
),
sized AS (
    SELECT gen.*, p.total, p.ecosystems
      FROM gen
      JOIN LATERAL (SELECT count(*) AS total,
                           array_agg(DISTINCT sp.ecosystem) AS ecosystems
                      FROM seeded_pick sp WHERE sp.asset_id = gen.asset_id) p ON true
     WHERE p.total > 0
),
inserted AS (
    -- The scope columns are denormalized at write time, exactly as the ingestion path does it:
    -- SEC-AUZ-016 wants the scope predicate IN the retrieval, and a scoped read of snapshots cannot
    -- join up the org tree per row.
    INSERT INTO sbom_snapshot (tenant_id, artifact_asset_id, content_hash, format, format_version,
                               revision_reference, source, submitted_by_principal_id,
                               component_count, quality_score, ecosystems, created_at,
                               scope_node_id, scope_ancestor_path, scope_node_type_id,
                               scope_criticality_id, scope_hierarchy_ver, scope_resolved_at)
    SELECT current_tenant_id(), sized.asset_id,
           sha256(convert_to('demo-sbom-' || sized.asset_id::text || '-' || sized.generation, 'UTF8')),
           'CYCLONEDX', '1.5',
           'refs/heads/main@' || substr(md5(sized.asset_id::text || sized.generation), 1, 12),
           'API_PUSH',
           '70000000-0000-4000-8000-00000000000a',
           sized.total, 70 + (sized.n * 7 + sized.generation * 5) % 30,
           sized.ecosystems,
           sized.created_at,
           ra.owning_node_id,
           coalesce((SELECT array_agg(cl.ancestor_id ORDER BY cl.depth DESC)
                       FROM org_closure cl WHERE cl.descendant_id = ra.owning_node_id),
                    ARRAY[]::uuid[]),
           n.type_id,
           coalesce(n.criticality_tier_id,
                    (SELECT id FROM criticality_tier ORDER BY ordinal DESC LIMIT 1)),
           1, now()
      FROM sized
      JOIN asset ra ON ra.id = sized.asset_id
      JOIN org_node n ON n.id = ra.owning_node_id
    RETURNING id, artifact_asset_id, created_at
)
SELECT * FROM inserted;

-- -----------------------------------------------------------------------------
-- 7. The entries, from the same pick the counts were computed from — so the stored component_count
--    and the rows actually present cannot disagree.
-- -----------------------------------------------------------------------------
INSERT INTO component_entry (tenant_id, snapshot_id, component_id, relationship, license_refs)
SELECT current_tenant_id(), s.id, p.component_id, p.relationship, ARRAY['Apache-2.0']
  FROM seeded_snapshot s
  JOIN seeded_pick p ON p.asset_id = s.artifact_asset_id
 ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 8. The dependency graph. Every transitive component is attached to a direct one of the same
--    ecosystem, which is what makes "what pulled this in" answerable — the question that turns a
--    vulnerable transitive dependency into an upgrade somebody can actually perform.
-- -----------------------------------------------------------------------------
INSERT INTO component_dependency (tenant_id, snapshot_id, parent_component_id, child_component_id)
SELECT current_tenant_id(), t.snapshot_id, t.parent_id, t.child_id
  FROM (
    SELECT e.snapshot_id,
           (SELECT d.component_id FROM component_entry d
              JOIN component dc ON dc.id = d.component_id
             WHERE d.snapshot_id = e.snapshot_id AND d.relationship = 1
               AND dc.ecosystem = c.ecosystem
             ORDER BY abs(hashtext(d.component_id::text || e.component_id::text)) LIMIT 1) AS parent_id,
           e.component_id AS child_id
      FROM component_entry e
      JOIN component c ON c.id = e.component_id
     WHERE e.relationship = 2
       AND e.snapshot_id IN (SELECT id FROM seeded_snapshot)
  ) t
 WHERE t.parent_id IS NOT NULL AND t.parent_id <> t.child_id
 ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 9. Coverage state points at the LATEST snapshot per repository. Everything the module reports as
--    "what we run" comes from here, so a repository whose pipeline stopped keeps its old snapshot
--    and shows as stale rather than disappearing.
-- -----------------------------------------------------------------------------
-- accountable_owner_id is NOT NULL by design: coverage without somebody accountable for it is a
-- number nobody is answerable for. The repository's owning org node is that party.
INSERT INTO sbom_coverage_state (tenant_id, asset_id, latest_snapshot_id, latest_snapshot_at,
                                 quality, covered_ecosystems, declared_stack_ecosystems,
                                 freshness_threshold_days, accountable_owner_id)
SELECT current_tenant_id(), latest.artifact_asset_id, latest.id, latest.created_at,
       CASE WHEN latest.created_at > now() - interval '90 days' THEN 'ABOVE_WARNING'
            ELSE 'AT_OR_BELOW_WARNING' END,
       coalesce((SELECT array_agg(DISTINCT c.ecosystem) FROM component_entry e
                   JOIN component c ON c.id = e.component_id
                  WHERE e.snapshot_id = latest.id), ARRAY[]::text[]),
       ARRAY['maven','npm','pypi'],
       30,
       ra.owning_node_id
  FROM (SELECT DISTINCT ON (artifact_asset_id) id, artifact_asset_id, created_at
          FROM seeded_snapshot ORDER BY artifact_asset_id, created_at DESC) latest
  JOIN asset ra ON ra.id = latest.artifact_asset_id
 ON CONFLICT (tenant_id, asset_id) DO UPDATE
    SET latest_snapshot_id = EXCLUDED.latest_snapshot_id,
        latest_snapshot_at = EXCLUDED.latest_snapshot_at,
        quality            = EXCLUDED.quality,
        covered_ecosystems = EXCLUDED.covered_ecosystems,
        updated_at         = now();

COMMIT;
