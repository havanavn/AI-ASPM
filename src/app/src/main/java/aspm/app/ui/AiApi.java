package aspm.app.ui;

import aspm.app.ai.Assistant;
import aspm.app.ai.Copilot;
import aspm.app.ai.Invocations;
import aspm.app.ai.ModelClient;
import aspm.app.ai.ModelEvaluation;
import aspm.app.resource.AiProviderService;
import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The AI surfaces of ADR-075 over JSON: ask, draft, usage and budget, provider probe, evaluation.
 * {@code PRD-AIC-019}, {@code PRD-AIC-043}, {@code PRD-AIC-044}, {@code PRD-AIC-049}, {@code PRD-AIC-053},
 * {@code PRD-AIC-057}.
 */
public final class AiApi {

    private final Assistant assistant;
    private final Copilot copilot;
    private final Invocations invocations;
    private final AiProviderService providers;
    private final ModelEvaluation evaluation;

    public AiApi(DataSource dataSource) {
        Objects.requireNonNull(dataSource);
        this.assistant = new Assistant(dataSource);
        this.copilot = new Copilot(dataSource);
        this.invocations = new Invocations(dataSource);
        this.providers = new AiProviderService(dataSource);
        this.evaluation = new ModelEvaluation(dataSource);
    }

    /** {@code POST /api/ui/ai/ask} with {@code {question, scopeNodeId?}}. */
    public Dispatcher.Response ask(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        Object out = assistant.ask(request.principal(), text(body, "question").orElse(""), uuid(body, "scopeNodeId"));
        Map<String, Object> m = new LinkedHashMap<>();
        if (out instanceof Assistant.Answer a) {
            m.put("answer", a.text());
            m.put("citations", a.citations());
            m.put("insufficient", a.insufficient());
            m.put("modelIdentity", a.modelIdentity());
            m.put("promptVersion", a.promptVersion());
            m.put("generated", true);
            m.put("facts", facts(a.facts()));
        } else {
            Assistant.Refused r = (Assistant.Refused) out;
            m.put("refused", r.code());
            m.put("detail", r.detail());
            m.put("facts", facts(r.facts()));
        }
        return Dispatcher.Response.ok(m);
    }

    /** {@code POST /api/ui/ai/draft} with {@code {kind, title?, notes}}. */
    public Dispatcher.Response draft(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        Object out = assistant.draft(request.principal(), text(body, "kind").orElse(""), text(body, "title").orElse(null), text(body, "notes").orElse(""));
        Map<String, Object> m = new LinkedHashMap<>();
        if (out instanceof Assistant.Draft d) {
            m.put("draft", d.text());
            m.put("modelIdentity", d.modelIdentity());
            m.put("promptVersion", d.promptVersion());
            m.put("generated", true);
        } else {
            Assistant.Refused r = (Assistant.Refused) out;
            m.put("refused", r.code());
            m.put("detail", r.detail());
        }
        return Dispatcher.Response.ok(m);
    }

    // ==============================================================================================
    // The copilot (PRD-AIC-058, ADR-077)
    // ==============================================================================================

    /**
     * {@code GET /api/ui/ai/copilot}. The panel's opening state: this person's conversations, the
     * starter questions their permissions make answerable, and which packs they may read.
     */
    public Dispatcher.Response copilotOpen(Dispatcher.Request request) throws SQLException {
        Principal principal = request.principal();
        List<Map<String, Object>> conversations = new ArrayList<>();
        for (Copilot.Conversation v : copilot.conversations(principal, 20)) {
            conversations.add(conversation(v));
        }
        List<Map<String, Object>> starters = new ArrayList<>();
        for (Copilot.Starter starter : copilot.starters(principal)) {
            starters.add(Map.of("text", starter.text(), "pack", starter.pack()));
        }
        // What it can and cannot look at, stated before anybody asks. A copilot that refuses part of a
        // question is easier to trust when it said in advance which part it would refuse (PRD-AIC-048).
        List<Map<String, Object>> packs = new ArrayList<>();
        for (Copilot.Pack pack : Copilot.PACKS) {
            boolean readable = pack.permission().isEmpty() || principal.holds(pack.permission());
            packs.add(Map.of("code", pack.code(), "label", pack.label(),
                    "permission", pack.permission().isEmpty() ? "none" : pack.permission(),
                    "readable", Boolean.valueOf(readable)));
        }
        return Dispatcher.Response.ok(Map.of("conversations", conversations, "starters", starters, "packs", packs,
                "individualWorkload", Boolean.valueOf(principal.holds(Copilot.WORKLOAD_INDIVIDUAL))));
    }

