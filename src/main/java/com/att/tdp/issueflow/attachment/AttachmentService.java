package com.att.tdp.issueflow.attachment;

import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditLogService;
import com.att.tdp.issueflow.common.BusinessRuleException;
import com.att.tdp.issueflow.common.ResourceNotFoundException;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AttachmentService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/png", "image/jpeg", "application/pdf", "text/plain"
    );

    private final AttachmentRepository attachmentRepository;
    private final TicketRepository ticketRepository;
    private final AuditLogService auditLogService;

    public AttachmentService(
            AttachmentRepository attachmentRepository,
            TicketRepository ticketRepository,
            AuditLogService auditLogService
    ) {
        this.attachmentRepository = attachmentRepository;
        this.ticketRepository = ticketRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public AttachmentResponse upload(Long ticketId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Attachment file is required");
        }
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new BusinessRuleException("File exceeds the 10 MB size limit");
        }
        // Normalise using Spring's parser to strip parameters (e.g. "text/plain; charset=utf-8" → "text/plain")
        // so the check is consistent regardless of how the client formats the Content-Type header.
        String rawContentType = file.getContentType();
        if (rawContentType == null) {
            throw new BusinessRuleException(
                    "Unsupported file type. Allowed: image/png, image/jpeg, application/pdf, text/plain"
            );
        }
        String contentType;
        try {
            MediaType parsed = MediaType.parseMediaType(rawContentType);
            contentType = parsed.getType() + "/" + parsed.getSubtype();
        } catch (InvalidMediaTypeException e) {
            throw new BusinessRuleException(
                    "Unsupported file type. Allowed: image/png, image/jpeg, application/pdf, text/plain"
            );
        }
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessRuleException(
                    "Unsupported file type. Allowed: image/png, image/jpeg, application/pdf, text/plain"
            );
        }
        Ticket ticket = ticketRepository.findByIdAndDeletedFalse(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
        try {
            Attachment attachment = new Attachment();
            attachment.setTicket(ticket);
            attachment.setFilename(file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename());
            attachment.setContentType(contentType); // store the normalised type
            attachment.setSize(file.getSize());
            attachment.setContent(file.getBytes());
            Attachment saved = attachmentRepository.save(attachment);
            auditLogService.log(AuditAction.CREATE, AuditEntityType.ATTACHMENT, saved.getId());
            return AttachmentResponse.from(saved);
        } catch (IOException exception) {
            throw new BusinessRuleException("Could not read attachment file");
        }
    }

    @Transactional
    public void delete(Long ticketId, Long attachmentId) {
        ticketRepository.findByIdAndDeletedFalse(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
        Attachment attachment = attachmentRepository.findByIdAndTicketId(attachmentId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found"));
        attachmentRepository.delete(attachment);
        auditLogService.log(AuditAction.DELETE, AuditEntityType.ATTACHMENT, attachmentId);
    }
}
