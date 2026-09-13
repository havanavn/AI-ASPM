package aspm.app.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.app.persistence.AllMigrations;
import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The executive layer of the overview runs against the real schema: the figures statement, the
 * observations built from it, and the per-organization posture. The figures the strip shows and the
 * sentences under it come from one statement (PRD-ASM-024), so the test asserts the keys the interface
 * reads exist, and that an application owed a review with no window is counted as unplanned while one
 * with a window is not.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OverviewInsightsTest {

    private static final UUID TENANT = UUID.fromString("7d000000-0000-4000-8000-000000000090");
    private static final UUID READER = UUID.fromString("7d000000-0000-4000-8000-000000009001");

    private DataSource dataSource;
    private OverviewInsights insights;
    private Principal reader;
    private UUID root;
    private UUID planned;
    private UUID unplanned;

    @BeforeAll
    void seed() throws Exception {
        dataSource = AllMigrations.dataSource();
        insights = new OverviewInsights(dataSource);
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "INSERT INTO tenant (id, display_name, lifecycle_state, residency_region, key_reference, entitlement_tier) "
                    + "VALUES (?, 'Overview test', 'ACTIVE', 'VN', 'vault://test', 'STANDARD') ON CONFLICT (id) DO NOTHING", TENANT);
            execute(c, "INSERT INTO password_policy (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", TENANT);
            execute(c, "INSERT INTO principal (id, tenant_id, kind, username, email, display_name, lifecycle_state) VALUES (?, ?, 'HUMAN', 'ovw.reader', 'ovw.reader@example.com', 'Reader', 'ACTIVE')",
                    READER, TENANT);
            UUID nodeType = returning(c, "INSERT INTO org_node_type (tenant_id, code, label_i18n, ordinal, may_own_assets, may_scope_work) "
                    + "VALUES (?, 'UNIT', '{\"en\":\"Unit\"}', 1, true, true) RETURNING id", TENANT);
            root = returning(c, "INSERT INTO org_node (tenant_id, type_id, name, criticality_mode) VALUES (?, ?, 'Group', 'INHERITED') RETURNING id", TENANT, nodeType);
            execute(c, "INSERT INTO org_closure (tenant_id, ancestor_id, descendant_id, depth, hierarchy_version) VALUES (?, ?, ?, 0, 1) ON CONFLICT DO NOTHING", TENANT, root, root);
            // A tier with a six-month interval, so an application that was never reviewed is owed one.
            UUID tier = returning(c, "INSERT INTO criticality_tier (tenant_id, code, label_i18n, ordinal) VALUES (?, 'T1', '{\"en\":\"Tier 1\"}', 1) RETURNING id", TENANT);
            execute(c, "INSERT INTO full_review_policy (tenant_id, criticality_tier_id, interval_months, warn_days_before) VALUES (?, ?, 6, 30)", TENANT, tier);
            UUID appType = returning(c, "INSERT INTO asset_type (tenant_id, code, label_i18n, identity_rule, is_network_reachable, may_carry_findings) "
                    + "VALUES (?, 'APPLICATION', '{\"en\":\"Application\"}', '{\"version\":1,\"natural_key_attributes\":[\"display_name\"]}', false, true) RETURNING id", TENANT);
            planned = application(c, appType, tier, "Planned App");
            unplanned = application(c, appType, tier, "Unplanned App");
            execute(c, "INSERT INTO assessment_plan_window (tenant_id, target_asset_id, starts_on, ends_on) VALUES (?, ?, current_date + 30, current_date + 40)", TENANT, planned);
            c.commit();
        }
        reader = new Principal(TENANT, READER, Set.of("vul.finding.read"), Set.of(root), true, false, false);
    }

    @Test
    @DisplayName("the figures the executive strip reads are all present, from one statement")
    void figuresCarryEveryKeyTheStripReads() throws Exception {
        Map<String, Long> f = as(() -> insights.figures(reader));
        for (String key : List.of("exposed_serious_open", "open_total", "open_90_days_ago", "apps_total",
                "apps_never_assessed", "sla_breached_open", "sla_due_7d", "exceptions_active",
                "exceptions_expiring_30d", "reviews_due", "reviews_due_unplanned", "escaped_90")) {
            assertTrue(f.containsKey(key), "missing figure " + key + " in " + f.keySet());
        }
        assertEquals(2L, f.get("apps_total"));
        assertEquals(2L, f.get("apps_never_assessed"));
    }

    @Test
    @DisplayName("PRD-ASM-026: owed-and-unplanned counts the application with no window, not the one with one")
    void unplannedIsOwedWithoutAWindow() throws Exception {
        Map<String, Long> f = as(() -> insights.figures(reader));
        assertEquals(2L, f.get("reviews_due"), "both never reviewed under a six-month interval, so both are owed");
        assertEquals(1L, f.get("reviews_due_unplanned"), "only the one without a window is unplanned");
        var observations = as(() -> insights.observations(reader));
        assertTrue(observations.stream().anyMatch(o -> o.code().equals("REVIEWS_UNPLANNED")),
                "an owed, unplanned review is an observation: " + observations.stream().map(OverviewInsights.Observation::code).toList());
        assertTrue(observations.stream().noneMatch(o -> o.code().equals("COMMITMENT_BREACHED")),
                "no service level clock exists here, so nothing is past commitment");
    }

    @Test
    @DisplayName("posture per organization carries the same three executive figures")
    void postureCarriesCommitmentAndReviewFigures() throws Exception {
        var rows = as(() -> insights.posture(reader));
        assertEquals(1, rows.size());
        var group = rows.get(0);
        assertEquals("Group", group.name());
        assertEquals(2L, group.applications());
        assertEquals(0L, group.breached());
        assertEquals(2L, group.reviewsDue());
        assertEquals(1L, group.reviewsUnplanned());
    }

    private UUID application(Connection c, UUID type, UUID tier, String name) throws SQLException {
        return returning(c, "INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, display_name, owning_node_id, criticality_mode, criticality_tier_id, "
                + "lifecycle_state, discovery_source, discovery_method, first_seen_at, last_confirmed_at) "
                + "VALUES (?, ?, ?, 1, ?, ?, 'ASSIGNED', ?, 'ACTIVE', 'manual', 'MANUAL', now(), now()) RETURNING id",
                TENANT, type, name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'), name, root, tier);
    }

    private static <T> T as(java.util.concurrent.Callable<T> body) throws SQLException {
        var context = aspm.kernel.tenantcontext.contract.TenantContext.of(new aspm.sharedkernel.TenantId(TENANT), "vn",
                aspm.kernel.tenantcontext.contract.EstablishedFrom.AUTHENTICATED_PRINCIPAL, Instant.now());
        try {
            return aspm.kernel.tenantcontext.contract.TenantContextHolder.callWith(context, body);
        } catch (SQLException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
        }
    }

    private static UUID returning(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getObject(1, UUID.class);
            }
        }
    }
}
