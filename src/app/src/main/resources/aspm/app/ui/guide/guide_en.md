This guide describes what each screen is for, what it will and will not tell you, and why it
sometimes refuses. It is written for everybody who uses the platform, not only for administrators —
so most of it applies whatever your role is called in your organization.

Read the first two sections even if you read nothing else. They explain the two behaviours that
surprise people: why your sidebar is shorter than a colleague's, and why a number is sometimes
missing rather than zero.

## 1. What the platform is for

It holds the security posture of your organization's software: the applications and services you
run, who is accountable for each, the weaknesses found in them, the dependencies they ship, and the
work of getting those weaknesses fixed.

Three consequences of that follow you around the product.

**It is a work system, not only a dashboard.** Assessment requests, findings, comments, claims that a
fix is in place and the verification of that claim all live here. If the conversation about a finding
happens somewhere else, every figure the platform produces is computed over an incomplete record.

**It never reads your source code.** The platform does not clone repositories and holds no Git
credentials. What it knows about your dependencies arrives because a build pipeline submitted a
software bill of materials. Between submissions it is blind, and it says so rather than showing you a
stale figure as if it were current.

**It distinguishes measured-and-clean from not-measured.** This is the single most important rule in
the product and section 2 is about nothing else.

## 2. Reading the numbers honestly

An empty result and an unmeasured one look identical in most tools. Here they do not.

- **A measured zero** is shown as a number: `0`.
- **An unmeasured figure carries no numeral at all.** You will see words — *Not measured*, *Never
  measured*, *Not shown to you*, *Unavailable* — where a digit would otherwise be.

That distinction is deliberate. A dashboard that is green because nobody has looked is worse than no
dashboard, because somebody will act on it.

Alongside the figures you will find two supporting facts:

- **Coverage** — how many of the assets in view have actually been measured, out of how many exist.
  "3 of 9 assets measured" means six of them are telling you nothing.
- **Freshness** — when the measurement happened. A dependency figure from four months ago describes
  the software as it was four months ago.

**A failed scan never closes a finding.** If an ingestion run fails, findings stay open and coverage
drops. Nothing silently improves because a measurement stopped arriving.

When you export a screen to a spreadsheet, the same rule applies: the export carries the same rows
and the same coverage as the screen it came from, not a tidier version of them.

## 3. Signing in

Your organization's administrator creates your account. You sign in with a username or email address
and a password.

**A second factor is required.** On first sign-in you are taken to enrolment: scan the QR code with
an authenticator application, then confirm with a six-digit code. You cannot reach any other screen
until enrolment is complete — it is not a setting you can decline. Save the recovery codes you are
given somewhere other than the machine you signed in from; each one works once.

**You may be asked to change your password immediately.** Accounts created by an administrator, and
accounts whose credential was reset, land on the change-password screen and stay there until a new
password is set.

**Passwords are length-first.** The default minimum is twelve characters and there are no composition
rules — no forced symbol, no forced digit. A long passphrase is both easier to remember and harder to
attack than a short one with a `!` on the end. Your password is checked against a corpus of
credentials known to have been breached, both when you set it and when you sign in; if it appears
there you will be asked to choose another.

**Sessions end.** By default a session lasts eight hours absolutely and thirty minutes idle, and
twelve hours is the longest any deployment may configure. When a session expires you are returned to
sign-in with the page you were on remembered.

### Step-up: being asked for the code again

Some operations ask for your second factor again even though you are already signed in. These are the
operations that change what everyone else's decisions are computed from, or that reveal something
restricted:

- composing a role or granting one to somebody,
- resetting another person's credential,
- issuing a pipeline credential,
- changing the structure types, criticality tiers or asset types,
- changing the assessment review policy or the AI provider configuration,
- approving the acceptance of a residual risk.

The interface may warn you *before* you start filling in a form that the operation will need a fresh
code — losing a half-typed grant to a redirect is worse than being told in advance. That warning is
advice; the actual gate is on the server and applies whether or not you saw the warning.

## 4. Finding your way around

**The sidebar** is grouped by what you are doing rather than by how the data is stored: what is
happening now, what the estate is, and what configures the platform.

