package aspm.module.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.notification.domain.EscalationChain;
import aspm.module.notification.domain.InboundAssociation;
import aspm.module.notification.domain.NotificationDispatch;
import aspm.module.notification.domain.RenderedNotification;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Prompt 14 — notification. DOC-13 in full. */
class NotificationTest {

    private static final Instant T0 = Instant.parse("2026-08-05T09:00:00Z");
    private static final UUID RECIPIENT = new UUID(160, 1);
    private static final UUID OTHER_RECIPIENT = new UUID(160, 2);
    private static final UUID SUBJECT = new UUID(160, 3);
    private static final UUID OTHER_SUBJECT = new UUID(160, 4);
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private static RenderedNotification rendered(UUID recipient, UUID subject, String body) {
        return new RenderedNotification(recipient, subject, "WORK_ITEM",
                RenderedNotification.Channel.EMAIL, "vi-VN", "Update on WRK-1", body,
                Optional.of(TOKEN), T0);
    }

    /** A renderer that records who it rendered for, so the per-recipient rule is observable. */
    private static final class RecordingRenderer implements NotificationDispatch.Renderer {

        final List<UUID> renderedFor = new java.util.ArrayList<>();

        @Override
        public RenderedNotification render(UUID recipientId, NotificationDispatch.DomainEvent event,
                int mergedEventCount) {
            renderedFor.add(recipientId);
            return rendered(recipientId, event.subjectId(),
                    "Now: " + event.eventKind() + " (" + mergedEventCount + " change(s))");
        }

        @Override
        public RenderedNotification renderBulkSummary(UUID recipientId, UUID bulkOperationId,
                int affectedCount) {
            renderedFor.add(recipientId);
            return rendered(recipientId, bulkOperationId, affectedCount + " item(s) updated");
        }
    }

