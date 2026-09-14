package aspm.app.ai;

import aspm.app.ai.Assistant.Fact;
import aspm.app.persistence.TenantConnections;
import aspm.app.resource.ModelNarrator;
import aspm.app.resource.OverviewInsights;
import aspm.app.resource.RiskScoring;
import aspm.app.runtime.Json;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The copilot: a conversation about the posture, answered from what the asker may see.
 *
 * <h2>What makes this different from {@code posture.answer}</h2>
 *
 * <p>{@link Assistant#ask} gathers one fixed set of figures and answers one question. That is enough for
 * "how are we doing" and wrong for everything else: asked "has this application been assessed", it
 * answers from estate-wide counts that do not mention the application, and the answer is fluent and
 * useless. The copilot has three parts the single-shot surface does not.
 *
 * <ol>
 *   <li><b>Retrieval per question.</b> Eleven fact packs, each a query the platform owns. A question is
 *       routed to the packs that can answer it, and only those are gathered — so an answer about service
 *       levels is not padded with a component inventory the reader did not ask about, and the model is
 *       not handed the estate every time somebody types a sentence.</li>
 *   <li><b>Entity resolution the model never performs.</b> "This application" and "Payments Portal" are
 *       resolved <b>in SQL, inside the caller's scope</b>. A model that could name a row could name one
 *       the caller may not see; asking it for an identifier would make the prompt an authorization
 *       decision point, which product principle 4 forbids. What it may do is suggest a <i>name</i> to
 *       search for, which the platform then resolves or fails to resolve.</li>
 *   <li><b>Permission-gated packs.</b> Each pack declares the permission it needs. A pack the caller
 *       cannot read is not gathered and is <b>named as withheld</b> — {@code PRD-AIC-048}: the answer
 *       says which part of the question it could not look at, rather than answering a narrower question
 *       as though it were the one asked. Per-person workload is the sharp case: DOC-07 §5.2 puts it
 *       behind {@code cap.member.read.all} and DOC-12 §5.1 keeps it out of executive presentation
 *       entirely, so seniority does not reach it and neither does this.</li>
 * </ol>
 *
 * <h2>Every number comes from a query</h2>
 *
 * <p>ADR-038 holds here as everywhere: the model writes the sentences around figures the platform
 * retrieved, and may not produce one of its own. The same four controls that guard {@code posture.answer}
 * guard this — an unresolved citation, an uncited claim, an invented figure, a severity the record does
 * not carry — and they are the same code ({@link Assistant#rejection}), not a second copy that could
 * drift. A failed control rejects the whole answer; it is never repaired.
 *
 * <h2>It answers with no model at all</h2>
 *
 * <p>Routing falls back to keyword rules, and composition falls back to sentences built from the facts.
 * A deployment with no provider configured, a provider that is rate limiting, or an exhausted budget
 * still gets a correct if plainer answer, labelled as written from the figures rather than by a model
 * (PP-9). The rules recognise Vietnamese and English, with and without diacritics, because the first
 * target locale is Vietnamese ({@code NFR-INT-003}) and people type without tone marks.
 */
public final class Copilot {

    /**
     * The copilot's own permission (V082), separate from {@code aic.assist.use}.
     *
     * <p>The draft button and the single-question ask box act on the record the person already has
     * open. This reaches across their whole scope, on every page, and composes across packs a person
     * would otherwise visit one screen at a time. Both are bounded by the same scope predicate and
     * neither discloses a row the caller could not open — but a tenant that wants drafting help for its
     * engineers without a conversational window onto the estate has to be able to say so, and with one
     * permission it could not. Reported from use on 2026-09-13.
     */
    public static final String USE = "aic.copilot.use";
    public static final String CAPABILITY = "copilot.chat";
    public static final String ROUTE_PROMPT = "copilot-route/v1";
    public static final String ANSWER_PROMPT = "copilot-answer/v1";

    /** How many prior turns are given to the model. Enough to carry a thread, short enough to stay cheap. */
    private static final int HISTORY_TURNS = 6;
    private static final int MAX_QUESTION = 2000;

    /**
     * How many fact packs one turn may retrieve.
     *
     * <p>Five. Above that the prompt stops being a set of facts and becomes a filing cabinet: the two
     * questions in the twenty-question measurement that produced an uncited answer were the two that
     * retrieved seven and eight packs. Fewer, better-chosen facts also cost less and answer faster.
     */
    private static final int MAX_PACKS = 5;

    // ==============================================================================================
    // The packs
    // ==============================================================================================

    /**
     * A pack anybody signed in may read.
     *
     * <p>The documentation packs. Both guides are navigation entries with no permission — a product that
     * explains itself only to the people who already have authority explains itself to the wrong half of
     * its users, and product principle 7 says the largest population has the narrowest permissions and
     * the least training. Neither guide contains tenant data.
     */
    public static final String PUBLIC = "";

    /** The finer half of the access pack: what a role actually carries, and who holds it. */
    public static final String ACCESS_ROLE_DETAIL = "auz.role.manage";

    /**
     * One body of retrievable facts.
     *
     * @param permission what the caller must hold; the pack is withheld and named otherwise
     * @param keywords matched against the question with diacritics stripped, Vietnamese and English
     */
    public record Pack(String code, String label, String permission, List<String> keywords) {
    }

    /**
     * The packs, in the order they are gathered.
     *
     * <p>Order matters for reading, not for correctness: the estate before the detail, the commitments
     * before the plan, so a reader following the F-numbers meets the general before the particular.
     */
    public static final List<Pack> PACKS = List.of(
            new Pack("POSTURE", "Open weaknesses by severity, age and exposure", "vul.finding.read",
                    List.of("risk", "rui ro", "lo hong", "lohong", "vulnerab", "finding", "security", "bao mat",
                            "posture", "tinh hinh", "tong quan", "nghiem trong", "critical", "high", "severity",
                            "muc do", "open", "dang mo", "chua fix", "chua khac phuc", "chua xu ly", "an toan")),
            new Pack("APPLICATIONS", "The applications named in the question, or the most exposed", "ast.asset.read",
                    // No "api" here: it belongs to the integration pack, and having it in both dragged the
                    // whole application inventory into every question about calling the platform.
                    List.of("ung dung", "application", "app", "san pham", "product", "he thong", "system",
                            "service", "dich vu", "website", "web", "portal")),
            new Pack("COVERAGE", "Which applications have been assessed, and which never have", "ast.asset.read",
                    List.of("danh gia", "assess", "pentest", "kiem thu", "review", "ra soat", "coverage",
                            "do phu", "chua duoc", "da duoc", "bao gio", "lan cuoi", "last review", "never")),
            new Pack("SLA", "Remediation commitments: breached, and falling due", "vul.finding.read",
                    List.of("sla", "tre", "qua han", "overdue", "due", "deadline", "cam ket", "commitment",
                            "breach", "vi pham", "nguy co", "sap den han", "han chot", "late", "at risk", "dung han")),
            new Pack("REQUESTS", "Assessment requests and where each one is waiting", "asm.request.read",
                    List.of("request", "yeu cau", "phieu", "board", "cong viec", "work item", "in flight",
                            "dang thuc hien", "dang lam", "waiting", "cho", "ticket", "bao gio xong")),
            new Pack("WORKLOAD", "Load per assessor team and, where permitted, per person", "cap.team.read",
                    List.of("workload", "khoi luong", "tai", "nhan su", "team", "doi", "nhom", "ai dang",
                            "ai lam", "phan cong", "assign", "capacity", "nang luc", "ban ron", "busy", "per person")),
            new Pack("ORGANIZATIONS", "Posture per organization, rolled up over everything it owns", "org.node.read",
                    List.of("don vi", "to chuc", "business unit", "cong ty", "phong ban", "org", "bu",
                            "subsidiary", "cong ty con", "so sanh", "compare", "moi don vi", "each unit")),
            new Pack("EXCEPTIONS", "Accepted risks and when each acceptance lapses", "vul.finding.read",
                    List.of("ngoai le", "exception", "chap nhan rui ro", "risk accept", "mien tru", "waiver",
                            "het han", "expire", "gia han", "renew", "phe duyet", "approved")),
            new Pack("TOP_FINDINGS", "The highest-scoring open findings, with what drives each score", "vul.finding.read",
                    List.of("worst", "te nhat", "nghiem trong nhat", "uu tien", "priority", "top", "dau tien",
                            "lam gi truoc", "what first", "highest", "cao nhat", "nguy hiem nhat")),
            new Pack("TREND", "Direction over time: the backlog against ninety days ago, what opened and closed "
                    + "by month, and how long remediation takes", "vul.finding.read",
                    List.of("xu huong", "trend", "so voi", "compared", "compare", "thang truoc", "last month",
                            "tang hay giam", "better or worse", "tot len", "xau di", "improving", "getting better",
                            "toc do", "how fast", "bao lau", "how long", "mttr", "thoi gian khac phuc",
                            "remediation time", "cycle time", "ba thang", "quy truoc", "nam nay", "over time",
                            "lich su", "history", "progress", "tien do")),
            new Pack("ASSESS_NEXT", "Applications owed an assessment, ordered by what makes each one urgent",
                    "ast.asset.read",
                    List.of("nen danh gia", "should i assess", "should assess", "assess next", "assess first",
                            "uu tien danh gia", "danh gia truoc", "ung dung nao", "app nao", "which application",
                            "what should i review", "chon ung dung", "thang", "month", "quy", "quarter",
                            "tuan", "week", "sap toi", "ke tiep", "next")),
            new Pack("PLAN", "The periodic assessment plan ahead, and who is expected to run it", "ast.asset.read",
                    List.of("ke hoach", "plan", "lich", "schedule", "sap toi", "quy", "quarter", "thang toi",
                            "next month", "upcoming", "dinh ky", "periodic", "cadence", "chu ky")),
            new Pack("HOWTO", "The user guide: what each screen does and how to do a thing in it", PUBLIC,
                    List.of("huong dan", "how do i", "how to", "lam the nao", "lam sao", "cach", "guide",
                            "su dung", "use", "usage", "man hinh", "screen", "where do i", "o dau", "tai sao",
                            "why", "nghia la gi", "what does", "giai thich", "explain", "bat dau", "get started")),
            new Pack("API", "The integration guide and the operations a caller may call", PUBLIC,
                    List.of("api", "endpoint", "rest", "curl", "http", "request body", "tich hop", "integrate",
                            "integration", "ky request", "sign", "signature", "hmac", "credential", "token",
                            "idempotency", "webhook", "push", "upload", "submit", "gui ket qua", "ci", "pipeline",
                            "swagger", "openapi", "payload", "json")),
            new Pack("ACCESS", "Roles, the permissions each carries, and who holds them", "iam.user.read",
                    List.of("phan quyen", "quyen", "permission", "role", "vai tro", "cap quyen", "grant",
                            // Not bare "quan tri": "hội đồng quản trị" is the board, and a question about what
                            // to report to it was routed to the role catalogue.
                            "revoke", "thu hoi", "access", "truy cap", "nguoi dung", "user", "account",
                            "team dev", "developer", "admin", "quan tri vien", "scope", "pham vi", "onboard")),
            new Pack("SBOM", "Vulnerable components the estate depends on, and how far the inventory covers it",
                    "sbm.coverage.read",
                    List.of("sbom", "thanh phan", "component", "thu vien", "library", "dependency", "phu thuoc",
                            "package", "cve", "supply chain", "chuoi cung ung", "log4j", "version", "advisory",
                            "nang cap", "upgrade", "patch", "ban va", "vendor", "open source", "ma nguon mo",
                            "npm", "maven", "pypi", "golang", "nuget")));

    /** The permission the individual half of the workload pack needs. DOC-07 §5.2: never implied by seniority. */
    public static final String WORKLOAD_INDIVIDUAL = "cap.member.read.all";


    // ==============================================================================================
    // What comes back
    // ==============================================================================================

    public record Conversation(String id, String title, String updatedAt, int messages,
            String focusAssetId, String focusAssetName) {
    }

    /** One turn as stored. {@code refusalCode} is set where the model was not used and why. */
    public record Message(String id, String role, String text, List<String> citations, List<Fact> facts,
            List<String> topics, List<String> withheld, String modelIdentity, String generatedAt,
            String refusalCode, boolean generated) {
    }

    /** The answer to one question, plus everything the reader needs to check it. */
    public record Turn(Conversation conversation, Message answer, List<String> followUps) {
    }

    /** A starter question the caller's permissions make answerable. */
    public record Starter(String text, String pack) {
    }

    // ==============================================================================================

    private final DataSource dataSource;
    private final ModelNarrator narrator;
    private final Assistant assistant;
    private final RiskScoring scoring;
    private final OverviewInsights insights;

    public Copilot(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
        this.narrator = new ModelNarrator(dataSource);
        this.assistant = new Assistant(dataSource);
        this.scoring = new RiskScoring(dataSource);
        this.insights = new OverviewInsights(dataSource);
    }

    // ==============================================================================================
    // Conversations
    // ==============================================================================================

    /** This person's conversations, newest first. Never anybody else's — see V081. */
    public List<Conversation> conversations(Principal principal, int limit) throws SQLException {
        List<Conversation> out = new ArrayList<>();
        try (Connection c = open(principal);
                PreparedStatement s = c.prepareStatement("""
                        SELECT v.id::text, v.title, to_char(v.updated_at, 'YYYY-MM-DD HH24:MI'), v.message_count,
                               v.focus_asset_id::text,
                               CASE WHEN v.focus_asset_id IS NULL THEN NULL
                                    ELSE coalesce(a.display_name, '(application no longer exists)') END
                          FROM ai_conversation v
                          LEFT JOIN asset a ON a.id = v.focus_asset_id
                         WHERE v.principal_id = ? AND v.lifecycle_state = 'ACTIVE'
                         ORDER BY v.updated_at DESC
                         LIMIT ?
                        """)) {
            s.setObject(1, principal.principalId());
            s.setInt(2, Math.max(1, Math.min(50, limit)));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(new Conversation(r.getString(1), r.getString(2), r.getString(3), r.getInt(4),
                            r.getString(5), r.getString(6)));
                }
            }
        }
        return out;
    }

    /** One conversation's turns in order, or empty where it is not this person's. */
    public List<Message> messages(Principal principal, UUID conversationId) throws SQLException {
        List<Message> out = new ArrayList<>();
        try (Connection c = open(principal)) {
            if (conversation(c, principal, conversationId).isEmpty()) {
                return List.of();
            }
            try (PreparedStatement s = c.prepareStatement("""
                    -- Wrapped in an object because the shared JSON reader takes an object at the top
                    -- level and these columns are arrays; wrapping here beats widening a kernel parser
                    -- every other caller depends on.
                    SELECT m.id::text, m.role, m.content,
                           jsonb_build_object('items', m.citations)::text,
                           jsonb_build_object('items', m.facts)::text,
                           jsonb_build_object('items', m.topics)::text,
                           jsonb_build_object('items', m.withheld)::text,
                           m.model_identity, to_char(m.created_at, 'YYYY-MM-DD HH24:MI'), m.refusal_code
                      FROM ai_conversation_message m
                     WHERE m.conversation_id = ?
                     ORDER BY m.ordinal
                    """)) {
                s.setObject(1, conversationId);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        out.add(new Message(r.getString(1), r.getString(2), r.getString(3),
                                strings(r.getString(4)), factsOf(r.getString(5)), strings(r.getString(6)),
                                strings(r.getString(7)), r.getString(8), r.getString(9), r.getString(10),
                                r.getString(8) != null));
                    }
                }
            }
        }
        return out;
    }

    /** Drops a conversation from the person's list. The rows stay, per V081. */
    public boolean clear(Principal principal, UUID conversationId) throws SQLException {
        try (Connection c = open(principal);
                PreparedStatement s = c.prepareStatement(
                        "UPDATE ai_conversation SET lifecycle_state = 'CLEARED', updated_at = now() "
                                + "WHERE id = ? AND principal_id = ? AND lifecycle_state = 'ACTIVE'")) {
            s.setObject(1, conversationId);
            s.setObject(2, principal.principalId());
            boolean changed = s.executeUpdate() == 1;
            c.commit();
            return changed;
        }
    }

    /**
     * Starter questions this caller can actually get an answer to.
     *
     * <p>Filtered by permission rather than shown and refused: offering "who is this assigned to" to a
     * person who cannot read the roster teaches them the copilot is unreliable, when it is doing exactly
     * what it should.
     */
    public List<Starter> starters(Principal principal) throws SQLException {
        List<Starter> out = new ArrayList<>();
        if (principal.holds("vul.finding.read")) {
            out.add(new Starter("What security risk do our products carry right now?", "POSTURE"));
            out.add(new Starter("Summarise the open critical and high findings.", "POSTURE"));
            out.add(new Starter("Which findings are past their remediation commitment, or about to be?", "SLA"));
            out.add(new Starter("What should we fix first, and why?", "TOP_FINDINGS"));
        }
        if (principal.holds("ast.asset.read")) {
            out.add(new Starter("Which applications should I assess this month, and why those?", "ASSESS_NEXT"));
            out.add(new Starter("Which applications have never been assessed?", "COVERAGE"));
            out.add(new Starter("What is planned for assessment in the next quarter?", "PLAN"));
        }
        if (principal.holds("sbm.coverage.read")) {
            out.add(new Starter("Which vulnerable libraries do we depend on, and is there a fix?", "SBOM"));
        }
        if (principal.holds("asm.request.read")) {
            out.add(new Starter("Which assessment requests are late or at risk of being late?", "REQUESTS"));
        }
        if (principal.holds("cap.team.read")) {
            out.add(new Starter("How is the workload spread across the assessment teams?", "WORKLOAD"));
        }
        if (principal.holds("org.node.read")) {
            out.add(new Starter("Which organization is in the worst shape, and what is driving it?", "ORGANIZATIONS"));
        }
        // The two questions that have an answer and no row behind it. Offered to everybody, because the
        // people who most need to be told how the product works are the ones with the fewest permissions.
        out.add(new Starter("How do I raise an assessment request?", "HOWTO"));
        out.add(new Starter("How do I call the API, and how is a request signed?", "API"));
        if (principal.holds("iam.user.read")) {
            out.add(new Starter("How do I give the developer team access, and what would they get?", "ACCESS"));
        }
        // Named last and only where there is something to name: a starter that mentions an application
        // the caller cannot reach would be a disclosure by suggestion.
        try (Connection c = open(principal)) {
            String name = firstApplicationName(c, principal);
            if (name != null && principal.holds("ast.asset.read")) {
                out.add(0, new Starter("Does " + name + " carry any security risk?", "APPLICATIONS"));
                out.add(1, new Starter("Has " + name + " been assessed, and when?", "COVERAGE"));
            }
        }
        return out;
    }

    // ==============================================================================================
    // Ask
    // ==============================================================================================

    /**
     * One turn: route, retrieve, answer, record.
     *
     * @param conversationId continue this conversation, or empty to begin one
     */
    public Turn ask(Principal principal, Optional<UUID> conversationId, String question) throws SQLException {
        String asked = question == null ? "" : question.strip();
        if (asked.length() < 3) {
            throw new IllegalArgumentException("ask a question");
        }
        if (asked.length() > MAX_QUESTION) {
            throw new IllegalArgumentException("a question of at most " + MAX_QUESTION + " characters");
        }
        try (Connection c = open(principal)) {
            UUID conversation = conversationId.flatMap(id -> {
                try {
                    return conversation(c, principal, id);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }).orElse(null);
            String priorFocus = null;
            List<String> history = new ArrayList<>();
            if (conversation == null) {
                conversation = createConversation(c, principal, asked);
            } else {
                priorFocus = focusOf(c, conversation);
                history = history(c, conversation);
            }

            // 1. Which packs, and which names to look for.
            Routing routing = route(principal, asked, history);

            // 2. Resolve the applications the question is about, in SQL, inside the caller's scope.
            List<Application> applications = resolveApplications(c, principal, asked, routing.entities(), priorFocus);
            if (!applications.isEmpty() && !routing.packs().contains("APPLICATIONS")) {
                // Naming an application is asking about it, whatever else the question said.
                routing = routing.with("APPLICATIONS");
            }

            // 3. Gather, permission by permission.
            Retrieval retrieval = gather(c, principal, routing, applications, asked);

            // 4. Answer: the model where there is one, the figures where there is not.
            Composed composed = compose(principal, asked, history, retrieval, applications);

            // 5. Record both turns and carry the focus forward.
            UUID focus = applications.isEmpty()
                    ? (priorFocus == null ? null : UUID.fromString(priorFocus))
                    : applications.get(0).id();
            int ordinal = nextOrdinal(c, conversation);
            insertMessage(c, conversation, ordinal, "USER", asked, List.of(), List.of(), List.of(), List.of(),
                    null, null, null, null);
            String messageId = insertMessage(c, conversation, ordinal + 1, "ASSISTANT", composed.text(),
                    composed.citations(), retrieval.facts(), retrieval.used(), retrieval.withheld(),
                    composed.modelIdentity(), composed.promptVersion(), composed.invocationId(), composed.refusalCode());
            try (PreparedStatement s = c.prepareStatement(
                    "UPDATE ai_conversation SET message_count = message_count + 2, updated_at = now(), "
                            + "focus_asset_id = ? WHERE id = ?")) {
                s.setObject(1, focus);
                s.setObject(2, conversation);
                s.executeUpdate();
            }
            c.commit();

            String threadId = conversation.toString();
            Conversation head = conversations(principal, 50).stream()
                    .filter(v -> v.id().equals(threadId))
                    .findFirst()
                    .orElse(new Conversation(threadId, asked, "", ordinal + 2, null, null));
            Message answer = new Message(messageId, "ASSISTANT", composed.text(), composed.citations(),
                    retrieval.facts(), retrieval.used(), retrieval.withheld(), composed.modelIdentity(), "",
                    composed.refusalCode(), composed.modelIdentity() != null);
            return new Turn(head, answer, followUps(retrieval, applications, principal));
        }
    }

    // ==============================================================================================
    // Routing
    // ==============================================================================================

    /** @param entities application names the router suggests looking for; the platform resolves them */
    record Routing(Set<String> packs, List<String> entities, boolean byModel) {
        Routing with(String pack) {
            Set<String> next = new LinkedHashSet<>(packs);
            next.add(pack);
            return new Routing(next, entities, byModel);
        }
    }

    /**
     * Which packs can answer this.
     *
     * <p>The model is asked first because a question can mean a pack without containing any of its
     * words — "are we going to miss anything this month" is about commitments and names none of them.
     * Its answer is a list of pack codes validated against {@link #PACKS}; an unknown code is dropped,
     * not guessed at. Where there is no model, or it refuses, keyword rules stand in; where neither
     * matches anything, the general packs answer the general question.
     */
    private Routing route(Principal principal, String question, List<String> history) throws SQLException {
        Set<String> byRules = keywordPacks(question);
        List<String> codes = new ArrayList<>();
        for (Pack p : PACKS) {
            codes.add(p.code());
        }
        Object out = narrator.structured(principal, CAPABILITY, ROUTE_PROMPT,
                "Decide which of the listed FACT PACKS are needed to answer the question in the fenced content, and "
                        + "list any application, product or system names the question refers to. Choose packs, never "
                        + "answer the question. Names go in `entities` exactly as the person wrote them.",
                factLines(codesWithLabels(), history),
                Map.of("user_question", question), "RECORD",
                "{\"packs\": [<one or more codes from the list>], \"entities\": [<zero or more names as written>]}", 300);
        if (out instanceof ModelNarrator.Structured structured) {
            Set<String> chosen = new LinkedHashSet<>();
            if (structured.json().get("packs") instanceof List<?> list) {
                for (Object o : list) {
                    String code = String.valueOf(o).strip().toUpperCase(Locale.ROOT);
                    if (codes.contains(code)) {
                        chosen.add(code);
                    }
                }
            }
            List<String> entities = new ArrayList<>();
            if (structured.json().get("entities") instanceof List<?> list) {
                for (Object o : list) {
                    String name = String.valueOf(o).strip();
                    // A "name" long enough to be a sentence is the model answering rather than routing.
                    if (name.length() >= 2 && name.length() <= 120) {
                        entities.add(name);
                    }
                }
            }
            // The rules' packs are added, never replaced: the two disagree in the direction of asking
            // for one query too many, which costs a query, where the other direction costs an answer.
            //
            // But not without a ceiling. Measured on twenty manager questions: the two answers that
            // failed their grounding checks were the two that retrieved the most — seven packs and
            // fifty-eight facts for "where should I invest next quarter", eight packs for "what do I
            // report to the board" — and the model answered both without citing anything, which is what
            // a model does when it is handed everything and asked to choose. The keyword match is the
            // stronger signal because it is a word the person actually typed, so it is kept first.
            Set<String> merged = new LinkedHashSet<>(byRules);
            merged.addAll(chosen);
            if (!merged.isEmpty()) {
                return new Routing(cap(merged), entities, true);
            }
        }
        // The ceiling applies to the rules alone as well: a question that says every word — and people
        // do write those — matched twelve packs, which is the filing cabinet this exists to prevent.
        return new Routing(cap(byRules.isEmpty() ? defaultPacks() : byRules), List.of(), false);
    }

    /** At most {@link #MAX_PACKS}, keeping the order they were chosen in — the strongest signal first. */
    private static Set<String> cap(Set<String> packs) {
        return packs.size() <= MAX_PACKS ? packs
                : new LinkedHashSet<>(List.copyOf(packs).subList(0, MAX_PACKS));
    }

    /** The general question, when nothing narrower matched. */
    private static Set<String> defaultPacks() {
        return new LinkedHashSet<>(List.of("POSTURE", "COVERAGE", "SLA"));
    }

    static Set<String> keywordPacks(String question) {
        String text = fold(question);
        Set<String> chosen = new LinkedHashSet<>();
        for (Pack p : PACKS) {
            for (String keyword : p.keywords()) {
                if (text.contains(keyword)) {
                    chosen.add(p.code());
                    break;
                }
            }
        }
        return chosen;
    }

    /**
     * Lower-cased, diacritics removed, {@code đ} folded to {@code d}, punctuation to spaces.
     *
     * <p>Vietnamese is the first target locale and people type it without tone marks — "lo hong" for
     * "lỗ hổng" — so a matcher that only sees the accented form matches half of what is typed.
     */
    static String fold(String text) {
        String lower = (text == null ? "" : text).toLowerCase(Locale.ROOT).replace('đ', 'd');
        String stripped = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return " " + stripped.replaceAll("[^a-z0-9]+", " ").strip() + " ";
    }

    private static List<String> codesWithLabels() {
        List<String> lines = new ArrayList<>();
        for (Pack p : PACKS) {
            lines.add("pack " + p.code() + ": " + p.label());
        }
        return lines;
    }

    private static List<String> factLines(List<String> packs, List<String> history) {
        List<String> lines = new ArrayList<>(packs);
        if (!history.isEmpty()) {
            lines.add("earlier in this conversation the person asked about: " + String.join(" | ", history));
        }
        return ModelNarrator.facts(lines.toArray(new String[0]));
    }

    // ==============================================================================================
    // Retrieval
    // ==============================================================================================

    /** @param withheld packs the question needed and the caller may not read (PRD-AIC-048) */
    record Retrieval(List<Fact> facts, List<String> used, List<String> withheld, Map<String, String> summaries) {
    }

    /** An application the question was about. */
    record Application(UUID id, String name, String criticality, String exposure, String orgName,
            String reviewStatus, String lastReview, String nextDue, Integer intervalMonths, long reviewCount) {
    }

    private Retrieval gather(Connection c, Principal principal, Routing routing, List<Application> applications,
            String question) throws SQLException {
        Numbering facts = new Numbering();
        List<String> used = new ArrayList<>();
        List<String> withheld = new ArrayList<>();
        Map<String, String> summaries = new LinkedHashMap<>();

        facts.add("scope of every fact below: the part of the organization this person may see, and nothing outside it", null);
        for (Pack pack : PACKS) {
            if (!routing.packs().contains(pack.code())) {
                continue;
            }
            if (!pack.permission().isEmpty() && !principal.holds(pack.permission())) {
                withheld.add(pack.label() + " — needs " + pack.permission());
                continue;
            }
            int before = facts.size();
            String summary = switch (pack.code()) {
                case "POSTURE" -> posture(c, principal, facts);
                case "APPLICATIONS" -> applications(c, principal, facts, applications);
                case "COVERAGE" -> coverage(c, principal, facts);
                case "SLA" -> serviceLevels(c, principal, facts);
                case "REQUESTS" -> requests(c, principal, facts);
                case "WORKLOAD" -> workload(c, principal, facts, withheld);
                case "ORGANIZATIONS" -> organizations(principal, facts);
                case "EXCEPTIONS" -> exceptions(c, principal, facts);
                case "TOP_FINDINGS" -> topFindings(principal, facts);
                case "TREND" -> trend(c, principal, facts);
                case "ASSESS_NEXT" -> assessNext(c, principal, facts);
                case "PLAN" -> plan(c, principal, facts);
                case "HOWTO" -> howTo(facts, question);
                case "API" -> api(principal, facts, question);
                case "ACCESS" -> access(c, principal, facts, question, routing.entities(), withheld);
                case "SBOM" -> sbom(c, principal, facts, question);
                default -> null;
            };
            if (facts.size() > before) {
                used.add(pack.code());
                if (summary != null) {
                    summaries.put(pack.code(), summary);
                }
            }
        }
        if (used.isEmpty() && withheld.isEmpty()) {
            // Nothing matched and nothing was refused: answer the general question rather than nothing.
            posture(c, principal, facts);
            used.add("POSTURE");
        }
        return new Retrieval(facts.list(), used, withheld, summaries);
    }

    // ------------------------------------------------------------------------------------------
    // Pack: POSTURE
    // ------------------------------------------------------------------------------------------

    private String posture(Connection c, Principal principal, Numbering facts) throws SQLException {
        long open = 0;
        long serious = 0;
        StringBuilder bySeverity = new StringBuilder();
        try (PreparedStatement s = c.prepareStatement("""
                SELECT coalesce(sl.code, 'UNRATED'), coalesce(sl.ordinal, 99),
                       count(*) FILTER (WHERE f.state = 'OPEN'),
                       count(*) FILTER (WHERE f.state = 'OPEN' AND f.first_detected_at < now() - interval '90 days'),
                       count(*) FILTER (WHERE f.state <> 'OPEN')
                  FROM finding f
                  LEFT JOIN severity_level sl ON sl.id = coalesce(f.effective_severity_id, f.reported_severity_id)
                 WHERE f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                 GROUP BY 1, 2
                 ORDER BY 2
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    // The tenant's own severity codes (ADR-027): the copilot never says "critical"
                    // unless the tenant calls a level that.
                    facts.add("open findings at severity " + r.getString(1) + ": " + r.getLong(3)
                            + " (of which " + r.getLong(4) + " open more than 90 days); closed at this severity: " + r.getLong(5),
                            "/vulnerabilities?severity=" + r.getString(1));
                    open += r.getLong(3);
                    if (r.getInt(2) <= 2) {
                        serious += r.getLong(3);
                    }
                    if (r.getLong(3) > 0) {
                        bySeverity.append(bySeverity.isEmpty() ? "" : ", ").append(r.getLong(3)).append(" ").append(r.getString(1));
                    }
                }
            }
        }
        try (PreparedStatement s = c.prepareStatement("""
                SELECT count(*) FILTER (WHERE f.state = 'OPEN' AND f.assignee_id IS NULL),
                       count(*) FILTER (WHERE f.state = 'OPEN' AND f.remediation_claimed_at IS NOT NULL),
                       count(*) FILTER (WHERE f.state = 'OPEN' AND f.accepted_under_exception_id IS NOT NULL),
                       count(*) FILTER (WHERE f.closed_at > now() - interval '30 days'),
                       count(*) FILTER (WHERE f.state = 'OPEN' AND tgt.exposure_declared = 'INTERNET_PUBLIC')
                  FROM finding f
                  LEFT JOIN LATERAL (SELECT a.exposure_declared FROM assessment_request_scope_asset sa
                                       JOIN asset a ON a.id = sa.asset_id
                                      WHERE sa.request_id = f.discovered_in_request_id
                                      ORDER BY a.exposure_declared LIMIT 1) tgt ON true
                 WHERE f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    facts.add("open findings with nobody assigned: " + r.getLong(1)
                            + "; open with a fix claimed but not yet verified: " + r.getLong(2)
                            + "; open under an accepted risk: " + r.getLong(3)
                            + "; open on an internet-facing target: " + r.getLong(5)
                            + "; closed in the last 30 days: " + r.getLong(4), "/vulnerabilities");
                }
            }
        }
        return open == 0
                ? "No finding is open in the part of the estate this person can see."
                : open + " findings are open (" + bySeverity + "), " + serious + " of them at the two most severe levels.";
    }

    // ------------------------------------------------------------------------------------------
    // Pack: APPLICATIONS
    // ------------------------------------------------------------------------------------------

    private String applications(Connection c, Principal principal, Numbering facts, List<Application> named)
            throws SQLException {
        List<Application> subjects = named;
        if (subjects.isEmpty()) {
            // No application named: answer about the ones carrying the most, which is what "do our
            // products carry any risk" is asking.
            subjects = mostExposed(c, principal, 5);
            if (subjects.isEmpty()) {
                facts.add("no application is registered in this person's part of the organization, so there is nothing to "
                        + "report on — an empty inventory is a coverage finding, not a clean estate", "/applications");
                return "No application is registered in this scope.";
            }
            facts.add("no application was named in the question, so the " + subjects.size()
                    + " carrying the most open weaknesses are described below", "/applications");
        }
        // ONE query for every application named, not one each.
        //
        // The first version ran the severity breakdown per application and took 6.5 seconds of database
        // time for five applications before the model was even called; the same question now costs one
        // round trip. The `OR discovered_in_request_id IN (...)` half it carried was redundant as well:
        // `asset_finding_link` is a view that already unions the direct impact rows with the
        // request-scope path, so the clause was a second copy of the view's own second branch.
        Map<UUID, StringBuilder> counts = new LinkedHashMap<>();
        Map<UUID, Long> open = new LinkedHashMap<>();
        List<UUID> ids = new ArrayList<>();
        for (Application app : subjects) {
            ids.add(app.id());
            counts.put(app.id(), new StringBuilder());
            open.put(app.id(), 0L);
        }
        try (PreparedStatement s = c.prepareStatement("""
                WITH family AS (
                    SELECT a.id AS root_id, a.id AS member_id FROM asset a WHERE a.id = ANY (?)
                    UNION
                    SELECT cc.root_id, cc.asset_id FROM asset_composition cc WHERE cc.root_id = ANY (?)
                )
                SELECT fam.root_id, coalesce(sl.code, 'UNRATED'), coalesce(sl.ordinal, 99),
                       count(DISTINCT f.id) FILTER (WHERE f.state = 'OPEN')
                  FROM family fam
                  JOIN asset_finding_link l ON l.asset_id = fam.member_id
                  JOIN finding f ON f.id = l.finding_id
                  LEFT JOIN severity_level sl ON sl.id = coalesce(f.effective_severity_id, f.reported_severity_id)
                 WHERE f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                 GROUP BY 1, 2, 3
                 ORDER BY 1, 3
                """)) {
            java.sql.Array assets = c.createArrayOf("uuid", ids.toArray(new UUID[0]));
            s.setArray(1, assets);
            s.setArray(2, assets);
            s.setArray(3, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    UUID root = r.getObject(1, UUID.class);
                    if (r.getLong(4) > 0 && counts.containsKey(root)) {
                        StringBuilder line = counts.get(root);
                        line.append(line.isEmpty() ? "" : ", ").append(r.getLong(4)).append(" ").append(r.getString(2));
                        open.merge(root, r.getLong(4), Long::sum);
                    }
                }
            }
        }
        StringBuilder summary = new StringBuilder();
        for (Application app : subjects) {
            String link = "/applications/" + app.id();
            long total = open.getOrDefault(app.id(), 0L);
            facts.add("application \"" + app.name() + "\": criticality " + (app.criticality() == null ? "not set" : app.criticality())
                    + ", exposure " + (app.exposure() == null ? "not declared" : app.exposure())
                    + ", owned by " + (app.orgName() == null ? "no organization node" : app.orgName()), link);
            facts.add("application \"" + app.name() + "\" review state: " + reviewSentence(app), link);
            facts.add("application \"" + app.name() + "\" open findings: "
                    + (total == 0 ? "none recorded" : counts.get(app.id()).toString())
                    + (total == 0 && app.reviewCount() == 0
                            ? " — and it has never been assessed, so none recorded is not the same as none present"
                            : ""),
                    link);
            summary.append(summary.isEmpty() ? "" : " ")
                    .append(app.name()).append(": ").append(total == 0 ? "no open finding recorded" : total + " open")
                    .append(", ").append(reviewSentence(app)).append(".");
        }
        return summary.toString();
    }

    private static String reviewSentence(Application app) {
        String status = app.reviewStatus() == null ? "NO_OBLIGATION" : app.reviewStatus();
        return switch (status) {
            case "NEVER" -> "never assessed"
                    + (app.intervalMonths() == null ? " and no review interval applies to it" : ", though its tier is owed one every " + app.intervalMonths() + " months");
            case "OVERDUE" -> "last assessed " + app.lastReview() + ", overdue since " + app.nextDue()
                    + " against a " + app.intervalMonths() + "-month interval";
            case "DUE_SOON" -> "last assessed " + app.lastReview() + ", next due " + app.nextDue();
            case "CURRENT" -> "last assessed " + app.lastReview() + ", within its " + app.intervalMonths() + "-month interval, next due " + app.nextDue();
            default -> app.reviewCount() > 0
                    ? "last assessed " + app.lastReview() + "; no review interval applies, so nothing is owed"
                    : "never assessed, and no review interval applies to it — which usually means no criticality is set";
        };
    }

    private List<Application> mostExposed(Connection c, Principal principal, int limit) throws SQLException {
        List<Application> out = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(applicationSelect("""
                 ORDER BY (SELECT count(*) FROM finding f
                            WHERE f.state = 'OPEN'
                              AND EXISTS (SELECT 1 FROM asset_finding_link l WHERE l.finding_id = f.id AND l.asset_id = a.id)) DESC,
                          CASE WHEN c.full_review_status IN ('OVERDUE', 'NEVER') THEN 0 ELSE 1 END,
                          a.display_name
                 LIMIT ?
                """))) {
            s.setArray(1, scopeArray(c, principal));
            s.setInt(2, limit);
            read(s, out);
        }
        return out;
    }

    /**
     * The applications the question is about.
     *
     * <p>Three routes, in order: a name the router lifted out of the question, a name of the caller's own
     * applications that appears in the question text, and — for "this application" with neither — the one
     * the conversation was already about. All three run in SQL under the scope predicate, so an
     * application outside the caller's reach cannot be resolved by any of them.
     */
    private List<Application> resolveApplications(Connection c, Principal principal, String question,
            List<String> hints, String priorFocus) throws SQLException {
        if (!principal.holds("ast.asset.read")) {
            return List.of();
        }
        List<Application> out = new ArrayList<>();
        // Route 2 first: it is exact. The question contains the application's own name.
        try (PreparedStatement s = c.prepareStatement(applicationSelect(
                " AND position(lower(a.display_name) in lower(?)) > 0 AND length(a.display_name) >= 3 "
                        + " ORDER BY length(a.display_name) DESC LIMIT 5"))) {
            s.setArray(1, scopeArray(c, principal));
            s.setString(2, question);
            read(s, out);
        }
        // Route 1: what the router suggested looking for, resolved here and never taken on trust.
        for (String hint : hints) {
            if (out.size() >= 5) {
                break;
            }
            if (hint.strip().length() < 2) {
                continue;
            }
            try (PreparedStatement s = c.prepareStatement(applicationSelect(
                    " AND a.display_name ILIKE ? ORDER BY length(a.display_name) LIMIT 3"))) {
                s.setArray(1, scopeArray(c, principal));
                s.setString(2, "%" + hint.strip().replace("%", "").replace("_", "") + "%");
                List<Application> found = new ArrayList<>();
                read(s, found);
                for (Application app : found) {
                    if (out.stream().noneMatch(a -> a.id().equals(app.id()))) {
                        out.add(app);
                    }
                }
            }
        }
        // Route 3: "this application", with the conversation already on one.
        if (out.isEmpty() && priorFocus != null && demonstrative(question)) {
            try (PreparedStatement s = c.prepareStatement(applicationSelect(" AND a.id = ? LIMIT 1"))) {
                s.setArray(1, scopeArray(c, principal));
                s.setObject(2, UUID.fromString(priorFocus));
                read(s, out);
            }
        }
        return out;
    }

    /** "this", "it", "ứng dụng này", "nó" — a question whose subject is the last one. */
    static boolean demonstrative(String question) {
        String text = fold(question);
        for (String marker : List.of(" nay ", " no ", " do ", " this ", " it ", " its ", " that ", " ay ")) {
            if (text.contains(marker)) {
                return true;
            }
        }
        // A question with no subject at all — "has it been assessed?" shortened to "assessed?" — is
        // also about whatever was last discussed.
        return text.strip().split(" ").length <= 4;
    }

    /**
     * The application projection, with the caller's scope predicate always present and the varying part
     * substituted rather than concatenated — a closing text-block delimiter followed by {@code +} joins
     * on stripped whitespace, which is how a planning query once became {@code ANDw.ends_on}.
     */
    private static String applicationSelect(String tail) {
        return """
                SELECT a.id, a.display_name, ct.code, a.exposure_declared, n.name,
                       c.full_review_status, to_char(c.last_full_review_at, 'YYYY-MM-DD'),
                       to_char(c.next_full_review_due, 'YYYY-MM-DD'), c.interval_months,
                       coalesce(c.full_review_count, 0) + coalesce(c.attested_review_count, 0)
                  FROM asset a
                  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION'
                  LEFT JOIN criticality_tier ct ON ct.id = a.criticality_tier_id
                  LEFT JOIN org_node n ON n.id = a.owning_node_id
                  LEFT JOIN application_review_cadence c ON c.asset_id = a.id
                 WHERE a.lifecycle_state <> 'RETIRED'
                   AND a.owning_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                   %s
                """.formatted(tail);
    }

    private static void read(PreparedStatement s, List<Application> out) throws SQLException {
        try (ResultSet r = s.executeQuery()) {
            while (r.next()) {
                out.add(new Application(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6), r.getString(7), r.getString(8),
                        (Integer) r.getObject(9), r.getLong(10)));
            }
        }
    }

    private static String firstApplicationName(Connection c, Principal principal) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(applicationSelect(" ORDER BY a.display_name LIMIT 1"))) {
            s.setArray(1, scopeArray(c, principal));
            List<Application> out = new ArrayList<>();
            read(s, out);
            return out.isEmpty() ? null : out.get(0).name();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pack: COVERAGE
    // ------------------------------------------------------------------------------------------

    private String coverage(Connection c, Principal principal, Numbering facts) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                SELECT count(*),
                       count(*) FILTER (WHERE c.full_review_status = 'NEVER'),
                       count(*) FILTER (WHERE c.full_review_status = 'OVERDUE'),
                       count(*) FILTER (WHERE c.full_review_status = 'DUE_SOON'),
                       count(*) FILTER (WHERE c.full_review_status = 'CURRENT'),
                       count(*) FILTER (WHERE c.full_review_status = 'NO_OBLIGATION'),
                       count(*) FILTER (WHERE a.criticality_tier_id IS NULL)
                  FROM asset a
                  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION'
                  LEFT JOIN application_review_cadence c ON c.asset_id = a.id
                 WHERE a.lifecycle_state <> 'RETIRED'
                   AND a.owning_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    facts.add("assessment coverage: " + r.getLong(1) + " applications in scope; "
                            + r.getLong(2) + " never assessed; " + r.getLong(3) + " overdue for their periodic review; "
                            + r.getLong(4) + " due soon; " + r.getLong(5) + " within their interval; "
                            + r.getLong(6) + " carry no review obligation, of which " + r.getLong(7)
                            + " because no criticality tier is set on them", "/planning");
                    return r.getLong(2) + " of " + r.getLong(1) + " applications have never been assessed and "
                            + r.getLong(3) + " are overdue.";
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Pack: SLA
    // ------------------------------------------------------------------------------------------

    private String serviceLevels(Connection c, Principal principal, Numbering facts) throws SQLException {
        long breached = 0;
        long dueSoon = 0;
        try (PreparedStatement s = c.prepareStatement("""
                SELECT count(*) FILTER (WHERE k.breached_at IS NOT NULL AND k.resolved_at IS NULL),
                       count(*) FILTER (WHERE k.resolved_at IS NULL AND k.breached_at IS NULL AND k.due_at < now() + interval '7 days'),
                       count(*) FILTER (WHERE k.resolved_at IS NULL)
                  FROM service_level_clock k
                  JOIN finding f ON f.id = k.subject_id AND k.subject_kind = 'FINDING'
                 WHERE f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    breached = r.getLong(1);
                    dueSoon = r.getLong(2);
                    facts.add("remediation commitments on findings: " + r.getLong(1) + " breached and still open, "
                            + r.getLong(2) + " falling due within seven days, " + r.getLong(3) + " clocks running in total"
                            + (r.getLong(3) == 0 ? " — no service level policy has been applied to findings in this scope, "
                                    + "so nothing here has a deadline to miss" : ""), "/vulnerabilities");
                }
            }
        }
        // Requests against their own due date. Terminal states come from the tenant's workflow catalogue
        // (ADR-027) rather than a list in this file: a tenant that renames CLOSED would otherwise have
        // every closed request counted as late forever.
        try (PreparedStatement s = c.prepareStatement("""
                SELECT count(*) FILTER (WHERE r.due_at < now()),
                       count(*) FILTER (WHERE r.due_at >= now() AND r.due_at < now() + interval '7 days'),
                       count(*) FILTER (WHERE r.due_at IS NULL)
                  FROM assessment_request r
                 WHERE r.requested_org_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                   AND r.state NOT IN (SELECT code FROM workflow_state GROUP BY code HAVING bool_and(category = 'TERMINAL'))
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    facts.add("assessment requests still open: " + r.getLong(1) + " past their due date, "
                            + r.getLong(2) + " due within seven days, " + r.getLong(3) + " with no due date recorded",
                            "/board");
                    breached += r.getLong(1);
                    dueSoon += r.getLong(2);
                }
            }
        }
        // Named, not just counted: "which ones" is the question behind "how many".
        try (PreparedStatement s = c.prepareStatement("""
                SELECT r.request_code, r.title, to_char(r.due_at, 'YYYY-MM-DD'),
                       (date_part('day', now() - r.due_at))::int, r.state, r.id::text
                  FROM assessment_request r
                 WHERE r.requested_org_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                   AND r.state NOT IN (SELECT code FROM workflow_state GROUP BY code HAVING bool_and(category = 'TERMINAL'))
                   AND r.due_at IS NOT NULL AND r.due_at < now() + interval '7 days'
                 ORDER BY r.due_at
                 LIMIT 10
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    int late = r.getInt(4);
                    facts.add("request " + r.getString(1) + " \"" + safe(r.getString(2)) + "\" is in state " + r.getString(5)
                            + " and was due " + r.getString(3) + (late > 0 ? ", " + late + " days ago" : ", within the next week"),
                            "/board/" + r.getString(6));
                }
            }
        }
        return breached == 0 && dueSoon == 0
                ? "Nothing is past a commitment and nothing falls due within seven days."
                : breached + " items are past their commitment and " + dueSoon + " fall due within seven days.";
    }

    // ------------------------------------------------------------------------------------------
    // Pack: REQUESTS
    // ------------------------------------------------------------------------------------------

    private String requests(Connection c, Principal principal, Numbering facts) throws SQLException {
        StringBuilder states = new StringBuilder();
        long open = 0;
        try (PreparedStatement s = c.prepareStatement("""
                SELECT r.state, count(*), max(w.category)
                  FROM assessment_request r
                  LEFT JOIN (SELECT code, max(category) AS category FROM workflow_state GROUP BY code) w ON w.code = r.state
                 WHERE r.requested_org_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                 GROUP BY r.state
                 ORDER BY count(*) DESC
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    facts.add("assessment requests in state " + r.getString(1) + " (" + r.getString(3) + "): " + r.getLong(2),
                            "/board?state=" + r.getString(1));
                    if (!"TERMINAL".equals(r.getString(3))) {
                        open += r.getLong(2);
                        states.append(states.isEmpty() ? "" : ", ").append(r.getLong(2)).append(" ").append(r.getString(1));
                    }
                }
            }
        }
        try (PreparedStatement s = c.prepareStatement("""
                SELECT r.request_code, safe.title, to_char(r.updated_at, 'YYYY-MM-DD'),
                       (date_part('day', now() - r.updated_at))::int, r.state, r.id::text
                  FROM assessment_request r
                  CROSS JOIN LATERAL (SELECT r.title AS title) safe
                 WHERE r.requested_org_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                   AND r.state NOT IN (SELECT code FROM workflow_state GROUP BY code HAVING bool_and(category = 'TERMINAL'))
                 ORDER BY r.updated_at
                 LIMIT 8
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    facts.add("request " + r.getString(1) + " \"" + safe(r.getString(2)) + "\" has sat in state "
                            + r.getString(5) + " since " + r.getString(3) + " (" + r.getInt(4) + " days)",
                            "/board/" + r.getString(6));
                }
            }
        }
        return open == 0 ? "No assessment request is open." : open + " requests are open (" + states + ").";
    }

    // ------------------------------------------------------------------------------------------
    // Pack: WORKLOAD
    // ------------------------------------------------------------------------------------------

    /**
     * Team load always; per-person load only behind {@code cap.member.read.all}.
     *
     * <p>{@code PRD-CAP-014} requires the statement that these are capacity figures and not a
     * performance measure to sit <b>where the measures are presented</b>. In a conversation that is the
     * answer itself, so the statement is a fact the answer is expected to carry — and the ordering is by
     * name, because a list sorted by volume is a ranking whatever sentence accompanies it.
     */
    private String workload(Connection c, Principal principal, Numbering facts, List<String> withheld)
            throws SQLException {
        facts.add("these load figures are for capacity planning and must not be read as a performance measure or a "
                + "ranking of people (PRD-CAP-014); they are listed by name, not by volume", "/workload");
        // Said BEFORE the per-person rows, and on a proportion rather than on equality.
        //
        // Measured: asked whether the security team was overloaded, the answer was "there is spare
        // capacity — every assessor has zero findings assigned" over an estate where 296 of 304 open
        // findings had no assignee at all. Zero assigned is unmeasured load, not light load (PP-1). The
        // first version of this guard tested unassigned == open and never fired, because eight findings
        // were assigned to somebody outside the listing.
        try (PreparedStatement s = c.prepareStatement("""
                SELECT count(*) FILTER (WHERE f.state = 'OPEN'),
                       count(*) FILTER (WHERE f.state = 'OPEN' AND f.assignee_id IS NULL)
                  FROM finding f
                 WHERE f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                if (r.next() && r.getLong(1) > 0 && r.getLong(2) * 10 >= r.getLong(1) * 9) {
                    facts.add("READ THE FIGURES BELOW WITH THIS: " + r.getLong(2) + " of the " + r.getLong(1)
                            + " open findings in this scope have no assignee at all. The per-team and per-person "
                            + "counts below therefore measure almost nothing, and a low count must NOT be read as "
                            + "spare capacity — it is work nobody has been given. The load here is unmeasured.",
                            "/workload");
                }
            }
        }
        StringBuilder teams = new StringBuilder();
        try (PreparedStatement s = c.prepareStatement("""
                SELECT tm.name,
                       count(DISTINCT mem.principal_id) FILTER (WHERE mem.removed_at IS NULL),
                       count(DISTINCT f.id) FILTER (WHERE f.state = 'OPEN')
                  FROM assessor_team tm
                  LEFT JOIN assessor_team_member mem ON mem.team_id = tm.id
                  LEFT JOIN finding f ON f.assignee_id = mem.principal_id AND mem.removed_at IS NULL
                       AND f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                 WHERE tm.lifecycle_state = 'ACTIVE'
                 GROUP BY tm.id, tm.name
                 ORDER BY tm.name
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    facts.add("assessor team \"" + safe(r.getString(1)) + "\": " + r.getLong(2) + " members, "
                            + r.getLong(3) + " open findings assigned across them", "/workload");
                    teams.append(teams.isEmpty() ? "" : "; ").append(r.getString(1)).append(" ")
                            .append(r.getLong(2)).append(" members / ").append(r.getLong(3)).append(" open");
                }
            }
        }
        try (PreparedStatement s = c.prepareStatement("""
                SELECT w.name, count(*)
                  FROM assessment_plan_window w2
                  JOIN assessor_team w ON w.id = w2.team_id
                 WHERE w2.state = 'PLANNED' AND w2.ends_on >= current_date
                 GROUP BY w.name ORDER BY w.name
                """)) {
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    facts.add("assessor team \"" + safe(r.getString(1)) + "\" has " + r.getLong(2)
                            + " assessment windows planned ahead", "/planning");
                }
            }
        }
        if (!principal.holds(WORKLOAD_INDIVIDUAL)) {
            // Absent, and said to be absent. ADR-047 keeps restricted fields out of the representation;
            // PRD-AIC-048 says an unavailable part is named rather than silently dropped.
            withheld.add("Load per individual person — needs " + WORKLOAD_INDIVIDUAL);
            return teams.isEmpty() ? "No assessor team is configured." : "By team: " + teams + ".";
        }
        try (PreparedStatement s = c.prepareStatement("""
                SELECT coalesce(p.display_name, p.username), coalesce(tm.name, 'no team'),
                       count(DISTINCT f.id) FILTER (WHERE f.state = 'OPEN'),
                       count(DISTINCT f.id) FILTER (WHERE f.state = 'OPEN' AND sl.ordinal <= 2)
                  FROM principal p
                  LEFT JOIN assessor_team_member mem ON mem.principal_id = p.id AND mem.removed_at IS NULL
                  LEFT JOIN assessor_team tm ON tm.id = mem.team_id
                  LEFT JOIN finding f ON f.assignee_id = p.id
                       AND f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                  LEFT JOIN severity_level sl ON sl.id = coalesce(f.effective_severity_id, f.reported_severity_id)
                 WHERE p.lifecycle_state = 'ACTIVE' AND p.kind = 'HUMAN'
                   AND (mem.id IS NOT NULL OR EXISTS (SELECT 1 FROM finding f2 WHERE f2.assignee_id = p.id))
                 GROUP BY p.id, p.display_name, p.username, tm.name
                 ORDER BY coalesce(p.display_name, p.username)
                 LIMIT 40
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    facts.add("assessor " + safe(r.getString(1)) + " (" + safe(r.getString(2)) + "): "
                            + r.getLong(3) + " open findings assigned, " + r.getLong(4) + " of them at the two most severe levels",
                            "/workload");
                }
            }
        }
        return teams.isEmpty() ? "No assessor team is configured." : "By team: " + teams + ".";
    }

    // ------------------------------------------------------------------------------------------
    // Pack: ORGANIZATIONS
    // ------------------------------------------------------------------------------------------

    private String organizations(Principal principal, Numbering facts) throws SQLException {
        List<OverviewInsights.Posture> rows = insights.posture(principal);
        if (rows.isEmpty()) {
            return null;
        }
        StringBuilder summary = new StringBuilder();
        for (OverviewInsights.Posture row : rows) {
            facts.add("organization \"" + safe(row.name()) + "\": " + row.applications() + " applications, "
                    + row.neverAssessed() + " never assessed, " + row.openNow() + " open findings ("
                    + row.openBefore() + " ninety days ago), " + row.serious() + " at the two most severe levels, "
                    + row.exposedSerious() + " of those on an internet-facing business-critical target, "
                    + row.breached() + " past a remediation commitment, " + row.reviewsDue() + " reviews owed of which "
                    + row.reviewsUnplanned() + " have no window planned; last assessed "
                    + (row.lastAssessedAt() == null ? "never" : row.lastAssessedAt()),
                    "/applications?node=" + row.nodeId());
            summary.append(summary.isEmpty() ? "" : " ").append(row.name()).append(": ")
                    .append(row.openNow()).append(" open, ").append(row.neverAssessed()).append(" never assessed.");
        }
        return summary.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Pack: EXCEPTIONS
    // ------------------------------------------------------------------------------------------

    private String exceptions(Connection c, Principal principal, Numbering facts) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                SELECT x.state, count(*), count(*) FILTER (WHERE x.expires_at < now() + interval '30 days'),
                       to_char(min(x.expires_at) FILTER (WHERE x.expires_at > now()), 'YYYY-MM-DD')
                  FROM risk_exception x
                 WHERE x.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                 GROUP BY x.state
                 ORDER BY x.state
                """)) {
            s.setArray(1, scopeArray(c, principal));
            long active = 0;
            long expiring = 0;
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    facts.add("accepted risks in state " + r.getString(1) + ": " + r.getLong(2) + ", of which "
                            + r.getLong(3) + " expire within 30 days"
                            + (r.getString(4) == null ? "" : "; the next expiry is " + r.getString(4)),
                            "/vulnerabilities?accepted=true");
                    if ("ACTIVE".equals(r.getString(1))) {
                        active = r.getLong(2);
                        expiring = r.getLong(3);
                    }
                }
            }
            return active == 0 ? "No accepted risk is active."
                    : active + " accepted risks are active, " + expiring + " expiring within 30 days.";
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pack: TOP_FINDINGS
    // ------------------------------------------------------------------------------------------

    private String topFindings(Principal principal, Numbering facts) throws SQLException {
        List<RiskScoring.Score> scores = scoring.topFindings(principal, 8);
        if (scores.isEmpty()) {
            return null;
        }
        for (RiskScoring.Score score : scores) {
            facts.add("open finding \"" + safe(score.title()) + "\": score " + score.score() + " (" + score.scoreBand()
                    + "), severity " + score.severity() + ", exposure " + score.exposure()
                    + ", target criticality " + score.criticality(),
                    score.requestId() == null ? "/pipeline/findings/" + score.findingId()
                            : "/board/" + score.requestId() + "/findings/" + score.findingId());
        }
        return "The highest-scoring open finding is \"" + safe(scores.get(0).title()) + "\" at " + scores.get(0).score() + ".";
    }

    // ------------------------------------------------------------------------------------------
    // Pack: TREND
    // ------------------------------------------------------------------------------------------

    /**
     * Direction, which is the question an executive asks second and often first.
     *
     * <p>"Is it getting better" and "how long does remediation take" were both answered with an honest
     * refusal — the facts carried counts and no history — while the platform held both: the backlog
     * ninety days ago is derivable from the two dates every finding already has, and the time to close
     * is the difference between them. A refusal over data the platform holds is the same failure as an
     * invented answer wearing better manners.
     *
     * <p>The months are calendar months and are labelled, so "compared with last month" is a row the
     * answer can point at rather than an interval it has to infer. Where nothing has closed, the
     * remediation figures are absent rather than zero: a median over no closures is not a fast team
     * ({@code PRD-ASM-023}).
     */
    private String trend(Connection c, Principal principal, Numbering facts) throws SQLException {
        long now = 0;
        long before = 0;
        try (PreparedStatement s = c.prepareStatement("""
                SELECT count(*) FILTER (WHERE f.state = 'OPEN'),
                       count(*) FILTER (WHERE f.first_detected_at < now() - interval '90 days'
                                          AND (f.closed_at IS NULL OR f.closed_at >= now() - interval '90 days')),
                       count(*) FILTER (WHERE f.closed_at > now() - interval '30 days'),
                       count(*) FILTER (WHERE f.closed_at > now() - interval '90 days'),
                       count(*) FILTER (WHERE f.first_detected_at > now() - interval '30 days'),
                       count(*) FILTER (WHERE f.first_detected_at > now() - interval '90 days')
                  FROM finding f
                 WHERE f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    now = r.getLong(1);
                    before = r.getLong(2);
                    long delta = now - before;
                    facts.add("backlog direction: " + now + " findings are open today against " + before
                            + " ninety days ago — " + (delta > 0 ? "a rise of " + delta
                                    : delta < 0 ? "a fall of " + Math.abs(delta) : "unchanged")
                            + ". Opened in the last 30 days: " + r.getLong(5) + "; in the last 90: " + r.getLong(6)
                            + ". Closed in the last 30 days: " + r.getLong(3) + "; in the last 90: " + r.getLong(4),
                            "/vulnerabilities");
                }
            }
        }
        // How long remediation takes, over what has actually closed. Absent rather than zero where
        // nothing has: a median over no closures is not a fast team.
        try (PreparedStatement s = c.prepareStatement("""
                SELECT count(*),
                       round(avg(extract(epoch FROM (f.closed_at - f.first_detected_at)) / 86400)::numeric, 1),
                       round((percentile_cont(0.5) WITHIN GROUP (
                              ORDER BY extract(epoch FROM (f.closed_at - f.first_detected_at)) / 86400))::numeric, 1),
                       round((percentile_cont(0.9) WITHIN GROUP (
                              ORDER BY extract(epoch FROM (f.closed_at - f.first_detected_at)) / 86400))::numeric, 1)
                  FROM finding f
                 WHERE f.closed_at IS NOT NULL AND f.closed_at > now() - interval '180 days'
                   AND f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    facts.add(r.getLong(1) == 0
                            ? "time to remediate: nothing has been closed in the last 180 days in this scope, so there "
                                    + "is no elapsed time to report — that is an absence of measurement, not a fast team"
                            : "time to remediate, over the " + r.getLong(1) + " findings closed in the last 180 days: "
                                    + "mean " + r.getBigDecimal(2) + " days, median " + r.getBigDecimal(3)
                                    + " days, 90th percentile " + r.getBigDecimal(4) + " days. The mean and the median "
                                    + "disagree where a few hard findings sit open for a long time, and both are true",
                            "/vulnerabilities");
                }
            }
        }
        try (PreparedStatement s = c.prepareStatement("""
                SELECT to_char(m.month, 'YYYY-MM'),
                       (SELECT count(*) FROM finding f
                         WHERE date_trunc('month', f.first_detected_at) = m.month
                           AND f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))),
                       (SELECT count(*) FROM finding f
                         WHERE date_trunc('month', f.closed_at) = m.month
                           AND f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?)))
                  FROM generate_series(date_trunc('month', now()) - interval '5 months',
                                       date_trunc('month', now()), interval '1 month') AS m(month)
                 ORDER BY m.month
                """)) {
            s.setArray(1, scopeArray(c, principal));
            s.setArray(2, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    facts.add("month " + r.getString(1) + ": " + r.getLong(2) + " findings opened, "
                            + r.getLong(3) + " closed", "/vulnerabilities");
                }
            }
        }
        long delta = now - before;
        return delta == 0 ? "The open backlog is unchanged against ninety days ago (" + now + ")."
                : "The open backlog is " + now + ", " + (delta > 0 ? "up " + delta : "down " + Math.abs(delta))
                        + " against ninety days ago.";
    }

    // ------------------------------------------------------------------------------------------
    // Pack: ASSESS_NEXT
    // ------------------------------------------------------------------------------------------

    /**
     * Which applications are most owed an assessment, in order, with the reason beside each.
     *
     * <h2>Why this is not the plan, and not the risk score</h2>
     *
     * <p>Asked "which application should I assess this month", the copilot had the plan (what somebody
     * intends to do) and the five applications carrying the most open findings (what has already been
     * looked at). Neither answers the question. The applications that most need assessing are the ones
     * the platform knows least about — never assessed, or assessed too long ago — weighted by what it
     * would cost to be wrong about them. The estate the platform cannot see is the estate it cannot
     * report on, and it is the part that looks clean.
     *
     * <h2>The ordering, stated rather than scored</h2>
     *
     * <p>A lexicographic ordering over recorded facts, in this sequence: <b>owed</b> (never assessed,
     * then overdue, then due soon) · <b>unplanned</b> before already planned · <b>internet-facing</b>
     * before internal · <b>criticality tier</b> · <b>open findings at the two most severe levels</b> ·
     * <b>how long it has been owed</b>.
     *
     * <p>Deliberately not a weighted score. DOC-28 owns the risk model and three of its six factors
     * have no input in this deployment; inventing a second weighted number here would produce a figure
     * that looks like that one, cannot be reconciled with it, and would be quoted as though it were.
     * An ordering can be read off the columns and disputed one clause at a time, which is what a
     * planning conversation actually needs.
     *
     * <h2>The dates are facts, so a question about a month can be answered</h2>
     *
     * <p>Each row carries the date the review falls due and today's date is stated alongside. "This
     * month" is then a comparison the answer can make and cite, rather than a period the model has to
     * guess the boundaries of.
     */
    private String assessNext(Connection c, Principal principal, Numbering facts) throws SQLException {
        facts.add("today is " + java.time.LocalDate.now() + "; the current calendar month runs to "
                + java.time.LocalDate.now().withDayOfMonth(java.time.LocalDate.now().lengthOfMonth())
                + ". Compare the due dates below against these rather than assuming a period.", null);
        facts.add("the applications below are ordered by: never assessed first, then overdue, then due soon; "
                + "within each of those, applications with no assessment window already planned come first, then "
                + "internet-facing before internal-only, then higher criticality tier, then more open findings at "
                + "the two most severe levels, then longest owed. This ordering is a composition of recorded facts "
                + "and is not the risk score — it answers what to look at, not how bad it is.", "/planning");
        int owed = 0;
        StringBuilder top = new StringBuilder();
        try (PreparedStatement s = c.prepareStatement("""
                SELECT a.display_name,
                       coalesce(cad.full_review_status, 'NO_OBLIGATION'),
                       to_char(cad.last_full_review_at, 'YYYY-MM-DD'),
                       to_char(cad.next_full_review_due, 'YYYY-MM-DD'),
                       cad.interval_months,
                       coalesce(ct.code, 'no tier set'),
                       coalesce(a.exposure_declared, 'not declared'),
                       (SELECT count(*) FROM finding f
                          LEFT JOIN severity_level sl ON sl.id = coalesce(f.effective_severity_id, f.reported_severity_id)
                         WHERE f.state = 'OPEN' AND coalesce(sl.ordinal, 99) <= 2
                           AND EXISTS (SELECT 1 FROM asset_finding_link l WHERE l.finding_id = f.id AND l.asset_id = a.id)),
                       (SELECT to_char(min(w.starts_on), 'YYYY-MM-DD') FROM assessment_plan_window w
                         WHERE w.state = 'PLANNED' AND w.ends_on >= current_date
                           AND (w.target_asset_id = a.id
                                OR w.target_asset_id IN (SELECT cc.asset_id FROM asset_composition cc WHERE cc.root_id = a.id))),
                       coalesce(n.name, 'no organization'),
                       (date_part('day', now() - cad.next_full_review_due))::int
                  FROM asset a
                  JOIN asset_type t ON t.id = a.type_id AND t.code = 'APPLICATION'
                  LEFT JOIN application_review_cadence cad ON cad.asset_id = a.id
                  LEFT JOIN criticality_tier ct ON ct.id = a.criticality_tier_id
                  LEFT JOIN org_node n ON n.id = a.owning_node_id
                 WHERE a.lifecycle_state <> 'RETIRED'
                   AND a.owning_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                   AND coalesce(cad.full_review_status, 'NO_OBLIGATION') IN ('NEVER', 'OVERDUE', 'DUE_SOON')
                 ORDER BY CASE coalesce(cad.full_review_status, '')
                              WHEN 'NEVER' THEN 0 WHEN 'OVERDUE' THEN 1 WHEN 'DUE_SOON' THEN 2 ELSE 3 END,
                          CASE WHEN EXISTS (SELECT 1 FROM assessment_plan_window w
                                             WHERE w.state = 'PLANNED' AND w.ends_on >= current_date
                                               AND (w.target_asset_id = a.id
                                                    OR w.target_asset_id IN (SELECT cc.asset_id FROM asset_composition cc
                                                                              WHERE cc.root_id = a.id))) THEN 1 ELSE 0 END,
                          CASE WHEN a.exposure_declared = 'INTERNET_PUBLIC' THEN 0 ELSE 1 END,
                          coalesce(ct.ordinal, 99),
                          8 DESC,
                          coalesce(cad.next_full_review_due, now()),
                          a.display_name
                 LIMIT 12
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    owed++;
                    String status = r.getString(2);
                    String planned = r.getString(9);
                    Integer late = (Integer) r.getObject(11);
                    facts.add("owed an assessment: \"" + safe(r.getString(1)) + "\" in " + safe(r.getString(10))
                            + " — " + ("NEVER".equals(status) ? "never assessed"
                                    : "last assessed " + r.getString(3) + ", " + ("OVERDUE".equals(status)
                                            ? "overdue since " + r.getString(4) + (late == null ? "" : " (" + late + " days)")
                                            : "due " + r.getString(4)))
                            + (r.getObject(5) == null ? ", no review interval applies" : ", interval " + r.getInt(5) + " months")
                            + ", criticality " + safe(r.getString(6)) + ", exposure " + safe(r.getString(7))
                            + ", " + r.getLong(8) + " open findings at the two most severe levels"
                            + (planned == null ? ", NO assessment window planned"
                                    : ", an assessment window is already planned from " + planned),
                            "/applications");
                    if (top.length() < 200) {
                        top.append(top.isEmpty() ? "" : ", ").append(r.getString(1));
                    }
                }
            }
        }
        if (owed == 0) {
            facts.add("no application in this scope is currently owed a periodic assessment — every one is either "
                    + "within its review interval or carries no interval at all. An application with no criticality "
                    + "tier carries no obligation, which is not the same as being up to date.", "/planning");
            return "No application is currently owed an assessment.";
        }
        return owed + " applications are owed an assessment; in order, the first are " + top + ".";
    }

    // ------------------------------------------------------------------------------------------
    // Pack: PLAN
    // ------------------------------------------------------------------------------------------

    private String plan(Connection c, Principal principal, Numbering facts) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                SELECT count(*),
                       count(*) FILTER (WHERE w.starts_on < current_date + interval '90 days'),
                       count(*) FILTER (WHERE w.team_id IS NULL AND w.assessor_principal_id IS NULL),
                       to_char(min(w.starts_on) FILTER (WHERE w.starts_on >= current_date), 'YYYY-MM-DD')
                  FROM assessment_plan_window w
                  JOIN asset a ON a.id = w.target_asset_id
                 WHERE w.state = 'PLANNED' AND w.ends_on >= current_date
                   AND a.owning_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    facts.add("assessment plan: " + r.getLong(1) + " windows planned, " + r.getLong(2)
                            + " of them starting within 90 days, " + r.getLong(3) + " with no team or person named yet"
                            + (r.getString(4) == null ? "" : "; the next window starts " + r.getString(4)), "/planning");
                }
            }
        }
        try (PreparedStatement s = c.prepareStatement("""
                SELECT a.display_name, to_char(w.starts_on, 'YYYY-MM-DD'), to_char(w.ends_on, 'YYYY-MM-DD'),
                       coalesce(tm.name, 'no team named'), coalesce(p.display_name, p.username, 'nobody named')
                  FROM assessment_plan_window w
                  JOIN asset a ON a.id = w.target_asset_id
                  LEFT JOIN assessor_team tm ON tm.id = w.team_id
                  LEFT JOIN principal p ON p.id = w.assessor_principal_id
                 WHERE w.state = 'PLANNED' AND w.ends_on >= current_date
                   AND a.owning_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                 ORDER BY w.starts_on
                 LIMIT 10
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    facts.add("planned window: \"" + safe(r.getString(1)) + "\" from " + r.getString(2) + " to "
                            + r.getString(3) + ", expected owner " + safe(r.getString(4)) + " / " + safe(r.getString(5)),
                            "/planning");
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Pack: SBOM
    // ------------------------------------------------------------------------------------------

    /**
     * What the estate depends on that is known to be vulnerable, and how much of the estate that is
     * known about at all.
     *
     * <h2>Grouped by component, not by the rows</h2>
     *
     * <p>The link table carries one row per asset per advisory: two hundred and thirty-one of them over
     * nineteen components in the estate this was measured against, most of them the same Log4Shell
     * entry repeated. Listing rows would answer "which library is vulnerable" with the same library six
     * times. The reader wants the component, its worst advisory, how many applications carry it, and
     * whether a fixed version exists.
     *
     * <h2>Fix available or not is the split that decides what happens next</h2>
     *
     * <p>A vulnerable component with a published fixed version is an upgrade somebody schedules. One
     * without is a compensating-control conversation, or an exception. Reporting them as one number
     * hides the difference between work and a decision, so both are stated.
     *
     * <h2>Direct or transitive</h2>
     *
     * <p>A direct dependency is one a team chose; a transitive one arrived with something else they
     * chose. The remediation conversation differs — one is an upgrade, the other is an upgrade of a
     * parent that may not have one — so the fact says which.
     *
     * <h2>The coverage caveat travels with the counts, always</h2>
     *
     * <p>A component vulnerability can only be matched where a bill of materials was submitted. Every
     * figure here is over the covered part of the estate, and the uncovered part is stated beside it
     * rather than left to be assumed away: an asset with no bill of materials has no matched
     * vulnerabilities and is not therefore clean (PP-1).
     */
    private String sbom(Connection c, Principal principal, Numbering facts, String question) throws SQLException {
        long covered = 0;
        long total = 0;
        try (PreparedStatement s = c.prepareStatement("""
                SELECT count(*),
                       count(*) FILTER (WHERE cov.latest_snapshot_at IS NOT NULL),
                       count(*) FILTER (WHERE cov.latest_snapshot_at > now() - make_interval(days => coalesce(cov.freshness_threshold_days, 30)))
                  FROM asset a
                  LEFT JOIN sbom_coverage_state cov ON cov.asset_id = a.id
                 WHERE a.lifecycle_state <> 'RETIRED'
                   AND a.owning_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    total = r.getLong(1);
                    covered = r.getLong(2);
                    facts.add("component inventory coverage: " + covered + " of " + total
                            + " assets have ever submitted a bill of materials, " + r.getLong(3)
                            + " of those within their freshness threshold. EVERY component figure below is over the "
                            + covered + " covered assets only; the other " + (total - covered) + " have no bill of "
                            + "materials, so no component vulnerability has been matched against them — which is not "
                            + "the same as them having none", "/composition");
                }
            }
        }
        long open = 0;
        StringBuilder bySeverity = new StringBuilder();
        try (PreparedStatement s = c.prepareStatement("""
                SELECT coalesce(aca.severity_code, 'UNRATED'), count(*),
                       count(DISTINCT aca.component_id),
                       count(*) FILTER (WHERE aca.fixed_version IS NOT NULL)
                  FROM asset_component_advisory aca
                  JOIN asset a ON a.id = aca.asset_id
                 WHERE aca.resolved_at IS NULL
                   AND a.owning_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                 GROUP BY 1
                 ORDER BY min(coalesce(aca.severity_ordinal, 99))
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    open += r.getLong(2);
                    bySeverity.append(bySeverity.isEmpty() ? "" : ", ").append(r.getLong(2)).append(" ").append(r.getString(1));
                    facts.add("component advisories open at severity " + r.getString(1) + ": " + r.getLong(2)
                            + " across " + r.getLong(3) + " distinct components, of which " + r.getLong(4)
                            + " have a published fixed version and " + (r.getLong(2) - r.getLong(4))
                            + " do not — the second group is a decision, not an upgrade", "/composition");
                }
            }
        }
        if (open == 0) {
            facts.add("no component advisory is open against any asset that has submitted a bill of materials in this "
                    + "scope" + (covered == 0 ? " — and no asset has submitted one, so nothing could have been matched"
                            : ""), "/composition");
            return covered == 0
                    ? "No bill of materials has been submitted, so no component vulnerability could be matched."
                    : "No component advisory is open across the " + covered + " assets with a bill of materials.";
        }
        // The components themselves. One row each, worst advisory first, with what it would take to fix.
        StringBuilder worst = new StringBuilder();
        try (PreparedStatement s = c.prepareStatement("""
                WITH links AS (
                    SELECT aca.component_id, aca.name, aca.version, aca.ecosystem, aca.asset_id,
                           aca.advisory_key, aca.severity_code, aca.severity_ordinal, aca.cvss_score,
                           aca.fixed_version, aca.is_direct
                      FROM asset_component_advisory aca
                      JOIN asset a ON a.id = aca.asset_id
                     WHERE aca.resolved_at IS NULL
                       AND a.owning_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                ),
                per_component AS (
                    SELECT component_id, name, version, ecosystem,
                           count(DISTINCT asset_id) AS assets,
                           count(DISTINCT advisory_key) AS advisories,
                           min(coalesce(severity_ordinal, 99)) AS worst_ordinal,
                           bool_or(is_direct) AS any_direct,
                           count(DISTINCT advisory_key) FILTER (WHERE fixed_version IS NOT NULL) AS fixable
                      FROM links GROUP BY component_id, name, version, ecosystem
                )
                SELECT pc.name, pc.version, pc.ecosystem, pc.assets, pc.advisories, pc.any_direct, pc.fixable,
                       w.advisory_key, w.severity_code, w.cvss_score, w.fixed_version
                  FROM per_component pc
                  JOIN LATERAL (SELECT l.advisory_key, l.severity_code, l.cvss_score, l.fixed_version
                                  FROM links l WHERE l.component_id = pc.component_id
                                 ORDER BY coalesce(l.severity_ordinal, 99), l.cvss_score DESC NULLS LAST
                                 LIMIT 1) w ON true
                 ORDER BY pc.worst_ordinal, pc.assets DESC, pc.name
                 LIMIT 10
                """)) {
            s.setArray(1, scopeArray(c, principal));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    facts.add("vulnerable component \"" + safe(r.getString(1)) + "@" + safe(r.getString(2)) + "\" ("
                            + safe(r.getString(3)) + "): carried by " + r.getLong(4) + " asset(s), "
                            + r.getLong(5) + " open advisory(ies), worst is " + safe(r.getString(8)) + " at severity "
                            + safe(r.getString(9)) + (r.getObject(10) == null ? "" : " (CVSS " + r.getBigDecimal(10) + ")")
                            + (r.getString(11) == null
                                    ? "; NO fixed version is published, so this one needs a decision rather than an upgrade"
                                    : "; fixed in version " + safe(r.getString(11)))
                            + (r.getBoolean(6) ? "; a direct dependency somebody chose"
                                    : "; transitive — it arrived with something else"),
                            "/composition");
                    if (worst.length() < 160) {
                        worst.append(worst.isEmpty() ? "" : ", ").append(r.getString(1)).append("@").append(r.getString(2));
                    }
                }
            }
        }
        // A component the question named — "are we exposed to log4j" — and which applications carry it.
        for (String term : Knowledge.terms(question)) {
            if (term.length() < 4) {
                continue;
            }
            try (PreparedStatement s = c.prepareStatement("""
                    SELECT aca.name, aca.version, string_agg(DISTINCT a.display_name, ', ' ORDER BY a.display_name),
                           count(DISTINCT aca.asset_id), min(aca.fixed_version), min(aca.advisory_key)
                      FROM asset_component_advisory aca
                      JOIN asset a ON a.id = aca.asset_id
                     WHERE aca.resolved_at IS NULL AND aca.name ILIKE ?
                       AND a.owning_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))
                     GROUP BY aca.name, aca.version
                     LIMIT 3
                    """)) {
                s.setString(1, "%" + term.replace("%", "").replace("_", "") + "%");
                s.setArray(2, scopeArray(c, principal));
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        facts.add("the question names \"" + term + "\": component \"" + safe(r.getString(1)) + "@"
                                + safe(r.getString(2)) + "\" is carried by " + r.getLong(4) + " asset(s) — "
                                + safe(r.getString(3)) + " — with advisory " + safe(r.getString(6))
                                + (r.getString(5) == null ? " and no published fix"
                                        : " fixed in " + safe(r.getString(5))), "/composition");
                    }
                }
            }
        }
        return open + " component advisories are open (" + bySeverity + ") across the " + covered + " of " + total
                + " assets that have submitted a bill of materials; the worst are on " + worst + ".";
    }

    // ------------------------------------------------------------------------------------------
    // Pack: HOWTO — the user guide
    // ------------------------------------------------------------------------------------------

    /**
     * The sections of the user guide that answer this question.
     *
     * <p>The prose is quoted rather than summarised into a fact line. A how-to answer is only worth
     * having if it is the platform's own words about its own screens; a paraphrase of a paraphrase is
     * where "click the button in Settings" comes from when there is no such button.
     */
    private String howTo(Numbering facts, String question) {
        List<Knowledge.Hit> hits = Knowledge.search(question, Set.of("guide"), 3);
        if (hits.isEmpty()) {
            facts.add("the user guide has no section matching this question; it covers signing in, navigation, "
                    + "findings, the assessment board, applications, dependencies, workload and planning, "
                    + "settings, AI assistance, access and roles, and what to do when something is refused",
                    "/guide");
            return null;
        }
        for (Knowledge.Hit hit : hits) {
            facts.add("user guide, section \"" + hit.section().heading() + "\": " + excerpt(hit.section().body()),
                    hit.section().link());
        }
        return "The guide covers this under \"" + hits.get(0).section().heading() + "\".";
    }

    // ------------------------------------------------------------------------------------------
    // Pack: API — the integration guide and the operations themselves
    // ------------------------------------------------------------------------------------------

    /**
     * How to call the platform, and what may be called.
     *
     * <p>Two halves, and the second is the one hand-written documentation always gets wrong. The
     * operations come from the registry the dispatcher enforces and refuses to start without, so this
     * cannot name an endpoint that does not exist, omit one that does, or state a permission that
     * differs from the one enforced.
     *
     * <p>Whether the caller holds the permission is stated per operation. It is not a filter: an
     * integration is usually built by somebody holding a service credential with a different grant
     * from their own, and hiding the operation would answer "there is no such endpoint" to a question
     * about an endpoint that exists.
     */
    private String api(Principal principal, Numbering facts, String question) {
        // The prose is also the bridge from a concept to an endpoint. "Submitting scan results" shares
        // no word with `/api/v1/finding-imports`, and the section that answers the question names the
        // path in its second line — so the paths the matched sections mention are what the registry is
        // then asked about. A synonym table would be a third place to keep in step with both.
        Set<String> namedPaths = new LinkedHashSet<>();
        for (Knowledge.Hit hit : Knowledge.search(question, Set.of("api"), 3)) {
            facts.add("integration guide, section \"" + hit.section().heading() + "\": " + excerpt(hit.section().body()),
                    hit.section().link());
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("/api/v1/[a-z-]+")
                    .matcher(hit.section().heading() + " " + hit.section().body());
            while (m.find()) {
                namedPaths.add(m.group());
            }
        }
        List<String> terms = Knowledge.terms(question);
        List<Map.Entry<Integer, String>> matched = new ArrayList<>();
        int v1 = 0;
        for (var operation : aspm.app.api.PlatformOperations.registry().all()) {
            String path = operation.pathTemplate();
            if (!path.startsWith("/api/v1")) {
                continue;
            }
            v1++;
            int score = 0;
            for (String named : namedPaths) {
                if (path.equals(named) || path.startsWith(named + "/")) {
                    score += 6;
                }
            }
            String folded = fold(path.replace('/', ' ').replace('-', ' '));
            for (String term : terms) {
                if (folded.contains(" " + term + " ")) {
                    score += 3;
                } else if (operation.requiredPermission().map(p -> p.contains(term)).orElse(false)) {
                    score += 1;
                }
            }
            if (score > 0) {
                var annotation = operation.annotationClass();
                String permission = operation.requiredPermission().orElse("none (unauthenticated)");
                matched.add(Map.entry(score, operation.method() + " " + path
                        + " — permission " + permission
                        + (operation.requiredPermission().map(principal::holds).orElse(Boolean.TRUE)
                                ? " (you hold it)" : " (you do not hold it)")
                        + ", annotation class " + annotation.name()
                        + (annotation.requiresIdempotencyKey() ? ", needs an Idempotency-Key header" : "")
                        + (annotation.requiresStepUp() ? ", needs step-up authentication" : "")));
            }
        }
        matched.sort(Map.Entry.<Integer, String>comparingByKey().reversed());
        for (var entry : matched.subList(0, Math.min(8, matched.size()))) {
            facts.add("API operation: " + entry.getValue(), "/api-guide");
        }
        facts.add("the integration surface has " + v1 + " operations under /api/v1; the full table with every "
                + "filterable and writable field is on the API guide page. Service callers use a signed request "
                + "(HMAC-SHA256 over method, path, body digest, timestamp and a single-use nonce) and there are no "
                + "bearer API keys (ADR-004)", "/api-guide");
        return matched.isEmpty() ? null : matched.size() + " API operations match this question.";
    }

    // ------------------------------------------------------------------------------------------
    // Pack: ACCESS — roles, permissions and who holds them
    // ------------------------------------------------------------------------------------------

    /**
     * Who can do what, and how that is changed.
     *
     * <p>Two levels, as the two screens are two screens: the population and their grants under
     * {@code iam.user.read}, and what a role actually carries under {@code auz.role.manage}. The
     * second is withheld and named rather than approximated, because "the developer role has 14
     * permissions" invites a decision that should be made looking at which fourteen.
     *
     * <p>A role the question names is resolved here, in SQL, for the same reason an application is:
     * the model may suggest a name and may not produce an identifier.
     */
    private String access(Connection c, Principal principal, Numbering facts, String question,
            List<String> hints, List<String> withheld) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                SELECT count(*) FILTER (WHERE p.kind = 'HUMAN' AND p.lifecycle_state = 'ACTIVE'),
                       (SELECT count(*) FROM role_assignment ra
                         WHERE ra.revoked_at IS NULL AND (ra.expires_at IS NULL OR ra.expires_at > now())),
                       (SELECT count(*) FROM role),
                       (SELECT count(*) FROM permission_catalogue),
                       (SELECT count(*) FROM permission_catalogue WHERE is_restricted),
                       (SELECT count(*) FROM permission_catalogue WHERE requires_step_up)
                  FROM principal p
                """)) {
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    facts.add("access in this tenant: " + r.getLong(1) + " active people, " + r.getLong(2)
                            + " role grants in force, " + r.getLong(3) + " roles defined against a product-fixed "
                            + "catalogue of " + r.getLong(4) + " permissions, of which " + r.getLong(5)
                            + " are restricted and " + r.getLong(6) + " require step-up authentication", "/access");
                }
            }
        }
        if (!principal.holds(ACCESS_ROLE_DETAIL)) {
            withheld.add("What each role carries, and who holds it — needs " + ACCESS_ROLE_DETAIL);
            return null;
        }
        // Which roles, how big, and how many people hold each. Ordered by name: a list ordered by
        // permission count reads as a ranking of authority, which is not what it measures.
        StringBuilder summary = new StringBuilder();
        try (PreparedStatement s = c.prepareStatement("""
                SELECT r.code, count(DISTINCT rp.permission_code),
                       (SELECT count(*) FROM role_assignment ra WHERE ra.role_id = r.id AND ra.revoked_at IS NULL
                          AND (ra.expires_at IS NULL OR ra.expires_at > now())),
                       string_agg(DISTINCT split_part(rp.permission_code, '.', 1), ', ' ORDER BY split_part(rp.permission_code, '.', 1))
                  FROM role r
                  LEFT JOIN role_permission rp ON rp.role_id = r.id
                 WHERE r.lifecycle_state = 'ACTIVE'
                 GROUP BY r.id, r.code
                 ORDER BY r.code
                """)) {
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    facts.add("role \"" + safe(r.getString(1)) + "\": " + r.getLong(2) + " permissions across the "
                            + (r.getString(4) == null ? "no" : safe(r.getString(4))) + " domain(s), held by "
                            + r.getLong(3) + " person or people right now", "/roles");
                    summary.append(summary.isEmpty() ? "" : "; ").append(r.getString(1)).append(" (")
                            .append(r.getLong(2)).append(" permissions, ").append(r.getLong(3)).append(" holders)");
                }
            }
        }
        // A role the question named: its permissions in full, because that is the question behind
        // "what can the developers do" and a count does not answer it.
        for (UUID role : resolveRoles(c, question, hints)) {
            try (PreparedStatement s = c.prepareStatement("""
                    SELECT r.code, string_agg(rp.permission_code, ', ' ORDER BY rp.permission_code)
                      FROM role r LEFT JOIN role_permission rp ON rp.role_id = r.id
                     WHERE r.id = ? GROUP BY r.code
                    """)) {
                s.setObject(1, role);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        facts.add("role \"" + safe(r.getString(1)) + "\" carries exactly these permissions: "
                                + (r.getString(2) == null ? "none" : safe(r.getString(2))), "/roles");
                    }
                }
            }
        }
        // And the scopes grants are actually made at, which is the half of a grant people forget.
        try (PreparedStatement s = c.prepareStatement("""
                SELECT ra.scope_mode, count(*)
                  FROM role_assignment ra
                 WHERE ra.revoked_at IS NULL AND (ra.expires_at IS NULL OR ra.expires_at > now())
                 GROUP BY ra.scope_mode ORDER BY ra.scope_mode
                """)) {
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    facts.add("role grants in force with scope mode " + r.getString(1) + ": " + r.getLong(2)
                            + " (a grant is a role AND the part of the organization it applies to)", "/access");
                }
            }
        }
        return summary.isEmpty() ? null : "Roles: " + summary + ".";
    }

    /** Roles the question names, resolved in SQL. The model suggests a name; it never returns an identifier. */
    private static List<UUID> resolveRoles(Connection c, String question, List<String> hints) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(
                "SELECT id FROM role WHERE position(lower(replace(code, '_', ' ')) in lower(?)) > 0 "
                        + "OR position(lower(code) in lower(?)) > 0 LIMIT 3")) {
            s.setString(1, question);
            s.setString(2, question);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(r.getObject(1, UUID.class));
                }
            }
        }
        for (String hint : hints) {
            if (out.size() >= 3 || hint.strip().length() < 2) {
                continue;
            }
            try (PreparedStatement s = c.prepareStatement("SELECT id FROM role WHERE code ILIKE ? LIMIT 2")) {
                s.setString(1, "%" + hint.strip().replace("%", "").replace("_", "") + "%");
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        UUID id = r.getObject(1, UUID.class);
                        if (!out.contains(id)) {
                            out.add(id);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Enough of a section to answer from, and not so much that three of them crowd out the figures. */
    private static String excerpt(String body) {
        String text = body.replaceAll("\\s*\\n\\s*", " ").strip();
        return text.length() > 900 ? text.substring(0, 900) + "…" : text;
    }

    // ==============================================================================================
    // Composition
    // ==============================================================================================

    record Composed(String text, List<String> citations, String modelIdentity, String promptVersion,
            UUID invocationId, String refusalCode) {
    }

    /**
     * The answer.
     *
     * <p>The model writes it where there is one and every control passes. Where there is not — no
     * provider, a budget spent, a provider rate limiting — or where a control rejects the reply, the
     * facts are turned into sentences here. The second answer is plainer and it is never wrong, which
     * is the right way round for a platform whose first principle is that a figure must be checkable.
     */
    private Composed compose(Principal principal, String question, List<String> history, Retrieval retrieval,
            List<Application> applications) throws SQLException {
        List<Fact> facts = retrieval.facts();
        List<String> factLines = new ArrayList<>();
        for (Fact fact : facts) {
            factLines.add(fact.ref() + ": " + fact.text());
        }
        for (String w : retrieval.withheld()) {
            factLines.add("withheld from this answer because the person asking may not read it: " + w);
        }
        factLines.add("cite facts by their F-number; do not cite anything that is not listed");
        if (!enabled(principal)) {
            return new Composed(rules(retrieval, applications, "the copilot capability is switched off for this tenant, "
                    + "so this answer was composed from the figures rather than written by a model"),
                    List.of(), null, null, null, "CAPABILITY_OFF");
        }
        Map<String, String> untrusted = new LinkedHashMap<>();
        untrusted.put("user_question", question);
        if (!history.isEmpty()) {
            untrusted.put("conversation_history", String.join("\n", history));
        }
        Object out = narrator.structured(principal, CAPABILITY, ANSWER_PROMPT,
                "Answer the question in the fenced content about this organization's application security posture, using "
                        + "only the FACTS. Every sentence that states something about the posture must cite the fact or facts "
                        + "it rests on, like [F3]. Answer in the same language the question was written in. Be direct and "
                        + "specific: name the applications, teams or requests the facts name. If a fact says something was "
                        + "withheld because the person may not read it, say so plainly rather than answering around it. "
                        // Added after a measurement: asked what to report to the board, the model looked for a
                        // fact that named a board report, did not find one, and declared the facts insufficient
                        // over a set that contained everything a board is told. An advisory question is answered
                        // by selecting and ordering the figures that bear on it, not by looking for a record that
                        // states the answer.
                        + "A question asking what to report, what to prioritise, where to invest or what to decide is "
                        + "answered by choosing the facts that bear on it, putting them in order of consequence, and "
                        + "saying what follows — each claim still citing its fact. Reserve insufficient for a subject "
                        + "the facts do not cover at all. If the facts cannot answer the question, set insufficient to "
                        + "true and say what is missing instead of guessing.",
                factLines, untrusted, "RECORD",
                "{\"answer\": <two to eight COMPLETE sentences — finish every sentence, never stop mid-clause; "
                        + "plain prose, each claim followed by its citation like [F3]>, "
                        + "\"citations\": [<every F-number used>], \"insufficient\": <true|false>}", 2000);
        if (out instanceof ModelNarrator.Refusal refused) {
            return new Composed(rules(retrieval, applications, "the model was not used (" + refused.code() + ": "
                    + refused.detail() + "), so this answer was composed from the figures"),
                    List.of(), null, null, null, refused.code());
        }
        ModelNarrator.Structured structured = (ModelNarrator.Structured) out;
        String answer = String.valueOf(structured.json().getOrDefault("answer", "")).strip();
        boolean insufficient = Boolean.TRUE.equals(structured.json().get("insufficient"));
        List<String> citations = new ArrayList<>();
        // The same four controls that guard posture.answer, and the same implementation — a second copy
        // would be a second thing to keep correct.
        Optional<Assistant.Refused> rejected = Assistant.rejection(narrator, principal, structured, answer, facts,
                factLines, question, insufficient, citations);
        if (rejected.isPresent()) {
            return new Composed(rules(retrieval, applications, "the model's answer was rejected by the grounding checks ("
                    + rejected.get().code() + "), so this answer was composed from the figures"),
                    List.of(), null, null, null, rejected.get().code());
        }
        return new Composed(answer, citations, structured.modelIdentity(), structured.promptVersion(),
                structured.invocationId(), null);
    }

    /**
     * The answer without a model: the packs' own sentences, in the order they were gathered.
     *
     * <p>Each summary is built from the same figures the facts carry, so the citations are exact. It
     * says which it is — a reader who cannot tell whether a model wrote something cannot weigh it
     * (PRD-AIC-036, PP-9).
     */
    private String rules(Retrieval retrieval, List<Application> applications, String why) {
        StringBuilder text = new StringBuilder();
        for (String pack : retrieval.used()) {
            String summary = retrieval.summaries().get(pack);
            if (summary != null && !summary.isBlank()) {
                text.append(text.isEmpty() ? "" : " ").append(summary);
            }
        }
        if (text.isEmpty()) {
            text.append("The figures below are what the platform holds for this question; nothing in them was summarised.");
        }
        if (!retrieval.withheld().isEmpty()) {
            text.append(" Part of the question could not be answered: ")
                    .append(String.join("; ", retrieval.withheld())).append(".");
        }
        text.append("\n\n(Composed from the recorded figures rather than written by a model — ").append(why).append(".)");
        return text.toString();
    }

    // ==============================================================================================
    // Follow-ups
    // ==============================================================================================

    /** What the reader is most likely to ask next, from what was just answered. Rules; no model call. */
    private static List<String> followUps(Retrieval retrieval, List<Application> applications, Principal principal) {
        List<String> out = new ArrayList<>();
        if (!applications.isEmpty()) {
            String name = applications.get(0).name();
            if (principal.holds("ast.asset.read")) {
                out.add("When was " + name + " last assessed?");
                out.add("Is an assessment planned for " + name + "?");
            }
            if (principal.holds("vul.finding.read")) {
                out.add("What should be fixed first on " + name + "?");
            }
        } else {
            if (retrieval.used().contains("POSTURE") && principal.holds("vul.finding.read")) {
                out.add("Which of those are past their remediation commitment?");
                out.add("Which application carries the most of them?");
            }
            if (retrieval.used().contains("SLA") && principal.holds("asm.request.read")) {
                out.add("Which requests are late, and who has them?");
            }
            if (retrieval.used().contains("COVERAGE") && principal.holds("ast.asset.read")) {
                out.add("Which applications have never been assessed?");
            }
            if (retrieval.used().contains("SBOM")) {
                out.add("Which of those already have a published fix?");
                out.add("Which applications carry the worst one?");
            }
            if (retrieval.used().contains("ASSESS_NEXT")) {
                out.add("Which of those are internet-facing?");
                out.add("Is an assessment window planned for any of them?");
            }
            if (retrieval.used().contains("ORGANIZATIONS")) {
                out.add("What is driving the worst organization's figures?");
            }
            if (retrieval.used().contains("API")) {
                out.add("How do I get a service credential?");
                out.add("What happens if I send the same request twice?");
            }
            if (retrieval.used().contains("ACCESS")) {
                out.add("What does a scope mean when granting a role?");
                out.add("Which role should a developer have?");
            }
            if (retrieval.used().contains("HOWTO")) {
                out.add("Why is my sidebar shorter than a colleague's?");
            }
        }
        if (out.size() > 3) {
            return out.subList(0, 3);
        }
        return out;
    }

    // ==============================================================================================
    // Storage
    // ==============================================================================================

    private Optional<UUID> conversation(Connection c, Principal principal, UUID id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT id FROM ai_conversation WHERE id = ? AND principal_id = ? AND lifecycle_state = 'ACTIVE'")) {
            s.setObject(1, id);
            s.setObject(2, principal.principalId());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(r.getObject(1, UUID.class)) : Optional.empty();
            }
        }
    }

    private static UUID createConversation(Connection c, Principal principal, String firstQuestion) throws SQLException {
        String title = firstQuestion.length() > 120 ? firstQuestion.substring(0, 117) + "..." : firstQuestion;
        try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO ai_conversation (principal_id, title) VALUES (?, ?) RETURNING id")) {
            s.setObject(1, principal.principalId());
            s.setString(2, title);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getObject(1, UUID.class);
            }
        }
    }

    private static String focusOf(Connection c, UUID conversation) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT focus_asset_id::text FROM ai_conversation WHERE id = ?")) {
            s.setObject(1, conversation);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }

    /**
     * The last few turns, as lines.
     *
     * <p>Read from the platform's own rows, never from the request body — see V081. Both sides are
     * included because a follow-up depends on what was answered, and both go through the untrusted
     * fence: the person's text is theirs, and the assistant's prior text was written by a model over
     * facts that may have contained attacker-authored titles.
     */
    private static List<String> history(Connection c, UUID conversation) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("""
                SELECT role, content FROM ai_conversation_message
                 WHERE conversation_id = ?
                 ORDER BY ordinal DESC
                 LIMIT ?
                """)) {
            s.setObject(1, conversation);
            s.setInt(2, HISTORY_TURNS * 2);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String content = r.getString(2);
                    if (content.length() > 400) {
                        content = content.substring(0, 400) + "…";
                    }
                    out.add(0, ("USER".equals(r.getString(1)) ? "asked: " : "was told: ") + content);
                }
            }
        }
        return out;
    }

    private static int nextOrdinal(Connection c, UUID conversation) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT coalesce(max(ordinal), -1) + 1 FROM ai_conversation_message WHERE conversation_id = ?")) {
            s.setObject(1, conversation);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    private static String insertMessage(Connection c, UUID conversation, int ordinal, String role, String content,
            List<String> citations, List<Fact> facts, List<String> topics, List<String> withheld,
            String modelIdentity, String promptVersion, UUID invocationId, String refusalCode) throws SQLException {
        List<Map<String, Object>> factRows = new ArrayList<>();
        for (Fact fact : facts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ref", fact.ref());
            row.put("text", fact.text());
            row.put("link", fact.link().orElse(null));
            factRows.add(row);
        }
        try (PreparedStatement s = c.prepareStatement("""
                INSERT INTO ai_conversation_message
                    (conversation_id, ordinal, role, content, citations, facts, topics, withheld,
                     model_identity, prompt_version, invocation_id, refusal_code)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                RETURNING id::text
                """)) {
            s.setObject(1, conversation);
            s.setInt(2, ordinal);
            s.setString(3, role);
            s.setString(4, content.length() > 40000 ? content.substring(0, 40000) : content);
            s.setString(5, Json.write(citations));
            s.setString(6, Json.write(factRows));
            s.setString(7, Json.write(topics));
            s.setString(8, Json.write(withheld));
            s.setString(9, modelIdentity);
            s.setString(10, promptVersion);
            s.setObject(11, invocationId);
            s.setString(12, refusalCode);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getString(1);
            }
        }
    }

    // ==============================================================================================

    /** Facts numbered F1..Fn as they are gathered, so a pack cannot collide with the one before it. */
    private static final class Numbering {
        private final List<Fact> facts = new ArrayList<>();
        private int next = 1;

        void add(String text, String link) {
            facts.add(new Fact("F" + next++, text, Optional.ofNullable(link)));
        }

        int size() {
            return facts.size();
        }

        List<Fact> list() {
            return List.copyOf(facts);
        }
    }

    private boolean enabled(Principal principal) throws SQLException {
        return assistant.enabled(principal, CAPABILITY);
    }

    private static java.sql.Array scopeArray(Connection c, Principal principal) throws SQLException {
        return c.createArrayOf("uuid", principal.scopeNodeIds().toArray(new UUID[0]));
    }

    /** A record's own text, with the quote that would break out of the fact line removed. */
    private static String safe(String text) {
        return text == null ? "" : text.replace('"', '\'');
    }

    /** The {@code {"items": [...]}} wrapper the read query builds around a jsonb array column. */
    private static List<?> items(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        Object parsed = Json.readObject(json).get("items");
        return parsed instanceof List<?> list ? list : List.of();
    }

    private static List<String> strings(String json) {
        List<String> out = new ArrayList<>();
        for (Object o : items(json)) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static List<Fact> factsOf(String json) {
        List<Fact> out = new ArrayList<>();
        for (Object o : items(json)) {
            if (o instanceof Map<?, ?> row) {
                out.add(new Fact(String.valueOf(row.get("ref")), String.valueOf(row.get("text")),
                        Optional.ofNullable(row.get("link")).map(String::valueOf)));
            }
        }
        return out;
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
