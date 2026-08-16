package aspm.app.resource;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The periodic review interval, per criticality tier. V043.
 *
 * <h2>What this configures, and why it is more powerful than it looks</h2>
 *
 * <p>One number — how many months an application on a given tier may go between full reviews — and
 * one warning window. Everything the assessment plan shows is derived from them: the next due date,
 * whether an application is overdue, and the entire coverage picture. Widening the interval does not
 * schedule anything, it makes a proportion of the estate stop being overdue, retroactively and
 * silently, because the cadence is computed rather than stored.
 *
 * <p>That is why {@link #MANAGE} is restricted and requires step-up while merely reading it does
 * not. The reader is planning; the writer is redefining what the plan is measured against.
 *
 * <h2>A tier with no policy is a state, not a blank</h2>
 *
 * <p>{@link #tiers} returns every criticality tier the tenant has, including those with no policy
 * row, so the editor shows the gap rather than omitting the tier. An application on a tier with no
 * interval reports {@code NO_OBLIGATION} — which reads like compliance and is not: nothing was ever
 * required of it. Product principle 1. The absence has to be visible in the place where it can be
 * corrected, or nobody corrects it.
 *
 * <h2>No tier is named in this class</h2>
 *
 * <p>Criticality tiers are tenant data (ADR-027). Everything here is keyed by tier id, and the codes
 * that come back are whatever the tenant configured. A tenant with seven tiers gets seven rows.
 */
public final class ReviewPolicyService {

    /** Setting the interval every application on a tier is measured against. Restricted, step-up. */
    public static final String MANAGE = "asm.policy.manage";

    /** One criticality tier, with its review interval if it has one. */
    public record TierPolicy(String tierId, String code, int ordinal, Integer intervalMonths,
            Integer warnDaysBefore, long applications, String updatedAt) {
    }

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public ReviewPolicyService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /**
     * Every tier, with its policy and how many applications it governs.
     *
     * <p>The application count is the reason this is not a two-column form. Changing an interval from
     * twelve months to twenty-four is a different decision when it affects two applications than when
     * it affects two hundred, and the editor should not make the person guess which one they are
     * making.
     */
    public List<TierPolicy> tiers(Principal principal) throws SQLException {
        List<TierPolicy> out = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT ct.id::text, ct.code, ct.ordinal,
                               p.interval_months, p.warn_days_before,
                               (SELECT count(*) FROM asset a
                                  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION'
                                 WHERE a.lifecycle_state <> 'RETIRED'
                                   AND coalesce(a.criticality_tier_id,
                                                (SELECT n.criticality_tier_id FROM org_node n
                                                  WHERE n.id = a.owning_node_id)) = ct.id),
                               to_char(p.updated_at, 'YYYY-MM-DD HH24:MI')
                          FROM criticality_tier ct
                          LEFT JOIN full_review_policy p ON p.criticality_tier_id = ct.id
                         WHERE ct.lifecycle_state <> 'RETIRED'
                         ORDER BY ct.ordinal
                        """)) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.add(new TierPolicy(r.getString(1), r.getString(2), r.getInt(3),
                            r.getObject(4) == null ? null : Integer.valueOf(r.getInt(4)),
                            r.getObject(5) == null ? null : Integer.valueOf(r.getInt(5)),
                            r.getLong(6), r.getString(7)));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Sets one tier's interval, or removes it.
     *
     * @param intervalMonths {@code null} removes the policy, which is an explicit decision that this
     *     tier carries no periodic obligation — not the same as leaving the field empty by accident,
     *     which is why the caller has to send an explicit clear rather than an absent field.
     * @return false when the tier does not belong to this tenant, so the caller returns not-found
     *     rather than reporting a write that did not happen
     */
    public boolean set(Principal principal, UUID tierId, Integer intervalMonths,
            Integer warnDaysBefore) throws SQLException {
        if (tierId == null) {
            return false;
        }
        try (Connection connection = open(principal)) {
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT 1 FROM criticality_tier WHERE id = ?")) {
                check.setObject(1, tierId);
                try (ResultSet r = check.executeQuery()) {
                    if (!r.next()) {
                        return false;
                    }
                }
            }
            if (intervalMonths == null) {
                try (PreparedStatement clear = connection.prepareStatement(
                        "DELETE FROM full_review_policy WHERE criticality_tier_id = ?")) {
                    clear.setObject(1, tierId);
                    clear.executeUpdate();
                }
                audit.domainChange(connection, principal, "full_review_policy",
                        aspm.kernel.audit.contract.DomainChangeKind.UPDATED, tierId, null,
                        java.util.Map.of("criticality_tier_id", tierId.toString(),
                                "interval_months", "cleared"));
                connection.commit();
                return true;
            }
            // Bounded rather than validated-and-rejected. One month is a plausible interval for a
            // payments front end and 120 is a plausible way of saying "effectively never"; a value
            // outside that is a typo, and clamping a typo is kinder than a form that loses the rest
            // of the edit.
            //
            // The upper bounds are the SCHEMA's, not invented here: ck_full_review_policy__interval
            // allows 1..120 months and ck_full_review_policy__warn allows 0..366 days. Clamping to a
            // wider range than the constraint allows is how a form turns a typo into a 500, which is
            // exactly what a first version of this did with a warning window of 3599 days.
            //
            // The warning window is also held below the interval itself: a warning that begins before
            // the previous review finished is permanently on, which is no warning at all.
            int months = Math.max(1, Math.min(120, intervalMonths));
            int warn = warnDaysBefore == null ? 30 : warnDaysBefore;
            warn = Math.max(0, Math.min(Math.min(366, months * 30 - 1), warn));
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO full_review_policy (tenant_id, criticality_tier_id, interval_months,
                                                    warn_days_before, created_by, updated_by)
                    VALUES (current_tenant_id(), ?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, criticality_tier_id) DO UPDATE
                       SET interval_months = EXCLUDED.interval_months,
                           warn_days_before = EXCLUDED.warn_days_before,
                           updated_at = now(), updated_by = EXCLUDED.updated_by,
                           row_version = full_review_policy.row_version + 1
                    """)) {
                statement.setObject(1, tierId);
                statement.setInt(2, months);
                statement.setInt(3, warn);
                statement.setObject(4, principal.principalId());
                statement.setObject(5, principal.principalId());
                statement.executeUpdate();
            }
            // How often a whole application must be reviewed is the cadence every overdue figure is
            // measured against, so lengthening it makes overdue work disappear from a dashboard
            // without anything being assessed.
            audit.domainChange(connection, principal, "full_review_policy",
                    aspm.kernel.audit.contract.DomainChangeKind.UPDATED, tierId, null,
                    java.util.Map.of("criticality_tier_id", tierId.toString(),
                            "interval_months", Integer.valueOf(months),
                            "warn_days_before", Integer.valueOf(warn)));
            connection.commit();
            return true;
        }
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
