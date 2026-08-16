package aspm.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.sharedkernel.AspmModule;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CON-PLT-010 — one module per bounded context, with no second decomposition scheme.
 *
 * <p>This test exists because the module inventory is otherwise a document that drifts from the
 * build. DOC-02 section 6.1 lists five kernel modules and fifteen further modules; a subproject
 * added without a descriptor, or a descriptor added without a subproject, is a divergence between
 * the topology the corpus specifies and the topology that compiles.
 *
 * <p>It is also what keeps the CON-PLT-016 slice rules non-vacuous. A cycle check over an empty
 * module graph passes for the wrong reason.
 */
class ModuleInventoryTest {

    /** The twenty modules of DOC-02 section 6.1: five kernel, fifteen domain, supporting, generic and projection. */
    private static final Set<String> EXPECTED_KERNEL_MODULES = Set.of(
            "tenant-context", "authorization", "audit", "schema-registry", "rules-engine");

    private static final Set<String> EXPECTED_MODULES = Set.of(
            "organization-scope", "asset-inventory", "knowledge", "vulnerability-management",
            "ingestion", "composition-analysis", "assessment", "risk-prioritization",
            "work-management", "capacity", "identity", "notification", "integration",
            "insight", "ai-assistance");

    private static JavaClasses platform;

    @BeforeAll
    static void importPlatform() {
        platform = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("aspm..");
    }

    private static Set<String> descriptorNames(String packagePrefix) {
        return platform.stream()
                .filter(c -> c.getPackageName().startsWith(packagePrefix))
                .filter(c -> c.isAnnotatedWith(AspmModule.class))
                .map(ModuleInventoryTest::readName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String readName(JavaClass descriptor) {
        return descriptor.getAnnotationOfType(AspmModule.class).name();
    }

    @Test
    @DisplayName("CON-PLT-010: exactly the five kernel modules of DOC-02 section 6.2 are present")
    void kernelInventoryMatchesTheCorpus() {
        assertEquals(new TreeSet<>(EXPECTED_KERNEL_MODULES), descriptorNames("aspm.kernel."),
                "kernel module inventory diverged from DOC-02 section 6.2");
    }

    @Test
    @DisplayName("CON-PLT-010: exactly the fifteen modules of DOC-02 Figure 6.1 are present")
    void moduleInventoryMatchesTheCorpus() {
        assertEquals(new TreeSet<>(EXPECTED_MODULES), descriptorNames("aspm.module."),
                "module inventory diverged from DOC-02 Figure 6.1");
    }

    @Test
    @DisplayName("CON-PLT-014: every declared module dependency names a module that exists")
    void declaredDependenciesResolve() {
        for (JavaClass descriptor : platform.stream()
                .filter(c -> c.getPackageName().startsWith("aspm.module."))
                .filter(c -> c.isAnnotatedWith(AspmModule.class))
                .toList()) {
            String[] declared = descriptor.getAnnotationOfType(AspmModule.class).permittedDependencies();
            for (String dependency : declared) {
                assertTrue(EXPECTED_MODULES.contains(dependency),
                        descriptor.getSimpleName() + " declares a dependency on '" + dependency
                                + "', which is not a module of DOC-02 Figure 6.1");
                assertFalse(dependency.equals(readName(descriptor)),
                        descriptor.getSimpleName() + " declares a dependency on itself");
            }
        }
    }
}
