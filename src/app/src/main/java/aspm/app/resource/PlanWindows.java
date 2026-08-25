package aspm.app.resource;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The planned assessment windows — the one write path onto {@code assessment_plan_window}.
 *
 * <h2>What a window is, and what it deliberately is not</h2>
 *
 * An intention: this target, between these two dates. It has no payload, no scope descriptor, no
 * workflow state and no assignee, because a plan laid out for next year does not know them and
 * inventing them would be the fabrication product principle 1 forbids. V070's header carries the full
 * argument for why this is not a draft assessment request; the short version is that the plan and the
 * record of work have to be able to disagree, or the gap between them closes itself.
 *
 * <h2>Why the write path is one class</h2>
 *
 * {@code SEC-AUZ-016} wants the scope predicate to be part of retrieval rather than a filter applied
 * afterwards, and the way that stays true is for there to be one place a window can be written. Every
 * method here resolves the target through the caller's own closure expansion before it writes, and
 * there is no method that takes a target the caller has not been shown to reach.
 *
 * <h2>Refusals are indistinguishable from absence</h2>
 *
 * A target outside the caller's scope, a target that does not exist, and a target of the wrong type
 * all produce the same empty result, and the endpoint answers 404 for all three. A distinct refusal
 * for "exists but not yours" is an existence oracle over the estate ({@code SEC-AUZ-020}).
 */
public final class PlanWindows {

    /**
     * The asset types a window may point at.
     *
     * <p>Not a database constraint: the type is a join away and a CHECK cannot see it, so ADR-030 puts
     * this in the domain layer. It is a constant rather than a literal in a query because the same set
     * has to be quoted by the test that asserts the refusal, and two spellings of one rule diverge.
     *
     * <p>An application is what an estate is planned at; a project is what the work is sized from,
     * because {@code api_count} and {@code access_path} are declared on the project. A service or a
     * domain is neither — planning an assessment of a hostname is planning it of whatever is behind
     * the hostname, which is the application.
     */
    public static final Set<String> PLANNABLE_TYPES = Set.of("APPLICATION", "PROJECT");

    /**
     * The most windows one request may create.
     *
     * <p>The bulk create exists so a planner can lay out a year for many targets at once, which is the
     * whole point — a hundred applications reviewed quarterly is four hundred windows and that is an
     * ordinary year, not an abuse. The bound is here so a malformed client cannot turn one request into
     * an unbounded write, and it is stated in the refusal rather than silently truncating: a plan that
     * saved some of what was asked for and said nothing is worse than one that saved none.
     */
    public static final int MAX_PER_REQUEST = 2_000;

    /** A window as the interface reads it. Dates as ISO strings — the wire format is a date, not an instant. */
    public record Window(String id, String targetAssetId, String targetName, String targetTypeCode,
            String startsOn, String endsOn, String assessmentTypeId, String assessmentTypeName,
            String triggerId, String note, String state, String requestId, String requestCode) {
    }

    /** A window as a caller asks for it. */
    public record Draft(UUID targetAssetId, LocalDate startsOn, LocalDate endsOn,
            UUID assessmentTypeId, UUID triggerId, String note) {
    }

    /** What a bulk create did, so the caller can be told rather than guess. */
    public record Created(int windows, int refusedTargets) {
    }

