package aspm.module.workmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.workmanagement.domain.ActivityEntry;
import aspm.module.workmanagement.domain.ActorType;
import aspm.module.workmanagement.domain.Collaboration;
import aspm.module.workmanagement.domain.Comment;
import aspm.module.workmanagement.domain.ConstrainedRichText;
import aspm.module.workmanagement.domain.MentionResolution;
import aspm.module.workmanagement.domain.ParticipantRole;
import aspm.module.workmanagement.domain.TransitionLog;
import aspm.module.workmanagement.domain.WorkItemLink;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Prompt 9 session 2 — collaboration: {@code PRD-WRK-019} and {@code INV-WRK-07} through {@code -10}. */
class CollaborationTest {

    private static final Instant T0 = Instant.parse("2026-07-01T09:00:00Z");
    private static final UUID ITEM = new UUID(90, 1);
    private static final UUID AUTHOR = new UUID(94, 1);
    private static final UUID OTHER = new UUID(94, 2);
    private static final UUID OUT_OF_SCOPE = new UUID(94, 9);
    private static final UUID STATE_OPEN = new UUID(91, 1);
    private static final UUID STATE_DONE = new UUID(91, 4);

    private static ConstrainedRichText text(String value) {
        return ConstrainedRichText.of(List.of(
                new ConstrainedRichText.Node.Paragraph(List.of(
                        new ConstrainedRichText.Node.Text(value)))));
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-WRK-10 — constrained rich text, not sanitized markup")
    class RichText {

        @Test
        @DisplayName("there is no way to construct a body from markup")
        void noMarkupEntryPoint() {
            for (Method m : ConstrainedRichText.class.getMethods()) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.contains("sanitize") || name.contains("html") || name.contains("parse"),
                        "found " + m.getName() + ". Sanitization is a denylist wearing a different name: the "
                                + "input space is HTML and the attacker gets to explore all of it. The "
                                + "allowlist works because content arrives as nodes and the renderer emits "
                                + "markup — the direction of the conversion is the control (INV-WRK-10).");
            }
            for (var constructor : ConstrainedRichText.class.getConstructors()) {
                assertEquals(0, constructor.getParameterCount(),
                        "a public constructor taking a string would put the open input space back");
            }
        }

        @Test
        @DisplayName("text is never interpreted: a script tag is content, not markup")
        void textIsNotMarkup() {
            var body = text("<script>fetch('//evil')</script>");
            assertEquals("<script>fetch('//evil')</script>", body.plainText(),
                    "it survives as literal text because there is no node type that could represent it as "
                            + "markup — a bypass would need a defect in a grammar with seven productions");
        }

        @Test
        @DisplayName("code content is preserved verbatim, because security discussion pastes payloads")
        void codeIsPreservedVerbatim() {
            String payload = "'; DROP TABLE finding; --";
            var body = ConstrainedRichText.of(List.of(
                    new ConstrainedRichText.Node.Code(payload, "sql", true)));
            assertEquals(payload, body.plainText(),
                    "a comment field that mangled a payload would be unusable for the work this platform "
                            + "exists to support");
        }

