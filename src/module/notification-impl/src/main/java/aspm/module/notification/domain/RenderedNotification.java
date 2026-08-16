package aspm.module.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One notification, rendered <b>for one recipient</b>. {@code PRD-NTF-014}.
 *
 * <p>"Content MUST be rendered per recipient with scope evaluated at render time, and a single rendered artifact
 * MUST NOT be delivered to multiple recipients." DOC-13 section 3 names the alternative and why it is tempting:
 * "This forbids rendering once for a group, which is <b>the efficient design and the wrong one</b>: a group
 * notification is a disclosure to the least-authorized recipient."
 *
 * <p>So the recipient is part of the artifact. There is no {@code deliverTo(List)} anywhere in this module, and
 * a rendered notification cannot be re-addressed — {@link #recipientId} is final and there is no
 * {@code withRecipient}.
 *
 * <p>{@code PRD-NTF-030} is enforced at construction: no credential, secret, evidence content, or per-person
 * workload data, "at any permission level or channel". A channel leaves the platform by design and is frequently
 * forwarded, so there is no permission level at which this content is acceptable.
 */
public record RenderedNotification(UUID recipientId, UUID subjectId, String subjectKind, Channel channel,
        String locale, String subject, String body, Optional<String> replyToken, Instant renderedAt) {

    public enum Channel {
        /** In-platform. Content stays inside the authorization boundary. */
        IN_APP,
        /** Leaves the platform. {@code PRD-NTF-032} makes its content minimal by default. */
        EMAIL,
        /** Also leaves the platform, and is typically retained by a third party. */
        CHAT,
        /** Machine-to-machine. Same content rules; the destination is no more trusted for being a program. */
        WEBHOOK;

        /** Whether content leaves the platform's control. Drives the minimal-by-default rule. */
        public boolean external() {
            return this != IN_APP;
        }
    }

    /** Substrings whose presence in rendered content is a defect rather than a style question. */
    private static final java.util.List<String> FORBIDDEN_MARKERS = java.util.List.of(
            "vault:", "BEGIN PRIVATE KEY", "password=", "secret=", "api_key=", "Bearer ");

    public RenderedNotification {
        Objects.requireNonNull(recipientId,
                "the recipient is part of the artifact (PRD-NTF-014). Rendering once for a group is the "
                        + "efficient design and the wrong one: a group notification is a disclosure to the "
                        + "least-authorized recipient, and a notification cannot be recalled.");
        Objects.requireNonNull(subjectId, "a subject is required");
        Objects.requireNonNull(subjectKind, "a subject kind is required");
        Objects.requireNonNull(channel, "a channel is required");
        Objects.requireNonNull(locale, "a locale is required (PRD-NTF-034)");
        Objects.requireNonNull(subject, "a subject line is required");
        Objects.requireNonNull(body, "a body is required");
        Objects.requireNonNull(replyToken, "replyToken is required, empty where the channel has no reply path");
        Objects.requireNonNull(renderedAt, "renderedAt is required");

        String combined = subject + "\n" + body;
        for (String marker : FORBIDDEN_MARKERS) {
            if (combined.contains(marker)) {
                // The message names the marker class and not the content, for the same reason SecretRef's
                // rejection does: the exception is logged.
                throw new IllegalArgumentException(
                        "notification content contains material matching a forbidden class (PRD-NTF-030). "
                                + "Credentials, secrets, evidence content and per-person workload data are "
                                + "excluded at any permission level and on any channel — an external channel "
                                + "leaves the platform by design and is frequently forwarded, so there is no "
                                + "permission level at which emailing a leaked credential is acceptable.");
            }
        }
        if (channel.external() && replyToken.isPresent() && replyToken.get().length() < 32) {
            throw new IllegalArgumentException(
                    "a reply token shorter than 32 characters is guessable, and the token confers the ability "
                            + "to post as the identified recipient (PRD-NTF-037)");
        }
    }
}
