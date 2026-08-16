package aspm.module.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.assessment.domain.AssessmentRequest;
import aspm.module.assessment.domain.RoleAccount;
import aspm.module.assessment.domain.SecretRef;
import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.ScopeDescriptor;
import aspm.sharedkernel.TenantId;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Prompt 10 session 1 — intake. {@code INV-ASM-01} through {@code INV-ASM-09}, and the credential handling of
 * DOC-16 section 8.
 */
class IntakeTest {

    private static final Instant T0 = Instant.parse("2026-08-01T09:00:00Z");
    private static final UUID TYPE = new UUID(110, 1);
    private static final UUID REQUESTER = new UUID(110, 2);
    private static final UUID NODE = new UUID(110, 3);
    private static final UUID OUT_OF_SCOPE_NODE = new UUID(110, 9);
    private static final UUID ASSET = new UUID(110, 4);

    private static ScopeDescriptor descriptor() {
        var node = new OrgNodeId(NODE);
        return new ScopeDescriptor(new TenantId(new UUID(1, 1)), node, List.of(node),
                new UUID(2, 1), new UUID(3, 1), T0, 1L);
    }

    /** Authorized for NODE only. Anything else resolves empty — not authorized and not existing, undistinguished. */
    private static final AssessmentRequest.ScopeAuthority AUTHORITY =
            (principal, orgNode) -> principal.equals(REQUESTER) && orgNode.equals(NODE)
                    ? Optional.of(descriptor()) : Optional.empty();

    private static RoleAccount account(String role, String username) {
        return new RoleAccount(role, "a " + role, username, SecretRef.of("vault:test/" + username),
                false, Optional.empty(), Optional.empty(), List.of("read"), RoleAccount.Status.PROVIDED);
    }

    private static AssessmentRequest.ReadinessAttestation ready() {
        return new AssessmentRequest.ReadinessAttestation(true, true, true, true, Optional.of(T0),
                Optional.of(REQUESTER));
    }

