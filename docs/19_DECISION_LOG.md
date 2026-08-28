---
document_id:    DOC-19
title:          Decision Log
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        1.1.0
status:         In review
owner:          Chief Software Architect
authors:        [Chief Software Architect, Principal Security Architect]
reviewers:      []
last_updated:   2026-08-04
tier:           0
prerequisites:  [DOC-00]
depends_on:     [DOC-00]
supersedes:     null
adrs_relied_on: []
open_questions: []
requirement_domains: []
security_review_required: false
---

# 19 — Decision Log

Architecture Decision Records. Format per DOC-00 §13.1. Every record states negative consequences — an ADR listing only benefits conceals the tradeoff a future team most needs to understand — and a **revisit trigger** expressed as a condition rather than a date.

An ADR is never edited to reflect a changed decision. A new ADR is written and the old is marked `Superseded by ADR-nnn`, retained in full, so the corpus can answer why the earlier approach was chosen and what changed.

ADR-049 through ADR-056 are the eight technology selections DOC-02 §16 deferred and DOC-15 §2 specified. They carry two fields beyond DOC-00 §13.1 — **Constraint verification** and **Gaps accepted** — because `OPS-DEP-001` requires the constraint verification to be recorded in the ADR and `OPS-DEP-002` requires a gap against a required property to be recorded with its compensating control. The set is introduced below the existing records, under its own heading.

## Index

| ID | Decision | Status |
|---|---|---|
| ADR-001 | Two orthogonal structures rather than one linear hierarchy | Accepted |
| ADR-002 | Tenant is a hard isolation boundary from v1 | Accepted |
| ADR-003 | Modular monolith with enforced boundaries, not microservices at v1 | Accepted |
| ADR-004 | Sender-constrained credentials; no bearer API keys | Accepted |
| ADR-005 | AI writes only to a suggestion ledger | Accepted |
| ADR-006 | One design language: Linear-style density with Azure-grade information architecture | Accepted |
| ADR-007 | ASVS Level 2 baseline with enumerated Level 3 uplift | Accepted |
| ADR-009 | One Asset aggregate with a type registry | Accepted |
| ADR-010 | One OrgNode hierarchy with a closure table and configurable node types | Accepted |
| ADR-011 | One normalization and deduplication pipeline | Accepted |
| ADR-012 | Dashboard compositions over one read model, with scope injected | Accepted |
| ADR-013 | The SBOM module stores and matches; it does not execute scanners over source | Accepted |
| ADR-016 | The matcher is pluggable, not a hardcoded dependency | Accepted |
| ADR-017 | Project build tooling is never executed | Accepted |
| ADR-019 | Test credentials in a vault, excluded from every export | Accepted |
| ADR-020 | WorkItem abstracts over all AppSec work, not only pentest requests | Accepted |
| ADR-021 | Effort derived from state duration; no mandatory timesheets | Accepted |
| ADR-022 | Per-person workload data is RESTRICTED and permission-gated | Accepted |
| ADR-023 | SBOM push API is the only automated ingestion path in v1 | Superseded in part by ADR-060 |
| ADR-024 | The platform never fetches, clones, or persists source code | Accepted |
| ADR-026 | Container registry scanning deferred, not descoped | Accepted |
| ADR-027 | No hardcoded roles, personas, organizational levels, or vocabulary | Accepted |
| ADR-028 | The platform replaces the incumbent issue tracker for AppSec work | Accepted |
| ADR-029 | Approval gates before work enters the backlog are tenant-configurable, default off | Accepted |
| ADR-030 | No foreign keys across module boundaries | Accepted |
| ADR-031 | Time-ordered UUID primary keys | Accepted |
| ADR-032 | Scope descriptors embedded as columns with an array ancestor path | Accepted |
| ADR-033 | Tenant-scoped rather than global component interning | Accepted |
| ADR-034 | The audit hash chain covers the payload hash, not the payload | Accepted |
| ADR-035 | Principal records are anonymized, not deleted, under erasure | Accepted |
| ADR-036 | API security expressed as annotation classes | Accepted |
| ADR-037 | Keyset pagination only; no filter expression language | Accepted |
| ADR-038 | AI produces narrative with placeholders bound to record fields, not generated numbers | Accepted |
| ADR-039 | Notification is never a transition side effect | Accepted |
| ADR-040 | One-way outbound propagation; no bidirectional state synchronization | Accepted |
| ADR-041 | Identity synchronization does not write role assignments | Accepted |
| ADR-042 | Technology selection carries two disqualifying constraints | Accepted |
| ADR-043 | The test strategy rejects a conventional pyramid | Accepted |
| ADR-044 | AI capabilities deferred from v1 while the AI architecture is built | Accepted |
| ADR-045 | Descoping occurs at capability level, never at requirement level | Accepted |
| ADR-046 | Coverage and freshness are materialized with the measures they qualify | Accepted |
| ADR-047 | Restricted fields are absent from representations, not masked | Accepted |
| ADR-048 | DOC-00 convention corrections during authoring | Accepted |
| ADR-049 | PostgreSQL 18 or later as the operational store | Accepted |
| ADR-050 | Java on the JVM with Gradle, contract-partitioned modules, and compile-blocking static analysis | Accepted |
| ADR-051 | Full-text search in the operational store; no separate search engine at v1 | Accepted |
| ADR-052 | Provider-agnostic secrets contract with a permissively licensed default; per-tenant namespace and hardware-backed key wrapping | Accepted |
| ADR-053 | Read model in the operational engine on read replicas; no analytical engine at v1 | Accepted |
| ADR-054 | Durable queue in the operational store, platform-owned; no message broker at v1 | Accepted |
| ADR-055 | Valkey as the shared cache, with a two-tier topology; cross-tenant key isolation is an application-layer control | Accepted |
| ADR-056 | S3-compatible object storage with per-tenant access policy and per-tenant supplied encryption key | Accepted |
| ADR-057 | The JDK HTTP server as the application tier's HTTP runtime, behind the TLS-terminating ingress | Accepted |
| ADR-058 | Server-rendered HTML from the application tier, with no build step and no client framework | Accepted |
| ADR-059 | Local password and TOTP authentication for v1, **narrowing ADR-004** | Accepted |

---

## ADR-001 — Two orthogonal structures rather than one linear hierarchy

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** The original requirement brief specified a single containment chain: Business Unit → Product → Project → Assessment → Finding → Repository → Service → API → Domain → SCA Scan → SBOM. Read as containment this asserts that a finding contains a repository and that a repository contains a service. Neither is true. One repository builds three services; one API is published on four domains; one finding affects six repositories.

**Options considered.**

- A. Implement the linear chain as specified — nested foreign keys throughout
- B. Two orthogonal structures: an organization tree for scope and accountability, an asset graph for technical reality, joined by an ownership edge
- C. A single generic graph with typed nodes for both organization and assets

**Decision.** Option B.

**Consequences.** Positive: the question *which internet-facing systems contain this vulnerable component* becomes a single traversal, and it is unanswerable under option A. Scope and technical topology evolve independently, which matches reality. Negative: two structures to learn, and the join between them — asset ownership — becomes a load-bearing invariant that must be enforced (`PRD-AST-003`). Option C was rejected because merging accountability and topology into one graph loses the single-parent invariant that makes aggregation correct.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** If a customer structure requires an asset to be accountable to more than one organization node, revisit — but prefer stakeholders over co-owners first.

---

## ADR-002 — Tenant is a hard isolation boundary from v1

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** The personas in the original brief are roles inside one enterprise, which is RBAC rather than multi-tenancy. But the product is intended as a commercial platform across markets, which implies real tenant isolation with separate key material and probable data residency separation.

**Options considered.**

- A. Logical separation now, harden later when a second customer arrives
- B. Hard tenant boundary from v1: persistence-layer enforcement, per-tenant keys, residency pinning
- C. Separate database per tenant

**Decision.** Option B.

**Consequences.** Positive: the most expensive refactor in enterprise SaaS is avoided. Cost is roughly five to eight percent of v1 effort. Negative: every query, cache key, and background job carries tenant context from the first commit, which is discipline the team must sustain — mitigated by making enforcement structural (`PRD-TEN-001`) rather than a convention. Option C was rejected as operationally unaffordable at the intended tenant count and as making cross-tenant platform operations harder rather than safer.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** If the product is definitively confined to a single organization forever, this is over-engineering — but that decision would need to be irreversible to justify revisiting.

---

## ADR-003 — Modular monolith with enforced boundaries, not microservices at v1

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** Twenty-plus bounded contexts with a domain model not yet validated by real usage. Microservices would fix context boundaries before they are known to be correct.

**Options considered.**

- A. Microservices per bounded context
- B. Modular monolith with compile-time-enforced module boundaries and documented extraction seams
- C. Unstructured monolith

**Decision.** Option B.

**Consequences.** Positive: context boundaries can be corrected cheaply while the model is still being learned; one deployable artifact; no distributed transaction problem in v1. Negative: independent scaling of a single module is unavailable without extraction work; a fault in one module can affect process-level availability; **module boundary discipline erodes unless tooling enforces it**, which is why enforcement must be compile-time rather than by review. Ingestion and AI are the two modules most likely to need extraction first, and their seams are documented for that reason.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Revisit when any single module's resource profile requires independent scaling, or when team count exceeds four independent squads.

---

## ADR-004 — Sender-constrained credentials; no bearer API keys

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** The brief specified JWT, OAuth2, API keys, and HMAC signatures simultaneously. Four parallel authentication mechanisms multiply attack surface and guarantee that the weakest becomes the breach path.

**Options considered.**

- A. All four as specified
- B. OIDC/OAuth2 for humans; OAuth2 client-credentials with mTLS or DPoP for services; signed requests with replay protection only for legacy CI that cannot do either. No raw bearer keys
- C. Bearer API keys only, for simplicity

**Decision.** Option B.

**Consequences.** Positive: no replayable credential is offered as a peer option. Negative: integration is harder for pipeline authors, who find a static token easiest — which is why the signed-request path exists rather than a flat refusal (`PRD-IAM-009`). A documented deprecation path is required for the legacy option or it becomes permanent.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Revisit when the legacy CI population supporting only signed requests reaches zero.

---

## ADR-005 — AI writes only to a suggestion ledger

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** The brief required that AI never modify data automatically, while also requiring AI to cluster findings and recommend priorities. Both are writes if implemented naively, so the requirements as stated are mutually exclusive.

**Options considered.**

- A. Weaken the no-automatic-modification rule for clustering and prioritization
- B. A separate suggestion store that is not the system of record; promotion requires an audited human action
- C. Drop AI clustering and prioritization

**Decision.** Option B.

**Consequences.** Positive: both requirements are satisfied honestly rather than by weakening one. Provenance — model identity, version, prompt hash, retrieved context, acting user — makes the arrangement auditable rather than merely stated. Negative: every AI capability needs a promotion surface, which is additional interface work, and users must act on suggestions rather than receiving finished state.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit. Relaxing this forfeits the reproducibility on which every audit-facing capability depends.

---

## ADR-006 — One design language: Linear-style density with Azure-grade information architecture

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** The brief cited Linear, GitHub, and Vercel alongside Azure Portal and Atlassian. These are opposite design languages — minimal, low-chrome, keyboard-first versus dense, nested, panel-heavy.

**Options considered.**

- A. Follow both, per screen
- B. Linear's interaction density and keyboard-first model with Azure Portal's information architecture rigor for deep hierarchies
- C. Follow Azure Portal throughout

**Decision.** Option B.

**Consequences.** Positive: one coherent language; keyboard-first serves both practitioners working in the interface for hours and accessibility conformance. Negative: deep hierarchy navigation in a low-chrome idiom is genuinely harder to design than in a panel-heavy one, and it will require more design iteration.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Revisit if usability testing shows navigation failure in deep organization trees.

---

## ADR-007 — ASVS Level 2 baseline with enumerated Level 3 uplift

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** The brief specified ASVS Level 2. The platform stores a prioritized, evidence-bearing map of an entire enterprise group's exploitable weaknesses, plus working exploit material and live credentials. It is a higher-value target than most systems it protects.

**Options considered.**

- A. Level 2 as specified
- B. Level 2 baseline with enumerated Level 3 uplift for authentication, session management, cryptography, secrets management, and audit logging
- C. Level 3 throughout

**Decision.** Option B.

**Consequences.** Positive: control strength is proportionate to asset value where it matters, and each uplift is individually justified rather than blanket-claimed. Negative: a mixed-level claim requires per-control documentation to be defensible, which is more work than either uniform option. Option C was rejected as disproportionate in areas where Level 2 is adequate and as diluting the claim by making it unverifiable.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Revisit if the platform begins storing customer production data rather than findings about it.

---

## ADR-009 — One Asset aggregate with a type registry

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** The brief listed five inventories: application, repository, service, API, domain. Implementing five produces five sets of ownership logic, five permission paths, five deduplication implementations, and five dashboards.

**Options considered.**

- A. Five separate inventories as listed
- B. One Asset aggregate with an asset type registry

**Decision.** Option B.

**Consequences.** Positive: adding an asset type in future is a registration, not a migration — container image, mobile application, model, data store are all anticipated. One ownership model, one permission path, one deduplication. Negative: type-specific attributes require a typed schema mechanism, which is more initial work than five fixed tables and requires validation discipline.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Revisit only if an asset type emerges whose lifecycle is genuinely incompatible with the shared model.

---

## ADR-010 — One OrgNode hierarchy with a closure table and configurable node types

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** The brief specified Business Unit, Product, and Project as three fixed levels. Conglomerate structures differ: some need Group, Division, Sub-BU; some need fewer levels.

**Options considered.**

- A. Three fixed tables as specified
- B. One OrgNode hierarchy, closure table, tenant-configured node types

**Decision.** Option B.

**Consequences.** Positive: inserting or renaming an organizational level is configuration, which ADR-027 requires. Subtree resolution for authorization and aggregation is a single query at any depth. Negative: closure table maintenance on reorganization is non-trivial and must be correct, since a corrupted closure table breaks every subsequent traversal. Cycle rejection must be enforced at write time.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit. Option A cannot satisfy ADR-027.

---

## ADR-011 — One normalization and deduplication pipeline

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** Findings arrive from file import, native SBOM matching, and manual assessment. Each could plausibly have its own path to finding creation.

**Options considered.**

- A. A path per source
- B. One shared normalization and deduplication pipeline

**Decision.** Option B.

**Consequences.** Positive: one identity implementation, so cross-source correlation works and duplicates cannot arise from path divergence. Negative: every source must map into the canonical model even where the fit is awkward, which occasionally loses source-specific nuance. That cost is accepted because divergent identity implementations produce duplicate findings that are undetectable and unrepairable after the fact.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit. A source-specific bypass will be requested when an integration is difficult; granting one is permanent.

---

## ADR-012 — Dashboard compositions over one read model, with scope injected

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** The brief listed five dashboards: Security, Executive, Developer, Business Unit, DCEO. Four differed only in the breadth of organization displayed.

**Options considered.**

- A. Five dashboards as listed
- B. A small number of compositions over one metrics read model, with scope root derived from the caller's authorization context

**Decision.** Option B.

**Consequences.** Positive: one composition is correct for every principal, and an entire class of authorization defect disappears because there is no navigation path to construct. Negative: a fourth composition was subsequently added for security team workload — that is a different data domain (people and work rather than vulnerabilities and assets) and could not render from the same read model, which is documented at DOC-01 §10.13.1.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Revisit if a further audience emerges whose data domain differs again.

---

## ADR-013 — The SBOM module stores and matches; it does not execute scanners over source

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** The requirement was for Trivy-based rescan of SBOM and SCA data. Two readings are possible: execute a scanner over project source, or match stored SBOMs against updated vulnerability intelligence.

**Options considered.**

- A. Execute scanners over fetched project source
- B. Store SBOMs and match them against intelligence; no scanner execution over source

**Decision.** Option B.

**Consequences.** Positive: no untrusted source code is processed, which removes an entire trust boundary and its sandbox requirement. Matching a stored SBOM takes seconds rather than minutes, so portfolio-wide re-evaluation on a new disclosure completes in the same working day (`NFR-SBM-003`). Negative: **the platform is blind between SBOM submissions**, which is the origin of the coverage and freshness governance requirements at DOC-01 §10.8.4 and of PP-1.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Revisit under DF-02 if source access policy is relaxed.

---

## ADR-016 — The matcher is pluggable, not a hardcoded dependency

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** Trivy is the reference matcher. Alternatives exist and intelligence sources differ by ecosystem.

**Options considered.**

- A. Integrate Trivy directly
- B. A match-engine interface with Trivy as the reference implementation

**Decision.** Option B.

**Consequences.** Positive: an additional or alternative matcher is a registration. Negative: an abstraction over one implementation is speculative until the second arrives, and the interface may prove wrong-shaped.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Revisit when a second matcher is actually integrated — that is when the interface is validated or corrected.

---

## ADR-017 — Project build tooling is never executed

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** Dependency resolution is more accurate when the project's build runs. Doing so executes unreviewed code inside the platform.

**Options considered.**

- A. Execute builds in a sandbox for accuracy
- B. Never execute build tooling; static manifest analysis only

**Decision.** Option B.

**Consequences.** Positive: no arbitrary code execution path exists in a platform holding the enterprise's attack surface. Negative: dependency resolution accuracy is bounded by what static manifest and lockfile data supports, and transitive resolution for some ecosystems is incomplete. Where full resolution is needed it belongs in the customer's own pipeline.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit. This will be requested for accuracy; it is the requirement most likely to be violated later and is retained as an explicit prohibition (`PRD-SBM-009`) for that reason.

---

## ADR-019 — Test credentials in a vault, excluded from every export

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** Pentest requests require test accounts, at least two per role, to test authorization. Those credentials are live access to pre-production systems that frequently share data or trust with production.

**Options considered.**

- A. Store as request fields
- B. Vault storage by reference; masked by default; audited reveal; absolute export exclusion

**Decision.** Option B.

**Consequences.** Positive: credentials do not appear in exports, backups, logs, error traces, or AI context. Negative: a vault dependency (OQ-026), and reveal is an extra step for the engineer who needs the credential daily — accepted because an export is the one artifact whose subsequent distribution cannot be controlled.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit the export exclusion. Reveal ergonomics may be improved.

---

## ADR-020 — WorkItem abstracts over all AppSec work, not only pentest requests

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** Capacity measurement requires knowing where the team's time goes. An AppSec team's effort goes into architecture reviews, threat models, vendor assessments, tooling, governance, reporting, enablement, and incident support — not primarily into penetration tests.

**Options considered.**

- A. Model pentest requests and findings only
- B. A WorkItem abstraction covering every trackable work category

**Decision.** Option B.

**Consequences.** Positive: capacity data describes the team's actual work. Under option A the platform would report a materially over-capacity team at low utilization, and that number would be used to deny resourcing — **a measurement system producing evidence against its own users is worse than none**. Negative: more work item types to model and configure.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit.

---

## ADR-021 — Effort derived from state duration; no mandatory timesheets

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** Capacity planning needs effort data. Two mechanisms are available.

**Options considered.**

- A. Mandatory time entry per work item
- B. Effort derived from state transition duration, with optional manual adjustment

**Decision.** Option B.

**Consequences.** Positive: data is captured without user burden and is more accurate than retrospective recall. Negative: derived duration cannot see effort spent outside a work item, which is why optional manual logging exists. Option A was rejected because mandatory timesheets are completed from memory, are resented in a way that reduces overall platform adoption, and produce worse data than the mechanism they replace.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Revisit only if chargeback requirements demand precision that derivation cannot provide.

---

## ADR-022 — Per-person workload data is RESTRICTED and permission-gated

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** The workload dashboard exposes per-person utilization and allocation. This is personal data concerning employment.

**Options considered.**

- A. Visible to anyone with dashboard access
- B. Classified RESTRICTED, gated by explicit permission, excluded from business owner and executive views, individually visible to each person, every access audited

**Decision.** Option B.

