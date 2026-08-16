package aspm.deployment;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The fifteen runbooks of DOC-15 §15. {@code OPS-DEP-049}, {@code OPS-DEP-050}.
 *
 * <p>"Required before production. Each states detection, immediate action, diagnosis, remediation, and the audit
 * record produced."
 *
 * <p>Five sections, all five required. A runbook missing its detection section is a procedure nobody starts; one
 * missing its audit record is an incident with no account of what was done to the data during it — which for
 * break-glass and erasure is the part a regulator asks about.
 *
 * <h2>{@code OPS-DEP-050} is why the phases are ordered rather than named</h2>
 *
 * <p>"The cross-tenant assertion failure runbook MUST specify <b>immediate containment before diagnosis</b>. It
 * is the platform's most severe possible condition: one customer's vulnerability inventory disclosed to another,
 * unrecoverable and disclosable. Diagnosing before containing extends the exposure."
 *
 * <p>The instinct under an isolation alert is to find out whether it is real before doing anything disruptive,
 * because containment is disruptive and most alerts are false. That instinct is correct for almost every alert
 * in the platform and wrong for this one, and it is wrong in the direction that cannot be undone: every second
 * spent confirming is a second of continued disclosure, and the disclosure is of the exact material this
 * platform exists to protect. So the ordering is a property of the type: {@link Phase} is an ordered enum,
 * {@link #steps} is keyed by it, and {@link #assertContainmentPrecedesDiagnosis} refuses a highest-severity
 * runbook whose immediate action is investigative.
 *
 * <h2>{@code OPS-DEP-049} — an unrehearsed runbook is a document</h2>
 *
 * <p>"A runbook that has not been rehearsed MUST NOT be relied upon in a service level commitment. An unrehearsed
 * runbook is a document, and <b>the first execution under incident conditions is where its gaps are found</b>."
 *
 * <p>{@link #relyOnInServiceLevelCommitment} is the enforcement: it throws unless a rehearsal date is present.
 */
public record Runbook(String name, String trigger, Severity severity, Map<Phase, String> steps,
        Optional<LocalDate> rehearsedOn) {

    /** The five sections DOC-15 §15 requires, in the order they are executed. */
    public enum Phase {
        DETECTION,
        /** What is done before anything is understood. For a disclosure condition this is containment. */
        IMMEDIATE_ACTION,
        DIAGNOSIS,
        REMEDIATION,
        /** What the incident leaves behind in the record. */
        AUDIT_RECORD
    }

    public enum Severity {
        /** One condition holds this. Containment precedes diagnosis. */
        HIGHEST,
        HIGH,
        STANDARD
    }

    /** Words that mark an immediate action as investigative rather than containing. */
    private static final List<String> INVESTIGATIVE_VERBS = List.of(
            "investigate", "determine whether", "confirm whether", "assess whether", "establish whether",
            "review the logs", "reproduce", "triage");

    public Runbook {
        Objects.requireNonNull(name, "a runbook name is required");
        Objects.requireNonNull(trigger, "a trigger is required — a runbook nobody knows to open is unused");
        Objects.requireNonNull(severity, "a severity is required");
        Objects.requireNonNull(rehearsedOn, "pass an empty optional rather than null (OPS-DEP-049)");
        steps = Map.copyOf(Objects.requireNonNull(steps, "the five sections are required"));

        for (Phase phase : Phase.values()) {
            String step = steps.get(phase);
            if (step == null || step.isBlank()) {
                throw new IllegalArgumentException(
                        name + " has no " + phase + " section. DOC-15 section 15 requires all five: a runbook "
                                + "missing detection is a procedure nobody starts, and one missing its audit "
                                + "record is an incident with no account of what was done to the data during "
                                + "it.");
            }
        }
    }

    /**
     * {@code OPS-DEP-050}. Refuses a highest-severity runbook whose immediate action is investigative.
     *
     * <p>Deliberately a text check rather than a flag. A flag named {@code containsFirst} is set to true by
     * whoever writes the runbook, including the one who wrote "immediate action: investigate whether the
     * assertion is a false positive" — because they believe they are containing. The check reads what the
     * runbook actually instructs.
     */
    public void assertContainmentPrecedesDiagnosis() {
        if (severity != Severity.HIGHEST) {
            return;
        }
        String immediate = steps.get(Phase.IMMEDIATE_ACTION).toLowerCase(Locale.ROOT);
        for (String verb : INVESTIGATIVE_VERBS) {
            if (immediate.contains(verb)) {
                throw new IllegalArgumentException(
                        name + " begins with '" + verb + "', which is diagnosis. OPS-DEP-050 requires immediate "
                                + "containment first: one customer's vulnerability inventory disclosed to "
                                + "another is unrecoverable and disclosable, and every second spent confirming "
                                + "is a second of continued disclosure. The instinct to confirm before acting "
                                + "is correct for almost every other alert in the platform.");
            }
        }
    }

    /**
     * {@code OPS-DEP-049}.
     *
     * @throws IllegalStateException where the runbook has not been rehearsed. The service level is the thing
     *     being promised to a customer, and promising a recovery time on an unexecuted procedure is promising
     *     an estimate
     */
    public Runbook relyOnInServiceLevelCommitment() {
        if (rehearsedOn.isEmpty()) {
            throw new IllegalStateException(
                    name + " has not been rehearsed and cannot support a service level commitment "
                            + "(OPS-DEP-049). An unrehearsed runbook is a document, and the first execution "
                            + "under incident conditions is where its gaps are found.");
        }
        return this;
    }

    private static Map<Phase, String> phases(String detection, String immediate, String diagnosis,
            String remediation, String auditRecord) {
        Map<Phase, String> steps = new EnumMap<>(Phase.class);
        steps.put(Phase.DETECTION, detection);
        steps.put(Phase.IMMEDIATE_ACTION, immediate);
        steps.put(Phase.DIAGNOSIS, diagnosis);
        steps.put(Phase.REMEDIATION, remediation);
        steps.put(Phase.AUDIT_RECORD, auditRecord);
        return steps;
    }

    /**
     * The fifteen, in the order DOC-15 §15 tabulates them.
     *
     * <p>None carries a rehearsal date. That is not an omission: {@code OPS-DEP-049} requires rehearsal before
     * production, no rehearsal has happened, and recording a date here would assert one that did not. The empty
     * optional is what makes the debt countable — the same reason a skipped test is written and disabled rather
     * than left unwritten.
     */
    public static List<Runbook> all() {
        return List.of(
                new Runbook("Cross-tenant assertion failure",
                        "Continuous verification alert (SEC-TEN-047)",
                        Severity.HIGHEST,
                        phases(
                                "The continuous cross-tenant assertion of SEC-TEN-047 fails, or a "
                                        + "partition_runway_report row shows an isolation policy absent from a "
                                        + "partition created since the last check.",
                                "Contain first, before anything is known. Withdraw the affected instance from "
                                        + "the load balancer; revoke app_runtime's session tokens so no "
                                        + "in-flight request continues on a connection whose tenant context is "
                                        + "in doubt; drain the pool. The instance stays up and unreachable "
                                        + "rather than being restarted, because a restart destroys the session "
                                        + "state the diagnosis needs.",
                                "Identify the read path. Compare the failing assertion's tenant against the "
                                        + "connection's bound tenant (OPS-DEP-010), then against the row-level "
                                        + "policies on the table and on its partitions — a partition created "
                                        + "after the parent was isolated is the recurring case. Then the cache "
                                        + "keys: cache key construction is the recurring failure point in "
                                        + "otherwise correctly isolated systems.",
                                "Fix the path, add the assertion that would have caught it, and re-run the "
                                        + "full isolation suite before the instance returns. Determine from "
                                        + "the audit record what was read across the boundary and by whom; "
                                        + "that determination is the notification decision, and it is made on "
                                        + "the record rather than on an assessment of likelihood.",
                                "Every step, with timestamps, in the audit chain. The chain covers the payload "
                                        + "hash (ADR-034), so the record of an incident involving erasure "
                                        + "remains verifiable after the erasure."),
                        Optional.empty()),

                new Runbook("Audit chain verification failure", "OPS-DEP-045", Severity.HIGHEST,
                        phases(
                                "The verification job reports a broken link or a sequence gap.",
                                "Freeze the affected tenant's audit writes at the chain head rather than "
                                        + "letting them continue past a break: appending to a broken chain "
                                        + "makes the break's position ambiguous.",
                                "Locate the first failing sequence and compare against the last verified "
                                        + "checkpoint. Distinguish a storage fault from a write-path defect "
                                        + "from tampering — the three have different evidence and only the "
                                        + "third is an incident.",
                                "Restore from the checkpoint where the cause is storage; fix and re-anchor "
                                        + "where it is the write path. A tampering finding is escalated rather "
                                        + "than remediated by the operator who found it.",
                                "The verification output and the determination, both retained. The record of "
                                        + "what happened is inviolable (PP-5), and that applies most to the "
                                        + "record of the record failing."),
                        Optional.empty()),

                new Runbook("Break-glass request", "Support or incident need", Severity.HIGH,
                        phases(
                                "A support engineer requests access to tenant data they hold no scope for.",
                                "Grant nothing yet. Establish the requesting principal, the tenant, the "
                                        + "bounded reason, and the second approver before any grant exists.",
                                "Confirm the need cannot be met by the tenant's own administrator, which it "
                                        + "usually can.",
                                "Time-bounded grant, notified to the tenant, expiring without renewal. "
                                        + "Renewal is a new request rather than an extension, so the second "
                                        + "approval happens again.",
                                "The request, both approvals, the reason, every action taken under the grant, "
                                        + "and the expiry. Break-glass is the path an insider would use, so "
                                        + "the record is the control."),
                        Optional.empty()),

                new Runbook("Erasure request", "Data subject request", Severity.HIGH,
                        phases(
                                "A verified erasure request arrives for a data subject.",
                                "Identify the subject's records across every context before erasing any, "
                                        + "because a partial erasure is neither compliance nor a working "
                                        + "record.",
                                "Determine what is erasable and what is retained under a legal hold or a "
                                        + "statutory retention period, and record the basis for each "
                                        + "retention.",
                                "Erase the payload; retain the hash. ADR-034 puts the payload hash in the "
                                        + "chain so erasure leaves the chain verifiable — the record that an "
                                        + "event occurred survives the erasure of what it contained.",
                                "The request, the scope determined, what was erased, what was retained and "
                                        + "why. The audit entry names no erased content."),
                        Optional.empty()),

                new Runbook("Tenant provisioning, suspension, offboarding", "Commercial event", Severity.HIGH,
                        phases(
                                "A commercial event: signature, non-payment, or termination.",
                                "For suspension, revoke access before anything else and leave the data "
                                        + "intact — suspension is reversible and deletion is not, and the two "
                                        + "arrive through the same commercial channel.",
                                "Confirm the event and the tenant identity against the commercial record. An "
                                        + "offboarding executed against the wrong tenant is unrecoverable.",
                                "Provisioning creates the tenant key and the isolated schema. Offboarding is "
                                        + "cryptographic erasure by dual-controlled key destruction "
                                        + "(OPS-DEP-022), which makes deletion demonstrable rather than "
                                        + "asserted.",
                                "The commercial reference, both controllers for a destruction, and the "
                                        + "resulting attestation."),
                        Optional.empty()),

                new Runbook("Connector credential compromise", "Detection or notification", Severity.HIGH,
                        phases(
                                "A credential is detected in an unexpected location, or the third party "
                                        + "notifies a compromise.",
                                "Revoke at the third party first, not in the platform. Disabling the "
                                        + "connector stops the platform using the credential and does nothing "
                                        + "about anyone else who holds it.",
                                "Determine the exposure window from the connector's audit record and what "
                                        + "the credential could reach. Credential and secret concentration is "
                                        + "the platform's third highest-risk surface, so the question is what "
                                        + "else the same secret opens.",
                                "Rotate, re-provision through the secrets store, re-enable. The plaintext "
                                        + "credential appears in no exception message, log, or audit entry at "
                                        + "any point in this procedure.",
                                "The detection, the revocation time, the exposure window, and the rotation. "
                                        + "The credential itself is referenced by vault handle only."),
                        Optional.empty()),

                new Runbook("Key rotation and emergency key rotation", "Schedule or compromise", Severity.HIGH,
                        phases(
                                "Scheduled rotation interval, or a key-encryption-key compromise.",
                                "For an emergency rotation, stop issuing under the old key before rewrapping "
                                        + "anything, so the window does not extend while the rewrap runs.",
                                "Establish which data encryption keys were wrapped under the affected "
                                        + "key-encryption key. Per-tenant keys mean the blast radius is a list "
                                        + "of tenants rather than the estate.",
                                "Rewrap, verify a decrypt under the new wrapping for each affected tenant, "
                                        + "then destroy the old key material under dual control. Verify before "
                                        + "destroying: the order is the difference between a rotation and "
                                        + "data loss.",
                                "The rotation, the tenants affected, the verification result per tenant, and "
                                        + "the dual-control record for the destruction."),
                        Optional.empty()),

                new Runbook("Intelligence provisioning failure", "OPS-DEP-042", Severity.STANDARD,
                        phases(
                                "A bundle fails signature verification, or provisioning has not succeeded "
                                        + "within its freshness budget.",
                                "Retain the prior bundle and keep matching against it (OPS-DEP-039). A "
                                        + "verification failure must never leave the platform with no "
                                        + "intelligence.",
                                "Distinguish a transfer fault from a signature mismatch. The second is an "
                                        + "injection attempt against the data driving every prioritization "
                                        + "decision until shown otherwise.",
                                "Re-provision from a verified source. Until it succeeds, the interface states "
                                        + "the intelligence age rather than presenting matches as current — "
                                        + "an instance silently matching against six-month-old intelligence "
                                        + "is PP-1 violated in its most consequential form (OPS-DEP-038).",
                                "The failure, the retained version and its age, and the eventual "
                                        + "provisioning."),
                        Optional.empty()),

                new Runbook("Projection lag exceeding budget", "CON-PLT-027", Severity.STANDARD,
                        phases(
                                "Projection lag passes its budget for a sustained interval.",
                                "Surface the lag in the interface before scaling anything. A read model "
                                        + "silently behind is a figure presented as current that is not, and "
                                        + "the user acting on it has no way to know.",
                                "Identify whether the cause is event volume, a slow projection, or a poison "
                                        + "event blocking the stream. The third does not resolve by scaling "
                                        + "and looks identical from the lag metric alone.",
                                "Scale projection workers for volume; fix and replay for a defect; quarantine "
                                        + "and record for a poison event.",
                                "The lag interval, the cause, and the interval during which read models were "
                                        + "presented as stale."),
                        Optional.empty()),

                new Runbook("Partition exhaustion", "OPS-DEP-011", Severity.HIGH,
                        phases(
                                "partition_runway_report() returns alerting = true for any parent table, at "
                                        + "least one month before the runway reaches the lead time.",
                                "Run the table's ensure_*_partitions with an extended lead time. This is a "
                                        + "create-if-not-exists, so it is safe to run before anything is "
                                        + "diagnosed.",
                                "Establish why the scheduled provisioning did not run. The alert firing means "
                                        + "the maintenance task was omitted, and the omission recurs monthly "
                                        + "until it is found.",
                                "Restore the scheduled job and confirm the runway. For audit_event a missing "
                                        + "partition fails every audited operation under CON-PLT-021 — a total "
                                        + "write outage from an omitted maintenance task.",
                                "The alert, the runway at detection, and the cause of the omission."),
                        Optional.empty()),

                new Runbook("Migration failure and reorganization saga stuck in manual intervention",
                        "PRD-WRK-041", Severity.HIGH,
                        phases(
                                "A migration fails mid-run, or a reorganization saga enters manual "
                                        + "intervention and stays there.",
                                "Stop the pipeline. A migration runs as migration_runner with row-level "
                                        + "enforcement bypassed, so a partially applied migration is the "
                                        + "highest-risk state the platform can be in.",
                                "Determine what applied. Expand-migrate-contract means a failed expand is "
                                        + "safe to retry and a failed contract is not, and the two are "
                                        + "distinguished by which step failed rather than by the error.",
                                "Roll forward, never back: rollback is to the prior application version "
                                        + "without a schema rollback (OPS-DEP-030). Then the cross-tenant "
                                        + "assertion of OPS-DEP-031 before the release is complete.",
                                "The migration, the failure point, the recovery, and the cross-tenant "
                                        + "assertion result."),
                        Optional.empty()),

                new Runbook("Restore", "OPS-DEP-034", Severity.HIGH,
                        phases(
                                "A rehearsal interval, or a recovery need.",
                                "Restore into the isolated environment of OPS-DEP-025, never alongside "
                                        + "production. A restore path that can reach production is a restore "
                                        + "path that can overwrite it.",
                                "Confirm the backup's tenant key material matches the target tenant. A "
                                        + "misrouted restore yields unreadable ciphertext by design "
                                        + "(OPS-DEP-035) — unreadable output is the control working, not a "
                                        + "corrupt backup.",
                                "Restore scoped to one tenant or to a whole instance. There is no path that "
                                        + "mixes tenants (OPS-DEP-037). Record the elapsed time against the "
                                        + "four-hour recovery time objective, measured rather than asserted.",
                                "The rehearsal or recovery, the scope, the elapsed time, and the outcome."),
                        Optional.empty()),

                new Runbook("Noisy neighbour", "OPS-DEP-046", Severity.STANDARD,
                        phases(
                                "One tenant's consumption degrades another's latency beyond the bound of "
                                        + "NFR-PLT-002.",
                                "Apply the per-tenant limit at ingress and queue. The limit is applied to the "
                                        + "tenant causing it, not shared across the affected ones.",
                                "Identify the work class. A portfolio-scale operation running in an "
                                        + "interactive class is the usual cause, and OPS-DEP-047 says it "
                                        + "should not have been able to.",
                                "Move the operation to a class that cannot starve interactive work, per "
                                        + "tenant and across tenants. A noisy-neighbour failure is attributed "
                                        + "to the platform rather than to the tenant who caused it.",
                                "The tenant, the class, the limit applied, and the latency observed by the "
                                        + "affected tenants."),
                        Optional.empty()),

                new Runbook("AI provider outage", "PRD-AIC-023", Severity.STANDARD,
                        phases(
                                "The provider endpoint is unavailable or exceeds its latency budget.",
                                "Fall back to the non-AI path. Nothing in the platform's determinism depends "
                                        + "on the provider: scores, service levels, deduplication, "
                                        + "authorization and state transitions are computed without it (PP-2).",
                                "Confirm the outage is the provider rather than the platform's own egress "
                                        + "allowlist or credential.",
                                "Restore, and reprocess nothing automatically. AI output is a suggestion "
                                        + "awaiting human promotion (ADR-005), so a queued backlog of "
                                        + "suggestions promoted in bulk on recovery is the failure mode to "
                                        + "avoid.",
                                "The outage interval and the capabilities stated as unavailable during it "
                                        + "(OPS-DEP-038)."),
                        Optional.empty()),

                new Runbook("Air-gapped provisioning", "DOC-15 section 14", Severity.STANDARD,
                        phases(
                                "A bundle arrives on controlled media.",
                                "Verify the signature before the bundle is read by anything else. The "
                                        + "transfer medium is the only ingress, so it is the only injection "
                                        + "path and it is unauthenticated until this step passes.",
                                "Record the bundle version and age. An air-gapped instance's intelligence is "
                                        + "as old as its last successful transfer, and nothing else reveals "
                                        + "that.",
                                "Apply, and state in the interface which capabilities remain unavailable and "
                                        + "the consequence of each (OPS-DEP-038). Audit anchoring is an "
                                        + "operator-attested offline record, labelled as weaker than an "
                                        + "external anchor (SEC-AUD-015).",
                                "The bundle identity, its signature verification, its version and age, and "
                                        + "the operator attestation."),
                        Optional.empty()));
    }
}
