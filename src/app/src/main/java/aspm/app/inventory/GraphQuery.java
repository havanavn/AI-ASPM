package aspm.app.inventory;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * One node and what it is directly connected to. The data behind the estate graph.
 *
 * <h2>A neighbourhood, never the whole graph</h2>
 *
 * <p>{@code CompositionPage} already states the rule this follows: "the estate is a graph of unknown
 * depth and a reader opens one branch; sending all of it to render one screen is a payload that grows
 * with the company rather than with the page". A view-graph button that fetched the estate would
 * contradict a comment already in the interface. So this returns one node, its immediate neighbours,
 * and a flag saying which of those have neighbours of their own — the reader expands what they care
 * about and nothing else is transferred.
 *
 * <h2>Two structures, one query surface</h2>
 *
 * <p>ADR-001 keeps the organization tree and the asset graph as separate structures joined by an
 * ownership edge, and the graph a reader wants to see crosses that join: a team owns applications,
 * an application contains projects, a project is published on a domain and built from a repository.
 * So an identifier here may name either kind of node and the caller does not say which. Resolving it
 * server-side is one fewer thing a client can get wrong, and it costs one extra lookup on the miss.
 *
 * <h2>The scope predicate is the same one the lists use</h2>
 *
 * <p>{@code owning_node_id} within the caller's closure, <b>or null</b>. The null case is not an
 * oversight: an unowned asset is visible to everybody in the tenant because {@code PRD-AST-011}'s
 * unclaimed queue exists to get it claimed, and a graph that hid it would disagree with the list that
 * shows it.
 *
 * <h2>What happens at the edge of what a reader may see, and the disclosure it accepts</h2>
 *
 * <p>A relationship whose other end is outside the caller's scope is <b>omitted</b>, and the node it
 * hangs off carries {@code boundary = true}. No count and no identity: a count is an oracle, and an
 * identity is the disclosure {@code SEC-AUZ-016} exists to prevent.
 *
 * <p>The flag itself is a residual disclosure and is accepted deliberately. It tells a
 * narrowly-scoped reader that a node they can see is connected to something they cannot. The
 * alternative is silence, and silence renders a graph that <em>looks</em> complete — which is the
 * first product principle inverted: absence of evidence presented as evidence of absence. Between
 * "there is more here" and a diagram that quietly lies about being whole, this platform prefers the
 * former, and states it in a legend rather than leaving it to be inferred.
 */
public final class GraphQuery {

    private final DataSource dataSource;

