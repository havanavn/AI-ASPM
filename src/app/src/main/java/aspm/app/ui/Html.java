package aspm.app.ui;

import java.util.Locale;
import java.util.Objects;

/**
 * HTML escaping and a minimal element builder.
 *
 * <p><b>The escaping here is not a formality.</b> Indirect prompt injection through ingested findings is
 * the fifth of the platform's highest-risk surfaces, and its defining property is that finding content
 * <i>legitimately</i> includes attacker-authored text: a finding title is whatever a scanner extracted
 * from a payload, and the attacker who authored the payload needed no platform access to put it there.
 *
 * <p>So every value that reaches a page passes through {@link #text} or {@link #attribute}. The builder
 * has no method that emits an unescaped value, which is the difference between escaping being applied and
 * escaping being remembered.
 *
 * <h2>Attribute and text are different escapes</h2>
 *
 * <p>Escaping for text content and escaping for an attribute value are not the same operation, and using
 * one where the other belongs is the standard way a payload survives. An unquoted attribute needs
 * whitespace and slash escaped as well, so {@link #attribute} always quotes and escapes the quote.
 */
public final class Html {

    private Html() {
    }

    /** Escapes text content. */
    public static String text(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Escapes an attribute value and returns it quoted, including the quotes.
     *
     * <p>Quoted by this method rather than by the caller: a caller that forgets the quotes turns every
     * space in the value into an attribute boundary, and the escape above does not cover that case
     * because it does not have to when the value is quoted.
     */
    public static String attribute(String value) {
        return "\"" + text(value) + "\"";
    }

    /**
     * A CSS custom property name, validated rather than escaped.
     *
     * <p>Token names are developer-supplied today and a plausible candidate for tenant theming tomorrow.
     * Validating the shape is the check that survives that change; escaping for a CSS context is a
     * different operation from escaping for HTML and would be the wrong one here.
     */
    public static String cssIdentifier(String value) {
        Objects.requireNonNull(value, "a token name is required");
        if (!value.toLowerCase(Locale.ROOT).matches("^[a-z][a-z0-9-]{0,63}$")) {
            throw new IllegalArgumentException(
                    "'" + value + "' is not an acceptable CSS identifier. Token names are interpolated "
                            + "into a stylesheet, so they are validated rather than escaped.");
        }
        return value;
    }
}
