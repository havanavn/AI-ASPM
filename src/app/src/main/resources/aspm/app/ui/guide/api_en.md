This page is for whoever is wiring a pipeline, a script or another system to the platform. The table
at the end is **generated from the platform's own operation registry** — the same list the dispatcher
enforces — so it cannot describe an endpoint that does not exist or omit one that does.

## 1. There are two doors, and they take different credentials

**`/api/v1/…` is the machine door.** Signed requests from a service credential. This is what a CI
pipeline uses.

**`/api/ui/…` is the interface's own door.** It takes the browser session cookie and exists to serve
this interface. It is documented here because you will see it in your browser's network tab, not
because it is a supported integration surface — its shapes follow the screens and change with them.

Write integrations against `/api/v1`.

## 2. There are no API keys, and that is deliberate

A bearer token is a credential that works for anyone who has it, from anywhere, until somebody
notices. A pipeline token leaks into build logs, into a screenshot, into a fork of the repository.
So the platform does not issue one.

Instead a service credential signs each request. The secret never travels; what travels is a
signature over the request, and a signature is useless for any other request.

### Getting a credential

Settings → Access → Service credentials → Issue. You choose the principal it acts as, the
organizational scope it is pinned to, and the permissions it declares. You are shown the secret
**once**; only its digest is stored, so there is no second chance and no "show again".

Two things to know before you use it:

- **The signing key is SHA-256 of the secret**, not the secret itself. The response says so.
- **Effective permissions are the intersection** of what the credential declares and what its
  principal holds. A credential cannot widen its principal, which is why issuing one is not a way
  around a role.

### Signing a request

```
canonical = METHOD \n PATH \n SHA256(body) \n TIMESTAMP \n NONCE
signature = HMAC-SHA256(signing_key, canonical)
```

Then send:

```
Authorization: ASPM-HMAC-SHA256 key=<key id>, ts=<unix seconds>, nonce=<hex>, signature=<hex>
x-aspm-content-sha256: <hex sha256 of the raw body>
Content-Type: application/json
Idempotency-Key: <your own key>
```

The body digest is part of what is signed, and the platform re-hashes the body it actually received
before comparing. Without that step the signature would only cover a promise about the body.

The timestamp must be within **five minutes** of the platform's clock and the nonce must not have
been used before — together they stop a captured request being replayed.

### What a service credential cannot do

It is never step-up authenticated. Operations in classes **C** and **E** — restricted reveals and
tenant configuration — require a fresh second factor, so no signed request can perform them. Asset
types, organization node types and criticality tiers are therefore created by a person in the
interface first; a pipeline can then use them.

## 3. Idempotency

Every write carries `Idempotency-Key`. Send the same key twice and the second call returns the first
call's outcome rather than performing the work again.

This is not politeness. A pipeline that times out waiting for a response and retries is the normal
case, and without a key the retry would re-detect every finding in the report — which reopens closed
findings and increments recurrence counts. The retry would manufacture "this keeps coming back" out
of one submission.

Use a key derived from the thing you are submitting — a build identifier, a commit, a run number.
Random keys per attempt defeat the mechanism entirely.

## 4. What the answers mean

**`404` may mean "you may not".** A denial and a non-existent identifier are deliberately
indistinguishable. If they differed, an unauthorized caller could map the estate by probing
identifiers and reading which ones came back with a different code. So a `404` from a path you
believe exists means either it does not, or your credential's scope does not reach it.

**`403` means the credential is authenticated and refused**, and the body names why —
`STEP_UP_REQUIRED` for a class C or E operation, `CREDENTIAL_CHANGE_REQUIRED` for a principal whose
password is marked for replacement.

**`422` means the document was understood and rejected**, with a code and a message you can act on.
A rejected scan report or bill of materials answers here.

**`401` means the signature did not verify**, or the timestamp was outside the window, or the nonce
was reused. It never means "wrong permission".

## 5. Submitting scan results

`POST /api/v1/finding-imports` takes a SARIF 2.1.0 document.

```json
{
  "application": "Booking Engine",
  "project": "Reservations",
  "repository": "booking-payments-api",
  "document": { "version": "2.1.0", "runs": [ … ] }
}
```

The three-part address is how the platform decides which asset the findings belong to. The
**document does not get to say**: a scan report is attacker-influenced input, and a report that
named its own target could file findings against somebody else's repository.

