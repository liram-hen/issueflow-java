package com.att.tdp.issueflow.audit;

import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AuditLogService(AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void log(AuditAction action, AuditEntityType entityType, Long entityId) {
        AuditLog log = new AuditLog();
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setActor(AuditActor.USER);
        currentUser().ifPresent(log::setPerformedBy);
        auditLogRepository.save(log);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void logSystem(AuditAction action, AuditEntityType entityType, Long entityId) {
        AuditLog log = new AuditLog();
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setActor(AuditActor.SYSTEM);
        auditLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> findLogs(AuditEntityType entityType, Long entityId, AuditAction action, AuditActor actor) {
        return auditLogRepository.findAll((root, query, builder) -> {
                    List<Predicate> predicates = new ArrayList<>();
                    if (entityType != null) {
                        predicates.add(builder.equal(root.get("entityType"), entityType));
                    }
                    if (entityId != null) {
                        predicates.add(builder.equal(root.get("entityId"), entityId));
                    }
                    if (action != null) {
                        predicates.add(builder.equal(root.get("action"), action));
                    }
                    if (actor != null) {
                        predicates.add(builder.equal(root.get("actor"), actor));
                    }
                    query.orderBy(builder.desc(root.get("timestamp")));
                    return builder.and(predicates.toArray(Predicate[]::new));
                })
                .stream()
                .map(AuditLogResponse::from)
                .toList();
    }

    private java.util.Optional<User> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return java.util.Optional.empty();
        }
        return userRepository.findByUsername(authentication.getName());
    }
}
