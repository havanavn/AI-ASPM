package aspm.app.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.ingestion.application.SarifParser;
import aspm.module.ingestion.domain.QuarantinedRecord;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The SARIF parser, against what the tools actually emit. {@code TST-ING-001}.
 *
 * <p><b>Why there is a fixture per tool when there is only one parser.</b> {@code TST-ING-001} requires a
 * fixture per source and version, and the requirement earns its place here precisely because the parser is
 * shared: "it reads SARIF" is not evidence that it reads what semgrep writes. The four tools differ in
 * which OPTIONAL fields they populate, and every one of those differences is a way to produce a finding
 * with no identity, no severity, or the wrong location:
 *
 * <ul>
 *   <li><b>semgrep</b> puts {@code security-severity} on the rule and sometimes omits {@code level} on the
 *       result, so severity has to fall back to the rule's default configuration.
 *   <li><b>CodeQL</b> emits {@code ruleIndex} without {@code ruleId} for some results, and message text
 *       with {@code {0}} placeholders filled from {@code arguments}.
 *   <li><b>mobsfscan</b> emits no snippet at all, which is what makes the structural-hash fallback
 *       load-bearing rather than theoretical.
 *   <li><b>nuclei</b> reports a URL rather than a file, which is not a code finding at all.
 * </ul>
 *
 * <p>The fixtures are hand-written from each tool's documented output shape rather than captured from a
 * run, and that is a real limitation worth stating: a field a tool emits that none of these fixtures
 * contains is a field this suite does not cover. Replacing them with captured output from the group's own
 * pipelines is the next thing this suite needs.
 */
class SarifParserTest {

    private static Map<String, Object> fixture(String name) throws IOException {
        try (InputStream stream = SarifParserTest.class.getResourceAsStream("/sarif/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("fixture /sarif/" + name + " is missing");
            }
            return aspm.app.runtime.Json.readObject(new String(stream.readAllBytes(),
                    StandardCharsets.UTF_8));
        }
    }

    private static SarifParser.Parsed parse(String name) throws IOException {
        Map<String, Object> document = fixture(name);
        return SarifParser.parse(document, aspm.app.runtime.Json.write(document)
                .getBytes(StandardCharsets.UTF_8).length);
    }

