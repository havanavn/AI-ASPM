package aspm.sharedkernel;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the descriptor of a module of DOC-02 Figure 6.1 or a kernel module of DOC-02 section 6.2.
 *
 * <p>An annotation rather than a naming convention. The first version of the inventory test
 * identified descriptors by their class name ending in {@code Module}, and the deliberate-violation
 * run of prompt 2 showed why that is wrong: a violation class named {@code KnowsADomainModule} was
 * collected as a descriptor and the test failed for an incidental reason instead of the intended
 * one. A test that fails for the wrong reason is indistinguishable from a test that works, until
 * the day it matters.
 *
 * @see aspm.sharedkernel.ScopeDescriptor
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AspmModule {

    /** Stable module name, matching the Gradle subproject and the DOC-02 row or figure node. */
    String name();

    /**
     * Classification per DOC-03 section 5.1: {@code core}, {@code supporting}, {@code generic},
     * {@code projection}, or {@code kernel} for a DOC-02 section 6.2 module.
     */
    String classification();

    /**
     * Names of modules whose CONTRACT this module may compile against, per DOC-03 section 5.3.
     *
     * <p>Event-driven relationships are deliberately absent: they carry no compile-time dependency
     * and are realized through the shared events package of DOC-02 section 7.3.
     *
     * <p>This is the DECLARED half of CON-PLT-014. The enforced half is the Gradle subproject
     * dependency list, which decides what is on the compile classpath.
     */
    String[] permittedDependencies() default {};
}
