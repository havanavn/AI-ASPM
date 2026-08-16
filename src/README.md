# src — module scaffold and build-time enforcement

Produced by prompt 2 of `IMPLEMENTATION_PROMPTS.md`, against DOC-02 sections 6 to 8 and the stack
of ADR-049 to ADR-056.

`docs/` is read-only reference. Nothing in this directory changes a requirement.

---

## Toolchain

| Component | Version | Why pinned |
|---|---|---|
| JDK | **25.0.4 LTS** (Temurin) | ADR-050 floor is Java 25 LTS or later |
| Gradle | **9.6.1** | Verified by SHA-256 against `services.gradle.org` before use |
| Error Prone | **2.50.0** | Runs on JDK 25; carries the platform's custom checks |
| ArchUnit | **1.4.1** | Bytecode-level structural assertions |

The host had only JDK 21; the JDK 25 toolchain was fetched separately. `gradle build` therefore
requires `JAVA_HOME` pointing at a JDK 25 or later installation. There is no Gradle wrapper
committed yet — see *What remains*.

---

## Layout

```
src/
├── settings.gradle.kts          46 subprojects
├── build.gradle.kts             root conventions: toolchain, Error Prone, dependency locking
├── gradle/libs.versions.toml    every coordinate pinned (ADR-050 deterministic resolution)
├── shared-kernel/               DOC-03 5.2: TenantId, PrincipalId, OrgNodeId, ScopeDescriptor,
│                                SeverityOrdinal, @AspmModule
├── platform-events/             DOC-02 7.3: the shared events package — the one permitted asymmetry
├── platform-kernel/             DOC-02 6.2, five modules × {contract, impl}
├── module/                      DOC-02 6.1, fifteen modules × {contract, impl}
├── build-checks/                custom Error Prone checks, applied to every other subproject
├── architecture-tests/          ArchUnit and hand-written structural assertions
└── app/                         the assembly; the only subproject permitted to depend on an -impl
```

**Twenty modules, not twenty-five.** Prompt 2 asks for "the five kernel modules; the twenty domain
modules". DOC-02 Figure 6.1 shows five kernel modules and **fifteen** further modules (M1–M15), so
the total is twenty. DOC-02 prevails; the scaffold has 5 + 15. `ModuleInventoryTest` asserts both
sets by name, so a divergence from the figure is a test failure rather than a reading error.

### Why each module is two subprojects

ADR-050's CON-PLT-013 mechanism. `<module>-contract` holds commands, queries, events and DTOs;
`<module>-impl` holds `domain`, `application` and `infrastructure`. No module declares a dependency
on another module's `-impl`, so a foreign internal type **is not on the compile classpath** and
reaching it is a resolution failure — no severity setting to downgrade, no suppression annotation to
apply. `java-library` rather than `java` is deliberate: without the `api`/`implementation`
distinction a transitive dependency would silently widen the reach CON-PLT-013 restricts.

### The dependency graph

`CON-PLT-014` requires the direction to match DOC-03 section 5.3. The graph is encoded twice, on
purpose:

- **Enforced** — the Gradle dependency list, which decides what compiles.
- **Declared** — `@AspmModule(permittedDependencies = ...)` on each module descriptor, which states
  the DOC-03 section 5.3 intent. `DependencyDirectionTest` asserts the actual bytecode graph is a
  subset of the declared graph, so adding a line to a build file is a reviewable divergence rather
  than a silent widening.

DOC-02 Figure 6.1's arrows are *upstream supplies downstream*, **not** compile-dependency direction,
and the two patterns resolve differently:

| Pattern | Compile direction | Source |
|---|---|---|
| Published Language | the **consumer** depends on the publisher's contract | "Downstream contexts consume; they do not extend" |
| Customer-Supplier, supplier pushes | the **supplier** depends on the customer's contract | `CON-PLT-014`'s own worked example: "Vulnerability Management depending on Ingestion would make a parser change a domain change" |
| Customer-Supplier, customer reads | the **customer** depends on the supplier's contract | Risk "consumes from three upstreams"; Capacity "reads; it does not write work state" |
| Anti-Corruption Layer | the **ACL holder** depends on the foreign contract | DOC-03 5.3 places the ACL in Ingestion |
| Separate Ways / event-driven (dashed) | **no compile dependency** | realized through `:platform-events` |

Two resolutions worth flagging to a reviewer:

- **`identity` and `authorization`.** DOC-03 5.3 makes Identity upstream of Authorization in
  Customer-Supplier, but `CON-PLT-011` forbids a kernel module from depending on a domain module.
  Resolved by publishing `PrincipalId` in the shared kernel, so the kernel accepts a principal
  without depending on the identity module.
- **`work-management`.** Receives finding and assessment linkage by event only (dashed edges), so it
  declares no dependency on `vulnerability-management` or `assessment`.

---

## Build-time checks, and the evidence they block

Six controls. Each was deliberately violated, the build run, and the failure captured — prompt 2's
last paragraph, because a check that exists but does not block is worse than no check.

| # | Requirement | Mechanism | Failure observed |
|---|---|---|---|
| 1 | `SEC-AUZ-050` | Custom Error Prone check at ERROR severity, inside javac | `error: [RoleIdentifierComparison] SEC-AUZ-050: a role identifier must not be compared against a literal` |
| 2 | `CON-PLT-013` | Gradle subproject partition | `error: package aspm.module.assetinventory.domain does not exist` |
| 3 | `CON-PLT-013` | `CrossModuleAccessTest` on bytecode, *after* the partition is deliberately widened | `CON-PLT-013: no module reaches another module's domain, application or infrastructure layer FAILED` |
| 4 | `CON-PLT-014` | Gradle subproject partition | `error: package aspm.module.capacity.contract does not exist` |
| 5 | `CON-PLT-014` | `DependencyDirectionTest`, declared graph vs bytecode | `CON-PLT-014: the actual module dependency graph is within the graph declared per DOC-03 section 5.3 FAILED` |
| 6 | `CON-PLT-016` | Gradle rejects a circular subproject dependency | `Circular dependency between the following tasks:` |
| 7 | `CON-PLT-017` | ArchUnit domain purity | `CON-PLT-017: no domain class depends on persistence, framework, transport or serialization FAILED` |
| 8 | `CON-PLT-011` | `CrossModuleAccessTest` on bytecode | `CON-PLT-011: no kernel module reaches any domain module, in any layer FAILED` |

All violations were reverted; the tree is clean and `gradle build` is green with 13 structural tests
passing.

### Three checks were defective and the demonstration is what found them

Recorded because the corpus asks that mistakes be owned rather than quietly fixed, and because each
is a failure mode a reviewer should look for in later prompts.

1. **A package-pattern rule that excluded the case it was written for.** The original CON-PLT-013
   rule was `noClasses().that().resideOutsideOfPackage("aspm.module.(*).domain..")`. The `(*)` is a
   plain wildcard, not a back-reference, so the `that()` clause excluded *every* module's domain
   package — and the single most likely real violation, one module's domain reaching another's, was
   invisible. **The rule passed while the violation was present.** Replaced by
   `CrossModuleAccessTest`, which compares source module against target module.

2. **Compile-time constant inlining defeats bytecode dependency analysis.** The first violation
   fixtures referenced `CapacityModule.NAME`, a `static final String`. `javac` folds such a constant
   into its call site and *no bytecode dependency survives*, so both bytecode rules saw nothing.
   The compile classpath still catches it (case 4), so the two mechanisms are complementary rather
   than redundant. The limitation is now stated in `DependencyDirectionTest` and
   `CrossModuleAccessTest` so no reviewer reads them as covering it.

3. **A name-suffix heuristic captured the violation itself.** `ModuleInventoryTest` identified
   descriptors by "class name ends with `Module`", so a fixture named `KnowsADomainModule` was
   collected as a descriptor and the test failed for an incidental reason instead of the intended
   one — indistinguishable from working. Replaced by the `@AspmModule` annotation.

### Two rules are currently vacuous, deliberately and visibly

`LayeringTest`'s two rules carry `allowEmptyShould(true)`, because no class lives in a `domain`
package until prompt 3. They are retained rather than deferred — a rule written after the code it
governs is a rule written to fit the code — and their effectiveness is evidenced by case 7 above,
not by their current pass. **Every other rule has `allowEmptyShould` removed**, so vacuity surfaces
as a failure rather than a green tick.

---

## What remains

Not started; belongs to prompt 3 and later:

- **No Gradle wrapper is committed.** `gradle/wrapper/` with a verified `gradle-wrapper.jar` and a
  `dependency-verification` metadata file are both required by ADR-050's deterministic-resolution
  property. `dependencyLocking` is enabled but **no lockfiles have been written** — run
  `gradle --write-locks` once the first real dependencies land.
- **Every module is a shell.** Each holds a descriptor and layer `package-info` files only. No
  aggregate, no invariant, no schema, no permission, no state machine.
- **`SEC-AUZ-050`'s second clause is not implemented.** The check rejects role-literal comparison;
  it does not yet reject data access that bypasses the evaluation contract, because the
  `CON-PLT-037` type — an authorization decision as a required input to query construction — does
  not exist until prompt 3. That type, not the analyzer, is intended to carry the enforcement.
- **`SEC-AUZ-051` exemption reporting is not wired.** Suppressions are greppable but no per-build
  count is emitted.
- **No persistence, so no `CON-DAT-012`.** Row-level security, the four database credentials, and
  `current_tenant_id()` are prompt 3.
- **`OQ-015` and `OQ-026` still gate prompt 3.** `IMPLEMENTATION_PROMPTS.md` requires both answered
  before it: partition counts are irreversible after production data, and three credential paths in
  build block 1 depend on the vault decision. ADR-052 settles the vault *contract*; the sizing
  figure is a business fact that has not been supplied.

---

# Prompt 3 — platform kernel, session 1 of at least 2

⚠ **Order-critical block.** `PRD-PLT-001` makes the platform kernel one of four mechanisms capturing
data that cannot be reconstructed later.

## What this session delivered

**`tenant-context` kernel — complete for the gate, DOC-24 sections 4 to 7.**

| Type | Carries |
|---|---|
| `TenantContext` | DOC-24 §5.2 record. Immutable. No constructor without a provenance, so a context assembled from a request field cannot be built without naming a provenance that does not fit — `SEC-TEN-004` |
| `TenantContextHolder` | `CON-PLT-035` implicit propagation, on `ScopedValue` (finalised in JDK 25) rather than a thread local, so the binding cannot survive into a pooled thread's next task |
| `TenantBoundWork` | `SEC-TEN-006`. No constructor without a context, so an unbound work item is not constructible |
| `TenantScopedAccess` | The `CON-PLT-036` gate. Requires an established context **and** an `AuthorizedQuery` |
| `AuthorizedQuery` | The `CON-PLT-037` key. Package-private constructor |
| `AuthorizationGateway` | The single mint. `protected`, so only a subclass reaches it |
| `ScopePredicate` | Carried as a **value** so it composes into retrieval, per `SEC-AUZ-016`. A callback could only run after rows exist |

**`authorization` kernel — contract complete, evaluator deliberately denying.**
`PermissionId`, `ObjectReference`, `EvaluationTime`, `AuthorizationRequest`, `ScopeGrant`,
`AuthorizationDecision`, `DenialReason`, `AuthorizationGate`, plus
`ScopeResolvingAuthorizationGate`.

`AuthorizationDecision` is a **sealed pair, not a record with an `outcome` field**. With a field,
`permittedFields()` is callable on a denial and one inverted comparison fails open. With a sealed
pair, a denial has no fields to read and the compiler requires both cases.

The evaluator **denies everything** until prompt 4 supplies the organization closure table —
`SCOPE_RESOLUTION_UNAVAILABLE`, which `SEC-AUZ-014` names explicitly. A permissive placeholder would
let every authorization test written before prompt 4 pass, and the tests would then be rewritten to
match a permissive baseline.

**Migrations — `V001` (tenant-context) and `V002` (audit), each owned by its module per `CON-PLT-015`.**

- `current_tenant_id()` **raises** when unset, with `EXCEPTION`, never null — `CON-DAT-013`
- `apply_tenant_isolation()` applies all four statements together, because applying them by hand per
  table is how one table ends up without `FORCE`, and a table without `FORCE` fails no query
- `tenant_isolation_gaps()` returns every tenant-scoped table missing enabled, forced, `USING` or
  `WITH CHECK` — the assertion `CON-DAT-012` needs
- Five roles: `app_runtime` (`NOBYPASSRLS NOSUPERUSER`), `migration_runner`, `integrity_verifier`,
  `offboarding_executor`, plus `payload_eraser` holding the only `DELETE` on the payload
- `audit_event` append-only **as an absence of grants, not a trigger** — a trigger can be disabled by
  the owner; a grant never made cannot be exercised. No role holds table `UPDATE` or `DELETE`. The
  erasure marker is a two-column grant, so a table-wide grant cannot be used to rewrite `chain_hash`
- Monthly range partitions provisioned three months ahead, with a runway function for `OPS-DEP-011`

### Two design decisions a reviewer should challenge if they disagree

**1. A kernel cycle had to be broken.** DOC-02 §6.2 assigns the data access gate to
`tenant-context`, but the gate needs authorization's decision while authorization needs the
established tenant. Taken literally that is a contract-level cycle, which `CON-PLT-016` forbids and
which would be worse in the kernel than anywhere else because every module depends on all five.
Resolved by inverting: `tenant-context` declares `AuthorizedQuery` and an abstract
`AuthorizationGateway`; `authorization-impl` extends it and supplies the evaluated result.
`tenant-context-contract` therefore still depends on `shared-kernel` alone.

**2. `AuthorizationRequest` carries no tenant field**, where DOC-07 §8.2's sketch lists
`tenant_context`. The evaluator reads the established context itself. That satisfies the sketch's own
annotation — "established, never from the request" — more strongly than a parameter can, since a
field could be populated from a request value and an absent field cannot. No requirement changes;
`SEC-AUZ-013` to `-015` are satisfied. Recorded because it is a visible departure from the sketch.

**The one honestly-stated weakness.** `protected` access means an `AuthorizationGateway` subclass
could in principle be declared outside the authorization module, so the final link in `CON-PLT-037`
is enforced on bytecode by S9 rather than by the compiler. A sealed hierarchy cannot span packages on
the classpath, and putting `AuthorizedQuery` in the authorization module would move the gate there
too, contradicting DOC-02 §6.2. The trade is recorded, not hidden.

## Test position

**61 tests, 0 failures, 27 skipped.** Corpus validation passes; `docs/` untouched.

| Suite | State |
|---|---|
| Structural S1–S6, S8, S9 | Passing. S8 and S9 added this session — the two the review point exercises |
| Isolation inventory I1–I20 | **All twenty present.** 2 assert now; 18 `@Disabled` naming the prompt that implements them |
| `FailClosedTest` | 8 passing: fail-closed, provenance required, break-glass pairing, async binding not inherited |
| `DenyByDefaultTest` | 6 passing, including A6 fault injection and "a denial mints no key" |
| `KernelPersistenceVerificationTest` | **9 written, 0 observed — all skipped.** See below |

**On the 18 disabled isolation paths.** Skipped, not absent. `TST-TEN-001` is the corpus's weakest
link because it is procedural — a subsystem can be added without its path and no test detects the
test that was never written. `theInventoryIsComplete` enumerates I1–I20 and fails if one is missing,
so a skip is a debt on a ledger where an absence would be invisible. Watch that the skip count falls
as prompts land and that no path becomes a passing test asserting nothing.

## ⚠ What is NOT verified, and why

**The database half of the prompt 3 review point has not been observed passing.** This environment has
no container access (the user is not in the `docker` group), no passwordless sudo, and no local
PostgreSQL. `CON-DAT-012`, `CON-DAT-013`, `CON-DAT-014` and `INV-AUD-01` are therefore **written and
unverified**. A skipped verification is not a passing one.

To close it:

```bash
sudo usermod -aG docker $USER && newgrp docker     # then a container, or:
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=pw postgres:18-alpine
JAVA_HOME=<jdk25> gradle :kernel-verification:test \
  -Daspm.verification.jdbcUrl=jdbc:postgresql://localhost:5432/postgres \
  -Daspm.verification.user=postgres -Daspm.verification.password=pw
```

## What remains in prompt 3

Three of the five kernel modules are still shells:

- **`audit`** — the chain itself. `V002` has the tables; the `chain_hash(n) = H(chain_hash(n−1) ‖
  canonical(envelope) ‖ payload_hash)` computation of DOC-14 §4.1, per-tenant head serialization
  under `SEC-AUD-014`, the versioned canonical serialization under `SEC-AUD-011`, the three
  verification levels of DOC-14 §4.2, and anchoring under `CON-DAT-028` are all unwritten.
- **`schema-registry`** — nothing. DOC-04 §20.4 `attribute_schema`; the ADR-027 mechanism.
- **`rules-engine`** — nothing. `CON-PLT-012` requires one evaluator shared by four modules.
- **`TenantScopedAccess` has no implementation** — the interface exists, the infrastructure class
  binding `SET LOCAL` and resetting on return (`SEC-TEN-007`, `OPS-DEP-010`) does not.
- **S11 and S13** of DOC-16 §3 are not implemented (catalogued event types; requirement identifiers
  in code resolving against the register).
- **Structural test S7** exists from prompt 2; **S10, S12** await their modules.

`OQ-015` is still unanswered. It did not block this session — monthly range partitioning grows with
time, not volume, so no irreversible sizing decision was taken, and the basis is recorded in `V002`
per `OPS-DEP-012`. **It blocks prompts 5 and 11**, where the hash partition counts on `finding` and
`component_entry` become irreversible after production data (`CON-DAT-035`).

---

# Prompt 3 — session 2: the audit chain and schema-registry

**82 tests, 0 failures, 27 skipped.** Corpus validation passes; `docs/` untouched.

## `audit` kernel — the chain is complete and verified

| Piece | Property that mattered |
|---|---|
| `AuditEventType` | The DOC-14 §3 catalogue as an **enum**, 79 types. `SEC-AUD-006` requires an uncatalogued type to fail the build; an enum makes it a symbol that does not resolve, so the build fails for the same reason a typo does. A string plus a registry check would defer it to runtime |
| `DomainChangeKind` | Per-aggregate changes deferred, because DOC-14 §3 defers them and no aggregate exists until prompt 4. Composite code `<aggregate>.<kind>` |
| `AuditEnvelope` | `final` record, no extension point — `SEC-AUD-001` forbids an event type introducing envelope fields. `AUTOMATION` without its rule is **not representable** (`SEC-AUD-004`) |
| `CanonicalSerializer` | Versioned, **length-prefixed** field encoding. Fixed-width UTC nanoseconds. Literal field order, not reflection |
| `ChainHasher` | `H(prev ‖ canonical(envelope) ‖ payloadHash)`, genesis binds the tenant. Constant-time comparison |
| `ChainVerifier` | Three levels; sequence and chain checked as **two independent mechanisms**; erasure reported as an observation, never a failure |

### Why the serialiser is this paranoid

`SEC-AUD-011` says any variance "makes verification fail on unaltered data", and `SEC-AUD-018` makes a
failure page to a destination outside operator control and forbids suppressing it. False alarms here
are therefore expensive, so every ambient dependency is removed rather than configured:

- **Length prefixing, not concatenation.** Concatenation with a separator is forgeable — two different
  field sets produce identical bytes when a value contains the separator, so an actor able to influence
  one field could match another event's canonical form.
- **Fixed-width nanoseconds.** `Instant.toString()` omits trailing zeros, so `09:00:00Z` and
  `09:00:00.000000000Z` are the same instant with different bytes. Asserted by a test.
- **A printable absent-marker.** `null` and `""` must not canonicalise identically, or one event can be
  substituted for the other.
- **Erasure fields excluded from the canonical form.** If they were covered, erasing a payload would
  change the envelope's canonical form and break every later link — the exact outcome `CON-DAT-027`
  exists to avoid.

### The assertion that matters most

`erasureIsLocal` asserts that after erasing a payload, **every chain hash is byte-identical**. That is
the whole of ADR-034 in one test: a tenant that has exercised erasure can still produce a passing
verification report. Without it, compliance and auditability are mutually exclusive.

Tamper detection is asserted positively too: an edited outcome yields `LINKAGE_MISMATCH`; a removed
event yields `SEQUENCE_GAP` (detected by the sequence mechanism, which `SEC-AUD-002` requires precisely
because "the chain alone cannot distinguish a removed tail from a shorter history"); a duplicated
sequence yields `SEQUENCE_DUPLICATE`; a chain copied to another tenant fails against its genesis.

### One trap Error Prone caught

`ChainVerifier.StoredEvent` was a record with `byte[]` components, which gives value-semantics `equals`
that compares arrays by **identity**. A later `assertEquals(expected, actual)` would have passed or
failed for a reason unrelated to the chain. Converted to a final class with defensive copies and
inherited identity equality — unambiguous, and does not mislead.

## `schema-registry` — contract only

`AttributeSchema` and `AttributeDataType`. `CON-DAT-018`'s bound is enforced in the constructor as well
as by the DOC-04 §20.4 `CHECK`, so a searchable field without an index slot is not representable before
the row reaches the database. Types are **product-fixed** — ADR-027 makes fields tenant data, not the
type system — and each declares whether it has a total order, because comparison is
authorization-relevant wherever an attribute participates in a workflow guard.

## ⚠ Two DOC-14 / DOC-04 discrepancies — for the corpus owner, not corrected here

Both are diagram-versus-schema, not requirement defects. I followed the requirement and the schema and
changed no requirement.

1. **`source_context`** appears in DOC-14 §2's envelope diagram (`address, user agent, request id`) but
   has no column in DOC-04 §20.1. `SEC-AUD-022` and its own explanatory note place exactly those in the
   payload — "source address, user agent, and any free text belong in the payload". **Requirement and
   schema agree; the diagram is the outlier.** Implemented per the requirement.

2. **`automation_rule_id`** appears in DOC-14 §2's envelope but likewise has no column in DOC-04 §20.1.
   Here the diagram is right and **the schema has an omission**: `SEC-AUD-004` requires that where
   automation acts "the rule and its owning principal must both be recoverable", and the payload is
   *erasable* — so putting it there would destroy automation attribution on erasure. I added the column
   to `V002` with a `CHECK` that an `AUTOMATION` actor must carry it. DOC-04 §20.1 should gain the
   column; that edit is the corpus owner's.

## What remains in prompt 3

- **`rules-engine`** — nothing yet. `CON-PLT-012` requires one evaluator shared by workflows,
  automation, service-level matching and checklist selection, and DOC-02 §6.2 notes one of them
  "determines whether an approval gate applies", so divergence is a privilege escalation. Needs DOC-09
  §13, DOC-28 and the checklist model read together; it deserves its own session rather than a guess.
- **`TenantScopedAccess` implementation** — the interface exists; the infrastructure class binding
  `SET LOCAL` and resetting on return (`SEC-TEN-007`, `OPS-DEP-010`) does not.
- **`AuditWriter`** — the chain arithmetic is done and tested, but nothing writes it. Needs the gate
  implementation plus per-tenant chain-head serialisation under `SEC-AUD-014`, which is a deliberate
  write-throughput cost DOC-14 §4.2 accepts and budgets against `NFR-AUD-001`.
- **Anchoring** — `CON-DAT-028` and `SEC-AUD-015`; the checkpoint table exists, the anchoring job does not.
- **S11 and S13** of DOC-16 §3.
- **The database half remains unverified** — 9 tests written, 0 observed. Unchanged from session 1.

---

# Prompt 3 — session 3: the rules engine

**103 tests, 0 failures, 27 skipped.** No compiler or Error Prone warnings. Corpus passes; `docs/`
untouched. All five kernel modules of DOC-02 §6.2 now have substance.

## One evaluator, four callers — `CON-PLT-012`

DOC-02 §6.2: "Four implementations would diverge, and one of them governs authorization-relevant
workflow transitions." I read all four semantics before writing any of it — workflow guards
(`PRD-WRK-033`), automation rules, most-specific-wins SLA matching (DOC-28 §11.1), and checklist
selection — because writing the evaluator against one of them and generalising later is how the
divergence gets built in.

| Type | Role |
|---|---|
| `FactValue` | Closed value hierarchy: `Text`, `Decimal`, `Bool`, `Timestamp`, `Date`, `Ordinal`, `Id`, `IdSet`, `TextSet`, `ScopePath`, `Absent` |
| `Condition` | Closed grammar: `All`, `Any`, `Not`, `Comparison`, `Present`, `WithinSubtree`, `AlwaysTrue` |
| `RuleOutcome` | **Three-valued** Kleene logic |
| `ConditionEvaluator` | No fields, no collaborators — side-effect freedom is structural |
| `RuleMatcher` | Most-specific-wins ranking, ties surfaced not guessed |
| `ConditionValidator` | Definition-time cost bound and unknown-fact-key rejection |

### Three-valued logic is the load-bearing decision

`NOT` over a missing fact must be `UNDEFINED`, not `TRUE`. Under two-valued logic a guard reading
"may close when NOT informational" **permits closing a finding whose severity was never recorded** —
a fail-open produced by a reasonable-looking guard over incomplete data, which is precisely what
product principle 1 exists to prevent. `RuleOutcome.negate()` is one line and it is why the enum
exists. There is a test named for it.

All four callers treat `UNDEFINED` as not-true, but the distinction is preserved to the boundary
because their *reasons* differ — and because `RuleMatcher` reports `UNDEFINED` rules separately from
rules that correctly did not apply. A rule that never fires because a fact is always absent is a
configuration defect that is **invisible** if `UNDEFINED` and `FALSE` are the same value.

### What the grammar deliberately cannot express

`PRD-WRK-033` forbids a guard invoking AI, external services, or unbounded queries, and requires
bounded cost because "a guard is evaluated on every transition attempt including denied ones" — so a
denied attempt is the cheap path an attacker repeats. Rather than validate against those, the grammar
has **no variant** for a call, a query, a loop, a regular expression, or arithmetic. A tenant cannot
author a guard that reaches outside the fact set because the syntax cannot say it. No regex in
particular: an attacker-influenced field plus a backtracking pattern is a denial-of-service on the
transition path.

Cost is bounded at *definition* time (`MAX_DEPTH` 16, `MAX_NODES` 256) rather than by a runtime
timeout, because a timeout makes a transition's availability depend on load and `PRD-WRK-033` also
requires determinism.

### Three smaller decisions worth challenging

- **Ties are surfaced, not resolved.** DOC-28 §11.1 says equal specificity → shorter duration wins.
  That is a *service-level* policy — checklist selection has no duration — so the engine returns every
  rule tied at the top specificity and the caller applies its own rule. Hardcoding "shorter wins" into
  the kernel would put a domain policy there, which ADR-027 forbids; picking arbitrarily would be
  non-deterministic. Ranking within a band is still stable (specificity desc, then rule id), because
  `PRD-RSK-032` pins a policy version at clock start and a flapping selection would pin different
  policies for identical findings.
- **A disjunction's specificity is its *minimum* branch.** Taking the maximum would let an author
  inflate specificity with an unreachable narrow alternative — a configuration gaming path.
- **Cross-type comparison is `UNDEFINED`, not `FALSE`.** Reporting a type mismatch as false makes the
  rule quietly inert, which is the failure a tenant cannot diagnose because the rule looks correct.

### One test of mine was wrong about the engine

The first version of `sameSemanticsForAllCallers` asserted `UNDEFINED` for a `Present`-based required-
fields guard. `Present` is deliberately the one test that *is* defined over an absent fact, so the
outcome is `FALSE`. The engine was right and the assertion was wrong; it is now split into two tests
covering the definite-false and undefined paths separately, with the correction noted in the source.

## Prompt 3 status: five modules, three gaps

| Kernel module | State |
|---|---|
| `tenant-context` | Gate complete; **infrastructure implementation missing** |
| `authorization` | Contract complete; evaluator denies pending prompt 4's closure table |
| `audit` | Chain complete and tested; **nothing writes it yet** |
| `schema-registry` | Contract complete; no persistence |
| `rules-engine` | **Complete** |

Remaining before prompt 3 can be called done:

