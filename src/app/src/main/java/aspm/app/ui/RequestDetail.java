package aspm.app.ui;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The assessment request detail page. DOC-05 §14.1, DOC-04 §12.2, DOC-09.
 *
 * <p>This is the screen on which the platform replaces an issue tracker for AppSec intake, and it is the
 * screen a tracker cannot produce: the fields that decide whether a pentest can start are not free-text
 * custom fields, they are invariants the schema enforces.
 *
 * <h2>Three of them carry the page</h2>
 *
 * <ol>
 *   <li><b>Readiness is four booleans and an attestation, not a flag.</b> {@code INV-ASM-04} requires
 *       complete readiness before acceptance, and DOC-04 §12.2 says why it is four columns: "so an
 *       incomplete readiness names <i>what</i> is missing rather than being a single opaque flag." The
 *       panel therefore lists what is outstanding. A request that cannot be accepted says which of the
 *       four is the reason.
 *   <li><b>Test accounts are two per role, and the rule is visible.</b> {@code INV-ASM-02} is a set
 *       assertion; a role with one account cannot demonstrate horizontal access control, which is the
 *       defect class this platform exists to find. The page marks the roles that fail it rather than
 *       leaving a reader to count.
 *   <li><b>A credential is a vault reference and there is no reveal.</b> {@code INV-ASM-03}, and
 *       {@code SEC-SEC-024} permits a reveal only through "an explicitly permissioned,
 *       step-up-authenticated, per-object-audited reveal operation" — which is not implemented, so no
 *       button offers one. Credential and secret concentration is the platform's third highest-risk
 *       surface and an intake form is where the credentials arrive.
 * </ol>
 *
 * <p>The derived fields — priority, effort, feasible start — are labelled as derived.
 * {@code INV-ASM-08}: "derived, never set by a client. No API surface writes these columns; the
 * estimation job does." A number a requester believes they can edit is a number they will argue about.
 */
public final class RequestDetail {

    private RequestDetail() {
    }

    /** The four readiness conditions, in the order DOC-04 §12.2 declares them. */
    private static final List<String[]> READINESS = List.of(
            new String[] {"readiness_environment_available", "request.readiness.environment"},
            new String[] {"readiness_accounts_provisioned", "request.readiness.accounts"},
            new String[] {"readiness_data_seeded", "request.readiness.data"},
            new String[] {"readiness_contact_available", "request.readiness.contact"});

    public static String render(Messages messages, Map<String, Object> request,
            List<Map<String, Object>> accounts, List<Map<String, Object>> environments,
            List<aspm.app.resource.RequestTransition.Available> transitions,
            List<Map<String, Object>> history) {
        Objects.requireNonNull(messages, "messages are required");
        Objects.requireNonNull(request, "a request is required");

        StringBuilder out = new StringBuilder(4096);
        out.append("<div class=\"grid split-2\">");

        out.append("<div class=\"stack-6\">");
        out.append(readiness(messages, request));
        out.append(accounts(messages, accounts));
        out.append(environments(messages, environments));
        out.append("</div>");

        out.append("<div class=\"stack-6\">");
        out.append(actions(messages, request, transitions));
        out.append(summary(messages, request));
        out.append(derived(messages, request));
        out.append(timeline(messages, history));
        out.append("</div>");

        out.append("</div>");
        return out.toString();
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * The transition panel. DOC-08 §7.1: "Transitions available from the keyboard on a focused item."
     *
     * <p>An unavailable transition is rendered <b>disabled with its reason</b> rather than hidden. A
     * button that silently disappears teaches nothing: a requester who cannot submit needs to know it is
     * the readiness attestation, not that the control moved. Each is a real {@code <button>} in a
     * {@code <form>}, so nothing here is pointer-only or script-only.
     *
     * <p>A transition requiring a reason gets a required text field. {@code PRD-UIX-014} keeps a
     * destructive action off the default focus target, which is why the reason field precedes the button
     * rather than the form submitting on Enter from an empty state.
     */
    private static String actions(Messages messages, Map<String, Object> request,
            List<aspm.app.resource.RequestTransition.Available> transitions) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2>")
                .append(Html.text(messages.get("request.actions.title"))).append("</h2>")
                .append("<div class=\"card-actions\"><span class=\"pill pill-info\">")
                .append(Html.text(String.valueOf(request.get("state")))).append("</span></div></div>");

        if (transitions.isEmpty()) {
            out.append("<div class=\"card-body\"><p class=\"fs-12 muted\">")
                    .append(Html.text(messages.get("request.actions.terminal")))
                    .append("</p></div></section>");
            return out.toString();
        }

        out.append("<div class=\"card-body col gap-4\">");
        for (var transition : transitions) {
            out.append("<form method=\"post\" action=\"/requests/")
                    .append(Html.text(String.valueOf(request.get("id"))))
                    .append("/transitions\" class=\"col gap-1\">")
                    // The replay key. Without it this form posted to a class B operation with no
                    // idempotency key and the dispatcher answered 400 — every transition button on this
                    // page, for as long as the page has existed. See Dispatcher.IDEMPOTENCY_FIELD.
                    //
                    // One value per rendered form, so a double-click or a refresh-repost carries the key
                    // it already sent, which is the case replay protection is for.
                    .append(Forms.idempotencyField())
                    .append("<input type=\"hidden\" name=\"event\" value=")
                    .append(Html.attribute(transition.event())).append(">");

            if (transition.reasonRequired() && transition.permitted()) {
                out.append("<label class=\"col gap-1\">")
                        .append("<span class=\"fs-12 muted\">")
                        .append(Html.text(messages.get("request.actions.reason"))).append("</span>")
                        // The shared control class, not a copy of it inline. The copy existed only
                        // because this field was written before Forms had one, and it drifted: it
                        // never picked up the focus ring the rest of the platform's inputs have.
                        .append("<input class=\"input\" name=\"reason\" required ")
                        .append("autocomplete=\"off\"></label>");
            }

            out.append("<button class=\"btn btn-sm")
                    .append(transition.permitted() ? " btn-primary" : "").append("\" type=\"submit\"")
                    .append(transition.permitted() ? "" : " disabled")
                    .append(">")
                    .append(Html.text(messages.getOr("request.event." + transition.event(),
                            transition.event())))
                    .append("</button>");

            if (!transition.permitted()) {
                out.append("<p class=\"fs-12 muted\">")
                        .append(Html.text(messages.get("request.actions.blocked",
                                transition.blockedReason().orElse(""))))
                        .append("</p>");
            }
            out.append("</form>");
        }
        out.append("</div><div class=\"card-footer\">")
                .append(Html.text(messages.get("request.actions.note")))
                .append("</div></section>");
        return out.toString();
    }

