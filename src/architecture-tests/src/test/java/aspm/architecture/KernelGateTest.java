package aspm.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S8 and S9 of DOC-16 section 3 — the two structural assertions that carry the kernel's gates.
 *
 * <ul>
 *   <li><b>S8</b> — no data access outside the tenant-context gate ({@code CON-PLT-036})
 *   <li><b>S9</b> — no query execution without an authorization decision input ({@code CON-PLT-037})
 * </ul>
 *
 * <p>These are the two assertions the prompt 3 review point exercises directly: "Try to write a query
 * without a tenant context and without an authorization decision. If either succeeds, the kernel is
 * not done."
 */
class KernelGateTest {

    /**
     * The only package permitted to touch a JDBC type. DOC-02 section 6.2 assigns the data access
     * gate to {@code tenant-context}, so its infrastructure layer is where a connection legitimately
     * exists.
     */
    private static final String GATE_INFRASTRUCTURE = "aspm.kernel.tenantcontext.infrastructure";

    /** The package permitted to mint the gate key. */
    private static final String AUTHORIZATION_KERNEL = "aspm.kernel.authorization";

    /** The package that declares the key, and therefore may call its package-private constructor. */
    private static final String KEY_DECLARING_PACKAGE = "aspm.kernel.tenantcontext.contract";

    private static JavaClasses platform;

    @BeforeAll
    static void importPlatform() {
        platform = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("aspm..");
    }

    // -------------------------------------------------------------------- S8

    @Test
    @DisplayName("S8 / CON-PLT-036: no class outside the tenant-context gate depends on a JDBC type")
    void dataAccessOnlyThroughTheGate() {
        List<String> violations = new ArrayList<>();
        for (JavaClass type : platform) {
            if (type.getPackageName().startsWith(GATE_INFRASTRUCTURE)) {
                continue;
            }
            for (JavaClass target : type.getDirectDependenciesFromSelf().stream()
                    .map(dependency -> dependency.getTargetClass())
                    .toList()) {
                String pkg = target.getPackageName();
                if (pkg.startsWith("java.sql") || pkg.startsWith("javax.sql")) {
                    violations.add(type.getFullName() + "  ->  " + target.getFullName());
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "CON-PLT-036: data access must be reachable only through a gate that requires an "
                        + "established tenant context, and there must not be an alternative access path "
                        + "in application code. DOC-02 section 13.1 states why: an alternative path "
                        + "exists for convenience and then becomes the normal path.\n  "
                        + String.join("\n  ", violations));
    }

    // -------------------------------------------------------------------- S9

    @Test
    @DisplayName("S9 / CON-PLT-037: only the authorization kernel may extend AuthorizationGateway")
    void onlyAuthorizationKernelMintsTheKey() {
        List<String> violations = new ArrayList<>();
        for (JavaClass type : platform) {
            type.getAllRawSuperclasses().stream()
                    .filter(s -> s.getName().endsWith(".AuthorizationGateway"))
                    .findFirst()
                    .ifPresent(_ -> {
                        if (!type.getPackageName().startsWith(AUTHORIZATION_KERNEL)) {
                            violations.add(type.getFullName());
                        }
                    });
        }
        assertTrue(violations.isEmpty(),
                "CON-PLT-037 and SEC-AUZ-013: AuthorizationGateway is the single minting point for the "
                        + "key that opens the data access gate, and extending it outside the authorization "
                        + "kernel is application code implementing its own check — which SEC-AUZ-013 "
                        + "prohibits. This assertion is the final link in the chain and the one enforced "
                        + "on bytecode rather than by the compiler; see AuthorizedQuery for why that "
                        + "trade was taken.\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("S9 / CON-PLT-037: no class outside its declaring package constructs an AuthorizedQuery")
    void theKeyIsNotConstructedElsewhere() {
        List<String> violations = new ArrayList<>();
        for (JavaClass type : platform) {
            if (type.getPackageName().equals(KEY_DECLARING_PACKAGE)) {
                continue;
            }
            for (var call : type.getConstructorCallsFromSelf()) {
                if (call.getTargetOwner().getSimpleName().equals("AuthorizedQuery")) {
                    violations.add(type.getFullName() + " constructs AuthorizedQuery directly");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "The constructor is package-private, so this should be unreachable — the assertion "
                        + "exists because a future refactor widening it would silently dissolve "
                        + "CON-PLT-037, and no other test would notice.\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("The kernel gate types exist and this test is not vacuous")
    void gateTypesArePresent() {
        List<String> required = List.of(
                "aspm.kernel.tenantcontext.contract.TenantScopedAccess",
                "aspm.kernel.tenantcontext.contract.AuthorizedQuery",
                "aspm.kernel.tenantcontext.contract.AuthorizationGateway",
                "aspm.kernel.authorization.contract.AuthorizationGate",
                "aspm.kernel.authorization.application.ScopeResolvingAuthorizationGate");
        for (String name : required) {
            assertTrue(platform.stream().anyMatch(c -> c.getFullName().equals(name)),
                    name + " is absent, so the S8 and S9 assertions above verify nothing");
        }
    }
}