    private final DataSource dataSource;
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public PlanWindows(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /**
     * Every live window for the targets the caller can reach, within a date range.
     *
     * <p>Cancelled windows are included and carry their state, because "we planned six and cancelled
     * two" is the planning finding and hiding the cancelled ones reports a plan that was always four.
     * The caller decides what to draw.
     *
     * @param from inclusive; a window overlapping the range at either end is in range, because a
     *     fortnight straddling 1 January belongs to both years a reader might be looking at
     */
    public List<Window> inRange(Principal principal, LocalDate from, LocalDate to)
            throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        List<Window> windows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT w.id::text, w.target_asset_id::text, a.display_name, t.code,
                               to_char(w.starts_on, 'YYYY-MM-DD'), to_char(w.ends_on, 'YYYY-MM-DD'),
                               w.assessment_type_id::text, ty.label_i18n ->> 'en',
                               w.trigger_id::text, w.note, w.state,
                               w.request_id::text, r.request_code
                          FROM assessment_plan_window w
                          JOIN asset a ON a.id = w.target_asset_id
                          JOIN asset_type t ON t.id = a.type_id
                          LEFT JOIN assessment_type ty ON ty.id = w.assessment_type_id
                          LEFT JOIN assessment_request r ON r.id = w.request_id
                         WHERE w.ends_on >= ? AND w.starts_on <= ?
                           AND a.owning_node_id IN (SELECT descendant_id FROM org_closure
                                                     WHERE ancestor_id = ANY (?))
                         ORDER BY w.starts_on, a.display_name
                        """)) {
            statement.setObject(1, from);
            statement.setObject(2, to);
            statement.setArray(3, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    windows.add(new Window(r.getString(1), r.getString(2), r.getString(3),
                            r.getString(4), r.getString(5), r.getString(6), r.getString(7),
                            r.getString(8), r.getString(9), r.getString(10), r.getString(11),
                            r.getString(12), r.getString(13)));
                }
            }
        }
        return windows;
    }

    /**
     * Creates windows in one transaction.
     *
     * <p><b>All or nothing.</b> A planner who asked for a year across forty applications and got
     * thirty-one of them, with no statement of which nine are missing, has a plan they cannot trust and
     * would have to diff by hand. So an unreachable or wrong-typed target aborts the whole batch and is
     * counted in the refusal, rather than being skipped quietly. The count is what the endpoint turns
     * into a 404 — never a list of which targets were refused, because that list is the existence
     * oracle {@code SEC-AUZ-020} forbids.
     *
     * <p><b>Audit: one event per window AND a summary.</b> {@code SEC-AUD-009} requires both of a bulk
     * operation, and it is right to: the summary answers "a plan was laid out on Tuesday" and the
     * per-item events answer "when did THIS window enter the plan, and who put it there" — which is
     * the question asked when a target turns out not to have been assessed. The rows carry
     * {@code created_by} themselves, but a row records only its current state, so a window later moved
     * would have no trace of the date it was first planned for.
     *
     * <p>That is also why the insert is a loop returning identifiers rather than a batch: a per-item
     * event needs the identifier of the item. The batch would have saved round trips the chained audit
     * writer then spends anyway, one per event, so there is nothing to optimise away here.
     */
    public Created create(Principal principal, List<Draft> drafts) throws SQLException {
        Objects.requireNonNull(drafts, "drafts are required");
        if (drafts.isEmpty()) {
            return new Created(0, 0);
        }
        if (drafts.size() > MAX_PER_REQUEST) {
            throw new IllegalArgumentException("a single request may create at most "
                    + MAX_PER_REQUEST + " windows; " + drafts.size() + " were asked for. Split the "
                    + "batch — truncating it would save a plan nobody asked for.");
        }
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return new Created(0, drafts.size());
        }
        for (Draft draft : drafts) {
            if (draft.startsOn() == null || draft.endsOn() == null) {
                throw new IllegalArgumentException("a window needs both a start and an end date");
            }
            if (draft.endsOn().isBefore(draft.startsOn())) {
                throw new IllegalArgumentException("a window ending before it starts is not a window: "
                        + draft.startsOn() + " to " + draft.endsOn());
            }
        }
        try (Connection connection = open(principal)) {
            Set<UUID> plannable = plannableTargets(connection, scope,
                    drafts.stream().map(Draft::targetAssetId).distinct().toList());
            int refused = 0;
            for (Draft draft : drafts) {
                if (!plannable.contains(draft.targetAssetId())) {
                    refused++;
                }
            }
            if (refused > 0) {
                // Nothing written and nothing to roll back: the transaction has not written yet, so
                // closing it is clean rather than the "wrote and was closed without commit" refusal.
                return new Created(0, refused);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO assessment_plan_window
                        (tenant_id, target_asset_id, starts_on, ends_on, assessment_type_id,
                         trigger_id, note, created_by, updated_by)
                    VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """)) {
                for (Draft draft : drafts) {
                    statement.setObject(1, draft.targetAssetId());
                    statement.setObject(2, draft.startsOn());
                    statement.setObject(3, draft.endsOn());
                    statement.setObject(4, draft.assessmentTypeId());
                    statement.setObject(5, draft.triggerId());
                    statement.setString(6, blankToNull(draft.note()));
                    statement.setObject(7, principal.principalId());
                    statement.setObject(8, principal.principalId());
                    UUID created;
                    try (ResultSet r = statement.executeQuery()) {
                        if (!r.next()) {
                            throw new SQLException("the insert returned no identifier, so the "
                                    + "per-item audit event SEC-AUD-009 requires cannot be written");
                        }
                        created = r.getObject(1, UUID.class);
                    }
                    audit.domainChange(connection, principal, "assessment_plan_window",
                            aspm.kernel.audit.contract.DomainChangeKind.CREATED, created,
                            aspm.app.audit.AuditScopes.ofAsset(connection, draft.targetAssetId()),
                            java.util.Map.of("target_asset_id", draft.targetAssetId().toString(),
                                    "starts_on", draft.startsOn().toString(),
                                    "ends_on", draft.endsOn().toString()));
                }
            }
            LocalDate earliest = drafts.stream().map(Draft::startsOn).min(LocalDate::compareTo)
                    .orElseThrow();
            LocalDate latest = drafts.stream().map(Draft::endsOn).max(LocalDate::compareTo)
                    .orElseThrow();
            audit.event(connection, principal,
                    aspm.kernel.audit.contract.AuditEventType.BULK_EXECUTED, null, null,
                    java.util.Map.of(
                            "action", "assessment_plan_window.planned",
                            "windows", Integer.valueOf(drafts.size()),
                            "targets", Integer.valueOf(
                                    (int) drafts.stream().map(Draft::targetAssetId).distinct().count()),
                            "earliest_start", earliest.toString(),
                            "latest_end", latest.toString()));
            connection.commit();
            return new Created(drafts.size(), 0);
        }
    }

    /**
     * Moves or re-describes one window.
     *
     * <p>Returns empty where the window is not the caller's to change, which the endpoint answers as
     * 404. A converted window can still be moved: the plan said one thing, the request may have been
     * raised for another date, and correcting the plan afterwards is how planned-versus-actual stays
     * honest rather than being quietly overwritten by what happened.
     */
    public Optional<UUID> update(Principal principal, UUID id, LocalDate startsOn, LocalDate endsOn,
            UUID assessmentTypeId, UUID triggerId, String note) throws SQLException {
        Objects.requireNonNull(id, "a window identifier is required");
        if (startsOn == null || endsOn == null) {
            throw new IllegalArgumentException("a window needs both a start and an end date");
        }
        if (endsOn.isBefore(startsOn)) {
            throw new IllegalArgumentException("a window ending before it starts is not a window: "
                    + startsOn + " to " + endsOn);
        }
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE assessment_plan_window w
                           SET starts_on = ?, ends_on = ?, assessment_type_id = ?, trigger_id = ?,
                               note = ?, updated_at = now(), updated_by = ?,
                               row_version = w.row_version + 1
                         WHERE w.id = ?
                           AND EXISTS (SELECT 1 FROM asset a
                                        WHERE a.id = w.target_asset_id
                                          AND a.owning_node_id IN
                                              (SELECT descendant_id FROM org_closure
                                                WHERE ancestor_id = ANY (?)))
                        """)) {
            statement.setObject(1, startsOn);
            statement.setObject(2, endsOn);
            statement.setObject(3, assessmentTypeId);
            statement.setObject(4, triggerId);
            statement.setString(5, blankToNull(note));
            statement.setObject(6, principal.principalId());
            statement.setObject(7, id);
            statement.setArray(8, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            if (statement.executeUpdate() == 0) {
                // Nothing matched, so there is nothing to record — but the statement still ran, and
                // TenantConnections counts a ran-and-matched-nothing UPDATE as a write. Closing
                // without saying the unit of work is finished raises rather than 404s, which turns
                // "no such window" into a 500. Measured: it did.
                connection.commit();
                return Optional.empty();
            }
            audit.domainChange(connection, principal, "assessment_plan_window",
                    aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id, null,
                    java.util.Map.of("starts_on", startsOn.toString(),
                            "ends_on", endsOn.toString()));
            connection.commit();
            return Optional.of(id);
        }
    }

    /**
     * Cancels a window. The row stays.
     *
     * <p>Deleting it would make a dropped plan indistinguishable from a plan that never existed, and
     * the difference between those two is the whole output of a planning review.
     */
    public Optional<UUID> cancel(Principal principal, UUID id) throws SQLException {
        return setState(principal, id, "CANCELLED", null);
    }

    /** Restores a cancelled window to the plan. */
    public Optional<UUID> restore(Principal principal, UUID id) throws SQLException {
        return setState(principal, id, "PLANNED", null);
    }

    /**
     * Records that a window became a request.
     *
     * <p>Called after the request exists, never instead of creating one: the conversion is a human
     * action through the intake form, because a request needs a scope and product principle 4 forbids
     * the platform choosing one on the user's behalf.
     */
    public Optional<UUID> markConverted(Principal principal, UUID id, UUID requestId)
            throws SQLException {
        Objects.requireNonNull(requestId, "a converted window points at the request it became");
        return setState(principal, id, "CONVERTED", requestId);
    }

    private Optional<UUID> setState(Principal principal, UUID id, String state, UUID requestId)
            throws SQLException {
        Objects.requireNonNull(id, "a window identifier is required");
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE assessment_plan_window w
                           SET state = ?,
                               -- coalesce, not assignment: a window converted once keeps the request
                               -- it became even if it is later cancelled, because "this plan was
                               -- acted on" survives the plan being dropped.
                               request_id = coalesce(?, w.request_id),
                               updated_at = now(), updated_by = ?,
                               row_version = w.row_version + 1
                         WHERE w.id = ?
                           AND EXISTS (SELECT 1 FROM asset a
                                        WHERE a.id = w.target_asset_id
                                          AND a.owning_node_id IN
                                              (SELECT descendant_id FROM org_closure
                                                WHERE ancestor_id = ANY (?)))
                        """)) {
            statement.setString(1, state);
            statement.setObject(2, requestId);
            statement.setObject(3, principal.principalId());
            statement.setObject(4, id);
            statement.setArray(5, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            if (statement.executeUpdate() == 0) {
                // See update(): an UPDATE that matched nothing is still a write as far as the
                // transaction is concerned, and must be closed out or the 404 becomes a 500.
                connection.commit();
                return Optional.empty();
            }
            audit.domainChange(connection, principal, "assessment_plan_window",
                    "CANCELLED".equals(state)
                            ? aspm.kernel.audit.contract.DomainChangeKind.RETIRED
                            : aspm.kernel.audit.contract.DomainChangeKind.TRANSITIONED,
                    id, null,
                    requestId == null
                            ? java.util.Map.of("state", state)
                            : java.util.Map.of("state", state, "request_id", requestId.toString()));
            connection.commit();
            return Optional.of(id);
        }
    }

    /**
     * Of the identifiers given, the ones the caller may plan against.
     *
     * <p>One query for the whole batch rather than one per target: a year across forty applications is
     * forty round trips otherwise, and the check has to happen inside the same transaction as the
     * insert or it is a check against a state that has since changed.
     *
     * <p>The type restriction and the scope predicate are applied together here, deliberately. Two
     * separate checks are two places for the next person to add a third asset type to and only find
     * one of them.
     */
    private Set<UUID> plannableTargets(Connection connection, Set<UUID> scope, List<UUID> targets)
            throws SQLException {
        if (targets.isEmpty()) {
            return Set.of();
        }
        Set<UUID> allowed = new java.util.HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.id
                  FROM asset a
                  JOIN asset_type t ON t.id = a.type_id
                 WHERE a.id = ANY (?)
                   AND t.code = ANY (?)
                   AND a.lifecycle_state <> 'RETIRED'
                   AND a.owning_node_id IN (SELECT descendant_id FROM org_closure
                                             WHERE ancestor_id = ANY (?))
                """)) {
            statement.setArray(1, connection.createArrayOf("uuid", targets.toArray(new UUID[0])));
            statement.setArray(2,
                    connection.createArrayOf("text", PLANNABLE_TYPES.toArray(new String[0])));
            statement.setArray(3, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    allowed.add(r.getObject(1, UUID.class));
                }
            }
        }
        return allowed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
