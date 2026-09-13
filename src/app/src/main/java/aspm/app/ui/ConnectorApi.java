package aspm.app.ui;

import aspm.app.integration.ConnectorService;
import aspm.app.integration.OutboundReferenceService;
import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
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
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Connectors and outbound references over JSON. Administration is class A/E under
 * {@code int.connector.manage}; creating and resolving a reference on a finding is class A/B under
 * {@code int.reference.create}, scoped by the finding. DOC-21 §2–§4, §6, §10.
 */
public final class ConnectorApi {

    private final DataSource dataSource;
    private final ConnectorService connectors;
    private final OutboundReferenceService references;

    public ConnectorApi(DataSource dataSource, ConnectorService connectors, OutboundReferenceService references) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.connectors = Objects.requireNonNull(connectors);
        this.references = Objects.requireNonNull(references);
    }

    // ==============================================================================================
    // Administration — /api/ui/settings/connectors
    // ==============================================================================================

    /** {@code GET /api/ui/settings/connectors}. Rows, the catalogue of kinds, and the pickers' options. */
    public Dispatcher.Response list(Dispatcher.Request request) throws SQLException {
        Principal principal = request.principal();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", connectors.list(principal).stream().map(ConnectorApi::connector).toList());
        body.put("catalogue", connectors.catalogue());
        body.put("mayManage", principal.holds(ConnectorService.MANAGE));
        body.put("elevated", principal.stepUpAuthenticated());
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> owners = new ArrayList<>();
        try (Connection c = TenantConnections.open(dataSource, principal)) {
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT n.id, (SELECT string_agg(an.name, ' › ' ORDER BY cl.depth DESC) FROM org_closure cl JOIN org_node an ON an.id = cl.ancestor_id "
                            + "WHERE cl.descendant_id = n.id) FROM org_node n WHERE n.id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)) "
                            + "ORDER BY 2 LIMIT 500")) {
                statement.setArray(1, c.createArrayOf("uuid", principal.scopeNodeIds().toArray()));
                try (ResultSet r = statement.executeQuery()) {
                    while (r.next()) {
                        nodes.add(Map.of("id", r.getString(1), "path", r.getString(2) == null ? "" : r.getString(2)));
                    }
                }
            }
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT id, coalesce(display_name, username) FROM principal WHERE kind = 'HUMAN' AND lifecycle_state = 'ACTIVE' ORDER BY 2 LIMIT 200");
                    ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    owners.add(Map.of("id", r.getString(1), "name", r.getString(2)));
                }
            }
        }
        body.put("nodes", nodes);
        body.put("owners", owners);
        return json(body);
    }

    /** {@code POST /api/ui/settings/connectors}. */
    public Dispatcher.Response create(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        return json(connector(connectors.create(request.principal(), text(body, "code").orElse(""), text(body, "displayName").orElse(""),
                text(body, "kind").orElse(""), config(body), text(body, "credential").map(String::toCharArray), instant(body, "credentialExpiresAt"),
                uuid(body, "scopeNodeId"), uuid(body, "ownerPrincipalId"), integer(body, "observeEveryMinutes").orElse(60))));
    }

    /** {@code POST /api/ui/settings/connectors/{id}}. */
    public Dispatcher.Response update(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        int rowVersion = body.get("rowVersion") instanceof Number n ? n.intValue() : -1;
        if (rowVersion < 0) {
            return new Dispatcher.Response(400, Map.of("code", "ROW_VERSION_REQUIRED", "message", "the row version being edited is required"), Map.of());
        }
        return json(connector(connectors.update(request.principal(), id(request), text(body, "displayName").orElse(null),
                body.containsKey("config") ? config(body) : null, body.containsKey("scopeNodeId"), uuid(body, "scopeNodeId"),
                integer(body, "observeEveryMinutes"), body.containsKey("credentialExpiresAt"), instant(body, "credentialExpiresAt"),
                uuid(body, "ownerPrincipalId"), rowVersion)));
    }

    /** {@code POST /api/ui/settings/connectors/{id}/transition}. */
    public Dispatcher.Response transition(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        return json(connector(connectors.transition(request.principal(), id(request), String.valueOf(body.getOrDefault("state", "")))));
    }

    /** {@code POST /api/ui/settings/connectors/{id}/rotate}: a new credential, or the end of an overlap. */
    public Dispatcher.Response rotate(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        if (Boolean.TRUE.equals(body.get("retirePrevious"))) {
            return json(connector(connectors.retirePreviousCredential(request.principal(), id(request))));
        }
        return json(connector(connectors.rotate(request.principal(), id(request), text(body, "credential").orElse("").toCharArray(),
                instant(body, "credentialExpiresAt"))));
    }

    /** {@code POST /api/ui/settings/connectors/{id}/probe}. */
    public Dispatcher.Response probe(Dispatcher.Request request) throws SQLException {
        return json(connectors.probe(request.principal(), id(request)));
    }

    /** {@code GET /api/ui/settings/connectors/{id}/operations}. */
    public Dispatcher.Response operations(Dispatcher.Request request) throws SQLException {
        return json(Map.of("rows", connectors.operations(request.principal(), id(request)).stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.id().toString());
            m.put("kind", o.kind());
            m.put("status", o.status());
            m.put("attempts", o.attempts());
            m.put("failureClass", o.failureClass().orElse(null));
            m.put("detail", o.detail().orElse(null));
            m.put("createdAt", o.createdAt().toString());
            m.put("finishedAt", o.finishedAt().map(OffsetDateTime::toString).orElse(null));
            m.put("referenceId", o.referenceId().map(UUID::toString).orElse(null));
            return m;
        }).toList()));
    }

    // ==============================================================================================
    // References — on a finding, and the divergence queue
    // ==============================================================================================

    /** {@code GET /api/ui/findings/{id}/references}. */
    public Dispatcher.Response findingReferences(Dispatcher.Request request) throws SQLException {
        Optional<OutboundReferenceService.View> view = references.forFinding(request.principal(), id(request));
        if (view.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", view.get().references().stream().map(ConnectorApi::reference).toList());
        body.put("offers", view.get().offers().stream().map(o -> Map.of("connectorId", o.connectorId().toString(), "code", o.code(),
                "displayName", o.displayName(), "kind", o.kind())).toList());
        body.put("mayCreate", view.get().mayCreate());
        return json(body);
    }

    /** {@code POST /api/ui/findings/{id}/references} with {@code {connectorId}}. */
    public Dispatcher.Response createFindingReference(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        UUID connectorId = uuid(body, "connectorId").orElseThrow(() -> new IllegalArgumentException("a connector is required"));
        return json(reference(references.create(request.principal(), id(request), connectorId)));
    }

    /** {@code GET /api/ui/outbound-references/divergences}. */
    public Dispatcher.Response divergences(Dispatcher.Request request) throws SQLException {
        return json(Map.of("rows", references.divergences(request.principal()).stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>(reference(d.reference()));
            m.put("findingId", d.findingId().toString());
            m.put("findingTitle", d.findingTitle());
            m.put("findingLifecycle", d.findingLifecycle());
            m.put("findingSeverity", d.findingSeverity());
            m.put("requestId", d.requestId().map(UUID::toString).orElse(null));
            return m;
        }).toList(), "mayResolve", request.principal().holds(OutboundReferenceService.CREATE)));
    }

    /** {@code POST /api/ui/outbound-references/{id}/resolve} with {@code {note}}. */
    public Dispatcher.Response resolve(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        return json(reference(references.resolveDivergence(request.principal(), id(request), text(body, "note").orElse(""))));
    }

    /** {@code POST /api/ui/outbound-references/{id}/retry}. */
    public Dispatcher.Response retry(Dispatcher.Request request) throws SQLException {
        return json(reference(references.retry(request.principal(), id(request))));
    }

    // ==============================================================================================

    private static UUID id(Dispatcher.Request request) {
        try {
            return UUID.fromString(request.pathVariables().get("id"));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the identifier is not a UUID");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config(Map<String, Object> body) {
        return body.get("config") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Optional<String> text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || String.valueOf(value).isBlank() ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    private static Optional<UUID> uuid(Map<String, Object> body, String key) {
        return text(body, key).map(v -> {
            try {
                return UUID.fromString(v);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(key + " is not a UUID");
            }
        });
    }

    private static Optional<Integer> integer(Map<String, Object> body, String key) {
        return body.get(key) instanceof Number n ? Optional.of(n.intValue()) : Optional.empty();
    }

    private static Optional<OffsetDateTime> instant(Map<String, Object> body, String key) {
        return text(body, key).map(v -> {
            try {
                return v.length() == 10 ? java.time.LocalDate.parse(v).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime() : OffsetDateTime.parse(v);
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException(key + " is not a date");
            }
        });
    }

    static Map<String, Object> connector(ConnectorService.Connector c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id().toString());
        m.put("code", c.code());
        m.put("displayName", c.displayName());
        m.put("kind", c.kind());
        m.put("adapterVersion", c.adapterVersion());
        m.put("config", c.config());
        m.put("credentialHeld", c.credentialHeld());
        m.put("rotationInProgress", c.rotationInProgress());
        m.put("credentialExpiresAt", c.credentialExpiresAt().map(OffsetDateTime::toString).orElse(null));
        m.put("scopeNodeId", c.scopeNodeId().map(UUID::toString).orElse(null));
        m.put("scopePath", c.scopePath().orElse(null));
        m.put("ownerPrincipalId", c.ownerPrincipalId().toString());
        m.put("ownerName", c.ownerName());
        m.put("lifecycleState", c.lifecycleState());
        m.put("validationDiagnosis", c.validationDiagnosis().orElse(null));
        m.put("observeEveryMinutes", c.observeEveryMinutes());
        m.put("rowVersion", c.rowVersion());
        m.put("openDivergences", c.openDivergences());
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("lastSuccessAt", c.health().lastSuccessAt().map(OffsetDateTime::toString).orElse(null));
        h.put("lastAttemptAt", c.health().lastAttemptAt().map(OffsetDateTime::toString).orElse(null));
        h.put("consecutiveFailures", c.health().consecutiveFailures());
        h.put("lastFailureClass", c.health().lastFailureClass().orElse(null));
        h.put("lastFailureDetail", c.health().lastFailureDetail().orElse(null));
        h.put("circuitState", c.health().circuitState());
        h.put("circuitOpenReason", c.health().circuitOpenReason().orElse(null));
        h.put("periodAttempts", c.health().periodAttempts());
        h.put("periodSuccesses", c.health().periodSuccesses());
        h.put("successRate", c.health().successRate());
        m.put("health", h);
        return m;
    }

    static Map<String, Object> reference(OutboundReferenceService.Reference r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id().toString());
        m.put("connectorId", r.connectorId().toString());
        m.put("connectorCode", r.connectorCode());
        m.put("connectorName", r.connectorName());
        m.put("kind", r.kind());
        m.put("status", r.status());
        m.put("externalKey", r.externalKey().orElse(null));
        m.put("externalUrl", r.externalUrl().orElse(null));
        m.put("externalState", r.externalState().orElse(null));
        m.put("externalResolved", r.externalResolved().orElse(null));
        m.put("lastObservedAt", r.lastObservedAt().map(OffsetDateTime::toString).orElse(null));
        m.put("divergenceKind", r.divergenceKind().orElse(null));
        m.put("divergenceDetectedAt", r.divergenceDetectedAt().map(OffsetDateTime::toString).orElse(null));
        m.put("divergencePlatformState", r.divergencePlatformState().orElse(null));
        m.put("failureDetail", r.failureDetail().orElse(null));
        m.put("createdAt", r.createdAt().toString());
        m.put("createdByName", r.createdByName());
        return m;
    }

    private static Dispatcher.Response json(Object body) {
        return Dispatcher.Response.ok(body);
    }
}
