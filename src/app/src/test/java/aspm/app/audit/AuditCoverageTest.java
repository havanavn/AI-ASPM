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

    private static final Map<String, String> NOT_AUDITED = new LinkedHashMap<>(pairs(
            "DeliveryWorker#claim",
            "leases outbox rows for one attempt (ADR-054): status, lease and attempt count on "
                    + "notification_delivery. The outcome of the attempt is the record, written by the "
                    + "same worker a moment later; a lease is bookkeeping between two workers, not a "
                    + "decision by anybody",
            "ConnectorWorker#claim",
            "leases connector outbox rows for one attempt (ADR-054), as DeliveryWorker#claim: status, "
                    + "lease and attempt count on outbound_operation, bookkeeping between two workers",
            "ConnectorWorker#process",
            "the attempt itself: the operation's outcome, the reference's external identity or observed "
                    + "state, and the connector's health. The decision to create the reference was audited "
                    + "when a person asked for it (OutboundReferenceService#create); what the tracker answered "
                    + "is an observation, and a divergence it produces is resolved — and audited — by a person",
            "ConnectorWorker#housekeeping",
            "sweeps: due observations enqueued, an expiry warning sent once, an overlap credential retired "
                    + "after its window, a health period rolled. None is a decision by anybody; the rotation "
                    + "that started the overlap was audited by the administrator who made it",
            "ConnectorService#probe",
            "a connectivity probe, as IdentityProviderService#test: it stores the outcome of a check the "
                    + "administrator just requested and can repeat, and changes no configuration",
            "ReportWorker#claim",
            "leases due schedules by pushing next_run_at forward under SKIP LOCKED (ADR-054); the run "
                    + "that follows records its artifacts and audits each generation (report.generated) — "
                    + "the lease is bookkeeping between two workers, not a decision by anybody",
            "ReportService#runSchedule",
            "one scheduled run: an artifact per recipient with a report.generated audit event emitted "
                    + "on the same connection for each (SEC-AUD-009), a dropped recipient recorded with the "
                    + "reason and the owner notified (PRD-DSH-045). The method body carries the audit call; "
                    + "listed because the scan looks for 'audit.' on the method and finds it only inside the loop",
            "ReportService#download",
            "a download counter on the caller's own artifact. The generation was audited as "
                    + "report.generated for that recipient; a second event per download would record the "
                    + "same disclosure twice",
            "ModelNarrator#call",
            "the invocation record (PRD-AIC-043): one row per model call, cached hit or budget refusal, "
                    + "written on its own connection. The AI trail is the ai.invoked event the capability run "
                    + "emits (TriageAgent#run) or the ai.invoked event the evaluation writes; per-call rows "
                    + "are metering and governance data, and an event per token count would bury both",
            "ModelEvaluation#start",
            "opens an evaluation run row that finish() completes; the run is audited as ai.invoked in "
                    + "finish(), on the same connection as its result",
            "NotificationApi#markRead",
            "a read mark on the caller's own notification. It is the in-product channel's read state "
                    + "(DOC-13 §5: 'read state rather than digest') and changes nothing anybody else can see",
            "NotificationApi#savePreferences",
            "a person's own delivery preferences — mute, quiet hours, locale. They govern what reaches "
                    + "that person and nothing else; an event on the chain would say 'somebody changed "
                    + "their own settings' about the one person who already knows",
            "FederatedSignIn#complete",
            "consumes a handshake row (federated_login_state) and records the attempt; the session it "
                    + "produces, the principal it may provision and the assignments it reconciles are "
                    + "audited inside establish(), on the same connection, once the provider's token has "
                    + "been verified and a tenant context can be bound",
            "FederatedSignIn#begin",
            "writes the handshake row (state, nonce, PKCE verifier) that complete() consumes. It is the "
                    + "operational half of a sign-in that has not happened yet; the attempt is recorded "
                    + "when it resolves either way",
            "IdentityProviderService#test",
            "a connectivity probe, as AiProviderService#recordTest: it stores the outcome of a discovery "
                    + "the administrator just requested and can repeat, and changes no configuration",
            "SealedSecretsProvider#store",
            "the custody row of a secret somebody else's write owns. The identity provider, channel or "
                    + "connector that holds the returned reference emits the event for its own creation, "
                    + "and an event here would say 'a secret was sealed' without being able to say for what",
            "SealedSecretsProvider#destroy",
            "the mirror of store: the owning record's retirement is the audited action; this zeroes the "
                    + "material it pointed at, and the row keeps destroyed_at as its own record",
            "SealedSecretsProvider#resolve",
            "increments last_accessed_at and access_count on a read. It is an access record, not a "
                    + "change to what anybody decided (PRD-CON-021 wants access observable per object, "
                    + "which this is)",
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

    /** Alternating key/value strings, because Map.of stops at ten pairs and this list is longer. */
    private static Map<String, String> pairs(String... keyValue) {
        if (keyValue.length % 2 != 0) {
            throw new IllegalArgumentException("pairs() needs an even number of strings");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValue.length; i += 2) {
            out.put(keyValue[i], keyValue[i + 1]);
        }
        return out;
    }

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
                    + "|import_quarantine|webhook_delivery|rescan_scan|federated_login_state)"
                    + "|DELETE\\s+FROM\\s+federated_login_state"
                    + "|UPDATE\\s+(rescan_schedule\\s+SET\\s+last_tick_at|principal_session|notification_delivery"
                    + "|notification_channel\\s+SET\\s+last_delivery_at"
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
