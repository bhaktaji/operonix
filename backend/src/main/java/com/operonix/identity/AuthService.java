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
import com.operonix.global.IndustryTemplate;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int REFRESH_BYTES = 32;
    /** Pre-2.0 clients do not send a country; they keep working against this market default. */
    private static final String DEFAULT_COUNTRY = "US";

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
        // Registration is public: it *creates* a workspace, so the request has no tenant. A caller
        // that still holds a session - a browser keeps its bearer token after sign-out elsewhere, and
        // creating an additional workspace is a supported journey - must not have that session's
        // tenant imposed on the new workspace's rows, which the TenantEntity guard would reject.
        UUID previousTenant = TenantContext.currentOrNull();
        TenantContext.clear();
        try {
            return provisionWorkspace(request);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            }
        }
    }

    private AuthResponse provisionWorkspace(RegisterRequest request) {
        String email = normaliseEmail(request.email());
        Tenant tenant = tenantService.provision(new TenantService.Provisioning(
                request.orgName(),
                request.countryCode() == null || request.countryCode().isBlank()
                        ? DEFAULT_COUNTRY : request.countryCode(),
                request.currency(),
                request.timezone(),
                request.locale(),
                request.industryTemplate() == null || request.industryTemplate().isBlank()
                        ? IndustryTemplate.GENERAL_SERVICE.name() : request.industryTemplate(),
                null,
                request.legalName(),
                null));

        // Email is unique per workspace, so the same person can own a second workspace later.
        if (users.existsByTenantIdAndEmail(tenant.getId(), email)) {
            throw ApiException.of(ErrorCode.EMAIL_ALREADY_EXISTS,
                    "An account with this email already exists in this workspace");
        }

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
                Map.of("name", tenant.getName(), "slug", tenant.getSlug(),
                        "country", tenant.getCountryCode(), "currency", tenant.getCurrency(),
                        "industryTemplate", tenant.getIndustryTemplate()));
        audit.record(tenant.getId(), user.getId(), AuditAction.USER_REGISTERED, "app_user", user.getId(),
                Map.of("email", user.getEmail(), "role", user.getRole().name()));

        Issued issued = issueTokens(user, null);
        return response(issued, user, tenant);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normaliseEmail(request.email());
        List<AppUser> candidates = users.findAllByEmail(email);
        if (candidates.isEmpty()) {
            // Equalise timing so unknown emails are not distinguishable from wrong passwords.
            passwordEncoder.matches(request.password(), dummyHash);
            throw invalidCredentials();
        }

        // Verify the password against every membership before revealing anything about workspaces:
        // a wrong password must never disclose which workspaces an email belongs to.
        List<AppUser> verified = new ArrayList<>(candidates.size());
        for (AppUser candidate : candidates) {
            if (passwordEncoder.matches(request.password(), candidate.getPasswordHash())) {
                verified.add(candidate);
            }
        }
        if (verified.isEmpty()) {
            AppUser first = candidates.get(0);
            audit.recordFailure(first.getTenantId(), first.getId(), AuditAction.USER_LOGIN_FAILED,
                    "app_user", first.getId(), Map.of("reason", "bad_credentials"));
            throw invalidCredentials();
        }

        List<AppUser> active = verified.stream().filter(AppUser::isActive).toList();
        if (active.isEmpty()) {
            AppUser first = verified.get(0);
            audit.recordFailure(first.getTenantId(), first.getId(), AuditAction.USER_LOGIN_FAILED,
                    "app_user", first.getId(), Map.of("reason", "inactive"));
            throw invalidCredentials();
        }

        AppUser user;
        if (active.size() == 1) {
            user = active.get(0);
        } else {
            String requested = request.workspace();
            if (requested == null || requested.isBlank()) {
                throw ApiException.of(ErrorCode.WORKSPACE_REQUIRED,
                                "This email belongs to more than one workspace. Choose one to continue.")
                        .withProperty("workspaces", workspaceChoices(active));
            }
            user = active.stream()
                    .filter(candidate -> tenantSlug(candidate).equalsIgnoreCase(requested.trim()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.of(ErrorCode.WORKSPACE_NOT_FOUND,
                            "No workspace with that address is available for this account"));
        }

        Tenant tenant = tenantService.require(user.getTenantId());
        user.setLastLoginAt(clock.instant());

        TenantContext.runWith(tenant.getId(), () -> {
            audit.record(AuditAction.USER_LOGIN, "app_user", user.getId(),
                    Map.of("email", user.getEmail(), "workspace", tenant.getSlug()));
            return null;
        });
        Issued issued = issueTokens(user, null);
        return response(issued, user, tenant);
    }

    @Transactional
    public AuthResponse refresh(String rawToken) {
        Instant now = clock.instant();
        String hash = sha256(rawToken);
        RefreshToken token = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> ApiException.of(ErrorCode.REFRESH_TOKEN_INVALID,
                        "The refresh token is invalid or has expired"));

        if (token.getRevokedAt() != null) {
            // A rotated token was replayed: the chain is compromised, so revoke that chain only.
            int revoked = sessionRevocation.revokeFamily(token.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}; {} token(s) in family {} revoked",
                    token.getUserId(), revoked, token.getFamilyId());
            throw ApiException.of(ErrorCode.REFRESH_TOKEN_REUSED,
                    "This session has been invalidated. Please sign in again.");
        }
        if (token.getExpiresAt().isBefore(now)) {
            sessionRevocation.revokeFamily(token.getFamilyId(), now);
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
        Issued issued = issueTokens(user, token.getFamilyId());
        token.setReplacedBy(issued.refreshTokenId());
        return response(issued, user, tenant);
    }

    @Transactional
    public void logout(String rawToken) {
        Instant now = clock.instant();
        refreshTokens.findByTokenHash(sha256(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(now);
                // Ending a session ends the whole chain: the browser cannot rotate a superseded token.
                sessionRevocation.revokeFamily(token.getFamilyId(), now);
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

    private List<Map<String, String>> workspaceChoices(List<AppUser> memberships) {
        List<Map<String, String>> choices = new ArrayList<>(memberships.size());
        for (AppUser membership : memberships) {
            Optional<Tenant> tenant = tenantService.find(membership.getTenantId());
            tenant.ifPresent(value -> {
                Map<String, String> choice = new LinkedHashMap<>();
                choice.put("slug", value.getSlug());
                choice.put("name", value.getName());
                choice.put("role", membership.getRole().name());
                choices.add(choice);
            });
        }
        return choices;
    }

    private String tenantSlug(AppUser user) {
        return tenantService.find(user.getTenantId()).map(Tenant::getSlug).orElse("");
    }

    private Issued issueTokens(AppUser user, UUID familyId) {
        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getTenantId(), user.getRole(),
                user.getEmail(), user.getFullName());
        JwtService.IssuedToken access = jwtService.issue(principal);

        byte[] bytes = new byte[REFRESH_BYTES];
        random.nextBytes(bytes);
        String rawRefresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        // A brand-new session starts its own chain; a rotation continues the existing one. The family
        // id must be set before the insert, because the id generator writes the row immediately.
        UUID family = familyId == null ? UUID.randomUUID() : familyId;

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setTokenHash(sha256(rawRefresh));
        refreshToken.setExpiresAt(clock.instant().plus(jwtService.refreshTtl()));
        refreshToken.setFamilyId(family);
        refreshTokens.save(refreshToken);

        return new Issued(access.value(), access.expiresInSeconds(), rawRefresh, refreshToken.getId());
    }

    private AuthResponse response(Issued issued, AppUser user, Tenant tenant) {
        return new AuthResponse(issued.accessToken(), issued.expiresInSeconds(), issued.refreshToken(),
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

    private record Issued(String accessToken, long expiresInSeconds, String refreshToken, UUID refreshTokenId) {
    }
}