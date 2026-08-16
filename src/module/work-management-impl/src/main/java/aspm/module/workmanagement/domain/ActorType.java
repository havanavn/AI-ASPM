package aspm.module.workmanagement.domain;

/**
 * Who effected a transition. DOC-04 section 16.3, following the {@code INV-AUD-05} pattern.
 *
 * <p>Distinguishing these is not bookkeeping. A transition rate per principal is the gaming-detection signal of
 * {@code SEC-PLT-005}, and an automation's transitions counted against the human who owns the rule would make
 * that signal read the wrong thing entirely.
 */
public enum ActorType {

    /** A human principal. */
    USER,

    /** A service credential — a CI system pushing a result, for example. */
    SERVICE,

    /**
     * An automation rule, acting under its owning principal's authority ceiling ({@code INV-WRK-13}).
     *
     * <p>Requires the rule identifier on the transition record. An automated transition that did not name its
     * rule would be indistinguishable from a human one at exactly the moment somebody is asking why an item
     * moved.
     */
    AUTOMATION,

    /** The platform itself — an expiry sweep, a scheduled closure. No principal to attribute it to. */
    SYSTEM;

    public boolean requiresAutomationRule() {
        return this == AUTOMATION;
    }

    /** Whether a principal identifier is expected. {@code SYSTEM} has none, and inventing one would be a lie. */
    public boolean carriesPrincipal() {
        return this == USER || this == SERVICE || this == AUTOMATION;
    }
}
