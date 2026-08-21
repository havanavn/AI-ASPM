package aspm.app.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.app.api.AnnotationClass;
import aspm.app.api.OperationRegistry;
import aspm.app.api.PlatformOperations;
import aspm.app.resource.ResourceCatalogue;
import aspm.app.resource.ResourceGroup;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The application tier. ADR-057, ADR-036, and the defects that only running it surfaced. */
class ApplicationTierTest {

    private static final String ORG_NODE_TYPES = "/api/v1/org-node-types";

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRINCIPAL = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** A resolver that always authenticates, so a test can reach past step 2 of the dispatch order. */
    private static PrincipalResolver alwaysAuthenticated(Set<String> permissions, boolean stepUp) {
        return new PrincipalResolver() {
            @Override
            public Optional<Principal> resolve(Map<String, String> headers) {
                return Optional.of(new Principal(TENANT, PRINCIPAL, permissions, Set.of(), stepUp, false,
                        false));
            }

            @Override
            public String description() {
                return "test";
            }
        };
    }

    private static Dispatcher dispatcherWith(PrincipalResolver resolver) {
        // Handlers that are never reached by these assertions: every one of them stops earlier.
        Dispatcher.Handler unreached = request -> {
            throw new AssertionError("the handler was reached; the check under test did not stop the "
                    + "request, which means the property it asserts is not enforced");
        };
        // Derived from the registry, not listed. The dispatcher refuses to construct where a route has
        // no registered operation or an operation has no route, so a hand-written list here would have
        // to be edited on every catalogue change — and a test that has to be edited to keep compiling is
        // a test somebody eventually edits to keep passing.
        List<Dispatcher.Route> routes = PlatformOperations.registry().all().stream()
                .map(operation -> new Dispatcher.Route(operation.method(),
                        new PathTemplate(operation.pathTemplate()), unreached))
                .toList();
        return new Dispatcher(PlatformOperations.registry(), routes, resolver);
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("The registry is no longer empty, and every route is registered")
    class Registry {

        @Test
        @DisplayName("PRD-PLT-012: the API half of backward traceability is no longer vacuous")
        void operationsAreRegistered() {
            OperationRegistry registry = PlatformOperations.registry();
            assertFalse(registry.all().isEmpty(),
                    "the registry was built, tested and EMPTY, so the backward-traceability gate reported "
                            + "the API half as vacuous — a gate over an empty set passes for the wrong "
                            + "reason. It is three operations now, which is a beginning and not a "
                            + "completion.");
            // Two hundred and twenty. The one added is GET /ui/guide — the user guide, class G on the
            // same reasoning as the account surfaces: authorized by identity rather than by a
            // catalogue permission, because naming one would hide the explanation from the roleless
            // principal the bootstrap creates.
            //
            // Before that, 219. The count is asserted so that adding a group or a page without deciding its
            // annotation class fails HERE rather than at the first request — and so the number in
            // deploy/README.md cannot drift from the number the platform serves.
            //
            // Previously 101. The three added are the actions the board was missing: moving a
            // request through its workflow, naming the people and the deadline, and closing a
            // finding with a stated outcome.
            //
            // Before that, 92. The nine added then were the assessment board: the board, one request, one
            // finding, recording a finding, amending it, accepting risk, reopening, and the two
            // comment routes.
            //
            // Before that, 91. The one added then was /ui/components — the technical estate, which is what
            // /ui/assets was doing badly: that generic list showed every asset row including the
            // applications and features the application inventory already presents.
            //
            // Before that, 86. The five added then were the component editor: the new-component form, the
            // edit form, create, update, and detach.
            //
            // Before that, 75. The eleven added then were the application inventory (list, new, detail, edit,
            // create, update, retire) and the organization hierarchy (list, create, update, deprecate).
            //
            // Before that, 68. The seven added then were role composition: the editor read, create, save, retire,
            // restore, delete, and POST /ui/sign-out — which exists because signing out is a state change
            // and a GET is reachable by anything that prefetches a link.
            //
            // Before that, 53. The fifteen added then were the identity and access surfaces: six
            // self-service (GET and POST on /ui/account, /ui/change-password and /ui/step-up), the
            // own-session revocation, two user-administration reads, the credential reset, two role-grant
            // writes, the permission matrix, and the credential policy read and write.
            //
            // Before that, 33: twenty-three API operations from six groups, the service document, the
            // overview dashboard, four list pages, and the unauthenticated interface assets. The
            // twenty-three derive from six groups — two reads each, plus a create for the five groups that
            // accept one and a patch for all six; findings register no create (ADR-011).
            // 219. Six added: the sign-in, second-factor and enrolment pages served under /app as
            // well as /ui — GET and POST for three routes. The SAME handlers at a second address, not a
            // second gate: signing in used to throw somebody out of /app to /ui and back, which reads
            // as leaving the product. Registered because a routed handler with no operation has no
            // annotation class, and the tier refuses to start rather than ship one (PRD-API-019).
            //
            // Before that, 213. The one added serves ONE finding by its own identifier, with no assessment request
            // in the path. A pipeline finding never had a request — a scanner ran against a commit —
            // and the board's endpoint resolves a finding by listing a request's findings, so those
            // findings had nowhere to be opened. Sending a delivery team through the assessment board
            // to read their own scanner output is what dilutes the board and supervises the team.
            //
            // Before that, 212. The one added keeps a session alive while somebody is actually using the interface.
            // The idle limit moves on `last_seen_at`, which only advances when a request resolves a
            // session — and writing up a finding for half an hour makes none, so people were signed out
            // mid-sentence and the interface navigated away with their unsaved text. Class G: the
            // subject is the caller's own session.
            //
            // Before that, 211. The one added reports submission health per integration credential — PRD-SBM-024,
            // and ADR-023 names it as the reason the SBOM push endpoint's single-point-of-failure
            // status is tolerable. Before it, the only per-credential fact recorded was last_used_at,
            // which cannot tell a pipeline that submitted from one whose document was refused forty
            // times, and says nothing at all about one whose secret went stale.
            //
            // Before that, 210. The two added are the finding lifecycle: a read that reports where a finding is,
            // which moves exist, and how it got there; and the write that moves it. Two rather than
            // five, because the destination is a field in the body — five endpoints would be five
            // places to attach a permission and one of them would eventually be attached wrongly
            // without reading as odd. The authority for each move is checked per transition:
            // claiming a fix, verifying one, and accepting a risk are three permissions, and the
            // registry entry holds the weaker gate that reaches the operation.
            //
            // Before that, 208. The one added is the most-common-weakness table, by month, over three
            // classifications. One operation serving the overview and every application and project
            // page: the same question asked with a different asset, so three surfaces cannot come to
            // disagree about what the estate's commonest weaknesses are.
            //
            // Before that, 207. The one added classifies a finding from its words — executive risk category, OWASP
            // Top 10:2025 and CWE — and writes nothing. The answer goes into the form; the assessor
            // reads it and their submission is the write, which is how classification became
            // AI-assisted without AI ever writing a finding.
            //
            // Before that, 206. The one added removes expired sessions. The table had 119 rows of which 115 were
            // already expired and nothing ever pruned one. It is a call on the scanner's existing tick
            // rather than a timer in this tier, because a timer here runs once per replica and
            // OPS-DEP-007 wants a singleton.
            //
            // Before that, 205. The one added is the per-dashboard analyse button. Nothing analyses on view, so the
            // invocation has to be something somebody pressed — and its permission is the decision one
            // rather than the administrator one, or a triager could not ask about the finding in front
            // of them.
            //
            // Before that, 204. The two added serve the product mark as files. It was inlined as a data URI in
            // three places first — two interface heads and a component — and one file with three
            // references to its URL is the shape that cannot drift. Class G: a logo discloses
            // nothing and must render on the sign-in page, before anybody is authenticated.
            //
            // Before that, 202. The three added are the AI suggestion ledger: the review queue, the human decision
            // that promotes or rejects, and the explicit invocation of one capability. ADR-005 made
            // the ledger a requirement and V019 built the table; it held zero rows and had no readers,
            // so the rule was satisfied on paper and absent in fact until these.
            //
            // Before that, 199. The one added is the vulnerability export: the same filter the dashboard reads,
            // through the same reader, as a spreadsheet — and uncapped, because the export is what
            // makes the table's five-hundred-row cap acceptable.
            //
            // Before that, 198. The one added is the vulnerability management dashboard: one class A read carrying
            // the headline, five distributions, the trend, the picker options and the findings, all
            // under one filter. One operation rather than eight because eight round trips is eight
            // chances to render a headline describing a different population from the table below it.
            //
            // Before that, 197. The three added are AI provider configuration: the read, the create, and the
            // enable/disable. Configuration only — ADR-044 defers the capability and PRD-AIC-056
            // forbids invoking one on view, so nothing here calls a model. It ships first because the
            // alternative shape, a deployment-wide environment variable, cannot be un-chosen once
            // tenants have keys in it.
            //
            // Before that, 194. The one added is the interactive SBOM upload. It exists because
            // /api/v1/sbom-submissions is class F and the dispatcher requires a service principal
            // there — so the estate tree's upload button, written against it first, would have
            // refused every human caller.
            //
            // Before that, 193. The one added is retiring a composition artifact — the honest form of "remove this
            // SBOM". There is no delete: INV-SBM-01 makes a snapshot immutable and the findings
            // against it record a weakness that was really present, so the asset leaves the estate
            // and the evidence stays.
            //
            // Before that, 192. The two added are the review policy: the read of every criticality tier with its
            // interval, and the write that sets one. The write is class E and not B — it does not
            // schedule work, it decides how long every application on a tier may go unassessed, and
            // because next-due is derived rather than stored, widening it makes part of the estate
            // stop being overdue retroactively.
            //
            // Before that, 190. The one added is the assessment planning payload: rows, Gantt bars and monthly
            // load in one class A read. One endpoint rather than three because a Gantt is useless
            // without the row it belongs to, and three round trips is three chances to draw a
            // half-consistent picture of a moving estate.
            //
            // Before that, 189. The four added were scheduled re-scanning: the two the scanner calls (class F,
            // the same identity model a CI push uses) and the two an administrator uses to set how
            // often. It closes the defect that a component's advisory list only ever changed when a
            // pipeline re-pushed — a CVE published after the last push was invisible for ever.
            //
            // Before that, 185. The three added were vulnerability alert subscriptions: list, create and
            // enable/disable. Create and toggle are class E — a subscription decides where a
            // description of the group's unfixed vulnerabilities is sent, which is configuration in
            // the sense that matters: it changes what leaves the platform.
            //
            // Before that, 182. The one added lists every unresolved advisory under one node — an application, a
            // project or a repository, through the same subtree walk, so the dependency tree can be
            // opened at any level and answer the same question.
            //
            // Before that, 181. The four added were ingestion credentials — list, issue, revoke — and the
            // dependency export. Issue and revoke are class E: minting a non-interactive identity is
            // authorization configuration, and E is the only class pairing step-up with a replay key.
            //
            // Before that, 177. The six added were software composition management: the dependency dashboard
            // payload, the application/project/repository tree fetched a level at a time, the CVE and
            // component searches, and the two drill-downs behind them. All class A on
            // sbm.coverage.read — the permission the SBOM coverage page already carried.
            //
            // Before that, 171. The one added was the posture dashboard for one PROJECT. Same handler as the
            // application one — a project is an asset of the same aggregate and every rollup is
            // rooted at whatever asset it is asked about — but its own registry entry, so the two
            // can be authorized apart.
            //
            // Before that, 170. The one added was the application security posture dashboard — the aggregates behind
            // the charts on an application page. Class A on the same permission as the application it
            // describes: a rollup is not less sensitive than the rows it is a rollup of, and giving a
            // summary its own permission would let a deployment hand somebody the shape of an
            // application they cannot open.
            //
            // Before that, 169. The two added were recording a finding from the React interface: the option lists
            // the form needs and the write itself, both on vul.finding.triage — the same permission
            // the server-rendered form carries. It was the last write that still handed an assessor
            // off to /ui, and the one they perform most often.
            //
            // Before that, 167. The fifteen added were the write side of the React interface, which until now handed
            // every edit off to the server-rendered page: the application editor read and its two save
            // routes plus retirement, node create, rename and deprecate, and the role surface — a list,
            // one role, create, save, retire, restore and delete. Every one carries the SAME permission
            // and annotation class as the /ui route it replaces. An editor reachable from /app under a
            // weaker gate than the identical one at /ui would be a bypass, not a convenience.
            //
            // Before that, 152. The four added were assessor teams: the roster read, create, membership move,
            // and retire. Membership is exclusive by constraint so per-team charts can be summed.
            //
            // Before that, 148. The one added was the workload analytics view — the management series behind
            // the charts, on cap.team.read with the per-person series gated separately inside.
            //
            // Before that, 147. The seven added were object-level authority: reading and changing who owns a
            // project and who may raise requests for it, the delivery-side people on one request,
            // and a claimed fix. Each write declares the weakest permission that makes the request
            // sensible and defers the real decision to ObjectAuthority — see the note beside them in
            // PlatformOperations, because a composite authority is a deviation from ADR-036's one
            // permission per operation and is recorded rather than hidden.
            //
            // Before that, 140. The two added were intake: one page of a project's requests, and raising one.
            // The write is class B on asm.request.submit — the same permission the server-rendered
            // intake surface uses, so the new form cannot be a weaker way in.
            //
            // Before that, 138. The two added were the project inventory and one project — the level between an
            // application and the parts it is built from, carrying the application permission
            // because a project is an asset of the same aggregate (ADR-009).
            //
            // Before that, 136. The seven added were the account panel and the authorization administration
            // screens as JSON: the caller's own profile and a session revocation (class G, authorized
            // by identity), the access dashboard and one principal (class A on iam.user.read), the
            // grant and revoke (class E), and the credential reset (class C). The two write classes
            // carry their step-up requirement from the class, not from a test in the handler.
            //
            // Before that, 129. The three added were the last three server-rendered dashboards as JSON: the
            // overview, the workload and service-level page, and dependency coverage. Each carries the
            // permission of the page it mirrors — vul.finding.read, cap.team.read, sbm.coverage.read.
            //
            // Before that, 126. The two added were the deeper interface deep links —
            // /app/board/{id}/findings/{fid} is four segments, and the shell must answer it or a
            // bookmarked finding is a 404.
            //
            // Before that, 124. The ten added completed the React interface's data: assignment, comments on a
            // request and on a finding, one finding read and its save, the assignable-people list,
            // the application inventory and one application, and the organization tree. Each carries
            // the permission of the server-rendered page it mirrors.
            //
            // Before that, 114. The eight added were the React interface: four class-G routes serving the shell and
            // its hashed assets, three scoped reads for the session, the board and one request, and the
            // scoped write that applies a state transition. The data operations carry the same
            // permissions as the server-rendered pages they mirror.
            //
            // Before that, 106. The two added were inline images in a write-up: the upload against a request or a
            // finding, and the byte-serving read. Two rather than one because the write and the read
            // have different annotation classes and must be authorized separately.
            // 190, down from 220 when the interface moved to the root. Thirty server-rendered pages and
            // form posts were unregistered because a React page serves the same rows — the estate,
            // board, organization, people, roles, account and the five generic resource lists — and ten
            // redirects for the two retired prefixes were added. An exact number rather than a floor: it
            // is a count somebody has to look at when it changes, which is the point.
            //
            // 191: POST /api/v1/finding-imports, the scan-report door. Class F with its own permission,
            // not the SBOM one — see the note beside its registration.
            // 190: GET /components removed with the page it served.
            //
            // 193: the guide became a React page. The server-rendered GET /guide went (−1); the two
            // documents arrived as JSON (+2); and /guide and /api-guide joined the enumerated interface
            // routes, each of which registers a shell operation of its own (+2). Four moving parts for
            // one page, which is why the number is asserted rather than described.
            //
            // 195: the project record editor, read and write. Two operations for one screen because the
            // read composes the tenant's declared field catalogue and the write validates against it,
            // and they are authorized differently — A_SCOPED_READ on ast.asset.read, B_SCOPED_WRITE on
            // ast.asset.update.
            //
            // 196: /projects/{id}/edit joined WebUi.ROUTES, and every enumerated interface route
            // registers a class-G shell operation of its own. Three moving parts for one screen —
            // the read, the write, and the shell that serves it.
            //
            // 201: the declared-field catalogue — one read and four writes. Four writes rather than
            // one because declaring, amending, retiring and reordering are authorized as separate
            // operations, and a tenant that wants somebody able to reorder but not retire needs them
            // separable to do it.
            //
            // 203: the host reverse lookup — the read, plus the shell for the page that serves it.
            // Domains were assets nothing could search: no name filter on any list reached one,
            // because a host is on the far end of an edge rather than in a column.
            //
            // 208: the endpoint environment catalogue — one read and four writes, the same five
            // shapes as the field catalogue because it is the same kind of tenant vocabulary. No SPA
            // page of its own: it is a section of the existing settings screen, so there is no sixth
            // class-G shell operation here.
            assertEquals(208, registry.all().size(), "registered: " + registry.all().size());
        }

        @Test
        @DisplayName("PRD-API-019: every operation carries a class and, unless class G, a permission")
        void everyOperationIsAnnotated() {
            for (OperationRegistry.Operation operation : PlatformOperations.registry().all()) {
                assertTrue(operation.requiredPermission().isPresent()
                                || operation.annotationClass() == AnnotationClass.G_UNAUTHENTICATED,
                        operation.pathTemplate() + " names no permission and is not class G");
            }
        }

        @Test
        @DisplayName("a permission code is validated at STARTUP, not at the first request")
        void permissionCodesAreValidatedEagerly() {
            // org.node_type.read was rejected by PermissionId's catalogue shape — at request time, and the
            // 400 it produced told the caller which permission the operation requires. Constructing every
            // code in registry() moves the failure to startup.
            PlatformOperations.registry();
            assertThrows(IllegalArgumentException.class,
                    () -> aspm.kernel.authorization.contract.PermissionId.of("org.node_type.read"),
                    "the underscore form must still be rejected, or this assertion proves nothing");
        }

        @Test
        @DisplayName("a route without a registered operation cannot be dispatched")
        void anUnregisteredRouteIsRefused() {
            List<Dispatcher.Route> routes = new java.util.ArrayList<>(
                    PlatformOperations.registry().all().stream()
                            .map(operation -> new Dispatcher.Route(operation.method(),
                                    new PathTemplate(operation.pathTemplate()),
                                    request -> Dispatcher.Response.ok(Map.of())))
                            .toList());
            routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/v1/unregistered"),
                    request -> Dispatcher.Response.ok(Map.of())));
            var ex = assertThrows(IllegalArgumentException.class, () -> new Dispatcher(
                    PlatformOperations.registry(), routes, alwaysAuthenticated(Set.of(), false)));
            assertTrue(ex.getMessage().contains("no annotation class"),
                    "a handler with no registered operation would dispatch with no annotation class");
        }

