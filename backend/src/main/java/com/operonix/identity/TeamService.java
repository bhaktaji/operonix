package com.operonix.identity;

import com.operonix.audit.AuditAction;
import com.operonix.audit.AuditService;
import com.operonix.common.error.ApiException;
import com.operonix.common.error.ErrorCode;
import com.operonix.identity.dto.CreateUserRequest;
import com.operonix.identity.dto.TeamMemberResponse;
import com.operonix.identity.dto.UpdateUserRequest;
import com.operonix.security.CurrentUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class TeamService {

    private final AppUserRepository users;
    private final SessionRevocationService sessionRevocation;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final Clock clock;

    public TeamService(AppUserRepository users, SessionRevocationService sessionRevocation,
                       PasswordEncoder passwordEncoder, AuditService audit, CurrentUser currentUser,
                       Clock clock) {
        this.users = users;
        this.sessionRevocation = sessionRevocation;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TeamMemberResponse> list() {
        return users.findAllByTenantIdOrderByFullNameAsc(currentUser.tenantId()).stream()
                .map(TeamMemberResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TeamMemberResponse.Assignable> assignable() {
        return users.findAllByTenantIdOrderByFullNameAsc(currentUser.tenantId()).stream()
                .filter(AppUser::isActive)
                .map(TeamMemberResponse.Assignable::from)
                .toList();
    }

    @Transactional
    public TeamMemberResponse create(CreateUserRequest request) {
        if (request.role() == Role.OWNER) {
            throw ApiException.validation("role", "an additional OWNER cannot be created through the API");
        }
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw ApiException.of(ErrorCode.EMAIL_ALREADY_EXISTS, "An account with this email already exists");
        }
        AppUser user = new AppUser();
        user.setTenantId(currentUser.tenantId());
        user.setEmail(email);
        user.setFullName(request.fullName().trim());
        user.setRole(request.role());
        user.setActive(true);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        users.save(user);

        audit.record(AuditAction.USER_CREATED, "app_user", user.getId(),
                Map.of("email", user.getEmail(), "role", user.getRole().name()));
        users.flush();
        return TeamMemberResponse.from(user);
    }

    @Transactional
    public TeamMemberResponse update(UUID userId, UpdateUserRequest request) {
        UUID tenantId = currentUser.tenantId();
        AppUser target = users.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Team member"));

        Role newRole = request.role() == null ? target.getRole() : request.role();
        boolean newActive = request.active() == null ? target.isActive() : request.active();
        boolean roleChanged = newRole != target.getRole();
        boolean activeChanged = newActive != target.isActive();
        boolean self = target.getId().equals(currentUser.id());

        if (self && (roleChanged || activeChanged)) {
            throw ApiException.of(ErrorCode.SELF_MODIFICATION,
                    "You cannot change your own role or deactivate your own account");
        }
        if (roleChanged && newRole == Role.OWNER) {
            throw ApiException.validation("role", "ownership transfer is not available in this version");
        }
        if (target.getRole() == Role.OWNER && (roleChanged || !newActive)) {
            long activeOwners = users.countByTenantIdAndRoleAndActiveTrue(tenantId, Role.OWNER);
            if (activeOwners <= 1) {
                throw ApiException.of(ErrorCode.LAST_OWNER_PROTECTED,
                        "The workspace must keep at least one active owner");
            }
        }

        if (request.fullName() != null && !request.fullName().isBlank()) {
            target.setFullName(request.fullName().trim());
        }
        if (roleChanged) {
            target.setRole(newRole);
        }
        if (activeChanged) {
            target.setActive(newActive);
            if (!newActive) {
                sessionRevocation.revokeAll(target.getId(), clock.instant());
            }
        }
        users.save(target);

        audit.record(activeChanged && !newActive ? AuditAction.USER_DEACTIVATED : AuditAction.USER_UPDATED,
                "app_user", target.getId(), details(target, roleChanged, activeChanged));
        users.flush();
        return TeamMemberResponse.from(target);
    }

    private Map<String, Object> details(AppUser target, boolean roleChanged, boolean activeChanged) {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("email", target.getEmail());
        detail.put("role", target.getRole().name());
        detail.put("active", target.isActive());
        if (roleChanged) {
            detail.put("roleChanged", true);
        }
        if (activeChanged) {
            detail.put("activeChanged", true);
        }
        detail.values().removeIf(Objects::isNull);
        return detail;
    }
}
