package aspm.deployment;

import aspm.deployment.RuntimeUnit.Destination;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Kubernetes half of DOC-15: what the Helm chart is GENERATED from. {@code OPS-DEP-003},
 * {@code OPS-DEP-005}, {@code OPS-DEP-006}, {@code OPS-DEP-008}, {@code OPS-DEP-009}, {@code OPS-DEP-014}.
 *
 * <p>This subproject's build file states the intent: "the manifests are generated FROM this model
 * rather than checked against it. A checker and a manifest drift; a generator cannot." This class is
 * that generator. It emits the chart's {@code files/model.yaml} — one entry per runtime unit the
 * release ships, with the unit's egress allowlist from {@link EgressAllowlist}, its resource profile,
 * its probes and whether it holds the database credential — and the chart templates read that file
 * rather than carrying their own copy of any of it. A NetworkPolicy in the chart therefore cannot say
 * something the model does not.
 *
 * <h2>Which units ship</h2>
 *
 * <p>The application runs today as ONE artifact in two modes: {@code ASPM_ROLE=app} (the application
 * tier) and {@code ASPM_ROLE=worker} (the general workers: notification delivery, housekeeping). The
 * web tier is served by the application tier (ADR-058); match, projection and scheduler units are
 * modelled and not yet separately deployable, and the model says so rather than emitting a Deployment
 * for a process that does not exist ({@code OPS-DEP-003}: same artifact, same code paths, configuration
 * decides the unit).
 *
 * <h2>Mesh</h2>
 *
 * <p>The chart is mesh-native in the sense that matters for this platform: TLS terminates at the
 * ingress, service-to-service traffic is mTLS when a mesh is present (Istio {@code PeerAuthentication
 * STRICT}, or Linkerd injection), and egress is deny-by-default per unit — enforced twice, by a
 * Kubernetes NetworkPolicy generated from this model and, under Istio, by a {@code Sidecar} with
 * {@code REGISTRY_ONLY} outbound policy plus one {@code ServiceEntry} per allowed external destination.
 * The mesh is an option ({@code mesh: none | istio | linkerd}); the NetworkPolicy is not, because a
 * deployment without a mesh must still refuse a webhook to the metadata service.
 */
public final class KubernetesManifests {

    /** A unit the chart deploys, with what the templates need to know about it. */
    public record ShippedUnit(RuntimeUnit unit, String name, String role, boolean servesHttp,
            Set<Destination> egress, Resources resources, boolean holdsDatabaseCredential,
            String readinessPath, String livenessPath) {
    }

    /** {@code OPS-DEP-005}: requests and limits per profile, never omitted. */
    public record Resources(String cpuRequest, String cpuLimit, String memoryRequest, String memoryLimit) {
    }

    private KubernetesManifests() {
    }

    /** The resource envelope for a profile. Starting points a deployment tunes from measurement. */
    public static Resources resourcesFor(RuntimeUnit.Profile profile) {
        return switch (profile) {
            case CPU_BOUND -> new Resources("250m", "1", "256Mi", "512Mi");
            case CPU_BOUND_MODERATE_MEMORY -> new Resources("500m", "2", "1Gi", "2Gi");
            case BALANCED -> new Resources("250m", "1", "512Mi", "1Gi");
            case MEMORY_HEAVY -> new Resources("500m", "2", "2Gi", "4Gi");
            case CPU_AND_IO -> new Resources("250m", "1", "512Mi", "1Gi");
            case MINIMAL -> new Resources("50m", "200m", "64Mi", "128Mi");
        };
    }

    /** The units this release deploys. */
    public static List<ShippedUnit> shipped() {
        RuntimeUnit app = RuntimeUnit.APPLICATION_TIER;
        RuntimeUnit workers = RuntimeUnit.GENERAL_WORKERS;
        return List.of(
                new ShippedUnit(app, "app", "app", true, EgressAllowlist.of(app), resourcesFor(app.profile()),
                        app.holdsDatabaseCredential(), app.probes().readinessPath(), app.probes().livenessPath()),
                new ShippedUnit(workers, "worker", "worker", false, EgressAllowlist.of(workers), resourcesFor(workers.profile()),
                        workers.holdsDatabaseCredential(), workers.probes().readinessPath(), workers.probes().livenessPath()));
    }

    /** The units modelled in DOC-15 §4 that this release does not deploy separately, and why. */
    public static Map<RuntimeUnit, String> notShipped() {
        Map<RuntimeUnit, String> out = new LinkedHashMap<>();
        out.put(RuntimeUnit.INGRESS, "provided by the cluster: Gateway API or an Ingress controller, TLS terminated there (ADR-057)");
        out.put(RuntimeUnit.WEB_TIER, "served by the application tier: ADR-058 server-rendered shell plus the committed bundle");
        out.put(RuntimeUnit.MATCH_WORKERS, "composition matching runs in the application tier today; a separate pool arrives with the match queue");
        out.put(RuntimeUnit.PROJECTION_WORKERS, "read models are maintained in the write transaction today; a separate pool arrives with projection lag");
        out.put(RuntimeUnit.SCHEDULER, "no in-process scheduler by design (OPS-DEP-007); the scanner container's tick and the worker's housekeeping stand in");
        return out;
    }

    /** The chart's {@code files/model.yaml}. Deterministic: the same model produces the same bytes. */
    public static String modelYaml() {
        StringBuilder y = new StringBuilder();
        y.append("# GENERATED by aspm.deployment.KubernetesManifests from the DOC-15 runtime-unit model.\n");
        y.append("# Do not edit: KubernetesManifestsTest rewrites this file and fails on drift.\n");
        y.append("# Every NetworkPolicy, resource envelope and probe in the chart is read from here.\n");
        y.append("units:\n");
        for (ShippedUnit u : shipped()) {
            y.append("  ").append(u.name()).append(":\n");
            y.append("    modelUnit: ").append(u.unit().name()).append("\n");
            y.append("    role: ").append(u.role()).append("\n");
            y.append("    servesHttp: ").append(u.servesHttp()).append("\n");
            y.append("    holdsDatabaseCredential: ").append(u.holdsDatabaseCredential()).append("\n");
            y.append("    placement: ").append(u.unit().placement().name()).append("\n");
            y.append("    profile: ").append(u.unit().profile().name()).append("\n");
            y.append("    readinessPath: ").append(u.readinessPath()).append("\n");
            y.append("    livenessPath: ").append(u.livenessPath()).append("\n");
            y.append("    resources:\n");
            y.append("      cpuRequest: \"").append(u.resources().cpuRequest()).append("\"\n");
            y.append("      cpuLimit: \"").append(u.resources().cpuLimit()).append("\"\n");
            y.append("      memoryRequest: ").append(u.resources().memoryRequest()).append("\n");
            y.append("      memoryLimit: ").append(u.resources().memoryLimit()).append("\n");
            y.append("    egress:\n");
            List<String> egress = new ArrayList<>();
            for (Destination d : Destination.values()) {
                if (u.egress().contains(d)) {
                    egress.add(d.name());
                }
            }
            if (egress.isEmpty()) {
                y.append("      []\n");
            }
            for (String d : egress) {
                y.append("      - ").append(d).append("\n");
            }
        }
        y.append("notShipped:\n");
        notShipped().forEach((unit, why) -> y.append("  ").append(unit.name()).append(": \"").append(why.replace("\"", "'")).append("\"\n"));
        y.append("destinationClasses:\n");
        for (Destination d : Destination.values()) {
            y.append("  - ").append(d.name()).append("\n");
        }
        return y.toString();
    }
}
