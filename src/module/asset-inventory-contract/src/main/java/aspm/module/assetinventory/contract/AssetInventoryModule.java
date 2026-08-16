package aspm.module.assetinventory.contract;

import aspm.sharedkernel.AspmModule;

/**
 * Descriptor for the asset-inventory module [core], DOC-03 section 5.1 context 1.
 *
 * <p>Published Language consumer of Organization & Scope (DOC-03 5.3 row 1).
 *
 * <p>The annotation carries the DECLARED half of CON-PLT-014; the Gradle subproject
 * dependency list is the enforced half. :architecture-tests cross-checks the two, with the
 * limitation recorded in {@code DependencyDirectionTest}: javac inlines a compile-time
 * constant, so a reference to a {@code static final String} alone leaves no bytecode
 * dependency to observe. That case is caught by the compile classpath, not by bytecode.
 */
@AspmModule(
name = "asset-inventory",
classification = "core",
permittedDependencies = {"organization-scope"})
public final class AssetInventoryModule {

    private AssetInventoryModule() {
        throw new AssertionError("descriptor is not instantiable");
    }
}
