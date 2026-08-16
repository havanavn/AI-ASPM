package aspm.app.ui;

import com.ibm.icu.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Every user-facing string. {@code INT-UIX-008}, {@code INT-UIX-009}, {@code INT-UIX-010}.
 *
 * <p>{@code INT-UIX-008}: "Every user-facing string MUST be externalized with ICU message formatting for
 * plurals, gender, and ordinals. <b>String concatenation to build a sentence MUST NOT be used.</b>"
 *
 * <p>Its rationale is the design constraint: "Concatenation produces sentences that cannot be translated
 * correctly because word order differs by language. <b>ICU is required rather than simple substitution
 * because plural rules differ in ways a substitution cannot express.</b>"
 *
 * <p>That last clause decided a dependency. This started on {@code java.text.MessageFormat}, which is
 * not ICU: it has {@code choice} and no {@code plural}, so a bundle written in ICU syntax throws at
 * format time rather than failing at build time. Calling that ICU would be exactly the claim the
 * requirement exists to prevent, so {@code com.ibm.icu.text.MessageFormat} is used instead — and it is
 * the one client-side-shaped dependency ADR-058 accepts, on the server, because the alternative was a
 * plural implementation of our own for every language the platform will ever ship.
 *
 * <p>So the renderer takes message <i>keys</i>, never text. A sentence assembled from two calls has
 * nowhere to be written, because there is no method that appends one rendered string to another.
 *
 * <h2>The pseudo-locale is a build gate, not a debugging aid</h2>
 *
 * <p>{@code INT-UIX-009} requires pseudo-localization to pass "without layout failure, string truncation,
 * or untranslated string leakage", and its rationale says why it is the test that matters: "Pseudo
 * localization is the only test that finds hardcoded strings and layout assumptions before a real locale
 * is added, and it does so without needing a translation."
 *
 * <p>{@link #PSEUDO} transforms every string in the source bundle — accents, and a length expansion of
 * roughly a third to model German and Vietnamese diacritic width. A string that appears untransformed in
 * a rendered page under this locale was never externalized, and the interface test asserts exactly that.
 */
public final class Messages {

    /** The pseudo-locale. Not a language: a locale whose only job is to make hardcoded text visible. */
    public static final Locale PSEUDO = Locale.forLanguageTag("qps-ploc");

    /** Source locale is English; Vietnamese is the first target locale (DOC-01 §4.3, DOC-08 §12). */
    public static final Locale SOURCE = Locale.ENGLISH;
    public static final Locale VIETNAMESE = Locale.forLanguageTag("vi");

    private static final String BUNDLE = "aspm.app.ui.messages";

    private final Locale locale;
    private final ResourceBundle bundle;

    private Messages(Locale locale, ResourceBundle bundle) {
        this.locale = locale;
        this.bundle = bundle;
    }

    public static Messages forLocale(Locale locale) {
        Objects.requireNonNull(locale, "a locale is required");
        if (PSEUDO.equals(locale)) {
            return new Messages(PSEUDO, ResourceBundle.getBundle(BUNDLE, SOURCE));
        }
        return new Messages(locale, ResourceBundle.getBundle(BUNDLE, locale));
    }

    public Locale locale() {
        return locale;
    }

    /**
     * A message, or the supplied text where the bundle has no such key.
     *
     * <p>For keys derived from TENANT DATA — a workflow event code, a state code, a guard name. Those
     * are open sets by design (ADR-027), so a tenant adding a transition would otherwise take down
     * the page that displays it. {@link #get} keeps throwing, and must: a missing string for a
     * PRODUCT key is a defect and rendering the key is how it gets noticed.
     *
     * <p>This method exists because the request page did exactly that. A guard that returned its own
     * description rather than a code was concatenated into a message key, and the resulting lookup
     * threw — turning "this move is blocked, here is why" into a 500 on the whole request.
     */
    public String getOr(String key, String fallback, Object... arguments) {
        try {
            return get(key, arguments);
        } catch (java.util.MissingResourceException absent) {
            return fallback;
        }
    }

    /**
     * Formats a message.
     *
     * @throws MissingResourceException where the key is absent. Deliberately not a fallback to the key
     *     itself: a missing string that renders as {@code findings.title} looks like a defect somebody
     *     will file, and a missing string that renders as nothing looks like an empty region — which
     *     DOC-08 §9 says reads as "nothing to report"
     */
    public String get(String key, Object... arguments) {
        Objects.requireNonNull(key, "a message key is required");
        String pattern = bundle.getString(key);
        // The PATTERN is pseudo-localized, not the formatted result. Transforming afterwards accented
        // the substituted values too — so a test asserting that a tenant name survives the pseudo-locale
        // failed, and it was right to: the point of the exercise is to test the layout with the real
        // values a user will see, expanded around them.
        String effective = PSEUDO.equals(locale) ? pseudo(pattern) : pattern;
        if (arguments.length == 0) {
            return effective;
        }
        // ICU formatting uses the SOURCE locale's rules under the pseudo-locale: qps-ploc has no plural
        // rules of its own, and falling back to the root locale would select a different category than
        // the source bundle was written for.
        Locale formatting = PSEUDO.equals(locale) ? SOURCE : locale;
        return new MessageFormat(effective, formatting).format(arguments);
    }

    /** Whether a key exists, for the test that asserts the bundles agree. */
    public boolean has(String key) {
        return bundle.containsKey(key);
    }

    public java.util.Set<String> keys() {
        return bundle.keySet();
    }

    /**
     * Accents every letter of a PATTERN and pads by roughly a third.
     *
     * <p><b>Depth parity decides what is transformed</b>, and the rule is not cosmetic. In ICU, a brace
     * at even depth opens an argument and a brace at odd depth opens a sub-message:
     *
     * <pre>{@code {0, plural, one {# row} other {# rows}} }</pre>
     *
     * <p>{@code 0, plural, one} sits at depth 1 — keywords, and accenting {@code other} makes the
     * pattern unparseable. {@code # row} sits at depth 2 — a sentence a user reads, and it must be
     * transformed. A nested {@code {1}} inside that sub-message returns to depth 3 and is left alone.
     *
     * <p>Skipping everything inside braces was the first version, and it left every plural sub-message
     * untransformed — so {@code 1 row} rendered unaccented under the pseudo-locale. That is the part of
     * the string most likely to be long in translation, and it was the part the gate did not check.
     */
    private static String pseudo(String text) {
        StringBuilder out = new StringBuilder("⟦");
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
                out.append(c);
                continue;
            }
            if (c == '}') {
                depth--;
                out.append(c);
                continue;
            }
            out.append(depth % 2 == 0 ? accent(c) : c);
        }
        // The expansion. A layout that fits the source and not this one fails on the first real
        // translation, and the failure is found by a user rather than by the build.
        int padding = Math.max(2, out.length() / 3);
        out.append("·".repeat(padding)).append("⟧");
        return out.toString();
    }

    private static char accent(char c) {
        return switch (c) {
            case 'a' -> 'á'; case 'e' -> 'é'; case 'i' -> 'í'; case 'o' -> 'ó'; case 'u' -> 'ü';
            case 'A' -> 'Á'; case 'E' -> 'É'; case 'I' -> 'Í'; case 'O' -> 'Ó'; case 'U' -> 'Ü';
            case 'c' -> 'ç'; case 'n' -> 'ñ'; case 's' -> 'š'; case 'y' -> 'ý';
            default -> c;
        };
    }
}
