package aspm.module.ingestion.domain;

import java.util.Objects;

/**
 * Asset class assignment, per DOC-11 section 7 and {@code PRD-ING-004}.
 *
 * <p>"Infrastructure findings are ingested for context and <b>must not contribute to application posture
 * scores</b>… an application posture figure dominated by operating system patch findings tells an application
 * team nothing they can act on."
 *
 * <p><b>{@code PRD-ING-032}: assigned by the parser and NOT tenant-configurable per finding.</b> The rationale is
 * a gaming path: "a tenant reclassifying infrastructure findings as application would inflate or deflate their
 * application posture at will" (DOC-28 section 13.2). So this class offers no per-finding override and there is
 * no setter — reclassification would be a parser change, which is code and is reviewed.
 */
public final class AssetClassAssignment {

    public enum AssetClass {
        /** The product's subject. Contributes to application posture. */
        APPLICATION,
        /** Reported separately. Voluminous and legitimate to hold; not application posture. */
        INFRASTRUCTURE,
        /** Adjacent domain (NG-02). Reported separately. */
        CLOUD;

        /** Whether findings of this class contribute to the application posture figure. */
        public boolean contributesToApplicationPosture() {
            return this == APPLICATION;
        }
    }

    /** What a container finding is about, which decides its class. */
    public enum ContainerSubject {
        /** An application dependency inside the image. */
        APPLICATION_DEPENDENCY,
        /** The base image or operating system packages. */
        BASE_IMAGE_OR_OS
    }

    private AssetClassAssignment() {
        throw new AssertionError("not instantiable");
    }

    /**
     * Classifies a container finding by <b>subject</b>, per {@code PRD-ING-033}.
     *
     * <p>"Classifying by <em>where the finding was found</em> puts every container finding in one class, and the
     * two have different owners and different remediation paths." An application dependency in an image is fixed
     * by the application team changing a manifest; a base image CVE is fixed by the platform team changing a base
     * image. One class for both routes half of them to someone who cannot act.
     *
     * <p>Takes the subject as an argument with no default. A default would silently put every container finding in
     * one class, which is the failure the requirement names.
     */
    public static AssetClass forContainerFinding(ContainerSubject subject) {
        Objects.requireNonNull(subject,
                "a container finding's subject is required and has no default. PRD-ING-033 splits container "
                        + "findings by what the finding is ABOUT, and a default would put every one of them in "
                        + "one class — which is exactly the failure the requirement names.");
        return switch (subject) {
            case APPLICATION_DEPENDENCY -> AssetClass.APPLICATION;
            case BASE_IMAGE_OR_OS -> AssetClass.INFRASTRUCTURE;
        };
    }
}
