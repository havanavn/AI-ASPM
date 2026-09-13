package aspm.app.ui;

import aspm.app.identity.FederatedSignIn;
import aspm.app.identity.SessionPrincipalResolver;
import aspm.app.runtime.Dispatcher;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The two class G routes of federated sign-in. {@code PRD-IAM-001}, {@code SEC-SEC-002}, {@code SEC-SEC-007}.
 *
 * <ul>
 *   <li>{@code GET /auth/{provider}/start} — begins the handshake and sends the browser to the provider.
 *   <li>{@code GET /auth/callback} — receives the code, completes the handshake, sets the session cookie
 *       and sends the browser on: to the landing page, to the local second-factor challenge, or back
 *       to sign-in with one undifferentiated failure message.
 * </ul>
 *
 * <p>One message for every failure, as on the password form: the reason code is recorded in
 * {@code authentication_attempt} for the platform and never shown to the browser, because "your
 * account is not provisioned" and "the token signature was invalid" are different things to an
 * attacker and the same thing to a user — try again, or ask an administrator.
 */
public final class FederationPages {

    /** Null when the deployment has no ASPM_PUBLIC_BASE_URL: the routes exist and refuse. */
    private final FederatedSignIn federation;
    private final boolean secureCookies;

    public FederationPages(FederatedSignIn federation, boolean secureCookies) {
        this.federation = federation;
        this.secureCookies = secureCookies;
    }

    /** {@code GET /auth/{provider}/start}. */
    public Dispatcher.Response start(Dispatcher.Request request) throws SQLException {
        String code = request.pathVariables().getOrDefault("provider", "");
        if (federation == null || !code.matches("[a-z][a-z0-9-]{1,31}")) {
            return redirect("/sign-in?failed=1");
        }
        Optional<FederatedSignIn.Start> start = federation.begin(code,
                Optional.ofNullable(request.query().get("next")));
        return start.map(s -> redirect(s.authorizationUrl())).orElseGet(() -> redirect("/sign-in?failed=1"));
    }

    /** {@code GET /auth/callback}. */
    public Dispatcher.Response callback(Dispatcher.Request request) throws SQLException {
        // A provider that refused (the person cancelled, or consent was denied) returns `error`
        // instead of `code`. Same page, same message, no detail: the provider's text is not ours to show.
        if (federation == null || request.query().containsKey("error")) {
            return redirect("/sign-in?failed=1");
        }
        FederatedSignIn.Outcome outcome = federation.complete(request.query().get("state"), request.query().get("code"),
                request.headers().get("x-forwarded-for"), request.headers().get("user-agent"));
        return switch (outcome) {
            case FederatedSignIn.Outcome.Rejected rejected -> redirect("/sign-in?failed=1");
            case FederatedSignIn.Outcome.Established done -> {
                Map<String, String> headers = new LinkedHashMap<>();
                String location;
                if (done.fullyAuthenticated()) {
                    headers.put("Set-Cookie", SessionPrincipalResolver.cookieHeader(done.sessionToken(), 28800, secureCookies));
                    location = done.redirectTarget().orElse(WebUi.landing());
                } else {
                    headers.put("Set-Cookie", SessionPrincipalResolver.cookieHeader(done.sessionToken(), 600, secureCookies));
                    location = done.enrolmentNeeded() ? "/mfa-enrol" : "/mfa";
                }
                headers.put("Location", location);
                headers.put("Content-Type", "text/html; charset=utf-8");
                headers.put("Cache-Control", "no-store");
                yield new Dispatcher.Response(303, new InterfaceResource.Raw(""), headers);
            }
        };
    }

    private static Dispatcher.Response redirect(String location) {
        return new Dispatcher.Response(303, new InterfaceResource.Raw(""), Map.of(
                "Location", location,
                "Content-Type", "text/html; charset=utf-8",
                "Cache-Control", "no-store"));
    }
}
