package com.operonix.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(UUID id, AuditAction action, String entityType, UUID entityId,
                                 UUID actorUserId, String detail, String ipAddress, Instant createdAt) {

    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(event.getId(), event.getAction(), event.getEntityType(),
                event.getEntityId(), event.getActorUserId(), event.getDetail(), event.getIpAddress(),
                event.getCreatedAt());
    }
}
