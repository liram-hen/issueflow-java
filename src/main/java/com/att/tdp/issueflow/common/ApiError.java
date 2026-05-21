package com.att.tdp.issueflow.common;

import java.time.Instant;
import java.util.List;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        List<String> messages
) {
}
