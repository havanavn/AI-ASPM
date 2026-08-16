package aspm.module.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Associating a reply with the item it answers. DOC-13 section 11.
 *
 * <p>The capability exists for one population: DOC-01's archetype A6, "occasional users who receive a
 * notification and reply to it by reflex". Everything below follows from that being who is on the other end.
 *
 * <h2>{@code PRD-NTF-040} — a reply that cannot be associated returns a failure</h2>
 *
 * <p>DOC-13: "If association fails silently, the request stalls with both parties believing the other is
 * responsible." That is the failure the whole capability exists to prevent, so
 * {@link #associate} returns a {@link Result} that has no third state — either it associated, or the sender is
 * told. There is no {@code Optional} for a caller to ignore.
 *
 * <h2>{@code PRD-NTF-037} — the token is the association, not the subject line or the sender</h2>
 *
 * <p>"Subject-line association is trivially forged and unreliable after forwarding. Sender-address association
 * permits anyone who learns an address to post as that principal."
 *
 * <p>Both are worth noticing as things a reasonable implementation would do. A subject line carrying
 * {@code [WRK-1042]} is the conventional design; it means anybody who can send mail can post a comment on any
 * item whose code they can guess, and item codes are sequential.
 */
public final class InboundAssociation {

    /** Token lifetime. {@code PRD-NTF-038} requires expiry; the token travels in email and will be forwarded. */
    public static final Duration TOKEN_LIFETIME = Duration.ofDays(30);

    /** Minimum entropy. A guessable token is the sender-address design with extra steps. */
    public static final int MINIMUM_TOKEN_LENGTH = 32;

    /**
     * An inbound reply token.
     *
     * <p>{@code PRD-NTF-038}: it "MUST identify the item, the recipient, and the notification, MUST expire, and
     * MUST be single-purpose — it MUST NOT confer any authorization beyond posting a comment on that item."
     *
     * <p>All four in the type. {@link #authorizes} answers exactly one question and there is no method that
     * would answer a broader one — a token that could be asked "may this principal read the item" would
     * eventually be used that way.
     */
    public record ReplyToken(String value, UUID workItemId, UUID recipientId, UUID notificationId,
            Instant issuedAt) {

        public ReplyToken {
            Objects.requireNonNull(value, "a token value is required");
            Objects.requireNonNull(workItemId, "the token identifies an item (PRD-NTF-038)");
            Objects.requireNonNull(recipientId, "the token identifies a recipient (PRD-NTF-038)");
            Objects.requireNonNull(notificationId, "the token identifies a notification (PRD-NTF-038)");
            Objects.requireNonNull(issuedAt, "an issue time is required, so the token can expire");
            if (value.length() < MINIMUM_TOKEN_LENGTH) {
                throw new IllegalArgumentException(
                        "a token shorter than " + MINIMUM_TOKEN_LENGTH + " characters is guessable, and the "
                                + "token permits posting as the identified recipient");
            }
        }

        public boolean expiredAt(Instant now) {
            return Duration.between(issuedAt, now).compareTo(TOKEN_LIFETIME) > 0;
        }

        /**
         * Whether this token permits posting a comment on this item. <b>The only question it answers.</b>
         *
         * <p>Confining it to one action on one item is what bounds the consequence of the token being
         * forwarded, which it will be.
         */
        public boolean authorizes(UUID candidateWorkItemId, Instant now) {
            return workItemId.equals(candidateWorkItemId) && !expiredAt(now);
        }
    }

    /**
     * The outcome. Two states, and the failure carries what to tell the sender.
     *
     * @param failureToSender present exactly when association failed. {@code PRD-NTF-040}: "The population this
     *     serves replies by reflex and believes they responded. A silently discarded reply is worse than no
     *     inbound support."
     */
    public record Result(Optional<AssociatedComment> comment, Optional<String> failureToSender) {

        public Result {
            Objects.requireNonNull(comment, "comment is required, empty on failure");
            Objects.requireNonNull(failureToSender, "failureToSender is required, empty on success");
            if (comment.isPresent() == failureToSender.isPresent()) {
                throw new IllegalArgumentException(
                        "exactly one of an associated comment or a failure to the sender. Neither is the "
                                + "silent discard PRD-NTF-040 forbids — a stalled request whose requester "
                                + "believes they answered.");
            }
        }

        public boolean associated() {
            return comment.isPresent();
        }
    }

    /**
     * A comment produced from an inbound reply.
     *
     * @param inboundOrigin always true here. {@code PRD-NTF-039}: recorded "because an inbound-attributed
     *     comment carries weaker identity assurance than an authenticated one", and {@code H11} requires the
     *     marker to reach the reader
     */
    public record AssociatedComment(UUID workItemId, UUID authorId, String body, boolean inboundOrigin) {

        public AssociatedComment {
            Objects.requireNonNull(workItemId, "workItemId is required");
            Objects.requireNonNull(authorId, "authorId is required");
            Objects.requireNonNull(body, "a body is required");
            if (!inboundOrigin) {
                throw new IllegalArgumentException(
                        "a comment from the inbound path is inbound-attributed by construction. Marking it "
                                + "otherwise would give an emailed reply the identity assurance of an "
                                + "authenticated one (PRD-NTF-039).");
            }
        }
    }

    private InboundAssociation() {
    }

    /**
     * Associates a reply.
     *
     * @param token the token extracted from the reply address. Absent where the reply arrived without one —
     *     forwarded, or sent to a bare address
     * @param stripQuotedHistory strips quoted history and signatures. {@code PRD-NTF-039}: without it a reply
     *     carries the entire notification back, including any detail the recipient could see then and cannot
     *     now
     */
    public static Result associate(Optional<ReplyToken> token, String rawBody, Instant now,
            java.util.function.UnaryOperator<String> stripQuotedHistory) {
        Objects.requireNonNull(token, "token is required, empty where the reply carried none");
        Objects.requireNonNull(rawBody, "a body is required");
        Objects.requireNonNull(now, "the current instant is required");
        Objects.requireNonNull(stripQuotedHistory, "a quoted-history stripper is required (PRD-NTF-039)");

        if (token.isEmpty()) {
            return failure("This reply could not be matched to a request and has NOT been recorded. "
                    + "Reply directly to the original notification, or open the request in the platform. "
                    + "Nobody has seen this message.");
        }
        ReplyToken replyToken = token.get();
        if (replyToken.expiredAt(now)) {
            return failure("The reply link for this request has expired and your message has NOT been "
                    + "recorded. Open the request in the platform to respond. Nobody has seen this message.");
        }

        String body = stripQuotedHistory.apply(rawBody).strip();
        if (body.isEmpty()) {
            // A reply that is nothing but quoted history is almost always an accidental send, and recording it
            // as a comment would appear to answer a question it did not answer.
            return failure("Your reply contained no new text once the quoted message was removed, so it has "
                    + "NOT been recorded. If you intended to respond, reply again with your message above the "
                    + "quoted text.");
        }

        return new Result(Optional.of(new AssociatedComment(replyToken.workItemId(),
                replyToken.recipientId(), body, true)), Optional.empty());
    }

    private static Result failure(String message) {
        return new Result(Optional.empty(), Optional.of(message));
    }
}
