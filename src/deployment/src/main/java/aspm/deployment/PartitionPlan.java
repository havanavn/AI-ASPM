package aspm.deployment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Partition provisioning and the sizing basis behind it. {@code OPS-DEP-011}, {@code OPS-DEP-012}.
 *
 * <p>Two requirements with the same shape: the mechanism is easy and the second half is what gets omitted.
 *
 * <ul>
 *   <li>{@code OPS-DEP-011} — creation automated ahead of need, <b>and a missing future partition alerts before
 *       it would be required</b>. The provisioning functions were written as each table arrived; the alert was
 *       written once, for {@code audit_event}. {@code V013} closes the rest, generically.
 *   <li>{@code OPS-DEP-012} — counts set before first production data, <b>and recorded with the sizing basis
 *       used</b>. "Recording the basis lets a later resize assess whether the assumption or the growth was
 *       wrong."
 * </ul>
 *
 * <p>⚠ <b>Working assumption (OQ-015):</b> the counts are the Medium reference profile of DOC-01 §12.1 with
 * headroom to Extra large. OQ-015 is marked as blocking implementation for exactly this reason — changing a hash
 * partition count redistributes every row ({@code CON-DAT-035}), so the number is irreversible once production
 * data exists. The mechanism does not change on an answer; the number does.
 */
public final class PartitionPlan {

    /** How a table is partitioned, and what that implies about changing it. */
    public enum Strategy {

        /**
         * By tenant. <b>Irreversible after first production data.</b> Changing the modulus rehashes every row
         * into a different partition, which for the finding tables is the whole dataset.
         */
        HASH_BY_TENANT,

        /**
         * By time. Reversible, and its failure mode is different: a missing future partition rejects inserts.
         * "For {@code audit_event} that fails every audited operation under {@code CON-PLT-021} — a total write
         * outage from an omitted maintenance task."
         */
        RANGE_BY_TIME
    }

    /**
     * A hash-partitioned table.
     *
     * @param sizingBasis why this count. Required and length-checked, because "default" satisfies "recorded"
     *     and answers nothing — and the question a resize asks is whether the assumption or the growth was
     *     wrong, which only the basis can answer
     */
    public record HashPartitioned(String tableName, int partitionCount, String sizingBasis,
            String openQuestion) {

        public HashPartitioned {
            Objects.requireNonNull(tableName, "a table name is required");
            Objects.requireNonNull(sizingBasis, "a sizing basis is required (OPS-DEP-012)");
            if (partitionCount <= 0) {
                throw new IllegalArgumentException("a hash partition count is positive");
            }
            if (sizingBasis.strip().length() < 40) {
                throw new IllegalArgumentException(
                        tableName + " records a sizing basis of fewer than forty characters. "
                                + "OPS-DEP-012 requires the basis because a later resize needs to know whether "
                                + "the assumption or the growth was wrong, and 'default' answers neither for a "
                                + "decision that redistributes every row (CON-DAT-035).");
            }
        }
    }

    /**
     * A range-partitioned table.
     *
     * @param provisioningFunction the {@code ensure_*_partitions(lead_months)} that creates ahead of need
     * @param unreconstructable whether losing a write window is permanent. DOC-15 §4 names the transition log
     *     as having this property: the data cannot be reconstructed, so an insert rejected during an outage is
     *     gone rather than delayed
     */
    public record RangePartitioned(String tableName, String provisioningFunction, int leadMonths,
            boolean unreconstructable) {

        public RangePartitioned {
            Objects.requireNonNull(tableName, "a table name is required");
            Objects.requireNonNull(provisioningFunction, "a provisioning function is required (OPS-DEP-011)");
            if (leadMonths < 1) {
                throw new IllegalArgumentException(
                        "a lead time of under one month means the partition is created in the month it is "
                                + "needed, which is provisioning on the deadline rather than ahead of need.");
            }
        }

        /**
         * The alert. One generic report over the catalogue rather than a function per table.
         *
         * <p>Per-table alerting is what produced the gap {@code V013} closes: four tables had provisioning and
         * no alert, each correct on its own, and the omission was only visible once the set was enumerated. A
         * report reading {@code pg_partitioned_table} covers a table added later on the day it is created.
         */
        public String alertingQuery() {
            return "SELECT runway_months, alerting FROM partition_runway_report() "
                    + "WHERE parent_table = '" + tableName + "'";
        }
    }

    /** The nine hash-partitioned tables of V006, V011 and V012. Mirrors {@code hash_partition_basis}. */
    private static final List<HashPartitioned> HASH = List.of(
            new HashPartitioned("finding", 32,
                    "Medium reference profile (DOC-01 section 12.1) with headroom to Extra large: 32 keeps the "
                            + "largest single partition within index-maintenance budget at Extra large finding "
                            + "volume.", "OQ-015"),
            new HashPartitioned("finding_fingerprint_input", 32,
                    "Matched to finding. A different modulus on a table joined to finding on tenant_id defeats "
                            + "partitionwise join, the access path deduplication depends on.", "OQ-015"),
            new HashPartitioned("finding_asset_impact", 32,
                    "Matched to finding, for the partitionwise join on tenant_id used by every impact rollup.",
                    "OQ-015"),
            new HashPartitioned("component", 32,
                    "Matched to finding. Component identity is interned tenant-scoped (ADR-032), so the "
                            + "distribution follows tenant count rather than component count.", "OQ-015"),
            new HashPartitioned("sbom_snapshot", 32,
                    "Matched to component, so a snapshot and its components share a partition and the match "
                            + "sweep is partition-local.", "OQ-015"),
            new HashPartitioned("component_entry", 32,
                    "Matched to sbom_snapshot. The largest row count in the composition context, always read "
                            + "through its snapshot.", "OQ-015"),
            new HashPartitioned("rm_posture_aggregate", 32,
                    "Matched to the write-side tables it projects from, so a rebuild reads and writes within "
                            + "one partition per tenant.", "OQ-015"),
            new HashPartitioned("rm_finding_index", 32,
                    "Matched to finding, which it indexes. A divergent modulus makes every projection catch-up "
                            + "a cross-partition scan.", "OQ-015"),
            new HashPartitioned("rm_work_queue", 32,
                    "Matched to the other read models. The queue is read per scope and scope is tenant-bound, "
                            + "so tenant hashing gives partition-local interactive reads.", "OQ-015"));

    /** The five range-partitioned tables. */
    private static final List<RangePartitioned> RANGE = List.of(
            new RangePartitioned("audit_event", "ensure_audit_partitions", 3, true),
            new RangePartitioned("audit_event_payload", "ensure_audit_partitions", 3, true),
            new RangePartitioned("work_item_state_transition", "ensure_transition_log_partitions", 3, true),
            new RangePartitioned("risk_score", "ensure_risk_score_partitions", 3, false),
            new RangePartitioned("automation_execution", "ensure_automation_execution_partitions", 3, false));

    private PartitionPlan() {
    }

    public static List<HashPartitioned> hashPartitioned() {
        return HASH;
    }

    public static List<RangePartitioned> rangePartitioned() {
        return RANGE;
    }

    /** Counts by table, for comparing the model against {@code hash_partition_counts()} in the database. */
    public static Map<String, Integer> hashCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (HashPartitioned table : HASH) {
            counts.put(table.tableName(), table.partitionCount());
        }
        return Map.copyOf(counts);
    }
}
