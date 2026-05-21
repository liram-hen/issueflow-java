package com.att.tdp.issueflow.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank
        @Size(max = 160)
        String name,

        @Size(max = 1_000)
        String description,

        @NotNull
        Long ownerId
) {
}
