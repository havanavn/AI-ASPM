package aspm.module.assessment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Objects;

/**
 * Derived coverage over an assessment's checklist instances. {@code INV-ASM-11}.
 *
 * <p>"Derived from checklist instances and cannot be set directly" — so this type has no public constructor
 * taking counts and exactly one factory, {@link #from}, which computes them. A settable coverage figure is a
 * figure somebody will set to the number they need.
 *
 * <p>DOC-03 §9.3 on why this exists at all: "An assessment reporting no findings is meaningless without knowing
 * what was examined, and 'no findings' is indistinguishable from 'we did not look' unless coverage is recorded."
 * That is PP-1 applied to manual work.
 */
public record CoverageSummary(int itemsTotal, int itemsAssessed, int itemsNotApplicable,
        int itemsNotAssessed, BigDecimal coverageRatio) {

    private CoverageSummary(int itemsTotal, int itemsAssessed, int itemsNotApplicable, int itemsNotAssessed) {
        this(itemsTotal, itemsAssessed, itemsNotApplicable, itemsNotAssessed,
                itemsTotal == 0
                        // Zero, not one. "100% of nothing" is the same arithmetic error PRD-RSK-027 guards at
                        // posture level: an assessment with no checklist would report perfect coverage.
                        ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                        : BigDecimal.valueOf(itemsAssessed + itemsNotApplicable)
                                .divide(BigDecimal.valueOf(itemsTotal), 4, RoundingMode.HALF_UP));
    }

    /** Computes coverage from the results themselves. The only way to obtain one. */
    public static CoverageSummary from(Collection<ItemResult> results) {
        Objects.requireNonNull(results, "results are required");
        int assessed = 0;
        int notApplicable = 0;
        int notAssessed = 0;
        for (ItemResult result : results) {
            switch (result.verdict()) {
                case PASS, FAIL -> assessed++;
                case NOT_APPLICABLE -> notApplicable++;
                case NOT_ASSESSED -> notAssessed++;
            }
        }
        return new CoverageSummary(results.size(), assessed, notApplicable, notAssessed);
    }

    /** {@code INV-ASM-12}: completion with unassessed items requires an explicit acknowledgement. */
    public boolean complete() {
        return itemsNotAssessed == 0;
    }

    /**
     * A presentation that cannot state a ratio without stating what it is a ratio of.
     *
     * <p>"340 of 351" is a claim a reader can evaluate; "97%" is one they cannot, and the second is the one that
     * reaches a slide.
     */
    public String presentation() {
        return (itemsAssessed + itemsNotApplicable) + " of " + itemsTotal + " item(s) covered — "
                + itemsAssessed + " assessed, " + itemsNotApplicable + " not applicable with reason, "
                + itemsNotAssessed + " not assessed";
    }
}
