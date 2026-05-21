package com.att.tdp.issueflow.attachment;

public record AttachmentResponse(
        Long id,
        Long ticketId,
        String filename,
        String contentType
) {

    static AttachmentResponse from(Attachment attachment) {
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getTicket().getId(),
                attachment.getFilename(),
                attachment.getContentType()
        );
    }
}
