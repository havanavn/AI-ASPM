package aspm.kernel.rulesengine.contract;

import aspm.sharedkernel.AspmModule;

/**
 * Descriptor for the rules-engine platform kernel module: Deterministic condition-action evaluation.
 *
 * <p>Kernel module per DOC-02 section 6.2. CON-PLT-011: a kernel module depends on no
 * domain module, enforced structurally by the absence of any module subproject from this
 * subproject's dependencies and asserted on bytecode by :architecture-tests.
 */
@AspmModule(name = "rules-engine", classification = "kernel")
public final class RulesEngineModule {

    private RulesEngineModule() {
        throw new AssertionError("descriptor is not instantiable");
    }
}