**The scope indicator** in the top bar names the part of the organization you are looking at. Every
figure on every screen is computed over that scope and nothing wider. If you are uncertain which
slice you are reading, this is where the answer is.

**The command interface** opens with Ctrl+K (⌘K on a Mac) from anywhere, or by clicking the search
box in the top bar. It lists the sections you can reach. Searching for an object by its code is not
implemented yet, and the dialog says so rather than returning nothing and letting you conclude the
object does not exist.

**Theme and density** are in the top bar: light, dark, high contrast, and comfortable or compact row
height. These apply for the current session.

**Keyboard.** Lists move with `j` and `k` or the arrow keys. Escape closes dialogs. No capability in
the product is reachable only with a pointer, and none is reachable only with script enabled.

**Your account** is reached from the top bar. It shows your username, email, display name, the roles
you hold, whether your second factor is enrolled, and every session currently signed in as you —
with the address and browser each came from. Terminate any you do not recognise.

## 5. Why your sidebar differs from a colleague's

Two independent things decide what you see. Confusing them is the most common source of "why can't I
see X".

**Permissions decide *what kinds of thing* you may do.** Read findings, triage a finding, submit a
request, manage roles — each is a separate named permission. Your administrator groups permissions
into roles and gives you one or more roles.

**Scope decides *which* objects those permissions reach.** A role is granted to you over a part of
the organization: the whole tenant, one node and everything under it, or one node alone. A person
with the permission to read findings, scoped to one project, sees that project's findings and no
others.

You need both. Being able to see a project does not let you request work against it; being allowed to
triage does not let you triage another division's findings.

Three behaviours follow from this, and they are intentional:

- **A section you cannot reach is not listed.** The sidebar hides it rather than showing it and
  refusing, because a sidebar full of links that fail teaches people not to trust the sidebar.
- **A refused request answers "not found", not "not allowed".** The two are deliberately
  indistinguishable. If they differed, somebody could map another division's projects by noting which
  identifiers came back with a different error.
- **A filtered picker is a convenience, never a control.** When a dropdown offers you only the
  projects you may use, that is to save you scrolling. The server re-checks whatever you submit.

**If you believe you should be able to see something and cannot,** ask whoever administers access
whether you hold the permission and whether your grant covers the right part of the organization. On
your account screen, "None assigned" under roles means exactly what it says: you can sign in and
reach nothing.

## 6. Overview

The landing screen. It answers "how are we doing" for your scope:

- headline measures, each of which shows a word rather than a numeral when the population behind it
  is unmeasured;
- a twelve-week trend of findings opened against findings closed;
- coverage bars — how much of the estate in view has been measured at all;
- the severity distribution of what is currently open;
- the most recently recorded findings.

The trend is the figure worth reading first. Closed consistently below opened means the backlog is
growing regardless of what the totals say.

Every panel is a link into the rows behind it. Clicking through re-checks your authorization against
each record; reaching a chart does not grant you the records it summarises.

## 7. Findings and vulnerabilities

**Vulnerabilities** lists every finding in your scope with filters for severity, state and the asset
it affects, and exports to a spreadsheet. **Pipeline** is the same findings narrowed to those that
arrived from automated scanning rather than from an assessment — the same permission, a different
audience.

### The life of a finding

A finding moves through six situations. They are separate because collapsing any two of them loses a
fact somebody needs:

1. **Open** — recorded, and nobody has claimed a fix.
2. **Fixed** — the delivery team says it is done and nobody has checked yet. This is a *claim*, not a
   conclusion.
3. **Acceptance requested** — somebody has asked to leave the weakness in place and nobody has
   approved it. This still counts as open work. If asking counted as closing, the fastest way to
   clear a backlog would be to request acceptances and never approve them.
4. **Closed** — somebody checked and it is fixed.
5. **Reopen** — somebody checked and it is *not* fixed. Distinct from Open on purpose: "was reported
   fixed and was not" is the fact worth counting, and returning it to Open would erase the only
   signal that a retest failed.
6. **Accepted risk** — deliberately left in place, until a stated date. An acceptance with no end
   date is not an acceptance, so the date is required.

### Who may make which move

