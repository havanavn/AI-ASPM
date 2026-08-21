# Deployment

**Read the first section before running anything.** It tells you what this stands up and what it
does not, and the difference is larger than a deployment guide normally has to admit.

---

## What exists, and what does not

This repository is a 26-document specification with an implementation of its **domain layer,
schema, and structural controls**. It is not yet a running platform.

| | State |
|---|---|
| Operational store schema | **Runs.** 16 migrations, 156 objects, row-level security `FORCE`, partitioning, immutability triggers |
| Domain models and invariants | **Built and tested.** 1,073 tests, 105 against a real engine |
| Structural enforcement | **Runs.** Module boundaries, authorization static analysis, honesty assertions — all compile- or test-time |
| Deployment topology | **Modelled, not provisioned.** `src/deployment` fails the build on a violation; it does not create infrastructure |
| Application tier | **Runs.** Entrypoint, JDK HTTP server (ADR-057), composition root, readiness and liveness |
| API and interface operations | **75 registered**, every one carrying an annotation class and — unless class G — a permission the dispatcher enforces. DOC-05 §12–§25 specify well over a hundred API operations alone |
| Authentication | **Runs, narrowed.** Local credential plus TOTP with forced enrolment (ADR-059). ADR-004's federated half and sender-constrained service credentials are **not** built |
| Authorization | **Runs.** Permission enforced at the dispatcher for every route; scope resolved per object in the handler |
| Interface | **Runs.** Server-rendered, no build step (ADR-058): overview, findings, requests with transitions, dependencies, workload, user and role administration, self-service account |

So `docker compose up` gives you a correct, inspectable data tier, an application tier that answers
requests, and an interface you sign in to.

**Why it is stated this way.** The platform's first product principle is that absence of evidence
is not evidence of absence, and a deployment guide that lists services without saying which of
them are absent is that same failure in the documentation. `_traceability/GAPS.md` reports the same
thing in machine-readable form.

**This table itself was wrong for several sessions.** It said the interface did not exist and that there
were 23 operations and 13 migrations, while the interface was running, sign-in worked, and there were 16
migrations. A deployment guide that understates what is running is the same defect as one that overstates
it: both leave a reader unable to trust the rest of the file.

---

## If `localhost:8080` looks dead

Two causes, and neither is the application.

**1. You are not signed in.** `http://localhost:8080/` redirects to `/ui/overview`, which redirects to
`/ui/sign-in` when there is no session. If you land on the sign-in form, nothing is wrong. The `/api`
paths still answer `401` rather than redirecting, because a pipeline following a redirect to an HTML form
would receive `200` and log success.

**2. A VPN may be holding the route to the container network.** On the machine this was developed on,
Cloudflare WARP claimed `172.18.0.0/16` — the subnet Docker had allocated:

```
$ ip route get 172.18.0.5
172.18.0.5 dev CloudflareWARP ...        # should be dev br-<id>
```

**The symptom does not look like a network fault.** `docker-proxy` accepts the connection on the
published port, so a port check says the service is up; the reset only happens once bytes are relayed
to the container. From inside the container everything works. It reads as an application defect.

The compose file now pins the network to `192.168.246.0/24`, which WARP excludes by default, so no
reconfiguration is needed. Pinning also makes the address deterministic — an unpinned network is
re-allocated on every recreate, so the same collision can reappear later with a different range.

To check whether this is your problem:

```bash
docker compose exec app bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080; \
  printf "GET /internal/health/ready HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n" >&3; cat <&3'
```

If that answers and `curl localhost:8080/` does not, it is the route and not the application.

---

## Prerequisites

- Docker Engine with Compose v2 or later
- Your user in the `docker` group. If `docker info` reports a permission error:

  ```bash
  sudo usermod -aG docker $USER && newgrp docker
  ```

- For the conformance profile: nothing extra.

The rest of the repository needs a JDK 25 or later and Gradle; see `../README.md`.

---

## Standing it up

```bash
cd deploy
cp .env.example .env        # then change every value
docker compose up -d postgres valkey objectstore
docker compose up migrate   # one-shot; applies V001 to V016 and exits

# The application tier. Gradle runs on the HOST because container egress is blocked in the
# environment this was developed in — DOC-15 section 14's air-gapped topology, by accident.
cd ../src && ./gradlew :app:installDist && cp -r app/build/install/app ../deploy/app/install
cd ../deploy && docker compose up -d --build app
```

### Calling it

