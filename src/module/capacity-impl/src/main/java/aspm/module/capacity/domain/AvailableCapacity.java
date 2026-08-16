package aspm.module.capacity.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Available capacity for one member over one period. {@code INV-CAP-01}.
 *
 * <p>"Available capacity is net of non-working days, recorded leave, and the overhead allowance. <b>Gross
 * headcount is never used.</b>"
 *
 * <p>The three deductions are constructor parameters rather than optional adjustments, because each is a
 * different way the same overstatement happens and omitting any one produces a number that looks plausible:
 *
 * <ul>
 *   <li><b>Non-working days.</b> A twenty-two working-day month costed as thirty is a thirty-six percent
 *       overstatement before anybody takes leave.
 *   <li><b>Recorded leave.</b> The deduction a manual model always makes and an automated one frequently
 *       forgets, because leave lives in a system the platform does not own (⚠ OQ-019).
 *   <li><b>Overhead allowance.</b> Meetings, on-call, interviews, the security questions that arrive by chat.
 *       A model with no overhead line reports a team at sixty percent utilization and is used to argue they
 *       have spare capacity.
 * </ul>
 *
 * <p>There is no factory taking a headcount, and a test asserts it.
 */
public record AvailableCapacity(BigDecimal calendarDays, BigDecimal nonWorkingDays,
        BigDecimal recordedLeaveDays, BigDecimal overheadAllowanceDays, BigDecimal capacityRatio) {

    /** Retained scale. Capacity in days to two places; a finer figure implies precision the inputs lack. */
    private static final int SCALE = 2;

    public AvailableCapacity {
        Objects.requireNonNull(calendarDays, "calendar days are required");
        Objects.requireNonNull(nonWorkingDays, "non-working days are required (INV-CAP-01)");
        Objects.requireNonNull(recordedLeaveDays, "recorded leave is required (INV-CAP-01)");
        Objects.requireNonNull(overheadAllowanceDays, "the overhead allowance is required (INV-CAP-01)");
        Objects.requireNonNull(capacityRatio, "a capacity ratio is required");

        for (BigDecimal component : List.of(calendarDays, nonWorkingDays, recordedLeaveDays,
                overheadAllowanceDays)) {
            if (component.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("a negative capacity component: " + component);
            }
        }
        if (capacityRatio.compareTo(BigDecimal.ZERO) < 0 || capacityRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "the capacity ratio is a proportion of full time in [0,1]; got " + capacityRatio);
        }
    }

    /**
     * Net available days.
     *
     * <p>Floors at zero rather than going negative. A member whose leave exceeds their working days is on
     * extended absence, and a negative capacity would <i>subtract</i> from a team total — making the team look
     * smaller than it is while somebody is away, which is arithmetically tidy and operationally absurd.
     */
    public BigDecimal netAvailableDays() {
        BigDecimal working = calendarDays.subtract(nonWorkingDays);
        BigDecimal afterLeave = working.subtract(recordedLeaveDays);
        BigDecimal afterOverhead = afterLeave.subtract(overheadAllowanceDays);
        BigDecimal scaled = afterOverhead.multiply(capacityRatio).setScale(SCALE, RoundingMode.HALF_UP);
        return scaled.max(BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP));
    }

    /**
     * The deductions, for presentation beside the figure.
     *
     * <p>A net capacity number with no visible deductions is one somebody disputes by asserting a bigger one.
     * The breakdown is what turns that into a conversation about the overhead allowance, which is the
     * conversation worth having.
     */
    public String breakdown() {
        return calendarDays + " calendar day(s), less " + nonWorkingDays + " non-working, less "
                + recordedLeaveDays + " recorded leave, less " + overheadAllowanceDays + " overhead, at "
                + capacityRatio + " capacity = " + netAvailableDays() + " available day(s)";
    }
}
