   package aspm.module.compositionanalysis.contract;

   import aspm.sharedkernel.AspmModule;

   /**
    * Descriptor for the composition-analysis module [supporting], DOC-03 section 5.1 context 7.
    *
    * <p>Customer-Supplier supplier to Ingestion (DOC-03 5.3 row 6, ADR-011). Not a second path
* to Vulnerability Management.
    *
    * <p>The annotation carries the DECLARED half of CON-PLT-014; the Gradle subproject
    * dependency list is the enforced half. :architecture-tests cross-checks the two, with the
    * limitation recorded in {@code DependencyDirectionTest}: javac inlines a compile-time
    * constant, so a reference to a {@code static final String} alone leaves no bytecode
    * dependency to observe. That case is caught by the compile classpath, not by bytecode.
    */
   @AspmModule(
   name = "composition-analysis",
   classification = "supporting",
   permittedDependencies = {"ingestion", "asset-inventory"})
   public final class CompositionAnalysisModule {

       private CompositionAnalysisModule() {
           throw new AssertionError("descriptor is not instantiable");
       }
   }
