package com.operonix.identity;

import com.operonix.audit.AuditAction;
import com.operonix.audit.AuditService;
import com.operonix.common.error.ApiException;
import com.operonix.common.error.ErrorCode;
import com.operonix.identity.dto.AuthResponse;
import com.operonix.identity.dto.LoginRequest;
import com.operonix.identity.dto.MeResponse;
import com.operonix.identity.dto.RegisterRequest;
import com.operonix.identity.dto.UserSummary;
import com.operonix.security.AuthenticatedUser;
import com.operonix.security.CurrentUser;
import com.operonix.security.JwtService;
import com.operonix.security.TenantContext;
import com.operonix.tenancy.Tenant;
import com.operonix.tenancy.TenantService;
import com.operonix.tenancy.dto.TenantSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int REFRESH_BYTES = 32;

    private final AppUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final SessionRevocationService sessionRevocation;
    private final TenantService tenantService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService(AppUserRepository users, RefreshTokenRepository refreshTokens,
                       SessionRevocationService sessionRevocation, TenantService tenantService,
                       PasswordEncoder passwordEncoder, JwtService jwtService, AuditService audit,
                       CurrentUser currentUser, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.sessionRevocation = sessionRevocation;
        this.tenantService = tenantService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
        this.currentUser = currentUser;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("operonix-timing-equaliser-7f3a");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normaliseEmail(request.email());
        if (users.existsByEmail(email)) {
            throw ApiException.of(ErrorCode.EMAIL_ALREADY_EXISTS, "An account with this email already exists");
        }
        Tenant tenant = tenantService.provision(request.orgName());
        AppUser user = new AppUser();
        user.setTenantId(tenant.getId());
        user.setEmail(email);
        user.setFullName(request.fullName().trim());
        user.setRole(Role.OWNER);
        user.setActive(true);
        user.setPasswordHash(passwordEncoder.encode(request.password()));

        TenantContext.runWith(tenant.getId(), () -> {
            users.save(user);
            return null;
        });

        audit.record(tenant.getId(), user.getId(), AuditAction.TENANT_CREATED, "tenant", tenant.getId(),
                Map.of("name", tenant.getName(), "slug", tenant.getSlug()));
        audit.record(tenant.getId(), user.getId(), AuditAction.USER_REGISTERED, "app_user", user.getId(),
                Map.of("email", user.getEmail(), "role", user.getRole().name()));

        Tokens tokens = issueTokens(user);
        return response(tokens, user, tenant);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normaliseEmail(request.email());
        AppUser user = users.findByEmail(email).orElse(null);
        if (user == null) {
            // Equalise timing so unknown emails are not distinguishable from wrong passwords.
            passwordEncoder.matches(request.password(), dummyHash);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            audit.recordFailure(user.getTenantId(), user.getId(), AuditAction.USER_LOGIN_FAILED,
                    "app_user", user.getId(), Map.of("reason", "bad_credentials"));
            throw invalidCredentials();
        }
        if (!user.isActive()) {
            audit.recordFailure(user.getTenantId(), user.getId(), AuditAction.USER_LOGIN_FAILED,
                    "app_user", user.getId(), Map.of("reason", "inactive"));
            throw invalidCredentials();
        }
        Tenant tenant = tenantService.require(user.getTenantId());
        user.setLastLoginAt(clock.instant());

        audit.record(AuditAction.USER_LOGIN, "app_user", user.getId(), Map.of("email", user.getEmail()));
        Tokens tokens = issueTokens(user);
        return response(tokens, user, tenant);
    }

    @Transactional
    public AuthResponse refresh(String rawToken) {
        Instant now = clock.instant();
        String hash = sha256(rawToken);
        RefreshToken token = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> ApiException.of(ErrorCode.REFRESH_TOKEN_INVALID,
                        "The refresh token is invalid or has expired"));

        if (token.getRevokedAt() != null) {
            // A revoked token was replayed: assume theft and terminate every session for the user.
            sessionRevocation.revokeAll(token.getUserId(), now);
            log.warn("Refresh token reuse detected for user {}; all sessions revoked", token.getUserId());
            throw ApiException.of(ErrorCode.REFRESH_TOKEN_REUSED,
                    "This session has been invalidated. Please sign in again.");
        }
        if (token.getExpiresAt().isBefore(now)) {
            sessionRevocation.revokeHash(hash, now);
            throw ApiException.of(ErrorCode.REFRESH_TOKEN_INVALID, "The refresh token has expired");
        }

        AppUser user = users.findById(token.getUserId())
                .orElseThrow(() -> ApiException.of(ErrorCode.REFRESH_TOKEN_INVALID, "The refresh token is invalid"));
        if (!user.isActive()) {
            sessionRevocation.revokeAll(user.getId(), now);
            throw ApiException.of(ErrorCode.REFRESH_TOKEN_INVALID, "This account is no longer active");
        }
        Tenant tenant = tenantService.require(user.getTenantId());

        token.setRevokedAt(now);
        Tokens tokens = issueTokens(user);
        return response(tokens, user, tenant);
    }

    @Transactional
    public void logout(String rawToken) {
        Instant now = clock.instant();
        refreshTokens.findByTokenHash(sha256(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(now);
            }
        });
    }

    @Transactional(readOnly = true)
    public MeResponse me() {
        AuthenticatedUser principal = currentUser.require();
        AppUser user = users.findByIdAndTenantId(principal.id(), principal.tenantId())
                .orElseThrow(() -> ApiException.of(ErrorCode.UNAUTHENTICATED, "The session is no longer valid"));
        return new MeResponse(UserSummary.from(user), tenantService.summary(principal.tenantId()));
    }

    private Tokens issueTokens(AppUser user) {
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getTenantId(), user.getRole(),
                user.getEmail(), user.getFullName());
        JwtService.IssuedToken access = jwtService.issue(principal);

        byte[] bytes = new byte[REFRESH_BYTES];
        random.nextBytes(bytes);
        String rawRefresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setTokenHash(sha256(rawRefresh));
        refreshToken.setExpiresAt(clock.instant().plus(jwtService.refreshTtl()));
        refreshTokens.save(refreshToken);

        return new Tokens(access.value(), access.expiresInSeconds(), rawRefresh);
    }

    private AuthResponse response(Tokens tokens, AppUser user, Tenant tenant) {
        return new AuthResponse(tokens.accessToken(), tokens.expiresInSeconds(), tokens.refreshToken(),
                UserSummary.from(user), TenantSummary.from(tenant));
    }

    private ApiException invalidCredentials() {
        return ApiException.of(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect");
    }

    private String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private record Tokens(String accessToken, long expiresInSeconds, String refreshToken) {
    }
}
