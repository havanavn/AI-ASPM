package aspm.app.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.app.resource.AssessmentPlanQuery.CoverageRow;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic behind the coverage tables.
 *
 * <p>These run without a database, which is the point. Everything else in this feature is checked by a
 * source scan or by executing SQL by hand, and a reporting defect — a denominator off by one, a total
 * that is not the sum of its rows — is invisible on screen and wrong in a board pack. This is the one
 * part that can be pinned down exhaustively, so it is.
 */
class PlanStatisticsTest {

    /**
     * The default catalogue: three tiers in the tenant's order. Deliberately holds a tier that most
     * cases never classify anything into, so every case also proves an unused tier keeps its column.
     */
    private static final List<PlanStatistics.Tier> CATALOGUE = catalogue("T1", "T2", "T3");

    /** The tenant's tier catalogue, as the query reads it from `criticality_tier`. */
    private static java.util.List<PlanStatistics.Tier> catalogue(String... codes) {
        var tiers = new java.util.ArrayList<PlanStatistics.Tier>();
        for (int i = 0; i < codes.length; i++) {
            tiers.add(new PlanStatistics.Tier(codes[i], codes[i] + " label", i + 1));
        }
        return tiers;
    }

    private static CoverageRow row(String org, String orgName, String tier, Integer ordinal,
            long observed, long attested, long planned, long converted, long done, long missed) {
        return new CoverageRow("asset-" + org + "-" + tier + "-" + observed + "-" + planned,
                org, orgName, tier, tier == null ? null : tier + " label", ordinal,
                observed, attested, planned, 0, converted, done, missed);
    }

    @Test
    @DisplayName("the total row is the sum of the organization rows")
    void totalIsTheSumOfWhatIsShown() {
        var report = PlanStatistics.of(List.of(
                row("o1", "Alpha", "T1", 1, 1, 0, 4, 2, 2, 1),
                row("o1", "Alpha", "T2", 2, 0, 0, 2, 0, 0, 2),
                row("o2", "Beta", "T1", 1, 3, 1, 6, 6, 5, 0)), CATALOGUE, 2026);

        var total = report.groups().get(0);
        assertTrue(total.total(), "the first group is the total");
        assertEquals(3, total.applications());
        assertEquals(12, total.plan().planned());
        assertEquals(7, total.plan().done());
        assertEquals(3, total.plan().missed());

        long summedPlanned = report.groups().stream().filter(g -> !g.total())
                .mapToLong(g -> g.plan().planned()).sum();
        long summedDone = report.groups().stream().filter(g -> !g.total())
                .mapToLong(g -> g.plan().done()).sum();
        assertEquals(total.plan().planned(), summedPlanned,
                "a reader adding up the column must reach the total. A separately computed total is "
                        + "how a summary comes to disagree with the rows beneath it.");
        assertEquals(total.plan().done(), summedDone);
    }

