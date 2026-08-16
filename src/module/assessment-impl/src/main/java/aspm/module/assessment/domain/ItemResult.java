package aspm.module.assessment.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One checklist item's result. {@code INV-ASM-19}: {@code PASS}, {@code FAIL}, {@code NOT_APPLICABLE} with
 * reason, or {@code NOT_ASSESSED}. <b>There is no null.</b>
 *
 * <p>The absence of null is the whole design. A null result is indistinguishable from a passing one in every
 * aggregate that counts, and "no findings" is indistinguishable from "we did not look" (PP-1). {@code
 * NOT_ASSESSED} is an explicit statement that carries into the coverage figure; a null would carry into nothing.
 *
 * <p><b>{@code INV-ASM-13}: an unreasoned exclusion is {@code NOT_ASSESSED}, not {@code NOT_APPLICABLE}.</b> The
 * reason requirement "is what prevents coverage being inflated by marking inconvenient items as inapplicable —
 * the path of least resistance under deadline" (DOC-03 §9.4 commentary on §9.3). This type enforces it by
 * refusing to construct a reasonless {@code NOT_APPLICABLE}, and {@link #notApplicableOrNotAssessed} gives a
 * caller the honest downgrade rather than a validation error they will work around.
 */
public record ItemResult(UUID itemId, Verdict verdict, Optional<String> reason, Optional<UUID> assessedBy,
        Optional<java.time.Instant> assessedAt) {

    public enum Verdict {
        PASS,
        FAIL,
        /** Excluded with a recorded reason. Counts as covered. */
        NOT_APPLICABLE,
        /**
         * Not examined. Counts as <b>not</b> covered.
         *
         * <p>The default for an unstarted item, and the honest destination for an exclusion nobody justified.
         */
        NOT_ASSESSED;

        /**
         * Whether this verdict counts toward coverage.
         *
         * <p>{@code NOT_APPLICABLE} counts: somebody looked and concluded the item does not apply, which is a
         * result. {@code NOT_ASSESSED} does not: nobody looked.
         */
        public boolean covered() {
            return this != NOT_ASSESSED;
        }
    }

    public ItemResult {
        Objects.requireNonNull(itemId, "itemId is required");
        Objects.requireNonNull(verdict, "a verdict is required; INV-ASM-19 has no null");
        Objects.requireNonNull(reason, "reason is required, empty where the verdict does not demand one");
        Objects.requireNonNull(assessedBy, "assessedBy is required, empty where NOT_ASSESSED");
        Objects.requireNonNull(assessedAt, "assessedAt is required, empty where NOT_ASSESSED");

        if (verdict == Verdict.NOT_APPLICABLE && reason.filter(r -> !r.isBlank()).isEmpty()) {
            throw new IllegalArgumentException(
                    "NOT_APPLICABLE requires a reason (INV-ASM-13). Without it, marking inconvenient items "
                            + "inapplicable is the path of least resistance under deadline, and coverage "
                            + "inflates while the assessment covers less. An unreasoned exclusion is "
                            + "NOT_ASSESSED — see notApplicableOrNotAssessed.");
        }
        if (verdict == Verdict.NOT_ASSESSED && (assessedBy.isPresent() || assessedAt.isPresent())) {
            throw new IllegalArgumentException(
                    "a NOT_ASSESSED item records nobody as having assessed it; an attributed non-assessment "
                            + "reads as work that was done");
        }
        if (verdict != Verdict.NOT_ASSESSED && (assessedBy.isEmpty() || assessedAt.isEmpty())) {
            throw new IllegalArgumentException(
                    "verdict " + verdict + " on item " + itemId + " records no assessor or time. An "
                            + "unattributed PASS is a coverage claim nobody made.");
        }
    }

    /** The starting state of every instantiated item. */
    public static ItemResult notAssessed(UUID itemId) {
        return new ItemResult(itemId, Verdict.NOT_ASSESSED, Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    public static ItemResult assessed(UUID itemId, Verdict verdict, String reason, UUID by,
            java.time.Instant at) {
        return new ItemResult(itemId, verdict, Optional.ofNullable(reason), Optional.of(by), Optional.of(at));
    }

    /**
     * The honest downgrade: {@code NOT_APPLICABLE} with a reason, {@code NOT_ASSESSED} without.
     *
     * <p>Offered so a caller meets {@code INV-ASM-13} by taking the accurate result rather than by inventing a
     * reason to get past a validation error. A rule people route around produces worse data than no rule.
     */
    public static ItemResult notApplicableOrNotAssessed(UUID itemId, String reason, UUID by,
            java.time.Instant at) {
        return reason == null || reason.isBlank()
                ? notAssessed(itemId)
                : assessed(itemId, Verdict.NOT_APPLICABLE, reason, by, at);
    }

    public boolean covered() {
        return verdict.covered();
    }
}
