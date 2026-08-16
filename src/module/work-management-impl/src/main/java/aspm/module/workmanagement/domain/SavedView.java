package aspm.module.workmanagement.domain;

import aspm.kernel.rulesengine.contract.Condition;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A saved query. {@code INV-WRK-11}, DOC-04 section 16.8.
 *
 * <p><b>No stored result set and no stored scope.</b> DOC-04 section 16.8: "Storing the author's scope with the
 * query would make a shared link carry the author's visibility — a scope escalation available to anyone with the
 * link."
 *
 * <p>That is the whole design. A saved view holds <b>filters only</b>; scope is applied at evaluation from the
 * viewer's own context. Two people opening the same shared view see different rows, and that is correct — PP-4:
 * "Scope is derived, never asserted by the client."
 *
 * <p>The class therefore has no field for a scope, no field for results, and no accessor that could return
 * either. A test asserts that shape, because the tempting optimisation — caching the author's result set so a
 * shared dashboard loads fast — is precisely the escalation.
 */
public final class SavedView {

    /** Who may open the view. Not who may see its contents: that is always the viewer's own scope. */
    public enum Sharing {
        PRIVATE,
        SHARED_TENANT,
        /** Shared with a subtree. Still evaluated against the viewer's scope, not the subtree's. */
        SHARED_SCOPE
    }

    private final UUID id;
    private final String name;
    private final UUID ownerPrincipalId;
    private final Condition filters;
    private Sharing sharing;
    private UUID sharedScopeNodeId;

    public SavedView(UUID id, String name, UUID ownerPrincipalId, Condition filters) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.name = Objects.requireNonNull(name, "a name is required");
        this.ownerPrincipalId = Objects.requireNonNull(ownerPrincipalId, "an owner is required");
        this.filters = Objects.requireNonNull(filters,
                "filters are required. A saved view holds filters ONLY — scope is applied at evaluation from "
                        + "the viewer's context (INV-WRK-11).");
        this.sharing = Sharing.PRIVATE;
    }

    /**
     * Shares the view.
     *
     * <p>Requires {@code wrk.savedview.share}, checked by the caller. The check is not here because this class
     * cannot see the sharer's permissions, and a permission check that reads an ambient context is one nobody
     * notices is missing.
     */
    public void share(Sharing newSharing, UUID scopeNodeId) {
        Objects.requireNonNull(newSharing, "a sharing mode is required");
        if (newSharing == Sharing.SHARED_SCOPE && scopeNodeId == null) {
            throw new IllegalArgumentException("SHARED_SCOPE needs the node it is shared with");
        }
        if (newSharing != Sharing.SHARED_SCOPE && scopeNodeId != null) {
            throw new IllegalArgumentException(
                    "a scope node on " + newSharing + " sharing suggests the node bounds what the view returns. "
                            + "It does not: the VIEWER's scope does (INV-WRK-11). Storing it here would be the "
                            + "author-scope escalation DOC-04 section 16.8 rejects.");
        }
        this.sharing = newSharing;
        this.sharedScopeNodeId = scopeNodeId;
    }

    /** The filters, to be combined with the viewer's scope predicate as a conjunct at query time. */
    public Condition filters() {
        return filters;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public UUID ownerPrincipalId() {
        return ownerPrincipalId;
    }

    public Sharing sharing() {
        return sharing;
    }

    /** Who the view is offered to — never what it returns. */
    public Optional<UUID> sharedScopeNodeId() {
        return Optional.ofNullable(sharedScopeNodeId);
    }
}
