package aspm.deployment;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The origins content is served from. {@code OPS-DEP-016}.
 *
 * <p>"Object storage serving uploaded content MUST be a distinct origin from the API and web tiers, and MUST
 * serve only non-inline with content-type enforcement."
 *
 * <p>Its rationale draws the line that matters: "Serving stored hostile content from the application origin makes
 * it <b>a same-origin execution risk</b>. The separate origin is the control; disposition and type enforcement
 * are defence in depth."
 *
 * <p>Evidence handling is the fourth of the platform's highest-risk surfaces — content that is <i>expected</i> to
 * be malicious and must remain retrievable. A stored cross-site scripting payload in an uploaded scanner report
 * is not a hypothetical here; it is the normal case. Served from the application origin it executes with the
 * session; served from a distinct origin it executes with nothing.
 *
 * <h2>Why a boolean would not do</h2>
 *
 * <p>A {@code separateOrigin} flag is satisfied by a subdomain of the application host, and a subdomain is not a
 * separate origin for cookies scoped to the parent domain — which is the configuration a reader of the flag would
 * produce. {@link #assertDistinctFrom} checks the registrable domain, not the host.
 */
public record ServingOrigin(String host, Disposition disposition, boolean contentTypeEnforced) {

    /** How content leaves the origin. */
    public enum Disposition {
        /** {@code Content-Disposition: attachment}. The only value permitted for uploaded content. */
        ATTACHMENT,
        /** Rendered in place. Permitted for platform-authored content only. */
        INLINE
    }

    public ServingOrigin {
        Objects.requireNonNull(host, "a host is required");
        Objects.requireNonNull(disposition, "a disposition is required");
        if (host.isBlank()) {
            throw new IllegalArgumentException("a blank host is not an origin");
        }
    }

    /**
     * The origin serving uploaded content: evidence, imported documents, exports.
     *
     * <p>Fixed to attachment with type enforcement, because the case for inline — previewing an uploaded PDF
     * without a download — is exactly the case {@code OPS-DEP-016} forbids, and it is the one a product
     * discussion will ask for.
     */
    public static ServingOrigin uploadedContent(String host) {
        return new ServingOrigin(host, Disposition.ATTACHMENT, true);
    }

    public static ServingOrigin application(String host) {
        return new ServingOrigin(host, Disposition.INLINE, true);
    }

    /**
     * Asserts this origin is distinct from every one of the others at the registrable-domain level.
     *
     * @throws IllegalArgumentException where any share a registrable domain. The message names both, because the
     *     configuration that produces this is a subdomain someone believed was isolation
     */
    public void assertDistinctFrom(List<ServingOrigin> others) {
        Objects.requireNonNull(others, "the other origins are required");
        String mine = registrableDomain(host);
        for (ServingOrigin other : others) {
            if (registrableDomain(other.host).equals(mine)) {
                throw new IllegalArgumentException(
                        host + " and " + other.host + " share the registrable domain '" + mine + "', so they "
                                + "are not distinct origins for a cookie scoped to it. Serving stored hostile "
                                + "content from the application origin makes it a same-origin execution risk "
                                + "(OPS-DEP-016), and a subdomain is the configuration that looks like "
                                + "isolation and is not.");
            }
        }
    }

    /**
     * The last two labels. Deliberately crude: it over-reports sharing for multi-label suffixes such as
     * {@code .co.uk}, and over-reporting a shared domain fails a deployment that was actually fine, while
     * under-reporting ships a same-origin execution risk. The asymmetry decides the direction of the error.
     */
    private static String registrableDomain(String host) {
        String[] labels = host.toLowerCase(Locale.ROOT).split("\\.", -1);
        if (labels.length < 2) {
            return host.toLowerCase(Locale.ROOT);
        }
        return labels[labels.length - 2] + "." + labels[labels.length - 1];
    }
}
