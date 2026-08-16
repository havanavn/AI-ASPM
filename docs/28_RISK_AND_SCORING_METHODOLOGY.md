---
document_id:    DOC-28
title:          Risk and Scoring Methodology
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        1.0.0
status:         In review
owner:          Principal Application Security Engineer
authors:        [Principal Application Security Engineer, Chief Software Architect, Staff Product Manager]
reviewers:      []
last_updated:   2026-08-04
tier:           2
prerequisites:  [DOC-00, DOC-01, DOC-03]
depends_on:     [DOC-00, DOC-01, DOC-03]
supersedes:     null
adrs_relied_on: [ADR-005, ADR-021, ADR-027]
open_questions: [OQ-017, OQ-018]
requirement_domains: [RSK]
security_review_required: true
---

# 28 — Risk and Scoring Methodology

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope)
2. [Prerequisites and Local Conventions](#2-prerequisites-and-local-conventions)
3. [What the Score Is For](#3-what-the-score-is-for)
4. [Model Structure](#4-model-structure)
5. [Factor Definitions](#5-factor-definitions)
6. [The Formula](#6-the-formula)
7. [Weight Configuration and Bounds](#7-weight-configuration-and-bounds)
8. [Versioning, Reproducibility, Attribution](#8-versioning-reproducibility-attribution)
9. [Coverage Qualification](#9-coverage-qualification)
10. [Aggregation and Fair Comparison](#10-aggregation-and-fair-comparison)
11. [The Service Level Engine](#11-the-service-level-engine)
12. [Effort Estimation](#12-effort-estimation)
13. [Anti-Gaming Controls](#13-anti-gaming-controls)
14. [The Explanation Contract](#14-the-explanation-contract)
15. [Calibration and Validation](#15-calibration-and-validation)
16. [Requirements Summary](#16-requirements-summary)
17. [Extensibility, Security, Closing](#17-extensibility-security-closing)

---

## 1. Purpose and Scope

### 1.1 Purpose

This document specifies how the platform computes risk, derives remediation deadlines, and aggregates posture. It exists as a standalone document because the score is the product's intellectual core: it will be challenged in every enterprise evaluation, and a methodology buried in an architecture document cannot be produced when challenged.

### 1.2 In scope

Factor definitions and their justification; the formula and why its shape was chosen; weight bounds; versioning and reproducibility; score change attribution; coverage qualification; node-level aggregation with the normalization that makes comparison fair; the service level policy engine; the effort estimation model; anti-gaming controls; the contract governing AI explanation; and the calibration methodology.

### 1.3 Out of scope

| Excluded | Owned by |
|---|---|
| The `ScoringModel` and `RiskScore` aggregate shapes | DOC-03 §12 |
| Vulnerability intelligence acquisition | DOC-21 |
| Dashboard and report presentation | DOC-12 |
| AI provider and capability mechanics | DOC-10 |
| Physical storage of scores and inputs | DOC-04 |

---

## 2. Prerequisites and Local Conventions

| Document | Why |
|---|---|
| DOC-01 | `PRD-RSK-001` – `015`; PP-1, PP-2; `CFG-RSK-001` – `002` |
| DOC-03 | `ScoringModel`, `RiskScore`, `ServiceLevelPolicy`, `ServiceLevelClock` (§12); `INV-RSK-01` – `11` |

**LC-01 — Requirement numbering continues DOC-01's sequence.** DOC-01 owns `PRD-RSK-001` through `PRD-RSK-015`, which state *what* must be true. This document issues `PRD-RSK-016` onward, specifying *how*. Sequence numbers are per class-and-domain pair and never reused (DOC-00 §6.1), so continuing the sequence across documents is correct; each individual identifier still has exactly one owning document. This is stated because a reader may expect all `PRD-RSK` identifiers to reside in DOC-01.

**LC-02 — Numeric values are provisional; structure is not.** ⚠ **Working assumption (OQ-017, OQ-018):** no organizational baseline of request volumes or existing service level targets has been supplied. Every default weight, threshold, and duration below is a considered starting point requiring calibration against real data (§15). **The factor set, formula shape, bounds mechanism, and reproducibility machinery are not provisional and do not change when the numbers are revised.**

---

## 3. What the Score Is For

### 3.1 The job

Reduce four thousand findings to the twelve that matter, in an order a security engineer can work through and a business owner can accept.

That is the entire purpose. Every design decision below is judged against it, and two consequences follow immediately.

**The score is a prioritization instrument, not a measurement of risk in an actuarial sense.** It does not estimate expected loss in currency. Attempting that would require breach probability and impact data no organization has, and would produce a number precise in appearance and arbitrary in fact. The score orders work; it does not price it.

**The score must survive challenge.** It will be disputed by whoever dislikes its implication — typically a business owner whose unit ranks poorly, and typically in a meeting. A score that cannot be explained factor by factor, reproduced exactly, and defended on its inputs will be disbelieved once and inert thereafter. Reproducibility is therefore not an engineering nicety; it is the property that makes the score usable at all.

### 3.2 Why determinism, restated

`PRD-RSK-001` and PP-2 require deterministic computation with no AI participation. The reasoning bears restating because the pressure to relax it will be constant and will be framed as sophistication.

| Property | Deterministic | Model-assisted |
|---|---|---|
| Same inputs, same output, indefinitely | Yes | No |
| Reproducible six months later for an audit | Yes | No |
| Change attributable to a specific input | Yes | No |
| Defensible when a business owner disputes it | Yes | No |
| Comparable across tenants for support and benchmarking | Yes | No |

A score whose value depends on a model invocation cannot support a resourcing decision that must later be justified, cannot be reproduced in an audit, and cannot be explained when it moves. AI explains the deterministic breakdown (§14); it never produces the number.

---

## 4. Model Structure

### 4.1 Two levels

```mermaid
flowchart LR
    F["Finding-level score<br/>one finding, one asset impact"] --> A["Asset-level score<br/>worst-case plus concentration"]
    A --> N["Node-level posture<br/>normalized for fair comparison"]
    N --> N2["Ancestor nodes<br/>recursive"]
```

*Figure 4.1 — Score levels. Finding-level scores prioritize work; node-level posture supports comparison and reporting. They answer different questions and are computed differently.*

**Finding-level** answers *what should be worked on next*. It is a per-finding-per-asset value, because the same vulnerability in an internet-facing payment service and in a retired internal tool are different priorities and must not share a score.

**Node-level** answers *which part of the organization needs attention*. It is not a sum of finding scores — §10 explains why summation is both unfair and gameable.

### 4.2 Factor set

Six active factors and one reserved. The set is product-fixed (`PRD-RSK-004`); weights are tenant-configurable within bounds (§7).

| Code | Factor | Source | Type |
|---|---|---|---|
| `SEV` | Base severity | Reported severity, normalized to the internal ordinal | Technical |
| `EXP` | Exploit likelihood | Exploit prediction from intelligence | Technical |
| `KEV` | Known exploited | Known-exploited status from intelligence | Technical |
| `EXPO` | Asset exposure | Asset exposure classification | Contextual |
| `CRIT` | Business criticality | Asset criticality, inherited or assigned | Contextual |
| `DATA` | Data sensitivity | Asset data classification attributes | Contextual |
| `REACH` | Reachability | Reserved, weight zero (DF-03) | Technical |

**Why these six.** Each answers a distinct question, and removing any one produces a specific failure:

- Without `SEV`, a trivial issue and a remote code execution rank equally.
- Without `EXP` and `KEV`, prioritization rests on severity alone — which is precisely why organizations have four thousand criticals and no order among them. **These two factors are the mechanism that reduces four thousand to twelve**, and they are what most distinguishes this model from severity sorting.
- Without `EXPO`, an internal-only weakness ranks with an internet-facing one.
- Without `CRIT`, a finding on a decommissioned tool ranks with one on a payment platform.
- Without `DATA`, a weakness on a system holding personal data ranks with one on a static marketing site.

**Why not more.** Every additional factor increases the explanation burden and the number of inputs a disputant can attack. Asset interconnection, blast radius, remediation cost, and time-since-detection were each considered and excluded: the first two require graph analysis not yet validated, the third makes the score a business decision rather than a risk statement, and the fourth is captured by the service level clock rather than by the score. Adding a factor is a model version change (§8), which is the correct friction.

**Why `REACH` is present at weight zero.** Reserving it means enabling reachability later is a weight change plus an input, not a model restructuring — and existing scores are unaffected until a tenant deliberately raises the weight (DOC-01 §16.2).

---

## 5. Factor Definitions

Each factor is defined by its input, its normalization to a common scale, its default weight, and the justification for that default.

All factors normalize to `[0, 1]`. Normalization is required because inputs arrive on incompatible scales — a severity ordinal, a probability, a boolean, a tier — and mixing raw scales makes weights uninterpretable.

### 5.1 `SEV` — Base severity

| | |
|---|---|
| **Input** | `Finding.effective_severity`, mapped through the tenant's taxonomy to the product-fixed internal ordinal (`PRD-VUL-005`) |
| **Normalization** | Ordinal position mapped to `[0, 1]` with the most severe at 1. For a five-value taxonomy: 1.00 / 0.75 / 0.50 / 0.25 / 0.10 |
| **Default weight** | 0.30 |
| **Justification** | Severity is the necessary base but a poor sole discriminator, since scanner-assigned severity is coarse and inflated. Thirty percent makes it the largest single factor without letting it dominate. |

**Note on the lowest value.** The floor is 0.10 rather than 0, so that an informational finding on a critical internet-facing asset holding personal data still produces a non-zero score. A zero floor would multiply the entire score to zero (§6) and hide the finding entirely, which is wrong: low severity on a high-value target is exactly the combination worth surfacing.

### 5.2 `EXP` — Exploit likelihood

| | |
|---|---|
| **Input** | Exploit prediction probability from vulnerability intelligence, where available |
| **Normalization** | Probability used directly, then rank-transformed within the tenant's finding population so that relative position drives the factor rather than absolute probability |
| **Default weight** | 0.20 |
| **Absent input** | Falls back to a neutral 0.5 with the fallback recorded in `factor_inputs`, never silently treated as 0 |

**Justification and the rank transform.** Raw exploit-prediction probabilities are heavily skewed: the vast majority of vulnerabilities sit below one percent, and a handful sit above fifty. Used directly, the factor is near-zero for almost everything and contributes nothing to ordering within the bulk of the population. Rank-transforming within the tenant's own population restores discrimination where the work actually is.

**The cost, stated.** Rank transformation makes the factor **population-relative**, so a finding's score can change when unrelated findings are added or resolved. This is a genuine downside — it means a score can move without anything about that finding changing. It is accepted because the alternative is a factor that does not discriminate, and it is mitigated by attribution (§8.3) recording *population shift* explicitly as the cause so that the movement is explainable rather than mysterious.

### 5.3 `KEV` — Known exploited

| | |
|---|---|
| **Input** | Presence in a known-exploited catalogue |
| **Normalization** | Binary: 1.0 present, 0.0 absent |
| **Default weight** | 0.20 |
| **Absent input** | 0.0 with the absence recorded. Absence of listing is weak evidence of non-exploitation |

**Justification.** Known-exploited status is the highest-confidence signal available: it is observed exploitation, not prediction. Twenty percent as a binary makes it decisive without being absolute — a known-exploited finding on a retired internal asset should still rank below a critical on an internet-facing payment service, and a weight that dominated would prevent that.

### 5.4 `EXPO` — Asset exposure

| | |
|---|---|
| **Input** | `Asset.exposure`, using the **more exposed** of declared and observed where they conflict (`INV-AST-08`) |
| **Normalization** | `INTERNET_PUBLIC` 1.00 · `PARTNER_B2B` 0.70 · `INTERNAL_ONLY` 0.35 · `AIR_GAPPED` 0.10 |
| **Default weight** | 0.15 |

**On using the more exposed value.** Where an asset is declared internal and observed public, the score uses public. Using the declaration would compute risk from a belief contradicted by evidence. The conflict itself remains a separate finding (`PRD-AST-017`); the score does not wait for it to be resolved.

**On the internal floor.** `INTERNAL_ONLY` at 0.35 rather than near zero encodes that internal-only is not safe. Assuming a network perimeter is a control is the assumption that produces the lateral movement in most breach reports.

### 5.5 `CRIT` — Business criticality

| | |
|---|---|
| **Input** | `Asset.criticality`, resolved through inheritance (`INV-AST-06`) |
| **Normalization** | Tier ordinal to `[0, 1]`, most critical at 1. Four tiers: 1.00 / 0.70 / 0.40 / 0.15 |
| **Default weight** | 0.15 |

**Justification.** Criticality is the primary business-context input and the factor a business owner is most likely to dispute — which is why override requires recorded justification (`INV-ORG-09`, `INV-AST-06`) and why the override is visible in the explanation.

### 5.6 `DATA` — Data sensitivity

| | |
|---|---|
| **Input** | Asset attributes for personal data, payment data, and regulated data, with volume band where recorded |
| **Normalization** | Highest applicable: regulated or payment 1.00 · personal data at scale 0.80 · personal data 0.60 · none recorded 0.20 |
| **Default weight** | 0.10 |

**On the "none recorded" floor of 0.20 rather than 0.** Absence of a data classification usually means nobody has classified the asset, not that it holds no sensitive data. A zero would reward the unclassified, making non-classification a way to lower scores. The floor makes unclassified assets slightly *less* favourable than confirmed-no-sensitive-data would be, which is the correct incentive and an application of PP-1.

### 5.7 Weight summary

| Factor | Default | Group |
|---|---|---|
| `SEV` | 0.30 | Technical: 0.70 |
| `EXP` | 0.20 | |
| `KEV` | 0.20 | |
| `EXPO` | 0.15 | Contextual: 0.40 |
| `CRIT` | 0.15 | |
| `DATA` | 0.10 | |
| `REACH` | 0.00 | Reserved |

Weights sum to 1.10 by design; the formula normalizes (§6.2). The technical-to-contextual ratio of roughly 7:4 is the model's central editorial judgement: technical characteristics determine *whether* something is dangerous, business context determines *whether it matters here*, and the former is weighted higher because a low-severity issue on a critical asset is still low-severity.

---

## 6. The Formula

### 6.1 Shape

```
raw       = Σ ( weightᵢ × factorᵢ )                       weighted sum
context   = max( EXPO, CRIT, DATA )                       contextual ceiling
score     = 100 × normalize( raw ) × ( 0.4 + 0.6 × context )
```

### 6.2 Why this shape and not the alternatives

**Why a weighted sum for the base rather than a product.** A product means any factor at zero zeroes the score. With six factors, several of which are legitimately zero — a finding not in a known-exploited catalogue, an air-gapped asset — a product produces zero for most findings and no ordering at all. A sum degrades gracefully when a factor is absent or unknown, which matters because inputs are frequently unavailable (§5.2, §5.3).

**Why a contextual multiplier on top of the sum.** A pure weighted sum lets technical severity alone carry a finding to a high score regardless of context: a critical severity finding on a retired air-gapped internal tool would score highly, which is wrong and is exactly the behaviour that makes teams distrust a score. The multiplier expresses that context is a *gate* on technical severity, not merely another addend.

**Why `max` rather than a sum of the contextual factors.** Exposure, criticality, and data sensitivity are substantially correlated — an internet-facing payment service is high on all three. Summing them triple-counts one underlying property. `max` takes the strongest contextual signal without compounding it.

**Why the multiplier floor is 0.4 rather than 0.** A zero floor makes a low-context asset score zero regardless of the finding, which hides genuine technical problems on assets nobody classified. The floor of 0.4 means the lowest-context asset scores at 40% of its technical value rather than nothing — still deprioritized, still visible.

**Why not CVSS environmental scoring.** CVSS environmental metrics exist for this purpose and were considered. They were rejected for three reasons: they are defined only for findings carrying a CVSS vector, which excludes manual assessment findings and many scanner outputs; they do not accommodate exploit prediction or known-exploited status, which are the factors doing the most prioritization work; and the modified base metrics are difficult to explain to a non-specialist audience, which defeats §3.1. The model above uses CVSS as an input to `SEV` where present, without adopting its environmental machinery.

### 6.3 Output

Integer `0–100`. Band thresholds are tenant-configurable within bounds, defaulting to: 90+ critical, 70–89 high, 40–69 medium, 15–39 low, below 15 informational.

**On presenting bands rather than raw scores.** A score of 73 and a score of 71 are not meaningfully different — the inputs are not that precise. Presenting bands prominently and the raw value secondarily prevents false precision driving argument about a two-point gap.

### 6.4 Requirements

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-RSK-016` | The formula MUST be a weighted sum of normalized factors modified by a contextual multiplier derived as the maximum of the contextual factors, with a floor preventing a zero multiplier. | A product zeroes on any absent factor; a pure sum lets technical severity ignore context; summing correlated contextual factors triple-counts. Each element of the shape addresses a specific failure. | M | AT |
| `PRD-RSK-017` | Every factor MUST normalize to `[0, 1]` before weighting, and every normalization MUST be recorded with the score. | Mixing raw scales makes weights uninterpretable and the explanation unusable. | M | AT |
| `PRD-RSK-018` | Where a factor's input is unavailable, the platform MUST apply a documented fallback and MUST record that the fallback was used. It MUST NOT silently substitute zero. | Silent zero substitution means missing data lowers the score, so absent intelligence looks like absent risk — PP-1 violated inside the formula. | M | AT |
| `PRD-RSK-019` | Scores MUST be presented as bands with the numeric value secondary. | The inputs do not support two-point precision, and presenting them as if they do invites argument about differences that carry no information. | M | DI |

---

## 7. Weight Configuration and Bounds

### 7.1 What tenants may change

| Configurable | Fixed |
|---|---|
| Factor weights, within bounds | The factor set |
| Band thresholds, within bounds | The formula shape |
| Contextual normalization tier values | The `max` contextual aggregation and the floor |
| Service level policies and calendars | The requirement that a policy version be pinned |

### 7.2 Bounds

| Factor | Min | Max | Why bounded |
|---|---|---|---|
| `SEV` | 0.15 | 0.45 | Below 0.15 severity stops discriminating; above 0.45 the model becomes severity sorting |
| `EXP` | 0.05 | 0.35 | Zero removes the primary volume-reduction mechanism |
| `KEV` | 0.05 | 0.35 | Zero discards the highest-confidence signal available |
| `EXPO` | 0.05 | 0.30 | Zero makes internet-facing and air-gapped equivalent |
| `CRIT` | 0.05 | 0.30 | Zero makes business context irrelevant, which is the failure the product exists to fix |
| `DATA` | 0.00 | 0.25 | May be zero where a tenant genuinely tracks sensitivity elsewhere |
| `REACH` | 0.00 | 0.20 | Reserved |

### 7.3 Requirements

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-RSK-020` | Weights MUST be validated against per-factor bounds at configuration time, and an out-of-bounds configuration MUST be rejected with a specific diagnosis. | Bounds exist so that a tenant cannot configure the model into meaninglessness — setting `EXP` and `KEV` to zero converts it back into the severity sorting that produced the four thousand findings. Rejection at configuration time is cheap; discovering it through inexplicable prioritization is not. | M | AT |
| `PRD-RSK-021` | Weight changes MUST require elevated permission distinct from operational permissions, MUST be audited with before and after values, and MUST NOT take effect on existing scores until an explicit recomputation is initiated. | A weight change silently alters enterprise-wide prioritization. Requiring explicit recomputation means the change is a deliberate event with a known scope, not a background shift that appears as unexplained score movement. | M | AT |
| `PRD-RSK-022` | The platform MUST support previewing the effect of a weight change on a sample of the tenant's findings before activation. | Without preview, a tenant tuning weights is guessing, and the feedback loop runs through a full recomputation and a confused security team. | S | DM |

---

## 8. Versioning, Reproducibility, Attribution

### 8.1 Model versioning

An activated `ScoringModel` version is immutable (`INV-RSK-05`). Changing weights, band thresholds, or the factor set creates a new version.

### 8.2 Reproducibility

Every `RiskScore` records the model version, every factor input value, every normalization applied, and every factor contribution (`INV-RSK-02`). Reproduction is therefore a lookup, not a recomputation.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-RSK-023` | A score MUST record sufficient input detail that its value can be recomputed identically without access to any other data, including data that has since changed. | Recomputation from live data would give a different answer once criticality, exposure, or intelligence has changed — which is always. Self-contained records make historical reproduction exact. This is what makes a score defensible in an audit six months later. | M | AT |
| `PRD-RSK-024` | Scores MUST be immutable. A recomputation creates a new score, and the prior score MUST be retained for the configured retention period. | An in-place update destroys the prior value and with it the ability to answer what changed. | M | AT |

### 8.3 Change attribution

When a score changes, the platform identifies which input changed and its contribution to the delta (`PRD-RSK-007`).

| Cause class | Example | Reported as |
|---|---|---|
| Intelligence update | Newly known-exploited | `KEV` changed 0 → 1 |
| Context change | Criticality reassigned | `CRIT` changed 0.40 → 1.00 |
| Severity adjustment | Practitioner downgraded | `SEV` changed, with the actor and reason |
| Population shift | Rank transform moved (§5.2) | `EXP` changed with population shift as the stated cause |
| Model change | Weight reconfigured | Model version changed, with the weight delta |
| Coverage change | Data became stale | Coverage qualifier changed; score value unchanged |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-RSK-025` | Score change attribution MUST identify the changed inputs and their contribution to the delta, and MUST distinguish a change caused by population shift from one caused by a change to the finding or its asset. | *"Why did our score change?"* is asked constantly, and speculation is the default answer. Distinguishing population shift matters because it is the one cause where nothing about the finding changed, and conflating it with a real change destroys trust in attribution generally. | M | AT |
| `PRD-RSK-026` | Where an aggregate posture score improves, the platform MUST determine whether the improvement is attributable to remediation or to reduced coverage, and MUST present the distinction. | A finding count falling because a scanner stopped running looks identical to one falling because vulnerabilities were fixed. This is PP-1 at its most consequential: an improvement narrative built on lost coverage will be presented to executives as success. | M | AT |

**`PRD-RSK-026` is the most important requirement in this document.** Every other requirement makes the score correct; this one makes it honest.

---

## 9. Coverage Qualification

Per PP-1 and `INV-RSK-06`, every aggregate score carries the coverage and freshness of its inputs.

```
⟨CoverageQualifier⟩
  ├─ assets_in_scope
  ├─ assets_with_current_data          per source type
  ├─ assets_never_measured
  ├─ oldest_data_age_days
  ├─ intelligence_age_days
  └─ confidence                        HIGH | MEDIUM | LOW | INSUFFICIENT
```

| Confidence | Condition |
|---|---|
| `HIGH` | ≥ 90% of in-scope assets have current data; intelligence within freshness threshold |
| `MEDIUM` | ≥ 70% current |
| `LOW` | ≥ 40% current, **or** intelligence beyond threshold |
| `INSUFFICIENT` | < 40% current. **A score MUST NOT be presented as a posture figure at this level** |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-RSK-027` | Every aggregate score MUST carry a coverage qualifier, and a score at `INSUFFICIENT` confidence MUST be presented as a coverage gap rather than as a posture figure. | A node with no data must not score well. Presenting a favourable number over 30% coverage is the specific mechanism by which the platform would produce a confident, wrong executive report. | M | AT, DI |
| `PRD-RSK-028` | Coverage MUST NOT be improvable by any action other than acquiring data. In particular, retiring or excluding unmeasured assets MUST NOT raise the coverage ratio. | Otherwise the cheapest way to reach `HIGH` confidence is to exclude everything unmeasured, which inverts the metric's meaning. | M | AT |

---

## 10. Aggregation and Fair Comparison

### 10.1 Why summation fails

The intuitive node score — sum the finding scores beneath it — fails in three ways, each of which would be raised in the first executive review.

**It penalizes size.** A business unit with 400 applications will exceed one with 40 regardless of relative security. The comparison is then dismissed as unfair, correctly, and dismissal of one metric spreads to the rest.

**It rewards concealment.** A unit that scans less has fewer findings and a lower sum. Summation makes *not looking* the cheapest improvement available.

**It obscures concentration.** One hundred medium findings spread across a portfolio and twenty criticals on one internet-facing payment service can sum equally, while demanding entirely different responses.

### 10.2 The aggregate

```
node_posture = w₁ × severity_pressure      normalized worst-case exposure
             + w₂ × concentration          share of score in the top decile of assets
             + w₃ × sla_health             proportion within remediation commitments
             + w₄ × coverage_penalty       explicit penalty for unmeasured scope
```

Defaults: `w₁` 0.40 · `w₂` 0.20 · `w₃` 0.25 · `w₄` 0.15.

| Component | What it captures | Why included |
|---|---|---|
| `severity_pressure` | Highest-scoring findings normalized against portfolio size | Size-independent, so comparison survives challenge |
| `concentration` | Whether risk is concentrated on few assets | Distinguishes the two scenarios of §10.1 that summation conflates |
| `sla_health` | Proportion of findings within their remediation commitment | Measures *process*, which is the only component a unit can improve immediately, and rewards behaviour rather than portfolio composition |
| `coverage_penalty` | Explicit penalty for unmeasured assets | Makes concealment expensive rather than free — the direct inversion of the failure in §10.1 |

**On `sla_health` being a quarter of the score.** It is the only component measuring what the unit *does* rather than what it *has*. A unit that inherits a poor portfolio cannot change that this quarter, but it can respond to findings within commitment. Weighting behaviour meaningfully is what makes the metric feel actionable rather than punitive, and a metric that feels punitive gets litigated instead of acted upon.

### 10.3 Requirements

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-RSK-029` | Node-level posture MUST NOT be a sum of finding scores. It MUST be size-normalized and MUST include an explicit penalty for unmeasured scope. | Summation penalizes size, rewards not scanning, and obscures concentration. Each failure would be raised at the first executive comparison, and the third makes the metric actively misleading. | M | AT |
| `PRD-RSK-030` | Comparative presentation MUST state the normalization applied and the coverage of each compared entity. | An unstated normalization is indistinguishable from an unfair comparison, and comparing a well-measured unit against a poorly-measured one without saying so favours the latter. | M | AT, DI |
| `PRD-RSK-031` | Comparative presentation MUST enforce a minimum comparison-set size or suppress. | A comparison against two peers discloses those peers' posture by inference (`SEC-AUZ-026`). | M | AT |

---

## 11. The Service Level Engine

### 11.1 Policy matching

A policy matches on finding characteristics, and the **most specific** match applies. Where two policies match with equal specificity, the shorter duration applies — a deliberate default: ambiguous configuration should err toward more urgency, not less.

```
⟨SlaMatchRule⟩
  ├─ score_band?          ⟨ScoreBand⟩
  ├─ severity?            SeverityId
  ├─ finding_class?
  ├─ exposure?
  ├─ criticality_tier?
  ├─ known_exploited?     bool
  └─ org_scope?           OrgNodeId          applies to a subtree
```

### 11.2 Default policies

⚠ **Provisional pending OQ-018.**

| Match | Target | Rationale |
|---|---|---|
| Known exploited, internet-facing | 3 business days | Observed exploitation on a reachable asset is the one case warranting interruption of planned work |
| Score band critical | 7 business days | |
| Score band high, internet-facing | 14 business days | |
| Score band high, other | 30 business days | |
| Score band medium | 60 business days | |
| Score band low | 180 business days | Long enough to batch with planned work; short enough to expire |
| Informational | No commitment | A deadline nobody intends to meet devalues every other deadline |
| Secret finding, validated live | 1 business day | Not a risk to weigh; an active exposure whose only remediation is rotation (`PRD-VUL-019`) |

**On informational carrying no commitment.** Assigning a deadline to findings that will not be remediated trains everyone to ignore deadline notifications, which destroys the mechanism for the findings that matter.

### 11.3 Clock mechanics

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-RSK-032` | A clock MUST pin its policy version at start, and a later policy change MUST NOT move an existing deadline. | A policy change retroactively moving deadlines makes commitments unstable and breaches unattributable. | M | AT |
| `PRD-RSK-033` | Duration MUST be computed in business time using the tenant's configured calendar and timezone, and calendar changes MUST NOT retroactively alter existing due dates. | A three-day deadline spanning a public holiday is not three working days. Calendar-naive computation produces systematic false breaches, which trains users to ignore breach notifications. | M | AT |
| `PRD-RSK-034` | Pausing MUST require a blocking attribution from an enumerated set, and paused time MUST be reportable separately from elapsed time. | Unattributed delay defaults to blaming the accountable team, which is usually wrong and always corrosive (PP-6). Enumeration prevents attribution becoming free text nobody can aggregate. | M | AT |
| `PRD-RSK-035` | Where a score increases such that a shorter policy applies, the platform MUST recompute the deadline from the original start using the new policy, and MUST NOT restart the clock. | A newly known-exploited finding must acquire the shorter deadline. Restarting would give a finding that has been open for weeks a fresh three days, which is the wrong direction entirely. Recomputing from the original start may produce an immediately-breached state, which is the correct and honest outcome. | M | AT |
| `PRD-RSK-036` | Where a score decreases, the deadline MUST NOT be extended automatically. Extension MUST require an approved, recorded action. | Otherwise downgrading severity is a mechanism for extending deadlines, which is a gaming path (`PRD-RSK-010`). | M | AT |

**On `PRD-RSK-035` producing immediate breach.** This is deliberate. A finding open for five weeks that becomes known-exploited *is* past a three-day commitment. Presenting it as breached is accurate and prompts the escalation the situation warrants; presenting it as newly-started conceals urgency.

### 11.4 Escalation

Escalation steps are tenant-configured (`CFG-RSK-002`) and trigger on elapsed proportion of budget: notify assignee at 50%, notify accountable owner at 75%, escalate to the next ancestor at breach, and escalate further on configured multiples of the target.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-RSK-037` | Escalation MUST NOT fire while a clock is paused for requester or third-party blocking. | Escalating the accountable team for a delay attributable elsewhere is precisely the failure PP-6 addresses. Escalation for the *blocking* party is a separate chain. | M | AT |

---

## 12. Effort Estimation

`PRD-PTR-017` requires deterministic, versioned effort estimation for assessment requests.

```
estimate_days = base(assessment_type, platform_types)
              + api_new × c₁ + api_changed × c₂
              + screens × c₃
              + role_pairs × c₄                 role_pairs = n(n−1)/2
              + authz_changed × c₅
              × environment_factor
              × unfamiliarity_factor
```

**On `role_pairs`.** Authorization testing effort scales with the number of *role pairs*, not roles, because each pair is a distinct horizontal-escalation test. Four roles produce six pairs, not four — a distinction that routinely causes underestimation by a factor of two on multi-role systems.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-RSK-038` | Effort estimation MUST be deterministic and versioned, and AI MUST NOT produce the estimate. | Estimates drive capacity commitments and must be explainable when missed. PP-2. | M | AT |
| `PRD-RSK-039` | The platform MUST record estimated against actual effort and MUST expose estimation bias by assessment type and platform type. | An uncalibrated estimate is worse than none, because it is trusted. Bias by type is actionable in a way aggregate error is not. | M | AT |
| `PRD-RSK-040` | Estimates MUST carry a confidence indication derived from the volume of calibration data for that type, and MUST be presented as low-confidence below a configured threshold of completed comparable items. | Roughly fifty completed items per type are needed before estimates are useful. An early estimate presented confidently and then missed damages trust in the whole capacity model. | M | AT, DI |

---

## 13. Anti-Gaming Controls

### 13.1 The premise

Once a score drives executive attention it will be optimized, and optimizing the score is cheaper than reducing risk. This is not cynicism; it is the predictable consequence of measurement, and a model that does not anticipate it will be gamed within two quarters.

### 13.2 Paths and controls

| Gaming path | Effect | Control |
|---|---|---|
| Mass-close findings as not-applicable | Score drops without remediation | Closure reasons are enumerated and separately reported (`PRD-VUL-011`); anomalous bulk closure is detected (§13.3) |
| Bulk-downgrade severity | `SEV` falls | Reported severity is immutable; adjustment is audited with actor and reason (`INV-VUL-07`) and adjustment rate is reported per principal |
| Except rather than fix | Obligation disappears | Excepted findings remain in aggregate risk (`INV-VUL-27`); exceptions expire and auto-reopen |
| Downgrade asset criticality | `CRIT` falls | Override requires justification and is audited (`INV-AST-06`); overrides are reported |
| Declare internal-only | `EXPO` falls | Observed exposure overrides declaration where more exposed (§5.4); conflicts surface as findings |
| Remove data classification | `DATA` falls | Unclassified floors at 0.20, which is worse than a confirmed-none classification (§5.6) |
| Stop scanning | Fewer findings | Coverage penalty in node posture (§10.2); coverage cannot be improved by exclusion (`PRD-RSK-028`) |
| Retire assets that are still live | Findings excluded | Retirement is audited; retired assets with recent activity evidence are flagged |
| Suppress as false positive | Finding excluded | Suppression expires and requires revalidation (`INV-VUL-21`); rate reported per principal |
| Extend deadlines | Breach avoided | Extension is a distinct approved state, reported separately from met (`INV-RSK-11`) |
| Split findings to reduce individual scores | Each scores lower | Score is per finding-asset pair; splitting increases count without lowering the maximum |

### 13.3 Requirements

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-RSK-041` | The platform MUST detect and surface anomalous rates of score-reducing actions — bulk closure as not-applicable, severity downgrade, criticality downgrade, exposure downgrade, suppression, and retirement — per principal and per organization node. | Each individual action is legitimate; the *rate* is the signal. Detection at the rate level catches gaming without impeding normal work. | M | AT |
| `PRD-RSK-042` | Reporting MUST distinguish verified remediation from other closure reasons wherever a closure or improvement figure is presented. | An undifferentiated closure rate is the metric most easily optimized by closing rather than fixing, and it is the figure most likely to appear in an executive summary. | M | AT, DI |
| `PRD-RSK-043` | Score-affecting configuration — weights, thresholds, criticality tiers, service level policies — MUST require elevated permission, MUST be audited, and MUST be reported in a periodic configuration-change summary. | Configuration change is the most efficient gaming path: one weight change affects every score at once and appears nowhere in a finding-level audit review. | M | AT |

---

## 14. The Explanation Contract

AI narrates; it does not compute (§3.2, `INV-AIC-04`).

**AI receives** the factor breakdown, the inputs with their sources and freshness, the contributions, the coverage qualifier, comparable scores in scope, and the change attribution.

**AI must** explain the breakdown in the audience's terms, cite the records supporting each claim, and state coverage limitations explicitly.

**AI must not** restate or recalculate the value, introduce any numeric value the platform did not compute, speculate about causes not present in the attribution, or reach a conclusion contradicting the score it purports to explain.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-RSK-044` | Score explanation MUST be generated from the recorded factor breakdown, and any generated explanation reaching a conclusion inconsistent with the recorded contributions MUST be rejected by output validation rather than presented. | An explanation contradicting the score it explains is worse than no explanation: the reader will believe the narrative over the number, because narrative reads as more authoritative than a table. | M | AT, MT |
| `PRD-RSK-045` | A non-AI factor breakdown presentation MUST be available for every score, independently of AI availability. | `PRD-AIC-003`. Defensibility cannot depend on a model being reachable, and the breakdown is the defence. | M | AT |

---

## 15. Calibration and Validation

### 15.1 The validation question

Not *is the score correct* — there is no ground truth — but **does the score order work better than the alternatives available**. The alternatives are severity sorting, arrival order, and practitioner intuition.

### 15.2 Method

| Method | What it tests |
|---|---|
| Retrospective ranking | Against historical findings whose outcome is known — exploited, found in a penetration test, or remediated as urgent — does the score rank them above the population? |
| Practitioner concordance | Do experienced practitioners' independent top-twenty selections overlap the score's top twenty? Low overlap indicates a missing factor or a mis-set weight |
| Known-exploited lead time | For findings that later became known-exploited, was the score already elevated before the listing? This tests whether `EXP` contributes real predictive value or merely restates severity |
| Stability | Do scores move only when inputs move? Excessive movement indicates over-sensitivity, particularly through the rank transform of §5.2 |
| Gaming detection efficacy | Do the §13 controls surface synthetic gaming introduced into a test dataset? |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-RSK-046` | The scoring model MUST be validated against the tenant's own historical finding data before a weight configuration is activated for production use, and the validation outcome MUST be recorded with the model version. | A weight set that has not been tested against real data is a guess presented as methodology, and it will be defended in a meeting where nobody can produce evidence for it. | M | DM |
| `PRD-RSK-047` | The platform MUST expose the concordance between score ranking and practitioner-assigned priority, as an ongoing model quality measure. | Persistent divergence means either the model is missing a factor practitioners use, or practitioners are working on the wrong things. Both are worth knowing, and neither is visible without the measure. | S | AT |

**On `PRD-RSK-047`.** This measure is uncomfortable by design: it can indicate that the model is wrong. That is precisely its value, and a model with no mechanism capable of showing it wrong is not a methodology.

---

## 16. Requirements Summary

Thirty-two requirements, `PRD-RSK-016` through `PRD-RSK-047`, continuing DOC-01's sequence per LC-01.

| Group | IDs | Count |
|---|---|---|
| Formula | `PRD-RSK-016` – `019` | 4 |
| Weight configuration | `PRD-RSK-020` – `022` | 3 |
| Versioning and attribution | `PRD-RSK-023` – `026` | 4 |
| Coverage | `PRD-RSK-027` – `028` | 2 |
| Aggregation | `PRD-RSK-029` – `031` | 3 |
| Service levels | `PRD-RSK-032` – `037` | 6 |
| Effort estimation | `PRD-RSK-038` – `040` | 3 |
| Anti-gaming | `PRD-RSK-041` – `043` | 3 |
| Explanation | `PRD-RSK-044` – `045` | 2 |
| Calibration | `PRD-RSK-046` – `047` | 2 |

Satisfies `PRD-RSK-001` – `015`, `PRD-PTR-017`, `PRD-CAP-011`, `CFG-RSK-001` – `002`, and model invariants `INV-RSK-01` – `11`.

---

## 17. Extensibility, Security, Closing

### 17.1 Extensibility

**Adding a factor** is a new model version with the factor at weight zero, so existing scores are unaffected and adoption is a deliberate tenant action. `REACH` is reserved this way. Anticipated future factors: asset interconnection blast radius, remediation complexity, and time-in-state pressure.

**Adding a service level dimension** extends `⟨SlaMatchRule⟩`; matching is already most-specific-wins, so a new dimension composes without reworking existing policies.

**Deliberate rigidity.** Determinism (`PRD-RSK-001`); product-fixed factor set and formula shape (`PRD-RSK-004`); bounded weights (`PRD-RSK-020`); coverage not improvable by exclusion (`PRD-RSK-028`); no automatic deadline extension (`PRD-RSK-036`).

**Known extension costs.** The rank transform (§5.2) makes `EXP` population-relative, so any future factor using a similar transform compounds the instability. Effort estimation needs roughly fifty completed items per type before it is useful (`PRD-RSK-040`), and that threshold is per type, so adding an assessment type restarts the accumulation.

### 17.2 Security considerations

**The score is integrity-sensitive rather than confidentiality-sensitive.** Its confidentiality is handled by scope (DOC-07); its integrity is this document's concern, because a manipulated score misdirects the security programme silently.

| Risk | Treatment |
|---|---|
| Weight manipulation | Elevated permission, audit, no retroactive effect without explicit recomputation (`PRD-RSK-021`) |
| Input manipulation | Severity, criticality, exposure, and classification changes are individually audited and rate-reported (§13) |
| Gaming through configuration | `PRD-RSK-043` — the most efficient path, since one change affects every score |
| Explanation manipulation | Output validation against the recorded breakdown (`PRD-RSK-044`) |
| Factor breakdown as a disclosure surface | Breakdown restricted to in-scope contributions (`SEC-AUZ-027`) |

**Residual risk.** The model is honest only where its inputs are. A tenant that systematically under-declares exposure and criticality will produce low scores that are internally consistent and externally wrong. The controls in §13 detect *rates* of change, not a consistently wrong baseline established at onboarding. Detecting that requires the exposure-conflict mechanism (§5.4) and periodic classification review, neither of which is complete. This is carried to DOC-26 as an accepted residual risk.

### 17.3 Open questions

| ID | Bearing | Status |
|---|---|---|
| OQ-017 | Request volume calibrates effort estimation and the feasible start date | Open; values provisional |
| OQ-018 | Existing service level targets replace the provisional defaults of §11.2 | Open; structure unaffected |

### 17.4 Notes for downstream documents

| Document | Note |
|---|---|
| DOC-04 | Score records are self-contained (`PRD-RSK-023`) and high-volume — a partitioning and retention candidate. Rank transformation requires a population-percentile projection |
| DOC-09 | Service level clock pause, resume, extension, and the recompute-not-restart rule (`PRD-RSK-035`) require state machine specification |
| DOC-10 | Owes the explanation capability against the §14 contract, including output validation rejecting inconsistent conclusions |
| DOC-12 | Owes coverage qualifier presentation, the improvement-versus-coverage distinction (`PRD-RSK-026`), band-primary presentation, and minimum comparison-set enforcement |
| DOC-16 | Owes the §15 validation suite, gaming-detection efficacy tests against synthetic data, and stability assertions on the rank transform |
| DOC-26 | Owes the baseline-misdeclaration residual risk (§17.2) and configuration-as-gaming-path analysis |

### 17.5 Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.0.0 | 2026-08-04 | Principal Application Security Engineer; Chief Software Architect; Staff Product Manager | Initial content-complete version. States the score's job as prioritization rather than actuarial risk and derives the determinism requirement from defensibility. Defines six active factors and one reserved, each with normalization, default weight, and justification including the non-zero floors that prevent missing data from lowering scores. Specifies the formula shape with each element justified against a named failure mode, and records why CVSS environmental scoring was rejected. Specifies weight bounds preventing a tenant from configuring the model back into severity sorting. Specifies reproducibility through self-contained score records, change attribution distinguishing population shift, and the improvement-versus-coverage distinction identified as the document's most important requirement. Specifies node aggregation that is size-normalized and penalizes unmeasured scope, with the three failure modes of summation stated. Specifies the service level engine including the recompute-not-restart rule and its deliberate production of immediate breach. Specifies effort estimation with role-pair scaling. Enumerates eleven gaming paths with controls. Specifies the AI explanation contract with output validation. Specifies a calibration methodology whose concordance measure is capable of showing the model wrong. Thirty-two requirements continuing DOC-01's sequence. | Pending |
