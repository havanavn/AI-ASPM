package aspm.module.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.ingestion.domain.CodeLocationNormalization;
import aspm.module.ingestion.domain.FindingFingerprint;
import aspm.module.ingestion.domain.FingerprintComputation;
import aspm.module.ingestion.domain.FingerprintInputs;
import aspm.sharedkernel.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rescan corpus of DOC-16 section 7.1 — all eleven cases, and {@code TST-VUL-001}.
 *
 * <p>{@code TST-VUL-001}: "The rescan corpus MUST assert both stability and distinctness… Stability alone
 * permits an algorithm that collapses distinct issues; distinctness alone permits one that splits on every
 * rescan. Both failures destroy data trust, in opposite directions."
 *
 * <p>This is prompt 6's review point. DOC-03 section 10.2 states what a failure here costs: "the team stops
 * believing the number — after which no subsequent correctness recovers the deployment." Every case below names
 * the change and the expected outcome from DOC-16 section 7.1's table verbatim.
 */
class RescanCorpusTest {

    private static final TenantId TENANT_A = new TenantId(new UUID(30, 1));
    private static final TenantId TENANT_B = new TenantId(new UUID(30, 2));
    private static final String ASSET = "github.com/acme/api";
    private static final String OTHER_ASSET = "github.com/acme/web";
    private static final String RULE = "java.sql-injection";
    private static final String OTHER_RULE = "java.xss";

    /** A CODE finding as a first scan reports it. */
    private static FindingFingerprint codeFinding(String reportedPath, String enclosing, String snippet) {
        return FingerprintComputation.forCode(TENANT_A, RULE, ASSET,
                CodeLocationNormalization.normalize(reportedPath),
                CodeLocationNormalization.structuralContextHash(enclosing, snippet));
    }

    // ============================================================ STABILITY: same finding expected

    @Nested
    @DisplayName("Stability — changes that must NOT create a new finding")
    class Stability {

        @Test
        @DisplayName("1. Code reformatted; line numbers shift → same finding")
        void reformattingIsStable() {
            var before = codeFinding("src/main/java/Repo.java", "public void find(String id)",
                    "String q = \"SELECT * FROM t WHERE id=\" + id;");
            // A reformatter changes whitespace and moves every line. Neither is a fingerprint input.
            var after = codeFinding("src/main/java/Repo.java", "public void find( String id )",
                    "String q =\n        \"SELECT * FROM t WHERE id=\"\n        + id;");

            assertTrue(before.identifiesSameAs(after),
                    "a fingerprint including line numbers produces a new finding on reformatting, which "
                            + "destroys triage state for a change that altered nothing (DOC-03 section 10.2)");
        }

        @Test
        @DisplayName("2. File moved or renamed within the asset → same finding")
        void fileMoveIsStable() {
            var before = codeFinding("src/main/java/com/acme/dao/Repo.java", "public void find(String id)",
                    "String q = concat(id);");
            var after = codeFinding("src/main/java/com/acme/persistence/Repo.java",
                    "public void find(String id)", "String q = concat(id);");

            assertTrue(before.identifiesSameAs(after),
                    "the directory changed and the code did not");
        }

        @Test
        @DisplayName("3. Scanner version upgraded, same rule → same finding")
        void scannerUpgradeIsStable() {
            // The scanner version is not a declared input for any class, so there is no parameter to vary —
            // which is the assertion. Attempting to add it is rejected.
            assertThrows(IllegalArgumentException.class,
                    () -> FingerprintInputs.builder(TENANT_A, FingerprintInputs.FindingClass.CODE)
                            .with("scanner_version", "2.14.0"),
                    "the scanner version is excluded by DOC-03 section 10.2, and the builder rejects an "
                            + "undeclared input rather than trusting a caller not to supply it");
        }

        @Test
        @DisplayName("4. Branch differs, same code → same finding")
        void branchIsStable() {
            var onMain = codeFinding("/github/workspace/src/main/java/Repo.java",
                    "public void find(String id)", "String q = concat(id);");
            var onFeature = codeFinding("/builds/feature-x/src/main/java/Repo.java",
                    "public void find(String id)", "String q = concat(id);");

            assertTrue(onMain.identifiesSameAs(onFeature),
                    "an absolute build-agent path differs between CI runs of the same commit; including it "
                            + "produces a new finding per CI provider");
        }

        @Test
        @DisplayName("5. Manifest path differs (dependency finding) → same finding")
        void manifestPathIsStable() {
            var first = FingerprintComputation.forDependency(
                    TENANT_A, "CVE-2024-0001", "pkg:maven/org.acme/lib@1.2.3", "[1.0.0,1.3.0)", ASSET);
            var second = FingerprintComputation.forDependency(
                    TENANT_A, "CVE-2024-0001", "pkg:maven/org.acme/lib@1.2.3", "[1.0.0,1.3.0)", ASSET);

            assertTrue(first.identifiesSameAs(second));
            // There is no manifest-path parameter at all, and adding one is rejected: "the identity of a
            // vulnerable component does not depend on where its manifest sits. Including manifest path splits
            // one finding across a monorepo."
            assertThrows(IllegalArgumentException.class,
                    () -> FingerprintInputs.builder(TENANT_A, FingerprintInputs.FindingClass.DEPENDENCY)
                            .with("manifest_path", "services/a/pom.xml"));
        }

