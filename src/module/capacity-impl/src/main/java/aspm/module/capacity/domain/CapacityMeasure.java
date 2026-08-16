package aspm.module.capacity.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A capacity measure. {@code INV-CAP-03}, {@code INV-CAP-04}, {@code INV-CAP-05}, {@code INV-CAP-06},
 * ADR-022.
 *
 * <h2>Why this type is unusually defensive</h2>
 *
 * <p>This is a measurement system pointed at people, and DOC-03 section 14 states the failure directly: "A
 * measurement system producing evidence against its own users is worse than none." Every constraint below
 * exists because the number is about a person and will be read by their management chain.
 *
 * <h2>{@code INV-CAP-03} — per-member measures are RESTRICTED, and the gate is not seniority</h2>
 *
 * <p>"gated by explicit permission rather than role seniority". The distinction is the whole control: a
 * permission that any sufficiently senior role implies is not a permission, it is a job title. The prompt adds
 * that per-member measures are "excluded from business owner and executive views entirely — <b>including team
 * aggregate</b>", which is stronger than it first reads: an executive cannot see the team figure either, because
 * a small team's figure is its members'.
 *
 * <h2>{@code INV-CAP-04} — the subtraction attack</h2>
 *
 * <p>"A team of three where two members' data is visible discloses the third by subtraction. The suppression
 * rule is the only mechanism that prevents an aggregate becoming a per-person disclosure."
 */
public final class CapacityMeasure {

    /** Whether the measure describes one person or a group. Decides everything else about its handling. */
    public enum SubjectKind {
        MEMBER,
        TEAM
    }

    /**
     * The minimum contributing members for a team figure. {@code INV-CAP-04}.
     *
     * <p>Four rather than three: with three, one member who can see their own figure subtracts it from the team
     * total and has a two-person aggregate — which for a pair is one subtraction from being individual data.
     */
    public static final int MINIMUM_CONTRIBUTING_MEMBERS = 4;

    /**
     * The permission a per-member measure requires. {@code INV-CAP-03}, ADR-022.
     *
     * <p>A constant rather than a parameter, so a caller cannot pass a permission the reader happens to hold.
     */
    public static final String PER_MEMBER_PERMISSION = "cap.member.measure.read";

    /** Views from which per-member data is excluded entirely, team aggregate included. */
    public enum Audience {
        /** The security function's own capacity view. The only audience per-member data reaches. */
        SECURITY_OPERATIONS,
        /** Excluded — including the team aggregate. */
        BUSINESS_OWNER,
        /** Excluded — including the team aggregate. */
        EXECUTIVE;

        public boolean mayReceivePerMemberData() {
            return this == SECURITY_OPERATIONS;
        }
    }

    private final UUID subjectId;
    private final SubjectKind subjectKind;
    private final LocalDate periodStart;
    private final AvailableCapacity available;
    private final BigDecimal allocatedEffortDays;
    private final Map<String, BigDecimal> effortByCategory;
    private final int contributingMemberCount;
    private final TargetBand targetBand;
    private final String purposeStatement;

    /**
     * The target band. {@code INV-CAP-05}: utilization is presented against a band, <b>never a maximum</b>.
     *
     * @param reason required. Without it the upper bound reads as a target to reach, and a team at a hundred
     *     percent has no slack to absorb an incident — which is the thing the band exists to protect
     */
    public record TargetBand(int lowPercent, int highPercent, String reason) {

        public TargetBand {
            Objects.requireNonNull(reason, "the band's reason is required (INV-CAP-05, H7)");
            if (reason.isBlank()) {
                throw new IllegalArgumentException(
                        "a band with no stated reason reads as a target to exceed rather than a range to stay "
                                + "within, and a team optimising toward the upper bound has removed the slack "
                                + "that absorbs incidents");
            }
            if (lowPercent <= 0 || highPercent <= lowPercent || highPercent > 100) {
                throw new IllegalArgumentException(
                        "a band runs from a positive lower bound to a higher upper one, at most 100");
            }
        }
    }

