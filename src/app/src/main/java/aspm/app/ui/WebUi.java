package aspm.app.ui;

import aspm.app.runtime.Dispatcher;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The built React interface, served from the classpath.
 *
 * <h2>Why the bundle is served by this tier and not by a separate host</h2>
 *
 * <p>The session cookie is {@code HttpOnly; SameSite=Strict}. That is what makes the interface unable
 * to leak a credential — there is no token in JavaScript to steal — and it only works while the
 * interface and the API share an origin. A bundle served from a CDN would need either a cross-site
 * cookie, which {@code SameSite=Strict} exists to forbid, or a token in browser storage, which is the
 * arrangement that turns any script injection into full account takeover.
 *
 * <h2>What is served, and what is refused</h2>
 *
 * <p>Two things only: {@code index.html} for any interface route, and hashed files under
 * {@code assets/}. The asset name is matched against a strict pattern before it is used to open a
 * resource, so a request for {@code ../../application.yml} is a 404 and never a path that reaches
 * {@link Class#getResourceAsStream}. Vite emits content-hashed names, so the pattern is not a
 * limitation — it is the exact shape of every file the build produces.
 *
 * <h2>The SPA fallback and why it is narrow</h2>
 *
 * <p>A single-page interface needs deep links to return the shell rather than a 404. It is mounted at
 * the ROOT now, so the prefix that used to bound the fallback is gone and there is nothing left to
 * narrow it with — which is why {@link #ROUTES} enumerates the templates instead. A catch-all under a
 * root mount would answer {@code /api/…} typos with an HTML document, turning every API mistake into a
 * parse error somewhere unrelated; an enumeration cannot, because a path it does not name is a 404.
 *
 * <p>The cost of the enumeration is that it must agree with the React router, and nothing about the
 * two files makes them agree by construction — so a test asserts it. A route in one and not the other
 * is a deep link that 404s or a page nobody can reach, and both fail silently.
 */
public final class WebUi {

    /** Where the Vite build writes. Kept in one place: it is also the path {@code vite.config.ts} names. */
    private static final String ROOT = "/aspm/app/webui/";

    /**
     * The only asset names that will be opened.
     *
     * <p>No dots beyond the extension, no slashes, no traversal. Matching before resolving is the
     * point — a check applied after building a path is a check applied to a string an attacker
     * already shaped.
     */
    private static final java.util.regex.Pattern ASSET =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9]+)*\\.(js|css|map|woff2|svg|png|ico)");

    private WebUi() {
    }

    /** Whether a build is present. A deployment without one must not advertise the route. */
    public static boolean built() {
        return WebUi.class.getResource(ROOT + "index.html") != null;
    }

    /**
     * Where an authenticated caller lands.
     *
     * <p>The React interface, when there is one. Everything a person does daily lives there now, and
     * the two surfaces are not equivalent: the server-rendered pages edit prose in a plain textarea,
     * so a user who lands on {@code /ui} never sees the rich-text editor at all and reasonably
     * concludes it was not built. Landing them on the interface that has it is the whole fix.
     *
     * <p>It falls back rather than hardcoding, because a deployment with no bundle on the classpath
     * would otherwise redirect every successful sign-in to a 404 — the authentication surface is the
     * worst possible place to discover that the build step was skipped.
     */
    public static String landing() {
        // One answer now. This used to choose between /app/overview and /ui/overview, falling back to
        // the server-rendered overview when no bundle was on the classpath. That page no longer exists,
        // so there is nothing to fall back TO: a deployment with no bundle has no interface, and
        // pretending otherwise would send every successful sign-in to a 404 discovered at the
        // authentication surface. AspmApplication logs that condition at startup instead.
        return "/overview";
    }

    /**
     * Every path the React router declares, as route templates.
     *
     * <p><b>Enumerated, and a wildcard was tried first.</b> The shell was registered under
     * {@code /{section}} through {@code /{section}/{id}/{sub}/{subId}} — four templates covering the
     * whole interface in four lines. At the root those match THREE OF ANYTHING, so
     * {@code GET /api/v1/does-not-exist} matched the interface and was answered by it. A pipeline calling
     * a mistyped endpoint would receive 200 and an HTML page and log success, and an unrouted path would
     * stop being indistinguishable from a scope denial — {@code PRD-API-036} in both directions. It was
     * the test for that rule that caught it, not review.
     *
     * <p>So the templates are exact, and the cost is that this list has to move when the router does.
     * {@code InterfaceTest} reads {@code src/webui/src/main.tsx} and fails the build when the two
     * disagree, which is the only reason enumerating is safe.
     */
    public static final java.util.List<String> ROUTES = java.util.List.of(
            "/overview",
            "/workload",
            // A redirect inside the router, kept so an old link resolves rather than 404s.
            "/analytics",
            "/planning",
            "/vulnerabilities",
            "/pipeline",
            "/pipeline/findings/{id}",
            "/settings",
            "/composition",
            "/board",
            "/board/{id}",
            "/board/{id}/findings/{findingId}",
            "/applications",
            "/applications/new",
            "/applications/{id}",
            "/applications/{id}/edit",
            "/hosts",
            "/projects",
            "/projects/{id}",
            "/projects/{id}/edit",
            "/requests/new",
            "/organization",
            "/account",
            "/guide",
            "/api-guide",
            "/access",
            "/access/users/{id}",
            "/roles",
            "/roles/{id}");

    /**
     * {@code GET /**} and {@code GET /**} — the two retired prefixes, stripped.
     *
     * <p>Both interfaces were addressed by prefix until the single-page build took the root. Links
     * written before that are in the database — a comment body citing {@code /board/...} is a record
     * and is not rewritten — and in people's bookmarks. So the prefix is removed and the request is
     * redirected to where the page now lives.
     *
     * <p>302 rather than 301: a permanent redirect is cached by the browser until it is cleared, and a
     * mistake in this method would then be unreachable for correction on the machines that already saw it.
     */
    public static Dispatcher.Response legacyPrefix(Dispatcher.Request request) {
        String path = request.path() == null ? "/" : request.path();
        int cut = path.indexOf('/', 1);
        String stripped = cut < 0 ? "/" : path.substring(cut);
        if (stripped.isEmpty() || !stripped.startsWith("/")) {
            stripped = "/";
        }
        StringBuilder location = new StringBuilder(stripped);
        String separator = "?";
        for (Map.Entry<String, String> parameter : new java.util.TreeMap<>(request.query()).entrySet()) {
            location.append(separator)
                    .append(java.net.URLEncoder.encode(parameter.getKey(),
                            java.nio.charset.StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(parameter.getValue(),
                            java.nio.charset.StandardCharsets.UTF_8));
            separator = "&";
        }
        return new Dispatcher.Response(302, new InterfaceResource.Raw(""),
                Map.of("Location", location.toString(), "Content-Type", "text/html; charset=utf-8"));
    }

    /** {@code GET /} and every template in {@link #ROUTES} — the interface shell. */
    public static Dispatcher.Response shell(Dispatcher.Request request) throws IOException {
        Optional<byte[]> document = read("index.html");
        if (document.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/html; charset=utf-8");
        // The shell itself is never cached. It names the hashed asset files, so a stale shell is a
        // browser pinned to a deployment that no longer exists — and the failure looks like the API
        // breaking rather than the page being old.
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        // The shell carries its own policy. A Binary response bypasses respond(), which is where the
        // page policy is applied — so without this the one HTML document in the build would be the
        // only page on the platform served with no Content-Security-Policy at all.
        //
        // connect-src 'self' is what lets the interface call its own API and nothing else; a script
        // that reached this page still has nowhere to send what it reads. img-src 'self' covers the
        // inline images in a write-up. No 'unsafe-inline' anywhere: Vite emits linked files, so there
        // is nothing inline to permit.
        headers.put("Content-Security-Policy",
                "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                        + "font-src 'self'; connect-src 'self'; form-action 'self'; "
                        + "frame-ancestors 'none'; base-uri 'none'");
        headers.put("Referrer-Policy", "no-referrer");
        return new Dispatcher.Response(200, new InterfaceResource.Binary(document.orElseThrow()),
                headers);
    }

    /** {@code GET /assets/{name}} — one hashed build artifact. */
    public static Dispatcher.Response asset(Dispatcher.Request request) throws IOException {
        String name = request.pathVariables().get("name");
        if (name == null || !ASSET.matcher(name).matches()) {
            return Dispatcher.Response.notFound();
        }
        Optional<byte[]> bytes = read("assets/" + name);
        if (bytes.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType(name));
        headers.put("X-Content-Type-Options", "nosniff");
        // Immutable, because the name contains a hash of the content. A different build produces a
        // different name, so there is no version of this file that can ever change.
        headers.put("Cache-Control", "public, max-age=31536000, immutable");
        // An asset is never a document. Even if a browser were persuaded to treat one as HTML, this
        // leaves it with no script, no origin to talk to and no frame to sit in.
        headers.put("Content-Security-Policy", "default-src 'none'; sandbox");
        return new Dispatcher.Response(200, new InterfaceResource.Binary(bytes.orElseThrow()),
                headers);
    }

    private static String contentType(String name) {
        if (name.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".map") || name.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (name.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".ico")) {
            return "image/x-icon";
        }
        // Deliberately NOT image/svg+xml. An SVG is a document that executes script, and serving one
        // from this origin is the hole AttachmentService refuses uploads to avoid. A build that
        // emitted one gets it as bytes nothing will render.
        return "application/octet-stream";
    }

    private static Optional<byte[]> read(String relative) throws IOException {
        try (InputStream stream = WebUi.class.getResourceAsStream(ROOT + relative)) {
            return stream == null ? Optional.empty() : Optional.of(stream.readAllBytes());
        }
    }

    /** For the banner, so a deployment says which interface it is serving. */
    public static String describe() {
        return built() ? "React interface available at /" : "no React build on the classpath";
    }
}