```bash
curl localhost:8080/internal/health/ready

curl localhost:8080/api/v1/org-node-types \
  -H "x-dev-tenant: 11111111-1111-1111-1111-111111111111" \
  -H "x-dev-principal: 33333333-3333-3333-3333-333333333333" \
  -H "x-dev-permissions: org.nodetype.read" \
  -H "x-dev-scope: <an org node id this principal is granted>"
```

Observed behaviour, each of it enforced rather than described:

| Request | Result |
|---|---|
| No credential | `401 UNAUTHENTICATED` |
| A credential that fails validation | `401 UNAUTHENTICATED` — identical, so a caller cannot learn which credentials exist |
| Unregistered path | `404 NOT_FOUND`, byte-identical to a scope denial |
| Authenticated, permission not held | `404`, never `403` |
| Permission held, no scope grant | `404` — `SEC-AUZ-014` denies on an empty scope rather than allowing over nothing |
| Another tenant's object by id | `404`, byte-identical to an id that exists nowhere |
| `POST` without step-up | `401 STEP_UP_REQUIRED` — class E |
| `POST` without `Idempotency-Key` | `400 IDEMPOTENCY_KEY_REQUIRED` |
| `POST` carrying `tenant_id` | `400` — unknown fields rejected, not ignored (`PRD-API-020`) |
| A node in the **same tenant** the principal is not granted | absent from the collection, `404` by id |
| `PATCH` without `row_version` | `400` — a lost update would otherwise be silent |
| `PATCH` with a stale `row_version` | `404`, the same as out-of-scope and non-existent |

`migrate` prints its post-conditions rather than only "done":

- how many tables carry forced row-level security, out of how many (370 of 373)
- the **three** tables deliberately outside it, each with its reason, printed rather than filtered
  away — `tenant`, `tenant_id_reservation`, `hash_partition_basis`
- **any other table without a forced policy** — this list must be empty, and a non-empty one is a
  cross-tenant read path, not a style issue
- range partition runway in months per table, with an alert flag below three (`OPS-DEP-011`)
- hash partition counts with confirmation that a sizing basis is recorded (`OPS-DEP-012`)

A migration that fails stops the run and says so, and it tells you not to retry blindly:
expand-migrate-contract means a failed expand is safe to retry and a failed contract is not.

### Looking at what you built

```bash
docker compose exec postgres psql -U aspm_owner -d aspm

-- Row-level security, forced, on every tenant-scoped table:
\d+ finding

-- The isolation gap query the platform ships with:
SELECT * FROM tenant_isolation_gaps();

-- Partition runway (OPS-DEP-011):
SELECT * FROM partition_runway_report();

-- Why the hash partition counts are what they are (OPS-DEP-012):
SELECT table_name, partition_count, sizing_basis FROM hash_partition_basis;
```

To watch isolation actually work, connect as `aspm_app`, which does **not** bypass row-level
security. The tenant context must be set with `SET LOCAL` **inside a transaction** — outside one,
`current_tenant_id()` raises rather than defaulting to anything:

```
ERROR:  no tenant context established for this session (CON-DAT-013, SEC-TEN-005)
HINT:   The application must SET LOCAL aspm.current_tenant inside the transaction, from an
        established TenantContext. It is never derived from a request parameter, header, path
        segment or body field (SEC-TEN-004).
```

That refusal is the control, not an inconvenience: a session-scoped default would survive into the
next borrower of a pooled connection, which is the disclosure mechanism `OPS-DEP-010` exists for.

```bash
docker compose exec postgres psql -U aspm_app -d aspm
```
```sql
BEGIN;
SET LOCAL aspm.current_tenant = '11111111-1111-1111-1111-111111111111';
SELECT code FROM org_node_type;                                  -- only tenant A's rows
SELECT count(*) FROM org_node_type
 WHERE tenant_id = '22222222-2222-2222-2222-222222222222';       -- 0, not an error
INSERT INTO org_node_type (tenant_id, code, label_i18n, ordinal, may_own_assets, may_scope_work)
VALUES ('22222222-2222-2222-2222-222222222222','SMUGGLED','{"en":"x"}',9,false,true);
-- ERROR: new row violates row-level security policy for table "org_node_type"
COMMIT;
```

Both directions matter. Reading another tenant's row returns **absence rather than a denial** —
`PRD-API-036` makes a scope violation indistinguishable from non-existence — and writing one is
refused by the policy's `WITH CHECK`, so a compromised or buggy application cannot plant a row in
another tenant's scope either.

### Post-deployment conformance

