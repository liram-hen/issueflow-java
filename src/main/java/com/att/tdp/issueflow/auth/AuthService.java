package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.security.JwtProperties;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditLogService;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final UserRepository userRepository;
    private final TokenDenyListRepository tokenDenyListRepository;
    private final AuditLogService auditLogService;

    public AuthService(
            AuthenticationManager authenticationManager,
            JwtEncoder jwtEncoder,
            JwtProperties jwtProperties,
            UserRepository userRepository,
            TokenDenyListRepository tokenDenyListRepository,
            AuditLogService auditLogService
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.userRepository = userRepository;
        this.tokenDenyListRepository = tokenDenyListRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );

        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(jwtProperties.expiresInSeconds());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getUsername())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("userId", user.getId())
                .claim("role", user.getRole().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        auditLogService.log(AuditAction.LOGIN, AuditEntityType.AUTH, user.getId());
        return new LoginResponse(token, "Bearer", jwtProperties.expiresInSeconds());
    }

    @Transactional
    public void logout(Jwt jwt) {
        if (jwt.getId() == null || jwt.getExpiresAt() == null) {
            return;
        }
        if (!tokenDenyListRepository.existsByTokenIdAndExpiresAtAfter(jwt.getId(), Instant.now())) {
            TokenDenyListEntry entry = new TokenDenyListEntry();
            entry.setTokenId(jwt.getId());
            entry.setExpiresAt(jwt.getExpiresAt());
            TokenDenyListEntry saved = tokenDenyListRepository.save(entry);
            auditLogService.log(AuditAction.LOGOUT, AuditEntityType.AUTH, saved.getId());
        }
    }

    /**
     * Removes deny-list entries whose token has already expired.
     * Expired tokens are harmless (the JWT timestamp validator rejects them before the deny-list check),
     * so this is purely a housekeeping job to keep the table from growing indefinitely.
     * Runs nightly at 03:00.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        tokenDenyListRepository.deleteAllByExpiresAtBefore(Instant.now());
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
        return new CurrentUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getRole()
        );
    }
}
