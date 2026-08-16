package aspm.kernel.schemaregistry.contract;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A tenant-defined custom attribute, per DOC-04 sections 8.2 and 20.4.
 *
 * <p>This is the mechanism through which ADR-027's configurability is realized (DOC-02 section 6.2).
 * ADR-027 makes custom fields tenant-configurable data rather than code, and the prohibited-pattern
 * table rejects "fixed enumeration for a tenant-configurable surface" — so a tenant adding a field is a
 * row here, never a migration.
 *
 * <p><b>{@code fieldKey} is immutable, {@code label} is not.</b> The same reasoning DOC-04 section 8.1
 * gives for taxonomy codes: the key is what integrations, saved queries, imports and API consumers
 * reference, and a mutable key silently breaks all of them — with the breakage appearing as empty
 * results rather than as errors.
 *
 * <p><b>Searchability is bounded, visibly.</b> {@code CON-DAT-018} bounds indexable attributes per type
 * as generic slots, and DOC-04 section 20.4 makes the bound structural: a field cannot be marked
 * searchable without a slot, and slots are unique per type. DOC-04 calls this "the honest expression of
 * the limitation" — configurability of searchable fields is bounded, and the bound is visible in the
 * schema rather than discovered at runtime. That property is enforced in the constructor below, so it
 * holds before a row reaches the database as well as at it.
 */
public record AttributeSchema(
        UUID id,
        TargetKind targetKind,
        UUID targetTypeId,
        String fieldKey,
        AttributeDataType dataType,
        String validationRule,
        boolean required,
        boolean searchable,
        boolean exportable,
        String visibilityRule,
        Short indexSlot,
        int displayOrder,
        LifecycleState lifecycleState) {

    /** What an attribute may be attached to. Product-fixed: these are the extensible aggregates. */
    public enum TargetKind {
        ASSET,
        WORK_ITEM,
        ASSESSMENT,
        REQUEST
    }

    /** Schema lifecycle. A field is retired rather than deleted, so recorded values stay readable. */
    public enum LifecycleState {
        DRAFT,
        ACTIVE,
        /**
         * No longer offered for new values, existing values still readable.
         *
         * <p>Deletion is deliberately absent. A deleted schema makes every recorded value
         * uninterpretable, and those values may appear in an audit payload whose envelope is immutable.
         */
        RETIRED
    }

    public AttributeSchema {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(targetKind, "targetKind is required");
        Objects.requireNonNull(fieldKey, "fieldKey is required");
        Objects.requireNonNull(dataType, "dataType is required");
        Objects.requireNonNull(validationRule, "validationRule is required; use an empty object, not null");
        Objects.requireNonNull(lifecycleState, "lifecycleState is required");

        if (!fieldKey.matches("^[a-z][a-z0-9_]{0,62}$")) {
            throw new IllegalArgumentException(
                    "field key '" + fieldKey + "' is not a stable lower_snake identifier. The key appears "
                            + "in integrations, saved queries, imports and exports; a key that can vary "
                            + "breaks them silently, producing empty results rather than errors.");
        }
        // CON-DAT-018 and the DOC-04 section 20.4 CHECK, enforced before the row reaches the database.
        if (searchable && indexSlot == null) {
            throw new IllegalArgumentException(
                    "a searchable attribute requires an index slot (CON-DAT-018). Searchable fields are "
                            + "bounded by provisioned generic slots, and the bound is deliberately visible "
                            + "here rather than discovered at runtime.");
        }
        if (indexSlot != null && indexSlot < 0) {
            throw new IllegalArgumentException("index slot is non-negative");
        }
        if (displayOrder < 0) {
            throw new IllegalArgumentException("display order is non-negative");
        }
    }

    public Optional<Short> slot() {
        return Optional.ofNullable(indexSlot);
    }

    public Optional<UUID> typeScope() {
        return Optional.ofNullable(targetTypeId);
    }

    /** True where values may still be written against this schema. */
    public boolean acceptsNewValues() {
        return lifecycleState == LifecycleState.ACTIVE;
    }
}