- **Claiming a fix** is for whoever owns the remediation. It is not a privileged act, and asking to
  accept a risk uses the same permission — whoever owns the work is who asks not to do it.
- **Verifying** — closing or reopening — is a separate permission, held by whoever assessed the
  finding. The team being assessed does not close its own findings; a platform where it does measures
  nothing.
- **Approving an acceptance** is restricted, requires a fresh second factor, and **must be a
  different person from the one who asked.** This is enforced in the database, not merely checked in
  the interface. If you are refused here, it is because you are the requester.

Every move is recorded as its own row with who made it, when, and why. That record cannot be edited.

### Reading a finding

The description and the proof of concept are rendered by the server, not assembled in your browser.
Finding content legitimately includes text an attacker wrote — that is what a proof of concept is —
so it is stored as Markdown and rendered through a strict, small subset. Raw HTML is escaped and
shown as text. Links to schemes other than `http` and `https` are not links. Images by URL are not
rendered at all, because an image tag pointing at somebody else's host reports who opened the finding
and when; evidence goes through the attachment path instead, where the file type is verified.

If a field you expect is simply absent rather than blanked out, that is the field-level rule at work:
a restricted value you may not read is *removed* from the response, not replaced with dots. A row of
dots would confirm that a value exists, which for a recovered secret confirms a credential exists at
that location.

## 8. The assessment board

The board is where requests for security work live and move. Each card is one request.

### Raising a request

Use **New request** from the board. You name the project the work is about, describe what you need,
and submit.

You need more than visibility to raise a request. Seeing a project tells the platform you may read
it; asking for work against it commits somebody else's time and exposes the asset to testing. Either
hold the assessment-execution permission — the security team does, so that retests and
incident-driven work are not blocked — or be recorded as an owner or delegate of that specific
project. Whoever owns a project can delegate the right to request work on it without needing any
platform-wide administrative permission.

### The workflow

Requests move through a workflow that your organization defines. The default shipped configuration
runs: draft, submitted, intake review, accepted or returned for information, scheduled, assigned, in
progress (with a blocked state available), testing complete, report draft, report under quality
assurance, report delivered, fixing, retest, and one of three terminal states — closed passed, closed
with accepted risk, or cancelled.

Two properties of that workflow are worth knowing because they will refuse you at some point:

- **Each move requires its own permission.** Submitting, triaging, scheduling, conducting and
  approving a report in quality assurance are five different permissions, and no role is required to
  hold all five.
- **Approving a report in quality assurance must be somebody other than its author.** The move is
  refused otherwise, with a sentence explaining why rather than a constraint name.

The board shows you which moves are available on each request and, where one is not, why.

### Working on a request

Open a request to see its state, the person it is assigned to, its deadline, the findings recorded
against it, its participants and its comments.

**Participants** are the delivery-side people: the developers actually doing the work. Add them and
they can read the request and its findings, comment, and claim a fix — but not close a finding. The
security team, the owner of the project the request names, and the person who raised the request can
all manage this list. The requester is included because they are usually the only one who knows which
developers are on the work.

**Recording a finding** against a request needs the triage permission. The editor is a rich-text
editor that stores Markdown, with a toolbar deliberately limited to what the renderer supports — a
button that produced markup the server then dropped would silently lose your work. Images pasted into
it go through the attachment path and must be raster images; the server checks the bytes rather than
trusting the file name.

**Comments** are permanent. A comment can be redacted with a visible record that it was, by somebody
holding that permission; it cannot be quietly removed.

## 9. Applications, projects and components

**Applications** is the inventory of what your organization runs. Each entry carries its name, the
organization node accountable for it, its criticality, its exposure, its lifecycle state and its
current risk score. Filter by any of those; sort by any of them.

Opening an application gives you its profile, its technical composition, its findings rolled up by
severity, its assessment requests and its review cadence.

**Projects** are the branch of an application that a particular team delivers. A project's
application is worked out by walking the composition graph up to the nearest application, not stored
on the project — so moving a project does not leave a stale answer behind. This is also what lets an
intake form ask only for the project.

**Components** is the technical estate at a finer grain: the features and services that make up an
application.