    private static AssessmentRequest submittedRequest() {
        var request = AssessmentRequest.draft(UUID.randomUUID(), "REQ-1", TYPE, NODE, List.of(ASSET),
                REQUESTER);
        request.addEnvironment(new AssessmentRequest.TestEnvironment("staging",
                "https://staging.example.internal", false, Optional.empty()));
        request.submit(AUTHORITY, T0);
        return request;
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-ASM-03 / PRD-API-033 — the credential field")
    class Credentials {

        @Test
        @DisplayName("a plaintext credential is rejected")
        void plaintextRejected() {
            for (String plaintext : List.of("hunter2", "P@ssw0rd!", "correct horse battery staple",
                    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.abc.def", "AKIAIOSFODNN7EXAMPLE")) {
                assertThrows(SecretRef.PlaintextCredentialRejected.class, () -> SecretRef.of(plaintext),
                        "'" + plaintext + "' must not be storable in a credential field (PRD-API-033)");
            }
        }

        @Test
        @DisplayName("the rejected value appears nowhere in the rejection")
        void rejectedValueIsNotEchoed() {
            String secret = "SuperSecretStagingPassword2026";
            var thrown = assertThrows(SecretRef.PlaintextCredentialRejected.class,
                    () -> SecretRef.of(secret));

            String message = thrown.getMessage();
            assertFalse(message.contains(secret),
                    "rejecting at the boundary and not logging the rejected value is what prevents the "
                            + "credential reaching a log as a side effect of the rejection (PRD-API-033)");
            for (int length = 4; length <= secret.length(); length++) {
                assertFalse(message.contains(secret.substring(0, length)),
                        "not even a prefix: got " + message);
            }
            assertFalse(message.contains(String.valueOf(secret.length())),
                    "and not the length either — a length alone narrows a brute force");
            assertTrue(message.contains("rotate"),
                    "the submitter must be told to rotate, because the credential reached whatever handled "
                            + "the request before it was discarded");
        }

        @Test
        @DisplayName("the rejection message cannot depend on the input: the builder takes no argument")
        void rejectionBuilderTakesNoInput() {
            boolean found = false;
            for (Method m : SecretRef.class.getDeclaredMethods()) {
                if (m.getName().equals("rejectionMessage")) {
                    found = true;
                    assertEquals(0, m.getParameterCount(),
                            "a parameter that exists is one a later change can start using, and the natural "
                                    + "later change is 'make this diagnosis more helpful'");
                }
            }
            assertTrue(found, "the named builder must exist for this assertion to mean anything");
        }

        @Test
        @DisplayName("a well-formed reference is accepted and is safe to log")
        void referenceAccepted() {
            var ref = SecretRef.of("vault:aspm/tenant-a/req-1/admin#3");
            assertEquals("vault", ref.provider());
            assertEquals("vault:aspm/tenant-a/req-1/admin#3", ref.toString(),
                    "the reference names a location, not a secret, so it may appear in a log");
            assertTrue(SecretRef.isReference("openbao:kv/data/x"));
            assertFalse(SecretRef.isReference("not a reference"));
        }

        @Test
        @DisplayName("SEC-PTR-004: nothing on the type can return a credential value")
        void noValueAccessor() {
            for (Method m : SecretRef.class.getMethods()) {
                if (m.getDeclaringClass() == Object.class) {
                    continue;
                }
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("secretvalue") || name.contains("plaintext")
                                || name.contains("resolve") || name.contains("reveal"),
                        "found " + m.getName() + ". The reveal path is explicitly permissioned, step-up "
                                + "authenticated and per-object audited (SEC-PTR-004), and it is deliberately "
                                + "not reachable from this type — the requester must not be able to read the "
                                + "credential back after submission.");
            }
            for (Method m : RoleAccount.class.getMethods()) {
                assertFalse(m.getReturnType() == String.class
                                && m.getName().toLowerCase(Locale.ROOT).contains("credential"),
                        "found " + m.getName() + " returning a String; a credential accessor here would put "
                                + "working credentials in every export and AI prompt");
            }
        }

        @Test
        @DisplayName("an MFA-enrolled account with no bypass reference is refused at intake")
        void mfaWithoutBypassRefused() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> new RoleAccount("admin", "an admin", "admin1",
                            SecretRef.of("vault:test/admin1"), true, Optional.empty(), Optional.empty(),
                            List.of(), RoleAccount.Status.PROVIDED));
            assertTrue(ex.getMessage().contains("day one"),
                    "the engagement cannot authenticate as it, and finding that out on day one costs a day of "
                            + "a booked engagement");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-ASM-01 / SEC-AUZ-018 — scope re-validated on write")
    class ScopeValidation {

        @Test
        @DisplayName("an out-of-scope node is refused, and the refusal reads as not-found")
        void outOfScopeNodeRefused() {
            var request = AssessmentRequest.draft(UUID.randomUUID(), "REQ-2", TYPE, OUT_OF_SCOPE_NODE,
                    List.of(ASSET), REQUESTER);
            var ex = assertThrows(IllegalArgumentException.class, () -> request.submit(AUTHORITY, T0));
            assertTrue(ex.getMessage().startsWith("not found"),
                    "distinguishing 'not authorized' from 'does not exist' turns intake into an "
                            + "org-structure oracle (SEC-AUZ-020); got " + ex.getMessage());
            assertTrue(ex.getMessage().contains("PP-4"),
                    "the picker that produced the identifier is a usability feature, never a control");
        }

        @Test
        @DisplayName("the descriptor is resolved by the server, not supplied by the caller")
        void descriptorIsResolvedNotSupplied() {
            for (Method m : AssessmentRequest.class.getMethods()) {
                if (!m.getName().equals("submit")) {
                    continue;
                }
                for (Class<?> parameter : m.getParameterTypes()) {
                    assertFalse(parameter == ScopeDescriptor.class,
                            "submit takes a resolver, not a descriptor: a caller who can hand over a "
                                    + "descriptor can hand over any descriptor (SEC-AUZ-018)");
                }
            }
        }

        @Test
        @DisplayName("INV-ASM-07: the scope is frozen at submission with no setter")
        void scopeIsImmutableAfterSubmission() {
            var request = submittedRequest();
            assertTrue(request.scope().isPresent());
            for (Method m : AssessmentRequest.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("scope") && (name.startsWith("set") || name.startsWith("update")),
                        "found " + m.getName() + "; the scope is immutable even if the project later moves "
                                + "(INV-ASM-07), which is what keeps historical reporting reproducible");
            }
        }

        @Test
        @DisplayName("INV-ASM-06: a request references exactly one org node")
        void oneOrgNodePerRequest() {
            for (Method m : AssessmentRequest.class.getMethods()) {
                if (m.getName().equals("requestedOrgNodeId")) {
                    assertEquals(UUID.class, m.getReturnType(),
                            "a collection here would be INV-ASM-06 violated in the signature: a request "
                                    + "spanning two projects has two owners, two readiness states and two sets "
                                    + "of accounts, and every one of those diverges");
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-ASM-02 — at least two accounts per declared role before ACCEPTED")
    class TwoAccountsPerRole {

        @Test
        @DisplayName("one account for a role blocks acceptance, naming the role and the reason")
        void oneAccountBlocksAcceptance() {
            var request = submittedRequest();
            request.addRoleAccount(account("admin", "admin1"));
            request.attestReadiness(ready());

            var ex = assertThrows(IllegalStateException.class, () -> request.accept(T0));
            assertTrue(ex.getMessage().contains("'admin' has 1 usable account"),
                    "got " + ex.getMessage());
            assertTrue(ex.getMessage().contains("user A can read user B's data"),
                    "two accounts of the same role is the ONLY way to demonstrate broken object-level "
                            + "authorization, and the message must say why rather than cite an identifier");
        }

        @Test
        @DisplayName("two accounts for every declared role permits acceptance")
        void twoAccountsPermitAcceptance() {
            var request = submittedRequest();
            request.addRoleAccount(account("admin", "admin1"));
            request.addRoleAccount(account("admin", "admin2"));
            request.addRoleAccount(account("viewer", "viewer1"));
            request.addRoleAccount(account("viewer", "viewer2"));
            request.attestReadiness(ready());

            request.accept(T0);
            assertEquals(AssessmentRequest.State.ACCEPTED, request.state(),
                    "the gate must permit the legitimate case, or it proves nothing");
        }

        @Test
        @DisplayName("a second role with only one account blocks acceptance even where the first has two")
        void everyRoleNeedsTwo() {
            var request = submittedRequest();
            request.addRoleAccount(account("admin", "admin1"));
            request.addRoleAccount(account("admin", "admin2"));
            request.addRoleAccount(account("viewer", "viewer1"));
            request.attestReadiness(ready());

            assertTrue(request.acceptanceGaps().stream().anyMatch(g -> g.contains("'viewer'")),
                    "a partially satisfied request would test admin-to-admin escalation and silently skip "
                            + "viewer-to-viewer");
        }

        @Test
        @DisplayName("unusable accounts do not count toward the two")
        void unusableAccountsDoNotCount() {
            var request = submittedRequest();
            request.addRoleAccount(account("admin", "admin1"));
            request.addRoleAccount(new RoleAccount("admin", "an admin", "admin2",
                    SecretRef.of("vault:test/admin2"), false, Optional.empty(), Optional.empty(), List.of(),
                    RoleAccount.Status.LOCKED));
            request.attestReadiness(ready());

            assertTrue(request.acceptanceGaps().stream().anyMatch(g -> g.contains("1 usable account")),
                    "a locked account is a credential the engagement cannot use, and counting it means the "
                            + "invariant is satisfied on paper and not in the environment");
        }

        @Test
        @DisplayName("role names are matched case-insensitively")
        void roleNamesAreNormalized() {
            var request = submittedRequest();
            request.addRoleAccount(account("Admin", "admin1"));
            request.addRoleAccount(account("admin", "admin2"));
            request.attestReadiness(ready());

            request.accept(T0);
            assertEquals(AssessmentRequest.State.ACCEPTED, request.state(),
                    "'Admin' and 'admin' are one role; treating them as two would report a gap that is not "
                            + "there and train requesters to add accounts until the message stops");
        }

        @Test
        @DisplayName("a request with no accounts at all is refused with a distinct message")
        void noAccountsAtAll() {
            var request = submittedRequest();
            request.attestReadiness(ready());
            assertTrue(request.acceptanceGaps().stream()
                            .anyMatch(g -> g.contains("no test accounts are declared")),
                    "reporting zero roles as 'all roles satisfied' is the vacuous-truth bug: an empty set "
                            + "satisfies a for-all");
        }

        @Test
        @DisplayName("a blank role name is refused, so two blank roles cannot satisfy the rule")
        void blankRoleNameRefused() {
            assertThrows(IllegalArgumentException.class, () -> account("  ", "u1"));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-ASM-04 / INV-ASM-05 — readiness and protective controls")
    class ReadinessAndControls {

        @Test
        @DisplayName("INV-ASM-05: a protective control with no recorded bypass is unrepresentable")
        void protectiveControlNeedsAnArrangement() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> new AssessmentRequest.TestEnvironment("staging", "https://s.example", true,
                            Optional.empty()));
            assertTrue(ex.getMessage().contains("tests the firewall"),
                    "the engagement tests the control, reports the application sound, and the assessment is "
                            + "worthless in a way nobody notices until an incident");

            // And the inverse: an arrangement recorded for a control not declared.
            assertThrows(IllegalArgumentException.class,
                    () -> new AssessmentRequest.TestEnvironment("staging", "https://s.example", false,
                            Optional.of("allowlisted by IP")),
                    "one of the two is wrong and neither can be trusted");

            // The legitimate shape.
            var arranged = new AssessmentRequest.TestEnvironment("staging", "https://s.example", true,
                    Optional.of("tester source IPs allowlisted at the WAF, ticket NET-4471"));
            assertTrue(arranged.protectiveControlPresent());
        }

        @Test
        @DisplayName("INV-ASM-04: incomplete readiness blocks acceptance and names every gap")
        void incompleteReadinessBlocksAcceptance() {
            var request = submittedRequest();
            request.addRoleAccount(account("admin", "admin1"));
            request.addRoleAccount(account("admin", "admin2"));
            request.attestReadiness(new AssessmentRequest.ReadinessAttestation(true, false, false, true,
                    Optional.of(T0), Optional.of(REQUESTER)));

            var gaps = request.acceptanceGaps();
            assertTrue(gaps.stream().anyMatch(g -> g.contains("not provisioned")));
            assertTrue(gaps.stream().anyMatch(g -> g.contains("representative data")),
                    "every gap at once: reporting the first makes acceptance a sequence of round trips for "
                            + "the least-trained user of the platform (PP-7)");
        }

        @Test
        @DisplayName("an unattested readiness is incomplete even where every box is ticked")
        void readinessNeedsAnAttestation() {
            var unattested = new AssessmentRequest.ReadinessAttestation(true, true, true, true,
                    Optional.empty(), Optional.empty());
            assertFalse(unattested.complete(),
                    "somebody has to be accountable for the claim; an unattributed attestation is a claim "
                            + "nobody made");
            assertThrows(IllegalArgumentException.class,
                    () -> new AssessmentRequest.ReadinessAttestation(true, true, true, true,
                            Optional.of(T0), Optional.empty()));
        }

        @Test
        @DisplayName("a request with no environment is refused")
        void environmentRequired() {
            var request = AssessmentRequest.draft(UUID.randomUUID(), "REQ-3", TYPE, NODE, List.of(ASSET),
                    REQUESTER);
            request.submit(AUTHORITY, T0);
            request.addRoleAccount(account("admin", "admin1"));
            request.addRoleAccount(account("admin", "admin2"));
            request.attestReadiness(ready());

            assertTrue(request.acceptanceGaps().stream()
                            .anyMatch(g -> g.contains("no test environment")),
                    "there is nothing to assess");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-ASM-08 / INV-ASM-09 — derived facts and retests")
    class DerivedAndRetest {

        @Test
        @DisplayName("INV-ASM-08: there is no setter for priority, effort, or feasible start")
        void derivedFactsAreNotSettable() {
            for (Method m : AssessmentRequest.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("set") && (name.contains("priority") || name.contains("effort")
                                || name.contains("feasible")),
                        "found " + m.getName() + ". PP-2: the estimate drives a capacity commitment and must "
                                + "be explainable when missed. A field somebody can type into is a field "
                                + "somebody will type a comfortable number into.");
            }
        }

        @Test
        @DisplayName("derived facts carry the model version that produced them")
        void derivedFactsAreReproducible() {
            assertThrows(IllegalArgumentException.class,
                    () -> new AssessmentRequest.DerivedRequestFacts(50, java.math.BigDecimal.TEN,
                            Optional.empty(), 0),
                    "without a model version the figure cannot be reproduced, and PRD-RSK-038 requires "
                            + "estimation to be deterministic and versioned");
        }

        @Test
        @DisplayName("INV-ASM-09: a retest needs both a prior assessment and a new revision")
        void retestNeedsBothReferences() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> AssessmentRequest.retest(UUID.randomUUID(), "REQ-4", TYPE, NODE, List.of(ASSET),
                            REQUESTER, UUID.randomUUID(), "  "));
            assertTrue(ex.getMessage().contains("same build"),
                    "a retest against the same build re-runs the same tests on the same code and reports the "
                            + "same findings as fixed or not fixed with no evidence either way");

            var valid = AssessmentRequest.retest(UUID.randomUUID(), "REQ-5", TYPE, NODE, List.of(ASSET),
                    REQUESTER, UUID.randomUUID(), "release-4.3.1");
            assertTrue(valid.isRetest());
            assertEquals(Optional.of("release-4.3.1"), valid.revisionIdentifier());
        }

        @Test
        @DisplayName("a non-retest cannot carry a prior assessment reference")
        void nonRetestCarriesNoPriorReference() {
            var plain = AssessmentRequest.draft(UUID.randomUUID(), "REQ-6", TYPE, NODE, List.of(ASSET),
                    REQUESTER);
            assertTrue(plain.priorAssessmentId().isEmpty());
            assertFalse(plain.isRetest());
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("DOC-09 section 4 — the request machine")
    class Machine {

        @Test
        @DisplayName("intake content is settled after acceptance")
        void contentSettledAfterAcceptance() {
            var request = submittedRequest();
            request.addRoleAccount(account("admin", "admin1"));
            request.addRoleAccount(account("admin", "admin2"));
            request.attestReadiness(ready());
            request.accept(T0);

            var ex = assertThrows(IllegalStateException.class,
                    () -> request.addRoleAccount(account("admin", "admin3")));
            assertTrue(ex.getMessage().contains("scheduled engagement"),
                    "changing accounts after acceptance moves the ground under a scheduled engagement");
        }

        @Test
        @DisplayName("a rejection requires a reason")
        void rejectionNeedsAReason() {
            var request = submittedRequest();
            assertThrows(IllegalArgumentException.class, () -> request.reject("  ", T0),
                    "a requester told only 'no' resubmits the same request, and the second rejection costs "
                            + "both parties the same again");
            request.reject("the target is decommissioned next month", T0);
            assertEquals(AssessmentRequest.State.REJECTED, request.state());
        }

        @Test
        @DisplayName("a request in assessment cannot be withdrawn or merged")
        void inAssessmentIsProtected() {
            var request = submittedRequest();
            request.addRoleAccount(account("admin", "admin1"));
            request.addRoleAccount(account("admin", "admin2"));
            request.attestReadiness(ready());
            request.accept(T0);
            request.schedule(T0);
            request.beginAssessment(T0);

            assertThrows(IllegalStateException.class, () -> request.withdraw(T0));
            assertThrows(IllegalStateException.class, () -> request.mergeInto(UUID.randomUUID(), T0));
            assertThrows(IllegalStateException.class, () -> request.reject("changed my mind", T0),
                    "work is under way; withdrawing it silently would leave an engagement running against a "
                            + "request that no longer exists");
        }

        @Test
        @DisplayName("a request cannot be merged into itself")
        void noSelfMerge() {
            var request = submittedRequest();
            assertThrows(IllegalArgumentException.class, () -> request.mergeInto(request.id(), T0));
        }

        @Test
        @DisplayName("acceptance is refused before submission, because the scope is unresolved")
        void acceptanceRequiresSubmission() {
            var draft = AssessmentRequest.draft(UUID.randomUUID(), "REQ-7", TYPE, NODE, List.of(ASSET),
                    REQUESTER);
            assertThrows(IllegalStateException.class, () -> draft.accept(T0));
            assertTrue(draft.acceptanceGaps().stream().anyMatch(g -> g.contains("scope has not been resolved")));
        }

        @Test
        @DisplayName("a request with no target asset asks for nothing")
        void targetAssetRequired() {
            assertThrows(IllegalArgumentException.class,
                    () -> AssessmentRequest.draft(UUID.randomUUID(), "REQ-8", TYPE, NODE, List.of(),
                            REQUESTER));
        }
    }
}
