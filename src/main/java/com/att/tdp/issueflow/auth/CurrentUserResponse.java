package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.user.UserRole;

public record CurrentUserResponse(
        Long id,
        String username,
        String email,
        String fullName,
        UserRole role
) {
}