One parser covers semgrep, mobsfscan and CodeQL. What comes back is counted **by disposition** —
ingested, already known, reopened, merged, quarantined — because "42 records processed" is the
number that says nothing. Every warning is returned in the response rather than logged, because you
are the only party who can act on a mapping gap or a held record.

Two behaviours worth knowing before your first push:

- **A weakness that comes back after somebody closed it is reopened**, and its recurrence count
  increments. That is the most important thing this endpoint reports.
- **Nothing is ever closed by an import.** A scan that no longer reports a weakness may have been
  narrowed, may have failed, may have run against a different revision. Closure stays a human
  decision with a verification method behind it.

## 6. Submitting a bill of materials

`POST /api/v1/sbom-submissions` takes CycloneDX or SPDX, addressed the same way. The response
carries the quality score and every warning, because the submitter is the only party who can fix a
low-quality document.

Between submissions the platform is blind, and it says so rather than showing a stale figure as
current. That is why submission health is visible per credential: a pipeline that has been rejected
two hundred times and one that simply has not run look identical from the data alone, and they need
opposite responses.

## 7. Reading data back

The read endpoints under `/api/v1` are scoped to the credential exactly as a person is scoped: the
organizational subtree the credential is pinned to, and nothing outside it. A collection returns
what your scope reaches, not what exists.

Filtering is by query parameter. `org` is subtree-inclusive everywhere it appears — naming a
division means that division and everything beneath it — and it means the same thing on every
surface that accepts it.

## 8. Creating the estate: organizations, applications, projects and repositories

Two resources cover all of it, because the platform has one organization hierarchy and one asset
aggregate rather than a separate inventory per kind of thing.

### An organization node

`POST /api/v1/org-nodes`

```json
{
  "type_id": "<an org node type id>",
  "parent_id": "<the node above it, or omitted for a root>",
  "name": "Payments Platform Team",
  "external_reference": "your own identifier for it",
  "criticality_mode": "INHERITED"
}
```

`type_id` comes from `GET /api/v1/org-node-types`. The type is tenant configuration, so the platform
does not ship a fixed set of levels — a division, a business unit, a product and a team are all the
same table with different type rows, which is what lets the hierarchy be as deep as your organization
actually is.

Creating a TYPE is class E and therefore needs a person: `POST /api/v1/org-node-types` cannot be
called by a credential at all. Create the levels once in the interface; a pipeline can then create
nodes under them for as long as it likes.

### An application, a project, a service, a repository

All four are assets, and the type decides which.

`POST /api/v1/assets`

```json
{
  "type_id": "<an asset type id>",
  "display_name": "Payments Portal",
  "owning_node_id": "<the org node accountable for it>",
  "criticality_mode": "INHERITED",
  "exposure_declared": "INTERNAL_ONLY"
}
```

`GET /api/v1/asset-types` lists what this tenant defines — in this deployment `APPLICATION`,
`SERVICE`, `FEATURE`, `PROJECT`, `REPOSITORY` and `DOMAIN`. There is no separate "create a project"
endpoint: a project is an asset whose type is `PROJECT`, contained by an application. That is
deliberate — five parallel inventories is five places for the same repository to exist under five
slightly different names.

`owning_node_id` is what makes the asset visible to anybody: scope is derived from the organization
tree, so an asset owned by no node appears on nobody's dashboard.

**A credential pinned to part of the tree must name a node inside it**, and must name one at all. A
node outside your scope answers `404` — the same answer as a node that does not exist, so a refusal
cannot be used to discover what the hierarchy contains. Omitting the field is refused for the same
reason: the asset would land outside every scope, including yours, and you could not read back the
thing you just created. A credential pinned to the whole tenant may create an unowned asset
deliberately; it then appears in the unowned queue rather than on a dashboard.

**Containment is not set here.** Which application contains which project is an edge in the asset
graph, and the SBOM and scan-report doors create those edges as a side effect of naming a
three-part address. There is no v1 endpoint that writes an edge directly.

### Updating one

`PATCH /api/v1/org-nodes/{id}` and `PATCH /api/v1/assets/{id}`, with `row_version` from the last read.
A mismatched version is refused rather than overwritten: two systems editing the same row is the
normal case for an integration, and last-write-wins loses the other one's change silently.

## 9. What you cannot do from the API, and why

Stated because absence is hard to distinguish from "not documented yet".

