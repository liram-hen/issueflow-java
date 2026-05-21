package com.att.tdp.issueflow.ticket;

import jakarta.validation.constraints.NotNull;

public record DependencyRequest(@NotNull Long blockedBy) {
}
