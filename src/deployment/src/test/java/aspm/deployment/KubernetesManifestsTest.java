package aspm.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Helm chart is generated from the model, and this test is the generator. {@code OPS-DEP-003},
 * {@code OPS-DEP-005}, {@code OPS-DEP-014}, {@code OPS-DEP-031}.
 *
 * <p>Same pattern as the operation manifest: the build writes {@code deploy/k8s/chart/aspm/files/model.yaml}
 * from {@link KubernetesManifests} and copies {@code apply.sh} and {@code conformance.sql} into the
 * chart's {@code files/}, so a chart that disagrees with the model or with the compose deployment is
 * a diff in the working tree rather than a discovery in a cluster.
 */
class KubernetesManifestsTest {

    private static Path corpusRoot() {
        return Path.of(System.getProperty("aspm.corpusRoot", "..")).toAbsolutePath().normalize();
    }

    @Test
    @DisplayName("OPS-DEP-014: every shipped unit carries exactly the egress allowlist the model declares, and units that do not ship are named")
    void modelIsTheAllowlist() {
        for (KubernetesManifests.ShippedUnit u : KubernetesManifests.shipped()) {
            assertEquals(EgressAllowlist.of(u.unit()), u.egress(), u.name());
            assertFalse(u.readinessPath().equals(u.livenessPath()), "OPS-DEP-008: probes are distinct");
        }
        // The application tier must not be able to reach the mail relay or webhook destinations: that
        // is the reason the worker is a separate Deployment at all.
        var app = KubernetesManifests.shipped().stream().filter(u -> u.name().equals("app")).findFirst().orElseThrow();
        assertFalse(app.egress().contains(RuntimeUnit.Destination.MAIL_RELAY));
        assertFalse(app.egress().contains(RuntimeUnit.Destination.WEBHOOK_ALLOWLIST));
        var worker = KubernetesManifests.shipped().stream().filter(u -> u.name().equals("worker")).findFirst().orElseThrow();
        assertTrue(worker.egress().contains(RuntimeUnit.Destination.MAIL_RELAY));
        assertEquals(5, KubernetesManifests.notShipped().size(), "seven units in DOC-15 §4, two shipped, five explained");
    }

    @Test
    @DisplayName("OPS-DEP-003: the chart's model file and its copies of apply.sh and conformance.sql are written from the source of truth")
    void chartFilesAreGenerated() throws IOException {
        Path chart = corpusRoot().resolve("deploy/k8s/chart/aspm");
        if (!Files.isDirectory(chart)) {
            // A checkout without the chart (a module-only build) has nothing to generate into.
            return;
        }
        Path files = chart.resolve("files");
        Files.createDirectories(files);
        String model = KubernetesManifests.modelYaml();
        assertTrue(model.contains("MAIL_RELAY") && model.contains("units:"), "the model names egress classes");
        Files.writeString(files.resolve("model.yaml"), model, StandardCharsets.UTF_8);
        // The same scripts the compose deployment runs, so a cluster and a laptop verify the same thing.
        Files.copy(corpusRoot().resolve("deploy/migrate/apply.sh"), files.resolve("apply.sh"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(corpusRoot().resolve("deploy/verify/conformance.sql"), files.resolve("conformance.sql"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertTrue(Files.size(files.resolve("model.yaml")) > 200);
    }
}
