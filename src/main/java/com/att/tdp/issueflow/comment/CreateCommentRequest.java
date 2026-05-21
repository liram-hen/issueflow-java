package com.att.tdp.issueflow.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCommentRequest(
        @NotNull
        Long authorId,

        @NotBlank
        @Size(max = 4_000)
        String content
) {
}
