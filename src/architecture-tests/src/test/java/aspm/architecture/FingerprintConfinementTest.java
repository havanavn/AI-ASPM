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
 * {@code INV-ING-01} — no module outside Ingestion writes a fingerprint.
 *
 * <p>DOC-16 section 4.2 lists this among the twelve invariants that are unrecoverable if violated, and singles it
 * out: "<b>the one unrecoverable invariant with no database enforcement</b>". There is no column constraint that
 * can express "only this module produced this value", so if this assertion does not exist, nothing enforces it.
 *
 * <p>Why it matters more than it looks. ADR-011 requires one normalization and deduplication pipeline shared by
 * file import and native matching, and {@code INV-VUL-06} requires the fingerprint to be identical regardless of
 * source path. A second computation site would satisfy neither: the two would agree on the day they were written
 * and diverge on the first change to either. The divergence would appear as duplicate findings for some sources
 * and not others — which reads as a parser bug, is triaged as one, and is not.
 *
 * <p>The three mechanisms, from {@code FingerprintComputation}'s own comment: location in {@code ingestion-impl}
 * so it is off every other module's compile classpath; this bytecode assertion; and a contract surface that
 * publishes the digest without the means to compute one. This test is the second, and it is the only one of the
 * three that catches a class placed inside {@code ingestion-impl} by someone who did not realise why it matters.
 */
class FingerprintConfinementTest {

    /** The only package permitted to reach the fingerprint types. */
    private static final String INGESTION = "aspm.module.ingestion";

    /** Type names whose use constitutes computing or holding a fingerprint's means of computation. */
    private static final List<String> FINGERPRINT_TYPES = List.of(
            "aspm.module.ingestion.domain.FindingFingerprint",
            "aspm.module.ingestion.domain.FingerprintComputation",
            "aspm.module.ingestion.domain.FingerprintInputs");

    private static JavaClasses platform;

    @BeforeAll
    static void importPlatform() {
        platform = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("aspm..");
    }

    @Test
    @DisplayName("INV-ING-01: no class outside aspm.module.ingestion references a fingerprint type")
    void fingerprintTypesAreConfinedToIngestion() {
        List<String> violations = new ArrayList<>();

        for (JavaClass type : platform) {
            if (type.getPackageName().startsWith(INGESTION)) {
                continue;
            }
            for (JavaClass target : type.getDirectDependenciesFromSelf().stream()
                    .map(dependency -> dependency.getTargetClass())
                    .toList()) {
                String name = target.getFullName();
                for (String confined : FINGERPRINT_TYPES) {
                    if (name.equals(confined) || name.startsWith(confined + "$")) {
                        violations.add(type.getFullName() + "  ->  " + name);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "INV-ING-01: the fingerprint is computed in Ingestion and nowhere else (INV-VUL-06, ADR-011). "
                        + "A second computation site agrees on the day it is written and diverges on the first "
                        + "change to either, and the divergence appears as duplicate findings for some sources "
                        + "and not others — which reads as a parser bug, is triaged as one, and is not. DOC-16 "
                        + "section 4.2 records this as the one unrecoverable invariant with no database "
                        + "enforcement, so this assertion is the enforcement.\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("INV-ING-01: the fingerprint types exist, so this assertion is not vacuous")
    void theConfinedTypesExist() {
        for (String confined : FINGERPRINT_TYPES) {
            assertTrue(platform.stream().anyMatch(c -> c.getFullName().equals(confined)),
                    confined + " is absent, so the confinement assertion above verifies nothing. If these "
                            + "types are renamed, this list must be updated in the same change — a stale name "
                            + "here is a silently disabled control.");
        }
    }

    @Test
    @DisplayName("no type outside ingestion declares a method whose name suggests it computes a fingerprint")
    void noForeignFingerprintFactoryExists() {
        List<String> suspicious = new ArrayList<>();
        for (JavaClass type : platform) {
            if (type.getPackageName().startsWith(INGESTION)) {
                continue;
            }
            for (var method : type.getMethods()) {
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("fingerprint") && (name.startsWith("compute") || name.startsWith("make")
                        || name.startsWith("create") || name.startsWith("derive") || name.startsWith("build"))) {
                    suspicious.add(type.getFullName() + "." + method.getName());
                }
            }
        }
        assertTrue(suspicious.isEmpty(),
                "a fingerprint-producing method outside ingestion would be a second identity regime even if it "
                        + "reached the same answer today: " + suspicious);
    }
}