- **There is no endpoint that raises an assessment request.** A request carries a scope, a readiness
  attestation and credential handling that the intake form collects together; the API has the read
  side (`GET /api/v1/requests`) and the transition side (`POST /api/v1/requests/{id}/transitions`)
  but not the creation side.
- **There is no endpoint that records a single finding.** Findings arrive through the import door,
  which is what keeps deduplication, fingerprinting and provenance on one path. A finding posted
  directly would have no import session behind it and would be invisible to the CI/CD dashboard,
  whose one honest predicate is that provenance.
- **No endpoint creates a tenant, a role or a permission.** Roles are tenant configuration (class E);
  the permission catalogue is product-fixed and not writable at all.
- **Nothing closes a finding automatically.** `PATCH /api/v1/findings/{id}` can amend fields, and the
  lifecycle transitions that close one are human decisions with a verification method behind them.

## 10. Reading, filtering and paging

Every collection under `/api/v1` answers the same envelope:

```json
{
  "items": [ … ],
  "has_more": true,
  "next_cursor": "eyJzb3J0IjoiMjAyNi0wOC0xNSIsImlkIjoiMDE5ZmYuLi4ifQ"
}
```

Paging is by **cursor, not page number**:

```
GET /api/v1/findings?limit=50
GET /api/v1/findings?limit=50&cursor=<next_cursor from the previous response>
```

Read until `has_more` is false. There is no `page` parameter and no total count — a page number over
a table that is being written to skips and repeats rows, and a count over a scoped, filtered set costs
a second full scan to produce a number that is stale when it arrives.

Filtering is **exact match on a named field**, and the named fields differ per endpoint — the table
below lists them per operation, generated from the same declaration the request validator enforces.

```
GET /api/v1/findings?state=OPEN&limit=50
```

- Several filters combine with AND. There is no OR, no range and no partial match on this surface;
  the dashboards do more, and they do it through `/api/ui`, which is not this contract.
- **A filter on a field that is not declared filterable is REJECTED**, not ignored. That is on
  purpose: a parameter silently dropped is how an integration reports the whole estate believing it
  reported one division.
- `org` is subtree-inclusive wherever it appears.
- The response carries what your credential's scope reaches. It is not a page of everything.

## 11. A complete first request

Everything above, in one worked example. This is the smallest correct call.

```bash
KEY_ID="your key id"
SECRET="the secret shown once when the key was issued"
# The SIGNING KEY is the SHA-256 of the secret, as hex.
SIGNING_KEY=$(printf %s "$SECRET" | sha256sum | cut -d' ' -f1)

PATH_ONLY="/api/v1/findings"          # NO query string — see below
BODY=""                                # a GET has an empty body, and it is still hashed
BODY_SHA=$(printf %s "$BODY" | sha256sum | cut -d' ' -f1)
TS=$(date +%s)
NONCE=$(openssl rand -hex 16)

CANONICAL=$(printf '%s\n%s\n%s\n%s\n%s' "GET" "$PATH_ONLY" "$BODY_SHA" "$TS" "$NONCE")
SIG=$(printf %s "$CANONICAL" | openssl dgst -sha256 -mac HMAC -macopt hexkey:"$SIGNING_KEY" -r \
      | cut -d' ' -f1)

curl -sS "https://your-host/api/v1/findings?limit=5" \
  -H "Accept: application/json" \
  -H "x-aspm-content-sha256: $BODY_SHA" \
  -H "Authorization: ASPM-HMAC-SHA256 key=$KEY_ID, ts=$TS, nonce=$NONCE, signature=$SIG"
```

Four details that decide whether your first call works:

- **The signed path excludes the query string.** `?limit=5` is sent but is NOT part of the canonical
  string. Signing `/api/v1/findings?limit=5` produces a valid-looking signature and a `401`.
- **The body is hashed even when empty.** The SHA-256 of the empty string is
  `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`; send it and sign it.
- **The nonce is 8 to 128 characters** and must never repeat. Sixteen random bytes as hex is 32
  characters and is fine.
- **The timestamp is Unix seconds and must be within five minutes** of the platform's clock. A
  container with a drifting clock fails every request with `401` and nothing about the message says
  the clock is the reason — check it first when a signature that looks right is refused.

Writes add two headers: `Content-Type: application/json` and `Idempotency-Key: <your key>`.

## 12. Rate classes

