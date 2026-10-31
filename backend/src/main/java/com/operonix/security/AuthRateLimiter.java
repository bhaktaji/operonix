package com.operonix.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared fixed-window limiter for authentication endpoints (LLD §9.3, P0-4).
 *
 * <p>The previous implementation counted in a per-process map, so N Cloud Run instances allowed N× the
 * intended attempts — protection that disappears exactly when the service scales. Counters now live in
 * PostgreSQL, are incremented atomically per window, and therefore hold across instances.
 *
 * <p>Fixed windows are deliberate: they can be one request burstier than a sliding window at a boundary,
 * which is an acceptable trade for a single atomic statement and no cross-instance coordination.
 */
@Component
public class AuthRateLimiter {

    private static final long CLEANUP_INTERVAL = 500;
    private static final Duration RETENTION = Duration.ofDays(1);

    private static final String UPSERT = """
            insert into rate_limit_counter (bucket, window_start, hits)
            values (?, ?, 1)
            on conflict (bucket, window_start)
            do update set hits = rate_limit_counter.hits + 1
            returning hits
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AtomicLong calls = new AtomicLong();

    public AuthRateLimiter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean tryAcquire(String key, int limit, Duration window) {
        Instant windowStart = windowStart(window);
        Integer hits = jdbc.queryForObject(UPSERT, Integer.class, key, Timestamp.from(windowStart));
        maybeCleanup();
        return hits == null || hits <= limit;
    }

    public long retryAfterSeconds(String key, Duration window) {
        Instant start = windowStart(window);
        long millis = start.plus(window).toEpochMilli() - clock.millis();
        return Math.max(1, (millis + 999) / 1000);
    }

    public void reset(String key) {
        jdbc.update("delete from rate_limit_counter where bucket = ?", key);
    }

    private Instant windowStart(Duration window) {
        long size = Math.max(1, window.toMillis());
        return Instant.ofEpochMilli((clock.millis() / size) * size);
    }

    /** Keeps the table small without a scheduler; a leftover row is harmless, an unbounded table is not. */
    private void maybeCleanup() {
        if (calls.incrementAndGet() % CLEANUP_INTERVAL != 0) {
            return;
        }
        jdbc.update("delete from rate_limit_counter where window_start < ?",
                Timestamp.from(clock.instant().minus(RETENTION)));
    }
}