package com.operonix.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operonix.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writes RFC 7807 responses for failures raised inside the security filter chain. */
final class SecurityProblemWriter {

    private SecurityProblemWriter() {
    }

    static void write(HttpServletResponse response, ObjectMapper mapper, HttpStatus status,
                      ErrorCode code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", code.typeUri());
        body.put("title", code.title());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("code", code.name());
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        mapper.writeValue(response.getOutputStream(), body);
    }
}
