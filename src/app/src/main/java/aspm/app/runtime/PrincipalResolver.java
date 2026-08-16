package aspm.app.runtime;

import java.util.Map;
import java.util.Optional;

/**
 * Turns transport credentials into a {@link Principal}. ADR-004.
 *
 * <p>ADR-004: OIDC/OAuth2 for humans, sender-constrained credentials for services, signed requests with
 * replay protection for legacy CI, and <b>no bearer API keys</b>.
 *
 * <p>The interface exists so the transport credential format is one replaceable thing rather than a check
 * spread across handlers. {@link #resolve} returns empty for "not authenticated" and throws for
 * "authentication was attempted and failed", because the dispatcher answers those differently and a single
 * empty return would make a failed authentication look like an anonymous request.
 */
public interface PrincipalResolver {

    /**
     * Resolves the caller's principal from the transport credentials.
     *
     * @param headers request headers, lower-cased keys
     * @return empty where no credential was presented
     * @throws SecurityException where a credential was presented and is not valid. The message reaching a
     *     client is the dispatcher's, not this one's: {@code PRD-UIX-025} keeps reconnaissance out of error
     *     surfaces, and "signature mismatch" tells an attacker which half of the credential is wrong
     */
    Optional<Principal> resolve(Map<String, String> headers);

    /** What this resolver is, for the startup banner. A deployment should be able to read it in a log. */
    String description();
}
