package aspm.app.identity;

import aspm.app.runtime.Principal;
import aspm.app.runtime.PrincipalResolver;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The real principal resolver. ADR-059, replacing the development header resolver.
 *
 * <p>A session cookie is resolved to a principal on <b>every request</b>, and the permission set is
 * resolved with it rather than copied into the session at sign-in. {@code SEC-SEC-011} requires
 * revocation to take effect within 60 seconds "including cached authorization state", and a permission
 * set cached in a session <i>is</i> cached authorization state that revocation does not reach.
 *
 * <h2>A password-only session resolves to no principal</h2>
 *
 * <p>{@link #resolve} returns empty for a session that has not completed the second factor. That is what
 * makes forced enrolment enforceable rather than advisory: every authenticated route asks this resolver,
 * so a half-authenticated session reaches none of them. The challenge and enrolment pages are class G and
 * read the cookie themselves, which is why they can be reached at all.
 */
public final class SessionPrincipalResolver implements PrincipalResolver {

    public static final String COOKIE = "aspm_session";

    private final IdentityService identity;
    private final UUID tenantId;

    /**
     * @param tenantId the tenant this deployment serves. Single-tenant for now; a multi-tenant
     *     deployment resolves it from the host, never from a request field ({@code SEC-TEN-004})
     */
    public SessionPrincipalResolver(DataSource dataSource, UUID tenantId) {
        this.identity = new IdentityService(Objects.requireNonNull(dataSource));
        this.tenantId = Objects.requireNonNull(tenantId, "a tenant is required");
    }

    @Override
    public Optional<Principal> resolve(Map<String, String> headers) {
        Optional<String> token = cookie(headers, COOKIE);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        try {
            Optional<IdentityService.Session> session = identity.session(tenantId, token.orElseThrow());
            if (session.isEmpty()) {
                return Optional.empty();
            }
            if (!"FULLY_AUTHENTICATED".equals(session.orElseThrow().factorState())) {
                // Not an error and not an anonymous request: a real session that may reach the second
                // factor and nothing else. Returning a Principal here would make enrolment optional.
                return Optional.empty();
            }
            return identity.principal(tenantId, session.orElseThrow());
        } catch (SQLException e) {
            // A resolver that cannot reach the store fails CLOSED. Returning empty makes the request
            // unauthenticated, which is a redirect to sign-in rather than an authorization bypass.
            System.getLogger("aspm.identity").log(System.Logger.Level.ERROR,
                    "session resolution failed; treating the request as unauthenticated", e);
            return Optional.empty();
        }
    }

    /** The session behind a request whatever its factor state, for the challenge and enrolment pages. */
    public Optional<IdentityService.Session> sessionFor(Map<String, String> headers) {
        try {
            Optional<String> token = cookie(headers, COOKIE);
            return token.isEmpty() ? Optional.empty() : identity.session(tenantId, token.orElseThrow());
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    public IdentityService identity() {
        return identity;
    }

    public UUID tenantId() {
        return tenantId;
    }

    @Override
    public String description() {
        return "Session cookie resolved against principal_session on every request, with permissions "
                + "resolved per request so revocation reaches cached authorization state (SEC-SEC-011). "
                + "Local password plus TOTP (ADR-059, narrowing ADR-004).";
    }

    /** Reads one cookie by name. */
    public static Optional<String> cookie(Map<String, String> headers, String name) {
        String header = headers.get("cookie");
        if (header == null) {
            return Optional.empty();
        }
        for (String pair : header.split(";", -1)) {
            String[] parts = pair.strip().split("=", 2);
            if (parts.length == 2 && name.equals(parts[0]) && !parts[1].isBlank()) {
                return Optional.of(parts[1]);
            }
        }
        return Optional.empty();
    }

    /** The cookie attributes {@code SEC-SEC-013} requires, in one place so no call site omits one. */
    public static String cookieHeader(String token, int maxAgeSeconds, boolean secure) {
        return COOKIE + "=" + token
                + "; Path=/"
                + "; HttpOnly"
                // SameSite=Strict rather than Lax: SEC-SEC-013 permits Lax and this platform has no
                // cross-site entry point that needs it, so the stricter value costs nothing.
                + "; SameSite=Strict"
                + (secure ? "; Secure" : "")
                + "; Max-Age=" + maxAgeSeconds;
    }

    public static String clearCookieHeader() {
        return COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0";
    }
}
