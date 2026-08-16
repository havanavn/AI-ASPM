package aspm.kernel.tenantcontext.contract;

import aspm.sharedkernel.OrgNodeId;
import java.util.List;
import java.util.Objects;

/**
 * The resolved scope a query may retrieve, expressed as data for the retrieval layer to apply.
 *
 * <p>{@code SEC-AUZ-016} and {@code CON-PLT-038} require the scope predicate to be applied <b>as
 * part of retrieval</b>, never as a filter afterwards. DOC-07 section 8.3 gives the three reasons:
 * retrieve-then-filter discloses foreign volume through counts, breaks pagination — a page of fifty
 * becomes twelve — and moves unauthorized data into application memory where it reaches logs and
 * error reports.
 *
 * <p>Carrying the predicate as a value rather than as a callback is what makes that structural: a
 * callback can only be invoked after rows exist, whereas a value can be composed into the query.
 *
 * @param permittedNodes org nodes whose subtrees the principal may read, already resolved
 * @param unrestricted true only for a platform operation with no scope narrowing, which is
 *     enumerated rather than ambient
 */
public record ScopePredicate(List<OrgNodeId> permittedNodes, boolean unrestricted) {

    public ScopePredicate {
        permittedNodes = List.copyOf(Objects.requireNonNull(permittedNodes, "permittedNodes is required"));
        if (unrestricted && !permittedNodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "an unrestricted predicate must not also enumerate nodes; the two are different "
                            + "authorization outcomes and conflating them hides which one applied");
        }
    }

    /**
     * The empty predicate: matches nothing.
     *
     * <p>This is the deny-by-default shape required by {@code SEC-AUZ-014}. It is deliberately not
     * the same value as {@link #unrestricted()}, because an empty list and "everything" are the two
     * outcomes most damaging to confuse.
     */
    public static ScopePredicate none() {
        return new ScopePredicate(List.of(), false);
    }

    public boolean matchesNothing() {
        return !unrestricted && permittedNodes.isEmpty();
    }
}
