package aspm.app.resource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One resource group of DOC-05, described as data.
 *
 * <p>DOC-05 §12 to §25 specify well over a hundred operations across roughly twenty groups. Hand-writing
 * each one produces a hundred chances to omit the scope predicate, the projection, or the optimistic
 * concurrency check — and ADR-036's whole argument is that a security characteristic an operation can omit
 * is one that will be omitted. A descriptor plus generic handlers makes those properties impossible to
 * omit, because there is no per-operation code in which to forget them.
 *
 * <h2>Everything is declared, and absence means denial</h2>
 *
 * <p>The temptation with a generic resource layer is {@code SELECT *} and a projection filter. That
 * inverts the default: a column added by a later migration is exposed until somebody remembers to hide it,
 * and the column most likely to be added to a security platform's table is a sensitive one.
 *
 * <p>So {@link #exposed} is the projection — the query selects those columns and no others. A column
 * absent from it is absent from the SQL, which is ADR-047 ("restricted fields are absent from
 * representations, not masked") enforced one layer earlier than the representation.
 *
 * <p>Likewise {@link #filterable}, {@link #writableOnCreate} and {@link #writableOnUpdate}: a field absent
 * from the set is rejected rather than ignored ({@code PRD-API-020}).
 *
 * @param table the physical table. Interpolated into SQL, so it is validated against an identifier shape
 *     at construction rather than trusted — a resource group is developer-supplied today and a plausible
 *     candidate for tenant configuration tomorrow
 * @param scopeColumn the column carrying the organizational scope, or empty where the group is
 *     tenant-wide configuration. <b>Empty is a decision, not a default</b>: it means every principal in
 *     the tenant who holds the permission sees every row, which is correct for node types and wrong for
 *     assets
 * @param scopeBearingOnCreate body fields on a create that name an organizational node and therefore
 *     decide where the new row lands. Every one of them is re-validated against the caller's scope
 *     before the insert, because a create authorized only on the collection permission lets a caller
 *     write into a branch of the tree it cannot read — the write half of broken object-level
 *     authorization, which reads alone never expose ({@code SEC-AUZ-016}, PP-4)
 * @param sortColumn the keyset sort column. Paired with {@code id} as the tiebreaker, because no other
 *     column in this schema is unique and a page boundary inside a run of equal values skips or
 *     duplicates rows under concurrent modification ({@code TST-PLT-008})
 */
public record ResourceGroup(
        String name,
        String table,
        Optional<String> scopeColumn,
        Set<String> scopeBearingOnCreate,
        String sortColumn,
        Map<String, ColumnKind> exposed,
        Set<String> filterable,
        Set<String> writableOnCreate,
        Set<String> writableOnUpdate,
        java.util.function.Function<Map<String, Object>, Map<String, Object>> completeOnCreate,
        String readPermission,
        String createPermission,
        String updatePermission) {

    /** How a column is rendered. The API's type is a decision, not whatever JDBC returns. */
    public enum ColumnKind { UUID, TEXT, INTEGER, BOOLEAN, TIMESTAMP, JSON, TEXT_ARRAY }

    /**
     * A value the database produces rather than the application.
     *
     * <p>Returned from {@link #completeOnCreate} in place of a bound parameter, and emitted into the
     * insert as the expression itself. There is one member and it is deliberate: the clock belongs to
     * the engine, so that {@code first_seen_at} on a row and {@code created_at} on the same row cannot
     * disagree by the drift between two hosts, and so that "how old is this finding" has one answer.
     * A second member would make this a small language for writing SQL out of data, which is the thing
     * the parameterized statement exists to prevent — anything more elaborate belongs in a column
     * default or a trigger, where the engine owns it outright.
     */
    public enum SqlDefault { NOW }

    /**
     * A derived value destined for a {@code jsonb} column.
     *
     * <p>Needed because {@link #placeholderCast} reads the cast off the projection, and a derived
     * column is by definition not in the projection — the caller never sees it, which is why the caller
     * cannot set it. Binding a bare string into {@code jsonb} is refused by the engine, so the type
     * carries the intent rather than the endpoint guessing from the shape of the text.
     *
     * @param json the document, as text. Never caller-supplied — every use is a constant
     */
    public record JsonValue(String json) {
        public JsonValue {
            Objects.requireNonNull(json, "a json value is required");
        }
    }

    private static final java.util.regex.Pattern IDENTIFIER =
            java.util.regex.Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    public ResourceGroup {
        Objects.requireNonNull(name, "a name is required");
        requireIdentifier(table, "table");
        Objects.requireNonNull(scopeColumn, "pass an empty optional rather than null");
        scopeColumn.ifPresent(column -> requireIdentifier(column, "scope column"));
        scopeBearingOnCreate = Set.copyOf(
                Objects.requireNonNull(scopeBearingOnCreate, "required, possibly empty"));
        scopeBearingOnCreate.forEach(field -> requireIdentifier(field, "scope-bearing field"));
        requireIdentifier(sortColumn, "sort column");
        exposed = Map.copyOf(Objects.requireNonNull(exposed, "an explicit projection is required"));
        filterable = Set.copyOf(Objects.requireNonNull(filterable, "a filterable set is required"));
        writableOnCreate = Set.copyOf(Objects.requireNonNull(writableOnCreate, "required, possibly empty"));
        writableOnUpdate = Set.copyOf(Objects.requireNonNull(writableOnUpdate, "required, possibly empty"));
        Objects.requireNonNull(completeOnCreate,
                "return an empty map rather than null: a group with nothing to derive says so");
        for (String field : scopeBearingOnCreate) {
            if (!writableOnCreate.contains(field)) {
                throw new IllegalArgumentException("a scope-bearing field the caller cannot set is a "
                        + "check on nothing: " + field + " is absent from writableOnCreate");
            }
        }
        Objects.requireNonNull(readPermission, "a read permission is required");
        // Create and update are SEPARATE permissions, as DOC-05 §12 and §13 assign them:
        // org.node.create and org.node.update, ast.asset.create and ast.asset.update. Collapsing them
        // into one means a principal who may correct a name may also create structure — and structure is
        // what every scope decision in the platform is computed from. SEC-AUZ-001 makes the catalogue
        // product-fixed and requires every entry to gate something; one code gating two operations makes
        // one of the two entries gate nothing, which that requirement calls a defect.
        Objects.requireNonNull(createPermission, "a create permission is required");
        Objects.requireNonNull(updatePermission, "an update permission is required");

        for (String column : exposed.keySet()) {
            requireIdentifier(column, "exposed column");
        }
        if (!exposed.containsKey("id")) {
            throw new IllegalArgumentException(
                    name + " does not expose 'id'. Without it a caller cannot address a single object, and "
                            + "the keyset tiebreaker has nothing to encode.");
        }
        if (!exposed.containsKey(sortColumn)) {
            throw new IllegalArgumentException(
                    name + " sorts by '" + sortColumn + "' and does not expose it. The cursor encodes the "
                            + "sort value, so a caller would be handed a value from a column they cannot "
                            + "see — which discloses it.");
        }
        for (String column : filterable) {
            if (!exposed.containsKey(column)) {
                throw new IllegalArgumentException(
                        name + " permits filtering on '" + column + "' and does not expose it. Filtering "
                                + "on an unexposed column is an oracle: absence and presence of results "
                                + "reveal the value one query at a time.");
            }
        }
        // tenant_id is never writable and never filterable. The tenant comes from the principal
        // (SEC-TEN-004), and a writable tenant_id would let a caller name another tenant — which the
        // row-level policy's WITH CHECK would reject, making this the second of two independent controls.
        for (Set<String> writable : java.util.List.of(writableOnCreate, writableOnUpdate)) {
            if (writable.contains("tenant_id") || writable.contains("id")
                    || writable.contains("row_version")) {
                throw new IllegalArgumentException(
                        name + " declares tenant_id, id or row_version as writable. The first is derived "
                                + "from the principal, the second is assigned by the engine, and the third "
                                + "is the optimistic concurrency token — a caller that can set it can "
                                + "overwrite a concurrent change silently.");
            }
        }
    }

    private static void requireIdentifier(String value, String what) {
        Objects.requireNonNull(value, what + " is required");
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "'" + value + "' is not an acceptable " + what + ". Identifiers are interpolated into "
                            + "SQL, so they are validated rather than escaped: escaping an identifier is a "
                            + "different operation from escaping a value and the two get confused.");
        }
    }

    /** {@code SELECT} list, in declaration order so a response's field order is stable. */
    public String projection() {
        return String.join(", ", exposed.keySet());
    }

    /** Whether a principal's scope restricts this group at all. */
    public boolean scoped() {
        return scopeColumn.isPresent();
    }

    /** The declared fields for a create, for {@code RequestValidation.rejectUnknownFields}. */
    public Map<String, ColumnKind> createFields() {
        Map<String, ColumnKind> fields = new LinkedHashMap<>();
        for (String column : writableOnCreate) {
            fields.put(column, exposed.getOrDefault(column, ColumnKind.TEXT));
        }
        return fields;
    }
}
