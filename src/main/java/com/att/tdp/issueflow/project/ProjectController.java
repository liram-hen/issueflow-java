package com.att.tdp.issueflow.project;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public List<ProjectResponse> getAllProjects() {
        return projectService.getAllProjects();
    }

    @GetMapping("/{projectId}")
    public ProjectResponse getProject(@PathVariable Long projectId) {
        return projectService.getProject(projectId);
    }

    @PostMapping
    public ProjectResponse createProject(@Valid @RequestBody CreateProjectRequest request) {
        return projectService.createProject(request);
    }

    @PatchMapping("/{projectId}")
    public ResponseEntity<Void> updateProject(
            @PathVariable Long projectId,
            @Valid @RequestBody UpdateProjectRequest request
    ) {
        projectService.updateProject(projectId, request);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteProject(@PathVariable Long projectId) {
        projectService.softDeleteProject(projectId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/deleted")
    public List<ProjectResponse> getDeletedProjects() {
        return projectService.getDeletedProjects();
    }

    @PostMapping("/{projectId}/restore")
    public ResponseEntity<Void> restoreProject(@PathVariable Long projectId) {
        projectService.restoreProject(projectId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{projectId}/workload")
    public List<WorkloadResponse> getWorkload(@PathVariable Long projectId) {
        return projectService.getWorkload(projectId);
    }
}
