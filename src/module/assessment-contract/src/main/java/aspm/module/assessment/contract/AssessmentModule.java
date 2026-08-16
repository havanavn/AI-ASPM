   package aspm.module.assessment.contract;

   import aspm.sharedkernel.AspmModule;

   /**
    * Descriptor for the assessment module [core], DOC-03 section 5.1 context 4.
    *
    * <p>Customer-Supplier supplier to Ingestion (DOC-03 5.3 row 7). Manual findings are not
* privileged.
    *
    * <p>The annotation carries the DECLARED half of CON-PLT-014; the Gradle subproject
    * dependency list is the enforced half. :architecture-tests cross-checks the two, with the
    * limitation recorded in {@code DependencyDirectionTest}: javac inlines a compile-time
    * constant, so a reference to a {@code static final String} alone leaves no bytecode
    * dependency to observe. That case is caught by the compile classpath, not by bytecode.
    */
   @AspmModule(
   name = "assessment",
   classification = "core",
   permittedDependencies = {"ingestion", "asset-inventory", "organization-scope"})
   public final class AssessmentModule {

       private AssessmentModule() {
           throw new AssertionError("descriptor is not instantiable");
       }
   }
