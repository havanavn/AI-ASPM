package aspm.app.ui;

import java.util.Locale;
import java.util.Objects;

/**
 * The layout for unauthenticated surfaces. DOC-08, ADR-058.
 *
 * <p>Deliberately not the application shell. A sign-in page with a sidebar full of links the caller
 * cannot follow is a page that looks broken, and the shell carries a scope indicator for a scope that
 * does not exist yet.
 *
 * <h2>Motion, and the one rule it obeys</h2>
 *
 * <p>The page animates: the panel rises, the field focus ring settles, the background gradient drifts.
 * Every one of those sits inside {@code prefers-reduced-motion}, which the design system already honours
 * globally — {@code INT-UIX-004} requires the preference to be respected, and vestibular disorders are a
 * real accessibility concern rather than a taste question.
 *
 * <p>Nothing animated is load-bearing. The form works with animation disabled, with CSS unavailable, and
 * with script blocked, because {@code PRD-UIX-013} forbids a capability that depends on a pointer and the
 * same reasoning covers one that depends on an animation frame.
 */
public final class AuthLayout {

    private AuthLayout() {
    }

    public static String render(Messages messages, String titleKey, String bodyHtml) {
        Objects.requireNonNull(messages, "messages are required");
        String language = messages.locale().toLanguageTag();
        String direction = switch (messages.locale().getLanguage()) {
            case "ar", "he", "fa", "ur" -> "rtl";
            default -> "ltr";
        };

        return """
            <!DOCTYPE html>
            <html lang="%s" dir="%s" data-theme="dark">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <!-- dark, stated rather than inferred.
            
                 This page used to carry no data-theme, so it fell through to prefers-color-scheme and
                 rendered LIGHT for most people — while the application it signs you into is dark
                 unconditionally. Signing in on a white page and landing on a dark one reads as two
                 products, and that is how it was reported: "the login still goes through /ui".
            
                 The palette itself is not new. The design system already carried a full dark theme; the
                 page simply never selected it, so this is one attribute rather than a second stylesheet
                 to keep in step (PP-10). -->
            <meta name="color-scheme" content="dark">
            <title>%s · %s</title>
            <link rel="stylesheet" href="/style.css">
            </head>
            <body class="auth-body">
            <div class="auth-aurora" aria-hidden="true"></div>
            <main class="auth-main" id="main">
              <div class="auth-panel">
                <div class="auth-brand">
                  <!-- The product's own mark, served from /brand/logo.svg — the same file and the same
                       <img> the application's sidebar uses. It was a CSS square with the letter "A" in
                       it, which is a placeholder wearing the confidence of a logo, and it meant the
                       first screen anybody sees was the one screen not carrying the brand. -->
                  <img class="auth-logo" src="/brand/logo.svg" alt="" aria-hidden="true"
                       width="28" height="28">
                  <span>%s</span>
                </div>
                %s
              </div>
              <p class="auth-footnote">%s</p>
            </main>
            <script src="/app.js" defer></script>
            </body>
            </html>
            """.formatted(
                Html.text(language), Html.text(direction),
                Html.text(messages.get(titleKey)), Html.text(messages.get("app.name")),
                Html.text(messages.get("app.name")),
                bodyHtml,
                Html.text(messages.get("auth.footnote")));
    }

    /** A labelled field. One helper so no call site forgets the label association. */
    /**
     * The first field of a form, which takes focus on load.
     *
     * <p>A one-field page — a six-digit code — that makes somebody click before typing is asking for a
     * step it could have taken itself. `autofocus` is an HTML attribute rather than script, so it works
     * with JavaScript unavailable, which {@code PRD-UIX-013}'s reasoning covers: nothing on this surface
     * may depend on a capability the caller might not have.
     */
    public static String firstField(Messages messages, String id, String labelKey, String type,
            String value, boolean required, String autocomplete, String hintKey) {
        return field(messages, id, labelKey, type, value, required, autocomplete, hintKey)
                .replaceFirst("<input id=", "<input autofocus id=");
    }

    public static String field(Messages messages, String id, String labelKey, String type,
            String value, boolean required, String autocomplete, String hintKey) {
        StringBuilder out = new StringBuilder();
        out.append("<div class=\"auth-field\">")
                .append("<label for=").append(Html.attribute(id)).append(">")
                .append(Html.text(messages.get(labelKey))).append("</label>")
                .append("<input id=").append(Html.attribute(id))
                .append(" name=").append(Html.attribute(id))
                .append(" type=").append(Html.attribute(type))
                .append(" value=").append(Html.attribute(value))
                .append(" autocomplete=").append(Html.attribute(autocomplete))
                .append(required ? " required" : "")
                .append(hintKey == null ? "" : " aria-describedby=" + Html.attribute(id + "-hint"))
                .append(">");
        if (hintKey != null) {
            out.append("<p class=\"auth-hint\" id=").append(Html.attribute(id + "-hint")).append(">")
                    .append(Html.text(messages.get(hintKey))).append("</p>");
        }
        out.append("</div>");
        return out.toString();
    }

    /**
     * An error block.
     *
     * <p>{@code role="alert"} so it is announced rather than only seen: a form that reports failure
     * visually alone fails a screen-reader user silently, and this is the form they cannot get past.
     */
    public static String error(String message) {
        return "<div class=\"auth-error\" role=\"alert\">" + Html.text(message) + "</div>";
    }

    public static String locale(Messages messages) {
        return messages.locale().toLanguageTag().toLowerCase(Locale.ROOT);
    }
}
