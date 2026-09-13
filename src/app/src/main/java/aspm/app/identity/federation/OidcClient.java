package aspm.app.identity.federation;

import aspm.app.egress.EgressGuard;
import aspm.app.runtime.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The OpenID Connect relying-party half: discovery, the authorization request, the code exchange, and
 * the key set. Authorization-code flow with PKCE, always. {@code SEC-SEC-002}, {@code PRD-IAM-001},
 * {@code PRD-CON-032}, {@code PRD-CON-033}, {@code PRD-CON-034}.
 *
 * <h2>Egress</h2>
 *
 * <p>Every URL this class calls is either the configured issuer's discovery document or a URL that
 * document returned. Both are checked against the {@link EgressGuard} immediately before the request
 * — a discovery document is provider data, and a provider that returned a token endpoint on
 * {@code 169.254.169.254} would otherwise turn a misconfigured issuer into a request against the
 * cloud metadata service. Redirects are never followed.
 *
 * <h2>Caching</h2>
 *
 * <p>Discovery and the JWK set are cached per issuer for a bounded time, and the key set is refreshed
 * once on an unknown {@code kid} so a provider's key rotation does not lock a tenant out until the
 * cache expires. The cache key is the issuer, not the tenant: two tenants using the same public
 * provider share its published metadata, which is public by definition and carries nothing of either
 * tenant. A tenant-specific issuer (a Keycloak realm, an Entra tenant) is its own key.
 */
public final class OidcClient {

    /** What discovery told us. */
    public record Discovery(String issuer, String authorizationEndpoint, String tokenEndpoint,
            String jwksUri, Optional<String> userinfoEndpoint, Optional<String> endSessionEndpoint,
            List<String> idTokenSigningAlgorithms, Instant fetchedAt) {
    }

    /** The outcome of a code exchange. */
    public record Tokens(String idToken, Optional<String> accessToken) {
    }

    /** A provider-side failure with a stable code; detail is a class name or HTTP status. */
    public static final class ProviderUnavailable extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;

        public ProviderUnavailable(String code, String detail) {
            super(code + ": " + detail);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final Duration DISCOVERY_TTL = Duration.ofHours(6);
    private static final Duration JWKS_TTL = Duration.ofHours(1);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private record Cached<T>(T value, Instant fetchedAt) {
        boolean fresh(Duration ttl, Instant now) {
            return fetchedAt.plus(ttl).isAfter(now);
        }
    }

    private final EgressGuard egress;
    /**
     * Test seam: rewrites a checked URL immediately before the request is sent, so a suite can run a
     * fake provider on the loopback interface behind an https issuer that satisfies every production
     * check — the schema's CHECK, the egress guard, the issuer match. Identity in production; the
     * constructor that sets it is named for tests and is not wired by {@code AspmApplication}.
     */
    private final java.util.function.UnaryOperator<String> endpointRewriteForTests;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Cached<Discovery>> discoveryCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<List<Map<String, Object>>>> jwksCache = new ConcurrentHashMap<>();

    public OidcClient(EgressGuard egress) {
        this(egress, java.util.function.UnaryOperator.identity());
    }

    /** See {@link #endpointRewriteForTests}. */
    public static OidcClient rewritingEndpointsForTests(EgressGuard egress,
            java.util.function.UnaryOperator<String> rewrite) {
        return new OidcClient(egress, rewrite);
    }

    private OidcClient(EgressGuard egress, java.util.function.UnaryOperator<String> endpointRewriteForTests) {
        this.egress = Objects.requireNonNull(egress);
        this.endpointRewriteForTests = Objects.requireNonNull(endpointRewriteForTests);
    }

    // ==============================================================================================
    // Discovery
    // ==============================================================================================

    /** Fetches (or serves from cache) the provider's configuration. */
    public Discovery discover(String issuer, Optional<String> discoveryUrl) throws ProviderUnavailable {
        String url = discoveryUrl.filter(u -> !u.isBlank())
                .orElse(issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration");
        Cached<Discovery> cached = discoveryCache.get(url);
        Instant now = Instant.now();
        if (cached != null && cached.fresh(DISCOVERY_TTL, now)) {
            return cached.value();
        }
        Map<String, Object> document = getJson(url, "DISCOVERY");
        String advertisedIssuer = text(document, "issuer").orElseThrow(() -> new ProviderUnavailable("DISCOVERY_INVALID", "no issuer"));
        // OIDC Discovery §4.3: the issuer in the document MUST match the one it was fetched for. A
        // document that names another issuer is either misconfiguration or a redirect somebody arranged.
        if (!advertisedIssuer.equals(issuer) && !advertisedIssuer.equals(issuer.replaceAll("/+$", ""))) {
            throw new ProviderUnavailable("DISCOVERY_ISSUER_MISMATCH", "the document names a different issuer");
        }
        List<String> algorithms = new ArrayList<>();
        if (document.get("id_token_signing_alg_values_supported") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    algorithms.add(s);
                }
            }
        }
        Discovery discovery = new Discovery(advertisedIssuer,
                text(document, "authorization_endpoint").orElseThrow(() -> new ProviderUnavailable("DISCOVERY_INVALID", "no authorization_endpoint")),
                text(document, "token_endpoint").orElseThrow(() -> new ProviderUnavailable("DISCOVERY_INVALID", "no token_endpoint")),
                text(document, "jwks_uri").orElseThrow(() -> new ProviderUnavailable("DISCOVERY_INVALID", "no jwks_uri")),
                text(document, "userinfo_endpoint"), text(document, "end_session_endpoint"),
                algorithms.isEmpty() ? List.of("RS256") : List.copyOf(algorithms), now);
        // Every endpoint the document names is a destination this platform will call. Checked now so a
        // hostile document fails at configuration time, and again before each call.
        for (String endpoint : List.of(discovery.authorizationEndpoint(), discovery.tokenEndpoint(), discovery.jwksUri())) {
            if (!egress.permitted(endpoint)) {
                throw new ProviderUnavailable("DISCOVERY_ENDPOINT_REFUSED", "an endpoint in the document is not a permitted destination");
            }
        }
        discoveryCache.put(url, new Cached<>(discovery, now));
        return discovery;
    }

