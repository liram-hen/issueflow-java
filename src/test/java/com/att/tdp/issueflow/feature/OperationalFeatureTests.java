// Tests §3.1 Audit Log, §3.2 Ticket Dependencies, §3.3 Attachment Management, §3.4 Ticket Export & Import, §3.7 Auto-Scheduling Escalation
package com.att.tdp.issueflow.feature;

import com.att.tdp.issueflow.attachment.AttachmentRepository;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditActor;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditLogRepository;
import com.att.tdp.issueflow.auth.TokenDenyListRepository;
import com.att.tdp.issueflow.comment.CommentRepository;
import com.att.tdp.issueflow.mention.MentionRepository;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketDependencyRepository;
import com.att.tdp.issueflow.ticket.TicketEscalationService;
import com.att.tdp.issueflow.ticket.TicketPriority;
import com.att.tdp.issueflow.ticket.TicketRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OperationalFeatureTests {

    @Autowired
    private MockMvc mockMvc;

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
    private TokenDenyListRepository tokenDenyListRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TicketEscalationService ticketEscalationService;

    private User admin;
    private User developer;
    private Project project;
    private Ticket ticket;
    private Ticket blocker;

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

        admin = saveUser("admin", UserRole.ADMIN);
        developer = saveUser("dev", UserRole.DEVELOPER);
        project = saveProject();
        ticket = saveTicket("Dependent", TicketStatus.TODO, TicketPriority.LOW, false, Instant.now().plusSeconds(3600));
        blocker = saveTicket("Blocker", TicketStatus.IN_PROGRESS, TicketPriority.HIGH, false, Instant.now().plusSeconds(3600));
    }

    /** §3.1 — State-changing API calls create audit log entries; GET /audit-logs filtered by entityType and action returns matching entries with correct actor and performedBy fields. */
    @Test
    void auditLogsStateChangingActionsAndCanBeFiltered() throws Exception {
        String token = login();

        mockMvc.perform(post("/tickets/{ticketId}/dependencies", ticket.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "blockedBy": %d
                                }
                                """.formatted(blocker.getId())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/audit-logs")
                        .header("Authorization", "Bearer " + token)
                        .param("entityType", "DEPENDENCY")
                        .param("action", "CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].actor").value("USER"))
                .andExpect(jsonPath("$[0].performedBy").value(admin.getId()));
    }

    /** §3.2 — Self-referential and duplicate dependencies are rejected; list returns blocker details; delete removes the dependency and a second delete returns 404. */
    @Test
    void dependencyCrudValidatesSelfDuplicateAndMissingRows() throws Exception {
        String token = login();

        mockMvc.perform(post("/tickets/{ticketId}/dependencies", ticket.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "blockedBy": %d
                                }
                                """.formatted(ticket.getId())))
                .andExpect(status().isBadRequest());

        addDependency(token);

        mockMvc.perform(post("/tickets/{ticketId}/dependencies", ticket.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "blockedBy": %d
                                }
                                """.formatted(blocker.getId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/tickets/{ticketId}/dependencies", ticket.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(blocker.getId()))
                .andExpect(jsonPath("$[0].title").value("Blocker"))
                .andExpect(jsonPath("$[0].status").value("IN_PROGRESS"));

        mockMvc.perform(delete("/tickets/{ticketId}/dependencies/{blockerId}", ticket.getId(), blocker.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/tickets/{ticketId}/dependencies/{blockerId}", ticket.getId(), blocker.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** §3.3 — File upload stores content, filename, contentType, and size in the database; delete removes the record entirely. */
    @Test
    void attachmentUploadAndDeletePersistBinaryMetadata() throws Exception {
        String token = login();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "screenshot.png",
                "image/png",
                new byte[]{1, 2, 3}
        );

        String response = mockMvc.perform(multipart("/tickets/{ticketId}/attachments", ticket.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(ticket.getId()))
                .andExpect(jsonPath("$.filename").value("screenshot.png"))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long attachmentId = Long.valueOf(response.replaceFirst("^\\{\\\"id\\\":([0-9]+).*", "$1"));
        assertThat(attachmentRepository.findById(attachmentId)).get().satisfies(attachment -> {
            assertThat(attachment.getSize()).isEqualTo(3);
            assertThat(attachment.getContent()).containsExactly(1, 2, 3);
        });

        mockMvc.perform(delete("/tickets/{ticketId}/attachments/{attachmentId}", ticket.getId(), attachmentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(attachmentRepository.findById(attachmentId)).isEmpty();
    }

    /** §3.4 — Export produces a correctly headed CSV containing ticket data; import creates valid rows and reports failed rows with error details in the response summary. */
    @Test
    void csvExportAndImportWorkWithPartialFailures() throws Exception {
        String token = login();

        mockMvc.perform(get("/tickets/export")
                        .header("Authorization", "Bearer " + token)
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString("id,title,description,status,priority,type,assigneeId")))
                .andExpect(content().string(containsString("Dependent")));

        MockMultipartFile csv = new MockMultipartFile(
                "file",
                "tickets.csv",
                "text/csv",
                """
                        id,title,description,status,priority,type,assigneeId
                        ,Imported one,Imported description,TODO,MEDIUM,TECHNICAL,%d
                        ,Bad status,Imported description,BLOCKED,MEDIUM,TECHNICAL,%d
                        """.formatted(developer.getId(), developer.getId()).getBytes()
        );

        mockMvc.perform(multipart("/tickets/import")
                        .file(csv)
                        .param("projectId", project.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.errors", hasSize(1)));

        assertThat(ticketRepository.findAllByProjectIdAndDeletedFalse(project.getId()))
                .extracting(Ticket::getTitle)
                .contains("Imported one");
    }

    /** §3.4 — Export data row contains all seven required fields with correct values for a known ticket. */
    @Test
    void exportDataRowContainsAllRequiredFields() throws Exception {
        String token = login();

        String csv = mockMvc.perform(get("/tickets/export")
                        .header("Authorization", "Bearer " + token)
                        .param("projectId", project.getId().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Verify each required field appears in the CSV (ticket is Dependent/TODO/LOW/BUG with developer as assignee)
        assertThat(csv)
                .contains("Dependent description")            // description
                .contains(",TODO,")                           // status
                .contains(",LOW,")                            // priority
                .contains(",BUG,")                            // type
                .contains("," + developer.getId());           // assigneeId
    }

    /** §3.4 — CSV export correctly quotes fields containing commas and double-quotes; import correctly unquotes them, preserving the original value. */
    @Test
    void csvHandlesCommasAndQuotesInFieldValues() throws Exception {
        String token = login();

        // Create a ticket whose title contains both a comma and double-quotes
        saveTicket("Fix \"login\" bug, please", TicketStatus.TODO, TicketPriority.HIGH, false, Instant.now().plusSeconds(3600));

        String exported = mockMvc.perform(get("/tickets/export")
                        .header("Authorization", "Bearer " + token)
                        .param("projectId", project.getId().toString()))
                .andReturn().getResponse().getContentAsString();

        // Apache Commons CSV must wrap the field in quotes and double the internal quote
        assertThat(exported).contains("\"Fix \"\"login\"\" bug, please\"");

        // Import a CSV row that uses the same quoting — parser must restore the original value
        MockMultipartFile csv = new MockMultipartFile("file", "tickets.csv", "text/csv",
                """
                id,title,description,status,priority,type,assigneeId
                ,"Fix ""login"" bug, please",Some description,TODO,HIGH,BUG,
                """.getBytes());

        mockMvc.perform(multipart("/tickets/import")
                        .file(csv)
                        .param("projectId", project.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.failed").value(0));

        assertThat(ticketRepository.findAllByProjectIdAndDeletedFalse(project.getId()))
                .extracting(Ticket::getTitle)
                .contains("Fix \"login\" bug, please");
    }

    /** §3.4 — Imported ticket has all field values exactly as specified in the CSV row. */
    @Test
    void importCreatesTicketWithCorrectFieldValues() throws Exception {
        String token = login();

        MockMultipartFile csv = new MockMultipartFile("file", "tickets.csv", "text/csv",
                """
                id,title,description,status,priority,type,assigneeId
                ,Imported ticket,Imported description,TODO,HIGH,FEATURE,%d
                """.formatted(developer.getId()).getBytes());

        mockMvc.perform(multipart("/tickets/import")
                        .file(csv)
                        .param("projectId", project.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.failed").value(0));

        Ticket imported = ticketRepository.findAllByProjectIdAndDeletedFalse(project.getId())
                .stream().filter(t -> t.getTitle().equals("Imported ticket")).findFirst().orElseThrow();

        assertThat(imported.getDescription()).isEqualTo("Imported description");
        assertThat(imported.getStatus()).isEqualTo(TicketStatus.TODO);
        assertThat(imported.getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(imported.getType()).isEqualTo(TicketType.FEATURE);
        assertThat(imported.getAssignee().getId()).isEqualTo(developer.getId());
    }

    /** §3.4, §3.8 — Import auto-assigns the least-loaded developer when assigneeId is blank in the CSV row. */
    @Test
    void importAutoAssignsWhenAssigneeIdIsBlank() throws Exception {
        String token = login();

        MockMultipartFile csv = new MockMultipartFile("file", "tickets.csv", "text/csv",
                """
                id,title,description,status,priority,type,assigneeId
                ,Auto assign import,Some description,TODO,MEDIUM,BUG,
                """.getBytes());

        mockMvc.perform(multipart("/tickets/import")
                        .file(csv)
                        .param("projectId", project.getId().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        Ticket imported = ticketRepository.findAllByProjectIdAndDeletedFalse(project.getId())
                .stream().filter(t -> t.getTitle().equals("Auto assign import")).findFirst().orElseThrow();

        assertThat(imported.getAssignee()).isNotNull();
        // getRole() triggers lazy loading — reload the user from the repo to avoid LazyInitializationException
        Long assigneeId = imported.getAssignee().getId();
        assertThat(userRepository.findById(assigneeId).orElseThrow().getRole()).isEqualTo(UserRole.DEVELOPER);
    }

    /** §3.7 — Scheduler promotes priority on overdue open tickets only; DONE, deleted, and future-dated tickets are untouched; the escalation is recorded as a SYSTEM UPDATE in the audit log. */
    @Test
    void escalationRaisesOverdueOpenTicketsOnlyAndAuditsSystemAction() {
        Ticket medium = saveTicket("Medium overdue", TicketStatus.IN_PROGRESS, TicketPriority.MEDIUM, false, Instant.now().minusSeconds(60));
        Ticket done = saveTicket("Done overdue", TicketStatus.DONE, TicketPriority.LOW, false, Instant.now().minusSeconds(60));
        Ticket deleted = saveTicket("Deleted overdue", TicketStatus.TODO, TicketPriority.LOW, true, Instant.now().minusSeconds(60));
        Ticket future = saveTicket("Future", TicketStatus.TODO, TicketPriority.LOW, false, Instant.now().plusSeconds(60));

        ticketEscalationService.escalateOverdueTickets();

        assertThat(ticketRepository.findById(ticket.getId())).get().extracting(Ticket::getPriority).isEqualTo(TicketPriority.LOW);
        assertThat(ticketRepository.findById(medium.getId())).get().extracting(Ticket::getPriority).isEqualTo(TicketPriority.HIGH);
        assertThat(ticketRepository.findById(done.getId())).get().extracting(Ticket::getPriority).isEqualTo(TicketPriority.LOW);
        assertThat(ticketRepository.findById(deleted.getId())).get().extracting(Ticket::getPriority).isEqualTo(TicketPriority.LOW);
        assertThat(ticketRepository.findById(future.getId())).get().extracting(Ticket::getPriority).isEqualTo(TicketPriority.LOW);
        assertThat(auditLogRepository.findAll()).anySatisfy(log -> {
            assertThat(log.getActor().name()).isEqualTo("SYSTEM");
            assertThat(log.getEntityType()).isEqualTo(AuditEntityType.TICKET);
            assertThat(log.getAction()).isEqualTo(AuditAction.UPDATE);
            assertThat(log.getEntityId()).isEqualTo(medium.getId());
        });
    }

    /** §3.1 — GET /audit-logs without any query parameters returns all logged entries, confirming the endpoint works without filters. */
    @Test
    void auditLogsCanBeRetrievedUnfiltered() throws Exception {
        String token = login();
        addDependency(token);

        mockMvc.perform(get("/audit-logs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(org.hamcrest.Matchers.greaterThan(0))));
    }

    /** §3.2 — A ticket with at least one blocker that is not yet DONE cannot be transitioned to DONE; the attempt is rejected with 400. */
    @Test
    void cannotTransitionToDoneWithUnresolvedBlockers() throws Exception {
        String token = login();
        addDependency(token);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/tickets/{ticketId}", ticket.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"DONE\"}"))
                .andExpect(status().isBadRequest());
    }

    /** §3.2 — A direct cycle (A blocked_by B, then B blocked_by A) is rejected with 400; both tickets would otherwise be permanently unresolvable. */
    @Test
    void directCircularDependencyIsRejected() throws Exception {
        String token = login();
        addDependency(token); // ticket (A) is blocked by blocker (B)

        // Trying to make B blocked by A would close the A↔B cycle
        mockMvc.perform(post("/tickets/{ticketId}/dependencies", blocker.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockedBy\": %d}".formatted(ticket.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(
                        org.hamcrest.Matchers.containsString("circular")));
    }

    /** §3.2 — An indirect cycle (A blocked_by B, B blocked_by C, then C blocked_by A) is rejected with 400; all three tickets would otherwise be permanently unresolvable. */
    @Test
    void indirectCircularDependencyIsRejected() throws Exception {
        String token = login();
        Ticket ticketC = saveTicket("Ticket C", TicketStatus.TODO, TicketPriority.HIGH, false, Instant.now().plusSeconds(3600));

        addDependency(token); // A blocked_by B

        mockMvc.perform(post("/tickets/{ticketId}/dependencies", blocker.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockedBy\": %d}".formatted(ticketC.getId())))
                .andExpect(status().isOk()); // B blocked_by C

        // Trying to make C blocked by A would complete the A→B→C→A cycle
        mockMvc.perform(post("/tickets/{ticketId}/dependencies", ticketC.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockedBy\": %d}".formatted(ticket.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages[0]").value(
                        org.hamcrest.Matchers.containsString("circular")));
    }

    /** §3.2 — A soft-deleted blocker is treated as resolved; once the blocker is soft-deleted the dependent ticket can transition to DONE even though it was blocked before. */
    @Test
    void softDeletedBlockerCountsAsResolved() throws Exception {
        String token = login();

        // Start a ticket at IN_REVIEW so it can reach DONE in one step
        Ticket ticketToClose = saveTicket("Ready to close", TicketStatus.IN_REVIEW, TicketPriority.HIGH, false, Instant.now().plusSeconds(3600));

        // Block it with the blocker (IN_PROGRESS — unresolved)
        mockMvc.perform(post("/tickets/{ticketId}/dependencies", ticketToClose.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockedBy\": %d}".formatted(blocker.getId())))
                .andExpect(status().isOk());

        // Confirm DONE is blocked while the blocker is still active
        mockMvc.perform(patch("/tickets/{ticketId}", ticketToClose.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"DONE\"}"))
                .andExpect(status().isBadRequest());

        // Soft-delete the blocker
        mockMvc.perform(delete("/tickets/{ticketId}", blocker.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Deleted blocker is resolved — DONE should now succeed
        mockMvc.perform(patch("/tickets/{ticketId}", ticketToClose.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"DONE\"}"))
                .andExpect(status().isOk());
    }

    /** §3.2 — Adding a dependency between tickets in different projects is rejected with 400; both tickets must belong to the same project. */
    @Test
    void crossProjectDependencyIsRejected() throws Exception {
        String token = login();

        Project other = new Project();
        other.setName("Other Project");
        other.setDescription("Other");
        other.setOwner(admin);
        projectRepository.save(other);

        Ticket otherTicket = new Ticket();
        otherTicket.setTitle("Other project ticket");
        otherTicket.setDescription("desc");
        otherTicket.setStatus(TicketStatus.TODO);
        otherTicket.setPriority(TicketPriority.HIGH);
        otherTicket.setType(TicketType.BUG);
        otherTicket.setProject(other);
        otherTicket.setAssignee(developer);
        otherTicket.setDueDate(Instant.now().plusSeconds(3600));
        ticketRepository.save(otherTicket);

        mockMvc.perform(post("/tickets/{ticketId}/dependencies", ticket.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"blockedBy\": %d}".formatted(otherTicket.getId())))
                .andExpect(status().isBadRequest());
    }

    /** §3.3 — image/jpeg, application/pdf, and text/plain uploads are all accepted alongside image/png. */
    @Test
    void allAllowedMimeTypesAreAccepted() throws Exception {
        String token = login();

        for (String[] typeAndName : new String[][]{
                {"image/jpeg",       "photo.jpg"},
                {"application/pdf",  "doc.pdf"},
                {"text/plain",       "notes.txt"}
        }) {
            MockMultipartFile file = new MockMultipartFile("file", typeAndName[1], typeAndName[0], new byte[]{1, 2, 3});
            mockMvc.perform(multipart("/tickets/{ticketId}/attachments", ticket.getId())
                            .file(file)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contentType").value(typeAndName[0]));
        }
    }

    /** §3.3 — Content-Type headers with charset parameters (e.g. "text/plain; charset=utf-8") are accepted; the charset is stripped before the type check so valid files are not incorrectly rejected. */
    @Test
    void contentTypeWithCharsetParameterIsAccepted() throws Exception {
        String token = login();
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain; charset=utf-8", "hello".getBytes());

        mockMvc.perform(multipart("/tickets/{ticketId}/attachments", ticket.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("text/plain")); // stored as normalised type
    }

    /** §3.3 — Files larger than 10 MB are rejected with 400 before any persistence occurs. */
    @Test
    void attachmentExceedingSizeLimitIsRejected() throws Exception {
        String token = login();
        byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", tooLarge);

        mockMvc.perform(multipart("/tickets/{ticketId}/attachments", ticket.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** §3.3 — Files with MIME types outside image/png, image/jpeg, application/pdf, text/plain are rejected with 400. */
    @Test
    void attachmentWithDisallowedMimeTypeIsRejected() throws Exception {
        String token = login();
        MockMultipartFile file = new MockMultipartFile(
                "file", "script.js", "application/javascript", "console.log('x')".getBytes()
        );

        mockMvc.perform(multipart("/tickets/{ticketId}/attachments", ticket.getId())
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** §3.1 — entityId filter returns only logs for that specific entity and returns empty for an unknown id. */
    @Test
    void entityIdFilterReturnsLogsForThatEntityOnly() throws Exception {
        String token = login();
        auditLogRepository.deleteAll();  // discard the login audit entry
        addDependency(token);

        Long dependencyEntityId = auditLogRepository.findAll().get(0).getEntityId();

        mockMvc.perform(get("/audit-logs")
                        .header("Authorization", "Bearer " + token)
                        .param("entityId", dependencyEntityId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].entityId").value(dependencyEntityId));

        mockMvc.perform(get("/audit-logs")
                        .header("Authorization", "Bearer " + token)
                        .param("entityId", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    /** §3.1 — actor=SYSTEM returns only scheduler-generated logs; actor=USER returns only human-triggered logs. */
    @Test
    void actorFilterDistinguishesUserAndSystemLogs() throws Exception {
        String token = login();
        auditLogRepository.deleteAll();  // discard the login audit entry
        addDependency(token);

        Ticket overdueTicket = saveTicket("Overdue for actor test", TicketStatus.TODO, TicketPriority.LOW, false, Instant.now().minusSeconds(60));
        ticketEscalationService.escalateOverdueTickets();

        mockMvc.perform(get("/audit-logs")
                        .header("Authorization", "Bearer " + token)
                        .param("actor", "SYSTEM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].actor").value("SYSTEM"));

        mockMvc.perform(get("/audit-logs")
                        .header("Authorization", "Bearer " + token)
                        .param("actor", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].actor").value("USER"));
    }

    /** §3.1 — entityType and action each work as standalone filters; unmatched values return an empty list. */
    @Test
    void singleFieldFiltersWorkInIsolation() throws Exception {
        String token = login();
        auditLogRepository.deleteAll();  // discard the login audit entry
        addDependency(token);

        mockMvc.perform(get("/audit-logs")
                        .header("Authorization", "Bearer " + token)
                        .param("entityType", "DEPENDENCY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get("/audit-logs")
                        .header("Authorization", "Bearer " + token)
                        .param("entityType", "TICKET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get("/audit-logs")
                        .header("Authorization", "Bearer " + token)
                        .param("action", "CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get("/audit-logs")
                        .header("Authorization", "Bearer " + token)
                        .param("action", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    /** §3.7 — Escalation never changes a ticket's status; only its priority is promoted. */
    @Test
    void escalationDoesNotModifyStatus() {
        Ticket overdue = saveTicket("Overdue status check", TicketStatus.IN_PROGRESS, TicketPriority.MEDIUM, false, Instant.now().minusSeconds(60));

        ticketEscalationService.escalateOverdueTickets();

        assertThat(ticketRepository.findById(overdue.getId())).get().satisfies(t -> {
            assertThat(t.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            assertThat(t.getPriority()).isEqualTo(TicketPriority.HIGH);
        });
    }

    /** §3.7 — Tickets without a dueDate are never eligible for escalation regardless of priority. */
    @Test
    void escalationExcludesTicketsWithNoDueDate() {
        Ticket noDueDate = saveTicket("No due date", TicketStatus.TODO, TicketPriority.LOW, false, null);

        ticketEscalationService.escalateOverdueTickets();

        assertThat(ticketRepository.findById(noDueDate.getId())).get()
                .extracting(Ticket::getPriority).isEqualTo(TicketPriority.LOW);
    }

    /** §3.7 — Each priority level escalates exactly one step: LOW→MEDIUM, MEDIUM→HIGH, HIGH→CRITICAL; CRITICAL stays at CRITICAL. */
    @Test
    void allPriorityLevelsEscalateExactlyOneStep() {
        Ticket low    = saveTicket("Low overdue",      TicketStatus.TODO, TicketPriority.LOW,      false, Instant.now().minusSeconds(60));
        Ticket medium = saveTicket("Medium overdue",   TicketStatus.TODO, TicketPriority.MEDIUM,   false, Instant.now().minusSeconds(60));
        Ticket high   = saveTicket("High overdue",     TicketStatus.TODO, TicketPriority.HIGH,     false, Instant.now().minusSeconds(60));
        Ticket crit   = saveTicket("Critical overdue", TicketStatus.TODO, TicketPriority.CRITICAL, false, Instant.now().minusSeconds(60));

        ticketEscalationService.escalateOverdueTickets();

        assertThat(ticketRepository.findById(low.getId())).get().extracting(Ticket::getPriority).isEqualTo(TicketPriority.MEDIUM);
        assertThat(ticketRepository.findById(medium.getId())).get().extracting(Ticket::getPriority).isEqualTo(TicketPriority.HIGH);
        assertThat(ticketRepository.findById(high.getId())).get().extracting(Ticket::getPriority).isEqualTo(TicketPriority.CRITICAL);
        assertThat(ticketRepository.findById(crit.getId())).get().extracting(Ticket::getPriority).isEqualTo(TicketPriority.CRITICAL);
    }

    /** §3.7 — A second escalation run on the same overdue ticket is a no-op; escalatedAt set on first run makes the ticket ineligible until dueDate is updated. */
    @Test
    void escalationOnlyRunsOncePerDueDateEvent() {
        Ticket overdue = saveTicket("Escalate once", TicketStatus.TODO, TicketPriority.LOW, false, Instant.now().minusSeconds(60));

        ticketEscalationService.escalateOverdueTickets(); // LOW → MEDIUM, escalatedAt set
        ticketEscalationService.escalateOverdueTickets(); // ticket is now ineligible; skipped

        assertThat(ticketRepository.findById(overdue.getId())).get()
                .extracting(Ticket::getPriority).isEqualTo(TicketPriority.MEDIUM);
    }

    /** §3.7 — Manually changing priority on an already-escalated overdue ticket does NOT re-enable escalation; only a dueDate update opens a new escalation window. */
    @Test
    void manualPriorityChangeDoesNotReEnableEscalation() throws Exception {
        String token = login();
        Ticket overdue = saveTicket("Escalate then manual", TicketStatus.TODO, TicketPriority.LOW, false, Instant.now().minusSeconds(60));

        ticketEscalationService.escalateOverdueTickets(); // LOW → MEDIUM, escalatedAt set

        // Manually override back to LOW — escalatedAt must NOT be cleared
        mockMvc.perform(patch("/tickets/{ticketId}", overdue.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\": \"LOW\"}"))
                .andExpect(status().isOk());

        ticketEscalationService.escalateOverdueTickets(); // should be skipped — still escalated

        assertThat(ticketRepository.findById(overdue.getId())).get()
                .extracting(Ticket::getPriority).isEqualTo(TicketPriority.LOW);
    }

    private void addDependency(String token) throws Exception {
        mockMvc.perform(post("/tickets/{ticketId}/dependencies", ticket.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "blockedBy": %d
                                }
                                """.formatted(blocker.getId())))
                .andExpect(status().isOk());
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

    private Project saveProject() {
        Project savedProject = new Project();
        savedProject.setName("Project");
        savedProject.setDescription("Project description");
        savedProject.setOwner(admin);
        return projectRepository.save(savedProject);
    }

    private Ticket saveTicket(String title, TicketStatus status, TicketPriority priority, boolean deleted, Instant dueDate) {
        Ticket savedTicket = new Ticket();
        savedTicket.setTitle(title);
        savedTicket.setDescription(title + " description");
        savedTicket.setStatus(status);
        savedTicket.setPriority(priority);
        savedTicket.setType(TicketType.BUG);
        savedTicket.setProject(project);
        savedTicket.setAssignee(developer);
        savedTicket.setDueDate(dueDate);
        savedTicket.setDeleted(deleted);
        savedTicket.setDeletedAt(deleted ? Instant.now() : null);
        return ticketRepository.save(savedTicket);
    }
}
