package aspm.module.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.assessment.domain.Evidence;
import aspm.module.assessment.domain.ExternalAssessorGrant;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Prompt 10 session 3 — evidence and external assessor grants. {@code INV-ASM-20} to {@code INV-ASM-29}. */
class EvidenceAndGrantTest {

    private static final Instant T0 = Instant.parse("2026-08-01T09:00:00Z");
    private static final UUID ASSESSMENT = new UUID(113, 1);
    private static final UUID UPLOADER = new UUID(113, 2);
    private static final UUID ASSESSOR = new UUID(113, 3);
    private static final UUID ENGAGEMENT = new UUID(113, 4);
    private static final UUID OTHER_PRINCIPAL = new UUID(113, 9);

    private static Evidence.IsolatedStorageRef storage() {
        return new Evidence.IsolatedStorageRef(UUID.randomUUID().toString(),
                "https://evidence.aspm-storage.example");
    }

    private static Evidence uploaded(String declaredType, String verifiedType, String originalName) {
        return Evidence.uploaded(UUID.randomUUID(), ASSESSMENT, null, "V5.3.1", storage(),
                declaredType, verifiedType, "sha256:abc", originalName, UPLOADER, T0, Duration.ofDays(365));
    }

    private static Evidence webShell() {
        return uploaded("application/x-php", "application/x-php", "shell.php");
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-ASM-21 — a malicious verdict FLAGS; it does not delete")
    class MaliciousVerdict {

        @Test
        @DisplayName("malicious evidence is retained and becomes FLAGGED_AVAILABLE")
        void maliciousIsRetained() {
            var evidence = webShell();
            evidence.recordScan(Evidence.ScanVerdict.MALICIOUS, "clamav 1.4.1 / 27100", T0.plusSeconds(30));

            assertEquals(Evidence.Availability.FLAGGED_AVAILABLE, evidence.availability(),
                    "a web shell demonstrating an unrestricted upload vulnerability IS the proof the finding "
                            + "rests on; deleting it destroys the evidence and makes the finding disputable");
        }

        @Test
        @DisplayName("there is no delete path on evidence at any privilege")
        void noDeletePath() {
            for (Method m : Evidence.class.getMethods()) {
                if (m.getDeclaringClass() == Object.class) {
                    continue;
                }
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("delete") || name.startsWith("purge")
                                || name.startsWith("remove") || name.startsWith("destroy"),
                        "found Evidence." + m.getName() + ". An antivirus product deleting a web shell is "
                                + "behaving correctly by its own lights — it does not know the web shell is "
                                + "Exhibit A (INV-ASM-21).");
            }
        }

        @Test
        @DisplayName("retrieving flagged evidence requires an explicit acknowledgement")
        void flaggedRetrievalRequiresAcknowledgement() {
            var evidence = webShell();
            evidence.recordScan(Evidence.ScanVerdict.MALICIOUS, "clamav 1.4.1", T0.plusSeconds(30));

            var ex = assertThrows(IllegalStateException.class, () -> evidence.retrievalTicket(false));
            assertTrue(ex.getMessage().contains("retained deliberately"),
                    "the message must explain why it exists rather than reading as a failure; got "
                            + ex.getMessage());
            assertTrue(ex.getMessage().contains("clamav"),
                    "and name the scanner, because a false positive on pentest evidence is the expected case");

            var ticket = evidence.retrievalTicket(true);
            assertTrue(ticket.requiresAcknowledgement());
        }

        @Test
        @DisplayName("evidence is not retrievable until the scan completes")
        void quarantinedUntilScanned() {
            var evidence = webShell();
            assertEquals(Evidence.Availability.QUARANTINED, evidence.availability());
            assertThrows(IllegalStateException.class, () -> evidence.retrievalTicket(true),
                    "acknowledging does not substitute for scanning");
        }

        @Test
        @DisplayName("SCAN_FAILED stays quarantined — PP-1, not optimism")
        void unscannableStaysQuarantined() {
            var evidence = uploaded("application/zip", "application/zip", "archive.zip");
            evidence.recordScan(Evidence.ScanVerdict.SCAN_FAILED, "clamav 1.4.1", T0.plusSeconds(30));

            assertEquals(Evidence.Availability.QUARANTINED, evidence.availability(),
                    "an unscannable file is not a clean one, and an encrypted archive the scanner could not "
                            + "open is exactly the shape a deliberate evasion takes");
        }

        @Test
        @DisplayName("a verdict without a named scanner is refused")
        void verdictNeedsAScanner() {
            var evidence = webShell();
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> evidence.recordScan(Evidence.ScanVerdict.MALICIOUS, "  ", T0));
            assertTrue(ex.getMessage().contains("false positive"),
                    "a verdict whose source is unknown cannot be re-evaluated when the scanner is later found "
                            + "to have been wrong");
        }

