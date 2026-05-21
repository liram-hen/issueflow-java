package com.att.tdp.issueflow.ticket;

public record WorkloadRow(
        Long userId,
        String username,
        long openTicketCount
) {
}
