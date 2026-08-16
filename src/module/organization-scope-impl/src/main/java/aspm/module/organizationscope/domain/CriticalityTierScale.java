package aspm.module.organizationscope.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The tenant's criticality scale, per DOC-03 section 7.6.
 *
 * <p>Tier names and count are tenant-configured ({@code CFG-AST-001}); the ordinal is the product-fixed
 * comparison mechanism. DOC-03 section 7.6: "tenants need their own vocabulary, and the platform needs a
 * stable basis for comparison, normalization, and cross-tenant support. Configurable presentation over a
 * fixed ordinal satisfies both; a fully configurable scale satisfies neither."
 *
 * <p><b>Lower ordinal means more critical.</b> Stated in every place the convention is used, because the
 * opposite is equally plausible and a reversed comparison would invert every prioritisation silently
 * rather than failing.
 */
public final class CriticalityTierScale {

    /** One tier. {@code code} is immutable; {@code label} is freely editable. */
    public record Tier(
            UUID id, String code, Map<String, String> label, int ordinal, Lifecycle lifecycleState) {

        public enum Lifecycle {
            ACTIVE,
            /** {@code INV-ORG-17}: a tier in use may be deprecated but not deleted. */
            DEPRECATED
        }

        public Tier {
            Objects.requireNonNull(id, "id is required");
            Objects.requireNonNull(code, "code is required");
            Objects.requireNonNull(lifecycleState, "lifecycleState is required");
            label = Map.copyOf(Objects.requireNonNull(label, "label is required"));
            if (!code.matches("^[A-Z][A-Z0-9_]{0,62}$")) {
                throw new IllegalArgumentException(
                        "tier code '" + code + "' is not a stable upper-snake identifier; the code appears "
                                + "in exports and saved queries and must not vary (INV-ORG-04 pattern)");
            }
            if (label.isEmpty()) {
                throw new IllegalArgumentException("a label is required in at least one locale");
            }
        }
    }

    /** A configuration-time finding. */
    public record Finding(String invariant, String detail) {}

    private final List<Tier> byIncreasingOrdinal;

    private CriticalityTierScale(List<Tier> ordered) {
        this.byIncreasingOrdinal = List.copyOf(ordered);
    }

    /**
     * Builds a scale, rejecting a set that cannot support a total order.
     *
     * <p>{@code INV-ORG-16} requires ordinals unique within a tenant and totally ordered. Two tiers sharing
     * an ordinal make every comparison between them non-deterministic, and the comparison decides
     * prioritisation — so this is rejected at construction rather than reported.
     */
    public static CriticalityTierScale of(Set<Tier> tiers) {
        Objects.requireNonNull(tiers, "tiers are required");
        List<Finding> findings = validate(tiers);
        if (!findings.isEmpty()) {
            throw new IllegalArgumentException(
                    "the criticality scale is not totally ordered: " + findings);
        }
        List<Tier> ordered = new ArrayList<>(tiers);
        ordered.sort(java.util.Comparator.comparingInt(Tier::ordinal));
        return new CriticalityTierScale(ordered);
    }

    /** Validates without constructing, for a configuration screen that reports rather than throws. */
    public static List<Finding> validate(Set<Tier> tiers) {
        List<Finding> findings = new ArrayList<>();
        Set<Integer> ordinals = new LinkedHashSet<>();
        Set<String> codes = new LinkedHashSet<>();
        for (Tier tier : tiers) {
            if (!ordinals.add(tier.ordinal())) {
                findings.add(new Finding("INV-ORG-16",
                        "ordinal " + tier.ordinal() + " is used by more than one tier, so the comparison "
                                + "between them is non-deterministic and prioritisation depends on read order"));
            }
            if (!codes.add(tier.code())) {
                findings.add(new Finding("INV-ORG-04",
                        "tier code '" + tier.code() + "' is not unique within the tenant"));
            }
        }
        if (!tiers.isEmpty() && tiers.stream().noneMatch(t -> t.lifecycleState() == Tier.Lifecycle.ACTIVE)) {
            findings.add(new Finding("INV-ORG-17",
                    "every tier is DEPRECATED, so no criticality can be assigned. Deprecation is for "
                            + "retiring one tier, not for emptying the scale."));
        }
        return List.copyOf(findings);
    }

    /** Tiers from most critical to least. */
    public List<Tier> mostCriticalFirst() {
        return byIncreasingOrdinal;
    }

    /**
     * Compares two tiers, negative meaning {@code a} is MORE critical.
     *
     * <p>Named for the ordinal rather than for severity, because "greater" is ambiguous on a scale where
     * lower means worse and the ambiguity is the whole hazard.
     */
    public int compareByOrdinal(Tier a, Tier b) {
        return Integer.compare(a.ordinal(), b.ordinal());
    }

    public Optional<Tier> byId(UUID id) {
        return byIncreasingOrdinal.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /** True where the tier may be assigned to a node ({@code INV-ORG-17}). */
    public boolean acceptsNewAssignment(UUID tierId) {
        return byId(tierId).map(t -> t.lifecycleState() == Tier.Lifecycle.ACTIVE).orElse(false);
    }
}