```bash
docker compose --profile verify run --rm conformance
```

Runs as `integrity_verifier` — BYPASSRLS and read-only, which is what the cross-tenant assertion of
`SEC-TEN-047` needs: it has to see across tenants to assert that nothing else can. Six checks, each
raising rather than warning:

1. **`uuidv7()` is native and time-ordered.** Not "does it exist" — the test-only shim would satisfy
   that. `prolang` distinguishes them, because applying the shim to a real 18 would replace the
   engine's implementation with a weaker one, permanently and invisibly. **This is the one claim the
   build cannot make**, since it runs against an embedded 17.5.
2. `tenant_isolation_gaps()` is empty — `OPS-DEP-031` requires this after every migration.
3. Forced row-level security on every table but the three documented exceptions.
4. Partition runway: zero runway fails, below the lead time warns.
5. Hash partition counts agree with the recorded sizing basis.
6. No BYPASSRLS role can log in directly, and `offboarding_executor` cannot log in at all.

**This is not the test suite, and the distinction matters.** `:kernel-verification` creates and
drops schema and switches between all four roles — authority no deployed environment should hand a
running job. An earlier version of this file pointed the suite at the deployed database and it
failed correctly, because `integrity_verifier` cannot create a table. The suite asks whether the
schema is correct and belongs to the build; this asks whether the schema that got **deployed** is
the one that was verified.

### Tearing down

```bash
docker compose down          # keeps the volumes
docker compose down -v       # deletes them
```

---

## What this compose file enforces, rather than describes

`OPS-DEP-009` requires that the three row-level-security-bypassing credentials are absent from any
runtime environment reachable by application code, and its rationale is that credential separation
is what makes that **structural rather than procedural**: an application that cannot obtain the
credential cannot use the bypass regardless of what its code attempts.

The compose file implements it. Each login user appears in exactly one service's environment:

| Login user | Group role | Appears in | Bypasses RLS |
|---|---|---|---|
| `aspm_app` | `app_runtime` | `app` only | no |
| `aspm_migrate` | `migration_runner` | `migrate` only — runs and exits | yes |
| `aspm_verify` | `integrity_verifier` | the `verify` profile only | yes, read-only |
| — | `offboarding_executor` | **nowhere** | yes |

`offboarding_executor` has no login user at all. It is dual-control gated (`OPS-DEP-022`) and it is
the mechanism of cryptographic erasure at tenant offboarding — which DOC-15 also identifies as *the
mechanism an insider would use to destroy evidence*. Giving it a password in a compose file would
be the opposite of dual control.

Adding one of these to another service is the defect, and it is visible in a diff of one file.
That is the reason it lives here rather than in a startup script.

---

## Where this deployment is weaker than the specification

Stated rather than left to be discovered.

| Requirement | What a deployment needs | What compose does |
|---|---|---|
| `OPS-DEP-016` | Object storage on a **distinct origin** from the API | A distinct **port** on the same host. Different ports are same-site for cookies; this is not equivalent, and `ServingOrigin.assertDistinctFrom` checks the registrable domain for that reason |
| `OPS-DEP-028` | Base images pinned **by digest** | Pinned by tag. "A tag can be reassigned to different content, so tag-based pinning is not pinning" |
| `OPS-DEP-019`, `-020` | Secrets injected from the secrets store, never in environment variables | `.env` and environment variables throughout. ⚠ **OQ-026 is open** — platform-provided vault or enterprise integration is undecided, which is why no vault container appears here |
| `OPS-DEP-014` | Egress deny-by-default, allowlist per runtime unit | Not applied. Compose networks are permissive; the per-unit allowlist is modelled in `src/deployment/EgressAllowlist.java` and enforced by nothing at runtime |
| `OPS-DEP-006` | Match workers not co-scheduled with the application tier | Not applicable — neither exists |
| `OPS-DEP-031` | A cross-tenant assertion after every migration | Not run by `apply.sh`. The assertion exists in the verification suite; wiring it into the migration step is unfinished work |
| `OPS-DEP-021` | Key-encryption keys in a hardware-backed store, per-tenant data keys wrapped | Nothing. There is no key management here at all |

None of these is a reason not to run it locally. All of them are reasons this is not a production
topology, and a guide that omitted them would be claiming one.

---

## Identity and access — what runs, and the deviations it carries

`V016` must be applied before the administration pages work. The `principal_administration` view and
`principal_session.step_up_at` arrive with it, and `/ui/users` answers 500 without them.

