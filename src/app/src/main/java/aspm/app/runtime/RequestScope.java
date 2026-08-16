package aspm.app.runtime;

import aspm.kernel.tenantcontext.contract.TenantContext;
import aspm.kernel.tenantcontext.contract.EstablishedFrom;
import aspm.module.organizationscope.contract.ScopeResolutionQuery;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.TenantId;
import aspm.sharedkernel.PrincipalId;
import aspm.sharedkernel.ScopeDescriptor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Scope resolution against the real closure table. {@code SEC-AUZ-010}, DOC-07 §7.2.
 *
 * <p>Product principle 4: <b>scope is derived, never asserted by the client.</b> The derivation is the
 * expansion below — a granted node reaches its whole subtree, and the subtree comes from {@code org_closure}
 * rather than from anything the caller sent.
 *
 * <p><b>Where the grants come from is the honest boundary here.</b> Which nodes a principal is granted is
 * owned by the identity and access context (DOC-03 §17), which no prompt in the implementation sequence
 * assigns and which does not exist. Under development authentication the grant arrives in a header, so the
 * asserted part is the grant and the derived part is the expansion. Replacing {@link DevPrincipalResolver}
 * with an ADR-004 identity provider closes it; nothing else here changes.
 *
 * <p>{@code SEC-AUZ-014} requires denial on unavailable resolution rather than an allow over an empty set,
 * "which reads as 'no data' to the caller". A SQL failure here therefore reports unavailable rather than
 * returning nothing.
 */
public final class RequestScope implements ScopeResolutionQuery {

    private final DataSource dataSource;
    private final Principal principal;

    public RequestScope(DataSource dataSource, Principal principal) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
        this.principal = Objects.requireNonNull(principal, "a principal is required");
    }

    /**
     * The tenant context for a principal, established from the principal and from nothing else.
     *
     * <p>Static, and it takes the principal rather than reading a field, so the dispatcher can establish
     * the context without constructing a scope resolver it does not need. An instance method here would
     * have meant passing a null data source at request entry to get at a value that never touches one.
     *
     * <p>⚠ <b>Working assumption (OQ-011):</b> the residency designation is fixed to the Vietnam-first
     * target. {@code OPS-DEP-004} makes each designation a separate deployment with no data path between
     * them, so this becomes deployment configuration rather than a constant; it is a constant here
     * because there is one deployment.
     */
    public static TenantContext contextFor(Principal principal) {
        Objects.requireNonNull(principal, "a principal is required");
        return TenantContext.of(new TenantId(principal.tenantId()), "vn",
                EstablishedFrom.AUTHENTICATED_PRINCIPAL, Instant.now());
    }

    @Override
    public Resolution resolveCurrent(PrincipalId principal, String permissionCode) {
        Objects.requireNonNull(principal, "a principal is required");
        Objects.requireNonNull(permissionCode, "a permission code is required");

        if (this.principal.scopeNodeIds().isEmpty()) {
            // Not "unavailable": this principal genuinely reaches nothing. The two are separate denial
            // reasons in SEC-AUZ-014 and conflating them makes a closure outage indistinguishable from a
            // correctly restrictive configuration.
            return new Resolution(List.of(), 0L, Optional.empty());
        }

        List<OrgNodeId> permitted = new ArrayList<>();
        try (Connection c =
                aspm.app.persistence.TenantConnections.open(dataSource, this.principal)) {
            // The expansion. A grant on a node reaches its descendants, which is what makes scope a
            // subtree rather than a node list — SEC-AUZ-010's union, not a cross product.
            try (PreparedStatement statement = c.prepareStatement(
                    "SELECT DISTINCT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)")) {
                statement.setArray(1, c.createArrayOf("uuid",
                        this.principal.scopeNodeIds().toArray(new UUID[0])));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        permitted.add(new OrgNodeId(rows.getObject(1, UUID.class)));
                    }
                }
            }
            c.rollback();
        } catch (SQLException e) {
            // Unavailable, not empty. SEC-AUZ-014 requires denial on unavailable resolution; returning an
            // empty permitted set here would be an allow over nothing, which reads as "no data".
            return new Resolution(List.of(), 0L, Optional.of("scope resolution query failed"));
        }
        return new Resolution(List.copyOf(permitted), 1L, Optional.empty());
    }

    /**
     * Historical scope. {@code SEC-AUZ-028}, {@code SEC-AUZ-029}.
     *
     * <p>Not implemented, and it returns the DENYING verdict rather than a permissive default. Historical
     * evaluation asks whether a principal's accountability at a past instant covered a descriptor, and
     * answering it needs the recorded descriptor history that the organization-scope module owns and that
     * no request path here reaches yet.
     *
     * <p>A permissive stub would be worse than the absence: {@code SEC-AUZ-029} exists because historical
     * evaluation "grants nothing for objects created after a move", and a stub that said yes would grant
     * exactly that, silently, on the one path nobody exercises during development.
     */
    @Override
    public HistoricalVerdict wasAuthorized(PrincipalId principal, String permissionCode,
            ScopeDescriptor descriptor, Instant at) {
        return new HistoricalVerdict(false, "historical scope evaluation is not implemented; the recorded descriptor history is owned by organization-scope and no request path reaches it yet (SEC-AUZ-028)");
    }
}
