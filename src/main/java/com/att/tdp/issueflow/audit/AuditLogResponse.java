package com.att.tdp.issueflow.audit;

import java.time.Instant;

public record AuditLogResponse(
        Long id,
        AuditAction action,
        AuditEntityType entityType,
        Long entityId,
        Long performedBy,
        AuditActor actor,
        Instant timestamp
) {

    static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                log.getPerformedBy() == null ? null : log.getPerformedBy().getId(),
                log.getActor(),
                log.getTimestamp()
        );
    }
}
