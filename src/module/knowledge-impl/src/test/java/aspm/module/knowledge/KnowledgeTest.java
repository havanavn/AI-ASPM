package aspm.module.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.module.knowledge.domain.KnowledgeArticle;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Knowledge. {@code INV-KBS-01} to {@code INV-KBS-03}. */
class KnowledgeTest {

    private static final Instant T0 = Instant.parse("2026-08-05T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 5);
    private static final UUID TENANT = new UUID(210, 1);
    private static final UUID OWNER = new UUID(210, 2);

    private static KnowledgeArticle article(LocalDate reviewDueBy) {
        return new KnowledgeArticle(UUID.randomUUID(), TENANT, "fixing-bola", "Fixing broken object-level "
                + "authorization", new KnowledgeArticle.ConstrainedContent(
                        List.of("Re-validate the identifier server-side."),
                        List.of(new KnowledgeArticle.ConstrainedContent.CodeBlock("assertInScope(id);",
                                "java"))),
                OWNER, reviewDueBy);
    }

    @Test
    @DisplayName("INV-KBS-03: an owner and a review date are both required")
    void ownerAndReviewDateRequired() {
        var noOwner = assertThrows(NullPointerException.class,
                () -> new KnowledgeArticle(UUID.randomUUID(), TENANT, "s", "t",
                        new KnowledgeArticle.ConstrainedContent(List.of("x"), List.of()), null, TODAY));
        assertTrue(noOwner.getMessage().contains("correct three years ago"),
                "guidance that is wrong is worse than absent: it carries organizational authority");

        var noDate = assertThrows(NullPointerException.class,
                () -> new KnowledgeArticle(UUID.randomUUID(), TENANT, "s", "t",
                        new KnowledgeArticle.ConstrainedContent(List.of("x"), List.of()), OWNER, null));
        assertTrue(noDate.getMessage().contains("never wrong"),
                "an article with no review date is simply old, which reads the same as current to a reader "
                        + "who was not there");
    }

    @Test
    @DisplayName("INV-KBS-02: an article is tenant-scoped")
    void tenantScoped() {
        var ex = assertThrows(NullPointerException.class,
                () -> new KnowledgeArticle(UUID.randomUUID(), null, "s", "t",
                        new KnowledgeArticle.ConstrainedContent(List.of("x"), List.of()), OWNER, TODAY));
        assertTrue(ex.getMessage().contains("architectural detail"),
                "an article explaining how to fix an authorization pattern names the framework, the services, "
                        + "and frequently the defect that prompted it");
    }

    @Test
    @DisplayName("INV-KBS-01: content is a node list, with no markup entry point")
    void contentIsConstrained() {
        for (Method m : KnowledgeArticle.ConstrainedContent.class.getMethods()) {
            String name = m.getName().toLowerCase(Locale.ROOT);
            assertFalse(name.contains("html") || name.contains("sanitize") || name.contains("parse"),
                    "found " + m.getName() + "; content is tenant-authored input rendered to other users and "
                            + "is a stored cross-site scripting vector (INV-KBS-01)");
        }
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeArticle.ConstrainedContent.CodeBlock("x", "java\" onload=\"alert(1)"),
                "the language hint reaches a class attribute, and code content is the one thing deliberately "
                        + "not escaped");
    }

    @Test
    @DisplayName("staleness is derived from the date, not from a sweep")
    void stalenessIsDerived() {
        var stale = article(TODAY.minusDays(1));
        stale.publish(T0);

        assertEquals(KnowledgeArticle.State.REVIEW_OVERDUE, stale.stateOn(TODAY),
                "a knowledge base whose staleness depends on a cron job is one where the guidance is stale "
                        + "and the label is not");
        assertTrue(stale.stalenessQualifier(TODAY).orElseThrow().contains("the owner is the person to ask"));

        var current = article(TODAY.plusMonths(6));
        current.publish(T0);
        assertEquals(KnowledgeArticle.State.PUBLISHED, current.stateOn(TODAY));
        assertTrue(current.stalenessQualifier(TODAY).isEmpty(),
                "and a current article carries no warning, or the warning stops meaning anything");
    }

    @Test
    @DisplayName("an overdue article stays readable, with a qualifier")
    void overdueArticlesAreNotWithdrawn() {
        var stale = article(TODAY.minusMonths(2));
        stale.publish(T0);
        assertTrue(stale.stalenessQualifier(TODAY).isPresent());
        assertEquals(KnowledgeArticle.State.REVIEW_OVERDUE, stale.stateOn(TODAY),
                "a dead link sends the reader to a search engine, and what they find there is not "
                        + "tenant-specific and not reviewed either");
    }

    @Test
    @DisplayName("a review must move the deadline forward")
    void reviewMovesTheDeadline() {
        var subject = article(TODAY.plusMonths(1));
        subject.publish(T0);
        assertThrows(IllegalArgumentException.class,
                () -> subject.recordReview(OWNER, TODAY.plusMonths(1), T0),
                "a review that changed nothing is a click, and the mechanism depends on somebody having read "
                        + "the article");
        subject.recordReview(OWNER, TODAY.plusMonths(12), T0);
        assertEquals(TODAY.plusMonths(12), subject.reviewDueBy());
    }

    @Test
    @DisplayName("an empty article cannot be created")
    void emptyArticleRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeArticle.ConstrainedContent(List.of(), List.of()),
                "an empty article publishes nothing and appears in search");
    }
}