    // ==============================================================================================
    // Authorization request
    // ==============================================================================================

    /** Random URL-safe material of the given byte length. */
    public String randomToken(int bytes) {
        byte[] material = new byte[bytes];
        random.nextBytes(material);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /** S256 of a PKCE verifier. */
    public static String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /** The URL the browser is sent to. */
    public static String authorizationUrl(Discovery discovery, String clientId, String redirectUri, List<String> scopes,
            String state, String nonce, String pkceVerifier, Map<String, String> extra) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("scope", String.join(" ", scopes));
        params.put("state", state);
        params.put("nonce", nonce);
        params.put("code_challenge", pkceChallenge(pkceVerifier));
        params.put("code_challenge_method", "S256");
        params.putAll(extra);
        StringBuilder query = new StringBuilder();
        params.forEach((k, v) -> {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        String base = discovery.authorizationEndpoint();
        return base + (base.contains("?") ? "&" : "?") + query;
    }

    // ==============================================================================================
    // Code exchange
    // ==============================================================================================

    /**
     * Exchanges the authorization code. The client secret, when present, travels as HTTP Basic
     * (client_secret_basic), which every preset supports; a public client sends only the PKCE verifier.
     */
    public Tokens exchangeCode(Discovery discovery, String clientId, Optional<char[]> clientSecret, String code,
            String redirectUri, String pkceVerifier) throws ProviderUnavailable {
        String endpoint = discovery.tokenEndpoint();
        if (!egress.permitted(endpoint)) {
            throw new ProviderUnavailable("ENDPOINT_REFUSED", "the token endpoint is not a permitted destination");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", clientId);
        form.put("code_verifier", pkceVerifier);
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpointRewriteForTests.apply(endpoint)))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("User-Agent", "aspm-oidc/1")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        clientSecret.ifPresent(secret -> request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (URLEncoder.encode(clientId, StandardCharsets.UTF_8) + ":" + URLEncoder.encode(new String(secret), StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8))));
        Map<String, Object> response = send(request.build(), "TOKEN_EXCHANGE");
        String idToken = text(response, "id_token").orElseThrow(() -> new ProviderUnavailable("TOKEN_EXCHANGE", "no id_token in the response"));
        return new Tokens(idToken, text(response, "access_token"));
    }

    // ==============================================================================================
    // Keys
    // ==============================================================================================

    /** The provider's signing keys; refreshed once when {@code kid} is not in the cached set. */
    public List<Map<String, Object>> keys(Discovery discovery, Optional<String> kid) throws ProviderUnavailable {
        Instant now = Instant.now();
        Cached<List<Map<String, Object>>> cached = jwksCache.get(discovery.jwksUri());
        if (cached != null && cached.fresh(JWKS_TTL, now)) {
            boolean known = kid.isEmpty() || cached.value().stream().anyMatch(k -> kid.get().equals(k.get("kid")));
            if (known) {
                return cached.value();
            }
        }
        Map<String, Object> document = getJson(discovery.jwksUri(), "JWKS");
        List<Map<String, Object>> keys = new ArrayList<>();
        if (document.get("keys") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> key = new LinkedHashMap<>();
                    m.forEach((k, v) -> key.put(String.valueOf(k), v));
                    keys.add(key);
                }
            }
        }
        if (keys.isEmpty()) {
            throw new ProviderUnavailable("JWKS_EMPTY", "the key set has no keys");
        }
        jwksCache.put(discovery.jwksUri(), new Cached<>(List.copyOf(keys), now));
        return keys;
    }

    /** Forgets cached metadata for an issuer, after a configuration change or an explicit test. */
    public void forget(String issuer) {
        discoveryCache.keySet().removeIf(k -> k.startsWith(issuer));
        jwksCache.clear();
    }

    // ==============================================================================================

    private Map<String, Object> getJson(String url, String stage) throws ProviderUnavailable {
        if (!egress.permitted(url)) {
            throw new ProviderUnavailable(stage + "_REFUSED", "the destination is not a permitted destination");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpointRewriteForTests.apply(url)))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "aspm-oidc/1")
                .GET().build();
        return send(request, stage);
    }

    private static Map<String, Object> send(HttpRequest request, String stage) throws ProviderUnavailable {
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProviderUnavailable(stage, "HTTP " + response.statusCode());
            }
            return Json.readObject(response.body());
        } catch (IOException e) {
            throw new ProviderUnavailable(stage, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailable(stage, "interrupted");
        } catch (IllegalArgumentException e) {
            throw new ProviderUnavailable(stage, "the response was not a JSON object");
        }
    }

    private static Optional<String> text(Map<String, Object> map, String field) {
        return map.get(field) instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }
}
