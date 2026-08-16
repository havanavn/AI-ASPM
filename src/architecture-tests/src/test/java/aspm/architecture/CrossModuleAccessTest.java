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
 * CON-PLT-013 and CON-PLT-015 — cross-module access is restricted to published contract surfaces.
 *
 * <p><b>Why this is hand-written rather than an ArchUnit package rule.</b> The first version used
 * {@code noClasses().that().resideOutsideOfPackage("aspm.module.(*).domain..")}, which reads
 * correctly and is wrong: the {@code (*)} is a plain wildcard, so the {@code that()} clause excludes
 * every module's domain package, and the single most likely real violation — one module's domain
 * type reaching another module's domain type — is invisible to the rule. The deliberate-violation
 * run of prompt 2 caught it: the rule passed while the violation was present.
 *
 * <p>The correct predicate compares the source module against the target module, which requires
 * looking at a pair rather than at a package pattern. That is what this test does.
 *
 * <p><b>Known limitation, stated rather than left implicit.</b> Bytecode analysis cannot observe a
 * reference to an inlined compile-time constant: {@code javac} folds a {@code static final String}
 * into the call site and no dependency survives. A violation that reads only such a constant from
 * another module is caught by the compile classpath — the type must still be resolvable, which the
 * Gradle subproject partition prevents — and not by this test. The two mechanisms are complementary
 * for this reason, not redundant.
 */
class CrossModuleAccessTest {

    private static JavaClasses platform;

    @BeforeAll
    static void importPlatform() {
        platform = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("aspm..");
    }

    /** Returns {@code module.segment.layer} coordinates, or null where the package is not a module layer. */
    private record Coordinate(String module, String layer) {

        static Coordinate of(String packageName) {
            for (String root : List.of("aspm.module.", "aspm.kernel.")) {
                if (!packageName.startsWith(root)) {
                    continue;
                }
                // Deliberately not String.split: its trailing-empty-string behaviour is surprising
                // and a boundary rule must not depend on a surprising parse.
                String rest = packageName.substring(root.length());
                int firstDot = rest.indexOf('.');
                if (firstDot < 0) {
                    return new Coordinate(root + rest, "");
                }
                String module = rest.substring(0, firstDot);
                String remainder = rest.substring(firstDot + 1);
                int secondDot = remainder.indexOf('.');
                String layer = secondDot < 0 ? remainder : remainder.substring(0, secondDot);
                return new Coordinate(root + module, layer);
            }
            return null;
        }

        boolean isInternalLayer() {
            return layer.equals("domain") || layer.equals("infrastructure") || layer.equals("application");
        }
    }

    @Test
    @DisplayName("CON-PLT-013: no module reaches another module's domain, application or infrastructure layer")
    void crossModuleAccessIsRestrictedToContracts() {
        List<String> violations = new ArrayList<>();

        for (JavaClass type : platform) {
            Coordinate source = Coordinate.of(type.getPackageName());
            if (source == null) {
                continue;
            }
            for (JavaClass target : type.getDirectDependenciesFromSelf().stream()
                    .map(dependency -> dependency.getTargetClass())
                    .toList()) {
                Coordinate destination = Coordinate.of(target.getPackageName());
                if (destination == null || destination.module().equals(source.module())) {
                    continue;
                }
                if (destination.isInternalLayer()) {
                    violations.add(type.getFullName() + "  ->  " + target.getFullName()
                            + "   [" + source.module() + " reaching " + destination.module()
                            + " layer '" + destination.layer() + "']");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "CON-PLT-013: cross-module access is restricted to published contract surfaces. "
                        + "A domain, application or infrastructure type is internal to its module; "
                        + "reaching it makes the two modules one module with a documented fiction "
                        + "between them, and CON-PLT-015 records that the erosion cannot be undone "
                        + "once two modules read the same table.\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("CON-PLT-011: no kernel module reaches any domain module, in any layer")
    void kernelReachesNoDomainModule() {
        List<String> violations = new ArrayList<>();

        for (JavaClass type : platform) {
            if (!type.getPackageName().startsWith("aspm.kernel.")) {
                continue;
            }
            for (JavaClass target : type.getDirectDependenciesFromSelf().stream()
                    .map(dependency -> dependency.getTargetClass())
                    .toList()) {
                if (target.getPackageName().startsWith("aspm.module.")) {
                    violations.add(type.getFullName() + "  ->  " + target.getFullName());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "CON-PLT-011: a kernel with a domain dependency is not a kernel. Every module "
                        + "depends on all five kernel modules, so one such edge makes every module "
                        + "transitively depend on that domain model.\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("The test itself is not vacuous: module and kernel classes were imported")
    void theImportIsNotEmpty() {
        long moduleClasses = platform.stream()
                .filter(c -> Coordinate.of(c.getPackageName()) != null)
                .count();
        assertTrue(moduleClasses >= 20,
                "expected at least the twenty module descriptors on the classpath, found "
                        + moduleClasses + ". A cross-module rule over an empty import passes for the "
                        + "wrong reason, which is the failure prompt 2's demonstration exists to catch.");
    }
}
