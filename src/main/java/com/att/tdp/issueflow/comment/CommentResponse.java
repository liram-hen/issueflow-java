package com.att.tdp.issueflow.comment;

import java.util.List;

public record CommentResponse(
        Long id,
        Long ticketId,
        Long authorId,
        String content,
        List<MentionedUserResponse> mentionedUsers
) {
}