**Consequences.** Positive: satisfies data protection obligations; prevents business owners routing work by observed availability and thereby bypassing prioritization; and — most importantly — **preserves data quality**, because individuals who believe these measures evaluate them will manage the inputs, and the measures then describe the gaming rather than the work. Negative: aggregate views must enforce a minimum group size to prevent reconstruction, which constrains reporting in small teams.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit. A customer will request productivity comparison; the answer is that the platform does not provide it.

---

## ADR-023 — SBOM push API is the only automated ingestion path in v1

**Status.** Superseded in part by ADR-060 · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

> **Superseded in part, and only in part.** ADR-060 adds a second automated path — a scan-report
> import — so the word *only* no longer holds. Everything else in this record does: the push API is
> still the automated path for bills of materials, its single-point-of-failure consequence still
> stands, and `PRD-SBM-024`'s per-credential submission health is now load-bearing for two doors
> rather than one. The record is retained in full because the reasoning that made exclusivity right
> in v1 is the reasoning ADR-060 had to answer.

**Context.** With no source access (ADR-024), SBOMs must be produced externally. Three sources are possible: CI pipeline push, manual upload, or a platform-issued CLI.

**Options considered.**

- A. Manual upload only
- B. Push API as the automated path, manual upload as fallback
- C. Platform CLI as primary

**Decision.** Option B.

**Consequences.** Positive: automated submission keeps data fresh without human recall. Negative: the endpoint is a single point of failure for the entire SCA capability, which is why submission health must be visible per credential (`PRD-SBM-024`) and why coverage and freshness are first-class metrics. Option A was rejected because manual submission freshness decays to uselessness.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Revisit under DF-01 and DF-02.

---

## ADR-024 — The platform never fetches, clones, or persists source code

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** Customer policy prohibits the platform holding source. Some scanning capabilities would require it.

**Options considered.**

- A. Ephemeral clone into memory, scan, discard
- B. No source acquisition of any kind; no Git credentials stored

**Decision.** Option B.

**Consequences.** Positive: an entire trust boundary and credential class is eliminated. No Git credential store exists to be compromised. Negative: the platform cannot generate SBOMs itself, cannot perform native SAST or secret scanning, and is blind between submissions — accepted, with the consequences made visible through coverage governance rather than hidden. Git repository URLs are retained as reference labels for human use only (ADR-025).

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Revisit under DF-02 if policy is relaxed. Reserved extension points exist so activation is additive.

---

## ADR-026 — Container registry scanning deferred, not descoped

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** Registry scanning requires no source code and would reflect what actually runs in production — arguably more accurate than CI-generated SBOMs.

**Options considered.**

- A. Include in v1
- B. Defer with reserved extension points rejected at the application layer
- C. Exclude permanently

**Decision.** Option B.

**Consequences.** Positive: v1 scope is contained while the future path is additive — a scan target type and an SBOM source value are reserved, so enabling them is code, not migration. Negative: registry-derived data would likely be higher fidelity than CI-submitted SBOMs, so the deferral has a real accuracy cost in the interim.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Revisit when registry access is approved and container deployment is the dominant delivery model.

---

## ADR-027 — No hardcoded roles, personas, organizational levels, or vocabulary

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** The example personas in the original brief — including a role scoped to assigned business units, named for one specific corporate structure — were treated as product entities. The product is intended for any conglomerate.

**Options considered.**

- A. Ship the example roles as product roles
- B. Product-fixed permission catalogue; tenant-configured roles, hierarchy depth, node names, workflows, custom fields, taxonomies, and vocabulary

**Decision.** Option B.

**Consequences.** Positive: the product is deployable by any conglomerate without code modification. Negative: substantially more v1 work — a permission catalogue, a role builder, workflow-as-data, custom fields, a configuration management layer (DOC-01 §11.4), and a default configuration good enough to onboard with. **The operative half is the prohibition on role-name branching in code**, which must be enforced by static analysis rather than review discipline, because the shortcut is faster to write, passes review, and is invisible until a customer's structure breaks it.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit. Option A makes the product unsellable to the second customer.

---

## ADR-028 — The platform replaces the incumbent issue tracker for AppSec work

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** The customer intends to retire external issue tracking and email for application security work. The platform must therefore be the system of record for that work.

**Options considered.**

- A. Integrate bidirectionally with the incumbent tracker
- B. Replace it for AppSec work, with outbound propagation to engineering trackers where development work legitimately lives elsewhere

**Decision.** Option B.

**Consequences.** Positive: one system of record; the security object and the work on it are structurally linked, which is the platform's advantage over a generic tracker. Negative: **this is the product's largest adoption risk.** It requires workflow-as-data, tenant custom fields, full-text search across comments, automation rules, inbound email association, templates, and migration import of history — and if the collaboration experience is materially worse than what the team left, the conversation moves to chat and the platform holds a hollow record of a process happening elsewhere. Option A was rejected because two systems of record for the same item means neither is one (PP-10).

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Revisit if adoption measurement shows collaboration occurring outside the platform despite the primitives being present.

---

## ADR-029 — Approval gates before work enters the backlog are tenant-configurable, default off

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** Where demand exceeds capacity, an approval gate places prioritization with the business rather than making the security team the party that refuses.

**Options considered.**

- A. Always require approval
- B. Configurable per organization node, default disabled
- C. Never

**Decision.** Option B.

**Consequences.** Positive: unnecessary friction is avoided at low volume and available at high volume. Negative: an inconsistently configured gate produces inconsistent intake experience across business units, which generates support questions.

**Compliance.** Verified through the requirements it generates in DOC-01 and their test coverage in DOC-16.

**Revisit trigger.** Revisit if request volume growth makes the default wrong.

---

## ADR-030 — No foreign keys across module boundaries

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-04. A foreign key from a finding to an asset is conventional and couples two modules' deployment.

**Options considered.**

- A. Declare cross-module foreign keys as normal
- B. Intra-module foreign keys only; cross-module integrity enforced in the domain layer and verified by reconciliation

**Decision.** Option B.

**Consequences.** Positive: module deployment decoupled; extraction (DOC-02 §15) remains possible; the actual invariant — a finding must affect at least one asset — is expressible where a foreign key cannot express it. **Negative: an orphaned reference becomes possible where it would otherwise be impossible.** That is a real loss, accepted because a database-level dependency graph mirroring the module graph makes boundary enforcement meaningless at the persistence layer, which is where boundaries erode first.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Revisit if reconciliation reports orphans at a rate indicating the domain-layer enforcement is inadequate.

---

## ADR-031 — Time-ordered UUID primary keys

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-04. Sequential integers enable enumeration and require coordination; random UUIDs fragment indexes at the volumes of the two largest tables.

**Options considered.**

- A. Sequential integers
- B. Random UUIDs
- C. Time-ordered UUIDs

**Decision.** Option C.

**Consequences.** Positive: insert locality on tables reaching 80 million rows; application-side generation, which the aggregate pattern requires; non-sequential across tenants. **Negative: approximate creation time leaks to anyone holding the identifier.** Accepted because the holder is authorized for the object and its creation time is visible to them anyway, and because the random component prevents constructing a neighbouring identifier.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Revisit if an identifier is ever exposed in an unauthenticated or cross-tenant-visible context; an opaque external identifier would then be required.

---

## ADR-032 — Scope descriptors embedded as columns with an array ancestor path

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-04 §6.6. Historical authorization evaluates the descriptor on every historical read.

**Options considered.**

- A. Joined descriptor table
- B. Embedded columns with an array ancestor path

**Decision.** Option B.

**Consequences.** Positive: the predicate "was this principal authorized then?" is a single indexable containment test; the descriptor is physically immutable with its row. **Negative: 60–100 bytes per scope-bearing row on tables reaching hundreds of millions of rows — the single largest storage consequence of `PRD-ORG-011`.** Accepted because the alternative is irreproducible historical reporting, which disqualifies the executive and audit use cases.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Revisit if measured storage at Extra large exceeds the deployment budget; the alternative would be a reconstructable historical closure, which is materially harder.

---

## ADR-033 — Tenant-scoped rather than global component interning

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-04 §15.2. Global interning is more space-efficient — a common library would be one row rather than one per tenant.

**Options considered.**

- A. Global interning
- B. Tenant-scoped interning
- C. No interning

**Decision.** Option B.

**Consequences.** Positive: removes the argument about whether a globally interned table populated by tenant submissions constitutes tenant-derived data under `SEC-TEN-024`, and removes the existence-inference surface. **Negative: approximately 3.2 million rows rather than 300,000.** Negligible against the 80 million entries it makes narrow. Option C was rejected: it costs approximately 10 GB of duplicated identifier text.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit. An arguable tenant boundary is the kind that erodes (`RISK-PLT-004`).

---

## ADR-034 — The audit hash chain covers the payload hash, not the payload

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-04 §20.1, DOC-14 §6. Audit must be immutable; personal data must be erasable; audit events contain personal data.

**Options considered.**

- A. Refuse erasure
- B. Delete events to satisfy erasure
- C. Chain the payload hash; erase the payload

**Decision.** Option C.

**Consequences.** Positive: erasure leaves every chain link verifiable; the event's existence, time, actor, object, and scope remain permanently provable. **Negative: after erasure it is no longer possible to verify that the erased payload matched its hash.** That is the honest limit of the design. Option A is a compliance breach; option B destroys the trail for every other purpose, unrepairably.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit. Both alternatives fail in one direction or the other.

---

## ADR-035 — Principal records are anonymized, not deleted, under erasure

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-04 §24.2.

**Options considered.**

- A. Delete the principal row
- B. Anonymize with a stable pseudonym

**Decision.** Option B.

**Consequences.** Positive: every audit attribution, comment authorship, and transition actor remains resolvable; "the same person did these three things" remains visible without revealing who. **Negative: a residual identifier persists, which a strict reading of erasure might dispute.** Accepted because deletion would destroy the audit trail's completeness to satisfy an erasure request.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Revisit if a regulator requires identifier removal rather than content removal.

---

## ADR-036 — API security expressed as annotation classes

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-05 §5. DOC-00 §15.1 requires nine annotations per operation across roughly 130 operations.

**Options considered.**

- A. Nine annotations repeated per operation
- B. Seven classes assigned per operation, annotating only deviations

**Decision.** Option B.

**Consequences.** Positive: the common case is uniform and deviations are visible; the document is reviewable. **Negative: correctness concentrates in the class definitions — an error in class A's scope re-validation affects every read operation.** Deliberate: one place to get right rather than 130, which makes the class definitions the most test-critical code in the API layer.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Revisit if the deviation count approaches the operation count, indicating the classes no longer describe the common case.

---

## ADR-037 — Keyset pagination only; no filter expression language

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-05 §7.

**Options considered.**

- A. Offset pagination with a query language
- B. Keyset pagination with typed filter parameters

**Decision.** Option B.

**Consequences.** Positive: pagination is stable under concurrent modification, which offset is not; filters are validatable against the index set; there is no injection surface over the schema. **Negative: expressive queries a language would permit are unavailable, and each new filter is a product change.** Accepted because an expression language is unboundable in cost against the platform's largest tables.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Revisit if tenant-authored reporting is delivered; it would require a query-safety layer, which is the same prerequisite recorded for tenant-authored compositions.

---

## ADR-038 — AI produces narrative with placeholders bound to record fields, not generated numbers

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-10 §5.

**Options considered.**

- A. Validate generated numbers against sources
- B. Substitute values from records into placeholders

**Decision.** Option B.

**Consequences.** Positive: an incorrect number becomes unrepresentable rather than detectable. **Negative: output schemas must bind placeholders to fields, which constrains how freely a capability's prose can be structured.** Accepted: a plausible number in a security report is indistinguishable from a correct one to its reader.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit.

---

## ADR-039 — Notification is never a transition side effect

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-09 §3, DOC-13 §3.

**Options considered.**

- A. Notification as a transition side effect
- B. Notification as an event subscriber

**Decision.** Option B.

**Consequences.** Positive: a notification failure neither fails the transition nor is swallowed. **Negative: notification lags the transition by the event dispatch interval.** Accepted because the alternatives are a mail outage becoming a work outage, or a silently lost notification.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit.

---

## ADR-040 — One-way outbound propagation; no bidirectional state synchronization

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-21 §10. ADR-028 makes the platform the system of record; some engineering teams keep their own tracker.

**Options considered.**

- A. Bidirectional synchronization
- B. One-way propagation with divergence surfaced

**Decision.** Option B.

**Consequences.** Positive: one system of record; the judgement "has this been remediated" stays with a human. **Negative: teams keeping their own tracker see a reference rather than a synchronized item, which is a real adoption cost in the population whose cooperation determines whether findings are fixed.** Accepted because two systems of record for one item means neither is one, and reconciliation requires deciding which wins — any answer being wrong in some case.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Revisit if adoption measurement shows the reference model is insufficient for the engineering population.

---

## ADR-041 — Identity synchronization does not write role assignments

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-21 §12.

**Options considered.**

- A. Directory groups drive role assignments directly
- B. Identity sync manages principal existence only; group-to-role mapping is platform-side configuration evaluated at authentication

**Decision.** Option B.

**Consequences.** Positive: the authorization model stays under platform audit and separation-of-duties control. **Negative: a tenant must configure the mapping in two places conceptually, and the platform-side mapping is an additional configuration surface.** Accepted because a directory writing assignments places authorization under the control of whoever administers that directory.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit.

---

## ADR-042 — Technology selection carries two disqualifying constraints

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-15 §2, DOC-02 §16.

**Options considered.**

- A. Select on general suitability and compensate for gaps
- B. Treat row-level security with forced owner enforcement, and build-time module boundary enforcement, as disqualifying

**Decision.** Option B.

**Consequences.** Positive: two structural controls are guaranteed rather than approximated. **Negative: the candidate set narrows, possibly excluding an otherwise preferable option.** Accepted because without either, `CON-DAT-012` and `CON-PLT-013` degrade to code review — which DOC-26 §13.2 identifies as the weaker kind of control, and the erosion is invisible until a breach.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Revisit only if a candidate offers an equivalent structural mechanism under a different name.

---

## ADR-043 — The test strategy rejects a conventional pyramid

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-16 §2.1. A pyramid optimizes for execution speed.

**Options considered.**

- A. Conventional pyramid
- B. Emphasis on structural, isolation, authorization, identity, and honesty suites

**Decision.** Option B.

**Consequences.** Positive: emphasis matches where the consequential failures are — none of which is detectable by testing a unit in isolation. **Negative: the suite is slower and more expensive to maintain, and aggregate coverage percentage becomes an unusable metric** — which is why `TST-PLT-002` prohibits reporting it.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit.

---

## ADR-044 — AI capabilities deferred from v1 while the AI architecture is built

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-17 §3.3. The product is named AI-native.

**Options considered.**

- A. Ship AI capabilities in v1
- B. Build the architecture; defer the capabilities

**Decision.** Option B.

**Consequences.** Positive: removes the least verifiable quality risk from v1; every capability has a non-AI fallback so the platform is fully functional. **Negative: weakens the initial commercial narrative for a product whose name describes AI** — recorded as risk R4.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Revisit once the evaluation harness demonstrates the absolute thresholds are consistently met.

---

## ADR-045 — Descoping occurs at capability level, never at requirement level

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-01 §15.3, DOC-17 §6.

**Options considered.**

- A. Relax individual MUST_HAVE requirements under schedule pressure
- B. Remove whole capabilities with impact analysis

**Decision.** Option B.

**Consequences.** Positive: a removed capability is visibly absent; the remainder is functional. **Negative: descoping is coarser, so the smallest available cut may be larger than the shortfall.** Accepted because a capability with relaxed requirements half works and will be relied upon.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit.

---

## ADR-046 — Coverage and freshness are materialized with the measures they qualify

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-02 §11.3, DOC-04, DOC-12 §4.

**Options considered.**

- A. Compute coverage at presentation time
- B. Materialize coverage in the projection alongside the measure

**Decision.** Option B.

**Consequences.** Positive: omitting the qualifier requires deliberate effort rather than being the default. **Negative: projection schemas widen and a coverage definition change requires backfill.** Accepted because computing at presentation makes the qualifier optional, and an optional qualifier is omitted from the report where it matters most.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit.

---

## ADR-047 — Restricted fields are absent from representations, not masked

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** DOC-07 §10, DOC-08 §9.

**Options considered.**

- A. Mask with a placeholder
- B. Omit the field entirely

**Decision.** Option B.

**Consequences.** Positive: absence discloses nothing. **Negative: a client cannot distinguish "no value" from "not permitted", which occasionally produces a confusing interface** — hence the narrow withheld-indicator exception, justified per field. A masked placeholder confirms a value exists, which for a secret finding confirms a credential exists at that location.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Do not revisit.

---

## ADR-048 — DOC-00 convention corrections during authoring

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect, Staff Product Manager

**Context.** Three convention defects were found by the corpus tooling rather than by review: a class code colliding with a domain code; real requirement identifiers used in illustrative examples, causing the register to record the example in place of the requirement; and an unregistered class code.

**Options considered.**

- A. Correct silently
- B. Correct and record, with the original diagnosis corrected where it was overstated

**Decision.** Option B.

**Consequences.** Positive: the corrections are traceable and one overstated diagnosis is corrected in the record — the class/domain collision was raised as a parser ambiguity, which a positional scheme does not have; the rename proceeded on readability grounds and `CON` retains the same property deliberately. **Negative: the record shows the conventions were imperfect at baseline.** That is accurate.

**Compliance.** Verified through the requirements it generates and their test coverage in DOC-16.

**Revisit trigger.** Revisit the convention set if the tooling finds a fourth defect of this class, which would indicate the conventions need restructuring rather than patching.

---

## Technology selections — ADR-049 to ADR-056

DOC-02 §16 deferred eight technology decisions; DOC-15 §2 restated them with required properties and named the two that carry disqualifying constraints. `OPS-DEP-001` requires the operational store and the application toolchain to be recorded as an ADR carrying the constraint verification, and `OPS-DEP-002` requires any gap against a required property to be recorded as an accepted risk with its compensating control rather than absorbed silently.

The eight records below therefore carry two fields additional to DOC-00 §13.1: **Constraint verification** and **Gaps accepted**. Both are mandatory in these eight only; they are not introduced as a general ADR field, because the general case does not have a named property list to verify against.

**What "demonstrate" means here.** ADR-042 made two properties disqualifying rather than desirable. A candidate satisfies such a property only through a named mechanism present in the shipped product, exercised by an artifact the pipeline runs. A roadmap item does not satisfy it. A convention enforced by a check that the build can be configured to skip does not satisfy it, because `OPS-DEP-026` requires every gate to block and a mechanism whose default is advisory inverts that. Three candidates were rejected on this basis and are recorded with their rejection reason rather than omitted, so a later reviewer can see what was excluded and re-test the judgement.

**The concentration these eight accept together.** Four of the eight resolve to the operational store: the store itself (ADR-049), full-text search (ADR-051), the read model (ADR-053), and the durable queue (ADR-054). This is deliberate. Each of the latter three would otherwise reimplement tenant isolation in a second engine, and DOC-24 §5.1 identifies persistence-layer enforcement as the layer catching the highest-frequency isolation mistake — so a second engine means a second implementation of the platform's highest-severity control, verified separately, decaying separately.

It is nonetheless a real concentration, and it is the aggregate cost of these eight records: the operational store becomes a single failure domain for search, projections, and asynchronous work, its capacity ceiling arrives for all four at once, and a maintenance window on it is a window on all four. ADR-051, ADR-053, and ADR-054 each name the measured condition that triggers extraction to a dedicated component, and the extraction seams of DOC-02 §15 are what make that a migration rather than a rewrite. **A reviewer who finds this concentration unacceptable should reject it here**, where the alternative is three additional isolation implementations, rather than at the first capacity incident, where the alternative is an unplanned migration.

---

## ADR-049 — PostgreSQL 18 or later as the operational store

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect

