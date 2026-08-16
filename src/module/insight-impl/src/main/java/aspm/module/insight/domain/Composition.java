package aspm.module.insight.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * The four compositions of DOC-12 sections 5 to 8, and the drill-through rule that governs all of them.
 *
 * <p>The compositions differ in what they show and share one property that matters more than any of it:
 * <b>drill-through performs full object-level authorization and does not inherit it from the composition
 * permission.</b>
 *
 * <h2>Why the drill-through rule is the load-bearing one</h2>
 *
 * <p>A composition is an aggregate over a scope the caller is authorized for. Permission to see the aggregate is
 * not permission to see every object behind it — an executive posture figure for a division legitimately
 * includes findings on systems the reader cannot open, and the aggregate is a permitted disclosure precisely
 * because it does not name them.
 *
 * <p>The tempting implementation authorizes the composition once and then treats every row it produced as
 * reachable, because the rows came from a query the caller was allowed to run. That converts an aggregate
 * permission into an object permission for everything the aggregate touched, which is the largest
 * object-level-authorization defect this platform could ship — and it is the defect class the product exists to
 * find in customers' software.
 *
 * <p>{@link #drillThrough} therefore takes an object-level check and applies it per row, and there is no
 * overload that omits it.
 */
public enum Composition {

    /** DOC-12 section 5. The figure an executive reads, and the one that must never be confidently wrong. */
    EXECUTIVE_POSTURE("dsh.composition.executive.read",
            "which parts of the organization need attention"),

    /** DOC-12 section 6. The twelve queues of {@link OperationalQueue} and the measures beside them. */
    SECURITY_OPERATIONS("dsh.composition.operations.read",
            "what the security function is working on and what is waiting"),

    /** DOC-12 section 7. What an engineering owner owes and by when. */
    ENGINEERING_OWNERSHIP("dsh.composition.ownership.read",
            "what this engineering group owns and what is due"),

    /** DOC-12 section 8. Capacity, utilization and flow for the security team itself. */
    SECURITY_TEAM_WORKLOAD("dsh.composition.workload.read",
            "whether the security function's load is sustainable");

    private final String compositionPermission;
    private final String question;

    Composition(String compositionPermission, String question) {
        this.compositionPermission = compositionPermission;
        this.question = question;
    }

    /**
     * The permission to view the composition.
     *
     * <p>Distinct per composition, and <b>never</b> sufficient for a drill-through. See
     * {@link #drillThrough}.
     */
    public String compositionPermission() {
        return compositionPermission;
    }

    /** The question this composition answers. Recorded so a fifth is judged against a gap, not a preference. */
    public String question() {
        return question;
    }

    /**
     * One row of a composition, as rendered.
     *
     * @param objectId the object behind the row, where there is one. A row summarising many objects has none,
     *     and is not drillable
     */
    public record Row(String label, java.math.BigDecimal value, Optional<UUID> objectId) {

        public Row {
            Objects.requireNonNull(label, "a label is required");
            Objects.requireNonNull(value, "a value is required");
            Objects.requireNonNull(objectId, "objectId is required, empty where the row summarises many");
        }
    }

    /**
     * Filters composition rows to those the caller may actually open.
     *
     * <p>{@code PRD-DSH-021}'s companion rule. The composition permission got the caller this far; it does not
     * get them into an object.
     *
     * @param objectLevelCheck applied per row, taking the principal and the object. Required — there is no
     *     overload without it, because the omission would be invisible in a code review of a method call that
     *     already looked correct
     * @return only the rows whose object the caller may open. A row that summarises many objects carries no
     *     identifier and is returned: it is the aggregate, which the caller is authorized for
     */
    public static List<Row> drillThrough(UUID principalId, List<Row> rows,
            BiPredicate<UUID, UUID> objectLevelCheck) {
        Objects.requireNonNull(principalId, "a principal is required");
        Objects.requireNonNull(rows, "rows are required");
        Objects.requireNonNull(objectLevelCheck,
                "an object-level check is required. Authorizing the composition once and then treating every "
                        + "row as reachable converts an aggregate permission into an object permission for "
                        + "everything the aggregate touched — which is the defect class this product exists to "
                        + "find in customers' software.");

        return rows.stream()
                .filter(row -> row.objectId().isEmpty()
                        || objectLevelCheck.test(principalId, row.objectId().get()))
                .toList();
    }
}
