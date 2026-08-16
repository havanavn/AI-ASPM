package aspm.module.assetinventory.contract;

import aspm.kernel.authorization.contract.PermissionId;
import java.util.List;

/**
 * The {@code ast.*} permission catalogue of DOC-07 section 5.2.
 *
 * <p>Product-fixed constants, not tenant data. {@code SEC-AUZ-001} makes the catalogue product-fixed while
 * roles are tenant-configurable (ADR-027), and DOC-04 section 22.3 records that the {@code permission} table
 * keys on a stable code "because the catalogue is code and its identifiers appear in configuration exports and
 * static analysis where a UUID would be unreadable".
 *
 * <p>Declared as {@link PermissionId} constants rather than raw strings so that a typo is a compile error
 * rather than a permission that silently matches nothing — and a permission matching nothing denies, which
 * looks like a configuration problem rather than a defect.
 */
public final class AssetPermissions {

    /** Read within scope. */
    public static final PermissionId ASSET_READ = PermissionId.of("ast.asset.read");

    /** Bulk extraction. Separate from read because export is a distinct egress path (DOC-07 section 17). */
    public static final PermissionId ASSET_EXPORT = PermissionId.of("ast.asset.export");

    public static final PermissionId ASSET_CREATE = PermissionId.of("ast.asset.create");
    public static final PermissionId ASSET_UPDATE = PermissionId.of("ast.asset.update");
    public static final PermissionId ASSET_RETIRE = PermissionId.of("ast.asset.retire");

    /** Merge — transfers findings and history, which is why it is not covered by update. */
    public static final PermissionId ASSET_MERGE = PermissionId.of("ast.asset.merge");

    /**
     * Claim an unowned asset.
     *
     * <p>DOC-07 section 5.2 annotates this one specially: it "<b>grants visibility of its findings, so it is
     * scope-checked against the proposed node</b> ({@code INV-AST-18})". Holding this permission somewhere is
     * not sufficient; it must be held <em>for the node being claimed</em>.
     */
    public static final PermissionId OWNERSHIP_CLAIM = PermissionId.of("ast.ownership.claim");

    public static final PermissionId OWNERSHIP_REASSIGN = PermissionId.of("ast.ownership.reassign");

    /** Declare exposure — a risk-scoring input, which is why it is separate from a general update. */
    public static final PermissionId EXPOSURE_DECLARE = PermissionId.of("ast.exposure.declare");

    public static final PermissionId ASSETTYPE_MANAGE = PermissionId.of("ast.assettype.manage");
    public static final PermissionId CUSTOMFIELD_MANAGE = PermissionId.of("ast.customfield.manage");

    private AssetPermissions() {
        throw new AssertionError("not instantiable");
    }

    /** The complete set, so a test can assert the catalogue matches DOC-07 section 5.2. */
    public static List<PermissionId> all() {
        return List.of(ASSET_READ, ASSET_EXPORT, ASSET_CREATE, ASSET_UPDATE, ASSET_RETIRE, ASSET_MERGE,
                OWNERSHIP_CLAIM, OWNERSHIP_REASSIGN, EXPOSURE_DECLARE, ASSETTYPE_MANAGE, CUSTOMFIELD_MANAGE);
    }
}
