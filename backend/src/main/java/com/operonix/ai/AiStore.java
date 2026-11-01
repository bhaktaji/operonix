package com.operonix.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * AI settings, quota counters and the query log.
 *
 * <p>The quota is enforced by the same statement that increments the counter, so two concurrent
 * requests cannot both pass the last remaining query.
 */
@Repository
public class AiStore {

    private final JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public AiStore(JdbcTemplate jdbc, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Settings(boolean enabled, int dailyQueryLimit, String model) {
    }

    public record Usage(int queries, int blocked, int tokensIn, int tokensOut) {
    }

    public record LogEntry(UUID tenantId, UUID actorId, String kind, String question,
                           String metricRequest, String answer, String provider, String model,
                           String status, String errorCode, int tokensIn, int tokensOut,
                           int latencyMs) {
    }

    /** Reads the workspace's settings, creating the default row on first use. */
    public Settings settings(UUID tenantId, int defaultLimit, String defaultModel) {
        jdbc.update("""
                insert into ai_settings (tenant_id, enabled, daily_query_limit, model, created_at, updated_at)
                values (?, true, ?, ?, now(), now())
                on conflict (tenant_id) do nothing
                """, tenantId, defaultLimit, defaultModel);
        return jdbc.queryForObject("""
                        select enabled, daily_query_limit, model from ai_settings where tenant_id = ?
                        """,
                (rs, rowNum) -> new Settings(rs.getBoolean("enabled"),
                        rs.getInt("daily_query_limit"), rs.getString("model")),
                tenantId);
    }

    public Settings updateSettings(UUID tenantId, Boolean enabled, Integer dailyLimit, String model,
                                   UUID actorId, int defaultLimit, String defaultModel) {
        Settings current = settings(tenantId, defaultLimit, defaultModel);
        jdbc.update("""
                update ai_settings
                   set enabled = ?, daily_query_limit = ?, model = ?, updated_by = ?, updated_at = now()
                 where tenant_id = ?
                """, enabled == null ? current.enabled() : enabled,
                dailyLimit == null ? current.dailyQueryLimit() : dailyLimit,
                model == null || model.isBlank() ? current.model() : model.trim(),
                actorId, tenantId);
        return settings(tenantId, defaultLimit, defaultModel);
    }

    /**
     * Reserves one query for the day.
     *
     * @return true when the query was within the daily limit
     */
    public boolean consume(UUID tenantId, LocalDate day, int limit) {
        int rows = jdbc.update("""
                insert into ai_usage (tenant_id, day, queries, blocked, tokens_in, tokens_out)
                values (?, ?, 1, 0, 0, 0)
                on conflict (tenant_id, day) do update
                    set queries = ai_usage.queries + 1
                    where ai_usage.queries < ?
                """, tenantId, Date.valueOf(day), limit);
        return rows > 0;
    }

    public void recordBlocked(UUID tenantId, LocalDate day) {
        jdbc.update("""
                insert into ai_usage (tenant_id, day, queries, blocked, tokens_in, tokens_out)
                values (?, ?, 0, 1, 0, 0)
                on conflict (tenant_id, day) do update set blocked = ai_usage.blocked + 1
                """, tenantId, Date.valueOf(day));
    }

    public void addTokens(UUID tenantId, LocalDate day, int tokensIn, int tokensOut) {
        if (tokensIn <= 0 && tokensOut <= 0) {
            return;
        }
        jdbc.update("""
                insert into ai_usage (tenant_id, day, queries, blocked, tokens_in, tokens_out)
                values (?, ?, 0, 0, ?, ?)
                on conflict (tenant_id, day) do update
                    set tokens_in = ai_usage.tokens_in + excluded.tokens_in,
                        tokens_out = ai_usage.tokens_out + excluded.tokens_out
                """, tenantId, Date.valueOf(day), tokensIn, tokensOut);
    }

    public Usage usage(UUID tenantId, LocalDate day) {
        Optional<Usage> usage = jdbc.query("""
                        select queries, blocked, tokens_in, tokens_out from ai_usage
                        where tenant_id = ? and day = ?
                        """,
                (rs, rowNum) -> new Usage(rs.getInt("queries"), rs.getInt("blocked"),
                        rs.getInt("tokens_in"), rs.getInt("tokens_out")),
                tenantId, Date.valueOf(day)).stream().findFirst();
        return usage.orElse(new Usage(0, 0, 0, 0));
    }

    public void log(LogEntry entry) {
        jdbc.update("""
                insert into ai_query_log (id, tenant_id, actor_user_id, kind, question, metric_request,
                                          answer, provider, model, status, error_code, tokens_in,
                                          tokens_out, latency_ms, created_at)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), entry.tenantId(), entry.actorId(), entry.kind(),
                truncate(entry.question(), 1000), requestJson(entry.metricRequest()),
                truncate(entry.answer(), 8000),
                entry.provider(), entry.model(), entry.status(), entry.errorCode(),
                entry.tokensIn(), entry.tokensOut(), entry.latencyMs(),
                Timestamp.from(Instant.now()));
    }

    /** Recent AI activity for the usage panel. */
    public java.util.List<java.util.Map<String, Object>> recent(UUID tenantId, int limit) {
        return jdbc.query("""
                        select kind, question, status, provider, model, tokens_in, tokens_out, created_at
                        from ai_query_log
                        where tenant_id = ?
                        order by created_at desc
                        limit ?
                        """,
                (rs, rowNum) -> {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("kind", rs.getString("kind"));
                    row.put("question", rs.getString("question"));
                    row.put("status", rs.getString("status"));
                    row.put("provider", rs.getString("provider"));
                    row.put("model", rs.getString("model"));
                    row.put("tokensIn", rs.getInt("tokens_in"));
                    row.put("tokensOut", rs.getInt("tokens_out"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return row;
                },
                tenantId, limit);
    }

    /** The column is jsonb, so free text from a provider prompt is wrapped rather than rejected. */
    private String requestJson(String request) {
        if (request == null || request.isBlank()) {
            return null;
        }
        String trimmed = request.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        try {
            return mapper.writeValueAsString(java.util.Map.of("text", request));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
