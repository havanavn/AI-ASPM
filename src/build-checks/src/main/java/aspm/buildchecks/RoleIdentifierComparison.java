package aspm.buildchecks;

import com.google.errorprone.BugPattern;
import com.google.errorprone.BugPattern.SeverityLevel;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import java.util.List;
import java.util.Locale;

/**
 * SEC-AUZ-050 — rejects comparison of a role identifier, by name, code or identity,
 * against a literal in application code.
 *
 * <p>Severity is ERROR, so a match fails compilation. SEC-AUZ-050 requires continuous
 * integration to enforce the rule; placing it in javac puts it at the developer's
 * compiler, which is where the shortcut is written. DOC-07 states why this is a build
 * gate rather than a review item: the shortcut is faster, reads naturally, and is
 * invisible until a customer's structure breaks it — review demonstrably does not catch
 * it, because the reviewer is reading the feature.
 *
 * <p>An exemption requires {@code @SuppressWarnings("RoleIdentifierComparison")} on the
 * element, which is greppable, countable and attributable — the visibility SEC-AUZ-051
 * requires.
 */
@BugPattern(
        name = "RoleIdentifierComparison",
        summary = "SEC-AUZ-050: a role identifier must not be compared against a literal; "
                + "branch on a permission through the authorization contract instead",
        explanation =
                "ADR-027 makes roles tenant-configured data. Comparing a role name, code or "
                + "identity against a literal hardcodes one tenant's structure into the "
                + "product and is invisible until another tenant's structure differs. Evaluate "
                + "a permission through the single authorization contract (SEC-AUZ-013) rather "
                + "than branching on who the principal is.",
        severity = SeverityLevel.ERROR,
        linkType = BugPattern.LinkType.NONE)
public final class RoleIdentifierComparison extends BugChecker
        implements BugChecker.BinaryTreeMatcher, BugChecker.MethodInvocationTreeMatcher {

    /**
     * Identifier fragments that mark an expression as carrying a role identifier. Matching on
     * the name is deliberate: the check must fire on a plain String field named roleName as
     * well as on a typed accessor, because the plain String is the form the shortcut takes.
     */
    private static final List<String> ROLE_TOKENS =
            List.of("rolename", "rolecode", "roleid", "roleidentifier", "role");

    private static final List<String> COMPARISON_METHODS =
            List.of("equals", "equalsignorecase", "contentequals", "compareto", "matches");

    @Override
    public Description matchBinary(BinaryTree tree, VisitorState state) {
        return switch (tree.getKind()) {
            case EQUAL_TO, NOT_EQUAL_TO -> {
                boolean leftRole = isRoleIdentifier(tree.getLeftOperand());
                boolean rightRole = isRoleIdentifier(tree.getRightOperand());
                boolean leftLiteral = isStringLiteral(tree.getLeftOperand());
                boolean rightLiteral = isStringLiteral(tree.getRightOperand());
                if ((leftRole && rightLiteral) || (rightRole && leftLiteral)) {
                    yield describeMatch(tree);
                }
                yield Description.NO_MATCH;
            }
            default -> Description.NO_MATCH;
        };
    }

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        ExpressionTree select = tree.getMethodSelect();
        if (!(select instanceof MemberSelectTree member)) {
            return Description.NO_MATCH;
        }
        String method = member.getIdentifier().toString().toLowerCase(Locale.ROOT);
        if (!COMPARISON_METHODS.contains(method)) {
            return Description.NO_MATCH;
        }
        boolean receiverIsRole = isRoleIdentifier(member.getExpression());
        boolean argumentIsLiteral = tree.getArguments().size() == 1
                && isStringLiteral(tree.getArguments().get(0));
        boolean receiverIsLiteral = isStringLiteral(member.getExpression());
        boolean argumentIsRole = tree.getArguments().size() == 1
                && isRoleIdentifier(tree.getArguments().get(0));

        if ((receiverIsRole && argumentIsLiteral) || (receiverIsLiteral && argumentIsRole)) {
            return describeMatch(tree);
        }
        return Description.NO_MATCH;
    }

    private static boolean isStringLiteral(Tree tree) {
        return tree instanceof LiteralTree literal && literal.getValue() instanceof String;
    }

    /** True where the expression's source text names a role identifier. */
    private static boolean isRoleIdentifier(Tree tree) {
        String text = tree.toString().toLowerCase(Locale.ROOT);
        // A method call returning a role identifier, a field access, or a bare local.
        for (String token : ROLE_TOKENS) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
