---
document_id:    DOC-20
title:          Open Questions
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        1.0.0
status:         In review
owner:          Staff Product Manager
authors:        [Staff Product Manager]
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

# 20 — Open Questions

Maintained continuously. Format per DOC-00 §14.1. Every question carries a **working assumption**, because authoring must never block on an unanswered question (DOC-00 §14.2) and must never proceed on an *undocumented* one.

## Status summary

| Status | Count |
|---|---|
| Answered / assumption ratified | 3 |
| Open, blocking implementation | 2 |
| Open, affecting values not structure | 5 |
| **Total** | **10** |

**All 26 documents are authored.** No open question blocked authoring: each was carried on a documented working assumption marked at its point of use, per DOC-00 §14.2. **Two now block implementation** rather than documentation, and their character has changed accordingly: OQ-015 because hash partition counts are irreversible after production data, and OQ-026 because three credential paths in the first build block depend on it.

**What the assumptions cost.** Every requirement written against an assumption is structurally sound and numerically provisional. Answering OQ-015 changes one table (DOC-01 §12.1) and the partition thresholds derived from it; answering OQ-026 changes DOC-06 §7 and DOC-15 §7. No document requires restructuring on any answer, which was the purpose of binding targets to named profiles and controls to required properties rather than to figures and products.

---

## Ratified working assumptions

These were open questions resolved by ratifying the recommended assumption rather than by an answer. They are marked ⚠️ at each point of use in DOC-01.

### OQ-010 — Internal deployment before commercial, or in parallel?
**Why it matters.** Determines the MVP boundary and the migration strategy.
**Owner.** Product sponsor · **Required by.** DOC-17
**Working assumption (ratified).** Internal deployment first; commercial architecture built from the start with no shortcuts that compromise tenancy.
**Impact if wrong.** Low structural, moderate sequencing. Affects DOC-17 only.
**Status.** `Answered` — assumption ratified.

### OQ-011 — Priority markets for v1
**Why it matters.** Drives the residency and regulatory matrix.
**Owner.** Product sponsor · **Required by.** DOC-24
**Working assumption (ratified).** Vietnam-first; EU and US architected but not activated. Residency pinning designed; activation is configuration.
**Impact if wrong.** Low.
**Status.** `Answered` — assumption ratified.

### OQ-012 — Replace or feed an incumbent executive dashboard?
**Why it matters.** If the platform must feed an incumbent, DOC-02 gains an external dependency and DOC-12 gains an export contract.
**Owner.** Product sponsor · **Required by.** DOC-02
**Working assumption (ratified).** Replace, consistent with the single-source-of-truth premise (PP-10, ADR-028).
**Impact if wrong.** Moderate.
**Status.** `Answered` — assumption ratified.

---

## Open

### OQ-015 — Actual portfolio sizing ⚠️ BLOCKING DOC-04
**Question.** How many organization nodes, assets, repositories, services, findings, and named users must the reference deployment support?
**Why it matters.** Index strategy, partitioning, and read model design in DOC-04 depend on volume. Non-functional targets are bound to the reference profiles at DOC-01 §12.1, which are constructed rather than measured.
**Blocks.** Build block 1 — hash partition counts for `component_entry`, `finding`, and `finding_asset_impact` must be fixed before first production data, and changing one afterwards redistributes every row (`CON-DAT-035`, `OPS-DEP-012`). Also determines the profile against which performance tests execute (`TST-PLT-006`) and the basis for capacity forecasting (`OPS-DEP-008`).
**Owner.** Security program owner · **Required by.** Before implementation begins
**Escalated.** Past its original required-by date. This is the more urgent of the two blocking questions because its consequence is irreversible rather than merely expensive.
**Working assumption.** Reference tenant profiles at DOC-01 §12.1, with Medium as the reference.
**Impact if wrong.** Profile figures change; **no NFR requires restructuring**. But a partition count set from a wrong assumption is corrected only by a full table rewrite under a maintenance window.
**A rough figure is sufficient.** "Approximately 200 projects, 3,000 repositories, 50,000 findings" would let the partition decision be made with a recorded basis. Precision is not required; an order of magnitude is.
**Status.** `Open` · **Escalated**

### OQ-017 — Current request volume and intake channel
**Question.** How many assessment and test requests arrive per month today, and through which channel?
**Why it matters.** Calibrates the effort estimation model (`PRD-PTR-017`) and the feasible start date computation (`PRD-CAP-010`). Also informs the migration adapter scope.
**Blocks.** Values in DOC-28, not its structure.
**Owner.** Security program owner · **Required by.** Before DOC-28
**Working assumption.** Not required for DOC-01. Estimation models present a low-confidence indication until roughly fifty completed items per type exist.
**Impact if wrong.** None structural.
**Status.** `Open`

