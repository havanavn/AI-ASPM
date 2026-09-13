package aspm.app.ai;

import aspm.app.persistence.TenantConnections;
import aspm.app.resource.ModelNarrator;
import aspm.app.resource.RiskScoring;
import aspm.app.resource.VulnerabilityQuery;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The two on-demand capabilities: a grounded question over the posture ({@code PRD-AIC-057}) and drafting
 * assistance ({@code PRD-AIC-019}). Neither writes to the ledger — an answer is read once, a draft is
 * edited and committed by the person through the ordinary form — and both are recorded as invocations.
 *
 * <p><b>Ask.</b> The platform composes the facts — counts, service level state, the highest-scoring
 * findings, coverage — from the caller's own scope ({@code PRD-AIC-030}), names each one F1..Fn, and asks
 * for an answer whose every claim cites a fact. A citation that does not resolve rejects the whole answer
 * ({@code PRD-AIC-033}); a figure not in the facts rejects it ({@code PRD-AIC-034}); the answer is labelled
 * generated wherever it is shown ({@code PRD-AIC-036}). The question itself goes through the fence like
 * any other text a model reads.
 *
 * <p><b>Draft.</b> From the caller's notes and the object being drafted about, a text the caller edits.
 * Attributed as generated until the person saves it — which the form does, not this class.
 */
public final class Assistant {

    public static final String USE = "aic.assist.use";
    public static final String ASK_CAPABILITY = "posture.answer";
    public static final String DRAFT_CAPABILITY = "drafting.assist";
    public static final String ASK_PROMPT = "posture-answer/v1";
    public static final String DRAFT_PROMPT = "draft/v1";

    /** One fact the answer may cite. */
    public record Fact(String ref, String text, Optional<String> link) {
    }

    public record Answer(String text, List<String> citations, List<Fact> facts, boolean insufficient, String modelIdentity,
            String promptVersion, boolean generated) {
    }

    public record Draft(String text, String modelIdentity, String promptVersion, boolean generated) {
    }

    public record Refused(String code, String detail, List<Fact> facts) {
    }

    private final DataSource dataSource;
    private final ModelNarrator narrator;
    private final VulnerabilityQuery vulnerabilities;
    private final RiskScoring scoring;

    public Assistant(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.narrator = new ModelNarrator(dataSource);
        this.vulnerabilities = new VulnerabilityQuery(dataSource);
        this.scoring = new RiskScoring(dataSource);
    }

    // ==============================================================================================
    // Ask
    // ==============================================================================================

