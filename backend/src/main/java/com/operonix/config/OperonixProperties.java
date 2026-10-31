package com.operonix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "operonix")
public record OperonixProperties(
        Jwt jwt,
        Cors cors,
        RateLimit rateLimit,
        ApiDocs apiDocs,
        @DefaultValue Web web,
        @DefaultValue("14") int defaultDueDays,
        @DefaultValue("14") int quoteValidityDays,
        @DefaultValue("26") int maxSeriesOccurrences
) {
    public record Jwt(
            String secret,
            String issuer,
            String audience,
            @DefaultValue("15m") Duration accessTokenTtl,
            @DefaultValue("30d") Duration refreshTokenTtl
    ) {
    }

    public record Cors(@DefaultValue("http://localhost:5173") List<String> allowedOrigins) {
    }

    public record RateLimit(
            @DefaultValue("10") int loginAttempts,
            @DefaultValue("5m") Duration loginWindow
    ) {
    }

    public record ApiDocs(@DefaultValue("true") boolean enabled) {
    }

    /** Location of the built SPA bundle served by {@code WebStaticConfig}. */
    public record Web(@DefaultValue("classpath:/static/") List<String> staticLocations) {
    }
}
