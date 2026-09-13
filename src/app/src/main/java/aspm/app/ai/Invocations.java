package aspm.app.ai;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Json;
import aspm.app.runtime.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The invocation record, the budget and the cache. {@code PRD-AIC-043}, {@code PRD-AIC-044},
 * {@code PRD-AIC-053}, {@code PRD-AIC-054}, {@code PRD-AIC-055}.
 *
 * <p>Every model call — made, cached, refused before it was made — is one row. The budget is read
 * before the call and the refusal is a recorded outcome with the reason stated, never a quiet
 * downgrade. The cache is the most recent successful row with the same prompt hash inside the
 * tenant's window, read under row-level security so the tenant is in the key by construction (I11).
 */
public final class Invocations {

    public record Budget(int dailyTokenBudget, int perPrincipalHourlyInvocations, int cacheMinutes, boolean retainPrompts,
            boolean retainOutputs) {
    }

    /** What the budget says about a call that is about to happen. */
    public record Allowance(boolean permitted, String reason, long tokensUsedToday, int callsThisHour) {
    }

    /** One call's outcome, for the record. */
    public record Record(String capability, Optional<UUID> providerId, String modelIdentity, String promptVersion, byte[] promptHash,
            List<String> contextRefs, String dataCategory, int injectionSignals, String outcome, Optional<String> refusalCode,
            int promptTokens, int completionTokens, long latencyMs, boolean cached, Optional<String> promptText, Optional<String> outputText) {
    }

    private final DataSource dataSource;

    public Invocations(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public static byte[] hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Budget budget(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT daily_token_budget, per_principal_hourly_invocations, cache_minutes, retain_prompts, retain_outputs FROM ai_budget");
                ResultSet r = statement.executeQuery()) {
            if (r.next()) {
                return new Budget(r.getInt(1), r.getInt(2), r.getInt(3), r.getBoolean(4), r.getBoolean(5));
            }
        }
        return new Budget(500000, 600, 15, false, true);
    }

