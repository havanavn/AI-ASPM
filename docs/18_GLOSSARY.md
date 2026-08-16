---
document_id:    DOC-18
title:          Glossary
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        1.0.0
status:         In review
owner:          Chief Software Architect
authors:        [Chief Software Architect]
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

# 18 — Glossary

The canonical definition of every domain term. **Each term has exactly one meaning across all 26 documents.** Where a user-interface label differs from the domain term, both are recorded, per DOC-00 §4.4 and §10.5.

Terms reserved with a single meaning are listed in DOC-00 Appendix D. A term used in this corpus without an entry here is a defect.

| Term | Definition | Notes and cautions | Vietnamese operational term |
|---|---|---|---|
| **Assessment** | A structured, scoped, evidence-producing security evaluation. One aggregate with a type registry covering architecture review, threat model, penetration test, vendor assessment, and generic assessment. |  |  |
| **Asset** | A unit of technical existence — a repository, service, API, domain, build artifact, or software component. Owned by exactly one OrgNode. One aggregate with a type registry (ADR-009). | Never a synonym for OrgNode. An asset exists technically; an OrgNode exists organizationally. |  |
| **Asset Graph** | The typed, many-to-many relationship structure between assets: repository builds artifact, artifact deploys as service, service exposes API, API published on domain, artifact described by SBOM. | Not a hierarchy. Modelling it as containment is the error ADR-001 corrects. |  |
| **Break-glass** | A time-boxed, individually justified, tenant-notified, elevated-audit access path for platform operators, used in place of standing access to tenant data. | A deliberately introduced privileged path. Its controls are the only thing distinguishing it from a backdoor. | Truy cập khẩn cấp |
| **Business Unit** | An OrgNode type, not a distinct entity. Tenants define their own node types and may name this level differently. | Do not treat as a fixed level (ADR-010, ADR-027). | Đơn vị kinh doanh / P&L |
| **Component Change Set** | The computed difference between two consecutive SBOM snapshots of the same artifact: components added, removed, and version-changed. |  |  |
| **Composition** | A declarative arrangement of measures and queues forming a dashboard. Scope is injected from the caller's authorization context rather than requested. | Four exist: executive posture, security operations, engineering ownership, security team workload. |  |
| **Coverage Gap** | An asset or organization node for which required data has never been received or has exceeded its freshness threshold. Distinguished from a clean result. | Central to PP-1. A coverage gap presented as a clean result is the platform's most dangerous failure mode. | Khoảng trống phủ |
| **Effective Permission** | The resolved permission set and scope for a principal, together with the grants that produced them, exposed for inspection. | Required so that access review, debugging, and audit are possible (PRD-AUZ-012). |  |
| **Exploitability Statement** | An assertion that a known vulnerability is not exploitable in a specific product context, ingestible and applicable to findings. | Distinct from a risk exception: it asserts the vulnerability does not apply, not that its risk is accepted. |  |
| **Feasible Start Date** | The earliest date on which requested work could begin, computed from current backlog, capacity, and prioritization, exposed at intake. | Its purpose is expectation management at the moment of asking (PRD-CAP-010, PRD-PTR-018). |  |
| **Finding** | A single identified security weakness, with a stable fingerprint, affecting one or more assets, from any source. | Per-asset remediation status is tracked independently of aggregate status. | Lỗ hổng / phát hiện |
| **Finding Fingerprint** | The deterministic, versioned identifier computed from attributes stable across rescans, used to decide whether an incoming finding is new or existing. | The hardest problem in the domain. Too specific destroys triage state on every rescan; too loose collapses distinct issues (PRD-VUL-001). |  |
| **Grounding Contract** | The per-AI-capability declaration of permitted data sources, required citation granularity, and output structure. |  |  |
| **Indirect Prompt Injection** | Attacker-authored text reaching model context through ingested finding data — a captured payload, a hostile header, crafted evidence. | Reachable by an attacker with no platform access. Specific to this product class (PRD-AIC-006). |  |
| **MatchRun** | One execution of vulnerability matching over a stored SBOM snapshot against a recorded intelligence version. | Surfaced to users as "Rescan". Domain term and UI label deliberately differ; recorded here per DOC-00 §4.4. | Quét lại |
| **OrgNode** | A node in the tenant's organization hierarchy. Exactly one parent except tenant roots. Type, depth, and label are tenant-configured. | The scope and accountability substrate. Distinct from Asset. |  |
| **Outbound Propagation** | One-way creation of a reference item in an external tracker for a remediation obligation, where the platform record remains authoritative. | Deliberately not bidirectional synchronization: two systems of record for one item means neither is one (PP-10). |  |
| **Read Model** | The purpose-built projection over which dashboard and reporting queries execute, separate from operational tables. |  |  |
| **Residency Pinning** | Tenant-level designation of the region in which its data must remain, enforced across primary storage and every secondary path including backup, telemetry, logging, notification, and AI egress. | Secondary paths are where residency is actually breached. |  |
| **Risk Exception** | A time-bound, approved, compensated decision not to remediate now. Mandatory bounded expiry; automatic reopen on expiry. | Unavailable for exposed credential findings. Excepted findings remain visible in posture reporting. | Chấp nhận rủi ro |
| **SBOM Snapshot** | An immutable, content-hash-identified software bill of materials for one build artifact, optionally anchored to a revision. | Immutability is the foundation of re-matching, change detection, and any future attestation capability. |  |
| **Scope** | The set of organization nodes and explicitly granted objects a principal may access. Always resolved server-side. | Never asserted by a client. A filtered picker is a usability feature, not an authorization control (PP-4). | Phạm vi |
| **Scope Root** | The highest organization node within a principal's authorized scope, from which dashboard drill-down is relative. | Removes the need for an absolute navigation path and the disclosure it would create (PRD-DSH-001). |  |
| **Scope Snapshot** | The organizational scope recorded on a scope-bearing event, used to resolve historical authorization and reporting independently of the current hierarchy. | Cannot be reconstructed retroactively. Required from v1 (PRD-ORG-011). |  |
| **Sender-Constrained Credential** | A credential bound to its holder such that observation alone does not permit reuse — mutual TLS or proof-of-possession. | Replaces bearer API keys, which are replayable by anyone who observes them (ADR-004). |  |
| **Separation of Duties** | An enforced constraint preventing one principal from holding conflicting permissions — requesting and approving an exception, acting and altering the record of action, configuring authorization while holding operational permissions. | Enforced, not advised. An advisory constraint is satisfied by the grant that violates it. |  |
| **Service Level Clock** | The elapsed-time measure against a work item's or finding's remediation obligation, pausable with recorded blocking attribution. | Pause with attribution is what makes lateness a process question rather than a blame question (PP-6). |  |
| **Single-Person Coverage** | A competency domain in which only one team member holds sufficient proficiency — a quantified continuity risk. |  |  |
| **Step-Up Authentication** | Fresh authentication required at the point of a sensitive operation, independent of session age. | Applies to restricted data reveal, elevated exception approval, authorization configuration change, erasure, and break-glass. |  |
| **Suggestion Ledger** | The store to which AI writes, which is not the system of record. Promotion into the system of record is an audited human action. | The load-bearing control reconciling AI assistance with the prohibition on AI write authority (ADR-005). |  |
| **Tenant** | A hard isolation boundary: separate key material, enforced at the persistence layer, with residency pinning. One customer organization. | Not a synonym for Business Unit. A tenant contains an organization hierarchy; a business unit is a node within it. |  |
| **Transition Log** | The append-only record of every work item state transition, with duration in the prior state and whether a service level clock was running. | Its data cannot be reconstructed later. Required from v1 (PRD-WRK-011). |  |
| **Utilization Target Band** | The configured range against which utilization is presented, in place of a maximum. | Presented as a band because waiting time grows non-linearly as utilization approaches saturation; showing a maximum invites optimizing toward a state that degrades throughput. |  |
| **WorkItem** | Any trackable application security work: assessment and test requests, remediation obligations, exception requests, ownership claims, platform engineering, governance, enablement, incident support, generic tasks. | Bounded to application security work. Not a general engineering tracker (NG-06). | Công việc |
| **Annotation class** | A grouping of the nine per-operation security annotations, assigned per API operation so the common case is uniform and only deviations are annotated. | DOC-05 §5. Concentrates correctness in the class definitions, which makes them the most test-critical code in the API layer. |  |
| **As-is aggregation** | Aggregation by the current organization hierarchy. |  |  |
| **As-was aggregation** | Aggregation by the scope descriptor recorded on each object, reproducing the organizational structure of the period. | Distinct from as-is. Both are legitimate; an unlabelled report will be compared against one using the other basis. |  |
| **Canonicalization** | Per-ecosystem normalization of a package identifier before matching, versioned so a rule change is traceable. | Comparing raw identifiers produces both false splits and false merges. |  |
| **Closure projection** | The derived ancestor-descendant table giving constant-cost subtree resolution at any depth. | Pure function of node parentage; rebuildable and rebuild-compared, because corruption breaks authorization silently. |  |
| **Coverage qualifier** | The coverage and freshness of the data supporting a measure, materialized with the measure rather than computed at presentation. | Making it a stored column rather than a derived value is what prevents its omission. | Độ phủ dữ liệu |
| **Degraded state** | An explicitly presented condition where a capability is unavailable, naming the capability, the reason, and what remains. | Distinct from empty and from loading. An empty region reads as nothing to report. |  |
| **Disqualifying constraint** | A required property of a technology selection whose absence rules the candidate out rather than requiring compensation. | Two exist: row-level security with forced owner enforcement, and build-time module boundary enforcement. |  |
| **Enforcement point** | A code location where authorization is evaluated before data is returned or a change applied. Enumerated in a map of twenty egress paths. | A path absent from the map is a path with no enforcement. |  |
| **Expand-migrate-contract** | A three-phase schema change permitting application and schema to deploy independently. | Required because atomic deployment is unachievable with rolling updates. |  |
| **Fingerprint input** | The retained set of values hashed to produce a finding fingerprint, kept so the algorithm can be improved without discarding triage history. | Without retention the first algorithm version is permanent. |  |
| **Forced row-level security** | Row-level enforcement applied to the table owner as well as to other roles. | Without it the owner bypasses the policy, and the application may connect as the owner. |  |
| **Grounding contract** | A per-AI-capability declaration of permitted sources, scope enforcement, citation granularity, and output schema. | Versioned and recorded per invocation. |  |
| **Honesty surface** | A presentation element that qualifies or attributes a figure — coverage, normalization, aggregation basis, generated-content label, migrated marker. | Fourteen are enumerated. None is suppressible by theme, density, template, or preference; each is individually easy to remove for a cleaner interface. |  |
| **Idempotency key** | A client-supplied key making a state-changing operation safe to retry, tenant-namespaced. |  |  |
| **Interned component** | A component identity stored once per tenant and referenced by entries, rather than repeated per snapshot. | Tenant-scoped rather than global, rejecting the more space-efficient option on tenant-boundary and inference grounds. |  |
| **Keyset pagination** | Pagination by a sort key with a unique tiebreaker rather than by offset. | Offset pagination silently skips and duplicates rows under concurrent modification, and full extraction is the most common API use. |  |
| **Lease** | A time-bounded claim on queued work, reclaimed automatically on expiry. | Preferred to heartbeat liveness because container termination is abrupt and is the normal failure mode. |  |
| **Match batch** | A scope-selected set of match runs, ordered by criticality and staleness, pausable and resumable. | Ordering is not arrival order so that an interrupted sweep has completed the important portion. |  |
| **Match run** | One execution of vulnerability matching over a stored snapshot against a recorded intelligence version. | Surfaced as "Rescan"; recorded even when skipped, so the coverage timeline has no gap. | Quét lại |
| **Numeric substitution** | Binding narrative placeholders to retrieved record fields rather than validating model-generated numbers. | Makes an incorrect number unrepresentable rather than detectable. |  |
| **Ordinal** | The product-fixed numeric position of a taxonomy value, providing a comparison basis independent of tenant vocabulary. | Configurable label over fixed ordinal is the pattern for severity, criticality, and every other tenant taxonomy. |  |
| **Outbound propagation** | One-way creation of a reference item in an external tracker, with the platform record authoritative and divergence surfaced rather than reconciled. | Bidirectional state synchronization would reproduce the failure of a generic tracker used for vulnerability management. |  |
| **Partition drop** | Retention implemented by removing a whole partition rather than deleting rows. | Deleting hundreds of millions of rows is a sustained load event; a drop is metadata. Legal hold is enforced at partition granularity for this reason. |  |
| **Quarantined record** | A source record that failed validation, retained with its raw content and reason, correctable and resubmittable. | Quarantine that cannot be resolved is deletion with extra steps. |  |
| **Reference profile** | A named data-volume and load profile — Small, Medium, Large, Extra large — to which non-functional targets are bound. | Binding targets to a profile means revaluation is a threshold change rather than a redesign of thirty-eight requirements. |  |
| **Residency designation** | The tenant-level region to which its data is pinned, enforced across primary and nine secondary paths. | Residency is breached in the paths nobody designed for residency: backup, telemetry, notification, model egress. |  |
| **Scope root** | The highest organization node within a principal's authorized scope, from which drill-down is relative. | Removes the need for an absolute navigation path and the disclosure it would create. |  |
| **Service level clock** | The elapsed-time measure against an obligation, pausable with recorded attribution, with its policy version and business calendar snapshotted at start. | Snapshotting is what prevents a later configuration change moving an existing deadline. |  |
| **Suggestion ledger** | The store to which AI writes, which is not the system of record. Promotion is an audited human action executing the ordinary operation. | The load-bearing control reconciling AI assistance with the prohibition on AI write authority. |  |
| **Target band** | A configured range against which utilization is presented, in place of a maximum. | Presented as a band because waiting time grows non-linearly near saturation; showing a maximum invites optimizing toward a state that degrades throughput. |  |
| **Transition log** | The append-only record of every work item state transition with duration and clock state. | Its data cannot be reconstructed later, which is why it is required in v1 regardless of when the analytics that consume it ship. |  |
| **Unclaimed asset** | An asset with no resolved owner, queued for claim with escalation. | Created rather than discarding an unresolvable finding, because discarding is silent data loss at the point of least detectability. |  |
| **Work class** | An isolated queue category — interactive, batch, projection, scheduled, dispatch — with independent concurrency control. | Interactive work is never queued behind a batch; that failure is the most common in self-built job orchestration. |  |

## Terms deliberately not used

| Avoided | Reason | Use instead |
|---|---|---|
| "Scan" for platform-executed analysis over source | The platform does not scan source (ADR-024) | *MatchRun*, or *ingested scan result* |
| "Project" for an engineering effort or repository | `Project` is an OrgNode type — a unit of scope | *Repository*, *Asset*, *initiative* |
| "Dashboard" for a role | Roles are tenant-defined; dashboards are compositions | *Composition* |
| "DCEO", "BU Manager" and similar as product roles | Illustrative examples only; no product artifact contains them (ADR-027) | *Role archetype*, *Business Owner* |
| "Real-time" | Unmeasurable | The latency budget with its percentile |

## Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.0.0 | 2026-08-04 | Chief Software Architect | Completed at 69 terms. Seeded with 35 terms arising from DOC-00 and DOC-01, including the domain-term to UI-label divergences and Vietnamese operational equivalents where in active use. Adds a deliberately-not-used list. Extended with 34 terms arising from DOC-02 through DOC-17, each with the caution or reasoning that makes the term load-bearing rather than merely defined. | Pending |
