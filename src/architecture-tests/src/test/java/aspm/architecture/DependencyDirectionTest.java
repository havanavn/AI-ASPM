package aspm.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.sharedkernel.AspmModule;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CON-PLT-014 — the permitted dependency direction must match the relationship patterns of
 * DOC-03 section 5.3, and a dependency in the prohibited direction must fail the build.
 *
 * <p>There are two halves to this control and each catches what the other cannot.
 *
 * <p><b>The enforced half</b> is the Gradle subproject dependency list: a module contract that is
 * not declared as a dependency is not on the compile classpath, so importing from it is a
 * resolution failure. That has no severity dial and no suppression annotation, which is why
 * ADR-050 treats it as the primary mechanism.
 *
 * <p><b>The declared half</b> is {@code @AspmModule(permittedDependencies = ...)} on each module
 * descriptor. The Gradle list alone is enforcement without a statement of intent: it records what
 * compiles, not what DOC-03 section 5.3 permits. This test asserts that the actual bytecode
 * dependency graph is a subset of the declared graph, which is what turns "somebody added a line to
 * a build file" into a reviewable divergence. It is deliberately a subset rather than an equality
 * check: a declared dependency not yet used is a permission, not a defect.
 *
 * <p><b>Limitation, stated because the prompt 2 violation run exposed it rather than because it was
 * anticipated.</b> {@code javac} folds a compile-time constant into its call site, so a class that
 * reads only a {@code static final String} from another module leaves <em>no</em> bytecode
 * dependency and this test cannot see it. The first version of the violation demonstration used
 * exactly that form and this test passed while the violation was present. That case is caught by the
 * compile classpath instead — the referenced type must still be resolvable, and the Gradle
 * subproject partition is what makes it unresolvable. The two mechanisms are complementary, not
 * redundant, and a reviewer must not read this test as covering the constant case.
 */
class DependencyDirectionTest {

    private static JavaClasses platform;

    @BeforeAll
    static void importPlatform() {
        platform = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("aspm..");
    }

    /** Module simple-name segment, e.g. "assetinventory", from a package such as aspm.module.assetinventory.domain. */
    private static String moduleSegment(String packageName) {
        String prefix = "aspm.module.";
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String rest = packageName.substring(prefix.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /** The declared graph, keyed by package segment, read from the compiled descriptors. */
    private static Map<String, Set<String>> declaredGraph() {
        Map<String, Set<String>> declared = new TreeMap<>();
        for (JavaClass descriptor : platform.stream()
                .filter(c -> c.isAnnotatedWith(AspmModule.class))
                .filter(c -> c.getPackageName().startsWith("aspm.module."))
                .toList()) {
            String segment = moduleSegment(descriptor.getPackageName());
            declared.put(segment,
                    java.util.Arrays.stream(
                                    descriptor.getAnnotationOfType(AspmModule.class).permittedDependencies())
                            .map(d -> d.replace("-", ""))
                            .collect(Collectors.toCollection(TreeSet::new)));
        }
        return declared;
    }

    /** The actual graph, read from compiled bytecode. */
    private static Map<String, Set<String>> actualGraph() {
        Map<String, Set<String>> actual = new TreeMap<>();
        for (JavaClass type : platform) {
            String from = moduleSegment(type.getPackageName());
            if (from == null) {
                continue;
            }
            Set<String> targets = actual.computeIfAbsent(from, k -> new TreeSet<>());
            for (JavaClass dependency : type.getDirectDependenciesFromSelf().stream()
                    .map(d -> d.getTargetClass()).toList()) {
                String to = moduleSegment(dependency.getPackageName());
                if (to != null && !to.equals(from)) {
                    targets.add(to);
                }
            }
        }
        return actual;
    }

    @Test
    @DisplayName("CON-PLT-014: the actual module dependency graph is within the graph declared per DOC-03 section 5.3")
    void actualGraphIsWithinDeclaredGraph() {
        Map<String, Set<String>> declared = declaredGraph();
        Map<String, Set<String>> actual = actualGraph();

        assertTrue(declared.size() >= 15,
                "expected a descriptor for each of the fifteen modules of DOC-02 Figure 6.1, found "
                        + declared.size() + "; without them this test verifies nothing");

        for (Map.Entry<String, Set<String>> entry : actual.entrySet()) {
            Set<String> permitted = declared.getOrDefault(entry.getKey(), Set.of());
            Set<String> undeclared = new TreeSet<>(entry.getValue());
            undeclared.removeAll(permitted);
            assertTrue(undeclared.isEmpty(),
                    "module '" + entry.getKey() + "' depends on " + undeclared
                            + " but declares only " + permitted + ". Either the dependency is in the "
                            + "prohibited direction per DOC-03 section 5.3 and must be removed, or the "
                            + "relationship is genuine and must be added to the descriptor with its "
                            + "DOC-03 section 5.3 row — CON-PLT-014.");
        }
    }

    @Test
    @DisplayName("CON-PLT-014: no module depends on a module that declares a dependency on it")
    void noMutualModuleDependency() {
        Map<String, Set<String>> declared = declaredGraph();
        for (Map.Entry<String, Set<String>> entry : declared.entrySet()) {
            for (String target : entry.getValue()) {
                Set<String> reverse = declared.getOrDefault(target, Set.of());
                assertTrue(!reverse.contains(entry.getKey()),
                        "'" + entry.getKey() + "' and '" + target + "' declare dependencies on each "
                                + "other. CON-PLT-016: a cycle means the two modules are one module with "
                                + "a documented fiction between them.");
            }
        }
    }
}
