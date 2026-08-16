package aspm.app.resource;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Proposes the three classifications for a finding. Writes nothing.
 *
 * <h2>It answers into a form; the only write here is a person's submission</h2>
 *
 * <p>An assessor presses "analyse", reads what appears in the three fields, and submits — or edits it
 * first. The submission is the write, and it is theirs. That is what lets classification be
 * AI-assisted without AI ever writing a finding (ADR-005). {@link #apply} exists for the other half of
 * that sentence — it records what a person submitted, whether they typed it or accepted a proposal —
 * and it is reachable only from the submit path, never from {@link #classify}.
 *
 * <h2>Why it refuses rather than guesses</h2>
 *
 * <p>Three outputs, three ways of saying "I do not know", and each is a real value rather than a blank:
 *
 * <ul>
 *   <li>{@code CWE-UNKNOWN} — the weakness class was not determined. A fabricated CWE number is worse
 *       than none: it is wrong AND it looks authoritative, and the next reader has no way to tell.
 *   <li>{@code NOT_APPLICABLE} for OWASP — a conclusion that this is not a Top 10 weakness, which is
 *       different from nobody having checked.
 *   <li>{@code OTHER_TECHNICAL} — nothing in the tenant's taxonomy fits. A growing count there means
 *       the taxonomy is missing a category, not that the findings are miscellaneous.
 * </ul>
 *
 * <p>The rules read the tenant's OWN {@code mapping_rules} for the executive category, so a tenant that
 * refines what "financial" covers changes the agent and the assessor's help text in one place.
 */
public final class FindingClassifier {

    /** One proposal, with what it was based on. */
    public record Proposal(String executiveRiskCategory, String executiveRiskLabel,
            String owaspCode, String owaspName, String cweId, String cweName,
            String basis, String confidence) {
    }

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public FindingClassifier(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /**
     * Classifies from the words of a finding.
     *
     * @param title the finding's title; the strongest single signal in practice
     * @param description free text, which may be empty
     * @param findingClass the recorded control classification, e.g. SECRET or DEPENDENCY
     */
    public Proposal classify(Principal principal, String title, String description,
            String findingClass) throws SQLException {
        String text = ((title == null ? "" : title) + " " + (description == null ? "" : description))
                .toLowerCase(Locale.ROOT);
        List<String> basis = new ArrayList<>();

        // The finding class is evidence and is used first where it is decisive. A SECRET finding is a
        // credential problem whatever the title says, and a DEPENDENCY finding is supply chain — those
        // two are recorded by the ingesting tool rather than inferred, so they outrank a keyword.
        String category = null;
        if ("SECRET".equals(findingClass)) {
            category = "SECRETS_CREDENTIALS";
            basis.add("finding class SECRET");
        } else if ("DEPENDENCY".equals(findingClass)) {
            category = "SUPPLY_CHAIN";
            basis.add("finding class DEPENDENCY");
        }

        if (category == null) {
            // ORDER MATTERS, and getting it wrong is silent. A first version put the financial rules
            // first and matched on domain nouns — so "IDOR on the invoice endpoint" classified as a
            // business-logic risk because it contained the word "invoice". The CWE rules got the same
            // finding right (CWE-639), and that disagreement is what exposed it: when two independent
            // signals contradict each other, one of them is reading a noun as evidence.
            //
            // So the financial rules now require MANIPULATION language, not the vocabulary of a
            // payments business. Every finding in a payments company mentions payments; only some of
            // them are business-logic flaws. And the mechanism categories — authorization, injection,
            // authentication — go first, because a named mechanism is stronger evidence than a subject.
            String[][] rules = {
                {"ACCESS_AUTHORIZATION", "idor,bola,access control,authoriz,privilege,forced browsing,"
                        + "insecure direct object,object reference,tenant isolation,horizontal "
                        + "escalation,vertical escalation"},
                {"AUTHENTICATION_ACCOUNT", "authentication,login,otp,mfa,two-factor,session,jwt,token,"
                        + "account takeover,password reset,credential stuffing"},
                {"INJECTION_EXECUTION", "sql injection,nosql,command injection,rce,remote code,"
                        + "deserializ,template injection,ssti,cross-site scripting,xss,path traversal,"
                        + "ldap injection"},
                {"SECRETS_CREDENTIALS", "hardcoded,secret,api key,private key,credential in,token in"},
                // Manipulation and abuse, not the domain's nouns. "price manipulation" is a flaw;
                // "price" is a column name.
                {"FINANCIAL_BUSINESS_LOGIC", "payment manipulation,price manipulation,"
                        + "amount manipulation,voucher abuse,promotion abuse,voucher can be,"
                        + "applied twice,double refund,refund abuse,transaction abuse,"
                        + "business logic,workflow bypass,negative amount,rounding,"
                        + "race condition,replay the payment,skip payment,bypass payment"},
                {"DATA_PRIVACY", "sensitive data,pii,personal data,data exposure,information "
                        + "disclosure,excessive data,unauthorized data,in logs"},
                {"CRYPTO_TRANSPORT", "cryptograph,tls,ssl,certificate,cipher,cleartext,plaintext "
                        + "transmission,weak hash,md5,sha1"},
                {"SECURITY_CONFIGURATION", "misconfigur,debug,default credential,default config,"
                        + "security header,cors,directory listing,verbose error"},
                {"SUPPLY_CHAIN", "dependency,outdated component,vulnerable library,vulnerable package,"
                        + "known vulnerabilit"},
                {"AVAILABILITY_ABUSE", "denial of service,dos,rate limit,resource exhaustion,"
                        + "automated abuse,brute force"},
            };
            for (String[] rule : rules) {
                for (String needle : rule[1].split(",")) {
                    if (text.contains(needle)) {
                        category = rule[0];
                        basis.add("matched “" + needle + "”");
                        break;
                    }
                }
                if (category != null) {
                    break;
                }
            }
        }
        if (category == null) {
            category = "OTHER_TECHNICAL";
            basis.add("nothing in the taxonomy matched the wording");
        }

        // OWASP follows from the category where the mapping is the published one, and is NOT forced
        // where it is not. The requirement is explicit that classification must not be invented for the
        // sake of filling a field.
        String owasp = switch (category) {
            case "ACCESS_AUTHORIZATION" -> "A01:2025";
            case "SECURITY_CONFIGURATION" -> "A02:2025";
            case "SUPPLY_CHAIN" -> "A03:2025";
            case "CRYPTO_TRANSPORT" -> "A04:2025";
            case "INJECTION_EXECUTION" -> "A05:2025";
            case "FINANCIAL_BUSINESS_LOGIC" -> "A06:2025";
            case "AUTHENTICATION_ACCOUNT" -> "A07:2025";
            // Secrets and data exposure map to no single Top 10 entry: a hardcoded key can be an
            // integrity failure, a cryptographic failure or an access-control one depending on what it
            // opens. Saying NOT_APPLICABLE is the honest answer; picking one would be a coin toss
            // wearing a citation.
            default -> "NOT_APPLICABLE";
        };
        if ("DATA_PRIVACY".equals(category) || "SECRETS_CREDENTIALS".equals(category)) {
            basis.add("OWASP left as not-applicable: this category maps to no single Top 10 entry");
        }

        String cwe = cweFor(text, category);
        if ("CWE-UNKNOWN".equals(cwe)) {
            basis.add("CWE not determined from the wording");
        }

        String[] labels = labelFor(principal, category, owasp, cwe);
        return new Proposal(category, labels[0], owasp, labels[1], cwe, labels[2],
                String.join("; ", basis),
                // A band, never a percentage (ADR-038). Two independent signals agreeing is the most
                // these rules can claim.
                basis.size() >= 2 && !"OTHER_TECHNICAL".equals(category) ? "MEDIUM" : "LOW");
    }

    /** The CWE, or CWE-UNKNOWN. Never a plausible-looking number. */
    private static String cweFor(String text, String category) {
        String[][] rules = {
            {"CWE-89", "sql injection"}, {"CWE-79", "cross-site scripting"}, {"CWE-79", "xss"},
            {"CWE-78", "command injection"}, {"CWE-94", "remote code"}, {"CWE-502", "deserializ"},
            {"CWE-918", "server-side request forgery"}, {"CWE-918", "ssrf"},
            {"CWE-22", "path traversal"}, {"CWE-352", "cross-site request forgery"},
            {"CWE-798", "hardcoded"}, {"CWE-798", "hard-coded"}, {"CWE-522", "credential"},
            {"CWE-639", "idor"}, {"CWE-639", "user-controlled key"}, {"CWE-862", "missing authoriz"},
            {"CWE-863", "incorrect authoriz"}, {"CWE-284", "access control"},
            {"CWE-287", "authentication"}, {"CWE-384", "session fixation"},
            {"CWE-613", "session expir"}, {"CWE-200", "information disclosure"},
            {"CWE-200", "sensitive data"}, {"CWE-201", "in logs"},
            {"CWE-319", "cleartext"}, {"CWE-327", "weak cipher"}, {"CWE-327", "cryptograph"},
            {"CWE-327", "tls 1.0"}, {"CWE-327", "tls 1.1"}, {"CWE-327", "sslv3"},
            {"CWE-400", "resource exhaustion"}, {"CWE-770", "rate limit"},
            {"CWE-1035", "known vulnerabilit"}, {"CWE-1104", "outdated component"},
            {"CWE-840", "business logic"}, {"CWE-362", "race condition"},
            {"CWE-434", "file upload"}, {"CWE-20", "input validation"},
        };
        for (String[] rule : rules) {
            if (text.contains(rule[1])) {
                return rule[0];
            }
        }
        // A category alone is not enough to name a weakness class. Supply chain is the one exception:
        // "a dependency with a known vulnerability" IS CWE-1035 by definition.
        return "SUPPLY_CHAIN".equals(category) ? "CWE-1035" : "CWE-UNKNOWN";
    }

    /** Display names, read from the tenant's own taxonomy and from the reference tables. */
    private String[] labelFor(Principal principal, String category, String owasp, String cwe)
            throws SQLException {
        String[] out = {category, owasp, cwe};
        try (Connection connection = open(principal)) {
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT label_i18n ->> 'en' FROM executive_risk_category WHERE code = ?")) {
                s.setString(1, category);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next() && r.getString(1) != null) {
                        out[0] = r.getString(1);
                    }
                }
            }
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT name FROM owasp_top10_2025 WHERE code = ?")) {
                s.setString(1, owasp);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        out[1] = r.getString(1);
                    }
                }
            }
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT name FROM cwe WHERE id = ?")) {
                s.setString(1, cwe);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        out[2] = r.getString(1);
                    }
                }
            }
        }
        return out;
    }

    /** What a finding is currently classified as, for the edit form. */
    public record Current(String category, String owasp, String cwe, String source, String basis) {
    }

    /**
     * Reads the classification already on a finding.
     *
     * <p>A separate read rather than a widening of {@code AssessmentService.Finding}: that record is
     * shared with the server-rendered pages and the board, and adding five fields to it would make
     * every one of those reads carry columns only the editor uses.
     */
    public Current current(Principal principal, java.util.UUID findingId) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement s = connection.prepareStatement("""
                        SELECT executive_risk_category, owasp_2025_code, primary_cwe_id,
                               classification_source, classification_basis
                          FROM finding WHERE id = ?
                        """)) {
            s.setObject(1, findingId);
            try (ResultSet r = s.executeQuery()) {
                return r.next()
                        ? new Current(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                                r.getString(5))
                        : new Current(null, null, null, null, null);
            }
        }
    }

    /** One taxonomy option, for the form's pickers. */
    public record Option(String code, String label, String hint) {
    }

    /** The tenant's ACTIVE categories, with the mapping rules as the assessor's help text. */
    public List<Option> categories(Principal principal) throws SQLException {
        List<Option> out = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement s = connection.prepareStatement("""
                        SELECT code, label_i18n ->> 'en', description_i18n ->> 'en',
                               (SELECT string_agg(v, ' · ')
                                  FROM jsonb_array_elements_text(mapping_rules) v)
                          FROM executive_risk_category
                         WHERE lifecycle_state = 'ACTIVE' ORDER BY ordinal
                        """)) {
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    // Description AND examples in the hint. The requirement asked that noting be easy
                    // to understand; a code with no explanation is a code somebody guesses at, and the
                    // guess lands in a statistic nobody can correct later.
                    String hint = (r.getString(3) == null ? "" : r.getString(3))
                            + (r.getString(4) == null ? "" : "  —  e.g. " + r.getString(4));
                    out.add(new Option(r.getString(1), r.getString(2), hint.strip()));
                }
            }
        }
        return List.copyOf(out);
    }

    /** The published OWASP list, plus NOT_APPLICABLE. Ordered as published. */
    public List<Option> owasp(Principal principal) throws SQLException {
        // The guidance travels with the option. A picker that only somebody already fluent in the
        // taxonomy can use does not produce missing data — it produces confidently wrong data, and a
        // wrong category looks exactly as authoritative as a right one downstream (PP-7).
        return simple(principal, "SELECT code, code || ' — ' || name, "
                + "coalesce(guidance_i18n ->> 'en', '') FROM owasp_top10_2025 ORDER BY ordinal");
    }

    /** The CWE list. CWE-UNKNOWN first, because it is the honest answer and must be easy to pick. */
    public List<Option> cwes(Principal principal) throws SQLException {
        return simple(principal, "SELECT id, id || ' — ' || name, "
                + "coalesce(guidance_i18n ->> 'en', '') FROM cwe "
                + "ORDER BY (id <> 'CWE-UNKNOWN'), "
                + "         nullif(regexp_replace(id, '[^0-9]', '', 'g'), '')::int");
    }

    private List<Option> simple(Principal principal, String sql) throws SQLException {
        List<Option> out = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement s = connection.prepareStatement(sql)) {
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(new Option(r.getString(1), r.getString(2), r.getString(3)));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Records the classification a person submitted.
     *
     * <p>Called from the submit path only. {@code source} is what the form reports: {@code ASSESSOR}
     * where they typed it, {@code AI_ASSISTED} where they pressed analyse and submitted what appeared.
     * Both are human submissions — the distinction exists so somebody can later measure whether the
     * proposals were any good, not because one is less trusted.
     *
     * <p>Also writes {@code finding_cwe} with the primary flag, so the multi-CWE model is populated
     * from the first finding rather than backfilled later from a single column whose meaning nobody
     * can re-derive.
     */
    public void apply(Principal principal, java.util.UUID findingId, String category, String owasp,
            String cwe, String source, String basis) throws SQLException {
        try (Connection connection = open(principal)) {
            try (PreparedStatement s = connection.prepareStatement("""
                    UPDATE finding
                       SET executive_risk_category = ?, owasp_2025_code = ?, primary_cwe_id = ?,
                           classification_source = ?, classification_basis = ?,
                           updated_at = now(), updated_by = ?
                     WHERE id = ?
                    """)) {
                s.setString(1, category);
                s.setString(2, owasp);
                s.setString(3, cwe);
                s.setString(4, source);
                s.setString(5, basis);
                s.setObject(6, principal.principalId());
                s.setObject(7, findingId);
                s.executeUpdate();
            }
            try (PreparedStatement s = connection.prepareStatement("""
                    INSERT INTO finding_cwe (tenant_id, finding_id, cwe_id, is_primary, source)
                    VALUES (current_tenant_id(), ?, ?, true, ?)
                    ON CONFLICT (tenant_id, finding_id, cwe_id)
                    DO UPDATE SET is_primary = true, source = EXCLUDED.source
                    """)) {
                s.setObject(1, findingId);
                s.setString(2, cwe);
                s.setString(3, "AI_ASSISTED".equals(source) ? "AI" : "ASSESSOR");
                s.executeUpdate();
            }
            audit.domainChange(connection, principal, "finding",
                    aspm.kernel.audit.contract.DomainChangeKind.UPDATED, findingId,
                    aspm.app.audit.AuditScopes.ofFinding(connection, findingId),
                    java.util.Map.of("executive_risk_category", category == null ? "" : category,
                            "owasp_2025_code", owasp == null ? "" : owasp,
                            "primary_cwe_id", cwe == null ? "" : cwe,
                            // Whether a person or a model proposed it. DOC-14 wants the actor's kind;
                            // this is the same question one level in, because an AI-assisted
                            // classification a person accepted is a different record from one they wrote.
                            "classification_source", source == null ? "" : source));
            // Both statements or neither: a finding whose column says one CWE while finding_cwe says
            // another is a record that disagrees with itself.
            connection.commit();
        }
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
