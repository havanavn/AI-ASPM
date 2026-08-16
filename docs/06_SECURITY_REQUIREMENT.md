---
document_id:    DOC-06
title:          Security Requirement
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        1.0.0
status:         In review
owner:          Principal Security Architect
authors:        [Principal Security Architect, Principal Application Security Engineer]
reviewers:      []
last_updated:   2026-08-04
tier:           4
prerequisites:  [DOC-00, DOC-01, DOC-03, DOC-07, DOC-24, DOC-26]
depends_on:     [DOC-00, DOC-01, DOC-02, DOC-03, DOC-04, DOC-05, DOC-07, DOC-24, DOC-26]
supersedes:     null
adrs_relied_on: [ADR-004, ADR-007, ADR-017, ADR-024]
open_questions: [OQ-026]
requirement_domains: [SEC, IAM, PTR, SBM]
security_review_required: true
---

# 06 — Security Requirement

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope) · 2. [ASVS Posture](#2-asvs-posture) · 3. [Authentication](#3-authentication) · 4. [Session Management](#4-session-management) · 5. [Local Credentials](#5-local-credentials) · 6. [Cryptography and Key Management](#6-cryptography-and-key-management) · 7. [Secrets Management](#7-secrets-management) · 8. [Input Validation and Output Encoding](#8-input-validation-and-output-encoding) · 9. [Injection Class Controls](#9-injection-class-controls) · 10. [File Upload and Hostile Content](#10-file-upload-and-hostile-content) · 11. [Document Parser Hardening](#11-document-parser-hardening) · 12. [Web and Transport Controls](#12-web-and-transport-controls) · 13. [Platform Supply Chain](#13-platform-supply-chain) · 14. [Vulnerability Management of the Platform](#14-vulnerability-management-of-the-platform) · 15. [Zero Trust Enforcement Points](#15-zero-trust-enforcement-points) · 16. [Logging and Monitoring Controls](#16-logging-and-monitoring-controls) · 17. [Requirements Summary](#17-requirements-summary) · 18. [Closing](#18-closing)

---

## 1. Purpose and Scope

Specifies the security controls implementing `CON-SEC-001` (ASVS Level 2 baseline with enumerated Level 3 uplift) and treating the threats of DOC-26.

**In scope.** ASVS posture with per-control uplift rationale; authentication, session, credential, cryptography, secrets; input validation, output encoding, and the injection class control set; file upload and hostile content; document parser hardening; web and transport controls; the platform's own supply chain and vulnerability management; Zero Trust enforcement points; logging controls.

**Out of scope.** Authorization model (DOC-07); tenant isolation (DOC-24); threat model (DOC-26); audit trail design (DOC-14); infrastructure and network (DOC-15); test specification (DOC-16).

**LC-01.** Requirements are issued as `SEC-SEC-nnn` for general controls, plus five identifiers forward-referenced from earlier documents and defined here: `SEC-PTR-004` (superseded by `SEC-PTR-007`), `SEC-PTR-006`, `SEC-PTR-007`, `SEC-SBM-003`, `SEC-SBM-004`.

**LC-02.** ⚠ **Working assumption (OQ-026):** the platform integrates with an external secrets vault and ships a platform-provided default for deployments lacking one. §7 is written against both; if the decision is integration-only, the platform-provided path is removed and nothing else changes.

---

## 2. ASVS Posture

**Baseline: Level 2 across all chapters.** **Uplift to Level 3** in five areas, each justified by the crown-jewels analysis of DOC-26 §3.

| Area | Why Level 3 |
|---|---|
| **Authentication** | The platform holds a prioritized attack plan for the customer's entire estate. Credential stuffing against a target of this value is plausible rather than theoretical, and a single compromised human account yields broad read |
| **Session management** | Session theft yields the same access as credential compromise, and the population with the widest read scope (practitioners) works in long sessions |
| **Cryptography** | Per-tenant keys (`SEC-TEN-012`) are the control bounding a storage compromise to one tenant. Weak key handling defeats the entire tenancy model |
| **Secrets management** | The connector credential store is access *to* the customer's engineering estate, not merely data about it (DOC-26 §3.3). It is the platform's highest-value asset |
| **Audit logging** | The trail is the platform's evidence of its own correctness and the only mechanism for evidencing compromise (DOC-26 T10) |

**Not uplifted, and why.** Business logic, data protection at rest beyond the above, communications, malicious code, and configuration remain Level 2 because Level 2 is adequate for their risk here and a blanket Level 3 claim is unverifiable — which makes it worthless in the procurement review it would be made for.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-001` | The platform MUST meet ASVS Level 2 across all chapters, with Level 3 in authentication, session management, cryptography, secrets management, and audit logging. Conformance MUST be documented per control, not claimed per level. | A per-level claim cannot be verified by a reviewer. Per-control documentation is what makes the claim defensible in the procurement security review every enterprise buyer performs. | M | DI |

---

## 3. Authentication

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-002` | Federated authentication via OIDC MUST be the primary method, with local authentication available only where no provider is configured and for break-glass. | The tenant's existing authentication strength, conditional access, and deprovisioning apply without duplication. A separate credential store for a platform of this sensitivity is a second thing to attack. | M | AT |
| `SEC-SEC-003` | Multi-factor authentication MUST be supported, and a tenant policy MUST be able to require it for all principals or for holders of specified permissions. | Single-factor access to this asset value is disproportionate. Per-permission scoping lets a tenant require it for restricted-data holders without imposing it on thousands of requesters. | M | AT |
| `SEC-SEC-004` | Step-up authentication MUST be required for restricted-data reveal, elevated exception approval, authorization or workflow configuration change, erasure, and break-glass, with a maximum age of 5 minutes. | A session established hours earlier is weak evidence that the person now acting is the authenticated principal. Five minutes binds the highest-consequence actions to a fresh assertion. | M | AT |
| `SEC-SEC-005` | Authentication throttling MUST degrade an attacker without permitting lockout of a named principal at will: progressive delay and risk-based challenge rather than account disable. | Naive lockout converts credential guessing into a reliable denial of service against a named user — a more effective attack than the one it prevents. | M | AT, PT |
| `SEC-SEC-006` | Credential stuffing controls MUST include breached-credential checking at set and at authentication where local credentials are in use. | Reuse is the dominant credential attack, and detection is materially more effective than composition rules. | M | AT |
| `SEC-SEC-007` | Authentication responses MUST NOT differentiate an unknown principal from an incorrect credential, in status, message, or timing. | Differentiation is a principal enumeration oracle, and the principal list here maps the customer's security function. | M | AT, PT |
| `SEC-SEC-008` | Every authentication event, success and failure, MUST be recorded with source context. | Failures matter more than successes for detection, and this is the starting point of most investigations. | M | AT |

---

## 4. Session Management

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-009` | Session identifiers MUST be generated by a cryptographically secure source with at least 128 bits of entropy, MUST NOT encode any principal or tenant data, and MUST be regenerated on privilege change and on authentication. | An identifier encoding tenant or principal data leaks it wherever the identifier appears. Regeneration on privilege change prevents session fixation carrying a lower-privilege session into a higher one. | M | AT, PT |
| `SEC-SEC-010` | Sessions MUST have both absolute and idle lifetime limits, tenant-configurable within product bounds, with a product maximum absolute lifetime of 12 hours. | Indefinite sessions accumulate on shared and personal devices. A 12-hour ceiling means a session cannot outlive a working day. | M | AT |
| `SEC-SEC-011` | Session revocation MUST take effect within 60 seconds across every component including cached authorization state, and MUST apply to tokens already issued. | Revocation waiting for token expiry is not revocation; the interval is the window in which a terminated or compromised principal retains access (`NFR-SEC-001`). | M | AT |
| `SEC-SEC-012` | Principals MUST be able to view and terminate their own active sessions with source context. | This is a user's only means of detecting that their account is in use elsewhere. | M | AT |
| `SEC-SEC-013` | Session cookies MUST be `Secure`, `HttpOnly`, and `SameSite=Lax` or stricter, and MUST be scoped to the narrowest applicable path. | Standard, and `HttpOnly` in particular limits the consequence of a residual cross-site scripting defect. | M | AT |

---

## 5. Local Credentials

Applies only where local authentication is in use (§3, `SEC-SEC-002`).

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-014` | Credentials MUST be stored using a memory-hard password hashing function with per-credential salt and parameters tuned to a target verification cost, and parameters MUST be stored alongside so they can be raised without invalidating existing credentials. | Storing parameters with the hash is what makes cost increases possible over the platform's life; without it the first parameter choice is permanent. | M | AT, CR |
| `SEC-SEC-015` | Credential policy MUST enforce a minimum length of 12 characters and breached-credential rejection, and MUST NOT enforce composition rules or mandatory periodic rotation. | Composition rules produce predictable substitutions; mandatory rotation produces predictable derivations. Both are contrary to current guidance and are stated here to prevent their addition as assumed good practice. | M | AT |
| `SEC-SEC-016` | Credential reset MUST use a single-use, time-limited token delivered out of band, MUST invalidate all sessions on use, and MUST NOT disclose whether the principal exists. | Session invalidation on reset is what makes reset effective against an active compromise. | M | AT |

---

## 6. Cryptography and Key Management

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-017` | Cryptographic operations MUST use vetted library implementations of current recommended algorithms. Custom cryptographic construction MUST NOT be used. | The failure mode of custom cryptography is silent and undetectable by testing. | M | CR, AR |
| `SEC-SEC-018` | Data encryption keys MUST be per tenant, wrapped by a key-encryption key held in a hardware-backed or equivalently protected store, and key access MUST be bound to the established tenant context. | Per-tenant keys with unrestricted access provide no isolation — the application would hold every key (`SEC-TEN-013`). | M | AT |
| `SEC-SEC-019` | Key rotation MUST be supported without downtime, with prior key versions retained for decryption until re-encryption completes, and rotation MUST be schedulable and auditable. | Rotation requiring downtime does not happen. Losing access to prior ciphertext is data loss. | M | AT |
| `SEC-SEC-020` | Key material MUST NOT be recoverable by platform operators without break-glass, and key destruction MUST be dual-controlled and audited. | Standing operator access to key material defeats §6 entirely. Destruction is irreversible data loss and is also the mechanism an insider would use to destroy evidence. | M | AT, CR |
| `SEC-SEC-021` | The following MUST be encrypted at rest with tenant key material: test credentials, secret finding values, evidence content, personal workload data, and tenant data exports. | These are the `RESTRICTED` categories. Field-level encryption limits a storage compromise to what the application decrypts in legitimate use. | M | AT |
| `SEC-SEC-022` | Transport MUST use TLS 1.2 or higher with strong cipher suites; TLS 1.3 MUST be preferred; plaintext HTTP MUST NOT be served except for a redirect. | Baseline. Stated so the redirect exception is explicit rather than implicit. | M | AT |

---

## 7. Secrets Management

⚠ **Working assumption (OQ-026).**

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-023` | Secrets MUST be held in a dedicated store with per-tenant namespaces, MUST be referenced by identifier throughout the platform, and MUST NOT appear in any domain table, configuration file, environment variable of a long-lived process, container image, or source repository. | The reference pattern is what makes the prohibition enforceable: there is no column to populate, so there is nothing to misconfigure (`INV-ASM-03`). | M | AT, CR |
| `SEC-SEC-024` | Secrets MUST NOT be retrievable in plaintext after entry except through an explicitly permissioned, step-up-authenticated, per-object-audited reveal operation. | An administrator who can read a stored connector credential can exfiltrate access to every integrated system (`PRD-CON-002`). | M | AT, PT |
| `SEC-SEC-025` | Secret material MUST be excluded from logs, error messages, stack traces, telemetry, exports, notifications, webhook payloads, and AI prompt context. Exclusion MUST be enforced by a redaction layer, not by author discipline. | Every one of these has been a real leakage path. A redaction layer at the serialization boundary is structural; remembering not to log a value is not. | M | AT, CR |
| `SEC-SEC-026` | Secret rotation MUST be supported for every secret type the platform holds, and a rotation failure MUST surface rather than silently retaining the prior value. | A secret that cannot be rotated becomes permanent, and permanent credentials to a customer's engineering estate are what an attacker targeting this platform is after. | M | AT |

##### `SEC-PTR-004` — Test credential protection · `Superseded` by `SEC-PTR-007`

**Superseded, text retained.** The reference-only rule assumed a secrets store to hold the value.
`OQ-026` — platform-provided vault, enterprise integration, or both — remains open and blocking, so
no store exists, and in a deployment without one this requirement resolves to "the platform holds a
note saying the password is in a chat thread". `SEC-PTR-007` states the model that replaces it. Every
other clause below is carried forward unchanged.

**Original statement.** ~~Test account credentials submitted with an assessment request MUST be stored in the secrets store by reference only; MUST be masked by default in every interface; MUST require an explicitly permissioned, step-up-authenticated, per-object-audited reveal; MUST NOT be readable by the submitting requester after submission; MUST be excluded from every export, notification, and AI context without exception; and MUST be flagged for rotation at engagement closure with attestation required before the reference is cleared.

**Rationale.** These are live credentials to pre-production environments that frequently share data or trust relationships with production. Storing them as request fields would place working credentials in every export, backup, log line that dumps a record, and AI prompt. The prohibition on the requester reading them back prevents the platform becoming a credential store with no rotation policy. The export exclusion admits no exception because an export is the one artifact whose subsequent distribution the platform cannot control.~~

**Priority.** MUST_HAVE · **Verification.** AT, PT, CR

##### `SEC-PTR-007` — Test credential custody, bounded by the engagement

**Statement.** Where no secrets store is integrated, the platform MAY take custody of a test account credential submitted with an assessment request, and where it does it MUST: encrypt the value at rest under a key held outside the database; destroy the value in the same transaction as the request's transition to a terminal state; retain a tombstone recording that a credential was held and when it was destroyed; flag the account for rotation at destruction; mask the value by default in every interface with reveal gated by an explicit permission, step-up authentication, and a per-object audit entry; exclude it from every export, notification, report, and AI context without exception; and **refuse the submission rather than store the value unencrypted where no key is configured**.

**Rationale.** Supersedes `SEC-PTR-004`'s reference-only rule for the case that requirement did not anticipate: no store to reference. The credential exists whether or not the platform holds it, and refusing custody does not prevent it being created and left live in a chat history nobody purges — it only removes the platform's ability to destroy it on time. Custody buys the one control an out-of-band channel cannot offer, because the platform is the thing that knows when the engagement ended. The refusal clause is the load-bearing one: a plaintext fallback is what gets written when the alternative is an error during a demonstration, and it would make every other clause decorative. Rotation remains a separate obligation because destroying the platform's copy does not change the password on the customer's system.

**Priority.** MUST_HAVE · **Verification.** AT, PT, CR
**Configurability.** T1 for the destruction trigger, the refusal, and the export exclusion. T3 for whether a reveal requires a stated purpose.
**Supersedes.** `SEC-PTR-004`. **Narrows on.** `OQ-026` — an answered vault question returns this surface to a reference.

##### `SEC-PTR-006` — Intake surface hardening

**Statement.** The assessment request intake surface MUST enforce: content-signature verification of every attachment against its declared type; malware scanning before an attachment becomes retrievable; storage on an origin distinct from the API with non-inline disposition and server-generated filenames; hardened parsing of uploaded API collections per §11; secret detection on uploaded collections with the result surfaced to the submitter; and per-principal rate limiting on request creation.

**Rationale.** This surface combines the platform's largest and least-trained user population with file upload, untrusted document parsing, and live credential submission (DOC-01 §10.4.6, PP-7). Secret detection matters specifically because API collections routinely embed production tokens in environment variables that the submitter did not consider credentials.

**Priority.** MUST_HAVE · **Verification.** AT, PT

---

## 8. Input Validation and Output Encoding

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-027` | Input MUST be validated against a positive schema — type, range, length, format, and permitted values — at the trust boundary, and unknown fields MUST be rejected. | Allowlist validation fails closed on the input nobody anticipated. Rejecting unknown fields also prevents a client typo becoming a silent no-op. | M | AT |
| `SEC-SEC-028` | Output encoding MUST be contextual and applied at the point of rendering, not at the point of storage. | Encoding at storage produces double-encoded content when the same value is rendered into a different context, and it makes the stored value wrong for export and API consumers. | M | AT, CR |
| `SEC-SEC-029` | Content originating outside the platform MUST be treated as hostile wherever it is rendered. This includes finding titles and descriptions, component names, scanner output, SBOM content, and migrated comments. | This is the control most likely to be overlooked because the content arrives through a trusted integration. A payload captured by a proxy scanner is attacker-authored text that will be rendered in the interface. | M | AT, PT |
| `SEC-SEC-030` | Rich text MUST be constrained by an allowlist of permitted elements and attributes. Arbitrary markup MUST NOT be sanitized and accepted. | Sanitizing arbitrary markup is an attempt to repair content of unknown intent; an allowlist defines what is permitted rather than guessing what is dangerous (`INV-WRK-10`). | M | AT, PT |

---

## 9. Injection Class Controls

| ID | Class | Control | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-031` | SQL and NoSQL injection | Parameterized statements exclusively. Dynamic query construction from input is prohibited, including in ORM escape hatches and reporting paths | M | AT, PT |
| `SEC-SEC-032` | Cross-site scripting | Contextual output encoding; constrained rich text; Content Security Policy without `unsafe-inline` or `unsafe-eval`; attachments served from a separate origin | M | AT, PT |
| `SEC-SEC-033` | Command injection | No shell invocation. Subprocess execution by argument array with server-generated paths | M | AT, PT |
| `SEC-SEC-034` | Path traversal | Server-generated storage paths; submitted filenames as metadata only; canonicalization and containment check before any filesystem access | M | AT, PT |
| `SEC-SEC-035` | Server-side request forgery | Egress destinations from configuration, never from data; allowlist; internal address ranges denied; redirects not followed to a non-allowlisted host | M | AT, PT |
| `SEC-SEC-036` | XML external entity and entity expansion | External entity resolution and DTD processing disabled; expansion limits | M | AT, PT |
| `SEC-SEC-037` | Server-side template injection | Templates are static artifacts; tenant-authored content is data, never a template | M | AT, PT |
| `SEC-SEC-038` | Unsafe deserialization | Structured formats with safe loaders only; no arbitrary type construction | M | AT, PT |
| `SEC-SEC-039` | Log injection | Structured logging with encoded field values; no concatenation of input into a log line | M | AT, PT |
| `SEC-SEC-040` | Header injection | Response header values validated; newlines rejected | M | AT, PT |

**On `SEC-SEC-033`.** After ADR-024 removed source code processing, the residual command-execution vector is argument injection through a submitter-influenced filename reaching a subprocess invocation (DOC-22 §13.2). Argument-array invocation with server-generated paths closes it.

**On `SEC-SEC-035`.** Three surfaces make this the platform's most exposed injection class: the API collection parser (external references), webhook delivery (tenant-configured destination), and connectors (integration target). Each is addressed at its own surface, and the general control is that a destination is configuration rather than data (`PRD-CON-007`, `PRD-API-054`).

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-041` | Every injection class control MUST be verified by automated test and MUST be in scope for penetration testing. | These are the classes the product exists to find in customers' software; shipping one is disqualifying. | M | AT, PT |

---

## 10. File Upload and Hostile Content

Evidence is *expected* to be malicious (`INV-ASM-21`), which makes the generic secure-upload pattern inadequate in three specific ways: a malware verdict cannot simply reject, the content must remain retrievable by authorized users, and it is stored alongside the identity of the system it exploits.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-042` | Uploads MUST be validated by content signature against the declared type, and a mismatch MUST be rejected. Size limits MUST apply per file and per request. | Declared type is attacker-controlled. Signature verification is the only type check that means anything. | M | AT |
| `SEC-SEC-043` | Uploads MUST be malware-scanned before becoming retrievable. A malicious verdict on evidence MUST flag rather than delete, requiring explicit acknowledgement before retrieval; a malicious verdict on any other upload category MUST reject. | For evidence the sample *is* the proof the finding rests on; deleting it destroys the evidence and makes the finding disputable. For every other category there is no such justification. | M | AT |
| `SEC-SEC-044` | Uploaded content MUST be stored with server-generated names on an origin distinct from the API, served only via short-lived signed references with non-inline disposition and content-type enforcement, and MUST NOT be rendered inline under any configuration. | Serving stored hostile content from the application origin makes it a same-origin execution risk. The separate origin is the control; disposition and type enforcement are defence in depth. | M | AT, PT |
| `SEC-SEC-045` | Archive and compressed uploads MUST enforce expansion ratio, entry count, total size, and path containment limits, and MUST reject entries resolving outside the extraction root. | Decompression bombs and archive path traversal are both routine and both trivially prevented by limits. | M | AT |
| `SEC-SEC-046` | Evidence retention MUST be bounded by a product maximum. Indefinite retention MUST NOT be configurable. | Accumulated exploit tooling is a growing liability for the platform as well as the tenant, and retention limits are a control that requires no ongoing effort (`INV-ASM-24`). | M | AT |

---

## 11. Document Parser Hardening

The platform parses untrusted structured documents at three surfaces: SBOM submission, scan result import, and API collection upload.

##### `SEC-SBM-003` — Parser process isolation

**Statement.** Document parsing and vulnerability matching MUST execute in a worker process separate from the API process, with independent resource limits. Parsing MUST NOT occur in a process serving requests.

**Rationale.** Intelligence databases are large and matching is memory-intensive; an out-of-memory condition in the API process fails request handling, and it would occur precisely during a portfolio sweep triggered by a high-profile disclosure — when the platform must be available. Isolation additionally contains a parser defect to a process that holds no session state (`CON-PLT-008`).

**Priority.** MUST_HAVE · **Verification.** AR, AT

##### `SEC-SBM-004` — Parser hardening

**Statement.** Every document parser MUST enforce: maximum document size; maximum nesting depth; maximum element and key count; maximum total expanded size; a bounded parse timeout; disabled external reference and entity resolution; safe deserialization with no arbitrary type construction; and rejection rather than truncation on limit breach. Limits MUST be declared per format in the parser registry. Parse failures MUST be logged with a submission identifier and MUST NOT echo document content.

**Rationale.** Submitted documents are untrusted input from a client the platform does not control. Without depth and size limits a malformed submission achieves denial of service through resource exhaustion; with external reference resolution enabled it achieves server-side request forgery and local file disclosure from inside the platform's network. Rejection rather than truncation matters because a truncated parse produces a partial component list, which under the closure logic of `PRD-SBM-053` could be interpreted as components having been removed. Content is excluded from logs because SBOM documents occasionally embed credentials in repository URLs.

**Priority.** MUST_HAVE · **Verification.** AT, PT
**Configurability.** T2 for limit values within a product ceiling; tenants MUST NOT raise them.

---

## 12. Web and Transport Controls

| ID | Control | Requirement | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-047` | Content Security Policy | Restrictive; no `unsafe-inline`, no `unsafe-eval`; nonce or hash based; `frame-ancestors 'none'`. Also covers clickjacking | M | AT |
| `SEC-SEC-048` | Strict Transport Security | `max-age` at least one year, `includeSubDomains`, preload eligible | M | AT |
| `SEC-SEC-049` | Content type options | `nosniff` on every response | M | AT |
| `SEC-SEC-050` | Referrer policy | `strict-origin-when-cross-origin` or stricter | M | AT |
| `SEC-SEC-051` | Cross-origin isolation | Opener policy and resource policy both `same-origin` | M | AT |
| `SEC-SEC-052` | Permissions policy | Deny by default; enable only what the interface uses | M | AT |
| `SEC-SEC-053` | Cross-origin resource sharing | Allowlist of tenant-configured origins; wildcard prohibited with credentials | M | AT |
| `SEC-SEC-054` | Cross-site request forgery | Cookie-authenticated state-changing requests require a synchronizer token or equivalent; token-authenticated API requests are exempt and MUST NOT accept cookie authentication | M | AT |
| `SEC-SEC-055` | Caching | `Cache-Control: no-store` on any response containing `CONFIDENTIAL` or `RESTRICTED` data | M | AT |

**On `SEC-SEC-054`.** The exemption is conditional and the condition is the control: an endpoint accepting *either* cookie or token authentication is vulnerable through the cookie path regardless of token handling. Separating them is what makes the exemption safe.

**On `SEC-SEC-055`.** Applies to intermediary and browser caching. It matters here because a shared workstation is common in operations contexts and a cached finding list is a disclosure after logout.

---

## 13. Platform Supply Chain

`CON-SEC-002`. A security posture product that cannot evidence its own supply chain integrity is not credible to the audience that evaluates it.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-056` | Build artifacts MUST be signed, with provenance attestation recording source revision, build environment, and inputs, and deployment MUST verify both. | Verification at deployment is what makes signing meaningful; signing without verification is a label. | M | AT |
| `SEC-SEC-057` | The platform MUST publish an SBOM for itself, updated per release. | It is requested in every enterprise security review, and declining to provide one from a product that requires them of others is not defensible. | M | DI |
| `SEC-SEC-058` | Dependency policy MUST be enforced at build time: known-vulnerable dependencies above a threshold fail the build; licence policy is enforced (`CON-LIC-001`); new direct dependencies require review. | A copyleft component in a commercial product is a distribution problem discovered at the worst possible time. | M | AT |
| `SEC-SEC-059` | Build pipelines MUST use least-privilege ephemeral credentials, MUST NOT expose signing keys to build steps, and MUST be protected against modification without review. | The pipeline is the highest-value target in the platform's own estate: compromising it compromises every tenant (DOC-26 T10). | M | AT, CR |
| `SEC-SEC-060` | Base images and runtime dependencies MUST be pinned by digest and rebuilt on a defined cadence. | Tag-based pinning is not pinning; a tag can be reassigned to different content. | M | AT |

---

## 14. Vulnerability Management of the Platform

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-061` | The platform MUST be subject to the security assessment regime it implements: dependency scanning, static analysis, secret scanning, and periodic penetration testing including the scenarios of DOC-26 §7. | A posture management product that does not manage its own posture cannot be recommended by its own logic. | M | PT, DI |
| `SEC-SEC-062` | The platform MUST publish a vulnerability disclosure policy with a defined contact and response commitment. | External researchers will find defects. Without a policy they disclose publicly or not at all. | M | DI |
| `SEC-SEC-063` | Security patches MUST be deployable within a defined window per severity, and the deployment path MUST NOT require a maintenance window for a patch-level change. | A patch requiring downtime is a patch deferred, leaving an unpatched version of a security platform running. | M | AT |

---

## 15. Zero Trust Enforcement Points

Zero Trust here means: no implicit trust from network position, and every request authenticated, authorized, and audited at the point of access.

| Point | Enforcement | Owner |
|---|---|---|
| Request entry | Authentication; tenant context from the credential, never a parameter | DOC-24 `SEC-TEN-004` |
| Every data access | Tenant policy at the engine; authorization decision as a required query input | DOC-24, DOC-02 `CON-PLT-037` |
| Every object reference | Re-validated independently of provenance | DOC-07 `SEC-AUZ-017` |
| Every field | Field-level restriction on every representation | DOC-07 `SEC-AUZ-021` |
| Graph traversal | Per-node evaluation | DOC-07 `SEC-AUZ-024` |
| Asynchronous work | Explicit tenant binding; no ambient inheritance | DOC-24 `SEC-TEN-006` |
| Service-to-service | Sender-constrained credentials; pinned scope | ADR-004, `SEC-AUZ-035` |
| Egress | Configured destinations only | `SEC-SEC-035` |
| Operator access | Break-glass; no standing access | DOC-24 `SEC-TEN-026` |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-064` | Network position MUST NOT confer trust. An internal caller MUST authenticate and be authorized identically to an external one. | Internal-only endpoints become externally reachable through misconfiguration, and the assumption that they cannot be is what makes the consequence severe. | M | AT, AR |

---

## 16. Logging and Monitoring Controls

Audit trail design is DOC-14. This section covers operational logging.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `SEC-SEC-065` | Operational logs MUST carry tenant identity as a dimension and MUST NOT carry tenant data as payload. Redaction MUST be enforced at the serialization boundary. | Telemetry is the most common residency and disclosure leak because it is designed for diagnosis rather than confidentiality (DOC-24 §8.1). | M | AT, CR |
| `SEC-SEC-066` | Security-relevant events MUST be detectable in monitoring: authentication failure patterns, authorization denial patterns, restricted-data reveal volume, configuration change, break-glass activation, and integrity verification failure. | These are the detection requirements of DOC-26 §10, and the residual risk profile there concentrates in detection rather than prevention. | M | AT |
| `SEC-SEC-067` | Detection alerts MUST be delivered to a destination outside the control of the principal whose activity triggered them. | An alert an insider can suppress is not a control, and operators can reach most platform-internal destinations (`SEC-PLT-008`). | M | AT, AR |
| `SEC-SEC-068` | Error responses MUST NOT disclose stack traces, framework versions, dependency versions, internal hostnames, or query fragments. | Each is reconnaissance, and the health endpoint plus error responses are the platform's unauthenticated information surface. | M | AT, PT |

---

## 17. Requirements Summary

Seventy-two requirements: `SEC-SEC-001` – `068`, plus `SEC-PTR-004`, `SEC-PTR-006`, `SEC-SBM-003`, `SEC-SBM-004`. All `MUST_HAVE`.

| Group | Count |
|---|---|
| ASVS posture | 1 |
| Authentication | 7 |
| Session | 5 |
| Local credentials | 3 |
| Cryptography | 6 |
| Secrets (incl. `SEC-PTR-004`) | 5 |
| Input and output | 4 |
| Injection classes | 11 |
| File upload (incl. `SEC-PTR-006`) | 6 |
| Parser hardening (`SEC-SBM-003`, `-004`) | 2 |
| Web and transport | 9 |
| Supply chain | 5 |
| Platform vulnerability management | 3 |
| Zero Trust | 1 |
| Logging | 4 |

Satisfies `CON-SEC-001`, `CON-SEC-002`, `PRD-IAM-001` – `012`, and the STRIDE cells of DOC-26 §6.

---

## 18. Closing

### 18.1 Extensibility

Controls are specified by property rather than by product, so a substitution is assessable. Parser limits are per-format registry entries, so a new format supplies its own. Injection class controls apply to every new surface by construction because they are framework properties rather than per-endpoint code.

**Deliberate rigidity.** No custom cryptography; no secret retrievable in plaintext without audited reveal; no inline rendering of uploaded content; no network-position trust; no configurable indefinite evidence retention; no tenant-raisable parser limits.

**Known extension cost.** Each new document format requires a hardened parser with declared limits and a malformed-input corpus. Each new egress surface requires destination allowlisting and a server-side request forgery test.

### 18.2 Residual risks

*Redaction completeness.* `SEC-SEC-025` and `SEC-SEC-065` place redaction at the serialization boundary, which covers the paths that serialize. A value reaching a log through string concatenation before serialization bypasses it, and `SEC-SEC-039` addresses that only for logging. This is the most likely residual leakage path and DOC-16 owes a corpus test.

*Malware verdict on evidence.* `SEC-SEC-043` requires flag-not-delete for evidence, which means the platform knowingly stores confirmed-malicious content. The controls are isolation, acknowledgement, and bounded retention; the residual is that the evidence store is a curated malware collection with the identity of the systems it targets (DOC-26 T7).

*Vault decision.* ⚠ OQ-026 unresolved. §7 is written to accommodate both, but a platform-provided default store is a new asset of the highest value, and its design would require its own review.

### 18.3 Notes for downstream documents

| Document | Note |
|---|---|
| DOC-08 | `SEC-SEC-047` prohibits inline script and style, which constrains the interface build; `SEC-SEC-055` prohibits caching of confidential responses |
| DOC-14 | Owes the audit trail design implementing the Level 3 uplift |
| DOC-15 | Owes the secrets store selection (OQ-026), key store, TLS termination, separate attachment origin, signed-reference issuance, image pinning, and build pipeline protection |
| DOC-16 | Owes: a control-by-control ASVS conformance matrix; the injection corpus per class; a redaction corpus for `SEC-SEC-025`; malformed-document corpora per parser format; and a test asserting no endpoint accepts both cookie and token authentication (`SEC-SEC-054`) |
| DOC-21 | Owes connector credential handling under §7 |

### 18.4 Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.0.0 | 2026-08-04 | Principal Security Architect; Principal Application Security Engineer | Initial content-complete version. Specifies the ASVS Level 2 baseline with five enumerated Level 3 uplifts, each justified by asset value, and records why the remaining chapters are deliberately not uplifted. Specifies authentication, session, local credential, cryptography, and secrets controls; defines the four identifiers forward-referenced from earlier documents — test credential protection, intake surface hardening, parser process isolation, and parser hardening. Specifies input validation, contextual output encoding, and eleven injection class controls with the residual command-execution vector after ADR-024 identified. Specifies file upload controls including flag-not-delete for evidence with its residual risk stated. Specifies nine web and transport controls, five supply chain controls, platform vulnerability management, nine Zero Trust enforcement points, and four logging controls. Seventy-two requirements. | Pending |

---

*End of DOC-06.*
