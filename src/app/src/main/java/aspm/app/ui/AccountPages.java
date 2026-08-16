package aspm.app.ui;

import aspm.app.identity.AccountService;
import aspm.app.identity.IdentityService;
import aspm.app.identity.PasswordPolicy;
import aspm.app.identity.SessionPrincipalResolver;
import aspm.app.runtime.Dispatcher;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * What a signed-in person can do to their own account. {@code SEC-SEC-006}, {@code SEC-SEC-012},
 * {@code SEC-SEC-016}, ADR-059.
 *
 * <ul>
 *   <li>{@code GET /account} — the account panel: two-factor state, password state, live sessions.
 *   <li>{@code GET|POST /change-password} — self-service change, and the surface a forced change lands
 *       on. The dispatcher redirects every other route here while {@code must_change_password} is set.
 *   <li>{@code POST /account/sessions/revoke} — terminate one of the caller's own sessions.
 *   <li>{@code GET|POST /step-up} — re-present the second factor for a class C or class E operation.
 * </ul>
 *
 * <h2>Why every route here is annotated class G, and why that is a deviation worth reading</h2>
 *
 * <p>These operations are authorized by <b>identity</b>, not by permission: the subject is the caller
 * themselves. The seven classes of ADR-036 have no shape for that — every non-G class must name a
 * permission from the product-fixed catalogue, and {@link aspm.app.api.OperationRegistry} refuses to
 * construct one without it.
 *
 * <p>Naming a catalogue permission here would be worse than the deviation. A principal whose role omitted
 * it could not change their own password — a lockout an administrator would create by accident, on the one
 * surface that exists to recover from lockouts. And {@code /change-password} must be reachable by a
 * principal holding <b>no role at all</b>, because the deployment bootstrap creates exactly that principal
 * and forces a change.
 *
 * <p>So the gate is the session, checked inside each handler, which is the precedent {@code /mfa} and
 * {@code /mfa-enrol} already set. <b>The cost is real and is not hidden:</b> class G declares
 * {@code Classification.PUBLIC} and {@code AuditLevel.FAILURES_ONLY}, and {@code GET /account} discloses
 * the caller's own session list including source addresses. The classification understates the page. It is
 * recorded in {@code deploy/README.md} as a known deviation rather than resolved by inventing an eighth
 * class, because ADR-036 fixed the number at seven and relitigating a ratified decision to make one page
 * tidier is the wrong trade.
 */
public final class AccountPages {

    private final SessionPrincipalResolver resolver;
    private final AccountService accounts;

    /**
     * No {@code secureCookies} flag, unlike {@link AuthPages}. Nothing here issues or clears a cookie: a
     * password change keeps the caller's session, and terminating the current session delegates to
     * {@code /sign-out}, which owns both the revocation and the cookie. Carrying the flag anyway would
     * be an unused field that reads as a cookie-setting path somebody forgot to write.
     */
    public AccountPages(DataSource dataSource, UUID tenantId) {
        this.resolver = new SessionPrincipalResolver(Objects.requireNonNull(dataSource), tenantId);
        this.accounts = new AccountService(dataSource);
    }

    // ----------------------------------------------------------------------------------------------

    /** {@code GET /account}. */
    public Dispatcher.Response account(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Optional<IdentityService.Session> found = fullyAuthenticated(request);
        if (found.isEmpty()) {
            return redirect("/sign-in?expired=1");
        }
        IdentityService.Session session = found.orElseThrow();

        Optional<AccountService.UserRow> me = accounts.user(resolver.tenantId(), session.principalId());
        List<Map<String, Object>> sessions = resolver.identity()
                .ownSessions(resolver.tenantId(), session.principalId());

        StringBuilder body = new StringBuilder(4096);

        if (request.query().containsKey("changed")) {
            body.append(notice(messages.get("account.password.changed")));
        }
        if (request.query().containsKey("revoked")) {
            body.append(notice(messages.get("account.sessions.revoked")));
        }

        body.append("<div class=\"grid grid-2 mb-6\">")
                .append(identityCard(messages, me, session))
                .append(credentialCard(messages, me))
                .append("</div>");

        body.append(sessionCard(messages, sessions, session.id()));

        // The shell filters navigation by permission, so it needs the caller's permissions — and this
        // route is class G, which means the dispatcher resolved no principal for it. Resolved here through
        // the same method the dispatcher's resolver uses, rather than passing Optional.empty() and getting
        // a page with an empty sidebar: a principal who reaches their own account page and finds no
        // navigation would reasonably conclude the platform had broken.
        Optional<aspm.app.runtime.Principal> caller =
                resolver.identity().principal(resolver.tenantId(), session);

        Page.Context context = Page.Context.of("account.title", "/account", caller)
                .withSubtitle("account.subtitle")
                .withBreadcrumbs(List.of(
                        new Page.Crumb(messages.get("scope.root"), Optional.of("/overview")),
                        new Page.Crumb(messages.get("account.title"), Optional.empty())));

        return html(Page.render(messages, context, body.toString()));
    }

