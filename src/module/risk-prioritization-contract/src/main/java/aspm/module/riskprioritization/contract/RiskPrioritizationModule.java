   package aspm.module.riskprioritization.contract;

   import aspm.sharedkernel.AspmModule;

   /**
    * Descriptor for the risk-prioritization module [core], DOC-03 section 5.1 context 3.
    *
    * <p>Customer-Supplier customer that READS three upstreams and computes; owns no source data
* (DOC-03 5.3 row 9).
    *
    * <p>The annotation carries the DECLARED half of CON-PLT-014; the Gradle subproject
    * dependency list is the enforced half. :architecture-tests cross-checks the two, with the
    * limitation recorded in {@code DependencyDirectionTest}: javac inlines a compile-time
    * constant, so a reference to a {@code static final String} alone leaves no bytecode
    * dependency to observe. That case is caught by the compile classpath, not by bytecode.
    */
   @AspmModule(
   name = "risk-prioritization",
   classification = "core",
   permittedDependencies = {"vulnerability-management", "asset-inventory", "organization-scope"})
   public final class RiskPrioritizationModule {

       private RiskPrioritizationModule() {
           throw new AssertionError("descriptor is not instantiable");
       }
   }
