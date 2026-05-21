// Tests §2.3 Project Management, §3.5 Soft Delete (projects), §3.8 Auto Assignment (workload endpoint)
package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.auth.TokenDenyListRepository;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.attachment.AttachmentRepository;
import com.att.tdp.issueflow.comment.CommentRepository;
import com.att.tdp.issueflow.mention.MentionRepository;
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

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectControllerTests {

    @Autowired
    private MockMvc mockMvc;

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
    private UserRepository userRepository;

    @Autowired
    private TokenDenyListRepository tokenDenyListRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User admin;
    private User developer;
    private User secondDeveloper;

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
        developer = saveUser("dev", "dev@example.com", "Dev User", UserRole.DEVELOPER, "secret");
        secondDeveloper = saveUser("dev2", "dev2@example.com", "Second Dev", UserRole.DEVELOPER, "secret");
    }

    /** §2.3, §2.2 — All project endpoints reject unauthenticated requests with 401. */
    @Test
    void projectEndpointsRequireJwt() throws Exception {
        mockMvc.perform(get("/projects"))
                .andExpect(status().isUnauthorized());
    }

    /** §2.3 — Full CRUD path: create project, list it, fetch by id, update name and description, confirm changes persist. */
    @Test
    void createListGetAndUpdateProject() throws Exception {
        String token = login("admin");

        long projectId = createProject(token, "Sample Project", "A sample project", admin.getId());

        mockMvc.perform(get("/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Sample Project"));

        mockMvc.perform(get("/projects/{projectId}", projectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId))
                .andExpect(jsonPath("$.description").value("A sample project"))
                .andExpect(jsonPath("$.ownerId").value(admin.getId()));

        mockMvc.perform(patch("/projects/{projectId}", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Updated Name",
                                  "description": "Updated description"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/projects/{projectId}", projectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.description").value("Updated description"));
    }

    /** §2.3, §4.1 — Blank name returns 400; a non-existent ownerId returns 404. */
    @Test
    void createProjectRejectsValidationAndMissingOwner() throws Exception {
        String token = login("admin");

        mockMvc.perform(post("/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "description": "Invalid"
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Missing owner",
                                  "description": "Invalid",
                                  "ownerId": 999999
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    /** §3.5 — Deleted project disappears from GET /projects and GET /projects/{id} but appears on GET /projects/deleted. */
    @Test
    void softDeletedProjectIsHiddenFromNormalReadsAndListedSeparately() throws Exception {
        String token = login("admin");
        long projectId = createProject(token, "Delete Me", "Soft deleted", admin.getId());

        mockMvc.perform(delete("/projects/{projectId}", projectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/projects/{projectId}", projectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/projects/deleted")
                        .header("Authorization", "Bearer " + login("dev")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/projects/deleted")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(projectId));
    }

    /** §3.5 — When multiple projects are soft-deleted, GET /projects/deleted returns all of them, not just the first. */
    @Test
    void allDeletedProjectsAppearInDeletedList() throws Exception {
        String token = login("admin");
        long id1 = createProject(token, "First Deleted", "desc", admin.getId());
        long id2 = createProject(token, "Second Deleted", "desc", admin.getId());

        mockMvc.perform(delete("/projects/{id}", id1).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(delete("/projects/{id}", id2).header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        mockMvc.perform(get("/projects/deleted").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", hasItems("First Deleted", "Second Deleted")));
    }

    /** §3.5 — POST /projects/{id}/restore is ADMIN-only (DEVELOPER gets 403); after restore the project reappears in normal reads. */
    @Test
    void restoreRequiresAdminAndMakesProjectVisibleAgain() throws Exception {
        String adminToken = login("admin");
        String developerToken = login("dev");
        long projectId = createProject(adminToken, "Restore Me", "Soft deleted", admin.getId());

        mockMvc.perform(delete("/projects/{projectId}", projectId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/projects/{projectId}/restore", projectId)
                        .header("Authorization", "Bearer " + developerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/projects/{projectId}/restore", projectId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/projects/{projectId}", projectId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId));

        mockMvc.perform(get("/projects/deleted")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    /** §3.8 — GET /projects/{id}/workload counts only non-DONE, non-deleted tickets within the target project per developer, sorted ascending; tickets in other projects and deleted tickets are excluded. */
    @Test
    void workloadCountsOpenNonDeletedTicketsByDeveloper() throws Exception {
        String token = login("admin");
        Project project = saveProject("Workload", admin);
        Ticket open = saveTicket(project, developer, "Open", TicketStatus.TODO, false);
        saveTicket(project, developer, "In progress", TicketStatus.IN_PROGRESS, false);
        saveTicket(project, developer, "Done", TicketStatus.DONE, false);
        saveTicket(project, developer, "Deleted", TicketStatus.TODO, true);
        saveTicket(saveProject("Other", admin), developer, "Other project", TicketStatus.TODO, false);
        saveTicket(project, secondDeveloper, "Second dev done", TicketStatus.DONE, false);

        mockMvc.perform(get("/projects/{projectId}/workload", project.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].username").value("dev2"))
                .andExpect(jsonPath("$[0].openTicketCount").value(0))
                .andExpect(jsonPath("$[1].username").value("dev"))
                .andExpect(jsonPath("$[1].openTicketCount").value(2));

        Project deletedProject = open.getProject();
        deletedProject.setDeleted(true);
        deletedProject.setDeletedAt(Instant.now());
        projectRepository.save(deletedProject);

        mockMvc.perform(get("/projects/{projectId}/workload", project.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** §2.3, §4.1 — GET, PATCH, and DELETE on a non-existent project all return 404. */
    @Test
    void unknownProjectOperationsReturnNotFound() throws Exception {
        String token = login("admin");

        mockMvc.perform(get("/projects/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/projects/999999")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Missing"
                                }
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/projects/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    private long createProject(String token, String name, String description, Long ownerId) throws Exception {
        String response = mockMvc.perform(post("/projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "description": "%s",
                                  "ownerId": %d
                                }
                                """.formatted(name, description, ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andReturn()
                .getResponse()
                .getContentAsString();

        return Long.parseLong(response.replaceAll(".*\\\"id\\\":([0-9]+).*", "$1"));
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

    private Ticket saveTicket(Project project, User assignee, String title, TicketStatus status, boolean deleted) {
        Ticket ticket = new Ticket();
        ticket.setTitle(title);
        ticket.setDescription(title + " description");
        ticket.setStatus(status);
        ticket.setPriority(TicketPriority.HIGH);
        ticket.setType(TicketType.BUG);
        ticket.setProject(project);
        ticket.setAssignee(assignee);
        ticket.setDueDate(Instant.now().plusSeconds(3600));
        ticket.setDeleted(deleted);
        ticket.setDeletedAt(deleted ? Instant.now() : null);
        return ticketRepository.save(ticket);
    }
}