```bash
docker compose run --rm migrate          # re-applies every migration; each file is guarded, so this is safe
docker compose up -d --build app
```

**Two settings decide whether this deployment is safe to put real data in**, and both are in `.env`:

| Setting | For a demo estate | For real data |
|---|---|---|
| `ASPM_ENVIRONMENT` | `development` — the interface works over plain HTTP, and `ASPM_DEV_AUTH=true` becomes available | anything else. Session cookies become `Secure`, so **sign-in requires HTTPS** and a TLS-terminating ingress in front of `127.0.0.1:${APP_PORT}` is mandatory. `DevPrincipalResolver` refuses to start |
| published ports | loopback is still correct | loopback, with only the ingress reaching the app. `APP_BIND`, `POSTGRES_BIND` and `OBJECTSTORE_BIND` exist for an ingress on another host; never set them to `0.0.0.0` |

`ASPM_DEV_AUTH` takes identity from a request header, or from an **unsigned** base64 cookie carrying
tenant, principal, permissions and scope. Anyone who can reach the port is anyone they choose to be.
It is absent from `.env` deliberately — export it for the one command that needs it rather than
leaving it set:

```bash
ASPM_DEV_AUTH=true docker compose up -d app
```

The application inventory additionally needs a **seed**, because the `APPLICATION` asset type is
tenant data (ADR-009) and a migration must not create it:

```bash
PORT=$(grep '^POSTGRES_PORT=' .env | cut -d= -f2)
PASS=$(grep '^MIGRATE_PASSWORD=' .env | cut -d= -f2)
for f in seed-tenant.sql seed-applications.sql seed-composition.sql seed-findings-link.sql \
         seed-cadence.sql seed-project-attributes.sql; do
    PGPASSWORD=$PASS psql -h 127.0.0.1 -p ${PORT:-5432} -U aspm_migrate -d aspm -f $f
done
```

### A tenant for real data — not this list

Everything in the block above seeds the **demonstration estate**. Do not run it against a tenant that
will hold a company's own findings: `seed-demo.sql` alone writes seventy assets, seven hundred
findings and two hundred assessment requests describing a company that does not exist, and every
count, score and coverage figure the platform reports would then be part fiction — the failure product
principle 1 exists to prevent.

`seed-bootstrap.sql` is the other path: structure and vocabulary, nothing that claims a fact about an
estate. Read its header, then:

```bash
PORT=$(grep '^POSTGRES_PORT=' .env | cut -d= -f2)
PASS=$(grep '^MIGRATE_PASSWORD=' .env | cut -d= -f2)
TENANT=$(uuidgen)                       # and put it in .env as ASPM_TENANT_ID
psql() { PGPASSWORD=$PASS command psql -h 127.0.0.1 -p ${PORT:-5432} -U aspm_migrate -d aspm \
         -v ON_ERROR_STOP=1 -v tenant_id="$TENANT" "$@"; }

psql -v demo_people=off -f seed-identity.sql          # THE PERMISSION CATALOGUE IS IN HERE
psql -v tenant_name='Your Group' -v residency=VN \
     -v admin_user=secadmin -v admin_email=sec@your.example \
     -v admin_name='Security Administrator' -f seed-bootstrap.sql
psql -f seed-workflow.sql
psql -f seed-cadence.sql
psql -f seed-project-attributes.sql                   # opinionated defaults; read them first
```

`-v demo_people=off` is not cosmetic: `CredentialBootstrap` gives `ASPM_BOOTSTRAP_PASSWORD` to every
principal with no credential, so the three example accounts in `seed-identity.sql` are accounts that
can **sign in**, holding real scope over real assets.

**Four defects in the first-run path were found by running exactly the above against an empty
database**, and each had been invisible because the demo estate was seeded once, by hand, before any
of them mattered:

| What was wrong | What it looked like |
|---|---|
| `seed-cadence.sql` used `SET LOCAL` outside a transaction | Discarded with a warning, so the file established no tenant context and the review policy failed to read `criticality_tier`. The inserts before it carry their tenant explicitly, so the file looked applied |
| Nothing creates an `assessment_type` | Every fresh tenant failed on `fk_workflow_definition__assessment_type_id__tenant`. The demo tenant's PENTEST row is referenced by `seed-workflow.sql` as a literal and inserted by no file |
| `seed-workflow.sql` used a literal `workflow_definition` primary key with `ON CONFLICT (id) DO NOTHING` | Run against a second tenant it inserts nothing and reports success. The states and transitions then target the first tenant's definition, row-level security refuses them, and the result is a tenant whose workflow has no states and nothing saying why |
| `seed-identity.sql`, `seed-workflow.sql`, `seed-cadence.sql` and `seed-project-attributes.sql` hardcoded the demo tenant id | Roles, workflow, review policy and declared fields all landed in a tenant the deployment does not serve. Not an error — a platform where no transition is defined and no field is offered |