        @Test
        @DisplayName("the REAL route table constructs — every route registered, every operation routed")
        void theRealRouteTableConstructs() {
            // AspmApplication.dispatcherFor is documented "exposed for tests, which build the same
            // dispatcher rather than a parallel one" — and no test called it. So the route table the
            // server actually serves was validated against the registry only at startup, and a route
            // added without its operation (or the reverse) showed up as a container that would not boot.
            //
            // Every other test in this class builds routes FROM the registry, which makes the parity check
            // trivially true and asserts nothing about the hand-written table in routesFor.
            //
            // The data source throws on getConnection: this asserts construction and route parity, not
            // behaviour, and a page class that opened a connection in its constructor would fail here —
            // which is itself worth catching, because that would make startup depend on the store being
            // up rather than readiness reporting that it is not (OPS-DEP-008).
            javax.sql.DataSource refusing = new javax.sql.DataSource() {
                @Override
                public java.sql.Connection getConnection() throws java.sql.SQLException {
                    throw new java.sql.SQLException("no connection is needed to construct the dispatcher");
                }

                @Override
                public java.sql.Connection getConnection(String username, String password)
                        throws java.sql.SQLException {
                    return getConnection();
                }

                @Override
                public java.io.PrintWriter getLogWriter() {
                    return null;
                }

                @Override
                public void setLogWriter(java.io.PrintWriter out) {
                }

                @Override
                public void setLoginTimeout(int seconds) {
                }

                @Override
                public int getLoginTimeout() {
                    return 0;
                }

                @Override
                public java.util.logging.Logger getParentLogger() {
                    return java.util.logging.Logger.getGlobal();
                }

                @Override
                public <T> T unwrap(Class<T> iface) throws java.sql.SQLException {
                    throw new java.sql.SQLException("not a wrapper");
                }

                @Override
                public boolean isWrapperFor(Class<?> iface) {
                    return false;
                }
            };

            Dispatcher dispatcher = AspmApplication.dispatcherFor(refusing,
                    alwaysAuthenticated(Set.of(), false));
            assertTrue(dispatcher != null, "the real dispatcher constructed");
        }

