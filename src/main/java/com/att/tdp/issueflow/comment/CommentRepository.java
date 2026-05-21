package com.att.tdp.issueflow.comment;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findAllByTicketIdOrderByCreatedAtAsc(Long ticketId);

    Optional<Comment> findByIdAndTicketId(Long id, Long ticketId);
}
