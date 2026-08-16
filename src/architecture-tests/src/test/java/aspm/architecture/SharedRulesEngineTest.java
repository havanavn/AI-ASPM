package aspm.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code CON-PLT-012} — one condition evaluator, not four.
 *
 * <p>DOC-02 section 6.2 gives the reason in one sentence: "Four implementations would diverge, and one of them
 * governs authorization-relevant workflow transitions." Divergence there is a privilege escalation, and it is the
 * kind that appears as inconsistent behaviour between two features rather than as a security defect.
 *
 * <p>The four callers are workflow guards, automation rules, service-level policy matching, and checklist
 * selection. Each reaches the evaluator through {@code ConditionEvaluation} in the rules-engine contract; none may
 * carry its own. The compile classpath already stops a module <i>importing</i> {@code rules-engine-impl}, which is
 * why the failure mode this test catches is the other one — a module quietly writing a second evaluator of its
 * own, which no classpath rule can see.
 *
 * <p>This assertion lives here rather than in any module's own tests because no module can see the others. It is
 * the same reasoning that put {@code FingerprintConfinementTest} here.
 */
class SharedRulesEngineTest {

    private static final String PORT = "aspm.kernel.rulesengine.contract.ConditionEvaluation";

    /** The only package permitted to implement the port. */
    private static final String RULES_ENGINE_IMPL = "aspm.kernel.rulesengine.domain";

    private static JavaClasses platform;

    @BeforeAll
    static void importPlatform() {
        platform = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("aspm..");
    }

    @Test
    @DisplayName("CON-PLT-012: exactly one production implementation of the evaluation port")
    void onlyOneEvaluatorImplementation() {
        List<JavaClass> implementations = platform.stream()
                .filter(c -> c.getAllRawInterfaces().stream()
                        .anyMatch(i -> i.getFullName().equals(PORT)))
                .toList();

        assertTrue(implementations.size() >= 1,
                "no implementation of " + PORT + " was found. Either the port has no implementation — in which "
                        + "case every guard, automation rule and policy match is unwired — or this test is "
                        + "importing nothing, which would make it pass for the wrong reason.");

        for (JavaClass implementation : implementations) {
            assertEquals(RULES_ENGINE_IMPL, implementation.getPackageName(),
                    implementation.getFullName() + " implements " + PORT + " outside the rules engine. A second "
                            + "evaluator agrees with the first on the day it is written and diverges on the "
                            + "first change to either — and one of the four callers governs "
                            + "authorization-relevant workflow transitions (DOC-02 section 6.2).");
        }

        assertEquals(1, implementations.size(),
                "found " + implementations.size() + " implementations: " + implementations.stream()
                        .map(JavaClass::getFullName).toList()
                        + ". Even inside the rules engine there is one evaluator, because a second would be "
                        + "reachable through the same port and callers cannot tell them apart.");
    }

    @Test
    @DisplayName("no module outside the rules engine names a class an evaluator of conditions")
    void noModuleCarriesItsOwnConditionEvaluator() {
        // The name heuristic caught two defects earlier in this build and produced one false failure, so it is
        // used here only as a SUPPLEMENT to the interface check above — never as the property itself. A class
        // named Evaluator that does not implement the port is worth a look; a class that implements the port is
        // a failure regardless of its name, and the test above is what asserts that.
        List<String> suspicious = platform.stream()
                .filter(c -> !c.getPackageName().startsWith("aspm.kernel.rulesengine"))
                .filter(c -> {
                    String simple = c.getSimpleName();
                    return simple.contains("ConditionEvaluator") || simple.contains("GuardEvaluator")
                            || simple.contains("RuleEvaluator");
                })
                .map(JavaClass::getFullName)
                .toList();

        assertTrue(suspicious.isEmpty(),
                "these classes look like a second evaluator: " + suspicious + ". If one of them is, it belongs "
                        + "in the rules engine behind ConditionEvaluation (CON-PLT-012). If none is, rename "
                        + "them — a name that reads as a rules evaluator will be treated as one by the next "
                        + "person to need one.");
    }
}
