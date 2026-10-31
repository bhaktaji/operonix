package com.operonix.security;

import com.operonix.common.error.ApiException;
import com.operonix.common.error.ErrorCode;
import com.operonix.config.OperonixProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

@Component
public class AuthGuard {

    private static final int REFRESH_ATTEMPTS = 30;
    private static final int REGISTER_ATTEMPTS = 60;

    private final AuthRateLimiter limiter;
    private final OperonixProperties properties;

    public AuthGuard(AuthRateLimiter limiter, OperonixProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    public void checkLogin(HttpServletRequest request, String email) {
        String key = loginKey(request, email);
        int limit = properties.rateLimit().loginAttempts();
        Duration window = properties.rateLimit().loginWindow();
        if (!limiter.tryAcquire(key, limit, window)) {
            throw ApiException.of(ErrorCode.RATE_LIMITED, "Too many sign-in attempts. Try again later.")
                    .withProperty("retryAfter", limiter.retryAfterSeconds(key, window));
        }
    }

    public void loginSucceeded(HttpServletRequest request, String email) {
        limiter.reset(loginKey(request, email));
    }

    public void checkRefresh(HttpServletRequest request) {
        Duration window = properties.rateLimit().loginWindow();
        String key = "refresh:" + clientIp(request);
        if (!limiter.tryAcquire(key, REFRESH_ATTEMPTS, window)) {
            throw ApiException.of(ErrorCode.RATE_LIMITED, "Too many token refreshes. Try again later.")
                    .withProperty("retryAfter", limiter.retryAfterSeconds(key, window));
        }
    }

    public void checkRegister(HttpServletRequest request) {
        Duration window = Duration.ofHours(1);
        String key = "register:" + clientIp(request);
        if (!limiter.tryAcquire(key, REGISTER_ATTEMPTS, window)) {
            throw ApiException.of(ErrorCode.RATE_LIMITED, "Too many workspaces created from this address.")
                    .withProperty("retryAfter", limiter.retryAfterSeconds(key, window));
        }
    }

    private String loginKey(HttpServletRequest request, String email) {
        return "login:" + clientIp(request) + ":" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    private String clientIp(HttpServletRequest request) {
        // Deliberately ignores X-Forwarded-For, which is client-controlled unless a trusted proxy
        // is configured. Remote address is the safe default for v1.
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
