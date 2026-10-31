package com.operonix.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.operonix.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper mapper;

    public RestAuthenticationEntryPoint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        boolean expired = "TOKEN_EXPIRED".equals(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE));
        ErrorCode code = expired ? ErrorCode.TOKEN_EXPIRED : ErrorCode.UNAUTHENTICATED;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                expired ? "Bearer error=\"invalid_token\", error_description=\"The access token expired\""
                        : "Bearer realm=\"operonix\"");
        SecurityProblemWriter.write(response, mapper, HttpStatus.UNAUTHORIZED, code,
                expired ? "The access token has expired; refresh it and retry"
                        : "Authentication is required to access this resource");
    }
}