    /** {@code GET /change-password}. Also the landing page of a forced change. */
    public Dispatcher.Response changePasswordForm(Dispatcher.Request request) throws Exception {
        Messages messages = InterfaceResource.messagesFor(request);
        Optional<IdentityService.Session> found = fullyAuthenticated(request);
        if (found.isEmpty()) {
            return redirect("/sign-in?expired=1");
        }
        boolean required = request.query().containsKey("required")
                || found.orElseThrow().mustChangePassword();
        PasswordPolicy.Settings policy = accounts.policy(resolver.tenantId());

        StringBuilder body = new StringBuilder(2048);
        body.append("<h1 class=\"auth-title\">")
                .append(Html.text(messages.get("account.change.title"))).append("</h1>");
        body.append("<p class=\"auth-lede\">")
                .append(Html.text(messages.get(required
                        ? "account.change.ledeRequired" : "account.change.lede")))
                .append("</p>");

        // The failures from the previous attempt, each translated to what to do about it. A form that
        // says only "password rejected" makes the user guess, and they guess by adding a digit.
        for (String failure : request.query().getOrDefault("failed", "").isBlank()
                ? List.<String>of()
                : List.of(request.query().get("failed").split(","))) {
            body.append(AuthLayout.error(policyMessage(messages, failure, policy)));
        }
        if (request.query().containsKey("wrong")) {
            body.append(AuthLayout.error(messages.get("account.change.currentWrong")));
        }
        // The thin-corpus warning is NOT shown here. PP-1 says a control whose coverage is inadequate
        // must not look adequate — but the audience for that is whoever can fix it, and this page's
        // audience cannot. Telling a developer that the breach corpus holds twenty-five entries tells them
        // how weak one of the platform's own controls is and gives them nothing to do about it. It is on
        // /security-policy, beside the switch that turns the check on, where an administrator sees it.

        body.append("<form method=\"post\" action=\"/change-password\" class=\"auth-form\">")
                .append(AuthLayout.field(messages, "current", "account.change.current", "password", "",
                        true, "current-password", null))
                .append(AuthLayout.field(messages, "candidate", "account.change.candidate", "password",
                        "", true, "new-password", null))
                .append("<p class=\"auth-hint\">")
                .append(Html.text(messages.get("account.change.rule", policy.minimumLength())))
                .append("</p>")
                .append("<button class=\"auth-submit\" type=\"submit\">")
                .append(Html.text(messages.get("account.change.action"))).append("</button></form>");

        // No way out while a change is required. Offering "back to the overview" here would offer a link
        // the dispatcher immediately redirects back, which reads as a broken page rather than a rule.
        body.append("<p class=\"auth-alt\">")
                .append(required
                        ? "<a href=\"/sign-out\">" + Html.text(messages.get("account.change.signOut"))
                                + "</a>"
                        : "<a href=\"/account\">" + Html.text(messages.get("account.change.back"))
                                + "</a>")
                .append("</p>");

        // The authentication layout, not the shell: a forced change is a surface with one exit, and a
        // sidebar full of links the dispatcher will refuse is a page that looks broken.
        return html(AuthLayout.render(messages, "account.change.title", body.toString()));
    }

    /** {@code POST /change-password}. */
    public Dispatcher.Response changePassword(Dispatcher.Request request) throws Exception {
        Optional<IdentityService.Session> found = fullyAuthenticated(request);
        if (found.isEmpty()) {
            return redirect("/sign-in?expired=1");
        }
        IdentityService.Session session = found.orElseThrow();
        Map<String, String> form = parseForm(request.rawForm().orElse(""));

        AccountService.ChangeOutcome outcome = accounts.changeOwnPassword(resolver.tenantId(),
                session.principalId(), session.id(),
                form.getOrDefault("current", "").toCharArray(),
                form.getOrDefault("candidate", "").toCharArray());

        return switch (outcome) {
            case AccountService.ChangeOutcome.Accepted accepted -> redirect(
                    "/account?changed=" + accepted.otherSessionsRevoked());
            case AccountService.ChangeOutcome.CurrentPasswordWrong ignored ->
                    redirect("/change-password?wrong=1");
            case AccountService.ChangeOutcome.PolicyFailed failed -> redirect(
                    "/change-password?failed="
                            + java.net.URLEncoder.encode(String.join(",", failed.failures()),
                                    StandardCharsets.UTF_8));
        };
    }

