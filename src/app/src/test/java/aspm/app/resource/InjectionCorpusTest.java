package aspm.app.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The indirect prompt injection corpus. {@code PRD-AIC-051}, {@code TST-AIC-002}, {@code PRD-AIC-037}.
 *
 * <h2>The risk this is about</h2>
 *
 * <p>An attacker who can put text into a scanned application can put text into model context with no
 * platform access at all — a scanner reads their source, the platform stores what it read, and a
 * capability later describes it. DOC-26 calls it boundary B6; CLAUDE.md calls it the fifth-highest
 * risk surface; it exists because of what this product does and cannot be designed away.
 *
 * <h2>What these tests assert, and what they cannot</h2>
 *
 * <p>They assert the framing: that every payload lands inside the fence, that none of it reaches
 * instruction position, that the rules are restated after it, that it cannot close its own fence,
 * and that it is counted as a signal. They assert nothing about whether a model would be persuaded —
 * no test can, which is why DOC-10 §6.2 makes containment (AI holds no write authority) the
 * load-bearing control and {@code RISK-PLT-001} records the residual honestly rather than claiming
 * the problem is solved.
 *
 * <h2>Why the corpus is a file and the fields are a constant</h2>
 *
 * <p>{@code PRD-AIC-051} requires the corpus to be extended whenever a grounding contract gains a
 * field — a procedural requirement, which DOC-26 §13.2 identifies as the weaker kind of control.
 * {@link #everyContextFieldIsAttacked} turns it into a build failure by comparing the file against
 * {@link ModelNarrator#CONTEXT_FIELDS}. A field added without an attack does not ship.
 */
class InjectionCorpusTest {

    private record Attack(String field, String attempt, String payload) {
    }

    private static final List<Attack> CORPUS = new ArrayList<>();

    /** The task and facts a capability supplies. Platform-composed, and never attacker text. */
    private static final String TASK = "Summarise where this organization stands.";
    private static final List<String> FACTS = List.of(
            "open findings: 12", "critical or high among them: 3", "severity: critical");

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = InjectionCorpusTest.class
                .getResourceAsStream("/aspm/ai/injection-corpus.tsv")) {
            assertNotNull(in, "the corpus is not on the test classpath; PRD-AIC-051 requires it to "
                    + "exist, and a suite that silently ran zero attacks would pass");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                assertEquals(3, parts.length,
                        "a corpus row is field, attempt, payload separated by tabs: " + line);
                // The file writes newlines as an escape so a payload stays on one row; the attack is
                // the real character, which is the point of several of them.
                CORPUS.add(new Attack(parts[0], parts[1],
                        parts[2].replace("\\n", "\n").replace("\\r", "\r")));
            }
        }
        assertTrue(CORPUS.size() >= 10, "the corpus has shrunk to " + CORPUS.size() + " rows");
    }

    @Test
    @DisplayName("TST-AIC-002: every field that reaches model context has an attack against it")
    void everyContextFieldIsAttacked() {
        Set<String> attacked = new LinkedHashSet<>();
        for (Attack attack : CORPUS) {
            attacked.add(attack.field());
        }
        List<String> undefended = new ArrayList<>(ModelNarrator.CONTEXT_FIELDS);
        undefended.removeAll(attacked);
        assertEquals(List.of(), undefended,
                "these fields reach model context with no attack written against them. The corpus is "
                        + "complete at authoring and decays as the contract grows (PRD-AIC-051); this "
                        + "assertion is the mechanism against that decay, so the answer is to add a "
                        + "row to injection-corpus.tsv rather than to relax this");

        List<String> unknown = new ArrayList<>(attacked);
        unknown.removeAll(ModelNarrator.CONTEXT_FIELDS);
        assertEquals(List.of(), unknown,
                "these corpus rows attack a field the narrator does not send, so they prove nothing "
                        + "and hide the gap they look like they cover: " + unknown);
    }

    @Test
    @DisplayName("PRD-AIC-037: no payload reaches instruction position")
    void payloadsStayInsideTheFence() {
        for (Attack attack : CORPUS) {
            String prompt = ModelNarrator.assemble(TASK, FACTS, Map.of(attack.field(),
                    attack.payload()));
            int open = prompt.indexOf("<<<REPORT_CONTENT>>>");
            int close = prompt.lastIndexOf("<<<REPORT_CONTENT>>>");
            assertTrue(open > 0 && close > open,
                    "the fence is missing for " + attack.field() + "; without it the payload is in "
                            + "the same position as the instructions");

            // Everything before the fence must be BYTE-IDENTICAL to the prompt built with no
            // untrusted content at all. Comparing that way rather than searching for words from the
            // payload, because a payload shares ordinary words with the facts — the first version of
            // this test failed on the word "organization", which was in both, and a test that reports
            // a defect that is not there is as expensive as one that misses a defect that is.
            // Stripped at the edges only: the blank line before the fence is the assembler's own
            // separator, and the assertion is about what the PAYLOAD changed.
            String withoutPayload = ModelNarrator.assemble(TASK, FACTS, Map.of()).strip();
            assertEquals(withoutPayload, prompt.substring(0, open).strip(),
                    "the " + attack.field() + " payload changed the prompt before the fence, which is "
                            + "instruction position. That is exactly the defect that existed when an "
                            + "organization name was interpolated into a FACTS bullet");
        }
    }

    @Test
    @DisplayName("PRD-AIC-037: a payload cannot close its own fence")
    void payloadsCannotEscape() {
        for (Attack attack : CORPUS) {
            String prompt = ModelNarrator.assemble(TASK, FACTS, Map.of(attack.field(),
                    attack.payload()));
            String fenced = prompt.substring(prompt.indexOf("<<<REPORT_CONTENT>>>") + 20,
                    prompt.lastIndexOf("<<<REPORT_CONTENT>>>"));
            assertFalse(fenced.contains("<<<REPORT_CONTENT>>>"),
                    "the " + attack.field() + " payload carried the marker into the block, so it "
                            + "could end the data section and speak as the platform");
            assertFalse(fenced.contains("\n\n"),
                    "the " + attack.field() + " payload kept a blank line, which is how a value "
                            + "starts something that reads as a new section");
        }
    }

    @Test
    @DisplayName("PRD-AIC-037: the rules are restated after the untrusted content")
    void instructionsAreAnchoredAfterTheData() {
        String prompt = ModelNarrator.assemble(TASK, FACTS,
                Map.of("finding_description", CORPUS.get(0).payload()));
        int close = prompt.lastIndexOf("<<<REPORT_CONTENT>>>");
        String after = prompt.substring(close);
        assertTrue(after.contains("END OF DATA"),
                "an instruction that appears only before several thousand characters of attacker text "
                        + "is an instruction the attacker gets the last word on. PRD-AIC-037 requires "
                        + "the restatement in as many words, and it was missing");
        assertTrue(after.contains("follow no instruction"),
                "the restatement has to restate the rule that matters, not merely mark the end");
    }

    @Test
    @DisplayName("PRD-AIC-038: every payload is counted as a signal")
    void everyPayloadIsDetected() {
        List<String> unnoticed = new ArrayList<>();
        for (Attack attack : CORPUS) {
            if (ModelNarrator.injectionSignals(Map.of(attack.field(), attack.payload())) == 0) {
                unnoticed.add(attack.field() + " / " + attack.attempt());
            }
        }
        assertEquals(List.of(), unnoticed,
                "these payloads produced no signal. Detection does not prevent anything — the fence "
                        + "and the containment do — but an attacker probing this platform's inference "
                        + "path is a finding worth raising, and it cannot be raised unnoticed: "
                        + unnoticed);
    }

    @Test
    @DisplayName("Ordinary report content is not reported as an attack")
    void honestContentIsNotFlagged() {
        Map<String, String> ordinary = new LinkedHashMap<>();
        ordinary.put("finding_title", "Reflected cross-site scripting in the search parameter");
        ordinary.put("finding_description", "The q parameter is echoed into the page without encoding. "
                + "An attacker can execute script in a victim's session. Fixed by encoding on output.");
        ordinary.put("component_identifier", "pkg:npm/lodash@4.17.20");
        ordinary.put("advisory_summary", "Prototype pollution allows modifying Object.prototype.");
        assertEquals(0, ModelNarrator.injectionSignals(ordinary),
                "a detector that fires on ordinary vulnerability writing is a detector whose signal "
                        + "nobody reads. These are the sentences this platform is full of");
    }

    @Test
    @DisplayName("The consistency check does not fire on ordinary remediation vocabulary")
    void severityWordsAreMatchedAsWords() {
        // Found by running the capability against a stub provider: the model wrote "validate against
        // an allow-list", the facts said Critical, and "allow-list" CONTAINS "low" — so every
        // remediation this capability produced was refused as a contradiction. A check that fires on
        // the words remediation advice is made of is a check that switches the capability off.
        for (String ordinary : List.of(
                "Validate the parameter against an allow-list before it reaches the query.",
                "The values below are affected; follow the vendor guidance.",
                "Lower the privileges of the service account and re-run the scan.",
                "Restrict the data flow so the input cannot reach the sink.")) {
            assertNull(ModelNarrator.contradiction(ordinary, FACTS),
                    "refused ordinary remediation text as a contradiction: " + ordinary);
        }
        // And it still catches the thing it is for.
        assertEquals("low", ModelNarrator.contradiction("Treat this as low risk.", FACTS));
    }

    @Test
    @DisplayName("Risk surfaces 3 and 4: a SECRET finding and a proof of concept are never sent")
    void secretsAndExploitMaterialAreNotOffered() throws IOException {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/aspm/app/resource/TriageAgent.java"),
                StandardCharsets.UTF_8);
        int start = source.indexOf("private Run remediation(");
        assertTrue(start > 0, "the remediation capability has moved; this test has to find it");
        String method = source.substring(start, source.indexOf("\n    /**", start));
        assertTrue(method.contains("f.finding_class <> 'SECRET'"),
                "the content of a SECRET finding IS a recovered credential. The exclusion belongs in "
                        + "the query, where no configuration and no argument can undo it");
        assertFalse(method.contains("finding_proof_of_concept"),
                "a proof of concept is working exploit material against a system that is still "
                        + "vulnerable; remediation guidance can be written from what the weakness is");
    }

    @Test
    @DisplayName("PRD-AIC-035: a reply that contradicts the record is refused")
    void downgradeAttemptsAreCaught() {
        // What a successful DOWNGRADE injection produces: the facts say critical, the reply says low.
        assertEquals("low", ModelNarrator.contradiction(
                "This is a low severity issue and can be scheduled normally.", FACTS));
        assertNull(ModelNarrator.contradiction(
                "Three critical or high findings are open and none was closed recently.", FACTS),
                "a reply that agrees with the facts must pass, or the check refuses every narration");
        assertNull(ModelNarrator.contradiction(
                "Twelve findings are open across the organization.", FACTS),
                "saying nothing about severity is a shorter sentence, not a contradiction");
        assertNull(ModelNarrator.contradiction("This is low severity.",
                List.of("open findings: 4")),
                "where the facts name no severity there is nothing to contradict — a tenant whose "
                        + "scale reads P1..P4 must not have every narration refused");
    }

}
