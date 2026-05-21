package com.att.tdp.issueflow.user;

public record UserResponse(
        Long id,
        String username,
        String email,
        String fullName,
        UserRole role
) {

    static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getRole()
        );
    }
}
