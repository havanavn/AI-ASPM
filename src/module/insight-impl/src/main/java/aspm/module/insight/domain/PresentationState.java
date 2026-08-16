package aspm.module.insight.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * The seven presentation states of DOC-08 section 9. {@code PRD-UIX-022} to {@code PRD-UIX-025}.
 *
 * <p>DOC-08 lists six under "U3. Six states, each visually distinct from the others" and its table carries seven
 * rows — the two empty states are separately specified and separately presented. Seven is what an implementation
 * needs, and conflating the two empty ones is the specific mistake the table's wording guards against.
 *
 * <h2>{@code PRD-UIX-022} is the one that matters</h2>
 *
 * <p>"Rendering unmeasured as zero is <b>the interface-layer expression of the PP-1 failure the whole corpus
 * guards against</b>: a favourable figure produced by absent data."
 *
 * <p>Every other honesty mechanism in the platform — the coverage qualifier, the confidence band, the
 * never-submitted state, the closure guard — exists to keep an unmeasured thing distinguishable from a measured
 * clean one. All of it is undone by a component that renders {@code null} as {@code 0}.
 *
 * <p>So {@link #UNMEASURED} is a state rather than a value, {@link #render} refuses to produce a numeral for it,
 * and a caller cannot reach a figure without going through a state that knows whether there is one.
 */
public enum PresentationState {

    /** Skeleton matching the eventual layout; never a spinner replacing content that will appear in place. */
    LOADING,

    /** Nothing here yet. The presentation says what belongs here and the action that creates it. */
    EMPTY_NO_DATA,

    /**
     * Filtered out. <b>Distinct from having no data.</b>
     *
     * <p>Conflating them tells a user their estate is clean when their filter is wrong, and the two look
     * identical in a table with no rows.
     */
    EMPTY_FILTERED,

    /**
     * Explicitly unmeasured, with the action that would measure it. <b>Never rendered as zero.</b>
     */
    UNMEASURED,

    /**
     * The field is present but not shown to this viewer.
     *
     * <p>{@code PRD-UIX-023} makes this the <b>narrow exception</b> to absence: "A masked placeholder confirms
     * the field has a value, which for a secret finding confirms a credential exists at that location." Withheld
     * is for the case where absence would misrepresent the object, and it must not confirm a value exists.
     */
    WITHHELD,

    /**
     * The capability is unavailable. States which, why, and what remains.
     *
     * <p>{@code PRD-UIX-024}: "An empty section in an executive report reads as 'nothing to report'."
     */
    DEGRADED,

    /** What happened, why, what to do. Never a code or a trace ({@code PRD-UIX-025}). */
    ERROR;

    /** Whether this state permits a numeral to be rendered. */
    public boolean carriesAFigure() {
        return false;
    }

    /**
     * Renders the state.
     *
     * @param detail what the state needs: the creating action for {@code EMPTY_NO_DATA}, the active filters for
     *     {@code EMPTY_FILTERED}, the measuring action for {@code UNMEASURED}, the unavailable capability and
     *     what remains for {@code DEGRADED}, and what to do for {@code ERROR}
     * @throws IllegalArgumentException where a state that needs detail is given none. A bare "no data" tells a
     *     user nothing they did not already see
     */
    public String render(String detail) {
        Objects.requireNonNull(detail, "detail is required; see the parameter documentation");
        if (detail.isBlank() && this != LOADING) {
            throw new IllegalArgumentException(
                    this + " requires detail. A bare state tells a user nothing they did not already see from "
                            + "the empty region, and the detail is the entire difference between the seven "
                            + "states (DOC-08 section 9).");
        }
        return switch (this) {
            case LOADING -> "Loading…";
            case EMPTY_NO_DATA -> "Nothing here yet. " + detail;
            case EMPTY_FILTERED -> "No results for the active filters. " + detail;
            // The word, never a numeral. PRD-UIX-022.
            case UNMEASURED -> "Not measured. " + detail;
            // No value, no length, no mask token — the placeholder is what confirms a value exists.
            case WITHHELD -> "Not shown to you.";
            case DEGRADED -> "Unavailable: " + detail;
            case ERROR -> detail;
        };
    }

    /**
     * Chooses the state for a measure, given whether it was measured.
     *
     * <p>The decision point. A component asking "is the value null" gets {@code EMPTY_NO_DATA} or a zero; asking
     * this gets {@code UNMEASURED}, because the caller has to supply {@code measuredPopulation} and cannot
     * answer without knowing it.
     *
     * @param measuredPopulation how many in-scope objects contributed. Zero means unmeasured, whatever the
     *     value says
     */
    public static Optional<PresentationState> forMeasure(int measuredPopulation, int inScopePopulation,
            boolean filterActive) {
        if (inScopePopulation == 0) {
            return Optional.of(filterActive ? EMPTY_FILTERED : EMPTY_NO_DATA);
        }
        if (measuredPopulation == 0) {
            return Optional.of(UNMEASURED);
        }
        // A figure is presentable. Empty means "render the number", which is the only case where a component
        // may produce a numeral.
        return Optional.empty();
    }

    /**
     * The rule {@code PRD-UIX-025} states, as a check.
     *
     * <p>"Error presentation MUST NOT include stack traces, framework or dependency versions, internal
     * hostnames, or query fragments. Each is reconnaissance, and <b>error surfaces are among the platform's
     * least-reviewed output paths</b>."
     */
    public static void assertErrorTextIsSafe(String text) {
        Objects.requireNonNull(text, "error text is required");
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String marker : java.util.List.of("\tat ", "exception", "caused by", "select ", "insert into",
                ".internal", ".local", "jdbc:", "stacktrace")) {
            if (lower.contains(marker)) {
                throw new IllegalArgumentException(
                        "error presentation contains material matching a reconnaissance class (PRD-UIX-025). "
                                + "Error surfaces are among the platform's least-reviewed output paths, which "
                                + "is why the check is here rather than in a review.");
            }
        }
    }
}
