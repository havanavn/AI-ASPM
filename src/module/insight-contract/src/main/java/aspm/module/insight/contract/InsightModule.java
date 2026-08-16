package aspm.module.insight.contract;

import aspm.sharedkernel.AspmModule;

/**
 * Descriptor for the insight module [projection], DOC-03 section 5.1 context 17.
 *
 * <p>Projection over events via :platform-events. Not a domain context (DOC-03 5.1 row 17).
 *
 * <p>The annotation carries the DECLARED half of CON-PLT-014; the Gradle subproject
 * dependency list is the enforced half. :architecture-tests cross-checks the two, with the
 * limitation recorded in {@code DependencyDirectionTest}: javac inlines a compile-time
 * constant, so a reference to a {@code static final String} alone leaves no bytecode
 * dependency to observe. That case is caught by the compile classpath, not by bytecode.
 */
@AspmModule(name = "insight", classification = "projection")
public final class InsightModule {

    private InsightModule() {
        throw new AssertionError("descriptor is not instantiable");
    }
}