        @Test
        @DisplayName("a registered operation without a handler is refused too")
        void anUnroutedOperationIsRefused() {
            var ex = assertThrows(IllegalArgumentException.class, () -> new Dispatcher(
                    PlatformOperations.registry(), List.of(), alwaysAuthenticated(Set.of(), false)));
            assertTrue(ex.getMessage().contains("no handler"),
                    "a registered operation that cannot be reached is a claim in the catalogue that "
                            + "nothing backs");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-API-036 — a denial is indistinguishable from non-existence")
    class Absence {

        @Test
        @DisplayName("an unregistered path returns the denial body, byte for byte")
        void anUnregisteredPathLooksLikeADenial() {
            Dispatcher dispatcher = dispatcherWith(alwaysAuthenticated(Set.of(), false));
            Dispatcher.Response response = dispatcher.dispatch(
                    "GET", "/api/v1/secrets", Map.of(), Map.of(), Optional.empty());

            assertEquals(404, response.status());
            assertEquals(Json.write(Dispatcher.Response.notFound().body()), Json.write(response.body()),
                    "an unrouted path must be indistinguishable from a scope denial, or the API surface "
                            + "can be mapped without authorization");
        }

        @Test
        @DisplayName("the denial body serializes; it used to throw and become a 500")
        void theDenialBodySerializes() {
            // Response.notFound() carried DenialResponse.Body, a record. Json.write rejects a type it does
            // not know, so it threw, was caught by the catch-all, and became a 500 WITH A CORRELATION
            // IDENTIFIER — trivially distinguishable from a 404. The rule was stated in three places and
            // held in none of them until this was run.
            String body = Json.write(Dispatcher.Response.notFound().body());
            assertTrue(body.contains("\"status\":404"), body);
            assertFalse(body.contains("correlation"),
                    "a correlation identifier in a denial body makes it distinguishable from a 404");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("The dispatch order, which is load-bearing")
    class Order {

        @Test
        @DisplayName("no credential stops before the handler")
        void unauthenticatedIsRejected() {
            Dispatcher dispatcher = dispatcherWith(new PrincipalResolver() {
                @Override
                public Optional<Principal> resolve(Map<String, String> headers) {
                    return Optional.empty();
                }

                @Override
                public String description() {
                    return "none";
                }
            });
            Dispatcher.Response response = dispatcher.dispatch(
                    "GET", ORG_NODE_TYPES, Map.of(), Map.of(), Optional.empty());
            assertEquals(401, response.status());
        }

        @Test
        @DisplayName("a failed authentication is indistinguishable from an absent one")
        void aFailedCredentialLooksLikeNone() {
            Dispatcher failing = dispatcherWith(new PrincipalResolver() {
                @Override
                public Optional<Principal> resolve(Map<String, String> headers) {
                    throw new SecurityException("signature mismatch");
                }

                @Override
                public String description() {
                    return "failing";
                }
            });
            Dispatcher.Response response = failing.dispatch(
                    "GET", ORG_NODE_TYPES, Map.of(), Map.of(), Optional.empty());
            assertEquals(401, response.status());
            assertFalse(Json.write(response.body()).contains("signature"),
                    "distinguishing a bad credential from no credential tells a caller which credentials "
                            + "exist");
        }

        @Test
        @DisplayName("class E requires step-up before the body is read")
        void stepUpIsRequiredForConfiguration() {
            Dispatcher dispatcher = dispatcherWith(
                    alwaysAuthenticated(Set.of(ResourceCatalogue.ORG_NODE_TYPES.createPermission()), false));
            Dispatcher.Response response = dispatcher.dispatch("POST",
                    ORG_NODE_TYPES, Map.of(),
                    Map.of("idempotency-key", "k"), Optional.of("{\"code\":\"X\"}"));
            assertEquals(401, response.status());
            assertEquals("STEP_UP_REQUIRED", ((Map<?, ?>) response.body()).get("code"));
        }

        @Test
        @DisplayName("class E requires an idempotency key")
        void idempotencyKeyIsRequired() {
            Dispatcher dispatcher = dispatcherWith(
                    alwaysAuthenticated(Set.of(ResourceCatalogue.ORG_NODE_TYPES.createPermission()), true));
            Dispatcher.Response response = dispatcher.dispatch("POST",
                    ORG_NODE_TYPES, Map.of(), Map.of(),
                    Optional.of("{\"code\":\"X\"}"));
            assertEquals(400, response.status());
            assertEquals("IDEMPOTENCY_KEY_REQUIRED", ((Map<?, ?>) response.body()).get("code"));
        }

        @Test
        @DisplayName("a UI form POST needing step-up is REDIRECTED, not handed a JSON 401")
        void uiFormPostRedirectsToTheChallenge() {
            // Reported by the user: creating a role answered
            //   {"code":"STEP_UP_REQUIRED","message":"step-up authentication required","status":401}
            // in the browser. The redirect branch was GET-only, so every class E form POST fell through
            // to the JSON body — which a person cannot act on and a form cannot recover from.
            //
            // Same shape as the missing idempotency field: a route verified through the API client, where
            // a 401 is a fine answer, and never through the browser that actually posts it.
            // Was POST /roles, which is a React page posting JSON now. The credential policy form is
            // the class E surface this tier still renders and posts itself.
            Dispatcher.Response response = dispatcherWith(
                    alwaysAuthenticated(Set.of(aspm.app.ui.AdminPages.MANAGE_USERS), false))
                    .dispatch("POST", "/security-policy", Map.of(),
                            Map.of("content-type", "application/x-www-form-urlencoded"),
                            Optional.of(Dispatcher.IDEMPOTENCY_FIELD + "=k&minimumLength=12"));
            assertEquals(303, response.status());
            assertEquals("/step-up?next=%2Fsecurity-policy", response.headers().get("Location"));
        }

        @Test
        @DisplayName("the return path walks up to a page that exists, never to the POST target")
        void theReturnPathIsAPageThatCanBeOpened() {
            // A nested form POST with no GET of its own: sending a caller back there after they elevate
            // would answer 404, and a challenge that succeeds and lands on an error reads as a broken
            // platform. The dispatcher walks up until the registry has a GET.
            //
            // *** ASSERTED OVER A PURPOSE-BUILT REGISTRY, AND THAT IS THE CHANGE. *** This used to post to
            // /ui/users/{id}/roles/revoke, a real route. Every nested form post like it is now a React
            // page sending JSON, so after the move to the root there is no live instance of this shape
            // left — and a test whose subject disappeared would have been deleted, taking the mechanism's
            // only coverage with it. The walk is still needed the moment a nested form post is added
            // back, so it is pinned here against two operations built for the purpose.
            OperationRegistry registry = OperationRegistry.of(List.of(
                    new OperationRegistry.Operation("GET", "/thing/{id}",
                            AnnotationClass.A_SCOPED_READ,
                            Optional.of(aspm.app.ui.AdminPages.MANAGE_USERS), Set.of(), Set.of()),
                    new OperationRegistry.Operation("POST", "/thing/{id}/act",
                            AnnotationClass.E_CONFIGURATION,
                            Optional.of(aspm.app.ui.AdminPages.MANAGE_USERS), Set.of(), Set.of())));
            Dispatcher.Handler unreached = request -> {
                throw new AssertionError("the step-up gate did not stop the request");
            };
            Dispatcher dispatcher = new Dispatcher(registry, registry.all().stream()
                    .map(operation -> new Dispatcher.Route(operation.method(),
                            new PathTemplate(operation.pathTemplate()), unreached))
                    .toList(), alwaysAuthenticated(Set.of(aspm.app.ui.AdminPages.MANAGE_USERS), false));

            String id = UUID.randomUUID().toString();
            Dispatcher.Response response = dispatcher.dispatch("POST", "/thing/" + id + "/act",
                    Map.of(), Map.of("content-type", "application/x-www-form-urlencoded"),
                    Optional.of(Dispatcher.IDEMPOTENCY_FIELD + "=k"));
            assertEquals(303, response.status());
            assertEquals("/step-up?next=" + java.net.URLEncoder.encode("/thing/" + id,
                            java.nio.charset.StandardCharsets.UTF_8),
                    response.headers().get("Location"));
        }

        @Test
        @DisplayName("an API caller still gets the JSON 401 — a pipeline cannot follow a form")
        void apiKeepsTheJsonRefusal() {
            Dispatcher.Response response = dispatcherWith(
                    alwaysAuthenticated(Set.of(ResourceCatalogue.ORG_NODE_TYPES.createPermission()),
                            false))
                    .dispatch("POST", ORG_NODE_TYPES, Map.of(),
                            Map.of("idempotency-key", "k"), Optional.of("{\"code\":\"X\"}"));
            assertEquals(401, response.status());
            assertEquals("STEP_UP_REQUIRED", ((Map<?, ?>) response.body()).get("code"));
        }

        @Test
        @DisplayName("a stepped-up caller with a key REACHES the handler — the gate has a key")
        void stepUpAdmitsWhenSatisfied() {
            // The assertion this class was missing, and the omission was not cosmetic.
            //
            // Every other step-up test asserts a caller WITHOUT step-up is refused. Nothing asserted that
            // a caller WITH it is admitted — and nothing could, because Principal.stepUpAuthenticated was
            // a literal `false` at every production construction site until V016. So the gate was closed
            // and had no key: every class C and class E operation answered 401 to every human caller,
            // including the org-node-type and asset-type writes that had been registered for weeks.
            //
            // A gate nothing can pass is indistinguishable, from the refusal side, from a gate nothing
            // needs. This test is the other side.
            assertThrows(AssertionError.class, () -> dispatcherWith(
                    alwaysAuthenticated(Set.of(ResourceCatalogue.ORG_NODE_TYPES.createPermission()), true))
                    .dispatch("POST", ORG_NODE_TYPES, Map.of(),
                            Map.of("idempotency-key", "k"), Optional.of("{\"code\":\"X\"}")),
                    "a stepped-up caller holding the permission and a key must reach the handler. The "
                            + "test harness's handler throws AssertionError when reached, so reaching it "
                            + "is the success condition here.");
        }

        @Test
        @DisplayName("a form post carries its idempotency key in a hidden field, not a header")
        void formPostSuppliesTheKeyInTheBody() {
            // An HTML form cannot set a request header. Class B and class E require replay protection, so
            // before the body was consulted every form posting to one of those routes answered 400 —
            // including the transition buttons on the request detail page, which is the entire workflow
            // surface. It was missed because the transition endpoint was verified through the API path,
            // where sending a header is trivial.
            assertThrows(AssertionError.class, () -> dispatcherWith(
                    alwaysAuthenticated(Set.of(ResourceCatalogue.ORG_NODE_TYPES.createPermission()), true))
                    .dispatch("POST", ORG_NODE_TYPES, Map.of(),
                            Map.of("content-type", "application/x-www-form-urlencoded"),
                            Optional.of(Dispatcher.IDEMPOTENCY_FIELD + "=abc-123&code=X")),
                    "a form-encoded post carrying " + Dispatcher.IDEMPOTENCY_FIELD + " must satisfy the "
                            + "replay check and reach the handler");
        }

        @Test
        @DisplayName("the key is read from the body ONLY for a form post, not for JSON")
        void jsonPostStillNeedsTheHeader() {
            // The body field is a concession to HTML forms, not a general alternative. A JSON client can
            // set a header, and admitting a body field there would let a retried request supply a fresh
            // key inside a payload the server also parses — replay protection the payload controls.
            Dispatcher.Response response = dispatcherWith(
                    alwaysAuthenticated(Set.of(ResourceCatalogue.ORG_NODE_TYPES.createPermission()), true))
                    .dispatch("POST", ORG_NODE_TYPES, Map.of(), Map.of(),
                            Optional.of("{\"" + Dispatcher.IDEMPOTENCY_FIELD + "\":\"abc\",\"code\":\"X\"}"));
            assertEquals(400, response.status());
            assertEquals("IDEMPOTENCY_KEY_REQUIRED", ((Map<?, ?>) response.body()).get("code"));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("The declared permission is enforced on EVERY route, not only where a handler remembers")
    class DeclaredPermissionIsEnforced {

        @Test
        @DisplayName("every non-G operation refuses a principal holding no permission")
        void everyOperationRefusesAnUnpermissionedPrincipal() {
            // The assertion that would have caught it. Written over the whole registry rather than over a
            // list of routes, because a list is what gets extended one route short.
            //
            // Before the dispatcher consulted operation.requiredPermission(), authorization lived in
            // individual handlers. ResourceEndpoint and RequestTransition checked; every page class that
            // did not was reachable by any authenticated principal — the user administration list and the
            // permission matrix among them. The declared permission was decorative, and the registry, the
            // manifest and the startup banner all reported it, so every artifact a reviewer would consult
            // said the gate was there.
            Dispatcher dispatcher = dispatcherWith(alwaysAuthenticated(Set.of(), true));
            for (OperationRegistry.Operation operation : PlatformOperations.registry().all()) {
                if (operation.requiredPermission().isEmpty()) {
                    continue;
                }
                String path = operation.pathTemplate().replace("{id}", UUID.randomUUID().toString());
                Dispatcher.Response response = dispatcher.dispatch(operation.method(), path, Map.of(),
                        Map.of("idempotency-key", "k",
                                "content-type", "application/x-www-form-urlencoded"),
                        Optional.of(""));
                assertEquals(404, response.status(),
                        operation.method() + " " + operation.pathTemplate() + " declares "
                                + operation.requiredPermission().orElseThrow() + " and answered "
                                + response.status() + " to a principal holding nothing. The handler was "
                                + "reached, which means the declared permission gates nothing on this "
                                + "route — broken object-level authorization, the first of the five "
                                + "highest-risk surfaces.");
            }
        }

        @Test
        @DisplayName("the refusal is 404, so the permission model cannot be mapped by probing")
        void refusalIsIndistinguishableFromAbsence() {
            Dispatcher dispatcher = dispatcherWith(alwaysAuthenticated(Set.of(), true));
            // Was /roles until that became a React route served by a class G shell — a page with no
            // permission of its own cannot demonstrate a refusal. Then /components, until that page was
            // removed. /security-policy is what is left: server-rendered, and gated on MANAGE_USERS.
            // Any substitute has to be BOTH, and the list of pages that are is now four long — noted
            // because the next removal narrows it again.
            Dispatcher.Response denied = dispatcher.dispatch("GET", "/security-policy", Map.of(),
                    Map.of(), Optional.empty());
            Dispatcher.Response absent = dispatcher.dispatch("GET", "/no-such-page", Map.of(),
                    Map.of(), Optional.empty());
            assertEquals(absent.status(), denied.status(),
                    "PRD-API-036: a denial and an unrouted path must be indistinguishable, or the "
                            + "difference is a map of the permission model. This is also why the "
                            + "navigation is filtered by permission rather than shown and refused — a "
                            + "link that 404s tells the caller the page exists and they are not allowed.");
        }

        @Test
        @DisplayName("holding the permission gets past the gate")
        void holdingThePermissionAdmits() {
            // The other side, for the same reason A21's step-up assertion needed one: a gate nothing can
            // pass is indistinguishable, from the refusal side, from a gate nothing needs.
            assertThrows(AssertionError.class, () -> dispatcherWith(
                    alwaysAuthenticated(Set.of(aspm.app.ui.AdminPages.MANAGE_USERS), true))
                    .dispatch("GET", "/security-policy", Map.of(), Map.of(), Optional.empty()),
                    "a principal holding the page's permission must reach the handler; the harness "
                            + "handler throws AssertionError when reached, so reaching it is success");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("An immutable map rejects a null key, and the interface looks up nullable ones")
    class NullKeyLookup {

        @Test
        @DisplayName("Map.getOrDefault(null, …) THROWS on an immutable map — it does not return the default")
        void immutableMapRejectsNullKeys() {
            // The behaviour behind a live 500. Every assessment request with no assessor yet has a
            // null principal identifier, and the request detail page looked the name up with
            // getOrDefault — which returns the default on a HashMap and throws on Map.of/copyOf.
            //
            // Asserted here rather than only fixed at the call site, because the fix is a habit and
            // the trap is in the JDK: the method reads as null-tolerant and is not. Every page that
            // resolves a nullable identifier to a display name has this shape.
            Map<UUID, String> immutable = Map.of(PRINCIPAL, "someone");
            assertThrows(NullPointerException.class, () -> immutable.getOrDefault(null, "—"),
                    "if this ever stops throwing, the null-safe helpers guarding it can be removed");

            Map<UUID, String> mutable = new java.util.HashMap<>();
            assertEquals("—", mutable.getOrDefault(null, "—"),
                    "and a mutable map does NOT throw, which is why the bug survived every unit test "
                            + "that built its fixture with a HashMap");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("A credential marked for replacement blocks every other route")
    class ForcedCredentialChange {

        /** A resolver whose principal must change its credential before doing anything else. */
        private PrincipalResolver mustChange() {
            return new PrincipalResolver() {
                @Override
                public Optional<Principal> resolve(Map<String, String> headers) {
                    return Optional.of(new Principal(TENANT, PRINCIPAL,
                            Set.of(ResourceCatalogue.ORG_NODE_TYPES.readPermission()), Set.of(),
                            true, false, true));
                }

                @Override
                public String description() {
                    return "test, credential change required";
                }
            };
        }

        @Test
        @DisplayName("an interface route redirects to the change surface")
        void interfaceRedirects() {
            // must_change_password was SET by three paths — the deployment bootstrap, an administrative
            // reset, and a credential found in the breach corpus at sign-in — and READ by nothing. A flag
            // no code enforces is a claim in a column.
            Dispatcher.Response response = dispatcherWith(mustChange())
                    .dispatch("GET", "/security-policy", Map.of(), Map.of(), Optional.empty());
            assertEquals(303, response.status());
            assertEquals("/change-password?required=1", response.headers().get("Location"));
        }

        @Test
        @DisplayName("an API route answers 403, naming the remedy rather than asking for credentials again")
        void apiRefuses() {
            // 403 and not 401: the caller IS authenticated, and a 401 sends a client to re-authenticate,
            // which succeeds and changes nothing.
            Dispatcher.Response response = dispatcherWith(mustChange())
                    .dispatch("GET", ORG_NODE_TYPES, Map.of(), Map.of(), Optional.empty());
            assertEquals(403, response.status());
            assertEquals("CREDENTIAL_CHANGE_REQUIRED", ((Map<?, ?>) response.body()).get("code"));
        }

        @Test
        @DisplayName("the change surface and sign-out stay reachable, or it is a locked door with no key")
        void theExitsStayOpen() {
            Dispatcher dispatcher = dispatcherWith(mustChange());
            for (String path : List.of("/change-password", "/sign-out")) {
                assertThrows(AssertionError.class,
                        () -> dispatcher.dispatch("GET", path, Map.of(), Map.of(), Optional.empty()),
                        path + " must remain reachable while a credential change is required. An "
                                + "enforcement that admits neither the change page nor the exit is a "
                                + "lockout with no recovery, on the surface that exists to recover.");
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Development authentication is contained by construction")
    class DevAuth {

        @Test
        @DisplayName("absent configuration yields no resolver, and a server with none refuses to start")
        void disabledByDefault() {
            assertTrue(DevPrincipalResolver.enabledFrom(Map.of()).isEmpty());
            assertTrue(DevPrincipalResolver.enabledFrom(
                    Map.of(DevPrincipalResolver.ENABLE_VARIABLE, "false")).isEmpty());
        }

        @Test
        @DisplayName("enabling it outside development refuses to construct")
        void refusesOutsideDevelopment() {
            var ex = assertThrows(IllegalStateException.class, () -> DevPrincipalResolver.enabledFrom(
                    Map.of(DevPrincipalResolver.ENABLE_VARIABLE, "true",
                            DevPrincipalResolver.ENVIRONMENT_VARIABLE, "production")));
            assertTrue(ex.getMessage().contains("SEC-TEN-004"),
                    "a flag that can be turned on in production is a flag that will be; the refusal has "
                            + "to be here rather than in a deployment checklist");
        }

        @Test
        @DisplayName("it says what it is, so an operator reading a log sees it")
        void announcesItself() {
            PrincipalResolver resolver = DevPrincipalResolver.enabledFrom(
                    Map.of(DevPrincipalResolver.ENABLE_VARIABLE, "true",
                            DevPrincipalResolver.ENVIRONMENT_VARIABLE, "development")).orElseThrow();
            assertTrue(resolver.description().contains("NOT ADR-004 compliant"));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Json — the parser rejects rather than coerces")
    class JsonCodec {

        @Test
        @DisplayName("a duplicate field is rejected, not last-one-wins")
        void duplicateFieldsRejected() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> Json.readObject("{\"a\":1,\"a\":2}"));
            assertTrue(ex.getMessage().contains("duplicate"),
                    "last-one-wins is a request smuggling primitive whenever two components parse the same "
                            + "body: the validator sees one value and the handler another");
        }

        @Test
        @DisplayName("a type the writer does not know fails instead of becoming its toString()")
        void unknownTypesFail() {
            var ex = assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
            assertTrue(ex.getMessage().contains("toString()"),
                    "letting toString() decide what a client sees is how an internal class name reaches a "
                            + "response body");
        }

        @Test
        @DisplayName("line separators are escaped, because a finding title is attacker-authored")
        void lineSeparatorsAreEscaped() {
            String written = Json.write(Map.of("title", "a b"));
            assertTrue(written.contains("\\u2028"),
                    "U+2028 is valid in JSON and terminates a line in JavaScript, so an ingested finding "
                            + "title becomes a script break in any consumer that evaluates the response");
        }

        @Test
        @DisplayName("nesting beyond the limit is rejected")
        void deepNestingRejected() {
            String deep = "{\"a\":".repeat(40) + "1" + "}".repeat(40);
            assertThrows(IllegalArgumentException.class, () -> Json.readObject(deep));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PathTemplate")
    class Templates {

        @Test
        @DisplayName("an empty segment does not match a variable")
        void emptySegmentDoesNotMatch() {
            assertTrue(new PathTemplate("/a/{b}/c").match("/a//c").isEmpty(),
                    "a caller supplying no identifier must get a routing failure, not a lookup for the "
                            + "empty string");
        }

        @Test
        @DisplayName("a variable is extracted and a literal must match")
        void matching() {
            assertEquals(Map.of("id", "42"),
                    new PathTemplate("/api/v1/x/{id}").match("/api/v1/x/42").orElseThrow());
            assertTrue(new PathTemplate("/api/v1/x/{id}").match("/api/v2/x/42").isEmpty());
            assertTrue(new PathTemplate("/api/v1/x/{id}").match("/api/v1/x/42/extra").isEmpty());
        }
    }
}
