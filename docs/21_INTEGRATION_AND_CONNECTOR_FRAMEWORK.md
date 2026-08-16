---
document_id:    DOC-21
title:          Integration and Connector Framework
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        1.0.0
status:         In review
owner:          Chief Software Architect
authors:        [Chief Software Architect, Principal Application Security Engineer]
reviewers:      []
last_updated:   2026-08-04
tier:           4
prerequisites:  [DOC-00, DOC-01, DOC-03, DOC-05, DOC-06]
depends_on:     [DOC-00, DOC-01, DOC-02, DOC-03, DOC-05, DOC-06, DOC-11, DOC-24]
supersedes:     null
adrs_relied_on: [ADR-004, ADR-024, ADR-028]
open_questions: [OQ-025, OQ-026]
requirement_domains: [CON]
security_review_required: true
---

# 21 — Integration and Connector Framework

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope) · 2. [The Connector Contract](#2-the-connector-contract) · 3. [Lifecycle and Versioning](#3-lifecycle-and-versioning) · 4. [Credentials](#4-credentials) · 5. [Failure Classification and Retry](#5-failure-classification-and-retry) · 6. [Health and Circuit Breaking](#6-health-and-circuit-breaking) · 7. [Egress Constraint](#7-egress-constraint) · 8. [Data Minimization](#8-data-minimization) · 9. [Connector Catalogue](#9-connector-catalogue) · 10. [Outbound Propagation](#10-outbound-propagation) · 11. [Intelligence Provisioning](#11-intelligence-provisioning) · 12. [Identity Provisioning](#12-identity-provisioning) · 13. [Air-Gapped Operation](#13-air-gapped-operation) · 14. [Requirements](#14-requirements) · 15. [Closing](#15-closing)

---

## 1. Purpose and Scope

**In scope.** The connector contract; lifecycle and versioning; credential handling and rotation; failure classification driving retry; health and circuit breaking; egress constraint; data minimization; the connector catalogue; outbound propagation with divergence detection; vulnerability intelligence provisioning including offline; identity provisioning; air-gapped operation.

**Out of scope.** File-based ingestion and parsing (DOC-11); the SBOM submission API, which is inbound rather than a connector (DOC-22); security controls (DOC-06); API surface (DOC-05 §23).

**LC-01.** Requirements are `PRD-CON-015` onward, continuing DOC-01's sequence.

**LC-02 — The distinction from ingestion.** DOC-11 parses files; this document integrates with running systems. Conflating them produces a design where a parse failure and a network failure share a code path despite having nothing in common — different retry semantics, different failure classes, different remediation.

---

## 2. The Connector Contract

```
ConnectorDefinition
  ├─ code, connector_kind
  ├─ direction              INBOUND | OUTBOUND | BIDIRECTIONAL_REFERENCE
  ├─ capabilities           declared operations
  ├─ credential_kinds       accepted credential types
  ├─ minimum_permissions    documented least-privilege set on the target
  ├─ configuration_schema   validated; includes egress destinations
  ├─ egress_destinations    from configuration only, never from data
  ├─ outbound_data_content  documented per operation (§8)
  ├─ rate_budget            requests per period against the target
  ├─ health_semantics       what constitutes success
  └─ version
```

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-CON-015` | Every integration MUST be implemented as a connector conforming to the contract. Ad-hoc integration code MUST NOT exist outside it. | Ad-hoc integrations accumulate as unrelated code with inconsistent failure handling, no common health view, and no consistent credential treatment. The contract makes health, rotation, and failure reporting uniform rather than per-integration afterthoughts. | M | AT, AR |
| `PRD-CON-016` | Each connector MUST document the minimum permission set required on the target system. | Without it, a tenant supplies an administrative token for convenience, and the platform then holds broader access to their estate than it needs — which is the highest-value asset in DOC-26 §3.3. | M | DI |
| `PRD-CON-017` | Connector configuration MUST be validated against its schema before activation, and an invalid configuration MUST be rejected with a specific diagnosis. | An invalid connector fails at first use, which is typically a scheduled run nobody is watching, and the failure presents as missing data rather than as an error. | M | AT |

---

## 3. Lifecycle and Versioning

`DRAFT → CONFIGURED → ACTIVE → SUSPENDED ⇄ ACTIVE → RETIRED ⊗`, with `FAILED_VALIDATION` from `DRAFT`.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-CON-018` | Connector versions MUST be independently deployable from tenant configuration, and a version upgrade MUST NOT require a tenant to reconfigure. | Target systems change their interfaces without notice. If every adapter update required tenant reconfiguration, tenants would defer it and integrations would fail silently. | M | AT |
| `PRD-CON-019` | A connector MUST be individually suspendable, and suspension MUST halt its operations while retaining its configuration and credential reference. | Suspension is the response to a target-system incident, a rate-limit breach, or a security concern, and it must not require reconfiguration afterwards. | M | AT |
| `PRD-CON-020` | Retiring a connector MUST retain its historical data provenance and MUST NOT delete records it produced. | Findings carry the connector as provenance (`PRD-ING-010`). Deleting the connector would orphan that attribution. | M | AT |

---

## 4. Credentials

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-CON-021` | Credentials MUST be held in the secrets store by reference, MUST NOT be retrievable in plaintext after entry, and MUST NOT appear in configuration, logs, telemetry, error messages, or exports. | The connector credential store is access *to* the customer's engineering estate, not data about it. An administrator who can read one can exfiltrate access to every integrated system. | M | AT, CR |
| `PRD-CON-022` | Rotation MUST be possible without reconfiguring the connector, MUST support an overlap period where both old and new credentials are valid, and MUST verify the new credential before retiring the old. | Rotation without overlap produces an outage window. Rotation without verification produces an outage discovered after the old credential is gone. | M | AT |
| `PRD-CON-023` | Credentials MUST support expiry, and approaching expiry MUST notify the connector owner in advance of a configurable window. | Unexpiring credentials become permanent, and permanent access to a customer's source control is what an attacker targeting the platform is after. | M | AT |
| `PRD-CON-024` | An authentication or authorization failure MUST NOT be blindly retried. It MUST mark the connector unhealthy, notify the owner, and stop until the credential is corrected. | Blind retry on an authentication failure locks the account on the target system, converting a configuration problem in the platform into an outage in the customer's engineering estate. | M | AT |

**On `PRD-CON-024`.** This is the failure most likely to damage a customer relationship: the platform's misconfiguration causing an outage in *their* systems. Classification-driven retry (§5) exists primarily to prevent it.

⚠ **Working assumption (OQ-026):** the secrets store is either an integrated enterprise vault or a platform-provided default. §4 is written against the reference contract and does not change with the decision.

---

## 5. Failure Classification and Retry

Retry behaviour differs by failure class. Blind retry is unsafe; blind non-retry discards recoverable work.

| Class | Examples | Retry | Health effect |
|---|---|---|---|
| `AUTHENTICATION` | Credential invalid or expired | **None** | Unhealthy immediately; owner notified |
| `AUTHORIZATION` | Insufficient permission on the target | **None** | Unhealthy; minimum permission set surfaced |
| `RATE_LIMIT` | Target throttling | Honour `Retry-After`; otherwise backoff | Degraded; budget reduced |
| `TRANSIENT` | Timeout, connection reset, 5xx | Exponential backoff with jitter, bounded attempts | Degraded after consecutive failures |
| `CONFIGURATION` | Endpoint not found, schema mismatch | **None** | Unhealthy; diagnosis surfaced |
| `DATA` | A record the target rejects | Skip the record, continue the run | Healthy; record quarantined |
| `TARGET_UNAVAILABLE` | Planned or unplanned outage | Backoff with a longer ceiling | Degraded; circuit opens on sustained failure |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-CON-025` | Every failure MUST be classified, and retry behaviour MUST be determined by class. Undifferentiated retry MUST NOT occur. | Each class has a different correct response, and using one policy for all is wrong for most of them — including in the way that causes an outage on the target (`PRD-CON-024`). | M | AT |
| `PRD-CON-026` | A `DATA` class failure MUST NOT fail the run. The record MUST be quarantined with its reason and the run MUST continue. | One record the target rejects must not discard the run's other work; this is the same partial-failure principle as `PRD-ING-007`. | M | AT |
| `PRD-CON-027` | Retry MUST honour the target's rate limit signals, and the platform MUST reduce its budget against a target that signals throttling rather than continuing at the configured rate. | Exhausting a customer's API quota affects their other systems, which converts a security tool into an operational incident and ends the deployment. | M | AT |

---

## 6. Health and Circuit Breaking

```
ConnectorHealth
  ├─ last_success_at, last_attempt_at
  ├─ consecutive_failures
  ├─ last_failure_class, last_failure_detail
  ├─ circuit_state           CLOSED | HALF_OPEN | OPEN
  ├─ backoff_until
  └─ rate_budget_remaining
```

Circuit opens after a configured consecutive-failure threshold, half-opens after a backoff period admitting a single probe, and closes on probe success.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-CON-028` | Health MUST be observable per connector per tenant, including last success, consecutive failures, failure class, and circuit state. | An integration failing for weeks is the primary mechanism by which coverage gaps form, and it produces no user-visible symptom other than data that stops changing — which resembles stability (PP-9). | M | AT |
| `PRD-CON-029` | Circuit opening MUST notify the connector owner and MUST record the condition. A suspended integration nobody is told about MUST NOT be possible. | A silently suspended integration is a permanent coverage gap, and the coverage metrics will show the gap without anyone knowing its cause. | M | AT |
| `PRD-CON-030` | Health state MUST feed coverage reporting, such that an unhealthy connector is reflected in the coverage of the data it supplies. | Otherwise coverage reports current data for an asset whose supplying integration has been failing for a month. This is PP-1 applied to integration health. | M | AT |
| `PRD-CON-031` | The platform MUST record and expose a per-connector success rate over a period, and MUST alert where it degrades below a threshold without the circuit opening. | Intermittent failure is more damaging than total failure because it looks like partial success. A 40% success rate produces data that appears current and is not. | M | AT |

**On `PRD-CON-031`.** Total failure opens the circuit and notifies. Intermittent failure does neither: it delivers some data, so nothing looks broken, and the resulting picture is silently incomplete. The success-rate measure exists for exactly that case.

---

## 7. Egress Constraint

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-CON-032` | Egress destinations MUST come from validated configuration and MUST NOT be derived from data, a record field, a redirect, or user input. | A connector accepting a data-derived destination is a server-side request forgery primitive positioned inside the platform's network, operating with the platform's credentials. The destination is configuration, and configuration is validated. | M | AT, PT |
| `PRD-CON-033` | Destinations MUST be validated against an allowlist policy, MUST NOT resolve to internal or link-local address ranges, and resolution MUST be re-checked at connection time. | Re-checking at connection time closes the rebinding gap between validation and use, which is otherwise a bypass of the allowlist. | M | AT, PT |
| `PRD-CON-034` | Redirects MUST NOT be followed to a destination outside the allowlist. | A permitted destination redirecting to an internal address is the standard bypass of destination allowlisting. | M | AT, PT |
| `PRD-CON-035` | Every connector MUST be individually disableable, and the platform MUST remain fully functional with all connectors disabled, degrading explicitly. | Required for air-gapped deployment (`CON-DEP-001`) and for tenants whose governance prohibits specific integrations. A platform with a mandatory external dependency cannot be deployed where it is most needed. | M | AT |

---

## 8. Data Minimization

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-CON-036` | Each connector MUST transmit only the minimum data necessary for its function, and its outbound content MUST be documented per operation. | Data leaving the platform is data leaving the tenant's control. A connector whose outbound payload is undocumented cannot be assessed by the tenant's data governance function, which makes it unapprovable in regulated environments and therefore unusable. | M | DI |
| `PRD-CON-037` | Outbound content MUST NOT include credentials, secret values, evidence content, or per-person workload data, at any configuration. | Same reasoning as export and notification: an outbound payload leaves the platform's control and cannot be recalled. | M | AT, CR |
| `PRD-CON-038` | Outbound content MUST be constrained to the scope the target integration is configured for, and MUST NOT include records outside it. | A connector configured for one business unit must not transmit another's findings, and the target system has no scope enforcement to compensate. | M | AT, PT |
| `PRD-CON-039` | Outbound egress MUST be constrained by the tenant's residency designation. | Connector egress is one of the nine secondary residency paths of DOC-24 §8.1 and is frequently overlooked because it is treated as integration rather than as data movement. | M | AT |

---

## 9. Connector Catalogue

| Kind | Direction | Purpose | Notes |
|---|---|---|---|
| **Vulnerability intelligence** | Inbound | CVE, weakness, severity, exploit prediction, known-exploited, exploitability statements | Offline provisioning required (§11) |
| **Scanner result pull** | Inbound | Retrieve results from a scanner platform on a schedule | Results pass to Ingestion (DOC-11) |
| **Identity provider** | Bidirectional reference | Authentication, provisioning, deprovisioning | §12 |
| **Notification channel** | Outbound | Email, chat platforms | Content restricted per DOC-13 §9 |
| **Work tracker** | Outbound reference | Propagate remediation obligations | §10. **Not bidirectional state sync** |
| **Service management** | Outbound reference | Change and incident correlation | Same model as work tracker |
| **Secret validity check** | Outbound | Verify whether a detected credential is live | Rate-limited and audited; itself an authentication attempt against a third party |
| **Object storage** | Bidirectional | Evidence, attachments, exports | Platform infrastructure rather than tenant integration |
| **Model provider** | Outbound | AI capabilities | Category and residency constrained (DOC-10 §7) |
| **Audit export** | Outbound | Continuous audit stream to a tenant system | Verifiable copy (`SEC-AUD-036`) |
| **Container registry** | Inbound | **Reserved (DF-01), rejected in this release** | ADR-026 |
| **Source control** | — | **Not offered** | ADR-024. No source code access; repository URLs are reference labels only |

**On the last two rows.** Their absence is the architecture, not an omission. ADR-024 removes source control from the connector catalogue entirely, which eliminates the credential class that would otherwise be the platform's highest-value asset (DOC-26 §3.3). Container registry access is reserved and rejected at the application layer so that enabling it later is additive.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-CON-040` | A source control connector MUST NOT be offered in this release, and no connector MUST accept credentials granting source code access. | ADR-024. Declining the capability avoids holding the asset, which is the correct trade when the asset is this valuable. | M | CR, AR |
| `PRD-CON-041` | Secret validity checking MUST be rate-limited, individually audited, and disableable per credential type, and MUST NOT be enabled by default. | The check is itself an authentication attempt against a third-party system. Misdirected or at volume it could constitute unauthorized access, and some tenants prohibit it outright. | M | AT |

---

## 10. Outbound Propagation

ADR-028 makes the platform the system of record for application security work. NG-06 bounds that to security work. Together they define the correct posture: **the platform does not synchronize state bidirectionally with an external tracker.**

**Why not bidirectional.** Two systems of record for one item means neither is one (PP-10). Reconciliation requires deciding which wins, and any answer is wrong in some case — a finding closed externally but not verified must not close here.

**What is offered instead.** One-way creation of a reference item, with the platform record authoritative and divergence surfaced rather than reconciled.

```
OutboundReference
  ├─ subject_kind, subject_id        the platform record, authoritative
  ├─ connector_id, external_id
  ├─ external_state                  observed, informational only
  ├─ last_observed_at
  ├─ divergence_detected_at
  └─ divergence_kind                 CLOSED_EXTERNALLY | REOPENED_EXTERNALLY
                                     | DELETED_EXTERNALLY | STATE_MISMATCH
```

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-CON-042` | Outbound propagation MUST be one-way. External state MUST NOT overwrite platform state, and the platform record MUST remain authoritative. | Bidirectional synchronization reproduces the failure of a generic tracker used for vulnerability management: closing the external ticket closes the finding, whether or not the vulnerability is gone. | M | AT, AR |
| `PRD-CON-043` | Divergence MUST be detected and surfaced for human resolution, and MUST NOT be automatically reconciled. | The decision "has this finding actually been remediated" is a judgement about the world, not a data merge. Surfacing it puts the decision with a human, which is where it belongs. | M | AT |
| `PRD-CON-044` | External deletion of a referenced item MUST surface as divergence and MUST NOT affect the platform record. | Otherwise a tracker cleanup silently removes the platform's remediation obligation. | M | AT |
| `PRD-CON-045` | Outbound content MUST be restricted to what the target audience is authorized for and MUST NOT include finding detail beyond a reference and a scope-appropriate summary. | The external tracker has no scope enforcement, and its audience is typically broader than the finding's authorized readership. | M | AT, PT |

**On `PRD-CON-045`.** The most common integration mistake is propagating the full finding — description, evidence references, exploit detail — into a tracker readable by an entire engineering organization. A reference plus a minimal summary is what the remediating team needs; the detail stays where scope is enforced.

⚠ **Working assumption (OQ-025):** the incumbent tracker for migration is not identified, so the migration adapter (`PRD-ING-013`) is generic over structured export. Outbound propagation is unaffected.

---

## 11. Intelligence Provisioning

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-CON-046` | Intelligence datasets MUST be provisioned as signed bundles whose signature and integrity are verified before use, and a bundle failing verification MUST be rejected with the prior version retained. | An unverified bundle is an injection path into the data driving every prioritization decision — an attacker altering it could suppress a vulnerability platform-wide. Retaining the prior version means rejection degrades freshness rather than capability. | M | AT |
| `PRD-CON-047` | Offline provisioning MUST be supported by the same bundle format as online, applied through an operator procedure. | `CON-DEP-001`. Using a different format for air-gapped deployment means the offline path is tested less and diverges. | M | AT |
| `PRD-CON-048` | Provisioning failure MUST NOT halt matching. Matching MUST continue against the last verified version with staleness recorded and surfaced. | Halting on update failure converts an intelligence-source outage into a platform outage, when the correct behaviour — continue with honest staleness — is both available and more useful (`PRD-SBM-063`). | M | AT |
| `PRD-CON-049` | Intelligence age MUST be exposed and MUST alert beyond a configurable threshold. | In air-gapped deployment nobody is notified by the absence of an update; the platform must notice on the operator's behalf. | M | AT |

---

## 12. Identity Provisioning

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-CON-050` | The platform MUST support automated provisioning and deprovisioning from the tenant's identity source, and deprovisioning MUST revoke sessions within the bound of `NFR-SEC-001`. | Manual user lifecycle in an organization with thousands of occasional users does not remain accurate, and the inaccuracy is in the unsafe direction: departed users retain access. | M | AT |
| `PRD-CON-051` | Identity synchronization MUST NOT create, modify, or remove role assignments. It MUST manage principal existence and attributes only. | Group-to-role mapping is authorization configuration. Driving it from an external directory would place the platform's authorization model under the control of whoever administers that directory — outside the platform's audit and separation-of-duties controls. | M | AT, AR |
| `PRD-CON-052` | Synchronization conflicts MUST be reported rather than resolved by overwrite, and a principal deactivated externally but active in the platform MUST surface as a conflict. | An overwrite resolution can either resurrect a departed user or deactivate an active one, and both fail silently. | M | AT |
| `PRD-CON-053` | The platform MUST support group-to-role mapping as tenant configuration evaluated at authentication, distinct from `PRD-CON-051`. | Tenants legitimately want directory groups to drive roles. Doing it as *platform-side configuration evaluated at authentication* keeps the mapping under platform audit and separation-of-duties control, unlike letting the directory write assignments. | S | AT |

**On `PRD-CON-051` and `PRD-CON-053` together.** The distinction is where the mapping lives. A directory that writes role assignments places authorization under external control; platform-side mapping evaluated at authentication achieves the same operational outcome while keeping the authorization model auditable within the platform.

---

## 13. Air-Gapped Operation

| Connector | Air-gapped behaviour |
|---|---|
| Vulnerability intelligence | Offline signed bundles; staleness surfaced |
| Scanner result pull | Unavailable; file import instead (DOC-11) |
| Identity provider | Internal provider if present; local authentication otherwise |
| Notification | Internal mail relay if present; in-product otherwise |
| Work tracker, service management | Internal target if reachable; otherwise disabled |
| Secret validity check | Unavailable |
| Model provider | Self-hosted endpoint if present; otherwise AI disabled with non-AI fallbacks |
| Audit export | Internal target if present |
| Object storage | Internal |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-CON-054` | In air-gapped deployment every unavailable connector MUST be explicitly disabled with the affected capability and its consequence stated in the interface, not silently absent. | An air-gapped instance silently matching against six-month-old intelligence is PP-1 violated in its most consequential form. Explicit unavailability is the only honest option. | M | AT |
| `PRD-CON-055` | Air-gapped deployment MUST NOT require a code path distinct from connected deployment. It MUST be the same platform with connectors disabled. | Divergent code paths per deployment model produce defects appearing in one model only, found by the customer (`SEC-TEN-044`). | M | AR, AT |

---

## 14. Requirements

Forty-one requirements, `PRD-CON-015` – `055`, all `MUST_HAVE` except `PRD-CON-053`.

| Group | IDs | Count |
|---|---|---|
| Contract | `015` – `017` | 3 |
| Lifecycle | `018` – `020` | 3 |
| Credentials | `021` – `024` | 4 |
| Failure and retry | `025` – `027` | 3 |
| Health | `028` – `031` | 4 |
| Egress | `032` – `035` | 4 |
| Data minimization | `036` – `039` | 4 |
| Catalogue | `040` – `041` | 2 |
| Outbound propagation | `042` – `045` | 4 |
| Intelligence | `046` – `049` | 4 |
| Identity | `050` – `053` | 4 |
| Air-gapped | `054` – `055` | 2 |

Satisfies `PRD-CON-001` – `014` and `CON-DEP-001`.

---

## 15. Closing

### 15.1 Extensibility

A new connector is a definition, an adapter, a credential kind declaration, a documented minimum permission set, documented outbound content, and health semantics — all six, because a connector missing any of them cannot be governed, rotated, monitored, or approved by a tenant's data governance function.

**Reserved.** Container registry (DF-01), rejected at the application layer. **Not offered and not reserved:** source control, per ADR-024 — its absence is the architecture.

**Deliberate rigidity.** Egress destinations from configuration only (`PRD-CON-032`); no bidirectional state sync (`PRD-CON-042`); identity sync does not write role assignments (`PRD-CON-051`); no blind retry on authentication failure (`PRD-CON-024`); every connector disableable (`PRD-CON-035`).

**Known extension cost.** Each connector requires ongoing maintenance against a target interface that changes without notice, and the failure mode is silent field mis-mapping rather than an error. Each new outbound connector requires a residency assessment and an outbound content declaration.

### 15.2 Security considerations

Connectors are the platform's outbound trust boundary and its largest credential concentration: collectively the credential store holds access to a tenant's scanners, trackers, and identity provider. A compromise of that store is a compromise of the tenant's engineering estate independently of anything stored in the platform (DOC-26 §3.3, T8).

| Risk | Control |
|---|---|
| Credential exfiltration by an administrator | Vault reference, non-retrievable after entry (`PRD-CON-021`) |
| Over-scoped credentials | Documented minimum permission set (`PRD-CON-016`) |
| Server-side request forgery | Destinations from configuration; allowlist; connection-time re-resolution; redirects not followed (`PRD-CON-032` – `034`) |
| Account lockout on the target | Classification-driven retry (`PRD-CON-024`) |
| Data egress beyond scope | Scope-constrained outbound content (`PRD-CON-038`, `-045`) |
| Residency breach | Egress residency constraint (`PRD-CON-039`) |
| Intelligence tampering | Signed bundle verification (`PRD-CON-046`) |
| Authorization under external control | Identity sync does not write assignments (`PRD-CON-051`) |

**Residual risks.** Silent field mis-mapping after a target interface change produces incorrect data that appears valid; the control is a fixture corpus per connector, which decays as targets change. A connector credential with more permission than documented is not detectable by the platform — it can document the minimum but cannot verify what was granted.

### 15.3 Notes for downstream documents

| Document | Note |
|---|---|
| DOC-11 | Scanner result pull delivers into the ingestion pipeline; the parse boundary is DOC-11's |
| DOC-12 | Connector health belongs on the operations composition; intermittent-failure success rate is the measure most likely to be omitted (`PRD-CON-031`) |
| DOC-15 | Owes the secrets store (OQ-026), egress allowlist enforcement, outbound network policy, and the intelligence bundle distribution mechanism |
| DOC-16 | Owes: a fixture corpus per connector; server-side request forgery tests including redirect and rebinding; an authentication-failure test asserting no retry; a bundle signature rejection test |
| DOC-17 | ⚠ OQ-025 unresolved — the migration adapter target is unidentified |

### 15.4 Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.0.0 | 2026-08-04 | Chief Software Architect; Principal Application Security Engineer | Initial content-complete version. Specifies the connector contract with a documented minimum permission set per target; lifecycle with version upgrades independent of tenant configuration; credential handling with overlap-period rotation and verification before retirement; seven failure classes driving retry, with no retry on authentication failure identified as the control preventing the platform causing an outage in the customer's estate; health including a success-rate measure for intermittent failure, which total-failure circuit breaking does not catch; egress constraint with connection-time re-resolution and redirect refusal; data minimization with documented outbound content per operation; the connector catalogue including the deliberate absence of source control; one-way outbound propagation with divergence surfaced rather than reconciled; signed intelligence bundles with the same format offline and online; identity provisioning that does not write role assignments, with platform-side group mapping as the alternative; and air-gapped operation using the same code path with connectors disabled. Forty-one requirements. | Pending |

---

*End of DOC-21.*
