package com.att.tdp.issueflow.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank
        @Size(max = 64)
        String username,

        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @NotBlank
        @Size(max = 128)
        String fullName,

        @NotNull
        UserRole role,

        @NotBlank
        @Size(min = 6, max = 128)
        String password
) {
}