Verified end to end on 2026-08-21: migrations, the five seeds above, then the application tier booted
against that database — ready `200`, interface `200`, API `401` without a session, and the credential
bootstrap touching one account rather than four.

| Seed | What it does |
|---|---|
| `seed-bootstrap.sql` | **The only seed for a tenant that will hold real data.** Tenant row, three organization node types and one root node, criticality tiers, severity scale, six asset types, endpoint environments, one assessment type, password policy, an ADMIN role and one administrator you name. No asset, no finding, no request |
| `seed-applications.sql` | The `APPLICATION` asset type, three applications, and **repairs three defects in the demo organization data** — colliding ordinals, a duplicate node type, and only the deepest level being allowed to own assets |
| `seed-composition.sql` | The `FEATURE` asset type, the **declared security attributes** (authentication, data handled, tech stack, runtime, compliance scope, third-party, deployment model), and the features that sit between an application and its services |
| `seed-findings-link.sql` | Links the demo findings to the assets they were found in, and adds closed and risk-accepted ones so the counts mean something |
| `seed-tenant.sql` | **Run first.** The tenant registration row. Everything worked without it — row-level security keys off a session variable and never consults the registry — but every migration backfill written as `FOR t IN SELECT id FROM tenant` iterated zero times, changed nothing, and reported success. Two of them did |
| `seed-cadence.sql` | The assessment triggers (change review, pre-go-live, periodic full review, ad hoc), the per-criticality review interval, and which terminal states count as completing a review |
| `seed-project-attributes.sql` | The **declared field catalogue for `PROJECT`** — who uses it, tech stack, how it is reached, architecture link, API count, sign-in controls, CDN, WAF, abuse controls. Tenant data, so another deployment declares a different set without a code change (ADR-027). Domains, the repository, the branch, criticality and the technical contact are deliberately **not** here: each is already first-class, and a second copy would give two answers to one question |

### Rebuild the app image after any Java or bundle change

`docker compose up -d` alone restarts the **existing** image. The migrations replay from source and
the database moves forward; the jar does not. A container running an older jar against a newer
database is the shape of the 500 that followed V027: the workflow gained the events `close` and
`reopen`, and the jar in the image still looked their labels up with the throwing form of
`Messages#get`, so every request whose only available move was one of those two returned
`INTERNAL_ERROR`.

```bash
docker compose up -d --build app
```

The tell, if it happens again: a current build shows the state control as a **dropdown**; the build
that throws shows a **row of buttons**.

### The React interface

`/app` serves the React interface; `/ui` still serves the server-rendered pages, and every page not
yet converted is reached through them. The bundle is built from `src/webui` and **committed** into
`app/src/main/resources/aspm/app/webui`, so the container build needs no Node toolchain — see
`src/webui/README.md`. A deployment with no bundle on the classpath answers `/app` with a 404 and
everything else keeps working.

### The migrate service re-applies everything, so every migration must replay

`docker compose up` runs `migrate` before `app`, and `apply.sh` applies **every** file on **every**
start. A migration that works once and fails the second time takes the whole stack down with
`service "migrate" didn't complete successfully: exit 1`.

Two failure modes have been hit. Both are silent on the first run.

**A view that a later migration widens.** `V022` created `request_board` with `CREATE OR REPLACE
VIEW`; `V024` widened it. On replay `V022` runs first, against a view that now has six more columns,
and `CREATE OR REPLACE` **cannot remove a column from an existing view** — `ERROR: cannot drop
columns from view`, and the stack will not start. Fixed by making every view migration `DROP VIEW IF
EXISTS` then `CREATE VIEW`, so each migration's definition is authoritative at the moment it runs.
`V018`, `V022`, `V024` and `V026` do this; any new view migration must too, including dropping
dependent views first.

**A backfill under row-level security.** V021 shipped with exactly that defect. Its backfill wrote a tenant-scoped table; the row-level policy
called `current_tenant_id()`, which **refuses rather than defaulting** — `SEC-TEN-005` failing closed,
working as designed. It had been applied by hand with a tenant context set and never replayed.

