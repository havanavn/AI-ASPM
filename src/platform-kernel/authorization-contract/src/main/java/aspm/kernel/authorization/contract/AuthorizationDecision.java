package aspm.kernel.authorization.contract;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The decision of DOC-07 section 8.2.
 *
 * <p>A sealed hierarchy of exactly two outcomes rather than a record with an {@code outcome} field.
 * The difference matters: with a field, {@code decision.permittedFields()} is callable on a denial and
 * returns something, and {@code if (decision.outcome() != DENY)} is one inverted comparison away from
 * failing open. With a sealed pair, a denial has no fields to read and the compiler requires both
 * cases to be handled.
 *
 * <p>{@code SEC-AUZ-014} requires denial by default: on no matching grant, on evaluation error, on
 * unavailable scope resolution, and on any unhandled condition. {@link #denyOn} exists so that an
 * evaluator's catch block has an obvious correct thing to return.
 */
public sealed interface AuthorizationDecision {

    /** A reference correlating this decision with its audit event ({@code SEC-AUZ-015}). */
    UUID reference();

    /** The permission that was evaluated. */
    PermissionId permission();

    /** Allowed. Carries the applied scope and, for field-level evaluation, the permitted fields. */
    record Allow(
            UUID reference,
            PermissionId permission,
            ScopeGrant appliedScope,
            List<String> permittedFields,
            boolean historical)
            implements AuthorizationDecision {

        public Allow {
            Objects.requireNonNull(reference, "reference is required");
            Objects.requireNonNull(permission, "permission is required");
            Objects.requireNonNull(appliedScope, "appliedScope is required for audit (SEC-AUZ-015)");
            permittedFields = List.copyOf(
                    Objects.requireNonNull(permittedFields, "permittedFields is required; use List.of()"));
        }
    }

    /**
     * Denied.
     *
     * <p>The {@link #reason()} is for the audit trail only. {@code SEC-AUZ-020} prohibits
     * differentiating non-existence from non-authorization in what reaches the client, and
     * {@code ADR-047} makes a restricted field absent rather than masked for the same family of
     * reasons: an informative negative response is a disclosure.
     */
    record Deny(UUID reference, PermissionId permission, DenialReason reason)
            implements AuthorizationDecision {

        public Deny {
            Objects.requireNonNull(reference, "reference is required");
            Objects.requireNonNull(permission, "permission is required");
            Objects.requireNonNull(reason, "reason is required for audit; never disclosed to a client");
        }
    }

    /**
     * The deny-by-default construction for an evaluator's failure path.
     *
     * <p>Named {@code denyOn} rather than {@code error} so that the call site reads as a decision
     * rather than as an exception translation — the point of {@code SEC-AUZ-014} is that a failure to
     * evaluate <em>is</em> a denial, not an absence of one.
     */
    static Deny denyOn(PermissionId permission, DenialReason reason) {
        return new Deny(UUID.randomUUID(), permission, reason);
    }

    default boolean isAllowed() {
        return this instanceof Allow;
    }

    default Optional<DenialReason> denialReason() {
        return this instanceof Deny d ? Optional.of(d.reason()) : Optional.empty();
    }
}