**Context.** DOC-15 §2, DOC-02 §16, DOC-04 §22.4. The operational store carries the first of ADR-042's two disqualifying constraints. DOC-04 was authored against eight named engine capabilities, marking each construct that depends on one, so that a substitution could assess its own gap rather than discover it. DOC-24 §5.1 establishes why the row-level constraint is disqualifying rather than preferable: without engine enforcement, correctness requires every query to *add* a tenant predicate and omission is a breach, whereas with it a query must *deliberately* escape enforcement to be wrong — a visible, reviewable act rather than an absence. The engine version is part of the decision because two required capabilities are version-dependent.

**Options considered.**

- **A. PostgreSQL 18 or later.** Row-level security with forced owner enforcement; declarative range, list, and hash partitioning with partition-wise joins; `jsonb` with expression and GIN indexing; array containment indexing; partial and expression indexes; deferrable constraint triggers; native `tsvector` full-text search evaluable in the same query as any other predicate; native time-ordered UUID generation from version 18. Costs: a single writer, so write scaling is vertical plus partitioning; no multi-primary across regions; connection cost requires an external pooler at the concurrency of DOC-01 §12.1's Large profile and above.
- **B. MySQL 8.4 or MariaDB.** Mature, widely operated, and the cheapest of the five to hire for in the Vietnam-first market of OQ-011. **Has no row-level security in any form.** The nearest equivalents are a `DEFINER`-rights view or an application-supplied predicate; neither is a policy the engine evaluates against a session-bound context, and neither has an equivalent of forced owner enforcement. Also lacks array containment indexing and declarative hash partitioning with partition-wise joins.
- **C. Microsoft SQL Server 2022.** Has genuine row-level security through security policies and inline predicate functions, so it clears the first constraint's read half. Lacks declarative hash partitioning, an array type with containment indexing, and a document type with expression indexing — three of DOC-04 §22.4's capabilities, two of which §22.4 marks as requiring significant redesign. Licence cost is borne by the customer in the single-tenant hosted and air-gapped topologies.
- **D. Oracle Database 23ai.** Virtual Private Database provides engine-evaluated row-level predicates and is the closest functional match to option A on the isolation constraint. Rejected on two grounds: licence cost in the topologies the customer operates, which `CON-LIC-001` makes a commercial constraint rather than a preference; and the bypass surface is the `EXEMPT ACCESS POLICY` system privilege rather than a per-table forced setting, which makes the bypass set harder to enumerate for `SEC-TEN-008` than a role attribute is.
- **E. CockroachDB or YugabyteDB.** PostgreSQL wire and dialect compatibility with horizontal write scaling and native multi-region residency pinning, which would serve DOC-24 §8 attractively. Both trail PostgreSQL on the specific constructs DOC-04 depends upon — partition-wise joins, deferrable constraint triggers, and expression-indexed document columns — and a distributed store makes the single-aggregate-per-transaction rule of `CON-PLT-019` more expensive rather than cheaper. CockroachDB additionally moved to a source-available licence in 2024, which `CON-LIC-001` makes a distribution problem for the topologies the customer operates.

**Decision.** Option A. **PostgreSQL 18 or later**, self-hostable, with managed PostgreSQL services acceptable as a deployment substrate provided they expose the capabilities verified below and permit the creation of non-superuser roles both with and without the bypass attribute. Managed services that do not permit the latter are excluded, because `OPS-DEP-009`'s credential separation is not implementable without it.

**Constraint verification — row-level security with forced owner enforcement (`CON-DAT-012`).**

| Element of the constraint | Mechanism | How it is demonstrated |
|---|---|---|
| Engine-evaluated rather than application-evaluated | `ALTER TABLE … ENABLE ROW LEVEL SECURITY` with a policy on every tenant-scoped table | A query issued by any client under `app_runtime`, including an interactive session outside the application, returns in-tenant rows only. Enforcement is a property of the table, not of the caller |
| Read enforcement | Policy `USING` clause comparing the row's tenant column to the session-bound context | A `SELECT` with no tenant predicate returns the caller's rows and no others |
| Write enforcement | Policy `WITH CHECK` clause covering `INSERT` and `UPDATE` | An insert naming a foreign tenant is rejected by the engine. This is the specific failure `CON-DAT-012` requires both clauses for: `USING` alone permits writing a row into another tenant, which is corruption rather than disclosure and is harder to detect |
| **Forced owner enforcement** | `ALTER TABLE … FORCE ROW LEVEL SECURITY` | Policies apply to the table's owning role. Without this setting the owner is exempt by default, which means the role that runs migrations and the role that runs the application are exempt whenever they coincide — the specific path `CON-DAT-012` closes |
| Session-bound context the client cannot supply | A namespaced parameter set with `SET LOCAL` inside the transaction and read by the policy; `SEC-TEN-004` forbids deriving it from any request parameter, header, path segment, or body field | A transaction that sets no context matches no rows and writes nothing, which is `SEC-TEN-005`'s fail-closed requirement expressed by the engine rather than by a code path |
| Enumerable, auditable bypass | The `BYPASSRLS` role attribute, granted to `migration_runner`, `integrity_verifier`, and `offboarding_executor` and withheld from `app_runtime` | The bypass set is a catalogue attribute, so `SEC-TEN-008`'s enumeration is a query against the database rather than a document that drifts. `OPS-DEP-009`'s audit event is emitted on use |
| Pooled connection safety | Transaction-scoped `SET LOCAL` rather than session-scoped `SET`, plus `DISCARD ALL` on return to the pool | `SEC-TEN-007`, `OPS-DEP-010`. A pooler operating in transaction mode cannot leak a `SET LOCAL` value into the next borrower, because the value dies with the transaction |

**The residual bypass, stated plainly.** Forced row-level security does not apply to a superuser, nor to any role holding `BYPASSRLS`. The constraint is therefore satisfied only in combination with `OPS-DEP-009`: `app_runtime` must be a non-superuser without the bypass attribute, and the three bypass credentials must be absent from every runtime environment reachable by application code. Neither half is sufficient alone — engine enforcement reached through a superuser application credential is not enforcement, and credential separation without engine enforcement is DOC-24 §5.1's rejected position. The verification above is a verification of the pair.

**Second verification — the remaining capabilities of DOC-04 §22.4.**

| Capability | Mechanism in the selected engine | Note |
|---|---|---|
| Array columns with containment indexing | `text[]` and `uuid[]` with GIN indexes supporting containment and overlap operators | Serves `scope_ancestor_path` and `scope_node_ids` (ADR-032), labels, and mention lists. §22.4 rates its absence as requiring a reconstructable closure instead |
| Typed document column with expression indexing | `jsonb` with expression indexes and GIN, including `jsonb_path_ops` for containment | Serves tenant custom attributes and the indexed-slot strategy of `CON-DAT-018` |
| Declarative partitioning with partition-wise joins | Range, list, and hash partitioning; partition-wise join and aggregate planning | Both planner settings are **disabled by default** and must be enabled in the reference configuration; the aligned `finding` / `finding_asset_impact` hash partitioning of `CON-DAT-024` degrades without them, which is a configuration defect that looks like a capacity problem |
| Full-text search combinable with a scope predicate in one query | `tsvector` column with a GIN index, combined with any other predicate in a single plan | The strongest constraint on search technology per §22.4, and the subject of ADR-051 |
| Partial and expression indexes | Supported | Serves operational queues, temporal current-row lookups, and attribute filters at a fraction of full-index size on the largest tables |
| Constraint triggers | `CREATE CONSTRAINT TRIGGER`, deferrable to transaction end | Serves the three engine-enforced guards, retaining the survive-a-defect property that domain-only enforcement loses |
| Time-ordered UUID generation | Native `uuidv7()` from version 18 | This is why version 18 is the floor rather than 16 or 17. §22.4 accepts application-side generation, so the native function is a simplification rather than a requirement; ADR-031 is satisfied either way |
| Zero-downtime schema evolution | Concurrent index build, `NOT VALID` constraint addition with concurrent validation, attach and detach of partitions | Serves the expand-migrate-contract requirement of `CON-DAT-033` and `OPS-DEP-029` |

**Consequences.** Positive: the disqualifying constraint is satisfied by an engine setting rather than by a convention, so `CON-DAT-012` is testable by a query and its regression is a failing test rather than an unnoticed drift; every construct DOC-04 marked as engine-dependent is available, so no schema section requires redesign; the licence permits the air-gapped and single-tenant hosted topologies without a cost the customer bears; ADR-051, ADR-053, and ADR-054 become available, removing three separate reimplementations of tenant isolation.

**Negative, and accepted.** Write throughput scales vertically plus by partitioning, not horizontally, so the Extra large profile of DOC-01 §12.1 — 150,000,000 audit events per month and 80,000,000 component entries — will require partition-level maintenance discipline and eventually a sharding decision that this ADR does not make. There is no multi-primary path, so a residency designation is a single-writer deployment and `OPS-DEP-004`'s separation per designation is also a separation of write capacity. Connection concurrency at the Large profile and above requires an external pooler, which is an additional component in the critical path and the component where `OPS-DEP-010` fails if configured in session mode rather than transaction mode. Vacuum and bloat management on the highest-churn tables is operational work that a managed service reduces but does not remove. Option E's native residency pinning is given up, so residency remains a deployment-topology concern rather than an engine concern.

**Gaps accepted (`OPS-DEP-002`).** None against the property list of DOC-15 §2 or the capability list of DOC-04 §22.4; every entry is met by a named mechanism above. Two operational conditions are recorded instead, because they are the conditions under which a met property stops being met: partition-wise join planning is off by default and must be asserted in the reference configuration test, and forced row-level security must be asserted per table by a test that enumerates tenant-scoped tables and fails on one lacking it — a table added without the setting is a silent hole, and DOC-16 owes that test.

**Compliance.** Verified by the isolation suite of DOC-16 asserting `CON-DAT-012` through every write path including bulk, import, and migration; by a schema conformance test enumerating tenant-scoped tables and asserting both `ENABLE` and `FORCE`; by a credential test asserting `app_runtime` holds neither superuser nor `BYPASSRLS`; and by `OPS-DEP-031`'s post-migration cross-tenant assertion.

**Revisit trigger.** Revisit if measured write throughput on the operational store exceeds 70% of the primary's sustained capacity at the deployment's profile after partitioning and read-replica offload, or if a residency designation requires multi-region write availability that a single primary cannot provide. Do not revisit on the basis of a candidate offering better aggregation or search performance — those are ADR-051 and ADR-053, which have their own triggers.

---

## ADR-050 — Java on the JVM with Gradle, contract-partitioned modules, and compile-blocking static analysis

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect

**Context.** DOC-02 §16, `CON-PLT-046`, DOC-15 §2. The application toolchain carries the second of ADR-042's disqualifying constraints. ADR-003 accepted a specific cost: module boundary discipline erodes unless tooling enforces it, and DOC-02 §7.1 states why review does not catch the erosion — each individual cross-boundary call is locally reasonable. `SEC-AUZ-050` requires the same for authorization patterns and states why it is a build gate rather than a review item: the role-name shortcut is faster, reads naturally, and is invisible until a customer's structure breaks it. Both controls are structural in DOC-26 §13.2's sense, and both collapse to code review on a toolchain that cannot express them.

**Options considered.**

- **A. Java 25 LTS or later, Gradle, Spring Boot with Spring Modulith, Error Prone, ArchUnit.** Verified below.
- **B. C# on .NET 10 or later, MSBuild, ASP.NET Core, Roslyn analyzers, NetArchTest.** **This option satisfies both disqualifying constraints.** Assembly separation with `internal` accessibility makes a cross-boundary reference a compile error; a Roslyn analyzer emitting at `DiagnosticSeverity.Error`, or promoted through `WarningsAsErrors`, makes an authorization-pattern violation a compile error; `packages.lock.json` gives deterministic restore. It is a genuine co-qualifier, not a courtesy entry.
- **C. Go.** **Also satisfies both constraints.** The `internal/` directory convention is enforced by the compiler rather than by a linter, so placing `domain` and `infrastructure` beneath `module/internal/` makes a cross-module reference to either a compile error; import cycles are rejected by the compiler natively, satisfying `CON-PLT-016` with no tooling at all; a custom `go/analysis` analyzer wired as a blocking pipeline step satisfies `SEC-AUZ-050`, which is written as a continuous-integration requirement.
- **D. TypeScript on Node.** Module boundary enforcement is available only through lint rules — workspace project boundaries, or an equivalent — which run beside compilation rather than within it. The compiler will compile a violating program, dynamic import defeats static module-graph resolution, and suppression is a comment rather than an annotated element. **Rejected on constraint 1**: the mechanism is advisory by construction, and `CON-PLT-013` requires a violation to fail the build rather than produce a warning.
- **E. Python.** No compile step exists to fail. Import-linter and equivalent tools are advisory layers over a language in which any module is reachable at runtime, and there is no accessibility mechanism to hide a package from another package. **Rejected on both constraints**, and therefore disqualified by `CON-PLT-046`.
- **F. Rust.** Satisfies both constraints on the strongest terms of any candidate: crate separation with `pub(crate)` makes boundary violation a compile error, and a custom compile-blocking lint is achievable. Rejected on secondary grounds recorded below, not on the constraints.

**Decision.** Option A. **Java 25 LTS or later on the JVM, built with Gradle, using Spring Boot with Spring Modulith for module verification, Error Prone for compile-blocking custom checks, and ArchUnit for bytecode-level structural assertions.**

**Constraint verification 1 — build-time module boundary enforcement (`CON-PLT-013` – `CON-PLT-017`).**

| Element of the constraint | Mechanism | How it is demonstrated |
|---|---|---|
| Cross-module imports restricted to published contract surfaces (`CON-PLT-013`) | Each module of DOC-02 §6.1 is **two Gradle subprojects**: `<module>-contract`, holding commands, queries, events, and DTOs, and `<module>-impl`, holding `domain`, `application`, and `infrastructure`. A module declares `implementation project(':<other>-contract')`. Nothing except the application assembly declares a dependency on any `-impl` | A type in another module's `domain` package **is not on the compile classpath**, so the compiler cannot resolve the symbol. The violation is a resolution failure, not a rule evaluation — there is no severity setting to downgrade and no suppression annotation to apply. This is the strongest available reading of "fail the build rather than produce a warning" |
| Prohibited dependency direction (`CON-PLT-014`) | Direction is expressed by which subproject declares the dependency; ArchUnit rules assert the DOC-03 §5.3 relationship patterns for cases a declaration cannot express, such as an event subscription that must not become a call | ArchUnit executes in the `check` task; a failure fails the build. Gradle's own resolution covers the coarse direction, ArchUnit the intent |
| No direct access to another module's persistence (`CON-PLT-015`) | Persistence types live in `-impl` and are therefore unreachable by the mechanism in row 1; ArchUnit additionally forbids persistence and JDBC types outside a module's `infrastructure` package | Compile failure for the cross-module case, ArchUnit for the intra-module case |
| Acyclic module graph (`CON-PLT-016`) | Gradle rejects a circular dependency between subprojects, failing at dependency resolution; ArchUnit slice rules assert freedom from cycles at package granularity within a subproject | The build fails before compilation. A cycle is not expressible in the project structure |
| Domain layer purity (`CON-PLT-017`) | ArchUnit: the `domain` package may not depend on `application`, `infrastructure`, the web framework, or persistence annotations | ArchUnit, run as a blocking gate per `OPS-DEP-026` |
| Module contract inventory | Spring Modulith's named-interface verification, recorded per module and asserted in the build | Makes a widening of the contract surface visible in the diff rather than invisible in the aggregate |

**Constraint verification 2 — static analysis of authorization patterns (`SEC-AUZ-050`, `SEC-AUZ-051`).**

| Element of the constraint | Mechanism | How it is demonstrated |
|---|---|---|
| Rejecting comparison of a role identifier — name, code, or identity — against a literal | A **custom Error Prone `BugChecker` at `ERROR` severity**. Error Prone runs as a compiler plugin over the javac AST, so a match is a compile error rather than a report a later stage may or may not read | A fixture containing the prohibited comparison fails compilation. `SEC-AUZ-050` requires continuous integration to enforce the rule; this places it one stage earlier, at the developer's compiler, which is where the shortcut is written |
| Rejecting data access that does not pass through the evaluation contract | **The primary mechanism is the type system, not the analyzer.** `CON-PLT-037` requires an authorization decision as an *input* to query construction; realized as a type whose only constructor is visible to the kernel package and which every repository query method requires as a parameter, a query cannot be written without one because the parameter cannot be produced elsewhere. The Error Prone check covers the residue — access constructed reflectively, or through a raw datasource obtained outside the gate of `CON-PLT-036` | The type-level control is not suppressible at all. The analyzer covers what the type system cannot see |
| Prohibiting direct cache and index client access (`CON-PLT-039`, `SEC-TEN-009`) | The same analyzer, extended with a banned-API check over the cache, search, queue, and idempotency clients, plus ArchUnit rules confining those clients to the kernel's infrastructure | Compile error at the call site; ArchUnit as the bytecode-level backstop |
| Visible, countable exemptions (`SEC-AUZ-051`) | Suppression requires `@SuppressWarnings("<CheckName>")` on the annotated element — greppable, countable, attributable to an element and an author, and reported as a per-build count | Satisfies `SEC-AUZ-051`'s requirement that the exemption count be visible and its growth trackable. A suppression is a diff, not a configuration change nobody reads |
| Deterministic dependency resolution | Gradle dependency locking with a committed lockfile, plus a dependency verification metadata file carrying checksums and signatures; dynamic and snapshot version selectors rejected by the build | Serves `SEC-SEC-058` and `OPS-DEP-028`'s pinning principle applied to library dependencies rather than only to images |

**Why not the Java Platform Module System.** JPMS would express the boundary in the language rather than in the build, which is superior in principle. It is not relied upon here because the runtime is a Spring Boot application assembled on the classpath, where JPMS delivers split-package conflicts and reflective-access exceptions in exchange for an enforcement property the Gradle subproject partition already provides at compile time. The partition is chosen because it delivers the same compile-time property with no runtime cost. This is recorded because a reviewer will ask, and because if the runtime later moves to the module path the boundary declaration becomes stronger at no design cost.

**Why option A over option B, stated honestly.** Options A and B are close on the constraints and neither is technically superior for this system. Java is selected on grounds that are ecosystem and market, not architectural: the SBOM, CycloneDX, SPDX, and vulnerability-intelligence libraries the platform must parse and match against are most mature on the JVM, which matters directly for DOC-22; Spring Modulith is purpose-built for the modular-monolith verification `CON-PLT-013` needs, with no direct .NET equivalent of the same specificity; and hiring depth in the Vietnam-first market of OQ-011 favours the JVM. **If a future team's composition inverts the hiring argument, option B remains a qualified candidate and this ADR should be revisited rather than defended.** Options C and F are rejected on the same class of secondary grounds: Go lacks the algebraic and sealed types that let DOC-03's invariant-heavy model be enforced by construction rather than by runtime checks, which is the opposite of this corpus's structural-over-procedural preference; Rust's enterprise library surface for the identity, workflow, and reporting concerns of DOC-05, DOC-09, and DOC-12 would require building what the JVM provides, and its compile-blocking custom lints need additional tooling outside the standard toolchain.

**Consequences.** Positive: both structural controls are compile-time or build-time properties with no advisory mode, so neither degrades to code review under delivery pressure — the specific cost ADR-003 accepted is now covered by a mechanism rather than by intent; making the authorization decision a required constructor parameter converts "remembering to check" into "unable to proceed without checking" at the type level, which no linter can achieve; the exemption surface is a countable set of annotations rather than an opaque configuration.

**Negative, and accepted.** The module partition doubles the subproject count, so the build graph is larger, incremental builds are more sensitive to contract changes, and a new module is more ceremony to create than a package would be — which is the friction that makes contract widening deliberate, but it is friction. Error Prone and ArchUnit rules are **code the platform owns and maintains**, so a rule that is deleted is a rule that no longer runs. JVM memory footprint is material for the match workers of `CON-PLT-008`, which hold an intelligence database resident and are already the memory-heavy unit of `OPS-DEP-005`. Startup time and warm-up affect the container replacement rate during a rolling upgrade under `OPS-DEP-029`. Choosing the JVM forecloses the single-binary distribution that would have simplified air-gapped delivery under `CON-DEP-001`.