    private CapacityMeasure(UUID subjectId, SubjectKind subjectKind, LocalDate periodStart,
            AvailableCapacity available, BigDecimal allocatedEffortDays,
            Map<String, BigDecimal> effortByCategory, int contributingMemberCount, TargetBand targetBand,
            String purposeStatement) {
        this.subjectId = Objects.requireNonNull(subjectId, "a subject is required");
        this.subjectKind = Objects.requireNonNull(subjectKind, "a subject kind is required");
        this.periodStart = Objects.requireNonNull(periodStart, "a period is required");
        this.available = Objects.requireNonNull(available, "available capacity is required");
        this.allocatedEffortDays = Objects.requireNonNull(allocatedEffortDays,
                "allocated effort is required");
        this.effortByCategory = Map.copyOf(
                Objects.requireNonNull(effortByCategory, "effort by category is required"));
        this.contributingMemberCount = contributingMemberCount;
        this.targetBand = Objects.requireNonNull(targetBand, "a target band is required (INV-CAP-05)");
        this.purposeStatement = Objects.requireNonNull(purposeStatement,
                "a purpose statement is required. A metric about a person with no stated purpose is one "
                        + "whose purpose the reader supplies, and for a per-person number that purpose is "
                        + "performance management.");

        if (purposeStatement.isBlank()) {
            throw new IllegalArgumentException("a blank purpose statement states no purpose");
        }
        if (allocatedEffortDays.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("allocated effort cannot be negative");
        }
        // INV-CAP-04, enforced at construction. A team measure below the minimum cannot exist, so no query,
        // export or report can produce one — the suppression is not a presentation decision.
        if (subjectKind == SubjectKind.TEAM && contributingMemberCount < MINIMUM_CONTRIBUTING_MEMBERS) {
            throw new IllegalArgumentException(
                    "a team measure over " + contributingMemberCount + " contributing member(s) is below the "
                            + "minimum of " + MINIMUM_CONTRIBUTING_MEMBERS + " (INV-CAP-04). A team of three "
                            + "where two members' data is visible discloses the third by subtraction, and the "
                            + "suppression rule is the only mechanism that prevents an aggregate becoming a "
                            + "per-person disclosure.");
        }
        if (subjectKind == SubjectKind.MEMBER && contributingMemberCount != 1) {
            throw new IllegalArgumentException("a member measure has exactly one contributor");
        }
        // INV-CAP-06. A capacity model counting only assessments "reports a materially over-capacity team at
        // low utilization, and that number is then used to deny resourcing".
        if (effortByCategory.size() < 2 && allocatedEffortDays.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalArgumentException(
                    "effort is recorded in " + effortByCategory.size() + " category. Effort spans EVERY work "
                            + "category, not only assessments (INV-CAP-06) — a single-category model reports a "
                            + "materially over-capacity team at low utilization, and that number is then used "
                            + "to deny resourcing.");
        }
    }

    public static CapacityMeasure forMember(UUID memberId, LocalDate periodStart,
            AvailableCapacity available, BigDecimal allocatedEffortDays,
            Map<String, BigDecimal> effortByCategory, TargetBand targetBand, String purposeStatement) {
        return new CapacityMeasure(memberId, SubjectKind.MEMBER, periodStart, available, allocatedEffortDays,
                effortByCategory, 1, targetBand, purposeStatement);
    }

    public static CapacityMeasure forTeam(UUID teamId, LocalDate periodStart, AvailableCapacity available,
            BigDecimal allocatedEffortDays, Map<String, BigDecimal> effortByCategory,
            int contributingMemberCount, TargetBand targetBand, String purposeStatement) {
        return new CapacityMeasure(teamId, SubjectKind.TEAM, periodStart, available, allocatedEffortDays,
                effortByCategory, contributingMemberCount, targetBand, purposeStatement);
    }

    /** {@code INV-CAP-03}: {@code RESTRICTED} where the subject is a member. */
    public String classification() {
        return subjectKind == SubjectKind.MEMBER ? "RESTRICTED" : "CONFIDENTIAL";
    }

