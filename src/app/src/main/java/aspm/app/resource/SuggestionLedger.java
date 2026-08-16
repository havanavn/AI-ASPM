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
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The suggestion ledger, and the human decision that ends a suggestion's life. ADR-005.
 *
 * <h2>The rule this class exists to make structural</h2>
 *
 * <p>AI writes here and nowhere else. Promotion into the system of record is a human action, taken by
 * a named person with a permission, and it is the only path from a model's output to something the
 * organization asserts. That is not a policy this class enforces by being careful — it is the shape
 * of the code: agents call {@link #propose} and have no other write, and {@link #decide} is reachable
 * only from an authenticated route carrying {@code aic.suggestion.promote}.
 *
 * <h2>Grounding is required, not encouraged</h2>
 *
 * <p>Every suggestion carries the records it rests on and the identity of what produced it. The schema
 * makes both NOT NULL, so a suggestion that cannot say why it exists cannot be stored. A reviewer's
 * question is always "why does it say that", and the answer must never be "the model said so".
 *
 * <h2>Rules and models are the same shape</h2>
 *
 * <p>{@code model_identity} distinguishes {@code deterministic-rules/v1} from a real provider and
 * model. Both write the same row, both are reviewed the same way, and a promoted suggestion says
 * forever which one produced it. Shipping the rules first proves the whole path — proposal, grounding,
 * decision, audit — without a provider, a budget, or a decision to send anything to a third party.
 */
public final class SuggestionLedger {

    /** Reading the queue. Goes with reading findings — a reviewer who cannot see one cannot judge it. */
    public static final String READ = "aic.suggestion.read";

    /** Putting a suggestion into the record. Restricted: this is the boundary ADR-005 is about. */
    public static final String PROMOTE = "aic.suggestion.promote";

    /**
     * One suggestion, as a reviewer sees it.
     *
     * @param freshness whether the subject has changed since this was generated. Three values, not
     *     two: {@code CURRENT}, {@code STALE}, and {@code UNKNOWN} for the rows written before the
     *     ledger recorded a subject version. Product principle 1 — not-measured is its own answer
     *     and must not be rendered as clean.
     */
    public record Suggestion(String id, String kind, String subjectKind, String subjectId,
            String subjectLabel, String headline, String detail, String recommendation,
            List<String> grounding, String modelIdentity, String promptVersion,
            String confidenceBand, String state, String generatedAt, String promotedBy,
            String rejectedReason, String freshness) {
    }

    /** What an agent proposes. The ledger fills in state, timing and identity columns. */
    public record Draft(String kind, String subjectKind, UUID subjectId, String headline,
            String detail, String recommendation, List<String> grounding, String confidenceBand) {
    }

    /**
     * One capability, as the catalogue holds it, with what its output has been worth.
     *
     * <p>{@code promoted} and {@code rejected} are the only measure of a capability that exists.
     * Without them, enabling or disabling one is a decision taken on the strength of how the idea
     * sounds — and the switch in ADR-044 is there precisely so that a capability which produces
     * noise can be turned off by somebody who can point at why.
     *
     * <p>No acceptance RATE is computed here. Four decisions is not a rate, and a percentage
     * computed over four decisions is a number that looks like evidence; the caller gets the counts
     * and the interface says "3 of 4" until there are enough to say more.
     */
    public record Capability(String code, String suggestionKind, String subjectKind, String surface,
            String dataCategory, boolean enabled, int maxPerRun, long pending,
            long promoted, long rejected, long withdrawn, String lastDecidedAt) {
    }

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public SuggestionLedger(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ==============================================================================================

    /** The capabilities this tenant has, with how many suggestions of each are waiting. */
    public List<Capability> capabilities(Principal principal) throws SQLException {
        List<Capability> out = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT c.code, c.suggestion_kind, c.subject_kind, c.surface,
                               c.data_category, c.enabled, c.max_per_run,
                               -- One pass over the ledger per capability rather than four
                               -- correlated subqueries: the counts answer one question and are
                               -- read together, and four scans of the same rows would drift apart
                               -- the first time somebody adds a fifth.
                               coalesce(o.pending, 0), coalesce(o.promoted, 0),
                               coalesce(o.rejected, 0), coalesce(o.withdrawn, 0),
                               to_char(o.last_decided_at, 'YYYY-MM-DD')
                          FROM ai_capability c
                          LEFT JOIN (
                              SELECT s.suggestion_kind,
                                     count(*) FILTER (WHERE s.state = 'PENDING')   AS pending,
                                     count(*) FILTER (WHERE s.state = 'PROMOTED')  AS promoted,
                                     count(*) FILTER (WHERE s.state = 'REJECTED')  AS rejected,
                                     count(*) FILTER (WHERE s.state = 'WITHDRAWN') AS withdrawn,
                                     max(s.promoted_at) AS last_decided_at
                                FROM ai_suggestion s
                               GROUP BY s.suggestion_kind
                          ) o ON o.suggestion_kind = c.suggestion_kind
                         ORDER BY c.code
                        """)) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.add(new Capability(r.getString(1), r.getString(2), r.getString(3),
                            r.getString(4), r.getString(5), r.getBoolean(6), r.getInt(7),
                            r.getLong(8), r.getLong(9), r.getLong(10), r.getLong(11),
                            r.getString(12)));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * The capabilities declared for one dashboard.
     *
     * <p>Drives the per-dashboard analyse button. Returns them whether enabled or not, so the button
     * can say what it WOULD run and what each may read — a person deciding whether to press it needs
     * that before pressing, not after.
     */
    public List<Capability> capabilitiesFor(Principal principal, String surface)
            throws SQLException {
        List<Capability> out = new ArrayList<>();
        for (Capability c : capabilities(principal)) {
            if (c.surface().equals(surface)) {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }

    /** Turns one capability on or off. Nothing runs until somebody does this (ADR-044). */
    public boolean setEnabled(Principal principal, String code, boolean enabled)
            throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE ai_capability SET enabled = ?, updated_at = now(), updated_by = ?
                         WHERE code = ?
                        """)) {
            statement.setBoolean(1, enabled);
            statement.setObject(2, principal.principalId());
            statement.setString(3, code);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                // ADR-044 defers the AI capabilities and leaves the switch. Turning one on is the act
                // that starts a model reading record content, so it is the event that dates every
                // suggestion that follows.
                audit.event(connection, principal,
                        aspm.kernel.audit.contract.AuditEventType.AI_CONFIGURATION_CHANGED,
                        null, null, java.util.Map.of("capability", code == null ? "" : code,
                                "enabled", Boolean.valueOf(enabled)));
            }
            connection.commit();
            return applied;
        }
    }

    /**
     * The review queue.
     *
     * <p>Scoped through the finding a suggestion is about, not by a scope column of its own. A
     * suggestion has no independent existence — it is a claim about a record — so the caller sees
     * exactly the suggestions whose subject they may already see. Giving the ledger its own scope
     * column would create a second, quieter answer to the same question (SEC-AUZ-016).
     */
    public List<Suggestion> pending(Principal principal, String kind, String subjectId, int limit)
            throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        String kindClause = kind == null || kind.isBlank() ? "" : " AND s.suggestion_kind = ?";
        String subjectClause = subjectId == null || subjectId.isBlank() ? ""
                : " AND s.subject_id = ?::uuid";
        String sql = """
                SELECT s.id::text, s.suggestion_kind, s.subject_kind, s.subject_id::text,
                       coalesce(f.title, a.display_name, n.name, q.title, '(subject withdrawn)'),
                       s.content ->> 'headline', s.content ->> 'detail',
                       s.content ->> 'recommendation',
                       -- Unpacked by Postgres rather than parsed here: `grounding_refs` is a JSON
                       -- ARRAY and the platform's Json helper reads objects. Asking the database for
                       -- a text array is one call and avoids a second, weaker JSON reader.
                       ARRAY(SELECT jsonb_array_elements_text(s.grounding_refs)),
                       s.model_identity, s.prompt_version,
                       s.confidence_band, s.state,
                       to_char(s.generated_at, 'YYYY-MM-DD HH24:MI'),
                       coalesce(p.display_name, p.username), s.rejected_reason,
                       -- Is this still about the record it describes?
                       --
                       -- Three answers, not two. UNKNOWN is for the rows written before the ledger
                       -- recorded a subject version, and for a subject this query cannot resolve —
                       -- rendering either of those as CURRENT would be the platform asserting
                       -- freshness it has not measured (PP-1). The comparison is against the
                       -- subject's own row_version, which every write path increments, rather than
                       -- against age: a suggestion from last week about a record nobody touched is
                       -- current, and one from an hour ago about a record edited since is not.
                       CASE
                           WHEN s.subject_row_version IS NULL THEN 'UNKNOWN'
                           WHEN coalesce(f.row_version, a.row_version, n.row_version,
                                         q.row_version, x.row_version) IS NULL THEN 'UNKNOWN'
                           WHEN coalesce(f.row_version, a.row_version, n.row_version,
                                         q.row_version, x.row_version) > s.subject_row_version
                               THEN 'STALE'
                           ELSE 'CURRENT'
                       END
                  FROM ai_suggestion s
                  LEFT JOIN finding f ON f.id = s.subject_id AND s.subject_kind = 'FINDING'
                  LEFT JOIN asset a ON a.id = s.subject_id AND s.subject_kind = 'ASSET'
                  LEFT JOIN org_node n ON n.id = s.subject_id AND s.subject_kind = 'ORG_NODE'
                  LEFT JOIN assessment_request q ON q.id = s.subject_id
                       AND s.subject_kind = 'ASSESSMENT_REQUEST'
                  LEFT JOIN risk_exception x ON x.id = s.subject_id
                       AND s.subject_kind = 'RISK_EXCEPTION'
                  LEFT JOIN principal p ON p.id = s.promoted_by
                 WHERE s.state = 'PENDING'
                   -- Visible only through a subject the caller may see. A suggestion about a finding
                   -- outside their scope must not appear, and its headline would disclose the finding.
                   AND (s.subject_kind <> 'FINDING' OR EXISTS (
                        SELECT 1 FROM finding sf
                         WHERE sf.id = s.subject_id
                           AND sf.scope_node_id IN (SELECT descendant_id FROM org_closure
                                                     WHERE ancestor_id = ANY (?))))
                   %s%s
                 ORDER BY s.generated_at DESC
                 LIMIT %d
                """.formatted(kindClause, subjectClause, Math.max(1, Math.min(200, limit)));

        List<Suggestion> out = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setArray(index++, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            if (!kindClause.isEmpty()) {
                statement.setString(index++, kind);
            }
            if (!subjectClause.isEmpty()) {
                statement.setString(index, subjectId);
            }
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.add(new Suggestion(r.getString(1), r.getString(2), r.getString(3),
                            r.getString(4), r.getString(5), r.getString(6), r.getString(7),
                            r.getString(8), textArray(r, 9), r.getString(10),
                            r.getString(11), r.getString(12), r.getString(13), r.getString(14),
                            r.getString(15), r.getString(16), r.getString(17)));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Records a suggestion. The ONLY write an agent has.
     *
     * @return true where it was stored; false where an identical pending suggestion already exists,
     *     so a second run does not stack duplicates in front of a reviewer
     */
    public boolean propose(Connection connection, Draft draft, String modelIdentity,
            String promptVersion) throws SQLException {
        Integer version = subjectVersion(connection, draft.subjectKind(), draft.subjectId());

        // A pending suggestion for this subject and kind already exists. Two cases, and they are
        // opposite decisions:
        //
        //   it was generated against the CURRENT state  -> this run adds nothing; keep the original,
        //                                                  because re-writing it would move it to
        //                                                  the top of a reviewer's queue and reset
        //                                                  nothing else.
        //   it was generated against an OLDER state     -> withdraw it and write this one. Without
        //                                                  this branch the deduplication guard keeps
        //                                                  the stale claim in front of the reviewer
        //                                                  forever and the current one can never be
        //                                                  written, which is the failure mode that
        //                                                  makes an ageing queue worse than none.
        try (PreparedStatement exists = connection.prepareStatement("""
                SELECT id, subject_row_version FROM ai_suggestion
                 WHERE suggestion_kind = ? AND subject_id = ? AND state = 'PENDING'
                """)) {
            exists.setString(1, draft.kind());
            exists.setObject(2, draft.subjectId());
            try (ResultSet r = exists.executeQuery()) {
                if (r.next()) {
                    UUID existing = r.getObject(1, UUID.class);
                    int recorded = r.getInt(2);
                    boolean unknownVersion = r.wasNull();
                    // Unknown counts as superseded here, and deliberately: a suggestion that cannot
                    // say what it was about should not outrank one that can.
                    if (!unknownVersion && version != null && version.intValue() <= recorded) {
                        return false;
                    }
                    withdraw(connection, existing, unknownVersion
                            ? "the subject version it was generated against was never recorded"
                            : "the subject has changed since it was generated");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ai_suggestion (tenant_id, suggestion_kind, subject_kind, subject_id,
                                           content, grounding_refs, model_identity, prompt_version,
                                           confidence_band, subject_row_version)
                VALUES (current_tenant_id(), ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                """)) {
            statement.setString(1, draft.kind());
            statement.setString(2, draft.subjectKind());
            statement.setObject(3, draft.subjectId());
            statement.setString(4, aspm.app.runtime.Json.write(java.util.Map.of(
                    "headline", draft.headline() == null ? "" : draft.headline(),
                    "detail", draft.detail() == null ? "" : draft.detail(),
                    "recommendation",
                    draft.recommendation() == null ? "" : draft.recommendation())));
            statement.setString(5, aspm.app.runtime.Json.write(draft.grounding()));
            statement.setString(6, modelIdentity);
            statement.setString(7, promptVersion);
            // A band, never a percentage. ADR-038 forbids a generated number, and a confidence figure
            // is the most persuasive number a model can emit and the least checkable.
            statement.setString(8, draft.confidenceBand());
            statement.setObject(9, version, java.sql.Types.INTEGER);
            statement.executeUpdate();
        }
        return true;
    }

    /**
     * The subject's own version counter, or null where this ledger cannot read one.
     *
     * <p>Null rather than a default. A subject kind nobody has taught this method about must produce
     * a suggestion that says its freshness is unknown, not one that claims to be current — the
     * alternative is that adding a sixth subject kind silently makes every suggestion about it look
     * fresh forever.
     */
    private static Integer subjectVersion(Connection connection, String subjectKind, UUID subjectId)
            throws SQLException {
        String table = switch (subjectKind == null ? "" : subjectKind) {
            case "FINDING" -> "finding";
            case "ASSET" -> "asset";
            case "ORG_NODE" -> "org_node";
            case "ASSESSMENT_REQUEST" -> "assessment_request";
            case "RISK_EXCEPTION" -> "risk_exception";
            default -> null;
        };
        if (table == null || subjectId == null) {
            return null;
        }
        // The table name comes from the switch above and never from a parameter: an identifier
        // cannot be bound, so the only safe source is a closed set written here.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT row_version FROM " + table + " WHERE id = ?")) {
            statement.setObject(1, subjectId);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? Integer.valueOf(r.getInt(1)) : null;
            }
        }
    }

    /**
     * Retires a suggestion that stopped applying. Not a decision — nobody judged it.
     *
     * <p>{@code WITHDRAWN} was in the state check constraint from the beginning with no writer. It
     * means exactly this, and a fifth state for "expired" would be two names for one fact.
     */
    private static void withdraw(Connection connection, UUID id, String why) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ai_suggestion
                   SET state = 'WITHDRAWN', rejected_reason = ?, row_version = row_version + 1
                 WHERE id = ? AND state = 'PENDING'
                """)) {
            statement.setString(1, why);
            statement.setObject(2, id);
            statement.executeUpdate();
        }
    }

    /**
     * Whether a suggestion is still pending and no longer about the current record.
     *
     * <p>Asked only after a promotion matched nothing, to tell "gone or not yours" from "still here
     * and out of date". It is scoped exactly as the queue is — through the subject — so it cannot
     * answer for a suggestion the caller could not see in the first place, which would make it a
     * quieter way to ask whether an identifier exists.
     */
    public boolean isStale(Principal principal, UUID id) throws SQLException {
        if (id == null) {
            return false;
        }
        for (Suggestion candidate : pending(principal, null, null, 200)) {
            if (candidate.id().equals(id.toString())) {
                return !"CURRENT".equals(candidate.freshness());
            }
        }
        return false;
    }

    /**
     * The human decision. Accepting or rejecting, both attributed.
     *
     * <p>Deliberately does NOT apply the suggestion to the record. Promotion here marks that a person
     * accepted it; the change it implies is made through that record's own write path, with that
     * path's own permission and validation. Letting the ledger write into a finding would give AI
     * output a second route into the system of record — the exact thing ADR-005 forbids — dressed as
     * a convenience.
     *
     * @param reason required on rejection; the schema refuses a rejection without one
     */
    public boolean decide(Principal principal, UUID id, boolean promote, String reason)
            throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(promote ? """
                        UPDATE ai_suggestion s
                           SET state = 'PROMOTED', promoted_by = ?, promoted_at = now(),
                               row_version = row_version + 1
                         WHERE s.id = ? AND s.state = 'PENDING'
                           -- *** A STALE SUGGESTION CANNOT BE PROMOTED. ***
                           --
                           -- Promotion is the audited human act that lets AI output into the system
                           -- of record (ADR-005), and what it records is that a named person agreed
                           -- with a claim about a record. If the record has moved since, the person
                           -- is agreeing with a description of a state that no longer exists, and
                           -- the trail would carry their name against it.
                           --
                           -- Rejection is deliberately NOT guarded the same way: a reviewer must
                           -- always be able to clear something off the queue, and refusing to let
                           -- them dismiss a stale item would leave it there permanently.
                           --
                           -- The guard is in the statement rather than in a preceding read, so a
                           -- change committed between the check and the write cannot slip through.
                           AND s.subject_row_version IS NOT NULL
                           AND s.subject_row_version >= coalesce(
                                   (SELECT f.row_version FROM finding f
                                     WHERE f.id = s.subject_id AND s.subject_kind = 'FINDING'),
                                   (SELECT a.row_version FROM asset a
                                     WHERE a.id = s.subject_id AND s.subject_kind = 'ASSET'),
                                   (SELECT n.row_version FROM org_node n
                                     WHERE n.id = s.subject_id AND s.subject_kind = 'ORG_NODE'),
                                   (SELECT q.row_version FROM assessment_request q
                                     WHERE q.id = s.subject_id
                                       AND s.subject_kind = 'ASSESSMENT_REQUEST'),
                                   (SELECT x.row_version FROM risk_exception x
                                     WHERE x.id = s.subject_id
                                       AND s.subject_kind = 'RISK_EXCEPTION'),
                                   -- No subject resolved: it was withdrawn or is of a kind this
                                   -- ledger cannot read. Either way the claim cannot be checked, so
                                   -- the comparison fails and promotion is refused.
                                   2147483647)
                        """ : """
                        UPDATE ai_suggestion
                           SET state = 'REJECTED', rejected_reason = ?,
                               row_version = row_version + 1
                         WHERE id = ? AND state = 'PENDING'
                        """)) {
            if (promote) {
                statement.setObject(1, principal.principalId());
            } else {
                String trimmed = reason == null || reason.isBlank()
                        ? "rejected without a stated reason" : reason.strip();
                statement.setString(1, trimmed);
            }
            statement.setObject(2, id);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                // ADR-005 makes promotion the audited human action that lets AI output into the
                // system of record. Recording it is therefore not incidental to the decision: it is
                // the difference between an accepted suggestion and an unattributed change.
                audit.event(connection, principal, promote
                                ? aspm.kernel.audit.contract.AuditEventType.AI_SUGGESTION_PROMOTED
                                : aspm.kernel.audit.contract.AuditEventType.AI_SUGGESTION_DISMISSED,
                        id, null, java.util.Map.of("suggestion_id", id.toString(),
                                "reason", promote ? "" : (reason == null || reason.isBlank()
                                        ? "rejected without a stated reason" : reason.strip())));
            }
            connection.commit();
            return applied;
        }
    }

    // ==============================================================================================

    /** A SQL text array as a list. */
    private static List<String> textArray(ResultSet r, int column) throws SQLException {
        java.sql.Array array = r.getArray(column);
        if (array == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : (Object[]) array.getArray()) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return List.copyOf(out);
    }

    Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
