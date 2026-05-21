package com.att.tdp.issueflow.user;

import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Size(max = 128)
        String fullName,

        UserRole role
) {
}