    /** Answers, or explains why not. Either way the facts gathered are returned so the reader can check. */
    public Object ask(Principal principal, String question, Optional<UUID> scopeNode) throws SQLException {
        if (question == null || question.strip().length() < 3) {
            throw new IllegalArgumentException("ask a question");
        }
        if (question.length() > 2000) {
            throw new IllegalArgumentException("a question of at most 2000 characters");
        }
        Principal as = scopeNode.map(node -> narrowed(principal, node)).orElse(principal);
        if (scopeNode.isPresent() && as.scopeNodeIds().isEmpty()) {
            throw new IllegalArgumentException("that part of the organization is outside your reach");
        }
        List<Fact> facts = gather(as, scopeNode);
        if (!enabled(principal, ASK_CAPABILITY)) {
            return new Refused("CAPABILITY_OFF", "the question-answering capability is switched off for this tenant; a person with "
                    + "AI configuration authority enables it under Configuration › AI models", facts);
        }
        List<String> factLines = new ArrayList<>();
        for (Fact fact : facts) {
            factLines.add(fact.ref() + ": " + fact.text());
        }
        factLines.add("cite facts by their F-number; do not cite anything that is not listed");
        Object out = narrator.structured(principal, ASK_CAPABILITY, ASK_PROMPT,
                "Answer the question in the fenced content about this organization's application security posture, using only the "
                        + "FACTS. Every sentence that states something about the posture must cite the fact(s) it rests on. If the facts "
                        + "cannot answer the question, set insufficient to true and say what is missing instead of guessing.",
                factLines, Map.of("user_question", question), "RECORD",
                "{\"answer\": <two to six sentences, plain English, each claim followed by its citation like [F3]>, "
                        + "\"citations\": [<every F-number used>], \"insufficient\": <true|false>}", 700);
        if (out instanceof ModelNarrator.Refusal refused) {
            return new Refused(refused.code(), refused.detail(), facts);
        }
        ModelNarrator.Structured structured = (ModelNarrator.Structured) out;
        String answer = String.valueOf(structured.json().getOrDefault("answer", "")).strip();
        boolean insufficient = Boolean.TRUE.equals(structured.json().get("insufficient"));
        if (answer.isBlank()) {
            narrator.reject(principal, structured.invocationId(), "EMPTY_ANSWER");
            return new Refused("EMPTY_ANSWER", "the model produced no answer", facts);
        }
        // PRD-AIC-033: every citation resolves, and every posture claim carries one.
        Set<String> known = new java.util.HashSet<>();
        facts.forEach(f -> known.add(f.ref()));
        List<String> citations = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(F\\d+)\\]").matcher(answer);
        while (m.find()) {
            if (!known.contains(m.group(1))) {
                narrator.reject(principal, structured.invocationId(), "UNRESOLVED_CITATION");
                return new Refused("UNRESOLVED_CITATION", "the answer cited " + m.group(1) + ", which is not among the facts it was given "
                        + "(PRD-AIC-033); it was rejected rather than repaired", facts);
            }
            if (!citations.contains(m.group(1))) {
                citations.add(m.group(1));
            }
        }
        if (structured.json().get("citations") instanceof List<?> declared) {
            for (Object c : declared) {
                String ref = String.valueOf(c);
                if (!known.contains(ref)) {
                    narrator.reject(principal, structured.invocationId(), "UNRESOLVED_CITATION");
                    return new Refused("UNRESOLVED_CITATION", "the answer cited " + ref + ", which is not among the facts it was given (PRD-AIC-033)", facts);
                }
                if (!citations.contains(ref)) {
                    citations.add(ref);
                }
            }
        }
        if (citations.isEmpty() && !insufficient) {
            narrator.reject(principal, structured.invocationId(), "UNCITED_ANSWER");
            return new Refused("UNCITED_ANSWER", "the answer cited nothing, so no claim in it can be checked (PRD-AIC-033)", facts);
        }
        // PRD-AIC-034: a figure not in the facts. Citation markers are not figures.
        String invented = ModelNarrator.inventedFigure(answer.replaceAll("\\[F\\d+\\]", ""), factLines, question);
        if (invented != null) {
            narrator.reject(principal, structured.invocationId(), "INVENTED_NUMBER");
            return new Refused("INVENTED_NUMBER", "the answer contained the figure " + invented + ", which is not among the facts (ADR-038)", facts);
        }
        String contradiction = ModelNarrator.contradictedSeverity(answer, factLines);
        if (contradiction != null) {
            narrator.reject(principal, structured.invocationId(), "CONTRADICTS_RECORD");
            return new Refused("CONTRADICTS_RECORD", "the answer described severity as " + contradiction + ", which the facts do not say (PRD-AIC-035)", facts);
        }
        return new Answer(answer, citations, facts, insufficient, structured.modelIdentity(), structured.promptVersion(), true);
    }

    /** The grounding contract of `posture.answer`: these projections, within the caller's scope, and nothing else (PRD-AIC-031). */
    List<Fact> gather(Principal as, Optional<UUID> scopeNode) throws SQLException {
        List<Fact> facts = new ArrayList<>();
        int n = 1;
        VulnerabilityQuery.Filter filter = new VulnerabilityQuery.Filter(scopeNode.map(List::of).orElse(null), null, null, null, null, false, null,
                Set.of(), null, null, null, null, null, null, false, null, null, null, false);
        VulnerabilityQuery.Summary summary = vulnerabilities.summary(as, filter);
        String where = scopeNode.map(node -> "the selected organization node").orElse("everything you may see");
        facts.add(new Fact("F" + n++, "scope of every fact below: " + where, Optional.empty()));
        facts.add(new Fact("F" + n++, "open findings: " + summary.open() + "; closed: " + summary.closed() + "; total recorded: " + summary.total(), Optional.of("/vulnerabilities")));
        facts.add(new Fact("F" + n++, "open by severity: critical " + summary.criticalOpen() + ", high " + summary.highOpen() + ", medium " + summary.mediumOpen()
                + ", low " + summary.lowOpen() + ", unrated " + summary.unratedOpen(), Optional.of("/vulnerabilities")));
        facts.add(new Fact("F" + n++, "open and internet-facing: " + summary.internetFacingOpen() + "; open with a fix claimed but unverified: " + summary.claimedOpen()
                + "; open under an accepted risk: " + summary.acceptedOpen() + "; open with nobody assigned: " + summary.unassignedOpen(), Optional.of("/vulnerabilities")));
        facts.add(new Fact("F" + n++, "open older than 30 days: " + summary.openOver30() + "; older than 90 days: " + summary.openOver90() + "; older than 180 days: "
                + summary.openOver180() + (summary.oldestOpenDays() == null ? "" : "; oldest open finding is " + summary.oldestOpenDays() + " days old"), Optional.of("/vulnerabilities")));
        facts.add(new Fact("F" + n++, "closed in the last 30 days: " + summary.closedLast30() + "; in the last 90 days: " + summary.closedLast90()
                + "; closures without verification: " + summary.unverifiedClosures() + "; recurring findings: " + summary.recurring(), Optional.of("/vulnerabilities")));
        facts.add(new Fact("F" + n++, "assets affected by open findings: " + summary.assetsAffected(), Optional.of("/composition")));
        try (Connection c = TenantConnections.open(dataSource, as)) {
            java.sql.Array scope = c.createArrayOf("uuid", as.scopeNodeIds().toArray());
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT count(*) FILTER (WHERE breached_at IS NOT NULL AND resolved_at IS NULL), count(*) FILTER (WHERE resolved_at IS NULL AND breached_at IS NULL AND due_at < now() + interval '7 days'), "
                            + "count(*) FILTER (WHERE resolved_at IS NULL) FROM service_level_clock k JOIN finding f ON f.id = k.subject_id AND k.subject_kind = 'FINDING' "
                            + "WHERE f.scope_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))")) {
                s.setArray(1, scope);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        facts.add(new Fact("F" + n++, "service levels on open findings: " + r.getLong(1) + " breached, " + r.getLong(2) + " due within 7 days, "
                                + r.getLong(3) + " running", Optional.of("/vulnerabilities")));
                    }
                }
            }
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT count(*), count(*) FILTER (WHERE EXISTS (SELECT 1 FROM asset_finding_link l JOIN finding f ON f.id = l.finding_id WHERE l.asset_id = a.id AND f.last_detected_at > now() - interval '90 days')) "
                            + "FROM asset a WHERE a.lifecycle_state IN ('DISCOVERED', 'ACTIVE') AND a.owning_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))")) {
                s.setArray(1, scope);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        facts.add(new Fact("F" + n++, "coverage: " + r.getLong(1) + " live assets, of which " + r.getLong(2) + " had a detection in the last 90 days; the rest are "
                                + "unmeasured, and an unmeasured asset is not a clean one", Optional.of("/composition")));
                    }
                }
            }
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT count(*) FILTER (WHERE state IN ('SUBMITTED', 'TRIAGED', 'SCHEDULED', 'IN_PROGRESS', 'ACCEPTED')), count(*) FILTER (WHERE state = 'DRAFT') "
                            + "FROM assessment_request WHERE requested_org_node_id IN (SELECT descendant_id FROM org_closure WHERE ancestor_id = ANY (?))")) {
                s.setArray(1, scope);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        facts.add(new Fact("F" + n++, "assessment requests in flight: " + r.getLong(1) + "; drafts: " + r.getLong(2), Optional.of("/board")));
                    }
                }
            }
        }
        for (RiskScoring.Score score : scoring.topFindings(as, 8)) {
            facts.add(new Fact("F" + n++, "high-scoring open finding \"" + score.title().replace('"', '\'') + "\": score " + score.score() + " " + score.scoreBand()
                    + ", severity " + score.severity() + ", exposure " + score.exposure() + ", criticality " + score.criticality(),
                    Optional.of(score.requestId() == null ? "/pipeline/findings/" + score.findingId() : "/board/" + score.requestId() + "/findings/" + score.findingId())));
        }
        return facts;
    }

    // ==============================================================================================
    // Draft
    // ==============================================================================================

    /**
     * @param kind {@code REQUEST_DESCRIPTION}, {@code FINDING_WRITEUP} or {@code COMMENT}
     * @param subjectTitle what the draft is about, from the form; fenced like everything else
     */
    public Object draft(Principal principal, String kind, String subjectTitle, String notes) throws SQLException {
        if (notes == null || notes.strip().length() < 5) {
            throw new IllegalArgumentException("write a few notes first; the draft is built from them");
        }
        if (notes.length() > 6000) {
            throw new IllegalArgumentException("notes of at most 6000 characters");
        }
        if (!enabled(principal, DRAFT_CAPABILITY)) {
            return new Refused("CAPABILITY_OFF", "drafting assistance is switched off for this tenant", List.of());
        }
        String task = switch (kind == null ? "" : kind) {
            case "REQUEST_DESCRIPTION" -> "Draft the description of an application security assessment request from the notes in the fenced content: "
                    + "what is in scope, what the requester is worried about, what the assessors need to know. Plain English, short paragraphs, "
                    + "no headings. Invent nothing that is not in the notes.";
            case "FINDING_WRITEUP" -> "Draft the description of a security finding from the notes in the fenced content: what the weakness is, where, "
                    + "how it was observed, and its impact. Markdown with the sections Summary, Details, Impact. Invent nothing that is not in the notes; "
                    + "do not add severity ratings or numbers.";
            case "COMMENT" -> "Draft a short comment for a security work item from the notes in the fenced content: professional, direct, two to five "
                    + "sentences. Invent nothing that is not in the notes.";
            default -> throw new IllegalArgumentException("the draft kind is REQUEST_DESCRIPTION, FINDING_WRITEUP or COMMENT");
        };
        Map<String, String> untrusted = new LinkedHashMap<>();
        untrusted.put("draft_notes", notes);
        if (subjectTitle != null && !subjectTitle.isBlank()) {
            untrusted.put("finding_title", subjectTitle);
        }
        Object out = narrator.structured(principal, DRAFT_CAPABILITY, DRAFT_PROMPT, task,
                ModelNarrator.facts("the draft will be edited by the person who wrote the notes before anything is saved",
                        "the draft is shown labelled as generated until that person accepts it"),
                untrusted, "RECORD", "{\"draft\": <the text>}", 900);
        if (out instanceof ModelNarrator.Refusal refused) {
            return new Refused(refused.code(), refused.detail(), List.of());
        }
        ModelNarrator.Structured structured = (ModelNarrator.Structured) out;
        String text = String.valueOf(structured.json().getOrDefault("draft", "")).strip();
        if (text.isBlank()) {
            narrator.reject(principal, structured.invocationId(), "EMPTY_DRAFT");
            return new Refused("EMPTY_DRAFT", "the model produced no draft", List.of());
        }
        if (text.length() > 12000) {
            text = text.substring(0, 12000);
        }
        return new Draft(text, structured.modelIdentity(), structured.promptVersion(), true);
    }

    // ==============================================================================================

    private static Principal narrowed(Principal principal, UUID node) {
        // The caller's reach narrowed to one node: only if the node is inside it. Checked in SQL by the
        // queries' closure predicate anyway; narrowing here keeps the facts about the node the caller named.
        return new Principal(principal.tenantId(), principal.principalId(), principal.permissions(), Set.of(node),
                principal.stepUpAuthenticated(), principal.serviceCredential(), principal.credentialChangeRequired());
    }

    boolean enabled(Principal principal, String capability) throws SQLException {
        try (Connection c = TenantConnections.open(dataSource, principal);
                PreparedStatement s = c.prepareStatement("SELECT enabled FROM ai_capability WHERE code = ?")) {
            s.setString(1, capability);
            try (ResultSet r = s.executeQuery()) {
                return r.next() && r.getBoolean(1);
            }
        }
    }
}
