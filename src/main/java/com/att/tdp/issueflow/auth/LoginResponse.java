package com.att.tdp.issueflow.auth;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {
}
