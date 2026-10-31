package com.operonix.common.api;

import com.operonix.common.error.ApiException;
import com.operonix.common.error.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.Map;

public final class PageRequests {

    public static final int MAX_PAGE_SIZE = 100;

    private PageRequests() {
    }

    /**
     * Parses a whitelisted {@code field,asc|desc} sort string. Unknown fields are rejected rather
     * than silently ignored so clients cannot probe column names or trigger expensive sorts.
     */
    public static Pageable of(int page, int size, String sort, Map<String, String> allowedSorts,
                              String defaultSort) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        String spec = (sort == null || sort.isBlank()) ? defaultSort : sort;
        String[] parts = spec.split(",", 2);
        String requestedField = parts[0].trim();
        String property = allowedSorts.get(requestedField);
        if (property == null) {
            throw ApiException.validation("sort", "unsupported sort field: " + requestedField);
        }
        Sort.Direction direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return PageRequest.of(safePage, safeSize, Sort.by(direction, property));
    }

    public static String normaliseStatus(String status) {
        return status == null ? null : status.trim().toUpperCase(Locale.ROOT);
    }

    public static String containsPattern(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
    }

    public static void requireKnownSort(Map<String, String> allowed, String field) {
        if (!allowed.containsKey(field)) {
            throw ApiException.validation("sort", "unsupported sort field: " + field);
        }
    }
}