        @Test
        @DisplayName("clean evidence retrieves without acknowledgement")
        void cleanRetrievesPlainly() {
            var evidence = uploaded("image/png", "image/png", "screenshot.png");
            evidence.recordScan(Evidence.ScanVerdict.CLEAN, "clamav 1.4.1", T0.plusSeconds(30));

            var ticket = evidence.retrievalTicket(false);
            assertFalse(ticket.requiresAcknowledgement(),
                    "the control must not obstruct the ordinary case, or people route around it");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-ASM-20 / -22 / -23 / -24 — classification, exclusion, filenames, retention")
    class Handling {

        @Test
        @DisplayName("INV-ASM-20: RESTRICTED unconditionally, with no field to configure")
        void restrictedUnconditionally() {
            assertEquals("RESTRICTED", webShell().classification());
            for (Method m : Evidence.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("set") && name.contains("classification"),
                        "a field is a thing a migration can set; ADR-047 makes restricted fields ABSENT from "
                                + "representations, so this classification tells a serializer to omit the "
                                + "record rather than redact parts of it");
            }
        }

        @Test
        @DisplayName("INV-ASM-22: no accessor yields the content or its location except the ticket")
        void contentReachableOnlyThroughTheTicket() {
            for (Method m : Evidence.class.getMethods()) {
                if (m.getDeclaringClass() == Object.class || m.getName().equals("retrievalTicket")) {
                    continue;
                }
                assertFalse(m.getReturnType() == Evidence.IsolatedStorageRef.class,
                        "found " + m.getName() + " returning the storage reference. INV-ASM-22 excludes "
                                + "evidence from every export, notification and AI context AT ANY PERMISSION "
                                + "LEVEL, which works because an export routine cannot reach the bytes at all.");
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("content") && !name.equals("contenthash"),
                        "found " + m.getName());
            }
        }