1. **`TenantScopedAccess` implementation** — the JDBC class binding `SET LOCAL` and resetting on
   return (`SEC-TEN-007`, `OPS-DEP-010`). Everything else in the kernel is testable without it; this
   is not.
2. **`AuditWriter`** — the arithmetic is done and tested; the writer needs the gate plus per-tenant
   chain-head serialisation under `SEC-AUD-014`, which DOC-14 §4.2 accepts as a deliberate
   write-throughput cost budgeted against `NFR-AUD-001`.
3. **Anchoring** — `CON-DAT-028`, `SEC-AUD-015`. Table exists, job does not.
4. **S11 and S13** of DOC-16 §3.
5. **The database half is still unverified** — 9 tests written, 0 observed. Unchanged since session 1
   and not closable from this environment.

Items 1 and 2 are blocked on nothing but sequence; item 5 is blocked on container or database access.

---

# Prompt 3 — session 4: the gate implementation and the audit writer

**122 tests, 0 failures, 31 skipped.** No compiler or Error Prone warnings. Corpus passes; `docs/`
untouched. **Prompt 3 is now complete apart from the unobserved database verification.**

## `JdbcTenantScopedAccess` — the gate, over JDBC

The only class in the platform permitted to touch a JDBC type, and `S8` asserts that on bytecode — so
an alternative access path is a failing build rather than a review finding, which matters because
DOC-02 §13.1 records that such a path "exists for convenience and then becomes the normal path".

- **The tenant is read from the holder, never a parameter.** There is no overload that takes one, so
  the source `SEC-TEN-004` prohibits is unavailable rather than forbidden.
- **`set_config(?, ?, true)` with bind parameters**, not string-interpolated `SET LOCAL`. The value is
  a UUID from an established context so interpolation would not be exploitable today — but this is the
  one statement every row-level policy depends on, and a parameterised statement removes the question
  rather than arguing it.
- **`autoCommit=false` first**, because `is_local => true` needs a transaction to be local to.
  Without it the setting outlives the borrow, which is DOC-24 §6.2 entry 5 exactly.
- **`DISCARD ALL` then close on return** (`SEC-TEN-007`, `OPS-DEP-010`). A connection that could not be
  reset is closed rather than reused; the swallow is deliberate and the close is what makes it safe.

## `ChainedAuditWriter` — chain computation inside the caller's transaction

`SEC-AUD-014` needs two things and both are structural here: the head is **locked before the sequence
is chosen**, and the store's single `append` carries event, payload and advanced head together — three
methods would permit two of them to succeed.

`AuditChainStore` is a **port**, so the sequencing and chaining logic is tested without a database per
`TST-PLT-005`. What the in-memory store cannot demonstrate is the *lock itself*; that is a property of
`SELECT ... FOR UPDATE` and is asserted only in `KernelPersistenceVerificationTest`, which remains
unobserved. The test class says so, so a green run here is not mistaken for that.

**The tenant and the break-glass reference come from the context, not the draft.** An emitter that
could name a tenant could write into another tenant's chain — the one cross-tenant write no later
verification could unpick — and one that could omit the break-glass reference could hide activity
`SEC-TEN-030` entitles the tenant to see.

`CanonicalPayload` gives the payload deterministic bytes: **keys sorted** (a `HashMap` orders by hash,
which would make identical payloads hash differently), **list order preserved** (order is meaningful in
a before/after payload), `BigDecimal` normalised, and **binary floating point rejected outright**
because its textual form is platform-dependent.

## `V003` — two more additions to DOC-04 §20.1

Both implement a requirement that section's table cannot carry. Reported, not corrected in `docs/`.

1. **`audit_chain_head`** — `SEC-AUD-014` requires per-tenant chain-head serialisation, and without a
   head row it would be `max(sequence)`, which **cannot be locked** (`FOR UPDATE` does not apply to an
   aggregate, so two writers read the same max and both write n+1) and would be an index scan per
   monthly partition on the hottest write path against `NFR-AUD-001`'s 15 ms budget. Carries an
   engine-level **forward-only trigger**: moving the head backwards would permit rewriting a range of
   history and continuing the chain consistently from the rewritten point.
2. **Checkpoint columns** `event_count`, `anchor_target`, `anchor_reference`, `anchor_confirmed_at` —
   DOC-14 §5's shape. `SEC-AUD-015` requires confirmation *tracked*, and DOC-04's single
   `external_anchor_ref` cannot: a reference exists on submission, confirmation is a later fact. Without
   the distinction `SEC-AUD-016`'s "anchor failure MUST alert" has nothing to alert on. `anchor_target`
   is likewise required so a report can **label an operator-attested offline anchor as weaker**, which
   DOC-14 §5 demands rather than presenting it as equivalent.

## S13 caught a defect in its own documentation, immediately

`RequirementReferenceTest` (S13, `PRD-PLT-012`) scans Java and SQL for requirement identifiers and
fails on any not in the register. Its first run failed — on the fake `SEC-AUZ-099` I had written into
its own Javadoc as an *illustration* of a dangling reference. That is the same defect class the corpus
tooling once found ("real requirement identifiers used in illustrative examples"), reproduced by me
within minutes of writing the check for it. Fixed to the corpus's own placeholder convention (`nnn`).

The check skips rather than passes where the register is missing, because a check that silently passes
when its input is absent is the vacuous-check failure prompt 2 exposed.

## Prompt 3: complete, with one gap that is not mine to close

| Kernel module | State |
|---|---|
| `tenant-context` | **Complete** — context, propagation, async binding, gate, JDBC implementation |
| `authorization` | **Complete for this block** — evaluator denies pending prompt 4's closure table |
| `audit` | **Complete** — catalogue, envelope, canonical forms, chain, verifier, writer, migrations |
| `schema-registry` | Contract complete; persistence deferred to the module that first needs it |
| `rules-engine` | **Complete** |

Structural tests: S1–S6, S8, S9, S13 implemented and demonstrated failing. **S11** is carried by the
`AuditEventType` enum plus the writer's catalogue check rather than by a separate bytecode rule — the
enum makes an uncatalogued constant a compile error, and the runtime check covers the composite
per-aggregate codes that do not exist yet. **S7** is from prompt 2. **S10, S12** await their modules.

### The one gap

**13 database verifications are written and none has been observed passing.** `CON-DAT-012`,
`CON-DAT-013`, `CON-DAT-014`, `INV-AUD-01`, `SEC-AUD-014` and the anchoring constraints are
**unverified**, and I cannot close that from this environment — no `docker` group membership, no
passwordless sudo, no local PostgreSQL. Everything else in prompt 3 is done and green.

```bash
sudo usermod -aG docker $USER && newgrp docker
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=pw postgres:18-alpine
gradle :kernel-verification:test -Daspm.verification.jdbcUrl=jdbc:postgresql://localhost:5432/postgres \
  -Daspm.verification.user=postgres -Daspm.verification.password=pw
```

Prompt 4 (organization and scope) is order-critical and its review point — a historical report
reproducing identically across a reorganization — needs a database too.

---

# Prompt 4 — organization and scope, session 1

⚠ **Order-critical.** `PRD-PLT-001`: the scope descriptor is the second of four mechanisms capturing data
that cannot be reconstructed. DOC-03 §6.7 puts it plainly — "the mechanism cannot be added later, the
data does not exist retroactively".

**151 tests, 0 failures, 31 skipped.** No warnings. Corpus passes; `docs/` untouched.

## The descriptor mechanism

`ScopeDescriptor` in `shared-kernel` was a two-field shell after prompt 2; it is now the full DOC-03 §6.7
shape — tenant, owning node, **root-to-node ancestor path**, node type at the time, criticality at the
time, resolved-at, hierarchy version.

Four things a descriptor cannot be constructed without, each enforced in the canonical constructor:

| Rejected | Why it matters |
|---|---|
| Empty ancestor path | Makes the DOC-04 §6.6 containment predicate match nothing, **silently denying every historical read** rather than failing visibly |
| Path not ending at the owning node | Subtree containment and node identity would disagree, and authorization would follow whichever the caller read |
| Repeated node in the path | The tree contained a cycle when the descriptor was resolved (`INV-ORG-07`) |
| `hierarchyVersion` below 1 | Version 0 would make the pre-first-change tree indistinguishable from unset |

**Immutability is the type's, not the caller's.** No setter, no wither, no copy taking a new node. A test
asserts the property by *shape* rather than by name — any method returning a `ScopeDescriptor`, or void
with parameters, would be a mutation path. (My first version matched names starting with `with` and failed
on `withinScopeOf`, a predicate. Matching on shape says what is actually meant.)

## `OrgClosure` — and why the divergence report is directional

`INV-ORG-14` makes rebuildability an invariant, and DOC-03 §7.4 says why: "a corrupted closure table
silently breaks authorization — a principal either loses access they should have, which is reported, or
gains access they should not, which is not."

So `diverges()` returns the **symmetric difference with the two directions separated**:

- `extraneous` — stored rows the rebuild does not produce. **Excess access. Nobody reports this**, which
  is the entire reason scheduled reconciliation exists rather than inspection.
- `missing` — rebuild rows the store lacks. Denied access; someone reports it within the hour.

A boolean would have lost that distinction. `grantsExcessAccess()` is the query a monitor alerts on.

Cycles and dangling parents are **rejected at build time**. `INV-ORG-07` requires cycles "rejected at
write time, not detected later", and a builder that looped would detect one by exhausting memory — which
is detecting it later in the worst available form.

## Criticality — undefined is returned, never defaulted

`INV-ORG-08` states that with no `ASSIGNED` ancestor "resolution is undefined". `Resolved` is a sealed
pair so `Undefined` is a value a caller must handle. A resolver that substituted the least critical tier
would make a misconfigured tree look correctly configured **and understate criticality, which flows into
scoring and then into service level deadlines**.

`INV-ORG-09`'s justification requirement lives in `validateOverride`, not in the `Assignment` constructor,
because whether an assignment *is* an override depends on the ancestry — a constructor that cannot see the
ancestry would reject every assignment or none. A first assignment with no `ASSIGNED` ancestor is not an
override and needs no justification: requiring one would make the field ceremonial, and a ceremonial field
gets filled in with "n/a".

## `ReorganizationSaga` — DOC-09 §17

All nine states, with the four requirements expressed structurally:

- **`PRD-WRK-039`** — `VALIDATING` is the only entry and has no compensation, because nothing is mutated.
  Entering compensation from it throws.
- **`PRD-WRK-040`** — `compensated()` records that the version **stays consumed**. A reused version makes
  two tree shapes share an identifier, making historical descriptors ambiguous.
- **`PRD-WRK-041`** — `MANUAL_INTERVENTION` is terminal with **no outbound transition in the class**.
  Recovery is an operator procedure; an automated exit would resume work on a corrupted authorization
  substrate.
- **`PRD-WRK-042`** — the saga never touches a descriptor, and the type offers no way to.

The prior parent is captured **at construction**, because "restore prior parent" is `REPARENTING`'s
compensation and a compensation that must look up what it restores can be defeated by the failure it is
compensating for.

## ⚠ What prompt 4 still owes

1. **`V004` migration** — `org_node_type`, `org_node`, `org_closure`, `criticality_tier` per DOC-04 §11.2,
   with the descriptor columns of §6.6 on scope-bearing tables and `CON-DAT-009`'s immutability enforced
   at the engine.
2. **`OrgNode` aggregate** — `INV-ORG-05`, `-06`, `-10`, `-11`, `-12` (including the ownership-gap *event*
   rather than a rejected write) and the lifecycle. The closure, criticality and saga are done; the node
   itself is not.
3. **`OrgNodeType`** — `INV-ORG-01` to `-04`, and `PRD-ORG-001`/`-004`'s no-fixed-depth, no-fixed-level-names
   configurability.
4. **`CriticalityTier`** — `INV-ORG-16`, `-17`.
5. **The real `ScopeResolver`** — prompt 3's evaluator still returns `SCOPE_RESOLUTION_UNAVAILABLE` for
   everything. Wiring it to the closure is what makes authorization function at all.

### The review point remains unobserved

Prompt 4's review point is "after a simulated reorganization, does a historical report reproduce
identically?" **The in-memory half is asserted and passes** — 5 tests under `TST-PTR-003`. What is *not*
verified, because it needs a database:

- that an `UPDATE` cannot alter a descriptor column (`CON-DAT-009` at the engine);
- that PostgreSQL's `scope_ancestor_path @> ARRAY[N]` agrees with `ScopeDescriptor.withinScopeOf` — two
  implementations of one predicate, and DOC-04 §6.6 relies on the indexed one;
- `O14` closure rebuild equality against **stored** rows rather than two in-memory builds.

The mechanism's shape is now fixed and tested. Its persistence is not, and DOC-16 §4.3 warns that failure
here "is silent: a historical report that changes after a reorganization looks like a data error rather
than an authorization defect".

---

# Prompt 4 — session 2: the type catalogue, the node aggregate, and the resolver

**171 tests, 0 failures, 31 skipped.** No warnings. Corpus passes; `docs/` untouched.

## Authorization now functions

Prompt 3's evaluator denied everything with `SCOPE_RESOLUTION_UNAVAILABLE` because the closure did not
exist. `ClosureBackedScopeResolution` supplies it, and the two operations are **deliberately separate
methods** rather than one with a nullable instant:

- `resolveCurrent` — the tree as it is now.
- `wasAuthorized` — the object's **recorded** descriptor path. The current closure is deliberately not
  consulted; DOC-04 §6.6 records that a join to a historical closure "would require reconstructing the
  closure at `scope_hierarchy_ver`, which is precisely the reconstruction the embedded descriptor exists
  to avoid".

DOC-03 §7.5 requires that "which one a given report uses must be stated on the report", and one method
with an optional parameter is how a caller ends up not knowing which it asked for.

**`SEC-AUZ-010`'s union-not-cross-product is structural.** `AssignmentSource` is queried *per permission*,
so the resolver never holds the material for a cross product. A source returning all assignments would
hand the caller exactly that. There is a test: read on unit-A plus approve on unit-B must not yield
approve on unit-A's project.

**Unavailable is not an empty permitted set.** An empty set means "this principal reaches nothing", a
legitimate configuration; unavailable means "we do not know", which `SEC-AUZ-014` denies for a different
reason. The record rejects a resolution that is both, because a partial resolution presented as
successful is how a principal silently loses part of their scope.

### A caveat named rather than left implicit

`wasAuthorized` composes the principal's **current** assignments with the object's **historical** scope.
That is what DOC-03 §6.7's deliberate limit prescribes — the descriptor "does not record who held which
role, which is Authorization's history and is owned by DOC-07". A reader assuming this method reproduces
the full authorization state of a past instant would build a historical report that is subtly not
reproducible, so the caveat is stated in the class rather than left to be discovered.

## `OrgNodeTypeCatalogue` — validated as a set

`INV-ORG-01` and `INV-ORG-02` are set-level properties, and DOC-04 §11.2.1 says so: "a set-level assertion
the engine cannot express per row". A per-row check would either pass on a rootless catalogue or reject
the first type ever defined. Validation returns **every** finding rather than the first, because a tenant
fixes the whole catalogue in one pass.

Type-level cycles are rejected "independently of instance-level cycle rejection" — the two are genuinely
separate: a legal type graph still permits an illegal instance tree, and a cyclic type graph makes every
instance tree unreachable. No fixed depth and no fixed level names: a three-level catalogue and a
ten-level one are both valid configurations rather than one being the product's shape
(`PRD-ORG-001`, `PRD-ORG-004`).

## `OrgNode` — and the one invariant that is an event

**`INV-ORG-12`'s ownership gap is a domain event, not a rejected write.** DOC-03 §7.3 is unusually
explicit: "Requiring an owner at write time appears safer and is worse… rejecting the write means the node
is not created, which means its assets have no home at all." Making the unsafe state *visible* beats making
it *unrepresentable* when the unsafe state is a normal transient. The emission is idempotent so a repeated
check does not flood the queue.

`INV-ORG-05` is enforced as the **coherence** of parent identity with parent type — supplying one without
the other means `INV-ORG-06` cannot be checked, and an unchecked parent-type rule is how a tenant's
structure silently stops meaning anything. `reparentTo` publishes no event: the saga publishes
`OrgNodeMoved` only after the closure is rebuilt, because an event published earlier would let a subscriber
read a half-moved tree.

Lifecycle is one-directional with **no path out of `ARCHIVED`**, because historical descriptors name
archived nodes (`INV-ORG-11`). A `DEPRECATED` node refuses new assignment but stays in operational views so
in-flight work completes; the two questions — "accepts assignment" and "appears in operational scope" — are
separate methods because they have different answers.

`Event.Deprecated` was renamed `LifecycleDeprecated`: Error Prone flagged the clash with
`java.lang.Deprecated`, and a domain event whose name shadows an annotation is a genuine reading trap.

## What prompt 4 still owes

- **`V004` migration** — `org_node_type`, `org_node`, `org_node_owner`, `org_closure`, `criticality_tier`
  per DOC-04 §11.2, plus the descriptor columns of §6.6 and `CON-DAT-009`'s engine-level immutability. The
  domain is done; none of it is persisted.
- **`CriticalityTier`** — `INV-ORG-16` (unique, totally ordered ordinals) and `INV-ORG-17` (deprecate, never
  delete). The resolver consumes tier identifiers; the tier aggregate itself is not written.
- **Wiring `DenyAllScopeResolver` out** — `ClosureBackedScopeResolution` exists but the authorization kernel
  still constructs the denying resolver. The adapter between them is a small piece of assembly and belongs
  with `V004`, because a resolver with no persisted closure has nothing to resolve against.
- **`INV-ORG-10`** — a node that has ever owned an asset or scoped a finding cannot be hard-deleted. DOC-04
  §11.2.2 maps this to `ON DELETE RESTRICT` from every referencing table, so it is a migration obligation
  and cannot be asserted in the domain.

**The database-dependent assertions are unchanged**: 13 kernel verifications plus the three persisted halves
of `TST-PTR-003`. I have stopped repeating the remedy — it is recorded above and in the prompt 3 sections.

---

# Prompt 4 — session 3: V004, the tier scale, and the resolver wired in

**184 tests, 0 failures, 37 skipped.** No warnings. Corpus passes; `docs/` untouched. **Prompt 4 is
complete apart from database observation.**

## The build caught a design error from prompt 3

`ScopeResolver` was in `authorization-impl`. It compiled, it looked right, and it was wrong: it is a
**port that a domain module implements**, and no module can see another module's `-impl` — that is ADR-050's
`CON-PLT-013` mechanism. The failure arrived the moment `organization-scope` tried to implement it:

```
error: package aspm.kernel.authorization.application does not exist
```

Moved to `authorization-contract`, which now depends on `tenant-context-contract` for the parameter type
(acyclic — `tenant-context-contract` depends only on `shared-kernel`, so `CON-PLT-016` holds). A port whose
implementors live outside the module belongs to the published surface by definition, and **the compile
failure said so before a reviewer had to.** The class comment records it.

## `V004` — the schema, with four engine-level guards

| Guard | Mechanism | Why not the domain |
|---|---|---|
| `CON-DAT-009` / `PRD-WRK-042` | `reject_scope_descriptor_change` trigger applied by `add_scope_descriptor()` | A descriptor mutated by any path destroys historical reproducibility, and DOC-16 §4.3 warns the failure is **silent** |
| `INV-ORG-04` | `reject_code_change` trigger on `org_node_type` and `criticality_tier` | A domain-only check is bypassed by an import path, and the breakage appears as empty results rather than errors |
| `INV-ORG-13` | Deferred constraint trigger asserting a depth-zero row per node | Deferred because a closure rebuild deletes and reinserts, so an immediate check would fail mid-transaction |
| `INV-ORG-09` | `CHECK` requiring justification on every `ASSIGNED` criticality | **Deliberately stricter than the invariant**, per DOC-04 §11.2.2 — the per-row form cannot see the ancestry, and the root's assignment is the most consequential in the tenant |

The descriptor columns are applied by a **function**, not by hand per table. Applying them by hand is how
one table ends up without the trigger — the same failure mode as a tenant-scoped table without `FORCE` — so
`scope_descriptor_gaps()` exists as the conformance query, mirroring `tenant_isolation_gaps()`.

`org_closure_divergence(tenant)` implements `CON-DAT-026` as a recursive CTE rather than application code,
and **preserves direction**: `EXTRANEOUS` rows grant access nobody reports, `MISSING` rows deny access
somebody reports within the hour.

**`INV-ORG-10` and `INV-ORG-17` are a pair, not one control.** `ON DELETE RESTRICT` alone permits deleting a
not-yet-referenced node whose historical descriptors already name it, so the application also holds **no
`DELETE` grant** on `org_node`, `org_node_type` or `criticality_tier`.

## `CriticalityTierScale` and the resolver adapter

`INV-ORG-16` rejects duplicate ordinals **at construction** rather than reporting them, because two tiers
sharing an ordinal make prioritisation depend on read order. Lower ordinal means more critical, restated
wherever it is used — the opposite convention is equally plausible and a reversed comparison would invert
every prioritisation silently.

`OrgScopeResolverAdapter` removes `DenyAllScopeResolver` from the assembly. It keeps
`SCOPE_RESOLUTION_UNAVAILABLE` and `NO_MATCHING_GRANT` **distinct**: conflating them would make a closure
outage indistinguishable from a correctly restrictive configuration, and the two need different operator
responses.

### A test I wrote that could not compile, and why that was the better answer

I tried to assert that the adapter cannot mint the gate key with `instanceof AuthorizedQuery`. It did not
compile — the types are unrelated, so the property is a **compile-time impossibility rather than a runtime
fact**. Replaced with a reflective shape check over `ScopeResolver`, `Resolution` and the adapter asserting
no method returns the key, which is the assertion that can actually regress.

## Prompt 4 status

| Piece | State |
|---|---|
| Scope descriptor | **Complete** — full §6.7 shape, immutable, four constructor rejections |
| `OrgClosure` + rebuild-and-compare | **Complete**, domain and SQL |
| Criticality inheritance and override | **Complete** |
| Reorganization saga | **Complete** |
| `OrgNodeTypeCatalogue`, `OrgNode`, `CriticalityTierScale` | **Complete** |
| `V004` schema | **Complete** — written, not executed |
| Resolver wired into the kernel | **Complete** |

**19 database verifications are now written and none observed**: 13 from the kernel, 6 added here for the
descriptor immutability trigger, the containment-predicate agreement, `INV-ORG-04`, `INV-ORG-09`,
`org_closure_divergence` and `scope_descriptor_gaps`.

The containment-predicate test is the one that matters most of those six: `scope_ancestor_path @> ARRAY[N]`
and `ScopeDescriptor.withinScopeOf` are **two implementations of one predicate**, DOC-04 §6.6 relies on the
indexed one, and every domain test asserts against the other. A divergence would make the test suite and
production disagree about who can read what — and nothing currently in the build would notice.

---

# Prompt 5 — asset inventory, session 1

**211 tests, 0 failures, 37 skipped.** No warnings. Corpus passes; `docs/` untouched.

This session covers the four `INV-AST` invariants prompt 5 names explicitly, plus the identity rules of
DOC-03 §8.5. The edge aggregate's own invariants, the ownership claim pipeline and merge remain — see below.

## `INV-AST-05` — one owner, enforced by shape

Ownership is one field with one mutator. There is no collection, no `addOwner`, and no path taking a list, so
"more than one owner" is **not representable** — which is what `TST-PLT-004` means by holding "through any
path including bulk and import". A bulk importer calling `assignOwnership` in a loop still cannot produce
two. The test asserts the *shape*: no ownership-touching method accepts a collection, and no `add`-style
mutator exists.

`assignOwnership` also rejects a descriptor whose owning node disagrees with the node being assigned —
otherwise scope-based authorization and ownership queries would return different answers for the same asset.

## `INV-AST-08` — the invariant where the intuitive implementation is wrong

DOC-03 §8.2: auto-correcting a declaration to match observation "is the intuitive behaviour and is wrong…
Silently updating the declaration erases the discrepancy, and with it the finding."

`ExposureClassification` therefore has **no operation that writes the declared value from an observation**.
`observe()` copies the declaration verbatim and derives `conflict()`; changing a declaration is `redeclare()`,
which requires a principal because it is an accountable act. A test asserts no method named
`reconcile`/`correct`/`sync` exists — the absence *is* the invariant.

Two consequences worth flagging:

- **The asymmetry is deliberate.** Declared public but observed internal is *not* a conflict. Over-declaration
  is conservative and yields a score that is too high; only understatement is a finding.
- **Scoring uses the more exposed value while a conflict stands.** The declaration is not corrected, but
  scoring must not use a value the platform has evidence is wrong — that would be knowingly understating
  risk, which product principle 1 forbids in the same breath as treating not-measured as clean.

### Error Prone caught a genuine trap here

The conflict comparison used `Enum.ordinal()`. Flagged, and correctly: that comparison decides whether an
asset enters the conflict queue and which value reaches scoring. **Sorting the constants alphabetically in a
tidy-up would silently invert it, and nothing would fail** — the enum still compiles and every test naming
two levels still passes. Replaced with an explicit `exposureRank` field, plus a test asserting the ranks are
distinct.

## `INV-AST-12` — a coverage signal that cannot be improved by editing

`lastConfirmedAt` has no setter. `confirmSeen` requires a `DiscoveryProvenance`, so there is no parameterless
touch, and it never moves backwards — an older observation arriving late is not evidence the asset was last
seen earlier than it was.

`editDisplayName` exists partly to be pointed at: it is the write path this invariant is about, and a test
asserts calling it leaves the signal unmoved. DOC-03 §8.2 calls this "PP-1 violated through a field nobody
thinks of as a metric".

## `INV-AST-17` — the subtle authorization defect in any graph model

DOC-03 §8.3 describes the attack precisely: a principal authorized for service S follows
`S → exposes → API → published_on → Domain` and reaches a domain owned by another business unit. **Filtering
the query is insufficient because the query started legitimately.**

Three properties, all asserted:

1. **Per node.** The predicate runs on every node reached, including the origin — an exempt origin would make
   the entry point an oracle.
2. **Terminate, do not fail.** An out-of-scope node ends its branch; the traversal continues and returns what
   the principal may see.
3. **Do not disclose the termination.** The caller-facing `Result` has **no** pruned count, and a test asserts
   no method on it is named `pruned`/`hidden`/`withheld`/`omitted`/`truncated`. A "3 results hidden" notice is
   an existence oracle — `SEC-AUZ-020` in a different guise. The count lives only on the `AuditView`, where
   `SEC-PLT-003`'s enumeration detection needs it.

**Edges to pruned nodes are also withheld**, not just the nodes: an edge to a pruned node discloses that the
node exists, which is the disclosure the pruning prevents.

`SEC-AUZ-025`'s scope-independent bound is satisfied because pruned branches do not consume depth — otherwise
reachable depth would vary with the principal's scope, which is itself a signal about what lies outside it.

## DOC-03 §8.5 — identity, and one gap left visible

Three forms of one repository URL — SSH, HTTPS, and a credentialed URL with `.git` and a trailing slash —
normalize to one identity. Without that, "finding history fragments three ways".

Path-parameter collapsing is what DOC-03 calls "the highest-consequence normalization", and it is
**conservative in the correct direction**: under-collapsing fragments an endpoint, which is visible and
fixable; over-collapsing merges distinct endpoints and destroys their separate finding histories, which is
not. `/users/profile/settings` is left alone; `/users/123` and a UUID segment collapse.

**`declaredButNotImplemented()` exposes the gap rather than hiding it.** PURL canonicalization and punycode
normalization are declared by their rules and not yet implemented — identity resolution is only as good as its
weakest normalization, and an unimplemented one silently produces false splits. A test asserts the repository
rule claims no gap and the component rule does. PURL canonicalization belongs to prompt 11.

## What prompt 5 still owes

- **`AssetType` aggregate** — `INV-AST-01` to `-04`, including `permitted_edges` which `INV-AST-14` needs.
- **`AssetRelationship` as an aggregate** — `INV-AST-13` (same tenant), `-14` (permitted edge types), `-15`
  (many-to-many both ways), `-16` (temporal closure). Traversal consumes a minimal edge record; the aggregate
  with its own invariants is not written.
