# Corpus status

*Moved out of `README.md` when that file became the product's front page. Nothing here changed; the
status table, the register figures and the seven recorded corrections are the same document, and
`CLAUDE.md` and `README.md` point at this path. It lives beside them rather than in `docs/`, because `docs/` is the 26 specification documents and the validator holds every file there to the front-matter convention of DOC-00 Appendix E — which this is not and should not pretend to be.*


Specification for an **AI-native Application Security Posture Management platform**. 26 documents, complete and internally consistent.

**This is a documentation repository. No implementation code belongs here.**

---

## Status — complete

All 26 documents are authored and at `In review`. **239,402 words. 1,177 active requirements** across ten classes, plus 6 accepted residual risk records and 3 superseded records.

| # | Document | Version | Status | Words |
|---|---|---|---|---|
| 00 | Conventions And Traceability | <semver> | ✅ Not started | Drafting | In review | Approved | Superseded | Withdrawn | 11,892 |
| 01 | Product Requirement | 1.0.0 | ✅ In review | 55,947 |
| 02 | System Architecture | 1.0.0 | ✅ In review | 7,541 |
| 03 | Domain Model | 1.0.0 | ✅ In review | 17,919 |
| 04 | Database Design | 1.0.0 | ✅ In review | 27,808 |
| 05 | Api Specification | 1.0.0 | ✅ In review | 7,856 |
| 06 | Security Requirement | 1.0.0 | ✅ In review | 5,532 |
| 07 | Rbac Permission | 1.0.0 | ✅ In review | 8,877 |
| 08 | Ui Ux Guideline | 1.0.0 | ✅ In review | 5,242 |
| 09 | Workflow | 1.0.0 | ✅ In review | 6,851 |
| 10 | Ai Requirement | 1.0.0 | ✅ In review | 5,070 |
| 11 | Import Export | 1.0.0 | ✅ In review | 5,143 |
| 12 | Reporting | 1.0.0 | ✅ In review | 4,907 |
| 13 | Notification | 1.0.0 | ✅ In review | 3,854 |
| 14 | Audit Logging | 1.0.0 | ✅ In review | 4,268 |
| 15 | Deployment | 1.0.0 | ✅ In review | 5,429 |
| 16 | Testing Strategy | 1.0.0 | ✅ In review | 6,841 |
| 17 | Product Roadmap | 1.0.0 | ✅ In review | 4,535 |
| 18 | Glossary | 1.0.0 | ✅ In review | 2,922 |
| 19 | Decision Log | 1.0.0 | ✅ In review | 7,891 |
| 20 | Open Questions | 1.0.0 | ✅ In review | 1,511 |
| 21 | Integration And Connector Framework | 1.0.0 | ✅ In review | 4,497 |
| 22 | Sbom And Vulnerability Matching | 1.0.0 | ✅ In review | 5,574 |
| 24 | Tenancy And Isolation Model | 1.0.0 | ✅ In review | 7,704 |
| 26 | Platform Threat Model | 1.0.0 | ✅ In review | 6,305 |
| 28 | Risk And Scoring Methodology | 1.0.0 | ✅ In review | 7,486 |

Corpus validation passes with no findings. DOC-23, DOC-25, and DOC-27 are intentionally unassigned; numbering is permanent and never reused, so adding a document never requires renumbering.

---

## Requirements

| Class | Count |
|---|---|
| `CFG` | 30 |
| `CON` | 91 |
| `INT` | 13 |
| `LIC` | 10 |
| `NFR` | 38 |
| `OPS` | 50 |
| `PRD` | 686 |
| `SEC` | 224 |
| `TST` | 35 |

Priority distribution over active requirements, measured from the register: **1,113 MUST_HAVE**, 61 SHOULD_HAVE, 3 COULD_HAVE.

Every requirement carries a rationale explaining *why* rather than restating *what*, a priority, a verification method, and either a domain-level or requirement-level extensibility statement.

---

## Getting started

```bash
cat CLAUDE.md                              # project instructions — read first
cat docs/00_CONVENTIONS_AND_TRACEABILITY.md

python3 tools/validate_corpus.py           # ID format, duplicates, dangling refs, prohibited strings
python3 tools/generate_register.py         # rebuild _traceability/requirements.csv
```

Python 3.8+ standard library only. No dependencies.

### Using with Claude Code

`CLAUDE.md` is the project instruction file. It carries the binding conventions, the 43 ratified decisions, the ten product principles, the open questions with their working assumptions, and the prohibited patterns. Its purpose is to prevent convention drift: a corpus of this size becomes internally inconsistent faster than any reader can detect.

To implement a module:

```
Read CLAUDE.md and docs/00_CONVENTIONS_AND_TRACEABILITY.md.
Then read docs/03_DOMAIN_MODEL.md §8, docs/04_DATABASE_DESIGN.md §11.3,
docs/07_RBAC_PERMISSION.md §5 (ast.* permissions), and docs/16_TESTING_STRATEGY.md §4.
Implement the asset-inventory module with its 24 INV-AST invariants and their tests.
```

