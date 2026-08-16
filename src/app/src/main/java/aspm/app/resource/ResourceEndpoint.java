package aspm.app.resource;

import aspm.app.api.KeysetPage;
import aspm.app.api.RequestValidation;
import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import aspm.app.runtime.RequestScope;
import aspm.kernel.authorization.contract.AuthorizationRequest;
import aspm.kernel.authorization.contract.ObjectReference;
import aspm.kernel.authorization.contract.PermissionId;
import aspm.kernel.tenantcontext.contract.AuthorizedQuery;
import aspm.kernel.tenantcontext.contract.ScopePredicate;
import aspm.module.organizationscope.application.OrgScopeResolverAdapter;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.PrincipalId;
import java.sql.Array;
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
 * Generic handlers over a {@link ResourceGroup}. The single implementation of DOC-05's collection,
 * retrieval, creation and update semantics.
 *
 * <h2>Four properties, enforced once each rather than once per group</h2>
 *
 * <ol>
 *   <li><b>The scope predicate is composed into the SQL</b>, never applied afterwards. Product principle 4
 *       and {@code SEC-AUZ-016}: post-filtering means the database returned rows the caller may not see,
 *       and every path that forgets to drop them — a count, a log line, an aggregate, an export — leaks.
 *   <li><b>Retrieval by identifier re-validates against the object</b> ({@code SEC-AUZ-017}) and returns
 *       absence rather than denial ({@code PRD-API-036}), so the API cannot enumerate what a caller may
 *       not read.
 *   <li><b>Keyset pagination only</b>, with the primary key as tiebreaker ({@code PRD-API-006},
 *       {@code TST-PLT-008}).
 *   <li><b>Optimistic concurrency on update.</b> {@code row_version} is required and the update is
 *       conditional on it, so a lost update is a rejected request rather than a silently overwritten one.
 * </ol>
 */
public final class ResourceEndpoint {

    private final DataSource dataSource;
    private final ResourceGroup group;
    private final aspm.app.audit.AuditTrail audit;

