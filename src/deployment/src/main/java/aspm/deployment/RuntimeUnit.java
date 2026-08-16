package aspm.deployment;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The runtime units of DOC-15 §4, with their placement, egress and resource constraints.
 *
 * <p>Three DOC-15 requirements are placement or network properties that no application-layer test can reach:
 *
 * <ul>
 *   <li>{@code OPS-DEP-005} — every unit declares requests and limits, and exceeding memory restarts the unit
 *   <li>{@code OPS-DEP-006} — match workers are not co-scheduled with the application tier
 *   <li>{@code OPS-DEP-014} — egress is deny-by-default with an allowlist <b>per unit</b>
 * </ul>
 *
 * <h2>Why the allowlist is per unit and not per platform</h2>
 *
 * <p>{@code OPS-DEP-014}: "Three surfaces make egress the platform's most exposed injection class: the document
 * parser, webhook delivery, and connectors. Deny-by-default at the network layer is <b>the control that holds
 * when an application-layer check is missed</b>."
 *
 * <p>A platform-wide allowlist is the union of every unit's needs, so the parser — the unit processing hostile
 * documents — inherits the connector's reach. Per unit, the parser reaches nothing, and a server-side request
 * forgery in it has nowhere to go even if {@code EgressPolicy} in the integration module were bypassed entirely.
 *
 * <p>That module's {@code EgressPolicy} is the application-layer check over configured connector destinations.
 * This is the network-layer allowlist over a unit. Two controls, deliberately, at two layers — named differently
 * here so PP-10 holds: one name, one meaning, one place.
 */
public enum RuntimeUnit {

    /** TLS termination, WAF, rate limit class. Makes no authorization decision ({@code OPS-DEP-017}). */
    INGRESS(Placement.EDGE, Profile.CPU_BOUND, false),

    WEB_TIER(Placement.APPLICATION_POOL, Profile.CPU_BOUND, true),

    /** All domain modules. Stateless. */
    APPLICATION_TIER(Placement.APPLICATION_POOL, Profile.CPU_BOUND_MODERATE_MEMORY, true),

    /**
     * Ingestion, export, report, dispatch.
     *
     * <p>The document parser lives here, and so does webhook delivery — two of the three surfaces
     * {@code OPS-DEP-014} names. The allowlist below is what a parser exploit is confined to.
     *
     * <p><b>Observation for DOC-15 §4.</b> Its unit table has seven rows and none of them is an AI worker, so
     * model-provider egress has no unit of its own and lands here by elimination — on the same allowlist as the
     * parser that processes hostile documents. That is the union problem this class's header argues against,
     * arriving because the table has no row for it. Recorded rather than resolved: adding an eighth unit is a
     * topology change and belongs in the document, not in this file.
     */
    GENERAL_WORKERS(Placement.WORKER_POOL, Profile.BALANCED, true),

    /**
     * Intelligence database resident, memory-heavy, isolated per {@code CON-PLT-008}.
     *
     * <p>{@code OPS-DEP-006}: "The failure this prevents occurs during a portfolio sweep triggered by a
     * high-profile disclosure — <b>precisely when the platform must be available</b>."
     */
    MATCH_WORKERS(Placement.MATCH_POOL, Profile.MEMORY_HEAVY, true),

    PROJECTION_WORKERS(Placement.WORKER_POOL, Profile.CPU_AND_IO, true),

    /** Singleton with leader election; emits scheduled work, performs none ({@code OPS-DEP-007}). */
    SCHEDULER(Placement.WORKER_POOL, Profile.MINIMAL, false);

    /**
     * A scheduling pool. Two units in different pools are not co-scheduled; two in the same pool may be.
     *
     * <p>This is the whole of {@code OPS-DEP-006}: match workers have their own pool, so the assertion is that
     * no other unit shares it, not that some anti-affinity annotation is present. An annotation can be satisfied
     * by a cluster that ignores it.
     */
    public enum Placement { EDGE, APPLICATION_POOL, WORKER_POOL, MATCH_POOL }

    /** Egress destination classes. Deny-by-default: absence from a unit's set means unreachable. */
    public enum Destination {
        PUBLIC_INBOUND,
        IDENTITY_PROVIDER,
        SECRETS_STORE,
        KEY_MANAGEMENT,
        CONNECTOR_ALLOWLIST,
        WEBHOOK_ALLOWLIST,
        MAIL_RELAY,
        INTELLIGENCE_FEED,
        MODEL_PROVIDER
    }

    /** {@code OPS-DEP-005}. Requests and limits are declared per profile, never omitted. */
    public enum Profile {
        CPU_BOUND, CPU_BOUND_MODERATE_MEMORY, BALANCED, MEMORY_HEAVY, CPU_AND_IO, MINIMAL
    }

    private final Placement placement;
    private final Profile profile;
    private final boolean holdsDatabaseCredential;

    RuntimeUnit(Placement placement, Profile profile, boolean holdsDatabaseCredential) {
        this.placement = placement;
        this.profile = profile;
        this.holdsDatabaseCredential = holdsDatabaseCredential;
    }

    public Placement placement() {
        return placement;
    }

    /** Delegates to {@link EgressAllowlist}; see that class for why the allowlist is not a field here. */
    public Set<Destination> egressAllowlist() {
        return EgressAllowlist.of(this);
    }

    public Profile profile() {
        return profile;
    }

    public boolean holdsDatabaseCredential() {
        return holdsDatabaseCredential;
    }

    /** {@code OPS-DEP-014}. Anything not on the unit's allowlist. */
    public boolean mayReach(Destination destination) {
        return EgressAllowlist.mayReach(this, destination);
    }

    /**
     * {@code OPS-DEP-005}: a unit exceeding its memory limit is restarted rather than permitted to degrade the
     * node.
     *
     * <p>Not configurable. "Match workers hold a large intelligence database; without a limit an out-of-memory
     * condition takes the node and everything on it, including the API tier if co-scheduled" — and the node it
     * takes is shared with whatever else the scheduler put there.
     */
    public boolean restartOnMemoryLimit() {
        return true;
    }

    /**
     * {@code OPS-DEP-008}: readiness and liveness are separate, readiness fails on an unavailable dependency and
     * liveness does not.
     *
     * <p>"Conflating them causes a restart loop during a dependency outage, which turns a degraded state into an
     * outage." Expressed as two distinct probes rather than a boolean, so a deployment cannot point both at the
     * same endpoint — which is how they get conflated in practice.
     */
    public record Probes(String readinessPath, String livenessPath) {

        public Probes {
            Objects.requireNonNull(readinessPath, "a readiness path is required");
            Objects.requireNonNull(livenessPath, "a liveness path is required");
            if (readinessPath.equals(livenessPath)) {
                throw new IllegalArgumentException(
                        "readiness and liveness point at the same endpoint, so a dependency outage will fail "
                                + "liveness and restart the unit. That turns a degraded state into an outage "
                                + "(OPS-DEP-008), and it does so at the moment the dependency is already down.");
            }
        }
    }

    public Probes probes() {
        return new Probes("/internal/health/ready", "/internal/health/live");
    }

    /** The units sharing a pool with this one. Empty for a unit with a pool to itself. */
    public List<RuntimeUnit> coScheduledWith() {
        return java.util.Arrays.stream(values())
                .filter(other -> other != this && other.placement == this.placement)
                .toList();
    }

    /** Every unit's allowlist, for generating the network policies. */
    public static Map<RuntimeUnit, Set<Destination>> egressPolicies() {
        return EgressAllowlist.all();
    }
}
