package aspm.kernel.rulesengine.domain;

import aspm.kernel.rulesengine.contract.Condition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Definition-time validation of a condition, so that evaluation-time cost is bounded by construction.
 *
 * <p>{@code PRD-WRK-033} requires guard expressions to be "bounded in evaluation cost", and states why:
 * "a guard is evaluated on every transition attempt including denied ones". A denied attempt is the
 * cheap path for an attacker to repeat, so an expensive guard is a denial-of-service on the transition
 * path. Bounding at definition time rather than by a runtime timeout is deliberate: a timeout makes a
 * transition's availability depend on load, and {@code PRD-WRK-033} also requires determinism.
 *
 * <p>This mirrors {@code PRD-WRK-034}, which validates a workflow definition before activation for
 * reachability and terminal states. Same principle: reject the definition, not the request.
 */
public final class ConditionValidator {

    /**
     * Maximum expression depth.
     *
     * <p>Bounds recursion in {@link ConditionEvaluator}, which is the only unbounded dimension in an
     * otherwise linear evaluation. Sixteen is well beyond any legible hand-authored rule and far below
     * any stack risk; the value is stated here rather than inlined so that raising it is a visible
     * decision with this comment attached.
     */
    public static final int MAX_DEPTH = 16;

    /** Maximum node count, bounding total evaluation work per condition. */
    public static final int MAX_NODES = 256;

    /** A validation finding. Typed, so a caller cannot render a warning as a rejection or vice versa. */
    public record Finding(Kind kind, String detail) {

        public enum Kind {
            /** Depth exceeds {@link #MAX_DEPTH}. Rejects the definition. */
            TOO_DEEP,
            /** Node count exceeds {@link #MAX_NODES}. Rejects the definition. */
            TOO_LARGE,
            /** The condition reads a fact the caller does not supply. Rejects the definition. */
            UNKNOWN_FACT_KEY,
            /**
             * The condition constrains nothing, so it matches everything.
             *
             * <p>Not an error. DOC-28 section 11.2's default policy set needs a catch-all, and a
             * checklist may apply unconditionally. Reported so that an author who did not intend a
             * catch-all can see they wrote one.
             */
            MATCHES_EVERYTHING
        }

        /** True where this finding must prevent activation. */
        public boolean rejects() {
            return kind != Kind.MATCHES_EVERYTHING;
        }
    }

    private ConditionValidator() {
        throw new AssertionError("not instantiable");
    }

    /**
     * Validates a condition against the fact keys its caller will supply.
     *
     * <p>The fact-key check is the part that catches the most common real defect: a rule referencing a
     * fact that is never populated evaluates UNDEFINED forever, so it never fires and never errors. That
     * is invisible in testing — the rule looks correct — and it is why {@link RuleMatcher} reports
     * UNDEFINED separately as well.
     *
     * @param availableFactKeys the keys the caller's fact set will contain
     */
    public static List<Finding> validate(Condition condition, Set<String> availableFactKeys) {
        Objects.requireNonNull(condition, "condition is required");
        Objects.requireNonNull(availableFactKeys, "availableFactKeys is required");

        List<Finding> findings = new ArrayList<>();

        int depth = condition.depth();
        if (depth > MAX_DEPTH) {
            findings.add(new Finding(Finding.Kind.TOO_DEEP,
                    "depth " + depth + " exceeds the bound of " + MAX_DEPTH
                            + "; a guard is evaluated on every transition attempt including denied ones "
                            + "(PRD-WRK-033)"));
        }

        int nodes = condition.nodeCount();
        if (nodes > MAX_NODES) {
            findings.add(new Finding(Finding.Kind.TOO_LARGE,
                    "node count " + nodes + " exceeds the bound of " + MAX_NODES));
        }

        for (String key : ConditionEvaluator.referencedFactKeys(condition)) {
            if (!availableFactKeys.contains(key)) {
                findings.add(new Finding(Finding.Kind.UNKNOWN_FACT_KEY,
                        "fact '" + key + "' is not supplied by this caller, so the condition would "
                                + "evaluate UNDEFINED for every object — the rule would never fire and "
                                + "never error, which is invisible in testing"));
            }
        }

        if (condition.specificity() == 0) {
            findings.add(new Finding(Finding.Kind.MATCHES_EVERYTHING,
                    "the condition constrains nothing and matches every object"));
        }

        return List.copyOf(findings);
    }

    /** True where no finding prevents activation. */
    public static boolean isActivatable(List<Finding> findings) {
        return findings.stream().noneMatch(Finding::rejects);
    }
}
