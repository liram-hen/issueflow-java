// Tests §2.1 User Management
package com.att.tdp.issueflow.user;

import com.att.tdp.issueflow.auth.TokenDenyListRepository;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.attachment.AttachmentRepository;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.TicketDependencyRepository;
import com.att.tdp.issueflow.comment.CommentRepository;
import com.att.tdp.issueflow.mention.MentionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTests {

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
        saveUser("admin", "admin@example.com", "Admin User", UserRole.ADMIN, "secret");
    }

    /** §2.1, §2.2 — All user endpoints reject unauthenticated requests with 401. */
    @Test
    void usersEndpointsRequireJwt() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isUnauthorized());
    }

    /** §2.1 — POST /users creates a user and returns id, username, email, fullName, role; passwordHash must not be exposed in the response. */
    @Test
    void createUserReturnsAssignmentResponseBody() throws Exception {
        String token = login();

        mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "jdoe",
                                  "email": "jdoe@example.com",
                                  "fullName": "John Doe",
                                  "role": "DEVELOPER",
                                  "password": "secret123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.username").value("jdoe"))
                .andExpect(jsonPath("$.email").value("jdoe@example.com"))
                .andExpect(jsonPath("$.fullName").value("John Doe"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    /** §2.1 — GET /users lists all users and GET /users/{id} returns a specific user by id. */
    @Test
    void listAndGetUsersReturnExistingUsers() throws Exception {
        String token = login();
        User developer = saveUser("dev", "dev@example.com", "Dev User", UserRole.DEVELOPER, "secret");

        mockMvc.perform(get("/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mockMvc.perform(get("/users/{userId}", developer.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("dev"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"));
    }

    /** §2.1 — POST /users/update/{id} changes fullName and role; changes are persisted and visible on the next GET. */
    @Test
    void updateUserSupportsFullNameAndRole() throws Exception {
        String token = login();
        User developer = saveUser("dev", "dev@example.com", "Dev User", UserRole.DEVELOPER, "secret");

        mockMvc.perform(post("/users/update/{userId}", developer.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Jane Doe",
                                  "role": "ADMIN"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/users/{userId}", developer.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Jane Doe"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    /** §2.1 — DELETE /users/{id} removes the user so a subsequent GET returns 404. */
    @Test
    void deleteUserHardDeletesUser() throws Exception {
        String token = login();
        User developer = saveUser("dev", "dev@example.com", "Dev User", UserRole.DEVELOPER, "secret");

        mockMvc.perform(delete("/users/{userId}", developer.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/users/{userId}", developer.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** §2.1, §4.1 — Blank username, invalid email, and blank fullName each fail validation; all three errors are reported together. */
    @Test
    void createUserRejectsValidationFailures() throws Exception {
        String token = login();

        mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "",
                                  "email": "not-an-email",
                                  "fullName": "",
                                  "role": "DEVELOPER",
                                  "password": "secret123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasSize(3)));
    }

    /** §2.1, §4.1 — Role values outside ADMIN and DEVELOPER are rejected with 400. */
    @Test
    void createUserRejectsUnsupportedRole() throws Exception {
        String token = login();

        mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "manager",
                                  "email": "manager@example.com",
                                  "fullName": "Manager User",
                                  "role": "MANAGER",
                                  "password": "secret123"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    /** §2.1, §4.1 — Username and email must be unique; duplicate values are each rejected with 400. */
    @Test
    void createUserRejectsDuplicateUsernameAndEmail() throws Exception {
        String token = login();

        mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "admin",
                                  "email": "other@example.com",
                                  "fullName": "Other User",
                                  "role": "DEVELOPER",
                                  "password": "secret123"
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "other",
                                  "email": "admin@example.com",
                                  "fullName": "Other User",
                                  "role": "DEVELOPER",
                                  "password": "secret123"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    /** §2.1, §4.1 — GET /users/{id} for a non-existent id returns 404. */
    @Test
    void unknownUserReturnsNotFound() throws Exception {
        String token = login();

        mockMvc.perform(get("/users/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** §2.1, §4.1 — A request missing the password field is rejected with 400; every created user must be able to authenticate. */
    @Test
    void createUserRequiresPassword() throws Exception {
        String token = login();

        mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "nopwd",
                                  "email": "nopwd@example.com",
                                  "fullName": "No Password",
                                  "role": "DEVELOPER"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    /** §2.1, §2.2 — A user created via POST /users can immediately authenticate via POST /auth/login, confirming the password is correctly hashed and stored during creation. */
    @Test
    void createdUserCanLogin() throws Exception {
        String adminToken = login();

        mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "loginuser",
                                  "email": "loginuser@example.com",
                                  "fullName": "Login User",
                                  "role": "DEVELOPER",
                                  "password": "secret2"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "loginuser",
                                  "password": "secret2"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", not(blankOrNullString())));
    }

    private String login() throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "admin",
                                  "password": "secret"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private User saveUser(String username, String email, String fullName, UserRole role, String password) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(password));
        return userRepository.save(user);
    }
}
