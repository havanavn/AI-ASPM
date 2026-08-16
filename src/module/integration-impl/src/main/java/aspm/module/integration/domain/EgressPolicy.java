package aspm.module.integration.domain;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Where a connector may connect. {@code PRD-CON-032}, {@code PRD-CON-033}, {@code PRD-CON-034}.
 *
 * <p>{@code PRD-CON-032}'s rationale is the clearest statement of the threat in the corpus: "A connector
 * accepting a data-derived destination is <b>a server-side request forgery primitive positioned inside the
 * platform's network, operating with the platform's credentials</b>. The destination is configuration, and
 * configuration is validated."
 *
 * <h2>Three closures, and each is a different bypass</h2>
 *
 * <ol>
 *   <li><b>Destinations come from configuration, never from data.</b> {@link #resolve} takes a configured
 *       destination <i>name</i> and looks it up. There is no method taking a URL, so a record field cannot
 *       become a destination however it reaches the connector.
 *   <li><b>Resolution is re-checked at connection time.</b> "Re-checking at connection time closes the rebinding
 *       gap between validation and use, which is otherwise a bypass of the allowlist." A name validated at
 *       configuration and resolved at connect can resolve to a different address; the second check is what
 *       catches it.
 *   <li><b>Redirects are not followed outside the allowlist.</b> "A permitted destination redirecting to an
 *       internal address is <i>the standard bypass</i> of destination allowlisting."
 * </ol>
 */
public final class EgressPolicy {

    /** Address ranges no destination may resolve to, whatever the allowlist says. */
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "127.", "10.", "192.168.", "169.254.", "0.", "::1", "fc", "fd", "fe80:");

    /** A configured destination. The name is what a connector refers to; the host is validated here. */
    public record Destination(String name, String scheme, String host, int port) {

        public Destination {
            Objects.requireNonNull(name, "a destination name is required");
            Objects.requireNonNull(scheme, "a scheme is required");
            Objects.requireNonNull(host, "a host is required");
            if (!scheme.equals("https")) {
                throw new IllegalArgumentException(
                        "egress is https only. A plaintext destination carries the platform's credentials over "
                                + "a network the tenant does not control, and the credential is access TO the "
                                + "customer's estate rather than data about it (PRD-CON-021).");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("a port outside 1..65535");
            }
        }
    }

    /** The outcome of a connect-time check. */
    public record Verdict(boolean permitted, String reason) {

        static Verdict permit(String host) {
            return new Verdict(true, "permitted: " + host + " is a configured destination and resolves "
                    + "outside the forbidden ranges");
        }

        static Verdict refuse(String reason) {
            return new Verdict(false, reason);
        }
    }

    private final Set<String> configuredDestinationNames;
    private final List<Destination> destinations;

    public EgressPolicy(List<Destination> destinations) {
        this.destinations = List.copyOf(Objects.requireNonNull(destinations, "destinations are required"));
        this.configuredDestinationNames = this.destinations.stream()
                .map(Destination::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (configuredDestinationNames.size() != this.destinations.size()) {
            throw new IllegalArgumentException(
                    "two destinations share a name; which one a connector reaches would depend on ordering");
        }
    }

    /**
     * Resolves a configured destination by <b>name</b>.
     *
     * <p>There is deliberately no overload taking a URL or a host. {@code PRD-CON-032} says the destination
     * "MUST NOT be derived from data, a record field, a redirect, or user input" — and the way to guarantee
     * that is for there to be no parameter a record field could occupy.
     *
     * @throws IllegalArgumentException where the name is not configured. Not a fallback to the name as a host:
     *     that fallback is the data-derived destination arriving through a typo
     */
    public Destination resolve(String configuredName) {
        Objects.requireNonNull(configuredName, "a configured destination name is required");
        return destinations.stream()
                .filter(d -> d.name().equals(configuredName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "'" + configuredName + "' is not a configured destination (PRD-CON-032). A connector "
                                + "accepting a data-derived destination is a server-side request forgery "
                                + "primitive positioned inside the platform's network, operating with the "
                                + "platform's credentials."));
    }

    /**
     * The connect-time check. {@code PRD-CON-033}.
     *
     * @param resolveHost performs DNS <b>now</b>. Passed in rather than done at configuration time, because the
     *     gap between validation and use is exactly what rebinding exploits
     */
    public Verdict permitConnection(Destination destination, Function<String, List<String>> resolveHost) {
        Objects.requireNonNull(destination, "a destination is required");
        Objects.requireNonNull(resolveHost, "a connect-time resolver is required (PRD-CON-033)");

        if (!configuredDestinationNames.contains(destination.name())) {
            return Verdict.refuse("'" + destination.name() + "' is not in this policy's configured set");
        }

        List<String> addresses = resolveHost.apply(destination.host());
        if (addresses.isEmpty()) {
            // Fail closed. An unresolvable host is not a permitted one, and treating resolution failure as
            // "carry on and let the connection fail" would skip the range check entirely.
            return Verdict.refuse(destination.host() + " did not resolve; an unresolvable host is refused "
                    + "rather than attempted, because attempting it would skip this check");
        }
        for (String address : addresses) {
            String normalized = address.strip().toLowerCase(java.util.Locale.ROOT);
            for (String forbidden : FORBIDDEN_PREFIXES) {
                if (normalized.startsWith(forbidden)) {
                    return Verdict.refuse(destination.host() + " resolves to " + address
                            + ", which is an internal or link-local range. Re-checked at connection time "
                            + "because the gap between validation and use is what rebinding exploits "
                            + "(PRD-CON-033).");
                }
            }
        }
        return Verdict.permit(destination.host());
    }

    /**
     * Whether a redirect may be followed. {@code PRD-CON-034}.
     *
     * <p>"A permitted destination redirecting to an internal address is the standard bypass of destination
     * allowlisting." The redirect target is checked against the configured <b>hosts</b>, not against the
     * original destination — a redirect within one host is fine, and a redirect to another configured
     * destination is also fine because that destination was validated.
     */
    public Verdict permitRedirect(URI redirectTarget, Function<String, List<String>> resolveHost) {
        Objects.requireNonNull(redirectTarget, "a redirect target is required");
        Objects.requireNonNull(resolveHost, "a connect-time resolver is required");

        String host = redirectTarget.getHost();
        if (host == null) {
            return Verdict.refuse("a redirect with no host cannot be checked, so it is not followed");
        }
        return destinations.stream()
                .filter(d -> d.host().equalsIgnoreCase(host))
                .findFirst()
                .map(d -> permitConnection(d, resolveHost))
                .orElseGet(() -> Verdict.refuse(
                        "redirect to " + host + " is outside the allowlist and is not followed "
                                + "(PRD-CON-034). A permitted destination redirecting to an internal address "
                                + "is the standard bypass of destination allowlisting."));
    }

    public List<Destination> destinations() {
        return destinations;
    }
}
