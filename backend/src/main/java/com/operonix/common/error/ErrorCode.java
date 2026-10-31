package com.operonix.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Access token expired"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Invalid refresh token"),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "Refresh token reuse detected"),
    WORKSPACE_REQUIRED(HttpStatus.BAD_REQUEST, "Workspace selection required"),
    WORKSPACE_NOT_FOUND(HttpStatus.NOT_FOUND, "Workspace not found for this account"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "Conflict"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email already registered"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid state transition"),
    IMMUTABLE_RESOURCE(HttpStatus.CONFLICT, "Resource can no longer be modified"),
    JOB_SCHEDULE_CONFLICT(HttpStatus.CONFLICT, "Technician schedule conflict"),
    LAST_OWNER_PROTECTED(HttpStatus.CONFLICT, "Workspace must keep an active owner"),
    SELF_MODIFICATION(HttpStatus.CONFLICT, "You cannot change your own access"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Resource was modified concurrently"),
    PAYMENT_EXCEEDS_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "Payment exceeds invoice balance"),
    FX_RATE_MISSING(HttpStatus.UNPROCESSABLE_ENTITY, "Exchange rate missing"),
    INVOICE_HAS_PAYMENTS(HttpStatus.CONFLICT, "Invoice has payments recorded"),
    NO_INVOICEABLE_LINES(HttpStatus.UNPROCESSABLE_ENTITY, "No invoiceable lines"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String typeUri() {
        return "https://operonix.app/errors/" + name().toLowerCase().replace('_', '-');
    }
}
