package aspm.module.workmanagement.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A comment on a work item. Aggregate root; DOC-03 section 13.1, DOC-04 section 16.4.
 *
 * <h2>{@code INV-WRK-08} — never hard-deletable</h2>
 *
 * <p>DOC-03 section 13.2: "A comment thread on a security finding is audit evidence. Selective deletion permits
 * reconstruction of a different history, so removal is redaction with a visible record rather than deletion."
 *
 * <p>There is therefore no {@code delete}. {@link #redact} replaces the body with a marker, sets the flag,
 * records who redacted it and why, and <b>keeps the original in the revision history</b>. The redaction is
 * visible: a reader sees that something was removed, by whom, and for what stated reason. That is the difference
 * between a redaction and a deletion, and it is the whole point — a thread with a visible gap can be reasoned
 * about, a thread with an invisible one cannot.
 *
 * <p>The engine holds the same rule: {@code V009} withholds {@code DELETE} from {@code app_runtime}, because a
 * domain rule alone is bypassed by any path that does not go through the domain, and a migration import is
 * exactly such a path.
 *
 * <h2>{@code is_migrated}, and why it lives here</h2>
 *
 * <p>DOC-26 section 8 identified migration authorship as an abuse case: "the capability that preserves history
 * could fabricate a record of a decision never made". A comment imported from an incumbent tracker carries
 * another person's name as author, and nothing in the text distinguishes it from one written here. The flag is
 * the control, and DOC-04 section 16.4 puts it on the comment rather than on the import session precisely
 * because "it must survive into every presentation".
 */
public final class Comment {

    /** One prior version of the body. Append-only; a revision is never rewritten. */
    public record Revision(int revision, ConstrainedRichText body, UUID editedBy, Instant editedAt) {

        public Revision {
            Objects.requireNonNull(body, "a body is required");
            Objects.requireNonNull(editedBy, "editedBy is required");
            Objects.requireNonNull(editedAt, "editedAt is required");
            if (revision < 1) {
                throw new IllegalArgumentException("revisions number from 1");
            }
        }
    }

    private final UUID id;
    private final UUID workItemId;
    private final Optional<UUID> threadRootId;
    private final UUID authorId;
    private final Instant createdAt;
    private final boolean migrated;
    private final Optional<String> migratedFromExternalId;

    private ConstrainedRichText body;
    private final List<Revision> revisions = new ArrayList<>();
    private final List<UUID> attachmentIds = new ArrayList<>();

    private boolean redacted;
    private UUID redactedBy;
    private Instant redactedAt;
    private String redactionReason;

    private Comment(UUID id, UUID workItemId, UUID threadRootId, ConstrainedRichText body, UUID authorId,
            Instant createdAt, boolean migrated, String migratedFromExternalId) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.workItemId = Objects.requireNonNull(workItemId, "workItemId is required");
        this.threadRootId = Optional.ofNullable(threadRootId);
        this.body = Objects.requireNonNull(body, "a body is required");
        this.authorId = Objects.requireNonNull(authorId, "authorId is required");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.migrated = migrated;
        this.migratedFromExternalId = Optional.ofNullable(migratedFromExternalId);

        if (body.isEmpty()) {
            throw new IllegalArgumentException(
                    "an empty comment. Posting one produces a notification and a timeline entry for no content, "
                            + "which trains readers to ignore both.");
        }
        if (migrated && this.migratedFromExternalId.isEmpty()) {
            throw new IllegalArgumentException(
                    "a migrated comment must carry its external identifier (DOC-04 section 16.4). Without it "
                            + "the flag cannot be checked against the source, and the abuse case DOC-26 "
                            + "section 8 names — fabricating a record of a decision never made — is "
                            + "indistinguishable from a genuine import.");
        }
        if (!migrated && this.migratedFromExternalId.isPresent()) {
            throw new IllegalArgumentException(
                    "an external identifier on a comment not marked migrated; the flag is what reaches the "
                            + "presentation, so the two must agree");
        }
        if (threadRootId != null && threadRootId.equals(id)) {
            throw new IllegalArgumentException("a comment cannot be its own thread root");
        }
    }

    /** A comment written in the platform. */
    public static Comment post(UUID id, UUID workItemId, UUID threadRootId, ConstrainedRichText body,
            UUID authorId, Instant at) {
        return new Comment(id, workItemId, threadRootId, body, authorId, at, false, null);
    }

    /**
     * A comment imported from an incumbent tracker (ADR-028).
     *
     * <p>A distinct factory rather than a boolean parameter, so that every migration call site is visible in a
     * search for this method name. The flag is a control against fabricated authorship; a control set by an
     * argument somebody can forget to pass is not one.
     */
    public static Comment migrated(UUID id, UUID workItemId, UUID threadRootId, ConstrainedRichText body,
            UUID originalAuthorId, Instant originallyWrittenAt, String externalId) {
        return new Comment(id, workItemId, threadRootId, body, originalAuthorId, originallyWrittenAt, true,
                Objects.requireNonNull(externalId, "an external identifier is required for a migrated comment"));
    }

    /**
     * Edits the body, retaining the previous version.
     *
     * <p>{@code INV-WRK-08}: "Comments are editable with retained history." The retention is what makes editing
     * safe to allow — without it, editing is deletion plus insertion, which is the capability the invariant
     * exists to withhold.
     *
     * @throws IllegalStateException on a redacted comment. Editing after redaction would let the redactor write
     *     new content under the original author's name
     */
    public void edit(ConstrainedRichText newBody, UUID editorId, Instant at) {
        Objects.requireNonNull(newBody, "a body is required");
        Objects.requireNonNull(editorId, "editorId is required");
        Objects.requireNonNull(at, "the edit instant is required");
        if (redacted) {
            throw new IllegalStateException(
                    "a redacted comment is not editable; an edit after redaction would place new content under "
                            + "the original author's name");
        }
        if (newBody.isEmpty()) {
            throw new IllegalArgumentException(
                    "editing a comment to empty is deletion by another route, which INV-WRK-08 withholds. Use "
                            + "redact, which leaves a visible record.");
        }
        if (newBody.equals(body)) {
            // Not an error, but not a revision either: an unchanged edit would put a spurious entry in the
            // history and a spurious event in the timeline.
            return;
        }
        revisions.add(new Revision(revisions.size() + 1, body, editorId, at));
        body = newBody;
    }

    /**
     * Redacts the comment. The only removal path, and it leaves a visible record.
     *
     * @param reason required. A redaction without a stated reason is indistinguishable from a deletion to
     *     everybody except the person who performed it
     */
    public void redact(UUID redactorId, String reason, Instant at) {
        Objects.requireNonNull(redactorId, "redactedBy is required");
        Objects.requireNonNull(at, "the redaction instant is required");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "a redaction requires a reason (INV-WRK-08, DOC-04 section 16.4). Without one it is a "
                            + "deletion that happens to leave a marker.");
        }
        if (redacted) {
            throw new IllegalStateException("already redacted at " + redactedAt);
        }
        // The original goes into the revision history BEFORE the body is replaced. A redaction that discarded it
        // would be the selective deletion the invariant forbids, with extra steps.
        revisions.add(new Revision(revisions.size() + 1, body, redactorId, at));
        body = ConstrainedRichText.redactionMarker();
        redacted = true;
        redactedBy = redactorId;
        redactedAt = at;
        redactionReason = reason;
    }

    /** Attaches evidence or a screenshot. */
    public void attach(UUID attachmentId) {
        Objects.requireNonNull(attachmentId, "an attachment identifier is required");
        if (redacted) {
            throw new IllegalStateException("a redacted comment does not accept new attachments");
        }
        if (!attachmentIds.contains(attachmentId)) {
            attachmentIds.add(attachmentId);
        }
    }

    /**
     * The principals this comment mentions.
     *
     * <p>Authoring-time scope filtering is {@link MentionResolution}'s job, not this method's: a comment loaded
     * from storage reports what it contains, and a reader's own scope decides what they see. Conflating the two
     * would make the stored content depend on who read it.
     */
    public Set<UUID> mentionedPrincipals() {
        return body.mentionedPrincipals();
    }

    public UUID id() {
        return id;
    }

    public UUID workItemId() {
        return workItemId;
    }

    public Optional<UUID> threadRootId() {
        return threadRootId;
    }

    public ConstrainedRichText body() {
        return body;
    }

    public UUID authorId() {
        return authorId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Prior versions, oldest first. Includes the pre-redaction original where redacted. */
    public List<Revision> revisions() {
        return List.copyOf(revisions);
    }

    public int editCount() {
        return revisions.size();
    }

    public List<UUID> attachmentIds() {
        return List.copyOf(attachmentIds);
    }

    public boolean redacted() {
        return redacted;
    }

    public Optional<UUID> redactedBy() {
        return Optional.ofNullable(redactedBy);
    }

    public Optional<Instant> redactedAt() {
        return Optional.ofNullable(redactedAt);
    }

    public Optional<String> redactionReason() {
        return Optional.ofNullable(redactionReason);
    }

    /** {@code INV-ING} migration authorship. Must survive into every presentation (DOC-04 section 16.4). */
    public boolean migrated() {
        return migrated;
    }

    public Optional<String> migratedFromExternalId() {
        return migratedFromExternalId;
    }
}
