   package aspm.module.workmanagement.contract;

   import aspm.sharedkernel.AspmModule;

   /**
    * Descriptor for the work-management module [core], DOC-03 section 5.1 context 5.
    *
    * <p>Receives finding and assessment linkage by EVENT only (DOC-02 Figure 6.1 dashed edges).
* No compile dependency on vulnerability-management or assessment.
    *
    * <p>The annotation carries the DECLARED half of CON-PLT-014; the Gradle subproject
    * dependency list is the enforced half. :architecture-tests cross-checks the two, with the
    * limitation recorded in {@code DependencyDirectionTest}: javac inlines a compile-time
    * constant, so a reference to a {@code static final String} alone leaves no bytecode
    * dependency to observe. That case is caught by the compile classpath, not by bytecode.
    */
   @AspmModule(
   name = "work-management",
   classification = "core",
   permittedDependencies = {"organization-scope"})
   public final class WorkManagementModule {

       private WorkManagementModule() {
           throw new AssertionError("descriptor is not instantiable");
       }
   }
