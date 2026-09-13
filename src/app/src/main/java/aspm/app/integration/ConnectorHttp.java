package aspm.app.integration;

import aspm.app.egress.EgressGuard;
import aspm.module.integration.domain.FailureClass;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * The one HTTP path every tracker adapter uses. {@code PRD-CON-025}, {@code PRD-CON-027},
 * {@code PRD-CON-032}, {@code PRD-CON-033}, {@code PRD-CON-034}.
 *
 * <p>No redirects, ever: a permitted destination redirecting to an internal address is the standard
 * bypass of destination allowlisting, so the client does not follow one and a 3xx is a
 * {@code CONFIGURATION} failure. The egress guard is consulted immediately before each request, on the
 * final URL, so the check is at connection time and not only at save. Failures are classified once,
 * here, so no adapter can retry undifferentiated.
 */
final class ConnectorHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** The response as the adapter sees it: status, body, and the target's rate-limit signal if any. */
    record Reply(int status, String body, Optional<String> retryAfter) {
    }

    private ConnectorHttp() {
    }

    static ConnectorAdapter.Result<Reply> send(EgressGuard egress, String method, String url, Map<String, String> headers,
            Optional<String> jsonBody) {
        EgressGuard.Verdict verdict = egress.check(url);
        if (!verdict.permitted()) {
            return ConnectorAdapter.Result.failed(FailureClass.CONFIGURATION, "destination refused: " + verdict.reason());
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "aspm-connector/1");
        jsonBody.ifPresent(b -> request.header("Content-Type", "application/json"));
        headers.forEach(request::header);
        request.method(method, jsonBody.map(b -> HttpRequest.BodyPublishers.ofString(b, StandardCharsets.UTF_8))
                .orElse(HttpRequest.BodyPublishers.noBody()));
        try {
            HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            Reply reply = new Reply(status, response.body() == null ? "" : response.body(), response.headers().firstValue("Retry-After"));
            if (status >= 200 && status < 300) {
                return ConnectorAdapter.Result.ok(reply, "HTTP " + status);
            }
            return ConnectorAdapter.Result.failed(classify(status), "HTTP " + status
                    + reply.retryAfter().map(v -> " retry-after=" + v).orElse(""));
        } catch (IOException e) {
            return ConnectorAdapter.Result.failed(FailureClass.TRANSIENT, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ConnectorAdapter.Result.failed(FailureClass.TRANSIENT, "interrupted");
        }
    }

    /**
     * DOC-21 §5, applied to HTTP. 401 is the credential; 403 is the permission set; 429 is the target's
     * rate limit; 404 on a configured path is the configuration; a redirect is a configuration that would
     * take us somewhere else; 5xx and the network are transient; any other 4xx is the record.
     */
    static FailureClass classify(int status) {
        if (status == 401) {
            return FailureClass.AUTHENTICATION;
        }
        if (status == 403) {
            return FailureClass.AUTHORIZATION;
        }
        if (status == 429) {
            return FailureClass.RATE_LIMITED;
        }
        if (status == 404 || status == 405 || status == 410 || (status >= 300 && status < 400)) {
            return FailureClass.CONFIGURATION;
        }
        if (status >= 500) {
            return FailureClass.TRANSIENT;
        }
        return FailureClass.DATA;
    }

    static String basic(String user, char[] secret) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + new String(secret)).getBytes(StandardCharsets.UTF_8));
    }

    static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * A base URL as configuration: https, a host, an optional path prefix, no query, no fragment, no
     * credentials in the authority, and a destination the egress guard would permit ({@code PRD-CON-033}
     * at save; it is checked again at each connection).
     */
    static String baseUrl(EgressGuard egress, Object value, String field) {
        String text = value == null ? "" : String.valueOf(value).strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(field + " must be https: a tracker credential over http is a credential on the wire");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(field + " must be a host with an optional path and nothing else");
        }
        EgressGuard.Verdict verdict = egress.check(text);
        if (!verdict.permitted()) {
            throw new IllegalArgumentException(field + " is not a permitted egress destination: " + verdict.reason());
        }
        return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }

    static String requiredText(Map<String, Object> config, String key, String what) {
        Object value = config.get(key);
        String text = value == null ? "" : String.valueOf(value).strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(what + " is required");
        }
        if (text.length() > 200) {
            throw new IllegalArgumentException(what + " is too long");
        }
        return text;
    }
}