        @Test
        @DisplayName("6. Concrete parameter value differs (runtime finding) → same finding")
        void parameterValueIsStable() {
            var withOneValue = FingerprintComputation.forRuntime(TENANT_A, RULE, ASSET,
                    CodeLocationNormalization.normalizeRequestPath("/search?q=alert(1)"), "q");
            var withAnother = FingerprintComputation.forRuntime(TENANT_A, RULE, ASSET,
                    CodeLocationNormalization.normalizeRequestPath("/search?q=%3Cscript%3E"), "q");

            assertTrue(withOneValue.identifiesSameAs(withAnother),
                    "a payload reflected at /search?q=X is one finding, not one per value of X");
        }

        @Test
        @DisplayName("6b. A path identifier differs (runtime finding) → same finding")
        void pathIdentifierIsStable() {
            var forOneUser = FingerprintComputation.forRuntime(TENANT_A, RULE, ASSET,
                    CodeLocationNormalization.normalizeRequestPath("/users/123/profile"), "name");
            var forAnother = FingerprintComputation.forRuntime(TENANT_A, RULE, ASSET,
                    CodeLocationNormalization.normalizeRequestPath("/users/456/profile"), "name");

            assertTrue(forOneUser.identifiesSameAs(forAnother),
                    "without collapsing, an inventory of a REST service is unbounded and so is its finding count");
        }
    }

    // ============================================================ DISTINCTNESS: distinct expected

    @Nested
    @DisplayName("Distinctness — changes that MUST create a separate finding")
    class Distinctness {

        @Test
        @DisplayName("7. Different rule, same location → distinct findings")
        void differentRuleIsDistinct() {
            var injection = FingerprintComputation.forCode(TENANT_A, RULE, ASSET, "repo.java", "abc123");
            var xss = FingerprintComputation.forCode(TENANT_A, OTHER_RULE, ASSET, "repo.java", "abc123");

            assertFalse(injection.identifiesSameAs(xss),
                    "collapsing two weaknesses at one location means fixing one appears to fix both, and "
                            + "closure is wrong (DOC-03 section 10.2's 'too loose' failure)");
        }

        @Test
        @DisplayName("8. Different component, same manifest → distinct findings")
        void differentComponentIsDistinct() {
            var libA = FingerprintComputation.forDependency(
                    TENANT_A, "CVE-2024-0001", "pkg:maven/org.acme/lib-a@1.0.0", "[1.0.0,1.1.0)", ASSET);
            var libB = FingerprintComputation.forDependency(
                    TENANT_A, "CVE-2024-0001", "pkg:maven/org.acme/lib-b@1.0.0", "[1.0.0,1.1.0)", ASSET);

            assertFalse(libA.identifiesSameAs(libB),
                    "one CVE affecting two components is two findings; they are fixed independently");
        }

        @Test
        @DisplayName("9. Different asset, same rule → distinct findings")
        void differentAssetIsDistinct() {
            var onApi = FingerprintComputation.forCode(TENANT_A, RULE, ASSET, "repo.java", "abc123");
            var onWeb = FingerprintComputation.forCode(TENANT_A, RULE, OTHER_ASSET, "repo.java", "abc123");

            assertFalse(onApi.identifiesSameAs(onWeb),
                    "two assets are owned by potentially different nodes; collapsing them would route one "
                            + "team's finding to another");
        }

        @Test
        @DisplayName("9b. Two distinct weaknesses in ONE file are distinct")
        void twoWeaknessesInOneFileAreDistinct() {
            var first = codeFinding("src/Repo.java", "public void find(String id)", "concat(id)");
            var second = codeFinding("src/Repo.java", "public void update(String id)", "concat(id)");

            assertFalse(first.identifiesSameAs(second),
                    "this is what the structural context hash is for: once the location is reduced to a "
                            + "basename, distinctness within a file must come from the surrounding syntax");
        }
    }

    // ============================================================ recurrence

    @Nested
    @DisplayName("Recurrence — cases 10 and 11")
    class Recurrence {

        @Test
        @DisplayName("10 and 11. Reappearance after closure, and after five years → same finding")
        void reappearanceIsTheSameFinding() {
            var original = codeFinding("src/Repo.java", "public void find(String id)", "concat(id)");
            // Five years later, a different scanner version, a moved file, a reformatted body. Nothing that
            // the fingerprint hashes has changed, so the digest is identical and the finding is reopened with
            // recurrence incremented rather than created afresh.
            var reappearance = codeFinding("app/src/main/java/Repo.java",
                    "public   void find( String id )", "concat( id )");

            assertTrue(original.identifiesSameAs(reappearance),
                    "no time component is hashed, so elapsed time cannot make a recurrence look like a new "
                            + "finding — which would reset its age and hide that it was fixed and regressed");
            assertEquals(original.digestHex(), reappearance.digestHex());
        }
    }

