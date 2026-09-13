package aspm.app.integration;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Json;
import aspm.app.runtime.Principal;
import aspm.app.secrets.Secrets;
import aspm.module.integration.domain.FailureClass;
import aspm.sharedkernel.secrets.SecretReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
 * Connector administration: the catalogue of kinds, a tenant's connectors, their lifecycle, and their
 * credentials. DOC-21 §2–§4, §6. {@code PRD-CON-015}, {@code PRD-CON-016}, {@code PRD-CON-017},
 * {@code PRD-CON-018}, {@code PRD-CON-019}, {@code PRD-CON-020}, {@code PRD-CON-021}, {@code PRD-CON-022},
 * {@code PRD-CON-023}, {@code PRD-CON-028}, {@code PRD-CON-035}, {@code PRD-CON-054}.
 *
 * <p>The credential arrives once and leaves as a reference. Configuration is validated by the adapter
 * for its kind before anything is written, and activation runs a probe against the target so an
 * invalid connector fails here, in front of the administrator, rather than at a scheduled run nobody
 * is watching ({@code PRD-CON-017}). Rotation stores the new credential, proves it, and only then
 * demotes the old one to "previous" for an overlap period ({@code PRD-CON-022}).
 */
public final class ConnectorService {

    public static final String MANAGE = "int.connector.manage";

    public record Health(Optional<OffsetDateTime> lastSuccessAt, Optional<OffsetDateTime> lastAttemptAt, int consecutiveFailures,
            Optional<String> lastFailureClass, Optional<String> lastFailureDetail, String circuitState, Optional<String> circuitOpenReason,
            int periodAttempts, int periodSuccesses) {
        public String successRate() {
            return periodAttempts == 0 ? "—" : (periodSuccesses * 100 / periodAttempts) + "%";
        }
    }

    public record Connector(UUID id, String code, String displayName, String kind, int adapterVersion, Map<String, Object> config,
            boolean credentialHeld, boolean rotationInProgress, Optional<OffsetDateTime> credentialExpiresAt, Optional<UUID> scopeNodeId,
            Optional<String> scopePath, UUID ownerPrincipalId, String ownerName, String lifecycleState, Optional<String> validationDiagnosis,
            int observeEveryMinutes, int rowVersion, Health health, long openDivergences) {
    }

    public record Operation(UUID id, String kind, String status, int attempts, Optional<String> failureClass, Optional<String> detail,
            OffsetDateTime createdAt, Optional<OffsetDateTime> finishedAt, Optional<UUID> referenceId) {
    }

