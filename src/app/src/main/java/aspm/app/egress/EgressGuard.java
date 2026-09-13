package aspm.app.egress;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one place that decides whether this platform is willing to make an outbound request to an
 * address that came from tenant configuration. {@code PRD-CON-032}, {@code PRD-CON-033},
 * {@code PRD-CON-034}, {@code OPS-DEP-015}, {@code TST-AUZ-001}.
 *
 * <h2>Why one class</h2>
 *
 * <p>{@code TST-AUZ-001} requires a new egress path to have an enforcement point and a test. Before this
 * class the platform had one egress path with tenant-supplied destinations — advisory webhooks — and
 * its check lived inside {@code WebhookAlerts}. Federated identity providers, notification channels
 * and outbound connectors each add destinations a tenant administrator types into a form, and a copy
 * of the check per surface is how the fourth surface ships without one. So the check is here, the
 * surfaces call it, and the test for it is one test.
 *
 * <h2>The three refusals</h2>
 *
 * <ul>
 *   <li><b>https only.</b> Anything this platform sends outbound names an application, a finding or a
 *       person. Over http that is readable by everything on the path.
 *   <li><b>No private, loopback, link-local or multicast destination.</b> A destination of
 *       {@code 169.254.169.254} turns a configuration form into cloud-credential exfiltration; one of
 *       {@code 127.0.0.1} reaches whatever trusts the local network. This is server-side request
 *       forgery — the defect class this product exists to find in customers' software.
 *   <li><b>Resolved, then checked, every time.</b> The name is resolved and every address is tested,
 *       at save and again immediately before the request ({@code PRD-CON-033}). Checking the literal
 *       text would be defeated by a name that resolves to a private address.
 * </ul>
 *
 * <p>What this does NOT close is DNS rebinding between the check and the connection: closing it means
 * pinning the connection to the checked address, which the JDK client cannot express. Stated rather
 * than left to be discovered, as {@code WebhookAlerts} states it.
 *
 * <h2>Test seam</h2>
 *
 * <p>The resolver is injectable so a test can present a name that resolves to a private address without
 * touching DNS, and {@link #permittingLoopbackForTests} exists so an integration test can point a
 * provider at a fake on 127.0.0.1. That constructor is package-visible to tests only through its name:
 * a production wiring that called it would be a review finding, and the startup banner says which
 * guard is in force.
 */
public final class EgressGuard {

    /** A refusal, with a reason that names the rule and never the resolved address. */
    public record Verdict(boolean permitted, String reason) {
        public static final Verdict OK = new Verdict(true, "permitted");
    }

    private static final EgressGuard PRODUCTION = new EgressGuard(EgressGuard::resolveAll, false);

    private final Function<String, InetAddress[]> resolver;
    private final boolean loopbackPermitted;

    private EgressGuard(Function<String, InetAddress[]> resolver, boolean loopbackPermitted) {
        this.resolver = Objects.requireNonNull(resolver);
        this.loopbackPermitted = loopbackPermitted;
    }

    /** The production guard: system DNS, no exceptions. */
    public static EgressGuard production() {
        return PRODUCTION;
    }

    /** A guard with an injected resolver, for tests that need a name to resolve somewhere specific. */
    public static EgressGuard withResolver(Function<String, InetAddress[]> resolver) {
        return new EgressGuard(resolver, false);
    }

    /**
     * A guard that admits loopback destinations over plain http, for an integration test that runs a
     * fake provider on 127.0.0.1. Never wired in production; the banner names the guard in force.
     */
    public static EgressGuard permittingLoopbackForTests() {
        return new EgressGuard(EgressGuard::resolveAll, true);
    }

    /** As {@link #permittingLoopbackForTests()}, with names resolved by the test rather than by DNS. */
    public static EgressGuard permittingLoopbackForTests(Function<String, InetAddress[]> resolver) {
        return new EgressGuard(resolver, true);
    }

    public boolean permitsLoopback() {
        return loopbackPermitted;
    }

    /** Whether a request to {@code url} may be made. */
    public Verdict check(String url) {
        if (url == null || url.isBlank()) {
            return new Verdict(false, "no destination");
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return new Verdict(false, "the destination is not a URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(loopbackPermitted && "http".equals(scheme))) {
            return new Verdict(false, "the destination is not an https URL");
        }
        if (uri.getUserInfo() != null) {
            return new Verdict(false, "the destination carries credentials in the URL");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return new Verdict(false, "the destination has no host");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.apply(host);
        } catch (RuntimeException e) {
            return new Verdict(false, "the destination does not resolve");
        }
        if (addresses == null || addresses.length == 0) {
            return new Verdict(false, "the destination does not resolve");
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() && loopbackPermitted) {
                continue;
            }
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || isUniqueLocalIpv6(address) || isCarrierGradeNat(address)) {
                return new Verdict(false, "the destination resolves inside a private, loopback or link-local range");
            }
        }
        return Verdict.OK;
    }

    /** {@link #check} as a boolean, for call sites that only need the answer. */
    public boolean permitted(String url) {
        return check(url).permitted();
    }

    /** The first reason a URL is refused, for a form error. Empty when permitted. */
    public Optional<String> refusal(String url) {
        Verdict verdict = check(url);
        return verdict.permitted() ? Optional.empty() : Optional.of(verdict.reason());
    }

    public String description() {
        return loopbackPermitted
                ? "egress guard: TEST MODE — loopback and http admitted; never run a real deployment like this"
                : "egress guard: https only; private, loopback, link-local, CGNAT and ULA destinations refused; resolved at use";
    }

    private static InetAddress[] resolveAll(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return new InetAddress[0];
        }
    }

    /** fc00::/7 — the IPv6 equivalent of a private range, which the JDK's isSiteLocalAddress misses. */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    /** 100.64.0.0/10 — carrier-grade NAT, internal to many enterprise networks. */
    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 0x40;
    }
}
