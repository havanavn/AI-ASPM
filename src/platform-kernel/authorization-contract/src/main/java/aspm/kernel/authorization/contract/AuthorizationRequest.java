package aspm.kernel.authorization.contract;

import aspm.sharedkernel.PrincipalId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The decision inputs of DOC-07 section 8.2.
 *
 * <p><b>Deviation from the DOC-07 section 8.2 sketch, recorded deliberately.</b> The sketch lists
 * {@code tenant_context ⟨TenantContext⟩} as an input, annotated "established, never from the
 * request". This type carries <b>no tenant field at all</b>: the evaluator reads the established
 * context from the tenant-context kernel itself. That satisfies the annotation more strongly than a
 * parameter can — a field could be populated from a request value, which {@code SEC-TEN-004}
 * prohibits, whereas an absent field cannot be. It also keeps this contract free of a dependency on
 * {@code tenant-context}, which is what avoids a kernel cycle under {@code CON-PLT-016}: the gate
 * lives in {@code tenant-context} per DOC-02 section 6.2, so a dependency in this direction would
 * close a loop. No requirement changes; DOC-07's requirements here are {@code SEC-AUZ-013} through
 * {@code SEC-AUZ-015}, all satisfied.
 *
 * @param principalId the principal whose authority is evaluated
 * @param permission the product-fixed permission being evaluated
 * @param objectRef the object named by the operation; absent for collection operations
 * @param evaluationTime current or historical
 * @param fieldSet fields for field-level evaluation per DOC-07 section 10; empty for whole-object
 */
public record AuthorizationRequest(
        PrincipalId principalId,
        PermissionId permission,
        ObjectReference objectRef,
        EvaluationTime evaluationTime,
        List<String> fieldSet) {

    public AuthorizationRequest {
        Objects.requireNonNull(principalId, "principalId is required");
        Objects.requireNonNull(permission, "permission is required");
        Objects.requireNonNull(evaluationTime, "evaluationTime is required");
        fieldSet = List.copyOf(Objects.requireNonNull(fieldSet, "fieldSet is required; use List.of()"));
    }

    /** A collection operation: no single object, governed by {@code SEC-AUZ-016}. */
    public static AuthorizationRequest forCollection(PrincipalId principal, PermissionId permission) {
        return new AuthorizationRequest(principal, permission, null, EvaluationTime.current(), List.of());
    }

    /** An operation naming a single object, re-validated per {@code SEC-AUZ-017}. */
    public static AuthorizationRequest forObject(
            PrincipalId principal, PermissionId permission, ObjectReference objectRef) {
        return new AuthorizationRequest(principal, permission,
                Objects.requireNonNull(objectRef, "objectRef is required for an object operation"),
                EvaluationTime.current(), List.of());
    }

    public Optional<ObjectReference> object() {
        return Optional.ofNullable(objectRef);
    }

    public boolean isCollectionOperation() {
        return objectRef == null;
    }
}