**Two decisions are required before implementation begins** (DOC-15 §2): the operational store and the application toolchain, each carrying a disqualifying constraint. And **two open questions block implementation** (DOC-20): OQ-015 sizing, because hash partition counts are irreversible after production data; and OQ-026 the secrets vault, because three credential paths in the first build block depend on it.

---

## Layout

```
.
├── CLAUDE.md                    Project instructions — read first
├── README.md                    This file
├── docs/                        The 26 documents
├── _traceability/
│   ├── requirements.csv         Generated register — 1,186 rows, no gaps
│   └── matrix.csv               Requirement→test matrix, populated during implementation
└── tools/
    ├── generate_register.py     Rebuild the register from docs/
    └── validate_corpus.py       Consistency checks
```

---

## The decisions that shape everything

43 decisions are recorded in `docs/19_DECISION_LOG.md`, each with the cost it accepts and a conditional revisit trigger. Four determine the shape of the rest.

**ADR-001 — two orthogonal structures, not one hierarchy.** The original brief specified a containment chain in which a finding contains a repository and a repository contains a service. Neither is true. The corpus works from an organization tree for accountability and an asset graph for technical reality, joined by an ownership edge. Without this, *which internet-facing systems contain this vulnerable component* is unanswerable — and that question is why the product exists.

**ADR-024 — the platform never touches source code.** No fetch, no clone, no persistence, no Git credentials. SBOMs arrive by API. The consequence is stated rather than hidden: **the platform is blind between submissions**, so coverage and freshness are first-class metrics and every derived figure carries the coverage supporting it. A dashboard that is green because data is missing rather than because a system is secure is the failure this governs.

**ADR-027 — nothing organization-specific is hardcoded.** Roles, hierarchy depth, node names, workflows, custom fields, taxonomies, and vocabulary are tenant-configured data. The operative half is the prohibition on role-name branching in code, enforced by static analysis as a build gate rather than by review — because the shortcut is faster to write, reads naturally, and stays invisible until a customer's structure breaks it.

**ADR-028 — the platform replaces the incumbent issue tracker for security work.** This is the differentiator: competing tools hand mobilization to an external tracker and lose the loop. It is also the largest adoption risk, because if collaboration is materially worse than what teams left, they record states here and converse elsewhere — and every reporting capability then produces confident output over an incomplete work record.

---

## What remains

| Item | Owner |
|---|---|
| Answer OQ-015 and OQ-026 | Both block implementation |
| Select the operational store and toolchain against their disqualifying constraints | DOC-15 §2, recorded as ADRs |
| Technical review of each document by a Principal Engineer who did not author it | DOC-00 §18.3 |
| Security review of the fourteen documents requiring it | DOC-00 §18.3 |
| Populate `_traceability/matrix.csv` as design and tests are produced | DOC-16 §16 |
| Baseline the corpus as `Baseline 1.0` on approval | DOC-00 §17.2 |

---

## Corrections recorded during authoring

Seven errors were found and corrected in the documents rather than fixed silently. They are recorded because the standard for handling mistakes is part of the corpus.

1. **Requirement count overstated** as 349 against an actual 332 in DOC-01 §10.
2. **Priority distribution estimated** rather than measured; corrected from the generated register.
3. **A code collision overstated.** The `RISK`/`RSK` overlap was raised as a parser ambiguity; because the scheme is positional it is not one. The rename proceeded on readability grounds, and DOC-01 §17.4 records that the original diagnosis was wrong — and that `CON` has the same property and is deliberately not renamed.
4. **Real requirement identifiers used in DOC-00's illustrative examples**, causing the register generator to record the example in place of the real requirement. Found by the validator on its first full run; fixed by reserving the `XMP` domain code.
5. **Twenty-seven requirements invisible to the register** in DOC-06, because their identifiers were in the last table column and four used a heading depth the generator did not recognize. Found by the register, not by review.
6. **An unregistered class code** `DEP` used in DOC-15. Found by the validator; forty identifiers renumbered into the registered `OPS` class.
7. **An inconsistency in DOC-00 itself** — §7.1 requires an extensibility attribute that §7.3's compact form omits. Resolved by the domain-level convention of DOC-01 LC-05 and recorded as a required patch.

8. **A drifting count in DOC-19's change history.** The "expanded records" figure is a running total
   of ADRs. `ADR-057`, `ADR-058` and `ADR-059` were added with complete records and no change-history
   row, so the total silently fell three behind; the next two entries carried the error forward, one
   of them by copying the previous row's arithmetic rather than counting the file. Corrected at 1.4.0
   by counting — 59 expanded records, with five numbers cross-referenced rather than expanded — and
   the superseded figure is left visible in the row that carried it. Found by counting the headings
   while adding three records, not by review.

Items 4, 5, and 6 were found by tooling rather than by reading. That is the argument for the tooling.
Item 8 was found by not trusting arithmetic over a number that could be counted.
