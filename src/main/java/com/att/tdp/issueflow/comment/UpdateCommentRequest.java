package com.att.tdp.issueflow.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCommentRequest(
        @NotBlank
        @Size(max = 4_000)
        String content
) {
}
