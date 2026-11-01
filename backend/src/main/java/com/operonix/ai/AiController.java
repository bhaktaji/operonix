package com.operonix.ai;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operonix AI API.
 *
 * <p>Reading AI status and usage is open to anyone signed in so a technician can see the assistant is
 * off; asking questions, changing settings and generating suggestions are manager and above because
 * they expose workspace-wide performance and consume the workspace quota.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiService ai;
    private final QuoteAssistService quotes;
    private final InsightService insights;

    public AiController(AiService ai, QuoteAssistService quotes, InsightService insights) {
        this.ai = ai;
        this.quotes = quotes;
        this.insights = insights;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER','TECHNICIAN')")
    public AiDtos.Status status() {
        return ai.status();
    }

    @GetMapping("/usage")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public AiDtos.UsageResponse usage() {
        return ai.usage();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public AiDtos.Status updateSettings(@Valid @RequestBody AiDtos.SettingsRequest request) {
        return ai.updateSettings(request);
    }

    @PostMapping("/ask")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public AiDtos.AskResponse ask(@Valid @RequestBody AiDtos.AskRequest request) {
        return ai.ask(request);
    }

    @PostMapping("/quote-assist")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public AiDtos.QuoteAssistResponse quoteAssist(
            @Valid @RequestBody(required = false) AiDtos.QuoteAssistRequest request) {
        return quotes.suggest(request == null
                ? new AiDtos.QuoteAssistRequest(null, null, null, null, null)
                : request);
    }

    @GetMapping("/insights")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public AiDtos.InsightsResponse insights() {
        return insights.insights();
    }
}
