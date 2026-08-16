package aspm.kernel.tenantcontext.contract;

import aspm.sharedkernel.AspmModule;

/**
 * Descriptor for the tenant-context platform kernel module: Context establishment, propagation, and the data access gate.
 *
 * <p>Kernel module per DOC-02 section 6.2. CON-PLT-011: a kernel module depends on no
 * domain module, enforced structurally by the absence of any module subproject from this
 * subproject's dependencies and asserted on bytecode by :architecture-tests.
 */
@AspmModule(name = "tenant-context", classification = "kernel")
public final class TenantContextModule {

    private TenantContextModule() {
        throw new AssertionError("descriptor is not instantiable");
    }
}