**Gaps accepted (`OPS-DEP-002`).**

| Gap | Compensating control |
|---|---|
| Gradle subproject separation enforces **reachability, not intent**: a type deliberately moved into `-contract` widens the published surface with no build failure | Spring Modulith's named-interface verification plus a recorded contract inventory make the widening a reviewable diff; contract changes require review under `CON-PLT-011`'s ownership rules |
| ArchUnit and Error Prone rules are platform-owned code; deleting or weakening a rule is a code change that the rule cannot itself detect | Each rule is covered by a **meta-test asserting that a known-violating fixture fails**, so deleting the rule fails a test rather than silently passing the build. DOC-16 owes these fixtures under `TST-AUZ-001` |
| Error Prone analyses javac ASTs; reflection, method handles, and generated bytecode escape it | ArchUnit operates on compiled bytecode and covers the generated and reflective surface; the type-level control of `CON-PLT-037` is not escapable by either route |
| `SEC-AUZ-050`'s second clause — data access not passing through the evaluation contract — cannot be fully decided statically in any candidate language | The type-level mechanism above carries the enforcement; the analyzer is defence in depth rather than the control. Stated here so that a reviewer does not read the analyzer as complete |

**Compliance.** Verified by the structural suite of DOC-16: a fixture per boundary rule asserting build failure, a fixture per authorization check asserting compile failure, a per-build exemption count reported under `SEC-AUZ-051`, and the pipeline gates of DOC-15 §9.1 blocking on each.

**Revisit trigger.** Revisit if a required mechanism above is withdrawn from its tool — specifically, if Error Prone ceases to support custom checks at error severity, or if the build tool ceases to reject circular subproject dependencies — because each is a single point of failure for a disqualifying constraint. Revisit the language choice if team composition inverts the hiring argument recorded above, in which case option B is re-tested against the same two verifications. Do not revisit on general language preference.

---

## ADR-051 — Full-text search in the operational store; no separate search engine at v1

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect

**Context.** DOC-15 §2 rates the search decision near-disqualifying, and DOC-04 §22.4 calls the combinability of a text query with a scope predicate in one execution "the strongest constraint on search technology". The reason is DOC-24 §6.2 entry 3: a separate engine filtered after retrieval leaks result counts across scope boundaries, and `SEC-TEN-011` requires relevance scoring and result counts to be computed after tenant filtering. `CON-PLT-038` extends the same rule to scope: collection queries accept the scope predicate as part of query construction and must not support post-retrieval filtering as an authorization mechanism. Search is also load-bearing for ADR-028: `PRD-WRK-018` makes search the retrieval path for the institutional memory that migration preserves, and inaccessible history is history not migrated.

**Options considered.**

- **A. Native full-text search in the operational store.** A `tsvector` column maintained by trigger, GIN-indexed, combined with the scope and tenant predicates in a single plan. This is what DOC-04 §16.2 already specifies for `work_item`, so the schema was authored against this option.
- **B. A dedicated search engine with scope replicated into the index.** Best ranking quality. Requires the organization hierarchy, scope descriptors, and role assignments to be replicated into the index and kept current, so an authorization decision is served from a projection with its own lag — a stale scope in a search index is a live authorization bypass of exactly the kind `CON-PLT-040` exists to close, and the lag is invisible at the point of use.
- **C. A dedicated search engine filtered after retrieval.** Rejected outright: it is the mechanism DOC-24 §6.2 entry 3 names, it violates `SEC-TEN-011` and `CON-PLT-038`, and it moves unauthorized rows into application memory where they reach logs and error reports.
- **D. A dedicated engine queried only for identifiers, re-filtered and re-counted in the store.** Preserves correctness of counts by discarding the engine's count and ranking, which discards most of the reason to adopt it, while retaining a second stateful component and a second tenant-isolation implementation.

**Decision.** Option A. Full-text search is executed in the operational store, in the same query as the tenant and scope predicates.

**Constraint verification — combinability, pre-scoring filtering, tenant partitioning (`SEC-TEN-011`, `CON-PLT-038`, `PRD-WRK-018`).**

| Element of the constraint | Mechanism | How it is demonstrated |
|---|---|---|
| Scope predicate combinable with the text query in one execution | Both are predicates in one `WHERE` clause over one table; the planner combines the GIN text index with the scope indexes | A single plan, inspectable. There is no second retrieval to filter |
| Filtering before scoring | Ranking functions are applied to the rows surviving the predicates, because the predicates are evaluated by the same query | `SEC-TEN-011` is satisfied structurally: there is no execution stage at which an out-of-scope row has been scored |
| Result counts computed post-filter | The count is a count over the same predicated relation | No pre-filter count exists to disclose foreign volume |
| Tenant partitioning | Row-level security applies to the search query as to every other query, per ADR-049 | The isolation control is the same control, not a second implementation. A defect in the search path is a defect in a path already covered by the isolation suite |

**Consequences.** Positive: the near-disqualifying constraint is satisfied by construction rather than by discipline, and the platform avoids a second engine in which tenant isolation, scope resolution, and residency would each need separate implementation and separate verification; search results are transactionally consistent with the records they describe, so a work item is findable the moment it is committed, with no projection lag to reason about; one fewer stateful component in the air-gapped topology.

**Negative, and accepted.** Ranking quality is materially below a dedicated engine: the store's ranking functions are not a tuned relevance model, there is no learning-to-rank, and phrase, proximity, and field-weighting control is coarser. **Vietnamese has no built-in text search configuration in the selected engine**, which matters directly because Vietnamese is the first target locale under `NFR-INT-003` — the fallback is a simple dictionary with diacritic folding and trigram similarity for fuzzy matching, which yields no stemming and weaker recall on inflected queries. Index maintenance is write amplification on the platform's highest-churn tables, and GIN index updates are the specific cost. `NFR-WRK-002`'s targets of 1.0 s p95 at Medium and 2.0 s p95 at Large must be measured against the largest profile the deployment will reach rather than assumed, because a GIN search combined with selective scope predicates degrades non-linearly once the text predicate stops being the selective one. Search load competes with operational load in the same engine, which is the concentration recorded in the section preamble.

**Gaps accepted (`OPS-DEP-002`).**

| Gap against a required property | Compensating control |
|---|---|
| **Relevance ranking** is provided but is weaker than the property implies for a search-led interface | Ranking is supplemented with explicit sort options and structured filters in the interface of DOC-08 rather than relying on relevance alone; a scored-relevance uplift is reserved as an in-engine extension that preserves single-query evaluation, so the uplift does not reopen the count-leak question |
| Vietnamese linguistic support is absent from the engine's dictionaries | Diacritic folding plus trigram similarity as the v1 configuration, with the configuration named in the reference deployment so it is testable; a locale-specific dictionary is an additive change requiring an index rebuild, not a re-architecture |
| Search load is not isolated from operational load | Search executes against a read replica for queries that tolerate replica lag, and the `PROJECTION` work class of DOC-02 §12.1 does not carry search maintenance; the extraction trigger below is the escalation |

**Compliance.** Verified by the isolation suite asserting that a search issued in one tenant's context returns no foreign rows and no foreign count, by a scope test asserting that a search issued by a narrow-scope principal returns neither out-of-scope rows nor an out-of-scope count, and by `NFR-WRK-002` latency measurement at the deployment's profile.

**Revisit trigger.** Revisit if measured search latency exceeds `NFR-WRK-002` at the deployment's profile after index and query tuning, **or** if a tenant requires linguistic capability the engine's dictionaries cannot express. On revisit, option B is the successor and its precondition is explicit: scope must be evaluated in the store and the engine must be queried within an already-scoped identifier set, or the count-leak of DOC-24 §6.2 entry 3 returns with it.

---

## ADR-052 — Provider-agnostic secrets contract with a permissively licensed default; per-tenant namespace and hardware-backed key wrapping

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Principal Security Architect, Chief Software Architect

⚠️ **Working assumption (OQ-026):** this record adopts the question's working assumption — integration supported, with a platform-provided default for deployments lacking an enterprise vault — as the decision, and answers "both". It does not close OQ-026, whose owner is the Principal Security Architect: what remains open is whether the platform-provided default is offered commercially, because DOC-06 §18.2 records that a platform-provided store is itself a new asset of the highest value requiring its own design review. **The contract below is unaffected by that answer**, which is why the decision can be taken now; three credential paths in build block 1 depend on it.

**Context.** DOC-15 §2 and §7, DOC-06 §7, DOC-24 §6.2 entry 12, OQ-026. This is the platform's largest credential concentration and the third of the five highest-risk surfaces. Three paths depend on it: test account credentials, connector credentials under `PRD-CON-021`, and secret finding values under `PRD-VUL-019`. DOC-26 §3.3 identifies the connector credential store as the platform's highest-value asset. Key management is decided here rather than separately, because `OPS-DEP-021` and `OPS-DEP-022` place key-encryption-key custody and dual-controlled destruction in the same component boundary.

**Options considered.**

- **A. A single vendor product, named as a hard dependency.** Simplest to operate and to document. Rejected because the air-gapped topology and the enterprise buyer both make it wrong: a customer with an established enterprise vault will not adopt a second one, and a customer without connectivity cannot use a hosted one.
- **B. A provider-agnostic contract with a platform-provided default and supported integrations.** Chosen. The contract is the decision; the providers are configuration.
- **C. Platform-provided only.** Creates the highest-value asset in the platform's own trust boundary in every deployment, including those whose customer already operates a vault under stricter controls than the platform can claim.
- **D. Integration only, with no default.** Excludes deployments without an existing vault, which includes evaluation and the internal-first deployment of OQ-010.

**Decision.** Option B, with the following selections. **The contract**: a secrets provider interface exposing per-tenant namespace resolution, write, reference-based read, rotation with overlap, and destruction, with every operation audited by the platform independently of the provider's own audit. **The platform-provided default**: OpenBao, selected over HashiCorp Vault on licence grounds — Vault's source-available licence is a distribution problem for the topologies the customer operates, which `CON-LIC-001` makes a commercial constraint, and per-tenant namespace support is confined to Vault's commercial edition. **Supported integrations**: HashiCorp Vault, and the managed secret and key services of the major cloud providers, each behind the same contract. **Key management**: key-encryption keys held in a hardware security module or an equivalently protected key service through a PKCS#11 or cloud-KMS seal; per-tenant data encryption keys wrapped by a transit-style key rather than stored in plaintext, per the envelope model of DOC-24 §7.1.

**Constraint verification — the four required properties of DOC-15 §2.**

| Required property | Mechanism | Verified how |
|---|---|---|
| Per-tenant namespaces | A namespace per tenant where the provider offers one; otherwise **a key-value mount per tenant with an access policy scoped to that mount and an authentication role bound to the tenant context of DOC-24 §5.2**. In both forms the partition is the access policy, not a path convention, so a reference from one tenant does not resolve in another — DOC-24 §6.2 entry 12's stated failure | An adversarial test resolving a reference minted in tenant A under tenant B's credential, asserting denial by the provider rather than by the platform |
| Non-retrievable after entry | **Partially met; see gaps.** The platform exposes no read path for platform-held credentials, per ADR-047's absence-not-masking rule; for secret finding values, retrieval is permission-gated and each retrieval emits an audit event | A representation test asserting the field is absent, and an audit test asserting the reveal event |
| Rotation with overlap | Provider-native versioned secrets with both the outgoing and incoming version valid for a bounded window, so a rotation does not require a synchronized redeploy | A rotation test asserting continuity of connector operation across the window |
| Audit of access | Provider audit device plus the platform's own audit event under DOC-14, deliberately duplicated | Chain verification under `OPS-DEP-045`; the duplication exists because a provider operator and a platform operator are not the same party |
| Key custody (`OPS-DEP-021`) | Hardware-backed or equivalently protected seal for the key-encryption key; per-tenant data encryption keys wrapped, never stored in plaintext | A test asserting that a per-tenant key is unreadable without the seal, and that backups restored under a foreign tenant key yield unreadable ciphertext per `OPS-DEP-035` |
| Dual-controlled destruction (`OPS-DEP-022`) | **Partially met; see gaps.** Provider deletion gated by an explicit allow flag, with dual approval enforced by the platform's own separation-of-duties mechanism and recorded as an audited procedure | A procedure test asserting that a single principal cannot complete destruction |

**Consequences.** Positive: no deployment topology is excluded, and the customer who already operates a vault under controls stricter than the platform can claim keeps it; the licence of the default permits redistribution into the air-gapped and single-tenant hosted topologies without a cost the customer bears; cryptographic erasure at offboarding becomes demonstrable rather than asserted, because key destruction is the mechanism; the contract means the OQ-026 answer changes configuration and a provider adapter, not the platform.

**Negative, and accepted.** A provider-agnostic contract is the **lowest common denominator of the providers behind it** — a capability present in one provider and absent in another either goes unused or becomes a provider-specific branch, and the second option is how abstraction layers rot. Each supported provider is a distinct adapter with its own tests, its own failure modes, and its own audit format to normalize, so the integration count is a recurring maintenance cost. Operating the platform-provided default in air-gapped deployments transfers vault operations — unsealing, backup, and rotation — to the customer's operators, who may be less practised at it than the platform team, and an unsealed vault is a platform outage under `CON-PLT-043`'s degradation rules. Hardware-backed key custody adds a procurement dependency to the air-gapped topology.

**Gaps accepted (`OPS-DEP-002`).**

| Gap against a required property | Compensating control |
|---|---|
| **"Non-retrievable after entry" is not a property any candidate provides.** A key-value secret is readable by any principal holding a sufficient policy; the property is enforced above the provider, not by it | Enforced at the application layer as **absence of a read path** rather than as a masked response, per ADR-047 — the platform's own API has no operation returning a platform-held credential. For secret finding values, which `PRD-VUL-019` requires to be retrievable under permission, the reveal is permission-gated and audited per retrieval. **Recorded as an accepted risk**: a principal with direct provider access bypasses this, which is why provider credentials are themselves in the enumerated bypass set and why `OPS-DEP-043` routes the detection alert outside operator control |
| **Dual-controlled destruction is not provider-native** in any candidate | Dual control is enforced by the platform's separation-of-duties mechanism ahead of the provider call, with the provider's deletion-allowed flag as the second gate. The residual risk is a principal holding direct provider access, addressed as in the row above |
| Per-tenant namespace support varies by provider and edition; the mount-per-tenant fallback has a per-provider ceiling on mount count | The reference deployment records the ceiling for each supported provider, and a tenant count approaching it is a capacity condition under `OPS-DEP-048` rather than a discovery at provisioning time |

**Compliance.** Verified by the isolation suite's cross-tenant reference resolution test, by the credential concentration tests DOC-16 owes for the three dependent paths, by the offboarding test asserting cryptographic erasure, and by audit chain verification under `OPS-DEP-045`.

**Revisit trigger.** Revisit when OQ-026 is formally closed, to confirm the commercial disposition of the platform-provided default. Revisit the default selection if its licence changes or if per-tenant namespace support is withdrawn from the permissively licensed edition. Revisit the contract itself only if two supported providers cannot be served without provider-specific branches in the domain layer, which would indicate the abstraction is in the wrong place.

---

## ADR-053 — Read model in the operational engine on read replicas; no analytical engine at v1

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect

**Context.** DOC-02 §11 and §16, DOC-15 §2, DOC-01 §12.1. The required properties are aggregation performance at the reference profile volumes, horizontal read scaling, and rebuildability. ADR-012 puts dashboard compositions over one metrics read model with scope injected from the caller's authorization context, and ADR-046 materializes coverage and freshness alongside the measures they qualify — so the read side is a set of projection tables whose scope predicates are evaluated per query, not a pre-aggregated cube.

**Options considered.**

- **A. The operational engine, a dedicated projection schema, and streaming read replicas.** Projections are tables in the same engine; read traffic is served from replicas.
- **B. A columnar analytical engine.** Materially better aggregation performance at the Extra large profile. Introduces a second tenant-isolation implementation: row policies exist in the leading candidates but are a different mechanism, verified separately, and the per-tenant key encryption of DOC-24 §5.1 layer 3 does not extend to it. Also a second residency configuration and a second backup path under `OPS-DEP-036`.
- **C. A distributed extension of the operational engine, sharding projections across nodes.** Retains the dialect and the isolation mechanism while adding horizontal aggregation. Adds operational complexity and a second failure mode for a capacity problem the reference profile does not yet present.
- **D. Compute aggregates at query time from the operational tables, with no projections.** Rejected by ADR-046 and `CON-PLT-026`; also makes every dashboard a scan of the largest tables.

**Decision.** Option A. Projections live in a dedicated schema in the operational engine; read traffic for compositions is served from streaming read replicas. Option C is the named successor.

**Constraint verification — the three required properties.**

| Required property | Mechanism | Verified how |
|---|---|---|
| Aggregation performance at DOC-01 §12.1 volumes | Materialized projection tables per ADR-046, incrementally maintained by the `PROJECTION` work class, with an indexing strategy naming the composition query each index serves | Measured against `NFR-DSH-001` — 1.5 s p95 at Medium, 2.5 s p95 at Large — and `NFR-VUL-001`, at the deployment's profile with the concurrency figure the requirement states |
| Horizontal read scaling | Streaming read replicas; composition queries are read-only and tolerate bounded replica lag | Measured by adding a replica and observing composition throughput scale; the lag budget is `CON-PLT-027`'s |
| Rebuildable | Projections are derived, carry no authoritative state, and are rebuilt by replaying from the operational tables and the event record | A rebuild test asserting a projection reconstructed from scratch is identical to the incrementally maintained one — which is also the test that detects a projection that has silently acquired authoritative state |
| Tenant isolation on the read side | **Row-level security applies on a physical replica**, because the replica carries the same tables and the same policies | The read side inherits DOC-24 §5.1 layer 1 rather than reimplementing it. This is the decisive argument for option A over option B and is stated as such |

**Consequences.** Positive: the read side is covered by the same isolation control, the same residency topology, the same backup path, and the same operational runbooks as the write side, so the platform's highest-severity risk has one implementation rather than two; a projection rebuild is a transactional operation in a familiar engine; replica lag is the only new consistency concept, and `CON-PLT-027` already budgets it.

**Negative, and accepted.** Aggregation over 1,000,000 open findings, 80,000,000 component entries, and 150,000,000 audit events per month at the Extra large profile is **the weakest point of this decision**, and option A gets there only through materialization discipline: an aggregate not anticipated by a projection is a scan, and the interface of DOC-08 must not offer arbitrary aggregation the read model has not been built for. Read scaling is replica-based, so it scales reads and not aggregation of a single large query. Replica lag is visible to users as a dashboard that trails the record it describes, which interacts badly with product principle 1 — a stale coverage figure is exactly the confident output over stale data that `OPS-DEP-042` exists to make alertable. Projection schemas widen as ADR-046 requires coverage and freshness alongside every measure, and a coverage definition change requires backfill. The concentration recorded in the section preamble applies: replicas share the primary's write path.

**Gaps accepted (`OPS-DEP-002`).**

| Gap against a required property | Compensating control |
|---|---|
| Aggregation performance at Extra large is **assumed rather than demonstrated**, because OQ-015 has not been answered and no deployment has reached that profile | `OPS-DEP-024`'s structurally representative synthetic data at the Medium profile is extended with an Extra large generation path for the projection suite specifically, so the assumption is tested before a customer reaches it rather than after. The extraction trigger below is the escalation, and option C is named so the migration is designed rather than improvised |
| "Horizontal read scaling" is satisfied for read throughput but not for single-query aggregation width | Recorded plainly so that a reviewer does not read replica addition as a remedy for a slow aggregate. The remedy for a slow aggregate is a projection or option C |

