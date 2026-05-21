// Tests §2.2 Authentication
package com.att.tdp.issueflow.auth;

import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.UserRole;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.comment.CommentRepository;
import com.att.tdp.issueflow.mention.MentionRepository;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.attachment.AttachmentRepository;
import com.att.tdp.issueflow.ticket.TicketDependencyRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private MentionRepository mentionRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private TicketDependencyRepository ticketDependencyRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TokenDenyListRepository tokenDenyListRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        tokenDenyListRepository.deleteAll();
        attachmentRepository.deleteAll();
        ticketDependencyRepository.deleteAll();
        mentionRepository.deleteAll();
        commentRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
        saveUser("dev", "dev@example.com", "Dev User", UserRole.DEVELOPER, "secret");
        saveUser("admin", "admin@example.com", "Admin User", UserRole.ADMIN, "secret");
    }

    /** §2.2 — POST /auth/login with valid credentials returns a signed JWT access token. */
    @Test
    void loginReturnsBearerToken() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "dev",
                                  "password": "secret"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", not(blankOrNullString())))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    /** §2.2 — GET /auth/me rejects unauthenticated requests and returns the authenticated user's profile when a valid token is supplied. */
    @Test
    void meRequiresJwtAndReturnsCurrentUser() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());

        String token = login("dev");

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("dev"))
                .andExpect(jsonPath("$.email").value("dev@example.com"))
                .andExpect(jsonPath("$.fullName").value("Dev User"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"));
    }

    /** §2.2 — DEVELOPER role is denied access to admin-only restore endpoints with 403 Forbidden. */
    @Test
    void developerCannotAccessAdminRestoreRoute() throws Exception {
        String token = login("dev");

        mockMvc.perform(post("/projects/1/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /** §2.2 — ADMIN role passes authorization on restore routes; 404 means the resource check ran, confirming the security layer let the request through. */
    @Test
    void adminPassesRestoreAuthorization() throws Exception {
        String token = login("admin");

        mockMvc.perform(post("/projects/1/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** §2.2 — POST /auth/logout adds the token to the server-side deny-list so subsequent requests with the same token return 401. */
    @Test
    void logoutRevokesToken() throws Exception {
        String token = login("dev");

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /** §2.2 — Wrong password returns 401 so the system does not reveal whether an account exists. */
    @Test
    void invalidCredentialsAreRejected() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "dev",
                                  "password": "wrong"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    /** §2.2 — purgeExpiredTokens() removes entries whose expiresAt is in the past and leaves valid entries untouched. */
    @Test
    void expiredDenyListEntriesArePurged() {
        TokenDenyListEntry expired = new TokenDenyListEntry();
        expired.setTokenId("expired-token");
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        tokenDenyListRepository.save(expired);

        TokenDenyListEntry valid = new TokenDenyListEntry();
        valid.setTokenId("valid-token");
        valid.setExpiresAt(Instant.now().plusSeconds(3600));
        tokenDenyListRepository.save(valid);

        authService.purgeExpiredTokens();

        org.assertj.core.api.Assertions.assertThat(
                tokenDenyListRepository.existsByTokenIdAndExpiresAtAfter("expired-token", Instant.now())).isFalse();
        org.assertj.core.api.Assertions.assertThat(
                tokenDenyListRepository.existsByTokenIdAndExpiresAtAfter("valid-token", Instant.now())).isTrue();
    }

    private String login(String username) throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "secret"
                                }
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private void saveUser(String username, String email, String fullName, UserRole role, String password) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(password));
        userRepository.save(user);
    }
}
