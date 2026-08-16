package aspm.app.ui;

import aspm.app.runtime.Dispatcher;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The product mark, served as files. Class G.
 *
 * <h2>Why this replaced three copies</h2>
 *
 * <p>The mark was first inlined as a data URI in three places — the single-page interface's
 * {@code index.html}, the server-rendered {@code Page} head, and a React component. That worked and it
 * was wrong for one reason: changing the logo meant changing three files, and the failure mode is that
 * somebody changes two. A product with two interfaces then shows two logos, and nothing tells anyone.
 *
 * <p>One route, one file on disk, three references to a URL. The old note said inlining saved a
 * request; it did, and a request is cheaper than a brand that drifts.
 *
 * <h2>Class G, and why that is not a concession</h2>
 *
 * <p>A logo discloses nothing, and it has to render on the sign-in page — before anybody is
 * authenticated. Requiring a session to fetch it would put a broken image on the one screen that
 * greets a person who has not signed in yet. The same reasoning already makes the stylesheet class G.
 *
 * <h2>Cached hard, on purpose</h2>
 *
 * <p>Unlike the hashed build assets this filename is stable, so a long cache would strand an old mark
 * in browsers after a rebrand. A day is long enough that nobody pays for it twice in a session and
 * short enough that a change lands without anybody being told to clear a cache.
 */
public final class BrandAssets {

    private static final String ROOT = "/aspm/app/brand/";

    private BrandAssets() {
    }

    /** {@code GET /brand/logo.svg}. */
    public static Dispatcher.Response logo(Dispatcher.Request request) throws IOException {
        return serve("logo.svg", "image/svg+xml; charset=utf-8");
    }

    /** {@code GET /brand/icon-180.png}. The home-screen tile; iOS will not take an SVG. */
    public static Dispatcher.Response touchIcon(Dispatcher.Request request) throws IOException {
        return serve("icon-180.png", "image/png");
    }

    private static Dispatcher.Response serve(String name, String contentType) throws IOException {
        try (InputStream in = BrandAssets.class.getResourceAsStream(ROOT + name)) {
            if (in == null) {
                // Absent rather than a blank 200. A missing brand asset is a packaging error, and a
                // 200 carrying nothing renders as a broken image with no way to tell why.
                return Dispatcher.Response.notFound();
            }
            return new Dispatcher.Response(200,
                    new InterfaceResource.Binary(in.readAllBytes()),
                    Map.of("Content-Type", contentType,
                            "Cache-Control", "public, max-age=86400",
                            // The mark is the same for every caller and carries nothing about them.
                            "X-Content-Type-Options", "nosniff"));
        }
    }

    /** The bytes, for anything that needs them in process rather than over HTTP. */
    static String svg() throws IOException {
        try (InputStream in = BrandAssets.class.getResourceAsStream(ROOT + "logo.svg")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