    public Budget budget(Principal principal) throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, principal)) {
            return budget(connection);
        }
    }

    public Budget saveBudget(Principal actor, int dailyTokens, int hourlyPerPrincipal, int cacheMinutes, boolean retainPrompts, boolean retainOutputs)
            throws SQLException {
        try (Connection connection = TenantConnections.open(dataSource, actor)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_budget (tenant_id, daily_token_budget, per_principal_hourly_invocations, cache_minutes, retain_prompts, retain_outputs, updated_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id) DO UPDATE SET daily_token_budget = EXCLUDED.daily_token_budget, "
                            + "per_principal_hourly_invocations = EXCLUDED.per_principal_hourly_invocations, cache_minutes = EXCLUDED.cache_minutes, "
                            + "retain_prompts = EXCLUDED.retain_prompts, retain_outputs = EXCLUDED.retain_outputs, updated_at = now(), updated_by = EXCLUDED.updated_by")) {
                statement.setObject(1, actor.tenantId());
                statement.setInt(2, dailyTokens);
                statement.setInt(3, hourlyPerPrincipal);
                statement.setInt(4, cacheMinutes);
                statement.setBoolean(5, retainPrompts);
                statement.setBoolean(6, retainOutputs);
                statement.setObject(7, actor.principalId());
                statement.executeUpdate();
            }
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC()).event(connection, actor,
                    aspm.kernel.audit.contract.AuditEventType.AI_CONFIGURATION_CHANGED, null, null,
                    Map.of("ai_budget", Map.of("daily_token_budget", dailyTokens, "per_principal_hourly_invocations", hourlyPerPrincipal,
                            "cache_minutes", cacheMinutes, "retain_prompts", retainPrompts, "retain_outputs", retainOutputs)));
            connection.commit();
            return budget(connection);
        }
    }

    /** {@code PRD-AIC-053} / {@code PRD-AIC-055}: may this principal make a call now, and why not. */
    public Allowance allowance(Connection connection, Principal principal) throws SQLException {
        Budget budget = budget(connection);
        long tokensToday;
        int callsThisHour;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT coalesce(sum(prompt_tokens + completion_tokens), 0) FROM ai_invocation WHERE created_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'");
                ResultSet r = statement.executeQuery()) {
            r.next();
            tokensToday = r.getLong(1);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM ai_invocation WHERE principal_id = ? AND created_at >= now() - interval '1 hour' AND outcome IN ('OK', 'ERROR', 'REFUSED')")) {
            statement.setObject(1, principal.principalId());
            try (ResultSet r = statement.executeQuery()) {
                r.next();
                callsThisHour = r.getInt(1);
            }
        }
        if (tokensToday >= budget.dailyTokenBudget()) {
            return new Allowance(false, "the tenant's daily AI budget of " + budget.dailyTokenBudget() + " tokens is exhausted (" + tokensToday
                    + " used); capabilities are unavailable until the day resets (PRD-AIC-054)", tokensToday, callsThisHour);
        }
        if (callsThisHour >= budget.perPrincipalHourlyInvocations()) {
            return new Allowance(false, "you have made " + callsThisHour + " AI calls in the last hour, the per-person limit (PRD-AIC-055)",
                    tokensToday, callsThisHour);
        }
        return new Allowance(true, "within budget", tokensToday, callsThisHour);
    }

    /** {@code PRD-AIC-055}: the recorded output of an identical request inside the window. */
    public Optional<String> cached(Connection connection, byte[] promptHash) throws SQLException {
        Budget budget = budget(connection);
        if (budget.cacheMinutes() <= 0) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT output_text FROM ai_invocation WHERE prompt_hash = ? AND outcome = 'OK' AND output_text IS NOT NULL "
                        + "AND created_at >= now() - make_interval(mins => ?) ORDER BY created_at DESC LIMIT 1")) {
            statement.setBytes(1, promptHash);
            statement.setInt(2, budget.cacheMinutes());
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? Optional.ofNullable(r.getString(1)) : Optional.empty();
            }
        }
    }

    public UUID record(Connection connection, Principal principal, Record record) throws SQLException {
        Budget budget = budget(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ai_invocation (tenant_id, capability, principal_id, provider_id, model_identity, prompt_version, prompt_hash, context_refs, "
                        + "data_category, injection_signals, outcome, refusal_code, prompt_tokens, completion_tokens, latency_ms, cached, prompt_text, output_text) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
            statement.setObject(1, principal.tenantId());
            statement.setString(2, record.capability());
            statement.setObject(3, principal.principalId());
            statement.setObject(4, record.providerId().orElse(null));
            statement.setString(5, record.modelIdentity());
            statement.setString(6, record.promptVersion());
            statement.setBytes(7, record.promptHash());
            statement.setString(8, Json.write(record.contextRefs()));
            statement.setString(9, record.dataCategory());
            statement.setInt(10, record.injectionSignals());
            statement.setString(11, record.outcome());
            statement.setString(12, record.refusalCode().orElse(null));
            statement.setInt(13, record.promptTokens());
            statement.setInt(14, record.completionTokens());
            statement.setInt(15, (int) Math.min(Integer.MAX_VALUE, record.latencyMs()));
            statement.setBoolean(16, record.cached());
            statement.setString(17, budget.retainPrompts() ? record.promptText().orElse(null) : null);
            // Output is what the cache serves; a tenant that retains nothing also caches nothing.
            statement.setString(18, budget.retainOutputs() ? record.outputText().orElse(null) : null);
            try (ResultSet r = statement.executeQuery()) {
                r.next();
                return r.getObject(1, UUID.class);
            }
        }
    }

    /**
     * The platform rejected what the model produced — an invented figure, a contradiction, a code off
     * the list, a citation that does not resolve. The record says so (PRD-AIC-032: "a rejection rate is
     * a quality signal") and the output leaves the cache, so an identical request is asked again rather
     * than served the rejected text.
     */
    public void refuse(Connection connection, UUID invocationId, String code) throws SQLException {
        if (invocationId == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE ai_invocation SET outcome = 'REFUSED', refusal_code = ?, output_text = NULL WHERE id = ?")) {
            statement.setString(1, code);
            statement.setObject(2, invocationId);
            statement.executeUpdate();
        }
    }

    // ==============================================================================================
    // Reporting — PRD-AIC-044 and the usage panel
    // ==============================================================================================

    public Map<String, Object> usage(Principal principal, int days) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection connection = TenantConnections.open(dataSource, principal)) {
            Budget budget = budget(connection);
            Allowance allowance = allowance(connection, principal);
            out.put("budget", Map.of("dailyTokenBudget", budget.dailyTokenBudget(), "perPrincipalHourlyInvocations", budget.perPrincipalHourlyInvocations(),
                    "cacheMinutes", budget.cacheMinutes(), "retainPrompts", budget.retainPrompts(), "retainOutputs", budget.retainOutputs()));
            out.put("tokensUsedToday", allowance.tokensUsedToday());
            out.put("permittedNow", allowance.permitted());
            out.put("reason", allowance.reason());
            // PRD-AIC-044: what left, to which provider, over the period — by data category.
            List<Map<String, Object>> egress = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT coalesce(p.label, i.model_identity), i.data_category, count(*) FILTER (WHERE i.outcome = 'OK'), "
                            + "count(*) FILTER (WHERE i.outcome = 'CACHED'), count(*) FILTER (WHERE i.outcome IN ('REFUSED', 'ERROR', 'BUDGET')), "
                            + "coalesce(sum(i.prompt_tokens + i.completion_tokens), 0), coalesce(sum(i.injection_signals), 0), max(i.created_at) "
                            + "FROM ai_invocation i LEFT JOIN ai_provider p ON p.id = i.provider_id "
                            + "WHERE i.created_at >= now() - make_interval(days => ?) GROUP BY 1, 2 ORDER BY 1, 2")) {
                statement.setInt(1, Math.max(1, Math.min(days, 365)));
                try (ResultSet r = statement.executeQuery()) {
                    while (r.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("provider", r.getString(1));
                        row.put("dataCategory", r.getString(2));
                        row.put("calls", r.getLong(3));
                        row.put("cached", r.getLong(4));
                        row.put("refused", r.getLong(5));
                        row.put("tokens", r.getLong(6));
                        row.put("injectionSignals", r.getLong(7));
                        row.put("lastAt", r.getTimestamp(8) == null ? null : r.getTimestamp(8).toInstant().toString());
                        egress.add(row);
                    }
                }
            }
            out.put("egress", egress);
            out.put("days", days);
            List<Map<String, Object>> recent = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT i.id, i.capability, coalesce(pr.display_name, pr.username, ''), i.model_identity, i.prompt_version, i.data_category, i.outcome, i.refusal_code, "
                            + "i.prompt_tokens, i.completion_tokens, i.latency_ms, i.cached, i.injection_signals, i.created_at "
                            + "FROM ai_invocation i LEFT JOIN principal pr ON pr.id = i.principal_id ORDER BY i.created_at DESC LIMIT 50");
                    ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", r.getString(1));
                    row.put("capability", r.getString(2));
                    row.put("by", r.getString(3));
                    row.put("modelIdentity", r.getString(4));
                    row.put("promptVersion", r.getString(5));
                    row.put("dataCategory", r.getString(6));
                    row.put("outcome", r.getString(7));
                    row.put("refusalCode", r.getString(8));
                    row.put("promptTokens", r.getInt(9));
                    row.put("completionTokens", r.getInt(10));
                    row.put("latencyMs", r.getInt(11));
                    row.put("cached", r.getBoolean(12));
                    row.put("injectionSignals", r.getInt(13));
                    row.put("at", r.getTimestamp(14).toInstant().toString());
                    recent.add(row);
                }
            }
            out.put("recent", recent);
        }
        return out;
    }
}
