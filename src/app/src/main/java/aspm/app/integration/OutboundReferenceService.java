package aspm.app.integration;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The one-way reference from a finding to an item in an external tracker. DOC-21 §10, ADR-040.
 * {@code PRD-CON-038}, {@code PRD-CON-042}, {@code PRD-CON-043}, {@code PRD-CON-044}, {@code PRD-CON-045}.
 *
 * <p>A person asks for a reference; a row exists from that moment as {@code PENDING}; the worker asks
 * the tracker and fills in the external identity. What the tracker later says is recorded on the row and,
 * where it disagrees with the platform record, surfaced as a divergence for a person. Nothing in this
 * class reads external state INTO the finding: the only write that touches a finding from here is none.
 *
 * <p>Scope is checked twice before anything is queued: the caller must see the finding (their scope
 * closure), and the connector's configured subtree must contain it ({@code PRD-CON-038}) — because the
 * tracker on the other side has no scope enforcement to compensate.
 */
public final class OutboundReferenceService {

    public static final String CREATE = "int.reference.create";

    public record Reference(UUID id, UUID connectorId, String connectorCode, String connectorName, String kind, String status,
            Optional<String> externalKey, Optional<String> externalUrl, Optional<String> externalState, Optional<Boolean> externalResolved,
            Optional<OffsetDateTime> lastObservedAt, Optional<String> divergenceKind, Optional<OffsetDateTime> divergenceDetectedAt,
            Optional<String> divergencePlatformState, Optional<String> failureDetail, OffsetDateTime createdAt, UUID createdBy,
            String createdByName, UUID subjectId) {
    }

    /** A divergence, with enough of the finding for the person who has to decide. */
    public record Divergence(Reference reference, UUID findingId, String findingTitle, String findingLifecycle, String findingSeverity,
            Optional<UUID> requestId) {
    }

    /** A connector a finding could still be sent to. */
    public record Offer(UUID connectorId, String code, String displayName, String kind) {
    }

    /** What the finding page shows: existing references, connectors still available, and whether this caller may add one. */
    public record View(List<Reference> references, List<Offer> offers, boolean mayCreate) {
    }

    private record Subject(String title, UUID scopeNodeId, String lifecycle) {
    }

    private final DataSource dataSource;
    private final aspm.app.audit.AuditTrail audit = new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public OutboundReferenceService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    // ==============================================================================================

