package com.att.tdp.issueflow.ticket;

import jakarta.validation.constraints.Size;
import java.time.Instant;

public record UpdateTicketRequest(
        @Size(max = 255)
        String title,

        @Size(max = 4_000)
        String description,

        TicketStatus status,

        TicketPriority priority,

        Long assigneeId,

        Instant dueDate
) {
}
