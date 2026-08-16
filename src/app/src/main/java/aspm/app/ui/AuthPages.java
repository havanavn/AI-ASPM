package aspm.app.ui;

import aspm.app.identity.CredentialBootstrap;
import aspm.app.identity.IdentityService;
import aspm.app.identity.SessionPrincipalResolver;
import aspm.app.identity.Totp;
import aspm.app.runtime.Dispatcher;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Sign-in, the second-factor challenge, and forced enrolment. ADR-059.
 *
 * <h2>The flow, and where each requirement lands</h2>
 *
 * <ol>
 *   <li>{@code GET /sign-in} — identifier and password. Class G.
 *   <li>{@code POST /sign-in} — on success a {@code PASSWORD_ONLY} session cookie, then a redirect to
 *       the challenge or to enrolment. <b>One failure message for every cause</b>: an unknown identifier,
 *       a wrong password and a suspended account are indistinguishable, because distinguishing them tells
 *       an attacker which identifiers exist.
 *   <li>{@code GET|POST /mfa-enrol} — reached when the principal has never enrolled, and reachable
 *       from nowhere else. ADR-059 makes enrolment a state, so this is the only route a half-authenticated
 *       un-enrolled session can complete.
 *   <li>{@code GET|POST /mfa} — the challenge. On success the session token is <b>regenerated</b>
 *       ({@code SEC-SEC-009}: on privilege change) and the caller lands on the overview.
 *   <li>{@code POST /sign-out} — revokes the session and clears the cookie.
 * </ol>
 */
public final class AuthPages {

    private final SessionPrincipalResolver resolver;
    private final boolean secureCookies;

    public AuthPages(DataSource dataSource, UUID tenantId, boolean secureCookies) {
        this.resolver = new SessionPrincipalResolver(Objects.requireNonNull(dataSource), tenantId);
        this.secureCookies = secureCookies;
    }

    public SessionPrincipalResolver resolver() {
        return resolver;
    }

    // ----------------------------------------------------------------------------------------------

    /** {@code GET /sign-in}. */
    public Dispatcher.Response signInForm(Dispatcher.Request request) {
        Messages messages = InterfaceResource.messagesFor(request);
        boolean failed = request.query().containsKey("failed");
        boolean signedOut = request.query().containsKey("signed_out");
        boolean expired = request.query().containsKey("expired");

        String body = "<h1 class=\"auth-title\">" + Html.text(messages.get("auth.signIn.title"))
                + "</h1><p class=\"auth-lede\">" + Html.text(messages.get("auth.signIn.lede")) + "</p>"
                // One message for every failure cause. See the class note.
                + (failed ? AuthLayout.error(messages.get("auth.signIn.failed")) : "")
                + (expired ? AuthLayout.error(messages.get("auth.signIn.expired")) : "")
                + (signedOut ? "<div class=\"auth-notice\" role=\"status\">"
                        + Html.text(messages.get("auth.signIn.signedOut")) + "</div>" : "")
                + "<form method=\"post\" action=\"\" class=\"auth-form\">"
                + AuthLayout.firstField(messages, "identifier", "auth.identifier", "text", "", true,
                        "username", "auth.identifier.hint")
                + AuthLayout.field(messages, "password", "auth.password", "password", "", true,
                        "current-password", null)
                + "<button class=\"auth-submit\" type=\"submit\">"
                + Html.text(messages.get("auth.signIn.action")) + "</button>"
                + "</form>"
                + "<p class=\"auth-alt\"><a href=\"/forgot-password\">"
                + Html.text(messages.get("auth.forgot.link")) + "</a></p>";

        return html(AuthLayout.render(messages, "auth.signIn.title", body));
    }