        @Test
        @DisplayName("the language hint is constrained: it reaches a class attribute")
        void languageHintIsConstrained() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ConstrainedRichText.Node.Code("x", "sql\" onload=\"alert(1)", false),
                    "an unconstrained value is an injection point in the one node whose content is "
                            + "deliberately not escaped");
            assertEquals("c++", new ConstrainedRichText.Node.Code("x", "c++", false).language(),
                    "and an ordinary language identifier still works");
        }

        @Test
        @DisplayName("an arbitrary URL cannot be expressed; only an item reference")
        void noArbitraryLinks() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ConstrainedRichText.Node.ItemReference("https://evil.example/login"),
                    "an arbitrary URL in a comment is a phishing vector inside a trusted surface, aimed at the "
                            + "population with the narrowest permissions and the least training (PP-7)");
            assertEquals("WRK-104", new ConstrainedRichText.Node.ItemReference("WRK-104").itemCode());
        }

        @Test
        @DisplayName("depth and node count are bounded")
        void documentIsBounded() {
            ConstrainedRichText.Node deep = new ConstrainedRichText.Node.Text("x");
            for (int i = 0; i < ConstrainedRichText.MAX_DEPTH + 2; i++) {
                deep = new ConstrainedRichText.Node.Strong(List.of(deep));
            }
            final ConstrainedRichText.Node tooDeep = deep;
            assertThrows(IllegalArgumentException.class, () -> ConstrainedRichText.of(List.of(tooDeep)),
                    "a deeply nested document is a renderer stack-overflow vector rather than a formatting "
                            + "choice");

            List<ConstrainedRichText.Node> many = new java.util.ArrayList<>();
            for (int i = 0; i <= ConstrainedRichText.MAX_NODES; i++) {
                many.add(new ConstrainedRichText.Node.Text("x"));
            }
            assertThrows(IllegalArgumentException.class, () -> ConstrainedRichText.of(many),
                    "one comment must not make an item view unloadable for everyone who opens it");
        }

        @Test
        @DisplayName("PRD-AIC-008: text leaves the type through one named, greppable method")
        void textExitIsGreppable() {
            long exits = java.util.Arrays.stream(ConstrainedRichText.class.getMethods())
                    .filter(m -> m.getReturnType() == String.class && m.getParameterCount() == 0)
                    .filter(m -> m.getDeclaringClass() != Object.class)
                    .count();
            assertEquals(1, exits,
                    "'which code paths can send a comment to a model' is a question somebody will have to "
                            + "answer, and PRD-AIC-008 excludes comment content from AI context unless the "
                            + "tenant permitted it");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-WRK-08 — editable with retained history, never hard-deletable")
    class Comments {

        private Comment posted() {
            return Comment.post(UUID.randomUUID(), ITEM, null, text("the fix is in release 4.2"), AUTHOR, T0);
        }

        @Test
        @DisplayName("no delete path exists on a comment at any privilege")
        void noDeletePath() {
            for (Method m : Comment.class.getMethods()) {
                if (m.getDeclaringClass() == Object.class) {
                    continue;
                }
                String name = m.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.startsWith("delete") || name.startsWith("remove")
                                || name.startsWith("purge") || name.startsWith("clear"),
                        "found Comment." + m.getName() + ". A comment thread on a security finding is audit "
                                + "evidence, and selective deletion permits reconstruction of a different "
                                + "history (INV-WRK-08).");
            }
        }

        @Test
        @DisplayName("an edit retains the previous version")
        void editRetainsHistory() {
            var comment = posted();
            comment.edit(text("the fix is in release 4.3"), AUTHOR, T0.plusSeconds(600));

            assertEquals(1, comment.editCount());
            assertEquals("the fix is in release 4.2", comment.revisions().get(0).body().plainText(),
                    "without retention, editing is deletion plus insertion — the capability the invariant "
                            + "withholds");
            assertEquals("the fix is in release 4.3", comment.body().plainText());
        }

        @Test
        @DisplayName("an unchanged edit records no revision")
        void unchangedEditIsNotARevision() {
            var comment = posted();
            comment.edit(text("the fix is in release 4.2"), AUTHOR, T0.plusSeconds(600));
            assertEquals(0, comment.editCount(),
                    "a spurious revision would put a spurious entry in the timeline");
        }

        @Test
        @DisplayName("editing to empty is refused — that is deletion by another route")
        void editToEmptyRefused() {
            var comment = posted();
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> comment.edit(text("   "), AUTHOR, T0.plusSeconds(60)));
            assertTrue(ex.getMessage().contains("redact"),
                    "the diagnosis must point at the permitted route; got " + ex.getMessage());
        }

        @Test
        @DisplayName("redaction keeps the original, leaves a visible record, and requires a reason")
        void redactionIsVisibleAndKeepsTheOriginal() {
            var comment = posted();
            assertThrows(IllegalArgumentException.class,
                    () -> comment.redact(OTHER, "  ", T0.plusSeconds(60)),
                    "a redaction without a stated reason is a deletion that happens to leave a marker");

            comment.redact(OTHER, "contained a live credential", T0.plusSeconds(60));

            assertTrue(comment.redacted());
            assertEquals(Optional.of(OTHER), comment.redactedBy());
            assertEquals(Optional.of("contained a live credential"), comment.redactionReason());
            assertTrue(comment.body().plainText().contains("redacted"),
                    "a reader must see that something was removed: a thread with a visible gap can be reasoned "
                            + "about, a thread with an invisible one cannot");
            assertEquals("the fix is in release 4.2",
                    comment.revisions().get(comment.revisions().size() - 1).body().plainText(),
                    "a redaction that discarded the original would be the selective deletion the invariant "
                            + "forbids, with extra steps");
        }

        @Test
        @DisplayName("a redacted comment is not editable and takes no new attachments")
        void redactedCommentIsFrozen() {
            var comment = posted();
            comment.redact(OTHER, "contained a live credential", T0.plusSeconds(60));
            assertThrows(IllegalStateException.class,
                    () -> comment.edit(text("something else"), OTHER, T0.plusSeconds(120)),
                    "an edit after redaction would place new content under the original author's name");
            assertThrows(IllegalStateException.class, () -> comment.attach(UUID.randomUUID()));
            assertThrows(IllegalStateException.class,
                    () -> comment.redact(OTHER, "again", T0.plusSeconds(180)));
        }

        @Test
        @DisplayName("DOC-26 section 8: a migrated comment is flagged and carries its external identifier")
        void migrationAuthorshipIsFlagged() {
            var imported = Comment.migrated(UUID.randomUUID(), ITEM, null,
                    text("agreed, accepting the risk"), AUTHOR, T0.minus(Duration.ofDays(400)), "JIRA-1042");
            assertTrue(imported.migrated());
            assertEquals(Optional.of("JIRA-1042"), imported.migratedFromExternalId());

            assertFalse(posted().migrated(),
                    "the capability that preserves history could fabricate a record of a decision never made; "
                            + "the flag is the control");
            assertThrows(NullPointerException.class,
                    () -> Comment.migrated(UUID.randomUUID(), ITEM, null, text("x"), AUTHOR, T0, null));
        }

        @Test
        @DisplayName("an empty comment cannot be posted")
        void emptyCommentRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> Comment.post(UUID.randomUUID(), ITEM, null, text("  "), AUTHOR, T0),
                    "it produces a notification and a timeline entry for no content, which trains readers to "
                            + "ignore both");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-WRK-09 — mentions do not disclose principals outside scope")
    class Mentions {

        /** Everyone is visible except OUT_OF_SCOPE. */
        private final MentionResolution resolution = new MentionResolution(
                (mentioner, candidate) -> !candidate.equals(OUT_OF_SCOPE));

        private ConstrainedRichText mentioning(UUID principal) {
            return ConstrainedRichText.of(List.of(
                    new ConstrainedRichText.Node.Paragraph(List.of(
                            new ConstrainedRichText.Node.Text("please look at this "),
                            new ConstrainedRichText.Node.Mention(principal)))));
        }

        @Test
        @DisplayName("an out-of-scope mention is REJECTED at post time, not silently stripped")
        void outOfScopeMentionRejected() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> resolution.resolve(AUTHOR, mentioning(OUT_OF_SCOPE)));
            assertTrue(ex.getMessage().contains("PP-4"),
                    "a filtered picker is a usability feature, never an authorization control — the request "
                            + "carries whatever identifier the client chose to put in it");
            assertFalse(ex.getMessage().contains(OUT_OF_SCOPE.toString()),
                    "and the identifiers are withheld, because naming them would confirm which of a submitted "
                            + "list exist. Got: " + ex.getMessage());
        }

        @Test
        @DisplayName("an in-scope mention resolves")
        void inScopeMentionResolves() {
            assertEquals(Set.of(OTHER), resolution.resolve(AUTHOR, mentioning(OTHER)),
                    "the control must permit the legitimate case, or it proves nothing");
        }

        @Test
        @DisplayName("autocomplete filters by scope and refuses a trivial prefix")
        void autocompleteIsScopedAndBounded() {
            var candidates = List.of(new MentionResolution.Suggestion(OTHER, "visible person"),
                    new MentionResolution.Suggestion(OUT_OF_SCOPE, "hidden person"));

            assertEquals(List.of(), resolution.autocomplete(AUTHOR, "a", candidates),
                    "an empty or trivial prefix returning the visible set is a directory listing even when "
                            + "correctly scoped");
            var offered = resolution.autocomplete(AUTHOR, "per", candidates);
            assertEquals(1, offered.size());
            assertEquals(OTHER, offered.get(0).principalId(),
                    "mention autocomplete is a user enumeration surface requiring scope filtering");
        }

        @Test
        @DisplayName("autocomplete is capped, so a broad-scope principal's picker is not a bulk export")
        void autocompleteIsCapped() {
            List<MentionResolution.Suggestion> many = new java.util.ArrayList<>();
            for (int i = 0; i < MentionResolution.MAX_SUGGESTIONS * 3; i++) {
                many.add(new MentionResolution.Suggestion(new UUID(95, i), "person " + i));
            }
            assertEquals(MentionResolution.MAX_SUGGESTIONS,
                    resolution.autocomplete(AUTHOR, "per", many).size());
        }

        @Test
        @DisplayName("visibility is re-checked at render time, because scope changes")
        void renderTimeRecheck() {
            assertFalse(resolution.renderableTo(AUTHOR, OUT_OF_SCOPE),
                    "a principal legitimately mentioned last quarter may be outside a later reader's scope, "
                            + "and re-checking is what keeps a historical comment from becoming a disclosure");
            assertTrue(resolution.renderableTo(AUTHOR, OTHER));
        }

        @Test
        @DisplayName("a mention carries an identifier, not a copied display name")
        void mentionCarriesIdentifier() {
            for (var component : ConstrainedRichText.Node.Mention.class.getRecordComponents()) {
                assertEquals(UUID.class, component.getType(),
                        "a name copied into the body at authoring time goes stale, and a stale mention in "
                                + "audit evidence reads as a claim about who was involved");
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("PRD-WRK-019 — watchers, read state, and the unified timeline")
    class Timeline {

        private Collaboration collaboration() {
            var c = new Collaboration(ITEM);
            c.addParticipant(OTHER, ParticipantRole.SUPPORT);
            c.watch(new UUID(96, 1), T0);
            return c;
        }

        @Test
        @DisplayName("subscribe and unsubscribe are both explicit, and unsubscribe removes")
        void watchingIsExplicitBothWays() {
            var c = collaboration();
            var watcher = new UUID(96, 1);
            assertTrue(c.watching(watcher));
            assertTrue(c.unwatch(watcher));
            assertFalse(c.watching(watcher),
                    "a suppression flag would be ignored by any code path that read the watcher list without "
                            + "checking it, and there will be several such paths");
            assertFalse(c.unwatch(watcher), "and unsubscribing twice is not an error");
        }

        @Test
        @DisplayName("the notification audience excludes SHADOW participants")
        void shadowsAreNotNotified() {
            var c = collaboration();
            var shadow = new UUID(96, 2);
            c.addParticipant(shadow, ParticipantRole.SHADOW);

            var audience = c.notificationAudience(AUTHOR);
            assertTrue(audience.contains(AUTHOR) && audience.contains(OTHER));
            assertFalse(audience.contains(shadow),
                    "a shadow is observing to learn and carries no expectation of action; including them in "
                            + "every notification is how a learning mechanism becomes a mail filter rule");
        }

        @Test
        @DisplayName("the audience is NOT scope-filtered here — notification applies the reader's scope")
        void audienceIsNotScopeFilteredHere() {
            var c = new Collaboration(ITEM);
            c.watch(OUT_OF_SCOPE, T0);
            assertTrue(c.notificationAudience(null).contains(OUT_OF_SCOPE),
                    "notification is a subscriber to domain events (PRD-WRK-037) and applies the reader's own "
                            + "scope on delivery; filtering here would put the decision in the wrong module "
                            + "and produce two places that could disagree");
        }

        @Test
        @DisplayName("read state is monotonic, so an out-of-order write cannot regress the mark")
        void readStateIsMonotonic() {
            var c = collaboration();
            c.markRead(AUTHOR, T0.plusSeconds(600));
            c.markRead(AUTHOR, T0.plusSeconds(60));
            assertEquals(Optional.of(T0.plusSeconds(600)), c.lastReadAt(AUTHOR),
                    "two tabs reading in either order must not resurrect notifications already dismissed, and "
                            + "monotonicity is what makes the write-behind cache DOC-04 section 16.5 suggests "
                            + "safe");
        }

        @Test
        @DisplayName("a principal who never opened the item has everything unread, not nothing")
        void neverReadMeansAllUnread() {
            var c = collaboration();
            var entries = List.of(new ActivityEntry(ActivityEntry.Kind.COMMENT, T0.plusSeconds(60),
                    Optional.of(OTHER), ActorType.USER, Optional.empty(), "commented"));
            assertEquals(1, c.unreadFor(AUTHOR, entries).size(),
                    "treating 'never read' as 'all read' is the arithmetic that hides an item from the person "
                            + "it was just assigned to");
        }

        @Test
        @DisplayName("your own actions are not unread to you")
        void ownActionsAreNotUnread() {
            var c = collaboration();
            var entries = List.of(
                    new ActivityEntry(ActivityEntry.Kind.COMMENT, T0.plusSeconds(60), Optional.of(AUTHOR),
                            ActorType.USER, Optional.empty(), "commented"),
                    new ActivityEntry(ActivityEntry.Kind.COMMENT, T0.plusSeconds(120), Optional.of(OTHER),
                            ActorType.USER, Optional.empty(), "commented"));
            var unread = c.unreadFor(AUTHOR, entries);
            assertEquals(1, unread.size(),
                    "an inbox reporting your own comment as unread trains you to dismiss the count without "
                            + "reading it, and then the count is worth nothing");
            assertEquals(Optional.of(OTHER), unread.get(0).actorId());
        }

        @Test
        @DisplayName("the timeline interleaves transitions and comments in chronological order")
        void timelineInterleaves() {
            var log = new TransitionLog(ITEM);
            log.recordCreation(UUID.randomUUID(), STATE_OPEN, AUTHOR, ActorType.USER, T0, true);
            log.append(UUID.randomUUID(), STATE_DONE, "complete", AUTHOR, ActorType.USER, null, null,
                    T0.plus(Duration.ofHours(4)), true, null, false, false);

            var comment = Comment.post(UUID.randomUUID(), ITEM, null, text("looking at it"), OTHER,
                    T0.plus(Duration.ofHours(2)));

            var timeline = Collaboration.timeline(log, List.of(comment), List.of());
            assertEquals(3, timeline.size());
            assertEquals(List.of(ActivityEntry.Kind.STATE_CHANGE, ActivityEntry.Kind.COMMENT,
                            ActivityEntry.Kind.STATE_CHANGE),
                    timeline.stream().map(ActivityEntry::kind).toList(),
                    "a state history and a comment thread as separate views cannot answer what happened, in "
                            + "what order, and why");
        }

        @Test
        @DisplayName("a redaction appears in the timeline as a redaction, not as an absence")
        void redactionIsVisibleInTheTimeline() {
            var log = new TransitionLog(ITEM);
            log.recordCreation(UUID.randomUUID(), STATE_OPEN, AUTHOR, ActorType.USER, T0, true);
            var comment = Comment.post(UUID.randomUUID(), ITEM, null, text("here is the token: abc"), OTHER,
                    T0.plusSeconds(60));
            comment.redact(AUTHOR, "contained a live credential", T0.plusSeconds(120));

            var timeline = Collaboration.timeline(log, List.of(comment), List.of());
            assertTrue(timeline.stream().anyMatch(e -> e.kind() == ActivityEntry.Kind.REDACTION),
                    "a timeline that silently omitted redacted comments would let selective redaction "
                            + "reconstruct a different history — the thing INV-WRK-08 exists to stop");
            assertTrue(timeline.stream().anyMatch(e -> e.summary().contains("live credential")),
                    "and the stated reason travels with it");
            assertEquals(3, timeline.size(),
                    "the redaction's snapshot of the original is not ALSO listed as an edit that never "
                            + "happened");
        }

        @Test
        @DisplayName("an automated transition renders as an automated action, not a human one")
        void automationIsDistinguishableInTheTimeline() {
            var log = new TransitionLog(ITEM);
            log.recordCreation(UUID.randomUUID(), STATE_OPEN, AUTHOR, ActorType.USER, T0, true);
            log.append(UUID.randomUUID(), STATE_DONE, "auto_close", AUTHOR, ActorType.AUTOMATION,
                    new UUID(97, 1), null, T0.plusSeconds(60), true, null, false, false);

            var timeline = Collaboration.timeline(log, List.of(), List.of());
            assertEquals(ActivityEntry.Kind.AUTOMATED_ACTION, timeline.get(1).kind(),
                    "an automated action rendered as a human one is how a reader concludes a person made a "
                            + "decision the platform made");
        }

        @Test
        @DisplayName("entries at the same instant order stably")
        void sameInstantOrdersStably() {
            var a = new ActivityEntry(ActivityEntry.Kind.STATE_CHANGE, T0, Optional.of(AUTHOR),
                    ActorType.USER, Optional.empty(), "'start'");
            var b = new ActivityEntry(ActivityEntry.Kind.COMMENT, T0, Optional.of(AUTHOR), ActorType.USER,
                    Optional.empty(), "commented");
            var forward = new java.util.ArrayList<>(List.of(a, b));
            var backward = new java.util.ArrayList<>(List.of(b, a));
            forward.sort(null);
            backward.sort(null);
            assertEquals(forward, backward,
                    "a timeline that reorders itself between two reads of the same item is one a reader stops "
                            + "trusting");
        }

        @Test
        @DisplayName("the timeline is built on read, so a new event kind needs no schema change")
        void timelineIsAProjection() {
            var log = new TransitionLog(ITEM);
            log.recordCreation(UUID.randomUUID(), STATE_OPEN, AUTHOR, ActorType.USER, T0, true);
            var external = new ActivityEntry(ActivityEntry.Kind.LINK_CHANGE, T0.plusSeconds(60),
                    Optional.of(AUTHOR), ActorType.USER, Optional.empty(), "linked WRK-104");

            var timeline = Collaboration.timeline(log, List.of(), List.of(external));
            assertEquals(2, timeline.size(),
                    "a new source is a new argument and a new Kind, with no timeline table to migrate "
                            + "(PRD-WRK-019 extensibility)");
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("INV-WRK-07 — links maintain their inverse automatically")
    class Links {

        private static final UUID A = new UUID(98, 1);
        private static final UUID B = new UUID(98, 2);
        private static final UUID C = new UUID(98, 3);

        @Test
        @DisplayName("creating a link produces both directions")
        void bothDirectionsWritten() {
            var pair = WorkItemLink.withInverse(A, B, WorkItemLink.LinkType.BLOCKS);
            assertEquals(2, pair.size());
            assertEquals(WorkItemLink.LinkType.BLOCKS, pair.get(0).linkType());
            assertEquals(WorkItemLink.LinkType.IS_BLOCKED_BY, pair.get(1).linkType());
            assertEquals(B, pair.get(1).fromItemId(),
                    "'what blocks this item' and 'what does this item block' are both frequent, and each "
                            + "direction must be independently indexable (DOC-04 section 16.6)");
        }

        @Test
        @DisplayName("every link type has an inverse, and the inverse of the inverse is itself")
        void inversesAreTotalAndInvolutive() {
            for (var type : WorkItemLink.LinkType.values()) {
                assertEquals(type, type.inverse().inverse(),
                        type + " does not round-trip; a link would change meaning each time it was rewritten");
            }
            assertTrue(WorkItemLink.LinkType.RELATES_TO.symmetric(),
                    "symmetric, and still two rows so a query finds it from either end without an OR across "
                            + "two columns");
            assertFalse(WorkItemLink.LinkType.BLOCKS.symmetric());
        }

        @Test
        @DisplayName("removal removes both directions")
        void removalIsAlsoBidirectional() {
            var forward = new WorkItemLink(A, B, WorkItemLink.LinkType.BLOCKS);
            assertEquals(List.of(forward, new WorkItemLink(B, A, WorkItemLink.LinkType.IS_BLOCKED_BY)),
                    forward.withInverse(),
                    "a half-removed link leaves the blocked-work queue and the item view disagreeing, with "
                            + "nobody able to say which is right");
        }

        @Test
        @DisplayName("a self-link is refused")
        void selfLinkRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WorkItemLink(A, A, WorkItemLink.LinkType.BLOCKS),
                    "a self-blocking item is a deadlock the blocked-work queue would report forever");
        }

        @Test
        @DisplayName("a blocking cycle is detected; a RELATES_TO cycle is not treated as one")
        void blockingCyclesDetected() {
            // A is blocked by B, B is blocked by C. Adding "C is blocked by A" closes the cycle.
            List<WorkItemLink> existing = List.of(
                    new WorkItemLink(A, B, WorkItemLink.LinkType.IS_BLOCKED_BY),
                    new WorkItemLink(B, C, WorkItemLink.LinkType.IS_BLOCKED_BY));

            assertTrue(WorkItemLink.wouldCreateBlockingCycle(
                            new WorkItemLink(C, A, WorkItemLink.LinkType.IS_BLOCKED_BY), existing),
                    "a cycle of blocking is a set of items none of which can ever start, and it presents as "
                            + "work that quietly never gets picked up rather than as an error anybody sees");
            assertFalse(WorkItemLink.wouldCreateBlockingCycle(
                            new WorkItemLink(C, A, WorkItemLink.LinkType.RELATES_TO), existing),
                    "a cycle of RELATES_TO is ordinary and frequently correct");
            assertFalse(WorkItemLink.wouldCreateBlockingCycle(
                            new WorkItemLink(A, C, WorkItemLink.LinkType.IS_BLOCKED_BY), existing),
                    "and a link that does not close a cycle must be permitted, or nothing could be linked");
        }
    }
}
