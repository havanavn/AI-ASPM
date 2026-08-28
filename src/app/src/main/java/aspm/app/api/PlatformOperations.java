package aspm.app.api;

import aspm.app.resource.ResourceCatalogue;
import aspm.app.resource.ResourceGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Every API operation the platform serves, with its annotation class. {@code PRD-API-019}, ADR-036.
 *
 * <p>Until this class existed, {@link OperationRegistry} was built, tested, and <b>empty</b> — and the
 * backward-traceability gate of {@code PRD-PLT-012} reported the API half as vacuous rather than passing,
 * because a gate over an empty set passes for the wrong reason.
 *
 * <p><b>It is still not the API.</b> DOC-05 §12 to §25 specify well over a hundred operations; this
 * derives twenty from six resource groups. What it does not cover is listed in {@code deploy/README.md}
 * with the reason each one needs behaviour a descriptor cannot express, rather than left to be inferred.
 *
 * <h2>The class assignment is per operation shape, not per group</h2>
 *
 * <p>DOC-05's tables assign a class to every operation, and the assignments follow a pattern the shape
 * determines: reads are A, writes on domain objects are B, and writes on tenant configuration are E
 * because configuration changes what every other decision is computed from. Deriving the class from the
 * shape means a new group cannot be registered with a weaker class than its shape warrants — the
 * omission ADR-036 is written against.
 *
 * <p>The exception is deliberate and stated: a group with an empty {@code writableOnCreate} registers no
 * creation operation at all. Findings are that case (ADR-011).
 */
public final class PlatformOperations {

    private PlatformOperations() {
    }

