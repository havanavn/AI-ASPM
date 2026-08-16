---
document_id:    DOC-04
title:          Database Design
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        1.0.0
status:         In review
owner:          Chief Software Architect
authors:        [Chief Software Architect, Principal Application Security Engineer]
reviewers:      []
last_updated:   2026-08-04
tier:           3
prerequisites:  [DOC-00, DOC-01, DOC-02, DOC-03, DOC-24]
depends_on:     [DOC-00, DOC-01, DOC-02, DOC-03, DOC-07, DOC-24, DOC-28]
supersedes:     null
adrs_relied_on: [ADR-002, ADR-003, ADR-009, ADR-010, ADR-011, ADR-027]
open_questions: [OQ-015]
requirement_domains: [DAT]
security_review_required: true
---

# 04 — Database Design

> **Authoring status.** Delivered in three parts, per DOC-00 §19.3.
> **Part 1 (delivered):** §1–§11 — design principles, common patterns, key strategy, tenant enforcement, indexing and partitioning methodology, and the Tenancy, Organization, and Asset schemas.
> **Part 2 (delivered):** §12–§15 — Assessment, Vulnerability Management, Exception, and Composition Analysis schemas.
> **Part 3 (delivered):** §16–§24 — Work Management, Capacity, Risk, Ingestion, Audit, and generic schemas; read models; consolidated tables; migration; retention and erasure.
> **Document complete.** 75 tables, 36 requirements. No content was abbreviated to fit a delivery.

---

## Table of Contents

