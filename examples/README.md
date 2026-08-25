# Using the API

Working Python examples. Standard library only — no `pip install`, no dependency to audit in a
pipeline that is about to hold your attack surface.

Every script here was run against a live deployment before being committed, and the output in this
file is copied from those runs rather than written from the specification.

```
aspm_client.py                    the signed-request client the others import
01_app_inventory.py               list, create and amend assets
02_export_inventory.py            the whole inventory to CSV
03_declared_fields.py             your own fields and endpoint environments: names, then values
04_create_assessment_request.py   raise an assessment request
05_import_findings.py             get findings IN, from a scanner's SARIF output
06_download_findings.py           get the finding list OUT — JSON, CSV, or the interface's spreadsheet
07_update_record.py               update SOME fields on a record that already exists
08_import_inventory_xlsx.py       load an inventory spreadsheet, validating first
```

---

## Before anything: mint a credential

There is no API key to copy. ADR-004 forbids bearer tokens, so authentication is a signed request and
what you hold is a key id and a secret you sign with.

**In the interface:** Access → Integrations → issue a credential. Choose the permissions and the
organization node it is scoped to; the secret is shown once.

Then, in the shell that will run these scripts:

```bash
export ASPM_BASE_URL=https://aspm.internal.example    # https — see "Over HTTP nothing works" below
export ASPM_KEY_ID=payments-ci-8e9a6aa5
export ASPM_SECRET=…                                  # shown once, at issue
```

The secret goes in the environment and not in a file next to these scripts, because a secret in a
repository is a secret in every clone of it. In CI it comes from the runner's secret store.

### The permission a credential actually has is an intersection

**What it declares, intersected with what its principal holds through roles.** A credential
declaring `ast.asset.update` behind a principal whose roles do not grant it has no such permission —
and the refusal is **404**, not 403, because the platform does not distinguish "no such object" from
"not yours".

That is the single most confusing failure you will hit. If a write returns 404 on an id you can read,
check the principal's roles before checking anything else.

### Which permission each script needs

| Script | Permissions |
|---|---|
| `01` list / create / amend | `ast.asset.read` · `ast.asset.create` · `ast.asset.update` · `ast.assettype.read` |
| `02` export | `ast.asset.read` · `ast.assettype.read` |
| `03` catalogue / show | `ast.asset.read`; writing also needs `ast.asset.update` |
| `04` create request | `asm.request.submit` · `asm.request.read` |
| `05` import findings | `ing.findings.import` — **service credentials only**, class F |
| `06` download | `vul.finding.read`; the coverage line also needs `sbm.coverage.read` |
| `07` update | `ast.asset.read` · `ast.asset.update` |
| `08` import | `ast.asset.read` · `ast.asset.create` · `ast.asset.update` · `ast.assettype.read` · `org.node.read` · `org.nodetype.read` · `asm.request.read` |

---

## Over HTTP nothing works, and that is the control

On a deployment holding real data `ASPM_ENVIRONMENT` is not `development`, which marks session
cookies `Secure`. These scripts do not use cookies — they sign every request — so they work over
plain HTTP too. **Your browser will not.** If you can sign in over `http://`, the deployment is in
development mode and should not be holding a company's findings.

The signature covers the method, the path, the body digest, a timestamp and a single-use nonce. It
does **not** cover the query string: the server builds its canonical path from the URI path alone.
The nonce still makes any capture a one-shot, but put anything that must be tamper-evident in the
body rather than in a parameter.

---

## What you can and cannot do over the versioned API

This is the part worth reading before writing anything of your own.

| What you want | Door | Versioned? |
|---|---|---|
| List, create, amend assets | `GET/POST/PATCH /api/v1/assets` | **yes** |
| Read findings, drive their transitions | `GET /api/v1/findings`, `PATCH /api/v1/findings/{id}` | **yes** |
| Read requests, drive their transitions | `GET /api/v1/requests`, `POST /api/v1/requests/{id}/transitions` | **yes** |
| Push a scan report | `POST /api/v1/finding-imports` | **yes** |
| Push a bill of materials | `POST /api/v1/sbom-submissions` | **yes** |
| Set your **declared fields** | `POST /api/ui/projects/{id}/editor` | no — interface contract |
| Set an **endpoint domain** | `POST /api/ui/projects/{id}/editor` | no — interface contract |
| **Create** an assessment request | `POST /api/ui/requests` | no — interface contract |
| Create a single finding directly | — | **does not exist** |
| Bulk-import assets | — | **does not exist** |

