package aspm.module.assessment.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A versioned checklist definition. Aggregate root; DOC-03 section 9.4.
 *
 * <p><b>{@code INV-ASM-17}: a published version is immutable.</b> DOC-03's argument, which is the clearest
 * statement of why versioning matters anywhere in the corpus: "Editing a live checklist would silently change
 * the meaning of every completed assessment that used it — an assessment that covered 340 of 351 items would,
 * after an edit adding 20 items, appear to have covered 340 of 371 without anyone having changed the
 * assessment."
 *
 * <p>Note the direction of that failure. The assessment's coverage <i>falls</i> without the assessment changing,
 * and the team that did the work is the one that looks worse. Nothing in the record would explain it.
 */
public final class ChecklistDefinition {

    public enum State {
        DRAFT,
        PUBLISHED,
        DEPRECATED
    }

    /** One item. {@code group} is a domain grouping — injection, authentication — for presentation and rollup. */
    public record Item(UUID id, String code, String group, String statement) {

        public Item {
            Objects.requireNonNull(id, "id is required");
            Objects.requireNonNull(code, "code is required");
            Objects.requireNonNull(group, "group is required");
            Objects.requireNonNull(statement, "statement is required");
            if (code.isBlank() || statement.isBlank()) {
                throw new IllegalArgumentException("an item needs a code and a statement");
            }
        }
    }

    private final UUID id;
    private final String code;
    private final int version;
    private final List<Item> items;
    private State state = State.DRAFT;
    private java.time.Instant publishedAt;

    public ChecklistDefinition(UUID id, String code, int version, List<Item> items) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.code = Objects.requireNonNull(code, "code is required");
        this.items = List.copyOf(Objects.requireNonNull(items, "items are required"));
        if (version < 1) {
            throw new IllegalArgumentException("a version is required; instances pin it (INV-ASM-18)");
        }
        this.version = version;
        if (this.items.isEmpty()) {
            throw new IllegalArgumentException(
                    "an empty checklist covers nothing and reports zero-of-zero coverage, which "
                            + "CoverageSummary renders as 0 rather than as perfect — but the definition should "
                            + "not exist at all");
        }
        long distinct = this.items.stream().map(Item::code).distinct().count();
        if (distinct != this.items.size()) {
            throw new IllegalArgumentException(
                    "two items share a code; a coverage rollup keyed on the code would count one and lose the "
                            + "other, and the total would still look right");
        }
    }

    /** Publishes. After this the version is frozen; a change is a new version. */
    public void publish(java.time.Instant at) {
        Objects.requireNonNull(at, "the publication instant is required");
        if (state != State.DRAFT) {
            throw new IllegalStateException("only a DRAFT definition is published; this one is " + state);
        }
        this.publishedAt = at;
        this.state = State.PUBLISHED;
    }

    /**
     * Deprecates the definition. Existing instances continue to use it ({@code INV-ASM-18}), which is why
     * deprecation neither deletes nor migrates anything.
     */
    public void deprecate() {
        if (state != State.PUBLISHED) {
            throw new IllegalStateException("only a PUBLISHED definition is deprecated");
        }
        this.state = State.DEPRECATED;
    }

    /**
     * Instantiates the checklist for an assessment, at this version.
     *
     * @throws IllegalStateException on a DRAFT definition. An assessment against an unpublished checklist would
     *     have its item set change underneath it
     */
    public ChecklistInstance instantiate(UUID instanceId, UUID assessmentId) {
        if (state == State.DRAFT) {
            throw new IllegalStateException(
                    "checklist " + code + " is DRAFT; instantiating it would let the item set change "
                            + "underneath a live assessment (INV-ASM-17)");
        }
        return new ChecklistInstance(instanceId, assessmentId, id, version, items);
    }

    public UUID id() {
        return id;
    }

    public String code() {
        return code;
    }

    public int version() {
        return version;
    }

    public List<Item> items() {
        return items;
    }

    public State state() {
        return state;
    }

    public Optional<java.time.Instant> publishedAt() {
        return Optional.ofNullable(publishedAt);
    }
}
