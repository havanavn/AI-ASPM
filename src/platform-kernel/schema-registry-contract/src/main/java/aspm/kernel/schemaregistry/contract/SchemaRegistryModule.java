package aspm.kernel.schemaregistry.contract;

import aspm.sharedkernel.AspmModule;

/**
 * Descriptor for the schema-registry platform kernel module: Typed attribute schemas for custom fields and type registries.
 *
 * <p>Kernel module per DOC-02 section 6.2. CON-PLT-011: a kernel module depends on no
 * domain module, enforced structurally by the absence of any module subproject from this
 * subproject's dependencies and asserted on bytecode by :architecture-tests.
 */
@AspmModule(name = "schema-registry", classification = "kernel")
public final class SchemaRegistryModule {

    private SchemaRegistryModule() {
        throw new AssertionError("descriptor is not instantiable");
    }
}
