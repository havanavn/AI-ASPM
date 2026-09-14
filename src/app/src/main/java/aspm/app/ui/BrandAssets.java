package aspm.app.ui;

import aspm.app.runtime.Dispatcher;
import java.io.IOException;
import java.io.InputStream;
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
 * <p>One route per file, each file once on disk, references by URL. The old note said inlining saved
 * a request; it did, and a request is cheaper than a brand that drifts.
 *
 * <p>Two marks, both supplied by the product owner as PNG on 2026-09-13 and resized here at build
 * time from the originals in the repository's {@code image/} folder: the square {@code icon.png}
 * (the shield alone, for favicons and anywhere the name is printed beside it) and the wordmark
 * {@code logo.png} (shield plus "AI ASPM", for the sign-in page and the sidebar head). The earlier
 * hand-drawn SVG is gone rather than kept as a third variant nobody chooses on purpose.
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

    /** {@code GET /brand/icon.png}. The square mark: favicon, sidebar, anywhere the name is beside it. */
    public static Dispatcher.Response icon(Dispatcher.Request request) throws IOException {
        return serve("icon.png", "image/png");
    }

    /** {@code GET /brand/logo.png}. The wordmark — mark plus name — for the sign-in page and the sidebar head. */
    public static Dispatcher.Response wordmark(Dispatcher.Request request) throws IOException {
        return serve("logo.png", "image/png");
    }

    /**
     * {@code GET /brand/logo-dark.png}. The wordmark for a dark ground.
     *
     * <p>Two files rather than one recoloured by CSS, because the mark is artwork: the shield keeps its
     * own colours in both and only the word changes, from navy to near-white. A filter that inverted
     * the whole image would invert the shield with it.
     */
    public static Dispatcher.Response wordmarkDark(Dispatcher.Request request) throws IOException {
        return serve("logo-dark.png", "image/png");
    }

    /** {@code GET /brand/copilot.png}. The copilot's launcher, on every page but the sign-in one. */
    public static Dispatcher.Response copilot(Dispatcher.Request request) throws IOException {
        return serve("copilot.png", "image/png");
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

}
