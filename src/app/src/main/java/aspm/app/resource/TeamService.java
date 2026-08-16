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
 * Named groups of assessors. V034, {@code PRD-CAP-001}.
 *
 * <h2>A team is tenant vocabulary, and membership is exclusive</h2>
 *
 * <p>No team name appears anywhere in this codebase (ADR-027). What the platform enforces is that a
 * person belongs to <b>one live team</b>, and that is arithmetic rather than policy: a person in two
 * teams has their requests counted in both, and a per-team chart whose bars sum to more than the
 * work that exists is a chart nobody can plan from.
 *
 * <p>Membership is tombstoned rather than deleted. "Who was on this team when that engagement ran"
 * is what a retrospective asks, and a deleted row answers it with today's roster.
 */
public final class TeamService {

    /** Managing teams is capacity configuration; reading them rides on {@code cap.team.read}. */
    public static final String MANAGE = "cap.team.manage";

    /** A team and its live roster size. */
    public record Team(UUID id, String name, String description, boolean active, long members) {
    }

    /** One member. {@code teamId} is null in the unassigned list. */
    public record Member(UUID principalId, String displayName, String username, UUID teamId,
            String teamName) {
    }

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public TeamService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ----------------------------------------------------------------------------------------------

    /** Every team, retired ones included and marked — a retired team still owns its history. */
    public List<Team> teams(Principal principal) throws SQLException {
        List<Team> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT t.id, t.name, coalesce(t.description, ''), "
                                + "       t.lifecycle_state = 'ACTIVE', "
                                + "       (SELECT count(*) FROM assessor_team_member m "
                                + "         WHERE m.team_id = t.id AND m.removed_at IS NULL) "
                                + "  FROM assessor_team t ORDER BY t.lifecycle_state, t.name")) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Team(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                            r.getBoolean(4), r.getLong(5)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Everyone who can be put on a team, with the team they are on.
     *
     * <p>Everyone rather than only current members, because the panel's job is to let somebody build
     * a roster — a list of people already on teams cannot be used to add the person who is not.
     */
    public List<Member> assignable(Principal principal) throws SQLException {
        List<Member> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT p.id, p.display_name, p.username, m.team_id, t.name "
                                + "  FROM principal p "
                                + "  LEFT JOIN assessor_team_member m ON m.principal_id = p.id "
                                + "       AND m.removed_at IS NULL "
                                + "  LEFT JOIN assessor_team t ON t.id = m.team_id "
                                + " WHERE p.kind = 'HUMAN' AND p.lifecycle_state = 'ACTIVE' "
                                + " ORDER BY p.username")) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Member(r.getObject(1, UUID.class), r.getString(2),
                            r.getString(3), r.getObject(4, UUID.class), r.getString(5)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /** @return the new team, or empty where the name is already taken by a live one */
    public java.util.Optional<UUID> create(Principal principal, String name, String description)
            throws SQLException {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            return java.util.Optional.empty();
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO assessor_team (tenant_id, name, description, created_by, "
                                + "updated_by) VALUES (?, ?, ?, ?, ?) "
                                + "ON CONFLICT DO NOTHING RETURNING id")) {
            statement.setObject(1, principal.tenantId());
            statement.setString(2, trimmed);
            statement.setString(3, description == null || description.isBlank()
                    ? null : description.strip());
            statement.setObject(4, principal.principalId());
            statement.setObject(5, principal.principalId());
            java.util.Optional<UUID> created;
            try (ResultSet r = statement.executeQuery()) {
                created = r.next() ? java.util.Optional.of(r.getObject(1, UUID.class))
                        : java.util.Optional.empty();
            }
            if (created.isPresent()) {
                audit.domainChange(connection, principal, "assessor_team",
                        aspm.kernel.audit.contract.DomainChangeKind.CREATED,
                        created.orElseThrow(), null, java.util.Map.of("name", trimmed));
            }
            connection.commit();
            return created;
        }
    }

    /** Retires a team. Its membership history and its name stay. */
    public boolean retire(Principal principal, UUID teamId) throws SQLException {
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement members = connection.prepareStatement(
                        "UPDATE assessor_team_member SET removed_at = now(), "
                                + "removed_reason = 'TEAM_RETIRED' "
                                + " WHERE team_id = ? AND removed_at IS NULL")) {
                    members.setObject(1, teamId);
                    members.executeUpdate();
                }
                boolean done;
                try (PreparedStatement team = connection.prepareStatement(
                        "UPDATE assessor_team SET lifecycle_state = 'RETIRED', updated_at = now(), "
                                + "updated_by = ?, row_version = row_version + 1 "
                                + " WHERE id = ? AND lifecycle_state = 'ACTIVE'")) {
                    team.setObject(1, principal.principalId());
                    team.setObject(2, teamId);
                    done = team.executeUpdate() == 1;
                }
                // Recorded even where `done` is false: the membership statement above ran either
                // way, and a roster emptied by a retirement that then matched nothing is still a
                // change to who is on a team.
                audit.domainChange(connection, principal, "assessor_team",
                        aspm.kernel.audit.contract.DomainChangeKind.RETIRED, teamId, null,
                        java.util.Map.of("membership_closed", Boolean.TRUE,
                                "team_row_retired", Boolean.valueOf(done)));
                connection.commit();
                return done;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Moves somebody onto a team, or off every team when {@code teamId} is null.
     *
     * <p>A move, not an add: the live membership is closed first, because the unique index permits
     * one and an insert alongside it would fail with a constraint error the caller cannot act on.
     * Doing it in one transaction is what stops a failure leaving somebody on no team at all.
     */
    public boolean assign(Principal principal, UUID principalId, UUID teamId) throws SQLException {
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement leave = connection.prepareStatement(
                        "UPDATE assessor_team_member SET removed_at = now(), "
                                + "removed_reason = 'MOVED' "
                                + " WHERE principal_id = ? AND removed_at IS NULL "
                                + "   AND (? IS NULL OR team_id <> ?)")) {
                    leave.setObject(1, principalId);
                    leave.setObject(2, teamId);
                    leave.setObject(3, teamId);
                    leave.executeUpdate();
                }
                boolean joined = false;
                if (teamId != null) {
                    try (PreparedStatement join = connection.prepareStatement(
                            "INSERT INTO assessor_team_member (tenant_id, team_id, principal_id, "
                                    + "added_by) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                        join.setObject(1, principal.tenantId());
                        join.setObject(2, teamId);
                        join.setObject(3, principalId);
                        join.setObject(4, principal.principalId());
                        joined = join.executeUpdate() == 1;
                    }
                }
                // Every per-team figure on the workload dashboard groups by this, so a roster change
                // moves numbers a manager is measured on.
                audit.domainChange(connection, principal, "assessor_team_member",
                        aspm.kernel.audit.contract.DomainChangeKind.ASSIGNED, principalId, null,
                        java.util.Map.of("principal_id", principalId.toString(),
                                "team_id", teamId == null ? "" : teamId.toString(),
                                "action", teamId == null ? "removed from every team" : "moved"));
                connection.commit();
                return teamId == null || joined;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from "
                + "the authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
