package com.att.tdp.issueflow.comment;

import com.att.tdp.issueflow.user.UserRole;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tickets/{ticketId}/comments")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping
    public List<CommentResponse> getComments(@PathVariable Long ticketId) {
        return commentService.getComments(ticketId);
    }

    @PostMapping
    public CommentResponse addComment(
            @PathVariable Long ticketId,
            @Valid @RequestBody CreateCommentRequest request
    ) {
        return commentService.addComment(ticketId, request);
    }

    @PatchMapping("/{commentId}")
    public ResponseEntity<Void> updateComment(
            @PathVariable Long ticketId,
            @PathVariable Long commentId,
            @Valid @RequestBody UpdateCommentRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long requestingUserId = ((Number) jwt.getClaim("userId")).longValue();
        UserRole requestingRole = UserRole.valueOf(jwt.getClaimAsString("role"));
        commentService.updateComment(ticketId, commentId, request, requestingUserId, requestingRole);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long ticketId,
            @PathVariable Long commentId
    ) {
        commentService.deleteComment(ticketId, commentId);
        return ResponseEntity.ok().build();
    }
}
