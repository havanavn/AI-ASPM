package aspm.app.ui;

import aspm.app.identity.IdentityService;
import aspm.app.identity.SessionPrincipalResolver;
import aspm.app.runtime.Dispatcher;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@code GET /guide} — how to use the platform, for the people who use it.
 *
 * <h2>Why a guide is part of the product rather than a wiki page beside it</h2>
 *
 * <p>Product principle 7: <b>the largest user population has the narrowest permissions and the least
 * training.</b> That population is requesters and engineering owners, and almost none of them will read
 * a document that lives somewhere they have to be told about. A guide reachable from the sidebar of the
 * screen somebody is confused by is read; the same text in a wiki is not.
 *
 * <p>It also carries two explanations the interface itself cannot give without repeating them on every
 * screen, and both are the source of the questions this platform generates:
 *
 * <ul>
 *   <li><b>Why a colleague's sidebar is longer.</b> Permission and scope are two independent things and
 *       the interface deliberately does not distinguish "no such object" from "not yours"
 *       ({@code SEC-AUZ-020}). Somewhere has to say so, or the honest refusal reads as a defect.
 *   <li><b>Why a figure is a word rather than a numeral.</b> {@code PRD-UIX-022} and product principle
 *       1. A reader who has not been told this reads "Not measured" as a rendering fault and mentally
 *       substitutes zero, which is the exact failure the rule exists to prevent.
 * </ul>
 *
 * <h2>The content is a per-locale resource, not a string bundle</h2>
 *
 * <p>{@code INT-UIX-008} requires every user-facing string to be externalized, and it is: the guide is
 * a Markdown file per locale on the classpath, so no sentence of it is compiled into this class. It is
 * not a {@link Messages} bundle because a bundle is keyed prose for interface chrome — a heading, a
 * button, a sentence with a placeholder. Splitting a document into two hundred keys would make it
 * unreadable to whoever maintains it and would gain nothing: the unit of translation for a document is
 * the document.
 *
 * <p>The five keys this page does use — its title, its subtitle, its navigation label and the failure
 * notice — are ordinary bundle keys, because they appear in the shell alongside every other label.
 *
 * <h2>It renders through the same restricted Markdown as a finding</h2>
 *
 * <p>{@link Markdown} escapes before it introduces markup, permits a closed set of elements, and refuses
 * raw HTML, non-{@code http} link schemes and images by URL. The guide is trusted content and none of
 * that is needed for it — which is the reason to use it anyway. A second rendering path for "trusted"
 * Markdown is a path somebody later points at untrusted content, and the two would then disagree about
 * what markup exists. One renderer, one answer.
 *
 * <h2>Why class G, and why the handler still demands a session</h2>
 *
 * <p>Class G, for the reason recorded at {@link AccountPages}: the seven classes of ADR-036 have no
 * shape for an operation authorized by identity rather than by a catalogue permission, and naming one
 * here would hide the guide from the principal who most needs it — the roleless account the deployment
 * bootstrap creates, whose first question is why they can see nothing.
 *
 * <p>The session check inside the handler is not ceremony. The guide names permission codes, scope
 * modes and workflow states, which together describe the shape of the authorization model;
 * {@code PRD-API-036} withholds exactly that from an unauthenticated caller, and a page that recited it
 * to anyone who asked would hand back what the service document is careful not to.
 */
public final class GuidePage {

    /** Where the per-locale documents live on the classpath. */
    private static final String RESOURCE_ROOT = "/aspm/app/ui/guide/";

    /**
     * Locales with a translated guide, by language tag.
     *
     * <p>An explicit list rather than "try the tag, fall back on failure". A missing resource and a
     * misspelled one are indistinguishable to {@code getResourceAsStream}, so a translation that was
     * renamed by accident would silently serve English and nothing would report it —
     * {@link #translations()} exists so the interface test can assert the set instead.
     */
    private static final List<String> TRANSLATED = List.of("en", "vi");

    private final SessionPrincipalResolver resolver;

    public GuidePage(DataSource dataSource, UUID tenantId) {
        this.resolver = new SessionPrincipalResolver(Objects.requireNonNull(dataSource), tenantId);
    }

    /** The language tags a guide exists for, for the test that asserts each one loads. */
    static List<String> translations() {
        return TRANSLATED;
    }

    // GET /guide was here and is gone. The React interface owns that address now: it had no route for
    // it, so the sidebar link matched the catch-all and drew an empty page over this document. Adding a
    // React route while this handler stayed would have made one URL mean two different pages depending
    // on whether the reader clicked or refreshed, which is worse than either page alone.
    //
    // What survives is the part that was always the value: the documents, and the rules for choosing
    // one. They are served as JSON by UiApi and rendered by the same Markdown renderer as before.

    /**
     * The API document for a locale, on the same fallback rules as the user guide.
     *
     * <p>A second document rather than a section of the first, because the two have different readers:
     * one is opened by somebody who cannot find a number, the other by somebody wiring a pipeline. A
     * guide that serves both serves neither, and the API half is the half that gets skimmed past.
     */
    static String loadApi(Locale locale) {
        String language = locale == null ? "" : locale.getLanguage().toLowerCase(Locale.ROOT);
        String chosen = TRANSLATED.contains(language) ? language : "en";
        String content = read(RESOURCE_ROOT + "api_" + chosen + ".md");
        return content.isBlank() && !"en".equals(chosen)
                ? read(RESOURCE_ROOT + "api_en.md") : content;
    }

    /**
     * The document for a locale, or the source-locale document.
     *
     * <p>Falls back by <b>language</b> and not by full tag, so a {@code vi-VN} caller reaches the
     * Vietnamese guide rather than the English one. The pseudo-locale reads the English document
     * deliberately: {@link Messages} pseudo-localizes patterns from the source bundle, and pseudo-
     * localizing a whole document would test nothing this page owns while making it unreadable.
     */
    static String load(Locale locale) {
        String language = locale == null ? "" : locale.getLanguage().toLowerCase(Locale.ROOT);
        String chosen = TRANSLATED.contains(language) ? language : "en";
        String content = read(RESOURCE_ROOT + "guide_" + chosen + ".md");
        if (content.isBlank() && !"en".equals(chosen)) {
            // A translation that failed to load falls back to English rather than to an empty page. A
            // reader who gets the source language has been inconvenienced; one who gets nothing has
            // been told the platform has no guide.
            return read(RESOURCE_ROOT + "guide_en.md");
        }
        return content;
    }

    private static String read(String path) {
        try (InputStream stream = GuidePage.class.getResourceAsStream(path)) {
            if (stream == null) {
                return "";
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException absent) {
            return "";
        }
    }

    private static Dispatcher.Response html(String markup) {
        return new Dispatcher.Response(200, new InterfaceResource.Raw(markup),
                Map.of("Content-Type", "text/html; charset=utf-8"));
    }

    private static Dispatcher.Response redirect(String location) {
        return new Dispatcher.Response(303, new InterfaceResource.Raw(""), Map.of(
                "Location", location, "Content-Type", "text/html; charset=utf-8"));
    }
}
