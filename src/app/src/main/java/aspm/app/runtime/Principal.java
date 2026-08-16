package aspm.app.runtime;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller. {@code SEC-TEN-004}, ADR-004.
 *
 * <p>{@code SEC-TEN-004}: "A tenant context MUST be established at request entry from an authenticated
 * principal or a scope-pinned service credential, and <b>MUST NOT be derivable from any request parameter,
 * header, path segment, or body field</b>."
 *
 * <p>That sentence is the reason this type exists and the reason it is constructed only by a
 * {@link PrincipalResolver}. The tenant is a field of the principal, so a handler that wants to know the
 * tenant has to have an authenticated caller; there is no method anywhere that derives one from a request.
 * Tenant isolation is the platform's highest-severity property and a tenant taken from a header is the
 * shortest path to losing it.
 *
 * @param permissions the named permissions this principal holds. The catalogue is product-fixed
 *     ({@code SEC-AUZ-001}); which ones a principal holds is tenant-configured data (ADR-027)
 * @param scopeNodeIds the organization nodes this principal is scoped to. Derived, never asserted by the
 *     client (product principle 4)
 * @param stepUpAuthenticated whether the second factor has been re-presented recently on THIS session.
 *     Class C and class E operations require it (ADR-036). Until V016 this was a literal {@code false} at
 *     every construction site, which made the dispatcher's step-up gate unsatisfiable rather than
 *     unenforced — see the header of V016
 * @param credentialChangeRequired whether this caller must set a new password before doing anything else.
 *     Carried on the principal rather than checked per page because the dispatcher is the only chokepoint
 *     every route passes through, and an enforcement point that each page opts into is one the next page
 *     added will omit
 */
public record Principal(UUID tenantId, UUID principalId, Set<String> permissions, Set<UUID> scopeNodeIds,
        boolean stepUpAuthenticated, boolean serviceCredential, boolean credentialChangeRequired,
        UUID credentialId) {

    /**
     * A human caller. {@code credentialId} is null because a person is not a key.
     *
     * <p>The four-argument form kept every existing construction site working when the credential
     * identity was added; there are six of them, and a default would have let a service caller be
     * built without one by accident, which is how per-credential attribution would quietly stop.
     */
    public Principal(UUID tenantId, UUID principalId, Set<String> permissions, Set<UUID> scopeNodeIds,
            boolean stepUpAuthenticated, boolean serviceCredential, boolean credentialChangeRequired) {
        this(tenantId, principalId, permissions, scopeNodeIds, stepUpAuthenticated, serviceCredential,
                credentialChangeRequired, null);
    }

    public Principal {
        Objects.requireNonNull(tenantId, "a tenant is required — a principal without one cannot be scoped");
        Objects.requireNonNull(principalId, "a principal identifier is required");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions are required, possibly empty"));
        scopeNodeIds = Set.copyOf(Objects.requireNonNull(scopeNodeIds, "scope is required, possibly empty"));
    }

    /**
     * Which key this request arrived on, or null for a human.
     *
     * <p>Needed because ten service credentials in a deployment may act as ONE principal, so
     * {@code principalId} cannot attribute a submission to the pipeline that made it. Submission health
     * is required per credential ({@code PRD-SBM-024}), and without this component that requirement is
     * not implementable — which is why it was not implemented.
     */
    public UUID credential() {
        return credentialId;
    }

    public boolean holds(String permission) {
        return permissions.contains(Objects.requireNonNull(permission, "a permission is required"));
    }
}