    /**
     * The transition timeline. DOC-09 §3 requires actor, actor type, timestamp, duration in the prior
     * state and whether the clock was running, and the log is append-only — so this is the record, not
     * a reconstruction.
     */
    private static String timeline(Messages messages, List<Map<String, Object>> history) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2>")
                .append(Html.text(messages.get("request.timeline.title"))).append("</h2></div>");
        if (history.isEmpty()) {
            out.append("<div class=\"card-body\"><p class=\"fs-12 muted\">")
                    .append(Html.text(messages.get("request.timeline.none")))
                    .append("</p></div></section>");
            return out.toString();
        }
        out.append("<div class=\"card-body col gap-3\">");
        for (Map<String, Object> entry : history) {
            out.append("<div class=\"col gap-tight\">")
                    .append("<div class=\"row wrap gap-2\">");
            if (entry.get("from_state") != null) {
                out.append("<span class=\"pill pill-unknown\">")
                        .append(Html.text(String.valueOf(entry.get("from_state")))).append("</span>")
                        .append("<span class=\"subtle\" aria-hidden=\"true\">&rarr;</span>");
            }
            out.append("<span class=\"pill pill-info\">")
                    .append(Html.text(String.valueOf(entry.get("to_state")))).append("</span>")
                    .append("<code class=\"fs-12\">")
                    .append(Html.text(String.valueOf(entry.get("event_code")))).append("</code>")
                    .append("</div>")
                    .append("<div class=\"fs-12 subtle\">")
                    .append(Html.text(String.valueOf(entry.get("occurred_at")))).append(" · ")
                    .append(Html.text(String.valueOf(entry.get("actor_type"))));
            if (entry.get("prior_state_duration") != null) {
                out.append(" · ").append(Html.text(messages.get("request.timeline.duration",
                        String.valueOf(entry.get("prior_state_duration")))));
            }
            // Whether the clock was running is part of the record, because a duration without it cannot
            // be read as service-level time (PRD-RSK-034).
            out.append(" · ").append(Html.text(messages.get(
                    Boolean.TRUE.equals(entry.get("sla_clock_running"))
                            ? "request.timeline.clockRunning" : "request.timeline.clockPaused")));
            out.append("</div>");
            if (entry.get("reason") != null) {
                out.append("<p class=\"fs-12\">")
                        .append(Html.text(String.valueOf(entry.get("reason")))).append("</p>");
            }
            out.append("</div>");
        }
        out.append("</div></section>");
        return out.toString();
    }

    private static String readiness(Messages messages, Map<String, Object> request) {
        long met = READINESS.stream().filter(c -> Boolean.TRUE.equals(request.get(c[0]))).count();
        boolean attested = request.get("readiness_attested_at") != null;
        boolean complete = met == READINESS.size() && attested;

        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2>")
                .append(Html.text(messages.get("request.readiness.title"))).append("</h2>")
                .append("<div class=\"card-actions\">")
                .append(complete
                        ? "<span class=\"pill pill-ok\">" + Html.text(messages.get("request.readiness.complete")) + "</span>"
                        : "<span class=\"pill pill-warn\">" + Html.text(messages.get("request.readiness.incomplete")) + "</span>")
                .append("</div></div><div class=\"card-body\">");

        out.append("<ul class=\"col gap-2\">");
        for (String[] condition : READINESS) {
            boolean ok = Boolean.TRUE.equals(request.get(condition[0]));
            out.append("<li class=\"row gap-3\">")
                    .append(ok
                            ? "<span class=\"pill pill-ok\">" + Html.text(messages.get("request.readiness.met")) + "</span>"
                            : "<span class=\"pill pill-warn\">" + Html.text(messages.get("request.readiness.missing")) + "</span>")
                    .append("<span>").append(Html.text(messages.get(condition[1]))).append("</span></li>");
        }
        out.append("</ul>");

        // The attestation is separate from the four conditions, because a checkbox nobody signed is a
        // claim with no author. INV-ASM-04's gate is the attestation, not the flags.
        out.append("<p class=\"fs-12 muted mt-3\">")
                .append(Html.text(attested
                        ? messages.get("request.readiness.attestedAt",
                                String.valueOf(request.get("readiness_attested_at")))
                        : messages.get("request.readiness.notAttested")))
                .append("</p>");

        if (!complete) {
            out.append("<div class=\"banner mt-3\"><span>")
                    .append(Html.text(messages.get("request.readiness.gate"))).append("</span></div>");
        }
        out.append("</div></section>");
        return out.toString();
    }

    private static String accounts(Messages messages, List<Map<String, Object>> accounts) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2>")
                .append(Html.text(messages.get("request.accounts.title"))).append("</h2></div>");

        if (accounts.isEmpty()) {
            out.append("<div class=\"card-body\">")
                    .append(StateRenderer.state(messages,
                            aspm.module.insight.domain.PresentationState.EMPTY_NO_DATA,
                            java.util.Optional.of(messages.get("request.accounts.none"))))
                    .append("</div></section>");
            return out.toString();
        }

        // INV-ASM-02 is a set assertion over roles. Counting per role here is what turns the invariant
        // into something a requester can act on before submitting.
        Map<String, Long> perRole = new java.util.LinkedHashMap<>();
        for (Map<String, Object> account : accounts) {
            perRole.merge(String.valueOf(account.get("role_name")), 1L, Long::sum);
        }
        List<String> short_ = perRole.entrySet().stream().filter(e -> e.getValue() < 2)
                .map(Map.Entry::getKey).toList();
        if (!short_.isEmpty()) {
            out.append("<div class=\"card-body pb-0\"><div class=\"banner\">")
                    .append("<span>")
                    .append(Html.text(messages.get("request.accounts.twoPerRole",
                            String.join(", ", short_))))
                    .append("</span></div></div>");
        }

        out.append("<div class=\"table-scroll\"><table class=\"data\"><thead><tr>")
                .append(th(messages, "request.accounts.role"))
                .append(th(messages, "request.accounts.username"))
                .append(th(messages, "request.accounts.credential"))
                .append(th(messages, "request.accounts.mfa"))
                .append(th(messages, "request.accounts.status"))
                .append("</tr></thead><tbody>");
        for (Map<String, Object> account : accounts) {
            out.append("<tr tabindex=\"-1\">")
                    .append("<td class=\"cell-primary\">")
                    .append(Html.text(String.valueOf(account.get("role_name")))).append("</td>")
                    .append("<td class=\"mono\">")
                    .append(Html.text(String.valueOf(account.get("username")))).append("</td>")
                    // The reference, never a value, and no reveal control beside it (SEC-SEC-024).
                    .append("<td><span class=\"id-chip\">")
                    .append(Html.text(String.valueOf(account.get("credential_ref")))).append("</span>")
                    .append("<span class=\"visually-hidden\">")
                    .append(Html.text(messages.get("request.accounts.referenceOnly")))
                    .append("</span></td>")
                    .append("<td>").append(Boolean.TRUE.equals(account.get("mfa_enrolled"))
                            ? "<span class=\"pill pill-ok\">" + Html.text(messages.get("request.accounts.mfaOn")) + "</span>"
                            : "<span class=\"pill pill-unknown\">" + Html.text(messages.get("request.accounts.mfaOff")) + "</span>")
                    .append("</td>")
                    .append("<td><span class=\"pill pill-info\">")
                    .append(Html.text(String.valueOf(account.get("account_status")))).append("</span></td>")
                    .append("</tr>");
        }
        out.append("</tbody></table></div>")
                .append("<div class=\"card-footer\">")
                .append(Html.text(messages.get("request.accounts.referenceOnly")))
                .append("</div></section>");
        return out.toString();
    }

    private static String environments(Messages messages, List<Map<String, Object>> environments) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2>")
                .append(Html.text(messages.get("request.environments.title"))).append("</h2></div>");
        if (environments.isEmpty()) {
            out.append("<div class=\"card-body\">")
                    .append(StateRenderer.state(messages,
                            aspm.module.insight.domain.PresentationState.EMPTY_NO_DATA,
                            java.util.Optional.of(messages.get("request.environments.none"))))
                    .append("</div></section>");
            return out.toString();
        }
        out.append("<div class=\"card-body col gap-4\">");
        for (Map<String, Object> environment : environments) {
            out.append("<div class=\"col gap-1\">")
                    .append("<div class=\"row\"><span class=\"pill pill-info\">")
                    .append(Html.text(String.valueOf(environment.get("env_type")))).append("</span>")
                    .append("<span class=\"mono fs-12\">")
                    .append(Html.text(String.valueOf(environment.get("base_url")))).append("</span></div>")
                    .append("<div class=\"row wrap fs-12 muted gap-3\">")
                    .append(flag(messages, "request.env.protectiveControl",
                            environment.get("protective_control_present")))
                    .append(flag(messages, "request.env.bypassArranged",
                            environment.get("bypass_arranged")))
                    .append(flag(messages, "request.env.rateLimit",
                            environment.get("rate_limit_present")))
                    .append(flag(messages, "request.env.dataDestruction",
                            environment.get("data_destruction_allowed")))
                    .append(flag(messages, "request.env.vpn", environment.get("vpn_required")))
                    .append("</div></div>");
        }
        out.append("</div></section>");
        return out.toString();
    }

    private static String summary(Messages messages, Map<String, Object> request) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2>")
                .append(Html.text(messages.get("request.summary.title"))).append("</h2></div>")
                .append("<div class=\"card-body col gap-3\">");
        out.append(row(messages, "request.field.state",
                "<span class=\"pill pill-info\">"
                        + Html.text(String.valueOf(request.get("state"))) + "</span>"));
        out.append(row(messages, "request.field.retest",
                Boolean.TRUE.equals(request.get("is_retest"))
                        ? Html.text(messages.get("request.yes"))
                        : Html.text(messages.get("request.no"))));
        out.append(row(messages, "request.field.submitted", value(messages, request.get("submitted_at"))));
        out.append(row(messages, "request.field.requestedBy",
                value(messages, request.get("requested_by"))));
        out.append("</div></section>");
        return out.toString();
    }

    private static String derived(Messages messages, Map<String, Object> request) {
        StringBuilder out = new StringBuilder();
        out.append("<section class=\"card\"><div class=\"card-header\"><h2>")
                .append(Html.text(messages.get("request.derived.title"))).append("</h2></div>")
                .append("<div class=\"card-body col gap-3\">");
        out.append(row(messages, "request.field.priority",
                value(messages, request.get("derived_priority_score"))));
        out.append(row(messages, "request.field.effort",
                value(messages, request.get("derived_effort_days"))));
        out.append(row(messages, "request.field.feasibleStart",
                value(messages, request.get("derived_feasible_start"))));
        out.append("</div><div class=\"card-footer\">")
                .append(Html.text(messages.get("request.derived.note")))
                .append("</div></section>");
        return out.toString();
    }

    // ----------------------------------------------------------------------------------------------

    private static String th(Messages messages, String key) {
        return "<th scope=\"col\">" + Html.text(messages.get(key)) + "</th>";
    }

    private static String row(Messages messages, String labelKey, String valueHtml) {
        return "<div class=\"row between\"><span class=\"fs-12 muted\">"
                + Html.text(messages.get(labelKey)) + "</span><span>" + valueHtml + "</span></div>";
    }

    /** A null derived value is the unmeasured state, never a dash a reader resolves to zero. */
    private static String value(Messages messages, Object value) {
        if (value == null) {
            return "<span class=\"state-label fs-12\">"
                    + Html.text(messages.get("state.unmeasured")) + "</span>";
        }
        return "<span class=\"tabular\">" + Html.text(String.valueOf(value)) + "</span>";
    }

    private static String flag(Messages messages, String labelKey, Object value) {
        boolean on = Boolean.TRUE.equals(value);
        return "<span class=\"row gap-1\"><span class=\"pill "
                + (on ? "pill-ok" : "pill-unknown") + "\">"
                + Html.text(messages.get(on ? "request.yes" : "request.no")) + "</span>"
                + Html.text(messages.get(labelKey)) + "</span>";
    }
}
