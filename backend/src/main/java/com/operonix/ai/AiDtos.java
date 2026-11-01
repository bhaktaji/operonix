package com.operonix.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AiDtos {

    private AiDtos() {
    }

    public record Status(boolean enabled, boolean providerConfigured, String provider, String model,
                         int dailyQueryLimit, int usedToday, int remainingToday, int blockedToday,
                         int tokensInToday, int tokensOutToday, String note) {
    }

    public record AskRequest(
            @NotBlank @Size(max = 500) String question,
            LocalDate from,
            LocalDate to
    ) {
    }

    /** One figure, as the server computed it. */
    public record MetricReading(String key, String label, String unit, BigDecimal value,
                                boolean available, String reason) {
    }

    public record BreakdownTable(String dimension, List<Map<String, Object>> rows) {
    }

    public record AskResponse(String status, boolean answered, String question, LocalDate from,
                              LocalDate to, String currency, List<MetricReading> metrics,
                              BreakdownTable breakdown, String narrative, boolean narrativeFromModel,
                              String provider, String model, List<String> notes,
                              /** Serialised metric request, so the answer can be reproduced. */
                              String intent) {
    }

    public record QuoteAssistRequest(UUID customerId, UUID locationId, List<UUID> serviceIds,
                                     @Size(max = 500) String notes, BigDecimal targetMarginPercent) {
    }

    public record QuoteSuggestion(UUID serviceItemId, String name, String unit, BigDecimal cost,
                                  BigDecimal historicalPrice, int timesQuoted, BigDecimal quantity,
                                  BigDecimal suggestedPrice, BigDecimal suggestedTotal,
                                  BigDecimal marginPercent, String rationale) {
    }

    public record QuoteAssistResponse(List<QuoteSuggestion> suggestions, String narrative,
                                      boolean narrativeFromModel, List<String> assumptions,
                                      String disclaimer) {
    }

    public record Insight(String severity, String title, String detail, String metric, String link) {
    }

    public record InsightsResponse(String currency, LocalDate from, LocalDate to,
                                   List<Insight> insights, List<String> notes) {
    }

    public record SettingsRequest(Boolean enabled, Integer dailyQueryLimit, String model) {
    }

    public record UsageResponse(String provider, String model, boolean providerConfigured,
                                boolean enabled, int dailyQueryLimit, int usedToday,
                                int remainingToday, int tokensInToday, int tokensOutToday,
                                List<Map<String, Object>> recent) {
    }
}
