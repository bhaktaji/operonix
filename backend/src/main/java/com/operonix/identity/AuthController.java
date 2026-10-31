package com.operonix.identity;

import com.operonix.identity.dto.AuthResponse;
import com.operonix.identity.dto.LoginRequest;
import com.operonix.identity.dto.LogoutRequest;
import com.operonix.identity.dto.MeResponse;
import com.operonix.identity.dto.RefreshRequest;
import com.operonix.identity.dto.RegisterRequest;
import com.operonix.security.AuthGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthGuard authGuard;

    public AuthController(AuthService authService, AuthGuard authGuard) {
        this.authService = authService;
        this.authGuard = authGuard;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        authGuard.checkRegister(http);
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        authGuard.checkLogin(http, request.email());
        AuthResponse response = authService.login(request);
        authGuard.loginSucceeded(http, request.email());
        return response;
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        authGuard.checkRefresh(http);
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
    }

    @GetMapping("/me")
    public MeResponse me() {
        return authService.me();
    }
}
