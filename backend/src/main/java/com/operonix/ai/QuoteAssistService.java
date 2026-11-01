package com.operonix.ai;

import com.operonix.catalog.ServiceItem;
import com.operonix.catalog.ServiceItemRepository;
import com.operonix.common.money.MoneyCalculator;
import com.operonix.security.CurrentUser;
import com.operonix.tenancy.Tenant;
import com.operonix.tenancy.TenantService;
import com.operonix.global.Currency;
import com.operonix.global.ReferenceDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Quotation assistance.
 *
 * <p>Suggestions are assembled from the catalogue, each item's planned cost and the prices this
 * workspace has actually quoted in the last year. The model may only comment on that assembled list.
 * Nothing here writes a quote: the user still chooses what to send.
 */
@Service
public class QuoteAssistService {

    private static final Logger log = LoggerFactory.getLogger(QuoteAssistService.class);
    private static final BigDecimal DEFAULT_MARGIN = BigDecimal.valueOf(30);
    private static final int MAX_ITEMS = 8;

    private final ServiceItemRepository items;
    private final JdbcTemplate jdbc;
    private final TenantService tenants;
    private final ReferenceDataService referenceData;
    private final AiService ai;
    private final AiStore store;
    private final CurrentUser currentUser;
    private final Clock clock;

