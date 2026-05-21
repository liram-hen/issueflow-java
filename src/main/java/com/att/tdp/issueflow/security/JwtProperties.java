package com.att.tdp.issueflow.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "issueflow.jwt")
public record JwtProperties(String secret, long expiresInSeconds) {
}