**Editing.** Creating and updating an application or a component is an ordinary scoped write and does
not ask for a second factor. Putting a code prompt in front of adding a staging URL would be a
control that fires on routine work, and controls that fire on routine work are the ones people route
around. Retiring an entry is likewise a normal write; it is reversible in the sense that the record
remains, and history is never deleted.

**Organization** shows the hierarchy itself — the nodes, their types, their parents, their
criticality and the assets hanging off each. Changing the hierarchy *is* configuration: it decides
who can see what, so it asks for a second factor and it is audited with before and after state.

## 10. Dependencies and software composition

**Dependencies** answers "what are we shipping, and which of it is vulnerable". It presents the
components in use, where each is used, the advisories affecting them, and the dependency graph.

**Composition** shows coverage: every asset including — importantly — the ones that have never
submitted a bill of materials at all. An asset absent from a dependency report is not an asset with
no dependencies.

### How data gets here

A build pipeline submits a software bill of materials to the platform's submission endpoint using a
credential issued from **Settings**. That push is the only automated ingestion path. The platform does
not fetch, clone or scan source code, and it stores no repository credentials — so nothing here
depends on granting the platform access to your code.

You may also upload a bill of materials by hand from an artifact's page. It goes through exactly the
same ingestion code as the pipeline path, so the two cannot come to mean different things.

**Submission health** reports, per credential, whether submissions are still arriving. A pipeline that
quietly stopped six weeks ago is the failure mode this exists to catch: coverage decays, no error is
raised anywhere, and the dashboard keeps showing the last figures it had. It is available to whoever
can read coverage, not only to whoever manages credentials — the person who needs to know an
integration has stopped is whoever owns the number it feeds.

## 11. Workload and planning

**Workload** shows how assessment work is flowing: counts by state, how long each stage is taking,
and the queue of work that is waiting and what it is waiting on.

**Per-person allocation is a separate permission.** Reading team-level aggregates and reading how much
work each named individual is carrying are different acts with different consequences, and the second
is restricted, audited, and never implied by seniority. Where you do not hold it, the per-person
section is *absent* from the screen rather than shown empty.

Aggregates over very small groups are suppressed rather than shown. In a team of three where two
members are visible to you, the third's workload is arithmetic away — so the platform declines to do
the arithmetic.

**Planning** answers "what is owed across the estate and when". The review cadence configured for
each criticality tier decides when an application becomes due for reassessment, and the next-due date
is derived rather than stored — which is why widening an interval is treated as configuration and
requires a second factor. Widening it makes part of the estate stop being overdue retroactively.

## 12. Settings

Configuration that changes how the platform behaves, as distinct from who may use it.

**AI providers.** Which model provider the platform may send content to, and its credential. This
decides what tenant data may leave your boundary, so both writes require a second factor and the
credential is never readable after entry — not at any permission level. The read side of this screen
carries no part of a key.

**Alerts.** Webhook destinations notified when a new advisory affects something you ship.

**Rescan schedule.** How often scheduled scans are fetched and submitted.

**Review policy.** The reassessment interval per criticality tier. See the note in section 11 about
why this asks for a second factor.

**Service credentials.** Pipeline identities. Issuing one displays the secret exactly once — there is
no path to retrieve it afterwards. Revoking one takes effect immediately. The credential is
sender-constrained rather than a bearer token, which is why the integration is a signing step rather
than a header you copy.

## 13. AI assistance

The platform can draft, classify and summarise. It does not decide.

**Everything a model produces lands in a suggestion ledger.** A suggestion is a proposal with its
grounding attached: which records it was derived from. Nothing a model writes enters the system of
record on its own.

**Promotion is a human action and it is audited.** When you promote a suggestion, the platform
re-checks that *you* are authorized to make the resulting change. A suggestion cannot accomplish
something you could not do yourself.

**Numbers are never generated.** Where a narrative contains a figure, that figure is bound to a field
on a record, not produced by a model. A score, a service-level deadline, a deduplication decision and
an authorization decision are all computed deterministically and are reproducible; if you run the
same computation twice you get the same answer twice.

**Analyse** buttons on the dashboards ask an enabled capability to look at the screen in front of
you. Asking is gated on the permission to act on the answer, not on the administrator permission that
enabled the capability.

## 14. Access and roles

