package aspm.app.assessment;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Assessment requests and the findings recorded against them. DOC-09, DOC-06, {@code PRD-ASM-*}.
 *
 * <h2>A finding belongs to the engagement that found it AND to the asset it is in</h2>
 *
 * <p>{@code discovered_in_request_id} says which engagement produced it;
 * {@code finding_asset_impact} says what it is in. Both, because they answer different questions: "what
 * did this pentest find" and "what is wrong with this application" are not the same list, and a tool
 * that keeps only one of them cannot produce the other.
 *
 * <p>The board counts the first. An application with two hundred historical findings does not make
 * this week's engagement a two-hundred-finding engagement.
 *
 * <h2>Risk acceptance goes through an exception, never through a column</h2>
 *
 * <p>The obvious shortcut is a date on the finding. {@link #acceptRisk} instead creates a
 * {@code risk_exception} in the {@code REQUESTED} state: it carries a requester, an expiry, a
 * duration bound and a state machine, and the engine refuses to approve one whose approver is its own
 * requester. An acceptance with an end date but no approver is a way to close a ticket, and the
 * schema will not represent one.
 */
public final class AssessmentService {

    /** How a finding was produced. Product vocabulary — see the note on the column in V021. */
    public static final List<String> CONTEXTS = List.of(
            "INTERNAL_PENTEST", "EXTERNAL_PENTEST", "REDTEAM_INTERNAL", "REDTEAM_EXTERNAL",
            "AUTOMATED_SCAN", "BUG_BOUNTY", "INCIDENT");

    /** The default when a person records a finding by hand, as the user asked. */
    public static final String DEFAULT_CONTEXT = "INTERNAL_PENTEST";

    /** One row of the request board. */
    public record Request(UUID id, String code, String title, String state, String createdAt,
            String dueAt, boolean retest, UUID orgNodeId, String orgNodeName,
            List<String> orgAncestors, UUID requestedBy, UUID contactId, UUID leadId,
            long scopeAssets, String primaryApplication, long findingTotal, long findingOpen,
            long findingAccepted, long findingSevereOpen, UUID triggerId, String triggerCode,
            String triggerLabel, boolean triggerIsFullReview, String closedAt,
            String stateCategory) {

        /**
         * Whether the request has reached a terminal state.
         *
         * <p>Read from the workflow definition's state CATEGORY, not from the state code. The
         * previous form of this test asked whether the code started with {@code CLOSED}, which is a
         * hardcoded assumption about a tenant-configurable vocabulary (ADR-027) and got two of the
         * four seeded terminal states wrong: {@code CANCELLED} and {@code REJECTED} do not start with
         * that word, so a cancelled request past its deadline was still being counted as overdue.
         */
        public boolean terminal() {
            return "TERMINAL".equals(stateCategory);
        }

        /**
         * Past its deadline and not finished. A closed request cannot be late.
         *
         * <p><b>UTC, explicitly.</b> {@code NFR-INT-003} stores instants in UTC and computes business
         * calendars in the tenant's timezone; the tenant timezone is not wired yet, so this uses UTC
         * and says so rather than silently taking the server's zone. The difference is a day either
         * side of midnight on every deadline in the platform, and the server's zone is a property of
         * where the container happens to run.
         */
        public boolean overdue() {
            return dueAt != null && !terminal()
                    && dueAt.compareTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()) < 0;
        }
    }

    /** A finding as the request page lists it. */
    public record Finding(UUID id, String title, String severity, String severityOrdinal,
            String state, String closureReason, String findingClass, String assessmentContext,
            String firstDetectedAt, String lastDetectedAt, String description,
            String proofOfConcept, String sourceTool, UUID assigneeId, String acceptedUntil,
            String assetName, int rowVersion, String remediationClaimedAt,
            String remediationClaimedBy, String remediationNote) {

        /**
         * A fix has been claimed and nobody has retested it.
         *
         * <p>Distinct from open-and-untouched and from closed-and-verified, and the difference is the
         * point: this is the retest queue. See V032 for why a claim is not a closure.
         */
        public boolean awaitingRetest() {
            return remediationClaimedAt != null && "OPEN".equals(state);
        }

        public boolean accepted() {
            return "RISK_ACCEPTED".equals(closureReason);
        }

        public boolean open() {
            return "OPEN".equals(state);
        }
    }

    /** A comment on a request or a finding. */
    public record Comment(UUID id, UUID authorId, String authorName, String body, String createdAt,
            int editCount, boolean redacted) {
    }

    private final DataSource dataSource;

    /**
     * The interface's own writes leave a chained record, as the machine doors already did.
     *
     * <p>{@code CON-PLT-021}: the event is written on the caller's connection, inside the caller's
     * transaction, so an action whose record cannot be written does not happen. That trade is only
     * available because these paths are units of work — while they ran on autocommit the change was
     * already committed by the time an event could be attempted, and the two could disagree.
     */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public AssessmentService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ----------------------------------------------------------------------------------------------

    /** The board, scoped to the caller and filtered. */
    public List<Request> board(Principal principal, Map<String, String> filters, String search,
            String sort) throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT id, request_code, title, state, created_at, due_at, is_retest, "
                        + "requested_org_node_id, org_node_name, org_ancestors, requested_by, "
                        + "requester_contact_id, lead_principal_id, scope_assets, "
                        + "primary_application, finding_total, finding_open, finding_accepted, "
                        + "finding_severe_open, trigger_id, trigger_code, "
                        // The label is tenant data and locale-keyed. English is resolved here as the
                        // fallback the rest of the interface already uses; the code is carried
                        // alongside so a tenant that has not translated a row still shows something.
                        + "coalesce(trigger_label->>'en', trigger_code), "
                        + "trigger_is_full_review, closed_at, state_category FROM request_board "
                        + " WHERE requested_org_node_id IN "
                        + "   (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))");
        List<Object> parameters = new ArrayList<>();
        parameters.add(scope.toArray(new UUID[0]));

        // ------------------------------------------------------------------------------------------
        // Multi-valued filters.
        //
        // Every one of these accepts a comma-separated list, and an EMPTY list is not the same as an
        // absent one. "Nothing selected" has to mean nothing matches, not everything matches: a person
        // who unticks every option and sees the full board again will conclude the filter is broken,
        // and — worse on this screen — a person who unticks every organisation but one, mistypes, and
        // sees every organisation will read somebody else's numbers as their own.
        // ------------------------------------------------------------------------------------------
        List<String> states = list(filters.get("state"));
        if (states != null) {
            if (states.isEmpty()) {
                return List.of();
            }
            sql.append(" AND state = ANY (?)");
            parameters.add(states.toArray(new String[0]));
        }
        // Organisation. Each selected node matches its whole SUBTREE, because a request raised against
        // a team inside a division is a request that division is accountable for — an exact-node match
        // would make the division filter report almost nothing and look like an empty estate.
        List<UUID> nodes = uuids(filters.get("node"));
        if (nodes != null) {
            if (nodes.isEmpty()) {
                return List.of();
            }
            sql.append(" AND requested_org_node_id IN (SELECT descendant_id FROM org_closure "
                    + "WHERE ancestor_id = ANY (?))");
            parameters.add(nodes.toArray(new UUID[0]));
        }
        // Project, against the scope the REQUESTER declared at intake. Not the assessment's resolved
        // scope: a full review pulls in every sibling project, and filtering on the resolved set would
        // return a request under a project nobody asked to have looked at.
        List<UUID> projects = uuids(filters.get("project"));
        if (projects != null) {
            if (projects.isEmpty()) {
                return List.of();
            }
            sql.append(" AND EXISTS (SELECT 1 FROM assessment_request_scope_asset sa "
                    + "WHERE sa.request_id = request_board.id AND sa.asset_id = ANY (?))");
            parameters.add(projects.toArray(new UUID[0]));
        }
        // Application, matched through the composition graph rather than by name. The board's
        // primary_application is a display string and two tenants may well have two applications
        // called "Portal"; the identifier is what the picker actually selected.
        List<UUID> applications = uuids(filters.get("application"));
        if (applications != null) {
            if (applications.isEmpty()) {
                return List.of();
            }
            sql.append(" AND EXISTS (SELECT 1 FROM assessment_request_scope_asset sa "
                    + " WHERE sa.request_id = request_board.id "
                    // asset_composition resolves each asset to the APPLICATION at the root of its
                    // tree, which is how a request naming only a project is still found by a filter on
                    // the application it belongs to. The application is derived, never stored twice.
                    + "   AND (sa.asset_id = ANY (?) "
                    + "        OR EXISTS (SELECT 1 FROM asset_composition ac "
                    + "                    WHERE ac.asset_id = sa.asset_id "
                    + "                      AND ac.root_id = ANY (?))))");
            parameters.add(applications.toArray(new UUID[0]));
            parameters.add(applications.toArray(new UUID[0]));
        }
        // Assessor. "none" is a selectable value and not the absence of a selection: an in-flight
        // request with nobody answerable is the row a lead is looking for, and it cannot be asked for
        // by leaving a filter blank.
        List<String> assessors = list(filters.get("assessor"));
        if (assessors != null) {
            if (assessors.isEmpty()) {
                return List.of();
            }
            List<UUID> ids = new ArrayList<>();
            boolean unassigned = false;
            for (String value : assessors) {
                if ("none".equals(value)) {
                    unassigned = true;
                } else {
                    try {
                        ids.add(UUID.fromString(value));
                    } catch (IllegalArgumentException e) {
                        // An unparseable identifier narrows to nothing rather than widening to
                        // everything. A filter that fails open is a filter that shows other people's
                        // work when it breaks.
                        return List.of();
                    }
                }
            }
            if (ids.isEmpty() && unassigned) {
                sql.append(" AND lead_principal_id IS NULL");
            } else if (!ids.isEmpty() && unassigned) {
                sql.append(" AND (lead_principal_id = ANY (?) OR lead_principal_id IS NULL)");
                parameters.add(ids.toArray(new UUID[0]));
            } else {
                sql.append(" AND lead_principal_id = ANY (?)");
                parameters.add(ids.toArray(new UUID[0]));
            }
        }
        // Why the request was raised. The filter matches the CODE, which is tenant data — the
        // application never enumerates the permitted values and an unknown one simply returns nothing.
        List<String> triggers = list(filters.get("trigger"));
        if (triggers != null) {
            if (triggers.isEmpty()) {
                return List.of();
            }
            List<String> codes = new ArrayList<>(triggers);
            // Explicitly askable, because "which requests never had a reason recorded" is a real
            // question and a blank column is not a way to ask it.
            boolean unstated = codes.remove("none");
            if (codes.isEmpty()) {
                sql.append(" AND trigger_id IS NULL");
            } else if (unstated) {
                sql.append(" AND (trigger_code = ANY (?) OR trigger_id IS NULL)");
                parameters.add(codes.toArray(new String[0]));
            } else {
                sql.append(" AND trigger_code = ANY (?)");
                parameters.add(codes.toArray(new String[0]));
            }
        }
        // ------------------------------------------------------------------------------------------
        // Date ranges. Inclusive of both ends, and the upper bound is compared against the START of
        // the following day so that a request due at 17:00 on the chosen date is inside the range.
        // Comparing a timestamptz against a bare date excludes everything after midnight, which reads
        // as "the filter loses the last day" and is the classic form of this defect.
        // ------------------------------------------------------------------------------------------
        String dueFrom = filters.get("dueFrom");
        if (dueFrom != null && !dueFrom.isBlank()) {
            sql.append(" AND due_at >= ?::date");
            parameters.add(dueFrom);
        }
        String dueTo = filters.get("dueTo");
        if (dueTo != null && !dueTo.isBlank()) {
            sql.append(" AND due_at < (?::date + interval '1 day')");
            parameters.add(dueTo);
        }
        // Separate from the range on purpose. A request with no deadline is excluded by any range
        // comparison, and "show me the ones nobody put a date on" is the question that finds them.
        if ("none".equals(filters.get("due"))) {
            sql.append(" AND due_at IS NULL");
        }
        String createdFrom = filters.get("createdFrom");
        if (createdFrom != null && !createdFrom.isBlank()) {
            sql.append(" AND created_at >= ?::date");
            parameters.add(createdFrom);
        }
        String createdTo = filters.get("createdTo");
        if (createdTo != null && !createdTo.isBlank()) {
            sql.append(" AND created_at < (?::date + interval '1 day')");
            parameters.add(createdTo);
        }
        String category = filters.get("category");
        if (category != null && !category.isBlank()) {
            sql.append(" AND state_category = ?");
            parameters.add(category);
        }
        if ("overdue".equals(filters.get("only"))) {
            // state_category, not a LIKE on the code: see Request#terminal.
            sql.append(" AND due_at < now() AND coalesce(state_category, '') <> 'TERMINAL'");
        }
        if ("fullreview".equals(filters.get("only"))) {
            sql.append(" AND trigger_is_full_review");
        }
        if (search != null && !search.isBlank()) {
            sql.append(" AND (title ILIKE ? OR request_code ILIKE ?)");
            String pattern = "%" + search.strip() + "%";
            parameters.add(pattern);
            parameters.add(pattern);
        }
        // Deadline first by default. A board sorted by code is a board nobody plans from, and NULLS
        // LAST keeps requests with no deadline from sorting to the top of "most urgent".
        String order = switch (sort == null ? "" : sort) {
            case "created" -> "created_at DESC";
            case "state" -> "state ASC, due_at ASC";
            case "findings" -> "finding_open DESC";
            case "title" -> "title ASC";
            default -> "due_at ASC";
        };
        sql.append(" ORDER BY ").append(order).append(" NULLS LAST, request_code ASC");

        List<Request> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < parameters.size(); i++) {
                Object value = parameters.get(i);
                if (value instanceof UUID[] array) {
                    statement.setArray(i + 1, connection.createArrayOf("uuid", array));
                } else if (value instanceof String[] array) {
                    statement.setArray(i + 1, connection.createArrayOf("text", array));
                } else {
                    statement.setObject(i + 1, value);
                }
            }
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    java.sql.Array ancestors = r.getArray(10);
                    rows.add(new Request(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                            r.getString(4), date(r.getObject(5)), date(r.getObject(6)),
                            r.getBoolean(7), r.getObject(8, UUID.class), r.getString(9),
                            ancestors == null ? List.of() : List.of((String[]) ancestors.getArray()),
                            r.getObject(11, UUID.class), r.getObject(12, UUID.class),
                            r.getObject(13, UUID.class), r.getLong(14), r.getString(15),
                            r.getLong(16), r.getLong(17), r.getLong(18), r.getLong(19),
                            r.getObject(20, UUID.class), r.getString(21), r.getString(22),
                            r.getBoolean(23),
                            date(r.getObject(24)), r.getString(25)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * A comma-separated filter value, as a list.
     *
     * <p>Three states, and keeping them apart is the whole point:
     *
     * <ul>
     *   <li>{@code null} — the parameter was absent. The filter does not apply and everything matches.
     *   <li>an empty list — the parameter was present and selected nothing. <b>Nothing</b> matches.
     *   <li>a non-empty list — those values match.
     * </ul>
     *
     * <p>The middle case is why this returns {@code null} rather than an empty list for an absent
     * parameter. Collapsing "no filter" and "filter matching nothing" into one value is how a
     * multi-select with everything unticked ends up showing the unfiltered board — which on this
     * screen means showing a person requests from organisations they were trying to exclude.
     */
    private static List<String> list(String raw) {
        if (raw == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",", -1)) {
            String value = part.strip();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    /** As {@link #list}, parsed as identifiers. An unparseable one narrows rather than widens. */
    private static List<UUID> uuids(String raw) {
        List<String> values = list(raw);
        if (values == null) {
            return null;
        }
        List<UUID> out = new ArrayList<>();
        for (String value : values) {
            try {
                out.add(UUID.fromString(value));
            } catch (IllegalArgumentException e) {
                // Dropped, not ignored: the caller sees a shorter list, and a list that becomes empty
                // matches nothing. A malformed identifier must never widen the result set.
            }
        }
        return out;
    }

    /**
     * The option lists the board's filters are built from, each scoped to the caller.
     *
     * <p>Derived from what the caller can reach, not from the rows currently on screen. A filter whose
     * options come from the visible page cannot be used to widen a selection — once you have narrowed
     * to one organisation, the others vanish from the picker and there is no way back except clearing
     * everything. The lists are also the same scoped queries authorization uses, so the picker and the
     * control cannot drift apart (product principle 4).
     */
    public Map<String, List<Map<String, String>>> boardFilterOptions(Principal principal)
            throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return Map.of("organizations", List.of(), "projects", List.of(),
                    "applications", List.of(), "assessors", List.of());
        }
        Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
        try (Connection connection = open(principal)) {
            out.put("organizations", options(connection, scope,
                    "SELECT n.id::text, n.name, coalesce(t.code, '') "
                            + "  FROM org_node n "
                            + "  LEFT JOIN org_node_type t ON t.id = n.type_id "
                            + " WHERE n.lifecycle_state = 'ACTIVE' AND n.id IN "
                            + "       (SELECT descendant_id FROM org_closure "
                            + "         WHERE ancestor_id = ANY (?)) "
                            + " ORDER BY n.name"));
            out.put("projects", options(connection, scope,
                    "SELECT a.id::text, a.display_name, coalesce(n.name, '') "
                            + "  FROM asset a "
                            + "  JOIN asset_type t ON t.id = a.type_id AND t.code = 'PROJECT' "
                            + "  LEFT JOIN org_node n ON n.id = a.owning_node_id "
                            + " WHERE a.lifecycle_state <> 'RETIRED' AND a.owning_node_id IN "
                            + "       (SELECT descendant_id FROM org_closure "
                            + "         WHERE ancestor_id = ANY (?)) "
                            + " ORDER BY a.display_name"));
            out.put("applications", options(connection, scope,
                    "SELECT a.id::text, a.display_name, coalesce(n.name, '') "
                            + "  FROM asset a "
                            + "  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION' "
                            + "  LEFT JOIN org_node n ON n.id = a.owning_node_id "
                            + " WHERE a.lifecycle_state <> 'RETIRED' AND a.owning_node_id IN "
                            + "       (SELECT descendant_id FROM org_closure "
                            + "         WHERE ancestor_id = ANY (?)) "
                            + " ORDER BY a.display_name"));
            // Whoever has actually led an assessment in this scope, plus nobody. Not every principal
            // holding the permission: a list of two hundred names, of whom eight ever led anything,
            // is a list somebody has to search rather than read.
            out.put("assessors", options(connection, scope,
                    "SELECT DISTINCT b.lead_principal_id::text, "
                            + "       coalesce(p.display_name, p.username, 'unknown'), '' "
                            + "  FROM request_board b "
                            + "  JOIN principal p ON p.id = b.lead_principal_id "
                            + " WHERE b.lead_principal_id IS NOT NULL "
                            + "   AND b.requested_org_node_id IN "
                            + "       (SELECT descendant_id FROM org_closure "
                            + "         WHERE ancestor_id = ANY (?)) "
                            + " ORDER BY 2"));
        }
        return Map.copyOf(out);
    }

    private static List<Map<String, String>> options(Connection connection, Set<UUID> scope,
            String sql) throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("id", r.getString(1));
                    entry.put("name", r.getString(2));
                    // A second line, so two things with the same display name are distinguishable.
                    // Without it a picker with two "Portal" entries is a picker somebody guesses at.
                    entry.put("hint", r.getString(3));
                    rows.add(entry);
                }
            }
        }
        return List.copyOf(rows);
    }

    /** One request, re-validated against the caller's scope. {@code SEC-AUZ-017}. */
    public Optional<Request> request(Principal principal, UUID id) throws SQLException {
        return board(principal, Map.of(), null, null).stream()
                .filter(r -> r.id().equals(id)).findFirst();
    }

    /** The findings recorded in one request. */
    public List<Finding> findings(Principal principal, UUID requestId) throws SQLException {
        List<Finding> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT f.id, f.title, s.code, s.ordinal, f.state, f.closure_reason, "
                                + "       f.finding_class, f.assessment_context, f.first_detected_at, "
                                + "       f.last_detected_at, f.description, f.proof_of_concept, "
                                + "       f.source_tool, f.assignee_id, x.expires_at, "
                                + "       (SELECT a.display_name FROM finding_asset_impact i "
                                + "          JOIN asset a ON a.id = i.asset_id "
                                + "         WHERE i.finding_id = f.id LIMIT 1), f.row_version, "
                                + "       f.remediation_claimed_at, "
                                + "       (SELECT p.display_name FROM principal p "
                                + "         WHERE p.id = f.remediation_claimed_by), "
                                + "       f.remediation_note "
                                + "  FROM finding f "
                                + "  LEFT JOIN severity_level s ON s.id = f.effective_severity_id "
                                + "  LEFT JOIN risk_exception x ON x.id = f.accepted_under_exception_id "
                                + " WHERE f.discovered_in_request_id = ? "
                                // Worst first. A list of findings ordered by insertion is a list
                                // somebody reads top to bottom and stops halfway down.
                                + " ORDER BY s.ordinal NULLS LAST, f.first_detected_at")) {
            statement.setObject(1, requestId);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(readFinding(r));
                }
            }
        }
        return List.copyOf(rows);
    }

    /** One finding, reached through the request that produced it. */
    public Optional<Finding> finding(Principal principal, UUID requestId, UUID findingId)
            throws SQLException {
        return findings(principal, requestId).stream()
                .filter(f -> f.id().equals(findingId)).findFirst();
    }

    /**
     * Records a finding found during an engagement.
     *
     * <p>{@code first_detected_at} and {@code last_detected_at} are set to now and are not asked for.
     * The user asked for the detection time to be filled automatically, and the reason it is right is
     * stronger than convenience: a hand-entered detection date is a date somebody guesses months
     * later when writing the report, and every service level clock in the platform starts from it.
     *
     * <p>The fingerprint is a digest of the identifying parts. ADR-011 runs one normalization and
     * deduplication pipeline for imports and native entry alike; a manual finding that skipped it
     * would be a duplicate the pipeline cannot see.
     */
    public UUID recordFinding(Principal principal, UUID requestId, UUID assetId, String title,
            UUID severityId, String findingClass, String context, String description,
            String proofOfConcept) throws SQLException {
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                byte[] fingerprint = fingerprint(requestId, title, assetId);
                UUID id;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO finding (tenant_id, fingerprint_digest, "
                                + "fingerprint_algorithm_version, finding_class, title, description, "
                                + "proof_of_concept, effective_severity_id, effective_severity_by, "
                                + "effective_severity_at, state, source_tool, raw_source_record_ref, "
                                + "assessment_context, discovered_in_request_id, first_detected_at, "
                                + "last_detected_at, created_by, updated_by) "
                                + "VALUES (current_tenant_id(), ?, 1, ?, ?, ?, ?, ?, ?, now(), "
                                + "        'OPEN', 'manual-entry', ?, ?, ?, now(), now(), ?, ?) "
                                + "RETURNING id")) {
                    insert.setBytes(1, fingerprint);
                    insert.setString(2, findingClass);
                    insert.setString(3, title.strip());
                    insert.setString(4, description == null ? null : description.strip());
                    insert.setString(5, proofOfConcept == null ? null : proofOfConcept.strip());
                    insert.setObject(6, severityId);
                    insert.setObject(7, principal == null ? null : principal.principalId());
                    insert.setString(8, "ui://request/" + requestId);
                    insert.setString(9, context == null || context.isBlank()
                            ? DEFAULT_CONTEXT : context);
                    insert.setObject(10, requestId);
                    insert.setObject(11, principal == null ? null : principal.principalId());
                    insert.setObject(12, principal == null ? null : principal.principalId());
                    try (ResultSet keys = insert.executeQuery()) {
                        keys.next();
                        id = keys.getObject(1, UUID.class);
                    }
                }
                if (assetId != null) {
                    try (PreparedStatement impact = connection.prepareStatement(
                            "INSERT INTO finding_asset_impact (tenant_id, finding_id, asset_id, "
                                    + "first_detected_at, last_detected_at, created_by) "
                                    + "VALUES (current_tenant_id(), ?, ?, now(), now(), ?)")) {
                        impact.setObject(1, id);
                        impact.setObject(2, assetId);
                        impact.setObject(3, principal == null ? null : principal.principalId());
                        impact.executeUpdate();
                    }
                }
                audit.domainChange(connection, principal, "finding",
                        aspm.kernel.audit.contract.DomainChangeKind.CREATED, id,
                        aspm.app.audit.AuditScopes.ofFinding(connection, id),
                        java.util.Map.of("title", title.strip(),
                                "finding_class", findingClass,
                                "request_id", requestId.toString(),
                                "asset_id", assetId == null ? "" : assetId.toString(),
                                "source", "manual-entry"));
                connection.commit();
                return id;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** Amends the write-up. The detection timestamps are never rewritten from the form. */
    public boolean updateFinding(Principal principal, UUID findingId, String title,
            UUID severityId, String context, String description, String proofOfConcept,
            int rowVersion) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE finding SET title = ?, effective_severity_id = ?, "
                                + "effective_severity_by = ?, effective_severity_at = now(), "
                                + "assessment_context = ?, description = ?, proof_of_concept = ?, "
                                + "updated_at = now(), updated_by = ?, row_version = row_version + 1 "
                                + " WHERE id = ? AND row_version = ?")) {
            statement.setString(1, title.strip());
            statement.setObject(2, severityId);
            statement.setObject(3, principal == null ? null : principal.principalId());
            statement.setString(4, context);
            statement.setString(5, description);
            statement.setString(6, proofOfConcept);
            statement.setObject(7, principal == null ? null : principal.principalId());
            statement.setObject(8, findingId);
            statement.setInt(9, rowVersion);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                // Only where the update matched. An event for an optimistic-lock failure would say a
                // finding changed that did not, and the caller is about to be told the same.
                audit.domainChange(connection, principal, "finding",
                        aspm.kernel.audit.contract.DomainChangeKind.UPDATED, findingId,
                        aspm.app.audit.AuditScopes.ofFinding(connection, findingId),
                        java.util.Map.of("title", title.strip(),
                                "severity_id", severityId == null ? "" : severityId.toString(),
                                "row_version_seen", Integer.valueOf(rowVersion)));
            }
            connection.commit();
            return applied;
        }
    }

    /**
     * Requests a risk acceptance with a deadline, and closes the finding under it.
     *
     * <p>The exception is created {@code REQUESTED}, not {@code ACTIVE}. Approval is a separate act by
     * a different person — {@code INV-VUL-26} refuses an exception approved by its own requester,
     * because self-approval makes the process a formality and DOC-07 §15.1 records it as the first
     * control an auditor tests.
     *
     * <p>So this closes the finding as {@code RISK_ACCEPTED} against a PENDING exception. That is
     * deliberate and visible: the interface shows the acceptance as awaiting approval, which is a
     * truthful state, rather than pretending an approval happened.
     */
    public boolean acceptRisk(Principal principal, UUID findingId, String findingClass,
            java.time.LocalDate until, int rowVersion) throws SQLException {
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                UUID exception;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO risk_exception (tenant_id, subject_kind, subject_id, "
                                + "subject_finding_class, state, requested_by, requested_at, "
                                + "expires_at, max_duration_days) "
                                + "VALUES (current_tenant_id(), 'FINDING', ?, ?, 'REQUESTED', ?, "
                                + "        now(), ?, ?) RETURNING id")) {
                    insert.setObject(1, findingId);
                    insert.setString(2, findingClass);
                    insert.setObject(3, principal == null ? null : principal.principalId());
                    insert.setObject(4, until.atStartOfDay(java.time.ZoneOffset.UTC)
                            .toOffsetDateTime());
                    insert.setInt(5, (int) Math.max(1,
                            java.time.temporal.ChronoUnit.DAYS.between(
                                    java.time.LocalDate.now(java.time.ZoneOffset.UTC), until)));
                    try (ResultSet keys = insert.executeQuery()) {
                        keys.next();
                        exception = keys.getObject(1, UUID.class);
                    }
                }
                try (PreparedStatement close = connection.prepareStatement(
                        "UPDATE finding SET state = 'CLOSED', closure_reason = 'RISK_ACCEPTED', "
                                + "closed_at = now(), accepted_under_exception_id = ?, "
                                + "updated_at = now(), updated_by = ?, "
                                + "row_version = row_version + 1 "
                                + " WHERE id = ? AND row_version = ?")) {
                    close.setObject(1, exception);
                    close.setObject(2, principal == null ? null : principal.principalId());
                    close.setObject(3, findingId);
                    close.setInt(4, rowVersion);
                    if (close.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                // Two events, because two things happened and an access review asks about them
                // separately: an exception was requested (by whom, until when) and a finding left the
                // open population under it.
                audit.domainChange(connection, principal, "risk_exception",
                        aspm.kernel.audit.contract.DomainChangeKind.CREATED, exception,
                        aspm.app.audit.AuditScopes.ofFinding(connection, findingId),
                        java.util.Map.of("subject_kind", "FINDING",
                                "subject_id", findingId.toString(),
                                "state", "REQUESTED",
                                "expires_at", until.toString()));
                audit.domainChange(connection, principal, "finding",
                        aspm.kernel.audit.contract.DomainChangeKind.TRANSITIONED, findingId,
                        aspm.app.audit.AuditScopes.ofFinding(connection, findingId),
                        java.util.Map.of("to_state", "CLOSED",
                                "closure_reason", "RISK_ACCEPTED",
                                "accepted_under_exception_id", exception.toString()));
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** Reopens a finding. The exception it was accepted under is withdrawn with it. */
    public boolean reopen(Principal principal, UUID findingId, int rowVersion) throws SQLException {
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement withdraw = connection.prepareStatement(
                        "UPDATE risk_exception SET state = 'WITHDRAWN', resolved_at = now(), "
                                + "resolution_reason = 'the finding was reopened' "
                                + " WHERE id = (SELECT accepted_under_exception_id FROM finding "
                                + "              WHERE id = ?) AND resolved_at IS NULL")) {
                    withdraw.setObject(1, findingId);
                    withdraw.executeUpdate();
                }
                try (PreparedStatement reopen = connection.prepareStatement(
                        "UPDATE finding SET state = 'OPEN', closure_reason = NULL, closed_at = NULL, "
                                + "accepted_under_exception_id = NULL, updated_at = now(), "
                                + "updated_by = ?, row_version = row_version + 1 "
                                + " WHERE id = ? AND row_version = ?")) {
                    reopen.setObject(1, principal == null ? null : principal.principalId());
                    reopen.setObject(2, findingId);
                    reopen.setInt(3, rowVersion);
                    if (reopen.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                audit.domainChange(connection, principal, "finding",
                        aspm.kernel.audit.contract.DomainChangeKind.REOPENED, findingId,
                        aspm.app.audit.AuditScopes.ofFinding(connection, findingId),
                        java.util.Map.of("row_version_seen", Integer.valueOf(rowVersion),
                                "risk_exception", "withdrawn where one was in force"));
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }


    /** Closure outcomes a person can choose for a finding. DOC-06 §closure. */
    public static final List<String> CLOSURES = List.of("FIXED_VERIFIED", "FALSE_POSITIVE",
            "NOT_APPLICABLE", "DUPLICATE");

    /**
     * Closes a finding with a stated outcome.
     *
     * <p>{@code FIXED_VERIFIED} additionally records WHO verified it and HOW —
     * {@code ck_finding__verified_closure} refuses the value without both, because "fixed" asserted
     * by the person who wrote the fix and checked by nobody is the closure an auditor opens first.
     * Risk acceptance is deliberately NOT in this list: it goes through {@link #acceptRisk}, which
     * creates an exception with an expiry, an approver and a state machine.
     */
    public boolean closeFinding(Principal principal, UUID findingId, String reason, String method,
            int rowVersion) throws SQLException {
        if (!CLOSURES.contains(reason)) {
            return false;
        }
        boolean verified = "FIXED_VERIFIED".equals(reason);
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE finding SET state = 'CLOSED', closure_reason = ?, closed_at = now(), "
                                + "closure_verified_by = ?, closure_verification_method = ?, "
                                + "updated_at = now(), updated_by = ?, row_version = row_version + 1 "
                                + " WHERE id = ? AND row_version = ? AND state = 'OPEN'"
                                // *** THE VERIFIER MAY NOT BE THE CLAIMANT. ***
                                //
                                // DOC-09 §7's finding lifecycle puts this guard on the `verified`
                                // transition and the implementation did not have it, so whoever
                                // claimed a fix could also close it as verified. That is the whole
                                // separation V032 exists to create, undone at the last step.
                                //
                                // Only for FIXED_VERIFIED: a false positive or a retired asset is
                                // not a fix and has no claimant to be separate from.
                                + (verified
                                        ? "   AND (remediation_claimed_by IS NULL "
                                                + "        OR remediation_claimed_by <> ?)"
                                        : ""))) {
            statement.setString(1, reason);
            statement.setObject(2, verified
                    ? (principal == null ? null : principal.principalId()) : null);
            statement.setString(3, verified
                    ? (method == null || method.isBlank() ? "RETEST" : method.strip()) : null);
            statement.setObject(4, principal == null ? null : principal.principalId());
            statement.setObject(5, findingId);
            statement.setInt(6, rowVersion);
            if (verified) {
                statement.setObject(7, principal == null ? null : principal.principalId());
            }
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.domainChange(connection, principal, "finding",
                        aspm.kernel.audit.contract.DomainChangeKind.TRANSITIONED, findingId,
                        aspm.app.audit.AuditScopes.ofFinding(connection, findingId),
                        java.util.Map.of("to_state", "CLOSED",
                                "closure_reason", reason,
                                "verification_method", verified
                                        ? (method == null || method.isBlank()
                                                ? "RETEST" : method.strip())
                                        : ""));
            }
            connection.commit();
            return applied;
        }
    }

    /** Assigns a finding to somebody. Null clears it. */
    public boolean assignFinding(Principal principal, UUID findingId, UUID assignee, int rowVersion)
            throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE finding SET assignee_id = ?, updated_at = now(), updated_by = ?, "
                                + "row_version = row_version + 1 WHERE id = ? AND row_version = ?")) {
            statement.setObject(1, assignee);
            statement.setObject(2, principal == null ? null : principal.principalId());
            statement.setObject(3, findingId);
            statement.setInt(4, rowVersion);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.domainChange(connection, principal, "finding",
                        aspm.kernel.audit.contract.DomainChangeKind.ASSIGNED, findingId,
                        aspm.app.audit.AuditScopes.ofFinding(connection, findingId),
                        // The empty string, not a null: an assignment cleared is a decision somebody
                        // made, and a payload key that vanishes reads as a field nobody touched.
                        java.util.Map.of("assignee_id",
                                assignee == null ? "" : assignee.toString()));
            }
            connection.commit();
            return applied;
        }
    }

    /**
     * Sets the people on a request: the dev contact, the assessor, and the deadline.
     *
     * <p>The assessor lives on the ASSESSMENT, not on the request — that is where the platform
     * records who is doing the work, and a request may have an original assessment and a retest with
     * different leads. So naming an assessor creates the assessment if the request has none, which is
     * also the act that moves a request from "asked for" to "being worked on".
     */
    public boolean assignRequest(Principal principal, UUID requestId, UUID contact, UUID assessor,
            java.time.LocalDate due) throws SQLException {
        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE assessment_request SET requester_contact_id = ?, due_at = ?, "
                                + "updated_at = now(), updated_by = ?, row_version = row_version + 1 "
                                + " WHERE id = ?")) {
                    update.setObject(1, contact);
                    update.setObject(2, due == null ? null
                            : due.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime());
                    update.setObject(3, principal == null ? null : principal.principalId());
                    update.setObject(4, requestId);
                    update.executeUpdate();
                }
                if (assessor != null) {
                    UUID existing = null;
                    try (PreparedStatement find = connection.prepareStatement(
                            "SELECT id FROM assessment WHERE request_id = ? "
                                    + "ORDER BY created_at DESC LIMIT 1")) {
                        find.setObject(1, requestId);
                        try (ResultSet r = find.executeQuery()) {
                            if (r.next()) {
                                existing = r.getObject(1, UUID.class);
                            }
                        }
                    }
                    if (existing == null) {
                        // The scope descriptors are copied from the request. INV-ASM-10: an
                        // assessment's scope comes from what was asked for, never from the assessor.
                        try (PreparedStatement insert = connection.prepareStatement(
                                "INSERT INTO assessment (tenant_id, type_id, request_id, state, "
                                        + "lead_principal_id, started_at, scope_node_id, "
                                        + "scope_ancestor_path, scope_node_type_id, "
                                        + "scope_criticality_id, scope_hierarchy_ver, "
                                        + "scope_resolved_at) "
                                        + "SELECT r.tenant_id, r.type_id, r.id, 'IN_PROGRESS', ?, "
                                        + "       now(), r.requested_org_node_id, "
                                        + "       r.scope_ancestor_path, r.scope_node_type_id, "
                                        + "       r.scope_criticality_id, r.scope_hierarchy_ver, "
                                        + "       now() FROM assessment_request r WHERE r.id = ?")) {
                            insert.setObject(1, assessor);
                            insert.setObject(2, requestId);
                            insert.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement lead = connection.prepareStatement(
                                "UPDATE assessment SET lead_principal_id = ?, updated_at = now(), "
                                        + "row_version = row_version + 1 WHERE id = ?")) {
                            lead.setObject(1, assessor);
                            lead.setObject(2, existing);
                            lead.executeUpdate();
                        }
                    }
                }
                audit.domainChange(connection, principal, "assessment_request",
                        aspm.kernel.audit.contract.DomainChangeKind.ASSIGNED, requestId,
                        aspm.app.audit.AuditScopes.ofRequest(connection, requestId),
                        java.util.Map.of("development_contact_id", contact == null
                                        ? "" : contact.toString(),
                                "assessor_id", assessor == null ? "" : assessor.toString()));
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** People who can be named on a request or a finding. */
    public List<Map<String, String>> assignableprincipals(Principal principal) throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, display_name, username FROM principal "
                                + " WHERE kind = 'HUMAN' AND lifecycle_state = 'ACTIVE' "
                                + " ORDER BY display_name");
                ResultSet r = statement.executeQuery()) {
            while (r.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", r.getObject(1, UUID.class).toString());
                row.put("name", r.getString(2) + "  ·  " + r.getString(3));
                rows.add(row);
            }
        }
        return List.copyOf(rows);
    }

    // ----------------------------------------------------------------------------------------------

    /** Comments on a request or a finding, oldest first. */
    public List<Comment> comments(Principal principal, String subjectKind, UUID subjectId)
            throws SQLException {
        List<Comment> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        // body is jsonb: V009 models a comment as a structured document
                        // (ASPM_RICH_TEXT_V1), not a string. The Markdown body lives under a `text`
                        // key, so a future structured editor can add blocks beside it without every
                        // reader changing — which is the point of the document shape.
                        "SELECT c.id, c.author_id, p.display_name, c.body ->> 'text', c.created_at, "
                                + "       c.edit_count, c.is_redacted "
                                + "  FROM comment c "
                                + "  LEFT JOIN principal p ON p.id = c.author_id "
                                + " WHERE c.subject_kind = ? AND c.subject_id = ? "
                                + " ORDER BY c.created_at")) {
            statement.setString(1, subjectKind);
            statement.setObject(2, subjectId);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    boolean redacted = r.getBoolean(7);
                    rows.add(new Comment(r.getObject(1, UUID.class), r.getObject(2, UUID.class),
                            r.getString(3),
                            // A redacted comment keeps its row and loses its body. The record that
                            // somebody said something at a time stays; DOC-14 makes the audit trail
                            // inviolable and deleting the row would erase the fact of the exchange.
                            redacted ? null : r.getString(4),
                            // The whole instant, not the date. A comment thread is a conversation
                            // and "2026-08-06" three times over says nothing about the order or the
                            // gaps in it — which is the question anybody re-reading a thread has.
                            instant(r.getObject(5, java.time.OffsetDateTime.class)),
                            r.getInt(6), redacted));
                }
            }
        }
        return List.copyOf(rows);
    }

    public UUID addComment(Principal principal, String subjectKind, UUID subjectId, String body)
            throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO comment (tenant_id, subject_kind, subject_id, body, "
                                + "body_format, author_id) "
                                + "VALUES (current_tenant_id(), ?, ?, ?::jsonb, "
                                + "        'MARKDOWN_RESTRICTED', ?) RETURNING id")) {
            statement.setString(1, subjectKind);
            statement.setObject(2, subjectId);
            // Built with the JSON writer, never by concatenation: the body is user text that will
            // contain quotes and backslashes by design — it is a payload somebody is quoting.
            statement.setString(3, aspm.app.runtime.Json.write(
                    Map.of("format", "MARKDOWN_RESTRICTED", "text", body.strip())));
            statement.setObject(4, principal == null ? null : principal.principalId());
            UUID id;
            try (ResultSet keys = statement.executeQuery()) {
                keys.next();
                id = keys.getObject(1, UUID.class);
            }
            // The comment body is NOT in the payload. It is user text that legitimately contains
            // attacker-authored material (the fifth surface in CLAUDE.md), and a trail is read by
            // tools that were not written to treat it as hostile. What is recorded is that somebody
            // commented, on what, and when — which is what an investigation asks.
            audit.domainChange(connection, principal, "comment",
                    aspm.kernel.audit.contract.DomainChangeKind.CREATED, id,
                    "FINDING".equals(subjectKind)
                            ? aspm.app.audit.AuditScopes.ofFinding(connection, subjectId)
                            : aspm.app.audit.AuditScopes.ofRequest(connection, subjectId),
                    java.util.Map.of("subject_kind", subjectKind,
                            "subject_id", subjectId.toString(),
                            "body_characters", Integer.valueOf(body.strip().length())));
            // Read first, then commit: a commit closes the result set the identifier comes from.
            connection.commit();
            return id;
        }
    }

    /** Severity levels, tenant-configured. */
    public List<Map<String, String>> severities(Principal principal) throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, code FROM severity_level WHERE lifecycle_state = 'ACTIVE' "
                                + "ORDER BY ordinal");
                ResultSet r = statement.executeQuery()) {
            while (r.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", r.getObject(1, UUID.class).toString());
                row.put("code", r.getString(2));
                rows.add(row);
            }
        }
        return List.copyOf(rows);
    }

    /** Applications in a request's scope, for the finding form's asset picker. */
    public List<Map<String, String>> scopeAssets(Principal principal, UUID requestId)
            throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT DISTINCT a.id, a.display_name, t.code "
                                + "  FROM assessment_scope_asset sa "
                                + "  JOIN assessment ex ON ex.id = sa.assessment_id "
                                + "  JOIN asset a ON a.id = sa.asset_id "
                                + "  JOIN asset_type t ON t.id = a.type_id "
                                + " WHERE ex.request_id = ? "
                                + " UNION "
                                // The parts of those applications, so a finding can be attached to the
                                // service it is actually in rather than to the application as a whole.
                                + "SELECT c.asset_id, c.display_name, c.type_code "
                                + "  FROM assessment_scope_asset sa "
                                + "  JOIN assessment ex ON ex.id = sa.assessment_id "
                                + "  JOIN asset_composition c ON c.root_id = sa.asset_id "
                                + " WHERE ex.request_id = ? "
                                + " ORDER BY 2")) {
            statement.setObject(1, requestId);
            statement.setObject(2, requestId);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("id", r.getObject(1, UUID.class).toString());
                    row.put("name", r.getString(2));
                    row.put("type", r.getString(3));
                    rows.add(row);
                }
            }
        }
        return List.copyOf(rows);
    }

    /** Display names for a set of principals, for the PIC columns. */
    public Map<UUID, String> principalNames(Principal principal, Set<UUID> ids) throws SQLException {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, display_name FROM principal WHERE id = ANY (?)")) {
            statement.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    names.put(r.getObject(1, UUID.class), r.getString(2));
                }
            }
        }
        return Map.copyOf(names);
    }

    // ----------------------------------------------------------------------------------------------

    private static Finding readFinding(ResultSet r) throws SQLException {
        return new Finding(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                r.getObject(4) == null ? null : String.valueOf(r.getObject(4)), r.getString(5),
                r.getString(6), r.getString(7), r.getString(8), date(r.getObject(9)),
                date(r.getObject(10)), r.getString(11), r.getString(12), r.getString(13),
                r.getObject(14, UUID.class), date(r.getObject(15)), r.getString(16), r.getInt(17),
                instant(r.getObject(18, java.time.OffsetDateTime.class)), r.getString(19),
                r.getString(20));
    }

    /**
     * Records that the delivery team believes a finding is fixed. {@code V032}.
     *
     * <p><b>The finding stays OPEN.</b> This is a claim, not a closure: the party being assessed does
     * not get to close its own finding, and {@code FIXED_VERIFIED} stays the assessor's word after a
     * retest. What this changes is that the finding joins the retest queue and stops looking like one
     * nobody has touched.
     *
     * <p>Withdrawable by passing a null note with {@code claimed} false — a developer who claimed the
     * wrong finding has to be able to say so, and a claim nobody can retract is one people stop
     * making.
     *
     * <p>Authority is decided by {@link aspm.app.authz.ObjectAuthority#mayClaimRemediation} before
     * this is called. It is not re-tested here, because two tests in two places is how they come to
     * disagree — but the update is still bounded to a finding of the named request, so a participant
     * on one engagement cannot reach a finding on another.
     */
    public boolean claimRemediation(Principal principal, UUID requestId, UUID findingId,
            boolean claimed, String note) throws SQLException {
        // `lifecycle_state` is written alongside the claim columns, and this is a correction.
        //
        // THE DEFECT. This method wrote only the claim columns. Once the finding lifecycle acquired its
        // own column, pressing "Mark as fixed" set `remediation_claimed_at` and left `lifecycle_state`
        // reading OPEN — so the status on screen did not move, which is exactly how the defect was
        // reported: "I click report as fixed and nothing changes". The record was split between two
        // columns describing the same fact, and the one everything displays was the stale one.
        //
        // The UI control this served has been removed in favour of the single Status picker, which goes
        // through FindingLifecycle. This path stays because the endpoint remains part of the API, and a
        // remaining caller must not be able to create the split record again.
        //
        // `state` is deliberately untouched: a claimed fix is still open work (PP-1), and
        // ck_finding__axes_agree requires FIXED to sit on the OPEN side.
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        claimed
                                ? "UPDATE finding SET remediation_claimed_at = now(), "
                                        + "  remediation_claimed_by = ?, remediation_note = ?, "
                                        + "  lifecycle_state = 'FIXED', "
                                        + "  updated_at = now(), updated_by = ?, "
                                        + "  row_version = row_version + 1 "
                                        + " WHERE id = ? AND discovered_in_request_id = ? "
                                        + "   AND state = 'OPEN' "
                                        // Only from the two states a claim can be made in. Claiming
                                        // over a pending risk acceptance would silently drop the
                                        // request somebody is waiting on.
                                        + "   AND lifecycle_state IN ('OPEN', 'REOPEN')"
                                : "UPDATE finding SET remediation_claimed_at = NULL, "
                                        + "  remediation_claimed_by = NULL, remediation_note = NULL, "
                                        + "  lifecycle_state = 'OPEN', "
                                        + "  updated_at = now(), updated_by = ?, "
                                        + "  row_version = row_version + 1 "
                                        + " WHERE id = ? AND discovered_in_request_id = ? "
                                        + "   AND lifecycle_state = 'FIXED'")) {
            int index = 1;
            if (claimed) {
                statement.setObject(index++, principal.principalId());
                statement.setString(index++, note == null || note.isBlank() ? null : note.strip());
            }
            statement.setObject(index++, principal.principalId());
            statement.setObject(index++, findingId);
            statement.setObject(index, requestId);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.domainChange(connection, principal, "finding",
                        aspm.kernel.audit.contract.DomainChangeKind.TRANSITIONED, findingId,
                        aspm.app.audit.AuditScopes.ofFinding(connection, findingId),
                        java.util.Map.of("lifecycle_state", claimed ? "FIXED" : "OPEN",
                                "claimed", Boolean.valueOf(claimed),
                                "request_id", requestId.toString()));
            }
            connection.commit();
            return applied;
        }
    }

    /**
     * The identity digest for a manually recorded finding.
     *
     * <p>ADR-011 runs ONE normalization and deduplication pipeline for file import and native entry
     * alike. A manual finding with a random digest would be invisible to it — every re-entry of the
     * same issue during a retest would be a new finding, and the recurrence count that tells a team
     * they keep reintroducing something would never move.
     */
    private static byte[] fingerprint(UUID requestId, String title, UUID assetId) {
        String material = "manual|" + requestId + "|"
                + title.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ")
                + "|" + assetId;
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every supported JDK", e);
        }
    }

    // ==============================================================================================
    // Why a request exists, and the periodic obligation that follows from it
    // ==============================================================================================

    /** One configured reason a request can be raised. */
    public record Trigger(UUID id, String code, String label, String guidance,
            boolean countsAsFullReview) {
    }

    /** One completed or in-flight whole-application review. */
    public record FullReview(UUID requestId, String code, String title, String state,
            String triggerLabel, String startedAt, boolean startedAtIsIntakeDate, String closedAt,
            String dueAt, String stateCategory, String terminalDisposition, String abandonedAt) {

        /** Still running: not finished, and not stopped. */
        public boolean open() {
            return !"TERMINAL".equals(stateCategory);
        }

        /**
         * Stopped without the work being done.
         *
         * <p>Kept visible rather than filtered out of the history. A run of cancelled annual reviews
         * is itself the finding, and a coverage panel that simply omitted them would show the same
         * empty history as an application nobody ever raised a review for.
         */
        public boolean abandoned() {
            return "ABANDONED".equals(terminalDisposition);
        }
    }

    /** Where an application stands against its review obligation, computed by the database. */
    public record Cadence(long completed, long inFlight, long abandoned, String lastAt,
            Integer intervalMonths, String nextDueAt, String status) {
    }

    /**
     * State code to the label the tenant configured, for the ACTIVE workflow.
     *
     * <p>Read once per page rather than per row. The codes are what the API, the audit trail and the
     * filters use; the labels are what a person reads, and the interface needs both — a board showing
     * only {@code RETEST} teaches its vocabulary to nobody, and one showing only "Retest" cannot be
     * matched against a log entry.
     */
    public Map<String, String> stateLabels(Principal principal) throws SQLException {
        Map<String, String> labels = new LinkedHashMap<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT s.code, coalesce(s.label_i18n->>'en', s.code) "
                                + "  FROM workflow_state s "
                                + "  JOIN workflow_definition d ON d.id = s.definition_id "
                                + " WHERE d.state = 'ACTIVE' "
                                + " ORDER BY s.display_order");
                ResultSet r = statement.executeQuery()) {
            while (r.next()) {
                labels.put(r.getString(1), r.getString(2));
            }
        }
        // unmodifiableMap over the LinkedHashMap, NOT Map.copyOf. Map.copyOf returns an unordered
        // map, and the order here is the workflow's own display_order — the sequence a state filter
        // is read in. Copying it produced a dropdown listing "Cancelled, Fixing, In progress, Open"
        // and nothing said anything was wrong.
        return java.util.Collections.unmodifiableMap(labels);
    }

    /**
     * The reasons a request may be raised, in the tenant's configured order.
     *
     * <p>Active rows only. A deprecated trigger stays readable on the requests that already carry it
     * — the record of why something happened does not change because the tenant stopped offering the
     * option — but it is not offered for new ones.
     */
    public List<Trigger> triggers(Principal principal) throws SQLException {
        List<Trigger> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, code, coalesce(label_i18n->>'en', code), guidance, "
                                + "counts_as_full_review FROM assessment_trigger "
                                + " WHERE lifecycle_state = 'ACTIVE' ORDER BY display_order, code")) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Trigger(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
                            r.getString(4), r.getBoolean(5)));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * Records why a request was raised.
     *
     * <p>Scope is re-validated by reading the request through {@link #request} first, so a request
     * identifier from outside the caller's scope is a 404 and not a write ({@code SEC-AUZ-017}).
     *
     * @return false if the request is not visible to this caller, or the trigger is not a configured
     *     active row — the second because accepting an arbitrary identifier here would let a caller
     *     point a request at a row from a deprecated or, worse, another tenant's configuration
     */
    public boolean setTrigger(Principal principal, UUID requestId, UUID triggerId)
            throws SQLException {
        if (request(principal, requestId).isEmpty()) {
            return false;
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE assessment_request SET trigger_id = "
                                + "  (SELECT id FROM assessment_trigger "
                                + "    WHERE id = ? AND lifecycle_state = 'ACTIVE'), "
                                + "  updated_at = now(), updated_by = ?, "
                                + "  row_version = row_version + 1 "
                                + " WHERE id = ? "
                                // Only when the named trigger actually resolves. Without this the
                                // subquery returning NULL would silently CLEAR the field, so a
                                // mistyped identifier would erase a recorded reason.
                                + "   AND EXISTS (SELECT 1 FROM assessment_trigger "
                                + "                WHERE id = ? AND lifecycle_state = 'ACTIVE')")) {
            statement.setObject(1, triggerId);
            statement.setObject(2, principal.principalId());
            statement.setObject(3, requestId);
            statement.setObject(4, triggerId);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.domainChange(connection, principal, "assessment_request",
                        aspm.kernel.audit.contract.DomainChangeKind.UPDATED, requestId,
                        aspm.app.audit.AuditScopes.ofRequest(connection, requestId),
                        java.util.Map.of("trigger_id", triggerId.toString()));
            }
            connection.commit();
            return applied;
        }
    }

    /** The whole-application reviews of one application, most recent first. */
    public List<FullReview> fullReviews(Principal principal, UUID assetId) throws SQLException {
        List<FullReview> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT request_id, request_code, title, state, "
                                + "       coalesce(trigger_label->>'en', trigger_code), "
                                + "       started_at, started_at_is_intake_date, closed_at, due_at, "
                                + "       state_category, terminal_disposition, abandoned_at "
                                + "  FROM application_full_review WHERE asset_id = ? "
                                + " ORDER BY coalesce(closed_at, abandoned_at, started_at) "
                                + "          DESC NULLS LAST")) {
            statement.setObject(1, assetId);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new FullReview(r.getObject(1, UUID.class), r.getString(2),
                            r.getString(3), r.getString(4), r.getString(5), date(r.getObject(6)),
                            r.getBoolean(7), date(r.getObject(8)), date(r.getObject(9)),
                            r.getString(10), r.getString(11), date(r.getObject(12))));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * The same, for a page of applications at once.
     *
     * <p>One query for the list rather than one per row: an inventory of two hundred applications
     * would otherwise issue two hundred round trips to render one table, and the first person to
     * notice would be whoever is looking at the slow-query log.
     */
    public Map<UUID, Cadence> cadences(Principal principal, java.util.Collection<UUID> assetIds)
            throws SQLException {
        if (assetIds == null || assetIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Cadence> out = new LinkedHashMap<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT asset_id, full_review_count, full_review_in_flight, "
                                + "       full_review_abandoned, last_full_review_at, "
                                + "       interval_months, next_full_review_due, full_review_status "
                                + "  FROM application_review_cadence WHERE asset_id = ANY (?)")) {
            statement.setArray(1, connection.createArrayOf("uuid", assetIds.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    Integer months = r.getObject(6) == null ? null : r.getInt(6);
                    out.put(r.getObject(1, UUID.class),
                            new Cadence(r.getLong(2), r.getLong(3), r.getLong(4),
                                    date(r.getObject(5)), months, date(r.getObject(7)),
                                    r.getString(8)));
                }
            }
        }
        return Map.copyOf(out);
    }

    /**
     * The projects each request names, keyed by request.
     *
     * <p>One query for the whole board rather than one per row — a board of fifty requests would
     * otherwise be fifty-one round trips, and this panel is on the platform's most-opened screen.
     *
     * <p>Reads {@code assessment_request_scope_asset}, which intake writes. The board's existing
     * {@code primary_application} column resolves through {@code assessment}, and an assessment does
     * not exist until somebody is assigned — which is why a newly raised request showed no
     * application at all until an assessor picked it up.
     */
    public Map<UUID, List<Map<String, Object>>> requestProjects(Principal principal,
            java.util.Collection<UUID> requestIds) throws SQLException {
        if (requestIds == null || requestIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Map<String, Object>>> out = new LinkedHashMap<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT sa.request_id, a.id, a.display_name, t.code, sa.named_by_requester "
                                + "  FROM assessment_request_scope_asset sa "
                                + "  JOIN asset a ON a.id = sa.asset_id "
                                + "  JOIN asset_type t ON t.id = a.type_id "
                                + " WHERE sa.request_id = ANY (?) AND t.code = 'PROJECT' "
                                + " ORDER BY a.display_name")) {
            statement.setArray(1, connection.createArrayOf("uuid", requestIds.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", r.getObject(2, UUID.class).toString());
                    entry.put("name", r.getString(3));
                    // Which one the requester picked, versus which were pulled in by a full review.
                    entry.put("namedByRequester", r.getBoolean(5));
                    out.computeIfAbsent(r.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(entry);
                }
            }
        }
        return Map.copyOf(out);
    }

    /**
     * The application behind each request, derived rather than stored.
     *
     * <h2>Why this exists rather than the board view's own column</h2>
     *
     * <p>{@code request_board.primary_application} resolves through {@code assessment_scope_asset},
     * and an assessment row does not exist until an assessor has been named. The consequence was
     * measurable and bad: of 211 requests on the demo board, <b>5</b> showed an application while 104
     * had named a project. The column was effectively empty, and once an application filter existed
     * the inconsistency became visible as a contradiction — the filter found rows whose Application
     * cell was blank.
     *
     * <p>So this resolves it from the scope the <b>requester declared at intake</b>: an application
     * named directly, or the {@code APPLICATION} at the composition root of any project named. Same
     * derivation the filter uses, so the column and the filter cannot disagree.
     *
     * <p>The application is still never stored on the request. ADR-001 keeps the organization tree and
     * the asset graph as two structures, and an application copied onto a request is a copy that goes
     * stale the first time a project moves.
     */
    public Map<UUID, List<String>> requestApplications(Principal principal,
            java.util.Collection<UUID> requestIds) throws SQLException {
        if (requestIds == null || requestIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<String>> out = new LinkedHashMap<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT DISTINCT sa.request_id, app.display_name "
                                + "  FROM assessment_request_scope_asset sa "
                                + "  JOIN asset a ON a.id = sa.asset_id "
                                + "  JOIN asset_type t ON t.id = a.type_id "
                                // The asset itself when it IS an application; otherwise the
                                // APPLICATION at the root of its composition tree.
                                + "  JOIN asset app ON app.id = CASE WHEN t.code = 'APPLICATION' "
                                + "         THEN a.id "
                                + "         ELSE (SELECT ac.root_id FROM asset_composition ac "
                                + "                WHERE ac.asset_id = a.id LIMIT 1) END "
                                + "  JOIN asset_type appt ON appt.id = app.type_id "
                                + "       AND appt.code = 'APPLICATION' "
                                + " WHERE sa.request_id = ANY (?) "
                                + " ORDER BY sa.request_id, app.display_name")) {
            statement.setArray(1, connection.createArrayOf("uuid", requestIds.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.computeIfAbsent(r.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(r.getString(2));
                }
            }
        }
        return Map.copyOf(out);
    }

    /** Where one application stands against its review obligation. */
    public Optional<Cadence> cadence(Principal principal, UUID assetId) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT full_review_count, full_review_in_flight, full_review_abandoned, "
                                + "       last_full_review_at, interval_months, "
                                + "       next_full_review_due, full_review_status "
                                + "  FROM application_review_cadence WHERE asset_id = ?")) {
            statement.setObject(1, assetId);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                Integer months = r.getObject(5) == null ? null : r.getInt(5);
                return Optional.of(new Cadence(r.getLong(1), r.getLong(2), r.getLong(3),
                        date(r.getObject(4)), months, date(r.getObject(6)), r.getString(7)));
            }
        }
    }

    /**
     * A whole instant, in UTC, as ISO-8601.
     *
     * <p>The offset is carried rather than stripped, so the reader is never left guessing which
     * timezone a wall-clock string is in. {@code NFR-INT-003} stores in UTC and formats in the
     * viewer's calendar; sending the instant is what lets the interface do the second half, and a
     * bare "14:08:41" would be seven hours wrong for the person who reported this.
     */
    private static String instant(java.time.OffsetDateTime value) {
        return value == null ? null
                : value.withOffsetSameInstant(java.time.ZoneOffset.UTC)
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String date(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.length() >= 10 ? text.substring(0, 10) : text;
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from "
                + "the authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
