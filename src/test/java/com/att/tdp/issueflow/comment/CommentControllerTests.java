// Tests §2.5 Comment Management, §3.6 @Mention Mechanism
package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.auth.TokenDenyListRepository;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.attachment.AttachmentRepository;
import com.att.tdp.issueflow.mention.MentionRepository;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketPriority;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.TicketDependencyRepository;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.UserRole;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CommentControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenDenyListRepository tokenDenyListRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AttachmentRepository attachmentRepository;

    @Autowired
    private TicketDependencyRepository ticketDependencyRepository;

    @Autowired
    private MentionRepository mentionRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User admin;
    private User author;
    private User mentioned;
    private User secondMentioned;
    private Ticket ticket;
    private Ticket otherTicket;

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

        admin = saveUser("admin", "admin@example.com", "Admin User", UserRole.ADMIN, "secret");
        author = saveUser("author", "author@example.com", "Author User", UserRole.DEVELOPER, "secret");
        mentioned = saveUser("jdoe", "jdoe@example.com", "John Doe", UserRole.DEVELOPER, "secret");
        secondMentioned = saveUser("asmith", "asmith@example.com", "Alice Smith", UserRole.DEVELOPER, "secret");

        Project project = saveProject("Project", admin);
        ticket = saveTicket(project, author, "Ticket", false);
        otherTicket = saveTicket(project, author, "Other Ticket", false);
    }

    /** §2.5, §2.2 — Comment endpoints reject unauthenticated requests with 401. */
    @Test
    void commentEndpointsRequireJwt() throws Exception {
        mockMvc.perform(get("/tickets/{ticketId}/comments", ticket.getId()))
                .andExpect(status().isUnauthorized());
    }

    /** §2.5, §3.6 — Adding a comment with @mentions persists them deduplicated and case-insensitively; mentionedUsers with id, username, fullName are included in list responses. */
    @Test
    void addAndListCommentsWithCaseInsensitiveMentions() throws Exception {
        String token = login();

        long commentId = addComment(token, ticket.getId(), author.getId(), "Hello @JDOE and @jdoe plus @asmith!");

        mockMvc.perform(get("/tickets/{ticketId}/comments", ticket.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(commentId))
                .andExpect(jsonPath("$[0].ticketId").value(ticket.getId()))
                .andExpect(jsonPath("$[0].authorId").value(author.getId()))
                .andExpect(jsonPath("$[0].mentionedUsers", hasSize(2)))
                .andExpect(jsonPath("$[0].mentionedUsers[0].id").value(mentioned.getId()))
                .andExpect(jsonPath("$[0].mentionedUsers[0].username").value("jdoe"))
                .andExpect(jsonPath("$[0].mentionedUsers[0].fullName").value("John Doe"))
                .andExpect(jsonPath("$[0].mentionedUsers[1].id").value(secondMentioned.getId()));

        assertMentionCount(2);
    }

    /** §2.5 — GET /tickets/{id}/comments returns only comments for that ticket, not comments belonging to other tickets. */
    @Test
    void commentsAreFilteredByTicket() throws Exception {
        String token = login();
        addComment(token, ticket.getId(), author.getId(), "First ticket");
        addComment(token, otherTicket.getId(), author.getId(), "Other ticket");

        mockMvc.perform(get("/tickets/{ticketId}/comments", ticket.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].content").value("First ticket"));
    }

    /** §3.6 — Editing comment content re-evaluates the mention list: removed mentions are deleted and new ones are created. */
    @Test
    void updateCommentRecomputesMentions() throws Exception {
        String token = login();
        long commentId = addComment(token, ticket.getId(), author.getId(), "Hello @jdoe");
        assertMentionCount(1);

        mockMvc.perform(patch("/tickets/{ticketId}/comments/{commentId}", ticket.getId(), commentId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "Updated for @ASMITH only"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/tickets/{ticketId}/comments", ticket.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("Updated for @ASMITH only"))
                .andExpect(jsonPath("$[0].mentionedUsers", hasSize(1)))
                .andExpect(jsonPath("$[0].mentionedUsers[0].username").value("asmith"));

        assertMentionCount(1);
    }

    /** §3.6 — Deleting a comment also removes its associated mention records from the database. */
    @Test
    void deleteCommentDeletesPersistedMentions() throws Exception {
        String token = login();
        long commentId = addComment(token, ticket.getId(), author.getId(), "Hello @jdoe");
        assertMentionCount(1);

        mockMvc.perform(delete("/tickets/{ticketId}/comments/{commentId}", ticket.getId(), commentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/tickets/{ticketId}/comments", ticket.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        assertMentionCount(0);
    }

    /** §2.5, §4.1 — Empty content returns 400; unknown authorId returns 404; @mention of a non-existent username returns 400. */
    @Test
    void commentsRejectValidationFailuresAndUnknownReferences() throws Exception {
        String token = login();

        mockMvc.perform(post("/tickets/{ticketId}/comments", ticket.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorId": %d,
                                  "content": ""
                                }
                                """.formatted(author.getId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/tickets/{ticketId}/comments", ticket.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorId": 999999,
                                  "content": "Hello"
                                }
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/tickets/{ticketId}/comments", ticket.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorId": %d,
                                  "content": "Hello @missing"
                                }
                                """.formatted(author.getId())))
                .andExpect(status().isBadRequest());
    }

    /** §2.5 — Comment operations on soft-deleted tickets return 404; updating a comment via the wrong ticketId also returns 404. */
    @Test
    void deletedTicketsAndWrongTicketCommentsReturnNotFound() throws Exception {
        String token = login();
        Ticket deletedTicket = saveTicket(ticket.getProject(), author, "Deleted Ticket", true);
        long commentId = addComment(token, ticket.getId(), author.getId(), "Hello");

        mockMvc.perform(get("/tickets/{ticketId}/comments", deletedTicket.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/tickets/{ticketId}/comments/{commentId}", otherTicket.getId(), commentId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "Wrong ticket"
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    /** §2.5 — The comment author (DEVELOPER) can edit their own comment; a different developer editing someone else's comment is rejected with 403. */
    @Test
    void onlyAuthorOrAdminCanEditComment() throws Exception {
        String adminToken  = login();
        String authorToken = login("author");
        String otherToken  = login("jdoe");

        long commentId = addComment(adminToken, ticket.getId(), author.getId(), "Original @asmith");

        // author edits own comment → 200
        mockMvc.perform(patch("/tickets/{ticketId}/comments/{commentId}", ticket.getId(), commentId)
                        .header("Authorization", "Bearer " + authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Edited by author\"}"))
                .andExpect(status().isOk());

        // admin edits someone else's comment → 200
        mockMvc.perform(patch("/tickets/{ticketId}/comments/{commentId}", ticket.getId(), commentId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Edited by admin\"}"))
                .andExpect(status().isOk());

        // different developer edits another user's comment → 403
        mockMvc.perform(patch("/tickets/{ticketId}/comments/{commentId}", ticket.getId(), commentId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Should be rejected\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * §3.6 — GET /users/{userId}/mentions must include mentionedUsers: [{ id, username, fullName }] in each
     * returned CommentResponse.
     *
     * This is tested separately from GET /tickets/{id}/comments even though both return CommentResponse,
     * because the two endpoints build the mentionedUsers list through different code paths:
     * CommentService.toResponse() vs UserService.getMentionsForUser(). A bug in either path would not
     * be caught by tests for the other.
     */
    @Test
    void mentionsEndpointIncludesMentionedUsersMetadata() throws Exception {
        String token = login();
        addComment(token, ticket.getId(), author.getId(), "Hello @jdoe and @asmith");

        mockMvc.perform(get("/users/{userId}/mentions", mentioned.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].mentionedUsers", hasSize(2)))
                .andExpect(jsonPath("$.data[0].mentionedUsers[0].id").value(mentioned.getId()))
                .andExpect(jsonPath("$.data[0].mentionedUsers[0].username").value("jdoe"))
                .andExpect(jsonPath("$.data[0].mentionedUsers[0].fullName").value("John Doe"))
                .andExpect(jsonPath("$.data[0].mentionedUsers[1].id").value(secondMentioned.getId()))
                .andExpect(jsonPath("$.data[0].mentionedUsers[1].username").value("asmith"));
    }

    /** §3.6 — GET /users/{id}/mentions returns all comments that mention the user, ordered newest first, with correct total count. */
    @Test
    void getMentionsByUserReturnsCommentsNewestFirst() throws Exception {
        String token = login();
        addComment(token, ticket.getId(), author.getId(), "First mention @jdoe");
        addComment(token, ticket.getId(), author.getId(), "Second mention @jdoe");

        mockMvc.perform(get("/users/{userId}/mentions", mentioned.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].content").value("Second mention @jdoe"))
                .andExpect(jsonPath("$.data[1].content").value("First mention @jdoe"))
                .andExpect(jsonPath("$.total").value(2));
    }

    private long addComment(String token, Long ticketId, Long authorId, String content) throws Exception {
        String response = mockMvc.perform(post("/tickets/{ticketId}/comments", ticketId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorId": %d,
                                  "content": "%s"
                                }
                                """.formatted(authorId, content)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(content))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceFirst("^\\{\\\"id\\\":([0-9]+).*", "$1"));
    }

    private String login() throws Exception {
        return login("admin");
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

    private void assertMentionCount(long count) {
        org.assertj.core.api.Assertions.assertThat(mentionRepository.count()).isEqualTo(count);
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

    private Project saveProject(String name, User owner) {
        Project project = new Project();
        project.setName(name);
        project.setDescription(name + " description");
        project.setOwner(owner);
        return projectRepository.save(project);
    }

    private Ticket saveTicket(Project project, User assignee, String title, boolean deleted) {
        Ticket savedTicket = new Ticket();
        savedTicket.setTitle(title);
        savedTicket.setDescription(title + " description");
        savedTicket.setStatus(TicketStatus.TODO);
        savedTicket.setPriority(TicketPriority.HIGH);
        savedTicket.setType(TicketType.BUG);
        savedTicket.setProject(project);
        savedTicket.setAssignee(assignee);
        savedTicket.setDueDate(Instant.now().plusSeconds(3600));
        savedTicket.setDeleted(deleted);
        savedTicket.setDeletedAt(deleted ? Instant.now() : null);
        return ticketRepository.save(savedTicket);
    }
}
