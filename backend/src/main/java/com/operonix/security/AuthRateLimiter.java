package com.operonix.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory sliding-window limiter for authentication endpoints (LLD §9.3).
 * Deliberately simple: single-region v1; replace with a shared store when the API scales out.
 */
@Component
public class AuthRateLimiter {

    private final Map<String, ConcurrentLinkedDeque<Long>> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public AuthRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        long cutoff = now - window.toMillis();
        ConcurrentLinkedDeque<Long> hits = windows.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && hits.peekFirst() < cutoff) {
                hits.pollFirst();
            }
            if (hits.size() >= limit) {
                return false;
            }
            hits.addLast(now);
            return true;
        }
    }

    public long retryAfterSeconds(String key, Duration window) {
        ConcurrentLinkedDeque<Long> hits = windows.get(key);
        if (hits == null || hits.isEmpty()) {
            return 1;
        }
        long oldest;
        synchronized (hits) {
            oldest = hits.peekFirst() == null ? clock.millis() : hits.peekFirst();
        }
        long waitMillis = oldest + window.toMillis() - clock.millis();
        return Math.max(1, (waitMillis + 999) / 1000);
    }

    public void reset(String key) {
        windows.remove(key);
    }
}
