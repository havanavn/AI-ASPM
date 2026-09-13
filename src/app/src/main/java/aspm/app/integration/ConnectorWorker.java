package aspm.app.integration;

import aspm.app.notification.Notifier;
import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Json;
import aspm.app.secrets.Secrets;
import aspm.kernel.tenantcontext.contract.EstablishedFrom;
import aspm.kernel.tenantcontext.contract.TenantContext;
import aspm.kernel.tenantcontext.contract.TenantContextHolder;
import aspm.sharedkernel.TenantId;
import aspm.module.integration.domain.ConnectorHealth;
import aspm.module.integration.domain.FailureClass;
import aspm.sharedkernel.secrets.SecretReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Drains the connector outbox: creates references in trackers, observes them, probes credentials.
 * ADR-054 (DISPATCH class), {@code PRD-CON-022}, {@code PRD-CON-023}, {@code PRD-CON-024},
 * {@code PRD-CON-025}, {@code PRD-CON-026}, {@code PRD-CON-027}, {@code PRD-CON-029}, {@code PRD-CON-031},
 * {@code PRD-CON-042}, {@code PRD-CON-043}, {@code PRD-CON-044}, {@code CON-PLT-031}, {@code CON-PLT-032}.
 *
 * <p>Same shape as the notification worker: a lease by time with {@code FOR UPDATE SKIP LOCKED}, an
 * attempt, a recorded outcome, and a retry decided by the failure class and nothing else. Two things are
 * particular to connectors. The circuit: an open circuit keeps its operations queued, admits one probe
 * after a cooldown for transient classes, and never reopens on a timer for a credential or configuration
 * failure ({@code PRD-CON-024}). And observation: what the tracker says is written on the reference and
 * compared with the finding — and where they disagree the worker writes a DIVERGENCE and tells people.
 * There is no code path from this class to a finding row; a test scans for one.
 */
public final class ConnectorWorker implements Runnable, AutoCloseable {

    static final Duration LEASE = Duration.ofMinutes(2);
    static final Duration IDLE_SLEEP = Duration.ofSeconds(10);
    static final Duration OVERLAP = Duration.ofHours(24);
    static final int BATCH = 20;

    private final DataSource dataSource;
    private final UUID tenantId;
    private final Secrets secrets;
    private final Map<String, ConnectorAdapter> adapters;
    private final Set<String> enabledKinds;
    private final Optional<String> publicBaseUrl;
    private final int expiryWarningDays;
    private final String owner = "connector-" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;

