package aspm.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PRD-AIC-021} — AI containment, as an architectural property.
 *
 * <p>"The AI module MUST hold no write grant on any domain table and MUST have no compile-time dependency on any
 * domain module." The rationale is the reason this file exists rather than a coding standard: "ADR-005 stated as
 * an architectural property rather than a policy. <b>A policy is violated by a prompt; an absent grant and an
 * absent dependency edge are not.</b>"
 *
 * <p>DOC-10 section 12 calls containment "load-bearing": "AI holds no write authority. A successful injection
 * produces a misleading <i>narrative</i>, not a state change." Indirect prompt injection through ingested
 * findings is the fifth of the platform's highest-risk surfaces — reachable by an attacker with no platform
 * access, because finding content legitimately includes attacker-authored text. This test is what keeps the
 * consequence of a successful injection bounded to text.
 *
 * <p>Two halves, and both are checked here because neither module can see the other: the bytecode half over
 * every class, and the grant half over every migration.
 */
class AiContainmentTest {

    private static final String AI_PACKAGE = "aspm.module.aiassistance";

    /** Package prefixes of the domain modules. The AI module may depend on none of them. */
    private static final List<String> DOMAIN_MODULE_PACKAGES = List.of(
            "aspm.module.organizationscope",
            "aspm.module.assetinventory",
            "aspm.module.vulnerabilitymanagement",
            "aspm.module.ingestion",
            "aspm.module.compositionanalysis",
            "aspm.module.assessment",
            "aspm.module.riskprioritization",
            "aspm.module.workmanagement",
            "aspm.module.capacity",
            "aspm.module.identity",
            "aspm.module.notification",
            "aspm.module.integration",
            "aspm.module.insight",
            "aspm.module.knowledge");

    private static JavaClasses platform;

    @BeforeAll
    static void importPlatform() {
        platform = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("aspm..");
    }

    @Test
    @DisplayName("PRD-AIC-021: the AI module has no compile-time dependency on any domain module")
    void noCompileTimeDependencyOnADomainModule() {
        List<String> violations = new ArrayList<>();
        platform.stream()
                .filter(c -> c.getPackageName().startsWith(AI_PACKAGE))
                .forEach(aiClass -> aiClass.getDirectDependenciesFromSelf().forEach(dependency -> {
                    String target = dependency.getTargetClass().getPackageName();
                    for (String domainPackage : DOMAIN_MODULE_PACKAGES) {
                        if (target.startsWith(domainPackage)) {
                            violations.add(aiClass.getName() + " -> " + dependency.getTargetClass().getName());
                        }
                    }
                }));

        assertTrue(violations.isEmpty(),
                "the AI module reaches a domain module: " + violations + ". A policy is violated by a prompt; "
                        + "an absent dependency edge is not. Containment is load-bearing — a successful "
                        + "injection must produce a misleading narrative, not a state change (ADR-005, "
                        + "DOC-10 section 12).");

        // Guard against the assertion passing because nothing was imported, which is how a structural test
        // silently stops testing.
        assertTrue(platform.stream().anyMatch(c -> c.getPackageName().startsWith(AI_PACKAGE)),
                "no AI module classes were imported, so this assertion proved nothing");
    }

    @Test
    @DisplayName("PRD-AIC-021: no migration grants the AI module write access to a domain table")
    void noWriteGrantInAnyMigration() throws IOException {
        List<Path> migrations = findMigrations();
        assertTrue(migrations.size() >= 12,
                "expected the twelve migrations written so far; found " + migrations.size()
                        + ". A grant check that reads no files passes for the wrong reason.");

        List<String> violations = new ArrayList<>();
        for (Path migration : migrations) {
            String sql = Files.readString(migration, StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            // Any GRANT naming an AI role. The check is deliberately broad: a role named for the AI module
            // receiving INSERT, UPDATE or DELETE on anything is the failure, whatever the table.
            for (String line : sql.split("\n", -1)) {
                if (!line.contains("grant")) {
                    continue;
                }
                boolean namesAiRole = line.contains("ai_runtime") || line.contains("ai_assistance")
                        || line.contains("aiassistance");
                boolean grantsWrite = line.contains("insert") || line.contains("update")
                        || line.contains("delete") || line.contains("all privileges");
                if (namesAiRole && grantsWrite) {
                    violations.add(migration.getFileName() + ": " + line.strip());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "a migration grants the AI module write access: " + violations + ". ADR-005 confines AI to a "
                        + "suggestion ledger, and promotion into the system of record is an audited human "
                        + "action — an absent grant is what makes that structural rather than procedural.");
    }

    @Test
    @DisplayName("no domain module depends on the AI module either")
    void noDomainModuleDependsOnAi() {
        List<String> violations = new ArrayList<>();
        platform.stream()
                .filter(c -> DOMAIN_MODULE_PACKAGES.stream().anyMatch(p -> c.getPackageName().startsWith(p)))
                .forEach(domainClass -> domainClass.getDirectDependenciesFromSelf().forEach(dependency -> {
                    if (dependency.getTargetClass().getPackageName().startsWith(AI_PACKAGE)) {
                        violations.add(domainClass.getName() + " -> "
                                + dependency.getTargetClass().getName());
                    }
                }));

        assertTrue(violations.isEmpty(),
                "a domain module reaches the AI module: " + violations + ". The dependency is absent in BOTH "
                        + "directions, because a domain module calling AI synchronously puts a model in a "
                        + "decision path (ADR-005) — and it would do so through a call that looks like any "
                        + "other.");
    }

    private static List<Path> findMigrations() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(p -> p.toString().contains("/db/migration/"))
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .sorted()
                    .toList();
        }
    }
}
