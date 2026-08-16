package aspm.module.assetinventory.application;

import aspm.kernel.schemaregistry.contract.AttributeDataType;
import aspm.kernel.schemaregistry.contract.AttributeSchema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code INV-AST-02} — an asset's attributes must validate against its type's schema <b>at every write</b>.
 *
 * <p>"At every write" is the operative phrase and the reason this is a separate validator rather than a check
 * inside a setter: the write paths are asset creation, asset update, bulk import, migration import and
 * re-resolution after an identity rule change. A check in one of them is a check in one of them.
 *
 * <p>Returns findings rather than throwing on the first, because a bulk import needs to report every invalid
 * attribute on a row at once — a caller that has to re-submit five times to discover five problems will
 * disable validation.
 */
public final class AttributeValidation {

    /** One validation failure, naming the attribute so a bulk report is actionable. */
    public record Finding(String fieldKey, String detail) {

        public Finding {
            Objects.requireNonNull(fieldKey, "fieldKey is required");
            Objects.requireNonNull(detail, "detail is required");
        }
    }

    private AttributeValidation() {
        throw new AssertionError("not instantiable");
    }

    /**
     * Validates an attribute map against the schemas defined for the asset's type.
     *
     * @param schemas the type's attribute schemas, from {@code schema-registry}
     * @param attributes the values being written
     */
    public static List<Finding> validate(
            Collection<AttributeSchema> schemas, Map<String, Object> attributes) {
        Objects.requireNonNull(schemas, "schemas are required");
        Objects.requireNonNull(attributes, "attributes are required; use Map.of() for none");

        List<Finding> findings = new ArrayList<>();
        Map<String, AttributeSchema> byKey = new java.util.HashMap<>();
        for (AttributeSchema schema : schemas) {
            byKey.put(schema.fieldKey(), schema);
        }

        // An attribute with no schema is rejected rather than ignored. Ignoring it means a typo in a field key
        // silently discards the value, and the user sees a saved form with a missing field — the failure PP-1
        // describes in a different guise, where absence is indistinguishable from a value that was never set.
        for (String key : attributes.keySet()) {
            if (!byKey.containsKey(key)) {
                findings.add(new Finding(key,
                        "no attribute schema is defined for this key on the asset's type. Ignoring an unknown "
                                + "attribute silently discards the value, so it is rejected instead."));
            }
        }

        for (AttributeSchema schema : schemas) {
            Object value = attributes.get(schema.fieldKey());

            if (value == null) {
                if (schema.required() && schema.acceptsNewValues()) {
                    findings.add(new Finding(schema.fieldKey(), "a required attribute is absent"));
                }
                continue;
            }
            if (!schema.acceptsNewValues()) {
                findings.add(new Finding(schema.fieldKey(),
                        "the attribute schema is " + schema.lifecycleState()
                                + " and accepts no new values; existing values remain readable"));
                continue;
            }
            typeCheck(schema, value).ifPresent(findings::add);
        }
        return List.copyOf(findings);
    }

    /** True where the write may proceed. */
    public static boolean isValid(List<Finding> findings) {
        return findings.isEmpty();
    }

    private static java.util.Optional<Finding> typeCheck(AttributeSchema schema, Object value) {
        AttributeDataType type = schema.dataType();
        String key = schema.fieldKey();

        boolean ok = switch (type) {
            case TEXT, LONG_TEXT, URL -> value instanceof String;
            // BigDecimal only, never Double: PRD-RSK-023 requires a score to be recomputable identically, and
            // an attribute feeding a score must not arrive as binary floating point.
            case DECIMAL -> value instanceof BigDecimal;
            case INTEGER -> value instanceof Integer || value instanceof Long;
            case BOOLEAN -> value instanceof Boolean;
            case TIMESTAMP -> value instanceof Instant || parsesAs(value, true);
            case DATE -> value instanceof LocalDate || parsesAs(value, false);
            case SINGLE_SELECT -> value instanceof String;
            case MULTI_SELECT -> value instanceof Collection<?>;
            case PRINCIPAL_REFERENCE, ORG_NODE_REFERENCE -> value instanceof UUID || isUuid(value);
        };

        if (!ok) {
            return java.util.Optional.of(new Finding(key,
                    "value of type " + value.getClass().getSimpleName() + " is not valid for declared type "
                            + type + (type == AttributeDataType.DECIMAL
                                    ? ". Use BigDecimal: binary floating point is not associative, so a value "
                                            + "feeding a score could not be recomputed identically."
                                    : "")));
        }
        if (type == AttributeDataType.DECIMAL && value instanceof Double) {
            return java.util.Optional.of(new Finding(key, "binary floating point is not accepted"));
        }
        return java.util.Optional.empty();
    }

    private static boolean parsesAs(Object value, boolean instant) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            if (instant) {
                Instant.parse(text);
            } else {
                LocalDate.parse(text);
            }
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isUuid(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            UUID.fromString(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
