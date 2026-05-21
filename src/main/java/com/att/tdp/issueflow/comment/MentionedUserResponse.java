package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.user.User;

public record MentionedUserResponse(
        Long id,
        String username,
        String fullName
) {

    public static MentionedUserResponse from(User user) {
        return new MentionedUserResponse(user.getId(), user.getUsername(), user.getFullName());
    }
}
