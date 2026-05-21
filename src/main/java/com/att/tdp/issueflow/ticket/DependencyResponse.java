package com.att.tdp.issueflow.ticket;

public record DependencyResponse(
        Long id,
        String title,
        TicketStatus status
) {

    static DependencyResponse from(Ticket ticket) {
        return new DependencyResponse(ticket.getId(), ticket.getTitle(), ticket.getStatus());
    }
}