    // ============================================================ INV-VUL-01 to -04

    @Nested
    @DisplayName("INV-VUL-01 to -04 — the invariants behind the corpus")
    class Invariants {

        @Test
        @DisplayName("INV-VUL-01: the same finding in two tenants has different digests")
        void fingerprintIsTenantScopedInItsInputs() {
            var inTenantA = FingerprintComputation.forCode(TENANT_A, RULE, ASSET, "repo.java", "abc123");
            var inTenantB = FingerprintComputation.forCode(TENANT_B, RULE, ASSET, "repo.java", "abc123");

            assertFalse(inTenantA.identifiesSameAs(inTenantB),
                    "a global fingerprint permits cross-tenant inference: submit a finding, observe whether "
                            + "it is treated as new, learn whether another tenant has the same vulnerability. "
                            + "The isolation must be in the hash INPUTS, not merely in the query filter.");
        }

        @Test
        @DisplayName("INV-VUL-01: the tenant cannot be omitted, because it is not an input a caller supplies")
        void tenantCannotBeOmitted() {
            assertThrows(NullPointerException.class,
                    () -> FingerprintInputs.builder(null, FingerprintInputs.FindingClass.CODE));
            // And it is not in the declared input set, so no caller can choose whether to include it.
            for (var findingClass : FingerprintInputs.FindingClass.values()) {
                assertFalse(findingClass.declaredInputs().contains("tenant"),
                        findingClass + " lists the tenant as an optional input, which would make INV-VUL-01 "
                                + "violable by omission");
            }
        }

        @Test
        @DisplayName("INV-VUL-02: deterministic across repeated computation")
        void deterministic() {
            var first = codeFinding("src/Repo.java", "m()", "x");
            for (int i = 0; i < 100; i++) {
                assertEquals(first.digestHex(), codeFinding("src/Repo.java", "m()", "x").digestHex(),
                        "no dependence on ordering, locale, or time");
            }
        }

        @Test
        @DisplayName("INV-VUL-03: a digest is never compared across algorithm versions")
        void versionsAreNotCompared() {
            var inputs = FingerprintInputs.builder(TENANT_A, FingerprintInputs.FindingClass.CODE)
                    .with("rule_identity", RULE)
                    .with("asset_identity", ASSET)
                    .with("normalized_code_location", "repo.java")
                    .with("structural_context_hash", "abc123")
                    .build();
            var atV1 = FingerprintComputation.of(inputs);

            assertEquals(FindingFingerprint.CURRENT_ALGORITHM_VERSION, atV1.algorithmVersion(),
                    "the version is recorded on every finding");
            assertThrows(IllegalArgumentException.class,
                    () -> FingerprintComputation.reFingerprint(inputs, 99),
                    "a migration must not guess a version: guessing produces digests matching nothing, which "
                            + "reads as a mass de-duplication failure rather than as a defect");
        }

        @Test
        @DisplayName("INV-VUL-04: the hashed inputs are retained on the fingerprint")
        void inputsAreRetained() {
            var fingerprint = codeFinding("src/Repo.java", "m()", "x");
            var retained = fingerprint.inputSnapshot();

            assertEquals(TENANT_A, retained.tenantId());
            assertEquals(FingerprintInputs.FindingClass.CODE, retained.findingClass());
            assertEquals(4, retained.values().size(),
                    "without retention a new algorithm version applies only to findings created after it, so "
                            + "the platform carries two identity regimes permanently and cross-version "
                            + "deduplication is impossible");

            // And the retained inputs are sufficient to recompute the same digest without the source tool.
            assertEquals(fingerprint.digestHex(),
                    FingerprintComputation.reFingerprint(retained, 1).digestHex());
        }

        @Test
        @DisplayName("PRD-ING-021: an absent input is distinguishable from an empty one")
        void absentIsNotEmpty() {
            var withEmpty = FingerprintComputation.forCode(TENANT_A, RULE, ASSET, "repo.java", "");
            var withValue = FingerprintComputation.forCode(TENANT_A, RULE, ASSET, "repo.java", "abc");
            assertFalse(withEmpty.identifiesSameAs(withValue));

            // A null input is rejected at build time rather than hashed as empty: an input the class declares
            // is part of identity, so a fingerprint without one would collide with every other finding
            // missing the same input.
            assertThrows(IllegalStateException.class,
                    () -> FingerprintInputs.builder(TENANT_A, FingerprintInputs.FindingClass.CODE)
                            .with("rule_identity", RULE)
                            .with("asset_identity", ASSET)
                            .with("normalized_code_location", null)
                            .with("structural_context_hash", "abc")
                            .build());
        }
    }
}