    @Test
    @DisplayName("PP-1: a rate with no denominator is null, never zero")
    void noPlanIsNotZeroPercent() {
        var report = PlanStatistics.of(List.of(
                row("o1", "Alpha", "T1", 1, 0, 0, 0, 0, 0, 0)), CATALOGUE, 2026);
        var group = report.groups().get(1);
        assertEquals(0, group.plan().planned());
        assertNull(group.plan().completion(),
                "an organization with nothing planned has NO completion rate. Reporting 0% would say "
                        + "it planned work and did none of it, which is a different and worse claim.");

        var withPlan = PlanStatistics.of(List.of(
                row("o1", "Alpha", "T1", 1, 0, 0, 4, 1, 1, 0)), CATALOGUE, 2026);
        assertEquals(0.25d, withPlan.groups().get(1).plan().completion().doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("an empty population has no coverage rate either")
    void emptyGroupHasNoRate() {
        var report = PlanStatistics.of(List.of(), List.of(), 2026);
        var total = report.groups().get(0);
        assertEquals(0, total.applications());
        assertNull(total.frequency().covered(),
                "nothing to cover is not zero coverage; the table must say so rather than print 0%");
        assertTrue(report.tiers().isEmpty(),
                "an estate that has configured no tier gets no tier columns, which is the honest "
                        + "rendering of it");
    }

    @Test
    @DisplayName("ADR-027: the tier columns are the tenant's own, in the tenant's own order")
    void tiersAreTenantDataInTenantOrder() {
        var report = PlanStatistics.of(List.of(
                row("o1", "Alpha", "SUPPORTING", 3, 0, 0, 0, 0, 0, 0),
                row("o1", "Alpha", "REVENUE_CRITICAL", 1, 0, 0, 0, 0, 0, 0),
                row("o1", "Alpha", "IMPORTANT", 2, 0, 0, 0, 0, 0, 0)),
                catalogue("REVENUE_CRITICAL", "IMPORTANT", "SUPPORTING"), 2026);
        assertEquals(List.of("REVENUE_CRITICAL", "IMPORTANT", "SUPPORTING"),
                report.tiers().stream().map(PlanStatistics.Tier::code).toList(),
                "ordered by the tenant's own ordinal, not alphabetically and not by any ranking this "
                        + "code invented. There is no CRITICAL/HIGH/MEDIUM in the product.");
        // Every group carries every tier, so a column means the same tier on every row.
        for (var group : report.groups()) {
            assertEquals(report.tiers().size(), group.byTier().size(),
                    "a row missing a tier key would shift the columns under it");
        }
    }

    @Test
    @DisplayName("a tier with no applications keeps its column, showing zero")
    void unusedTierStillHasAColumn() {
        // MEASURED, not hypothesised: the first build derived the columns from the rows, so TIER3
        // vanished from a real deployment's table because no application carried it. A reader then
        // cannot tell "no application is Tier 3" — a finding about the estate — from "there is no
        // Tier 3" — a fact about the configuration.
        var report = PlanStatistics.of(List.of(
                row("o1", "Alpha", "T1", 1, 0, 0, 0, 0, 0, 0)), CATALOGUE, 2026);
        assertEquals(List.of("T1", "T2", "T3"),
                report.tiers().stream().map(PlanStatistics.Tier::code).toList(),
                "every configured tier is a column, whether or not anything is in it");
        var group = report.groups().get(1);
        assertEquals(Long.valueOf(0), group.byTier().get("T3"),
                "and the empty one reads zero rather than being absent");
    }

    @Test
    @DisplayName("a tier retired after something was classified into it keeps its column")
    void retiredTierStillHasAColumn() {
        // Otherwise the row would not add up to its own total: the application is in the population
        // and its tier is in no column.
        var report = PlanStatistics.of(List.of(
                row("o1", "Alpha", "T1", 1, 0, 0, 0, 0, 0, 0),
                row("o1", "Alpha", "GONE", 9, 0, 0, 0, 0, 0, 0)), CATALOGUE, 2026);
        assertTrue(report.tiers().stream().anyMatch(t -> "GONE".equals(t.code())),
                "a deprecated tier that still has applications in it needs a column");
        var group = report.groups().get(1);
        long classified = group.byTier().values().stream().mapToLong(Long::longValue).sum();
        assertEquals(group.applications() - group.tierUnset(), classified,
                "the tier columns plus the unset column must equal the population, or the row does "
                        + "not add up to its own total");
    }

    @Test
    @DisplayName("an unclassified application is its own group, not folded into the lowest tier")
    void unsetTierIsSeparate() {
        var report = PlanStatistics.of(List.of(
                row("o1", "Alpha", "T1", 1, 0, 0, 0, 0, 0, 0),
                row("o1", "Alpha", null, null, 0, 0, 0, 0, 0, 0),
                row("o1", "Alpha", null, null, 0, 0, 0, 0, 0, 0)), CATALOGUE, 2026);
        var group = report.groups().get(1);
        assertEquals(2, group.tierUnset(),
                "an application nobody classified is not a low-criticality application. Folding the "
                        + "two together hides the gap that needs fixing.");
        assertEquals(Long.valueOf(1), group.byTier().get("T1"));
        assertEquals(3, group.applications(),
                "the unclassified ones are still applications and still count in the population");
    }

    @Test
    @DisplayName("the frequency buckets are 0, 1, 2 and more, and they partition the population")
    void frequencyBucketsPartition() {
        var report = PlanStatistics.of(List.of(
                row("o1", "A", "T1", 1, 0, 0, 0, 0, 0, 0),
                row("o1", "A", "T1", 1, 1, 0, 0, 0, 0, 0),
                row("o1", "A", "T1", 1, 0, 1, 0, 0, 0, 0),
                row("o1", "A", "T1", 1, 1, 1, 0, 0, 0, 0),
                row("o1", "A", "T1", 1, 2, 1, 0, 0, 0, 0),
                row("o1", "A", "T1", 1, 5, 0, 0, 0, 0, 0)), CATALOGUE, 2026);
        var f = report.groups().get(0).frequency();
        assertEquals(1, f.none());
        assertEquals(2, f.once(), "observed-once and attested-once both count as one review");
        assertEquals(1, f.twice());
        assertEquals(2, f.more());
        assertEquals(6, f.applications(),
                "the buckets must partition the population, or the percentages do not add to 100");
        assertEquals(1d - (1d / 6d), f.covered().doubleValue(), 1e-9);
    }

    @Test
    @DisplayName("PRD-ASM-020: the frequency table can say how much of it rests on assertion")
    void attestedContributionIsVisible() {
        var report = PlanStatistics.of(List.of(
                row("o1", "A", "T1", 1, 0, 2, 0, 0, 0, 0),
                row("o1", "A", "T1", 1, 3, 0, 0, 0, 0, 0)), CATALOGUE, 2026);
        var f = report.groups().get(0).frequency();
        assertEquals(2, f.attested(),
                "a frequency table is a coverage figure derived from review counts, so it has to "
                        + "carry how many of those reviews the platform did not observe");
        assertNotNull(f.covered());
        assertEquals(2, f.more() + f.twice(),
                "both applications were reviewed more than once — one of them only on somebody's word");
    }

    @Test
    @DisplayName("an application whose branch has no root is reported, not hidden")
    void missingOrganizationIsItsOwnGroup() {
        var report = PlanStatistics.of(List.of(
                row("o1", "Alpha", "T1", 1, 0, 0, 1, 0, 0, 0),
                row(null, null, "T1", 1, 0, 0, 2, 0, 0, 0)), CATALOGUE, 2026);
        assertEquals(3, report.groups().size(), "the total, Alpha, and the rootless one");
        var orphan = report.groups().stream().filter(g -> !g.total() && g.orgId() == null).findFirst();
        assertTrue(orphan.isPresent(),
                "silently folding it into another organization would move two planned windows into a "
                        + "group that did not plan them");
        assertEquals("No organization", orphan.get().orgName());
        assertEquals(3, report.groups().get(0).plan().planned(),
                "and it still counts in the total, because it is still in the estate");
    }
}
