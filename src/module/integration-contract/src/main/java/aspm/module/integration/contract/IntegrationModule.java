   package aspm.module.integration.contract;

   import aspm.sharedkernel.AspmModule;

   /**
    * Descriptor for the integration module [generic], DOC-03 section 5.1 context 15.
    *
    * <p>External connector surface. The translating Anti-Corruption Layer sits in Ingestion, not
* here (DOC-03 5.3 row 4).
    *
    * <p>The annotation carries the DECLARED half of CON-PLT-014; the Gradle subproject
    * dependency list is the enforced half. :architecture-tests cross-checks the two, with the
    * limitation recorded in {@code DependencyDirectionTest}: javac inlines a compile-time
    * constant, so a reference to a {@code static final String} alone leaves no bytecode
    * dependency to observe. That case is caught by the compile classpath, not by bytecode.
    */
   @AspmModule(name = "integration", classification = "generic")
   public final class IntegrationModule {

       private IntegrationModule() {
           throw new AssertionError("descriptor is not instantiable");
       }
   }