    /**
     * {@code POST /account/sessions/revoke}. {@code SEC-SEC-012}.
     *
     * <p>The session identifier arrives from the form and is <b>not trusted as an authorization</b>: the
     * principal is taken from the cookie and the update carries both, so a caller who edits the hidden
     * field revokes nothing. Product principle 4 — scope is derived, never asserted by the client — and a
     * session list is a scope like any other.
     */
    public Dispatcher.Response revokeSession(Dispatcher.Request request) throws Exception {
        Optional<IdentityService.Session> found = fullyAuthenticated(request);
        if (found.isEmpty()) {
            return redirect("/sign-in?expired=1");
        }
        IdentityService.Session session = found.orElseThrow();
        String raw = parseForm(request.rawForm().orElse("")).getOrDefault("session", "");
        UUID target;
        try {
            target = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return redirect("/account");
        }

        // Revoking the session you are using is signing out. Delegated to the sign-out route rather than
        // repeated here: that route revokes the token AND clears the cookie, and a second implementation
        // that revoked without clearing would leave the browser presenting a dead token on every request.
        if (target.equals(session.id())) {
            return redirect("/sign-out");
        }
        boolean revoked = resolver.identity()
                .revokeOwnSession(resolver.tenantId(), session.principalId(), target);
        return redirect(revoked ? "/account?revoked=1" : "/account");
    }

    // ----------------------------------------------------------------------------------------------

    /** {@code GET /step-up}. */
    public Dispatcher.Response stepUpForm(Dispatcher.Request request) {
        Messages messages = InterfaceResource.messagesFor(request);
        Optional<IdentityService.Session> found = fullyAuthenticated(request);
        if (found.isEmpty()) {
            return redirect("/sign-in?expired=1");
        }
        String next = safeNext(request.query().get("next"));

        String body = "<h1 class=\"auth-title\">" + Html.text(messages.get("account.stepUp.title"))
                + "</h1><p class=\"auth-lede\">" + Html.text(messages.get("account.stepUp.lede"))
                + "</p>"
                + (request.query().containsKey("failed")
                        ? AuthLayout.error(messages.get("account.stepUp.failed")) : "")
                + "<form method=\"post\" action=\"/step-up\" class=\"auth-form\">"
                + "<input type=\"hidden\" name=\"next\" value=" + Html.attribute(next) + ">"
                + AuthLayout.field(messages, "code", "auth.code", "text", "", true, "one-time-code",
                        "account.stepUp.hint")
                + "<button class=\"auth-submit\" type=\"submit\">"
                + Html.text(messages.get("account.stepUp.action")) + "</button></form>"
                + "<p class=\"auth-alt\"><a href=\"/overview\">"
                + Html.text(messages.get("account.stepUp.abandon")) + "</a></p>";

        return html(AuthLayout.render(messages, "account.stepUp.title", body));
    }

    /** {@code POST /step-up}. */
    public Dispatcher.Response stepUp(Dispatcher.Request request) throws Exception {
        Optional<String> token = SessionPrincipalResolver.cookie(request.headers(),
                SessionPrincipalResolver.COOKIE);
        if (token.isEmpty()) {
            return redirect("/sign-in?expired=1");
        }
        Map<String, String> form = parseForm(request.rawForm().orElse(""));
        String next = safeNext(form.get("next"));
        boolean elevated = resolver.identity().recordStepUp(resolver.tenantId(), token.orElseThrow(),
                form.getOrDefault("code", ""), request.headers().get("x-forwarded-for"),
                request.headers().get("user-agent"));
        if (!elevated) {
            return redirect("/step-up?failed=1&next="
                    + java.net.URLEncoder.encode(next, StandardCharsets.UTF_8));
        }
        return redirect(next);
    }

