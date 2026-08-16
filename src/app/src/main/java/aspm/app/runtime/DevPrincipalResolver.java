package aspm.app.runtime;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A development-only principal resolver, and a deliberate, guarded gap against ADR-004.
 *
 * <p>ADR-004 requires OIDC/OAuth2 for humans, sender-constrained credentials for services, and
 * <b>no bearer API keys</b>. This resolver satisfies none of that: it reads the principal from request
 * headers, which is precisely what {@code SEC-TEN-004} forbids a tenant to be derived from.
 *
 * <h2>Why it exists and how it is contained</h2>
 *
 * <p>The alternative was shipping no runnable endpoint, and an endpoint nobody can call is not evidence
 * that the enforcement above it works. It exists so the dispatcher, the authorization gate and the
 * tenant-scoped access path can be exercised end to end before an identity provider is wired.
 *
 * <p>It is contained by construction rather than by intent:
 *
 * <ul>
 *   <li>{@link #enabledFrom} returns empty unless {@code ASPM_DEV_AUTH=true} is set explicitly, and a
 *       server with no resolver refuses to start rather than serving unauthenticated — deny by default,
 *       expressed as an absence.
 *   <li>It <b>refuses to construct</b> where {@code ASPM_ENVIRONMENT} is anything but {@code development}.
 *       A flag that can be turned on in production is a flag that will be, so the refusal is here rather
 *       than in a deployment checklist.
 *   <li>It states what it is at startup, so an operator reading a log sees it rather than inferring it.
 * </ul>
 *
 * <p>⚠ <b>This is the largest gap in the running system</b>, and it is what has to be replaced first.
 */
public final class DevPrincipalResolver implements PrincipalResolver {

    public static final String ENABLE_VARIABLE = "ASPM_DEV_AUTH";
    public static final String ENVIRONMENT_VARIABLE = "ASPM_ENVIRONMENT";

    private DevPrincipalResolver() {
    }

    /**
     * Builds the resolver, but only in an explicitly configured development environment.
     *
     * @return a resolver only where development authentication is explicitly enabled in an explicitly
     *     development environment
     * @throws IllegalStateException where it is enabled outside development. Failing to start is correct:
     *     the alternative is a production instance that authenticates by header
     */
    public static Optional<PrincipalResolver> enabledFrom(Map<String, String> environment) {
        if (!"true".equalsIgnoreCase(environment.getOrDefault(ENABLE_VARIABLE, ""))) {
            return Optional.empty();
        }
        String env = environment.getOrDefault(ENVIRONMENT_VARIABLE, "").toLowerCase(Locale.ROOT);
        if (!"development".equals(env)) {
            throw new IllegalStateException(
                    ENABLE_VARIABLE + " is enabled and " + ENVIRONMENT_VARIABLE + " is '" + env
                            + "'. Header-supplied identity is not authentication (ADR-004), and a tenant "
                            + "taken from a header is what SEC-TEN-004 exists to forbid. Refusing to start "
                            + "is the correct outcome; a warning would be a production instance that "
                            + "authenticates by header.");
        }
        return Optional.of(new DevPrincipalResolver());
    }

    /** The cookie name the browser session uses. Same guard, different transport. */
    public static final String COOKIE = "aspm_dev";

    @Override
    public Optional<Principal> resolve(Map<String, String> headers) {
        // A browser cannot set x-dev-* headers, so the interface needs a session. It carries the same
        // asserted identity through a cookie and is exactly as unauthenticated as the header form —
        // the environment guard above is what contains both.
        Map<String, String> effective = headers.containsKey("x-dev-tenant")
                ? headers
                : fromCookie(headers);
        String tenant = effective.get("x-dev-tenant");
        String principal = effective.get("x-dev-principal");
        if (tenant == null || principal == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Principal(
                    UUID.fromString(tenant),
                    UUID.fromString(principal),
                    split(effective.get("x-dev-permissions")),
                    split(effective.get("x-dev-scope")).stream().map(UUID::fromString)
                            .collect(Collectors.toUnmodifiableSet()),
                    "true".equalsIgnoreCase(effective.getOrDefault("x-dev-step-up", "")),
                    // Class F operations are service-credential only (ADR-004, ADR-023). A development
                    // header cannot make a credential sender-constrained, so this flag only lets the
                    // ingestion path be exercised — it is the same gap as the rest of this resolver.
                    "true".equalsIgnoreCase(effective.getOrDefault("x-dev-service", "")),
                    // Never forced to change a credential: this resolver has no credential to change.
                    // A header-asserted principal that could be sent to the change-password page would
                    // be sent there on every request, because nothing it does can clear the flag.
                    false));
        } catch (IllegalArgumentException e) {
            // A malformed credential is a FAILED authentication, not an anonymous request. The message is
            // for the server log; the dispatcher decides what a client is told (PRD-UIX-025).
            throw new SecurityException("malformed development credential", e);
        }
    }

    /**
     * Decodes the development session cookie into the same shape the headers use.
     *
     * <p>Format is {@code tenant|principal|permissions|scope}, unsigned and unencrypted. That is
     * acceptable only because the whole mechanism is unauthenticated by construction: signing a cookie
     * whose contents the caller may choose freely would add a control that protects nothing while making
     * it look protected, which is worse than the plain form.
     */
    private static Map<String, String> fromCookie(Map<String, String> headers) {
        String header = headers.get("cookie");
        if (header == null) {
            return Map.of();
        }
        for (String pair : header.split(";", -1)) {
            String[] parts = pair.strip().split("=", 2);
            if (parts.length != 2 || !COOKIE.equals(parts[0])) {
                continue;
            }
            String[] fields = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
                    java.nio.charset.StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 4) {
                return Map.of();
            }
            return Map.of("x-dev-tenant", fields[0], "x-dev-principal", fields[1],
                    "x-dev-permissions", fields[2], "x-dev-scope", fields[3]);
        }
        return Map.of();
    }

    /** Encodes a development session. Used by the sign-in page and by nothing else. */
    public static String encodeSession(String tenant, String principal, String permissions,
            String scope) {
        String raw = String.join("|", tenant, principal, permissions, scope);
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Set<String> split(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(",", -1)).map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String description() {
        return "DEVELOPMENT header authentication — NOT ADR-004 compliant: no OIDC, identity is asserted "
                + "by the caller. Never enable outside a development environment.";
    }
}
