   package aspm.module.identity.contract;

   import aspm.sharedkernel.AspmModule;

   /**
    * Descriptor for the identity module [generic], DOC-03 section 5.1 context 11.
    *
    * <p>Supplies principal existence only (ADR-041). Authorization consumes PrincipalId from the
* shared kernel rather than depending on this module, because CON-PLT-011 forbids a kernel
* dependency on a domain module.
    *
    * <p>The annotation carries the DECLARED half of CON-PLT-014; the Gradle subproject
    * dependency list is the enforced half. :architecture-tests cross-checks the two, with the
    * limitation recorded in {@code DependencyDirectionTest}: javac inlines a compile-time
    * constant, so a reference to a {@code static final String} alone leaves no bytecode
    * dependency to observe. That case is caught by the compile classpath, not by bytecode.
    */
   @AspmModule(name = "identity", classification = "generic")
   public final class IdentityModule {

       private IdentityModule() {
           throw new AssertionError("descriptor is not instantiable");
       }
   }
