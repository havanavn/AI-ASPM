---
document_id:    DOC-12
title:          Reporting and Dashboards
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        1.0.0
status:         In review
owner:          Staff Product Manager
authors:        [Staff Product Manager, Principal Application Security Engineer]
reviewers:      []
last_updated:   2026-08-04
tier:           6
prerequisites:  [DOC-00, DOC-01, DOC-07, DOC-28]
depends_on:     [DOC-00, DOC-01, DOC-02, DOC-03, DOC-07, DOC-10, DOC-22, DOC-24, DOC-28]
supersedes:     null
adrs_relied_on: [ADR-012, ADR-020, ADR-022, ADR-027]
open_questions: [OQ-015, OQ-019]
requirement_domains: [DSH]
security_review_required: true
---

# 12 — Reporting and Dashboards

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope) · 2. [Universal Rules](#2-universal-rules) · 3. [Scope Root and Drill-Down](#3-scope-root-and-drill-down) · 4. [Coverage Presentation](#4-coverage-presentation) · 5. [Composition — Executive Posture](#5-composition--executive-posture) · 6. [Composition — Security Operations](#6-composition--security-operations) · 7. [Composition — Engineering Ownership](#7-composition--engineering-ownership) · 8. [Composition — Security Team Workload](#8-composition--security-team-workload) · 9. [Comparative Presentation](#9-comparative-presentation) · 10. [Report Catalogue](#10-report-catalogue) · 11. [Templates and Scheduling](#11-templates-and-scheduling) · 12. [Audit Evidence](#12-audit-evidence) · 13. [Requirements](#13-requirements) · 14. [Closing](#14-closing)

---

## 1. Purpose and Scope

**In scope.** Universal presentation rules; scope root and drill-down; coverage presentation; four dashboard compositions each with measures, filters, drill-through, permissions, and export as mandated by DOC-00 §18.2; comparative presentation and its disclosure controls; the report catalogue; templates and scheduled delivery; audit evidence export.

**Out of scope.** Measure definitions, which belong to the domains that compute them (`RSK`, `CAP`, `SBM`, `VUL`); read model schemas (DOC-04 §21); interface design (DOC-08); AI narrative mechanics (DOC-10).

**LC-01.** Requirements are `PRD-DSH-017` onward, continuing DOC-01's sequence.

**LC-02.** This document owns *presentation*. Where a measure appears here, its definition is referenced rather than restated (DOC-00 §6.4).

---

## 2. Universal Rules

Applying to every composition and report.

| Rule | Requirement |
|---|---|
| Scope root derived from the caller, never parameterized | §3 |
| Every measure carries coverage and freshness | §4 |
| Aggregation basis labelled *as-was* or *as-is* | `PRD-DSH-018` |
| Comparative presentation normalized and minimum-population enforced | §9 |
| Drill-through performs full object-level authorization | `PRD-DSH-019` |
| Filter state shareable and re-evaluated as the viewer | `PRD-DSH-020` |
| Generated content labelled as generated | `PRD-AIC-036` |
| Export enforces scope at generation and excludes restricted categories | DOC-11 §13 |
| Per-person data behind a distinct permission | `PRD-CAP-013` |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-DSH-017` | Every composition and report MUST specify its measures, filters, drill-through targets, required permissions, and export formats. A presentation without all five MUST NOT ship. | Mandated by DOC-00 §18.2. A dashboard without defined filters becomes a fixed report; without defined permissions a disclosure path; without export a generator of manual data requests to the security team. | M | DI |
| `PRD-DSH-018` | Every aggregate presentation MUST state whether it aggregates by recorded scope descriptor (*as-was*) or by current hierarchy (*as-is*). | Both are legitimate and they give different answers after a reorganization. An unlabelled report will be compared against another unlabelled report using the other basis, and the discrepancy will be read as a data error (DOC-03 §7.5). | M | AT, DI |
| `PRD-DSH-019` | Drill-through MUST perform full object-level authorization on the records it returns, and MUST NOT inherit authorization from the composition permission. | Chart visibility is not record visibility. Inheriting it is a disclosure through a convenience path (`SEC-AUZ-024` reasoning applied to reporting). | M | AT, PT |
| `PRD-DSH-020` | Filter state MUST be encodable in a shareable reference that re-evaluates against the recipient's authorized scope. | Sharing a view is how colleagues coordinate. Re-evaluation as the recipient is a security requirement in usability clothing: the alternative is a link granting the author's visibility to anyone who receives it. | M | AT, PT |

---

## 3. Scope Root and Drill-Down

The original requirement brief specified drill-down from enterprise to business unit to product to project, universally. That path is unavailable to most users: a business owner has no enterprise tier to descend from.

**Resolution.** Every composition begins at the **highest organization node within the caller's authorized scope** and drills down relative to it.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-DSH-021` | Compositions MUST NOT accept a scope root parameter. The root MUST be derived from the caller's authorization context. | An absolute root parameter is a scope escalation attempt away. Rendering a broader view with out-of-scope data suppressed is worse: it discloses the organization's shape above the caller and the count of peers beside them. | M | AT, PT |
| `PRD-DSH-022` | Navigation MUST NOT disclose the existence of nodes outside the caller's scope, through breadcrumbs, counts, comparison sets, percentile positions, or rank indicators. | Each is an aggregate disclosure that per-record authorization passes cleanly. A rank position discloses both the existence and the relative posture of peers. | M | AT, PT |
| `PRD-DSH-023` | Where a caller's scope contains multiple unrelated roots, the composition MUST present them as siblings under a synthetic root and MUST NOT imply a common ancestor. | A synthetic root that looked like a real node would misrepresent the organization. Presenting them as siblings is accurate and reveals nothing about what sits above them. | M | AT |

---

## 4. Coverage Presentation

PP-1 at the presentation layer. This is the section that prevents the platform producing a confident, wrong executive report.

| Confidence | Presentation |
|---|---|
| `HIGH` | Measure presented with a coverage indicator available on inspection |
| `MEDIUM` | Coverage stated adjacent to the measure |
| `LOW` | Coverage stated prominently; measure de-emphasized |
| `INSUFFICIENT` | **The coverage gap is the primary statement. The measure MUST NOT be presented as a posture figure** |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-DSH-024` | Every measure derived from data with variable coverage MUST present its coverage and freshness, and the presentation MUST NOT be suppressible by configuration or template. | This is the requirement most likely to be dropped during interface design as visual clutter. It is not clutter: it is the difference between a figure and a claim. "Twelve critical, from data three days old, covering seventy percent" is a materially different statement from "twelve critical" (`PRD-SBM-022`). | M | AT, DI |
| `PRD-DSH-025` | At `INSUFFICIENT` confidence the coverage gap MUST be the primary statement and the measure MUST NOT be rendered as a posture figure. | A favourable number over thirty percent coverage is the specific mechanism by which the platform would mislead an executive audience. | M | AT, DI |
| `PRD-DSH-026` | Where an aggregate improves, the presentation MUST distinguish improvement attributable to remediation from improvement attributable to reduced coverage. | A finding count falling because a scanner stopped running looks identical to one falling because vulnerabilities were fixed. This is the most consequential presentation requirement in the corpus (`PRD-RSK-026`). | M | AT, DI |
| `PRD-DSH-027` | Assets never measured MUST appear in reporting as unmeasured and MUST NOT be absent. | Absence reads as absence of problems. A project that has never submitted an SBOM is not low-risk; it is unmeasured (`PRD-SBM-056`). | M | AT |

---

## 5. Composition — Executive Posture

**Audience.** Executives and business owners. **Permission.** `dsh.composition.executive`. **Scope root.** Caller's highest node.

### 5.1 Measures

| Measure | Owner | Presentation |
|---|---|---|
| Posture score for the scope root, with trend | `PRD-RSK-008` | Band primary, value secondary |
| Coverage and confidence for the scope | `PRD-RSK-027` | Always adjacent |
| Findings by band, current and trend | `VUL` | Counts with coverage |
| Known-exploited exposure count | `PRD-VUL-007` | Absolute; no normalization — one is material |
| Service level compliance and breach count by attribution | `PRD-CAP-009` | Attribution split always shown |
| Active exceptions with expiry profile | `PRD-EXC-010` | Count and nearest expiry |
| Child node comparison, normalized | §9 | Minimum population enforced |
| Material changes this period with drivers | `PRD-RSK-025` | Change attribution |
| Coverage gaps: unmeasured and stale asset counts | `PRD-SBM-020`, `-021` | Prominent |
| AI narrative | `PRD-AIC-014` | Labelled as generated; leads with coverage where insufficient |

**Deliberately absent.** Per-person capacity or utilization, in any form, including team aggregate (DOC-07 §6.2). A business owner who can see security-team capacity directs requests by observed availability, bypassing the prioritization the platform exists to enforce. What they legitimately need — when work can start — is `feasible_start_date` at intake.

### 5.2 Filters, drill-through, export

**Filters.** Period; child node; asset class; criticality tier; exposure; `has_personal_data`; band.

**Drill-through.** Node → child composition. Band → finding list filtered to that band within scope. Coverage gap → the unmeasured or stale asset queue. Breach attribution → the breached items with that attribution.

**Export.** Document format for distribution; tabular for the underlying measures; scheduled delivery per §11.

---

## 6. Composition — Security Operations

**Audience.** Practitioners and the program owner. **Permission.** `dsh.composition.operational`.

### 6.1 Queues

Per `PRD-DSH-010`. Every queue supports server-side pagination, sort, column configuration, saved views, bulk action where permitted, and export.

| # | Queue | Columns | Highlight |
|---|---|---|---|
| ① | Unassigned and needs triage | Code, title, node, type, criticality, exposure, submitted, **age**, go-live, **days to go-live**, readiness score, estimated effort | 🔴 age > 2 days, or days to go-live < estimated effort |
| ② | Awaiting requester or third party | Code, blocked since, **days waiting**, blocking attribution, missing items, last escalation, escalation level | 🔴 days waiting > 7 |
| ③ | In progress | Code, assignee, state, days in state, checklist completion, service level remaining, findings so far by band, blockers | 🔴 service level remaining < 20% |
| ④ | Awaiting remediation | Code, node, owner, open findings by band, oldest finding age, remediation deadline, retest eligible | 🔴 remediation deadline breached |
| ⑤ | Awaiting verification | Code, findings to verify, requested, days waiting, assignee | |
| ⑥ | At risk and breached | Code, deadline, days over, **attribution**, owner, escalation state | Sorted by days over |
| ⑦ | Report pipeline | Code, report state, reviewer, days in review | Frequently overlooked stage |
| ⑧ | Coverage health | Node, asset, last successful data, days since, status, failure reason, quality score | 🔴 never measured, or stale beyond threshold |
| ⑨ | Unowned assets | Asset, type, discovered, days unowned, escalation level, candidate node | 🔴 days unowned > 14 |
| ⑩ | Exception expiry | Exception, subject, expires, days remaining, approver | 🔴 expires within 14 days |
| ⑪ | Integration health | Connector, last success, consecutive failures, failure class, circuit state, **success rate** | 🔴 circuit open, or success rate below threshold |
| ⑫ | Confirmed-live secrets | Finding, asset, secret type, detected, rotation state | 🔴 always — highest urgency |

**Queues ⑧ and ⑪ exist because their absence is the classic blind spot.** If forty assets have silently had no data for three months, the vulnerability dashboard shows green — not because they are secure but because there is no data. Queue ⑪ additionally surfaces *intermittent* failure via success rate, which circuit breaking does not catch (`PRD-CON-031`).

### 6.2 Measures

| Measure | Owner |
|---|---|
| Intake against completion, with backlog trend | `PRD-CAP-007` |
| State occupancy over time — cumulative flow | `PRD-CAP-001` |
| Cycle time decomposed by stage | `PRD-CAP-008` |
| Service level exposure over a forward window against capacity | `PRD-DSH-028` |
| Breach count by attribution | `PRD-CAP-009` |
| Backlog age distribution | `VUL`, `PRD-CAP-001` |
| Findings by band and class, with coverage | `VUL` |
| Recurrence rate | `PRD-VUL-013` |
| Verified against inferred closure | `PRD-RSK-042` |
| Automation leverage — proportion detected by automation against manual | `CAP-2` |

**On intake against completion.** Where intake exceeds completion across consecutive periods, backlog grows without bound and no improvement in execution speed can close it. That is an arithmetic conclusion rather than an argument, and it is the strongest instrument a program owner has (JTBD-19).

**On cumulative flow.** State occupancy over time distinguishes a security-team bottleneck from an engineering-remediation bottleneck. A widening `awaiting remediation` band is not the security team's problem; a widening `unassigned` band is.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-DSH-028` | Service level exposure MUST be presented over a forward window against available capacity, not only as current breaches. | A view showing only current breaches reports history. Forward exposure against capacity permits renegotiation before a date is missed, which is the only useful moment. | M | DM |
| `PRD-DSH-029` | Breach counts MUST be presented decomposed by attribution and MUST NOT be presented as a single figure. | Unattributed lateness defaults to blaming the security team, which is usually wrong and always corrosive. The decomposition is frequently the single most consequential view for the program owner's standing (PP-6). | M | AT, DI |
| `PRD-DSH-030` | Closure figures MUST distinguish verified remediation from other closure reasons wherever presented. | An undifferentiated closure rate is the metric most easily optimized by closing rather than fixing, and it is the figure most likely to reach an executive summary (`PRD-RSK-042`). | M | AT, DI |

---

## 7. Composition — Engineering Ownership

**Audience.** Engineering owners and requesters. **Permission.** `dsh.composition.engineering`. **Scope root.** Caller's assigned nodes.

### 7.1 Design premise

A list of four hundred findings across the group is noise to this audience; twelve findings on their services with deadlines is actionable. This composition is deliberately narrow, and its adoption determines whether findings get fixed (DOC-01 §7.2 A5).

### 7.2 Measures and queues

| Element | Content |
|---|---|
| My open obligations | Findings and work items assigned to the caller or their teams, ordered by deadline then score |
| Deadlines this period | Forward view of remediation commitments |
| My requests | Requests the caller submitted, with state and feasible start |
| My assets | Assets owned by the caller's nodes, with posture and coverage |
| Coverage gaps on my assets | Unmeasured or stale, with what to do about it |
| Recently resolved | Verified closures, as feedback |
| Exceptions on my scope | Active, with expiry |

**Deliberately absent.** Any comparison against other nodes. This composition serves action, not standing; peer comparison here would produce disputes rather than remediation, and it is also a disclosure surface (§9).

**Coverage gaps state the remedy.** A gap presented without the action to close it is a complaint. This composition states what to submit and where.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-DSH-031` | The engineering composition MUST present coverage gaps together with the action that closes them. | This audience can close the gap and generally does not know how. A gap without a remedy is a complaint, and complaints are ignored. | M | DM |
| `PRD-DSH-032` | The engineering composition MUST NOT present comparison against other organization nodes. | It serves action rather than standing. Comparison produces disputes rather than remediation, and it discloses peer posture. | M | AT |

---

## 8. Composition — Security Team Workload

**Audience.** Program owner. **Permission.** `dsh.composition.workload`, plus `cap.member.read.all` for per-person elements.

This is the fourth composition, and ADR-012 consolidated five dashboards into three. The reason it is a fourth rather than a scope variant: the other three render vulnerability, asset, and risk data over one metrics read model; this one renders **people and work**, which is a different data domain and cannot come from the same projection (DOC-03 §5.1).

### 8.1 Measures

| Measure | Owner | Presentation |
|---|---|---|
| Team capacity against allocation, by period | `PRD-CAP-002`, `-004` | Over-allocation highlighted |
| Utilization trend | `PRD-CAP-005` | **Against a target band, never against a maximum** |
| Effort distribution by work category | `PRD-CAP-006` | Proportional, over time |
| Intake against completion with backlog trend | `PRD-CAP-007` | |
| Cumulative flow | `PRD-CAP-001` | |
| Cycle time by stage | `PRD-CAP-008` | Including time awaiting external parties |
| Breach attribution | `PRD-CAP-009` | |
| Per-member workload | `PRD-CAP-013` | **`RESTRICTED`**; distinct permission; access audited |
| Competency coverage and single-person coverage | `PRD-CAP-012` | Continuity risk |
| Estimation bias by type | `PRD-CAP-011` | With confidence where calibration data is thin |
| Feasible start date | `PRD-CAP-010` | Consumed at intake |
| Blocked work with blocking party and duration | `PRD-CAP-015` | Actionable queue |

### 8.2 Two presentation rules that matter

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-DSH-033` | Utilization MUST be presented against a configurable target band, never against a maximum, and the presentation MUST state why the band is below full allocation. | Presenting against one hundred percent invites the conclusion that a team at seventy percent is idle. Queueing behaviour makes this badly wrong: as utilization approaches saturation, waiting time grows non-linearly, so a team sustained near full allocation has materially worse delivery times and no capacity to absorb urgent work. Without the stated reason, someone will read the band as underperformance (`PRD-CAP-005`). | M | AT, DI |
| `PRD-DSH-034` | The composition MUST state, where individual measures are presented, that they are for capacity planning and not for performance evaluation or ranking. | Measurement changes behaviour, and the behaviour depends on what people believe it is for. Stating the purpose where the data appears is the cheapest control against the gaming that would otherwise make the data describe the gaming rather than the work (`PRD-CAP-014`). | M | DI |

### 8.3 Access constraints

| Element | Requirement |
|---|---|
| Per-member measures | `cap.member.read.all`; every access audited at object granularity |
| Team aggregates | Minimum contributing-member count enforced or suppressed (`INV-CAP-04`) |
| Business owner and executive access | **Excluded entirely**, including team aggregate (DOC-07 §6.2) |
| Export of per-member data | Separate permission; individually audited (`PRD-ING-055`) |

⚠ **Working assumption (OQ-019):** team size and availability data source unresolved. Manual availability entry is supported; the measures are unaffected.

---

## 9. Comparative Presentation

Comparison is what makes an aggregate actionable and is also the platform's principal inference surface.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-DSH-035` | Comparative presentation MUST normalize for portfolio size and criticality composition, and MUST state the normalization applied. | Un-normalized comparison penalizes large business units for being large and is dismissed on first presentation, correctly — and dismissal of one metric spreads to the rest. An unstated normalization is indistinguishable from an unfair comparison (`PRD-RSK-030`). | M | AT, DI |
| `PRD-DSH-036` | Comparison sets MUST enforce a minimum population or suppress, and MUST NOT permit derivation of an out-of-scope value by subtraction from a broader total. | A comparison against two peers discloses those peers' posture by inference. Showing a scope subtotal alongside a broader total makes the difference the out-of-scope value (`SEC-AUZ-026`). | M | AT, PT |
| `PRD-DSH-037` | Comparative presentation MUST state the coverage of each compared entity. | Comparing a well-measured unit against a poorly-measured one without saying so favours the latter, which inverts the metric's meaning and rewards not measuring. | M | AT, DI |
| `PRD-DSH-038` | Rank position and percentile MUST NOT be presented where the comparison set includes entities outside the caller's scope. | Both disclose the existence and relative posture of peers even where no peer identity is shown. | M | AT, PT |

---

## 10. Report Catalogue

| Report | Audience | Content | Delivery |
|---|---|---|---|
| **Executive posture** | Executive, business owner | Executive composition with narrative, per scope and period | Scheduled, document |
| **Operational summary** | Program owner | Queues, flow, attribution, coverage | Scheduled or on demand |
| **Node posture** | Business owner | Single node with children, normalized | Scheduled |
| **Assessment report** | Requester, engineering owner | One assessment: scope, coverage, findings, evidence references, conditions | On completion |
| **Finding register** | Practitioner, audit | Filtered finding set with full attributes | On demand, tabular |
| **Exception register** | Audit, business owner | Active and historical exceptions with approvals and expiry | Scheduled or on demand |
| **Coverage report** | Program owner, business owner | Measured, unmeasured, stale, quality, by node | Scheduled |
| **Service level report** | Program owner, business owner | Compliance, breaches by attribution, paused time | Scheduled |
| **Capacity report** | Program owner | Workload composition, aggregate only unless permitted | Scheduled |
| **Audit evidence** | Audit | §12 | On demand |
| **Tenant configuration** | Tenant administrator | Current configuration with change history | On demand |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-DSH-039` | The assessment report MUST include coverage — items assessed, not applicable with reasons, and not assessed — and MUST NOT present findings without it. | An assessment reporting no findings is meaningless without knowing what was examined. Undifferentiated "no findings" is indistinguishable from "we did not look" (`INV-ASM-11`). | M | AT, DI |
| `PRD-DSH-040` | The exception register MUST show expiry, approver, compensating controls, and renewal chains. | It is the most frequently requested audit artifact and the one that reveals whether the exception process functions or is a rubber stamp. A renewal chain shows a repeatedly-renewed exception as such rather than as one long decision. | M | AT |

---

## 11. Templates and Scheduling

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-DSH-041` | Report templates MUST be tenant-configurable in structure, section selection, and branding, and MUST be validated to reference only available measures. | Executive reporting format is an organizational convention. A fixed format is rewritten by hand every cycle, reintroducing the manual work the capability exists to remove. | S | DM |
| `PRD-DSH-042` | Templates MUST NOT be able to suppress coverage presentation, generated-content labelling, normalization statements, or aggregation basis labels. | These are the four honesty mechanisms of this document. A configurable template that could remove them would make them optional, and they would be removed by whoever wants a cleaner-looking report. | M | AT, CR |
| `PRD-DSH-043` | Scheduled reports MUST be generated per recipient with scope evaluated at generation, and a single artifact MUST NOT be delivered to recipients with differing authorization. | One artifact to multiple recipients is a disclosure to the least-authorized among them, and a delivered report cannot be recalled. | M | AT, PT |
| `PRD-DSH-044` | Templates MUST be data, not executable, and MUST NOT have access to platform internals. | A tenant-authored template with expression capability is server-side template injection through configuration (`SEC-SEC-037`). | M | AT, CR |
| `PRD-DSH-045` | Scheduled delivery failure MUST be surfaced to the schedule owner, and a recipient who has lost access MUST be dropped from delivery with the owner informed. | A recipient silently retained after losing access receives an ongoing disclosure; one silently dropped leaves the owner believing they are informed. | M | AT |

**`PRD-DSH-042` is the load-bearing requirement in this section.** Every other honesty mechanism in this document is defeated if a template can remove it.

---

## 12. Audit Evidence

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-DSH-046` | Audit evidence export MUST assemble, for a scope and period: assessments with coverage, findings with lifecycle and closure reasons, exceptions with approvals, service level compliance with attribution, access review output, and configuration change history. | Audit preparation otherwise consumes multiple days of senior time assembling by hand from exports. The evidence exists; the requirement is to assemble it on request. | S | AT |
| `PRD-DSH-047` | The artifact MUST record its scope, filters, generation time, and the aggregation basis, and its generation MUST be audited. | An evidence artifact whose scope and basis are not recorded cannot be relied upon by the auditor receiving it, and cannot be distinguished later from a narrower or broader one. | M | AT |
| `PRD-DSH-048` | Audit evidence MUST NOT include credentials, secret values, evidence content, or per-person workload data. Evidence artifacts MUST be referenced rather than embedded. | Same absolute exclusions as export. Referencing rather than embedding lets an auditor request specific items through the audited reveal path rather than receiving a bundle of exploit material. | M | AT, CR |

---

## 13. Requirements

Thirty-two requirements, `PRD-DSH-017` – `048`, all `MUST_HAVE` except `PRD-DSH-041` and `PRD-DSH-046`.

| Group | IDs | Count |
|---|---|---|
| Universal rules | `017` – `020` | 4 |
| Scope root | `021` – `023` | 3 |
| Coverage | `024` – `027` | 4 |
| Operations composition | `028` – `030` | 3 |
| Engineering composition | `031` – `032` | 2 |
| Workload composition | `033` – `034` | 2 |
| Comparison | `035` – `038` | 4 |
| Report catalogue | `039` – `040` | 2 |
| Templates and scheduling | `041` – `045` | 5 |
| Audit evidence | `046` – `048` | 3 |

Satisfies `PRD-DSH-001` – `016`, `CFG-DSH-001`, `PRD-CAP-005`, `-013`, `-014`, `PRD-RSK-026`, `-030`, `-031`, `PRD-SBM-022`, `-056`.

---

## 14. Closing

### 14.1 Extensibility

Compositions are declarative arrangements of measures and queues over the read model. A new measure is a definition in its owning domain that becomes available to every composition. A new composition is configuration. Tenant-authored compositions are anticipated and not delivered: they require a query-safety layer over the read model, which is a distinct engineering commitment.

**Deliberate rigidity.** No scope root parameter (`PRD-DSH-021`); coverage presentation not suppressible (`PRD-DSH-024`, `-042`); templates not executable (`PRD-DSH-044`); per-recipient generation (`PRD-DSH-043`); business owner and executive exclusion from capacity data.

**Known extension cost.** Every new aggregate or comparative presentation requires an inference-disclosure review (§9, `RISK-PLT-006`), and it is the review most likely to be skipped because the presentation looks innocuous. A new measure requiring a read model dimension the projection does not carry requires backfill (DOC-04 §21).

### 14.2 Security considerations

Dashboards are aggregation surfaces, and aggregation discloses through arithmetic what per-record authorization correctly withholds.

| Risk | Control |
|---|---|
| Scope escalation through a root parameter | Root derived, not parameterized (`PRD-DSH-021`) |
| Organization shape disclosure through navigation | No out-of-scope nodes in breadcrumbs, counts, or ranks (`PRD-DSH-022`, `-038`) |
| Peer posture inference through comparison | Minimum population; no subtraction from a broader total (`PRD-DSH-036`) |
| Coverage-adjusted comparison favouring the unmeasured | Coverage stated per compared entity (`PRD-DSH-037`) |
| Record disclosure through drill-through | Full object-level authorization (`PRD-DSH-019`) |
| Author scope travelling with a shared view | Re-evaluated as the viewer (`PRD-DSH-020`) |
| Disclosure to the least-authorized scheduled recipient | Per-recipient generation (`PRD-DSH-043`) |
| Personnel data exposure | Distinct permission; audited; excluded from business owner and executive |
| Honesty mechanisms removed by template | Not suppressible (`PRD-DSH-042`) |
| Template injection | Templates are data (`PRD-DSH-044`) |

**Residual risk.** Aggregation is an open-ended disclosure channel: §9 addresses the presentations identified, and each new one creates a new channel (`RISK-PLT-006`). The per-addition review is procedural, which DOC-26 §13.2 identifies as the weaker kind of control.

### 14.3 Notes for downstream documents

| Document | Note |
|---|---|
| DOC-08 | Owes the visual treatment of coverage that is prominent enough to satisfy `PRD-DSH-024` and not so heavy that it is removed; the `INSUFFICIENT` presentation; the utilization target band with its stated reason |
| DOC-15 | Owes read model infrastructure, scheduled generation capacity that does not contend with interactive load (`NFR-DSH-003`), and artifact storage with expiring references |
| DOC-16 | Owes: a load test per composition at the `NFR-DSH-001` targets; inference tests attempting subtraction and small-population comparison; a template test asserting honesty mechanisms cannot be suppressed; a per-recipient scheduled delivery test |
| DOC-17 | ⚠ OQ-015 affects composition load targets; OQ-019 affects capacity measure availability |

### 14.4 Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.0.0 | 2026-08-04 | Staff Product Manager; Principal Application Security Engineer | Initial content-complete version. Specifies scope-root-relative drill-down resolving the original brief's absolute path, with navigation prohibited from disclosing out-of-scope structure. Specifies four-level coverage presentation with the measure withheld at insufficient confidence and the improvement-versus-coverage distinction identified as the most consequential presentation requirement in the corpus. Specifies four compositions with measures, filters, drill-through, permissions, and export: executive posture excluding capacity data entirely; security operations with twelve queues including coverage health and integration health as the classic blind spots; engineering ownership deliberately narrow with coverage gaps presented alongside their remedy; and security team workload as a fourth composition justified by its different data domain, with utilization against a target band and the reason for the band stated. Specifies comparative presentation with normalization, minimum population, and coverage per entity. Specifies templates that cannot suppress the four honesty mechanisms, which is the load-bearing requirement of the document. Thirty-two requirements. | Pending |

---

*End of DOC-12.*
