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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Moves a finding through its lifecycle, and records that it moved.
 *
 * <h2>The five states, named by what a person would say</h2>
 *
 * <pre>
 *   OPEN ──claim──▶ FIXED ──verify──▶ CLOSED
 *    ▲               │                  │
 *    │               │ refute           │ regressed
 *    │               ▼                  │
 *    └──────────── REOPEN ◀─────────────┘
 *
 *   OPEN / REOPEN / FIXED ──ask──▶ ACCEPTANCE_REQUESTED ──approve──▶ ACCEPTED_RISK ──withdraw──▶ OPEN
 *                                          └──reject──▶ OPEN
 * </pre>
 *
 * <p><b>OPEN</b> found, nobody has claimed a fix. <b>FIXED</b> the delivery team says it is done and
 * nobody has checked. <b>CLOSED</b> we checked and it is. <b>REOPEN</b> we checked and it is not.
 * <b>ACCEPTED_RISK</b> left in place deliberately, until a date.
 *
 * <h2>Two permissions, because claiming and verifying are two different jobs</h2>
 *
 * <p>{@code vul.finding.claimfix} says "I have deployed the fix, please check" and belongs to whoever
 * did the work — a delivery engineer with no security permission at all. {@code vul.finding.verify}
 * says "I checked" and belongs to the security side. One permission covering both would let the team
 * that wrote the fix declare it verified, which is the single combination this workflow exists to
 * prevent: the claim-is-not-closure rule is only a rule if a different person closes it.
 *
 * <h2>Two columns, because "is it still work" and "where is it" are different questions</h2>
 *
 * <p>{@code lifecycle_state} carries the five names. {@code state} keeps its original OPEN/CLOSED
 * meaning, and FIXED and REOPEN are both OPEN there — a finding awaiting verification is open work, and
 * one whose fix failed never stopped being open. Every open count, overdue calculation and coverage
 * figure in the platform reads {@code state}; had the five names gone onto that column, a merely
 * CLAIMED fix would have vanished from all of them while nobody had checked anything. A CHECK ties the
 * two so they cannot disagree, which is why every write below sets both.
 *
 * <h2>FIXED is the claim that already existed</h2>
 *
 * <p>{@code remediation_claimed_at} has always meant "somebody says this is done and nobody has
 * verified it". A FIXED state stored beside it would give one question two answers that drift. So
 * entering FIXED writes the claim columns and the claim columns are what FIXED means — nothing here
 * invents a parallel truth, and every finding claimed before this existed reads correctly.
 *
 * <h2>Accepting a risk goes through a real exception, because the schema insists</h2>
 *
 * <p>{@code ck_finding__acceptance_linked} refuses {@code closure_reason = 'RISK_ACCEPTED'} without a
 * {@code risk_exception}, and {@code risk_exception} refuses an expiry that is absent, in the past, or
 * beyond its own stated duration. Those constraints predate this class and they are correct: an
 * acceptance with no end date is not an acceptance, it is a decision to stop looking. So acceptance
 * writes the exception, and the date the user types is the exception's expiry.
 *
 * <p>A SECRET-class finding can never be accepted — two separate constraints say so, on the finding and
 * on the exception. A leaked credential is not a risk you hold, it is a credential somebody else has;
 * the only remediation is rotation. {@link #view} reports that as a reason on the move rather than
 * letting the button fail at the database.
 *
 * <h2>Acceptance takes two people, and this class learned that the hard way</h2>
 *
 * <p>The first version of this class did it in one action: the holder of {@code vul.finding.acceptrisk}
 * named a date and the finding became ACCEPTED_RISK. The first end-to-end run was refused by
 * {@code assert_exception_approver_differs} — {@code INV-VUL-26}, which forbids an exception approved by
 * its own requester and which DOC-07 §15.1 records as the first control an auditor tests. The invariant
 * was right. One person accepting a known weakness is not an exception process; it is one person's
 * decision wearing the paperwork of one.
 *
 * <p>So there is a middle position. {@link #ask} creates the exception in REQUESTED and moves the
 * finding to ACCEPTANCE_REQUESTED, which is still OPEN on the coarse axis — if asking counted as
 * closing, requesting exceptions and never approving them would improve every number while the risk sat
 * where it was. {@link #approve} needs a DIFFERENT principal holding {@code vul.finding.acceptrisk}, and
 * refuses in a sentence before the trigger does, because "refused by INV-VUL-26" is not something
 * somebody can act on.
 *
 * <h2>Why every move is a row</h2>
 *
 * <p>Product principle 5. A state column answers where a finding is now and destroys how it got there
 * on every write. "This was closed, reopened twice, then accepted" is the sentence that makes tracking
 * a finding worth more than counting one, and it is unanswerable without the log. Each row carries a
 * note, required, because the reviews that matter happen months later when whoever moved it has
 * forgotten why.
 */
public final class FindingLifecycle {

    /** Claiming a fix — the delivery side. */
    public static final String CLAIM = "vul.finding.claimfix";
    /** Verifying or refuting a claim — the security side. */
    public static final String VERIFY = "vul.finding.verify";
    /** Accepting a risk until a date. Restricted, step-up. */
    public static final String ACCEPT = "vul.finding.acceptrisk";

    /** What a caller may do to this finding right now, and why not where they may not. */
    public record Move(String to, String label, String permission, boolean permitted, String reason,
            boolean needsDate) {
    }

    /** One row of the history. */
    public record Entry(String from, String to, String note, String acceptedUntil, String occurredAt,
            String actor) {
    }

    /** Everything the finding page needs to draw the control. */
    /**
     * Everything the finding page needs to draw the control.
     *
     * <p>{@code acceptedUntil} and {@code proposedUntil} are separate on purpose. A first version
     * coalesced them — {@code coalesce(risk_accepted_until, exception.expires_at)} — and the API then
     * reported a finding as "accepted until 2027-01-01" while it was merely REQUESTED and nobody had
     * agreed to anything. V057 had just moved that date off the finding for precisely that reason, and
     * the read path put it back. Two fields, so a caller cannot render one as the other.
     */
    public record View(String state, String label, List<Move> moves, List<Entry> history,
            String claimedAt, String claimedBy, String acceptedUntil, String proposedUntil,
            String acceptedReason, String closureReason, String verifiedBy, int recurrenceCount,
            int otherApprovers) {
    }

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public FindingLifecycle(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /** The human name of a state. Kept here so the API and the register agree on one spelling. */
    public static String label(String state) {
        return switch (state == null ? "" : state) {
            case "OPEN" -> "Open";
            case "FIXED" -> "Fixed — awaiting verification";
            case "CLOSED" -> "Closed — verified";
            case "REOPEN" -> "Reopened — fix did not hold";
            case "ACCEPTANCE_REQUESTED" -> "Acceptance requested — awaiting a second approver";
            case "ACCEPTED_RISK" -> "Risk accepted";
            default -> state;
        };
    }

    /**
     * The transitions that exist, independent of who is asking.
     *
     * <p>Deliberately not a table in the database. Workflow-as-data is right for assessment requests,
     * where every tenant runs a different process (ADR-028). A finding's lifecycle is not that: the
     * five situations are properties of what a vulnerability IS, and a tenant that could remove
     * "verified" from the path between claimed and closed would be configuring away the control rather
     * than configuring the product. Configurable structure, opinionated defaults — and this is one of
     * the places the default is the point.
     */
    private static List<Move> transitionsFrom(String state, boolean isSecret) {
        List<Move> reachable = reachableFrom(state, isSecret);
        // EVERY state is returned, not only the reachable ones.
        //
        // The first version listed just the legal moves, so a finding sitting at OPEN offered two
        // choices and the other four simply were not there. The reported symptom was "the dropdown does
        // not have all the states you added" — and from the screen that is indistinguishable from them
        // never having been built. A lifecycle you can only learn by walking it is a lifecycle nobody
        // learns.
        //
        // The unreachable ones are listed disabled, each saying why. That is the same treatment already
        // given to a move the caller lacks the permission for, and for the same reason: an option that
        // is absent teaches nothing, while "somebody has to report a fix before this can be verified"
        // teaches the whole rule in one line.
        List<Move> moves = new ArrayList<>();
        for (String target : ORDER) {
            if (target.equals(state)) {
                continue;   // "change to what it already is" is not an option; the schema refuses it
            }
            Move legal = reachable.stream().filter(m -> m.to().equals(target)).findFirst().orElse(null);
            moves.add(legal != null ? legal
                    : new Move(target, label(target), VERIFY, false, whyNotFrom(state, target), false));
        }
        return moves;
    }

    /** Lifecycle order, so the picker reads as the path a finding takes rather than as a set. */
    private static final List<String> ORDER = List.of("OPEN", "FIXED", "CLOSED", "REOPEN",
            "ACCEPTANCE_REQUESTED", "ACCEPTED_RISK");

    /**
     * Why a state cannot be reached from here — the rule, not a refusal.
     *
     * <p>Each sentence names the step that is missing rather than saying "not allowed". Somebody who
     * reads "a fix has to be reported before it can be verified" now knows the model; somebody who
     * reads "invalid transition" has learnt that the software disagrees with them.
     */
    private static String whyNotFrom(String from, String to) {
        return switch (to) {
            case "OPEN" -> "Already open work. This only appears for a finding that was closed, "
                    + "accepted, or has an acceptance waiting.";
            case "FIXED" -> "CLOSED".equals(from)
                    ? "This is closed. Reopen it first if the weakness has come back."
                    : "ACCEPTED_RISK".equals(from)
                        ? "The risk is accepted. Withdraw the acceptance before reporting a fix."
                        : "An acceptance is waiting on a decision. Resolve that first.";
            case "CLOSED" -> "Nothing has been reported as fixed yet, and a finding is only closed by "
                    + "verifying a fix somebody claimed — that separation is the point.";
            case "REOPEN" -> "Nothing to reopen: this is open work already.";
            case "ACCEPTANCE_REQUESTED" -> "CLOSED".equals(from)
                    ? "This is closed. There is no risk left to accept."
                    : "An acceptance has already been requested or granted.";
            case "ACCEPTED_RISK" -> "Somebody has to ASK for the acceptance first, and a second person "
                    + "approves it — one person cannot do both (INV-VUL-26).";
            default -> "Not reachable from here.";
        };
    }

    private static List<Move> reachableFrom(String state, boolean isSecret) {
        List<Move> moves = new ArrayList<>();
        switch (state == null ? "OPEN" : state) {
            case "OPEN", "REOPEN" -> {
                moves.add(new Move("FIXED", "Report as fixed", CLAIM, true, null, false));
                moves.add(acceptance(isSecret));
            }
            case "FIXED" -> {
                moves.add(new Move("CLOSED", "Verified — close it", VERIFY, true, null, false));
                moves.add(new Move("REOPEN", "Not fixed — reopen", VERIFY, true, null, false));
                moves.add(acceptance(isSecret));
            }
            case "ACCEPTANCE_REQUESTED" -> {
                // Approving needs the restricted permission AND a different person; the second half is
                // checked against the request itself, not here, because it depends on who asked.
                moves.add(new Move("ACCEPTED_RISK", "Approve the acceptance", ACCEPT, true, null,
                        false));
                moves.add(new Move("OPEN", "Reject it — back to open", CLAIM, true, null, false));
            }
            case "CLOSED" ->
                // Closed is not final. A verified fix that regressed is the commonest way a finding
                // comes back, and forcing it to be re-reported as a new finding would lose the fact
                // that it is the same weakness returning — which is the fact worth knowing.
                moves.add(new Move("REOPEN", "It came back — reopen", VERIFY, true, null, false));
            case "ACCEPTED_RISK" ->
                moves.add(new Move("OPEN", "Withdraw the acceptance", VERIFY, true, null, false));
            default -> { }
        }
        return moves;
    }

    /**
     * Asking, not accepting. The permission is the delivery side's: whoever owns the remediation is who
     * asks not to do it, and asking is not a privileged act. Deciding is, and that is {@link #ACCEPT}.
     */
    private static Move acceptance(boolean isSecret) {
        return new Move("ACCEPTANCE_REQUESTED", "Ask to accept the risk", CLAIM, !isSecret,
                isSecret
                        ? "A leaked secret cannot be accepted — it is a credential somebody else "
                          + "already has, and the only remediation is rotation."
                        : null,
                true);
    }

    /** Reads the finding's current position, what may be done to it, and how it got here. */
    public View view(Principal principal, UUID findingId) throws SQLException {
        try (Connection connection = open(principal)) {
            String state = "OPEN";
            String claimedAt = null;
            String claimedBy = null;
            String acceptedUntil = null;
            String proposedUntil = null;
            String acceptedReason = null;
            String closureReason = null;
            String verifiedBy = null;
            int recurrence = 0;
            boolean isSecret = false;
            boolean found = false;
            try (PreparedStatement s = connection.prepareStatement("""
                    SELECT f.lifecycle_state, f.finding_class, f.recurrence_count,
                           f.remediation_claimed_at::text, claimer.display_name,
                           f.risk_accepted_until::text,
                           f.risk_accepted_reason, f.closure_reason, verifier.display_name,
                           -- The date somebody ASKED for, and only while it is still only a request.
                           -- Reported under its own name so no caller can render it as agreed.
                           CASE WHEN e.state = 'REQUESTED' THEN e.expires_at::date::text END
                      FROM finding f
                      LEFT JOIN risk_exception e ON e.id = f.accepted_under_exception_id
                      LEFT JOIN principal claimer ON claimer.id = f.remediation_claimed_by
                      LEFT JOIN principal verifier ON verifier.id = f.closure_verified_by
                     WHERE f.id = ?
                    """)) {
                s.setObject(1, findingId);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        found = true;
                        state = r.getString(1);
                        isSecret = "SECRET".equals(r.getString(2));
                        recurrence = r.getInt(3);
                        claimedAt = r.getString(4);
                        claimedBy = r.getString(5);
                        acceptedUntil = r.getString(6);
                        acceptedReason = r.getString(7);
                        closureReason = r.getString(8);
                        verifiedBy = r.getString(9);
                        proposedUntil = r.getString(10);
                    }
                }
            }
            // Row-level security already limited the read to the caller's scope, so a miss here is
            // either a finding that does not exist or one they may not see. Both get the same answer:
            // saying which would turn this into a way to test whether an identifier is real.
            if (!found) {
                return null;
            }

            List<Move> moves = new ArrayList<>();
            for (Move m : transitionsFrom(state, isSecret)) {
                boolean permitted = m.permitted() && principal.holds(m.permission());
                String reason = m.reason();
                if (m.permitted() && !permitted) {
                    reason = "You do not hold " + m.permission() + ".";
                }
                moves.add(new Move(m.to(), m.label(), m.permission(), permitted, reason,
                        m.needsDate()));
            }
            return new View(state, label(state), moves, history(connection, findingId), claimedAt,
                    claimedBy, acceptedUntil, proposedUntil, acceptedReason, closureReason, verifiedBy,
                    recurrence, otherApprovers(connection, principal));
        }
    }

    /**
     * How many OTHER people could approve an acceptance. Zero means the request would stall forever,
     * and somebody about to make one should be told before they make it.
     *
     * <h2>Why this lives here and not in a migration</h2>
     *
     * V057 and V058 both check the same thing at migration time and raise a notice. Neither is visible:
     * {@code deploy/migrate/apply.sh} redirects psql output to a file and prints it only when a
     * migration FAILS, so a notice on a successful run is written to a log nobody reads. A check whose
     * only output is invisible is not a check — it is the shape of one, which is worse, because it looks
     * like the condition is being watched.
     *
     * <p>It also belongs here on the merits. Whether a second approver exists is a fact about today's
     * role grants, not about the schema, and it changes without a migration running. The person who
     * needs to know is the one on the screen about to ask for an exception.
     */
    private int otherApprovers(Connection connection, Principal principal) throws SQLException {
        // The same conditions IdentityService applies when it resolves permissions: an active role, an
        // active principal, and an assignment that is neither revoked nor expired. A count that ignored
        // revocation is exactly what V058 was written to correct.
        try (PreparedStatement s = connection.prepareStatement("""
                SELECT count(DISTINCT ra.principal_id)
                  FROM role_assignment ra
                  JOIN role r ON r.id = ra.role_id AND r.lifecycle_state = 'ACTIVE'
                  JOIN role_permission rp ON rp.role_id = r.id
                  JOIN principal p ON p.id = ra.principal_id AND p.lifecycle_state = 'ACTIVE'
                 WHERE rp.permission_code = ?
                   AND ra.revoked_at IS NULL
                   AND (ra.expires_at IS NULL OR ra.expires_at > now())
                   AND ra.principal_id <> ?
                """)) {
            s.setString(1, ACCEPT);
            s.setObject(2, principal.principalId());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getInt(1) : 0;
            }
        }
    }

    private List<Entry> history(Connection connection, UUID findingId) throws SQLException {
        List<Entry> out = new ArrayList<>();
        try (PreparedStatement s = connection.prepareStatement("""
                SELECT t.from_state, t.to_state, t.note, t.accepted_until::text,
                       t.occurred_at::text, coalesce(p.display_name, 'unknown')
                  FROM finding_transition t
                  LEFT JOIN principal p ON p.id = t.actor_id
                 WHERE t.finding_id = ?
                 ORDER BY t.occurred_at DESC
                 LIMIT 50
                """)) {
            s.setObject(1, findingId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(new Entry(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                            r.getString(5), r.getString(6)));
                }
            }
        }
        return out;
    }

    /** Refused because the caller may not, or because the move is not one the lifecycle allows. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message);
        }
    }

    /**
     * Performs a transition.
     *
     * @param note        why, required — a move nobody explained teaches the next reader nothing
     * @param untilDate   the acceptance expiry, required for and only used by ACCEPTED_RISK
     * @return the state the finding is now in
     */
    public String transition(Principal principal, UUID findingId, String to, String note,
            String untilDate) throws SQLException {
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.length() < 3) {
            throw new Refused("Say why in a few words. The note is what makes this reviewable later.");
        }
        String target = to == null ? "" : to.toUpperCase(Locale.ROOT);

        try (Connection connection = open(principal)) {
            connection.setAutoCommit(false);
            try {
                String from;
                boolean isSecret;
                UUID exceptionId;
                // FOR UPDATE, because two people pressing verify and reopen at the same moment must not
                // both succeed against the state each of them read.
                try (PreparedStatement s = connection.prepareStatement(
                        "SELECT lifecycle_state, finding_class, accepted_under_exception_id, scope_node_id, "
                        + "scope_ancestor_path, scope_node_type_id, scope_criticality_id, "
                        + "scope_hierarchy_ver FROM finding WHERE id = ? FOR UPDATE")) {
                    s.setObject(1, findingId);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) {
                            throw new Refused("That finding is not available.");
                        }
                        from = r.getString(1);
                        isSecret = "SECRET".equals(r.getString(2));
                        exceptionId = r.getObject(3, UUID.class);
                    }
                }

                Move move = transitionsFrom(from, isSecret).stream()
                        .filter(m -> m.to().equals(target)).findFirst().orElse(null);
                if (move == null) {
                    throw new Refused("A finding that is " + label(from).toLowerCase(Locale.ROOT)
                            + " cannot move to " + label(target).toLowerCase(Locale.ROOT) + ".");
                }
                if (!move.permitted()) {
                    throw new Refused(move.reason());
                }
                if (!principal.holds(move.permission())) {
                    throw new Refused("You do not hold " + move.permission() + ".");
                }

                LocalDate until = null;
                if (move.needsDate()) {
                    if (untilDate == null || untilDate.isBlank()) {
                        throw new Refused("An acceptance needs a date it runs out. Without one it is "
                                + "not an acceptance, it is a decision to stop looking.");
                    }
                    try {
                        until = LocalDate.parse(untilDate.trim());
                    } catch (RuntimeException e) {
                        throw new Refused("That is not a date the system could read.");
                    }
                    // UTC, matching how the platform stores time. This is the friendly message, not
                    // the control: `risk_exception` refuses `expires_at <= requested_at` outright, so a
                    // tenant an hour the other side of midnight gets a clear sentence here or a
                    // constraint violation there, never an acceptance that expired before it began.
                    if (until.isBefore(LocalDate.now(java.time.ZoneOffset.UTC))) {
                        throw new Refused("The date has already passed.");
                    }
                }

                switch (target) {
                    case "FIXED" -> claim(connection, principal, findingId, trimmed);
                    case "CLOSED" -> close(connection, principal, findingId);
                    case "REOPEN" -> reopen(connection, principal, findingId, from, exceptionId);
                    case "ACCEPTANCE_REQUESTED" ->
                            ask(connection, principal, findingId, until, trimmed);
                    case "ACCEPTED_RISK" -> approve(connection, principal, findingId, exceptionId);
                    case "OPEN" -> withdraw(connection, principal, findingId, exceptionId, trimmed,
                            "ACCEPTANCE_REQUESTED".equals(from));
                    default -> throw new Refused("Unknown target state.");
                }

                try (PreparedStatement s = connection.prepareStatement("""
                        INSERT INTO finding_transition
                            (tenant_id, finding_id, from_state, to_state, note, accepted_until, actor_id)
                        VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?)
                        """)) {
                    s.setObject(1, findingId);
                    s.setString(2, from);
                    s.setString(3, target);
                    s.setString(4, trimmed);
                    if (until == null) {
                        s.setNull(5, java.sql.Types.DATE);
                    } else {
                        s.setObject(5, until);
                    }
                    s.setObject(6, principal.principalId());
                    s.executeUpdate();
                }
                // Beside the transition log, not instead of it. The log is the finding's own history,
                // readable on the page; the audit chain is tamper-evident and covers every aggregate
                // uniformly, which is what DOC-14 asks of an investigation that does not know in
                // advance which record it is looking for.
                audit.domainChange(connection, principal, "finding",
                        "REOPEN".equals(target)
                                ? aspm.kernel.audit.contract.DomainChangeKind.REOPENED
                                : aspm.kernel.audit.contract.DomainChangeKind.TRANSITIONED,
                        findingId, aspm.app.audit.AuditScopes.ofFinding(connection, findingId),
                        java.util.Map.of("from_state", from,
                                "to_state", target,
                                "note_characters", Integer.valueOf(trimmed.length()),
                                "accepted_until", until == null ? "" : until.toString()));
                connection.commit();
                return target;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
            // No `finally { setAutoCommit(true) }`. It was there to hand a connection back the way it
            // was found, which mattered while this class borrowed one and returned it mid-request;
            // the connection is now a unit of work that ends at close, and leaving the transaction
            // would discard the transaction-local tenant. TenantConnections refuses it for that
            // reason, so keeping the line would have thrown from a finally block — hiding whatever
            // the transition was really failing on.
        }
    }

    /** OPEN or REOPEN → FIXED. Writes the claim columns, which are what FIXED means. */
    private void claim(Connection connection, Principal principal, UUID id, String note)
            throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("""
                UPDATE finding
                   SET lifecycle_state = 'FIXED', state = 'OPEN',
                       remediation_claimed_at = now(), remediation_claimed_by = ?,
                       remediation_note = ?, updated_at = now(), updated_by = ?,
                       -- The version moves with the state.
                       --
                       -- It did not, and that was a defect wider than the AI ledger
                       -- that exposed it: AssessmentService.updateFinding guards on
                       -- row_version, so an edit form opened before a transition
                       -- still applied afterwards — two write paths on one aggregate
                       -- and only one of them participating in the optimistic lock.
                       row_version = row_version + 1
                 WHERE id = ?
                """)) {
            s.setObject(1, principal.principalId());
            s.setString(2, note);
            s.setObject(3, principal.principalId());
            s.setObject(4, id);
            s.executeUpdate();
        }
    }

    /**
     * FIXED → CLOSED. The verifier is recorded because the schema requires it for FIXED_VERIFIED, and
     * the schema requires it because a closure nobody signed is indistinguishable from a closure that
     * was never checked.
     */
    private void close(Connection connection, Principal principal, UUID id) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("""
                UPDATE finding
                   SET lifecycle_state = 'CLOSED', state = 'CLOSED',
                       closure_reason = 'FIXED_VERIFIED', closed_at = now(),
                       closure_verified_by = ?, closure_verification_method = 'MANUAL_RETEST',
                       updated_at = now(), updated_by = ?,
                       -- The version moves with the state.
                       --
                       -- It did not, and that was a defect wider than the AI ledger
                       -- that exposed it: AssessmentService.updateFinding guards on
                       -- row_version, so an edit form opened before a transition
                       -- still applied afterwards — two write paths on one aggregate
                       -- and only one of them participating in the optimistic lock.
                       row_version = row_version + 1
                 WHERE id = ?
                """)) {
            s.setObject(1, principal.principalId());
            s.setObject(2, principal.principalId());
            s.setObject(3, id);
            s.executeUpdate();
        }
    }

    /**
     * → REOPEN. Clears the closure pair together, because a CHECK ties {@code closed_at} and
     * {@code closure_reason} to each other and clearing one alone is refused.
     *
     * <p>{@code recurrence_count} rises only when the previous state was CLOSED. A claim that failed
     * its retest never went away, so counting it as a recurrence would inflate the one number that is
     * supposed to mean "this weakness came back after we confirmed it was gone".
     */
    private void reopen(Connection connection, Principal principal, UUID id, String from,
            UUID exceptionId) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("""
                UPDATE finding
                   SET lifecycle_state = 'REOPEN', state = 'OPEN',
                       closure_reason = NULL, closed_at = NULL,
                       closure_verified_by = NULL, closure_verification_method = NULL,
                       accepted_under_exception_id = NULL,
                       risk_accepted_until = NULL, risk_accepted_reason = NULL,
                       remediation_claimed_at = NULL, remediation_claimed_by = NULL,
                       recurrence_count = recurrence_count + CASE WHEN ? THEN 1 ELSE 0 END,
                       updated_at = now(), updated_by = ?,
                       -- The version moves with the state.
                       --
                       -- It did not, and that was a defect wider than the AI ledger
                       -- that exposed it: AssessmentService.updateFinding guards on
                       -- row_version, so an edit form opened before a transition
                       -- still applied afterwards — two write paths on one aggregate
                       -- and only one of them participating in the optimistic lock.
                       row_version = row_version + 1
                 WHERE id = ?
                """)) {
            s.setBoolean(1, "CLOSED".equals(from));
            s.setObject(2, principal.principalId());
            s.setObject(3, id);
            s.executeUpdate();
        }
        resolve(connection, principal, exceptionId, "REVOKED", "The finding was reopened.");
    }

    /**
     * → ACCEPTED_RISK. Creates the exception the constraint requires, then points the finding at it.
     *
     * <p>{@code max_duration_days} is derived from the date rather than asked for, because two fields
     * that must agree are two fields that will not. {@code expires_at} is the end of the chosen day, so
     * "accepted until the 30th" includes the 30th.
     */
    private void ask(Connection connection, Principal principal, UUID id, LocalDate until,
            String note) throws SQLException {
        UUID exceptionId;
        try (PreparedStatement s = connection.prepareStatement("""
                INSERT INTO risk_exception
                    (tenant_id, subject_kind, subject_id, subject_finding_class, state,
                     requested_by, requested_at, expires_at, max_duration_days,
                     step_up_authenticated, resolution_reason, created_by, updated_by,
                     scope_node_id, scope_ancestor_path, scope_node_type_id, scope_criticality_id,
                     scope_hierarchy_ver, scope_resolved_at)
                SELECT current_tenant_id(), 'FINDING', f.id, f.finding_class, 'REQUESTED',
                       ?, now(), (?::date + interval '1 day'),
                       greatest(1, (?::date - current_date) + 1),
                       true, NULL, ?, ?,
                       f.scope_node_id, f.scope_ancestor_path, f.scope_node_type_id,
                       f.scope_criticality_id, f.scope_hierarchy_ver, now()
                  FROM finding f
                 WHERE f.id = ?
                RETURNING id
                """)) {
            s.setObject(1, principal.principalId());
            s.setObject(2, until);
            s.setObject(3, until);
            s.setObject(4, principal.principalId());
            s.setObject(5, principal.principalId());
            s.setObject(6, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    throw new Refused("The request could not be recorded, so nothing was asked for.");
                }
                exceptionId = r.getObject(1, UUID.class);
            }
        }
        // The finding is linked to the request and stays OPEN. `risk_accepted_until` is deliberately
        // left empty: the proposed date lives on the exception until somebody approves, because a
        // finding advertising a date nobody agreed to is a falsehood the vulnerability list filters on.
        try (PreparedStatement s = connection.prepareStatement("""
                UPDATE finding
                   SET lifecycle_state = 'ACCEPTANCE_REQUESTED', state = 'OPEN',
                       closure_reason = NULL, closed_at = NULL,
                       accepted_under_exception_id = ?, risk_accepted_until = NULL,
                       risk_accepted_reason = ?, closure_verified_by = NULL,
                       closure_verification_method = NULL,
                       updated_at = now(), updated_by = ?,
                       -- The version moves with the state.
                       --
                       -- It did not, and that was a defect wider than the AI ledger
                       -- that exposed it: AssessmentService.updateFinding guards on
                       -- row_version, so an edit form opened before a transition
                       -- still applied afterwards — two write paths on one aggregate
                       -- and only one of them participating in the optimistic lock.
                       row_version = row_version + 1
                 WHERE id = ?
                """)) {
            s.setObject(1, exceptionId);
            s.setString(2, note);
            s.setObject(3, principal.principalId());
            s.setObject(4, id);
            s.executeUpdate();
        }
    }

    /**
     * ACCEPTANCE_REQUESTED → ACCEPTED_RISK. A second person agrees, and the date they agreed to is the
     * one already on the request rather than one they retype — two fields that must match are two
     * fields that will not.
     */
    private void approve(Connection connection, Principal principal, UUID id, UUID exceptionId)
            throws SQLException {
        if (exceptionId == null) {
            throw new Refused("There is no acceptance request on this finding to approve.");
        }
        // Refused here, in a sentence, before `assert_exception_approver_differs` refuses it as
        // INV-VUL-26. The trigger is the control; this is the explanation.
        try (PreparedStatement s = connection.prepareStatement(
                "SELECT requested_by FROM risk_exception WHERE id = ?")) {
            s.setObject(1, exceptionId);
            try (ResultSet r = s.executeQuery()) {
                if (r.next() && principal.principalId().equals(r.getObject(1, UUID.class))) {
                    throw new Refused("You asked for this acceptance, so you cannot approve it. "
                            + "A second approver is required (INV-VUL-26) — one person accepting a "
                            + "known weakness is not an exception process.");
                }
            }
        }
        try (PreparedStatement s = connection.prepareStatement("""
                UPDATE risk_exception
                   SET state = 'ACTIVE', approved_by = ?, approved_at = now(),
                       step_up_authenticated = true, updated_at = now(), updated_by = ?,
                       -- The version moves with the state.
                       --
                       -- It did not, and that was a defect wider than the AI ledger
                       -- that exposed it: AssessmentService.updateFinding guards on
                       -- row_version, so an edit form opened before a transition
                       -- still applied afterwards — two write paths on one aggregate
                       -- and only one of them participating in the optimistic lock.
                       row_version = row_version + 1
                 WHERE id = ? AND state = 'REQUESTED'
                """)) {
            s.setObject(1, principal.principalId());
            s.setObject(2, principal.principalId());
            s.setObject(3, exceptionId);
            if (s.executeUpdate() == 0) {
                throw new Refused("That request is no longer awaiting approval.");
            }
        }
        try (PreparedStatement s = connection.prepareStatement("""
                UPDATE finding f
                   SET lifecycle_state = 'ACCEPTED_RISK', state = 'CLOSED',
                       closure_reason = 'RISK_ACCEPTED', closed_at = now(),
                       -- One day back off `expires_at`. The exception expires at the START of the day
                       -- AFTER the chosen date, so that "accepted until the 31st" includes the 31st;
                       -- copying `expires_at::date` straight across reported the acceptance as running
                       -- to the 1st, a day later than anybody agreed to. Found by reading the verified
                       -- row rather than the code.
                       risk_accepted_until = (e.expires_at - interval '1 day')::date,
                       updated_at = now(), updated_by = ?,
                       -- The version moves with the state, as in every other transition here.
                       row_version = f.row_version + 1
                  FROM risk_exception e
                 WHERE f.id = ? AND e.id = f.accepted_under_exception_id
                """)) {
            s.setObject(1, principal.principalId());
            s.setObject(2, id);
            s.executeUpdate();
        }
    }

    /**
     * ACCEPTED_RISK → OPEN (withdrawn) or ACCEPTANCE_REQUESTED → OPEN (rejected). The exception is
     * resolved, never deleted: somebody asked, and that happened whatever the answer was.
     */
    private void withdraw(Connection connection, Principal principal, UUID id, UUID exceptionId,
            String note, boolean wasOnlyRequested) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("""
                UPDATE finding
                   SET lifecycle_state = 'OPEN', state = 'OPEN',
                       closure_reason = NULL, closed_at = NULL,
                       accepted_under_exception_id = NULL, risk_accepted_until = NULL,
                       risk_accepted_reason = NULL, updated_at = now(), updated_by = ?,
                       -- The version moves with the state.
                       --
                       -- It did not, and that was a defect wider than the AI ledger
                       -- that exposed it: AssessmentService.updateFinding guards on
                       -- row_version, so an edit form opened before a transition
                       -- still applied afterwards — two write paths on one aggregate
                       -- and only one of them participating in the optimistic lock.
                       row_version = row_version + 1
                 WHERE id = ?
                """)) {
            s.setObject(1, principal.principalId());
            s.setObject(2, id);
            s.executeUpdate();
        }
        resolve(connection, principal, exceptionId, wasOnlyRequested ? "REJECTED" : "REVOKED", note);
    }

    private void resolve(Connection connection, Principal principal, UUID exceptionId, String state,
            String why) throws SQLException {
        if (exceptionId == null) {
            return;
        }
        try (PreparedStatement s = connection.prepareStatement("""
                UPDATE risk_exception
                   SET state = ?, resolved_at = now(), resolution_reason = ?,
                       updated_at = now(), updated_by = ?,
                       -- The version moves with the state.
                       --
                       -- It did not, and that was a defect wider than the AI ledger
                       -- that exposed it: AssessmentService.updateFinding guards on
                       -- row_version, so an edit form opened before a transition
                       -- still applied afterwards — two write paths on one aggregate
                       -- and only one of them participating in the optimistic lock.
                       row_version = row_version + 1
                 WHERE id = ? AND state IN ('REQUESTED', 'APPROVED', 'ACTIVE')
                """)) {
            s.setString(1, state);
            s.setString(2, why);
            s.setObject(3, principal.principalId());
            s.setObject(4, exceptionId);
            s.executeUpdate();
        }
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
