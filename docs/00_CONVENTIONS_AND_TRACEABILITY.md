# 00 — Conventions and Traceability

**Product:** AI-native Application Security Posture Management Platform (AI ASPM)
**Document ID:** DOC-00
**Version:** 1.0.3
**Status:** Approved — Baseline
**Owner:** Chief Software Architect
**Last updated:** 2026-08-04
**Supersedes:** None

---

## Table of Contents

1. [Purpose and Authority of This Document](#1-purpose-and-authority-of-this-document)
2. [Audience and Reading Paths](#2-audience-and-reading-paths)
3. [The Documentation Corpus](#3-the-documentation-corpus)
4. [Language Policy](#4-language-policy)
5. [Normative Language (RFC 2119)](#5-normative-language-rfc-2119)
6. [Requirement Identification Scheme](#6-requirement-identification-scheme)
7. [Requirement Anatomy and Mandatory Attributes](#7-requirement-anatomy-and-mandatory-attributes)
8. [Traceability Contract](#8-traceability-contract)
9. [Configurability Conventions](#9-configurability-conventions)
10. [Naming Conventions](#10-naming-conventions)
11. [Diagram Conventions](#11-diagram-conventions)
12. [Document Structure Template](#12-document-structure-template)
13. [Architecture Decision Record Conventions](#13-architecture-decision-record-conventions)
14. [Open Question Conventions](#14-open-question-conventions)
15. [Security Annotation Conventions](#15-security-annotation-conventions)
16. [Non-Functional Requirement Conventions](#16-non-functional-requirement-conventions)
17. [Corpus Versioning and Change Control](#17-corpus-versioning-and-change-control)
18. [Review Gates and Definition of Done](#18-review-gates-and-definition-of-done)
19. [Quality Bar and Prohibited Patterns](#19-quality-bar-and-prohibited-patterns)
20. [Repository Layout and Tooling](#20-repository-layout-and-tooling)
21. [Changing These Conventions](#21-changing-these-conventions)

**Appendices**
- [A. Domain Code Registry](#appendix-a--domain-code-registry)
- [B. Consolidated ADR Index](#appendix-b--consolidated-adr-index)
- [C. Legacy Requirement ID Alias Table](#appendix-c--legacy-requirement-id-alias-table)
- [D. Reserved Words and Forbidden Terms](#appendix-d--reserved-words-and-forbidden-terms)
- [E. Document Front-Matter Schema](#appendix-e--document-front-matter-schema)

---

## 1. Purpose and Authority of This Document

### 1.1 Purpose

This document defines the conventions that govern every other document in the AI ASPM documentation corpus. It establishes:

- how requirements are identified, so that any statement in the corpus can be referenced unambiguously;
- how requirements trace forward into design, schema, API, code, and tests, so that implementation completeness is *verifiable* rather than *asserted*;
- how names are formed, so that a single concept has exactly one name across the domain model, database, API, UI, and glossary;
- how decisions and unresolved questions are recorded, so that the reasoning behind the system survives the people who made it.

### 1.2 Why this document exists at all

The corpus is projected at roughly 1,300–1,650 pages across 26 documents. A body of documentation at that scale has a specific failure mode: it becomes internally inconsistent faster than any reader can detect, and the inconsistencies are discovered during implementation, at the point where they are most expensive to fix.

The two mechanisms that prevent this are **stable identifiers** and **a single naming authority**. Without stable identifiers, a "single source of truth" cannot be audited against an implementation — a reviewer can confirm that a document *says* something, but not that the system *does* it. Without a single naming authority, the same concept acquires three names (`business_unit`, `org_unit`, `bu`) and the divergence propagates into the schema, where it becomes permanent.

**Rationale for treating this as Document 00 rather than an appendix:** conventions applied retroactively are conventions that were never applied. Every requirement written before the ID scheme exists must be rewritten to adopt it, and rewriting is where requirements silently change meaning. This document is therefore authored first and frozen before Document 01 begins.

### 1.3 Authority

This document is **normative and binding** on the entire corpus.

- Where any other document conflicts with this one on a matter of convention (naming, identification, structure, traceability), **this document prevails** and the other document MUST be corrected.
- Where any other document conflicts with this one on a matter of *substance* (what the product does), the **substantive document prevails** and this document is out of scope for that dispute.
- Conventions defined here MAY be extended by a downstream document for domain-specific needs, but MUST NOT be contradicted. Extensions MUST be declared in that document's "Local Conventions" section and MUST NOT redefine an existing term.

### 1.4 Scope

**In scope:** identification, traceability, naming, structure, diagram notation, decision recording, review gates, corpus versioning.

**Out of scope:** product requirements (DOC-01), architecture (DOC-02), domain semantics (DOC-03), and all other substantive content. This document defines *how to write*, never *what to build*.

---

## 2. Audience and Reading Paths

The corpus serves five distinct audiences with different entry points. A reader who starts at Document 01 and proceeds sequentially will spend effort disproportionate to their role.

| Audience | Purpose | Recommended path |
|---|---|---|
| **Engineering leadership / Principal Engineers** | Assess architectural soundness and risk before committing resources | 00 → 19 (ADRs) → 03 → 02 → 24 → 26 → 20 |
| **Implementing engineers** | Build a specific module without guessing | 00 → 18 (Glossary) → 03 → 04 → 05 → module-specific doc → 16 |
| **Security reviewers / Auditors** | Verify control coverage and threat treatment | 00 → 06 → 07 → 24 → 26 → 14 → 16 |
| **Product management** | Validate scope, sequencing, and market fit | 01 → 17 → 20 → 12 |
| **QA / Test engineering** | Derive test coverage from requirements | 00 → 16 → traceability matrix → 09 → 05 |

**Convention:** every document MUST declare its own prerequisites in front matter (`prerequisites:`), so a reader arriving from any direction knows what they are assumed to have read. A document with no prerequisites other than DOC-00 and DOC-18 MUST state that explicitly rather than omitting the field.

---

## 3. The Documentation Corpus

### 3.1 Corpus inventory

Twenty-six documents. Numbering is **permanent and non-reusable**: if a document is withdrawn, its number is retired, never reassigned. This guarantees that a reference such as `DOC-24 §3.2` in a code comment, commit message, or ticket remains meaningful for the lifetime of the product.

| ID | Filename | Tier | Status | Pages (est.) |
|---|---|---|---|---|
| DOC-00 | `00_CONVENTIONS_AND_TRACEABILITY.md` | 0 — Foundation | **Approved** | 14 |
| DOC-01 | `01_PRODUCT_REQUIREMENT.md` | 1 — Problem | Not started | 65–82 |
| DOC-02 | `02_SYSTEM_ARCHITECTURE.md` | 3 — Structure | Not started | 55–70 |
| DOC-03 | `03_DOMAIN_MODEL.md` | 2 — Model | Not started | 83–102 |
| DOC-04 | `04_DATABASE_DESIGN.md` | 3 — Structure | Not started | 93–117 |
| DOC-05 | `05_API_SPECIFICATION.md` | 4 — Contracts | Not started | 105–133 |
| DOC-06 | `06_SECURITY_REQUIREMENT.md` | 4 — Contracts | Not started | 62–77 |
| DOC-07 | `07_RBAC_PERMISSION.md` | 2 — Model | Not started | 46–58 |
| DOC-08 | `08_UI_UX_GUIDELINE.md` | 6 — Experience | Not started | 72–89 |
| DOC-09 | `09_WORKFLOW.md` | 5 — Behavior | Not started | 68–87 |
| DOC-10 | `10_AI_REQUIREMENT.md` | 5 — Behavior | Not started | 50–65 |
| DOC-11 | `11_IMPORT_EXPORT.md` | 4 — Contracts | Not started | 59–76 |
| DOC-12 | `12_REPORTING.md` | 6 — Experience | Not started | 49–63 |
| DOC-13 | `13_NOTIFICATION.md` | 5 — Behavior | Not started | 36–45 |
| DOC-14 | `14_AUDIT_LOGGING.md` | 5 — Behavior | Not started | 28–36 |
| DOC-15 | `15_DEPLOYMENT.md` | 7 — Operations | Not started | 56–70 |
| DOC-16 | `16_TESTING_STRATEGY.md` | 7 — Operations | Not started | 45–56 |
| DOC-17 | `17_PRODUCT_ROADMAP.md` | 8 — Closure | Not started | 30–40 |
| DOC-18 | `18_GLOSSARY.md` | 0 / 8 | **Seeded** | 15–25 |
| DOC-19 | `19_DECISION_LOG.md` | 0 / 8 | **Seeded** | 34–45 |
| DOC-20 | `20_OPEN_QUESTIONS.md` | 0 / 8 | **Seeded** | 12–20 |
| DOC-21 | `21_INTEGRATION_AND_CONNECTOR_FRAMEWORK.md` | 4 — Contracts | Not started | 38–48 |
| DOC-22 | `22_SBOM_AND_VULNERABILITY_MATCHING.md` | 4 — Contracts | Not started | 18–24 |
| DOC-24 | `24_TENANCY_AND_ISOLATION_MODEL.md` | 2 — Model | Not started | 28–35 |
| DOC-26 | `26_PLATFORM_THREAT_MODEL.md` | 3 — Structure | Not started | 32–40 |
| DOC-28 | `28_RISK_AND_SCORING_METHODOLOGY.md` | 2 — Model | Not started | 32–40 |

**Total: ≈1,320–1,650 pages.**

**Note on numbering gaps.** DOC-23, DOC-25, and DOC-27 are intentionally unassigned. New documents added during authoring receive the next free number in the block adjacent to their tier rather than being renumbered into sequence. Renumbering is prohibited because it invalidates every external reference. The gaps are a feature, not an oversight.

### 3.2 Page unit definition

One **page** = approximately 500 words of dense technical prose, or one substantial table, or one diagram with its explanatory text. Roughly 50 lines of authored Markdown. This unit exists solely to make effort estimation comparable across documents; it is not a formatting instruction and no document should be padded or truncated to hit a page figure.

### 3.3 Document status vocabulary

Exactly six values. No others are permitted in front matter.

| Status | Meaning | May implementation rely on it? |
|---|---|---|
| `Not started` | No content authored | No |
| `Drafting` | Actively being written; sections may be missing | No |
| `In review` | Content complete; under technical review | Reference only; do not build |
| `Approved` | Reviewed and baselined | **Yes — binding** |
| `Superseded` | Replaced by a named document/version | No — follow the successor |
| `Withdrawn` | Removed from scope; number retired | No |

**Rule:** implementation MUST NOT begin against a document below `Approved` status. Where commercial pressure demands parallel work, the affected module MUST be explicitly listed in DOC-20 as accepted risk, with the specific sections being relied upon named. This makes the risk visible rather than implicit.

---

## 4. Language Policy

### 4.1 English-first, unconditionally

All artifacts in the following categories MUST be authored in English:

- documentation (all 26 documents);
- database identifiers (tables, columns, indexes, constraints, enum values);
- API identifiers (paths, parameters, headers, field names, error codes);
- domain model identifiers (aggregates, entities, value objects, events);
- source code identifiers, comments, and commit messages;
- log messages, metric names, and trace span names;
- default UI strings and the source locale of all translatable content;
- default report and notification templates.

**Rationale.** Three independent reasons, each sufficient on its own. (1) The product targets multiple markets; a schema with non-English identifiers cannot be handed to an international engineering team or an external auditor without a translation layer that will drift. (2) Security tooling, vulnerability intelligence feeds (CVE, CWE, CVSS, EPSS, KEV), and standards references (ASVS, OWASP, IEC 62443) are English-native; mixing languages at the schema boundary creates mapping errors in exactly the data where correctness matters most. (3) Mixed-language identifiers defeat text search and static analysis across the codebase.

### 4.2 Where non-English content is legitimate

| Content type | Policy |
|---|---|
| End-user-visible strings | Externalized and translatable. English is the **source locale**; Vietnamese is the first target locale. See `INT` requirements in DOC-08. |
| Tenant-authored data (work item titles, comments, findings, custom field labels) | Any language, any script. The platform MUST NOT assume Latin script anywhere in storage, indexing, search, sorting, or export. |
| Regulatory citation titles | Original language, with an English gloss on first use. Example: *Nghị định 13/2023/NĐ-CP (Decree 13/2023 on Personal Data Protection)*. |
| Verbal review discussion | Any language. Outcomes MUST be recorded in English. |

### 4.3 Internationalization is a v1 architectural requirement

i18n MUST be architecturally present from the first release even if only English ships. Specifically: all user-facing strings externalized; ICU MessageFormat for pluralization and gender; locale-aware date, time, number, and currency formatting; tenant-level and user-level locale preference; timezone-aware storage (UTC at rest, render in the viewer's zone); RTL-capable layout primitives; pseudo-localization in the test suite.

**Rationale.** Retrofitting i18n touches every UI component, every notification template, every export, and every date comparison. The cost differential between designing for it and adding it later is roughly an order of magnitude. Since the product is intended for multiple markets and a Vietnamese-language user base is certain, deferring this is not a saving.

### 4.4 Terminology alignment across languages

Where a Vietnamese term is in active operational use, DOC-18 MUST record the canonical English domain term, the Vietnamese operational term, and any UI label that differs from both. Example:

| Domain term (canonical) | UI label | Vietnamese operational term |
|---|---|---|
| `MatchRun` | "Rescan" | *Quét lại* |
| `OrgNode` (type: BUSINESS_UNIT) | "Business Unit" | *Đơn vị kinh doanh / P&L* |
| `PentestRequest` | "Pentest Request" | *Yêu cầu kiểm thử xâm nhập* |

**Rule:** a UI label MAY differ from the domain term where the domain term is a technical abstraction users do not share. When they differ, the mapping MUST be recorded in DOC-18. Undocumented divergence between domain term and UI label is a defect.

---

## 5. Normative Language (RFC 2119)

### 5.1 Keywords

Interpreted per RFC 2119 / RFC 8174. When used with normative intent, keywords MUST appear in **uppercase**.

| Keyword | Meaning |
|---|---|
| **MUST** / **REQUIRED** / **SHALL** | Absolute requirement. Non-compliance is a defect that blocks release. |
| **MUST NOT** / **SHALL NOT** | Absolute prohibition. |
| **SHOULD** / **RECOMMENDED** | Strong recommendation. Deviation is permitted only with a recorded ADR stating the justification. |
| **SHOULD NOT** / **NOT RECOMMENDED** | Strong discouragement; same ADR requirement to deviate. |
| **MAY** / **OPTIONAL** | Genuinely discretionary. No justification needed either way. |

### 5.2 Usage rules

- Lowercase "must", "should", "may" carry **no normative weight**. Authors SHOULD avoid them in requirement statements entirely to prevent ambiguity.
- Every occurrence of a normative keyword MUST sit inside a requirement that has an ID. A normative keyword in unlabelled prose is untestable and therefore prohibited.
- "MUST" and "SHOULD" MUST NOT appear in the same requirement statement. Split them into two requirements so each can be independently verified.
- Words with a legal or commercial connotation — "guarantee", "ensure", "prevent", "fully secure", "unhackable" — MUST NOT be used in normative statements. Use precise, measurable language: not *"the system prevents SQL injection"* but *"all database access MUST use parameterized statements; dynamic SQL construction from user input is prohibited (see SEC-SEC-041)"*.

---

## 6. Requirement Identification Scheme

### 6.1 Format

```
<CLASS>-<DOMAIN>-<NNN>[.<SUB>]
```

Examples: `PRD-PTR-003`, `NFR-XMP-004`, `SEC-XMP-005`, `CFG-AUZ-002`, `PRD-WRK-017.2`

Illustrative identifiers throughout this document use the reserved `XMP` domain code (Appendix A). Real identifiers MUST NOT appear in examples: they are indistinguishable from genuine definitions to the register generator, which then records the example in place of the real requirement. This was found by the corpus validator during the first full run.

| Component | Rule |
|---|---|
| `CLASS` | 3–4 letters, from the closed set in §6.2. `RISK` is the sole four-letter code, disambiguating it from the `RSK` domain code |
| `DOMAIN` | 3 letters, from the registry in Appendix A. **Closed set** — new codes require a DOC-00 amendment |
| `NNN` | Zero-padded 3-digit sequence, assigned per `CLASS`+`DOMAIN` pair, monotonically increasing, **never reused** |
| `.SUB` | Optional single level for decomposing a requirement into independently testable parts. Maximum depth: one. |

**Why not a flat global counter?** A flat counter forces every author to coordinate on the next number, which serializes authoring across 26 documents. Per-domain sequences let the SBOM author and the RBAC author work simultaneously without collision. The `CLASS`+`DOMAIN` prefix also makes an ID self-describing: a reader encountering `SEC-PTR-006` in a test name knows immediately it is a security requirement in the pentest-request domain, without a lookup.

**Why maximum sub-depth of one?** Deeper nesting (`PRD-PTR-003.2.1.4`) invariably signals that the parent requirement is actually a feature description, not a requirement. The depth limit forces decomposition into properly scoped requirements.

### 6.2 Class codes (closed set)

| Class | Meaning | Primary home |
|---|---|---|
| `PRD` | Functional product requirement | DOC-01 and module documents |
| `NFR` | Non-functional requirement (measurable) | DOC-01 |
| `SEC` | Security control requirement | DOC-06, DOC-24, DOC-26 |
| `CFG` | Configurability / extensibility requirement | Owning module document |
| `CON` | Constraint (external, legal, technical — not negotiable) | DOC-01 |
| `INT` | Internationalization / accessibility requirement | DOC-08 |
| `OPS` | Operational / observability requirement | DOC-15 |
| `ADR` | Architecture Decision Record | DOC-19 |
| `OQ` | Open Question | DOC-20 |
| `TST` | Test specification | DOC-16 |
| `RISK` | Documented risk (accepted or mitigated) | DOC-20, DOC-26 |

**On the `CFG` class.** This class exists because of ADR-027 (no hardcoded roles or personas — the platform must serve any conglomerate). A configurability requirement is materially different from a functional one: it constrains *what must not be fixed in code*. Giving it its own class makes it possible to query the corpus for "everything that must be tenant-configurable" and verify that nothing on that list was hardcoded — a check that is otherwise impossible to perform systematically. This is the single most likely category of requirement to be quietly violated during implementation, because hardcoding is always the faster path in the moment.

**On the `RISK` class.** A risk is not a requirement, but it must be identifiable and traceable to whatever mitigates it. Without an ID, accepted risks become folklore.

### 6.3 ID lifecycle

| State | Rule |
|---|---|
| **Active** | Normal. Binding at document status `Approved`. |
| **Deprecated** | Still present but scheduled for removal. MUST name the successor ID and the release in which it is removed. |
| **Superseded** | Replaced. MUST name the successor. Original text MUST be retained struck-through, never deleted. |
| **Withdrawn** | Out of scope. ID retired permanently. MUST state the reason. |

**IDs are immutable.** Once an ID appears in an `Approved` document, its number MUST NOT be reassigned, and the *meaning* of the requirement MUST NOT be materially changed under the same ID. Correcting a typo, tightening imprecise wording without changing intent, or improving rationale is permitted. Changing what the system must do requires a **new ID** plus marking the old one `Superseded`.

**Rationale.** An ID that changes meaning is worse than no ID. Code comments, test names, commit messages, and audit evidence all reference IDs; silently altering the referent invalidates all of them without any signal. This rule is what makes the traceability matrix trustworthy over multiple years.

### 6.4 Where requirements live

A requirement has exactly **one owning document**. Other documents reference it by ID; they MUST NOT restate its text.

| Requirement kind | Owning document |
|---|---|
| What the product does, for whom, why | DOC-01 |
| Measurable quality attributes | DOC-01 |
| Domain rules and invariants | DOC-03 |
| Security controls | DOC-06 |
| Authorization semantics | DOC-07 |
| Module-specific functional detail | The module document |
| Tenant-configurability | The module document owning that surface |

**Rule against duplication.** Restating a requirement in a second document creates two copies that will diverge, and there is no mechanism to detect the divergence. Where a downstream document needs to elaborate, it MUST reference the ID and add detail under a **new** ID scoped to that document's concern. Example: `PRD-PTR-003` (≥2 test accounts per role) is owned by DOC-01; `TST-PTR-003` (the test that verifies it) is owned by DOC-16; `SEC-PTR-004` (how those credentials are protected) is owned by DOC-06. Three IDs, one concept, no duplication.

---

## 7. Requirement Anatomy and Mandatory Attributes

### 7.1 Mandatory attributes

Every requirement MUST carry all of the following. A requirement missing any attribute is incomplete and MUST NOT pass review.

| Attribute | Requirement |
|---|---|
| **ID** | Per §6 |
| **Statement** | One normative sentence using an uppercase RFC 2119 keyword. Two sentences maximum. |
| **Rationale** | *Why* this requirement exists. What breaks, or what risk materializes, without it. |
| **Priority** | `MUST_HAVE` (v1 blocking) / `SHOULD_HAVE` (v1 target, descopable) / `COULD_HAVE` (post-v1) |
| **Verification** | How compliance is demonstrated: `AUTOMATED_TEST` / `MANUAL_TEST` / `CODE_REVIEW` / `ARCHITECTURE_REVIEW` / `PENETRATION_TEST` / `DEMONSTRATION` / `DOCUMENT_INSPECTION` |
| **Extensibility** | How this requirement accommodates future change, or an explicit statement that it is intentionally fixed |

Conditionally mandatory:

| Attribute | Required when |
|---|---|
| **Security considerations** | Any requirement touching authentication, authorization, data handling, external input, file handling, or credentials |
| **Configurability** | Any requirement whose behaviour may legitimately differ between tenants |
| **Depends on** | The requirement is unimplementable without another requirement being satisfied |
| **Conflicts with** | Tension with another requirement exists; MUST name the resolving ADR |

### 7.2 On the rationale attribute

The rationale requirement is not documentary ceremony. It has three concrete functions.

**It enables correct descoping.** When schedule pressure arrives — and it will — the team must decide what to cut. A requirement with no stated rationale is cut on the basis of how expensive it looks, not how much it matters. A requirement whose rationale reads *"without this, a single failed scan silently closes every finding for the project, destroying data trust"* survives the conversation that a bare statement would not.

**It enables correct reinterpretation.** Implementation always encounters cases the requirement author did not anticipate. An engineer who knows *why* can extend the intent correctly. An engineer who knows only *what* either implements it literally and wrongly, or asks — and the author may no longer be available.

**It surfaces requirements that have no reason.** Some proportion of any requirement set exists because someone assumed it, not because anyone needed it. Forcing a written rationale exposes these before they cost engineering time.

**Anti-pattern.** A rationale that restates the requirement is not a rationale. *"Because the system must support MFA"* is a restatement. *"Because credential stuffing against a platform holding the group's complete exploitable attack surface is a plausible and high-consequence attack path; password-only authentication is insufficient given the asset value"* is a rationale.

### 7.3 Canonical requirement format

Requirements are authored as tables for density, with an expanded form for complex requirements.

**Compact form** — for straightforward requirements. The compact form omits the extensibility attribute; where it is used, extensibility MUST be stated once per domain in a `Domain Extensibility` subsection, because extensibility is nearly always a property of a domain's model rather than of an individual requirement. Where a specific requirement's extensibility differs from its domain, expanded form MUST be used.

| ID | Statement | Rationale | Pri | Verify |
|---|---|---|---|---|
| `PRD-XMP-001` | Vulnerability matching MUST execute asynchronously; no synchronous matching endpoint SHALL be exposed. | A match run over a large SBOM can exceed HTTP timeout budgets; a synchronous endpoint would fail unpredictably under exactly the conditions where results matter most. | MUST_HAVE | AUTOMATED_TEST |

**Expanded form** — required where the requirement has security implications, configurability, or non-obvious edge cases:

> #### `SEC-XMP-002` — SBOM parser hardening
>
> **Statement.** SBOM documents MUST be parsed with document depth limits, total size limits, component count limits, and external reference resolution disabled. YAML input MUST use a safe loader that does not construct arbitrary types.
>
> **Rationale.** An SBOM is an untrusted document supplied over an API by a client the platform does not control. CycloneDX and SPDX both permit deeply nested structures and external references. Without limits, a malicious or malformed submission achieves denial of service through parser resource exhaustion; with external reference resolution enabled, it achieves server-side request forgery and local file disclosure. Because SBOM push is the **only** ingestion path for SCA data (ADR-023), this parser is the single highest-value entry point in the module and cannot be treated as a routine input handler.
>
> **Priority.** MUST_HAVE
> **Verification.** AUTOMATED_TEST — malformed corpus fixtures MUST be part of the standing test suite (see `TST-XMP-002`)
> **Security considerations.** Parser executes in a worker process, not the API process (`SEC-SBM-003`). Failures MUST be logged with submission ID but MUST NOT echo document content into logs, since the document may contain secrets accidentally embedded by the submitter.
> **Configurability.** Limits are operator-configurable within a hard product-defined ceiling. Tenants MUST NOT be able to raise limits above the ceiling.
> **Depends on.** `SEC-XMP-003` (process isolation)
> **Extensibility.** Limits are declared per SBOM format in a parser registry, so adding a format (SWID, custom) supplies its own limits without modifying the shared parsing path.

---

## 8. Traceability Contract

### 8.1 The chain

Traceability is the property that permits a reviewer to answer, for any requirement: *is this actually built, and how do we know?* The chain has six links.

```
CON / OQ ──► PRD / NFR / SEC / CFG ──► DOC-03 design element
                    │                          │
                    │                          ▼
                    │                   DOC-04 schema object
                    │                          │
                    │                          ▼
                    │                   DOC-05 API operation
                    │                          │
                    ▼                          ▼
             DOC-16 test case  ◄──────  implementation artifact
                    │                    (module / commit / PR)
                    ▼
             Release evidence (DOC-17)
```

### 8.2 Forward and backward obligations

**Forward (completeness).** Every `MUST_HAVE` requirement MUST trace forward to at least one test case in DOC-16. A `MUST_HAVE` requirement with no test is, operationally, not a requirement — nothing detects its absence.

**Backward (justification).** Every schema table, every API operation, and every non-trivial module MUST trace backward to at least one requirement ID. An artifact that traces to nothing is either undocumented scope creep or a requirement someone forgot to write down. Both need resolution before release; the traceability check is what surfaces them.

**Rationale.** Forward traceability alone catches under-delivery. Backward traceability catches the more insidious problem: features that exist without justification, which carry attack surface, maintenance cost, and test burden that no one ever agreed to accept. In a security product, undocumented functionality is a specific liability — it will be found by an auditor or an attacker rather than by the team.

### 8.3 Matrix format

DOC-16 owns the authoritative matrix. Columns are fixed:

| Requirement ID | Owning doc | Design ref | Schema ref | API ref | Test case ID | Verification method | Status |
|---|---|---|---|---|---|---|---|

Cells that do not apply MUST contain `N/A` with a one-line reason, never blank. A blank cell is indistinguishable from an omission; `N/A — no persistent state` is a reviewed judgement.

### 8.4 Reference syntax

Uniform across the corpus:

| Target | Syntax | Example |
|---|---|---|
| Requirement | Backtick ID | `` `PRD-PTR-003` `` |
| Document | `DOC-nn` | DOC-07 |
| Document section | `DOC-nn §x.y` | DOC-03 §4.2 |
| ADR | `ADR-nnn` | ADR-027 |
| Open question | `OQ-nnn` | OQ-014 |
| Glossary term | *Italic on first use per document* | *Asset Graph* |
| Schema object | `snake_case` in backticks | `` `pentest_request` `` |
| API operation | Method + path in backticks | `` `POST /v1/sbom-submissions` `` |
| Domain element | `PascalCase` in backticks | `` `SbomSnapshot` `` |

Cross-document references MUST use IDs, never page numbers, section titles, or phrases like "as described above". Titles get edited; IDs do not.

### 8.5 Traceability gates

| Gate | Condition | Enforcement |
|---|---|---|
| Document approval | Every requirement in the document has all mandatory attributes | Review checklist, §18 |
| Design approval | Every `MUST_HAVE` traces to a design element | Matrix review |
| Implementation start | Owning documents are `Approved`, or exception recorded in DOC-20 | Release management |
| Release candidate | 100% of `MUST_HAVE` requirements have a passing test; 0 untraced schema objects or API operations | Automated where the matrix is machine-readable; otherwise review |

---

## 9. Configurability Conventions

### 9.1 Why this section exists

ADR-027 establishes that the platform must be deployable by **any** conglomerate, not one specific organization. This has a consequence that is easy to state and easy to violate: **organization-specific structure and vocabulary MUST NOT appear in code, schema, or fixed enumerations.**

The personas discussed during requirements gathering — including roles such as "DCEO" — are **illustrative examples of scope-based authorization**, not product entities. There is no `DCEO` role in the product. There is a role *builder*, and one tenant may create a role named `DCEO`, another `Division President`, another `Regional CISO`, each with different permission sets and different scope assignments.

**Rationale.** Hardcoding is always locally cheaper. An engineer implementing a dashboard finds it faster to write `if (user.role === 'BU_MANAGER')` than to resolve a permission through the authorization service. Each such shortcut is invisible in isolation and collectively makes the product unsellable to the second customer. This section exists so that the boundary is documented before the first line of code, and so that violations are reviewable against a written standard rather than a reviewer's memory.

### 9.2 The three-tier configurability model

Every configurable surface MUST be classified into exactly one tier, and the tier MUST be stated in its `CFG` requirement.

| Tier | Definition | Who changes it | Change mechanism |
|---|---|---|---|
| **T1 — Product-fixed** | Invariant across all tenants. Changing it is a product change. | Engineering | Code release |
| **T2 — Operator-configurable** | Varies by deployment (SaaS vs. on-prem, region). | Platform operator | Deployment configuration |
| **T3 — Tenant-configurable** | Varies by customer. Self-service. | Tenant administrator | In-product UI + API |

### 9.3 Mandatory T3 (tenant-configurable) surfaces

The following MUST be T3. Implementing any of these as a fixed enumeration in code or as a non-extensible database enum is a **release-blocking defect**.

| Surface | Why it cannot be fixed |
|---|---|
| **Organization hierarchy depth and node type names** | Conglomerates differ structurally: Group → Division → BU → Sub-BU → Product → Project in one, Holding → Company → Department → System in another. A fixed four-level model fits neither. Requires the `OrgNode` closure-table design (ADR-010). |
| **Roles and their permission sets** | No two enterprises share a role taxonomy. Permission *catalog* is T1 (the set of things one can do is a product concern); *roles* composing those permissions are T3. |
| **Work item types and their workflows** | State machines MUST be data, not code. One tenant's pentest process has a QA review stage; another's does not. |
| **Work item custom fields** | Every enterprise has fields the product did not anticipate. Absence of custom fields is a primary reason teams keep a parallel spreadsheet — which defeats the single-source-of-truth goal entirely. |
| **SLA policies** | Severity-to-deadline mapping, business calendars, holidays, escalation chains. |
| **Risk scoring weights** | Within a product-defined model structure and validated bounds. The *formula shape* is T1; the *weights* are T3. |
| **Severity taxonomy and thresholds** | Some organizations use 4 levels, some 5, some map to internal risk tiers. |
| **Notification rules, channels, templates** | |
| **Business criticality tiers** | Tier names and count. |
| **Approval gates and their conditions** | Whether BU approval is required before a request enters the AppSec backlog, and who approves. |
| **Asset types and their attribute schemas** | Per ADR-009, adding an asset type must not require migration. |
| **Vocabulary / UI label overrides** | A tenant calling business units "P&Ls" must be able to say so without a code change. |

### 9.4 Explicitly T1 (product-fixed)

Equally important to state, so that "configurable" does not become an excuse for an unopinionated product:

- The **permission catalog** — the enumeration of distinct actions the system supports. Tenants compose roles from it; they do not invent permissions.
- **Security controls** — MFA enforcement capability, encryption, audit immutability, session handling. A tenant MUST NOT be able to configure these below the product floor. Operators MAY raise them (T2), never lower them.
- **Audit event schema and immutability.**
- **Finding identity / fingerprint algorithm** — must be globally consistent, versioned as a product artifact.
- **The tenant isolation boundary itself** — not configurable by tenants, by construction.
- **Core domain invariants** — e.g. an `Asset` is owned by exactly one `OrgNode`.
- **The scoring model's structural form** — weights are tenant-tunable; the formula and its factors are versioned product artifacts, because reproducibility and defensibility depend on it (DOC-28).

### 9.5 Authoring rules

- `CFG` requirements MUST state the tier, the configuration surface (UI, API, deployment config), the default value, the validation bounds, and the audit behaviour on change.
- Any requirement that names a specific role, business unit, organizational level, or company-specific term MUST either declare it as an example (`for example`, `such as`) or be rewritten. Reviewers MUST reject requirements containing implicit organization-specific assumptions.
- Configuration changes to T3 surfaces MUST be audit-logged with actor, before value, after value, and timestamp (DOC-14). Configuration is privileged data; changing an SLA policy or a role's permissions is a security-relevant event.
- Every T3 surface MUST ship a **sensible default configuration**. A product that requires 40 configuration decisions before first use will not survive its own onboarding. Defaults are a design deliverable, not an afterthought.

---

## 10. Naming Conventions

### 10.1 The single-name principle

One concept, one name, everywhere. The chain is:

```
Ubiquitous language (DOC-18)
   → Domain model:  PascalCase       SbomSnapshot
   → Database:      snake_case       sbom_snapshot
   → API JSON:      snake_case       sbom_snapshot
   → API path:      kebab-case       /v1/sbom-snapshots
   → Code:          language idiom   SbomSnapshot / sbomSnapshot
   → UI label:      Title Case       "SBOM Snapshot"  (may differ; MUST be recorded in DOC-18)
```

Any deviation from the mechanical transformation between layers is a defect unless recorded in DOC-18 with a reason.

**Rationale.** The dominant cost of inconsistent naming is not aesthetic. It is that engineers cannot tell whether `assessment_finding` and `finding` are the same thing, so they write defensive code for both, and eventually the schema contains both.

### 10.2 Database

| Object | Convention | Example |
|---|---|---|
| Table | `snake_case`, **singular** | `pentest_request` |
| Join table | Both entities, alphabetical | `finding_asset` |
| Primary key | `id`, UUIDv7 | `id` |
| Foreign key | `<referenced_table>_id` | `project_id` |
| Boolean | `is_` / `has_` / `can_` prefix | `is_golive_blocking` |
| Timestamp | `_at` suffix, UTC, `timestamptz` | `submitted_at` |
| Date only | `_date` suffix | `golive_date` |
| Duration | `_seconds` / `_days` — unit in the name | `duration_seconds` |
| Count | `_count` suffix | `api_count` |
| Enum column | Singular noun | `exposure_level` |
| Enum value | `SCREAMING_SNAKE_CASE` | `INTERNET_PUBLIC` |
| Index | `ix_<table>__<cols>` | `ix_finding__project_id_severity` |
| Unique index | `ux_<table>__<cols>` | `ux_sbom_snapshot__content_hash` |
| Foreign key constraint | `fk_<table>__<ref_table>` | `fk_finding__project` |
| Check constraint | `ck_<table>__<rule>` | `ck_pentest_request__dates_ordered` |
| Partition | `<table>_p<period>` | `audit_event_p2026_08` |

**Singular table names.** Chosen for consistency with the domain model — a row *is* one `PentestRequest`, and `pentest_request.id` reads correctly in every join. Mixed singular/plural is the actual failure mode to avoid; this convention exists to make the choice uniform, and the choice is now closed.

**Prohibited:** reserved SQL words as identifiers; abbreviations not in Appendix A; `data`, `info`, `value`, `temp`, `misc`, `flag`, `type1`; `tbl_` / `t_` prefixes; numeric suffixes indicating iteration (`finding_v2`).

**Every timestamp is `timestamptz` and stored in UTC.** Rationale: the platform serves multiple regions, and one tenant's business calendar and SLA computation must not be corrupted by another's server timezone. Local rendering is a presentation concern.

### 10.3 API

| Element | Convention | Example |
|---|---|---|
| Path segment | `kebab-case`, **plural** collections | `/v1/pentest-requests` |
| Path parameter | `{snake_case}` | `/v1/pentest-requests/{request_id}` |
| Query parameter | `snake_case` | `?business_unit_id=…&sla_status=BREACHED` |
| JSON field | `snake_case` | `"golive_date"` |
| Enum value in JSON | `SCREAMING_SNAKE_CASE` | `"INTERNET_PUBLIC"` |
| Custom header | `X-` prefix avoided; use registered or `Anthropic`-free vendor form | `Idempotency-Key`, `Signature-Input` |
| Error code | `SCREAMING_SNAKE_CASE`, stable | `SCOPE_VIOLATION` |
| Sub-resource action | Noun where possible; verb only for true actions | `POST /v1/scan-batches/{id}/cancel` |

**Plural collections, singular tables.** These are deliberately different because they describe different things: a URL path names a *collection of resources*; a table name names *the type of one row*. Both are internally consistent, which is what matters. This is recorded here specifically to pre-empt the review comment.

**Versioning.** URL-path major version (`/v1/`). Additive changes are non-breaking and ship within a version. Breaking changes require a new major version with a documented deprecation window (DOC-05). Rationale: path versioning is unambiguous in logs, gateways, and client code, and this is a platform whose API is consumed by CI pipelines that nobody will proactively update.

### 10.4 Domain model

| Element | Convention | Example |
|---|---|---|
| Aggregate root / Entity | `PascalCase`, singular noun | `PentestRequest` |
| Value object | `PascalCase`, descriptive | `Cvss4Score`, `FindingFingerprint` |
| Domain event | `<Aggregate><PastTenseVerb>` | `PentestRequestSubmitted` |
| Command | `<ImperativeVerb><Aggregate>` | `SubmitPentestRequest` |
| Repository | `<Aggregate>Repository` | `FindingRepository` |
| Domain service | `<Capability>Service` | `RiskScoringService` |
| Policy | `<Subject>Policy` | `AutoClosePolicy` |
| Bounded context | `PascalCase` with space in prose | Scan Orchestration |

**Domain events MUST be past tense.** An event records something that has already happened and is therefore immutable. `FindingResolved`, never `ResolveFinding` (that is a command) or `FindingResolving` (that is a state). Rationale: the tense distinction is the only thing that keeps event-sourced and event-driven code readable at scale, and it prevents the common error of treating an event as a request.

### 10.5 UI

| Element | Convention |
|---|---|
| Navigation, page title, section heading | Title Case |
| Button label | Sentence case, imperative verb — "Assign engineer", not "Assignment" |
| Status badge | Title Case, human phrasing — `AWAITING_REQUESTER` renders as "Awaiting requester" |
| Field label | Sentence case |
| Empty state | Explains what belongs here and the next action; never only "No data" |
| Error message | What happened, why, what to do. Never a raw code or stack trace. |

**Status labels MUST be human-readable, never the raw enum.** Rationale: raw enum values leaking into the UI is the most common signal of a product built without a presentation layer, and it makes the interface unintelligible to non-engineers — who include most of the executive audience this product is built to serve.

### 10.6 Other identifiers

| Element | Convention | Example |
|---|---|---|
| Code module | `kebab-case` directory matching bounded context | `scan-orchestration/` |
| Metric | `snake_case`, `<domain>_<subject>_<unit>` | `sbm_match_run_duration_seconds` |
| Trace span | `<context>.<operation>` | `sbm.match_run.execute` |
| Feature flag | `SCREAMING_SNAKE_CASE`, `<DOMAIN>_<FEATURE>` | `SBM_REGISTRY_SCANNING` |
| Permission | `<domain>.<resource>.<action>` | `ptr.request.assign` |
| Human-facing entity code | `<PREFIX>-<SCOPE>-<YYYY>-<SEQ>` | `PT-RETAIL-2026-0142` |

**Human-facing codes** (such as pentest request codes) MUST be short, immutable, pronounceable over a phone call, and MUST NOT encode information that can change. Rationale: these codes are quoted in conversation and referenced in external correspondence; encoding a mutable attribute such as severity or assignee guarantees that the code eventually contradicts reality.

---

## 11. Diagram Conventions

### 11.1 Format

All diagrams MUST be authored as **Mermaid** inside the Markdown source. Rationale: diagrams stored as images become stale because updating them requires a tool the next author does not have. Text-based diagrams are diffable in review, editable by anyone, and cannot silently drift out of version control. Where Mermaid is genuinely insufficient (detailed UI wireframes, complex physical topology), an image MAY be used, but its source file MUST be committed alongside it.

Every diagram MUST have: a caption stating what it shows, a legend if any notation is not self-evident, and a stated level of abstraction.

### 11.2 Architecture diagrams — C4

DOC-02 MUST use the C4 model, levels 1–3. Level 4 (code) is prohibited in documentation: it duplicates the code, and the duplicate will be wrong within one sprint.

| Level | Shows | Audience |
|---|---|---|
| L1 Context | Platform, users, external systems | Everyone |
| L2 Container | Deployable/runtime units and their protocols | Engineering, ops |
| L3 Component | Modules within a container, mapped to bounded contexts | Implementing engineers |

### 11.3 State machines

Every state machine MUST be presented as **both** a Mermaid `stateDiagram-v2` *and* a full transition table. The diagram conveys shape; the table conveys completeness. Neither alone is sufficient — a diagram cannot legibly carry guards, actors, and side effects, and a table cannot show structure.

Transition tables MUST have these columns:

| From | Event | To | Guard | Actor | Side effects | Notes / edge cases |
|---|---|---|---|---|---|---|

Every state machine MUST additionally document: the initial state; all terminal states; states in which any SLA clock is paused; and behaviour on concurrent conflicting transitions.

**Rule:** a transition table with no `Guard` entries anywhere is almost certainly incomplete and MUST be challenged in review.

### 11.4 Data models

ERDs use Mermaid `erDiagram` with explicit cardinality and optionality on both ends of every relationship. Rationale: `1..*` versus `0..*` is the difference between a required and an optional foreign key, which is the difference between a schema that permits orphans and one that does not. Ambiguous cardinality in a diagram becomes a nullable column by default, and nullable-by-accident columns are a permanent source of defects.

### 11.5 Notation

| Concept | Notation |
|---|---|
| Trust boundary | Labelled `subgraph` with the boundary named |
| Synchronous call | Solid arrow `-->` |
| Asynchronous / event | Dashed arrow `-.->` |
| Data flow direction | Arrowhead at the recipient |
| External system | Node label suffixed `[external]` |
| Deferred / future capability | Node label suffixed `[deferred]` |

Colour MUST NOT be the sole carrier of meaning, in any diagram, anywhere in the corpus. Every distinction encoded by colour MUST also be encoded by shape, label, or line style. Rationale: `INT` accessibility requirements (WCAG 2.2 AA) apply to documentation as well as product, and the corpus will be read in printed and monochrome form during audits and procurement review.

---

## 12. Document Structure Template

### 12.1 Mandatory sections

Every substantive document (DOC-01 through DOC-28, excluding DOC-18/19/20 which have their own structures) MUST contain these sections in this order:

1. **Front matter** — per Appendix E
2. **Table of contents**
3. **Purpose and Scope** — including an explicit *out of scope* list
4. **Prerequisites** — documents assumed read
5. **Local Conventions** — extensions to DOC-00, or "None"
6. *(body sections — document-specific)*
7. **Requirements Summary** — table of every requirement ID introduced, with statement and priority
8. **Extensibility Considerations** — how the design accommodates anticipated change
9. **Security Considerations** — consolidated, cross-referenced to DOC-06 and DOC-26
10. **Open Questions** — IDs referencing DOC-20, not restated content
11. **Decisions Referenced** — ADR IDs relied upon
12. **Change History**

### 12.2 On the mandatory Extensibility section

Every module document MUST state how it accommodates change. This is required rather than encouraged because of a specific and recurring failure: designs are optimized for the currently known requirement set and become obstacles the moment the second customer arrives. Given ADR-027 — the platform must serve any conglomerate — extensibility is not speculative future-proofing but a present functional requirement.

The section MUST cover:

- **Anticipated extensions** — named, with the extension mechanism identified (plugin registry, type registry, configuration, event subscription).
- **Reserved extension points** — enum values, interfaces, or schema fields deliberately left open. Example: `ScanTarget.target_type` reserves `CONTAINER_IMAGE` and `GIT_REPO` although v1 activates only `SBOM_FILE` (ADR-026). Reserved values MUST be rejected at the application layer in v1, not merely absent — so that enabling them later is additive code, not a migration.
- **Deliberate rigidity** — what is intentionally *not* extensible, and why. This is as important as the flexibility. A design that claims everything is extensible has no invariants, and a domain model without invariants is a data dump.
- **Known extension costs** — changes that will be expensive, so that a future team can weigh them honestly rather than discover them.

### 12.3 On the mandatory Security Considerations section

Required in every document, including those that appear non-security-relevant. Rationale: this platform stores a prioritized, evidence-bearing map of the exploitable attack surface of an entire enterprise group, plus working exploit payloads in pentest evidence and live credentials for test accounts. It is a higher-value target than most systems it protects. Treating security as the concern of one document reproduces the exact failure the product exists to detect in other people's software.

Documents with no *new* security implications MUST state so explicitly and name the controls they inherit. "Not applicable" without justification MUST be rejected in review.

---

## 13. Architecture Decision Record Conventions

### 13.1 Format

DOC-19 holds all ADRs. Each MUST contain:

| Field | Content |
|---|---|
| **ID** | `ADR-nnn`, sequential, never reused |
| **Title** | Imperative or declarative statement of the decision |
| **Status** | `Proposed` / `Accepted` / `Rejected` / `Superseded by ADR-nnn` / `Deprecated` |
| **Date** | Decision date |
| **Deciders** | Roles, not only names |
| **Context** | The forces at play. What made a decision necessary. |
| **Options considered** | At least two, each with genuine pros and cons |
| **Decision** | What was chosen |
| **Consequences** | Positive, negative, and neutral. **Negative consequences are mandatory.** |
| **Compliance** | How adherence is verified |
| **Revisit trigger** | The specific condition under which this should be reconsidered |

### 13.2 On mandatory negative consequences

An ADR listing only benefits is marketing, not engineering, and it is worse than no ADR: it conceals the tradeoff that a future team most needs to understand. Every architectural decision costs something. If the author cannot name the cost, the decision has not been analysed.

Example of the standard expected: ADR-003 (modular monolith over microservices) must state plainly that independent scaling of a single module is unavailable without extraction work, that a fault in one module can affect process-level availability, and that discipline in enforcing module boundaries must be tooling-enforced because it will otherwise erode. These are real costs and were accepted knowingly.

### 13.3 On mandatory revisit triggers

Every ADR MUST name the condition that should cause reconsideration — not a date, a *condition*. "Revisit in 12 months" produces a review that concludes nothing. "Revisit when any single module's resource profile requires independent scaling, or when team count exceeds four independent squads" produces a review with a decidable question.

### 13.4 Supersession

An ADR is never edited to reflect a changed decision. A **new** ADR is written; the old is marked `Superseded by ADR-nnn` and retained in full. Rationale: the corpus must be able to answer "why did we do it the old way, and what changed?" — a question that arises constantly and is unanswerable if history is overwritten. Twenty-nine decisions from the requirements-analysis phase are indexed in Appendix B.

---

## 14. Open Question Conventions

### 14.1 Format

DOC-20 holds all open questions. Each MUST contain:

| Field | Content |
|---|---|
| **ID** | `OQ-nnn` |
| **Question** | Precise, answerable. Not a topic. |
| **Why it matters** | What is blocked or at risk while unresolved |
| **Blocks** | Document IDs and requirement IDs that cannot be finalized |
| **Owner** | The person who can answer it. Never "TBD". |
| **Required by** | Date, derived from the writing schedule |
| **Working assumption** | What the corpus proceeds on in the interim |
| **Impact if assumption is wrong** | Estimated rework |
| **Status** | `Open` / `Answered` / `Deferred` / `Obsolete` |

### 14.2 The working assumption rule

Every open question MUST have a documented working assumption. Authoring MUST NOT block on an unanswered question.

**Rationale.** Blocking produces no documentation and no clarity. Proceeding on an *undocumented* assumption produces documentation that appears authoritative while resting on guesses — the worst of the three outcomes, because the guess becomes invisible. Proceeding on a *documented* assumption produces usable documentation whose weak points are labelled, and makes the cost of a wrong assumption estimable in advance.

Working assumptions MUST be visibly marked at the point of use in body text:

> ⚠️ **Working assumption (OQ-018):** SLA targets in this section are placeholders pending confirmation of existing organizational SLAs. Values MUST be reviewed before release. Structure is stable; only the numbers are provisional.

### 14.3 Escalation

An open question past its `Required by` date with status `Open` MUST be escalated to the corpus owner and recorded in the change history. A blocking question that is silently late is how documentation projects fail without anyone deciding to fail.

---

## 15. Security Annotation Conventions

### 15.1 API operations

Every operation in DOC-05 MUST carry:

| Annotation | Content |
|---|---|
| **Authentication** | Accepted mechanisms (per ADR-004) |
| **Authorization predicate** | The exact check, including the object-level scope condition |
| **Scope re-validation** | How server-side scope is verified independently of client-supplied identifiers |
| **Rate limit class** | Named class from DOC-06 |
| **Replay protection** | Mechanism, or `N/A` with reason |
| **Input validation** | Schema, size, and depth limits |
| **Audit event emitted** | Event type from DOC-14 |
| **Data classification** | Highest classification touched |
| **Abuse case** | The most plausible misuse of this specific operation |

### 15.2 The scope re-validation annotation

This field is mandatory and MUST NOT be omitted for any operation accepting a resource identifier. It exists because the platform's highest-likelihood vulnerability class is broken object-level authorization: a legitimate user substituting an identifier belonging to another business unit or tenant.

A server-side-filtered dropdown is **not** an authorization control. It is a usability feature. Any operation whose annotation reads "the client only sends valid IDs because the picker filters them" MUST be rejected in review. Rationale: this is the exact defect class the product is built to find in customers' applications. Shipping it in the platform itself would be indefensible, and it is the finding an evaluating customer's security team is most likely to look for first.

### 15.3 Data classification

Four levels, applied to every field group in DOC-04 and every response schema in DOC-05:

| Level | Definition | Examples |
|---|---|---|
| `PUBLIC` | No harm if disclosed | Product documentation |
| `INTERNAL` | Business-sensitive within a tenant | Asset inventory, org structure |
| `CONFIDENTIAL` | Harmful if disclosed outside authorized scope | Findings, risk scores, pentest reports |
| `RESTRICTED` | Severe harm; strict need-to-know | Test account credentials, exploit evidence, secret-scanning results, per-person performance data |

`RESTRICTED` data MUST be: encrypted with envelope encryption using per-tenant keys; masked by default in all interfaces; revealed only through an explicit, audit-logged action; excluded from all export formats without exception; excluded from logs, error messages, traces, and AI prompt context.

**Note on per-person performance data.** Individual utilization and productivity metrics are classified `RESTRICTED`, not `CONFIDENTIAL`. This is deliberate. Such data is personal data under applicable data-protection regimes, its exposure to the wrong audience creates distorted management incentives, and it is the category most likely to be casually exposed on a dashboard because it appears operational rather than sensitive.

---

## 16. Non-Functional Requirement Conventions

### 16.1 NFRs must be measurable

An NFR that cannot be measured cannot be met, and cannot be shown to have been missed. Every `NFR` requirement MUST specify: the metric, the target value, the percentile where applicable, the load conditions, the measurement method, and the data scale at which it holds.

**Prohibited:** "the system must be fast", "the UI must be responsive", "the platform must be scalable", "reports must generate quickly".

**Required form:**

> `NFR-XMP-003` — Dashboard query latency
> The AppSec operations dashboard MUST render its initial viewport within **1.5 s at p95** and **3.0 s at p99**, measured server-side from request receipt to last byte, under a tenant dataset of **50,000 open findings, 5,000 assets, 2,000 work items**, with **50 concurrent dashboard sessions**, on the reference deployment profile defined in DOC-15 §4.
>
> **Rationale.** Dashboards consulted daily are abandoned above roughly 2 s perceived latency; abandonment defeats the product's purpose regardless of correctness. The stated dataset reflects the upper bound of a large business unit and is the scale at which naive aggregation queries degrade — which is why the target is bound to a data volume rather than stated absolutely.
> **Verification.** AUTOMATED_TEST — load suite, `TST-XMP-003`, gating release.

### 16.2 Mandatory NFR categories

DOC-01 MUST specify measurable targets in every one of these categories. A missing category is a gap, not a simplification.

Performance and latency; throughput and capacity; data volume ceilings; concurrency; availability and error budget; durability (RPO/RTO); scalability dimensions and limits; security response time (time from vulnerability-intelligence update to tenant visibility); observability coverage; accessibility conformance; browser and device support; localization readiness; upgrade and downtime characteristics.

### 16.3 Scale targets require a stated basis

Every scale figure MUST state whether it derives from a measured baseline, a customer-supplied figure, or an assumption. Where it is an assumption, it MUST link to an `OQ`. Rationale: an unattributed scale figure gets treated as validated and is designed against; when it turns out to be wrong by an order of magnitude, nobody can reconstruct where it came from or what else depended on it.

---

## 17. Corpus Versioning and Change Control

### 17.1 Document versioning

Semantic versioning per document:

| Change | Increment | Requires |
|---|---|---|
| Typo, formatting, clarification with no meaning change | Patch (`1.0.1`) | Author judgement |
| New requirement, new section, expanded detail | Minor (`1.1.0`) | Technical review |
| Requirement removed, meaning changed, structure reorganized | Major (`2.0.0`) | Approval + impact analysis + affected-document review |

### 17.2 Corpus baselines

The corpus as a whole is baselined at release milestones (`Baseline 1.0` = approved v1 scope). A baseline is immutable and archived. Rationale: an auditor, a customer's security team, or an incident investigation needs to know what the documentation said at the time a decision was made — not what it says now.

### 17.3 Change impact analysis

Any major version change MUST include an impact analysis naming: affected requirement IDs, affected downstream documents, affected implementation modules, affected test cases, and whether existing tenant data or configuration requires migration.

**Rule:** a change to an `Approved` document that invalidates an implemented requirement MUST NOT be made silently. It requires a new requirement ID, supersession of the old, and an entry in DOC-19 if the change is architectural.

### 17.4 Change history

Every document MUST end with:

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|

Change descriptions MUST be substantive. "Updated section 4" is not a change record; "Added `SEC-SBM-004` parser hardening requirement following threat-model review of the SBOM ingestion path" is.

---

## 18. Review Gates and Definition of Done

### 18.1 Definition of Ready (before authoring)

A document may begin authoring when: its prerequisite documents are `Approved` or their reliance is recorded in DOC-20; blocking open questions have documented working assumptions; the owner is assigned; and the section outline is agreed.

### 18.2 Definition of Done (before `Approved`)

A document is complete when **all** of the following hold. This checklist is the review instrument, not a guideline.

**Structure**
- [ ] All mandatory sections present (§12.1)
- [ ] Front matter complete and valid (Appendix E)
- [ ] Table of contents accurate
- [ ] Change history present

**Requirements**
- [ ] Every requirement has an ID conforming to §6
- [ ] Every requirement has all mandatory attributes (§7.1)
- [ ] Every rationale explains *why*, not restating *what*
- [ ] Every normative keyword is uppercase and inside an identified requirement
- [ ] No requirement duplicates one owned by another document
- [ ] No requirement contains an unmarked organization-specific assumption (§9.5)

**Traceability**
- [ ] Every `MUST_HAVE` requirement has a stated verification method
- [ ] All cross-references use IDs, resolve correctly, and point to existing targets
- [ ] Every new term is defined in DOC-18
- [ ] Every decision relied upon is recorded in DOC-19

**Technical content**
- [ ] Every module has an Extensibility section meeting §12.2
- [ ] Every module has a Security Considerations section meeting §12.3
- [ ] Every API operation has all security annotations (§15.1), including scope re-validation
- [ ] Every database table has an indexing strategy naming the query each index serves
- [ ] Every state machine has a complete transition table with guards, actors, side effects, and edge cases
- [ ] Every dashboard specifies KPIs, filters, drill-down, permissions, and export
- [ ] Every NFR is measurable per §16.1
- [ ] Every configurable surface declares its tier (§9.2)

**Quality**
- [ ] No `TODO`, `TBD`, or `???` outside an explicitly marked working assumption
- [ ] No prohibited pattern from §19
- [ ] Diagrams have captions, legends, and stated abstraction level
- [ ] Colour is not the sole carrier of meaning in any diagram

### 18.3 Review roles

| Review | Reviewer role | Focus |
|---|---|---|
| Technical | Principal Engineer, not the author | Correctness, completeness, feasibility |
| Security | Principal Security Architect | Control coverage, threat treatment, annotation completeness |
| Domain | Product Manager | Requirement accuracy against real need |
| Editorial | Any corpus author | Convention compliance, cross-reference integrity, glossary consistency |

Security review is mandatory for DOC-03, 04, 05, 06, 07, 10, 11, 14, 21, 22, 24, 26. **The author MUST NOT be the sole reviewer of their own document under any circumstance.**

---

## 19. Quality Bar and Prohibited Patterns

### 19.1 The standard

Every document is written to be reviewed by a Principal Engineer who has not participated in its creation, is not inclined to fill gaps charitably, and will ask why. Practically: state the decision, state the alternative, state why the alternative was rejected. Give numbers where numbers exist and label estimates as estimates. Name the failure mode. Never assert without reason.

### 19.2 Prohibited patterns

These MUST be rejected in review.

| Pattern | Why prohibited |
|---|---|
| Requirement without rationale | Cannot be correctly descoped, extended, or defended |
| Unmeasurable NFR | Cannot be met or shown to be missed |
| "The system will handle X appropriately" | Defers the actual design decision while appearing to make it |
| Requirement duplicated across documents | Copies diverge; nothing detects the divergence |
| Reference by section title or "as described above" | Titles change; the reference silently rots |
| Colour-only encoding in diagrams | Fails accessibility and monochrome review |
| Unmarked organization-specific assumption | Violates ADR-027; makes the product unsellable to the second customer |
| Enumeration in code for a T3-configurable surface | Same |
| "AI will determine / decide / handle" | Violates ADR-005; AI output is advisory and never authoritative |
| Security control described only as an intention | Untestable; provides assurance without substance |
| API operation lacking a scope re-validation annotation | Direct path to broken object-level authorization |
| State machine without guards or edge cases | Guaranteed to be incomplete |
| Table without an indexing strategy | Performance failure discovered in production |
| "TBD" without an `OQ` reference | Invisible gap |
| Absolute security claim ("prevents", "guarantees") | Indefensible and legally hazardous |
| Content abbreviated to save space | Directly contradicts the purpose of this corpus |

### 19.3 On completeness

No document may be shortened for economy of effort. The corpus exists so that implementation proceeds without guessing; an abbreviated document transfers the omitted work to an engineer with less context, who will make a decision nobody reviews.

Where a document grows beyond what a single authoring session can carry, it MUST be split across sessions at a clean section boundary, with the stopping point explicitly recorded, and resumed. It MUST NOT be compressed. Length is a consequence of the domain, not a stylistic choice.

---

## 20. Repository Layout and Tooling

### 20.1 Layout

```
docs/
├── 00_CONVENTIONS_AND_TRACEABILITY.md
├── 01_PRODUCT_REQUIREMENT.md
├── …
├── 28_RISK_AND_SCORING_METHODOLOGY.md
├── _assets/
│   ├── diagrams/          # image sources where Mermaid is insufficient
│   └── schemas/           # OpenAPI, JSON Schema, example payloads
├── _traceability/
│   ├── requirements.csv   # machine-readable requirement register
│   └── matrix.csv         # traceability matrix
└── _baselines/
    └── baseline-1.0/      # immutable archived snapshot
```

### 20.2 Machine-readable requirement register

`_traceability/requirements.csv` MUST be maintained as the authoritative machine-readable index. Columns: `id, class, domain, seq, owning_doc, statement, priority, verification, status, supersedes, superseded_by`.

**Rationale.** A traceability matrix maintained only in prose cannot be validated automatically, and a matrix that is not validated automatically is wrong within weeks at this corpus size. The register enables three checks in CI: every requirement ID referenced anywhere in the corpus exists; every `MUST_HAVE` requirement appears in the matrix with a test case; no ID is duplicated or reused.

### 20.3 Automated checks

The following SHOULD be enforced in CI over the documentation repository:

- ID format validation against §6.1
- Duplicate and reused ID detection
- Dangling reference detection (`DOC-nn`, `ADR-nnn`, `OQ-nnn`, requirement IDs)
- Glossary coverage — terms in `PascalCase` or backticked domain identifiers must exist in DOC-18
- Front-matter schema validation
- Prohibited-string detection (`TBD`, `TODO`, `???` outside marked assumptions)
- Detection of real requirement identifiers used in illustrative examples, which pollute the register (use the reserved `XMP` domain code instead)
- Mermaid syntax validation
- Markdown link integrity

**Rationale.** Convention compliance verified only by human review degrades predictably, because reviewers optimize for substance over form — correctly. Automating the mechanical checks preserves reviewer attention for the content that actually requires judgement.

---

## 21. Changing These Conventions

Conventions are stable but not immutable.

| Change type | Process |
|---|---|
| Clarification, example, typo | Patch version; author judgement |
| New domain code, new class code, new convention | Minor version; Chief Architect approval; MUST be backward-compatible with existing IDs |
| Change to ID scheme, traceability contract, or naming rules | Major version; **requires an ADR**; MUST include a migration plan for existing IDs and an impact analysis across the corpus |

**Retroactive convention changes are prohibited.** A convention change applies to content authored after it takes effect. Existing `Approved` content is not rewritten to comply, because rewriting is where meaning silently changes. Where consistency genuinely requires migration, it is executed as an explicit, reviewed, single-purpose change with its own impact analysis — never as an incidental cleanup.

---

## Appendix A — Domain Code Registry

Closed set. Adding a code requires a minor version of this document and Chief Architect approval.

| Code | Domain | Primary owning document |
|---|---|---|
| `PLT` | Platform-wide / cross-cutting | DOC-01, DOC-02 |
| `TEN` | Tenancy and isolation | DOC-24 |
| `ORG` | Organization hierarchy (BU, product, project) | DOC-03 |
| `IAM` | Identity and authentication | DOC-06 |
| `AUZ` | Authorization, RBAC, scope | DOC-07 |
| `AST` | Asset inventory and asset graph | DOC-03 |
| `ASM` | Assessments (architecture review, threat modeling) | DOC-09 |
| `PTR` | Pentest request and engagement | DOC-09 |
| `VUL` | Findings and vulnerability management | DOC-03 |
| `RSK` | Risk scoring, SLA, prioritization | DOC-28 |
| `EXC` | Risk exceptions and acceptance | DOC-09 |
| `SBM` | SBOM storage and vulnerability matching | DOC-22 |
| `ING` | Ingestion, import, export | DOC-11 |
| `CON` | Connectors and external integration | DOC-21 |
| `WRK` | Work management and collaboration | DOC-09 |
| `CAP` | Capacity, workload, resource management | DOC-12 |
| `DSH` | Dashboards and reporting | DOC-12 |
| `AIC` | AI capabilities | DOC-10 |
| `NTF` | Notification | DOC-13 |
| `AUD` | Audit logging | DOC-14 |
| `SEC` | Security controls | DOC-06 |
| `API` | API surface and contract | DOC-05 |
| `DAT` | Data management, retention, migration | DOC-04 |
| `UIX` | User interface and experience | DOC-08 |
| `INT` | Internationalization and accessibility | DOC-08 |
| `DEP` | Deployment and operations | DOC-15 |
| `TST` | Testing | DOC-16 |
| `LIC` | Licensing, entitlement, metering | DOC-01 |
| `KBS` | Knowledge base | DOC-01 |
| `XMP` | **Reserved for illustrative examples only.** No real requirement may use it. Any identifier with this domain code MUST be excluded from the requirement register | — |

---

## Appendix B — Consolidated ADR Index

Twenty-nine decisions from the requirements-analysis phase. Full text in DOC-19. Status `Accepted` unless noted.

| ID | Decision |
|---|---|
| ADR-001 | Replace the linear business hierarchy with two orthogonal structures: an organization tree for scope and permission, and an asset graph for technical reality, joined by an ownership edge |
| ADR-002 | Tenant is a hard isolation boundary from v1, enforced at the persistence layer with per-tenant encryption keys |
| ADR-003 | Modular monolith with compile-time-enforced module boundaries and documented extraction seams, not microservices at v1 |
| ADR-004 | OIDC/OAuth2 for humans; OAuth2 client-credentials with sender-constrained tokens for services; HMAC with replay protection only for legacy CI. Bearer API keys are not offered |
| ADR-005 | AI writes only to a separate AI Suggestion Ledger; promotion into the system of record requires an audited human action |
| ADR-006 | Single coherent design language: Linear-style interaction density and keyboard-first model with Azure Portal-grade information architecture for deep hierarchies |
| ADR-007 | OWASP ASVS Level 2 baseline with enumerated Level 3 uplift for authentication, session management, cryptography, secrets management, and audit logging |
| ADR-008 | Documentation corpus of 26 documents, with numbering permanent and non-reusable |
| ADR-009 | One `Asset` aggregate with a type registry, replacing five parallel inventories |
| ADR-010 | One `OrgNode` hierarchy with a closure table and configurable node types, replacing three fixed levels |
| ADR-011 | One normalization and deduplication pipeline shared by file import and native matching |
| ADR-012 | Three canonical dashboard compositions over one metrics read-model, plus a distinct operations composition; scope injected from the caller's authorization context |
| ADR-013 | The SBOM module stores and matches; it does not execute scanners over source code |
| ADR-014 | Vulnerability re-evaluation is performed by re-matching stored SBOMs against updated intelligence, not by re-scanning |
| ADR-015 | `INTERACTIVE` and `BATCH` queue classes are isolated; on-demand work is never blocked behind a portfolio sweep |
| ADR-016 | Trivy is a pluggable matcher behind a `ScanEngine` abstraction, not a hardcoded dependency |
| ADR-017 | Project build tooling and dependency installation MUST NOT be executed by the platform |
| ADR-018 | Automatic closure of findings requires confirmed successful coverage; failed or stale runs MUST NOT close findings |
| ADR-019 | Test-account credentials are stored in the secrets vault, masked by default, and excluded from all exports |
| ADR-020 | `WorkItem` abstracts over all AppSec work types, not only pentest requests |
| ADR-021 | Effort is derived from state duration; mandatory timesheets are rejected |
| ADR-022 | Per-person utilization data is restricted to manager roles, classified `RESTRICTED`, and access-audited |
| ADR-023 | SBOM push API is the only automated ingestion path in v1; manual upload is the fallback |
| ADR-024 | The platform MUST NOT fetch, clone, or persist source code; no Git credentials are stored |
| ADR-025 | `git_repo_url` is a reference label for human use, not an integration point |
| ADR-026 | Container registry scanning is deferred, not descoped; extension points are reserved and rejected at the application layer in v1 |
| ADR-027 | No roles, personas, organizational levels, or vocabulary are hardcoded. The permission catalog is product-fixed; roles, hierarchy depth, node type names, workflows, custom fields, and vocabulary are tenant-configurable |
| ADR-028 | The platform is the sole system of record for AppSec work management, replacing external issue trackers. This requires configurable workflows as data, tenant custom fields, full-text search, automation rules, email-in, work item templates, and migration import from the incumbent tracker |
| ADR-029 | Approval gates before work enters the AppSec backlog are tenant-configurable, defaulting to disabled |

---

## Appendix C — Legacy Requirement ID Alias Table

Requirement IDs were introduced during requirements analysis before this document existed. Two domain codes were provisional and are hereby superseded. The originals MUST NOT be used in new content; this table exists so that references in earlier analysis remain resolvable.

| Provisional ID pattern | Canonical ID pattern | Reason |
|---|---|---|
| `PRD-SCAN-001` … `PRD-SCAN-013` | `PRD-SBM-001` … `PRD-SBM-013` | `SCAN` implied scanner execution, which ADR-013/ADR-024 exclude. `SBM` reflects the actual domain: SBOM storage and matching |
| `PRD-RES-001` … `PRD-RES-010` | `PRD-CAP-001` … `PRD-CAP-010` | `RES` was ambiguous (resource / response / result). `CAP` names the domain: capacity and workload |

Sequence numbers are preserved in the mapping. Requirements whose substance changed as a result of ADR-023/024/026 are re-issued under new sequence numbers in DOC-22 and marked as superseding the original, per §6.3.

---

## Appendix D — Reserved Words and Forbidden Terms

### D.1 Terms with exactly one meaning in this corpus

These MUST NOT be used in any other sense. Full definitions in DOC-18.

`Asset` · `Assessment` · `Finding` · `Scope` · `Project` · `Product` · `Business Unit` · `OrgNode` · `Tenant` · `WorkItem` · `Request` · `Scan` · `MatchRun` · `Snapshot` · `Component` · `Severity` · `Risk` · `Score` · `Exception` · `Engagement`

Notably: **`Project`** means an `OrgNode` of type project — a unit of organizational scope. It never means an engineering effort, a repository, or a delivery initiative. **`Scan`** never means the execution of a scanner over source code by this platform (ADR-024); it refers to an ingested scan result or a match run.

### D.2 Forbidden in normative statements

| Forbidden | Use instead |
|---|---|
| "ensure", "guarantee", "prevent" | The specific mechanism and its scope |
| "secure", "fully secure", "hardened" | The named controls applied |
| "user-friendly", "intuitive" | The measurable interaction requirement |
| "as needed", "if necessary", "where appropriate" | The specific condition |
| "etc.", "and so on", "among others" | The complete enumeration, or an explicit extension point |
| "simply", "just", "obviously" | Nothing — these conceal complexity |
| "real-time" | The latency budget with percentile |
| "best practice" | The named standard and its clause |
| "AI decides", "AI determines" | "AI suggests"; promotion requires human action (ADR-005) |

---

## Appendix E — Document Front-Matter Schema

Every document MUST open with front matter in this form. Fields are mandatory unless marked optional.

```yaml
document_id:    DOC-nn
title:          <Document title>
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        <semver>
status:         Not started | Drafting | In review | Approved | Superseded | Withdrawn
owner:          <role>
authors:        [<role>, …]
reviewers:      [<role>, …]          # required at status In review and above
last_updated:   YYYY-MM-DD
tier:           0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8
prerequisites:  [DOC-nn, …]          # documents assumed read
depends_on:     [DOC-nn, …]          # documents whose content this relies upon
referenced_by:  [DOC-nn, …]          # optional; maintained best-effort
supersedes:     <DOC-nn vX.Y.Z>      # optional
adrs_relied_on: [ADR-nnn, …]
open_questions: [OQ-nnn, …]
requirement_domains: [<CODE>, …]     # domain codes for requirements this document owns
security_review_required: true | false
```

---

## Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.0.0 | 2026-08-04 | Chief Software Architect | Initial baseline. Establishes requirement ID scheme, traceability contract, naming conventions, configurability tiers, diagram conventions, ADR and open-question formats, security annotation requirements, review gates, and prohibited patterns. Indexes 29 ADRs from requirements analysis (Appendix B) and records the provisional-ID migration (Appendix C). | Pending |

---

*End of DOC-00.*
