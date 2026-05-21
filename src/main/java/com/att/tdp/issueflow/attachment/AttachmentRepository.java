package com.att.tdp.issueflow.attachment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    Optional<Attachment> findByIdAndTicketId(Long id, Long ticketId);

    void deleteAllByTicketId(Long ticketId);
}
