package aspm.app.api;

import java.util.Objects;

/**
 * The denial that cannot differentiate. {@code SEC-AUZ-020}, {@code PRD-API-021}, assertion A3.
 *
 * <p>"Denials do not differentiate non-existence from non-authorization, in status, code, message, or
 * <b>timing</b>."
 *
 * <h2>Three of those are easy and the fourth is the one that leaks</h2>
 *
 * <p>Status, code and message are settled by returning the same value, which this class does by
 * having exactly one factory for both cases and no parameter distinguishing them. A caller
 * <i>cannot</i> return a different body for the two, because there is nothing to vary.
 *
 * <p>Timing is different. A lookup-then-deny path reads the object, finds it, evaluates scope, and
 * denies — while a non-existent object denies after the read alone. The difference is small,
 * consistent, and measurable, which makes it a reliable existence oracle for an attacker willing to
 * average over a few hundred requests. {@code TST-AUZ-002} therefore requires the timing assertion to
 * be statistical rather than a single comparison.
 *
 * <p>The mitigation is structural, not a sleep: <b>the scope predicate is applied in retrieval</b>
 * ({@code SEC-AUZ-016}). An out-of-scope object is not found, so both cases take the same path and do
 * the same work. {@link #assertAppliedInRetrieval} exists so a handler that fetched first and checked
 * second fails a test rather than shipping a timing side channel.
 */
public final class DenialResponse {

    /** Always 404. Never 403 for a scope failure — see the class comment. */
    public static final int STATUS = 404;

    /** Always this code. A distinct code for authorization would restore the oracle in one field. */
    public static final String CODE = "NOT_FOUND";

    /** Always this message. No identifier, no object kind, no hint. */
    public static final String MESSAGE = "not found";

    private DenialResponse() {
    }

    /**
     * The one and only denial.
     *
     * <p>There is deliberately no {@code forbidden()} alongside this, and no parameter for the reason.
     * A reason parameter is a field somebody logs, then returns, then a client depends on.
     */
    public static Body notFound() {
        return new Body(STATUS, CODE, MESSAGE);
    }

    /** The response body. Carries nothing that could differ between the two cases. */
    public record Body(int status, String code, String message) {

        public Body {
            Objects.requireNonNull(code, "a code is required");
            Objects.requireNonNull(message, "a message is required");
        }
    }

    /**
     * Asserts that a scoped retrieval applied its predicate <b>in</b> the query rather than after it.
     *
     * <p>Takes the query that will run and the scope predicate that must appear in it. A handler that
     * fetched by identifier and then compared scope in memory produces a query without the predicate,
     * and this is what catches it — the timing difference that pattern creates is otherwise invisible
     * until somebody measures it deliberately.
     *
     * @throws IllegalStateException where the predicate is absent from the query
     */
    public static void assertAppliedInRetrieval(String query, String scopePredicateFragment) {
        Objects.requireNonNull(query, "a query is required");
        Objects.requireNonNull(scopePredicateFragment, "the scope predicate fragment is required");
        if (!query.contains(scopePredicateFragment)) {
            throw new IllegalStateException(
                    "the scope predicate is not in the retrieval query (SEC-AUZ-016). Fetching by identifier "
                            + "and comparing scope afterwards produces the same 404 and a measurably different "
                            + "latency, which is a reliable existence oracle (PRD-API-021). Query: " + query);
        }
    }
}
