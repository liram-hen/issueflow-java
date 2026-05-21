package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditLogService;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketEscalationService {

    private final TicketRepository ticketRepository;
    private final AuditLogService auditLogService;

    public TicketEscalationService(TicketRepository ticketRepository, AuditLogService auditLogService) {
        this.ticketRepository = ticketRepository;
        this.auditLogService = auditLogService;
    }

    @Scheduled(fixedDelayString = "${issueflow.escalation.fixed-delay-ms:60000}")
    @Transactional
    public void escalateOverdueTickets() {
        for (Ticket ticket : ticketRepository.findAllByDeletedFalseAndStatusNotAndDueDateBeforeAndEscalatedAtIsNull(TicketStatus.DONE, Instant.now())) {
            TicketPriority next = nextPriority(ticket.getPriority());
            if (next != ticket.getPriority()) {
                ticket.setPriority(next);
                auditLogService.logSystem(AuditAction.UPDATE, AuditEntityType.TICKET, ticket.getId());
            }
            if (ticket.getPriority() == TicketPriority.CRITICAL) {
                ticket.setOverdue(true);
            }
            ticket.setEscalatedAt(Instant.now());
        }
    }

    private TicketPriority nextPriority(TicketPriority priority) {
        return switch (priority) {
            case LOW -> TicketPriority.MEDIUM;
            case MEDIUM -> TicketPriority.HIGH;
            case HIGH -> TicketPriority.CRITICAL;
            case CRITICAL -> TicketPriority.CRITICAL;
        };
    }
}
