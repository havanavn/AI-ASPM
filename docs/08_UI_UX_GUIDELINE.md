---
document_id:    DOC-08
title:          UI/UX Guideline
product:        AI-native Application Security Posture Management Platform (AI ASPM)
version:        1.0.0
status:         In review
owner:          Senior UX Designer
authors:        [Senior UX Designer, Staff Product Manager, Chief Software Architect]
reviewers:      []
last_updated:   2026-08-04
tier:           6
prerequisites:  [DOC-00, DOC-01, DOC-07, DOC-09, DOC-12]
depends_on:     [DOC-00, DOC-01, DOC-05, DOC-06, DOC-07, DOC-09, DOC-10, DOC-12, DOC-13]
supersedes:     null
adrs_relied_on: [ADR-006, ADR-027, ADR-028]
open_questions: []
requirement_domains: [UIX, INT]
security_review_required: false
---

# 08 — UI/UX Guideline

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope) · 2. [Design Language](#2-design-language) · 3. [Design Tokens](#3-design-tokens) · 4. [Typography and Density](#4-typography-and-density) · 5. [Colour and Theming](#5-colour-and-theming) · 6. [Information Architecture](#6-information-architecture) · 7. [Interaction Model](#7-interaction-model) · 8. [Component Specifications](#8-component-specifications) · 9. [State Presentation](#9-state-presentation) · 10. [Honesty Surfaces](#10-honesty-surfaces) · 11. [Accessibility](#11-accessibility) · 12. [Internationalization](#12-internationalization) · 13. [Performance](#13-performance) · 14. [Requirements](#14-requirements) · 15. [Closing](#15-closing)

---

## 1. Purpose and Scope

**In scope.** The design language resolution required by ADR-006; design tokens; typography and density; colour and theming; information architecture for a configurable hierarchy; the keyboard-first interaction model; component specifications; state presentation; the honesty surfaces owed by other documents; accessibility conformance; internationalization architecture; performance budgets.

**Out of scope.** Visual design artifacts, which are produced against this specification; measure definitions (DOC-12 and its upstreams); component implementation.

**LC-01.** Requirements are `PRD-UIX-nnn` for interface requirements and `INT-UIX-nnn` for internationalization and accessibility, both from `001`.

**LC-02.** §10 collects presentation requirements owed to this document by DOC-10, DOC-12, DOC-13, and DOC-07. They are gathered rather than restated: each is referenced with the interface obligation it creates.

---

## 2. Design Language

### 2.1 The ADR-006 resolution

The original brief cited two opposite design languages: minimal, low-chrome, keyboard-first on one side; dense, nested, panel-heavy on the other. Following both produces incoherence.

**Resolution.** **Interaction density and keyboard-first model from the first tradition; information architecture rigour from the second.**

| Adopted | Rejected |
|---|---|
| High information density without visual weight | Heavy chrome, nested panels, deep modal stacks |
| Keyboard as the primary input for practitioners | Mouse-first interaction as the assumed default |
| Command interface for navigation and action | A navigation tree as the only way to reach anything |
| Instant local response with optimistic update | Full-page transitions between related views |
| Rigorous hierarchy navigation for deep configurable trees | Flat navigation assuming a shallow structure |
| Explicit scope indication at all times | Ambient or implied scope |

**Why density without weight is the harder problem here.** This platform presents deep hierarchies of unbounded configurable depth (ADR-027) alongside high-cardinality lists. A low-chrome idiom makes deep-hierarchy navigation genuinely harder to design than a panel-heavy one, and DOC-02 accepted that this needs more design iteration.

### 2.2 Principles

| # | Principle | Consequence |
|---|---|---|
| U1 | **Scope is always visible** | The caller's current scope root and position are shown at all times; a user must never be uncertain which slice of the organization they are looking at |
| U2 | **Density serves the practitioner; clarity serves the occasional user** | The two populations differ by orders of magnitude in both size and training (PP-7). Practitioner surfaces are dense; intake and engineering surfaces are explanatory |
| U3 | **Absence is stated, never implied** | An empty result, an unmeasured asset, a withheld field, and an unavailable capability each look different from each other and from a loading state |
| U4 | **A figure never appears without its qualification** | Coverage and freshness travel with the measure (§10) |
| U5 | **Keyboard reaches everything** | Every action available by pointer is available by keyboard; no capability is pointer-only |
| U6 | **The interface never blocks on a slow dependency** | Model providers, search, and read models degrade in place rather than preventing the surrounding view from rendering |

---

## 3. Design Tokens

All visual values are tokens. No component carries a literal colour, spacing, radius, or duration.

| Category | Tokens |
|---|---|
| Colour — semantic | `surface`, `surface-raised`, `surface-sunken`, `border`, `border-strong`, `text`, `text-muted`, `text-inverse`, `accent`, `accent-muted`, `focus` |
| Colour — status | `status-critical`, `status-high`, `status-medium`, `status-low`, `status-info`, `status-neutral`, `status-success`, `status-warning`, `status-unknown` |
| Colour — data | `data-1` … `data-8`, ordered and distinguishable in monochrome and under common colour vision deficiencies |
| Spacing | `space-0` … `space-9`, 4-pixel base |
| Radius | `radius-none`, `-sm`, `-md`, `-lg`, `-full` |
| Elevation | `elevation-0` … `elevation-3` |
| Motion | `duration-instant` (0 ms), `-fast` (120 ms), `-base` (200 ms), `-slow` (320 ms); `easing-standard`, `-decelerate`, `-accelerate` |
| Typography | §4 |
| Density | `density-compact`, `-default`, `-comfortable` |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-UIX-001` | Every visual value MUST be a token. Literal values MUST NOT appear in components. | Theming, density, and the high-contrast mode of `INT-UIX-004` are all token substitutions. A literal breaks all three at once, and the breakage is only visible in the mode nobody tests. | M | AT, CR |
| `PRD-UIX-002` | Status tokens MUST be distinguishable without colour, and the data palette MUST be ordered and distinguishable in monochrome and under deuteranopia, protanopia, and tritanopia. | `INT-UIX-002`. Severity and status are the platform's most consequential encodings, and a colour-only encoding fails for a material proportion of users and in every printed or monochrome review. | M | AT, MT |
| `PRD-UIX-003` | `duration-instant` MUST be used for any transition on a surface the user is actively working in, and no animation MUST delay the availability of an interactive element. | Practitioners work in these surfaces for hours. Animation that delays interaction is the difference between a tool that feels fast and one that feels like an obligation. | M | MT |

---

## 4. Typography and Density

**Type scale.** Seven steps on a 1.2 ratio from a 14-pixel base, with a separate monospace scale for identifiers, code, versions, and payload fragments.

**Line length** bounded to roughly 80 characters for prose; tabular content is unbounded.

**Density scales** by token, affecting row height, control height, and vertical rhythm — not font size. Font size reduction below the base is prohibited: it degrades legibility, and the density gain is smaller than the row-height gain.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-UIX-004` | Density MUST be user-selectable and MUST affect spacing and control size only, never font size below the base. | Practitioners want more rows; occasional users want more room. Reducing font size to achieve density degrades legibility for a smaller gain than row-height reduction provides. | M | DM |
| `PRD-UIX-005` | Identifiers, versions, package identifiers, hashes, and payload fragments MUST be rendered in a monospace face with unambiguous zero, one, and letter forms. | These values are read character by character, transcribed, and compared. A proportional face makes `l`, `1`, `I` and `0`, `O` indistinguishable, and a mis-transcribed package identifier produces a wrong answer that looks right. | M | MT |

---

## 5. Colour and Theming

Light and dark themes as token sets. Dark is not an inversion: surface and elevation relationships are defined per theme.

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-UIX-006` | Both themes MUST meet the contrast requirements of `INT-UIX-001` independently. Neither MUST be derived by inverting the other. | Inverted palettes fail contrast in predictable places — muted text on raised surfaces above all — and the failure is in the theme that receives less design attention. | M | AT |
| `PRD-UIX-007` | Theme selection MUST follow the operating system preference by default and MUST be overridable per user, with the choice persisted. | | M | DM |
| `PRD-UIX-008` | Status colour MUST be consistent across every surface: a severity, a service level state, or a coverage confidence MUST use the same token everywhere it appears. | Inconsistent status colour forces re-learning per screen, and in a security tool the cost of misreading a severity is high enough that consistency outweighs any local visual preference. | M | CR |

---

## 6. Information Architecture

### 6.1 The problem

The hierarchy is tenant-configured with unbounded depth and tenant-named levels (ADR-027). The interface cannot assume four levels, cannot assume level names, and cannot assume a user starts at the top.

### 6.2 Navigation model

Three coexisting mechanisms, because no single one serves a configurable deep hierarchy:

| Mechanism | Serves |
|---|---|
| **Scope selector** | Establishing and changing position in the hierarchy. Shows the caller's scope root and current position; searchable by node name; shows only authorized nodes |
| **Command interface** | Direct navigation to any object by code or title, and direct invocation of any action, without traversing the hierarchy |
| **Section navigation** | The functional areas: dashboards, findings, assessments, work, assets, knowledge, administration |

**Breadcrumbs** show the path from the caller's scope root, never from a tenant root the caller cannot see (`PRD-DSH-022`).

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-UIX-009` | The interface MUST NOT assume a fixed hierarchy depth or fixed level names, and MUST render tenant-configured node type labels including vocabulary overrides. | ADR-027. An interface with "Business Unit" as a hardcoded heading is unusable by a tenant whose structure differs, which is every tenant but one. | M | AT |
| `PRD-UIX-010` | Breadcrumbs MUST begin at the caller's scope root and MUST NOT display or imply ancestors outside their scope. | Displaying an unreachable ancestor discloses the organization's shape above the caller (`PRD-DSH-022`). | M | AT, PT |
| `PRD-UIX-011` | The current scope MUST be visible on every surface that presents scoped data. | U1. A user uncertain which slice they are viewing will misread every figure on the page, and in a multi-business-unit platform that uncertainty is the default state without explicit indication. | M | MT |
| `PRD-UIX-012` | Every object MUST be reachable by its human-facing code through the command interface. | These codes are quoted in conversation, email, and tickets. Requiring hierarchy traversal to reach a code someone just read out is the single most common navigation frustration in tools of this kind. | M | AT |

---

## 7. Interaction Model

### 7.1 Keyboard-first

| Element | Requirement |
|---|---|
| Command interface | Single shortcut from anywhere; searches objects, actions, and navigation targets |
| List navigation | Move, select, multi-select, and act without a pointer |
| Transitions | Available from the keyboard on a focused item |
| Form submission | Keyboard submit with an explicit confirm for destructive actions |
| Modal dismissal | Escape, with unsaved-content confirmation |
| Focus | Always visible, never suppressed, restored on modal close |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-UIX-013` | Every action available by pointer MUST be available by keyboard. A pointer-only capability MUST NOT exist. | U5, and `INT-UIX-003`. This serves both the chosen interaction model and accessibility conformance, which is why it is absolute rather than aspirational. | M | AT, MT |
| `PRD-UIX-014` | Destructive and irreversible actions MUST require an explicit confirmation that is not the default focus target, and MUST state what will happen rather than asking for generic confirmation. | "Are you sure?" is dismissed reflexively. "This will close 47 findings as not applicable" is read. The bulk operations of this platform make the distinction consequential. | M | MT |
| `PRD-UIX-015` | Optimistic update MUST be used for state changes with a visible reconciliation on failure, and MUST NOT be used where the server may reject on a guard the client cannot evaluate. | Optimistic update on a guarded transition shows success then reverts, which reads as the platform being unreliable. Guards are server-side by design (`PRD-WRK-033`), so the client cannot predict them. | M | MT |

### 7.2 Bulk interaction

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-UIX-016` | Bulk selection MUST state the count and scope of what is selected, MUST distinguish "all on this page" from "all matching the filter", and MUST show the per-item outcome after execution. | The two selections differ by orders of magnitude, and conflating them is how a triage of fifty becomes a triage of fifty thousand. Per-item outcome is required because bulk operations are partial-success (`PRD-API-029`). | M | MT |
| `PRD-UIX-017` | Where a bulk operation is partially denied, the interface MUST show which items were denied and why, and MUST NOT present the operation as wholly successful or wholly failed. | Per-item permission evaluation means partial denial is normal (`INV-WRK-12`). Presenting it as success hides that some items were not acted on. | M | AT |

---

## 8. Component Specifications

Specified by behaviour and obligation rather than appearance.

| Component | Obligations |
|---|---|
| **Data table** | Server-side pagination, sort, column configuration, saved views, keyboard navigation, bulk selection, row density, per-cell truncation with full value on inspection, empty and loading states, export |
| **Scope selector** | Authorized nodes only; searchable; shows current position; persists selection per session |
| **Measure tile** | Value, band, trend, **coverage indicator**, drill-through target. Coverage is not optional (§10) |
| **Chart** | Colour-independent encoding, keyboard-accessible data, tabular alternative, drill-through, coverage statement, normalization statement where comparative |
| **Finding detail** | Impacts per asset, enrichment with freshness, score with factor breakdown on request, evidence references, work links, timeline, transitions available to the viewer |
| **Work item detail** | Fields, custom fields, checklist, links, watchers, unified timeline, comment composer with autosave, transitions |
| **Comment thread** | Constrained rich text, mentions with scope-filtered autocomplete, edit history, redaction marker, **inbound-attribution marker**, reactions, read state |
| **Intake form** | Multi-step with save-draft, inline validation, derived-field display, readiness attestation, explanatory guidance for occasional users |
| **Credential field** | Reference input only; never accepts a value; masked display; reveal as a separate audited action |
| **Filter bar** | Typed controls, shareable state, saved views, active-filter summary |
| **Notification centre** | Read state, grouping by object, act-from-notification, volume-reduction control |
| **Timeline** | Interleaved transitions, comments, attachments, automation, AI suggestions in one chronology with actor type distinguished |
| **Empty state** | What belongs here and the next action; never only "No data" |
| **Degraded state** | Which capability is unavailable, why, and what remains available |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-UIX-018` | The credential field MUST NOT accept a plaintext value. It MUST accept a reference and MUST reject a value with an explanation. | The interface is where an inline credential would enter (`PRD-API-033`). Rejecting at the field with an explanation is the only place the user learns why. | M | AT |
| `PRD-UIX-019` | The timeline MUST distinguish actor types — human, service, automation, platform — visually and MUST identify the automation rule where one acted. | An entry attributed indistinguishably makes an automated change look like a human decision, which matters when someone is reconstructing why an item is in its current state. | M | MT |
| `PRD-UIX-020` | Comments attributed through inbound email MUST be visibly marked as such. | Inbound-attributed content carries weaker identity assurance than authenticated content (`PRD-NTF-039`), and a reader will not notice the distinction unless the interface makes it. | M | MT |
| `PRD-UIX-021` | Truncated values MUST be recoverable in full without navigation, and truncation MUST NOT be applied to identifiers, versions, or package identifiers. | These values are meaningless truncated, and a truncated package identifier that looks complete produces a wrong conclusion. | M | MT |

---

## 9. State Presentation

U3. Six states, each visually distinct from the others.

| State | Presentation |
|---|---|
| **Loading** | Skeleton matching the eventual layout; never a spinner replacing content that will appear in place |
| **Empty — no data yet** | What belongs here and the action that creates it |
| **Empty — filtered out** | The active filters and a way to clear them. Distinct from having no data |
| **Unmeasured** | Explicitly unmeasured, with the action that would measure it. **Never rendered as zero** |
| **Withheld** | The field is present but not shown to this viewer. Only where absence would itself be confusing; otherwise the field is **absent** (`SEC-AUZ-022`) |
| **Degraded** | The capability is unavailable, with the reason and what remains |
| **Error** | What happened, why, what to do. Never a code or a trace |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-UIX-022` | Unmeasured MUST be visually distinct from zero and from empty. | Rendering unmeasured as zero is the interface-layer expression of the PP-1 failure the whole corpus guards against: a favourable figure produced by absent data. | M | AT, MT |
| `PRD-UIX-023` | A restricted field the viewer may not see MUST be absent rather than masked, except where absence would misrepresent the object, in which case a withheld indicator MUST be shown without confirming a value exists. | A masked placeholder confirms the field has a value, which for a secret finding confirms a credential exists at that location (`SEC-AUZ-022`). The exception is narrow and must be justified per field. | M | AT, MT |
| `PRD-UIX-024` | A degraded capability MUST state which capability, why, and what remains available. An empty region MUST NOT be rendered in its place. | An empty section in an executive report reads as "nothing to report" (`PRD-AIC-048`, PP-9). | M | MT |
| `PRD-UIX-025` | Error presentation MUST NOT include stack traces, framework or dependency versions, internal hostnames, or query fragments. | Each is reconnaissance (`SEC-SEC-068`), and error surfaces are among the platform's least-reviewed output paths. | M | AT, PT |

---

## 10. Honesty Surfaces

Presentation obligations owed to this document by others, gathered so they are implemented as a coherent set rather than discovered one at a time.

| Obligation | Owed by | Interface requirement |
|---|---|---|
| Coverage and freshness with every measure | `PRD-DSH-024` | Present on the measure tile and chart, prominent enough to be read, not so heavy that it is removed |
| Coverage gap as the primary statement at insufficient confidence | `PRD-DSH-025` | The measure is not rendered as a figure; the gap is |
| Improvement distinguished from lost coverage | `PRD-DSH-026` | A trend improvement carries its cause |
| Unmeasured assets visible | `PRD-DSH-027` | §9 |
| Normalization statement on comparison | `PRD-DSH-035` | Adjacent to the comparison, not in a footnote |
| Aggregation basis label | `PRD-DSH-018` | On every aggregate presentation |
| Utilization against a target band with the reason | `PRD-DSH-033` | The band and its rationale are both visible |
| Individual metrics purpose statement | `PRD-DSH-034` | Where per-person data is presented |
| Generated content labelled | `PRD-AIC-036` | Distinct from authored content, surviving export |
| AI unavailability stated | `PRD-AIC-048` | §9 degraded state |
| Estimation confidence where calibration is thin | `PRD-RSK-040` | On the estimate |
| Intelligence staleness | `PRD-VUL-008` | On findings whose enrichment is stale |
| Inbound-attributed comments marked | `PRD-NTF-039` | `PRD-UIX-020` |
| Migrated records marked | `PRD-ING-049` | Marker survives every presentation |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-UIX-026` | The honesty surfaces of §10 MUST NOT be suppressible by theme, density, template, or user preference. | These are the mechanisms by which the platform avoids producing confident wrong output. Any of them being optional means it is removed by whoever wants a cleaner view, and `PRD-DSH-042` already prohibits template suppression — this extends the prohibition to every other suppression path. | M | AT, CR |
| `PRD-UIX-027` | Coverage indication MUST be designed to be read rather than tolerated, and its visual weight MUST be validated with the executive audience before release. | This is the requirement most likely to be dropped as clutter during visual design. Validating with the audience that most needs it is the check against that. | M | MT |

**On `PRD-UIX-026`.** It is the interface counterpart of `PRD-DSH-042` and exists because the suppression paths differ: a template removes a section, a density setting hides a secondary line, a user preference turns off "extra detail". All three would defeat the same mechanism.

---

## 11. Accessibility

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `INT-UIX-001` | The interface MUST conform to WCAG 2.2 Level AA, verified by automated and manual testing, with conformance documented per success criterion. | A procurement gate for government and large enterprise buyers, failing which removes the platform from consideration before any capability is evaluated — and the correct outcome independently of procurement. Per-criterion documentation is what makes the claim verifiable. | M | AT, MT |
| `INT-UIX-002` | Information MUST NOT be conveyed by colour alone, in any surface, chart, or diagram. | Severity and status are the platform's most consequential encodings. A colour-only encoding fails for a material proportion of users and in every printed or monochrome review — including procurement and audit review. | M | AT, MT |
| `INT-UIX-003` | Every workflow MUST be completable by keyboard alone, with visible focus, logical order, and no keyboard trap. | `PRD-UIX-013`. The chosen interaction model and accessibility conformance require the same thing. | M | MT |
| `INT-UIX-004` | The interface MUST support a high-contrast mode, MUST respect reduced-motion preference, and MUST remain usable at 200% zoom and 400% text scaling without loss of function or content. | Each is a WCAG 2.2 AA criterion and each is a token or layout property rather than a feature, which is why `PRD-UIX-001` matters. | M | AT, MT |
| `INT-UIX-005` | Dynamic content changes MUST be announced to assistive technology, and asynchronous completion MUST NOT be signalled by a visual change alone. | The platform's surfaces update from background work — projections, job completion, notifications. A change nobody is told about is a change a screen reader user does not receive. | M | MT |
| `INT-UIX-006` | Charts MUST have a keyboard-accessible tabular alternative conveying the same information. | A chart is inaccessible by construction to a screen reader user. The tabular alternative is also what makes charts usable in export and in monochrome. | M | MT |
| `INT-UIX-007` | Form errors MUST be associated with their field, announced, and MUST NOT rely on position or colour. | Intake forms are used by the largest and least-trained population under time pressure (PP-7). An unassociated error is an unresolvable one. | M | AT, MT |

---

## 12. Internationalization

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `INT-UIX-008` | Every user-facing string MUST be externalized with ICU message formatting for plurals, gender, and ordinals. String concatenation to build a sentence MUST NOT be used. | Concatenation produces sentences that cannot be translated correctly because word order differs by language. ICU is required rather than simple substitution because plural rules differ in ways a substitution cannot express. | M | AT, CR |
| `INT-UIX-009` | The interface MUST pass pseudo-localization testing without layout failure, string truncation, or untranslated string leakage. | Pseudo-localization is the only test that finds hardcoded strings and layout assumptions before a real locale is added, and it does so without needing a translation. | M | AT |
| `INT-UIX-010` | Dates, times, numbers, and lists MUST be formatted per the viewer's locale, and instants MUST be rendered in the viewer's timezone with the zone indicated where ambiguity is possible. | `NFR-INT-004`. A deadline rendered without a zone is misread by anyone not in the author's zone, and this platform's deadlines carry consequences. | M | AT |
| `INT-UIX-011` | Layout MUST support right-to-left direction, and directional iconography and interaction MUST mirror. | Retrofitting bidirectional support touches every layout primitive. Building for it costs little; adding it later is a rewrite of the layer everything depends on. | M | AT |
| `INT-UIX-012` | Tenant-authored content MUST render correctly in any script, and MUST NOT assume Latin script in display, sorting, search, or truncation. | Tenant data is authored in the tenant's language. Truncation in particular fails on scripts where a character boundary is not a byte boundary. | M | AT |
| `INT-UIX-013` | Tenant vocabulary overrides MUST apply everywhere the overridden term appears, including navigation, tables, charts, reports, notifications, and exports. | ADR-027. A tenant that renames business units to P&Ls seeing the platform's term in half the interface is a persistent signal the tool was not configured for them. | M | AT |

**Source locale is English; Vietnamese is the first target locale** (DOC-01 §4.3). The architecture is required from v1 even if only English ships, because retrofitting touches every component, template, export, and date comparison.

---

## 13. Performance

| Surface | Budget | Source |
|---|---|---|
| Interactive readiness | 2.5 s at p95 on the reference client and network profile | `NFR-UIX-001` |
| Dashboard initial viewport | 1.5 s p95 server-side | `NFR-DSH-001` |
| Filter application and drill-down | 800 ms p95 | `NFR-DSH-002` |
| Finding list | 600 ms p95 | `NFR-VUL-001` |
| Work item detail with timeline | 700 ms p95 | `NFR-WRK-001` |
| Comment submission acknowledgement | 400 ms p95 | `NFR-WRK-001` |
| Search first results | 1.0 s p95 | `NFR-WRK-002` |
| Usable at 200 ms round-trip latency | Required | `NFR-UIX-001` |

| ID | Statement | Rationale | Pri | V |
|---|---|---|---|---|
| `PRD-UIX-028` | Views MUST render progressively, and a slow region MUST NOT delay the surrounding view. | U6. Model providers, search, and read models each have independent latency, and one slow region blocking the page makes the whole platform feel unreliable. | M | AT |
| `PRD-UIX-029` | The interface MUST remain usable at 200 ms round-trip latency, and interaction MUST NOT require a round trip where local state suffices. | The platform is used across regions over corporate networks with material latency. An interface tuned only for low latency is unusable for a proportion of its users. | M | MT |
| `PRD-UIX-030` | Responses containing `CONFIDENTIAL` or `RESTRICTED` data MUST NOT be cached by the browser or an intermediary. | `SEC-SEC-055`. Shared workstations are common in operations contexts, and a cached finding list is a disclosure after logout. | M | AT |
| `PRD-UIX-031` | Tablet-class viewports MUST support review and approval workflows. Full practitioner workflows are not required below desktop. | Approvals occur away from a desk, and an approval waiting for the approver to reach a desktop is the most common cause of stalled approval workflows. Practitioner work is desk work (DF-06). | S | MT |

---

## 14. Requirements

Thirty-one `PRD-UIX` and thirteen `INT-UIX`; forty-four total, all `MUST_HAVE` except `PRD-UIX-031`.

| Group | IDs | Count |
|---|---|---|
| Tokens | `PRD-UIX-001` – `003` | 3 |
| Typography and density | `004` – `005` | 2 |
| Theming | `006` – `008` | 3 |
| Information architecture | `009` – `012` | 4 |
| Interaction | `013` – `017` | 5 |
| Components | `018` – `021` | 4 |
| State presentation | `022` – `025` | 4 |
| Honesty surfaces | `026` – `027` | 2 |
| Performance | `028` – `031` | 4 |
| Accessibility | `INT-UIX-001` – `007` | 7 |
| Internationalization | `INT-UIX-008` – `013` | 6 |

Satisfies `NFR-UIX-001` – `003`, `NFR-INT-001` – `004`, and the presentation obligations of §10.

---

## 15. Closing

### 15.1 Extensibility

Tokens make theming, density, and high-contrast mode substitutions rather than variants. Components are specified by obligation, so a visual redesign does not change what they must do. Tenant configurability — node type labels, vocabulary, custom fields, workflow states — is rendered from configuration, so a tenant's structural change requires no interface change.

**Deliberate rigidity.** No literal visual values (`PRD-UIX-001`); no colour-only encoding (`INT-UIX-002`); no pointer-only capability (`PRD-UIX-013`); no plaintext credential field (`PRD-UIX-018`); honesty surfaces not suppressible (`PRD-UIX-026`).

**Known extension costs.** Deep-hierarchy navigation in a low-chrome idiom requires more design iteration than a panel-heavy one would (§2.1), and it is the area most likely to need revision after usability testing. Bidirectional support constrains every layout primitive from the outset. Each new locale requires review of layouts where text expansion is significant.

### 15.2 Security considerations

| Risk | Control |
|---|---|
| Inline credential entry | Field rejects values (`PRD-UIX-018`) |
| Masked field confirming a value exists | Absence rather than masking (`PRD-UIX-023`) |
| Reconnaissance through error presentation | No traces or versions (`PRD-UIX-025`) |
| Out-of-scope structure disclosure through breadcrumbs | Root-relative (`PRD-UIX-010`) |
| User enumeration through mention autocomplete | Scope-filtered (`PRD-API-040`) |
| Disclosure after logout on a shared workstation | No caching of confidential responses (`PRD-UIX-030`) |
| Bulk action wider than intended | Selection scope stated (`PRD-UIX-016`) |
| Content Security Policy prohibits inline script and style | Constrains the build; no inline handlers or styles (`SEC-SEC-047`) |

**Residual risk.** Coverage indication (`PRD-UIX-027`) depends on visual design achieving prominence without being removed as clutter. This is a design judgement rather than a testable property, and it is the interface's most consequential open risk: an unread coverage indicator is functionally identical to an absent one.

### 15.3 Notes for downstream documents

| Document | Note |
|---|---|
| DOC-15 | `SEC-SEC-047` prohibits inline script and style, which constrains the asset pipeline; `PRD-UIX-030` requires cache headers on confidential responses |
| DOC-16 | Owes: WCAG per-criterion conformance testing; pseudo-localization; keyboard-only completion per workflow; a test asserting honesty surfaces are not suppressible under any theme, density, or preference; latency testing at 200 ms round trip |
| DOC-17 | Deep-hierarchy navigation is the area most likely to need post-usability revision and should carry schedule contingency |

### 15.4 Change History

| Version | Date | Author | Change | Reviewer |
|---|---|---|---|---|
| 1.0.0 | 2026-08-04 | Senior UX Designer; Staff Product Manager; Chief Software Architect | Initial content-complete version. Resolves ADR-006 as interaction density and keyboard-first from one tradition with information architecture rigour from the other, and records that density without visual weight is the harder problem given unbounded configurable hierarchy depth. Specifies tokens with no literal values so that theming, density, and high-contrast mode are substitutions; typography with monospace mandatory for identifiers; a three-mechanism navigation model that assumes no fixed depth or level names; keyboard-first interaction with no pointer-only capability; component specifications by obligation; six visually distinct states with unmeasured distinct from zero; §10 gathering fourteen honesty-surface obligations owed by other documents with a prohibition on suppression by any path; WCAG 2.2 AA with per-criterion documentation; internationalization required from v1 with ICU formatting and no concatenation; and performance budgets including usability at 200 ms round-trip latency. Forty-four requirements. | Pending |

---

*End of DOC-08.*
