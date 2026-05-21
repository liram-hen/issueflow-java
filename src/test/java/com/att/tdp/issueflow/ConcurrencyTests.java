// Tests §2.4 Ticket Management and §2.5 Comment Management — concurrent update/edit returns 409.
// Uses @SpyBean to inject ObjectOptimisticLockingFailureException deterministically, verifying
// that GlobalExceptionHandler maps it to HTTP 409 Conflict.
package com.att.tdp.issueflow;

import com.att.tdp.issueflow.attachment.AttachmentRepository;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.auth.TokenDenyListRepository;
import com.att.tdp.issueflow.comment.Comment;
import com.att.tdp.issueflow.comment.CommentRepository;
import com.att.tdp.issueflow.comment.CommentService;
import com.att.tdp.issueflow.mention.MentionRepository;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketDependencyRepository;
import com.att.tdp.issueflow.ticket.TicketPriority;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.ticket.TicketService;
import com.att.tdp.issueflow.ticket.TicketStatus;
import com.att.tdp.issueflow.ticket.TicketType;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.UserRole;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ConcurrencyTests {

    @Autowired private MockMvc mockMvc;
    @MockitoSpyBean private TicketService ticketService;
    @MockitoSpyBean private CommentService commentService;

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private MentionRepository mentionRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private TokenDenyListRepository tokenDenyListRepository;
    @Autowired private TicketDependencyRepository ticketDependencyRepository;
    @Autowired private AttachmentRepository attachmentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User admin;
    private Ticket ticket;
    private Comment comment;

    @BeforeEach
    void setUp() {
        Mockito.reset(ticketService, commentService);
        auditLogRepository.deleteAll();
        tokenDenyListRepository.deleteAll();
        attachmentRepository.deleteAll();
        ticketDependencyRepository.deleteAll();
        mentionRepository.deleteAll();
        commentRepository.deleteAll();
        ticketRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        admin = saveUser("admin", UserRole.ADMIN);
        User developer = saveUser("dev", UserRole.DEVELOPER);
        Project project = saveProject(admin);
        ticket = saveTicket(project, developer);
        comment = saveComment(ticket, developer);
    }

    /** §2.4 — When JPA detects a version conflict on a ticket update, ObjectOptimisticLockingFailureException is mapped by GlobalExceptionHandler to HTTP 409 Conflict. */
    @Test
    void concurrentTicketUpdateReturns409Conflict() throws Exception {
        String token = login();

        doThrow(new ObjectOptimisticLockingFailureException("Ticket", ticket.getId()))
                .when(ticketService).updateTicket(eq(ticket.getId()), any());

        mockMvc.perform(patch("/tickets/{ticketId}", ticket.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"conflicting update\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messages[0]")
                        .value("Resource was modified by another request, please retry"));
    }

    /** §2.5 — When JPA detects a version conflict on a comment edit, ObjectOptimisticLockingFailureException is mapped by GlobalExceptionHandler to HTTP 409 Conflict. */
    @Test
    void concurrentCommentEditReturns409Conflict() throws Exception {
        String token = login();

        doThrow(new ObjectOptimisticLockingFailureException("Comment", comment.getId()))
                .when(commentService).updateComment(eq(ticket.getId()), eq(comment.getId()), any(), any(), any());

        mockMvc.perform(patch("/tickets/{ticketId}/comments/{commentId}", ticket.getId(), comment.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"conflicting edit\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messages[0]")
                        .value("Resource was modified by another request, please retry"));
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

    private User saveUser(String username, UserRole role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setFullName(username + " User");
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode("secret"));
        return userRepository.save(user);
    }

    private Project saveProject(User owner) {
        Project project = new Project();
        project.setName("Project");
        project.setDescription("Project description");
        project.setOwner(owner);
        return projectRepository.save(project);
    }

    private Ticket saveTicket(Project project, User assignee) {
        Ticket t = new Ticket();
        t.setTitle("Ticket");
        t.setDescription("desc");
        t.setStatus(TicketStatus.TODO);
        t.setPriority(TicketPriority.HIGH);
        t.setType(TicketType.BUG);
        t.setProject(project);
        t.setAssignee(assignee);
        t.setDueDate(Instant.now().plusSeconds(3600));
        return ticketRepository.save(t);
    }

    private Comment saveComment(Ticket t, User author) {
        Comment c = new Comment();
        c.setTicket(t);
        c.setAuthor(author);
        c.setContent("Hello");
        return commentRepository.save(c);
    }
}