    /** {@code POST /sign-in}. */
    public Dispatcher.Response signIn(Dispatcher.Request request) throws Exception {
        Map<String, String> form = parseForm(request.rawForm().orElse(""));
        String identifier = form.getOrDefault("identifier", "");
        String password = form.getOrDefault("password", "");

        IdentityService.SignIn outcome = resolver.identity().signIn(resolver.tenantId(), identifier,
                password.toCharArray(), request.headers().get("x-forwarded-for"),
                request.headers().get("user-agent"));

        return switch (outcome) {
            case IdentityService.SignIn.SecondFactorRequired step -> {
                Map<String, String> headers = new LinkedHashMap<>();
                // The password-only session is short-lived and carries no authority: the resolver
                // refuses to produce a principal from it, so it reaches the challenge and nothing else.
                headers.put("Set-Cookie",
                        SessionPrincipalResolver.cookieHeader(step.sessionToken(), 600, secureCookies));
                headers.put("Location", step.enrolmentNeeded() ? "/mfa-enrol" : "/mfa");
                headers.put("Content-Type", "text/html; charset=utf-8");
                yield new Dispatcher.Response(303, new InterfaceResource.Raw(""), headers);
            }
            case IdentityService.SignIn.Authenticated done -> new Dispatcher.Response(303,
                    new InterfaceResource.Raw(""), Map.of(
                            "Set-Cookie", SessionPrincipalResolver.cookieHeader(done.sessionToken(),
                                    28800, secureCookies),
                            "Location", WebUi.landing(),
                            "Content-Type", "text/html; charset=utf-8"));
            // The delay is applied by the caller waiting for the response rather than by a message
            // telling them to wait: SEC-SEC-005 wants an attacker degraded, not informed.
            case IdentityService.SignIn.Rejected rejected -> {
                if (rejected.retryAfterSeconds() > 0) {
                    try {
                        Thread.sleep(java.time.Duration.ofSeconds(
                                Math.min(5, rejected.retryAfterSeconds())));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                yield redirect("/sign-in?failed=1");
            }
        };
    }

    /** {@code GET /mfa-enrol}. Forced: an un-enrolled principal reaches nothing else. */
    public Dispatcher.Response enrolForm(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Optional<IdentityService.Session> session = resolver.sessionFor(request.headers());
        if (session.isEmpty()) {
            return redirect("/sign-in?expired=1");
        }
        if (session.orElseThrow().mfaEnrolled()) {
            return redirect("/mfa");
        }

        String secret = resolver.identity()
                .beginEnrolment(resolver.tenantId(), session.orElseThrow().principalId());
        // The account label is the identifier a person recognises among a dozen authenticator entries,
        // so it is the USERNAME. A truncated UUID was here first, under a comment saying a UUID is
        // unusable — the comment was right and the code did not follow it.
        String account = resolver.identity()
                .usernameOf(resolver.tenantId(), session.orElseThrow().principalId())
                .orElse("account");
        String uri = Totp.provisioningUri(messages.get("app.name"), account, secret);

        // The QR, or nothing. QrCode.encode returns empty when the content will not fit, and rendering
        // a symbol that cannot hold the URI would produce a code that scans to the wrong secret — a user
        // whose authenticator then generates rejected codes blames the authenticator.
        String qr = QrCode.encode(uri)
                .map(matrix -> "<div class=\"auth-qr\">"
                        + QrCode.toSvg(matrix, messages.get("auth.enrol.qrLabel"))
                        + "</div>")
                .orElse("");

        String body = "<h1 class=\"auth-title\">" + Html.text(messages.get("auth.enrol.title"))
                + "</h1><p class=\"auth-lede\">" + Html.text(messages.get("auth.enrol.lede")) + "</p>"
                + qr
                + "<div class=\"auth-secret\">"
                + "<span class=\"auth-secret-label\">"
                + Html.text(messages.get("auth.enrol.secretLabel")) + "</span>"
                + "<code class=\"auth-secret-value\">" + Html.text(group(secret)) + "</code>"
                + "</div>"
                + "<p class=\"auth-hint\"><a href=" + Html.attribute(uri) + ">"
                + Html.text(messages.get("auth.enrol.openInApp")) + "</a></p>"
                // The key stays visible beside the QR rather than behind a disclosure. A screen-reader
                // user cannot scan, and a desktop authenticator has nothing to point a camera at.
                + "<p class=\"auth-hint\">" + Html.text(messages.get("auth.enrol.keyAlso")) + "</p>"
                + (request.query().containsKey("failed")
                        ? AuthLayout.error(messages.get("auth.code.failed")) : "")
                + "<form method=\"post\" action=\"\" class=\"auth-form\">"
                + AuthLayout.firstField(messages, "code", "auth.code", "text", "", true, "one-time-code",
                        "auth.code.hint")
                + "<button class=\"auth-submit\" type=\"submit\">"
                + Html.text(messages.get("auth.enrol.action")) + "</button></form>";

        return html(AuthLayout.render(messages, "auth.enrol.title", body));
    }

    /** {@code POST /mfa-enrol}. */
    public Dispatcher.Response enrol(Dispatcher.Request request) throws Exception {
        Optional<IdentityService.Session> session = resolver.sessionFor(request.headers());
        if (session.isEmpty()) {
            return redirect("/sign-in?expired=1");
        }
        String code = parseForm(request.rawForm().orElse("")).getOrDefault("code", "");
        boolean confirmed = resolver.identity().confirmEnrolment(resolver.tenantId(),
                session.orElseThrow().principalId(), code);
        return redirect(confirmed ? "/mfa" : "/mfa-enrol?failed=1");
    }

    /** {@code GET /mfa}. */
    public Dispatcher.Response challengeForm(Dispatcher.Request request) {
        Messages messages = InterfaceResource.messagesFor(request);
        Optional<IdentityService.Session> session = resolver.sessionFor(request.headers());
        if (session.isEmpty()) {
            return redirect("/sign-in?expired=1");
        }
        if (!session.orElseThrow().mfaEnrolled()) {
            return redirect("/mfa-enrol");
        }
        if ("FULLY_AUTHENTICATED".equals(session.orElseThrow().factorState())) {
            return redirect(WebUi.landing());
        }

        String body = "<h1 class=\"auth-title\">" + Html.text(messages.get("auth.challenge.title"))
                + "</h1><p class=\"auth-lede\">" + Html.text(messages.get("auth.challenge.lede")) + "</p>"
                + (request.query().containsKey("failed")
                        ? AuthLayout.error(messages.get("auth.code.failed")) : "")
                + "<form method=\"post\" action=\"\" class=\"auth-form\">"
                + AuthLayout.firstField(messages, "code", "auth.code", "text", "", true, "one-time-code",
                        "auth.code.hint")
                + "<button class=\"auth-submit\" type=\"submit\">"
                + Html.text(messages.get("auth.challenge.action")) + "</button></form>"
                + "<p class=\"auth-alt\"><a href=\"/sign-out\">"
                + Html.text(messages.get("auth.challenge.abandon")) + "</a></p>";

        return html(AuthLayout.render(messages, "auth.challenge.title", body));
    }

    /** {@code POST /mfa}. */
    public Dispatcher.Response challenge(Dispatcher.Request request) throws Exception {
        String code = parseForm(request.rawForm().orElse("")).getOrDefault("code", "");
        Optional<String> token = SessionPrincipalResolver.cookie(request.headers(),
                SessionPrincipalResolver.COOKIE);
        if (token.isEmpty()) {
            return redirect("/sign-in?expired=1");
        }
        Optional<String> fresh = resolver.identity().completeSecondFactor(resolver.tenantId(),
                token.orElseThrow(), code, request.headers().get("x-forwarded-for"),
                request.headers().get("user-agent"));
        if (fresh.isEmpty()) {
            return redirect("/mfa?failed=1");
        }
        // A NEW token. SEC-SEC-009 regenerates on privilege change, so a first-factor token captured
        // earlier is worthless from here.
        return new Dispatcher.Response(303, new InterfaceResource.Raw(""), Map.of(
                "Set-Cookie", SessionPrincipalResolver.cookieHeader(fresh.orElseThrow(), 28800,
                        secureCookies),
                "Location", WebUi.landing(),
                "Content-Type", "text/html; charset=utf-8"));
    }

    /** {@code GET|POST /sign-out}. */
    public Dispatcher.Response signOut(Dispatcher.Request request) throws Exception {
        Optional<String> token = SessionPrincipalResolver.cookie(request.headers(),
                SessionPrincipalResolver.COOKIE);
        if (token.isPresent()) {
            resolver.identity().revoke(resolver.tenantId(), token.orElseThrow(), "USER_SIGNED_OUT");
        }
        return new Dispatcher.Response(303, new InterfaceResource.Raw(""), Map.of(
                "Set-Cookie", SessionPrincipalResolver.clearCookieHeader(),
                "Location", "/sign-in?signed_out=1",
                "Content-Type", "text/html; charset=utf-8"));
    }

    /**
     * {@code GET|POST /forgot-password}.
     *
     * <p>{@code SEC-SEC-016} requires that this "MUST NOT disclose whether the principal exists". So the
     * response is the same page with the same message whatever was submitted, and no branch above it
     * looks up the identifier before deciding what to render.
     *
     * <p>⚠ Email delivery is not implemented, so no token is issued yet. The page says so rather than
     * claiming a message was sent — a confirmation for an email nobody will receive is worse than an
     * honest gap, because the user waits for it.
     */
    public Dispatcher.Response forgotPassword(Dispatcher.Request request) {
        Messages messages = InterfaceResource.messagesFor(request);
        boolean submitted = "POST".equalsIgnoreCase(request.method());

        String body = "<h1 class=\"auth-title\">" + Html.text(messages.get("auth.forgot.title"))
                + "</h1><p class=\"auth-lede\">" + Html.text(messages.get("auth.forgot.lede")) + "</p>"
                + (submitted
                        ? "<div class=\"auth-notice\" role=\"status\">"
                                + Html.text(messages.get("auth.forgot.submitted")) + "</div>"
                                + "<div class=\"auth-error\" role=\"status\">"
                                + Html.text(messages.get("auth.forgot.notImplemented")) + "</div>"
                        : "<form method=\"post\" action=\"/forgot-password\" class=\"auth-form\">"
                                + AuthLayout.firstField(messages, "identifier", "auth.identifier", "text",
                                        "", true, "username", null)
                                + "<button class=\"auth-submit\" type=\"submit\">"
                                + Html.text(messages.get("auth.forgot.action")) + "</button></form>")
                + "<p class=\"auth-alt\"><a href=\"/sign-in\">"
                + Html.text(messages.get("auth.forgot.back")) + "</a></p>";

        return html(AuthLayout.render(messages, "auth.forgot.title", body));
    }

    // ----------------------------------------------------------------------------------------------

    /** Groups a base32 secret into fours, because it is transcribed by hand. */
    private static String group(String secret) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < secret.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append(' ');
            }
            out.append(secret.charAt(i));
        }
        return out.toString();
    }

    private static Dispatcher.Response html(String markup) {
        return new Dispatcher.Response(200, new InterfaceResource.Raw(markup),
                Map.of("Content-Type", "text/html; charset=utf-8"));
    }

    private static Dispatcher.Response redirect(String location) {
        return new Dispatcher.Response(303, new InterfaceResource.Raw(""), Map.of(
                "Location", location, "Content-Type", "text/html; charset=utf-8"));
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            form.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value.replace('+', ' '), StandardCharsets.UTF_8));
        }
        return form;
    }

    /** Exposed so the composition root can report what the bootstrap did without importing it twice. */
    public static String bootstrapVariable() {
        return CredentialBootstrap.PASSWORD_VARIABLE;
    }
}
