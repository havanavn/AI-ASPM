---
document_id:    DOC-16
title:          Testing Strategy
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        1.0.0
status:         In review
owner:          Principal Application Security Engineer
authors:        [Principal Application Security Engineer, Chief Software Architect, Principal Security Architect]
reviewers:      []
last_updated:   2026-08-04
tier:           7
prerequisites:  [DOC-00, DOC-01, DOC-03, DOC-06, DOC-07, DOC-24]
depends_on:     [DOC-00, DOC-01, DOC-02, DOC-03, DOC-04, DOC-05, DOC-06, DOC-07, DOC-08, DOC-09, DOC-10, DOC-11, DOC-12, DOC-13, DOC-14, DOC-15, DOC-21, DOC-22, DOC-24, DOC-26, DOC-28]
supersedes:     null
adrs_relied_on: [ADR-003, ADR-005, ADR-027]
open_questions: [OQ-015]
requirement_domains: [TST]
security_review_required: true
---

# 16 — Testing Strategy

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope) · 2. [Strategy](#2-strategy) · 3. [Structural Tests](#3-structural-tests) · 4. [Invariant Tests](#4-invariant-tests) · 5. [Isolation Tests](#5-isolation-tests) · 6. [Authorization Tests](#6-authorization-tests) · 7. [Identity and Ingestion Tests](#7-identity-and-ingestion-tests) · 8. [Security Control Tests](#8-security-control-tests) · 9. [Workflow Tests](#9-workflow-tests) · 10. [AI Evaluation](#10-ai-evaluation) · 11. [Presentation and Honesty Tests](#11-presentation-and-honesty-tests) · 12. [Accessibility and Localization](#12-accessibility-and-localization) · 13. [Performance Tests](#13-performance-tests) · 14. [Operational Tests](#14-operational-tests) · 15. [Penetration Testing](#15-penetration-testing) · 16. [Traceability](#16-traceability) · 17. [Requirements](#17-requirements) · 18. [Closing](#18-closing)

---

## 1. Purpose and Scope

**In scope.** The test strategy and its emphasis; the structural, invariant, isolation, authorization, identity, security control, workflow, AI, presentation, accessibility, performance, and operational test suites; penetration testing scope; the traceability matrix and release gates.

**Out of scope.** Test implementation; test data generation tooling; the requirements being verified, which are owned by their documents.

**LC-01.** Requirements are `TST-nnn` with a domain code identifying the suite. `TST-PTR-003`, forward-referenced from DOC-00, is defined at §4.3.

**LC-02 — This document gathers what other documents owe it.** Twenty-two documents recorded obligations to DOC-16. They are collected here rather than restated, each referenced to the requirement it verifies. The collection *is* the document's principal contribution: an obligation recorded in twenty-two places and never gathered is an obligation nobody implements.

---

## 2. Strategy

### 2.1 Where emphasis goes, and why not a pyramid

A conventional pyramid — many unit tests, fewer integration, fewest end-to-end — optimizes for execution speed. That is the wrong optimization here, because **the platform's most consequential failures are not logic errors in a unit.**

They are: a query missing a tenant predicate; an enforcement point without an authorization check; a fingerprint that changes on rescan; a closure driven by a failed run; a coverage figure presented without its qualifier. Each is a *structural* or *cross-cutting* property, and none is detectable by testing a unit in isolation.

Emphasis is therefore placed on five suites that a pyramid would under-weight:

| Suite | Why it carries disproportionate weight |
|---|---|
| **Structural** (§3) | Enforces architecture at build time. Catches boundary erosion and role-name branching, which review demonstrably does not catch |
| **Isolation** (§5) | Cross-tenant disclosure is unrecoverable and disclosable. It must be asserted per access path, not assumed |
| **Authorization** (§6) | The platform's highest-likelihood serious defect, across twenty egress paths |
| **Identity** (§7) | Fingerprint failure is the most common cause of abandoned deployments and is unrecoverable |
| **Honesty** (§11) | Coverage and qualification failures produce confident wrong output, which is the failure mode the corpus exists to prevent |

### 2.2 The asymmetry that shapes the isolation and authorization suites

A missing tenant predicate or a missing authorization check returns **more** data, not less. It passes every functional test, passes review when the reviewer is focused on the feature, and produces no error.

Only a test that asserts the **absence** of data catches it. Those are different tests from the ones that assert the presence of correct data, and both are required.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-PLT-001` | For every access path, the suite MUST include a negative assertion that data outside the caller's tenant and scope is absent — not merely that authorized data is present. | The failure mode returns more data, not less, and produces no error. A positive test passes while the defect is present. | M | AT |
| `TST-PLT-002` | Test suite emphasis MUST be allocated to the five suites of §2.1, and coverage MUST NOT be reported as a single aggregate percentage. | An aggregate percentage lets high coverage of simple logic conceal absent coverage of the properties that matter. Reporting per suite makes the gap visible. | M | DI |

---

## 3. Structural Tests

Executed at build time; each blocks the build (`OPS-DEP-026`).

| # | Assertion | Verifies |
|---|---|---|
| S1 | No cross-module import outside a published contract surface | `CON-PLT-013` |
| S2 | Module dependency direction matches the DOC-03 §5.3 relationship patterns | `CON-PLT-014` |
| S3 | Module dependency graph is acyclic | `CON-PLT-016` |
| S4 | No module accesses another module's persistence | `CON-PLT-015` |
| S5 | Kernel modules have no domain dependency | `CON-PLT-011` |
| S6 | Domain layer has no persistence, framework, transport, or serialization dependency | `CON-PLT-017` |
| S7 | **No comparison of a role identifier against a literal** | `SEC-AUZ-002`, `SEC-AUZ-050` |
| S8 | No data access outside the tenant-context gate | `CON-PLT-036` |
| S9 | No query execution without an authorization decision input | `CON-PLT-037` |
| S10 | AI module holds no write path to a domain aggregate | `PRD-AIC-021` |
| S11 | Every audit-emitting path references a catalogued event type | `SEC-AUD-006` |
| S12 | Every API operation carries an annotation class | `PRD-API-019` |
| S13 | Every requirement identifier referenced in code or tests exists in the register | `PRD-PLT-012` |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-PLT-003` | S1 through S13 MUST be build gates, and an exemption MUST be individually annotated, reviewed, and counted so its growth is visible. | These assertions catch the failures review does not: a cross-module import reads as a normal import, and a role comparison reads naturally to a reviewer focused on the feature. Unexplained suppressions accumulate silently; a visible count is a review signal (`SEC-AUZ-051`). | M | AT |

---

## 4. Invariant Tests

### 4.1 Coverage

DOC-03 specifies 106 invariants. Each requires a test asserting that the invariant cannot be violated through any available path — not merely that the happy path preserves it.

### 4.2 The twelve that must be right in v1

DOC-03 §19 identifies twelve invariants as unrecoverable if violated. Each requires a dedicated suite rather than a single test.

| Invariant | Suite content |
|---|---|
`INV-TEN-01`, `INV-TEN-02` | §5 in full |
| `INV-AST-05` | Ownership cannot be set to more than one node through any path including merge and import |
| `INV-VUL-01` | Fingerprints are tenant-scoped in their inputs; a crafted cross-tenant submission does not deduplicate against another tenant |
| `INV-VUL-04` | Fingerprint inputs are retained and sufficient to recompute; re-fingerprinting from them preserves triage state, assignment, comments, and exceptions |
| `INV-VUL-13` | A failed, cancelled, stale-intelligence, or low-quality-snapshot match run cannot drive closure through any path |
| `INV-WRK-04` | No update or delete path exists on the transition log at any privilege available to the application |
| `INV-ING-01` | **No module outside Ingestion writes a fingerprint** — the one unrecoverable invariant with no database enforcement (DOC-04 §22.1) |
| `INV-RSK-02` | A score records inputs sufficient to recompute it identically without access to data that has since changed |
| `INV-AUD-01` | No update or delete path on audit events at any privilege |
| `INV-AIC-01` | Structural test S10 plus a runtime assertion |
| Scope descriptor mechanism | §4.3 |

### 4.3 Scope descriptor and historical reproducibility

##### `TST-PTR-003` — Scope descriptor and historical reproducibility

**Statement.** The suite MUST assert that after a reorganization: a historical report reproduces identically to its pre-reorganization output; a principal formerly authorized retains read access to objects that arose under their prior accountability and gains none to objects created after the move; the service level policy in effect at a finding's creation continues to apply; and no scope descriptor on an existing object is modified.

**Rationale.** `PRD-ORG-011` and `PRD-WRK-042`. The mechanism cannot be retrofitted, and its failure is silent: a historical report that changes after a reorganization looks like a data error rather than an authorization defect, and the four assertions above are the only way to distinguish them.

**Priority.** MUST_HAVE · **Verification.** AT

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-PLT-004` | Every invariant in DOC-03 MUST have a test asserting it cannot be violated through any available write path, including bulk operations, import, migration, and administrative tooling. | A happy-path test confirms the invariant holds when nothing unusual happens. The paths that violate it are the unusual ones: bulk, import, migration. | M | AT |
| `TST-PLT-005` | Invariant tests MUST execute against the domain layer without a database where the invariant is domain-enforced. | `CON-PLT-017` exists so that invariant tests are fast, because slow tests are run less often and invariants then regress. A domain test requiring a database defeats the reason for the layering rule. | M | AT |

---

## 5. Isolation Tests

`SEC-TEN-046`. Two tenants; every assertion is that tenant B's data is absent from tenant A's result.

| # | Path | Assertion |
|---|---|---|
| I1 | API single-object read | Identifier substitution returns not-found, indistinguishable from non-existence, in equivalent time |
| I2 | API collection read | No foreign row; count is post-filter |
| I3 | API write | Foreign identifier in path or body rejected |
| I4 | Search | No foreign document; hit count post-filter; relevance unaffected by foreign content |
| I5 | Aggregation | No foreign contribution; no derivation by subtraction |
| I6 | Export | No foreign row in any format |
| I7 | Notification | No foreign content in rendered output |
| I8 | Background job | No foreign row processed; job without tenant binding does not execute |
| I9 | Event handler | Tenant established from the event, not ambient |
| I10 | Cache | Foreign key construction impossible; a request cannot receive a foreign cached value |
| I11 | AI context | No foreign record in retrieved context; prompt cache tenant-keyed |
| I12 | File and evidence retrieval | Signed reference bound to tenant and object |
| I13 | Secret resolution | A reference from one tenant does not resolve in another |
| I14 | Error responses | No foreign identifier or content in any error |
| I15 | Idempotency and rate limit namespaces | Tenant-namespaced; no cross-tenant collision |
| I16 | Fingerprint deduplication | No cross-tenant deduplication; no existence inference |
| I17 | Migration | Post-migration cross-tenant assertion passes |
| I18 | Restore | Cross-tenant restore yields unreadable ciphertext |
| I19 | Connection pooling | Session reset on return; no stale context reuse |
| I20 | Shared intelligence | Contains no tenant-derived column; schema-asserted |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-TEN-001` | The isolation suite MUST cover every path in §5, and a new subsystem MUST NOT be introduced without adding its path and assertion. | The DOC-24 §6.2 inventory is complete at authoring and decays as the platform grows. This requirement is the mechanism against decay, and it is the weakest link in the isolation model because it is procedural (`SEC-TEN-010`). | M | AT |
| `TST-TEN-002` | Cross-tenant assertion MUST run continuously in production, not only in the suite, and MUST alert on violation. | Layer 4 of DOC-24 §5.1. Residual defects are caught by production assertion, not by inspection — and I1 through I20 cover the paths the team modelled, not the ones they did not. | M | AT |
| `TST-TEN-003` | Every enumerated enforcement bypass MUST have a test asserting it emits an audit event and is unreachable from ordinary application paths. | A bypass without an unreachability test becomes reachable through refactoring (`SEC-TEN-048`). | M | AT |

---

## 6. Authorization Tests

`SEC-AUZ-049`. One test per enforcement point of DOC-07 §17, each asserting denial for an out-of-scope principal.

| # | Assertion | Verifies |
|---|---|---|
| A1 | Object identifier re-validated independently of provenance, including an identifier from a prior response | `SEC-AUZ-017` |
| A2 | A filtered picker is not treated as a control: a write with an unlisted identifier is rejected | `SEC-AUZ-018` |
| A3 | Denial does not differentiate non-existence from non-authorization, in status, code, message, or **timing** | `SEC-AUZ-020`, `PRD-API-021` |
| A4 | Collection predicate applied in retrieval; counts post-filter | `SEC-AUZ-016` |
| A5 | Multiple assignments resolve as a union of permission-scope pairs, **not a cross product** | `SEC-AUZ-010` |
| A6 | Deny on evaluation error, unavailable scope resolution, and unhandled condition — by fault injection | `SEC-AUZ-014` |
| A7 | Field-level restriction enforced on read, collection, search, export, notification, and AI context | `SEC-AUZ-021` |
| A8 | Restricted field absent rather than masked | `SEC-AUZ-022` |
| A9 | Graph traversal filters per node; branch termination not indicated; bound independent of scope | `SEC-AUZ-024`, `-025` |
| A10 | Aggregate minimum population enforced; no derivation by subtraction | `SEC-AUZ-026` |
| A11 | Score breakdown restricted to in-scope contributions | `SEC-AUZ-027` |
| A12 | Historical evaluation uses the recorded descriptor; read-only; no post-move objects | `SEC-AUZ-028`, `-029` |
| A13 | Object grant not widened by hierarchy, role, or assignment change | `SEC-AUZ-031` |
| A14 | Grant-only principal cannot enumerate through search, traversal, aggregate, notification, export, or error differentiation | `SEC-AUZ-033` |
| A15 | Service principal scope pinned; payload-asserted scope rejected | `SEC-AUZ-035` |
| A16 | Automation cannot exceed its owner's authority; suspended on authority loss | `SEC-AUZ-037`, `-038` |
| A17 | Separation of duties enforced at grant and at action | `SEC-AUZ-039` |
| A18 | Delegation cannot exceed the delegator; not re-delegable | `SEC-AUZ-043` |
| A19 | Bulk evaluates per item; a denied item is not acted on | `INV-WRK-12` |
| A20 | Shared saved view evaluates as the viewer | `INV-WRK-11` |
| A21 | Effective-permission inspection uses the live evaluation path | `SEC-AUZ-047` |
| A22 | Cookie and token authentication are not both accepted on any endpoint | `SEC-SEC-054` |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-AUZ-001` | Every enforcement point in DOC-07 §17 MUST have a denial test, and a new data egress path MUST NOT be introduced without adding a map entry and a test. | The map is complete at authoring and decays. A new egress path without a test is unenforced, and no test detects its absence because the test is the one not written (`RISK-PLT-002`). | M | AT |
| `TST-AUZ-002` | A3 MUST include a timing assertion with statistical significance, not a single-request comparison. | A lookup-then-deny path differs measurably from an immediate deny, and the difference is a reliable existence oracle. A single comparison cannot distinguish it from noise. | M | AT |

---

## 7. Identity and Ingestion Tests

### 7.1 Fingerprint stability

`INV-VUL-02`. A rescan corpus asserting identity across changes that should not affect it, and distinctness across changes that should.

| Change | Expected |
|---|---|
| Code reformatted; line numbers shift | Same finding |
| File moved or renamed within the asset | Same finding |
| Scanner version upgraded, same rule | Same finding |
| Branch differs, same code | Same finding |
| Manifest path differs (dependency finding) | Same finding |
| Concrete parameter value differs (runtime finding) | Same finding |
| Different rule, same location | Distinct findings |
| Different component, same manifest | Distinct findings |
| Different asset, same rule | Distinct findings |
| Reappearance after closure | Same finding, reopened, recurrence incremented |
| Reappearance after five years | Same finding, reopened |

### 7.2 Parser corpora

`PRD-ING-026`. Per parser, per declared source version: valid, empty, malformed, at each declared limit, absent optional fields, and injection content in every field that reaches the interface or model context.

### 7.3 Version comparison

`PRD-SBM-041`. Per supported ecosystem: pre-release ordering, epoch and revision components, boundary inclusivity, disjoint affected ranges, non-semantic schemes, and backported patch metadata precedence.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-VUL-001` | The rescan corpus MUST assert both stability and distinctness, and MUST execute on any change to fingerprint inputs or the algorithm version. | Stability alone permits an algorithm that collapses distinct issues; distinctness alone permits one that splits on every rescan. Both failures destroy data trust, in opposite directions. | M | AT |
| `TST-ING-001` | Each parser MUST have a fixture corpus per declared source version, regenerated when a source tool releases a new version. | A source tool changing its output produces silent field mis-mapping, not a parse error. The per-version fixture is the only detection mechanism. | M | AT |
| `TST-SBM-001` | The version comparison corpus MUST cover the six difficulty classes of DOC-22 §6.3 per ecosystem. | These are the cases that fail in production and pass a naive suite, because a naive suite tests versions that differ obviously. Backported patch metadata in particular is the largest false-positive source. | M | AT |
| `TST-SBM-002` | A test MUST assert that no failure mode in DOC-22 §11 results in closure, coverage improvement, or a favourable posture change. | `PRD-SBM-065` is a module-wide invariant, and the property a reviewer should test the module against. | M | AT |

---

## 8. Security Control Tests

| Suite | Content | Verifies |
|---|---|---|
| **ASVS conformance** | Per-control test or documented manual verification, per success criterion | `SEC-SEC-001` |
| **Injection** | Corpus per class of DOC-06 §9, applied at every trust boundary | `SEC-SEC-041` |
| **Redaction** | Corpus asserting credentials, secrets, and personal data are absent from logs, telemetry, traces, errors, exports, notifications, webhooks, and AI context | `SEC-SEC-025`, `-065` |
| **Upload** | Signature mismatch rejected; malware verdict flags evidence and rejects otherwise; archive limits; path containment; non-inline serving from a separate origin | `SEC-SEC-042` – `045` |
| **Parser hardening** | Malformed corpus per format at each limit; external reference resolution attempted and refused; deserialization attack corpus | `SEC-SBM-004` |
| **Authentication** | Enumeration resistance; throttling without lockout; step-up enforcement and age; session revocation within bound; regeneration on privilege change | `SEC-SEC-002` – `013` |
| **Credential handling** | A credential field rejects a value and does not log it; a submitting requester cannot reveal; export excludes at every permission level | `SEC-PTR-004`, `PRD-API-033` |
| **Audit integrity** | Chain verification; erasure leaves chain valid; erased payload reported distinctly from tampering; gap detection; concurrent insert produces no fork; no update or delete path | `SEC-AUD-011` – `022` |
| **Egress** | Destination allowlist; internal ranges refused; redirect not followed; rebinding refused at connection time | `PRD-CON-032` – `034`, `OPS-DEP-015` |
| **Connector failure** | Authentication failure not retried; rate limit honoured; circuit opens and notifies; bundle signature rejection retains prior version | `PRD-CON-024` – `027`, `-046` |
| **Header and transport** | Every response header of DOC-06 §12 asserted present and correctly valued | `SEC-SEC-047` – `055` |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-SEC-001` | ASVS conformance MUST be documented per success criterion, not per level. | A per-level claim cannot be verified by a reviewer, which makes it worthless in the procurement security review it would be made for (`SEC-SEC-001`). | M | DI |
| `TST-SEC-002` | The redaction corpus MUST include credentials in formats the detector was not designed for, and a miss MUST be recorded as a known gap rather than treated as a pass. | Redaction depends on detection, and a credential in an unrecognized format reaches the destination. Recording the gap is the honest treatment; a passing suite over a narrow corpus is not (DOC-10 §13.2). | M | AT |
| `TST-SEC-003` | A test MUST assert that `vulnerability_intelligence` carries no tenant-derived column, no reference to a tenant-scoped table, and no prevalence or usage measure. | `RISK-PLT-004`. The shared dataset's safety rests on a property, and a well-meaning future addition breaks it without breaking any other test. The schema test *is* the control. | M | AT |

---

## 9. Workflow Tests

| # | Assertion | Verifies |
|---|---|---|
| W1 | Every transition guard denies when unsatisfied and permits when satisfied | DOC-09 per machine |
| W2 | Evaluation order: scope before permission; a scope failure returns not-found | `PRD-WRK-031` |
| W3 | Transitions are atomic; no partial transition observable | `PRD-WRK-032` |
| W4 | No fixed machine is tenant-configurable | `PRD-WRK-035` |
| W5 | Workflow activation rejected where a state is unreachable or no terminal state exists | `PRD-WRK-034` |
| W6 | In-flight items continue under their pinned workflow version after a definition change | `INV-WRK-01` |
| W7 | Every edge case in DOC-09 §4 through §16 behaves as specified | DOC-09 |
| W8 | Every concurrency row of DOC-09 §18 resolves as specified | `PRD-WRK-043` – `045` |
| W9 | Reorganization saga compensates on failure at each step, **including compensation failure reaching manual intervention** | `PRD-WRK-039` – `041` |
| W10 | Secret finding has no exception path and its clock does not pause on dispute | `INV-VUL-28`, DOC-09 §7 |
| W11 | Exception approval rejects requester-as-approver at the engine level | `INV-VUL-26` |
| W12 | Match run cannot be marked coverage-confirmed on failure or stale intelligence | `INV-SBM-09` |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-WRK-001` | Every documented edge case MUST have a test. An edge case documented without a test MUST be treated as a gap. | The edge cases in DOC-09 are the behaviours a reader would not assume, which makes them the behaviours an implementer would get wrong. | M | AT |
| `TST-WRK-002` | W9 MUST include compensation failure, asserting the tenant is blocked from further reorganization and alerted. | A partially reorganized tree has a corrupted authorization substrate; permitting a second reorganization on top compounds it beyond diagnosis (`PRD-WRK-041`). | M | AT |

---

## 10. AI Evaluation

`PRD-AIC-049` – `051`. The harness gates release and runs on any change to a model version, prompt, grounding contract, or provider.

| Measure | Gate |
|---|---|
| Citation validity | 100% |
| Numeric fidelity | 100% |
| Coverage disclosure in low-coverage scenarios | 100% |
| Scope containment | 100% |
| Consistency with the deterministic computation | 100% |
| Grounding accuracy | ≥ 98% |
| Injection resistance | ≥ 95%, every failure reviewed individually |
| Refusal correctness on insufficient data | ≥ 95% |

Additional assertions: promotion executes through the ordinary operation and re-validates authorization; excluded data categories are unreachable; generated content is labelled in every representation including export; a stale-subject suggestion expires rather than remaining promotable; no automatic invocation on view.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-AIC-001` | The five 100% measures MUST block release absolutely. A rate-threshold failure MUST block release without a recorded exception naming each failure. | Each of the five is a specific defect rather than a quality distribution: an invalid citation is wrong, not slightly wrong. | M | AT |
| `TST-AIC-002` | The injection corpus MUST include injected content in every field reaching model context and MUST be extended whenever a grounding contract gains a field. | The corpus is complete at authoring and decays as contracts grow (`PRD-AIC-051`). | M | AT |

---

## 11. Presentation and Honesty Tests

The suite verifying that the platform does not produce confident wrong output. It exists because every mechanism in it is individually easy to remove for a cleaner interface.

| # | Assertion | Verifies |
|---|---|---|
| H1 | Every measure derived from variable-coverage data presents coverage and freshness | `PRD-DSH-024` |
| H2 | At insufficient confidence the coverage gap is primary and the measure is not rendered as a figure | `PRD-DSH-025` |
| H3 | An improvement carries its cause; remediation is distinguished from lost coverage | `PRD-DSH-026` |
| H4 | Unmeasured is visually distinct from zero and from empty | `PRD-UIX-022` |
| H5 | Comparison states its normalization and each entity's coverage | `PRD-DSH-035`, `-037` |
| H6 | Aggregation basis labelled as-was or as-is | `PRD-DSH-018` |
| H7 | Utilization presented against a target band with its reason | `PRD-DSH-033` |
| H8 | Individual metrics carry the purpose statement | `PRD-DSH-034` |
| H9 | Generated content labelled, surviving export | `PRD-AIC-036` |
| H10 | Migrated records marked, surviving every presentation | `PRD-ING-049` |
| H11 | Inbound-attributed comments marked | `PRD-UIX-020` |
| H12 | Estimation confidence shown where calibration data is thin | `PRD-RSK-040` |
| H13 | Intelligence staleness shown on affected findings | `PRD-VUL-008` |
| H14 | **No honesty surface is suppressible by theme, density, template, or user preference** | `PRD-UIX-026`, `PRD-DSH-042` |
| H15 | Closure figures distinguish verified from other reasons | `PRD-DSH-030` |
| H16 | Breach counts presented by attribution, never as a single figure | `PRD-DSH-029` |
| H17 | Assessment reports include coverage; findings are not presented without it | `PRD-DSH-039` |
| H18 | Scheduled reports generated per recipient; no shared artifact across differing authorization | `PRD-DSH-043` |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-DSH-001` | H14 MUST be asserted across every theme, density setting, template configuration, and user preference combination that could suppress a surface. | The suppression paths differ — a template removes a section, a density setting hides a secondary line, a preference turns off extra detail — and each would defeat the same mechanism. Testing one path leaves the others open. | M | AT |
| `TST-DSH-002` | H1 through H18 MUST be verified against rendered output, not against the data layer. | A measure carrying its coverage in the API response and losing it in the interface has lost it. The assertion belongs where the reader sees it. | M | AT |

---

## 12. Accessibility and Localization

| # | Assertion | Verifies |
|---|---|---|
| L1 | WCAG 2.2 AA per success criterion, automated and manual | `INT-UIX-001` |
| L2 | No information conveyed by colour alone, in any surface, chart, or diagram | `INT-UIX-002` |
| L3 | Every workflow completable by keyboard alone; visible focus; no trap | `INT-UIX-003` |
| L4 | High-contrast mode; reduced motion respected; usable at 200% zoom and 400% text scaling | `INT-UIX-004` |
| L5 | Dynamic changes announced to assistive technology | `INT-UIX-005` |
| L6 | Every chart has a keyboard-accessible tabular alternative | `INT-UIX-006` |
| L7 | Form errors associated, announced, not position or colour dependent | `INT-UIX-007` |
| L8 | Pseudo-localization passes with no layout failure, truncation, or untranslated string | `INT-UIX-009` |
| L9 | Dates, times, numbers, and lists locale-formatted; instants in viewer timezone with zone shown where ambiguous | `INT-UIX-010` |
| L10 | Right-to-left layout and mirrored iconography | `INT-UIX-011` |
| L11 | Non-Latin script correct in display, sorting, search, and truncation | `INT-UIX-012` |
| L12 | Vocabulary overrides applied in navigation, tables, charts, reports, notifications, and exports | `INT-UIX-013` |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-INT-001` | Pseudo-localization MUST be a build gate. | It is the only test finding hardcoded strings and layout assumptions before a real locale is added, and it needs no translation to run (`INT-UIX-009`). | M | AT |
| `TST-INT-002` | L12 MUST assert vocabulary overrides in exported artifacts, not only in the interface. | An override applied in the interface and lost in the report is the most likely partial implementation, and the report is what leaves the platform. | M | AT |

---

## 13. Performance Tests

Bound to the reference profiles of DOC-01 §12.1, not to absolute figures.

| # | Target | Source |
|---|---|---|
| P1 | Dashboard viewport 1.5 s p95 at Medium, 50 concurrent sessions | `NFR-DSH-001` |
| P2 | Filter and drill-down 800 ms p95 | `NFR-DSH-002` |
| P3 | Finding list 600 ms p95 at Medium, 1.2 s at Extra large | `NFR-VUL-001` |
| P4 | Work item detail 700 ms; comment acknowledgement 400 ms p95 | `NFR-WRK-001` |
| P5 | Search first results 1.0 s p95 at Medium, 2.0 s at Large | `NFR-WRK-002` |
| P6 | Import 2,000 findings per second per worker; 500 MB accepted | `NFR-ING-001` |
| P7 | Single match run 30 s p95 at 5,000 components | `NFR-SBM-001` |
| P8 | Portfolio sweep 4 h at Medium, 12 h at Extra large, concurrency 1 | `NFR-SBM-002` |
| P9 | **Intelligence-to-visibility 6 h p95** | `NFR-SBM-003` |
| P10 | Audit write adds ≤ 15 ms p95; audit search 5 s p95 over 12 months | `NFR-AUD-001` |
| P11 | Noisy neighbour: one tenant at limit degrades another ≤ 10% p95 | `NFR-PLT-002` |
| P12 | Interactive readiness 2.5 s p95; usable at 200 ms round trip | `NFR-UIX-001` |
| P13 | Migration duration at Medium within the stated maintenance window | `OPS-DEP-032` |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-PLT-006` | Performance tests MUST execute against synthetic data structurally representative at the Medium profile, including hierarchy depth, finding volume, and component cardinality. | Testing against a small dataset validates correctness and nothing about the properties that fail at scale — index selectivity, partition pruning, and join strategy all change with volume (`OPS-DEP-024`). | M | AT |
| `TST-PLT-007` | P8 and P9 MUST be validated early, before the composition analysis capability is considered complete. | Whether the sweep budget is achievable depends on two indexes (DOC-22 §13.3). Discovering it is not achievable after the capability is built means redesigning the matching path. | M | AT |
| `TST-PLT-008` | Keyset pagination MUST be tested under concurrent modification, asserting no row is skipped or duplicated across pages. | Offset pagination's failure is silent and appears only under concurrency, which is absent from most suites — and full extraction is the most common API use (`PRD-API-022`). | M | AT |

---

## 14. Operational Tests

| # | Assertion | Verifies |
|---|---|---|
| O1 | Restore rehearsal into the isolated environment succeeds within the recovery time objective | `OPS-DEP-034` |
| O2 | Cross-tenant restore yields unreadable ciphertext | `OPS-DEP-035` |
| O3 | Upgrade and rollback rehearsed at Medium; rollback without schema rollback | `OPS-DEP-029`, `-030` |
| O4 | Post-migration cross-tenant assertion executes and blocks completion on failure | `OPS-DEP-031` |
| O5 | Bypass credentials unreachable from application code | `OPS-DEP-009` |
| O6 | Connection pooling resets session state | `OPS-DEP-010` |
| O7 | Partition automation creates ahead of need; missing partition alerts before required | `OPS-DEP-011` |
| O8 | Retention consults the legal hold register before partition drop | `OPS-DEP-013` |
| O9 | Every degraded state of DOC-02 §14 renders with its capability, reason, and remaining function | `CON-PLT-043` |
| O10 | Air-gapped provisioning and upgrade rehearsed | `OPS-DEP-040` |
| O11 | Every runbook rehearsed | `OPS-DEP-049` |
| O12 | Silent-failure alerting fires for each condition of `OPS-DEP-042` | `OPS-DEP-042` |
| O13 | Projection rebuild produces output identical to the maintained projection | `CON-DAT-031` |
| O14 | Closure projection rebuild from `org_node` matches the maintained closure | `CON-DAT-026` |
| O15 | Attribute schema reconciliation detects a document violating its registry schema | `CON-DAT-019` |
| O16 | `current_tenant_id()` raises rather than returning null when unset | `CON-DAT-013` |
| O17 | Forced row-level security closes the table-owner path | `CON-DAT-012` |
| O18 | Event consumers are idempotent under duplicate and out-of-order delivery | `CON-PLT-023` |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-DEP-001` | O1, O3, O10, and O11 MUST be rehearsals with recorded outcome and elapsed time, not automated assertions. | Each verifies a procedure executed by people under pressure. An automated pass tells you the mechanism works, not that the procedure does. | M | DM |
| `TST-DEP-002` | O13 and O14 MUST assert equality against a rebuild, not merely that the projection is non-empty. | A corrupted closure silently breaks authorization: a principal either loses access, which is reported, or gains access, which is not. Rebuild-and-compare is the only practical detection (`INV-ORG-14`). | M | AT |

---

## 15. Penetration Testing

`SEC-PLT-011`, `SEC-SEC-061`. Every scenario of DOC-26 §7 must be a stated objective, and a scenario without one recorded as untested.

| Objective | Scenario |
|---|---|
| Reach another business unit's data by identifier substitution | T1 |
| Reach another tenant's data by any path | T2 |
| Influence generated output by injecting content into a scanned application | T3 |
| Extract in bulk under legitimate permission without detection | T4 |
| Obtain tenant data as an operator without break-glass, or suppress notification | T5 |
| Improve a posture score without remediating | T6 |
| Obtain evidence content without the permission | T7 |
| Escalate through a service credential | T8 |
| Escalate through workflow, automation, or role configuration | T9 |
| Reach the platform through its own supply chain | T10 |
| Enumerate principals, assets, or findings through any surface | Abuse cases, DOC-26 §8 |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-PLT-009` | Penetration testing MUST cover every DOC-26 §7 scenario and every DOC-26 §8 abuse case, and an uncovered item MUST be recorded as untested rather than omitted. | Internal testing verifies the paths the team modelled; external testing finds the paths they did not. Recording untested items is more useful than implying full coverage. | M | PT, DI |
| `TST-PLT-010` | Penetration testing MUST be completed before commercial release and MUST include tenant isolation and evidence handling as stated objectives. | These two are the platform's highest-severity and most attractive targets, and both are areas where a defect is unrecoverable (`SEC-TEN-050`, `PRD-ASM-012`). | M | PT |

---

## 16. Traceability

### 16.1 The matrix

DOC-16 owns the authoritative matrix (DOC-00 §8.3). Columns are fixed: requirement identifier, owning document, design reference, schema reference, API reference, test case identifier, verification method, status. A non-applicable cell contains `N/A` with a one-line reason, never blank.

### 16.2 Release gates

| Gate | Condition |
|---|---|
| **Coverage** | 100% of `MUST_HAVE` requirements have a passing test |
| **Backward traceability** | Zero untraced schema objects and API operations |
| **Structural** | S1–S13 pass |
| **Isolation** | I1–I20 pass; continuous assertion active |
| **Authorization** | A1–A22 pass |
| **Honesty** | H1–H18 pass |
| **AI** | Five absolute measures at 100% where AI ships |
| **Accessibility** | L1–L12 pass |
| **Performance** | P1–P13 within target at the applicable profile |
| **Operational** | O1–O18 pass; rehearsals recorded |
| **Penetration** | Completed with findings resolved or accepted before commercial release |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `TST-PLT-011` | The matrix MUST be machine-readable and validated in the pipeline, and a `MUST_HAVE` requirement without a test case MUST fail the gate. | A matrix maintained only in prose cannot be validated, and one that is not validated is wrong within weeks at this corpus size — 1,101 requirements across 26 documents (DOC-00 §20.2). | M | AT |
| `TST-PLT-012` | Backward traceability MUST be enforced: a schema object or API operation tracing to no requirement MUST fail the gate. | Forward traceability catches under-delivery; backward catches undocumented functionality, which carries attack surface and test burden nobody agreed to accept — and in a security product is a specific liability (DOC-00 §8.2). | M | AT |

---

## 17. Requirements

Thirty requirements across the suites, all `MUST_HAVE`.

| Suite | IDs | Count |
|---|---|---|
| Strategy and structural | `TST-PLT-001` – `003` | 3 |
| Invariants | `TST-PLT-004` – `005`, `TST-PTR-003` | 3 |
| Isolation | `TST-TEN-001` – `003` | 3 |
| Authorization | `TST-AUZ-001` – `002` | 2 |
| Identity and ingestion | `TST-VUL-001`, `TST-ING-001`, `TST-SBM-001` – `002` | 4 |
| Security controls | `TST-SEC-001` – `003` | 3 |
| Workflow | `TST-WRK-001` – `002` | 2 |
| AI | `TST-AIC-001` – `002` | 2 |
| Presentation | `TST-DSH-001` – `002` | 2 |
| Localization | `TST-INT-001` – `002` | 2 |
| Performance | `TST-PLT-006` – `008` | 3 |
| Operational | `TST-DEP-001` – `002` | 2 |
| Penetration and traceability | `TST-PLT-009` – `012` | 4 |

Satisfies the verification obligations recorded by all twenty-two upstream documents.

---

## 18. Closing

### 18.1 Extensibility

Each suite is extended by adding a row, and four suites carry a requirement that adding a subsystem, egress path, grounding contract field, or parser version *requires* the corresponding row (`TST-TEN-001`, `TST-AUZ-001`, `TST-AIC-002`, `TST-ING-001`). Those four are the mechanism against decay, and they are procedural — which DOC-26 §13.2 identifies as the weaker kind of control and which makes them the corpus's most important process requirements.

**Deliberate rigidity.** Negative assertions required (`TST-PLT-001`); no aggregate coverage percentage (`TST-PLT-002`); structural assertions as build gates (`TST-PLT-003`); the five AI measures absolute (`TST-AIC-001`); backward traceability enforced (`TST-PLT-012`).

**Known extension costs.** Parser corpora require regeneration per source-tool version indefinitely. The injection and redaction corpora grow with every field reaching model context or a log. Performance tests require synthetic data at profile volume, which is itself a maintained artifact.

### 18.2 What this strategy cannot verify

Stated plainly, because a strategy that implies complete verification is misleading.

| Not verifiable | Consequence |
|---|---|
| **Redaction completeness** | Depends on detection. A credential in an unrecognized format reaches its destination and no test detects what the corpus does not contain (`TST-SEC-002` records the gap rather than closing it) |
| **Injection resistance beyond the corpus** | Not a solved problem. The 95% threshold measures known patterns; containment is the load-bearing control (`RISK-PLT-001`) |
| **Coverage indication being read** | `PRD-UIX-027` requires validation with the executive audience. An unread indicator is functionally identical to an absent one, and that is a design judgement rather than a testable property |
| **Inference through aggregates not yet built** | Each new presentation creates a channel (`RISK-PLT-006`). The per-addition review is procedural |
| **A consistently wrong classification baseline** | Controls detect rates of change, not a wrong starting point (`RISK-PLT-003`) |
| **Runbook adequacy under real incident conditions** | Rehearsal approximates. The rarest runbooks are the least rehearsed and the most severe |

### 18.3 Notes for downstream documents

| Document | Note |
|---|---|
| DOC-15 | Pipeline gates of §9 map to the gates of DOC-15 §9.1; O1, O3, O10, and O11 require the environments of DOC-15 §8 |
| DOC-17 | `TST-PLT-011` and `-012` are the release gates of `PRD-PLT-011` and `-012`; `TST-PLT-007` should be scheduled early in the composition analysis block |
| DOC-19 | Accepted gaps recorded under `TST-SEC-002` and §18.2 are decisions, not omissions |
| DOC-20 | ⚠ OQ-015 determines the profile against which performance tests execute |

### 18.4 Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.0.0 | 2026-08-04 | Principal Application Security Engineer; Chief Software Architect; Principal Security Architect | Initial content-complete version. Rejects the conventional pyramid on the grounds that the platform's consequential failures are structural and cross-cutting rather than unit-level, and places emphasis on five suites a pyramid would under-weight. States the asymmetry that a missing tenant or authorization predicate returns more data and produces no error, so negative assertions are required. Gathers the verification obligations recorded by twenty-two upstream documents into thirteen structural assertions, twelve unrecoverable-invariant suites, twenty isolation paths, twenty-two authorization enforcement points, eleven identity stability cases, eleven security control suites, twelve workflow assertions, eight AI measures with five absolute, eighteen honesty assertions, twelve accessibility and localization assertions, thirteen performance targets bound to reference profiles, eighteen operational assertions, and eleven penetration objectives. Specifies the traceability matrix with forward and backward gates. Records six properties the strategy cannot verify. Thirty requirements. | Pending |

---

*End of DOC-16.*