**Part 1 — delivered**
1. [Purpose and Scope](#1-purpose-and-scope)
2. [Prerequisites and Local Conventions](#2-prerequisites-and-local-conventions)
3. [Sizing Basis](#3-sizing-basis)
4. [Physical Design Principles](#4-physical-design-principles)
5. [Key Strategy](#5-key-strategy)
6. [Common Column Patterns](#6-common-column-patterns)
7. [Tenant Enforcement](#7-tenant-enforcement)
8. [Taxonomies and Typed Attributes](#8-taxonomies-and-typed-attributes)
9. [Indexing Methodology](#9-indexing-methodology)
10. [Partitioning Methodology](#10-partitioning-methodology)
11. [Schema — Tenancy, Organization, Asset](#11-schema--tenancy-organization-asset)

**Part 2 — delivered**
12. [Schema — Assessment](#12-schema--assessment) · 13. [Vulnerability Management](#13-schema--vulnerability-management) · 14. [Exceptions](#14-schema--exceptions) · 15. [Composition Analysis](#15-schema--composition-analysis)

**Part 3 — delivered**
16. [Work Management](#16-schema--work-management) · 17. [Capacity](#17-schema--capacity) · 18. [Risk and Service Levels](#18-schema--risk-and-service-levels) · 19. [Ingestion](#19-schema--ingestion) · 20. [Audit and Generic](#20-schema--audit-and-generic-modules) · 21. [Read Models](#21-read-models) · 22. [Consolidated Tables](#22-consolidated-tables) · 23. [Migration](#23-migration-strategy) · 24. [Retention, Erasure, Closing](#24-retention-erasure-and-closing)

---

## 1. Purpose and Scope

### 1.1 Purpose

This document specifies the physical data model: every table, its columns and types, its constraints, its relationships, **its indexing strategy with the query each index serves**, its partitioning where applicable, and its retention.

The indexing requirement is stated first because DOC-00 §18.2 makes it a condition of document completeness. An index without a named query is an index nobody can evaluate: it cannot be removed safely because its purpose is unknown, and it cannot be confirmed sufficient because the query it was meant to serve was never written down.

### 1.2 In scope

Design principles derived from the architecture and domain model; key and identifier strategy; the common column patterns applied throughout; row-level tenant enforcement; taxonomy and typed-attribute storage realizing ADR-027; indexing and partitioning methodology; the complete physical schema; read model schemas; migration strategy; retention and erasure.

### 1.3 Out of scope

| Excluded | Owned by |
|---|---|
| Domain semantics and invariants | DOC-03 |
| Module boundaries and transaction rules | DOC-02 |
| Isolation policy intent | DOC-24 |
| Authorization semantics | DOC-07 |
| Query implementation and ORM choices | Implementation |
| Infrastructure, backup mechanics, replication topology | DOC-15 |

### 1.4 The engine assumption

DOC-02 §16 defers the operational store selection but states its required properties: row-level security enforced by the engine, transactional guarantees, declarative partitioning, and typed document storage for attribute schemas.

This document is written against **a relational engine providing all four**. Where a construct depends on an engine capability rather than on general relational features, it is marked ⚙ so that a substitution can assess the gap. This is not a covert technology decision: it is the honest consequence of `SEC-TEN-001`, which cannot be satisfied by an engine without row-level enforcement, and of ADR-027, which cannot be satisfied without typed document storage or an unacceptable proliferation of tables.

---

## 2. Prerequisites and Local Conventions

| Document | Why |
|---|---|
| DOC-00 | §10.2 database naming conventions — binding on every identifier here |
| DOC-01 | §12.1 reference profiles; `CON-DAT-001` – `003` |
| DOC-02 | `CON-PLT-015` no cross-module table access; `CON-PLT-019` one aggregate per transaction; `CON-PLT-028` materialized coverage; D10 volume driver |
| DOC-03 | 32 aggregates and 106 invariants; every invariant needs a constraint or a documented reason it is enforced only in the domain layer |
| DOC-24 | `SEC-TEN-001` – `011`; the leakage surface inventory |

**LC-01.** Requirements are issued as `CON-DAT-nnn`, continuing DOC-01's sequence which ended at `CON-DAT-003`.

**LC-02 — Constraint traceability.** Every invariant from DOC-03 is classified in this document as `DB` (enforced by a database constraint), `DOMAIN` (enforced only in the domain layer, with a stated reason), or `BOTH`. A consolidated mapping appears in Part 3 §22. `CON-PLT-018` places primary enforcement in the domain layer; database constraints are defence in depth, not a substitute — but where a constraint is cheap and the invariant is critical, both is correct.

**LC-03 — Type notation.** Types are written generically: `uuid`, `text`, `int`, `bigint`, `numeric(p,s)`, `bool`, `timestamptz`, `date`, `jsonb`, `bytea`, `text[]`. Engine-specific types are avoided except where marked ⚙.

**LC-04 — Index notation.** Every index is presented as: name, columns, kind, and **the query it serves**, stated as a sentence. An index without that sentence is incomplete.

---

## 3. Sizing Basis

⚠ **Working assumption (OQ-015).** No portfolio sizing has been supplied. This document is designed against the **Medium** reference profile of DOC-01 §12.1, with growth headroom to **Extra large**, and every partitioning decision states the volume that triggers it.

| Table family | Medium | Extra large | Design consequence |
|---|---|---|---|
| `component_entry` | ~3,000,000 | ~80,000,000 | **Largest table in the platform.** Partitioned; narrow rows; no free-text search |
| `audit_event` | ~60,000,000/yr | ~1,800,000,000/yr | Partitioned monthly; append-only; separate storage class after archival |
| `work_item_state_transition` | ~500,000 | ~8,000,000 | Partitioned monthly; append-only |
| `finding` | ~300,000 lifetime | ~8,000,000 | Not partitioned at Medium; partition by tenant hash at ≥ 2,000,000 |
| `finding_asset_impact` | ~600,000 | ~20,000,000 | Follows `finding` |
| `risk_score` | ~2,000,000 | ~50,000,000 | Partitioned by month; heavy retention pressure |
| `asset` | ~5,000 | ~100,000 | Not partitioned |
| `asset_relationship` | ~20,000 | ~500,000 | Not partitioned; temporal, so closed rows accumulate |
| `org_node` | ~300 | ~5,000 | Not partitioned |
| `org_closure` | ~1,500 | ~40,000 | Not partitioned; rebuilt rather than migrated |

**What changes if the real numbers differ materially.** Partition thresholds and index selectivity assumptions. **What does not change:** the schema shape, key strategy, tenant enforcement, and constraint set. This separation is deliberate — the design is bound to a named profile so that revaluation is a threshold change rather than a redesign.

---

## 4. Physical Design Principles

Seven principles, each traced to its driver. A design choice below traces to one of these or is unjustified.

| # | Principle | Source | Consequence |
|---|---|---|---|
| P1 | **Tenant enforcement is at the engine, not in queries** | `SEC-TEN-001`, D1 | Every tenant-scoped table carries `tenant_id`, is subject to a row-level policy, and has `tenant_id` as the leading column of every composite index |
| P2 | **Module schemas do not share tables** | `CON-PLT-015` | Each module owns its tables; cross-module references are by identifier with no foreign key across module boundaries (§4.1) |
| P3 | **Append-only streams are separate from mutable aggregates** | D10, `INV-WRK-04`, `INV-AUD-01` | Transition logs, audit events, and scores are insert-only, partitioned, and never updated |
| P4 | **History is preserved by state, not by deletion** | `INV-ORG-10`, `INV-AST-10` | No hard delete on entities that have carried findings, assets, or work. Lifecycle columns, not soft-delete flags (§6.4) |
| P5 | **Tenant-configurable taxonomies are rows, not database enums** | ADR-027, D3 | Severity, criticality, node types, asset types, work item types, and statuses are lookup tables with a stable ordinal (§8.1) |
| P6 | **Derived values that qualify a measure are materialized with it** | `CON-PLT-028`, PP-1 | Coverage and freshness are columns alongside the figures they qualify, not computed at read time |
| P7 | **Every index names its query** | DOC-00 §18.2 | An index with no stated query is removed or documented before the schema is approved |

### 4.1 On P2 and the absence of cross-module foreign keys

A foreign key from `finding.asset_id` to `asset.id` would be conventional and is deliberately **not** declared, because `finding` is owned by the Vulnerability Management module and `asset` by Asset Inventory.

**Why.** A cross-module foreign key does three things the architecture prohibits. It couples the two modules' deployment: a schema change to `asset` requires coordination with Vulnerability Management. It forecloses extraction (DOC-02 §15): a foreign key cannot span a service boundary. And it makes the database, rather than the domain, the arbiter of a relationship whose validity is a domain rule — `INV-VUL-08` requires at least one asset impact, which a foreign key cannot express.

**What replaces it.** Referential integrity across module boundaries is enforced in the domain layer at write time, and verified by a periodic reconciliation job that reports orphans rather than preventing them. Within a module, foreign keys are declared normally.

**The cost, stated plainly.** An orphaned reference is possible where it would otherwise be impossible. That is a real loss. It is accepted because the alternative — a database-level dependency graph mirroring the module graph — makes `CON-PLT-013` boundary enforcement meaningless at the persistence layer, which is where boundaries erode first.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `CON-DAT-004` | Foreign keys MUST NOT be declared across module boundaries. Cross-module referential integrity MUST be enforced in the domain layer and verified by a reconciliation job that reports orphans. | A cross-module foreign key couples deployment, forecloses extraction, and moves a domain rule into the engine where it cannot express the actual invariant. | M | AT, AR |
| `CON-DAT-005` | Foreign keys MUST be declared for every intra-module relationship, with explicit `ON DELETE` behaviour, and `ON DELETE CASCADE` MUST NOT be used on any table carrying history. | Within a module the coupling already exists, so the engine should enforce it. Cascade deletion on historical data is the mechanism by which audit trails silently lose rows. | M | AT, CR |

---

## 5. Key Strategy

### 5.1 Primary keys

**Every table uses a single-column `uuid` primary key named `id`, generated as a time-ordered UUID (version 7).** ⚙

| Property | Why it matters here |
|---|---|
| Time-ordered | Insert locality: new rows cluster at the index tail rather than scattering across the B-tree. For `component_entry` and `audit_event` at the volumes of §3, random UUIDs would fragment the index badly enough to affect insert throughput |
| Globally unique without coordination | Identifiers can be generated by the application before insert, which the aggregate pattern requires — a domain object has identity before it is persisted |
| Non-sequential across tenants | `SEC-AUZ-019`. A visible sequential identifier permits enumeration and discloses volume. Time-ordering leaks approximate creation time, which is accepted (§5.2) |
| Stable across environments | Migration and configuration promotion (`CFG-PLT-011`) do not require identifier remapping |

**Why not a sequential integer.** Enumeration and volume disclosure (`SEC-AUZ-019`), coordination on insert, and identifier collision on data migration between environments.

**Why not a random UUID.** Index fragmentation at the volumes of §3, which is measurable rather than theoretical for the two largest tables.

### 5.2 The disclosure a time-ordered identifier accepts

A time-ordered UUID leaks approximate creation time to anyone holding the identifier. This is accepted with reasons:

- The holder is authorized for the object, and the object's creation timestamp is visible to them anyway.
- It does not permit enumeration: the random component prevents constructing a neighbouring identifier.
- It does not disclose volume, unlike a sequence.

**Where it is not accepted:** identifiers appearing in unauthenticated or cross-tenant-visible contexts. There are none in the current design; if one is introduced, an opaque external identifier is required rather than exposing the primary key.

### 5.3 Natural keys and uniqueness

Natural keys are enforced as unique constraints, never used as primary keys. A natural key changes — repository URLs move, package identifiers get recanonicalized (`PRD-SBM-035`) — and a changing primary key requires updating every reference.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `CON-DAT-006` | Primary keys MUST be single-column time-ordered UUIDs named `id`. Composite and natural primary keys MUST NOT be used. | Time ordering provides insert locality at the volumes of §3; single-column keys keep foreign keys and index definitions simple; UUIDs avoid coordination and permit application-side generation, which the aggregate pattern requires. | M | AT, CR |
| `CON-DAT-007` | Natural keys MUST be enforced as unique constraints scoped by `tenant_id`, and MUST NOT serve as primary keys. | Natural keys change. A changing primary key requires cascading updates across every reference, and the change is exactly what identity resolution (`PRD-AST-006`) exists to handle gracefully. | M | AT |
| `CON-DAT-008` | Human-facing codes MUST be stored in a dedicated column, unique within tenant, immutable after assignment, and MUST NOT encode any mutable attribute. | These codes are quoted in conversation and external correspondence (`PRD-PTR-001` request codes). Encoding severity, assignee, or status guarantees the code eventually contradicts reality. | M | AT |

---

## 6. Common Column Patterns

Applied to every table unless stated otherwise. Stating them once prevents thirty tables each inventing a variation.

### 6.1 Mandatory columns

```sql
id            uuid         PRIMARY KEY
tenant_id     uuid         NOT NULL              -- omitted only on non-tenant-scoped tables
created_at    timestamptz  NOT NULL DEFAULT now()
created_by    uuid                               -- principal; null for system-originated
updated_at    timestamptz  NOT NULL DEFAULT now()
updated_by    uuid
row_version   int          NOT NULL DEFAULT 1    -- optimistic concurrency
```

### 6.2 Timestamps

All instants are `timestamptz` stored in UTC (`NFR-INT-004`). Calendar dates that carry no time — a go-live date, a leave day — are `date`, because coercing them to an instant forces a timezone decision that produces off-by-one errors when the viewer's zone differs from the author's.

Duration columns carry their unit in the name: `duration_seconds`, `age_days` (DOC-00 §10.2).

### 6.3 Optimistic concurrency

`row_version` increments on every update, and updates carry the expected version. `INV-WRK-17` requires concurrent edit detection; this is its persistence mechanism.

**Why on every table rather than only where contention is expected.** Contention appears where nobody predicted it, and adding the column later requires a migration plus an audit of every update path. The cost is four bytes per row.

### 6.4 Lifecycle rather than soft delete

**No table carries an `is_deleted` flag.** Entities that cannot be hard-deleted (P4) carry an explicit `lifecycle_state` referencing a status lookup table.

| Reason | Explanation |
|---|---|
| A flag conflates distinct states | `DEPRECATED` (accepts no new assignment, remains in operational views) and `ARCHIVED` (leaves operational views entirely) are different, and `INV-ORG-11` depends on the difference |
| A flag must be remembered in every query | An omitted `is_deleted = false` returns deleted rows, and it produces no error. An omitted lifecycle predicate is the same class of defect, but naming the states forces the author to consider which ones they want |
| A flag carries no history | Lifecycle transitions are recorded; a flag flip is not |

### 6.5 Content hashes

Where a table stores content whose identity is its content — `sbom_snapshot`, `evidence`, `import_session` — a `content_hash bytea NOT NULL` column carries it, with a unique constraint scoped by tenant. This makes idempotency a constraint rather than an application check (`PRD-SBM-033`, `PRD-ING-005`).

### 6.6 Embedded scope descriptors

`INV-ORG-11` and DOC-03 §6.7 require a scope descriptor on every scope-bearing object. Stored as columns rather than a joined table:

```sql
scope_node_id        uuid    NOT NULL      -- owning node at the time of the event
scope_ancestor_path  uuid[]  NOT NULL      -- root → owning node, at the time
scope_node_type_id   uuid    NOT NULL
scope_criticality_id uuid    NOT NULL
scope_hierarchy_ver  bigint  NOT NULL
scope_resolved_at    timestamptz NOT NULL
```

**Why columns rather than a joined `scope_descriptor` table.** Historical authorization (`SEC-AUZ-028`) evaluates against the descriptor on every historical read. A join to fetch it doubles the cost of the most latency-sensitive authorization path. Embedding also makes the descriptor physically immutable with its row, which is what `INV-ORG-11` requires.

**Why an array for the ancestor path.** ⚙ The predicate *"was this principal, authorized for node N, authorized for this object at that time?"* is `scope_ancestor_path @> ARRAY[N]` — a single indexable containment test. A join to a historical closure table would require reconstructing the closure at `scope_hierarchy_ver`, which is precisely the reconstruction §6.6 exists to avoid.

**The cost.** Approximately 60–100 bytes per scope-bearing row, on tables reaching hundreds of millions of rows. At Extra large this is material and is the single largest storage consequence of `PRD-ORG-011`. It is accepted because the alternative is irreproducible historical reporting, which disqualifies the executive and audit use cases.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `CON-DAT-009` | Scope descriptors MUST be embedded as columns on the scope-bearing row and MUST be immutable after insert. | Historical authorization evaluates the descriptor on every read; a join doubles the cost of the most latency-sensitive authorization path. Immutability with the row is what `INV-ORG-11` requires. | M | AT |
| `CON-DAT-010` | Every table MUST carry `row_version` for optimistic concurrency, and updates MUST be conditional on the expected version. | `INV-WRK-17`. Silent last-write-wins loses work invisibly — the person whose change vanished believes it was saved. | M | AT |
| `CON-DAT-011` | Tables MUST NOT carry a soft-delete flag. Entities that cannot be hard-deleted MUST carry an explicit lifecycle state. | A flag conflates `DEPRECATED` and `ARCHIVED`, which `INV-ORG-11` distinguishes, and carries no transition history. | M | CR |

---

## 7. Tenant Enforcement

### 7.1 The policy pattern

Every tenant-scoped table carries a row-level policy evaluated by the engine against a session-scoped tenant setting. ⚙

```sql
-- applied to every tenant-scoped table
ALTER TABLE <t> ENABLE ROW LEVEL SECURITY;
ALTER TABLE <t> FORCE ROW LEVEL SECURITY;          -- applies to the table owner too

CREATE POLICY <t>_tenant_isolation ON <t>
  USING      (tenant_id = current_tenant_id())
  WITH CHECK (tenant_id = current_tenant_id());
```

**On `FORCE`.** Without it the table owner bypasses the policy, and the application connects as a role that may be the owner. Forcing the policy for the owner too means there is no accidental bypass — only the deliberate, enumerated ones of `SEC-TEN-008`.

**On `WITH CHECK` as well as `USING`.** `USING` filters reads; `WITH CHECK` prevents writes into another tenant. Omitting the latter permits an insert carrying a foreign `tenant_id`, which is a cross-tenant *write* — a corruption rather than a disclosure, and harder to detect.

**On `current_tenant_id()` failing closed.** The function reads the session setting and **raises rather than returning null** where it is unset. A null-returning function makes the policy predicate `tenant_id = NULL`, which is never true, so reads return nothing — silently. A raise makes the missing context a visible error, per `SEC-TEN-005`.

### 7.2 Enumerated bypass roles

`SEC-TEN-008` requires bypasses to be enumerated, justified, and unreachable from application code.

| Role | Purpose | Reachable from application code |
|---|---|---|
| `app_runtime` | All ordinary access. Policy enforced | Yes — the only role the application uses |
| `migration_runner` | Schema migrations. `BYPASSRLS` | **No.** Separate credential, used only by the migration pipeline |
| `integrity_verifier` | Cross-tenant assertion (`SEC-TEN-047`), read-only, `BYPASSRLS` | **No.** Separate credential; emits an audit event per run |
| `offboarding_executor` | Tenant destruction (`SEC-TEN-041`), `BYPASSRLS` | **No.** Separate credential; dual-controlled |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `CON-DAT-012` | Every tenant-scoped table MUST have row-level security enabled and forced, with both a read policy and a write check. | `USING` alone permits inserting a row into another tenant — a corruption rather than a disclosure, and harder to detect than a read leak. `FORCE` closes the owner-bypass path. | M | AT, PT |
| `CON-DAT-013` | The tenant context function MUST raise where the session tenant is unset, and MUST NOT return null. | A null makes the policy predicate silently unsatisfiable, so a missing context returns an empty result rather than an error — indistinguishable from legitimately empty data (`SEC-TEN-005`). | M | AT |
| `CON-DAT-014` | Bypass roles MUST be enumerated as in §7.2, MUST use credentials unavailable to the application runtime, and each MUST emit an audit event on use. | An unenumerated bypass becomes the path of least resistance for ordinary features. Credential separation is what makes unreachability structural rather than procedural. | M | AT, CR |
| `CON-DAT-015` | `tenant_id` MUST be the leading column of every composite index on a tenant-scoped table. | Every query carries a tenant predicate by construction, so a leading `tenant_id` makes every index usable for it. A non-leading tenant column produces an index the planner cannot use for the most common predicate. | M | AT, CR |

### 7.3 Why not schema-per-tenant

Considered and rejected. Schema-per-tenant provides strong isolation and defeats the deployment model: migrations must run per schema, so a thousand tenants means a thousand migration executions with partial-failure states; connection pooling degrades because pools become tenant-affine; and cross-tenant platform operations — integrity verification, offboarding — require iterating schemas. Row-level enforcement with per-tenant keys (`SEC-TEN-012`) gives isolation at the layers that matter without those costs.

---

## 8. Taxonomies and Typed Attributes

### 8.1 Taxonomies are tables, not database enums

ADR-027 requires severity, criticality, node types, asset types, work item types, and workflow states to be tenant-configurable. A database enum type cannot be tenant-scoped and cannot be extended without a migration.

**Common shape** for every taxonomy table:

```sql
id            uuid        PRIMARY KEY
tenant_id     uuid                          -- null ⇒ platform-supplied default
code          text        NOT NULL           -- immutable, stable for integrations
label_i18n    jsonb       NOT NULL           -- { "en": "...", "vi": "..." }
ordinal       int         NOT NULL           -- product-fixed comparison basis
lifecycle_state text      NOT NULL
...
UNIQUE (tenant_id, code)
UNIQUE (tenant_id, ordinal)
```

**Why `ordinal` is mandatory.** DOC-03 §7.6 and `PRD-VUL-005` require configurable presentation over a fixed comparison basis. Without a stored ordinal, comparing two severities requires interpreting labels, and cross-tenant support becomes impossible.

**Why `code` is immutable and `label_i18n` is not.** The label is what users see and is expected to change; the code is what integrations, saved queries, imports, and API consumers reference. A mutable code silently breaks all of them, and the breakage appears as empty results rather than errors.

**Why localized labels are `jsonb` rather than a translation table.** ⚙ A translation table means every taxonomy read joins to it, and taxonomy values are read on virtually every query. The document column is read with the row. The cost is that a bulk translation update rewrites rows rather than inserting into a side table — acceptable, because translations change rarely.

### 8.2 Typed attributes

`PRD-AST-014`, `PRD-WRK-003`, and `CFG-WRK-003` require tenant-defined custom fields with typed values, validation, and **participation in filtering, search, and export**.

**Storage.** A `jsonb` column on the owning table, with the schema held in a registry table. ⚙

```sql
-- on asset, work_item, assessment, assessment_request
attributes    jsonb   NOT NULL DEFAULT '{}'::jsonb

-- registry, owned by the schema-registry kernel module
attribute_schema (
  id, tenant_id, target_kind, target_type_id,
  field_key text NOT NULL,               -- immutable
  data_type text NOT NULL,               -- STRING|INT|DECIMAL|BOOL|DATE|TIMESTAMP|ENUM|REF|TEXT
  validation jsonb NOT NULL,             -- bounds, pattern, enum members, referenced type
  is_required bool, is_searchable bool, is_exportable bool,
  visibility_rule jsonb,                 -- conditional visibility
  display_order int,
  lifecycle_state text
)
```

**Why not a column per custom field.** A migration per tenant per field. With hundreds of tenants each defining a handful, the table acquires thousands of mostly-null columns and every schema change becomes a coordinated migration.

**Why not an entity-attribute-value table.** A row per field per entity. Filtering on three custom fields becomes three self-joins, and ordering by one requires a join plus a sort over an unindexable projection. At the volumes of §3 this does not perform, and it is the classic failure of the pattern.

**Why `jsonb` works.** Expression indexes on specific keys give indexed filtering on the fields tenants actually filter by, without a column per field:

```sql
CREATE INDEX ix_work_item__attr_priority
  ON work_item ((attributes ->> 'customer_priority'))
  WHERE attributes ? 'customer_priority';
```

**The honest cost of `jsonb`.** Three real drawbacks, stated because the pattern is often presented as free:

1. **The engine cannot validate the schema.** Validation is domain-layer (`CON-PLT-018`), and a defect there writes malformed data the engine accepts. Mitigation: a check constraint asserting the value is an object, plus a reconciliation job validating stored attributes against the registry and reporting violations.
2. **Index creation is per-field and operational.** A tenant marking a new field searchable requires an index, which is a migration-class operation rather than a configuration change. Mitigation: a bounded number of indexable fields per type, provisioned as generic slots — an accepted compromise, and a genuine limitation on `CFG-WRK-003`.
3. **Type coercion in predicates is error-prone.** `attributes ->> 'count'` is text; comparing it numerically requires a cast, and an unindexed cast is a scan. Mitigation: expression indexes carry the cast, and query construction is centralized rather than hand-written per call site.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `CON-DAT-016` | Tenant-configurable taxonomies MUST be stored as tables with an immutable `code`, a localized label document, and a product-fixed `ordinal`. Database enum types MUST NOT be used for any tenant-configurable value. | A database enum cannot be tenant-scoped or extended without migration, which defeats ADR-027. The ordinal preserves a comparison basis independent of tenant vocabulary. | M | AT |
| `CON-DAT-017` | Custom attributes MUST be stored in a typed document column with the schema in a registry, and MUST NOT use a column-per-field or entity-attribute-value design. | Column-per-field requires a migration per tenant field; EAV cannot serve multi-field filtering or ordering at the volumes of §3. | M | AT, AR |
| `CON-DAT-018` | Attributes marked searchable MUST be backed by an expression index, and the number of indexable attributes per type MUST be bounded and documented. | Unindexed attribute filtering degrades to a scan on the largest tables. The bound is a genuine limitation on configurability and must be stated rather than discovered. | M | AT, DI |
| `CON-DAT-019` | A reconciliation job MUST validate stored attribute documents against the registry and report violations. | The engine cannot enforce the schema, so a domain-layer defect writes data the engine accepts. Reconciliation is the only detection mechanism. | M | AT |

---

## 9. Indexing Methodology

### 9.1 The rule

**Every index states the query it serves, in a sentence.** DOC-00 §18.2 makes this a completeness condition. Its purpose is practical: an index whose query is unknown cannot be removed safely and cannot be confirmed sufficient.

### 9.2 Derivation

Indexes are derived from three sources, in priority order:

| Source | Example |
|---|---|
| **Authorization predicates** — evaluated on every access, so they are the highest-frequency predicates in the platform | `tenant_id` leading everywhere; `scope_ancestor_path` containment for historical authorization |
| **The DOC-05 operation set** — every collection endpoint's filter, sort, and pagination becomes an index requirement | Finding list filtered by scope, state, severity band, sorted by score |
| **Projection maintenance** — event-driven updates locate rows by source identifier | `finding_asset_impact` by `finding_id` |

An index arising from none of these is speculative and is not created. Indexes are a write cost on tables reaching hundreds of millions of rows.

### 9.3 Standard patterns

| Pattern | Form | Serves |
|---|---|---|
| Tenant-scoped lookup | `(tenant_id, id)` | Any single-row fetch under the tenant policy |
| Tenant-scoped natural key | `UNIQUE (tenant_id, <natural key>)` | Identity resolution and idempotency |
| Scope-filtered list | `(tenant_id, scope_node_id, <sort>)` | Collection reads within a node |
| Subtree-filtered list ⚙ | `(tenant_id, scope_ancestor_path)` GIN | Reads across a subtree without a closure join |
| State-filtered work queue | `(tenant_id, state_id, <sort>) WHERE <not terminal>` | Operational queues; partial index excludes the majority of terminal rows |
| Temporal current-row | `(tenant_id, <entity>) WHERE valid_until IS NULL` | Current relationships without scanning closed history |
| Append-only time range | `(tenant_id, <subject>, occurred_at DESC)` | History reads, newest first |
| Attribute filter | `((attributes ->> 'key')) WHERE attributes ? 'key'` | Custom field filtering |

**On partial indexes.** They matter disproportionately here. `finding` at Extra large is 8,000,000 rows of which the large majority are terminal; a partial index on non-terminal states is a small fraction of the size and serves the queries that run constantly. The operational surfaces read open work; closed work is read by reporting, which uses projections.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `CON-DAT-020` | Every index MUST be documented with the query it serves. An index without a documented query MUST NOT be created, and an existing one MUST be removed or documented. | An index whose purpose is unknown cannot be removed safely or confirmed sufficient, and indexes are a write cost on the largest tables. | M | DI, CR |
| `CON-DAT-021` | Operational queues MUST be served by partial indexes excluding terminal states. | Terminal rows are the large majority at scale and are read by reporting through projections, not by operational surfaces. | M | AT |
| `CON-DAT-022` | Index creation and removal on tables above the §3 Medium volumes MUST be performable without blocking writes. | A blocking index build on the finding or component tables is an outage, and it will be needed most when the platform is busiest. | M | AT |

---

## 10. Partitioning Methodology

### 10.1 When to partition

| Trigger | Strategy |
|---|---|
| Append-only, time-queried, retention-bounded | **Range by month.** Retention becomes partition drop rather than a mass delete |
| Very large, tenant-queried, no time dimension | **Hash by tenant.** Bounds any single query's scan; isolates one tenant's growth |
| Large, tenant-queried, time-bounded | Range by month, sub-hash by tenant where a single tenant dominates |
| Below Medium volumes | **Do not partition.** Partitioning adds planning cost and operational complexity that a smaller table does not repay |

### 10.2 Application

| Table | Strategy | Trigger volume | Retention mechanism |
|---|---|---|---|
| `audit_event` | Range by month | Immediate — this table grows fastest | Drop partition after retention; archive to cold storage first |
| `work_item_state_transition` | Range by month | Immediate | Retained for the work item's life; archived, not dropped |
| `risk_score` | Range by month | Immediate | Drop after retention; latest score per subject retained in a projection |
| `component_entry` | Hash by tenant, 32 partitions | Immediate | Follows snapshot retention |
| `sbom_snapshot` | Hash by tenant | ≥ 20,000 | Retention by age and count per artifact |
| `finding` | Hash by tenant | ≥ 2,000,000 | No deletion; archived |
| `finding_asset_impact` | Hash by tenant, aligned with `finding` | Follows `finding` | Follows `finding` |
| `notification_delivery` | Range by month | ≥ 5,000,000 | Drop after retention |
| `ai_suggestion` | Range by month | ≥ 1,000,000 | Drop after retention |

**On aligning `finding_asset_impact` with `finding`.** ⚙ Both hash by `tenant_id` with the same partition count so that the join between them is partition-wise rather than crossing every partition. A misaligned join on the platform's most frequent multi-table read would negate the benefit of partitioning either.

**On not partitioning `finding` at Medium.** 300,000 rows does not repay partitioning. The threshold is stated so that the decision is revisited on data rather than on intuition, and so that the migration path exists before it is needed.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `CON-DAT-023` | Append-only tables MUST be range-partitioned by month, and retention MUST be implemented as partition drop rather than row deletion. | Deleting hundreds of millions of rows is a sustained load event that competes with operational traffic. A partition drop is metadata. | M | AT |
| `CON-DAT-024` | Partition keys MUST be aligned between tables that are frequently joined. | A misaligned join crosses every partition, negating the benefit. `finding` and `finding_asset_impact` are the platform's most frequent multi-table read. | M | AT, AR |
| `CON-DAT-025` | Partition creation MUST be automated ahead of need, and a missing future partition MUST alert rather than fail an insert. | A missing partition rejects inserts, which for `audit_event` fails every audited operation under `CON-PLT-021`. | M | AT |

---

## 11. Schema — Tenancy, Organization, Asset

### 11.1 Module: `tenant-context`

#### 11.1.1 `tenant`

Not tenant-scoped; it defines tenants. No row-level policy.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `uuid` | PK | |
| `display_name` | `text` | NOT NULL | |
| `lifecycle_state` | `text` | NOT NULL, CHECK in enumerated set | `PROVISIONING`, `ACTIVE`, `SUSPENDED`, `OFFBOARDING`, `OFFBOARDED` |
| `residency_region` | `text` | NOT NULL | `SEC-TEN-018` |
| `hierarchy_version` | `bigint` | NOT NULL DEFAULT 1 | `INV-TEN-03`; monotonic |
| `key_reference` | `text` | NOT NULL | Reference to tenant key material; never the key |
| `entitlement_tier` | `text` | NOT NULL | `LIC-PLT-003` |
| `established_at` | `timestamptz` | NOT NULL | |
| `offboarded_at` | `timestamptz` | | |
| Common columns | | | §6.1 less `tenant_id` |

**Constraints.** `CHECK (hierarchy_version >= 1)`. `CHECK (lifecycle_state <> 'OFFBOARDED' OR offboarded_at IS NOT NULL)` — an offboarded tenant without a timestamp is an incomplete offboarding and must not be representable.

**Invariant mapping.** `INV-TEN-01` `DOMAIN` — enforced by the policy pattern on every other table rather than here. `INV-TEN-03` `BOTH`. `INV-TEN-04` `DOMAIN` — residency change is a migration process, not a column update.

| Index | Columns | Serves |
|---|---|---|
| `pk_tenant` | `(id)` | Tenant context establishment on every request |
| `ux_tenant__display_name` | UNIQUE `(display_name)` | Administrative lookup; prevents duplicate onboarding |
| `ix_tenant__lifecycle_residency` | `(lifecycle_state, residency_region)` | Operator listing of active tenants per region for maintenance and residency verification |

**Partitioning.** None. **Retention.** Row retained after offboarding so the identifier is never reused (`SEC-TEN-043`).

#### 11.1.2 `tenant_id_reservation`

A single-column table of every tenant identifier ever issued, including offboarded ones.

**Why it exists.** `SEC-TEN-043` prohibits identifier reuse. Relying on the `tenant` row surviving is fragile: a future cleanup, a restore from an older backup, or an offboarding that removes the row would permit reuse. A dedicated append-only reservation table makes the prohibition structural.

| Column | Type | Notes |
|---|---|---|
| `tenant_id` | `uuid` | PK. Never deleted, ever |
| `reserved_at` | `timestamptz` | NOT NULL |

### 11.2 Module: `organization-scope`

#### 11.2.1 `org_node_type`

Taxonomy table per §8.1, plus:

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `permitted_parent_type_ids` | `uuid[]` | NOT NULL DEFAULT '{}' | Empty ⇒ may be a tree root (`INV-ORG-01`) |
| `may_own_assets` | `bool` | NOT NULL | |
| `may_scope_work` | `bool` | NOT NULL | |

**Invariant mapping.** `INV-ORG-01` `DOMAIN` — "at least one type per tenant has no permitted parents" is a set-level assertion the engine cannot express per row; validated at configuration time (`CFG-PLT-009`). `INV-ORG-02` `DOMAIN` — type-level cycle detection is graph traversal. `INV-ORG-03` `DB` — foreign key from `org_node.type_id` with `ON DELETE RESTRICT`. `INV-ORG-04` `DB` — an update trigger rejecting a `code` change.

| Index | Columns | Serves |
|---|---|---|
| `ux_org_node_type__code` | UNIQUE `(tenant_id, code)` | Type resolution by code from configuration import and API |
| `ix_org_node_type__ordinal` | `(tenant_id, ordinal)` | Ordered type listing for the hierarchy configuration interface |

#### 11.2.2 `org_node`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `uuid` | PK | |
| `tenant_id` | `uuid` | NOT NULL | |
| `type_id` | `uuid` | NOT NULL, FK → `org_node_type` RESTRICT | |
| `parent_id` | `uuid` | FK → `org_node` RESTRICT | Null ⇒ tree root (`INV-ORG-05`) |
| `name` | `text` | NOT NULL | |
| `external_reference` | `text` | | Key in an authoritative source (`PRD-ORG-012`) |
| `criticality_mode` | `text` | NOT NULL, CHECK `ASSIGNED`/`INHERITED` | |
| `criticality_tier_id` | `uuid` | FK → `criticality_tier` RESTRICT | |
| `criticality_justification` | `text` | | Required on override (`INV-ORG-09`) |
| `criticality_assigned_by` | `uuid` | | |
| `criticality_assigned_at` | `timestamptz` | | |
| `lifecycle_state` | `text` | NOT NULL | `ACTIVE`, `DEPRECATED`, `ARCHIVED` |
| `tags` | `text[]` | NOT NULL DEFAULT '{}' | |
| Common columns | | | §6.1 |

**Constraints.**

```sql
CHECK (criticality_mode = 'INHERITED' OR criticality_tier_id IS NOT NULL)
CHECK (parent_id IS NULL OR parent_id <> id)                     -- trivial self-cycle
UNIQUE (tenant_id, parent_id, name)                              -- siblings distinct
UNIQUE (tenant_id, external_reference)                           -- where not null
```

**Ownership** is a separate table rather than array columns, because `INV-ORG-12` permits an empty set, distinguishes business from technical ownership, and ownership changes are audited independently of node changes:

```sql
org_node_owner (
  id, tenant_id,
  org_node_id  uuid NOT NULL FK → org_node,
  principal_id uuid NOT NULL,
  owner_kind   text NOT NULL CHECK IN ('BUSINESS','TECHNICAL'),
  UNIQUE (tenant_id, org_node_id, principal_id, owner_kind)
)
```

**Invariant mapping.** `INV-ORG-05` `BOTH` — single `parent_id` column is structural. `INV-ORG-06` `DOMAIN` — parent type validity requires reading the parent's type and the child type's permitted set; expressible as a trigger but placed in the domain per `CON-PLT-018`. `INV-ORG-07` `DOMAIN` — full cycle detection is a traversal; the trivial self-reference is a `CHECK`. `INV-ORG-08` `DOMAIN`. `INV-ORG-09` `DB` — `CHECK` requiring justification where mode is `ASSIGNED` and an ancestor is also assigned is not expressible per-row; a partial `CHECK` requires non-empty justification whenever `criticality_mode = 'ASSIGNED'`, which is stricter than the invariant and accepted. `INV-ORG-10` `DB` — `ON DELETE RESTRICT` from every referencing table. `INV-ORG-11` `DOMAIN` + `CON-DAT-009`. `INV-ORG-12` `DB` — no `NOT NULL` on ownership, by design.

**On the stricter criticality justification constraint.** The invariant requires justification only when overriding an ancestor's assignment. The `CHECK` requires it on every explicit assignment, including the root where there is nothing to override. This is deliberate: the root's criticality assignment is the most consequential in the tenant, and requiring a justification for it costs one sentence at onboarding.

| Index | Columns | Serves |
|---|---|---|
| `pk_org_node` | `(id)` | Node fetch |
| `ix_org_node__parent` | `(tenant_id, parent_id)` | Children of a node — the hierarchy navigation interface, and closure rebuild |
| `ux_org_node__sibling_name` | UNIQUE `(tenant_id, parent_id, name)` | Prevents duplicate siblings; serves name-based resolution during import |
| `ux_org_node__external_ref` | UNIQUE `(tenant_id, external_reference)` WHERE not null | Hierarchy synchronization from an authoritative source (`PRD-ORG-012`) |
| `ix_org_node__type_state` | `(tenant_id, type_id, lifecycle_state)` | "All active business units" — the scope-assignment interface and reporting rollups |
| `ix_org_node__criticality` | `(tenant_id, criticality_tier_id)` WHERE mode = `ASSIGNED` | Finding nodes with explicit criticality, for the inheritance resolver and for override reporting |

**Partitioning.** None at any profile — 5,000 rows at Extra large.

#### 11.2.3 `org_closure`

Derived projection (DOC-03 §7.4), rebuildable from `org_node` (`INV-ORG-14`).

| Column | Type | Notes |
|---|---|---|
| `tenant_id` | `uuid` | NOT NULL |
| `ancestor_id` | `uuid` | NOT NULL |
| `descendant_id` | `uuid` | NOT NULL |
| `depth` | `int` | NOT NULL, CHECK `>= 0`. Zero ⇒ self-reference (`INV-ORG-13`) |
| `hierarchy_version` | `bigint` | NOT NULL — version at which the row became valid |

**Primary key** `(tenant_id, ancestor_id, descendant_id)` — the one place a composite key is used, because the table has no independent identity and `CON-DAT-006`'s reasons do not apply to a derived projection. Recorded as a deliberate exception.

**Invariant mapping.** `INV-ORG-13` `DB` — a constraint trigger asserting a depth-zero row exists for every node. `INV-ORG-14` `DOMAIN` + a reconciliation job comparing the projection against a rebuild (`CON-DAT-026`). `INV-ORG-15` `DOMAIN` — updated in the same transaction as the parentage change.

| Index | Columns | Serves |
|---|---|---|
| `pk_org_closure` | `(tenant_id, ancestor_id, descendant_id)` | Descendants of a node — **subtree scope resolution, the platform's highest-frequency query** |
| `ix_org_closure__descendant` | `(tenant_id, descendant_id, depth)` | Ancestors of a node — breadcrumbs, criticality inheritance resolution, and escalation to the nearest ancestor owner |
| `ix_org_closure__ancestor_depth` | `(tenant_id, ancestor_id, depth)` | Descendants to a bounded depth — the hierarchy tree interface, which loads one level at a time |

**On three indexes for a small table.** All three directions are on hot paths. `pk` serves every authorization decision; the descendant index serves inheritance resolution and every escalation; the depth-bounded index serves the navigation interface. At 40,000 rows the write cost is negligible and the read benefit is on the platform's most frequent operations.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `CON-DAT-026` | A reconciliation job MUST rebuild the closure projection from `org_node` and assert equality with the maintained projection, reporting any divergence. | `INV-ORG-14`. A corrupted closure silently breaks authorization: a principal either loses access they should have, which is reported, or gains access they should not, which is not. Rebuild-and-compare is the only practical detection. | M | AT |

#### 11.2.4 `criticality_tier`

Taxonomy table per §8.1. `ordinal` lower ⇒ more critical (`INV-ORG-16`). `ON DELETE RESTRICT` from `org_node` and `asset` enforces `INV-ORG-17`.

### 11.3 Module: `asset-inventory`

#### 11.3.1 `asset_type`

Taxonomy table per §8.1, plus:

| Column | Type | Notes |
|---|---|---|
| `identity_rule` | `jsonb` | NOT NULL — natural key attributes, normalizations, match strategy, rule version (`PRD-AST-006`) |
| `attribute_schema_ref` | `uuid` | FK → `attribute_schema` grouping |
| `permitted_edges` | `jsonb` | NOT NULL — edge type to permitted counterpart types (`INV-AST-14`) |
| `is_network_reachable` | `bool` | NOT NULL — exposure applies (`INV-AST-07`) |
| `may_carry_findings` | `bool` | NOT NULL |
| `applicable_checklist_ids` | `uuid[]` | NOT NULL DEFAULT '{}' |

**Invariant mapping.** `INV-AST-01` `BOTH` — FK plus a trigger rejecting `type_id` change. `INV-AST-03` `DB` — RESTRICT. `INV-AST-04` `DB` — trigger on `code`.

#### 11.3.2 `asset`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `uuid` | PK | |
| `tenant_id` | `uuid` | NOT NULL | |
| `type_id` | `uuid` | NOT NULL, FK RESTRICT | Immutable (`INV-AST-01`) |
| `identity_key` | `text` | NOT NULL | Resolved natural key (§11.3.4) |
| `identity_rule_version` | `int` | NOT NULL | Enables re-resolution (`PRD-AST-006`) |
| `display_name` | `text` | NOT NULL | |
| `owning_node_id` | `uuid` | | Null ⇒ `UNCLAIMED` (`INV-AST-05`) |
| `criticality_mode` | `text` | NOT NULL | `ASSIGNED`/`INHERITED` |
| `criticality_tier_id` | `uuid` | FK RESTRICT | |
| `criticality_justification` | `text` | | |
| `exposure_declared` | `text` | | Applies where type is network-reachable |
| `exposure_declared_by` | `uuid` | | |
| `exposure_declared_at` | `timestamptz` | | |
| `exposure_observed` | `text` | | |
| `exposure_observed_source` | `text` | | |
| `exposure_observed_at` | `timestamptz` | | |
| `exposure_conflict` | `bool` | NOT NULL DEFAULT false | Derived (`INV-AST-08`); stored per P6 |
| `lifecycle_state` | `text` | NOT NULL | `DISCOVERED`, `ACTIVE`, `DEPRECATED`, `RETIRED` |
| `attributes` | `jsonb` | NOT NULL DEFAULT '{}' | §8.2 |
| `tags` | `text[]` | NOT NULL DEFAULT '{}' | |
| `technical_contact_id` | `uuid` | | Distinct from node owner (`PRD-AST-016`) |
| `discovery_source` | `text` | NOT NULL | Provenance (`PRD-AST-007`) |
| `discovery_method` | `text` | NOT NULL | |
| `first_seen_at` | `timestamptz` | NOT NULL | |
| `last_confirmed_at` | `timestamptz` | NOT NULL | Advances only on discovery evidence (`INV-AST-12`) |
| `merged_into_asset_id` | `uuid` | FK → `asset` | Set on absorption (`INV-AST-22`) |
| Scope descriptor columns | | | §6.6, resolved at ownership assignment |
| Common columns | | | §6.1 |

**Constraints.**

```sql
UNIQUE (tenant_id, type_id, identity_key)                        -- INV-AST-06 identity
CHECK (criticality_mode = 'INHERITED' OR criticality_tier_id IS NOT NULL)
CHECK (lifecycle_state <> 'RETIRED' OR merged_into_asset_id IS NOT NULL
       OR retired_reason IS NOT NULL)
CHECK (merged_into_asset_id IS NULL OR merged_into_asset_id <> id)
```

**Invariant mapping.** `INV-AST-05` `DB` — a single nullable `owning_node_id` makes more than one owner unrepresentable. `INV-AST-06` `DB` — unique constraint on the resolved key. `INV-AST-07` `DOMAIN` — requires reading the type. `INV-AST-08` `DOMAIN` — `exposure_conflict` is computed on write. `INV-AST-09` `DOMAIN` + projection exclusion. `INV-AST-10` `DB` — RESTRICT from `finding_asset_impact` and `assessment_scope`. `INV-AST-11` `DB` — §11.3.5. `INV-AST-12` `DB` — a trigger rejecting `last_confirmed_at` advancement where `discovery_source` indicates manual edit.

**On `INV-AST-12` as a trigger.** This is one of two places where a trigger is preferred to domain-only enforcement, because the invariant protects a *coverage signal* and a domain-layer defect would make a stale asset appear fresh — a PP-1 violation through a field nobody thinks of as a metric. Cheap to enforce, expensive to miss.

| Index | Columns | Kind | Serves |
|---|---|---|---|
| `pk_asset` | `(id)` | btree | Asset fetch |
| `ux_asset__identity` | UNIQUE `(tenant_id, type_id, identity_key)` | btree | **Identity resolution on every ingestion and submission** — the highest-frequency write-path lookup |
| `ix_asset__owner_state` | `(tenant_id, owning_node_id, lifecycle_state)` | btree | Assets owned by a node — inventory list, scope-filtered reads, node-level rollups |
| `ix_asset__unclaimed` | `(tenant_id, first_seen_at)` | btree, WHERE `owning_node_id IS NULL AND lifecycle_state <> 'RETIRED'` | **The unowned asset queue** (`PRD-AST-011`), oldest first. Partial index is a small fraction of the table |
| `ix_asset__scope_subtree` | `(tenant_id, scope_ancestor_path)` | GIN ⚙ | Subtree-scoped asset reads without a closure join |
| `ix_asset__type_state` | `(tenant_id, type_id, lifecycle_state)` | btree | "All active repositories" — inventory filtering and coverage denominators |
| `ix_asset__exposure_conflict` | `(tenant_id, exposure_observed_at)` | btree, WHERE `exposure_conflict` | The exposure conflict queue (`PRD-AST-017`) |
| `ix_asset__criticality` | `(tenant_id, criticality_tier_id)` | btree, WHERE mode = `ASSIGNED` | Override reporting and inheritance resolution |
| `ix_asset__tags` | `(tags)` | GIN ⚙ | Tag-filtered inventory reads |
| `ix_asset__stale_confirm` | `(tenant_id, last_confirmed_at)` | btree, WHERE state IN (`DISCOVERED`,`ACTIVE`) | Assets not confirmed recently — coverage and freshness reporting |

**Partitioning.** None — 100,000 rows at Extra large.

**Retention.** No deletion (`INV-AST-10`). Retired assets remain; excluded from operational projections.

#### 11.3.3 `asset_relationship`

Temporal edges (`INV-AST-16`).

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `uuid` | PK | |
| `tenant_id` | `uuid` | NOT NULL | |
| `from_asset_id` | `uuid` | NOT NULL, FK RESTRICT | |
| `to_asset_id` | `uuid` | NOT NULL, FK RESTRICT | |
| `edge_type` | `text` | NOT NULL | `BUILDS`, `DEPLOYS_AS`, `EXPOSES`, `PUBLISHED_ON`, `DESCRIBED_BY`, `CONTAINS`, `DEPENDS_ON` |
| `attributes` | `jsonb` | NOT NULL DEFAULT '{}' | e.g. path within a monorepo |
| `discovery_source` | `text` | NOT NULL | |
| `valid_from` | `timestamptz` | NOT NULL | |
| `valid_until` | `timestamptz` | | Null ⇒ current |
| Common columns | | | §6.1 |

**Constraints.**

```sql
CHECK (from_asset_id <> to_asset_id)
CHECK (valid_until IS NULL OR valid_until > valid_from)
UNIQUE (tenant_id, from_asset_id, to_asset_id, edge_type)
  WHERE valid_until IS NULL                                      -- one current edge per triple
```

**On the partial unique constraint.** ⚙ Without it, two current edges of the same type between the same pair are representable, and graph traversal would double-count — inflating every "which services contain this component" answer. Scoping the constraint to current rows permits the historical sequence that `INV-AST-16` requires.

**Invariant mapping.** `INV-AST-13` `DB` — both FKs are tenant-scoped through the policy. `INV-AST-14` `DOMAIN` — requires reading both types and the permitted-edge document. `INV-AST-15` `DB` — no constraint restricts either side to one, by design. `INV-AST-16` `DB` — the partial unique constraint plus no delete path. `INV-AST-17` `DOMAIN` — per-node traversal filtering is an authorization concern (`SEC-AUZ-024`).

| Index | Columns | Serves |
|---|---|---|
| `pk_asset_relationship` | `(id)` | Edge fetch |
| `ix_asset_rel__from_current` | `(tenant_id, from_asset_id, edge_type)` WHERE `valid_until IS NULL` | **Forward traversal** — the component-to-exposure chain of DOC-03 §6.3 |
| `ix_asset_rel__to_current` | `(tenant_id, to_asset_id, edge_type)` WHERE `valid_until IS NULL` | **Reverse traversal** — "which repositories build this artifact", for remediation routing |
| `ux_asset_rel__current_triple` | UNIQUE `(tenant_id, from_asset_id, to_asset_id, edge_type)` WHERE `valid_until IS NULL` | Prevents duplicate current edges; serves upsert on rediscovery |
| `ix_asset_rel__historical` | `(tenant_id, from_asset_id, valid_from DESC)` | "What was deployed when this finding was open" — retest scoping and historical posture reproduction |

**On both directions being indexed.** The forward chain answers *what is exposed*; the reverse answers *who owns the code that produced it*. Both are required by the worked case of DOC-03 §6.3, and an unindexed reverse traversal on a large graph is a scan.

**Partitioning.** None. **Retention.** Closed edges retained; archival candidate above 500,000 rows, since most queries target current edges.

#### 11.3.4 `asset_identity_alias`

Supports the `ALIAS_SET` match strategy and re-resolution after a rule version change.

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `asset_id` | `uuid` | NOT NULL, FK CASCADE — aliases have no life beyond their asset |
| `identity_key` | `text` | NOT NULL |
| `identity_rule_version` | `int` | NOT NULL |
| `source` | `text` | NOT NULL |

`UNIQUE (tenant_id, asset_id, identity_key)`. Index `ix_asset_alias__key (tenant_id, identity_key)` serves identity resolution where the primary key does not match — the second lookup in the resolution pipeline.

**On `ON DELETE CASCADE` here.** §6/`CON-DAT-005` prohibits cascade on tables carrying history. An alias carries no history: it is a derived lookup entry. This is a deliberate, narrow exception and is recorded as such.

#### 11.3.5 `asset_external_identifier`

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `asset_id` | `uuid` | NOT NULL, FK RESTRICT |
| `source_system` | `text` | NOT NULL |
| `external_id` | `text` | NOT NULL |

`UNIQUE (tenant_id, source_system, external_id)` enforces `INV-AST-11` — two assets claiming the same external identifier from the same source is a duplicate to resolve, not a permitted state. Index `ix_asset_extid__lookup (tenant_id, source_system, external_id)` serves correlation on every connector-sourced ingestion.

#### 11.3.6 `ownership_claim`

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `asset_id` | `uuid` | NOT NULL, FK RESTRICT |
| `proposed_node_id` | `uuid` | NOT NULL, FK RESTRICT |
| `basis` | `text` | NOT NULL — `EXPLICIT`, `INFERRED_PATH_PATTERN`, `INFERRED_PIPELINE`, `INFERRED_PRIOR_FINDING`, `INFERRED_MANUAL_PROPOSAL` |
| `confidence` | `numeric(4,3)` | CHECK between 0 and 1 |
| `state` | `text` | NOT NULL — `PROPOSED`, `CONFIRMED`, `REJECTED`, `EXPIRED` |
| `claimed_by`, `claimed_at` | | |
| `resolved_by`, `resolved_at` | | |
| `escalation_level` | `int` | NOT NULL DEFAULT 0 |

**Constraint.** `UNIQUE (tenant_id, asset_id) WHERE state = 'PROPOSED'` enforces `INV-AST-19` — at most one proposed claim per asset.

**Invariant mapping.** `INV-AST-18` `DOMAIN` — authorization against the proposed node (`SEC-AUZ-018`). `INV-AST-19` `DB`. `INV-AST-20` `DOMAIN`.

| Index | Columns | Serves |
|---|---|---|
| `ux_ownership_claim__proposed` | UNIQUE `(tenant_id, asset_id)` WHERE state = `PROPOSED` | Enforces one open claim; serves the "is there an open claim" check on the asset view |
| `ix_ownership_claim__queue` | `(tenant_id, state, claimed_at)` WHERE state = `PROPOSED` | The claim queue, oldest first — and the escalation scheduler (`INV-AST-20`) |
| `ix_ownership_claim__node` | `(tenant_id, proposed_node_id, state)` | "Claims awaiting my confirmation" — the node owner's queue |

#### 11.3.7 `asset_merge`

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `surviving_asset_id` | `uuid` | NOT NULL, FK RESTRICT |
| `absorbed_asset_ids` | `uuid[]` | NOT NULL |
| `reason` | `text` | NOT NULL — `DUPLICATE_IDENTITY`, `RULE_VERSION_CHANGE`, `MANUAL` |
| `attribute_resolutions` | `jsonb` | NOT NULL — how conflicts were resolved (`INV-AST-24`) |
| `owner_resolution` | `jsonb` | Required where absorbed owners conflicted |
| `reversal_state` | `jsonb` | Sufficient state to reverse (`INV-AST-23`) |
| `reversible_until` | `timestamptz` | |
| `performed_by`, `performed_at` | | |

**Invariant mapping.** `INV-AST-21` `DOMAIN`. `INV-AST-22` `DB` — `asset.merged_into_asset_id` plus `RETIRED` state. `INV-AST-23` `DOMAIN` + `reversal_state`. `INV-AST-24` `DB` — `CHECK (owner_resolution IS NOT NULL OR ...)` is not expressible without knowing whether owners conflicted, so `DOMAIN` with a reconciliation assertion.

Index `ix_asset_merge__surviving (tenant_id, surviving_asset_id, performed_at DESC)` serves the merge history on an asset view, newest first.

---

## 12. Schema — Assessment

### 12.1 `assessment_type`

Taxonomy table per §8.1, plus:

| Column | Type | Notes |
|---|---|---|
| `payload_schema_ref` | `uuid` | FK → attribute schema grouping |
| `workflow_definition_id` | `uuid` | FK RESTRICT → `workflow_definition` |
| `requires_request` | `bool` | NOT NULL — intake precedes the assessment |
| `checklist_selection_rules` | `jsonb` | NOT NULL — evaluated by the shared rules engine (`CON-PLT-012`) |
| `specialist_track_rules` | `jsonb` | NOT NULL |

### 12.2 `assessment_request`

The intake aggregate (DOC-03 §9.2). The platform's highest-volume write surface from non-specialist users.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `uuid` | PK | |
| `tenant_id` | `uuid` | NOT NULL | |
| `request_code` | `text` | NOT NULL | Human-facing, immutable (`CON-DAT-008`) |
| `type_id` | `uuid` | NOT NULL, FK RESTRICT | |
| `group_id` | `uuid` | FK RESTRICT → `request_group` | Multi-project work (`INV-ASM-06`) |
| `title` | `text` | NOT NULL | |
| `change_description` | `text` | NOT NULL | Minimum length enforced in domain |
| `change_references` | `text[]` | NOT NULL DEFAULT '{}' | Change management identifiers |
| `revision_reference` | `text` | | Commit or release tested (`PRD-PTR-014`) |
| `in_scope_items` | `text[]` | NOT NULL DEFAULT '{}' | |
| `out_of_scope_items` | `text[]` | NOT NULL DEFAULT '{}' | Protective for both parties (`PRD-PTR-013`) |
| `prohibited_actions` | `text[]` | NOT NULL DEFAULT '{}' | |
| `exposure_level` | `text` | NOT NULL | Classification inputs (`PRD-PTR-015`) |
| `criticality_tier_id` | `uuid` | NOT NULL, FK RESTRICT | |
| `handles_personal_data` | `bool` | NOT NULL | |
| `personal_data_volume_band` | `text` | | |
| `handles_payment_data` | `bool` | NOT NULL | |
| `has_ai_component` | `bool` | NOT NULL | |
| `authz_changed` | `bool` | NOT NULL | Highest-value effort signal (`PRD-PTR-015`) |
| `has_third_party_integration` | `bool` | NOT NULL | |
| `is_vendor_owned` | `bool` | NOT NULL | |
| `platform_types` | `text[]` | NOT NULL | Drives checklist selection |
| `tech_stack` | `text[]` | NOT NULL DEFAULT '{}' | |
| `api_count_declared` | `int` | CHECK `>= 0` | |
| `api_count_derived` | `int` | | From the uploaded collection (`PRD-PTR-007`) |
| `api_count_new` | `int` | CHECK `>= 0` | |
| `api_count_changed` | `int` | CHECK `>= 0` | |
| `screen_count` | `int` | CHECK `>= 0` | |
| `role_count_declared` | `int` | CHECK `>= 0` | |
| `git_repo_urls` | `text[]` | NOT NULL DEFAULT '{}' | **Reference labels only** (ADR-025) |
| `readiness` | `jsonb` | NOT NULL DEFAULT '{}' | Attestation items (`INV-ASM-04`) |
| `readiness_complete` | `bool` | NOT NULL DEFAULT false | Derived; stored per P6 |
| `requested_start_date` | `date` | | |
| `required_completion_date` | `date` | | Capacity planning depends on it (`PRD-PTR-008`) |
| `golive_date` | `date` | | |
| `is_golive_blocking` | `bool` | NOT NULL DEFAULT false | Separately permissioned to set |
| `technical_poc_id`, `business_poc_id`, `oncall_id`, `escalation_id` | `uuid` | | `PRD-PTR-023` |
| `oncall_phone` | `text` | | |
| `priority_score` | `int` | | Derived, deterministic (`INV-ASM-08`) |
| `estimated_effort_days` | `numeric(6,2)` | | Derived |
| `estimation_model_version` | `int` | | Reproducibility |
| `estimation_confidence` | `text` | | `PRD-RSK-040` |
| `feasible_start_date` | `date` | | Derived from backlog (`PRD-CAP-010`) |
| `readiness_score` | `int` | CHECK 0–100 | Triage aid |
| `applicable_checklist_ids` | `uuid[]` | NOT NULL DEFAULT '{}' | Auto-selected |
| `activated_track_ids` | `uuid[]` | NOT NULL DEFAULT '{}' | Specialist tracks |
| `state_id` | `uuid` | NOT NULL, FK RESTRICT → `workflow_state` | |
| `workflow_definition_version` | `int` | NOT NULL | Pinned at creation (`INV-WRK-01`) |
| `attributes` | `jsonb` | NOT NULL DEFAULT '{}' | Tenant custom fields |
| `duplicate_of_request_id` | `uuid` | FK RESTRICT | Set on merge (`PRD-PTR-020`) |
| Scope descriptor columns | | | §6.6, **immutable after submission** (`INV-ASM-07`) |
| Common columns | | | §6.1 |

**Constraints.**

```sql
UNIQUE (tenant_id, request_code)
CHECK (required_completion_date IS NULL OR requested_start_date IS NULL
       OR required_completion_date >= requested_start_date)
CHECK (golive_date IS NULL OR required_completion_date IS NULL
       OR required_completion_date <= golive_date)
CHECK (api_count_declared IS NULL OR api_count_new IS NULL OR api_count_changed IS NULL
       OR api_count_new + api_count_changed <= api_count_declared)
CHECK (NOT handles_personal_data OR personal_data_volume_band IS NOT NULL)
CHECK (duplicate_of_request_id IS NULL OR duplicate_of_request_id <> id)
```

**On the date ordering constraints.** These are cheap and catch a common data-entry error that would otherwise produce a negative-duration service level clock. The `required_completion_date <= golive_date` check is the one that matters operationally: a completion date after go-live is either a mistake or an admission that the assessment will not inform the release.

**Invariant mapping.** `INV-ASM-01` `DOMAIN` — scope re-validation is authorization (`SEC-AUZ-017`); the engine cannot express "within the submitting principal's authorized scope". `INV-ASM-02` `DOMAIN` — the two-accounts-per-role rule is a set assertion over a child table, evaluated at the accept transition. `INV-ASM-03` `DB` — no credential column exists; only `credential_ref` on the child table. `INV-ASM-04` `BOTH` — `readiness_complete` column plus a domain guard on the accept transition. `INV-ASM-05` `DOMAIN` — depends on environment rows. `INV-ASM-06` `DB` — a single scope descriptor makes multiple project scopes unrepresentable. `INV-ASM-07` `DB` — a trigger rejecting any update to scope descriptor columns. `INV-ASM-08` `DOMAIN`. `INV-ASM-09` `DOMAIN`.

| Index | Columns | Serves |
|---|---|---|
| `pk_assessment_request` | `(id)` | Request fetch |
| `ux_assessment_request__code` | UNIQUE `(tenant_id, request_code)` | Lookup by the code quoted in correspondence — the most common human-initiated lookup |
| `ix_asm_req__triage_queue` | `(tenant_id, state_id, submitted_at)` WHERE state not terminal | **The intake triage queue**, oldest first (`PRD-DSH-010`) |
| `ix_asm_req__unassigned` | `(tenant_id, submitted_at)` WHERE `assignee_id IS NULL` AND state not terminal | The unassigned queue — the first table on the operations dashboard |
| `ix_asm_req__scope_subtree` | `(tenant_id, scope_ancestor_path)` GIN ⚙ | Requests within a subtree — the engineering owner's and business owner's views |
| `ix_asm_req__requester` | `(tenant_id, created_by, created_at DESC)` | "My requests" — the requester's only view, and the largest user population's landing page |
| `ix_asm_req__golive_pressure` | `(tenant_id, golive_date)` WHERE `is_golive_blocking` AND state not terminal | Go-live blocking work by date — the escalation view |
| `ix_asm_req__due` | `(tenant_id, required_completion_date)` WHERE state not terminal | Forward service level exposure (`PRD-DSH-007`) |
| `ix_asm_req__group` | `(tenant_id, group_id)` WHERE not null | Sibling requests in a group |

**Partitioning.** None — below Medium volumes at every profile.

### 12.3 `assessment_request_role_account`

Separate table because `INV-ASM-02` is a set assertion and because credential references require their own access control and audit.

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `request_id` | `uuid` | NOT NULL, FK RESTRICT |
| `role_name` | `text` | NOT NULL |
| `role_description` | `text` | Expected permissions, so the tester knows what constitutes a violation |
| `username` | `text` | NOT NULL |
| `credential_ref` | `text` | NOT NULL — **vault reference; never a value** (`INV-ASM-03`) |
| `mfa_enrolled` | `bool` | NOT NULL |
| `mfa_bypass_ref` | `text` | Vault reference or contact instruction |
| `tenant_or_org_context` | `text` | For multi-tenant targets |
| `expected_permissions` | `text[]` | NOT NULL DEFAULT '{}' |
| `account_status` | `text` | NOT NULL — `PROVIDED`, `VERIFIED`, `EXPIRED`, `LOCKED`, `INVALID` |
| `verified_at`, `verified_by` | | Pre-engagement verification (`PRD-PTR-022`) |
| `rotation_required` | `bool` | NOT NULL DEFAULT false | Set at closure (`INV-ASM-29`) |
| `rotation_attested_at`, `rotation_attested_by` | | |

**Constraints.**

```sql
UNIQUE (tenant_id, request_id, role_name, username)
CHECK (length(credential_ref) > 0)
CHECK (credential_ref NOT LIKE '%:%' OR credential_ref LIKE 'vault:%')
```

**On the second `CHECK`.** A crude but effective guard: it rejects a value that looks like an inline credential rather than a vault reference. It cannot prevent a determined mistake, but it catches the common one — a developer pasting a password into the field during integration testing and the value reaching production. `INV-ASM-03` is enforced in the domain; this is defence in depth on the single most sensitive field in the intake surface.

**Invariant mapping.** `INV-ASM-02` `DOMAIN` — evaluated at the accept transition. `INV-ASM-03` `BOTH`. `INV-ASM-28` `DOMAIN` — reveal authorization. `INV-ASM-29` `BOTH` — `rotation_required` column plus a domain guard on closure.

| Index | Columns | Serves |
|---|---|---|
| `ix_asm_role_acct__request` | `(tenant_id, request_id, role_name)` | All accounts for a request, grouped by role — the accept-transition count check and the tester's working view |
| `ix_asm_role_acct__verification_due` | `(tenant_id, account_status, request_id)` WHERE status IN (`PROVIDED`,`EXPIRED`,`LOCKED`) | The pre-engagement verification job (`PRD-PTR-022`) |
| `ix_asm_role_acct__rotation_due` | `(tenant_id, request_id)` WHERE `rotation_required` AND `rotation_attested_at IS NULL` | **Outstanding credential rotations** — the queue that prevents test accounts outliving their engagement |

**Retention.** `credential_ref` is nulled once rotation is attested, so a closed engagement retains the account record without a live vault reference. The vault entry itself is destroyed by the rotation process. This is a deliberate divergence from "retain everything": a dangling reference to a destroyed secret is misleading, and retaining the reference invites a reveal attempt that will fail confusingly.

### 12.4 `assessment_request_environment`

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id`, `request_id` | | FK RESTRICT |
| `env_type` | `text` | NOT NULL — `UAT`, `STAGING`, `PREPROD`, `PROD_READONLY` |
| `base_url` | `text` | NOT NULL |
| `protective_control_present` | `bool` | NOT NULL — `PRD-PTR-016` |
| `protective_control_vendor` | `text` | |
| `bypass_arranged` | `bool` | NOT NULL DEFAULT false |
| `bypass_method` | `text` | |
| `rate_limit_present` | `bool` | NOT NULL |
| `rate_limit_threshold` | `text` | |
| `data_destruction_allowed` | `bool` | NOT NULL |
| `db_reset_available` | `bool` | NOT NULL |
| `db_reset_procedure` | `text` | |
| `vpn_required` | `bool` | NOT NULL |
| `vpn_access_procedure` | `text` | |
| `test_window_constraints` | `text` | |
| `monitoring_suppression_arranged` | `bool` | NOT NULL DEFAULT false |

**Constraint.** `CHECK (NOT protective_control_present OR bypass_arranged)` enforces `INV-ASM-05` at the row level.

**On enforcing this as a database constraint.** It is the highest-return field pair in the intake surface: a protective control between the tester and the target produces a test of the control, and discovering it on day one costs the engagement two days. Making it unrepresentable rather than merely validated means it cannot be bypassed by an API client or a migration.

| Index | Columns | Serves |
|---|---|---|
| `ix_asm_req_env__request` | `(tenant_id, request_id)` | Environments for a request |

### 12.5 `request_group`

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `group_code` | `text` | NOT NULL, unique per tenant |
| `title` | `text` | NOT NULL |
| `coordinating_principal_id` | `uuid` | |

Index `ix_request_group__code UNIQUE (tenant_id, group_code)`.

### 12.6 `assessment`

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id`, `tenant_id` | | | |
| `type_id` | `uuid` | NOT NULL, FK RESTRICT | |
| `request_id` | `uuid` | FK RESTRICT | Null ⇒ initiated without intake |
| `revision_reference` | `text` | | |
| `payload` | `jsonb` | NOT NULL DEFAULT '{}' | Per type schema |
| `coverage_items_total` | `int` | NOT NULL DEFAULT 0 | Derived; stored per P6 |
| `coverage_items_assessed` | `int` | NOT NULL DEFAULT 0 | |
| `coverage_items_not_applicable` | `int` | NOT NULL DEFAULT 0 | |
| `coverage_items_not_assessed` | `int` | NOT NULL DEFAULT 0 | |
| `coverage_ratio` | `numeric(5,4)` | | |
| `incompleteness_acknowledged` | `bool` | NOT NULL DEFAULT false | `INV-ASM-12` |
| `incompleteness_reason` | `text` | | |
| `outcome` | `text` | | Type-specific verdict |
| `state_id` | `uuid` | NOT NULL, FK RESTRICT | |
| `workflow_definition_version` | `int` | NOT NULL | |
| `effort_derived_days` | `numeric(6,2)` | | From state duration (`INV-ASM-16`) |
| `effort_manual_adjustment_days` | `numeric(6,2)` | | Separate column, never overwriting |
| `started_at`, `completed_at` | `timestamptz` | | |
| Scope descriptor columns | | | §6.6, from **asset ownership** (`INV-ASM-10`) |
| Common columns | | | §6.1 |

**Constraints.**

```sql
CHECK (coverage_items_assessed + coverage_items_not_applicable
       + coverage_items_not_assessed <= coverage_items_total)
CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
CHECK (NOT incompleteness_acknowledged OR incompleteness_reason IS NOT NULL)
```

**Invariant mapping.** `INV-ASM-10` `DOMAIN` — scope derived from scoped assets, which is a set operation. `INV-ASM-11` `BOTH` — coverage columns are maintained by the domain and cannot be set directly through the API; the sum constraint catches arithmetic error. `INV-ASM-12` `DOMAIN` + the acknowledgement constraint. `INV-ASM-13` `DB` — enforced on `checklist_item_result` (§12.9). `INV-ASM-14` `DB` — conditions are a separate table with independent lifecycle. `INV-ASM-15` `DB` — no finding column exists. `INV-ASM-16` `DB` — two separate effort columns.

| Index | Columns | Serves |
|---|---|---|
| `pk_assessment` | `(id)` | Fetch |
| `ix_assessment__state` | `(tenant_id, state_id, started_at)` WHERE state not terminal | In-progress assessments — the practitioner queue |
| `ix_assessment__assignee` | `(tenant_id, state_id)` — via `assessment_assignment` | See §12.7 |
| `ix_assessment__scope_subtree` | `(tenant_id, scope_ancestor_path)` GIN ⚙ | Assessments within a subtree |
| `ix_assessment__request` | `(tenant_id, request_id)` WHERE not null | The assessment for a request |
| `ix_assessment__type_completed` | `(tenant_id, type_id, completed_at DESC)` WHERE completed | Assessment history by type — calibration data for effort estimation (`PRD-RSK-039`) |
| `ix_assessment__low_coverage` | `(tenant_id, coverage_ratio)` WHERE completed AND `coverage_ratio < 1` | **Completed assessments with incomplete coverage** — the PP-1 reporting queue |

**On `ix_assessment__low_coverage`.** This index exists to serve a query the platform must be able to run and that most tools cannot: *which completed assessments did not cover everything they should have*. Without it the coverage columns are recorded and never examined.

### 12.7 `assessment_scope` and `assessment_assignment`

```sql
assessment_scope (
  id, tenant_id,
  assessment_id uuid NOT NULL FK RESTRICT,
  asset_id      uuid NOT NULL,                    -- no FK: cross-module (CON-DAT-004)
  UNIQUE (tenant_id, assessment_id, asset_id)
)

assessment_assignment (
  id, tenant_id,
  assessment_id uuid NOT NULL FK RESTRICT,
  principal_id  uuid NOT NULL,
  role          text NOT NULL CHECK IN ('LEAD','SUPPORT','REVIEWER','SHADOW'),
  allocated_effort_days numeric(6,2),
  UNIQUE (tenant_id, assessment_id, principal_id, role)
)
```

| Index | Columns | Serves |
|---|---|---|
| `ix_assessment_scope__asset` | `(tenant_id, asset_id)` | "Which assessments covered this asset" — the asset view and retest scoping |
| `ix_assessment_assign__principal` | `(tenant_id, principal_id, role)` | **"My assessments"** — the practitioner's personal queue, and the capacity allocation source |
| `ux_assessment_assign__lead` | UNIQUE `(tenant_id, assessment_id)` WHERE role = `LEAD` | Exactly one lead per assessment; supporters are additional |

### 12.8 `assessment_condition`

Independent lifecycle is the point (`INV-ASM-14`).

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `assessment_id` | `uuid` | NOT NULL, FK RESTRICT |
| `description` | `text` | NOT NULL |
| `owner_principal_id` | `uuid` | |
| `due_date` | `date` | |
| `state` | `text` | NOT NULL — `OPEN`, `CLOSED`, `WAIVED` |
| `closed_at`, `closed_by`, `closure_note` | | |
| `work_item_id` | `uuid` | Cross-module reference, no FK |

| Index | Columns | Serves |
|---|---|---|
| `ix_asm_condition__open` | `(tenant_id, due_date)` WHERE state = `OPEN` | **Open conditions by due date** — the queue that prevents conditional approval becoming unconditional |
| `ix_asm_condition__assessment` | `(tenant_id, assessment_id)` | Conditions on an assessment |
| `ix_asm_condition__owner` | `(tenant_id, owner_principal_id)` WHERE state = `OPEN` | "Conditions assigned to me" |

### 12.9 Checklists

```sql
checklist_definition (
  id, tenant_id, code, label_i18n,
  version        int  NOT NULL,                   -- immutable once published (INV-ASM-17)
  applicability  jsonb NOT NULL,
  state          text NOT NULL CHECK IN ('DRAFT','PUBLISHED','DEPRECATED'),
  published_at   timestamptz,
  UNIQUE (tenant_id, code, version)
)

checklist_item (
  id, tenant_id,
  definition_id  uuid NOT NULL FK RESTRICT,
  group_code     text NOT NULL,                   -- domain grouping
  item_code      text NOT NULL,
  statement      text NOT NULL,
  guidance       text,
  is_mandatory   bool NOT NULL,
  display_order  int  NOT NULL,
  UNIQUE (tenant_id, definition_id, item_code)
)

checklist_instance (
  id, tenant_id,
  assessment_id      uuid NOT NULL FK RESTRICT,
  definition_id      uuid NOT NULL FK RESTRICT,
  definition_version int  NOT NULL,               -- pinned (INV-ASM-18)
  completed_at       timestamptz,
  UNIQUE (tenant_id, assessment_id, definition_id)
)

checklist_item_result (
  id, tenant_id,
  instance_id  uuid NOT NULL FK RESTRICT,
  item_id      uuid NOT NULL FK RESTRICT,
  outcome      text NOT NULL
                 CHECK IN ('PASS','FAIL','NOT_APPLICABLE','NOT_ASSESSED'),
  reason       text,
  assessed_by  uuid,
  assessed_at  timestamptz,
  UNIQUE (tenant_id, instance_id, item_id),
  CHECK (outcome <> 'NOT_APPLICABLE' OR (reason IS NOT NULL AND length(reason) > 0))
)
```

**On the final `CHECK`.** It enforces `INV-ASM-13` at the row level: an unreasoned exclusion is `NOT_ASSESSED`, not `NOT_APPLICABLE`. This is the constraint that prevents coverage being inflated under deadline by marking inconvenient items as inapplicable — the path of least resistance, and the one that makes assessment coverage meaningless.

**Publication immutability.** A trigger rejects any update to `checklist_definition` or `checklist_item` where the definition's state is `PUBLISHED`, enforcing `INV-ASM-17` as `DB`.

| Index | Columns | Serves |
|---|---|---|
| `ix_checklist_item__definition` | `(tenant_id, definition_id, group_code, display_order)` | Ordered item list for an instance — the assessment working interface |
| `ix_checklist_result__instance` | `(tenant_id, instance_id)` | All results for an instance — coverage computation |
| `ix_checklist_result__not_assessed` | `(tenant_id, instance_id)` WHERE outcome = `NOT_ASSESSED` | Outstanding items — the completion guard and the practitioner's remaining-work view |

### 12.10 `evidence`

`RESTRICTED` unconditionally (`INV-ASM-20`).

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `assessment_id` | `uuid` | FK RESTRICT |
| `finding_id` | `uuid` | Cross-module reference, no FK |
| `checklist_item_result_id` | `uuid` | FK RESTRICT |
| `storage_ref` | `text` | NOT NULL — object store reference. **No content in the database** |
| `declared_media_type` | `text` | NOT NULL |
| `verified_media_type` | `text` | Magic-byte verified |
| `byte_size` | `bigint` | NOT NULL |
| `content_hash` | `bytea` | NOT NULL |
| `malware_verdict` | `text` | NOT NULL — `PENDING`, `CLEAN`, `MALICIOUS`, `SCAN_FAILED` |
| `malware_scanner`, `malware_scanned_at` | | |
| `availability` | `text` | NOT NULL — `QUARANTINED`, `AVAILABLE`, `FLAGGED_AVAILABLE` |
| `original_filename` | `text` | Metadata only; server-generated name in `storage_ref` (`INV-ASM-23`) |
| `retention_until` | `timestamptz` | NOT NULL (`INV-ASM-24`) |
| `uploaded_by`, `uploaded_at` | | |

**Constraints.**

```sql
CHECK (availability <> 'AVAILABLE' OR malware_verdict = 'CLEAN')
CHECK (availability <> 'FLAGGED_AVAILABLE' OR malware_verdict = 'MALICIOUS')
CHECK (malware_verdict <> 'PENDING' OR availability = 'QUARANTINED')
UNIQUE (tenant_id, content_hash, assessment_id)
```

**On the availability constraints.** They make the three-state relationship structural: pending means quarantined, clean means available, malicious means flagged-available and never plain-available. `INV-ASM-21` requires flag-rather-than-delete, and the constraint set makes the intermediate states unrepresentable rather than merely discouraged.

**Invariant mapping.** `INV-ASM-20` `DB` — no classification column exists to lower; the classification is implicit in the table. `INV-ASM-21` `DB` — constraint set above. `INV-ASM-22` `DOMAIN` — export and context exclusion is enforced where those artifacts are produced. `INV-ASM-23` `DB` — `storage_ref` is server-generated; `original_filename` is not usable as a path. `INV-ASM-24` `DB` — `retention_until NOT NULL`.

| Index | Columns | Serves |
|---|---|---|
| `ix_evidence__assessment` | `(tenant_id, assessment_id)` | Evidence for an assessment |
| `ix_evidence__finding` | `(tenant_id, finding_id)` WHERE not null | Evidence supporting a finding — the dispute and retest path |
| `ix_evidence__quarantined` | `(tenant_id, uploaded_at)` WHERE availability = `QUARANTINED` | The scan backlog; alerts where an item is quarantined longer than expected |
| `ix_evidence__retention_due` | `(tenant_id, retention_until)` WHERE `retention_until < now()` — evaluated by the job | **Expired evidence for destruction** — bounded retention as a security control (`INV-ASM-24`) |

**Retention.** Destroyed on `retention_until`, subject to legal hold. Destruction removes the object-store content and marks the row rather than deleting it, so the fact that evidence existed remains auditable.

### 12.11 `external_assessor_grant` and `external_grant_object`

```sql
external_assessor_grant (
  id, tenant_id,
  principal_id     uuid NOT NULL,
  valid_from       timestamptz NOT NULL,
  valid_until      timestamptz NOT NULL,          -- mandatory (INV-ASM-26)
  state            text NOT NULL
                     CHECK IN ('PENDING_AGREEMENT','ACTIVE','REVOKED','EXPIRED'),
  revoked_by, revoked_at,
  CHECK (valid_until > valid_from)
)

external_grant_agreement (
  id, tenant_id, grant_id uuid NOT NULL FK CASCADE,
  agreement_code text NOT NULL, agreement_version int NOT NULL,
  accepted_at timestamptz, accepted_from_address text,
  UNIQUE (tenant_id, grant_id, agreement_code)
)

external_grant_object (
  id, tenant_id, grant_id uuid NOT NULL FK CASCADE,
  object_kind text NOT NULL, object_id uuid NOT NULL,
  UNIQUE (tenant_id, grant_id, object_kind, object_id)
)
```

**On `ON DELETE CASCADE` here.** A grant's agreements and object list have no meaning without the grant, and a grant row is never deleted — it expires. Cascade exists only for the offboarding path. Recorded as the third narrow exception to `CON-DAT-005`.

**Invariant mapping.** `INV-ASM-25` `DB` — grants reference objects explicitly; there is no scope column, so scope inheritance is unrepresentable. `INV-ASM-26` `DB` — `valid_until NOT NULL` with a maximum enforced in domain. `INV-ASM-27` `DOMAIN` — the agreement gate is checked at authorization time.

| Index | Columns | Serves |
|---|---|---|
| `ix_ext_grant__principal_active` | `(tenant_id, principal_id)` WHERE state = `ACTIVE` | **Authorization: the grant set for an external principal** — evaluated on every request they make |
| `ix_ext_grant__expiring` | `(tenant_id, valid_until)` WHERE state = `ACTIVE` | The automatic expiry job (`INV-ASM-26`) and the expiry-approaching alert |
| `ix_ext_grant_object__object` | `(tenant_id, object_kind, object_id)` | "Who has an external grant on this object" — access review |

---

## 13. Schema — Vulnerability Management

### 13.1 `severity_level`

Taxonomy table per §8.1. `ordinal` lower ⇒ more severe. Referenced by `finding` twice (reported and effective).

### 13.2 `finding`

The platform's central table.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id`, `tenant_id` | | | |
| `fingerprint_digest` | `bytea` | NOT NULL | §13.3 |
| `fingerprint_algorithm_version` | `int` | NOT NULL | `INV-VUL-03` |
| `finding_class` | `text` | NOT NULL | `CODE`, `DEPENDENCY`, `RUNTIME`, `INFRASTRUCTURE`, `SECRET`, `MANUAL`, `CONFIGURATION` |
| `asset_class` | `text` | NOT NULL | `APPLICATION`, `INFRASTRUCTURE`, `CLOUD`, `CONTAINER` — posture separation (`INV-ING-07`) |
| `title` | `text` | NOT NULL | |
| `description` | `text` | | |
| `reported_severity_id` | `uuid` | NOT NULL, FK RESTRICT | **Immutable** (`INV-VUL-07`) |
| `effective_severity_id` | `uuid` | NOT NULL, FK RESTRICT | Adjustable |
| `severity_adjusted_by`, `severity_adjusted_at` | | | |
| `severity_adjustment_reason` | `text` | | |
| `state_id` | `uuid` | NOT NULL, FK RESTRICT | |
| `workflow_definition_version` | `int` | NOT NULL | |
| `closure_reason` | `text` | | `FIXED_VERIFIED`, `FIXED_UNVERIFIED`, `NOT_APPLICABLE`, `FALSE_POSITIVE`, `DUPLICATE`, `RISK_ACCEPTED`, `ASSET_RETIRED` |
| `closure_verification_method` | `text` | | |
| `closure_verified_by`, `closed_by`, `closed_at` | | | |
| `closure_justification` | `text` | | |
| `closure_actor_type` | `text` | | `USER`, `SYSTEM` — distinguishes auto-close (`PRD-SBM-054`) |
| `assignee_id` | `uuid` | | Individual, never a group (`INV-VUL-15`) |
| `recurrence_count` | `int` | NOT NULL DEFAULT 0 | `INV-VUL-12` |
| `dispute_state` | `text` | | `NONE`, `RAISED`, `UPHELD`, `REJECTED` |
| `dispute_raised_by`, `dispute_raised_at`, `dispute_reason` | | | |
| `dispute_adjudicated_by`, `dispute_adjudicated_at` | | | |
| `source_tool` | `text` | NOT NULL | `PRD-VUL-004` |
| `source_tool_version` | `text` | | |
| `source_rule_id` | `text` | | |
| `import_session_id` | `uuid` | | Cross-module, no FK |
| `assessment_id` | `uuid` | | Cross-module, no FK |
| `first_detected_at` | `timestamptz` | NOT NULL | |
| `last_detected_at` | `timestamptz` | NOT NULL | |
| `resolved_at` | `timestamptz` | | |
| Scope descriptor columns | | | §6.6, from affected asset ownership |
| Common columns | | | §6.1 |

**Constraints.**

```sql
UNIQUE (tenant_id, fingerprint_digest, fingerprint_algorithm_version)
CHECK (closure_reason IS NULL OR closed_at IS NOT NULL)
CHECK (closure_reason <> 'FIXED_VERIFIED'
       OR (closure_verification_method IS NOT NULL AND closure_verified_by IS NOT NULL))
CHECK (finding_class <> 'SECRET' OR closure_reason IS DISTINCT FROM 'RISK_ACCEPTED')
CHECK (last_detected_at >= first_detected_at)
CHECK (recurrence_count >= 0)
```

**On the third `CHECK`.** `INV-VUL-11` requires a verification record for `FIXED_VERIFIED`. Making it a constraint means the strongest closure claim cannot be recorded without its evidence, which matters because that reason is the one reported as real remediation.

**On the fourth `CHECK`.** `INV-VUL-16` and `INV-VUL-28` prohibit risk acceptance for secret findings. Enforcing it in the engine means it survives an API defect, a bulk operation, and a migration. A leaked credential accepted as risk is a live exposure with an approval attached; the constraint makes it unrepresentable.

**Invariant mapping.** `INV-VUL-07` `DB` — a trigger rejecting `reported_severity_id` update. `INV-VUL-08` `DOMAIN` — "at least one impact" is a set assertion; a deferred constraint trigger is possible and rejected as too expensive on the highest-volume write path. `INV-VUL-09` `DOMAIN` — aggregate state derived from impacts. `INV-VUL-10` `DB`. `INV-VUL-11` `DB`. `INV-VUL-12` `DB` — `recurrence_count` plus a domain guard preventing new-record creation on a matching fingerprint. `INV-VUL-13` `DOMAIN` — coverage confirmation is match-run state. `INV-VUL-14` `DOMAIN`. `INV-VUL-15` `DB` — single `assignee_id` column. `INV-VUL-16` `DB`.

| Index | Columns | Kind | Serves |
|---|---|---|---|
| `pk_finding` | `(id)` | btree | Fetch |
| `ux_finding__fingerprint` | UNIQUE `(tenant_id, fingerprint_digest, fingerprint_algorithm_version)` | btree | **Deduplication on every ingestion** — the highest-frequency write-path lookup in the platform (`INV-VUL-01`) |
| `ix_finding__open_by_scope` | `(tenant_id, scope_node_id, effective_severity_id, id)` | btree, WHERE state not terminal | **The finding list**, the platform's most frequent read (`NFR-VUL-001`) |
| `ix_finding__scope_subtree` | `(tenant_id, scope_ancestor_path)` | GIN ⚙, WHERE state not terminal | Subtree-scoped finding reads without a closure join |
| `ix_finding__assignee` | `(tenant_id, assignee_id, effective_severity_id)` | btree, WHERE state not terminal | **"My findings"** — the practitioner and engineering owner landing page |
| `ix_finding__class_state` | `(tenant_id, finding_class, state_id)` | btree | Class-filtered reads; the secret-finding queue |
| `ix_finding__unassigned` | `(tenant_id, first_detected_at)` | btree, WHERE `assignee_id IS NULL` AND state not terminal | The unassigned queue |
| `ix_finding__closure_audit` | `(tenant_id, closure_reason, closed_at DESC)` | btree, WHERE closed | Closure reason reporting (`PRD-RSK-042`) and bulk-closure anomaly detection (`SEC-PLT-005`) |
| `ix_finding__auto_closed` | `(tenant_id, closed_at DESC)` | btree, WHERE `closure_actor_type = 'SYSTEM'` | Automatic closures for review — the guard against mass false-close going unnoticed |
| `ix_finding__recurrence` | `(tenant_id, recurrence_count DESC)` | btree, WHERE `recurrence_count > 0` | Recurring findings — fix quality reporting |
| `ix_finding__dispute` | `(tenant_id, dispute_raised_at)` | btree, WHERE `dispute_state = 'RAISED'` | The adjudication queue |
| `ix_finding__detection_window` | `(tenant_id, last_detected_at)` | btree, WHERE state not terminal | Findings not re-detected recently — staleness and coverage reporting |

**On `ix_finding__open_by_scope` ordering.** Scope first because every read is scope-filtered by authorization; severity second because it is the default sort; `id` last to make the index a covering path for keyset pagination, which `PRD-API-003` requires to be stable under concurrent modification.

**Partitioning.** None at Medium (300,000 rows). **Hash by `tenant_id`, 32 partitions, at ≥ 2,000,000 rows**, aligned with `finding_asset_impact` (`CON-DAT-024`).

**Retention.** No deletion. Archival of terminal findings older than the configured window to a cold partition; the fingerprint index retains terminal rows so that recurrence detection continues to work (`INV-VUL-12`) — a finding that recurs after five years must still reopen rather than appearing new.

### 13.3 `finding_fingerprint_input`

`INV-VUL-04` requires retaining the hashed inputs so that re-fingerprinting is possible without re-running the source tool.

| Column | Type | Notes |
|---|---|---|
| `finding_id` | `uuid` | PK, FK RESTRICT — one row per finding |
| `tenant_id` | `uuid` | NOT NULL |
| `algorithm_version` | `int` | NOT NULL |
| `inputs` | `jsonb` | NOT NULL — the values hashed, keyed by input name |

**Single-column primary key `finding_id`** — a one-to-one extension table, so it needs no independent identity. Fourth documented exception to `CON-DAT-006`, and the cheapest kind: the key is a foreign key.

**Why a separate table rather than a column on `finding`.** The inputs document is read only during re-fingerprinting, which is rare, and it is comparatively wide. Keeping it out of `finding` keeps the main table's rows narrow, which matters because `finding` is read constantly and `finding_fingerprint_input` almost never. At Extra large this is the difference between 8,000,000 wide rows and 8,000,000 narrow rows plus a rarely-touched side table.

**The storage cost, stated.** Roughly 200–400 bytes per finding. At Extra large, 2–3 GB. This is the price of being able to improve the fingerprint algorithm at all (DOC-01, `INV-VUL-04`), and without it the first algorithm version is permanent.

| Index | Columns | Serves |
|---|---|---|
| `pk_finding_fingerprint_input` | `(finding_id)` | Re-fingerprinting migration; fingerprint dispute investigation |
| `ix_fp_input__version` | `(tenant_id, algorithm_version)` | **Findings requiring re-fingerprinting after an algorithm change** — the migration driver |

### 13.4 `finding_asset_impact`

Inside the `Finding` aggregate boundary (DOC-03 §10.3), so it is written in the same transaction.

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `finding_id` | `uuid` | NOT NULL, FK RESTRICT |
| `asset_id` | `uuid` | NOT NULL — cross-module, no FK |
| `status` | `text` | NOT NULL — `OPEN`, `REMEDIATED`, `NOT_APPLICABLE`, `EXCEPTED` |
| `detected_version` | `text` | For dependency findings |
| `fixed_version` | `text` | |
| `location_hint` | `text` | Human-readable location; **not part of identity** |
| `first_detected_at`, `last_confirmed_at` | `timestamptz` | NOT NULL |
| `remediated_at` | `timestamptz` | |
| `remediation_work_item_id` | `uuid` | Cross-module, no FK |
| Scope descriptor columns | | §6.6 — **per impact**, because impacts may span owners (`INV-VUL-14`) |

**Constraints.** `UNIQUE (tenant_id, finding_id, asset_id)`. `CHECK (status <> 'REMEDIATED' OR remediated_at IS NOT NULL)`.

**On per-impact scope descriptors.** A dependency finding affecting services in three business units has three impacts with three descriptors. The finding's own descriptor is the union; each impact carries its own so that a business unit sees exactly its own impact and not the others. Without per-impact scope, either all three units see all three impacts, or none sees any.

| Index | Columns | Serves |
|---|---|---|
| `ux_finding_impact__pair` | UNIQUE `(tenant_id, finding_id, asset_id)` | Upsert on re-detection; the finding detail view |
| `ix_finding_impact__asset_open` | `(tenant_id, asset_id, status)` WHERE status = `OPEN` | **"Open findings on this asset"** — the asset view, risk aggregation, and the intake warning of `PRD-PTR-025` |
| `ix_finding_impact__scope_subtree` | `(tenant_id, scope_ancestor_path)` GIN ⚙, WHERE status = `OPEN` | Per-impact subtree filtering |
| `ix_finding_impact__work_item` | `(tenant_id, remediation_work_item_id)` WHERE not null | Reverse link from remediation work to the impacts it addresses |

**Partitioning.** Hash by `tenant_id`, aligned with `finding` (`CON-DAT-024`), at the same trigger volume.

### 13.5 `finding_secret_detail`

Separate table for `SECRET` class findings, because the secret value requires field-level encryption and must not widen the main table.

| Column | Type | Notes |
|---|---|---|
| `finding_id` | `uuid` | PK, FK RESTRICT |
| `tenant_id` | `uuid` | NOT NULL |
| `secret_type_id` | `uuid` | NOT NULL, FK RESTRICT → `secret_type` registry |
| `secret_value_encrypted` | `bytea` | **Encrypted with tenant key material** (`SEC-TEN-014`) |
| `secret_value_masked` | `text` | NOT NULL — partial mask for display (`INV-VUL-19`) |
| `secret_digest` | `bytea` | NOT NULL — identifies recurrence without the value |
| `validity_state` | `text` | NOT NULL — `UNCHECKED`, `VALID`, `INVALID`, `CHECK_FAILED`, `CHECK_PROHIBITED` |
| `validity_checked_at` | `timestamptz` | |
| `rotation_state` | `text` | NOT NULL — `PENDING`, `ROTATED`, `REVOKED` |
| `rotation_attested_at`, `rotation_attested_by` | | |

**Constraints.** `CHECK (length(secret_value_masked) > 0)`. `CHECK (rotation_state <> 'ROTATED' OR rotation_attested_at IS NOT NULL)`.

**Invariant mapping.** `INV-VUL-19` `BOTH` — the risk-acceptance prohibition is on `finding` (§13.2); masked display and encryption are here.

| Index | Columns | Serves |
|---|---|---|
| `pk_finding_secret_detail` | `(finding_id)` | Detail fetch on reveal |
| `ix_secret__validity` | `(tenant_id, validity_state)` WHERE state = `VALID` | **Confirmed-live leaked credentials** — the highest-urgency queue in the platform (one-business-day service level, DOC-28 §11.2) |
| `ix_secret__rotation_pending` | `(tenant_id, finding_id)` WHERE `rotation_state = 'PENDING'` | Outstanding rotations |

**Retention.** `secret_value_encrypted` is nulled once rotation is attested. The digest and masked form remain so that recurrence detection and history survive without retaining the credential. This is the same reasoning as §12.3: retaining a live secret after its remediation is an accumulating liability, not conservatism.

### 13.6 `vulnerability_intelligence` — non-tenant-scoped

The single shared domain table (`INV-VUL-17`, `SEC-TEN-024`). **No `tenant_id`, no row-level policy, read-only to tenants.**

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` | PK |
| `vulnerability_ref` | `text` | NOT NULL, UNIQUE — e.g. a CVE identifier |
| `title`, `summary` | `text` | |
| `weakness_refs` | `text[]` | NOT NULL DEFAULT '{}' |
| `severity_scores` | `jsonb` | NOT NULL — scheme, vector, value per scheme |
| `exploit_prediction` | `numeric(6,5)` | CHECK 0–1 |
| `exploit_prediction_percentile` | `numeric(6,5)` | |
| `known_exploited` | `bool` | NOT NULL DEFAULT false |
| `known_exploited_since` | `date` | |
| `published_at`, `modified_at` | `timestamptz` | |
| `intelligence_version` | `bigint` | NOT NULL — source dataset version |
| `retrieved_at` | `timestamptz` | NOT NULL (`INV-VUL-18`) |

**Constraint enforcing the boundary.** A comment is insufficient; the prohibition in `SEC-TEN-024` is enforced by a schema test asserting this table has no `tenant_id` column, no column referencing a tenant-scoped table, and no column whose name suggests prevalence or usage. **The test is the control**, because the risk (`RISK-PLT-004`) is a well-meaning future addition that breaks a property-based safety argument.

| Index | Columns | Serves |
|---|---|---|
| `ux_vuln_intel__ref` | UNIQUE `(vulnerability_ref)` | Enrichment lookup by identifier |
| `ix_vuln_intel__kev` | `(known_exploited_since DESC)` WHERE `known_exploited` | **Newly known-exploited vulnerabilities** — the trigger for a priority sweep (`PRD-SBM-046`) |
| `ix_vuln_intel__modified` | `(modified_at DESC)` | Changed-since-last-sweep detection |
| `ix_vuln_intel__version` | `(intelligence_version)` | Provisioning reconciliation and staleness reporting |

```sql
vulnerability_affected_range (
  id,
  vulnerability_id uuid NOT NULL FK CASCADE,      -- reference data; cascade is correct here
  ecosystem        text NOT NULL,
  package_name     text NOT NULL,
  introduced       text,                          -- version-scheme-specific, compared per ecosystem
  fixed            text,
  last_affected    text,
  range_kind       text NOT NULL,
  distro_qualifier text                           -- backported patch metadata (PRD-SBM-040)
)
```

| Index | Columns | Serves |
|---|---|---|
| `ix_vuln_range__lookup` | `(ecosystem, package_name)` | **The matching lookup** — executed once per component per match run, so at Extra large this is tens of millions of lookups per sweep. The single most performance-critical index in the platform |

**On that index.** `NFR-SBM-002` requires a portfolio sweep in 4 hours at Medium and 12 at Extra large. At 80,000,000 component entries this index determines whether that is achievable. It is the reason `vulnerability_affected_range` is denormalized to carry `ecosystem` and `package_name` directly rather than joining through `vulnerability_intelligence`.

### 13.7 `finding_vulnerability_ref` and `finding_weakness_ref`

```sql
finding_vulnerability_ref (
  id, tenant_id,
  finding_id       uuid NOT NULL FK RESTRICT,
  vulnerability_ref text NOT NULL,                -- no FK: crosses the tenant boundary (SEC-TEN-025)
  UNIQUE (tenant_id, finding_id, vulnerability_ref)
)
```

**On the absence of a foreign key to `vulnerability_intelligence`.** `SEC-TEN-025` requires the association between a tenant's finding and an intelligence record to live in the tenant's partition. A foreign key would be technically possible — the intelligence table is not tenant-scoped — and is omitted for a different reason: a tenant may hold a reference to an identifier the platform has not yet ingested, and rejecting the finding because enrichment is unavailable would discard data. The reference is stored as text and resolved when available.

| Index | Columns | Serves |
|---|---|---|
| `ix_finding_vuln__ref` | `(tenant_id, vulnerability_ref)` | **"Which of our findings relate to this vulnerability"** — the disclosure-response query (JTBD-06) |
| `ix_finding_vuln__finding` | `(tenant_id, finding_id)` | Enrichment for a finding |

### 13.8 `finding_suppression`

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `fingerprint_digest` | `bytea` | NOT NULL — fingerprint-scoped, never pattern-scoped (`INV-VUL-20`) |
| `fingerprint_algorithm_version` | `int` | NOT NULL |
| `scope_kind` | `text` | NOT NULL — `FINDING`, `CLASS_IN_SCOPE`, `ASSET` |
| `scope_node_id`, `scope_asset_id` | `uuid` | Per `scope_kind` |
| `reason` | `text` | NOT NULL — `FALSE_POSITIVE`, `ACCEPTED_PATTERN`, `TEST_CODE`, `NOT_REACHABLE` |
| `justification` | `text` | NOT NULL |
| `expires_at` | `timestamptz` | **NOT NULL** (`INV-VUL-21`) |
| `revalidation_due_at` | `timestamptz` | |
| `state` | `text` | NOT NULL — `ACTIVE`, `EXPIRED`, `REVOKED` |

**Constraints.** `CHECK (length(justification) > 0)`. `CHECK (expires_at > created_at)`. `CHECK (scope_kind <> 'ASSET' OR scope_asset_id IS NOT NULL)`.

**On `expires_at NOT NULL`.** The same reasoning as exception expiry: a suppression correct today may be wrong after the code changes, and a non-expiring suppression is a permanent blind spot. Making the column non-nullable means an indefinite suppression is unrepresentable rather than discouraged.

| Index | Columns | Serves |
|---|---|---|
| `ix_suppression__fingerprint` | `(tenant_id, fingerprint_digest, fingerprint_algorithm_version)` WHERE state = `ACTIVE` | **Suppression check on every ingestion** — must be fast because it runs per candidate finding |
| `ix_suppression__expiring` | `(tenant_id, expires_at)` WHERE state = `ACTIVE` | The expiry job and the revalidation queue |
| `ix_suppression__creator` | `(tenant_id, created_by, created_at)` | Suppression rate per principal — gaming detection (`PRD-RSK-041`) |

---

## 14. Schema — Exceptions

### 14.1 `risk_exception`

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `exception_code` | `text` | NOT NULL, unique per tenant — quoted in audit correspondence |
| `subject_kind` | `text` | NOT NULL — `FINDING`, `FINDING_CLASS_IN_SCOPE`, `ASSET` |
| `subject_finding_id`, `subject_asset_id` | `uuid` | Cross-module, no FK |
| `subject_class`, `subject_scope_node_id` | | For class-in-scope |
| `justification` | `text` | NOT NULL |
| `no_controls_declared` | `bool` | NOT NULL DEFAULT false | `INV-VUL-25` |
| `no_controls_approved_by`, `no_controls_approved_at` | | | |
| `expires_at` | `timestamptz` | **NOT NULL** (`INV-VUL-23`) |
| `max_duration_days_at_creation` | `int` | NOT NULL — the bound in force when created, retained for audit |
| `review_cadence_days` | `int` | |
| `next_review_at` | `timestamptz` | |
| `state` | `text` | NOT NULL — `REQUESTED`, `APPROVED`, `ACTIVE`, `EXPIRED`, `REVOKED`, `RENEWED` |
| `renewed_from_exception_id` | `uuid` | FK RESTRICT |
| `requested_by`, `requested_at` | | |
| `revoked_by`, `revoked_at`, `revocation_reason` | | |
| `auto_reopened_at` | `timestamptz` | Set when expiry reopened the subject (`INV-VUL-24`) |
| Scope descriptor columns | | §6.6 |

**Constraints.**

```sql
UNIQUE (tenant_id, exception_code)
CHECK (expires_at > created_at)
CHECK (length(justification) > 0)
CHECK (NOT no_controls_declared OR no_controls_approved_by IS NOT NULL)
CHECK (subject_kind <> 'FINDING' OR subject_finding_id IS NOT NULL)
CHECK (renewed_from_exception_id IS NULL OR renewed_from_exception_id <> id)
```

**On retaining `max_duration_days_at_creation`.** The configured maximum may change. Auditing whether an exception was within bounds requires knowing the bound that applied when it was granted, not the current one. Without this column, a later relaxation of the maximum makes historical exceptions appear compliant that were not — and a later tightening makes compliant ones appear to have been violations.

**Invariant mapping.** `INV-VUL-23` `DB` — `expires_at NOT NULL`; the maximum bound is `DOMAIN` because it is tenant configuration. `INV-VUL-24` `DOMAIN` — auto-reopen is a scheduled process. `INV-VUL-25` `DB`. `INV-VUL-26` `DB` — enforced on the approval table (§14.2). `INV-VUL-27` `DOMAIN` — projection inclusion. `INV-VUL-28` `DB` — enforced on `finding` (§13.2). `INV-VUL-29` `DOMAIN` — scope-dependent approval authority and maximum.

| Index | Columns | Serves |
|---|---|---|
| `ux_risk_exception__code` | UNIQUE `(tenant_id, exception_code)` | Lookup by code |
| `ix_risk_exception__expiring` | `(tenant_id, expires_at)` WHERE state = `ACTIVE` | **The expiry and auto-reopen job**, and the expiry-approaching notification |
| `ix_risk_exception__review_due` | `(tenant_id, next_review_at)` WHERE state = `ACTIVE` AND not null | The periodic review queue (`INV-VUL-06`… `PRD-EXC-006`) |
| `ix_risk_exception__subject_finding` | `(tenant_id, subject_finding_id)` WHERE not null | "Is this finding excepted" — evaluated on the finding view and in posture aggregation |
| `ix_risk_exception__scope_subtree` | `(tenant_id, scope_ancestor_path)` GIN ⚙ | **The exception register by scope** — the most requested audit artifact (`PRD-EXC-010`) |
| `ix_risk_exception__approval_queue` | `(tenant_id, requested_at)` WHERE state = `REQUESTED` | The approval queue |

### 14.2 `risk_exception_approval`

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `exception_id` | `uuid` | NOT NULL, FK RESTRICT |
| `approver_id` | `uuid` | NOT NULL |
| `approval_level` | `text` | NOT NULL — `STANDARD`, `ELEVATED` |
| `decision` | `text` | NOT NULL — `APPROVED`, `REJECTED` |
| `decided_at` | `timestamptz` | NOT NULL |
| `note` | `text` | |
| `step_up_authenticated` | `bool` | NOT NULL — `PRD-IAM-003` |

**Constraint enforcing separation of duties.**

```sql
-- INV-VUL-26 / SEC-AUZ-041: approver must differ from requester
CREATE FUNCTION check_exception_approver_distinct() ...  -- constraint trigger
```

A constraint trigger comparing `approver_id` against the parent exception's `requested_by`. **This is the second place where a trigger is preferred to domain-only enforcement** (the first was `INV-AST-12`), because self-approval is the control an auditor tests first and the one an insider would most plausibly attempt. Enforcing it in the engine means it survives an API defect, a bulk import, and a migration.

| Index | Columns | Serves |
|---|---|---|
| `ix_exc_approval__exception` | `(tenant_id, exception_id, decided_at)` | Approval history for an exception — the audit artifact |
| `ix_exc_approval__approver` | `(tenant_id, approver_id, decided_at DESC)` | Approval rate per principal — separation-of-duties review and gaming detection |

### 14.3 `risk_exception_compensating_control`

```sql
risk_exception_compensating_control (
  id, tenant_id,
  exception_id uuid NOT NULL FK RESTRICT,
  description  text NOT NULL,
  control_kind text NOT NULL,
  verified_at, verified_by,
  CHECK (length(description) > 0)
)
```

Index `ix_exc_control__exception (tenant_id, exception_id)`.

`INV-VUL-25` requires either a control or an explicit approved declaration that none exist; the declaration lives on the exception (§14.1), so the disjunction is `DOMAIN` with both sides constrained.

---

## 15. Schema — Composition Analysis

### 15.1 The largest table in the platform

`component_entry` reaches approximately 80,000,000 rows at Extra large (§3). Its design is dominated by row width, because at that volume every byte is 80 MB.

**The naive design** stores the package identifier, name, and version on every entry. The same identifier — a common logging library at a common version — appears in thousands of snapshots across a tenant, so the identifier text is stored thousands of times. At an average of 130 bytes for identifier plus name plus version, that is approximately 10 GB of duplicated text.

**The design used here** interns component identity into a `component` table and stores only a reference on the entry.

### 15.2 `component` — interned identity

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `purl_canonical` | `text` | NOT NULL — canonicalized per ecosystem (`PRD-SBM-036`) |
| `purl_original` | `text` | NOT NULL — retained so a canonicalization defect is correctable without resubmission (`PRD-SBM-035`) |
| `canonicalization_version` | `int` | NOT NULL |
| `ecosystem` | `text` | NOT NULL |
| `name` | `text` | NOT NULL |
| `version` | `text` | NOT NULL |
| `is_canonicalizable` | `bool` | NOT NULL — false ⇒ unmatchable (`PRD-SBM-037`) |
| `unmatchable_reason` | `text` | |

**Constraint.** `UNIQUE (tenant_id, purl_canonical, canonicalization_version)`.

**Why tenant-scoped rather than globally interned.** Global interning would be more space-efficient — a common library would be one row rather than one per tenant. It is rejected for two reasons.

The first is the tenant boundary. `SEC-TEN-024` requires the shared dataset to contain no tenant-derived data, and a globally interned component table populated on demand is *created by* tenant submissions. Whether that constitutes tenant-derived data is arguable, and an arguable boundary is the kind that erodes (`RISK-PLT-004`). Tenant-scoped interning removes the argument.

The second is the inference surface. A globally interned table with on-demand creation makes component existence observable in principle, and DOC-24 §6.2 entry 14 records that exactly this class of observation — deduplication response as an oracle — is a cross-tenant inference path.

**The cost of the decision.** At 32 active tenants each with 100,000 distinct components, approximately 3,200,000 rows rather than perhaps 300,000. Negligible against the 80,000,000 entries it makes narrow, and the correct trade.

| Index | Columns | Serves |
|---|---|---|
| `ux_component__canonical` | UNIQUE `(tenant_id, purl_canonical, canonicalization_version)` | Interning lookup on every submission — one per component per SBOM |
| `ix_component__match_lookup` | `(tenant_id, ecosystem, name)` | **The match join** against `vulnerability_affected_range` (§13.6). Together these two indexes determine whether `NFR-SBM-002` is achievable |
| `ix_component__unmatchable` | `(tenant_id, ecosystem)` WHERE NOT `is_canonicalizable` | Unmatchable components — the quality feedback surface (`PRD-SBM-037`) |
| `ix_component__recanonicalize` | `(tenant_id, canonicalization_version)` | Components needing re-canonicalization after a rule change |

### 15.3 `component_entry`

| Column | Type | Notes |
|---|---|---|
| `snapshot_id` | `uuid` | NOT NULL, FK RESTRICT |
| `component_id` | `uuid` | NOT NULL, FK RESTRICT |
| `tenant_id` | `uuid` | NOT NULL |
| `relationship` | `smallint` | NOT NULL — 1 direct, 2 transitive |
| `depth` | `smallint` | |
| `license_refs` | `text[]` | |
| `reachability` | `smallint` | **Reserved (DF-03); null in this release** |

**Primary key `(tenant_id, snapshot_id, component_id)`.** Fifth documented exception to `CON-DAT-006`, and the most consequential. The entry has no independent identity — it is the fact that a snapshot contains a component — and omitting a surrogate `uuid` saves 16 bytes plus one index on 80,000,000 rows: approximately 1.3 GB of data and 2–3 GB of index.

**No common columns.** No `created_at`, `updated_by`, or `row_version`. Entries are inserted once with their snapshot, never updated, and their creation time is the snapshot's. Carrying the standard six columns would add roughly 40 bytes per row — 3 GB at Extra large — for information already available on the parent. Sixth documented exception, and the reasoning is the same: an immutable child of an immutable parent needs no independent audit columns.

**Resulting row width** approximately 45 bytes plus overhead, against approximately 200 in the naive design.

| Index | Columns | Serves |
|---|---|---|
| `pk_component_entry` | `(tenant_id, snapshot_id, component_id)` | **Snapshot contents** — read once per match run per snapshot |
| `ix_component_entry__component` | `(tenant_id, component_id)` | **"Which snapshots contain this component"** — the disclosure-response query. This is the index that answers *which of our applications contain the vulnerable library* |
| `ix_component_entry__direct` | `(tenant_id, snapshot_id)` WHERE `relationship = 1` | Direct dependencies only — prioritization and the developer-facing view |

**Partitioning.** Hash by `tenant_id`, 32 partitions, from the outset. A single tenant's growth is bounded to its partitions, and the two hot queries both carry `tenant_id`, so every query is partition-pruned to one.

**Retention.** Follows snapshot retention. Deleting a snapshot deletes its entries — the only place in the schema where bulk deletion of a large table occurs, which is why snapshot retention is implemented as partition-aware batch deletion rather than a single statement.

### 15.4 `sbom_snapshot`

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `artifact_asset_id` | `uuid` | NOT NULL — cross-module, no FK |
| `content_hash` | `bytea` | NOT NULL — identity (`PRD-SBM-033`) |
| `format` | `text` | NOT NULL — `CYCLONEDX`, `SPDX` |
| `format_version` | `text` | NOT NULL |
| `revision_reference` | `text` | |
| `build_reference` | `text` | |
| `source` | `text` | NOT NULL — `API_PUSH`, `MANUAL_UPLOAD`; `PLATFORM_GENERATED`, `REGISTRY_DERIVED` reserved and rejected |
| `submitted_by_principal_id` | `uuid` | NOT NULL |
| `component_count` | `int` | NOT NULL, CHECK `> 0` (`INV-SBM-03`) |
| `quality_score` | `int` | NOT NULL, CHECK 0–100 |
| `quality_detail` | `jsonb` | NOT NULL — per-criterion measures |
| `ecosystems` | `text[]` | NOT NULL — coverage-aware closure depends on it (`PRD-SBM-055`) |
| `storage_ref` | `text` | Original document in object storage |
| Scope descriptor columns | | §6.6 |
| Common columns | | §6.1, less `updated_*` — immutable |

**Constraints.**

```sql
UNIQUE (tenant_id, content_hash)
CHECK (component_count > 0)
CHECK (source IN ('API_PUSH','MANUAL_UPLOAD'))                   -- INV-SBM-05
```

**On the `source` check.** ADR-026 reserves two additional values, and `INV-SBM-05` requires them rejected in this release. A database `CHECK` is the strongest available expression: enabling them later is a one-line migration accompanying the code that supports them, and until then no code path — including a migration or a bulk import — can introduce them.

**Immutability** is enforced by a trigger rejecting any update (`INV-SBM-01`).

| Index | Columns | Serves |
|---|---|---|
| `ux_sbom_snapshot__hash` | UNIQUE `(tenant_id, content_hash)` | **Idempotent submission** — resubmitting identical content returns the existing snapshot (`PRD-SBM-033`) |
| `ix_snapshot__artifact_latest` | `(tenant_id, artifact_asset_id, created_at DESC)` | **Latest snapshot for an artifact** — coverage state, change set computation, and every match trigger |
| `ix_snapshot__quality` | `(tenant_id, quality_score)` WHERE `quality_score < 70` | Low-quality snapshots — the `PARTIAL` coverage queue (`PRD-SBM-032`) |
| `ix_snapshot__retention` | `(tenant_id, created_at)` | Retention batch selection |

**Partitioning.** Hash by `tenant_id` at ≥ 20,000 rows, aligned with `component_entry`.

### 15.5 `match_run`

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `snapshot_id` | `uuid` | NOT NULL, FK RESTRICT |
| `idempotency_key` | `bytea` | NOT NULL — hash of snapshot hash, intelligence version, matcher version, canonicalization version (`PRD-SBM-047`) |
| `intelligence_version` | `bigint` | NOT NULL |
| `matcher_version` | `text` | NOT NULL |
| `canonicalization_version` | `int` | NOT NULL |
| `queue_class` | `text` | NOT NULL — `INTERACTIVE`, `BATCH` |
| `batch_id` | `uuid` | FK RESTRICT → `match_batch` |
| `priority` | `int` | NOT NULL |
| `lease_worker_id` | `text` | |
| `lease_expires_at` | `timestamptz` | Lease-based reclamation (`PRD-SBM-048`) |
| `state` | `text` | NOT NULL |
| `attempt_count` | `int` | NOT NULL DEFAULT 0 |
| `coverage_confirmed` | `bool` | NOT NULL DEFAULT false | **Gates closure** (`INV-SBM-09`) |
| `intelligence_stale` | `bool` | NOT NULL DEFAULT false |
| `candidates_emitted` | `int` | |
| `delta_new`, `delta_persisting`, `delta_fixed` | `int` | |
| `started_at`, `completed_at` | `timestamptz` | |
| `failure_reason` | `text` | |

**Constraints.** `UNIQUE (tenant_id, idempotency_key)`. `CHECK (NOT coverage_confirmed OR (state = 'COMPLETED' AND NOT intelligence_stale))` — enforcing `INV-SBM-09` in the engine, so a failed or stale run cannot be marked as confirming coverage and therefore cannot drive closure.

**On that constraint.** It is the database-level guard against the mass false-close failure described in `PRD-SBM-053`. The domain enforces it too; making it a constraint means it survives a defect in the closure path, which is the path where the failure would be catastrophic and silent.

| Index | Columns | Serves |
|---|---|---|
| `ux_match_run__idempotency` | UNIQUE `(tenant_id, idempotency_key)` | Idempotent trigger |
| `ix_match_run__claim` | `(queue_class, priority DESC, created_at)` WHERE state = `QUEUED` | **Worker claim** — the queue read, ordered by class then priority |
| `ix_match_run__lease_expired` | `(lease_expires_at)` WHERE state = `RUNNING` | Lease reclamation (`PRD-SBM-048`) |
| `ix_match_run__snapshot` | `(tenant_id, snapshot_id, created_at DESC)` | Run history for a snapshot |
| `ix_match_run__batch_progress` | `(tenant_id, batch_id, state)` | Batch progress reporting (`PRD-SBM-049`) |

**Partitioning.** Range by month at ≥ 5,000,000 rows.

**Note on `ix_match_run__claim`.** It deliberately omits `tenant_id` as the leading column, contrary to `CON-DAT-015`, because the worker claim query is cross-tenant by nature — a worker takes the next item of a class regardless of tenant. This is executed by a worker whose tenant context is established *from* the claimed item, not before it. Recorded as an explicit, single exception with the reasoning; per-tenant concurrency caps (`CON-PLT-034`) are applied by the claim logic rather than by the index.

### 15.6 `match_batch`

```sql
match_batch (
  id, tenant_id,
  trigger_kind text NOT NULL,                      -- INTELLIGENCE_UPDATE, KEV_UPDATE, MANUAL, SCHEDULED
  selection_criteria jsonb NOT NULL,
  concurrency_limit int NOT NULL DEFAULT 1,
  total_runs, queued, running, succeeded, failed, skipped int NOT NULL DEFAULT 0,
  state text NOT NULL,                             -- QUEUED, RUNNING, PAUSED, COMPLETED, CANCELLED
  paused_at, resumed_at, completed_at
)
```

Index `ix_match_batch__active (tenant_id, state)` WHERE state IN (`QUEUED`,`RUNNING`,`PAUSED`) serves the batch monitor and the pause/resume control (`PRD-SBM-049`).

### 15.7 `sbom_coverage_state` — projection

Materialized per `CON-PLT-028` and P6.

| Column | Type | Notes |
|---|---|---|
| `asset_id` | `uuid` | PK — one row per artifact asset |
| `tenant_id` | `uuid` | NOT NULL |
| `latest_snapshot_id` | `uuid` | Null ⇒ never submitted |
| `latest_snapshot_at` | `timestamptz` | |
| `snapshot_age_days` | `int` | |
| `latest_quality_score` | `int` | |
| `latest_ecosystems` | `text[]` | |
| `declared_ecosystems` | `text[]` | From the asset's technology attributes |
| `freshness_threshold_days` | `int` | NOT NULL — from criticality |
| `status` | `text` | NOT NULL — `CURRENT`, `PARTIAL`, `STALE`, `NEVER_SUBMITTED` |
| `computed_at` | `timestamptz` | NOT NULL |

| Index | Columns | Serves |
|---|---|---|
| `pk_sbom_coverage_state` | `(asset_id)` | Coverage for an asset |
| `ix_coverage__status` | `(tenant_id, status)` | **The three coverage queues** — never-submitted, stale, partial (`PRD-SBM-058`) |
| `ix_coverage__never` | `(tenant_id, asset_id)` WHERE status = `NEVER_SUBMITTED` | The most important queue in the module (`PRD-SBM-056`) |
| `ix_coverage__stale_by_age` | `(tenant_id, snapshot_age_days DESC)` WHERE status = `STALE` | Staleness ranked worst-first |

### 15.8 `exploitability_statement`

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | Tenant-scoped: tenant-authored statements are tenant data (`PRD-SBM-043`) |
| `vulnerability_ref` | `text` | NOT NULL |
| `component_purl` | `text` | NOT NULL |
| `authorship` | `text` | NOT NULL — `TENANT`, `SUPPLIER` |
| `status` | `text` | NOT NULL — `NOT_AFFECTED`, `AFFECTED`, `FIXED`, `UNDER_INVESTIGATION` |
| `justification` | `text` | NOT NULL |
| `scope_kind`, `scope_asset_id`, `scope_node_id` | | Statement applicability |
| `expires_at` | `timestamptz` | |
| `created_by` | `uuid` | |

Index `ix_exploitability__lookup (tenant_id, vulnerability_ref, component_purl)` serves suppression evaluation at match time (`PRD-SBM-042`) — one lookup per candidate.

### 15.9 `component_change_set`

Materialized rather than computed on demand, because change-set reads are frequent (the developer-facing "what changed" view) and the computation joins two large entry sets.

```sql
component_change_set (
  id, tenant_id,
  artifact_asset_id uuid NOT NULL,
  from_snapshot_id  uuid NOT NULL FK RESTRICT,
  to_snapshot_id    uuid NOT NULL FK RESTRICT,
  added_count, removed_count, upgraded_count, downgraded_count int NOT NULL,
  changes jsonb NOT NULL,                          -- per-component detail
  computed_at timestamptz NOT NULL,
  UNIQUE (tenant_id, from_snapshot_id, to_snapshot_id)
)
```

| Index | Columns | Serves |
|---|---|---|
| `ix_change_set__artifact` | `(tenant_id, artifact_asset_id, computed_at DESC)` | Latest change set for an artifact — the "what changed" view |
| `ix_change_set__downgrades` | `(tenant_id, computed_at DESC)` WHERE `downgraded_count > 0` | **Version downgrades** — almost always unintentional and invisible in a finding list (`PRD-SBM-052`) |

**Retention.** Shorter than snapshot retention: change sets are recomputable from the snapshots and are read mostly soon after computation.

---

## 16. Schema — Work Management

### 16.1 Workflow as data

ADR-027 requires workflows to be tenant-configured data (`PRD-WRK-008`). Three tables hold the definition; a fourth pins the version on each item.

```sql
workflow_definition (
  id, tenant_id,
  work_item_type_id uuid NOT NULL FK RESTRICT,
  version           int  NOT NULL,                 -- immutable once activated (INV-WRK-01)
  initial_state_id  uuid NOT NULL,
  state             text NOT NULL CHECK IN ('DRAFT','ACTIVE','RETIRED'),
  validated_at      timestamptz,                   -- reachability validated (INV-WRK-02)
  activated_at      timestamptz,
  UNIQUE (tenant_id, work_item_type_id, version),
  CHECK (state <> 'ACTIVE' OR validated_at IS NOT NULL)
)

workflow_state (
  id, tenant_id,
  definition_id uuid NOT NULL FK RESTRICT,
  code          text NOT NULL,                     -- immutable
  label_i18n    jsonb NOT NULL,
  category      text NOT NULL
                  CHECK IN ('OPEN','IN_PROGRESS','WAITING_EXTERNAL','TERMINAL'),
  sla_clock_running bool NOT NULL,                 -- drives clock pause (INV-RSK-09)
  display_order int NOT NULL,
  UNIQUE (tenant_id, definition_id, code)
)

workflow_transition (
  id, tenant_id,
  definition_id   uuid NOT NULL FK RESTRICT,
  from_state_id   uuid NOT NULL FK RESTRICT,
  to_state_id     uuid NOT NULL FK RESTRICT,
  event_code      text NOT NULL,
  guard_rule      jsonb,                           -- evaluated by the shared rules engine
  required_fields text[] NOT NULL DEFAULT '{}',
  required_permission text,                        -- authorization-relevant configuration
  side_effects    jsonb NOT NULL DEFAULT '[]',
  reason_required bool NOT NULL DEFAULT false,
  UNIQUE (tenant_id, definition_id, from_state_id, event_code)
)
```

**On `CHECK (state <> 'ACTIVE' OR validated_at IS NOT NULL)`.** `INV-WRK-02` requires reachability and terminal-state validation before activation. A workflow with an unreachable state is silently broken — items enter and cannot leave, and the defect surfaces days later as stalled work with no visible cause. The constraint makes activation without validation unrepresentable.

**On `sla_clock_running` living on the state rather than being inferred from `category`.** Two states in the same category may differ: a tenant may treat one waiting state as their responsibility and another as the requester's. Storing it per state makes the pause behaviour configurable, which `PRD-RSK-034` requires for accurate attribution.

**On `required_permission` being a column here.** This is authorization configuration living in the work management schema (DOC-26 T9). It is the reason `wrk.workflow.manage` is one of the three most consequential permissions in the catalogue: editing this column changes who can effect a transition without any change to a role.

| Index | Columns | Serves |
|---|---|---|
| `ix_workflow_state__definition` | `(tenant_id, definition_id, display_order)` | Ordered state list — the board view columns and the configuration interface |
| `ix_workflow_transition__from` | `(tenant_id, definition_id, from_state_id)` | **Available transitions for an item's current state** — evaluated on every item view |
| `ix_workflow_def__active` | `(tenant_id, work_item_type_id)` WHERE state = `ACTIVE` | The current definition for a type, resolved at item creation |

### 16.2 `work_item`

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `item_code` | `text` | NOT NULL, unique per tenant |
| `type_id` | `uuid` | NOT NULL, FK RESTRICT |
| `workflow_definition_id` | `uuid` | NOT NULL, FK RESTRICT |
| `workflow_definition_version` | `int` | NOT NULL — pinned (`INV-WRK-01`) |
| `state_id` | `uuid` | NOT NULL, FK RESTRICT |
| `title` | `text` | NOT NULL |
| `description` | `text` | |
| `assignee_id` | `uuid` | Single individual (`INV-WRK-05`) |
| `labels` | `text[]` | NOT NULL DEFAULT '{}' |
| `attributes` | `jsonb` | NOT NULL DEFAULT '{}' |
| `subject_kind` | `text` | `FINDING`, `ASSESSMENT`, `ASSET`, `EXCEPTION`, `NONE` |
| `subject_id` | `uuid` | Cross-module, no FK (`INV-WRK-16`) |
| `parent_item_id` | `uuid` | FK RESTRICT — sub-items |
| `effort_derived_days` | `numeric(6,2)` | From transition durations (`INV-WRK-15`) |
| `effort_manual_days` | `numeric(6,2)` | Separate column, never overwriting |
| `estimated_effort_days` | `numeric(6,2)` | |
| `planning_period_id` | `uuid` | FK RESTRICT — light iteration support (`PRD-WRK-029`) |
| `search_vector` | `tsvector` ⚙ | Maintained by trigger; `PRD-WRK-018` |
| Scope descriptor columns | | §6.6, from the subject (`INV-WRK-06`) |
| Common columns | | §6.1 |

**Constraints.**

```sql
UNIQUE (tenant_id, item_code)
CHECK (subject_kind = 'NONE' OR subject_id IS NOT NULL)
CHECK (parent_item_id IS NULL OR parent_item_id <> id)
```

**Invariant mapping.** `INV-WRK-01` `DB`. `INV-WRK-05` `DB` — single column. `INV-WRK-06` `DOMAIN`. `INV-WRK-15` `DB` — two columns. `INV-WRK-16` `DOMAIN` + reconciliation (`CON-DAT-004`). `INV-WRK-17` `DB` — `row_version`.

| Index | Columns | Kind | Serves |
|---|---|---|---|
| `ux_work_item__code` | UNIQUE `(tenant_id, item_code)` | btree | Lookup by quoted code |
| `ix_work_item__assignee_open` | `(tenant_id, assignee_id, state_id)` | btree, WHERE state not terminal | **"My work"** — the most frequently loaded view in the platform (`PRD-WRK-013`) |
| `ix_work_item__board` | `(tenant_id, type_id, state_id, updated_at DESC)` | btree, WHERE state not terminal | The board view, grouped by state column (`PRD-WRK-014`) |
| `ix_work_item__scope_subtree` | `(tenant_id, scope_ancestor_path)` | GIN ⚙, WHERE state not terminal | Subtree-scoped work reads |
| `ix_work_item__subject` | `(tenant_id, subject_kind, subject_id)` | btree | **"Work on this finding"** — the bidirectional link (`INV-WRK-16`) |
| `ix_work_item__unassigned` | `(tenant_id, created_at)` | btree, WHERE `assignee_id IS NULL` AND state not terminal | The unassigned queue |
| `ix_work_item__search` | `(search_vector)` | GIN ⚙ | Full-text search (`PRD-WRK-018`) |
| `ix_work_item__labels` | `(labels)` | GIN ⚙ | Label filtering and saved queries |
| `ix_work_item__parent` | `(tenant_id, parent_item_id)` | btree, WHERE not null | Sub-items |
| `ix_work_item__planning` | `(tenant_id, planning_period_id, state_id)` | btree, WHERE not null | Iteration progress |
| `ix_work_item__attr_*` | expression indexes per searchable attribute | btree | Custom field filtering (`CON-DAT-018`) |

**On maintaining `search_vector` by trigger rather than expression index.** ⚙ Full-text search covers the title, description, **and comments** (`PRD-WRK-018`). Comments live in a child table, so the vector cannot be an expression over this row alone. A trigger on both tables maintains it, which makes the write path slightly heavier and the search path a single index scan. The alternative — searching two tables and merging — cannot rank results coherently, and `NFR-WRK-002` requires first results within one second at Medium.

**On scope filtering in search.** The GIN index on `search_vector` is combined with the scope predicate at query time. DOC-01 §16.4 recorded this as a known extension cost: filtering after retrieval leaks result counts across scope boundaries, and filtering before ranking degrades relevance. The resolution here is that the scope predicate is a *conjunct* in the same query, so the engine applies both before ranking — which requires that `scope_ancestor_path` and `search_vector` be usable together. ⚙ This is a genuine engine capability dependency and is the single strongest constraint on the search technology choice.

### 16.3 `work_item_state_transition` — append-only

`INV-WRK-04` and `PRD-WRK-011`. The data this table holds **cannot be reconstructed later**, which is why it is `MUST_HAVE` in v1 despite the analytics it supports being secondary.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` | PK |
| `tenant_id` | `uuid` | NOT NULL |
| `work_item_id` | `uuid` | NOT NULL |
| `sequence` | `int` | NOT NULL — monotonic per item |
| `from_state_id` | `uuid` | Null on creation |
| `to_state_id` | `uuid` | NOT NULL |
| `event_code` | `text` | NOT NULL |
| `actor_id` | `uuid` | |
| `actor_type` | `text` | NOT NULL — `USER`, `SERVICE`, `AUTOMATION`, `SYSTEM` (`INV-AUD-05` pattern) |
| `automation_rule_id` | `uuid` | Where `actor_type = 'AUTOMATION'` |
| `reason` | `text` | |
| `transitioned_at` | `timestamptz` | NOT NULL — **partition key** |
| `duration_in_previous_state_seconds` | `bigint` | Denormalized for query speed |
| `sla_clock_running` | `bool` | NOT NULL — was the clock running in the state just left |
| `blocking_attribution` | `text` | `REQUESTER_READINESS`, `THIRD_PARTY`, `ENVIRONMENT`, `SCOPE_CHANGE`, `CAPACITY`, `EXTERNAL_DEPENDENCY` |

**Constraints.**

```sql
UNIQUE (tenant_id, work_item_id, sequence)
CHECK (duration_in_previous_state_seconds IS NULL OR duration_in_previous_state_seconds >= 0)
CHECK (actor_type <> 'AUTOMATION' OR automation_rule_id IS NOT NULL)
```

**Append-only enforcement.** No `UPDATE` or `DELETE` grant to `app_runtime`; a rule rejecting both is additionally declared ⚙ so that a privilege misconfiguration does not silently permit modification. `INV-WRK-04` is `DB`.

**On denormalizing `duration_in_previous_state_seconds`.** It is derivable by self-joining to the previous sequence. Storing it avoids that join on every cycle-time and flow computation, and those computations read the whole history of many items at once. The cost is 8 bytes per row and a write-time computation the transition already has in hand.

**On `sla_clock_running` being recorded on the transition rather than resolved from the state.** The state's `sla_clock_running` flag is tenant configuration and can change. A historical service level computation must use the flag as it was, not as it is — otherwise a configuration change retroactively alters past breach attribution.

| Index | Columns | Serves |
|---|---|---|
| `pk_work_item_state_transition` | `(id)` | — |
| `ux_wist__item_sequence` | UNIQUE `(tenant_id, work_item_id, sequence)` | **Item history in order** — the activity timeline and cycle-time computation |
| `ix_wist__state_occupancy` | `(tenant_id, transitioned_at, to_state_id)` | **Cumulative flow** — state occupancy over time, the view that distinguishes a security-team bottleneck from an engineering one |
| `ix_wist__actor` | `(tenant_id, actor_id, transitioned_at DESC)` | Per-principal transition rate — gaming detection (`SEC-PLT-005`) |
| `ix_wist__blocking` | `(tenant_id, blocking_attribution, transitioned_at)` WHERE not null | **Breach attribution reporting** (`PRD-CAP-009`) |

**Partitioning.** Range by `transitioned_at`, monthly, from the outset. **Retention:** archived, not dropped — the log is retained for the work item's life, and items are not deleted.

### 16.4 `comment` and `comment_revision`

```sql
comment (
  id, tenant_id,
  work_item_id uuid NOT NULL FK RESTRICT,
  thread_root_id uuid FK RESTRICT,               -- null ⇒ top-level
  body         text NOT NULL,                    -- constrained rich text (INV-WRK-10)
  body_format  text NOT NULL,
  mentioned_principal_ids uuid[] NOT NULL DEFAULT '{}',
  is_redacted  bool NOT NULL DEFAULT false,
  redacted_by, redacted_at, redaction_reason,
  edit_count   int NOT NULL DEFAULT 0,
  author_id    uuid NOT NULL,
  migrated_from_external_id text,               -- INV-ING migration authorship
  is_migrated  bool NOT NULL DEFAULT false,
  CHECK (NOT is_redacted OR (redacted_by IS NOT NULL AND redaction_reason IS NOT NULL)),
  CHECK (NOT is_migrated OR migrated_from_external_id IS NOT NULL)
)

comment_revision (
  id, tenant_id,
  comment_id uuid NOT NULL FK RESTRICT,
  revision   int  NOT NULL,
  body       text NOT NULL,
  edited_by  uuid NOT NULL,
  edited_at  timestamptz NOT NULL,
  UNIQUE (tenant_id, comment_id, revision)
)
```

**No delete path.** `INV-WRK-08` prohibits hard deletion: a comment thread on a security finding is audit evidence, and selective deletion permits reconstruction of a different history. Removal is redaction — `is_redacted` set, `body` replaced with a marker, the original preserved in `comment_revision`. No `DELETE` grant to `app_runtime`. `INV-WRK-08` is `DB`.

**On `is_migrated`.** DOC-26 §8 identified migration authorship as an abuse case: the capability that preserves history could fabricate a record of a decision never made. The flag is the control, and it must survive into every presentation — which is why it is a column on the comment rather than a property of the import session.

| Index | Columns | Serves |
|---|---|---|
| `ix_comment__work_item` | `(tenant_id, work_item_id, created_at)` | **The comment thread** — loaded with every item view |
| `ix_comment__thread` | `(tenant_id, thread_root_id, created_at)` WHERE not null | Threaded replies |
| `ix_comment__mentions` | `(mentioned_principal_ids)` GIN ⚙ | "Comments mentioning me" — the notification and inbox path |
| `ix_comment__author` | `(tenant_id, author_id, created_at DESC)` | Author history; migration verification |

### 16.5 Participation, watchers, read state

```sql
work_item_participant (
  id, tenant_id, work_item_id uuid NOT NULL FK RESTRICT,
  principal_id uuid NOT NULL,
  role text NOT NULL CHECK IN ('LEAD','SUPPORT','REVIEWER','SHADOW'),
  UNIQUE (tenant_id, work_item_id, principal_id, role)
)

work_item_watcher (
  tenant_id, work_item_id uuid NOT NULL FK RESTRICT,
  principal_id uuid NOT NULL,
  subscribed_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, work_item_id, principal_id)      -- seventh PK exception
)

work_item_read_state (
  tenant_id, work_item_id uuid NOT NULL FK RESTRICT,
  principal_id uuid NOT NULL,
  last_read_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, work_item_id, principal_id)      -- eighth PK exception
)
```

Both exceptions follow the §Part 2 criterion: a pure membership fact with no identity beyond its parents.

| Index | Columns | Serves |
|---|---|---|
| `ix_watcher__principal` | `(tenant_id, principal_id)` | "Items I watch" — the personal view and notification fan-out |
| `ix_read_state__principal` | `(tenant_id, principal_id, last_read_at)` | Unread computation for the inbox (`PRD-WRK-019`) |

**On `work_item_read_state` write volume.** Every item view by every user writes a row. At Extra large with 40,000 users this is the highest-frequency write in the platform, and it is entirely uninteresting data. It is therefore excluded from audit (a read-state update is not an audited action) and is a candidate for a write-behind cache rather than a synchronous write — recorded as an implementation note rather than a schema decision.

### 16.6 `work_item_link`

```sql
work_item_link (
  id, tenant_id,
  from_item_id uuid NOT NULL FK RESTRICT,
  to_item_id   uuid NOT NULL FK RESTRICT,
  link_type    text NOT NULL
                 CHECK IN ('BLOCKS','RELATES_TO','DUPLICATES','CAUSED_BY'),
  UNIQUE (tenant_id, from_item_id, to_item_id, link_type),
  CHECK (from_item_id <> to_item_id)
)
```

**Inverses are maintained automatically** (`INV-WRK-07`) — a `BLOCKS` link writes the `IS_BLOCKED_BY` direction as a second row. Storing both directions rather than deriving the inverse means each direction is independently indexable, which matters because *"what blocks this item"* and *"what does this item block"* are both frequent and the first drives the blocked-work queue.

| Index | Columns | Serves |
|---|---|---|
| `ix_work_link__from` | `(tenant_id, from_item_id, link_type)` | Outgoing links on an item view |
| `ix_work_link__to` | `(tenant_id, to_item_id, link_type)` | Incoming links; the blocked-work queue (`PRD-CAP-015`) |

### 16.7 `automation_rule` and `automation_execution`

```sql
automation_rule (
  id, tenant_id, name,
  trigger_kind text NOT NULL,
  conditions   jsonb NOT NULL,                   -- shared rules engine (CON-PLT-012)
  actions      jsonb NOT NULL,
  owning_principal_id uuid NOT NULL,             -- authority ceiling (INV-WRK-13)
  authority_suspended bool NOT NULL DEFAULT false, -- SEC-AUZ-038
  suspended_reason text,
  execution_budget_per_trigger int NOT NULL DEFAULT 50,
  is_enabled   bool NOT NULL DEFAULT false,
  CHECK (NOT authority_suspended OR suspended_reason IS NOT NULL)
)

automation_execution (
  id, tenant_id,
  rule_id      uuid NOT NULL FK RESTRICT,
  trigger_event_id uuid,
  actions_attempted int NOT NULL,
  actions_succeeded int NOT NULL,
  actions_denied    int NOT NULL,                -- authority ceiling rejections
  loop_depth   int NOT NULL,
  executed_at  timestamptz NOT NULL              -- partition key
)
```

**On `authority_suspended` as a stored column.** `SEC-AUZ-038` requires rules to be suspended when their owning principal loses authority. Computing this at execution time would require an authorization evaluation per rule per trigger; storing it lets the authorization change event set the flag once. The cost is that the flag can be stale between the authority change and the event handler — bounded by `NFR-SEC-002` at 60 seconds, and the execution-time authority ceiling (`INV-WRK-13`) is the backstop.

**On `actions_denied`.** A rule repeatedly attempting actions its owner cannot perform is either a misconfiguration or an escalation attempt. Counting them makes it visible; without the column the denials are invisible because the rule appears to run.

| Index | Columns | Serves |
|---|---|---|
| `ix_automation_rule__trigger` | `(tenant_id, trigger_kind)` WHERE `is_enabled` AND NOT `authority_suspended` | **Rule resolution on every trigger** — must be fast because it runs on every state change |
| `ix_automation_exec__rule` | `(tenant_id, rule_id, executed_at DESC)` | Execution history; rule debugging |
| `ix_automation_exec__denied` | `(tenant_id, executed_at DESC)` WHERE `actions_denied > 0` | Authority-ceiling rejections — the escalation-attempt signal |

**Partitioning.** `automation_execution` range by month at ≥ 5,000,000 rows.

### 16.8 `saved_view`

```sql
saved_view (
  id, tenant_id, name,
  owner_principal_id uuid NOT NULL,
  query_definition jsonb NOT NULL,
  sharing text NOT NULL CHECK IN ('PRIVATE','SHARED_TENANT','SHARED_SCOPE'),
  shared_scope_node_id uuid
)
```

**No stored result set and no stored scope.** `INV-WRK-11` requires a shared query to evaluate against the *viewer's* scope. Storing the author's scope with the query would make a shared link carry the author's visibility — a scope escalation available to anyone with the link. The query definition holds filters only; scope is applied at evaluation from the viewer's context.

---

## 17. Schema — Capacity

```sql
team_member (
  id, tenant_id,
  principal_id uuid NOT NULL,
  capacity_ratio numeric(4,3) NOT NULL CHECK (capacity_ratio > 0 AND capacity_ratio <= 1),
  overhead_allowance_ratio numeric(4,3) NOT NULL CHECK (>= 0 AND < 1),
  active_from date NOT NULL, active_until date,
  UNIQUE (tenant_id, principal_id, active_from)
)

team_member_competency (
  id, tenant_id, team_member_id uuid NOT NULL FK RESTRICT,
  domain text NOT NULL, proficiency smallint NOT NULL CHECK (1..5),
  UNIQUE (tenant_id, team_member_id, domain)
)

availability_record (
  id, tenant_id, team_member_id uuid NOT NULL FK RESTRICT,
  period_start date NOT NULL, period_end date NOT NULL,
  reason text NOT NULL, source text NOT NULL CHECK IN ('MANUAL','IMPORTED'),
  CHECK (period_end >= period_start)
)
```

**Classification.** `team_member`, `team_member_competency`, and `availability_record` carry personal data about employment. Per `PRD-CAP-013` they are `RESTRICTED`, and the classification is enforced by the permission gate (`cap.member.read.all`) rather than by a column — there is no column to set, so there is nothing to misconfigure.

### 17.1 `capacity_measure` — projection

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `subject_kind` | `text` | NOT NULL — `TEAM`, `MEMBER` |
| `subject_id` | `uuid` | Null for `TEAM` |
| `period_start`, `period_end` | `date` | NOT NULL |
| `available_capacity_days` | `numeric(8,2)` | NOT NULL — net of non-working days, leave, overhead (`INV-CAP-01`) |
| `allocated_effort_days` | `numeric(8,2)` | NOT NULL |
| `utilization_ratio` | `numeric(5,4)` | NOT NULL |
| `effort_by_category` | `jsonb` | NOT NULL (`PRD-CAP-006`) |
| `contributing_member_count` | `int` | NOT NULL — **minimum group size enforcement** (`INV-CAP-04`) |
| `computed_at` | `timestamptz` | NOT NULL |

**On `contributing_member_count`.** `INV-CAP-04` requires team aggregates to enforce a minimum group size or suppress, because a team of three where two members are visible discloses the third by subtraction. Storing the count with the measure means the suppression decision is made once at computation rather than repeatedly at presentation, where it would be omitted from one surface.

| Index | Columns | Serves |
|---|---|---|
| `ux_capacity_measure__subject_period` | UNIQUE `(tenant_id, subject_kind, subject_id, period_start)` | Idempotent recomputation |
| `ix_capacity_measure__team_period` | `(tenant_id, period_start DESC)` WHERE `subject_kind = 'TEAM'` | Team utilization trend |
| `ix_capacity_measure__overallocated` | `(tenant_id, period_start)` WHERE `utilization_ratio > 1` | **Over-allocation** — surfaced at allocation time (`PRD-CAP-004`) |

### 17.2 `workload_snapshot` — daily rollup

| Column | Type | Notes |
|---|---|---|
| `tenant_id` | `uuid` | NOT NULL |
| `snapshot_date` | `date` | NOT NULL — partition key |
| `state_occupancy` | `jsonb` | NOT NULL — count per state |
| `intake_count`, `completion_count` | `int` | NOT NULL |
| `backlog_count` | `int` | NOT NULL |
| `computed_at` | `timestamptz` | NOT NULL |
| PK | `(tenant_id, snapshot_date)` | Ninth PK exception — a pure per-day fact |

**Idempotent and backfillable** (`INV-CAP-02`): recomputation for a past date overwrites, and the computation reads only `work_item_state_transition`, which is append-only and complete. A rollup defect is therefore correctable retroactively — which is the whole reason the transition log is `MUST_HAVE`.

| Index | Columns | Serves |
|---|---|---|
| `pk_workload_snapshot` | `(tenant_id, snapshot_date)` | Trend series read |

**Partitioning.** Range by `snapshot_date`, yearly — volume is low but the table is queried by range, and yearly partitions keep retention simple.

---

## 18. Schema — Risk and Service Levels

### 18.1 `scoring_model` and weights

```sql
scoring_model (
  id, tenant_id,
  version    int  NOT NULL,
  state      text NOT NULL CHECK IN ('DRAFT','ACTIVE','RETIRED'),
  validated_against_history_at timestamptz,        -- PRD-RSK-046
  band_thresholds jsonb NOT NULL,
  activated_at, retired_at,
  UNIQUE (tenant_id, version),
  CHECK (state <> 'ACTIVE' OR validated_against_history_at IS NOT NULL)
)

scoring_model_factor_weight (
  id, tenant_id,
  model_id uuid NOT NULL FK RESTRICT,
  factor_code text NOT NULL,                       -- SEV, EXP, KEV, EXPO, CRIT, DATA, REACH
  weight numeric(4,3) NOT NULL CHECK (weight >= 0 AND weight <= 1),
  UNIQUE (tenant_id, model_id, factor_code)
)
```

**On `CHECK (state <> 'ACTIVE' OR validated_against_history_at IS NOT NULL)`.** `PRD-RSK-046` requires validation against the tenant's own historical data before a weight configuration is activated. The constraint makes an unvalidated activation unrepresentable — because a weight set that has not been tested is a guess presented as methodology, and it will be defended in a meeting where nobody can produce evidence for it.

**Immutability of an activated version** is enforced by a trigger on both tables.

### 18.2 `risk_score`

Immutable (`INV-RSK-03`), self-contained (`PRD-RSK-023`), high volume.

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `subject_kind` | `text` | NOT NULL — `FINDING_IMPACT`, `ASSET`, `ORG_NODE` |
| `subject_id` | `uuid` | NOT NULL |
| `model_version` | `int` | NOT NULL |
| `factor_inputs` | `jsonb` | NOT NULL — the values used, with source and freshness per factor |
| `factor_contributions` | `jsonb` | NOT NULL — per-factor contribution to the total |
| `value` | `smallint` | NOT NULL, CHECK 0–100 |
| `band` | `text` | NOT NULL |
| `coverage_confidence` | `text` | NOT NULL — `HIGH`, `MEDIUM`, `LOW`, `INSUFFICIENT` (`INV-RSK-06`) |
| `coverage_detail` | `jsonb` | NOT NULL — materialized with the score (`CON-PLT-028`) |
| `population_version` | `bigint` | For rank-transformed factors (§DOC-28 §5.2) |
| `computed_at` | `timestamptz` | NOT NULL — partition key |
| `superseded_by_score_id` | `uuid` | Set when a newer score is computed |
| `change_attribution` | `jsonb` | Which inputs changed and their delta contribution (`PRD-RSK-025`) |

**Constraints.** No `UPDATE` grant except to set `superseded_by_score_id` ⚙ — a narrowly-granted column update, because full immutability would require a separate supersession table for a single pointer.

**On storing `factor_inputs` rather than referencing the source rows.** `PRD-RSK-023` requires a score to be recomputable without access to data that has since changed. Referencing the asset's criticality by identifier would give a different answer once criticality is reassigned. Self-containment is the requirement; the cost is roughly 400–800 bytes per score.

**The storage cost, quantified.** At Extra large with 50,000,000 scores, `factor_inputs` plus `factor_contributions` plus `coverage_detail` is approximately 30–40 GB. This is the largest single storage consequence of the reproducibility requirement, and it is why retention (§24) drops score partitions aggressively while a projection retains the latest score per subject.

| Index | Columns | Serves |
|---|---|---|
| `ix_risk_score__current` | `(tenant_id, subject_kind, subject_id, computed_at DESC)` | **Latest score for a subject** — read on every finding view and every aggregation |
| `ix_risk_score__band` | `(tenant_id, subject_kind, band, computed_at DESC)` WHERE `superseded_by_score_id IS NULL` | Current scores by band — dashboard aggregation |
| `ix_risk_score__insufficient` | `(tenant_id, computed_at DESC)` WHERE `coverage_confidence = 'INSUFFICIENT'` | **Scores that must not be presented as posture figures** (`PRD-RSK-027`) |

**Partitioning.** Range by `computed_at`, monthly, from the outset.

**Retention.** Superseded scores retained for the configured reproducibility window (default 24 months), then partition-dropped. The latest score per subject is additionally held in a projection so that dropping historical partitions does not remove current values.

### 18.3 Service level policy and clocks

```sql
service_level_policy (
  id, tenant_id, code, label_i18n,
  version int NOT NULL,
  matching_rules jsonb NOT NULL,                   -- most-specific-wins; shared rules engine
  specificity int NOT NULL,                        -- precomputed match specificity
  target_business_days numeric(6,2) NOT NULL,
  business_calendar_id uuid NOT NULL FK RESTRICT,
  state text NOT NULL,
  UNIQUE (tenant_id, code, version)
)

business_calendar (
  id, tenant_id, code, timezone text NOT NULL,
  working_days smallint[] NOT NULL                 -- ISO day numbers
)

business_calendar_holiday (
  id, tenant_id, calendar_id uuid NOT NULL FK RESTRICT,
  holiday_date date NOT NULL, description text,
  UNIQUE (tenant_id, calendar_id, holiday_date)
)

escalation_step (
  id, tenant_id, policy_id uuid NOT NULL FK RESTRICT,
  trigger_at_budget_ratio numeric(4,3) NOT NULL,   -- 0.5, 0.75, 1.0, 2.0
  target_kind text NOT NULL,                        -- ASSIGNEE, OWNER, ANCESTOR_OWNER, ROLE
  target_ref  uuid,
  UNIQUE (tenant_id, policy_id, trigger_at_budget_ratio)
)
```

**On precomputing `specificity`.** DOC-28 §11.1 specifies most-specific-match-wins with shortest-duration as the tiebreak. Computing specificity at match time would require interpreting the rule document per candidate policy per finding. Precomputing it at policy save makes matching an ordered index scan.

### 18.4 `service_level_clock` and intervals

| Column | Type | Notes |
|---|---|---|
| `id`, `tenant_id` | | |
| `subject_kind`, `subject_id` | | `FINDING`, `WORK_ITEM`, `ASSESSMENT_REQUEST` |
| `policy_id` | `uuid` | NOT NULL, FK RESTRICT |
| `policy_version` | `int` | NOT NULL — **pinned at start** (`INV-RSK-08`) |
| `calendar_snapshot` | `jsonb` | NOT NULL — the calendar as it was |
| `started_at` | `timestamptz` | NOT NULL |
| `due_at` | `timestamptz` | NOT NULL |
| `original_due_at` | `timestamptz` | NOT NULL — before any recomputation |
| `state` | `text` | NOT NULL — `RUNNING`, `PAUSED`, `MET`, `BREACHED`, `EXTENDED` |
| `elapsed_running_seconds` | `bigint` | NOT NULL DEFAULT 0 |
| `elapsed_paused_seconds` | `bigint` | NOT NULL DEFAULT 0 |
| `breached_at` | `timestamptz` | |
| `extension_approved_by`, `extension_approved_at`, `extension_reason` | | `INV-RSK-11` |
| `last_escalation_ratio` | `numeric(4,3)` | Highest escalation step already fired |

**On `calendar_snapshot`.** `PRD-RSK-033` requires that calendar changes not retroactively alter existing due dates. A holiday added after a clock started must not move its deadline. Snapshotting the calendar with the clock is the only way to guarantee it, and the document is small.

**On `original_due_at`.** `PRD-RSK-035` requires recomputation from the original start when a score increase brings a shorter policy — and explicitly permits the result to be an immediately-breached state. Retaining the original due date makes the recomputation auditable: a reader can see both what was committed and what the shorter policy required.

```sql
service_level_clock_interval (
  id, tenant_id,
  clock_id uuid NOT NULL FK RESTRICT,
  sequence int NOT NULL,
  interval_kind text NOT NULL CHECK IN ('RUNNING','PAUSED'),
  started_at timestamptz NOT NULL, ended_at timestamptz,
  blocking_attribution text,                       -- required when PAUSED (PRD-RSK-034)
  UNIQUE (tenant_id, clock_id, sequence),
  CHECK (interval_kind <> 'PAUSED' OR blocking_attribution IS NOT NULL)
)
```

**On the interval `CHECK`.** `PRD-RSK-034` requires an attribution on every pause. Making it a constraint means a pause without attribution is unrepresentable — and unattributed pause time is exactly what makes breach reporting arguable rather than factual (PP-6).

| Index | Columns | Serves |
|---|---|---|
| `ix_sla_clock__subject` | `(tenant_id, subject_kind, subject_id)` | The clock for a subject — read on every item view |
| `ix_sla_clock__due` | `(tenant_id, due_at)` WHERE state = `RUNNING` | **Forward exposure** and the breach detection job (`PRD-DSH-007`) |
| `ix_sla_clock__escalation_due` | `(tenant_id, due_at, last_escalation_ratio)` WHERE state = `RUNNING` | The escalation scheduler — finds clocks whose next step is due |
| `ix_sla_clock__breached` | `(tenant_id, breached_at DESC)` WHERE state = `BREACHED` | Breach reporting |
| `ix_sla_interval__clock` | `(tenant_id, clock_id, sequence)` | Interval history for attribution reporting |

---

## 19. Schema — Ingestion

```sql
parser_definition (
  id, code, label_i18n,                            -- platform-supplied; no tenant_id
  supported_formats text[] NOT NULL,
  supported_versions text[] NOT NULL,
  field_mapping jsonb NOT NULL,
  validation_rules jsonb NOT NULL,
  limits jsonb NOT NULL,                           -- depth, size, element count
  asset_anchor_strategy jsonb NOT NULL,
  parser_version text NOT NULL,
  UNIQUE (code, parser_version)
)

import_session (
  id, tenant_id,
  idempotency_key bytea NOT NULL,
  parser_id uuid FK RESTRICT, parser_version text,
  source_kind text NOT NULL,                       -- FILE, MATCH_RUN, ASSESSMENT, MIGRATION
  source_ref text, source_content_hash bytea,
  records_total, records_ingested, records_quarantined, records_skipped int NOT NULL DEFAULT 0,
  findings_created, findings_updated, findings_reopened int NOT NULL DEFAULT 0,
  state text NOT NULL,
  reversed_at, reversed_by, reversal_state jsonb,
  initiated_by uuid NOT NULL, started_at, completed_at,
  scope descriptor columns,
  UNIQUE (tenant_id, idempotency_key)
)

quarantined_record (
  id, tenant_id,
  session_id uuid NOT NULL FK RESTRICT,
  record_ordinal int NOT NULL,
  raw_payload jsonb NOT NULL,
  reason_code text NOT NULL, reason_detail text,
  state text NOT NULL CHECK IN ('QUARANTINED','CORRECTED','DISCARDED'),
  resubmitted_session_id uuid FK RESTRICT
)

field_mapping_template (
  id, tenant_id, name,
  source_format text NOT NULL,
  mapping jsonb NOT NULL,
  created_by uuid NOT NULL
)
```

**On `raw_payload` in `quarantined_record`.** Storing the rejected record verbatim is what makes correction and resubmission possible (`PRD-ING-008`) rather than requiring the whole file to be re-imported. It also means quarantined records may contain whatever the source contained, including credentials embedded in a repository URL — so the table is `CONFIDENTIAL` at minimum and is excluded from export.

| Index | Columns | Serves |
|---|---|---|
| `ux_import_session__idempotency` | UNIQUE `(tenant_id, idempotency_key)` | Idempotent import (`PRD-ING-005`) |
| `ix_import_session__recent` | `(tenant_id, started_at DESC)` | Import history; the health view |
| `ix_import_session__reversible` | `(tenant_id, completed_at DESC)` WHERE `reversed_at IS NULL` | Reversal candidates (`PRD-ING-011`) |
| `ix_quarantine__session` | `(tenant_id, session_id, record_ordinal)` | The quarantine review queue for a session |
| `ix_quarantine__open` | `(tenant_id, created_at)` WHERE state = `QUARANTINED` | Outstanding quarantined records across sessions |

---

## 20. Schema — Audit and Generic Modules

### 20.1 `audit_event` — the integrity design

`INV-AUD-01` (append-only), `INV-AUD-02` (cryptographically verifiable, independently anchored), and `INV-AUD-03` (metadata separated from erasable payload) together determine this design. It is the most constrained table in the schema.

**The conflict to resolve.** Audit must be immutable and verifiable. Personal data must be erasable. Audit events reference and sometimes contain personal data. Deleting events destroys the chain; refusing erasure breaches obligation.

**The resolution: hash the payload, chain the hash, erase the payload.**

```sql
audit_event (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  sequence          bigint NOT NULL,               -- monotonic per tenant
  event_type        text NOT NULL,
  occurred_at       timestamptz NOT NULL,          -- partition key
  actor_id          uuid,
  actor_type        text NOT NULL,                 -- USER|SERVICE|AUTOMATION|SYSTEM
  on_behalf_of_id   uuid,                          -- delegation (SEC-AUZ-044)
  break_glass_ref   uuid,                          -- SEC-TEN-030
  object_kind       text, object_id uuid,
  outcome           text NOT NULL,                 -- SUCCESS|DENIED|FAILED
  denial_reason     text,                          -- full fidelity; never returned to a client
  -- scope as it was (PRD-AUD-004)
  scope_node_id uuid, scope_ancestor_path uuid[], scope_hierarchy_ver bigint,
  -- integrity
  payload_hash      bytea NOT NULL,                -- hash of the payload content
  prev_chain_hash   bytea NOT NULL,
  chain_hash        bytea NOT NULL,                -- H(prev_chain_hash ‖ metadata ‖ payload_hash)
  -- erasure
  payload_erased_at timestamptz,
  payload_erasure_basis text,
  UNIQUE (tenant_id, sequence),
  CHECK (payload_erased_at IS NULL OR payload_erasure_basis IS NOT NULL)
)

audit_event_payload (
  event_id uuid PRIMARY KEY,                        -- tenth PK exception
  tenant_id uuid NOT NULL,
  payload  jsonb NOT NULL                           -- before/after values, request detail
)
```

**How verification survives erasure.** The chain covers `payload_hash`, not the payload itself. Deleting the `audit_event_payload` row leaves every `chain_hash` unchanged and every link verifiable. The event's existence, time, actor, object, outcome, and scope remain permanently.

**What is lost, stated honestly.** After erasure it is no longer possible to verify that the erased payload matched `payload_hash` — the content is gone. What remains provable is that *an event of this type, by this actor, on this object, at this time, with a payload whose hash was X* occurred, and that no event has been inserted, removed, or reordered. That is sufficient for the audit purposes the trail serves, and it is the honest limit of the design.

**Independent anchoring.** A `audit_chain_checkpoint` table records `(tenant_id, sequence, chain_hash, checkpointed_at, external_anchor_ref)` periodically, with the anchor published to a location outside the platform's control. `INV-AUD-02` requires this: verification material stored only alongside the events is defeated by the same adversary who could alter them.

**Append-only enforcement.** No `UPDATE` or `DELETE` grant to `app_runtime` on `audit_event`. `DELETE` on `audit_event_payload` is granted only to the erasure role. `INV-AUD-01` is `DB`.

| Index | Columns | Serves |
|---|---|---|
| `pk_audit_event` | `(id)` | Event fetch |
| `ux_audit_event__sequence` | UNIQUE `(tenant_id, sequence)` | **Chain verification in order** — the integrity check reads sequentially |
| `ix_audit_event__object` | `(tenant_id, object_kind, object_id, occurred_at DESC)` | **"What happened to this object"** — the most common investigative query |
| `ix_audit_event__actor` | `(tenant_id, actor_id, occurred_at DESC)` | "What did this principal do" — insider investigation and volume anomaly detection (`SEC-PLT-002`) |
| `ix_audit_event__type_time` | `(tenant_id, event_type, occurred_at DESC)` | Event-type reporting; restricted-reveal audit (`PRD-AUD-003`) |
| `ix_audit_event__denied` | `(tenant_id, actor_id, occurred_at DESC)` WHERE outcome = `DENIED` | **Enumeration detection** (`SEC-PLT-003`) — sustained denials from one principal |
| `ix_audit_event__break_glass` | `(tenant_id, occurred_at DESC)` WHERE `break_glass_ref IS NOT NULL` | Break-glass activity, visible to the tenant (`SEC-TEN-030`) |
| `ix_audit_event__scope_subtree` | `(tenant_id, scope_ancestor_path)` GIN ⚙ | Scope-filtered audit search — audit read is a disclosure surface and must be scope-constrained |
| `ix_audit_payload__erasure` | on payload: `(tenant_id)` | Erasure batch selection |

**Partitioning.** Range by `occurred_at`, monthly, from the outset. `audit_event_payload` partitioned identically and aligned (`CON-DAT-024`), because the join is on every detailed audit read.

**Retention.** Configurable with a product minimum (`PRD-AUD-010`). Archived to cold storage before partition drop. **Legal hold pins a partition against drop** — implemented as a hold register consulted by the retention job, because a per-row hold on a partitioned append-only table cannot prevent a partition drop.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `CON-DAT-027` | The audit hash chain MUST cover the payload hash rather than the payload content, so that payload erasure leaves the chain verifiable. | `PRD-AUD-009`. Chaining the content would make erasure destroy verifiability; chaining the hash preserves proof that no event was inserted, removed, or reordered while permitting content removal. | M | AT |
| `CON-DAT-028` | Chain checkpoints MUST be anchored outside the platform's control at a configured interval. | `INV-AUD-02`. Verification material stored only alongside the events it protects is defeated by the same adversary who could alter them — including a compromise of the platform itself (DOC-26 T10). | M | AT |
| `CON-DAT-029` | Legal hold MUST be enforced by a register consulted before partition drop, not by a per-row flag. | A per-row hold cannot prevent a partition drop, and the retention mechanism is partition drop (`CON-DAT-023`). A row-level flag would be silently ineffective. | M | AT |

### 20.2 Identity and authorization

Compact specifications; semantics in DOC-06 and DOC-07.

```sql
principal (
  id, tenant_id, external_subject text, kind text NOT NULL,   -- HUMAN|SERVICE
  display_name, email_normalized text,
  lifecycle_state text NOT NULL,                              -- ACTIVE|SUSPENDED|DEPROVISIONED
  entitlement_tier text NOT NULL,                             -- LIC-PLT-003
  mfa_enrolled bool NOT NULL DEFAULT false,
  UNIQUE (tenant_id, external_subject),
  UNIQUE (tenant_id, email_normalized)                        -- where not null
)

service_principal (
  principal_id uuid PRIMARY KEY FK RESTRICT,                  -- eleventh PK exception
  tenant_id uuid NOT NULL,
  credential_kind text NOT NULL,                              -- MTLS|DPOP|HMAC_LEGACY
  pinned_scope jsonb NOT NULL,                                -- SEC-AUZ-035: never payload-asserted
  expires_at timestamptz NOT NULL,                            -- expiring by default (PRD-IAM-008)
  last_used_at timestamptz,
  CHECK (expires_at > created_at)
)

session (
  id, tenant_id, principal_id uuid NOT NULL,
  issued_at, absolute_expires_at, idle_expires_at timestamptz NOT NULL,
  revoked_at timestamptz, revocation_reason text,
  step_up_at timestamptz,                                     -- PRD-IAM-003
  source_context jsonb
)

permission (
  code text PRIMARY KEY,                                      -- twelfth PK exception: product-fixed catalogue
  domain text NOT NULL, resource text NOT NULL, action text NOT NULL,
  is_restricted_data bool NOT NULL,                           -- SEC-AUZ-005
  minimum_entitlement_tier text NOT NULL
)

role (
  id, tenant_id, code text NOT NULL, label_i18n jsonb NOT NULL,
  derived_from_template text,
  lifecycle_state text NOT NULL,
  UNIQUE (tenant_id, code)
)

role_permission (
  tenant_id, role_id uuid NOT NULL FK RESTRICT,
  permission_code text NOT NULL FK RESTRICT,
  PRIMARY KEY (tenant_id, role_id, permission_code)            -- thirteenth PK exception
)

role_assignment (
  id, tenant_id, principal_id uuid NOT NULL, role_id uuid NOT NULL FK RESTRICT,
  scope_pattern text NOT NULL,                                 -- TENANT|SUBTREE|LEAF_SET|SELF
  scope_node_ids uuid[] NOT NULL DEFAULT '{}',
  include_archived bool NOT NULL DEFAULT false,
  valid_until timestamptz, state text NOT NULL,
  granted_by uuid NOT NULL
)

object_grant (
  id, tenant_id, principal_id uuid NOT NULL,
  object_kind text NOT NULL, object_id uuid NOT NULL,
  valid_from, valid_until timestamptz NOT NULL,                -- mandatory (SEC-AUZ-032)
  state text NOT NULL,
  CHECK (valid_until > valid_from)
)

sod_constraint (
  id, tenant_id, name,
  conflicting_permission_codes text[] NOT NULL,
  enforcement text NOT NULL CHECK IN ('GRANT_TIME','ACTION_TIME','BOTH'),
  is_default bool NOT NULL DEFAULT false,
  relaxed_by, relaxed_at, relaxation_reason
)

delegation (
  id, tenant_id, delegator_id uuid NOT NULL, delegate_id uuid NOT NULL,
  permission_codes text[] NOT NULL, scope_node_ids uuid[] NOT NULL,
  valid_from, valid_until timestamptz NOT NULL,
  CHECK (delegator_id <> delegate_id),
  CHECK (valid_until > valid_from)
)
```

| Index | Columns | Serves |
|---|---|---|
| `ix_role_assignment__principal` | `(tenant_id, principal_id)` WHERE state = `ACTIVE` | **Scope resolution on every request** — the platform's highest-frequency authorization read |
| `ix_role_assignment__role` | `(tenant_id, role_id)` WHERE state = `ACTIVE` | "Who holds this role" — the inverse inspection of `SEC-AUZ-046` |
| `ix_role_assignment__node` | `(scope_node_ids)` GIN ⚙ | "Who is authorized for this node" — access review |

#### `asset_grant` — a capability over one asset (`SEC-AUZ-052` – `055`)

Separate from `role_assignment` because it answers a different question. A role assignment grants a
permission over a *subtree of the organization tree*; this grants a capability over *one asset*. See
DOC-07 §13.2 for why neither can be expressed as the other, and note that it is also distinct from
the external object grant of §13.1 despite the shared word.

```
asset_grant (
    id, tenant_id,
    asset_id       → asset,
    principal_id,
    capability     OWN | RAISE_REQUEST,        -- product-fixed; a CHECK, not a lookup table
    granted_by, granted_at,
    revoked_at, revoked_reason                 -- tombstoned, never deleted
)
```

| Index | Definition | Query it serves |
|---|---|---|
| `uq_asset_grant__live` | `(tenant_id, asset_id, principal_id, capability)` WHERE `revoked_at IS NULL` | One live grant per person per capability; partial, so the same grant can be made again after revocation |
| `ix_asset_grant__asset` | `(tenant_id, asset_id, capability)` WHERE `revoked_at IS NULL` | "Who may request against this project" — read on every intake form and every project access panel |
| `ix_asset_grant__principal` | `(tenant_id, principal_id, capability)` WHERE `revoked_at IS NULL` | "What does this person own" — the profile panel, and the offboarding check that must run before an account is disabled |

**No expiry column, deliberately.** `SEC-AUZ-032` mandates expiry on the *external* object grant of
DOC-07 §13.1; `SEC-AUZ-052` states why this one is different and must not inherit that rule.

#### `assessment_request_participant` — the delivery side of one request

```
assessment_request_participant (
    id, tenant_id,
    request_id     → assessment_request,
    principal_id,
    participation  DELIVERY,
    added_by, added_at,
    removed_at, removed_reason                 -- removed, never deleted: their comments remain
)
```

| Index | Definition | Query it serves |
|---|---|---|
| `uq_asm_participant__live` | `(tenant_id, request_id, principal_id)` WHERE `removed_at IS NULL` | One live participation per person per request |
| `ix_asm_participant__request` | `(tenant_id, request_id)` WHERE `removed_at IS NULL` | The participant panel, and the authority test that runs on every comment |
| `ix_asm_participant__principal` | `(tenant_id, principal_id)` WHERE `removed_at IS NULL` | "Which assessments is this person on" — how a developer finds their work, and what an offboarding check reassigns |

#### `finding` — the remediation claim (DOC-09 §7)

Three columns rather than a state, because the deployed finding lifecycle is two states. A claim is
not a closure.

```
finding (
    …
    remediation_claimed_at  timestamptz,
    remediation_claimed_by  uuid,              -- CHECK: both or neither
    remediation_note        text
)
```

| Index | Definition | Query it serves |
|---|---|---|
| `ix_finding__awaiting_retest` | `(tenant_id, remediation_claimed_at)` WHERE `remediation_claimed_at IS NOT NULL AND state = 'OPEN'` | The retest queue, oldest first. Partial, so the index is the size of the queue rather than of the estate |

#### `assessment_request_scope_asset` — what a request was raised against

Distinct from `assessment_scope_asset`, which hangs off the *assessment* and therefore does not exist
until an assessor is named. Intake records a subject before any assessor exists, and the two diverge
on purpose: one is what was asked for, the other what was covered.

```
assessment_request_scope_asset (
    tenant_id, request_id → assessment_request, asset_id,
    named_by_requester    boolean,             -- the project chosen, vs the application derived
    created_at, created_by
)
```

| Index | Definition | Query it serves |
|---|---|---|
| `ix_asm_req_scope__asset` | `(tenant_id, asset_id)` | "Every request raised against this project, newest first" — the paginated panel at the foot of a project dashboard, and the one list that grows without bound |
| `ix_object_grant__principal` | `(tenant_id, principal_id)` WHERE state = `ACTIVE` | External assessor authorization |
| `ix_object_grant__object` | `(tenant_id, object_kind, object_id)` | "Who has a grant on this object" |
| `ix_session__principal_active` | `(tenant_id, principal_id)` WHERE `revoked_at IS NULL` | Session revocation (`NFR-SEC-001`) and the user's own session list |
| `ix_service_principal__expiring` | `(tenant_id, expires_at)` | Expiring credentials — the notification that prevents accumulation |
| `ix_principal__deprovision` | `(tenant_id, lifecycle_state)` | Deprovisioning reconciliation from the identity source |

```sql
break_glass_grant (
  id, tenant_id, principal_id uuid NOT NULL,
  justification text NOT NULL, ticket_reference text NOT NULL,
  scope_limit jsonb NOT NULL,                                 -- SEC-TEN-031: RESTRICTED excluded by default
  approver_id uuid NOT NULL,                                  -- dual control (SEC-TEN-027)
  valid_from, valid_until timestamptz NOT NULL,
  tenant_notified_at timestamptz,                             -- non-suppressible (SEC-TEN-029)
  state text NOT NULL,
  CHECK (approver_id <> principal_id),
  CHECK (length(justification) > 0 AND length(ticket_reference) > 0),
  CHECK (valid_until > valid_from)
)
```

**On `CHECK (approver_id <> principal_id)`.** The third constraint chosen for engine enforcement rather than domain-only. Self-approved break-glass is standing access with extra steps; making it unrepresentable is cheap and the failure would be severe and cross-tenant.

### 20.3 Notification, integration, knowledge, AI — compact

```sql
notifiable_event (code text PRIMARY KEY, default_audience jsonb, is_digestible bool NOT NULL,
                  is_mandatory bool NOT NULL)                 -- PRD-NTF-012

subscription (id, tenant_id, principal_id, event_code text FK RESTRICT,
              channel text NOT NULL, digest_schedule text,
              is_enabled bool NOT NULL,
              UNIQUE (tenant_id, principal_id, event_code, channel))

notification_delivery (id, tenant_id, principal_id, event_code, channel,
                       object_kind, object_id,
                       rendered_at timestamptz NOT NULL,      -- partition key
                       scope_evaluated_at timestamptz NOT NULL, -- PRD-NTF-007
                       state text NOT NULL, attempt_count int NOT NULL,
                       failure_reason text, delivered_at timestamptz)

connector (id, tenant_id, code, connector_kind text NOT NULL,
           configuration jsonb NOT NULL,                      -- egress destinations are config (PRD-CON-007)
           credential_ref text,                               -- vault; never a value (INV-CON-01)
           is_enabled bool NOT NULL)

connector_health (connector_id uuid PRIMARY KEY FK RESTRICT,  -- fourteenth PK exception
                  tenant_id, last_success_at, consecutive_failures int NOT NULL,
                  last_failure_class text, last_failure_at,
                  circuit_state text NOT NULL, backoff_until timestamptz)

outbound_reference (id, tenant_id, subject_kind, subject_id,
                    connector_id uuid NOT NULL FK RESTRICT,
                    external_id text NOT NULL, external_state text,
                    divergence_detected_at timestamptz,        -- PRD-CON-012: surfaced, not reconciled
                    UNIQUE (tenant_id, connector_id, external_id))

knowledge_article (id, tenant_id, code, title, body text NOT NULL,
                   body_format text NOT NULL, owner_principal_id uuid NOT NULL,
                   review_due_at timestamptz NOT NULL,         -- PRD-KBS-001
                   version int NOT NULL, state text NOT NULL,
                   search_vector tsvector, scope_node_id uuid)

knowledge_article_link (id, tenant_id, article_id uuid NOT NULL FK RESTRICT,
                        target_kind text NOT NULL, target_ref text NOT NULL)

ai_suggestion (id, tenant_id, capability text NOT NULL,
               subject_kind, subject_id,
               content jsonb NOT NULL, citations jsonb NOT NULL,
               provider text NOT NULL, model_id text NOT NULL, model_version text NOT NULL,
               prompt_hash bytea NOT NULL, context_refs jsonb NOT NULL,
               state text NOT NULL,                            -- PROPOSED|PROMOTED|DISMISSED|EXPIRED
               promoted_by, promoted_at, resulting_change_ref uuid,
               generated_at timestamptz NOT NULL,               -- partition key
               expires_at timestamptz NOT NULL)
```

**On `ai_suggestion` being in its own table with no write path into domain tables.** `INV-AIC-01` requires that no code path exist from AI into a domain aggregate. At the persistence layer this is enforced by the AI module having no `INSERT` or `UPDATE` grant on any domain table — a privilege-level guarantee rather than a code convention. Recorded because it is the cheapest possible enforcement of the platform's most important AI constraint.

| Index | Columns | Serves |
|---|---|---|
| `ix_notif_delivery__failed` | `(tenant_id, rendered_at DESC)` WHERE state = `FAILED` | Persistent delivery failure surfaced to administrators (`PRD-NTF-011`) |
| `ix_notif_delivery__principal` | `(tenant_id, principal_id, rendered_at DESC)` | The in-product notification centre (`PRD-NTF-010`) |
| `ix_connector_health__unhealthy` | `(tenant_id)` WHERE `consecutive_failures > 0` | **The integration health view** — silent failure is how coverage gaps form (`PRD-CON-003`) |
| `ix_outbound_ref__divergent` | `(tenant_id, divergence_detected_at DESC)` WHERE not null | Divergence for human resolution (`PRD-CON-012`) |
| `ix_knowledge__review_due` | `(tenant_id, review_due_at)` WHERE state = `PUBLISHED` | Guidance needing review — stale guidance is followed |
| `ix_knowledge__search` | `(search_vector)` GIN ⚙ | Knowledge search alongside work items (`PRD-KBS-004`) |
| `ix_ai_suggestion__subject` | `(tenant_id, subject_kind, subject_id)` WHERE state = `PROPOSED` | Suggestions on an object |
| `ix_ai_suggestion__expiring` | `(tenant_id, expires_at)` WHERE state = `PROPOSED` | Expiry job |

**Partitioning.** `notification_delivery` and `ai_suggestion` range by month at their §10.2 triggers.

### 20.4 `attribute_schema`

The registry underpinning §8.2. Owned by the `schema-registry` kernel module.

```sql
attribute_schema (
  id, tenant_id,
  target_kind text NOT NULL,                        -- ASSET|WORK_ITEM|ASSESSMENT|REQUEST
  target_type_id uuid,
  field_key text NOT NULL,                          -- immutable
  data_type text NOT NULL,
  validation jsonb NOT NULL,
  is_required, is_searchable, is_exportable bool NOT NULL,
  visibility_rule jsonb,
  index_slot smallint,                              -- CON-DAT-018 bounded slots
  display_order int NOT NULL,
  lifecycle_state text NOT NULL,
  UNIQUE (tenant_id, target_kind, target_type_id, field_key),
  UNIQUE (tenant_id, target_kind, target_type_id, index_slot),   -- where not null
  CHECK (NOT is_searchable OR index_slot IS NOT NULL)
)
```

**On `index_slot` and the final `CHECK`.** `CON-DAT-018` bounds the number of indexable attributes per type, provisioned as generic slots. The constraint makes the bound structural: a field cannot be marked searchable without a slot, and slots are unique per type. This is the honest expression of the limitation recorded in §8.2 — configurability of searchable fields is bounded, and the bound is visible in the schema rather than discovered at runtime.

---

## 21. Read Models

Projections per DOC-02 §11.2, in a separate store. Rebuildable (`CON-PLT-026`), tenant-partitioned structurally (`CON-PLT-029`), with coverage materialized alongside measures (`CON-PLT-028`).

| Projection | Grain | Key columns | Coverage columns | Lag budget |
|---|---|---|---|---|
| `rm_posture_aggregate` | tenant × node × period | node, ancestor path, period | `assets_in_scope`, `assets_with_current_data`, `assets_never_measured`, `confidence` | 60 s |
| `rm_finding_index` | finding × impact | scope path, severity ordinal, score band, state category, assignee | `last_detected_at`, `source_freshness` | 10 s |
| `rm_work_queue` | work item | scope path, state category, assignee, due, sla status | — | 5 s |
| `rm_workload_current` | member / team × period | subject, period | `contributing_member_count` | 60 s |
| `rm_coverage_state` | asset | asset, status, age, quality | the measure *is* coverage | 60 s |
| `rm_activity_timeline` | work item × event | item, occurred_at, event kind | — | 5 s |
| `rm_latest_risk_score` | subject | subject kind and id | `coverage_confidence` | 60 s |

**On `rm_latest_risk_score` existing separately from `risk_score`.** Score partitions are dropped after the reproducibility window (§18.2). The latest score per subject must survive that, so it is projected. Without this projection, dropping a 25-month-old partition would remove the current score for any subject not rescored since.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `CON-DAT-030` | Every projection MUST carry `tenant_id` as a structural partition or index prefix, and a projection query MUST NOT be able to span tenants. | `CON-PLT-029`, DOC-24 §6.2 entry 4. Projections are aggregation surfaces, which is where cross-tenant leakage through counts occurs. | M | AT, PT |
| `CON-DAT-031` | Every projection MUST be rebuildable from the operational store or the event stream, and the rebuild MUST be verifiable by comparison. | `CON-PLT-026`. Projections are where subtle aggregation errors live, and a defect is otherwise permanent. | M | AT |
| `CON-DAT-032` | The latest computed value of any measure whose history is subject to partition drop MUST be retained in a projection. | Otherwise retention removes current values along with historical ones — a data loss disguised as retention. | M | AT |

---

## 22. Consolidated Tables

### 22.1 Invariant enforcement mapping

All 106 invariants from DOC-03, classified per LC-02.

| Context | Total | `DB` | `BOTH` | `DOMAIN` only |
|---|---|---|---|---|
| Tenant | 4 | 1 | 1 | 2 |
| Organization & Scope | 17 | 6 | 3 | 8 |
| Asset Inventory | 24 | 9 | 3 | 12 |
| Assessment | 29 | 12 | 4 | 13 |
| Vulnerability Management | 29 | 11 | 3 | 15 |
| Composition Analysis | 15 | 7 | 1 | 7 |
| Risk & Prioritization | 11 | 5 | 1 | 5 |
| Work Management | 17 | 6 | 2 | 9 |
| Capacity | 6 | 2 | 0 | 4 |
| Ingestion | 8 | 2 | 0 | 6 |
| AI Assistance | 9 | 1 | 0 | 8 |
| Generic contexts | 20 | 6 | 1 | 13 |
| **Total** | **189** | **68** | **22** | **99** |

**On the total exceeding 106.** DOC-03 §19 counted 106 invariants across thirteen contexts as *distinct model constraints*; this table counts each invariant once per context in which it is enforced, and several — the tenant invariants in particular — are enforced in every context. The discrepancy is a counting difference, not an inconsistency, and is recorded rather than silently reconciled.

**Of the twelve invariants DOC-03 §19 identified as unrecoverable if violated in v1**, eleven have at least partial database enforcement:

| Invariant | Enforcement |
|---|---|
| `INV-TEN-01`, `INV-TEN-02` | Row-level policy on every table (`CON-DAT-012`) |
| `INV-AST-05` | Single nullable `owning_node_id` column |
| `INV-VUL-01` | Unique constraint on tenant-scoped fingerprint |
| `INV-VUL-04` | `finding_fingerprint_input` table exists and is populated |
| `INV-VUL-13` | `match_run.coverage_confirmed` check constraint |
| `INV-WRK-04` | No `UPDATE`/`DELETE` grant plus a rejecting rule |
| `INV-ING-01` | `DOMAIN` only — a single computation site is a code property |
| `INV-RSK-02` | `factor_inputs` and `factor_contributions` NOT NULL |
| `INV-AUD-01` | No `UPDATE`/`DELETE` grant |
| `INV-AIC-01` | No `INSERT`/`UPDATE` grant to the AI module on domain tables |
| Scope descriptor mechanism | Embedded columns with an immutability trigger |

**`INV-ING-01` is the exception** and is worth naming: "the fingerprint is computed in one place" cannot be expressed as a constraint. It is enforced by module boundaries (`CON-PLT-013`) and by the absence of a fingerprint-writing path outside Ingestion. It is therefore the most fragile of the twelve at the persistence layer, and DOC-16 owes it a test asserting no other module writes `fingerprint_digest`.

### 22.2 Partitioning summary

| Table | Strategy | From | Retention |
|---|---|---|---|
| `audit_event` + `audit_event_payload` | Range monthly, aligned | Immediate | Archive then drop; hold register pins partitions |
| `work_item_state_transition` | Range monthly | Immediate | Archive, never drop |
| `risk_score` | Range monthly | Immediate | Drop after reproducibility window; latest in projection |
| `component_entry` | Hash by tenant, 32 | Immediate | Follows snapshot |
| `notification_delivery` | Range monthly | ≥ 5,000,000 | Drop |
| `ai_suggestion` | Range monthly | ≥ 1,000,000 | Drop |
| `automation_execution` | Range monthly | ≥ 5,000,000 | Drop |
| `workload_snapshot` | Range yearly | Immediate | Retain |
| `sbom_snapshot` | Hash by tenant | ≥ 20,000 | Age and count per artifact |
| `finding` + `finding_asset_impact` | Hash by tenant, aligned | ≥ 2,000,000 | No deletion; cold partition |
| `match_run` | Range monthly | ≥ 5,000,000 | Drop |

### 22.3 Primary key exceptions

Fourteen, all satisfying the §Part 2 criterion — no identity apart from parents.

`org_closure`, `finding_fingerprint_input`, `finding_secret_detail`, `component_entry`, `sbom_coverage_state`, `tenant_id_reservation`, `work_item_watcher`, `work_item_read_state`, `workload_snapshot`, `audit_event_payload`, `service_principal`, `permission`, `role_permission`, `connector_health`.

`permission` is the one that differs: its key is a stable product-fixed `code` rather than a foreign key, because the catalogue is code and its identifiers appear in configuration exports and static analysis (`SEC-AUZ-050`) where a UUID would be unreadable.

### 22.4 Engine capability dependencies

Constructs marked ⚙ throughout, consolidated so a substitution can assess the gap.

| Capability | Used for | If absent |
|---|---|---|
| Row-level security with forced owner enforcement | `CON-DAT-012` | **Disqualifying.** Tenant isolation would become query discipline (DOC-24 §5.1) |
| Array columns with containment indexing | `scope_ancestor_path`, `scope_node_ids`, `labels`, `mentioned_principal_ids` | Significant redesign: historical authorization would need a reconstructable closure |
| Typed document column with expression indexing | Custom attributes (§8.2) | Column-per-field or EAV, both rejected in §8.2 |
| Declarative partitioning with partition-wise joins | §10, `CON-DAT-024` | Manual sharding; the `finding`/`impact` join degrades |
| Full-text search combinable with a scope predicate in one query | `PRD-WRK-018`, §16.2 | **The strongest constraint on search technology.** Separate search infrastructure filtered after retrieval leaks counts (DOC-24 §6.2 entry 3) |
| Partial and expression indexes | Operational queues, temporal current-row, attribute filters | Full indexes at several times the size on the largest tables |
| Constraint triggers | The three engine-enforced guards | Domain-only enforcement, losing the survive-a-defect property |
| Time-ordered UUID generation | §5.1 | Application-side generation is acceptable |

---

## 23. Migration Strategy

### 23.1 Principles

| Principle | Reason |
|---|---|
| **Expand, migrate, contract** — never a single breaking change | `NFR-DEP-004` requires upgrade without extended downtime. A breaking change requires application and schema to deploy atomically, which they cannot |
| **Every migration is reversible or forward-only by declaration** | An irreversible migration must be a deliberate, reviewed decision, not an accident of writing |
| **No blocking operation on a table above Medium volumes** | `CON-DAT-022`. A blocking index build or table rewrite on `finding` or `component_entry` is an outage |
| **Backfill is a separate, resumable, throttled job** | A backfill inside a migration transaction locks the table for its duration |
| **Migrations run as `migration_runner` with policy bypass** | Enumerated per `SEC-TEN-008`; every run audited (`CON-DAT-014`) |
| **A cross-tenant assertion runs after every migration** | `SEC-TEN-049`. Migrations run with enforcement bypassed and are the highest-risk operation in the platform |

### 23.2 The operations that require particular care

| Operation | Approach |
|---|---|
| Adding a `NOT NULL` column to a large table | Add nullable, backfill in batches, add the constraint as `NOT VALID` then validate concurrently |
| Changing a hash partition count | **Full table rewrite.** `component_entry` and `finding` partition counts must be fixed before first production data (DOC-04 Part 2 note to DOC-15) |
| Re-fingerprinting after an algorithm change | Not a schema migration but a data migration reading `finding_fingerprint_input`, computing new digests, and merging. Must preserve triage state, assignment, comments, and exceptions (`INV-VUL-05`) — the most complex data migration the platform will perform |
| Re-canonicalizing components | Reads `component.purl_original`, recomputes, merges duplicates, and re-points entries. Bounded by `component` rather than `component_entry`, which makes it tractable |
| Adding a searchable attribute | Requires an index on a slot (`CON-DAT-018`), which is a concurrent index build |
| Taxonomy ordinal change | Rewrites nothing but invalidates every cached comparison and every stored band; requires score recomputation |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `CON-DAT-033` | Schema migrations MUST follow expand-migrate-contract and MUST NOT require atomic deployment of application and schema. | Atomic deployment is not achievable with rolling application updates, and requiring it forces downtime on every schema change. | M | AT, AR |
| `CON-DAT-034` | Backfill MUST be a separate resumable throttled job, not part of a migration transaction. | A backfill inside a transaction locks the table for its duration, which on the largest tables is an outage. | M | AT |
| `CON-DAT-035` | Hash partition counts MUST be fixed before first production data, and changing one MUST be treated as a full-table rewrite requiring a planned maintenance window. | Changing a hash partition count redistributes every row. Discovering this after production data exists converts a configuration choice into a multi-hour outage. | M | DI, AR |
| `CON-DAT-036` | Every migration MUST be followed by a cross-tenant assertion before being considered complete. | `SEC-TEN-049`. Migrations bypass row-level enforcement, so a defect produces cross-tenant contamination that no other control would catch. | M | AT |

---

## 24. Retention, Erasure, and Closing

### 24.1 Retention

| Data | Default | Mechanism | Floor |
|---|---|---|---|
| Audit events | 7 years | Archive then partition drop; hold register | Product minimum, tenant-configurable above it |
| Work item transitions | Life of the item | Archive; never dropped | — |
| Risk scores | 24 months | Partition drop; latest in projection | 12 months for reproducibility |
| SBOM snapshots | 12 months or 20 per artifact | Batch delete with entries | 2 snapshots, for change-set computation |
| Component entries | Follows snapshot | Cascade with snapshot deletion | — |
| Evidence | Tenant-configured within a product maximum | Object destruction; row marked | — |
| Findings | Indefinite | No deletion; cold partition | — |
| Notification deliveries | 90 days | Partition drop | — |
| AI suggestions | 90 days | Partition drop | — |
| Quarantined records | 30 days after resolution | Delete | — |
| Test credentials | Nulled at rotation attestation | Column null; vault entry destroyed | — |
| Secret values | Nulled at rotation attestation | Column null; digest and mask retained | — |

**On evidence retention having a product maximum rather than only a minimum.** `INV-ASM-24` treats indefinite retention of exploit tooling as an accumulating liability. This is the only data category where the product caps how long a tenant may keep something, and the reason is that the liability is the platform's as well as the tenant's.

### 24.2 Erasure

`PRD-AUD-009` and `CON-DAT-001`. The physical mechanism, by data location:

| Location | Mechanism |
|---|---|
| Audit event payload | Delete the `audit_event_payload` row; set `payload_erased_at` and basis. Chain remains verifiable (`CON-DAT-027`) |
| Comment body | Redact: body replaced, revisions redacted, row retained (`INV-WRK-08`) |
| Evidence content | Destroy the object; retain the row with a destruction marker |
| Finding description and evidence references | Redact the text; retain the finding — the finding's existence is not personal data |
| Principal record | Anonymize: `display_name` and `email_normalized` replaced with a stable pseudonym; `id` retained so that every attribution remains resolvable |
| Notification deliveries | Delete rows |
| Capacity measures for a member | Delete member-grained rows; team aggregates recomputed with a reduced contributing count |

**On anonymizing rather than deleting `principal`.** Deleting a principal row would orphan every audit attribution, every comment authorship, and every transition actor — destroying the audit trail's completeness to satisfy an erasure request. Anonymization retains the identifier and the structure while removing the identifying content, which satisfies both obligations. The pseudonym is stable so that "the same person did these three things" remains visible without revealing who.

**Legal hold** is enforced by the register of `CON-DAT-029` and blocks erasure as well as retention expiry (`PRD-AUD-009`, `SEC-TEN-042`).

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `CON-DAT-037` | Erasure MUST anonymize principal records rather than deleting them, retaining the identifier so that attributions remain resolvable. | Deleting a principal orphans every audit attribution and authorship, destroying the trail's completeness to satisfy an erasure request. A stable pseudonym satisfies both obligations. | M | AT |
| `CON-DAT-038` | Erasure MUST require elevated permission distinct from administration, MUST be dual-controlled, and MUST be blocked by legal hold. | Irreversible destruction, and the plausible mechanism for an insider covering activity (`SEC-AUZ-042`). | M | AT |
| `CON-DAT-039` | Every erasure MUST itself be audited, recording what was erased, on what basis, and by whom — without recording the erased content. | The trail must record that erasure occurred while not defeating it. | M | AT |

### 24.3 Extensibility considerations

**Adding a table** requires: `tenant_id` with a row-level policy, the common columns, an entry in the §22.2 partitioning assessment, indexes each naming a query, and an invariant mapping. `CON-DAT-012` and `SEC-TEN-003` make tenant-scoping the default, so an omission is safe rather than a breach.

**Adding a taxonomy value** is a row. **Adding a custom attribute** is a registry row, plus an index if searchable, bounded by slots. **Adding a module** adds a schema namespace with no cross-module foreign keys (`CON-DAT-004`).

**Reserved and inert in this release:** `component_entry.reachability`; `sbom_snapshot.source` values `PLATFORM_GENERATED` and `REGISTRY_DERIVED`, rejected by `CHECK`; the scoring model's `REACH` factor at weight zero.

**Deliberate rigidity.** Single-column UUID keys except the fourteen documented exceptions; no cross-module foreign keys; no soft-delete flags; taxonomies as tables not enums; append-only tables with no update grant; the audit chain covering payload hashes.

**Known extension costs.** Hash partition counts are fixed before production data (`CON-DAT-035`). Searchable attributes are bounded by index slots (§20.4) — the honest limit on `CFG-WRK-003`. Re-fingerprinting is the most complex data migration the platform will perform. Read model dimension changes require backfill.

### 24.4 Security considerations

The schema's security contribution is making controls structural rather than procedural:

| Control | Structural mechanism |
|---|---|
| Tenant isolation | Forced row-level policy on every table; raising context function; four separate credentials with three bypass roles unreachable from the application |
| Append-only audit | No `UPDATE`/`DELETE` grant; rejecting rule; hash chain; external anchor |
| Erasure without loss of verifiability | Chain covers payload hashes (`CON-DAT-027`) |
| No AI write path | AI module holds no write grant on any domain table |
| Secret containment | Encrypted column, separate table, nulled at rotation; no credential column on the request |
| Evidence containment | No content in the database; retention capped |
| Self-approval prevention | Constraint triggers on exception approval and break-glass |
| Coverage signal integrity | Trigger on `asset.last_confirmed_at` |
| Mass false-close prevention | `match_run.coverage_confirmed` check constraint |
| Unvalidated scoring activation | `scoring_model` check constraint |

**Residual risks.** Six of the twelve unrecoverable invariants have only partial database enforcement, and `INV-ING-01` has none — a single fingerprint computation site is a code property, enforced by module boundaries and owed a test (§22.1). Typed attribute documents are not schema-validated by the engine, so a domain defect writes data the engine accepts; `CON-DAT-019` reconciliation is the only detection. The `search_vector` trigger spans two tables, so a defect could leave search results stale in a way that is invisible — the search index is not a system of record but it is what users trust to find institutional memory.

### 24.5 Open questions

| ID | Bearing | Status |
|---|---|---|
| OQ-015 | Partition thresholds in §10.2 and §22.2; index selectivity assumptions; the storage estimates in §3, §6.6, §13.3, and §18.2 | **Open.** Values change; schema shape does not |

### 24.6 Notes for downstream documents

| Document | Note |
|---|---|
| DOC-05 | Keyset pagination on `ix_finding__open_by_scope` ending in `id`; collection filters must map to existing indexes; `ix_match_run__claim` is the one index not tenant-leading and the claim endpoint must account for it |
| DOC-06 | Owes the encryption scheme for `finding_secret_detail.secret_value_encrypted` and the vault decision (OQ-026); the four database credentials of §7.2 |
| DOC-09 | Owes state machines for every `state` column specified here: match run, import session, ownership claim, evidence availability, exception, service level clock, break-glass grant |
| DOC-14 | Owes the audit event catalogue populating `event_type`, the chain algorithm, the checkpoint interval, and the external anchor mechanism |
| DOC-15 | Owes four database credentials with three unreachable from the application; partition automation (`CON-DAT-025`); the search technology satisfying the §22.4 combinability requirement; fixed partition counts before production |
| DOC-16 | Owes: closure rebuild-and-compare; attribute reconciliation; a test that no module outside Ingestion writes `fingerprint_digest`; a schema test that `vulnerability_intelligence` has no tenant-derived column; chain verification across an erasure; a post-migration cross-tenant assertion; and a test that `app_runtime` cannot update or delete append-only tables |
| DOC-22 | `ix_component__match_lookup` with `ix_vuln_range__lookup` determine whether `NFR-SBM-002` is achievable; validate early against real volumes |

### 24.7 Requirements Summary

Thirty-six requirements, `CON-DAT-004` through `CON-DAT-039`, continuing DOC-01's sequence. All `MUST_HAVE`.

| Group | IDs | Count |
|---|---|---|
| Relationships and keys | `CON-DAT-004` – `008` | 5 |
| Column patterns | `CON-DAT-009` – `011` | 3 |
| Tenant enforcement | `CON-DAT-012` – `015` | 4 |
| Taxonomies and attributes | `CON-DAT-016` – `019` | 4 |
| Indexing | `CON-DAT-020` – `022` | 3 |
| Partitioning | `CON-DAT-023` – `025` | 3 |
| Projections | `CON-DAT-026`, `030` – `032` | 4 |
| Audit integrity | `CON-DAT-027` – `029` | 3 |
| Migration | `CON-DAT-033` – `036` | 4 |
| Erasure | `CON-DAT-037` – `039` | 3 |

---

## Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.1.0 | 2026-08-07 | Chief Software Architect | Adds the four tables and one column supporting object-level authority and intake: `asset_grant` (a capability over one asset, distinct from `role_assignment`'s subtree grant and from DOC-07 §13.1's external object grant), `assessment_request_participant`, the `finding` remediation-claim columns, and `assessment_request_scope_asset` — which exists because `assessment_scope_asset` hangs off the assessment and so records nothing until an assessor is named. Every index names the query it serves; `asset_grant` carries no expiry column and `SEC-AUZ-052` says why. | Pending |
| 0.1.0 | 2026-08-04 | Chief Software Architect; Principal Application Security Engineer | Part 1. Seven physical design principles including the deliberate absence of cross-module foreign keys with its cost stated; time-ordered UUID keys with the disclosure they accept; embedded scope descriptors with quantified storage cost; forced row-level security with a raising context function; taxonomy-as-table and typed-attribute storage with three honest costs; indexing methodology requiring every index to name its query; and thirteen tables across three modules. | Pending |
| 0.2.0 | 2026-08-04 | Chief Software Architect; Principal Application Security Engineer | Part 2. Thirty-one tables across assessment, vulnerability management, exception, and composition analysis. Database-level enforcement of the protective-control bypass pairing, the not-applicable reason requirement, and the prohibition on risk-accepting secret findings. Component identity interned tenant-scoped rather than globally, rejecting the more space-efficient option on tenant-boundary and inference grounds and reducing the largest table from ~200 to ~45 bytes per row. Six primary-key exceptions consolidated under one stated criterion. | Pending |
| 1.0.0 | 2026-08-04 | Chief Software Architect; Principal Application Security Engineer; Principal Security Architect | Part 3, completing the document. Adds work management including the append-only transition log with denormalized durations and the state-time SLA flag; capacity with minimum-group-size materialized on the measure; risk with self-contained scores whose storage cost is quantified as the price of reproducibility, and service level clocks with calendar snapshots and original due dates; ingestion; audit with the hash-chain-over-payload-hash design that reconciles erasure with verifiability, external anchoring, and hold-register-based partition protection; identity, authorization, notification, integration, knowledge, and AI schemas; seven read models including the latest-score projection that survives partition drop; consolidated invariant mapping identifying `INV-ING-01` as the one unrecoverable invariant with no database enforcement; fourteen primary-key exceptions; eight engine capability dependencies with the consequence of each absence; migration strategy including the six operations requiring particular care; and retention and erasure with principal anonymization rather than deletion. Thirty-six requirements. Status moves to `In review`. | Pending |

---

*End of DOC-04. Content complete at version 1.0.0.*