Three of those deserve their reason stated rather than being discovered:

**`attributes` is refused on `/api/v1/assets`, loudly.** Send it and you get a 400 naming the field:
`unknown field(s) [attributes]`. It is excluded from the projection because it can hold anything a
tenant puts there, including material that needs an attribute-level permission the platform does not
yet have — so returning it wholesale would return fields nobody authorized individually. Declared
fields go through `03`.

**There is no `POST /api/v1/findings`.** One normalization and deduplication pipeline is shared by
file import and native matching, so a finding created by a plain REST POST would bypass
fingerprinting — and a finding that bypassed fingerprinting is a duplicate nothing reconciles.
Findings arrive as a scan report and the pipeline decides what is new. That is `05`.

**There is no `POST /api/v1/requests`.** A submitted request carries a resolved scope descriptor and
a draft carries none, so submission is the act that resolves it. A REST POST would produce a request
with no resolved scope, and every later authorization decision would be made against that. That is
`04`.

### ⚠ One asymmetry that will cost you data if you do not know it

The two doors treat an unknown field **oppositely**:

- `/api/v1/*` — an unknown field is a **400** naming it.
- `/api/ui/projects/{id}/editor` — an unknown attribute key is **accepted and discarded**. HTTP 200,
  nothing written.

So `delivery_teams` when the field is `delivery_team` returns success and writes nothing. That is the
exact failure the versioned API's rule exists to prevent, on the door you would use to load real
data. `03_declared_fields.py` therefore validates every key against the catalogue **locally** and
refuses before sending. If you write your own client, do the same.

---

## Updating a record that already exists

Three paths, three semantics. All three measured on a live deployment.

| Path | Semantics | Fields |
|---|---|---|
| `PATCH /api/v1/assets/{id}` | **true partial** — send one field, only that field and `row_version` change | `display_name` · `owning_node_id` · `lifecycle_state`, and nothing else |
| `POST /api/ui/applications/{id}`<br>`POST /api/ui/projects/{id}/editor` | **full replace** — a field it carries and you omit is **cleared** | exposure, criticality, contact, declared fields, tags, description, repository |
| `domains` inside those bodies | **partial per environment** — one you do not name keeps its endpoints | endpoint hosts |

### What each v1 resource lets you change on update

Read off `ResourceCatalogue`, so this is the whole list:

| Resource | Writable on create | Writable on **update** |
|---|---|---|
| `assets` | `type_id` `display_name` `owning_node_id` `criticality_mode` `exposure_declared` | `display_name` `owning_node_id` `lifecycle_state` |
| `org-nodes` | `type_id` `parent_id` `name` `criticality_mode` `external_reference` | `name` `lifecycle_state` `external_reference` |
| `asset-types` | `code` `label_i18n` `ordinal` `is_network_reachable` `may_carry_findings` | `label_i18n` `ordinal` `lifecycle_state` |
| `criticality-tiers` | `code` `label_i18n` `ordinal` | `label_i18n` `ordinal` `lifecycle_state` |
| `org-node-types` | `code` `label_i18n` `ordinal` `may_own_assets` `may_scope_work` | `label_i18n` `ordinal` `lifecycle_state` |
| `findings` | *(nothing — creation is ingestion's)* | `assignee_id` |
| `requests` | *(nothing)* | *(nothing — state moves by transition)* |

So `exposure_declared` is settable at create and **not** over `PATCH`; `criticality_tier_id` is not
settable over v1 at all. Both are refused with a 400 naming the field. To change either on an
existing record you must use the editor path — which is full replace, which is why `07` exists.

`PATCH` with a stale `row_version` answers **404**, not 409. The editor path answers **409 STALE**.
Same conflict, two different codes, so handle both.

### ⚠ The full-replace trap, measured

```console
# a project whose seeded attributes were {description, delivery_team}
POST /api/ui/projects/{id}/editor  {"name": …, "rowVersion": …,
                                    "attributes": {"delivery_team": "Policy Systems Team"}}
→ 200

# afterwards
{"description": "", "delivery_team": "Policy Systems Team", "user_base": [], "tech_stack": [], …}
        ^ the seeded description is gone. Nobody mentioned it, so it was cleared.
```

The application editor behaves the same way: sending `name`, `owningNodeId`, `exposureDeclared` and
`rowVersion` alone emptied `description`, `userBase`, `features` **and** `tags`.

There is a subtler version of the same trap. `criticalityTierId: null` does not mean "leave it
alone" — it means **INHERITED**. An early draft of `07` sent null on every write, which silently
converted an application from `ASSIGNED TIER1` to inheriting its organization's tier: a change nobody
made, to a value the risk model scores. `--dry-run` is what caught it, which is what `--dry-run` is
for. The editor payload carries the tier CODE and the save wants the tier ID, so both `03` and `07`
resolve the code back through the option list the same response supplied, and refuse rather than
guess if it is not there.

```console
$ python3 07_update_record.py application <id> --exposure PARTNER_B2B --dry-run

  Payments API  (rowVersion 5)

  would change:
    exposureDeclared
      from "INTERNET_PUBLIC"
      to   "PARTNER_B2B"

  --dry-run: nothing sent. The full body that would be posted:
   {
     "name": "Payments API",
     "criticalityTierId": "dddddddd-0000-4000-8000-00000000000d",   ← preserved, not nulled
     "description": "Card and transfer authorization for retail customers and partner merchants.",
     "userBase": "Retail customers, partner merchants",
     "features": "Authorization, Refunds, Tokenization, Webhooks",
     "tags": "pci, payments",
     …
   }
```

A write that changes nothing is not sent at all: it would still bump the row version and still land
in the audit trail as an edit somebody made.

---

## Walkthroughs

### What is my field called?

The name is the key typed when the field was declared — lower case, underscores. It is not derived
from the label.

```console
$ python3 03_declared_fields.py catalogue --type PROJECT

  11 declared field(s) on PROJECT

  key                      dataType       filterable  required  label
  -----------------------  -------------  ----------  --------  ---------------------
  description              LONG_TEXT      False       False     Description
  delivery_team            TEXT           False       False     Delivery team
  user_base                MULTI_SELECT   True        False     Who uses it
  tech_stack               MULTI_SELECT   True        False     Tech stack
  access_path              SINGLE_SELECT  True        False     How it is reached
  …
```

Note `user_base` is labelled "Who uses it". The label is for people; the key is for you.

The same key takes three prefixes depending on where it appears:

```
attributes.<key>    in the body of a write
attr.<key>          in a query string, filtering a list
asset.attributes    in the database
```

Endpoint environments work the same way, upper case: body key `domains.<CODE>`, filters
`host.<CODE>` and `hostState.<CODE>`.

### Write a declared field and an endpoint

```console
$ python3 03_declared_fields.py set <project-id> \
      --field delivery_team="Payments Platform" \
      --field tech_stack=JAVA,SPRING \
      --field waf=CLOUDFLARE

  key            dataType       value
  -------------  -------------  -----------------
  delivery_team  TEXT           Payments Platform
  tech_stack     MULTI_SELECT   JAVA, SPRING
  waf            SINGLE_SELECT  CLOUDFLARE

  endpoints by environment:
    PRODUCTION   none recorded
    UAT          uat-cards.example.internal
    STAGING      none recorded
```

A misspelled key is refused before the request leaves:

```console
$ python3 03_declared_fields.py set <project-id> --field delivery_teams=Payments
'delivery_teams' is not a declared field on this type. The server would return 200 and
  write nothing. Declared keys: abuse_controls, access_path, api_count, …
```

`--domain UAT=` with an empty value **clears** that environment's endpoints. An environment simply
absent from the body is left exactly as it was — the two are different, deliberately.

### ⚠ A second asymmetry: saving a project may duplicate its repository

`03 set` re-sends `repository` and `repositoryBranch` as the GET returned them, because omitting them
**clears** the project's BUILDS edge. But the GET returns the repository asset's *display name*, and
the save resolves a name to an asset by an identity rule that does not always reproduce the identity
key the asset was created with. Measured here: a repository keyed `repo:card-authorization-platform-api`
with display name `card-authorization-platform-api` gained a **second** asset keyed
`card-authorization-platform-api`, and the project's edge moved to it.

The estate already carried the fingerprint of this happening through the interface, before any script
touched it:

```
repo:Card Issuing/Authorization/aspm-upload-check
repo:Card Issuing/Authorization/repo:Card Issuing/Authorization/aspm-upload-check
repo:Card Issuing/Authorization/repo:Card Issuing/Authorization/repo:…/aspm-upload-check
```

Two writers, two identity rules, one asset type — the thing ADR-009's per-type identity rule exists
to prevent. So `03 set` refuses on a project with a repository recorded unless you pass
`--repository <name>` or `--accept-repository-risk`. It cannot fix the platform; it can decline to
duplicate an asset silently.

### Raise an assessment request

Two refusals you will meet, both of which are the platform being right:

```console
$ python3 04_create_assessment_request.py create <project-id> … --account "Customer:alice:vault://…"
  POST /api/ui/requests -> 422 ROLE_NEEDS_TWO_ACCOUNTS: 'Customer' has 1 account. Two are
  needed at the same privilege level: testing whether one user can reach another user's data
  needs a second user to reach. (field: roles)
```

```console
  POST /api/ui/requests -> 422 PROJECT_NOT_DELEGATED: you can see that project but have not
  been given the right to request an assessment of it. Its owner, or the security team, can
  grant that. (field: projectId)
```

Seeing a project is not the right to commission work against it. `GET /api/ui/projects` returns a
`raisable` list — the ids this credential may actually raise against.

With two accounts per role and a delegated project:

```console
  created REQ-2026-0055 01a032f7-1156-74cd-a638-cadd21d01ab0
```

Test accounts are passed as `role:username:credential_ref`. The reference is a pointer —
`vault://…`, `onepassword://…` — and the script does not expose the field that takes a literal
password. The platform already concentrates more live credentials than most systems it protects; a
password typed into an intake form is one more of them in the highest-value target in the estate.

### Push findings in

```console
$ python3 05_import_findings.py semgrep.sarif --repository group/payments-api

  session      01a032f7-8355-7afc-8fff-45348e5cad97
  state        COMPLETED
  target       01a032f7-444b-778e-ab0e-1ebd8c76d3a8

  results in document      2
  new findings             2
  already known            0
  reopened                 0
  merged within document   0
  held in quarantine       0
  severity mapping gaps    0
```

Submit the same document again and the shape of the answer changes, which is the whole point:

```console
  results in document      2
  new findings             0
  already known            2
```

Never a total. "47 findings imported" cannot be acted on — forty-seven new weaknesses and
forty-seven re-detections are the same number and completely different days.

**This is the one endpoint where `Idempotency-Key` actually works.** It implements the check itself:
a retry with the *same* key returns the first submission's report without ingesting again. A retry
with a *new* key ingests again, re-detects everything, and re-detection of a closed finding
**reopens** it — a CI timeout then manufactures "this keeps coming back". Generate the key once per
logical submission and reuse it across retries; the client here generates one per call, which is
right for interactive use and wrong for a retry loop you write yourself.

Everywhere else the header is required and **not acted on** — see "Retrying a write" below.

### Get the list out

```console
$ python3 06_download_findings.py summary

  25 finding(s) this credential can see

  by severity
    CRITICAL                     10
    HIGH                         6
    MEDIUM                       6
    LOW                          3

  dependency coverage: 62 asset(s) tracked, 38 have never submitted a bill of materials
  Those assets contribute no component findings. Their zero is not a clean result — nothing
  has looked.
```

The coverage line is printed beside the counts on purpose. Zero criticals across an estate nobody
scanned is not zero criticals, and a report that gives the first number without the second is the
failure this platform exists to remove.

Three export shapes, and they are not the same file:

```bash
python3 06_download_findings.py json --out findings.json   # versioned API, keyset-paginated
python3 06_download_findings.py csv  --out findings.csv    # versioned API, flattened locally
python3 06_download_findings.py xlsx --out findings.xlsx   # the interface's own export, BINARY
```

`xlsx` returns `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` — a spreadsheet,
not CSV. Written as bytes.

**Finding text is in none of them.** `description` and `raw_source_record_ref` are absent from the
API projection: finding content is attacker-authored by design, and a snippet recovered from a
repository can carry a payload aimed at whatever reads it next. Serving it belongs to the evidence
path, from a separate origin. If a report needs it, that is a conversation about evidence handling
and not a missing query parameter.

---

## Loading a spreadsheet

`08_import_inventory_xlsx.py` reads an `.xlsx` with `zipfile` and `xml.etree` — no `openpyxl`, because
a loader for a company's whole attack surface is a poor place to add a package nobody has audited.

**It validates by default and writes only with `--apply`.** The first pass resolves every
organization, tier, person and vocabulary value against the live tenant and sends nothing:

```console
$ python3 08_import_inventory_xlsx.py inventory.xlsx

  column mapping
    → Orgnization                        org_node   the organization that owns it
    → Product/Sản phẩm                   application   grouped under this application
    → Service/Hệ thống                   project   the record this row becomes
    → Techstack                          attr:tech_stack
    → Access (Public/Internal/ZTNA)      access   exposure and access path
    …

  4 row(s) resolve · 0 refused

  2 row(s) with something worth reading:
    ! row 4 (Quote engine): owner 'Le Van C' has no account; recorded as delivery_team text
    ! row 5 (Tokenization service): tech stack not in the tenant vocabulary: COBOL
    ! row 5 (Tokenization service): WAF 'Fastly WAF' is not in the vocabulary; recorded as OTHER
                                    and the original kept in the description
```

Header matching folds accents rather than stripping them — stripping turns `Sản phẩm` into `snphm`,
which matches nothing and leaves a Vietnamese column silently unmapped.

### Product becomes an application, Service becomes a project

Because the PROJECT type is where the declared fields live: tech stack, access path, API count, WAF,
abuse controls, architecture link. Mapping a service to an APPLICATION instead keeps the hierarchy
and loses seven columns — that type declares five fields and they are different ones.

`Access` fills **two** places: `exposure_declared`, which the risk model scores, and the declared
`access_path`. Public → `INTERNET_PUBLIC` + `DIRECT_INTERNET`; ZTNA → `INTERNAL_ONLY` + `ZTNA`.

`Password Policy` has no field in this tenant. It is **not** folded into `authentication_controls` —
a policy is not a control, and that list enumerates controls — so it is kept verbatim in the
description, and the sentence is not split on its commas.

### ⚠ The parent edge cannot be created by any API

A project hangs off its application by a `CONTAINS` edge, and no endpoint writes one: the project
editor states it "deliberately cannot create one", the SBOM door attaches only the artifact it just
created, and there is no bulk-import endpoint. So the import creates both records and every service
arrives **with no application above it** — visible in the projects list with an empty application
column, and absent from every application-level rollup.

`--emit-link-sql FILE` writes the statements that create those edges. The script does not run them:
they write `asset_relationship` directly with none of the checks a service method applies.

```console
$ python3 08_import_inventory_xlsx.py inventory.xlsx --apply --emit-link-sql link.sql
    row 2  Card settlement service                  project
    …
  4 of 4 row(s) written.

  ⚠ 4 service(s) have NO application above them.
$ psql … -v tenant_id=<your tenant> -f link.sql     # after reading it
```

---

## Two things these examples do not do

**They do not bulk-load an inventory.** There is no asset import endpoint. Loading a real estate
means `POST /api/v1/assets` per asset, then `03` per asset for the declared fields — two calls each.
For a few hundred assets that is fine; for tens of thousands, ask for an import path rather than
building a retry loop around a door that was not designed for it.

**They do not touch configuration.** Declaring a field, an environment, a role or an asset type is a
class E operation and requires a fresh second factor. That is not something a script should hold, and
the interface is the right place for it. `03` reads the catalogue; it does not add to it.