**Compliance.** Verified by the projection rebuild test, by latency measurement against `NFR-DSH-001` and `NFR-VUL-001` at the deployment's profile, by the isolation suite executed against a replica as well as the primary, and by projection lag monitoring under `OPS-DEP-042`.

**Revisit trigger.** Revisit if a composition's measured latency exceeds its requirement at the deployment's profile after projection and index work, or if projection lag exceeds `CON-PLT-027`'s budget under normal load. Option C is tried before option B, because option C retains the single isolation implementation that is this decision's principal benefit.

---

## ADR-054 — Durable queue in the operational store, platform-owned; no message broker at v1

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect

**Context.** DOC-02 §12, DOC-15 §2. The required properties are at-least-once delivery, lease with visibility timeout, per-class isolation, and a tenant-bound payload. DOC-02 §12.1 defines five work classes with specific isolation obligations, and `CON-PLT-030` through `CON-PLT-034` add lease expiry with automatic reclamation, idempotency on a derived key, bounded retry with a terminal failure state, non-overlapping jittered scheduled work, and per-tenant concurrency caps. `SEC-TEN-006` requires an explicit tenant binding to enqueue and forbids a work item without one from executing, which DOC-24 §6.2 entry 2 identifies as the surface where cross-tenant iteration actually happens.

**Options considered.**

- **A. A queue owned by the platform, implemented as tables in the operational store with skip-locked claiming.** Enqueue participates in the same transaction as the aggregate write, so an event or job is never published for a transaction that rolled back, and the outbox problem does not arise. Row-level security applies to the queue tables, so the tenant binding of `SEC-TEN-006` is enforced by DOC-24 §5.1 layer 1 rather than by a code path.
- **B. A message broker with per-queue isolation.** Mature delivery semantics, better throughput, and operational tooling. Requires a transactional outbox anyway, because a broker publish cannot join the database transaction — so the store-backed table is built regardless and the broker is added beyond it. Tenant binding becomes a payload convention the broker does not enforce.
- **C. A log-based streaming platform.** Strong ordering and replay. Poorly matched to a work queue: per-message lease with visibility timeout, per-message retry with backoff, and a terminal failure state per item are not native, and per-class isolation with per-tenant concurrency caps under `CON-PLT-034` is not expressible in a partition model without a partition per tenant.
- **D. A managed cloud queue service.** Native visibility timeout and at-least-once delivery. Excluded by `CON-DEP-001`'s air-gapped topology, so it would be a second implementation rather than the implementation.
- **E. An existing store-backed queue extension.** Provides visibility timeout and archival. Rejected because the extension may be unavailable on a managed substrate or in an air-gapped image, and because the semantics `CON-PLT-030` through `CON-PLT-034` require — per-class isolation, per-tenant concurrency caps, terminal failure state, jitter — would be layered on top of it anyway, leaving a dependency that carries little of the work.

**Decision.** Option A. The durable queue is platform-owned tables in the operational store, claimed with a skip-locked select, one table or partition per work class of DOC-02 §12.1. Option B is the named successor and DOC-02 §15's extraction seam applies to it.

**Constraint verification — the four required properties and the five constraints.**

| Required property or constraint | Mechanism | Verified how |
|---|---|---|
| At-least-once delivery | A claimed item is deleted or marked complete only after the handler commits; a crash between claim and completion leaves the lease to expire | A crash-injection test asserting the item is re-delivered rather than lost |
| Lease with visibility timeout (`CON-PLT-031`) | A lease expiry timestamp written at claim; an expiry sweep reclaims items whose lease has passed. **Heartbeat-only liveness is not used**, per `CON-PLT-031` | A test terminating a worker abruptly — the normal failure mode per `CON-PLT-031`'s rationale — and asserting reclamation |
| Per-class isolation (`CON-PLT-030`, `PRD-SBM-004`) | One relation per work class with independent concurrency control, so `INTERACTIVE` work is never claimed behind `BATCH` work | A test enqueuing a portfolio sweep and then an interactive match run, asserting the interactive run is not delayed by it |
| Tenant-bound payload (`SEC-TEN-006`) | The tenant column is part of the queue table and carries a not-null constraint and a row-level policy, so **an item cannot be enqueued without a tenant binding and cannot be claimed outside the binding's context** | An adversarial test attempting to enqueue without context, asserting rejection by the engine. This is the property option B cannot provide: a broker payload convention is checked by code, a column constraint under a policy is checked by the store |
| Per-tenant concurrency caps (`CON-PLT-034`) | Claim query bounded by per-tenant in-flight count | A starvation test asserting one tenant's sweep does not occupy the fleet, per `SEC-TEN-035` |
| Idempotency and bounded retry (`CON-PLT-032`) | A derived idempotency key per item, tenant-namespaced per DOC-24 §6.2 entry 16; attempt count with backoff and a terminal failure state | A duplicate-delivery test asserting one effect, and a permanently-failing-item test asserting a terminal state rather than indefinite retry |
| Non-overlapping jittered scheduled work (`CON-PLT-033`) | A scheduler singleton with leader election per `OPS-DEP-007`, an overlap guard per schedule, and jitter applied at enqueue | A test asserting a long-running scheduled run is not overlapped by its successor |
| Transactional enqueue | Enqueue is an insert in the aggregate's transaction | A rollback test asserting no item is visible for a rolled-back transaction — the dual-write failure option B must solve with an outbox |

**Consequences.** Positive: the outbox problem does not exist, so an event published for a transaction that rolled back is not a failure mode the platform has; the tenant binding of `SEC-TEN-006` is enforced by the same layer as every other access, which addresses DOC-24 §6.2 entry 2 structurally; one fewer stateful component in every topology, including air-gapped; queue depth per class and per tenant — the signal `OPS-DEP-042` requires and `OPS-DEP-047` acts on — is a query rather than a broker metric to export; the semantics `CON-PLT-030` through `CON-PLT-034` require are implemented once, in the place where they are needed, rather than adapted onto a component that provides some of them.

**Negative, and accepted.** **The platform owns a queue implementation**, including its lease sweep, its backoff, its poison-item handling, and its tests — DOC-02 §12.1's own rationale for `CON-PLT-030` calls per-class isolation "the most common operational failure in self-built job orchestration", and this decision accepts that risk knowingly on the strength of the compensating tests above. Queue churn is write and vacuum load on the operational store, concentrated on the highest-frequency tables, and a queue table that is not partitioned and aggressively archived becomes a bloat incident. Claim latency has a polling floor, mitigated by a notification wake-up but not removed, which consumes part of `NFR-NTF-001`'s 60 s p95 dispatch budget. Throughput ceiling is materially below a broker's, and the `DISPATCH` and `PROJECTION` classes at the Extra large profile are where that ceiling arrives first. The concentration recorded in the section preamble applies: a queue outage and a store outage are the same outage.

**Gaps accepted (`OPS-DEP-002`).**

| Gap against a required property | Compensating control |
|---|---|
| Throughput at the Extra large profile is **assumed, not demonstrated** — OQ-015 is unanswered, and `DISPATCH` and `PROJECTION` volumes derive from finding and audit volumes that are provisional | Queue depth and claim latency per class are first-class signals under `OPS-DEP-042`; the extraction trigger below is expressed as a measured condition on them rather than as a volume estimate, so the trigger is decidable without the answer to OQ-015 |
| Delivery-semantic maturity is lower than a broker's, because the implementation is new code rather than exercised software | The crash-injection, duplicate-delivery, starvation, and reclamation tests above are owed by DOC-16 and are the substitute for maturity. Recorded plainly: they are a substitute, not an equivalent |
| Ordering guarantees are per-item, not per-stream | DOC-02 §10.2 already defines the ordering the platform requires; a capability that exceeds it is not a gap. Stated so a reviewer does not read option C's ordering as a lost property |

**Compliance.** Verified by the asynchronous work suite of DOC-16 covering each row of the verification table, by the isolation suite's enqueue-without-context test, and by queue depth and claim latency monitoring under `OPS-DEP-042`.

**Revisit trigger.** Revisit if measured claim latency at the 95th percentile exceeds the work class's budget under normal load after index and archival tuning, or if queue write volume becomes a measurable contributor to operational store contention. On revisit, option B is adopted **behind the transactional enqueue rather than in place of it** — the store-backed table becomes the outbox and the broker becomes the transport, which is why this decision does not foreclose it.

---

## ADR-055 — Valkey as the shared cache, with a two-tier topology; cross-tenant key isolation is an application-layer control

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect

**Context.** DOC-15 §2, DOC-02 §13.3, DOC-24 §6.2 entry 1. The required properties are key-prefix scoping, explicit invalidation, and no cross-tenant key collision by construction. The third is the one that matters: DOC-24 §6.2 places cache key construction first in the leakage inventory, and CLAUDE.md's risk list names it "the recurring failure point in otherwise correctly isolated systems". `CON-PLT-040` additionally requires authorization and scope caches to be invalidated on hierarchy change, assignment change, role change, and node archival within `NFR-SEC-002`'s bound — and DOC-02 §13.3 notes the trigger most likely to be missed is hierarchy change, because it originates in a different module.

**Options considered.**

- **A. Valkey, permissively licensed, with a two-tier topology.** An in-process cache for authorization and scope resolution, a shared Valkey instance for cross-replica cached values, and an invalidation broadcast between them.
- **B. Redis.** Functionally equivalent and better known. Its licence moved to a source-available model and subsequently to a strong copyleft option; either is a distribution problem for the self-hosted and air-gapped topologies the customer operates, which `CON-LIC-001` makes a commercial constraint rather than a preference.
- **C. Memcached.** Simpler and permissively licensed. No access-control list with key patterns, which removes the reserved uplift recorded below, and no server-side atomic primitives for the epoch-based prefix invalidation the design depends on.
- **D. An in-process cache only, with no shared tier.** Attractive because it removes a component and a network key namespace entirely. Rejected because invalidation across replicas within `NFR-SEC-002`'s bound needs a broadcast channel regardless, and because a stale scope cache is a live authorization bypass — the failure mode where a per-replica cache with independent expiry is worst.
- **E. The operational store as the cache.** Consistent with ADR-051, ADR-053, and ADR-054, but a cache exists to remove load from the store; putting it there inverts the purpose.

**Decision.** Option A. **Valkey 8 or later** as the shared cache. Two tiers: an in-process cache for authorization and scope resolution with a short ceiling on entry age, and Valkey for values shared across replicas, with invalidation broadcast over Valkey's publish-subscribe channel so `CON-PLT-040`'s triggers reach both tiers.

**Constraint verification — the three required properties.**

| Required property | Mechanism | Verified how |
|---|---|---|
| Key-prefix scoping | A mandatory key constructor per `CON-PLT-039` emitting a structural prefix of tenant, then scope discriminator where the value is scope-dependent, then resource | A construction test enumerating cached value types and asserting the prefix shape; a static analysis check rejecting direct client access, per ADR-050's banned-API mechanism |
| Explicit invalidation | Direct deletion for single keys; **an epoch counter embedded in the key prefix, incremented to invalidate a family**, rather than a pattern scan and delete. `CON-PLT-040`'s four triggers each increment the relevant epoch, and the increment is broadcast to the in-process tier | An invalidation test per trigger of `CON-PLT-040`, including hierarchy change originating in another module, asserting the effect within `NFR-SEC-002`'s bound. The epoch mechanism is chosen because a pattern scan over a large keyspace is an availability risk at the moment an authorization change must take effect |
| **No cross-tenant key collision by construction** | **Not provided by any candidate at the store layer.** See gaps | — |

**Consequences.** Positive: the licence permits redistribution into every topology without a cost the customer bears; the epoch mechanism makes family invalidation an O(1) write rather than a keyspace scan, which matters because `CON-PLT-040`'s triggers fire during reorganization — precisely when a scan would be most expensive; the two-tier topology serves the authorization hot path from process memory while keeping a single invalidation authority.

**Negative, and accepted.** Two tiers means two places a value can be stale and two invalidation paths to test, and the in-process tier's staleness is bounded by an age ceiling rather than by the broadcast, because a missed broadcast must not be indefinite. The epoch mechanism leaves superseded entries in the keyspace until they expire, so memory is traded for invalidation cost and the eviction policy becomes load-bearing. Valkey is less widely operated than option B, so operational familiarity is lower and third-party tooling thinner. A cache outage degrades latency across the platform, which `CON-PLT-043` requires be explicit rather than silent.

**Gaps accepted (`OPS-DEP-002`).**

| Gap against a required property | Compensating control |
|---|---|
| **"No cross-tenant key collision by construction" is not a property any candidate provides.** A cache keyspace is flat strings; the store cannot know that a key omitting the tenant is wrong. The property is enforced above the store in every candidate, and DOC-15 §2 states it as a required property of the decision rather than of the product | Four controls, none sufficient alone. **One**: `CON-PLT-039`'s mandatory key constructor, which cannot emit a key without a tenant because the tenant comes from the request-scoped context rather than from a parameter. **Two**: the static analysis check of ADR-050 rejecting direct cache client access, so the constructor cannot be bypassed without a compile error and a countable suppression. **Three**: `SEC-TEN-010`'s adversarial test for DOC-24 §6.2 entry 1, asserting no cache entry contains foreign tenant data. **Four**: cached values carrying `RESTRICTED` fields are encrypted with the tenant's data encryption key, so a key collision yields ciphertext the reader cannot decrypt — DOC-24 §5.1 layer 3 applied to the cache. **This is recorded as an accepted risk, not as a solved problem**, and it is the highest-severity accepted risk in these eight records |
| A server-layer mechanism exists but is not adopted in the multi-tenant topology: per-tenant access-control-list users restricting a connection to a tenant key pattern, which would make a foreign key a server-side rejection | Adopted in the **single-tenant hosted** topology, where one credential serves one tenant at no cost. Not adopted in multi-tenant, where an access-control-list user per tenant at the Large profile's tenant counts is incompatible with connection pooling — a pooled connection cannot be bound to a tenant without defeating pooling, which is the same tension `OPS-DEP-010` resolves for the store by resetting rather than by binding. **Reserved as the uplift** if a server-side mechanism becomes available that survives pooling |

**Compliance.** Verified by the construction test, the static analysis gate, the per-trigger invalidation tests of `CON-PLT-040`, and the adversarial cache test that `SEC-TEN-010` requires for DOC-24 §6.2 entry 1.

**Revisit trigger.** Revisit if a cache technology offers server-enforced key namespacing compatible with connection pooling, because that would convert this record's principal accepted risk into a structural control. Revisit the product selection if its licence changes.

---

## ADR-056 — S3-compatible object storage with per-tenant access policy and per-tenant supplied encryption key

**Status.** Accepted · **Date.** 2026-08-04 · **Deciders.** Chief Software Architect, Principal Security Architect

**Context.** DOC-15 §2 and §6, DOC-24 §6.2 entry 11, ADR-024. The required properties are tenant partitioning by **access policy, not path convention**, signed references with short expiry, and server-side encryption with supplied keys. The emphasis is DOC-15's own: DOC-24 §6.2 entry 11 names the failure as a shared bucket separated by path convention alone, where a traversal or a signed reference for the wrong object crosses tenants. The stored content is the fourth of the five highest-risk surfaces — evidence that is expected to be malicious and must remain retrievable — and `OPS-DEP-016` requires it served from a distinct origin, non-inline, with content-type enforcement.

**Options considered.**

- **A. The S3 API as the storage contract, with implementation by deployment.** A managed object store in hosted topologies; a self-hostable S3-compatible store in single-tenant hosted and air-gapped topologies. One client, one access-policy model, one encryption path.
- **B. A single named product across all topologies.** Simpler. Either excludes air-gapped operation or forces the hosted topologies onto a self-managed store, and `CON-DEP-001` does not permit the first.
- **C. Storing evidence in the operational store as large binary columns.** Consistent with the concentration of ADR-051, ADR-053, and ADR-054, and it would inherit row-level security. Rejected on two grounds: evidence volume in the operational store makes backup, restore, and vacuum materially worse for every other table, and `OPS-DEP-016`'s distinct-origin requirement for serving hostile content is not satisfiable from the application tier.
- **D. A filesystem-backed store with path-based separation.** The mechanism DOC-24 §6.2 entry 11 names as the failure. Rejected.

**Decision.** Option A. **The S3 API is the storage contract.** Tenant partition is a **bucket per tenant** where the implementation supports the deployment's tenant count, and otherwise a prefix per tenant with an access policy carrying a condition on that prefix; in both forms the partition is the policy, not the path. Each tenant's objects are encrypted with **that tenant's data encryption key supplied per request**, sourced from ADR-052. Implementations: a managed object store for hosted topologies, and **Ceph RADOS Gateway** for self-hosted and air-gapped topologies, selected over MinIO on `CON-LIC-001` grounds — MinIO's licence and the withdrawal of functionality from its freely available edition make it a redistribution and support risk for a store the customer operates.

**Constraint verification — the three required properties.**

| Required property | Mechanism | Verified how |
|---|---|---|
| **Tenant partitioning by access policy, not path convention** | Bucket per tenant with a bucket policy naming only the tenant's access identity, or a prefix condition in the policy attached to a per-tenant identity. The application holds a per-tenant credential or assumes a per-tenant role; **a request for a foreign object is denied by the store, not by the platform** | An adversarial test issuing a well-formed request for another tenant's object under the first tenant's credential, asserting denial by the store. A path-traversal test asserting the same, since traversal is the mechanism DOC-24 §6.2 entry 11 names |
| Signed references with short expiry | Pre-signed URLs with an expiry bounded well below the session lifetime, minted per request, bound to the object and the method | An expiry test, and a test asserting a reference minted for one object does not serve another |
| **Server-side encryption with supplied keys** | Per-request supplied encryption key, the key being the tenant's data encryption key from ADR-052. The store does not retain the key | A cross-tenant read test asserting that a request succeeding at the policy layer — which is the case the policy is meant to stop and this layer exists in case it does not — still yields a decryption failure rather than plaintext. This is DOC-24 §5.1 layer 3 applied to object storage, and it is why supplied keys rather than store-managed keys are a required property |
| Distinct origin, non-inline, content-type enforced (`OPS-DEP-016`) | Objects served from a hostname distinct from the API and web tiers, with content disposition and content type set at storage time and enforced at serve time | A test asserting stored active content is not served inline and not served from the application origin |

**Consequences.** Positive: two independent controls stand between a tenant and another tenant's evidence — the store's access policy and the tenant's encryption key — and the second holds if the first is misconfigured, which is the realistic failure; the same client and the same policy model serve every topology, so `OPS-DEP-003`'s single artifact with no divergent code paths is preserved; cryptographic erasure at offboarding covers stored evidence through the same key destruction as everything else; the licence of the self-hosted implementation permits the air-gapped topology without a cost the customer bears.

**Negative, and accepted.** Supplied-key encryption means **the platform presents the key on every read and every write**, so the key is in application memory more often than a store-managed key would be, and `OPS-DEP-020`'s bounded-lifetime requirement applies to it in a hot path. Key rotation requires re-encrypting objects by copy rather than by re-wrapping a store-held key, which is a bulk operation over a tenant's entire evidence set. Supplied-key encryption is incompatible with some store-side functionality — server-side integrity reporting and certain replication paths behave differently or not at all — so features that appear available are not. A bucket per tenant meets an implementation-specific ceiling on bucket count, which is why the prefix form is retained rather than removed. Ceph is operationally heavier than the alternative rejected on licence grounds, and that weight falls on the customer's operators in the air-gapped topology. Two implementations means two sets of policy-behaviour tests, because policy evaluation differs in detail between S3-compatible stores even where the API matches.

**Gaps accepted (`OPS-DEP-002`).**

