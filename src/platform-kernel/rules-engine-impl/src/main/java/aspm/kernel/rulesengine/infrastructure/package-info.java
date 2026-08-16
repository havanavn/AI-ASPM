/**
 * Infrastructure layer of the rules-engine kernel module.
 *
 * <p>Layering per DOC-02 section 8.1: dependencies point inward. The domain layer
 * carries no persistence, framework, transport or serialization dependency
 * (CON-PLT-017), asserted on bytecode by :architecture-tests.
 */
package aspm.kernel.rulesengine.infrastructure;
