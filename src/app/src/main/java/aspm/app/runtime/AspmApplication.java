package aspm.app.runtime;

import aspm.app.api.OperationRegistry;
import aspm.app.api.PlatformOperations;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The composition root. The one place the modular monolith of ADR-003 is assembled.
 *
 * <p>ADR-003 keeps modules isolated at compile time and {@code :app} is the only subproject permitted to
 * depend on an {@code -impl}. That permission exists for exactly this file: something has to know every
 * module's wiring, and confining it here is what stops the knowledge spreading into the modules themselves.
 *
 * <h2>It refuses to start rather than starting degraded</h2>
 *
 * <p>Product principle 9: fail loudly, degrade explicitly. A server with no principal resolver would have
 * to treat every request as anonymous, which is deny-by-default inverted; a server that cannot reach the
 * operational store would answer readiness with a 503 forever. The first is refused at startup because it
 * is a configuration error, and the second is reported through readiness because it is an outage
 * ({@code OPS-DEP-008}).
 */
public final class AspmApplication {

    private AspmApplication() {
    }

    public static void main(String[] args) throws Exception {
        // Deployment secrets may arrive as mounted files named by ASPM_*_REF variables (OPS-DEP-019,
        // OPS-DEP-020): resolved once here into an in-memory copy, so no consumer below needs to know.
        Map<String, String> environment = aspm.app.secrets.Secrets.expandDeploymentReferences(System.getenv());
        aspm.app.resource.ObjectStore.bindDeploymentEnvironment(environment);
        // Self-hosted model endpoints the operator vouches for (ADR-075): the one way a private
        // address may be a model provider.
        aspm.app.ai.ModelEndpoints.bind(environment);

        DataSource dataSource = dataSource(environment);

        // ADR-004 requires OIDC for humans and sender-constrained credentials for services. Neither is
        // implemented. DevPrincipalResolver is the only resolver available and it refuses to construct
        // outside a development environment, so a production deployment gets no resolver and this throws.
        // ADR-059. The session resolver is the real one; the development header resolver remains only
        // as an explicit opt-in for a development environment, and refuses to construct outside one.
        java.util.UUID tenantId = java.util.UUID.fromString(environment.getOrDefault(
                "ASPM_TENANT_ID", "11111111-1111-1111-1111-111111111111"));
        boolean secureCookies = !"development"
                .equalsIgnoreCase(environment.getOrDefault("ASPM_ENVIRONMENT", ""));
        var authPages = new aspm.app.ui.AuthPages(dataSource, tenantId, secureCookies);

        String bootstrapPassword = environment.get(
                aspm.app.identity.CredentialBootstrap.PASSWORD_VARIABLE);
        if (bootstrapPassword != null && !bootstrapPassword.isBlank()) {
            var touched = aspm.app.identity.CredentialBootstrap.run(dataSource, tenantId,
                    bootstrapPassword);
            System.getLogger("aspm").log(System.Logger.Level.WARNING,
                    "credential bootstrap set an initial password for " + touched
                            + "; each must change it at first sign-in");
        }

        // The secrets store (ADR-052) and the federation wiring (ADR-004, V074). The egress guard is the
        // single enforcement point for every destination a tenant configures (TST-AUZ-001).
        aspm.app.secrets.Secrets secrets = aspm.app.secrets.Secrets.fromEnvironment(environment, dataSource);
        aspm.app.egress.EgressGuard egress = aspm.app.egress.EgressGuard.production();
        var oidc = new aspm.app.identity.federation.OidcClient(egress);
        var providerService = new aspm.app.identity.federation.IdentityProviderService(
                dataSource, secrets, oidc, egress);
        authPages.withProviders(providerService);
        // The public base URL is what the redirect URI is built from and is registered at every
        // provider. It is configuration, never the Host header: a redirect URI derived from a request
        // is a redirect an attacker chooses. Absent means federation is configured-but-disabled, and the
        // administration page says so rather than offering a sign-in button that cannot work.
        String publicBaseUrl = environment.getOrDefault("ASPM_PUBLIC_BASE_URL", "").strip();
        if (!publicBaseUrl.isEmpty() && !publicBaseUrl.startsWith("https://")
                && !"development".equalsIgnoreCase(environment.getOrDefault("ASPM_ENVIRONMENT", ""))) {
            throw new IllegalStateException("ASPM_PUBLIC_BASE_URL must be https outside a development environment: "
                    + "the OIDC redirect URI carries the authorization code, and http would carry it in the clear");
        }
        aspm.app.identity.FederatedSignIn federatedSignIn = publicBaseUrl.isEmpty() ? null
                : new aspm.app.identity.FederatedSignIn(dataSource, tenantId, secrets, oidc, providerService, publicBaseUrl);
        Federation federation = new Federation(providerService, federatedSignIn);

        // Notification delivery (V075, ADR-054). The senders a deployment ships; the worker that drains
        // the outbox runs here only when ASPM_ROLE is `all` (compose) or `worker` (the general-workers
        // unit of DOC-15 §4); the application tier alone (`app`) enqueues and never sends.
        var senders = aspm.app.notification.DeliveryWorker.defaultSenders(egress, environment);
        var channelService = new aspm.app.notification.NotificationChannelService(dataSource, secrets, senders);
        // Named runtimeUnit, not "role": it is the DOC-15 §4 runtime unit this process plays, and the
        // SEC-AUZ-050 build gate rightly refuses to see anything called a role compared to a literal.
        String runtimeUnit = environment.getOrDefault("ASPM_ROLE", "all").strip().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.List.of("all", "app", "worker").contains(runtimeUnit)) {
            throw new IllegalStateException("ASPM_ROLE must be all, app or worker");
        }
        aspm.app.notification.DeliveryWorker worker = "app".equals(runtimeUnit) ? null
                : new aspm.app.notification.DeliveryWorker(dataSource, tenantId, secrets, senders);

