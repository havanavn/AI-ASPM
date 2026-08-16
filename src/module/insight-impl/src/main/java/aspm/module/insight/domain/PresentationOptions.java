package aspm.module.insight.domain;

import java.util.Objects;
import java.util.Set;

/**
 * Everything a reader or a template may vary about a rendering. {@code H14}, {@code TST-DSH-001}.
 *
 * <p>{@code PRD-UIX-026} and {@code PRD-DSH-042}: <b>no honesty surface is suppressible by theme, density,
 * template, or user preference.</b> {@code TST-DSH-001} requires the assertion across every combination,
 * "because the suppression paths differ — a template removes a section, a density setting hides a secondary
 * line, a preference turns off extra detail — and each would defeat the same mechanism. Testing one path leaves
 * the others open."
 *
 * <h2>The design is what this type does not have</h2>
 *
 * <p>There is no {@code hideQualifiers}, no {@code showCoverage}, no {@code sections} list a template could omit
 * a qualifier from, and no free-form preference map. Every field below varies <i>how</i> something is rendered,
 * never <i>whether</i>.
 *
 * <p>That is the whole mechanism, and DOC-16 says why it needs to be structural: the honesty suite "exists
 * because every mechanism in it is individually easy to remove for a cleaner interface." A boolean here would
 * be set to false by somebody making a dashboard look tidier, in a commit nobody reads as a security change.
 *
 * <p>{@link #all()} enumerates the full combination space so the test can assert over it rather than over a
 * sample.
 */
public record PresentationOptions(Theme theme, Density density, Template template, boolean preferTerseLabels,
        boolean preferNumericFirst) {

    public enum Theme {
        LIGHT,
        DARK,
        /** Deliberately included: a monochrome render is where colour-carried meaning would vanish. */
        HIGH_CONTRAST,
        PRINT
    }

    public enum Density {
        COMFORTABLE,
        COMPACT,
        /** The densest setting, and the one a "hide secondary lines" implementation would key off. */
        CONDENSED
    }

    public enum Template {
        EXECUTIVE_SUMMARY,
        OPERATIONAL_DETAIL,
        ASSESSMENT_REPORT,
        SCHEDULED_EMAIL
    }

    public PresentationOptions {
        Objects.requireNonNull(theme, "a theme is required");
        Objects.requireNonNull(density, "a density is required");
        Objects.requireNonNull(template, "a template is required");
    }

    public static PresentationOptions defaults() {
        return new PresentationOptions(Theme.LIGHT, Density.COMFORTABLE, Template.OPERATIONAL_DETAIL,
                false, false);
    }

    /**
     * Every combination — one hundred and ninety-two, which is small enough to assert over exhaustively, and
     * exhaustive is what {@code TST-DSH-001} asks for since a sample leaves the untested paths open.
     */
    public static Set<PresentationOptions> all() {
        var combinations = new java.util.LinkedHashSet<PresentationOptions>();
        for (Theme theme : Theme.values()) {
            for (Density density : Density.values()) {
                for (Template template : Template.values()) {
                    for (boolean terse : new boolean[] {false, true}) {
                        for (boolean numericFirst : new boolean[] {false, true}) {
                            combinations.add(new PresentationOptions(theme, density, template, terse,
                                    numericFirst));
                        }
                    }
                }
            }
        }
        return Set.copyOf(combinations);
    }
}