| Gap against a required property | Compensating control |
|---|---|
| The bucket-per-tenant form has an implementation-specific ceiling on bucket count, above which the prefix-with-policy-condition form is used — **a weaker mechanism**, because it depends on correct condition authoring rather than on a bucket boundary | The policy is generated from the tenant record rather than authored per tenant, so a condition cannot be omitted for one tenant; the adversarial cross-tenant object test runs against both forms; the per-tenant supplied encryption key is unchanged between the forms, so layer 3 is identical either way. The deployment records which form it uses, per `OPS-DEP-048` |
| S3-compatible implementations differ in policy-condition semantics and in supplied-key support detail, so "the S3 API as the contract" is a contract with per-implementation variance | Each supported implementation is a named configuration with its own conformance run of the four verification tests above, executed in the pipeline. An implementation failing any of the four is not a supported implementation — the same disqualifying posture ADR-042 applies to the store and the toolchain, applied here to the storage substrate |

**Compliance.** Verified by the cross-tenant object test, the traversal test, the signed-reference expiry and binding tests, the wrong-key decryption test, and the origin and disposition tests of `OPS-DEP-016`, each run per supported implementation.

**Revisit trigger.** Revisit if an implementation withdraws supplied-key encryption, because the second of the two independent controls would be lost and the decision would rest on policy correctness alone. Revisit the self-hosted implementation selection if its licence changes or if a lighter permissively licensed store demonstrates all four verification rows.

---

## Records owed

Four decisions indexed in DOC-00 Appendix B are not yet expanded here because they concern the documentation corpus rather than the product, and are fully stated in DOC-00 itself:

| ID | Decision | Where stated |
|---|---|---|
| ADR-008 | Documentation corpus of 26 documents with permanent non-reusable numbering | DOC-00 §3.1 |
| ADR-014 | Vulnerability re-evaluation by re-matching stored SBOMs | Absorbed into ADR-013; see DOC-01 §10.8.6 |
| ADR-015 | Interactive and batch queue class isolation | DOC-01 `PRD-SBM-004` |
| ADR-025 | Repository URL is a reference label, not an integration point | Absorbed into ADR-024 |

## ADR-057 — The JDK HTTP server as the application tier's HTTP runtime, behind the ingress that terminates TLS

**Status.** Accepted · 2026-08-05

**Context.** DOC-15 §2 deferred eight technology selections and ADR-049 through ADR-056 resolved them. **The HTTP runtime is not among the eight.** It went unrecorded because DOC-02 §16 treated the application tier as a property of the toolchain, and ADR-050 selected the toolchain without selecting how a request reaches a handler. The omission surfaced when the application tier was built: there was no decision to implement.

DOC-15 §3.1 places an ingress in front of the application tier that terminates TLS, applies the WAF, and assigns the rate limit class. `OPS-DEP-017` then constrains it further: "Ingress MUST assign the rate limit class and MUST NOT make authorization decisions", because "a gateway making partial decisions creates a second enforcement point that will diverge from the first". So the runtime being chosen here speaks plain HTTP on an internal network and is never the TLS endpoint.

ADR-036 is the binding constraint on the choice. The seven annotation classes are "framework properties, not per-operation code, so an operation inherits them and cannot omit one". A runtime whose own request pipeline is the natural place to express cross-cutting behaviour competes with that: the platform then has two frameworks disagreeing about where the scope re-validation lives, and `PRD-API-019`'s guarantee — that an operation cannot ship without its class — degrades to a convention about which one to use.

**Options considered.**

| Option | Rejected because |
|---|---|
| A full application framework with dependency injection, its own filter chain, and annotation-driven routing | It supplies a second framework for exactly the concerns ADR-036 assigns to ours. The failure is not that it cannot be made to work; it is that an operation would then have two places to declare its security characteristics and the weaker declaration would sometimes win. Also the largest transitive dependency surface in the platform, on a component holding the group's complete exploitable attack surface |
| A lightweight embedded HTTP framework | Smaller, and the objection above still holds in reduced form. Adds a dependency whose release cadence the platform then tracks, for routing and body handling that `OperationRegistry` and `RequestValidation` already do |
| A reactive or virtual-thread-native server toolkit | The throughput ceiling it raises is not the platform's constraint. `NFR-WRK-001` budgets 700 ms at p95 for work item retrieval and the operational store is the bound, per ADR-049's concentration note. Selecting for a bottleneck that is not the bottleneck buys an unfamiliar programming model |
| **The JDK's built-in HTTP server (`com.sun.net.httpserver`), with the platform's own dispatcher above it** | **Selected** |

**Decision.** The application tier serves HTTP with the JDK's built-in server. Routing, class enforcement, body validation, tenant establishment and serialization are the platform's own dispatcher, which is the single place `PRD-API-019` is enforced.

**Constraint verification.**

| Required property | How it is satisfied | Evidence |
|---|---|---|
| An operation cannot be dispatched without an annotation class | `OperationRegistry.resolve` returns empty for an unregistered path and the dispatcher treats that as a routing failure, so there is no path from a request to a handler that does not pass a registered class | `PRD-API-019`, ADR-036 |
| No second enforcement point | The runtime performs no authorization, no filtering, and no content negotiation. It supplies a socket, a request line, headers, and a body stream | `OPS-DEP-017`, `CON-PLT-009` |
| Deterministic dependency resolution | It is part of the JDK selected by ADR-050. There is no coordinate to pin, no advisory feed to track, and nothing to download at deploy time — which DOC-15 §14 requires of the air-gapped topology | ADR-050, `OPS-DEP-028` |
| Runs behind a TLS-terminating ingress | DOC-15 §3.1's topology. The runtime is never the TLS endpoint and is never exposed directly | `OPS-DEP-016`, `OPS-DEP-017` |

**Gaps accepted.** Recorded per `OPS-DEP-002` rather than absorbed.

| Gap | Compensating control |
|---|---|
| No HTTP/2 or HTTP/3 | The ingress terminates the client protocol. The internal hop is HTTP/1.1 on a private network, where multiplexing buys little against a connection-pooled application tier |
| No built-in request timeout, body size limit, or slow-loris protection | `RequestValidation.MAX_BODY_BYTES` bounds the body in the dispatcher, and the ingress owns connection-level protection. **This is a real gap**: a deployment that exposes the application tier directly loses both, which is why `OPS-DEP-016` and the topology of DOC-15 §3.1 are prerequisites of this decision rather than context for it |
| No connection metrics out of the box | DOC-15 §12.1 requires per-signal instrumentation, which the dispatcher emits. A runtime-supplied metric would have been cheaper |
| The API is marked internal in the JDK's module system | It is a supported, exported package of `jdk.httpserver` and has been since Java 6. The risk is API churn, not removal, and the dispatcher is the only caller |

**Consequences.** Positive: one framework governs the security characteristics of an operation, so `PRD-API-019` is a property of the code path rather than a convention; the dependency surface of the tier holding the group's exploitable attack surface is the JDK itself; the air-gapped topology needs no registry access for the HTTP layer. Negative: routing, content negotiation, and body parsing are the platform's code and therefore the platform's defects — a framework would have brought tested implementations of all three, and this record is the argument that the trade is worth it only because ADR-036 already required us to own the layer above them. Neutral: migrating to a framework later means replacing the dispatcher's transport adapter, not its enforcement, because the two are separate types.

**Revisit trigger.** Revisit if the application tier must terminate TLS directly in any supported topology, if a measured p95 at the Medium profile shows the HTTP layer rather than the operational store as the bound, or if streaming request or response bodies become a requirement — the third being the most likely, since export at portfolio scale is already in scope.

---

## ADR-058 — Server-rendered HTML from the application tier, with no build step and no client framework

**Status.** Accepted · 2026-08-05

**Context.** DOC-15 §2 deferred eight technology selections; ADR-049 to ADR-056 resolved them and ADR-057 added the HTTP runtime the eight had not covered. **The interface technology was not among any of them.** DOC-08 specifies the design language, the states, the honesty surfaces, and the accessibility and internationalization obligations, and says nothing about what renders them — which is correct for a guideline and leaves the decision unmade.

What DOC-08 does constrain is unusually specific for an interface, and the constraints are not the ones a framework choice is normally made on:

- `INT-UIX-001` — WCAG 2.2 Level AA, "verified by automated and manual testing, with conformance documented **per success criterion**"
- `PRD-UIX-013` — "Every action available by pointer MUST be available by keyboard. A pointer-only capability MUST NOT exist"
- `INT-UIX-008` — every string externalized with ICU formatting; **string concatenation to build a sentence must not be used**
- `INT-UIX-009` — pseudo-localization must pass "without layout failure, string truncation, or untranslated string leakage"
- `PRD-UIX-022` — an unmeasured value must never render as zero, which DOC-08 §9 makes a presentation *state* rather than a value

Every one of those is a property of the markup that reaches the browser. None of them is made easier by a component framework, and two are made harder: a framework's synthetic event layer is where pointer-only handlers appear, and its templating is where a concatenated sentence stops looking like one.

DOC-15 §14 adds a constraint the topology already imposed: "dependency updates included in the artifact bundle; no registry access at deploy time". A front-end toolchain is a second dependency ecosystem with its own registry, lockfile, and advisory feed, in the tier serving the group's complete exploitable attack surface.

**Options considered.**

| Option | Rejected because |
|---|---|
| A single-page application with a component framework and a bundler | A second dependency ecosystem and a second build, for a tier whose hard requirements are semantic markup, keyboard operability and translatable strings. It also splits rendering across two languages, so `PRD-UIX-022`'s rule — unmeasured is a state, never a numeral — would need enforcing twice, and the second enforcement is the one that decays |
| A server-rendered framework with a template language | Closer, and still a template language in which `{{a}} {{b}}` is a concatenated sentence that no linter recognises as one. The dependency cost is smaller than an SPA's and the benefit over plain rendering is mostly ergonomic |
| A hypermedia library driving partial updates over HTML fragments | Attractive: it keeps rendering in one place and adds interactivity without a build. Rejected for v1 on dependency grounds only, and it is the natural first step if the revisit trigger fires |
| **Server-rendered HTML from the application tier, no build step, no client dependency, progressive enhancement with a small amount of first-party script** | **Selected** |

**Decision.** The interface is HTML rendered by the application tier. Strings come from resource bundles through ICU `MessageFormat`. Design tokens are emitted as CSS custom properties, one set per mode. Interactive behaviour that genuinely needs script — the command interface, list keyboard navigation — is first-party, loaded as a plain file, and every capability it provides also exists without it.

**Constraint verification.**

| Required property | How it is satisfied | Evidence |
|---|---|---|
| Every pointer action available by keyboard | Actions are `<a>` and `<button>` elements inside forms. A pointer-only capability requires a click handler on a non-interactive element, and there is no element type in the renderer that produces one | `PRD-UIX-013`, `INT-UIX-003` |
| Strings externalized, no concatenated sentences | Every user-facing string is a bundle key formatted through ICU. The renderer takes message keys, not text, so a concatenated sentence has nowhere to be written | `INT-UIX-008` |
| Pseudo-localization passes | A pseudo-locale bundle is generated from the source bundle and asserted in the build; an unexternalized string appears untransformed and fails the assertion | `INT-UIX-009` |
| Both themes independently contrast-checked | Tokens are declared per mode with no mode derived from another, and contrast is computed in the build rather than asserted by eye | `PRD-UIX-006`, `PRD-UIX-008` |
| Unmeasured never renders as a numeral | The renderer accepts a `PresentationState` or a figure, never both, and `UNMEASURED` has no numeral form | `PRD-UIX-022` |
| No registry access at deploy time | No client dependency, no bundler, no lockfile beyond the JVM's | DOC-15 §14, `OPS-DEP-028` |

**Gaps accepted.** Recorded per `OPS-DEP-002`.

| Gap | Compensating control |
|---|---|
| Full page loads rather than in-place transitions | DOC-08 §13's budgets are the measure. Server-rendered pages of bounded size over a local network meet them at the profiles considered; if a measured budget fails, the hypermedia option above is the smallest change that addresses it |
| Rich interactions — drag reordering, live filtering, virtualized long lists — are absent | Each has a keyboard-and-form equivalent, which `PRD-UIX-013` requires to exist anyway. The absence is a loss of polish, not of capability |
| No component library, so every component is first-party and untested by anyone else | DOC-08 §8 specifies the components in enough detail that a library would have been adapted rather than adopted. The cost is real and is the main one accepted here |
| Right-to-left support (`INT-UIX-011`) is structural only — logical properties and `dir` — and unverified against a real RTL locale | Recorded as unverified rather than claimed. Verification needs an RTL locale and a reviewer who reads it |

**Consequences.** Positive: one language renders the interface, so `PRD-UIX-022` and the honesty surfaces are enforced where the markup is produced rather than in two places; the deployable artifact has no second dependency ecosystem; the air-gapped topology needs nothing added. Negative: interaction polish is limited, and every component is ours to build and to get wrong — this is the cost, and it is larger than the dependency saving in developer time. Neutral: adopting a hypermedia layer later changes how fragments are requested, not how they are rendered.

**Revisit trigger.** Revisit if a measured interaction budget from DOC-08 §13 fails at the Medium profile, if a surface requires genuinely continuous interaction that forms cannot express, or if the accessibility conformance record shows first-party components failing criteria that a maintained library satisfies.

---

## ADR-059 — Local password and TOTP authentication for v1, narrowing ADR-004 rather than satisfying it

**Status.** Accepted · 2026-08-05 · **Narrows ADR-004**

**Context.** ADR-004 decided "OIDC/OAuth2 for humans; sender-constrained credentials for services; signed requests with replay protection for legacy CI. **No bearer API keys**." The product owner has specified that human authentication is username or email, a password, and a second factor — not a redirect to an external identity provider.

**These are not the same decision, and this record exists so that is not obscured.** An implementation that shipped local passwords while leaving ADR-004 as the stated decision would leave a reviewer believing an identity provider is in the trust path when it is not. ADR-004's index entry now points here.

What ADR-004 was actually protecting, read from its consequences rather than its title, is three properties: no long-lived bearer secret that works from anywhere; a second factor that is not optional; and credential handling the platform does not have to get right by itself. The first two are achievable locally. **The third is genuinely given up**, and that is the cost recorded below.

⚠ **Working assumption (OQ-010):** internal deployment first. That is what makes this acceptable for v1: the initial tenant is the operator's own organization, where the platform is the identity source rather than one relying party among many. A commercial tenant with an existing identity provider needs federation, and the revisit trigger names it.

**Options considered.**

| Option | Rejected because |
|---|---|
| Hold ADR-004 and require OIDC before any human can sign in | Correct in a vacuum and wrong here: it makes the platform undeployable until an identity provider is configured, and the first deployment is internal (OQ-010). It also puts the login experience of the product outside the product, which the owner has explicitly declined |
| Local password only, MFA optional per user | `SEC-SEC-003` and `PRD-IAM-002` permit a tenant policy requiring MFA; the owner requires enrolment after first sign-in. Optional MFA on a platform holding the group's exploitable attack surface is the credential-stuffing exposure `SEC-SEC-006` is written against |
| Local password with SMS or email second factor | Email is the reset channel; using it as the second factor collapses two independent factors into one. SMS is interceptable and its failure mode is silent |
| **Local password with TOTP, mandatory enrolment, and federation as an added path later** | **Selected** |

**Decision.** Human authentication is a local credential — username or email plus a password — followed by a TOTP second factor whose enrolment is forced before the principal can reach any authenticated surface. Federation is added later as an additional path, not as a replacement, so a tenant may bring an identity provider without the platform losing the ability to authenticate its own operators.

Service authentication is unchanged: ADR-004's sender-constrained credential still governs it, and **no bearer API key is introduced by this record**.

**Constraint verification.**

| Required property | How it is satisfied | Evidence |
|---|---|---|
| Memory-hard credential storage, per-credential salt, parameters stored alongside | Argon2id with the parameters persisted per credential, so a later cost increase re-hashes on next sign-in without invalidating existing credentials | `SEC-SEC-014` |
| A second factor that cannot be declined | Enrolment is a state of the principal, not a setting: an un-enrolled principal is redirected to enrolment and every other authenticated route refuses | `SEC-SEC-003`, `PRD-IAM-002` |
| Throttling that degrades an attacker without letting them lock a named account | Progressive delay keyed on the attempt, and a risk challenge — never an account disable, which would hand an attacker a denial-of-service against any named principal | `SEC-SEC-005` |
| Breached-credential checking at set and at authentication | Checked against a local corpus at both points. ⚠ The corpus shipped is a small illustrative set; a deployment loads a full one, and the check is a no-op that reports itself rather than a silent pass | `SEC-SEC-006` |
| Session identifiers with ≥128 bits of entropy, encoding nothing, regenerated on privilege change | Generated from a CSPRNG, stored hashed, and rotated when the second factor completes — which is a privilege change | `SEC-SEC-009` |
| Absolute and idle lifetimes, product maximum 12 hours absolute | Both stored per session and enforced on every request; the tenant policy is bounded by the product maximum | `SEC-SEC-010` |
| Reset that does not disclose whether a principal exists | The reset request responds identically for a known and an unknown identifier, and the token is single-use, short-lived, hashed at rest, and invalidates every session on use | `SEC-SEC-016` |
| Every authentication event, including failures, audited with source context | Written to the audit path with the outcome and the source, including the failures — which are the events that matter for `SEC-PLT-003`'s enumeration detection | `PRD-IAM-012` |

**Gaps accepted.** Recorded per `OPS-DEP-002`.

| Gap | Compensating control |
|---|---|
| **The platform is now a credential holder.** ADR-004's third property is given up: password hashes, TOTP secrets and reset tokens live here, in a system whose own threat model calls it a higher-value target than most systems it protects | Argon2id at a tuned cost, TOTP secrets encrypted at rest under the tenant key, reset tokens stored hashed, and every one of these on the audited path. None of it is as good as not holding the credential |
| No federation, so a commercial tenant cannot bring its identity provider | Named in the revisit trigger. The credential model is deliberately additive rather than exclusive, so federation is a second path and not a migration |
| Breached-credential corpus is illustrative rather than complete | The check reports its own coverage rather than passing silently. A deployment that has not loaded a corpus can see that it has not |
| No WebAuthn, so the second factor is phishable | TOTP is a real second factor and a phishable one. WebAuthn is the upgrade this record does not make, and an operator with a hardware key gains nothing yet |

**Consequences.** Positive: the product is deployable without an identity provider, which the internal-first assumption requires; MFA is mandatory by construction rather than by policy adherence; the sign-in experience is inside the product where its design can be held to DOC-08. Negative: the platform holds credentials it previously would not have, and every one of `SEC-SEC-005`, `-006`, `-009`, `-010`, `-014` and `-016` becomes ours to implement correctly rather than an identity provider's — this is the whole cost, and it is larger than the deployment convenience it buys. Neutral: adding federation later touches the credential resolution path and nothing above it.

**Revisit trigger.** Revisit when the first commercial tenant with an existing identity provider is onboarded, or when a phishing-resistant factor becomes a customer requirement. Do not revisit in order to remove the local path: the operator's own break-glass access cannot depend on an external provider being reachable.

---

## ADR-060 — A second automated ingestion path: SARIF scan reports, alongside the SBOM push

**Status.** Accepted · **Date.** 2026-08-14 · **Deciders.** Chief Software Architect, Principal Security Architect

**Context.** ADR-023 made the SBOM push API "the only automated ingestion path in v1". That was right when it was written and stopped being right for a reason visible in the product: the platform has a CI/CD findings surface, and it had no way to be populated. `finding.source_import_session_id` had existed since V006 and pointed at nothing, because there was no session table and no parser; the dashboard's one honest predicate — `source_import_session_id IS NOT NULL` — filtered a population no API could create. Meanwhile the group's static analysis output lived in pipeline logs, which is to say nowhere: not deduplicated, not aged, not attributable to an asset, and invisible to every coverage figure the platform reports.

ADR-024 forbids the platform from fetching source, so it cannot run these scanners itself. The results exist only because somebody else ran them, and the only way they reach the platform is a submission.

Four tools are in the group's toolchain: semgrep, mobsfscan, nuclei and CodeQL. All four emit SARIF 2.1.0. SonarQube does not and is explicitly out of scope for this decision.

**Options considered.**

