package aspm.buildchecks;

import com.google.errorprone.BugPattern;
import com.google.errorprone.BugPattern.SeverityLevel;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import java.util.List;

/**
 * SEC-TEN-009 and CON-PLT-039 — rejects direct use of a cache, search, queue or
 * idempotency client from application code.
 *
 * <p>DOC-24 section 6.2 places cache key construction first in the leakage inventory, and
 * ADR-055 records that no cache technology can provide "no cross-tenant key collision by
 * construction" at the store layer. The enforcement is therefore that a key must come
 * from the mandatory constructor, which cannot omit the tenant because the tenant comes
 * from the request-scoped context rather than from a parameter. This check is what makes
 * bypassing the constructor a compile error and a countable suppression rather than a
 * line nobody reads.
 */
@BugPattern(
        name = "DirectInfrastructureClientAccess",
        summary = "SEC-TEN-009: cache, search, queue and idempotency clients must be reached "
                + "through the kernel's key-constructing gate, not directly",
        explanation =
                "A key constructed by hand is reviewed by someone reading the feature. A "
                + "constructor that cannot omit the tenant removes the possibility rather than "
                + "relying on attention (CON-PLT-039).",
        severity = SeverityLevel.ERROR,
        linkType = BugPattern.LinkType.NONE)
public final class DirectInfrastructureClientAccess extends BugChecker
        implements BugChecker.MethodInvocationTreeMatcher {

    /**
     * Receiver type names that must not be touched outside the kernel gate. Named rather
     * than inferred, so that adding a subsystem requires adding it here — which is
     * SEC-TEN-010's requirement that a new leakage surface cannot be introduced without
     * its entry.
     */
    private static final List<String> GUARDED_RECEIVERS = List.of(
            "jedis", "valkeyclient", "redisclient", "cacheclient",
            "searchclient", "indexclient", "queueclient", "idempotencyclient");

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        if (!(tree.getMethodSelect() instanceof MemberSelectTree member)) {
            return Description.NO_MATCH;
        }
        String receiver = member.getExpression().toString().toLowerCase(java.util.Locale.ROOT);
        for (String guarded : GUARDED_RECEIVERS) {
            if (receiver.contains(guarded)) {
                return describeMatch(tree);
            }
        }
        return Description.NO_MATCH;
    }
}
