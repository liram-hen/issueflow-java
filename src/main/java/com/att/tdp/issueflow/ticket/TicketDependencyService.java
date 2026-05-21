package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditLogService;
import com.att.tdp.issueflow.common.BusinessRuleException;
import com.att.tdp.issueflow.common.DuplicateResourceException;
import com.att.tdp.issueflow.common.ResourceNotFoundException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketDependencyService {

    private final TicketRepository ticketRepository;
    private final TicketDependencyRepository dependencyRepository;
    private final AuditLogService auditLogService;

    public TicketDependencyService(
            TicketRepository ticketRepository,
            TicketDependencyRepository dependencyRepository,
            AuditLogService auditLogService
    ) {
        this.ticketRepository = ticketRepository;
        this.dependencyRepository = dependencyRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void addDependency(Long ticketId, DependencyRequest request) {
        if (ticketId.equals(request.blockedBy())) {
            throw new BusinessRuleException("Ticket cannot depend on itself");
        }
        Ticket ticket = findActiveTicket(ticketId);
        Ticket blocker = findActiveTicket(request.blockedBy());
        if (!ticket.getProject().getId().equals(blocker.getProject().getId())) {
            throw new BusinessRuleException("Both tickets must belong to the same project");
        }
        if (dependencyRepository.existsByTicketIdAndBlockedById(ticketId, blocker.getId())) {
            throw new DuplicateResourceException("Dependency already exists");
        }
        if (wouldCreateCycle(ticketId, blocker.getId())) {
            throw new BusinessRuleException(
                    "Adding this dependency (ticket " + ticketId + " blocked by ticket " + blocker.getId()
                    + ") would create a circular dependency chain");
        }
        TicketDependency dependency = new TicketDependency();
        dependency.setTicket(ticket);
        dependency.setBlockedBy(blocker);
        TicketDependency saved = dependencyRepository.save(dependency);
        auditLogService.log(AuditAction.CREATE, AuditEntityType.DEPENDENCY, saved.getId());
    }

    @Transactional(readOnly = true)
    public List<DependencyResponse> listDependencies(Long ticketId) {
        findActiveTicket(ticketId);
        return dependencyRepository.findAllByTicketId(ticketId)
                .stream()
                .map(TicketDependency::getBlockedBy)
                .filter(ticket -> !ticket.isDeleted())
                .map(DependencyResponse::from)
                .toList();
    }

    @Transactional
    public void removeDependency(Long ticketId, Long blockerId) {
        findActiveTicket(ticketId);
        TicketDependency dependency = dependencyRepository.findByTicketIdAndBlockedById(ticketId, blockerId)
                .orElseThrow(() -> new ResourceNotFoundException("Dependency not found"));
        dependencyRepository.delete(dependency);
        auditLogService.log(AuditAction.DELETE, AuditEntityType.DEPENDENCY, dependency.getId());
    }

    /**
     * BFS from blockerId following the "is blocked by" chain.
     * If ticketId is reachable, adding ticketId→blockerId would close a cycle.
     */
    private boolean wouldCreateCycle(Long ticketId, Long blockerId) {
        Set<Long> visited = new HashSet<>();
        Queue<Long> queue = new LinkedList<>();
        queue.add(blockerId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (current.equals(ticketId)) return true;
            if (!visited.add(current)) continue;
            dependencyRepository.findAllByTicketId(current)
                    .stream()
                    .map(dep -> dep.getBlockedBy().getId())
                    .forEach(queue::add);
        }
        return false;
    }

    private Ticket findActiveTicket(Long ticketId) {
        return ticketRepository.findByIdAndDeletedFalse(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
    }
}