    private final DataSource dataSource;
    private final Secrets secrets;
    private final Map<String, ConnectorAdapter> adapters;
    private final Set<String> enabledKinds;
    private final aspm.app.audit.AuditTrail audit = new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public ConnectorService(DataSource dataSource, Secrets secrets, List<ConnectorAdapter> adapters, Set<String> enabledKinds) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.secrets = Objects.requireNonNull(secrets);
        this.adapters = adapters.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ConnectorAdapter::kind, a -> a));
        this.enabledKinds = Set.copyOf(enabledKinds);
    }

    /**
     * Every kind the platform ships, enabled or not. {@code PRD-CON-054}: a disabled kind is listed with
     * the consequence stated, never silently absent.
     */
    public List<Map<String, Object>> catalogue() {
        List<Map<String, Object>> out = new ArrayList<>();
        adapters.values().stream().sorted(java.util.Comparator.comparing(ConnectorAdapter::kind)).forEach(a -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", a.kind());
            entry.put("label", a.label());
            entry.put("version", a.version());
            entry.put("credentialLabel", a.credentialLabel());
            entry.put("minimumPermissions", a.minimumPermissions());
            entry.put("outboundContent", a.outboundContent());
            boolean enabled = enabledKinds.contains(a.kind());
            entry.put("enabled", enabled);
            entry.put("consequence", enabled ? null
                    : "disabled by this deployment (" + TrackerAdapters.KINDS_VARIABLE + "): findings cannot be propagated to "
                            + a.label() + "; existing references of this kind are no longer observed");
            out.add(entry);
        });
        return out;
    }

    ConnectorAdapter adapterFor(String kind) {
        ConnectorAdapter adapter = adapters.get(kind == null ? "" : kind);
        if (adapter == null) {
            throw new IllegalArgumentException("unknown connector kind; the options are " + adapters.keySet().stream().sorted().toList());
        }
        if (!enabledKinds.contains(adapter.kind())) {
            throw new IllegalArgumentException(adapter.label() + " is disabled by this deployment (" + TrackerAdapters.KINDS_VARIABLE + ")");
        }
        return adapter;
    }

    Map<String, ConnectorAdapter> adapters() {
        return adapters;
    }

    Set<String> enabledKinds() {
        return enabledKinds;
    }

    // ==============================================================================================
    // Reads
    // ==============================================================================================

    public List<Connector> list(Principal principal) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, principal)) {
            return connectors(connection, "ORDER BY CASE c.lifecycle_state WHEN 'ACTIVE' THEN 0 WHEN 'FAILED_VALIDATION' THEN 1 "
                    + "WHEN 'CONFIGURED' THEN 2 WHEN 'SUSPENDED' THEN 3 ELSE 4 END, c.display_name", null);
        }
    }

    public Connector one(Principal principal, UUID id) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, principal)) {
            return one(connection, id);
        }
    }

    public List<Operation> operations(Principal principal, UUID connectorId) throws SQLException {
        List<Operation> out = new ArrayList<>();
        try (Connection connection = TenantConnections.open(dataSource, principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, kind, status, attempts, last_failure_class, last_detail, created_at, finished_at, reference_id "
                                + "FROM outbound_operation WHERE connector_id = ? ORDER BY created_at DESC LIMIT 50")) {
            statement.setObject(1, connectorId);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.add(new Operation(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getInt(4),
                            Optional.ofNullable(r.getString(5)), Optional.ofNullable(r.getString(6)), r.getObject(7, OffsetDateTime.class),
                            Optional.ofNullable(r.getObject(8, OffsetDateTime.class)), Optional.ofNullable(r.getObject(9, UUID.class))));
                }
            }
        }
        return out;
    }

    // ==============================================================================================
    // Writes
    // ==============================================================================================

    public Connector create(Principal actor, String code, String displayName, String kind, Map<String, Object> config, Optional<char[]> credential,
            Optional<OffsetDateTime> credentialExpiresAt, Optional<UUID> scopeNodeId, Optional<UUID> owner, int observeEveryMinutes) throws SQLException {
        ConnectorAdapter adapter = adapterFor(kind);
        if (code == null || !code.matches("[a-z][a-z0-9-]{1,31}")) {
            throw new IllegalArgumentException("the connector code is 2–32 lower-case letters, digits or dashes, starting with a letter");
        }
        if (displayName == null || displayName.isBlank() || displayName.strip().length() > 80) {
            throw new IllegalArgumentException("a display name of at most 80 characters is required");
        }
        if (observeEveryMinutes < 5 || observeEveryMinutes > 10080) {
            throw new IllegalArgumentException("the observation interval is between 5 minutes and 7 days");
        }
        boolean present = credential.map(c -> c.length > 0).orElse(false);
        Map<String, Object> validated = adapter.validate(config == null ? Map.of() : config, present);
        Optional<SecretReference> ref = present ? Optional.of(secrets.store(actor.tenantId(), "connector", code + "-credential", credential.get()))
                : Optional.empty();
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            scopeNodeId.ifPresent(node -> requireNode(connection, node));
            UUID id;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO connector (tenant_id, code, display_name, kind, adapter_version, config, credential_ref, credential_expires_at, "
                            + "scope_node_id, owner_principal_id, observe_every_minutes, created_by, updated_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
                statement.setObject(1, actor.tenantId());
                statement.setString(2, code);
                statement.setString(3, displayName.strip());
                statement.setString(4, adapter.kind());
                statement.setInt(5, adapter.version());
                statement.setString(6, Json.write(validated));
                statement.setString(7, ref.map(SecretReference::toString).orElse(null));
                statement.setObject(8, credentialExpiresAt.orElse(null));
                statement.setObject(9, scopeNodeId.orElse(null));
                statement.setObject(10, owner.orElse(actor.principalId()));
                statement.setInt(11, observeEveryMinutes);
                statement.setObject(12, actor.principalId());
                statement.setObject(13, actor.principalId());
                try (ResultSet r = statement.executeQuery()) {
                    r.next();
                    id = r.getObject(1, UUID.class);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO connector_health (connector_id, tenant_id) VALUES (?, ?)")) {
                statement.setObject(1, id);
                statement.setObject(2, actor.tenantId());
                statement.executeUpdate();
            }
            audit.domainChangeBy(connection, actor.principalId(), "connector", aspm.kernel.audit.contract.DomainChangeKind.CREATED, id,
                    scopeNodeId.orElse(null), Map.of("code", code, "kind", adapter.kind(), "credential_held", ref.isPresent(),
                            "destination", destinationOf(validated), "scope_node_id", scopeNodeId.map(UUID::toString).orElse("tenant")));
            connection.commit();
            return one(connection, id);
        }
    }

    public Connector update(Principal actor, UUID id, String displayName, Map<String, Object> config, boolean scopeGiven, Optional<UUID> scopeNodeId,
            Optional<Integer> observeEveryMinutes, boolean expiryGiven, Optional<OffsetDateTime> credentialExpiresAt, Optional<UUID> owner,
            int rowVersion) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Connector current = one(connection, id);
            if (current.rowVersion() != rowVersion) {
                throw new IllegalStateException("the connector changed since it was loaded; reload and apply the change again");
            }
            if ("RETIRED".equals(current.lifecycleState())) {
                throw new IllegalArgumentException("a retired connector is not edited (PRD-CON-020: it stays as provenance)");
            }
            ConnectorAdapter adapter = adapterFor(current.kind());
            Map<String, Object> validated = config == null ? current.config() : adapter.validate(config, current.credentialHeld());
            String name = displayName == null || displayName.isBlank() ? current.displayName() : displayName.strip();
            if (name.length() > 80) {
                throw new IllegalArgumentException("a display name of at most 80 characters is required");
            }
            int interval = observeEveryMinutes.orElse(current.observeEveryMinutes());
            if (interval < 5 || interval > 10080) {
                throw new IllegalArgumentException("the observation interval is between 5 minutes and 7 days");
            }
            Optional<UUID> scope = scopeGiven ? scopeNodeId : current.scopeNodeId();
            scope.ifPresent(node -> requireNode(connection, node));
            // A changed configuration on a connector that failed validation is the correction; it goes
            // back to CONFIGURED and activation re-runs the probe. An ACTIVE connector stays active: the
            // adapter validated the new configuration and the next operation proves it against the target.
            String state = "FAILED_VALIDATION".equals(current.lifecycleState()) && config != null ? "CONFIGURED" : current.lifecycleState();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE connector SET display_name = ?, config = ?::jsonb, adapter_version = ?, scope_node_id = ?, observe_every_minutes = ?, "
                            + "credential_expires_at = ?, owner_principal_id = ?, lifecycle_state = ?, "
                            + "validation_diagnosis = CASE WHEN ? = 'FAILED_VALIDATION' THEN validation_diagnosis ELSE NULL END, "
                            + "updated_at = now(), updated_by = ?, row_version = row_version + 1 WHERE id = ? AND row_version = ?")) {
                statement.setString(1, name);
                statement.setString(2, Json.write(validated));
                statement.setInt(3, adapter.version());
                statement.setObject(4, scope.orElse(null));
                statement.setInt(5, interval);
                statement.setObject(6, expiryGiven ? credentialExpiresAt.orElse(null) : current.credentialExpiresAt().orElse(null));
                statement.setObject(7, owner.orElse(current.ownerPrincipalId()));
                statement.setString(8, state);
                statement.setString(9, state);
                statement.setObject(10, actor.principalId());
                statement.setObject(11, id);
                statement.setInt(12, rowVersion);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("the connector changed since it was loaded; reload and apply the change again");
                }
            }
            audit.domainChangeBy(connection, actor.principalId(), "connector", aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id,
                    scope.orElse(null), Map.of("destination", destinationOf(validated), "scope_node_id", scope.map(UUID::toString).orElse("tenant"),
                            "observe_every_minutes", interval, "state", state));
            connection.commit();
            return one(connection, id);
        }
    }

    /**
     * DOC-21 §3. {@code ACTIVE} runs the adapter's probe first and lands in {@code FAILED_VALIDATION} with
     * the diagnosis when it fails ({@code PRD-CON-017}); {@code SUSPENDED} halts operations and keeps
     * everything ({@code PRD-CON-019}); {@code RETIRED} destroys the credentials and keeps every reference
     * ({@code PRD-CON-020}).
     */
    public Connector transition(Principal actor, UUID id, String target) throws SQLException {
        if (!List.of("ACTIVE", "SUSPENDED", "RETIRED").contains(target)) {
            throw new IllegalArgumentException("the target state is ACTIVE, SUSPENDED or RETIRED");
        }
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Connector current = one(connection, id);
            if ("RETIRED".equals(current.lifecycleState())) {
                throw new IllegalArgumentException("a retired connector stays retired");
            }
            String landed = target;
            String diagnosis = null;
            if ("ACTIVE".equals(target)) {
                ConnectorAdapter.Result<String> probe = probeNow(connection, actor.tenantId(), current);
                if (!probe.succeeded()) {
                    landed = "FAILED_VALIDATION";
                    diagnosis = probe.failure().map(Enum::name).orElse("FAILURE") + ": " + probe.detail();
                } else {
                    // Activation after a proved probe is the correction an open circuit was waiting for.
                    ConnectorHealthStore.closeCircuit(connection, id);
                }
            } else if ("SUSPENDED".equals(target) && !"ACTIVE".equals(current.lifecycleState())) {
                throw new IllegalArgumentException("only an active connector is suspended");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE connector SET lifecycle_state = ?, validation_diagnosis = ?, updated_at = now(), updated_by = ?, "
                            + "row_version = row_version + 1 WHERE id = ?")) {
                statement.setString(1, landed);
                statement.setString(2, diagnosis);
                statement.setObject(3, actor.principalId());
                statement.setObject(4, id);
                statement.executeUpdate();
            }
            if ("RETIRED".equals(target)) {
                for (Optional<SecretReference> ref : List.of(currentRef(connection, id), previousRef(connection, id))) {
                    ref.ifPresent(r -> secrets.destroy(actor.tenantId(), r));
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE connector SET credential_ref = NULL, credential_previous_ref = NULL WHERE id = ?")) {
                    statement.setObject(1, id);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE outbound_operation SET status = 'FAILED', last_detail = 'CONNECTOR_RETIRED', finished_at = now(), updated_at = now() "
                                + "WHERE connector_id = ? AND status IN ('QUEUED', 'LEASED')")) {
                    statement.setObject(1, id);
                    statement.executeUpdate();
                }
            }
            audit.domainChangeBy(connection, actor.principalId(), "connector",
                    "RETIRED".equals(target) ? aspm.kernel.audit.contract.DomainChangeKind.RETIRED : aspm.kernel.audit.contract.DomainChangeKind.TRANSITIONED,
                    id, current.scopeNodeId().orElse(null), Map.of("from", current.lifecycleState(), "to", landed,
                            "diagnosis", diagnosis == null ? "" : diagnosis));
            connection.commit();
            return one(connection, id);
        }
    }

    /**
     * {@code PRD-CON-022}: the new credential is stored, proved against the target, and only then made
     * current; the old one becomes "previous" and stays valid for the overlap the worker honours (it is
     * tried when the new one is refused) until retired by {@link #retirePreviousCredential} or by the
     * worker after the overlap window.
     */
    public Connector rotate(Principal actor, UUID id, char[] credential, Optional<OffsetDateTime> expiresAt) throws SQLException {
        if (credential == null || credential.length == 0) {
            throw new IllegalArgumentException("the new credential is required");
        }
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Connector current = one(connection, id);
            if ("RETIRED".equals(current.lifecycleState())) {
                throw new IllegalArgumentException("a retired connector holds no credential");
            }
            ConnectorAdapter adapter = adapterFor(current.kind());
            SecretReference fresh = secrets.store(actor.tenantId(), "connector", current.code() + "-credential-" + System.currentTimeMillis(), credential);
            ConnectorAdapter.Result<String> probe = adapter.probe(current.config(), Optional.of(credential));
            if (!probe.succeeded()) {
                secrets.destroy(actor.tenantId(), fresh);
                ConnectorHealthStore.record(connection, actor.tenantId(), id, probe.failure(), "ROTATION " + probe.detail());
                connection.commit();
                throw new IllegalArgumentException("the new credential was refused by the target (" + probe.failure().map(Enum::name).orElse("FAILURE")
                        + ", " + probe.detail() + "); the current credential stays in place");
            }
            Optional<SecretReference> old = currentRef(connection, id);
            // A rotation already in overlap: the oldest goes now, so at most two credentials are ever valid.
            previousRef(connection, id).ifPresent(r -> secrets.destroy(actor.tenantId(), r));
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE connector SET credential_ref = ?, credential_previous_ref = ?, rotation_verified_at = now(), credential_expires_at = ?, "
                            + "expiry_notified_at = NULL, updated_at = now(), updated_by = ?, row_version = row_version + 1 WHERE id = ?")) {
                statement.setString(1, fresh.toString());
                statement.setString(2, old.map(SecretReference::toString).orElse(null));
                statement.setObject(3, expiresAt.orElse(null));
                statement.setObject(4, actor.principalId());
                statement.setObject(5, id);
                statement.executeUpdate();
            }
            ConnectorHealthStore.record(connection, actor.tenantId(), id, Optional.empty(), "ROTATION " + probe.detail());
            ConnectorHealthStore.closeCircuit(connection, id);
            audit.domainChangeBy(connection, actor.principalId(), "connector", aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id,
                    current.scopeNodeId().orElse(null), Map.of("credential_rotated", true, "overlap", old.isPresent(),
                            "expires_at", expiresAt.map(OffsetDateTime::toString).orElse("")));
            connection.commit();
            return one(connection, id);
        }
    }

    /** Ends the overlap: the previous credential is destroyed. */
    public Connector retirePreviousCredential(Principal actor, UUID id) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Connector current = one(connection, id);
            Optional<SecretReference> previous = previousRef(connection, id);
            if (previous.isEmpty()) {
                throw new IllegalArgumentException("no rotation is in progress");
            }
            secrets.destroy(actor.tenantId(), previous.get());
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE connector SET credential_previous_ref = NULL, updated_at = now(), updated_by = ?, row_version = row_version + 1 WHERE id = ?")) {
                statement.setObject(1, actor.principalId());
                statement.setObject(2, id);
                statement.executeUpdate();
            }
            audit.domainChangeBy(connection, actor.principalId(), "connector", aspm.kernel.audit.contract.DomainChangeKind.UPDATED, id,
                    current.scopeNodeId().orElse(null), Map.of("previous_credential_retired", true));
            connection.commit();
            return one(connection, id);
        }
    }

    /** A connectivity probe the administrator asked for now. Recorded in health and in the operation history. */
    public Map<String, Object> probe(Principal actor, UUID id) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            Connector current = one(connection, id);
            if ("RETIRED".equals(current.lifecycleState())) {
                throw new IllegalArgumentException("a retired connector is not probed");
            }
            ConnectorAdapter.Result<String> probe = probeNow(connection, actor.tenantId(), current);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO outbound_operation (tenant_id, connector_id, kind, status, attempts, last_failure_class, last_detail, requested_by, finished_at) "
                            + "VALUES (?, ?, 'PROBE', ?, 1, ?, ?, ?, now())")) {
                statement.setObject(1, actor.tenantId());
                statement.setObject(2, id);
                statement.setString(3, probe.succeeded() ? "DONE" : "FAILED");
                statement.setString(4, probe.failure().map(Enum::name).orElse(null));
                statement.setString(5, probe.detail());
                statement.setObject(6, actor.principalId());
                statement.executeUpdate();
            }
            connection.commit();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", probe.succeeded());
            out.put("failureClass", probe.failure().map(Enum::name).orElse(null));
            out.put("detail", probe.succeeded() ? probe.value().get() : probe.detail());
            out.put("minimumPermissions", adapterFor(current.kind()).minimumPermissions());
            return out;
        }
    }

    // ==============================================================================================

    private ConnectorAdapter.Result<String> probeNow(Connection connection, UUID tenantId, Connector current) throws SQLException {
        ConnectorAdapter adapter = adapterFor(current.kind());
        Optional<char[]> credential = currentRef(connection, current.id()).flatMap(ref -> secrets.resolveTenant(tenantId, ref));
        ConnectorAdapter.Result<String> probe;
        if (current.credentialHeld() && credential.isEmpty()) {
            probe = ConnectorAdapter.Result.failed(FailureClass.AUTHENTICATION, "the credential reference does not resolve in the secrets store");
        } else {
            try {
                probe = adapter.probe(current.config(), credential);
            } catch (RuntimeException e) {
                probe = ConnectorAdapter.Result.failed(FailureClass.PROTOCOL, e.getClass().getSimpleName());
            }
        }
        ConnectorHealthStore.record(connection, tenantId, current.id(), probe.failure(), "PROBE " + probe.detail());
        return probe;
    }

    private static void requireNode(Connection connection, UUID node) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM org_node WHERE id = ?")) {
            statement.setObject(1, node);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    throw new IllegalArgumentException("the scope node does not exist in this tenant");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String destinationOf(Map<String, Object> config) {
        Object url = config.containsKey("baseUrl") ? config.get("baseUrl") : config.get("url");
        return url == null ? "" : String.valueOf(url);
    }

    static Optional<SecretReference> currentRef(Connection connection, UUID id) throws SQLException {
        return ref(connection, id, "credential_ref");
    }

    static Optional<SecretReference> previousRef(Connection connection, UUID id) throws SQLException {
        return ref(connection, id, "credential_previous_ref");
    }

    private static Optional<SecretReference> ref(Connection connection, UUID id, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + column + " FROM connector WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? Optional.ofNullable(r.getString(1)).map(SecretReference::parse) : Optional.empty();
            }
        }
    }

    Connector one(Connection connection, UUID id) throws SQLException {
        List<Connector> found = connectors(connection, "WHERE c.id = ?", id);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("no such connector");
        }
        return found.get(0);
    }

    List<Connector> connectors(Connection connection, String clause, Object parameter) throws SQLException {
        String sql = "SELECT c.id, c.code, c.display_name, c.kind, c.adapter_version, c.config::text, c.credential_ref IS NOT NULL, "
                + "c.credential_previous_ref IS NOT NULL, c.credential_expires_at, c.scope_node_id, "
                + "(SELECT string_agg(an.name, ' › ' ORDER BY cl.depth DESC) FROM org_closure cl JOIN org_node an ON an.id = cl.ancestor_id "
                + " WHERE cl.descendant_id = c.scope_node_id), "
                + "c.owner_principal_id, coalesce(p.display_name, p.username, '?'), c.lifecycle_state, c.validation_diagnosis, c.observe_every_minutes, "
                + "c.row_version, h.last_success_at, h.last_attempt_at, coalesce(h.consecutive_failures, 0), h.last_failure_class, h.last_failure_detail, "
                + "coalesce(h.circuit_state, 'CLOSED'), h.circuit_open_reason, coalesce(h.period_attempts, 0), coalesce(h.period_successes, 0), "
                + "(SELECT count(*) FROM outbound_reference o WHERE o.connector_id = c.id AND o.divergence_detected_at IS NOT NULL AND o.divergence_resolved_at IS NULL) "
                + "FROM connector c LEFT JOIN connector_health h ON h.connector_id = c.id LEFT JOIN principal p ON p.id = c.owner_principal_id " + clause;
        List<Connector> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) {
                statement.setObject(1, parameter);
            }
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    Health health = new Health(Optional.ofNullable(r.getObject(18, OffsetDateTime.class)), Optional.ofNullable(r.getObject(19, OffsetDateTime.class)),
                            r.getInt(20), Optional.ofNullable(r.getString(21)), Optional.ofNullable(r.getString(22)), r.getString(23),
                            Optional.ofNullable(r.getString(24)), r.getInt(25), r.getInt(26));
                    out.add(new Connector(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4), r.getInt(5),
                            Json.readObject(r.getString(6)), r.getBoolean(7), r.getBoolean(8), Optional.ofNullable(r.getObject(9, OffsetDateTime.class)),
                            Optional.ofNullable(r.getObject(10, UUID.class)), Optional.ofNullable(r.getString(11)), r.getObject(12, UUID.class), r.getString(13),
                            r.getString(14), Optional.ofNullable(r.getString(15)), r.getInt(16), r.getInt(17), health, r.getLong(27)));
                }
            }
        }
        return out;
    }
}