    /**
     * Whether this measure may be released to a reader.
     *
     * <p>Three conditions for a member measure and they are conjunctive: the right audience, the explicit
     * permission, and — because the check is worth stating — the permission being the named one rather than any
     * the reader holds.
     *
     * @param heldPermissions the reader's effective permissions. A per-member measure requires
     *     {@link #PER_MEMBER_PERMISSION} to be among them; no other permission implies it, whatever the reader's
     *     seniority
     */
    public Release releaseTo(Audience audience, java.util.Set<String> heldPermissions) {
        Objects.requireNonNull(audience, "an audience is required");
        Objects.requireNonNull(heldPermissions, "the reader's permissions are required");

        if (subjectKind == SubjectKind.MEMBER) {
            if (!audience.mayReceivePerMemberData()) {
                return Release.withheld("per-member capacity data is excluded from the " + audience
                        + " view entirely (ADR-022). A measurement system producing evidence against its own "
                        + "users is worse than none.");
            }
            if (!heldPermissions.contains(PER_MEMBER_PERMISSION)) {
                return Release.withheld("per-member capacity requires " + PER_MEMBER_PERMISSION
                        + ", which no other permission implies (INV-CAP-03). A permission that a sufficiently "
                        + "senior role implies is not a permission, it is a job title.");
            }
            // Audited per access. The obligation travels with the release rather than being a separate thing
            // the caller remembers.
            return Release.granted(true);
        }

        if (!audience.mayReceivePerMemberData()) {
            // The team aggregate is withheld from these audiences too. The prompt says "including team
            // aggregate", and the reason is INV-CAP-04's: a small team's figure is its members'.
            return Release.withheld("team capacity is excluded from the " + audience + " view, including the "
                    + "aggregate: a small team's figure is its members' (INV-CAP-04)");
        }
        return Release.granted(false);
    }

    /** A release decision, carrying the audit obligation where there is one. */
    public record Release(boolean permitted, boolean requiresPerAccessAudit, Optional<String> reason) {

        static Release granted(boolean perAccessAudit) {
            return new Release(true, perAccessAudit, Optional.empty());
        }

        static Release withheld(String reason) {
            return new Release(false, false, Optional.of(reason));
        }
    }

    /**
     * Utilization as a proportion of available capacity.
     *
     * <p>Returned as a value with its band, never bare. {@link #presentation} is what a caller renders.
     */
    public BigDecimal utilizationPercent() {
        BigDecimal availableDays = available.netAvailableDays();
        if (availableDays.compareTo(BigDecimal.ZERO) == 0) {
            // Zero available and any allocation is not "infinite utilization"; it is somebody working while
            // on leave, and a division here would produce an arithmetic exception or an absurd number.
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return allocatedEffortDays.multiply(BigDecimal.valueOf(100))
                .divide(availableDays, 2, RoundingMode.HALF_UP);
    }

    /** Whether utilization sits inside the target band. Neither below nor above is "good". */
    public boolean withinTargetBand() {
        BigDecimal utilization = utilizationPercent();
        return utilization.compareTo(BigDecimal.valueOf(targetBand.lowPercent())) >= 0
                && utilization.compareTo(BigDecimal.valueOf(targetBand.highPercent())) <= 0;
    }

    /**
     * The figure with its band, its reason and its purpose. {@code INV-CAP-05}.
     *
     * <p>There is deliberately no accessor returning utilization against a maximum, and no "percent of
     * capacity" phrasing: a maximum reads as a target, and the whole point of a band is that a hundred percent
     * is a failure state.
     */
    public String presentation() {
        return "Utilization " + utilizationPercent() + "% against a target band of " + targetBand.lowPercent()
                + "–" + targetBand.highPercent() + "%"
                + (withinTargetBand() ? " (within band)" : " (OUTSIDE the band)")
                + ".\nWhy this band: " + targetBand.reason()
                + "\nCapacity: " + available.breakdown()
                + "\nPurpose: " + purposeStatement;
    }

    public UUID subjectId() {
        return subjectId;
    }

    public SubjectKind subjectKind() {
        return subjectKind;
    }

    public LocalDate periodStart() {
        return periodStart;
    }

    public AvailableCapacity available() {
        return available;
    }

    public BigDecimal allocatedEffortDays() {
        return allocatedEffortDays;
    }

    /** {@code INV-CAP-06}: every work category, not only assessments. */
    public Map<String, BigDecimal> effortByCategory() {
        return effortByCategory;
    }

    public int contributingMemberCount() {
        return contributingMemberCount;
    }

    public TargetBand targetBand() {
        return targetBand;
    }

    public String purposeStatement() {
        return purposeStatement;
    }
}
