package aspm.app.ai;

import aspm.app.egress.EgressGuard;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a model provider may live. {@code PRD-AIC-025}, {@code OPS-DEP-014}, {@code PRD-CON-033}.
 *
 * <p>A hosted provider is a public https destination and the egress guard decides. A self-hosted
 * inference server — vLLM, Ollama, a gateway in the estate's own network, which is exactly what
 * {@code OQ-027}'s ratified assumption names — is a private address, often plain http inside a mesh
 * that terminates TLS for it. The guard would refuse it, correctly, because a private destination
 * chosen by a tenant is a request forger. So the OPERATOR names them: {@code ASPM_MODEL_ENDPOINTS} is
 * a comma-separated list of base URLs the deployment vouches for, the same shape as
 * {@code ASPM_SMTP_RELAYS}. A tenant may configure a provider at one of those or at any public https
 * address, and nothing else.
 *
 * <p>Bound once at start, like the object store's environment, so the services that build their own
 * collaborators from a data source read the same list the entry point read.
 */
public final class ModelEndpoints {

    public static final String VARIABLE = "ASPM_MODEL_ENDPOINTS";

    private static volatile List<String> vouched = List.of();

    private ModelEndpoints() {
    }

    public static void bind(Map<String, String> environment) {
        List<String> out = new ArrayList<>();
        for (String part : environment.getOrDefault(VARIABLE, "").split(",")) {
            String base = part.strip();
            if (base.isEmpty()) {
                continue;
            }
            URI uri = URI.create(base);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalStateException(VARIABLE + " carries an entry that is not a base URL: " + base);
            }
            out.add(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        }
        vouched = List.copyOf(out);
    }

    /** For tests and for the start-up banner. */
    public static List<String> vouched() {
        return vouched;
    }

    /** Whether a tenant may point a provider at this URL: operator-vouched, or a public https destination. */
    public static boolean permitted(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String candidate = url.strip();
        for (String base : vouched) {
            if (candidate.equalsIgnoreCase(base) || candidate.toLowerCase(Locale.ROOT).startsWith(base.toLowerCase(Locale.ROOT) + "/")) {
                return true;
            }
        }
        return EgressGuard.production().permitted(candidate);
    }

    public static String refusal(String url) {
        return vouched.isEmpty()
                ? "the endpoint must be a public https URL; a self-hosted endpoint on a private network must be vouched for by the "
                        + "deployment in " + VARIABLE
                : "the endpoint must be a public https URL or one of the endpoints this deployment vouches for (" + VARIABLE + ")";
    }
}
