package aspm.app.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.app.persistence.AllMigrations;
import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * {@code PRD-ASM-026} against the engine: a window planned for a team and a person comes back naming
 * them; the plan's team filter finds an application through its planned window before any request
 * exists on it; a note edit leaves the owner alone; a team that no longer exists is said to be gone
 * rather than shown as nobody; and the business unit of a row follows the tenant's tree shape.
 *
 * <p>Ordered, because the last case changes the tree shape for everything after it and the cheapest
 * honest way to test both shapes is to test one, then change the tree, then test the other.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlanOwnerTest {

    private static final UUID TENANT = UUID.fromString("7d000000-0000-4000-8000-000000000080");
    private static final UUID PLANNER = UUID.fromString("7d000000-0000-4000-8000-000000008001");
    private static final UUID ALICE = UUID.fromString("7d000000-0000-4000-8000-000000008002");

    private DataSource dataSource;
    private PlanWindows windows;
    private AssessmentPlanQuery plan;
    private Principal planner;
    private UUID root;
    private UUID unit;
    private UUID team;
    private UUID app;
    private UUID redTeam;
    private UUID windowId;

    @BeforeAll
    void seed() throws Exception {
        dataSource = AllMigrations.dataSource();
        windows = new PlanWindows(dataSource);
        plan = new AssessmentPlanQuery(dataSource);
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "INSERT INTO tenant (id, display_name, lifecycle_state, residency_region, key_reference, entitlement_tier) "
                    + "VALUES (?, 'Plan owner test', 'ACTIVE', 'VN', 'vault://test', 'STANDARD') ON CONFLICT (id) DO NOTHING", TENANT);
            execute(c, "INSERT INTO password_policy (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", TENANT);
            for (Object[] p : new Object[][] {{PLANNER, "plan.planner", "Planner"}, {ALICE, "plan.alice", "Alice Assessor"}}) {
                execute(c, "INSERT INTO principal (id, tenant_id, kind, username, email, display_name, lifecycle_state) VALUES (?, ?, 'HUMAN', ?, ?, ?, 'ACTIVE')",
                        p[0], TENANT, p[1], p[1] + "@example.com", p[2]);
            }
            UUID nodeType = returning(c, "INSERT INTO org_node_type (tenant_id, code, label_i18n, ordinal, may_own_assets, may_scope_work) "
                    + "VALUES (?, 'UNIT', '{\"en\":\"Unit\"}', 1, true, true) RETURNING id", TENANT);
            root = node(c, nodeType, null, "Group");
            unit = node(c, nodeType, root, "Payments");
            team = node(c, nodeType, unit, "Payments Platform");
            closure(c, root, root, 0);
            closure(c, unit, unit, 0); closure(c, root, unit, 1);
            closure(c, team, team, 0); closure(c, unit, team, 1); closure(c, root, team, 2);
            UUID appType = returning(c, "INSERT INTO asset_type (tenant_id, code, label_i18n, identity_rule, is_network_reachable, may_carry_findings) "
                    + "VALUES (?, 'APPLICATION', '{\"en\":\"Application\"}', '{\"version\":1,\"natural_key_attributes\":[\"display_name\"]}', false, true) RETURNING id", TENANT);
            app = returning(c, "INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, display_name, owning_node_id, "
                    + "lifecycle_state, discovery_source, discovery_method, first_seen_at, last_confirmed_at) "
                    + "VALUES (?, ?, 'payments-portal', 1, 'Payments Portal', ?, 'ACTIVE', 'manual', 'MANUAL', now(), now()) RETURNING id",
                    TENANT, appType, team);
            redTeam = returning(c, "INSERT INTO assessor_team (tenant_id, name) VALUES (?, 'Red Team') RETURNING id", TENANT);
            execute(c, "INSERT INTO assessor_team_member (tenant_id, team_id, principal_id) VALUES (?, ?, ?)", TENANT, redTeam, ALICE);
            c.commit();
        }
        planner = new Principal(TENANT, PLANNER, Set.of("asm.request.schedule", "ast.asset.read"), Set.of(root), true, false, false);
    }

    @Test
    @Order(1)
    @DisplayName("a window planned for a team and a person comes back naming both")
    void ownerIsStoredAndNamed() throws Exception {
        PlanWindows.Created created = as(() -> windows.create(planner, List.of(new PlanWindows.Draft(app,
                LocalDate.now().plusMonths(2), LocalDate.now().plusMonths(2).plusDays(10), null, null, "Q plan", redTeam, ALICE))));
        assertEquals(1, created.windows());
        assertEquals(0, created.refusedTargets());
        PlanWindows.Window w = only(as(() -> windows.inPlan(planner)));
        windowId = UUID.fromString(w.id());
        assertEquals(redTeam.toString(), w.teamId());
        assertEquals("Red Team", w.teamName());
        assertEquals(ALICE.toString(), w.assessorId());
        assertEquals("Alice Assessor", w.assessorName());
    }

    @Test
    @Order(2)
    @DisplayName("the team and assessor filters find an application through its planned window, before any request exists")
    void plannedWindowSatisfiesTheOwnerFilter() throws Exception {
        var byTeam = as(() -> plan.rows(planner, new AssessmentPlanQuery.Filter(null, List.of(redTeam), null, false)));
        assertEquals(1, byTeam.size(), "a team with a plan and no work yet must not see an empty page");
        assertEquals(app.toString(), byTeam.get(0).assetId());
        var byPerson = as(() -> plan.rows(planner, new AssessmentPlanQuery.Filter(null, null, List.of(ALICE), false)));
        assertEquals(1, byPerson.size());
        var byOther = as(() -> plan.rows(planner, new AssessmentPlanQuery.Filter(null, List.of(UUID.randomUUID()), null, false)));
        assertTrue(byOther.isEmpty(), "another team's filter must not match through somebody else's plan");
    }

    @Test
    @Order(3)
    @DisplayName("a note edit leaves the owner alone; an explicit owner edit changes it")
    void noteEditKeepsTheOwner() throws Exception {
        LocalDate start = LocalDate.now().plusMonths(2);
        as(() -> windows.update(planner, windowId, start, start.plusDays(10), null, null, "note only"));
        PlanWindows.Window afterNote = only(as(() -> windows.inPlan(planner)));
        assertEquals("note only", afterNote.note());
        assertEquals("Red Team", afterNote.teamName(), "an update that did not mention the owner must not un-assign it");
        assertEquals("Alice Assessor", afterNote.assessorName());
        as(() -> windows.update(planner, windowId, start, start.plusDays(10), null, null, "note only", redTeam, null, true));
        PlanWindows.Window afterOwner = only(as(() -> windows.inPlan(planner)));
        assertEquals("Red Team", afterOwner.teamName());
        assertNull(afterOwner.assessorId(), "an explicit null with ownerGiven clears the person");
        assertNull(afterOwner.assessorName());
    }

    @Test
    @Order(4)
    @DisplayName("ADR-030: a team that no longer exists is said to be gone, not shown as nobody")
    void goneTeamIsSaidToBeGone() throws Exception {
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            execute(c, "DELETE FROM assessor_team_member WHERE team_id = ?", redTeam);
            execute(c, "DELETE FROM assessor_team WHERE id = ?", redTeam);
            c.commit();
        }
        PlanWindows.Window w = only(as(() -> windows.inPlan(planner)));
        assertEquals(redTeam.toString(), w.teamId(), "the reference is kept: the plan outlives the roster");
        assertEquals("(team no longer exists)", w.teamName());
    }

    @Test
    @Order(5)
    @DisplayName("PRD-ASM-026 / ADR-027: the business unit is the level below a single root, and the root itself in a forest")
    void businessUnitFollowsTheTreeShape() throws Exception {
        var rows = as(() -> plan.rows(planner, AssessmentPlanQuery.Filter.none()));
        assertEquals(1, rows.size());
        assertEquals("Payments", rows.get(0).businessUnitName(),
                "Group > Payments > Payments Platform: the unit is Payments, not the team node and not the root");
        assertEquals(unit.toString(), rows.get(0).businessUnitId());
        // A second top-level node turns the tree into a forest of units, as a tenant that models its
        // subsidiaries as roots has. The unit is then the root the application sits under.
        try (Connection c = TenantConnections.openForTenant(dataSource, TENANT)) {
            UUID nodeType = returning(c, "SELECT id FROM org_node_type WHERE tenant_id = ? AND code = 'UNIT'", TENANT);
            UUID other = node(c, nodeType, null, "Other Subsidiary");
            closure(c, other, other, 0);
            c.commit();
        }
        var forest = as(() -> plan.rows(planner, AssessmentPlanQuery.Filter.none()));
        assertEquals("Group", forest.get(0).businessUnitName());
        assertEquals(root.toString(), forest.get(0).businessUnitId());
    }

    private static PlanWindows.Window only(List<PlanWindows.Window> list) {
        assertEquals(1, list.size(), "exactly one window in this plan: " + list);
        return list.get(0);
    }

    private static UUID node(Connection c, UUID type, UUID parent, String name) throws SQLException {
        return returning(c, "INSERT INTO org_node (tenant_id, type_id, parent_id, name, criticality_mode) VALUES (?, ?, ?, ?, 'INHERITED') RETURNING id",
                TENANT, type, parent, name);
    }

    private static void closure(Connection c, UUID ancestor, UUID descendant, int depth) throws SQLException {
        execute(c, "INSERT INTO org_closure (tenant_id, ancestor_id, descendant_id, depth, hierarchy_version) VALUES (?, ?, ?, ?, 1) ON CONFLICT DO NOTHING", TENANT, ancestor, descendant, depth);
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
