package aspm.module.assessment.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A checklist instantiated against one assessment. DOC-03 section 9.4.
 *
 * <p><b>{@code INV-ASM-18}: the definition version is pinned</b>, "so historical coverage claims remain
 * interpretable". The pinned version and the item set travel with the instance rather than being looked up, so
 * an instance is readable years later without the definition still existing in the shape it had.
 *
 * <p>Every item starts {@code NOT_ASSESSED} ({@code INV-ASM-19}). Not absent, not null: an item nobody has
 * touched is a recorded gap in coverage from the moment the checklist is instantiated, which is what makes an
 * abandoned assessment's partial coverage meaningful.
 */
public final class ChecklistInstance {

    private final UUID id;
    private final UUID assessmentId;
    private final UUID definitionId;
    private final int definitionVersion;
    private final List<ChecklistDefinition.Item> items;
    private final Map<UUID, ItemResult> results = new LinkedHashMap<>();
    private Instant completedAt;

    ChecklistInstance(UUID id, UUID assessmentId, UUID definitionId, int definitionVersion,
            List<ChecklistDefinition.Item> items) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.assessmentId = Objects.requireNonNull(assessmentId, "assessmentId is required");
        this.definitionId = Objects.requireNonNull(definitionId, "definitionId is required");
        this.definitionVersion = definitionVersion;
        this.items = List.copyOf(Objects.requireNonNull(items, "items are required"));
        for (ChecklistDefinition.Item item : this.items) {
            results.put(item.id(), ItemResult.notAssessed(item.id()));
        }
    }

    /**
     * Records a result.
     *
     * @throws IllegalArgumentException where the item is not in this instance's pinned item set. An item added
     *     to the definition after instantiation is not part of this assessment's coverage denominator, and
     *     accepting a result for it would make the numerator and denominator describe different checklists
     */
    public void record(ItemResult result) {
        Objects.requireNonNull(result, "a result is required");
        if (!results.containsKey(result.itemId())) {
            throw new IllegalArgumentException(
                    "item " + result.itemId() + " is not in this instance's pinned item set (version "
                            + definitionVersion + "). Accepting it would make the coverage numerator and "
                            + "denominator describe different checklists (INV-ASM-18).");
        }
        results.put(result.itemId(), result);
    }

    /** {@code INV-ASM-11}: derived, never set. */
    public CoverageSummary coverage() {
        return CoverageSummary.from(results.values());
    }

    /** The items nobody has assessed, so an acknowledgement can name them rather than count them. */
    public List<ChecklistDefinition.Item> unassessedItems() {
        return items.stream().filter(i -> !results.get(i.id()).covered()).toList();
    }

    public void markCompleted(Instant at) {
        this.completedAt = Objects.requireNonNull(at, "the completion instant is required");
    }

    public UUID id() {
        return id;
    }

    public UUID assessmentId() {
        return assessmentId;
    }

    public UUID definitionId() {
        return definitionId;
    }

    /** Pinned at instantiation. There is no setter ({@code INV-ASM-18}). */
    public int definitionVersion() {
        return definitionVersion;
    }

    public List<ChecklistDefinition.Item> items() {
        return items;
    }

    public Map<UUID, ItemResult> results() {
        return Map.copyOf(results);
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }
}
