# webui — the React interface

React 19 · Vite 7 · Tailwind CSS 4 · shadcn/ui components over Radix primitives · Lucide icons.

## Building

```bash
npm install
npm run build      # type-checks, then writes into ../app/src/main/resources/aspm/app/webui
```

The output is **committed** and packaged into `app.jar` as a classpath resource. That is deliberate:
the Gradle build does not invoke npm, so a deployment — including the `docker compose` one — needs no
Node toolchain and cannot fail on a registry outage. The cost is that a change here is not live until
`npm run build` has been run and its output committed alongside the source.

```bash
npm run dev        # Vite on :5173, proxying /api and /ui to the Java tier on :8099
```

## Where the boundaries are

- **The bundle and the API share an origin.** The session cookie is `HttpOnly; SameSite=Strict`, so
  there is no token in JavaScript to steal. Serving the bundle from a CDN would require either a
  cross-site cookie or a token in browser storage; the second turns any script injection into account
  takeover, which is why neither is done.
- **The navigation comes from the server**, already filtered to what the caller may reach. The
  `permissions` array in `/api/ui/session` disables controls; it never decides what exists.
- **Attacker-influenced prose is not sent.** A finding's description and proof of concept are absent
  from `/api/ui/board/{id}`: they are rendered by the server's restricted Markdown renderer, and
  shipping the source to a browser would move that decision to the client.
- **`/api/ui/*` is not a public API.** `/api/v1` is the versioned contract of DOC-05. These endpoints
  are the shape one screen needs and change whenever that screen does.

## What is converted

| Route | Status |
|---|---|
| `/app/board` | React |
| `/app/board/{id}` | React — state, assignment, reason, findings, comments |
| `/app/board/{id}/findings/{fid}` | React — description, proof of concept, comments, all CKEditor |
| `/app/applications` | React |
| `/app/applications/{id}` | React — review cadence, profile, composition |
| `/app/organization` | React |
| `/app/overview` | React — KPIs, a twelve-week opened/closed trend, coverage bars, severity, recent findings |
| `/app/workload` | React — flow by state, stage times, the waiting queue, per-member allocation where permitted |
| `/app/composition` | React — every asset including the ones that never submitted |
| `/app/projects` | React — the branch of an application a team delivers |
| `/app/projects/{id}` | React — profile, parts, findings rollup, requests |
| `/app/account` | React — profile, credential state, live sessions with revoke |
| `/app/access` | React — people, their grants, and the role/permission matrix |
| `/app/access/users/{id}` | React — grant, revoke, and issue a credential reset |
| `/app/components` | Still server-rendered; `/app/*` hands off to `/ui` |
| role editor (`/ui/roles/{id}`) | Still server-rendered — composing a role's permission set |
| authentication (sign-in · TOTP · change password · step-up) | Deliberately still server-rendered — see below |

**The overview is the landing route again.** `/app` redirects to `/app/overview` rather than to the
board, which is where the sign-in redirect and every breadcrumb root already point.

**A project's application is derived, never stored.** `/api/ui/projects` walks the composition
graph up to the nearest `APPLICATION` root. A column on the project would be a second copy of an
answer the graph already holds, and the copy is what goes stale when a project moves — which is the
case the level exists for. It is also what lets an intake form ask only for the project.

**A class C or class E operation needs a fresh second factor, and the client does not test for it.**
The dispatcher answers `401 STEP_UP_REQUIRED` for an `/api/` path; `api.ts` turns that into a trip to
`/ui/step-up?next=…` and back. A page may show a warning *before* the caller starts filling a form —
losing a half-typed grant to a redirect is worse than being told in advance — but the warning is
advice, never the gate.

**An unmeasured figure carries no numeral in the payload.** `/api/ui/overview` sends `value: null`
with a `state` whenever the measured population is zero, because a client that receives `{"value":
0, "measured": 0}` is one careless `value ?? 0` away from committing the failure `PRD-UIX-022`
names. `MeasureCard` keeps the other half: with a state set it renders the word and no digit.

**Why authentication and administration stay on `/ui`.** Those flows are sign-in, TOTP enrolment,
forced credential change and step-up re-authentication. Their correctness lives in server-side
redirects — the dispatcher sends an unauthenticated or un-stepped-up caller to the page that owns the
operation, and the browser follows. Reimplementing that as client-side routing means reimplementing
the gate, and a gate that exists in two places is a gate that can disagree with itself. There is no
visual gain worth that.

## The rich-text editor

CKEditor 5, configured with the `Markdown` plugin so `getData()` returns **Markdown**.

That is the whole security argument. The content edited here — a finding description, a proof of
concept, a comment — is the most attacker-influenced text on the platform. Storing the HTML CKEditor
produces by default would mean sanitizing HTML on the server: a new, large, famously error-prone
surface sitting exactly where the hostile content is. Storing Markdown changes nothing about the
pipeline: `Markdown.java` still escapes before it introduces markup, still permits only a closed set
of elements, and remains the only thing that decides what markup exists.

- The **toolbar is the subset that renderer supports.** A button producing markup the server then
  drops is a control that silently loses somebody's work.
- **Images** go through the existing attachment endpoint via a custom upload adapter — base64 in a
  form field, not multipart, for the reason recorded on that endpoint. The server derives the media
  type from the bytes and refuses anything that is not a raster image.
- The editor is **1.2 MB and lazily loaded**, so pages with no prose on them do not pay for it.
- `licenseKey: "GPL"` — CKEditor 5 is dual licensed and will not start without the choice stated.

Converting a page means adding its JSON to `UiApi`, registering the operation in `PlatformOperations`
with the **same permission and annotation class** as the page it replaces, and updating the two
traceability tests.
