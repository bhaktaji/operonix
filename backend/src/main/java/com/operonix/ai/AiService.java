package com.operonix.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operonix.analytics.AnalyticsDtos;
import com.operonix.analytics.AnalyticsService;
import com.operonix.audit.AuditAction;
import com.operonix.audit.AuditService;
import com.operonix.common.error.ApiException;
import com.operonix.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Natural-language reporting.
 *
 * <p>The contract is narrow on purpose:
 *
 * <ol>
 *   <li>The question is mapped onto catalogue metrics by a deterministic resolver — never by the
 *       model, which therefore cannot widen its own query surface.</li>
 *   <li>The server computes every figure through the same analytics path the dashboard uses.</li>
 *   <li>The model receives those figures and returns prose. A reply containing a number that is not
 *       in the payload is discarded and the deterministic summary is served instead.</li>
 * </ol>
 *
 * <p>Quotas are reserved before the provider is called, and every request — allowed, blocked,
 * degraded or failed — is logged.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final int MAX_QUESTION = 500;

    private final AiStore store;
    private final AiProperties properties;
    private final LlmProvider provider;
    private final AnalyticsService analytics;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AiService(AiStore store, AiProperties properties, LlmProvider provider,
                     AnalyticsService analytics, CurrentUser currentUser, AuditService audit,
                     ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.provider = provider;
        this.analytics = analytics;
        this.currentUser = currentUser;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ================================================================ settings and status

    @Transactional
    public AiDtos.Status status() {
        UUID tenantId = currentUser.tenantId();
        AiStore.Settings settings = settingsFor(tenantId);
        AiStore.Usage usage = store.usage(tenantId, LocalDate.now(clock));
        boolean configured = properties.enabled() && provider.configured();
        int remaining = Math.max(0, settings.dailyQueryLimit() - usage.queries());
        return new AiDtos.Status(settings.enabled(), configured, provider.name(), settings.model(),
                settings.dailyQueryLimit(), usage.queries(), remaining, usage.blocked(),
                usage.tokensIn(), usage.tokensOut(), note(settings, configured));
    }

    @Transactional
    public AiDtos.Status updateSettings(AiDtos.SettingsRequest request) {
        UUID tenantId = currentUser.tenantId();
        if (request.dailyQueryLimit() != null
                && (request.dailyQueryLimit() < 0 || request.dailyQueryLimit() > 10_000)) {
            throw ApiException.validation("dailyQueryLimit", "must be between 0 and 10000");
        }
        AiStore.Settings updated = store.updateSettings(tenantId, request.enabled(),
                request.dailyQueryLimit(), request.model(), currentUser.id(),
                properties.defaultDailyQueryLimit(), properties.model());
        audit.record(AuditAction.AI_SETTINGS_UPDATED, "ai_settings", null,
                Map.of("enabled", updated.enabled(), "dailyQueryLimit", updated.dailyQueryLimit()));
        return status();
    }

    @Transactional
    public AiDtos.UsageResponse usage() {
        UUID tenantId = currentUser.tenantId();
        AiStore.Settings settings = settingsFor(tenantId);
        AiStore.Usage usage = store.usage(tenantId, LocalDate.now(clock));
        return new AiDtos.UsageResponse(provider.name(), settings.model(),
                properties.enabled() && provider.configured(), settings.enabled(),
                settings.dailyQueryLimit(), usage.queries(),
                Math.max(0, settings.dailyQueryLimit() - usage.queries()), usage.tokensIn(),
                usage.tokensOut(), store.recent(tenantId, 20));
    }

    private AiStore.Settings settingsFor(UUID tenantId) {
        return store.settings(tenantId, properties.defaultDailyQueryLimit(), properties.model());
    }

    private static String note(AiStore.Settings settings, boolean configured) {
        if (!settings.enabled()) {
            return "Operonix AI is turned off for this workspace. Analytics remain available.";
        }
        if (!configured) {
            return "No AI provider key is configured, so answers use the built-in explanations."
                    + " All figures are still calculated by Operonix.";
        }
        return "Answers are written by " + "Gemini" + " from figures calculated by Operonix.";
    }

    // ================================================================ ask

    @Transactional
    public AiDtos.AskResponse ask(AiDtos.AskRequest request) {
        UUID tenantId = currentUser.tenantId();
        LocalDate today = LocalDate.now(clock);
        AiStore.Settings settings = settingsFor(tenantId);
        String question = request.question().trim();
        if (question.length() > MAX_QUESTION) {
            throw ApiException.validation("question", "must be 500 characters or fewer");
        }

        AiIntent intent = AiIntent.resolve(question, today);
        LocalDate from = request.from() == null ? intent.from() : request.from();
        LocalDate to = request.to() == null ? intent.to() : request.to();

        if (!settings.enabled() || !properties.enabled()) {
            store.log(entry(tenantId, "REPORTING", question, null, null, "DISABLED", null, 0));
            return response("DISABLED", false, question,
                    new AiIntent(intent.metrics(), from, to, intent.dimension(), intent.groupBy(),
                            intent.matched(), intent.topic()), analytics.scope().currency(), null, null,
                    "Operonix AI is turned off for this workspace. Open Analytics to see the same"
                            + " figures without the assistant.", false, List.of(
                            "Nothing was sent to a language model."));
        }

        if (!store.consume(tenantId, today, settings.dailyQueryLimit())) {
            store.recordBlocked(tenantId, today);
            store.log(entry(tenantId, "REPORTING", question, null, null, "QUOTA_EXCEEDED", null, 0));
            audit.record(AuditAction.AI_QUERY_ASKED, "ai_usage", null,
                    Map.of("status", "QUOTA_EXCEEDED"));
            return response("QUOTA_EXCEEDED", false, question,
                    new AiIntent(intent.metrics(), from, to, intent.dimension(), intent.groupBy(),
                            intent.matched(), intent.topic()), analytics.scope().currency(), null, null,
                    "The daily AI limit of " + settings.dailyQueryLimit()
                            + " queries has been reached. Analytics remain fully available.", false,
                    List.of("Quota resets at midnight in the workspace timezone."));
        }

        // Everything below is computed by the server before any provider call.
        long started = System.currentTimeMillis();
        AnalyticsDtos.Overview overview = analytics.overview(from, to, null);
        List<AnalyticsDtos.MetricValue> selected = overview.metrics().stream()
                .filter(metric -> intent.metrics().contains(metric.key()))
                .toList();
        if (selected.isEmpty()) {
            selected = overview.metrics();
        }

        List<AiDtos.MetricReading> readings = selected.stream()
                .map(metric -> new AiDtos.MetricReading(metric.key(), metric.label(), metric.unit(),
                        metric.value(), metric.available(), metric.reason()))
                .toList();

        AiDtos.BreakdownTable table = null;
        if (intent.dimension() != null) {
            AnalyticsDtos.Breakdown breakdown = analytics.breakdown(intent.dimension(), from, to);
            List<Map<String, Object>> rows = breakdown.rows().stream().limit(10).map(row -> {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("label", row.label());
                values.put("revenue", row.revenue());
                values.put("cost", row.cost());
                values.put("profit", row.profit());
                values.put("profitAvailable", row.profitAvailable());
                values.put("count", row.count());
                values.put("minutes", row.minutes());
                values.putAll(row.dimensions());
                return values;
            }).toList();
            table = new AiDtos.BreakdownTable(intent.dimension(), rows);
        }

        String deterministic = summarise(readings, table, overview.currency(), from, to);
        String payload = json(readings, table, overview.currency(), from, to);
        String narrative = deterministic;
        boolean fromModel = false;
        String status = "OK";
        List<String> notes = new ArrayList<>();

        if (!provider.configured()) {
            status = "DEGRADED";
            notes.add("No AI provider is configured; this is Operonix's built-in explanation of the"
                    + " figures above.");
        } else {
            try {
                LlmProvider.Completion completion = provider.complete(
                        prompt(question, payload), properties.maxOutputTokens());
                store.addTokens(tenantId, today, completion.inputTokens(), completion.outputTokens());
                if (grounded(completion.text(), payload)) {
                    narrative = completion.text();
                    fromModel = true;
                } else {
                    status = "DEGRADED";
                    notes.add("The AI reply mentioned a figure that Operonix did not calculate, so it"
                            + " was replaced with the verified summary.");
                }
            } catch (LlmProvider.LlmUnavailableException ex) {
                status = "DEGRADED";
                notes.add("The AI provider is unavailable right now. These figures are unaffected.");
            }
        }

        notes.add("Outstanding and overdue are balances as at " + to + ".");
        store.log(entryWithLatency(tenantId, "REPORTING", question,
                json(intent), narrative, status, null, (int) (System.currentTimeMillis() - started)));
        audit.record(AuditAction.AI_QUERY_ASKED, "ai_query", null,
                Map.of("status", status, "metrics", intent.metrics().size()));

        return response(status, true, question,
                new AiIntent(intent.metrics(), from, to, intent.dimension(), intent.groupBy(),
                        intent.matched(), intent.topic()),
                overview.currency(), readings, table, narrative, fromModel, notes);
    }

    private AiDtos.AskResponse response(String status, boolean answered, String question,
                                        AiIntent intent, String currency,
                                        List<AiDtos.MetricReading> metrics,
                                        AiDtos.BreakdownTable table, String narrative,
                                        boolean fromModel, List<String> notes) {
        return new AiDtos.AskResponse(status, answered, question, intent.from(), intent.to(),
                currency, metrics, table, narrative, fromModel, provider.name(),
                settingsModel(), notes, json(intent));
    }

    private String settingsModel() {
        return settingsFor(currentUser.tenantId()).model();
    }

    // ================================================================ narrative

    private String summarise(List<AiDtos.MetricReading> readings, AiDtos.BreakdownTable table,
                             String currency, LocalDate from, LocalDate to) {
        StringBuilder text = new StringBuilder("Between " + from + " and " + to + ": ");
        List<String> parts = new ArrayList<>();
        for (AiDtos.MetricReading reading : readings) {
            if (reading.available() && reading.value() != null) {
                parts.add(reading.label().toLowerCase(Locale.ROOT) + " " + format(reading.value(),
                        reading.unit(), currency));
            } else {
                parts.add(reading.label().toLowerCase(Locale.ROOT) + " is not available ("
                        + (reading.reason() == null ? "insufficient data" : reading.reason()) + ")");
            }
        }
        text.append(String.join("; ", parts)).append(".");
        if (table != null && !table.rows().isEmpty()) {
            Object top = table.rows().get(0).get("label");
            text.append(" The largest ").append(table.dimension().toLowerCase(Locale.ROOT))
                    .append(" by revenue is ").append(top).append(".");
        }
        return text.toString();
    }

    private String format(BigDecimal value, String unit, String currency) {
        NumberFormat money = NumberFormat.getCurrencyInstance(Locale.US);
        money.setCurrency(java.util.Currency.getInstance(currency));
        return switch (unit) {
            case "CURRENCY" -> money.format(value);
            case "PERCENT" -> value.stripTrailingZeros().toPlainString() + "%";
            case "MINUTES" -> value.stripTrailingZeros().toPlainString() + " minutes";
            default -> value.stripTrailingZeros().toPlainString();
        };
    }

    private List<LlmProvider.Message> prompt(String question, String payload) {
        String system = """
                You are the analyst inside Operonix, a business management platform.
                You will be given figures that the server has already calculated.
                Rules, without exception:
                - Never invent, estimate, re-calculate or extrapolate a number.
                - Only mention numbers that appear in the supplied data.
                - If a figure is marked unavailable, say it is unavailable and why; do not treat it as zero.
                - Answer in at most four sentences of plain prose. No markdown, no lists, no headings.
                - Do not give accounting, tax or legal advice.
                """;
        String user = "Question: " + question + "\n\nServer-calculated data (JSON):\n" + payload;
        return List.of(new LlmProvider.Message(LlmProvider.Role.SYSTEM, system),
                new LlmProvider.Message(LlmProvider.Role.USER, user));
    }

    /**
     * Rejects a model reply that contains a figure the server did not supply. Small integers (day
     * numbers, counts, "top 3") and 4-digit years are allowed because they appear in ordinary prose.
     */
    static boolean grounded(String narrative, String payload) {
        if (narrative == null || narrative.isBlank()) {
            return false;
        }
        Matcher matcher = NUMBER.matcher(narrative);
        while (matcher.find()) {
            String token = matcher.group().replace(",", "");
            if (payload.contains(token)) {
                continue;
            }
            try {
                BigDecimal value = new BigDecimal(token);
                if (value.abs().compareTo(BigDecimal.valueOf(1000)) <= 0
                        && value.stripTrailingZeros().scale() <= 0) {
                    continue;
                }
                if (value.scale() <= 0 && value.compareTo(BigDecimal.valueOf(1900)) >= 0
                        && value.compareTo(BigDecimal.valueOf(2200)) <= 0) {
                    continue;
                }
            } catch (NumberFormatException ignored) {
                continue;
            }
            return false;
        }
        return true;
    }

    private String json(Object... parts) {
        try {
            return mapper.writeValueAsString(parts);
        } catch (Exception ex) {
            log.warn("Could not serialise AI payload", ex);
            return "{}";
        }
    }

    private AiStore.LogEntry entry(UUID tenantId, String kind, String question, String request,
                                   String answer, String status, String errorCode, int latency) {
        return new AiStore.LogEntry(tenantId, currentUser.optional().map(u -> u.id()).orElse(null),
                kind, question, request, answer, provider.name(), settingsModel(), status,
                errorCode, 0, 0, latency);
    }

    private AiStore.LogEntry entryWithLatency(UUID tenantId, String kind, String question,
                                              String request, String answer, String status,
                                              String errorCode, int latency) {
        return new AiStore.LogEntry(tenantId, currentUser.optional().map(u -> u.id()).orElse(null),
                kind, question, request, answer, provider.name(), settingsModel(), status,
                errorCode, 0, 0, latency);
    }

    /** Exposed so the quote assistant can share settings and quota handling decisions. */
    AiStore store() {
        return store;
    }

    AiProperties properties() {
        return properties;
    }

    LlmProvider provider() {
        return provider;
    }

    AnalyticsService analytics() {
        return analytics;
    }

    void recordDisabled(String kind, String question) {
        store.log(entry(currentUser.tenantId(), kind, question, null, null, "DISABLED", null, 0));
    }

    boolean allowedToday(AiStore.Settings settings) {
        return settings.enabled() && properties.enabled()
                && store.consume(currentUser.tenantId(), LocalDate.now(clock),
                        settings.dailyQueryLimit());
    }

    void logCall(String kind, String question, String request, String answer, String status,
                 int latency) {
        store.log(entryWithLatency(currentUser.tenantId(), kind, question, request, answer, status,
                null, latency));
    }
}
