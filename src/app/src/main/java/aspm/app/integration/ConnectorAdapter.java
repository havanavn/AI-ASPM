package aspm.app.integration;

import aspm.module.integration.domain.FailureClass;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The connector contract of DOC-21 §2, as the interface every outbound adapter implements.
 * {@code PRD-CON-015}, {@code PRD-CON-016}, {@code PRD-CON-017}, {@code PRD-CON-036}, {@code PRD-CON-045}.
 *
 * <p>Three operations and nothing else: create a reference item, observe one, probe the credential.
 * There is deliberately no method that takes an external state and applies it to anything — an adapter
 * reports what the target says and the worker decides whether that is a divergence for a person
 * ({@code PRD-CON-042}, ADR-040). Every method receives the destination through {@code config},
 * validated at save and re-checked by the egress guard at connection time ({@code PRD-CON-032},
 * {@code PRD-CON-033}); there is no parameter a record field could occupy.
 */
public interface ConnectorAdapter {

    /**
     * What leaves the platform for one finding. {@code PRD-CON-045}: a reference and a scope-appropriate
     * summary — title, severity label, where in the tree, the source tool, the platform link. Never the
     * description, the proof of concept, evidence, an assignee's workload ({@code PRD-CON-037}).
     */
    record Reference(UUID findingId, String title, String severity, String scopePath, String sourceTool, String link) {
        public Reference {
            Objects.requireNonNull(findingId);
            Objects.requireNonNull(title);
            Objects.requireNonNull(severity);
            Objects.requireNonNull(scopePath);
            Objects.requireNonNull(sourceTool);
            Objects.requireNonNull(link);
            // The same markers the notification sender refuses: an adapter is the last thing that touches
            // content before it leaves.
            String all = title + "\n" + scopePath + "\n" + sourceTool;
            for (String marker : new String[] {"vault:", "sealed:", "BEGIN PRIVATE KEY", "password=", "secret=", "api_key=", "Bearer "}) {
                if (all.contains(marker)) {
                    throw new IllegalArgumentException("outbound content carries material that must never leave the platform (PRD-CON-037)");
                }
            }
        }

        /** The ticket summary line. */
        public String summary() {
            return "[ASPM " + severity + "] " + title;
        }

        /** The ticket body. States what the ticket is, so nobody reads it as the record. */
        public String body() {
            return "Security finding " + findingId + "\n"
                    + "Severity: " + severity + "\n"
                    + "Scope: " + scopePath + "\n"
                    + "Source: " + sourceTool + "\n"
                    + "Platform record: " + link + "\n\n"
                    + "This ticket is a reference to a finding in the application security platform. The platform "
                    + "record is authoritative and is not updated from this ticket; closing it here does not close "
                    + "the finding. Detail and evidence are in the platform, where access is scoped.";
        }
    }

    /** What the target returned for a created item. */
    record Created(String externalId, String externalKey, Optional<String> url, String state, boolean resolved) {
        public Created {
            Objects.requireNonNull(externalId);
            Objects.requireNonNull(externalKey);
            Objects.requireNonNull(url);
            Objects.requireNonNull(state);
        }
    }

    /** What the target says now about an item. {@code deleted} when it no longer exists there. */
    record Observation(Optional<String> state, boolean resolved) {
        public Observation {
            Objects.requireNonNull(state);
        }

        public static Observation gone() {
            return new Observation(Optional.empty(), false);
        }

        public boolean deleted() {
            return state.isEmpty();
        }
    }

    /** The outcome of one call: a value, or a classified failure with a detail that never quotes a body. */
    record Result<T>(Optional<T> value, Optional<FailureClass> failure, String detail) {
        public static <T> Result<T> ok(T value, String detail) {
            return new Result<>(Optional.of(value), Optional.empty(), detail);
        }

        public static <T> Result<T> failed(FailureClass failure, String detail) {
            return new Result<>(Optional.empty(), Optional.of(failure), detail);
        }

        public boolean succeeded() {
            return value.isPresent();
        }
    }

    /** The product-fixed kind code, as stored in {@code connector.kind}. */
    String kind();

    /** {@code PRD-CON-018}: bumped when the adapter's wire behaviour changes; the row records it. */
    int version();

    /** A human label for the settings page. */
    String label();

    /** {@code PRD-CON-016}: the least-privilege set on the target, stated where the credential is entered. */
    List<String> minimumPermissions();

    /** {@code PRD-CON-036}: what each operation transmits, stated per operation. */
    Map<String, String> outboundContent();

    /** What the credential field is, for the form ("API token", "personal access token", "password"). */
    String credentialLabel();

    /**
     * {@code PRD-CON-017}: validates and normalizes configuration before anything is stored, or throws
     * {@link IllegalArgumentException} with a specific diagnosis.
     */
    Map<String, Object> validate(Map<String, Object> config, boolean credentialPresent);

    Result<Created> create(Map<String, Object> config, Optional<char[]> credential, Reference reference);

    Result<Observation> observe(Map<String, Object> config, Optional<char[]> credential, String externalId);

    /** {@code PRD-CON-022}: verifies a credential against the target without creating anything. */
    Result<String> probe(Map<String, Object> config, Optional<char[]> credential);
}
