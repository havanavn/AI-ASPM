package aspm.kernel.authorization.contract;

import java.time.Instant;
import java.util.Objects;

/**
 * Whether authorization is evaluated against current scope or a recorded historical descriptor,
 * per DOC-07 sections 8.2 and 12.
 */
public sealed interface EvaluationTime {

    /** Evaluation against the principal's present scope. */
    record Current() implements EvaluationTime {}

    /**
     * Evaluation against the descriptor recorded at {@code at}.
     *
     * <p>{@code SEC-AUZ-028} and {@code SEC-AUZ-029}: historical access is read-only and grants
     * nothing for objects created after a reorganization. {@code TST-PTR-003} asserts that a
     * historical report reproduces identically across a reorganization, which is only possible where
     * evaluation uses the recorded descriptor rather than the current tree.
     */
    record Historical(Instant at) implements EvaluationTime {
        public Historical {
            Objects.requireNonNull(at, "historical evaluation requires an instant");
        }
    }

    static EvaluationTime current() {
        return new Current();
    }

    static EvaluationTime historical(Instant at) {
        return new Historical(at);
    }
}
