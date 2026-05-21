package com.att.tdp.issueflow.mention;

import com.att.tdp.issueflow.comment.Comment;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MentionRepository extends JpaRepository<Mention, Long> {

    List<Mention> findAllByCommentId(Long commentId);

    Page<Mention> findAllByMentionedUserIdOrderByCreatedAtDesc(Long mentionedUserId, Pageable pageable);

    void deleteAllByComment(Comment comment);
}
