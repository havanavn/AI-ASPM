package aspm.module.workmanagement.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A bulk operation over work items. {@code INV-WRK-12}, {@code PRD-WRK-016}.
 *
 * <p>DOC-07 section 5.2 on why {@code wrk.item.bulk} is a separate permission: "Bulk is how a scope error becomes
 * ten thousand scope errors."
 *
 * <p><b>Permission is evaluated per item and audit is written per item.</b> Not once for the batch. The
 * shortcut — check the caller may perform the operation, then apply it to the selected identifiers — is what
 * turns a client-supplied list into a cross-scope write, because the selection came from the client and PP-4
 * says a filtered picker is never an authorization control.
 *
 * <p><b>Partial application is the correct outcome.</b> A batch of two hundred where three are out of scope
 * applies to one hundred and ninety-seven and reports three refusals. All-or-nothing would let one unauthorized
 * identifier — trivially added by an attacker, or trivially present by accident — block legitimate work, and
 * would tell the attacker their identifier was rejected, which is more than a refusal needs to say.
 */
public final class BulkOperation {

    /** What happened to one item. */
    public record ItemOutcome(UUID itemId, Result result, String detail) {

        public enum Result {
            APPLIED,
            /** Refused. Reported as a count to the caller; audited individually. */
            REFUSED,
            /** The item moved between selection and application ({@code PRD-WRK-043}). */
            STALE
        }
    }

    /** The whole batch's outcome, and the per-item audit obligation. */
    public record Outcome(List<ItemOutcome> outcomes) {

        public Outcome {
            outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes are required"));
        }

        public long applied() {
            return outcomes.stream().filter(o -> o.result() == ItemOutcome.Result.APPLIED).count();
        }

        public long refused() {
            return outcomes.stream().filter(o -> o.result() == ItemOutcome.Result.REFUSED).count();
        }

        public long stale() {
            return outcomes.stream().filter(o -> o.result() == ItemOutcome.Result.STALE).count();
        }

        /**
         * The audit records this batch owes — <b>one per item</b>, including refusals.
         *
         * <p>{@code INV-WRK-12} requires audit per item. A single batch-level record would make "was this
         * finding's work item modified on the third" unanswerable, and refusals are also the escalation-attempt
         * signal of {@code SEC-AUZ-037}: a principal repeatedly submitting out-of-scope identifiers in bulk is
         * enumerating.
         */
        public List<ItemOutcome> auditRecords() {
            return outcomes;
        }
    }

    /** Cap on batch size. A larger request is rejected rather than truncated. */
    public static final int MAX_BATCH = 1_000;

    private BulkOperation() {
    }

    /**
     * Applies an operation to each item independently.
     *
     * @param authorizedPerItem the per-item authorization decision, which must include the scope check. Taking a
     *     predicate rather than a boolean is the point: a single boolean parameter is the batch-level check this
     *     invariant exists to prevent
     * @param apply performs the change. Returns the detail recorded in the audit entry; throws
     *     {@link WorkItem.StaleWriteException} where the item moved
     */
    public static Outcome apply(List<UUID> itemIds, Predicate<UUID> authorizedPerItem,
            java.util.function.Function<UUID, String> apply) {
        Objects.requireNonNull(itemIds, "itemIds are required");
        Objects.requireNonNull(authorizedPerItem, "a per-item authorization decision is required (INV-WRK-12)");
        Objects.requireNonNull(apply, "an apply function is required");
        if (itemIds.size() > MAX_BATCH) {
            throw new IllegalArgumentException(
                    "a batch of " + itemIds.size() + " exceeds " + MAX_BATCH + ". Rejected rather than "
                            + "truncated: a silently truncated bulk operation reports success for items it "
                            + "never touched.");
        }

        List<ItemOutcome> outcomes = new ArrayList<>(itemIds.size());
        for (UUID itemId : itemIds) {
            if (!authorizedPerItem.test(itemId)) {
                // "not permitted" and nothing more. Distinguishing out-of-scope from insufficient-permission
                // here would turn a bulk endpoint into the object-existence oracle the transition evaluation
                // order removes (SEC-AUZ-020).
                outcomes.add(new ItemOutcome(itemId, ItemOutcome.Result.REFUSED, "not permitted"));
                continue;
            }
            try {
                outcomes.add(new ItemOutcome(itemId, ItemOutcome.Result.APPLIED, apply.apply(itemId)));
            } catch (WorkItem.StaleWriteException stale) {
                outcomes.add(new ItemOutcome(itemId, ItemOutcome.Result.STALE, stale.getMessage()));
            }
        }
        return new Outcome(outcomes);
    }
}
