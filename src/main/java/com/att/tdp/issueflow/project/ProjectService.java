package com.att.tdp.issueflow.project;

import com.att.tdp.issueflow.common.ResourceNotFoundException;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditLogService;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final AuditLogService auditLogService;

    public ProjectService(
            ProjectRepository projectRepository,
            UserRepository userRepository,
            TicketRepository ticketRepository,
            AuditLogService auditLogService
    ) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.ticketRepository = ticketRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> getAllProjects() {
        return projectRepository.findAllByDeletedFalse()
                .stream()
                .map(ProjectResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(Long projectId) {
        return ProjectResponse.from(findActiveProject(projectId));
    }

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request) {
        User owner = userRepository.findById(request.ownerId())
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found"));

        Project project = new Project();
        project.setName(request.name());
        project.setDescription(request.description());
        project.setOwner(owner);

        Project saved = projectRepository.save(project);
        auditLogService.log(AuditAction.CREATE, AuditEntityType.PROJECT, saved.getId());
        return ProjectResponse.from(saved);
    }

    @Transactional
    public void updateProject(Long projectId, UpdateProjectRequest request) {
        Project project = findActiveProject(projectId);
        if (StringUtils.hasText(request.name())) {
            project.setName(request.name());
        }
        if (request.description() != null) {
            project.setDescription(request.description());
        }
        auditLogService.log(AuditAction.UPDATE, AuditEntityType.PROJECT, project.getId());
    }

    @Transactional
    public void softDeleteProject(Long projectId) {
        Project project = findActiveProject(projectId);
        project.setDeleted(true);
        project.setDeletedAt(Instant.now());
        auditLogService.log(AuditAction.DELETE, AuditEntityType.PROJECT, project.getId());
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> getDeletedProjects() {
        return projectRepository.findAllByDeletedTrue()
                .stream()
                .map(ProjectResponse::from)
                .toList();
    }

    @Transactional
    public void restoreProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        project.setDeleted(false);
        project.setDeletedAt(null);
        auditLogService.log(AuditAction.RESTORE, AuditEntityType.PROJECT, project.getId());
    }

    @Transactional(readOnly = true)
    public List<WorkloadResponse> getWorkload(Long projectId) {
        findActiveProject(projectId);
        return ticketRepository.findProjectWorkload(projectId)
                .stream()
                .map(row -> new WorkloadResponse(row.userId(), row.username(), row.openTicketCount()))
                .toList();
    }

    private Project findActiveProject(Long projectId) {
        return projectRepository.findByIdAndDeletedFalse(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
    }
}
