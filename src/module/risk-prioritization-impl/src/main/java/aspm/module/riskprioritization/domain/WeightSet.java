package aspm.module.riskprioritization.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A tenant's factor weights, validated against the per-factor bounds of DOC-28 section 7.2.
 *
 * <p><b>The weights do not sum to one, and must not be required to.</b> DOC-28 section 5.7: "Weights sum to 1.10
 * by design; the formula normalizes (section 6.2)." An earlier version of this class required a sum of exactly
 * one, which would have rejected the document's own default weight set. Normalization by the weight sum is what
 * keeps {@code raw} inside {@code [0, 1]} for any bounds-valid configuration, so requiring the sum is both wrong
 * and unnecessary.
 *
 * <p><b>Why bounds rather than free configuration</b> ({@code PRD-RSK-020}): "setting {@code EXP} and {@code KEV}
 * to zero converts it back into the severity sorting that produced the four thousand findings." Rejection at
 * configuration time is cheap; discovering it through inexplicable prioritization is not — so the diagnosis names
 * the factor, the offered value, and the bound it broke.
 */
public final class WeightSet {

    /** Guards against a configuration whose factors are all at their floor, making normalization meaningless. */
    private static final BigDecimal MINIMUM_TOTAL = new BigDecimal("0.50");

    private final Map<Factor, BigDecimal> weights;
    private final BigDecimal total;

    private WeightSet(Map<Factor, BigDecimal> weights) {
        this.weights = weights;
        this.total = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The default weight set of DOC-28 section 5.7, whose total is 1.10. */
    public static WeightSet defaults() {
        Map<Factor, BigDecimal> defaults = new EnumMap<>(Factor.class);
        for (Factor factor : Factor.values()) {
            defaults.put(factor, factor.defaultWeight());
        }
        return new WeightSet(defaults);
    }

    /**
     * Validates a tenant configuration.
     *
     * @throws IllegalArgumentException naming the specific factor and bound, per {@code PRD-RSK-020}'s
     *     requirement for "a specific diagnosis" — a generic rejection leaves the tenant guessing which of seven
     *     values was wrong
     */
    public static WeightSet of(Map<Factor, BigDecimal> proposed) {
        Objects.requireNonNull(proposed, "a weight set is required");
        Map<Factor, BigDecimal> validated = new EnumMap<>(Factor.class);
        Map<String, String> diagnoses = new LinkedHashMap<>();

        for (Factor factor : Factor.values()) {
            BigDecimal weight = proposed.get(factor);
            if (weight == null) {
                // Not defaulted silently. A missing factor in a submitted configuration is far more likely to be
                // an omission than a deliberate choice, and defaulting it would apply a weight the tenant never
                // reviewed to every score in the tenancy.
                diagnoses.put(factor.name(),
                        "no weight supplied; the factor set is product-fixed (PRD-RSK-004), so every factor "
                                + "needs an explicit weight, including " + factor.name() + " at its default of "
                                + factor.defaultWeight());
                continue;
            }
            if (weight.compareTo(factor.minimumWeight()) < 0) {
                diagnoses.put(factor.name(), weight + " is below the minimum of " + factor.minimumWeight()
                        + " (DOC-28 section 7.2)");
                continue;
            }
            if (weight.compareTo(factor.maximumWeight()) > 0) {
                diagnoses.put(factor.name(), weight + " is above the maximum of " + factor.maximumWeight()
                        + " (DOC-28 section 7.2)");
                continue;
            }
            validated.put(factor, weight);
        }

        if (!diagnoses.isEmpty()) {
            throw new IllegalArgumentException(
                    "weight configuration rejected (PRD-RSK-020). Bounds exist so that a tenant cannot "
                            + "configure the model into meaninglessness: " + diagnoses);
        }

        WeightSet set = new WeightSet(validated);
        if (set.total.compareTo(MINIMUM_TOTAL) < 0) {
            throw new IllegalArgumentException(
                    "the weights total " + set.total + ", below " + MINIMUM_TOTAL + ". Every factor sitting at "
                            + "its floor is bounds-valid factor by factor and still collapses the model: after "
                            + "normalization by the total, the relative shape survives but the configuration "
                            + "signals that the tenant has disabled the methodology rather than tuned it.");
        }
        return set;
    }

    public BigDecimal weightOf(Factor factor) {
        return weights.get(factor);
    }

    /** The weight sum. Public because the score records it — the divisor is part of reproducing the value. */
    public BigDecimal total() {
        return total;
    }

    /**
     * {@code normalize(raw)} of DOC-28 section 6.1 — division by the weight total.
     *
     * @param scale the scale to retain. Fixed by the caller rather than chosen here, so that two calls in the
     *     same computation cannot differ
     */
    public BigDecimal normalize(BigDecimal raw, int scale) {
        return raw.divide(total, new MathContext(scale + 4, RoundingMode.HALF_UP))
                .setScale(scale, RoundingMode.HALF_UP);
    }

    public Map<Factor, BigDecimal> asMap() {
        return Map.copyOf(weights);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WeightSet w && weights.equals(w.weights);
    }

    @Override
    public int hashCode() {
        return weights.hashCode();
    }

    @Override
    public String toString() {
        return "WeightSet" + weights + " total=" + total;
    }
}