- **Ownership claim pipeline** — `INV-AST-18` (a claim is authorized against the *proposed node*, because
  unrestricted self-service claiming is a data exfiltration path), `-19` (one `PROPOSED` claim at a time),
  `-20` (escalation notifies without assigning).
- **Merge** — `INV-AST-21` to `-24`, especially `-24`: conflicting owners require explicit resolution and the
  merge **must not pick one**.
- **`V005` migration** and the `ast.*` permissions of DOC-07 §5.
- **17 of 24 `INV-AST` invariants** have no test yet. The four named by the prompt plus `INV-AST-07`, `-09`,
  `-10`, `-11` and `-16` are covered.

---

# Prompt 5 — session 2: type registry, edges, claims, merge

**236 tests, 0 failures, 37 skipped.** No warnings. Corpus passes; `docs/` untouched. **All 24 `INV-AST`
invariants now have domain coverage.**

## `INV-AST-18` — a security control, not a workflow step

DOC-03 §8.4 states the attack directly: "Unrestricted self-service claiming is a data exfiltration path:
claim a competitor business unit's repository and receive its vulnerability data."

`confirm()` takes a `BiPredicate<PrincipalId, OrgNodeId>` and **cannot be called without one**. A test
enumerates every `confirm` overload and fails if any lacks the predicate — an overload confirming on
authentication alone is the invariant bypassed, and it is the kind of overload that gets added for a test
fixture and then used in production. A second test captures what the predicate receives and asserts it is the
**proposed node**, not the claimant's own scope.

The predicate is *supplied* rather than evaluated here because `SEC-AUZ-013` makes authorization the kernel's
single contract and this module must not implement its own check.

## `INV-AST-20` — escalation notifies without assigning

`escalate()` returns a notification target and leaves the claim `PROPOSED`. It **cannot** assign ownership,
because it has no access to the asset. DOC-03 §8.4: assigning to an ancestor on timeout "would place
accountability with someone who has no operational relationship to the asset. Findings would route to a
divisional manager who cannot act on them, which trains that manager to ignore the platform. Escalation makes
the gap someone's *problem*; it does not pretend to solve it."

## `INV-AST-24` — merge refuses rather than defaults

`prepare()` returns `Preparation.OwnerConflict` where owners differ, carrying **every distinct owner** so the
resolving principal chooses from the actual set rather than answering a yes/no question about one of them. The
only way past it is `prepareWithOwnerResolution`, which requires both the chosen owner and the principal
accountable for choosing.

Three refusals worth noting:

- **No `preferSurvivor`, `autoResolve` or `forceMerge` exists**, and a test asserts none appears. A default is
  how the ownership decision stops being made.
- **Naming a third node is rejected** — that is a transfer disguised as a merge, and a transfer has its own
  event and audit trail.
- **A zero reversal window is rejected**, because it "makes an irreversible operation look reversible"
  (`INV-AST-23`).

`transferObligations()` returns an explicit checklist rather than claiming to have moved anything: findings,
edges and external identifiers live in other aggregates and modules, and `CON-PLT-015` forbids reaching into
them. A method claiming to have transferred everything would be claiming authority it does not have.

## `INV-AST-13` — why `connect` takes assets, not identifiers

Both endpoints are passed as `Asset` objects because `INV-AST-13` requires them in the same tenant and an
identifier alone cannot establish that. Accepting bare identifiers would push the invariant to a database
constraint that cannot see the tenant of a row in another module's table (ADR-030) — so it would not be
enforced anywhere. An edge spanning tenants makes graph traversal a cross-tenant read regardless of every
other control, **because traversal follows edges**.

## `INV-AST-16` — closing, and one thing rejected rather than ignored

`supersede()` closes; there is no delete, and a test asserts no `delete*` method exists. Closing an edge
**twice** is rejected rather than ignored: the second call carries a different instant, and silently keeping
the first would make the temporal record wrong in a way nothing surfaces.

## Prompt 5 status

| Piece | State |
|---|---|
| `IdentityRule` + normalizations | **Complete**, with unimplemented normalizations declared |
| `ExposureClassification` | **Complete** |
| `Asset` aggregate | **Complete** |
| `AssetGraphTraversal` | **Complete** |
| `AssetType` registry | **Complete** |
| `AssetRelationship` | **Complete** |
| `OwnershipClaim` pipeline | **Complete** |
| `AssetMerge` | **Complete** |

All 24 `INV-AST` invariants have domain coverage. **Still owed by prompt 5:**

- **`V005` migration** — `asset_type`, `asset`, `asset_relationship`, `ownership_claim`, `asset_merge`, per
  DOC-04 §11.3, with the §6.6 descriptor columns and the hash partition counts that **OQ-015 gates**
  (`CON-DAT-035`: changing a hash partition count rewrites every row).
- **`INV-AST-02`** — attribute validation against the type's schema at every write. Needs
  `schema-registry` persistence, which prompt 3 deferred to its first consumer. This is that consumer.
- **The `ast.*` permissions** of DOC-07 §5 and their enforcement points.
- **`AssetGraphTraversal` is not wired to real authorization** — it takes a `Predicate<UUID>`, and nothing
  yet supplies one built from `ClosureBackedScopeResolution`. Until that assembly exists, `INV-AST-17` is
  proven correct in the domain and unproven in the product.

**OQ-015 now blocks a migration rather than a decision.** `V005` is where the irreversible hash partition
counts on `asset` are set, and DOC-15 §5.2's Medium-profile working assumption is recorded but unconfirmed.

---

# Prompt 5 — session 3: V005, and a correction

**242 tests, 0 failures, 43 skipped.** No warnings. Corpus passes; `docs/` untouched.

## Correction: OQ-015 does not gate `V005`

The previous session's note said `V005` sets irreversible hash partition counts on `asset` and was
therefore blocked on OQ-015. **That was wrong.** DOC-04 §11.3.2: "**Partitioning.** None — 100,000 rows at
Extra large." Neither `asset` nor `asset_relationship` is partitioned.

The counts OQ-015 actually gates are on `finding`, `finding_asset_impact` (aligned hash by tenant) and
`component_entry` (hash by tenant, 32) per DOC-04 §22.2 — which arrive with vulnerability management and
composition analysis. **The `V002` comment naming "prompts 5 and 11" was wrong for the same reason and has
been corrected in place**, since a migration comment that misdirects the next reader about an irreversible
decision is worse than no comment.

## `V005` — five engine-level guards

| Guard | Why the engine and not the domain |
|---|---|
| `INV-AST-12` coverage-signal trigger | DOC-04 §11.3.2 names this one of only **two** places a trigger is preferred to domain enforcement: "the invariant protects a *coverage signal* and a domain-layer defect would make a stale asset appear fresh… Cheap to enforce, expensive to miss." Rejects advancement where `discovery_source = 'MANUAL_EDIT'`, and any backwards move |
| `INV-AST-08` conflict derivation | The flag is **stored** (the conflict queue is read far more than exposure is written, so computing on read would make the queue a full scan) and **derived by trigger**, so it cannot disagree with the values it summarises |
| `INV-AST-01` type immutability | Changing a type changes identity rule, permitted edges and attribute schema at once |
| `INV-AST-19` one open claim | A partial unique index on `state = 'PROPOSED'`. The domain asserts it over a candidate set; **the index is what holds when two requests arrive concurrently** |
| `INV-AST-16` no reopen | Reopening a closed edge would make "what was deployed when this finding was open" answerable two ways |

`derive_exposure_conflict` carries an explicit comment about what it must *not* do — write `exposure_declared`
from `exposure_observed`. That is the one line someone would add to "fix" a conflict, and it would erase the
finding.

## A second two-implementations-of-one-rule risk, now covered

`exposure_rank()` in SQL and `ExposureClassification.Level.exposureRank()` in Java are the same ordering
written twice. A divergence would make the conflict queue and the domain disagree about what a conflict *is*,
and nothing else in the build would notice — the same shape as the `scope_ancestor_path @> ARRAY[N]` versus
`withinScopeOf` risk from prompt 4. `exposureRankingAgreesWithTheDomain` iterates every enum constant and
compares against the SQL function.

That makes **two** predicate-agreement assertions now written, both unobserved.

## Grants: the pair, again

No `DELETE` on `asset`, `asset_type` or `asset_relationship`. `ON DELETE RESTRICT` alone permits deleting a
not-yet-referenced asset whose historical descriptors already name it, so the withheld grant is the other
half. The one `DELETE` granted is on `asset_external_identifier`, because a merge moves those rows and the
merge record — not the identifier row — is the history `INV-AST-21` requires kept.

## Prompt 5 status

`IdentityRule`, `ExposureClassification`, `Asset`, `AssetGraphTraversal`, `AssetType`,
`AssetRelationship`, `OwnershipClaim`, `AssetMerge`, `V005` — **complete**. All 24 `INV-AST` invariants have
domain coverage; six now have engine coverage as well.

**Still owed:**

- **`INV-AST-02`** — attribute validation against the type's schema at every write. Needs `schema-registry`
  persistence; `asset_type.attribute_schema_ref` is declared and nothing populates it.
- **The `ast.*` permissions** of DOC-07 §5 and their enforcement points.
- **`AssetGraphTraversal` is still not wired to real authorization.** It takes a `Predicate<UUID>` and nothing
  builds one from `ClosureBackedScopeResolution`. `INV-AST-17` is proven in the domain and **unproven in the
  product** — this is the largest remaining gap in prompt 5 and it is assembly, not design.

**25 database verifications are now written and none observed** (13 kernel, 6 org-scope, 6 asset). Two of
them are predicate-agreement checks whose failure mode is that the test suite and production disagree about
who can read what, or about what counts as an exposure conflict.

---

# Prompt 5 — complete

**254 tests, 0 failures, 43 skipped.** No warnings. Corpus passes; `docs/` untouched.

## `INV-AST-17` is now proven in the product, not only in the domain

`ScopeAuthorizedAssetGraph` builds the per-node predicate from `ClosureBackedScopeResolution`. Until this
existed, the traversal filtered against whatever the caller passed — and a caller passing `id -> true` would
have compiled.

Three properties, each with a test:

- **The DOC-03 §8.3 attack is blocked end to end.** A principal traverses from a service they own; the edge to
  another business unit's domain does not carry them across.
- **An unavailable resolution yields an *empty* traversal, not an unfiltered one.** This is the fail-open shape
  that matters here: a predicate defaulting to `true` on resolution failure would return the whole graph.
- **An `UNCLAIMED` asset is excluded for every principal.** An asset nobody owns has no scope, and including it
  would make it visible to everybody. DOC-07 §9.2 makes unclaimed assets visible only with
  `ast.ownership.claim` in the candidate scope — a different query from following an edge.

Scope is resolved **once per traversal**, not per node: the predicate is still evaluated per node, which is what
the invariant requires, but the permitted set is fixed for the request and cannot change mid-traversal.
Resolving per node would put a resolution call on every edge of the platform's most frequent operation.

## `INV-AST-02` — validated at every write, and rejecting the unknown

A separate validator rather than a check inside a setter, because "at every write" means asset creation, update,
bulk import, migration import and re-resolution after a rule change — a check in one of them is a check in one
of them.

Two decisions worth noting:

- **An attribute with no schema is rejected, not ignored.** Ignoring it means a typo in a field key silently
  discards the value and the user sees a saved form with a missing field.
- **Every finding is returned, not the first.** A caller that must re-submit five times to discover five
  problems will disable validation.

`DECIMAL` requires `BigDecimal` and rejects `Double`, because binary floating point is not associative and a
value feeding a score could not then be recomputed identically (`PRD-RSK-023`).

## A corpus-wide rule encoded once

Adding `AssetPermissions` failed to compile: a module *contract* could not see the authorization kernel. DOC-03
§5.3 row 3 makes Authorization a Published Language to **all** contexts — "a per-context check is how
enforcement points get omitted" — so a module contract legitimately names permissions.

Encoded in the root build for every `:module:*-contract` rather than added per module as each discovers it,
because **rediscovering a corpus-wide rule fifteen times is fifteen chances to resolve it differently.**

Permissions are typed `PermissionId` constants. A test asserts the **declared field type** of each — Error Prone
pointed out that an `instanceof` there was just a null check, and the property that can actually regress is
someone adding a permission as a raw `String`. A string typo yields a permission matching nothing, which
denies, so it looks like a configuration problem rather than a defect and gets "fixed" by widening a role.

## Prompt 5 final status

All 24 `INV-AST` invariants have domain coverage; six also have engine coverage. `IdentityRule`,
`ExposureClassification`, `Asset`, `AssetGraphTraversal`, `AssetType`, `AssetRelationship`, `OwnershipClaim`,
`AssetMerge`, `AttributeValidation`, `AssetPermissions`, `ScopeAuthorizedAssetGraph`, `V005` — **complete**.

Blocks 1 through 3 of DOC-15 §4 are now built: platform kernel, scope descriptors, asset inventory. **Finding
identity — block 4 and the one prompt 6 calls "the highest-risk block in the project" — is next.**

---

# Prompt 6 — finding identity, session 1

⚠ **The highest-risk block in the project.** `PRD-PLT-001` block 4. DOC-03 §10.2 on getting it wrong: "the
team stops believing the number — after which no subsequent correctness recovers the deployment."

**275 tests, 0 failures, 43 skipped.** No warnings. Corpus passes; `docs/` untouched.

## The rescan corpus found a real defect on its first run

This is prompt 6's review point, and it did its job. Two of the eleven cases failed — **both in the "too
specific" direction**, which is the unrecoverable one:

```
1. Code reformatted; line numbers shift → same finding          FAILED
10/11. Reappearance after closure / after five years            FAILED
```

**Cause.** `structuralContextHash` collapsed runs of whitespace to a single space, which is the obvious reading
of "ignore formatting" and is wrong. A reformatter does not only change the *amount* of whitespace, it changes
*where* it is: `find(String id)` becomes `find( String id )`, and collapsing preserves the added spaces.

**Fix.** Remove whitespace adjacent to any non-word character — around parentheses, commas, operators, braces —
while preserving whitespace *between two word characters*, because that is a token boundary and `int x` is not
`intx`. Removing all whitespace would have merged them.

Had this shipped, every reformatting commit would have created a new finding, triage state would have been lost
across the estate, and the counts would have risen for no reason. The failure is only visible against a corpus;
no unit test of the hash function would have caught it, because the hash function was doing exactly what it said.

## `INV-ING-01` — the one unrecoverable invariant with no database enforcement

DOC-16 §4.2's phrase. Three mechanisms in combination, none sufficient alone:

1. **Location** — the fingerprint types are in `ingestion-impl`, so they are off every other module's compile
   classpath (ADR-050).
2. **`FingerprintConfinementTest`** — a bytecode assertion that no class outside `aspm.module.ingestion`
   references them, plus a check that no foreign type declares a `compute*Fingerprint`-shaped method. This is
   the only one of the three that catches a class placed *inside* `ingestion-impl` by someone who did not
   realise why it matters.
3. **Contract surface** — a downstream module can read a digest and cannot make one.

A second computation site would agree on the day it was written and diverge on the first change to either. The
divergence appears as duplicate findings for some sources and not others — **which reads as a parser bug, is
triaged as one, and is not.**

## Exclusions enforced, not documented

`FingerprintInputs.Builder.with()` **rejects an undeclared key.** A caller cannot add `line_number` to a `CODE`
fingerprint or `manifest_path` to a `DEPENDENCY` one, because the key is not in the class's declared set. Three
rescan cases are asserted by the *rejection* rather than by varying a value — there is no parameter to vary,
which is stronger than a test that varies one and checks it is ignored.

`INV-VUL-01` is likewise structural: the tenant is a **constructor argument** to the builder, not a named input,
so it cannot be omitted. A test asserts no finding class lists `tenant` among its declared inputs — if one did,
the invariant would be violable by omission. DOC-03: "The isolation must be in the hash inputs, not merely in
the query filter."

## One heuristic moved to the shared kernel

Path-parameter collapsing was in `asset-inventory`'s domain, and `ingestion` needed the same heuristic for
runtime request paths. Reaching across would have violated `CON-PLT-013` — my own `CrossModuleAccessTest` would
have caught it — and duplicating it is how two implementations of one heuristic stop agreeing. Moved to
`PathNormalization` in the shared kernel, which qualifies on DOC-03 §5.2's terms: a pure function over a string
with no domain model. Product principle 10, one name one meaning one place.

## One annotated suppression, justified

`FingerprintInputs.FindingClass` holds its declared inputs as a `List`, which `ImmutableEnumChecker` flags
because it cannot see that `List.of()` is immutable. Two alternatives were tried and are worse: `String[]` draws
the same warning and deservedly, and moving the data to a static `Map` separates each finding class from its
declared inputs — when the whole point is that a reader checking whether `CODE` excludes line numbers finds the
answer beside the constant. Suppressed with the reasoning recorded, in the annotated and countable form
`SEC-AUZ-051` establishes. Three suppressions exist in the codebase and all three are annotated.

## The honest limit, recorded rather than left for a rescan

Basename plus structural context is stable under a move and under reformatting. It is **not** stable under a
rename that also changes the surrounding code, and nothing available at ingestion time distinguishes "the same
weakness, moved and edited" from "a new weakness in a new file". DOC-03 §10.2 asks for inputs that sit between
the two failure modes rather than for a scheme with neither, so the limit is stated in
`CodeLocationNormalization` and here.

## What prompt 6 still owes

- **DOC-11 §2–10 in full** — the parser framework, quarantine, provenance, the import session state machine, and
  `PRD-ING-022`'s retained raw source record. Only the fingerprint model and its normalization are built.