    public QuoteAssistService(ServiceItemRepository items, JdbcTemplate jdbc, TenantService tenants,
                              ReferenceDataService referenceData, AiService ai, AiStore store,
                              CurrentUser currentUser, Clock clock) {
        this.items = items;
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.referenceData = referenceData;
        this.ai = ai;
        this.store = store;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    public AiDtos.QuoteAssistResponse suggest(AiDtos.QuoteAssistRequest request) {
        UUID tenantId = currentUser.tenantId();
        Tenant tenant = tenants.require(tenantId);
        Currency currency = referenceData.requireCurrency(tenant.getCurrency());
        int scale = currency.getMinorUnits();
        BigDecimal margin = request == null || request.targetMarginPercent() == null
                ? DEFAULT_MARGIN : request.targetMarginPercent();
        if (margin.signum() < 0 || margin.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw com.operonix.common.error.ApiException.validation("targetMarginPercent",
                    "must be between 0 and 90");
        }
        BigDecimal targetMargin = margin.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

        List<ServiceItem> catalogue = items.search(tenantId, true, null);
        if (request != null && request.serviceIds() != null && !request.serviceIds().isEmpty()) {
            catalogue = catalogue.stream()
                    .filter(item -> request.serviceIds().contains(item.getId()))
                    .toList();
        }

        Map<UUID, History> history = history(tenantId);
        List<AiDtos.QuoteSuggestion> suggestions = new ArrayList<>();
        for (ServiceItem item : catalogue.stream().limit(MAX_ITEMS).toList()) {
            History past = history.get(item.getId());
            BigDecimal cost = item.getCost() == null ? BigDecimal.ZERO : item.getCost();
            BigDecimal historical = past == null ? null : past.averagePrice();
            BigDecimal suggested = suggestPrice(cost, historical, targetMargin, scale);
            BigDecimal total = suggested.multiply(BigDecimal.ONE).setScale(scale, RoundingMode.HALF_UP);
            BigDecimal realised = suggested.signum() > 0 && cost.signum() > 0
                    ? suggested.subtract(cost).multiply(BigDecimal.valueOf(100))
                            .divide(suggested, 1, RoundingMode.HALF_UP)
                    : null;
            suggestions.add(new AiDtos.QuoteSuggestion(item.getId(), item.getName(),
                    item.getUnit().name(), MoneyCalculator.money(cost, scale), historical,
                    past == null ? 0 : past.count(), BigDecimal.ONE,
                    MoneyCalculator.money(suggested, scale), total, realised,
                    rationale(cost, historical, suggested, targetMargin, currency.getCode())));
        }
        suggestions.sort(Comparator.comparing(AiDtos.QuoteSuggestion::name));

        List<String> assumptions = new ArrayList<>();
        assumptions.add("Price guidance uses this item's planned cost and the average price you have"
                + " actually quoted over the last 12 months.");
        assumptions.add("Target margin applied: " + margin.stripTrailingZeros().toPlainString() + "%.");
        assumptions.add("Cost of zero means no planned cost is recorded, so the historical price is"
                + " used unchanged.");
        assumptions.add("Amounts are in " + currency.getCode() + ".");
        String disclaimer = "Suggestions only. No quote was created or changed — review each line"
                + " before sending anything to a customer.";

        String narrative = deterministicNarrative(suggestions, currency.getCode());
        boolean fromModel = false;
        AiStore.Settings settings = store.settings(tenantId, 100, ai.properties().model());
        if (suggestions.isEmpty()) {
            narrative = "No active catalogue items are available yet, so there is nothing to suggest."
                    + " Add services to your catalogue first.";
        } else if (ai.allowedToday(settings)) {
            try {
                String payload = payload(suggestions, assumptions);
                LlmProvider.Completion completion = ai.provider().complete(List.of(
                        new LlmProvider.Message(LlmProvider.Role.SYSTEM, """
                                You are Operonix's quoting assistant. You are given a list of service
                                items with prices already decided by the server. Explain briefly how to
                                structure the quote and what to double-check. Never change, invent or
                                recalculate a price. Never promise the customer anything. At most four
                                sentences of plain prose, no markdown.
                                """),
                        new LlmProvider.Message(LlmProvider.Role.USER, payload)),
                        ai.properties().maxOutputTokens());
                store.addTokens(tenantId, java.time.LocalDate.now(clock), completion.inputTokens(),
                        completion.outputTokens());
                if (AiService.grounded(completion.text(), payload)) {
                    narrative = completion.text();
                    fromModel = true;
                }
            } catch (LlmProvider.LlmUnavailableException ex) {
                log.debug("Quote assistance fell back to the deterministic narrative");
            }
        } else if (!settings.enabled()) {
            assumptions.add("The AI assistant is off; these are Operonix's own recommendations.");
        } else {
            assumptions.add("The daily AI limit has been reached, so the built-in explanation is shown.");
        }

        ai.logCall("QUOTE_ASSIST", request == null ? null : request.notes(),
                payload(suggestions, assumptions), narrative, fromModel ? "OK" : "DEGRADED", 0);
        return new AiDtos.QuoteAssistResponse(suggestions, narrative, fromModel, assumptions,
                disclaimer);
    }

    private record History(BigDecimal averagePrice, int count) {
    }

    private Map<UUID, History> history(UUID tenantId) {
        Map<UUID, History> result = new LinkedHashMap<>();
        jdbc.query("""
                        select l.service_item_id, avg(l.unit_price) as avg_price, count(*) as times
                        from quote_line l
                        join quote q on q.id = l.quote_id and q.tenant_id = l.tenant_id
                        where q.tenant_id = ? and q.created_at >= ?
                        group by l.service_item_id
                        """,
                rs -> {
                    result.put(rs.getObject("service_item_id", UUID.class),
                            new History(rs.getBigDecimal("avg_price"), rs.getInt("times")));
                },
                tenantId, java.sql.Timestamp.from(
                        java.time.Instant.now(clock).minus(java.time.Duration.ofDays(365))));
        return result;
    }

    private BigDecimal suggestPrice(BigDecimal cost, BigDecimal historical, BigDecimal targetMargin,
                                    int scale) {
        BigDecimal byMargin = targetMargin.compareTo(BigDecimal.ONE) >= 0
                ? cost
                : cost.divide(BigDecimal.ONE.subtract(targetMargin), scale + 2, RoundingMode.HALF_UP);
        if (historical == null) {
            return MoneyCalculator.money(byMargin, scale);
        }
        if (cost.signum() <= 0) {
            return MoneyCalculator.money(historical, scale);
        }
        // Never suggest below cost; otherwise prefer what the market has already accepted.
        return MoneyCalculator.money(historical.max(byMargin).max(cost), scale);
    }

    private String rationale(BigDecimal cost, BigDecimal historical, BigDecimal suggested,
                             BigDecimal targetMargin, String currency) {
        if (cost.signum() <= 0) {
            return "No planned cost recorded; based on historical pricing only.";
        }
        if (historical == null) {
            return "No quotation history; priced at a " + targetMargin.multiply(BigDecimal.valueOf(100))
                    .stripTrailingZeros().toPlainString() + "% margin over cost.";
        }
        return "Blends the recorded cost with the average price accepted in the last 12 months.";
    }

    private String deterministicNarrative(List<AiDtos.QuoteSuggestion> suggestions, String currency) {
        if (suggestions.isEmpty()) {
            return "No active catalogue items are available yet.";
        }
        AiDtos.QuoteSuggestion top = suggestions.get(0);
        return "Drafted " + suggestions.size() + " line suggestion(s). "
                + "Start with " + top.name() + " at " + top.suggestedPrice().toPlainString() + " "
                + currency + "; confirm the quantity on site before sending. Every price is a starting"
                + " point you can change.";
    }

    private String payload(List<AiDtos.QuoteSuggestion> suggestions, List<String> assumptions) {
        StringBuilder text = new StringBuilder("Service items:\n");
        for (AiDtos.QuoteSuggestion suggestion : suggestions) {
            text.append("- ").append(suggestion.name())
                    .append(" | cost ").append(suggestion.cost())
                    .append(" | historical ").append(suggestion.historicalPrice())
                    .append(" | suggested ").append(suggestion.suggestedPrice())
                    .append(" | margin% ").append(suggestion.marginPercent())
                    .append("\n");
        }
        text.append("\nAssumptions: ").append(String.join(" ", assumptions));
        return text.toString();
    }
}