    private static NotificationDispatch.DomainEvent event(UUID id, UUID subject, String kind, Instant at,
            UUID bulkOperation) {
        return new NotificationDispatch.DomainEvent(id, subject, "WORK_ITEM", kind, at,
                Optional.ofNullable(bulkOperation));
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-NTF-013 / -014 — a subscriber, rendering per recipient")
    class Architecture {

        @Test
        @DisplayName("dispatch cannot participate in a transaction: no writes, no repository, no outcome")
        void dispatchIsASubscriber() {
            for (Method m : NotificationDispatch.class.getMethods()) {
                if (!m.getName().equals("dispatch")) {
                    continue;
                }
                for (Class<?> parameter : m.getParameterTypes()) {
                    String name = parameter.getSimpleName().toLowerCase(Locale.ROOT);
                    assertFalse(name.contains("repository") || name.contains("connection")
                                    || name.contains("transaction") || name.contains("session"),
                            "found " + parameter.getSimpleName() + ". A notification failure inside a "
                                    + "transaction either fails the transaction — making a mail outage a work "
                                    + "outage — or is swallowed, losing the notification silently "
                                    + "(PRD-NTF-013).");
                }
                assertEquals(NotificationDispatch.Outcome.class, m.getReturnType(),
                        "it returns artifacts to deliver, not a transaction outcome a caller could branch on");
            }
        }

        @Test
        @DisplayName("PRD-NTF-014: a rendered artifact names one recipient and cannot be re-addressed")
        void oneArtifactOneRecipient() {
            for (Method m : RenderedNotification.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("with") && name.contains("recipient"),
                        "found " + m.getName() + "; rendering once for a group is the efficient design and "
                                + "the wrong one — a group notification is a disclosure to the "
                                + "least-authorized recipient, and a notification cannot be recalled");
            }
            for (var component : RenderedNotification.class.getRecordComponents()) {
                if (component.getName().equals("recipientId")) {
                    assertEquals(UUID.class, component.getType(),
                            "a collection here would be the group render in the signature");
                }
            }
        }

        @Test
        @DisplayName("two recipients of one event produce two renders")
        void twoRecipientsTwoRenders() {
            var renderer = new RecordingRenderer();
            var e = event(new UUID(161, 1), SUBJECT, "assigned", T0, null);

            var outcome = NotificationDispatch.dispatch(List.of(e),
                    Map.of(e.eventId(), Set.of(RECIPIENT, OTHER_RECIPIENT)),
                    (recipient, subject) -> true, renderer);

            assertEquals(2, outcome.toDeliver().size());
            assertEquals(Set.of(RECIPIENT, OTHER_RECIPIENT), Set.copyOf(renderer.renderedFor),
                    "scope is evaluated at render, per recipient (PRD-NTF-007)");
        }

        @Test
        @DisplayName("PRD-NTF-030: forbidden content cannot be rendered, on any channel")
        void forbiddenContentIsUnrenderable() {
            for (RenderedNotification.Channel channel : RenderedNotification.Channel.values()) {
                var ex = assertThrows(IllegalArgumentException.class,
                        () -> new RenderedNotification(RECIPIENT, SUBJECT, "FINDING", channel, "en",
                                "Secret found", "the value is vault:aspm/prod/db#3", Optional.empty(), T0));
                assertTrue(ex.getMessage().contains("any permission level"),
                        channel + ": an external channel leaves the platform by design and is frequently "
                                + "forwarded, so there is no permission level at which emailing a leaked "
                                + "credential is acceptable");
            }
        }

        @Test
        @DisplayName("the rejection names the class of content, never the content")
        void rejectionDoesNotEchoTheSecret() {
            String secret = "Bearer eyJhbGciOiJIUzI1NiJ9.verysecret";
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> new RenderedNotification(RECIPIENT, SUBJECT, "FINDING",
                            RenderedNotification.Channel.EMAIL, "en", "x", secret, Optional.empty(), T0));
            assertFalse(ex.getMessage().contains("verysecret"),
                    "the exception is logged, so echoing the content would put it in the log line reporting "
                            + "its rejection — the same reasoning as SecretRef in prompt 10");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-NTF-031 — suppression, not empty delivery")
    class Suppression {

        @Test
        @DisplayName("a subject no longer visible produces no notification at all")
        void narrowedScopeSuppresses() {
            var renderer = new RecordingRenderer();
            var e = event(new UUID(161, 2), SUBJECT, "commented", T0, null);

            var outcome = NotificationDispatch.dispatch(List.of(e),
                    Map.of(e.eventId(), Set.of(RECIPIENT)),
                    (recipient, subject) -> false, renderer);

            assertTrue(outcome.toDeliver().isEmpty(),
                    "an empty notification about an object the recipient cannot see confirms that the object "
                            + "exists and concerns them — a disclosure through absence (PRD-NTF-031)");
            assertTrue(renderer.renderedFor.isEmpty(),
                    "and nothing is rendered, so there is no artifact to accidentally deliver");
            assertEquals(1, outcome.suppressed().size());
            assertEquals(NotificationDispatch.Suppression.Reason.SUBJECT_NO_LONGER_VISIBLE,
                    outcome.suppressed().get(0).reason(),
                    "recorded, because 'did not arrive' and 'was never generated' are different diagnoses "
                            + "and only one is a bug");
        }

        @Test
        @DisplayName("visibility is evaluated now, not when the event occurred")
        void visibilityIsEvaluatedAtRenderTime() {
            var renderer = new RecordingRenderer();
            var e = event(new UUID(161, 3), SUBJECT, "assigned", T0.minus(Duration.ofDays(2)), null);

            var outcome = NotificationDispatch.dispatch(List.of(e),
                    Map.of(e.eventId(), Set.of(RECIPIENT)),
                    // Access lost since the event.
                    (recipient, subject) -> false, renderer);

            assertTrue(outcome.toDeliver().isEmpty(),
                    "including a finding summary in an email to a recipient who has since lost access is a "
                            + "disclosure no later authorization change can retract (PRD-NTF-029)");
        }

        @Test
        @DisplayName("one recipient losing access does not suppress the other's notification")
        void suppressionIsPerRecipient() {
            var renderer = new RecordingRenderer();
            var e = event(new UUID(161, 4), SUBJECT, "assigned", T0, null);

            var outcome = NotificationDispatch.dispatch(List.of(e),
                    Map.of(e.eventId(), Set.of(RECIPIENT, OTHER_RECIPIENT)),
                    (recipient, subject) -> recipient.equals(RECIPIENT), renderer);

            assertEquals(1, outcome.toDeliver().size());
            assertEquals(RECIPIENT, outcome.toDeliver().get(0).recipientId());
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-NTF-023 / -024 — the volume controls")
    class Volume {

        @Test
        @DisplayName("PRD-NTF-024: a burst within the window merges into one, stating the net change")
        void burstCoalesces() {
            var renderer = new RecordingRenderer();
            var events = List.of(
                    event(new UUID(162, 1), SUBJECT, "assigned", T0, null),
                    event(new UUID(162, 2), SUBJECT, "started", T0.plusSeconds(20), null),
                    event(new UUID(162, 3), SUBJECT, "blocked", T0.plusSeconds(40), null));

            var audience = Map.of(
                    events.get(0).eventId(), Set.of(RECIPIENT),
                    events.get(1).eventId(), Set.of(RECIPIENT),
                    events.get(2).eventId(), Set.of(RECIPIENT));

            var outcome = NotificationDispatch.dispatch(events, audience, (r, s) -> true, renderer);

            assertEquals(1, outcome.toDeliver().size(),
                    "a recipient does not need to know an item moved through three states in forty seconds; "
                            + "they need to know where it is now (PRD-NTF-024)");
            assertTrue(outcome.toDeliver().get(0).body().contains("blocked"),
                    "the NET change, not the first event; got: " + outcome.toDeliver().get(0).body());
            assertEquals(2, outcome.suppressed().size());
        }

        @Test
        @DisplayName("events beyond the window do not merge")
        void separateWindowsDoNotMerge() {
            var renderer = new RecordingRenderer();
            var events = List.of(
                    event(new UUID(162, 4), SUBJECT, "assigned", T0, null),
                    event(new UUID(162, 5), SUBJECT, "blocked", T0.plusSeconds(3600), null));
            var audience = Map.of(
                    events.get(0).eventId(), Set.of(RECIPIENT),
                    events.get(1).eventId(), Set.of(RECIPIENT));

            assertEquals(2, NotificationDispatch.dispatch(events, audience, (r, s) -> true, renderer)
                            .toDeliver().size(),
                    "an hour apart is two things that happened, and merging them would hide one");
        }

        @Test
        @DisplayName("PRD-NTF-023: a bulk operation produces one summary per recipient, across subjects")
        void bulkProducesOneSummary() {
            var renderer = new RecordingRenderer();
            var bulk = new UUID(163, 1);
            var events = new java.util.ArrayList<NotificationDispatch.DomainEvent>();
            var audience = new java.util.LinkedHashMap<UUID, Set<UUID>>();
            for (int i = 0; i < 250; i++) {
                var e = event(new UUID(164, i), new UUID(165, i), "assigned",
                        T0.plusSeconds(i * 3L), bulk);
                events.add(e);
                audience.put(e.eventId(), Set.of(RECIPIENT));
            }

            var outcome = NotificationDispatch.dispatch(events, audience, (r, s) -> true, renderer);

            assertEquals(1, outcome.toDeliver().size(),
                    "uncoalesced delivery sends hundreds of messages from one action and trains the recipient "
                            + "to filter the sender PERMANENTLY (PRD-NTF-023) — and muting is effectively "
                            + "irreversible");
            assertTrue(outcome.toDeliver().get(0).body().contains("250 item(s)"));
            assertEquals(250, outcome.suppressed().size());
        }

        @Test
        @DisplayName("a bulk summary spans subjects, so coalescing per subject would not have caught it")
        void bulkCollapsesAcrossSubjects() {
            var renderer = new RecordingRenderer();
            var bulk = new UUID(163, 2);
            // Two subjects, twelve minutes apart: outside the coalescing window and in different groups.
            var first = event(new UUID(166, 1), SUBJECT, "assigned", T0, bulk);
            var second = event(new UUID(166, 2), OTHER_SUBJECT, "assigned",
                    T0.plus(Duration.ofMinutes(12)), bulk);

            var outcome = NotificationDispatch.dispatch(List.of(first, second),
                    Map.of(first.eventId(), Set.of(RECIPIENT), second.eventId(), Set.of(RECIPIENT)),
                    (r, s) -> true, renderer);

            assertEquals(1, outcome.toDeliver().size(),
                    "a large bulk operation spans more than sixty seconds, so per-subject coalescing alone "
                            + "would let it through as one message per item");
        }

        @Test
        @DisplayName("a bulk summary counts only the subjects the recipient can still see")
        void bulkSummaryRespectsScope() {
            var renderer = new RecordingRenderer();
            var bulk = new UUID(163, 3);
            var visible = event(new UUID(167, 1), SUBJECT, "assigned", T0, bulk);
            var hidden = event(new UUID(167, 2), OTHER_SUBJECT, "assigned", T0.plusSeconds(5), bulk);

            var outcome = NotificationDispatch.dispatch(List.of(visible, hidden),
                    Map.of(visible.eventId(), Set.of(RECIPIENT), hidden.eventId(), Set.of(RECIPIENT)),
                    (recipient, subject) -> subject.equals(SUBJECT), renderer);

            assertEquals(1, outcome.toDeliver().size());
            assertTrue(outcome.toDeliver().get(0).body().contains("1 item(s)"),
                    "'2 items updated' when the recipient can see one discloses the existence of the other "
                            + "through a count");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-NTF-026 to -028 — escalation")
    class Escalation {

        private static final Map<EscalationChain.TargetKind, Set<UUID>> RESOLVED = Map.of(
                EscalationChain.TargetKind.ASSIGNEE, Set.of(new UUID(170, 1)),
                EscalationChain.TargetKind.ACCOUNTABLE_OWNER, Set.of(new UUID(170, 2)),
                EscalationChain.TargetKind.NEAREST_ANCESTOR_OWNER, Set.of(new UUID(170, 3)),
                EscalationChain.TargetKind.PROGRAM_OWNER, Set.of(new UUID(170, 4)),
                EscalationChain.TargetKind.BLOCKING_PARTY, Set.of(new UUID(170, 9)));

        @Test
        @DisplayName("PRD-NTF-027: a step fires once, so a recomputation does not re-fire the chain")
        void stepsFireOnce() {
            var firstRun = EscalationChain.fire(EscalationChain.DEFAULT_SERVICE_LEVEL_CHAIN,
                    new BigDecimal("0.80"), Optional.empty(), Optional.empty(), RESOLVED::get);
            assertEquals(2, firstRun.size(), "0.50 and 0.75 have both been passed");

            var afterRestart = EscalationChain.fire(EscalationChain.DEFAULT_SERVICE_LEVEL_CHAIN,
                    new BigDecimal("0.80"), Optional.of(new BigDecimal("0.75")), Optional.empty(),
                    RESOLVED::get);
            assertTrue(afterRestart.isEmpty(),
                    "a recipient who received four escalations for one item stops reading them "
                            + "(PRD-NTF-027)");
        }

        @Test
        @DisplayName("PRD-NTF-028: targets resolve at fire time, not at clock start")
        void targetsResolveAtFireTime() {
            var newOwner = new UUID(171, 1);
            var firings = EscalationChain.fire(EscalationChain.DEFAULT_SERVICE_LEVEL_CHAIN,
                    new BigDecimal("1.00"), Optional.of(new BigDecimal("0.75")), Optional.empty(),
                    kind -> kind == EscalationChain.TargetKind.ACCOUNTABLE_OWNER
                            ? Set.of(newOwner) : RESOLVED.get(kind));

            assertTrue(firings.get(0).principals().contains(newOwner),
                    "resolving at clock start would escalate to someone who is no longer responsible");
        }

        @Test
        @DisplayName("PRD-NTF-026: a pause for the requester suppresses the chain and escalates the blocker")
        void pausedForRequesterEscalatesTheBlocker() {
            var firings = EscalationChain.fire(EscalationChain.DEFAULT_SERVICE_LEVEL_CHAIN,
                    new BigDecimal("1.00"), Optional.empty(),
                    Optional.of(EscalationChain.PauseAttribution.REQUESTER), RESOLVED::get);

            assertFalse(firings.isEmpty(),
                    "suppressing WITHOUT the separate chain means a blocked item escalates to nobody, which "
                            + "is how a request waits four months");
            for (var firing : firings) {
                assertTrue(firing.againstBlockingParty());
                assertEquals(Set.of(new UUID(170, 9)), firing.principals(),
                        "escalating the accountable team for a delay attributable elsewhere destroys the "
                                + "credibility of every subsequent escalation (PP-6)");
            }
        }

        @Test
        @DisplayName("a pause attributed to the security function does not suppress")
        void securityFunctionPauseStillEscalates() {
            var firings = EscalationChain.fire(EscalationChain.DEFAULT_SERVICE_LEVEL_CHAIN,
                    new BigDecimal("0.60"), Optional.empty(),
                    Optional.of(EscalationChain.PauseAttribution.SECURITY_FUNCTION), RESOLVED::get);

            assertEquals(1, firings.size());
            assertFalse(firings.get(0).againstBlockingParty(),
                    "the security function is escalated against too — the platform can be blamed");
        }

        @Test
        @DisplayName("the default chain matches DOC-13 section 8")
        void defaultChainMatchesTheDocument() {
            assertEquals(4, EscalationChain.DEFAULT_SERVICE_LEVEL_CHAIN.size());
            assertEquals(new BigDecimal("1.00"),
                    EscalationChain.DEFAULT_SERVICE_LEVEL_CHAIN.get(2).atBudgetRatio(),
                    "breach is a step in the chain, not the end of it — 2.00 exists because an item twice "
                            + "over its budget is a different conversation");
            assertEquals(Set.of(EscalationChain.TargetKind.PROGRAM_OWNER),
                    EscalationChain.DEFAULT_SERVICE_LEVEL_CHAIN.get(3).targets());
        }

        @Test
        @DisplayName("a step with no target cannot be configured")
        void stepsMustNotifySomebody() {
            assertThrows(IllegalArgumentException.class,
                    () -> new EscalationChain.Step(new BigDecimal("0.5"), Set.of()),
                    "a step with no target notifies nobody and reads as one");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-NTF-037 to -040 — inbound association")
    class Inbound {

        /** Stands in for the quoted-history stripper of PRD-NTF-039. */
        private static String strip(String body) {
            return body.split("\n>", 2)[0];
        }

        private static InboundAssociation.ReplyToken token(Instant issuedAt) {
            return new InboundAssociation.ReplyToken(TOKEN, SUBJECT, RECIPIENT, new UUID(180, 1), issuedAt);
        }

        @Test
        @DisplayName("PRD-NTF-040: an unassociable reply returns a failure to the sender")
        void unassociableReplyFails() {
            var result = InboundAssociation.associate(Optional.empty(), "Yes, go ahead", T0, Inbound::strip);

            assertFalse(result.associated());
            assertTrue(result.failureToSender().orElseThrow().contains("NOT been recorded"),
                    "the population this serves replies by reflex and believes they responded; a silently "
                            + "discarded reply produces a stalled request whose requester believes they "
                            + "answered (PRD-NTF-040)");
            assertTrue(result.failureToSender().orElseThrow().contains("Nobody has seen this message"),
                    "and the sender is told plainly, because the alternative is that they wait");
        }

        @Test
        @DisplayName("the result has no third state a caller could ignore")
        void resultHasNoSilentDiscard() {
            assertThrows(IllegalArgumentException.class,
                    () -> new InboundAssociation.Result(Optional.empty(), Optional.empty()),
                    "neither is the silent discard PRD-NTF-040 forbids");
            assertThrows(IllegalArgumentException.class,
                    () -> new InboundAssociation.Result(
                            Optional.of(new InboundAssociation.AssociatedComment(SUBJECT, RECIPIENT, "x",
                                    true)),
                            Optional.of("also failed")));
        }

        @Test
        @DisplayName("PRD-NTF-037: association is by token, never by subject line or sender")
        void associationIsByTokenOnly() {
            for (Method m : InboundAssociation.class.getMethods()) {
                for (Class<?> parameter : m.getParameterTypes()) {
                    String name = parameter.getSimpleName().toLowerCase(Locale.ROOT);
                    assertFalse(name.contains("subjectline") || name.contains("senderaddress"),
                            "found " + parameter.getSimpleName() + ". A subject line carrying [WRK-1042] is "
                                    + "the conventional design; it means anybody who can send mail can post "
                                    + "on any item whose code they can guess, and item codes are sequential.");
                }
            }
        }

        @Test
        @DisplayName("PRD-NTF-038: the token expires and authorizes exactly one action on one item")
        void tokenIsBoundedAndSinglePurpose() {
            var replyToken = token(T0);
            assertTrue(replyToken.authorizes(SUBJECT, T0.plus(Duration.ofDays(1))));
            assertFalse(replyToken.authorizes(OTHER_SUBJECT, T0.plus(Duration.ofDays(1))),
                    "confining it to one item bounds the consequence of the token being forwarded, which it "
                            + "will be");
            assertFalse(replyToken.authorizes(SUBJECT, T0.plus(Duration.ofDays(31))));

            for (Method m : InboundAssociation.ReplyToken.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("read") || name.contains("permission") || name.contains("grant"),
                        "found " + m.getName() + ". A token that could be asked 'may this principal read the "
                                + "item' would eventually be used that way (PRD-NTF-038).");
            }
        }

        @Test
        @DisplayName("a short token cannot be constructed")
        void tokenMustBeUnguessable() {
            assertThrows(IllegalArgumentException.class,
                    () -> new InboundAssociation.ReplyToken("abc", SUBJECT, RECIPIENT, new UUID(180, 1), T0));
        }

        @Test
        @DisplayName("PRD-NTF-039: quoted history is stripped and the comment is inbound-attributed")
        void quotedHistoryStrippedAndAttributed() {
            var result = InboundAssociation.associate(Optional.of(token(T0)),
                    "Yes, deploy it tonight.\n> On 4 August, the platform wrote:\n> Finding WRK-1 ...",
                    T0.plusSeconds(60), Inbound::strip);

            var comment = result.comment().orElseThrow();
            assertEquals("Yes, deploy it tonight.", comment.body(),
                    "without stripping, a reply carries the entire notification back — including detail the "
                            + "recipient could see then and may not now");
            assertTrue(comment.inboundOrigin(),
                    "an inbound-attributed comment carries weaker identity assurance than an authenticated "
                            + "one, and H11 requires the marker to reach the reader");
            assertEquals(RECIPIENT, comment.authorId(),
                    "attributed to the principal the TOKEN identifies, not to the sender address");
        }

        @Test
        @DisplayName("a comment from the inbound path cannot be marked as anything else")
        void inboundOriginCannotBeCleared() {
            assertThrows(IllegalArgumentException.class,
                    () -> new InboundAssociation.AssociatedComment(SUBJECT, RECIPIENT, "x", false),
                    "marking it otherwise would give an emailed reply the identity assurance of an "
                            + "authenticated one");
        }

        @Test
        @DisplayName("a reply that is only quoted history fails rather than posting an empty comment")
        void emptyReplyFails() {
            var result = InboundAssociation.associate(Optional.of(token(T0)),
                    "\n> On 4 August, the platform wrote:\n> Finding WRK-1 ...", T0.plusSeconds(60), Inbound::strip);

            assertFalse(result.associated());
            assertTrue(result.failureToSender().orElseThrow().contains("no new text"),
                    "recording it would appear to answer a question it did not answer, and the requester "
                            + "would stop chasing");
        }
    }
}
