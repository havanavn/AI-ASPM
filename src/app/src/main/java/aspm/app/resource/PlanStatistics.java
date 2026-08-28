package aspm.app.resource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The coverage tables on the planning screen: totals, and the same figures per organization.
 *
 * <h2>A pure function, deliberately</h2>
 *
 * Everything here folds a list of {@link AssessmentPlanQuery.CoverageRow} into groups. No database, no
 * principal, no clock. That is not tidiness — it is the only part of this feature that can be tested
 * exhaustively without a live engine, and the arithmetic is where a reporting bug hides longest: a
 * percentage denominator off by one is invisible on screen and wrong in a board pack.
 *
 * <h2>Three rules the arithmetic follows</h2>
 *
 * <p><b>A rate with no denominator is null, never zero.</b> An organization with nothing planned has
 * no completion rate; reporting 0% would say it planned work and did none. Product principle 1 applied
 * to a division.
 *
 * <p><b>The criticality columns are the tenant's own tiers, in the tenant's own order.</b> There is no
 * CRITICAL/HIGH/MEDIUM here and there must not be (ADR-027): the tiers are rows in
 * {@code criticality_tier} and their order is its {@code ordinal}. Applications carrying no tier are
 * their own column — an application nobody classified is not a low-criticality application, and
 * merging it into one would hide the gap that needs fixing.
 *
 * <p><b>Every total is the sum of the groups shown.</b> The total row is computed from the same rows
 * the per-organization rows are computed from, in one pass, so a reader adding up the column reaches
 * the total. A separately queried total is how a summary comes to disagree with the list beneath it.
 */
public final class PlanStatistics {

    /** A criticality tier as a column heading: the tenant's code, label and position. */
    public record Tier(String code, String label, int ordinal) {
    }

    /** How a plan is progressing, for one group. */
    public record Plan(long planned, long converted, long done, long missed, long cancelled) {

        /**
         * Done as a fraction of planned, or null where nothing was planned.
         *
         * <p>Returned as a fraction rather than a rounded percentage so the interface owns the
         * rounding. Two roundings of one number is two numbers.
         */
        public Double completion() {
            return planned == 0 ? null : Double.valueOf((double) done / planned);
        }
    }

    /**
     * How many applications were reviewed how many times, in the year.
     *
     * <p>The buckets are 0, 1, 2 and more-than-2 because that is the shape of the question: nobody
     * plans the difference between five reviews and six, and everybody cares about the difference
     * between none and one.
     *
     * <p>{@code attested} counts the REVIEWS, not the applications, that rest on an assertion rather
     * than on evidence the platform holds. It is carried beside the buckets because a frequency table
     * is a coverage figure derived from review counts, and {@code PRD-ASM-020} requires those to say
     * how much of them was asserted.
     */
    public record Frequency(long none, long once, long twice, long more, long attested) {

        public long applications() {
            return none + once + twice + more;
        }

        /** The share of applications reviewed at least once, or null where the group is empty. */
        public Double covered() {
            long total = applications();
            return total == 0 ? null : Double.valueOf((double) (total - none) / total);
        }
    }

    /**
     * One row of every table: an organization, or the total.
     *
     * @param orgId null on the total row, and also null for an application whose branch has no root —
     *     which cannot happen through the organization tree and is therefore reported rather than
     *     silently folded into another group
     */
    public record Group(String orgId, String orgName, boolean total, long applications,
            Plan plan, Map<String, Long> byTier, long tierUnset, Frequency frequency) {
    }

    /** The whole statistics block for one year. */
    public record Report(int year, List<Tier> tiers, List<Group> groups) {
    }

    private PlanStatistics() {
    }

    /**
     * Folds per-application rows into the total and the per-organization groups.
     *
     * @param rows one row per application, already scope-filtered by the query that produced them
     * @param year the calendar year the rows were computed for, carried through so the tables can say
     *     which year they describe — a coverage figure with no period on it is unreadable
     */
    public static Report of(List<AssessmentPlanQuery.CoverageRow> rows, List<Tier> catalogue,
            int year) {
        Objects.requireNonNull(rows, "rows are required");
        Objects.requireNonNull(catalogue, "the tier catalogue is required");

        // The columns come from the CATALOGUE, not from the rows.
        //
        // Deriving them from the rows was the first attempt and it was wrong: a tier nobody had
        // classified anything into vanished from the table, so a reader could not tell "no
        // application is Tier 3" — a finding — from "there is no Tier 3" — a configuration fact. It
        // also made the table change shape as the data moved.
        Map<String, Tier> tiers = new LinkedHashMap<>();
        for (Tier tier : catalogue) {
            tiers.put(tier.code(), tier);
        }
        // A tier that has been deprecated since an application was classified into it still needs a
        // column, or that application's row would not add up to its own total.
        for (var row : rows) {
            if (row.tierCode() != null && !tiers.containsKey(row.tierCode())) {
                tiers.put(row.tierCode(), new Tier(row.tierCode(),
                        (row.tierLabel() == null ? row.tierCode() : row.tierLabel()) + " (retired)",
                        row.tierOrdinal() == null ? Integer.MAX_VALUE : row.tierOrdinal().intValue()));
            }
        }
        List<Tier> ordered = new ArrayList<>(tiers.values());
        ordered.sort(Comparator.comparingInt(Tier::ordinal).thenComparing(Tier::code));

        // Grouped in encounter order, which the query already sorted by organization name. Preserving
        // it means the tables read in the same order as the list above them.
        Map<String, List<AssessmentPlanQuery.CoverageRow>> byOrg = new LinkedHashMap<>();
        for (var row : rows) {
            byOrg.computeIfAbsent(row.orgId() == null ? "" : row.orgId(), key -> new ArrayList<>())
                    .add(row);
        }

        List<Group> groups = new ArrayList<>();
        groups.add(fold(null, "All organizations", true, rows, ordered));
        for (var entry : byOrg.entrySet()) {
            String id = entry.getKey().isEmpty() ? null : entry.getKey();
            String name = entry.getValue().get(0).orgName();
            groups.add(fold(id, name == null ? "No organization" : name, false, entry.getValue(),
                    ordered));
        }
        return new Report(year, ordered, groups);
    }

    private static Group fold(String orgId, String orgName, boolean total,
            List<AssessmentPlanQuery.CoverageRow> rows, List<Tier> tiers) {
        long planned = 0;
        long converted = 0;
        long done = 0;
        long missed = 0;
        long cancelled = 0;
        long none = 0;
        long once = 0;
        long twice = 0;
        long more = 0;
        long attested = 0;
        long unset = 0;

        // Every tier gets an entry even where the count is zero, so the row has the same shape as its
        // neighbours and a reader scanning down a column is not reading a different tier per row.
        Map<String, Long> byTier = new LinkedHashMap<>();
        for (Tier tier : tiers) {
            byTier.put(tier.code(), Long.valueOf(0));
        }

        for (var row : rows) {
            planned += row.planned();
            converted += row.converted();
            done += row.done();
            missed += row.missed();
            cancelled += row.cancelled();
            attested += row.reviewsAttested();

            if (row.tierCode() == null) {
                unset++;
            } else {
                byTier.merge(row.tierCode(), Long.valueOf(1), Long::sum);
            }

            long reviews = row.reviews();
            if (reviews == 0) {
                none++;
            } else if (reviews == 1) {
                once++;
            } else if (reviews == 2) {
                twice++;
            } else {
                more++;
            }
        }
        return new Group(orgId, orgName, total, rows.size(),
                new Plan(planned, converted, done, missed, cancelled),
                byTier, unset, new Frequency(none, once, twice, more, attested));
    }
}
