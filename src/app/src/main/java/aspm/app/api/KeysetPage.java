package aspm.app.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Keyset pagination. <b>Offset pagination is not offered anywhere in this API.</b>
 *
 * <h2>Why offset is absent rather than discouraged</h2>
 *
 * <p>Under concurrent modification an offset page skips and duplicates rows: an insert before the
 * current offset shifts every subsequent row forward, so a client walking pages misses one and sees
 * another twice. On a findings list that is a finding a reviewer never sees, and nothing anywhere
 * reports it — the client received a complete-looking page.
 *
 * <p>{@code TST-PLT-008} asserts exactly that property, which is why the cursor is built from the
 * sort key rather than from a position.
 *
 * <h2>The tiebreaker is not optional</h2>
 *
 * <p>A cursor on a non-unique sort key — an updated timestamp, a score, a name — cannot say
 * <i>which</i> of the rows sharing that value the page ended on. Two rows with the same timestamp
 * straddle the boundary and one is lost or repeated, which is the offset failure reintroduced by the
 * mechanism adopted to avoid it. {@link Cursor} therefore always carries a unique tiebreaker, and
 * there is no constructor without one.
 *
 * @param <T> the row type
 */
public record KeysetPage<T>(List<T> items, Optional<Cursor> nextCursor, boolean hasMore) {

    /** Maximum page size. A caller asking for more gets this, not an error — see {@link #clampPageSize}. */
    public static final int MAX_PAGE_SIZE = 200;

    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * An opaque position in a result set.
     *
     * @param sortValue the last row's sort key, as an orderable string
     * @param tiebreaker the last row's unique identifier. Required — see the class comment
     */
    public record Cursor(String sortValue, String tiebreaker) {

        public Cursor {
            Objects.requireNonNull(sortValue, "a sort value is required");
            Objects.requireNonNull(tiebreaker,
                    "a unique tiebreaker is required. A cursor on a non-unique sort key cannot say WHICH of "
                            + "the rows sharing that value the page ended on, so two rows with the same "
                            + "timestamp straddle the boundary and one is lost or repeated — the offset "
                            + "failure reintroduced by the mechanism adopted to avoid it.");
        }

        /** The opaque token a client echoes back. Deliberately not a row number. */
        public String encode() {
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((sortValue + " " + tiebreaker).getBytes(
                            java.nio.charset.StandardCharsets.UTF_8));
        }

        public static Cursor decode(String token) {
            Objects.requireNonNull(token, "a cursor token is required");
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(token),
                    java.nio.charset.StandardCharsets.UTF_8);
            int separator = decoded.indexOf(' ');
            if (separator < 0) {
                throw new IllegalArgumentException("malformed cursor");
            }
            return new Cursor(decoded.substring(0, separator), decoded.substring(separator + 1));
        }
    }

    public KeysetPage {
        items = List.copyOf(Objects.requireNonNull(items, "items are required"));
        Objects.requireNonNull(nextCursor, "nextCursor is required, empty at the end");
    }

    /**
     * Builds a page from rows already fetched with a limit of {@code size + 1}.
     *
     * <p>Fetching one extra row is how {@code hasMore} is known without a count query. A count would be
     * a second query over the same scope predicate, and the two could disagree under concurrent
     * modification — reporting "51 results" above a page that shows 50 and then ends.
     */
    public static <T> KeysetPage<T> of(List<T> fetched, int requestedSize, Function<T, Cursor> cursorOf) {
        Objects.requireNonNull(fetched, "the fetched rows are required");
        Objects.requireNonNull(cursorOf, "a cursor extractor is required");
        int size = clampPageSize(requestedSize);

        boolean more = fetched.size() > size;
        List<T> page = more ? List.copyOf(fetched.subList(0, size)) : List.copyOf(fetched);
        Optional<Cursor> next = page.isEmpty() || !more
                ? Optional.empty()
                : Optional.of(cursorOf.apply(page.get(page.size() - 1)));
        return new KeysetPage<>(page, next, more);
    }

    /**
     * Clamps rather than rejects.
     *
     * <p>A client asking for ten thousand rows is not attacking; it is a script written by somebody who
     * did not read the documentation. Rejecting makes them retry in a loop, which costs more than
     * serving 200 once.
     */
    public static int clampPageSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
