package aspm.module.aiassistance.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Narrative with placeholders bound to retrieved record fields. {@code PRD-AIC-034}, ADR-038.
 *
 * <p>"Output MUST NOT contain a numeric value the platform did not compute. Numeric values MUST be substituted
 * from the retrieved records rather than generated."
 *
 * <p>DOC-10's note on the implementation is the design: "the model produces a narrative with placeholders bound
 * to retrieved record fields, and the platform fills them. <b>This is stronger than validating generated numbers
 * against sources, because it makes an incorrect number unrepresentable rather than detectable.</b>"
 *
 * <h2>Why validation would not be enough</h2>
 *
 * <p>A validator compares generated numbers to source records and rejects mismatches. It fails on the number it
 * does not recognise as a number — "roughly a third", "double last quarter", "the majority" — and those are
 * exactly the phrasings a model reaches for. Substitution has no such gap: the model cannot emit a figure at all,
 * because {@link #bind} rejects any digit outside a placeholder.
 *
 * <p>The cost is that the model cannot phrase a comparison naturally. That is accepted: DOC-10 records that "a
 * plausible number in a security report is indistinguishable from a correct one to its reader".
 */
public final class NarrativeBinding {

    /** {@code {{field.path}}}. Deliberately narrow: no expressions, no formatting directives, no defaults. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9_.]{0,63})}}");

    /** Any digit. Checked against the template with placeholders removed. */
    private static final Pattern ANY_DIGIT = Pattern.compile("\\d");

    /**
     * Words that express a quantity without a digit.
     *
     * <p>The gap a numeric validator leaves. A model told not to emit numbers reaches for these, and "roughly a
     * third of your services are affected" is a numeric claim the platform did not compute — indistinguishable
     * to its reader from one it did.
     */
    private static final Set<String> QUANTITY_WORDS = Set.of(
            "roughly", "approximately", "about", "around", "nearly", "almost",
            "half", "third", "quarter", "double", "triple", "twice", "majority", "most", "few",
            "several", "many", "dozens", "hundreds", "thousands", "significantly", "substantially");

    /** The result of binding. Carries what it used, so the citation check has something to resolve. */
    public record Bound(String text, Set<String> boundFields) {

        public Bound {
            Objects.requireNonNull(text, "text is required");
            boundFields = Set.copyOf(Objects.requireNonNull(boundFields, "boundFields are required"));
        }
    }

    private NarrativeBinding() {
    }

    /**
     * Binds a model-produced template to retrieved record fields.
     *
     * @param template the model's output. Must contain no digit and no quantity word outside a placeholder
     * @param retrievedFields the values, from records the platform read. A placeholder with no field is an
     *     error rather than an empty string: rendering "we found  criticals" is worse than failing
     * @throws IllegalArgumentException on a generated number, a quantity word, or an unbound placeholder
     */
    public static Bound bind(String template, Map<String, String> retrievedFields) {
        Objects.requireNonNull(template, "a template is required");
        Objects.requireNonNull(retrievedFields, "retrieved fields are required");

        String withoutPlaceholders = PLACEHOLDER.matcher(template).replaceAll(" ");

        if (ANY_DIGIT.matcher(withoutPlaceholders).find()) {
            throw new IllegalArgumentException(
                    "the narrative contains a digit outside a placeholder (PRD-AIC-034). Numeric values are "
                            + "SUBSTITUTED from retrieved records rather than generated, which makes an "
                            + "incorrect number unrepresentable rather than detectable — models generate "
                            + "plausible numbers, and a plausible number in a security report is "
                            + "indistinguishable from a correct one to its reader.");
        }

        Set<String> offendingWords = new LinkedHashSet<>();
        // Limit -1: trailing empty segments are harmless here, but the default's behaviour is the one Error
        // Prone warns about and this file's whole subject is not relying on surprising defaults.
        for (String word : withoutPlaceholders.toLowerCase(java.util.Locale.ROOT).split("[^a-z]+", -1)) {
            if (QUANTITY_WORDS.contains(word)) {
                offendingWords.add(word);
            }
        }
        if (!offendingWords.isEmpty()) {
            throw new IllegalArgumentException(
                    "the narrative expresses a quantity in words: " + offendingWords + ". This is the gap a "
                            + "numeric validator leaves — 'roughly a third of your services are affected' is a "
                            + "numeric claim the platform did not compute, and it is exactly the phrasing a "
                            + "model reaches for when told not to emit digits (PRD-AIC-034).");
        }

        Set<String> bound = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String field = matcher.group(1);
            String value = retrievedFields.get(field);
            if (value == null) {
                throw new IllegalArgumentException(
                        "placeholder {{" + field + "}} has no retrieved value. An unbound placeholder renders "
                                + "as an absence — 'we found  criticals' — which is worse than failing, "
                                + "because a reader completes the sentence themselves.");
            }
            bound.add(field);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);

        return new Bound(result.toString(), bound);
    }

    /**
     * The fields a template references, for validating a template before it is bound.
     *
     * <p>{@code PRD-NTF-033}'s reasoning applies here too: a template referencing an absent field "fails at
     * delivery, which is the worst time to discover it".
     */
    public static Set<String> referencedFields(String template) {
        Objects.requireNonNull(template, "a template is required");
        Set<String> fields = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            fields.add(matcher.group(1));
        }
        return Set.copyOf(fields);
    }
}