    private static SarifParser.Result byRule(SarifParser.Parsed parsed, String ruleIdentity) {
        return parsed.results().stream().filter(r -> ruleIdentity.equals(r.ruleIdentity())).findFirst()
                .orElseThrow(() -> new AssertionError("no result for rule " + ruleIdentity));
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("semgrep 1.45.0")
    class Semgrep {

        @Test
        @DisplayName("security-severity outranks the SARIF level, and a passing result is not a finding")
        void severityAndKind() throws IOException {
            SarifParser.Parsed parsed = parse("semgrep-1.45.0.sarif");

            assertEquals(4, parsed.resultCount(), "four results in the document");
            assertEquals(3, parsed.results().size(), "three of them are weaknesses");
            // The fourth is kind=pass. Skipped, and COUNTED — a submitter whose report produced fewer
            // findings than results has to be able to see where the rest went.
            assertEquals(1, parsed.notWeaknesses());
            assertTrue(parsed.quarantined().isEmpty(), "nothing malformed in this fixture");
            assertEquals(java.util.Set.of("semgrep"), parsed.toolNames());

            var subprocess = byRule(parsed,
                    "python.lang.security.audit.dangerous-subprocess-use.dangerous-subprocess-use");
            // 8.8 is HIGH by the published CVSS banding, NOT medium — which is what the SARIF level
            // "warning" alone would have produced. This is the whole reason security-severity is read
            // first: SARIF's four levels cannot express the difference between a style warning and a
            // command injection, and both arrive as "warning".
            assertEquals(Integer.valueOf(2), subprocess.severityOrdinal());
            assertEquals("security-severity 8.8", subprocess.reportedSeverityRaw());
            assertEquals("refund.py", subprocess.normalizedLocation());
            assertEquals(Integer.valueOf(42), subprocess.startLine());
            assertEquals("semgrep", subprocess.toolName());
            assertEquals("1.45.0", subprocess.toolVersion(),
                    "semanticVersion is preferred over version: a version recorded per finding is only "
                            + "useful if it means the same thing across submissions");

            // 9.4 is CRITICAL. A four-level SARIF document cannot say that; the tool said it in a
            // property, and dropping the property would have filed a critical secret as a medium.
            var secret = byRule(parsed,
                    "generic.secrets.security.detected-generic-api-key.detected-generic-api-key");
            assertEquals(Integer.valueOf(1), secret.severityOrdinal());
        }

        @Test
        @DisplayName("a result with no level takes the rule's default configuration")
        void levelFallsBackToTheRule() throws IOException {
            var debug = byRule(parse("semgrep-1.45.0.sarif"),
                    "python.flask.security.audit.debug-enabled.debug-enabled");
            // The result carries no `level`; the rule's defaultConfiguration says error → ordinal 2.
            assertEquals(Integer.valueOf(2), debug.severityOrdinal());
            assertEquals("error", debug.reportedSeverityRaw());
            assertEquals("app.py", debug.normalizedLocation());
            assertNull(debug.snippet(), "semgrep omitted the snippet on this one");
        }

        @Test
        @DisplayName("the title names the weakness, not the occurrence")
        void titleComesFromTheRule() throws IOException {
            var subprocess = byRule(parse("semgrep-1.45.0.sarif"),
                    "python.lang.security.audit.dangerous-subprocess-use.dangerous-subprocess-use");
            // The rule's shortDescription, not the result's message. A queue titled by occurrence reads
            // as a log; titled by weakness it reads as work somebody can pick up.
            assertEquals("Detected subprocess function with user-controlled input", subprocess.title());
            assertTrue(subprocess.message().contains("'run'"), "the message keeps the occurrence detail");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("CodeQL 2.15.4")
    class CodeQl {

        @Test
        @DisplayName("a result carrying only ruleIndex resolves through the driver's rule array")
        void ruleIndexResolves() throws IOException {
            SarifParser.Parsed parsed = parse("codeql-2.15.4.sarif");
            assertEquals(3, parsed.results().size());
            assertTrue(parsed.quarantined().isEmpty(),
                    "ruleIndex without ruleId is valid SARIF and must not be held back");

            var weakCrypto = byRule(parsed, "java/weak-cryptographic-algorithm");
            assertEquals(Integer.valueOf(2), weakCrypto.severityOrdinal(), "security-severity 7.5 is high");
            // {0} filled from message.arguments — the specification's own substitution. Leaving the
            // placeholder would put "{0}" in front of a reader as if it were the algorithm's name.
            assertEquals("Cryptographic algorithm DES is weak and should not be used.",
                    weakCrypto.message());
            assertEquals("com.example.TokenCipher.encrypt", weakCrypto.enclosingConstruct());
        }

        @Test
        @DisplayName("only the primary location anchors the finding; the taint path stays in the record")
        void onlyTheFirstLocationAnchors() throws IOException {
            var injection = byRule(parse("codeql-2.15.4.sarif"), "java/sql-injection");
            // The document names three locations for this result — the sink, a related source, and a
            // code flow through both. One finding, one identity: anchoring to all of them would be one
            // finding claiming several.
            assertEquals("ledgerdao.java", injection.normalizedLocation());
            assertEquals(Integer.valueOf(214), injection.startLine());
            assertEquals(Integer.valueOf(1), injection.severityOrdinal(), "9.8 is critical");
            assertTrue(injection.rawRecord().contains("LedgerResource.java"),
                    "the source end of the flow is retained in the raw record, so the path is readable");
        }

        @Test
        @DisplayName("the tool's own fingerprints are retained but are not the identity")
        void toolFingerprintsAreEvidenceNotIdentity() throws IOException {
            var injection = byRule(parse("codeql-2.15.4.sarif"), "java/sql-injection");
            assertEquals("b47fd1e2c9a0:1",
                    injection.partialFingerprints().get("primaryLocationLineHash"));
            // The structural hash is DERIVED. If the tool's line hash were adopted as identity, a
            // reformat would change it and produce a second finding — which is exactly what DOC-16 §7.1
            // forbids.
            assertNotEquals("b47fd1e2c9a0:1", injection.structuralContextHash());
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("mobsfscan 0.3.6")
    class Mobsfscan {

        @Test
        @DisplayName("no snippet anywhere, and identity still works")
        void identityWithoutSnippets() throws IOException {
            SarifParser.Parsed parsed = parse("mobsfscan-0.3.6.sarif");
            assertEquals(3, parsed.results().size());
            for (SarifParser.Result result : parsed.results()) {
                assertNull(result.snippet(), "mobsfscan emits no snippet");
                assertNull(result.enclosingConstruct(), "and no logical location");
                assertFalse(result.structuralContextHash().isBlank(),
                        "the fallback still produces one, or the finding would have no identity");
            }
            // Two rules in the same file must not collapse into one finding. With no snippet and no
            // construct, the line is the only discriminator left — admitted in the fallback for exactly
            // this case, and never as a fingerprint input in its own right.
            var webview = byRule(parsed, "android_webview_debug");
            var hardcoded = byRule(parsed, "android_kotlin_hardcoded");
            assertNotEquals(webview.structuralContextHash(), hardcoded.structuralContextHash());
        }

        @Test
        @DisplayName("a result with no location is held, and the held field is named")
        void missingLocationIsQuarantined() throws IOException {
            SarifParser.Parsed parsed = parse("mobsfscan-0.3.6.sarif");
            assertEquals(1, parsed.quarantined().size());
            SarifParser.Held held = parsed.quarantined().get(0);
            assertEquals(QuarantinedRecord.Reason.SCHEMA_VALIDATION, held.reason());
            assertEquals(List.of("locations[0].physicalLocation.artifactLocation.uri"),
                    held.failingFields(),
                    "'failed validation' is not correctable, so the field has to be named");
            assertTrue(held.rawContent().contains("android_missing_location_detail"),
                    "the raw content is retained so the record is correctable without the source file");
            // Held, not dropped: the other three are ingested. One bad record must not discard the file.
            assertEquals(3, parsed.results().size());
        }

        @Test
        @DisplayName("severity comes from the rule's default level where the tool sets no security-severity")
        void severityFromLevel() throws IOException {
            SarifParser.Parsed parsed = parse("mobsfscan-0.3.6.sarif");
            assertEquals(Integer.valueOf(3), byRule(parsed, "android_certificate_transparency")
                    .severityOrdinal(), "warning maps to the third ordinal");
            assertEquals(Integer.valueOf(2), byRule(parsed, "android_kotlin_hardcoded").severityOrdinal(),
                    "error maps to the second");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("nuclei 3.1.0 — a runtime target is refused rather than misfiled")
    class Nuclei {

        @Test
        @DisplayName("every result is held, because a URL is not a code location")
        void runtimeTargetsAreHeld() throws IOException {
            SarifParser.Parsed parsed = parse("nuclei-3.1.0.sarif");

            // NOT a defect in this fixture and not a parse failure. nuclei is a DAST tool: its locations
            // are live URLs, and this parser produces CODE findings whose identity is (rule, asset,
            // normalized code location, structural context). Pushing a URL through that reduces
            // "https://booking.example.vn/.git/config" to the basename "config" and files it as static
            // analysis over code — a finding whose class, location and dedup behaviour are all wrong in a
            // way that looks right in a list.
            //
            // So the results are held with the reason named, the submitter is told, and nothing is
            // silently misfiled. Ingesting them needs the RUNTIME identity path, which is blocked on a
            // corpus decision rather than on code: the RUNTIME class declares `parameter_name` as an
            // identity input, a template match against a URL has no parameter, and the fingerprint
            // builder refuses a declared input that is absent while PRD-ING-021 forbids substituting "".
            //
            // This test asserts the honest behaviour. It is expected to CHANGE when that decision is
            // taken, and changing it will be a deliberate act with the decision recorded beside it.
            assertEquals(0, parsed.results().size(), "nothing is ingested as a code finding");
            assertEquals(2, parsed.quarantined().size(), "both results are held, neither is dropped");
            for (SarifParser.Held held : parsed.quarantined()) {
                assertEquals(QuarantinedRecord.Reason.SCHEMA_VALIDATION, held.reason());
                assertTrue(held.failingFields().get(0).contains("a runtime target"),
                        "the reason says WHY, so the submitter can act on it: " + held.failingFields());
            }
            assertEquals(java.util.Set.of("nuclei"), parsed.toolNames(),
                    "the tool is still recorded, so the session says which pipeline this was");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Identity, and the two ways it can be wrong")
    class Identity {

        private static Map<String, Object> oneResult(String snippet, int startLine) {
            return oneResult(snippet, startLine, "src/pay/Charge.java");
        }

        /** One result, one rule, one file — the smallest document that has an identity to assert. */
        private static Map<String, Object> oneResult(String snippet, int startLine, String uri) {
            Map<String, Object> region = Map.of("startLine", Integer.valueOf(startLine),
                    "snippet", Map.of("text", snippet));
            Map<String, Object> physical = Map.of(
                    "artifactLocation", Map.of("uri", uri), "region", region);
            Map<String, Object> result = Map.of(
                    "ruleId", "rule.a",
                    "message", Map.of("text", "found"),
                    "locations", List.of(Map.of("physicalLocation", physical)));
            Map<String, Object> driver = Map.of("name", "semgrep",
                    "rules", List.of(Map.of("id", "rule.a",
                            "defaultConfiguration", Map.of("level", "error"))));
            Map<String, Object> run = Map.of("tool", Map.of("driver", driver),
                    "results", List.of(result));
            return Map.of("version", "2.1.0", "runs", List.of(run));
        }

        @Test
        @DisplayName("DOC-16 §7.1: code reformatted and line numbers shifted is the SAME finding")
        void reformattingDoesNotChangeIdentity() {
            var before = SarifParser.parse(oneResult("if (amount>0) { charge(amount); }", 40), 4096);
            var after = SarifParser.parse(oneResult("if ( amount > 0 ) {\n    charge( amount );\n}", 91),
                    4096);

            // Same rule, same file, same code with different whitespace and a different line. The
            // fingerprint inputs are the normalized location and the structural hash, and both must be
            // unchanged — otherwise a reformatting commit produces a new finding, which destroys the
            // triage state of everything it touched for a change that altered nothing.
            assertEquals(before.results().get(0).normalizedLocation(),
                    after.results().get(0).normalizedLocation());
            assertEquals(before.results().get(0).structuralContextHash(),
                    after.results().get(0).structuralContextHash());
        }

        @Test
        @DisplayName("a file moved within the repository is the same finding; different code is not")
        void locationIsTheBasename() {
            String code = "if (amount>0) { charge(amount); }";
            var original = SarifParser.parse(oneResult(code, 40), 4096);
            var moved = SarifParser.parse(
                    oneResult(code, 40, "services/pay/legacy/src/Charge.java"), 4096);

            assertEquals(original.results().get(0).normalizedLocation(),
                    moved.results().get(0).normalizedLocation(),
                    "DOC-16 §7.1: a file moved or renamed within the asset stays the same finding");
            assertEquals(original.results().get(0).structuralContextHash(),
                    moved.results().get(0).structuralContextHash());

            var different = SarifParser.parse(oneResult("charge(amount * 2)", 40), 4096);
            assertNotEquals(original.results().get(0).structuralContextHash(),
                    different.results().get(0).structuralContextHash(),
                    "different code in the same place is a different weakness; collapsing them would "
                            + "make fixing one appear to fix both");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Whole-document rejection")
    class WholeDocument {

        @Test
        @DisplayName("PRD-ING-027: an unsupported version is refused, with the supported ones named")
        void unsupportedVersion() {
            var rejected = assertThrows(SarifParser.Rejected.class, () -> SarifParser.parse(
                    Map.of("version", "2.2.0", "runs", List.of()), 128));
            assertEquals("UNSUPPORTED_FORMAT_VERSION", rejected.code());
            assertTrue(rejected.getMessage().contains("2.1.0"),
                    "a rejection that does not name what IS supported is a dead end: "
                            + rejected.getMessage());
        }

        @Test
        @DisplayName("an errata revision of 2.1.0 in $schema is that version")
        void schemaCarriesTheVersion() {
            var parsed = SarifParser.parse(Map.of(
                    "$schema", "https://json.schemastore.org/sarif-2.1.0-rtm.5.json",
                    "runs", List.of()), 128);
            assertEquals(0, parsed.resultCount(),
                    "a scan that ran and found nothing is a real answer, not an error");
        }

        @Test
        @DisplayName("a document that declares no version at all is refused, never assumed")
        void undeclaredVersion() {
            var rejected = assertThrows(SarifParser.Rejected.class,
                    () -> SarifParser.parse(Map.of("runs", List.of()), 128));
            assertEquals("SCHEMA_VALIDATION", rejected.code());
        }

        @Test
        @DisplayName("no runs key is refused; an empty runs array is an empty scan")
        void runsMustBePresent() {
            var rejected = assertThrows(SarifParser.Rejected.class,
                    () -> SarifParser.parse(Map.of("version", "2.1.0"), 128));
            assertEquals("SCHEMA_VALIDATION", rejected.code());
            // The distinction matters: absent means the document cannot be vouched for, empty means the
            // scan ran and found nothing — measured-and-clean, which product principle 1 requires to be
            // distinguishable from not-measured.
            assertEquals(0, SarifParser.parse(Map.of("version", "2.1.0", "runs", List.of()), 128)
                    .resultCount());
        }

        @Test
        @DisplayName("a document over the declared size limit is refused whole, never truncated")
        void oversizeIsRefused() {
            var rejected = assertThrows(SarifParser.Rejected.class, () -> SarifParser.parse(
                    Map.of("version", "2.1.0", "runs", List.of()),
                    SarifParser.definition().limits().maxDocumentBytes() + 1));
            assertEquals("DOCUMENT_TOO_LARGE", rejected.code());
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Test
    @DisplayName("the registry has one parser per format and version")
    void theRegistryIsUnambiguous() {
        assertTrue(aspm.module.ingestion.domain.ParserDefinition
                        .validateRegistry(java.util.Set.of(SarifParser.definition())).isEmpty(),
                "two parsers claiming one format and version would make parser resolution depend on "
                        + "registry iteration order");
    }
}
