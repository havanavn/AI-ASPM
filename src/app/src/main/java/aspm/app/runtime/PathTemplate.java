package aspm.app.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A path template such as {@code /api/v1/org-node-types/{id}}, and the matching of a concrete path to it.
 *
 * <p>Separate from the dispatcher because {@link aspm.app.api.OperationRegistry} keys operations by their
 * template: an operation's identity in the registry and the thing a request is matched against must be the
 * same string, or an operation can be registered under one spelling and dispatched under another — which is
 * an operation running without the annotation class that was assigned to it ({@code PRD-API-019}).
 */
public record PathTemplate(String template) {

    public PathTemplate {
        Objects.requireNonNull(template, "a template is required");
        if (!template.startsWith("/")) {
            throw new IllegalArgumentException("a path template starts with '/': " + template);
        }
    }

    /**
     * Matches a concrete path against this template.
     *
     * @return the extracted path variables, or empty where the path does not match this template
     */
    public Optional<Map<String, String>> match(String path) {
        Objects.requireNonNull(path, "a path is required");
        String[] templateParts = template.split("/", -1);
        String[] pathParts = path.split("/", -1);
        if (templateParts.length != pathParts.length) {
            return Optional.empty();
        }
        Map<String, String> variables = new LinkedHashMap<>();
        for (int i = 0; i < templateParts.length; i++) {
            String t = templateParts[i];
            String p = pathParts[i];
            if (t.startsWith("{") && t.endsWith("}")) {
                if (p.isEmpty()) {
                    // An empty segment would make /a//c match /a/{b}/c with b = "". A caller supplying no
                    // identifier must get a routing failure, not a lookup for the empty string.
                    return Optional.empty();
                }
                variables.put(t.substring(1, t.length() - 1), p);
            } else if (!t.equals(p)) {
                return Optional.empty();
            }
        }
        return Optional.of(Map.copyOf(variables));
    }
}