    public ResourceEndpoint(DataSource dataSource, ResourceGroup group,
            aspm.app.audit.AuditTrail audit) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
        this.group = Objects.requireNonNull(group, "a resource group is required");
        // Not optional, and not nullable. An audit trail that a call site can decline to pass is an
        // audit trail with holes exactly where somebody was in a hurry (CON-PLT-021, PP-5).
        this.audit = Objects.requireNonNull(audit, "an audit trail is required");
    }

    public ResourceGroup group() {
        return group;
    }

    // ------------------------------------------------------------------ collection

    /** {@code GET /v1/<group>}. Class A. */
    public Dispatcher.Response list(Dispatcher.Request request) throws SQLException {
        Principal principal = request.principal();
        AuthorizedQuery authorized = authorize(principal,
                AuthorizationRequest.forCollection(new PrincipalId(principal.principalId()),
                        new PermissionId(group.readPermission())));

        List<RequestValidation.TypedFilter> filters = new ArrayList<>();
        for (Map.Entry<String, String> parameter : request.query().entrySet()) {
            if ("limit".equals(parameter.getKey()) || "cursor".equals(parameter.getKey())) {
                continue;
            }
            filters.add(new RequestValidation.TypedFilter(parameter.getKey(),
                    RequestValidation.TypedFilter.Operator.EQUALS, parameter.getValue()));
        }
        RequestValidation.validateFilters(request.operation().filterableFields(), filters);

        int limit = KeysetPage.clampPageSize(pageSize(request.query().get("limit")));
        Optional<KeysetPage.Cursor> cursor = Optional.ofNullable(request.query().get("cursor"))
                .map(KeysetPage.Cursor::decode);

        StringBuilder sql = new StringBuilder("SELECT ").append(group.projection())
                .append(" FROM ").append(group.table()).append(" WHERE 1 = 1");
        List<Object> arguments = new ArrayList<>();

        Optional<String> scopeClause = scopeClause(authorized.scope());
        scopeClause.ifPresent(clause -> sql.append(clause));

        for (RequestValidation.TypedFilter filter : filters) {
            sql.append(" AND ").append(filter.field()).append(" = ?");
            arguments.add(coerce(filter.field(), String.valueOf(filter.value())));
        }
        if (cursor.isPresent()) {
            sql.append(" AND (").append(group.sortColumn()).append(", id) > (?, ?)");
            arguments.add(coerce(group.sortColumn(), cursor.orElseThrow().sortValue()));
            arguments.add(UUID.fromString(cursor.orElseThrow().tiebreaker()));
        }
        sql.append(" ORDER BY ").append(group.sortColumn()).append(", id LIMIT ?");
        arguments.add(Integer.valueOf(limit + 1));

        List<Map<String, Object>> rows = new ArrayList<>();
        inTenantTransaction(principal, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = bindScope(statement, connection, authorized.scope());
                for (Object argument : arguments) {
                    statement.setObject(++index, argument);
                }
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        rows.add(representation(results));
                    }
                }
            }
            return null;
        });

        KeysetPage<Map<String, Object>> page = KeysetPage.of(rows, limit,
                row -> new KeysetPage.Cursor(String.valueOf(row.get(group.sortColumn())),
                        String.valueOf(row.get("id"))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page.items());
        body.put("has_more", Boolean.valueOf(page.hasMore()));
        body.put("next_cursor", page.nextCursor().map(KeysetPage.Cursor::encode).orElse(null));
        return Dispatcher.Response.ok(body);
    }

    // ------------------------------------------------------------------ retrieval

    /** {@code GET /v1/<group>/{id}}. Class A, re-validated against the object. */
    public Dispatcher.Response get(Dispatcher.Request request) throws SQLException {
        Principal principal = request.principal();
        Optional<UUID> id = identifier(request);
        if (id.isEmpty()) {
            // A malformed identifier is absence, not a validation error: distinguishing them teaches a
            // caller the shape of an identifier that exists.
            return Dispatcher.Response.notFound();
        }

        AuthorizedQuery authorized = authorize(principal, AuthorizationRequest.forObject(
                new PrincipalId(principal.principalId()), new PermissionId(group.readPermission()),
                new ObjectReference(group.table(), id.orElseThrow())));

        StringBuilder sql = new StringBuilder("SELECT ").append(group.projection())
                .append(" FROM ").append(group.table()).append(" WHERE id = ?");
        scopeClause(authorized.scope()).ifPresent(clause -> sql.append(clause));

        Map<String, Object> found = inTenantTransaction(principal, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                statement.setObject(1, id.orElseThrow());
                bindScopeAfter(statement, connection, authorized.scope(), 1);
                try (ResultSet results = statement.executeQuery()) {
                    return results.next() ? representation(results) : null;
                }
            }
        });
        return found == null ? Dispatcher.Response.notFound() : Dispatcher.Response.ok(found);
    }

    // ------------------------------------------------------------------ creation

    /** {@code POST /v1/<group>}. Class B or E. */
    public Dispatcher.Response create(Dispatcher.Request request) throws SQLException {
        Principal principal = request.principal();
        AuthorizedQuery authorized = authorize(principal, AuthorizationRequest.forCollection(
                new PrincipalId(principal.principalId()), new PermissionId(group.createPermission())));

        Map<String, Object> body = request.body().orElseThrow(
                () -> new IllegalArgumentException("a request body is required"));
        RequestValidation.rejectUnknownFields(group.writableOnCreate(), body);
        try {
            requireScopeBearingFieldsInScope(principal, authorized, body);
        } catch (OutOfScope refused) {
            // Absence, not denial. PRD-API-036: a caller must not be able to tell a node it may not
            // write to from one that does not exist, or the difference maps the hierarchy. The reason
            // goes to the log, where the operator can see it and the caller cannot.
            System.getLogger("aspm.resource").log(System.Logger.Level.INFO,
                    "refused a create on " + group.name() + ": " + refused.getMessage());
            return Dispatcher.Response.notFound();
        }

        List<String> columns = new ArrayList<>(group.writableOnCreate().stream()
                .filter(body::containsKey).sorted().toList());
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("the body sets no writable field");
        }

        // Columns the schema requires and the request cannot carry — identity, provenance, coverage
        // timestamps. Derived from the validated body, never from it directly, and computed AFTER the
        // unknown-field rejection above so a caller cannot reach them by naming one.
        Map<String, Object> derived = group.completeOnCreate().apply(Map.copyOf(body));
        for (String column : derived.keySet()) {
            if (group.writableOnCreate().contains(column)) {
                throw new IllegalStateException(column + " is both caller-writable and derived; one of "
                        + "the two would silently win");
            }
        }
        List<String> derivedColumns = new ArrayList<>(new java.util.TreeSet<>(derived.keySet()));

        StringBuilder sql = new StringBuilder("INSERT INTO ").append(group.table())
                .append(" (tenant_id");
        for (String column : columns) {
            sql.append(", ").append(column);
        }
        for (String column : derivedColumns) {
            sql.append(", ").append(column);
        }
        sql.append(") VALUES (?");
        for (String column : columns) {
            sql.append(", ?").append(placeholderCast(column));
        }
        for (String column : derivedColumns) {
            // The one value the engine produces rather than the application: see SqlDefault.
            Object supplied = derived.get(column);
            if (supplied == ResourceGroup.SqlDefault.NOW) {
                sql.append(", now()");
            } else {
                sql.append(", ?").append(supplied instanceof ResourceGroup.JsonValue
                        ? "::jsonb" : placeholderCast(column));
            }
        }
        sql.append(") RETURNING ").append(group.projection());

        Map<String, Object> created = inTenantTransaction(principal, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                // The tenant comes from the principal. There is no body field for it — writableOnCreate
                // rejects tenant_id at construction — so a caller cannot name another tenant, and the
                // row-level WITH CHECK would refuse it if one somehow could.
                statement.setObject(1, principal.tenantId());
                int index = 1;
                for (String column : columns) {
                    statement.setObject(++index, value(column, body.get(column)));
                }
                for (String column : derivedColumns) {
                    Object supplied = derived.get(column);
                    if (supplied instanceof ResourceGroup.JsonValue json) {
                        statement.setObject(++index, json.json());
                    } else if (supplied != ResourceGroup.SqlDefault.NOW) {
                        statement.setObject(++index, supplied);
                    }
                }
                Map<String, Object> row;
                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    row = representation(results);
                }
                // In THIS transaction, after the insert and before the commit. If the event cannot be
                // written the create does not happen — CON-PLT-021 makes that trade deliberately, and
                // it only holds while the two share a transaction.
                audit.domainChange(connection, principal, group.table(),
                        aspm.kernel.audit.contract.DomainChangeKind.CREATED,
                        UUID.fromString(String.valueOf(row.get("id"))), scopeOf(row), row);
                return row;
            }
        });
        return Dispatcher.Response.created(created,
                "/api/v1/" + group.name() + "/" + created.get("id"));
    }

    // ------------------------------------------------------------------ update

    /** {@code PATCH /v1/<group>/{id}}. Class B, conditional on {@code row_version}. */
    public Dispatcher.Response patch(Dispatcher.Request request) throws SQLException {
        Principal principal = request.principal();
        Optional<UUID> id = identifier(request);
        if (id.isEmpty()) {
            return Dispatcher.Response.notFound();
        }

        AuthorizedQuery authorized = authorize(principal, AuthorizationRequest.forObject(
                new PrincipalId(principal.principalId()), new PermissionId(group.updatePermission()),
                new ObjectReference(group.table(), id.orElseThrow())));

        Map<String, Object> body = request.body().orElseThrow(
                () -> new IllegalArgumentException("a request body is required"));

        java.util.Set<String> declared = new java.util.LinkedHashSet<>(group.writableOnUpdate());
        declared.add("row_version");
        RequestValidation.rejectUnknownFields(declared, body);

        Object version = body.get("row_version");
        if (!(version instanceof Number expected)) {
            // DOC-05 §12: row_version required. Without it the update is last-writer-wins, and the loser
            // is not told — which for organizational structure means a scope change nobody made.
            throw new IllegalArgumentException(
                    "row_version is required. Without it a concurrent change is overwritten silently.");
        }

        List<String> columns = group.writableOnUpdate().stream()
                .filter(body::containsKey).sorted().toList();
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("the body sets no updatable field");
        }

        StringBuilder sql = new StringBuilder("UPDATE ").append(group.table()).append(" SET ");
        for (int i = 0; i < columns.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(columns.get(i)).append(" = ?")
                    .append(placeholderCast(columns.get(i)));
        }
        sql.append(", row_version = row_version + 1, updated_at = now()")
                .append(" WHERE id = ? AND row_version = ?");
        scopeClause(authorized.scope()).ifPresent(clause -> sql.append(clause));
        sql.append(" RETURNING ").append(group.projection());

        Map<String, Object> updated = inTenantTransaction(principal, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 0;
                for (String column : columns) {
                    statement.setObject(++index, value(column, body.get(column)));
                }
                statement.setObject(++index, id.orElseThrow());
                statement.setInt(++index, expected.intValue());
                bindScopeAfter(statement, connection, authorized.scope(), index);
                try (ResultSet results = statement.executeQuery()) {
                    if (!results.next()) {
                        return null;
                    }
                    Map<String, Object> row = representation(results);
                    // The payload carries what the caller asked to change, not the whole row: DOC-14
                    // wants before/after values, and a full row on every patch buries the one field
                    // that moved under thirty that did not.
                    audit.domainChange(connection, principal, group.table(),
                            aspm.kernel.audit.contract.DomainChangeKind.UPDATED,
                            id.orElseThrow(), scopeOf(row), changed(body, row));
                    return row;
                }
            }
        });

        if (updated == null) {
            // Three causes, one response: the object does not exist, it is out of scope, or the version
            // did not match. Distinguishing the third from the first two would confirm existence to a
            // caller who may not see it, so a 409 is only safe once existence is already established —
            // and here it is not.
            return Dispatcher.Response.notFound();
        }
        return Dispatcher.Response.ok(updated);
    }

    /**
     * Rows of a child table belonging to one parent.
     *
     * <p>Authorization is the PARENT's. A child row carries no scope descriptor of its own — a role
     * account belongs to a request and has no independent existence — so authorizing the parent and then
     * reading children is correct here in a way it would not be for a sibling resource. The parent read
     * is a full {@link #get}, so it re-validates against the object and returns absence for anything out
     * of scope; this method is only reached once that has passed.
     *
     * @param table the child table. Validated against the identifier shape, not escaped
     * @param foreignKey the column naming the parent
     * @param columns the projection. Explicit, so a column added by a later migration is not exposed
     */
    public List<Map<String, Object>> children(Principal principal, String table, String foreignKey,
            UUID parentId, List<String> columns) throws SQLException {
        requireIdentifier(table);
        requireIdentifier(foreignKey);
        columns.forEach(ResourceEndpoint::requireIdentifier);

        String sql = "SELECT " + String.join(", ", columns) + " FROM " + table
                + " WHERE " + foreignKey + " = ? ORDER BY 1";
        List<Map<String, Object>> rows = new ArrayList<>();
        inTenantTransaction(principal, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, parentId);
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (String column : columns) {
                            Object value = results.getObject(column);
                            row.put(column, value instanceof UUID uuid ? uuid.toString() : value);
                        }
                        rows.add(row);
                    }
                }
            }
            return null;
        });
        return List.copyOf(rows);
    }

    private static final java.util.regex.Pattern IDENTIFIER =
            java.util.regex.Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private static void requireIdentifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "'" + value + "' is not an acceptable identifier. These are interpolated into SQL, "
                            + "so they are validated rather than escaped — escaping an identifier is a "
                            + "different operation from escaping a value and the two get confused.");
        }
    }

    // ------------------------------------------------------------------ authorization and scope

    private AuthorizedQuery authorize(Principal principal, AuthorizationRequest authorizationRequest) {
        if (!principal.holds(authorizationRequest.permission().code())) {
            throw new Dispatcher.UnauthorizedException(
                    "principal does not hold " + authorizationRequest.permission().code());
        }
        var gate = new aspm.kernel.authorization.application.ScopeResolvingAuthorizationGate(
                new OrgScopeResolverAdapter(new RequestScope(dataSource, principal)),
                (context, denied, denial) -> System.getLogger("aspm.authz").log(
                        System.Logger.Level.INFO, "denied " + denied.permission().code()));
        return gate.authorize(authorizationRequest)
                .orElseThrow(() -> new Dispatcher.UnauthorizedException("denied"));
    }

    /**
     * The scope predicate as a SQL fragment.
     *
     * <p>Composed into the query rather than applied to its result. {@code SEC-AUZ-016}: post-filtering
     * means the database returned rows the caller may not see, and then every path that forgets to drop
     * them leaks — a count, an aggregate, a log line, an export.
     */
    private Optional<String> scopeClause(ScopePredicate scope) {
        if (!group.scoped() || scope.unrestricted()) {
            return Optional.empty();
        }
        return Optional.of(" AND " + group.scopeColumn().orElseThrow() + " = ANY (?)");
    }

    /**
     * Re-validates every body field that names an organizational node, before the insert.
     *
     * <p><b>What this is guarding against.</b> A create was authorized on the collection permission
     * alone — "may this principal create assets at all" — and then inserted whatever {@code
     * owning_node_id} or {@code parent_id} the client sent. Nothing checked that the named node lay
     * inside the caller's scope, and row-level security bounds the write to the tenant and nothing
     * narrower. So a principal scoped to one division could file an asset into another's estate, or
     * graft a subtree under somebody else's node and change their rollups. Class B declares {@code
     * PATH_AND_BODY_IDENTIFIERS} and only the path half was ever performed.
     *
     * <p>The check is the same containment the reads compose, so a caller can only create where it can
     * already see — PP-4, scope derived and never asserted by the client.
     *
     * <p><b>Absent is refused too.</b> A scope-bearing field the body omits leaves the row outside every
     * scope, which for {@code org_node} means a new root beside the tenant's hierarchy and for {@code
     * asset} means an unclaimed row its own creator cannot read back. An unrestricted caller may do
     * both deliberately; a scoped one may not do either by accident.
     */
    private void requireScopeBearingFieldsInScope(Principal principal, AuthorizedQuery authorized,
            Map<String, Object> body) {
        requireInScope(group.scopeBearingOnCreate(), authorized.scope(), body);
    }

    /**
     * The check itself, separated from the group so it can be asserted directly.
     *
     * <p>Package-private and static: a control whose only exercise is through a database, a dispatcher
     * and an authorization gate is a control nobody writes a test for, and this is the one that decides
     * whether a caller can write outside its authority.
     */
    static void requireInScope(java.util.Set<String> scopeBearing, ScopePredicate scope,
            Map<String, Object> body) {
        if (scopeBearing.isEmpty() || scope.unrestricted()) {
            return;
        }
        java.util.Set<UUID> permitted = scope.permittedNodes().stream()
                .map(OrgNodeId::value).collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (String field : scopeBearing) {
            Object named = body.get(field);
            if (named == null) {
                throw new OutOfScope(field + " is required: a caller whose authority is limited to part "
                        + "of the organization cannot create a row that belongs to no part of it");
            }
            UUID node;
            try {
                node = UUID.fromString(String.valueOf(named));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(field + " must be an identifier", e);
            }
            if (!permitted.contains(node)) {
                // Indistinguishable from a node that does not exist, deliberately: PRD-API-036, and
                // the alternative lets a caller map the hierarchy by watching which identifiers are
                // refused differently.
                throw new OutOfScope(field + " does not name a node this caller may write to");
            }
        }
    }

    /** A write aimed outside the caller's scope. Rendered as absence, never as a different refusal. */
    static final class OutOfScope extends RuntimeException {
        private static final long serialVersionUID = 1L;

        OutOfScope(String message) {
            super(message);
        }
    }

    /**
     * The node an audited row belongs to, read off the row that was just written.
     *
     * <p>Off the row rather than off the request, because the request is what was asked for and the row
     * is what happened. For a group scoped by its own identifier — {@code org_node} — the node IS the
     * row, which is why the scope column is consulted rather than a fixed field name.
     */
    private UUID scopeOf(Map<String, Object> row) {
        Optional<String> column = group.scopeColumn();
        if (column.isEmpty()) {
            // Tenant-wide configuration: a node type belongs to no node, and inventing one would place
            // the event somewhere in the tree it did not happen.
            return null;
        }
        Object value = row.get(column.orElseThrow());
        return value == null ? null : UUID.fromString(String.valueOf(value));
    }

    /**
     * The fields this update actually changed, as the row now reads them.
     *
     * <p>Keyed on what the caller asked to set, valued from what was stored — so a value the database
     * normalized, defaulted or rejected is recorded as it ended up, not as it was sent.
     */
    private static Map<String, Object> changed(Map<String, Object> body, Map<String, Object> row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String field : body.keySet()) {
            if (row.containsKey(field)) {
                payload.put(field, row.get(field));
            }
        }
        return payload;
    }

    private int bindScope(PreparedStatement statement, Connection connection, ScopePredicate scope)
            throws SQLException {
        if (scopeClause(scope).isEmpty()) {
            return 0;
        }
        statement.setArray(1, scopeArray(connection, scope));
        return 1;
    }

    private void bindScopeAfter(PreparedStatement statement, Connection connection, ScopePredicate scope,
            int index) throws SQLException {
        if (scopeClause(scope).isPresent()) {
            statement.setArray(index + 1, scopeArray(connection, scope));
        }
    }

    private static Array scopeArray(Connection connection, ScopePredicate scope) throws SQLException {
        return connection.createArrayOf("uuid",
                scope.permittedNodes().stream().map(OrgNodeId::value).toArray(UUID[]::new));
    }

    // ------------------------------------------------------------------ persistence

    @FunctionalInterface
    private interface InTransaction<T> {
        T apply(Connection connection) throws SQLException;
    }

    /**
     * A transaction with the tenant session bound.
     *
     * <p>{@code SET LOCAL}, never {@code SET}: a session-scoped value survives the connection's return to
     * the pool and into the next borrower's request, which is the disclosure mechanism
     * {@code OPS-DEP-010} and {@code SEC-TEN-007} name.
     */
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

    private Map<String, Object> representation(ResultSet results) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<String, ResourceGroup.ColumnKind> column : group.exposed().entrySet()) {
            String name = column.getKey();
            Object value = switch (column.getValue()) {
                case UUID -> {
                    java.util.UUID uuid = results.getObject(name, java.util.UUID.class);
                    yield uuid == null ? null : uuid.toString();
                }
                case INTEGER -> {
                    int number = results.getInt(name);
                    yield results.wasNull() ? null : Integer.valueOf(number);
                }
                case BOOLEAN -> {
                    boolean flag = results.getBoolean(name);
                    yield results.wasNull() ? null : Boolean.valueOf(flag);
                }
                case TIMESTAMP -> {
                    var instant = results.getObject(name, java.time.OffsetDateTime.class);
                    // NFR-INT-004: stored and rendered in UTC. Rendering in a server-local zone would
                    // make the same record read differently depending on where it was served from.
                    yield instant == null ? null
                            : instant.toInstant().toString();
                }
                case TEXT_ARRAY -> {
                    Array array = results.getArray(name);
                    yield array == null ? List.of() : List.of((Object[]) array.getArray());
                }
                default -> results.getString(name);
            };
            row.put(name, value);
        }
        return row;
    }

    private Object value(String column, Object supplied) {
        ResourceGroup.ColumnKind kind = group.exposed().get(column);
        if (supplied == null) {
            return null;
        }
        return switch (kind) {
            case UUID -> UUID.fromString(String.valueOf(supplied));
            case INTEGER -> Integer.valueOf(((Number) supplied).intValue());
            case BOOLEAN -> Boolean.valueOf(Boolean.TRUE.equals(supplied));
            case JSON -> aspm.app.runtime.Json.write(supplied);
            case TEXT_ARRAY -> String.valueOf(supplied);
            default -> String.valueOf(supplied);
        };
    }

    private Object coerce(String column, String supplied) {
        return switch (group.exposed().getOrDefault(column, ResourceGroup.ColumnKind.TEXT)) {
            case UUID -> UUID.fromString(supplied);
            case INTEGER -> Integer.valueOf(supplied);
            case BOOLEAN -> Boolean.valueOf(supplied);
            default -> supplied;
        };
    }

    private String placeholderCast(String column) {
        return group.exposed().get(column) == ResourceGroup.ColumnKind.JSON ? "::jsonb" : "";
    }

    private static Optional<UUID> identifier(Dispatcher.Request request) {
        try {
            return Optional.of(UUID.fromString(request.pathVariables().get("id")));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private static int pageSize(String value) {
        if (value == null || value.isBlank()) {
            return KeysetPage.DEFAULT_PAGE_SIZE;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be an integer");
        }
    }
}
