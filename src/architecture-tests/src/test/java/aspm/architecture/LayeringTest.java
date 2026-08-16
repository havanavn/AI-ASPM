package aspm.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CON-PLT-017 — domain layer purity.
 *
 * <p>DOC-02 section 8.1 makes the inward rule absolute in one direction. The domain layer
 * is where the invariants live, and a domain that depends on persistence or transport
 * cannot be tested without them — which means the invariant tests get run less often,
 * which is the actual failure this constraint prevents.
 */
class LayeringTest {

    private static JavaClasses platform;

    /** Package prefixes that must not appear in a domain layer, with the reason each is listed. */
    private static final String[] FORBIDDEN_IN_DOMAIN = {
        "java.sql..",              // persistence
        "javax.sql..",
        "jakarta.persistence..",
        "javax.persistence..",
        "jakarta.servlet..",       // transport
        "javax.servlet..",
        "java.net.http..",
        "com.fasterxml.jackson..", // serialization
        "jakarta.json..",
        "org.springframework..",   // framework
    };

    @BeforeAll
    static void importPlatform() {
        platform = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("aspm..");
    }

    @Test
    @DisplayName("CON-PLT-017: no domain class depends on persistence, framework, transport or serialization")
    void domainIsPure() {
        noClasses()
                .that().resideInAnyPackage("aspm.module.*.domain..", "aspm.kernel.*.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN_IN_DOMAIN)
                .because("CON-PLT-017 and DOC-02 section 8.1: a domain that cannot be tested without "
                        + "infrastructure is a domain whose invariant tests are run less often.")
                .allowEmptyShould(true)
                .check(platform);
    }

    @Test
    @DisplayName("CON-PLT-018: a domain layer does not depend on its own application or infrastructure layer")
    void domainDoesNotDependOutward() {
        noClasses()
                .that().resideInAnyPackage("aspm.module.*.domain..", "aspm.kernel.*.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("aspm.module.*.application..", "aspm.module.*.infrastructure..",
                        "aspm.kernel.*.application..", "aspm.kernel.*.infrastructure..")
                .because("DOC-02 section 8.1: within a module, dependencies point inward.")
                .allowEmptyShould(true)
                .check(platform);
    }
}
