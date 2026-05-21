package com.att.tdp.issueflow.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findAllByDeletedFalse();

    List<Project> findAllByDeletedTrue();

    Optional<Project> findByIdAndDeletedFalse(Long id);
}
