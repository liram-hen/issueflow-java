package com.att.tdp.issueflow.ticket;

import java.time.Instant;

public record TicketResponse(
        Long id,
        String title,
        String description,
        TicketStatus status,
        TicketPriority priority,
        TicketType type,
        Long projectId,
        Long assigneeId,
        Instant dueDate,
        boolean isOverdue
) {

    static TicketResponse from(Ticket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getStatus(),
                ticket.getPriority(),
                ticket.getType(),
                ticket.getProject().getId(),
                ticket.getAssignee() == null ? null : ticket.getAssignee().getId(),
                ticket.getDueDate(),
                ticket.isOverdue()
        );
    }
}
