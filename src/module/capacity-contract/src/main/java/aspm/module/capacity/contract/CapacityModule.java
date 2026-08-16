   package aspm.module.capacity.contract;

   import aspm.sharedkernel.AspmModule;

   /**
    * Descriptor for the capacity module [supporting], DOC-03 section 5.1 context 8.
    *
    * <p>Customer-Supplier customer that reads work item assignment and transition history; does
* not write work state (DOC-03 5.3 row 11).
    *
    * <p>The annotation carries the DECLARED half of CON-PLT-014; the Gradle subproject
    * dependency list is the enforced half. :architecture-tests cross-checks the two, with the
    * limitation recorded in {@code DependencyDirectionTest}: javac inlines a compile-time
    * constant, so a reference to a {@code static final String} alone leaves no bytecode
    * dependency to observe. That case is caught by the compile classpath, not by bytecode.
    */
   @AspmModule(
   name = "capacity",
   classification = "supporting",
   permittedDependencies = {"work-management", "organization-scope"})
   public final class CapacityModule {

       private CapacityModule() {
           throw new AssertionError("descriptor is not instantiable");
       }
   }
