package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditLogService;
import com.att.tdp.issueflow.common.BusinessRuleException;
import com.att.tdp.issueflow.common.ResourceNotFoundException;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.UserRole;
import java.io.IOException;
import java.util.Comparator;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TicketCsvService {

    private static final String[] HEADERS = {"id", "title", "description", "status", "priority", "type", "assigneeId"};

    private final TicketRepository ticketRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public TicketCsvService(
            TicketRepository ticketRepository,
            ProjectRepository projectRepository,
            UserRepository userRepository,
            AuditLogService auditLogService
    ) {
        this.ticketRepository = ticketRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public String exportTickets(Long projectId) {
        projectRepository.findByIdAndDeletedFalse(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        try {
            StringWriter writer = new StringWriter();
            try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(HEADERS).build())) {
                for (Ticket ticket : ticketRepository.findAllByProjectIdAndDeletedFalse(projectId)) {
                    printer.printRecord(
                            ticket.getId(),
                            ticket.getTitle(),
                            ticket.getDescription(),
                            ticket.getStatus(),
                            ticket.getPriority(),
                            ticket.getType(),
                            ticket.getAssignee() == null ? "" : ticket.getAssignee().getId()
                    );
                }
            }
            return writer.toString();
        } catch (IOException exception) {
            throw new BusinessRuleException("Could not export tickets");
        }
    }

    @Transactional
    public ImportTicketsResponse importTickets(MultipartFile file, Long projectId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("CSV file is required");
        }
        Project project = projectRepository.findByIdAndDeletedFalse(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));

        int created = 0;
        List<String> errors = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build()
                .parse(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            for (CSVRecord record : parser) {
                try {
                    boolean autoAssign = isBlank(record.get("assigneeId"));
                    Ticket ticket = parseTicket(record, project);
                    if (autoAssign) {
                        ticket.setAssignee(autoAssignDeveloper(project.getId()));
                    }
                    Ticket saved = ticketRepository.save(ticket);
                    auditLogService.log(AuditAction.CREATE, AuditEntityType.TICKET, saved.getId());
                    if (autoAssign && saved.getAssignee() != null) {
                        auditLogService.logSystem(AuditAction.AUTO_ASSIGN, AuditEntityType.TICKET, saved.getId());
                    }
                    created++;
                } catch (RuntimeException exception) {
                    errors.add("row " + record.getRecordNumber() + ": " + exception.getMessage());
                }
            }
            auditLogService.log(AuditAction.IMPORT, AuditEntityType.TICKET, projectId);
            return new ImportTicketsResponse(created, errors.size(), errors);
        } catch (IOException exception) {
            throw new BusinessRuleException("Could not read CSV file");
        }
    }

    private Ticket parseTicket(CSVRecord record, Project project) {
        Ticket ticket = new Ticket();
        ticket.setTitle(required(record, "title"));
        ticket.setDescription(record.get("description"));
        ticket.setStatus(TicketStatus.valueOf(required(record, "status")));
        ticket.setPriority(TicketPriority.valueOf(required(record, "priority")));
        ticket.setType(TicketType.valueOf(required(record, "type")));
        ticket.setProject(project);
        String assigneeId = record.get("assigneeId");
        if (assigneeId != null && !assigneeId.isBlank()) {
            User assignee = userRepository.findById(Long.valueOf(assigneeId))
                    .orElseThrow(() -> new ResourceNotFoundException("Assignee not found"));
            ticket.setAssignee(assignee);
        }
        return ticket;
    }

    private User autoAssignDeveloper(Long projectId) {
        return userRepository.findAllByRole(UserRole.DEVELOPER)
                .stream()
                .min(Comparator
                        .comparingLong((User u) -> ticketRepository.countByAssigneeIdAndProjectIdAndDeletedFalseAndStatusNot(
                                u.getId(), projectId, TicketStatus.DONE))
                        .thenComparing(User::getCreatedAt))
                .orElse(null);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String required(CSVRecord record, String name) {
        String value = record.get(name);
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException(name + " is required");
        }
        return value;
    }
}