    /**
     * Constrains a return path to somewhere inside this interface.
     *
     * <p>An unchecked {@code next} on an authentication surface is an open redirect, and this is the
     * surface where a caller has just been told to prove who they are — the one place a redirect to an
     * attacker's page is most likely to be trusted. The test is a whitelist of shape, not a blacklist of
     * hosts: a protocol-relative {@code //evil.example} starts with a slash and is a different origin, so
     * a second leading slash is refused explicitly.
     */
    static String safeNext(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return WebUi.landing();
        }
        String decoded = URLDecoder.decode(candidate, StandardCharsets.UTF_8);
        // The interface is mounted at the root, so "local" is now "starts with one slash and not two".
        // The SECOND test is the one doing the work: //evil.example is protocol-relative, a browser
        // reads it as an absolute URL to another host, and it passes a startsWith("/") check. When the
        // permitted prefix was /ui/ that shape could not arise; at the root it can, so it is refused
        // explicitly rather than by the shape of the prefix.
        boolean local = decoded.startsWith("/")
                && !decoded.startsWith("//")
                && decoded.indexOf('\\') < 0
                && decoded.indexOf(':') < 0
                // A newline in a Location header splits the response. Refused here as well as escaped
                // later, because two controls on a response-splitting vector is the right number.
                && decoded.chars().noneMatch(c -> c == '\r' || c == '\n');
        return local ? decoded : WebUi.landing();
    }

    // ----------------------------------------------------------------------------------------------

    private static String identityCard(Messages messages, Optional<AccountService.UserRow> me,
            IdentityService.Session session) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(messages.get("account.identity.title")))
                .append("</h2></div><div class=\"card-body col gap-3\">");
        out.append(definition(messages.get("account.identity.username"),
                me.map(AccountService.UserRow::username).orElse("—")));
        out.append(definition(messages.get("account.identity.email"),
                me.map(AccountService.UserRow::email).filter(e -> e != null && !e.isBlank())
                        .orElse(messages.get("account.identity.noEmail"))));
        out.append(definition(messages.get("account.identity.displayName"),
                me.map(AccountService.UserRow::displayName).orElse("—")));
        out.append(definition(messages.get("account.identity.roles"),
                me.map(u -> u.roleCodes().isEmpty()
                                ? messages.get("account.identity.noRoles")
                                : String.join(", ", u.roleCodes()))
                        .orElse("—")));
        out.append("<div class=\"row gap-2 wrap\">")
                .append(session.mfaEnrolled()
                        ? pill("ok", messages.get("account.identity.mfaOn"))
                        : pill("danger", messages.get("account.identity.mfaOff")))
                .append(session.stepUpFresh()
                        ? pill("info", messages.get("account.identity.elevated"))
                        : "")
                .append("</div>");
        out.append("</div></section>");
        return out.toString();
    }

    /**
     * The credential panel.
     *
     * <p><b>It no longer lists the tenant's credential policy.</b> It used to show the minimum length,
     * reuse history, breach-check setting and expiry to every caller — which is tenant security
     * configuration, and a principal with no administrative permission has no business reading it. The
     * reuse depth and expiry interval in particular tell an attacker who has one credential how long it is
     * good for and how many of a person's previous passwords are off the table.
     *
     * <p>What stays is the caller's own <b>state</b>: whether a change is required, and where to make one.
     * The one policy value that survives is the minimum length, and it appears on the change form rather
     * than here — a person choosing a password needs the rule that will reject them, and withholding it
     * produces a rejection they cannot act on. That is the whole of the policy they see.
     */
    private static String credentialCard(Messages messages, Optional<AccountService.UserRow> me) {
        boolean mustChange = me.map(AccountService.UserRow::mustChangePassword).orElse(false);
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(messages.get("account.credential.title")))
                .append("</h2></div><div class=\"card-body col gap-3\">");
        if (mustChange) {
            out.append("<div class=\"auth-error\" role=\"alert\">")
                    .append(Html.text(messages.get("account.credential.mustChange")))
                    .append("</div>");
        }
        out.append(definition(messages.get("account.credential.state"),
                messages.get(mustChange
                        ? "account.credential.stateMustChange" : "account.credential.stateOk")));
        out.append("<p class=\"fs-12 muted\">")
                .append(Html.text(messages.get("account.credential.policyElsewhere")))
                .append("</p>");
        out.append("<div class=\"form-actions\"><a class=\"btn btn-primary btn-sm\" "
                        + "href=\"/change-password\">")
                .append(Html.text(messages.get("account.credential.change")))
                .append("</a></div>");
        out.append("</div></section>");
        return out.toString();
    }

    private static String sessionCard(Messages messages, List<Map<String, Object>> sessions,
            UUID currentId) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2 class=\"card-title\">")
                .append(Html.text(messages.get("account.sessions.title")))
                .append("</h2><p class=\"fs-12 muted\">")
                .append(Html.text(messages.get("account.sessions.lede")))
                .append("</p></div><div class=\"card-body\">");
        if (sessions.isEmpty()) {
            // Cannot happen while the caller is reading the page — they hold one. Rendered anyway,
            // because a panel with no empty state is a panel that renders a bare border on the day the
            // impossible happens.
            out.append(StateRenderer.state(messages,
                    aspm.module.insight.domain.PresentationState.EMPTY_NO_DATA,
                    Optional.of(messages.get("account.sessions.none"))));
        }
        for (Map<String, Object> session : sessions) {
            boolean current = String.valueOf(session.get("id")).equals(currentId.toString());
            out.append("<div class=\"session-row\"><div class=\"session-meta\">")
                    .append("<span class=\"row gap-2 wrap\">")
                    .append("<span class=\"fs-13\">")
                    .append(Html.text(String.valueOf(session.get("source_address"))))
                    .append("</span>")
                    .append(current ? pill("info", messages.get("account.sessions.current")) : "")
                    .append("PASSWORD_ONLY".equals(session.get("factor_state"))
                            ? pill("warn", messages.get("account.sessions.halfAuthenticated")) : "")
                    .append("</span>")
                    .append("<span class=\"fs-11 muted\">")
                    .append(Html.text(messages.get("account.sessions.times",
                            String.valueOf(session.get("last_seen_at")),
                            String.valueOf(session.get("expires_at")))))
                    .append("</span>")
                    .append("<span class=\"session-agent\">")
                    .append(Html.text(String.valueOf(session.get("user_agent"))))
                    .append("</span></div>")
                    .append("<form method=\"post\" action=\"/account/sessions/revoke\">")
                    .append("<input type=\"hidden\" name=\"session\" value=")
                    .append(Html.attribute(String.valueOf(session.get("id")))).append(">")
                    .append("<button class=\"btn btn-sm\" type=\"submit\">")
                    .append(Html.text(messages.get(current
                            ? "account.sessions.signOut" : "account.sessions.terminate")))
                    .append("</button></form></div>");
        }
        out.append("</div></section>");
        return out.toString();
    }

    /** One policy failure code, translated into the remedy. */
    private static String policyMessage(Messages messages, String failure,
            PasswordPolicy.Settings policy) {
        String code = failure.contains(":") ? failure.substring(0, failure.indexOf(':')) : failure;
        return switch (code) {
            case "TOO_SHORT" -> messages.get("account.change.tooShort", policy.minimumLength());
            case "TOO_LONG" -> messages.get("account.change.tooLong", policy.maximumLength());
            case "CONTAINS_USERNAME" -> messages.get("account.change.containsUsername");
            case "CONTAINS_EMAIL" -> messages.get("account.change.containsEmail");
            case "BREACHED" -> messages.get("account.change.breached");
            case "REUSED" -> messages.get("account.change.reused", policy.reuseHistory());
            // An unrecognised code is reported as itself rather than swallowed. A silent default here
            // would make a new policy rule fail with no message at all.
            default -> messages.get("account.change.rejected", code);
        };
    }

    private static String definition(String label, String value) {
        return "<div class=\"row between gap-3\">"
                + "<span class=\"fs-12 muted\">" + Html.text(label) + "</span>"
                + "<span class=\"fs-13\">" + Html.text(value) + "</span></div>";
    }

    private static String pill(String tone, String label) {
        return "<span class=\"pill pill-" + tone + "\">" + Html.text(label) + "</span>";
    }

    private static String notice(String message) {
        return "<div class=\"banner\" role=\"status\">" + Html.text(message) + "</div>";
    }

    /**
     * The session behind the request, and only if it has completed the second factor.
     *
     * <p>The factor-state check is the reason this is a method rather than a call to
     * {@code resolver.sessionFor}: these pages are class G, so the dispatcher performs no authentication
     * for them, and a PASSWORD_ONLY session reaching the account panel would read the caller's session
     * list before they had finished proving who they are.
     */
    private Optional<IdentityService.Session> fullyAuthenticated(Dispatcher.Request request) {
        return resolver.sessionFor(request.headers())
                .filter(session -> "FULLY_AUTHENTICATED".equals(session.factorState()));
    }

    private static Dispatcher.Response html(String markup) {
        return new Dispatcher.Response(200, new InterfaceResource.Raw(markup),
                Map.of("Content-Type", "text/html; charset=utf-8"));
    }

    private static Dispatcher.Response redirect(String location) {
        return new Dispatcher.Response(303, new InterfaceResource.Raw(""), Map.of(
                "Location", location, "Content-Type", "text/html; charset=utf-8"));
    }

    static Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            // Last wins, which is what makes Forms.checkbox work: an unchecked box submits only its
            // paired hidden "false", a checked one submits "false" then "true".
            form.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value.replace('+', ' '), StandardCharsets.UTF_8));
        }
        return form;
    }
}
