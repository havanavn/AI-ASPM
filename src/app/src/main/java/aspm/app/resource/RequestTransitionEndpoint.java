package aspm.app.resource;

import aspm.app.runtime.Dispatcher;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@code POST /api/v1/requests/{id}/transitions}. Class B, idempotency key required.
 *
 * <p>One operation for every event rather than one per event, because DOC-09 §4's machine is data: an
 * endpoint per event would have to be added whenever a tenant adds a transition, which is the coupling
 * ADR-028 removes.
 *
 * <p>Status codes follow DOC-09 §3. A transition not available from the current state is
 * {@code 409 STATE_TRANSITION_INVALID}; one already applied is a success with no second record. A scope
 * failure is {@code 404}, identical to non-existence.
 */
public final class RequestTransitionEndpoint {

    private final RequestTransition transitions;

    public RequestTransitionEndpoint(DataSource dataSource) {
        this.transitions = new RequestTransition(Objects.requireNonNull(dataSource));
    }

    public Dispatcher.Response post(Dispatcher.Request request) throws Exception {
        UUID id;
        try {
            id = UUID.fromString(request.pathVariables().get("id"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Dispatcher.Response.notFound();
        }

        Map<String, Object> body = request.body().orElseThrow(
                () -> new IllegalArgumentException("a request body is required"));
        aspm.app.api.RequestValidation.rejectUnknownFields(java.util.Set.of("event", "reason"), body);

        Object event = body.get("event");
        if (!(event instanceof String eventCode) || eventCode.isBlank()) {
            throw new IllegalArgumentException("event is required");
        }
        Optional<String> reason = Optional.ofNullable(body.get("reason")).map(String::valueOf);

        RequestTransition.Outcome outcome;
        try {
            outcome = transitions.apply(request.principal(), id, eventCode, reason);
        } catch (Dispatcher.UnauthorizedException e) {
            return Dispatcher.Response.notFound();
        }

        return switch (outcome) {
            case RequestTransition.Outcome.Applied applied -> Dispatcher.Response.ok(Map.of(
                    "from_state", applied.fromState(),
                    "to_state", applied.toState(),
                    // Stated in the response, because a client retrying cannot otherwise tell whether
                    // its first attempt was the one that moved the request.
                    "already_in_state", Boolean.valueOf(applied.alreadyInState())));
            case RequestTransition.Outcome.Invalid invalid -> new Dispatcher.Response(409, Map.of(
                    "status", Integer.valueOf(409),
                    "code", "STATE_TRANSITION_INVALID",
                    "message", "no transition '" + invalid.event() + "' is available from the current "
                            + "state"), Map.of());
            case RequestTransition.Outcome.Denied denied -> "NOT_FOUND".equals(denied.code())
                    ? Dispatcher.Response.notFound()
                    : new Dispatcher.Response(422, Map.of(
                            "status", Integer.valueOf(422),
                            "code", denied.code(),
                            "message", denied.detail()), Map.of());
        };
    }

    /** {@code GET /api/v1/requests/{id}/transitions}. What is available, and why not. */
    public Dispatcher.Response get(Dispatcher.Request request) throws Exception {
        UUID id;
        try {
            id = UUID.fromString(request.pathVariables().get("id"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Dispatcher.Response.notFound();
        }
        List<RequestTransition.Available> available;
        try {
            available = transitions.available(request.principal(), id);
        } catch (Dispatcher.UnauthorizedException e) {
            return Dispatcher.Response.notFound();
        }
        List<Map<String, Object>> items = available.stream()
                .map(a -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
                    row.put("event", a.event());
                    row.put("to_state", a.toState());
                    row.put("reason_required", Boolean.valueOf(a.reasonRequired()));
                    row.put("permitted", Boolean.valueOf(a.permitted()));
                    row.put("blocked_reason", a.blockedReason().orElse(null));
                    return row;
                })
                .toList();
        return Dispatcher.Response.ok(Map.of("items", items));
    }
}
