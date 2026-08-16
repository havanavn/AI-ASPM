   package aspm.module.aiassistance.contract;

   import aspm.sharedkernel.AspmModule;

   /**
    * Descriptor for the ai-assistance module [generic], DOC-03 section 5.1 context 16.
    *
    * <p>Separate Ways, read-only (DOC-03 5.3 row 14). PRD-AIC-021: no compile-time dependency on
* any domain module, and no write grant on any domain table.
    *
    * <p>The annotation carries the DECLARED half of CON-PLT-014; the Gradle subproject
    * dependency list is the enforced half. :architecture-tests cross-checks the two, with the
    * limitation recorded in {@code DependencyDirectionTest}: javac inlines a compile-time
    * constant, so a reference to a {@code static final String} alone leaves no bytecode
    * dependency to observe. That case is caught by the compile classpath, not by bytecode.
    */
   @AspmModule(name = "ai-assistance", classification = "generic")
   public final class AiAssistanceModule {

       private AiAssistanceModule() {
           throw new AssertionError("descriptor is not instantiable");
       }
   }
