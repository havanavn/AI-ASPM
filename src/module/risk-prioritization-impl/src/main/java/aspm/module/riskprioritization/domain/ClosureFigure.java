package aspm.module.riskprioritization.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A closure or improvement figure that cannot be presented without its breakdown. {@code PRD-RSK-042}.
 *
 * <p>"An undifferentiated closure rate is the metric most easily optimized by closing rather than fixing, and it
 * is the figure most likely to appear in an executive summary."
 *
 * <h2>Why a type rather than a reporting convention</h2>
 *
 * <p>The requirement says the distinction MUST be made "wherever a closure or improvement figure is presented".
 * A convention is satisfied by remembering; a type that has no accessor for an undifferentiated total is satisfied
 * by compiling. {@link #verifiedCount()} and {@link #otherClosureCounts()} are available; a plain
 * {@code totalClosed()} is deliberately absent, and {@link #totalWithBreakdown()} returns the total together with
 * the breakdown so a caller cannot obtain one without the other.
 *
 * <h2>Closure reasons are tenant data; verification is not</h2>
 *
 * <p>A tenant configures its closure reasons ({@code PRD-VUL-011}), so this type takes them as strings rather than
 * an enumeration — a fixed enumeration here would violate ADR-027. What is <b>not</b> tenant-configurable is
 * whether a reason counts as verified remediation: that mapping is what the figure means, and a tenant able to
 * mark "closed as not applicable" as verified remediation would have the gaming path back in one configuration
 * change. The verified count is therefore supplied separately and cannot be one of the other reasons.
 */
public final class ClosureFigure {

    private final int verifiedCount;
    private final Map<String, Integer> otherClosureCounts;
    private final int total;

    private ClosureFigure(int verifiedCount, Map<String, Integer> otherClosureCounts) {
        if (verifiedCount < 0) {
            throw new IllegalArgumentException("verifiedCount cannot be negative");
        }
        Objects.requireNonNull(otherClosureCounts, "the other-reason breakdown is required, possibly empty");
        Map<String, Integer> copy = new LinkedHashMap<>();
        int sum = verifiedCount;
        for (Map.Entry<String, Integer> entry : otherClosureCounts.entrySet()) {
            String reason = Objects.requireNonNull(entry.getKey(), "a closure reason code is required");
            if (reason.isBlank()) {
                throw new IllegalArgumentException(
                        "a blank closure reason code would appear in the breakdown as an unlabelled bucket, "
                                + "which is an undifferentiated total by another name (PRD-RSK-042)");
            }
            int count = Objects.requireNonNull(entry.getValue(), "a count is required for " + reason);
            if (count < 0) {
                throw new IllegalArgumentException("a negative count for " + reason);
            }
            copy.put(reason, count);
            sum += count;
        }
        this.verifiedCount = verifiedCount;
        this.otherClosureCounts = Map.copyOf(copy);
        this.total = sum;
    }

    /**
     * Builds a closure figure.
     *
     * @param verifiedCount closures where remediation was verified — the only count that supports a claim of
     *     progress
     * @param otherClosureCounts every other closure reason, by the tenant's own reason code
     */
    public static ClosureFigure of(int verifiedCount, Map<String, Integer> otherClosureCounts) {
        return new ClosureFigure(verifiedCount, otherClosureCounts);
    }

    public int verifiedCount() {
        return verifiedCount;
    }

    public Map<String, Integer> otherClosureCounts() {
        return otherClosureCounts;
    }

    /** Every closure that was not verified remediation. */
    public int otherCount() {
        return total - verifiedCount;
    }

    /**
     * The total, carried only alongside the breakdown that qualifies it. There is intentionally no accessor
     * anywhere on this class that returns the total alone.
     */
    public record TotalWithBreakdown(int total, int verified, Map<String, Integer> other, String presentation) {
    }

    public TotalWithBreakdown totalWithBreakdown() {
        StringBuilder presentation = new StringBuilder();
        presentation.append(total).append(" closed, of which ").append(verifiedCount)
                .append(" verified remediated");
        if (!otherClosureCounts.isEmpty()) {
            presentation.append("; other reasons: ");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : otherClosureCounts.entrySet()) {
                if (!first) {
                    presentation.append(", ");
                }
                presentation.append(entry.getKey()).append(' ').append(entry.getValue());
                first = false;
            }
        }
        presentation.append(" (PRD-RSK-042).");
        return new TotalWithBreakdown(total, verifiedCount, otherClosureCounts, presentation.toString());
    }

    /**
     * The proportion of closures that were verified remediation.
     *
     * <p>Returns 0 for an empty period rather than 1: "all of nothing was verified" is the same arithmetic error
     * that {@code PRD-RSK-027} guards at coverage level, and it would report a period with no work done as a
     * period of perfect verification.
     */
    public double verifiedProportion() {
        return total == 0 ? 0.0 : (double) verifiedCount / total;
    }
}
