package com.operonix.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI configuration.
 *
 * <p>{@code apiKey} is supplied by Secret Manager in production and is never logged, never returned
 * by an endpoint and never sent to the browser. With no key the whole feature degrades: the
 * deterministic explanations and every non-AI screen keep working.
 *
 * @param enabled       workspace-independent kill switch for the provider call path
 * @param provider      provider id; only {@code gemini} is implemented
 * @param model         model id used for new conversations when a workspace has no override
 * @param baseUrl       provider endpoint root, so a proxy can be substituted without a rebuild
 * @param timeout       hard timeout for a provider round trip
 * @param maxOutputTokens ceiling on the reply length, which also caps cost
 */
@ConfigurationProperties(prefix = "operonix.ai")
public record AiProperties(
        boolean enabled,
        String provider,
        String apiKey,
        String model,
        String baseUrl,
        Duration timeout,
        int maxOutputTokens,
        int defaultDailyQueryLimit
) {

    public AiProperties {
        provider = provider == null || provider.isBlank() ? "gemini" : provider;
        model = model == null || model.isBlank() ? "gemini-2.0-flash" : model;
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://generativelanguage.googleapis.com" : baseUrl;
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        maxOutputTokens = maxOutputTokens <= 0 ? 700 : maxOutputTokens;
        defaultDailyQueryLimit = defaultDailyQueryLimit <= 0 ? 100 : defaultDailyQueryLimit;
    }

    /** True only when the feature is switched on and a key is present. */
    public boolean usable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
