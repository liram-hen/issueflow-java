package com.att.tdp.issueflow.project;

public record WorkloadResponse(
        Long userId,
        String username,
        long openTicketCount
) {
}