        @Test
        @DisplayName("INV-ASM-23: the storage key is server-generated, and the original name never reaches a header")
        void filenamesAreServerGenerated() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> new Evidence.IsolatedStorageRef("../../etc/passwd", "https://e.example"));
            assertTrue(ex.getMessage().contains("Content-Disposition"),
                    "a key derived from the uploaded filename puts attacker-controlled input into a path, a "
                            + "header, and eventually a browser");

            var evidence = uploaded("image/png", "image/png", "\"; rm -rf /; x=\".png");
            evidence.recordScan(Evidence.ScanVerdict.CLEAN, "clamav", T0);
            String disposition = evidence.retrievalTicket(false).dispositionHeader();

            assertFalse(disposition.contains("rm -rf"),
                    "the original filename must not reach the header; got " + disposition);
            assertTrue(disposition.startsWith("attachment;"),
                    "an inline disposition renders attacker-authored content in a browser (SEC-PTR-006)");
            assertTrue(evidence.originalFilenameForDisplayOnly().contains("rm -rf"),
                    "the original is retained as metadata — it is evidence about the upload too — and the "
                            + "accessor is named for its one permitted use");
        }

        @Test
        @DisplayName("a declared/verified type mismatch is visible")
        void typeMismatchIsVisible() {
            var polyglot = uploaded("image/png", "application/x-php", "avatar.png");
            assertTrue(polyglot.typeMismatch(),
                    "trusting the declared type is how a polyglot becomes an image everywhere it is listed and "
                            + "a script where it is served (SEC-PTR-006)");
            assertFalse(uploaded("image/png", "image/png", "a.png").typeMismatch());
        }

        @Test
        @DisplayName("INV-ASM-24: retention is bounded and cannot be set beyond the maximum")
        void retentionIsBounded() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> Evidence.uploaded(UUID.randomUUID(), ASSESSMENT, null, null, storage(),
                            "image/png", "image/png", "sha256:a", "a.png", UPLOADER, T0,
                            Duration.ofDays(3650)));
            assertTrue(ex.getMessage().contains("accumulating liability"),
                    "indefinite retention of exploit tooling is not a conservative default, and this store is "
                            + "a higher-value target than most systems the platform protects");

            var evidence = uploaded("image/png", "image/png", "a.png");
            assertFalse(evidence.retentionExpired(T0.plus(Duration.ofDays(364))));
            assertTrue(evidence.retentionExpired(T0.plus(Duration.ofDays(365))));
        }

        @Test
        @DisplayName("unattached evidence cannot be created")
        void evidenceMustAttach() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> Evidence.uploaded(UUID.randomUUID(), null, null, null, storage(), "image/png",
                            "image/png", "sha256:a", "a.png", UPLOADER, T0, Duration.ofDays(30)));
            assertTrue(ex.getMessage().contains("nobody is accountable"),
                    "and no retention sweep will find it by subject");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-ASM-25 to -29 — external assessor grants")
    class Grants {

        private ExternalAssessorGrant grant(Instant from, Instant until) {
            return new ExternalAssessorGrant(UUID.randomUUID(), ASSESSOR, ENGAGEMENT,
                    List.of(new ExternalAssessorGrant.ObjectGrant("ASSESSMENT", ASSESSMENT)),
                    Set.of("NDA", "RULES_OF_ENGAGEMENT"), from, until);
        }

        private ExternalAssessorGrant active() {
            var g = grant(T0, T0.plus(Duration.ofDays(30)));
            g.issue();
            g.acceptAgreement(new ExternalAssessorGrant.AgreementAcceptance("NDA", 2, T0, "203.0.113.7"));
            g.acceptAgreement(new ExternalAssessorGrant.AgreementAcceptance("RULES_OF_ENGAGEMENT", 1, T0,
                    "203.0.113.7"));
            g.activate(T0);
            return g;
        }

        @Test
        @DisplayName("INV-ASM-25: a grant conveys explicit objects and cannot express a scope")
        void grantsAreExplicitObjects() {
            for (Method m : ExternalAssessorGrant.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("scope") || name.contains("subtree") || name.contains("node"),
                        "found " + m.getName() + ". Scope inheritance widens SILENTLY — the org tree behaving "
                                + "correctly grows an untrusted party's visibility as a side effect of an "
                                + "unrelated reorganization (INV-ASM-25).");
            }
            var g = active();
            assertTrue(g.grants("ASSESSMENT", ASSESSMENT, T0.plusSeconds(60)));
            assertFalse(g.grants("ASSESSMENT", UUID.randomUUID(), T0.plusSeconds(60)),
                    "membership of an explicit list, with no subtree walk to widen it");
        }

        @Test
        @DisplayName("INV-ASM-26: valid_until is mandatory and bounded")
        void expiryIsMandatoryAndBounded() {
            assertThrows(NullPointerException.class,
                    () -> new ExternalAssessorGrant(UUID.randomUUID(), ASSESSOR, ENGAGEMENT,
                            List.of(new ExternalAssessorGrant.ObjectGrant("ASSESSMENT", ASSESSMENT)),
                            Set.of(), T0, null),
                    "every dormant external account an access review finds is a standing compromise of all "
                            + "the customer's posture data");

            var ex = assertThrows(IllegalArgumentException.class,
                    () -> grant(T0, T0.plus(Duration.ofDays(400))));
            assertTrue(ex.getMessage().contains("extendable grants become permanent"),
                    "a long grant is an extension granted in advance");
        }

        @Test
        @DisplayName("INV-ASM-26: validity is computed from the clock, not from the state")
        void expiryIsAutomatic() {
            var g = active();
            assertTrue(g.valid(T0.plus(Duration.ofDays(29))));
            assertFalse(g.valid(T0.plus(Duration.ofDays(31))),
                    "the state is still ACTIVE — validity that depended on a sweep having run would stay open "
                            + "for as long as the sweep was broken, and a broken sweep is silent");
            assertEquals(ExternalAssessorGrant.State.ACTIVE, g.state());
            assertFalse(g.grants("ASSESSMENT", ASSESSMENT, T0.plus(Duration.ofDays(31))));
        }

        @Test
        @DisplayName("DOC-09 section 14.1: no extension transition exists")
        void noExtensionTransition() {
            for (Method m : ExternalAssessorGrant.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("extend") || name.contains("prolong")
                                || (name.startsWith("set") && name.contains("until")),
                        "found " + m.getName() + ". Continuing access requires a new grant with a new "
                                + "approval, because extendable grants become permanent.");
            }
        }

        @Test
        @DisplayName("INV-ASM-27: no access before every required agreement is accepted")
        void agreementsGateAccess() {
            var g = grant(T0, T0.plus(Duration.ofDays(30)));
            g.issue();
            g.acceptAgreement(new ExternalAssessorGrant.AgreementAcceptance("NDA", 2, T0, "203.0.113.7"));

            var ex = assertThrows(IllegalStateException.class, () -> g.activate(T0));
            assertTrue(ex.getMessage().contains("RULES_OF_ENGAGEMENT"),
                    "the outstanding agreements must be named; got " + ex.getMessage());
            assertFalse(g.valid(T0));
        }

        @Test
        @DisplayName("accepting an agreement nobody asked for does not substitute for one that was")
        void unrequestedAgreementRefused() {
            var g = grant(T0, T0.plus(Duration.ofDays(30)));
            g.issue();
            assertThrows(IllegalArgumentException.class,
                    () -> g.acceptAgreement(new ExternalAssessorGrant.AgreementAcceptance("SOME_OTHER", 1, T0,
                            "203.0.113.7")));
        }

        @Test
        @DisplayName("an acceptance records which version was agreed and where from")
        void acceptanceIsAttributable() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExternalAssessorGrant.AgreementAcceptance("NDA", 0, T0, "203.0.113.7"),
                    "accepting 'the NDA' without saying which one leaves no record of what was agreed");
            assertThrows(NullPointerException.class,
                    () -> new ExternalAssessorGrant.AgreementAcceptance("NDA", 1, T0, null),
                    "an acceptance nobody can locate is one nobody can attribute if it is later disputed");
        }

        @Test
        @DisplayName("INV-ASM-28: credentials are revealable to the grantee, with elevated audit attached")
        void credentialRevealIsPermittedAndAudited() {
            var g = active();
            var permission = g.mayRevealTestCredential(ASSESSOR, ENGAGEMENT, T0.plusSeconds(60));
            assertTrue(permission.permitted(),
                    "the assessor cannot test with a credential they cannot read, and refusing would push it "
                            + "into an email");
            assertTrue(permission.requiresElevatedAudit(),
                    "the audit obligation travels with the answer rather than being a separate thing somebody "
                            + "remembers (PRD-AUD-003)");

            assertFalse(g.mayRevealTestCredential(OTHER_PRINCIPAL, ENGAGEMENT, T0.plusSeconds(60))
                    .permitted());
            assertFalse(g.mayRevealTestCredential(ASSESSOR, UUID.randomUUID(), T0.plusSeconds(60)).permitted(),
                    "a grant for one engagement conveying credentials for another is exactly the widening "
                            + "INV-ASM-25 removes");
            assertFalse(g.mayRevealTestCredential(ASSESSOR, ENGAGEMENT, T0.plus(Duration.ofDays(31)))
                    .permitted(), "and an expired grant reveals nothing");
        }

        @Test
        @DisplayName("INV-ASM-29: closure flags rotation, and only an attestation closes it")
        void closureFlagsRotation() {
            var g = active();
            g.revoke(OTHER_PRINCIPAL, "engagement closed early", T0.plus(Duration.ofDays(10)));

            assertTrue(g.credentialRotationFlagged(),
                    "flagged on revocation rather than by a separate closure routine, because a routine that "
                            + "must remember to run will not run on the engagement that ended badly");
            assertTrue(g.rotationOutstanding());

            g.attestCredentialRotation(OTHER_PRINCIPAL);
            assertFalse(g.rotationOutstanding(),
                    "the attestation is what closes it; a flag nobody has to answer for is a list that grows");
        }

        @Test
        @DisplayName("rotation cannot be attested while the assessor is still using the credentials")
        void attestationRequiresClosure() {
            var g = active();
            assertThrows(IllegalStateException.class, () -> g.attestCredentialRotation(OTHER_PRINCIPAL),
                    "attesting while the grant is ACTIVE would rotate credentials the assessor is still using");
        }

        @Test
        @DisplayName("a grant conveying no objects cannot be created")
        void emptyGrantRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExternalAssessorGrant(UUID.randomUUID(), ASSESSOR, ENGAGEMENT, List.of(),
                            Set.of(), T0, T0.plus(Duration.ofDays(1))));
        }

        @Test
        @DisplayName("a grant cannot be activated into an already-closed window")
        void cannotActivateIntoAClosedWindow() {
            var g = grant(T0, T0.plus(Duration.ofDays(1)));
            g.issue();
            g.acceptAgreement(new ExternalAssessorGrant.AgreementAcceptance("NDA", 1, T0, "203.0.113.7"));
            g.acceptAgreement(new ExternalAssessorGrant.AgreementAcceptance("RULES_OF_ENGAGEMENT", 1, T0,
                    "203.0.113.7"));

            var ex = assertThrows(IllegalStateException.class,
                    () -> g.activate(T0.plus(Duration.ofDays(2))));
            assertTrue(ex.getMessage().contains("read the state rather than the clock"),
                    "an ACTIVE grant that is not valid is a trap for the next reader");
        }

        @Test
        @DisplayName("expiry cannot be recorded before the window closes")
        void expiryNotRecordedEarly() {
            var g = active();
            assertThrows(IllegalArgumentException.class, () -> g.markExpired(T0.plus(Duration.ofDays(5))),
                    "marking it expired early would make the recorded end differ from the enforced one");
            g.markExpired(T0.plus(Duration.ofDays(30)));
            assertEquals(ExternalAssessorGrant.State.EXPIRED, g.state());
        }
    }
}
