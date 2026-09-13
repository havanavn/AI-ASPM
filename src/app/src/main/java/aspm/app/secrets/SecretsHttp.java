package aspm.app.secrets;

import aspm.sharedkernel.secrets.SecretReference;
import aspm.sharedkernel.secrets.SecretsProvider;
import aspm.app.runtime.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * The one HTTP client the secrets adapters share, configured the way every outbound client in this
 * tier is configured: no redirects, bounded connect and request timeouts. {@code PRD-CON-034}.
 *
 * <p>Redirects are never followed because a 302 from a secrets endpoint is a destination nobody
 * configured, and following it would post a bearer token to it. Timeouts are short because a secret
 * resolution sits on a request path or a delivery attempt, and a hung vault must degrade to "absent"
 * rather than hold a worker.
 *
 * <p>Responses are returned as text for the caller to parse; failures are reported by class name and
 * status, never by body, because a provider's error body can quote the path and the caller's token.
 */
final class SecretsHttp {

    /** A response, with the body already read. */
    record Reply(int status, String body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        Map<String, Object> json() {
            return body == null || body.isBlank() ? Map.of() : Json.readObject(body);
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private SecretsHttp() {
    }

    static Reply send(String method, URI uri, Map<String, String> headers, String body) {
        Objects.requireNonNull(uri);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
        headers.forEach(request::header);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        request.method(method, publisher);
        try {
            HttpResponse<String> response = CLIENT.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new SecretsProvider.SecretsException("PROVIDER_UNREACHABLE", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecretsProvider.SecretsException("INTERRUPTED", "interrupted while calling the provider");
        }
    }

    static Reply getJson(URI uri, Map<String, String> headers) {
        return send("GET", uri, withAccept(headers), null);
    }

    static Reply postJson(URI uri, Map<String, String> headers, Object body) {
        Map<String, String> h = new java.util.LinkedHashMap<>(withAccept(headers));
        h.put("Content-Type", "application/json");
        return send("POST", uri, h, Json.write(body));
    }

    static Reply putJson(URI uri, Map<String, String> headers, Object body) {
        Map<String, String> h = new java.util.LinkedHashMap<>(withAccept(headers));
        h.put("Content-Type", "application/json");
        return send("PUT", uri, h, Json.write(body));
    }

    static Reply postForm(URI uri, Map<String, String> headers, Map<String, String> form) {
        Map<String, String> h = new java.util.LinkedHashMap<>(withAccept(headers));
        h.put("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder encoded = new StringBuilder();
        form.forEach((k, v) -> {
            if (encoded.length() > 0) {
                encoded.append('&');
            }
            encoded.append(java.net.URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(java.net.URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return send("POST", uri, h, encoded.toString());
    }

    static Reply delete(URI uri, Map<String, String> headers) {
        return send("DELETE", uri, withAccept(headers), null);
    }

    private static Map<String, String> withAccept(Map<String, String> headers) {
        Map<String, String> h = new java.util.LinkedHashMap<>(headers);
        h.putIfAbsent("Accept", "application/json");
        h.putIfAbsent("User-Agent", "aspm-secrets/1");
        return h;
    }

    /** Only https reaches a secrets provider, with one named exception a caller must opt into. */
    static URI httpsBase(String configured, String what) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(what + " is not configured");
        }
        URI uri = URI.create(configured.strip().replaceAll("/+$", ""));
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException(what + " must be an https URL: a secrets provider reached over "
                    + "cleartext is a secrets provider whose values cross the network in the clear");
        }
        return uri;
    }

    static String base64Url(byte[] bytes) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