- **`V006`** — `finding`, `finding_fingerprint_input` (DOC-04 §13.3's narrow retention table), and
  `finding_asset_impact`. **This is where OQ-015 genuinely binds:** `finding` and `finding_asset_impact` are
  aligned hash-partitioned by tenant, and `CON-DAT-035` makes changing a count a full table rewrite.
- **The parser corpora of DOC-16 §7.2** — per parser, per declared source version, including injection content
  in every field that reaches the interface or a model context.
- **`PRD-ING-021` at the parser layer** — the fingerprint path enforces it; no parser exists yet to enforce it in.

---

# Prompt 6 — session 2: V006 and the irreversible decision

**281 tests, 0 failures, 49 skipped.** No warnings. Corpus passes; `docs/` untouched.

## ⚠ The one irreversible choice in the build so far

`V006` sets **32 hash partitions** on `finding` and 32 aligned on `finding_asset_impact`, on the documented
Medium-profile working assumption of DOC-15 §5.2. `CON-DAT-035` makes changing that a full table rewrite of the
largest tables in the platform, and `OPS-DEP-012` requires the basis recorded — it is, at the top of the
migration, with the reasoning for 32 rather than 8 or 128 and an explicit statement that **the choice is
reversible until the first production row exists and not afterwards.**

`hash_partition_counts()` plus a verification test assert the count actually applied, because a comment
recording 32 and a database holding 16 is worse than no comment: the drift would surface as a capacity incident
rather than as a failure.

**OQ-015 remains open and this is the point at which it stops being a documentation matter.** Nothing else in
`V006` depends on the answer; an order of magnitude either way means re-running this migration before any
production deployment.

## Five engine guards, each closing a defensible-sounding shortcut

| Guard | The shortcut it closes |
|---|---|
| `INV-VUL-08` reported severity immutable | Overwriting what the tool reported destroys the ability to see it was changed, and with it the ability to audit the adjustment |
| `INV-ING-01`/`INV-VUL-05` fingerprint immutable to the application | Re-fingerprinting is a **migration** preserving triage state, assignment, comments, exceptions and history — never a recompute-and-replace |
| `INV-VUL-16` no `RISK_ACCEPTED` on a `SECRET` finding | A live credential is not a risk to weigh; its only remediation is rotation. The constraint is class-specific, and a test asserts the same closure is permitted on a `CODE` finding |
| `INV-VUL-11` `FIXED_VERIFIED` needs a verifier and method | A closure claiming verification without either is the closure nobody can defend in an audit |
| `INV-VUL-04` inputs append-only to the application | The retained inputs are the record of how identity was computed, and an editable record of that is not a record |

`findings_without_retained_inputs()` exists because **one** finding lacking retained inputs is enough to make a
whole-estate re-fingerprinting migration incomplete — and that finding is permanently stuck on its creating
algorithm version.

`raw_source_record_ref` is a reference into object storage rather than the document itself: `PRD-ING-022`
requires the raw record retained, and a multi-megabyte scanner report inside the platform's hottest table is a
read amplification nobody budgeted.

## Running total across prompts 1–6

| | |
|---|---|
| Prompts complete | 1, 2, 3, 4, 5 |
| Prompt 6 | fingerprint model, rescan corpus, confinement, `V006` — DOC-11's pipeline outstanding |
| Java sources | 204 |
| Migrations | 6 |
| Tests | 281 passing, 49 skipped |
| Database verifications written | **31**, none observed |
| Build-time structural gates | S1–S6, S8, S9, S13, plus fingerprint confinement |

**What prompt 6 still owes:** DOC-11 §2–10 in full — the parser framework, the import session state machine,
quarantine, provenance, and `PRD-ING-021` enforced at the parser layer rather than only at the fingerprint
boundary. Plus the parser corpora of DOC-16 §7.2, which require injection content in every field reaching the
interface or a model context.

**Prompts 7–20 are not started:** vulnerability management and exceptions, risk and service levels, work
management, assessment and intake, composition analysis, the API surface, read models and reporting,
notification, capacity, integration, knowledge/AI/automation/migration, the interface, deployment, and the
verification pass with its traceability matrix.

---

# Prompt 6 — session 3: the import pipeline

**297 tests, 0 failures, 49 skipped.** No warnings. Corpus passes; `docs/` untouched.

## A test caught a partially-applied rejection

`QuarantinedRecord.discard(null)` set the state to `DISCARDED` **before** validating the reason. The rejection
threw as intended, but the record was already settled — so the next legitimate `discard` failed with "requires a
QUARANTINED record", and the record sat in a state nobody chose.

Fixed by validating arguments before mutating, and the same ordering hazard was present in
`ImportSession.parseFailed` and `rejectBeforeParsing` — fixed there too, before a test found them. **A partially
applied rejection is worse than either outcome**: the caller believes nothing happened and something did.

## `PRD-ING-038` — a parse failure ingests nothing, expressed as a missing transition

DOC-09 §15's edge case: "partial parse results are not normalized, because a truncated record set could be read
as records having been removed." There is no `PARSING → COMPLETED` path and no `parsedPartially` method, and a
test asserts no method named for partiality exists.

`COMPLETED_WITH_QUARANTINE` is reached only from `NORMALIZING` — quarantine is per record *within a fully parsed
document*, which is a different thing from a truncated document. The distinction is the whole point: absence in
a complete parse means absence; absence in a truncated one means nothing.

The terminal state is **derived from the quarantine count**, not passed in, so a caller cannot report `COMPLETED`
on a session that quarantined records and thereby hide the queue `PRD-ING-039` requires to be resolvable.

## `PRD-ING-041` — no total accessor exists

"A total of 39,997 out of 40,000 is not actionable; knowing that three were quarantined for one reason is." The
session exposes counts by disposition with all six always present including zeroes, and a test asserts **no
`total()` accessor exists** — because an accessor invites reporting only the total.

## `PRD-ING-039` — quarantine that cannot be resolved is deletion with extra steps

That sentence is DOC-11 §9's, and it shaped the type: the raw content is required at construction so a record is
correctable *without the source file*, and a `SCHEMA_VALIDATION` failure that names no field is **not
constructible** — "failed validation" is not correctable, so it is not resolvable, so it is deletion.

A discard requires a stated reason, because a record that quietly vanished is an **unknown** coverage gap
whereas one discarded for a stated reason is a **known** one.

## `PRD-ING-032`/`-033` — asset class, and the gaming path it closes

Container findings are classified by **subject**, with no default: an application dependency inside an image is
`APPLICATION`, a base image CVE is `INFRASTRUCTURE`. "One class for both routes half of them to someone who
cannot act" — the application team changes a manifest, the platform team changes a base image.

No per-finding override exists, and a test asserts no `set*`/`override*`/`reclassify*` method appears. DOC-28
§13.2's gaming path: "a tenant reclassifying infrastructure findings as application would inflate or deflate
their application posture at will."

## Position

| | |
|---|---|
| Complete | Prompts 1–5 |
| Prompt 6 | fingerprint model, rescan corpus, `INV-ING-01` confinement, `V006`, import session, quarantine, asset class |
| Prompt 6 outstanding | the parser registry and format specifications of DOC-11 §4–5, asset anchor resolution §6, and the per-parser fixture corpora of DOC-16 §7.2 |
| Not started | Prompts 7–20 |
| Java sources / migrations | 210 / 6 |
| Tests | 297 passing, 49 skipped |
| DB verifications written, unobserved | 31 |
| Defects found by tests written this session | 3 (one partially-applied rejection, two pre-emptively in the same shape) |

**Defects the corpus's own discipline has surfaced so far:** two vacuous ArchUnit rules, one constant-inlining
blind spot, one name-heuristic collision, one record-with-array-components equality trap, one `Enum.ordinal()`
dependency in a security comparison, one port in the wrong layer, two rescan-corpus stability failures, one
partially-applied rejection. Nine, none of which a reading review would have found.

---

# Prompt 6 — complete

**310 tests, 0 failures, 49 skipped.** No warnings. Corpus passes; `docs/` untouched.

## `PRD-ING-027` — three shapes of "best effort", all closed

`acceptsFormatVersion` is exact set membership. A test asserts all three fallbacks a well-meaning implementation
reaches for are rejected: patch-level (`2.1.1` against `2.1.0`), prefix (`2.1`), and highest-known-version
(`3.0.0`). Each is "a best-effort parse wearing a different hat", and DOC-11 §4 states the cost: "partially
mapped findings that appear valid."

The rejection message names the supported versions **and states why the attempt was not made**, because
"unsupported" on its own invites a request to try anyway.

## `PRD-ING-040` — no middle value, and absence counts as a gap too

`mapSeverity` returns `Optional.empty()` for an unrecognised source value. "A defaulted severity is
indistinguishable from a reported one and silently corrupts prioritization for every finding from that source."

It also returns empty for `null`, which is `PRD-ING-021` in the same method: a field absent in the source is
null, the parser does not infer, and an absent severity is a gap exactly as an unrecognised one is.

## `PRD-ING-029` — resolution is a total function

`AssetAnchorResolution.resolve` never returns empty and never throws for an unresolvable anchor. Step 5 always
succeeds. "Discarding is silent data loss at the point of least detectability. Creating an unclaimed asset routes
the problem into a visible queue with an escalation path."

`Step` is an enum rather than a boolean, because `PRD-ING-030` needs creation distinguishable from a match: "a
session that created 400 assets resolved almost nothing and should be investigated before its findings are
trusted. Without the distinction, that session looks identical to one that matched cleanly." The value that
**failed** to match is retained too — without it, a template pointing at the wrong column looks the same as a
normalization rule that stopped matching.

## `PRD-ING-031` — the file-asserted scope is rejected, not ignored

A source-asserted organizational scope raises rather than being silently dropped. "A file-asserted scope is a
cross-scope injection primitive requiring only that the file be edited" — product principle 4 applied to a
document instead of an API request, and the document is the easier of the two to edit. Rejected loudly because an
operator needs to know the file tried.

## Prompt 6 final status

`FingerprintInputs`, `FindingFingerprint`, `FingerprintComputation`, `CodeLocationNormalization`,
`PathNormalization`, `ImportSession`, `QuarantinedRecord`, `AssetClassAssignment`, `ParserDefinition`,
`AssetAnchorResolution`, `V006`, the rescan corpus, and the `INV-ING-01` confinement assertion — **complete**.

**Blocks 1 through 4 of DOC-15 §4 are built:** platform kernel, scope descriptors, asset inventory, finding
identity. Every mechanism `PRD-ING-001` marks as capturing unreconstructable data now exists except the work item
transition log, which is prompt 9.

**Outstanding from prompt 6, and deferred deliberately:** the per-parser fixture corpora of DOC-16 §7.2. They
require a real document per source tool per declared version, including "injection content in every field that
reaches the interface or model context". Writing fixtures against invented document shapes would produce a corpus
that tests my guesses about SARIF rather than SARIF, and `TST-ING-001` exists to catch a *real* tool changing its
output. The parser framework is ready to receive them; the fixtures need the actual tools.

## Position

| | |
|---|---|
| Complete | Prompts 1, 2, 3, 4, 5, 6 |
| Not started | Prompts 7–20 |
| Java sources / migrations | 210 / 6 |
| Tests | 310 passing, 49 skipped |
| DB verifications written, unobserved | 31 |
| Defects surfaced by the build's own discipline | 9 |

---

# Prompt 7 — vulnerability management, session 1

**331 tests, 0 failures, 49 skipped.** No warnings. Corpus passes; `docs/` untouched.

Three fixed state machines: finding (DOC-09 §6), secret finding (§7), risk exception (§8). All three are
`[fixed]` rather than tenant-configurable, because `PRD-WRK-035` records that "a tenant-editable version could
remove the invariant through configuration" — and these are the invariants governing closure semantics, which
DOC-03 §5.4 calls "the one thing the platform must get right".

## The secret machine's two deliberate differences, both asserted

DOC-09 §7 exists as a separate machine for one reason: **"remediating the *code* does not remediate the
*exposure*"** (`INV-VUL-19`). A credential deleted from the working tree is still live in every clone.

| Difference | Test |
|---|---|
| **No `EXCEPTED` state** (`INV-VUL-28`) | Enumerates every state and asserts none is named `EXCEPTED`, plus `deliberatelyAbsentStates()` records the absence on the type — an absence is only enforced if something checks for it |
| **The clock does not pause on dispute** | Asserts `SecretFindingState.DISPUTED.clockRuns()` is true *and* `FindingState.DISPUTED.clockRuns()` is false, so the two genuinely differ |

The second is the difference most likely to be "fixed" by someone porting the ordinary finding's pause logic
across, so it is stated on the enum and asserted against its counterpart rather than left in a comment.

`VALIDITY_UNKNOWN` is treated as a **live** exposure: "treating unknown as inactive is the assumption that
produces incidents." Product principle 1 in a specific and costly form.

`HISTORY_PURGE_PENDING` exists because rotation-attested-but-still-in-history is "a real and common state…
conflating them either closes prematurely or holds the finding open after the risk is gone."

## `INV-VUL-28` is checked at construction, not at approval

An exception for a `SECRET` finding is **unconstructible**, not merely unapprovable. A pending request would
imply the answer might be yes, and the message says why it cannot be: the secret machine has no `EXCEPTED`
state, so an approved exception would have nowhere to put the subject.

The `subjectIsSecretClass` parameter is a constructor **guard** and deliberately not retained as a field —
Error Prone flagged it unused, and keeping it would invite a later reader to branch on it and reintroduce the
path.

## `INV-VUL-23` — bounded, not merely present

Expiry is mandatory *and* checked against the tenant's configured maximum: "an exception expiring in 2099 is an
unbounded one with extra steps."

`review()` records an outcome and **does not touch expiry**. A review that could extend it would make the bound
reachable by repetition; renewal exists for that and requires a new approval. `renew()` records the successor's
id, because "three renewals of a 90-day exception is a 360-day acceptance, and it should read as one".

`expire()` before `expiresAt` is rejected — shortening an approved acceptance without a reason would bypass the
revocation that requires one.

## Two separations that look like ceremony and are not

**`APPROVED` is not `ACTIVE`.** Only `ACTIVE` suppresses the obligation. Treating approval as activation would
suppress before the subject transitioned, leaving a finding whose clock is running and whose obligation is
already waived.

**`Controls` is a sealed pair, not a nullable list.** An empty compensating-controls list is rejected in favour
of `NoneDeclared(justification)`, because "an empty list and 'we accept this with no mitigation' are different
statements, and only the second is a decision".

## `EXCEPTED` returns only to `TRIAGED`

`FindingState.EXCEPTED.permittedSuccessors()` is exactly `{TRIAGED}`. An `EXCEPTED` finding that could go
straight to `RESOLVED` would let an expiring exception *close* the finding it was suppressing — and DOC-09 §8
requires the subject to auto-reopen.

## Position

| | |
|---|---|
| Complete | Prompts 1–6 |
| Prompt 7 | the three state machines and their guards; `INV-VUL-11`/`-16` already at the engine in `V006` |
| Prompt 7 outstanding | the `Finding` aggregate itself with impact-derived state (`INV-VUL-09`), suppressions, `V007` for exceptions with the `INV-VUL-26` constraint trigger, and the `vul.*`/`exc.*` permissions |
| Not started | Prompts 8–20 |
| Tests | 331 passing, 49 skipped |
| DB verifications unobserved | 31 |

---

# Automated verification — and what running it found

`./verify.sh` at the repository root. One command, no arguments, needs only `JAVA_HOME` on a JDK 25+.
Six stages, stopping at the first failure: register regeneration · corpus validation · compile with Error
Prone at ERROR · build-time structural gates · domain and invariant tests · **the database suite against a
real PostgreSQL**.

## The skip was the bug

Stage 6 previously called `assumeTrue` when no database was reachable, so it **skipped**. Across seven
prompts that meant 31 verifications were written and **none had ever executed** — a whole file passing by not
running, which is the vacuous-check failure prompt 2 was written to expose, reproduced at file scale.

`io.zonky.test:embedded-postgres` starts a real PostgreSQL **as the current user, with no docker daemon and
no root**: the binaries are a Maven artifact and `initdb`/`pg_ctl` have never needed privilege. There was
never a reason for stage 6 to be optional. `requireDatabase()` now **asserts** rather than assumes — a
startup failure is an environment defect and deserves a red build.

**Coverage limit, stated.** The embedded binaries reach 17.5.0; ADR-049's floor is 18 for native `uuidv7()`.
A test-only shim in `src/test/resources/db/testonly/V000__uuidv7_shim.sql` supplies it, and documents that
native `uuidv7()` semantics are the **one** thing not covered — everything else asserted here is PG13-or-
earlier behaviour. DOC-04 §22.4 accepts application-side generation anyway, so nothing depends on the
native function's semantics.

## Six defects found in the first minutes of running

### 1. RLS on a partitioned parent does not protect its partitions — REAL, and serious

`apply_tenant_isolation` enabled and forced RLS on the parent. **A partition is a table: only its own
policies apply to it.** `SELECT * FROM audit_event_2026_08` would have returned **every tenant's rows**.

This is `CON-DAT-012` unsatisfied on the largest tables in the platform, and it is exactly the shape DOC-24
§5.1 warns about — the isolation *looks* enforced because the parent is enforced. `apply_tenant_isolation`
now recurses over partitions.

### 2. Every future partition would have been a fresh hole — REAL

`ensure_audit_partitions` creates next month's partitions on a schedule. Each one arrived **un-isolated**,
because RLS is not inherited. A cross-tenant read path opening every month, on a timer. Now isolated on
creation.

### 3. `audit_event`'s scope columns had no immutability guard — REAL

It declares them natively (DOC-04 §20.1) rather than via `add_scope_descriptor()`, so it had the columns
and not the `CON-DAT-009` trigger. The append-only grants made it unwritable *in practice* — but
`payload_eraser` holds a column-scoped `UPDATE`, and "stronger in practice" stops being true the moment a
grant widens.

### 4. A migration ordering failure only sequence exposes

`reject_scope_descriptor_change` was defined in `V004` and needed by `V002`'s table. A function defined later
cannot be used earlier. Moved to `V001` with the other enforcement primitives, where it belonged anyway —
it is a shared-kernel concern, not an organization-scope one.

### 5. A `RAISE` format string that never compiled

`RAISE EXCEPTION '... ''%%'' ...', a, b, c` — PL/pgSQL reads `%%` as an **escaped percent**, not a
placeholder. One placeholder, three arguments, "too many parameters specified for RAISE". The function
`reject_code_change` had never compiled, so `INV-ORG-04` was **entirely unenforced** and nothing said so.

### 6. A name-based conformance check, for the third time

`scope_descriptor_gaps()` looked for a trigger named `tr_<relname>__immutable_scope`. PostgreSQL propagates
a row trigger to partitions **under the parent's name**, so it reported every partition as unprotected when
all of them were fine. Now checks `tgfoid` — the function — rather than the name.

**This is the third defect in this build caused by asserting a naming convention instead of the property it
stands for.** The others: an ArchUnit rule whose `(*)` was a plain wildcard, and a descriptor lookup keyed
on a class-name suffix. The pattern is worth naming: *check the property, not the name that usually
accompanies it.*

## Three test bugs, distinguished from product defects

A duplicate `asset_type` code across two seeds in one transaction; a missing savepoint, so an expected
constraint violation aborted the transaction and the second half of the assertion failed with "current
transaction is aborted" — which reads as a product defect and is not; and an `INV-AUD-01` assertion that did
not exclude the **table owner**, who always holds every privilege. In production the owner is
`migration_runner`, an *enumerated* bypass under `SEC-TEN-008`. The assertion now targets ordinary roles,
which is the property that matters.

## Verified state

```
architecture-tests                     42 tests   0 failed  18 skipped
kernel-verification                    31 tests   0 failed   0 skipped
module/asset-inventory-impl            64 tests   0 failed   0 skipped
module/ingestion-impl                  47 tests   0 failed   0 skipped
module/organization-scope-impl         56 tests   0 failed   0 skipped
module/vulnerability-management-impl   21 tests   0 failed   0 skipped
platform-kernel/audit-impl             31 tests   0 failed   0 skipped
platform-kernel/authorization-impl      6 tests   0 failed   0 skipped
platform-kernel/rules-engine-impl      21 tests   0 failed   0 skipped
platform-kernel/schema-registry-impl    4 tests   0 failed   0 skipped
platform-kernel/tenant-context-impl     8 tests   0 failed   0 skipped
TOTAL 331 tests, 0 failed, 18 skipped
```

The 18 skips are the DOC-16 §5 isolation paths whose subsystems are not built, each `@Disabled` naming the
prompt that implements it (`TST-TEN-001`). The summary prints a note saying skips are debts rather than
passes, because the whole lesson of this session is that a skip nobody reads is a verification nobody has.

**Defects surfaced by the build's own discipline: 15.** Six of them existed only because the database suite
had never run.

---

# Prompt 7 — complete

`./verify.sh`: **359 tests, 0 failed, 18 skipped.** 38 of them run against a real PostgreSQL. Corpus passes;
`docs/` untouched.

## `INV-VUL-09` — the state is derived, so the wrong state is unrepresentable

There is no `setState`. `Finding.state()` computes from impact statuses, and a test asserts no
`setState`/`transitionTo` method exists. DOC-03 §10.3: "Deriving aggregate state from impacts rather than
storing it independently prevents the state most likely to be wrong: a finding marked resolved while an impact
remains open."

`openImpactCount()` exists because **"six of eight fixed is useful information that a single aggregate status
destroys"** — and partial remediation is the normal case for a dependency finding across many services.

`INV-VUL-08`'s first impact is a **constructor argument**, so a zero-impact finding never exists even
transiently. A transient one would be visible to a concurrent read and would derive its state from an empty set.

## `INV-VUL-13` — the invariant that has destroyed real deployments

DOC-03 states it plainly: "one failure auto-closes every dependency finding for a project. This has occurred in
production deployments of comparable tooling, and it destroys data trust irrecoverably."

`autoResolve` takes a `CoverageEvidence` with five conditions and **there is no parameterless close** — a test
asserts no `autoResolve` overload takes fewer than two arguments. A refusal names the unmet conditions, because
a refusal nobody can diagnose gets worked around.

A test drives all five conditions individually: each one alone must block closure, because DOC-09 §6's
`auto_resolve` guard is a conjunction and a disjunction would satisfy every naive reading of it.

## `INV-VUL-12` — the prior closure is what makes recurrence meaningful

Reappearance reopens the same record, increments recurrence, and **retains the prior closure**. That retention
is what distinguishes a regression from a re-triage: reappearing after `FIXED_VERIFIED` is a regression;
after `FALSE_POSITIVE` it is a triage question. Without it, `recurrence_count` counts two different things.

Reappearance **while excepted** advances detection and does not transition — "the exception is what suppresses
the obligation, not the absence of detection".

## `V007` — `INV-VUL-26` as a deferred constraint trigger

A `CHECK` would have worked per row. A **constraint trigger** is `DEFERRABLE INITIALLY DEFERRED`, so it runs at
commit — which matters because a migration import writes request and approval as two statements in one
transaction, and an immediate check would reject an intermediate state that the commit corrects. The
verification asserts the failure arrives at `commit()`, not at the `INSERT`.

Both halves are tested: self-approval is rejected, **and independent approval commits.** A trigger that
rejected everything would pass the first assertion and be useless.

Also at the engine: `ACTIVE` without an approver is rejected, so a migration import cannot fabricate an
exception nobody approved; at most one live exception per subject, because two would make "is this suppressed"
answerable two ways; and a suppression must be bounded and justified, because an already-expired suppression
"is either a mistake or an attempt to look bounded while suppressing nothing".

## The four permission separations, each with its reason recorded

DOC-07 §5.2 annotates why each exists, and the annotations are the design:

| Separate permission | Why folding it in breaks something |
|---|---|
| `vul.finding.severity.adjust` | "It changes risk and is an **anti-gaming control point**" — folded into triage, anyone who can move a finding could lower its score |
| `vul.finding.dispute.adjudicate` | One permission for both lets a disputer adjudicate their own dispute — a unilateral close |
| `vul.finding.bulk` | "Bulk is how a scope error becomes ten thousand scope errors"; per-item evaluation still applies (`INV-WRK-12`) |
| `exc.exception.approve` | "Self-approval makes the exception process a formality **and is the first control an auditor tests**" |

`separationOfDutiesPairs()` declares the constraints as data, because `SEC-AUZ-039` makes separation of duties
*enforced rather than advised* and that needs something enforceable.

# Prompt 8 — risk scoring and service levels

DOC-28 in full, `V008` for DOC-04 §18, and the service level clock of DOC-09 §9.

### A defect I put in and took out: `RiskScore` did not implement the specified model

The first version of `RiskScore` was written from the outline before §4.2, §5 and §6 had been read closely. It
was wrong in three ways that a passing test suite concealed, because the tests asserted what the code did:

| What I wrote | What DOC-28 specifies |
|---|---|
| Factors `EXPL, SEV, CRIT, EXPO, DATA, CTRL` | `SEV, EXP, KEV, EXPO, CRIT, DATA, REACH` (§4.2) — `KEV` and `REACH` absent entirely, and two invented |
| Weights required to sum to exactly 1 | "Weights sum to **1.10** by design; the formula normalizes" (§5.7). My check would have rejected the document's own defaults |
| A plain weighted sum | `100 × normalize(raw) × (0.4 + 0.6 × max(EXPO, CRIT, DATA))` (§6.1) — the contextual multiplier, the `max`, and the 0.4 floor were all missing |

Every one of those is a `PP-10` violation of the "one name, one meaning" kind, and the third is the substance of
`PRD-RSK-016`, whose rationale explains each element of the shape as an answer to a specific failure. Rewritten
against the document, with `Factor` carrying the per-factor bounds of §7.2 so the code and the schema `CHECK`
cannot disagree about them.

**The lesson is narrower than "read the document".** I had read it — I had not read the *sections that define the
arithmetic* before writing the arithmetic. An outline tells you a document has a formula; it does not tell you
the formula.

### `PRD-RSK-035` and `PRD-RSK-036` — the two behaviours that look like bugs

Both have a nested test class named so a future reader meets the reason before the assertion.

A **score increase** bringing a shorter policy recomputes `due_at` **from the original start** and may produce an
immediately-breached clock. A finding open six weeks that turns out to be actively exploited *is* past a
three-day deadline; restarting would hand it a fresh three days, "which is the wrong direction entirely".
`isBreachedAt` computes rather than waiting for a sweep, which is what makes the immediate breach observable
instead of dependent on a scheduled job.

A **score decrease** never extends. `recomputeForScoreChange` returns `false` and changes nothing — silently,
not by raising, because a score decrease is legitimate and frequent and raising would teach callers to stop
reporting them. It is the *extension* that must not follow, and extension needs `extend()` with an approver and a
reason.

The two interact: a recomputation preserves paused time already granted, so a team is not charged for somebody
else's delay even while the deadline shortens underneath them.

### `PRD-RSK-026` is a type, not a report query

DOC-28 §8.3 calls it "the most important requirement in this document. Every other requirement makes the score
correct; this one makes it honest." `PostureImprovementAttribution` has a mandatory verdict and
`presentableAsImprovement()` returns **false** for `COVERAGE_LOSS` even though the number genuinely fell — because
the claim being made is "we improved", and that claim is unsupported when the measured population shrank.

Same reasoning made `ClosureFigure` a type with **no accessor returning the total alone** (`PRD-RSK-042`). A
reporting convention is satisfied by remembering; a type without the accessor is satisfied by compiling. A test
asserts no method named `totalClosed` exists.

`INDETERMINATE` is kept distinct from `COVERAGE_LOSS`: there a claim exists and is wrong, here there was never a
figure to compare.

### Attribution carries a residual rather than balancing its own books

Per-factor deltas are counterfactuals — recompute with one factor held at its old value. They **do not sum to
the total**, because the contextual multiplier makes the formula non-additive. `interactionResidual()` reports the
difference explicitly, and a test asserts components plus residual reconstruct the total exactly. Distributing it
silently across factors would present an arithmetic convenience as a finding about the world.

`POPULATION_SHIFT` is separated from `INTELLIGENCE_UPDATE` for `EXP` on the population version, because it "is
the one cause where nothing about the finding changed, and conflating it with a real change destroys trust in
attribution generally".

### Rate detection requires both comparisons, and the gap is named

`ScoreReducingRateAnomaly` fires only when a rate exceeds **both** the principal's own trailing rate and the
node's peer median. Self-comparison alone misses a principal who always operated at a gaming rate; peer
comparison alone misfires on a triage specialist whose role legitimately concentrates one action, and a weekly
false flag trains the reviewer to dismiss the signal.

**The cost is stated in the class:** a coordinated shift across a whole node is not detected by this control.
Naming it beats leaving a reader to assume the detector is complete.

Threshold arithmetic, not a learned model — `PP-2`. A principal told their closure rate was flagged is entitled
to the arithmetic, and "the model found it anomalous" is not an answer that survives being challenged.

### `V008`

`risk_score` is **range**-partitioned monthly, so unlike `finding` it carries no `OQ-015` dependency: extending a
range scheme is adding a partition. Each new partition is isolated explicitly, for the reason found in prompt 4 —
RLS on a parent does not protect a direct query against a child.

The weight bounds of §7.2 are a per-factor `CASE` rather than one `0..1` range, because the bounds *are* the
control: a generic range would permit zeroing `EXP` and `KEV`, which is the configuration `PRD-RSK-020` exists to
reject.

`score_reducing_action_event` is append-only with no `UPDATE` even for `migration_runner`. Rate detection over a
record its subject can amend detects nothing.

One test bug found and fixed by running it: the score-immutability verification seeded `coverage_confidence` at
`HIGH` and then "updated" it to `HIGH`, so `IS DISTINCT FROM` was false and nothing was rejected. Reseeded at
`INSUFFICIENT`, which is the direction that matters — the one that would make an unpresentable score presentable
without acquiring any data.

---

# Prompt 9 — work management, session 1

Workflow as data, the evaluation order of DOC-09 §2.1, and the append-only transition log. Collaboration,
automation, links and `V009` are sessions 2 and 3.

## The transition log is why this prompt is a build block

DOC-03 §13.2, quoted because the reasoning is the whole justification for building it before its only consumer:
"*How many items were in remediation at the end of last quarter* is answerable only from a transition record; it
is not derivable from current state with a modification timestamp. A platform omitting this in v1 and adding
workload analytics in v2 finds its historical charts begin on the day the log was introduced, with the preceding
period permanently unavailable."

`TransitionLog` **computes** the sequence, the duration in the previous state and the `from` state from the entry
at the tail. A caller supplying them would eventually supply one that disagrees with the log, and a disagreement
in an append-only record is unfixable by construction.

`sla_clock_running` is recorded **on the transition**, not resolved from the state. The state's flag is tenant
configuration and can change; a historical service-level computation must use the flag as it was. Same shape as
the calendar snapshot on the service level clock, applied to a different piece of configuration.

Rehydration rejects a gap in the sequence. Every duration after a gap is wrong by the missing interval **while
still looking arithmetically sound**, which is the kind of corruption nothing downstream detects.

The prompt asks for a test asserting no delete path at any privilege. It checks the *shape* of `TransitionLog` and
`WorkItemStateTransition` reflectively — `delete`, `remove`, `clear`, `truncate`, `set`, `replace`, `purge` — rather
than trusting the absence to survive a future edit.

## `PRD-WRK-031` — the order is the control

Scope precedes permission so a permission denial cannot confirm that an out-of-scope object exists
(`SEC-AUZ-020`), and a scope failure returns **404 with the detail `"not found"` and nothing more**. One test
pins the whole ordering at its most consequential point: an out-of-scope caller, holding no permissions,
requesting an event that does not exist. If any later check ran first the response would differ.

`TransitionEvaluation` is pure — it reads no repository and writes nothing — so a denial can be explained to the
person who received it. It covers steps 2–8 and reports which step denied; steps 9 and 10 belong to the
aggregate's transaction, because `PRD-WRK-032` requires them atomic together and a decision function cannot carry
a transaction.

An `UNDEFINED` guard denies, and says so differently from a guard that evaluated false. "The guard said no" and
"the guard could not be evaluated" need different fixes.

## A port the build was right to demand

The shared evaluator lives in `rules-engine-impl`, which work-management cannot reach — the compile classpath
rejected the first attempt. Rather than weaken the boundary, `ConditionEvaluation` was added to the rules-engine
**contract** with a thin adapter in the impl. Same reasoning as `ScopeResolver` in prompt 4, in the opposite
direction: there the implementors were external, here the consumers are.

The module's own test uses a three-valued double, because a test reaching around the boundary would assert
against a dependency the production code cannot have. That exactly one implementation of the port exists in the
whole build is asserted in `architecture-tests`, which is the only place that can see every module at once.

## `PRD-WRK-034` — four checks, each naming a specific silent breakage

Validation returns findings, not a boolean: an activation refused with "invalid" leaves a tenant administrator
editing a state machine by guesswork. The worst of the four is the trap — "a state with no outbound transition:
items enter and cannot leave, and the defect surfaces days later as stalled work with no visible cause".

Three structural checks were added beyond the document's four: duplicate state identifiers, two transitions on
the same event from the same state (which one fires would depend on ordering, and they may carry different guards
and permissions), and an initial state not among the definition's states.

**One check fails open, and says so.** DOC-09 §3 requires a reason on any transition into a terminal state that
is *not a success outcome*, but DOC-04 §16.1 gives states a category and no outcome polarity — nothing in the
schema records which terminal states are successes. Rather than invent a column the corpus does not define, the
check recognises the codes the shipped defaults use and stays silent on codes it does not know. A tenant whose
rejection state is called something else gets no finding. Closing it properly needs an outcome polarity on
`workflow_state`, which is a corpus change and not one to make from inside an implementation session.

## Two blocking-attribution enumerations, mapped rather than duplicated

DOC-04 §16.3 lists six values (what stopped the work); `PRD-RSK-034` lists three (who is accountable). They are
genuinely different questions — one decides what to fix, the other decides whom to escalate to — but two
overlapping enumerations is what PP-10 warns about, so `TransitionBlockingAttribution.escalationAttribution()`
holds the mapping once and the clock never infers it.

`CAPACITY` maps to the security function, not the requester. Mapping it elsewhere would suppress the escalation
that ought to fire, which is how a backlog becomes invisible: every item paused, nobody escalated.

---

# Prompt 9 — session 2: collaboration

`PRD-WRK-019`, and the invariants `INV-WRK-07` through `INV-WRK-10`. This is the phase the prompt's review point
names: "if the comment experience is materially worse than a chat tool, users will record states here and
converse elsewhere."

## `INV-WRK-10` — an allowlist, and no way in for markup

`ConstrainedRichText` **does not accept an HTML string.** No constructor takes markup, there is no `sanitize`
method, and a test asserts both. Sanitization is a denylist wearing a different name: the input space is HTML and
the attacker gets to explore all of it. Here the document is seven node types and nothing else can be
represented, so a bypass needs a defect in a parser over a grammar with seven productions rather than a gap in a
filter over an open one. **Content arrives as nodes and the renderer emits markup — the direction of the
conversion is the control.**

Two places where a closed grammar still leaks and both are closed explicitly:

- **The code node's language hint** reaches a class attribute in the rendered output, and code content is the one
  thing deliberately not escaped (a security discussion pastes payloads). Constrained to a short identifier.
- **No arbitrary link node.** Only an item reference matching a code pattern. An arbitrary URL in a comment is a
  phishing vector inside a trusted surface, aimed at the population PP-7 describes as having the narrowest
  permissions and the least training.

The cost is stated in the class: tables, images and embedded HTML from an incumbent tracker's export cannot be
represented, and migration's answer is a lossy conversion that records what it dropped, never a widening of the
grammar. A comment that renders slightly worse is a smaller problem than a comment that runs.

`plainText()` is the single zero-argument String-returning method, asserted by a test. "Which code paths can send
a comment to a model" is a question somebody will have to answer, and `PRD-AIC-008` excludes comment content from
AI context unless the tenant permitted it.

## `INV-WRK-08` — redaction keeps the original *before* replacing the body

`redact()` pushes the current body into the revision history and only then substitutes the marker. A redaction
that discarded the original would be the selective deletion the invariant forbids, with extra steps.

Editing to empty is refused and the diagnosis points at `redact`, because an empty edit is deletion by another
route. A redacted comment is frozen — an edit afterwards would place new content under the original author's
name.

`Comment.migrated(...)` is a distinct factory rather than a boolean parameter, so every migration call site is
visible in a search for the method name. DOC-26 §8 names migration authorship as an abuse case — "the capability
that preserves history could fabricate a record of a decision never made" — and a control set by an argument
somebody can forget to pass is not one.

## `INV-WRK-09` — two filters, and the second is the control

`autocomplete()` filters what is offered; `resolve()` filters what is **accepted**. Only the second matters:
PP-4 says a filtered picker "is a usability feature, never an authorization control", because the request that
posts a comment carries whatever identifier the client put in it. An implementation with only the picker has a
control any HTTP client bypasses.

An unauthorized mention is **rejected, not silently stripped** — silent stripping lets an author believe they
drew somebody into a discussion who was never notified. The rejection reports a *count* and withholds the
identifiers, because naming them would confirm which of a submitted list exist.

Visibility is re-checked at render time too. A principal legitimately mentioned last quarter may be outside a
later reader's scope.

## The timeline is a projection, and a redaction appears in it

Built on read from the transition log, the comments, and any further event sources — `PRD-WRK-019`'s
extensibility note requires a new event kind to need no schema change. A separate timeline table would need a
write on every event and would drift the moment one write failed.

A redacted comment appears **as a redaction with its stated reason**, not as an absence. A timeline that silently
omitted redacted comments would let selective redaction reconstruct a different history — the thing the invariant
exists to stop. The redaction's own snapshot of the original is not additionally listed as an edit, which would
read as a change that never happened.

Same-instant entries have a stable tiebreak. A state change and the automated action that caused it are written
in one transaction, and a timeline that reordered itself between two reads is one a reader stops trusting.

## Small decisions with reasons worth keeping

- **`SHADOW` participants are excluded from the notification audience.** A shadow is observing to learn;
  including them in every notification is how a learning mechanism becomes a mail filter rule.
- **The audience is deliberately not scope-filtered here.** Notification is a subscriber (`PRD-WRK-037`) and
  applies the reader's own scope on delivery; filtering twice produces two places that can disagree.
- **Read state is monotonic.** Two tabs reading in either order must not resurrect dismissed notifications — and
  monotonicity is what makes the write-behind cache DOC-04 §16.5 suggests safe to adopt later.
- **Never-read means all-unread**, not nothing. The opposite is the arithmetic that hides an item from the person
  it was just assigned to. A principal's own actions are excluded, or the count teaches people to ignore it.
- **Blocking cycles are detected; `RELATES_TO` cycles are not treated as cycles.** A cycle of blocking is a set of
  items none of which can ever start, and it presents as work that quietly never gets picked up.

---

# Prompt 9 — session 3: the aggregate, automation, and `V009`

Completes prompt 9. The `WorkItem` aggregate, `AutomationRule`, `SavedView`, `BulkOperation`, the concurrency
rows of DOC-09 §18, and `V009` for all of DOC-04 §16.

## `INV-WRK-06` — the mistake this guards is one line shorter than the right answer

`WorkItem.createFor(...)` takes the **subject's** scope descriptor, and the parameter is named
`subjectScope` for that reason. Taking the creator's scope is the shorter code and lets a broad-scope user
create an item nobody in the subject's own tree can see.

Standalone work — governance, enablement — is a **separate factory**, because there the creator does supply the
scope. One exception to an invariant is worth making explicit at the call site rather than inferring it from an
argument being null.

## `INV-WRK-05` and `INV-WRK-15` are enforced by field shape, not by checks

A single `assigneeId` field with no collection anywhere: there is nothing to add a second assignee to, so the
invariant cannot be violated by a caller who did not know about it. A test asserts the accessor returns
`Optional`, because a collection return type here would be the invariant violated in the signature.

Derived and manual effort are two fields and `recomputeDerivedEffort` never touches the manual one. Only
clock-running time counts toward the derived figure — charging a team for time somebody else blocked makes the
number an argument rather than a measurement. `effort()` returns both the figure and whether it was adjusted, so
a consumer cannot present an adjusted figure as a measured one.

## `INV-WRK-13` — the escalation is invisible, so the ceiling is checked three times

DOC-03 §13.2: an automation rule "is a privilege escalation mechanism that no access review would detect." The
escalation is invisible because nothing about it looks like a grant — a person authors a rule, the rule acts, the
actions succeed, and the review lists the author's permissions and finds nothing wrong.

Three checks, and the third is what makes the first two survivable:

1. **`enable()`** refuses a rule whose owner cannot perform every action directly, naming the actions.
2. **`suspendForAuthorityChange()`** on the `SEC-AUZ-038` event, which also disables — leaving it
   enabled-but-suspended would mean two flags must agree for the rule to be safe, and any read path checking one
   would run it. `V009` holds the same rule as a `CHECK`.
3. **The execution-time ceiling** (`TransitionEvaluation` step 5), which is what makes the stored flag's
   sixty-second staleness under `NFR-SEC-002` survivable rather than exploitable.

Each `Action` declares the permission it requires, so the ceiling can be checked *before* anything is attempted.
Resolving it at execution time would mean discovering a breach halfway through, with the earlier actions applied.

`INV-WRK-14` bounds the **trigger**, not the rule: a rule that is fine in isolation and pathological in
combination is the common case. Both the loop guard and the budget throw rather than no-op, because
`PRD-WRK-044` requires the denial recorded and a silent stop is precisely the undiagnosable case it names.

## `INV-WRK-11` and `INV-WRK-12` — shapes that make the wrong thing unwritable

`SavedView` has no field for a scope and none for results, asserted by a test over its declared fields *and* by a
query over `information_schema.columns` in the DB suite. The tempting optimisation — caching the author's result
set so a shared dashboard loads fast — is exactly the escalation DOC-04 §16.8 rejects.

`BulkOperation.apply` takes a `Predicate<UUID>`, not a boolean. A single boolean parameter *is* the batch-level
check the invariant exists to prevent. Partial application is the correct outcome: all-or-nothing would let one
unauthorized identifier block legitimate work, and would tell an attacker their identifier was rejected. Every
item is audited including refusals, and a refusal says only "not permitted".

## `V009`

Two enforcement mechanisms on three tables — no `UPDATE`/`DELETE` grant **and** a rejecting trigger — for the
transition log, comment revisions, and automation executions. DOC-04 §16.3 asks for the second explicitly "so
that a privilege misconfiguration does not silently permit modification". Belt and braces is proportionate here
because the data cannot be reconstructed.

`comment` **does** take `UPDATE`, because redaction and edit are updates; two triggers confine those updates to
the permitted shapes. A redaction cannot be reversed, and `is_migrated` / `author_id` cannot be changed —
clearing the flag would launder an imported comment into one apparently written here, which is DOC-26 §8's abuse
case exactly.

`INV-WRK-07` is a `DEFERRABLE INITIALLY DEFERRED` constraint trigger, same reasoning as `INV-VUL-26` in `V007`:
the pair is two statements, and an immediate check would reject the intermediate state the transaction corrects.

A retention difference worth noting against `V008`: transition log partitions are **archived, not dropped**,
while `risk_score` partitions are dropped after the reproducibility window. A score can be recomputed from its
own record; a transition cannot be reconstructed from anything.

Also carried: the read-mark monotonicity trigger, which is what would make DOC-04 §16.5's suggested write-behind
cache safe to adopt later — an out-of-order replay cannot regress a mark.

## One test that had to be rewritten to be real

The bulk stale-write test first fabricated a `StaleWriteException`. Its constructor is package-private so that
only the aggregate can claim a conflict occurred, and the compiler said so. Replaced with a genuine conflict: the
batch holds a version read before somebody else moved the item, which is the actual situation.

---

# Prompt 10 — assessment and intake, session 1

Intake: `INV-ASM-01` through `INV-ASM-09`, and the credential handling of DOC-16 §8. The prompt calls this "the
platform's largest external write surface and highest object-level authorization risk by volume", and DOC-01
§10.4.6 pairs that with the least-trained user population (PP-7).

## The rejection message takes no argument, and Error Prone is why

`SecretRef.of()` rejects anything that is not a vault reference. The first version passed the rejected candidate
into the message builder and never read it — Error Prone's `UnusedVariable` flagged the parameter.

That was the right call for a better reason than the checker knew. `PRD-API-033` requires the rejected value not
be logged, "because rejecting at the boundary and not logging the rejected value prevents the credential reaching
a log as a side effect of the rejection". A parameter that exists is one a later change can start using, and the
natural later change is *make this diagnosis more helpful*. **Removing the parameter makes the leak impossible
rather than absent.**

A test asserts the message contains no substring of the input — not even a four-character prefix — and not the
length. A length alone narrows a brute force; a character-class summary narrows it further; a hash of a short
credential is the credential. What the message does say is **rotate it**, because by the time the rejection
happens the credential has already reached whatever handled the request.

`SecretRef` also has no `resolve`, `reveal`, or value accessor at all. The reveal path of `SEC-PTR-004` is
explicitly permissioned, step-up authenticated and per-object audited, and it is deliberately not reachable from
this type — the requester must not be able to read the credential back after submission.

## `INV-ASM-01` — `submit()` takes a resolver, not a descriptor

`SEC-AUZ-018`: the project reference is re-validated server-side, independently of the picker. A test asserts
`submit` has no `ScopeDescriptor` parameter, because a caller who can hand over a descriptor can hand over any
descriptor. The picker is a usability feature, never a control (PP-4).

The refusal reads `not found`, not `forbidden`. Distinguishing the two would turn intake into an org-structure
oracle for the platform's largest and least-trained user population.

## `INV-ASM-02` — the message explains the invariant rather than citing it

Two accounts per role is "the *only* way to demonstrate broken object-level authorization: showing that user A
can read user B's data requires both A and B" (DOC-03 §9.2). The refusal says that in those words. A requester
told only `INV-ASM-02 violated` adds one more account and tries again.

Three ways the count could have been wrong, each with a test:

- **Locked and expired accounts do not count.** A credential the engagement cannot use satisfies the invariant on
  paper and not in the environment.
- **Role names normalize case.** `Admin` and `admin` are one role; treating them as two reports a gap that is not
  there and trains requesters to add accounts until the message stops.
- **Zero roles is not "all roles satisfied".** An empty set satisfies a for-all, and the vacuous-truth reading
  would accept a request with no accounts at all.

`acceptanceGaps()` returns **every** unmet precondition, not the first. Reporting the first makes acceptance a
sequence of round trips for PP-7's population.

## `INV-ASM-05` is enforced at construction, so an unarranged control cannot be stored

`TestEnvironment` refuses to exist with a protective control declared and no recorded bypass — and refuses the
inverse too, because an arrangement recorded for a control not declared means one of the two is wrong and neither
can be trusted. The message names the consequence: the engagement tests the firewall, reports the application
sound, and the assessment is worthless in a way nobody notices until an incident.

## Small things with reasons

- **MFA enrolled with no bypass reference is refused at intake.** The engagement cannot authenticate as the
  account, and finding that out on day one costs a day of a booked engagement.
- **A blank role name is refused**, or two blank-named accounts would satisfy the two-per-role rule while testing
  nothing.
- **A retest is a separate factory** taking both the prior assessment and the new revision (`INV-ASM-09`). A
  retest against the same build re-runs the same tests on the same code and reports findings as fixed or not
  fixed with no evidence either way.
- **Derived facts carry their model version** and have no setter (`INV-ASM-08`, PP-2). A field somebody can type
  into is a field somebody will type a comfortable number into.

⚠️ **Working assumption (OQ-026):** `SecretRef` carries a provider-qualified reference and nothing about any
provider's API, so answering OQ-026 changes an adapter rather than this type or the schema that stores it.

---

# Prompt 10 — session 2: the assessment aggregate, checklists, coverage

`INV-ASM-10` through `INV-ASM-19`. DOC-03 §9.3 calls two of these "the whole of PP-1 applied to manual work".

## `INV-ASM-19` — the absence of null *is* the design

`ItemResult` has four verdicts and no null. A null result is indistinguishable from a passing one in every
aggregate that counts, and `NOT_ASSESSED` is an explicit statement that carries into the coverage figure where a
null would carry into nothing. Every item starts `NOT_ASSESSED` at instantiation — not absent — which is what
makes an abandoned assessment's partial coverage meaningful.

## `INV-ASM-13` offers the honest downgrade rather than a validation error

`NOT_APPLICABLE` without a reason cannot be constructed. But a rule people route around produces worse data than
no rule, so `notApplicableOrNotAssessed()` gives a caller the accurate result — `NOT_ASSESSED` where no reason
was given — instead of forcing them to invent a reason to get past a check. The requirement exists because
marking inconvenient items inapplicable is "the path of least resistance under deadline", and a check that
*creates* pressure to write a fictional reason has made that worse rather than better.

## `INV-ASM-12` — the acknowledgement must name every gap

An assessment with unassessed items completes only with an acknowledgement, and the acknowledgement must list
**every** unassessed item. A partial one "understates the gap while appearing to disclose it", which is worse
than none because it looks like disclosure.

The mirror case is also refused: an acknowledgement where coverage is complete. Recording one routinely is what
makes the requirement meaningless on the assessment where it matters.

The refusal states the coverage rather than merely refusing — `1 of 5 item(s) covered` — because the practitioner
needs to know how far off they are, not that a rule fired.

## `INV-ASM-11` — coverage sums items, it does not average ratios

Across two checklists of 10 and 300 items, averaging the ratios would report 50% when 10 items were covered: a
fully covered small checklist half-cancels an untouched large one. Summing gives 3%. There is a test with exactly
those numbers.

`CoverageSummary` has one factory, `from(results)`, and no way to state a ratio without stating its denominator —
`presentation()` renders "340 of 351", because "97%" is a claim a reader cannot evaluate and it is the one that
reaches a slide. An empty checklist reports **zero**, not perfect.

## `INV-ASM-17` — the failure direction is worth naming

DOC-03's argument for version immutability is the clearest statement of why versioning matters anywhere in the
corpus: an assessment that covered 340 of 351 items would, after an edit adding 20 items, appear to have covered
340 of 371 "without anyone having changed the assessment".

Note which way that fails. **The coverage falls and the team that did the work looks worse**, with nothing in the
record to explain it. An instance therefore pins the version and carries its own item set, and a result for an
item outside that set is refused — accepting it would make the numerator and denominator describe different
checklists.

## Two absences that are the requirement

- **`INV-ASM-15`:** no method on `Assessment` mentions findings. They are produced through Ingestion (ADR-011);
  a second creation site would diverge and produce different fingerprints for the same weakness.
- **`INV-ASM-14`:** conditions are raised by `approve()` and there is no `closeCondition`. "Attaching them to the
  assessment means they close when the assessment does, which is precisely the failure." A condition requires an
  owner and a date at construction — without an owner there is nobody to chase, without a date there is never a
  day it is late.

Both are asserted by tests that scan the public method names, because an absence nobody checks is an absence
until the next person needs a convenience method.

---

# Prompt 10 — session 3: evidence, external grants, `V010`

Completes prompt 10. `INV-ASM-20` through `INV-ASM-29`, and `V010` for all of DOC-04 §12.

## The defect the suite found on its first run

`trg_checklist_item__immutable` fired on `UPDATE OR DELETE`. It did not cover **`INSERT`** — so an item could be
*added* to a published checklist definition.

That is the exact failure `INV-ASM-17` describes: "an assessment that covered 340 of 351 items would, after an
edit adding 20 items, appear to have covered 340 of 371 without anyone having changed the assessment." The edit
that adds is an INSERT, not an UPDATE.

**The rule read as complete because immutability is usually about changing what is there. Here the damage comes
from adding what is not.** That is the eighteenth defect this build's own discipline has surfaced, and the second
of the "check the property, not the shape the property usually takes" kind.

Fixing it broke one of my own fixtures, which had seeded a `PUBLISHED` definition and then inserted an item —
correctly rejected once the gap closed.

## `INV-ASM-21` — the one place where quarantine-and-destroy is the wrong answer

`MALICIOUS` produces `FLAGGED_AVAILABLE`, never deletion. `Evidence` has no `delete`, `purge` or `destroy`
method at any privilege, and `V010` has a trigger rejecting row deletion outright: destruction at
`retention_until` removes the object-store content and **marks the row**, so a finding's missing proof stays
distinguishable from proof that never existed.

Worth stating plainly, because it feels wrong: an antivirus product deleting a web shell is behaving correctly by
its own lights. **It does not know the web shell is Exhibit A.**

`SCAN_FAILED` stays quarantined. An unscannable file is not a clean one, and an encrypted archive the scanner
could not open is exactly the shape a deliberate evasion takes.

A verdict requires a named scanner and version — because a false positive on pentest evidence is the *expected*
case, and a verdict whose source is unknown cannot be re-evaluated when the scanner turns out to have been wrong.

## `INV-ASM-22` works by there being nothing to reach

"Excluded from every export, notification and AI context **at any permission level**" cannot be enforced by a
permission check, because the requirement is that no permission suffices. It is enforced by `retrievalTicket()`
being the only method that yields the storage reference — a test scans every other public method for one that
returns it. An export routine cannot reach the bytes by accident because it cannot reach them at all.

`V010` deliberately carries **no** flag for this. A column constraint cannot express "absent from every export",
and a flag would read as enforcement and be none.

## `INV-ASM-25` — the word doing the work is *silently*

An external assessor grant has no scope column, in the domain or the schema, and a test asserts no method
mentions scope, subtree or node. Scope widening is not a bug anybody notices: it is the org tree behaving
correctly while an untrusted party's visibility grows as a side effect of an unrelated reorganization.

`valid(now)` is computed from the clock rather than read from the state, so an expiry job that stops running does
not extend anybody's access — it only stops *recording* what already happened. And there is no `extend` method,
asserted by a test: DOC-09 §14.1 says continuing access is a new grant with a new approval, because extendable
grants become permanent.

## A name corrected against the document

My `ScanVerdict` had `UNSCANNABLE`; DOC-04 §12.10's `malware_verdict` column says `SCAN_FAILED`. The document
wins — PP-10 is one name, one meaning, one place, and a domain constant that disagrees with the column it is
stored in produces a mapping layer whose two sides drift. Renamed, with the reason recorded on the constant.

## `V010`

Sixteen tables. Five constraints duplicate a domain invariant at the engine, and the file header names the bypass
path for each rather than asserting that belt and braces is generally good — four of the five are migration
import (ADR-028), which carries a decade of an incumbent tracker's data straight past the domain layer.

Three conformance functions, each answering a question a report needs rather than only guarding a write:
`requests_failing_two_account_rule()` (normalizing role-name case, as the domain does),
`assessments_with_divergent_coverage()` (the failure mode of every materialized aggregate), and
`outstanding_credential_rotations()` — where every row is a live credential to a pre-production environment held
by a party whose access has ended.

---

# Prompt 11 — composition analysis, session 1: version comparison

DOC-22 §6.3, which the document introduces as "the most error-prone element of the module". Six difficulty
classes, per ecosystem, with `TST-SBM-001`'s corpus.

## The comparator returns `Optional<Integer>`, not `int`

`PRD-SBM-039` requires `INDETERMINATE` "rather than asserting either presence or absence". **An `int`-returning
comparator has no way to say *I do not know* — it must return a number, and every number is an assertion.**
Returning empty is what makes the honest answer expressible at all, and it is why there is no
`Comparator<String>` anywhere in this module.

`Ecosystem.UNKNOWN` has no fallback comparator. A default would be the "single comparison scheme across
ecosystems" `PRD-SBM-038` forbids, wearing a fallback's clothing.

## Every branch exists for a named failure

The corpus has no case of the form "is 1.0 less than 2.0" — DOC-16 §7.3 says a naive suite passes "because a
naive suite tests versions that differ obviously". What is covered instead:

| Case | What the wrong answer produces |
|---|---|
| `1.0.0-alpha` < `1.0.0` | A range of "affected below 1.0.0" excludes the pre-release that *is* affected |
| `alpha.2` < `alpha.10` | String comparison inverts it — the single most common pre-release bug |
| `1.0` < `1.0.post1` (PyPI) | A post-release orders **after**; the opposite direction from every pre-release |
| `1.0` < `1.0-sp1`, `1.0` < `1.0-customqualifier` (Maven) | Maven orders an *unknown* qualifier after the release; semver orders an unknown pre-release identifier before it. **Same input, opposite answer, different ecosystem** |
| `2.0` < `1:1.0` (Debian) | Epoch ignored inverts the entire comparison |
| `1.0~rc1` < `1.0` | The tilde is how distributions package pre-releases; omitting it inverts every pre-release comparison in the two ecosystems where most OS components live |

## `PRD-SBM-040` is checked **first**, and that is structural

Distribution patch metadata overrides the range comparison. Checking it after the ranges would give the same
answer today — but it invites a later change that returns early on a range match, at which point the precedence
silently stops applying. Checking it first means it cannot.

`PATCHED_BY_DISTRIBUTION` is a distinct outcome from `NOT_AFFECTED`. The component *is* in the upstream affected
range; a reader auditing why a known-vulnerable version produced no finding needs to see that, and a reviewer
checking whether the patch claim is trustworthy needs to be able to find these cases.

A patch claim requires a source reference, and one that cannot be ordered yields `INDETERMINATE` rather than
suppression — failing open there would let malformed metadata suppress real findings.

## Error Prone caught a real hazard

`StringSplitter` flagged `version.split("\\.")`. That is not a style nit here: `"1.0.".split("\\.")` drops the
trailing empty segment and compares **equal to `1.0`** — a malformed version silently becoming a well-formed one,
inside a comparator whose output is a match verdict. With limit `-1` the empty segment survives, fails the
numeric check, and yields `INDETERMINATE`, which is the honest answer. There is a corpus case asserting exactly
that.

## Two properties the corpus asserts about itself

- **Every ecosystem declared orderable can actually order.** A declared-but-unimplemented ecosystem produces
  silent `INDETERMINATE` for every component in it.
- **The set of ecosystems carrying distribution patches is pinned.** A new ecosystem shipping backports needs
  its own corpus cases before it is declared, or `PRD-SBM-040` applies to it untested.

Plus antisymmetry on every ordering assertion, reflexivity per ecosystem (a version must equal itself, or a
range boundary matches or not depending on which side it is evaluated from), and arbitrary-precision segment
comparison — `int` parsing would overflow and invert the comparison on a date-derived segment.

---

# Prompt 11 — session 2: closure guards and coverage governance

`PRD-SBM-053` to `-060`, `PRD-SBM-065`, and `TST-SBM-002`. This is `PP-1` made enforceable over a whole module.

## `PRD-SBM-055` gets its own gate because the run looks healthy

DOC-22 calls it "the most subtle closure error in the module", and the reason is that **every run-level
precondition passes**: "A team splits its pipeline and one job begins submitting an SBOM covering only its own
ecosystem. Every finding in the other ecosystems appears remediated. Nothing failed, the run completed
successfully, the snapshot quality is high — and the closure is wrong for hundreds of findings."

So `ClosureAuthority` has two entry points. `authorize()` checks the four run-level preconditions;
`mayCloseComponent()` additionally checks that the snapshot covered *that component's* ecosystem. A test asserts
the healthy-run case explicitly — the run may close, and a component outside the covered ecosystems still may
not.

A test also asserts that **no boolean-returning method exists that does not take a `Decision`**. Five
preconditions expressed as an `if` in the closure path means the fifth gets forgotten, and the fifth is this one.

## The asymmetry in stale intelligence is deliberate

`PRD-SBM-063` requires matching to continue against stale intelligence. `PRD-SBM-053` refuses closure on it. Both
are right for the same reason: **stale intelligence can still find a vulnerability; it cannot establish that one
is gone.** The refusal message says so, because an operator seeing matching work and closure not work will
otherwise read it as a bug.

`SKIPPED_NO_CHANGE` is the same shape — a legitimate, deliberately-recorded run outcome (`PRD-SBM-050`, so the
coverage timeline shows no gap) that authorizes nothing, because no components were evaluated.

## `TST-SBM-002` is one test over the whole failure table

DOC-16 names `PRD-SBM-065` "the property a reviewer should test the module against", so it is a single test
enumerating seven failure modes from DOC-22 §11 and asserting each drives no closure for any ecosystem — rather
than one assertion buried in each case, where a new failure mode would arrive without one.

## `PRD-SBM-056` — an absence that reads as good news

DOC-22 calls it "the single most important requirement in the module": "A project that has never submitted is
not low-risk; it is unmeasured. Without an explicit state it is absent from reporting entirely, and **absence
reads as absence of problems.**"

`NEVER_SUBMITTED` is a value with a named factory, not a null or an empty `Optional`, so code handling it has to
say the word. `neverSubmitted()` still requires an accountable owner — a gap nobody owns sits in a queue nobody
reads.

Staleness is evaluated **before** quality: a stale snapshot of perfect quality is `STALE`, and reporting it
`PARTIAL` would understate the gap.

`withEcosystemRemovedFromDeclaredStack()` refuses unless the ecosystem was actually covered — the same shape as
`PRD-RSK-028`'s refusal to exclude unmeasured assets from a coverage ratio. Removing an uncovered ecosystem would
move an asset from `PARTIAL` to `CURRENT` without a single additional component being examined.

---

# Prompt 11 — session 3: canonicalization, the match run machine, `V011`

Completes prompt 11. All fifteen `INV-SBM` invariants have tests.

## Two defects the DB suite found, one of them a PostgreSQL trap worth knowing

**`array_length(empty_array, 1)` returns NULL, not 0** — and a `CHECK` evaluating to NULL **passes**. So
`CHECK (array_length(ecosystems, 1) >= 1)` read as correct and enforced nothing, in two places: a snapshot could
declare it covered no ecosystems, and a run could claim confirmed coverage while naming none. Both feed
`PRD-SBM-055`, so both would have re-opened the partial-submission closure hole from the other end. `cardinality()`
returns 0 for an empty array, which is what the comparison needed.

**The prompt-4 conformance test caught a missing trigger.** `sbom_snapshot` carries scope descriptor columns and I
had not attached `reject_scope_descriptor_change`. The whole-table immutability trigger would in fact have
rejected a scope change too — but the conformance query looks for *that* function by name, and being absent from
an inventory of scope-bearing tables is how the next scope-bearing table gets added without one. Nineteen and
twenty on the defect count.

## ADR-032 — the efficiency argument is the attractive one, and it is wrong

Global interning would be one component row instead of one per tenant: 300,000 rather than 3,200,000. DOC-04
§15.2 rejects it because a globally interned table populated on demand is *created by* tenant submissions, which
makes component existence observable in principle — "deduplication response as an oracle" (DOC-24 §6.2 entry 14).

`tenantScopedKey(tenantId)` takes the tenant as a required argument and there is no global variant; a test scans
the public methods for one. The trade is stated in the migration header rather than left implicit, because the
next person to look at 3.2 million rows of duplicate component identity will want to know it was deliberate.

## `component_entry` — where the comments are the design

Two documented exceptions to the schema conventions, both about row width at 80,000,000 rows where "every byte
is 80 MB": no surrogate key (1.3 GB of data, 2–3 GB of index) and no common columns (3 GB). The insert-once
trigger is there **because** the omitted audit columns depend on that premise — if entries could be updated,
their absence would be a gap rather than a saving. A DB test asserts the columns are absent by querying
`information_schema`, so a well-meaning later addition fails rather than costing gigabytes silently.

## Canonicalization folds case only where the registry does

npm and PyPI fold; Maven and Go do not. Folding everywhere would produce DOC-22 §6.2's "false merge" — two
distinct artifacts becoming one, with false positives on both — and folding nowhere produces the false split.
Tests assert both directions.

Canonicalization never throws. An unmatchable component is data (`PRD-SBM-037`), not an error; throwing would
abort the snapshot and lose the components that *did* canonicalize, which is the silent-skip failure in a louder
costume.

## The match run's lease is clock-derived, like the grant in prompt 10

`PRD-SBM-048`'s failure is that a terminated worker "leaves the run claimed and the batch stalls **silently**" —
no error, no alert, and a coverage timeline that stops advancing, which resembles a stable estate (PP-9). So
`leaseExpired(now)` is computed rather than flagged, and `closureOutcome()` checks the lease *before* the state,
so a `RUNNING` run whose worker died does not report as merely running.

`INV-SBM-09` is enforced twice deliberately: the aggregate derives `coverageConfirmed` from how the run ended, and
`V011` carries `CHECK (NOT coverage_confirmed OR state = 'COMPLETED')`. The engine check is what stops a repair
script clearing a stuck batch from driving closure.

---

# Prompt 12 — API surface, session 1: the framework properties

DOC-05 §5's seven annotation classes as framework properties, the absolutes the prompt lists, and
`TST-PLT-008`. Lives in `app`, the only subproject permitted to see every `-impl`.

## The classes carry the properties; operations carry nothing

DOC-00 §15.1 requires nine annotations per operation. Written per operation, **an operation ships with eight —
and the missing one is discovered when somebody audits, which is to say not discovered.** So `AnnotationClass` is
an enum whose constants carry all nine, and an operation acquires them by being assigned a class.

`OperationRegistry.resolve()` returns empty for an unregistered path, and the dispatcher must treat that as a
routing failure. That is what makes `PRD-API-019`'s "a new operation MUST NOT be introduced without one"
enforceable rather than aspirational — running an unregistered operation *is* running one with no class.

The registry is data rather than annotations on handler methods for one reason: a reviewer asking "which
operations are class C" reads one file. With annotations they read every handler and trust they found them all.

Two shapes the constructor refuses: an authenticated operation naming no permission (deny-by-default means it
becomes the operation somebody removes the check from), and a class-G operation declaring one (it cannot be
evaluated, and declaring it reads as a control that is not there).

## A3 — three of the four are easy and the fourth is the one that leaks

Status, code and message are settled by `DenialResponse` having **one** factory, no parameters, and no
`forbidden()` beside it. A caller cannot return a different body for the two cases because there is nothing to
vary. A test scans the public methods for the second one.

Timing is different, and the mitigation is structural rather than a sleep: the scope predicate is applied **in
retrieval** (`SEC-AUZ-016`), so an out-of-scope object is simply not found and both cases do the same work.
`assertAppliedInRetrieval()` catches a handler that fetched by identifier and compared scope afterwards — the
pattern whose latency difference is invisible until somebody measures it deliberately.

`TST-AUZ-002` requires the timing assertion to be statistical, so it is: 2,000 samples per case, compared on
medians, tolerating a tenth of the median against measurement noise. "A single comparison cannot distinguish it
from noise."

## `TST-PLT-008` — the test uses the shape that actually breaks cursors

Twenty rows sharing five timestamps, walked five at a time, with a row inserted **at the earliest timestamp**
between every page. Under offset pagination that shifts every later row forward and loses one. The assertion is
that no pre-existing row is missing and none appears twice.

The tiebreaker is not optional and cannot be omitted: a cursor on a non-unique sort key cannot say *which* of the
rows sharing that value the page ended on, so two rows straddle the boundary and one is lost — the offset failure
reintroduced by the mechanism adopted to avoid it. The cursor token is opaque so a client cannot construct one
meaning "row 5000".

`hasMore` comes from fetching one extra row rather than a count query. A count would be a second query over the
same scope predicate, and the two could disagree under concurrent modification — reporting "51 results" above a
page that shows 50 and ends.

## Small decisions with stated reasons

- **Restricted fields are absent, not masked** (ADR-047). A mask leaves the key present with a placeholder, which
  tells the reader the field exists and has a value — and for a boolean or a small enumeration that is most of
  the information. A test asserts no mask token appears either.
- **The idempotency key is tenant-namespaced**, tenant first. A shared key space returns one tenant's stored
  response to another: a cross-tenant disclosure produced by a reliability mechanism, through a code path nobody
  thinks of as a read. DOC-24 names cache key construction as the recurring failure point, and an idempotency
  store is a cache wearing a different name.
- **No filter expression language.** An expression language on a scoped collection is a second query planner that
  must reapply the scope predicate correctly. The cost is stated: a caller wanting a filter the platform does not
  offer has to ask for one.
- **Unknown fields are rejected and every one is named**, but the error offers no "did you mean" — that would be
  a schema-disclosure oracle for an operation the caller may not be authorized to use in full.
- **Page size is clamped, not rejected.** A client asking for ten thousand rows is a script written by somebody
  who did not read the documentation; rejecting makes them retry in a loop.

---

# Prompt 12 — session 2: the twenty-two authorization assertions

DOC-16 §6, built as an **inventory** rather than twenty-two scattered tests, for the reason `TST-AUZ-001` gives:
"The map is complete at authoring and decays. A new egress path without a test is unenforced, **and no test
detects its absence because the test is the one not written.**"

`theInventoryIsComplete()` fails if any of A1–A22 lacks a method claiming it, and rejects a claimed identifier
that is not in the document — an invented one would make the inventory look more complete than the corpus
requires. Same shape as the isolation-path inventory from prompt 3, and for the same reason.

## Eighteen assert; four are countable debts

The four disabled entries each name the module and the prompt that will close them:

| | Awaiting |
|---|---|
| A9 graph traversal at the API | the traversal endpoint (session 3) — the per-node filter itself is asserted in asset-inventory |
| A11 score breakdown scope filter | the explanation endpoint (session 3) — `RiskScore` already retains the contributions |
| A18 delegation | the identity module (prompt 18) |
| A21 effective-permission inspection | the identity module (prompt 18) |

A `@Disabled` test is a debt on a ledger; a missing test is not. The suite's skip count went 18 → 22, which is
the honest direction for this session.

## The assertions exercise real enforcement points, not doubles

`app` is the only subproject that can see every `-impl`, so A2 runs against `AssessmentRequest.submit`, A16
against `AutomationRule`, A17 against `TransitionEvaluation`, A19 against `BulkOperation`, A20 against
`SavedView`, A10 against `NodePosture.comparisonSet`. A double asserts what the test author believed the module
does.

## Three assertions where writing the test clarified the requirement

**A5 — union, not cross product.** Two assignments, `(read, X)` and `(write, Y)`, must not yield `write@X`. Stated
as a set operation it sounds obvious; the reason `SEC-AUZ-010` names it is that a cross product *is* a correct
set operation, just the wrong one, and the escalation it produces looks like sound code.

**A6 — three distinct faults, all failing closed.** An evaluation error, an unavailable resolver, and no
resolver at all. The third is the one worth having: a null collaborator must deny rather than default to permit.
The test also asserts the object did not advance — a partially applied write on an unresolved scope is worse
than a refusal.

**A14 — error differentiation is enumeration.** `SEC-AUZ-033` lists six paths and the last is the one that would
otherwise be free. A grant-only principal probing for differentiated errors is enumerating without touching
search, traversal, or export at all.

---

# Prompt 12 — session 3: the traversal and breakdown endpoints

Closes A9 and A11. The skip count went 22 → 20.

## A9 — the leak is in the field that reports the filtering worked

`SEC-AUZ-024` has three parts and the third is the one implementations break while satisfying the first two:
filter per node, **do not fail**, and **do not indicate that a branch was terminated**.

A result carrying `truncated: true` is a correct-looking API design and an existence oracle — the caller learns
that an edge led somewhere they cannot see, which is exactly what they were not to learn. The subtler version is
a **count**: "12 nodes reached" where a full traversal reaches 15 discloses three out-of-scope neighbours through
a field nobody would call a disclosure. `Result` carries only what was reached, and a test scans its record
components for `truncat`, `omitted`, `partial` and `total`.

The test case is a three-node chain with the middle node out of scope: the edge is real and the query is
authorized, so per-*query* filtering would walk straight through it. The edge itself is also withheld — an edge to
an out-of-scope node discloses that something is there.

An out-of-scope **start** returns an empty result rather than an error, so the caller learns nothing they would
not learn for a node that does not exist.

`SEC-AUZ-025`'s bound is a constant and `traverse()` takes no `int` parameter at all, asserted reflectively. A
bound varying with scope turns response size into a measure of the caller's authority — and, across several
principals, into a map of who can see what.

## A11 — the tempting implementation is a subtraction oracle

`SEC-AUZ-027`: "An aggregate score is a permitted disclosure; its breakdown can reveal the existence and severity
of out-of-scope findings." The number is fine to show. "Driven by three criticals on the payments service" tells a
reader who cannot see that service that it has three criticals.

The obvious implementation filters the contributions and **re-sums them so the breakdown adds up**. That is wrong
twice: it makes the number depend on who is looking, so two readers comparing notes see different postures for
one node; and a reader who can see the true aggregate elsewhere subtracts the visible sum to learn exactly how
much sits out of scope. That is `SEC-AUZ-026`'s no-derivation-by-subtraction at aggregate level.

So the aggregate passes through unchanged, the visible contributions are listed, and the difference is **not** a
residual line item. `completeForReader()` says whether the listing accounts for the whole score — disclosing that
something is hidden without disclosing how much. The qualifier states that the aggregate was not recomputed,
because a partial breakdown beside an unexplained total otherwise reads as an arithmetic error; and it carries
neither a count nor a magnitude, asserted by the test.

A reader who can see everything is told the breakdown is complete, so the qualifier does not become noise
everybody ignores.

---

# Prompt 13 — read models and reporting, session 1: the eighteen honesty assertions

DOC-16 §11, whose opening sentence is the design brief: the suite "exists because **every mechanism in it is
individually easy to remove for a cleaner interface**."

## `TST-DSH-002` decides where the code goes

"H1 through H18 MUST be verified against rendered output, not against the data layer. A measure carrying its
coverage in the API response and losing it in the interface has lost it."

So this session built a **renderer**, and every assertion reads the rendered string. Plain text, because the
assertions are about what a reader can see and text is the smallest thing that makes "can they see it" testable.
What carries over to a real interface is that the honesty content is produced by this layer rather than assembled
by the presentation one.

## H14's mechanism is an ordering, not a rule

`render()` builds the mandatory lines **before** it reads `PresentationOptions`, and `applyPresentation()` may
reorder or reword but is asserted to return the same line count. A renderer that assembled everything and then
filtered by options would pass every test written against the defaults and fail on the one combination nobody
tried — which is exactly what `TST-DSH-001` exists to prevent.

`PresentationOptions` has no `hideQualifiers`, no `sections` list, and no preference map. A test scans its record
components for `hide`/`show`/`suppress`/`omit` and for any `Map`. **A boolean there would be set to false by
somebody making a dashboard look tidier, in a commit nobody reads as a security change.**

The suppression assertion runs over all **192** combinations — 4 themes × 3 densities × 4 templates × 2 × 2. My
first version asserted 96, which was arithmetic rather than a design error, and the test caught it. The count is
asserted so that adding an option without extending the suite fails here rather than silently leaving the new
path untested.

## Three renderers take an argument specifically so the dishonest call is unwritable

- **`renderClosure`** has no parameter for a total alone. The total is computed from the breakdown, so an
  undifferentiated closure rate cannot be produced (H15).
- **`renderBreaches`** takes a map by attribution and never emits a single figure. A single breach count invites
  the reading that the accountable team missed every one; by attribution the same number becomes a question
  about where the delay was (H16, PP-6).
- **`renderAssessmentFindings`** takes coverage as a required argument, so findings cannot be rendered without it
  (H17). "No findings over 12 of 351 items" is a different statement from "no findings over 351".

## Markers are prefixed into the text, not attached as metadata

H9, H10 and H11 all require a label to survive presentation or export. A metadata field is dropped by an export
that does not know about it — and **the reader of an exported artifact is exactly the reader who cannot check**.
So the marker is part of the string.

## `PRD-DSH-021` — the scope root has no overload taking a node

`ScopeRootResolution.forPrincipal(principal, authorizationContext)` derives the root from the caller. A test
asserts there is no two-parameter overload taking a node: a root the client supplies is a scope the client chose,
and the composition permission would then convey visibility of any subtree the caller named. With no derivable
root the composition renders nothing rather than defaulting to the tenant root.

That is also what makes H18 hold — two recipients cannot share a scheduled artifact, because the root is derived
per recipient.

---

# Prompt 13 — session 2: the twelve queues and the four compositions

## Queues ⑧ and ⑪ are enumerated because absence is the failure

DOC-12: "**Queues ⑧ and ⑪ exist because their absence is the classic blind spot.** If forty assets have silently
had no data for three months, the vulnerability dashboard shows green — not because they are secure but because
there is no data."

A queue that exists only as a saved view somebody configured is a queue a tenant can be deployed without — and
the two that matter most are the two nobody thinks to configure. So all twelve are an enumeration with
contiguous numbering matching the document, asserted by a test so a reviewer can read the two side by side.

Queue ⑪ highlights on **success rate**, not only on an open circuit. A connector failing one submission in three
never trips and silently loses a third of the data (`PRD-CON-031`). That is the half circuit breaking misses,
and it is in the highlight rule rather than a footnote.

## Every threshold carries its reason, and none is settable

A threshold living in a dashboard configuration is one a tenant raises until the red disappears — the reporting
equivalent of DOC-28 §13.2's gaming paths, producing a queue that is always empty and always wrong. So each
constant carries `whyThisRule()`, and a test asserts none is blank: a tenant asking to change a threshold can be
told what it is for, which is the conversation that either produces a better threshold or ends the request.

Two queues deliberately have **no** highlight and both say why. A threshold on the short-lived
awaiting-verification queue would fire constantly and be ignored, which costs the attention every other red
marker needs; and everything in the breached queue is already breached, so a red marker on all of it
distinguishes nothing. Exactly one queue is unconditionally red — if a second were, neither would read as urgent.

## Drill-through is where a composition would leak everything it touched

Permission to see an aggregate is not permission to see every object behind it. An executive posture figure for
a division legitimately includes findings on systems the reader cannot open, and **the aggregate is a permitted
disclosure precisely because it does not name them**.

The tempting implementation authorizes the composition once and then treats every row as reachable, because the
rows came from a query the caller was allowed to run. That converts an aggregate permission into an object
permission for everything the aggregate touched — the defect class this product exists to find in customers'
software.

`Composition.drillThrough` takes the object-level check as a required argument, and a test asserts there is
exactly **one** overload. A convenience overload would be the omission nobody notices, in a code review of a
method call that already looked correct.

A summary row carrying no object identifier survives the filter: it *is* the aggregate, which the caller is
authorized for.

---

# Prompt 13 — session 3: `V012`, the read models

Completes prompt 13. Seven projections, and the three properties every one must have.

## `CON-DAT-030` is asserted over the catalogue, not by inspection

"Projections are aggregation surfaces, which is where cross-tenant leakage through **counts** occurs" — a read
that returns no rows and still discloses. So `projections_without_tenant_prefix()` walks `pg_class` for every
`rm_*` table and reports two faults, not one:

- no `tenant_id` column at all, and
- **`tenant_id` present but not leading the primary key.** A key of `(finding_id, tenant_id)` permits an index
  scan that spans tenants before the predicate applies. Present-but-not-first reads as compliant and is not.

The failure mode this guards is a projection added later without one, which is why it is a catalogue query
rather than a review checklist.

## Three honesty surfaces are enforced in the schema, not only in the renderer

Session 1 put H2, H7 and H8 in the rendering layer. `V012` puts them in the rows as well, because **an export
reading this table directly would otherwise find a number and print it**:

- `ck_rm_posture__insufficient_has_no_figure` — an `INSUFFICIENT` row cannot carry a posture value at all.
- `ck_rm_workload__band_reason` and `ck_rm_workload__purpose` — a utilization row without its band reason or
  purpose statement is unrepresentable.
- `ck_rm_workload__minimum_population` — a `TEAM` figure derived from fewer than four contributors is an
  individual's figure with a team's label (`SEC-AUZ-026`). Blocking it here means no report can produce one,
  whatever query it runs.

## `rm_latest_risk_score` exists because of retention, and carries no foreign key

DOC-04 §21: score partitions are dropped after the reproducibility window, so "dropping a 25-month-old partition
would remove the current score for any subject not rescored since" — **a data loss disguised as retention, and
one that arrives twenty-five months after the decision that caused it.**

A test asserts the projection has *no* foreign key to `risk_score`. One would either block the partition drop or
cascade the current score away with it, which is the same loss through the mechanism meant to prevent it.

## The queue number is part of the key

An item can be in two queues at once — awaiting a third party *and* breached. A single-queue-per-item model
silently drops it from the second, **and the dropped one is the one nobody is watching**. A test inserts the
same item into queues ② and ⑥ and asserts both survive.

## Where DELETE is granted freely, and why only here

Every table in `V012` is derived and rebuildable (`CON-DAT-031`), so dropping one loses nothing — and being able
to drop and rebuild is the remedy for the aggregation defect that requirement anticipates. The operational
tables have the opposite property and the opposite grants, and the header says so rather than leaving the
inconsistency to be noticed.

---

# Prompt 14 — notification

DOC-13 in full. One session, because the whole domain follows from one sentence.

## The asymmetry that shapes everything

DOC-13 §2: "A system that notifies too little leaves a process stalled with someone unaware — recoverable,
because the queue views still show the work. **A system that notifies too much gets muted, and a muted system is
one whose service level escalations do not arrive, whose information requests go unanswered, and whose findings
age unnoticed.** Muting is also effectively irreversible: a user who has filtered the sender does not un-filter
when the volume improves."

That is why coalescing and bulk suppression are in `NotificationDispatch` rather than in a configuration
somebody enables. **A volume control a tenant can leave off is one that will be off in the deployment that most
needs it.**

## `PRD-NTF-013` is enforced by what `dispatch` cannot take

A notification failure inside a transaction "either fails the transaction — making a mail outage a work outage —
or is swallowed, losing the notification silently." So `dispatch` takes events that have already happened, holds
no repository, performs no write, and returns artifacts rather than an outcome a caller could branch on. A test
scans its parameter types for anything named like a repository, connection, transaction or session.

## `PRD-NTF-031` — suppressed, not emptied

"An empty notification about an object the recipient cannot see confirms that the object exists and concerns
them — a disclosure through absence." So a narrowed scope produces **no artifact at all** — the test asserts the
renderer was never called, so there is nothing to accidentally deliver.

The suppression is *recorded* internally, because "did not arrive" and "was never generated" are different
diagnoses and only one is a bug. The record never reaches the recipient, which would defeat the requirement it
supports.

The same rule applies inside a bulk summary: "2 items updated" when the recipient can see one **discloses the
existence of the other through a count**. The summary counts only what they can still see.

## Bulk collapses across subjects, and that is why per-subject coalescing is not enough

A large bulk operation spans more than sixty seconds, so grouping by `(recipient, subject)` within the coalescing
window would let it through as one message per item. `PRD-NTF-023` says "never one per affected item", so bulk is
keyed on `(recipient, operation)` and collapses across subjects. A test uses two subjects twelve minutes apart —
outside the window and in different groups — and asserts one summary.

## Escalation: three rules, none configurable

Chains are tenant-configured because escalation paths are organizational. These three are not, because each
protects the credibility the mechanism depends on:

- **A step fires once.** "A recipient who received four escalations for one item stops reading them." The
  already-fired ratio is a parameter, so a caller cannot lose it by reconstructing the object.
- **Targets resolve at fire time.** The accountable owner may have changed; resolving at clock start escalates to
  someone no longer responsible.
- **A pause for the requester or a third party suppresses the remediation chain and fires the blocking-party
  chain instead.** Both halves — suppressing *without* the separate chain means a blocked item escalates to
  nobody, which is how a request waits four months.

## Inbound: the `Result` has no third state

`PRD-NTF-040` closes the failure the capability exists to prevent: "If association fails silently, the request
stalls with both parties believing the other is responsible." So `associate()` returns either a comment or a
message to the sender, and the record's constructor rejects both-or-neither. There is no `Optional` a caller can
ignore.

Association is by token only. A subject line carrying `[WRK-1042]` is the conventional design and it means
**anybody who can send mail can post on any item whose code they can guess, and item codes are sequential.** The
token identifies item, recipient and notification, expires, and answers exactly one question — a test asserts no
method on it could be asked "may this principal read the item", because one that could would eventually be used
that way.

A reply that is only quoted history fails rather than posting an empty comment: recording it would appear to
answer a question it did not answer, and the requester would stop chasing.

---

# Prompt 15 — capacity

DOC-03 §14 and DOC-12 §8. Six invariants, and every one of them exists because **this is a measurement system
pointed at people**. DOC-03 states the failure directly: "A measurement system producing evidence against its own
users is worse than none."

## `INV-CAP-03` — the gate is a named permission, not seniority

"gated by explicit permission rather than role seniority." The distinction *is* the control: **a permission that
any sufficiently senior role implies is not a permission, it is a job title.** The test gives a principal
`auz.role.manage` and `aud.audit.read` and asserts they still cannot see a per-member figure.

`PER_MEMBER_PERMISSION` is a constant and `releaseTo` takes no permission-name parameter, so a caller cannot pass
one the reader happens to hold. The release carries its own per-access audit obligation rather than leaving it as
a separate thing somebody remembers.

The prompt's "excluded from business owner and executive views entirely — **including team aggregate**" is
stronger than it first reads, and it is implemented literally: those audiences receive neither, because a small
team's figure is its members'.

## `INV-CAP-04` — a team measure below the minimum cannot be constructed

Not suppressed at presentation: **unrepresentable**. `CapacityMeasure.forTeam` throws below four contributors, so
no query, export or report can produce one whatever it asks for.

Four rather than three, and the reason is recorded on the constant: with three, a member who can see their own
figure subtracts it and holds a two-person aggregate — one subtraction from individual data.

## `INV-CAP-01` — three deductions, each a different way the same overstatement happens

Non-working days (a 22-day month costed at 30 is a 36% overstatement *before* anybody takes leave), recorded
leave (the deduction a manual model always makes and an automated one forgets, because leave lives in a system
the platform does not own — ⚠ OQ-019), and the overhead allowance (a model with no overhead line reports a team
at 60% and is used to argue they have spare capacity).

All three are constructor parameters, there is no headcount factory, and a test asserts it. Capacity floors at
zero: a negative would **subtract** from a team total, making the team look smaller while somebody is away.

## `INV-CAP-05` and `INV-CAP-06`

The band's reason is required at construction, and there is no accessor phrased as a maximum or a percent of
capacity — a maximum reads as a target, and the point of a band is that a hundred percent is a **failure state**.
Both ends are outside it, asserted in both directions.

A single-category effort model is refused. Counting only assessments "reports a materially over-capacity team at
low utilization, and that number is then used to deny resourcing."

## `INV-CAP-02` — idempotence is what makes a defect correctable

A rollup that accumulated rather than replaced could not be re-run: a defect found in March means every day since
is wrong, with no remedy but explaining the discontinuity in a chart forever.

`compute()` is a pure function of its inputs, so the same day twice gives the same answer and a day three months
ago gives the answer that day should have had. `agreesWith()` is the verification half — without it a backfill
silently replaces one wrong answer with another and nobody knows which was which.

An item that did not exist yet occupies **no** state rather than a zero, or a cumulative-flow chart shows work
before it was created. And a test asserts there is no `increment` method: **a counter is not backfillable, having
no record of what it counted** — which is the whole argument for building the transition log back in prompt 9.

---

# Prompt 16 — integration

DOC-21 in full. One session.

## `PRD-CON-024` — the retry policy is a count, not a flag

DOC-21: "Blind retry on an authentication failure locks the account on the target system, converting a
configuration problem in the platform into an outage in the customer's engineering estate." And then, plainly:
"**This is the failure most likely to damage a customer relationship: the platform's misconfiguration causing an
outage in *their* systems.**"

So `AUTHENTICATION` and `AUTHORIZATION` have `maxRetryAttempts() == 0` rather than a `retryable` flag. **A
generic `while (attempt < max)` loop written later by somebody who never read this file does the right thing.**
`backoffBefore()` also returns empty for them, so a caller who ignored the count still cannot obtain a delay.

The circuit opens on the *first* credential failure rather than after five: the threshold exists to distinguish a
blip from a fault, and waiting for five is five authentication attempts against the target — which is how the
account gets locked. A credential circuit also refuses to half-open on a timer, because that would retry the
authentication the circuit opened to prevent.

## `PRD-CON-031` — the failure the circuit cannot see

"Total failure opens the circuit and notifies. **Intermittent failure does neither: it delivers some data, so
nothing looks broken, and the resulting picture is silently incomplete.**"

The test runs two failures then a success, ten times over. The consecutive counter never reaches five, the
circuit never trips, and the connector is at 33%. `degraded()` is a separate condition from `circuitOpen()` and
`requiresAlert()` is true for either — a health model watching only the circuit has the blind spot in it.

An idle connector reports 0%, not 100%: no attempts is not perfect success, and reporting one would make an
unused integration look healthy. It is also not *degraded*, because conflating the two fills the queue with
connectors nobody has run yet.

## Egress: three closures, three different bypasses

- **No method takes a URL.** `resolve()` takes a configured *name*, so a record field cannot become a
  destination however it arrives. An unconfigured name is refused rather than treated as a host — that fallback
  is the data-derived destination arriving through a typo.
- **Resolution is re-checked at connect time**, with the resolver passed in. The test uses the same configured
  destination resolving first externally and then to `169.254.169.254`.
- **A redirect target is re-resolved too**, so the allowlist is not a name check. A configured hostname
  resolving internally is the same rebinding attack arriving through a redirect.

Unresolvable fails closed: treating resolution failure as "attempt it and see" skips the range check entirely.

## One-way propagation, and why the divergence is a question

A test scans for `apply`, `sync`, `pull`, `merge` and `reconcile` — a bidirectional method added later would look
like a helpful completion of an obviously half-finished class.

`Divergence.Resolution` has exactly one constant, so there is no resolved state a caller can construct. The
divergence carries the *question* rather than a verdict, because both readings can be right: "the ticket may be
closed because the fix shipped, or because somebody was tidying the backlog — only a person knows which, and the
platform guessing is how a live vulnerability gets marked remediated."

## Identity synchronization, and a guard the corpus does not require

Existence only — a test asserts no method mentions a role, permission or grant. A directory group named
"Security Team" is an organizational fact, not a permission grant, and writing assignments from it means a
membership change in a system the platform does not control silently alters authorization here.

Beyond the requirement, `plan()` refuses to deactivate more than 20% of known principals in one run. **A
directory connector returning a truncated page is a routine failure, and acting on it is an outage the platform
inflicts on itself at the moment nobody can log in to fix it.** A real mass departure is rare and can be
confirmed. Departed principals are deactivated and never deleted — a deleted principal orphans every audit entry
attributed to them.

---

# Prompt 17 — remaining modules, session 1: ai-assistance

The prompt singles out two properties as **architectural rather than behavioural**, and `PRD-AIC-021` says why:
"A policy is violated by a prompt; an absent grant and an absent dependency edge are not."

## `AiContainmentTest` checks both halves, and a third nobody asked for

- **No compile-time dependency on any domain module**, over bytecode. The build already gives the module no such
  dependency; the test is what catches somebody adding one, and it guards against passing vacuously by asserting
  AI classes were actually imported.
- **No write grant in any migration.** Walks all twelve migration files for a `GRANT` naming an AI role with
  `INSERT`, `UPDATE`, `DELETE` or `ALL PRIVILEGES`. Deliberately broad — a grant to an AI role on *anything* is
  the failure, whatever the table — and it asserts it found at least twelve files, because a grant check that
  reads no files passes for the wrong reason.
- **No domain module depends on AI either.** Not in the requirement, but the dependency must be absent in both
  directions: a domain module calling AI synchronously puts a model in a decision path (ADR-005), and it would do
  so through a call that looks like any other.

DOC-10 §12 calls containment "load-bearing": a successful injection must produce a misleading *narrative*, not a
state change. Indirect prompt injection through ingested findings is the fifth highest-risk surface, reachable by
an attacker with no platform access — this test is what bounds the consequence to text.

## `PRD-AIC-034` — the gap a validator leaves

Substitution over validation, because it "makes an incorrect number unrepresentable rather than detectable".
`bind()` rejects any digit outside a placeholder.

**And any quantity word.** A validator compares generated *numbers* to source records; it fails on the number it
does not recognise as one. "Roughly a third of your services are affected", "this is double last quarter", "the
majority of findings are unresolved" — all numeric claims the platform did not compute, and all exactly what a
model reaches for when told not to emit digits. Four such phrasings are in the corpus.

The placeholder grammar admits no expression and no default: an expression is arithmetic the platform did not
perform arriving through the mechanism that exists to prevent that, and a default renders a number nobody
computed when the field is absent. An unbound placeholder fails rather than rendering "we found  criticals" —
**a reader completes that sentence themselves.**

## The harness gates release, and five gates admit no argument

`PRD-AIC-049`'s reasoning is not strictness: the five are absolute "because each failure is a specific defect
rather than a quality distribution — **an invalid citation is wrong, not slightly wrong**."

`Gate.recordException` throws for an absolute measure, and for a rate measure it requires a justification *and
the failures named* — a count is a number somebody compares against next quarter's without knowing whether they
are the same failures.

Two shapes that would make a harness look like it is working, both refused: a result over zero scenarios (reports
a perfect score and means nothing), and a missing measure — **an unmeasured property in a release gate is a
property nobody is holding.**

---

# Prompt 17 — session 2: knowledge and migration import

Completes prompt 17. Automation rules landed in prompt 9.

## Knowledge — the invariant that decides whether the base is worth having

`INV-KBS-03` requires an owner and a review date, both at construction. **An article with no owner has nobody to
ask, and one with no review date is never wrong — it is simply old, which reads the same as current to a reader
who was not there.** Guidance that is wrong is worse than absent: it carries organizational authority and directs
engineers toward a pattern that was correct three years ago.

Staleness is **derived from the date**, not swept. A knowledge base whose staleness depends on a cron job is one
where the guidance is stale and the label is not.

An overdue article stays readable with a qualifier rather than being withdrawn — a dead link sends the reader to
a search engine, and what they find there is not tenant-specific and not reviewed either. A review must move the
deadline forward: one that changed nothing is a click, and the mechanism depends on somebody having read the
article.

## Migration — the capability and the abuse case are the same capability

`PRD-ING-049`: "Migration writes historical authorship, which is **also the capability to fabricate a record of a
decision never made**."

- **There is no way to build an unflagged migrated record.** `migrated()` takes no argument and returns true.
- **An unresolved author becomes a marked placeholder carrying the external identifier**, never an arbitrary
  principal. Without the identifier a reader knows only that somebody unknown said this; with it they can go and
  ask the incumbent system.
- **`Authorship` is exactly one of resolved or placeholder.** Neither leaves the record unattributed; both would
  let a presentation pick whichever it found.

`Authorization` validates all three of `PRD-ING-051`'s conditions to construct, and `execute` takes it as a value
rather than trusting a caller's check. The scope-validation condition carries its own reason: **a bulk historical
write is the most attractive place to skip scope validation, because the records "already exist" somewhere
else.**

## Fidelity reports what was lost, in the same shape as the closure figure

`PRD-ING-052`: "A migration reporting only success conceals what was lost, and **what was lost is discovered
months later when someone looks for a decision that is no longer recorded**."

So there is no `recordsImported` accessor — a test scans for one. `summary()` returns the count with the dropped
fields and unresolved authors **named rather than counted**, and it states the OQ-025 working assumption inline,
because a reader needs to know the dropped list is this source's rather than a general one.

---

# Prompt 18 — interface

DOC-08 sections 9 and 10, and the token rule.

## A correction to my own note

The `@Disabled` annotations on authorization assertions A18 and A21 said the identity module arrives in prompt 18.
**That was my assumption, not the plan** — prompt 18 is the interface, and no prompt in
`IMPLEMENTATION_PROMPTS.md` assigns the identity and access context (DOC-03 §17) to a session. The annotations now
say so, which makes the gap in the prompt sequence visible rather than resting on a wrong forward reference.

## `PRD-UIX-022` — the decision needs the population, so a null check cannot substitute

"Rendering unmeasured as zero is **the interface-layer expression of the PP-1 failure the whole corpus guards
against**: a favourable figure produced by absent data." Every other honesty mechanism — the coverage qualifier,
the confidence band, `NEVER_SUBMITTED`, the closure guards — is undone by a component that renders `null` as `0`.

So `UNMEASURED` is a state and not a value, `render()` produces no numeral for it (asserted with a regex), and
`forMeasure(measured, inScope, filtered)` requires the measured population. **A component asking "is the value
null" gets a zero; asking this gets `UNMEASURED`**, because it cannot answer without knowing what was measured.

Seven states, not six: DOC-08 says "six states" and its table carries seven rows, because the two empty ones are
separately specified. Conflating them tells a user their estate is clean when their filter is wrong, and both look
identical in a table with no rows. A test asserts all seven render distinguishably — two states rendering the same
text are not visually distinct however they are styled.

## Tokens: substitution, and colour never alone

A token missing a mode cannot be constructed, and the message says why: it **falls back to whatever the component
would have done — the literal the token exists to remove — and the missing mode is always the one nobody opens.**

A semantic token must declare a non-colour channel. `HIGH_CONTRAST` and `PRINT_MONOCHROME` are first-class modes,
because an executive report is printed and a colour-only signal in it is a signal nobody sees.

**The unmeasured token's channel is a text label, not an icon.** An icon is a convention a reader learns, and the
one reader who has not learnt it reads an unmeasured tile as an empty one — the failure `PRD-UIX-022` exists to
prevent, arriving through its own mitigation.

## `PRD-UIX-025` — the least-reviewed output path

Error text is checked against a reconnaissance list: stack frames, exception class names, SQL fragments, internal
hostnames, JDBC URLs. Six examples in the corpus. The check is in code rather than in a review because "error
surfaces are among the platform's least-reviewed output paths" — which is precisely why a review does not catch
them.

## The fourteen surfaces, counted

DOC-08 §10 gathers them "so they are implemented as a coherent set rather than discovered one at a time", and the
count is asserted — a missing one is otherwise invisible. Two of the fourteen resolve to §9 states rather than
separate mechanisms, recorded so a reader does not go hunting for a fourteenth mechanism that does not exist.

---

# Prompt 19 — deployment and operations

DOC-15. A new `:deployment` subproject, one migration, and four verifications against the live engine.

## What this subproject is for

Every property DOC-15 requires is otherwise expressed in an orchestration manifest, and **a manifest is data a
reviewer reads**. `OPS-DEP-009`'s whole argument is that credential separation is "structural rather than
procedural — an application that cannot obtain the credential cannot use the bypass regardless of what its code
attempts". A manifest that injects `migration_runner` into the application tier satisfies that requirement's
prose and defeats its purpose, and it does so **in a configuration file, which is not where anyone looks for an
isolation defect**.

So the topology is a typed model that fails the build, and the manifests are generated from it. A checker and a
manifest drift; a generator cannot.

## The defect this surfaced: four tables provisioned, one alerted

`OPS-DEP-011` has two halves — creation automated ahead of need, **and a missing future partition alerts before
it would be required**. The provisioning half was built as each table arrived: `ensure_audit_partitions`,
`ensure_transition_log_partitions`, `ensure_risk_score_partitions`, `ensure_automation_execution_partitions`.
The alerting half was built once, in V002, for `audit_event` alone.

**Each migration is correct in isolation. The gap is only visible when the set is enumerated**, which is what
building the deployment model did. `V013` closes it with one report over `pg_partitioned_table` rather than a
function per table — because a hand-maintained list is what produced the gap, and it would produce it again. A
verification creates a range-partitioned table inside a rolled-back transaction and asserts the report finds it.

The four tables that had provisioning and no alert include the transition log, which DOC-15 §4 gives the same
property as the three unreorderable build blocks: **an insert rejected during a partition outage is gone, not
delayed.**

`V013` also records `hash_partition_basis` — `OPS-DEP-012`'s second half, which is the half that gets omitted.
A count answers "how many"; the basis answers "why", and why is the only question a resize decision needs. A
`CHECK` rejects a basis under forty characters, because `default` satisfies "recorded" and answers neither.

## Containment before diagnosis, checked as text rather than as a flag

`OPS-DEP-050` on the cross-tenant runbook. The instinct under an isolation alert is to find out whether it is
real before doing anything disruptive — **correct for almost every other alert in the platform, and wrong for
this one in the direction that cannot be undone.**

A `containsFirst` flag would be set to true by whoever wrote "immediate action: investigate whether the assertion
is a false positive", because they believe they are containing. So the check reads what the runbook actually
instructs and rejects an investigative verb in a highest-severity immediate action.

None of the fifteen carries a rehearsal date, and a test asserts none does. `OPS-DEP-049` requires rehearsal
before production; no rehearsal has happened, and recording a date would assert one that did not occur. The empty
optional is what makes the debt countable.

## Two Error Prone findings, and the second one was the tell

`ImmutableEnumChecker` rejected a `Set` field on `RuntimeUnit`. The bitmask that replaced it drew `EnumOrdinal`.
Both are right — and **a design needing an ordinal-indexed bitmask to satisfy a checker is a design putting
configuration where identity belongs.** A unit's placement and resource profile are what it *is*; its allowlist
is what a deployment *grants* it. The allowlist moved to `EgressAllowlist`, and the build went quiet.

`EgressAllowlist` throws on a unit with no entry, because under deny-by-default at the cluster **a unit with no
policy gets the cluster default, and the cluster default is allow** — a missing entry is the opposite of what its
absence suggests.

## An observation for DOC-15 §4, recorded rather than resolved

Its unit table has seven rows and none is an AI worker, so model-provider egress has no unit of its own and lands
on the general workers by elimination — the same allowlist as the parser that processes hostile documents. That
is the union problem the per-unit allowlist exists to avoid, arriving because the table has no row for it. An
eighth unit is a topology change and belongs in the document.

---

# Prompt 20 — verification pass

`tools/generate_matrix.py`, `_traceability/matrix.csv` (1,186 rows), `_traceability/GAPS.md`, and step 7 of
`verify.sh`.

## Both gates fail, and the numbers are the deliverable

| Gate | Result |
|---|---|
| Forward — every `MUST_HAVE` has a passing test | **FAIL.** 404 of 1,113 active `MUST_HAVE` (36.3%) |
| Backward — zero schema objects tracing to no requirement | **FAIL.** 13 of 136 objects |
| Backward — zero API operations tracing to no requirement | **VACUOUS.** The registry holds no operations |

The 707 with no test split into two categories that a single percentage hides. **619 are not built.** The other
**88 are cited by main source or schema and by no test — built and unverified**, which is the worse of the two
and the one worth acting on first.

Two more are cited only by a `@Disabled` test: `PRD-ING-014` and `SEC-TEN-011`, both in the isolation-path
inventory. They were already countable debts; now they are countable in the gate as well.

**Vacuous, not passing.** The API half of the backward gate has nothing to check because no module has
registered an operation. Recording that as a pass would be a gate over an empty set passing for the wrong
reason, which is exactly how a gate stops being a gate.

## Three cell values, because a blank and a gap are different things

DOC-16 §16.1 asks for a value or `N/A` with a reason. That vocabulary cannot express the difference between *this
requirement owes no artifact of that kind* and *this requirement owes one and has none*, and collapsing the
second into the first is **how a traceability matrix comes to read as complete while covering a third of the
corpus**. So `MISSING` is a third token and only it feeds the gates.

`N/A` is reserved for the two cases knowable from the register alone: a superseded requirement, and one whose
verification set does not include `AUTOMATED_TEST`. My first version wrote confident reasons for every empty
cell — "the requirement constrains behaviour, not storage" — which reads well and **is a guess. This tool cannot
tell whether a requirement needs a table, and asserting it does not is how a matrix certifies absences nobody
checked.**

## Four defects in my own tool, three of them silent

1. **The verification field is a pipe-separated set** — `AUTOMATED_TEST|PENETRATION_TEST` and twenty other
   combinations. Treating it as a single value matched only the 719 plain rows and read every combined value as
   unrecognised, so 121 requirements verified by test *and* something else were scored as owing no test.
   Coverage read 283 instead of 404.
2. **The test-method regex anchored on four spaces.** These suites use `@Nested`, so methods sit at eight or
   twelve, and almost every citation was attributed to the class. The matrix filled with
   `<class-level citation>` — a coarser answer that still looked like an answer.
3. **A short-circuit on "no requirement identifier in the file" threw away the traces that were there.** V007
   cites invariants and design sections and no requirement identifier at all, so every object in it was reported
   untraced. Found by checking one name on the fail list against the file, which is the check a summary count
   does not invite.
4. **The schema trace ignored `INV-*`.** The invariants are the specification; a trigger function citing
   `INV-WRK-08` is traced to the domain model. Sixteen objects were on the fail list for citing the right thing.

The untraced count moved 67 → 36 → 17 → 13 across those fixes. **Every one of the four made the gate look worse
than reality**, which is the safe direction for a bug in a gate to fail and not a reason to have shipped it.

## Step 7 reports; it does not block

Making the forward gate fatal would stop every run of `verify.sh` for a condition that is documented, expected,
and not a regression — **and a gate that blocks on a known state is a gate somebody comments out**, which is
`OPS-DEP-026` running backwards. The numbers print on every run so a regression in them is visible.

## What is not closed, and why

The 13 untraced schema objects are listed with whether they carry a prose rationale: seven explain themselves and
name no identifier a tool can follow, six carry neither. Closing them means adding the requirement identifier
each object already implements. **A citation added to satisfy a gate on the day the gate was written is the
weakest evidence available**, and it needs whoever can confirm the mapping against the owning document.

---

# Deployment — `deploy/`, and what it cost to make it actually run

Not a prompt. Requested after the sequence finished, because the honest answer to "can we deploy
this" was no.

## The accounting first

Prompt 12 said: implement the API "for every resource group whose module now exists". I built the
framework — seven annotation classes, keyset pagination, `DenialResponse`, `OperationRegistry` —
and **left the registry empty**. No endpoint exists, and that is why nothing runs.

Worse: my own prompt-20 tool reported `VACUOUS (API) — the operation registry holds no operations`,
and I recorded it as a table row rather than naming it as the hole it is. Prompt 19 asked me to
implement deployment and I delivered a typed model instead of artifacts.

No prompt in the sequence asked for an entrypoint, an HTTP server, or packaging. That is a defect
in the plan rather than something I dropped — but it should have been raised at prompt 2.

## What now runs

`docker compose up` stands up PostgreSQL 18, Valkey and MinIO per ADR-049/055/056, applies V001 to
V013, and passes a conformance job. Verified end-to-end, not just written.

**The compose file enforces `OPS-DEP-009` rather than describing it.** Each login user appears in
exactly one service. `offboarding_executor` gets no login user anywhere: it is dual-control gated
and is *also the mechanism an insider would use to destroy evidence*, so a password for it in a
compose file is the opposite of dual control.

## Five defects, four of them mine, all found by running it

1. **`find | sort` sorts by PATH, not filename.** V010 ran before V001 and failed on a function
   V001 had not created. My script's comment claimed it sorted by version. `ON_ERROR_STOP` caught
   it and stopped cleanly, which is the one part that worked as designed.
2. **`CREATE ROLE ... BYPASSRLS` requires superuser, and V001 runs as a non-superuser** — so V001
   cannot create its own roles in this topology. I predicted this in a comment and then pre-created
   only three of the five it declares. **Creating a group role and issuing a credential are
   different acts**, which is the whole point of the separation.
3. **PostgreSQL 18 images changed the data directory layout.** Mounting `/var/lib/postgresql/data`
   as on 17 makes the container refuse to start.
4. **`hash_partition_basis` in my V013 had no grants.** Every function in that migration was
   granted explicitly and the table was not, so `integrity_verifier` could not read the record it
   exists to check. Found by the conformance job, not by reading the migration I wrote yesterday.
5. **I pointed the test suite at the deployed database.** It failed correctly:
   `integrity_verifier` cannot create a table. The suite creates and drops schema and switches
   between all four roles — authority no deployed environment should hand a running job. Replaced
   with a read-only conformance job, which is what DOC-15 §5.1 says that role is *for*.

## Two things the run taught that the corpus states and I had not internalised

**`SET aspm.current_tenant` outside a transaction is refused.** `current_tenant_id()` raises rather
than defaulting. That refusal is the control: a session-scoped default would survive into the next
borrower of a pooled connection, which is exactly `OPS-DEP-010`.

**Three tables are deliberately outside row-level security**, and one of them —
`tenant_id_reservation` — *has* a `tenant_id` column, because there it is the subject of the row
rather than its scope. My first post-condition check used "has a tenant_id column" as the predicate
and flagged it. The exceptions are now named explicitly, so an **undocumented** one still fails; a
predicate clever enough to exclude this one would exclude the next for the wrong reason.

## Isolation, demonstrated rather than asserted

As `app_runtime` in tenant A's context: tenant B's rows are absent from a plain read, an explicit
predicate for tenant B returns **zero rows rather than a denial** (`PRD-API-036`), and an INSERT
carrying tenant B's identifier is rejected by the policy's `WITH CHECK`. Both directions.

## Native `uuidv7()` is now verified, and only here

The build runs against an embedded PostgreSQL 17.5 with a documented shim. The conformance job
checks `prolang = 'internal'` — not merely that the function exists, which the shim would satisfy —
because applying the shim to a real 18 would replace the engine's implementation with a weaker one,
permanently and invisibly.

## Also added

A Gradle wrapper, which did not exist. The conformance path runs offline against the host's Gradle
home, matching DOC-15 §14: no registry access at deploy time.

---

# The application tier — endpoints that answer

The debt prompt 12 left. ADR-057 records the HTTP runtime decision, which DOC-15 §2's eight deferred
selections never covered.

## What runs

`docker compose up` now stands up an application tier alongside the data tier. Three operations over
organization node types, chosen because node types are the configurable structure of ADR-027 — they exercise
tenant configuration, scoped read and configuration write over a table that already carries row-level
security.

**The endpoints do not bypass the kernel; they are wired into it.** Authorization goes through
`ScopeResolvingAuthorizationGate`, which is the only class permitted to mint an `AuthorizedQuery` — so a
handler that skipped the gate would have nothing to pass to the query layer. Deny-by-default as an absence
rather than as a branch that could be inverted.

## ADR-057, and why not a framework

The binding constraint was ADR-036: the seven annotation classes are "framework properties, not
per-operation code". A framework with its own filter chain competes for exactly that, and then **an
operation has two places to declare its security characteristics and the weaker one sometimes wins.** So
the JDK's HTTP server supplies a socket and the platform's `Dispatcher` is the single enforcement point.
The cost — routing, content negotiation and body parsing become our defects — is recorded in the ADR's
Gaps accepted, along with the absent request timeout and body limit, whose compensating control is the
ingress that DOC-15 §3.1 already requires.

## Five defects, and running it is what found them

1. **An unregistered path returned 500, not 404.** `Response.notFound()` carried a record; `Json.write`
   rejects a type it does not know, so it threw, was caught by the catch-all, and became a 500 **with a
   correlation identifier** — trivially distinguishable from a 404. `PRD-API-036` says a denial is
   indistinguishable from non-existence; I had written that claim in three comments and it held in none of
   them.
2. **`org.node_type.read` is not a valid permission code.** `PermissionId` requires the catalogue shape and
   rejected the underscore — **at request time**, and the 400 told the caller which permission the
   operation requires. Every code is now constructed in `registry()`, so a malformed one fails at startup.
3. **No tenant context was established at request entry**, so the kernel threw
   `MissingTenantContextException` on every authorized call. It did not produce an unfiltered read: that is
   `SEC-TEN-005` fail-closed working exactly as specified — **the platform refused rather than guessed.**
4. **The healthcheck used `CMD-SHELL`, which is `/bin/sh`**, and `/dev/tcp` is a bash feature. It reported
   "Directory nonexistent", which reads like a missing file rather than a missing shell feature.
5. **The matrix generator's block boundaries were off by one**, so the first operation was reported as
   traced by the second's citations. Found because a `FAIL` line named an operation I had just commented.

## The API gate is no longer vacuous, and the tool no longer claims it is

Prompt 20 hard-coded `VACUOUS (API) — the registry holds no operations`. That became a false statement the
moment three were registered. The generator now enumerates operations from source and checks each one's
block for a requirement citation: **3 registered, 0 untraced.**

**The gate passing is not the API being built.** Backward traceability asks whether what exists traces to a
requirement; forward coverage is where the missing operations show up, and it is at 36%.

## The largest gap, contained by construction rather than by intent

`DevPrincipalResolver` reads the principal from headers, which is what `SEC-TEN-004` forbids a tenant to be
derived from. It exists because **an endpoint nobody can call is not evidence that the enforcement above it
works.**

It refuses to construct unless `ASPM_ENVIRONMENT=development`, so the gap cannot be lifted into production
by copying a compose file — the server fails to start. It states what it is at startup, so an operator
reading a production log is looking at a misconfiguration rather than inferring one.

Scope is still derived: the grant arrives in a header, and the subtree expansion is real SQL over
`org_closure`. Replacing the resolver closes it; nothing else changes.

---

# The rest of the API — six resource groups, 23 operations

## A descriptor, not a hundred hand-written endpoints

DOC-05 §12–§25 specify well over a hundred operations. Hand-writing each is a hundred chances to omit the
scope predicate, the projection, or the concurrency check — and **ADR-036's whole argument is that a
security characteristic an operation can omit is one that will be omitted.**

So a `ResourceGroup` declares its table, scope column, projection, filterable set and writable sets, and
`ResourceEndpoint` supplies the behaviour. There is no per-operation code in which to forget anything.

**The projection is the query, not a filter over it.** `SELECT *` with a filter inverts the default: a
column added by a later migration is exposed until somebody hides it, and on a security platform the
column most likely to be added is a sensitive one. A column absent from the descriptor is absent from the
SQL — ADR-047 enforced one layer earlier than the representation.

Two absences that look like oversights and are not: `asset.attributes` is tenant-defined custom fields and
would return values nobody authorized individually (`SEC-AUZ-022`); `finding.description` is
attacker-authored by design and belongs to the evidence path of `OPS-DEP-016`, from a distinct origin, not
to a JSON field on the application origin.

## Findings register no create operation at all

ADR-011 makes normalization and deduplication one pipeline shared by import and matching, so a finding
created through a REST `POST` would bypass fingerprinting and become a duplicate nothing reconciles.
`writableOnCreate` is **empty rather than absent**, so the refusal is visible in the descriptor rather than
inferred from a missing line.

## Two defects, both about the difference between describing and running

1. **`POST /assets` was gated by `ast.asset.update`.** One `writePermission` served create and update;
   DOC-05 §12 and §13 assign separate codes. Collapsing them means a principal who may correct a name may
   also create structure — and `SEC-AUZ-001` requires every catalogue entry to gate something, so one code
   doing two jobs makes the other entry gate nothing, which that requirement calls a defect.

2. **The matrix generator parsed `PlatformOperations.java` for constructor calls.** That worked while
   operations were listed and **silently became wrong** when they were derived: it found four calls inside
   a loop and reported four operations where the platform serves 23. **A parser guesses at what code will
   do.** Replaced by a manifest the build writes from the real registry — and the tool now reports an
   absent manifest as *unknown*, never as zero, because zero would pass the backward gate vacuously.

## A fabricated citation, caught by making the tool check

`CFG-AST-002` was cited as the trace for asset types. **It does not exist.** No compiler catches a
requirement identifier in a string, and no reviewer did either. The generator now verifies every API
citation against the register and reports fabrications as a gate finding — the same class of defect the
corpus tooling was built for, arriving in the tooling's own new column.

## Demonstrated, not asserted

Two org nodes in one tenant, the principal granted one: **the collection returns one and the other is a
404 by id.** That is scope authorization inside a tenant, which is a different control from tenant
isolation and fails independently of it. `PATCH` on the ungranted node is a 404; on the granted node it
succeeds and `row_version` moves 1 → 2; repeating with the stale version is a 404 again — **three causes,
one response**, because distinguishing a version conflict would confirm existence to a caller who may not
see it.

---

# The interface — DOC-08, server-rendered

ADR-058 records the technology decision, which DOC-15 §2's eight deferred selections and ADR-057 both
left unmade.

## Why not a framework, in one line

DOC-08's hard requirements are WCAG 2.2 AA **per success criterion**, keyboard parity for every pointer
action, every string externalized with **no concatenated sentences**, pseudo-localization passing, and
an unmeasured value that never renders as zero. Every one of those is a property of the markup that
reaches the browser. **None is made easier by a component framework and two are made harder** — a
synthetic event layer is where pointer-only handlers appear, and a template language is where a
concatenated sentence stops looking like one.

## Four defects, three of them in things I had just written

1. **`java.text.MessageFormat` is not ICU.** It has `choice` and no `plural`, so a bundle written in ICU
   syntax throws at format time. `INT-UIX-008` requires ICU *because* "plural rules differ in ways a
   substitution cannot express" — using the JDK class and calling it ICU would be the exact claim the
   requirement exists to prevent. ICU4J is now a dependency, and the reason is in the build file.

2. **Pseudo-localization transformed the formatted result, not the pattern** — so substituted values were
   accented too, and a test asserting a tenant name survives it failed correctly.

3. **The pseudo-locale skipped everything inside braces**, which meant every ICU plural sub-message went
   untransformed: `⟦1 row···⟧` rendered with `row` unaccented. **That is the part of a string most likely
   to grow in translation, and it was the part the gate did not check.** Fixed by depth parity — even
   depth is a sentence and gets transformed, odd depth is an argument or a keyword and does not.
   Accenting `other` would make the pattern unparseable.

4. **S13 caught my own note about defect 4 from the previous session.** Documenting that a fabricated
   requirement identifier had been cited put the identifier back in a source file, and the architecture
   test flagged it again. The comment now describes it instead of quoting it — and the episode is why
   two checks exist: S13 scans sources, `generate_matrix.py` scans the emitted manifest, which is data
   and outside S13's reach.

## What the shell carries so a page cannot omit it

The current scope (`PRD-UIX-011`: "a user uncertain which slice they are viewing will misread every
figure on the page"), the skip link and landmarks, and the development-authentication warning. All three
are rendered by `Page`, not by the page.

Colour is never the sole carrier. The seven states are distinguished by border treatment — dashed, solid,
double, dotted — because **high contrast and monochrome print are where a colour-only distinction
disappears, and an executive report is printed.** Contrast is computed in the test, not judged by eye:
4.5:1 for primary and secondary text on their own surface, in both themes, neither derived from the other.

## Demonstrated in a browser, not asserted

Signed in through the form, `GET /ui/org-nodes` returns **one row** where the tenant holds two — the
node outside the principal's grant is absent from the page exactly as it is absent from the API, because
the page calls the same `ResourceEndpoint`. Null cells render "Not measured" rather than blank. Three
locales verified live: English, Vietnamese, and the pseudo-locale.

Vietnamese carries no ICU `plural` forms and that is **correct rather than unfinished** — the language
does not inflect nouns for number. A source bundle that needs plural and a target that does not is
`INT-UIX-008` working.

## Not built, and named rather than implied

WCAG conformance is not yet documented per success criterion, which is what `INT-UIX-001` actually asks
for. Right-to-left is structural only — logical properties and `dir` — and unverified against a real RTL
locale. Tenant vocabulary override (`INT-UIX-013`) has no data behind it, so table headers are schema
identifiers. There is no command interface backing store, no charts and therefore no tabular alternative
(`INT-UIX-006`), and no density or theme persistence (`PRD-UIX-007`).

---

# The interface, redesigned

The first version was a correctness scaffold with no design: a bare table whose headers were schema
column names, no information architecture, no dashboard, no charts. For a product meant to be sold that
is not a partial result, it is the wrong artifact. Rebuilt.

## The commercial constraint that shapes the navigation

The module list for this platform is written in one conglomerate's vocabulary — Business Unit, P&L. **A
sidebar with "Business Unit" compiled into it cannot be sold to the second customer**, whose levels are
divisions or something else again. `PRD-UIX-009` and ADR-027 forbid it, and the reason is commercial as
much as technical: DOC-08 §6.1 says the interface "cannot assume four levels, cannot assume level names,
and cannot assume a user starts at the top".

So section names are **product** structure — Operate, Estate, Configure — and every organizational level
name arrives as tenant data through the scope switcher. The customer's vocabulary belongs in seed data.

## What was built

A real shell: persistent grouped sidebar, command interface on `⌘K` with filtering and Enter-to-open,
scope switcher, breadcrumbs from the caller's scope root, theme and density controls. **Azure's breadth at
Linear's density** — the sidebar carries breadth, the command interface removes the cost of depth.

A design system with four independently declared modes. Dark is not an inversion: `PRD-UIX-006` forbids
it because "inverted palettes fail contrast in predictable places, muted text on raised surfaces above
all, and the failure is in the theme that receives less design attention". Contrast is computed in the
test, not judged by eye.

An overview dashboard with KPI cards, server-rendered SVG trend charts, coverage meters and drill-down.
Charts are SVG from Java — no build step, and the tabular alternative `INT-UIX-006` requires is emitted
beside every one rather than generated on request.

A dense data table: presentation column order derived from the descriptor, severity and state as pills,
identifiers as monospace chips with the full value for a screen reader, timestamps with the zone, sticky
header, focusable rows, `j`/`k` navigation, and filters restricted to the declared filterable set —
because an interface that can express what the API rejects teaches its users to be wrong.

## The dashboard shows "Not measured" on every card, and that is the product working

There is no projection running and no assessment or SBOM data, so nothing has been measured. **A dashboard
seeded with plausible demonstration numbers would be indistinguishable from a working one to anyone
evaluating this product**, and it is the exact failure the honesty surfaces exist to prevent: the first
product principle is that absence of evidence is not evidence of absence.

So the KPI cards render the unmeasured state with no numeral anywhere in the markup, the coverage meters
are hatched rather than 0%, and the trend chart's line does not draw across unmeasured periods — its
tabular alternative says "not measured" per period instead of showing zeros. **A trend drawn through
absent data is the most persuasive way this platform could lie.**

## Still not built, named rather than implied

The eight modules in the brief are one page each at most. Absent: Pentest Request workflow, Assessment
history, SCA dashboards and Trivy result import, AI narrative, Excel/CSV import-export, the AppSec
workload and SLA dashboards, per-engineer forecasting. Tenant vocabulary override (`INT-UIX-013`) has no
query behind it, so table headers are de-slugged column names. Theme and density are not persisted
(`PRD-UIX-007`). Object search in the command interface has no index, and the palette says so instead of
showing an empty box. WCAG conformance is not yet documented per success criterion, which is what
`INT-UIX-001` asks for. Authentication is still the development resolver.

---

# Pentest Request intake — the screen that replaces the tracker

Chosen first because it is where this platform stops being a tracker with custom fields. The fields that
decide whether a pentest can start are not free text — they are invariants the schema already enforces, and
the page's job is to make them actionable before submission rather than reasons for rejection after it.

## Three things a Jira project cannot do

1. **Readiness is four conditions and an attestation, not a flag.** `INV-ASM-04` blocks acceptance until
   readiness is complete, and DOC-04 §12.2 says why it is four columns rather than one: "so an incomplete
   readiness names *what* is missing rather than being a single opaque flag." The panel lists which one is
   outstanding. The seeded request is missing seeded test data, and the page says exactly that.

   The attestation is separate from the four flags, because **a condition marked met by nobody is a claim
   with no author.**

2. **Two accounts per role, and the page counts.** `INV-ASM-02` is a set assertion; the panel names the
   roles that fail it — here `Member`, which has one — with the reason: one account per role cannot
   demonstrate horizontal access control, **which is the defect class the assessment exists to find.**

3. **A credential is a vault reference and there is no reveal control.** `INV-ASM-03`, and `SEC-SEC-024`
   permits a reveal only through an explicitly permissioned, step-up-authenticated, per-object-audited
   operation — not implemented, so no button offers one. Verified in the rendered page: `vault://` appears,
   the word "reveal" appears only in the sentence explaining its absence, and no plaintext does.

Derived values — priority 78, effort 6.5 days, feasible start — are labelled as derived. `INV-ASM-08`: no
API surface writes them. **A number a requester believes they can edit is a number they will argue about.**

## Two constraints refused the seed data, correctly

`ck_assessment_request__derived_versioned` rejected a priority score with no model version — a derived
value that cannot say which model produced it is a number nobody can re-derive. Then
`ck_assessment_request__scope_complete` rejected a partial scope descriptor: `INV-ASM-07` requires the
whole descriptor at submission, because a submitted request without one "would be a request nobody can
authorize a read of".

Both are the schema doing the job the interface is built on top of. Neither was a code defect.

## Child rows are authorized by their parent, deliberately

`ResourceEndpoint.children` reads role accounts and environments only after a full `get` on the request has
passed — which re-validates against the object and returns absence for anything out of scope. A role
account carries no scope descriptor of its own and has no independent existence, so the parent's
authorization is the right one here in a way it would not be for a sibling resource. Table and column names
are validated against an identifier shape rather than escaped, because escaping an identifier is a
different operation from escaping a value and the two get confused.

## Requests are read-only over REST, and that is `INV-ASM-07`

A create would have to resolve scope for a draft — which the invariant forbids — or write a request nobody
can authorize a read of. `writableOnCreate` is empty rather than absent so the refusal is visible in the
descriptor. State is exposed and not updatable: a `PATCH` on `state` would bypass every guard the
workflow transition carries.

## Next, and still absent

The transition actions themselves (submit, triage, accept, schedule) with their guards; comments and the
attachment path; SCA import and its per-project dashboard; the AppSec workload and SLA dashboards. And
authentication is still the development resolver.

---

# Request transitions — and two defects the specification exposed

Implementing transitions meant reading DOC-09 §4 properly, and the reading found two things wrong with
migrations I had already written and tested.

## Defect 1: a fixed enumeration on a configurable machine

V010 constrained the request state with

```sql
CHECK (state IN ('DRAFT','SUBMITTED','TRIAGED','ACCEPTED','SCHEDULED','IN_ASSESSMENT',
                 'REJECTED','DEFERRED','WITHDRAWN','MERGED'))
```

**DOC-09 §4 marks the Assessment Request machine `[configurable]`**, and §2.2 permits a tenant to add
states within a category and to add, remove or rename transitions. A `CHECK` enumerating states makes
both impossible — a tenant adding a state gets a constraint violation, and ADR-027's promise of a
platform deployable by any conglomerate without code modification is not deliverable. CLAUDE.md lists
this exact shape under prohibited patterns.

**The names were wrong too, and the gap was larger than the names.** DOC-09 §4 specifies 25 states; V010
allowed 10, of which `TRIAGED` and `IN_ASSESSMENT` appear nowhere in the document. `REPORT_DRAFT`,
`REPORT_UNDER_QA`, `REPORT_DELIVERED`, `FIXING`, `RETEST_REQUESTED`, `RETEST_IN_PROGRESS`,
`CLOSED_PASSED` and `CLOSED_WITH_ACCEPTED_RISK` were **unrepresentable** — the intake half of the
product's core workflow existed and the delivery half did not. No test caught it because no test
asserted the schema against §4's diagram.

## Defect 2: no workflow definition could be authored at all

`workflow_definition.initial_state_id` was `NOT NULL` in V009, and `workflow_state.definition_id`
references the definition — so **the initial state cannot exist before the definition that names it**.
The tables were written, tested for their constraints, and unreachable as a whole, because no test
created a definition end to end. V009's own `validated_before_active` guard already implied the fix: an
initial state is required to *activate*, not to exist.

## V014, and the machine as data

Widens `workflow_definition` to bind an assessment type; replaces the `CHECK` with a trigger validating
against `workflow_state`; adds `assessment_request_transition` — the "or the equivalent" DOC-09 §3
permits for machines that are not work items — append-only by grant, not by convention; and adds the
`PRD-WRK-034` validation function. Seeded with §4's machine: **22 states, 32 transitions, zero defects,
definition `ACTIVE`.**

## Verified against the specification, event by event

| Attempt | Result |
|---|---|
| `GET .../transitions` from `SUBMITTED` | `begin_triage` permitted; `cancel` **blocked with `permission`** rather than hidden |
| `accept` from `SUBMITTED` | `409 STATE_TRANSITION_INVALID` — not available from this state |
| `begin_triage` | `200`, `SUBMITTED → INTAKE_REVIEW` |
| `begin_triage` again | `200`, `already_in_state: true`, **no second record** (DOC-09 §3 idempotency) |
| `accept` | `422 GUARD_FAILED`: *readiness_data_seeded; readiness_attested_at; roles with fewer than two accounts: Member* |
| `reject` with no reason | `422 REASON_REQUIRED` |
| `request_information` with a reason | `200`, and the log records **clock paused** because the destination is `WAITING_EXTERNAL` |
| `app_runtime` UPDATE on the log | `permission denied` — append-only is a grant, not a convention |

**The guard failure is the product.** It names which readiness condition and which role, so a requester
can act. A denial that said "not ready" would be a tracker.

## The evaluation order is ordered for a reason

`PRD-WRK-031` requires scope before permission, and names the consequence of getting it wrong: "ordering
scope before permission prevents a permission denial confirming that an out-of-scope object exists". So a
scope failure is the same 404 as a non-existent request, and the permission check at step 4 is only
reached by a caller who legitimately holds the object.

## One approximation, stated

The `qa_differs` guard should compare the QA approver against the **report author**. No report author is
recorded in the schema, so it compares against the requester. The check is weaker than DOC-09 §4
specifies and says so in the code rather than being treated as equivalent.

---

# SCA ingestion and dependency coverage

ADR-013: the module stores and matches; it does not execute scanners over source. ADR-024: the platform
never fetches, clones, or persists source code. ADR-023 makes the push API the only automated ingestion
path. So a scanner runs wherever the code already is, and its output arrives here — which is what the
brief describes.

## Three requirements shape the whole endpoint

**`PRD-SBM-037`: an unmatchable component is recorded, never skipped** — "silent skipping is the mechanism
by which a partially matched SBOM appears fully matched". Submitted six components; three canonicalized,
three recorded as unmatchable with **distinct enumerated reasons**:

```
pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.15.2 → unchanged        maven   ok
pkg:npm/Express@4.18.2                                       → pkg:npm/express npm     ok
pkg:pypi/Requests_Lib@2.31.0                                 → requests-lib    pypi    ok
not-a-purl                                                   → NOT_A_PACKAGE_URL
pkg:npm/no-version                                           → MISSING_VERSION
pkg:weirdeco/mystery@1.0                                     → UNKNOWN_ECOSYSTEM
```

The canonicalization is per ecosystem because getting it wrong makes one package two rows: npm is
case-insensitive, pypi treats underscore and hyphen as equivalent, maven is neither.

**`PRD-API-038`: the response carries the score and every warning** — "the submitter is the only party who
can fix a low-quality SBOM, and they see the response, not the log". Quality 40, with
`UNMATCHABLE_COMPONENTS:3/6`, `COMPONENTS_WITHOUT_LICENSE:4`, `ARTIFACT_CREATED_UNCLAIMED`.

**`PRD-API-039`: an unknown artifact is created unclaimed, never rejected** — "rejection loses data at the
point of least detectability: a pipeline receiving a 4xx logs it and continues". The asset was created and
the response says so. It is created **unclaimed**, so it lands in the unowned queue rather than being
attributed to whoever ran the pipeline.

## Verified against the invariants

| Attempt | Result |
|---|---|
| Six components, three unmatchable | `201`, quality 40, every warning itemized |
| Identical content resubmitted | `200`, `created: false`, same snapshot id — `PRD-SBM-033` |
| Zero components | `422 EMPTY_SBOM` — `INV-SBM-03`, "the likely output of a misconfigured pipeline" |
| Tenant with no asset type | `422 TENANT_NOT_CONFIGURED` with the reason |

## Two defects, and the second is the interesting one

**A missing tenant configuration returned `500`.** My own guard fired correctly — the tenant had no asset
type — but a 500 tells a pipeline nothing it can act on. `PRD-API-038`'s reasoning applies beyond the
quality score: the submitter is the party who can get it fixed. Now `422 TENANT_NOT_CONFIGURED`.

**`last_confirmed_at` is `NOT NULL` on `asset`, and I passed null.** The schema was right to refuse: an
asset whose existence was never confirmed is one PP-1 cannot distinguish from an asset nobody has looked
at. An SBOM push *is* the automated observation `INV-AST-12` protects — it forbids a **manual edit** from
advancing the field, not an observation from setting it.

## The dashboard, and the requirement it exists for

`PRD-SBM-056` is described in DOC-22 as "the single most important requirement in the module". The coverage
query is a **left join from `asset`**, not a scan of the coverage table — an asset that never submitted is
absent from `sbom_coverage_state`, and "an asset absent from coverage reporting is an asset where absence
reads as absence of problems".

So `NEVER_SUBMITTED` is a status with a dashed pill and a word, component count renders as *Not measured*
rather than 0, and the quality meter is hatched rather than a 0% bar. **A zero-width bar reads as
"measured, and none".**

## Not built

Match runs against vulnerability intelligence — there is no intelligence feed provisioned, so no CVE
appears anywhere yet, and `PRD-SBM-053` forbids closure driven by a run that did not complete against
non-stale intelligence. Change sets between consecutive snapshots (`PRD-SBM-018`), license policy,
exploitability statements, and the per-asset drill-down page. SPDX input. And the submission still runs
under the development resolver, so `x-dev-service: true` stands in for the sender-constrained credential
ADR-004 requires.

---

# Demonstration data, and two defects it exposed

Seeded on request. The rule I held to: **the seed writes the record, never the measurement.** Eight org
nodes across three tenant-named levels, seven assets, ten findings, eight requests with role accounts and
environments — all inserted. Coverage figures, quality scores and transition history came from **running
the real write paths**: two SBOMs pushed through the submission API, five transitions through the
transition endpoint.

That distinction is not pedantry. A dashboard whose numbers were inserted by a seed script is
indistinguishable from a working one to anyone evaluating the product, and it is the exact failure the
honesty surfaces exist to prevent.

Vocabulary is data: `BUSINESS_UNIT`, `PRODUCT`, `PROJECT` are seeded node types, and the words appear
nowhere in the code.

## Defect 1: a guard that passed because there was nothing to check

`accept` succeeded on a request with **no test accounts at all**. The `submit_ready` guard checks
`GROUP BY role_name HAVING count(*) < 2` — and with zero accounts the `GROUP BY` returns no rows, so "no
role has fewer than two accounts" is trivially true.

**The same shape as the `array_length(empty, 1)` defect recorded earlier in this build**: an emptiness case
that makes a check vacuous rather than failing. `INV-ASM-02` is a set assertion, and the empty set
satisfies "every role has two" only if you forget to ask whether there is a role. Now:

```
422 GUARD_FAILED — no test account has been provided, so no role can have the two accounts
INV-ASM-02 requires — and the readiness attestation claiming otherwise is the contradiction
this guard exists to catch
```

The seed is what surfaced it: a request whose readiness *claimed* accounts were provisioned, and had none.

## Defect 2: "never submitted" read off the wrong column

The coverage dashboard reported **7 assets submitted, 0 never submitted** when two had actually submitted.
`sbom_coverage_state.quality` is `NOT NULL` with a default of `REJECTED`, so every asset with a coverage
row has a quality whether or not it ever submitted anything — and I was reading absence off that column.

**An asset nobody scanned was presented as an asset that was scanned and failed.** That is `PRD-SBM-056`
inverted by the code meant to honour it. The schema pairs `latest_snapshot_id` with `latest_snapshot_at`
(`ck_coverage__snapshot_complete`), and that pair is the only honest discriminator. Corrected:

```
1 with a usable SBOM · 3 submitted · 4 never submitted
group/payments-api          Never submitted   Not measured   Not measured
payments-authorization      Current           7 components   quality 100
policy-core                 Partial           4 components   quality 55
```

## What the pages show now

| Page | Content |
|---|---|
| Overview | 10 open findings measured over 7 assets; assessment and dependency coverage still **Not measured**, because no assessment has completed |
| Findings | 10 across seven classes, four severities, ages 3–55 days, two unassigned |
| Assets | 7, including one **unowned** and one with an **exposure conflict** — declared internal, observed internet-public |
| Requests | 8 across `DRAFT`, `SUBMITTED`, `INTAKE_REVIEW`, `SCHEDULED`, `IN_PROGRESS`, `FIXING`, `CLOSED_PASSED` |
| Dependencies | 7 assets, 3 with real snapshots pushed through the API |

Three overview KPIs still read **Not measured** and that is correct: nothing has measured assessment or
dependency coverage across the estate. Seeding those numbers is the one thing I did not do.

## Three constraints refused the seed, all correctly

`exposure_rank` knows `INTERNET_PUBLIC / PARTNER_B2B / INTERNAL_ONLY / AIR_GAPPED` and my values were not
on that scale. `INV-ASM-09` refused a retest that named no prior assessment — "a retest that cannot name
what it is retesting is a claim about work nobody can find". And `pgcrypto` is absent outside the test-only
shim, so the fingerprint uses the built-in `sha256`.

---

# The AppSec workload and service-level dashboard

Built on V014's transition log, which is why that migration had to come first: `PRD-CAP-001` requires
workload snapshots "by rollup from the state transition history", and `INV-WRK-03` makes that history
append-only — so a cycle time computed from it is reproducible.

## Three things separate this from a spreadsheet report

**1. Waiting time is reported separately, never absorbed.** `PRD-CAP-008` requires cycle time
"decomposed by workflow stage, including time in states awaiting external parties", and PP-6 makes waiting
visible and attributed. Each stage carries whether the service-level clock was running in it, and the
waiting queue shows the **reason recorded on the transition that paused it**.

**Absorbing waiting into one cycle time is how a team's throughput gets blamed for a requester who took
three weeks to seed test data.**

**2. Individual measures are gated, and the gate is absence.** `PRD-CAP-013` classifies per-person workload
RESTRICTED, accessible "only through explicit permission rather than by role seniority or organizational
position". Verified both ways: with `cap.team.read` alone the per-member panel is **absent** — not greyed,
not blanked — and with `cap.member.read.all` it appears. ADR-047: restricted fields are absent from
representations, not masked. **A greyed panel confirms the data exists and says how many rows it has.**

`PRD-CAP-014` requires the purpose statement to sit *where the measures are presented*, so it is inside the
panel rather than in a policy page nobody opens. And the table is ordered by identifier: **a table sorted by
count is a ranking whatever its caption says.**

Worth repeating from DOC-07 §5.2, because it is a product decision and not a technicality: the Business
Owner template excludes even `cap.team.read`, because "a business owner who can see aggregate security-team
capacity will direct requests by observed availability, bypassing the prioritization the platform exists to
enforce".

**3. Two figures render unmeasured, and name what is missing.** Service-level compliance: no policy is
configured and no clock is running, so nothing has a deadline — **a compliance figure over zero clocks is
100%**, which is the most flattering form of the PP-1 failure. Utilization: `PRD-CAP-005` defines it as
allocated over available capacity, and no capacity ratio or availability is recorded, so the denominator
does not exist. **A figure computed against a guessed denominator would be quoted in a staffing
conversation.**

`PRD-CAP-005` also requires utilization "against a configurable target band rather than against a maximum",
and the panel says why: a bar against 100% invites the reading that 100% is the goal, and a team at full
allocation has no capacity for the incident that is the reason the team exists.

## The defect, and it was in the worst possible direction

`INTAKE_REVIEW` — a working stage — was labelled **awaiting others**. The clock flag on a transition row
describes the state being *entered*, not the one being *left*, and I grouped by `from_state` while reading
that flag. The transition out of `INTAKE_REVIEW` went to `RETURNED_FOR_INFO`, which pauses the clock.

**The panel exists to separate waiting time from working time, and it was attributing working time to
waiting.** The stage's behaviour now comes from the stage's own `workflow_state` row.

## What it shows on the seeded data

7 requests submitted this week over 8 total; 10 open findings over 7 assets; 2 unassigned; findings by
severity with the over-30-days count beside each; requests by state with the clock flag per state; one
request in the waiting queue with its recorded reason; service-level compliance and utilization
unmeasured with the reason.

---

# Authentication — the login site, and ADR-004 narrowed rather than pretended

## The conflict, raised before any code was written

ADR-004 decided **OIDC/OAuth2 for humans**. The specified requirement is username or email, a password, and
a second factor. **These are not the same decision**, so ADR-059 records the narrowing rather than leaving
ADR-004 as the stated position — an implementation shipping local passwords under ADR-004's title would
leave a reviewer believing an identity provider is in the trust path when it is not.

Read through its consequences, ADR-004 was protecting three properties: no long-lived bearer secret usable
from anywhere; a second factor that is not optional; and **credential handling the platform does not have to
get right itself**. The first two are achievable locally. The third is genuinely given up, and it is the
first line of ADR-059's accepted gaps.

Roles are data. `ADMIN`, `PENTESTER`, `DEVELOPER`, `PROJECT_SECURITY_OWNER`, `BUSINESS_UNIT_MANAGER` are
seeded rows with tenant-defined labels; none appears in a constraint or in code, because `PRD-AUZ-001` makes
the permission catalogue product-fixed and the roles that group it tenant-composed.

## V015, and the requirement behind each unusual choice

**Argon2id, not PBKDF2.** `SEC-SEC-014` requires a *memory-hard* function. PBKDF2 is iteration-hard: an
attacker with a GPU gains far more from parallelism against it. Shipping PBKDF2 and recording ASVS Level 3
conformance is the documented-conformance failure that level exists to prevent. 64 MiB, 3 passes, and
**parameters stored per credential** so the cost can be raised without a mass reset — which is why nobody
ever raises a global one.

**No `locked_until` column anywhere.** `SEC-SEC-005`: "progressive delay and risk-based challenge *rather
than account disable*". An attacker who can disable a named account has a denial-of-service against the
platform, and the first account they would disable is the one that could stop them. The delay is capped at
30 seconds for the same reason — an uncapped delay *is* the lockout.

**An unknown username is verified against a dummy hash.** Without it an unknown identifier returns in
microseconds and a known one takes the full Argon2 cost, so **response time is a user-enumeration oracle**.
Measured: 0.25 s for a nonexistent user, the same order as a real one.

**`last_accepted_step` on the enrolment.** RFC 6238 §5.2 requires refusing a code already accepted. Without
it a code seen over a shoulder or lifted by a phishing proxy stays valid for the rest of its window — the
whole window that matters.

## Verified end to end, not asserted

| Step | Result |
|---|---|
| `/ui/overview` unauthenticated | `303 → /ui/sign-in` |
| `/api/v1/findings` unauthenticated | `401` JSON, **not** a redirect — a pipeline following one to an HTML form would get 200 and log success |
| Wrong password | `303 → ?failed=1` |
| Unknown username | identical response, comparable timing |
| Correct password, never enrolled | `303 → /ui/mfa-enrol` — **forced** |
| `PASSWORD_ONLY` session at the dashboard | `303 → sign-in`; the resolver refuses to produce a principal from it |
| Enrolment with a real TOTP code | confirmed |
| **Replaying that same code at the challenge** | **refused** |
| A fresh code | authenticated, and the **session token changed** — `SEC-SEC-009` regenerates on privilege change |
| Attempt log | five rows including both failures; `app_runtime` `DELETE` → `permission denied` |

## Two gaps stated in the code rather than left to be found

**The TOTP secret is not encrypted.** ADR-059 says it is encrypted at rest under the tenant key; no key
management exists and **OQ-026 is open**. The column is named `secret_ciphertext` and holds plaintext — a
name saying one thing and content another is not something to leave silent, so the key reference reads
`PLAINTEXT_PENDING_OQ_026`.

**No QR code on the enrolment page.** The setup key and the `otpauth://` link both work. Rendering a QR
needs an encoder that is not written, and the page says so rather than implying one is coming.

Also honest: `must_change_password` is set for every bootstrapped principal and **nothing forces the change
yet** — the change-password surface is the next piece, not a claim being made here.

---

# The 2FA QR code, and how it was checked without a reference encoder

Written rather than depended upon, for ADR-058's reason: no client build step, no registry access at deploy
time. Byte mode, level M, versions 1 to 10 — an `otpauth://` URI is 100 to 200 bytes and version 10 holds
213, so the range covers the input and stops well short of a general encoder's tables.

**A wrong QR is worse than no QR.** A user who scans a malformed code blames their authenticator, then
their phone, then support — the failure is expensive and points away from its cause. So the encoder returns
*nothing* rather than guessing when content will not fit, and the setup key stays on the page beside it.

## No reference encoder was available, so four properties were asserted instead

No pip, no `ensurepip`, no sudo — "it matches a known-good implementation" was not an available assertion.
What was available is mathematics, and each of these **disagrees with a wrong table rather than sharing its
error**:

1. **Reed-Solomon divisibility.** Data followed by its remainder must divide by the generator with zero
   left. A wrong generator, field or division fails this with no table consulted. Plus: every non-zero
   element of GF(256) has a multiplicative inverse, which a malformed log table would break.
2. **BCH bit strings computed, then compared to the published constants.** A wrong computation and a wrong
   memory of the table cannot agree by accident.
3. **Capacity derived from geometry.** Total codewords must equal the module count minus the function
   patterns, divided by eight — counted independently of the drawing code.
4. **Placement asserted positionally** — three finders, both timing lines, the single dark module.

## The three bugs those checks caught — and the one they did not

**The second format-information copy was off by one in both halves**, and its column half **overwrote the
single dark module** at `(size-8, 8)`. A reader would have found format information failing its own BCH
check and rejected the symbol before looking at the data.

**Alignment patterns whose centre lies on a timing line were skipped.** The guard tested "already
reserved", and timing reserves row and column 6 — so `(6, 24)` and `(24, 6)` at version 8 were silently
dropped. Versions 1 to 6 were unaffected; **7 to 10 lost two patterns each**, which is the defect that
produces a symbol some readers accept and others do not.

**Both format-information copies were transposed.** Low bits ran along row 8 and high bits up column 8;
ISO/IEC 18004 figure 25 is the reverse. Reported by the user as **"Google Authenticator will not scan the
QR code you generate"** — which is exactly what a transposed format copy causes: the format value fails its
own BCH check, so the reader cannot determine the error-correction level or the mask and rejects the symbol
without reading a single data module.

### Why the checks above missed it, and what the check should have been

The level-M format values are very nearly palindromic. A transposed copy differs from a correct one in
**three of fifteen modules**, so the symbol looked right, and the placement assertions passed because they
asserted a module had been *written*, not *which bit landed there*.

Worse, I claimed verification I had not performed. This section previously read *"the rendered symbol was
decoded back, by a decoder written separately"* and showed a clean round-trip: version 7, `BCH valid=True`,
196 codewords, the URI recovered. **That decoder was not independent.** It reused this encoder's reserved
map, its zigzag order and its bit indexing, so a misunderstanding shared by both round-trips perfectly. A
decoder that agrees with the encoder tests that they agree — nothing more. The output was true and the
conclusion drawn from it was not.

What settled it was external and cannot share an assumption:

```
independent decoder (OpenCV QRCodeDetector), before the fix:  FAILED
independent decoder (OpenCV QRCodeDetector), after the fix:   DECODED, matches the URI
reference encoder (segno), module-by-module diff at the same mask:
    before:  8 format modules differ, at bits 0, 3, 11, 14 of both copies
    after:   0 format modules differ
```

The module-by-module diff against a reference encoder is what localised it: finder, separator, timing,
alignment, version information and the dark module all matched, so the fault was confined to the 30 format
modules, and forcing all eight masks through the reference encoder identified which bit belongs at each
position by solving rather than by recalling the figure a third time.

One difference remains and is not a defect: the single pad codeword is `0xEC` here and `0x00` in segno's
symbol, which changes that block's 18 error-correction codewords. `0xEC` is the specified value; readers
ignore padding content, and both symbols decode.

`QrCodeTest#formatModulesCarryTheBitTheSpecificationAssigns` now reads the format modules back and requires
the value to be one of the eight published level-M constants, with both copies agreeing. Its own comment
states what it cannot catch: its position table is this encoder's mapping written a second time, so an error
shared by both is inverted by the read and comes back valid. It is a regression guard, not a proof — the
proof was the external decoder.

Then the full flow with a code computed from the decoded secret: enrol → challenge → overview, all green.

## One inconsistency of my own, corrected

The authenticator label was a truncated principal UUID, **under a comment saying a UUID in an authenticator
list is unusable**. The comment was right and the code did not follow it. It is the username now —
`otpauth://totp/AI-ASPM:pentester`.

Rendering notes: a four-module quiet zone, because a symbol flush to its border is one many readers refuse;
an opaque white plate that does **not** follow the theme, because on a dark theme a transparent background
makes the light modules dark; and one SVG path rather than two thousand rectangles.

---

## Position

| | |
|---|---|
| Complete | Prompts **1–20**, plus a running data tier, application tier, interface and identity/access surfaces in `deploy/` |
| Release gates | Forward 443/1113 · backward 20 untraced schema objects (156 total: 85 to a requirement, 51 to a section) · API 104 registered, 0 untraced |
| Java sources / migrations | 367 / 22 (V016–V022 applied and verified on the deployed database) |
| Tests | 1,076 passing (105 against a real engine), 20 skipped — 18 isolation paths, and A18/A21, which await a delegation mechanism and an effective-permission inspection surface respectively (the identity and access context itself now exists) |
| Defects surfaced by the build's own discipline | 47 — two of them by asserting the mathematics a table cannot fake |
| Defects surfaced by a user | 1 — the transposed QR format information, which every check here passed and no reader accepted |
| Defects surfaced by building the administration pages | 2 — a step-up gate nothing could pass, and form posts to every class B and class E route answering 400 |
| Defects surfaced by the user reviewing the running product | 10 — the QR format transposition, **the declared permission gating nothing on any page route**, a sidebar advertising a section with no route, "sign out" linking to the sign-in form without ending the session, a development-authentication warning shown in every deployment, **every class E form POST answering a JSON 401 no browser could act on**, a role-delete button with no `DELETE` grant behind it, and an application insert omitting two NOT NULL columns, **a 500 on every unassigned assessment request** from `Map.getOrDefault(null, …)` throwing on an immutable map, two overview figures linking to `/ui/assessments`, which has never had a route, and **a migration that could not replay**, which stopped the whole stack coming up |
