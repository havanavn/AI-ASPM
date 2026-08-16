package aspm.kernel.authorization.contract;

import aspm.sharedkernel.AspmModule;

/**
 * Descriptor for the authorization platform kernel module: The single permission evaluation contract.
 *
 * <p>Kernel module per DOC-02 section 6.2. CON-PLT-011: a kernel module depends on no
 * domain module, enforced structurally by the absence of any module subproject from this
 * subproject's dependencies and asserted on bytecode by :architecture-tests.
 */
@AspmModule(name = "authorization", classification = "kernel")
public final class AuthorizationModule {

    private AuthorizationModule() {
        throw new AssertionError("descriptor is not instantiable");
    }
}
