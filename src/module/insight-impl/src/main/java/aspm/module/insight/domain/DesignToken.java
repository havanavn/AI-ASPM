package aspm.module.insight.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Design tokens. DOC-08: "Every visual value is a token; no literals in components. Theming, density and
 * high-contrast mode are <b>token substitutions</b>."
 *
 * <h2>Why substitution rather than a component-level branch</h2>
 *
 * <p>A component that branches on theme has to branch correctly in every component, and the one that does not is
 * found by a user in high-contrast mode. Substitution moves the decision to one place: a component names a
 * token, and the theme decides what the token resolves to.
 *
 * <p>It also makes the honesty requirement checkable. {@code PRD-UIX-026} forbids suppressing an honesty surface
 * by theme, density or preference — and a token set can be asserted to resolve every token in every mode, which
 * a set of component branches cannot.
 *
 * <h2>Colour is never the sole carrier of meaning</h2>
 *
 * <p>DOC-00's prohibited-patterns table lists it, and it applies here rather than only to diagrams: a token
 * carrying a semantic meaning declares a non-colour channel alongside. High-contrast and monochrome print are
 * the modes where a colour-only signal disappears, and both are in {@link Mode}.
 */
public final class DesignToken {

    /** A rendering mode. Every token must resolve in each. */
    public enum Mode {
        LIGHT,
        DARK,
        HIGH_CONTRAST,
        PRINT_MONOCHROME
    }

    /** How a token's meaning is carried, beyond colour. */
    public enum NonColourChannel {
        /** No semantic meaning; the token is decoration and needs no second channel. */
        NONE,
        SHAPE,
        ICON,
        TEXT_LABEL,
        POSITION,
        BORDER_STYLE
    }

    private final String name;
    private final NonColourChannel nonColourChannel;
    private final Map<Mode, String> valuesByMode;

    private DesignToken(String name, NonColourChannel nonColourChannel, Map<Mode, String> valuesByMode) {
        this.name = Objects.requireNonNull(name, "a token name is required");
        this.nonColourChannel = Objects.requireNonNull(nonColourChannel,
                "a non-colour channel is required, NONE where the token is decoration");
        this.valuesByMode = Map.copyOf(Objects.requireNonNull(valuesByMode, "values are required"));

        for (Mode mode : Mode.values()) {
            if (!this.valuesByMode.containsKey(mode)) {
                throw new IllegalArgumentException(
                        "token '" + name + "' has no value for " + mode + ". A token missing a mode falls back "
                                + "to whatever the component would have done, which is the literal the token "
                                + "exists to remove — and the mode it is missing is always the one nobody "
                                + "opens.");
            }
        }
        if (nonColourChannel == NonColourChannel.NONE && name.contains("semantic")) {
            throw new IllegalArgumentException(
                    "token '" + name + "' carries semantic meaning with no non-colour channel. Colour as the "
                            + "sole carrier of meaning fails accessibility and monochrome review (DOC-00), and "
                            + "high-contrast and print are the modes where it disappears entirely.");
        }
    }

    public static DesignToken decoration(String name, Map<Mode, String> valuesByMode) {
        return new DesignToken(name, NonColourChannel.NONE, valuesByMode);
    }

    /**
     * A token whose colour carries meaning, with the channel that carries it where colour cannot.
     *
     * @param nonColourChannel must not be {@link NonColourChannel#NONE}
     */
    public static DesignToken semantic(String name, NonColourChannel nonColourChannel,
            Map<Mode, String> valuesByMode) {
        if (nonColourChannel == NonColourChannel.NONE) {
            throw new IllegalArgumentException(
                    "a semantic token needs a non-colour channel; that is what makes it semantic rather than "
                            + "decorative");
        }
        return new DesignToken(name, nonColourChannel, valuesByMode);
    }

    public String resolve(Mode mode) {
        Objects.requireNonNull(mode, "a mode is required");
        return valuesByMode.get(mode);
    }

    public String name() {
        return name;
    }

    public NonColourChannel nonColourChannel() {
        return nonColourChannel;
    }

    /**
     * The shipped token set for the seven presentation states.
     *
     * <p>Each is semantic, so each declares its non-colour channel. The unmeasured token's channel is a
     * <b>text label</b> and not an icon: an icon is a convention a reader learns, and the one reader who has not
     * learnt it reads an unmeasured tile as an empty one.
     */
    public static Map<PresentationState, DesignToken> stateTokens() {
        Map<PresentationState, DesignToken> tokens = new LinkedHashMap<>();
        tokens.put(PresentationState.LOADING, semantic("semantic.state.loading",
                NonColourChannel.SHAPE, uniform("skeleton")));
        tokens.put(PresentationState.EMPTY_NO_DATA, semantic("semantic.state.empty-no-data",
                NonColourChannel.TEXT_LABEL, uniform("empty-no-data")));
        tokens.put(PresentationState.EMPTY_FILTERED, semantic("semantic.state.empty-filtered",
                NonColourChannel.TEXT_LABEL, uniform("empty-filtered")));
        tokens.put(PresentationState.UNMEASURED, semantic("semantic.state.unmeasured",
                NonColourChannel.TEXT_LABEL, uniform("unmeasured")));
        tokens.put(PresentationState.WITHHELD, semantic("semantic.state.withheld",
                NonColourChannel.TEXT_LABEL, uniform("withheld")));
        tokens.put(PresentationState.DEGRADED, semantic("semantic.state.degraded",
                NonColourChannel.ICON, uniform("degraded")));
        tokens.put(PresentationState.ERROR, semantic("semantic.state.error",
                NonColourChannel.ICON, uniform("error")));
        return Map.copyOf(tokens);
    }

    private static Map<Mode, String> uniform(String value) {
        Map<Mode, String> values = new LinkedHashMap<>();
        for (Mode mode : Mode.values()) {
            values.put(mode, value);
        }
        return values;
    }
}
