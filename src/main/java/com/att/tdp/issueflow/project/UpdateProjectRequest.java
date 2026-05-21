package com.att.tdp.issueflow.project;

import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
        @Size(max = 160)
        String name,

        @Size(max = 1_000)
        String description
) {
}
