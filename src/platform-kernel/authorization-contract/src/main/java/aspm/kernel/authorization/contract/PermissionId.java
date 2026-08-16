package aspm.kernel.authorization.contract;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A product-fixed permission code from the catalogue of DOC-07 section 5.
 *
 * <p>{@code SEC-AUZ-001} makes the catalogue product-fixed while roles, hierarchy depth, workflows
 * and vocabulary are tenant-configurable (ADR-027). The code is therefore safe to appear in code,
 * configuration exports and static analysis — DOC-04 section 22.3 notes this is precisely why the
 * {@code permission} table keys on a stable code rather than a UUID, "because the catalogue is code
 * and its identifiers appear in configuration exports and static analysis where a UUID would be
 * unreadable".
 *
 * <p>A permission is not a role. Comparing a role identifier against a literal is rejected at compile
 * time by {@code SEC-AUZ-050}; comparing a permission code is the correct alternative that check
 * exists to steer code towards.
 */
public record PermissionId(String code) {

    private static final Pattern SHAPE = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$");

    public PermissionId {
        Objects.requireNonNull(code, "permission code is required");
        if (!SHAPE.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "permission code '" + code + "' is not of the catalogue form 'domain.object.action' "
                            + "used throughout DOC-07 section 5.2");
        }
    }

    public static PermissionId of(String code) {
        return new PermissionId(code);
    }
}
