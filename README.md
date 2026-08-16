# AI ASPM

**Application Security Posture Management for a conglomerate.** One place that answers *which
internet-facing systems contain this vulnerable component, who owns them, and what is actually being
done about it* — and that can tell you when it does not know.

Most posture tools count findings. This one counts what was measured. Every figure carries its
coverage and its freshness, because a dashboard that is green because nobody looked is the failure
mode the product exists to remove.

---

## What it does

**Inventory that reflects reality.** One asset graph — applications, services, repositories,
artifacts, components — beside a separate organization tree for accountability. Two structures, not
one hierarchy, because ownership and technical containment are different questions.

**Ingestion without agents.** SBOM push (CycloneDX, and Trivy output normalised into it) and
scan-report import (SARIF 2.1.0 — semgrep, mobsfscan, CodeQL) over signed requests. One normalization
and deduplication pipeline, so a finding recognised twice is one finding with a recurrence count, not
two rows.

**Work management, not a hand-off.** Assessment requests, a six-state finding lifecycle with guards,
risk acceptance with an expiry and a second approver, comments, attachments, service levels. The loop
closes here instead of in an issue tracker that the security team cannot see into.

**Composition analysis.** Dependency trees per artifact, advisory matching, reachability of the
component through the asset graph, and an explicit staleness threshold per bill of materials.

**Authorization that is derived, never asserted.** Scope comes from the organization tree and is
resolved per object on every read and every write. A filtered picker is a usability feature; the
control is separate and is applied again at the write.

**Tenant isolation at the row.** Row-level security `FORCE` on every tenant-scoped table, per-tenant
context established transaction-locally, and 138 foreign keys that carry the tenant so a row in one
tenant cannot reference a row in another.

**An audit trail that can be verified.** A hash chain over every state change — API and interface
alike — written in the same transaction as the change, so an action whose record cannot be written
does not happen.

**AI that cannot write.** Model output goes to a suggestion ledger with the records it rests on and
the identity of what produced it; promotion into the system of record is an audited human action.
Numbers never come from the model, replies that contradict the record are refused, and untrusted
finding text is fenced with an injection corpus written against every field that reaches model
context.

**Localised from the start.** English and Vietnamese, ICU message formatting, UTC storage with
per-tenant business-calendar computation.

---

## Install

Requires Docker and about 2 GB of free disk.

```bash
git clone <your-remote> aspm && cd aspm/deploy
cp .env.example .env          # then change every value in it
docker compose up -d          # postgres, migrations, app, valkey, object store
```

Open **http://localhost:8080** and sign in as `admin` with the `BOOTSTRAP_PASSWORD` from your `.env`.
The first sign-in enrols a second factor and forces a credential change.

Demo data — an organization, applications, dependencies and findings — is optional:

```bash
PORT=$(grep '^POSTGRES_PORT=' .env | cut -d= -f2); PASS=$(grep '^MIGRATE_PASSWORD=' .env | cut -d= -f2)
for f in seed-tenant.sql seed-applications.sql seed-composition.sql seed-findings-link.sql seed-cadence.sql; do
    PGPASSWORD=$PASS psql -h 127.0.0.1 -p ${PORT:-5432} -U aspm_migrate -d aspm -f $f
done
```

Building from source needs JDK 25 and Node 18 or newer:

```bash
cd src && ./gradlew test              # 1,181 tests
cd webui && npm ci && npm run build   # the interface bundle
cd .. && ./gradlew :app:installDist
```

`deploy/README.md` covers the rest: what each seed does, how to read the migration post-conditions,
and what to check first when port 8080 looks dead.

---

## What runs, and what does not

Stated plainly, because the product's first principle applies to its own documentation.

| | |
|---|---|
| **Runs** | 193 API and interface operations, each with an annotation class and an enforced permission · 66 migrations over 430 tables · local credential with TOTP · signed-request ingestion for SBOM and SARIF · the audit hash chain · the AI suggestion ledger with deterministic agents |
| **Narrowed** | Authentication is local only — the federated half of ADR-004 is specified and not built |
| **Not built** | Notification delivery, outbound connectors, reporting exports, container registry scanning (deferred with its extension points reserved) |
| **Needs a decision** | An AI provider is tenant configuration and none is set, so AI capabilities fall back to their deterministic path. Two open questions block production sizing: portfolio scale and the secrets vault |

---

## How it is built

JDK 25 with the platform's own HTTP server and no web framework · PostgreSQL 18 · React and Vite for
the interface · Valkey · S3-compatible object storage · Gradle. No ORM: every query is written and
indexed against a query it names.

Four decisions shape everything else, each recorded with the cost it accepts:

- **Two orthogonal structures** — an organization tree for accountability, an asset graph for
  technical reality, joined by an ownership edge.
- **The platform never fetches or stores source code.** No Git credentials. The consequence is stated
  rather than hidden: it is blind between submissions, so coverage is a first-class metric.
- **Nothing organization-specific is hardcoded.** Roles, hierarchy depth, workflow states, severity
  scales and vocabulary are tenant data, enforced by static analysis rather than by review.
- **AI writes only to a ledger.** Containment is the load-bearing control, not prompt wording.

---

## Documentation

The implementation is written against a 26-document specification — 1,191 requirements, each with a
rationale, a priority and a verification method.

| | |
|---|---|
| `docs/` | The specification. Start with `00_CONVENTIONS_AND_TRACEABILITY.md`, then `03_DOMAIN_MODEL.md` |
| `docs/19_DECISION_LOG.md` | 43 decisions, each with its cost and a revisit trigger |
| `CORPUS_STATUS.md` | Document status, register figures, and the corrections recorded during authoring |
| `_traceability/requirements.csv` | The machine-readable register |
| `tools/validate_corpus.py` | ID format, duplicates, dangling references, prohibited patterns |

```bash
python3 tools/validate_corpus.py     # no dependencies, Python 3.8+
```

---

## Status

Pre-production. The data tier, the domain layer, the structural controls and the interface run and are
tested; the gaps above are real and are tracked rather than implied. Not yet reviewed by an engineer
who did not build it.
