package aspm.module.organizationscope.contract;

import aspm.sharedkernel.AspmModule;

/**
 * Descriptor for the organization-scope module [supporting], DOC-03 section 5.1 context 6.
 *
 * <p>Publishes OrgNodeId and the immutable ScopeDescriptor. Depends on no domain module.
 *
 * <p>The annotation carries the DECLARED half of CON-PLT-014; the Gradle subproject
 * dependency list is the enforced half. :architecture-tests cross-checks the two, with the
 * limitation recorded in {@code DependencyDirectionTest}: javac inlines a compile-time
 * constant, so a reference to a {@code static final String} alone leaves no bytecode
 * dependency to observe. That case is caught by the compile classpath, not by bytecode.
 */
@AspmModule(name = "organization-scope", classification = "supporting")
public final class OrganizationScopeModule {

    private OrganizationScopeModule() {
        throw new AssertionError("descriptor is not instantiable");
    }
}