    public GraphQuery(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /** {@code ASSET} or {@code ORG}. The two structures of ADR-001, in one payload. */
    public record Node(UUID id, String kind, String typeCode, String name, String lifecycleState,
            String exposureDeclared, String criticalityCode, Long findingOpen, Long criticalOpen,
            boolean boundary, boolean expandable) {
    }

    /**
     * A directed relationship.
     *
     * <p>{@code kind} is the edge type as recorded — {@code CONTAINS}, {@code PUBLISHED_ON},
     * {@code BUILDS}, {@code DEPENDS_ON} — plus two the graph adds for the org tree:
     * {@code PARENT} between two nodes and {@code OWNS} from a node to an asset. Naming them
     * separately keeps the reader able to tell accountability from technical containment, which is
     * the distinction ADR-001 exists to preserve.
     */
    public record Edge(UUID from, UUID to, String kind) {
    }

    public record Neighbourhood(Node root, List<Node> nodes, List<Edge> edges) {
    }

    /** The scope predicate, spelled once. */
    private static final String ASSET_VISIBLE =
            "(a.owning_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))"
                    + " OR a.owning_node_id IS NULL)";

    /** The facts a node carries. Enough to make the graph a security view rather than a topology. */
    private static final String ASSET_COLUMNS =
            "a.id, ty.code, a.display_name, a.lifecycle_state, a.exposure_declared, ct.code, "
                    + "p.finding_open, p.critical_open";

    private static final String ASSET_FROM =
            "  FROM asset a "
                    + "  JOIN asset_type ty ON ty.id = a.type_id "
                    + "  LEFT JOIN criticality_tier ct ON ct.id = a.criticality_tier_id "
                    // The posture rollup, for the open and critical counts. LEFT, because an asset
                    // nothing has been found in has no row and must still appear — a node missing
                    // from the graph because it is clean is the inverse of what this is for.
                    + "  LEFT JOIN application_posture p ON p.asset_id = a.id ";

    /**
     * The neighbourhood around one identifier, or empty where the caller cannot reach it.
     *
     * <p>Empty rather than a distinguishable refusal: {@code SEC-AUZ-020} makes non-existence and
     * non-authorization indistinguishable, and the endpoint answers 404 for both.
     */
    public Optional<Neighbourhood> around(Principal principal, UUID id) throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty() || id == null) {
            return Optional.empty();
        }
        try (Connection connection = TenantConnections.open(dataSource, principal)) {
            java.sql.Array scopeArray = connection.createArrayOf("uuid", scope.toArray(new UUID[0]));

            Node asset = readAsset(connection, id, scopeArray);
            if (asset != null) {
                return Optional.of(aroundAsset(connection, asset, scopeArray));
            }
            Node org = readOrgNode(connection, id, scopeArray);
            if (org != null) {
                return Optional.of(aroundOrgNode(connection, org, scopeArray));
            }
            return Optional.empty();
        }
    }

    // ----------------------------------------------------------------------------------- an asset

    private Neighbourhood aroundAsset(Connection connection, Node root, java.sql.Array scope)
            throws SQLException {
        Map<UUID, Node> nodes = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        boolean rootBoundary = false;

        // The owning node, so the graph crosses from the asset graph into the organization tree at
        // the one place ADR-001 says they join.
        Node owner = ownerOf(connection, root.id(), scope);
        if (owner != null) {
            nodes.put(owner.id(), owner);
            edges.add(new Edge(owner.id(), root.id(), "OWNS"));
        }

        // Relationships in BOTH directions. An application contains a project and a project is
        // published on a domain, so a reader standing on the project needs the edge above it and the
        // edges below it; a single-direction query would make the graph depend on where you entered.
        //
        // Serves: ix_asset_relationship__from_current and __to_current, both partial on
        // valid_until IS NULL, which is what this asks for.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT r.edge_type, r.from_asset_id, r.to_asset_id, " + ASSET_COLUMNS + ", "
                        + ASSET_VISIBLE + " AS visible "
                        + ASSET_FROM
                        + "  JOIN asset_relationship r "
                        + "    ON (r.from_asset_id = ? AND r.to_asset_id = a.id) "
                        + "    OR (r.to_asset_id = ? AND r.from_asset_id = a.id) "
                        + " WHERE r.valid_until IS NULL AND a.lifecycle_state <> 'RETIRED'")) {
            statement.setArray(1, scope);
            statement.setObject(2, root.id());
            statement.setObject(3, root.id());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    String edgeType = results.getString(1);
                    UUID from = results.getObject(2, UUID.class);
                    UUID to = results.getObject(3, UUID.class);
                    if (!results.getBoolean(12)) {
                        // Outside the caller's scope. The edge is dropped and the ROOT is marked, so
                        // the reader knows the picture is partial without learning what is missing.
                        rootBoundary = true;
                        continue;
                    }
                    Node other = nodeFrom(results, 4);
                    nodes.putIfAbsent(other.id(), other);
                    edges.add(new Edge(from, to, edgeType));
                }
            }
        }
        return finish(connection, withBoundary(root, rootBoundary), nodes, edges, scope);
    }

    private Node ownerOf(Connection connection, UUID assetId, java.sql.Array scope)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT n.id, t.code, n.name, n.lifecycle_state "
                        + "  FROM asset a JOIN org_node n ON n.id = a.owning_node_id "
                        + "  JOIN org_node_type t ON t.id = n.type_id "
                        + " WHERE a.id = ? AND n.id IN "
                        + "   (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))")) {
            statement.setObject(1, assetId);
            statement.setArray(2, scope);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? orgNodeFrom(results) : null;
            }
        }
    }

    // ------------------------------------------------------------------------- an organization node

    private Neighbourhood aroundOrgNode(Connection connection, Node root, java.sql.Array scope)
            throws SQLException {
        Map<UUID, Node> nodes = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        boolean rootBoundary = false;

        // The parent, and the children one level down. Depth one, like everything else here: an
        // organization tree rendered whole is the payload this class exists not to send.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT n.id, t.code, n.name, n.lifecycle_state, n.parent_id, "
                        + "       (n.id IN (SELECT descendant_id FROM org_closure "
                        + "                  WHERE ancestor_id = ANY (?))) AS visible "
                        + "  FROM org_node n JOIN org_node_type t ON t.id = n.type_id "
                        + " WHERE (n.parent_id = ? OR n.id = (SELECT parent_id FROM org_node "
                        + "                                    WHERE id = ?)) "
                        + "   AND n.lifecycle_state <> 'DEPRECATED'")) {
            statement.setArray(1, scope);
            statement.setObject(2, root.id());
            statement.setObject(3, root.id());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    if (!results.getBoolean(6)) {
                        rootBoundary = true;
                        continue;
                    }
                    Node node = orgNodeFrom(results);
                    UUID parent = results.getObject(5, UUID.class);
                    nodes.putIfAbsent(node.id(), node);
                    // Direction is parent -> child whichever end the root is, so the arrow always
                    // means "contains" rather than "related to".
                    edges.add(root.id().equals(parent)
                            ? new Edge(root.id(), node.id(), "PARENT")
                            : new Edge(node.id(), root.id(), "PARENT"));
                }
            }
        }

        // The assets this node owns directly. Not the subtree's: a division that owns nothing itself
        // should look like it owns nothing, and the children are on the graph for the reader to open.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + ASSET_COLUMNS + ASSET_FROM
                        + " WHERE a.owning_node_id = ? AND a.lifecycle_state <> 'RETIRED' "
                        + " ORDER BY ty.ordinal, a.display_name LIMIT 200")) {
            statement.setObject(1, root.id());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    Node asset = nodeFrom(results, 1);
                    nodes.putIfAbsent(asset.id(), asset);
                    edges.add(new Edge(root.id(), asset.id(), "OWNS"));
                }
            }
        }
        return finish(connection, withBoundary(root, rootBoundary), nodes, edges, scope);
    }

    // --------------------------------------------------------------------------------- expandable

    /**
     * Which returned neighbours have neighbours of their own.
     *
     * <p>Without this a leaf and an unexpanded branch look identical, and a graph where every node
     * appears to open is a graph that teaches the reader to click everything. Counted with one query
     * over the returned identifiers rather than one per node.
     *
     * <p>A node is expandable when it has any CURRENT relationship other than the edges already in
     * this response, or — for an organization node — any child or any owned asset.
     */
    private Neighbourhood finish(Connection connection, Node root, Map<UUID, Node> nodes,
            List<Edge> edges, java.sql.Array scope) throws SQLException {
        Set<UUID> assetIds = new LinkedHashSet<>();
        Set<UUID> orgIds = new LinkedHashSet<>();
        for (Node node : nodes.values()) {
            ("ASSET".equals(node.kind()) ? assetIds : orgIds).add(node.id());
        }

        Set<UUID> expandable = new LinkedHashSet<>();
        if (!assetIds.isEmpty()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT a.id FROM asset a "
                            + " WHERE a.id = ANY (?) AND EXISTS ("
                            + "   SELECT 1 FROM asset_relationship r "
                            + "    WHERE r.valid_until IS NULL "
                            + "      AND (r.from_asset_id = a.id OR r.to_asset_id = a.id) "
                            // The edge back to the node the reader is standing on does not count.
                            + "      AND r.from_asset_id <> ? AND r.to_asset_id <> ?)")) {
                statement.setArray(1, connection.createArrayOf("uuid", assetIds.toArray()));
                statement.setObject(2, root.id());
                statement.setObject(3, root.id());
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        expandable.add(results.getObject(1, UUID.class));
                    }
                }
            }
        }
        if (!orgIds.isEmpty()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT n.id FROM org_node n "
                            + " WHERE n.id = ANY (?) AND (EXISTS ("
                            + "     SELECT 1 FROM org_node c WHERE c.parent_id = n.id "
                            + "       AND c.lifecycle_state <> 'DEPRECATED') "
                            + "   OR EXISTS (SELECT 1 FROM asset a WHERE a.owning_node_id = n.id "
                            + "               AND a.lifecycle_state <> 'RETIRED'))")) {
                statement.setArray(1, connection.createArrayOf("uuid", orgIds.toArray()));
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        expandable.add(results.getObject(1, UUID.class));
                    }
                }
            }
        }

        List<Node> out = new ArrayList<>();
        for (Node node : nodes.values()) {
            out.add(new Node(node.id(), node.kind(), node.typeCode(), node.name(),
                    node.lifecycleState(), node.exposureDeclared(), node.criticalityCode(),
                    node.findingOpen(), node.criticalOpen(), node.boundary(),
                    expandable.contains(node.id())));
        }
        return new Neighbourhood(root, List.copyOf(out), List.copyOf(edges));
    }

    // ------------------------------------------------------------------------------------ reading

    private Node readAsset(Connection connection, UUID id, java.sql.Array scope) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + ASSET_COLUMNS + ASSET_FROM + " WHERE a.id = ? AND " + ASSET_VISIBLE)) {
            statement.setObject(1, id);
            statement.setArray(2, scope);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? nodeFrom(results, 1) : null;
            }
        }
    }

    private Node readOrgNode(Connection connection, UUID id, java.sql.Array scope)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT n.id, t.code, n.name, n.lifecycle_state "
                        + "  FROM org_node n JOIN org_node_type t ON t.id = n.type_id "
                        + " WHERE n.id = ? AND n.id IN "
                        + "   (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))")) {
            statement.setObject(1, id);
            statement.setArray(2, scope);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? orgNodeFrom(results) : null;
            }
        }
    }

    private static Node nodeFrom(ResultSet results, int offset) throws SQLException {
        Long open = (Long) results.getObject(offset + 6);
        Long critical = (Long) results.getObject(offset + 7);
        return new Node(results.getObject(offset, UUID.class), "ASSET",
                results.getString(offset + 1), results.getString(offset + 2),
                results.getString(offset + 3), results.getString(offset + 4),
                results.getString(offset + 5), open, critical, false, false);
    }

    private static Node orgNodeFrom(ResultSet results) throws SQLException {
        return new Node(results.getObject(1, UUID.class), "ORG", results.getString(2),
                results.getString(3), results.getString(4), null, null, null, null, false, false);
    }

    private static Node withBoundary(Node node, boolean boundary) {
        return new Node(node.id(), node.kind(), node.typeCode(), node.name(), node.lifecycleState(),
                node.exposureDeclared(), node.criticalityCode(), node.findingOpen(),
                node.criticalOpen(), boundary, node.expandable());
    }
}