**Why it was not caught.** Migrations run as `aspm_migrate`, a LOGIN role *granted* `migration_runner`
— and **`BYPASSRLS` is a role attribute that membership does not inherit**, so the real migration
session has policies applied. The verification suite applies migrations as the embedded superuser,
which bypasses them, and it applies each file once. Neither half of the condition is present there.

A third, related trap: a backfill that loops `SELECT id FROM tenant` is correct in shape and does
nothing at all if the registry is empty — see `seed-tenant.sql` above. Guard such a loop with a
`ROW_COUNT` total and `RAISE EXCEPTION` on zero, so the no-op is loud.

**Known gap.** Nothing asserts replay. Reproducing it needs a fixture that provisions the deployment's
four roles properly — a role that owns the objects and does not bypass row-level security — which is
the same work the conformance job in `deploy/verify` does against a real cluster. Until then, replay
is verified by hand:

```bash
PORT=$(grep '^POSTGRES_PORT=' .env | cut -d= -f2); PASS=$(grep '^MIGRATE_PASSWORD=' .env | cut -d= -f2)
for f in $(find ../src -path '*/db/migration/V*.sql' -not -path '*/build/*' \
           | awk -F/ '{print $NF"\t"$0}' | sort | cut -f2); do
    PGPASSWORD=$PASS psql -h 127.0.0.1 -p ${PORT:-5432} -U aspm_migrate -d aspm \
        -v ON_ERROR_STOP=1 --quiet -f "$f" || { echo "REPLAY FAILED: $f"; break; }
done
```

**Attribute value types matter.** A `MULTI_SELECT` attribute is stored as a JSON **array** and a
`BOOLEAN` as a JSON **boolean**. `jsonb` containment is type-sensitive, so a value written as the
string `"true"` or as `"JAVA, SPRING"` is invisible to the filter — and the filter returns nothing,
which reads as "no third-party applications" rather than as a bug. The seeds write the right types;
the editor writes them from the declared `data_type`.

Note the **port**: `.env` may set `POSTGRES_PORT`, and the host often has its own PostgreSQL on 5432.
Pointing psql at the wrong one produces `password authentication failed`, which reads as a stale
credential rather than as the wrong server.

What is reachable once it is:

| Route | Class | Permission | What it does |
|---|---|---|---|
| `/ui/account` | G | — | Two-factor state, credential policy in force, and every session signed in as you with source context (`SEC-SEC-012`) |
| `/ui/change-password` | G | — | Self-service change. **Also the forced-change surface**: the dispatcher redirects every other route here while `must_change_password` is set |
| `/ui/step-up` | G | — | Re-presents the second factor for a class C or class E operation (`PRD-IAM-003`, `SEC-SEC-004`) |
| `/ui/users` | A | `iam.user.read` | The administration list, with users holding no role called out rather than shown blank |
| `/ui/users/{id}` | A | `iam.user.read` | Assignments with their scope, credential state, session count |
| `/ui/users/{id}/reset` | C | `iam.credential.reset` | Single-use token, forced change, **every session of the target revoked** (`SEC-SEC-016`) |
| `/ui/users/{id}/roles` | E | `auz.role.manage` | Grant a role over a scope. Revoke is a sibling route |
| `/ui/roles` | A / E | `auz.role.manage` | The permission matrix, plus create. Retired roles shown and marked |
| `/ui/roles/{id}` | A / E | `auz.role.manage` | Compose one role: rename, replace its permission set, retire, restore, delete-if-never-assigned |
| `/ui/security-policy` | A / E | `iam.user.manage` | Password and session policy (`PRD-IAM-006`, `PRD-IAM-007`) |

### The authorization defect the user found by using it

**The permission every operation declares was never consulted.** The registry refuses to construct an
operation without a required permission; the manifest records it; the startup banner prints it. Nothing
read it. Authorization lived inside individual handlers — `ResourceEndpoint` checked, `RequestTransition`
checked, and every page class that did not check was reachable by **any authenticated principal**. A
principal holding only `vul.finding.read` opened the user administration list and the permission matrix,
because `AdminPages` used `holds()` to decide which *panels* to draw and never to decide whether to answer
at all.

This is broken object-level authorization: first on the list of the five highest-risk surfaces, and the
defect class this product exists to find in other people's software. A control that is declared, tested for
its declaration, and never consulted is worse than an absent one — every artifact a reviewer would consult
said the gate was there.

It is enforced in the dispatcher now, for every route, because `CON-PLT-009` wants one enforcement point
and a handler check is opt-in: the next page added omits it, which is exactly how this happened. Handlers
keep their own checks, because they also resolve **scope**, which needs the row.

