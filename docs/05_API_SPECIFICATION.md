---
document_id:    DOC-05
title:          API Specification
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        1.0.0
status:         In review
owner:          Chief Software Architect
authors:        [Chief Software Architect, Principal Security Architect]
reviewers:      []
last_updated:   2026-08-04
tier:           4
prerequisites:  [DOC-00, DOC-01, DOC-03, DOC-04, DOC-07, DOC-24]
depends_on:     [DOC-00, DOC-01, DOC-02, DOC-03, DOC-04, DOC-07, DOC-22, DOC-24, DOC-28]
supersedes:     null
adrs_relied_on: [ADR-004, ADR-005, ADR-023, ADR-024, ADR-027, ADR-028]
open_questions: [OQ-015]
requirement_domains: [API]
security_review_required: true
---

# 05 — API Specification

> **Note on form.** This document specifies ~130 operations. Rather than repeating nine security annotations per operation (DOC-00 §15.1), it defines **annotation classes** (§5) and assigns each operation a class, annotating only deviations. This makes the common case uniform and the exceptions visible, and is the reason the document is shorter than its original 105–133 page estimate. No specification content is omitted.

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope) · 2. [Conventions](#2-conventions) · 3. [Authentication](#3-authentication) · 4. [Versioning and Deprecation](#4-versioning-and-deprecation) · 5. [Security Annotation Classes](#5-security-annotation-classes) · 6. [Errors](#6-errors) · 7. [Collections](#7-collections) · 8. [Idempotency](#8-idempotency) · 9. [Asynchronous Operations](#9-asynchronous-operations) · 10. [Bulk Operations](#10-bulk-operations) · 11. [Rate Limiting](#11-rate-limiting) · 12. [Organization and Scope](#12-organization-and-scope) · 13. [Assets](#13-assets) · 14. [Assessments and Requests](#14-assessments-and-requests) · 15. [Findings](#15-findings) · 16. [Exceptions](#16-exceptions) · 17. [Composition Analysis](#17-composition-analysis) · 18. [Work Management](#18-work-management) · 19. [Risk and Service Levels](#19-risk-and-service-levels) · 20. [Capacity](#20-capacity) · 21. [Insight and Reporting](#21-insight-and-reporting) · 22. [AI](#22-ai) · 23. [Ingestion](#23-ingestion) · 24. [Administration](#24-administration) · 25. [Webhooks](#25-webhooks) · 26. [Requirements](#26-requirements) · 27. [Closing](#27-closing)

---

## 1. Purpose and Scope

Specifies the programmatic contract: authentication, common protocols, every operation with its security annotations, and webhooks.

**In scope.** Resource model, operations, request and response schemas, error taxonomy, pagination, idempotency, async jobs, bulk semantics, rate limiting, webhooks, per-operation security annotations.

**Out of scope.** Authorization semantics (DOC-07), isolation mechanics (DOC-24), physical schema (DOC-04), state machines (DOC-09), transport and infrastructure (DOC-15).

**LC-01.** Requirements are `PRD-API-015` onward, continuing DOC-01's sequence which ended at `PRD-API-014`.

**LC-02.** Schemas are described by field, type, and constraint. The machine-readable specification (`PRD-API-001`) is generated from the implementation and is authoritative for exact serialization.

---

## 2. Conventions

| Element | Convention | Example |
|---|---|---|
| Base path | `/v{major}` | `/v1` |
| Collection | plural kebab-case | `/v1/pentest-requests` |
| Path parameter | `{snake_case}` | `/v1/findings/{finding_id}` |
| Query parameter | `snake_case` | `?severity_min=HIGH` |
| Body field | `snake_case` | `"golive_date"` |
| Enum value | `SCREAMING_SNAKE_CASE` | `"INTERNET_PUBLIC"` |
| Sub-resource action | noun where possible; verb only for true actions | `POST /v1/scan-batches/{id}/cancel` |
| Timestamp | RFC 3339, UTC, `Z` suffix | `2026-08-04T09:12:00Z` |
| Date | ISO 8601 date | `2026-09-01` |
| Identifier | UUID string | |
| Money | Absent — the platform holds no monetary values | |

**Tenant is never a parameter.** It is established from the credential (`SEC-TEN-004`). No path, query, header, or body field carries it.

---

## 3. Authentication

Per ADR-004. Three mechanisms, deliberately not four.

| Mechanism | For | Binding |
|---|---|---|
| OIDC / OAuth2 authorization code | Human principals via the interface | Session cookie or bearer token, sender-constrained where the client supports it |
| OAuth2 client credentials, **sender-constrained** (mTLS or DPoP) | Service principals | Credential-pinned scope (`SEC-AUZ-035`) |
| HTTP message signature with nonce and timestamp | Legacy CI unable to do the above | Signed request; replay-protected |

**No unconstrained bearer API keys** (`PRD-IAM-009`).

**Signature scheme.** `Signature-Input` and `Signature` headers over method, path, host, `Content-Digest`, `Signature-Date`, and `Signature-Nonce`. Acceptance window ±300 s; nonces retained for twice the window and rejected on repeat.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-015` | Signed requests MUST be rejected outside the acceptance window or on a repeated nonce, and the nonce store MUST be tenant-namespaced. | A signed request without replay protection is a reusable credential for anyone who observes it. A shared nonce namespace is a cross-tenant collision surface. | M | AT, PT |
| `PRD-API-016` | Step-up authentication MUST be required for: restricted-data reveal, elevated exception approval, authorization or workflow configuration change, erasure, and break-glass. A request lacking a recent step-up MUST return `401` with `step_up_required`. | A session established hours earlier is weak evidence that the person currently acting is the authenticated principal (`PRD-IAM-003`). | M | AT |

---

## 4. Versioning and Deprecation

**Path major version.** Additive changes ship within a version; breaking changes require a new major version.

| Change | Breaking |
|---|---|
| New optional field in a request | No |
| New field in a response | No |
| New optional query parameter | No |
| New endpoint | No |
| New enum value in a **response** | **Yes** — clients switch on them |
| Removing or renaming any field | Yes |
| Making an optional request field required | Yes |
| Narrowing a type or tightening validation | Yes |
| Changing pagination or error semantics | Yes |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-017` | A new enum value in a response MUST be treated as breaking unless the field is documented as open-ended at introduction and clients are contractually required to tolerate unknown values. | Clients branch on enum values, and an unexpected value produces a runtime failure in a CI pipeline nobody is watching. Declaring openness at introduction is the only safe way to add values later. | M | DI |
| `PRD-API-018` | Deprecation MUST be announced via `Deprecation` and `Sunset` headers on every affected response, with a minimum 12-month window after announcement, and usage of deprecated operations MUST be reportable per client. | Consumers include pipelines nobody will proactively update. Per-client usage reporting is what makes it possible to contact the affected consumers rather than guessing. | M | AT |

---

## 5. Security Annotation Classes

DOC-00 §15.1 requires nine annotations per operation. They are defined as classes here; §12–§24 assign a class per operation and note deviations only.

| Class | Auth | Authorization | Scope re-validation | Rate class | Replay | Audit | Classification |
|---|---|---|---|---|---|---|---|
| **A — scoped read** | Any | Named permission + scope predicate applied **in retrieval** | Every path identifier re-validated | `READ` | N/A (idempotent) | Read event for `RESTRICTED` only | `CONFIDENTIAL` |
| **B — scoped write** | Any | Named permission + scope predicate | Every identifier in path **and body** re-validated independently of provenance | `WRITE` | Idempotency key | State-change event | `CONFIDENTIAL` |
| **C — restricted reveal** | Any + **step-up** | Dedicated `RESTRICTED` permission, never implied | As B | `SENSITIVE` | N/A | **Per-object read event** | `RESTRICTED` |
| **D — bulk / export** | Any | Distinct bulk or export permission + **per-item** evaluation | Per item | `BULK` | Idempotency key | Event with scope and volume | `CONFIDENTIAL` |
| **E — configuration** | Any + **step-up** | Elevated permission distinct from operational | As B | `WRITE` | Idempotency key | Event with **before and after** values | `INTERNAL` |
| **F — service ingest** | Service credential only | Credential-pinned scope; payload scope **never trusted** | Payload references re-validated against pinned scope | `INGEST` | Idempotency key + nonce for signed | Ingest event | `CONFIDENTIAL` |
| **G — unauthenticated** | None | None | N/A | `ANON` | N/A | Failure events only | `PUBLIC` |

**Universal rules, applying to every class.** Deny by default. Denials do not differentiate non-existence from non-authorization, in status, code, message, or timing (`SEC-AUZ-020`). Request bodies validated against a declared schema with size, depth, and element limits, rejecting unknown fields. Restricted fields **absent**, not masked (`SEC-AUZ-022`). No credential, secret, or evidence content in any response, at any permission level.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-019` | Every operation MUST be assigned an annotation class, and a new operation MUST NOT be introduced without one. | The class assignment is the mechanism that prevents an operation shipping without its security characteristics considered (`SEC-AUZ-049`). | M | DI, CR |
| `PRD-API-020` | Requests MUST reject unknown fields rather than ignoring them. | A silently ignored field means a client typo produces a no-op the client believes succeeded. | M | AT |
| `PRD-API-021` | Response latency for a denial MUST be indistinguishable from a denial for a non-existent object. | A lookup-then-deny path differs measurably from an immediate deny, which is a reliable existence oracle. | M | AT, PT |

---

## 6. Errors

RFC 9457 problem details. Stable machine-readable `code`; `detail` never contains internal implementation detail or data.

```json
{ "type": "https://errors.example/scope-violation",
  "title": "Not found", "status": 404,
  "code": "RESOURCE_NOT_FOUND",
  "detail": "The requested resource does not exist or is not accessible.",
  "instance": "/v1/findings/018f...", "trace_id": "01J..." }
```

| Code | Status | Meaning |
|---|---|---|
| `AUTHENTICATION_REQUIRED` | 401 | |
| `STEP_UP_REQUIRED` | 401 | With `step_up_methods` |
| `PERMISSION_DENIED` | 403 | Permission absent; **object existence not implied** |
| `RESOURCE_NOT_FOUND` | 404 | **Returned for both non-existence and scope violation** |
| `VALIDATION_FAILED` | 422 | With per-field `errors[]` |
| `QUALITY_THRESHOLD_NOT_MET` | 422 | SBOM submission; per-criterion detail |
| `PRECONDITION_FAILED` | 412 | Optimistic concurrency (`row_version`) |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | Same key, different payload |
| `STATE_TRANSITION_INVALID` | 409 | Not permitted from the current state |
| `INVARIANT_VIOLATION` | 409 | With the invariant identifier |
| `ENTITLEMENT_EXCEEDED` | 402 | Never disables a security control (`LIC-PLT-005`) |
| `RATE_LIMIT_EXCEEDED` | 429 | With `Retry-After` |
| `PAYLOAD_TOO_LARGE` | 413 | |
| `DEPENDENCY_DEGRADED` | 503 | With the degraded capability named (PP-9) |

**`PERMISSION_DENIED` versus `RESOURCE_NOT_FOUND`.** `403` is returned only where the permission is absent regardless of any object — an operation the principal cannot perform anywhere. Where an object is named and is outside scope, `404` is returned, identical to non-existence.

---

## 7. Collections

**Keyset pagination only.** Offset pagination silently skips and duplicates rows under concurrent modification (`PRD-API-003`), and full extraction is the most common API use.

```
GET /v1/findings?page_size=100&page_token=...
→ { "items": [...], "next_page_token": "...", "page_size": 100 }
```

No total count by default. `?include_total=true` returns `total_count` computed **after** scope filtering, subject to the aggregate disclosure rules of `SEC-AUZ-026`.

**Filtering.** Typed query parameters, not a query language: `?state=OPEN&severity_min=HIGH&scope_node_id=...&updated_after=...`. Repeated parameters are `OR`; distinct parameters are `AND`.

**Sorting.** `?sort=-risk_score,created_at`. Only fields backed by an index (DOC-04 §9) are sortable; others return `VALIDATION_FAILED`.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-022` | Collections MUST use keyset pagination, and the sort key MUST include a unique tiebreaker so ordering is total. | Without a tiebreaker, rows with equal sort values may appear on two pages or none. | M | AT |
| `PRD-API-023` | Total counts MUST be computed after scope filtering and MUST be opt-in. | A pre-filter count discloses out-of-scope volume; making it opt-in keeps the default path cheap. | M | AT, PT |
| `PRD-API-024` | Sortable fields MUST be restricted to those backed by an index, and an unsupported sort MUST be rejected rather than served by a scan. | An unindexed sort on the finding table at Extra large is a scan that affects every other request. | M | AT |
| `PRD-API-025` | A filter expression language MUST NOT be exposed. Filtering MUST be by typed parameters. | An expression language over the schema is an injection surface, is unboundable in cost, and cannot be validated against the index set. | M | AR |

---

## 8. Idempotency

`Idempotency-Key` header, required on every non-idempotent write. Server retains key, request digest, and outcome for 24 hours; a repeat returns the original outcome. Same key with a different digest returns `409 IDEMPOTENCY_KEY_CONFLICT`. Keys are tenant-namespaced (DOC-24 §6.2 entry 16).

---

## 9. Asynchronous Operations

Operations exceeding a request budget return a job reference.

```
POST /v1/exports  → 202 { "job_id": "...", "status": "QUEUED",
                          "status_url": "/v1/jobs/{job_id}" }
GET /v1/jobs/{job_id} → { "status": "RUNNING|SUCCEEDED|FAILED|CANCELLED",
                          "progress": { "total": 50000, "completed": 12000 },
                          "result": { "download_url": "...", "expires_at": "..." },
                          "error": { "code": "...", "detail": "..." } }
POST /v1/jobs/{job_id}/cancel → 202
```

**Async operations:** export, import, report generation, portfolio match sweep, bulk operations above threshold, configuration import, tenant data export.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-026` | Async result references MUST be authenticated, short-lived, single-tenant, and bound to the requesting principal's scope at generation. | A result reference is an uncontrolled artifact once issued; binding scope at generation prevents a later authorization change from being bypassed by an old link. | M | AT, PT |
| `PRD-API-027` | Job status MUST be readable only by the initiating principal or a principal with an administrative job permission, and MUST NOT disclose scope beyond the requester's. | Job progress discloses volume, and volume discloses scope breadth. | M | AT |

---

## 10. Bulk Operations

```
POST /v1/findings/bulk-triage
{ "item_ids": [...], "operation": { "state": "...", "closure_reason": "..." } }
→ 200 { "succeeded": 47, "failed": 3,
        "results": [ { "id": "...", "status": "SUCCEEDED" },
                     { "id": "...", "status": "FAILED", "code": "PERMISSION_DENIED" } ] }
```

Maximum 500 items per request. Above that, async.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-028` | Bulk operations MUST evaluate permission per item, MUST emit an audit event per item, and MUST report per-item outcome. A bulk operation MUST NOT succeed on an item the principal could not act on individually. | Otherwise bulk becomes a bypass for per-item denial — an escalation through a convenience feature (`INV-WRK-12`). | M | AT, PT |
| `PRD-API-029` | Bulk operations MUST be partial-success and MUST NOT roll back succeeded items on later failures. | All-or-nothing on 500 items means one permission denial discards 499 legitimate operations, which drives users to the export-and-spreadsheet workflow. | M | AT |

---

## 11. Rate Limiting

| Class | Scope | Limit | Applies to |
|---|---|---|---|
| `ANON` | Source address | 60 / min | Unauthenticated |
| `READ` | Principal | 1,200 / min | Class A |
| `WRITE` | Principal | 300 / min | Classes B, E |
| `SENSITIVE` | Principal | 30 / min | Class C — restricted reveal |
| `BULK` | Principal | 20 / min | Class D |
| `INGEST` | Service credential | 600 / min | Class F |
| `SEARCH` | Principal | 120 / min | Full-text search |
| `AI` | Tenant | Budget-based | AI capabilities (`PRD-AIC-011`) |

Headers: `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`, `Retry-After` on 429. Counters tenant-keyed.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-030` | The `SENSITIVE` class MUST be limited an order of magnitude below `READ`. | Restricted reveal is the highest-value action available to an insider (DOC-26 T4); a low limit makes bulk credential harvesting slow and visible. | M | AT |
| `PRD-API-031` | Limits MUST be set such that the documented reference integration pattern operates without throttling, and `INGEST` throttling MUST be surfaced in connector health. | A limit that throttles the documented pattern produces silent submission failure, which is how coverage gaps form (`PRD-SBM-059`). | M | AT |

---

## 12. Organization and Scope

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `GET /v1/org-nodes` | A | `org.node.read` | Filters: `parent_id`, `type_id`, `lifecycle_state`, `subtree_of` |
| `GET /v1/org-nodes/{id}` | A | `org.node.read` | |
| `POST /v1/org-nodes` | B | `org.node.create` | Parent must be in scope; type validity enforced |
| `PATCH /v1/org-nodes/{id}` | B | `org.node.update` | `row_version` required |
| `POST /v1/org-nodes/{id}/move` | E | `org.node.reorganize` | **Async.** Changes who can see what |
| `POST /v1/org-nodes/merge` | E | `org.node.reorganize` | Async |
| `POST /v1/org-nodes/{id}/split` | E | `org.node.reorganize` | Async; every asset must be apportioned |
| `POST /v1/org-nodes/{id}/archive` | B | `org.node.archive` | |
| `PUT /v1/org-nodes/{id}/criticality` | B | `org.criticality.assign` | Justification required |
| `GET/PUT /v1/org-nodes/{id}/owners` | B | `org.owner.assign` | |
| `GET /v1/org-nodes/{id}/subtree` | A | `org.node.read` | Closure-backed; depth-bounded |
| `GET/POST/PATCH /v1/org-node-types` | E | `org.nodetype.manage` | Reachability validated before activation |
| `GET/POST/PATCH /v1/criticality-tiers` | E | `org.nodetype.manage` | |

**Deviation.** Reorganization operations are async because they may touch thousands of closure rows and cannot complete within a request budget. Their audit event records the full before and after parentage.

---

## 13. Assets

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `GET /v1/assets` | A | `ast.asset.read` | Filters: `type_id`, `owning_node_id`, `subtree_of`, `lifecycle_state`, `exposure`, `criticality_tier_id`, `tags`, `unowned`, `exposure_conflict`, `updated_after` |
| `GET /v1/assets/{id}` | A | `ast.asset.read` | |
| `POST /v1/assets` | B | `ast.asset.create` | |
| `PATCH /v1/assets/{id}` | B | `ast.asset.update` | Type immutable; `last_confirmed_at` not settable |
| `POST /v1/assets/{id}/retire` | B | `ast.asset.retire` | |
| `POST /v1/assets/merge` | D | `ast.asset.merge` | Async; conflicting owners require explicit resolution |
| `POST /v1/assets/merges/{id}/reverse` | D | `ast.asset.merge` | Within the reversible window |
| `GET /v1/assets/{id}/relationships` | A | `ast.asset.read` | `?direction=OUT\|IN\|BOTH&edge_type=&as_of=` |
| `POST/DELETE /v1/asset-relationships` | B | `ast.asset.update` | Delete closes the edge; never removes the row |
| `GET /v1/assets/{id}/graph` | A | `ast.asset.read` | **Per-node scope filtering; branches silently terminated** (`SEC-AUZ-024`). Depth-bounded |
| `PUT /v1/assets/{id}/exposure` | B | `ast.exposure.declare` | Observed value not settable via API |
| `PUT /v1/assets/{id}/criticality` | B | `org.criticality.assign` | |
| `GET /v1/ownership-claims` | A | `ast.ownership.claim` | The unowned queue |
| `POST /v1/ownership-claims` | B | `ast.ownership.claim` | Authorized against the **proposed node** (`INV-AST-18`) |
| `POST /v1/ownership-claims/{id}/confirm` | B | `ast.ownership.claim` | |
| `POST /v1/ownership-claims/{id}/reject` | B | `ast.ownership.claim` | |
| `POST /v1/assets/{id}/transfer-ownership` | B | `ast.ownership.reassign` | |
| `GET/POST/PATCH /v1/asset-types` | E | `ast.assettype.manage` | |
| `GET/POST/PATCH /v1/attribute-schemas` | E | `ast.customfield.manage` | Searchable requires an index slot |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-032` | Graph traversal operations MUST filter per node reached, MUST terminate out-of-scope branches without indicating truncation, and MUST enforce a depth and breadth bound independent of the caller's scope. | The query begins legitimately, so query-level filtering does not help. Indicating truncation discloses that something exists out of scope; a bound varying with scope discloses scope breadth (`SEC-AUZ-024`, `-025`). | M | AT, PT |

---

## 14. Assessments and Requests

### 14.1 Requests

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `GET /v1/assessment-requests` | A | `asm.request.read.own` \| `.scope` | Own-only for requesters. Filters: `state`, `type_id`, `subtree_of`, `assignee_id`, `unassigned`, `golive_blocking`, `due_before`, `readiness_complete` |
| `GET /v1/assessment-requests/{id}` | A | as above | Credential references **absent** without `asm.credential.reveal` |
| `POST /v1/assessment-requests` | B | `asm.request.create` | **Project reference re-validated server-side** (`SEC-AUZ-018`) |
| `PATCH /v1/assessment-requests/{id}` | B | `asm.request.create` (own) \| `.triage` | Scope descriptor immutable after submission |
| `POST /v1/assessment-requests/{id}/submit` | B | `asm.request.create` | Validates: ≥2 accounts per role, readiness complete, bypass recorded where a control is present |
| `POST /v1/assessment-requests/{id}/accept` \| `/reject` \| `/defer` \| `/return-for-information` | B | `asm.request.triage` | |
| `POST /v1/assessment-requests/{id}/assign` | B | `asm.request.assign` | |
| `PUT /v1/assessment-requests/{id}/priority` | B | `asm.request.priority.override` | Distinct permission |
| `PUT /v1/assessment-requests/{id}/golive-blocking` | B | `asm.request.golive_blocking.set` | Distinct permission |
| `GET /v1/projects?intent=assessment_request` | A | `asm.request.create` | **Server-filtered; a usability feature, not a control** |
| `GET /v1/assessment-requests/{id}/feasibility` | A | `asm.request.create` | Earliest feasible start; warns where incompatible with the required date |
| `POST /v1/assessment-requests/{id}/check-duplicates` | A | `asm.request.create` | |
| `GET/POST /v1/request-groups` | B | `asm.request.create` | |

### 14.2 Role accounts and environments

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `GET /v1/assessment-requests/{id}/role-accounts` | A | request read | Usernames and status; **`credential_ref` absent** |
| `POST/PATCH/DELETE .../role-accounts` | B | `asm.request.create` | Credential submitted **by reference only**; an inline value is rejected |
| `POST .../role-accounts/{id}/reveal` | **C** | `asm.credential.reveal` | **Step-up required. Per-object audit. The submitting requester cannot reveal** |
| `POST .../role-accounts/{id}/verify` | B | `asm.request.triage` | |
| `POST .../role-accounts/{id}/attest-rotation` | B | `asm.request.triage` | |
| `GET/POST/PATCH .../environments` | B | `asm.request.create` | Bypass required where a protective control is present |

### 14.3 Attachments

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `POST /v1/attachments` | B | context-dependent | Multipart. Magic-byte verified; malware-scanned; `QUARANTINED` until clean |
| `GET /v1/attachments/{id}` | A | context-dependent | Returns metadata and a short-lived signed reference; **never inline content** |
| `POST /v1/attachments/{id}/parse-api-collection` | B | `asm.request.create` | **Async.** Hardened parser; external references disabled. Returns derived endpoint inventory, count discrepancy, and detected secrets |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-033` | Credential fields MUST accept a vault reference only. A request body containing a value in a credential field MUST be rejected with `VALIDATION_FAILED` and the value MUST NOT be logged. | The API is where an inline credential would enter. Rejecting at the boundary and not logging the rejected value prevents the credential reaching a log as a side effect of the rejection. | M | AT, CR |
| `PRD-API-034` | Attachment content MUST be served only via short-lived signed references from an origin distinct from the API, with non-inline disposition, and MUST NOT be returned in an API response body. | Evidence and uploads are potentially hostile content; serving from the API origin makes stored content a same-origin execution risk. | M | AT, PT |
| `PRD-API-035` | API collection parsing MUST disable external reference resolution and MUST enforce depth, size, and element limits. | With external references enabled the parser is a server-side request forgery and local file disclosure primitive operating inside the platform's network. | M | AT, PT |

### 14.4 Assessments

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `GET /v1/assessments` | A | `asm.assessment.read` | Filters include `coverage_ratio_max` — completed with incomplete coverage |
| `GET /v1/assessments/{id}` | A | `asm.assessment.read` | |
| `POST /v1/assessments` | B | `asm.assessment.conduct` | Scope derived from target assets, not the creator |
| `PATCH /v1/assessments/{id}` | B | `asm.assessment.conduct` | Coverage columns not settable |
| `POST /v1/assessments/{id}/transitions/{event}` | B | `asm.assessment.conduct` + transition permission | Completion blocked where items are unassessed without acknowledgement |
| `GET/PUT .../checklist-instances/{id}/results` | B | `asm.assessment.conduct` | `NOT_APPLICABLE` requires a reason |
| `GET/POST/PATCH .../conditions` | B | `asm.assessment.conduct` | Tracked independently of assessment completion |
| `GET/POST/PATCH /v1/checklist-definitions` | E | `asm.checklist.manage` | Published versions immutable |
| `GET /v1/assessments/{id}/evidence` | A | `asm.assessment.read` | Metadata only |
| `GET /v1/evidence/{id}/content` | **C** | `asm.evidence.read` | Step-up; per-object audit; signed reference; flagged content requires acknowledgement |
| `POST /v1/assessments/{id}/evidence` | B | `asm.evidence.upload` | |
| `GET/POST /v1/external-grants` | E | `asm.externalgrant.issue` | Explicit object grants; bounded expiry mandatory |
| `POST /v1/external-grants/{id}/revoke` | E | `asm.externalgrant.revoke` | |

---

## 15. Findings

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `GET /v1/findings` | A | `vul.finding.read` | Filters: `state`, `severity_min`, `score_band`, `finding_class`, `asset_class`, `asset_id`, `subtree_of`, `assignee_id`, `unassigned`, `known_exploited`, `has_exception`, `suppressed`, `recurrence_min`, `sla_status`, `detected_after`. Sort: `-risk_score`, `-severity`, `created_at`, `-last_detected_at` |
| `GET /v1/findings/{id}` | A | `vul.finding.read` | Includes impacts, enrichment with freshness, current score with coverage. Secret value **absent** |
| `PATCH /v1/findings/{id}` | B | `vul.finding.triage` | |
| `POST /v1/findings/{id}/transitions/{event}` | B | `vul.finding.triage` | Closure reason required; `FIXED_VERIFIED` requires method and verifier |
| `POST /v1/findings/{id}/assign` | B | `vul.finding.assign` | Individual only |
| `PUT /v1/findings/{id}/severity` | B | `vul.finding.severity.adjust` | Reported severity unchanged; reason required |
| `POST /v1/findings/{id}/dispute` | B | `vul.finding.dispute` | |
| `POST /v1/findings/{id}/dispute/adjudicate` | B | `vul.finding.dispute.adjudicate` | Distinct permission |
| `POST /v1/findings/bulk-triage` | D | `vul.finding.bulk` | Per-item permission and audit |
| `GET /v1/findings/{id}/impacts` | A | `vul.finding.read` | Per-impact scope filtering |
| `PATCH /v1/findings/{id}/impacts/{asset_id}` | B | `vul.finding.triage` | |
| `GET /v1/findings/{id}/secret` | **C** | `vul.secret.reveal` | Step-up; per-object audit; partial mask by default |
| `POST /v1/findings/{id}/secret/attest-rotation` | B | `vul.finding.triage` | Value nulled on attestation |
| `GET/POST /v1/suppressions` | B | `vul.suppression.create` | Bounded expiry mandatory |
| `POST /v1/suppressions/{id}/revoke` | B | `vul.suppression.revoke` | |
| `GET /v1/findings/{id}/history` | A | `vul.finding.read` | State, severity, assignment, score over time |
| `GET /v1/vulnerabilities/{ref}/findings` | A | `vul.finding.read` | **Disclosure response**: our findings for a vulnerability |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-036` | Finding responses MUST include the freshness of enrichment data and the coverage qualifier of any score. | Stale exploit intelligence presented as current is PP-1 violated where prioritization rests on it. A score without its coverage qualifier is a figure without a claim. | M | AT |
| `PRD-API-037` | Secret values MUST be returned partially masked by default, and full reveal MUST be a distinct operation with step-up and per-object audit. | The masked form is sufficient for identification; full reveal is required only for rotation and is the platform's most sensitive read. | M | AT |

---

## 16. Exceptions

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `GET /v1/risk-exceptions` | A | `exc.exception.request` \| read | Filters: `state`, `expiring_before`, `subtree_of`, `approver_id`, `subject_kind`. The audit register |
| `POST /v1/risk-exceptions` | B | `exc.exception.request` | Expiry mandatory and bounded; compensating controls or an approved no-controls declaration |
| `POST /v1/risk-exceptions/{id}/approve` | B + **step-up** | `exc.exception.approve` \| `.approve.elevated` | **Approver must differ from requester** — enforced |
| `POST /v1/risk-exceptions/{id}/reject` | B | as above | |
| `POST /v1/risk-exceptions/{id}/revoke` | B | `exc.exception.revoke` | |
| `POST /v1/risk-exceptions/{id}/renew` | B | `exc.exception.request` | New exception referencing the prior |
| `POST /v1/risk-exceptions/{id}/review` | B | `exc.exception.approve` | |
| `GET/POST .../compensating-controls` | B | `exc.exception.request` | |

**Deviation.** Approval requires step-up and, for elevated authority, a distinct permission. Attempting to approve one's own request returns `409 INVARIANT_VIOLATION` naming `INV-VUL-26` — deliberately explicit, because the caller is authorized and the refusal is a policy statement rather than an authorization denial.

---

## 17. Composition Analysis

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `POST /v1/sbom-submissions` | **F** | `sbm.sbom.submit` | **The only automated ingestion path.** Scope re-validated against pinned credential scope; quality gate; zero components rejected |
| `GET /v1/sbom-submissions/{id}` | A | `sbm.sbom.read` | Quality score and per-criterion warnings |
| `GET /v1/sbom-snapshots` | A | `sbm.snapshot.read` | Filters: `artifact_asset_id`, `created_after`, `quality_score_max` |
| `GET /v1/sbom-snapshots/{id}` | A | `sbm.snapshot.read` | |
| `GET /v1/sbom-snapshots/{id}/components` | A | `sbm.snapshot.read` | Paginated; `?relationship=DIRECT` |
| `GET /v1/artifacts/{id}/change-sets` | A | `sbm.snapshot.read` | `?downgrades_only=true` |
| `POST /v1/match-runs` | B | `sbm.matchrun.trigger` | `INTERACTIVE` |
| `POST /v1/match-batches` | D | `sbm.matchrun.trigger.batch` | Async; distinct permission; concurrency configurable |
| `POST /v1/match-batches/{id}/pause` \| `/resume` \| `/cancel` | B | `sbm.matchrun.trigger.batch` | |
| `GET /v1/match-runs/{id}` | A | `sbm.snapshot.read` | Includes `coverage_confirmed` and delta |
| `GET /v1/coverage-states` | A | `sbm.coverage.read` | `?status=NEVER_SUBMITTED\|STALE\|PARTIAL\|CURRENT` |
| `GET/POST /v1/exploitability-statements` | B | `vul.suppression.create` | Authorship recorded |
| `GET /v1/intelligence-status` | A | `sbm.coverage.read` | Version, age, staleness alert threshold |

**Submission request and response.**

```
POST /v1/sbom-submissions
Idempotency-Key: <hash(content, artifact)>
{ "project_ref": "...", "artifact_name": "...", "artifact_version": "...",
  "revision_reference": "...", "format": "CYCLONEDX", "format_version": "1.6",
  "sbom": { ... } }                        // or multipart for large documents

→ 202 { "submission_id": "...", "snapshot_id": "...",
        "quality": { "overall": 92, "purl_validity_ratio": 0.99,
                     "concrete_version_ratio": 0.97,
                     "distinguishes_direct_transitive": true },
        "warnings": [ { "code": "ECOSYSTEM_MISMATCH", "detail": "..." } ],
        "asset_created_unclaimed": false }
→ 422 QUALITY_THRESHOLD_NOT_MET  { "criteria": [ { "criterion": "...", "measured": 0.41,
                                                   "threshold": 0.70 } ] }
```

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-038` | SBOM submission MUST return the quality score and every warning in the response body, and MUST NOT report them only server-side. | The submitter is the only party who can fix a low-quality SBOM, and they see the response, not the log. | M | AT |
| `PRD-API-039` | Submission MUST NOT reject a request because the named artifact is unknown; it MUST create the asset unclaimed and report that it did. | Rejection loses data at the point of least detectability — a pipeline receiving a 4xx logs it and continues. | M | AT |

---

## 18. Work Management

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `GET /v1/work-items` | A | `wrk.item.read` | Filters: `type_id`, `state`, `state_category`, `assignee_id`, `watching`, `labels`, `subject_kind`, `subject_id`, `subtree_of`, `sla_status`, `blocked`, `planning_period_id`, custom attributes |
| `GET /v1/work-items/{id}` | A | `wrk.item.read` | Field-level restrictions applied |
| `POST /v1/work-items` | B | `wrk.item.create` | Scope derived from the subject |
| `PATCH /v1/work-items/{id}` | B | `wrk.item.update` | `row_version` required; `412` on conflict |
| `POST /v1/work-items/{id}/transitions/{event}` | B | `wrk.item.transition` + transition permission | Guards evaluated; required fields enforced |
| `POST /v1/work-items/{id}/assign` | B | `wrk.item.assign` | |
| `POST /v1/work-items/bulk-update` | D | `wrk.item.bulk` | Per-item permission and audit |
| `GET/POST /v1/work-items/{id}/comments` | B | `wrk.comment.post` | Constrained rich text; mentions scope-filtered |
| `PATCH /v1/comments/{id}` | B | `wrk.comment.post` (own) | Revision retained |
| `POST /v1/comments/{id}/redact` | E | `wrk.comment.redact` | **No delete operation exists** |
| `GET/PUT/DELETE /v1/work-items/{id}/watchers` | B | `wrk.item.read` | Self-service |
| `PUT /v1/work-items/{id}/read-state` | B | `wrk.item.read` | Not audited |
| `GET /v1/work-items/{id}/timeline` | A | `wrk.item.read` | Unified: transitions, comments, attachments, automation, AI suggestions |
| `GET/POST/DELETE /v1/work-items/{id}/links` | B | `wrk.item.update` | Inverse maintained automatically |
| `GET/PUT .../checklist` | B | `wrk.item.update` | Sub-items without independent scheduling |
| `GET /v1/search` | A | `wrk.item.read` | **Scope predicate applied before ranking**; counts post-filter |
| `GET/POST/PATCH/DELETE /v1/saved-views` | B | `wrk.item.read`; share needs `wrk.savedview.share` | **Shared views evaluate as the viewer** |
| `GET/POST/PATCH /v1/work-item-types` | E | `wrk.itemtype.manage` | |
| `GET/POST/PATCH /v1/workflow-definitions` | E | `wrk.workflow.manage` | **Validated for reachability before activation** |
| `POST /v1/workflow-definitions/{id}/activate` | E | `wrk.workflow.manage` | `422` on unreachable state or absent terminal state |
| `GET/POST/PATCH /v1/automation-rules` | E | `wrk.automation.manage` | Owner authority ceiling; loop guard; execution budget |
| `GET /v1/automation-rules/{id}/executions` | A | `wrk.automation.manage` | Includes `actions_denied` |
| `POST /v1/inbound-email` | **F** | signed webhook | Reply-to-comment association |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-040` | Mention autocomplete MUST be scope-filtered, and MUST NOT confirm the existence of a principal outside the caller's scope. | Autocomplete is a user enumeration surface (`INV-WRK-09`). | M | AT, PT |
| `PRD-API-041` | Search MUST apply the scope predicate before ranking, and total-hit counts MUST be post-filter. | Pre-filter ranking or counting discloses out-of-scope volume even where no content is returned (`SEC-TEN-011`). | M | AT, PT |
| `PRD-API-042` | A shared saved view MUST be evaluated against the viewing principal's scope. The stored definition MUST NOT include a scope. | Storing the author's scope would make a shared link carry the author's visibility — a scope escalation available to anyone with the link (`INV-WRK-11`). | M | AT, PT |
| `PRD-API-043` | No comment deletion operation MUST exist at any permission level. Removal MUST be redaction leaving a visible record. | A comment thread on a security finding is audit evidence; selective deletion permits reconstruction of a different history (`INV-WRK-08`). | M | AT, CR |

---

## 19. Risk and Service Levels

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `GET /v1/risk-scores/{subject_kind}/{subject_id}` | A | `rsk.score.read` | Latest with coverage qualifier |
| `GET .../explanation` | A | `rsk.score.explain` | **Factor breakdown restricted to in-scope contributions** |
| `GET .../history` | A | `rsk.score.read` | With change attribution |
| `GET/POST/PATCH /v1/scoring-models` | E + **step-up** | `rsk.model.configure` | Weights bounded; activation requires historical validation |
| `POST /v1/scoring-models/{id}/preview` | A | `rsk.model.configure` | Effect on a sample before activation |
| `POST /v1/scoring-models/{id}/activate` | E + **step-up** | `rsk.model.configure` | `422` where unvalidated or out of bounds |
| `POST /v1/scoring-models/{id}/recompute` | D | `rsk.model.configure` | Async; explicit — activation does not recompute |
| `GET/POST/PATCH /v1/sla-policies` | E | `rsk.sla.configure` | |
| `GET/POST/PATCH /v1/business-calendars` | E | `rsk.sla.configure` | |
| `GET /v1/sla-clocks` | A | `rsk.score.read` | Filters: `state`, `due_before`, `breached`, `paused`, `attribution` |
| `POST /v1/sla-clocks/{id}/extend` | B | `rsk.sla.extend` | Distinct state from breach |

---

## 20. Capacity

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `GET /v1/capacity/team` | A | `cap.team.read` | Aggregate; suppressed below minimum group size |
| `GET /v1/capacity/members` | **C** | `cap.member.read.all` | **`RESTRICTED`. Never implied by seniority. Access audited** |
| `GET /v1/capacity/me` | A | `cap.member.read.own` | Own data only |
| `GET/POST/PATCH /v1/team-members` | E | `cap.member.manage` | |
| `GET/POST /v1/availability-records` | E | `cap.member.manage` | |
| `GET /v1/capacity/measures` | A | `cap.team.read` \| `cap.member.read.all` | Grain determines permission |
| `GET /v1/capacity/estimation-accuracy` | A | `cap.team.read` | Bias by type |

**Deviation.** `GET /v1/capacity/members` is class C without step-up: it is a high-frequency management view, and step-up on every load would be circumvented. The controls are the dedicated permission, the audit record, and minimum-group-size suppression on any aggregate derived from it.

---

## 21. Insight and Reporting

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `GET /v1/dashboards/{composition}` | A | `dsh.composition.*` per composition | `composition` ∈ `executive`, `operational`, `engineering`, `workload`. **Scope root derived from the caller** |
| `GET /v1/dashboards/{composition}/measures/{measure}` | A | as above | Includes coverage qualifier and normalization statement |
| `GET /v1/dashboards/{composition}/drill-through` | A | as above + underlying read permission | Applies the element's slice as a queue filter; **full object-level evaluation, not inherited from the chart** |
| `POST /v1/reports` | D | `dsh.report.schedule` | Async |
| `GET/POST/PATCH /v1/report-schedules` | E | `dsh.report.schedule` | **Scope evaluated per recipient at generation** |
| `GET/POST/PATCH /v1/report-templates` | E | `dsh.report.template.manage` | |
| `POST /v1/audit-evidence-exports` | D | `dsh.audit_evidence.generate` | Async |
| `POST /v1/exports` | D | resource export permission | Async. **Credentials, secrets, and evidence excluded unconditionally** |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-044` | Dashboard responses MUST NOT accept a scope root parameter. The root MUST be derived from the caller's authorization context. | An absolute root parameter is a scope escalation attempt away, and rendering a broader view with out-of-scope data suppressed discloses the organization's shape above the caller (`PRD-DSH-001`). | M | AT, PT |
| `PRD-API-045` | Drill-through MUST perform full object-level authorization on the records returned and MUST NOT rely on the composition permission. | Chart visibility is not record visibility; inheriting it is a disclosure through a convenience path. | M | AT, PT |
| `PRD-API-046` | Every export response MUST state the scope applied and the record count, and both MUST appear in the audit event. | Bulk extraction under legitimate permission is the highest-value insider action; only scope and volume in the audit record distinguish it from routine reporting. | M | AT |

---

## 22. AI

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `POST /v1/ai/suggestions` | B | capability permission | Async. Grounding retrieval **enforces the caller's scope** |
| `GET /v1/ai/suggestions` | A | `aic.suggestion.read` | Filters: `capability`, `subject`, `state` |
| `GET /v1/ai/suggestions/{id}` | A | `aic.suggestion.read` | Content, citations, provenance |
| `POST /v1/ai/suggestions/{id}/promote` | B | `aic.suggestion.promote` + **the permission for the resulting change** | Re-validates authorization for the change |
| `POST /v1/ai/suggestions/{id}/dismiss` | B | `aic.suggestion.read` | |
| `GET/PATCH /v1/ai/configuration` | E + **step-up** | `aic.provider.configure` | Provider, model per capability, permitted data categories, budgets |
| `GET /v1/ai/usage` | A | `aic.provider.configure` | Consumption against budget |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-047` | No AI operation MUST create or modify a domain object. Promotion MUST execute the change through the same operation a human would use, with the same authorization. | ADR-005. A promotion path that writes directly would bypass the invariants and permissions of the ordinary path. | M | AT, CR |
| `PRD-API-048` | AI responses MUST include model provenance and citations, and generated content MUST be labelled as such in every representation including exports. | A reader's evaluation of a statement depends on its provenance; unlabelled generated text in an exported report is presented as the security function's considered assessment. | M | AT, DI |

---

## 23. Ingestion

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `POST /v1/imports` | D or F | `ing.import.execute` | Async. Multipart or reference. Idempotent on content and target |
| `GET /v1/imports/{id}` | A | `ing.import.execute` | Per-record outcome summary |
| `POST /v1/imports/{id}/reverse` | D | `ing.import.reverse` | Async; distinct permission |
| `GET /v1/imports/{id}/quarantined-records` | A | `ing.quarantine.manage` | |
| `POST /v1/quarantined-records/{id}/resubmit` | B | `ing.quarantine.manage` | Corrected record only |
| `GET/POST /v1/field-mapping-templates` | B | `ing.import.execute` | |
| `POST /v1/imports/preview` | B | `ing.import.execute` | Mapping preview before commitment |
| `POST /v1/migrations/work-items` | D + **step-up** | `ing.migration.execute` | Async. **Writes historical authorship**; migrated records flagged |
| `GET/POST/PATCH /v1/connectors` | E + **step-up** | `con.connector.configure` | Egress destinations are configuration |
| `POST /v1/connectors/{id}/rotate-credential` | E | `con.connector.credential.rotate` | Credential never retrievable |
| `GET /v1/connectors/health` | A | `con.connector.health.read` | Last success, failure count, circuit state |

---

## 24. Administration

| Operation | Class | Permission | Notes |
|---|---|---|---|
| `GET /v1/permissions` | A | `auz.effective.inspect` | The product-fixed catalogue |
| `GET/POST/PATCH /v1/roles` | E + **step-up** | `auz.role.manage` | Catalogue permissions only |
| `GET/POST/PATCH/DELETE /v1/role-assignments` | E + **step-up** | `auz.assignment.manage` | |
| `GET /v1/principals/{id}/effective-permissions` | A | `auz.effective.inspect` | Resolved set, scope, and producing grants |
| `GET /v1/authorization/who-can` | A | `auz.effective.inspect` | Inverse: who can perform a permission on an object |
| `POST /v1/authorization/access-review-export` | D | `auz.effective.inspect` | Async; audited |
| `GET/POST/PATCH /v1/sod-constraints` | E + **step-up** | `auz.sod.manage` | |
| `GET/POST /v1/delegations` | B | `auz.delegation.create` | Bounded; not re-delegable |
| `GET/POST/PATCH /v1/principals` | E | `iam.principal.manage` | |
| `GET/POST/PATCH /v1/service-principals` | E + **step-up** | `iam.serviceprincipal.manage` | Pinned scope; expiring by default |
| `GET /v1/sessions/me` \| `DELETE /v1/sessions/{id}` | B | self | Self-service session visibility and termination |
| `POST /v1/sessions/revoke-principal` | E + **step-up** | `iam.principal.manage` | Effective within `NFR-SEC-001` |
| `GET /v1/audit-events` | A | `aud.audit.read` | **Distinct permission; never implied by administration.** Scope-filtered |
| `POST /v1/audit-events/verify-integrity` | A | `aud.audit.read` | Chain verification over a range |
| `GET/PATCH /v1/audit-retention` | E + **step-up** | `aud.retention.configure` | |
| `GET/POST /v1/legal-holds` | E | `aud.legalhold.manage` | Blocks retention expiry and erasure |
| `POST /v1/erasure-requests` | E + **step-up** | `dat.erasure.execute` | **Dual control.** Async. Blocked by legal hold |
| `POST /v1/break-glass-grants` | E + **step-up** | operator | Second approver; tenant notified non-suppressibly |
| `GET/POST /v1/configuration-exports` \| `-imports` | E + **step-up** | `ten.configuration.export` \| `.import` | Async; environment promotion |
| `GET /v1/tenant/entitlement` | A | tenant admin | Usage against entitlement |
| `POST /v1/tenant/data-export` | D + **step-up** | tenant admin | Async; complete tenant export |
| `GET /v1/notifiable-events` \| `GET/PUT /v1/subscriptions` | B | self | Mandatory categories not unsubscribable |
| `GET /v1/notifications` \| `POST /v1/notifications/{id}/read` | A/B | self | In-product centre |
| `GET/POST/PATCH /v1/knowledge-articles` | B | `kbs.article.read` \| `.author` | Constrained rich text |
| `GET /v1/health` \| `GET /v1/version` | **G** | none | No tenant or version detail beyond the API version |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-049` | Audit read MUST require a permission not implied by any administrative permission, and audit queries MUST be scope-filtered. | A principal who can act and read the record of action can verify their action was recorded; an unfiltered audit query is an unrestricted read path around every other control (`PRD-AUD-007`). | M | AT, PT |
| `PRD-API-050` | Erasure MUST require dual control expressed as two distinct authenticated approvals, and MUST be rejected where a legal hold applies. | Irreversible destruction and the plausible mechanism for an insider covering activity (`SEC-AUZ-042`). | M | AT |
| `PRD-API-051` | Unauthenticated endpoints MUST NOT disclose tenant existence, tenant count, build detail, or dependency versions. | The health endpoint is the platform's only unauthenticated surface and is the first thing an attacker reads. | M | AT, PT |

---

## 25. Webhooks

Outbound delivery of domain events to tenant-configured endpoints.

```
POST <tenant endpoint>
Webhook-Id: 01J...
Webhook-Timestamp: 1754300000
Webhook-Signature: v1,<base64 hmac>
{ "event_type": "finding.raised", "event_id": "...", "occurred_at": "...",
  "tenant_id": "...", "object": { "kind": "finding", "id": "..." },
  "data": { ... } }
```

Signature over `Webhook-Id`, `Webhook-Timestamp`, and body, with a per-subscription secret. Retry with exponential backoff for 24 hours; delivery status readable; subscription disabled after sustained failure with the owner notified.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-API-052` | Webhook payloads MUST be signed with a per-subscription secret, and MUST include an identifier and timestamp within the signed material. | An unsigned webhook is an unauthenticated instruction to the receiving system; without a signed timestamp and identifier it is replayable. | M | AT |
| `PRD-API-053` | Webhook payloads MUST be restricted to the subscription's configured scope and MUST NOT include `RESTRICTED` data. | A webhook is an egress channel to an endpoint the platform does not control, and payload content cannot be recalled. | M | AT, PT |
| `PRD-API-054` | Webhook destinations MUST be validated against an allowlist policy and MUST NOT resolve to internal addresses. | An arbitrary destination makes the webhook sender a server-side request forgery primitive inside the platform's network (`PRD-CON-007`). | M | AT, PT |

---

## 26. Requirements

Forty requirements, `PRD-API-015` through `PRD-API-054`, all `MUST_HAVE`.

| Group | IDs | Count |
|---|---|---|
| Authentication | `015` – `016` | 2 |
| Versioning | `017` – `018` | 2 |
| Annotation classes | `019` – `021` | 3 |
| Collections | `022` – `025` | 4 |
| Async and bulk | `026` – `029` | 4 |
| Rate limiting | `030` – `031` | 2 |
| Assets | `032` | 1 |
| Assessments | `033` – `035` | 3 |
| Findings | `036` – `037` | 2 |
| Composition | `038` – `039` | 2 |
| Work management | `040` – `043` | 4 |
| Reporting | `044` – `046` | 3 |
| AI | `047` – `048` | 2 |
| Administration | `049` – `051` | 3 |
| Webhooks | `052` – `054` | 3 |

Satisfies `PRD-API-001` – `014` and the enforcement-point map of DOC-07 §17.

---

## 27. Closing

### 27.1 Extensibility

Operations are added additively within a major version. The specification is generated, so it cannot drift from the implementation. Cross-cutting concerns — pagination, filtering, errors, idempotency, rate limiting, audit emission, authorization — are framework properties, so a new operation inherits them and cannot omit one. Annotation class assignment (`PRD-API-019`) is the gate that prevents an operation shipping without its security characteristics considered.

**Deliberate rigidity.** No filter expression language (`PRD-API-025`); keyset pagination only (`PRD-API-022`); no comment deletion (`PRD-API-043`); no scope root parameter (`PRD-API-044`); no unconstrained bearer credentials; no AI write path (`PRD-API-047`).

**Known extension costs.** Every new egress operation requires an entry in the DOC-07 §17 enforcement point map and a test (`SEC-AUZ-049`). Every new sortable field requires an index (`PRD-API-024`). Adding an enum value to a response is breaking unless declared open at introduction (`PRD-API-017`).

### 27.2 Security considerations

The API is the platform's largest programmatic surface. Its highest-risk properties, and where each is addressed:

| Risk | Control |
|---|---|
| Object identifier substitution | Class A/B re-validation independent of provenance; `404` for scope violation |
| Existence disclosure through response differences | Uniform `404`; latency uniformity (`PRD-API-021`) |
| Volume disclosure through counts | Post-filter, opt-in totals (`PRD-API-023`) |
| Bulk as a permission bypass | Per-item evaluation (`PRD-API-028`) |
| Scope escalation through a shared link | Saved views and result references evaluate as the viewer (`PRD-API-042`, `-026`) |
| Chart-to-record privilege inheritance | Full object-level evaluation on drill-through (`PRD-API-045`) |
| Credential entering through the API | Vault references only; rejected values not logged (`PRD-API-033`) |
| Hostile uploaded content | Separate origin, non-inline, signed references (`PRD-API-034`) |
| Parser as an SSRF primitive | External references disabled (`PRD-API-035`); webhook destination allowlist (`PRD-API-054`) |
| Enumeration | Rate limit classes; denial-pattern detection (`SEC-PLT-003`) |
| Insider bulk extraction | `BULK` and `SENSITIVE` limits; scope and volume in audit (`PRD-API-046`) |

**Residual risk.** The annotation class mechanism concentrates correctness in the class definitions: an error in class A's scope re-validation affects every read operation. That concentration is deliberate — one place to get right rather than 130 — and it makes the class definitions the most test-critical code in the API layer.

### 27.3 Notes for downstream documents

| Document | Note |
|---|---|
| DOC-06 | Owes signature scheme detail, step-up mechanism, and the nonce store |
| DOC-08 | Field-level restrictions mean absence, not masking — the interface must render absence gracefully |
| DOC-09 | Every `/transitions/{event}` operation is governed by a state machine owed here |
| DOC-15 | Owes gateway rate limit class configuration, the separate attachment origin, and signed-reference issuance |
| DOC-16 | Owes: a test per operation asserting denial for an out-of-scope principal; latency-uniformity assertions for `PRD-API-021`; a bulk test asserting per-item denial is not bypassed; a generated-versus-implementation specification comparison |
| DOC-21 | Owes the connector contract behind `/v1/connectors` |

### 27.4 Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.0.0 | 2026-08-04 | Chief Software Architect; Principal Security Architect | Initial content-complete version. Introduces seven security annotation classes so that ~130 operations carry uniform security characteristics with only deviations annotated, replacing per-operation repetition. Specifies three authentication mechanisms with no unconstrained bearer credentials; a breaking-change taxonomy treating new response enum values as breaking; RFC 9457 errors with uniform `404` for scope violation and non-existence; keyset-only pagination with post-filter opt-in totals; typed filtering with no expression language; idempotency, async job, and partial-success bulk protocols; eight rate limit classes with `SENSITIVE` an order of magnitude below `READ`; and the full operation catalogue across thirteen resource groups. Forty requirements. | Pending |

---

*End of DOC-05. Content complete at version 1.0.0.*