    /** {@code GET /api/ui/ai/copilot/{id}}. One conversation, replayed. Empty where it is not this person's. */
    public Dispatcher.Response copilotConversation(Dispatcher.Request request) throws SQLException {
        UUID id = pathId(request);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Copilot.Message m : copilot.messages(request.principal(), id)) {
            messages.add(message(m));
        }
        return Dispatcher.Response.ok(Map.of("id", id.toString(), "messages", messages));
    }

    /** {@code POST /api/ui/ai/copilot} with {@code {question, conversationId?}}. */
    public Dispatcher.Response copilotAsk(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        Copilot.Turn turn = copilot.ask(request.principal(), uuid(body, "conversationId"),
                text(body, "question").orElse(""));
        return Dispatcher.Response.ok(Map.of("conversation", conversation(turn.conversation()),
                "answer", message(turn.answer()), "followUps", turn.followUps()));
    }

    /** {@code POST /api/ui/ai/copilot/{id}/clear}. Drops it from the person's list; the rows stay (V081). */
    public Dispatcher.Response copilotClear(Dispatcher.Request request) throws SQLException {
        return Dispatcher.Response.ok(Map.of("cleared",
                Boolean.valueOf(copilot.clear(request.principal(), pathId(request)))));
    }

    private static Map<String, Object> conversation(Copilot.Conversation v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("title", v.title());
        m.put("updatedAt", v.updatedAt());
        m.put("messages", Integer.valueOf(v.messages()));
        m.put("focusAssetId", v.focusAssetId());
        m.put("focusAssetName", v.focusAssetName());
        return m;
    }

    private static Map<String, Object> message(Copilot.Message message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", message.id());
        m.put("role", message.role());
        m.put("text", message.text());
        m.put("citations", message.citations());
        m.put("facts", facts(message.facts()));
        m.put("topics", message.topics());
        m.put("withheld", message.withheld());
        m.put("modelIdentity", message.modelIdentity());
        m.put("createdAt", message.generatedAt());
        m.put("refused", message.refusalCode());
        // PRD-AIC-036: whether a model wrote this, in every representation. The rules path is not
        // generated content and must not wear the label.
        m.put("generated", Boolean.valueOf(message.generated()));
        return m;
    }

    private static UUID pathId(Dispatcher.Request request) {
        try {
            return UUID.fromString(request.pathVariables().get("id"));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the identifier is not a UUID");
        }
    }

    /** {@code GET /api/ui/ai/usage?days=30}. */
    public Dispatcher.Response usage(Dispatcher.Request request) throws SQLException {
        int days = 30;
        try {
            days = Integer.parseInt(request.query().getOrDefault("days", "30"));
        } catch (NumberFormatException ignored) {
            // the default stands
        }
        Map<String, Object> out = new LinkedHashMap<>(invocations.usage(request.principal(), days));
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (ModelClient.Kind k : ModelClient.KINDS) {
            kinds.add(Map.of("code", k.code(), "label", k.label(), "family", k.family(), "defaultBaseUrl", k.defaultBaseUrl(), "hint", k.hint()));
        }
        out.put("kinds", kinds);
        out.put("vouchedEndpoints", aspm.app.ai.ModelEndpoints.vouched());
        out.put("mayManage", request.principal().holds(AiProviderService.MANAGE));
        return Dispatcher.Response.ok(out);
    }

    /** {@code POST /api/ui/ai/budget}. */
    public Dispatcher.Response budget(Dispatcher.Request request) throws SQLException {
        Map<String, Object> body = request.body().orElse(Map.of());
        Invocations.Budget current = invocations.budget(request.principal());
        Invocations.Budget saved = invocations.saveBudget(request.principal(),
                integer(body, "dailyTokenBudget").orElse(current.dailyTokenBudget()),
                integer(body, "perPrincipalHourlyInvocations").orElse(current.perPrincipalHourlyInvocations()),
                integer(body, "cacheMinutes").orElse(current.cacheMinutes()),
                body.containsKey("retainPrompts") ? Boolean.TRUE.equals(body.get("retainPrompts")) : current.retainPrompts(),
                body.containsKey("retainOutputs") ? Boolean.TRUE.equals(body.get("retainOutputs")) : current.retainOutputs());
        return Dispatcher.Response.ok(Map.of("dailyTokenBudget", saved.dailyTokenBudget(), "perPrincipalHourlyInvocations", saved.perPrincipalHourlyInvocations(),
                "cacheMinutes", saved.cacheMinutes(), "retainPrompts", saved.retainPrompts(), "retainOutputs", saved.retainOutputs()));
    }

    /** {@code POST /api/ui/ai-providers/{id}/test}. */
    public Dispatcher.Response testProvider(Dispatcher.Request request) throws SQLException {
        UUID id;
        try {
            id = UUID.fromString(request.pathVariables().get("id"));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the identifier is not a UUID");
        }
        return Dispatcher.Response.ok(providers.test(request.principal(), id));
    }

    /** {@code POST /api/ui/ai/evaluate}. Runs the harness now; takes as long as the provider takes. */
    public Dispatcher.Response evaluate(Dispatcher.Request request) throws SQLException {
        ModelEvaluation.Run run = evaluation.run(request.principal());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.id().toString());
        m.put("modelIdentity", run.modelIdentity());
        m.put("gate", run.gate());
        m.put("scenarios", run.scenarios());
        Map<String, Object> measures = new LinkedHashMap<>();
        run.measures().forEach((k, v) -> measures.put(k, Map.of("pass", v.pass(), "total", v.total(), "threshold", v.threshold(), "gate", v.gate(), "percent", v.percent())));
        m.put("measures", measures);
        m.put("failures", run.failures());
        return Dispatcher.Response.ok(m);
    }

    /** {@code GET /api/ui/ai/evaluations}. */
    public Dispatcher.Response evaluations(Dispatcher.Request request) throws SQLException {
        return Dispatcher.Response.ok(Map.of("rows", evaluation.runs(request.principal())));
    }

    // ==============================================================================================

    private static List<Map<String, Object>> facts(List<Assistant.Fact> facts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Assistant.Fact f : facts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", f.ref());
            m.put("text", f.text());
            m.put("link", f.link().orElse(null));
            out.add(m);
        }
        return out;
    }

    private static Optional<String> text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null || String.valueOf(value).isBlank() ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    private static Optional<UUID> uuid(Map<String, Object> body, String key) {
        return text(body, key).map(v -> {
            try {
                return UUID.fromString(v);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(key + " is not a UUID");
            }
        });
    }

    private static Optional<Integer> integer(Map<String, Object> body, String key) {
        return body.get(key) instanceof Number n ? Optional.of(n.intValue()) : Optional.empty();
    }

    /** Unused-parameter guard so the principal type stays referenced for readers of this class. */
    static boolean mayManage(Principal principal) {
        return principal.holds(AiProviderService.MANAGE);
    }
}
