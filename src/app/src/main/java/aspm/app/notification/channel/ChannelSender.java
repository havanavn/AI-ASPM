package aspm.app.notification.channel;

import aspm.module.integration.domain.FailureClass;
import java.util.Map;
import java.util.Optional;

/**
 * The delivery contract DOC-13 §14.1 refers to: "a new channel implements the delivery contract and
 * declares its digest support". {@code PRD-NTF-003}, {@code PRD-NTF-042}, {@code PRD-CON-025}.
 *
 * <p>A sender takes a rendered message, a destination and the channel's credential, makes exactly one
 * attempt, and reports the outcome classified — because {@code PRD-CON-025} forbids undifferentiated
 * retry, and only the sender knows whether a 401 from Slack means the token is dead (no retry, mark
 * unhealthy, tell the owner) or a 429 means back off. The worker decides what to do with the class;
 * the sender never retries on its own.
 */
public interface ChannelSender {

    /** The message as rendered for ONE recipient. {@code PRD-NTF-014}. */
    record Message(String title, Optional<String> body, Optional<String> link, String locale) {
        public Message {
            java.util.Objects.requireNonNull(title);
            java.util.Objects.requireNonNull(body);
            java.util.Objects.requireNonNull(link);
            java.util.Objects.requireNonNull(locale);
            // PRD-NTF-030: the same markers RenderedNotification refuses. A sender is the last thing
            // that touches content before it leaves, so it checks too.
            String all = title + "\n" + body.orElse("");
            for (String marker : new String[] {"vault:", "sealed:", "BEGIN PRIVATE KEY", "password=", "secret=", "api_key=", "Bearer "}) {
                if (all.contains(marker)) {
                    throw new IllegalArgumentException("notification content carries material that must never leave the platform");
                }
            }
        }
    }

    /** What one attempt produced. */
    record Outcome(boolean delivered, Optional<FailureClass> failure, String detail) {
        public static Outcome sent(String detail) {
            return new Outcome(true, Optional.empty(), detail);
        }

        public static Outcome failed(FailureClass failure, String detail) {
            return new Outcome(false, Optional.of(failure), detail);
        }
    }

    /** The channel kind this sender answers to. */
    String kind();

    /**
     * Validates the displayable configuration for this kind and returns it normalised. Throws
     * {@link IllegalArgumentException} with a message an administrator can act on ({@code PRD-CON-017}).
     */
    Map<String, Object> validate(Map<String, Object> config, boolean secretPresent);

    /** Whether this kind needs a credential at all. */
    boolean requiresSecret();

    /**
     * One attempt.
     *
     * @param config the validated channel configuration
     * @param secret the resolved credential, absent for a channel without one
     * @param address the resolved destination for this delivery, if the kind uses one
     */
    Outcome send(Map<String, Object> config, Optional<char[]> secret, Optional<String> address, Message message);
}