        // Outbound connectors (V076, DOC-21): Jira, GitLab, ServiceNow and a signed webhook as options.
        // Kinds may be disabled per deployment (ASPM_CONNECTOR_KINDS) for an air-gapped estate; the
        // worker that drains the connector outbox follows the same unit rule as notification delivery.
        var adapters = aspm.app.integration.TrackerAdapters.all(egress);
        var enabledKinds = aspm.app.integration.TrackerAdapters.enabledKinds(environment);
        var connectorService = new aspm.app.integration.ConnectorService(dataSource, secrets, adapters, enabledKinds);
        var referenceService = new aspm.app.integration.OutboundReferenceService(dataSource);
        // Scheduled reports (V077, DOC-12 §11): rendered per recipient by the worker unit; files in the
        // export bucket; the audit evidence export on demand through the same renderer.
        var reportService = new aspm.app.reporting.ReportService(dataSource, new aspm.app.resource.ObjectStore(environment),
                new aspm.app.reporting.ReportRenderer(dataSource, new aspm.app.resource.VulnerabilityQuery(dataSource)));
        aspm.app.reporting.ReportWorker reportWorker = "app".equals(runtimeUnit) ? null
                : new aspm.app.reporting.ReportWorker(dataSource, tenantId, reportService);
        aspm.app.integration.ConnectorWorker connectorWorker = "app".equals(runtimeUnit) ? null
                : new aspm.app.integration.ConnectorWorker(dataSource, tenantId, secrets, adapters, enabledKinds,
                        publicBaseUrl.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(publicBaseUrl),
                        Integer.parseInt(environment.getOrDefault("ASPM_CONNECTOR_EXPIRY_WARNING_DAYS", "14")));

        PrincipalResolver principals = "true".equalsIgnoreCase(
                environment.getOrDefault(DevPrincipalResolver.ENABLE_VARIABLE, ""))
                ? DevPrincipalResolver.enabledFrom(environment).orElseThrow(
                () -> new IllegalStateException(
                        "no principal resolver is configured, so every request would be anonymous. "
                                + "ADR-004 requires OIDC/OAuth2 for humans and sender-constrained "
                                + "credentials for services; neither is implemented yet. For local "
                                + "development set ASPM_DEV_AUTH=true and ASPM_ENVIRONMENT=development, "
                                + "and read what DevPrincipalResolver says about what that gives up."))
                : chain(new aspm.app.identity.ServiceCredentialResolver(dataSource, tenantId),
                        authPages.resolver());

        // Set before any page renders. Page defaults it to false, so a deployment that forgets gets no
        // banner rather than a permanent one.
        aspm.app.ui.Page.developmentAuthentication("true".equalsIgnoreCase(
                environment.getOrDefault(DevPrincipalResolver.ENABLE_VARIABLE, "")));

        OperationRegistry registry = PlatformOperations.registry();
        Dispatcher dispatcher = new Dispatcher(registry, routesFor(dataSource, authPages, federation, channelService, connectorService, referenceService, reportService), principals);

        int port = Integer.parseInt(environment.getOrDefault("ASPM_PORT", "8080"));
        HttpRuntime runtime = new HttpRuntime(port, dispatcher, readiness(dataSource));
        runtime.start();