- **A. Keep ADR-023 as written.** Scan results stay outside the platform; the CI/CD surface stays empty and honest about being empty.
- **B. Extend the SBOM push endpoint to accept scan reports.** One door, one credential, one permission.
- **C. A second endpoint with its own permission, over a registered parser.**
- **D. A platform-issued CLI that wraps the scanners.**

**Decision.** Option C. `POST /api/v1/finding-imports`, class F, permission `ing.findings.import`, over one registered SARIF 2.1.0 parser.

**Why not the others.** Option A leaves the product with a dashboard that cannot be populated, which is worse than not having the dashboard: an empty surface reads as an empty estate. Option B was rejected on authorization grounds and they are the whole argument — a bill of materials is a list of components a build declared, while a scan report carries file paths, code snippets and rule identities, so it is the higher-value document and its submitter needs the higher bar. Sharing `sbm.sbom.submit` would mean that granting a pipeline the right to declare its dependencies also granted it the right to file findings against the repository, and a tenant must be able to grant one without the other. Option D requires the platform to execute tooling over code, which ADR-024 forbids.

**Consequences.**

Positive: the CI/CD surface has a population; scan results are deduplicated against everything else by the identity scheme of DOC-03 §10.2 rather than living in a second regime; a weakness that comes back after somebody closed it becomes visible as a recurrence instead of a new row; and one parser covers four tools, so a fifth SARIF-emitting tool costs a fixture rather than a code path.

Negative, and this is the cost ADR-023 was protecting against: there are now two automated doors, so there are two places a credential can leak into and two endpoints whose submission health must be watched. `PRD-SBM-024`'s per-credential health becomes load-bearing for both. The parser is a hardened worker over attacker-influenced input — a scan report legitimately contains attacker-authored strings, which is the platform's fifth-highest-risk surface — and its declared limits are the only thing bounding a hostile document.

Neutral: target resolution is shared with the SBOM door rather than reimplemented, so the two cannot disagree about which asset a repository name means (ADR-011).

**What this decision does NOT do.** It does not admit runtime findings. nuclei reports URLs rather than files, and the RUNTIME identity class of DOC-03 §10.2 declares `parameter_name` as an identity input that a template match against a URL does not have — while `PRD-ING-021` forbids substituting a value the source did not supply. So a SARIF result whose location is an `http`/`https` URI is held in quarantine with the reason named, and ingesting it needs a further decision recorded as `OQ-028` rather than a guess made in code. Two of the four named tools are therefore covered for their code findings, a third (CodeQL) fully, and nuclei not at all until that question is answered.

**Compliance.** Verified through `PRD-ING-024`, `PRD-ING-027`, `PRD-ING-040` and `PRD-ING-041`, and the per-tool fixtures `TST-ING-001` requires.

**Revisit trigger.** Revisit if a third automated ingestion path is proposed: two doors is a cost accepted once, and a third would need the aggregate to be argued rather than the increment. Revisit also when `OQ-028` is answered, because that changes what this parser may ingest.

---

## ADR-061 — The environment an endpoint is published in is a tenant catalogue, not a pair of form fields

**Status.** Accepted · **Date.** 2026-08-21 · **Deciders.** Chief Software Architect, Principal Security Architect

**Context.** ADR-027 forbids hardcoded vocabulary, and the schema honoured it: the environment an endpoint is published in is carried as `attributes->>'environment'` on the `PUBLISHED_ON` edge, indexed and deliberately not constrained by a `CHECK`, with the migration that introduced it stating that "environments are tenant vocabulary" and that the interface "groups by the distinct values present".

The enumeration reappeared where the schema could not see it — in the two editors. The application form offered *Production* and *Staging*; the project form offered *Production* and *UAT*. Two hardcoded pairs, disagreeing with each other.

The consequence was not cosmetic, and it is the reason this record exists rather than a defect note. The inventory lists offer an endpoint column per environment **found in the recorded data**. An environment with no write path therefore acquires no endpoint, so it never becomes a column, so it is absent from every list, filter, count and export. An application's UAT host was unrecordable and consequently uncountable. A pre-production estate is routinely a copy of production data behind weaker controls, and "which of our systems have a UAT host, and where is it" is a question this product exists to answer.

Two lesser defects sat inside the same surface and are corrected by the same change. The endpoint columns were sent to the interface with `filterable: false`, while both list endpoints validated filter keys against the declared-field catalogue — so a filter naming an endpoint column was not refused, it was **silently discarded**, and an unfiltered list came back looking filtered. And the editors read one host per environment with a `findFirst()`, so a project published on two hosts in one environment displayed one of them and closed the edge to the other on save, without ever having shown it.

**Options considered.**

- **A. Add UAT to the application form.** Fixes the reported symptom at the smallest cost.
- **B. Derive the form's environments from the environments already present in the data.** No new table; the form offers what the estate uses.
- **C. A tenant catalogue of endpoint environments, with the forms, the columns and the filters all reading from it.**
- **D. Constrain the edge attribute to a product-fixed enumeration and enumerate it once, in code.**

**Decision.** Option C. `asset_endpoint_environment`, tenant-scoped, administered under the existing `cfg.asset.field.manage`, seeded with the two lists the editors had compiled into them. Endpoint columns become a first-class `HOSTNAME` column kind carrying two filters: whether an endpoint is recorded in that environment, and what the hostname contains.

**Why not the others.** Option A leaves the defect and moves the symptom: the next tenant runs SIT, or pre-production, or calls production *LIVE*, and is back to a release. Option B is circular in exactly the way that caused this — an environment nobody can record never appears in the data, so it is never offered, so nobody can record it; and it has no place to hold the sentence saying what an environment is for. Option D is the prohibited pattern DOC-00 names, and the migration that introduced the attribute had already rejected it in writing.

**Consequences.**

Positive: an environment is declared once and appears in both editors, in the column picker on both inventory lists, and in the filter bar, with no code change. An **active environment with nothing recorded in it still gets a column**, which is the half of product principle 1 this surface was missing — "no UAT host recorded against this project" is an answer, and there was previously no way for the platform to give it. The endpoint filters answer a posture question (`ABSENT` is a `NOT EXISTS`, so it returns the systems whose pre-production estate nobody has inventoried) and a triage question (a hostname fragment from an alert) without conflating them.

Negative: a third vocabulary surface now needs administering, and a tenant that declares nothing gets the seeded defaults rather than nothing — which is `PP-3`, opinionated defaults, and is a cost only in that the defaults are visible in a migration. Deprecating an environment leaves every edge published in it current and unmaintainable from any form, which is why the catalogue screen shows the endpoint count beside the retire control and why a form widens itself to include any environment the record it is editing already holds. And the environments an importer writes are still unconstrained by the catalogue, so the interface must distinguish three states rather than two — declared, retired, and recorded-but-never-declared — where it previously distinguished none.

Neutral: the edge attribute remains unconstrained, so this catalogue governs what the interface **offers**, never what the data may **hold**. An imported edge naming an environment nobody declared keeps its column and is marked as undeclared, because rejecting it would discard a recorded fact and admitting it silently would present a vocabulary nobody agreed to as though somebody had.

**What this decision does NOT do.** It does not make the environment part of the endpoint's identity. Two edges from one asset to one domain in two environments remain two edges to one `DOMAIN` asset, and the reachability question — everything published at this host — is still answered without reference to the environment. It also does not weigh the environment in the risk model: an endpoint in a weakly-controlled environment is not scored differently from one in production, because `exposure_declared` is the scored input (DOC-28) and adding an environment term would change every score. That is a separate decision nobody has made.

**Compliance.** Verified through `CFG-AST-002`, whose second clause — a column and a filter for every configured environment irrespective of what is recorded — is the operative half, and through `PRD-AST-004`.

**Revisit trigger.** Revisit if the risk model is asked to weigh the environment an endpoint is published in, because that turns tenant vocabulary into a scored input and the two cannot both be free. Revisit also if a tenant needs environments to differ per asset type, which this catalogue is deliberately tenant-wide rather than per-type: one estate, one set of environment names, until somebody produces a case where that is false.

---

## ADR-062 — A unique-identity violation is a conflict the caller can act on, not an internal error

**Status.** Accepted · **Date.** 2026-08-25 · **Deciders.** Chief Software Architect, Principal Security Architect

**Context.** Asset identity is enforced at the engine by a unique index over `(tenant_id, type_id, identity_key)`. The dispatcher translates one SQL state into a domain response — `23503`, foreign key violation — and translates no other. A unique violation therefore falls through to the generic handler.

Measured on a running deployment, twice, with a service credential:

```
POST /api/v1/assets {"display_name": "DUPLICATE PROBE app", …}  → 201
POST /api/v1/assets {"display_name": "DUPLICATE PROBE app", …}  → 500 INTERNAL_ERROR, correlation=86d933f8…
POST /api/v1/assets {"display_name": "duplicate probe APP", …}  → 500   (identity folds case)
```

Three things follow, and the third is the expensive one. A client cannot tell "this already exists" from "the platform is broken". A correlation identifier sends somebody to a server log for a condition that is entirely the caller's to fix. And a `500` is the one status a well-written client is *supposed* to retry — so a bulk load meeting an existing record retries a request that can never succeed, at whatever backoff it was given, for as long as it is willing to.

The obvious repair — answer `409` and name the record already holding the identity — is wrong as stated, and `SEC-AUZ-020` is why: responses must not differentiate non-existence from non-authorization, in status code, error code, message **or timing**. An identity collision with a record outside the caller's scope is exactly that differentiation. A caller who cannot see an asset would learn it exists by trying to create one with its name, and would learn its identifier from the refusal.

**Options considered.**

- **A. Leave it.** The constraint holds, no data is corrupted, and the caller reads the documentation.
- **B. Map `23505` to `409 CONFLICT`, always naming the conflicting record.**
- **C. Map `23505` to `409 CONFLICT`, naming the conflicting record only where the caller can already reach it, and returning a bare conflict otherwise.**
- **D. Check for the existing record before inserting, and answer from that check.**

**Decision.** Option C. `23505` maps to `409` with a stable code; the response carries the conflicting record's identifier only when a scoped re-read returns it to this caller, and otherwise says that the identity is taken without saying by what.

**Why not the others.** Option A leaves a status code that instructs clients to retry an impossible request, and leaves the integrity of the inventory being defended by a message nobody can act on. Option B is a disclosure: it turns the create endpoint into an oracle for the existence and identifier of records outside the caller's scope, which is the first of the five highest-risk surfaces this platform names about itself. Option D is the same defect in a different place — a read-then-insert races, and the window between the check and the insert is exactly where a concurrent create lands, so the constraint would still fire and still be unmapped. The check is a usability improvement over the refusal, never a replacement for it.

**Consequences.**

Positive: a bulk load can distinguish "already present" from "server fault" and choose between skipping, updating and stopping, which is what a first import of a real estate spends most of its time doing. The retry storm goes away. A conflict stops consuming a correlation identifier and an entry in an error log that on-call reads.

Negative, and it is the cost this record accepts: the response is now **deliberately less informative for some callers than for others**, and that asymmetry has to be implemented in the error path rather than in the happy path, which is where error handling is least often tested. A conflict that names nothing is harder to debug, and the person debugging it will be tempted to widen it. `SEC-AUZ-020` is the reason not to, and that is why it is cited here rather than left implied.

Neutral: other unique constraints in the schema gain the same treatment by construction. This is a mapping of a SQL state, not a special case for one table.

**What this decision does NOT do.** It does not make creation idempotent. A repeated create is still a refusal rather than a no-op returning the first outcome, and callers that want to repeat a create safely need ADR-063. It also does not add a lookup-by-name to the versioned API: `display_name` is deliberately not filterable, so finding the existing record remains the caller's problem, and this decision only stops that problem being reported as a server fault.

**Compliance.** Verified through `PRD-AST-006` (identity independent of display name), `SEC-AUZ-020` (non-existence and non-authorization are indistinguishable), `PRD-UIX-025` (no internal detail in an error) and `PRD-WRK-043`, whose "explicit conflict response" is the shape being applied to a different conflict.

**Revisit trigger.** Revisit if the platform gains an upsert on any resource, because an upsert makes the identity collision an ordinary outcome rather than a refusal and this mapping would then fire on a path where it means something else.

---

## ADR-063 — Idempotency keys are required and unenforced; make them real rather than ceremonial

**Status.** Accepted · **Date.** 2026-08-25 · **Deciders.** Chief Software Architect, Principal Security Architect

**Context.** `PRD-API-005` is a MUST_HAVE and reads: *state-changing operations MUST support idempotency keys, and a repeated key MUST return the original outcome rather than repeating the effect.* The dispatcher requires the header on every class B and class E operation and refuses the request without one.

It then validates the key's shape, namespaces it by tenant — which is a real control, and the reason one tenant's replay cannot collide with another's under `SEC-TEN-009` — and does nothing else with it. There is no stored-outcome table in the schema. Measured:

```
POST /api/v1/assets  {…}  Idempotency-Key: K   → 201  id=01a03722-e184…
POST /api/v1/assets  {…}  Idempotency-Key: K   → 500        (executed again, hit the identity constraint)
```

So the second half of the requirement is unimplemented, and the header is a gate rather than a guarantee. One endpoint does satisfy it and implements the check itself: `POST /api/v1/finding-imports` looks up the prior import session by key before parsing or writing anything and returns the first submission's report, because a second ingestion re-detects every finding and re-detection of a closed finding reopens it — a CI timeout would otherwise manufacture "this keeps coming back" out of nothing.

That single implementation is what makes the gap hard to see. The behaviour is correct on the door most likely to be retried, and absent everywhere else, so a reviewer who tests ingestion concludes the platform has idempotency.

**Options considered.**

- **A. Leave it, and document that the header is required and not honoured.**
- **B. Stop requiring the header where nothing acts on it,** and mark `PRD-API-005` as partially met.
- **C. A stored-outcome table consulted by the dispatcher, for every operation that requires a key.**
- **D. Per-endpoint implementation, as ingestion already does.**

**Decision.** Option C. A tenant-scoped record of `(tenant, key, method, path, request digest)` to the response that was produced, written in the same transaction as the operation and consulted before it. A repeat of the same key with the same request returns the stored response. A repeat with a **different** request digest is a client error and is refused, not served from the store.

**Why not the others.** Option A keeps a control that looks like a control, which is worse than not having one: a client author reads the required header and reasonably concludes retries are safe. Option B is defensible and was close — a gate that stops a form double-submit has some value even without replay — but it gives up the requirement rather than meeting it, and `PRD-API-005` was written for the case this platform actually has: pipelines writing to it on a timeout. Option D produces one implementation per endpoint, each written by somebody who has to remember the rule, which is how ingestion came to be the only one.

Binding the request digest into the key is what stops the store becoming a new defect. Without it, a client that reuses a key across two different requests receives the first request's response for the second — a wrong answer delivered confidently, which is harder to detect than an error.

**Consequences.**

Positive: `PRD-API-005` becomes true of the platform rather than of one endpoint. A pipeline can retry a scoped write without either duplicating the effect or having to reason about which endpoint it is talking to. The form double-submit case the dispatcher comment describes is genuinely closed rather than merely refused.

Negative: a write to a shared table on every state-changing operation, on the transaction path, at whatever the platform's write rate becomes — and the store grows without bound unless it is aged out, so a retention window becomes an operational parameter somebody must set and monitor. A stored response can also be **stale**: replaying a 201 for a record that has since been retired returns an outcome that was true and is not. That is the correct answer to "what happened when I sent this" and the wrong answer to "what is true now", and the two are easy to confuse.

Neutral: ingestion's own check stays. It is inside the ingestion transaction and answers a question about an import session rather than about an HTTP response, and collapsing the two would make the ingestion path depend on the dispatcher's storage for a property it currently owns.

**What this decision does NOT do.** It does not make every operation safe to repeat — an operation that is not idempotent in the domain stays that way, and the store only guarantees that a repeated *request* does not become a repeated *effect*. It does not apply to class A reads, where a key protects nothing. And it does not change the nonce-and-timestamp replay protection on signed requests, which answers a different question: that mechanism stops an attacker replaying a captured call, this one stops a legitimate client repeating its own.

**Compliance.** Verified through `PRD-API-005`, `SEC-TEN-009` (the namespace must be tenant-partitioned structurally, which the existing key construction already satisfies and the new store must preserve), and `PRD-ING-005`, which is the ingestion-specific form the existing implementation meets.

**Revisit trigger.** Revisit when the write rate makes the store's own contention measurable, or when a retention window shorter than the longest client retry interval is proposed — a store that forgets before a client stops retrying is a store that answers "no record of this" to the case it exists for.

---

## ADR-064 — One identity rule per asset type, applied by every writer that creates one

**Status.** Accepted · **Date.** 2026-08-25 · **Deciders.** Chief Software Architect, Principal Security Architect

**Context.** `PRD-AST-006` is a MUST_HAVE: *each Asset MUST have a stable identity independent of its display name, and the platform MUST define per-type identity resolution rules.* `asset_type.identity_rule` exists to hold those rules, and ADR-009 makes the type registry the place they live.

Two writers create `REPOSITORY` assets and they do not agree. The seeds and the composition pipeline write an identity key of the form `repo:<namespace>/<name>`. The inventory form derives one by folding the **display name** — which is what `PRD-AST-006` forbids in its first clause — so the same repository resolves to a different identity depending on which door it arrives through.

The estate carried the fingerprint of this before any of it was investigated:

```
repo:Card Issuing/Authorization/aspm-upload-check
repo:Card Issuing/Authorization/repo:Card Issuing/Authorization/aspm-upload-check
repo:Card Issuing/Authorization/repo:Card Issuing/Authorization/repo:…/aspm-upload-check
```

Three rows, one repository, the prefix accumulating once per save. It is produced by an ordinary round trip: the editor returns the repository's display name, the save resolves that name by the other rule, no existing asset matches, and a new one is created. Reproduced twice more during verification on 2026-08-24, on two different projects, each time moving the project's `BUILDS` edge to the new duplicate and leaving the original with a closed edge.

The consequence is not cosmetic and it is not confined to the inventory. Two assets for one repository are two rows a component advisory can reach, two coverage states, and two answers to "which systems contain this vulnerable component" — the question ADR-001 restructured the whole model to make answerable.

**Options considered.**

- **A. Make the inventory form use the `repo:` form.** One writer changes, the rule stays implicit.
- **B. Normalise existing duplicates and add a uniqueness check in the application layer.**
- **C. One derivation, in one place, driven by `asset_type.identity_rule`, that every writer calls — and editors that round-trip an identifier rather than a display name.**
- **D. Accept both forms and reconcile duplicates after the fact.**

**Decision.** Option C. Identity derivation becomes a single function over the type's declared `identity_rule`; every path that creates or resolves an asset by name calls it; and the record editors send the identifier they were given rather than re-resolving a name the user never edited.

**Why not the others.** Option A fixes the writers that exist today and leaves the next one to be written by somebody who does not know this note exists — which is precisely how the second writer appeared. Option B puts the rule in the application layer, where DOC-26 §13.2 places it in the weaker class of control: it holds until the next endpoint forgets it. Option D is the failure it claims to fix — reconciliation of duplicate assets is a thing this platform does for findings, deliberately, because findings arrive from sources it does not control; assets it creates itself should not need it.

The second half of the decision is the load-bearing half. Even with one derivation rule, an editor that round-trips a display name will create a new asset whenever the name and the stored identity disagree — which is exactly the case a rename produces. Sending the identifier removes the re-resolution entirely.

**Consequences.**

Positive: one repository is one asset however it was recorded, so reachability, coverage and advisory impact stop being split across duplicates. A rename stops being a create. The `identity_rule` column stops being documentation and becomes the thing the code reads.

Negative: the existing duplicates do not merge themselves. Merging them means moving edges, findings and coverage from one asset to another and closing the loser, and there is no DELETE grant on `asset` — so the migration is a data-repair exercise with an audit trail of its own, on rows that real work may already reference. Until it runs, the estate holds assets that this decision says should not exist. Naming that here is the point: the decision is cheap and the cleanup is not.

