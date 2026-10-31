package com.operonix.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.operonix.common.api.PageResponse;
import com.operonix.common.error.ApiException;
import com.operonix.common.error.ErrorCode;
import com.operonix.security.AuthenticatedUser;
import com.operonix.security.CurrentUser;
import com.operonix.security.TenantContext;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_DETAIL = 4000;

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;
    private final CurrentUser currentUser;

    public AuditService(AuditEventRepository repository, ObjectMapper objectMapper, CurrentUser currentUser) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.currentUser = currentUser;
    }

    public void record(AuditAction action, String entityType, UUID entityId, Map<String, Object> detail) {
        AuthenticatedUser user = currentUser.optional().orElse(null);
        UUID tenantId = user != null ? user.tenantId() : TenantContext.currentOrNull();
        record(tenantId, user != null ? user.id() : null, action, entityType, entityId, detail);
    }

    public void record(UUID tenantId, UUID actorId, AuditAction action, String entityType, UUID entityId,
                       Map<String, Object> detail) {
        if (tenantId == null) {
            log.warn("Skipping audit event {} because no tenant could be resolved", action);
            return;
        }
        AuditEvent event = new AuditEvent();
        event.setTenantId(tenantId);
        event.setActorUserId(actorId);
        event.setAction(action);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setDetail(serialise(detail));
        event.setIpAddress(clientIp());
        event.setRequestId(MDC.get("requestId"));
        TenantContext.runWith(tenantId, () -> {
            repository.save(event);
            return null;
        });
    }

    /**
     * Tenant-scoped audit search. Built with Criteria predicates rather than a JPQL
     * {@code :param is null or ...} chain: PostgreSQL cannot infer the type of a bare null bind
     * parameter, so optional filters are expressed as predicates instead.
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditEventResponse> search(AuditAction action, Instant from, Instant to,
                                                   Pageable pageable) {
        UUID tenantId = currentUser.tenantId();
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "The 'from' instant must not be after 'to'");
        }
        Specification<AuditEvent> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (action != null) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return PageResponse.of(repository.findAll(specification, pageable)).map(AuditEventResponse::from);
    }

    /**
     * Failure events must survive the caller's rollback (e.g. a rejected login), so they are
     * committed in their own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID tenantId, UUID actorId, AuditAction action, String entityType,
                              UUID entityId, Map<String, Object> detail) {
        record(tenantId, actorId, action, entityType, entityId, detail);
    }

    private String serialise(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(detail);
            return json.length() > MAX_DETAIL ? json.substring(0, MAX_DETAIL) : json;
        } catch (JsonProcessingException ex) {
            log.warn("Could not serialise audit detail", ex);
            return null;
        }
    }

    private String clientIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            HttpServletRequest request = servlet.getRequest();
            String remote = request.getRemoteAddr();
            return remote != null && remote.length() <= 45 ? remote : null;
        }
        return null;
    }
}
