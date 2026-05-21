package com.att.tdp.issueflow.ticket;

import com.att.tdp.issueflow.audit.AuditActor;
import com.att.tdp.issueflow.common.BusinessRuleException;
import com.att.tdp.issueflow.common.ResourceNotFoundException;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditLogService;
import com.att.tdp.issueflow.project.Project;
import com.att.tdp.issueflow.project.ProjectRepository;
import com.att.tdp.issueflow.ticket.TicketDependencyRepository;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import com.att.tdp.issueflow.user.UserRole;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final TicketDependencyRepository dependencyRepository;

    public TicketService(
            TicketRepository ticketRepository,
            ProjectRepository projectRepository,
            UserRepository userRepository,
            AuditLogService auditLogService,
            TicketDependencyRepository dependencyRepository
    ) {
        this.ticketRepository = ticketRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.dependencyRepository = dependencyRepository;
    }

    @Transactional(readOnly = true)
    public List<TicketResponse> getTicketsByProject(Long projectId) {
        findActiveProject(projectId);
        return ticketRepository.findAllByProjectIdAndDeletedFalse(projectId)
                .stream()
                .map(TicketResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TicketResponse getTicket(Long ticketId) {
        return TicketResponse.from(findActiveTicket(ticketId));
    }

    @Transactional
    public TicketResponse createTicket(CreateTicketRequest request) {
        Project project = findActiveProject(request.projectId());
        boolean autoAssigned = request.assigneeId() == null;
        User assignee = autoAssigned
                ? autoAssignDeveloper(request.projectId())
                : findUser(request.assigneeId(), "Assignee not found");

        Ticket ticket = new Ticket();
        ticket.setTitle(request.title());
        ticket.setDescription(request.description());
        ticket.setStatus(request.status());
        ticket.setPriority(request.priority());
        ticket.setType(request.type());
        ticket.setProject(project);
        ticket.setAssignee(assignee);
        ticket.setDueDate(request.dueDate());

        Ticket saved = ticketRepository.save(ticket);
        auditLogService.log(AuditAction.CREATE, AuditEntityType.TICKET, saved.getId());
        if (autoAssigned && saved.getAssignee() != null) {
            auditLogService.logSystem(AuditAction.AUTO_ASSIGN, AuditEntityType.TICKET, saved.getId());
        }
        return TicketResponse.from(saved);
    }

    @Transactional
    public void updateTicket(Long ticketId, UpdateTicketRequest request) {
        Ticket ticket = findActiveTicket(ticketId);
        if (ticket.getStatus() == TicketStatus.DONE) {
            throw new BusinessRuleException("DONE tickets cannot be updated");
        }

        if (request.status() != null) {
            validateForwardStatusMove(ticket.getStatus(), request.status());
            if (request.status() == TicketStatus.DONE) {
                validateNoUnresolvedBlockers(ticketId);
            }
            ticket.setStatus(request.status());
        }
        if (StringUtils.hasText(request.title())) {
            ticket.setTitle(request.title());
        }
        if (request.description() != null) {
            ticket.setDescription(request.description());
        }
        if (request.priority() != null) {
            ticket.setPriority(request.priority());
            ticket.setOverdue(false);
        }
        if (request.assigneeId() != null) {
            ticket.setAssignee(findUser(request.assigneeId(), "Assignee not found"));
        }
        if (request.dueDate() != null) {
            ticket.setDueDate(request.dueDate());
            ticket.setEscalatedAt(null);
        }
        auditLogService.log(AuditAction.UPDATE, AuditEntityType.TICKET, ticket.getId());
    }

    @Transactional
    public void deleteTicket(Long ticketId) {
        Ticket ticket = findActiveTicket(ticketId);
        ticket.setDeleted(true);
        ticket.setDeletedAt(Instant.now());
        auditLogService.log(AuditAction.DELETE, AuditEntityType.TICKET, ticket.getId());
    }

    @Transactional(readOnly = true)
    public List<TicketResponse> getDeletedTickets(Long projectId) {
        findActiveProject(projectId);
        return ticketRepository.findAllByProjectIdAndDeletedTrue(projectId)
                .stream()
                .map(TicketResponse::from)
                .toList();
    }

    @Transactional
    public void restoreTicket(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
        ticket.setDeleted(false);
        ticket.setDeletedAt(null);
        auditLogService.log(AuditAction.RESTORE, AuditEntityType.TICKET, ticket.getId());
    }

    private Project findActiveProject(Long projectId) {
        return projectRepository.findByIdAndDeletedFalse(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
    }

    private Ticket findActiveTicket(Long ticketId) {
        return ticketRepository.findByIdAndDeletedFalse(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
    }

    private User findUser(Long userId, String message) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(message));
    }

    private User autoAssignDeveloper(Long projectId) {
        return userRepository.findAllByRole(UserRole.DEVELOPER)
                .stream()
                .min(Comparator
                        .comparingLong((User user) -> ticketRepository.countByAssigneeIdAndProjectIdAndDeletedFalseAndStatusNot(
                                user.getId(),
                                projectId,
                                TicketStatus.DONE
                        ))
                        .thenComparing(User::getCreatedAt))
                .orElse(null);
    }

    private void validateForwardStatusMove(TicketStatus current, TicketStatus requested) {
        if (requested.ordinal() != current.ordinal() + 1) {
            throw new BusinessRuleException("Ticket status must move exactly one step forward: "
                    + current + " -> " + requested + " is not allowed");
        }
    }

    private void validateNoUnresolvedBlockers(Long ticketId) {
        boolean hasOpenBlockers = dependencyRepository.findAllByTicketId(ticketId)
                .stream()
                .map(TicketDependency::getBlockedBy)
                .anyMatch(blocker -> !blocker.isDeleted() && blocker.getStatus() != TicketStatus.DONE);
        if (hasOpenBlockers) {
            throw new BusinessRuleException("Cannot close ticket: it has unresolved blockers");
        }
    }
}
