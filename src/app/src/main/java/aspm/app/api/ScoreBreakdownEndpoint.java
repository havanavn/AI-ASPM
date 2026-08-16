package aspm.app.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The score factor breakdown endpoint. {@code SEC-AUZ-027}, assertion A11.
 *
 * <p>"A score factor breakdown MUST include only contributions from objects within the requesting principal's
 * scope. <b>An aggregate score is a permitted disclosure; its breakdown can reveal the existence and severity of
 * out-of-scope findings.</b>"
 *
 * <p>That sentence contains the whole design problem. The number is fine to show — a node's posture is what an
 * executive dashboard exists for. The explanation of the number is not, because "driven by three critical
 * findings on the payments service" tells a reader who cannot see the payments service that it has three
 * criticals.
 *
 * <h2>The aggregate is NOT recomputed from the visible subset</h2>
 *
 * <p>The tempting implementation filters the contributions and re-sums them, so the breakdown adds up. It is
 * wrong twice over:
 *
 * <ul>
 *   <li>It changes the number depending on who is looking. Two readers comparing notes see different postures
 *       for the same node, and neither is the platform's answer.
 *   <li><b>It is a subtraction oracle.</b> A reader who can see the true aggregate elsewhere — on a dashboard,
 *       in an export, from last week's report — subtracts the visible sum and learns exactly how much score sits
 *       out of scope. {@code SEC-AUZ-026}'s "no derivation by subtraction" is the same hazard at aggregate
 *       level.
 * </ul>
 *
 * <p>So the aggregate is reported as computed, the visible contributions are listed, and the difference is
 * <b>not</b> presented as a residual line item. {@link Breakdown#completeForReader()} says whether the listed
 * contributions account for the whole score, which is the honest statement — it discloses that something is
 * hidden without disclosing how much.
 */
public final class ScoreBreakdownEndpoint {

    /**
     * One contribution, and the object it came from.
     *
     * @param sourceObjectId the object whose scope decides visibility. A contribution with no source cannot be
     *     scope-checked, so there is no constructor without one
     */
    public record Contribution(UUID sourceObjectId, String factor, BigDecimal value, String label) {

        public Contribution {
            Objects.requireNonNull(sourceObjectId,
                    "a source object is required (SEC-AUZ-027). A contribution whose origin is unknown cannot "
                            + "be scope-checked, and an unscope-checkable contribution is one that gets shown.");
            Objects.requireNonNull(factor, "a factor is required");
            Objects.requireNonNull(value, "a value is required");
            Objects.requireNonNull(label, "a label is required");
        }
    }

    /**
     * The reader's view.
     *
     * @param aggregateValue the platform's number, <b>as computed</b> over every contribution
     * @param visibleContributions only those from in-scope objects
     */
    public record Breakdown(BigDecimal aggregateValue, List<Contribution> visibleContributions,
            boolean completeForReader) {

        public Breakdown {
            Objects.requireNonNull(aggregateValue, "the aggregate is required");
            visibleContributions = List.copyOf(
                    Objects.requireNonNull(visibleContributions, "visible contributions are required"));
        }

        /**
         * The sentence presented with an incomplete breakdown.
         *
         * <p>States that contributions are omitted and says nothing about how many or how large. A count would
         * be an inventory of out-of-scope objects; a magnitude would be the subtraction oracle handed over
         * directly.
         */
        public String qualifier() {
            return completeForReader
                    ? "this breakdown accounts for the whole score"
                    : "this breakdown is partial: contributions from objects outside your scope are omitted "
                            + "(SEC-AUZ-027). The aggregate above is the platform's figure over all "
                            + "contributions and has not been recomputed for this view — recomputing it would "
                            + "make the number depend on the reader, and the difference from the visible sum "
                            + "would disclose the omitted total by subtraction.";
        }
    }

    private ScoreBreakdownEndpoint() {
    }

    /**
     * Builds a breakdown for a reader.
     *
     * @param inScope evaluated per contribution's source object
     */
    public static Breakdown forReader(BigDecimal aggregateValue, List<Contribution> allContributions,
            Predicate<UUID> inScope) {
        Objects.requireNonNull(aggregateValue, "the aggregate is required");
        Objects.requireNonNull(allContributions, "the contributions are required");
        Objects.requireNonNull(inScope, "a per-object scope predicate is required (SEC-AUZ-027)");

        List<Contribution> visible = new ArrayList<>();
        boolean anyHidden = false;
        for (Contribution contribution : allContributions) {
            if (inScope.test(contribution.sourceObjectId())) {
                visible.add(contribution);
            } else {
                anyHidden = true;
            }
        }
        // The aggregate passes through unchanged. See the class comment for why re-summing would be wrong
        // twice over.
        return new Breakdown(aggregateValue, visible, !anyHidden);
    }
}
