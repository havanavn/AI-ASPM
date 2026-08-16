---
document_id:    DOC-10
title:          AI Requirement
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        1.0.0
status:         In review
owner:          Chief Software Architect
authors:        [Chief Software Architect, Principal Security Architect, Staff Product Manager]
reviewers:      []
last_updated:   2026-08-04
tier:           5
prerequisites:  [DOC-00, DOC-01, DOC-03, DOC-07, DOC-24, DOC-26, DOC-28]
depends_on:     [DOC-00, DOC-01, DOC-02, DOC-03, DOC-05, DOC-07, DOC-24, DOC-26, DOC-28]
supersedes:     null
adrs_relied_on: [ADR-005, ADR-027]
open_questions: [OQ-027]
requirement_domains: [AIC]
security_review_required: true
---

# 10 — AI Requirement

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope) · 2. [What AI Is and Is Not Permitted to Do](#2-what-ai-is-and-is-not-permitted-to-do) · 3. [Provider Abstraction](#3-provider-abstraction) · 4. [The Suggestion Ledger](#4-the-suggestion-ledger) · 5. [Grounding Contracts](#5-grounding-contracts) · 6. [Indirect Prompt Injection](#6-indirect-prompt-injection) · 7. [Data Governance](#7-data-governance) · 8. [Capability Specifications](#8-capability-specifications) · 9. [Non-AI Fallbacks](#9-non-ai-fallbacks) · 10. [Evaluation Harness](#10-evaluation-harness) · 11. [Cost Control](#11-cost-control) · 12. [Requirements](#12-requirements) · 13. [Closing](#13-closing)

---

## 1. Purpose and Scope

**In scope.** Provider abstraction and routing; the suggestion ledger implementing ADR-005; grounding contracts per capability; indirect prompt injection defence; data governance and redaction; six capability specifications; non-AI fallbacks; the evaluation harness and its release gates; cost control.

**Out of scope.** The scoring model AI explains (DOC-28); the ledger's persistence (DOC-04 §20.3); API surface (DOC-05 §22); threat analysis (DOC-26 T3).

**LC-01.** Requirements are `PRD-AIC-021` onward, continuing DOC-01's sequence.

**LC-02.** ⚠ **Working assumption (OQ-027):** the platform calls providers and does not operate models. If it must host models, §3 gains a hosting topology and DOC-15 gains substantial scope; nothing else in this document changes.

---

## 2. What AI Is and Is Not Permitted to Do

The "AI-native" claim requires discipline because it is the most-abused adjective in the current security market and buyers have learned to discount it (DOC-01 §5.4).

| Permitted | Prohibited |
|---|---|
| Explain a deterministic computation | Produce or alter the computation |
| Draft text a human reviews before commitment | Commit text without review |
| Suggest a grouping, priority, or assignment | Apply one |
| Summarize records the requester may already read | Read beyond the requester's scope to summarize narrowly |
| Cite the records supporting each claim | Assert a claim without a citable source |
| State uncertainty and coverage limits | Present a confident narrative over incomplete data |

**Two prohibitions are architectural rather than behavioural**, which is what makes them reliable: AI holds no write grant on any domain table (DOC-04 §20.3), and no dependency edge exists from the AI module into any domain module (DOC-02 D8). A behavioural rule can be violated by a prompt; an absent grant cannot.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-AIC-021` | The AI module MUST hold no write grant on any domain table and MUST have no compile-time dependency on any domain module. | ADR-005 stated as an architectural property rather than a policy. A policy is violated by a prompt; an absent grant and an absent dependency edge are not. | M | AT, AR |
| `PRD-AIC-022` | Model output MUST NOT reach any privileged action path, including authorization decisions, workflow guards, score computation, service level policy matching, and automation rule conditions. | Each is a decision the platform is accountable for and must be able to reproduce. A model in any of these paths makes the decision irreproducible and the accountability unassignable. | M | AT, CR |

---

## 3. Provider Abstraction

### 3.1 Contract

```
ModelProvider
  ├─ capabilities        text completion | structured output | embedding
  ├─ context_window, max_output
  ├─ supports_structured_output   bool     — required for every capability here
  ├─ residency_regions   text[]
  ├─ data_retention_policy         NONE | TRANSIENT | RETAINED
  ├─ trains_on_submitted_data      bool
  └─ health              available | degraded | unavailable
```

**Supported provider classes:** hosted commercial; self-hosted inference server; any endpoint implementing a compatible interface. Provider-specific behaviour is confined to an adapter; capabilities depend on the contract, not on a provider.

### 3.2 Routing

Per capability: a primary model, an ordered fallback list, and a hard timeout. On timeout or unavailability the next model is tried; on exhaustion the capability degrades explicitly (§9).

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-AIC-023` | Every capability MUST declare a primary model, an ordered fallback list, a hard timeout, and a non-AI fallback. | Model latency and availability are outside the platform's control. Without an explicit degraded path, one slow provider makes a report or dashboard appear broken rather than incomplete (PP-9). | M | AT |
| `PRD-AIC-024` | A provider whose terms permit training on submitted data MUST NOT be selectable for tenant data without recorded explicit tenant consent, and the consent MUST name the provider. | `CON-AIC-001`. Generic consent to "AI features" is not consent to a named provider training on the customer's vulnerability inventory. | M | AT, DI |
| `PRD-AIC-025` | Provider selection MUST be constrained by the tenant's residency designation, and a provider unable to satisfy it MUST NOT be selectable. | Model provider egress is the newest and least-governed residency path and the one a tenant's data governance function asks about first (`SEC-TEN-021`). | M | AT |
| `PRD-AIC-026` | Provider, model identity, and model version MUST be recorded per invocation, and version pinning MUST be supported per capability. | Model behaviour changes between versions, so output quality changes with no change in the platform. Pinning makes behaviour reproducible; recording makes a quality regression attributable rather than mysterious. | M | AT |

---

## 4. The Suggestion Ledger

Every AI output is a suggestion in a store that is not the system of record.

```
AiSuggestion
  ├─ capability, subject_refs
  ├─ content              structured, per the capability's output schema
  ├─ citations            ⟨SourceCitation⟩[]  record references per claim
  ├─ provenance           provider, model_id, model_version, prompt_hash,
  │                       retrieved_context_refs, grounding_contract_version
  ├─ confidence_notes     stated uncertainty and coverage limits
  ├─ state                PROPOSED | PROMOTED | DISMISSED | EXPIRED | FAILED
  └─ promotion            actor, timestamp, resulting_change_ref
```

**Promotion** is an explicit human action that executes the change through the same operation a human would use (`PRD-API-047`), re-validating the promoting principal's authorization for that change (`INV-AIC-03`).

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-AIC-027` | Promotion MUST execute the resulting change through the ordinary operation, not through a privileged path, and MUST re-validate authorization for that change. | A dedicated promotion write path would bypass the invariants and permission checks of the ordinary path — reintroducing AI write authority through a side door. | M | AT, PT |
| `PRD-AIC-028` | A suggestion whose subject changed materially since generation MUST expire rather than remain promotable. | Promoting advice computed against stale state is worse than no advice, because it carries the authority of having been generated by the system. | M | AT |
| `PRD-AIC-029` | The ledger MUST be treated as data, never as instructions. No ledger content MUST be interpreted as a command by any platform component. | The ledger is a write surface influenced by untrusted input (§6). Treating its content as instruction would make injection into a finding an instruction to the platform. | M | AT, CR |

---

## 5. Grounding Contracts

Each capability declares what it may retrieve, what citation granularity it must produce, and its output schema. The contract is versioned and recorded per invocation.

```
GroundingContract
  ├─ permitted_sources      enumerated projections and record types
  ├─ scope_enforcement      the requesting principal's scope, always
  ├─ max_records, max_tokens
  ├─ citation_granularity   per_claim | per_section
  ├─ output_schema          structured; validated before use
  └─ prohibited_content     categories that MUST NOT enter context
```

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-AIC-030` | Grounding retrieval MUST enforce the requesting principal's scope, and a capability MUST NOT retrieve records the requester could not read directly. | Broad retrieval with narrow presentation is a scope bypass with a natural-language interface, and a particularly hard one to detect because the disclosure is paraphrased rather than quoted (`INV-AIC-05`). | M | AT, PT |
| `PRD-AIC-031` | Every capability MUST declare permitted sources explicitly. A capability MUST NOT retrieve from a source not in its contract. | An unconstrained retrieval surface expands silently as new data is added, and the expansion is invisible in review of the capability itself. | M | AT |
| `PRD-AIC-032` | Output MUST be structured and validated against the declared schema before use. Output failing validation MUST be rejected, not repaired. | Repairing model output is an attempt to fix content of unknown intent. Rejection is the only safe response, and a rejection rate is a quality signal. | M | AT |
| `PRD-AIC-033` | Factual claims MUST cite the specific records supporting them, and a claim without a resolvable citation MUST cause rejection of the output. | Citation is what makes an error detectable by the reader. An unsourced claim in an executive narrative is indistinguishable from a sourced one (`PRD-AIC-004`). | M | AT |
| `PRD-AIC-034` | Output MUST NOT contain a numeric value the platform did not compute. Numeric values MUST be substituted from the retrieved records rather than generated. | Models generate plausible numbers, and a plausible number in a security report is indistinguishable from a correct one to its reader. Substitution rather than generation removes the failure mode entirely. | M | AT |

**On `PRD-AIC-034`.** The implementation is substitution: the model produces a narrative with placeholders bound to retrieved record fields, and the platform fills them. This is stronger than validating generated numbers against sources, because it makes an incorrect number unrepresentable rather than detectable.

---

## 6. Indirect Prompt Injection

### 6.1 The threat

Finding content legitimately includes attacker-authored text: a payload captured by a proxy scanner, a hostile header value, a crafted parameter recorded as evidence. That content is then, by design, summarized by a model for the security team.

**An attacker who can place text into a scanned application can place text into model context, without any platform access at all** (DOC-26 T3, boundary B6). This is specific to this product class and is the domain's most under-appreciated risk.

**What the attacker can attempt.** Suppress a finding from a summary; characterize its severity as lower than recorded; misdirect stated priority; or induce output that misleads about a vulnerability's nature.

### 6.2 Defence in depth

| Layer | Control |
|---|---|
| **Containment** — load-bearing | AI holds no write authority (`PRD-AIC-021`). A successful injection produces a misleading *narrative*, not a state change |
| Structural segregation | Untrusted content is delimited and labelled as data in context, never concatenated into instruction position |
| Instruction anchoring | Capability instructions precede untrusted content and are restated after it |
| Output schema validation | Structure enforced; unexpected fields or shapes rejected (`PRD-AIC-032`) |
| Consistency validation | Output contradicting the retrieved records is rejected (`PRD-AIC-035`) |
| Numeric substitution | Values come from records, not from generation (`PRD-AIC-034`) |
| Labelling | Generated content identified in every representation (`PRD-AIC-036`) |
| Detection | Injection-pattern signals recorded and reportable |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-AIC-035` | Output MUST be validated for consistency with the retrieved records, and output contradicting them MUST be rejected. | This is the control against a successful injection: an injected instruction to characterize a critical finding as low produces output inconsistent with the record, which is detectable without knowing the injection occurred. | M | AT |
| `PRD-AIC-036` | Generated content MUST be visually and structurally identified as generated in every representation, including exported reports and notifications. | A reader's evaluation depends on provenance. Unlabelled generated text in an exported report is presented to its audience as the security function's considered assessment. | M | AT, DI |
| `PRD-AIC-037` | Untrusted content MUST be structurally delimited in context and MUST NOT be concatenated into instruction position. Capability instructions MUST be restated after untrusted content. | Segregation and anchoring are mitigation rather than prevention — injection is not a solved problem — which is why containment is the load-bearing control. | M | AT, CR |
| `PRD-AIC-038` | The platform MUST record and report signals indicating attempted injection in ingested content. | Detection does not prevent, but it identifies that an attacker is targeting the platform's inference path — itself a finding worth raising. | S | AT |

**Honest assessment.** Indirect prompt injection is not solved. Residual risk `RISK-PLT-001` records this. The reason its *impact* is bounded rather than its *likelihood* reduced to zero is architectural: because AI cannot write and its output is labelled, cited, and consistency-checked, a successful injection degrades reporting quality rather than data integrity.

---

## 7. Data Governance

### 7.1 Category controls

Tenants declare, per category, whether it may enter model context. Enforcement is at context assembly, not at prompt authoring.

| Category | Default | Configurable |
|---|---|---|
| Finding metadata — title, severity, class, state | Permitted | Yes |
| Finding description and remediation guidance | Permitted | Yes |
| Asset metadata — name, type, exposure, criticality | Permitted | Yes |
| Organization structure — node names | Permitted | Yes |
| Risk scores and factor breakdowns | Permitted | Yes |
| Coverage and freshness data | Permitted | Yes |
| Work item titles and states | Permitted | Yes |
| **Comment content** | **Excluded** | Yes |
| **Captured payloads and request fragments** | **Excluded** | Yes |
| **Component identifiers and SBOM content** | Permitted | Yes |
| **Principal identities** | **Excluded** | Yes |
| **Per-person workload data** | **Excluded** | **No** |
| **Evidence content** | **Excluded** | **No** |
| **Credentials and secret values** | **Excluded** | **No** |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-AIC-039` | Category permission MUST be enforced at context assembly, and a category excluded by tenant configuration or by product rule MUST be unreachable by any capability. | Enforcement at prompt authoring depends on every prompt author remembering. Enforcement at assembly makes exclusion structural. | M | AT, CR |
| `PRD-AIC-040` | Evidence content, credentials, secret values, and per-person workload data MUST NOT enter model context under any configuration. | Evidence is working exploit material; credentials are live access; workload data is personal data about employment. No capability's value justifies transmitting any of them (`INV-AIC-07`). | M | AT, CR |
| `PRD-AIC-041` | Redaction MUST be applied at context assembly for detected credentials, secret patterns, and personal data, and the redaction MUST be logged. | Context assembled from findings will otherwise include secret values embedded in captured payloads — content that entered as finding data rather than as a credential field. | M | AT |
| `PRD-AIC-042` | Comment content and captured payloads MUST default to excluded. | Users write in comments assuming a bounded audience, and that assumption is reasonable. Captured payloads are the injection vector of §6, so excluding them by default reduces exposure to it. | M | AT |

**On `PRD-AIC-042` and §6 together.** Excluding captured payloads by default materially reduces injection exposure, but a tenant may enable them for better analysis of specific findings. That is a legitimate tradeoff and it is theirs to make — which is why the control is configurable and why the containment layer must hold regardless.

### 7.2 Invocation records

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-AIC-043` | Every invocation MUST be recorded with capability, principal, provider, model identity and version, prompt hash, retrieved context references, redaction applied, outcome, and token consumption. Prompt and output retention MUST be configurable. | Tenants ask what the platform sent to a provider and on whose behalf; auditors ask afterwards. Retention is configurable because prompts contain tenant data and indefinite retention is a liability. | M | AT |
| `PRD-AIC-044` | The platform MUST provide a tenant-visible report of what data categories left the boundary, to which provider, over a period. | Data governance approval requires this, and producing it on request rather than from logs is the difference between an approvable capability and an unapprovable one. | M | AT |

---

## 8. Capability Specifications

### 8.1 Executive narrative — `PRD-AIC-014`

| | |
|---|---|
| **Purpose** | Summarize posture, material changes, and their drivers for a scope and period |
| **Sources** | Posture aggregate projection; score change attribution; coverage qualifiers; service level summary; top findings by score |
| **Citation** | Per claim |
| **Output** | Structured sections: state, material changes with drivers, coverage limitations, recommended attention |
| **Mandatory content** | Coverage and confidence stated explicitly; where confidence is `INSUFFICIENT`, the narrative MUST lead with that rather than reporting a figure |
| **Rejection** | Any unsourced claim; any generated numeric; any conclusion contradicting the underlying measures |
| **Fallback** | Templated summary from the same projections without narrative prose |

**Why coverage leads when confidence is insufficient.** This is the capability with the highest consequence of a confident wrong statement: its audience is the least able to detect an error and the most likely to act on it (DOC-01 §7.2 A8). A narrative over 30% coverage that reads as authoritative is the specific failure PP-1 exists to prevent.

### 8.2 Score explanation — `PRD-AIC-015`

| | |
|---|---|
| **Sources** | The recorded factor breakdown and change attribution only |
| **Citation** | Per factor |
| **Output** | Per-factor explanation in the audience's terms; the change cause where a change occurred |
| **Prohibited** | Restating or recalculating the value; speculating about causes absent from the attribution; reaching a conclusion inconsistent with the contributions |
| **Fallback** | The factor breakdown table (`PRD-RSK-045`) |

### 8.3 Finding grouping — `PRD-AIC-016`

| | |
|---|---|
| **Sources** | Finding metadata, component identity, weakness classification, affected assets within scope |
| **Output** | Proposed groups with a stated basis per group |
| **Constraint** | **Advisory only.** Grouping MUST NOT alter finding identity or state; deterministic deduplication remains the system of record |
| **Fallback** | Deterministic grouping by component and vulnerability identifier |

### 8.4 Remediation guidance — `PRD-AIC-017`

| | |
|---|---|
| **Sources** | Knowledge base articles; finding metadata; asset technology attributes; fixed-version data |
| **Citation** | Per recommendation, to the knowledge article |
| **Constraint** | Grounded in curated tenant content, not model recall. A recommendation without a knowledge base source MUST be labelled as ungrounded or withheld |
| **Fallback** | Direct knowledge base article links by finding class |

**On grounding in curated content rather than recall.** Model recall produces generically plausible guidance. Grounding in the tenant's own knowledge base produces guidance specific to their stack, standards, and prior decisions — which is the difference between advice a developer follows and advice they ignore.

### 8.5 Prioritization suggestion — `PRD-AIC-018`

| | |
|---|---|
| **Sources** | Scored findings within scope; service level status; asset context; declared operational constraints |
| **Output** | Suggested ordering with stated reasoning per item |
| **Constraint** | MUST NOT alter recorded scores or service levels. Where the suggestion diverges from score order, the divergence MUST be stated with its reason |
| **Fallback** | Score-ordered list |

### 8.6 Drafting assistance — `PRD-AIC-019`

| | |
|---|---|
| **Sources** | The object being drafted about, within scope |
| **Output** | Editable draft, attributed as generated until a human accepts it |
| **Constraint** | Never committed without human acceptance; attribution persists until acceptance |
| **Fallback** | Templates |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-AIC-045` | Where a capability's suggestion diverges from a deterministic computation, the divergence MUST be stated with its reason rather than presented as the computation's result. | Silent divergence makes the deterministic result appear to be something it is not, which undermines the reproducibility the deterministic path exists to provide. | M | AT |
| `PRD-AIC-046` | Executive narrative MUST lead with coverage limitation where confidence is `INSUFFICIENT`, and MUST NOT present a posture figure as the primary statement. | Its audience is least able to detect an error and most likely to act on it. A confident narrative over incomplete data is PP-1 violated at the point of maximum consequence. | M | AT, MT |

---

## 9. Non-AI Fallbacks

Every capability has one (`PRD-AIC-003`). The platform remains fully functional with AI disabled; a tenant that turns it off loses convenience, not capability.

| Capability | Fallback | What is lost |
|---|---|---|
| Executive narrative | Templated summary from the same projections | Prose synthesis; the figures and coverage are identical |
| Score explanation | Factor breakdown table | Plain-language rendering |
| Finding grouping | Deterministic grouping by component and vulnerability | Cross-cutting groupings a human would notice |
| Remediation guidance | Knowledge article links by finding class | Contextual selection |
| Prioritization | Score-ordered list | Adjustment for constraints the score does not encode |
| Drafting | Templates | Draft prose |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-AIC-047` | Disabling AI MUST NOT remove any figure, queue, report section, or workflow. Only presentation quality MUST degrade. | This is what makes the platform sellable into environments that prohibit model use — which includes several of the most security-conscious buyers. | M | AT |
| `PRD-AIC-048` | Where a capability is unavailable, the interface MUST state that it is unavailable and why, and MUST NOT render an empty section. | An empty section in an executive report reads as "nothing to report" (PP-9). | M | AT, DM |

---

## 10. Evaluation Harness

AI output quality is not verifiable by inspection and regresses silently on model change, prompt change, or context change. Without a gating harness, quality is discovered by users, in production, in an executive report.

### 10.1 Fixtures

Per capability: a corpus of grounded scenarios with known-correct facts, drawn from synthetic tenant data spanning high and low coverage, conflicting inputs, empty scopes, and stale intelligence.

### 10.2 Measures and thresholds

| Measure | Definition | Gate |
|---|---|---|
| **Citation validity** | Proportion of claims whose citation resolves to a record supporting them | 100% — a single invalid citation is a defect, not a rate |
| **Numeric fidelity** | Proportion of numeric values matching the source record | 100% by construction (`PRD-AIC-034`); any deviation indicates the substitution path was bypassed |
| **Grounding accuracy** | Proportion of factual claims supported by retrieved context | ≥ 98% |
| **Coverage disclosure** | Proportion of low-coverage scenarios where the limitation is stated | 100% |
| **Injection resistance** | Proportion of injected scenarios where output remains consistent with records | ≥ 95%, with every failure reviewed individually |
| **Scope containment** | Proportion of scenarios where no out-of-scope record influences output | 100% |
| **Consistency** | Proportion of outputs consistent with the deterministic computation they explain | 100% |
| **Refusal correctness** | Proportion of insufficient-data scenarios where the capability declines rather than answers | ≥ 95% |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-AIC-049` | The harness MUST gate release. A capability failing any 100% threshold MUST NOT ship, and a capability failing a rate threshold MUST NOT ship without a recorded exception naming the failures. | Citation validity, numeric fidelity, coverage disclosure, scope containment, and consistency are absolute because each failure is a specific defect rather than a quality distribution — an invalid citation is wrong, not slightly wrong. | M | AT |
| `PRD-AIC-050` | The harness MUST run on any change to a model version, prompt, grounding contract, or provider, and results MUST be recorded against the change. | These four are the variables. A change to any of them changes output quality with no change to the platform, so the harness must be triggered by them rather than by a release schedule. | M | AT |
| `PRD-AIC-051` | The injection corpus MUST include injected content in every field that reaches model context, and MUST be extended whenever a new field is added to a grounding contract. | The corpus is complete at authoring and decays as contracts grow. The extension requirement is the mechanism against decay. | M | AT |
| `PRD-AIC-052` | Human review of a sampled proportion of production output MUST be supported and its outcome MUST feed the measures. | The harness tests known scenarios. Production output encounters cases the corpus does not, and sampled review is the only mechanism that surfaces them. | S | DM |

---

## 11. Cost Control

AI consumption scales with usage rather than with seats, so an unbounded capability invoked from a dashboard refresh produces a commercially significant bill from ordinary use.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-AIC-053` | Budgets MUST be enforced per tenant and per capability, with configurable periods, and consumption MUST be visible to the tenant against the budget. | A tenant cannot manage consumption it cannot see, and an unobservable metering dimension produces disputed invoices (`PRD-AIC-011`). | M | AT |
| `PRD-AIC-054` | On budget exhaustion the capability MUST become unavailable with the reason stated, and the platform MUST NOT silently degrade output quality to reduce cost. | Silently switching to a cheaper model changes output quality with no signal, which is worse than unavailability because the reader cannot tell. | M | AT |
| `PRD-AIC-055` | Invocation MUST be rate-limited per principal in addition to the tenant budget, and repeated identical requests within a window MUST return the cached suggestion rather than re-invoking. | Without per-principal limits one user's refresh loop exhausts the tenant budget. Caching identical requests is both a cost and a consistency control — the same question should not yield two different narratives. | M | AT |
| `PRD-AIC-056` | Capabilities MUST NOT be invoked automatically on view. Invocation MUST be explicit or scheduled. | Automatic invocation on view makes cost a function of page loads, which is unbounded and unrelated to value. | M | AT, AR |

**On `PRD-AIC-055` caching identical requests.** The consistency argument is the stronger one: two executives asking the same question of the same data should receive the same narrative, and a re-invocation would not guarantee that.

---

## 12. Requirements

Thirty-six requirements, `PRD-AIC-021` – `056`, all `MUST_HAVE` except `PRD-AIC-038` and `PRD-AIC-052`.

| Group | IDs | Count |
|---|---|---|
| Permitted scope | `021` – `022` | 2 |
| Provider abstraction | `023` – `026` | 4 |
| Suggestion ledger | `027` – `029` | 3 |
| Grounding | `030` – `034` | 5 |
| Injection defence | `035` – `038` | 4 |
| Data governance | `039` – `044` | 6 |
| Capabilities | `045` – `046` | 2 |
| Fallbacks | `047` – `048` | 2 |
| Evaluation | `049` – `052` | 4 |
| Cost | `053` – `056` | 4 |

Satisfies `PRD-AIC-001` – `020`, `CFG-AIC-001`, `CON-AIC-001`, and `INV-AIC-01` – `09`.

---

## 13. Closing

### 13.1 Extensibility

A new capability is a grounding contract, a prompt, an output schema, evaluation fixtures, and a non-AI fallback — all five, because a capability missing any of them cannot be gated, cannot be governed, or cannot degrade. A new provider is an adapter. Category controls apply to new capabilities automatically because enforcement is at context assembly.

**Deliberate rigidity.** No write grant and no dependency edge (`PRD-AIC-021`); no model output in a privileged path (`PRD-AIC-022`); no generated numerics (`PRD-AIC-034`); evidence, credentials, and workload data never in context (`PRD-AIC-040`); output rejected rather than repaired (`PRD-AIC-032`); no automatic invocation on view (`PRD-AIC-056`).

**Known extension cost.** Every grounding contract field addition requires extending the injection corpus (`PRD-AIC-051`). Numeric substitution requires the output schema to bind placeholders to record fields, which constrains how freely a capability's prose can be structured — a real limitation accepted to make incorrect numbers unrepresentable.

### 13.2 Security considerations

The domain's distinctive risk is indirect prompt injection reachable without platform access (§6). Secondary risks: scope bypass through broad grounding retrieval; data egress to third-party providers; secret and personal data leakage into context; and cost exhaustion as a denial-of-service vector.

**Residual risks.** `RISK-PLT-001` — injection is mitigated, not prevented; impact is bounded architecturally rather than likelihood reduced to zero. Category exclusion depends on detection for embedded secrets (`PRD-AIC-041`), and a credential in an unrecognized format reaches context; the redaction corpus is the control and its adequacy is the least verifiable assumption here.

### 13.3 Notes for downstream documents

| Document | Note |
|---|---|
| DOC-08 | Owes generated-content labelling that survives export, the unavailability state of `PRD-AIC-048`, and a promotion interface that makes the resulting change explicit before acceptance |
| DOC-12 | Narrative sections in reports carry generated-content labelling and coverage disclosure |
| DOC-15 | Owes provider connectivity, egress residency constraint, and — if OQ-027 resolves to hosting — inference infrastructure |
| DOC-16 | Owes the harness itself, the injection corpus, the redaction corpus, and a test asserting the AI module holds no write grant |
| DOC-21 | Provider connectivity is a connector under DOC-21's contract |

### 13.4 Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.0.0 | 2026-08-04 | Chief Software Architect; Principal Security Architect; Staff Product Manager | Initial content-complete version. States ADR-005 as two architectural properties — no write grant, no dependency edge — rather than as policy. Specifies provider abstraction with residency and training-consent constraints; the suggestion ledger with promotion through the ordinary operation; grounding contracts with numeric substitution rather than validation so that incorrect numbers are unrepresentable; eight-layer injection defence with containment identified as load-bearing and the residual honestly assessed; category-based data governance enforced at context assembly with four categories never permitted; six capability specifications with sources, citation granularity, prohibitions, and fallbacks; non-AI fallbacks for every capability with what is lost stated; an evaluation harness with five absolute thresholds and three rate thresholds gating release; and cost control prohibiting automatic invocation on view. Thirty-six requirements. | Pending |

---

*End of DOC-10.*
