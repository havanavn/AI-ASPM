package aspm.module.knowledge.contract;

import aspm.sharedkernel.AspmModule;

/**
 * Descriptor for the knowledge module [supporting], DOC-03 section 5.1 context 10.
 *
 * <p>Publishes guidance referenced by finding class and checklist item.
 *
 * <p>The annotation carries the DECLARED half of CON-PLT-014; the Gradle subproject
 * dependency list is the enforced half. :architecture-tests cross-checks the two, with the
 * limitation recorded in {@code DependencyDirectionTest}: javac inlines a compile-time
 * constant, so a reference to a {@code static final String} alone leaves no bytecode
 * dependency to observe. That case is caught by the compile classpath, not by bytecode.
 */
@AspmModule(name = "knowledge", classification = "supporting")
public final class KnowledgeModule {

    private KnowledgeModule() {
        throw new AssertionError("descriptor is not instantiable");
    }
}
