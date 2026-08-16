package aspm.kernel.schemaregistry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.kernel.schemaregistry.contract.AttributeDataType;
import aspm.kernel.schemaregistry.contract.AttributeSchema;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CON-DAT-018 and ADR-027 at the schema-registry contract. */
class AttributeSchemaTest {

    private static AttributeSchema schema(boolean searchable, Short slot) {
        return new AttributeSchema(UUID.randomUUID(), AttributeSchema.TargetKind.WORK_ITEM, null,
                "business_criticality", AttributeDataType.SINGLE_SELECT, "{}", false, searchable, true,
                null, slot, 0, AttributeSchema.LifecycleState.ACTIVE);
    }

    @Test
    @DisplayName("CON-DAT-018: a searchable attribute without an index slot is not representable")
    void searchableRequiresASlot() {
        assertThrows(IllegalArgumentException.class, () -> schema(true, null),
                "DOC-04 section 20.4 makes the bound structural: configurability of searchable fields "
                        + "is bounded, and the bound must be visible in the schema rather than "
                        + "discovered at runtime");
        assertDoesNotThrow(() -> schema(true, (short) 3));
        assertDoesNotThrow(() -> schema(false, null));
    }

    @Test
    @DisplayName("a field key must be a stable identifier, because integrations reference it")
    void fieldKeyIsStable() {
        for (String bad : new String[] {"Business Criticality", "1field", "field-key", "", "UPPER"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AttributeSchema(UUID.randomUUID(), AttributeSchema.TargetKind.ASSET, null,
                            bad, AttributeDataType.TEXT, "{}", false, false, true, null, null, 0,
                            AttributeSchema.LifecycleState.ACTIVE),
                    "accepted an unstable field key: '" + bad + "'");
        }
    }

    @Test
    @DisplayName("a retired schema stops accepting values but remains readable")
    void retirementIsNotDeletion() {
        var retired = new AttributeSchema(UUID.randomUUID(), AttributeSchema.TargetKind.ASSET, null,
                "legacy_tag", AttributeDataType.TEXT, "{}", false, false, true, null, null, 1,
                AttributeSchema.LifecycleState.RETIRED);
        assertFalse(retired.acceptsNewValues());
        // There is no DELETED state by design: a deleted schema makes recorded values uninterpretable,
        // and those values may appear in an audit payload whose envelope is immutable.
        assertTrue(java.util.Arrays.stream(AttributeSchema.LifecycleState.values())
                .noneMatch(s -> s.name().equals("DELETED")));
    }

    @Test
    @DisplayName("every data type declares whether it has a defined total order")
    void orderingIsDefinedPerType() {
        // A rule using a relational operator on an unordered type must be rejectable as a configuration
        // error rather than producing an implementation-dependent comparison — and comparison is
        // authorization-relevant wherever an attribute participates in a workflow guard.
        assertTrue(AttributeDataType.DECIMAL.isOrdered());
        assertTrue(AttributeDataType.SINGLE_SELECT.isOrdered());
        assertFalse(AttributeDataType.MULTI_SELECT.isOrdered());
        assertFalse(AttributeDataType.PRINCIPAL_REFERENCE.isOrdered());
    }
}
