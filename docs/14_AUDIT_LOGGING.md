---
document_id:    DOC-14
title:          Audit Logging
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        1.0.0
status:         In review
owner:          Principal Security Architect
authors:        [Principal Security Architect, Chief Software Architect]
reviewers:      []
last_updated:   2026-08-04
tier:           5
prerequisites:  [DOC-00, DOC-01, DOC-03, DOC-04, DOC-24]
depends_on:     [DOC-00, DOC-01, DOC-02, DOC-03, DOC-04, DOC-06, DOC-07, DOC-24, DOC-26]
supersedes:     null
adrs_relied_on: [ADR-002, ADR-005, ADR-007]
open_questions: []
requirement_domains: [AUD]
security_review_required: true
---

# 14 — Audit Logging

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope) · 2. [Event Envelope](#2-event-envelope) · 3. [Auditable Action Catalogue](#3-auditable-action-catalogue) · 4. [Integrity](#4-integrity) · 5. [Anchoring and Verification](#5-anchoring-and-verification) · 6. [Erasure Reconciliation](#6-erasure-reconciliation) · 7. [Retention and Legal Hold](#7-retention-and-legal-hold) · 8. [Access Control](#8-access-control) · 9. [Search and Investigation](#9-search-and-investigation) · 10. [External Export](#10-external-export) · 11. [Failure Behaviour](#11-failure-behaviour) · 12. [Requirements Summary](#12-requirements-summary) · 13. [Closing](#13-closing)

---

## 1. Purpose and Scope

The audit trail is the platform's evidence of its own correctness and the tenant's evidence to their auditors. Both uses fail identically if the record can be altered, and the second fails if it cannot be produced on demand.

**In scope.** Event envelope and typed payloads; the auditable action catalogue; hash chain integrity and independent anchoring; the reconciliation of erasure obligations with immutability; retention and legal hold; access control on the trail; investigative search; external export; failure behaviour.

**Out of scope.** Physical schema (DOC-04 §20.1, which specifies the tables); operational logging (DOC-06 §16); authorization semantics (DOC-07); threat analysis (DOC-26).

**LC-01.** Requirements are `SEC-AUD-nnn`. `PRD-AUD-001` – `010` in DOC-01 state *what* must be true; this document specifies *how*.

---

## 2. Event Envelope

A fixed envelope with a typed payload. The envelope is specified before the catalogue so that integrity, scope recording, actor attribution, and erasure separation apply to every event type automatically.

```
AuditEvent
  ── identity ──────────────────────────────────────
  event_id            uuid
  tenant_id           uuid
  sequence            bigint          monotonic per tenant, gapless
  ── what ──────────────────────────────────────────
  event_type          text            from the catalogue (§3)
  outcome             SUCCESS | DENIED | FAILED
  denial_reason       text?           full fidelity; never returned to a client
  object_kind         text?
  object_id           uuid?
  ── who ───────────────────────────────────────────
  actor_id            uuid?
  actor_type          USER | SERVICE | AUTOMATION | SYSTEM
  on_behalf_of_id     uuid?           delegation (SEC-AUZ-044)
  automation_rule_id  uuid?
  break_glass_ref     uuid?
  source_context      jsonb           address, user agent, request id
  ── when ──────────────────────────────────────────
  occurred_at         timestamptz
  ── where (scope as it was) ───────────────────────
  scope_node_id       uuid?
  scope_ancestor_path uuid[]
  scope_hierarchy_ver bigint
  ── integrity ─────────────────────────────────────
  payload_hash        bytea
  prev_chain_hash     bytea
  chain_hash          bytea
  ── erasure ───────────────────────────────────────
  payload_erased_at   timestamptz?
  payload_erasure_basis text?

AuditEventPayload                     separate, erasable
  event_id            uuid
  payload             jsonb           before/after values, request detail
```

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-AUD-001` | Every audit event MUST use the fixed envelope with a typed payload. A new event type MUST NOT introduce envelope fields. | The envelope is where integrity, scope, attribution, and erasure separation live. An event type adding envelope fields would need its own integrity treatment, and the exception would spread. | M | AT, CR |
| `SEC-AUD-002` | The sequence MUST be monotonic and gapless per tenant, and a gap MUST be treated as a verification failure. | A gapless sequence is what makes removal detectable independently of the hash chain — two mechanisms rather than one, because the chain alone cannot distinguish a removed tail from a shorter history. | M | AT |
| `SEC-AUD-003` | Every event MUST record the organizational scope in effect at the time, independently of the current hierarchy. | Without it, reorganization retroactively changes who appears to have been authorized for a past action, making the trail unusable as evidence about the prior period (`PRD-AUD-004`). | M | AT |
| `SEC-AUD-004` | Actor type MUST distinguish human, service, automation, and platform process, and where an action was performed on behalf of another the initiating principal MUST also be recorded. | An action attributed to "system" is unattributable. Where automation acts, the rule and its owning principal must both be recoverable or an automated escalation has no traceable origin. | M | AT |
| `SEC-AUD-005` | `denial_reason` MUST be recorded at full fidelity and MUST NOT be returned to a client. | Denial detail is the primary signal of probing and of misconfiguration; returning it is an existence oracle (`SEC-AUZ-020`). | M | AT, PT |

---

## 3. Auditable Action Catalogue

Ten categories. An action not in the catalogue is not audited, which is why the catalogue is a requirement rather than a list.

| Category | Events |
|---|---|
| **Authentication** | `auth.succeeded`, `auth.failed`, `auth.step_up.succeeded`, `auth.step_up.failed`, `auth.throttled`, `session.created`, `session.revoked`, `session.expired` |
| **Authorization** | `authz.denied`, `role.created`, `role.updated`, `role.permission.changed`, `assignment.granted`, `assignment.revoked`, `object_grant.issued`, `object_grant.revoked`, `object_grant.expired`, `delegation.created`, `delegation.expired`, `sod_constraint.changed`, `sod_constraint.relaxed` |
| **Domain state change** | Per aggregate: `created`, `updated`, `transitioned`, `assigned`, `merged`, `retired`, `reopened`. Enumerated per aggregate in the machine-readable catalogue |
| **Restricted data access** | `credential.revealed`, `secret.revealed`, `evidence.retrieved`, `workload.member_data.accessed`, `audit.read` |
| **Configuration** | `org_node_type.changed`, `workflow.changed`, `workflow.activated`, `automation_rule.changed`, `scoring_model.changed`, `scoring_model.activated`, `sla_policy.changed`, `taxonomy.changed`, `attribute_schema.changed`, `ai_configuration.changed`, `connector.configured`, `connector.credential.rotated`, `entitlement.changed`, `retention.changed` |
| **Bulk and export** | `bulk.executed` (per item plus a summary), `export.generated`, `report.generated`, `configuration.exported`, `configuration.imported`, `tenant_data.exported`, `access_review.exported` |
| **Ingestion** | `import.started`, `import.completed`, `import.reversed`, `sbom.submitted`, `sbom.rejected`, `match_run.completed`, `record.quarantined`, `migration.executed` |
| **AI** | `ai.invoked`, `ai.suggestion.generated`, `ai.suggestion.promoted`, `ai.suggestion.dismissed`, `ai.redaction.applied` |
| **Privileged and platform** | `break_glass.requested`, `break_glass.approved`, `break_glass.activated`, `break_glass.expired`, `enforcement_bypass.used`, `erasure.executed`, `legal_hold.applied`, `legal_hold.released`, `key.rotated`, `key.destroyed`, `migration.schema.applied`, `integrity.verified`, `integrity.failed` |
| **Tenant lifecycle** | `tenant.provisioned`, `tenant.suspended`, `tenant.reactivated`, `tenant.offboarding.started`, `tenant.offboarded` |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-AUD-006` | The catalogue MUST be maintained as a machine-readable artifact, and every audit-emitting code path MUST reference a catalogued type. An uncatalogued type MUST fail the build. | A trail whose coverage is undefined cannot be assessed for sufficiency, and gaps are discovered during an audit rather than before one. Build failure is what keeps the catalogue current. | M | AT |
| `SEC-AUD-007` | Every access to `RESTRICTED` data MUST be recorded at object granularity with the specific object and field. | For restricted data the *read* is the sensitive event: nothing changes when someone reveals a credential, yet that read is what an investigation needs. "User viewed findings" is unusable; "user revealed the credential on request PT-0142 at 03:12" is (`PRD-AUD-003`). | M | AT |
| `SEC-AUD-008` | Configuration changes MUST record before and after values, excluding secret material. | Configuration change is the least-visible escalation path in the platform (DOC-26 T9) and does not appear in a permission review. | M | AT |
| `SEC-AUD-009` | Bulk operations MUST emit an event per item in addition to a summary event. | A bulk action is many decisions, each of which may later need justification. A summary alone cannot answer which items were affected. | M | AT |
| `SEC-AUD-010` | Read operations on non-restricted data MUST NOT be audited by default. | Auditing every read would multiply trail volume by two orders of magnitude, and the resulting cost creates pressure to reduce coverage — sacrificing the events that matter for events that do not. Volume anomaly detection (`SEC-PLT-002`) operates on telemetry rather than audit. | M | AR |

**On `SEC-AUD-010`.** This is the one place the document deliberately limits coverage, and the reasoning is that unbounded coverage produces pressure to cut it. Restricted-data reads are audited (`SEC-AUD-007`); ordinary reads are not.

---

## 4. Integrity

### 4.1 The chain

```
chain_hash(n) = H( chain_hash(n−1) ‖ canonical(envelope(n)) ‖ payload_hash(n) )
chain_hash(0) = H( tenant_id ‖ genesis_marker )
```

`canonical(envelope)` is a deterministic serialization of every envelope field except the integrity and erasure fields, with a stated field order, canonical numeric and timestamp forms, and no whitespace variance. `H` is a current recommended cryptographic hash.

**The chain covers `payload_hash`, not the payload.** This is the mechanism that reconciles erasure with verifiability (§6).

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-AUD-011` | The canonical serialization MUST be deterministic and versioned, and the version MUST be recorded per event. | Any variance — field order, numeric form, whitespace — makes verification fail on unaltered data. Versioning permits the format to change without invalidating history. | M | AT |
| `SEC-AUD-012` | The chain MUST be per tenant, and a cross-tenant chain MUST NOT be constructed. | A shared chain makes one tenant's verification depend on another's events, and it would make a single tenant's offboarding break every other tenant's chain. | M | AT |
| `SEC-AUD-013` | No mechanism MUST exist to modify or delete an audit event: not through the application, the API, administrative tooling, or an operator interface. Deletion MUST be possible only by partition drop under retention (§7). | An audit facility with an administrative delete is a log, not an audit facility, and its evidential weight in a dispute is correspondingly limited (`INV-AUD-01`). | M | AT, CR |
| `SEC-AUD-014` | Chain computation MUST occur in the same transaction as the event insert, and a concurrent insert MUST NOT produce two events with the same sequence or a forked chain. | A forked chain is undetectable as tampering and unrepairable. Serialization of the chain head per tenant is required, which is a deliberate write-throughput cost. | M | AT |

**On `SEC-AUD-014`.** Per-tenant chain head serialization bounds audit write throughput to one event at a time per tenant. This is accepted: `NFR-AUD-001` budgets 15 ms at p95 for the audit write, and a single tenant's audited operation rate is well below the contention threshold at the volumes of DOC-01 §12.1. It is recorded because it is a genuine scalability limit and the point at which it would bind is worth knowing.

### 4.2 Verification

Three levels, because the cost differs by two orders of magnitude:

| Level | Checks | Cost |
|---|---|---|
| **Spot** | Sequence continuity and chain linkage over a bounded range | Cheap; runs continuously |
| **Range** | Full recomputation over a period against a checkpoint | Moderate; on demand and scheduled |
| **Full** | Recomputation from genesis | Expensive; on suspicion or for audit |

---

## 5. Anchoring and Verification

Verification material stored only alongside the events it protects is defeated by the same adversary who could alter them — including a compromise of the platform itself (DOC-26 T10).

```
AuditChainCheckpoint
  tenant_id, sequence, chain_hash, event_count,
  checkpointed_at, anchor_target, anchor_reference, anchor_confirmed_at
```

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-AUD-015` | Checkpoints MUST be created at a configured interval and MUST be anchored to a target outside the platform's control, with the anchor reference recorded and confirmation tracked. | Without an external anchor, an adversary with sufficient access can rewrite history and recompute the chain consistently. The anchor is what makes that detectable (`INV-AUD-02`). | M | AT |
| `SEC-AUD-016` | Anchor failure MUST alert and MUST NOT halt audit writing. | Halting on anchor failure converts an external dependency outage into a platform write outage (`CON-PLT-021` already trades availability for integrity; compounding it is not warranted). The gap in anchoring is recorded and alerted. | M | AT |
| `SEC-AUD-017` | Verification MUST be invocable by a tenant over their own trail, and the result MUST be reportable as evidence. | An auditor asking whether the trail has been altered needs an answer that does not rest on the platform operator's assurance. | M | AT |
| `SEC-AUD-018` | A verification failure MUST alert to a destination outside operator control, MUST be recorded as an audit event, and MUST NOT be suppressible in-product. | A verification failure is the single most serious signal the platform can produce, and the party most likely to suppress it is the one with platform access (`SEC-PLT-008`). | M | AT |

**Anchor targets.** Any of: append-only external storage under tenant control; a customer-nominated log service; a distributed timestamping service; or, in air-gapped deployment, an operator-attested offline record. The last is weaker and MUST be labelled as such in the verification report rather than presented as equivalent.

---

## 6. Erasure Reconciliation

`PRD-AUD-009`. A genuine conflict: audit events must be immutable; personal data must be erasable; events reference and sometimes contain personal data.

**The two failure modes are equally bad.** Refusing erasure to preserve immutability is a compliance breach. Deleting events to satisfy erasure destroys the chain, invalidating the trail for every other purpose, unrepairably.

**The mechanism.** Erasure deletes the `AuditEventPayload` row and sets `payload_erased_at` and `payload_erasure_basis` on the event. Because the chain covers `payload_hash` rather than the payload, every `chain_hash` is unchanged and every link remains verifiable.

**What remains provable after erasure.** That an event of this type, by this actor, on this object, at this time, in this scope, with a payload whose hash was X, occurred — and that no event has been inserted, removed, or reordered.

**What is lost, stated plainly.** It is no longer possible to verify that the erased payload matched `payload_hash`. That is the honest limit of the design, and it is sufficient for the purposes the trail serves.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-AUD-019` | Erasure MUST remove only the payload, MUST leave the envelope and chain intact, and MUST record that erasure occurred with its basis. | Recording the erasure preserves the trail's completeness as a record of what happened, including the removal. | M | AT |
| `SEC-AUD-020` | Verification MUST report erased payloads distinctly from missing or altered data, and MUST NOT report an erased payload as a verification failure. | Conflating the two makes erasure indistinguishable from tampering, which either produces false alarms or trains the reader to ignore real ones. | M | AT |
| `SEC-AUD-021` | Erasure MUST require elevated permission distinct from administration, MUST be dual-controlled, MUST record the requesting basis, and MUST be blocked by legal hold. | Erasure is irreversible and is the plausible mechanism for an insider covering activity (`SEC-AUZ-042`). | M | AT |
| `SEC-AUD-022` | The envelope MUST NOT contain personal data beyond principal identifiers, which are pseudonymized rather than erased. | If the envelope contained personal data it would itself require erasure, and erasing it breaks the chain. Keeping the envelope free of it is what makes the design work. | M | AT, CR |

**On `SEC-AUD-022`.** This constrains what may go in the envelope: source address, user agent, and any free text belong in the payload. The envelope carries identifiers and structure only. Principal records are pseudonymized under erasure (`CON-DAT-037`), so an identifier remains resolvable to a stable pseudonym and attributions survive.

---

## 7. Retention and Legal Hold

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-AUD-023` | Retention MUST be tenant-configurable above a product minimum, and the minimum MUST NOT be configurable downward. | A tenant configuring thirty days has no audit capability, and would do so under storage cost pressure. | M | AT |
| `SEC-AUD-024` | Expiry MUST be implemented as partition drop after archival, and archived events MUST remain verifiable against their checkpoints. | Deleting hundreds of millions of rows is a sustained load event competing with operational traffic (`CON-DAT-023`). Archived events remaining verifiable is what makes archival distinct from deletion. | M | AT |
| `SEC-AUD-025` | Legal hold MUST be enforced by a register consulted before partition drop and before erasure, not by a per-row flag. | A per-row hold cannot prevent a partition drop, and partition drop is the retention mechanism — a row-level flag would be silently ineffective (`CON-DAT-029`). | M | AT |
| `SEC-AUD-026` | Retention configuration changes MUST be audited and MUST NOT apply retroactively to shorten retention of already-recorded events without a separate elevated action. | Otherwise shortening retention is a one-configuration-change route to destroying history, which is the same capability as erasure without its controls. | M | AT |

**On `SEC-AUD-026`.** This closes a gap that would otherwise make §6's erasure controls circumventable: an actor unable to erase could instead reduce retention and wait. Separating the two means both require deliberate, controlled action.

---

## 8. Access Control

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-AUD-027` | Audit read MUST require a permission not implied by administrative permission, and MUST be separately assignable. | A principal who can both act and read the record of action can verify their action was recorded; one who can also configure audit is a full separation-of-duties failure (`PRD-AUD-007`). | M | AT |
| `SEC-AUD-028` | Audit queries MUST be scope-filtered against the querying principal's authorized scope. | An unfiltered audit query is an unrestricted read path around every other control: it reveals which findings exist, on which assets, in which business units. | M | AT, PT |
| `SEC-AUD-029` | Audit read MUST itself be audited. | The trail records who examined which vulnerability, which reveals investigative and operational focus — sensitive in its own right. | M | AT |
| `SEC-AUD-030` | Audit events MUST NOT contain restricted values. A reveal event records that a reveal occurred, never what was revealed. | Otherwise the audit trail becomes the largest single collection of the platform's most sensitive data, with a different access control path (`PRD-AUD-003`). | M | AT, CR |
| `SEC-AUD-031` | Break-glass activity MUST be visible to the affected tenant at object granularity, and that visibility MUST NOT be suppressible by the operator. | Notification and visibility are what make break-glass accountable rather than merely logged (`SEC-TEN-029`, `-030`). | M | AT |

---

## 9. Search and Investigation

An audit trail that cannot be queried within an investigation's constraints is an archive.

| Query | Support |
|---|---|
| What happened to this object | Object index, newest first |
| What did this principal do | Actor index, newest first |
| Who accessed this restricted object | Event type plus object |
| What changed in configuration this period | Category filter with before and after |
| Which denials came from this principal | Outcome-filtered actor index |
| What happened under this break-glass grant | Grant reference index |
| Everything in this scope this period | Scope containment plus time range |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-AUD-032` | Audit search MUST return within 5 seconds at p95 over a 12-month range at the Medium profile. | `NFR-AUD-001`. Both routine access review and incident investigation require interactive query, not a batch extract. | M | AT |
| `SEC-AUD-033` | Search MUST support export of a result set as evidence, with the export itself audited and the applied scope and filters recorded in the artifact. | An evidence export whose scope and filters are not recorded cannot be relied upon by the auditor receiving it. | M | AT |
| `SEC-AUD-034` | Search MUST NOT support a free-form expression language over the trail. | An expression language over audit content is an injection surface with unbounded cost against the platform's largest table, and it cannot be validated against the index set (`PRD-API-025`). | M | AR |

---

## 10. External Export

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-AUD-035` | The platform MUST support continuous export of audit events to a tenant-nominated external system in a documented format, with at-least-once delivery, gap detection, and sequence preservation. | Tenants retain audit data beyond the platform's retention and correlate it with other sources. External retention is also the tenant's own control against platform compromise, which is a legitimate concern for a platform holding this data. | M | AT |
| `SEC-AUD-036` | Exported events MUST carry the sequence, chain hash, and envelope so that the external copy is independently verifiable. | An external copy that cannot be verified is a convenience, not a control. Carrying the chain material makes the external copy an independent check on the internal trail. | M | AT |
| `SEC-AUD-037` | Export MUST be residency-constrained per `SEC-TEN-018` and MUST exclude payloads where the destination is outside the tenant's residency designation. | Audit export is one of the nine secondary residency paths of DOC-24 §8.1 and is frequently overlooked because it is treated as operational rather than as data movement. | M | AT |

---

## 11. Failure Behaviour

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-AUD-038` | An audited operation MUST NOT be acknowledged as successful unless its audit event is durably recorded. Where audit recording fails, the operation MUST fail. | A trail with gaps of unknown extent has limited evidential value, and the gap is undetectable afterwards. This is the platform's single deliberate trade of availability for integrity (`CON-PLT-021`, `NFR-AUD-002`). | M | AT |
| `SEC-AUD-039` | Audit unavailability MUST produce a clear service-unavailable response naming audit as the degraded dependency, not a generic error. | An operator diagnosing a write outage must be able to identify the cause immediately; the alternative is an investigation of the application while the cause is a dependency. | M | AT |
| `SEC-AUD-040` | Missing future partitions MUST alert in advance of need and MUST NOT be permitted to cause an insert failure. | A missing partition rejects audit inserts, which under `SEC-AUD-038` fails every audited operation — a total write outage from an omitted maintenance task (`CON-DAT-025`). | M | AT |

**The cost of `SEC-AUD-038`, stated.** An audit store outage becomes a write outage. Accepted: a platform continuing to accept changes it cannot record is producing an unreliable record, and an unreliable record is worse for the customer than a brief outage. Mitigation is availability of the audit store, not relaxation of the rule.

---

## 12. Requirements Summary

Forty requirements, `SEC-AUD-001` – `040`, all `MUST_HAVE`.

| Group | IDs | Count |
|---|---|---|
| Envelope | `001` – `005` | 5 |
| Catalogue | `006` – `010` | 5 |
| Integrity | `011` – `014` | 4 |
| Anchoring | `015` – `018` | 4 |
| Erasure | `019` – `022` | 4 |
| Retention | `023` – `026` | 4 |
| Access control | `027` – `031` | 5 |
| Search | `032` – `034` | 3 |
| External export | `035` – `037` | 3 |
| Failure | `038` – `040` | 3 |

Satisfies `PRD-AUD-001` – `010`, the ASVS Level 3 audit uplift of `SEC-SEC-001`, and `INV-AUD-01` – `06`.

---

## 13. Closing

### 13.1 Extensibility

The envelope is fixed and the payload is typed, so a new event type is a catalogue entry plus a payload schema — integrity, scope, attribution, and erasure separation apply automatically (`SEC-AUD-001`). Anchor targets are pluggable. Export formats are serializers over the same event stream.

**Deliberate rigidity.** No modification or deletion path (`SEC-AUD-013`); the chain covers payload hashes (`SEC-AUD-011`); the envelope carries no personal data (`SEC-AUD-022`); no expression language (`SEC-AUD-034`); audit failure fails the operation (`SEC-AUD-038`).

**Known extension costs.** Per-tenant chain head serialization bounds audit write throughput (§4.1). Adding an envelope field requires a canonical serialization version and makes cross-version verification a two-format operation. Auditing a previously unaudited read path multiplies volume and must be assessed against retention cost.

### 13.2 Residual risks

*Anchor strength varies.* In air-gapped deployment the anchor is operator-attested, which is materially weaker than an external service. `SEC-AUD-015` requires it to be labelled as such rather than presented as equivalent, but the weakness remains and is the trail's least defensible property in that deployment model.

*Erasure limits verifiability of erased content.* By construction (§6). Accepted and documented rather than concealed.

*Read coverage is deliberately partial.* `SEC-AUD-010` excludes ordinary reads. An insider reading broadly without revealing restricted data leaves no audit trace, only telemetry. Volume anomaly detection (`SEC-PLT-002`) is the compensating control and it is detective rather than evidential — a distinction that matters if the evidence is later needed in a dispute.

### 13.3 Notes for downstream documents

| Document | Note |
|---|---|
| DOC-09 | The break-glass and erasure state machines emit the privileged events of §3 |
| DOC-12 | Audit evidence export (`SEC-AUD-033`) is a report type; the artifact must record scope and filters |
| DOC-15 | Owes audit store availability commensurate with `SEC-AUD-038`, anchor target configuration, partition automation, and the alert destination of `SEC-AUD-018` |
| DOC-16 | Owes: chain verification across an erasure (`SEC-AUD-020`); a test asserting no update or delete path exists at any privilege; a concurrent-insert test asserting no forked chain (`SEC-AUD-014`); a gap-detection test; and an audit search performance test at the Medium profile |
| DOC-21 | Audit export is a connector under DOC-21's contract |

### 13.4 Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.0.0 | 2026-08-04 | Principal Security Architect; Chief Software Architect | Initial content-complete version. Specifies a fixed envelope with typed payloads so that integrity, scope, attribution, and erasure separation apply to every event type; a ten-category action catalogue enforced at build time; a per-tenant hash chain over payload hashes with gapless sequence as a second detection mechanism; three verification levels; external anchoring with air-gapped attestation labelled as weaker; the erasure reconciliation with what remains provable and what is lost both stated; retention with the shortening-as-erasure-circumvention gap closed; access control with audit read separately permissioned and itself audited; investigative search with no expression language; verifiable external export; and failure behaviour including the deliberate trade of availability for integrity with its cost stated. Forty requirements. | Pending |

---

*End of DOC-14.*
