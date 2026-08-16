   package aspm.module.notification.contract;

   import aspm.sharedkernel.AspmModule;

   /**
    * Descriptor for the notification module [generic], DOC-03 section 5.1 context 13.
    *
    * <p>Event subscriber via :platform-events (DOC-02 7.3). No compile dependency on any
* publishing module. PRD-WRK-037: never a transition side effect.
    *
    * <p>The annotation carries the DECLARED half of CON-PLT-014; the Gradle subproject
    * dependency list is the enforced half. :architecture-tests cross-checks the two, with the
    * limitation recorded in {@code DependencyDirectionTest}: javac inlines a compile-time
    * constant, so a reference to a {@code static final String} alone leaves no bytecode
    * dependency to observe. That case is caught by the compile classpath, not by bytecode.
    */
   @AspmModule(name = "notification", classification = "generic")
   public final class NotificationModule {

       private NotificationModule() {
           throw new AssertionError("descriptor is not instantiable");
       }
   }
