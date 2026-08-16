package aspm.module.integration.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Identity synchronization from a directory. DOC-21, and the prompt's constraint:
 * <b>"manages principal existence only and never writes role assignments."</b>
 *
 * <h2>Why the boundary is where it is</h2>
 *
 * <p>A directory knows who exists and who left. It does not know what they may do <i>in this platform</i> —
 * ADR-027 makes roles tenant-configured data, and a directory group named "Security Team" is an organizational
 * fact rather than a permission grant.
 *
 * <p>Writing role assignments from a directory would mean a group membership change in a system the platform
 * does not control silently alters authorization here, with no record in the platform's own access review and no
 * approval by anybody who understands what the role conveys. That is `SEC-AUZ-031`'s widening, arriving through
 * an integration.
 *
 * <p>So this class has exactly three operations, all about existence, and a test asserts no method mentions a
 * role, permission or grant.
 */
public final class IdentitySynchronization {

    /** One directory principal, as the connector sees it. */
    public record DirectoryPrincipal(String externalId, String displayName, boolean activeInDirectory) {

        public DirectoryPrincipal {
            Objects.requireNonNull(externalId, "an external identifier is required");
            Objects.requireNonNull(displayName, "a display name is required");
        }
    }

    /**
     * What the synchronization decided.
     *
     * @param deactivate principals absent from the directory or marked inactive. <b>Deactivated, not
     *     deleted</b>: a deleted principal orphans every audit entry, comment and assignment attributed to them,
     *     and the audit record is inviolable (PP-5)
     */
    public record Plan(List<DirectoryPrincipal> create, List<DirectoryPrincipal> updateDisplayName,
            List<UUID> deactivate) {

        public Plan {
            create = List.copyOf(Objects.requireNonNull(create, "create is required"));
            updateDisplayName = List.copyOf(
                    Objects.requireNonNull(updateDisplayName, "updateDisplayName is required"));
            deactivate = List.copyOf(Objects.requireNonNull(deactivate, "deactivate is required"));
        }
    }

    /**
     * Guard against a truncated directory read deactivating everybody.
     *
     * <p>A directory connector returning an empty or partial page is a routine failure, and acting on it
     * deactivates the entire tenant's principals — an outage the platform inflicts on itself, at the moment
     * nobody can log in to fix it.
     */
    public static final double MAXIMUM_DEACTIVATION_PROPORTION = 0.2;

    private IdentitySynchronization() {
    }

    /**
     * Plans a synchronization.
     *
     * @throws IllegalStateException where the plan would deactivate more than
     *     {@link #MAXIMUM_DEACTIVATION_PROPORTION} of known principals. Refusing is the right failure: a real
     *     mass departure is rare and can be confirmed, while a truncated read is common and unrecoverable
     */
    public static Plan plan(List<DirectoryPrincipal> directory,
            java.util.Map<String, UUID> knownByExternalId) {
        Objects.requireNonNull(directory, "the directory listing is required");
        Objects.requireNonNull(knownByExternalId, "the known principals are required");

        List<DirectoryPrincipal> create = new java.util.ArrayList<>();
        List<DirectoryPrincipal> update = new java.util.ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();

        for (DirectoryPrincipal principal : directory) {
            seen.add(principal.externalId());
            if (!principal.activeInDirectory()) {
                continue;
            }
            if (knownByExternalId.containsKey(principal.externalId())) {
                update.add(principal);
            } else {
                create.add(principal);
            }
        }

        List<UUID> deactivate = new java.util.ArrayList<>();
        knownByExternalId.forEach((externalId, id) -> {
            if (!seen.contains(externalId)) {
                deactivate.add(id);
            }
        });
        directory.stream()
                .filter(p -> !p.activeInDirectory())
                .map(p -> knownByExternalId.get(p.externalId()))
                .filter(Objects::nonNull)
                .forEach(deactivate::add);

        if (!knownByExternalId.isEmpty()
                && (double) deactivate.size() / knownByExternalId.size() > MAXIMUM_DEACTIVATION_PROPORTION) {
            throw new IllegalStateException(
                    "this synchronization would deactivate " + deactivate.size() + " of "
                            + knownByExternalId.size() + " principals, beyond the "
                            + (int) (MAXIMUM_DEACTIVATION_PROPORTION * 100) + "% guard. A directory connector "
                            + "returning a truncated page is a routine failure, and acting on it deactivates "
                            + "the tenant's principals — an outage the platform inflicts on itself, at the "
                            + "moment nobody can log in to fix it. A real mass departure is rare and can be "
                            + "confirmed.");
        }

        return new Plan(create, update, List.copyOf(deactivate));
    }
}
