   package aspm.module.ingestion.contract;

   import aspm.sharedkernel.AspmModule;

   /**
    * Descriptor for the ingestion module [supporting], DOC-03 section 5.1 context 9.
    *
    * <p>Customer-Supplier supplier that pushes into Vulnerability Management (DOC-03 5.3 row 5).
* Holds the Anti-Corruption Layer over Integration (row 4).
    *
    * <p>The annotation carries the DECLARED half of CON-PLT-014; the Gradle subproject
    * dependency list is the enforced half. :architecture-tests cross-checks the two, with the
    * limitation recorded in {@code DependencyDirectionTest}: javac inlines a compile-time
    * constant, so a reference to a {@code static final String} alone leaves no bytecode
    * dependency to observe. That case is caught by the compile classpath, not by bytecode.
    */
   @AspmModule(
   name = "ingestion",
   classification = "supporting",
   permittedDependencies = {"vulnerability-management", "integration"})
   public final class IngestionModule {

       private IngestionModule() {
           throw new AssertionError("descriptor is not instantiable");
       }
   }
