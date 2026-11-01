package com.operonix.global;

public enum ExpenseCategory {
    LABOR,
    MATERIAL,
    EQUIPMENT,
    SUBCONTRACTOR,
    OTHER;

    public static ExpenseCategory parse(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw com.operonix.common.error.ApiException.of(
                    com.operonix.common.error.ErrorCode.VALIDATION_FAILED,
                    "Unknown expense category: " + value);
        }
    }
}
