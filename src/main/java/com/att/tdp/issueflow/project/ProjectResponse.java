package com.att.tdp.issueflow.project;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        Long ownerId
) {

    static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getOwner().getId()
        );
    }
}
