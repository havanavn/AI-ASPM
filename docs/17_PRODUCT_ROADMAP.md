---
document_id:    DOC-17
title:          Product Roadmap
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        1.0.0
status:         In review
owner:          Staff Product Manager
authors:        [Staff Product Manager, Chief Software Architect]
reviewers:      []
last_updated:   2026-08-04
tier:           8
prerequisites:  [DOC-00, DOC-01]
depends_on:     [DOC-00, DOC-01, DOC-02, DOC-03, DOC-11, DOC-15, DOC-16, DOC-24, DOC-26]
supersedes:     null
adrs_relied_on: [ADR-002, ADR-003, ADR-023, ADR-024, ADR-026, ADR-027, ADR-028]
open_questions: [OQ-010, OQ-015, OQ-017, OQ-018, OQ-019, OQ-025, OQ-026, OQ-027]
requirement_domains: [PLT]
security_review_required: false
---

# 17 — Product Roadmap

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope) · 2. [Release Strategy](#2-release-strategy) · 3. [The v1 Boundary](#3-the-v1-boundary) · 4. [Build Order](#4-build-order) · 5. [Phased Delivery](#5-phased-delivery) · 6. [Descoping Options](#6-descoping-options) · 7. [Onboarding and Migration](#7-onboarding-and-migration) · 8. [Decisions Still Required](#8-decisions-still-required) · 9. [Risks to the Plan](#9-risks-to-the-plan) · 10. [Governance Requirements](#10-governance-requirements) · 11. [Closing](#11-closing)

---

## 1. Purpose and Scope

**In scope.** Release strategy; the v1 capability boundary with justification; build order derived from dependency; phased delivery; descoping options presented as whole capabilities with impact analysis, per the instruction at DOC-01 §15.3; onboarding and migration; the decisions still required; risks to the plan; release governance requirements.

**Out of scope.** Effort estimates and dates. This document sequences and bounds; it does not schedule, because a schedule requires team composition and velocity data that does not exist.

**LC-01.** Requirements are `PRD-PLT-nnn` from `001`, covering release governance.

---

## 2. Release Strategy

### 2.1 Internal first

⚠ **Working assumption (OQ-010):** internal deployment precedes commercial availability, with commercial architecture built from the start and no shortcuts that compromise tenancy.

| Property | Consequence |
|---|---|
| Internal deployment is a **tenant**, not a special case | ADR-002. `SEC-TEN-044` requires one code path across deployment models, so the internal deployment exercises the multi-tenant model with one tenant |
| Commercial architecture from v1 | Tenancy, configurability, and residency are built, not retrofitted. The cost is 5–8% of v1 effort; the retrofit cost is a rewrite arriving during the second sale |
| Internal deployment is the first real validation | Of the scoring model against real data (`PRD-RSK-046`), of estimation calibration, and of the collaboration primitives on which ADR-028 depends |

**The risk this creates.** An internal-only first release invites configurability shortcuts, because there is only one organization to satisfy and every ADR-027 requirement looks like unnecessary generality. §9 R1 treats this as the plan's principal risk.

### 2.2 Release cadence

| Release | Content |
|---|---|
| Patch | Defect fixes; no schema change requiring downtime (`NFR-DEP-004`) |
| Minor | Additive capability; additive API within a version; expand-migrate-contract schema changes |
| Major | Breaking API change with a 12-month deprecation window (`PRD-API-018`) |

---

## 3. The v1 Boundary

### 3.1 The test applied

A capability is in v1 if the platform cannot perform its core function without it. The core function is stated in DOC-01 §4.3: hold the technical truth, the organizational truth, the risk truth, the work truth, and the honesty layer. **Holding four of the five produces a tool rather than a posture management system**, so all five are v1.

### 3.2 In v1

| Capability | Why it cannot be deferred |
|---|---|
| `CAP-1` Scope and ownership | Everything else is scoped by it. Ownership resolution in particular: without it findings route nowhere |
| `CAP-2.1`, `-2.2`, `-2.5`, `-2.6` Ingestion, connectors, coverage, enrichment | Ingestion is how data arrives; coverage is the honesty layer; enrichment is what reduces four thousand findings to twelve |
| `CAP-2.3` SBOM matching | The disclosure-response mechanism, and the platform's clearest differentiated capability |
| `CAP-2.4` Manual assessment | The security function's judgement is recorded here; no competing tool models it well |
| `CAP-3` Risk and prioritization | The central claim. A platform that aggregates without prioritizing is the spreadsheet it replaces |
| `CAP-4` Assessment and validation | Intake, engagement, retest, exceptions. Intake is where the largest population meets the platform |
| `CAP-5.1`–`5.5`, `5.7` Work, collaboration, capacity, SLA, notification | ADR-028. Under-building collaboration is the most likely way this product fails (DOC-01 §5.3) |
| `CAP-6.1`, `6.3` Dashboards and reporting | The reason the platform is bought by an executive |
| `CAP-7` Platform and governance | Configurability, authorization, audit, tenancy. Not deferrable by construction |

### 3.3 Deferred with justification

| Deferred | Why it is safe to defer | Reserved |
|---|---|---|
| `CAP-6.2` AI capabilities | Every capability has a non-AI fallback (`PRD-AIC-047`). The platform is fully functional without them, and deferring reduces v1 risk in the area with the least verifiable quality | Provider abstraction, ledger, and contracts specified (DOC-10) |
| `CAP-6.4` Knowledge base | Remediation guidance is valuable and not load-bearing. Findings are actionable without it | Model and linkage specified |
| `CAP-5.6` Migration import | Needed at adoption, not at first use. An internal-first release can run in parallel with the incumbent briefly | `PRD-ING-048` – `052` |
| `CAP-5.8` Automation rules | A force multiplier for a mature deployment; a v1 deployment has no established patterns to automate | Model specified |
| Container registry scanning | ADR-026. Reserved and rejected at the application layer | `ScanTarget` type |
| Reachability analysis | Requires call graph data unavailable under ADR-024 | Zero-weight factor, finding attributes |
| Cross-tenant benchmarking | Requires a tenant base and a consent framework | Disabled by default |
| Tenant-authored dashboard compositions | Requires a query-safety layer over the read model | Read model structure |
| Blocking pipeline gate | Requires latency and availability commitments appropriate to a build-blocking service | Advisory API present |
| Locales beyond source and first target | Architecture present from v1 (`INT-UIX-008` – `013`) | — |

**On deferring AI in an "AI-native" product.** The name describes the architecture — a domain model built to support grounded generation, a deterministic score AI explains rather than computes, a suggestion ledger — not a dependency on shipping AI in the first release. Deferring the capabilities while building the architecture is consistent with DOC-01 §5.4, and it removes the least verifiable quality risk from v1. It does, however, weaken the initial commercial narrative, which §9 R4 records.

---

## 4. Build Order

Derived from the dependency graph, not from perceived value. Building in value order produces rework where a dependency is discovered late.

| # | Block | Depends on | Notes |
|---|---|---|---|
| 1 | Platform kernel: tenant context, authorization, audit, schema registry, rules engine | — | Everything depends on it. `INV-TEN-01`, `INV-AUD-01`, and `SEC-AUZ-013` are established here or nowhere |
| 2 | Organization and scope | Kernel | The scope descriptor mechanism (`PRD-ORG-011`) cannot be retrofitted |
| 3 | Asset inventory and graph | Organization | Ownership resolution and identity rules |
| 4 | Ingestion pipeline and finding identity | Assets | `INV-ING-01` and `INV-VUL-01`. **The single highest-risk block**: identity done wrong is the most common cause of abandoned deployments |
| 5 | Vulnerability management | Ingestion | Lifecycle, triage, suppression |
| 6 | Risk and service levels | Findings, assets, organization | Scoring, clocks, escalation |
| 7 | Work management and collaboration | Kernel, findings, assessments | ADR-028. Large, and the differentiator |
| 8 | Assessment and intake | Assets, work | Intake is the highest-volume external write surface |
| 9 | Composition analysis | Assets, ingestion | SBOM submission and matching |
| 10 | Exceptions | Findings, work | |
| 11 | Read models and dashboards | All of the above | Projections require their sources |
| 12 | Notification | Domain events | Subscriber; can trail the events it subscribes to |
| 13 | Capacity | Work transition log | Requires accumulated history to be useful; the log must exist from block 7 |
| 14 | Connectors | Ingestion, secrets | |
| 15 | AI, knowledge, automation, migration | Read models, work | Deferred per §3.3 |

**Three blocks cannot be reordered without permanent cost:**

| Block | Why |
|---|---|
| 1 — kernel | Retrofitting tenant enforcement or a single authorization point means revisiting every query and every call site |
| 2 — scope descriptors | Historical scope cannot be reconstructed. Objects created before the mechanism exists have no descriptor, ever |
| 4 — finding identity | A first identity algorithm without retained inputs (`INV-VUL-04`) is permanent, because re-fingerprinting requires the inputs |

Additionally, **the work item transition log (`PRD-WRK-011`) must exist from block 7** even though capacity (block 13) is what consumes it. Its data cannot be reconstructed later; a platform adding workload analytics in v2 finds its history begins on the day the log was introduced.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-PLT-001` | Blocks 1, 2, and 4, and the transition log of block 7, MUST be delivered before any capability that writes data those mechanisms would have recorded. | Each captures data that cannot be reconstructed. Delivering a capability first means the data for the period before the mechanism exists is permanently absent, and no later work recovers it. | M | AR, DI |

---

## 5. Phased Delivery

Phases within v1. Each is internally usable, which matters because the internal deployment is the validation path (§2.1).

| Phase | Capability | Usable for |
|---|---|---|
| **P1 — Foundation** | Kernel, organization, assets, authorization, audit | Inventory and ownership. Immediately useful: *who owns this repository* is answerable, which it is not today |
| **P2 — Findings** | Ingestion, finding lifecycle, triage, suppression, coverage | Consolidated vulnerability management. Replaces the export-and-spreadsheet workflow |
| **P3 — Risk** | Scoring, service levels, escalation, exceptions | Prioritization and remediation commitments |
| **P4 — Work** | Work management, collaboration, notification | Replaces the incumbent tracker for security work. **The adoption test** |
| **P5 — Assessment** | Intake, engagement, retest, evidence | Replaces unstructured request intake |
| **P6 — Composition** | SBOM submission, matching, coverage governance | Disclosure response |
| **P7 — Insight** | Read models, four compositions, reporting, capacity | Replaces manual monthly reporting |
| **P8 — Integration** | Connectors, identity provisioning, outbound propagation | Automated ingestion at scale |

**P4 is the phase to watch.** It is where ADR-028 is tested against reality: if the collaboration primitives are inadequate, users record states here and hold conversations elsewhere, and every subsequent phase produces confident output over an incomplete work record.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-PLT-002` | Each phase MUST be independently usable, and a phase MUST NOT depend on a later phase to deliver value. | The internal deployment is the validation path. A phase that is not usable produces no feedback, which means its defects are discovered in a later phase where they are more expensive. | M | DI |
| `PRD-PLT-003` | Phase P4 MUST include a measured adoption check: whether work-item conversation occurs in the platform or elsewhere. | ADR-028's risk is measurable and the measurement is cheap. Discovering the answer at P7, when reporting depends on the work record, is too late to change course. | M | DM |

---

## 6. Descoping Options

DOC-01 §15.3 recorded that 87% of requirements are `MUST_HAVE` and instructed that **descoping occur at capability level, not requirement level**. Cutting a capability removes a coherent set and leaves the remainder functional; relaxing individual requirements within a retained capability produces a capability that half works, which is worse than its absence because it will be relied upon.

Options, in the order they should be taken.

| # | Cut | Retains | Loses | Impact |
|---|---|---|---|---|
| D1 | Capacity and workload (`CAP-5.4`) | Work management, SLA, all queues | Utilization, allocation, effort distribution, feasible start date | **Low.** The transition log must still be built (`PRD-PLT-001`), so the capability can be added later over complete history. Loses the resourcing argument of JTBD-19 and the intake expectation-setting of `PRD-CAP-010` |
| D2 | Assessment types beyond penetration test (`CAP-2.4` partial) | Penetration test intake and engagement | Architecture review, threat model, vendor assessment as structured activities | **Moderate.** They revert to documents outside the platform, so their coverage is unrecorded. The registry means adding them later is configuration |
| D3 | Exceptions (`CAP-4.4`) | Findings, risk, work | Structured risk acceptance with expiry and auto-reopen | **High and asymmetric.** Non-remediation continues to happen; without the capability it happens informally, with no record, no expiry, and no accountability — which is the state the platform was bought to escape. **Recommended only if D1 and D2 are insufficient** |
| D4 | Two of four dashboard compositions (`CAP-6.1` partial) | Executive and operational | Engineering ownership and workload compositions | **Moderate to high.** The engineering composition is what makes findings get fixed (DOC-01 §7.2 A5); cutting it shifts remediation communication back to email |
| D5 | Outbound propagation (`PRD-CON-011`) | All inbound integration | Reference items in engineering trackers | **Moderate.** Teams that keep their own tracker must work in two places, which reduces adoption in exactly the population D4 also affects |
| D6 | Tenant-configurable workflows (`PRD-WRK-008`) | Fixed default workflows | Process adaptation | **High for commercial, low for internal.** Acceptable *only* for an internal-first release, and only if the workflow-as-data model is built with a single default rather than replaced by hardcoded states — otherwise it is a rewrite later, not an addition |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-PLT-004` | Descoping MUST remove whole capabilities from §6, and MUST NOT relax individual requirements within a retained capability. | A capability with relaxed requirements half works and will be relied upon; a removed capability is visibly absent. This is the instruction of DOC-01 §15.3 made a requirement so it survives schedule pressure. | M | DI |
| `PRD-PLT-005` | Where a capability is descoped, the mechanisms that capture data it would consume MUST still be delivered where that data cannot be reconstructed. | D1 is the case: cutting capacity is low impact *only because* the transition log is retained. Cutting both makes the capability unrecoverable over the elapsed period (`PRD-PLT-001`). | M | AR |
| `PRD-PLT-006` | Descoping decisions MUST be recorded as decisions with their impact analysis, not as backlog deferrals. | A deferral is invisible in six months; a recorded decision states what was given up and why, and can be revisited on its own terms. | M | DI |

**What must not be descoped**, because each is either unrecoverable or the platform's reason for existing: the platform kernel; scope descriptors; finding identity with retained inputs; the transition log; coverage and freshness governance; audit integrity; tenant isolation; the configurability model as a *mechanism*, even where only one configuration ships.

---

## 7. Onboarding and Migration

A v1 that launches empty gets ignored. Onboarding is a delivery concern, not a post-launch activity.

| Step | Source | Notes |
|---|---|---|
| Organization hierarchy | Authoritative source or bulk import (`PRD-ORG-012`) | First, because everything is scoped by it |
| Node criticality and ownership | Manual, guided | The highest-value manual step; without it risk scoring has no business context |
| Asset discovery | Connector pull and scanner output | Produces unclaimed assets in volume; the ownership claim queue is the onboarding workload |
| Existing findings | File import with mapping templates (`PRD-ING-045`) | Identity recomputed, not carried (`PRD-ING-028` reasoning) |
| Checklists and templates | Import of existing artifacts | Existing checklists become checklist definitions |
| Historical assessments | Structured import where records exist | Coverage is often unavailable historically and must be recorded as unknown, not as complete |
| Work history | Migration import (`CAP-5.6`, deferred) | Deferred; the parallel-running period covers it |
| Configuration | Default templates, then tenant adjustment | `CFG-PLT-008` |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-PLT-007` | Onboarding MUST be deliverable as a documented, repeatable procedure with the mapping templates and import artifacts it requires. | A bespoke onboarding per tenant does not scale and makes the second deployment as expensive as the first — which is the commercial failure ADR-027 exists to prevent, arriving through a different route. | M | DI |
| `PRD-PLT-008` | Imported historical data whose coverage is unknown MUST be recorded as unknown coverage, not as complete. | Otherwise onboarding produces a favourable baseline the platform then reports against, and every subsequent trend is measured from a fiction (PP-1). | M | AT |

**`PRD-PLT-008` is the requirement most likely to be resisted at onboarding**, because unknown coverage on historical data makes the initial dashboard look worse than the spreadsheet it replaced. It is correct: the spreadsheet's confidence was unearned.

---

## 8. Decisions Still Required

### 8.1 Technology decisions

DOC-02 §16 defers eight. Each must be made before the block that depends on it.

| Decision | Required before | Constraint |
|---|---|---|
| Operational store | Block 1 | Row-level security with forced owner enforcement — **disqualifying if absent** |
| Application language and toolchain | Block 1 | Build-time module boundary enforcement and authorization static analysis — **disqualifying if absent** (`CON-PLT-046`) |
| Secrets store | Block 1 | ⚠ OQ-026 |
| Search engine | Block 7 | Full-text search combinable with a scope predicate in one query — the strongest constraint on this choice |
| Read model store | Block 11 | Aggregation performance at DOC-01 §12.1 volumes |
| Durable queue | Block 4 | Lease semantics, per-class isolation |
| Cache | Block 1 | Key-prefix scoping, explicit invalidation |
| Object store | Block 8 | Access-policy tenant partitioning, not path convention |

### 8.2 Open questions

| ID | Blocks | Consequence if unanswered |
|---|---|---|
| OQ-015 sizing | Block 1 partition counts | Hash partition counts must be fixed before production data (`CON-DAT-035`); changing one later is a full table rewrite |
| OQ-026 vault | Block 1 | Test credentials, connector credentials, and secret findings all depend on it |
| OQ-017, OQ-018 volumes and SLAs | Block 6 | Scoring and service level values are provisional; structure is not |
| OQ-019 team size | Block 13 | Capacity measures; manual entry is the fallback |
| OQ-025 incumbent tracker | Block 15 | Migration adapter fidelity |
| OQ-027 AI hosting | Deferred block | Would add substantial DOC-15 scope |
| OQ-010 internal-first | This document | Ratified as an assumption |

**Two are urgent.** OQ-015 because partition counts are irreversible after production data, and OQ-026 because three separate credential paths depend on it and all three are in block 1.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-PLT-009` | The operational store and toolchain decisions MUST be made against the disqualifying constraints of §8.1 before block 1 begins. | Both constraints exist because two structural controls depend on them (`CON-DAT-012`, `CON-PLT-046`). A toolchain unable to enforce module boundaries reduces `CON-PLT-013` to code review, which DOC-26 §13.2 identifies as the weaker kind of control. | M | AR, DI |
| `PRD-PLT-010` | Hash partition counts MUST be fixed before the first production data, and the decision MUST be recorded with the sizing basis used. | Changing a hash partition count redistributes every row (`CON-DAT-035`). Recording the basis means a later resize decision can assess whether the original assumption was wrong or the growth was. | M | DI |

---

## 9. Risks to the Plan

| # | Risk | Likelihood | Impact | Treatment |
|---|---|---|---|---|
| R1 | **Configurability shortcuts during an internal-first release.** Every ADR-027 requirement looks like unnecessary generality when there is one organization to satisfy | **High** | **High** — the retrofit arrives during the second sale | `SEC-AUZ-050` static analysis as a build gate; `PRD-PLT-004` prohibiting requirement-level descoping; the internal deployment configured as a tenant rather than as a special case |
| R2 | **Finding identity done wrong in block 4.** The most common cause of abandoned deployments | Medium | **Very high** — unrecoverable | Retained fingerprint inputs (`INV-VUL-04`); a rescan corpus in the test suite; block 4 sequenced early enough that the failure surfaces during internal use rather than commercially |
| R3 | **Collaboration under-built in P4.** Users record state here and converse elsewhere | Medium | **High** — every later capability produces confident output over an incomplete work record | `PRD-PLT-003` measured adoption check at P4; collaboration primitives specified as `MUST_HAVE` rather than gestured at |
| R4 | **AI deferral weakens the commercial narrative** for a product named AI-native | Medium | Medium | The architecture is built and demonstrable (DOC-10); the deferral removes the least verifiable quality risk from v1 and is defensible on that basis |
| R5 | **Coverage honesty makes the initial product look worse than the spreadsheet it replaces** | **High** | Medium | Expected and correct. `PRD-PLT-008` and the honesty surfaces of DOC-08 §10 exist for this. The counter-argument is that the spreadsheet's confidence was unearned, and it must be made explicitly at onboarding rather than discovered as a complaint |
| R6 | **Deep-hierarchy navigation requires more design iteration** than a panel-heavy idiom would (DOC-08 §2.1) | Medium | Low to medium | Schedule contingency in P1; usability testing before P7 |
| R7 | **Scope creep into general engineering work tracking.** NG-06 will be pushed once work management exists | **High** | High — acquires requirements that destroy the security domain model | NG-06 recorded as a non-goal with rationale; `PRD-PLT-004` requiring capability-level decisions |
| R8 | **Sizing assumptions wrong by an order of magnitude**, invalidating partition and index choices | Medium | Medium | Structure bound to named profiles (DOC-01 §12.1) so revaluation is a threshold change; `PRD-PLT-010` recording the basis |

**R1 and R5 are the two the plan is most likely to fail on**, and both are organizational rather than technical. R1 because the discipline that costs most is the one with no visible payoff until the second customer. R5 because a product that reports honestly at launch competes against a predecessor that reported optimistically, and the comparison is unfavourable on the metric the audience already trusts.

---

## 10. Governance Requirements

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-PLT-011` | A release candidate MUST NOT ship with any `MUST_HAVE` requirement lacking a passing test, or with any untraced schema object or API operation. | DOC-00 §8.5. Forward traceability catches under-delivery; backward traceability catches undocumented scope, which carries attack surface and test burden nobody agreed to accept. | M | AT |
| `PRD-PLT-012` | The requirement register MUST be regenerated and the corpus validated as a release gate. | The register is the mechanism by which dangling references and unregistered requirements are detected. It has already caught twenty-seven requirements invisible to it (DOC-06 change history) — a defect no visual review would find. | M | AT |
| `PRD-PLT-013` | Every accepted residual risk MUST be reviewed when its revisit trigger condition occurs, and the review outcome MUST be recorded. | DOC-26 §11 and `SEC-PLT-009`. A dated review produces a meeting that concludes nothing; a conditional trigger produces a review with a decidable question. | M | DI |
| `PRD-PLT-014` | Each release MUST record which deferred capabilities remain deferred and whether their reserved extension points are still valid. | Reserved extension points decay silently as the model evolves. A release that closed one without noticing has given up the option rather than deferring it (`CON-PLT-045`). | M | DI |
| `PRD-PLT-015` | The platform MUST be subject to its own assessment regime before commercial release, including penetration testing covering every scenario of DOC-26 §7. | `SEC-SEC-061`. A posture management product that has not managed its own posture cannot be recommended by its own logic, and the omission is the first thing an evaluating security team will find. | M | PT |

---

## 11. Closing

### 11.1 Requirements

Fifteen requirements, `PRD-PLT-001` – `015`, all `MUST_HAVE`.

| Group | IDs | Count |
|---|---|---|
| Build order | `001` | 1 |
| Phasing | `002` – `003` | 2 |
| Descoping | `004` – `006` | 3 |
| Onboarding | `007` – `008` | 2 |
| Decisions | `009` – `010` | 2 |
| Governance | `011` – `015` | 5 |

### 11.2 Extensibility

The deferred capabilities of §3.3 each have a reserved extension point, and `PRD-PLT-014` requires each release to confirm the reservation is still valid. The build order is dependency-derived, so a new capability's position follows from what it depends on rather than from negotiation.

**Deliberate rigidity.** Capability-level descoping only (`PRD-PLT-004`); unreconstructable mechanisms delivered regardless of the capability that consumes them (`PRD-PLT-005`); disqualifying technology constraints (`PRD-PLT-009`).

### 11.3 What this document does not do

It does not schedule. Sequencing is derivable from dependency; duration is not derivable from anything in this corpus, because it requires team composition and velocity data that does not exist. Presenting a schedule here would give a number with no basis, which DOC-00 §16.3 prohibits for scale figures and which applies equally to dates.

### 11.4 Notes for downstream documents

| Document | Note |
|---|---|
| DOC-15 | Block order determines environment provisioning order; the eight technology decisions of §8.1 are owed here |
| DOC-16 | `PRD-PLT-011` and `-012` are release gates and must be implemented as such rather than as advisory checks |
| DOC-19 | Descoping decisions taken under `PRD-PLT-006` are recorded as ADRs |
| DOC-20 | OQ-015 and OQ-026 are the two urgent questions; both block block 1 |

### 11.5 Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.0.0 | 2026-08-04 | Staff Product Manager; Chief Software Architect | Initial content-complete version. States release strategy against the ratified internal-first assumption and identifies the configurability-shortcut risk it creates. Defines the v1 boundary by the test that four of the five truths of DOC-01 §4.3 produce a tool rather than a posture system. Defers ten capabilities with justification and reserved extension points, including AI on the basis that the architecture rather than the shipped capability carries the AI-native claim. Derives a fifteen-block build order from dependency and identifies four mechanisms that cannot be reordered because their data is unreconstructable. Specifies eight phases each independently usable, with P4 identified as the adoption test for ADR-028. Presents six descoping options as whole capabilities with impact analysis per DOC-01 §15.3, ordered, with exceptions identified as high and asymmetric impact and configurable workflows as acceptable only for internal-first. Specifies onboarding as a delivery concern with unknown historical coverage recorded as unknown. Lists eight technology decisions with two disqualifying constraints and two urgent open questions. Records eight risks with the two organizational ones identified as the most likely failure modes. Fifteen governance requirements. | Pending |

---

*End of DOC-17.*