Two screens, deliberately separate, because "who may use the platform" and "what a role means" are
different jobs usually held by different people. One screen serving both is how somebody looking up a
colleague's grants ends up editing a permission grid.

### Access — people and their grants

Lists the people in your scope and what each holds. Opening a person shows their grants, lets you add
or revoke one, and lets you issue a credential reset.

**Granting a role means choosing two things:** the role, and the scope it applies over.

- **Tenant** — the entire organization. Names no node.
- **Subtree** — the node you choose and everything beneath it.
- **One node only** — the node you choose.

A grant may carry an expiry. Revoking a grant records who revoked it and why; it does not delete the
history of what that person did while they held it.

**A credential reset** issues a single-use link, revokes the person's live sessions and forces a
password change. It is restricted and requires a second factor, because the link it produces is
briefly a way into the account.

### Roles — what a role means

A role is a named set of permissions. The permission catalogue itself is fixed by the product — you
compose from it, you do not add to it — because a permission with nothing enforcing it would appear
to grant protection and provide none.

Roles are yours. Rename them, split them, merge them, retire them, delete the ones you do not use.
The names that ship with a new deployment are a starting point chosen so the platform is usable on
day one, not product concepts; nothing in the platform behaves differently because a role is called
one thing rather than another.

Points worth knowing before you compose one:

- **Read and export are separate permissions throughout.** Reading one finding and extracting fifty
  thousand are different acts with different risk.
- **Restricted permissions are never implied.** Revealing a recovered secret, reading per-person
  workload and revealing a test credential each require their own deliberate grant. No amount of
  seniority or administrative capability confers them.
- **A new permission is never added to an existing role automatically.** When the platform gains a
  capability, nobody has it until somebody grants it. That is the correct default even though it
  means a step after every upgrade.
- **Managing roles is separate from every operational permission.** Somebody who can both act and
  grant can grant themselves anything, which makes every other constraint advisory.
- **A role in use cannot be deleted.** Retire it instead; the grants that reference it keep their
  meaning.

**Credential policy** is a third screen under configuration: password length, reuse history, whether
breached-credential checking is on, session lifetimes, and whether the second factor is required for
everybody. It also reports the size of the breach corpus loaded, so a thin corpus is visible rather
than looking like a working check.

## 15. When something is refused

Four different refusals, and they mean different things.

**The screen is not in your sidebar.** You do not hold the permission that screen requires. Nothing is
wrong; ask for the permission if you need it.

**"Not found" on something you believe exists.** Either it does not exist, or it exists outside your
scope. The platform will not tell you which — see section 5.

**You are asked for your authenticator code again.** The operation is one of those in section 3. Enter
the code and you are returned to what you were doing.

**An action is refused with a sentence.** A workflow guard, a separation-of-duties rule, or a missing
prerequisite. The sentence says which; act on it rather than retrying.

If a page fails outright rather than refusing, that is a defect and it is meant to be loud. The
platform is built to fail visibly rather than to degrade into showing you a plausible but wrong
answer.

## 16. Accessibility and language

The interface works at 200% zoom without loss of function. Colour is never the only carrier of
meaning — every state that has a colour also has a label or a shape. Every capability is reachable
from the keyboard, and every capability works without JavaScript enabled, though some are more
comfortable with it.

The source language is English and Vietnamese is the first target locale. Dates and numbers are
formatted for your locale; times are stored in UTC and displayed against your organization's business
calendar, which is what makes a deadline mean the same thing to everybody reading it.

## 17. What this build does not do yet

Stated plainly, because a gap you discover by trying is worse than one you were told about.

- **Search by object code is not implemented.** The command interface navigates to sections; it does
  not find records.
- **Historical authorization is not implemented.** Asking "who could see this last March" is not
  answerable yet, and the platform refuses the question rather than answering it from today's
  organization chart.
- **Container registry scanning is deferred.** The extension points exist and are refused at the
  application layer rather than half-working.
- **Some administrative screens are still server-rendered** and look a little different from the
  rest. Sign-in, the second-factor challenge, the credential change and the step-up prompt are
  deliberately among them: their correctness lives in server-side redirects, and a gate implemented in
  two places is a gate that can disagree with itself.
