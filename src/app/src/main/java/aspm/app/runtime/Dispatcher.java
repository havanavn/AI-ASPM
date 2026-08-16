package aspm.app.runtime;

import aspm.app.api.AnnotationClass;
import aspm.app.api.DenialResponse;
import aspm.app.api.IdempotencyKey;
import aspm.app.api.OperationRegistry;
import aspm.app.api.RequestValidation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The single place an operation's annotation class is enforced. {@code PRD-API-019}, ADR-036.
 *
 * <p>ADR-036 requires the seven classes to be "framework properties, not per-operation code, so an
 * operation inherits them and cannot omit one". This class is that framework. A handler receives a request
 * that has already been authenticated, class-checked, idempotency-checked and body-validated, and it has no
 * way to receive one that has not — because there is no path from the transport to a handler that does not
 * pass through {@link #dispatch}.
 *
 * <p>ADR-057 keeps the transport out of this type deliberately: {@link HttpRuntime} adapts sockets to
 * {@link Request} and back, and everything security-relevant happens here. Replacing the HTTP runtime later
 * means replacing that adapter, not this enforcement.
 *
 * <h2>The order is load-bearing</h2>
 *
 * <ol>
 *   <li><b>Route first.</b> An unregistered path is a routing failure. {@code OperationRegistry.resolve}
 *       returning empty means the operation has no annotation class, and running it anyway is exactly what
 *       {@code PRD-API-019} forbids.
 *   <li><b>Authenticate second</b>, before the body is read. A body read before authentication is work
 *       done for an anonymous caller.
 *   <li><b>Idempotency and body validation third.</b>
 *   <li><b>Authorization inside the handler</b>, through the kernel gate — not here. The gate needs the
 *       object identifier, which only the handler knows how to extract, and {@code SEC-AUZ-017} requires
 *       re-validation against the object rather than against the path.
 * </ol>
 */
public final class Dispatcher {

    /** What a handler receives. Everything on it has already been checked. */
    public record Request(String method, String path, Map<String, String> pathVariables,
            Map<String, String> query, Map<String, String> headers, Optional<Map<String, Object>> body,
            Optional<String> rawForm, Principal principal, OperationRegistry.Operation operation) {

        public Request {
            pathVariables = Map.copyOf(pathVariables);
            query = Map.copyOf(query);
            headers = Map.copyOf(headers);
        }
    }

    /** What a handler returns. */
    public record Response(int status, Object body, Map<String, String> headers) {

        public static Response ok(Object body) {
            return new Response(200, body, Map.of());
        }

        public static Response created(Object body, String location) {
            return new Response(201, body, Map.of("Location", location));
        }

        /**
         * {@code PRD-API-036} and {@code SEC-AUZ-018}: a scope violation is indistinguishable from
         * non-existence. There is no {@code forbidden()} factory, because a 403 on an object the caller
         * may not see confirms the object exists — which is the enumeration this rule prevents.
         */
        public static Response notFound() {
            // A Map, not the DenialResponse.Body record. Json.write rejects a type it does not know,
            // so passing the record threw, was caught by the dispatcher's catch-all, and became a 500
            // carrying a correlation identifier — DISTINGUISHABLE from a 404, which is exactly what
            // PRD-API-036 forbids. The claim that an unrouted path looked like a scope denial was
            // false until this line, and only running it showed that.
            return new Response(DenialResponse.STATUS,
                    Map.of("status", Integer.valueOf(DenialResponse.STATUS),
                            "code", DenialResponse.CODE,
                            "message", DenialResponse.MESSAGE),
                    Map.of());
        }
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    /** One route: an operation, the template it is registered under, and its handler. */
    public record Route(String method, PathTemplate template, Handler handler) {
    }

    private final OperationRegistry registry;
    private final List<Route> routes;
    private final PrincipalResolver principals;

    public Dispatcher(OperationRegistry registry, List<Route> routes, PrincipalResolver principals) {
        this.registry = Objects.requireNonNull(registry, "an operation registry is required");
        this.routes = List.copyOf(Objects.requireNonNull(routes, "routes are required"));
        this.principals = Objects.requireNonNull(principals,
                "a principal resolver is required. A dispatcher with none would have to treat every "
                        + "request as anonymous, which is deny-by-default inverted (SEC-AUZ-014).");

        // Every route must be registered, and every registered operation must have a route. The first
        // omission dispatches an operation with no annotation class; the second leaves an operation in the
        // catalogue that gates nothing, which SEC-AUZ-001 calls a defect for permissions and which is the
        // same defect here.
        for (Route route : this.routes) {
            if (registry.resolve(route.method(), route.template().template()).isEmpty()) {
                throw new IllegalArgumentException(
                        route.method() + " " + route.template().template() + " has a handler and no "
                                + "registered operation, so it would dispatch with no annotation class "
                                + "(PRD-API-019).");
            }
        }
        for (OperationRegistry.Operation operation : registry.all()) {
            boolean routed = this.routes.stream().anyMatch(r ->
                    r.method().equalsIgnoreCase(operation.method())
                            && r.template().template().equals(operation.pathTemplate()));
            if (!routed) {
                throw new IllegalArgumentException(
                        operation.method() + " " + operation.pathTemplate() + " is registered and has no "
                                + "handler. A registered operation that cannot be reached is a claim in the "
                                + "catalogue that nothing backs.");
            }
        }
    }

    /**
     * Routes, authenticates, validates, and hands the request to its handler.
     *
     * @param rawBody the request body, or empty where there was none. Read by the transport, because
     *     bounding its size is a transport concern ({@code RequestValidation.MAX_BODY_BYTES})
     */
    public Response dispatch(String method, String path, Map<String, String> query,
            Map<String, String> headers, Optional<String> rawBody) {

        // 1. Route. No registered operation means no annotation class.
        for (Route route : routes) {
            if (!route.method().equalsIgnoreCase(method)) {
                continue;
            }
            Optional<Map<String, String>> variables = route.template().match(path);
            if (variables.isEmpty()) {
                continue;
            }
            OperationRegistry.Operation operation =
                    registry.resolve(method, route.template().template()).orElseThrow();
            return dispatchTo(route, operation, variables.orElseThrow(), query, headers, rawBody, path);
        }
        // An unrouted INTERFACE path — anything that is not the API, see isInterfacePath — sends an
        // unauthenticated caller to sign-in, and only answers 404 once they are signed in.
        //
        // *** WHY THIS IS NOT MERELY A CONVENIENCE. *** Routing runs before authentication, so before
        // this branch existed a signed-out caller got a redirect from /ui/overview and a 404 from
        // /ui/anything-else — which is an oracle telling them which interface routes exist. The
        // asymmetry was the defect; the better experience is a side effect of removing it.
        //
        // Under /api the 404 stands: PRD-API-036 makes an unrouted path indistinguishable from a scope
        // denial, and a pipeline must not be redirected to an HTML form it would log as success.
        if (isInterfacePath(path)) {
            boolean authenticated;
            try {
                authenticated = principals.resolve(headers).isPresent();
            } catch (SecurityException e) {
                authenticated = false;
            }
            if (!authenticated) {
                return new Response(303, aspm.app.ui.InterfaceResource.emptyBody(),
                        Map.of("Location", SIGN_IN_EXPIRED,
                                "Content-Type", "text/html; charset=utf-8"));
            }
        }
        return Response.notFound();
    }

    /**
     * The page a form submission came from, for a step-up return path.
     *
     * <p>A POST target is rarely a page: {@code /roles/{id}/retire} has no {@code GET}, and sending a
     * caller back there after they elevate would answer 404. So this walks up the path, one segment at a
     * time, until it finds a template the registry serves a {@code GET} for — {@code .../retire} becomes
     * {@code /roles/{id}}, and {@code /users/{id}/roles/revoke} becomes {@code /users/{id}}.
     *
     * <p>Derived from the registry rather than from a table of pairs, so a route added later gets a
     * correct return path without anybody remembering to add one. Falls back to the overview, which every
     * authenticated caller can open.
     *
     * @param template the matched route template, with variables
     * @param path the concrete request path
     */
    private String owningPage(String template, String path) {
        String currentTemplate = template;
        String currentPath = path;
        // The floor used to be the four characters of "/ui/". At the root the only floor is a single
        // segment: walking past it would ask the registry about "/", which IS a registered GET — the
        // interface shell — so every unmatched POST would name the shell as its owning page instead of
        // falling through to the overview.
        while (currentTemplate.length() > 1) {
            if (registry.resolve("GET", currentTemplate).isPresent()) {
                return currentPath;
            }
            int cut = currentTemplate.lastIndexOf('/');
            int pathCut = currentPath.lastIndexOf('/');
            if (cut <= 0 || pathCut <= 0) {
                break;
            }
            currentTemplate = currentTemplate.substring(0, cut);
            currentPath = currentPath.substring(0, pathCut);
        }
        return "/overview";
    }

    /**
     * The form field an HTML form uses to carry its idempotency key.
     *
     * <p>Public because every form posting to a class B or class E route must render it, and a name
     * duplicated as a literal in the page classes is one that gets misspelled in exactly one of them —
     * where the symptom is a 400 on one form and not the others.
     */
    /**
     * Where a browser with no session is sent.
     *
     * <p>A literal, and deliberately not built from the request. It used to choose between {@code /ui}
     * and {@code /app} by reading the request path, because the authentication pages were served under
     * both; the interface is at the root now, so there is one answer. A redirect target assembled from
     * anything a caller can influence is an open redirect, and an authentication surface is the worst
     * place to have one.
     */
    private static final String SIGN_IN_EXPIRED = "/sign-in?expired=1";

    public static final String IDEMPOTENCY_FIELD = "idempotency_key";

    /**
     * Reads one field out of a form-encoded body, without parsing the whole thing.
     *
     * <p>Needed here because the idempotency check runs BEFORE body parsing, and it runs before it for a
     * reason: rejecting a replay should not require having read and decoded a body first.
     */
    private static String formField(Optional<String> rawBody, String name) {
        if (rawBody.isEmpty()) {
            return null;
        }
        for (String pair : rawBody.orElseThrow().split("&", -1)) {
            int equals = pair.indexOf('=');
            if (equals > 0 && name.equals(java.net.URLDecoder.decode(pair.substring(0, equals),
                    java.nio.charset.StandardCharsets.UTF_8))) {
                return java.net.URLDecoder.decode(pair.substring(equals + 1).replace('+', ' '),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private Response dispatchTo(Route route, OperationRegistry.Operation operation,
            Map<String, String> variables, Map<String, String> query, Map<String, String> headers,
            Optional<String> rawBody, String path) {

        // 2. Authenticate, before reading the body.
        Principal principal;
        if (operation.annotationClass() == AnnotationClass.G_UNAUTHENTICATED) {
            principal = null;
        } else {
            Optional<Principal> resolved;
            try {
                resolved = principals.resolve(headers);
            } catch (SecurityException e) {
                // A failed authentication and an absent one get the same response. Distinguishing them
                // tells a caller which credentials exist.
                if (isInterfacePath(path)) {
                    return new Response(303, aspm.app.ui.InterfaceResource.emptyBody(),
                            Map.of("Location", SIGN_IN_EXPIRED,
                                    "Content-Type", "text/html; charset=utf-8"));
                }
                return new Response(401, Map.of("status", 401, "code", "UNAUTHENTICATED",
                        "message", "authentication required"), Map.of());
            }
            if (resolved.isEmpty()) {
                // A browser asked for a page: send it to sign-in. A JSON 401 in an address bar is a
                // wall of text, and the caller has no way to act on it.
                //
                // The API keeps the 401: a pipeline following a redirect to an HTML form would receive
                // 200 and a page, and would log success.
                if (isInterfacePath(path)) {
                    return new Response(303, aspm.app.ui.InterfaceResource.emptyBody(),
                            Map.of("Location", SIGN_IN_EXPIRED,
                                    "Content-Type", "text/html; charset=utf-8"));
                }
                return new Response(401, Map.of("status", 401, "code", "UNAUTHENTICATED",
                        "message", "authentication required"), Map.of());
            }
            principal = resolved.orElseThrow();

            // 2a. A credential the platform has marked for replacement blocks everything else.
            //
            //     Until this branch existed, must_change_password was SET by three paths — the deployment
            //     bootstrap, an administrative reset, and a credential found in the breach corpus at
            //     sign-in — and READ by nothing. A flag that no code enforces is a claim in a column.
            //
            //     Enforced here rather than on each page because this is the only point every route
            //     passes through. A per-page check is one the next page added will omit, and the page
            //     that omits it is the one an attacker with a reset credential uses.
            //
            //     The change-password page itself is exempt, and so is signing out: an enforcement that
            //     admits neither is a locked door with the key behind it. Both are named explicitly —
            //     a prefix test would admit /change-password-anything.
            if (principal.credentialChangeRequired()
                    && !"/change-password".equals(path)
                    && !"/sign-out".equals(path)) {
                if (isInterfacePath(path)) {
                    return new Response(303, aspm.app.ui.InterfaceResource.emptyBody(),
                            Map.of("Location", "/change-password?required=1",
                                    "Content-Type", "text/html; charset=utf-8"));
                }
                // 403, not 401: the caller IS authenticated, and a 401 would send a client to
                // re-authenticate, which succeeds and changes nothing. The code names the remedy because
                // the caller is already known — this is not an enumeration surface.
                return new Response(403, Map.of("status", 403, "code", "CREDENTIAL_CHANGE_REQUIRED",
                        "message", "this credential must be replaced before the API can be used"),
                        Map.of());
            }

            // 2b. THE PERMISSION THE OPERATION DECLARES. Enforced here, for every route.
            //
            // *** THIS WAS MISSING, AND IT IS THE DEFECT CLASS THIS PRODUCT EXISTS TO FIND. ***
            //
            // Every operation declares a required permission and the registry refuses to construct one
            // without it — and nothing read it. Authorization was enforced inside individual handlers:
            // ResourceEndpoint checks, RequestTransition checks, and every page class that did not check
            // was reachable by ANY authenticated principal. A principal holding only vul.finding.read
            // reached the user administration list and the permission matrix, because AdminPages used
            // holds() to decide which PANELS to draw and never to decide whether to answer at all.
            //
            // Reported by the user as "why can a developer see the RBAC table and other users". That is
            // exactly what it was: broken object-level authorization, first on the list of the five
            // highest-risk surfaces, in the platform whose purpose is finding it in other people's code.
            //
            // The declared permission was decorative. A control that is declared, tested for its
            // declaration, and never consulted is worse than an absent one: the registry, the manifest and
            // the startup banner all reported a permission on every route, so every artifact a reviewer
            // would consult said the gate was there.
            //
            // Why the dispatcher and not the handlers: CON-PLT-009 wants one enforcement point. A handler
            // check is opt-in, and the next page added omits it — which is precisely how this happened.
            // Handlers keep their own checks, because they also resolve SCOPE, which needs the row; this
            // gate answers the coarser question the route already declared the answer to.
            //
            // 404 and not 403: PRD-API-036 makes an unrouted path indistinguishable from a scope denial,
            // so a caller cannot map the permission model by probing. The same applies to the interface —
            // and it is why the navigation must be filtered by permission rather than shown and refused.
            if (operation.requiredPermission().isPresent()
                    && !principal.holds(operation.requiredPermission().orElseThrow())) {
                return Response.notFound();
            }

            if (operation.annotationClass().requiresStepUp() && !principal.stepUpAuthenticated()) {
                // A browser gets sent to the step-up challenge with somewhere to come back to. A JSON
                // 401 in an address bar is a wall of text the caller cannot act on, and before V016
                // there was no surface that could clear this condition at all — every class C and class E
                // operation answered 401 to every human caller (see the header of V016).
                //
                // The return path is DERIVED from the request path, never taken from a caller-supplied
                // parameter: a `next` read out of the query string here is an open redirect on an
                // authentication surface, which is the worst place to have one.
                //
                // *** THIS BRANCH USED TO BE GET-ONLY, AND THAT MADE EVERY CLASS E FORM UNUSABLE. ***
                // Creating or saving a role posts to /ui/roles, so it fell through to the JSON 401 and a
                // browser showed {"code":"STEP_UP_REQUIRED"} with no way to act on it. Reported by the
                // user, and it is the same shape as the missing idempotency field: a route verified
                // through the client that finds it easy, and never through the one that uses it.
                //
                // GET-only was a deliberate choice for a reason that turned out not to hold — I wanted to
                // avoid redirecting a POST somewhere that would replay it. Redirecting to the step-up FORM
                // replays nothing: the caller elevates and re-submits deliberately. The cost is that the
                // typed values are lost once, which is why the pages carrying these forms now ask for
                // elevation BEFORE showing an editable form.
                if (isInterfacePath(path)) {
                    return new Response(303, aspm.app.ui.InterfaceResource.emptyBody(),
                            Map.of("Location", "/step-up?next="
                                            + java.net.URLEncoder.encode(
                                                    owningPage(route.template().template(), path),
                                                    java.nio.charset.StandardCharsets.UTF_8),
                                    "Content-Type", "text/html; charset=utf-8"));
                }
                return new Response(401, Map.of("status", 401, "code", "STEP_UP_REQUIRED",
                        "message", "step-up authentication required"), Map.of());
            }
            // *** THE OTHER HALF OF THE SIGNATURE. ***
            //
            // A signed request covers the body by its DIGEST, because a PrincipalResolver is handed
            // headers and never the body. That makes the resolver's verification a signature over a
            // promise until somebody checks the promise, and this is where it is checked: the body
            // that actually arrived must hash to the value that was signed.
            //
            // Without this, a captured submission could be replayed with any body at all — the
            // signature would still verify, because it only ever covered a header the attacker is
            // free to leave untouched. It is the single most important line in the scheme.
            //
            // Gated on the SIGNED SCHEME being in use, not merely on the principal being a service
            // one. A development principal also carries serviceCredential = true and sends no
            // digest, so gating on the flag alone answered 401 to every dev-auth ingestion call —
            // it completes that scheme and applies to nothing else.
            if (principal.serviceCredential() && headers.getOrDefault("authorization", "")
                    .startsWith("ASPM-HMAC-SHA256 ")) {
                String signed = headers.getOrDefault(
                        aspm.app.identity.ServiceCredentialResolver.CONTENT_HASH_HEADER, "");
                String actual = aspm.app.identity.ServiceCredentialResolver.contentHash(
                        rawBody.orElse(""));
                if (!java.security.MessageDigest.isEqual(
                        signed.toLowerCase(java.util.Locale.ROOT)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        actual.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                    return new Response(401, Map.of("status", 401, "code", "CONTENT_DIGEST_MISMATCH",
                            "message", "the body does not match the signed digest"), Map.of());
                }
            }
            if (operation.annotationClass() == AnnotationClass.F_SERVICE_INGEST
                    && !principal.serviceCredential()) {
                return Response.notFound();
            }
            if (!operation.annotationClass().invokableByHumanSession() && !principal.serviceCredential()) {
                return Response.notFound();
            }
        }

        // 3. Idempotency, then body validation.
        boolean formEncodedRequest = headers.getOrDefault("content-type", "")
                .startsWith("application/x-www-form-urlencoded");
        // A GET is nullipotent, so a replay key protects nothing on one — requiring it is a
        // category error, and it broke the first service-credential READ the platform grew: the
        // scanner asking what work is due got 400 for want of a key that would have meant nothing.
        // Idempotency belongs to writes; the annotation class carries it because until now every
        // class that declared it was a write.
        if (operation.requiresIdempotencyKey() && !"GET".equals(operation.method())
                && !"HEAD".equals(operation.method())) {
            // The header, or — for a form post — a hidden field.
            //
            // *** THIS IS A DEFECT BEING CORRECTED, NOT AN ALLOWANCE BEING ADDED. *** Class B and class E
            // require replay protection, and the interface submits ordinary HTML forms, which cannot set
            // a request header without script. So every form posting to a class B or class E route
            // answered 400 IDEMPOTENCY_KEY_REQUIRED — the transition buttons on the request detail page
            // among them, which is the whole workflow surface.
            //
            // It was not caught because the transition endpoint was verified through the API path, where
            // a header is trivial to send. The UI path posts the same operation and was never exercised
            // as a browser exercises it. A route verified only by the client that finds it easy is a
            // route verified for the wrong caller.
            //
            // A hidden field is not a weaker key. What replay protection needs is a value the CLIENT
            // fixes and repeats on retry, and a field rendered into the page has exactly that property:
            // a double submit or a refresh-repost carries the same value, which is the case it guards.
            // A key minted per attempt — which a script adding a header would do — protects against
            // nothing.
            String clientKey = headers.get("idempotency-key");
            if ((clientKey == null || clientKey.isBlank()) && formEncodedRequest) {
                clientKey = formField(rawBody, IDEMPOTENCY_FIELD);
            }
            if (clientKey == null || clientKey.isBlank()) {
                return new Response(400, Map.of("status", 400, "code", "IDEMPOTENCY_KEY_REQUIRED",
                        "message", "this operation requires an Idempotency-Key header"), Map.of());
            }
            try {
                // Namespaced by tenant: an unnamespaced key lets one tenant's replay collide with
                // another's, which is a cross-tenant effect from a header a client controls.
                IdempotencyKey.namespaced(principal.tenantId(), clientKey);
            } catch (IllegalArgumentException e) {
                return new Response(400, Map.of("status", 400, "code", "IDEMPOTENCY_KEY_INVALID",
                        "message", "the idempotency key is not acceptable"), Map.of());
            }
        }

        Optional<Map<String, Object>> body = Optional.empty();
        // A form post is not JSON. The interface submits application/x-www-form-urlencoded, so the raw
        // body travels alongside rather than being forced through the JSON parser — which would reject
        // it and turn a working form into a 400 nobody could explain.
        boolean formEncoded = formEncodedRequest;
        if (!formEncoded && rawBody.isPresent() && !rawBody.orElseThrow().isBlank()) {
            Map<String, Object> parsed;
            try {
                parsed = Json.readObject(rawBody.orElseThrow());
            } catch (IllegalArgumentException e) {
                return new Response(400, Map.of("status", 400, "code", "MALFORMED_BODY",
                        "message", "the request body is not a valid JSON object"), Map.of());
            }
            body = Optional.of(parsed);
        }

        Dispatcher.Request request = new Dispatcher.Request(route.method(), path, variables, query,
                headers, body, formEncoded ? rawBody : Optional.empty(), principal, operation);

        // 4. Establish the tenant context for the duration of the handler, FROM THE PRINCIPAL.
        //
        //    SEC-TEN-004: the context is established "at request entry from an authenticated principal
        //    or a scope-pinned service credential" and is never derived from the request. This is that
        //    entry point, and it is the only one — a handler cannot establish a context, so a handler
        //    cannot establish the wrong one.
        //
        //    Omitting it did not produce an unfiltered read. The kernel's TenantContextHolder threw
        //    MissingTenantContextException and every request became a 500, which is SEC-TEN-005's
        //    fail-closed working exactly as specified: the platform refused rather than guessed.
        //
        //    callWith scopes the binding to the call and clears it afterwards, so nothing survives into
        //    the next request on this carrier thread — the same property OPS-DEP-010 requires of a
        //    pooled database connection, applied to the context itself.
        try {
            Response response = principal == null
                    ? route.handler().handle(request)
                    : aspm.kernel.tenantcontext.contract.TenantContextHolder.callWith(
                            RequestScope.contextFor(principal),
                            () -> route.handler().handle(request));
            return withRestrictedFieldsAbsent(response, operation);
        } catch (UnauthorizedException e) {
            return Response.notFound();
        } catch (IllegalArgumentException e) {
            // The message is the platform's own validation text and is safe to return — but it is
            // checked against the reconnaissance classes of PRD-UIX-025 first, because "safe by
            // construction" is what every leaked error surface was believed to be.
            String message = e.getMessage() == null ? "the request could not be accepted" : e.getMessage();
            try {
                aspm.module.insight.domain.PresentationState.assertErrorTextIsSafe(message);
            } catch (IllegalArgumentException unsafe) {
                message = "the request could not be accepted";
            }
            return new Response(400, Map.of("status", 400, "code", "INVALID_REQUEST",
                    "message", message), Map.of());
        } catch (Exception e) {
            // A reference the caller supplied that the engine will not accept.
            //
            // V065 made every foreign key between two tenant-scoped tables carry the tenant, so
            // "this identifier belongs to another tenant" and "this identifier does not exist" now
            // produce the SAME violation — and therefore the same answer here. That is the point:
            // an error that distinguished them would be the existence oracle V065 closed, rebuilt at
            // the HTTP layer.
            //
            // 400 rather than 500 because nothing failed: the request named something unusable, the
            // engine refused it, and no operator needs paging for that. 23503 is the SQLSTATE for
            // foreign_key_violation; anything else falls through to the handler below, because a
            // constraint the caller cannot influence IS a defect and must stay loud.
            //
            // The cause chain is walked, not just the top exception: a violation raised inside a
            // unit of work arrives wrapped more often than not, and a check on the outermost
            // exception alone would have been a check that silently never matched.
            if (foreignKeyViolation(e)) {
                return new Response(400, Map.of("status", 400, "code", "UNKNOWN_REFERENCE",
                        "message", "one of the identifiers in this request is not one this tenant "
                                + "can use"), Map.of());
            }
            return internalError(e);
        }
    }

    /** {@code 23503} is {@code foreign_key_violation}, anywhere in the cause chain. */
    private static boolean foreignKeyViolation(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && "23503".equals(sql.getSQLState())) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    /**
     * {@code PRD-UIX-025}: no stack trace, no exception class, no query fragment, no internal
     * hostname. The detail goes to the server log; the client gets a correlation identifier.
     */
    private static Response internalError(Exception e) {
        String correlation = java.util.UUID.randomUUID().toString();
        System.getLogger("aspm.dispatch").log(System.Logger.Level.ERROR,
                "unhandled failure, correlation=" + correlation, e);
        return new Response(500, Map.of("status", 500, "code", "INTERNAL_ERROR",
                "message", "the request could not be completed",
                "correlation", correlation), Map.of());
    }

    /**
     * {@code SEC-AUZ-022} and ADR-047: restricted fields are <b>absent</b>, not masked.
     *
     * <p>Applied here rather than trusted to the handler. A handler that forgets produces a response with
     * the field present, and nothing downstream would notice — the whole reason ADR-036 makes these
     * framework properties.
     */
    private static Response withRestrictedFieldsAbsent(Response response,
            OperationRegistry.Operation operation) {
        if (operation.restrictedFields().isEmpty() || !(response.body() instanceof Map<?, ?> map)) {
            return response;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> representation = (Map<String, Object>) map;
        return new Response(response.status(),
                RequestValidation.withRestrictedFieldsAbsent(representation, operation.restrictedFields()),
                response.headers());
    }

    /** Raised by a handler when the authorization gate denies. Becomes a 404, never a 403. */
    public static final class UnauthorizedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public UnauthorizedException(String message) {
            super(message);
        }
    }

    /**
     * A page a person is looking at, as opposed to an API a pipeline is calling.
     *
     * <p>INVERTED from what it used to be, and the inversion is the whole of the change. While the
     * interface lived under {@code /ui} and {@code /app} this could name its prefixes; mounted at the
     * root it cannot, because the root is every path. So the test states what it always meant: anything
     * that is not the API is a page somebody is looking at.
     *
     * <p>{@code /api} keeps its 401 and its 404. A pipeline redirected to an HTML sign-in form receives
     * 200 and a page, and logs success — which is the failure this distinction exists to prevent.
     */
    private static boolean isInterfacePath(String path) {
        if (path == null) {
            return false;
        }
        return !path.equals("/api") && !path.startsWith("/api/");
    }

}