    public Optional<View> forFinding(Principal principal, UUID findingId) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, principal)) {
            Optional<Subject> subject = visibleFinding(connection, principal, findingId);
            if (subject.isEmpty()) {
                return Optional.empty();
            }
            List<Reference> references = references(connection, "WHERE r.subject_kind = 'FINDING' AND r.subject_id = ? ORDER BY r.created_at DESC", findingId);
            List<Offer> offers = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT c.id, c.code, c.display_name, c.kind FROM connector c WHERE c.lifecycle_state = 'ACTIVE' "
                            + "AND (c.scope_node_id IS NULL OR ? IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = c.scope_node_id)) "
                            + "AND NOT EXISTS (SELECT 1 FROM outbound_reference r WHERE r.connector_id = c.id AND r.subject_kind = 'FINDING' "
                            + "AND r.subject_id = ? AND r.status <> 'FAILED') ORDER BY c.display_name")) {
                statement.setObject(1, subject.get().scopeNodeId());
                statement.setObject(2, findingId);
                try (ResultSet r = statement.executeQuery()) {
                    while (r.next()) {
                        offers.add(new Offer(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4)));
                    }
                }
            }
            return Optional.of(new View(references, offers, principal.holds(CREATE)));
        }
    }

    /** Queues the creation of a reference. {@code PRD-CON-038} is enforced here, before anything is queued. */
    public Reference create(Principal actor, UUID findingId, UUID connectorId) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Subject subject = visibleFinding(connection, actor, findingId)
                    .orElseThrow(() -> new IllegalArgumentException("no such finding"));
            String state;
            String connectorName;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT c.lifecycle_state, c.display_name, c.scope_node_id IS NULL OR ? IN (SELECT descendant_id FROM org_closure "
                            + "WHERE ancestor_id = c.scope_node_id) FROM connector c WHERE c.id = ?")) {
                statement.setObject(1, subject.scopeNodeId());
                statement.setObject(2, connectorId);
                try (ResultSet r = statement.executeQuery()) {
                    if (!r.next()) {
                        throw new IllegalArgumentException("no such connector");
                    }
                    state = r.getString(1);
                    connectorName = r.getString(2);
                    if (!r.getBoolean(3)) {
                        throw new IllegalArgumentException("this connector is configured for another part of the organization; the finding is "
                                + "outside its scope and will not be sent (PRD-CON-038)");
                    }
                }
            }
            if (!"ACTIVE".equals(state)) {
                throw new IllegalArgumentException("the connector is " + state.toLowerCase(java.util.Locale.ROOT) + "; only an active connector receives references");
            }
            UUID id;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO outbound_reference (tenant_id, subject_kind, subject_id, scope_node_id, connector_id, platform_state_at_creation, created_by) "
                            + "VALUES (?, 'FINDING', ?, ?, ?, ?, ?) RETURNING id")) {
                statement.setObject(1, actor.tenantId());
                statement.setObject(2, findingId);
                statement.setObject(3, subject.scopeNodeId());
                statement.setObject(4, connectorId);
                statement.setString(5, subject.lifecycle());
                statement.setObject(6, actor.principalId());
                try (ResultSet r = statement.executeQuery()) {
                    r.next();
                    id = r.getObject(1, UUID.class);
                }
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) {
                    throw new IllegalArgumentException("this finding already has a reference in " + connectorName);
                }
                throw e;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO outbound_operation (tenant_id, connector_id, reference_id, kind, requested_by) VALUES (?, ?, ?, 'CREATE_REFERENCE', ?)")) {
                statement.setObject(1, actor.tenantId());
                statement.setObject(2, connectorId);
                statement.setObject(3, id);
                statement.setObject(4, actor.principalId());
                statement.executeUpdate();
            }
            audit.domainChangeBy(connection, actor.principalId(), "outbound_reference", aspm.kernel.audit.contract.DomainChangeKind.CREATED, id,
                    subject.scopeNodeId(), Map.of("finding_id", findingId.toString(), "connector_id", connectorId.toString(),
                            "connector", connectorName, "content", "reference and scope-appropriate summary (PRD-CON-045)"));
            connection.commit();
            return references(connection, "WHERE r.id = ?", id).get(0);
        }
    }

    /** A failed reference is asked for again. The row is reused so the finding keeps one history. */
    public Reference retry(Principal actor, UUID referenceId) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Reference current = visibleReference(connection, actor, referenceId);
            if (!"FAILED".equals(current.status())) {
                throw new IllegalArgumentException("only a failed reference is retried");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE outbound_reference SET status = 'PENDING', failure_detail = NULL, updated_at = now() WHERE id = ?")) {
                statement.setObject(1, referenceId);
                statement.executeUpdate();
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) {
                    throw new IllegalArgumentException("this finding already has a live reference in that connector");
                }
                throw e;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO outbound_operation (tenant_id, connector_id, reference_id, kind, requested_by) VALUES (?, ?, ?, 'CREATE_REFERENCE', ?)")) {
                statement.setObject(1, actor.tenantId());
                statement.setObject(2, current.connectorId());
                statement.setObject(3, referenceId);
                statement.setObject(4, actor.principalId());
                statement.executeUpdate();
            }
            audit.domainChangeBy(connection, actor.principalId(), "outbound_reference", aspm.kernel.audit.contract.DomainChangeKind.UPDATED, referenceId,
                    null, Map.of("retried", true));
            connection.commit();
            return references(connection, "WHERE r.id = ?", referenceId).get(0);
        }
    }

    /** Open divergences on findings this caller can see, newest first. {@code PRD-CON-043}. */
    public List<Divergence> divergences(Principal principal) throws SQLException {
        List<Divergence> out = new ArrayList<>();
        try (Connection connection = TenantConnections.open(dataSource, principal)) {
            List<Reference> refs = references(connection, "WHERE r.divergence_detected_at IS NOT NULL AND r.divergence_resolved_at IS NULL "
                    + "AND r.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)) ORDER BY r.divergence_detected_at DESC LIMIT 200",
                    connection.createArrayOf("uuid", principal.scopeNodeIds().toArray()));
            for (Reference ref : refs) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT f.title, f.lifecycle_state, coalesce(s.code, 'UNRATED'), f.discovered_in_request_id FROM finding f "
                                + "LEFT JOIN severity_level s ON s.id = coalesce(f.effective_severity_id, f.reported_severity_id) WHERE f.id = ?")) {
                    statement.setObject(1, ref.subjectId());
                    try (ResultSet r = statement.executeQuery()) {
                        if (r.next()) {
                            out.add(new Divergence(ref, ref.subjectId(), r.getString(1), r.getString(2), r.getString(3),
                                    Optional.ofNullable(r.getObject(4, UUID.class))));
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * A person decides. The note is the decision and its reason; the finding is untouched — if it should
     * move, that is a finding transition, made where finding transitions are made and audited there.
     */
    public Reference resolveDivergence(Principal actor, UUID referenceId, String note) throws SQLException {
        if (note == null || note.strip().length() < 10) {
            throw new IllegalArgumentException("a resolution note of at least ten characters is required: what was decided, and why");
        }
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Reference current = visibleReference(connection, actor, referenceId);
            if (current.divergenceKind().isEmpty()) {
                throw new IllegalArgumentException("this reference has no open divergence");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE outbound_reference SET divergence_resolved_at = now(), divergence_resolved_by = ?, divergence_resolution_note = ?, "
                            + "updated_at = now() WHERE id = ? AND divergence_resolved_at IS NULL")) {
                statement.setObject(1, actor.principalId());
                statement.setString(2, note.strip());
                statement.setObject(3, referenceId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalArgumentException("the divergence was already resolved");
                }
            }
            audit.domainChangeBy(connection, actor.principalId(), "outbound_reference", aspm.kernel.audit.contract.DomainChangeKind.UPDATED, referenceId,
                    null, Map.of("divergence_resolved", current.divergenceKind().get(), "external_state", current.externalState().orElse(""),
                            "note", note.strip(), "finding_changed", false));
            connection.commit();
            return references(connection, "WHERE r.id = ?", referenceId).get(0);
        }
    }

    // ==============================================================================================

    private Reference visibleReference(Connection connection, Principal principal, UUID referenceId) throws SQLException {
        List<Reference> found = references(connection, "WHERE r.id = ?", referenceId);
        if (found.isEmpty() || visibleFinding(connection, principal, found.get(0).subjectId()).isEmpty()) {
            throw new IllegalArgumentException("no such reference");
        }
        return found.get(0);
    }

    private static Optional<Subject> visibleFinding(Connection connection, Principal principal, UUID findingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                // The same authorization rule as VulnerabilityQuery: the caller's scope closure, plus a finding
                // with no organization when the caller sees every root (an unscoped finding belongs to nobody in
                // particular, so it is shown to whoever sees everything and to nobody narrower).
                "SELECT f.title, f.scope_node_id, f.lifecycle_state FROM finding f WHERE f.id = ? "
                        + "AND (f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)) "
                        + "OR (f.scope_node_id IS NULL AND NOT EXISTS (SELECT 1 FROM org_node WHERE parent_id IS NULL AND id <> ALL (?))))")) {
            java.sql.Array scope = connection.createArrayOf("uuid", principal.scopeNodeIds().toArray());
            statement.setObject(1, findingId);
            statement.setArray(2, scope);
            statement.setArray(3, scope);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? Optional.of(new Subject(r.getString(1), r.getObject(2, UUID.class), r.getString(3))) : Optional.empty();
            }
        }
    }

    static List<Reference> references(Connection connection, String clause, Object parameter) throws SQLException {
        List<Reference> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT r.id, r.connector_id, c.code, c.display_name, c.kind, r.status, r.external_key, r.external_url, r.external_state, r.external_resolved, "
                        + "r.last_observed_at, r.divergence_kind, r.divergence_detected_at, r.divergence_platform_state, r.failure_detail, r.created_at, "
                        + "r.created_by, coalesce(p.display_name, p.username, '?'), r.subject_id "
                        + "FROM outbound_reference r JOIN connector c ON c.id = r.connector_id LEFT JOIN principal p ON p.id = r.created_by " + clause)) {
            if (parameter instanceof java.sql.Array array) {
                statement.setArray(1, array);
            } else if (parameter != null) {
                statement.setObject(1, parameter);
            }
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    Boolean resolved = (Boolean) r.getObject(10);
                    out.add(new Reference(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
                            Optional.ofNullable(r.getString(7)), Optional.ofNullable(r.getString(8)), Optional.ofNullable(r.getString(9)),
                            Optional.ofNullable(resolved), Optional.ofNullable(r.getObject(11, OffsetDateTime.class)), Optional.ofNullable(r.getString(12)),
                            Optional.ofNullable(r.getObject(13, OffsetDateTime.class)), Optional.ofNullable(r.getString(14)), Optional.ofNullable(r.getString(15)),
                            r.getObject(16, OffsetDateTime.class), r.getObject(17, UUID.class), r.getString(18), r.getObject(19, UUID.class)));
                }
            }
        }
        return out;
    }
}