    public static OperationRegistry registry() {
        List<OperationRegistry.Operation> operations = new ArrayList<>();

        // The interface. DOC-08, ADR-058.
        //
        // Class G for the assets and the sign-in page: a stylesheet, a script and a sign-in form
        // disclose nothing, and requiring authentication to reach a sign-in page is a loop.
        // Class A for every page that presents data — the SAME class as the API operation behind it,
        // because a page is a read of the same rows and a weaker class on the interface would be a
        // second, weaker enforcement point (CON-PLT-009).
        // Class G. A logo discloses nothing and must render on the sign-in page, before anybody is
        // authenticated — the same reasoning that already makes the stylesheet class G.
        operations.add(new OperationRegistry.Operation("GET", "/brand/logo.svg",
                AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/brand/icon-180.png",
                AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/style.css",
                AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/app.js",
                AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        // Class G, all of them, and every one is an authentication surface rather than an
        // authenticated one. A sign-in page behind authentication is a loop; a second-factor challenge
        // behind full authentication is the same loop one step later. The gate on these routes is the
        // session's factor state, checked inside the handler, not the annotation class.
        //
        // One list, because there is one interface. These were registered twice — once under /ui and
        // once under /app — so that signing in from the single-page interface did not throw somebody out
        // to the other prefix and back. With the interface at the root there is nowhere else to be.
        // Registered here as well as routed, because a handler without an operation starts with no
        // annotation class and PRD-API-019 refuses to boot rather than let that ship.
        for (String route : java.util.List.of("/sign-in", "/mfa", "/mfa-enrol",
                "/forgot-password")) {
            operations.add(new OperationRegistry.Operation("GET", route,
                    AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
            operations.add(new OperationRegistry.Operation("POST", route,
                    AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        }
        // The self-service account surfaces. Class G, and the reason is in AccountPages' class note: these
        // operations are authorized by IDENTITY rather than by permission, the seven classes of ADR-036
        // have no shape for that, and naming a catalogue permission would lock a principal out of the one
        // page that recovers from a lockout.
        //
        // The gate is the session's factor state, checked inside each handler. The deviation is that class
        // G declares Classification.PUBLIC while GET /ui/account discloses the caller's own session list —
        // recorded in deploy/README.md rather than resolved by inventing an eighth class.
        for (String route : java.util.List.of("/change-password", "/step-up")) {
            operations.add(new OperationRegistry.Operation("GET", route,
                    AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
            operations.add(new OperationRegistry.Operation("POST", route,
                    AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        }
        // The user guide. Class G on the same reasoning as the account surfaces above: it is
        // authorized by identity rather than by a catalogue permission, and naming one would hide the
        // guide from the roleless principal the deployment bootstrap creates — the reader whose first
        // question is why they can see nothing. The session is checked inside the handler, because the
        // guide names permission codes and scope modes and PRD-API-036 withholds the shape of the
        // authorization model from an unauthenticated caller.
        // The same two documents, as JSON, for the interface that renders them. Class G on the same
        // reasoning as the page: a guide gated on a catalogue permission is hidden from the roleless
        // principal the bootstrap creates, who is the reader most in need of it. The session is checked
        // in the handler, so neither document reaches an unauthenticated caller.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/guide",
                AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/api-guide",
                AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));

        // User administration. DOC-07, PRD-IAM-*.
        //
        // Class A on the read, matching the API convention: a page is a read of the same rows, so a weaker
        // class on the interface would be a second, weaker enforcement point (CON-PLT-009).
        // Class C for the reset, which is the class that fits and not merely the nearest.
        //
        //   - It reveals a RESTRICTED value: a single-use token that is a bearer credential for the
        //     account. Class C is the restricted-reveal class and carries Classification.RESTRICTED.
        //   - It requires step-up, which the catalogue independently marks on iam.credential.reset.
        //   - Its replay protection is NOT_APPLICABLE, and that is correct rather than convenient: a
        //     repeated reset spends the previous token and issues one more, converging on the same state —
        //     one live token, sessions revoked, a change forced. Repeating it is not a second effect.
        // Class E for the grants: a role assignment is authorization configuration, which is what every
        // subsequent scope decision is computed from. E is also the only class pairing step-up with a
        // replay key, and both belong on a grant — a duplicate grant created by a retried request is a
        // change to who can see what that nobody asked for twice.
        // Role composition used to be registered here as five server-rendered form posts. It is now
        // served only by /api/ui/roles*, which registers the same class E on the same permission a few
        // lines above — the reasoning it carried is recorded there rather than duplicated here.

        // The application inventory and the organization hierarchy. ADR-009, ADR-010.
        //
        // Class A on the reads and class B on the writes. NOT class E: an application is a scoped
        // domain object, not tenant configuration — DOC-05 §12 assigns E to node TYPES and criticality
        // tiers, which change how every other decision is computed, and B to the objects themselves.
        // Making an inventory edit demand step-up would put a TOTP prompt in front of adding a staging
        // URL, and a control that fires on routine work is one people route around.
        // The assessment board. Class A on the reads; class B on recording and amending a finding —
        // a scoped write on a domain object. Accepting risk is class C: it reveals nothing but it is
        // the operation DOC-07 §15.1 calls the first control an auditor tests, and asm.request.acceptrisk
        // is marked requires_step_up in the catalogue.
        // ---- The React interface -----------------------------------------------------------------
        //
        // The shell and its assets are class G: a bundle discloses nothing, and gating it would mean
        // an unauthenticated visitor gets a broken page instead of a sign-in redirect from the first
        // API call the interface makes.
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/board/{id}/assign",
                AnnotationClass.B_SCOPED_WRITE,
                Optional.of(aspm.app.resource.ResourceCatalogue.REQUESTS.updatePermission()),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/board/{id}/comments",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.RequestPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/board/{id}/comments",
                AnnotationClass.B_SCOPED_WRITE, Optional.of(aspm.app.ui.RequestPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET",
                "/api/ui/board/{id}/findings/{findingId}", AnnotationClass.A_SCOPED_READ,
                Optional.of(aspm.app.ui.RequestPages.READ), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST",
                "/api/ui/board/{id}/findings/{findingId}", AnnotationClass.B_SCOPED_WRITE,
                Optional.of(aspm.app.ui.RequestPages.TRIAGE), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST",
                "/api/ui/board/{id}/findings/{findingId}/comments", AnnotationClass.B_SCOPED_WRITE,
                Optional.of(aspm.app.ui.RequestPages.READ), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/people",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.RequestPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/applications",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/applications/{id}",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        // The posture dashboard for one application. A_SCOPED_READ and the same permission as the
        // inventory: it answers a question about an asset the caller can already read, and giving an
        // aggregate view its own permission would let a deployment grant the summary of an
        // application to somebody who cannot open it — a rollup is not less sensitive than its rows.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/applications/{id}/posture",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        // Projects. Same permission as the application inventory they are a branch of: a project is
        // an asset, and ADR-009 keeps one aggregate with a type registry rather than a second
        // inventory with a second set of permissions to keep in step.
        // Software composition. sbm.coverage.read, the same permission the SBOM coverage page
        // has always carried: these routes answer the same question in more detail, and a more
        // detailed answer to a question somebody may already ask is not a new decision.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/alerts",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.resource.WebhookAlerts.MANAGE),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/alerts",
                AnnotationClass.E_CONFIGURATION, Optional.of(aspm.app.resource.WebhookAlerts.MANAGE),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/alerts/{id}/active",
                AnnotationClass.E_CONFIGURATION, Optional.of(aspm.app.resource.WebhookAlerts.MANAGE),
                Set.of(), Set.of()));
        // Planning the periodic assessment of applications. Class A: it reads the same requests and
        // cadence the application pages already read, filtered by the caller's scope, and decides
        // nothing.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/assessment-plan",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        // Laying out and moving planned windows. Class B: a scoped write against one target at a
        // time, re-validating the caller's reach over every target in the batch. Not E — a window is
        // one record's intention, not configuration other decisions are computed from; and not F,
        // because planning is a person's act at a screen, never a pipeline's.
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/assessment-plan/windows",
                AnnotationClass.B_SCOPED_WRITE,
                Optional.of(aspm.app.ui.UiApi.PLAN_WINDOW_PERMISSION), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("PATCH",
                "/api/ui/assessment-plan/windows/{id}", AnnotationClass.B_SCOPED_WRITE,
                Optional.of(aspm.app.ui.UiApi.PLAN_WINDOW_PERMISSION), Set.of(), Set.of()));
        // Asserting that a review happened outside the platform, and taking the assertion back. Class
        // B: scoped to one application, re-validated at the statement. Its own permission rather than
        // a reuse, because it moves a coverage figure on one person's word (ADR-066).
        operations.add(new OperationRegistry.Operation("POST",
                "/api/ui/assessment-plan/attestations", AnnotationClass.B_SCOPED_WRITE,
                Optional.of(aspm.app.resource.ReviewAttestations.ATTEST), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST",
                "/api/ui/assessment-plan/attestations/{id}/withdraw", AnnotationClass.B_SCOPED_WRITE,
                Optional.of(aspm.app.resource.ReviewAttestations.ATTEST), Set.of(), Set.of()));
        // Class B: a scoped write on one asset. Not E — it changes one record's lifecycle, not the
        // configuration every other decision is computed from. The handler refuses an APPLICATION so
        // this cannot become a second, weaker path to retiring one (CON-PLT-009).
        // Class B, and deliberately NOT class F. /api/v1/sbom-submissions is the pipeline's door and
        // the dispatcher requires a service principal there; a browser session is not a pipeline.
        // Relaxing that class to let one page through would have widened the pipeline door for every
        // caller, so the interactive upload gets its own scoped write instead — over the same
        // ingestion code, so the two cannot come to mean different things.
        operations.add(new OperationRegistry.Operation("POST",
                "/api/ui/dependencies/artifact/{id}/sbom",
                AnnotationClass.B_SCOPED_WRITE, Optional.of("sbm.sbom.submit"),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST",
                "/api/ui/dependencies/artifact/{id}/retire",
                AnnotationClass.B_SCOPED_WRITE, Optional.of(aspm.app.ui.ApplicationPages.UPDATE),
                Set.of(), Set.of()));
        // The vulnerability management dashboard. Class A: it reads findings the caller may already
        // reach through the board and the application pages, filtered by the same scope, and decides
        // nothing. SEC-AUZ-016 matters more here than anywhere else on the interface — the page is
        // almost entirely counts, and a count computed over a wider population than the caller may see
        // discloses the existence of findings outside their scope without ever showing one.
        // The AI suggestion ledger. The read is class A. The decision is class B and restricted: it
        // is the moment ADR-005 exists for, where a model's output becomes something a named person
        // accepted. Running a capability is class B too — it writes suggestions, and PRD-AIC-056
        // requires the invocation to be explicit rather than a side effect of viewing anything.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/suggestions",
                AnnotationClass.A_SCOPED_READ,
                Optional.of(aspm.app.resource.SuggestionLedger.READ), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/suggestions/{id}/decide",
                AnnotationClass.B_SCOPED_WRITE,
                Optional.of(aspm.app.resource.SuggestionLedger.PROMOTE), Set.of(), Set.of()));
        // The most-common-weakness tables. One class A read serving the overview, the application pages
        // and the project pages, so the three cannot come to disagree.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/top-weaknesses",
                AnnotationClass.A_SCOPED_READ, Optional.of("vul.finding.read"),
                Set.of(), Set.of()));
        // The classify button on the finding form. Class A despite being a POST: it reads text the
        // caller already has and returns an opinion, writing nothing. The body is a POST because a
        // finding description is too long and too sensitive for a query string, not because state moves.
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/findings/classify",
                AnnotationClass.A_SCOPED_READ, Optional.of("vul.finding.triage"),
                Set.of(), Set.of()));
        // Submission health per integration credential — PRD-SBM-024, which ADR-023 names as the
        // mitigation for the SBOM push endpoint being a single point of failure for the whole SCA
        // capability. Gated on the coverage read rather than on credential management: the person who
        // needs to know an integration has stopped is whoever owns the coverage figure it feeds, and
        // requiring the restricted sbm.credential.manage would have hidden it from them.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/sbom-submission-health",
                AnnotationClass.A_SCOPED_READ, Optional.of("sbm.coverage.read"),
                Set.of(), Set.of()));
        // One finding without an assessment request. Class A on the finding read permission: it is the
        // same record the board serves, reached by its own identifier so a pipeline finding — which
        // never had a request — has somewhere to be opened. Scope is in the query, not after it.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/findings/{id}",
                AnnotationClass.A_SCOPED_READ, Optional.of("vul.finding.read"),
                Set.of(), Set.of()));
        // The finding lifecycle. The read reports every move with a permitted flag and a reason, so a
        // caller who may not verify learns that rather than finding a button missing.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/findings/{id}/lifecycle",
                AnnotationClass.A_SCOPED_READ, Optional.of("vul.finding.read"),
                Set.of(), Set.of()));
        // Class B. The declared permission is what it takes to REACH the operation; the authority for
        // each move — claimfix, verify, acceptrisk — is checked per transition, because one registry
        // entry cannot hold three permissions and three endpoints would be three places for the wrong
        // one to be attached later without looking wrong.
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/findings/{id}/transition",
                AnnotationClass.B_SCOPED_WRITE, Optional.of("vul.finding.read"),
                Set.of(), Set.of()));
        // The per-dashboard analyse button. Class B, and gated on the DECISION permission rather than
        // the administrator one: enabling a capability is an administrator's call, asking an enabled
        // one to look at the screen in front of you belongs to whoever will act on the answer.
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/agents/analyse",
                AnnotationClass.B_SCOPED_WRITE,
                Optional.of(aspm.app.resource.SuggestionLedger.PROMOTE), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/agents/{code}/run",
                AnnotationClass.B_SCOPED_WRITE, Optional.of("aic.capability.manage"),
                Set.of(), Set.of()));
        // The export is an ordinary scoped read of rows the dashboard already shows, in a different
        // container. Class A, same permission: a spreadsheet is not a weaker disclosure than a table.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/vulnerabilities/export",
                AnnotationClass.A_SCOPED_READ, Optional.of("vul.finding.read"),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/vulnerabilities",
                AnnotationClass.A_SCOPED_READ, Optional.of("vul.finding.read"),
                Set.of(), Set.of()));
        // The AI provider configuration. The read is class A and carries no part of a key; both writes
        // are class E — one accepts a live third-party credential, and both decide what may leave the
        // platform for a third party to read (risk surface 5).
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/ai-providers",
                AnnotationClass.A_SCOPED_READ,
                Optional.of(aspm.app.resource.AiProviderService.MANAGE), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/ai-providers",
                AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.resource.AiProviderService.MANAGE), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/ai-providers/{id}/active",
                AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.resource.AiProviderService.MANAGE), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/review-policy",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        // Class E. Setting the interval is not scheduling work: it decides how long every application
        // on a tier may go unassessed, and because next-due is derived rather than stored, widening
        // it makes part of the estate stop being overdue retroactively and silently. That is
        // configuration in the sense the class exists for, and it is why the permission is restricted
        // and step-up while merely reading the same numbers is not.
        operations.add(new OperationRegistry.Operation("PUT", "/api/ui/review-policy/{id}",
                AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.resource.ReviewPolicyService.MANAGE), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/dependencies",
                AnnotationClass.A_SCOPED_READ, Optional.of("sbm.coverage.read"),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/dependencies/node",
                AnnotationClass.A_SCOPED_READ, Optional.of("sbm.coverage.read"),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/dependencies/tree",
                AnnotationClass.A_SCOPED_READ, Optional.of("sbm.coverage.read"),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/dependencies/advisories",
                AnnotationClass.A_SCOPED_READ, Optional.of("sbm.coverage.read"),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/dependencies/components",
                AnnotationClass.A_SCOPED_READ, Optional.of("sbm.coverage.read"),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/dependencies/locations",
                AnnotationClass.A_SCOPED_READ, Optional.of("sbm.coverage.read"),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/dependencies/graph",
                AnnotationClass.A_SCOPED_READ, Optional.of("sbm.coverage.read"),
                Set.of(), Set.of()));
        // Issuing a pipeline credential is class E: it is authorization configuration, it mints a
        // non-interactive identity, and E is the only class pairing step-up with a replay key —
        // both of which belong on an operation that discloses a secret exactly once. The export is
        // an ordinary scoped read of rows the dashboard already shows, in a different container.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/service-credentials",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.identity.ServiceCredentialAdmin.MANAGE),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/service-credentials",
                AnnotationClass.E_CONFIGURATION, Optional.of(aspm.app.identity.ServiceCredentialAdmin.MANAGE),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/service-credentials/{id}/revoke",
                AnnotationClass.E_CONFIGURATION, Optional.of(aspm.app.identity.ServiceCredentialAdmin.MANAGE),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/dependencies/export",
                AnnotationClass.A_SCOPED_READ, Optional.of("sbm.coverage.read"),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/projects",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/projects/{id}",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/projects/{id}/requests",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.RequestPages.READ),
                Set.of(), Set.of()));
        // Reading and writing one project's record. B_SCOPED_WRITE rather than E_CONFIGURATION for
        // the save: this changes a domain object inside the caller's scope, not the tenant's
        // configuration. The FIELDS available are configuration and are declared elsewhere; filling
        // them in for one project is ordinary inventory work, and requiring a fresh second factor for
        // it would put a step-up in front of the most routine edit on the platform.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/projects/{id}/editor",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/projects/{id}/editor",
                AnnotationClass.B_SCOPED_WRITE, Optional.of(aspm.app.ui.ApplicationPages.UPDATE),
                Set.of(), Set.of()));
        // The declared-field catalogue. E_CONFIGURATION on every write: this is tenant configuration
        // and it is not scoped — a field declared on PROJECT changes what every project in the tenant
        // is asked for, in every branch of the organization tree, so there is no object to
        // re-validate against and no scope that could narrow it.
        //
        // The READ is A_SCOPED_READ on the same permission, deliberately: the catalogue is not
        // sensitive — every form already renders it — and requiring the management permission to
        // LOOK would leave somebody staring at a field they cannot explain with no way to read its
        // purpose. Managing is gated; reading the definitions is not.
        // The host reverse lookup. A_SCOPED_READ on the asset read permission: it answers a question
        // about assets the caller can already reach, and the scope predicate is applied to the
        // ATTACHED asset rather than to the domain — a domain has no owner, which is the whole reason
        // it is a shared asset. Two readers searching the same host each see only their own side.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/hosts",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/settings/fields",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/settings/fields",
                AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.inventory.InventoryService.FIELD_ADMIN), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/settings/fields/{id}",
                AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.inventory.InventoryService.FIELD_ADMIN), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST",
                "/api/ui/settings/fields/{id}/lifecycle", AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.inventory.InventoryService.FIELD_ADMIN), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/settings/fields/{id}/move",
                AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.inventory.InventoryService.FIELD_ADMIN), Set.of(), Set.of()));
        // The endpoint environment catalogue — the vocabulary both inventory editors render their
        // domain inputs from. A read anyone who can read the inventory may make, because the labels
        // appear on every list; writes are class E under the same permission as the field catalogue.
        // The estate graph. A read over two structures at once, so it is authorized as a scoped
        // read on the asset side — the organization nodes it returns are already filtered by the
        // caller's own closure, which is what SEC-AUZ-016 asks of an aggregate.
        // The application inventory as a spreadsheet. A read, and the same scoped read the list is:
        // the export runs the list handler, so it cannot return a row the list would not.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/applications/export",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/graph/{id}",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/settings/environments",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/settings/environments",
                AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.inventory.InventoryService.FIELD_ADMIN), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/settings/environments/{id}",
                AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.inventory.InventoryService.FIELD_ADMIN), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST",
                "/api/ui/settings/environments/{id}/lifecycle", AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.inventory.InventoryService.FIELD_ADMIN), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST",
                "/api/ui/settings/environments/{id}/move", AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.inventory.InventoryService.FIELD_ADMIN), Set.of(), Set.of()));
        // The posture dashboard for one project. Registered separately from the application one even
        // though a single handler serves both, so that the two can be authorized apart if a tenant
        // ever needs that — and so neither is reachable through the other's absence.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/projects/{id}/posture",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        // Raising a request. Class B — a scoped write on a domain object, not configuration — and
        // gated on the submit permission the intake surface has always used. The project it names is
        // re-read through the scoped query before anything is written (SEC-AUZ-017), so the picker
        // being filtered is a convenience and this is the control.
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/requests",
                AnnotationClass.B_SCOPED_WRITE, Optional.of("asm.request.submit"),
                Set.of(), Set.of()));
        // Ownership and delegation over one project, and the delivery-side people on one request.
        //
        // *** THE DECLARED PERMISSION HERE IS A FLOOR, NOT THE GATE. ***
        //
        // ADR-036 gives an operation ONE permission and the dispatcher enforces it before a handler
        // runs. These three authorities are composite — the security team, OR whoever owns this
        // particular project, OR whoever raised this particular request — and no single permission
        // expresses that. So each operation declares the weakest permission that makes the request
        // sensible at all, and ObjectAuthority makes the real decision in one place. The deviation is
        // recorded rather than hidden, and the class is still E: this is authorization configuration
        // and it still requires a fresh second factor.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/projects/{id}/access",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/projects/{id}/access",
                AnnotationClass.E_CONFIGURATION, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/projects/{id}/access/revoke",
                AnnotationClass.E_CONFIGURATION, Optional.of(aspm.app.ui.ApplicationPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/board/{id}/participants",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.RequestPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/board/{id}/participants",
                AnnotationClass.E_CONFIGURATION, Optional.of(aspm.app.ui.RequestPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST",
                "/api/ui/board/{id}/participants/remove", AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.ui.RequestPages.READ), Set.of(), Set.of()));
        // Class B, not E: claiming a fix moves one object and is not configuration. It is also the
        // one write a developer performs, so it must not demand a second factor they have no reason
        // to have enrolled for a comment-and-claim workflow.
        operations.add(new OperationRegistry.Operation("POST",
                "/api/ui/board/{id}/findings/{findingId}/remediation",
                AnnotationClass.B_SCOPED_WRITE, Optional.of(aspm.app.ui.RequestPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/organization",
                AnnotationClass.A_SCOPED_READ,
                Optional.of(aspm.app.resource.ResourceCatalogue.ORG_NODES.readPermission()),
                Set.of(), Set.of()));
        // The three dashboards, each carrying the permission of the server-rendered page it mirrors.
        // The overview reads findings and is gated on the finding read permission for the reason
        // recorded against /ui/overview: a dashboard gated more weakly than the rows behind it is a
        // summary of data the caller may not see.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/overview",
                AnnotationClass.A_SCOPED_READ,
                Optional.of(aspm.app.resource.ResourceCatalogue.FINDINGS.readPermission()),
                Set.of(), Set.of()));
        // cap.team.read is the AGGREGATE permission. The per-member section inside the payload needs
        // cap.member.read.all in addition, and the endpoint omits the key rather than emptying it
        // (PRD-CAP-013, ADR-047) — an operation-level permission cannot express that, which is why
        // the second test lives in the handler.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/workload",
                AnnotationClass.A_SCOPED_READ, Optional.of("cap.team.read"), Set.of(), Set.of()));
        // Assessor teams. Reading rides on the aggregate permission; changing them needs the
        // separate manage permission, because a roster decides how every per-team figure groups.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/teams",
                AnnotationClass.A_SCOPED_READ, Optional.of("cap.team.read"), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/teams",
                AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.resource.TeamService.MANAGE), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/teams/members",
                AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.resource.TeamService.MANAGE), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/teams/{id}/retire",
                AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.resource.TeamService.MANAGE), Set.of(), Set.of()));
        // The management view. cap.team.read is the AGGREGATE permission; the per-person series
        // inside need cap.member.read.all in addition and are omitted without it.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/workload/analytics",
                AnnotationClass.A_SCOPED_READ, Optional.of("cap.team.read"), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/composition",
                AnnotationClass.A_SCOPED_READ, Optional.of("sbm.coverage.read"),
                Set.of(), Set.of()));
        // The account panel, as JSON. Class G for the reason recorded against /ui/account and
        // restated at AccessApi: the subject is the caller themselves, and a catalogue permission
        // here would lock a principal out of their own profile — including the roleless principal the
        // deployment bootstrap creates. The session is checked inside the handler.
        // The session keepalive. Class G for the same reason as the account panel: the subject is the
        // caller's own session, and a catalogue permission would stop a principal keeping their own
        // session alive. It grants nothing — the touch it causes is the write every authenticated
        // request already performs, and the absolute limit it cannot postpone.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/session/keepalive",
                AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/account",
                AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/account/sessions/revoke",
                AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        // Authorization administration, as JSON. Each carries the class AND the permission of the
        // server-rendered page it mirrors, which is what keeps the step-up requirement: class C and
        // class E are refused by the dispatcher without a fresh second factor, and the interface
        // reacts to that refusal rather than testing for it itself.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/access",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.AdminPages.READ_USERS),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/access/users/{id}",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.AdminPages.READ_USERS),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/access/users/{id}/roles",
                AnnotationClass.E_CONFIGURATION, Optional.of(aspm.app.ui.AdminPages.MANAGE_ROLES),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST",
                "/api/ui/access/users/{id}/roles/revoke", AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.ui.AdminPages.MANAGE_ROLES), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/access/users/{id}/reset",
                AnnotationClass.C_RESTRICTED_REVEAL,
                Optional.of(aspm.app.ui.AdminPages.RESET_CREDENTIAL), Set.of(), Set.of()));
        // The write side of the React interface. Each carries the SAME permission and the same
        // annotation class as the server-rendered route it mirrors — an editor reachable from /app
        // under a weaker gate than the identical one at /ui would be a bypass, not a convenience.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/applications/editor",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.UPDATE),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/applications/{id}/editor",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.ApplicationPages.UPDATE),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/applications",
                AnnotationClass.B_SCOPED_WRITE, Optional.of(aspm.app.ui.ApplicationPages.CREATE),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/applications/{id}",
                AnnotationClass.B_SCOPED_WRITE, Optional.of(aspm.app.ui.ApplicationPages.UPDATE),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/applications/{id}/retire",
                AnnotationClass.B_SCOPED_WRITE, Optional.of(aspm.app.ui.ApplicationPages.UPDATE),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/organization",
                AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.ui.OrganizationPages.CREATE), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/organization/{id}",
                AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.ui.OrganizationPages.UPDATE), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST",
                "/api/ui/organization/{id}/deprecate", AnnotationClass.E_CONFIGURATION,
                Optional.of(aspm.app.ui.OrganizationPages.UPDATE), Set.of(), Set.of()));
        // Recording a finding, on the same permission as the server-rendered form: triage, not read.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/board/{id}/finding-form",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.RequestPages.TRIAGE),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/board/{id}/findings",
                AnnotationClass.B_SCOPED_WRITE, Optional.of(aspm.app.ui.RequestPages.TRIAGE),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/roles",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.AdminPages.MANAGE_ROLES),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/roles/{id}",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.AdminPages.MANAGE_ROLES),
                Set.of(), Set.of()));
        for (String route : java.util.List.of("/api/ui/roles", "/api/ui/roles/{id}",
                "/api/ui/roles/{id}/retire", "/api/ui/roles/{id}/restore",
                "/api/ui/roles/{id}/delete")) {
            operations.add(new OperationRegistry.Operation("POST", route,
                    AnnotationClass.E_CONFIGURATION,
                    Optional.of(aspm.app.ui.AdminPages.MANAGE_ROLES), Set.of(), Set.of()));
        }
        // The two retired prefixes, as redirects. Class G because a redirect that strips a prefix
        // discloses nothing and decides nothing — whatever it lands on applies its own gate. Registered
        // so PRD-API-019 is satisfied: a handler with no operation has no annotation class.
        for (String legacy : java.util.List.of("/ui", "/app")) {
            for (String shape : java.util.List.of("", "/{a}", "/{a}/{b}", "/{a}/{b}/{c}",
                    "/{a}/{b}/{c}/{d}")) {
                operations.add(new OperationRegistry.Operation("GET", legacy + shape,
                        AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
            }
        }
        operations.add(new OperationRegistry.Operation("GET", "/",
                AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        // One operation per interface template. Class G: the shell carries no authorization of its own —
        // it is an HTML document naming hashed asset files, and every figure in it arrives through an API
        // call that is authorized on its own terms. See WebUi.ROUTES for why these are enumerated.
        for (String template : aspm.app.ui.WebUi.ROUTES) {
            operations.add(new OperationRegistry.Operation("GET", template,
                    AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        }
        operations.add(new OperationRegistry.Operation("GET", "/assets/{name}",
                AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        // The DATA is not. Each of these carries the same permission and the same scope composition
        // as the server-rendered page it replaces — the representation changed, the authorization
        // did not.
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/session",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.RequestPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/board",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.RequestPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/board/{id}",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.RequestPages.READ),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/board/{id}/transitions",
                AnnotationClass.B_SCOPED_WRITE,
                Optional.of(aspm.app.resource.ResourceCatalogue.REQUESTS.updatePermission()),
                Set.of(), Set.of()));

        operations.add(new OperationRegistry.Operation("POST", "/board/{id}/attachments",
                AnnotationClass.B_SCOPED_WRITE, Optional.of(aspm.app.ui.RequestPages.READ),
                Set.of(), Set.of()));
        // Class A: the bytes of an image already referenced by a page the caller could read. The
        // subject authorization happened at that page; see AttachmentService#load on why a second,
        // differently-shaped scope test here would be a liability rather than defence in depth.
        operations.add(new OperationRegistry.Operation("GET", "/attachments/{id}",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.RequestPages.READ),
                Set.of(), Set.of()));
        for (String code : java.util.List.of(aspm.app.ui.RequestPages.READ,
                aspm.app.ui.RequestPages.TRIAGE, aspm.app.ui.RequestPages.ACCEPT_RISK)) {
            aspm.kernel.authorization.contract.PermissionId.of(code);
        }

        // Component composition. Class A to open the form, class B to write — a feature or a service
        // is a scoped domain object like the application it belongs to.
        for (String code : java.util.List.of(aspm.app.ui.ApplicationPages.READ,
                aspm.app.ui.ApplicationPages.CREATE, aspm.app.ui.ApplicationPages.UPDATE,
                aspm.app.ui.OrganizationPages.READ, aspm.app.ui.OrganizationPages.CREATE,
                aspm.app.ui.OrganizationPages.UPDATE)) {
            aspm.kernel.authorization.contract.PermissionId.of(code);
        }

        // The credential and session policy. Gated on iam.user.manage rather than a code of its own,
        // because the catalogue is product-fixed and has no sec.policy.* entry — and whoever administers
        // credentials is who administers the rules those credentials are held to. Stated because a
        // reviewer will ask why a policy page reuses a user permission.
        operations.add(new OperationRegistry.Operation("GET", "/security-policy",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.ui.AdminPages.MANAGE_USERS),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/security-policy",
                AnnotationClass.E_CONFIGURATION, Optional.of(aspm.app.ui.AdminPages.MANAGE_USERS),
                Set.of(), Set.of()));

        // Every permission code named above is constructed here so a malformed one fails at STARTUP
        // rather than on the first request — where the 400 it produced told the caller which permission
        // the operation requires.
        for (String code : java.util.List.of(aspm.app.ui.AdminPages.READ_USERS,
                aspm.app.ui.AdminPages.MANAGE_USERS, aspm.app.ui.AdminPages.RESET_CREDENTIAL,
                aspm.app.ui.AdminPages.MANAGE_ROLES)) {
            aspm.kernel.authorization.contract.PermissionId.of(code);
        }

        operations.add(new OperationRegistry.Operation("GET", "/sign-out",
                AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/sign-out",
                AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));
        // The overview dashboard reads findings, so it is gated on the finding read permission — the
        // same permission as the list it drills into. A dashboard gated more weakly than the rows behind
        // it is a summary of data the caller may not see.
        // The workload dashboard. Class A on cap.team.read — the AGGREGATE permission. The per-member
        // panel inside it needs cap.member.read.all as well, and the page omits the panel rather than
        // masking it (PRD-CAP-013, ADR-047).
        //
        // DOC-07 §5.2 excludes cap.team.read even from the Business Owner template, and the reasoning is
        // worth repeating: "a business owner who can see aggregate security-team capacity will direct
        // requests by observed availability, bypassing the prioritization the platform exists to
        // enforce". What they legitimately need is the feasible start date at intake.
        // SBOM submission. Class F: service credential only, and ADR-023 makes it the only automated
        // ingestion path in v1. ADR-004 requires a sender-constrained credential rather than a bearer
        // key, because a pipeline token that can be replayed from anywhere is one that will be.
        operations.add(new OperationRegistry.Operation("GET", "/api/v1/rescans/pending",
                AnnotationClass.F_SERVICE_INGEST, Optional.of("sbm.scan.run"),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/v1/rescans/{id}",
                AnnotationClass.F_SERVICE_INGEST, Optional.of("sbm.scan.run"),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/ui/rescan-schedule",
                AnnotationClass.A_SCOPED_READ, Optional.of(aspm.app.resource.WebhookAlerts.MANAGE),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/ui/rescan-schedule",
                AnnotationClass.E_CONFIGURATION, Optional.of(aspm.app.resource.WebhookAlerts.MANAGE),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/v1/sbom-submissions",
                AnnotationClass.F_SERVICE_INGEST, Optional.of("sbm.sbom.submit"), Set.of(), Set.of()));
        // Class F and its own permission. A scan report is a higher-value document than a bill of
        // materials — file paths, code snippets and rule identities — so granting a pipeline the right to
        // declare its dependencies must not also grant it the right to file findings against the
        // repository. V062 adds the catalogue entry; which roles hold it is tenant data (ADR-027).
        operations.add(new OperationRegistry.Operation("POST", "/api/v1/finding-imports",
                AnnotationClass.F_SERVICE_INGEST, Optional.of("ing.findings.import"),
                Set.of(), Set.of()));
        // Class F, the same door the scanner already knocks on. A signed service credential, no human
        // caller: this is housekeeping with no decision in it, and giving it a button would invite
        // somebody to treat a growing table as their problem to remember.
        operations.add(new OperationRegistry.Operation("POST", "/api/v1/session-reap",
                AnnotationClass.F_SERVICE_INGEST,
                Optional.of(aspm.app.identity.SessionReaper.REAP), Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("GET", "/api/v1/coverage-states",
                AnnotationClass.A_SCOPED_READ, Optional.of("sbm.coverage.read"), Set.of(), Set.of()));
        // Transitions. One operation for every event rather than one per event, because DOC-09 §4's
        // machine is data — an endpoint per event would need adding whenever a tenant adds a transition,
        // which is the coupling ADR-028 removes.
        //
        // Class B: a scoped write. Not E, because a transition is not configuration — it moves one
        // object and its guards are the configuration.
        operations.add(new OperationRegistry.Operation("GET", "/api/v1/requests/{id}/transitions",
                AnnotationClass.A_SCOPED_READ,
                Optional.of(aspm.app.resource.ResourceCatalogue.REQUESTS.readPermission()),
                Set.of(), Set.of()));
        operations.add(new OperationRegistry.Operation("POST", "/api/v1/requests/{id}/transitions",
                AnnotationClass.B_SCOPED_WRITE,
                Optional.of(aspm.app.resource.ResourceCatalogue.REQUESTS.updatePermission()),
                Set.of(), Set.of()));
        // The request detail page. Class A on the request read permission — the same permission as the
        // API operation behind it, because the page is a read of the same row.

        // The service document, at /api rather than at / — the root serves the interface now. It carries
        // no permission because there is no authorization to evaluate, and it discloses no path, no
        // operation and no permission code, because an unauthenticated index would hand back exactly
        // what PRD-API-036 withholds.
        operations.add(new OperationRegistry.Operation("GET", "/api",
                AnnotationClass.G_UNAUTHENTICATED, Optional.empty(), Set.of(), Set.of()));

        for (ResourceGroup group : ResourceCatalogue.all()) {
            String collection = "/api/v1/" + group.name();
            String object = collection + "/{id}";

            // Every permission code is constructed here so a malformed one fails at STARTUP. One failed
            // on the first request instead, and the 400 it produced told the caller which permission the
            // operation requires — which an unauthorized caller should not learn from an error.
            aspm.kernel.authorization.contract.PermissionId.of(group.readPermission());
            aspm.kernel.authorization.contract.PermissionId.of(group.createPermission());
            aspm.kernel.authorization.contract.PermissionId.of(group.updatePermission());

            // Class A: scoped read. The scope predicate is composed into the query, and for a group with
            // no scope column that is a declared decision rather than an omission (ResourceGroup).
            operations.add(new OperationRegistry.Operation("GET", collection,
                    AnnotationClass.A_SCOPED_READ, Optional.of(group.readPermission()),
                    Set.of(), group.filterable()));

            // Class A, re-validated against the OBJECT (SEC-AUZ-017). Authorizing the path and then
            // loading the row is the broken-object-level-authorization defect this product exists to
            // find in customers' software.
            operations.add(new OperationRegistry.Operation("GET", object,
                    AnnotationClass.A_SCOPED_READ, Optional.of(group.readPermission()),
                    Set.of(), Set.of()));

            if (!group.writableOnCreate().isEmpty()) {
                operations.add(new OperationRegistry.Operation("POST", collection,
                        classFor(group), Optional.of(group.createPermission()), Set.of(), Set.of()));
            }
            if (!group.writableOnUpdate().isEmpty()) {
                operations.add(new OperationRegistry.Operation("PATCH", object,
                        classFor(group), Optional.of(group.updatePermission()), Set.of(), Set.of()));
            }
        }
        return OperationRegistry.of(List.copyOf(operations));
    }

    /**
     * Class E for tenant configuration, class B for a scoped domain object.
     *
     * <p>DOC-05 §12 assigns E to node types and criticality tiers and B to node updates, and the rule
     * behind it is the difference: configuration is what every scope, score and service level decision is
     * computed from, so changing it warrants step-up authentication and an idempotency key. A duplicated
     * node type created by a retried request is a structural change nobody asked for twice.
     *
     * <p>A group with no scope column is configuration by construction — every principal holding the
     * permission sees every row — so the same fact decides both the class and the absence of a scope
     * predicate, and the two cannot drift apart.
     */
    private static AnnotationClass classFor(ResourceGroup group) {
        return group.scoped() ? AnnotationClass.B_SCOPED_WRITE : AnnotationClass.E_CONFIGURATION;
    }
}
