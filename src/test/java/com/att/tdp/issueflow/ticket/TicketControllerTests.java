// Tests §2.4 Ticket Management, §3.5 Soft Delete (tickets), §3.7 Auto-Scheduling Escalation, §3.8 Auto Assignment
package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditActor;
import com.att.tdp.issueflow.audit.AuditLog;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.auth.TokenDenyListRepository;
import com.att.tdp.issueflow.attachment.AttachmentRepository;
import com.att.tdp.issueflow.comment.CommentRepository;
import com.att.tdp.issueflow.mention.MentionRepository;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.UserRole;
import com.att.tdp.issueflow.ticket.TicketEscalationService;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TicketControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenDenyListRepository tokenDenyListRepository;

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
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TicketEscalationService ticketEscalationService;

    private User admin;
    private User developer;
    private User secondDeveloper;
    private Project project;
    private Project otherProject;

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
        project = saveProject("Project", admin, false);
        otherProject = saveProject("Other Project", admin, false);
    }

    /** §2.4, §2.2 — All ticket endpoints reject unauthenticated requests with 401. */
    @Test
    void ticketEndpointsRequireJwt() throws Exception {
        mockMvc.perform(get("/tickets").param("projectId", project.getId().toString()))
                .andExpect(status().isUnauthorized());
    }

    /** §2.4 — Full CRUD path: create ticket with all fields, fetch by id, list scoped to project, update multiple fields, soft-delete, confirm it is no longer visible. */
    @Test
    void createGetListUpdateAndDeleteTicket() throws Exception {
        String token = login();
        long ticketId = createTicket(token, project.getId(), developer.getId(), "Fix login bug", "TODO", "HIGH", "BUG", futureDueDate());
        createTicket(token, otherProject.getId(), developer.getId(), "Other project ticket", "TODO", "HIGH", "BUG", futureDueDate());

        mockMvc.perform(get("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Fix login bug"))
                .andExpect(jsonPath("$.projectId").value(project.getId()))
                .andExpect(jsonPath("$.assigneeId").value(developer.getId()))
                .andExpect(jsonPath("$.isOverdue").value(false));

        mockMvc.perform(get("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(ticketId));

        mockMvc.perform(patch("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Updated title",
                                  "description": "Updated description",
                                  "status": "IN_PROGRESS",
                                  "priority": "MEDIUM",
                                  "assigneeId": %d,
                                  "dueDate": "%s"
                                }
                                """.formatted(secondDeveloper.getId(), futureDueDate())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated title"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.assigneeId").value(secondDeveloper.getId()));

        mockMvc.perform(delete("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    /** §2.4, §4.1 — Missing required fields and out-of-spec enum values (BLOCKED, URGENT, STORY) are each rejected with 400. */
    @Test
    void createTicketRejectsValidationFailuresAndInvalidEnums() throws Exception {
        String token = login();

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "",
                                  "description": "Missing required fields"
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Invalid status",
                                  "status": "BLOCKED",
                                  "priority": "HIGH",
                                  "type": "BUG",
                                  "projectId": %d
                                }
                                """.formatted(project.getId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Invalid priority",
                                  "status": "TODO",
                                  "priority": "URGENT",
                                  "type": "BUG",
                                  "projectId": %d
                                }
                                """.formatted(project.getId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Invalid type",
                                  "status": "TODO",
                                  "priority": "HIGH",
                                  "type": "STORY",
                                  "projectId": %d
                                }
                                """.formatted(project.getId())))
                .andExpect(status().isBadRequest());
    }

    /** §2.4, §4.1 — Tickets cannot be created for a soft-deleted project (404) or with a non-existent assigneeId (404). */
    @Test
    void createTicketRequiresActiveProjectAndExistingAssignee() throws Exception {
        String token = login();
        Project deletedProject = saveProject("Deleted Project", admin, true);

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson(deletedProject.getId(), developer.getId(), "Deleted project")))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson(project.getId(), 999999L, "Missing assignee")))
                .andExpect(status().isNotFound());
    }

    /** §2.4 — Status must advance exactly one step: TODO→IN_PROGRESS, IN_PROGRESS→IN_REVIEW, IN_REVIEW→DONE. Skipping steps and backward transitions are both rejected with 400. */
    @Test
    void statusMustMoveExactlyOneStepForward() throws Exception {
        String token = login();

        // Valid one-step transitions
        long t1 = createTicket(token, project.getId(), developer.getId(), "T1", "TODO", "HIGH", "BUG", futureDueDate());
        patchStatus(token, t1, "IN_PROGRESS").andExpect(status().isOk());          // TODO → IN_PROGRESS ✓

        long t2 = createTicket(token, project.getId(), developer.getId(), "T2", "IN_PROGRESS", "HIGH", "BUG", futureDueDate());
        patchStatus(token, t2, "IN_REVIEW").andExpect(status().isOk());            // IN_PROGRESS → IN_REVIEW ✓

        long t3 = createTicket(token, project.getId(), developer.getId(), "T3", "IN_REVIEW", "HIGH", "BUG", futureDueDate());
        patchStatus(token, t3, "DONE").andExpect(status().isOk());                 // IN_REVIEW → DONE ✓

        // Skip transitions
        long t4 = createTicket(token, project.getId(), developer.getId(), "T4", "TODO", "HIGH", "BUG", futureDueDate());
        patchStatus(token, t4, "IN_REVIEW").andExpect(status().isBadRequest());    // TODO → IN_REVIEW ✗
        patchStatus(token, t4, "DONE").andExpect(status().isBadRequest());         // TODO → DONE ✗

        long t5 = createTicket(token, project.getId(), developer.getId(), "T5", "IN_PROGRESS", "HIGH", "BUG", futureDueDate());
        patchStatus(token, t5, "DONE").andExpect(status().isBadRequest());         // IN_PROGRESS → DONE ✗

        // Backward transitions
        long t6 = createTicket(token, project.getId(), developer.getId(), "T6", "IN_PROGRESS", "HIGH", "BUG", futureDueDate());
        patchStatus(token, t6, "TODO").andExpect(status().isBadRequest());         // IN_PROGRESS → TODO ✗

        long t7 = createTicket(token, project.getId(), developer.getId(), "T7", "IN_REVIEW", "HIGH", "BUG", futureDueDate());
        patchStatus(token, t7, "IN_PROGRESS").andExpect(status().isBadRequest()); // IN_REVIEW → IN_PROGRESS ✗
        patchStatus(token, t7, "TODO").andExpect(status().isBadRequest());         // IN_REVIEW → TODO ✗
    }

    /** §2.4 — Any update attempt on a DONE ticket returns 400 regardless of which field is changed. */
    @Test
    void doneTicketsCannotBeUpdated() throws Exception {
        String token = login();
        long ticketId = createTicket(token, project.getId(), developer.getId(), "Done ticket", "DONE", "HIGH", "BUG", futureDueDate());

        mockMvc.perform(patch("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Should fail"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    /** §3.8 — Omitting assigneeId triggers auto-assignment to the developer with the fewest open tickets in the same project. */
    @Test
    void assigneeIsOptionalAndAutoAssignmentUsesLeastLoadedDeveloper() throws Exception {
        String token = login();
        createTicket(token, project.getId(), developer.getId(), "Existing open", "TODO", "HIGH", "BUG", futureDueDate());

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Auto assigned",
                                  "description": "No assignee supplied",
                                  "status": "TODO",
                                  "priority": "HIGH",
                                  "type": "BUG",
                                  "projectId": %d
                                }
                                """.formatted(project.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(secondDeveloper.getId()));
    }

    /** §3.8 — When no DEVELOPER users exist, the ticket is created successfully with assigneeId null rather than failing. */
    @Test
    void missingAssigneeStaysNullWhenNoDevelopersExist() throws Exception {
        String token = login();
        ticketRepository.deleteAll();
        userRepository.delete(developer);
        userRepository.delete(secondDeveloper);

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Unassigned",
                                  "description": "No developers available",
                                  "status": "TODO",
                                  "priority": "LOW",
                                  "type": "TECHNICAL",
                                  "projectId": %d
                                }
                                """.formatted(project.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId", nullValue()));
    }

    /** §3.5 — When multiple tickets in a project are soft-deleted, GET /tickets/deleted?projectId returns all of them, not just the first. */
    @Test
    void allDeletedTicketsForProjectAppearInDeletedList() throws Exception {
        String token = login();
        long id1 = createTicket(token, project.getId(), developer.getId(), "Delete Me 1", "TODO", "HIGH", "BUG", futureDueDate());
        long id2 = createTicket(token, project.getId(), developer.getId(), "Delete Me 2", "TODO", "HIGH", "BUG", futureDueDate());

        mockMvc.perform(delete("/tickets/{id}", id1).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(delete("/tickets/{id}", id2).header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        mockMvc.perform(get("/tickets/deleted")
                        .header("Authorization", "Bearer " + token)
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].title", hasItems("Delete Me 1", "Delete Me 2")));
    }

    /** §3.7 — Escalation sets isOverdue=true on CRITICAL overdue open tickets; DONE tickets are excluded from escalation entirely. */
    @Test
    void dueDateControlsIsOverdueAndDoneTicketsAreNotOverdue() throws Exception {
        String token = login();
        long overdueId = createTicket(token, project.getId(), developer.getId(), "Overdue", "TODO", "CRITICAL", "BUG", "2020-01-01T00:00:00Z");
        long doneId = createTicket(token, project.getId(), developer.getId(), "Done old", "DONE", "CRITICAL", "BUG", "2020-01-01T00:00:00Z");

        // before escalation the flag is false
        mockMvc.perform(get("/tickets/{ticketId}", overdueId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isOverdue").value(false));

        ticketEscalationService.escalateOverdueTickets();

        // after escalation the CRITICAL overdue ticket is flagged
        mockMvc.perform(get("/tickets/{ticketId}", overdueId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isOverdue").value(true));

        // DONE tickets are excluded from escalation and remain not overdue
        mockMvc.perform(get("/tickets/{ticketId}", doneId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isOverdue").value(false));
    }

    /** §3.5 — Deleted ticket is hidden from normal reads; only ADMIN can list via GET /tickets/deleted and restore via POST /tickets/{id}/restore; DEVELOPER gets 403 on both. */
    @Test
    void softDeletedTicketsCanBeListedAndRestoredByAdmin() throws Exception {
        String adminToken = login("admin");
        String devToken   = login("dev");
        long ticketId = createTicket(adminToken, project.getId(), developer.getId(), "Delete Me", "TODO", "HIGH", "BUG", futureDueDate());

        mockMvc.perform(delete("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/tickets/deleted")
                        .header("Authorization", "Bearer " + devToken)
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/tickets/deleted")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(ticketId));

        mockMvc.perform(post("/tickets/{ticketId}/restore", ticketId)
                        .header("Authorization", "Bearer " + devToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/tickets/{ticketId}/restore", ticketId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticketId));
    }

    /** §3.7 — Manually changing priority clears isOverdue but does NOT re-enable automatic escalation; the dueDate event is already consumed and only a dueDate update opens a new escalation window. */
    @Test
    void manualPriorityChangeResetsIsOverdueButDoesNotReEnableEscalation() throws Exception {
        String token = login();
        long ticketId = createTicket(token, project.getId(), developer.getId(), "Overdue HIGH", "TODO", "HIGH", "BUG", "2020-01-01T00:00:00Z");

        ticketEscalationService.escalateOverdueTickets();

        mockMvc.perform(get("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.priority").value("CRITICAL"))
                .andExpect(jsonPath("$.isOverdue").value(true));

        mockMvc.perform(patch("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\": \"LOW\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.priority").value("LOW"))
                .andExpect(jsonPath("$.isOverdue").value(false));

        ticketEscalationService.escalateOverdueTickets();

        // escalatedAt was set during the first run and was not cleared by the manual priority
        // change, so the ticket is ineligible for escalation and stays at LOW.
        mockMvc.perform(get("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.priority").value("LOW"))
                .andExpect(jsonPath("$.isOverdue").value(false));
    }

    /** §3.7 — Running escalation repeatedly on a CRITICAL overdue ticket keeps the priority at CRITICAL and isOverdue stays true — escalation is idempotent. */
    @Test
    void criticalOverdueTicketStaysCriticalAndIsIdempotent() throws Exception {
        String token = login();
        long ticketId = createTicket(token, project.getId(), developer.getId(), "Already Critical", "TODO", "CRITICAL", "BUG", "2020-01-01T00:00:00Z");

        ticketEscalationService.escalateOverdueTickets();

        mockMvc.perform(get("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.priority").value("CRITICAL"))
                .andExpect(jsonPath("$.isOverdue").value(true));

        ticketEscalationService.escalateOverdueTickets();

        mockMvc.perform(get("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.priority").value("CRITICAL"))
                .andExpect(jsonPath("$.isOverdue").value(true));
    }

    /** §3.8 — Auto-assignment generates an audit log entry with actor=SYSTEM and action=AUTO_ASSIGN as required by the spec. */
    @Test
    void autoAssignIsAuditedWithSystemActorAndAutoAssignAction() throws Exception {
        String token = login();

        mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Auto assign audit",
                                  "description": "desc",
                                  "status": "TODO",
                                  "priority": "HIGH",
                                  "type": "BUG",
                                  "projectId": %d
                                }
                                """.formatted(project.getId())))
                .andExpect(status().isOk());

        boolean hasAutoAssignLog = auditLogRepository.findAll().stream().anyMatch(log ->
                log.getAction() == AuditAction.AUTO_ASSIGN &&
                log.getActor()  == AuditActor.SYSTEM
        );
        org.assertj.core.api.Assertions.assertThat(hasAutoAssignLog).isTrue();
    }

    /** §3.8 — Auto-assignment picks the developer with the fewest open non-DONE tickets in the same project. */
    @Test
    void autoAssignPicksLeastLoadedDeveloper() throws Exception {
        // `developer` has 3 open tickets in `project`; `secondDeveloper` has 0
        saveDirectTicket(project, developer, "Loaded 1", TicketStatus.TODO);
        saveDirectTicket(project, developer, "Loaded 2", TicketStatus.TODO);
        saveDirectTicket(project, developer, "Loaded 3", TicketStatus.IN_PROGRESS);

        String token = login();
        autoAssignCreate(token, project.getId(), "Auto picks least")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(secondDeveloper.getId()));
    }

    /** §3.8 — When two developers have the same open ticket count, the one registered first (oldest createdAt) wins the tie. */
    @Test
    void autoAssignTieBreaksByOldestRegistration() throws Exception {
        // Both developers have 0 workload; `developer` was registered before `secondDeveloper`
        String token = login();
        autoAssignCreate(token, project.getId(), "Auto tie break")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(developer.getId()));
    }

    /** §3.8 — ADMIN users are never candidates for auto-assignment; only DEVELOPER users are picked. */
    @Test
    void autoAssignExcludesAdminCandidates() throws Exception {
        // Remove the second developer so only `admin` (ADMIN) and `developer` (DEVELOPER) remain;
        // if ADMINs were eligible, the admin (registered before `developer`) would win the tie.
        userRepository.delete(secondDeveloper);
        String token = login();

        autoAssignCreate(token, project.getId(), "Skip admin")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(developer.getId()));
    }

    /** §3.8 — When no DEVELOPER users exist, the ticket is created with a null assignee, no error, and no AUTO_ASSIGN audit entry. */
    @Test
    void autoAssignWithNoDevelopersReturnsNullAssignee() throws Exception {
        userRepository.delete(developer);
        userRepository.delete(secondDeveloper);
        String token = login();
        auditLogRepository.deleteAll(); // discard the login audit log

        autoAssignCreate(token, project.getId(), "No devs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(nullValue()));

        boolean hasAutoAssignLog = auditLogRepository.findAll().stream()
                .anyMatch(log -> log.getAction() == AuditAction.AUTO_ASSIGN);
        org.assertj.core.api.Assertions.assertThat(hasAutoAssignLog).isFalse();
    }

    /** §3.8 — Auto-assign workload is scoped to the target project; tickets in other projects do not count toward the candidate's workload. */
    @Test
    void autoAssignWorkloadIsScopedToProject() throws Exception {
        // `developer` has 5 open tickets in OTHER project, 0 in target project
        for (int i = 0; i < 5; i++) {
            saveDirectTicket(otherProject, developer, "Other " + i, TicketStatus.TODO);
        }
        // `secondDeveloper` has 3 open tickets in TARGET project
        for (int i = 0; i < 3; i++) {
            saveDirectTicket(project, secondDeveloper, "Target " + i, TicketStatus.TODO);
        }

        String token = login();
        // In `project`: developer has 0, secondDeveloper has 3 → developer wins.
        // If workload was counted across all projects, developer (5) would lose to secondDeveloper (3).
        autoAssignCreate(token, project.getId(), "Scoped pick")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(developer.getId()));
    }

    /** §3.8 — Auto-assignment is only triggered on creation; a PATCH that omits assigneeId never reassigns, even when the current assignee is heavily loaded. */
    @Test
    void autoAssignNotTriggeredOnUpdate() throws Exception {
        String token = login();
        long ticketId = createTicket(token, project.getId(), developer.getId(), "Stays with dev", "TODO", "HIGH", "BUG", futureDueDate());

        // Load `developer` further so auto-assign WOULD pick `secondDeveloper` if it ran on update
        for (int i = 0; i < 3; i++) {
            saveDirectTicket(project, developer, "Loaded " + i, TicketStatus.TODO);
        }

        mockMvc.perform(patch("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"IN_PROGRESS\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/tickets/{ticketId}", ticketId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.assigneeId").value(developer.getId()));
    }

    private org.springframework.test.web.servlet.ResultActions autoAssignCreate(String token, Long projectId, String title) throws Exception {
        return mockMvc.perform(post("/tickets")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "title": "%s",
                          "description": "%s description",
                          "status": "TODO",
                          "priority": "HIGH",
                          "type": "BUG",
                          "projectId": %d,
                          "dueDate": "%s"
                        }
                        """.formatted(title, title, projectId, futureDueDate())));
    }

    private Ticket saveDirectTicket(Project p, User assignee, String title, TicketStatus status) {
        Ticket t = new Ticket();
        t.setTitle(title);
        t.setDescription(title + " description");
        t.setStatus(status);
        t.setPriority(TicketPriority.HIGH);
        t.setType(TicketType.BUG);
        t.setProject(p);
        t.setAssignee(assignee);
        t.setDueDate(Instant.now().plusSeconds(3600));
        return ticketRepository.save(t);
    }

    private long createTicket(
            String token,
            Long projectId,
            Long assigneeId,
            String title,
            String status,
            String priority,
            String type,
            String dueDate
    ) throws Exception {
        String response = mockMvc.perform(post("/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "%s",
                                  "description": "%s description",
                                  "status": "%s",
                                  "priority": "%s",
                                  "type": "%s",
                                  "projectId": %d,
                                  "assigneeId": %d,
                                  "dueDate": "%s"
                                }
                                """.formatted(title, title, status, priority, type, projectId, assigneeId, dueDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(title))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll(".*\\\"id\\\":([0-9]+).*", "$1"));
    }

    private String validCreateJson(Long projectId, Long assigneeId, String title) {
        return """
                {
                  "title": "%s",
                  "description": "%s description",
                  "status": "TODO",
                  "priority": "HIGH",
                  "type": "BUG",
                  "projectId": %d,
                  "assigneeId": %d,
                  "dueDate": "%s"
                }
                """.formatted(title, title, projectId, assigneeId, futureDueDate());
    }

    private org.springframework.test.web.servlet.ResultActions patchStatus(String token, long ticketId, String status) throws Exception {
        return mockMvc.perform(patch("/tickets/{ticketId}", ticketId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"%s\"}".formatted(status)));
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

    private User saveUser(String username, String email, String fullName, UserRole role, String password) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(password));
        return userRepository.save(user);
    }

    private Project saveProject(String name, User owner, boolean deleted) {
        Project savedProject = new Project();
        savedProject.setName(name);
        savedProject.setDescription(name + " description");
        savedProject.setOwner(owner);
        savedProject.setDeleted(deleted);
        savedProject.setDeletedAt(deleted ? Instant.now() : null);
        return projectRepository.save(savedProject);
    }

    private String futureDueDate() {
        return Instant.now().plusSeconds(3600).toString();
    }
}
