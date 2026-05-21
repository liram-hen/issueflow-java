package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.common.BusinessRuleException;
import com.att.tdp.issueflow.common.ForbiddenException;
import com.att.tdp.issueflow.common.ResourceNotFoundException;
import com.att.tdp.issueflow.user.UserRole;
import com.att.tdp.issueflow.audit.AuditAction;
import com.att.tdp.issueflow.audit.AuditEntityType;
import com.att.tdp.issueflow.audit.AuditLogService;
import com.att.tdp.issueflow.mention.Mention;
import com.att.tdp.issueflow.mention.MentionRepository;
import com.att.tdp.issueflow.ticket.Ticket;
import com.att.tdp.issueflow.ticket.TicketRepository;
import com.att.tdp.issueflow.user.User;
import com.att.tdp.issueflow.user.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentService {

    private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_]+)");

    private final CommentRepository commentRepository;
    private final MentionRepository mentionRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public CommentService(
            CommentRepository commentRepository,
            MentionRepository mentionRepository,
            TicketRepository ticketRepository,
            UserRepository userRepository,
            AuditLogService auditLogService
    ) {
        this.commentRepository = commentRepository;
        this.mentionRepository = mentionRepository;
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> getComments(Long ticketId) {
        findActiveTicket(ticketId);
        return commentRepository.findAllByTicketIdOrderByCreatedAtAsc(ticketId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CommentResponse addComment(Long ticketId, CreateCommentRequest request) {
        Ticket ticket = findActiveTicket(ticketId);
        User author = userRepository.findById(request.authorId())
                .orElseThrow(() -> new ResourceNotFoundException("Author not found"));

        Comment comment = new Comment();
        comment.setTicket(ticket);
        comment.setAuthor(author);
        comment.setContent(request.content());
        Comment saved = commentRepository.save(comment);
        persistMentions(saved, request.content());
        auditLogService.log(AuditAction.CREATE, AuditEntityType.COMMENT, saved.getId());

        return toResponse(saved);
    }

    @Transactional
    public void updateComment(Long ticketId, Long commentId, UpdateCommentRequest request,
                              Long requestingUserId, UserRole requestingRole) {
        findActiveTicket(ticketId);
        Comment comment = findComment(ticketId, commentId);
        if (requestingRole != UserRole.ADMIN && !comment.getAuthor().getId().equals(requestingUserId)) {
            throw new ForbiddenException("Only the comment author or an admin can edit this comment");
        }
        comment.setContent(request.content());
        mentionRepository.deleteAllByComment(comment);
        mentionRepository.flush();
        persistMentions(comment, request.content());
        auditLogService.log(AuditAction.UPDATE, AuditEntityType.COMMENT, comment.getId());
    }

    @Transactional
    public void deleteComment(Long ticketId, Long commentId) {
        findActiveTicket(ticketId);
        Comment comment = findComment(ticketId, commentId);
        mentionRepository.deleteAllByComment(comment);
        commentRepository.delete(comment);
        auditLogService.log(AuditAction.DELETE, AuditEntityType.COMMENT, commentId);
    }

    private Ticket findActiveTicket(Long ticketId) {
        return ticketRepository.findByIdAndDeletedFalse(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
    }

    private Comment findComment(Long ticketId, Long commentId) {
        return commentRepository.findByIdAndTicketId(commentId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment not found"));
    }

    private void persistMentions(Comment comment, String content) {
        for (User user : detectMentionedUsers(content)) {
            Mention mention = new Mention();
            mention.setComment(comment);
            mention.setMentionedUser(user);
            mentionRepository.save(mention);
        }
    }

    private List<User> detectMentionedUsers(String content) {
        Matcher matcher = MENTION_PATTERN.matcher(content);
        Map<String, User> usersByLowercaseUsername = new LinkedHashMap<>();
        while (matcher.find()) {
            String username = matcher.group(1);
            String key = username.toLowerCase(Locale.ROOT);
            if (!usersByLowercaseUsername.containsKey(key)) {
                User user = userRepository.findByUsernameIgnoreCase(username)
                        .orElseThrow(() -> new BusinessRuleException("Mentioned user not found: " + username));
                usersByLowercaseUsername.put(key, user);
            }
        }
        return new ArrayList<>(usersByLowercaseUsername.values());
    }

    private CommentResponse toResponse(Comment comment) {
        List<MentionedUserResponse> mentionedUsers = mentionRepository.findAllByCommentId(comment.getId())
                .stream()
                .map(mention -> MentionedUserResponse.from(mention.getMentionedUser()))
                .toList();

        return new CommentResponse(
                comment.getId(),
                comment.getTicket().getId(),
                comment.getAuthor().getId(),
                comment.getContent(),
                mentionedUsers
        );
    }
}
