package aspm.app.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Every API operation and the annotation class it is assigned. {@code PRD-API-019}, ADR-036.
 *
 * <p>"A new operation MUST NOT be introduced without one." That is enforceable only if there is
 * somewhere an operation has to be registered before it can be dispatched — so {@link #resolve}
 * returns empty for an unregistered path, and the dispatcher treats that as a routing failure rather
 * than as an unannotated operation to run anyway.
 *
 * <p>The registry is <b>data</b> rather than annotations on handler methods, for one reason: a
 * reviewer asking "which operations are class C" reads one file. With annotations they read every
 * handler and trust that they found them all.
 */
public final class OperationRegistry {

    /**
     * One operation.
     *
     * @param requiredPermission the named permission. Present on every class except {@code G}, which is
     *     the only class with no authorization at all
     * @param restrictedFields fields absent from this operation's representation ({@code SEC-AUZ-022})
     * @param filterableFields the declared filterable set; empty for non-collection operations
     */
    public record Operation(String method, String pathTemplate, AnnotationClass annotationClass,
            Optional<String> requiredPermission, Set<String> restrictedFields,
            Set<String> filterableFields) {

        public Operation {
            Objects.requireNonNull(method, "an HTTP method is required");
            Objects.requireNonNull(pathTemplate, "a path template is required");
            Objects.requireNonNull(annotationClass,
                    "an annotation class is required (PRD-API-019). The class assignment is the mechanism "
                            + "that prevents an operation shipping without its security characteristics "
                            + "considered.");
            Objects.requireNonNull(requiredPermission, "requiredPermission is required, empty only for G");
            restrictedFields = Set.copyOf(
                    Objects.requireNonNull(restrictedFields, "restrictedFields are required, possibly empty"));
            filterableFields = Set.copyOf(
                    Objects.requireNonNull(filterableFields, "filterableFields are required, possibly empty"));

            boolean unauthenticated = annotationClass == AnnotationClass.G_UNAUTHENTICATED;
            if (unauthenticated && requiredPermission.isPresent()) {
                throw new IllegalArgumentException(
                        "class G is unauthenticated; a required permission on it cannot be evaluated, and "
                                + "declaring one reads as a control that is not there");
            }
            if (!unauthenticated && requiredPermission.isEmpty()) {
                throw new IllegalArgumentException(
                        method + " " + pathTemplate + " is class " + annotationClass + " and names no "
                                + "permission. Deny by default means an operation with no named permission is "
                                + "an operation nobody can be authorized for — which in practice becomes an "
                                + "operation somebody removes the check from.");
            }
        }

        /** Whether this operation must carry an idempotency key, from its class alone. */
        public boolean requiresIdempotencyKey() {
            return annotationClass.requiresIdempotencyKey();
        }
    }

    private final Map<String, Operation> operations;

    private OperationRegistry(Map<String, Operation> operations) {
        this.operations = operations;
    }

    public static OperationRegistry of(List<Operation> operations) {
        Objects.requireNonNull(operations, "operations are required");
        Map<String, Operation> byKey = new LinkedHashMap<>();
        for (Operation operation : operations) {
            String key = key(operation.method(), operation.pathTemplate());
            if (byKey.put(key, operation) != null) {
                throw new IllegalArgumentException(
                        "two operations registered for " + key + "; which class applies would depend on "
                                + "registration order, and the weaker one would sometimes win");
            }
        }
        return new OperationRegistry(Map.copyOf(byKey));
    }

    /**
     * Resolves an operation.
     *
     * @return empty where the operation is not registered. The dispatcher must treat that as a routing
     *     failure: running an unregistered operation is running one with no annotation class, which is
     *     exactly what {@code PRD-API-019} forbids
     */
    public Optional<Operation> resolve(String method, String pathTemplate) {
        return Optional.ofNullable(operations.get(key(method, pathTemplate)));
    }

    public List<Operation> all() {
        return List.copyOf(operations.values());
    }

    /** Every operation of a given class, for a reviewer asking "which operations reveal restricted data". */
    public List<Operation> inClass(AnnotationClass annotationClass) {
        return operations.values().stream().filter(o -> o.annotationClass() == annotationClass).toList();
    }

    private static String key(String method, String pathTemplate) {
        return method.toUpperCase(java.util.Locale.ROOT) + " " + pathTemplate;
    }
}
