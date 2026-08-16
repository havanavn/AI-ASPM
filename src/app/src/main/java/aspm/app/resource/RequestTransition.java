package aspm.app.resource;

import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import aspm.kernel.authorization.contract.AuthorizationRequest;
import aspm.kernel.authorization.contract.ObjectReference;
import aspm.kernel.authorization.contract.PermissionId;
import aspm.module.organizationscope.application.OrgScopeResolverAdapter;
import aspm.sharedkernel.PrincipalId;
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
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Assessment request transitions. DOC-09 §2.1, §3, §4.
 *
 * <p>The transitions are <b>data</b>. V014 corrected V010's fixed state enumeration, so the available
 * events come from {@code workflow_transition} rows for the type's active definition — which is what
 * makes DOC-09 §4's machine tenant-configurable as it is marked.
 *
 * <h2>The evaluation order is the specification, and it is ordered for a reason</h2>
 *
 * <p>DOC-09 §2.1 lists ten steps and says the order is deliberate: "the cheapest and most
 * disclosure-sensitive checks run first". {@code PRD-WRK-031} makes it a requirement and names the
 * consequence of getting it wrong — "ordering scope before permission prevents a permission denial
 * confirming that an out-of-scope object exists".
 *
 * <p>So this class checks scope <b>before</b> permission, and a scope failure produces the same 404 as a
 * non-existent request. A 403 at step 4 on an object the caller cannot see would be an oracle.
 *
 * <h2>Atomic, and append-only</h2>
 *
 * <p>{@code PRD-WRK-032}: state change, transition record and effects in one transaction, and "a partial
 * transition MUST NOT be observable". The record is written in the same statement batch as the update,
 * conditional on {@code row_version} — so a concurrent transition loses rather than interleaving.
 *
 * <p>{@code PRD-WRK-036}: returning to a prior state is a distinct forward transition with its own
 * record, never a reversal. The database enforces it: {@code app_runtime} holds no UPDATE or DELETE on
 * the log.
 */
public final class RequestTransition {

    /** One transition the caller could request from the current state. */
    public record Available(String event, String toState, String requiredPermission,
            boolean reasonRequired, Optional<String> guard, boolean permitted,
            Optional<String> blockedReason, String toStateLabel, String toStateCategory) {

        /**
         * Whether taking this move ENDS the request.
         *
         * <p>From the target state's category, which is workflow data. The interface needs it because
         * closing is the move people look for and cannot find when it is one unlabelled button among
         * nine; it also needs it because a move that ends the engagement should not be one keystroke
         * away from a move that advances it.
         */
        public boolean closes() {
            return "TERMINAL".equals(toStateCategory);
        }
    }

    /** The outcome of a transition attempt. */
    public sealed interface Outcome {

        /** Applied, or already applied — DOC-09 §3 makes a repeat request a success without a record. */
        record Applied(String fromState, String toState, boolean alreadyInState) implements Outcome {
        }

        /** Not available from the current state. DOC-09 §3: {@code 409 STATE_TRANSITION_INVALID}. */
        record Invalid(String currentState, String event) implements Outcome {
        }