### OQ-018 — Existing service level definitions
**Question.** Do documented remediation and assessment SLAs exist, and are they keyed to request type or to criticality?
**Why it matters.** DOC-28 needs real values, not invented ones.
**Blocks.** Values in DOC-28.
**Owner.** Security program owner · **Required by.** Before DOC-28
**Working assumption.** Structure defined in the platform; values are tenant configuration by design (`CFG-RSK-002`).
**Impact if wrong.** None structural.
**Status.** `Open`

### OQ-019 — Security team size and availability data source
**Question.** How many FTE, and is leave or HR data integrable?
**Why it matters.** Capacity model accuracy (`PRD-CAP-002`, `PRD-CAP-003`).
**Owner.** Security program owner · **Required by.** Before DOC-12
**Working assumption.** Manual availability entry supported; external import optional.
**Impact if wrong.** None structural.
**Status.** `Open`

### OQ-025 — Incumbent work tracker identity
**Question.** Which tracker is being replaced, and what fidelity does its export support?
**Why it matters.** `PRD-ING-013` migration fidelity — preserving original authorship and timestamps — depends on what the source export contains. A comment thread attributed entirely to a migration principal is unusable as history.
**Blocks.** The migration adapter in DOC-11.
**Owner.** Security program owner · **Required by.** Before DOC-11
**Working assumption.** A generic adapter over structured export, with fidelity limits documented per source.
**Impact if wrong.** Moderate.
**Status.** `Open`

### OQ-026 — Secrets vault: platform-provided or enterprise integration? ⚠️ BLOCKING DOC-06
**Question.** Does the platform provide its own secrets vault, integrate with an existing enterprise vault, or both?
**Why it matters.** Test account credentials (`PRD-PTR-004`), connector credentials (`PRD-CON-002`), and secret findings (`PRD-VUL-019`) all depend on it. It is also the platform's largest credential concentration and therefore a primary threat-model input.
**Blocks.** Build block 1. Three credential paths depend on it: test account credentials (`SEC-PTR-004`), connector credentials (`PRD-CON-021`), and secret finding values (`PRD-VUL-019`). All three are in the first block, and the connector credential store is the platform's highest-value asset (DOC-26 §3.3).
**Owner.** Principal Security Architect · **Required by.** Before implementation begins
**Escalated.** Past its original required-by date.
**Working assumption.** Integration supported, with a platform-provided default for deployments lacking an enterprise vault.
**Impact if wrong.** Moderate for the specification: DOC-06 §7 and DOC-15 §7 change; §7's contract accommodates both. **Higher than moderate for the product**: a platform-provided default store is itself a new asset of the highest value and would require its own design review (DOC-06 §18.2).
**Status.** `Open` · **Escalated**

### OQ-027 — Per-tenant AI self-hosting in v1?
**Question.** Must the platform operate models on a tenant's behalf, or is provider choice including self-hosted endpoints sufficient?
**Why it matters.** If the platform must operate models, DOC-15 gains substantial scope — GPU capacity planning, model lifecycle, and per-tenant inference isolation.
**Blocks.** DOC-10, DOC-15.
**Owner.** Chief Software Architect · **Required by.** Before DOC-10
**Working assumption.** Provider choice including self-hosted and compatible endpoints. The platform does not operate models.
**Impact if wrong.** Moderate.
**Status.** `Open`

---

### OQ-028 — May a RUNTIME finding be identified when the source supplies no parameter name?
**Question.** DOC-03 §10.2 declares `parameter_name` an identity input of the RUNTIME class, and `PRD-ING-021` forbids a parser from inferring or defaulting a field the source did not supply. A DAST template match against a URL — nuclei's entire output — has no parameter. Is such a finding identified with the parameter recorded as ABSENT, or is it a different class, or is it out of scope?
**Why it matters.** It decides whether the group's DAST output can be ingested at all. The fingerprint builder refuses a declared input that is missing, so today every such result is held in quarantine with the reason named (ADR-060) — correct, and it means nuclei findings do not reach the platform. The three candidate answers have different costs: recording the parameter as ABSENT makes every parameterless match against one URL and one template a single finding, which is probably right and is a change to an identity class; a new class is a change to DOC-03 and to the fingerprint algorithm's declared inputs; declaring it out of scope means the platform has no answer for dynamic testing, which DOC-01 does not accept.
**Blocks.** Ingestion of DAST output. Does not block anything already built: the quarantine path is the honest behaviour in the meantime, and it is tested.
**Owner.** Chief Software Architect · **Required by.** Before DAST results are expected in the platform
**Working assumption.** None, deliberately. `FindingFingerprint` already distinguishes an ABSENT input from an empty one, so the mechanism exists — but choosing to use it here changes what identity MEANS for a class, and DOC-03 §8.5 records that identity rules are the expensive kind of thing to get wrong. Quarantine with a named reason is the behaviour until this is decided.
**Impact if wrong.** Moderate, and asymmetric. Too loose collapses distinct dynamic findings into one and makes fixing one appear to fix all; too tight produces a new finding per request and destroys triage state. The first is the more expensive per DOC-03 §10.2.
**Status.** `Open`

