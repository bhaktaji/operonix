package com.operonix.common.error;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final HttpStatus status;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ApiException(ErrorCode code, HttpStatus status, String detail) {
        super(detail);
        this.code = code;
        this.status = status;
    }

    public static ApiException of(ErrorCode code, String detail) {
        return new ApiException(code, code.status(), detail);
    }

    public static ApiException validation(String field, String message) {
        return of(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid")
                .withProperty("errors", java.util.List.of(Map.of("field", field, "message", message)));
    }

    public static ApiException notFound(String resource) {
        return of(ErrorCode.NOT_FOUND, resource + " not found");
    }

    public static ApiException forbidden(String detail) {
        return of(ErrorCode.FORBIDDEN, detail);
    }

    public static ApiException conflict(ErrorCode code, String detail) {
        return of(code, detail);
    }

    public static ApiException immutable(String detail) {
        return of(ErrorCode.IMMUTABLE_RESOURCE, detail);
    }

    public ApiException withProperty(String key, Object value) {
        this.properties.put(key, value);
        return this;
    }

    public ErrorCode code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