Three consequences followed from the same review, all of them things the interface asserted and did not do:

| What it claimed | What it did |
|---|---|
| The sidebar lists what you can use | It listed everything, including `/ui/assessments`, which **has no route at all** and answered 404 for everyone |
| "Sign out" ends your session | It was a **link to `/ui/sign-in`**. It rendered the sign-in form while the session cookie stayed live — anybody who used it on a shared machine left an authenticated session behind believing they had ended it. It is a POST to `/ui/sign-out` now |
| A banner warns when development authentication is in use | `Context.of` hardcoded the flag to `true`, so it warned on every page in every deployment. A warning that is always on consumes the attention a real one needs |

Navigation and the command palette are filtered by permission — derived from the registry, never declared
beside the link, so the two cannot drift. Hidden rather than shown-and-refused: the dispatcher answers 404
so the permission model cannot be mapped by probing, and a sidebar that lists the page and then 404s hands
back exactly what the 404 withholds.

**What a non-administrator now sees:** the overview and lists their permissions allow, and `/ui/account` —
their own profile, their own sessions, and a password change. Not the user list, not the permission matrix,
not the credential policy. The account panel used to print the tenant's password policy to every caller;
the reuse depth and expiry interval in particular tell an attacker holding one credential how long it is
good for. The only policy value that survives is the minimum length, on the change form, because a person
choosing a password needs the rule that will reject them.

**Roles are composed, not seeded and frozen.** An administrator holding `auz.role.manage` creates a role,
renames it, replaces its permission set from the catalogue, retires it and — if it was never assigned —
deletes it. A role that has ever been assigned is **retired, never deleted**: `role_assignment.role_id` is
`ON DELETE RESTRICT` including for revoked grants, because a revoked grant is the record of a decision
(product principle 5). Retiring works because `IdentityService.principal` joins `role` on
`lifecycle_state = 'ACTIVE'`, so a retired role stops granting immediately while its history stays
readable. A new role holds **nothing** — a default permission set would be a grant nobody chose.

### Step-up on a form, and why the first attempt was unusable

Class C and class E need the second factor re-presented. The dispatcher's redirect to `/ui/step-up` was
written for `GET` only, so **every administrative form POST fell through to a JSON `401 STEP_UP_REQUIRED`**
— creating a role, saving one, granting, resetting, saving the policy. A browser showed a line of JSON and
a form that could not recover. That is the third instance of one pattern in this work: a route verified
through the client that finds it easy, never through the one that uses it.

Two changes:

- The dispatcher redirects a **UI POST** as well, to a page that exists. `/ui/users/{id}/roles/revoke` has
  no `GET`, so the return path walks up the template until the registry serves one — landing on
  `/ui/users/{id}`. Derived from the registry, so a route added later gets a correct return path without
  anybody remembering. The API keeps the JSON 401: a pipeline cannot follow a redirect to a form.
- The pages **ask before showing an editable form**. Not elevated → an inline prompt, and the form renders
  inside `<fieldset disabled>`, which disables every control natively and means a disabled control is not
  submitted. Elevation lasts five minutes; losing a thirty-checkbox permission matrix once to a challenge
  nobody warned about is how people learn to distrust a form.

**Two defects this work surfaced, both of which passed every existing check.**

*The step-up gate had no key.* `Principal.stepUpAuthenticated` was a literal `false` at every
production construction site, and the dispatcher enforces step-up for class C and class E. So every
class E operation already registered — node-type and asset-type writes among them — answered
401 `STEP_UP_REQUIRED` to every human caller, and no surface existed that could clear the condition.
The authorization tests asserted that a caller *without* step-up is refused; nothing asserted that a
caller *with* it is admitted, because no such caller could be constructed. A gate nothing can pass is
indistinguishable, from the refusal side, from a gate nothing needs.

*Form posts to class B and class E routes answered 400.* An HTML form cannot set a request header, and
those classes require an `Idempotency-Key`. **Every transition button on the request detail page was
returning 400** — the entire workflow surface. It was missed because the transition endpoint was
verified through the API path, where sending a header is trivial; the UI posts the same operation and
was never exercised the way a browser exercises it. A route verified only by the client that finds it
easy is a route verified for the wrong caller. The key now also arrives as a hidden field, which is
not a weaker key: replay protection needs a value the *client* fixes and repeats on retry, and a field
rendered into the page has exactly that property.

**Deviations carried deliberately.** Each is a decision, not a to-do that slipped.

