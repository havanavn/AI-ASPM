package aspm.app.runtime;

import aspm.app.api.RequestValidation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * Sockets to {@link Dispatcher.Request} and back. ADR-057.
 *
 * <p>This is the transport adapter and nothing else. It contains no authorization, no scope logic, no
 * tenant derivation and no content negotiation, which is what makes ADR-057's revisit trigger cheap:
 * replacing the HTTP runtime means replacing this file.
 *
 * <p>It speaks plain HTTP. DOC-15 §3.1 places a TLS-terminating ingress in front of the application tier
 * and {@code OPS-DEP-017} forbids that ingress from making authorization decisions, so this listens on an
 * internal address and is never the TLS endpoint.
 */
public final class HttpRuntime implements AutoCloseable {

    private final HttpServer server;
    private final Dispatcher dispatcher;

    public HttpRuntime(int port, Dispatcher dispatcher, ReadinessProbe readiness) throws IOException {
        this.dispatcher = Objects.requireNonNull(dispatcher, "a dispatcher is required");
        Objects.requireNonNull(readiness, "a readiness probe is required");

        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        // Virtual threads: a request spends its life waiting on the operational store, and ADR-049's
        // concentration note makes that the bound. One platform thread per request would cap concurrency
        // on the wrong resource.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // OPS-DEP-008 requires readiness and liveness to be SEPARATE, and requires readiness to fail on an
        // unavailable dependency while liveness does not: "conflating them causes a restart loop during a
        // dependency outage, which turns a degraded state into an outage". They are two contexts here, and
        // RuntimeUnit.Probes refuses to be constructed with the same path for both.
        server.createContext("/internal/health/live", exchange ->
                respond(exchange, 200, "{\"status\":\"alive\"}"));
        server.createContext("/internal/health/ready", exchange -> {
            ReadinessProbe.Result result = readiness.check();
            respond(exchange, result.ready() ? 200 : 503,
                    Json.write(Map.of("status", result.ready() ? "ready" : "not ready",
                            "detail", result.detail())));
        });

        server.createContext("/", this::handle);
    }

    /** Readiness. Separate type so the check is testable without a socket. */
    @FunctionalInterface
    public interface ReadinessProbe {

        Result check();

        record Result(boolean ready, String detail) {
        }
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            Map<String, String> headers = lowerCasedHeaders(exchange);
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

            Optional<String> body = readBody(exchange);
            if (body.isEmpty() && exchange.getRequestBody().available() > 0) {
                respond(exchange, 413, Json.write(Map.of("status", 413, "code", "BODY_TOO_LARGE",
                        "message", "the request body exceeds the permitted size")));
                return;
            }

            Dispatcher.Response response = dispatcher.dispatch(
                    exchange.getRequestMethod(), path, query, headers, body);

            // Checked BEFORE the header loop below, not after: respondBinary writes the response's
            // own headers, and reaching it through that loop emitted every one of them twice. A
            // duplicated ETag is malformed, and duplicated Content-Security-Policy headers are
            // intersected by the browser rather than merged — a policy stricter than either.
            //
            // Bytes are written as bytes. Routed away from respond() because that method encodes a
            // String and applies the page's Content-Security-Policy, and an image needs neither.
            if (response.body() instanceof aspm.app.ui.InterfaceResource.Binary binary) {
                respondBinary(exchange, response.status(), binary.content(), response.headers());
                return;
            }
            String contentType = response.headers().getOrDefault("Content-Type",
                    "application/json; charset=utf-8");
            for (Map.Entry<String, String> header : response.headers().entrySet()) {
                if (!"Content-Type".equals(header.getKey())) {
                    exchange.getResponseHeaders().add(header.getKey(), header.getValue());
                }
            }
            // A body the resource already serialized — a page, a stylesheet — is written as it is.
            // Passing it through the JSON writer would quote and escape it into an unusable string, and
            // the writer rejects unknown types precisely so that mistake fails loudly.
            String rendered = response.body() instanceof aspm.app.ui.InterfaceResource.Raw raw
                    ? raw.content()
                    : Json.write(response.body());
            respond(exchange, response.status(), rendered, contentType);
        } catch (RuntimeException e) {
            String correlation = java.util.UUID.randomUUID().toString();
            System.getLogger("aspm.http").log(System.Logger.Level.ERROR,
                    "transport failure, correlation=" + correlation, e);
            respond(exchange, 500, Json.write(Map.of("status", 500, "code", "INTERNAL_ERROR",
                    "message", "the request could not be completed", "correlation", correlation)));
        }
    }

    /**
     * @return empty where the body exceeds {@link RequestValidation#MAX_BODY_BYTES}
     */
    private static Optional<String> readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            // Bounded read rather than readAllBytes: ADR-057 records the absence of a runtime body limit
            // as an accepted gap whose compensating control is this bound. readAllBytes would allocate
            // whatever the caller sends before the limit could be applied.
            byte[] buffer = in.readNBytes(RequestValidation.MAX_BODY_BYTES + 1);
            if (buffer.length > RequestValidation.MAX_BODY_BYTES) {
                return Optional.empty();
            }
            return Optional.of(new String(buffer, StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> lowerCasedHeaders(HttpExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.getFirst());
            }
        });
        // The request line, as pseudo-headers, written AFTER the client's own so a client cannot
        // supply them. A signed-request credential covers the method and the path (ADR-004), and a
        // PrincipalResolver is handed headers and nothing else — without these it would be verifying
        // a signature over a request it cannot see the shape of, which is a signature over nothing.
        headers.put(":method", exchange.getRequestMethod());
        headers.put(":path", exchange.getRequestURI().getPath());
        return headers;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return query;
        }
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            query.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body, "application/json; charset=utf-8");
    }

    /** Writes bytes with the handler's own headers and none of the page defaults. */
    private static void respondBinary(HttpExchange exchange, int status, byte[] bytes,
            Map<String, String> headers) throws IOException {
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        // The interface is served from the same origin as the API, which OPS-DEP-016 permits: it is
        // platform-authored markup, not uploaded content. Uploaded content goes to the object store's
        // distinct origin, and a Content-Security-Policy is what keeps this page from loading it inline.
        exchange.getResponseHeaders().add("Content-Security-Policy",
                // img-src 'self' is what lets a screenshot pasted into a finding write-up render.
                // Without it default-src 'none' blocks every image on the page, and the failure is a
                // broken-image icon with no error anywhere — the CSP is working exactly as written.
                // 'self' and nothing else: an image from another origin is an exfiltration beacon
                // that fires when a reviewer opens the finding.
                // connect-src 'self' is what lets the editor upload an image without a page
                // navigation. Under default-src 'none' a fetch() fails before the request leaves the
                // browser; 'self' bounds it to this origin, so a script that was somehow injected
                // still has nowhere to send what it reads.
                "default-src 'none'; style-src 'self'; script-src 'self'; img-src 'self'; "
                        + "connect-src 'self'; form-action 'self'; frame-ancestors 'none'; "
                        + "base-uri 'none'");
        exchange.getResponseHeaders().add("Referrer-Policy", "no-referrer");
        // The representation is data, never a document. A JSON body rendered as HTML by a browser that
        // sniffs the type turns an ingested finding title — attacker-authored by design — into markup.
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