Neutral: types whose identity rule is genuinely the display name — where the natural key and the label are the same string — behave exactly as before. The change is that the rule is consulted rather than assumed.

**What this decision does NOT do.** It does not define the identity rule for any particular type; those stay tenant-visible data in the type registry. It does not touch finding identity, which is product-fixed under `CFG-PLT-004` and deliberately not tenant-configurable. And it does not repair the recorded duplicates — that is a separate migration, and the rule it needs — which of two rows survives when both carry findings, coverage and edges — is raised as `OQ-029` rather than guessed at here.

**Compliance.** Verified through `PRD-AST-006` (stable identity independent of display name, per-type rules) and `PRD-AST-001` (one Asset aggregate distinguished by type, with the registry declaring what each type means).

**Revisit trigger.** Revisit when a second asset type acquires a namespaced identity — an artifact coordinate, a container digest — because a rule expressive enough for two forms is a rule that needs a grammar rather than a convention, and that is a larger decision than this one.

---

## ADR-065 — A plan is a dated intention, stored; not a draft request and not a recurrence rule

**Status.** Accepted · **Date.** 2026-08-25 · **Deciders.** Chief Software Architect, Principal Security Architect

**Context.** `PRD-ASM-003` makes the periodic full review an obligation the platform tracks, and V024 gives it the machinery: `full_review_policy` states the interval per criticality tier, `assessment_trigger.counts_as_full_review` records which requests discharge it, and `application_review_cadence` computes what is owed and when. Everything needed to say *this application is overdue* was present.

Nothing was present to say *when we intend to do it*. The planning screen drew a Gantt of assessment requests and offered a "Schedule" button that opened the intake form pre-filled with a trigger, a due date and — where the application had exactly one — a project. Laying out a year therefore meant creating one full assessment request per review.

Reported from use on 2026-08-25, in the reporter's words: *"khi schedule có thể cho cả năm nên nếu điền đủ thông tin thì sẽ không có thông tin để điền"* — when the schedule can cover a whole year, if the form insists on complete information there is no information to give it. That is not friction. An intake form legitimately requires a scope descriptor, a title and a type-specific payload; none of the three is knowable in October for work happening the following September. The obligation the corpus spent V024 making measurable was, in practice, unplannable, and the plan lived in a spreadsheet — which is where the coverage question stops being answerable at all.

Two further gaps were reported alongside it. A single next-due date cannot express an application reviewed four times a year, so the cadence view could describe the obligation but not the plan that discharges it. And the planning table showed cadence facts only, so an engagement had to be sized from a different screen — the API count and the access path being declared on the PROJECT, not the application.

**Decision.** Add `assessment_plan_window` (V070): a target, a start date, an end date, and nothing else that is not known at planning time. Three properties are load-bearing.

1. **A window is not a request.** It has no payload, no scope descriptor, no workflow state, no assignee and no SLA clock. It is written by `PlanWindows`, the single write path, and read into the planning page as a distinct kind of Gantt bar.
2. **Windows are stored, not derived.** The alternative — a recurrence rule per target, with windows computed on read — was offered and rejected.
3. **Conversion is explicit.** A window becomes work when a person raises the request from it; the window then records the request's identifier and stops being an open intention.

The plannable target is an application **or** a project, in one column, because ADR-009 gives the platform one `Asset` aggregate with a type registry. An estate is planned at application level and sized at project level, and one column carries both.

**Options considered.**

| Option | Why not |
|---|---|
| Draft assessment requests for the year | The one that was already in place, in effect. A request is the record of work: four hundred draft rows dated next year enter every in-flight figure on the platform, and the request board — the queue an assessor works from — becomes a list of things nobody is meant to start. Cancelling a request is also a disposition with a reason and a workflow transition, correctly so; cancelling an intention should cost a click. Worst of all, the plan and the record become the same rows, so the plan silently becomes whatever happened and "we planned four and did two" can no longer be asked. |
| A recurrence rule per target | Fewer keystrokes, and genuinely tempting. Rejected because a derived plan changes retroactively: somebody asking in November what Q1 was supposed to look like would be shown Q1 recomputed under today's rule, with nothing on the screen admitting the substitution. Product principle 5 makes the record of what happened inviolable, and a plan of record is close enough to be held to it. The convenience is kept where it costs nothing — the interface *proposes* evenly spaced windows, defaulted from `full_review_policy.interval_months` so the tenant states its cadence once, and the planner edits them before saving. |
| Extend `application_review_cadence` with a next-planned date | One date per application cannot express several reviews a year, which is the case `PRD-ASM-017` exists for. It would also put a mutable intention inside a computed read model, so the plan would be overwritten every time the cadence was recomputed. |
| Two nullable columns, `application_id` and `project_id` | Needs a CHECK to keep exactly one populated, needs every query to coalesce them, and needs a third column the day somebody plans an assessment of a service. ADR-009 already decided this shape. |

**Consequences.**

- **Accepted: the plan can disagree with reality, and nothing reconciles it automatically.** A window whose fortnight passed with no request raised stays `PLANNED` and stays on the timeline. That is deliberate — it is the finding a planning review exists to produce — but it means the plan degrades into noise if nobody reviews it. No requirement obliges anyone to.
- **Accepted: `asm.request.schedule` is reused rather than a new permission added.** ADR-027 fixes the permission catalogue at product level, so a new permission is one every existing tenant must grant before the feature works for anybody. The reused permission's meaning — decide when work happens — covers planning exactly. The cost is that an estate wanting to separate "may plan" from "may schedule a raised request" cannot, and will need a catalogue addition later.
- **Accepted: a window carries no assignee, so the plan cannot answer "whose fortnight is this".** Capacity is visible as counts per period and not as load per assessor. Adding an assignee would have meant deciding what happens when that person leaves, which is a workflow question a plan should not have.
- **Accepted: the target-type restriction lives in the domain layer.** A CHECK cannot see the join to `asset_type`, so ADR-030 applies and `PlanWindows` enforces it on the one write path, with `PlanWindowTest` asserting the refusal. A second writer added without that check would create windows against a service or a hostname, and only reconciliation would find them.
- **Cost paid at once: `SEC-AUD-009` makes a bulk plan expensive.** An event per window plus a summary means a four-hundred-window plan writes four hundred and one chained audit rows. The insert is therefore a loop returning identifiers rather than a batch — the batch would have saved round trips the chained writer spends anyway. A batch bound of 2,000 refuses rather than truncates.
- **Defect found and corrected during verification, recorded here because it will recur.** `PlanWindows.update` and `setState` returned `Optional.empty()` on a statement that matched no rows, without ending the transaction. `TenantConnections` counts a ran-and-matched-nothing `UPDATE` as a write and refuses to close a written transaction without `commit()`, so the intended 404 for a window that does not exist reached the client as `500`. Measured, not inferred. Both paths now commit the empty unit of work with the reason beside them, and `PlanWindowTest` asserts both the commits and the comments — because the next reader sees a commit after a failed update and removes it.

**Revisit if.** A tenant needs the plan to generate requests unattended — at which point `PRD-ASM-018` is what has to change first, and the scope-selection problem product principle 4 raises has to be answered rather than bypassed. Or if plan-versus-actual reporting shows the plan is routinely abandoned rather than adjusted, which would mean the planning horizon is longer than the estate can commit to and the feature is measuring the wrong thing.

**References.** `PRD-ASM-003`, `PRD-ASM-015`, `PRD-ASM-016`, `PRD-ASM-017`, `PRD-ASM-018`, `SEC-AUZ-016`, `SEC-AUZ-020`, `SEC-AUD-009`, ADR-009, ADR-027, ADR-030, V024, V070.

## ADR-066 — A review the platform did not observe is asserted, not recorded as observed

**Status.** Accepted · **Date.** 2026-08-27 · **Deciders.** Chief Software Architect, Principal Security Architect

**Context.** `application_review_cadence` answers "when was this application last reviewed end to end, and when is the next one owed". It derives that answer from `application_full_review`, which requires the full chain: an `assessment_request` naming a trigger the tenant marked `counts_as_full_review`, an `assessment` execution record, an `assessment_scope_asset` link, and a transition into a terminal state to produce `closed_at`.

Reported from use on 2026-08-27, while preparing to load a real estate: *"với những request chưa được note trước đó, hoặc trước khi có hệ thống này, tôi muốn thêm vào là đã đánh giá trong khoảng thời gian trước đó thì làm như nào"*. Two cases hide in that, and only one was answerable.

1. **A request is in the platform but its reason was never recorded.** Already solvable, and no change is needed: the request board filters on trigger = none — a filter that exists precisely because "which requests never had a reason recorded" is a real question — and `AssessmentService.setTrigger` sets it. The review then counts as the observed review it always was, with its findings and history intact. One constraint is worth stating: `application_full_review` inner-joins `assessment`, so a request that never carried an execution record cannot be made to count by relabelling it. That is correct — a request nobody executed is not a review.

2. **The assessment predates the platform.** There is nothing to relabel. Making it count through the existing model means fabricating a request, an execution record, a scope link and a terminal transition.

The cost of leaving case 2 open is not cosmetic. On first load, every application assessed outside the platform reads `NEVER` and a large share read `OVERDUE`. The first thing the estate's own security team sees is a coverage figure they know to be wrong — and a figure known to be wrong stops being read, which takes the accurate parts of it down too.

**Decision.** Add `application_review_attestation` (V071): an attributed statement that a whole-application review happened between two dates. It discharges the periodic obligation and it is marked as asserted everywhere it appears.

Product principle 1 is normally quoted for measured versus not-measured. It has a third state, and naming it is the whole of this decision:

| | What the platform holds |
|---|---|
| **OBSERVED** | The request, the execution record, the transitions, the findings. A claim the platform can substantiate from its own data. |
| **ATTESTED** | A person's statement that work happened between two dates, their name against it, and optionally a reference to the report. The platform did not watch the work. |
| **NOT MEASURED** | Nothing. |

So: `full_review_count` keeps its existing meaning and counts observed reviews only; `attested_review_count` sits beside it; `last_full_review_at` takes the later of the two, because the question that column answers is "when was this last reviewed" and a `never` beside a status of `CURRENT` is a screen contradicting itself; and a new `last_full_review_source` column says which source produced the date, so a reader is never shown a date without being able to find out how the platform knows it.

**Options considered.**

| Option | Why not |
|---|---|
| Import historical reviews as completed requests | Reuses the whole model and the review then appears in history like any other — which is exactly the objection. It writes a workflow history nobody lived into `assessment_request_transition`, the log `PRD-PLT-001` names as data that cannot be reconstructed, and it makes assertion permanently indistinguishable from evidence. |
| A `COMPLETED` state on the planned window | Tempting, because a window is already a dated period for a target. Rejected because ADR-065 draws the line that a plan is not a record of work, and a window that discharges an obligation is a record of work. The two concepts would then have one table and two meanings. |
| Let the tenant edit `last_full_review_at` directly | No attribution, no period, no evidence, no withdrawal, and no way to tell it was edited. It is the same capability with every audit property removed. |
| Do nothing; accept the wrong figures at go-live | The option in force until now. It makes the first impression of the platform a coverage number its own users can refute, which is the fastest way to lose a measurement nobody is obliged to trust. |

**Consequences.**

- **Cost paid deliberately: a new permission, `asm.review.attest`.** Not folded into `asm.request.qa` or `.approve`, because this action moves a coverage figure on one person's word and reusing an existing permission would grant it to everybody who already holds that one — which nobody decided. ADR-027 fixes the catalogue at product level precisely so a new authority has to be handed out on purpose. Every tenant must grant it before the feature works for anybody, and a tenant that forgets will see the control absent rather than an error.
- **Accepted: an attestation is unverifiable by construction.** The evidence reference is free text and optional, and nothing checks that the named report exists or says what the attestation claims. The mitigation is disclosure, not validation: an attestation with no evidence is displayed as the weaker claim it is, and the asserting person's name travels with it.
- **Accepted: `last_full_review_at` changed what it computes.** Existing consumers — `OverviewQuery`, `TriageAgent`, `AssessmentService`, the planning page — now receive a date that may rest on an assertion, and any of them that displays it without also reading `last_full_review_source` will present hearsay as evidence. Every current consumer was reviewed; the risk is the next one, and `ReviewAttestationTest` asserts the source column exists but cannot force a consumer to read it.
- **Accepted: attestations do not enter coverage-by-severity or produce findings.** An asserted review says work happened, not what it found. An estate that backfills heavily will therefore show good periodic coverage and no findings from it, which is honest and may still read as reassuring.
- **Accepted: granularity is per application only.** The obligation `full_review_policy` expresses is per application, so an attestation against a project would discharge nothing while sitting in the record as though it had. An estate that assesses per project cannot express that here.
- **Withdrawal is retained, not deleted, and requires a reason.** A claim made and retracted is a different finding from a claim never made: the first says a control was believed to exist, which is what a post-incident review looks for.
- **The spreadsheet importer carries it.** `examples/08_import_inventory_xlsx.py` accepts assessment-date columns and asserts one review per application per distinct end date, reading the existing assertions once up front so a re-run — which is expected, because sheets get corrected and reloaded — does not assert the same review twice. Month-only cells resolve to the last day of the month, because an obligation is discharged when work finished and resolving to the 1st would move the next due date a month early.

**Revisit if.** Coverage reporting to an external party has to distinguish attested reviews more strongly than a label — at which point the evidence reference has to become a real attachment with a retention policy rather than free text. Or if attested coverage grows past a share of the estate where the aggregate figure stops being defensible, which is a threshold nobody has set and which this record deliberately does not invent.

**References.** `PRD-ASM-003`, `PRD-ASM-019`, `PRD-ASM-020`, `PRD-ASM-021`, `PRD-ASM-022`, `PRD-PLT-001`, `SEC-AUZ-016`, `SEC-AUZ-020`, ADR-027, ADR-030, ADR-065, V024, V071.

## Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.6.0 | 2026-08-27 | Chief Software Architect, Principal Security Architect | Added ADR-066, admitting a third state into the coverage figures: a review the platform did not observe, asserted by an attributed person. The record separates two cases the report conflated — a request already in the platform whose reason was never recorded, which needed no change because the board already filters on trigger = none, and an assessment predating the platform, which had no path at all. Importing history as completed requests is recorded as rejected: it would write a workflow history nobody lived into the transition log PRD-PLT-001 names as unreconstructable. Two consequences are stated as accepted costs rather than solved: an attestation is unverifiable by construction, mitigated by disclosure and not by validation; and `last_full_review_at` changed what it computes, so any consumer that reads it without the new source column will present an assertion as evidence. A new permission is added deliberately rather than reused, at the cost of every tenant having to grant it. 61 expanded records — counted from the headings. | Pending |
| 1.5.0 | 2026-08-25 | Chief Software Architect, Principal Security Architect | Added ADR-065, recording the assessment plan as a stored dated intention rather than a draft request or a recurrence rule. The record states the defect it closes rather than only the decision it takes: because the only way to schedule was the intake form, and the form correctly requires a scope descriptor and a type payload, a year could not be planned at all and the periodic obligation V024 exists to measure was tracked in a spreadsheet. Two alternatives are recorded as rejected with the reason — draft requests, which would put a year of plan into every in-flight figure and let the plan silently become whatever happened; and a recurrence rule, which would rewrite last quarter's plan the day somebody edited the rule. One defect found during verification is recorded in the consequences rather than fixed silently: an UPDATE matching no rows left its transaction open, so the intended 404 for an unknown window reached the client as a 500. 60 expanded records — counted from the headings, not carried forward. | Pending |
| 1.4.0 | 2026-08-25 | Chief Software Architect, Principal Security Architect | Added ADR-062, ADR-063 and ADR-064, each recording a defect reproduced against a running deployment rather than a decision taken in the abstract. ADR-062: a unique-identity violation reaches the client as `500 INTERNAL_ERROR`, because the dispatcher maps `23503` and no other SQL state — so a duplicate create is indistinguishable from a server fault and is the one status a client is supposed to retry. The repair is constrained by `SEC-AUZ-020`: naming the conflicting record would turn the create endpoint into an existence oracle for records outside the caller's scope. ADR-063: `PRD-API-005` requires a repeated idempotency key to return the original outcome; the dispatcher validates the key's shape, namespaces it, and executes the request anyway, so the requirement is met by exactly one endpoint that implements the check itself. ADR-064: two writers derive `REPOSITORY` identity by two different rules, one of them from the display name that `PRD-AST-006` forbids, which has been producing duplicate assets — the estate carries a `repo:` prefix stacked three deep on one repository, and two further duplicates were reproduced during verification. **59 expanded records, and the figure in the row below is corrected.** The running count had drifted: ADR-057, ADR-058 and ADR-059 were added with complete records and no change-history row, so 1.2.0's "52" understated by three, and 1.3.0 — written by the same hand as this row — copied the previous row's arithmetic instead of counting the file, giving 53 where it held 56. 1.3.0 is corrected in place with the superseded figure left visible; 1.2.0 and earlier are left exactly as written, because a change history records what was said at the time and rewriting older entries would destroy the evidence that the drift happened. Counted, not derived: 59 headings, with 008, 014, 015, 018 and 025 cross-referenced rather than expanded. | Pending |
| 1.3.0 | 2026-08-21 | Chief Software Architect, Principal Security Architect | Added ADR-061, making the environment an endpoint is published in a tenant catalogue rather than two hardcoded pairs of form fields. The record states the defect it closes rather than only the decision it takes: because the endpoint columns were derived from recorded data alone, an environment with no write path could never become a column, so an application's UAT host was unrecordable and therefore absent from every list, filter and count. Two further defects in the same surface are recorded in it — endpoint columns sent as unfilterable while their filter keys were validated against the wrong catalogue and therefore silently discarded, and a `findFirst()` in both editors that hid the second host in an environment and closed its edge on save. 56 expanded records (corrected from 53 — see 1.4.0). | Pending |
| 1.2.0 | 2026-08-14 | Chief Software Architect, Principal Security Architect | Added ADR-060, a second automated ingestion path for SARIF scan reports, and marked ADR-023 `Superseded in part` with the retained record annotated to say which part. The exclusivity clause of ADR-023 was the only thing superseded; its single-point-of-failure consequence now applies to two endpoints rather than one, which the new record states rather than leaving to be inferred. ADR-060 also records what it does not do: runtime findings stay out, because the RUNTIME identity class declares an input a URL-based result does not carry, and that question is raised as `OQ-028` rather than answered in code. 52 expanded records. | Pending |
| 1.1.0 | 2026-08-04 | Chief Software Architect, Principal Security Architect | Extended with the eight technology selections DOC-02 §16 deferred and DOC-15 §2 specified, as ADR-049 through ADR-056, satisfying `OPS-DEP-001`. Each carries a Constraint verification field and a Gaps accepted field beyond DOC-00 §13.1, the second satisfying `OPS-DEP-002`. The two disqualifying constraints of ADR-042 were verified mechanism by mechanism: three candidate operational stores and two candidate toolchains were rejected for failing one or both, and three candidates that satisfied both constraints were rejected on secondary grounds recorded in the record rather than omitted. Four required properties are recorded as not provided by any candidate and enforced above the selected component — non-retrievable-after-entry and dual-controlled destruction in ADR-052, and cross-tenant cache key collision in ADR-055, the last identified as the highest-severity accepted risk of the set. The concentration of four of the eight decisions into the operational store is stated as an aggregate cost with a measured extraction trigger per decision. 51 expanded records. | Pending |
| 1.0.0 | 2026-08-04 | Chief Software Architect | Completed at 43 expanded records. Seeded with 24 covering the decisions taken during requirements analysis and DOC-01 authoring. Each carries options considered, mandatory negative consequences, and a conditional revisit trigger. Four corpus-level decisions are cross-referenced rather than expanded. Extended with nineteen decisions taken during the authoring of DOC-02 through DOC-17, each recording the cost accepted, including three convention corrections found by the corpus tooling rather than by review and one overstated diagnosis corrected in the record. | Pending |