| Deviation | Why, and what it costs |
|---|---|
| The self-service routes are **class G** | They are authorized by identity, not permission, and every non-G class must name a catalogue permission. Naming one would let a role omit it — locking a principal out of the page that recovers from lockouts — and `/ui/change-password` must be reachable by a principal holding **no role at all**, which the bootstrap creates. **The cost:** class G declares `Classification.PUBLIC`, and `/ui/account` discloses the caller's own session list. The classification understates the page. ADR-036 fixed the class count at seven, and an eighth class to tidy one page is the wrong trade |
| The reset token travels in a **query string** | It lands in browser history and any proxy access log in front of this tier. Bounded by being single-use and expiring in 30 minutes. The correct fix is a server-side flash store, which this tier does not have |
| The scope node is **typed, not picked** | A picker must list only nodes the *granting* principal can reach — one that lists more discloses the organization's shape above them. Product principle 4 also makes a picker a usability feature and never the control; `ck_assignment__scope_present` is the control |
| Step-up shares `last_accepted_step` with sign-in | A code spent signing in cannot immediately elevate, so a caller who signs in and opens an administrative page waits for the next 30-second step. The alternative is a step-up a captured code satisfies twice |
| The TOTP secret is **not encrypted** | Stored in a column named `secret_ciphertext` with `secret_key_ref = 'PLAINTEXT_PENDING_OQ_026'`. ⚠ OQ-026 is open; there is no key management in this deployment at all |
| The breach corpus holds **25 entries** | A check over it passes almost everything, which looks exactly like a check that works. Every surface that applies it says so on the page rather than passing silently — PP-1, applied to one of the platform's own controls |
| `forgot-password` issues no token | Email delivery is not configured. The page says so instead of confirming a message nobody will receive, and directs the user to an administrator — which is why the administrative reset displays the token on screen |

---

## What the application tier still owes

In dependency order.

1. **Federated authentication.** ADR-004 requires OIDC/OAuth2 for humans and sender-constrained
   credentials for services, with **no bearer API keys**. ADR-059 narrows the human half to a local
   credential plus TOTP and that is what runs — which means the platform is now a credential holder,
   the third property ADR-004 was protecting. `DevPrincipalResolver` still exists as an explicit
   opt-in for a development environment and **refuses to construct** unless
   `ASPM_ENVIRONMENT=development`, so the gap cannot be lifted into production by copying a file; the
   server fails to start instead. The service half — sender-constrained credentials — is unbuilt, and
   `POST /api/v1/sbom-submissions` is class F with nothing able to satisfy it.
2. **The identity and access context** (DOC-03 §17), which no prompt in the implementation sequence
   assigned. Roles, assignments, sessions, credentials and the permission catalogue now exist in
   `V015`/`V016` with the surfaces above. What remains of it: provisioning and deprovisioning from a
   tenant identity source (`PRD-IAM-004`), the break-glass path (`PRD-IAM-011`), recovery codes (the
   `mfa_recovery_code` table is created and nothing writes to it), and audit events on the hash chain
   for authentication and session lifecycle — `authentication_attempt` records them in its own table,
   and `PRD-IAM-012` wants them on the chain.
3. **The rest of the API.** Six groups of roughly twenty, and only the operations a descriptor can
   express. Absent, each for a reason rather than for want of time:
   - **Reorganization** — move, merge, split. DOC-05 §12 records these as asynchronous because they
     touch thousands of closure rows and cannot finish in a request budget. `parent_id` is therefore
     not updatable, and that absence is the control.
   - **Graph traversal** (`GET /assets/{id}/graph`). `PRD-API-032` requires per-node filtering, silent
     branch termination, and a depth bound independent of the caller's scope. Every one of those is
     behaviour, not a projection.
   - **Bulk and export** (class D), **state transitions**, **merges and their reversal**, **ownership
     claims**, **evidence and attachments**, **webhooks**, **ingestion**.

   The forward-coverage gate in `_traceability/GAPS.md` is where the missing ones show up.
4. **Audited denials.** The authorization gate's denial sink logs. DOC-14 requires an audit event on
   the hash chain, and a log line is not one.
5. **Idempotency replay.** The key is required, validated and tenant-namespaced; the stored outcome
   that makes a repeat return the original result rather than repeating the effect
   (`PRD-API-005`) is not implemented.
6. **Rate limit classes.** Every operation declares one and nothing enforces it. `OPS-DEP-017`
   assigns enforcement to the ingress, which is not in this topology.
