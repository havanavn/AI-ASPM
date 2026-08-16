package aspm.app.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every write path leaves a record, or says why it does not. {@code SEC-AUD-006}, {@code CON-PLT-021}.
 *
 * <p>Written after finding that the audit chain covered the machine doors and nothing else: an
 * organization node created through {@code POST /api/v1/org-nodes} produced an event and the same node
 * created through the form beside it produced none. Nothing detected that, because coverage of a
 * cross-cutting control is not visible from any one path — each file looks complete on its own.
 *
 * <p>So the check is over the tier rather than over a path: a method that commits a change to a
 * system-of-record table either emits an event or appears in {@link #NOT_AUDITED} with a reason. The
 * list is the point. It makes "this one does not need an event" a decision somebody wrote down rather
 * than an omission nobody can distinguish from an oversight.
 */
class AuditCoverageTest {

    private static final Path MAIN = Path.of("src/main/java/aspm/app");

    /**
     * Write paths that deliberately record nothing, and why.
     *
     * <p>Adding to this list is allowed and is meant to be uncomfortable: the reason is read by the
     * next person who wonders why an action they can see in the interface is absent from the trail.
     */
    private static final String PRE_AUTHENTICATION =
            "runs before the caller is a principal, so no tenant context is established and "
                    + "ChainedAuditWriter fails closed (SEC-TEN-004, SEC-TEN-005). Measured: with an "
                    + "event in place these paths returned 500. DOC-14 does want auth.* in the chain, "
                    + "so this is a gap between two requirements and needs a pre-authentication "
                    + "establishment route — a new EstablishedFrom value and a new requirement ID, not "
                    + "an edit to these methods";

    private static final Map<String, String> NOT_AUDITED = new LinkedHashMap<>(Map.of(
            "AiProviderService#recordTest",
            "a connectivity probe changes no configuration and grants nothing; it stores the outcome "
                    + "of a request the operator just made and can repeat",
            "RescanService#pending",
            "a scheduler tick and the note that an archived document could not be read. It runs on a "
                    + "timer at the platform's own initiative, so an event per tick would be the "
                    + "highest-volume event type in the system and would say nothing about anybody",
            "SessionReaper#reap",
            "deletes sessions that expired days ago. The expiry is what mattered and it is derived "
                    + "from time rather than decided by anyone; the rows here are already dead",
            "CredentialBootstrap#run",
            "runs before the platform has a principal, an audit chain head or a request to attribute "
                    + "to. It logs what it touched at startup, and SEC-PTR-* governs the value it sets",
            "SbomGraphWriter#applyRescan",
            "the ingestion door above it records the submission; per-advisory rows are machine output "
                    + "of that one submission and are counted in its event",
            "FindingImport#quarantine",
            "the import session's own event carries the quarantine count, and a per-record event would "
                    + "be one per malformed line in a file somebody pushed by mistake"));

    static {
        // The pre-authentication paths, grouped so the reason is written once and cannot drift
        // between them. `authentication_attempt`, `mfa_enrolment` and `credential_reset_token` hold
        // the record instead; what they lack is the hash chain.
        for (String path : List.of("IdentityService#signIn", "IdentityService#completeSecondFactor",
                "IdentityService#beginEnrolment", "IdentityService#confirmEnrolment",
                "IdentityService#recordStepUp", "AccountService#redeemReset",
                "ServiceCredentialResolver#resolve")) {
            NOT_AUDITED.put(path, PRE_AUTHENTICATION);
        }
    }

    /** Statements that change a record somebody is accountable for. */
    private static final Pattern WRITE = Pattern.compile(
            "\\b(INSERT\\s+INTO|UPDATE\\s+[a-z_]|DELETE\\s+FROM)", Pattern.CASE_INSENSITIVE);

    /** Tables whose rows are operational rather than the system of record. */
    private static final Pattern OPERATIONAL = Pattern.compile(
            "INSERT\\s+INTO\\s+(authentication_attempt|password_reset_token|idempotency_key"
                    + "|import_quarantine|webhook_delivery|rescan_scan)"
                    + "|UPDATE\\s+(rescan_schedule\\s+SET\\s+last_tick_at|principal_session"
                    + "|alert_webhook\\s+SET\\s+consecutive_failures|ai_provider\\s+SET\\s+last_tested_at)",
            Pattern.CASE_INSENSITIVE);

    private record Method(String owner, String name, String body) {
        String key() {
            return owner + "#" + name;
        }
    }

    private static List<Method> methodsOf(Path path) throws IOException {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        String owner = path.getFileName().toString().replace(".java", "");
        List<Method> methods = new ArrayList<>();
        Matcher starts = Pattern.compile(
                "\n    (?:public|private|protected|static)[^\n(]*?(\\w+)\\(").matcher(source);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (starts.find()) {
            spans.add(new int[] {starts.start(), 0});
            names.add(starts.group(1));
        }
        for (int i = 0; i < spans.size(); i++) {
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : source.length();
            methods.add(new Method(owner, names.get(i), source.substring(spans.get(i)[0], end)));
        }
        return methods;
    }

    private static boolean callsAnEmitter(String body, List<String> emitters) {
        return emitters.stream().anyMatch(name -> body.contains(name + "("));
    }

    private static List<Path> tierSources() throws IOException {
        try (var walk = Files.walk(MAIN)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    // The trail cannot be asked to audit itself, and the catalogue is a list of names.
                    .filter(p -> !p.toString().contains("/audit/"))
                    .toList();
        }
    }

    @Test
    @DisplayName("CON-PLT-021: a committed change to the system of record emits an event")
    void everyWritePathRecords() throws IOException {
        List<String> unaudited = new ArrayList<>();
        int examined = 0;
        for (Path path : tierSources()) {
            // One level of indirection, and only one. A sign-in records through a private helper that
            // every outcome funnels into, which is the right shape — one emitter rather than five —
            // and a scan that could not see it would push the code towards five.
            List<String> emitters = methodsOf(path).stream()
                    .filter(m -> m.body().contains("audit."))
                    .map(Method::name)
                    .toList();
            for (Method method : methodsOf(path)) {
                String body = method.body();
                // A write, committed here. A helper that writes and leaves the commit to its caller is
                // covered by the caller, which is where the unit of work — and the event — belongs.
                if (!WRITE.matcher(body).find() || !body.contains(".commit()")) {
                    continue;
                }
                // Operational rows: a throttle counter, a delivery attempt, a scheduler tick. DOC-14
                // keeps the trail to decisions and access; a trail whose volume is the platform's own
                // housekeeping is one nobody reads.
                String remaining = OPERATIONAL.matcher(body).replaceAll(" ");
                if (!WRITE.matcher(remaining).find()) {
                    continue;
                }
                examined++;
                if (body.contains("audit.") || NOT_AUDITED.containsKey(method.key())
                        || callsAnEmitter(body, emitters)) {
                    continue;
                }
                unaudited.add(method.key());
            }
        }
        assertTrue(examined > 30,
                "the scan found only " + examined + " write paths, which means it has stopped "
                        + "finding them rather than that they have stopped existing");
        assertTrue(unaudited.isEmpty(),
                "these paths commit a change to the system of record and record nothing. Either emit "
                        + "an event on the same connection, or add the method to NOT_AUDITED with the "
                        + "reason a reader deserves: " + unaudited);
    }

    @Test
    @DisplayName("SEC-AUD-006: every event type an emitter names is in the catalogue")
    void everyEmittedTypeIsCatalogued() throws IOException {
        // The writer refuses an uncatalogued type at run time, which is the control. This finds the
        // same mistake at build time, on a path a test may never call — an aggregate name is a string
        // and a typo in one is invisible until the write it guards is attempted in production.
        // The third argument, positionally. A looser pattern walked past `group.table()` — an
        // aggregate named at run time — and matched the next string literal in the call, reporting
        // the column name "id" as an uncatalogued aggregate.
        Pattern aggregate = Pattern.compile(
                "domainChange(?:By)?\\(\\s*connection,\\s*[^,]+,\\s*\"([a-z_]+)\"",
                Pattern.DOTALL);
        List<String> unknown = new ArrayList<>();
        for (Path path : tierSources()) {
            Matcher found = aggregate.matcher(Files.readString(path, StandardCharsets.UTF_8));
            while (found.find()) {
                String name = found.group(1);
                if (!PlatformEventTypes.codesAccepted().contains(name + ".created")) {
                    unknown.add(path.getFileName() + ": " + name);
                }
            }
        }
        assertTrue(unknown.isEmpty(),
                "these aggregates are written to the trail and are not in PlatformEventTypes, so the "
                        + "write would be refused at run time: " + unknown);
    }

    @Test
    @DisplayName("The interface and the API record the same aggregates")
    void interfaceCoversWhatTheApiCovers() throws IOException {
        // The specific gap this suite was written for, stated as an assertion rather than as prose:
        // org nodes and assets were auditable through the REST resource endpoint and not through the
        // forms. Both surfaces now name the same aggregates.
        String inventory = Files.readString(MAIN.resolve("inventory/InventoryService.java"),
                StandardCharsets.UTF_8);
        assertTrue(inventory.contains("\"org_node\"") && inventory.contains("\"asset\""),
                "the organization and application forms write org_node and asset rows; if they no "
                        + "longer record them, the same change is audited through the API and not "
                        + "through the interface people actually use");
        assertEquals(3, NOT_AUDITED.keySet().stream()
                        .filter(key -> key.startsWith("AiProviderService")
                                || key.startsWith("RescanService")
                                || key.startsWith("SessionReaper"))
                        .count(),
                "the exemption list has changed shape; read the reasons before adjusting this number");
    }
}