        /** A guard, a required field, or an invariant denied it. */
        record Denied(String code, String detail) implements Outcome {
        }
    }

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public RequestTransition(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * The transitions available from a request's current state, each with whether this caller may take
     * it and why not.
     *
     * <p>Rendering an unavailable transition with its reason rather than hiding it is deliberate. DOC-08
     * §7.1 requires transitions to be reachable from the keyboard on a focused item, and a button that
     * silently disappears teaches nothing — a requester who cannot submit needs to know it is the
     * readiness attestation, not that the button moved.
     */
    public List<Available> available(Principal principal, UUID requestId) throws SQLException {
        Map<String, Object> request = load(principal, requestId);
        if (request == null) {
            return List.of();
        }
        String state = String.valueOf(request.get("state"));

        List<Available> out = new ArrayList<>();
        for (Map<String, Object> row : transitions(principal, requestId, state)) {
            String permission = String.valueOf(row.get("required_permission"));
            String guard = row.get("guard") == null ? "" : String.valueOf(row.get("guard"));
            Optional<String> blocked = Optional.empty();

            if (!principal.holds(permission)) {
                // Step 4. Reported as a blocked action rather than an absent one, because the caller
                // already reached the object legitimately — scope passed at step 2.
                blocked = Optional.of("permission");
            } else if (!guard.isEmpty()) {
                blocked = evaluateGuard(principal, request, guard);
            }
            out.add(new Available(String.valueOf(row.get("event_code")),
                    String.valueOf(row.get("to_state")), permission,
                    Boolean.TRUE.equals(row.get("reason_required")),
                    guard.isEmpty() ? Optional.empty() : Optional.of(guard),
                    blocked.isEmpty(), blocked,
                    String.valueOf(row.get("to_label")), String.valueOf(row.get("to_category"))));
        }
        return List.copyOf(out);
    }

    /** The transition log, newest first. */
    public List<Map<String, Object>> history(Principal principal, UUID requestId) throws SQLException {
        return inTenantTransaction(principal, connection -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT sequence_number, from_state, to_state, event_code, actor_type, "
                            + "actor_principal_id, reason, occurred_at, prior_state_duration, "
                            + "sla_clock_running FROM assessment_request_transition "
                            + "WHERE request_id = ? ORDER BY sequence_number DESC")) {
                statement.setObject(1, requestId);
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("sequence_number", results.getLong(1));
                        row.put("from_state", results.getString(2));
                        row.put("to_state", results.getString(3));
                        row.put("event_code", results.getString(4));
                        row.put("actor_type", results.getString(5));
                        row.put("actor_principal_id", results.getString(6));
                        row.put("reason", results.getString(7));
                        row.put("occurred_at", String.valueOf(results.getObject(8)));
                        row.put("prior_state_duration", results.getString(9));
                        row.put("sla_clock_running", results.getBoolean(10));
                        rows.add(row);
                    }
                }
            }
            return List.copyOf(rows);
        });
    }

    /**
     * Requests a transition.
     *
     * @param reason required where the transition's definition says so, and always on a transition to a
     *     non-success terminal state (DOC-09 §3)
     */
    public Outcome apply(Principal principal, UUID requestId, String event, Optional<String> reason)
            throws SQLException {
        Objects.requireNonNull(event, "an event is required");

        // Steps 1 and 2: tenant context is established by the dispatcher; scope is re-validated by the
        // authorization gate below, and a failure there is absence rather than denial.
        authorize(principal, requestId);

        Map<String, Object> request = load(principal, requestId);
        if (request == null) {
            return new Outcome.Denied("NOT_FOUND", "not found");
        }
        String state = String.valueOf(request.get("state"));

        // DOC-09 §3 idempotency: re-requesting a transition already applied is a success with no second
        // record. Checked by destination rather than by an idempotency key, because the key protects the
        // HTTP retry and this protects the human who clicked twice.
        Optional<Map<String, Object>> matching = transitions(principal, requestId, state).stream()
                .filter(t -> event.equals(t.get("event_code"))).findFirst();
        if (matching.isEmpty()) {
            boolean alreadyThere = transitionsToState(principal, requestId, event)
                    .contains(state);
            if (alreadyThere) {
                return new Outcome.Applied(state, state, true);
            }
            // Step 3. Not available from this state.
            return new Outcome.Invalid(state, event);
        }
        Map<String, Object> transition = matching.orElseThrow();
        String permission = String.valueOf(transition.get("required_permission"));

        // Step 4.
        if (!principal.holds(permission)) {
            return new Outcome.Denied("PERMISSION_REQUIRED", permission);
        }

        // Step 7: required fields. The reason is the one this platform always needs.
        boolean reasonRequired = Boolean.TRUE.equals(transition.get("reason_required"));
        if (reasonRequired && (reason.isEmpty() || reason.orElseThrow().isBlank())) {
            return new Outcome.Denied("REASON_REQUIRED",
                    "this transition requires a reason, and a terminal state that is not a success "
                            + "outcome always does (DOC-09 section 3)");
        }

        // Step 8: the guard. Step 9's domain invariants are enforced by the schema — readiness before
        // acceptance is a CHECK, so a guard bypass still cannot write the row.
        String guard = transition.get("guard") == null ? "" : String.valueOf(transition.get("guard"));
        if (!guard.isEmpty()) {
            Optional<String> blocked = evaluateGuard(principal, request, guard);
            if (blocked.isPresent()) {
                return new Outcome.Denied("GUARD_FAILED", blocked.orElseThrow());
            }
        }

        // Step 10: one transaction.
        String toState = String.valueOf(transition.get("to_state"));
        boolean clockRunning = Boolean.TRUE.equals(transition.get("to_clock_running"));
        int expectedVersion = ((Number) request.get("row_version")).intValue();

        return inTenantTransaction(principal, connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE assessment_request SET state = ?, row_version = row_version + 1, "
                            + "updated_at = now(), updated_by = ? "
                            + "WHERE id = ? AND row_version = ? AND state = ?")) {
                update.setString(1, toState);
                update.setObject(2, principal.principalId());
                update.setObject(3, requestId);
                update.setInt(4, expectedVersion);
                update.setString(5, state);
                // Captured, not re-executed. The first version of this called executeUpdate()
                // twice — once to test and once in the else — which applied the transition twice
                // and would have made the second one fail the row_version guard, reporting a
                // concurrent transition that never happened.
                int applied = update.executeUpdate();
                if (applied != 1) {
                    // A concurrent transition won. DOC-09 §18 makes this a lost update rather than a
                    // merge: two transitions from the same state are not composable.
                    return new Outcome.Denied("CONCURRENT_TRANSITION",
                            "the request changed state while this transition was being evaluated");
                }
            }

            long sequence;
            try (PreparedStatement next = connection.prepareStatement(
                    "SELECT coalesce(max(sequence_number), 0) + 1 FROM assessment_request_transition "
                            + "WHERE request_id = ?")) {
                next.setObject(1, requestId);
                try (ResultSet results = next.executeQuery()) {
                    results.next();
                    sequence = results.getLong(1);
                }
            }

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO assessment_request_transition (tenant_id, request_id, sequence_number, "
                            + "from_state, to_state, event_code, actor_principal_id, actor_type, reason, "
                            + "prior_state_duration, sla_clock_running) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'HUMAN', ?, "
                            + "  (SELECT now() - max(occurred_at) FROM assessment_request_transition "
                            + "   WHERE request_id = ?), ?)")) {
                insert.setObject(1, principal.tenantId());
                insert.setObject(2, requestId);
                insert.setLong(3, sequence);
                // The first record has no prior state, which is what the CHECK pairs with sequence 1.
                insert.setString(4, sequence == 1 ? null : state);
                insert.setString(5, toState);
                insert.setString(6, event);
                insert.setObject(7, principal.principalId());
                insert.setString(8, reason.filter(r -> !r.isBlank()).orElse(null));
                insert.setObject(9, requestId);
                insert.setBoolean(10, clockRunning);
                insert.executeUpdate();
            }
            // *** THE HELD CREDENTIAL DIES WITH THE ENGAGEMENT. ***
            //
            // Inside the same transaction as the transition, so "closed" and "destroyed" cannot come
            // apart: a rollback takes both, and there is no window where the board shows a closed
            // request whose password is still held. The intake form promises the requester exactly
            // this, and a sweep on a schedule would make the promise approximately true.
            //
            // Terminality is read from the workflow definition rather than matched against a code —
            // DOC-09 lets a tenant name its states, so a list here would go stale and would then
            // quietly keep credentials past closure, which is the failure nobody notices.
            if (isTerminal(connection, toState)) {
                int destroyed = purgeHeldCredentials(connection, requestId,
                        "REQUEST_CLOSED_" + toState);
                if (destroyed > 0) {
                    System.getLogger("aspm.custody").log(System.Logger.Level.INFO,
                            "destroyed " + destroyed + " held credential(s) on request " + requestId
                                    + " entering terminal state " + toState);
                }
            }
            audit.domainChange(connection, principal, "assessment_request",
                    aspm.kernel.audit.contract.DomainChangeKind.TRANSITIONED, requestId,
                    aspm.app.audit.AuditScopes.ofRequest(connection, requestId),
                    java.util.Map.of("from_state", state,
                            "to_state", toState,
                            "event", event,
                            "sequence_number", Long.valueOf(sequence),
                            // Whether the held credentials died with it. This is the one fact about a
                            // closure a requester was promised, and the transition log does not carry
                            // it — it belongs to the credential, which no longer exists to be asked.
                            "held_credentials_destroyed", Boolean.valueOf(isTerminal(connection, toState))));
            return new Outcome.Applied(state, toState, false);
        });
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Guard evaluation.
     *
     * <p>{@code PRD-WRK-033}: guards are "deterministic, side-effect free, and bounded in evaluation
     * cost" and "MUST NOT invoke AI, external services, or unbounded queries", because a guard runs on
     * every attempt including denied ones and a model in a guard would put one in a decision path
     * (ADR-005). Each branch here is a bounded query against the request and its children.
     *
     * @return empty where the guard passes; the reason otherwise
     */
    private Optional<String> evaluateGuard(Principal principal, Map<String, Object> request,
            String guard) throws SQLException {
        if (guard.contains("submit_ready")) {
            return submitReady(principal, request);
        }
        if (guard.contains("approver_differs")) {
            // DOC-09 §4: approver ≠ requester. SEC-AUZ-039's separation of duties, checked at action
            // time rather than at grant time, because the requester of this object is only known now.
            return Objects.equals(String.valueOf(request.get("requested_by")),
                    principal.principalId().toString())
                    ? Optional.of("the approver must differ from the requester")
                    : Optional.empty();
        }
        if (guard.contains("qa_differs")) {
            // ⚠ Approximated. DOC-09 §4 requires the QA approver to differ from the report author, and
            // the schema records no report author — so this compares against the requester instead.
            // Stated rather than silently treated as equivalent: the check is weaker than specified.
            return Objects.equals(String.valueOf(request.get("requested_by")),
                    principal.principalId().toString())
                    ? Optional.of("the QA approver must differ from the author; this check currently "
                            + "compares against the requester because no report author is recorded")
                    : Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * DOC-09 §4's submit guard: "≥2 accounts per declared role; readiness complete; bypass recorded
     * where a control is present; credentials by reference only".
     */
    private Optional<String> submitReady(Principal principal, Map<String, Object> request)
            throws SQLException {
        List<String> failures = new ArrayList<>();

        for (String condition : List.of("readiness_environment_available",
                "readiness_accounts_provisioned", "readiness_data_seeded",
                "readiness_contact_available")) {
            if (!Boolean.TRUE.equals(request.get(condition))) {
                failures.add(condition);
            }
        }
        if (request.get("readiness_attested_at") == null) {
            failures.add("readiness_attested_at");
        }

        UUID id = UUID.fromString(String.valueOf(request.get("id")));

        // *** THE ZERO CASE, AND IT IS NOT THE PASSING CASE. ***
        //
        // The per-role check below is `GROUP BY role_name HAVING count(*) < 2`. With NO accounts at all
        // the GROUP BY returns no rows, so "no role has fewer than two accounts" is trivially true and
        // the guard passed a request with nothing to test against. Found by running the seed: a request
        // whose readiness claimed accounts were provisioned, and had none, was ACCEPTED.
        //
        // The same shape as the array_length(empty, 1) defect recorded earlier in this build: an
        // emptiness case that makes a check vacuous rather than failing. INV-ASM-02 is a SET assertion,
        // and the empty set satisfies "every role has two" only if you forget to ask whether there is a
        // role.
        int accountCount = inTenantTransaction(principal, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM assessment_request_role_account WHERE request_id = ?")) {
                statement.setObject(1, id);
                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    return Integer.valueOf(results.getInt(1));
                }
            }
        }).intValue();
        if (accountCount == 0) {
            failures.add("no test account has been provided, so no role can have the two accounts "
                    + "INV-ASM-02 requires — and the readiness attestation claiming otherwise is the "
                    + "contradiction this guard exists to catch");
        }

        List<String> thin = inTenantTransaction(principal, connection -> {
            List<String> roles = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT role_name FROM assessment_request_role_account WHERE request_id = ? "
                            + "GROUP BY role_name HAVING count(*) < 2")) {
                statement.setObject(1, id);
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        roles.add(results.getString(1));
                    }
                }
            }
            return List.copyOf(roles);
        });
        if (!thin.isEmpty()) {
            failures.add("roles with fewer than two accounts: " + String.join(", ", thin));
        }

        List<String> unusable = inTenantTransaction(principal, connection -> {
            List<String> accounts = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT username FROM assessment_request_role_account "
                            + "WHERE request_id = ? AND account_status IN ('EXPIRED', 'LOCKED')")) {
                statement.setObject(1, id);
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        accounts.add(results.getString(1));
                    }
                }
            }
            return List.copyOf(accounts);
        });
        if (!unusable.isEmpty()) {
            failures.add("accounts expired or locked: " + String.join(", ", unusable));
        }

        return failures.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", failures));
    }

    // ----------------------------------------------------------------------------------------------

    private void authorize(Principal principal, UUID requestId) {
        String permission = ResourceCatalogue.REQUESTS.readPermission();
        if (!principal.holds(permission)) {
            throw new Dispatcher.UnauthorizedException("principal does not hold " + permission);
        }
        var gate = new aspm.kernel.authorization.application.ScopeResolvingAuthorizationGate(
                new OrgScopeResolverAdapter(
                        new aspm.app.runtime.RequestScope(dataSource, principal)),
                (context, denied, denial) -> { });
        // The AuthorizedQuery is not used here: the scope filter is applied by the queries below
        // through row-level security and the object read, and the gate's value is the DENIAL. Error
        // Prone flags the ignored return, and it is right to — so the result is bound and the reason
        // it goes unread is stated rather than left as an apparent oversight.
        var authorized = gate.authorize(AuthorizationRequest.forObject(
                new PrincipalId(principal.principalId()), new PermissionId(permission),
                new ObjectReference("assessment_request", requestId)));
        if (authorized.isEmpty()) {
            throw new Dispatcher.UnauthorizedException("denied");
        }
    }

    /** The request, scope-filtered. Null where it does not exist or is out of scope — the same thing. */
    private Map<String, Object> load(Principal principal, UUID requestId) throws SQLException {
        return inTenantTransaction(principal, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, state, row_version, requested_by, readiness_environment_available, "
                            + "readiness_accounts_provisioned, readiness_data_seeded, "
                            + "readiness_contact_available, readiness_attested_at, type_id "
                            + "FROM assessment_request WHERE id = ?")) {
                statement.setObject(1, requestId);
                try (ResultSet results = statement.executeQuery()) {
                    if (!results.next()) {
                        return null;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", results.getObject(1, UUID.class).toString());
                    row.put("state", results.getString(2));
                    row.put("row_version", results.getInt(3));
                    row.put("requested_by", String.valueOf(results.getObject(4)));
                    row.put("readiness_environment_available", results.getBoolean(5));
                    row.put("readiness_accounts_provisioned", results.getBoolean(6));
                    row.put("readiness_data_seeded", results.getBoolean(7));
                    row.put("readiness_contact_available", results.getBoolean(8));
                    row.put("readiness_attested_at", results.getObject(9));
                    row.put("type_id", results.getObject(10, UUID.class).toString());
                    return row;
                }
            }
        });
    }

    /** Transition rows leaving the given state, for the type's ACTIVE definition. */
    private List<Map<String, Object>> transitions(Principal principal, UUID requestId, String state)
            throws SQLException {
        return inTenantTransaction(principal, connection -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT t.event_code, x.code AS to_state, t.required_permission, t.reason_required, "
                            + "       t.guard_rule::text AS guard, x.sla_clock_running AS to_clock, "
                    + "       coalesce(x.label_i18n->>'en', x.code) AS to_label, "
                    + "       x.category AS to_category "
                            + "  FROM assessment_request r "
                            + "  JOIN assessment_type ty ON ty.id = r.type_id "
                            + "  JOIN workflow_definition d ON d.id = ty.workflow_definition_id "
                            + "  JOIN workflow_state f ON f.definition_id = d.id AND f.code = ? "
                            + "  JOIN workflow_transition t ON t.definition_id = d.id "
                            + "       AND t.from_state_id = f.id "
                            + "  JOIN workflow_state x ON x.id = t.to_state_id "
                            + " WHERE r.id = ? AND d.state = 'ACTIVE' "
                            + " ORDER BY x.display_order")) {
                statement.setString(1, state);
                statement.setObject(2, requestId);
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("event_code", results.getString("event_code"));
                        row.put("to_state", results.getString("to_state"));
                        row.put("required_permission", results.getString("required_permission"));
                        row.put("reason_required", results.getBoolean("reason_required"));
                        String guard = results.getString("guard");
                        row.put("guard", guard == null || "{}".equals(guard) ? null : guard);
                        row.put("to_clock_running", results.getBoolean("to_clock"));
                        row.put("to_label", results.getString("to_label"));
                        row.put("to_category", results.getString("to_category"));
                        rows.add(row);
                    }
                }
            }
            return List.copyOf(rows);
        });
    }

    /** Destination states an event leads to, for the idempotency check. */
    private List<String> transitionsToState(Principal principal, UUID requestId, String event)
            throws SQLException {
        return inTenantTransaction(principal, connection -> {
            List<String> states = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT x.code FROM assessment_request r "
                            + "  JOIN assessment_type ty ON ty.id = r.type_id "
                            + "  JOIN workflow_definition d ON d.id = ty.workflow_definition_id "
                            + "  JOIN workflow_transition t ON t.definition_id = d.id "
                            + "  JOIN workflow_state x ON x.id = t.to_state_id "
                            + " WHERE r.id = ? AND t.event_code = ? AND d.state = 'ACTIVE'")) {
                statement.setObject(1, requestId);
                statement.setString(2, event);
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        states.add(results.getString(1));
                    }
                }
            }
            return List.copyOf(states);
        });
    }

    @FunctionalInterface
    private interface InTransaction<T> {
        T apply(Connection connection) throws SQLException;
    }

    private <T> T inTenantTransaction(Principal principal, InTransaction<T> body) throws SQLException {
        try (Connection connection =
                aspm.app.persistence.TenantConnections.open(dataSource, principal)) {
            try {
                T result = body.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * Whether a state is terminal, per the active workflow definition.
     *
     * <p>Read from {@code category}, never matched against a code. {@code CANCELLED} and
     * {@code REJECTED} do not start with the word "closed", and a code test would have kept a
     * credential live on every request that was abandoned rather than finished.
     */
    private static boolean isTerminal(java.sql.Connection connection, String state)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM workflow_state WHERE code = ? AND category = 'TERMINAL' LIMIT 1")) {
            statement.setString(1, state);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    /**
     * Destroys every credential the request holds, leaving the tombstone.
     *
     * <p>On this connection rather than through {@code IntakeService}, so it shares the
     * transaction. {@code rotation_required} is set at the same time and is a separate obligation —
     * destroying our copy does not change the password on the customer's system.
     */
    private static int purgeHeldCredentials(java.sql.Connection connection, UUID requestId,
            String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE assessment_request_role_account "
                        + "   SET secret_ciphertext = NULL, secret_nonce = NULL, "
                        + "       secret_algorithm = NULL, secret_purged_at = now(), "
                        + "       secret_purge_reason = ?, rotation_required = true "
                        + " WHERE request_id = ? AND secret_ciphertext IS NOT NULL")) {
            statement.setString(1, reason);
            statement.setObject(2, requestId);
            return statement.executeUpdate();
        }
    }

}
