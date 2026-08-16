package aspm.module.capacity.domain;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The daily workload rollup. {@code INV-CAP-02}, {@code PRD-CAP-001}.
 *
 * <p>"Daily rollups are idempotent and re-runnable over historical periods, <b>so a rollup defect is
 * correctable</b>."
 *
 * <p>That last clause is the requirement. A rollup that accumulates rather than replaces cannot be re-run: a
 * defect found in March means every day since is wrong and the only remedy is to explain the discontinuity in a
 * chart forever. Idempotence is what makes a rollup defect an afternoon's backfill instead.
 *
 * <h2>Backfillable from the transition log, which is why the log came first</h2>
 *
 * <p>{@link #compute} derives occupancy from transition entries rather than from a counter incremented as events
 * arrive. A counter is not backfillable — it has no record of what it counted — and DOC-03 section 13.2's
 * argument for building the transition log in v1 is precisely that this computation must be possible for periods
 * that have already passed.
 */
public record WorkloadRollup(UUID tenantId, LocalDate snapshotDate, Map<UUID, Integer> stateOccupancy,
        int computedFromTransitionCount) {

    public WorkloadRollup {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(snapshotDate, "a snapshot date is required");
        stateOccupancy = Map.copyOf(Objects.requireNonNull(stateOccupancy, "state occupancy is required"));
        if (computedFromTransitionCount < 0) {
            throw new IllegalArgumentException("a negative transition count");
        }
        for (Map.Entry<UUID, Integer> entry : stateOccupancy.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 0) {
                throw new IllegalArgumentException("a negative occupancy for state " + entry.getKey());
            }
        }
    }

    /**
     * One item's state at the end of the snapshot day, as derived from its transition log.
     *
     * @param stateAtEndOfDay absent where the item did not exist yet on that day. Not a zero and not a
     *     sentinel state: an item that did not exist did not occupy a state, and counting it in one would make
     *     a cumulative-flow chart show work before it was created
     */
    public record ItemStateOnDay(UUID workItemId, java.util.Optional<UUID> stateAtEndOfDay) {

        public ItemStateOnDay {
            Objects.requireNonNull(workItemId, "workItemId is required");
            Objects.requireNonNull(stateAtEndOfDay, "the state is required, empty where the item did not exist");
        }
    }

    /**
     * Computes a rollup for one day.
     *
     * <p><b>Idempotent by construction</b>: the result is a function of the inputs alone. Running it twice for
     * the same day produces an equal value, and running it for a day three months ago produces the value that
     * day should have had — which is what {@code INV-CAP-02} means by re-runnable.
     *
     * @param itemStates every item's state at end of day, derived from the transition log
     */
    public static WorkloadRollup compute(UUID tenantId, LocalDate snapshotDate,
            List<ItemStateOnDay> itemStates, int transitionCount) {
        Objects.requireNonNull(itemStates, "item states are required");
        Map<UUID, Integer> occupancy = new LinkedHashMap<>();
        for (ItemStateOnDay item : itemStates) {
            item.stateAtEndOfDay().ifPresent(state -> occupancy.merge(state, 1, Integer::sum));
        }
        return new WorkloadRollup(tenantId, snapshotDate, occupancy, transitionCount);
    }

    /**
     * Whether recomputing produced the same answer.
     *
     * <p>The verification half of {@code CON-DAT-031}: a projection must be "rebuildable ... and the rebuild
     * MUST be verifiable by comparison". Without this, a backfill silently replaces one wrong answer with
     * another and nobody knows which was which.
     */
    public boolean agreesWith(WorkloadRollup recomputed) {
        Objects.requireNonNull(recomputed, "a recomputed rollup is required");
        return tenantId.equals(recomputed.tenantId())
                && snapshotDate.equals(recomputed.snapshotDate())
                && stateOccupancy.equals(recomputed.stateOccupancy());
    }

    /** Total items occupying any state. Excludes items that did not exist — see {@link ItemStateOnDay}. */
    public int totalOccupied() {
        return stateOccupancy.values().stream().mapToInt(Integer::intValue).sum();
    }
}
