package aspm.app.authz;

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
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Authority that depends on the OBJECT as well as the caller. DOC-07, ADR-036.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>{@link aspm.app.api.OperationRegistry} declares one permission per operation, and the dispatcher
 * enforces it before a handler runs. That is the right shape for almost everything and it cannot
 * express "the security team, or whoever owns this particular project, or the person who raised this
 * particular request". Three surfaces need exactly that.
 *
 * <p>The alternative was to write the composite test at each of those surfaces. That is how
 * enforcement points get omitted — DOC-07 says so directly: "contexts do not implement their own
 * checks". So the operation's declared permission is the <b>floor</b>, deliberately the weakest thing
 * that makes the request sensible at all, and every object-level decision is made here. One class,
 * one place to read, one place to test.
 *
 * <h2>The three authorities, and the reasoning behind each</h2>
 *
 * <ul>
 *   <li><b>Granting on an asset.</b> {@code ast.asset.grant}, or owning the asset. An owner who
 *       cannot delegate is an owner in name only, and the alternative — every delegation going
 *       through a central administrator — is the bottleneck this level was introduced to remove.
 *   <li><b>Raising a request for a project.</b> The security team may raise against anything they can
 *       see, because they raise retests and incident-driven work. Everybody else needs the project's
 *       owner to have said so. This is the narrowing the platform previously lacked: organization
 *       scope let anybody who could SEE a project ask for work against it.
 *   <li><b>Managing a request's participants.</b> The security team, the owner of the project the
 *       request names, or the person who raised it. The requester is included because they are the
 *       one who knows which developers are actually on the work, and excluding them would route every
 *       addition through somebody with less information.
 * </ul>
 *
 * <h2>What a participant may do, and the one thing they may not</h2>
 *
 * <p>Read the request and its findings, comment, and claim a fix. <b>Not close a finding.</b> A
 * platform where the team being assessed closes its own findings measures nothing — see V032, which
 * makes the claim a separate fact from the closure for that reason.
 */
public final class ObjectAuthority {

    /** Holding this means the caller may grant over any asset they can see. */
    public static final String GRANT_PERMISSION = "ast.asset.grant";

    /**
     * The security team's permission: executing assessments.
     *
     * <p>Used here as the marker for "may raise a request against anything in scope, and is an
     * authority on any request". It is not a role name — ADR-027 — and a tenant that wants a
     * different group to hold that authority moves the permission rather than editing this file.
     */
    public static final String ASSESSOR_PERMISSION = "asm.request.execute";

    /** A capability one principal holds over one asset. */
    public enum Capability {
        /** Accountable for the asset; may delegate {@link #RAISE_REQUEST} on it. */
        OWN,
        /** May ask for an assessment of this asset, and only this asset. */
        RAISE_REQUEST
    }

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public ObjectAuthority(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ----------------------------------------------------------------------------------------------

    /** Whether the caller may add or revoke grants on this asset. */
    public boolean mayGrantOn(Principal principal, UUID assetId) throws SQLException {
        if (principal == null || assetId == null) {
            return false;
        }
        return principal.holds(GRANT_PERMISSION) || holds(principal, assetId, Capability.OWN);
    }

    /**
     * Whether the caller may raise an assessment request naming this asset.
     *
     * <p>Scope is <b>not</b> checked here. The caller reaches this after the asset has been read
     * through a scoped query, which is where scope belongs — testing it twice in two shapes is how
     * the two come to disagree.
     */
    public boolean mayRaiseRequestFor(Principal principal, UUID assetId) throws SQLException {
        if (principal == null || assetId == null) {
            return false;
        }
        if (principal.holds(ASSESSOR_PERMISSION)) {
            return true;
        }
        return holds(principal, assetId, Capability.OWN)
                || holds(principal, assetId, Capability.RAISE_REQUEST);
    }

    /**
     * Whether the caller may add or remove the delivery-side people on a request.
     *
     * <p>One query rather than three round trips: the request's requester and the owners of every
     * asset it names are the same question asked of one row and its scope table.
     */
    public boolean mayManageParticipants(Principal principal, UUID requestId) throws SQLException {
        if (principal == null || requestId == null) {
            return false;
        }
        if (principal.holds(ASSESSOR_PERMISSION)) {
            return true;
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT EXISTS (SELECT 1 FROM assessment_request r "
                                + "         WHERE r.id = ? AND r.requested_by = ?) "
                                + "    OR EXISTS (SELECT 1 FROM assessment_request_scope_asset sa "
                                + "         JOIN asset_grant g ON g.asset_id = sa.asset_id "
                                + "        WHERE sa.request_id = ? AND g.principal_id = ? "
                                + "          AND g.capability = 'OWN' AND g.revoked_at IS NULL)")) {
            statement.setObject(1, requestId);
            statement.setObject(2, principal.principalId());
            statement.setObject(3, requestId);
            statement.setObject(4, principal.principalId());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && results.getBoolean(1);
            }
        }
    }

