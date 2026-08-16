package aspm.kernel.audit.contract;

/**
 * Actor classification, per {@code SEC-AUD-004}.
 *
 * <p>"An action attributed to 'system' is unattributable." The four values exist so that
 * {@link #SYSTEM} is a narrow residue rather than the default, and so that {@link #AUTOMATION}
 * carries its rule and owning principal — without which "an automated escalation has no traceable
 * origin".
 */
public enum ActorType {
    USER,
    SERVICE,
    AUTOMATION,
    SYSTEM
}
