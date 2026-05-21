package com.att.tdp.issueflow.ticket;

import java.util.List;

public record ImportTicketsResponse(
        int created,
        int failed,
        List<String> errors
) {
}
