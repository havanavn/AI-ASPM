package aspm.deployment;

import aspm.deployment.RuntimeUnit.Destination;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What each runtime unit may reach. {@code OPS-DEP-014}, {@code OPS-DEP-015}.
 *
 * <p>"Egress MUST be deny-by-default with an allowlist <b>per runtime unit</b>, and a unit MUST NOT reach a
 * destination outside its allowlist."
 *
 * <p>{@code OPS-DEP-014}'s rationale: "Three surfaces make egress the platform's most exposed injection class:
 * the document parser, webhook delivery, and connectors. Deny-by-default at the network layer is <b>the control
 * that holds when an application-layer check is missed</b>."
 *
 * <h2>Why per unit</h2>
 *
 * <p>A platform-wide allowlist is the union of every unit's needs, so the unit processing hostile documents
 * inherits the connector's reach. Per unit, a server-side request forgery in the parser has nowhere to go even
 * if the integration module's {@code EgressPolicy} were bypassed entirely. That class is the application-layer
 * check over configured connector destinations; this is the network-layer allowlist over a unit. Two controls at
 * two layers, named differently so PP-10 holds.
 *
 * <h2>Why this is not a field on {@link RuntimeUnit}</h2>
 *
 * <p>It was, and Error Prone rejected it: {@code ImmutableEnumChecker} on a {@code Set} field, then
 * {@code EnumOrdinal} on the bitmask that replaced it. Both are right, and the second attempt was the tell — a
 * design needing an ordinal-indexed bitmask to satisfy a checker is a design putting configuration where
 * identity belongs. A unit's placement and resource profile are what it <i>is</i>; its allowlist is what a
 * deployment <i>grants</i> it, and the two change for different reasons.
 */
public final class EgressAllowlist {

    private static final Map<RuntimeUnit, Set<Destination>> BY_UNIT = build();

    private EgressAllowlist() {
    }

    private static Map<RuntimeUnit, Set<Destination>> build() {
        Map<RuntimeUnit, Set<Destination>> allowlists = new EnumMap<>(RuntimeUnit.class);

        allowlists.put(RuntimeUnit.INGRESS, EnumSet.of(Destination.PUBLIC_INBOUND));

        // Serves the application. Reaches nothing itself.
        allowlists.put(RuntimeUnit.WEB_TIER, EnumSet.noneOf(Destination.class));

        allowlists.put(RuntimeUnit.APPLICATION_TIER, EnumSet.of(
                Destination.IDENTITY_PROVIDER, Destination.SECRETS_STORE, Destination.KEY_MANAGEMENT));

        // The document parser and webhook delivery both live here — two of the three surfaces
        // OPS-DEP-014 names. This allowlist is what a parser exploit is confined to.
        //
        // Observation for DOC-15 section 4: its unit table has seven rows and none is an AI worker, so
        // model-provider egress has no unit of its own and lands here by elimination, on the same allowlist
        // as the parser processing hostile documents. That is the union problem this class argues against,
        // arriving because the table has no row for it. Recorded rather than resolved — an eighth unit is a
        // topology change and belongs in the document.
        allowlists.put(RuntimeUnit.GENERAL_WORKERS, EnumSet.of(
                Destination.CONNECTOR_ALLOWLIST, Destination.WEBHOOK_ALLOWLIST, Destination.MAIL_RELAY,
                Destination.MODEL_PROVIDER, Destination.SECRETS_STORE, Destination.KEY_MANAGEMENT));

        allowlists.put(RuntimeUnit.MATCH_WORKERS, EnumSet.of(
                Destination.INTELLIGENCE_FEED, Destination.SECRETS_STORE));

        allowlists.put(RuntimeUnit.PROJECTION_WORKERS, EnumSet.of(Destination.SECRETS_STORE));

        // OPS-DEP-007: the scheduler only enqueues work. It needs no destination at all.
        allowlists.put(RuntimeUnit.SCHEDULER, EnumSet.noneOf(Destination.class));

        for (RuntimeUnit unit : RuntimeUnit.values()) {
            if (!allowlists.containsKey(unit)) {
                throw new IllegalStateException(
                        unit + " has no allowlist. Under deny-by-default at the cluster a unit with no policy "
                                + "gets the cluster default, and the cluster default is allow (OPS-DEP-014). "
                                + "A missing entry is therefore the opposite of what its absence suggests.");
            }
            allowlists.put(unit, Set.copyOf(allowlists.get(unit)));
        }
        return Map.copyOf(allowlists);
    }

    public static Set<Destination> of(RuntimeUnit unit) {
        Objects.requireNonNull(unit, "a unit is required");
        return BY_UNIT.get(unit);
    }

    public static boolean mayReach(RuntimeUnit unit, Destination destination) {
        Objects.requireNonNull(destination, "a destination is required");
        return of(unit).contains(destination);
    }

    /** Every unit's allowlist, for generating the network policies. Ordered for a stable diff. */
    public static Map<RuntimeUnit, Set<Destination>> all() {
        return BY_UNIT;
    }
}