Every operation carries one, and the table below shows it: `READ`, `WRITE`, `SENSITIVE`, `BULK`,
`INGEST` or `ANON`. They exist so an ingestion push and a dashboard read are not budgeted against each
other — a nightly bulk submission must not exhaust the allowance a person's page load needs.

**They are declared and not yet enforced.** No budget is applied and no operation answers `429` today.
The class is stated here because it is the contract a client should be built against — a client that
retries an ingestion push without backoff will work now and fail when the limiter arrives — but a
reader must not take it for a control that is running. Nothing on this platform currently limits how
fast a credential may call it.

## 13. The values these fields take

A field that accepts a fixed set is refused when the value is not in it, so the sets are here. Two
kinds are distinguished, because they are governed differently.

**Product-fixed.** These are the same in every deployment and are enforced by the database:

| Field | Accepted values |
|---|---|
| `criticality_mode` on an org node or an asset | `ASSIGNED`, `INHERITED` |
| `exposure_declared` on an asset | `INTERNET_PUBLIC`, `PARTNER_B2B`, `INTERNAL_ONLY`, `AIR_GAPPED` |
| `lifecycle_state` on an asset | `DISCOVERED`, `ACTIVE`, `DEPRECATED`, `RETIRED` |
| `finding_class` | `CODE`, `DEPENDENCY`, `RUNTIME`, `INFRASTRUCTURE`, `SECRET`, `MANUAL`, `CONFIGURATION` |
| `state` on a finding | `OPEN`, `CLOSED` |
| `lifecycle_state` on a finding | `OPEN`, `FIXED`, `REOPEN`, `ACCEPTANCE_REQUESTED`, `CLOSED`, `ACCEPTED_RISK` |
| `assessment_context` on a finding | `INTERNAL_PENTEST`, `EXTERNAL_PENTEST`, `REDTEAM_INTERNAL`, `REDTEAM_EXTERNAL`, `AUTOMATED_SCAN`, `BUG_BOUNTY`, `INCIDENT` |

A finding carries **two** state fields and they are not redundant. `state` is the coarse axis every
count and dashboard aggregates on; `lifecycle_state` is where it sits in the workflow. They are
constrained to agree — `OPEN`, `FIXED`, `REOPEN` and `ACCEPTANCE_REQUESTED` are exactly the lifecycle
values for which `state` is `OPEN` — so filter on `state` when you want "still counts against us" and
on the detail record when you want why.

`criticality_mode: "INHERITED"` means the tier comes from the org node above; `ASSIGNED` requires
`criticality_tier_id` in the same body, and the write is refused without it.

**Tenant configuration.** These have no fixed set at all, and hardcoding one is the mistake this
platform is built to avoid. Read them, do not assume them:

| What | Where it comes from |
|---|---|
| Org node types (division, business unit, team, …) | `GET /api/v1/org-node-types` |
| Asset types (application, service, project, repository, …) | `GET /api/v1/asset-types` |
| Criticality tiers | `GET /api/v1/criticality-tiers` |
| Assessment request states and the transitions between them | `GET /api/v1/requests/{id}/transitions` returns what is legal from where the request stands now |
| Severity levels, work item states, custom fields | Tenant configuration; not writable from the machine door |

The request workflow deserves the emphasis. `POST /api/v1/requests/{id}/transitions` takes a `state`,
and which values are legal depends on the tenant's configured workflow **and** on the request's
current state. Asking for the legal set first is the supported way; guessing a state name and reading
the refusal is not, because a refusal deliberately does not enumerate what would have been accepted.

## 14. Finding the identifiers you need

Nothing in this API takes a name where it could take an identifier, so the first integration is
mostly a sequence of reads. In order:

1. `GET /api/v1/org-node-types` → pick the type for the level you are creating.
2. `GET /api/v1/org-nodes` → find the parent, or omit `parent_id` for a root.
3. `POST /api/v1/org-nodes` → the response carries the new `id` and `row_version`.
4. `GET /api/v1/asset-types` → pick `APPLICATION`, `REPOSITORY` or whatever this tenant defines.
5. `POST /api/v1/assets` with `owning_node_id` set to the node from step 3.
6. Push findings or a bill of materials at the three-part address, not at the asset id — the
   ingestion doors resolve and create the repository asset themselves.

Steps 1 to 5 are only needed for estate you are creating deliberately. A pipeline that just pushes
scan results does not need any of them: section 5's address is enough, and the asset appears.
