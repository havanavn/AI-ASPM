package aspm.module.assessment.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One test account supplied with an assessment request. DOC-03 section 9.2.
 *
 * <p>The credential is a {@link SecretRef} and there is no field, constructor parameter, or accessor that could
 * hold or return a value ({@code INV-ASM-03}). {@code SEC-PTR-004} additionally forbids the submitting requester
 * from reading it back after submission — that is an authorization rule enforced on the reveal path, and the
 * reason this type has no {@code credentialValue()} for such a check to guard.
 *
 * @param expectedPermissions what this role should be able to do. The assessment tests whether it can do more,
 *     which is the whole object-level authorization exercise
 */
public record RoleAccount(String roleName, String roleDescription, String username, SecretRef credentialRef,
        boolean mfaEnrolled, Optional<SecretRef> mfaBypassRef, Optional<String> tenantOrOrgContext,
        List<String> expectedPermissions, Status status) {

    /** Account state. {@code EXPIRED}, {@code LOCKED} and {@code INVALID} are the three ways intake goes stale. */
    public enum Status {
        PROVIDED,
        VERIFIED,
        EXPIRED,
        LOCKED,
        INVALID;

        /**
         * Whether the account can be used for testing.
         *
         * <p>{@code PROVIDED} counts: requiring verification before acceptance would block every request behind
         * a manual step, and DOC-01 section 10.4.6 describes this population as the largest and least trained.
         * Verification is what the engagement does first, not what intake demands.
         */
        public boolean usable() {
            return this == PROVIDED || this == VERIFIED;
        }
    }

    public RoleAccount {
        Objects.requireNonNull(roleName, "a role name is required");
        Objects.requireNonNull(roleDescription, "a role description is required");
        Objects.requireNonNull(username, "a username is required");
        Objects.requireNonNull(credentialRef,
                "a credential reference is required (INV-ASM-03). There is no field here that could hold a "
                        + "value instead.");
        Objects.requireNonNull(mfaBypassRef, "mfaBypassRef is required, empty where none");
        Objects.requireNonNull(tenantOrOrgContext, "tenantOrOrgContext is required, empty for single-tenant");
        Objects.requireNonNull(status, "a status is required");
        expectedPermissions = List.copyOf(
                Objects.requireNonNull(expectedPermissions, "expectedPermissions are required"));

        if (roleName.isBlank()) {
            throw new IllegalArgumentException(
                    "a blank role name cannot be counted toward INV-ASM-02's two-accounts-per-role rule, so "
                            + "two blank-named accounts would satisfy it while testing nothing");
        }
        if (username.isBlank()) {
            throw new IllegalArgumentException("a blank username identifies no account");
        }
        // An MFA-enrolled account with no bypass reference cannot be driven by an automated test run, and the
        // engagement discovers that on day one rather than at intake.
        if (mfaEnrolled && mfaBypassRef.isEmpty()) {
            throw new IllegalArgumentException(
                    "account '" + username + "' is MFA-enrolled with no bypass reference. The engagement "
                            + "cannot authenticate as it, and finding that out on day one costs a day of a "
                            + "booked engagement (PRD-PTR-009 readiness).");
        }
    }

    /** Role names are compared case-insensitively, so 'Admin' and 'admin' are not two roles. */
    public String normalizedRoleName() {
        return roleName.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