    public ConnectorWorker(DataSource dataSource, UUID tenantId, Secrets secrets, List<ConnectorAdapter> adapters, Set<String> enabledKinds,
            Optional<String> publicBaseUrl, int expiryWarningDays) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.secrets = Objects.requireNonNull(secrets);
        this.adapters = adapters.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ConnectorAdapter::kind, a -> a));
        this.enabledKinds = Set.copyOf(enabledKinds);
        this.publicBaseUrl = Objects.requireNonNull(publicBaseUrl).map(u -> u.endsWith("/") ? u.substring(0, u.length() - 1) : u);
        this.expiryWarningDays = Math.max(1, expiryWarningDays);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofVirtual().name("aspm-connector-worker").start(this);
        }
    }

    @Override
    public void close() {
        running.set(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public void run() {
        System.Logger log = System.getLogger("aspm.connector");
        log.log(System.Logger.Level.INFO, "connector worker started as " + owner + " for tenant " + tenantId);
        while (running.get()) {
            int processed;
            try {
                processed = tick();
            } catch (Exception e) {
                log.log(System.Logger.Level.ERROR, "connector tick failed: " + e.getClass().getSimpleName());
                processed = 0;
            }
            if (processed == 0) {
                try {
                    Thread.sleep(IDLE_SLEEP);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** One pass: sweep, claim, attempt each, record. Returns how many operations were attempted. */
    public int tick() throws Exception {
        TenantContext context = TenantContext.of(new TenantId(tenantId), "vn", EstablishedFrom.SCHEDULED_JOB_BINDING, Instant.now());
        return TenantContextHolder.callWith(context, () -> {
            housekeeping();
            List<Claim> claims = claim();
            for (Claim claim : claims) {
                process(claim);
            }
            return claims.size();
        });
    }

    // ==============================================================================================

    record Claim(UUID operationId, UUID connectorId, Optional<UUID> referenceId, String kind, int attempts) {
    }

    private record Target(String kind, Map<String, Object> config, Optional<SecretReference> credential, Optional<SecretReference> previous,
            String lifecycleState, UUID ownerId, String displayName) {
    }

    private record Subject(UUID findingId, String title, String severity, String scopePath, String sourceTool, String state, String lifecycle,
            Optional<UUID> scopeNodeId, Optional<UUID> requestId) {
    }

    private List<Claim> claim() throws SQLException {
        List<Claim> out = new ArrayList<>();
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId);
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE outbound_operation o
                           SET status = 'LEASED', lease_until = now() + make_interval(secs => ?), lease_owner = ?,
                               attempts = attempts + 1, updated_at = now()
                         WHERE o.id IN (
                               SELECT op.id FROM outbound_operation op
                                 JOIN connector c ON c.id = op.connector_id
                                WHERE op.next_attempt_at <= now()
                                  AND (op.status = 'QUEUED' OR (op.status = 'LEASED' AND op.lease_until < now()))
                                  AND c.lifecycle_state = 'ACTIVE'
                                ORDER BY op.next_attempt_at
                                FOR UPDATE OF op SKIP LOCKED
                                LIMIT ?)
                        RETURNING o.id, o.connector_id, o.reference_id, o.kind, o.attempts
                        """)) {
            statement.setDouble(1, LEASE.toSeconds());
            statement.setString(2, owner);
            statement.setInt(3, BATCH);
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.add(new Claim(r.getObject(1, UUID.class), r.getObject(2, UUID.class), Optional.ofNullable(r.getObject(3, UUID.class)),
                            r.getString(4), r.getInt(5)));
                }
            }
            connection.commit();
        }
        return out;
    }

    private void process(Claim claim) throws SQLException {
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId)) {
            Target target = target(connection, claim.connectorId());
            ConnectorAdapter adapter = adapters.get(target.kind());
            if (adapter == null || !enabledKinds.contains(target.kind())) {
                finish(connection, claim, "FAILED", Optional.of(FailureClass.CONFIGURATION), "KIND_DISABLED_IN_THIS_DEPLOYMENT", Optional.empty());
                connection.commit();
                return;
            }
            ConnectorHealth health = ConnectorHealthStore.load(connection, tenantId, claim.connectorId());
            if (!ConnectorHealthStore.admitProbe(connection, health)) {
                // PRD-CON-024 / DOC-21 §6: the circuit is open. The work waits; it is not lost and not retried blindly.
                requeue(connection, claim, ConnectorHealthStore.COOLDOWN, "CIRCUIT_OPEN");
                ensureOwnerTold(connection, claim.connectorId(), target, health);
                connection.commit();
                return;
            }
            Optional<char[]> credential = target.credential().flatMap(ref -> secrets.resolveTenant(tenantId, ref));
            if (target.credential().isPresent() && credential.isEmpty()) {
                fail(connection, claim, target, FailureClass.AUTHENTICATION, "SECRET_UNRESOLVABLE");
                connection.commit();
                return;
            }
            switch (claim.kind()) {
                case "CREATE_REFERENCE" -> createReference(connection, claim, target, adapter, credential);
                case "OBSERVE" -> observe(connection, claim, target, adapter, credential);
                case "PROBE" -> {
                    ConnectorAdapter.Result<String> probe = call(() -> adapter.probe(target.config(), credential));
                    if (probe.succeeded()) {
                        succeed(connection, claim, target, probe.detail());
                    } else {
                        fail(connection, claim, target, probe.failure().orElseThrow(), probe.detail());
                    }
                }
                default -> finish(connection, claim, "FAILED", Optional.of(FailureClass.CONFIGURATION), "UNKNOWN_OPERATION", Optional.empty());
            }
            connection.commit();
        }
    }

    private void createReference(Connection connection, Claim claim, Target target, ConnectorAdapter adapter, Optional<char[]> credential)
            throws SQLException {
        UUID referenceId = claim.referenceId().orElseThrow();
        Optional<Subject> subject = subject(connection, referenceId);
        if (subject.isEmpty()) {
            finish(connection, claim, "FAILED", Optional.of(FailureClass.DATA), "SUBJECT_MISSING", Optional.empty());
            markReference(connection, referenceId, "FAILED", "the finding no longer exists");
            return;
        }
        Subject s = subject.get();
        ConnectorAdapter.Reference reference = new ConnectorAdapter.Reference(s.findingId(), s.title(), s.severity(), s.scopePath(), s.sourceTool(),
                publicBaseUrl.orElse("") + "/pipeline/findings/" + s.findingId());
        ConnectorAdapter.Result<ConnectorAdapter.Created> result = withOverlap(target, credential,
                c -> call(() -> adapter.create(target.config(), c, reference)));
        if (result.succeeded()) {
            ConnectorAdapter.Created created = result.value().get();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE outbound_reference SET status = 'LINKED', external_id = ?, external_key = ?, external_url = ?, external_state = ?, "
                            + "external_resolved = ?, last_observed_at = now(), failure_detail = NULL, updated_at = now() WHERE id = ?")) {
                statement.setString(1, created.externalId());
                statement.setString(2, created.externalKey());
                statement.setString(3, created.url().orElse(null));
                statement.setString(4, created.state());
                statement.setBoolean(5, created.resolved());
                statement.setObject(6, referenceId);
                statement.executeUpdate();
            }
            succeed(connection, claim, target, result.detail());
        } else {
            FailureClass failure = result.failure().orElseThrow();
            boolean retrying = fail(connection, claim, target, failure, result.detail());
            if (!retrying) {
                markReference(connection, referenceId, "FAILED", failure.name() + ": " + result.detail());
            }
        }
    }

    private void observe(Connection connection, Claim claim, Target target, ConnectorAdapter adapter, Optional<char[]> credential) throws SQLException {
        UUID referenceId = claim.referenceId().orElseThrow();
        String externalId;
        Boolean previouslyResolved;
        boolean divergenceOpen;
        UUID createdBy;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT external_id, external_resolved, divergence_detected_at IS NOT NULL AND divergence_resolved_at IS NULL, created_by "
                        + "FROM outbound_reference WHERE id = ? AND status = 'LINKED'")) {
            statement.setObject(1, referenceId);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    finish(connection, claim, "FAILED", Optional.of(FailureClass.DATA), "REFERENCE_NOT_LINKED", Optional.empty());
                    return;
                }
                externalId = r.getString(1);
                previouslyResolved = (Boolean) r.getObject(2);
                divergenceOpen = r.getBoolean(3);
                createdBy = r.getObject(4, UUID.class);
            }
        }
        Optional<Subject> subject = subject(connection, referenceId);
        ConnectorAdapter.Result<ConnectorAdapter.Observation> result = withOverlap(target, credential,
                c -> call(() -> adapter.observe(target.config(), c, externalId)));
        if (!result.succeeded()) {
            fail(connection, claim, target, result.failure().orElseThrow(), result.detail());
            return;
        }
        ConnectorAdapter.Observation observed = result.value().get();
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE outbound_reference SET external_state = ?, external_resolved = ?, last_observed_at = now(), updated_at = now() WHERE id = ?")) {
            statement.setString(1, observed.state().orElse("DELETED"));
            statement.setBoolean(2, observed.resolved());
            statement.setObject(3, referenceId);
            statement.executeUpdate();
        }
        // PRD-CON-042 / PRD-CON-043 / PRD-CON-044: compare, record, tell — never reconcile.
        if (!divergenceOpen && subject.isPresent()) {
            Subject s = subject.get();
            Optional<String> kind = divergence(observed, s.state(), previouslyResolved);
            if (kind.isPresent()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE outbound_reference SET divergence_kind = ?, divergence_detected_at = now(), divergence_platform_state = ?, "
                                + "divergence_resolved_at = NULL, divergence_resolved_by = NULL, divergence_resolution_note = NULL, updated_at = now() WHERE id = ?")) {
                    statement.setString(1, kind.get());
                    statement.setString(2, s.lifecycle());
                    statement.setObject(3, referenceId);
                    statement.executeUpdate();
                }
                Set<UUID> audience = new LinkedHashSet<>(List.of(createdBy, target.ownerId()));
                Notifier.emit(connection, tenantId, new Notifier.Event("integration.divergence", "FINDING", s.findingId(), s.scopeNodeId(),
                        s.title(), Optional.empty(), target.displayName(), Map.of("state", kind.get()),
                        Optional.of(s.requestId().map(req -> "/board/" + req + "/findings/" + s.findingId()).orElse("/pipeline/findings/" + s.findingId()))),
                        audience);
            }
        }
        succeed(connection, claim, target, result.detail());
    }

    /** DOC-21 §10's four kinds, from what the tracker says and where the finding is. Pure; tested directly. */
    public static Optional<String> divergence(ConnectorAdapter.Observation observed, String platformState, Boolean previouslyResolved) {
        boolean platformOpen = "OPEN".equals(platformState);
        if (observed.deleted()) {
            return Optional.of("DELETED_EXTERNALLY");
        }
        if (observed.resolved() && platformOpen) {
            return Optional.of("CLOSED_EXTERNALLY");
        }
        if (!observed.resolved() && !platformOpen) {
            return Optional.of(Boolean.TRUE.equals(previouslyResolved) ? "REOPENED_EXTERNALLY" : "STATE_MISMATCH");
        }
        return Optional.empty();
    }

    // ==============================================================================================

    /**
     * {@code PRD-CON-022}: during rotation the previous credential is still valid. When the current one is
     * refused with an authentication failure and a previous exists, the call is made once more with it — so
     * a rotation whose new secret has not yet propagated on the target does not open the circuit.
     */
    private <T> ConnectorAdapter.Result<T> withOverlap(Target target, Optional<char[]> credential,
            java.util.function.Function<Optional<char[]>, ConnectorAdapter.Result<T>> call) {
        ConnectorAdapter.Result<T> first = call.apply(credential);
        if (first.succeeded() || first.failure().orElse(null) != FailureClass.AUTHENTICATION || target.previous().isEmpty()) {
            return first;
        }
        Optional<char[]> previous = target.previous().flatMap(ref -> secrets.resolveTenant(tenantId, ref));
        if (previous.isEmpty()) {
            return first;
        }
        ConnectorAdapter.Result<T> second = call.apply(previous);
        return second.succeeded() ? new ConnectorAdapter.Result<>(second.value(), Optional.empty(), second.detail() + " (previous credential)") : first;
    }

    private static <T> ConnectorAdapter.Result<T> call(java.util.function.Supplier<ConnectorAdapter.Result<T>> body) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            return ConnectorAdapter.Result.failed(FailureClass.PROTOCOL, e.getClass().getSimpleName());
        }
    }

    private void succeed(Connection connection, Claim claim, Target target, String detail) throws SQLException {
        finish(connection, claim, "DONE", Optional.empty(), detail, Optional.empty());
        ConnectorHealthStore.record(connection, tenantId, claim.connectorId(), Optional.empty(), detail);
    }

    /** Records the failure on the operation and the connector; returns whether the operation will be retried. */
    private boolean fail(Connection connection, Claim claim, Target target, FailureClass failure, String detail) throws SQLException {
        Optional<Duration> backoff = failure.backoffBefore(claim.attempts(), retryAfter(detail));
        ConnectorHealthStore.Recorded recorded = ConnectorHealthStore.record(connection, tenantId, claim.connectorId(), Optional.of(failure), detail);
        if (backoff.isPresent()) {
            requeue(connection, claim, recorded.health().circuitOpen() ? ConnectorHealthStore.COOLDOWN.plus(backoff.get()) : backoff.get(), failure.name() + " " + detail);
        } else {
            finish(connection, claim, failure.quarantinesRecordAndContinues() ? "QUARANTINED" : "FAILED", Optional.of(failure), detail, Optional.empty());
        }
        if (recorded.circuitJustOpened() || (recorded.health().circuitOpen() && !recorded.health().ownerNotified())) {
            ensureOwnerTold(connection, claim.connectorId(), target, recorded.health());
        }
        return backoff.isPresent();
    }

    /** {@code PRD-CON-029}: an open circuit nobody was told about is not permitted to exist past this tick. */
    private void ensureOwnerTold(Connection connection, UUID connectorId, Target target, ConnectorHealth health) throws SQLException {
        if (!health.circuitOpen() || health.ownerNotified()) {
            return;
        }
        Notifier.emit(connection, tenantId, new Notifier.Event("integration.unhealthy", "CONNECTOR", connectorId, Optional.empty(),
                target.displayName(), Optional.empty(), "", Map.of("state", health.circuitOpenReason().orElse("circuit open")),
                Optional.of("/settings?tab=connectors")), Set.of(target.ownerId()));
        ConnectorHealthStore.markOwnerNotified(connection, connectorId);
        health.recordOwnerNotified();
    }

    private static void finish(Connection connection, Claim claim, String status, Optional<FailureClass> failure, String detail, Optional<Duration> backoff)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE outbound_operation SET status = ?, lease_until = NULL, lease_owner = NULL, last_failure_class = ?, last_detail = ?, "
                        + "finished_at = CASE WHEN ? IN ('DONE', 'FAILED', 'QUARANTINED') THEN now() ELSE finished_at END, "
                        + "next_attempt_at = CASE WHEN ? = 'QUEUED' THEN now() + make_interval(secs => ?) ELSE next_attempt_at END, updated_at = now() WHERE id = ?")) {
            statement.setString(1, status);
            statement.setString(2, failure.map(Enum::name).orElse(null));
            statement.setString(3, detail == null ? null : detail.length() > 300 ? detail.substring(0, 300) : detail);
            statement.setString(4, status);
            statement.setString(5, status);
            statement.setDouble(6, backoff.map(Duration::toSeconds).orElse(0L));
            statement.setObject(7, claim.operationId());
            statement.executeUpdate();
        }
    }

    private static void requeue(Connection connection, Claim claim, Duration backoff, String detail) throws SQLException {
        finish(connection, claim, "QUEUED", Optional.empty(), detail, Optional.of(backoff));
    }

    private static void markReference(Connection connection, UUID referenceId, String status, String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE outbound_reference SET status = ?, failure_detail = ?, updated_at = now() WHERE id = ?")) {
            statement.setString(1, status);
            statement.setString(2, detail.length() > 300 ? detail.substring(0, 300) : detail);
            statement.setObject(3, referenceId);
            statement.executeUpdate();
        }
    }

    /** Retry-After in seconds, when the detail carries one (PRD-CON-027). */
    static Optional<Duration> retryAfter(String detail) {
        if (detail == null) {
            return Optional.empty();
        }
        int at = detail.indexOf("retry-after=");
        if (at < 0) {
            return Optional.empty();
        }
        String value = detail.substring(at + "retry-after=".length()).split("[^0-9]")[0];
        return value.isEmpty() ? Optional.empty() : Optional.of(Duration.ofSeconds(Math.min(Long.parseLong(value), 3600)));
    }

    private static Target target(Connection connection, UUID connectorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT kind, config::text, credential_ref, credential_previous_ref, lifecycle_state, owner_principal_id, display_name FROM connector WHERE id = ?")) {
            statement.setObject(1, connectorId);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    throw new IllegalStateException("connector vanished under a leased operation");
                }
                return new Target(r.getString(1), Json.readObject(r.getString(2)), Optional.ofNullable(r.getString(3)).map(SecretReference::parse),
                        Optional.ofNullable(r.getString(4)).map(SecretReference::parse), r.getString(5), r.getObject(6, UUID.class), r.getString(7));
            }
        }
    }

    /** The finding behind a reference, read once, minimal: what PRD-CON-045 lets leave and what the comparison needs. */
    private static Optional<Subject> subject(Connection connection, UUID referenceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT f.id, f.title, coalesce(s.code, 'UNRATED'), coalesce((SELECT string_agg(an.name, ' › ' ORDER BY cl.depth DESC) "
                        + "FROM org_closure cl JOIN org_node an ON an.id = cl.ancestor_id WHERE cl.descendant_id = f.scope_node_id), '—'), "
                        + "f.source_tool, f.state, f.lifecycle_state, f.scope_node_id, f.discovered_in_request_id "
                        + "FROM outbound_reference r JOIN finding f ON f.id = r.subject_id "
                        + "LEFT JOIN severity_level s ON s.id = coalesce(f.effective_severity_id, f.reported_severity_id) WHERE r.id = ?")) {
            statement.setObject(1, referenceId);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Subject(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
                        r.getString(7), Optional.ofNullable(r.getObject(8, UUID.class)), Optional.ofNullable(r.getObject(9, UUID.class))));
            }
        }
    }

    // ==============================================================================================
    // Sweeps that ride the tick
    // ==============================================================================================

    private void housekeeping() throws SQLException {
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId)) {
            // Observation (PRD-CON-043): one OBSERVE per due, linked reference of an active, enabled connector.
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO outbound_operation (tenant_id, connector_id, reference_id, kind) "
                            + "SELECT r.tenant_id, r.connector_id, r.id, 'OBSERVE' FROM outbound_reference r JOIN connector c ON c.id = r.connector_id "
                            + "WHERE r.status = 'LINKED' AND c.lifecycle_state = 'ACTIVE' AND c.kind = ANY (?) "
                            + "AND (r.last_observed_at IS NULL OR r.last_observed_at < now() - make_interval(mins => c.observe_every_minutes)) "
                            + "AND NOT EXISTS (SELECT 1 FROM outbound_operation o WHERE o.reference_id = r.id AND o.status IN ('QUEUED', 'LEASED')) "
                            + "ORDER BY r.last_observed_at NULLS FIRST LIMIT 50")) {
                statement.setArray(1, connection.createArrayOf("text", enabledKinds.toArray()));
                statement.executeUpdate();
            }
            // Credential expiry (PRD-CON-023): the owner is told once, ahead of the window.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, display_name, owner_principal_id, credential_expires_at::text FROM connector WHERE lifecycle_state IN ('ACTIVE', 'SUSPENDED') "
                            + "AND credential_expires_at IS NOT NULL AND expiry_notified_at IS NULL AND credential_expires_at <= now() + make_interval(days => ?)")) {
                statement.setInt(1, expiryWarningDays);
                try (ResultSet r = statement.executeQuery()) {
                    while (r.next()) {
                        UUID id = r.getObject(1, UUID.class);
                        Notifier.emit(connection, tenantId, new Notifier.Event("credential.rotation_required", "CONNECTOR", id, Optional.empty(),
                                r.getString(2), Optional.empty(), "", Map.of("state", r.getString(4)), Optional.of("/settings?tab=connectors")),
                                Set.of(r.getObject(3, UUID.class)));
                        try (PreparedStatement mark = connection.prepareStatement("UPDATE connector SET expiry_notified_at = now() WHERE id = ?")) {
                            mark.setObject(1, id);
                            mark.executeUpdate();
                        }
                    }
                }
            }
            // Rotation overlap ends (PRD-CON-022): the previous credential is destroyed after the window.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, credential_previous_ref FROM connector WHERE credential_previous_ref IS NOT NULL AND rotation_verified_at < now() - make_interval(secs => ?)")) {
                statement.setDouble(1, OVERLAP.toSeconds());
                try (ResultSet r = statement.executeQuery()) {
                    while (r.next()) {
                        secrets.destroy(tenantId, SecretReference.parse(r.getString(2)));
                        try (PreparedStatement clear = connection.prepareStatement("UPDATE connector SET credential_previous_ref = NULL WHERE id = ?")) {
                            clear.setObject(1, r.getObject(1, UUID.class));
                            clear.executeUpdate();
                        }
                    }
                }
            }
            // Success-rate period (PRD-CON-031): degraded without an open circuit is alerted once per period.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT h.connector_id, c.display_name, c.owner_principal_id, h.period_successes, h.period_attempts FROM connector_health h "
                            + "JOIN connector c ON c.id = h.connector_id WHERE c.lifecycle_state = 'ACTIVE' AND h.circuit_state <> 'OPEN' "
                            + "AND h.degraded_alerted_at IS NULL AND h.period_attempts >= 10 AND h.period_successes * 100 < h.period_attempts * ?")) {
                statement.setInt(1, ConnectorHealth.DEGRADED_BELOW_PERCENT.intValue());
                try (ResultSet r = statement.executeQuery()) {
                    while (r.next()) {
                        UUID id = r.getObject(1, UUID.class);
                        Notifier.emit(connection, tenantId, new Notifier.Event("integration.unhealthy", "CONNECTOR", id, Optional.empty(), r.getString(2),
                                Optional.empty(), "", Map.of("state", "DEGRADED: " + r.getInt(4) + "/" + r.getInt(5) + " succeeded this period"),
                                Optional.of("/settings?tab=connectors")), Set.of(r.getObject(3, UUID.class)));
                        try (PreparedStatement mark = connection.prepareStatement("UPDATE connector_health SET degraded_alerted_at = now() WHERE connector_id = ?")) {
                            mark.setObject(1, id);
                            mark.executeUpdate();
                        }
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE connector_health SET period_started_at = now(), period_attempts = 0, period_successes = 0, degraded_alerted_at = NULL "
                            + "WHERE period_started_at < now() - interval '24 hours'")) {
                statement.executeUpdate();
            }
            connection.commit();
        }
    }
}
