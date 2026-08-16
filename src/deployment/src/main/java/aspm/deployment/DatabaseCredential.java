package aspm.deployment;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The four database credentials of DOC-04 §7.2, as DOC-15 §5.1 deploys them. {@code OPS-DEP-009}.
 *
 * <p>"The three bypass credentials MUST NOT be present in any runtime environment reachable by application
 * code, and their use MUST emit an audit event."
 *
 * <p>The rationale is the reason this is a type and not a manifest comment: "Credential separation is what makes
 * bypass unreachability <b>structural rather than procedural</b> — an application that cannot obtain the
 * credential cannot use the bypass regardless of what its code attempts."
 *
 * <p>Row-level security with {@code FORCE} is the control; these four roles are what decide who it applies to.
 * Three of them are outside it. A deployment that injects one of those three into the application tier has
 * disabled tenant isolation for that tier — <b>and it has done so in a configuration file, not in code</b>, which
 * is where nobody looks for an isolation defect. So the injection scope is part of the credential's identity here
 * and cannot be widened after construction.
 */
public final class DatabaseCredential {

    /** Where a credential can be injected. The bypass three may name none of the runtime units. */
    public enum InjectionScope {

        /** Application and worker tiers. Row-level security enforced. */
        RUNTIME,

        /** The migration pipeline. Not a runtime unit; runs and exits. */
        MIGRATION_PIPELINE,

        /** The audit chain verification job. */
        VERIFICATION_JOB,

        /** The offboarding procedure, dual-control gated ({@code OPS-DEP-022}). */
        OFFBOARDING_PROCEDURE
    }

    /** The four. Names match DOC-15 §5.1 exactly, because a renamed role is an ungranted role. */
    public static final DatabaseCredential APP_RUNTIME = new DatabaseCredential(
            "app_runtime", false, false, EnumSet.of(InjectionScope.RUNTIME));

    public static final DatabaseCredential MIGRATION_RUNNER = new DatabaseCredential(
            "migration_runner", true, false, EnumSet.of(InjectionScope.MIGRATION_PIPELINE));

    public static final DatabaseCredential INTEGRITY_VERIFIER = new DatabaseCredential(
            "integrity_verifier", true, true, EnumSet.of(InjectionScope.VERIFICATION_JOB));

    public static final DatabaseCredential OFFBOARDING_EXECUTOR = new DatabaseCredential(
            "offboarding_executor", true, false, EnumSet.of(InjectionScope.OFFBOARDING_PROCEDURE));

    private static final List<DatabaseCredential> ALL = List.of(
            APP_RUNTIME, MIGRATION_RUNNER, INTEGRITY_VERIFIER, OFFBOARDING_EXECUTOR);

    private final String roleName;
    private final boolean bypassesRowLevelSecurity;
    private final boolean readOnly;
    private final Set<InjectionScope> injectableInto;

    private DatabaseCredential(String roleName, boolean bypassesRowLevelSecurity, boolean readOnly,
            Set<InjectionScope> injectableInto) {
        this.roleName = Objects.requireNonNull(roleName, "a role name is required");
        this.bypassesRowLevelSecurity = bypassesRowLevelSecurity;
        this.readOnly = readOnly;
        this.injectableInto = Set.copyOf(Objects.requireNonNull(injectableInto, "an injection scope is required"));

        if (bypassesRowLevelSecurity && this.injectableInto.contains(InjectionScope.RUNTIME)) {
            throw new IllegalArgumentException(
                    roleName + " bypasses row-level security and is injectable into a runtime environment. "
                            + "OPS-DEP-009 makes bypass unreachability structural rather than procedural: an "
                            + "application that cannot obtain the credential cannot use the bypass regardless of "
                            + "what its code attempts. Injecting it here removes the only thing that was "
                            + "structural about it, in a configuration file rather than in code.");
        }
        if (this.injectableInto.isEmpty()) {
            throw new IllegalArgumentException(
                    roleName + " is injectable nowhere, so nothing can use it. An unreachable credential that "
                            + "still exists in the database is an account nobody rotates.");
        }
    }

    public static List<DatabaseCredential> all() {
        return ALL;
    }

    /** The three of DOC-15 §5.1 that are outside row-level security. */
    public static List<DatabaseCredential> bypassCredentials() {
        return ALL.stream().filter(DatabaseCredential::bypassesRowLevelSecurity).toList();
    }

    public String roleName() {
        return roleName;
    }

    public boolean bypassesRowLevelSecurity() {
        return bypassesRowLevelSecurity;
    }

    public boolean readOnly() {
        return readOnly;
    }

    public Set<InjectionScope> injectableInto() {
        return injectableInto;
    }

    /**
     * Whether use of this credential emits an audit event. The second half of {@code OPS-DEP-009}.
     *
     * <p>Always true for a bypass credential and it is not configurable, because the case where somebody would
     * want it off — a long migration producing a large volume of audit events — is the case where the record
     * matters most. {@code OPS-DEP-031} requires a cross-tenant assertion after every migration precisely
     * because migrations "run with row-level enforcement bypassed and are the highest-risk operation in the
     * platform".
     */
    public boolean useIsAudited() {
        return bypassesRowLevelSecurity;
    }

    /**
     * The credential a runtime unit is given.
     *
     * <p>There is one, it is the same one for every unit, and this method takes no argument that could select
     * another. A unit needing a bypass is a design error rather than a configuration option.
     */
    public static DatabaseCredential forRuntimeUnits() {
        return APP_RUNTIME;
    }

    @Override
    public String toString() {
        return roleName;
    }
}