---

### OQ-029 — When two assets are one thing, which survives the merge?
**Question.** ADR-064 stops duplicate assets being created. It does not repair the ones already recorded — the estate carries three `REPOSITORY` rows for one repository, and two more were produced during verification before the cause was understood. Merging them means one row survives and the other is closed. Which one, when both carry work?
**Why it matters.** The duplicates are not empty. An asset can hold findings, coverage state, edges to the composition graph, assessment scope, and grants. The obvious rule — keep the oldest — loses whatever was recorded against the newer row, which is usually the one the interface has been writing to most recently. The opposite rule loses the history the older row anchors, including "what was deployed when this finding was open", which `INV-AST-16` and the temporal edge model exist to preserve. Choosing per-table is worse than either: a finding pointing at one row and its coverage at the other is a split record nothing detects.
**Blocks.** The repair migration ADR-064 defers. Does not block ADR-064 itself, which prevents new duplicates and is independently useful.
**Owner.** Chief Software Architect · **Required by.** Before real inventory is loaded, because a merge across records a company depends on is a different exercise from a merge across seeded demonstration data
**Working assumption.** None. Every candidate rule loses something, and which loss is acceptable depends on what the duplicates actually hold in the estate being repaired — which is a measurement nobody has taken. Recording an assumption here would be recording a preference as a finding.
**Impact if wrong.** High and irreversible in one direction. There is no DELETE grant on `asset`, so a merge closes the loser rather than removing it and the trail survives — but findings, coverage and edges moved to the wrong survivor are moved, and moving them back is a second migration over data a third one has since touched.
**Status.** `Open`

---

## Escalation

A question past its required-by date with status `Open` MUST be escalated to the corpus owner and recorded here. A blocking question that is silently late is how documentation projects fail without anyone deciding to fail (DOC-00 §14.3).

| ID | Escalated | To | Outcome |
|---|---|---|---|
| OQ-015 | 2026-08-04 | Corpus owner | Open. Blocks implementation; an order-of-magnitude figure is sufficient |
| OQ-026 | 2026-08-04 | Corpus owner | Open. Blocks implementation; three credential paths in build block 1 depend on it |

Per DOC-00 §14.3, an open question past its required-by date is escalated and recorded. Both were carried on working assumptions through authoring, which was correct; neither can be carried through implementation, because both produce artifacts that are expensive or impossible to change afterwards.

## Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.3.0 | 2026-08-25 | Chief Software Architect | Added `OQ-029`, raised by ADR-064. That decision stops duplicate assets being created and deliberately does not repair the ones already recorded, because the repair needs a rule for which of two rows survives when both carry findings, coverage and graph edges — and every candidate rule loses something. Recorded with no working assumption for the same reason as `OQ-028`: the choice depends on what the duplicates actually hold, which nobody has measured. | Pending |
| 1.2.0 | 2026-08-14 | Chief Software Architect | Added `OQ-028`, raised by ADR-060 rather than by review: the RUNTIME identity class declares an input that a DAST result against a URL does not carry, and the parser holds such results in quarantine instead of substituting a value. Recorded with NO working assumption, which is a departure from every other entry here and is deliberate — the other assumptions bind figures and products, this one would bind what identity means for a class of finding, and DOC-03 §8.5 is explicit that the first version of an identity rule is the least informed. | Pending |
| 0.1.0 | 2026-08-04 | Staff Product Manager | Seeded with three ratified assumptions and seven open questions arising from DOC-01. |
| 1.0.0 | 2026-08-04 | Staff Product Manager | Completed at corpus baseline. Records that all 26 documents were authored without any question blocking authoring, each carried on a documented working assumption marked at its point of use. Reclassifies OQ-015 and OQ-026 from blocking documents to blocking implementation, with their consequence restated: partition counts are irreversible after production data, and three credential paths in the first build block depend on the vault decision. Both escalated per DOC-00 §14.3. States what the assumptions cost — every affected requirement is structurally sound and numerically provisional, and no document requires restructuring on any answer. | Pending |