    /** Whether the caller is on the delivery side of this request. */
    public boolean isParticipant(Principal principal, UUID requestId) throws SQLException {
        if (principal == null || requestId == null) {
            return false;
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM assessment_request_participant "
                                + " WHERE request_id = ? AND principal_id = ? AND removed_at IS NULL")) {
            statement.setObject(1, requestId);
            statement.setObject(2, principal.principalId());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    /**
     * Whether the caller may say a fix is in place on findings of this request.
     *
     * <p>The security team can too, because they are the ones who record it after a conversation the
     * platform did not see. Closing the finding remains a separate act with a separate test.
     */
    public boolean mayClaimRemediation(Principal principal, UUID requestId) throws SQLException {
        if (principal == null) {
            return false;
        }
        return principal.holds(ASSESSOR_PERMISSION) || principal.holds("vul.finding.triage")
                || isParticipant(principal, requestId);
    }

    // ----------------------------------------------------------------------------------------------

    /** Every asset the caller holds a capability over, by capability. */
    public Map<Capability, Set<UUID>> capabilitiesOf(Principal principal) throws SQLException {
        Map<Capability, Set<UUID>> out = new LinkedHashMap<>();
        for (Capability capability : Capability.values()) {
            out.put(capability, new java.util.LinkedHashSet<>());
        }
        if (principal == null) {
            return out;
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT capability, asset_id FROM asset_grant "
                                + " WHERE principal_id = ? AND revoked_at IS NULL")) {
            statement.setObject(1, principal.principalId());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    out.get(Capability.valueOf(results.getString(1)))
                            .add(results.getObject(2, UUID.class));
                }
            }
        }
        return out;
    }

    /** One grant, for the panel that lists them. */
    public record Grant(UUID id, UUID assetId, String assetName, UUID principalId,
            String principalName, String username, String capability, String grantedAt,
            String grantedByName) {
    }

    /** The live grants on one asset, owners first. */
    public List<Grant> grantsOn(Principal principal, UUID assetId) throws SQLException {
        return read(principal,
                "SELECT g.id, g.asset_id, a.display_name, g.principal_id, "
                        + "       coalesce(p.display_name, ''), coalesce(p.username, ''), "
                        + "       g.capability, to_char(g.granted_at, 'YYYY-MM-DD HH24:MI'), "
                        + "       coalesce(b.display_name, '') "
                        + "  FROM asset_grant g "
                        + "  JOIN asset a ON a.id = g.asset_id "
                        + "  LEFT JOIN principal p ON p.id = g.principal_id "
                        + "  LEFT JOIN principal b ON b.id = g.granted_by "
                        + " WHERE g.asset_id = ? AND g.revoked_at IS NULL "
                        + " ORDER BY g.capability, p.username",
                statement -> statement.setObject(1, assetId));
    }

    /** The live grants one principal holds, for their profile and for an offboarding check. */
    public List<Grant> grantsOf(Principal principal, UUID principalId) throws SQLException {
        return read(principal,
                "SELECT g.id, g.asset_id, a.display_name, g.principal_id, "
                        + "       coalesce(p.display_name, ''), coalesce(p.username, ''), "
                        + "       g.capability, to_char(g.granted_at, 'YYYY-MM-DD HH24:MI'), "
                        + "       coalesce(b.display_name, '') "
                        + "  FROM asset_grant g "
                        + "  JOIN asset a ON a.id = g.asset_id "
                        + "  LEFT JOIN principal p ON p.id = g.principal_id "
                        + "  LEFT JOIN principal b ON b.id = g.granted_by "
                        + " WHERE g.principal_id = ? AND g.revoked_at IS NULL "
                        + " ORDER BY g.capability, a.display_name",
                statement -> statement.setObject(1, principalId));
    }

    /**
     * Every live grant on an asset the caller can reach, for the estate-wide view.
     *
     * <p>Scoped through the asset's owning node, the same predicate the inventory uses. Without it
     * this would be the one place an administrator scoped to one division could enumerate who owns
     * what in another — a grant list is an organizational map, and DOC-07 treats the shape of the
     * organization above you as something you do not get to see.
     */
    public List<Grant> allGrants(Principal principal) throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        return read(principal,
                "SELECT g.id, g.asset_id, a.display_name, g.principal_id, "
                        + "       coalesce(p.display_name, ''), coalesce(p.username, ''), "
                        + "       g.capability, to_char(g.granted_at, 'YYYY-MM-DD HH24:MI'), "
                        + "       coalesce(b.display_name, '') "
                        + "  FROM asset_grant g "
                        + "  JOIN asset a ON a.id = g.asset_id "
                        + "  LEFT JOIN principal p ON p.id = g.principal_id "
                        + "  LEFT JOIN principal b ON b.id = g.granted_by "
                        + " WHERE g.revoked_at IS NULL "
                        + "   AND a.owning_node_id IN "
                        + "       (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)) "
                        + " ORDER BY a.display_name, g.capability, p.username",
                statement -> statement.setArray(1, statement.getConnection()
                        .createArrayOf("uuid", scope.toArray(new UUID[0]))));
    }

    /**
     * Grants a capability. Idempotent: granting what somebody already holds changes nothing.
     *
     * @return whether a new grant was written
     */
    public boolean grant(Principal principal, UUID assetId, UUID principalId, Capability capability)
            throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO asset_grant (tenant_id, asset_id, principal_id, capability, "
                                + "granted_by) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            statement.setObject(1, principal.tenantId());
            statement.setObject(2, assetId);
            statement.setObject(3, principalId);
            statement.setString(4, capability.name());
            statement.setObject(5, principal.principalId());
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                // An object-level grant is access that no role decides and no org node bounds, which
                // is exactly why DOC-07 §15 gives it its own access review: it is the access a role
                // audit does not see.
                audit.event(connection, principal,
                        aspm.kernel.audit.contract.AuditEventType.OBJECT_GRANT_ISSUED,
                        assetId, aspm.app.audit.AuditScopes.ofAsset(connection, assetId),
                        java.util.Map.of("asset_id", assetId.toString(),
                                "principal_id", principalId.toString(),
                                "capability", capability.name()));
            }
            connection.commit();
            return applied;
        }
    }

    /** Revokes one grant. Tombstoned, never deleted — see the header of V030. */
    public boolean revoke(Principal principal, UUID grantId, String reason) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE asset_grant SET revoked_at = now(), revoked_reason = ? "
                                + " WHERE id = ? AND revoked_at IS NULL")) {
            statement.setString(1, reason);
            statement.setObject(2, grantId);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.event(connection, principal,
                        aspm.kernel.audit.contract.AuditEventType.OBJECT_GRANT_REVOKED,
                        grantId, null, java.util.Map.of("grant_id", grantId.toString(),
                                "reason", reason == null ? "" : reason));
            }
            connection.commit();
            return applied;
        }
    }

    // ----------------------------------------------------------------------------------------------

    /** One person on the delivery side of a request. */
    public record Participant(UUID id, UUID principalId, String displayName, String username,
            String addedAt, String addedByName) {
    }

    public List<Participant> participants(Principal principal, UUID requestId) throws SQLException {
        List<Participant> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT pa.id, pa.principal_id, coalesce(p.display_name, ''), "
                                + "       coalesce(p.username, ''), "
                                + "       to_char(pa.added_at, 'YYYY-MM-DD HH24:MI'), "
                                + "       coalesce(b.display_name, '') "
                                + "  FROM assessment_request_participant pa "
                                + "  LEFT JOIN principal p ON p.id = pa.principal_id "
                                + "  LEFT JOIN principal b ON b.id = pa.added_by "
                                + " WHERE pa.request_id = ? AND pa.removed_at IS NULL "
                                + " ORDER BY p.username")) {
            statement.setObject(1, requestId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(new Participant(results.getObject(1, UUID.class),
                            results.getObject(2, UUID.class), results.getString(3),
                            results.getString(4), results.getString(5), results.getString(6)));
                }
            }
        }
        return List.copyOf(rows);
    }

    public boolean addParticipant(Principal principal, UUID requestId, UUID principalId)
            throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO assessment_request_participant (tenant_id, request_id, "
                                + "principal_id, added_by) VALUES (?, ?, ?, ?) "
                                + "ON CONFLICT DO NOTHING")) {
            statement.setObject(1, principal.tenantId());
            statement.setObject(2, requestId);
            statement.setObject(3, principalId);
            statement.setObject(4, principal.principalId());
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                // A participant reaches one request and everything in it — findings, evidence, the
                // credentials held for the engagement. It is an object grant in everything but name,
                // so it is recorded as one rather than under a name only this table uses.
                audit.event(connection, principal,
                        aspm.kernel.audit.contract.AuditEventType.OBJECT_GRANT_ISSUED,
                        requestId, aspm.app.audit.AuditScopes.ofRequest(connection, requestId),
                        java.util.Map.of("request_id", requestId.toString(),
                                "principal_id", principalId.toString(),
                                "as", "request participant"));
            }
            connection.commit();
            return applied;
        }
    }

    public boolean removeParticipant(Principal principal, UUID participantId, String reason)
            throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE assessment_request_participant SET removed_at = now(), "
                                + "removed_reason = ? WHERE id = ? AND removed_at IS NULL")) {
            statement.setString(1, reason);
            statement.setObject(2, participantId);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.event(connection, principal,
                        aspm.kernel.audit.contract.AuditEventType.OBJECT_GRANT_REVOKED,
                        participantId, null,
                        java.util.Map.of("participant_id", participantId.toString(),
                                "reason", reason == null ? "" : reason,
                                "as", "request participant"));
            }
            connection.commit();
            return applied;
        }
    }

    // ----------------------------------------------------------------------------------------------

    private boolean holds(Principal principal, UUID assetId, Capability capability)
            throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM asset_grant WHERE asset_id = ? AND principal_id = ? "
                                + "   AND capability = ? AND revoked_at IS NULL")) {
            statement.setObject(1, assetId);
            statement.setObject(2, principal.principalId());
            statement.setString(3, capability.name());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private List<Grant> read(Principal principal, String sql, Binder binder) throws SQLException {
        List<Grant> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(new Grant(results.getObject(1, UUID.class),
                            results.getObject(2, UUID.class), results.getString(3),
                            results.getObject(4, UUID.class), results.getString(5),
                            results.getString(6), results.getString(7), results.getString(8),
                            results.getString(9)));
                }
            }
        }
        return List.copyOf(rows);
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