        banner(runtime.port(), registry, principals);
        System.Logger log = System.getLogger("aspm");
        log.log(System.Logger.Level.INFO, "SECRETS (ADR-052): providers in resolution order, * marks the writer:");
        for (String line : secrets.describe()) {
            log.log(System.Logger.Level.INFO, "   " + line);
        }
        log.log(System.Logger.Level.INFO, "EGRESS: " + egress.description());
        log.log(System.Logger.Level.INFO, "AI (ADR-075): provider kinds " + aspm.app.ai.ModelClient.KINDS.stream().map(aspm.app.ai.ModelClient.Kind::code).toList()
                + "; vouched self-hosted endpoints " + (aspm.app.ai.ModelEndpoints.vouched().isEmpty() ? "none (set " + aspm.app.ai.ModelEndpoints.VARIABLE + ")"
                        : aspm.app.ai.ModelEndpoints.vouched()));
        log.log(System.Logger.Level.INFO, federatedSignIn == null
                ? "FEDERATION (ADR-004): not active — set ASPM_PUBLIC_BASE_URL to enable OIDC sign-in; providers may "
                        + "be configured meanwhile"
                : "FEDERATION (ADR-004): OIDC sign-in active; redirect URI " + federatedSignIn.redirectUri());
        if (worker != null) {
            worker.start();
            log.log(System.Logger.Level.INFO, "NOTIFICATION DELIVERY (ADR-054): worker running in this process (ASPM_ROLE=" + runtimeUnit
                    + "); channel kinds " + channelService.kinds());
            connectorWorker.start();
            log.log(System.Logger.Level.INFO, "CONNECTORS (DOC-21): worker running in this process; kinds enabled "
                    + enabledKinds.stream().sorted().toList());
            reportWorker.start();
            log.log(System.Logger.Level.INFO, "REPORTS (DOC-12 §11): scheduler running in this process; export bucket "
                    + (reportService.storageConfigured() ? "configured" : "NOT configured — scheduled reports will fail and say so"));
        } else {
            log.log(System.Logger.Level.INFO, "NOTIFICATION DELIVERY (ADR-054): this unit enqueues only (ASPM_ROLE=app); a worker "
                    + "unit must run to deliver");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (worker != null) {
                worker.close();
            }
            if (connectorWorker != null) {
                connectorWorker.close();
            }
            if (reportWorker != null) {
                reportWorker.close();
            }
            runtime.close();
        }, "aspm-shutdown"));
    }

    /**
     * A signed service request first, a browser session second.
     *
     * <p>Order matters and it is this way round because the two are disjoint: only a signed request
     * carries an {@code Authorization: ASPM-HMAC-SHA256} header, and only a browser carries the
     * session cookie. Trying the service credential first means a pipeline never pays for a session
     * lookup, and a browser never touches the nonce table.
     */
    private static PrincipalResolver chain(PrincipalResolver first, PrincipalResolver second) {
        return new PrincipalResolver() {
            @Override
            public java.util.Optional<Principal> resolve(Map<String, String> headers) {
                java.util.Optional<Principal> resolved = first.resolve(headers);
                return resolved.isPresent() ? resolved : second.resolve(headers);
            }

            @Override
            public String description() {
                return first.description() + " || " + second.description();
            }
        };
    }

    /**
     * {@code OPS-DEP-008}: readiness fails on an unavailable dependency; liveness does not. Conflating them
     * causes a restart loop during a dependency outage, which turns a degraded state into an outage.
     */
    private static HttpRuntime.ReadinessProbe readiness(DataSource dataSource) {
        return () -> {
            try (Connection connection = dataSource.getConnection()) {
                return new HttpRuntime.ReadinessProbe.Result(connection.isValid(2),
                        connection.isValid(2) ? "operational store reachable"
                                : "operational store did not answer");
            } catch (SQLException e) {
                // PRD-UIX-025: no driver message, no host, no JDBC URL. Readiness is an unauthenticated
                // endpoint, so its body is the most exposed error surface in the platform.
                return new HttpRuntime.ReadinessProbe.Result(false, "operational store unreachable");
            }
        };
    }

    private static DataSource dataSource(Map<String, String> environment) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(require(environment, "ASPM_DB_URL"));
        source.setUser(require(environment, "ASPM_DB_USER"));
        source.setPassword(require(environment, "ASPM_DB_PASSWORD"));
        // The application tier holds app_runtime, which does NOT bypass row-level security. OPS-DEP-009
        // makes that structural: there is no credential here that could.
        return source;
    }

    private static String require(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required. Starting without it would mean starting "
                    + "with a default, and a default database credential is a credential in the image.");
        }
        return value;
    }

    private static void banner(int port, OperationRegistry registry, PrincipalResolver principals) {
        System.Logger log = System.getLogger("aspm");
        log.log(System.Logger.Level.INFO, "AI-ASPM application tier listening on port " + port);
        log.log(System.Logger.Level.INFO, registry.all().size() + " operation(s) registered:");
        for (OperationRegistry.Operation operation : registry.all()) {
            log.log(System.Logger.Level.INFO, "   " + operation.annotationClass().name().charAt(0) + "  "
                    + operation.method() + " " + operation.pathTemplate() + "  ["
                    + operation.requiredPermission().orElse("unauthenticated") + "]");
        }
        // Stated at startup rather than left in a file somebody may not read. An operator who sees this
        // line in a production log is looking at a misconfiguration.
        log.log(System.Logger.Level.WARNING, "AUTHENTICATION: " + principals.description());
        log.log(System.Logger.Level.WARNING,
                "This tier serves plain HTTP and expects the TLS-terminating ingress of DOC-15 section 3.1 "
                        + "in front of it (ADR-057).");
    }

    /**
     * One route per registered operation, derived from the same catalogue the registry is derived from.
     *
     * <p>Derived rather than listed. The dispatcher refuses to construct where a route has no registered
     * operation or a registered operation has no route, and two hand-maintained lists would fail that
     * check on every addition until somebody edited both — which is the kind of friction that gets
     * resolved by relaxing the check.
     */
    /** The federation wiring handed to the routes. {@code signIn} is null without a public base URL. */
    record Federation(aspm.app.identity.federation.IdentityProviderService providers,
            aspm.app.identity.FederatedSignIn signIn) {
    }

    private static List<Dispatcher.Route> routesFor(DataSource dataSource,
            aspm.app.ui.AuthPages auth, Federation federation,
            aspm.app.notification.NotificationChannelService channelService,
            aspm.app.integration.ConnectorService connectorService,
            aspm.app.integration.OutboundReferenceService referenceService,
            aspm.app.reporting.ReportService reportService) {
        List<Dispatcher.Route> routes = new java.util.ArrayList<>();
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api"),
                aspm.app.resource.ServiceDocument::get));

        // The interface. ADR-058.
        // The product mark, beside the stylesheet and for the same reason: both are class G, both must
        // render on the sign-in page, and both are one file rather than a copy per interface.
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/brand/icon.png"),
                aspm.app.ui.BrandAssets::icon));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/brand/logo.png"),
                aspm.app.ui.BrandAssets::wordmark));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/brand/logo-dark.png"),
                aspm.app.ui.BrandAssets::wordmarkDark));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/brand/copilot.png"),
                aspm.app.ui.BrandAssets::copilot));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/brand/icon-180.png"),
                aspm.app.ui.BrandAssets::touchIcon));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/style.css"),
                aspm.app.ui.InterfaceResource::stylesheet));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/app.js"),
                aspm.app.ui.InterfaceResource::script));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/sign-in"), auth::signInForm));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/sign-in"), auth::signIn));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/mfa"), auth::challengeForm));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/mfa"), auth::challenge));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/mfa-enrol"), auth::enrolForm));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/mfa-enrol"), auth::enrol));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/forgot-password"),
                auth::forgotPassword));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/forgot-password"),
                auth::forgotPassword));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/sign-out"), auth::signOut));
        // POST as well as GET. The sidebar signs out with a form, because signing out is a state change
        // and a GET is reachable by anything that prefetches a link — a browser or a corporate proxy
        // warming a page can end a session the user is still using. GET stays for the abandon link on the
        // second-factor challenge, where there is no session worth keeping.
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/sign-out"), auth::signOut));

        // Self-service, then administration. The tenant comes from the same place the auth pages take it:
        // a deployment property, never a request field (SEC-TEN-004).
        var account = new aspm.app.ui.AccountPages(dataSource, auth.resolver().tenantId());
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/change-password"),
                account::changePasswordForm));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/change-password"),
                account::changePassword));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/step-up"), account::stepUpForm));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/step-up"), account::stepUp));

        // The user guide. Registered beside the self-service surfaces rather than beside the estate
        // pages because it shares their authorization shape: identity, not permission.
        // The guide is a React page now; its documents are served as JSON by UiApi.

        var admin = new aspm.app.ui.AdminPages(dataSource, auth.resolver().tenantId());
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/security-policy"),
                admin::policyForm));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/security-policy"),
                admin::policySave));

        // ApplicationPages is no longer constructed here. Its last routed handler was GET /components,
        // removed with that page; the application inventory itself has been a React page for some time.
        // The class stays because ApplicationPages.READ is the permission the React navigation and the
        // operation registry both name — and it is one of the server-rendered handlers now reachable by
        // nothing, which is a larger cleanup than this change.
        var board = new aspm.app.ui.RequestPages(dataSource);
        // ---- The React interface and the JSON it reads (ADR-006's design language, built) --------
        //
        // Mounted at the root, and it IS the interface. It went in beside the server-rendered pages
        // under a /app prefix while it was incomplete, because a cut-over then would have taken away
        // working screens to deliver a prettier version of two. That is finished: what remains
        // server-rendered is authentication and administration — sign-in, MFA, step-up, forced
        // credential change, the security policy, the guide — where correctness lives in a redirect
        // and a gate in two places can disagree with itself.
        var webApi = new aspm.app.ui.UiApi(dataSource);
        // The write side of the React interface. Every one of its handlers calls the same service
        // method the server-rendered page called, so the two tiers cannot disagree about what is
        // permitted. Constructed here rather than beside its own routes because one of them has to
        // be registered before the parameterized application route below.
        var editorApi = new aspm.app.ui.EditorApi(dataSource, auth.resolver().tenantId());
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/session"),
                webApi::session));
        // The same two documents as JSON, for the React pages that replaced the hand-off. The
        // server-rendered /guide stays: it is the page a caller with no bundle on the classpath gets,
        // and it is what the sign-in tier can link to.
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/guide"), webApi::guide));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/api-guide"),
                webApi::apiGuide));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/board"), webApi::board));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/board/{id}"),
                webApi::request));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/board/{id}/transitions"),
                webApi::transition));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/board/{id}/assign"),
                webApi::assign));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/board/{id}/comments"),
                webApi::requestComments));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/board/{id}/comments"),
                webApi::comment));
        routes.add(new Dispatcher.Route("GET",
                new PathTemplate("/api/ui/board/{id}/findings/{findingId}"), webApi::finding));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/board/{id}/findings/{findingId}"), webApi::saveFinding));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/board/{id}/findings/{findingId}/comments"),
                webApi::comment));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/people"), webApi::people));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/applications"),
                webApi::applications));
        // BEFORE the parameterized route below. The dispatcher takes the first match in
        // registration order, so /api/ui/applications/{id} would otherwise swallow "editor" as an
        // identifier, fail to parse it as a UUID, and answer 404 — which is exactly what it did, and
        // the create form rendered the word "not found" with nothing to say why.
        // Registered before /api/ui/applications/{id} so the template cannot swallow "export",
        // the same reason "editor" is registered before it.
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/applications/export"),
                webApi::applicationsExport));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/applications/editor"),
                editorApi::applicationEditor));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/applications/{id}"),
                webApi::application));
        // The security posture dashboard. Its own route rather than a fatter detail payload: the
        // detail card is fetched on every navigation to the page and this is eleven aggregate
        // queries, so folding them together would slow down the part that is always needed for the
        // part that is scrolled to.
        routes.add(new Dispatcher.Route("GET",
                new PathTemplate("/api/ui/applications/{id}/posture"),
                webApi::assetPosture));
        // Software composition. The dashboard payload, the tree a level at a time, the two
        // searches, and the drill-downs behind them.
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/alerts"),
                webApi::alertSubscriptions));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/alerts"),
                webApi::createAlert));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/alerts/{id}/active"),
                webApi::setAlertActive));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/assessment-plan"),
                webApi::assessmentPlan));
        // The plan itself. Registered after the read above and before the {id} template so neither
        // can swallow "windows".
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/assessment-plan/windows"), webApi::planWindowsCreate));
        routes.add(new Dispatcher.Route("PATCH",
                new PathTemplate("/api/ui/assessment-plan/windows/{id}"),
                webApi::planWindowUpdate));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/assessment-plan/attestations"), webApi::reviewAttest));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/assessment-plan/attestations/{id}/withdraw"),
                webApi::reviewAttestationWithdraw));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/dependencies/artifact/{id}/sbom"),
                webApi::uploadArtifactSbom));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/dependencies/artifact/{id}/retire"),
                webApi::retireArtifact));
        // The export first, so "export" is never read as a path parameter of the dashboard route.
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/suggestions"),
                webApi::suggestions));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/suggestions/{id}/decide"),
                webApi::decideSuggestion));
        // Before the parameterized run route, so "analyse" is never read as a capability code.
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/top-weaknesses"),
                webApi::topWeaknesses));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/findings/classify"),
                webApi::classifyFinding));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/sbom-submission-health"),
                webApi::sbomSubmissionHealth));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/findings/{id}"),
                webApi::findingDetail));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/findings/{id}/lifecycle"),
                webApi::findingLifecycle));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/findings/{id}/transition"),
                webApi::transitionFinding));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/agents/analyse"),
                webApi::analyseSurface));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/agents/{code}/run"),
                webApi::runAgent));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/vulnerabilities/export"),
                webApi::vulnerabilityExport));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/vulnerabilities"),
                webApi::vulnerabilities));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/ai-providers"),
                webApi::aiProviders));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/ai-providers"),
                webApi::createAiProvider));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/ai-providers/{id}/active"),
                webApi::setAiProviderActive));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/review-policy"),
                webApi::reviewPolicy));
        routes.add(new Dispatcher.Route("PUT", new PathTemplate("/api/ui/review-policy/{id}"),
                webApi::setReviewPolicy));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/dependencies"),
                webApi::dependencyOverview));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/dependencies/node"),
                webApi::dependencyNode));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/dependencies/tree"),
                webApi::dependencyTree));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/dependencies/advisories"),
                webApi::dependencyAdvisories));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/dependencies/components"),
                webApi::dependencyComponents));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/dependencies/locations"),
                webApi::dependencyLocations));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/dependencies/graph"),
                webApi::dependencyGraph));
        // Ingestion credentials, and the exports. The export route is registered before the
        // parameterized dependency routes so "export" is never read as an identifier.
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/service-credentials"),
                webApi::serviceCredentials));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/service-credentials"),
                webApi::issueServiceCredential));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/service-credentials/{id}/revoke"),
                webApi::revokeServiceCredential));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/dependencies/export"),
                webApi::dependencyExport));
        // The estate graph. One node and its immediate neighbours, whichever structure the
        // identifier names — registered before /api/ui/projects so no template can swallow it.
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/graph/{id}"),
                webApi::graph));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/projects"),
                webApi::projects));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/projects/{id}"),
                webApi::project));
        routes.add(new Dispatcher.Route("GET",
                new PathTemplate("/api/ui/projects/{id}/requests"), webApi::projectRequests));
        // The project record editor. Registered before /projects/{id}/access so neither template can
        // shadow the other; PathTemplate matches on registration order.
        routes.add(new Dispatcher.Route("GET",
                new PathTemplate("/api/ui/projects/{id}/editor"), editorApi::projectEditor));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/projects/{id}/editor"), editorApi::projectSave));
        // The same handler as the application posture. A project is an asset of the same aggregate
        // (ADR-009) and every rollup it reads is rooted at whatever asset it is asked about.
        routes.add(new Dispatcher.Route("GET",
                new PathTemplate("/api/ui/projects/{id}/posture"), webApi::assetPosture));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/requests"),
                webApi::createRequest));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/projects/{id}/access"),
                webApi::projectAccess));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/projects/{id}/access"),
                webApi::grantProjectAccess));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/projects/{id}/access/revoke"),
                webApi::revokeProjectAccess));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/board/{id}/participants"),
                webApi::participants));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/board/{id}/participants"),
                webApi::addParticipant));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/board/{id}/participants/remove"),
                webApi::removeParticipant));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/board/{id}/findings/{findingId}/remediation"),
                webApi::claimRemediation));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/hosts"),
                webApi::hosts));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/organization"),
                webApi::organization));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/overview"),
                webApi::overview));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/workload"),
                webApi::workload));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/teams"), webApi::teams));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/teams"),
                webApi::createTeam));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/teams/members"),
                webApi::assignTeamMember));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/teams/{id}/retire"),
                webApi::retireTeam));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/workload/analytics"),
                webApi::analytics));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/composition"),
                webApi::composition));
        // Federated sign-in (V074): the handshake routes and the provider administration API.
        var federationPages = new aspm.app.ui.FederationPages(federation.signIn(), auth.secureCookies());
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/auth/{provider}/start"), federationPages::start));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/auth/callback"), federationPages::callback));
        var providerApi = new aspm.app.ui.IdentityProviderApi(federation.providers(), federation.signIn());
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/access/identity-providers"), providerApi::list));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/access/identity-providers"), providerApi::create));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/access/identity-providers/{id}"), providerApi::update));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/access/identity-providers/{id}/transition"),
                providerApi::transition));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/access/identity-providers/{id}/test"), providerApi::test));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/access/identity-providers/{id}/group-roles"),
                providerApi::setGroupRoles));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/access/local-sign-in"), providerApi::setLocalSignIn));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/access/users/{id}/break-glass"), providerApi::setBreakGlass));
        // Notifications (V075): the centre and preferences for the caller, channels and routes for the
        // administrator.
        var notificationApi = new aspm.app.ui.NotificationApi(dataSource, auth.resolver(), channelService);
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/notifications"), notificationApi::list));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/notifications/{id}/read"), notificationApi::markRead));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/account/notification-preferences"), notificationApi::preferences));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/account/notification-preferences"), notificationApi::savePreferences));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/settings/notification-channels"), notificationApi::listChannels));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/notification-channels"), notificationApi::createChannel));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/notification-channels/{id}"), notificationApi::updateChannel));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/notification-channels/{id}/transition"),
                notificationApi::transitionChannel));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/notification-channels/{id}/verify"), notificationApi::verifyChannel));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/notification-channels/{id}/confirm"), notificationApi::confirmChannel));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/settings/notification-channels/{id}/deliveries"),
                notificationApi::deliveries));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/notification-routes"), notificationApi::setRoutes));

        // Outbound connectors and references (V076, DOC-21 §10).
        var connectorApi = new aspm.app.ui.ConnectorApi(dataSource, connectorService, referenceService);
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/settings/connectors"), connectorApi::list));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/connectors"), connectorApi::create));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/connectors/{id}"), connectorApi::update));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/connectors/{id}/transition"), connectorApi::transition));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/connectors/{id}/rotate"), connectorApi::rotate));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/connectors/{id}/probe"), connectorApi::probe));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/settings/connectors/{id}/operations"), connectorApi::operations));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/findings/{id}/references"), connectorApi::findingReferences));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/findings/{id}/references"), connectorApi::createFindingReference));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/outbound-references/divergences"), connectorApi::divergences));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/outbound-references/{id}/resolve"), connectorApi::resolve));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/outbound-references/{id}/retry"), connectorApi::retry));

        // Scheduled reports and audit evidence (V077, DOC-12 §11–§12).
        var reportApi = new aspm.app.ui.ReportApi(dataSource, reportService);
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/settings/report-schedules"), reportApi::list));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/report-schedules"), reportApi::create));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/report-schedules/{id}"), reportApi::update));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/report-schedules/{id}/recipients"), reportApi::setRecipients));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/report-schedules/{id}/transition"), reportApi::transition));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/report-schedules/{id}/run"), reportApi::run));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/reports/artifacts"), reportApi::artifacts));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/reports/artifacts/{id}/download"), reportApi::download));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/reports/audit-evidence"), reportApi::auditEvidence));

        // The AI surfaces (ADR-075, V078).
        var aiApi = new aspm.app.ui.AiApi(dataSource);
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/ai/ask"), aiApi::ask));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/ai/draft"), aiApi::draft));
        // The copilot (ADR-077). Read its own conversations, ask, replay one, drop one.
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/ai/copilot"), aiApi::copilotOpen));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/ai/copilot"), aiApi::copilotAsk));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/ai/copilot/{id}"), aiApi::copilotConversation));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/ai/copilot/{id}/clear"), aiApi::copilotClear));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/ai/usage"), aiApi::usage));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/ai/budget"), aiApi::budget));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/ai/evaluate"), aiApi::evaluate));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/ai/evaluations"), aiApi::evaluations));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/ai-providers/{id}/test"), aiApi::testProvider));
        var accessApi = new aspm.app.ui.AccessApi(dataSource, auth.resolver().tenantId());
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/session/keepalive"),
                accessApi::keepalive));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/account"),
                accessApi::account));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/account/sessions/revoke"), accessApi::revokeSession));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/access"),
                accessApi::access));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/access/users/{id}"),
                accessApi::user));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/access/users/{id}/roles"), accessApi::grant));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/access/users/{id}/roles/revoke"),
                accessApi::revokeGrant));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/access/users/{id}/reset"), accessApi::reset));
        routes.add(new Dispatcher.Route("GET",
                new PathTemplate("/api/ui/applications/{id}/editor"), editorApi::applicationEditor));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/applications"),
                editorApi::applicationSave));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/applications/{id}"),
                editorApi::applicationSave));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/applications/{id}/retire"),
                editorApi::applicationRetire));
        // The declared-field catalogue. Registered before the organization routes purely to keep the
        // settings surface together; PathTemplate matches on registration order and none of these
        // can shadow another.
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/settings/fields"),
                editorApi::fieldCatalogue));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/fields"),
                editorApi::fieldCreate));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/fields/{id}"),
                editorApi::fieldUpdate));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/settings/fields/{id}/lifecycle"),
                editorApi::fieldLifecycle));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/settings/fields/{id}/move"), editorApi::fieldMove));
        // The endpoint environment catalogue. Same five shapes as the field catalogue above it and
        // the same permission on the writes: it is the other half of the same tenant vocabulary.
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/settings/environments"),
                editorApi::environmentCatalogue));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/settings/environments"),
                editorApi::environmentCreate));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/settings/environments/{id}"),
                editorApi::environmentUpdate));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/settings/environments/{id}/lifecycle"),
                editorApi::environmentLifecycle));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/settings/environments/{id}/move"),
                editorApi::environmentMove));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/organization"),
                editorApi::nodeCreate));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/organization/{id}"),
                editorApi::nodeUpdate));
        routes.add(new Dispatcher.Route("POST",
                new PathTemplate("/api/ui/organization/{id}/deprecate"),
                editorApi::nodeDeprecate));
        routes.add(new Dispatcher.Route("GET",
                new PathTemplate("/api/ui/board/{id}/finding-form"), editorApi::findingForm));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/board/{id}/findings"),
                editorApi::recordFinding));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/roles"),
                editorApi::roles));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/roles"),
                editorApi::roleCreate));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/roles/{id}"),
                editorApi::role));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/roles/{id}"),
                editorApi::roleSave));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/roles/{id}/retire"),
                editorApi::roleRetire));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/roles/{id}/restore"),
                editorApi::roleRestore));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/roles/{id}/delete"),
                editorApi::roleDelete));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/board/{id}/attachments"),
                board::upload));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/attachments/{id}"),
                board::attachment));
        var sbom = new aspm.app.resource.SbomEndpoint(dataSource);
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/v1/sbom-submissions"),
                sbom::submit));
        // The second automated ingestion door, beside the first rather than instead of it. They carry
        // different documents — a bill of materials declares components, a scan report describes
        // weaknesses in code — and they resolve their target through the same method, so the two can
        // never disagree about which asset a repository name means.
        var findingImports = new aspm.app.resource.FindingImportEndpoint(dataSource);
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/v1/finding-imports"),
                findingImports::submit));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/v1/rescans/pending"),
                sbom::pendingRescans));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/v1/rescans/{id}"),
                sbom::submitRescan));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/ui/rescan-schedule"),
                sbom::rescanSchedule));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/ui/rescan-schedule"),
                sbom::setRescanSchedule));
        // Housekeeping on the one timer that exists. See SessionReaper for why it is a call rather
        // than a thread in this tier.
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/v1/session-reap"),
                new aspm.app.identity.SessionReaper(dataSource)::reap));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/v1/coverage-states"),
                sbom::coverage));
        var requestTransitions = new aspm.app.resource.RequestTransitionEndpoint(dataSource);
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/api/v1/requests/{id}/transitions"),
                requestTransitions::get));
        routes.add(new Dispatcher.Route("POST", new PathTemplate("/api/v1/requests/{id}/transitions"),
                requestTransitions::post));
        for (aspm.app.resource.ResourceGroup group : aspm.app.resource.ResourceCatalogue.all()) {
            var endpoint = new aspm.app.resource.ResourceEndpoint(dataSource, group,
                    new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC()));
            String collection = "/api/v1/" + group.name();
            String object = collection + "/{id}";
            routes.add(new Dispatcher.Route("GET", new PathTemplate(collection), endpoint::list));
            routes.add(new Dispatcher.Route("GET", new PathTemplate(object), endpoint::get));
            if (!group.writableOnCreate().isEmpty()) {
                routes.add(new Dispatcher.Route("POST", new PathTemplate(collection), endpoint::create));
            }
            if (!group.writableOnUpdate().isEmpty()) {
                routes.add(new Dispatcher.Route("PATCH", new PathTemplate(object), endpoint::patch));
            }
        }
        // ---- THE INTERFACE, AND IT IS REGISTERED LAST ------------------------------------------------
        //
        // Mounted at the ROOT. It used to sit under /app while server-rendered pages held /ui, and the
        // prefix existed only so the two could share an origin without colliding. With one interface
        // there is nothing to collide with, and a person typing the host name reaches the product
        // instead of a JSON service document.
        //
        // *** ORDER IS LOAD-BEARING, AND IT IS WHY THIS BLOCK MOVED TO THE END. *** The interface
        // templates overlap real routes — /applications/{id} is both a React page and, until this change,
        // a server-rendered one. Registered in the middle of the list, as they were, they shadowed every
        // explicit route declared after them. Last means every explicit route is tried first.
        //
        // Both old prefixes keep working as redirects rather than as pages. Not politeness: comment
        // bodies in the database hold links written when the address was /ui/..., and a bookmark is the
        // one thing a person cannot be asked to update. A redirect is not a second interface.
        for (String legacy : java.util.List.of("/ui", "/app")) {
            routes.add(new Dispatcher.Route("GET", new PathTemplate(legacy),
                    aspm.app.ui.WebUi::legacyPrefix));
            routes.add(new Dispatcher.Route("GET", new PathTemplate(legacy + "/{a}"),
                    aspm.app.ui.WebUi::legacyPrefix));
            routes.add(new Dispatcher.Route("GET", new PathTemplate(legacy + "/{a}/{b}"),
                    aspm.app.ui.WebUi::legacyPrefix));
            routes.add(new Dispatcher.Route("GET", new PathTemplate(legacy + "/{a}/{b}/{c}"),
                    aspm.app.ui.WebUi::legacyPrefix));
            routes.add(new Dispatcher.Route("GET", new PathTemplate(legacy + "/{a}/{b}/{c}/{d}"),
                    aspm.app.ui.WebUi::legacyPrefix));
        }
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/"),
                aspm.app.ui.WebUi::shell));
        routes.add(new Dispatcher.Route("GET", new PathTemplate("/assets/{name}"),
                aspm.app.ui.WebUi::asset));
        // One route per template the router declares. See WebUi.ROUTES for why this is not four
        // wildcards: a wildcard at the root matched /api/v1/does-not-exist and answered it with a page.
        for (String template : aspm.app.ui.WebUi.ROUTES) {
            routes.add(new Dispatcher.Route("GET", new PathTemplate(template),
                    aspm.app.ui.WebUi::shell));
        }
        return List.copyOf(routes);
    }

    /** Exposed for tests, which build the same dispatcher rather than a parallel one. */
    public static Dispatcher dispatcherFor(DataSource dataSource, PrincipalResolver principals) {
        aspm.app.secrets.Secrets secrets = aspm.app.secrets.Secrets.fromEnvironment(
                Map.of("ASPM_ENVIRONMENT", "development", "ASPM_SECRETS_PROVIDERS", "sealed"), dataSource);
        aspm.app.egress.EgressGuard egress = aspm.app.egress.EgressGuard.production();
        var oidc = new aspm.app.identity.federation.OidcClient(egress);
        var providers = new aspm.app.identity.federation.IdentityProviderService(dataSource, secrets, oidc, egress);
        var channelService = new aspm.app.notification.NotificationChannelService(dataSource, secrets,
                aspm.app.notification.DeliveryWorker.defaultSenders(egress, Map.of()));
        return new Dispatcher(PlatformOperations.registry(),
                routesFor(dataSource, new aspm.app.ui.AuthPages(dataSource,
                        java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"), false),
                        new Federation(providers, null), channelService,
                        new aspm.app.integration.ConnectorService(dataSource, secrets, aspm.app.integration.TrackerAdapters.all(egress),
                                aspm.app.integration.TrackerAdapters.enabledKinds(Map.of())),
                        new aspm.app.integration.OutboundReferenceService(dataSource),
                        new aspm.app.reporting.ReportService(dataSource, new aspm.app.resource.ObjectStore(Map.of()),
                                new aspm.app.reporting.ReportRenderer(dataSource, new aspm.app.resource.VulnerabilityQuery(dataSource)))),
                principals);
    }
}
