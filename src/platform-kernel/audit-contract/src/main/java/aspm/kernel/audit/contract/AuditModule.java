package aspm.kernel.audit.contract;

import aspm.sharedkernel.AspmModule;

/**
 * Descriptor for the audit platform kernel module: Event recording with integrity.
 *
 * <p>Kernel module per DOC-02 section 6.2. CON-PLT-011: a kernel module depends on no
 * domain module, enforced structurally by the absence of any module subproject from this
 * subproject's dependencies and asserted on bytecode by :architecture-tests.
 */
@AspmModule(name = "audit", classification = "kernel")
public final class AuditModule {

    private AuditModule() {
        throw new AssertionError("descriptor is not instantiable");
    }
}
