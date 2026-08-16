package aspm.module.knowledge.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A knowledge article. {@code INV-KBS-01} to {@code INV-KBS-03}, DOC-03 section 17.
 *
 * <p>Three invariants, and the third is the one that decides whether a knowledge base is worth having.
 *
 * <h2>{@code INV-KBS-03} — an owner and a review date, both mandatory</h2>
 *
 * <p>Guidance that is wrong is worse than absent: it carries organizational authority and directs engineers
 * toward a pattern that was correct three years ago. DOC-01 section 10.4 makes the same point about published
 * guidance being permission-gated "since a malicious or careless article could direct engineers toward an
 * insecure pattern at scale".
 *
 * <p>An article with no owner has nobody to ask, and one with no review date is never wrong — it is simply old,
 * which reads the same as current to a reader who was not there. Both are constructor parameters.
 *
 * <h2>{@code INV-KBS-02} — tenant-scoped, never shared</h2>
 *
 * <p>DOC-01 section 10.4: content "may embed internal architectural detail and is therefore tenant-scoped and
 * not shared across tenants". An article explaining how to fix an authorization pattern in <i>this</i>
 * organization's framework names that framework, its services, and frequently the defect that prompted the
 * article.
 */
public final class KnowledgeArticle {

    public enum State {
        DRAFT,
        PUBLISHED,
        /** Past its review date. Still readable — withdrawing it silently would leave a dead link. */
        REVIEW_OVERDUE,
        ARCHIVED
    }

    private final UUID id;
    private final UUID tenantId;
    private final String slug;
    private final ConstrainedContent content;
    private final UUID ownerPrincipalId;

    private String title;
    private LocalDate reviewDueBy;
    private State state = State.DRAFT;
    private Instant publishedAt;

    /**
     * Constrained rich text. {@code INV-KBS-01}, the same allowlist as {@code PRD-WRK-019}.
     *
     * <p>DOC-01 section 10.4: "Content is tenant-authored input rendered to other users and is a stored
     * cross-site scripting vector; the same constrained rich text allowlist as {@code PRD-WRK-019} applies."
     *
     * <p>Modelled as a node list here for the same reason the comment body is: content arrives as nodes and the
     * renderer emits markup, so there is no constructor taking markup for a sanitizer to be wrong about.
     */
    public record ConstrainedContent(List<String> paragraphs, List<CodeBlock> codeBlocks) {

        public record CodeBlock(String content, String language) {

            public CodeBlock {
                Objects.requireNonNull(content, "code content is required");
                Objects.requireNonNull(language, "a language is required, empty where unknown");
                if (!language.isEmpty() && !language.matches("[a-zA-Z0-9_+-]{1,24}")) {
                    throw new IllegalArgumentException(
                            "language '" + language + "' is not a short identifier; it reaches a class "
                                    + "attribute in the rendered output, and code content is the one thing "
                                    + "deliberately not escaped");
                }
            }
        }

        public ConstrainedContent {
            paragraphs = List.copyOf(Objects.requireNonNull(paragraphs, "paragraphs are required"));
            codeBlocks = List.copyOf(Objects.requireNonNull(codeBlocks, "code blocks are required"));
            if (paragraphs.isEmpty() && codeBlocks.isEmpty()) {
                throw new IllegalArgumentException("an empty article publishes nothing and appears in search");
            }
        }
    }

    public KnowledgeArticle(UUID id, UUID tenantId, String slug, String title, ConstrainedContent content,
            UUID ownerPrincipalId, LocalDate reviewDueBy) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.tenantId = Objects.requireNonNull(tenantId,
                "a tenant is required (INV-KBS-02). Content may embed internal architectural detail — an "
                        + "article explaining how to fix an authorization pattern names the framework, the "
                        + "services, and frequently the defect that prompted it.");
        this.slug = Objects.requireNonNull(slug, "a slug is required");
        this.title = Objects.requireNonNull(title, "a title is required");
        this.content = Objects.requireNonNull(content, "content is required");
        this.ownerPrincipalId = Objects.requireNonNull(ownerPrincipalId,
                "an owner is required (INV-KBS-03). An article with no owner has nobody to ask, and guidance "
                        + "that is wrong is worse than absent — it carries organizational authority and "
                        + "directs engineers toward a pattern that was correct three years ago.");
        this.reviewDueBy = Objects.requireNonNull(reviewDueBy,
                "a review date is required (INV-KBS-03). An article with none is never wrong; it is simply "
                        + "old, which reads the same as current to a reader who was not there.");
        if (title.isBlank() || slug.isBlank()) {
            throw new IllegalArgumentException("a blank title or slug makes the article unfindable");
        }
    }

    /** Publishing requires the authoring permission, checked by the caller — see the class comment. */
    public void publish(Instant at) {
        Objects.requireNonNull(at, "the publication instant is required");
        if (state != State.DRAFT) {
            throw new IllegalStateException("only a DRAFT article is published; this one is " + state);
        }
        this.publishedAt = at;
        this.state = State.PUBLISHED;
    }

    /**
     * The state as of a date, with the review deadline applied.
     *
     * <p>Derived rather than swept, so an overdue article is overdue the day it passes its date rather than the
     * day a job happens to run. A knowledge base whose staleness depends on a cron job is one where the guidance
     * is stale and the label is not.
     */
    public State stateOn(LocalDate date) {
        Objects.requireNonNull(date, "a date is required");
        if (state == State.PUBLISHED && date.isAfter(reviewDueBy)) {
            return State.REVIEW_OVERDUE;
        }
        return state;
    }

    /**
     * Records a review, moving the deadline forward.
     *
     * @throws IllegalArgumentException where the new date is not after the current one. A review that did not
     *     move the deadline is a click, and the whole mechanism depends on somebody having read the article
     */
    public void recordReview(UUID reviewerId, LocalDate newReviewDueBy, Instant at) {
        Objects.requireNonNull(reviewerId, "a reviewer is required");
        Objects.requireNonNull(newReviewDueBy, "a new review date is required");
        Objects.requireNonNull(at, "the review instant is required");
        if (!newReviewDueBy.isAfter(reviewDueBy)) {
            throw new IllegalArgumentException(
                    "a review must move the deadline forward; leaving it where it is records a review that "
                            + "changed nothing, and the mechanism depends on somebody having read the article");
        }
        this.reviewDueBy = newReviewDueBy;
    }

    /** Archives. The article stops appearing in search and its link still resolves. */
    public void archive() {
        if (state == State.ARCHIVED) {
            throw new IllegalStateException("already archived");
        }
        this.state = State.ARCHIVED;
    }

    /**
     * The qualifier a reader sees on an overdue article.
     *
     * <p>Shown rather than the article being withdrawn: a dead link sends the reader to a search engine, and
     * what they find there is not tenant-specific and not reviewed either.
     */
    public Optional<String> stalenessQualifier(LocalDate today) {
        if (stateOn(today) != State.REVIEW_OVERDUE) {
            return Optional.empty();
        }
        return Optional.of("This article passed its review date on " + reviewDueBy
                + " and has not been re-reviewed. Guidance that was correct when written may not be now; the "
                + "owner is the person to ask before following it.");
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String slug() {
        return slug;
    }

    public String title() {
        return title;
    }

    public ConstrainedContent content() {
        return content;
    }

    public UUID ownerPrincipalId() {
        return ownerPrincipalId;
    }

    public LocalDate reviewDueBy() {
        return reviewDueBy;
    }

    public State state() {
        return state;
    }

    public Optional<Instant> publishedAt() {
        return Optional.ofNullable(publishedAt);
    }
}
