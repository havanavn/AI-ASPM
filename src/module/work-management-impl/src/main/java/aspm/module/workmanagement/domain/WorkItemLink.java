package aspm.module.workmanagement.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A link between two work items, with its inverse maintained automatically. {@code INV-WRK-07},
 * DOC-04 section 16.6.
 *
 * <p><b>Both directions are stored.</b> DOC-04 section 16.6: "Storing both directions rather than deriving the
 * inverse means each direction is independently indexable, which matters because <i>what blocks this item</i> and
 * <i>what does this item block</i> are both frequent and the first drives the blocked-work queue."
 *
 * <p>{@link #withInverse} is the only way to build one, so a caller cannot write a single direction. A link
 * present in one direction only is worse than no link: the blocked-work queue would miss it while the item view
 * showed it, and the two would disagree with nobody able to say which was right.
 */
public record WorkItemLink(UUID fromItemId, UUID toItemId, LinkType linkType) {

    /**
     * The link types of DOC-04 section 16.6, each paired with its inverse.
     *
     * <p>Product-fixed rather than tenant vocabulary: {@code BLOCKS} drives the blocked-work queue of
     * {@code PRD-CAP-015} and {@code DUPLICATES} drives deduplication behaviour, so a tenant-invented type would
     * be a link nothing acts on. Renaming for display is a localization concern ({@code NFR-INT-003}), not a
     * schema one.
     */
    public enum LinkType {
        BLOCKS,
        IS_BLOCKED_BY,
        RELATES_TO,
        DUPLICATES,
        IS_DUPLICATED_BY,
        CAUSED_BY,
        CAUSES;

        /** The type written in the opposite direction. */
        public LinkType inverse() {
            return switch (this) {
                case BLOCKS -> IS_BLOCKED_BY;
                case IS_BLOCKED_BY -> BLOCKS;
                // Symmetric: its own inverse. The pair is still two rows, because a query for "related items"
                // must find it from either end without an OR across two columns.
                case RELATES_TO -> RELATES_TO;
                case DUPLICATES -> IS_DUPLICATED_BY;
                case IS_DUPLICATED_BY -> DUPLICATES;
                case CAUSED_BY -> CAUSES;
                case CAUSES -> CAUSED_BY;
            };
        }

        public boolean symmetric() {
            return inverse() == this;
        }

        /** Whether this direction means the {@code from} item is waiting on the {@code to} item. */
        public boolean blocksProgress() {
            return this == IS_BLOCKED_BY;
        }
    }

    public WorkItemLink {
        Objects.requireNonNull(fromItemId, "fromItemId is required");
        Objects.requireNonNull(toItemId, "toItemId is required");
        Objects.requireNonNull(linkType, "linkType is required");
        if (fromItemId.equals(toItemId)) {
            throw new IllegalArgumentException(
                    "an item cannot link to itself; a self-blocking item is a deadlock the blocked-work queue "
                            + "would report forever");
        }
    }

    /**
     * The pair of rows one logical link produces.
     *
     * <p>Returns both because {@code INV-WRK-07} makes inverse maintenance automatic rather than the caller's
     * responsibility — a caller who wrote one direction and forgot the other would produce a link the two views
     * disagree about.
     */
    public static List<WorkItemLink> withInverse(UUID fromItemId, UUID toItemId, LinkType linkType) {
        WorkItemLink forward = new WorkItemLink(fromItemId, toItemId, linkType);
        WorkItemLink inverse = new WorkItemLink(toItemId, fromItemId, linkType.inverse());
        return List.of(forward, inverse);
    }

    /** The rows to remove when a link is removed. Both, for the same reason both are written. */
    public List<WorkItemLink> withInverse() {
        return List.of(this, new WorkItemLink(toItemId, fromItemId, linkType.inverse()));
    }

    /**
     * Detects a cycle that would deadlock the blocked-work queue.
     *
     * <p>Only {@code BLOCKS}/{@code IS_BLOCKED_BY} is checked. A cycle of {@code RELATES_TO} is ordinary and
     * frequently correct; a cycle of blocking is a set of items none of which can ever start, and it presents as
     * work that quietly never gets picked up rather than as an error anybody sees.
     *
     * @param existing every link already stored for the items involved
     * @return true where adding {@code candidate} would close a blocking cycle
     */
    public static boolean wouldCreateBlockingCycle(WorkItemLink candidate, List<WorkItemLink> existing) {
        Objects.requireNonNull(candidate, "a candidate link is required");
        Objects.requireNonNull(existing, "the existing links are required");
        if (!candidate.linkType().blocksProgress()) {
            return false;
        }
        // Walk forward from what the candidate says this item is blocked by, looking for a way back.
        Set<UUID> seen = new LinkedHashSet<>();
        java.util.Deque<UUID> frontier = new java.util.ArrayDeque<>();
        frontier.add(candidate.toItemId());
        while (!frontier.isEmpty()) {
            UUID current = frontier.removeFirst();
            if (current.equals(candidate.fromItemId())) {
                return true;
            }
            if (!seen.add(current)) {
                continue;
            }
            for (WorkItemLink link : existing) {
                if (link.linkType().blocksProgress() && link.fromItemId().equals(current)) {
                    frontier.addLast(link.toItemId());
                }
            }
        }
        return false;
    }
}
